# storage manager

A Fabric client mod that turns an alt account into an automated storage bot: it uses
[Baritone](https://github.com/cabaletta/baritone) to physically walk to chests and store/fetch
items, controlled through a local web UI or, if you run Meteor Client, from inside the game.

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
- Optionally, a crafting table and a furnace for the **Craft** panel.

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
- **Craft** makes an item from what's in storage, crafting or smelting any missing ingredients
  first (raw iron → iron ingots → iron pickaxe). Recipes come from the bundled `recipes.json`;
  smelting burns coal, charcoal or coal blocks from storage, and makes charcoal from logs if it has
  to. The bot stays at the furnace until the batch is done (about 10s an item) and puts the result
  and any leftovers back into storage. If storage is short of something, the bot gathers it first,
  along with any tools that takes (see the tool chain below), then crafts.
- **Gather** mines any item a block drops with Baritone until that many have been picked up:
  `minecraft:oak_log`, `minecraft:raw_iron`, string from cobwebs, apples and saplings from leaves,
  seeds from grass, flint from gravel. Naming an ore like `minecraft:iron_ore` gathers what it
  drops. Whenever the inventory fills, the bot walks back and puts everything away, then heads out
  again. The `"mine"` entries in `recipes.json` come first. Anything they don't cover is looked up
  in the game's own block loot tables, which ship inside the client jar. Drops that need shears or
  silk touch are ignored, and blocks that always drop the item beat ones that only sometimes do.
  The tool a block needs comes from its tags. Mob drops (gunpowder, leather) can't be gathered. Gathering stops if Baritone runs out of blocks to find, picks nothing up for three
  minutes, or starts breaking a block in or right around the storage region or next to a
  configured chest, table or furnace.
- **Tools**: before gathering, the bot equips a suitable pickaxe, axe, shovel or hoe. It keeps one
  it already carries if that's good enough. Otherwise it takes the best one from storage, or
  crafts one (stone or iron rather than diamond) if storage has none. A tool that breaks gets
  replaced, up to five times per job. Equipped tools are never deposited, dumped or delivered:
  Stop, sorting and every other job leave them with the bot. When the bot carries two of the same
  tool, it keeps the more worn one. **Equip best tools** fetches the best pickaxe, axe and shovel
  up front, and **Put tools away** unequips everything and returns it to storage. The equipped set
  is saved with the index.
- **Kit** is a debug button that gets the bot a diamond pickaxe, axe, shovel, sword and hoe by
  whatever means it takes. Tools already in storage are withdrawn. The rest are crafted, and the
  whole tool chain is planned up front: the bot works out which tools it needs along the way
  (wooden pickaxe → stone → iron, to mine cobblestone, raw iron and diamonds). It totals the
  materials for everything, down to every stick for every tool and the coal to smelt the iron.
  Each material is gathered in one trip for the whole amount, as soon as the bot has a tool that
  can mine it. The order goes: all the logs, craft the wooden pickaxe, all the cobblestone and
  coal, craft the stone pickaxe, and so on. The plan is redone after every trip, so one that comes
  back short just goes round again. The crafting table and furnace must be set in Setup, and
  whatever the new tools replace is put away at the end.
- **Craft** and **Gather** use the same chain when they're short of materials or need a tool the
  bot can't get, and so does replacing a tool that broke mid-gather.
- With nothing queued, the bot strolls to a random chest every few minutes rather than standing
  still. Toggle it off in the Status panel.

The bot pauses itself below 3 hearts (resuming at 6) and on disconnect; reconnecting is left to
you or an external launcher.

### In-game panel (Meteor Client)

With [Meteor Client](https://meteorclient.com) installed, the mod also registers as a Meteor
addon: a **Storage Manager** category holding a module of the same name. Toggling that module, or
pressing the keybind you give it, opens a panel with buttons for the jobs listed above plus the
bot's current status; the same controls appear inline when the module is expanded in the ClickGUI.

Setup there is done by clicking blocks instead of typing coordinates. Click **Input chest**,
**Output chest**, **Crafting table** or **Furnace** and the panel closes; right-click the block you mean and it
reopens with the position filled in. **Region** asks for two right-clicks, one on each opposite
corner. The click that picks a block is swallowed, so picking a chest doesn't also open it.
**Cancel pick** abandons a pick you've changed your mind about.

Tick the **render** setting to outline everything configured in the world: the input chest in
green, the output chest in orange, the crafting table in blue, the furnace in purple and the storage region in
yellow.
The **item** and **count** settings feed the Withdraw, Craft and Gather buttons.

Meteor is optional: it's a compile-time dependency only, the mod loads and behaves exactly as
before without it, and the web UI stays available either way (the panel has a button to open it).

## License

Available under the storage-manager license (custom permissive-NC, see LICENSE). Free to
use, modify, and share — just not sell.

## Build status

Built by Jenkins: `Minecraft/storage-manager-master` (master) and `Minecraft/storage-manager-tags` (refs/tags/v*). Webhook-driven since the 2026-08-25 split.

<!-- tested 2026-08-25T16:52:40Z -->
