package storage.manager.client.gather;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import storage.manager.StorageManager;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Which blocks drop which items, read from the vanilla block loot tables. The client jar carries
 * the whole built-in datapack for singleplayer, so this works on any server without the mod
 * needing its own copy of the data - and it's what lets GATHER take any item a block drops
 * (string from cobwebs, saplings from leaves, flint from gravel), not just the ores listed in
 * recipes.json.
 *
 * <p>Drops that need shears or silk touch are left out: the bot's tools are plain, so a leaves
 * block is a source of saplings and sticks, never of leaves.
 */
public final class DropIndex {

    /** One block that can drop an item. {@code guaranteed} is false for chance or crop-age drops. */
    public record Source(String block, boolean guaranteed) {}

    private final Map<String, List<Source>> sourcesByItem = new LinkedHashMap<>();

    private DropIndex() {
    }

    public static DropIndex empty() {
        return new DropIndex();
    }

    /**
     * Builds the index from each block's loot table JSON. {@code lootTableJson} returns null for a
     * block with no table (air, fluids, technical blocks). A table that won't parse is skipped
     * rather than failing the whole index.
     */
    public static DropIndex build(Iterable<String> blockIds, Function<String, String> lootTableJson) {
        DropIndex index = new DropIndex();
        for (String block : blockIds) {
            String json = lootTableJson.apply(block);
            if (json == null) {
                continue;
            }
            try {
                index.addTable(block, JsonParser.parseString(json).getAsJsonObject());
            } catch (RuntimeException e) {
                StorageManager.LOGGER.debug("Skipping unreadable loot table for {}", block, e);
            }
        }
        return index;
    }

    public boolean isEmpty() {
        return sourcesByItem.isEmpty();
    }

    /**
     * Blocks worth mining for {@code item}: the ones that always drop it when there are any, so
     * sticks come from dead bushes rather than leaves at a 2% chance - otherwise every block that
     * can drop it at all.
     */
    public List<String> blocksDropping(String item) {
        List<Source> sources = sourcesByItem.getOrDefault(item, List.of());
        boolean anyGuaranteed = sources.stream().anyMatch(Source::guaranteed);
        List<String> blocks = new ArrayList<>();
        for (Source source : sources) {
            if ((source.guaranteed || !anyGuaranteed) && !blocks.contains(source.block)) {
                blocks.add(source.block);
            }
        }
        return blocks;
    }

    private void addTable(String block, JsonObject table) {
        for (JsonElement poolElement : array(table, "pools")) {
            JsonObject pool = poolElement.getAsJsonObject();
            JsonArray conditions = array(pool, "conditions");
            if (needsSpecialTool(conditions)) {
                continue;
            }
            boolean chance = isChancy(conditions);
            for (JsonElement entry : array(pool, "entries")) {
                addEntry(block, entry.getAsJsonObject(), chance);
            }
        }
    }

    private void addEntry(String block, JsonObject entry, boolean chance) {
        JsonArray conditions = array(entry, "conditions");
        if (needsSpecialTool(conditions)) {
            return;
        }
        chance |= isChancy(conditions);
        String type = string(entry, "type");
        if ("minecraft:item".equals(type)) {
            String item = string(entry, "name");
            if (item != null) {
                List<Source> sources = sourcesByItem.computeIfAbsent(item, key -> new ArrayList<>());
                Source source = new Source(block, !chance);
                if (!sources.contains(source)) {
                    sources.add(source);
                }
            }
            return;
        }
        // alternatives / group / sequence all nest their options under "children". Loot-table
        // references, tags and dynamic contents (a shulker box's items) aren't sources of anything
        // the bot can count on, so they're ignored.
        for (JsonElement child : array(entry, "children")) {
            addEntry(block, child.getAsJsonObject(), chance);
        }
    }

    /** True when these conditions only pass for a particular tool - shears, silk touch. */
    private static boolean needsSpecialTool(JsonArray conditions) {
        for (JsonElement element : conditions) {
            if (requiresTool(element.getAsJsonObject())) {
                return true;
            }
        }
        return false;
    }

    private static boolean requiresTool(JsonObject condition) {
        String type = string(condition, "condition");
        if ("minecraft:match_tool".equals(type)) {
            return true;
        }
        if ("minecraft:any_of".equals(type)) {
            JsonArray terms = array(condition, "terms");
            if (terms.isEmpty()) {
                return false;
            }
            for (JsonElement term : terms) {
                if (!requiresTool(term.getAsJsonObject())) {
                    return false;
                }
            }
            return true;
        }
        if ("minecraft:all_of".equals(type)) {
            for (JsonElement term : array(condition, "terms")) {
                if (requiresTool(term.getAsJsonObject())) {
                    return true;
                }
            }
        }
        // "inverted" (no shears) and everything else can be met by the bot's own tools.
        return false;
    }

    /** Random or state-dependent drops - a sapling from leaves, wheat only from a grown crop. */
    private static boolean isChancy(JsonArray conditions) {
        for (JsonElement element : conditions) {
            String type = string(element.getAsJsonObject(), "condition");
            if ("minecraft:random_chance".equals(type)
                    || "minecraft:random_chance_with_enchanted_bonus".equals(type)
                    || "minecraft:table_bonus".equals(type)
                    || "minecraft:block_state_property".equals(type)) {
                return true;
            }
        }
        return false;
    }

    private static JsonArray array(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonArray() ? value.getAsJsonArray() : new JsonArray();
    }

    private static String string(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : null;
    }
}
