package storage.manager.client.recipe;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Confirms the bundled {@code recipes.json} actually parses and yields the entries the mod
 * expects - a regression test for the data file itself, not just the loader code.
 */
class RecipeBookTest {

    @Test
    void loadsStickRecipeFromBundledFile() {
        RecipeBook book = RecipeBook.load();
        Recipe stick = book.craftFor("minecraft:stick");
        assertNotNull(stick, "recipes.json should define minecraft:stick");
        assertEquals(4, stick.resultCount);
        assertEquals(2, stick.ingredientCounts().get("minecraft:oak_planks"));
    }

    @Test
    void loadsIronPickaxeRecipeFromBundledFile() {
        RecipeBook book = RecipeBook.load();
        Recipe pickaxe = book.craftFor("minecraft:iron_pickaxe");
        assertNotNull(pickaxe, "recipes.json should define minecraft:iron_pickaxe");
        assertEquals(3, pickaxe.ingredientCounts().get("minecraft:iron_ingot"));
        assertEquals(2, pickaxe.ingredientCounts().get("minecraft:stick"));
    }

    @Test
    void loadsSmeltRecipesAndFuelsFromBundledFile() {
        RecipeBook book = RecipeBook.load();
        Recipe ingot = book.smeltFor("minecraft:iron_ingot");
        assertNotNull(ingot, "recipes.json should smelt minecraft:iron_ingot");
        assertEquals("minecraft:raw_iron", ingot.input);
        assertEquals(1, ingot.ingredientCounts().get("minecraft:raw_iron"));

        Recipe coalBlock = book.fuels().stream()
                .filter(fuel -> "minecraft:coal_block".equals(fuel.item)).findFirst().orElse(null);
        assertNotNull(coalBlock, "recipes.json should list coal blocks as fuel");
        assertEquals(80, coalBlock.smeltsPerItem);
    }

    @Test
    void unknownItemHasNoRecipe() {
        RecipeBook book = RecipeBook.load();
        assertNull(book.craftFor("minecraft:definitely_not_a_real_item"));
    }
}
