package storage.manager.client.job;

import net.minecraft.core.BlockPos;

/**
 * Read-only view of what's at a position in the world, classified for the chunked-scan
 * dedup logic. {@link RegionScanner} takes one so the scan can run against a hand-rolled
 * fake in tests instead of needing a live Minecraft client.
 *
 * <p>Returns a {@link ScanResult} (kind + type id) rather than a raw {@code BlockState}
 * because constructing one outside a fully-bootstrapped MC environment isn't viable -
 * {@code Block.Properties.of()} pulls in {@code SoundType}/{@code SoundEvents}/{@code
 * BuiltInRegistries}, all of which demand bootstrap. The scanner only needs the kind and
 * the registry id, so collapsing to a small value object here keeps tests MC-free.
 */
interface BlockStateLookup {
    ScanResult scanAt(BlockPos pos);
}
