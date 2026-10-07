# Syxcraft: Songs of Syx cities in Minecraft

Turn a city you built in **Songs of Syx** into a walkable area of a **Minecraft** world: terrain, water, buildings
with doors, windows and roofs, furniture, farms, quarries, shrines and more, at 1 to 3 blocks per tile.

There are two mods with a file in between:

1. **Syx Map Exporter**: a Songs of Syx script mod (game version 71). Load a save and, about 2 seconds later, it
   writes `<SaveName>-<date>-<time>.syxmap` to the `sos2mc` folder in the game's user folder.
2. **Syxcraft**: a Fabric mod for Minecraft 26.3 that reads those files and places the city.

## Install
Download both from the [Releases](../../releases) page.

**Songs of Syx (v71):** unzip `sos2mc-export-<version>.zip` into the game's `mods` folder, so you get
`mods/sos2mc-export/_Info.txt`, then enable "Syx Map Exporter" in the launcher's mod list.
- Windows: `%APPDATA%\songsofsyx\mods\`
- Linux (Steam/Proton): `~/.local/share/Steam/steamapps/compatdata/1162750/pfx/drive_c/users/steamuser/AppData/Roaming/songsofsyx/mods/`
- The exporter never changes your save, but don't save over a save while the mod is on: the save then lists the
  mod as required. Its log is `songsofsyx/sos2mc/exporter.log`.

**Minecraft 26.3:** put `syxcraft-<version>.jar` in your `mods` folder with
[Fabric Loader](https://fabricmc.net/) 0.19.5+ and [Fabric API](https://modrinth.com/mod/fabric-api). Java 25+.
Needed on both the client (for the placer screen) and the server.

To uninstall, delete the files. Placed cities stay in the world.

## Placing a city
- In a world, press **Y** to open the placer. You need operator rights; in singleplayer the world's owner always has them.
  - **City:** pick an export (newest first, ⟳ rescans).
  - **Position:** centred on you, or on an X/Z you type in.
  - **Height:** match the terrain, put the city's water at sea level (lines its coast and rivers up with the
    ocean), or a custom Y.
  - **Scale:** 1, 2 (default) or 3 blocks per Songs of Syx tile.
  - **Area:** the built-up city plus a margin, or the whole map.
  - **Topography:** Flat, Random (rolling hills) or Local terrain (follows the Minecraft ground that was there).
    Only open land moves: buildings and rooms stay at city level and roads run over the hills like trails.
    **Hills** caps how high land may rise, **Valleys** how deep it may sink (default 5).
  - **Layers:** terrain, water, clear above, fill below, plants, blend edges, buildings, roofs, furniture, quarries.
  - **Roof style, wall height, quarry depth** (quarries and mass graves).
  - **Caves:** how high mountain caves are inside (auto = the wall height), and **Cave torches** to light them.
  - **Citizens** (experimental, off by default): the city's people as named villagers with professions from their
    jobs, capped at 25–400 (a big city is sampled evenly).
  - **Preview outline:** particles around the footprint. The footprint size and coordinates are shown on the screen.
  - **Place city / Cancel placement.** A progress bar shows each stage.
  - When a city is done, chat offers **[Regenerate city]**: it opens the screen again with X/Z set to that city's
    centre, so it can be placed again in the same spot (Local terrain probably won't work then, since the land it
    would follow is the city's own).
- Commands:
  - `/syx list`
  - `/syx place "<file>.syxmap" [scale] [at <x> <z> [flat|random|local] [hills] [valleys]]`
  - `/syx cancel`
  - `/syx render` writes a top-down PNG of the last placement to the game folder.
  - `/syx render slice <z> <x1> <x2>` writes a side-view PNG; `/syx render plan <dy>` a floor plan at ground+dy.
  - `/syx check` checks doors (hinges, no more than double doors), panes, attics and floating terrain.
- Placement can't be undone. Use a new or backed-up world.
- Exports are found in the Songs of Syx folders (Windows, Proton, native Linux, macOS) and in `<minecraft>/syxmaps/`.

## Changing which blocks are used
`config/syxcraft/palette.json` maps Songs of Syx keys to blocks. It's created with every default on first placement:
- `ground.*`, `floor.*`: ground types and floors/roads
- `wall.*`, `wallBase.*`, `pillar.*`, `roof.*`, `roofBlock.*`, `door.*`, `window.*`, `ceiling.*`: one set per
  building material (`_MUD`, `WOOD`, `STONE`, `GRAND`)
- `houseWall`: the thin walls between houses packed wall to wall
- `fence.*`, `fort.*`, `fortTop.*`: fences and fortifications
- `ore.*`: quarry lining

Values are block states, e.g. `"minecraft:oak_leaves[persistent=true]"`. Edit the file and place again.

## What becomes what
- **Terrain:** ground type and grass wear become grass, coarse dirt, dirt, sand or stone. Floors and roads use their
  own blocks.
- **Water:** shallow and deep water; depth grows with distance from the shore.
- **Mountains and rocks:** mountains become stone hills, height growing with distance from the edge, with ore where
  the save has deposits. Rocks become boulders.
- **Plants:** trees (oak, birch, dark oak), bushes, flowers, mushrooms, and wild crops on farmland.
- **Buildings:** walls in their material with log corner pillars and glass windows. Doorways (Songs of Syx "openings")
  get (double) doors. Interiors get the room's floor, hanging lanterns, and a hipped or flat roof.
- **Furniture:** a design per room type and sprite:
  - tables, chairs, workshops (crafting table, loom, stonecutter…)
  - a foundry (blast furnaces, chimneys, anvils)
  - storage as barrel stacks, plus knick-knacks (bells, pots, barrels…)
  - hearths, wells, market stalls, beds, homes, the throne, monuments
  - shrines and temples in their god's colours: Crator earthy; Shmalor black and white with red; Aminion red and
    white with black; Athuri green and gold
- **Mines and clay pits:** quarries of the chosen depth with a ladder and walls lined with the mined material.
- **Mass graves:** pits as deep as the quarries, with bones and skulls at the bottom.
- **Benches:** spruce benches with brown cushions. Right-click one with an empty hand to sit; sneak to get up.
- **Farms, ponds, canals, wells, monuments, construction sites, pastures** each get their own design.

`tools/syxmap_inspect.py` prints a summary of an export and draws preview images. `FORMAT.md` documents the file.

## Building from source
- **Syxcraft:** `cd minecraft && ./gradlew build` (Java 25+). The jar is `build/libs/syxcraft-<version>.jar`.
  `./devserver.sh start|cmd|wait|stop` runs a headless test server.
- **Exporter:** `cd exporter && ./build.sh [--install]`. It compiles against your own copy of `SongsOfSyx.jar`
  (set `SOS_GAME` / `SOS_USER` if the game isn't in the default Steam folders). The game's sources are not part
  of this repository.

## Credits
- Songs of Syx by Gamatron. Minecraft by Mojang. This project is not affiliated with either.
- Built with [Fabric](https://fabricmc.net/) and Fabric API.
- Written with the help of Claude Code (Anthropic).

## License
MIT, see `LICENSE`.
