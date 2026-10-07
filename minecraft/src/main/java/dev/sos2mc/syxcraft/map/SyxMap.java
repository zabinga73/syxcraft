package dev.sos2mc.syxcraft.map;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** A loaded .syxmap export (see sos2mc/FORMAT.md). All per-tile arrays are row-major: i = x + y*width. */
public final class SyxMap {

	public static final int F_INDOORS = 1, F_ROOF = 2, F_MASSIVE_WALL = 4, F_COVERS = 8, F_FURN_MASTER = 16, F_CANDLE = 32;

	public final Path path;
	public final String city, save, gameVersion, exported;
	public final int width, height;

	public final String[] groundKeys, floorKeys, terrainKeys, mineralKeys, blueprintKeys;
	public final String[] terrainStructure; // per terrain palette entry, building material or null

	public final byte[] ground, floor, floorDegrade, grass, terrain, flags, heightStart, heightEnd, mineral, mineralAmount,
			furnItem, furnTile, furnData, furnRot, minimapRGB;
	/** v2+: water rooms (canals, ponds, pools, drains): 0 = not one, 1 = dry, >1 = holds water. null in v1 files. */
	public final byte[] water;
	public final short[] terrainData, room, roomBlueprint;

	public final List<Room> rooms = new ArrayList<>();
	public final List<Furniture> furniture = new ArrayList<>();
	public final Blueprint[] blueprints;
	/** v3+: the people of the city. Empty for older files. */
	public final List<Person> people = new ArrayList<>();

	public record Room(int id, String blueprint, int bp, int x1, int y1, int x2, int y2, int upgrade, String name) {
	}

	public record Furniture(int bp, int item, int x, int y, int w, int h, int rot, int room) {
	}

	/** a citizen: tile position, name, race key, HTYPE key (SUBJECT, CHILD, SLAVE...), workplace room id (0 = none) */
	public record Person(int x, int y, String name, String race, String type, int work, String job, int homeX, int homeY) {
	}

	/** A furniture tile type of one blueprint: what sprite it uses and whether it blocks movement. */
	public record FurnTile(int index, String spriteKey, String availability, boolean blocker, boolean noWalls) {
	}

	public record FurnItem(int index, int group, String groupName, int size, int rot, int w, int h, int[][] tiles) {
	}

	public static final class Blueprint {
		public final String key, name, type;
		public final Map<Integer, FurnTile> tiles = new HashMap<>();
		public final Map<Integer, FurnItem> items = new HashMap<>();
		public final List<String> resources = new ArrayList<>();
		public final String floor;

		Blueprint(JsonObject o) {
			key = str(o, "key");
			name = str(o, "name");
			type = str(o, "type");
			JsonObject fu = o.has("furnisher") && o.get("furnisher").isJsonObject() ? o.getAsJsonObject("furnisher") : null;
			String fl = null;
			if (fu != null) {
				fl = str(fu, "floor");
				if (fu.has("resources"))
					for (JsonElement e : fu.getAsJsonArray("resources"))
						resources.add(e.getAsString());
				Map<Integer, String> sprites = new HashMap<>();
				for (JsonElement e : fu.getAsJsonArray("sprites")) {
					JsonObject s = e.getAsJsonObject();
					sprites.put(s.get("id").getAsInt(), str(s, "key"));
				}
				for (JsonElement e : fu.getAsJsonArray("tiles")) {
					JsonObject t = e.getAsJsonObject();
					int si = t.has("sprite") ? t.get("sprite").getAsInt() : -1;
					FurnTile ft = new FurnTile(t.get("index").getAsInt(), sprites.get(si), str(t, "availability"),
							t.get("blocker").getAsBoolean(), t.has("noWalls") && t.get("noWalls").getAsBoolean());
					tiles.put(ft.index(), ft);
				}
				Map<Integer, String> groupNames = new HashMap<>();
				for (JsonElement e : fu.getAsJsonArray("groups")) {
					JsonObject g = e.getAsJsonObject();
					groupNames.put(g.get("index").getAsInt(), str(g, "name"));
				}
				for (JsonElement e : fu.getAsJsonArray("items")) {
					JsonObject it = e.getAsJsonObject();
					JsonArray grid = it.getAsJsonArray("tiles");
					int[][] t = new int[grid.size()][];
					for (int y = 0; y < grid.size(); y++) {
						JsonArray row = grid.get(y).getAsJsonArray();
						t[y] = new int[row.size()];
						for (int x = 0; x < row.size(); x++)
							t[y][x] = row.get(x).getAsInt();
					}
					int g = it.get("group").getAsInt();
					FurnItem fi = new FurnItem(it.get("index").getAsInt(), g, groupNames.get(g), it.get("size").getAsInt(),
							it.get("rot").getAsInt(), it.get("w").getAsInt(), it.get("h").getAsInt(), t);
					items.put(fi.index(), fi);
				}
			}
			floor = fl;
		}
	}

	private SyxMap(Path path, JsonObject o) {
		this.path = path;
		city = str(o, "city");
		save = str(o, "save");
		gameVersion = str(o, "gameVersion");
		exported = str(o, "exported");
		width = o.get("width").getAsInt();
		height = o.get("height").getAsInt();
		JsonObject pal = o.getAsJsonObject("palettes");
		groundKeys = strings(pal.getAsJsonArray("ground"));
		floorKeys = strings(pal.getAsJsonArray("floor"));
		terrainKeys = strings(pal.getAsJsonArray("terrain"));
		mineralKeys = strings(pal.getAsJsonArray("mineral"));
		blueprintKeys = strings(pal.getAsJsonArray("blueprint"));
		terrainStructure = new String[terrainKeys.length];
		JsonArray ti = o.getAsJsonArray("terrainInfo");
		for (int i = 0; i < ti.size() && i < terrainKeys.length; i++)
			if (ti.get(i).isJsonObject())
				terrainStructure[i] = str(ti.get(i).getAsJsonObject(), "structure");

		JsonObject l = o.getAsJsonObject("layers");
		ground = bytes(l, "ground");
		floor = bytes(l, "floor");
		floorDegrade = bytes(l, "floorDegrade");
		grass = bytes(l, "grass");
		terrain = bytes(l, "terrain");
		flags = bytes(l, "flags");
		heightStart = bytes(l, "heightStart");
		heightEnd = bytes(l, "heightEnd");
		mineral = bytes(l, "mineral");
		mineralAmount = bytes(l, "mineralAmount");
		furnItem = bytes(l, "furnItem");
		furnTile = bytes(l, "furnTile");
		furnData = bytes(l, "furnData");
		furnRot = bytes(l, "furnRot");
		minimapRGB = bytes(l, "minimapRGB");
		water = l.has("water") ? bytes(l, "water") : null;
		terrainData = shorts(l, "terrainData");
		room = shorts(l, "room");
		roomBlueprint = shorts(l, "roomBlueprint");

		for (JsonElement e : o.getAsJsonArray("rooms")) {
			JsonObject r = e.getAsJsonObject();
			boolean single = r.has("singleton");
			rooms.add(new Room(r.get("id").getAsInt(), str(r, "blueprint"), r.get("bp").getAsInt(),
					single ? 0 : r.get("x1").getAsInt(), single ? 0 : r.get("y1").getAsInt(),
					single ? 0 : r.get("x2").getAsInt(), single ? 0 : r.get("y2").getAsInt(),
					r.has("upgrade") ? r.get("upgrade").getAsInt() : 0, str(r, "name")));
		}
		for (JsonElement e : o.getAsJsonArray("furniture")) {
			JsonObject f = e.getAsJsonObject();
			furniture.add(new Furniture(f.get("bp").getAsInt(), f.get("item").getAsInt(), f.get("x").getAsInt(),
					f.get("y").getAsInt(), f.get("w").getAsInt(), f.get("h").getAsInt(), f.get("rot").getAsInt(),
					f.get("room").getAsInt()));
		}
		if (o.has("people"))
			for (JsonElement e : o.getAsJsonArray("people")) {
				JsonObject p = e.getAsJsonObject();
				people.add(new Person(p.get("x").getAsInt(), p.get("y").getAsInt(), str(p, "name"), str(p, "race"),
						str(p, "type"), p.has("work") ? p.get("work").getAsInt() : 0, str(p, "job"),
						p.has("homeX") ? p.get("homeX").getAsInt() : -1, p.has("homeY") ? p.get("homeY").getAsInt() : -1));
			}
		JsonArray bps = o.getAsJsonArray("blueprints");
		blueprints = new Blueprint[bps.size()];
		for (int i = 0; i < bps.size(); i++)
			blueprints[i] = bps.get(i).isJsonObject() ? new Blueprint(bps.get(i).getAsJsonObject()) : null;
	}

	public static SyxMap load(Path p) throws IOException {
		try (Reader r = new InputStreamReader(new GZIPInputStream(Files.newInputStream(p), 1 << 16), StandardCharsets.UTF_8)) {
			JsonObject o = JsonParser.parseReader(r).getAsJsonObject();
			if (!"syxmap".equals(str(o, "format")))
				throw new IOException("not a syxmap file: " + p);
			return new SyxMap(p, o);
		}
	}

	/* ---------- convenience accessors ---------- */

	public boolean inBounds(int x, int y) {
		return x >= 0 && y >= 0 && x < width && y < height;
	}

	public int idx(int x, int y) {
		return x + y * width;
	}

	public String terrainKey(int i) {
		return terrainKeys[terrain[i] & 0xFF];
	}

	public String structure(int i) {
		return terrainStructure[terrain[i] & 0xFF];
	}

	public String floorKey(int i) {
		return floorKeys[floor[i] & 0xFF];
	}

	public String groundKey(int i) {
		return groundKeys[ground[i] & 0xFF];
	}

	public String mineralKey(int i) {
		return mineralKeys[mineral[i] & 0xFF];
	}

	public int roomId(int i) {
		return room[i] & 0xFFFF;
	}

	public Room roomById(int id) {
		return id > 0 && id <= rooms.size() ? rooms.get(id - 1) : null;
	}

	public Blueprint blueprintAt(int i) {
		int b = roomBlueprint[i] & 0xFFFF;
		return b > 0 && b < blueprints.length ? blueprints[b] : null;
	}

	private java.util.BitSet waterMask;

	/** natural water tiles (lakes, rivers, the sea; bridges over them too), the input for river detection */
	public java.util.BitSet waterMask() {
		if (waterMask == null) {
			waterMask = new java.util.BitSet(width * height);
			for (int i = 0; i < width * height; i++) {
				String t = terrainKey(i);
				if (t != null && t.startsWith("WATER"))
					waterMask.set(i);
			}
		}
		return waterMask;
	}

	/** does this water-room tile hold water? (v1 exports don't say: assume it does) */
	public boolean hasWater(int i) {
		return water == null || (water[i] & 0xFF) > 1;
	}

	public boolean flag(int i, int f) {
		return (flags[i] & f) != 0;
	}

	public int terrainData(int i) {
		return terrainData[i] & 0xFFFF;
	}

	/**
	 * Bounding box (inclusive) of everything built by the player: rooms, floors, buildings, fences. The main road
	 * (_MAIN_ROAD, which runs to the map edge for trade) doesn't count.
	 */
	public int[] cityBounds() {
		int x0 = width, y0 = height, x1 = -1, y1 = -1;
		for (int y = 0; y < height; y++)
			for (int x = 0; x < width; x++) {
				int i = idx(x, y);
				String t = terrainKey(i);
				String f = floorKey(i);
				boolean playerFloor = f != null && !f.startsWith("_MAIN");
				if (room[i] != 0 || playerFloor || t.startsWith("BUILDING") || t.startsWith("FENCE")
						|| t.startsWith("FORTIFICATION")) {
					x0 = Math.min(x0, x);
					y0 = Math.min(y0, y);
					x1 = Math.max(x1, x);
					y1 = Math.max(y1, y);
				}
			}
		if (x1 < 0)
			return new int[] { 0, 0, width - 1, height - 1 };
		return new int[] { x0, y0, x1, y1 };
	}

	/* ---------- json helpers ---------- */

	static String str(JsonObject o, String k) {
		return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : null;
	}

	private static String[] strings(JsonArray a) {
		String[] s = new String[a.size()];
		for (int i = 0; i < s.length; i++)
			s[i] = a.get(i).isJsonNull() ? null : a.get(i).getAsString();
		return s;
	}

	private static byte[] bytes(JsonObject layers, String k) {
		return Base64.getDecoder().decode(layers.getAsJsonObject(k).get("data").getAsString());
	}

	private static short[] shorts(JsonObject layers, String k) {
		byte[] b = bytes(layers, k);
		short[] s = new short[b.length / 2];
		for (int i = 0; i < s.length; i++)
			s[i] = (short) ((b[i * 2] & 0xFF) | (b[i * 2 + 1] << 8));
		return s;
	}
}
