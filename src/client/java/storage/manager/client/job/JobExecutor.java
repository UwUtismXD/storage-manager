package storage.manager.client.job;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;

import storage.manager.StorageManager;
import storage.manager.client.baritone.BotNavigator;
import storage.manager.client.interact.ChestInteractor;
import storage.manager.client.storage.StorageIndex;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Drains {@link JobQueue} one job at a time on the client tick thread. Each job is expanded
 * into an ordered list of chest {@link Visit}s; SORT_INPUT appends its deposit visits
 * dynamically once it has actually seen what's in the input chest, since that can't be known
 * up front.
 */
public class JobExecutor {

    private enum VisitKind { SCAN, WITHDRAW_ITEM, DEPOSIT_ITEM, READ_INPUT, DEPOSIT_TO_OUTPUT }

    private record Visit(BlockPos pos, VisitKind kind, String itemId, List<BlockPos> triedChests) {
        private Visit(BlockPos pos, VisitKind kind, String itemId) {
            this(pos, kind, itemId, List.of());
        }
    }

    private enum Phase { PATHING, OPENING, ACTING, CLOSING }

    private static final double ARRIVE_RANGE = 3.5;
    private static final long PATH_TIMEOUT_TICKS = 20L * 60;   // 60s
    private static final long OPEN_TIMEOUT_TICKS = 20L * 5;    // 5s
    private static final long ACTING_TIMEOUT_TICKS = 20L * 10; // 10s - guards DEPOSIT_TO_OUTPUT's loop

    private final JobQueue queue;
    private final BotNavigator navigator = new BotNavigator();
    private final ChestInteractor interactor = new ChestInteractor();
    private final StorageIndex index;

    private Job currentJob;
    private Deque<Visit> plan;
    private Visit currentVisit;
    private Phase phase;
    private long phaseTicks;
    private boolean paused;
    private String lastWarning = "";

    public JobExecutor(JobQueue queue, StorageIndex index) {
        this.queue = queue;
        this.index = index;
    }

    public void pause() {
        if (!paused) {
            navigator.cancel();
        }
        paused = true;
    }

    public void resume() {
        paused = false;
    }

    public boolean isPaused() {
        return paused;
    }

    public String getStatus() {
        if (paused) {
            return "paused";
        }
        if (currentJob == null) {
            return "idle (" + queue.size() + " queued)";
        }
        return currentJob + " - " + phase;
    }

    public String getLastWarning() {
        return lastWarning;
    }

    private void warn(String message, Object... args) {
        StorageManager.LOGGER.warn(message, args);
        String formatted = message;
        for (Object arg : args) {
            formatted = formatted.replaceFirst("\\{}", java.util.regex.Matcher.quoteReplacement(String.valueOf(arg)));
        }
        lastWarning = formatted;
    }

    public void tick() {
        if (paused) {
            return;
        }
        if (currentJob == null) {
            currentJob = queue.poll();
            if (currentJob == null) {
                return;
            }
            plan = buildPlan(currentJob);
            if (!advanceVisit()) {
                finishJob();
            }
            return;
        }

        switch (phase) {
            case PATHING -> tickPathing();
            case OPENING -> tickOpening();
            case ACTING -> tickActing();
            case CLOSING -> tickClosing();
        }
    }

    private Deque<Visit> buildPlan(Job job) {
        Deque<Visit> plan = new ArrayDeque<>();
        switch (job.type) {
            case SCAN_REGION -> {
                discoverChestsInRegion();
                for (StorageIndex.ChestEntry chest : index.allChests()) {
                    plan.add(new Visit(chest.pos.toBlockPos(), VisitKind.SCAN, null));
                }
            }
            case SORT_INPUT -> {
                BlockPos input = index.getInputChest();
                if (input != null) {
                    plan.add(new Visit(input, VisitKind.READ_INPUT, null));
                }
            }
            case WITHDRAW -> {
                List<StorageIndex.Contribution> contributions = index.findItem(job.itemId, job.count);
                for (StorageIndex.Contribution c : contributions) {
                    plan.add(new Visit(c.chestPos(), VisitKind.WITHDRAW_ITEM, job.itemId));
                }
                BlockPos output = index.getOutputChest();
                if (!contributions.isEmpty() && output != null) {
                    plan.add(new Visit(output, VisitKind.DEPOSIT_TO_OUTPUT, job.itemId));
                }
            }
        }
        return plan;
    }

    private void discoverChestsInRegion() {
        Minecraft client = Minecraft.getInstance();
        ClientLevel world = client.level;
        StorageIndex.Region region = index.getRegion();
        if (world == null || region.min == null || region.max == null) {
            return;
        }
        BlockPos min = region.min.toBlockPos();
        BlockPos max = region.max.toBlockPos();
        // getBlockState() on an unloaded/ungenerated position just reads as air client-side,
        // so chests outside currently-visible chunks are silently skipped rather than crashing -
        // they're picked up the next time the bot passes near them during a scan.
        for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
            BlockState state = world.getBlockState(pos);
            Block block = state.getBlock();
            if (block instanceof ChestBlock && state.getValue(ChestBlock.TYPE) == ChestType.RIGHT) {
                // The other half of a double chest - opening either half opens the same combined
                // inventory, so only the LEFT/SINGLE half is registered to avoid visiting it twice.
                // Also purges any stale duplicate entry left over from before this check existed.
                index.removeChest(pos);
                continue;
            }
            if (block instanceof ChestBlock || block instanceof BarrelBlock || block instanceof ShulkerBoxBlock) {
                index.registerEmptyChest(pos.immutable(), BuiltInRegistries.BLOCK.getKey(block).toString());
            }
        }
    }

    private boolean isContainerBlock(BlockPos pos) {
        ClientLevel world = Minecraft.getInstance().level;
        if (world == null) {
            return true; // can't verify right now - don't falsely drop a real chest from the index
        }
        Block block = world.getBlockState(pos).getBlock();
        return block instanceof ChestBlock || block instanceof BarrelBlock || block instanceof ShulkerBoxBlock;
    }

    /** Pops the next visit and starts pathing to it. Returns false if the plan is empty. */
    private boolean advanceVisit() {
        if (plan == null || plan.isEmpty()) {
            return false;
        }
        currentVisit = plan.poll();
        navigator.goTo(currentVisit.pos());
        phase = Phase.PATHING;
        phaseTicks = 0;
        return true;
    }

    private void tickPathing() {
        phaseTicks++;
        if (navigator.hasArrived(currentVisit.pos(), ARRIVE_RANGE)) {
            // Stop Baritone's own movement/look/sneak control before we try to interact - if it's
            // still actively adjusting position (its own GoalNear radius is tighter than
            // ARRIVE_RANGE) our interact can lose to whatever input it's still holding, e.g. a
            // sneak override used for edge safety, which makes right-click try to place a held
            // item instead of opening the container.
            navigator.cancel();
            if (!isContainerBlock(currentVisit.pos())) {
                // Whatever was here got broken/moved since the last scan - drop it from the index
                // instead of trying (and timing out) forever on a chest that no longer exists.
                warn("No container at {} anymore, removing from index", currentVisit.pos());
                index.removeChest(currentVisit.pos());
                nextVisitOrFinish();
                return;
            }
            interactor.open(currentVisit.pos());
            phase = Phase.OPENING;
            phaseTicks = 0;
            return;
        }
        if (!navigator.isBusy()) {
            // Path finished (or failed) short of the goal - try once more before giving up.
            navigator.goTo(currentVisit.pos());
        }
        if (phaseTicks > PATH_TIMEOUT_TICKS) {
            warn("Timed out walking to {}, skipping", currentVisit.pos());
            navigator.cancel();
            nextVisitOrFinish();
        }
    }

    private void tickOpening() {
        phaseTicks++;
        if (interactor.isOpen()) {
            phase = Phase.ACTING;
            phaseTicks = 0;
            return;
        }
        // The first attempt can lose a race with leftover Baritone input state - retry rather
        // than only trying once and waiting out the full timeout.
        if (phaseTicks % 10 == 0) {
            interactor.open(currentVisit.pos());
        }
        if (phaseTicks > OPEN_TIMEOUT_TICKS) {
            warn("Chest at {} never opened, skipping", currentVisit.pos());
            nextVisitOrFinish();
        }
    }

    private void tickActing() {
        phaseTicks++;
        switch (currentVisit.kind()) {
            case SCAN -> {
                recordSnapshot(currentVisit.pos());
                phase = Phase.CLOSING;
            }
            case WITHDRAW_ITEM -> {
                int slot = interactor.findContainerSlot(currentVisit.itemId());
                if (slot >= 0) {
                    interactor.quickMove(slot);
                }
                recordSnapshot(currentVisit.pos());
                phase = Phase.CLOSING;
            }
            case DEPOSIT_ITEM -> {
                int slot = interactor.findPlayerSlotWithItem(currentVisit.itemId());
                if (slot >= 0) {
                    interactor.quickMove(slot);
                    if (interactor.findPlayerSlotWithItem(currentVisit.itemId()) >= 0) {
                        // Still holding some (or all) of it - this chest had no room. Try the
                        // next candidate instead of quietly leaving it stuck in the bot's inventory.
                        List<BlockPos> tried = new ArrayList<>(currentVisit.triedChests());
                        tried.add(currentVisit.pos());
                        BlockPos next = index.depositCandidates(currentVisit.itemId()).stream()
                                .filter(p -> !tried.contains(p))
                                .findFirst().orElse(null);
                        if (next != null) {
                            plan.addFirst(new Visit(next, VisitKind.DEPOSIT_ITEM, currentVisit.itemId(), tried));
                        } else {
                            warn("No chest had room for {}, leaving it in the bot's inventory", currentVisit.itemId());
                        }
                    }
                }
                recordSnapshot(currentVisit.pos());
                phase = Phase.CLOSING;
            }
            case DEPOSIT_TO_OUTPUT -> {
                int slot = interactor.findPlayerSlotWithItem(currentVisit.itemId());
                if (slot < 0) {
                    recordSnapshot(currentVisit.pos());
                    phase = Phase.CLOSING;
                } else if (phaseTicks > ACTING_TIMEOUT_TICKS) {
                    // Still holding some after 10s - most likely the output chest is full.
                    warn("Could not fully deposit {} at {}, output chest may be full",
                            currentVisit.itemId(), currentVisit.pos());
                    recordSnapshot(currentVisit.pos());
                    phase = Phase.CLOSING;
                } else {
                    interactor.quickMove(slot);
                    // Stay in ACTING until no more of this item is left to deposit.
                }
            }
            case READ_INPUT -> {
                List<StorageIndex.SlotEntry> contents = interactor.snapshotContainerSlots();
                int capacity = interactor.countFreePlayerSlots();
                int taken = 0;
                for (StorageIndex.SlotEntry entry : contents) {
                    if (taken >= capacity) {
                        // Input chest (e.g. a double chest) has more distinct stacks than the bot
                        // can carry at once - grab what fits now and queue another sort pass for
                        // the rest instead of trying to quick-move into a full inventory.
                        break;
                    }
                    // Actually pull it out of the input chest into the bot's own inventory -
                    // reading the snapshot alone never removed anything.
                    interactor.quickMove(entry.slot);
                    taken++;
                    for (BlockPos dest : index.depositCandidates(entry.item)) {
                        plan.add(new Visit(dest, VisitKind.DEPOSIT_ITEM, entry.item));
                        break;
                    }
                }
                if (taken < contents.size()) {
                    queue.enqueue(Job.sortInput());
                }
                recordSnapshot(currentVisit.pos());
                phase = Phase.CLOSING;
            }
        }
    }

    private void recordSnapshot(BlockPos pos) {
        Minecraft client = Minecraft.getInstance();
        String type = "container";
        if (client.level != null) {
            type = BuiltInRegistries.BLOCK.getKey(client.level.getBlockState(pos).getBlock()).toString();
        }
        index.upsertChest(pos, type, interactor.snapshotContainerSlots(), System.currentTimeMillis());
    }

    private void tickClosing() {
        interactor.close();
        nextVisitOrFinish();
    }

    private void nextVisitOrFinish() {
        if (!advanceVisit()) {
            finishJob();
        }
    }

    private void finishJob() {
        currentJob = null;
        plan = null;
        currentVisit = null;
    }
}
