package storage.manager.client.job;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
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
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Drains {@link JobQueue} one job at a time on the client tick thread. Each job is expanded
 * into an ordered list of chest {@link Visit}s; SORT_INPUT appends its deposit visits
 * dynamically once it has actually seen what's in the input chest, since that can't be known
 * up front.
 */
public class JobExecutor {

    private enum VisitKind {
        SCAN, WITHDRAW_ITEM, DEPOSIT_ITEM, READ_INPUT, READ_INPUT_RANDOM,
        DEPOSIT_TO_OUTPUT, TAKE_ALL, DEPOSIT_RANDOM
    }

    /** {@code slot} is -1 unless the visit targets one specific container slot (exact withdrawals). */
    private record Visit(BlockPos pos, VisitKind kind, String itemId, List<BlockPos> triedChests, int slot) {
        private Visit(BlockPos pos, VisitKind kind, String itemId) {
            this(pos, kind, itemId, List.of(), -1);
        }

        private Visit(BlockPos pos, VisitKind kind, String itemId, List<BlockPos> triedChests) {
            this(pos, kind, itemId, triedChests, -1);
        }
    }

    private enum Phase { PATHING, OPENING, ACTING }

    private static final double ARRIVE_RANGE = 3.5;
    private static final long PATH_TIMEOUT_TICKS = 20L * 60;   // 60s
    private static final long OPEN_TIMEOUT_TICKS = 20L * 5;    // 5s
    private static final long ACTING_TIMEOUT_TICKS = 20L * 10; // 10s - guards DEPOSIT_TO_OUTPUT's loop
    /** Upper bound on quick-moves in one deposit visit - the bot can't carry more than this. */
    private static final int MAX_DEPOSITS_PER_VISIT = 36;
    private static final long RESERVED_REFRESH_TICKS = 20L; // 1s

    /** Idle-stroll pacing. Randomized between these so it doesn't read as clockwork. */
    private static final long WANDER_MIN_IDLE_TICKS = 20L * 120; // 2min
    private static final long WANDER_MAX_IDLE_TICKS = 20L * 300; // 5min
    /** Baritone takes a moment to start pathing; don't read "not pathing yet" as "arrived". */
    private static final long WANDER_START_GRACE_TICKS = 40L;
    private static final long WANDER_TIMEOUT_TICKS = 20L * 60;

    private final JobQueue queue;
    private final BotNavigator navigator = new BotNavigator();
    private final ChestInteractor interactor = new ChestInteractor();
    private final StorageIndex index;

    /**
     * Resolved positions of the configured input/output chests, both halves of each. Published for
     * the web UI, which needs to label them but runs on HTTP threads where world access isn't safe.
     */
    public record ReservedChests(List<StorageIndex.Pos> input, List<StorageIndex.Pos> output) {
        /** Both roles flattened to block positions, for "is this chest spoken for" checks. */
        public Set<BlockPos> positions() {
            Set<BlockPos> all = new HashSet<>();
            for (StorageIndex.Pos pos : input) {
                all.add(pos.toBlockPos());
            }
            for (StorageIndex.Pos pos : output) {
                all.add(pos.toBlockPos());
            }
            return all;
        }
    }

    private volatile ReservedChests reservedSnapshot = new ReservedChests(List.of(), List.of());
    private long ticksSinceReservedRefresh;

    private Job currentJob;
    private Deque<Visit> plan;
    private Visit currentVisit;
    private Phase phase;
    private long phaseTicks;
    private boolean paused;
    private String lastWarning = "";

    private volatile boolean wanderEnabled = true;
    private boolean wandering;
    private long idleTicks;
    private long wanderTicks;
    private long nextWanderTicks = WANDER_MIN_IDLE_TICKS;

    public JobExecutor(JobQueue queue, StorageIndex index) {
        this.queue = queue;
        this.index = index;
    }

    public void pause() {
        if (!paused) {
            stopWandering();
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
            return "idle (" + queue.size() + " queued)" + (wandering ? " - wandering" : "");
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

    public ReservedChests reservedChestPositions() {
        return reservedSnapshot;
    }

    public void tick() {
        // Refreshed on a timer rather than only while jobs run, so the chest view can label the
        // input/output chests even when the bot is idle or paused. Costs a couple of block lookups.
        if (++ticksSinceReservedRefresh >= RESERVED_REFRESH_TICKS) {
            ticksSinceReservedRefresh = 0;
            reservedChests();
        }
        if (paused) {
            return;
        }
        if (currentJob == null) {
            currentJob = queue.poll();
            if (currentJob == null) {
                tickIdle();
                return;
            }
            // Real work turned up - abandon any stroll before planning, so Baritone isn't still
            // heading somewhere else when the first visit starts.
            stopWandering();
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
        }
    }

    /**
     * Sends the bot on a stroll to a random known chest every few minutes while there's nothing to
     * do, so it doesn't stand frozen in one spot. Targets are chests it has already been to rather
     * than random points in the region box - those are known-reachable, and it reads as the bot
     * looking around its own storage rather than walking into a wall.
     *
     * <p>Deliberately not a queued {@link Job}: strolling should never delay real work or show up
     * in the queue, and any job arriving cancels it mid-path.
     */
    private void tickIdle() {
        if (wandering) {
            wanderTicks++;
            boolean arrived = wanderTicks > WANDER_START_GRACE_TICKS && !navigator.isBusy();
            if (arrived || wanderTicks > WANDER_TIMEOUT_TICKS) {
                stopWandering();
            }
            return;
        }
        if (!wanderEnabled) {
            return;
        }
        if (++idleTicks < nextWanderTicks) {
            return;
        }
        BlockPos target = index.randomStorageChests(Set.of()).stream().findFirst().orElse(null);
        if (target == null) {
            idleTicks = 0; // nothing indexed to stroll to yet - check again after another interval
            return;
        }
        navigator.goTo(target);
        wandering = true;
        wanderTicks = 0;
    }

    /** Ends any stroll (cancelling the path) and re-arms the idle timer. */
    private void stopWandering() {
        if (wandering) {
            // cancel() also releases keys Baritone may still be holding, which matters before the
            // next chest open - a forced sneak turns right-click into a block placement.
            navigator.cancel();
            wandering = false;
        }
        idleTicks = 0;
        wanderTicks = 0;
        nextWanderTicks = ThreadLocalRandom.current()
                .nextLong(WANDER_MIN_IDLE_TICKS, WANDER_MAX_IDLE_TICKS + 1);
    }

    public boolean isWanderEnabled() {
        return wanderEnabled;
    }

    public void setWanderEnabled(boolean enabled) {
        wanderEnabled = enabled;
    }

    private Deque<Visit> buildPlan(Job job) {
        Deque<Visit> plan = new ArrayDeque<>();
        switch (job.type) {
            case SCAN_REGION -> {
                discoverChestsInRegion();
                // Discovery always runs (it's cheap and finds newly-placed chests); the freshness
                // bound only decides which chests are worth walking to and opening again.
                long cutoff = job.maxAgeMillis > 0
                        ? System.currentTimeMillis() - job.maxAgeMillis
                        : Long.MAX_VALUE;
                List<BlockPos> targets = new ArrayList<>();
                for (StorageIndex.ChestEntry chest : index.allChests()) {
                    if (chest.lastScanned < cutoff) {
                        targets.add(chest.pos.toBlockPos());
                    }
                }
                for (BlockPos pos : scanOrder(targets)) {
                    plan.add(new Visit(pos, VisitKind.SCAN, null));
                }
            }
            case SORT_INPUT, SORT_INPUT_RANDOM -> {
                BlockPos input = index.getInputChest();
                if (input != null) {
                    VisitKind kind = job.type == Job.Type.SORT_INPUT_RANDOM
                            ? VisitKind.READ_INPUT_RANDOM
                            : VisitKind.READ_INPUT;
                    plan.add(new Visit(input, kind, null));
                }
            }
            case WITHDRAW -> {
                List<StorageIndex.Contribution> contributions = index.findItem(job.itemId, job.count, reservedChests());
                for (StorageIndex.Contribution c : contributions) {
                    plan.add(new Visit(c.chestPos(), VisitKind.WITHDRAW_ITEM, job.itemId));
                }
                BlockPos output = index.getOutputChest();
                if (!contributions.isEmpty() && output != null) {
                    plan.add(new Visit(output, VisitKind.DEPOSIT_TO_OUTPUT, job.itemId));
                }
            }
            case WITHDRAW_SLOT -> {
                if (job.sourceChest != null) {
                    plan.add(new Visit(job.sourceChest, VisitKind.WITHDRAW_ITEM, job.itemId,
                            List.of(), job.sourceSlot));
                    BlockPos output = index.getOutputChest();
                    if (output != null) {
                        plan.add(new Visit(output, VisitKind.DEPOSIT_TO_OUTPUT, job.itemId));
                    } else {
                        warn("No output chest configured - {} will stay in the bot's inventory", job.itemId);
                    }
                }
            }
            case RANDOMIZE -> {
                // One pass over every storage chest, in random order. Each TAKE_ALL visit
                // appends its own deposit visits as it goes, since what's in a chest (and how
                // much the bot can carry) isn't knowable until it's actually open.
                for (BlockPos pos : index.randomStorageChests(reservedChests())) {
                    plan.add(new Visit(pos, VisitKind.TAKE_ALL, null));
                }
            }
        }
        return plan;
    }

    /**
     * Orders scan targets bottom-up: every chest on the lowest Y layer first, then the next layer
     * up, and so on. Within a layer the bot walks to whichever chest is nearest, continuing from
     * where the previous layer left off.
     *
     * <p>Without this the plan follows the index's insertion order, which comes from
     * {@link BlockPos#betweenClosed} - that varies X fastest, then Y, then Z, so a tall storage
     * room gets covered as full-height columns. The bot climbs 20 blocks, drops back down, and
     * climbs again once per column.
     */
    private List<BlockPos> scanOrder(List<BlockPos> targets) {
        Map<Integer, List<BlockPos>> byLayer = new TreeMap<>();
        for (BlockPos pos : targets) {
            byLayer.computeIfAbsent(pos.getY(), y -> new ArrayList<>()).add(pos);
        }
        LocalPlayer player = Minecraft.getInstance().player;
        BlockPos cursor = player != null ? player.blockPosition() : BlockPos.ZERO;
        List<BlockPos> ordered = new ArrayList<>();
        for (List<BlockPos> layer : byLayer.values()) {
            List<BlockPos> remaining = new ArrayList<>(layer);
            while (!remaining.isEmpty()) {
                BlockPos from = cursor;
                BlockPos nearest = Collections.min(remaining, Comparator.comparingDouble(p -> p.distSqr(from)));
                remaining.remove(nearest);
                ordered.add(nearest);
                cursor = nearest;
            }
        }
        return ordered;
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

    /**
     * The chests a RANDOMIZE pass must not touch: the configured input and output chests.
     *
     * <p>Both halves of a double chest are returned, because the two sides are recorded in
     * different places. {@link #discoverChestsInRegion()} only ever indexes the LEFT/SINGLE
     * half, while the setup form stores whichever half the user happened to read off F3 - so
     * comparing the configured position against the index directly misses the output chest
     * entirely whenever those two disagree.
     */
    private Set<BlockPos> reservedChests() {
        reservedSnapshot = new ReservedChests(
                chestHalves(index.getInputChest()), chestHalves(index.getOutputChest()));
        return reservedSnapshot.positions();
    }

    /** A chest position plus its other half if it's a double chest, else just the position. */
    private List<StorageIndex.Pos> chestHalves(BlockPos pos) {
        if (pos == null) {
            return List.of();
        }
        List<StorageIndex.Pos> halves = new ArrayList<>();
        halves.add(new StorageIndex.Pos(pos));
        BlockPos otherHalf = otherChestHalf(pos);
        if (otherHalf != null) {
            halves.add(new StorageIndex.Pos(otherHalf));
        }
        return halves;
    }

    /** The other half of the double chest at {@code pos}, or null if it's not one. */
    private BlockPos otherChestHalf(BlockPos pos) {
        ClientLevel world = Minecraft.getInstance().level;
        if (world == null) {
            return null;
        }
        BlockState state = world.getBlockState(pos);
        if (!(state.getBlock() instanceof ChestBlock) || state.getValue(ChestBlock.TYPE) == ChestType.SINGLE) {
            return null;
        }
        return pos.relative(ChestBlock.getConnectedDirection(state));
    }

    private boolean isContainerBlock(BlockPos pos) {
        ClientLevel world = Minecraft.getInstance().level;
        if (world == null) {
            return true; // can't verify right now - don't falsely drop a real chest from the index
        }
        Block block = world.getBlockState(pos).getBlock();
        return block instanceof ChestBlock || block instanceof BarrelBlock || block instanceof ShulkerBoxBlock;
    }

    /**
     * Pops the next visit and starts it. Returns false if the plan is empty.
     *
     * <p>A storage room is mostly chests already within arm's reach of each other, so the next
     * target is usually reachable from where the last one left us. Those skip pathing entirely -
     * previously every visit issued a Baritone goal that {@link #tickPathing()} cancelled a tick
     * later, which meant starting and aborting a path calculation per chest without moving.
     */
    private boolean advanceVisit() {
        while (plan != null && !plan.isEmpty()) {
            currentVisit = plan.poll();
            phaseTicks = 0;
            if (!navigator.hasArrived(currentVisit.pos(), ARRIVE_RANGE)) {
                navigator.goTo(currentVisit.pos());
                phase = Phase.PATHING;
                return true;
            }
            if (openCurrentVisit()) {
                return true;
            }
            // Container's gone - it's already been dropped from the index, so try the next visit.
        }
        return false;
    }

    /**
     * Sends the open for the current visit, now that we're in range. Returns false if the block
     * turned out not to be a container anymore, in which case it's dropped from the index.
     */
    private boolean openCurrentVisit() {
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
            return false;
        }
        interactor.open(currentVisit.pos());
        phase = Phase.OPENING;
        phaseTicks = 0;
        return true;
    }

    private void tickPathing() {
        phaseTicks++;
        if (navigator.hasArrived(currentVisit.pos(), ARRIVE_RANGE)) {
            if (!openCurrentVisit()) {
                nextVisitOrFinish();
            }
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
        // than only trying once and waiting out the full timeout. Every 4 ticks (5/s, about as
        // fast as a person clicks) rather than every 10, so a dropped open costs 200ms not 500ms.
        if (phaseTicks % 4 == 0) {
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
                finishVisit();
            }
            case WITHDRAW_ITEM -> {
                int slot = currentVisit.slot();
                // An exact request names the slot the user picked, but the index is only as fresh
                // as the last time the bot opened this chest - if the slot no longer holds what we
                // expect, fall back to finding the item anywhere in here.
                if (slot < 0 || !currentVisit.itemId().equals(interactor.containerItemAt(slot))) {
                    slot = interactor.findContainerSlot(currentVisit.itemId());
                }
                if (slot >= 0) {
                    interactor.quickMove(slot);
                }
                recordSnapshot(currentVisit.pos());
                finishVisit();
            }
            case DEPOSIT_ITEM, DEPOSIT_RANDOM -> {
                String itemId = currentVisit.itemId();
                // Sorting consolidates every stack of this item into one chest; a shuffle places
                // exactly one, so the remaining stacks can land somewhere else.
                boolean depositAll = currentVisit.kind() == VisitKind.DEPOSIT_ITEM;
                boolean chestFull = false;
                for (int move = 0; move < MAX_DEPOSITS_PER_VISIT; move++) {
                    int before = interactor.countPlayerItems(itemId);
                    if (before == 0) {
                        break;
                    }
                    int slot = interactor.findPlayerSlotWithItem(itemId);
                    if (slot < 0) {
                        break;
                    }
                    interactor.quickMove(slot);
                    if (interactor.countPlayerItems(itemId) == before) {
                        // Nothing moved, so this chest really is full. The old test asked "am I
                        // still holding any of this?" - which was also true whenever the bot simply
                        // carried more than one stack of the item, so every extra stack faked a
                        // full chest and sent the bot off to open another one for nothing.
                        chestFull = true;
                        break;
                    }
                    if (!depositAll) {
                        break;
                    }
                }
                if (chestFull && interactor.countPlayerItems(itemId) > 0) {
                    List<BlockPos> tried = new ArrayList<>(currentVisit.triedChests());
                    tried.add(currentVisit.pos());
                    // A randomized deposit keeps picking at random on retry; a sorted one keeps
                    // preferring chests that already hold the item.
                    Set<BlockPos> reserved = reservedChests();
                    List<BlockPos> candidates = currentVisit.kind() == VisitKind.DEPOSIT_RANDOM
                            ? index.randomStorageChests(reserved)
                            : index.depositCandidates(itemId, reserved);
                    BlockPos next = candidates.stream()
                            .filter(p -> !tried.contains(p))
                            .findFirst().orElse(null);
                    if (next != null) {
                        plan.addFirst(new Visit(next, currentVisit.kind(), itemId, tried));
                    } else {
                        warn("No chest had room for {}, leaving it in the bot's inventory", itemId);
                    }
                }
                recordSnapshot(currentVisit.pos());
                finishVisit();
            }
            case TAKE_ALL -> {
                BlockPos source = currentVisit.pos();
                List<StorageIndex.SlotEntry> contents = interactor.snapshotContainerSlots();
                int capacity = interactor.countFreePlayerSlots();
                Set<BlockPos> reserved = reservedChests();
                List<Visit> followUps = new ArrayList<>();
                int taken = 0;
                for (StorageIndex.SlotEntry entry : contents) {
                    if (taken >= capacity) {
                        break;
                    }
                    // Pick the destination before pulling the stack out - if there's nowhere else
                    // to put it, leave it where it is rather than stranding it in the bot's inventory.
                    BlockPos dest = index.randomStorageChests(reserved).stream()
                            .filter(p -> !p.equals(source))
                            .findFirst().orElse(null);
                    if (dest == null) {
                        warn("Nowhere to shuffle {} to - it needs at least two storage chests", entry.item);
                        break;
                    }
                    interactor.quickMove(entry.slot);
                    taken++;
                    // Seeding triedChests with the source stops the full-chest retry above from
                    // handing the stack straight back to the chest it just came out of.
                    followUps.add(new Visit(dest, VisitKind.DEPOSIT_RANDOM, entry.item, List.of(source)));
                }
                if (taken > 0 && taken < contents.size()) {
                    // More stacks than the bot can carry in one trip - come back once it's empty again.
                    followUps.add(new Visit(source, VisitKind.TAKE_ALL, null));
                }
                // These have to run before the next chest's TAKE_ALL, otherwise the bot turns up
                // there with a full inventory and can't pick anything up.
                for (int i = followUps.size() - 1; i >= 0; i--) {
                    plan.addFirst(followUps.get(i));
                }
                recordSnapshot(source);
                finishVisit();
            }
            case DEPOSIT_TO_OUTPUT -> {
                int slot = interactor.findPlayerSlotWithItem(currentVisit.itemId());
                if (slot < 0) {
                    recordSnapshot(currentVisit.pos());
                    finishVisit();
                } else if (phaseTicks > ACTING_TIMEOUT_TICKS) {
                    // Still holding some after 10s - most likely the output chest is full.
                    warn("Could not fully deposit {} at {}, output chest may be full",
                            currentVisit.itemId(), currentVisit.pos());
                    recordSnapshot(currentVisit.pos());
                    finishVisit();
                } else {
                    interactor.quickMove(slot);
                    // Stay in ACTING until no more of this item is left to deposit.
                }
            }
            case READ_INPUT, READ_INPUT_RANDOM -> {
                // Scattering sends each stack to its own random chest, so nothing becomes the one
                // chest holding all the diamonds. Sorting instead consolidates each item together.
                boolean scatter = currentVisit.kind() == VisitKind.READ_INPUT_RANDOM;
                List<StorageIndex.SlotEntry> contents = interactor.snapshotContainerSlots();
                int capacity = interactor.countFreePlayerSlots();
                Set<BlockPos> reserved = reservedChests();
                // Shuffled once and drawn from in order, so no two stacks in this pass land in the
                // same chest - picking a fresh random chest per stack could hand out duplicates.
                Deque<BlockPos> scatterTargets = scatter
                        ? new ArrayDeque<>(index.randomStorageChests(reserved))
                        : new ArrayDeque<>();
                Set<String> takenItems = new LinkedHashSet<>();
                boolean outOfSpace = false;
                int taken = 0;
                for (StorageIndex.SlotEntry entry : contents) {
                    if (taken >= capacity) {
                        // Input chest (e.g. a double chest) has more distinct stacks than the bot
                        // can carry at once - grab what fits now and queue another sort pass for
                        // the rest instead of trying to quick-move into a full inventory.
                        outOfSpace = true;
                        break;
                    }
                    // Confirm there's somewhere to put it before pulling it out - with the
                    // input/output chests now excluded, a region that hasn't been scanned yet has
                    // no candidates at all, and taking it anyway would strand it in the inventory.
                    if (scatter ? scatterTargets.isEmpty()
                            : index.depositCandidates(entry.item, reserved).isEmpty()) {
                        warn("No storage chest to sort {} into - rescan the region first", entry.item);
                        break;
                    }
                    // Actually pull it out of the input chest into the bot's own inventory -
                    // reading the snapshot alone never removed anything.
                    interactor.quickMove(entry.slot);
                    taken++;
                    if (scatter) {
                        // One visit per stack, each to its own chest - the whole point here.
                        plan.add(new Visit(scatterTargets.poll(), VisitKind.DEPOSIT_RANDOM, entry.item));
                    } else {
                        takenItems.add(entry.item);
                    }
                }
                // One visit per distinct item, not per stack. DEPOSIT_ITEM now unloads every stack
                // of an item in a single visit, so queueing one per slot just sent the bot to walk
                // to, open, and close extra chests after the items had already been put away.
                for (String item : takenItems) {
                    BlockPos dest = index.depositCandidates(item, reserved).stream()
                            .findFirst().orElse(null);
                    if (dest != null) {
                        plan.add(new Visit(dest, VisitKind.DEPOSIT_ITEM, item));
                    }
                }
                // Only chase the leftovers when this pass actually shifted something, otherwise a
                // bot that's already full (capacity 0) re-queues SORT_INPUT forever without moving
                // a single item.
                if (outOfSpace && taken > 0) {
                    queue.enqueue(scatter ? Job.sortInputRandom() : Job.sortInput());
                }
                recordSnapshot(currentVisit.pos());
                finishVisit();
            }
        }
    }

    private void recordSnapshot(BlockPos pos) {
        Minecraft client = Minecraft.getInstance();
        String type = "container";
        if (client.level != null) {
            type = BuiltInRegistries.BLOCK.getKey(client.level.getBlockState(pos).getBlock()).toString();
        }
        // Both reads have to happen while the container is still open - callers run this during
        // ACTING, before finishVisit() sends the close.
        index.upsertChest(pos, type, interactor.containerSlotCount(),
                interactor.snapshotContainerSlots(), System.currentTimeMillis());
    }

    /**
     * Closes the container and moves straight on to the next visit, in the same tick. This used to
     * be a CLOSING phase of its own, which cost an extra tick per chest for no benefit - every
     * caller has already finished reading the container by the time it gets here.
     */
    private void finishVisit() {
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
        // Index writes are debounced, but a just-finished job is a natural point to persist at
        // rather than leaving the last few chests riding on the background flush.
        index.flush();
    }
}
