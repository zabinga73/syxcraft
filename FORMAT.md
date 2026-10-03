# `.syxmap` format (v2)

Written by the Songs of Syx exporter mod (`exporter/`) to `%APPDATA%/songsofsyx/sos2mc/<SaveName>-<stamp>.syxmap`.
The newest file's path is also written to `latest.txt` in that folder.
Under Proton, `%APPDATA%` is `compatdata/1162750/pfx/drive_c/users/steamuser/AppData/Roaming`.

The file is gzip-compressed UTF-8 JSON. The reference reader is `tools/syxmap_inspect.py`.

## Top level
| key | meaning |
|---|---|
| `format`, `formatVersion` | `"syxmap"`, `2` (v2 added the `water` layer and `save`) |
| `gameVersion`, `city`, `save`, `exported` | e.g. `0.71.44`; city-state name; loaded save name (null for a new game); timestamp. Files are named `<save>-<stamp>.syxmap` (autosaves: `<city>-AutoSave-…`, new games: `<city>-newgame-…`) |
| `width`, `height` | tiles (default 768×768). Tile index = `x + y*width`. +x is east, +y is south |
| `flagBits` | bit values for the `flags` layer |
| `palettes` | string keys for the `ground`, `floor`, `terrain`, `mineral` and `blueprint` layers. `floor`, `mineral` and `blueprint` use index 0 = none (`null`) |
| `terrainInfo[i]` | per terrain palette entry: `key`, `class`, `name`, `roof`, `massiveWall`, `structure` (building material: `MUD`, `WOOD`, `STONE`…) |
| `floorInfo[i]` | per floor entry: `key`, `name`, `road`, `grass`, `resource` (index 0 = `null`) |
| `rooms[]` | room instances. `id` is the value in the `room` layer, starting at 1. Each has `blueprint`, `bp`, bounds `x1,y1,x2,y2`, `area`, `upgrade`, `degrade`, `name`. Singleton rooms (canals and similar) have `singleton: true` |
| `blueprints[i]` | per blueprint palette entry: `key`, `name`, `type`, `class`, and a `furnisher` catalogue (below). Index 0 = `null` |
| `furniture[]` | placed furniture items: `bp`, `item` (catalogue item index), `x,y` (top-left of the item's grid), `w,h`, `rot`, `room` |
| `layers` | per-tile arrays (below) |
| `warnings` | non-fatal problems the exporter hit |

## Furnisher catalogue (`blueprints[i].furnisher`)
- `groups[]`: `index`, `name` (e.g. "Work Bench"), `sizes`, `rotations`
- `items[]`: `index` (the value in the `furnItem` layer), `group`, `size`, `rot`, `w`, `h`, `tiles` (an h×w grid
  of tile indices, -1 = empty)
- `tiles[]`: `index` (the value in the `furnTile` layer), `availability` (e.g. `ROOM_SOLID`, `AVOID_PASS`), `blocker`,
  `data`, `noWalls`, `canCandle`, `sprite` (an index into `sprites`)
- `sprites[]`: `id`, `class`, `frames` (`"SHEETFILE:ROW"`), `key`. The key is the room's SPRITES name, such as
  `TABLE_COMBO`, `CHAIR_1X1` or `HEARTH_COMBO`, recovered by matching sheets, and it's what drives the Minecraft
  prefab choice. It's missing when no match was found.
- `resources`, `floor`, `mustBeIndoors`, `mustBeOutdoors`, `usesArea`

## Layers (`layers.<name>` = `{type, data: base64}`)
| layer | type | meaning |
|---|---|---|
| `ground` | u8 | `palettes.ground` (NORMAL, FOREST, PASTURE, INFERTILE, ROCK, SAND) |
| `floor` | u8 | `palettes.floor`, 0 = no floor. Roads and room floors |
| `floorDegrade` | u8 | 0..255 wear of the floor |
| `grass` | u8 | 0..15 grass growth. Low on walked-over/worn ground |
| `terrain` | u8 | `palettes.terrain`: water, trees, mountain, rock, building wall, ceiling, opening, fence, fortification… |
| `terrainData` | u16 LE | the terrain's raw per-tile data (tree size, water depth, wall joins…). Meaning depends on the terrain |
| `flags` | u8 | `indoors` 1, `roof` 2, `massiveWall` 4, `coversCompletely` 8, `furnMaster` 16, `candle` 32 |
| `heightStart`, `heightEnd` | i8 | the terrain's visual height range (walls and mountains) |
| `mineral`, `mineralAmount` | u8 | `palettes.mineral` deposit, and its amount |
| `room` | u16 LE | `rooms[id-1]`, 0 = none |
| `roomBlueprint` | u16 LE | `blueprints` index, 0 = none |
| `furnItem`, `furnTile` | u8 | catalogue item and tile index of the furniture on this tile. Only valid where `room` ≠ 0 and the tile has furniture. Check `furnTile` or the `furniture[]` list rather than relying on 0 |
| `furnData`, `furnRot` | u8 | raw sprite data, and the rotation the sprite reports |
| `water` | u8 | (v2) canal/pond/pool/drain tiles: 1 = dry, 2..255 = holds water (game irrigation × 254 + 1); 0 = not a water room. Missing in v1 files |
| `minimapRGB` | 3×u8 | the game's minimap colour. Channels may be 0..127, so double them when the max is ≤127 |

## Notes
- Building walls are terrain `BUILDING__<MAT>`. Roofed interiors are `BUILDING_CEILING_<MAT>_CEILING`, and
  `…_OPENING` tiles are doorways: gaps in the wall ring with the interior on one side and outdoors on the other. They're flagged indoors+roof and the Minecraft side puts doors there.
- SoS has no elevation. The Minecraft side derives height from water depth, mountains and rock.
