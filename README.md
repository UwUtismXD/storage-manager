# storage manager

A Fabric client mod for Minecraft 1.21.8 that turns an alt account into an automated storage
bot: it uses [Baritone](https://github.com/cabaletta/baritone) to physically walk to chests and
store/fetch items, controlled through a local web UI.

## Requirements

- The full **Baritone** mod (not just the API) must be installed in `mods/` alongside this mod at
  runtime - it's only a `compileOnly` dependency here. Download `baritone-standalone-fabric-1.15.0.jar`
  from the [Baritone releases page](https://github.com/cabaletta/baritone/releases/tag/v1.15.0)
  (matches Minecraft 1.21.6-1.21.8). `./gradlew runClient` already has this set up under `run/mods/`.
- Fabric API (pulled in automatically as a dependency).

## Setup

For build environment setup, see the [Fabric Documentation page](https://docs.fabricmc.net/develop/getting-started/creating-a-project#setting-up).

Once the bot account is in the world, open `http://127.0.0.1:8642` (only reachable from the same
machine by default) and use the **Setup** panel to enter, by X/Y/Z coordinates copied from the
F3 debug screen:

- The storage region's min/max corners (the bot scans this box for chests/barrels/shulker boxes).
- An input chest (drop items here for the bot to sort into storage).
- An output chest (the bot delivers withdrawn items here for you to collect).

From there, use **Rescan storage** / **Sort input chest** on demand, or the **Withdraw item** form
to request items (e.g. `minecraft:diamond`, count `64`) - the bot queues and executes the job on
its next tick.

## License

This template is available under the CC0 license. Feel free to learn from it and incorporate it in your own projects.
