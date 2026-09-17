package storage.manager.client.recipe;

import storage.manager.client.tool.ToolKit;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToIntFunction;

/**
 * Plans getting items the bot has no materials for, a round at a time, looking at the whole tree
 * at once. Asked for a diamond pickaxe with an empty storage it works out that diamonds need an
 * iron pickaxe, raw iron a stone one and cobblestone a wooden one, adds those tools to the goal,
 * and totals the raw materials for all of it - so the first log trip brings back the logs for every
 * plank and stick down the chain, not just the wooden pickaxe's.
 *
 * <p>Each call answers "what next?" against the current storage, and the bot calls again after
 * doing it: gather everything it can already mine, else craft the next tool that unlocks more,
 * else craft the targets. Re-planning every round keeps it honest when a trip comes back short.
 *
 * <p>Pure function of the recipe book and a {@link World} - no Minecraft classes.
 */
public final class ChainPlanner {

    /** The virtual recipe result the whole goal is planned as, so ingredients are shared across it. */
    static final String GOAL = "storage-manager:goal";

    /**
     * Re-plans allowed while totting up the shortfall. Each adds a tool tier or settles a changed
     * choice; a diamond tool from nothing takes about five.
     */
    private static final int MAX_PLAN_ROUNDS = 16;

    /** A tool that mining an item needs. A null {@code tool} means bare hands will do. */
    public record Requirement(ToolKit.Category tool, int minHarvest) {
        public static final Requirement BARE_HANDS = new Requirement(null, 0);
    }

    /** What the planner needs to know about the game. */
    public interface World {
        /** How many of the item storage holds. */
        int stock(String item);

        /** What mining the item takes, or null if no block drops it. */
        Requirement mineRequirement(String item);

        /** Whether the bot has - carried or in storage - a tool meeting this requirement right now. */
        boolean canMine(Requirement requirement);
    }

    public enum Action {
        /** Gather each of {@code items} - the full amount the rest of the chain needs. */
        GATHER,
        /** Craft {@code items}' single entry: a tool the chain needs, or a target once nothing is missing. */
        CRAFT,
        /** Nothing left to do. */
        DONE,
        /** Can't proceed - {@code problem} says why. */
        STUCK
    }

    /**
     * The next step, plus what it was planned from: every raw material the whole remaining chain is
     * short of, and the intermediate tools it'll make along the way.
     */
    public record Step(Action action, Map<String, Integer> items, String problem,
                       Map<String, Integer> missing, List<String> tools) {
    }

    private ChainPlanner() {
    }

    /**
     * Plans the next step towards making {@code targets} (item to count). Targets are always made,
     * never taken from storage - the caller handles "storage already has one" before asking.
     */
    public static Step next(RecipeBook book, Map<String, Integer> targets, World world) {
        if (targets.isEmpty()) {
            return new Step(Action.DONE, Map.of(), null, Map.of(), List.of());
        }
        World planning = new World() {
            @Override
            public int stock(String item) {
                return targets.containsKey(item) ? 0 : world.stock(item);
            }

            @Override
            public Requirement mineRequirement(String item) {
                return world.mineRequirement(item);
            }

            @Override
            public boolean canMine(Requirement requirement) {
                return world.canMine(requirement);
            }
        };

        // Plan against the storage the bot will have once it's gathered, not the storage it has now:
        // keep adding each round's shortfall to a hypothetical stock until nothing is missing. The
        // craft planner's choices depend on stock - with logs in storage it burns one for charcoal
        // instead of asking for coal - so a total planned from empty storage comes up short once the
        // first trip is back, and the bot would have to go out for the same material twice.
        List<String> tools = new ArrayList<>();
        Map<String, Integer> shortfall = new LinkedHashMap<>();
        CraftPlanner.Plan plan = null;
        for (int round = 0; ; round++) {
            if (round >= MAX_PLAN_ROUNDS) {
                return stuck("the plan never settled - still short of " + plan.missing(), shortfall, tools);
            }
            plan = planGoal(book, targets, tools, item -> planning.stock(item) + shortfall.getOrDefault(item, 0));
            if (plan.missing().isEmpty()) {
                break;
            }
            for (Map.Entry<String, Integer> missing : plan.missing().entrySet()) {
                String item = missing.getKey();
                Requirement requirement = world.mineRequirement(item);
                if (requirement == null) {
                    return stuck("nothing drops " + item + " and there's no recipe for it", shortfall, tools);
                }
                shortfall.merge(item, missing.getValue(), Integer::sum);
                if (requirement.tool() != null && !world.canMine(requirement)) {
                    String tool = ToolKit.cheapestTool(requirement.tool(), requirement.minHarvest());
                    if (!tools.contains(tool) && !targets.containsKey(tool)) {
                        tools.add(tool);
                    }
                }
            }
        }
        // Adding shortfalls only ever grows the totals, so a choice that got undone on the way - a log
        // earmarked for charcoal before coal turned up to burn instead - would still be counted. Trim
        // each back to the least that keeps the whole plan short of nothing.
        for (String item : new ArrayList<>(shortfall.keySet())) {
            while (shortfall.get(item) > 0) {
                shortfall.merge(item, -1, Integer::sum);
                if (!planGoal(book, targets, tools, stock -> planning.stock(stock) + shortfall.getOrDefault(stock, 0))
                        .missing().isEmpty()) {
                    shortfall.merge(item, 1, Integer::sum);
                    break;
                }
            }
        }
        shortfall.values().removeIf(count -> count <= 0);
        // Only the tools something still left to gather needs.
        tools.removeIf(tool -> shortfall.keySet().stream()
                .map(world::mineRequirement)
                .noneMatch(requirement -> requirement.tool() != null && !world.canMine(requirement)
                        && ToolKit.cheapestTool(requirement.tool(), requirement.minHarvest()).equals(tool)));
        tools.sort(Comparator.comparingInt(ToolKit::harvestLevel));

        Map<String, Integer> gatherNow = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> missing : shortfall.entrySet()) {
            Requirement requirement = world.mineRequirement(missing.getKey());
            if (requirement.tool() == null || world.canMine(requirement)) {
                gatherNow.put(missing.getKey(), missing.getValue());
            }
        }
        if (!gatherNow.isEmpty()) {
            return new Step(Action.GATHER, gatherNow, null, shortfall, tools);
        }
        if (!shortfall.isEmpty()) {
            for (String tool : tools) {
                if (CraftPlanner.plan(book, planning::stock, tool, 1).possible()) {
                    return new Step(Action.CRAFT, Map.of(tool, 1), null, shortfall, tools);
                }
            }
            return stuck("short of " + shortfall + " and can't make a tool to mine it with", shortfall, tools);
        }
        for (Map.Entry<String, Integer> target : targets.entrySet()) {
            if (CraftPlanner.plan(book, planning::stock, target.getKey(), target.getValue()).possible()) {
                return new Step(Action.CRAFT, Map.of(target.getKey(), target.getValue()), null, Map.of(), tools);
            }
        }
        return stuck("no recipe makes " + targets.keySet(), shortfall, tools);
    }

    /** Plans every target plus the intermediate tools as one virtual craft, so they share stock and spare. */
    private static CraftPlanner.Plan planGoal(RecipeBook book, Map<String, Integer> targets, List<String> tools,
                                              ToIntFunction<String> stock) {
        Recipe goal = new Recipe();
        goal.type = "craft";
        goal.result = GOAL;
        goal.shaped = false;
        goal.ingredients = new ArrayList<>();
        tools.forEach(goal.ingredients::add);
        targets.forEach((item, count) -> {
            for (int i = 0; i < count; i++) {
                goal.ingredients.add(item);
            }
        });
        return CraftPlanner.plan(book.withRecipe(goal), stock, GOAL, 1);
    }

    private static Step stuck(String problem, Map<String, Integer> shortfall, List<String> tools) {
        return new Step(Action.STUCK, Map.of(), problem, shortfall, tools);
    }
}
