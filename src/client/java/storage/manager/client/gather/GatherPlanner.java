package storage.manager.client.gather;

import storage.manager.client.recipe.Recipe;
import storage.manager.client.recipe.RecipeBook;
import storage.manager.client.tool.ToolKit;

import java.util.ArrayList;
import java.util.List;

/**
 * Works out what a GATHER request actually mines, from the {@code "mine"} entries in
 * recipes.json and the game's own block loot tables. Pure data in, pure data out - which block ids exist and what tags they carry is
 * the executor's business, since that needs the game's registries.
 */
public final class GatherPlanner {

    private GatherPlanner() {
    }

    /**
     * What to mine for one item. {@code tool} is null when recipes.json doesn't say - the executor
     * then asks the blocks' own mineable tags - and {@code minHarvest} is the harvest level the
     * recipes demand (0 = anything).
     */
    public record Target(String item, List<String> blocks, ToolKit.Category tool, int minHarvest) {}

    /**
     * Resolves a request, in this order:
     * <ol>
     *   <li>{@code "mine"} recipes for the item - hand-written, so they win: {@code raw_iron} from
     *       both iron ores, with the tool tier spelled out.</li>
     *   <li>Every block whose loot table drops the item - string from cobwebs, apples from oak
     *       leaves, seeds from grass.</li>
     *   <li>A {@code "mine"} recipe for a block of that name ({@code iron_ore}), gathering what it
     *       drops, since mining an ore never yields the ore itself.</li>
     * </ol>
     * If nothing matches, {@code blocks} comes back empty - the item isn't a block drop (a mob
     * drop, say). The one exception is an empty {@code drops} index, where loot tables couldn't be
     * read at all: then the item is assumed to drop itself, like logs or sand.
     */
    public static Target resolve(RecipeBook book, DropIndex drops, String requested) {
        String item = requested;
        List<Recipe> mines = book.minesFor(item);
        if (mines.isEmpty()) {
            List<String> blocks = drops.blocksDropping(requested);
            if (!blocks.isEmpty()) {
                return new Target(requested, blocks, null, 0);
            }
            Recipe source = book.mineOf(requested);
            if (source != null && source.result != null) {
                item = source.result;
                mines = book.minesFor(item);
            }
        }
        if (mines.isEmpty()) {
            return new Target(requested, drops.isEmpty() ? List.of(requested) : List.of(), null, 0);
        }
        List<String> blocks = new ArrayList<>();
        ToolKit.Category tool = null;
        int minHarvest = 0;
        for (Recipe mine : mines) {
            if (!blocks.contains(mine.block)) {
                blocks.add(mine.block);
            }
            ToolKit.Category needed = ToolKit.Category.parse(mine.requiresTool);
            if (needed != null) {
                tool = tool != null ? tool : needed;
                minHarvest = Math.max(minHarvest, ToolKit.harvestLevelOfTier(mine.minTier));
            }
        }
        return new Target(item, List.copyOf(blocks), tool, minHarvest);
    }
}
