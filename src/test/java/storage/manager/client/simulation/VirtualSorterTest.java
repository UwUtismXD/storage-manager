package storage.manager.client.simulation;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class VirtualSorterTest {
    private static VirtualStorageWorld sample() {
        VirtualStorageWorld.Chest a = new VirtualStorageWorld.Chest(
                "a", new VirtualStorageWorld.Position(4, 0, 0), 3);
        VirtualStorageWorld.Chest b = new VirtualStorageWorld.Chest(
                "b", new VirtualStorageWorld.Position(0, 0, 4), 3);
        // Put existing stock in the model by sorting it through a setup-only pass below.
        return new VirtualStorageWorld(new VirtualStorageWorld.Position(0, 0, 0), List.of(a, b), List.of(
                new VirtualStorageWorld.Stack("minecraft:stone", 32),
                new VirtualStorageWorld.Stack("minecraft:stone", 32),
                new VirtualStorageWorld.Stack("minecraft:dirt", 16)));
    }

    @Test
    void everyStrategyPreservesItemsAndIsRepeatable() {
        VirtualSorter sorter = new VirtualSorter();
        List<SortStrategy> strategies = List.of(VirtualSorter.consolidate(), VirtualSorter.balanced(),
                VirtualSorter.nearest(), VirtualSorter.roundRobin(), VirtualSorter.random());

        for (SortStrategy strategy : strategies) {
            VirtualSorter.Result first = sorter.run(sample(), strategy, 1234L);
            VirtualSorter.Result second = sorter.run(sample(), strategy, 1234L);
            assertEquals(Map.of("minecraft:stone", 64, "minecraft:dirt", 16), first.world().totals());
            assertEquals(first.usedSlots(), second.usedSlots(), strategy.name());
            assertEquals(first.chestVisits(), second.chestVisits(), strategy.name());
            assertEquals(first.walkingDistance(), second.walkingDistance(), 0.00001, strategy.name());
            assertEquals(3, first.stacksMoved());
        }
    }

    @Test
    void consolidateKeepsLikeItemsTogetherWhenCapacityAllows() {
        VirtualSorter.Result result = new VirtualSorter().run(sample(), VirtualSorter.consolidate(), 1L);

        assertEquals(64, result.world().chests().get(0).itemCount("minecraft:stone"));
        assertEquals(16, result.world().chests().get(1).itemCount("minecraft:dirt"));
        assertEquals(2, result.usedSlots());
    }

    @Test
    void randomUsesSeedSoAlgorithmComparisonsAreReproducible() {
        VirtualSorter sorter = new VirtualSorter();
        VirtualSorter.Result a = sorter.run(sample(), VirtualSorter.random(), 9L);
        VirtualSorter.Result b = sorter.run(sample(), VirtualSorter.random(), 9L);
        assertEquals(a.world().chests().stream().map(VirtualStorageWorld.Chest::stacks).toList(),
                b.world().chests().stream().map(VirtualStorageWorld.Chest::stacks).toList());
    }

    @Test
    void noDestinationFailsWithoutPartiallyMutatingOriginal() {
        VirtualStorageWorld.Chest full = new VirtualStorageWorld.Chest(
                "full", new VirtualStorageWorld.Position(1, 0, 0), 1)
                .withStack(new VirtualStorageWorld.Stack("minecraft:dirt", 64));
        VirtualStorageWorld world = new VirtualStorageWorld(new VirtualStorageWorld.Position(0, 0, 0),
                List.of(full), List.of(new VirtualStorageWorld.Stack("minecraft:stone", 64)));
        assertThrows(IllegalStateException.class,
                () -> new VirtualSorter().run(world, VirtualSorter.consolidate(), 1L));
        assertEquals(1, world.input().size());
    }
}
