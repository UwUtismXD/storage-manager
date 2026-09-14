# Storage Manager overview

This document describes the production code in the Storage Manager Fabric client mod. Generated
files under `build/` and `versions/*/build/` are not part of the implementation and are omitted.
The unit tests under `src/test/` exercise the job factories/queue, region scanning, and setup JSON
validation.

## What the mod does

Storage Manager keeps a local JSON index of discovered Minecraft containers, exposes a small web
UI/API on port `8642`, and runs queued jobs on the client tick thread. Baritone is used only for
movement. Container opening, inventory transfer, and all Minecraft world access are performed by
the client-side code.

The normal flow is:

```text
Web UI/API -> JobQueue -> JobExecutor -> BotNavigator + ChestInteractor
                                      -> StorageIndex
                                      -> JSON autosave
```

HTTP handlers never manipulate Minecraft objects directly. They enqueue jobs or read synchronized
index/executor state; `JobExecutor.tick()` performs world actions from the client tick callback.

## Entry points and lifecycle

### `storage.manager.StorageManager`

File: `src/main/java/storage/manager/StorageManager.java`

- `onInitialize()` — Fabric’s common initializer. Registers the mod/logger entry point. The actual
  storage manager is client-only, so client setup is delegated to `StorageManagerClient`.

### `storage.manager.client.StorageManagerClient`

File: `src/client/java/storage/manager/client/StorageManagerClient.java`

- `onInitializeClient()` — creates and loads the `StorageIndex`, starts autosave, registers item
  texture reload handling, constructs the `JobQueue`, `JobExecutor`, and `WebServer`, and starts
  the web server on `127.0.0.1:8642`. It also registers the client tick, join/disconnect, and JVM
  shutdown hooks.
  - Every client tick processes queued texture requests.
  - No player means no job execution.
  - Health at or below 6.0 pauses execution; health at or above 12.0 resumes it.
  - Disconnect pauses jobs; joining resumes them.
  - Shutdown flushes the index and stops HTTP serving.

## Movement and container interaction

### `BotNavigator`

File: `src/client/java/storage/manager/client/baritone/BotNavigator.java`

This is the non-blocking Baritone adapter. Callers issue a goal and poll from ticks.

- `baritone()` — obtains Baritone’s primary instance.
- `goTo(BlockPos pos)` — starts pathing toward `pos`, accepting a two-block interaction radius.
- `isBusy()` — reports whether Baritone is currently pathing.
- `hasArrived(BlockPos target, double maxDistance)` — checks the player’s squared distance to a
  target. Returns `false` when there is no player.
- `cancel()` — cancels pathing and clears all forced key overrides. Clearing keys is important
  before a right-click, because a lingering sneak input can cause block placement instead of
  opening a container.

### `ChestInteractor`

File: `src/client/java/storage/manager/client/interact/ChestInteractor.java`

This class wraps vanilla client interaction and container-menu operations. It assumes the final 36
slots in an open menu belong to the player, and it never performs pathfinding.

- `client()` — returns the singleton Minecraft client.
- `open(BlockPos pos)` — sends a main-hand `useItemOn` interaction against the top face of a block.
  It does nothing without a player or game mode.
- `isOpen()` — checks whether the player has a container menu different from the inventory menu.
- `containerSlotCount()` — returns the number of non-player slots in the open menu, or zero when
  no container is open.
- `snapshotContainerSlots()` — returns non-empty container slots as `StorageIndex.SlotEntry`
  values, including item id/count, enchantments, custom name, and damage information.
- `describe(int slotIndex, ItemStack stack)` — converts an item stack into the web/index value
  object.
- `readEnchantments(ItemStack stack)` — reads normal and stored enchantment components and formats
  them as strings such as `sharpness 5`.
- `containerItemAt(int slotIndex)` — returns the item id at a container-side slot, or `null` for
  invalid/empty slots.
- `findContainerSlot(String itemId)` — finds the first container-side slot containing an item.
- `countFreePlayerSlots()` — counts empty player-side slots in the open menu.
- `findPlayerSlotWithItem(String itemId)` — finds a player-side slot containing an item.
- `firstNonEmptyPlayerSlot()` — finds the first occupied player-side slot.
- `itemAt(int slotIndex)` — returns the item id at any open-menu slot, or `null` if empty/invalid.
- `countPlayerStacks(String itemId)` — counts how many player-side slots contain an item.
- `hasCarriedItems()` — checks the player’s own inventory menu, including when no container is open.
- `countPlayerItems(String itemId)` — totals item counts across player-side slots. This is used
  before/after quick-moves to distinguish a full chest from remaining inventory stacks.
- `quickMove(int slotIndex)` — sends a vanilla `QUICK_MOVE`/shift-click operation for a slot.
- `close()` — closes the open container, if any.

## Jobs and execution

### `Job`

File: `src/client/java/storage/manager/client/job/Job.java`

`Job` is an immutable command object. Its `Type` values are `SCAN_REGION`, `SORT_INPUT`,
`WITHDRAW`, `RANDOMIZE`, `WITHDRAW_SLOT`, and `DUMP_INVENTORY`.

- `Job(...)` constructors — populate the type, item/count, and optional exact source chest/slot.
- `scanRegion()` — creates a region discovery job.
- `sortInput()` — creates a job that empties the configured input chest into storage.
- `withdraw(String itemId, int count)` — requests an item quantity from indexed storage.
- `randomize()` — creates a shuffle job that mixes stacks between storage chests.
- `dumpInventory()` — creates a cleanup job that unloads the bot’s carried items.
- `withdrawSlot(BlockPos chest, int slot, String itemId, int count)` — requests a particular
  visible stack from a chest view. The executor still verifies the slot and falls back to the item
  search if the index is stale.
- `toString()` — creates the status-panel description for each job type.

### `JobQueue`

File: `src/client/java/storage/manager/client/job/JobQueue.java`

Backed by `ConcurrentLinkedDeque`, so HTTP worker threads can enqueue while the client tick polls.

- `enqueue(Job job)` — appends a job.
- `poll()` — removes and returns the oldest job, or `null` when empty.
- `size()` — returns the current queue size.
- `clear()` — removes all waiting jobs; used by stop.
- `snapshot()` — returns an independent list for the status API.

### `JobExecutor`

File: `src/client/java/storage/manager/client/job/JobExecutor.java`

The executor is a client-tick state machine. Each job becomes a deque of `Visit` records. Each
visit moves through `PATHING`, `OPENING`, and `ACTING`, with timeouts for pathing/opening/action.
Actions are deliberately capped at nine quick-moves per tick.

#### Public/external methods

- `JobExecutor(JobQueue queue, StorageIndex index)` — wires the queue and persistent index.
- `pause()` — cancels movement/wandering and prevents further ticks from doing work.
- `resume()` — permits processing again.
- `isPaused()` — reports pause state.
- `requestStop()` — sets a volatile stop flag. Teardown waits for the next client tick because the
  request normally originates on an HTTP worker thread.
- `getStatus()` — returns `paused`, idle/queue status, or current job/phase/progress.
- `getLastWarning()` — returns the latest user-visible warning.
- `reservedChestPositions()` — returns the cached resolved input/output positions for the web UI.
- `tick()` — main state-machine entry point. Refreshes reserved chest halves, handles stop/pause,
  starts queued jobs, chunk-processes scans, advances visits, or dispatches the current phase.
- `isWanderEnabled()` / `setWanderEnabled(boolean)` — reads or changes idle wandering.

#### State, planning, and navigation helpers

- `progress()` — formats completed versus remaining visit count.
- `warn(String, Object...)` — logs a warning and formats it for the web UI.
- `handleStop()` — cancels navigation, closes containers, clears the queue/plan, and starts a
  dump-inventory plan if the player is carrying anything.
- `tickIdle()` — optionally wanders to a random known storage chest after a randomized idle delay.
- `stopWandering()` — cancels wandering and resets its timer.
- `buildPlan(Job)` — expands each job type into visits. Scans revisit discovered chests; sorting
  starts with the input chest; withdrawals pull from contributions then deposit to output;
  randomization visits storage chests once; dumping chooses a chest with room.
- `floorBands(List<BlockPos>)` — groups chest Y levels into floors.
- `floorAt(int, Map<Integer,Integer>)` — maps a Y coordinate to its nearest known floor.
- `travelCost(BlockPos, BlockPos, Map)` — estimates horizontal walking plus a heavy floor-change
  penalty.
- `nearestFirst(List<BlockPos>, BlockPos)` — greedy floor-aware nearest-neighbor ordering.
- `playerPos()` — returns the player position or origin when no player exists.
- `reservedChests()` — resolves configured input/output chests, including both halves of double
  chests, and publishes the result.
- `chestHalves(BlockPos)` — returns a configured chest position plus its connected half.
- `otherChestHalf(BlockPos)` — finds the connected half from live world chest state.
- `isContainerBlock(BlockPos)` — verifies chest, barrel, or shulker-box presence; conservatively
  returns true when no world is loaded.
- `freeSlots(ChestEntry)` — estimates free capacity, treating unopened chests as 27 slots.
- `chestsWithRoom(List<BlockPos>)` — filters indexed, non-reserved, non-tried chests with capacity.
- `nearestChestWithRoom(List<BlockPos>)` — chooses the closest eligible chest.
- `nearestSingle(List<BlockPos>, BlockPos)` — simple Euclidean nearest lookup.
- `advanceVisit()` — pops the next visit, starts movement or opening, and skips vanished containers.
- `openCurrentVisit()` — cancels Baritone, validates the target block, and issues the open action.
- `tickPathing()` — waits for arrival, retries failed path starts, and skips timed-out targets.
- `tickOpening()` — polls for the menu and retries opening every four ticks before timing out.
- `tickActing()` — dispatches the current visit to scan, withdraw, deposit, shuffle, dump, or
  input-reading behavior.

#### Job-specific action helpers

- `tickDepositItem()` — shift-clicks all stacks of one item into a chest, continuing over ticks and
  redirecting leftovers to another chest when the current one fills.
- `tickDepositBatch()` — deposits one stack each of several items, up to the per-tick move cap,
  then schedules leftovers elsewhere.
- `tickShuffleChest()` — initializes per-chest shuffle state, exchanges carried stacks with the
  chest, records the result, retries a chest if inventory capacity prevented a full read, and drains
  any final carried buffer.
- `shuffleRound()` — pulls original stacks and replaces them with randomized carried stacks while
  avoiding duplicate item types in one chest.
- `drainVisits(BlockPos)` — assigns leftover carried stacks to eligible chests, preferring one stack
  per item per chest and relaxing that rule only when necessary.
- `tickDumpAll()` — unloads the player inventory into the current chest and spills to another chest
  if it becomes full.
- `tickReadInput()` — reads the input chest, checks that destinations exist, pulls what fits, and
  appends one deposit visit per distinct item; queues another sort pass for remaining stacks.
- `carriedStacks()` — totals the shuffle buffer’s stack count.
- `dropCarried(String)` — removes one stack of an item from the shuffle buffer.
- `reconcileCarried()` — trims predicted carried state to stacks actually present in the inventory.
- `recordSnapshot(BlockPos)` — captures the open container’s current contents and updates the index.
- `finishVisit()` — closes the container and immediately advances.
- `nextVisitOrFinish()` — advances or finishes the job when no visits remain.
- `finishJob()` — clears transient state and flushes the index.

## Region scanning

### `BlockStateLookup`, `MinecraftBlockStateLookup`, `ScanKind`, and `ScanResult`

Files: `src/client/java/storage/manager/client/job/`

- `BlockStateLookup.scanAt(BlockPos)` — small testable seam returning a classified `ScanResult`.
- `MinecraftBlockStateLookup.scanAt(BlockPos)` — reads the live client world and classifies chests,
  barrels, and shulker boxes. It distinguishes the right half of a double chest and returns empty
  when no world is loaded.
- `ScanKind` — classifies non-container, left/single chest, right chest, barrel, and shulker box.
- `ScanResult.of(...)` — creates a classified result with a registry id.
- `ScanResult.ofKind(...)` — creates a test result with a synthetic id.
- `ScanResult.EMPTY` — represents a non-container/no-world result.

### `RegionScanner`

File: `src/client/java/storage/manager/client/job/RegionScanner.java`

- `RegionScanner(BlockPos a, BlockPos b, StorageIndex, BlockStateLookup)` — normalizes arbitrary
  cuboid corners and starts the cursor at the minimum corner.
- `isDone()` — reports whether every block has been visited.
- `scanChunk()` — scans up to 4096 blocks per call, registers supported containers, removes right
  double-chest halves, and pauses naturally between client ticks.
- `advanceCursor()` — walks Z first, then X, then Y, and reports when the cuboid is exhausted.

## Persistent storage index

### `StorageIndex`

File: `src/client/java/storage/manager/client/storage/StorageIndex.java`

All public index methods are synchronized because HTTP handlers read the index while the client
tick thread updates it. The default file is the Fabric config path
`storage-manager/storage-index.json`.

- `Pos()` / `Pos(BlockPos)` — JSON-friendly coordinate value and converter.
- `Pos.toBlockPos()` — converts stored coordinates back to Minecraft coordinates.
- `Pos.key()` — returns `x,y,z`, the chest map key.
- `Region.contains(BlockPos)` — checks whether a position is inside the configured cuboid.
- `SlotEntry(int, String, int)` — creates the serializable slot record used by UI/index snapshots.
- `Contribution(BlockPos, int, int)` — identifies a chest slot contributing to a withdrawal.
- `StorageIndex()` — selects the Fabric config file.
- `StorageIndex(Path)` — test-friendly constructor using an explicit file path.
- `load()` — reads JSON if present and restores setup/chest state; logs IO failures.
- `startAutoSave()` — starts a daemon thread that flushes every five seconds.
- `markDirty()` — marks in-memory changes for persistence.
- `flush()` — serializes a consistent snapshot and writes it if dirty. Failed writes remain dirty
  for a later retry.
- `writeAtomically(String)` — writes a sibling `.tmp` file and renames it into place, falling back
  when atomic moves are unsupported.
- `setRegion(...)`, `setInputChest(...)`, `setOutputChest(...)` — update setup coordinates and mark
  the index dirty.
- `getInputChest()`, `getOutputChest()` — return configured positions or `null`.
- `getInputChestPos()`, `getOutputChestPos()` — return JSON-friendly coordinate objects.
- `getRegion()` — returns the configured region.
- `upsertChest(...)` — replaces a chest’s type, capacity, contents, and scan timestamp.
- `removeChest(BlockPos)` — removes one stale chest record.
- `clearChests()` — removes cached chest/content records but retains setup coordinates.
- `registerEmptyChest(...)` — adds an unopened chest only when it is not already indexed.
- `allChests()` — returns a copy of all chest entries.
- `totalCounts(Collection<BlockPos>)` — aggregates item totals while excluding reserved chests.
- `findItem(String, int, Collection<BlockPos>)` — greedily selects whole indexed stacks for a
  withdrawal; the final contribution may exceed the requested count.
- `depositCandidates(String, Collection<BlockPos>)` — returns randomized chests already containing
  the item first, then other chests, excluding reserved positions.
- `randomStorageChests(Collection<BlockPos>)` — returns all non-reserved chests in random order.

## Item textures

### `ItemTextures`

File: `src/client/java/storage/manager/client/texture/ItemTextures.java`

The web thread requests textures, but the client tick thread performs resource-pack reads. Results
are cached, and missing textures are cached as an empty byte array.

- `registerReloadListener()` — clears the cache when client resources/resource packs reload.
- `get(String itemId, long timeoutMillis)` — returns cached PNG bytes or waits for the next tick
  to resolve the request; returns `null` for missing, failed, or timed-out lookups.
- `processPending()` — resolves at most 16 pending item ids per client tick.
- `load(String)` — reads an item definition/model/texture chain and returns PNG bytes.
- `resolveModelRef(ResourceManager, ResourceLocation)` — resolves `items/<id>.json` to a model,
  falling back to the legacy `item/<id>` model path.
- `findModelRef(JsonObject, int)` — recursively finds concrete model references in nested item
  definition shapes such as fallback, condition, select, and range-dispatch nodes.
- `resolveTextureRef(ResourceManager, String)` — walks model parents, collects texture aliases,
  prefers common texture keys, and ignores the `particle` fallback when possible.
- `dereference(String, Map<String,String>)` — follows `#alias` texture references with a depth cap.
- `readJson(ResourceManager, ResourceLocation)` — reads/parses a resource JSON object or returns
  `null` on absence/parse failure.

## Web server and API

### `WebServer`

File: `src/client/java/storage/manager/client/web/WebServer.java`

`WebServer` uses Java’s `HttpServer` with an eight-thread daemon worker pool. It binds to localhost
unless `start(..., true)` explicitly requests LAN access. The `ExecutorView` interface keeps HTTP
code testable without a live Minecraft client.

- `WebServer(...)` — stores the queue, index, executor view, and texture service.
- `start(int port, boolean lanAccessible)` — binds routes, installs the worker executor, and starts
  serving. Binding failures are logged.
- `stop()` — stops the server immediately when running.
- `handleIndex()` / `handleChests()` — serve the two packaged HTML pages.
- `serveStatic(...)` — serves only GET requests and returns 405/404 as appropriate.
- `handleInventory()` — GET endpoint returning aggregate storage totals and chest records.
- `handleStatus()` — GET endpoint returning executor status/warning/pause/wander state and queue.
- `handleWithdraw()` — POST endpoint validating `{item,count}` and optionally `{chest,slot}`, then
  enqueuing a normal or exact-slot withdrawal.
- `handleScan()` — POST endpoint enqueueing `SCAN_REGION`.
- `handleSort()` — POST endpoint enqueueing `SORT_INPUT`.
- `handleRandomize()` — POST endpoint enqueueing `RANDOMIZE`.
- `handleClearChests()` — POST endpoint clearing cached chest records.
- `handleStop()` — POST endpoint requesting tick-thread stop/cleanup.
- `handleWander()` — POST endpoint validating `{enabled}` and toggling idle wandering.
- `handleTexture()` — GET endpoint returning PNG bytes for `?item=<id>`, or 404 when unavailable.
- `queryParam(...)` — decodes a named raw query parameter.
- `handleSetup()` — GET returns configured region/chests plus resolved double-chest halves; POST
  validates and applies partial setup changes.
- `SetupResult.OK` / `SetupResult.fail(...)` — represent setup validation outcomes.
- `processSetup(JsonObject)` — validates all supplied top-level setup fields before applying any,
  so malformed partial requests leave the index unchanged.
- `readPos(...)` — validates one `{x,y,z}` object and returns a dotted error path on failure.
- `readPosPosition(...)` — converts a validated JSON position to `BlockPos`.
- `readPosPair(...)` — validates `{min,max}` position pairs.
- `readPosPairPositions(...)` — converts a validated pair to two `BlockPos` values.
- `readJson(...)` — parses bounded UTF-8 JSON request bodies and rejects bodies over 16 KiB.
- `webThreadFactory()` — creates named daemon HTTP threads.
- `BoundedInputStream.read()` / `read(byte[],int,int)` — enforce the request body byte limit.
- `sendJson(...)` — serializes a response payload and writes JSON headers/body.

## HTTP route summary

| Route | Method | Purpose |
|---|---:|---|
| `/` | GET | Main UI |
| `/chests` | GET | Chest browser UI |
| `/api/inventory` | GET | Totals and indexed chests |
| `/api/status` | GET | Executor/queue state |
| `/api/withdraw` | POST | Queue item or exact-slot withdrawal |
| `/api/scan` | POST | Queue region scan |
| `/api/sort` | POST | Queue input sorting |
| `/api/randomize` | POST | Queue storage shuffle |
| `/api/chests/clear` | POST | Clear indexed chest records |
| `/api/stop` | POST | Stop current/backlog work and recover carried items |
| `/api/wander` | POST | Toggle idle wandering with `{enabled}` |
| `/api/texture?item=...` | GET | Return an item PNG |
| `/api/setup` | GET/POST | Read or update region/input/output setup |

## Threading and safety rules

1. HTTP handlers may enqueue jobs and read synchronized snapshots, but must not touch Minecraft,
   Baritone, client world, or container menus.
2. `StorageManagerClient` calls `textures.processPending()` and `executor.tick()` from the client
   tick thread.
3. `StorageIndex` synchronizes its state; flushes serialize under the state lock and perform disk
   writes under a separate flush lock.
4. Job actions are bounded per tick and guarded by timeouts to avoid freezing the client or getting
   stuck on a missing/full/unopenable container.
5. Setup coordinates and reserved chest halves are excluded from normal storage operations so the
   input/output chests are not accidentally used as ordinary storage.

