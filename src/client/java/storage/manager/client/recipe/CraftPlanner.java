package storage.manager.client.recipe;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToIntFunction;

/**
 * Works out everything a CRAFT job needs before the bot moves: for each ingredient, "is there
 * enough in storage? if not, can we make the rest?" - recursively, so asking for a diamond
 * pickaxe with no sticks in storage plans oak_log -> oak_planks -> stick -> diamond_pickaxe, and
 * asking for an iron pickaxe with only raw iron plans a furnace run for the ingots first.
 *
 * <p>Pure function of the recipe book and a storage-count lookup - no Minecraft classes - so it's
 * covered by plain unit tests the same way {@code VirtualSorter} is.
 */
public final class CraftPlanner {

    /**
     * One station session: run {@code recipe} {@code crafts} times - at a crafting table, or for a
     * smelt recipe at a furnace, burning {@code fuelCount} of {@code fuel}. Crafting steps have no
     * fuel ({@code null}, 0).
     */
    public record Step(String item, int crafts, Recipe recipe, String fuel, int fuelCount) {
        public Step(String item, int crafts, Recipe recipe) {
            this(item, crafts, recipe, null, 0);
        }

        public boolean smelt() {
            return recipe.isSmelt();
        }
    }

    /**
     * {@code steps} are in dependency order (ingredients before what they're used in, requested
     * item last). {@code withdrawals} is how much of each item to pull from storage up front,
     * fuel included. {@code missing} is what neither storage nor a known recipe could cover -
     * non-empty means the craft can't happen.
     */
    public record Plan(List<Step> steps, Map<String, Integer> withdrawals, Map<String, Integer> missing) {
        public boolean possible() {
            return missing.isEmpty() && !steps.isEmpty();
        }

        public boolean needsCraftingTable() {
            return steps.stream().anyMatch(step -> !step.smelt());
        }

        public boolean needsFurnace() {
            return steps.stream().anyMatch(Step::smelt);
        }
    }

    /** Reported as missing when a smelt needs fuel and the book doesn't list any. */
    static final String DEFAULT_FUEL = "minecraft:coal";

    private final RecipeBook book;
    private final ToIntFunction<String> storageCount;

    private final Map<String, Integer> stockLeft = new HashMap<>();
    /** Surplus from earlier steps, e.g. 2 spare sticks when 2 were needed but a craft makes 4. */
    private final Map<String, Integer> spare = new HashMap<>();
    private final List<Step> steps = new ArrayList<>();
    private final Map<String, Integer> withdrawals = new LinkedHashMap<>();
    private final Map<String, Integer> missing = new LinkedHashMap<>();
    private final Deque<String> inProgress = new ArrayDeque<>();

    private CraftPlanner(RecipeBook book, ToIntFunction<String> storageCount) {
        this.book = book;
        this.storageCount = storageCount;
    }

    /**
     * Plans making {@code count} of {@code itemId}. The requested item itself is always made,
     * never just withdrawn - that's what a CRAFT job asks for. Everything below it prefers storage
     * and only makes the shortfall.
     */
    public static Plan plan(RecipeBook book, ToIntFunction<String> storageCount, String itemId, int count) {
        CraftPlanner planner = new CraftPlanner(book, storageCount);
        planner.make(itemId, count);
        return new Plan(List.copyOf(planner.steps), planner.withdrawals, planner.missing);
    }

    private void need(String itemId, int amount) {
        int fromSpare = Math.min(amount, spare.getOrDefault(itemId, 0));
        if (fromSpare > 0) {
            spare.merge(itemId, -fromSpare, Integer::sum);
            amount -= fromSpare;
        }
        int stock = stock(itemId);
        int fromStock = Math.min(amount, stock);
        if (fromStock > 0) {
            stockLeft.put(itemId, stock - fromStock);
            withdrawals.merge(itemId, fromStock, Integer::sum);
            amount -= fromStock;
        }
        if (amount <= 0) {
            return;
        }
        // A recipe that (indirectly) needs its own result - e.g. iron_ingot <-> iron_block -
        // would recurse forever, so treat the item as a raw material we just don't have.
        if (inProgress.contains(itemId)) {
            missing.merge(itemId, amount, Integer::sum);
            return;
        }
        make(itemId, amount);
    }

    private int stock(String itemId) {
        return stockLeft.computeIfAbsent(itemId, storageCount::applyAsInt);
    }

    /**
     * Tries each recipe for the item in turn and keeps the first whose whole subtree can be
     * sourced - so iron ingots come out of a furnace when there's raw iron but no iron block to
     * uncraft. If none works, the closest attempt is kept so its shortfall is what gets reported.
     */
    private void make(String itemId, int amount) {
        List<Recipe> recipes = book.recipesFor(itemId);
        if (recipes.isEmpty()) {
            missing.merge(itemId, amount, Integer::sum);
            return;
        }
        Snapshot before = snapshot();
        Snapshot closest = null;
        for (Recipe recipe : recipes) {
            run(itemId, recipe, amount);
            if (missing.equals(before.missing)) {
                return;
            }
            closest = closer(snapshot(), closest);
            restore(before);
        }
        restore(closest);
    }

    private void run(String itemId, Recipe recipe, int amount) {
        int perRun = Math.max(1, recipe.resultCount);
        int runs = (amount + perRun - 1) / perRun;
        inProgress.push(itemId);
        for (Map.Entry<String, Integer> ingredient : recipe.ingredientCounts().entrySet()) {
            need(ingredient.getKey(), ingredient.getValue() * runs);
        }
        Step step = recipe.isSmelt() ? fuelled(itemId, runs, recipe) : new Step(itemId, runs, recipe);
        inProgress.pop();
        steps.add(step);
        int surplus = runs * perRun - amount;
        if (surplus > 0) {
            spare.merge(itemId, surplus, Integer::sum);
        }
    }

    /**
     * Picks the fuel for a smelt step: the first fuel storage already covers outright, then the
     * first one that can be made (charcoal from logs), and failing both, the closest attempt's
     * shortfall is reported. Fuel is rounded up to whole items, so a run of 5 still burns a full
     * coal.
     */
    private Step fuelled(String itemId, int runs, Recipe recipe) {
        List<Recipe> fuels = book.fuels();
        if (fuels.isEmpty()) {
            int coal = unitsFor(runs, 8);
            missing.merge(DEFAULT_FUEL, coal, Integer::sum);
            return new Step(itemId, runs, recipe, DEFAULT_FUEL, coal);
        }
        for (Recipe fuel : fuels) {
            int units = unitsFor(runs, fuel.smeltsPerItem);
            if (spare.getOrDefault(fuel.item, 0) + stock(fuel.item) >= units) {
                need(fuel.item, units);
                return new Step(itemId, runs, recipe, fuel.item, units);
            }
        }
        Snapshot before = snapshot();
        Snapshot closest = null;
        Recipe closestFuel = null;
        for (Recipe fuel : fuels) {
            int units = unitsFor(runs, fuel.smeltsPerItem);
            need(fuel.item, units);
            if (missing.equals(before.missing)) {
                return new Step(itemId, runs, recipe, fuel.item, units);
            }
            Snapshot attempt = closer(snapshot(), closest);
            if (attempt != closest) {
                closest = attempt;
                closestFuel = fuel;
            }
            restore(before);
        }
        restore(closest);
        return new Step(itemId, runs, recipe, closestFuel.item, unitsFor(runs, closestFuel.smeltsPerItem));
    }

    private static int unitsFor(int runs, int smeltsPerItem) {
        return (runs + smeltsPerItem - 1) / smeltsPerItem;
    }

    /**
     * Planner state at one moment - taken before a trial so a recipe that turns out unsourceable
     * leaves no trace, and after one so the closest failure can be put back for reporting.
     */
    private record Snapshot(Map<String, Integer> stockLeft, Map<String, Integer> spare,
                            Map<String, Integer> withdrawals, Map<String, Integer> missing, List<Step> steps) {
    }

    private Snapshot snapshot() {
        return new Snapshot(new HashMap<>(stockLeft), new HashMap<>(spare),
                new LinkedHashMap<>(withdrawals), new LinkedHashMap<>(missing), List.copyOf(steps));
    }

    private void restore(Snapshot snapshot) {
        replace(stockLeft, snapshot.stockLeft);
        replace(spare, snapshot.spare);
        replace(withdrawals, snapshot.withdrawals);
        replace(missing, snapshot.missing);
        steps.clear();
        steps.addAll(snapshot.steps);
    }

    /**
     * The failed attempt worth reporting: the one short the fewest items, and on a tie the one
     * storage covered more of. With 3 raw iron and no coal that's "missing 1 coal" from the smelt,
     * not "missing 1 iron block" from the craft that never got anywhere.
     */
    private static Snapshot closer(Snapshot attempt, Snapshot best) {
        if (best == null) {
            return attempt;
        }
        int attemptMissing = total(attempt.missing);
        int bestMissing = total(best.missing);
        if (attemptMissing != bestMissing) {
            return attemptMissing < bestMissing ? attempt : best;
        }
        return total(attempt.withdrawals) > total(best.withdrawals) ? attempt : best;
    }

    private static int total(Map<String, Integer> counts) {
        int sum = 0;
        for (int count : counts.values()) {
            sum += count;
        }
        return sum;
    }

    private static void replace(Map<String, Integer> target, Map<String, Integer> source) {
        target.clear();
        target.putAll(source);
    }
}
