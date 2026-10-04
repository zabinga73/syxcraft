package dev.sos2mc.syxcraft.place;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import dev.sos2mc.syxcraft.map.SyxMap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Turns a SyxMap + settings into Minecraft blocks, one block column at a time. Tile analysis (what each tile is,
 * distances to shores/edges, door directions, roof distance field) is precomputed here; the column pass is
 * {@link #column}, furniture/trees come later from {@link Details}.
 *
 * Coordinates: tile (tx,ty) -> blocks x = X0 + (tx-tx0)*s + u, z = Z0 + (ty-ty0)*s + v. SoS +y is south = MC +z.
 * B is the y of the ground surface block; walkable space starts at B+1.
 */
public final class CityPlan {

	// tile kinds
	static final byte OPEN = 0, WATER_SHALLOW = 1, WATER_DEEP = 2, BRIDGE = 3, MOUNTAIN = 4, CAVE = 5, ROCK = 6,
			TREE = 7, BUSH = 8, FLOWER = 9, MUSHROOM = 10, DECOR = 11, GROWABLE = 12, FENCE = 13, FORT = 14,
			FORT_BROKEN = 15, STAIRS = 16, WALL = 17, WALL_BROKEN = 18, INTERIOR = 19, DOOR = 20;

	public final SyxMap map;
	public final PlaceSettings st;
	public final Palette pal;
	public final int s, H;
	/** region in tiles, inclusive */
	public final int tx0, ty0, tx1, ty1;
	/** region size in blocks */
	public final int bw, bh;
	/** world block coords of the region's NW corner */
	public final int X0, Z0;
	public int B;

	final byte[] kind;
	/** tile distance to the nearest tile of a different kind group (water depth, mountain height) */
	final int[] dist;
	/** door tiles: direction index (0 N,1 E,2 S,3 W) towards the outside, -1 otherwise */
	final byte[] doorDir;
	/** quarry pit tiles: mineral key, null otherwise */
	final String[] pit;
	/** per region block: Chebyshev distance to the outside of its building, -1 = not a building */
	int[] roofDist;
	/** per region block: which connected building it belongs to (-1 = none); per building: extra height and roof wood */
	private int[] buildingOf;
	private int[] buildingExtra;
	private String[] buildingWood;
	static final String[] WOODS = { "oak", "spruce", "birch", "jungle", "acacia", "dark_oak", "mangrove", "cherry" };
	/** per tile: distance (8-way, capped) to the nearest settled tile or water; where topography may lift ground */
	final int[] openDist;
	/** per region block: how far topography moved the ground from B (filled in by the column pass) */
	final int[] lift;
	private final long seed;
	/** per quarry room: the block column that gets the ladder */
	final Map<Integer, long[]> ladders = new HashMap<>();

	static final int[] DX = { 0, 1, 0, -1 }, DY = { -1, 0, 1, 0 };
	static final String[] DIR = { "north", "east", "south", "west" };

	public CityPlan(SyxMap map, PlaceSettings st, Palette pal, int centreX, int centreZ) {
		this.map = map;
		this.st = st;
		this.pal = pal;
		this.s = Math.max(1, st.scale);
		this.H = st.interiorHeight();
		if (st.area == PlaceSettings.Area.WHOLE_MAP) {
			tx0 = 0;
			ty0 = 0;
			tx1 = map.width - 1;
			ty1 = map.height - 1;
		} else {
			int[] b = map.cityBounds();
			tx0 = Math.max(0, b[0] - st.margin);
			ty0 = Math.max(0, b[1] - st.margin);
			tx1 = Math.min(map.width - 1, b[2] + st.margin);
			ty1 = Math.min(map.height - 1, b[3] + st.margin);
		}
		bw = (tx1 - tx0 + 1) * s;
		bh = (ty1 - ty0 + 1) * s;
		X0 = centreX - bw / 2;
		Z0 = centreZ - bh / 2;

		int n = map.width * map.height;
		kind = new byte[n];
		doorDir = new byte[n];
		pit = new String[n];
		for (int i = 0; i < n; i++)
			kind[i] = classify(map.terrainKey(i));
		dist = distances();
		for (int ty = 0; ty < map.height; ty++)
			for (int tx = 0; tx < map.width; tx++)
				doorDir[map.idx(tx, ty)] = (byte) (kind[map.idx(tx, ty)] == DOOR ? outward(tx, ty) : -1);
		findQuarries();
		openDist = openDistances();
		lift = new int[bw * bh];
		String name = map.save != null ? map.save : map.city != null ? map.city : "";
		seed = name.hashCode() * 0x9E3779B97F4A7C15L;
	}

	/* ------------------------------------------------------------------ */
	/* topography */

	static final int TOPO_FLAT_NEAR = 2, TOPO_RAMP = 10, TOPO_CAP = 64;

	/**
	 * what keeps the ground flat at B: rooms, buildings, fences, fortifications, water. Roads and paving outside
	 * rooms don't count, so they run over the hills like trails and only level out where they reach the town.
	 */
	boolean settled(int i) {
		byte k = kind[i];
		return map.roomId(i) != 0 || isBuilding(k) || isWater(k) || k == FENCE || k == FORT || k == FORT_BROKEN
				|| k == STAIRS;
	}

	private int[] openDistances() {
		int w = map.width, h = map.height, n = w * h;
		int[] d = new int[n];
		ArrayDeque<Integer> q = new ArrayDeque<>();
		for (int i = 0; i < n; i++) {
			if (settled(i)) {
				q.add(i);
			} else {
				d[i] = TOPO_CAP;
			}
		}
		while (!q.isEmpty()) {
			int i = q.poll();
			if (d[i] >= TOPO_CAP - 1)
				continue;
			int x = i % w, y = i / w;
			for (int dy = -1; dy <= 1; dy++)
				for (int dx = -1; dx <= 1; dx++) {
					int nx = x + dx, ny = y + dy;
					if (nx < 0 || ny < 0 || nx >= w || ny >= h)
						continue;
					int j = nx + ny * w;
					if (d[j] > d[i] + 1) {
						d[j] = d[i] + 1;
						q.add(j);
					}
				}
		}
		return d;
	}

	/** 0 next to the settlement, rising smoothly to 1 out in open land (block coords relative to the region) */
	double topoWeight(int bx, int bz) {
		// tile distance interpolated between tile centres, so slopes don't step at tile edges
		double fx = (bx + 0.5) / s - 0.5 + tx0, fz = (bz + 0.5) / s - 0.5 + ty0;
		int x0 = (int) Math.floor(fx), z0 = (int) Math.floor(fz);
		double ax = fx - x0, az = fz - z0;
		double d = (1 - az) * ((1 - ax) * od(x0, z0) + ax * od(x0 + 1, z0)) + az * ((1 - ax) * od(x0, z0 + 1) + ax * od(x0 + 1, z0 + 1));
		double t = Math.max(0, Math.min(1, (d - TOPO_FLAT_NEAR) / TOPO_RAMP));
		return t * t * (3 - 2 * t);
	}

	private int od(int tx, int ty) {
		tx = Math.max(0, Math.min(map.width - 1, tx));
		ty = Math.max(0, Math.min(map.height - 1, ty));
		return openDist[map.idx(tx, ty)];
	}

	/** how far to move this column's ground from B; natural = the column's natural ground y (LOCAL only) */
	int topoLift(int bx, int bz, int natural) {
		if (st.topography == PlaceSettings.Topography.FLAT)
			return 0;
		double wgt = topoWeight(bx, bz);
		if (wgt <= 0)
			return 0;
		int A = st.hills, V = st.valleys;
		double target;
		if (st.topography == PlaceSettings.Topography.LOCAL) {
			target = Math.max(-V, Math.min(A, natural - B));
		} else {
			// rolling hills in tile space (same landscape at every scale), mostly rises with shallow hollows
			double tx = bx / (double) s, tz = bz / (double) s;
			double n = 0.65 * noise(tx / 42.0, tz / 42.0, 0) + 0.25 * noise(tx / 17.0, tz / 17.0, 1)
					+ 0.10 * noise(tx / 7.0, tz / 7.0, 2);
			double u = Math.max(0, Math.min(1, n * 0.5 + 0.5)); // 0..1
			target = A * 1.25 * u * u - V * (1 - u) * (1 - u);
			target = Math.max(-V, Math.min(A, target));
		}
		return (int) Math.round(target * wgt);
	}

	/** smooth value noise in -1..1 */
	private double noise(double x, double z, int octave) {
		int x0 = (int) Math.floor(x), z0 = (int) Math.floor(z);
		double fx = x - x0, fz = z - z0;
		fx = fx * fx * (3 - 2 * fx);
		fz = fz * fz * (3 - 2 * fz);
		double a = lattice(x0, z0, octave), b = lattice(x0 + 1, z0, octave), c = lattice(x0, z0 + 1, octave),
				d = lattice(x0 + 1, z0 + 1, octave);
		return (a + (b - a) * fx) * (1 - fz) + (c + (d - c) * fx) * fz;
	}

	private double lattice(int x, int z, int octave) {
		long h = hash(x * 374761393 + octave * 668265263, z * 1442695041 + (int) seed) ^ seed;
		h = hash((int) h, (int) (h >>> 32));
		return (h % 20001) / 10000.0 - 1;
	}

	/** ground surface y of a region block column after topography (B if outside the region or not yet built) */
	/** y of the floor a tile's furniture stands on: the bottom of a quarry pit, otherwise the ground */
	public int floorY(int i) {
		return pit[i] != null ? B - st.quarryDepth : B;
	}

	public int groundY(int bx, int bz) {
		bx = Math.max(0, Math.min(bw - 1, bx));
		bz = Math.max(0, Math.min(bh - 1, bz));
		return B + lift[bx + bz * bw];
	}

	static byte classify(String k) {
		if (k == null)
			return OPEN;
		if (k.equals("WATER_SHALLOW"))
			return WATER_SHALLOW;
		if (k.equals("WATER_DEEP"))
			return WATER_DEEP;
		if (k.equals("WATER_BRIDGE"))
			return BRIDGE;
		if (k.equals("MOUNTAIN"))
			return MOUNTAIN;
		if (k.startsWith("CAVE"))
			return CAVE;
		if (k.equals("ROCK"))
			return ROCK;
		if (k.startsWith("TREE_"))
			return TREE;
		if (k.equals("BUSH"))
			return BUSH;
		if (k.equals("FLOWER"))
			return FLOWER;
		if (k.equals("MUSHROOM"))
			return MUSHROOM;
		if (k.startsWith("DECORD_"))
			return DECOR;
		if (k.startsWith("GROWABLE_"))
			return GROWABLE;
		if (k.startsWith("FENCE_"))
			return FENCE;
		if (k.startsWith("FORTIFICATION_B_"))
			return FORT_BROKEN;
		if (k.startsWith("FORTIFICATION_"))
			return FORT;
		if (k.equals("STAIRS"))
			return STAIRS;
		if (k.startsWith("BUILDING_CEILING")) {
			return k.endsWith("_OPENING") ? DOOR : INTERIOR;
		}
		if (k.startsWith("BUILDING_BROKEN"))
			return WALL_BROKEN;
		if (k.startsWith("BUILDING_"))
			return WALL;
		return OPEN;
	}

	static boolean isWater(byte k) {
		return k == WATER_SHALLOW || k == WATER_DEEP || k == BRIDGE;
	}

	static boolean isBuilding(byte k) {
		return k == WALL || k == WALL_BROKEN || k == INTERIOR || k == DOOR;
	}

	static boolean isMountain(byte k) {
		return k == MOUNTAIN || k == CAVE;
	}

	/** multi-source BFS: water tiles get distance to land, mountain tiles distance to non-mountain */
	private int[] distances() {
		int w = map.width, h = map.height, n = w * h;
		int[] d = new int[n];
		ArrayDeque<Integer> q = new ArrayDeque<>();
		for (int i = 0; i < n; i++) {
			byte k = kind[i];
			if (isWater(k) || isMountain(k)) {
				d[i] = Integer.MAX_VALUE;
			} else {
				d[i] = 0;
			}
		}
		// seeds: tiles adjacent to a different group
		for (int y = 0; y < h; y++)
			for (int x = 0; x < w; x++) {
				int i = x + y * w;
				if (d[i] == 0)
					continue;
				boolean water = isWater(kind[i]);
				for (int k = 0; k < 4; k++) {
					int nx = x + DX[k], ny = y + DY[k];
					if (nx < 0 || ny < 0 || nx >= w || ny >= h)
						continue;
					int j = nx + ny * w;
					if (water ? !isWater(kind[j]) : !isMountain(kind[j])) {
						d[i] = 1;
						q.add(i);
						break;
					}
				}
			}
		while (!q.isEmpty()) {
			int i = q.poll();
			int x = i % w, y = i / w;
			boolean water = isWater(kind[i]);
			for (int k = 0; k < 4; k++) {
				int nx = x + DX[k], ny = y + DY[k];
				if (nx < 0 || ny < 0 || nx >= w || ny >= h)
					continue;
				int j = nx + ny * w;
				if (d[j] == Integer.MAX_VALUE && isWater(kind[j]) == water) {
					d[j] = d[i] + 1;
					q.add(j);
				}
			}
		}
		for (int i = 0; i < n; i++)
			if (d[i] == Integer.MAX_VALUE)
				d[i] = 64; // a group touching nothing (whole-map ocean)
		return d;
	}

	/** direction from a door tile towards the outdoors (the neighbour that isn't part of the building) */
	private int outward(int tx, int ty) {
		for (int k = 0; k < 4; k++) {
			int nx = tx + DX[k], ny = ty + DY[k];
			if (!map.inBounds(nx, ny))
				continue;
			int j = map.idx(nx, ny);
			if (!isBuilding(kind[j]))
				return k;
		}
		return 2;
	}

	private void findQuarries() {
		// mass graves are pits too: a ladder in each, in its first tile (whose north side is outside the pit)
		for (SyxMap.Room r : map.rooms) {
			if (r.blueprint() == null || !r.blueprint().startsWith("_DUMP_CORPSE") || r.x2() <= r.x1())
				continue;
			found:
			for (int ty = r.y1(); ty < r.y2(); ty++)
				for (int tx = r.x1(); tx < r.x2(); tx++)
					if (map.inBounds(tx, ty) && map.roomId(map.idx(tx, ty)) == r.id()) {
						ladders.put(r.id(), new long[] { tx, ty });
						break found;
					}
		}
		if (!st.has(PlaceSettings.QUARRIES))
			return;
		for (SyxMap.Room r : map.rooms) {
			if (r.blueprint() == null || !r.blueprint().startsWith("MINE_") || r.x2() <= r.x1())
				continue;
			String mineral = r.blueprint().substring(5);
			long[] ladder = null;
			for (int ty = r.y1(); ty < r.y2(); ty++)
				for (int tx = r.x1(); tx < r.x2(); tx++) {
					if (!map.inBounds(tx, ty))
						continue;
					int i = map.idx(tx, ty);
					if (map.roomId(i) != r.id())
						continue;
					// storage and auxiliaries stand on the quarry floor, so their tiles are dug too
					pit[i] = mineral;
					if (ladder == null && !hasFurniture(i))
						ladder = new long[] { tx, ty };
				}
			if (ladder != null)
				ladders.put(r.id(), ladder);
		}
	}

	boolean hasFurniture(int i) {
		return map.roomId(i) != 0 && map.blueprintAt(i) != null
				&& map.blueprintAt(i).tiles.containsKey(map.furnTile[i] & 0xFF) && (map.furnTile[i] & 0xFF) != 0;
	}

	/* ------------------------------------------------------------------ */

	public int tileOfBlockX(int bx) {
		return tx0 + bx / s;
	}

	public int tileOfBlockZ(int bz) {
		return ty0 + bz / s;
	}

	public int blockX(int tx) {
		return X0 + (tx - tx0) * s;
	}

	public int blockZ(int ty) {
		return Z0 + (ty - ty0) * s;
	}

	boolean inRegion(int tx, int ty) {
		return tx >= tx0 && ty >= ty0 && tx <= tx1 && ty <= ty1;
	}

	byte kindAt(int tx, int ty) {
		return map.inBounds(tx, ty) ? kind[map.idx(tx, ty)] : OPEN;
	}

	/** Chebyshev distance field over building blocks of the region (for hipped roofs). */
	void computeRoofDistances() {
		int n = bw * bh;
		roofDist = new int[n];
		ArrayDeque<Integer> q = new ArrayDeque<>();
		for (int bz = 0; bz < bh; bz++)
			for (int bx = 0; bx < bw; bx++) {
				int i = bx + bz * bw;
				byte k = kind[map.idx(tileOfBlockX(bx), tileOfBlockZ(bz))];
				if (!isBuilding(k)) {
					roofDist[i] = -1;
					continue;
				}
				roofDist[i] = Integer.MAX_VALUE;
				boolean edge = false;
				for (int dz = -1; dz <= 1 && !edge; dz++)
					for (int dx = -1; dx <= 1 && !edge; dx++) {
						int nx = bx + dx, nz = bz + dz;
						if (nx < 0 || nz < 0 || nx >= bw || nz >= bh)
							edge = true;
						else if (!isBuilding(kind[map.idx(tileOfBlockX(nx), tileOfBlockZ(nz))]))
							edge = true;
					}
				if (edge) {
					roofDist[i] = 0;
					q.add(i);
				}
			}
		while (!q.isEmpty()) {
			int i = q.poll();
			int bx = i % bw, bz = i / bw;
			for (int dz = -1; dz <= 1; dz++)
				for (int dx = -1; dx <= 1; dx++) {
					int nx = bx + dx, nz = bz + dz;
					if (nx < 0 || nz < 0 || nx >= bw || nz >= bh)
						continue;
					int j = nx + nz * bw;
					if (roofDist[j] == Integer.MAX_VALUE) {
						roofDist[j] = roofDist[i] + 1;
						q.add(j);
					}
				}
		}
		labelBuildings();
	}

	/**
	 * Splits the building blocks into connected buildings (each has one roof) and gives each its own extra height
	 * (0..max, most in the lower half) and roof wood.
	 */
	private void labelBuildings() {
		int n = bw * bh;
		buildingOf = new int[n];
		java.util.Arrays.fill(buildingOf, -1);
		List<Integer> extra = new ArrayList<>();
		List<String> wood = new ArrayList<>();
		int max = st.maxExtraHeight();
		ArrayDeque<Integer> q = new ArrayDeque<>();
		for (int start = 0; start < n; start++) {
			if (roofDist[start] < 0 || buildingOf[start] >= 0)
				continue;
			int id = extra.size();
			long h = hash(start % bw + X0, start / bw + Z0) ^ seed;
			double u = ((h >>> 11) % 10000) / 10000.0;
			extra.add((int) Math.round(max * u * u));
			wood.add(WOODS[(int) ((h >>> 3) % WOODS.length)]);
			buildingOf[start] = id;
			q.add(start);
			while (!q.isEmpty()) {
				int i = q.poll(), x = i % bw, z = i / bw;
				for (int k = 0; k < 4; k++) {
					int nx = x + DX[k], nz = z + DY[k];
					if (nx < 0 || nz < 0 || nx >= bw || nz >= bh)
						continue;
					int j = nx + nz * bw;
					if (roofDist[j] >= 0 && buildingOf[j] < 0) {
						buildingOf[j] = id;
						q.add(j);
					}
				}
			}
		}
		buildingExtra = extra.stream().mapToInt(Integer::intValue).toArray();
		buildingWood = wood.toArray(new String[0]);
	}

	/** interior height of the building at this region block (H outside buildings) */
	public int heightAt(int bx, int bz) {
		if (buildingOf == null || bx < 0 || bz < 0 || bx >= bw || bz >= bh)
			return H;
		int b = buildingOf[bx + bz * bw];
		return b < 0 ? H : H + buildingExtra[b];
	}

	/** the tallest interior height of any building */
	public int maxHeight() {
		int m = 0;
		if (buildingExtra != null)
			for (int e : buildingExtra)
				m = Math.max(m, e);
		return H + m;
	}

	/** a roof block id in this building's random wood, when the option is on and the roof is wooden */
	private String roofWood(String id, int bx, int bz) {
		if (!st.roofWoods || buildingOf == null || id == null)
			return id;
		int b = buildingOf[bx + bz * bw];
		if (b < 0)
			return id;
		for (String w : WOODS)
			for (String kind : new String[] { "_stairs", "_planks" })
				if (id.equals("minecraft:" + w + kind))
					return "minecraft:" + buildingWood[b] + kind;
		return id;
	}

	/** a lantern hanging at the usual height; in a taller building it hangs on a chain from the ceiling */
	public void hangLantern(BlockState[] col, int bx, int bz) {
		int hb = heightAt(bx, bz);
		for (int y = B + H + 1; y <= B + hb; y++)
			put(col, y, Palette.parse("minecraft:iron_chain"));
		put(col, B + H, Palette.parse("minecraft:lantern[hanging=true]"));
	}

	public int roofDistAt(int bx, int bz) {
		if (bx < 0 || bz < 0 || bx >= bw || bz >= bh)
			return -1;
		return roofDist[bx + bz * bw];
	}

	/* ------------------------------------------------------------------ */
	/* column pass */

	static final int BELOW = 48, ABOVE = 64;

	/** Build one block column inside the region. bx/bz are region-relative block coords. */
	void column(WorldWriter w, int bx, int bz) {
		final int tx = tileOfBlockX(bx), ty = tileOfBlockZ(bz);
		final int u = bx % s, v = bz % s;
		final int i = map.idx(tx, ty);
		final int X = X0 + bx, Z = Z0 + bz;
		final byte k = kind[i];
		final long h = hash(X, Z);

		BlockState[] col = new BlockState[BELOW + ABOVE];
		int top = B; // highest y this column builds up to

		boolean terrain = st.has(PlaceSettings.TERRAIN);
		boolean buildings = st.has(PlaceSettings.BUILDINGS);

		// ground surface and soil
		BlockState surface = surfaceBlock(i, h);
		put(col, B, surface);
		put(col, B - 1, pal.get("ground.subsoil"));
		put(col, B - 2, pal.get("ground.subsoil"));

		switch (k) {
		case WATER_SHALLOW, WATER_DEEP, BRIDGE -> {
			if (st.has(PlaceSettings.WATER)) {
				int depth = k == WATER_SHALLOW ? 2 : Math.min(14, 3 + dist[i] / 2);
				put(col, B, k == BRIDGE ? pal.get("bridge") : Blocks.AIR.defaultBlockState());
				for (int y = B - depth; y <= B - 1; y++)
					put(col, y, pal.get("water"));
				put(col, B - depth - 1, pal.get(k == WATER_SHALLOW ? "waterBed" : "waterBedDeep"));
				put(col, B - depth - 2, pal.get("ground.deep"));
			}
		}
		case MOUNTAIN, CAVE -> {
			if (terrain) {
				int mh = Math.min(48, 3 + dist[i] * (s + 1) + (int) (h & 1));
				for (int y = B; y <= B + mh; y++)
					put(col, y, ore(i, h, y) ? oreBlock(map.mineralKey(i)) : pal.get("mountain"));
				put(col, B + mh, pal.get("mountainTop"));
				if (k == CAVE)
					for (int y = B + 1; y <= B + H; y++)
						put(col, y, Blocks.AIR.defaultBlockState());
				top = B + mh;
			}
		}
		case ROCK -> {
			if (terrain) {
				int size = (map.terrainData(i) >> 8) & 0xF;
				int rh = 1 + size / 3 - (int) ((h >> 3) & 1);
				for (int y = B + 1; y <= B + rh; y++)
					put(col, y, ((h >> y) & 3) == 0 ? pal.get("rockMoss") : pal.get("rock"));
				top = Math.max(top, B + rh);
			}
		}
		case BUSH -> {
			if (st.has(PlaceSettings.VEGETATION) && (h & 7) < 6) {
				put(col, B + 1, (map.terrainData(i) & 48) == 48 ? pal.get("bushFlower") : pal.get("bush"));
				top = B + 1;
			}
		}
		case FLOWER -> {
			if (st.has(PlaceSettings.VEGETATION) && (h & 3) != 0) {
				put(col, B + 1, Blocks1.flower(map.terrainData(i) + (int) (h >> 5 & 3)));
				top = B + 1;
			}
		}
		case MUSHROOM -> {
			put(col, B, Palette.parse("minecraft:podzol"));
			if (st.has(PlaceSettings.VEGETATION) && (h & 3) < 2) {
				put(col, B + 1, Palette.parse((h & 4) == 0 ? "minecraft:brown_mushroom" : "minecraft:red_mushroom"));
				top = B + 1;
			}
		}
		case DECOR -> {
			if (st.has(PlaceSettings.VEGETATION) && (h & 3) == 0) {
				String key = map.terrainKey(i);
				String p = key.contains("INFERTILE") ? "minecraft:dead_bush"
						: key.contains("BEACH") ? "minecraft:dead_bush" : key.contains("WOOD") ? "minecraft:fern" : "minecraft:short_grass";
				put(col, B + 1, Palette.parse(p));
				top = B + 1;
			}
		}
		case GROWABLE -> {
			if (st.has(PlaceSettings.VEGETATION)) {
				String crop = Blocks1.wildCrop(map.terrainKey(i).substring(9), h);
				if (crop.contains("wheat") || crop.contains("carrots") || crop.contains("potatoes") || crop.contains("beetroots"))
					put(col, B, Palette.parse("minecraft:farmland[moisture=7]"));
				put(col, B + 1, Palette.parse(crop));
				top = B + 1;
			}
		}
		case FENCE -> {
			if (buildings) {
				String fk = map.terrainKey(i).substring(6);
				boolean line = (u == 0 && v == 0) || (u > 0 && v == 0 && kindAt(tx + 1, ty) == FENCE)
						|| (v > 0 && u == 0 && kindAt(tx, ty + 1) == FENCE);
				if (line) {
					put(col, B + 1, pal.get("fence." + fk, "fence.default"));
					top = B + 1;
				}
			}
		}
		case FORT, FORT_BROKEN, STAIRS -> {
			if (buildings) {
				String fk = map.terrainKey(i).replace("FORTIFICATION_B_", "").replace("FORTIFICATION_", "");
				int fh = k == FORT ? H + 4 : (H + 4) / 2;
				for (int y = B; y <= B + fh; y++)
					put(col, y, pal.get("fort." + fk, "fort.default"));
				if (k == FORT && ((X + Z) & 1) == 0)
					put(col, B + fh + 1, pal.get("fortTop." + fk, "fortTop.default"));
				top = B + fh + 1;
			}
		}
		case WALL, WALL_BROKEN, INTERIOR, DOOR -> {
			if (buildings)
				top = building(col, i, tx, ty, u, v, bx, bz, X, Z, k, h);
		}
		case TREE -> {
			if (map.groundKey(i) != null && (h & 7) == 0)
				put(col, B, Palette.parse("minecraft:podzol"));
		}
		default -> {
			int rt = roomGround(col, i, tx, ty, bx, bz, X, Z, h);
			if (rt >= 0) {
				top = Math.max(top, rt);
				break;
			}
			// open ground: worn grass, plants
			if (st.has(PlaceSettings.VEGETATION) && map.floor[i] == 0 && map.roomId(i) == 0 && surface.is(Blocks.GRASS_BLOCK)) {
				int g = map.grass[i];
				if (((h >> 7) & 15) < g / 3) {
					put(col, B + 1, Palette.parse(((h >> 12) & 15) == 0 ? "minecraft:tall_grass[half=lower]" : "minecraft:short_grass"));
					top = B + 1;
				}
			}
		}
		}

		// quarries
		String mineral = pit[i];
		if (mineral != null) {
			int D = st.quarryDepth;
			for (int y = B - D + 1; y <= B; y++)
				put(col, y, Blocks.AIR.defaultBlockState());
			put(col, B - D, Palette.parse(mineral.equals("CLAY") ? "minecraft:mud" : "minecraft:gravel"));
			put(col, B - D - 1, pal.get("ground.deep"));
			long[] lad = ladders.get(map.roomId(i));
			if (lad != null && lad[0] == tx && lad[1] == ty && u == 0 && v == 0) {
				// ladder against the north side of the pit, facing south into it
				for (int y = B - D + 1; y <= B; y++)
					put(col, y, Palette.parse("minecraft:ladder[facing=south]"));
			}
		} else if (buildings && !isGrave(i) && graveNeighbour(bx, bz)) {
			// earth walls around a mass grave pit
			for (int y = B - st.quarryDepth; y <= B - 1; y++)
				put(col, y, ((hash(X, y * 31 + Z) & 3) == 0) ? Palette.parse("minecraft:coarse_dirt") : pal.get(y > B - 3 ? "ground.subsoil" : "ground.deep"));
		} else if (st.has(PlaceSettings.QUARRIES)) {
			String lining = pitNeighbourMineral(bx, bz);
			if (lining != null) {
				int D = st.quarryDepth;
				for (int y = B - D; y <= B - 1; y++)
					put(col, y, ((hash(X, y * 31 + Z) & 3) == 0) ? oreBlock(lining) : pal.get(y > B - 3 ? "ground.subsoil" : "ground.deep"));
			}
		}

		// building roofs (also applied over walls/doors/interiors)
		if (buildings && st.has(PlaceSettings.ROOFS) && roofDist != null && isBuilding(k))
			top = Math.max(top, roof(col, bx, bz, i, h));

		// topography: open land rides up or down as a whole column; the settlement itself stays at B
		int dh = 0;
		if (terrain && st.topography != PlaceSettings.Topography.FLAT) {
			dh = topoLift(bx, bz, st.topography == PlaceSettings.Topography.LOCAL ? w.naturalGround(X, Z) : B);
			lift[bx + bz * bw] = dh;
			// a raised column is solid soil all the way down to where its ground used to be (no buried grass)
			for (int y = B - 3; y > B - 3 - dh && y - B + BELOW >= 0; y--)
				if (col[y - B + BELOW] == null)
					put(col, y, pal.get("ground.subsoil"));
		}
		write(w, X, Z, col, top, dh);
	}

	/**
	 * Outdoor rooms that change the ground itself: farms, ponds/pools, canals/drains, the mass grave, construction
	 * sites and pastures. Returns the top built y, or -1 when the tile isn't one of them.
	 */
	private int roomGround(BlockState[] col, int i, int tx, int ty, int bx, int bz, int X, int Z, long h) {
		SyxMap.Blueprint bp = map.blueprintAt(i);
		if (bp == null || bp.key == null || !st.has(PlaceSettings.BUILDINGS))
			return -1;
		String key = bp.key;
		BlockState air = Blocks.AIR.defaultBlockState();
		if (key.startsWith("FARM_")) {
			String crop = farmCrop(key.substring(5), X, Z);
			boolean farmland = crop.contains("wheat") || crop.contains("carrots") || crop.contains("potatoes") || crop.contains("beetroots");
			if (farmland && Math.floorMod(X, 9) == 4 && Math.floorMod(Z, 9) == 4) {
				put(col, B, pal.get("water")); // hydration, like a vanilla farm
				return B;
			}
			put(col, B, Palette.parse(farmland ? "minecraft:farmland[moisture=7]" : crop.contains("mushroom") ? "minecraft:podzol" : "minecraft:grass_block"));
			put(col, B + 1, Palette.parse(crop));
			return B + 1;
		}
		if (key.startsWith("POOL_") && roomEdge(bx, bz)) {
			// a little wooden rim and fence, so a pond by a lake doesn't read as part of the lake
			put(col, B, Palette.parse("minecraft:spruce_planks"));
			put(col, B - 1, pal.get("ground.subsoil"));
			put(col, B + 1, Palette.parse("minecraft:spruce_fence"));
			return B + 1;
		}
		if (key.startsWith("POOL_") || key.startsWith("_WATERCANAL") || key.startsWith("_WATERDRAIN")) {
			boolean canal = !key.startsWith("POOL_");
			boolean full = map.hasWater(i);
			int depth = canal ? 1 : 2;
			put(col, B, air);
			for (int y = B - depth; y <= B - 1; y++)
				put(col, y, full ? pal.get("water") : air);
			String bed = key.startsWith("POOL_STONE") || canal ? "minecraft:stone_bricks" : full ? "minecraft:clay" : "minecraft:mud";
			put(col, B - depth - 1, Palette.parse(bed));
			if (full && !canal && key.startsWith("POOL_POND") && h % 11 == 0)
				put(col, B, Palette.parse("minecraft:lily_pad"));
			return B;
		}
		if (key.startsWith("_DUMP_CORPSE")) {
			// a pit as deep as the quarries, churned earth with bone mounds at the bottom
			int D = st.quarryDepth, F = B - D;
			for (int y = F + 1; y <= B; y++)
				put(col, y, air);
			put(col, F, Palette.parse((h & 3) == 0 ? "minecraft:rooted_dirt" : "minecraft:coarse_dirt"));
			put(col, F - 1, pal.get("ground.subsoil"));
			long[] lad = ladders.get(map.roomId(i));
			if (lad != null && lad[0] == tx && lad[1] == ty && bx % s == 0 && bz % s == 0) {
				for (int y = F + 1; y <= B; y++)
					put(col, y, Palette.parse("minecraft:ladder[facing=south]"));
			} else if (h % 9 == 0) {
				put(col, F + 1, Palette.parse("minecraft:bone_block"));
			} else if (h % 41 == 1) {
				put(col, F + 1, Palette.parse("minecraft:skeleton_skull[rotation=" + (h >> 8) % 16 + "]"));
			}
			return B;
		}
		if (key.startsWith("_CONSTRUCTION")) {
			// a building site: bare ground, scaffolding along the edges, piles of material
			put(col, B, Palette.parse((h & 3) == 0 ? "minecraft:gravel" : "minecraft:coarse_dirt"));
			if (roomEdge(bx, bz) && Math.floorMod(X + Z, 4) == 0) {
				for (int y = B + 1; y <= B + 3; y++)
					put(col, y, Palette.parse("minecraft:scaffolding[distance=0,bottom=false]"));
				return B + 3;
			}
			if (h % 29 == 0) {
				String[] piles = { "minecraft:oak_planks", "minecraft:cobblestone", "minecraft:barrel[facing=up]", "minecraft:stripped_oak_log", "minecraft:bricks" };
				String pile = piles[(int) ((h >> 6) % piles.length)];
				put(col, B + 1, Palette.parse(pile));
				if ((h & 64) != 0)
					put(col, B + 2, Palette.parse(pile));
				return B + 2;
			}
			return B;
		}
		if (key.startsWith("_STOCKADE")) {
			// trodden yard inside a palisade of spruce logs; the gate (the room's entrance item) is left open
			put(col, B, Palette.parse((h & 3) == 0 ? "minecraft:coarse_dirt" : (h & 3) == 1 ? "minecraft:dirt_path" : "minecraft:dirt"));
			if (roomEdge(bx, bz) && !hasFurniture(i)) {
				int top = B + H + 1;
				for (int y = B + 1; y < top; y++)
					put(col, y, Palette.parse("minecraft:spruce_log"));
				put(col, top, Palette.parse("minecraft:spruce_fence"));
				return top;
			}
			return B;
		}
		if (key.startsWith("FIGHTPIT_")) {
			// the arena floor (and the ground under the stands, which Details builds)
			put(col, B, Palette.parse("minecraft:sand"));
			return B;
		}
		if (key.startsWith("PASTURE_")) {
			put(col, B, Palette.parse("minecraft:grass_block"));
			if (roomEdge(bx, bz)) {
				put(col, B + 1, Palette.parse("minecraft:oak_fence"));
				return B + 1;
			}
			return B;
		}
		return -1;
	}

	/** is this block at the edge of its room (a 4-neighbour belongs to another room or none)? */
	private boolean roomEdge(int bx, int bz) {
		int id = map.roomId(map.idx(tileOfBlockX(bx), tileOfBlockZ(bz)));
		for (int k = 0; k < 4; k++) {
			int nx = bx + DX[k], nz = bz + DY[k];
			if (nx < 0 || nz < 0 || nx >= bw || nz >= bh)
				return true;
			if (map.roomId(map.idx(tileOfBlockX(nx), tileOfBlockZ(nz))) != id)
				return true;
		}
		return false;
	}

	/** fully grown crop for a farm type, vegetables in alternating rows */
	static String farmCrop(String type, int X, int Z) {
		return switch (type) {
		case "GRAIN" -> "minecraft:wheat[age=7]";
		case "VEG" -> switch (Math.floorMod(X, 3)) {
			case 0 -> "minecraft:carrots[age=7]";
			case 1 -> "minecraft:potatoes[age=7]";
			default -> "minecraft:beetroots[age=3]";
			};
		case "FRUIT" -> "minecraft:sweet_berry_bush[age=3]";
		case "MUSHROOM" -> Math.floorMod(X + Z, 2) == 0 ? "minecraft:brown_mushroom" : "minecraft:red_mushroom";
		case "COTTON" -> "minecraft:wheat[age=6]";
		case "HERB" -> "minecraft:fern";
		case "SPICES" -> "minecraft:beetroots[age=3]";
		default -> "minecraft:wheat[age=7]";
		};
	}

	/** walls, doors and interiors of buildings; returns the top built y (before the roof) */
	private int building(BlockState[] col, int i, int tx, int ty, int u, int v, int bx, int bz, int X, int Z, byte k, long h) {
		String sk = structKey(map.structure(i));
		BlockState wall = pal.get("wall." + sk, "wall.default");
		final int H = heightAt(bx, bz); // this building's own interior height (height variety)
		switch (k) {
		case WALL, WALL_BROKEN -> {
			int wh = k == WALL ? H : Math.max(1, H / 2);
			put(col, B, pal.get("wallBase." + sk, "wallBase.default"));
			boolean corner = isCorner(tx, ty);
			int win = k == WALL && !corner ? windowDir(tx, ty) : -1;
			for (int y = B + 1; y <= B + wh; y++) {
				BlockState b = corner ? pillar(sk) : wall;
				if (k == WALL_BROKEN && ((h >> y) & 3) == 0)
					b = Palette.parse("minecraft:mossy_cobblestone");
				// windows in rows, one per storey of the standard height, so tall walls aren't one glass strip
				int storey = this.H, pos = (y - B - 1) % storey + 1;
				if (win >= 0 && pos >= 2 && pos <= Math.max(2, storey - 1) && y <= B + Math.max(2, H - 1))
					// glass only in the outermost layer of a thick wall; the layers behind it stay open
					b = outerLayer(win, u, v) ? pal.get("window." + sk, "window.default") : Blocks.AIR.defaultBlockState();
				put(col, y, b);
			}
			return B + wh;
		}
		case INTERIOR -> {
			put(col, B, floorOrIndoors(i, h));
			for (int y = B + 1; y <= B + H; y++)
				put(col, y, Blocks.AIR.defaultBlockState());
			// hanging lanterns on a 5-block grid keep every interior lit (no mob spawning indoors)
			if (Math.floorMod(X, 5) == 2 && Math.floorMod(Z, 5) == 2)
				hangLantern(col, bx, bz);
			return B + H;
		}
		case DOOR -> {
			put(col, B, floorOrIndoors(i, h));
			int d = doorDir[i];
			int along = (d == 0 || d == 2) ? u : v;
			if (!doorLeaf(tx, ty, along)) {
				// at scale 3+ the doorway is wider than a double door: wall up the rest
				for (int y = B + 1; y <= B + H; y++)
					put(col, y, wall);
				return B + H;
			}
			// door leaf on the outer layer of the (s-thick) wall, the rest is an open passage
			boolean outer = outerLayer(d, u, v);
			for (int y = B + 1; y <= B + H; y++)
				put(col, y, y >= B + 3 ? wall : Blocks.AIR.defaultBlockState());
			if (outer) {
				// doors pop off anything that isn't a full block (dirt path, farmland)
				put(col, B, sturdy(floorOrIndoors(i, h)));
				String facing = DIR[(d + 2) % 4]; // a door faces the side you open it from: inside
				String hinge = hinge(tx, ty, d, u, v);
				String door = pal.has("door." + sk) ? blockId("door." + sk) : "minecraft:oak_door";
				put(col, B + 1, Palette.parse(door + "[half=lower,facing=" + facing + ",hinge=" + hinge + "]"));
				put(col, B + 2, Palette.parse(door + "[half=upper,facing=" + facing + ",hinge=" + hinge + "]"));
			}
			return B + H;
		}
		default -> {
			return B;
		}
		}
	}

	/** hipped (or flat) roof from the building distance field; returns the top y */
	private int roof(BlockState[] col, int bx, int bz, int i, long h) {
		String sk = structKey(map.structure(i));
		int R = B + heightAt(bx, bz) + 1;
		int d = roofDistAt(bx, bz);
		BlockState roofBlock = pal.get("roofBlock." + sk, "roofBlock.default");
		String rbId = idOf(roofBlock), rbWood = roofWood(rbId, bx, bz);
		if (!rbWood.equals(rbId))
			roofBlock = Palette.parse(rbWood); // only re-read when the wood changed, so palette block states survive
		String roofStairs = roofWood(blockId("roof." + sk), bx, bz);
		if (d < 0)
			return R;
		if (st.roof == PlaceSettings.Roof.FLAT) {
			put(col, R, roofBlock);
			if (d == 0) {
				put(col, R + 1, pal.get("wall." + sk, "wall.default"));
				return R + 1;
			}
			return R;
		}
		int maxRise = s >= 2 ? 6 : 4;
		int r = Math.min(d, maxRise);
		if (d > 0)
			put(col, R, pal.get("ceiling." + sk, "ceiling.default"));
		// the space under the roof slope is filled solid: a dark hollow attic is a mob spawner
		for (int y = R + 1; y < R + r; y++)
			put(col, y, roofBlock);
		// stairs face the neighbour that's higher up the slope
		int best = -1, bestR = r;
		for (int k = 0; k < 4; k++) {
			int nd = roofDistAt(bx + DX[k], bz + DY[k]);
			int nr = Math.min(Math.max(nd, -1), maxRise);
			if (nr > bestR) {
				bestR = nr;
				best = k;
			}
		}
		if (best >= 0)
			put(col, R + r, Palette.parse(roofStairs + "[facing=" + DIR[best] + "]"));
		else
			put(col, R + r, roofBlock);
		return R + r;
	}

	/* ------------------------------------------------------------------ */

	private BlockState surfaceBlock(int i, long h) {
		if (map.floor[i] != 0) {
			String fk = map.floorKey(i);
			if ((map.floorDegrade[i] & 0xFF) > 160 && ((h >> 9) & 3) == 0)
				return pal.get("floor.worn");
			return pal.get("floor." + fk, "floor.DIRT");
		}
		if (isBuilding(kind[i]))
			return pal.get("ground.indoors");
		String g = map.groundKey(i);
		BlockState base = pal.get("ground." + g, "ground.NORMAL");
		if (base.is(Blocks.GRASS_BLOCK)) {
			int grass = map.grass[i];
			// worn grass: little growth left where people walk a lot
			if (grass <= 2 || (grass <= 6 && ((h >> 4) & 7) >= grass))
				return pal.get("ground.worn"); // trodden paths: shovelled dirt path
			if ("FOREST".equals(g) && ((h >> 6) & 15) == 0)
				return Palette.parse("minecraft:podzol");
		}
		if ("ROCK".equals(g) && ((h >> 3) & 3) == 0)
			return Palette.parse(((h >> 5) & 1) == 0 ? "minecraft:andesite" : "minecraft:gravel");
		if ("INFERTILE".equals(g) && ((h >> 3) & 3) == 0)
			return Palette.parse("minecraft:gravel");
		return base;
	}

	private BlockState floorOrIndoors(int i, long h) {
		if (map.floor[i] != 0)
			return surfaceBlock(i, h);
		SyxMap.Blueprint bp = map.blueprintAt(i);
		if (bp != null && bp.floor != null && pal.has("floor." + bp.floor))
			return pal.get("floor." + bp.floor);
		return pal.get("ground.indoors");
	}

	private boolean isCorner(int tx, int ty) {
		boolean n = isWallish(tx, ty - 1), so = isWallish(tx, ty + 1), e = isWallish(tx + 1, ty), w = isWallish(tx - 1, ty);
		return !((n && so && !e && !w) || (e && w && !n && !so));
	}

	private boolean isWallish(int tx, int ty) {
		byte k = kindAt(tx, ty);
		return k == WALL || k == WALL_BROKEN || k == DOOR;
	}

	/** straight wall between inside and outside, every third tile along the wall */
	/** is sub-block (u, v) on the wall face that points in direction d (0 N, 1 E, 2 S, 3 W)? */
	boolean outerLayer(int d, int u, int v) {
		return s == 1 || switch (d) {
		case 0 -> v == 0;
		case 1 -> u == s - 1;
		case 2 -> v == s - 1;
		default -> u == 0;
		};
	}

	/**
	 * Hinges for the doors across one doorway tile, matching vanilla double doors: looking along the door's facing,
	 * the door on the counter-clockwise side hinges left, the one on the clockwise side right, so both swing open
	 * towards the jambs.
	 */
	String hinge(int tx, int ty, int outward, int u, int v) {
		int facing = (outward + 2) % 4;
		int along = (outward == 0 || outward == 2) ? u : v; // position along the wall, increasing east / south
		int[] run = doorRun(tx, ty);
		if (run[1] * s == 1)
			return "left";
		boolean lowSide = run[0] * s + along == doorStart(run[1]);
		boolean clockwiseIsIncreasing = facing == 0 || facing == 1; // facing N: clockwise = east (+x); E: south (+z)
		return lowSide == clockwiseIsIncreasing ? "left" : "right";
	}

	/**
	 * Adjacent doorway tiles along a wall form one doorway. Returns {index of this tile in the run, run length in
	 * tiles}.
	 */
	int[] doorRun(int tx, int ty) {
		int d = doorDir[map.idx(tx, ty)];
		int ax = (d == 0 || d == 2) ? 1 : 0, ay = 1 - ax; // step along the wall
		int back = 0;
		while (isDoorFacing(tx - (back + 1) * ax, ty - (back + 1) * ay, d))
			back++;
		int fwd = 0;
		while (isDoorFacing(tx + (fwd + 1) * ax, ty + (fwd + 1) * ay, d))
			fwd++;
		return new int[] { back, back + fwd + 1 };
	}

	private boolean isDoorFacing(int tx, int ty, int d) {
		return kindAt(tx, ty) == DOOR && doorDir[map.idx(tx, ty)] == d;
	}

	/** a full block to stand a door on: dirt path and farmland become coarse dirt */
	static BlockState sturdy(BlockState s) {
		if (s.is(Blocks.DIRT_PATH) || s.is(Blocks.FARMLAND))
			return Blocks.COARSE_DIRT.defaultBlockState();
		return s;
	}

	/** first block of the (at most two) door leaves across a doorway of len tiles: centred */
	int doorStart(int len) {
		return Math.max(0, (len * s - 2) / 2);
	}

	/** is this block along the doorway a door leaf? Never more than a double door; the rest is walled up. */
	boolean doorLeaf(int tx, int ty, int along) {
		int[] run = doorRun(tx, ty);
		int pos = run[0] * s + along, start = doorStart(run[1]);
		return run[1] * s == 1 || pos == start || pos == start + 1;
	}

	/**
	 * Window on a straight wall between inside and outside, every third tile, never touching a door or a corner.
	 * Returns the direction towards the outside, or -1.
	 */
	int windowDir(int tx, int ty) {
		if (!windowTile(tx, ty))
			return -1;
		for (int dy = -1; dy <= 1; dy++)
			for (int dx = -1; dx <= 1; dx++)
				if (kindAt(tx + dx, ty + dy) == DOOR)
					return -1;
		boolean eastWest = isWallish(tx - 1, ty) && isWallish(tx + 1, ty);
		if (eastWest)
			return !isBuilding(kindAt(tx, ty - 1)) ? 0 : 2;
		return !isBuilding(kindAt(tx + 1, ty)) ? 1 : 3;
	}

	private boolean windowTile(int tx, int ty) {
		boolean ns = isWallish(tx - 1, ty) && isWallish(tx + 1, ty); // wall runs east-west
		boolean in, out;
		if (ns) {
			in = kindAt(tx, ty - 1) == INTERIOR || kindAt(tx, ty + 1) == INTERIOR;
			out = !isBuilding(kindAt(tx, ty - 1)) || !isBuilding(kindAt(tx, ty + 1));
			return in && out && Math.floorMod(tx, 3) == 1;
		}
		in = kindAt(tx - 1, ty) == INTERIOR || kindAt(tx + 1, ty) == INTERIOR;
		out = !isBuilding(kindAt(tx - 1, ty)) || !isBuilding(kindAt(tx + 1, ty));
		return in && out && Math.floorMod(ty, 3) == 1;
	}

	private String pitNeighbourMineral(int bx, int bz) {
		for (int k = 0; k < 4; k++) {
			int nx = bx + DX[k], nz = bz + DY[k];
			if (nx < 0 || nz < 0 || nx >= bw || nz >= bh)
				continue;
			String m = pit[map.idx(tileOfBlockX(nx), tileOfBlockZ(nz))];
			if (m != null && pit[map.idx(tileOfBlockX(bx), tileOfBlockZ(bz))] == null)
				return m;
		}
		return null;
	}

	boolean isGrave(int i) {
		SyxMap.Blueprint bp = map.blueprintAt(i);
		return bp != null && bp.key != null && bp.key.startsWith("_DUMP_CORPSE");
	}

	private boolean graveNeighbour(int bx, int bz) {
		for (int k = 0; k < 4; k++) {
			int nx = bx + DX[k], nz = bz + DY[k];
			if (nx < 0 || nz < 0 || nx >= bw || nz >= bh)
				continue;
			if (isGrave(map.idx(tileOfBlockX(nx), tileOfBlockZ(nz))))
				return true;
		}
		return false;
	}

	private boolean ore(int i, long h, int y) {
		return map.mineral[i] != 0 && ((h >> (y & 15)) & 3) == 0;
	}

	BlockState oreBlock(String mineral) {
		return pal.get("ore." + mineral, "ore.default");
	}

	BlockState pillar(String sk) {
		BlockState p = pal.get("pillar." + sk, "pillar.default");
		return p;
	}

	static String structKey(String structure) {
		return structure == null ? "default" : structure;
	}

	/** the block id of a palette entry without its properties (to add our own) */
	String blockId(String key) {
		return idOf(pal.get(key));
	}

	static String idOf(BlockState st) {
		return net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(st.getBlock()).toString();
	}

	static void put(BlockState[] col, int y, BlockState s, int B) {
		int idx = y - B + BELOW;
		if (idx >= 0 && idx < col.length)
			col[idx] = s;
	}

	private void put(BlockState[] col, int y, BlockState state) {
		put(col, y, state, B);
	}

	/** write a column: built blocks, filled ground below, cleared space above */
	private void write(WorldWriter w, int X, int Z, BlockState[] col, int top, int dh) {
		final int B = this.B + dh; // the column's own ground level
		top += dh;
		int lowest = Integer.MAX_VALUE;
		for (int idx = 0; idx < col.length; idx++) {
			if (col[idx] == null)
				continue;
			int y = idx - BELOW + B;
			lowest = Math.min(lowest, y);
			w.set(X, y, Z, col[idx]);
		}
		// fill holes under the column (water, caves, air over cliffs) down to solid ground
		if (st.has(PlaceSettings.FILL_BELOW) && lowest != Integer.MAX_VALUE) {
			BlockState deep = pal.get("ground.deep");
			for (int y = lowest - 1; y > lowest - BELOW && y > w.minY(); y--) {
				if (!WorldWriter.soft(w.get(X, y, Z)))
					break;
				w.set(X, y, Z, y >= B - 4 ? pal.get("ground.subsoil") : deep);
			}
		}
		// clear natural terrain, trees and water above what we built
		if (st.has(PlaceSettings.CLEAR_ABOVE)) {
			// all the way up: a capped clear left the tips of tall mountains floating
			int natural = w.anyTop(X, Z);
			for (int y = natural; y > top; y--) {
				int idx = y - B + BELOW;
				if (idx >= 0 && idx < col.length && col[idx] != null)
					continue;
				w.air(X, y, Z);
			}
		}
	}

	/** gentle ramp between the flattened city and the natural terrain around it */
	void blendColumn(WorldWriter w, int X, int Z, int ring, int width) {
		final int B = groundY(X - X0, Z - Z0); // the nearest edge column of the city, after topography
		int nat = w.groundTop(X, Z);
		BlockState natTopState = w.get(X, nat, Z);
		if (!natTopState.getFluidState().isEmpty())
			return; // leave seas and rivers alone
		double t = ring / (double) (width + 1);
		int target = (int) Math.round(B + (nat - B) * t);
		if (target == nat)
			return;
		BlockState surface = natTopState.is(Blocks.GRASS_BLOCK) || natTopState.is(Blocks.DIRT) ? Blocks.GRASS_BLOCK.defaultBlockState() : natTopState;
		if (target < nat) {
			for (int y = w.anyTop(X, Z); y > target; y--)
				w.air(X, y, Z);
			w.set(X, target, Z, surface);
		} else {
			for (int y = nat; y < target; y++)
				w.set(X, y, Z, pal.get("ground.subsoil"));
			w.set(X, target, Z, surface);
		}
	}

	static long hash(int x, int z) {
		long h = x * 0x9E3779B97F4A7C15L + z * 0xC2B2AE3D27D4EB4FL;
		h ^= h >>> 29;
		h *= 0xBF58476D1CE4E5B9L;
		h ^= h >>> 32;
		return h & Long.MAX_VALUE;
	}

	/** small block-choice helpers that don't belong in the palette */
	static final class Blocks1 {
		private static final String[] FLOWERS = { "minecraft:dandelion", "minecraft:poppy", "minecraft:blue_orchid",
				"minecraft:allium", "minecraft:azure_bluet", "minecraft:red_tulip", "minecraft:orange_tulip",
				"minecraft:white_tulip", "minecraft:pink_tulip", "minecraft:oxeye_daisy", "minecraft:cornflower",
				"minecraft:lily_of_the_valley" };

		static BlockState flower(int variant) {
			return Palette.parse(FLOWERS[Math.floorMod(variant, FLOWERS.length)]);
		}

		static String wildCrop(String resource, long h) {
			return switch (resource) {
			case "GRAIN" -> "minecraft:wheat[age=7]";
			case "VEGETABLE" -> (h & 1) == 0 ? "minecraft:carrots[age=7]" : "minecraft:potatoes[age=7]";
			case "FRUIT" -> "minecraft:sweet_berry_bush[age=3]";
			case "MUSHROOM" -> (h & 1) == 0 ? "minecraft:brown_mushroom" : "minecraft:red_mushroom";
			case "OPIATES" -> "minecraft:poppy";
			case "HERB" -> "minecraft:fern";
			case "COTTON" -> "minecraft:white_tulip";
			default -> "minecraft:short_grass";
			};
		}
	}
}
