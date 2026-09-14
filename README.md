# storage manager

A Fabric client mod that turns an alt account into an automated storage bot: it uses
[Baritone](https://github.com/cabaletta/baritone) to physically walk to chests and store/fetch
items, controlled through a local web UI.

Builds for **Minecraft 1.21.8** and **26.1.2** from one source tree, via
[Stonecutter](https://stonecutter.kikugie.dev/).

## Requirements

The full **Baritone** mod (not just the API) must be installed in `mods/` alongside this mod at
runtime - it's only a compile-time dependency here. Which build you need depends on the Minecraft
version, and they register under *different mod ids*, which is what this mod's dependency
declaration is matched against:

| Minecraft | Baritone build | Registers as | Java |
|---|---|---|---|
| 1.21.8 | [`baritone-standalone-fabric-1.15.0.jar`](https://github.com/cabaletta/baritone/releases/tag/v1.15.0) (covers 1.21.6-1.21.8) | `baritone` | 21 |
| 26.1.2 | Meteor Client's fork - upstream Baritone has no 26.1 release yet | `baritone-meteor` | 25 |

Note that Meteor's fork displays as "Baritone" in launchers like Prism, but its mod id is
`baritone-meteor`. The correct id is baked into each jar at build time from
`stonecutter.properties.toml`, so you don't need to do anything about it - just don't expect the
1.21.8 jar to load against it.

Fabric API is pulled in automatically.

## Building

```sh
./gradlew build            # every version -> versions/<mc>/build/libs/
./gradlew buildAndCollect  # every version -> build/libs/<mod version>/
./gradlew :26.1.2:build    # one version only
```

Jars are named `storage-manager-<mod version>+<mc version>.jar`.

## Virtual sorting tests

The client source also contains a pure in-memory sorter under
`storage.manager.client.simulation`. It models chest capacity, stack merging, input contents,
positions, chest visits, and walking distance without starting Minecraft or controlling Baritone.
`VirtualSorter` currently includes `consolidate`, `balanced`, `nearest`, `roundRobin`, and seeded
`random` strategies. Add another `SortStrategy` to compare a new routing idea against the same
virtual world. The test suite runs the same simulation for every Stonecutter Minecraft target:

```sh
./gradlew test
```

Use a fixed seed when comparing randomized strategies so results are reproducible. The result
reports stacks moved, chest visits, route distance, used slots, and wasted slots; the returned
world can also be inspected for its final per-chest contents.

The active version - the one the source tree is currently processed for, and what `runClient`
launches - is set by `stonecutter active "..."` in `stonecutter.gradle.kts`. Version-specific API
differences are handled by the replacement rules in that same file rather than by duplicating
source, so there is exactly one copy of every Java file.

For build environment setup, see the
[Fabric Documentation page](https://docs.fabricmc.net/develop/getting-started/creating-a-project#setting-up).

## Usage

Once the bot account is in the world, open `http://127.0.0.1:8642` (only reachable from the same
machine by default) and use the **Setup** panel to enter, by X/Y/Z coordinates copied from the
F3 debug screen:

- The storage region's min/max corners (the bot scans this box for chests/barrels/shulker boxes).
- An input chest (drop items here for the bot to sort into storage).
- An output chest (the bot delivers withdrawn items here for you to collect).

Then:

- **Rescan storage** visits every known chest and discovers newly placed ones.
- **Clear indexed chests** removes cached chest locations and contents after rebuilding the room;
  it keeps the configured region and input/output coordinates. Run a rescan afterwards.
- **Sort input chest** empties the input chest, grouping each item into a chest that already
  holds some.
- **Randomize storage** redistributes everything already in storage. The bot opens each chest
  once, in random order, unloading part of what it's carrying into that chest and picking up what
  was already there - so every stack moves exactly once and the pass costs one visit per chest
  rather than one per stack. No undo.
- **Stop** cancels the running job and everything queued behind it. Anything the bot is still
  carrying goes into the nearest chest with room, so a half-finished shuffle doesn't leave items
  riding around in its inventory.
- The **Inventory** table has a per-row quantity box and Withdraw button; the bot fetches the
  item to the output chest. `/chests` also lets you drag a specific stack onto the output chest
  to request that exact slot.
- With nothing queued, the bot strolls to a random chest every few minutes rather than standing
  still. Toggle it off in the Status panel.

The bot pauses itself below 3 hearts (resuming at 6) and on disconnect; reconnecting is left to
you or an external launcher.

## License

Available under the storage-manager license (custom permissive-NC, see LICENSE). Free to
use, modify, and share — just not sell.

## Build status

Built by Jenkins: `Minecraft/storage-manager-master` (master) and `Minecraft/storage-manager-tags` (refs/tags/v*). Webhook-driven since the 2026-08-25 split.

<!-- tested 2026-08-25T16:52:40Z -->
