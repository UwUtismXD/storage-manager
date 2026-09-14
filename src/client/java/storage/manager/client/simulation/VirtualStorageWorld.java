package storage.manager.client.simulation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small in-memory model of the part of a Minecraft storage room that sorting needs.
 * It deliberately has no Fabric, Minecraft, Baritone, or client-thread dependency.
 */
public final class VirtualStorageWorld {
    public record Position(int x, int y, int z) {
        public double distanceTo(Position other) {
            long dx = (long) x - other.x;
            long dy = (long) y - other.y;
            long dz = (long) z - other.z;
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        }
    }

    public record Stack(String item, int count) {
        public Stack {
            if (item == null || item.isBlank() || count <= 0 || count > 64) {
                throw new IllegalArgumentException("A stack must contain 1..64 items with an id");
            }
        }
    }

    public static final class Chest {
        private final String id;
        private final Position position;
        private final int capacity;
        private final List<Stack> stacks = new ArrayList<>();

        public Chest(String id, Position position, int capacity) {
            if (id == null || position == null || capacity <= 0) {
                throw new IllegalArgumentException("Chest id, position, and capacity are required");
            }
            this.id = id;
            this.position = position;
            this.capacity = capacity;
        }

        public String id() { return id; }
        public Position position() { return position; }
        public int capacity() { return capacity; }
        public List<Stack> stacks() { return List.copyOf(stacks); }
        public int usedSlots() { return stacks.size(); }
        public int freeSlots() { return capacity - stacks.size(); }
        public int itemCount(String item) {
            return stacks.stream().filter(s -> s.item().equals(item)).mapToInt(Stack::count).sum();
        }

        /** Adds stock that existed before the simulated pass began. */
        public Chest withStack(Stack stack) {
            add(stack);
            return this;
        }

        boolean accepts(Stack stack) {
            return stacks.stream().anyMatch(s -> s.item().equals(stack.item()) && s.count() + stack.count() <= 64)
                    || freeSlots() > 0;
        }

        private void add(Stack stack) {
            for (int i = 0; i < stacks.size(); i++) {
                Stack existing = stacks.get(i);
                if (existing.item().equals(stack.item()) && existing.count() + stack.count() <= 64) {
                    stacks.set(i, new Stack(existing.item(), existing.count() + stack.count()));
                    return;
                }
            }
            if (freeSlots() == 0) throw new IllegalStateException("Chest is full: " + id);
            stacks.add(stack);
        }

        private Chest copy() {
            Chest copy = new Chest(id, position, capacity);
            copy.stacks.addAll(stacks);
            return copy;
        }
    }

    private final Position inputPosition;
    private final List<Chest> chests;
    private final List<Stack> input = new ArrayList<>();

    public VirtualStorageWorld(Position inputPosition, List<Chest> chests, List<Stack> input) {
        if (inputPosition == null || chests == null || chests.isEmpty() || input == null) {
            throw new IllegalArgumentException("An input position, storage chests, and input are required");
        }
        this.inputPosition = inputPosition;
        this.chests = chests.stream().map(Chest::copy).collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        this.input.addAll(input);
    }

    public Position inputPosition() { return inputPosition; }
    public List<Chest> chests() { return List.copyOf(chests); }
    public List<Stack> input() { return List.copyOf(input); }

    Chest chest(String id) {
        return chests.stream().filter(c -> c.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown chest: " + id));
    }

    void place(String id, Stack stack) { chest(id).add(stack); }

    void clearInput() { input.clear(); }

    public Map<String, Integer> totals() {
        Map<String, Integer> totals = new LinkedHashMap<>();
        for (Chest chest : chests) {
            for (Stack stack : chest.stacks) totals.merge(stack.item(), stack.count(), Integer::sum);
        }
        for (Stack stack : input) totals.merge(stack.item(), stack.count(), Integer::sum);
        return Map.copyOf(totals);
    }

    VirtualStorageWorld copy() {
        return new VirtualStorageWorld(inputPosition, chests, input);
    }
}
