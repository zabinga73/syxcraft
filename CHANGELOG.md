# Changelog

Versions cover both parts: the Songs of Syx exporter (`exporter/`) and the Minecraft mod Syxcraft (`minecraft/`).

## 1.5.0
Syxcraft:
- Chambers (rich people's houses): a bed with a gold-studded dark oak headboard, red carpets with a gold runner
  down the aisle, cushioned dark oak benches you can sit on, a blackstone fireplace with gold and candles, and
  corners heaped with gold blocks, raw gold, emeralds, the odd diamond block, chests and pots.
- Graveyard: laid out from the player's own arrangement: headstones (stone walls, some with a lit candle), dirt
  grave plots, flowerbeds, dirt pathways and dark spruce trees sized like the game's (was a field of stone stacks).
- Statues face the way they do in the game: one turned a quarter is turned in Minecraft too.
- Fix: pillars 2x2 tiles big had no shaft, leaving their capitals floating.
- Height variety leans harder towards the low end: about 80% of buildings get less than half the maximum.
- `/syx check` reports `highWaterColumns` (water above city level).

## 1.4.0
Syxcraft:
- Mixed woods (placer screen): each building's wooden roof in a random wood (oak, spruce, birch, jungle, acacia,
  dark oak, mangrove, cherry). Stone, brick and tile roofs keep their material. Off by default.
- Taller (placer screen): height variety. Each building gets 0 to +5/+10/+20/+30 extra blocks of wall, most in the
  lower half. The value is for scale 2 and scales with the scale. Off by default.
- Tall walls get rows of windows like storeys, and ceiling lanterns hang on chains down to the usual height, so tall
  interiors stay lit.

## 1.3.0
Syxcraft:
- Fight pit: the game's entrance passages are a tunnel from outside to the arena under the stands, plus a ramp of
  stairs from the arena floor up into the stands. The outer wall is open where they come out.
- Stage: a wooden platform 2 blocks high with steps all round and lanterns at the corners.
- Execution: the gallows is a raised wooden scaffold with stairs, posts and a beam with hanging chains (was rows
  of lecterns). Chopping blocks, gibbet cages and crosses get simple designs too.
- Statues: a figure with Minecraft player proportions, exactly centred on its pedestal.
- Mines: their lanterns hang at the bottom of the pit instead of floating at city level.

## 1.2.0
Syxcraft:
- Road and plaza blocks: Fungus Road brown mushroom block, Fungus Highway mushroom stem, Dark Plaza black glazed
  terracotta, Plaza white glazed terracotta, Forest Plaza brown glazed terracotta, Sacred Path polished sulfur,
  Crystal Water crying obsidian, Bricked Road bricks. palette.json values you never changed pick these up.
- Fix: statue legs and arms used a block that doesn't exist (polished diorite wall); they're diorite walls now.

## 1.1.0
Syxcraft:
- Sculptures: pillars are classical quartz columns (stepped platform, flared base and capital, round shaft); statues
  are a simple white figure on a polished pedestal. Torch monuments stand on polished andesite and quartz.
- Fight pit: a small wooden coliseum built from the game's layout: sand arena, log rim, stands of spruce stairs
  rising outwards, an outer wall with towers, and open gateways.
- Stockade: a spruce-log palisade with a gate at its entrance, and beds, tables, barrels and the like scattered
  over the yard.
- Water pump: stone floor, a rimmed basin, granite pump housings, rows of barrels and an outlet channel, after the
  game's sprite. Ponds and pools get a plank rim with a spruce fence.
- Mines and clay pits: storage and auxiliaries stand on the quarry floor instead of on pillars at city level.
- Roads: Cobblestone Road is cobblestone (was moss), Paved Road stone bricks, Highway polished andesite.
  palette.json values you never changed pick these up automatically.

## 1.0.0
First public release: Syxcraft 1.0.0 and the Syx Map Exporter 1.0.0 (export format v3). Same features as 0.11.0
plus exporter 0.3.0.

## 0.11.0
Syxcraft:
- Topography has its own Valleys setting: the most open land may sink below the city level (default 5 blocks;
  none, 2, 5, 8, 12, 16 or 24). Hills now only sets how high land may rise. Both apply to Random and Local terrain.
- `/syx place <file> <scale> at <x> <z> <topography> [hills] [valleys]`.

## 0.10.0
Syxcraft:
- Topography: roads and paving outside rooms no longer hold the ground flat, so they run over the hills like
  trails instead of cutting trenches. They level out where they reach buildings and rooms.
- Tall mountains are cleared all the way up. Clearing used to stop 96 blocks above the city, leaving their tips
  floating.
- `/syx check` reports `floatingColumns` (terrain left high over the city), and its attic count only looks under
  roofs now (hills with trees used to count).

## 0.9.0
Syxcraft:
- Spruce benches have brown cushions on the seat. Right-click one with an empty hand to sit down; sneak to get up.
- Mass graves are dug pits as deep as the quarries (Quarry depth setting), with earth walls, a ladder in the
  corner and bones and skulls on the bottom.
- Citizens takes a number instead of on/off: Off, ≤25, ≤50, ≤100, ≤200, ≤400 or All. A bigger city is sampled
  evenly (the same people each time), and the chat says the ratio. Still off by default.

## 0.8.0
Exporter 0.3.0 (export format v3):
- New `people` list: each citizen's tile, name, race, type, workplace and home.

Syxcraft:
- Topography setting (placer screen, left column): Flat (as before), Random (rolling hills) or Local terrain
  (follows the Minecraft ground that was there). Only open land moves: anything within 2 tiles of rooms, roads,
  buildings, fences, fortifications or water stays at city level, ramping to full height about 10 tiles out.
  "Hills" caps the height (±8 to ±48, default 16). Edge blending and trees follow the new ground.
- `/syx place <file> <scale> at <x> <z> [flat|random|local] [hills]`.
- Experimental Citizens toggle (off by default, untested): villagers named after the city's people, needs an export
  from exporter 0.3.0.

## 0.7.0
Syxcraft:
- No more hollow attics: the space between a building's ceiling and its hipped roof is filled with the roof block,
  so there's no dark space for mobs to spawn in.
- `/syx check` also counts air left in attics (should be 0).

## 0.6.0
Exporter 0.2.0 (export format v2):
- New `water` layer: whether each canal, pond, pool or drain tile holds water right now (from the game's own
  irrigation state).

Syxcraft:
- Ponds and pools are dug basins, canals and drains are trenches. Full ones hold water (ponds get lily pads),
  empty ones stay dry with a mud or stone-brick bed. Exports from the old exporter don't record this, so their water
  rooms are built full.
- Farms: farmland with crops (wheat; carrots, potatoes and beetroots in rows; berry bushes for fruit; mushrooms…)
  and a water block every 9 blocks, like a vanilla farm.
- Nature monuments: planted trees sized by the monument (azalea or flowering azalea, oak for "natural" ones) and
  flowerbeds (two flowers per bed, a wild mix for natural beds).
- Torch monuments: a stone plinth and pillar with a fire on top.
- Benches: a single row of seats along the bench.
- Mass grave: churned dirt with bone mounds and the odd skull.
- Construction sites: bare ground, scaffolding along the edges, piles of building material.
- Pastures: grass fenced in along the edge (untested: no pasture in the test saves yet).

## 0.5.0
Syxcraft:
- Dirt roads are gravel.
- Grass worn down by walking becomes shovelled dirt paths (it used to be coarse dirt and dirt).
- palette.json from earlier versions picks up these new defaults automatically. Values you changed yourself are
  kept.

## 0.4.0
Syxcraft:
- Lavatory basins are cauldrons of water instead of fence-and-pressure-plate tables.

## 0.3.0
Syxcraft:
- No more extra door at the end of a doorway passage. A house whose entrance opens straight onto the building's
  doorway uses that doorway as its front door (in Songs of Syx it is the same opening).
- Glass panes (and fences, walls, gates, roof stairs) connect properly. Placed blocks now work out their connections
  to neighbours that were already there, not only to ones placed after them, so windows are clean sheets of glass
  instead of loose posts.
- Houses get their thin wooden dividing walls at scale 1 too. Beds can run north–south to fit the narrow houses.
- `/syx check` also reports loose panes and the number of house-wall blocks.

## 0.2.0
Syxcraft:
- Double doors open correctly. Hinges now follow vanilla rules, so the two leaves swing open towards the jambs
  instead of meeting in the middle.
- Never more than a double door. A doorway wider than two blocks (scale 3+, or several Songs of Syx openings side
  by side) gets one centred double door, and the rest is walled up.
- Doors no longer pop off on dirt-path floors: a door always stands on a full block.
- Windows: glass only in the outermost layer of thick walls, with an open recess behind it, so you see a clear
  window rather than a grid of panes. Windows never sit next to a doorway.
- Lighting: hanging lanterns on a 5-block grid in every building, a lantern in every house, and a wall torch above
  the outside of every doorway. Buildings are no longer mob shelters.
- Houses packed wall to wall (as Songs of Syx builds them) get a one-block spruce wall between them, shared with
  the neighbour, plus a door at each house's entrance (scale 2 and up). The block can be changed with the
  `houseWall` key in palette.json.
- Seats (benches, chairs) face the nearest table, hearth, fire or altar. Hearth benches face the fires.
- Wells look like village wells: a cobblestone rim around water, fence posts at the corners, a cobblestone roof and a bell.
- The speaker's stage is a raised smooth stone platform instead of rows of lecterns.
- The placer screen shows the mod version.
- Debug commands: `/syx check` (checks door pairing), `/syx render plan <dy>` (floor plan at a height),
  `/syx probe <x> <y> <z>`.

## 0.1.0
First release.
- Exporter: writes a `.syxmap` of the loaded save, named after the save.
- Syxcraft: a placer screen (Y) and `/syx` commands; terrain, water, plants, buildings with hipped roofs, doors
  and windows, furniture designs, shrine themes, quarries; edge blending; palette.json overrides.
