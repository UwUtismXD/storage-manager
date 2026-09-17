package storage.manager.client.gather;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Loot tables trimmed down from the vanilla ones, keeping the structure that matters. */
class DropIndexTest {

    private static final String SHEARS_OR_SILK = """
            {"condition":"minecraft:any_of","terms":[
              {"condition":"minecraft:match_tool","predicate":{"items":"minecraft:shears"}},
              {"condition":"minecraft:match_tool","predicate":{"predicates":{"minecraft:enchantments":[
                {"enchantments":"minecraft:silk_touch","levels":{"min":1}}]}}}]}""";

    private static final Map<String, String> TABLES = Map.of(
            "minecraft:oak_leaves", """
                    {"pools":[
                      {"entries":[{"type":"minecraft:alternatives","children":[
                        {"type":"minecraft:item","name":"minecraft:oak_leaves","conditions":[%s]},
                        {"type":"minecraft:item","name":"minecraft:oak_sapling","conditions":[
                          {"condition":"minecraft:table_bonus","chances":[0.05]}]}]}]},
                      {"conditions":[{"condition":"minecraft:inverted","term":%s}],
                       "entries":[{"type":"minecraft:item","name":"minecraft:stick","conditions":[
                          {"condition":"minecraft:table_bonus","chances":[0.02]}]}]}]}
                    """.formatted(SHEARS_OR_SILK, SHEARS_OR_SILK),
            "minecraft:dead_bush", """
                    {"pools":[{"entries":[{"type":"minecraft:alternatives","children":[
                      {"type":"minecraft:item","name":"minecraft:dead_bush","conditions":[
                        {"condition":"minecraft:match_tool","predicate":{"items":"minecraft:shears"}}]},
                      {"type":"minecraft:item","name":"minecraft:stick"}]}]}]}
                    """,
            "minecraft:iron_ore", """
                    {"pools":[{"entries":[{"type":"minecraft:alternatives","children":[
                      {"type":"minecraft:item","name":"minecraft:iron_ore","conditions":[
                        {"condition":"minecraft:match_tool","predicate":{}}]},
                      {"type":"minecraft:item","name":"minecraft:raw_iron"}]}]}]}
                    """,
            "minecraft:broken", "{not json");

    private static final DropIndex DROPS = DropIndex.build(
            List.of("minecraft:oak_leaves", "minecraft:dead_bush", "minecraft:iron_ore", "minecraft:broken",
                    "minecraft:air"),
            TABLES::get);

    @Test
    void silkTouchAndShearsDropsAreNotSources() {
        assertTrue(DROPS.blocksDropping("minecraft:oak_leaves").isEmpty());
        assertTrue(DROPS.blocksDropping("minecraft:iron_ore").isEmpty());
        assertEquals(List.of("minecraft:iron_ore"), DROPS.blocksDropping("minecraft:raw_iron"));
    }

    @Test
    void chanceDropsCountWhenNothingDropsTheItemReliably() {
        assertEquals(List.of("minecraft:oak_leaves"), DROPS.blocksDropping("minecraft:oak_sapling"));
    }

    @Test
    void guaranteedSourcesWinOverChanceOnes() {
        // Leaves drop sticks 2% of the time and dead bushes always do - only the bush is worth mining.
        assertEquals(List.of("minecraft:dead_bush"), DROPS.blocksDropping("minecraft:stick"));
    }

    /** The real tables, read off the game jar the same way the executor does. */
    @Test
    void readsTheGamesOwnLootTables() {
        DropIndex real = DropIndex.build(
                List.of("minecraft:cobweb", "minecraft:gravel", "minecraft:oak_leaves", "minecraft:iron_ore"),
                block -> {
                    String path = "/data/minecraft/loot_table/blocks/" + block.substring("minecraft:".length()) + ".json";
                    try (var in = DropIndexTest.class.getResourceAsStream(path)) {
                        return in == null ? null : new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                    } catch (java.io.IOException e) {
                        return null;
                    }
                });
        assertEquals(List.of("minecraft:cobweb"), real.blocksDropping("minecraft:string"));
        assertEquals(List.of("minecraft:gravel"), real.blocksDropping("minecraft:flint"));
        assertEquals(List.of("minecraft:oak_leaves"), real.blocksDropping("minecraft:apple"));
        assertEquals(List.of("minecraft:iron_ore"), real.blocksDropping("minecraft:raw_iron"));
        assertTrue(real.blocksDropping("minecraft:iron_ore").isEmpty());
    }

    @Test
    void unknownItemHasNoSources() {
        assertTrue(DROPS.blocksDropping("minecraft:gunpowder").isEmpty());
    }
}
