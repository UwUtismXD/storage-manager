package storage.manager.client.recipe;

import org.junit.jupiter.api.Test;

import storage.manager.client.tool.ToolKit;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runs the bundled recipes against a fake world that starts with nothing. */
class ChainPlannerTest {

    private static final RecipeBook BOOK = RecipeBook.load();

    private static final Map<String, ChainPlanner.Requirement> MINING = Map.of(
            "minecraft:oak_log", ChainPlanner.Requirement.BARE_HANDS,
            "minecraft:cobblestone", new ChainPlanner.Requirement(ToolKit.Category.PICKAXE, 0),
            "minecraft:coal", new ChainPlanner.Requirement(ToolKit.Category.PICKAXE, 0),
            "minecraft:raw_iron", new ChainPlanner.Requirement(ToolKit.Category.PICKAXE, 1),
            "minecraft:diamond", new ChainPlanner.Requirement(ToolKit.Category.PICKAXE, 2));

    /** Storage plus the tools the bot carries, updated as steps are "run". */
    private static final class FakeWorld implements ChainPlanner.World {
        final Map<String, Integer> storage = new HashMap<>();
        final Set<String> carried = new HashSet<>();

        @Override
        public int stock(String item) {
            return storage.getOrDefault(item, 0);
        }

        @Override
        public ChainPlanner.Requirement mineRequirement(String item) {
            return MINING.get(item);
        }

        @Override
        public boolean canMine(ChainPlanner.Requirement requirement) {
            return carried.stream().anyMatch(tool -> ToolKit.categoryOf(tool) == requirement.tool()
                    && ToolKit.harvestLevel(tool) >= requirement.minHarvest());
        }

        void run(ChainPlanner.Step step) {
            switch (step.action()) {
                case GATHER -> step.items().forEach((item, count) -> storage.merge(item, count, Integer::sum));
                case CRAFT -> step.items().forEach((item, count) -> {
                    // Run every station step as a ledger, so leftovers (spare planks, sticks) stay
                    // in storage the way the bot's put-away leaves them.
                    CraftPlanner.Plan plan = CraftPlanner.plan(BOOK, this::stock, item, count);
                    assertTrue(plan.possible(), "planner asked to craft " + item + " but it isn't possible");
                    for (CraftPlanner.Step craft : plan.steps()) {
                        craft.recipe().ingredientCounts()
                                .forEach((used, n) -> storage.merge(used, -n * craft.crafts(), Integer::sum));
                        if (craft.fuel() != null) {
                            storage.merge(craft.fuel(), -craft.fuelCount(), Integer::sum);
                        }
                        storage.merge(craft.item(), craft.crafts() * Math.max(1, craft.recipe().resultCount),
                                Integer::sum);
                    }
                    if (ToolKit.categoryOf(item) != null) {
                        storage.merge(item, -1, Integer::sum);
                        carried.add(item);
                    }
                });
                default -> { }
            }
        }
    }

    @Test
    void firstTripBringsBackTheLogsForTheWholeChain() {
        FakeWorld world = new FakeWorld();
        ChainPlanner.Step first = ChainPlanner.next(BOOK, Map.of("minecraft:diamond_pickaxe", 1), world);

        assertEquals(ChainPlanner.Action.GATHER, first.action());
        assertEquals(Set.of("minecraft:oak_log"), first.items().keySet());
        assertEquals(List.of("minecraft:wooden_pickaxe", "minecraft:stone_pickaxe", "minecraft:iron_pickaxe"),
                first.tools());
        // Every tier's sticks and the wooden pickaxe's planks, not just the wooden pickaxe's own.
        int woodenOnly = CraftPlanner.plan(BOOK, item -> 0, "minecraft:wooden_pickaxe", 1)
                .missing().get("minecraft:oak_log");
        int wholeChain = first.items().get("minecraft:oak_log");
        assertTrue(wholeChain >= woodenOnly, "whole chain " + wholeChain + " vs wooden only " + woodenOnly);
        assertEquals(wholeChain, first.missing().get("minecraft:oak_log"));
    }

    @Test
    void gathersEachMaterialOnceOnTheWayToADiamondPickaxe() {
        assertEachMaterialGatheredOnce(List.of("minecraft:diamond_pickaxe"));
    }

    @Test
    void gathersEachMaterialOnceForTheWholeKit() {
        assertEachMaterialGatheredOnce(List.of("minecraft:diamond_pickaxe", "minecraft:diamond_axe",
                "minecraft:diamond_shovel", "minecraft:diamond_sword", "minecraft:diamond_hoe"));
    }

    /** Drives the planner round by round the way the executor does, dropping tools from the goal once carried. */
    private static void assertEachMaterialGatheredOnce(List<String> tools) {
        FakeWorld world = new FakeWorld();
        Map<String, Integer> gatherTrips = new HashMap<>();
        List<String> log = new java.util.ArrayList<>();
        for (int round = 0; round < 30; round++) {
            Map<String, Integer> targets = new java.util.LinkedHashMap<>();
            tools.stream().filter(tool -> !world.carried.contains(tool)).forEach(tool -> targets.put(tool, 1));
            ChainPlanner.Step step = ChainPlanner.next(BOOK, targets, world);
            log.add(step.action() + " " + step.items() + " short " + step.missing());
            if (step.action() == ChainPlanner.Action.DONE) {
                break;
            }
            assertTrue(step.action() != ChainPlanner.Action.STUCK, step.problem() + "\n" + String.join("\n", log));
            if (step.action() == ChainPlanner.Action.GATHER) {
                step.items().keySet().forEach(item -> gatherTrips.merge(item, 1, Integer::sum));
            }
            world.run(step);
        }
        assertTrue(world.carried.containsAll(tools), "never finished:\n" + String.join("\n", log));
        gatherTrips.forEach((item, trips) -> assertEquals(1, trips,
                item + " was gathered " + trips + " times:\n" + String.join("\n", log)));
    }

    @Test
    void itemNothingDropsIsStuck() {
        ChainPlanner.Step step = ChainPlanner.next(BOOK, Map.of("minecraft:gold_ingot", 1), new FakeWorld());
        assertEquals(ChainPlanner.Action.STUCK, step.action());
    }

    @Test
    void enoughInStorageCraftsTheTargetStraightAway() {
        FakeWorld world = new FakeWorld();
        world.storage.put("minecraft:oak_log", 4);
        ChainPlanner.Step step = ChainPlanner.next(BOOK, Map.of("minecraft:stick", 8), world);
        assertEquals(ChainPlanner.Action.CRAFT, step.action());
        assertEquals(Map.of("minecraft:stick", 8), step.items());
    }
}
