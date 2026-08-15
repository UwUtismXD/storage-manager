package storage.manager.client.job;

/**
 * What the chunked region scanner can find at a block position, after classification.
 * Lives between {@link BlockStateLookup} and {@link RegionScanner} so the scanner can
 * pick a register/remove action without touching {@code BlockState} directly - that keeps
 * unit tests MC-free.
 */
enum ScanKind {
    /** Not a container - skip. */
    NONE,
    /** Single chest, or the LEFT half of a double chest - register it. */
    CHEST_SINGLE_OR_LEFT,
    /** RIGHT half of a double chest - remove any stale duplicate and skip. */
    CHEST_RIGHT,
    BARREL,
    SHULKER_BOX
}
