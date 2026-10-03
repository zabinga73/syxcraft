package dev.sos2mc.syxcraft.place;

import java.util.ArrayList;
import java.util.List;

import dev.sos2mc.syxcraft.map.SyxMap;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Second pass: things that span more than one column or sit on top of the built ground: trees, furniture
 * prefabs (per furniture tile, with a few whole-item designs), candles/lights.
 */
final class Details {

	private final CityPlan p;
	private final SyxMap m;
	private final int s, B, H;

	Details(CityPlan plan) {
		this.p = plan;
		this.m = plan.map;
		this.s = plan.s;
		this.B = plan.B;
		this.H = plan.H;
	}

	/** detail jobs, ordered so neighbouring work stays in the same chunks */
	List<Runnable> jobs(WorldWriter w) {
		List<Runnable> jobs = new ArrayList<>();
		// cushions are entities, so a re-placed city would otherwise keep the old ones floating about
		jobs.add(() -> Seats.removeCushions(w.level, new net.minecraft.world.phys.AABB(p.X0, B - CityPlan.BELOW, p.Z0,
				p.X0 + p.bw, B + CityPlan.ABOVE, p.Z0 + p.bh)));
		boolean veg = p.st.has(PlaceSettings.VEGETATION), furn = p.st.has(PlaceSettings.FURNITURE)
				&& p.st.has(PlaceSettings.BUILDINGS);
		for (int ty = p.ty0; ty <= p.ty1; ty++) {
			final int fty = ty;
			jobs.add(() -> {
				for (int tx = p.tx0; tx <= p.tx1; tx++) {
					int i = m.idx(tx, fty);
					if (veg && p.kind[i] == CityPlan.TREE)
						tree(w, tx, fty, i);
					if (furn && p.hasFurniture(i) && p.pit[i] == null)
						furnitureTile(w, tx, fty, i);
					if (p.st.has(PlaceSettings.BUILDINGS) && p.kind[i] == CityPlan.DOOR)
						doorTorch(w, tx, fty, i);
				}
			});
		}
		if (furn)
			for (SyxMap.Furniture f : m.furniture)
				if (p.inRegion(f.x(), f.y()))
					jobs.add(() -> furnitureItem(w, f));
		return jobs;
	}

	/* ---------------------------------------------------------------- trees */

	private void tree(WorldWriter w, int tx, int ty, int i) {
		long h = CityPlan.hash(tx * 7 + 3, ty * 13 + 5);
		String key = m.terrainKey(i);
		int size = key.endsWith("2") ? 2 : key.endsWith("1") ? 1 : 0;
		// SoS forests are one tree per tile; thin them out so Minecraft canopies have room
		int every = s >= 2 ? (size == 2 ? 2 : 3) : (size == 2 ? 3 : 4);
		if (h % every != 0)
			return;
		int x = p.blockX(tx) + (int) ((h >> 8) % s), z = p.blockZ(ty) + (int) ((h >> 12) % s);
		String type = (h >> 16) % 5 == 0 ? "Birch" : (size == 2 && (h >> 20) % 3 == 0) ? "Dark" : "";
		BlockState log = p.pal.get("log" + type, "log"), leaves = p.pal.get("leaves" + type, "leaves");
		final int B = p.groundY(x - p.X0, z - p.Z0); // topography may have moved the ground under this tree
		int trunk = 3 + size * 2 + (int) ((h >> 24) & 1);
		for (int y = B + 1; y <= B + trunk; y++)
			w.set(x, y, z, log);
		int r = size == 0 ? 1 : 2;
		int top = B + trunk;
		for (int dy = -2; dy <= 1; dy++) {
			int rr = dy == 1 ? Math.max(0, r - 1) : (dy == -2 && size == 0 ? 0 : r);
			for (int dz = -rr; dz <= rr; dz++)
				for (int dx = -rr; dx <= rr; dx++) {
					if (Math.abs(dx) == rr && Math.abs(dz) == rr && rr > 1 && ((h >> (dx + dz + 30)) & 1) == 0)
						continue;
					w.setIfAir(x + dx, top + dy, z + dz, leaves);
				}
		}
		w.setIfAir(x, top + 2, z, leaves);
	}

	/* ------------------------------------------------------------ furniture */

	/** one furniture tile: a small block design chosen from the room type and the sprite name */
	private void furnitureTile(WorldWriter w, int tx, int ty, int i) {
		SyxMap.Blueprint bp = m.blueprintAt(i);
		if (bp == null)
			return;
		SyxMap.FurnTile ft = bp.tiles.get(m.furnTile[i] & 0xFF);
		if (ft == null)
			return;
		String sprite = ft.spriteKey() == null ? "" : ft.spriteKey().toUpperCase();
		String room = bp.key == null ? "" : bp.key;
		// shrines/temples, homes and the throne get whole-item designs in furnitureItem()
		if (room.startsWith("SHRINE_") || room.startsWith("TEMPLE_") || room.equals("_HOME") || room.startsWith("_THRONE")
				|| room.startsWith("WELL_") || room.startsWith("SPEAKER_") || room.startsWith("POOL_")
				|| room.startsWith("_WATER") || room.startsWith("_CONSTRUCTION") || room.startsWith("MONUMENT_NATURE")
				|| room.startsWith("MONUMENT_TORCH") || room.startsWith("_BENCH"))
			return;
		long h = CityPlan.hash(tx * 31 + 7, ty * 17 + 11);
		int x0 = p.blockX(tx), z0 = p.blockZ(ty);
		// colours (stall canopies, carpets) stay the same across one room
		Piece piece = choose(room, sprite, ft, CityPlan.hash(m.roomId(i) * 97 + 13, 7));
		for (int v = 0; v < s; v++)
			for (int u = 0; u < s; u++)
				piece.place(w, x0 + u, B + 1, z0 + v, u, v, focusDir(tx, ty, bp), CityPlan.hash(x0 + u, z0 + v));
		if (m.flag(i, SyxMap.F_CANDLE))
			light(w, x0 + (s - 1), z0 + (s - 1));
	}

	interface Piece {
		/** place at (x, y, z) = first block above the floor; u/v sub-block in the tile; dir towards a table or -1 */
		void place(WorldWriter w, int x, int y, int z, int u, int v, int dir, long h);
	}

	private Piece choose(String room, String sp, SyxMap.FurnTile ft, long h) {
		// room-specific production gear first
		if (room.startsWith("REFINER_SMELTER") || room.startsWith("WORKSHOP_SMITHY"))
			if (has(sp, "WORK", "TABLE", "AUX", "MID", "BIG", "SMELT", "FURNACE", "ANVIL"))
				return foundry();
		if (room.startsWith("REFINER_BAKERY") && has(sp, "WORK", "TABLE", "OVEN", "AUX"))
			return stack("minecraft:smoker[facing=south]", "minecraft:bricks");
		if (room.startsWith("REFINER_BREWERY") && has(sp, "WORK", "TABLE", "AUX"))
			return (w, x, y, z, u, v, d, hh) -> w.set(x, y, z, P((hh & 1) == 0 ? "minecraft:brewing_stand" : "minecraft:water_cauldron[level=3]"));
		if (room.startsWith("REFINER_COALER") && has(sp, "WORK", "TABLE", "AUX"))
			return stack("minecraft:furnace[facing=south,lit=true]", "minecraft:coal_block");
		if (room.startsWith("REFINER_WEAVER") || room.startsWith("WORKSHOP_TAILOR"))
			if (has(sp, "WORK", "TABLE"))
				return single("minecraft:loom");
		if (room.startsWith("WORKSHOP_") && has(sp, "TABLE", "WORK")) {
			String t = switch (room.substring(9)) {
			case "CARPENTER" -> "minecraft:crafting_table";
			case "MASON" -> "minecraft:stonecutter";
			case "BOWYER" -> "minecraft:fletching_table";
			case "PAPER" -> "minecraft:cartography_table";
			case "JEWELRY" -> "minecraft:grindstone[face=floor]";
			case "POTTERY" -> "minecraft:decorated_pot";
			case "MECHANIC" -> "minecraft:dispenser[facing=up]";
			case "RATION" -> "minecraft:smoker[facing=south]";
			default -> "minecraft:crafting_table";
			};
			return single(t);
		}
		if (room.startsWith("LAVATORY") && has(sp, "SIT"))
			return single("minecraft:composter");
		// the lavatory's "Basins" use table sprites in Songs of Syx: make them washbasins
		if (room.startsWith("LAVATORY") && has(sp, "TABLE", "BASIN"))
			return single("minecraft:water_cauldron[level=3]");
		if ((room.startsWith("_HOSPITAL") || room.startsWith("PHYSICIAN") || room.startsWith("RESTHOME")) && has(sp, "BED"))
			return bed("white");
		if (room.startsWith("MINE_") || room.startsWith("_STOCKPILE") || room.startsWith("_HAULER"))
			return barrels();
		if (room.startsWith("LIBRARY") || room.startsWith("UNIVERSITY") || room.startsWith("SCHOOL"))
			if (has(sp, "SHELF", "BOOK", "STORAGE"))
				return stack("minecraft:bookshelf", "minecraft:bookshelf");
		if (room.startsWith("MONUMENT_"))
			return monument(room.substring(9));
		if (room.startsWith("GRAVEYARD") || room.startsWith("TOMB"))
			return (w, x, y, z, u, v, d, hh) -> {
				if (((u + v) & 1) == 0) {
					w.set(x, y, z, P("minecraft:cobblestone_wall"));
					w.set(x, y + 1, z, P("minecraft:skeleton_skull"));
				}
			};

		// generic, by sprite name
		if (has(sp, "STORAGE", "CRATE", "BARREL", "SACK", "PALLET"))
			return barrels();
		if (has(sp, "HEARTH", "FIRE", "STOVE", "OVEN"))
			return hearth();
		if (has(sp, "STONE_RING", "WELL"))
			return well();
		if (has(sp, "BUCKET", "BASIN", "TANK", "VAT", "TUB"))
			return single("minecraft:water_cauldron[level=3]");
		if (has(sp, "STALL"))
			return stall(h);
		if (has(sp, "CARPET", "RUG"))
			return single("minecraft:" + carpetColour(h) + "_carpet");
		if (has(sp, "CHAIR", "SIT", "STOOL", "BENCH", "SEAT"))
			return chair();
		if (has(sp, "TABLE", "DESK", "COUNTER", "WORKTABLE", "WORK"))
			return table();
		if (has(sp, "BED", "BUNK"))
			return bed("red");
		if (has(sp, "CANDLE", "LAMP", "TORCH", "LIGHT", "LANTERN"))
			return (w, x, y, z, u, v, d, hh) -> {
				if (u == 0 && v == 0) {
					w.set(x, y, z, P("minecraft:spruce_fence"));
					w.set(x, y + 1, z, P("minecraft:lantern"));
				}
			};
		if (has(sp, "SHELF", "BOOK"))
			return stack("minecraft:bookshelf", "minecraft:bookshelf");
		if (has(sp, "FRAME", "PODIUM", "SPEAK"))
			return (w, x, y, z, u, v, d, hh) -> {
				if (u == 0 && v == 0)
					w.set(x, y, z, P("minecraft:lectern[facing=south]"));
			};
		if (has(sp, "ALTAR", "EMBLEM", "STATUE"))
			return stack("minecraft:chiseled_stone_bricks", "minecraft:lantern");
		if (has(sp, "NICKNACK", "MISC", "DECOR", "AUX", "EDGE", "MID", "BIG", "BOTTOM"))
			return knickknack();
		return ft.blocker() ? knickknack() : (w, x, y, z, u, v, d, hh) -> {
		};
	}

	/* -------------------------------------------------------- whole items */

	private void furnitureItem(WorldWriter w, SyxMap.Furniture f) {
		SyxMap.Blueprint bp = f.bp() > 0 && f.bp() < m.blueprints.length ? m.blueprints[f.bp()] : null;
		if (bp == null || bp.key == null)
			return;
		String room = bp.key;
		SyxMap.FurnItem item = bp.items.get(f.item());
		if (item == null)
			return;
		if (room.startsWith("SHRINE_") || room.startsWith("TEMPLE_"))
			shrine(w, f, item, room.substring(room.indexOf('_') + 1));
		else if (room.equals("_HOME"))
			home(w, f, item, bp);
		else if (room.startsWith("_THRONE"))
			throne(w, f);
		else if (room.startsWith("WELL_"))
			well(w, f);
		else if (room.startsWith("SPEAKER_"))
			speaker(w, f);
		else if (room.startsWith("MONUMENT_NATURE"))
			nature(w, f, item.groupName() == null ? "" : item.groupName());
		else if (room.startsWith("MONUMENT_TORCH"))
			torchMonument(w, f);
		else if (room.startsWith("_BENCH"))
			bench(w, f);
	}

	/** god colour schemes chosen by the user */
	record Theme(String floor, String base, String accent, String top, String light) {
	}

	static Theme theme(String god) {
		return switch (god) {
		// earthy
		case "CRATOR" -> new Theme("minecraft:coarse_dirt", "minecraft:packed_mud", "minecraft:brown_terracotta",
				"minecraft:moss_block", "minecraft:campfire");
		// black and white, hints of red
		case "SHMALOR" -> new Theme("minecraft:polished_blackstone_bricks", "minecraft:calcite", "minecraft:blackstone",
				"minecraft:red_nether_bricks", "minecraft:redstone_lamp[lit=true]");
		// red and white, hints of black
		case "AMINION" -> new Theme("minecraft:white_concrete", "minecraft:red_terracotta", "minecraft:quartz_block",
				"minecraft:black_concrete", "minecraft:lantern");
		// mostly green and gold
		case "ATHURI" -> new Theme("minecraft:green_terracotta", "minecraft:lime_terracotta", "minecraft:gold_block",
				"minecraft:emerald_block", "minecraft:lantern");
		default -> new Theme("minecraft:stone_bricks", "minecraft:chiseled_stone_bricks", "minecraft:polished_andesite",
				"minecraft:gold_block", "minecraft:lantern");
		};
	}

	/** shrine/temple: patterned floor, a raised altar in the middle, corner pillars with lights */
	private void shrine(WorldWriter w, SyxMap.Furniture f, SyxMap.FurnItem item, String god) {
		Theme t = theme(god);
		int x0 = p.blockX(f.x()), z0 = p.blockZ(f.y()), bw = f.w() * s, bh = f.h() * s;
		for (int dz = 0; dz < bh; dz++)
			for (int dx = 0; dx < bw; dx++) {
				int tx = f.x() + dx / s, ty = f.y() + dz / s;
				int ti = item.tiles()[Math.min(dz / s, item.h() - 1)][Math.min(dx / s, item.w() - 1)];
				if (ti < 0)
					continue;
				boolean border = dx == 0 || dz == 0 || dx == bw - 1 || dz == bh - 1;
				boolean checker = ((dx + dz) & 1) == 0;
				w.set(x0 + dx, B, z0 + dz, P(border ? t.accent() : checker ? t.floor() : t.base()));
				w.air(x0 + dx, B + 1, z0 + dz);
				boolean corner = (dx == 0 || dx == bw - 1) && (dz == 0 || dz == bh - 1);
				if (corner) {
					for (int y = B + 1; y <= B + Math.min(H, 3); y++)
						w.set(x0 + dx, y, z0 + dz, P(t.accent()));
					w.set(x0 + dx, B + Math.min(H, 3) + 1, z0 + dz, P(t.light()));
				}
				if (!m.inBounds(tx, ty))
					continue;
			}
		// altar: the middle third, two high, topped with the god's accent and a light
		int ax0 = x0 + bw / 3, ax1 = x0 + bw - 1 - bw / 3, az0 = z0 + bh / 3, az1 = z0 + bh - 1 - bh / 3;
		for (int z = az0; z <= az1; z++)
			for (int x = ax0; x <= ax1; x++) {
				w.set(x, B + 1, z, P(t.base()));
				boolean edge = x == ax0 || x == ax1 || z == az0 || z == az1;
				w.set(x, B + 2, z, P(edge ? t.accent() : t.top()));
			}
		int cx = (ax0 + ax1) / 2, cz = (az0 + az1) / 2;
		w.set(cx, B + 3, cz, P(t.light()));
	}

	/**
	 * A home unit. Songs of Syx packs houses wall to wall with very thin walls, so each house gets a one-block
	 * wooden wall around it (shared with its neighbour, at scale 1 too), a door at its entrance unless the entrance
	 * opens straight onto the building's own doorway, a lantern, a bed and odds and ends on its blocking tiles.
	 */
	private void home(WorldWriter w, SyxMap.Furniture f, SyxMap.FurnItem item, SyxMap.Blueprint bp) {
		int x0 = p.blockX(f.x()), z0 = p.blockZ(f.y()), bw = f.w() * s, bh = f.h() * s;
		java.util.Set<Long> taken = new java.util.HashSet<>();
		{
			BlockState wall = p.pal.get("houseWall");
			for (int dz = 0; dz < bh; dz++)
				for (int dx = 0; dx < bw; dx++) {
					int side = dz == 0 ? 0 : dx == bw - 1 ? 1 : dz == bh - 1 ? 2 : dx == 0 ? 3 : -1;
					if (side < 0 || !houseWallHere(f, dx, dz, bw, bh))
						continue;
					for (int y = B + 1; y <= B + H; y++)
						w.set(x0 + dx, y, z0 + dz, wall);
					taken.add(key(x0 + dx, z0 + dz));
				}
			houseDoor(w, f, item, bp, x0, z0, bw, bh, taken);
			w.set(x0 + bw / 2, B + H, z0 + bh / 2, P("minecraft:lantern[hanging=true]"));
		}

		List<int[]> spots = new ArrayList<>();
		for (int ty = 0; ty < item.h(); ty++)
			for (int tx = 0; tx < item.w(); tx++) {
				int ti = item.tiles()[ty][tx];
				SyxMap.FurnTile t = ti < 0 ? null : bp.tiles.get(ti);
				if (t != null && t.blocker())
					for (int v = 0; v < s; v++)
						for (int u = 0; u < s; u++) {
							int x = p.blockX(f.x() + tx) + u, z = p.blockZ(f.y() + ty) + v;
							if (!taken.contains(key(x, z)))
								spots.add(new int[] { x, z });
						}
			}
		if (spots.isEmpty())
			return;
		long h = CityPlan.hash(f.x() * 3 + 1, f.y() * 5 + 2);
		boolean bedDone = false;
		for (int[] a : spots) {
			for (int[] b : spots) {
				if (bedDone)
					break;
				boolean alongX = b[1] == a[1] && b[0] == a[0] + 1, alongZ = b[0] == a[0] && b[1] == a[1] + 1;
				if (alongX || alongZ) {
					String col = (h & 1) == 0 ? "red" : "brown";
					String facing = alongX ? "west" : "north"; // head at a, foot at b
					w.set(a[0], B + 1, a[1], P("minecraft:" + col + "_bed[part=head,facing=" + facing + "]"), WorldWriter.FLAGS_RAW);
					w.set(b[0], B + 1, b[1], P("minecraft:" + col + "_bed[part=foot,facing=" + facing + "]"), WorldWriter.FLAGS_RAW);
					a[0] = Integer.MIN_VALUE;
					b[0] = Integer.MIN_VALUE;
					bedDone = true;
				}
			}
		}
		String[] stuff = { "minecraft:chest[facing=south]", "minecraft:crafting_table", "minecraft:barrel[facing=up]",
				"minecraft:furnace[facing=south]", "minecraft:bookshelf", "minecraft:composter", "minecraft:barrel[facing=up]",
				"minecraft:flower_pot" };
		int n = 0;
		for (int[] a : spots) {
			if (a[0] == Integer.MIN_VALUE)
				continue;
			long hh = CityPlan.hash(a[0], a[1]);
			if ((hh & 3) == 0 && n > 2)
				continue; // leave some space to walk
			if ((hh >> 3) % 4 == 0)
				table().place(w, a[0], B + 1, a[1], 0, 0, -1, hh);
			else
				w.set(a[0], B + 1, a[1], P(stuff[(int) ((hh >> 5) % stuff.length)]));
			n++;
		}
	}

	/**
	 * North and west edges always get the wall; south and east edges only when the house there isn't another one
	 * (that house's own north/west wall is the shared wall). Edges against the building's real wall are skipped.
	 */
	private boolean houseWallHere(SyxMap.Furniture f, int dx, int dz, int bw, int bh) {
		int x = p.blockX(f.x()) + dx, z = p.blockZ(f.y()) + dz;
		java.util.List<int[]> sides = new java.util.ArrayList<>();
		if (dz == 0)
			sides.add(new int[] { 0, -1, 0 });
		if (dz == bh - 1)
			sides.add(new int[] { 0, 1, 1 });
		if (dx == 0)
			sides.add(new int[] { -1, 0, 0 });
		if (dx == bw - 1)
			sides.add(new int[] { 1, 0, 1 });
		for (int[] sd : sides) {
			int ntx = p.tileOfBlockX(x + sd[0] - p.X0), nty = p.tileOfBlockZ(z + sd[1] - p.Z0);
			byte k = p.kindAt(ntx, nty);
			if (k == CityPlan.WALL || k == CityPlan.WALL_BROKEN || k == CityPlan.DOOR)
				continue; // the building's wall is already there
			if (sd[2] == 1 && isHouseTile(ntx, nty) && !inItem(f, ntx, nty))
				continue; // the neighbouring house draws this shared wall
			return true;
		}
		return false;
	}

	private boolean isHouseTile(int tx, int ty) {
		if (!m.inBounds(tx, ty))
			return false;
		int i = m.idx(tx, ty);
		SyxMap.Blueprint bp = m.blueprintAt(i);
		return bp != null && "_HOME".equals(bp.key) && p.hasFurniture(i);
	}

	private static boolean inItem(SyxMap.Furniture f, int tx, int ty) {
		return tx >= f.x() && ty >= f.y() && tx < f.x() + f.w() && ty < f.y() + f.h();
	}

	/** a door in the house wall at its entrance tile (the tile Songs of Syx marks "no walls") */
	private void houseDoor(WorldWriter w, SyxMap.Furniture f, SyxMap.FurnItem item, SyxMap.Blueprint bp, int x0, int z0,
			int bw, int bh, java.util.Set<Long> taken) {
		for (int ty = 0; ty < item.h(); ty++)
			for (int tx = 0; tx < item.w(); tx++) {
				int ti = item.tiles()[ty][tx];
				SyxMap.FurnTile t = ti < 0 ? null : bp.tiles.get(ti);
				if (t == null || !t.noWalls())
					continue;
				int side = ty == 0 ? 0 : tx == item.w() - 1 ? 1 : ty == item.h() - 1 ? 2 : tx == 0 ? 3 : -1;
				if (side < 0)
					continue;
				// the middle of that tile's stretch of the edge
				int dx = side == 1 ? bw - 1 : side == 3 ? 0 : tx * s + s / 2;
				int dz = side == 2 ? bh - 1 : side == 0 ? 0 : ty * s + s / 2;
				int x = x0 + dx, z = z0 + dz;
				int ox = CityPlan.DX[side], oz = CityPlan.DY[side];
				byte outside = p.kindAt(p.tileOfBlockX(x + ox - p.X0), p.tileOfBlockZ(z + oz - p.Z0));
				if (outside == CityPlan.DOOR || outside == CityPlan.WALL || outside == CityPlan.WALL_BROKEN) {
					// the house opens straight onto the building's doorway: that door is its front door
					for (int y = B + 1; y <= B + 2; y++)
						w.air(x, y, z);
					taken.add(key(x, z));
					taken.add(key(x - ox, z - oz));
					return;
				}
				String facing = CityPlan.DIR[(side + 2) % 4];
				w.set(x, B, z, CityPlan.sturdy(w.get(x, B, z)));
				w.set(x, B + 1, z, P("minecraft:spruce_door[half=lower,facing=" + facing + ",hinge=left]"), WorldWriter.FLAGS_RAW);
				w.set(x, B + 2, z, P("minecraft:spruce_door[half=upper,facing=" + facing + ",hinge=left]"), WorldWriter.FLAGS_RAW);
				// keep both sides of the door free
				for (int k = -1; k <= 1; k += 2)
					for (int y = B + 1; y <= B + 2; y++) {
						long kk = key(x + ox * k, z + oz * k);
						if (!taken.contains(kk))
							w.air(x + ox * k, y, z + oz * k);
					}
				taken.add(key(x - ox, z - oz));
				return;
			}
	}

	private static long key(int x, int z) {
		return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
	}

	/** a village-style well: cobblestone rim around water, fence posts in the corners, a cobblestone roof */
	private void well(WorldWriter w, SyxMap.Furniture f) {
		int x0 = p.blockX(f.x()), z0 = p.blockZ(f.y()), bw = f.w() * s, bh = f.h() * s;
		if (bw < 3 || bh < 3) {
			w.set(x0, B + 1, z0, P("minecraft:water_cauldron[level=3]"));
			return;
		}
		for (int dz = 0; dz < bh; dz++)
			for (int dx = 0; dx < bw; dx++) {
				int x = x0 + dx, z = z0 + dz;
				boolean rim = dx == 0 || dz == 0 || dx == bw - 1 || dz == bh - 1;
				boolean corner = (dx == 0 || dx == bw - 1) && (dz == 0 || dz == bh - 1);
				w.set(x, B - 3, z, P("minecraft:cobblestone"));
				for (int y = B - 2; y <= B; y++)
					w.set(x, y, z, P(rim ? "minecraft:cobblestone" : "minecraft:water"));
				w.set(x, B + 1, z, rim ? P("minecraft:cobblestone") : P("minecraft:air"));
				for (int y = B + 2; y <= B + 3; y++)
					w.set(x, y, z, corner ? P("minecraft:oak_fence") : P("minecraft:air"));
				w.set(x, B + 4, z, P("minecraft:cobblestone"));
			}
		w.set(x0 + bw / 2, B + 3, z0 + bh / 2, P("minecraft:bell[attachment=ceiling]"));
	}

	/** nature monuments: planted trees (sized by the item) and flowerbeds */
	private void nature(WorldWriter w, SyxMap.Furniture f, String group) {
		int x0 = p.blockX(f.x()), z0 = p.blockZ(f.y()), bw = f.w() * s, bh = f.h() * s;
		long h = CityPlan.hash(f.x() * 13 + 1, f.y() * 7 + 3);
		boolean natural = group.contains("natural");
		if (group.startsWith("Flower")) {
			// a bed: two flower kinds per planted bed, a wild mix for natural ones
			int a = (int) (h % 12), b = (int) ((h >> 8) % 12);
			for (int dz = 0; dz < bh; dz++)
				for (int dx = 0; dx < bw; dx++) {
					long hh = CityPlan.hash(x0 + dx, z0 + dz);
					w.set(x0 + dx, B, z0 + dz, P("minecraft:grass_block"));
					int kind = natural ? (int) (hh % 12) : (((dx + dz) & 1) == 0 ? a : b);
					if (!natural || (hh & 3) != 0)
						w.set(x0 + dx, B + 1, z0 + dz, CityPlan.Blocks1.flower(kind));
				}
			return;
		}
		// a tree in the middle of the item, bigger items get bigger trees
		int size = Math.max(f.w(), f.h());
		int cx = x0 + bw / 2, cz = z0 + bh / 2;
		boolean flowering = !natural && (h & 1) == 0;
		String log = natural ? "minecraft:oak_log" : "minecraft:birch_log";
		String leaves = natural ? "minecraft:oak_leaves[persistent=true]"
				: flowering ? "minecraft:flowering_azalea_leaves[persistent=true]" : "minecraft:azalea_leaves[persistent=true]";
		int trunk = 2 + size * 2, r = 1 + size;
		for (int y = B + 1; y <= B + trunk; y++)
			w.set(cx, y, cz, P(log));
		int top = B + trunk;
		for (int dy = -2; dy <= 1; dy++) {
			int rr = dy == 1 ? Math.max(1, r - 1) : dy == -2 ? Math.max(1, r - 1) : r;
			for (int dz = -rr; dz <= rr; dz++)
				for (int dx = -rr; dx <= rr; dx++) {
					if (dx * dx + dz * dz > rr * rr + 1)
						continue;
					w.setIfAir(cx + dx, top + dy, cz + dz, P(leaves));
				}
		}
		w.setIfAir(cx, top + 2, cz, P(leaves));
	}

	/** torch monument: a stone plinth with a pillar and a fire on top */
	private void torchMonument(WorldWriter w, SyxMap.Furniture f) {
		int x0 = p.blockX(f.x()), z0 = p.blockZ(f.y()), bw = f.w() * s, bh = f.h() * s;
		int cx0 = x0 + (bw - 1) / 2, cz0 = z0 + (bh - 1) / 2, cx1 = x0 + bw / 2, cz1 = z0 + bh / 2;
		for (int dz = 0; dz < bh; dz++)
			for (int dx = 0; dx < bw; dx++) {
				int x = x0 + dx, z = z0 + dz;
				boolean centre = x >= cx0 && x <= cx1 && z >= cz0 && z <= cz1;
				if (centre) {
					w.set(x, B + 1, z, P("minecraft:stone_bricks"));
					w.set(x, B + 2, z, P("minecraft:chiseled_stone_bricks"));
					w.set(x, B + 3, z, P("minecraft:campfire[lit=true]"));
				} else
					w.set(x, B + 1, z, P("minecraft:stone_brick_slab[type=bottom]"));
			}
	}

	/** a row of benches: stairs along the item, backs on one side */
	private void bench(WorldWriter w, SyxMap.Furniture f) {
		int x0 = p.blockX(f.x()), z0 = p.blockZ(f.y()), bw = f.w() * s, bh = f.h() * s;
		boolean alongX = f.w() >= f.h();
		for (int dz = 0; dz < bh; dz++)
			for (int dx = 0; dx < bw; dx++) {
				// one row of seats even when the tile is several blocks deep
				if (alongX ? dz != bh - 1 : dx != bw - 1)
					continue;
				w.set(x0 + dx, B + 1, z0 + dz, P("minecraft:spruce_stairs[facing=" + (alongX ? "north" : "west") + "]"));
				Seats.cushion(w.level, x0 + dx, B + 1, z0 + dz, alongX ? 0 : 3);
			}
	}

	/** the speaker's stage: a raised smooth stone platform */
	private void speaker(WorldWriter w, SyxMap.Furniture f) {
		int x0 = p.blockX(f.x()), z0 = p.blockZ(f.y()), bw = f.w() * s, bh = f.h() * s;
		for (int dz = 0; dz < bh; dz++)
			for (int dx = 0; dx < bw; dx++)
				w.set(x0 + dx, B + 1, z0 + dz, P("minecraft:smooth_stone"));
	}

	/** a wall torch above the outside of each doorway */
	private void doorTorch(WorldWriter w, int tx, int ty, int i) {
		int d = p.doorDir[i];
		if (d < 0 || p.H < 3)
			return;
		// one torch per doorway, over its first door leaf
		int[] run = p.doorRun(tx, ty);
		int a = p.doorStart(run[1]) - run[0] * s;
		if (a < 0 || a >= s)
			return; // the first leaf is in another tile of this doorway

		int u = d == 1 ? s - 1 : d == 3 ? 0 : a, v = d == 2 ? s - 1 : d == 0 ? 0 : a;
		int x = p.blockX(tx) + u + CityPlan.DX[d], z = p.blockZ(ty) + v + CityPlan.DY[d];
		if (w.get(x, B + 3, z).isAir())
			w.set(x, B + 3, z, P("minecraft:wall_torch[facing=" + CityPlan.DIR[d] + "]"));
	}

	private void throne(WorldWriter w, SyxMap.Furniture f) {
		int x0 = p.blockX(f.x()), z0 = p.blockZ(f.y()), bw = f.w() * s, bh = f.h() * s;
		int cx = x0 + bw / 2, cz = z0 + bh / 2;
		for (int dz = 0; dz < bh; dz++)
			for (int dx = 0; dx < bw; dx++)
				w.set(x0 + dx, B, z0 + dz, P(dx == bw / 2 ? "minecraft:red_wool" : "minecraft:polished_andesite"));
		w.set(cx, B + 1, cz, P("minecraft:gold_block"));
		w.set(cx, B + 2, cz, P("minecraft:quartz_stairs[facing=north]"));
		w.set(cx - 1, B + 1, cz, P("minecraft:quartz_stairs[facing=east]"));
		w.set(cx + 1, B + 1, cz, P("minecraft:quartz_stairs[facing=west]"));
		w.set(cx, B + 3, cz - 1, P("minecraft:gold_block"));
		w.set(cx, B + 1, cz - 1, P("minecraft:gold_block"));
		w.set(cx, B + 2, cz - 1, P("minecraft:gold_block"));
	}

	/* ------------------------------------------------------------ pieces */

	private static Piece single(String id) {
		return (w, x, y, z, u, v, d, h) -> w.set(x, y, z, P(id));
	}

	private Piece stack(String bottom, String top) {
		return (w, x, y, z, u, v, d, h) -> {
			w.set(x, y, z, P(bottom));
			if (H >= 3 && (h & 1) == 0)
				w.set(x, y + 1, z, P(top));
		};
	}

	/** storage: barrels stacked one to three high (as the user asked for storage units) */
	private Piece barrels() {
		return (w, x, y, z, u, v, d, h) -> {
			int n = 1 + (int) (h % 3);
			for (int k = 0; k < Math.min(n, H - 1); k++)
				w.set(x, y + k, z, P(k == 0 && (h >> 4) % 5 == 0 ? "minecraft:chest[facing=south]" : "minecraft:barrel[facing=up]"));
		};
	}

	private Piece table() {
		return (w, x, y, z, u, v, d, h) -> {
			w.set(x, y, z, P("minecraft:spruce_fence"));
			w.set(x, y + 1, z, P((h & 7) == 0 ? "minecraft:candle[lit=true,candles=2]" : (h & 7) == 1 ? "minecraft:flower_pot" : "minecraft:spruce_pressure_plate"));
		};
	}

	private Piece chair() {
		return (w, x, y, z, u, v, d, h) -> {
			if (s >= 2 && ((u + v) & 1) == 1)
				return; // chairs a bit apart
			// back of the chair away from the table so the seat faces it
			String facing = d < 0 ? CityPlan.DIR[(int) (h & 3)] : CityPlan.DIR[(d + 2) % 4];
			w.set(x, y, z, P("minecraft:oak_stairs[facing=" + facing + "]"));
		};
	}

	private Piece bed(String colour) {
		return (w, x, y, z, u, v, d, h) -> {
			if (s >= 2) {
				if (u != 0)
					return;
				w.set(x, y, z + (v == 0 ? 0 : 0), P("minecraft:" + colour + "_bed[part=" + (v == 0 ? "head" : "foot") + ",facing=north]"), WorldWriter.FLAGS_RAW);
			} else {
				w.set(x, y, z, P("minecraft:" + colour + "_carpet"));
			}
		};
	}

	private Piece hearth() {
		return (w, x, y, z, u, v, d, h) -> {
			boolean centre = s == 1 || (u == 0 && v == 0);
			w.set(x, y - 1, z, P("minecraft:stone_bricks"));
			w.set(x, y, z, P(centre ? "minecraft:campfire[lit=true]" : "minecraft:stone_brick_slab"));
		};
	}

	private Piece well() {
		return (w, x, y, z, u, v, d, h) -> {
			w.set(x, y - 1, z, P("minecraft:water"));
			w.set(x, y - 2, z, P("minecraft:water"));
			w.set(x, y, z, P("minecraft:cobblestone_wall"));
			if (H >= 3 && ((u + v) & 1) == 0)
				w.set(x, y + 1, z, P("minecraft:oak_fence"));
		};
	}

	private Piece stall(long seed) {
		String wool = "minecraft:" + carpetColour(seed) + "_wool";
		return (w, x, y, z, u, v, d, h) -> {
			w.set(x, y, z, P(((u + v) & 1) == 0 ? "minecraft:barrel[facing=up]" : "minecraft:spruce_planks"));
			if (H >= 3) {
				w.set(x, y + Math.min(H, 3) - 1, z, P(wool));
				if (((u ^ v) & 1) == 0)
					w.set(x, y + 1, z, P("minecraft:spruce_fence"));
			}
		};
	}

	/** iron foundry gear: blast furnaces with brick chimneys and iron details */
	private Piece foundry() {
		return (w, x, y, z, u, v, d, h) -> {
			int kind = (int) (h % 4);
			switch (kind) {
			case 0 -> {
				w.set(x, y, z, P("minecraft:blast_furnace[facing=south,lit=true]"));
				for (int k = 1; k < H; k++)
					w.set(x, y + k, z, P(k == 1 ? "minecraft:iron_bars" : "minecraft:bricks"));
			}
			case 1 -> w.set(x, y, z, P("minecraft:anvil[facing=north]"));
			case 2 -> {
				w.set(x, y, z, P("minecraft:blast_furnace[facing=south,lit=true]"));
				w.set(x, y + 1, z, P("minecraft:hopper"));
			}
			default -> w.set(x, y, z, P("minecraft:lava_cauldron"));
			}
		};
	}

	/** decorative monuments: torch pillars, statues, nature beds */
	private Piece monument(String type) {
		return (w, x, y, z, u, v, d, h) -> {
			switch (type) {
			case "TORCH" -> {
				w.set(x, y, z, P("minecraft:stone_bricks"));
				w.set(x, y + 1, z, P("minecraft:stone_brick_wall"));
				w.set(x, y + 2, z, P("minecraft:campfire[lit=true]"));
			}
			case "NATURE" -> {
				w.set(x, y - 1, z, P("minecraft:moss_block"));
				if ((h & 3) == 0)
					w.set(x, y, z, P("minecraft:flowering_azalea"));
				else
					w.set(x, y, z, CityPlan.Blocks1.flower((int) (h >> 3)));
			}
			case "DEATH" -> {
				w.set(x, y, z, P("minecraft:polished_blackstone"));
				w.set(x, y + 1, z, P("minecraft:wither_skeleton_skull"));
			}
			case "BLOB" -> {
				w.set(x, y, z, P("minecraft:slime_block"));
				if ((h & 1) == 0)
					w.set(x, y + 1, z, P("minecraft:slime_block"));
			}
			default -> {
				for (int k = 0; k < Math.min(H, 3); k++)
					w.set(x, y + k, z, P(k == 0 ? "minecraft:polished_andesite" : "minecraft:smooth_stone"));
				w.set(x, y + Math.min(H, 3), z, P("minecraft:lantern"));
			}
			}
		};
	}

	private static final String[] KNICKS = { "minecraft:barrel[facing=up]", "minecraft:decorated_pot", "minecraft:flower_pot",
			"minecraft:cauldron", "minecraft:chest[facing=south]", "minecraft:composter", "minecraft:hay_block",
			"minecraft:bookshelf", "minecraft:lantern", "minecraft:potted_red_tulip", "minecraft:barrel[facing=up]",
			"minecraft:bell[attachment=floor]", "minecraft:chiseled_bookshelf", "minecraft:brewing_stand", "minecraft:candle[lit=true,candles=3]" };

	/** knick-knacks: barrel stacks, bells, pots and other odds and ends */
	private Piece knickknack() {
		return (w, x, y, z, u, v, d, h) -> {
			if ((h & 3) == 0)
				return;
			String id = KNICKS[(int) ((h >> 2) % KNICKS.length)];
			w.set(x, y, z, P(id));
			if (id.contains("barrel") && (h & 16) != 0 && H >= 3)
				w.set(x, y + 1, z, P("minecraft:barrel[facing=up]"));
		};
	}

	/* ------------------------------------------------------------ helpers */

	private void light(WorldWriter w, int x, int z) {
		for (int y = B + 1; y <= B + H; y++) {
			if (w.get(x, y, z).isAir()) {
				w.set(x, y, z, P("minecraft:lantern"));
				return;
			}
		}
	}

	/**
	 * Direction (0 N,1 E,2 S,3 W) from a seat towards the nearest thing worth facing in the same room (a table,
	 * hearth, fire, altar or stage) within 3 tiles; -1 if none.
	 */
	private int focusDir(int tx, int ty, SyxMap.Blueprint bp) {
		int best = -1, bestD = Integer.MAX_VALUE;
		for (int dy = -3; dy <= 3; dy++)
			for (int dx = -3; dx <= 3; dx++) {
				if (dx == 0 && dy == 0)
					continue;
				int nx = tx + dx, ny = ty + dy;
				if (!m.inBounds(nx, ny))
					continue;
				int j = m.idx(nx, ny);
				if (m.blueprintAt(j) != bp)
					continue;
				SyxMap.FurnTile ft = bp.tiles.get(m.furnTile[j] & 0xFF);
				if (ft == null || ft.spriteKey() == null)
					continue;
				String k = ft.spriteKey().toUpperCase();
				if (!has(k, "TABLE", "HEARTH", "FIRE", "ALTAR", "STAGE", "PODIUM", "DESK", "COUNTER"))
					continue;
				int d = dx * dx + dy * dy;
				if (d < bestD) {
					bestD = d;
					best = Math.abs(dx) > Math.abs(dy) ? (dx > 0 ? 1 : 3) : (dy > 0 ? 2 : 0);
				}
			}
		return best;
	}

	private static String carpetColour(long h) {
		String[] c = { "red", "blue", "yellow", "green", "orange", "purple", "cyan", "white" };
		return c[(int) ((h >> 6) % c.length)];
	}

	private static boolean has(String sp, String... keys) {
		for (String k : keys)
			if (sp.contains(k))
				return true;
		return false;
	}

	private static final java.util.Map<String, BlockState> CACHE = new java.util.concurrent.ConcurrentHashMap<>();

	static BlockState P(String id) {
		return CACHE.computeIfAbsent(id, Palette::parse);
	}
}
