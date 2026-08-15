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
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
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
        SCAN, WITHDRAW_ITEM, DEPOSIT_ITEM, DEPOSIT_BATCH, READ_INPUT, READ_INPUT_RANDOM,
        DEPOSIT_TO_OUTPUT, SHUFFLE_CHEST, PLANNED_SHUFFLE, DUMP_ALL
    }

    /**
     * {@code items} is empty for kinds that don't target one, holds a single entry for the
     * item-at-a-time kinds, and one entry per stack for DEPOSIT_BATCH. {@code slot} is -1 unless
     * the visit targets one specific container slot (exact withdrawals).
     */
    private record Visit(BlockPos pos, VisitKind kind, List<String> items, List<BlockPos> triedChests, int slot) {

        private Visit(BlockPos pos, VisitKind kind) {
            this(pos, kind, List.of(), List.of(), -1);
        }

        private Visit(BlockPos pos, VisitKind kind, String item) {
            this(pos, kind, List.of(item), List.of(), -1);
        }

        private Visit(BlockPos pos, VisitKind kind, String item, int slot) {
            this(pos, kind, List.of(item), List.of(), slot);
        }

        private Visit(BlockPos pos, VisitKind kind, List<String> items, List<BlockPos> triedChests) {
            this(pos, kind, items, triedChests, -1);
        }

        /** The single item this visit is about, for the kinds that only ever have one. */
        private String item() {
            return items.isEmpty() ? null : items.getFirst();
        }
    }

    private enum Phase { PATHING, OPENING, ACTING }

    private static final double ARRIVE_RANGE = 3.5;
    private static final long PATH_TIMEOUT_TICKS = 20L * 60;   // 60s
    private static final long OPEN_TIMEOUT_TICKS = 20L * 5;    // 5s
    private static final long ACTING_TIMEOUT_TICKS = 20L * 10; // 10s - guards the multi-tick loops

    /**
     * Quick-moves issued per tick by the visits that keep going across ticks (SHUFFLE_CHEST,
     * DUMP_ALL). Emptying a double chest and refilling it is over a hundred moves; firing those in
     * a single tick is a packet burst no human could produce, and it makes the client's predicted
     * container state carry a long way before the server ever confirms any of it.
     */
    private static final int MOVES_PER_TICK = 9;

    /**
     * How many stacks RANDOMIZE tries to keep in hand between chests. The carried stacks are what
     * gets mixed into the next chest, so a deep buffer means a chest's new contents are drawn from
     * many different source chests - but it has to stay well under the bot's 36 slots, or there's
     * no room left to pick the next chest up.
     */
    private static final int TARGET_BUFFER_STACKS = 18;

    /** A planned shuffle may need several passes to resolve full-chest transfer cycles. */
    private static final int MAX_PLANNED_SHUFFLE_PASSES = 12;

    /** Assumed capacity of a chest the bot has never opened, for planning deposits into it. */
    private static final int ASSUMED_CHEST_SIZE = 27;

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
    private static final long RESERVED_REFRESH_TICKS = 20L; // 1s

    private Job currentJob;
    private Deque<Visit> plan;
    private Visit currentVisit;
    private Phase phase;
    private long phaseTicks;
    private boolean paused;
    private String lastWarning = "";

    /** Visits completed on the current job, for the progress readout. */
    private int visitsDone;

    /**
     * Stacks the bot is deliberately carrying between chests during a shuffle, by item id.
     * Tracked explicitly rather than read off the inventory so a deposit can't reach for something
     * the bot brought along itself - a tool or food in the hotbar isn't cargo.
     */
    private final Map<String, Integer> carried = new LinkedHashMap<>();

    /** Desired stack counts by chest and item for the experimental, pre-planned shuffle. */
    private final Map<BlockPos, Map<String, Integer>> plannedContents = new LinkedHashMap<>();
    private int plannedShufflePass;
    private int plannedShuffleMoves;

    /** Set from HTTP handler threads; acted on at the top of the next {@link #tick()}. */
    private volatile boolean stopRequested;

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

    /**
     * Asks for everything to stop: the running job, its remaining visits and the whole queue.
     * Anything the bot is still carrying is then unloaded into the nearest chest with room, so
     * stopping mid-shuffle doesn't leave half a chest's worth of items riding around in the
     * inventory where the next job would trip over it.
     *
     * <p>Only sets a flag - the actual teardown touches the plan and the world, both of which
     * belong to the client tick thread.
     */
    public void requestStop() {
        stopRequested = true;
    }

    public String getStatus() {
        if (paused) {
            return "paused";
        }
        if (currentJob == null) {
            return "idle (" + queue.size() + " queued)" + (wandering ? " - wandering" : "");
        }
        return currentJob + " - " + phase + " (" + progress() + ")";
    }

    /**
     * "visit 12 of 41" style progress. The total is the best estimate available rather than a fixed
     * count: several job types append follow-up visits as they discover what's actually in a chest,
     * so the denominator can grow as the job runs.
     */
    private String progress() {
        int remaining = plan != null ? plan.size() : 0;
        return visitsDone + "/" + (visitsDone + remaining) + " chests";
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
        if (stopRequested) {
            stopRequested = false;
            handleStop();
            return;
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
            visitsDone = 0;
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
     * Tears down whatever was running and, if the bot ended up holding anything, queues a dump so
     * the items go back into storage rather than sitting in the inventory.
     */
    private void handleStop() {
        navigator.cancel();
        interactor.close();
        stopWandering();
        queue.clear();
        currentJob = null;
        plan = null;
        currentVisit = null;
        shuffle = null;
        carried.clear();
        visitsDone = 0;
        if (!interactor.hasCarriedItems()) {
            return;
        }
        currentJob = Job.dumpInventory();
        plan = buildPlan(currentJob);
        if (!advanceVisit()) {
            finishJob();
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
                    plan.add(new Visit(pos, VisitKind.SCAN));
                }
            }
            case SORT_INPUT, SORT_INPUT_RANDOM -> {
                BlockPos input = index.getInputChest();
                if (input != null) {
                    VisitKind kind = job.type == Job.Type.SORT_INPUT_RANDOM
                            ? VisitKind.READ_INPUT_RANDOM
                            : VisitKind.READ_INPUT;
                    plan.add(new Visit(input, kind));
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
                    plan.add(new Visit(job.sourceChest, VisitKind.WITHDRAW_ITEM, job.itemId, job.sourceSlot));
                    BlockPos output = index.getOutputChest();
                    if (output != null) {
                        plan.add(new Visit(output, VisitKind.DEPOSIT_TO_OUTPUT, job.itemId));
                    } else {
                        warn("No output chest configured - {} will stay in the bot's inventory", job.itemId);
                    }
                }
            }
            case RANDOMIZE -> {
                // One visit per storage chest, in random order, and that's the whole plan: each
                // SHUFFLE_CHEST both unloads part of what the bot is carrying and picks that
                // chest's own contents up, so a chest is opened once and every stack moves once.
                carried.clear();
                for (BlockPos pos : index.randomStorageChests(reservedChests())) {
                    plan.add(new Visit(pos, VisitKind.SHUFFLE_CHEST));
                }
            }
            case EXPERIMENTAL_RANDOMIZE -> buildPlannedShuffle(plan);
            case DUMP_INVENTORY -> {
                BlockPos target = nearestChestWithRoom(List.of());
                if (target != null) {
                    plan.add(new Visit(target, VisitKind.DUMP_ALL));
                } else {
                    warn("Nowhere to dump the bot's inventory - no known chest has room");
                }
            }
        }
        return plan;
    }

    /**
     * Makes the experimental shuffle deterministic before the bot touches a chest. Every indexed
     * occupied slot contributes one entry; shuffling that list and dealing it back into groups the
     * same size as the original chests preserves total capacity without assuming any chest is
     * initially empty. Actual transfers are resolved later by {@link #tickPlannedShuffle()}.
     */
    private void buildPlannedShuffle(Deque<Visit> plan) {
        carried.clear();
        plannedContents.clear();
        plannedShufflePass = 1;
        plannedShuffleMoves = 0;

        Set<BlockPos> reserved = reservedChests();
        List<StorageIndex.ChestEntry> chests = new ArrayList<>();
        List<String> stacks = new ArrayList<>();
        for (StorageIndex.ChestEntry chest : index.allChests()) {
            BlockPos pos = chest.pos.toBlockPos();
            if (reserved.contains(pos)) {
                continue;
            }
            chests.add(chest);
            for (StorageIndex.SlotEntry slot : chest.slots) {
                stacks.add(slot.item);
            }
        }
        if (chests.isEmpty()) {
            warn("No indexed storage chests to randomize - rescan the region first");
            return;
        }
        Collections.shuffle(stacks);
        int offset = 0;
        for (StorageIndex.ChestEntry chest : chests) {
            Map<String, Integer> target = new HashMap<>();
            for (int i = 0; i < chest.slots.size(); i++) {
                target.merge(stacks.get(offset++), 1, Integer::sum);
            }
            plannedContents.put(chest.pos.toBlockPos(), target);
        }
        for (BlockPos pos : nearestFirst(new ArrayList<>(plannedContents.keySet()), playerPos())) {
            plan.add(new Visit(pos, VisitKind.PLANNED_SHUFFLE));
        }
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
        List<BlockPos> ordered = new ArrayList<>();
        BlockPos cursor = playerPos();
        for (List<BlockPos> layer : byLayer.values()) {
            List<BlockPos> leg = nearestFirst(layer, cursor);
            ordered.addAll(leg);
            if (!leg.isEmpty()) {
                cursor = leg.getLast();
            }
        }
        return ordered;
    }

    /**
     * Greedy nearest-neighbour walking order from {@code from}. Not an optimal tour, but it turns a
     * handful of deposit stops that would otherwise criss-cross the room into one loop around it.
     */
    private static List<BlockPos> nearestFirst(List<BlockPos> targets, BlockPos from) {
        List<BlockPos> remaining = new ArrayList<>(targets);
        List<BlockPos> ordered = new ArrayList<>(remaining.size());
        BlockPos cursor = from;
        while (!remaining.isEmpty()) {
            BlockPos anchor = cursor;
            BlockPos nearest = Collections.min(remaining, Comparator.comparingDouble(p -> p.distSqr(anchor)));
            remaining.remove(nearest);
            ordered.add(nearest);
            cursor = nearest;
        }
        return ordered;
    }

    private static BlockPos playerPos() {
        LocalPlayer player = Minecraft.getInstance().player;
        return player != null ? player.blockPosition() : BlockPos.ZERO;
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
     * The chests a shuffle must not touch: the configured input and output chests.
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

    /** Free slots according to the index. A chest never opened is assumed to be an empty single. */
    private static int freeSlots(StorageIndex.ChestEntry chest) {
        int size = chest.size > 0 ? chest.size : ASSUMED_CHEST_SIZE;
        return Math.max(0, size - chest.slots.size());
    }

    /** Known chests that still have room, excluding the input/output chests and {@code tried}. */
    private List<StorageIndex.ChestEntry> chestsWithRoom(List<BlockPos> tried) {
        Set<BlockPos> reserved = reservedChests();
        List<StorageIndex.ChestEntry> targets = new ArrayList<>();
        for (StorageIndex.ChestEntry chest : index.allChests()) {
            BlockPos pos = chest.pos.toBlockPos();
            if (reserved.contains(pos) || tried.contains(pos) || freeSlots(chest) <= 0) {
                continue;
            }
            targets.add(chest);
        }
        return targets;
    }

    private BlockPos nearestChestWithRoom(List<BlockPos> tried) {
        List<BlockPos> candidates = new ArrayList<>();
        for (StorageIndex.ChestEntry chest : chestsWithRoom(tried)) {
            candidates.add(chest.pos.toBlockPos());
        }
        return nearestFirst(candidates, playerPos()).stream().findFirst().orElse(null);
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
            visitsDone++;
            phaseTicks = 0;
            shuffle = null;
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
                if (slot < 0 || !currentVisit.item().equals(interactor.containerItemAt(slot))) {
                    slot = interactor.findContainerSlot(currentVisit.item());
                }
                if (slot >= 0) {
                    interactor.quickMove(slot);
                }
                recordSnapshot(currentVisit.pos());
                finishVisit();
            }
            case DEPOSIT_ITEM -> tickDepositItem();
            case DEPOSIT_BATCH -> tickDepositBatch();
            case SHUFFLE_CHEST -> tickShuffleChest();
            case PLANNED_SHUFFLE -> tickPlannedShuffle();
            case DUMP_ALL -> tickDumpAll();
            case DEPOSIT_TO_OUTPUT -> {
                int slot = interactor.findPlayerSlotWithItem(currentVisit.item());
                if (slot < 0) {
                    recordSnapshot(currentVisit.pos());
                    finishVisit();
                } else if (phaseTicks > ACTING_TIMEOUT_TICKS) {
                    // Still holding some after 10s - most likely the output chest is full.
                    warn("Could not fully deposit {} at {}, output chest may be full",
                            currentVisit.item(), currentVisit.pos());
                    recordSnapshot(currentVisit.pos());
                    finishVisit();
                } else {
                    interactor.quickMove(slot);
                    // Stay in ACTING until no more of this item is left to deposit.
                }
            }
            case READ_INPUT, READ_INPUT_RANDOM -> tickReadInput();
        }
    }

    /**
     * Unloads every stack of one item into this chest - the consolidating half of a sort. Capped
     * to {@link #MOVES_PER_TICK} quick-moves per call so a multi-stack drop spreads across several
     * ticks instead of firing in a single packet burst (the same rationale that already drives
     * {@link #tickShuffleChest()}, {@link #tickPlannedShuffle()} and {@link #tickDumpAll()}). When
     * the cap is hit with more stacks still in the inventory, the container is left open and the
     * visit is resumed on the next tick; only the chest-full branch finishes immediately so the
     * next chest can be opened.
     */
    private void tickDepositItem() {
        String itemId = currentVisit.item();
        boolean chestFull = false;
        int moves = 0;
        for (int move = 0; move < MOVES_PER_TICK; move++) {
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
            moves++;
        }
        boolean stillHolding = interactor.countPlayerItems(itemId) > 0;
        if (chestFull) {
            if (stillHolding) {
                List<BlockPos> tried = new ArrayList<>(currentVisit.triedChests());
                tried.add(currentVisit.pos());
                BlockPos next = index.depositCandidates(itemId, reservedChests()).stream()
                        .filter(p -> !tried.contains(p))
                        .findFirst().orElse(null);
                if (next != null) {
                    plan.addFirst(new Visit(next, VisitKind.DEPOSIT_ITEM, List.of(itemId), tried));
                } else {
                    warn("No chest had room for {}, leaving it in the bot's inventory", itemId);
                }
            }
            recordSnapshot(currentVisit.pos());
            finishVisit();
            return;
        }
        if (stillHolding && moves >= MOVES_PER_TICK && phaseTicks <= ACTING_TIMEOUT_TICKS) {
            // Hit the per-tick cap with more stacks still here - leave the container open and
            // resume on the next tick, matching the return-early path used by tickShuffleChest
            // and tickDumpAll for the same reason.
            return;
        }
        recordSnapshot(currentVisit.pos());
        finishVisit();
    }

    /**
     * Drops one stack each of several different items into this chest. Batching is what makes a
     * scattered deposit affordable: the point of scattering is that no chest ends up holding all
     * of any one item, which says nothing about how many <em>different</em> items may share a
     * chest - so there's no reason to make a separate trip per stack.
     *
     * <p>Capped to {@link #MOVES_PER_TICK} successful quick-moves per call so a large batch
     * doesn't fire all its packets in one client tick. Items the bot actually deposited (or which
     * the chest turned out to be full for) are dropped from {@code currentVisit.items()} via the
     * iterator, so the next tick picks up wherever this one stopped without re-depositing the same
     * stacks or losing the rest of the batch.
     */
    private void tickDepositBatch() {
        // Whether this chest may hold a given item was settled when the batch was assembled - a
        // shuffle drain checks it against the index, a scatter picks a random chest per stack.
        // Re-testing it here would only override the drain's deliberate last-resort doubling up.
        int room = interactor.containerSlotCount() - interactor.snapshotContainerSlots().size();
        List<String> leftovers = new ArrayList<>();
        Iterator<String> it = currentVisit.items().iterator();
        int moves = 0;
        while (it.hasNext()) {
            if (moves >= MOVES_PER_TICK) {
                break; // hit the per-tick cap; remaining items stay queued for the next tick
            }
            String item = it.next();
            if (room <= 0) {
                leftovers.add(item);
                it.remove();
                continue;
            }
            int slot = interactor.findPlayerSlotWithItem(item);
            if (slot < 0) {
                dropCarried(item); // gone from the inventory - nothing to place
                it.remove();
                continue;
            }
            int before = interactor.countPlayerItems(item);
            interactor.quickMove(slot);
            if (interactor.countPlayerItems(item) == before) {
                leftovers.add(item);
                it.remove();
                room = 0; // full, whatever the index thought
                continue;
            }
            dropCarried(item);
            it.remove();
            room--;
            moves++;
        }
        if (!leftovers.isEmpty()) {
            List<BlockPos> tried = new ArrayList<>(currentVisit.triedChests());
            tried.add(currentVisit.pos());
            BlockPos next = nearestChestWithRoom(tried);
            if (next != null) {
                plan.addFirst(new Visit(next, VisitKind.DEPOSIT_BATCH, leftovers, tried));
            } else {
                warn("No chest had room for {} stack(s), leaving them in the bot's inventory",
                        leftovers.size());
            }
        }
        if (!currentVisit.items().isEmpty()
                && moves >= MOVES_PER_TICK
                && phaseTicks <= ACTING_TIMEOUT_TICKS) {
            // Hit the per-tick cap with items still queued for this chest - leave the container
            // open and resume on the next tick, the same way tickShuffleChest / tickDumpAll do.
            return;
        }
        recordSnapshot(currentVisit.pos());
        finishVisit();
    }

    /** Per-visit state for {@link #tickShuffleChest()}, which spreads its moves over several ticks. */
    private static final class ShuffleState {
        final List<StorageIndex.SlotEntry> originals;
        final Set<Integer> taken = new HashSet<>();

        ShuffleState(List<StorageIndex.SlotEntry> originals) {
            this.originals = originals;
        }
    }

    private ShuffleState shuffle;

    /** Moves this chest one step toward its already-chosen experimental target contents. */
    private void tickPlannedShuffle() {
        Map<String, Integer> target = plannedContents.get(currentVisit.pos());
        if (target == null) {
            recordSnapshot(currentVisit.pos());
            finishVisit();
            return;
        }

        int moves = 0;
        Map<String, Integer> current = containerItemCounts();
        // First make room for cargo that is specifically meant to end up in this chest.
        for (Map.Entry<String, Integer> entry : target.entrySet()) {
            String item = entry.getKey();
            while (moves < MOVES_PER_TICK
                    && current.getOrDefault(item, 0) < entry.getValue()
                    && carried.getOrDefault(item, 0) > 0) {
                int slot = interactor.findPlayerSlotWithItem(item);
                if (slot < 0) {
                    dropCarried(item);
                    break;
                }
                int before = interactor.countPlayerItems(item);
                interactor.quickMove(slot);
                if (interactor.countPlayerItems(item) == before) {
                    break; // no room after all
                }
                dropCarried(item);
                current.merge(item, 1, Integer::sum);
                moves++;
            }
        }

        // Then pull only stacks this chest has in excess of its target. This preserves stacks
        // that already happen to belong here, rather than pointlessly moving them twice.
        for (StorageIndex.SlotEntry entry : interactor.snapshotContainerSlots()) {
            if (moves >= MOVES_PER_TICK) {
                break;
            }
            if (current.getOrDefault(entry.item, 0) <= target.getOrDefault(entry.item, 0)) {
                continue;
            }
            int before = interactor.countPlayerItems(entry.item);
            interactor.quickMove(entry.slot);
            if (interactor.countPlayerItems(entry.item) == before) {
                break; // player inventory is full
            }
            current.merge(entry.item, -1, Integer::sum);
            carried.merge(entry.item, 1, Integer::sum);
            moves++;
        }
        plannedShuffleMoves += moves;
        if (moves >= MOVES_PER_TICK && phaseTicks <= ACTING_TIMEOUT_TICKS) {
            return;
        }
        recordSnapshot(currentVisit.pos());
        finishVisit();
    }

    private Map<String, Integer> containerItemCounts() {
        Map<String, Integer> counts = new HashMap<>();
        for (StorageIndex.SlotEntry entry : interactor.snapshotContainerSlots()) {
            counts.merge(entry.item, 1, Integer::sum);
        }
        return counts;
    }

    /**
     * One chest's worth of a RANDOMIZE pass: unload part of what the bot is carrying into this
     * chest, and pick this chest's own contents up in exchange.
     *
     * <p>This is the whole reason a shuffle is now one visit per chest rather than one per stack.
     * The old plan emptied a chest into the bot and then walked to a separate randomly-chosen chest
     * for every single stack, so a 20-stack chest cost 20 round trips across the room - and since
     * destinations were drawn from every chest, including ones the pass hadn't reached yet, a good
     * share of those stacks got picked up and moved all over again later. Carrying a buffer between
     * chests gets the same mixing out of one visit per chest, with each stack moved exactly once.
     */
    private void tickShuffleChest() {
        if (shuffle == null) {
            shuffle = new ShuffleState(interactor.snapshotContainerSlots());
            reconcileCarried();
        }
        boolean moved = shuffleRound();
        if (moved && phaseTicks <= ACTING_TIMEOUT_TICKS) {
            return; // more to move here - keep going next tick
        }
        BlockPos pos = currentVisit.pos();
        recordSnapshot(pos);
        if (shuffle.taken.size() < shuffle.originals.size() && currentVisit.triedChests().isEmpty()) {
            // The bot filled up before this chest was empty. Come back to it at the end of the
            // pass, once carrying less - marked as retried so it can't bounce back and forth.
            plan.addLast(new Visit(pos, VisitKind.SHUFFLE_CHEST, List.of(), List.of(pos)));
        }
        if (plan.isEmpty() && !carried.isEmpty()) {
            // Last chest of the pass, and the buffer is still full - spread it over the chests
            // that have room instead of walking off with it.
            plan.addAll(drainVisits(pos));
        }
        finishVisit();
    }

    /**
     * One tick's worth of shuffling: take a few of this chest's original stacks, then push back
     * about as many carried ones. Returns whether anything actually moved - when nothing does, the
     * chest is either empty or as full as it's going to get, and the visit is done.
     */
    private boolean shuffleRound() {
        int took = 0;
        int free = interactor.countFreePlayerSlots();
        for (StorageIndex.SlotEntry entry : shuffle.originals) {
            if (took >= MOVES_PER_TICK || free <= 0) {
                break;
            }
            if (shuffle.taken.contains(entry.slot)) {
                continue;
            }
            // Only ever pull slots recorded in the opening snapshot, and only while they still
            // hold what they held then - deposits made during this visit land in the slots we
            // just emptied, and taking one of those straight back out would undo the swap.
            if (!entry.item.equals(interactor.containerItemAt(entry.slot))) {
                shuffle.taken.add(entry.slot);
                continue;
            }
            interactor.quickMove(entry.slot);
            if (entry.item.equals(interactor.containerItemAt(entry.slot))) {
                break; // didn't move - inventory is full, so stop pulling and go deposit instead
            }
            shuffle.taken.add(entry.slot);
            carried.merge(entry.item, 1, Integer::sum);
            took++;
            free--;
        }

        List<StorageIndex.SlotEntry> live = interactor.snapshotContainerSlots();
        Set<String> held = new HashSet<>();
        for (StorageIndex.SlotEntry entry : live) {
            held.add(entry.item);
        }
        int room = Math.max(0, interactor.containerSlotCount() - live.size());
        // Put back roughly what was taken, so a chest ends the pass about as full as it started,
        // plus anything above the buffer target - that's what keeps the bot from silently
        // accumulating stacks it never puts down.
        int surplus = Math.max(0, carriedStacks() - TARGET_BUFFER_STACKS);
        int allowance = Math.min(Math.min(took + surplus, room), MOVES_PER_TICK);

        List<String> options = new ArrayList<>(carried.keySet());
        Collections.shuffle(options);
        int put = 0;
        for (String item : options) {
            if (put >= allowance) {
                break;
            }
            if (held.contains(item)) {
                continue; // never two stacks of the same item in one chest
            }
            int slot = interactor.findPlayerSlotWithItem(item);
            if (slot < 0) {
                dropCarried(item);
                continue;
            }
            int before = interactor.countPlayerItems(item);
            interactor.quickMove(slot);
            if (interactor.countPlayerItems(item) == before) {
                break; // chest is full
            }
            dropCarried(item);
            held.add(item);
            put++;
        }
        return took > 0 || put > 0;
    }

    /**
     * Spreads whatever is still in hand at the end of a shuffle over the chests that have room,
     * one stack per item per chest, visiting them in a single loop rather than one trip per stack.
     */
    private List<Visit> drainVisits(BlockPos from) {
        List<String> stacks = new ArrayList<>();
        carried.forEach((item, count) -> {
            for (int i = 0; i < count; i++) {
                stacks.add(item);
            }
        });
        Collections.shuffle(stacks);

        List<StorageIndex.ChestEntry> targets = chestsWithRoom(List.of(from));
        Collections.shuffle(targets);
        Map<BlockPos, Set<String>> holds = new HashMap<>();
        Map<BlockPos, Integer> room = new HashMap<>();
        for (StorageIndex.ChestEntry chest : targets) {
            BlockPos pos = chest.pos.toBlockPos();
            Set<String> items = new HashSet<>();
            for (StorageIndex.SlotEntry slot : chest.slots) {
                items.add(slot.item);
            }
            holds.put(pos, items);
            room.put(pos, freeSlots(chest));
        }

        Map<BlockPos, List<String>> batches = new LinkedHashMap<>();
        int unplaced = 0;
        for (String item : stacks) {
            BlockPos chosen = null;
            for (StorageIndex.ChestEntry chest : targets) {
                BlockPos pos = chest.pos.toBlockPos();
                if (room.get(pos) > 0 && !holds.get(pos).contains(item)) {
                    chosen = pos;
                    break;
                }
            }
            if (chosen == null) {
                // Nothing left that doesn't already hold this item. Storage holding more stacks of
                // something than it has chests is the normal way to get here - a chest full of
                // cobblestone spread one per chest runs out of chests. Doubling up beats walking
                // off with it, so the rule relaxes here and only here.
                for (StorageIndex.ChestEntry chest : targets) {
                    BlockPos pos = chest.pos.toBlockPos();
                    if (room.get(pos) > 0) {
                        chosen = pos;
                        break;
                    }
                }
            }
            if (chosen == null) {
                unplaced++;
                continue;
            }
            batches.computeIfAbsent(chosen, key -> new ArrayList<>()).add(item);
            holds.get(chosen).add(item);
            room.merge(chosen, -1, Integer::sum);
        }
        if (unplaced > 0) {
            warn("Nowhere left to spread {} stack(s) to - they stay in the bot's inventory", unplaced);
        }

        List<Visit> visits = new ArrayList<>();
        for (BlockPos pos : nearestFirst(new ArrayList<>(batches.keySet()), from)) {
            visits.add(new Visit(pos, VisitKind.DEPOSIT_BATCH, batches.get(pos), List.of()));
        }
        return visits;
    }

    /** Shift-clicks the bot's whole inventory into this chest, spilling into the next one if it fills. */
    private void tickDumpAll() {
        boolean chestFull = false;
        for (int move = 0; move < MOVES_PER_TICK; move++) {
            int slot = interactor.firstNonEmptyPlayerSlot();
            if (slot < 0) {
                break;
            }
            String item = interactor.itemAt(slot);
            if (item == null) {
                break;
            }
            int before = interactor.countPlayerItems(item);
            interactor.quickMove(slot);
            if (interactor.countPlayerItems(item) == before) {
                chestFull = true;
                break;
            }
        }
        boolean stillHolding = interactor.firstNonEmptyPlayerSlot() >= 0;
        if (stillHolding && !chestFull && phaseTicks <= ACTING_TIMEOUT_TICKS) {
            return; // more to unload here - keep going next tick
        }
        recordSnapshot(currentVisit.pos());
        if (stillHolding) {
            List<BlockPos> tried = new ArrayList<>(currentVisit.triedChests());
            tried.add(currentVisit.pos());
            BlockPos next = nearestChestWithRoom(tried);
            if (next != null) {
                plan.addFirst(new Visit(next, VisitKind.DUMP_ALL, List.of(), tried));
            } else {
                warn("No chest had room - the rest stays in the bot's inventory");
            }
        }
        carried.clear();
        finishVisit();
    }

    /** Empties the input chest, either consolidating each item or scattering stack by stack. */
    private void tickReadInput() {
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
                // One stack per chest is the point here, so each gets a batch of exactly one.
                // The list has to be mutable so tickDepositBatch can drop processed items from
                // it via its iterator if the visit ever spans more than MOVES_PER_TICK ticks.
                plan.add(new Visit(scatterTargets.poll(), VisitKind.DEPOSIT_BATCH,
                        new ArrayList<>(List.of(entry.item)), List.of()));
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

    private int carriedStacks() {
        int total = 0;
        for (int count : carried.values()) {
            total += count;
        }
        return total;
    }

    private void dropCarried(String item) {
        Integer count = carried.get(item);
        if (count == null) {
            return;
        }
        if (count <= 1) {
            carried.remove(item);
        } else {
            carried.put(item, count - 1);
        }
    }

    /**
     * Trims the carried tally back to what the bot is really holding. Quick-moves are predicted
     * client-side and the server can reject one, so without this a stack the bot never actually
     * picked up would be offered to every chest for the rest of the pass.
     */
    private void reconcileCarried() {
        carried.entrySet().removeIf(entry -> {
            int actual = interactor.countPlayerStacks(entry.getKey());
            if (actual <= 0) {
                return true;
            }
            entry.setValue(Math.min(entry.getValue(), actual));
            return false;
        });
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
            if (currentJob != null && currentJob.type == Job.Type.EXPERIMENTAL_RANDOMIZE
                    && continuePlannedShuffle()) {
                return;
            }
            finishJob();
        }
    }

    /** Starts another reconciliation pass when the fixed experimental layout is not there yet. */
    private boolean continuePlannedShuffle() {
        if (plannedShuffleComplete()) {
            plannedContents.clear();
            return false;
        }
        if (plannedShufflePass >= MAX_PLANNED_SHUFFLE_PASSES || plannedShuffleMoves == 0) {
            warn("Experimental shuffle could not finish its planned layout; {} cargo stack(s) will be dumped safely",
                    carriedStacks());
            plannedContents.clear();
            if (!carried.isEmpty()) {
                queue.enqueue(Job.dumpInventory());
            }
            return false;
        }
        plannedShufflePass++;
        plannedShuffleMoves = 0;
        plan = new ArrayDeque<>();
        for (BlockPos pos : nearestFirst(new ArrayList<>(plannedContents.keySet()), playerPos())) {
            plan.add(new Visit(pos, VisitKind.PLANNED_SHUFFLE));
        }
        return advanceVisit();
    }

    private boolean plannedShuffleComplete() {
        if (!carried.isEmpty()) {
            return false;
        }
        Map<BlockPos, StorageIndex.ChestEntry> indexed = new HashMap<>();
        for (StorageIndex.ChestEntry chest : index.allChests()) {
            indexed.put(chest.pos.toBlockPos(), chest);
        }
        for (Map.Entry<BlockPos, Map<String, Integer>> entry : plannedContents.entrySet()) {
            StorageIndex.ChestEntry chest = indexed.get(entry.getKey());
            if (chest == null || !itemCounts(chest.slots).equals(entry.getValue())) {
                return false;
            }
        }
        return true;
    }

    private static Map<String, Integer> itemCounts(List<StorageIndex.SlotEntry> slots) {
        Map<String, Integer> counts = new HashMap<>();
        for (StorageIndex.SlotEntry slot : slots) {
            counts.merge(slot.item, 1, Integer::sum);
        }
        return counts;
    }

    private void finishJob() {
        currentJob = null;
        plan = null;
        currentVisit = null;
        shuffle = null;
        plannedContents.clear();
        // Index writes are debounced, but a just-finished job is a natural point to persist at
        // rather than leaving the last few chests riding on the background flush.
        index.flush();
    }
}
