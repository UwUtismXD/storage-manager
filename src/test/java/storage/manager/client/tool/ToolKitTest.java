package storage.manager.client.tool;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolKitTest {

    @Test
    void categorisesVanillaToolsOnly() {
        assertEquals(ToolKit.Category.PICKAXE, ToolKit.categoryOf("minecraft:iron_pickaxe"));
        // "pickaxe" ends in "axe" - it must not read as an axe made of "iron_pick".
        assertEquals(ToolKit.Category.AXE, ToolKit.categoryOf("minecraft:netherite_axe"));
        assertEquals(ToolKit.Category.SHOVEL, ToolKit.categoryOf("minecraft:wooden_shovel"));
        assertEquals(ToolKit.Category.HOE, ToolKit.categoryOf("minecraft:copper_hoe"));
        assertEquals(ToolKit.Category.SWORD, ToolKit.categoryOf("minecraft:diamond_sword"));
        assertNull(ToolKit.categoryOf("minecraft:bow"));
        assertNull(ToolKit.categoryOf("minecraft:oak_log"));
        assertNull(ToolKit.categoryOf("minecraft:battle_axe"));
        assertNull(ToolKit.categoryOf(null));
    }

    @Test
    void harvestLevelsMatchVanillaTiers() {
        assertEquals(0, ToolKit.harvestLevel("minecraft:golden_pickaxe"));
        assertEquals(1, ToolKit.harvestLevel("minecraft:stone_pickaxe"));
        assertEquals(2, ToolKit.harvestLevel("minecraft:iron_pickaxe"));
        assertEquals(3, ToolKit.harvestLevel("minecraft:netherite_pickaxe"));
        assertEquals(-1, ToolKit.harvestLevel("minecraft:stick"));
        assertEquals(2, ToolKit.harvestLevelOfTier("iron"));
        assertEquals(0, ToolKit.harvestLevelOfTier("wood"));
        assertEquals(0, ToolKit.harvestLevelOfTier(null));
    }

    @Test
    void bestFirstPutsNetheriteOnTopAndGoldAtTheBottom() {
        List<String> picks = ToolKit.toolsBestFirst(ToolKit.Category.PICKAXE);
        assertEquals("minecraft:netherite_pickaxe", picks.getFirst());
        assertEquals("minecraft:golden_pickaxe", picks.getLast());
        assertTrue(ToolKit.rank("minecraft:copper_pickaxe") > ToolKit.rank("minecraft:stone_pickaxe"));
    }

    @Test
    void craftCandidatesSkipToolsTooWeakForTheBlock() {
        assertEquals(List.of("minecraft:iron_pickaxe", "minecraft:diamond_pickaxe"),
                ToolKit.craftCandidates(ToolKit.Category.PICKAXE, 2));
        assertEquals("minecraft:stone_axe", ToolKit.craftCandidates(ToolKit.Category.AXE, 0).getFirst());
    }

    @Test
    void cheapestToolClimbsTheTechTree() {
        assertEquals("minecraft:wooden_pickaxe", ToolKit.cheapestTool(ToolKit.Category.PICKAXE, 0));
        assertEquals("minecraft:stone_pickaxe", ToolKit.cheapestTool(ToolKit.Category.PICKAXE, 1));
        assertEquals("minecraft:iron_pickaxe", ToolKit.cheapestTool(ToolKit.Category.PICKAXE, 2));
        assertEquals("minecraft:diamond_shovel", ToolKit.cheapestTool(ToolKit.Category.SHOVEL, 3));
    }

    @Test
    void keepsOneSlotPerEquippedToolPreferringTheMostWorn() {
        List<ToolKit.Held> inventory = List.of(
                new ToolKit.Held(0, "minecraft:iron_pickaxe", 10),
                new ToolKit.Held(4, "minecraft:iron_pickaxe", 200),
                new ToolKit.Held(7, "minecraft:diamond_pickaxe", 0),
                new ToolKit.Held(9, "minecraft:oak_log", 0));
        Set<Integer> kept = ToolKit.keptSlots(List.of("minecraft:iron_pickaxe", "minecraft:stone_axe"), inventory);
        // The worn copy stays with the bot, the fresh one and the unequipped diamond pickaxe are cargo,
        // and an equipped axe that isn't carried protects nothing.
        assertEquals(Set.of(4), kept);
    }
}
