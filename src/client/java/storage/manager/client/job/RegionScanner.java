package storage.manager.client.job;

import net.minecraft.core.BlockPos;

import storage.manager.client.storage.StorageIndex;

/**
 * Walks a configured cuboid in chunks, registering every container block into a
 * {@link StorageIndex} as it goes. Each {@link #scanChunk()} does bounded work so the client
 * tick thread can spread a large region across many ticks instead of freezing on it.
 *
 * <p>{@link #BLOCKS_PER_TICK} is the per-tick budget - chosen so a 64x64x64 region finishes in
 * ~64 ticks (~3.2s) while never blocking the tick thread long enough to be felt as a freeze.
 */
final class RegionScanner {

    /** Block reads per tick. See class doc for rationale. */
    static final int BLOCKS_PER_TICK = 4096;

    private final BlockPos min;
    private final BlockPos max;
    private final StorageIndex index;
    private final BlockStateLookup lookup;
    private BlockPos cursor;
    private boolean done;

    /**
     * Builds a scanner for the cuboid between {@code a} and {@code b}. The two corners are
     * normalized internally so passing them in either order walks the same volume - the user
     * picked them in the setup form by clicking two points, with no guarantee about order.
     */
    RegionScanner(BlockPos a, BlockPos b, StorageIndex index, BlockStateLookup lookup) {
        this.min = new BlockPos(
                Math.min(a.getX(), b.getX()),
                Math.min(a.getY(), b.getY()),
                Math.min(a.getZ(), b.getZ())).immutable();
        this.max = new BlockPos(
                Math.max(a.getX(), b.getX()),
                Math.max(a.getY(), b.getY()),
                Math.max(a.getZ(), b.getZ())).immutable();
        this.index = index;
        this.lookup = lookup;
        this.cursor = this.min;
    }

    /** True once every block in the cuboid has been visited. */
    boolean isDone() {
        return done;
    }

    /**
     * Scans up to {@link #BLOCKS_PER_TICK} blocks, then returns. Subsequent calls continue from
     * where this one stopped; calls after {@link #isDone()} are no-ops.
     */
    void scanChunk() {
        if (done) {
            return;
        }
        int budget = BLOCKS_PER_TICK;
        while (budget-- > 0) {
            switch (lookup.scanAt(cursor).kind()) {
                case CHEST_RIGHT -> {
                    // The other half of a double chest - opening either half opens the same combined
                    // inventory, so only the LEFT/SINGLE half is registered to avoid visiting it twice.
                    // Also purges any stale duplicate entry left over from before this check existed.
                    index.removeChest(cursor);
                }
                case CHEST_SINGLE_OR_LEFT, BARREL, SHULKER_BOX ->
                    index.registerEmptyChest(cursor.immutable(), lookup.scanAt(cursor).typeId());
                case NONE -> { /* nothing to index */ }
            }
            if (!advanceCursor()) {
                done = true;
                return;
            }
        }
    }

    /**
     * Advances {@link #cursor} in Z, then X, then Y - same axis order {@code BlockPos.betweenClosed}
     * uses. Returns false once the cursor has walked past the top-far corner and the scan is done.
     */
    private boolean advanceCursor() {
        int x = cursor.getX();
        int y = cursor.getY();
        int z = cursor.getZ();
        if (z < max.getZ()) {
            cursor = new BlockPos(x, y, z + 1);
            return true;
        }
        if (x < max.getX()) {
            cursor = new BlockPos(x + 1, y, min.getZ());
            return true;
        }
        if (y < max.getY()) {
            cursor = new BlockPos(min.getX(), y + 1, min.getZ());
            return true;
        }
        return false;
    }
}