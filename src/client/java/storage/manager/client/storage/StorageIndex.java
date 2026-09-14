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
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;

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
 *
 * <p>Producers that want the next save to happen sooner than the next scheduled tick (e.g.
 * {@code JobExecutor.finishJob()}) call {@link #requestFlush()} to nudge the saver via the
 * {@link #flushSignal} queue - the saver drains the signal before sleeping, so the wall-clock
 * gap between a job finishing and its result hitting disk is "a few ms" rather than "up to
 * {@code SAVE_INTERVAL_MILLIS}".
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

    // Machine-read only - the loader is the only consumer, no one inspects the file by hand.
    // A vanilla player's index can reach the MB range; pretty-printing adds ~30% whitespace for
    // no benefit there.
    private static final Gson GSON = new GsonBuilder().create();

    private static final long SAVE_INTERVAL_MILLIS = 5000L;

    /**
     * Refuse to deserialize anything larger than this. A real index fits in single-digit MB
     * even for a very large storage room; anything bigger is corrupted-by-bloat or a hand-
     * edited bomb. Quarantined on load rather than fed to Gson, which would OOM before parsing.
     */
    private static final long MAX_INDEX_BYTES = 50L * 1024L * 1024L;

    /**
     * Positional bounds applied at load time. Minecraft's world border is at \u00b129,999,984 from
     * spawn; anything outside is junk and gets skipped during validation so it can't NPE a
     * later web request.
     */
    private static final int POS_MIN = -30_000_000;
    private static final int POS_MAX = 30_000_000;

    /**
     * Format for quarantine filenames. {@code :} in {@link DateTimeFormatter#ISO_INSTANT} is
     * illegal on Windows; the literal {@code '}''} here substitutes a hyphen so the path is
     * portable.
     */
    private static final DateTimeFormatter QUARANTINE_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    private final Path file;

    private Region region = new Region();
    private Pos inputChest;
    private Pos outputChest;
    private final Map<String, ChestEntry> chests = new LinkedHashMap<>();
    private final Object flushLock = new Object();
    private boolean dirty;

    /**
     * One-slot signal the daemon saver waits on. Producers (any thread that wants the next
     * save to happen sooner than the {@link #SAVE_INTERVAL_MILLIS} tick) call
     * {@link #requestFlush()}; the saver drains the signal before sleeping. {@code poll(timeout)}
     * releases the saver thread promptly instead of letting it finish its current sleep first.
     */
    private final LinkedBlockingQueue<Boolean> flushSignal = new LinkedBlockingQueue<>(1);

    public StorageIndex() {
        this(FabricLoader.getInstance().getConfigDir()
                .resolve("storage-manager").resolve("storage-index.json"));
    }

    /**
     * Test-friendly constructor - bypasses {@link FabricLoader} so unit tests in other packages
     * can build an index without a running fabric environment. The index never touches the file
     * path until {@link #load()} or {@link #flush()} runs, so a throwaway path is fine in tests
     * that only exercise the in-memory methods.
     */
    public StorageIndex(Path file) {
        this.file = file;
    }

    /**
     * Reads the index from disk. Three failure modes are handled deliberately rather than left
     * to propagate as uncaught exceptions:
     *
     * <ul>
     *   <li><b>File absent</b> \u2192 no-op, start with empty state.</li>
     *   <li><b>File too large</b> (\u003e {@link #MAX_INDEX_BYTES}) \u2192 quarantined; refusing to
     *       feed Gson a multi-GB blob keeps the JVM from OOMing.</li>
     *   <li><b>Parse / IO failure of any kind</b> \u2192 quarantined; the broken file is renamed
     *       aside so the user can recover from it and so the next successful flush doesn't
     *       silently overwrite the evidence of what went wrong.</li>
     * </ul>
     *
     * <p>{@code JsonSyntaxException} extends {@code RuntimeException}, not {@code IOException},
     * so a bare {@code catch (IOException)} here (the previous version) let malformed JSON escape
     * to {@code onInitializeClient()} and fail the game start. The broader catch covers both.
     *
     * <p>Returns silently with empty state on failure; the caller never sees a thrown exception
     * from this method.
     */
    public synchronized void load() {
        if (!Files.exists(file)) {
            return;
        }
        long size;
        try {
            size = Files.size(file);
        } catch (IOException e) {
            StorageManager.LOGGER.error("Could not stat storage index, starting empty", e);
            return;
        }
        if (size > MAX_INDEX_BYTES) {
            quarantine(file, "too large (" + size + " bytes)");
            return;
        }
        Data data;
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            data = GSON.fromJson(reader, Data.class);
        } catch (Exception e) {
            // Catches IOException + JsonSyntaxException + JsonParseException + anything else
            // Gson can throw. The file gets renamed aside in a separate try block so a failure
            // there (full disk, antivirus lock on Windows) doesn't mask the original parse error.
            StorageManager.LOGGER.error("Failed to parse storage index, quarantining", e);
            quarantine(file, "parse failure: " + e.getClass().getSimpleName());
            return;
        }
        if (data == null) {
            return;
        }
        this.region = data.region != null ? data.region : new Region();
        // inputChest / outputChest: null is the legitimate "not configured" state, leave alone.
        this.inputChest = data.inputChest;
        this.outputChest = data.outputChest;
        this.chests.clear();
        if (data.chests != null) {
            int skipped = 0;
            StringBuilder skippedPositions = new StringBuilder();
            for (Map.Entry<String, ChestEntry> entry : data.chests.entrySet()) {
                ChestEntry chest = entry.getValue();
                if (!isValidChest(chest)) {
                    skipped++;
                    if (skippedPositions.length() < 256) {
                        if (skippedPositions.length() > 0) {
                            skippedPositions.append(", ");
                        }
                        skippedPositions.append(entry.getKey());
                    }
                    continue;
                }
                this.chests.put(entry.getKey(), chest);
            }
            if (skipped > 0) {
                String more = skippedPositions.length() >= 256 ? ", ..." : "";
                StorageManager.LOGGER.warn("Skipped {} invalid chest entries from index: {}{}",
                        skipped, skippedPositions, more);
            }
        }
    }

    /**
     * True when a deserialized {@link ChestEntry} has a non-null, in-bounds position, a
     * non-blank type, a non-null slots list, and every slot is internally consistent.
     * Null {@code pos} is the main realistic failure - a missing JSON field becomes a default-
     * initialized {@code Pos} or a literal null depending on Gson internals, and a literal null
     * then NPEs on every {@code chest.pos.toBlockPos()} later.
     */
    private static boolean isValidChest(ChestEntry chest) {
        if (chest == null) {
            return false;
        }
        if (chest.pos == null) {
            return false;
        }
        if (chest.pos.x < POS_MIN || chest.pos.x > POS_MAX
                || chest.pos.y < POS_MIN || chest.pos.y > POS_MAX
                || chest.pos.z < POS_MIN || chest.pos.z > POS_MAX) {
            return false;
        }
        if (chest.type == null || chest.type.isBlank()) {
            return false;
        }
        if (chest.slots == null) {
            // Gson can overwrite a default-initialized field with null when the JSON has
            // "slots": null - defensive against that.
            return false;
        }
        for (SlotEntry slot : chest.slots) {
            if (slot == null || slot.slot < 0 || slot.slot >= Math.max(chest.size, 1)) {
                return false;
            }
            if (slot.count < 0) {
                return false;
            }
            if (slot.item == null || slot.item.isBlank()) {
                return false;
            }
            // damage / maxDamage must come as a pair - a future migration that flips one to int
            // and forgets the other would silently break the durability bar; reject the half-set
            // case now so the bad shape doesn't ride through load.
            if ((slot.damage == null) != (slot.maxDamage == null)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Moves {@code badFile} aside to a unique {@code .corrupt-<timestamp>} sibling, so the
     * evidence of what failed is preserved and the next {@link #flush()} doesn't silently
     * overwrite it. Errors here are logged but never re-thrown - a rename failure must not
     * mask the parse failure that triggered this call, nor escape to {@code onInitializeClient()}.
     */
    private void quarantine(Path badFile, String reason) {
        try {
            Files.createDirectories(badFile.getParent());
        } catch (IOException e) {
            StorageManager.LOGGER.error("Could not create index directory to quarantine {} ({})",
                    badFile, reason, e);
            return;
        }
        String base = badFile.getFileName().toString();
        Path target = badFile.resolveSibling(base + ".corrupt-"
                + QUARANTINE_TIMESTAMP.format(Instant.now()) + "-" + UUID.randomUUID().toString().substring(0, 8));
        try {
            Files.move(badFile, target);
            StorageManager.LOGGER.error("Quarantined unreadable index to {} ({}). The file " +
                    "is preserved for recovery; rename it back to {} once it's repaired.",
                    target, reason, badFile.getFileName());
        } catch (IOException e) {
            StorageManager.LOGGER.error("Could not quarantine unreadable index {} ({}) - " +
                    "file left in place; next flush will overwrite it", badFile, reason, e);
        }
    }

    /** Starts the background flusher. Daemon, so it never holds the game open on exit. */
    public void startAutoSave() {
        Thread saver = new Thread(() -> {
            while (true) {
                try {
                    Boolean ignored2 = flushSignal.poll(SAVE_INTERVAL_MILLIS,
                            java.util.concurrent.TimeUnit.MILLISECONDS);
                    if (ignored2 == null) {
                        // Timeout: regular tick. Flush anyway in case the index went dirty
                        // without an explicit request (none today, but keeps the invariant simple).
                        flush();
                        continue;
                    }
                    // Drain any extra signals that piled up before we woke up - a job finishing
                    // during a slow flush can stack signals, but the queue is size-1 so only the
                    // first matters.
                    flushSignal.clear();
                    flush();
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "storage-manager-index-saver");
        saver.setDaemon(true);
        saver.start();
    }

    /**
     * Asks the daemon saver to wake up and flush sooner than its next scheduled tick. Safe to
     * call from any thread; the worst case is the saver is already awake, in which case the
     * signal queue is already non-empty and the {@code offer} is a no-op.
     */
    public void requestFlush() {
        flushSignal.offer(Boolean.TRUE);
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
                synchronized (this) {
                    dirty = true; // try again on the next flush rather than dropping the changes
                }
            }
        }
    }

    /**
     * Writes via a uniquely-named temp file then a rename. On filesystems that support atomic
     * rename ({@code ATOMIC_MOVE}) a crash mid-write leaves the old {@code file} intact and a
     * {@code .tmp-<uuid>} sibling behind - load()'s leftover-tmp recovery uses that sibling.
     *
     * <p>On filesystems that don't support atomic rename (FAT, some network mounts), the rename
     * is replaced by a copy + delete so a crash never leaves both the temp and the destination
     * half-written: copy either succeeds (target is whole, leftover tmp is just garbage to clean
     * up next load) or doesn't run at all. The destination is always overwritten atomically with
     * respect to readers that open it before the copy starts; readers that open it mid-copy
     * see a truncated file, which is exactly the case {@link #load()} handles by quarantining.
     */
    private void writeAtomically(String json) throws IOException {
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temp = file.resolveSibling(file.getFileName() + ".tmp-" + UUID.randomUUID());
        Files.writeString(temp, json, StandardCharsets.UTF_8);
        try {
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            // Copy replaces the destination, then the source is removed. If the JVM dies between
            // copy and delete, the leftover .tmp is detected on the next load and recovered.
            Files.copy(temp, file, StandardCopyOption.REPLACE_EXISTING);
            Files.delete(temp);
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

    /** Removes the cached chest/content records but deliberately retains the user's setup coordinates. */
    public synchronized void clearChests() {
        if (!chests.isEmpty()) {
            chests.clear();
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

    /**
     * Visible to tests: the directory this index reads/writes, so tests can scan for leftover
     * {@code .tmp-*} siblings and quarantined {@code .corrupt-*} files.
     */
    Path getFile() {
        return file;
    }

    /**
     * Visible to tests: the signal queue, so tests can confirm {@link #requestFlush()} wakes
     * the saver.
     */
    LinkedBlockingQueue<Boolean> getFlushSignal() {
        return flushSignal;
    }

    /**
     * Visible to tests: scans the index's directory for leftover {@code .tmp-*} files left by
     * a crashed {@link #writeAtomically()} and quarantines them. Public so a test, or a future
     * "clean up on startup" caller, can drive it directly.
     *
     * <p>Returns the number of files quarantined.
     */
    public int recoverLeftoverTempFiles() throws IOException {
        Path parent = file.getParent();
        if (parent == null) {
            return 0;
        }
        int recovered = 0;
        List<Path> toQuarantine = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(parent, path -> {
            String name = path.getFileName().toString();
            return name.startsWith(file.getFileName().toString() + ".tmp-");
        })) {
            for (Path entry : stream) {
                toQuarantine.add(entry);
            }
        }
        for (Path tmp : toQuarantine) {
            quarantine(tmp, "leftover temp from interrupted write");
            recovered++;
        }
        return recovered;
    }
}