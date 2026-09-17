package storage.manager.client.recipe;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tier 1 tests for {@link CraftPlanner} - the "have it? craft it? craft what it needs first?"
 * resolution, checked against hand-built recipes and a fake storage count.
 */
class CraftPlannerTest {

    private static Recipe shapeless(String result, int resultCount, String... ingredients) {
        Recipe recipe = new Recipe();
        recipe.type = "craft";
        recipe.result = result;
        recipe.resultCount = resultCount;
        recipe.shaped = false;
        recipe.ingredients = List.of(ingredients);
        return recipe;
    }

    private static final RecipeBook BOOK = RecipeBook.of(List.of(
            shapeless("planks", 4, "log"),
            shapeless("stick", 4, "planks", "planks"),
            shapeless("pickaxe", 1, "diamond", "diamond", "diamond", "stick", "stick"),
            shapeless("torch", 4, "coal", "stick"),
            shapeless("ingot", 9, "block"),
            shapeless("block", 1, "ingot", "ingot", "ingot", "ingot", "ingot", "ingot", "ingot", "ingot", "ingot")));

    private static CraftPlanner.Plan plan(Map<String, Integer> storage, String item, int count) {
        return CraftPlanner.plan(BOOK, id -> storage.getOrDefault(id, 0), item, count);
    }

    private static List<String> stepSummary(CraftPlanner.Plan plan) {
        return plan.steps().stream().map(s -> s.crafts() + "x" + s.item()).toList();
    }

    @Test
    void usesStorageDirectlyWhenIngredientsAreThere() {
        CraftPlanner.Plan plan = plan(Map.of("diamond", 3, "stick", 2), "pickaxe", 1);

        assertTrue(plan.possible());
        assertEquals(List.of("1xpickaxe"), stepSummary(plan));
        assertEquals(Map.of("diamond", 3, "stick", 2), plan.withdrawals());
    }

    @Test
    void craftsMissingSticksAllTheWayDownFromLogs() {
        CraftPlanner.Plan plan = plan(Map.of("diamond", 12, "log", 5), "pickaxe", 4);

        assertTrue(plan.possible());
        // 8 sticks = 2 stick crafts = 4 planks = 1 log.
        assertEquals(List.of("1xplanks", "2xstick", "4xpickaxe"), stepSummary(plan));
        assertEquals(Map.of("diamond", 12, "log", 1), plan.withdrawals());
    }

    @Test
    void onlyCraftsTheShortfallWhenStorageHasSome() {
        CraftPlanner.Plan plan = plan(Map.of("diamond", 12, "stick", 5, "planks", 64), "pickaxe", 4);

        assertTrue(plan.possible());
        // 5 sticks from storage, 3 more = 1 stick craft = 2 planks from storage.
        assertEquals(List.of("1xstick", "4xpickaxe"), stepSummary(plan));
        assertEquals(Map.of("diamond", 12, "stick", 5, "planks", 2), plan.withdrawals());
    }

    @Test
    void reportsRawMaterialAtTheBottomOfTheChainWhenMissing() {
        CraftPlanner.Plan plan = plan(Map.of("diamond", 3), "pickaxe", 1);

        assertFalse(plan.possible());
        assertEquals(Map.of("log", 1), plan.missing());
    }

    @Test
    void reportsIngredientWithNoRecipe() {
        CraftPlanner.Plan plan = plan(Map.of("stick", 2), "pickaxe", 1);

        assertFalse(plan.possible());
        assertEquals(Map.of("diamond", 3), plan.missing());
    }

    @Test
    void surplusFromOneCraftIsReusedLater() {
        Map<String, Integer> storage = new HashMap<>(Map.of("coal", 1, "diamond", 3, "planks", 2));
        // Torch needs 1 stick; crafting it makes 4, so the pickaxe's 2 come from the spare.
        CraftPlanner.Plan torch = plan(storage, "torch", 4);
        assertEquals(List.of("1xstick", "1xtorch"), stepSummary(torch));

        CraftPlanner.Plan both = CraftPlanner.plan(RecipeBook.of(List.of(
                shapeless("stick", 4, "planks", "planks"),
                shapeless("kit", 1, "stick", "stick", "stick"),
                shapeless("combo", 1, "stick", "kit"))), id -> storage.getOrDefault(id, 0), "combo", 1);
        assertTrue(both.possible());
        assertEquals(List.of("1xstick", "1xkit", "1xcombo"), stepSummary(both));
        assertEquals(Map.of("planks", 2), both.withdrawals());
    }

    @Test
    void cyclicRecipesDoNotRecurseForever() {
        CraftPlanner.Plan plan = plan(Map.of(), "block", 1);

        assertFalse(plan.possible());
        assertEquals(Map.of("block", 1), plan.missing());
    }

    @Test
    void unknownTargetIsMissing() {
        CraftPlanner.Plan plan = plan(Map.of(), "nope", 2);

        assertFalse(plan.possible());
        assertEquals(Map.of("nope", 2), plan.missing());
    }

    private static Recipe smelt(String input, String result) {
        Recipe recipe = new Recipe();
        recipe.type = "smelt";
        recipe.input = input;
        recipe.result = result;
        return recipe;
    }

    private static Recipe fuel(String item, int smeltsPerItem) {
        Recipe recipe = new Recipe();
        recipe.type = "fuel";
        recipe.item = item;
        recipe.smeltsPerItem = smeltsPerItem;
        return recipe;
    }

    private static final RecipeBook SMELT_BOOK = RecipeBook.of(List.of(
            smelt("raw_iron", "ingot"),
            smelt("log", "charcoal"),
            shapeless("ingot", 9, "block"),
            shapeless("pickaxe", 1, "ingot", "ingot", "ingot"),
            fuel("coal", 8),
            fuel("charcoal", 8),
            fuel("coal_block", 80)));

    private static CraftPlanner.Plan smeltPlan(Map<String, Integer> storage, String item, int count) {
        return CraftPlanner.plan(SMELT_BOOK, id -> storage.getOrDefault(id, 0), item, count);
    }

    @Test
    void smeltsWithFuelRoundedUpToWholeItems() {
        CraftPlanner.Plan plan = smeltPlan(Map.of("raw_iron", 9, "coal", 5), "ingot", 9);

        assertTrue(plan.possible());
        CraftPlanner.Step step = plan.steps().getFirst();
        assertTrue(step.smelt());
        assertEquals(9, step.crafts());
        assertEquals("coal", step.fuel());
        assertEquals(2, step.fuelCount());
        assertEquals(Map.of("raw_iron", 9, "coal", 2), plan.withdrawals());
        assertTrue(plan.needsFurnace());
        assertFalse(plan.needsCraftingTable());
    }

    @Test
    void fallsBackToSmeltingWhenTheCraftRecipeCantBeSourced() {
        // Ingots have a craft recipe (from blocks) listed first, but there are no blocks - raw iron it is.
        CraftPlanner.Plan plan = smeltPlan(Map.of("raw_iron", 3, "coal", 1), "pickaxe", 1);

        assertTrue(plan.possible(), () -> "missing " + plan.missing());
        assertEquals(List.of("3xingot", "1xpickaxe"), stepSummary(plan));
        assertTrue(plan.needsFurnace());
        assertTrue(plan.needsCraftingTable());
        assertEquals(Map.of("raw_iron", 3, "coal", 1), plan.withdrawals());
    }

    @Test
    void prefersCraftingWhenBothWork() {
        CraftPlanner.Plan plan = smeltPlan(Map.of("block", 1, "raw_iron", 9, "coal", 2), "ingot", 9);

        assertEquals(List.of("1xingot"), stepSummary(plan));
        assertFalse(plan.steps().getFirst().smelt());
    }

    @Test
    void picksAFuelStorageActuallyHas() {
        CraftPlanner.Plan plan = smeltPlan(Map.of("raw_iron", 4, "coal_block", 1), "ingot", 4);

        assertTrue(plan.possible());
        assertEquals("coal_block", plan.steps().getFirst().fuel());
        assertEquals(1, plan.steps().getFirst().fuelCount());
    }

    @Test
    void makesCharcoalForFuelWhenCoalRunsShort() {
        // 16 raw iron burns 2 fuel. One coal isn't enough on its own, but it can fire the smelt
        // that turns 2 logs into the 2 charcoal the iron needs.
        CraftPlanner.Plan plan = smeltPlan(Map.of("raw_iron", 16, "log", 2, "coal", 1), "ingot", 16);

        assertTrue(plan.possible(), () -> "missing " + plan.missing());
        assertEquals(List.of("2xcharcoal", "16xingot"), stepSummary(plan));
        assertEquals("coal", plan.steps().get(0).fuel());
        assertEquals("charcoal", plan.steps().get(1).fuel());
        assertEquals(Map.of("raw_iron", 16, "log", 2, "coal", 1), plan.withdrawals());
    }

    @Test
    void charcoalCantFuelItsOwnSmelt() {
        CraftPlanner.Plan plan = smeltPlan(Map.of("raw_iron", 8, "log", 5), "ingot", 8);

        assertFalse(plan.possible());
    }

    @Test
    void missingFuelIsReported() {
        CraftPlanner.Plan plan = smeltPlan(Map.of("raw_iron", 3), "ingot", 3);

        assertFalse(plan.possible());
        assertEquals(Map.of("coal", 1), plan.missing());
    }

    @Test
    void bundledRecipesSmeltIronPickaxeFromRawIron() {
        Map<String, Integer> storage = Map.of("minecraft:raw_iron", 3, "minecraft:coal", 1,
                "minecraft:stick", 2);
        CraftPlanner.Plan plan = CraftPlanner.plan(RecipeBook.load(),
                id -> storage.getOrDefault(id, 0), "minecraft:iron_pickaxe", 1);

        assertTrue(plan.possible(), () -> "missing " + plan.missing());
        assertTrue(plan.needsFurnace());
    }

    @Test
    void bundledRecipesChainDiamondPickaxeFromLogs() {
        Map<String, Integer> storage = Map.of("minecraft:diamond", 3, "minecraft:oak_log", 1);
        CraftPlanner.Plan plan = CraftPlanner.plan(RecipeBook.load(),
                id -> storage.getOrDefault(id, 0), "minecraft:diamond_pickaxe", 1);

        assertTrue(plan.possible(), () -> "missing " + plan.missing());
        assertEquals("minecraft:diamond_pickaxe", plan.steps().getLast().item());
    }
}
