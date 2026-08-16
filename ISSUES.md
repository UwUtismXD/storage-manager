# Code review — storage-manager

Reviewer pass over `src/main/java/`, `src/client/java/`, the tests under
`src/test/java/`, and the docs. Findings ordered by impact. Items marked
**[fixed in this branch]** have follow-up commits on the
`fix/review-pass-2026-08-16` branch; the rest are observations for triage.

For context: all issues in the tracker (#1–#8) are closed; the work below
is post-#8, against the cleanup commit `7e4f208`.

## Bugs

### 1. `JobExecutor.warn()` only substitutes the first `{}`  **[fixed]**

```java
formatted = formatted.replaceFirst("\\{}", java.util.regex.Matcher.quoteReplacement(String.valueOf(arg)));
```

`replaceFirst` substitutes a single occurrence per call, then the loop
runs once per arg — but the second iteration finds the *same* first
placeholder and overwrites the substituted value rather than advancing.
Result: every call with two or more `{}`s leaves the trailing ones as
literal `{}` in `lastWarning`, which is what the web UI surfaces verbatim.

Currently one call site is affected:

```
warn("Could not fully deposit {} at {}, output chest may be full",
        currentVisit.item(), currentVisit.pos());
```

The user-visible status would read something like
`Could not fully deposit minecraft:diamond at {}, output chest may be full`.

Fix: use `String.replace(CharSequence, CharSequence)` (literal, all
occurrences) instead of `replaceFirst`.

### 2. WebServer uses an unbounded thread pool  **[fixed]**

```java
server.setExecutor(Executors.newCachedThreadPool());
```

`newCachedThreadPool` spawns a new thread for every concurrent request
with no upper bound. A slow client (or a `slowloris`-style attack from
another local user) can grow the pool arbitrarily large; on a JVM with
a default ~256KB stack per thread the bot OOMs well before reaching
hundreds of threads.

Fix: switch to a small fixed-size pool with named daemon threads. Eight
threads is far more than the local control UI ever produces in practice,
and the unbounded queue keeps dispatch non-rejecting under brief
saturation.

### 3. WebServer reads request bodies without a size limit  **[fixed]**

`readJson(HttpExchange)` wraps `exchange.getRequestBody()` directly and
hands it to Gson. Gson reads until EOF. A POST with an unbounded
`Content-Length` (or chunked encoding with no cap) makes the JVM
allocate the whole body — the same DoS class as #2, through the memory
channel rather than the thread channel.

The endpoints that read bodies (`/api/withdraw`, `/api/wander`,
`/api/setup`) all expect tiny JSON payloads — single-digit bytes in
practice. 16 KiB is comfortably above the largest legitimate request and
several orders of magnitude below anything that would actually stress
the heap.

Fix: reject upfront on `Content-Length > MAX_REQUEST_BYTES`, and wrap
the body in a bounded stream that throws if the actual read exceeds it.

### 4. `/api/setup` accepts arbitrary `BlockPos` values with no auth  **[not fixed]**

`handleSetup`'s POST branch reads `{region, inputChest, outputChest}`
and calls `index.setRegion(min, max)` / `setInputChest(pos)` /
`setOutputChest(pos)` with whatever the request supplies. There is no
range validation and no authentication.

A request like `{"region":{"min":{"x":-2147483648,"y":-2147483648,"z":-2147483648},"max":{"x":2147483647,"y":2147483647,"z":2147483647}}}`
sets the cuboid to roughly the entire world coordinate space. The next
`/api/scan` triggers `RegionScanner` against that, which would index
billions of blocks — the chunked scan finishes without freezing the tick
thread, but every other "is this chest known?" lookup now walks a
multi-million-entry `LinkedHashMap`, and the index file balloons.

Worth a follow-up: clamp coordinates to a sane range (say ±30,000 from
origin — Minecraft's buildable world boundary) and either reject extreme
values or surface a confirmation in the UI.

### 5. No rate limiting on any endpoint

Every handler responds immediately to every request. Combined with #2
and #3 the JVM is well-armored, but a misbehaving tab that re-fires
`/api/scan` in a loop will keep the bot walking forever and racking up
log spam. A token bucket per remote IP would be the minimum; ideally the
expensive endpoints (`/api/scan`, `/api/randomize`, `/api/sort`)
should reject while a job is already queued for the same operation.

## Design notes

### 6. `JobExecutor` state machine has zero tests

`JobTest` covers the `Job` factory outputs and `toString` formatting,
`JobQueueTest` covers the queue (FIFO, thread safety, snapshot
independence), and `RegionScannerTest` covers the scanner geometry. The
tricky part — `JobExecutor.tickDepositItem`, `tickDepositBatch`,
`tickShuffleChest`, `tickDumpAll`, `tickReadInput` — is fully untested.

The recent #5/#2/#4 fixes were all landed in this untested zone. The
comments are good, but unit tests would catch the next bug here much
faster than a player reporting "the bot put my diamonds in the wrong
chest." Big scope, but worth its own follow-up.

### 7. `StorageIndex.randomStorageChests(Collection<BlockPos>)` is mis-named

The method returns *every* non-excluded chest, not just "storage"
chests. The input/output chests are excluded via the parameter, but the
name implies a category filter. A `chestsWithRoom` style filter — "only
chests that haven't been flagged as reserved by setup" — would be more
honest; alternatively rename to `shufflableChests` or
`allExcept(BlockPos...)`.

Minor, but the current name misleads a reader into thinking the method
filters out input/output chests internally when it actually relies on
the caller.

### 8. `ChestInteractor.open()` doesn't close a stale container

If the previous visit was interrupted before `interactor.close()` (a
crash, a tick-thread exception, an explicit `handleStop` racing a
visit), the next `open()` call will fire a `useItemOn` while a container
is already open. Minecraft usually sorts this out (the existing menu is
replaced) but the interactor's `isOpen()` snapshot doesn't know, and
the `findContainerSlot` / `quickMove` calls operate on whatever menu
happens to be open at that instant.

Cheap fix: have `open()` call `close()` first if `isOpen()` returns
true. No semantics change for the common path, but it makes the
invocation safe across interruptions.

### 9. StorageIndex JSON file has no growth cap

For a region with thousands of chests (large storage rooms), the
serialized `Data` object grows linearly with chest count and never
shrinks — entries removed via `removeChest` are dropped from the live
`chests` map but the file is always rewritten whole. A `clearChests()`
followed by no further activity still leaves a tiny file. The risk is
not bloat per se, just that every flush serializes the whole index on
the tick thread (with the lock held).

Already mitigated by the debounced save (every 5s) and atomic write, so
not urgent. Worth noting in case someone wants to switch to a
write-through cache later.

### 10. No schema migration / version field in `Data`

`StorageIndex.load()` calls `GSON.fromJson(reader, Data.class)` with no
version field in `Data`. A future schema change either silently leaves
old fields at defaults (Gson's default behavior) or fails to load the
file outright. A `"version": 1` field in `Data` would make migrations
explicit and would let the loader warn instead of silently dropping
data.

## Minor

- `StorageIndex.writeAtomically` writes to a sibling `.tmp` path. Single
  flusher thread today, so no race — but the path is derived from the
  filename rather than the directory, which means two indices in the
  same dir would collide. Probably never relevant.
- `BotNavigator` calls `BaritoneAPI.getProvider().getPrimaryBaritone()`
  with no null check. The README already requires Baritone, so this is
  fine; just noting.
- `ItemTextures` `cache.clear()` on reload leaves any in-flight
  `CompletableFuture` in `pending` to resolve against the new pack. The
  comment acknowledges this; reasonable trade-off.
- `JobExecutor.warn()`'s `formatted` string uses `String.valueOf(arg)`,
  which for a `BlockPos` produces `BlockPos{x=..., y=..., z=...}`. The
  status panel would read better with just the coordinates, but that's
  a cosmetic call.

## Things deliberately not flagged

- The double-checked open in `tickOpening` (retries every 4 ticks for
  up to 5s) — already handled correctly, the comment explains why.
- The `MOVES_PER_TICK` cap on the multi-tick deposits — already done in
  PR #5 / commit `5b2e6b1`.
- The chunked region scanner — PR #8 / commit `12ace33`, this is the
  production behaviour the review ran against.
- The `flushLock` + atomic-rename write strategy — sound, no concerns.
- `JobQueue` thread safety — covered by `concurrentEnqueuePollDoesNotLoseJobs`.