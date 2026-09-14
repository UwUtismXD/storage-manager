package storage.manager.client.simulation;

import java.util.List;
import java.util.Random;

/** A pluggable destination-selection algorithm for the virtual sorter. */
public interface SortStrategy {
    String name();

    /** Selects a destination from the current, already-updated virtual state. */
    String choose(List<VirtualStorageWorld.Chest> chests, VirtualStorageWorld.Stack stack,
                  int stackIndex, VirtualStorageWorld.Position from, Random random);
}
