package storage.manager.client.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;

import storage.manager.StorageManager;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Live index of every known chest, its contents and where deposits/withdrawals
 * should stage. Backed by a JSON file so state survives restarts. Every method
 * is synchronized because it's read from HTTP handler threads and written from
 * the client tick thread.
 *
 * <p>Writes are debounced: mutators only flag the index dirty and a background thread
 * flushes at most once every {@link #SAVE_INTERVAL_MILLIS}. Saving inline on every change
 * meant a region scan serialized the whole (growing) index once per chest found and again
 * per chest visited - quadratic work, all of it stalling the client tick thread.
 */
public class StorageIndex {

    public static class Pos {
        public int x, y, z;

        public Pos() {
        }

        public Pos(BlockPos pos) {
            this.x = pos.getX();
            this.y = pos.getY();
            this.z = pos.getZ();
        }

        public BlockPos toBlockPos() {
            return new BlockPos(x, y, z);
        }

        public String key() {
            return x + "," + y + "," + z;
        }
    }

    public static class Region {
        public Pos min;
        public Pos max;

        public boolean contains(BlockPos pos) {
            if (min == null || max == null) {
                return false;
            }
            return pos.getX() >= Math.min(min.x, max.x) && pos.getX() <= Math.max(min.x, max.x)
                    && pos.getY() >= Math.min(min.y, max.y) && pos.getY() <= Math.max(min.y, max.y)
                    && pos.getZ() >= Math.min(min.z, max.z) && pos.getZ() <= Math.max(min.z, max.z);
        }
    }

    public static class SlotEntry {
        public int slot;
        public String item;
        public int count;
        /** e.g. {@code ["sharpness 5", "unbreaking 3"]}. Null (and so omitted from JSON) when none. */
        public List<String> enchants;
        /** Set only for renamed stacks. */
        public String customName;
        /** Set only for damaged tools, so the UI can draw a durability bar. */
        public Integer damage;
        public Integer maxDamage;

        public SlotEntry(int slot, String item, int count) {
            this.slot = slot;
            this.item = item;
            this.count = count;
        }
    }

    public static class ChestEntry {
        public Pos pos;
        public String type;
        /**
         * Container slot count, so the UI can draw a double chest as 54 slots rather than
         * guessing from the highest occupied index. 0 means "not opened yet" - either a chest
         * discovered by a region scan but never visited, or an entry from an older index file.
         */
        public int size;
        public long lastScanned;
        public List<SlotEntry> slots = new ArrayList<>();
    }

    /** Where a WITHDRAW job should pull `count` items of `item` from one chest. */
    public record Contribution(BlockPos chestPos, int slot, int count) {
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static final long SAVE_INTERVAL_MILLIS = 5000L;

    private final Path file;

    private Region region = new Region();
    private Pos inputChest;
    private Pos outputChest;
    private final Map<String, ChestEntry> chests = new LinkedHashMap<>();
    private final Object flushLock = new Object();
    private boolean dirty;

    public StorageIndex() {
        this.file = FabricLoader.getInstance().getConfigDir()
                .resolve("storage-manager").resolve("storage-index.json");
    }

    public synchronized void load() {
        if (!Files.exists(file)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Data data = GSON.fromJson(reader, Data.class);
            if (data != null) {
                this.region = data.region != null ? data.region : new Region();
                this.inputChest = data.inputChest;
                this.outputChest = data.outputChest;
                this.chests.clear();
                if (data.chests != null) {
                    this.chests.putAll(data.chests);
                }
            }
        } catch (IOException e) {
            StorageManager.LOGGER.error("Failed to load storage index", e);
        }
    }

    /** Starts the background flusher. Daemon, so it never holds the game open on exit. */
    public void startAutoSave() {
        Thread saver = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(SAVE_INTERVAL_MILLIS);
                } catch (InterruptedException e) {
                    return;
                }
                flush();
            }
        }, "storage-manager-index-saver");
        saver.setDaemon(true);
        saver.start();
    }

    private synchronized void markDirty() {
        dirty = true;
    }

    /**
     * Writes the index out if anything changed since the last flush. Serialization happens under
     * the state lock (it needs a consistent view) but the file write doesn't, so a slow disk can't
     * stall the tick thread behind it.
     *
     * <p>{@code flushLock} serializes whole flushes against each other - the background saver, a
     * finishing job and the shutdown hook can all land here at once, and two overlapping writes
     * could otherwise leave the older snapshot on disk.
     */
    public void flush() {
        synchronized (flushLock) {
            String json;
            synchronized (this) {
                if (!dirty) {
                    return;
                }
                dirty = false;
                Data data = new Data();
                data.region = region;
                data.inputChest = inputChest;
                data.outputChest = outputChest;
                data.chests = chests;
                json = GSON.toJson(data);
            }
            try {
                writeAtomically(json);
            } catch (IOException e) {
                StorageManager.LOGGER.error("Failed to save storage index", e);
                markDirty(); // try again on the next flush rather than dropping the changes
            }
        }
    }

    /**
     * Writes via a temp file and a rename. Now that saves are debounced each one carries up to
     * {@link #SAVE_INTERVAL_MILLIS} of work, so a crash mid-write shouldn't be able to leave a
     * half-written index behind - the old file stays intact until the new one is complete.
     */
    private void writeAtomically(String json) throws IOException {
        Files.createDirectories(file.getParent());
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temp, json, StandardCharsets.UTF_8);
        try {
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static class Data {
        Region region;
        Pos inputChest;
        Pos outputChest;
        Map<String, ChestEntry> chests;
    }

    public synchronized void setRegion(BlockPos min, BlockPos max) {
        Region r = new Region();
        r.min = new Pos(min);
        r.max = new Pos(max);
        this.region = r;
        markDirty();
    }

    public synchronized void setInputChest(BlockPos pos) {
        this.inputChest = new Pos(pos);
        markDirty();
    }

    public synchronized void setOutputChest(BlockPos pos) {
        this.outputChest = new Pos(pos);
        markDirty();
    }

    public synchronized BlockPos getInputChest() {
        return inputChest != null ? inputChest.toBlockPos() : null;
    }

    public synchronized BlockPos getOutputChest() {
        return outputChest != null ? outputChest.toBlockPos() : null;
    }

    /** Raw x/y/z form (as opposed to {@link #getInputChest()}) for reporting back to the web UI. */
    public synchronized Pos getInputChestPos() {
        return inputChest;
    }

    public synchronized Pos getOutputChestPos() {
        return outputChest;
    }

    public synchronized Region getRegion() {
        return region;
    }

    public synchronized void upsertChest(BlockPos pos, String type, int size, List<SlotEntry> slots, long now) {
        ChestEntry entry = new ChestEntry();
        entry.pos = new Pos(pos);
        entry.type = type;
        entry.size = size;
        entry.lastScanned = now;
        entry.slots = slots;
        chests.put(entry.pos.key(), entry);
        markDirty();
    }

    /** Drops a stale entry, e.g. the redundant RIGHT half of a double chest from before dedup existed. */
    public synchronized void removeChest(BlockPos pos) {
        if (chests.remove(new Pos(pos).key()) != null) {
            markDirty();
        }
    }

    public synchronized void registerEmptyChest(BlockPos pos, String type) {
        String key = new Pos(pos).key();
        if (!chests.containsKey(key)) {
            upsertChest(pos, type, 0, new ArrayList<>(), 0L);
        }
    }

    public synchronized Collection<ChestEntry> allChests() {
        return new ArrayList<>(chests.values());
    }

    /**
     * Aggregate counts of every item held in storage, for the web UI.
     *
     * <p>{@code exclude} keeps the input/output chests out of the sum. They get indexed like any
     * other chest the bot opens, so without this a withdrawal looks like it did nothing: the items
     * leave a storage chest, land in the output chest, and the reported total never moves.
     */
    public synchronized Map<String, Integer> totalCounts(Collection<BlockPos> exclude) {
        Map<String, Integer> totals = new LinkedHashMap<>();
        for (ChestEntry chest : chests.values()) {
            if (exclude.contains(chest.pos.toBlockPos())) {
                continue;
            }
            for (SlotEntry slot : chest.slots) {
                totals.merge(slot.item, slot.count, Integer::sum);
            }
        }
        return totals;
    }

    /**
     * Greedily picks whole slots to satisfy a withdrawal request. Each contribution is a full
     * stack (withdrawal moves whole stacks via shift-click, it can't split one) - the last slot
     * may overshoot the requested count rather than under-deliver.
     *
     * <p>{@code exclude} keeps the input/output chests out of the search. Without it a withdrawal
     * could source items straight out of the output chest and then deliver them right back to it.
     */
    public synchronized List<Contribution> findItem(String itemId, int count, Collection<BlockPos> exclude) {
        List<Contribution> contributions = new ArrayList<>();
        int remaining = count;
        for (ChestEntry chest : chests.values()) {
            if (remaining <= 0) {
                break;
            }
            BlockPos chestPos = chest.pos.toBlockPos();
            if (exclude.contains(chestPos)) {
                continue;
            }
            for (SlotEntry slot : chest.slots) {
                if (remaining <= 0) {
                    break;
                }
                if (itemId.equals(slot.item) && slot.count > 0) {
                    contributions.add(new Contribution(chestPos, slot.slot, slot.count));
                    remaining -= slot.count;
                }
            }
        }
        return contributions;
    }

    /**
     * Chests already holding this item (consolidation candidates), then any other known chest -
     * randomized within each group so repeated deposits don't all pile onto the same one chest
     * (which also means a full chest naturally gets skipped over on the next attempt instead of
     * being tried forever).
     *
     * <p>{@code exclude} keeps the input/output chests out of the results. Without it a sort pass
     * could deposit straight back into the input chest and then pick the same items up again on
     * the next pass.
     */
    public synchronized List<BlockPos> depositCandidates(String itemId, Collection<BlockPos> exclude) {
        List<BlockPos> withItem = new ArrayList<>();
        List<BlockPos> others = new ArrayList<>();
        for (ChestEntry chest : chests.values()) {
            BlockPos chestPos = chest.pos.toBlockPos();
            if (exclude.contains(chestPos)) {
                continue;
            }
            boolean hasItem = chest.slots.stream().anyMatch(s -> itemId.equals(s.item));
            (hasItem ? withItem : others).add(chestPos);
        }
        Collections.shuffle(withItem);
        Collections.shuffle(others);
        withItem.addAll(others);
        return withItem;
    }

    /**
     * Every known chest in random order, ignoring which items each already holds - the
     * deliberate opposite of {@link #depositCandidates(String, Collection)}, used by the RANDOMIZE job.
     *
     * <p>Callers pass the positions to leave out - the input/output chests, and for a double
     * chest <em>both</em> of its halves. Matching those up needs to read block states to find
     * which half is which, so it can't happen here: this class is touched by HTTP handler
     * threads, and world access is client-tick-thread only.
     */
    public synchronized List<BlockPos> randomStorageChests(Collection<BlockPos> exclude) {
        List<BlockPos> result = new ArrayList<>();
        for (ChestEntry chest : chests.values()) {
            BlockPos pos = chest.pos.toBlockPos();
            if (!exclude.contains(pos)) {
                result.add(pos);
            }
        }
        Collections.shuffle(result);
        return result;
    }
}
