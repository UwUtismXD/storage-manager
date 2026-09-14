package storage.manager.client.simulation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/** Executes a sorting pass entirely in memory and returns measurable results. */
public final class VirtualSorter {
    @FunctionalInterface
    private interface Choice {
        String choose(List<VirtualStorageWorld.Chest> chests, VirtualStorageWorld.Stack stack,
                      int stackIndex, VirtualStorageWorld.Position from, Random random);
    }
    public record Result(String strategy, int stacksMoved, int chestVisits, double walkingDistance,
                         int usedSlots, int wastedSlots, VirtualStorageWorld world) {}

    public Result run(VirtualStorageWorld original, SortStrategy strategy, long seed) {
        VirtualStorageWorld world = original.copy();
        Random random = new Random(seed);
        List<VirtualStorageWorld.Stack> incoming = world.input();
        world.clearInput();
        List<String> visited = new ArrayList<>();
        VirtualStorageWorld.Position current = world.inputPosition();
        int moved = 0;

        for (int i = 0; i < incoming.size(); i++) {
            VirtualStorageWorld.Stack stack = incoming.get(i);
            String destination = strategy.choose(world.chests(), stack, i, current, random);
            if (destination == null) throw new IllegalStateException("No chest can accept " + stack.item());
            VirtualStorageWorld.Chest chest = world.chests().stream()
                    .filter(c -> c.id().equals(destination)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Strategy selected unknown chest: " + destination));
            if (!chest.accepts(stack)) throw new IllegalStateException("Strategy selected a full chest: " + destination);
            world.place(destination, stack);
            if (!destination.equals(visited.isEmpty() ? null : visited.get(visited.size() - 1))) {
                visited.add(destination);
                current = chest.position();
            }
            moved++;
        }

        double distance = 0;
        current = world.inputPosition();
        for (String id : visited) {
            VirtualStorageWorld.Chest chest = world.chest(id);
            distance += current.distanceTo(chest.position());
            current = chest.position();
        }
        distance += current.distanceTo(world.inputPosition());
        int used = world.chests().stream().mapToInt(VirtualStorageWorld.Chest::usedSlots).sum();
        int capacity = world.chests().stream().mapToInt(VirtualStorageWorld.Chest::capacity).sum();
        return new Result(strategy.name(), moved, visited.size(), distance, used, capacity - used, world);
    }

    public static SortStrategy consolidate() {
        return named("consolidate", (chests, stack, ignored, from, random) -> firstAccepting(chests, stack,
                Comparator.<VirtualStorageWorld.Chest>comparingInt(c -> c.itemCount(stack.item()) > 0 ? 0 : 1)
                        .thenComparingInt(VirtualStorageWorld.Chest::usedSlots)));
    }

    public static SortStrategy balanced() {
        return named("balanced", (chests, stack, ignored, from, random) -> firstAccepting(chests, stack,
                Comparator.comparingInt(VirtualStorageWorld.Chest::usedSlots)));
    }

    public static SortStrategy nearest() {
        return named("nearest", (chests, stack, ignored, from, random) -> firstAccepting(chests, stack,
                Comparator.comparingDouble(c -> c.position().distanceTo(from))));
    }

    public static SortStrategy roundRobin() {
        return named("round-robin", (chests, stack, index, from, random) -> {
            for (int offset = 0; offset < chests.size(); offset++) {
                VirtualStorageWorld.Chest chest = chests.get((index + offset) % chests.size());
                if (chest.accepts(stack)) return chest.id();
            }
            return null;
        });
    }

    public static SortStrategy random() {
        return named("random", (chests, stack, ignored, from, random) -> {
            List<VirtualStorageWorld.Chest> available = chests.stream().filter(c -> c.accepts(stack)).toList();
            return available.isEmpty() ? null : available.get(random.nextInt(available.size())).id();
        });
    }

    private static String firstAccepting(List<VirtualStorageWorld.Chest> chests, VirtualStorageWorld.Stack stack,
                                         Comparator<VirtualStorageWorld.Chest> order) {
        return chests.stream().filter(c -> c.accepts(stack)).sorted(order).map(VirtualStorageWorld.Chest::id)
                .findFirst().orElse(null);
    }

    private static SortStrategy named(String name, Choice chooser) {
        return new SortStrategy() {
            @Override public String name() { return name; }
            @Override public String choose(List<VirtualStorageWorld.Chest> chests, VirtualStorageWorld.Stack stack,
                                           int stackIndex, VirtualStorageWorld.Position from, Random random) {
                return chooser.choose(chests, stack, stackIndex, from, random);
            }
        };
    }
}
