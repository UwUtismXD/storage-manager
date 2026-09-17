package storage.manager.client.recipe;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tier 1 tests for {@link Recipe#ingredientCounts()} - the one piece of genuinely new logic the
 * crafting feature introduces, kept pure (no Minecraft classes) so it's testable the same way
 * {@code VirtualSorter} is.
 */
class RecipeTest {

    private static Recipe.ShapeEntry cell(String item, int x, int y) {
        Recipe.ShapeEntry entry = new Recipe.ShapeEntry();
        entry.item = item;
        entry.x = x;
        entry.y = y;
        return entry;
    }

    @Test
    void shapedRecipeMergesDuplicateItemsAcrossCells() {
        Recipe recipe = new Recipe();
        recipe.shaped = true;
        recipe.shape = List.of(
                cell("minecraft:iron_ingot", 0, 0),
                cell("minecraft:iron_ingot", 1, 0),
                cell("minecraft:iron_ingot", 2, 0),
                cell("minecraft:stick", 1, 1),
                cell("minecraft:stick", 1, 2));

        Map<String, Integer> counts = recipe.ingredientCounts();
        assertEquals(3, counts.get("minecraft:iron_ingot"));
        assertEquals(2, counts.get("minecraft:stick"));
        assertEquals(2, counts.size());
    }

    @Test
    void shapelessRecipeMergesDuplicateEntries() {
        Recipe recipe = new Recipe();
        recipe.shaped = false;
        recipe.ingredients = List.of("minecraft:bone", "minecraft:bone");

        Map<String, Integer> counts = recipe.ingredientCounts();
        assertEquals(2, counts.get("minecraft:bone"));
        assertEquals(1, counts.size());
    }

    @Test
    void shapelessSingleIngredientCountsOne() {
        Recipe recipe = new Recipe();
        recipe.shaped = false;
        recipe.ingredients = List.of("minecraft:bone");

        assertEquals(1, recipe.ingredientCounts().get("minecraft:bone"));
    }

    @Test
    void missingShapeOrIngredientsYieldsNoRequirements() {
        Recipe shapedWithNoShape = new Recipe();
        shapedWithNoShape.shaped = true;
        assertEquals(0, shapedWithNoShape.ingredientCounts().size());

        Recipe shapelessWithNoIngredients = new Recipe();
        shapelessWithNoIngredients.shaped = false;
        assertEquals(0, shapelessWithNoIngredients.ingredientCounts().size());
    }
}
