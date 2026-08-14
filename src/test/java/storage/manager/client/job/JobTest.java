package storage.manager.client.job;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tier 1 tests for {@link Job} factories and {@code toString} formatting. The toString output
 * feeds the status panel that drives the web UI progress readout, so a regression here silently
 * breaks what the player sees without breaking any actual logic.
 */
class JobTest {

    @Test
    void scanRegionDefaultZeroAgeRevisitsEverything() {
        Job j = Job.scanRegion();
        assertEquals(Job.Type.SCAN_REGION, j.type);
        assertEquals(0L, j.maxAgeMillis);
        assertNull(j.itemId);
        assertNull(j.sourceChest);
    }

    @Test
    void scanRegionWithAgeRestrictsToStale() {
        Job j = Job.scanRegion(60_000L);
        assertEquals(Job.Type.SCAN_REGION, j.type);
        assertEquals(60_000L, j.maxAgeMillis);
    }

    @Test
    void sortInputAndSortInputRandomAreDistinctTypes() {
        assertEquals(Job.Type.SORT_INPUT, Job.sortInput().type);
        assertEquals(Job.Type.SORT_INPUT_RANDOM, Job.sortInputRandom().type);
    }

    @Test
    void randomizeAndExperimentalRandomizeAreDistinctTypes() {
        assertEquals(Job.Type.RANDOMIZE, Job.randomize().type);
        assertEquals(Job.Type.EXPERIMENTAL_RANDOMIZE, Job.experimentalRandomize().type);
    }

    @Test
    void withdrawCarriesItemAndCount() {
        Job j = Job.withdraw("minecraft:diamond", 64);
        assertEquals(Job.Type.WITHDRAW, j.type);
        assertEquals("minecraft:diamond", j.itemId);
        assertEquals(64, j.count);
        assertNull(j.sourceChest);
    }

    @Test
    void withdrawSlotCarriesPositionAndSlot() {
        BlockPos pos = new BlockPos(10, 64, -20);
        Job j = Job.withdrawSlot(pos, 7, "minecraft:iron_ingot", 32);
        assertEquals(Job.Type.WITHDRAW_SLOT, j.type);
        assertEquals(pos, j.sourceChest);
        assertEquals(7, j.sourceSlot);
        assertEquals("minecraft:iron_ingot", j.itemId);
        assertEquals(32, j.count);
    }

    @Test
    void dumpInventoryIsDumpType() {
        assertEquals(Job.Type.DUMP_INVENTORY, Job.dumpInventory().type);
    }

    @Test
    void toStringFormatsEachTypeWithItsRelevantData() {
        // The exact strings feed the status panel - changing them silently breaks the UI,
        // so pin them rather than re-deriving at the call site.
        assertEquals("SCAN_REGION", Job.scanRegion().toString());
        assertEquals("SCAN_REGION (new/stale only)", Job.scanRegion(30_000L).toString());
        assertEquals("SORT_INPUT", Job.sortInput().toString());
        assertEquals("SORT_INPUT (scattered)", Job.sortInputRandom().toString());
        assertEquals("RANDOMIZE", Job.randomize().toString());
        assertEquals("EXPERIMENTAL RANDOMIZE", Job.experimentalRandomize().toString());
        assertEquals("DUMP inventory into nearest chest", Job.dumpInventory().toString());
        assertEquals("WITHDRAW 64x minecraft:diamond", Job.withdraw("minecraft:diamond", 64).toString());
        Job slot = Job.withdrawSlot(new BlockPos(1, 2, 3), 4, "minecraft:oak_log", 16);
        assertEquals("WITHDRAW minecraft:oak_log from 1,2,3 slot 4", slot.toString());
    }
}
