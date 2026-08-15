package storage.manager.client.job;

import net.minecraft.core.BlockPos;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import storage.manager.client.storage.StorageIndex;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tier 2 tests for {@link RegionScanner}. Uses a {@link FakeLookup} so the scan runs without
 * Minecraft - the seam is the {@link BlockStateLookup} interface returning {@link ScanKind}.
 * {@link StorageIndex} itself is exercised live via its public {@link StorageIndex#StorageIndex(Path)}
 * test constructor, which bypasses the {@code FabricLoader} call its main constructor makes.
 */
class RegionScannerTest {

    private StorageIndex index;
    private FakeLookup lookup;

    @BeforeEach
    void setUp() {
        index = new StorageIndex(Path.of("build", "test-storage-index.json"));
        lookup = new FakeLookup();
    }

    @Test
    void singleBlockRegionFinishesInOneChunkAndRegistersChest() {
        BlockPos pos = new BlockPos(5, 6, 7);
        lookup.put(pos, ScanKind.CHEST_SINGLE_OR_LEFT);
        RegionScanner scanner = new RegionScanner(pos, pos, index, lookup);
        assertFalse(scanner.isDone());
        scanner.scanChunk();
        assertTrue(scanner.isDone());
        assertEquals(1, index.allChests().size());
        assertEquals(pos, index.allChests().iterator().next().pos.toBlockPos());
    }

    @Test
    void nonContainerBlocksAreIgnored() {
        BlockPos pos = new BlockPos(0, 0, 0);
        lookup.put(pos, ScanKind.NONE);
        RegionScanner scanner = new RegionScanner(pos, pos, index, lookup);
        scanner.scanChunk();
        assertTrue(scanner.isDone());
        assertTrue(index.allChests().isEmpty());
    }

    @Test
    void rightHalfOfDoubleChestIsRemovedLeftHalfRegistered() {
        BlockPos left = new BlockPos(0, 0, 0);
        BlockPos right = new BlockPos(1, 0, 0);
        lookup.put(left, ScanKind.CHEST_SINGLE_OR_LEFT);
        lookup.put(right, ScanKind.CHEST_RIGHT);
        // Pre-register the right half to verify the scanner removes it - the test for the
        // dedup logic, not the existence check.
        index.registerEmptyChest(right, "minecraft:chest");
        RegionScanner scanner = new RegionScanner(
                new BlockPos(0, 0, 0), new BlockPos(1, 0, 0), index, lookup);
        scanner.scanChunk();
        assertTrue(scanner.isDone());
        assertEquals(1, index.allChests().size());
        assertEquals(left, index.allChests().iterator().next().pos.toBlockPos());
    }

    @Test
    void barrelIsRegistered() {
        BlockPos pos = new BlockPos(0, 0, 0);
        lookup.put(pos, ScanKind.BARREL);
        RegionScanner scanner = new RegionScanner(pos, pos, index, lookup);
        scanner.scanChunk();
        assertTrue(scanner.isDone());
        assertEquals(1, index.allChests().size());
    }

    @Test
    void shulkerBoxIsRegistered() {
        BlockPos pos = new BlockPos(0, 0, 0);
        lookup.put(pos, ScanKind.SHULKER_BOX);
        RegionScanner scanner = new RegionScanner(pos, pos, index, lookup);
        scanner.scanChunk();
        assertTrue(scanner.isDone());
        assertEquals(1, index.allChests().size());
    }

    @Test
    void invertedCornersWalkTheSameVolume() {
        // User picks corners in arbitrary order via the setup form; the scanner normalizes them
        // so (a, b) and (b, a) walk the same volume.
        BlockPos a = new BlockPos(5, 6, 7);
        BlockPos b = new BlockPos(0, 0, 0);
        lookup.put(a, ScanKind.CHEST_SINGLE_OR_LEFT);
        lookup.put(b, ScanKind.BARREL);
        RegionScanner scanner = new RegionScanner(a, b, index, lookup);
        scanner.scanChunk();
        assertTrue(scanner.isDone());
        assertEquals(2, index.allChests().size());
    }

    @Test
    void smallRegionFitsInOneScanChunk() {
        // 3x1x1 = 3 blocks. Well under the per-tick budget.
        lookup.put(new BlockPos(0, 0, 0), ScanKind.NONE);
        lookup.put(new BlockPos(1, 0, 0), ScanKind.CHEST_SINGLE_OR_LEFT);
        lookup.put(new BlockPos(2, 0, 0), ScanKind.NONE);
        RegionScanner scanner = new RegionScanner(
                new BlockPos(0, 0, 0), new BlockPos(2, 0, 0), index, lookup);
        assertFalse(scanner.isDone());
        scanner.scanChunk();
        assertTrue(scanner.isDone());
        assertEquals(1, index.allChests().size());
    }

    @Test
    void callsAfterCompletionAreNoop() {
        RegionScanner scanner = new RegionScanner(
                new BlockPos(0, 0, 0), new BlockPos(0, 0, 0), index, lookup);
        scanner.scanChunk();
        assertTrue(scanner.isDone());
        // Subsequent calls must not throw and must not re-register anything.
        scanner.scanChunk();
        scanner.scanChunk();
        assertTrue(scanner.isDone());
        assertTrue(index.allChests().isEmpty());
    }

    @Test
    void everyBlockInLargerRegionIsVisited() {
        // 16x1x16 = 256 blocks. Under budget, so finishes in one chunk - and lets us verify
        // every position was visited (not just the per-tick cap).
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                lookup.put(new BlockPos(x, 0, z), ScanKind.CHEST_SINGLE_OR_LEFT);
            }
        }
        RegionScanner scanner = new RegionScanner(
                new BlockPos(0, 0, 0), new BlockPos(15, 0, 15), index, lookup);
        scanner.scanChunk();
        assertTrue(scanner.isDone());
        assertEquals(256, index.allChests().size());
    }

    private static final class FakeLookup implements BlockStateLookup {
        /** Test type-id for a chest - matches what {@code MinecraftBlockStateLookup} would emit. */
        private static final String CHEST_TYPE_ID = "minecraft:chest";
        private static final String BARREL_TYPE_ID = "minecraft:barrel";
        private static final String SHULKER_TYPE_ID = "minecraft:white_shulker_box";

        private final Map<BlockPos, ScanResult> states = new HashMap<>();

        void put(BlockPos pos, ScanKind kind) {
            states.put(pos.immutable(), new ScanResult(kind, typeIdFor(kind)));
        }

        private static String typeIdFor(ScanKind kind) {
            return switch (kind) {
                case CHEST_SINGLE_OR_LEFT, CHEST_RIGHT -> CHEST_TYPE_ID;
                case BARREL -> BARREL_TYPE_ID;
                case SHULKER_BOX -> SHULKER_TYPE_ID;
                case NONE -> null;
            };
        }

        @Override
        public ScanResult scanAt(BlockPos pos) {
            return states.getOrDefault(pos.immutable(), ScanResult.EMPTY);
        }
    }
}
