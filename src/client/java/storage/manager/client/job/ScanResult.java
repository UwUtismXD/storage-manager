package storage.manager.client.job;

import net.minecraft.core.BlockPos;

/**
 * What the chunked region scanner found at a position. Pairs the classification
 * ({@link ScanKind}) with the registry-style type id (e.g. {@code "minecraft:chest"})
 * so the scanner can register a chest without the test seam needing
 * {@link net.minecraft.core.registries.BuiltInRegistries}.
 *
 * <p>{@code typeId} is non-null exactly when {@code kind} is a container kind;
 * {@link #EMPTY} is the one to return for air/non-containers.
 */
record ScanResult(ScanKind kind, String typeId) {

    /** Convenience: non-container result. */
    static final ScanResult EMPTY = new ScanResult(ScanKind.NONE, null);

    /** Factory so call sites stay readable. */
    static ScanResult of(ScanKind kind, String typeId) {
        return new ScanResult(kind, typeId);
    }

    /** Shorthand for tests that only need the kind - uses a synthetic type id. */
    static ScanResult ofKind(ScanKind kind) {
        return new ScanResult(kind, kind == ScanKind.NONE ? null : "test:" + kind.name().toLowerCase());
    }
}
