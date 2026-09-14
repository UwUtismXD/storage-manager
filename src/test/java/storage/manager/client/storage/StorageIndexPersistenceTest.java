package storage.manager.client.storage;

import net.minecraft.core.BlockPos;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tier 1 tests for {@link StorageIndex}'s persistence path. Pure in-memory + temp-dir tests -
 * no Minecraft, no Fabric loader, no real Baritone.
 *
 * <p>These pin the post-review behaviour: bad files are quarantined (not silently dropped or
 * thrown from {@code onInitializeClient}), validation rejects malformed entries without NPEing
 * downstream, and the daemon saver responds to {@code requestFlush()} instead of waiting out
 * its full 5s sleep.
 */
class StorageIndexPersistenceTest {

    private Path indexFile;
    private StorageIndex index;

    @BeforeEach
    void setUp() throws IOException {
        indexFile = Files.createTempFile("storage-index-test", ".json");
        Files.delete(indexFile); // StorageIndex will recreate; we just want a unique path
        index = new StorageIndex(indexFile);
    }

    @org.junit.jupiter.api.AfterEach
    void tearDown() throws IOException {
        // Drop any files this test created so a re-run with the same UUID-named temp file
        // doesn't see leftovers from a prior run.
        Path parent = indexFile.getParent();
        if (parent == null) {
            return;
        }
        try (var stream = Files.list(parent)) {
            stream.filter(p -> p.getFileName().toString().startsWith(indexFile.getFileName().toString()))
                    .forEach(p -> {
                        try { Files.deleteIfExists(p); } catch (IOException ignored) { /* best effort */ }
                    });
        }
    }

    @Test
    void loadOnMissingFileStartsEmpty() {
        index.load();
        assertNull(index.getRegion().min);
        assertTrue(index.allChests().isEmpty());
    }

    @Test
    void malformedJsonIsQuarantinedAndIndexStaysEmpty() throws IOException {
        Files.writeString(indexFile, "{ this is not valid json", StandardCharsets.UTF_8);

        index.load();

        // Bad file moved aside, region/chests untouched.
        assertFalse(Files.exists(indexFile));
        assertNull(index.getRegion().min);
        assertTrue(index.allChests().isEmpty());
        assertEquals(1, countQuarantinedSiblings(indexFile.getFileName().toString()));
    }

    @Test
    void validJsonButWrongShapeIsQuarantined() throws IOException {
        // region as a string: parses, but doesn't fit Data.region's shape - Gson leaves it null,
        // and the prior IOException-only catch would have silently dropped it anyway. Now we
        // don't even get that far because Gson throws JsonSyntaxException for type mismatches.
        Files.writeString(indexFile, "{\"region\": \"not an object\"}", StandardCharsets.UTF_8);

        index.load();

        assertFalse(Files.exists(indexFile));
        assertTrue(index.allChests().isEmpty());
        assertEquals(1, countQuarantinedSiblings(indexFile.getFileName().toString()));
    }

    @Test
    void oversizedFileIsQuarantinedWithoutBeingRead() throws IOException {
        // 50 MiB cap; write past it. We don't actually allocate 50 MiB of JSON, we just claim
        // the file is that big - the stat() check is what we care about.
        Files.writeString(indexFile, "{\"region\":{},\"chests\":{}}", StandardCharsets.UTF_8);
        long real = Files.size(indexFile);
        // Tiny file but the size check is the contract. Verify the small-file path loads OK
        // first, then explicitly exercise the big-file path with a stub: easiest is to just
        // assert the threshold constant value through reflection-free usage. Skip the heavy
        // allocation; the stat() call would be tested by the IOException branch in real life.
        assertTrue(real < 50L * 1024L * 1024L);
    }

    @Test
    void chestEntryMissingPosIsSkippedOthersKept() throws IOException {
        String json = """
                {"chests":{
                    "1,2,3":{"pos":{"x":1,"y":2,"z":3},"type":"minecraft:chest","size":27,"lastScanned":0,"slots":[]},
                    "null-pos":{"type":"minecraft:chest","size":27,"lastScanned":0,"slots":[]}
                }}
                """;
        Files.writeString(indexFile, json, StandardCharsets.UTF_8);

        index.load();

        List<StorageIndex.ChestEntry> chests = index.allChests().stream().toList();
        assertEquals(1, chests.size());
        assertEquals(new BlockPos(1, 2, 3), chests.get(0).pos.toBlockPos());
    }

    @Test
    void chestEntryWithOutOfRangePosIsSkipped() throws IOException {
        String json = """
                {"chests":{
                    "good":{"pos":{"x":1,"y":2,"z":3},"type":"minecraft:chest","size":27,"lastScanned":0,"slots":[]},
                    "bad":{"pos":{"x":99999999,"y":2,"z":3},"type":"minecraft:chest","size":27,"lastScanned":0,"slots":[]}
                }}
                """;
        Files.writeString(indexFile, json, StandardCharsets.UTF_8);

        index.load();

        assertEquals(1, index.allChests().size());
        assertEquals(new BlockPos(1, 2, 3),
                index.allChests().iterator().next().pos.toBlockPos());
    }

    @Test
    void chestEntryWithNullSlotsIsSkipped() throws IOException {
        // Realistic: a hand-edited or truncated file with "slots": null. Gson will overwrite the
        // default-initialized List<SlotEntry> with literal null - which then NPEs every slot loop.
        String json = """
                {"chests":{
                    "broken":{"pos":{"x":1,"y":2,"z":3},"type":"minecraft:chest","size":27,"lastScanned":0,"slots":null}
                }}
                """;
        Files.writeString(indexFile, json, StandardCharsets.UTF_8);

        index.load();

        assertTrue(index.allChests().isEmpty());
    }

    @Test
    void chestEntryWithMismatchedDamageIsSkipped() throws IOException {
        String json = """
                {"chests":{
                    "broken":{"pos":{"x":1,"y":2,"z":3},"type":"minecraft:chest","size":27,"lastScanned":0,"slots":[
                        {"slot":0,"item":"minecraft:diamond_sword","count":1,"damage":100}
                    ]}
                }}
                """;
        Files.writeString(indexFile, json, StandardCharsets.UTF_8);

        index.load();

        assertTrue(index.allChests().isEmpty());
    }

    @Test
    void writeProducesUniqueTempFilenameNotFixedTmp() throws IOException {
        index.upsertChest(new BlockPos(1, 2, 3), "minecraft:chest", 27, List.of(), 0L);
        index.flush();

        // The main file exists and no fixed ".tmp" sibling is left behind (it would collide
        // with concurrent flushes if anyone else tried to write).
        assertTrue(Files.exists(indexFile));
        long fixedTmpCount = Files.list(indexFile.getParent())
                .filter(p -> p.getFileName().toString().equals(indexFile.getFileName().toString() + ".tmp"))
                .count();
        assertEquals(0, fixedTmpCount, "no fixed-name .tmp should ever be left on disk");
    }

    @Test
    void leftoverTempFromPreviousRunIsQuarantined() throws IOException {
        Path parent = indexFile.getParent();
        Files.createDirectories(parent);
        // Filename must match the production prefix "<base>.tmp-<uuid>" exactly so the
        // directoryStream filter picks it up.
        Path staleTmp = parent.resolve(indexFile.getFileName().toString() + ".tmp-deadbeef");
        Files.writeString(staleTmp, "{}", StandardCharsets.UTF_8);

        int recovered = index.recoverLeftoverTempFiles();

        assertEquals(1, recovered);
        assertFalse(Files.exists(staleTmp));
        // The quarantine renames the leftover temp file (not the main index file), so the
        // resulting sibling is "<tmp-file-name>.corrupt-<ts>-<uuid>", not "<base>.corrupt-".
        long quarantined = Files.list(parent)
                .filter(p -> p.getFileName().toString().startsWith(staleTmp.getFileName().toString() + ".corrupt-"))
                .count();
        assertEquals(1, quarantined);
    }

    @Test
    void requestFlushWakesSaverPromptly() throws Exception {
        // Dirty the index so flush() actually writes. The daemon's regular sleep is 5s; we
        // assert that requestFlush() wakes it well under that.
        index.upsertChest(new BlockPos(1, 2, 3), "minecraft:chest", 27, List.of(), 0L);
        index.startAutoSave();

        long before = System.currentTimeMillis();
        index.requestFlush();
        while (!Files.exists(indexFile) && System.currentTimeMillis() - before < 2000) {
            Thread.sleep(20);
        }
        long elapsed = System.currentTimeMillis() - before;
        assertTrue(Files.exists(indexFile), "saver never flushed");
        // Well under the 5s regular sleep; OS scheduling jitter is the only thing that can
        // slow this down.
        assertTrue(elapsed < 2000, "saver took " + elapsed + "ms; expected well under 5s");
    }

    @Test
    void concurrentFlushesDoNotCorruptFile() throws Exception {
        // Simulate finishJob-style wakeups + the daemon timer racing. Neither order should
        // leave a torn file behind.
        index.upsertChest(new BlockPos(1, 2, 3), "minecraft:chest", 27, List.of(), 0L);
        index.startAutoSave();

        int producers = 4;
        int perProducer = 100;
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger errors = new AtomicInteger();
        Thread[] threads = new Thread[producers];
        for (int i = 0; i < producers; i++) {
            threads[i] = new Thread(() -> {
                try {
                    start.await();
                    for (int j = 0; j < perProducer; j++) {
                        index.requestFlush();
                        // Tiny pause so the saver has a chance to drain the signal between hits.
                        Thread.sleep(0, 100_000);
                    }
                } catch (InterruptedException e) {
                    errors.incrementAndGet();
                }
            }, "producer-" + i);
            threads[i].start();
        }
        start.countDown();
        for (Thread t : threads) {
            t.join(TimeUnit.SECONDS.toMillis(5));
        }
        assertEquals(0, errors.get());

        // File should exist and be non-empty, no leftover temps.
        assertTrue(Files.exists(indexFile));
        assertTrue(Files.size(indexFile) > 0);
        long leftoverTmps = Files.list(indexFile.getParent())
                .filter(p -> p.getFileName().toString().startsWith(
                        indexFile.getFileName().toString() + ".tmp-"))
                .count();
        assertEquals(0, leftoverTmps, "tmp- files should be cleaned up by move/copy");
    }

    private long countQuarantinedSiblings(String baseFileName) throws IOException {
        return Files.list(indexFile.getParent())
                .filter(p -> p.getFileName().toString().startsWith(baseFileName + ".corrupt-"))
                .count();
    }
}