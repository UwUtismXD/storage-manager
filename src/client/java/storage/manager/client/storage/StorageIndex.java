package storage.manager.client.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;

import storage.manager.StorageManager;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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

        public SlotEntry(int slot, String item, int count) {
            this.slot = slot;
            this.item = item;
            this.count = count;
        }
    }

    public static class ChestEntry {
        public Pos pos;
        public String type;
        public long lastScanned;
        public List<SlotEntry> slots = new ArrayList<>();
    }

    /** Where a WITHDRAW job should pull `count` items of `item` from one chest. */
    public record Contribution(BlockPos chestPos, int slot, int count) {
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path file;

    private Region region = new Region();
    private Pos inputChest;
    private Pos outputChest;
    private final Map<String, ChestEntry> chests = new LinkedHashMap<>();

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

    public synchronized void save() {
        try {
            Files.createDirectories(file.getParent());
            Data data = new Data();
            data.region = region;
            data.inputChest = inputChest;
            data.outputChest = outputChest;
            data.chests = chests;
            try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                GSON.toJson(data, writer);
            }
        } catch (IOException e) {
            StorageManager.LOGGER.error("Failed to save storage index", e);
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
        save();
    }

    public synchronized void setInputChest(BlockPos pos) {
        this.inputChest = new Pos(pos);
        save();
    }

    public synchronized void setOutputChest(BlockPos pos) {
        this.outputChest = new Pos(pos);
        save();
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

    public synchronized void upsertChest(BlockPos pos, String type, List<SlotEntry> slots, long now) {
        ChestEntry entry = new ChestEntry();
        entry.pos = new Pos(pos);
        entry.type = type;
        entry.lastScanned = now;
        entry.slots = slots;
        chests.put(entry.pos.key(), entry);
        save();
    }

    /** Drops a stale entry, e.g. the redundant RIGHT half of a double chest from before dedup existed. */
    public synchronized void removeChest(BlockPos pos) {
        if (chests.remove(new Pos(pos).key()) != null) {
            save();
        }
    }

    public synchronized void registerEmptyChest(BlockPos pos, String type) {
        String key = new Pos(pos).key();
        if (!chests.containsKey(key)) {
            upsertChest(pos, type, new ArrayList<>(), 0L);
        }
    }

    public synchronized Collection<ChestEntry> allChests() {
        return new ArrayList<>(chests.values());
    }

    /** Aggregate counts of every item across all known chests, for the web UI. */
    public synchronized Map<String, Integer> totalCounts() {
        Map<String, Integer> totals = new LinkedHashMap<>();
        for (ChestEntry chest : chests.values()) {
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
     */
    public synchronized List<Contribution> findItem(String itemId, int count) {
        List<Contribution> contributions = new ArrayList<>();
        int remaining = count;
        for (ChestEntry chest : chests.values()) {
            if (remaining <= 0) {
                break;
            }
            for (SlotEntry slot : chest.slots) {
                if (remaining <= 0) {
                    break;
                }
                if (itemId.equals(slot.item) && slot.count > 0) {
                    contributions.add(new Contribution(chest.pos.toBlockPos(), slot.slot, slot.count));
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
     */
    public synchronized List<BlockPos> depositCandidates(String itemId) {
        List<BlockPos> withItem = new ArrayList<>();
        List<BlockPos> others = new ArrayList<>();
        for (ChestEntry chest : chests.values()) {
            boolean hasItem = chest.slots.stream().anyMatch(s -> itemId.equals(s.item));
            (hasItem ? withItem : others).add(chest.pos.toBlockPos());
        }
        Collections.shuffle(withItem);
        Collections.shuffle(others);
        withItem.addAll(others);
        return withItem;
    }
}
