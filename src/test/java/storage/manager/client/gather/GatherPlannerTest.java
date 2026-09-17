package storage.manager.client.gather;

import org.junit.jupiter.api.Test;

import storage.manager.client.recipe.RecipeBook;
import storage.manager.client.tool.ToolKit;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runs against the bundled recipes.json, so it also guards the "mine" entries themselves. */
class GatherPlannerTest {

    private static final RecipeBook BOOK = RecipeBook.load();

    private static final DropIndex DROPS = DropIndex.build(List.of("minecraft:cobweb"), Map.of(
            "minecraft:cobweb", """
                    {"pools":[{"entries":[{"type":"minecraft:alternatives","children":[
                      {"type":"minecraft:item","name":"minecraft:cobweb",
                       "conditions":[{"condition":"minecraft:match_tool","predicate":{"items":"minecraft:shears"}}]},
                      {"type":"minecraft:item","name":"minecraft:string"}]}]}]}
                    """)::get);

    @Test
    void itemWithMineRecipesGathersFromEveryBlockThatDropsIt() {
        GatherPlanner.Target target = GatherPlanner.resolve(BOOK, DROPS, "minecraft:raw_iron");
        assertEquals("minecraft:raw_iron", target.item());
        assertTrue(target.blocks().containsAll(List.of("minecraft:iron_ore", "minecraft:deepslate_iron_ore")));
        assertEquals(ToolKit.Category.PICKAXE, target.tool());
        assertEquals(1, target.minHarvest());
    }

    @Test
    void namingTheOreGathersWhatItDrops() {
        GatherPlanner.Target target = GatherPlanner.resolve(BOOK, DROPS, "minecraft:diamond_ore");
        assertEquals("minecraft:diamond", target.item());
        assertEquals(2, target.minHarvest());
    }

    @Test
    void cobblestoneComesFromStoneAndCobblestone() {
        GatherPlanner.Target target = GatherPlanner.resolve(BOOK, DROPS, "minecraft:cobblestone");
        assertTrue(target.blocks().containsAll(List.of("minecraft:stone", "minecraft:cobblestone")));
    }

    @Test
    void nonBlockItemComesFromTheBlockWhoseLootTableDropsIt() {
        GatherPlanner.Target target = GatherPlanner.resolve(BOOK, DROPS, "minecraft:string");
        assertEquals(List.of("minecraft:cobweb"), target.blocks());
    }

    @Test
    void itemNothingDropsHasNoBlocks() {
        assertTrue(GatherPlanner.resolve(BOOK, DROPS, "minecraft:gunpowder").blocks().isEmpty());
    }

    @Test
    void withoutLootTablesAnItemIsAssumedToDropItself() {
        GatherPlanner.Target target = GatherPlanner.resolve(BOOK, DropIndex.empty(), "minecraft:birch_log");
        assertEquals(List.of("minecraft:birch_log"), target.blocks());
        assertNull(target.tool());
        assertEquals(0, target.minHarvest());
    }

    @Test
    void recipeWithoutToolLeavesToolToTheBlockTags() {
        GatherPlanner.Target target = GatherPlanner.resolve(BOOK, DROPS, "minecraft:dirt");
        assertTrue(target.blocks().contains("minecraft:grass_block"));
        assertNull(target.tool());
    }
}
