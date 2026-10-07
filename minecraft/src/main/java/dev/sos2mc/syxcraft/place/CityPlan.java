package dev.sos2mc.syxcraft.place;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import dev.sos2mc.syxcraft.map.SyxMap;
import net.minecraft.core.BlockPos;
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
	/** per building: its roof style (MIXED resolved) and its deepest roof distance (how far its middle is from a wall) */
	private PlaceSettings.Roof[] buildingRoof;
	private int[] buildingDepth;
	/** per region block: water in a farm, placed so every farmland block is within 4 of water in its own farm */
	private java.util.BitSet farmWater;
	/** peaks only: per tile, straight-line distance in tiles from a mountain tile to the nearest non-mountain tile */
	private float[] mountainDist;
	/** crops held back by write() until everything around them is placed */
	private final it.unimi.dsi.fastutil.longs.LongArrayList cropPos = new it.unimi.dsi.fastutil.longs.LongArrayList();
	private final java.util.ArrayList<BlockState> cropState = new java.util.ArrayList<>();
	/** per building: its footprint's bounding box in region blocks (minX, minZ, maxX, maxZ), for domes */
	private int[][] buildingBox;
	private String[] buildingWood;
	static final String[] WOODS = { "oak", "spruce", "birch", "jungle", "acacia", "dark_oak", "mangrove", "cherry" };
	/** per tile: distance (8-way, capped) to the nearest settled tile or water; where topography may lift ground */
	final int[] openDist;
	/** per region block: how far topography moved the ground from B (filled in by the column pass) */
	final int[] lift;
	/** per region block: the highest y the column pass built (Integer.MIN_VALUE until built) */
	final int[] builtTop;
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
		if (st.peaks)
			mountainDist = mountainDistances();
		for (int ty = 0; ty < map.height; ty++)
			for (int tx = 0; tx < map.width; tx++)
				doorDir[map.idx(tx, ty)] = (byte) (kind[map.idx(tx, ty)] == DOOR ? outward(tx, ty) : -1);
		findQuarries();
		openDist = openDistances();
		lift = new int[bw * bh];
		builtTop = new int[bw * bh];
		java.util.Arrays.fill(builtTop, Integer.MIN_VALUE);
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
			// peaks: natural mountains keep their full height (only the world's ceiling stops them)
			target = Math.max(-V, st.peaks ? natural - B : Math.min(A, natural - B));
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

	/** peaks: world y from which mountain tops are snow (give or take a few blocks) */
	static final int SNOW_LINE = 175;

	/**
	 * Height of a Songs of Syx mountain above the ground with peaks on. It rises with the straight-line distance from
	 * the mountain's foot, a little less than linearly so broad ranges get high without walls, and ridged noise splits a
	 * range into separate summits, shoulders and saddles. Near the top of the world it eases off instead of being cut
	 * flat. The slope stays within about two blocks up per block across, so no spikes. dh = the topography's lift here;
	 * returns the height above B.
	 */
	private int peakHeight(WorldWriter w, int bx, int bz, int dh, long h) {
		double fx = (bx + 0.5) / s - 0.5 + tx0, fz = (bz + 0.5) / s - 0.5 + ty0;
		int x0 = (int) Math.floor(fx), z0 = (int) Math.floor(fz);
		double ax = fx - x0, az = fz - z0;
		double d = (1 - az) * ((1 - ax) * md(x0, z0) + ax * md(x0 + 1, z0)) + az * ((1 - ax) * md(x0, z0 + 1) + ax * md(x0 + 1, z0 + 1));
		double db = Math.max(0, d * s); // blocks in from the foot
		double base = 3 + 1.6 * Math.pow(db, 0.9);
		// summits and saddles along a range: ridged noise in tile space, so the same range at every scale
		double r = 1 - Math.abs(noise(fx / 36.0, fz / 36.0, 5));
		double f = 0.55 + 0.6 * r * r + 0.15 * noise(fx / 13.0, fz / 13.0, 6);
		double mh = base * f + (h & 1);
		// out of the land around it (topography): a smooth max, so the foot follows the land and the body the peak
		mh = (dh + mh + Math.sqrt((dh - mh) * (dh - mh) + 64)) / 2;
		// ease into the world's ceiling
		double room = w.maxY() - B - 2, knee = 0.75 * room;
		if (mh > knee)
			mh = knee + (room - knee) * Math.tanh((mh - knee) / (room - knee));
		return Math.max(3, (int) Math.round(mh));
	}

	private float md(int tx, int ty) {
		tx = Math.max(0, Math.min(map.width - 1, tx));
		ty = Math.max(0, Math.min(map.height - 1, ty));
		return mountainDist[map.idx(tx, ty)];
	}

	/** straight-line-ish (chamfer 1 / sqrt 2) distance from each mountain tile to the nearest other tile, in tiles */
	private float[] mountainDistances() {
		int w = map.width, h = map.height;
		float[] d = new float[w * h];
		final float INF = 1e9f, D1 = 1f, D2 = (float) Math.sqrt(2);
		// the edge of the placed area counts as a foot too, so a range cut off there slopes down to it, not a cliff
		for (int i = 0; i < d.length; i++) {
			int x = i % w, y = i / w;
			d[i] = isMountain(kind[i]) && x > tx0 && x < tx1 && y > ty0 && y < ty1 ? INF : 0;
		}
		for (int y = 0; y < h; y++)
			for (int x = 0; x < w; x++) {
				int i = x + y * w;
				if (d[i] == 0)
					continue;
				float v = d[i];
				if (x > 0) v = Math.min(v, d[i - 1] + D1);
				if (y > 0) v = Math.min(v, d[i - w] + D1);
				if (x > 0 && y > 0) v = Math.min(v, d[i - w - 1] + D2);
				if (x < w - 1 && y > 0) v = Math.min(v, d[i - w + 1] + D2);
				d[i] = v;
			}
		for (int y = h - 1; y >= 0; y--)
			for (int x = w - 1; x >= 0; x--) {
				int i = x + y * w;
				if (d[i] == 0)
					continue;
				float v = d[i];
				if (x < w - 1) v = Math.min(v, d[i + 1] + D1);
				if (y < h - 1) v = Math.min(v, d[i + w] + D1);
				if (x < w - 1 && y < h - 1) v = Math.min(v, d[i + w + 1] + D2);
				if (x > 0 && y < h - 1) v = Math.min(v, d[i + w - 1] + D2);
				d[i] = v;
			}
		// a mountain running off the map edge has no foot there: the INF stays only for an all-mountain map
		for (int i = 0; i < d.length; i++)
			if (d[i] >= INF)
				d[i] = 0;
		return d;
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
	/**
	 * Lays out each crop farm's water. Farmland stays wet within 4 blocks of water, so a fixed world grid left thin or
	 * oddly shaped farms with strips out of reach that dried out. Greedy per farm: the first block (row by row) not yet
	 * in reach gets water up to 4 further along and down, kept inside the farm, which brings it and its 9x9 into reach.
	 */
	private void computeFarmWater() {
		farmWater = new java.util.BitSet(bw * bh);
		for (SyxMap.Room r : map.rooms) {
			if (r.blueprint() == null || !r.blueprint().startsWith("FARM_") || !isFarmland(farmCrop(r.blueprint().substring(5), 0, 0)))
				continue;
			int bx0 = (r.x1() - tx0) * s, bz0 = (r.y1() - ty0) * s, bx1 = (r.x2() - tx0 + 1) * s - 1, bz1 = (r.y2() - ty0 + 1) * s - 1;
			bx0 = Math.max(0, bx0);
			bz0 = Math.max(0, bz0);
			bx1 = Math.min(bw - 1, bx1);
			bz1 = Math.min(bh - 1, bz1);
			if (bx0 > bx1 || bz0 > bz1)
				continue;
			int fw = bx1 - bx0 + 1, fh = bz1 - bz0 + 1;
			boolean[] wet = new boolean[fw * fh];
			for (int z = bz0; z <= bz1; z++)
				for (int x = bx0; x <= bx1; x++) {
					if (wet[(x - bx0) + (z - bz0) * fw] || !inRoom(r.id(), x, z))
						continue;
					// the water: as far as 4 along and down (so it covers this block and the most of what's next), but
					// on a block of this farm
					int wx = x, wz = z;
					search: for (int dz = 4; dz >= 0; dz--)
						for (int dx = 4; dx >= -4; dx--)
							if (inRoom(r.id(), x + dx, z + dz)) {
								wx = x + dx;
								wz = z + dz;
								break search;
							}
					farmWater.set(wx + wz * bw);
					for (int zz = Math.max(bz0, wz - 4); zz <= Math.min(bz1, wz + 4); zz++)
						for (int xx = Math.max(bx0, wx - 4); xx <= Math.min(bx1, wx + 4); xx++)
							wet[(xx - bx0) + (zz - bz0) * fw] = true;
				}
		}
	}

	private boolean inRoom(int id, int bx, int bz) {
		return bx >= 0 && bz >= 0 && bx < bw && bz < bh && map.roomId(map.idx(tileOfBlockX(bx), tileOfBlockZ(bz))) == id;
	}

	/**
	 * Plants the crops write() held back, without shape updates, so a crop isn't checked for light before the light
	 * engine has caught up with the new ground (it pops off below light 8). Run after everything else is placed.
	 */
	List<Runnable> plantCrops(WorldWriter w) {
		List<Runnable> jobs = new ArrayList<>();
		for (int from = 0; from < cropPos.size(); from += 4096) {
			int a = from, b = Math.min(cropPos.size(), from + 4096);
			jobs.add(() -> {
				for (int k = a; k < b; k++) {
					long p = cropPos.getLong(k);
					int x = BlockPos.getX(p), y = BlockPos.getY(p), z = BlockPos.getZ(p);
					if (w.get(x, y, z).isAir())
						w.set(x, y, z, cropState.get(k), WorldWriter.FLAGS_RAW);
				}
			});
		}
		jobs.add(() -> {
			cropPos.clear();
			cropState.clear();
		});
		return jobs;
	}

	static boolean isFarmland(String crop) {
		return crop.contains("wheat") || crop.contains("carrots") || crop.contains("potatoes") || crop.contains("beetroots");
	}

	void computeRoofDistances() {
		computeFarmWater();
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
		List<PlaceSettings.Roof> roofs = new ArrayList<>();
		List<Integer> depth = new ArrayList<>();
		List<int[]> boxes = new ArrayList<>();
		PlaceSettings.Roof[] mixed = { PlaceSettings.Roof.HIPPED, PlaceSettings.Roof.POINTED, PlaceSettings.Roof.DOMED };
		int max = st.maxExtraHeight();
		ArrayDeque<Integer> q = new ArrayDeque<>();
		for (int start = 0; start < n; start++) {
			if (roofDist[start] < 0 || buildingOf[start] >= 0)
				continue;
			int id = extra.size();
			long h = hash(start % bw + X0, start / bw + Z0) ^ seed;
			double u = ((h >>> 11) % 10000) / 10000.0;
			extra.add((int) Math.round(max * u * u * u)); // ~80% in the lower half, most near the bottom
			wood.add(WOODS[(int) ((h >>> 3) % WOODS.length)]);
			roofs.add(st.roof == PlaceSettings.Roof.MIXED ? mixed[(int) ((h >>> 23) % mixed.length)] : st.roof);
			int deepest = 0;
			int[] box = { start % bw, start / bw, start % bw, start / bw };
			boxes.add(box);
			buildingOf[start] = id;
			q.add(start);
			while (!q.isEmpty()) {
				int i = q.poll(), x = i % bw, z = i / bw;
				deepest = Math.max(deepest, roofDist[i]);
				box[0] = Math.min(box[0], x);
				box[1] = Math.min(box[1], z);
				box[2] = Math.max(box[2], x);
				box[3] = Math.max(box[3], z);
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
			depth.add(deepest);
		}
		buildingExtra = extra.stream().mapToInt(Integer::intValue).toArray();
		buildingWood = wood.toArray(new String[0]);
		buildingRoof = roofs.toArray(new PlaceSettings.Roof[0]);
		buildingDepth = depth.stream().mapToInt(Integer::intValue).toArray();
		buildingBox = boxes.toArray(new int[0][]);
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

		// peaks can reach the top of the world, so their columns reach that far
		BlockState[] col = new BlockState[BELOW + (st.peaks ? Math.max(ABOVE, w.maxY() - B + 1) : ABOVE)];
		int top = B; // highest y this column builds up to

		boolean terrain = st.has(PlaceSettings.TERRAIN);
		boolean buildings = st.has(PlaceSettings.BUILDINGS);
		// how far topography moves this column (applied in write())
		int dh = 0;
		if (terrain && st.topography != PlaceSettings.Topography.FLAT) {
			dh = topoLift(bx, bz, st.topography == PlaceSettings.Topography.LOCAL ? w.naturalGround(X, Z) : B);
			lift[bx + bz * bw] = dh;
		}
		int writeDh = dh; // a peak builds its own height from B instead

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
				if (st.peaks) {
					// the mountain rises out of the land around it rather than sitting on top of it
					mh = peakHeight(w, bx, bz, dh, h);
					writeDh = 0;
				}
				for (int y = B; y <= B + mh; y++)
					put(col, y, ore(i, h, y) ? oreBlock(map.mineralKey(i), hash(X + y * 7, Z)) : pal.get("mountain"));
				put(col, B + mh, st.peaks && B + mh >= SNOW_LINE + (int) (h % 7) ? pal.get("mountainSnow") : pal.get("mountainTop"));
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
					put(col, y, ((hash(X, y * 31 + Z) & 3) == 0) ? oreBlock(lining, hash(X + y, Z)) : pal.get(y > B - 3 ? "ground.subsoil" : "ground.deep"));
			}
		}

		// building roofs (also applied over walls/doors/interiors)
		if (buildings && st.has(PlaceSettings.ROOFS) && roofDist != null && isBuilding(k))
			top = Math.max(top, roof(col, bx, bz, i, h));

		// topography: open land rides up or down as a whole column; the settlement itself stays at B
		if (writeDh != 0) {
			// a raised column is solid soil all the way down to where its ground used to be (no buried grass)
			for (int y = B - 3; y > B - 3 - writeDh && y - B + BELOW >= 0; y--)
				if (col[y - B + BELOW] == null)
					put(col, y, pal.get("ground.subsoil"));
		}
		write(w, X, Z, col, top, writeDh);
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
			boolean farmland = isFarmland(crop);
			if (farmland && farmWater != null && farmWater.get(bx + bz * bw)) {
				put(col, B, pal.get("water")); // hydration, like a vanilla farm
				return B;
			}
			put(col, B, Palette.parse(farmland ? "minecraft:farmland[moisture=7]" : crop.contains("mushroom") ? "minecraft:podzol" : "minecraft:grass_block"));
			put(col, B + 1, Palette.parse(crop));
			return B + 1;
		}
		if (key.startsWith("POOL_") && roomEdge(bx, bz)) {
			// a rim and fence, so a pond by a lake doesn't read as part of the lake: stone pools get a smooth quartz rim
			// and an andesite wall (there's no polished andesite fence), ponds a wooden rim and fence
			boolean stone = key.startsWith("POOL_STONE");
			put(col, B, Palette.parse(stone ? "minecraft:smooth_quartz" : "minecraft:spruce_planks"));
			put(col, B - 1, pal.get("ground.subsoil"));
			put(col, B + 1, Palette.parse(stone ? "minecraft:andesite_wall" : "minecraft:spruce_fence"));
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
		int b = buildingOf == null ? -1 : buildingOf[bx + bz * bw];
		PlaceSettings.Roof style = b < 0 ? (st.roof == PlaceSettings.Roof.MIXED ? PlaceSettings.Roof.HIPPED : st.roof) : buildingRoof[b];
		int depth = b < 0 ? d : buildingDepth[b];
		if (style == PlaceSettings.Roof.FLAT) {
			put(col, R, roofBlock);
			if (d == 0) {
				put(col, R + 1, pal.get("wall." + sk, "wall.default"));
				return R + 1;
			}
			return R;
		}
		int r = rise(style, d, depth, bx, bz, b);
		if (d > 0)
			put(col, R, pal.get("ceiling." + sk, "ceiling.default"));
		else if (r > 0)
			put(col, R, roofBlock); // a dome already stands above the wall here: close the gap under it
		// the space under the roof slope is filled solid: a dark hollow attic is a mob spawner
		for (int y = R + 1; y < R + r; y++)
			put(col, y, roofBlock);
		// stairs face the neighbour that's higher up the slope
		int best = -1, bestR = r;
		for (int k = 0; k < 4; k++) {
			int nd = roofDistAt(bx + DX[k], bz + DY[k]);
			int nr = nd < 0 ? -1 : rise(style, nd, depth, bx + DX[k], bz + DY[k], b);
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

	/** how far a roof rises above the wall top at roof distance d, in a building whose middle is depth from a wall */
	private int rise(PlaceSettings.Roof style, int d, int depth, int bx, int bz, int b) {
		switch (style) {
		case POINTED: {
			// two up per block; a building too big for that within the cap gets a shallower slope, so it still
			// comes to a ridge rather than a flat top
			int cap = s >= 2 ? 16 : 10;
			return 2 * depth <= cap ? 2 * d : (int) Math.round(cap * d / (double) Math.max(1, depth));
		}
		case DOMED: {
			// an elliptical dome over the building's footprint, peaking in the middle and about as tall as it is wide,
			// with a low hipped skirt round the walls so the corners outside the ellipse are roofed too
			int skirt = Math.min(d, 2);
			if (b < 0)
				return skirt;
			int[] bb = buildingBox[b];
			double a = (bb[2] - bb[0] + 1) / 2.0, c = (bb[3] - bb[1] + 1) / 2.0;
			double u = (bx + 0.5 - (bb[0] + a)) / a, v = (bz + 0.5 - (bb[1] + c)) / c, r2 = u * u + v * v;
			double hd = Math.min(Math.max(3, Math.sqrt(a * c)), s >= 2 ? 12 : 8);
			int dome = r2 < 1 ? (int) Math.round(hd * Math.sqrt(1 - r2)) : 0;
			// never steeper than two up per block from the nearest wall: where an odd-shaped footprint's wall cuts the
			// ellipse high up, the dome curves down to it instead of ending in a sheer face
			return Math.max(Math.min(dome, 1 + 2 * d), skirt);
		}
		default:
			return Math.min(d, s >= 2 ? 6 : 4);
		}
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

	/** the ore for a mineral; h picks between it and its ".alt" ore where the palette has one (gems: diamond, redstone) */
	BlockState oreBlock(String mineral, long h) {
		if ((h & 1) == 1 && pal.has("ore." + mineral + ".alt"))
			return pal.get("ore." + mineral + ".alt");
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
		int lowest = Integer.MAX_VALUE, highest = top;
		for (int idx = 0; idx < col.length; idx++) {
			if (col[idx] == null)
				continue;
			int y = idx - BELOW + B;
			lowest = Math.min(lowest, y);
			highest = Math.max(highest, y);
			if (col[idx].getBlock() instanceof net.minecraft.world.level.block.CropBlock) {
				// planted at the very end (plantCrops): a crop pops off at any shape update from a neighbour written
				// after it while the new ground's light hasn't caught up yet, and that stripped whole fields
				cropPos.add(BlockPos.asLong(X, y, Z));
				cropState.add(col[idx]);
				w.air(X, y, Z);
				continue;
			}
			w.set(X, y, Z, col[idx]);
		}
		builtTop[(X - X0) + (Z - Z0) * bw] = highest;
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

	/**
	 * Remove water and lava left above a built column: falls that poured in from terrain cut later, which are left
	 * standing once their source is gone. expectTop = the highest y the column should reach.
	 */
	static void sweepFluids(WorldWriter w, int X, int Z, int expectTop) {
		for (int y = w.anyTop(X, Z); y > expectTop; y--)
			if (w.get(X, y, Z).getBlock() instanceof net.minecraft.world.level.block.LiquidBlock)
				w.set(X, y, Z, Blocks.AIR.defaultBlockState());
	}

	/**
	 * gentle ramp between the flattened city and the natural terrain around it; returns the column's new ground y,
	 * or Integer.MAX_VALUE where it was left alone (seas and rivers)
	 */
	int blendColumn(WorldWriter w, int X, int Z, int ring, int width) {
		int river = funnelColumn(w, X, Z, width);
		if (river != Integer.MIN_VALUE)
			return river;
		final int B = groundY(X - X0, Z - Z0); // the nearest edge column of the city, after topography
		int top = w.groundTop(X, Z), nat = w.solidGround(X, Z);
		// water at about city level is a sea, river or lake: leave it alone. Water higher up (a mountain pool, spring
		// or fall) is cut down with the land under it.
		if (top > nat && !w.get(X, top, Z).getFluidState().isEmpty() && top <= Math.max(B, w.level.getSeaLevel()) + 1)
			return Integer.MAX_VALUE;
		BlockState natTopState = w.get(X, nat, Z);
		double t = ring / (double) (width + 1);
		int target = (int) Math.round(B + (nat - B) * t);
		if (target == nat)
			return Integer.MAX_VALUE; // unchanged: nothing to sweep either
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
		return target;
	}

	/* ------------------------------------------------------------------ */
	/* River lineup */

	/** the blend ring is wider with River lineup, so the city's broad river has room to narrow down */
	static final int RIVER_BLEND = 48;

	public int blendWidth() {
		return st.river ? RIVER_BLEND : PlacementJob.BLEND_WIDTH;
	}

	/**
	 * A river channel through the blend ring, from where the city's river leaves (centre cs, width ws, along the side
	 * in region blocks) to the Minecraft river found at the ring's outer edge (cm, wm; wm 0 = none found, the channel
	 * narrows to nothing).
	 */
	record Funnel(int side, double cs, double ws, double cm, double wm, int depth) {
	}

	private List<Funnel> funnels = List.of();

	/** world x/z of a ring column: side, offset along the side (region blocks), ring distance out from the edge */
	private int[] ringPos(int side, int along, int r) {
		return switch (side) {
		case Rivers.N -> new int[] { X0 + along, Z0 - r };
		case Rivers.S -> new int[] { X0 + along, Z0 + bh - 1 + r };
		case Rivers.W -> new int[] { X0 - r, Z0 + along };
		default -> new int[] { X0 + bw - 1 + r, Z0 + along };
		};
	}

	/** before blending: one funnel per river exit, aimed at the nearest Minecraft river water past the ring */
	void prepareFunnels(WorldWriter w, List<Rivers.Exit> exits) {
		List<Funnel> out = new ArrayList<>();
		int W = blendWidth();
		for (Rivers.Exit e : exits) {
			int off = e.side() == Rivers.N || e.side() == Rivers.S ? tx0 : ty0;
			double cs = (e.centre() - off + 0.5) * s, ws = e.width() * s;
			int len = e.side() == Rivers.N || e.side() == Rivers.S ? bw : bh;
			// the city river's depth where it leaves (its deepest tile)
			int depth = 2;
			for (int a = e.from(); a <= e.to(); a++) {
				int tx = e.side() == Rivers.N || e.side() == Rivers.S ? a : e.side() == Rivers.E ? tx1 : tx0;
				int ty = e.side() == Rivers.E || e.side() == Rivers.W ? a : e.side() == Rivers.S ? ty1 : ty0;
				int i = map.idx(tx, ty);
				if (kind[i] == WATER_DEEP)
					depth = Math.max(depth, Math.min(14, 3 + dist[i] / 2));
			}
			// Minecraft river water along the ring's outer edge, near the exit: runs of water at the city's water level
			int from = (int) Math.floor(cs - ws - W), to = (int) Math.ceil(cs + ws + W);
			double bestC = cs, bestW = 0, bestScore = -Double.MAX_VALUE;
			int run = -1, dry = 0;
			for (int a = from; a <= to + 1; a++) {
				boolean wet = false;
				if (a <= to) {
					int[] p = ringPos(e.side(), a, W);
					int top = w.groundTop(p[0], p[1]);
					BlockState ts = w.get(p[0], top, p[1]);
					// water, or a frozen river's ice
					wet = (ts.getFluidState().is(net.minecraft.tags.FluidTags.WATER) || ts.is(net.minecraft.tags.BlockTags.ICE))
							&& Math.abs(top - (B - 1)) <= 2;
				}
				if (wet) {
					if (run < 0)
						run = a;
					dry = 0;
					continue;
				}
				// a run ends after a few dry columns (snow on ice, a sand bar), so patchy rivers count whole; the widest
				// near the exit wins
				if (run >= 0 && (++dry > 3 || a > to)) {
					int end = a - dry;
					double c = (run + end) / 2.0 + 0.5, width = end - run + 1, score = Math.min(width, 24) - Math.abs(c - cs) / 4;
					if (score > bestScore) {
						bestScore = score;
						bestC = c;
						bestW = Math.max(4, Math.min(24, width));
					}
					run = -1;
				}
			}
			out.add(new Funnel(e.side(), cs, ws, bestW > 0 ? bestC : cs, bestW, depth));
			dev.sos2mc.syxcraft.Syxcraft.LOG.info("river exit side {} centre {} width {} -> Minecraft river at {} width {} (len {})", e.side(), cs, ws,
					bestW > 0 ? bestC : "none", bestW, len);
		}
		funnels = out;
	}

	/**
	 * A ring column inside a funnel becomes river: water at the city's water level, deepest along the middle, on a sand
	 * bed. Returns the water top, or MIN_VALUE when the column isn't in a funnel.
	 */
	private int funnelColumn(WorldWriter w, int X, int Z, int width) {
		if (funnels.isEmpty())
			return Integer.MIN_VALUE;
		int bx = X - X0, bz = Z - Z0, side, along, r;
		if (bx >= 0 && bx < bw) {
			side = bz < 0 ? Rivers.N : Rivers.S;
			along = bx;
			r = bz < 0 ? -bz : bz - bh + 1;
		} else if (bz >= 0 && bz < bh) {
			side = bx < 0 ? Rivers.W : Rivers.E;
			along = bz;
			r = bx < 0 ? -bx : bx - bw + 1;
		} else
			return Integer.MIN_VALUE; // corners
		for (Funnel f : funnels) {
			if (f.side() != side)
				continue;
			double t = (r - 1) / (double) Math.max(1, width - 1), sm = t * t * (3 - 2 * t);
			double c = f.cs() + (f.cm() - f.cs()) * sm, hw = (f.ws() * (1 - sm) + f.wm() * sm) / 2;
			double off = Math.abs(along + 0.5 - c);
			if (hw < 1 || off > hw)
				continue;
			int dmax = (int) Math.round(f.depth() * (1 - sm) + 3 * sm);
			int depth = Math.max(1, (int) Math.round(dmax * (1 - (off / hw) * (off / hw)) + 0.5));
			int top = w.anyTop(X, Z);
			for (int y = top; y >= B; y--)
				w.air(X, y, Z);
			for (int y = B - depth; y <= B - 1; y++)
				w.set(X, y, Z, pal.get("water"));
			w.set(X, B - depth - 1, Z, Blocks.SAND.defaultBlockState());
			for (int y = B - depth - 2; y > B - depth - 10 && WorldWriter.soft(w.get(X, y, Z)); y--)
				w.set(X, y, Z, pal.get("ground.subsoil"));
			return B - 1;
		}
		return Integer.MIN_VALUE;
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
