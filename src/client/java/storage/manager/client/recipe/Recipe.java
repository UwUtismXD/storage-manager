package storage.manager.client.recipe;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One entry from the bundled {@code recipes.json}. Mirrors the file's JSON shape directly (Gson
 * deserialization target), so field names here must match the JSON keys exactly.
 *
 * <p>{@code "craft"}, {@code "smelt"} and {@code "fuel"} entries feed the CRAFT job; {@code "mine"}
 * entries tell the GATHER job which blocks drop an item and what tool breaking them needs.
 */
public class Recipe {
    /** One of {@code "craft"}, {@code "smelt"}, {@code "mine"}, {@code "fuel"}. */
    public String type;

    /** {@code craft}/{@code smelt}/{@code mine}: the item produced. */
    public String result;
    public int resultCount = 1;

    /** {@code craft} only: true selects {@link #shape}, false selects {@link #ingredients}. */
    public boolean shaped;
    /** {@code craft}, shaped only: one entry per occupied crafting-grid cell. */
    public List<ShapeEntry> shape;
    /** {@code craft}, shapeless only: one entry per required item, order irrelevant. */
    public List<String> ingredients;

    /** {@code smelt} only: the item placed in the furnace's input slot. */
    public String input;

    /** {@code mine} only: the block broken to get {@link #result}. */
    public String block;
    /**
     * {@code mine} only, optional: {@code "pickaxe"}, {@code "axe"}, {@code "shovel"} or
     * {@code "hoe"}. Null leaves the choice to the block's own mineable tag.
     */
    public String requiresTool;
    /** {@code mine} only, optional: minimum tool tier, e.g. {@code "iron"}. */
    public String minTier;

    /** {@code fuel} only: the item that may be burned as furnace fuel. */
    public String item;
    /**
     * {@code fuel} only: how many items one {@link #item} smelts before burning out - 8 for coal,
     * 80 for a coal block. Vanilla's fractional values (a plank smelts 1.5) are rounded down, so
     * the bot never counts on a burn that runs out partway through its last item.
     */
    public int smeltsPerItem = 8;

    public static class ShapeEntry {
        public String item;
        public int x;
        public int y;
    }

    public boolean isSmelt() {
        return "smelt".equals(type);
    }

    /**
     * How many of each ingredient item a single craft consumes, merging duplicate entries (e.g.
     * three iron ingots in a shaped recipe, or a shapeless list naming the same item twice). A
     * smelt consumes one {@link #input} per run - its fuel is planned separately, since which fuel
     * gets burned depends on what's in storage.
     * Pure function of already-loaded data - no Minecraft classes involved - so it's covered by
     * plain unit tests the same way {@code VirtualSorter} is.
     */
    public Map<String, Integer> ingredientCounts() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        if (isSmelt()) {
            if (input != null) {
                counts.put(input, 1);
            }
        } else if (shaped) {
            if (shape != null) {
                for (ShapeEntry entry : shape) {
                    counts.merge(entry.item, 1, Integer::sum);
                }
            }
        } else if (ingredients != null) {
            for (String ingredient : ingredients) {
                counts.merge(ingredient, 1, Integer::sum);
            }
        }
        return counts;
    }
}
