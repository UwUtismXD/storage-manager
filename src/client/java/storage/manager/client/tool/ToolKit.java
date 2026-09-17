package storage.manager.client.tool;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * What counts as a tool, how good it is, and which of the bot's stacks are its own equipment
 * rather than cargo. Works on item ids alone - no Minecraft classes - so it's covered by plain
 * unit tests.
 *
 * <p>The bot "equips" at most one tool id per {@link Category}. That choice is stored rather than
 * recomputed from whatever happens to be best in the inventory: a diamond pickaxe the user asked
 * to have withdrawn must still reach the output chest, not get adopted because it outranks the
 * iron one the bot is carrying.
 */
public final class ToolKit {

    public enum Category {
        /** Swords mine nothing gathering asks for - they're a category so the kit's sword stays equipped. */
        PICKAXE, AXE, SHOVEL, HOE, SWORD;

        /** Lower-case name, as stored in the index and written in recipes.json's {@code requiresTool}. */
        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }

        /** Null for null, blank or unknown names. */
        public static Category parse(String name) {
            if (name == null) {
                return null;
            }
            for (Category category : values()) {
                if (category.key().equals(name.trim().toLowerCase(Locale.ROOT))) {
                    return category;
                }
            }
            return null;
        }
    }

    /**
     * A tool material and the harvest level it mines at (0 = wood, 1 = stone, 2 = iron,
     * 3 = diamond). {@code alias} is the short name recipes.json uses for {@code minTier}.
     */
    private record Material(String prefix, String alias, int harvestLevel) {}

    /**
     * Worst to best. Gold mines fastest but lasts 32 uses and only at wood level, so it ranks
     * bottom. Copper (1.21.9+) mines at stone level but lasts longer and digs faster than stone.
     */
    private static final List<Material> MATERIALS = List.of(
            new Material("golden", "gold", 0),
            new Material("wooden", "wood", 0),
            new Material("stone", "stone", 1),
            new Material("copper", "copper", 1),
            new Material("iron", "iron", 2),
            new Material("diamond", "diamond", 3),
            new Material("netherite", "netherite", 3));

    /**
     * Order the bot tries when it has to craft a tool: cheap but durable first. A wooden pickaxe
     * breaks after 59 blocks, and spending diamonds on an axe for chopping logs is a waste.
     */
    private static final List<String> CRAFT_PREFERENCE = List.of("stone", "copper", "iron", "wooden", "diamond");

    private ToolKit() {
    }

    /** One occupied player-inventory slot. {@code damage} is uses spent, 0 for a fresh tool. */
    public record Held(int slot, String item, int damage) {}

    /** The tool category of an item id, or null if it isn't a vanilla pickaxe/axe/shovel/hoe. */
    public static Category categoryOf(String itemId) {
        String path = path(itemId);
        for (Category category : Category.values()) {
            String suffix = "_" + category.key();
            if (path.endsWith(suffix) && material(path.substring(0, path.length() - suffix.length())) != null) {
                return category;
            }
        }
        return null;
    }

    /** Harvest level of a tool id, or -1 if it isn't one. */
    public static int harvestLevel(String itemId) {
        Material material = materialOf(itemId);
        return material != null ? material.harvestLevel : -1;
    }

    /** Position in the worst-to-best ordering, or -1 if it isn't a tool. */
    public static int rank(String itemId) {
        Material material = materialOf(itemId);
        return material != null ? MATERIALS.indexOf(material) : -1;
    }

    /**
     * Harvest level a {@code minTier} name stands for - {@code "wood"}, {@code "wooden"},
     * {@code "iron"} and so on. Null or unknown means anything will do.
     */
    public static int harvestLevelOfTier(String tier) {
        Material material = tier == null ? null : material(tier.trim().toLowerCase(Locale.ROOT));
        return material != null ? material.harvestLevel : 0;
    }

    /** Every vanilla tool id of a category, best first. */
    public static List<String> toolsBestFirst(Category category) {
        List<String> ids = new ArrayList<>(MATERIALS.size());
        for (int i = MATERIALS.size() - 1; i >= 0; i--) {
            ids.add(toolId(MATERIALS.get(i).prefix, category));
        }
        return ids;
    }

    /** Tool ids worth crafting for a category that mine at {@code minHarvest} or better, in preference order. */
    public static List<String> craftCandidates(Category category, int minHarvest) {
        List<String> ids = new ArrayList<>();
        for (String prefix : CRAFT_PREFERENCE) {
            if (material(prefix).harvestLevel >= minHarvest) {
                ids.add(toolId(prefix, category));
            }
        }
        return ids;
    }

    /**
     * The cheapest tool of a category that mines at {@code minHarvest}: the one to work up to when
     * the bot has nothing at all - wood, then stone, then iron, then diamond, each tier's materials
     * mineable with the tier below.
     */
    public static String cheapestTool(Category category, int minHarvest) {
        String prefix = switch (Math.max(0, minHarvest)) {
            case 0 -> "wooden";
            case 1 -> "stone";
            case 2 -> "iron";
            default -> "diamond";
        };
        return toolId(prefix, category);
    }

    /**
     * The player-inventory slots holding equipped tools, which every deposit leaves alone. One
     * slot per equipped id: when the bot carries several of the same tool, the most worn one is
     * kept, so a fresh copy that was just withdrawn or crafted is the one that gets put away.
     */
    public static Set<Integer> keptSlots(Collection<String> equipped, List<Held> inventory) {
        Set<Integer> kept = new HashSet<>();
        for (String tool : new HashSet<>(equipped)) {
            Held best = null;
            for (Held held : inventory) {
                if (!tool.equals(held.item)) {
                    continue;
                }
                if (best == null || held.damage > best.damage) {
                    best = held;
                }
            }
            if (best != null) {
                kept.add(best.slot);
            }
        }
        return kept;
    }

    private static String toolId(String prefix, Category category) {
        return "minecraft:" + prefix + "_" + category.key();
    }

    private static Material materialOf(String itemId) {
        Category category = categoryOf(itemId);
        if (category == null) {
            return null;
        }
        String path = path(itemId);
        return material(path.substring(0, path.length() - category.key().length() - 1));
    }

    private static Material material(String name) {
        for (Material material : MATERIALS) {
            if (material.prefix.equals(name) || material.alias.equals(name)) {
                return material;
            }
        }
        return null;
    }

    private static String path(String itemId) {
        if (itemId == null) {
            return "";
        }
        int colon = itemId.indexOf(':');
        return colon >= 0 ? itemId.substring(colon + 1) : itemId;
    }
}
