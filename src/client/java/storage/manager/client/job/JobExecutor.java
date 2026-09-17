package storage.manager.client.job;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.CraftingTableBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.tags.BlockTags;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import storage.manager.StorageManager;
import storage.manager.client.baritone.BotNavigator;
import storage.manager.client.gather.DropIndex;
import storage.manager.client.gather.GatherPlanner;
import storage.manager.client.interact.ChestInteractor;
import storage.manager.client.interact.CraftingInteractor;
import storage.manager.client.interact.FurnaceInteractor;
import storage.manager.client.recipe.ChainPlanner;
import storage.manager.client.recipe.CraftPlanner;
import storage.manager.client.recipe.Recipe;
import storage.manager.client.recipe.RecipeBook;
import storage.manager.client.storage.StorageIndex;
import storage.manager.client.tool.ToolKit;
import storage.manager.client.web.WebServer;

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
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.ToIntFunction;
import java.util.stream.Collectors;

/**
 * Drains {@link JobQueue} one job at a time on the client tick thread. Each job is expanded
 * into an ordered list of chest {@link Visit}s; SORT_INPUT appends its deposit visits
 * dynamically once it has actually seen what's in the input chest, since that can't be known
 * up front.
 */
public class JobExecutor implements storage.manager.client.web.WebServer.ExecutorView {

    private enum VisitKind {
        SCAN, WITHDRAW_ITEM, DEPOSIT_ITEM, DEPOSIT_BATCH, READ_INPUT,
        DEPOSIT_TO_OUTPUT, SHUFFLE_CHEST, SHUFFLE_GRAB, SHUFFLE_DROP, DUMP_ALL, CRAFT, SMELT, GATHER,
        OBTAIN, PUT_AWAY
    }

    /**
     * An OBTAIN visit's goal: {@code targets} (item to count) either to carry and equip
     * ({@code equip}, the kit's tools) or to craft into storage. {@code rounds} counts plan-act
     * cycles so far, {@code depth} how deeply this OBTAIN is nested inside a gather that needed a
     * tool, and {@code lastGather} what the previous round set out to gather - the same shortfall
     * again means that trip brought nothing back.
     */
    private record Obtain(Map<String, Integer> targets, boolean equip, int rounds, int depth,
                          Map<String, Integer> lastGather) {
        private Obtain next(Map<String, Integer> gathering) {
            return new Obtain(targets, equip, rounds + 1, depth, gathering);
        }
    }

    /**
     * {@code items} is empty for kinds that don't target one, holds a single entry for the
     * item-at-a-time kinds, and one entry per stack for DEPOSIT_BATCH. {@code slot} is -1 unless
     * the visit targets one specific container slot (exact withdrawals). {@code crafts} is how many
     * times a CRAFT or SMELT visit runs its recipe, and 0 for every other kind. A SMELT visit's
     * {@code items} are the result then the fuel, and {@code fuelCount} is how much of that fuel
     * to load.
     *
     * <p>Three kinds have no {@code pos}. GATHER - Baritone picks where to mine - carries its
     * {@code gather} spec, with {@code crafts} how many items are still wanted and {@code fuelCount}
     * how many times it has already re-equipped a tool that broke. OBTAIN carries an
     * {@link Obtain} goal and is planned only once reached, so it sees what earlier steps brought
     * back. PUT_AWAY likewise expands to deposits of whatever the bot carries by the time it's reached.
     */
    private record Visit(BlockPos pos, VisitKind kind, List<String> items, List<BlockPos> triedChests, int slot,
                         int crafts, int fuelCount, GatherSpec gather, Obtain obtain) {

        private Visit(BlockPos pos, VisitKind kind, List<String> items, List<BlockPos> triedChests, int slot,
                      int crafts, int fuelCount) {
            this(pos, kind, items, triedChests, slot, crafts, fuelCount, null, null);
        }

        private Visit(BlockPos pos, VisitKind kind) {
            this(pos, kind, List.of(), List.of(), -1, 0, 0);
        }

        private Visit(BlockPos pos, VisitKind kind, String item) {
            this(pos, kind, List.of(item), List.of(), -1, 0, 0);
        }

        private Visit(BlockPos pos, VisitKind kind, String item, int slot) {
            this(pos, kind, List.of(item), List.of(), slot, 0, 0);
        }

        private Visit(BlockPos pos, VisitKind kind, List<String> items, List<BlockPos> triedChests) {
            this(pos, kind, items, triedChests, -1, 0, 0);
        }

        private static Visit craft(BlockPos table, String item, int crafts) {
            return new Visit(table, VisitKind.CRAFT, List.of(item), List.of(), -1, crafts, 0);
        }

        private static Visit smelt(BlockPos furnace, CraftPlanner.Step step) {
            return new Visit(furnace, VisitKind.SMELT, List.of(step.item(), step.fuel()), List.of(), -1,
                    step.crafts(), step.fuelCount());
        }

        private static Visit gather(GatherSpec spec, int wanted, int reequips) {
            return new Visit(null, VisitKind.GATHER, List.of(spec.item()), List.of(), -1, wanted, reequips, spec, null);
        }

        private static Visit obtain(Obtain goal) {
            return new Visit(null, VisitKind.OBTAIN, List.copyOf(goal.targets().keySet()), List.of(), -1, 0, 0,
                    null, goal);
        }

        /** Equip one tool, made from scratch if it has to be. */
        private static Visit obtainTool(String tool, int depth) {
            return obtain(new Obtain(Map.of(tool, 1), true, 0, depth, null));
        }

        /** The single item this visit is about, for the kinds that only ever have one. */
        private String item() {
            return items.isEmpty() ? null : items.getFirst();
        }
    }

    private enum Phase { PATHING, OPENING, ACTING, GATHERING }

    private static final double ARRIVE_RANGE = 3.5;
    /**
     * How long a walk may go without getting any closer before it's abandoned. Measured from the
     * last progress rather than the start, so the long walk home from a gathering trip isn't cut
     * off just for being long.
     */
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

    /** Assumed capacity of a chest the bot has never opened, for planning deposits into it. */
    private static final int ASSUMED_CHEST_SIZE = 27;

    /** Chest levels within this many blocks vertically share a standing position. */
    private static final int SAME_FLOOR_GAP = 1;

    /** Walking cost in blocks charged per floor change - the detour to the stairs and back. */
    private static final double FLOOR_CHANGE_COST = 24.0;

    /** Idle-stroll pacing. Randomized between these so it doesn't read as clockwork. */
    private static final long WANDER_MIN_IDLE_TICKS = 20L * 120; // 2min
    private static final long WANDER_MAX_IDLE_TICKS = 20L * 300; // 5min
    /** Baritone takes a moment to start pathing; don't read "not pathing yet" as "arrived". */
    private static final long WANDER_START_GRACE_TICKS = 40L;
    private static final long WANDER_TIMEOUT_TICKS = 20L * 60;

    /** Baritone needs a few ticks to scan for targets before an inactive mine means "none found". */
    private static final long GATHER_START_GRACE_TICKS = 40L;
    /** Mining with nothing picked up for this long means Baritone is wandering, not finding. */
    private static final long GATHER_STALL_TICKS = 20L * 180; // 3min
    /** A trip ends with this few free slots left, so pickups aren't dropped on the floor. */
    private static final int GATHER_MIN_FREE_SLOTS = 1;
    /** Tools a single GATHER job may wear out and replace before it stops. */
    private static final int MAX_TOOL_REEQUIPS = 5;
    /** Blocks around the storage region and configured blocks that gathering must never break. */
    private static final int PROTECTED_MARGIN = 1;
    /** Categories EQUIP_TOOLS fills. Hoes only matter for a few blocks, so gathering fetches one on demand. */
    private static final List<ToolKit.Category> STANDARD_TOOLS =
            List.of(ToolKit.Category.PICKAXE, ToolKit.Category.AXE, ToolKit.Category.SHOVEL);

    private final JobQueue queue;
    private final BotNavigator navigator = new BotNavigator();
    private final ChestInteractor interactor = new ChestInteractor();
    private final CraftingInteractor craftingInteractor = new CraftingInteractor();
    private final FurnaceInteractor furnaceInteractor = new FurnaceInteractor();
    private final RecipeBook recipeBook = RecipeBook.load();
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

    /**
     * In-flight chunked region discovery for the current SCAN_REGION job. {@code null} when no
     * scan is running or the most recent one finished; reset by {@link #finishJob()} and
     * {@link #handleStop()} so an interrupted scan doesn't carry over to the next job.
     */
    private RegionScanner currentScanner;

    /** Visits completed on the current job, for the progress readout. */
    private int visitsDone;

    /**
     * Stacks the bot is deliberately carrying between chests during a shuffle, by item id.
     * Tracked explicitly rather than read off the inventory so a deposit can't reach for something
     * the bot brought along itself - a tool or food in the hotbar isn't cargo.
     */
    private final Map<String, Integer> carried = new LinkedHashMap<>();

    /** Set from HTTP handler threads; acted on at the top of the next {@link #tick()}. */
    private volatile boolean stopRequested;

    private volatile boolean wanderEnabled = true;
    private volatile List<WebServer.InventorySlot> botInventory = emptyBotInventory();
    private boolean wandering;
    private long idleTicks;
    private long wanderTicks;
    private long nextWanderTicks = WANDER_MIN_IDLE_TICKS;

    public JobExecutor(JobQueue queue, StorageIndex index) {
        this.queue = queue;
        this.index = index;
        interactor.setKeptSlots(this::keptToolSlots);
    }

    public void pause() {
        if (!paused) {
            stopWandering();
            navigator.cancel();
        }
        if (gather != null) {
            // Cancelling Baritone ended the mine - pick it back up once resumed.
            gather.restart = true;
        }
        paused = true;
    }

    public void resume() {
        paused = false;
    }

    public boolean isPaused() {
        return paused;
    }

    @Override
    public List<WebServer.InventorySlot> botInventory() {
        return botInventory;
    }

    /** Captures all 36 player slots on the client thread for the web server to read safely. */
    private void refreshBotInventory() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            botInventory = emptyBotInventory();
            return;
        }
        Set<Integer> kept = keptToolSlots();
        List<WebServer.InventorySlot> snapshot = new ArrayList<>(36);
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            snapshot.add(stack.isEmpty()
                    ? new WebServer.InventorySlot(slot, null, 0, false)
                    : new WebServer.InventorySlot(slot,
                            BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), stack.getCount(),
                            kept.contains(slot)));
        }
        botInventory = List.copyOf(snapshot);
    }

    /** Whether the bot carries any of this item as cargo - equipped tools don't count. */
    private boolean botHolds(String itemId) {
        refreshBotInventory();
        return botInventory.stream().anyMatch(slot -> itemId.equals(slot.item()) && !slot.kept());
    }

    /** Whether any slot at all, equipped or not, holds this item. */
    private boolean botCarriesAny(String itemId) {
        refreshBotInventory();
        return botInventory.stream().anyMatch(slot -> itemId.equals(slot.item()));
    }

    private static List<WebServer.InventorySlot> emptyBotInventory() {
        List<WebServer.InventorySlot> empty = new ArrayList<>(36);
        for (int slot = 0; slot < 36; slot++) {
            empty.add(new WebServer.InventorySlot(slot, null, 0, false));
        }
        return List.copyOf(empty);
    }

    /** Player-inventory indices holding equipped tools - see {@link ToolKit#keptSlots}. */
    private Set<Integer> keptToolSlots() {
        LocalPlayer player = Minecraft.getInstance().player;
        Map<String, String> equipped = index.getEquippedTools();
        if (player == null || equipped.isEmpty()) {
            return Set.of();
        }
        List<ToolKit.Held> held = new ArrayList<>();
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (!stack.isEmpty()) {
                held.add(new ToolKit.Held(slot, BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),
                        stack.getDamageValue()));
            }
        }
        return ToolKit.keptSlots(equipped.values(), held);
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
        if (phase == Phase.GATHERING && currentVisit != null) {
            int gained = gather != null ? gather.gained : 0;
            return currentJob + " - GATHERING (" + gained + "/" + currentVisit.crafts() + " this trip)";
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
        // Fill each `{}` with the next arg in order, like SLF4J. Done by index rather than
        // String.replace (which fills every placeholder with the first arg) or replaceFirst (which
        // treats `$`/`\` in the arg as regex replacement syntax). `lastWarning` is what the web UI
        // surfaces verbatim.
        StringBuilder formatted = new StringBuilder();
        int from = 0;
        for (Object arg : args) {
            int at = message.indexOf("{}", from);
            if (at < 0) {
                break;
            }
            formatted.append(message, from, at).append(arg);
            from = at + 2;
        }
        formatted.append(message, from, message.length());
        lastWarning = formatted.toString();
    }

    public ReservedChests reservedChestPositions() {
        return reservedSnapshot;
    }

    public void tick() {
        refreshBotInventory();
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
            // Spread the region scan across multiple ticks before planning, so a large cuboid
            // doesn't freeze the tick thread on a single call. Only SCAN_REGION triggers this -
            // other jobs reuse whatever the index already knows.
            if (currentJob.type == Job.Type.SCAN_REGION) {
                if (currentScanner == null) {
                    StorageIndex.Region region = index.getRegion();
                    if (region.min != null && region.max != null) {
                        currentScanner = new RegionScanner(
                                region.min.toBlockPos(), region.max.toBlockPos(),
                                index, new MinecraftBlockStateLookup());
                    }
                }
                if (currentScanner != null) {
                    currentScanner.scanChunk();
                    if (!currentScanner.isDone()) {
                        return;
                    }
                    currentScanner = null;
                }
            }
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
            case GATHERING -> tickGather();
        }
    }

    /**
     * Tears down whatever was running and, if the bot ended up holding anything, queues a dump so
     * the items go back into storage rather than sitting in the inventory.
     */
    private void handleStop() {
        navigator.cancel();
        interactor.close();
        craftingInteractor.close();
        if (currentVisit != null && currentVisit.kind() == VisitKind.SMELT && furnaceInteractor.isOpen()) {
            // A stopped smelt shouldn't leave its half-done batch in the furnace - pull input,
            // fuel and output back out so the dump below puts them into storage.
            furnaceInteractor.takeOut(FurnaceInteractor.RESULT_SLOT);
            furnaceInteractor.takeOut(FurnaceInteractor.INPUT_SLOT);
            furnaceInteractor.takeOut(FurnaceInteractor.FUEL_SLOT);
            furnaceInteractor.close();
        }
        stopWandering();
        queue.clear();
        currentScanner = null;
        currentJob = null;
        plan = null;
        currentVisit = null;
        shuffle = null;
        craftsRemaining = -1;
        smelt = null;
        gather = null;
        randomizeRemaining = null;
        randomizeBatchDestinations = null;
        randomizeLeftovers = null;
        carried.clear();
        visitsDone = 0;
        // Equipped tools don't count as carried, so a bot holding only its pickaxe stays put.
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
                // Discovery already happened in tick() across however many ticks the region
                // needed; we only plan which discovered chests are worth re-opening.
                List<BlockPos> targets = new ArrayList<>();
                for (StorageIndex.ChestEntry chest : index.allChests()) {
                    targets.add(chest.pos.toBlockPos());
                }
                for (BlockPos pos : nearestFirst(targets, playerPos())) {
                    plan.add(new Visit(pos, VisitKind.SCAN));
                }
            }
            case SORT_INPUT -> {
                BlockPos input = index.getInputChest();
                if (input != null) {
                    plan.add(new Visit(input, VisitKind.READ_INPUT));
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
                // Per-batch shuffle: snapshot every visited chest's contents at job start into a
                // stack-count tracker ("remaining"), then loop "pull up to RANDOMIZE_BATCH_SIZE
                // stacks from a random pull chest, drop them one-by-one into separate random
                // destinations" until remaining is empty. Each drop is its own visit, so a stack
                // never lands in the same chest as another stack in the same batch.
                randomizeRemaining = new HashMap<>();
                randomizeBatchDestinations = new HashSet<>();
                randomizeLeftovers = new ArrayList<>();
                for (StorageIndex.ChestEntry chest : index.allChests()) {
                    // Only visited chests count - the index's slots list is the source of truth
                    // for freeSlots, and a never-opened chest (lastScanned == 0) would send the
                    // bot to walk to an "empty" container that the server reports as full.
                    if (chest.lastScanned <= 0 || reservedChests().contains(chest.pos.toBlockPos())) {
                        continue;
                    }
                    for (StorageIndex.SlotEntry slot : chest.slots) {
                        randomizeRemaining.merge(slot.item, 1, Integer::sum);
                    }
                }
                plan = new ArrayDeque<>(buildRandomizeBatches());
            }
            case DUMP_INVENTORY -> {
                BlockPos target = nearestChestWithRoom(List.of());
                if (target != null) {
                    plan.add(new Visit(target, VisitKind.DUMP_ALL));
                } else {
                    warn("Nowhere to dump the bot's inventory - no known chest has room");
                }
            }
            case CRAFT -> {
                boolean fromStorage = recipeBook.recipesFor(job.itemId).isEmpty()
                        || CraftPlanner.plan(recipeBook, storageCount(reservedChests()), job.itemId, job.count).possible();
                if (fromStorage) {
                    buildCraftPlan(plan, job); // also where "no recipe" gets reported
                } else {
                    // Short of something - gather it (and any tools that takes), then craft.
                    plan.add(Visit.obtain(new Obtain(Map.of(job.itemId, job.count), false, 0, 0, null)));
                }
            }
            case GATHER -> buildGatherPlan(plan, job);
            case EQUIP_TOOLS -> {
                boolean any = false;
                for (ToolKit.Category category : STANDARD_TOOLS) {
                    any |= planEquip(plan, category, 0, false, true) != null;
                }
                if (!any) {
                    warn("No pickaxe, axe or shovel in storage to equip");
                }
            }
            case KIT -> buildKitPlan(plan);
            case STOW_TOOLS -> {
                // Once unequipped the tools are ordinary cargo, so a plain dump puts them away.
                index.clearEquippedTools();
                BlockPos target = nearestChestWithRoom(List.of());
                if (target != null) {
                    plan.add(new Visit(target, VisitKind.DUMP_ALL));
                } else if (interactor.hasCarriedItems()) {
                    warn("Nowhere to put the bot's tools - no known chest has room");
                }
            }
        }
        return plan;
    }

    /**
     * Debug loadout: a diamond tool of every kind, equipped whatever the bot had before. All five
     * are one OBTAIN, so the chain is planned together - the diamonds for every tool, the sticks for
     * every tool and the wooden-to-iron pickaxes on the way are each gathered in one go. Tools the
     * kit replaced become cargo and go back to storage at the end.
     */
    private void buildKitPlan(Deque<Visit> plan) {
        Map<String, Integer> tools = new LinkedHashMap<>();
        for (ToolKit.Category category : KIT_ORDER) {
            tools.put("minecraft:diamond_" + category.key(), 1);
        }
        plan.add(Visit.obtain(new Obtain(tools, true, 0, 0, null)));
        plan.add(new Visit(null, VisitKind.PUT_AWAY));
    }

    private static final List<ToolKit.Category> KIT_ORDER = List.of(ToolKit.Category.PICKAXE,
            ToolKit.Category.AXE, ToolKit.Category.SHOVEL, ToolKit.Category.SWORD, ToolKit.Category.HOE);

    /**
     * Plan-act cycles an OBTAIN may take. A diamond pickaxe from nothing is about ten - gather, craft
     * a tool, gather with it, and so on up four tiers, then craft the targets - so this leaves room
     * for trips that come back short.
     */
    private static final int MAX_OBTAIN_ROUNDS = 30;

    /** How deeply a gather that needs a tool may start another OBTAIN for it. */
    private static final int MAX_OBTAIN_DEPTH = 3;

    /** What a GATHER visit mines, resolved when it's planned. */
    private record GatherSpec(String item, List<Block> blocks, ToolKit.Category tool, int minHarvest,
                              boolean toolRequired) {}

    /** Plans a GATHER job - see {@link #gatherVisits}. */
    private void buildGatherPlan(Deque<Visit> plan, Job job) {
        List<Visit> visits = gatherVisits(job.itemId, job.count, 0);
        if (visits != null) {
            plan.addAll(visits);
        }
    }

    /**
     * What gathering {@code requested} involves: the item actually counted, the blocks that drop
     * it, and the tool mining them takes. Null if no block drops it.
     *
     * <p>The tool comes from recipes.json's {@code requiresTool}/{@code minTier} when it says, and
     * otherwise from the blocks' own mineable tags - an axe for logs, a shovel for sand. It's only
     * {@code toolRequired} when the blocks drop nothing without one; logs just come slower by hand.
     */
    private GatherSpec resolveGather(String requested) {
        GatherPlanner.Target target = GatherPlanner.resolve(recipeBook, dropIndex(), requested);
        List<Block> blocks = new ArrayList<>();
        for (String id : target.blocks()) {
            ResourceLocation location = ResourceLocation.tryParse(id);
            Block block = location == null ? null : BuiltInRegistries.BLOCK.getOptional(location).orElse(null);
            if (block != null && !block.defaultBlockState().isAir()) {
                blocks.add(block);
            }
        }
        if (blocks.isEmpty()) {
            return null;
        }
        ToolKit.Category tool = target.tool();
        boolean required = tool != null;
        int minHarvest = target.minHarvest();
        for (Block block : blocks) {
            BlockState state = block.defaultBlockState();
            if (tool == null) {
                tool = mineableWith(state);
            }
            required |= state.requiresCorrectToolForDrops();
            minHarvest = Math.max(minHarvest, harvestLevelNeeded(state));
        }
        return new GatherSpec(target.item(), List.copyOf(blocks), tool, minHarvest, required && tool != null);
    }

    /**
     * The visits that gather {@code count} of {@code requested}: make sure the bot has a suitable
     * tool, then one GATHER visit that mines until the inventory fills or the count is reached.
     * Each trip queues its own put-away and, if more is wanted, the next trip - see
     * {@link #endGatherTrip}. Returns null, having warned, if it can't be gathered at all.
     *
     * <p>A block that only drops with a tool gets one: the best to hand or in storage, else a cheap
     * one crafted, else an OBTAIN that works one up from raw materials. A block that drops anyway
     * (logs) is mined bare-handed rather than starting a tool chain for it.
     */
    private List<Visit> gatherVisits(String requested, int count, int depth) {
        GatherSpec spec = resolveGather(requested);
        if (spec == null) {
            warn("No block drops {} - gathering only covers what blocks drop, not mob drops", requested);
            return null;
        }
        List<Visit> visits = new ArrayList<>();
        if (spec.tool() != null) {
            Deque<Visit> equip = new ArrayDeque<>();
            if (planEquip(equip, spec.tool(), spec.minHarvest(), true, false) != null) {
                visits.addAll(equip);
            } else if (!spec.toolRequired()) {
                warn("No {} to hand - gathering {} bare-handed", spec.tool().key(), spec.item());
            } else if (depth < MAX_OBTAIN_DEPTH) {
                visits.add(Visit.obtainTool(ToolKit.cheapestTool(spec.tool(), spec.minHarvest()), depth + 1));
            } else {
                warn("Gathering {} needs a {} that mines at {} level or better, and there's no way to get one",
                        spec.item(), spec.tool().key(), tierName(spec.minHarvest()));
                return null;
            }
        }
        StorageManager.LOGGER.info("Gathering {}x {} from {}", count, spec.item(),
                spec.blocks().stream().map(block -> BuiltInRegistries.BLOCK.getKey(block).toString()).toList());
        visits.add(Visit.gather(spec, count, 0));
        return visits;
    }

    /**
     * Expands an OBTAIN into its next round, followed by another OBTAIN to re-plan once that round
     * has run. Targets the bot already carries are equipped, and ones in storage withdrawn, before
     * any planning. The rest go to {@link ChainPlanner}, which looks at the whole tree at once:
     * <ul>
     *   <li>GATHER - every raw material the bot can already mine, in the full amount the whole
     *       remaining chain needs, so each material takes one trip rather than one per tool;</li>
     *   <li>CRAFT a tool - when nothing more can be mined without it (a wooden pickaxe before any
     *       cobblestone). It's equipped first so the craft's put-away leaves it with the bot;</li>
     *   <li>CRAFT a target - once nothing is missing. A craft-mode OBTAIN ends there; an equip-mode
     *       one re-plans, which finds the tool carried and moves on to the next.</li>
     * </ul>
     * Returns the visits to run next - empty when done, or given up with a warning.
     */
    private List<Visit> planObtain(Visit visit) {
        Obtain goal = visit.obtain();
        Map<String, Integer> targets = new LinkedHashMap<>(goal.targets());
        Set<BlockPos> reserved = reservedChests();
        List<Visit> next = new ArrayList<>();
        if (goal.equip()) {
            Iterator<Map.Entry<String, Integer>> it = targets.entrySet().iterator();
            while (it.hasNext()) {
                String tool = it.next().getKey();
                ToolKit.Category category = ToolKit.categoryOf(tool);
                if (category == null) {
                    warn("Can't equip {} - not a tool", tool);
                    it.remove();
                    continue;
                }
                if (botCarriesAny(tool)) {
                    index.setEquippedTool(category.key(), tool);
                    it.remove();
                    continue;
                }
                List<StorageIndex.Contribution> stored = index.findItem(tool, 1, reserved);
                if (!stored.isEmpty()) {
                    index.setEquippedTool(category.key(), tool);
                    next.add(new Visit(stored.getFirst().chestPos(), VisitKind.WITHDRAW_ITEM, tool,
                            stored.getFirst().slot()));
                    it.remove();
                }
            }
        }
        if (targets.isEmpty()) {
            return next;
        }
        if (goal.rounds() >= MAX_OBTAIN_ROUNDS) {
            warn("Gave up getting {} after {} rounds", targets.keySet(), goal.rounds());
            return next;
        }

        ChainPlanner.Step step = ChainPlanner.next(recipeBook, targets, chainWorld(reserved));
        StorageManager.LOGGER.info("Getting {}: {} {} - chain still short of {}, tools on the way {}",
                targets, step.action(), step.action() == ChainPlanner.Action.STUCK ? step.problem() : step.items(),
                step.missing(), step.tools());
        switch (step.action()) {
            case DONE -> {
                return next;
            }
            case STUCK -> {
                warn("Can't get {} - {}", targets.keySet(), step.problem());
                return next;
            }
            case GATHER -> {
                if (step.missing().equals(goal.lastGather())) {
                    warn("Gathering for {} brought nothing back - still short of {}", targets.keySet(), step.missing());
                    return next;
                }
                for (Map.Entry<String, Integer> material : step.items().entrySet()) {
                    List<Visit> gather = gatherVisits(material.getKey(), material.getValue(), goal.depth());
                    if (gather == null) {
                        return next;
                    }
                    next.addAll(gather);
                }
                next.add(Visit.obtain(goal.next(step.missing())));
                return next;
            }
            case CRAFT -> {
                Map.Entry<String, Integer> craft = step.items().entrySet().iterator().next();
                String item = craft.getKey();
                boolean finalCraft = !goal.equip() && targets.containsKey(item);
                ToolKit.Category category = ToolKit.categoryOf(item);
                if (category != null && !finalCraft) {
                    index.setEquippedTool(category.key(), item);
                }
                Deque<Visit> steps = new ArrayDeque<>();
                buildCraftPlan(steps, Job.craft(item, craft.getValue()));
                if (steps.isEmpty()) {
                    return next; // buildCraftPlan said why - no table, no furnace
                }
                next.addAll(steps);
                if (!finalCraft) {
                    next.add(Visit.obtain(goal.next(null)));
                }
                return next;
            }
        }
        return next;
    }

    /** The planner's view of the game: storage counts, what mining takes, and the tools the bot can get at. */
    private ChainPlanner.World chainWorld(Set<BlockPos> reserved) {
        ToIntFunction<String> stock = storageCount(reserved);
        return new ChainPlanner.World() {
            @Override
            public int stock(String item) {
                return stock.applyAsInt(item);
            }

            @Override
            public ChainPlanner.Requirement mineRequirement(String item) {
                GatherSpec spec = resolveGather(item);
                if (spec == null) {
                    return null;
                }
                return spec.toolRequired()
                        ? new ChainPlanner.Requirement(spec.tool(), spec.minHarvest())
                        : ChainPlanner.Requirement.BARE_HANDS;
            }

            @Override
            public boolean canMine(ChainPlanner.Requirement requirement) {
                for (String tool : ToolKit.toolsBestFirst(requirement.tool())) {
                    if (ToolKit.harvestLevel(tool) < requirement.minHarvest()) {
                        return false;
                    }
                    if (botCarriesAny(tool) || !index.findItem(tool, 1, reserved).isEmpty()) {
                        return true;
                    }
                }
                return false;
            }
        };
    }

    /** Built on the first GATHER rather than at startup - it reads a thousand-odd loot tables. */
    private DropIndex dropIndex;

    private DropIndex dropIndex() {
        if (dropIndex == null) {
            long start = System.currentTimeMillis();
            List<String> blocks = BuiltInRegistries.BLOCK.keySet().stream().map(Object::toString).toList();
            dropIndex = DropIndex.build(blocks, JobExecutor::readBlockLootTable);
            StorageManager.LOGGER.info("Indexed block drops from {} loot tables in {}ms", blocks.size(),
                    System.currentTimeMillis() - start);
            if (dropIndex.isEmpty()) {
                StorageManager.LOGGER.warn("No block loot tables readable - gathering falls back to recipes.json");
            }
        }
        return dropIndex;
    }

    /** A block's vanilla loot table, off the game jar's built-in datapack, or null if it has none. */
    private static String readBlockLootTable(String blockId) {
        int colon = blockId.indexOf(':');
        String path = "/data/" + blockId.substring(0, colon) + "/loot_table/blocks/" + blockId.substring(colon + 1) + ".json";
        try (java.io.InputStream in = Block.class.getResourceAsStream(path)) {
            return in == null ? null : new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            return null;
        }
    }

    private static ToolKit.Category mineableWith(BlockState state) {
        if (state.is(BlockTags.MINEABLE_WITH_PICKAXE)) {
            return ToolKit.Category.PICKAXE;
        }
        if (state.is(BlockTags.MINEABLE_WITH_AXE)) {
            return ToolKit.Category.AXE;
        }
        if (state.is(BlockTags.MINEABLE_WITH_SHOVEL)) {
            return ToolKit.Category.SHOVEL;
        }
        if (state.is(BlockTags.MINEABLE_WITH_HOE)) {
            return ToolKit.Category.HOE;
        }
        return null;
    }

    private static int harvestLevelNeeded(BlockState state) {
        if (state.is(BlockTags.NEEDS_DIAMOND_TOOL)) {
            return 3;
        }
        if (state.is(BlockTags.NEEDS_IRON_TOOL)) {
            return 2;
        }
        return state.is(BlockTags.NEEDS_STONE_TOOL) ? 1 : 0;
    }

    private static String tierName(int harvestLevel) {
        return switch (harvestLevel) {
            case 0 -> "wood";
            case 1 -> "stone";
            case 2 -> "iron";
            default -> "diamond";
        };
    }

    /**
     * Makes sure the bot will have a {@code category} tool that mines at {@code minHarvest} or
     * better, equipping it and appending whatever visits fetching it takes. Returns the equipped
     * id, or null if there's no such tool to be had.
     *
     * <p>Unless {@code upgrade} is set, a suitable tool already equipped and carried is kept -
     * gathering shouldn't send the bot back to storage just because a better pickaxe exists.
     * Otherwise the best tool wins, whether the bot is already carrying it or it's in storage. Only
     * when neither has one (and {@code allowCraft}) is one crafted, choosing a cheap durable tier
     * rather than burning diamonds on an axe.
     *
     * <p>The tool is equipped at plan time, before it's fetched. That's harmless - an equipped id
     * the bot doesn't carry protects nothing - and it's what keeps a crafted tool from being put
     * away by the craft's own put-away visits.
     */
    private String planEquip(Deque<Visit> plan, ToolKit.Category category, int minHarvest,
                             boolean allowCraft, boolean upgrade) {
        String current = index.getEquippedTools().get(category.key());
        if (!upgrade && current != null && botCarriesAny(current) && ToolKit.harvestLevel(current) >= minHarvest) {
            return current;
        }
        Set<BlockPos> reserved = reservedChests();
        for (String candidate : ToolKit.toolsBestFirst(category)) {
            if (ToolKit.harvestLevel(candidate) < minHarvest) {
                break; // best-first, so everything after this is weaker too
            }
            if (botCarriesAny(candidate)) {
                index.setEquippedTool(category.key(), candidate);
                return candidate;
            }
            List<StorageIndex.Contribution> stored = index.findItem(candidate, 1, reserved);
            if (!stored.isEmpty()) {
                index.setEquippedTool(category.key(), candidate);
                plan.add(new Visit(stored.getFirst().chestPos(), VisitKind.WITHDRAW_ITEM, candidate,
                        stored.getFirst().slot()));
                return candidate;
            }
        }
        if (!allowCraft) {
            return null;
        }
        for (String candidate : ToolKit.craftCandidates(category, minHarvest)) {
            if (canCraft(candidate)) {
                index.setEquippedTool(category.key(), candidate);
                buildCraftPlan(plan, Job.craft(candidate, 1));
                return candidate;
            }
        }
        return null;
    }

    /** Whether a CRAFT of one {@code itemId} would go ahead right now, without warning if not. */
    private boolean canCraft(String itemId) {
        if (recipeBook.recipesFor(itemId).isEmpty()) {
            return false;
        }
        CraftPlanner.Plan craft = CraftPlanner.plan(recipeBook, storageCount(reservedChests()), itemId, 1);
        return craft.possible()
                && (!craft.needsCraftingTable() || index.getCraftingTable() != null)
                && (!craft.needsFurnace() || index.getFurnace() != null);
    }

    private ToIntFunction<String> storageCount(Set<BlockPos> reserved) {
        return item -> index.findItem(item, Integer.MAX_VALUE, reserved).stream()
                .mapToInt(StorageIndex.Contribution::count).sum();
    }

    /**
     * Plans a CRAFT job. {@link CraftPlanner} decides, ingredient by ingredient, whether storage
     * already has enough or the shortfall has to be crafted first (sticks from planks, planks from
     * logs, ...). The bot then pulls every raw ingredient in one sweep, runs each craft at the
     * configured table in dependency order, and puts the result - plus any leftovers - away.
     * Bails out (leaving {@code plan} empty) rather than attempting a partial craft if anything
     * in the chain can't be sourced or no table is configured.
     */
    private void buildCraftPlan(Deque<Visit> plan, Job job) {
        if (recipeBook.recipesFor(job.itemId).isEmpty()) {
            warn("No known recipe for {} - add one to recipes.json", job.itemId);
            return;
        }
        Set<BlockPos> reserved = reservedChests();
        CraftPlanner.Plan craft = CraftPlanner.plan(recipeBook, storageCount(reserved), job.itemId, job.count);
        if (!craft.possible()) {
            String missing = craft.missing().entrySet().stream()
                    .map(e -> e.getValue() + "x " + e.getKey())
                    .collect(Collectors.joining(", "));
            warn("Can't craft {}x {} - not enough in storage, short: {}", job.count, job.itemId, missing);
            return;
        }
        BlockPos table = index.getCraftingTable();
        if (craft.needsCraftingTable() && table == null) {
            warn("No crafting table configured - set one in the Setup panel");
            return;
        }
        BlockPos furnace = index.getFurnace();
        if (craft.needsFurnace() && furnace == null) {
            warn("{} needs smelting but no furnace is configured - set one in the Setup panel", job.itemId);
            return;
        }
        StorageManager.LOGGER.info("Crafting {}x {}: withdraw {}, then {}", job.count, job.itemId,
                craft.withdrawals(), craft.steps().stream()
                        .map(step -> (step.smelt() ? "smelt " : "craft ") + step.crafts() + "x " + step.item())
                        .collect(Collectors.joining(" -> ")));
        Set<String> touched = new LinkedHashSet<>();
        for (Map.Entry<String, Integer> withdrawal : craft.withdrawals().entrySet()) {
            for (StorageIndex.Contribution c : index.findItem(withdrawal.getKey(), withdrawal.getValue(), reserved)) {
                plan.add(new Visit(c.chestPos(), VisitKind.WITHDRAW_ITEM, withdrawal.getKey(), c.slot()));
            }
            touched.add(withdrawal.getKey());
        }
        for (CraftPlanner.Step step : craft.steps()) {
            plan.add(step.smelt() ? Visit.smelt(furnace, step) : Visit.craft(table, step.item(), step.crafts()));
            touched.add(step.item());
        }
        // The result first, then anything left over: whole stacks get withdrawn even when only a
        // few items were needed, and a craft can make more than it used (4 sticks when 2 were
        // needed). Deposits of items the bot turns out not to hold are skipped in advanceVisit.
        touched.remove(job.itemId);
        List<String> putAway = new ArrayList<>();
        putAway.add(job.itemId);
        putAway.addAll(touched);
        for (String item : putAway) {
            BlockPos destination = index.depositCandidates(item, reserved).stream().findFirst().orElse(null);
            if (destination != null) {
                plan.add(new Visit(destination, VisitKind.DEPOSIT_ITEM, item));
            } else if (item.equals(job.itemId)) {
                warn("No storage chest to deposit crafted {} into - it'll stay in the bot's inventory", item);
            }
        }
    }

    /**
     * Groups the chest Y levels present into floors, splitting wherever there's a vertical gap
     * bigger than {@link #SAME_FLOOR_GAP}. Derived from the targets rather than configured, so a
     * glass divider, a walkway or a change of level is picked up automatically.
     */
    private static Map<Integer, Integer> floorBands(List<BlockPos> targets) {
        List<Integer> levels = targets.stream().map(BlockPos::getY).distinct().sorted().toList();
        Map<Integer, Integer> floorOf = new HashMap<>();
        int floor = 0;
        Integer previous = null;
        for (int y : levels) {
            if (previous != null && y - previous > SAME_FLOOR_GAP) {
                floor++;
            }
            floorOf.put(y, floor);
            previous = y;
        }
        return floorOf;
    }

    /** The floor a Y sits on, falling back to the closest known level for positions off-grid. */
    private static int floorAt(int y, Map<Integer, Integer> floorOf) {
        Integer exact = floorOf.get(y);
        if (exact != null) {
            return exact;
        }
        int best = 0;
        int bestDistance = Integer.MAX_VALUE;
        for (Map.Entry<Integer, Integer> entry : floorOf.entrySet()) {
            int distance = Math.abs(entry.getKey() - y);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = entry.getValue();
            }
        }
        return best;
    }

    /**
     * Rough walking cost between two chests. Horizontal distance is what the bot actually walks;
     * vertical distance within a floor is free, because chests a block or two apart are opened
     * from the same standing position without moving. Crossing floors is charged heavily - that's
     * a trip to the stairs, and it's the move that should be made fewest times.
     */
    private static double travelCost(BlockPos from, BlockPos to, Map<Integer, Integer> floorOf) {
        double dx = from.getX() - to.getX();
        double dz = from.getZ() - to.getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        int floors = Math.abs(floorAt(from.getY(), floorOf) - floorAt(to.getY(), floorOf));
        return horizontal + floors * FLOOR_CHANGE_COST;
    }

    /**
     * Greedy nearest-neighbour walking order from {@code from}, costed by {@link #travelCost}
     * rather than straight-line distance. Not an optimal tour, but it keeps the bot on one floor
     * until that floor is done, and takes stacked chests together instead of once per level.
     */
    private static List<BlockPos> nearestFirst(List<BlockPos> targets, BlockPos from) {
        if (targets.size() < 2) {
            return new ArrayList<>(targets);
        }
        Map<Integer, Integer> floors = floorBands(targets);
        List<BlockPos> remaining = new ArrayList<>(targets);
        List<BlockPos> ordered = new ArrayList<>(remaining.size());
        BlockPos cursor = from;
        while (!remaining.isEmpty()) {
            BlockPos anchor = cursor;
            BlockPos nearest = Collections.min(remaining,
                    Comparator.comparingDouble(p -> travelCost(anchor, p, floors)));
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

    private boolean isCraftingTableBlock(BlockPos pos) {
        ClientLevel world = Minecraft.getInstance().level;
        if (world == null) {
            return true; // can't verify right now - don't falsely bail on a real table
        }
        return world.getBlockState(pos).getBlock() instanceof CraftingTableBlock;
    }

    /** Furnace, blast furnace or smoker - they share one menu, so the interactor handles all three. */
    private boolean isFurnaceBlock(BlockPos pos) {
        ClientLevel world = Minecraft.getInstance().level;
        if (world == null) {
            return false;
        }
        return world.getBlockState(pos).getBlock() instanceof AbstractFurnaceBlock;
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
        return nearestSingle(candidates, playerPos());
    }

    /**
     * Single nearest neighbour by Euclidean distance - no floor-aware routing, no greedy tour.
     * Used by {@link #nearestChestWithRoom()} when only the closest chest matters, where building
     * a floor map and running the full {@link #nearestFirst} tour is wasted work. O(n) instead
     * of the floor-aware path's O(n&middot;levels) per comparison.
     */
    private static BlockPos nearestSingle(List<BlockPos> targets, BlockPos from) {
        if (targets.isEmpty()) {
            return null;
        }
        return Collections.min(targets, Comparator.comparingDouble(p -> p.distSqr(from)));
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
            craftsRemaining = -1;
            smelt = null;
            gather = null;
            if (currentVisit.kind() == VisitKind.DEPOSIT_ITEM && !botHolds(currentVisit.item())) {
                // A leftover put-away planned before the craft ran - nothing was left over after all.
                continue;
            }
            if (currentVisit.kind() == VisitKind.OBTAIN || currentVisit.kind() == VisitKind.PUT_AWAY) {
                // Planning placeholders: expand into real visits now that earlier steps have run.
                List<Visit> steps = currentVisit.kind() == VisitKind.OBTAIN
                        ? planObtain(currentVisit) : putAwayVisits();
                for (int i = steps.size() - 1; i >= 0; i--) {
                    plan.addFirst(steps.get(i));
                }
                visitsDone--;
                continue;
            }
            if (currentVisit.kind() == VisitKind.GATHER) {
                navigator.cancel();
                phase = Phase.GATHERING;
                return true;
            }
            if (!navigator.hasArrived(currentVisit.pos(), ARRIVE_RANGE)) {
                navigator.goTo(currentVisit.pos());
                phase = Phase.PATHING;
                pathClosest = Double.MAX_VALUE;
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
        if (currentVisit.kind() == VisitKind.CRAFT) {
            if (!isCraftingTableBlock(currentVisit.pos())) {
                warn("No crafting table at {} anymore - check the Setup panel", currentVisit.pos());
                return false;
            }
            craftingInteractor.open(currentVisit.pos());
        } else if (currentVisit.kind() == VisitKind.SMELT) {
            if (!isFurnaceBlock(currentVisit.pos())) {
                warn("No furnace at {} anymore - check the Setup panel", currentVisit.pos());
                return false;
            }
            furnaceInteractor.open(currentVisit.pos());
        } else {
            if (!isContainerBlock(currentVisit.pos())) {
                // Whatever was here got broken/moved since the last scan - drop it from the index
                // instead of trying (and timing out) forever on a chest that no longer exists.
                warn("No container at {} anymore, removing from index", currentVisit.pos());
                index.removeChest(currentVisit.pos());
                return false;
            }
            interactor.open(currentVisit.pos());
        }
        phase = Phase.OPENING;
        phaseTicks = 0;
        return true;
    }

    /** Closest the current walk has come to its target, in blocks - progress resets the timeout. */
    private double pathClosest = Double.MAX_VALUE;

    private void tickPathing() {
        phaseTicks++;
        if (navigator.hasArrived(currentVisit.pos(), ARRIVE_RANGE)) {
            if (!openCurrentVisit()) {
                nextVisitOrFinish();
            }
            return;
        }
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null) {
            double distance = Math.sqrt(player.blockPosition().distSqr(currentVisit.pos()));
            if (distance < pathClosest - 1) {
                pathClosest = distance;
                phaseTicks = 0;
            }
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
        boolean craftVisit = currentVisit.kind() == VisitKind.CRAFT;
        boolean smeltVisit = currentVisit.kind() == VisitKind.SMELT;
        boolean open = craftVisit ? craftingInteractor.isOpen()
                : smeltVisit ? furnaceInteractor.isOpen()
                : interactor.isOpen();
        if (open) {
            phase = Phase.ACTING;
            phaseTicks = 0;
            return;
        }
        // The first attempt can lose a race with leftover Baritone input state - retry rather
        // than only trying once and waiting out the full timeout. Every 4 ticks (5/s, about as
        // fast as a person clicks) rather than every 10, so a dropped open costs 200ms not 500ms.
        if (phaseTicks % 4 == 0) {
            if (craftVisit) {
                craftingInteractor.open(currentVisit.pos());
            } else if (smeltVisit) {
                furnaceInteractor.open(currentVisit.pos());
            } else {
                interactor.open(currentVisit.pos());
            }
        }
        if (phaseTicks > OPEN_TIMEOUT_TICKS) {
            warn("{} at {} never opened, skipping",
                    craftVisit ? "Crafting table" : smeltVisit ? "Furnace" : "Chest", currentVisit.pos());
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
            case SHUFFLE_GRAB -> tickShuffleGrab();
            case SHUFFLE_DROP -> tickShuffleDrop();
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
            case READ_INPUT -> tickReadInput();
            case CRAFT -> tickCraft();
            case SMELT -> tickSmelt();
        }
    }

    /**
     * Unloads every stack of one item into this chest - the consolidating half of a sort. Capped
     * to {@link #MOVES_PER_TICK} quick-moves per call so a multi-stack drop spreads across several
     * ticks instead of firing in a single packet burst (the same rationale that already drives
     * {@link #tickShuffleChest()} and {@link #tickDumpAll()}). When
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

    /**
     * Crafts still owed on the current CRAFT visit. -1 means "not yet started" - the first
     * {@link #tickCraft()} call copies it from the visit's planned {@code crafts}.
     */
    private int craftsRemaining = -1;

    /**
     * Per-job state for {@link #tickShuffleGrab()} / {@link #tickShuffleDrop()}. {@code remaining}
     * is the in-memory "finished" tracker built once at job start from a snapshot of every visited
     * chest - one entry per stack, decremented when that stack is deposited somewhere new.
     * {@code batchDestinations} is the set of chests already used by the current batch's DROP
     * visits, so a stack never lands in the same chest as another stack in the same batch.
     * {@code leftovers} holds item ids whose DROP visit bailed out (the retry cap was hit); the
     * drain pass picks them up at job end.
     */
    private Map<String, Integer> randomizeRemaining;
    private Set<BlockPos> randomizeBatchDestinations;
    private List<String> randomizeLeftovers;

    /**
     * Max inventory slots a SHUFFLE_GRAB visit picks up per batch (one hotbar's worth) - slots,
     * not item counts, and every slot taken counts toward it.
     */
    private static final int RANDOMIZE_BATCH_SIZE = 9;

    /**
     * Retry cap for a SHUFFLE_DROP visit. With many chests in the room, half the room is plenty
     * to find a destination; if that fails the destination really doesn't exist and we should bail
     * out and let the drain pass handle it. Hard floor of 5 keeps very small rooms workable; hard
     * ceiling of 20 stops an over-large room from looping unnecessarily.
     */
    private static int randomizeRetryCap(int knownChestCount) {
        return Math.max(5, Math.min(20, (knownChestCount + 1) / 2));
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
     * Builds the next batch's worth of RANDOMIZE visits (one SHUFFLE_GRAB followed by up to
     * RANDOMIZE_BATCH_SIZE SHUFFLE_DROPs) and appends them to {@link #plan}. Called once at job
     * start, then re-invoked at the end of each batch to build the next one. Each batch is
     * independent so the bot never walks back to a pull chest unless it's still the best pick.
     */
    private void appendRandomizeBatch() {
        if (randomizeRemaining.isEmpty()) {
            return;
        }
        BlockPos pullChest = pickRandomizePullChest();
        if (pullChest == null) {
            return;
        }
        randomizeBatchDestinations.clear();
        plan.add(new Visit(pullChest, VisitKind.SHUFFLE_GRAB));
    }

    /**
     * Picks a random pull chest for the next batch. Excludes chests the bot is currently at (so a
     * batch's pull and its drops can use different chests - the user's "separate" rule) and chests
     * already known to be empty of remaining items (visiting one just to find nothing wastes a
     * trip). Returns null when no candidate exists; the caller treats that as a job-finish signal.
     */
    private BlockPos pickRandomizePullChest() {
        Set<BlockPos> reserved = reservedChests();
        Set<String> neededItems = new HashSet<>();
        for (Map.Entry<String, Integer> entry : randomizeRemaining.entrySet()) {
            if (entry.getValue() > 0) {
                neededItems.add(entry.getKey());
            }
        }
        BlockPos botPos = currentVisit == null ? playerPos() : currentVisit.pos();
        List<BlockPos> candidates = new ArrayList<>();
        for (StorageIndex.ChestEntry chest : index.allChests()) {
            BlockPos pos = chest.pos.toBlockPos();
            if (reserved.contains(pos) || pos.equals(botPos) || chest.lastScanned <= 0) {
                continue;
            }
            // Only chests that still hold at least one item we need - if a chest only holds
            // items we've already finished, there's nothing useful to pull from it.
            boolean useful = false;
            for (StorageIndex.SlotEntry slot : chest.slots) {
                Integer remaining = randomizeRemaining.get(slot.item);
                if (remaining != null && remaining > 0) {
                    useful = true;
                    break;
                }
            }
            if (useful) {
                candidates.add(pos);
            }
        }
        if (candidates.isEmpty()) {
            return null;
        }
        Collections.shuffle(candidates);
        return candidates.getFirst();
    }

    /**
     * SHUFFLE_GRAB visit: pull up to RANDOMIZE_BATCH_SIZE slots from this chest's contents,
     * skipping slots whose item has already hit zero in the remaining tracker (those are surplus
     * from earlier batches and would just clutter the inventory) and slots with no valid
     * destination (those stay put). Each slot pulled gets a SHUFFLE_DROP visit against the
     * destination chosen just before taking it.
     */
    private void tickShuffleGrab() {
        BlockPos pos = currentVisit.pos();
        List<StorageIndex.SlotEntry> contents = interactor.snapshotContainerSlots();
        Set<String> heldInChest = new HashSet<>();
        for (StorageIndex.SlotEntry slot : contents) {
            heldInChest.add(slot.item);
        }
        int pulled = 0;
        for (StorageIndex.SlotEntry slot : contents) {
            if (pulled >= RANDOMIZE_BATCH_SIZE) {
                break;
            }
            if (interactor.countFreePlayerSlots() <= 0) {
                break;
            }
            Integer remaining = randomizeRemaining.get(slot.item);
            // Slot's item isn't tracked (shouldn't happen - we only built remaining from the
            // same visited-chests source), or is already finished. Either way: leave it.
            if (remaining == null || remaining <= 0) {
                continue;
            }
            // Pick the destination BEFORE taking the slot, so a slot with nowhere to go stays in
            // the chest instead of being picked up uncounted - that's how a chest of pickaxes used
            // to fill the whole inventory. Re-validated against the live container on arrival -
            // that's the SHUFFLE_DROP visit's job - so a chest that fills up in between gets caught.
            BlockPos destination = pickRandomizeDropDestination(slot.item, pos, List.of());
            if (destination == null) {
                continue;
            }
            interactor.quickMove(slot.slot);
            // Confirm the move landed - same defensive check shuffleRound uses, since quickMove
            // is fire-and-forget client-side and the server can reject silently.
            if (heldInChest.contains(interactor.containerItemAt(slot.slot))) {
                continue;
            }
            // Reserve the destination so the next slot in this batch doesn't pick the same one.
            randomizeBatchDestinations.add(destination);
            plan.add(new Visit(destination, VisitKind.SHUFFLE_DROP, slot.item));
            pulled++;
        }
        recordSnapshot(pos);
        // If we pulled nothing, the chest was already drained of needed items (or full of
        // unrelated items we don't want to disturb). Move on to the next batch by rebuilding
        // the plan rather than booking a no-op round trip.
        if (pulled == 0) {
            appendRandomizeBatch();
        }
        finishVisit();
    }

    /**
     * Picks a destination chest for one stack of {@code item}, excluding the pull chest, anything
     * the bot has already tried, anything already used by the current batch (strict rule), and
     * anything that already holds this item. Returns null when no candidate survives those filters;
     * the caller can decide whether to relax or defer.
     */
    private BlockPos pickRandomizeDropDestination(String item, BlockPos exclude, List<BlockPos> tried) {
        int cap = randomizeRetryCap(countVisitedChests());
        Set<BlockPos> reserved = reservedChests();
        Set<BlockPos> triedSet = new HashSet<>(tried);
        List<StorageIndex.ChestEntry> candidates = new ArrayList<>();
        for (StorageIndex.ChestEntry chest : index.allChests()) {
            BlockPos pos = chest.pos.toBlockPos();
            if (reserved.contains(pos) || triedSet.contains(pos) || pos.equals(exclude)) {
                continue;
            }
            if (randomizeBatchDestinations.contains(pos)) {
                continue;
            }
            if (freeSlots(chest) <= 0) {
                continue;
            }
            // Skip chests that already hold this item - the existing shuffle invariant. Means we
            // never collapse two stacks of the same item into one chest during a pass.
            boolean alreadyHolds = false;
            for (StorageIndex.SlotEntry slot : chest.slots) {
                if (item.equals(slot.item)) {
                    alreadyHolds = true;
                    break;
                }
            }
            if (alreadyHolds) {
                continue;
            }
            candidates.add(chest);
        }
        Collections.shuffle(candidates);
        BlockPos picked = null;
        int attempts = 0;
        for (StorageIndex.ChestEntry chest : candidates) {
            if (attempts >= cap) {
                break;
            }
            picked = chest.pos.toBlockPos();
            attempts++;
        }
        return picked;
    }

    /**
     * SHUFFLE_DROP visit: walk to the destination chest, open it, quickMove one stack in.
     * The destination was chosen at GRAB time; this visit's job is just the navigation + the
     * deposit, with a live-container check on arrival to catch chests that filled up since the
     * batch was planned.
     */
    private void tickShuffleDrop() {
        String item = currentVisit.item();
        BlockPos destination = currentVisit.pos();
        // Live check: the chest may have filled up between GRAB time and now.
        if (interactor.containerSlotCount() <= interactor.snapshotContainerSlots().size()) {
            // Try to find a replacement destination, otherwise park for drain.
            BlockPos replacement = pickRandomizeDropDestination(item, destination,
                    new ArrayList<>(currentVisit.triedChests()));
            if (replacement == null) {
                warn("Destination {} filled up and no alternative for {} - deferring to drain", destination, item);
                randomizeLeftovers.add(item);
                finishVisit();
                return;
            }
            // Substitute the destination in-place: cancel this visit, queue a new one against
            // the replacement with the original destination added to triedChests.
            randomizeBatchDestinations.remove(destination);
            randomizeBatchDestinations.add(replacement);
            plan.addFirst(new Visit(replacement, VisitKind.SHUFFLE_DROP, item));
            finishVisit();
            return;
        }
        int slot = interactor.findPlayerSlotWithItem(item);
        if (slot < 0) {
            // Stack gone (server rejected earlier, another mod intervened). Just finish.
            finishVisit();
            return;
        }
        interactor.quickMove(slot);
        int before = interactor.countPlayerItems(item);
        if (interactor.countPlayerItems(item) >= before) {
            // Server rejected the move - chest really was full even though containerSlotCount
            // said there was room (e.g. single-chest sized as 54, double-chest with no room).
            // Mark this destination as tried and re-pick.
            randomizeBatchDestinations.remove(destination);
            BlockPos replacement = pickRandomizeDropDestination(item, destination,
                    mergeTried(currentVisit.triedChests(), destination));
            if (replacement == null) {
                randomizeLeftovers.add(item);
                finishVisit();
                return;
            }
            randomizeBatchDestinations.add(replacement);
            plan.addFirst(new Visit(replacement, VisitKind.SHUFFLE_DROP, item));
            finishVisit();
            return;
        }
        // Success: mark this stack finished.
        decrementRemaining(item);
        recordSnapshot(destination);
        // If everything is finished, kick off the drain pass for any leftovers accumulated
        // from earlier batches.
        if (randomizeRemaining.isEmpty() && !randomizeLeftovers.isEmpty()) {
            plan.addAll(drainVisits(destination));
        }
        finishVisit();
    }

    private static List<BlockPos> mergeTried(List<BlockPos> existing, BlockPos extra) {
        List<BlockPos> merged = new ArrayList<>(existing);
        merged.add(extra);
        return merged;
    }

    /** Counts chests the index actually knows the contents of - used to size the retry cap. */
    private int countVisitedChests() {
        int n = 0;
        for (StorageIndex.ChestEntry chest : index.allChests()) {
            if (chest.lastScanned > 0 && !reservedChests().contains(chest.pos.toBlockPos())) {
                n++;
            }
        }
        return n;
    }

    private void decrementRemaining(String item) {
        Integer count = randomizeRemaining.get(item);
        if (count == null) {
            return;
        }
        if (count <= 1) {
            randomizeRemaining.remove(item);
        } else {
            randomizeRemaining.put(item, count - 1);
        }
    }

    /**
     * First call into the RANDOMIZE plan - replaces the old one-visit-per-chest loop. Builds the
     * first batch of GRAB + DROP visits; subsequent batches are appended by SHUFFLE_GRAB itself
     * (or by the drain pass if it kicks in mid-plan).
     */
    private List<Visit> buildRandomizeBatches() {
        List<Visit> initial = new ArrayList<>();
        if (randomizeRemaining.isEmpty()) {
            // Nothing in any visited chest - the user clicked Randomize on a freshly scanned
            // (but unvisited) region, or on an empty storage room. Surface a warning instead of
            // booking a useless walk.
            warn("No visited storage chests to randomize - run a scan and visit the chests first");
            return initial;
        }
        appendRandomizeBatchTo(initial);
        return initial;
    }

    /** Same as {@link #appendRandomizeBatch()} but into a caller-supplied list. */
    private void appendRandomizeBatchTo(List<Visit> into) {
        if (randomizeRemaining.isEmpty()) {
            return;
        }
        BlockPos pullChest = pickRandomizePullChest();
        if (pullChest == null) {
            return;
        }
        randomizeBatchDestinations.clear();
        into.add(new Visit(pullChest, VisitKind.SHUFFLE_GRAB));
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

/**
     * CRAFT visit: fills the crafting grid from the recipe and collects the result, repeating
     * until {@link #craftsRemaining} reaches zero or ingredients run out. Each cycle only ever
     * places one item per grid cell (see {@link CraftingInteractor#placeIngredient}), so one
     * quick-move on the result slot drains exactly one batch - {@link #craftsRemaining} is what
     * carries the job's requested count across as many cycles/ticks as it takes.
     */
    private void tickCraft() {
        Recipe recipe = recipeBook.craftFor(currentVisit.item());
        if (recipe == null) {
            // buildCraftPlan already validated this recipe exists - only reachable if
            // recipes.json changed mid-job, which never happens today. Bail rather than NPE.
            warn("Recipe for {} vanished mid-job", currentVisit.item());
            finishVisit();
            return;
        }
        if (craftsRemaining < 0) {
            craftsRemaining = currentVisit.crafts();
        }
        if (craftsRemaining <= 0) {
            finishVisit();
            return;
        }
        if (phaseTicks > ACTING_TIMEOUT_TICKS) {
            warn("Timed out crafting {}, {} batch(es) short", currentVisit.item(), craftsRemaining);
            craftingInteractor.clearGrid();
            finishVisit();
            return;
        }
        boolean placedAll = true;
        if (recipe.shaped && recipe.shape != null) {
            for (Recipe.ShapeEntry cell : recipe.shape) {
                int slot = CraftingInteractor.gridSlot(cell.x, cell.y);
                if (craftingInteractor.gridItemAt(slot) == null) {
                    placedAll &= craftingInteractor.placeIngredient(slot, cell.item);
                }
            }
        } else if (!recipe.shaped && recipe.ingredients != null) {
            int i = 0;
            for (String item : recipe.ingredients) {
                int slot = CraftingInteractor.gridSlot(i % 3, i / 3);
                if (craftingInteractor.gridItemAt(slot) == null) {
                    placedAll &= craftingInteractor.placeIngredient(slot, item);
                }
                i++;
            }
        }
        if (!placedAll) {
            warn("Ran out of ingredients crafting {} - {} batch(es) short", currentVisit.item(), craftsRemaining);
            craftingInteractor.clearGrid();
            finishVisit();
            return;
        }
        if (craftingInteractor.hasResult()) {
            craftingInteractor.collectResult();
            craftsRemaining--;
            // The timeout guards against a stuck grid, not a long run - a chain needing dozens of
            // batches would otherwise hit 10s partway through while still making progress.
            phaseTicks = 0;
        }
    }

    /**
     * A vanilla furnace takes 10s an item, so this is a furnace that has stopped working - wrong
     * block type for the recipe (a smoker won't take raw iron), or something jammed - rather than
     * a slow one.
     */
    private static final long SMELT_STALL_TICKS = 20L * 25;

    /**
     * How long the furnace may sit unlit with input and nothing to burn before it counts as out of
     * fuel. A couple of seconds rather than one tick, so a fuel slot that just emptied as the next
     * item lights isn't mistaken for a dead furnace.
     */
    private static final long SMELT_UNFUELLED_TICKS = 40L;

    /** Per-visit state for {@link #tickSmelt()}, which keeps a visit open for as long as the batch takes. */
    private static final class SmeltState {
        int inputPlaced;
        int fuelPlaced;
        int collected;
        int lastInputCount = -1;
        long stalledTicks;
        long unfuelledTicks;
    }

    private SmeltState smelt;

    /**
     * SMELT visit: loads the planned input and fuel, then stays at the open furnace taking the
     * output as it appears until the whole batch is out. The bot waits rather than wandering off
     * because every later step in the chain is waiting on these items anyway.
     *
     * <p>Leaves early - pulling input, output and fuel back into the inventory so the put-away
     * visits return them to storage - if the furnace runs out of fuel, stalls, or the bot's
     * inventory fills up.
     */
    private void tickSmelt() {
        Recipe recipe = recipeBook.smeltFor(currentVisit.item());
        if (recipe == null) {
            warn("Recipe for {} vanished mid-job", currentVisit.item());
            finishVisit();
            return;
        }
        if (!furnaceInteractor.isOpen()) {
            // Something closed the screen mid-batch. The progress so far is kept - reopen and resume.
            furnaceInteractor.open(currentVisit.pos());
            phase = Phase.OPENING;
            phaseTicks = 0;
            return;
        }
        String result = currentVisit.item();
        String input = recipe.input;
        String fuel = currentVisit.items().get(1);
        int runs = currentVisit.crafts();
        int wanted = runs * Math.max(1, recipe.resultCount);

        if (smelt == null) {
            String inSlot = furnaceInteractor.itemAt(FurnaceInteractor.INPUT_SLOT);
            String outSlot = furnaceInteractor.itemAt(FurnaceInteractor.RESULT_SLOT);
            if ((inSlot != null && !inSlot.equals(input)) || (outSlot != null && !outSlot.equals(result))) {
                warn("Furnace at {} already has {} in it - empty it and try again", currentVisit.pos(),
                        inSlot != null && !inSlot.equals(input) ? inSlot : outSlot);
                finishVisit();
                return;
            }
            // Output left over from before isn't this batch's: take it now, uncounted, so the full
            // batch still goes in. It ends up in storage with the rest of the put-away.
            furnaceInteractor.takeOut(FurnaceInteractor.RESULT_SLOT);
            smelt = new SmeltState();
        }

        boolean progressed = false;
        if (smelt.inputPlaced < runs) {
            int placed = furnaceInteractor.place(FurnaceInteractor.INPUT_SLOT, input,
                    runs - smelt.inputPlaced, MOVES_PER_TICK);
            smelt.inputPlaced += placed;
            progressed |= placed > 0;
        }
        if (fuel != null && smelt.fuelPlaced < currentVisit.fuelCount()) {
            int placed = furnaceInteractor.place(FurnaceInteractor.FUEL_SLOT, fuel,
                    currentVisit.fuelCount() - smelt.fuelPlaced, MOVES_PER_TICK);
            smelt.fuelPlaced += placed;
            progressed |= placed > 0;
        }
        if (furnaceInteractor.itemAt(FurnaceInteractor.RESULT_SLOT) != null) {
            int before = furnaceInteractor.countPlayerItems(result);
            furnaceInteractor.takeOut(FurnaceInteractor.RESULT_SLOT);
            int got = furnaceInteractor.countPlayerItems(result) - before;
            if (got <= 0) {
                warn("Bot's inventory is full - couldn't take {} out of the furnace", result);
                endSmelt();
                return;
            }
            smelt.collected += got;
            progressed = true;
        }
        if (smelt.collected >= wanted) {
            endSmelt();
            return;
        }

        int inputCount = furnaceInteractor.countAt(FurnaceInteractor.INPUT_SLOT);
        if (inputCount != smelt.lastInputCount) {
            smelt.lastInputCount = inputCount;
            progressed = true;
        }
        boolean moreInput = inputCount > 0
                || (smelt.inputPlaced < runs && furnaceInteractor.countPlayerItems(input) > 0);
        if (!moreInput) {
            warn("Only smelted {} of {} {} - ran out of {}", smelt.collected, wanted, result, input);
            endSmelt();
            return;
        }
        boolean moreFuel = furnaceInteractor.isLit()
                || furnaceInteractor.itemAt(FurnaceInteractor.FUEL_SLOT) != null
                || (fuel != null && smelt.fuelPlaced < currentVisit.fuelCount()
                        && furnaceInteractor.countPlayerItems(fuel) > 0);
        smelt.unfuelledTicks = moreFuel ? 0 : smelt.unfuelledTicks + 1;
        if (smelt.unfuelledTicks > SMELT_UNFUELLED_TICKS) {
            warn("Furnace ran out of fuel - smelted {} of {} {}", smelt.collected, wanted, result);
            endSmelt();
            return;
        }
        smelt.stalledTicks = progressed ? 0 : smelt.stalledTicks + 1;
        if (smelt.stalledTicks > SMELT_STALL_TICKS) {
            warn("Furnace at {} stopped smelting {} ({} of {} done) - is it the right kind of furnace?",
                    currentVisit.pos(), input, smelt.collected, wanted);
            endSmelt();
        }
    }

    /**
     * Empties whatever this batch left in the furnace back into the bot's inventory - unsmelted
     * input on an early exit, and spare fuel either way - then moves on.
     */
    private void endSmelt() {
        furnaceInteractor.takeOut(FurnaceInteractor.RESULT_SLOT);
        furnaceInteractor.takeOut(FurnaceInteractor.INPUT_SLOT);
        String fuel = currentVisit.items().get(1);
        if (fuel != null && fuel.equals(furnaceInteractor.itemAt(FurnaceInteractor.FUEL_SLOT))) {
            furnaceInteractor.takeOut(FurnaceInteractor.FUEL_SLOT);
        }
        finishVisit();
    }

    /** Per-trip state for {@link #tickGather()}. */
    private static final class GatherState {
        /** Items of the target picked up this trip. Only ever counts up - see {@link #tickGather()}. */
        int gained;
        int lastCount;
        long ticks;
        long sinceProgress;
        /** Set when Baritone's mine needs (re)issuing: at the start, and after a pause cancelled it. */
        boolean restart = true;
        /** Whether the trip set out with its tool, so a tool that goes missing reads as broken. */
        boolean hadTool;
    }

    private GatherState gather;

    /**
     * GATHER visit: Baritone mines while this watches what the bot picks up. The trip ends when
     * the count is reached, the inventory is nearly full, the tool breaks, or Baritone runs out of
     * blocks to find - and immediately, before a second block goes, if Baritone starts breaking
     * something in or around the storage room.
     *
     * <p>Progress is counted as increases in the target item only. Baritone places cobblestone and
     * dirt as scaffolding, so the raw count can drop mid-trip; subtracting those would have the bot
     * gathering the same cobblestone twice.
     */
    private void tickGather() {
        GatherSpec spec = currentVisit.gather();
        String item = currentVisit.item();
        int wanted = currentVisit.crafts();
        if (gather == null) {
            gather = new GatherState();
            gather.lastCount = inventoryCount(item);
            gather.hadTool = spec.tool() != null && equippedToolCarried(spec.tool());
            if (spec.toolRequired() && !gather.hadTool) {
                // The fetch planned for it didn't deliver - chest gone, craft short of something.
                warn("Didn't get a {} to gather {} with - stopping", spec.tool().key(), item);
                finishVisit();
                return;
            }
            if (freeInventorySlots() <= GATHER_MIN_FREE_SLOTS) {
                warn("Bot's inventory is still full - storage had no room for the last load, so not gathering more {}",
                        item);
                finishVisit();
                return;
            }
        }
        if (gather.restart) {
            navigator.mine(spec.blocks());
            gather.restart = false;
            gather.ticks = 0;
        }
        gather.ticks++;

        int count = inventoryCount(item);
        if (count > gather.lastCount) {
            gather.gained += count - gather.lastCount;
            gather.sinceProgress = 0;
        } else {
            gather.sinceProgress++;
        }
        gather.lastCount = count;

        BlockPos breaking = blockBeingBroken();
        if (breaking != null && isProtected(breaking)) {
            warn("Stopped gathering {} - Baritone started breaking a block at {}, inside the storage area",
                    item, breaking);
            endGatherTrip(false, false);
            return;
        }
        if (gather.gained >= wanted) {
            endGatherTrip(false, false);
            return;
        }
        if (freeInventorySlots() <= GATHER_MIN_FREE_SLOTS) {
            endGatherTrip(true, false);
            return;
        }
        if (gather.hadTool && !equippedToolCarried(spec.tool())) {
            index.setEquippedTool(spec.tool().key(), null);
            if (currentVisit.fuelCount() >= MAX_TOOL_REEQUIPS) {
                warn("Wore out {} tools gathering {} - stopping with {} still to get",
                        MAX_TOOL_REEQUIPS, item, wanted - gather.gained);
                endGatherTrip(false, false);
            } else {
                endGatherTrip(true, true);
            }
            return;
        }
        if (gather.ticks > GATHER_START_GRACE_TICKS && !navigator.isMining()) {
            warn("Baritone can't find any more blocks for {} - stopping with {} still to get",
                    item, wanted - gather.gained);
            endGatherTrip(false, false);
            return;
        }
        if (gather.sinceProgress > GATHER_STALL_TICKS) {
            warn("Picked up no {} in {} minutes - stopping with {} still to get",
                    item, GATHER_STALL_TICKS / 1200, wanted - gather.gained);
            endGatherTrip(false, false);
        }
    }

    /**
     * Ends a gathering trip: stop mining, put away everything the bot is carrying other than its
     * tools, and if {@code again}, queue the next trip for whatever is still wanted - preceded by
     * fetching a replacement tool when {@code reequip}.
     */
    private void endGatherTrip(boolean again, boolean reequip) {
        navigator.cancel();
        GatherSpec spec = currentVisit.gather();
        int remaining = currentVisit.crafts() - gather.gained;
        gather = null;
        List<Visit> next = new ArrayList<>(putAwayVisits());
        if (again && remaining > 0) {
            int reequips = currentVisit.fuelCount();
            if (reequip) {
                reequips++;
                Deque<Visit> equip = new ArrayDeque<>();
                if (planEquip(equip, spec.tool(), spec.minHarvest(), true, false) != null) {
                    next.addAll(equip);
                } else if (spec.toolRequired()) {
                    // Nothing to hand, in storage or craftable - work one up from raw materials.
                    next.add(Visit.obtainTool(ToolKit.cheapestTool(spec.tool(), spec.minHarvest()), 1));
                }
            }
            next.add(Visit.gather(spec, remaining, reequips));
        }
        for (int i = next.size() - 1; i >= 0; i--) {
            plan.addFirst(next.get(i));
        }
        finishVisit();
    }

    /**
     * One DEPOSIT_ITEM per item type the bot carries (tools excluded), each aimed at a chest
     * already holding some, walked in nearest-first order. A chest turning out full is handled by
     * the deposit itself, which moves on to the next candidate.
     */
    private List<Visit> putAwayVisits() {
        refreshBotInventory();
        Set<String> items = new LinkedHashSet<>();
        for (WebServer.InventorySlot slot : botInventory) {
            if (slot.item() != null && !slot.kept()) {
                items.add(slot.item());
            }
        }
        Set<BlockPos> reserved = reservedChests();
        Map<BlockPos, List<String>> byChest = new LinkedHashMap<>();
        int unplaced = 0;
        for (String item : items) {
            BlockPos destination = index.depositCandidates(item, reserved).stream().findFirst().orElse(null);
            if (destination == null) {
                unplaced++;
                continue;
            }
            byChest.computeIfAbsent(destination, key -> new ArrayList<>()).add(item);
        }
        if (unplaced > 0) {
            warn("No storage chest known to put {} item type(s) in - they stay in the bot's inventory", unplaced);
        }
        List<Visit> visits = new ArrayList<>();
        for (BlockPos pos : nearestFirst(new ArrayList<>(byChest.keySet()), playerPos())) {
            for (String item : byChest.get(pos)) {
                visits.add(new Visit(pos, VisitKind.DEPOSIT_ITEM, item));
            }
        }
        return visits;
    }

    /** Cargo count of an item in the bot's inventory, not counting equipped tools. */
    private int inventoryCount(String itemId) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return 0;
        }
        Set<Integer> kept = keptToolSlots();
        int total = 0;
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (!stack.isEmpty() && !kept.contains(slot)
                    && BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().equals(itemId)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private int freeInventorySlots() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return 0;
        }
        int free = 0;
        for (int slot = 0; slot < 36; slot++) {
            if (player.getInventory().getItem(slot).isEmpty()) {
                free++;
            }
        }
        return free;
    }

    private boolean equippedToolCarried(ToolKit.Category category) {
        String tool = index.getEquippedTools().get(category.key());
        return tool != null && botCarriesAny(tool);
    }

    /** The block the bot is mid-way through breaking, or null. Baritone breaks through the vanilla crosshair. */
    private static BlockPos blockBeingBroken() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.gameMode == null || !mc.gameMode.isDestroying()) {
            return null;
        }
        return mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK
                ? hit.getBlockPos() : null;
    }

    /**
     * Whether a block is part of the storage setup: inside the region (plus a block of margin for
     * its walls) or next to one of the configured chests, table or furnace. Gathering cobblestone
     * next to a cobblestone storage room would otherwise take the walls down first - they're the
     * nearest cobblestone Baritone knows of.
     */
    private boolean isProtected(BlockPos pos) {
        StorageIndex.Region region = index.getRegion();
        if (region.min != null && region.max != null) {
            BlockPos a = region.min.toBlockPos();
            BlockPos b = region.max.toBlockPos();
            if (pos.getX() >= Math.min(a.getX(), b.getX()) - PROTECTED_MARGIN
                    && pos.getX() <= Math.max(a.getX(), b.getX()) + PROTECTED_MARGIN
                    && pos.getY() >= Math.min(a.getY(), b.getY()) - PROTECTED_MARGIN
                    && pos.getY() <= Math.max(a.getY(), b.getY()) + PROTECTED_MARGIN
                    && pos.getZ() >= Math.min(a.getZ(), b.getZ()) - PROTECTED_MARGIN
                    && pos.getZ() <= Math.max(a.getZ(), b.getZ()) + PROTECTED_MARGIN) {
                return true;
            }
        }
        for (BlockPos configured : new BlockPos[] {
                index.getInputChest(), index.getOutputChest(), index.getCraftingTable(), index.getFurnace()}) {
            if (configured != null && pos.distManhattan(configured) <= PROTECTED_MARGIN) {
                return true;
            }
        }
        return false;
    }

    /**
     * Empties the input chest by scattering each stack into an independently-chosen random
     * storage chest - two stacks of the same item intentionally end up in different chests
     * (this is the whole point of the sort: not consolidation). When more stacks of one item
     * are pulled than there are chests available, the surplus falls back to whatever chest
     * has room and the user is warned; the alternative is leaving stacks in the inventory,
     * which an unprioritised sort pass would never pick back up.
     */
    private void tickReadInput() {
        List<StorageIndex.SlotEntry> contents = interactor.snapshotContainerSlots();
        int capacity = interactor.countFreePlayerSlots();
        Set<BlockPos> reserved = reservedChests();
        // Refuse the whole pass if any item has nowhere to go - partial sorting would strand
        // the rest in the inventory, and the original per-item check fired once per pull.
        // Hoisting it out means a region that hasn't been scanned yet never half-drains.
        for (StorageIndex.SlotEntry entry : contents) {
            if (index.depositCandidates(entry.item, reserved).isEmpty()) {
                warn("No storage chest to sort {} into - rescan the region first", entry.item);
                recordSnapshot(currentVisit.pos());
                finishVisit();
                return;
            }
        }
        // Pull stacks one at a time, but track per-stack rather than per-item: with
        // identical items we want each slot to land in its own chest, and the old
        // LinkedHashSet<String> silently collapsed same-item stacks into one destination.
        List<StorageIndex.SlotEntry> pulled = new ArrayList<>();
        boolean outOfSpace = false;
        for (StorageIndex.SlotEntry entry : contents) {
            if (pulled.size() >= capacity) {
                // Input chest (e.g. a double chest) has more distinct stacks than the bot
                // can carry at once - grab what fits now and queue another sort pass for
                // the rest instead of trying to quick-move into a full inventory.
                outOfSpace = true;
                break;
            }
            // Actually pull it out of the input chest into the bot's own inventory -
            // reading the snapshot alone never removed anything.
            interactor.quickMove(entry.slot);
            pulled.add(entry);
        }
        // Per-stack routing. holds.get(pos) tracks which item ids this pass has already
        // sent to that chest, so two cobblestone stacks don't pile onto the same chest when
        // any other chest is available. The destination list is shuffled once so each
        // stack sees chests in a fresh order; without the shuffle, the LinkedHashMap
        // iteration would always pick the same first-registered chest.
        List<BlockPos> destinations = index.allChests().stream()
                .map(c -> c.pos.toBlockPos())
                .filter(p -> !reserved.contains(p))
                .collect(Collectors.toCollection(ArrayList::new));
        Collections.shuffle(destinations);
        Map<BlockPos, Set<String>> holds = new HashMap<>();
        for (BlockPos pos : destinations) {
            holds.put(pos, new HashSet<>());
        }
        // Shuffle the pulled stacks too so adjacent same-item stacks don't all hit the same
        // first-pass candidate in lockstep.
        Collections.shuffle(pulled);
        Map<BlockPos, List<String>> batches = new LinkedHashMap<>();
        int doubled = 0;
        for (StorageIndex.SlotEntry entry : pulled) {
            BlockPos chosen = null;
            for (BlockPos pos : destinations) {
                if (!holds.get(pos).contains(entry.item)) {
                    chosen = pos;
                    break;
                }
            }
            if (chosen == null) {
                // More stacks of this item than chests that don't already hold it - the
                // surplus has to go somewhere, so fall back to any chest with room and
                // surface the count rather than silently doubling up.
                for (BlockPos pos : destinations) {
                    chosen = pos;
                    break;
                }
                if (chosen != null) {
                    doubled++;
                }
            }
            if (chosen == null) {
                continue;
            }
            holds.get(chosen).add(entry.item);
            batches.computeIfAbsent(chosen, key -> new ArrayList<>()).add(entry.item);
        }
        if (doubled > 0) {
            warn("Not enough chests to scatter {} stack(s) - doubled them up on existing chests", doubled);
        }
        BlockPos origin = currentVisit.pos();
        for (BlockPos pos : nearestFirst(new ArrayList<>(batches.keySet()), origin)) {
            plan.add(new Visit(pos, VisitKind.DEPOSIT_BATCH, batches.get(pos), List.of()));
        }
        // Only chase the leftovers when this pass actually shifted something, otherwise a
        // bot that's already full (capacity 0) re-queues SORT_INPUT forever without moving
        // a single stack.
        if (outOfSpace && !pulled.isEmpty()) {
            queue.enqueue(Job.sortInput());
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
        if (currentVisit != null && currentVisit.kind() == VisitKind.CRAFT) {
            craftingInteractor.close();
        } else if (currentVisit != null && currentVisit.kind() == VisitKind.SMELT) {
            furnaceInteractor.close();
        } else {
            interactor.close();
        }
        // For RANDOMIZE, each batch is independent - if the plan is empty but more items still
        // need scattering, build the next batch here rather than finishing the job. Without this
        // the bot would stop after one batch's worth of drops even if half the room's stacks
        // remained unvisited.
        if (currentJob != null && currentJob.type == Job.Type.RANDOMIZE
                && randomizeRemaining != null && !randomizeRemaining.isEmpty()) {
            appendRandomizeBatch();
        }
        nextVisitOrFinish();
    }

    private void nextVisitOrFinish() {
        if (!advanceVisit()) {
            finishJob();
        }
    }

    private void finishJob() {
        currentScanner = null;
        currentJob = null;
        plan = null;
        currentVisit = null;
        shuffle = null;
        randomizeRemaining = null;
        randomizeBatchDestinations = null;
        randomizeLeftovers = null;
        gather = null;
        // Nudge the daemon saver to flush sooner than its next 5s tick rather than blocking the
        // client tick thread on disk I/O here - flush() does sync writes, and finishJob() runs on
        // the tick thread, so calling it would re-introduce exactly the stutter the debounced
        // saver was added to remove. requestFlush() wakes the saver via its signal queue; the
        // wall-clock gap to disk is "a few ms" rather than "up to 5s".
        index.requestFlush();
    }
}
