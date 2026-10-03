package sos2mc.export;

import java.io.BufferedOutputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

import game.GAME;
import game.VERSION;
import init.paths.PATHS;
import init.sprite.SPRITES;
import init.sprite.game.GameSheets;
import init.sprite.game.Sheet;
import init.sprite.game.SheetPair;
import init.sprite.game.Sheets;
import settlement.entity.ENTITY;
import settlement.entity.humanoid.Humanoid;
import settlement.main.SETT;
import settlement.stats.Induvidual;
import settlement.stats.STATS;
import snake2d.util.datatypes.COORDINATE;
import settlement.room.main.Room;
import settlement.room.main.RoomBlueprint;
import settlement.room.main.RoomBlueprintImp;
import settlement.room.main.RoomInstance;
import settlement.room.main.furnisher.Furnisher;
import settlement.room.main.furnisher.FurnisherItem;
import settlement.room.main.furnisher.FurnisherItemGroup;
import settlement.room.main.furnisher.FurnisherItemTile;
import settlement.room.sprite.RoomSprite;
import settlement.room.sprite.RoomSpriteImp;
import settlement.room.water.RoomPumpable;
import settlement.tilemap.floor.Floors.Floor;
import settlement.tilemap.ground.GroundType;
import settlement.tilemap.terrain.Terrain;
import settlement.tilemap.terrain.Terrain.TerrainTile;
import init.resources.Minable;
import snake2d.util.color.COLOR;
import snake2d.util.datatypes.Coo;
import snake2d.util.datatypes.RECTANGLE;
import snake2d.util.file.Json;
import snake2d.util.sets.KeyMap;
import snake2d.util.sets.LIST;
import util.keymap.MAPPED;

/**
 * Dumps the live settlement into a .syxmap file: gzip(JSON), with per-tile layers stored as base64 of
 * little-endian arrays (row-major, index = x + y*width). See FORMAT.md.
 */
final class Exporter {

	static final int FORMAT_VERSION = 3;

	// flags layer bits
	static final int F_INDOORS = 1, F_ROOF = 2, F_MASSIVE_WALL = 4, F_COVERS = 8, F_FURN_MASTER = 16,
			F_CANDLE = 32;

	private final String saveName;

	Exporter(String saveName) {
		this.saveName = saveName;
	}

	private final int W = SETT.TWIDTH, H = SETT.THEIGHT, A = SETT.TAREA;

	private final Palette groundP = new Palette(false), floorP = new Palette(true), terrainP = new Palette(false),
			mineralP = new Palette(true), blueprintP = new Palette(true);

	private final List<Map<String, Object>> terrainMeta = new ArrayList<>();
	private final IdentityHashMap<Room, Integer> roomIds = new IdentityHashMap<>();
	private final List<Map<String, Object>> rooms = new ArrayList<>();
	private final List<Map<String, Object>> items = new ArrayList<>();
	private final IdentityHashMap<Sheet, String> sheetNames = new IdentityHashMap<>();
	private final List<String> warnings = new ArrayList<>();

	String export() throws Exception {
		indexSheets();

		byte[] ground = new byte[A], floor = new byte[A], floorDeg = new byte[A], grass = new byte[A],
				terrain = new byte[A], flags = new byte[A], mineral = new byte[A], mineralAmt = new byte[A],
				hStart = new byte[A], hEnd = new byte[A], furnItem = new byte[A], furnTile = new byte[A],
				furnData = new byte[A], furnRot = new byte[A], color = new byte[A * 3], water = new byte[A];
		short[] tdata = new short[A], room = new short[A], roomBp = new short[A];

		Terrain T = SETT.TERRAIN();
		short[] rawTData = (short[]) field(Terrain.class, "datad").get(T);
		Object fd = SETT.ROOMS().fData;
		byte[] rawSprite = (byte[]) field(fd.getClass(), "spriteDataI").get(fd);
		Coo coo = new Coo();

		for (int ty = 0; ty < H; ty++) {
			for (int tx = 0; tx < W; tx++) {
				int t = tx + ty * W;
				int f = 0;

				GroundType g = SETT.GROUND().MAP.get(tx, ty);
				ground[t] = (byte) groundP.id(g == null ? null : groundKey(g), g);

				Floor fl = SETT.FLOOR().getter.get(tx, ty);
				floor[t] = (byte) floorP.id(fl == null ? null : fl.key, fl);
				if (fl != null)
					floorDeg[t] = (byte) clamp255(SETT.FLOOR().degrade.get(tx, ty) * 255);
				grass[t] = (byte) SETT.GRASS().currentI.get(t);

				TerrainTile tt = T.get(t);
				terrain[t] = (byte) terrainP.id(tt.key(), tt);
				tdata[t] = rawTData[t];
				if (T.indoors.is(t))
					f |= F_INDOORS;
				if (tt.roofIs())
					f |= F_ROOF;
				if (tt.isMassiveWall())
					f |= F_MASSIVE_WALL;
				if (tt.coversCompletely(tx, ty))
					f |= F_COVERS;
				hStart[t] = (byte) clampS8(tt.heightStart(tx, ty));
				hEnd[t] = (byte) clampS8(tt.heightEnd(tx, ty));

				Minable m = SETT.MINERALS().getter.get(tx, ty);
				mineral[t] = (byte) mineralP.id(m == null ? null : m.key(), m);
				if (m != null)
					mineralAmt[t] = (byte) Math.min(255, SETT.MINERALS().amountInt.get(tx, ty));

				COLOR c = SETT.TILE_MAP().miniC(tx, ty);
				if (c != null) {
					color[t * 3] = c.red();
					color[t * 3 + 1] = c.green();
					color[t * 3 + 2] = c.blue();
				}

				Room r = SETT.ROOMS().map.get(t);
				if (r != null) {
					RoomBlueprint bp = r.blueprint();
					roomBp[t] = (short) blueprintP.id(bp.key, bp);
					// canals, ponds/pools and drains: is there water in this tile right now?
					if (bp instanceof RoomPumpable.ROOM_PUMPABLE) {
						try {
							RoomPumpable pu = ((RoomPumpable.ROOM_PUMPABLE) bp).pumpable(tx, ty);
							if (pu != null)
								water[t] = (byte) clamp255(1 + pu.irrigation(tx, ty) * 254);
						} catch (Throwable e) {
							// unknown state: leave 0
						}
					}
					room[t] = (short) roomId(r, tx, ty);

					FurnisherItem it = SETT.ROOMS().fData.item.get(t);
					FurnisherItemTile ft = SETT.ROOMS().fData.tile.get(t);
					if (it != null && ft != null) {
						furnItem[t] = (byte) it.index();
						furnTile[t] = (byte) ft.index();
						furnData[t] = rawSprite[t];
						if (ft.sprite() != null) {
							try {
								furnRot[t] = (byte) ft.sprite().rotation(rawSprite[t] & 0xFF, it);
							} catch (Throwable e) {
								// some sprites need render context; rotation stays 0
							}
						}
						if (SETT.ROOMS().fData.candle.is(t))
							f |= F_CANDLE;
						COORDINATE_master:
						{
							if (SETT.ROOMS().fData.itemX1Y1(tx, ty, coo, r) == null)
								break COORDINATE_master;
							if (coo.x() + it.firstX() == tx && coo.y() + it.firstY() == ty) {
								f |= F_FURN_MASTER;
								Map<String, Object> o = new LinkedHashMap<>();
								o.put("bp", blueprintP.id(bp.key, bp));
								o.put("item", it.index());
								o.put("x", coo.x());
								o.put("y", coo.y());
								o.put("w", it.width());
								o.put("h", it.height());
								o.put("rot", it.rotation);
								o.put("room", (int) room[t]);
								items.add(o);
							}
						}
					}
				}
				flags[t] = (byte) f;
			}
		}

		Map<String, Object> root = new LinkedHashMap<>();
		root.put("format", "syxmap");
		root.put("formatVersion", FORMAT_VERSION);
		root.put("gameVersion", VERSION.VERSION_STRING);
		root.put("city", cityName());
		root.put("save", saveName);
		root.put("exported", new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss").format(new Date()));
		root.put("width", W);
		root.put("height", H);
		root.put("flagBits", Map.of("indoors", F_INDOORS, "roof", F_ROOF, "massiveWall", F_MASSIVE_WALL,
				"coversCompletely", F_COVERS, "furnMaster", F_FURN_MASTER, "candle", F_CANDLE));

		Map<String, Object> pal = new LinkedHashMap<>();
		pal.put("ground", groundP.keys);
		pal.put("floor", floorP.keys);
		pal.put("terrain", terrainP.keys);
		pal.put("mineral", mineralP.keys);
		pal.put("blueprint", blueprintP.keys);
		root.put("palettes", pal);
		root.put("terrainInfo", terrainInfo());
		root.put("floorInfo", floorInfo());
		root.put("rooms", rooms);
		root.put("blueprints", blueprintCatalog());
		root.put("furniture", items);
		root.put("people", people());

		Map<String, Object> layers = new LinkedHashMap<>();
		layers.put("ground", layer("u8", ground));
		layers.put("floor", layer("u8", floor));
		layers.put("floorDegrade", layer("u8", floorDeg));
		layers.put("grass", layer("u8", grass));
		layers.put("terrain", layer("u8", terrain));
		layers.put("terrainData", layer("u16", le(tdata)));
		layers.put("flags", layer("u8", flags));
		layers.put("heightStart", layer("i8", hStart));
		layers.put("heightEnd", layer("i8", hEnd));
		layers.put("mineral", layer("u8", mineral));
		layers.put("mineralAmount", layer("u8", mineralAmt));
		layers.put("room", layer("u16", le(room)));
		layers.put("roomBlueprint", layer("u16", le(roomBp)));
		layers.put("furnItem", layer("u8", furnItem));
		layers.put("furnTile", layer("u8", furnTile));
		layers.put("furnData", layer("u8", furnData));
		layers.put("furnRot", layer("u8", furnRot));
		layers.put("minimapRGB", layer("rgb8", color));
		layers.put("water", layer("u8", water));
		root.put("layers", layers);
		root.put("warnings", warnings);

		Path dir = ModLog.dir();
		Files.createDirectories(dir);
		String base;
		if (saveName == null || saveName.isBlank())
			base = cityName() + "-newgame";
		else if (saveName.matches("(?i)(auto|quick)save\\d*|_.*"))
			base = cityName() + "-" + saveName; // AutoSave3 -> Eutrid-AutoSave3
		else
			base = saveName;
		String name = safe(base) + "-" + new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date()) + ".syxmap";
		Path out = dir.resolve(name);
		try (OutputStream os = new GZIPOutputStream(new BufferedOutputStream(Files.newOutputStream(out)), 1 << 16);
				Writer w = new OutputStreamWriter(os, StandardCharsets.UTF_8)) {
			JsonOut.write(w, root);
		}
		Files.writeString(dir.resolve("latest.txt"), out.toAbsolutePath().toString());
		return out.toAbsolutePath().toString();
	}

	/* ---------------- people ---------------- */

	/** every humanoid on the map: where they are, who they are, where they work and live */
	private List<Object> people() {
		List<Object> res = new ArrayList<>();
		ENTITY[] ents = SETT.ENTITIES().getAllEnts();
		for (ENTITY e : ents) {
			if (!(e instanceof Humanoid) || e.isRemoved())
				continue;
			Humanoid h = (Humanoid) e;
			try {
				Induvidual in = h.indu();
				Map<String, Object> o = new LinkedHashMap<>();
				COORDINATE c = h.tc();
				o.put("x", c.x());
				o.put("y", c.y());
				o.put("name", String.valueOf(STATS.APPEARANCE().name(in)));
				o.put("race", in.race().key);
				o.put("type", in.hType().key);
				o.put("class", in.clas().key);
				o.put("gender", STATS.APPEARANCE().gender.get(in));
				o.put("player", in.player());
				RoomInstance work = STATS.WORK().EMPLOYED.get(in);
				if (work != null) {
					RECTANGLE wc = work.body();
					o.put("work", roomId(work, wc.x1(), wc.y1()));
					o.put("job", work.blueprint().key);
				}
				COORDINATE hc = STATS.HOME().GETTER.hCoo(h);
				if (hc != null) {
					o.put("homeX", hc.x());
					o.put("homeY", hc.y());
				}
				res.add(o);
			} catch (Throwable t) {
				warnings.add("person skipped: " + t);
			}
		}
		return res;
	}

	/* ---------------- rooms ---------------- */

	private int roomId(Room r, int tx, int ty) {
		Integer id = roomIds.get(r);
		if (id != null)
			return id;
		id = rooms.size() + 1;
		roomIds.put(r, id);
		Map<String, Object> o = new LinkedHashMap<>();
		o.put("id", id);
		o.put("blueprint", r.blueprint().key);
		o.put("bp", blueprintP.id(r.blueprint().key, r.blueprint()));
		if (r instanceof RoomInstance) {
			RoomInstance ri = (RoomInstance) r;
			RECTANGLE b = ri.body();
			o.put("x1", b.x1());
			o.put("y1", b.y1());
			o.put("x2", b.x2());
			o.put("y2", b.y2());
			o.put("area", ri.area());
			o.put("upgrade", ri.upgrade());
			o.put("degrade", round(ri.getDegrade()));
			try {
				o.put("name", String.valueOf(ri.name()));
			} catch (Throwable e) {
			}
		} else {
			o.put("singleton", true);
		}
		rooms.add(o);
		return id;
	}

	private List<Object> blueprintCatalog() {
		List<Object> res = new ArrayList<>();
		for (int i = 0; i < blueprintP.objs.size(); i++) {
			RoomBlueprint bp = (RoomBlueprint) blueprintP.objs.get(i);
			if (bp == null) { // index 0 = no room
				res.add(null);
				continue;
			}
			Map<String, Object> o = new LinkedHashMap<>();
			o.put("key", bp.key);
			o.put("class", bp.getClass().getName());
			if (bp instanceof RoomBlueprintImp) {
				RoomBlueprintImp imp = (RoomBlueprintImp) bp;
				o.put("name", String.valueOf(imp.info.name));
				o.put("type", imp.type);
				try {
					Furnisher fu = imp.constructor();
					if (fu != null)
						o.put("furnisher", furnisher(bp.key, fu));
				} catch (Throwable e) {
					warnings.add("furnisher " + bp.key + ": " + e);
				}
			}
			res.add(o);
		}
		return res;
	}

	private Map<String, Object> furnisher(String key, Furnisher fu) {
		Map<String, Object> o = new LinkedHashMap<>();
		o.put("mustBeIndoors", fu.mustBeIndoors());
		o.put("mustBeOutdoors", fu.mustBeOutdoors());
		o.put("usesArea", fu.usesArea());
		List<String> res = new ArrayList<>();
		for (int i = 0; i < fu.resources(); i++)
			res.add(fu.resource(i).key);
		o.put("resources", res);
		try {
			o.put("floor", fu.floor(0) == null ? null : fu.floor(0).key);
		} catch (Throwable e) {
		}

		Map<String, List<String>> spriteFrames = spriteFramesFromJson(key);
		IdentityHashMap<RoomSprite, Integer> spriteIds = new IdentityHashMap<>();
		List<Object> sprites = new ArrayList<>();
		Map<Integer, Object> tiles = new LinkedHashMap<>();
		List<Object> groups = new ArrayList<>();
		List<Object> itemsOut = new ArrayList<>();

		LIST<FurnisherItemGroup> gs = fu.groups();
		for (int gi = 0; gi < gs.size(); gi++) {
			FurnisherItemGroup g = gs.get(gi);
			Map<String, Object> go = new LinkedHashMap<>();
			go.put("index", g.index());
			go.put("name", String.valueOf(g.name));
			go.put("sizes", g.size());
			go.put("rotations", g.rotations());
			groups.add(go);
			for (int s = 0; s < g.size(); s++) {
				for (int rot = 0; rot < g.rotations(); rot++) {
					FurnisherItem it = g.item(s, rot);
					if (it == null)
						continue;
					Map<String, Object> io = new LinkedHashMap<>();
					io.put("index", it.index());
					io.put("group", g.index());
					io.put("size", s);
					io.put("rot", it.rotation);
					io.put("w", it.width());
					io.put("h", it.height());
					List<Object> grid = new ArrayList<>();
					for (int y = 0; y < it.height(); y++) {
						List<Object> row = new ArrayList<>();
						for (int x = 0; x < it.width(); x++) {
							FurnisherItemTile t = it.get(x, y);
							row.add(t == null ? -1 : t.index());
							if (t != null && !tiles.containsKey(t.index()))
								tiles.put(t.index(), tile(t, spriteIds, sprites, spriteFrames));
						}
						grid.add(row);
					}
					io.put("tiles", grid);
					itemsOut.add(io);
				}
			}
		}
		o.put("groups", groups);
		o.put("items", itemsOut);
		o.put("tiles", new ArrayList<>(tiles.values()));
		o.put("sprites", sprites);
		return o;
	}

	private Map<String, Object> tile(FurnisherItemTile t, IdentityHashMap<RoomSprite, Integer> spriteIds,
			List<Object> sprites, Map<String, List<String>> spriteFrames) {
		Map<String, Object> o = new LinkedHashMap<>();
		o.put("index", t.index());
		o.put("availability", t.availability.name());
		o.put("blocker", t.isBlocker());
		o.put("data", t.data());
		o.put("noWalls", t.noWalls);
		o.put("canCandle", t.canGoCandle);
		RoomSprite s = t.sprite();
		if (s != null) {
			Integer id = spriteIds.get(s);
			if (id == null) {
				id = sprites.size();
				spriteIds.put(s, id);
				sprites.add(sprite(id, s, spriteFrames));
			}
			o.put("sprite", id);
		}
		return o;
	}

	private Map<String, Object> sprite(int id, RoomSprite s, Map<String, List<String>> spriteFrames) {
		Map<String, Object> o = new LinkedHashMap<>();
		o.put("id", id);
		o.put("class", s.getClass().getName());
		List<String> frames = framesOf(s);
		o.put("frames", frames);
		// recover the SPRITES json key (e.g. TABLE_COMBO) by matching frames
		String best = null;
		int bestScore = 0;
		for (Map.Entry<String, List<String>> e : spriteFrames.entrySet()) {
			int score = 0;
			for (String f : frames)
				if (e.getValue().contains(f))
					score++;
			if (score > bestScore || (score == bestScore && score > 0 && e.getValue().size() == frames.size())) {
				bestScore = score;
				best = e.getKey();
			}
		}
		if (best != null)
			o.put("key", best);
		return o;
	}

	private List<String> framesOf(RoomSprite s) {
		List<String> res = new ArrayList<>();
		if (!(s instanceof RoomSpriteImp))
			return res;
		try {
			Sheets[] sheets = (Sheets[]) field(RoomSpriteImp.class, "sheets").get(s);
			for (Sheets ss : sheets) {
				for (int i = 0; i < ss.sheets.size(); i++) {
					SheetPair p = ss.sheets.get(i);
					String n = sheetNames.get(p.s);
					if (n != null && !res.contains(n))
						res.add(n);
				}
			}
		} catch (Throwable e) {
			warnings.add("frames " + s.getClass().getName() + ": " + e);
		}
		return res;
	}

	/** SPRITES block of the room's init json: key -> frame strings "FILE:ROW". */
	private Map<String, List<String>> spriteFramesFromJson(String key) {
		Map<String, List<String>> res = new LinkedHashMap<>();
		try {
			if (!PATHS.INIT().getFolder("room").exists(key))
				return res;
			Json j = new Json(PATHS.INIT().getFolder("room").get(key));
			if (!j.has("SPRITES"))
				return res;
			Json sp = j.json("SPRITES");
			for (String k : sp.keys()) {
				List<String> fr = new ArrayList<>();
				try {
					Json[] js = sp.jsonsIs(k) ? sp.jsons(k) : new Json[] { sp.json(k) };
					for (Json x : js) {
						if (!x.has("FRAMES"))
							continue;
						for (String v : x.values("FRAMES"))
							fr.add(v.replace(" ", "").replace("\t", ""));
					}
				} catch (Throwable e) {
					// not a sprite entry
				}
				res.put(k, fr);
			}
		} catch (Throwable e) {
			warnings.add("sprite json " + key + ": " + e);
		}
		return res;
	}

	/** Reverse map of the game's sheet cache: Sheet -> "FILE:ROW". */
	@SuppressWarnings("unchecked")
	private void indexSheets() {
		try {
			GameSheets gs = SPRITES.GAME();
			LIST<KeyMap<LIST<Sheet>>> all = (LIST<KeyMap<LIST<Sheet>>>) field(GameSheets.class, "gsheets").get(gs);
			for (int ti = 0; ti < all.size(); ti++) {
				KeyMap<LIST<Sheet>> km = all.get(ti);
				for (String file : km.keys()) {
					LIST<Sheet> l = km.get(file);
					for (int i = 0; i < l.size(); i++)
						sheetNames.putIfAbsent(l.get(i), file + ":" + i);
				}
			}
		} catch (Throwable e) {
			warnings.add("sheet index: " + e);
		}
	}

	/* ---------------- palettes info ---------------- */

	private List<Object> terrainInfo() {
		List<Object> res = new ArrayList<>();
		for (int i = 0; i < terrainP.objs.size(); i++) {
			TerrainTile t = (TerrainTile) terrainP.objs.get(i);
			Map<String, Object> o = new LinkedHashMap<>();
			o.put("key", t.key());
			o.put("class", t.getClass().getName());
			o.put("name", String.valueOf(t.name()));
			o.put("roof", t.roofIs());
			o.put("massiveWall", t.isMassiveWall());
			try {
				// building components know their structure (wood, stone, ...)
				Object b = t.getClass().getMethod("building").invoke(t);
				Object st = b.getClass().getField("structure").get(b);
				o.put("structure", ((MAPPED) st).key());
			} catch (Throwable e) {
			}
			res.add(o);
		}
		return res;
	}

	private List<Object> floorInfo() {
		List<Object> res = new ArrayList<>();
		for (int i = 0; i < floorP.objs.size(); i++) {
			Floor f = (Floor) floorP.objs.get(i);
			if (f == null) { // index 0 = no floor
				res.add(null);
				continue;
			}
			Map<String, Object> o = new LinkedHashMap<>();
			o.put("key", f.key);
			o.put("name", String.valueOf(f.name));
			o.put("road", f.isRoad);
			o.put("grass", f.isGrass);
			o.put("resource", f.resource == null ? null : f.resource.key);
			res.add(o);
		}
		return res;
	}

	private String groundKey(GroundType g) {
		try {
			Object types = SETT.GROUND().types;
			for (Field fi : types.getClass().getFields())
				if (fi.getType() == GroundType.class && fi.get(types) == g)
					return fi.getName();
		} catch (Throwable e) {
		}
		return "GROUND_" + g.index;
	}

	private String cityName() {
		try {
			String n = String.valueOf(GAME.player().name).trim();
			if (!n.isEmpty())
				return n;
		} catch (Throwable e) {
		}
		return "city";
	}

	/* ---------------- utils ---------------- */

	static final class Palette {
		final List<String> keys = new ArrayList<>();
		final List<Object> objs = new ArrayList<>();
		final Map<String, Integer> ids = new LinkedHashMap<>();

		Palette(boolean zeroIsNone) {
			if (zeroIsNone) {
				keys.add(null);
				objs.add(null);
			}
		}

		int id(String key, Object o) {
			if (key == null)
				return 0;
			Integer i = ids.get(key);
			if (i == null) {
				i = keys.size();
				keys.add(key);
				objs.add(o);
				ids.put(key, i);
			}
			return i;
		}
	}

	private static Map<String, Object> layer(String type, byte[] data) {
		Map<String, Object> o = new LinkedHashMap<>();
		o.put("type", type);
		o.put("data", Base64.getEncoder().encodeToString(data));
		return o;
	}

	private static byte[] le(short[] s) {
		byte[] b = new byte[s.length * 2];
		for (int i = 0; i < s.length; i++) {
			b[i * 2] = (byte) s[i];
			b[i * 2 + 1] = (byte) (s[i] >> 8);
		}
		return b;
	}

	static Field field(Class<?> c, String name) throws NoSuchFieldException {
		for (Class<?> k = c; k != null; k = k.getSuperclass()) {
			try {
				Field f = k.getDeclaredField(name);
				f.setAccessible(true);
				return f;
			} catch (NoSuchFieldException e) {
			}
		}
		throw new NoSuchFieldException(c.getName() + "." + name);
	}

	private static int clamp255(double d) {
		return (int) Math.max(0, Math.min(255, d));
	}

	private static int clampS8(int v) {
		return Math.max(-128, Math.min(127, v));
	}

	private static double round(double d) {
		return Math.round(d * 1000) / 1000.0;
	}

	private static String safe(String s) {
		return s.replaceAll("[^A-Za-z0-9 _-]", "_").trim();
	}
}
