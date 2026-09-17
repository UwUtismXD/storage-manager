package storage.manager.client.recipe;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import storage.manager.StorageManager;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * In-memory table of every recipe from the bundled {@code recipes.json}, loaded once at client
 * init the same way {@link storage.manager.client.storage.StorageIndex} loads its JSON file.
 * Read-only after {@link #load()} - the file isn't user-editable at runtime, so there's no
 * dirty-tracking/flush machinery here.
 */
public class RecipeBook {
    private static final Gson GSON = new Gson();
    private static final Type RECIPE_LIST = new TypeToken<List<Recipe>>() {}.getType();

    private final Map<String, Recipe> craftByResult = new HashMap<>();
    private final Map<String, Recipe> smeltByResult = new HashMap<>();
    /** Every block that yields an item, in file order - raw iron comes from two ores. */
    private final Map<String, List<Recipe>> minesByResult = new HashMap<>();
    private final Map<String, Recipe> mineByBlock = new HashMap<>();
    /** In file order, which is also the order the planner tries them in. */
    private final List<Recipe> fuels = new ArrayList<>();
    private final List<Recipe> all = new ArrayList<>();

    /** Builds a book from recipes already in memory - for tests that don't want the bundled file. */
    public static RecipeBook of(List<Recipe> recipes) {
        RecipeBook book = new RecipeBook();
        book.addAll(recipes);
        return book;
    }

    /** A copy of this book with one more recipe, which replaces any existing one for the same result. */
    public RecipeBook withRecipe(Recipe extra) {
        RecipeBook book = new RecipeBook();
        book.addAll(all);
        book.addAll(List.of(extra));
        return book;
    }

    private void addAll(List<Recipe> recipes) {
        for (Recipe recipe : recipes) {
            all.add(recipe);
            if ("craft".equals(recipe.type) && recipe.result != null) {
                craftByResult.put(recipe.result, recipe);
            } else if (recipe.isSmelt() && recipe.result != null && recipe.input != null) {
                smeltByResult.put(recipe.result, recipe);
            } else if ("mine".equals(recipe.type) && recipe.result != null && recipe.block != null) {
                minesByResult.computeIfAbsent(recipe.result, key -> new ArrayList<>()).add(recipe);
                mineByBlock.putIfAbsent(recipe.block, recipe);
            } else if ("fuel".equals(recipe.type) && recipe.item != null && recipe.smeltsPerItem > 0) {
                fuels.add(recipe);
            }
        }
    }

    /** Reads {@code /recipes.json} off the classpath. Missing/unparsable file yields an empty book. */
    public static RecipeBook load() {
        RecipeBook book = new RecipeBook();
        try (InputStream in = RecipeBook.class.getResourceAsStream("/recipes.json")) {
            if (in == null) {
                StorageManager.LOGGER.warn("No recipes.json bundled - crafting has no known recipes");
                return book;
            }
            List<Recipe> recipes = GSON.fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), RECIPE_LIST);
            if (recipes != null) {
                book.addAll(recipes);
            }
        } catch (Exception e) {
            // Catches IOException + JsonSyntaxException - a broken bundled file shouldn't crash
            // client init, just leave crafting unavailable until it's fixed.
            StorageManager.LOGGER.error("Failed to load recipes.json", e);
        }
        return book;
    }

    /** The crafting-table recipe that produces {@code itemId}, or null if none is known. */
    public Recipe craftFor(String itemId) {
        return craftByResult.get(itemId);
    }

    /** The furnace recipe that produces {@code itemId}, or null if none is known. */
    public Recipe smeltFor(String itemId) {
        return smeltByResult.get(itemId);
    }

    /**
     * Every known way of making {@code itemId}, crafting first - a crafting table is instant, a
     * furnace takes ten seconds an item. Empty when the item can only come out of storage.
     */
    public List<Recipe> recipesFor(String itemId) {
        List<Recipe> recipes = new ArrayList<>(2);
        Recipe craft = craftByResult.get(itemId);
        if (craft != null) {
            recipes.add(craft);
        }
        Recipe smelt = smeltByResult.get(itemId);
        if (smelt != null) {
            recipes.add(smelt);
        }
        return recipes;
    }

    /**
     * The {@code "mine"} recipes producing {@code itemId}, one per block that drops it. Kept apart
     * from {@link #recipesFor}: crafting never plans a trip out to mine a missing ingredient.
     */
    public List<Recipe> minesFor(String itemId) {
        return List.copyOf(minesByResult.getOrDefault(itemId, List.of()));
    }

    /** The {@code "mine"} recipe for breaking {@code blockId}, or null if none is known. */
    public Recipe mineOf(String blockId) {
        return mineByBlock.get(blockId);
    }

    /** Every known furnace fuel, in the order the planner should try them. */
    public List<Recipe> fuels() {
        return List.copyOf(fuels);
    }
}
