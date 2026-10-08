package dev.sos2mc.syxcraft.place;

import java.util.ArrayList;
import java.util.List;

import dev.sos2mc.syxcraft.map.SyxMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.PaintingVariantTags;
import net.minecraft.world.entity.decoration.painting.Painting;
import net.minecraft.world.entity.decoration.painting.PaintingVariant;
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
		jobs.add(() -> {
			for (Painting e : w.level.getEntitiesOfClass(Painting.class, new net.minecraft.world.phys.AABB(p.X0, B - CityPlan.BELOW,
					p.Z0, p.X0 + p.bw, B + CityPlan.ABOVE, p.Z0 + p.bh), e -> e.entityTags().contains(PAINTING_TAG)))
				e.discard();
		});
		boolean veg = p.st.has(PlaceSettings.VEGETATION), furn = p.st.has(PlaceSettings.FURNITURE)
				&& p.st.has(PlaceSettings.BUILDINGS);
		for (int ty = p.ty0; ty <= p.ty1; ty++) {
			final int fty = ty;
			jobs.add(() -> {
				for (int tx = p.tx0; tx <= p.tx1; tx++) {
					int i = m.idx(tx, fty);
					if (veg && p.kind[i] == CityPlan.TREE)
						tree(w, tx, fty, i);
					if (furn && p.hasFurniture(i))
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
		// paintings go up last, once the furniture they mustn't overlap is in
		if (furn)
			for (SyxMap.Room r : m.rooms)
				if (r.blueprint() != null && r.blueprint().startsWith("_HOME_CHAMBER") && p.inRegion(r.x1(), r.y1()))
					jobs.add(() -> paintings(w, r));
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
		if (p.cut(x - p.X0, z - p.Z0))
			return; // unsquare: this bit of open land was left to the Minecraft terrain
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
		if (room.startsWith("GRAVEYARD_")) {
			graveTile(w, tx, ty, i, sprite, bp);
			return;
		}
		if (room.startsWith("_HOME_CHAMBER")) {
			chamberTile(w, tx, ty, i, ft.index(), bp);
			return;
		}
		// shrines/temples, homes and the throne get whole-item designs in furnitureItem()
		if (room.startsWith("SHRINE_") || room.startsWith("TEMPLE_") || room.equals("_HOME") || room.startsWith("_THRONE")
				|| room.startsWith("WELL_") || room.startsWith("SPEAKER_") || room.startsWith("POOL_")
				|| room.startsWith("_WATER") || room.startsWith("_CONSTRUCTION") || room.startsWith("MONUMENT_NATURE")
				|| room.startsWith("MONUMENT_TORCH") || room.startsWith("MONUMENT_BLOB") || room.startsWith("_BENCH") || room.startsWith("MONUMENT_SCULPTURE")
				|| room.startsWith("FIGHTPIT_") || room.startsWith("_STOCKADE") || room.startsWith("_WATERPUMP")
				|| room.startsWith("STAGE_") || room.startsWith("_EXECUTION") || room.startsWith("MONUMENT_DEATH"))
			return;
		long h = CityPlan.hash(tx * 31 + 7, ty * 17 + 11);
		int x0 = p.blockX(tx), z0 = p.blockZ(ty);
		// colours (stall canopies, carpets) stay the same across one room
		Piece piece = choose(room, sprite, ft, CityPlan.hash(m.roomId(i) * 97 + 13, 7));
		for (int v = 0; v < s; v++)
			for (int u = 0; u < s; u++)
				piece.place(w, x0 + u, p.floorY(i) + 1, z0 + v, u, v, focusDir(tx, ty, bp), CityPlan.hash(x0 + u, z0 + v));
		if (m.flag(i, SyxMap.F_CANDLE))
			light(w, x0 + (s - 1), z0 + (s - 1), p.floorY(i));
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
		if (room.startsWith("PHYSICIAN") && has(sp, "BED"))
			return bed("pink", "white");
		if ((room.startsWith("_HOSPITAL") || room.startsWith("RESTHOME")) && has(sp, "BED"))
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
		else if (room.startsWith("MONUMENT_BLOB"))
			humidifier(w, f);
		else if (room.startsWith("_BENCH"))
			bench(w, f);
		else if (room.startsWith("MONUMENT_SCULPTURE"))
			sculpture(w, f, item.groupName() == null ? "" : item.groupName().toUpperCase());
		else if (room.startsWith("FIGHTPIT_"))
			fightPit(w, f, bp);
		else if (room.startsWith("_STOCKADE"))
			stockade(w, f);
		else if (room.startsWith("_WATERPUMP"))
			pump(w, f);
		else if (room.startsWith("STAGE_"))
			stage(w, f, bp);
		else if (room.startsWith("GRAVEYARD_") && "TREE".equalsIgnoreCase(item.groupName()))
			graveTree(w, f);
		else if (room.startsWith("_EXECUTION"))
			execution(w, f, item.groupName() == null ? "" : item.groupName().toUpperCase());
		else if (room.startsWith("MONUMENT_DEATH"))
			deathMonument(w, f, item.groupName() == null ? "" : item.groupName().toUpperCase());
	}

	/* ------------------------------------------------------ death monuments */

	/** the death monument's three items: a mound of skulls, a head on a spike, the winged Averii sculpture */
	private void deathMonument(WorldWriter w, SyxMap.Furniture f, String group) {
		int x0 = p.blockX(f.x()), z0 = p.blockZ(f.y()), W = f.w() * s, Dp = f.h() * s;
		if (group.contains("SKULL") || group.contains("MOUND"))
			skullMound(w, x0, z0, W, Dp);
		else if (group.contains("SPIKE") || group.contains("HEAD"))
			headOnSpike(w, x0, z0, W, Dp);
		else
			averii(w, x0, z0, W, Dp, f.rot());
	}

	/**
	 * a heap of skeleton skulls: a dome of bone blocks covered in skulls, with skulls stuck to the sides of each step
	 * and a ring of loose ones round the foot
	 */
	private void skullMound(WorldWriter w, int x0, int z0, int W, int Dp) {
		double cx = (W - 1) / 2.0, cz = (Dp - 1) / 2.0, r = Math.max(W, Dp) / 2.0;
		int peak = Math.max(1, (int) Math.round(Math.min(W, Dp) * 0.55)); // core blocks at the centre
		int[] top = new int[W * Dp]; // core height per column, 0 = none
		for (int dz = 0; dz < Dp; dz++)
			for (int dx = 0; dx < W; dx++) {
				double d = Math.hypot((dx - cx) / Math.max(0.5, cx + 0.5), (dz - cz) / Math.max(0.5, cz + 0.5));
				top[dx + dz * W] = d >= 1 ? 0 : (int) Math.round(peak * Math.sqrt(1 - d * d) + 0.25);
			}
		int y = B + 1;
		for (int dz = 0; dz < Dp; dz++)
			for (int dx = 0; dx < W; dx++) {
				int x = x0 + dx, z = z0 + dz, t = top[dx + dz * W];
				long h = CityPlan.hash(x * 5 + 1, z * 3 + 2);
				w.set(x, B, z, P((h & 3) == 0 ? "minecraft:soul_soil" : "minecraft:coarse_dirt"));
				for (int k = 0; k < t; k++)
					w.set(x, y + k, z, P(((h >> k) & 7) == 0 ? "minecraft:dripstone_block" : "minecraft:bone_block"));
				// a skull on top of every column, the bare edges get one too unless the hash leaves a gap
				if (t > 0 || (h & 3) != 0)
					w.set(x, y + t, z, skull(h >> 8));
				// skulls on the sides of the steps, facing out
				for (int dir = 0; dir < 4; dir++) {
					int nx = dx + CityPlan.DX[dir], nz = dz + CityPlan.DY[dir];
					int nt = nx >= 0 && nz >= 0 && nx < W && nz < Dp ? top[nx + nz * W] : -1;
					for (int k = Math.max(0, nt + 1); k < t; k++)
						if (((h >> (dir * 3 + k)) & 3) != 0)
							w.setIfAir(x + CityPlan.DX[dir], y + k, z + CityPlan.DY[dir],
									P("minecraft:skeleton_wall_skull[facing=" + CityPlan.DIR[dir] + "]"));
				}
			}
		if (r >= 2) // the odd candle stuck in the heap
			w.setIfAir(x0 + W / 2, y + top[W / 2 + (Dp / 2) * W] + 1, z0 + Dp / 2, P("minecraft:candle[lit=true,candles=1]"));
	}

	private static BlockState skull(long h) {
		return P("minecraft:skeleton_skull[rotation=" + Math.floorMod(h, 16) + "]");
	}

	/** a sharpened stake with a severed head on it, in a patch of trampled dirt */
	private void headOnSpike(WorldWriter w, int x0, int z0, int W, int Dp) {
		int x = x0 + (W - 1) / 2, z = z0 + (Dp - 1) / 2;
		long h = CityPlan.hash(x, z);
		for (int dz = 0; dz < Dp; dz++)
			for (int dx = 0; dx < W; dx++)
				w.set(x0 + dx, B, z0 + dz, P(((dx + dz + h) & 1) == 0 ? "minecraft:coarse_dirt" : "minecraft:rooted_dirt"));
		int pole = 2 + Math.min(2, s - 1); // taller at bigger scales
		for (int k = 1; k <= pole; k++)
			w.set(x, B + k, z, P("minecraft:stripped_dark_oak_log"));
		w.set(x, B + pole + 1, z, P("minecraft:dark_oak_fence")); // the sharpened tip, through the neck
		w.set(x, B + pole + 2, z, P("minecraft:zombie_head[rotation=" + Math.floorMod(h >> 4, 16) + "]"));
		// blood run down the stake and pooled at its foot
		w.setIfAir(x + 1, B + 1, z, P("minecraft:redstone_wire"));
		if ((h & 1) == 0)
			w.setIfAir(x, B + 1, z + 1, P("minecraft:redstone_wire"));
	}

	/**
	 * the Averii: a horned, winged demon of blackstone on a dark pedestal, wings spread behind it. rot = the game's
	 * quarter turns; the figure faces north at 0, east at 1, south at 2, west at 3.
	 */
	private void averii(WorldWriter w, int x0, int z0, int W, int Dp, int rot) {
		boolean sideways = (rot & 1) == 1;
		int y = B + 1;
		for (int dz = 0; dz < Dp; dz++)
			for (int dx = 0; dx < W; dx++) {
				int x = x0 + dx, z = z0 + dz;
				boolean edge = dx == 0 || dz == 0 || dx == W - 1 || dz == Dp - 1;
				w.set(x, y, z, P(edge && W >= 4 ? "minecraft:polished_blackstone_bricks" : "minecraft:polished_blackstone"));
				if (W >= 4 && !edge)
					w.set(x, y + 1, z, P("minecraft:chiseled_polished_blackstone"));
			}
		int f = y + (W >= 4 ? 2 : 1); // figure's feet
		int across = sideways ? Dp : W, deep = sideways ? W : Dp, a0 = sideways ? z0 : x0, d0 = sideways ? x0 : z0;
		boolean big = Math.min(W, Dp) >= 4;
		int fw = big ? (across % 2 == 0 ? 2 : 1) : 1;
		int fa0 = a0 + (across - fw) / 2;
		int fd = d0 + (deep - 1) / 2; // the figure is one block deep; its wings stand one block behind it
		int back = (rot == 0 || rot == 3) ? 1 : -1; // facing -z/-x puts the back towards +z/+x
		int wd = fd + back;
		if (wd < d0 || wd >= d0 + deep)
			wd = fd; // no room behind: wings in the figure's own plane
		int legs = big ? 2 : 1, torso = big ? 3 : 1;
		for (int j = 0; j < legs; j++)
			for (int c = 0; c < fw; c++)
				setAD(w, sideways, fa0 + c, fd, f + j, "minecraft:blackstone");
		for (int j = 0; j < torso; j++)
			for (int c = 0; c < fw; c++)
				setAD(w, sideways, fa0 + c, fd, f + legs + j, "minecraft:polished_blackstone");
		if (big) // arms hanging, claws at the bottom
			for (int j = 0; j < torso; j++) {
				String b = j == 0 ? "minecraft:polished_blackstone_wall" : "minecraft:blackstone";
				setAD(w, sideways, fa0 - 1, fd, f + legs + j, b);
				setAD(w, sideways, fa0 + fw, fd, f + legs + j, b);
			}
		int head = f + legs + torso;
		for (int c = 0; c < fw; c++)
			setAD(w, sideways, fa0 + c, fd, head, "minecraft:chiseled_polished_blackstone"); // a grim face
		// horns: dripstone points from the head's outer corners (one horn pair on a 1-wide head goes beside it)
		String horn = "minecraft:pointed_dripstone[vertical_direction=up,thickness=tip]";
		if (fw == 2) {
			setAD(w, sideways, fa0, fd, head + 1, horn);
			setAD(w, sideways, fa0 + 1, fd, head + 1, horn);
		} else {
			setAD(w, sideways, fa0, fd, head + 1, horn);
		}
		// wings: from the shoulders out and up, a bony leading edge over a dark membrane, tips past the head
		// the wings taper as they rise, so they read as wings and not a cape; tips stand higher than the horns
		int span = big ? Math.max(3, (across - fw) / 2 + 2) : 1;
		int shoulder = f + legs + torso - 1;
		for (int o = 1; o <= span; o++) {
			int topY = shoulder + o + (o == span ? 1 : 0);
			int botY = topY - Math.max(o == span ? 1 : 2, span + 1 - o);
			if (!big)
				botY = topY = shoulder + 1;
			for (int side = -1; side <= 1; side += 2) {
				int a = side < 0 ? fa0 - o : fa0 + fw - 1 + o;
				for (int yy = botY; yy <= topY; yy++)
					setAD(w, sideways, a, wd, yy, yy == topY ? "minecraft:deepslate_tiles" : "minecraft:polished_deepslate");
			}
		}
		// where the wings join the back
		if (wd != fd)
			for (int c = 0; c < fw; c++)
				setAD(w, sideways, fa0 + c, wd, shoulder, "minecraft:polished_deepslate");
	}

	/* ----------------------------------------------------------- graveyard */

	private String spriteAt(int tx, int ty, SyxMap.Blueprint bp, int room) {
		if (!m.inBounds(tx, ty))
			return null;
		int j = m.idx(tx, ty);
		if (m.roomId(j) != room || !p.hasFurniture(j))
			return null;
		SyxMap.FurnTile t = bp.tiles.get(m.furnTile[j] & 0xFF);
		return t == null || t.spriteKey() == null ? "" : t.spriteKey().toUpperCase();
	}

	/** graveyard tiles, laid out as the player arranged them: headstones, grave plots, flowerbeds, pathways */
	private void graveTile(WorldWriter w, int tx, int ty, int i, String sprite, SyxMap.Blueprint bp) {
		int x0 = p.blockX(tx), z0 = p.blockZ(ty), room = m.roomId(i);
		long th = CityPlan.hash(tx * 13 + 1, ty * 7 + 3);
		for (int v = 0; v < s; v++)
			for (int u = 0; u < s; u++) {
				int x = x0 + u, z = z0 + v;
				long h = CityPlan.hash(x, z);
				if (sprite.startsWith("TOMBSTONE")) {
					w.set(x, B, z, P("minecraft:grass_block"));
					// the headstone stands at the plot's end of the tile
					int k = -1;
					for (int d = 0; d < 4 && k < 0; d++) {
						String n = spriteAt(tx + CityPlan.DX[d], ty + CityPlan.DY[d], bp, room);
						if (n != null && n.startsWith("GRAVE"))
							k = d;
					}
					boolean edge = k < 0 ? v == s - 1 : (k == 0 && v == 0) || (k == 2 && v == s - 1) || (k == 3 && u == 0) || (k == 1 && u == s - 1);
					if (edge) {
						String[] stones = { "minecraft:stone_brick_wall", "minecraft:mossy_stone_brick_wall", "minecraft:cobblestone_wall",
								"minecraft:andesite_wall", "minecraft:mossy_cobblestone_wall" };
						w.set(x, B + 1, z, P(stones[(int) (th % stones.length)]));
						if ((th >> 8) % 5 == 0)
							w.set(x, B + 2, z, P("minecraft:candle[candles=1,lit=true]"));
					}
				} else if (sprite.startsWith("GRAVE")) {
					w.set(x, B, z, P((h & 3) == 0 ? "minecraft:rooted_dirt" : (h & 3) == 1 ? "minecraft:podzol" : "minecraft:coarse_dirt"));
				} else if (sprite.startsWith("FLOWER")) {
					w.set(x, B, z, P("minecraft:grass_block"));
					w.set(x, B + 1, z, CityPlan.Blocks1.flower((int) (h >> 5)));
				} else if (sprite.startsWith("MON")) {
					w.set(x, B, z, P("minecraft:grass_block")); // under a tree, planted by graveTree
				} else {
					w.set(x, B, z, P("minecraft:dirt_path"));
				}
			}
	}

	/** a graveyard tree: a dark spruce, as big as its item */
	private void graveTree(WorldWriter w, SyxMap.Furniture f) {
		int x0 = p.blockX(f.x()), z0 = p.blockZ(f.y()), W = f.w() * s, Dp = f.h() * s;
		boolean wide = W >= 4 && W % 2 == 0;
		int tw = wide ? 2 : 1, tx0 = x0 + (W - tw) / 2, tz0 = z0 + (Dp - tw) / 2;
		// the canopy is centred on the trunk, so it covers it evenly
		double cx = tx0 + (tw - 1) / 2.0, cz = tz0 + (tw - 1) / 2.0;
		int trunk = 4 + W, maxR = Math.max(1, W / 2);
		BlockState log = P("minecraft:spruce_log"), leaves = P("minecraft:spruce_leaves[persistent=true]");
		for (int y = B + 1; y <= B + trunk; y++)
			for (int a = 0; a < tw; a++)
				for (int b = 0; b < tw; b++)
					w.set(tx0 + a, y, tz0 + b, log);
		int top = B + trunk + 1;
		int gx0 = (int) Math.floor(cx) - maxR - 2, gz0 = (int) Math.floor(cz) - maxR - 2;
		for (int j = 0; j <= trunk - 2; j++) { // layers down from the tip, widening in steps
			// a 2x2 trunk needs 0.75 to cover its corners (0.71 from the middle)
			double r = Math.min(maxR, (j + 1) / 2) + (wide ? 0.75 : 0) + (j % 2 == 1 ? 0.3 : 0);
			int y = top - j;
			for (int z = gz0; z <= gz0 + 2 * maxR + 5; z++)
				for (int x = gx0; x <= gx0 + 2 * maxR + 5; x++)
					if (Math.hypot(x - cx, z - cz) <= r + 0.2)
						w.setIfAir(x, y, z, leaves);
		}
		// the tip over the whole trunk
		for (int a = 0; a < tw; a++)
			for (int b = 0; b < tw; b++)
				w.setIfAir(tx0 + a, top + 1, tz0 + b, leaves);
	}

	/* ------------------------------------------------------------ chambers */

	/**
	 * A rich person's house: a canopy-headed bed, red carpets with a gold runner down the aisle, cushioned dark oak
	 * benches, a blackstone fireplace with gold and candles, and corners heaped with gold, gems and chests.
	 * Tile indices are the game's chamber tiles (3/4 bed head, 1/2 bed body, 5/6 benches, 7/8 mantel, 9 carpet,
	 * 10 hoard, 11/12 aisle).
	 */
	private void chamberTile(WorldWriter w, int tx, int ty, int i, int idx, SyxMap.Blueprint bp) {
		int x0 = p.blockX(tx), z0 = p.blockZ(ty), room = m.roomId(i);
		SyxMap.Room r = room > 0 && room <= m.rooms.size() ? m.rooms.get(room - 1) : null;
		double ccx = r == null ? x0 : (p.blockX(r.x1()) + p.blockX(r.x2())) / 2.0, ccz = r == null ? z0 : (p.blockZ(r.y1()) + p.blockZ(r.y2())) / 2.0;
		// direction from the room's middle out to this tile: where its wall is
		double ox = x0 + s / 2.0 - ccx, oz = z0 + s / 2.0 - ccz;
		int out = Math.abs(ox) > Math.abs(oz) ? (ox > 0 ? 1 : 3) : (oz > 0 ? 2 : 0);
		for (int v = 0; v < s; v++)
			for (int u = 0; u < s; u++) {
				int x = x0 + u, z = z0 + v;
				long h = CityPlan.hash(x * 3 + 7, z * 5 + 1);
				boolean wallSide = (out == 0 && v == 0) || (out == 2 && v == s - 1) || (out == 3 && u == 0) || (out == 1 && u == s - 1);
				switch (idx) {
				case 9 -> w.set(x, B + 1, z, P("minecraft:red_carpet"));
				case 11, 12 -> w.set(x, B + 1, z, P("minecraft:yellow_carpet"));
				case 3, 4 -> { // bed head: headboard against the wall, beds' heads in front of it
					int k = bedBody(tx, ty, bp, room);
					boolean back = (k == 2 && v == 0) || (k == 0 && v == s - 1) || (k == 1 && u == 0) || (k == 3 && u == s - 1);
					if (k < 0 || back) {
						w.set(x, B + 1, z, P("minecraft:dark_oak_planks"));
						w.set(x, B + 2, z, P(((u + v) & 1) == 0 ? "minecraft:gold_block" : "minecraft:dark_oak_planks"));
						w.set(x, B + 3, z, P("minecraft:dark_oak_fence"));
					} else if (s == 1 || !back) {
						int fx = x + CityPlan.DX[k], fz = z + CityPlan.DY[k];
						String facing = CityPlan.DIR[(k + 2) % 4]; // head towards the headboard
						w.set(x, B + 1, z, P("minecraft:red_bed[part=head,facing=" + facing + "]"), WorldWriter.FLAGS_RAW);
						w.set(fx, B + 1, fz, P("minecraft:red_bed[part=foot,facing=" + facing + "]"), WorldWriter.FLAGS_RAW);
					}
				}
				case 1, 2 -> { // the rest of the bed area: a red spread, the beds' feet are set by the head tile
					if (w.get(x, B + 1, z).isAir())
						w.set(x, B + 1, z, P("minecraft:red_carpet"));
				}
				case 5, 6 -> { // cushioned benches, backs to the wall
					w.set(x, B + 1, z, P("minecraft:dark_oak_stairs[facing=" + CityPlan.DIR[out] + "]"));
					Seats.cushion(w.level, x, B + 1, z, out, "minecraft:red_carpet");
				}
				case 7, 8 -> { // fireplace: blackstone against the wall, fire / gold and candles in front
					if (wallSide || s == 1) {
						for (int y = B + 1; y <= B + 3; y++)
							w.set(x, y, z, P("minecraft:polished_blackstone_bricks"));
					} else if (idx == 7) {
						w.set(x, B + 1, z, P("minecraft:campfire[lit=true]"));
						w.set(x, B + 3, z, P("minecraft:chiseled_polished_blackstone"));
					} else {
						w.set(x, B + 1, z, P("minecraft:gold_block"));
						w.set(x, B + 2, z, P("minecraft:candle[candles=4,lit=true]"));
					}
				}
				case 10 -> { // the hoard
					int pick = (int) (h % 20);
					String b = pick < 7 ? "minecraft:gold_block" : pick < 10 ? "minecraft:raw_gold_block" : pick < 12 ? "minecraft:emerald_block"
							: pick < 13 ? "minecraft:diamond_block" : pick < 16 ? "minecraft:chest[facing=" + CityPlan.DIR[(out + 2) % 4] + "]"
							: pick < 18 ? "minecraft:decorated_pot" : "minecraft:gold_block";
					w.set(x, B + 1, z, P(b));
					if (pick < 7 && ((h >> 6) & 1) == 0)
						w.set(x, B + 2, z, P("minecraft:gold_block")); // stacked bullion
					else if (pick >= 18)
						w.set(x, B + 2, z, P("minecraft:lantern"));
				}
				default -> {
				}
				}
			}
	}

	static final String PAINTING_TAG = "syxcraft_painting";
	private static final Direction[] FACING = { Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST };

	/**
	 * paintings along a chamber's walls: about every other wall tile, at eye level, never over a window or door and
	 * never overlapping furniture (the game's own painting rules check the wall and the space in front)
	 */
	private void paintings(WorldWriter w, SyxMap.Room r) {
		List<Holder<PaintingVariant>> all = new ArrayList<>();
		w.level.registryAccess().lookupOrThrow(Registries.PAINTING_VARIANT).getTagOrEmpty(PaintingVariantTags.PLACEABLE)
				.forEach(all::add);
		if (all.isEmpty())
			return;
		int maxH = Math.max(1, Math.min(3, H - 1)), maxW = s + 2, hung = 0;
		for (int ty = r.y1(); ty <= r.y2(); ty++)
			for (int tx = r.x1(); tx <= r.x2(); tx++) {
				if (!m.inBounds(tx, ty) || m.roomId(m.idx(tx, ty)) != r.id())
					continue;
				for (int dir = 0; dir < 4; dir++) {
					int ntx = tx + CityPlan.DX[dir], nty = ty + CityPlan.DY[dir];
					if (!m.inBounds(ntx, nty) || p.kind[m.idx(ntx, nty)] != CityPlan.WALL)
						continue;
					long h = CityPlan.hash(tx * 7 + dir, ty * 11 + 3);
					// the block in front of the middle of this tile's wall side, facing into the room
					int x = p.blockX(tx) + (dir == 1 ? s - 1 : dir == 3 ? 0 : s / 2);
					int z = p.blockZ(ty) + (dir == 2 ? s - 1 : dir == 0 ? 0 : s / 2);
					Direction facing = FACING[(dir + 2) % 4];
					// eye level, then a row every 4 blocks further up while the room is tall enough; each row picks
					// its own wall tiles, so they don't stack in columns
					int ceiling = B + p.heightAt(x - p.X0, z - p.Z0);
					for (int row = 0, y = B + 2; y + 1 <= ceiling; row++, y += 4) {
						if (((h >> row) & 1) != 0)
							continue;
						hung += hang(w, all, new BlockPos(x, y, z), facing, h >> 8 + row, maxH, maxW);
					}
				}
			}
		dev.sos2mc.syxcraft.Syxcraft.LOG.info("{} paintings in chamber {}", hung, r.id());
	}

	/** one painting at pos if any variant fits there (and isn't over a window); returns 1 if one went up */
	private int hang(WorldWriter w, List<Holder<PaintingVariant>> all, BlockPos pos, Direction facing, long h, int maxH, int maxW) {
		List<Holder<PaintingVariant>> vs = new ArrayList<>(all);
		java.util.Collections.shuffle(vs, new java.util.Random(h));
		for (Holder<PaintingVariant> v : vs) {
			if (v.value().height() > maxH || v.value().width() > maxW)
				continue;
			Painting pt = new Painting(w.level, pos, facing, v);
			if (!pt.survives() || overWindow(w, pt, facing))
				continue;
			pt.addTag(PAINTING_TAG);
			w.level.addFreshEntity(pt);
			return 1;
		}
		return 0;
	}

	/** true if any block behind the painting is glass, a pane, bars or a door (or not there at all) */
	private static boolean overWindow(WorldWriter w, Painting pt, Direction facing) {
		var box = pt.getBoundingBox().deflate(0.1);
		for (BlockPos b : BlockPos.betweenClosed(BlockPos.containing(box.minX, box.minY, box.minZ),
				BlockPos.containing(box.maxX, box.maxY, box.maxZ))) {
			BlockState st = w.level.getBlockState(b.relative(facing.getOpposite()));
			if (st.isAir() || st.getBlock() instanceof net.minecraft.world.level.block.IronBarsBlock
					|| st.getBlock() instanceof net.minecraft.world.level.block.TransparentBlock
					|| st.getBlock() instanceof net.minecraft.world.level.block.DoorBlock || st.is(net.minecraft.tags.BlockTags.TRAPDOORS))
				return true;
		}
		return false;
	}

	/** direction from a bed-head tile to the bed body (tiles 1/2), -1 if none */
	private int bedBody(int tx, int ty, SyxMap.Blueprint bp, int room) {
		for (int k = 0; k < 4; k++) {
			int nx = tx + CityPlan.DX[k], ny = ty + CityPlan.DY[k];
			if (!m.inBounds(nx, ny) || m.roomId(m.idx(nx, ny)) != room || !p.hasFurniture(m.idx(nx, ny)))
				continue;
			int t = m.furnTile[m.idx(nx, ny)] & 0xFF;
			if (t == 1 || t == 2)
				return k;
		}
		return -1;
	}

	/* --------------------------------------------------------------- stage */

	/** a wooden platform 2 blocks high, with two steps up all the way round (the game's outer ring) */
	private void stage(WorldWriter w, SyxMap.Furniture f, SyxMap.Blueprint bp) {
		int x0 = p.blockX(f.x()), z0 = p.blockZ(f.y()), W = f.w() * s, Dp = f.h() * s;
		int ring = s; // the outer tile ring, in blocks
		for (int dz = 0; dz < Dp; dz++)
			for (int dx = 0; dx < W; dx++) {
				int x = x0 + dx, z = z0 + dz;
				int e = Math.min(Math.min(dx, dz), Math.min(W - 1 - dx, Dp - 1 - dz)); // blocks in from the edge
				if (e >= ring) {
					w.set(x, B + 1, z, P("minecraft:spruce_planks"));
					w.set(x, B + 2, z, P(e == ring ? "minecraft:stripped_spruce_log[axis=y]" : "minecraft:spruce_planks"));
					continue;
				}
				// steps facing the platform: the side the nearest edge is on decides the facing
				String facing = dx == e ? "east" : W - 1 - dx == e ? "west" : dz == e ? "south" : "north";
				boolean corner = (dx < ring || W - 1 - dx < ring) && (dz < ring || Dp - 1 - dz < ring);
				int step = Math.min(e, 1); // 0 = outer step, 1 = inner step
				if (corner) {
					for (int y = B + 1; y <= B + 1 + step; y++)
						w.set(x, y, z, P("minecraft:spruce_planks"));
				} else {
					if (step == 1)
						w.set(x, B + 1, z, P("minecraft:spruce_planks"));
					w.set(x, B + 1 + step, z, P("minecraft:spruce_stairs[facing=" + facing + "]"));
				}
			}
		// lanterns on posts at the platform's corners
		int[][] cs = { { ring, ring }, { W - 1 - ring, ring }, { ring, Dp - 1 - ring }, { W - 1 - ring, Dp - 1 - ring } };
		for (int[] c : cs) {
			w.set(x0 + c[0], B + 3, z0 + c[1], P("minecraft:spruce_fence"));
			w.set(x0 + c[0], B + 4, z0 + c[1], P("minecraft:lantern"));
		}
	}

	/* ----------------------------------------------------------- execution */

	private void execution(WorldWriter w, SyxMap.Furniture f, String group) {
		int x0 = p.blockX(f.x()), z0 = p.blockZ(f.y()), bw = f.w() * s, bh = f.h() * s;
		boolean alongZ = bh >= bw;
		int A = alongZ ? bw : bh, L = alongZ ? bh : bw;
		if (group.contains("GALLOWS")) {
			// a scaffold: plank deck 3 up on log posts, stairs at one end, a beam with chains over the middle
			int deck = B + 3, mid0 = (A - 1) / 2, mid1 = A / 2;
			int stairs = Math.min(3, L / 4);
			String up = alongZ ? "south" : "east"; // climbing towards the far end
			for (int b = 0; b < L; b++)
				for (int a = 0; a < A; a++) {
					int x = alongZ ? x0 + a : x0 + b, z = alongZ ? z0 + b : z0 + a;
					if (b < stairs) {
						for (int y = B + 1; y < B + 1 + b; y++)
							w.set(x, y, z, P("minecraft:spruce_planks"));
						w.set(x, B + 1 + b, z, P("minecraft:spruce_stairs[facing=" + up + "]"));
						continue;
					}
					boolean post = (a == 0 || a == A - 1) && (b == stairs || b == L - 1 || (b - stairs) % 4 == 0);
					if (post)
						for (int y = B + 1; y < deck; y++)
							w.set(x, y, z, P("minecraft:spruce_log"));
					w.set(x, deck, z, P("minecraft:spruce_planks"));
					boolean upright = (a == mid0 || a == mid1) && (b == stairs + 1 || b == L - 2);
					if (upright)
						for (int y = deck + 1; y <= deck + 4; y++)
							w.set(x, y, z, P("minecraft:spruce_log"));
					else if ((a == mid0 || a == mid1) && b > stairs + 1 && b < L - 2) {
						w.set(x, deck + 4, z, P("minecraft:stripped_spruce_log[axis=" + (alongZ ? "z" : "x") + "]"));
						if ((b - stairs) % 3 == 0 && a == mid0)
							w.set(x, deck + 3, z, P("minecraft:iron_chain"));
					}
				}
		} else if (group.contains("CHOPPING")) {
			for (int b = 1; b < L; b += 3)
				for (int a = 1; a < A; a += 3) {
					int x = alongZ ? x0 + a : x0 + b, z = alongZ ? z0 + b : z0 + a;
					w.set(x, B + 1, z, P("minecraft:stripped_oak_log"));
				}
		} else if (group.contains("CAGE") || group.contains("GIBBET")) {
			int x = x0 + bw / 2, z = z0 + bh / 2;
			for (int y = B + 1; y <= B + 5; y++)
				w.set(x, y, z, P("minecraft:spruce_log"));
			w.set(x + 1, B + 5, z, P("minecraft:spruce_fence"));
			w.set(x + 1, B + 4, z, P("minecraft:iron_chain"));
			w.set(x + 1, B + 3, z, P("minecraft:iron_bars"));
			w.set(x + 1, B + 2, z, P("minecraft:iron_bars"));
		} else { // a cross
			int x = x0 + bw / 2, z = z0 + bh / 2;
			for (int y = B + 1; y <= B + 5; y++)
				w.set(x, y, z, P("minecraft:spruce_log"));
			w.set(x - 1, B + 4, z, P("minecraft:spruce_log[axis=x]"));
			w.set(x + 1, B + 4, z, P("minecraft:spruce_log[axis=x]"));
		}
	}

	/* ---------------------------------------------------------- sculptures */

	private void sculpture(WorldWriter w, SyxMap.Furniture f, String group) {
		int x0 = p.blockX(f.x()), z0 = p.blockZ(f.y()), W = f.w() * s, Dp = f.h() * s;
		if (group.contains("PILLAR"))
			column(w, x0, z0, W, Dp);
		else
			statue(w, x0, z0, W, Dp, (f.rot() & 1) == 1); // rotated a quarter turn in the game: face east/west
	}

	/** a classical column: stepped quartz platform, moulded base, round fluted-looking shaft, capital and abacus */
	private void column(WorldWriter w, int x0, int z0, int W, int Dp) {
		double cx = (W - 1) / 2.0, cz = (Dp - 1) / 2.0;
		// shaft radius for any size: 2x2 tiles (4 blocks) used to get no shaft at all, leaving the capital floating
		double r = Math.max(0.75, W * 0.27);
		int L = Math.max(4, W * 2 - 2);
		int y = B + 1;
		for (int dz = 0; dz < Dp; dz++)
			for (int dx = 0; dx < W; dx++) {
				double d = Math.hypot(dx - cx, dz - cz);
				int x = x0 + dx, z = z0 + dz;
				w.set(x, y, z, P("minecraft:smooth_quartz")); // stylobate
				boolean inner = dx > 0 && dz > 0 && dx < W - 1 && dz < Dp - 1;
				if (inner || W < 5)
					w.set(x, y + 1, z, P("minecraft:quartz_bricks"));
				if (d <= r + 1.2)
					w.set(x, y + 2, z, P("minecraft:chiseled_quartz_block")); // base moulding, wider than the shaft
				if (d <= r)
					for (int k = 0; k < L; k++)
						w.set(x, y + 3 + k, z, P("minecraft:quartz_pillar")); // shaft
				if (d <= r + 1.2)
					w.set(x, y + 3 + L, z, P("minecraft:chiseled_quartz_block")); // capital flares out
				w.set(x, y + 4 + L, z, P("minecraft:smooth_quartz")); // square abacus over the whole footprint
			}
	}

	/** a simple white figure on a polished pedestal */
	private void statue(WorldWriter w, int x0, int z0, int W, int Dp, boolean sideways) {
		int y = B + 1;
		for (int dz = 0; dz < Dp; dz++)
			for (int dx = 0; dx < W; dx++) {
				int x = x0 + dx, z = z0 + dz;
				w.set(x, y, z, P("minecraft:smooth_quartz"));
				boolean inner = W < 5 || (dx > 0 && dz > 0 && dx < W - 1 && dz < Dp - 1);
				if (inner) {
					w.set(x, y + 1, z, P("minecraft:polished_andesite"));
					w.set(x, y + 2, z, P("minecraft:chiseled_quartz_block"));
				}
			}
		// a figure with Minecraft player proportions: as wide as the torso is its head, arms 1 wide, two legs.
		// Its width and depth match the footprint's parity, so it stands exactly in the middle. 'across' is the
		// figure's shoulder line: x normally, z when the game turned the statue a quarter.
		int f = y + 3;
		int across = sideways ? Dp : W, deep = sideways ? W : Dp, a0 = sideways ? z0 : x0, d0 = sideways ? x0 : z0;
		int fw = across % 2 == 0 ? 2 : 1, fd = deep % 2 == 0 ? 2 : 1;
		int fa0 = a0 + (across - fw) / 2, fd0 = d0 + (deep - fd) / 2;
		boolean big = Math.min(W, Dp) >= 4;
		int legs = big ? 3 : 1, torso = big ? 3 : 1, head = big ? fw : 1;
		for (int k = 0; k < fd; k++) {
			int dd = fd0 + k;
			for (int j = 0; j < legs; j++)
				for (int c = 0; c < fw; c++)
					setAD(w, sideways, fa0 + c, dd, f + j, "minecraft:quartz_pillar");
			for (int j = 0; j < torso; j++) {
				for (int c = 0; c < fw; c++)
					setAD(w, sideways, fa0 + c, dd, f + legs + j, j == torso - 1 ? "minecraft:chiseled_quartz_block" : "minecraft:quartz_block");
				if (big) { // arms
					setAD(w, sideways, fa0 - 1, dd, f + legs + j, "minecraft:quartz_pillar");
					setAD(w, sideways, fa0 + fw, dd, f + legs + j, "minecraft:quartz_pillar");
				}
			}
			for (int j = 0; j < head; j++)
				for (int c = 0; c < fw; c++)
					setAD(w, sideways, fa0 + c, dd, f + legs + torso + j, "minecraft:smooth_quartz");
		}
	}

	/** set a block by (across, deep) coordinates: (x, z) normally, (z, x) for a figure turned sideways */
	private void setAD(WorldWriter w, boolean sideways, int a, int d, int y, String block) {
		if (sideways)
			w.set(d, y, a, P(block));
		else
			w.set(a, y, d, P(block));
	}

	/* ----------------------------------------------------------- fight pit */

	/**
	 * A small wooden coliseum laid out from the game's own fight pit tiles: sand arena, a log rim around it, stands of
	 * spruce stairs rising away from the arena, an outer wall with towers, and open gateways where the stairs are.
	 */
	private void fightPit(WorldWriter w, SyxMap.Furniture f, SyxMap.Blueprint bp) {
		int x0 = p.blockX(f.x()), z0 = p.blockZ(f.y()), W = f.w() * s, Dp = f.h() * s;
		// 0 outside, 1 arena, 2 rim, 3 seat, 4 wall, 5 tower, 6 passage tunnel, 7 ramp up to the stands,
		// 8 gap in the outer wall (the game leaves stands tiles in the wall line where a passage comes out)
		byte[] cat = new byte[W * Dp];
		for (int ty = 0; ty < f.h(); ty++)
			for (int tx = 0; tx < f.w(); tx++) {
				int i = m.idx(f.x() + tx, f.y() + ty);
				if (!m.inBounds(f.x() + tx, f.y() + ty) || m.roomId(i) != f.room())
					continue;
				byte c = 1;
				if (p.hasFurniture(i)) {
					SyxMap.FurnTile ft = bp.tiles.get(m.furnTile[i] & 0xFF);
					String sp = ft == null || ft.spriteKey() == null ? "" : ft.spriteKey().toUpperCase();
					c = sp.startsWith("RIM") ? 2 : sp.startsWith("SEAT") ? 3 : sp.startsWith("WALL") ? 4
							: sp.startsWith("TOWER") ? 5 : sp.startsWith("STAIRS_RIGHT") ? 7 : sp.startsWith("STAIRS") ? 6 : (byte) 1;
					if (c == 3 && (tx == 0 || ty == 0 || tx == f.w() - 1 || ty == f.h() - 1))
						c = 8;
				}
				for (int v = 0; v < s; v++)
					for (int u = 0; u < s; u++)
						cat[tx * s + u + (ty * s + v) * W] = c;
			}
		// block distance from the arena, through the room
		int[] dist = new int[W * Dp];
		java.util.Arrays.fill(dist, Integer.MAX_VALUE);
		java.util.ArrayDeque<Integer> q = new java.util.ArrayDeque<>();
		for (int j = 0; j < cat.length; j++)
			if (cat[j] == 1) {
				dist[j] = 0;
				q.add(j);
			}
		while (!q.isEmpty()) {
			int j = q.poll(), jx = j % W, jz = j / W;
			for (int dz = -1; dz <= 1; dz++)
				for (int dx = -1; dx <= 1; dx++) {
					int nx = jx + dx, nz = jz + dz;
					if (nx < 0 || nz < 0 || nx >= W || nz >= Dp)
						continue;
					int n = nx + nz * W;
					if (cat[n] != 0 && dist[n] > dist[j] + 1) {
						dist[n] = dist[j] + 1;
						q.add(n);
					}
				}
		}
		int maxSeat = 2;
		for (int j = 0; j < cat.length; j++)
			if (cat[j] == 3 && dist[j] != Integer.MAX_VALUE)
				maxSeat = Math.max(maxSeat, Math.min(dist[j] + 1, 10));
		int wallTop = B + maxSeat + 1;
		for (int dz = 0; dz < Dp; dz++)
			for (int dx = 0; dx < W; dx++) {
				int j = dx + dz * W, x = x0 + dx, z = z0 + dz;
				int d = dist[j] == Integer.MAX_VALUE ? maxSeat : dist[j];
				switch (cat[j]) {
				case 2 -> { // rim: a low log wall with a rail
					w.set(x, B + 1, z, P("minecraft:stripped_spruce_log"));
					w.set(x, B + 2, z, P("minecraft:stripped_spruce_log"));
					w.set(x, B + 3, z, P("minecraft:spruce_fence"));
				}
				case 3 -> { // stands
					int h = Math.max(2, Math.min(d + 1, 10));
					for (int y = B + 1; y < B + h; y++)
						w.set(x, y, z, P("minecraft:spruce_planks"));
					w.set(x, B + h, z, P("minecraft:spruce_stairs[facing=" + awayFrom(dist, cat, W, Dp, dx, dz) + "]"));
				}
				case 4 -> { // outer wall, with log posts
					boolean post = Math.floorMod(x + z, 4) == 0;
					for (int y = B + 1; y <= wallTop; y++)
						w.set(x, y, z, P(post ? "minecraft:spruce_log" : "minecraft:spruce_planks"));
					w.set(x, wallTop + 1, z, P("minecraft:spruce_fence"));
				}
				case 5 -> { // towers
					for (int y = B + 1; y <= wallTop + 3; y++)
						w.set(x, y, z, P("minecraft:spruce_log"));
					w.set(x, wallTop + 4, z, P("minecraft:lantern"));
				}
				case 6 -> { // vomitorium: a tunnel from outside to the arena, the stands carry on above it
					int h = Math.max(2, Math.min(d + 1, 10));
					for (int y = B + 4; y < Math.max(B + 5, B + h); y++)
						w.set(x, y, z, P("minecraft:spruce_planks"));
					if (h >= 5)
						w.set(x, B + h, z, P("minecraft:spruce_stairs[facing=" + awayFrom(dist, cat, W, Dp, dx, dz) + "]"));
				}
				case 7 -> { // a ramp of stairs from the arena floor up into the stands, rising one per block
					int h = Math.max(1, Math.min(d, 10));
					for (int y = B + 1; y < B + h; y++)
						w.set(x, y, z, P("minecraft:spruce_planks"));
					w.set(x, B + h, z, P("minecraft:spruce_stairs[facing=" + awayFrom(dist, cat, W, Dp, dx, dz) + "]"));
				}
				case 8 -> { // the passage's way out through the outer wall
					for (int y = B + 4; y <= wallTop; y++)
						w.set(x, y, z, P("minecraft:spruce_planks"));
					w.set(x, wallTop + 1, z, P("minecraft:spruce_fence"));
				}
				default -> {
				}
				}
			}
	}

	/** stair facing for a seat: its back to the higher rows, away from the arena */
	private static String awayFrom(int[] dist, byte[] cat, int W, int Dp, int dx, int dz) {
		int best = -1, bestD = Integer.MIN_VALUE;
		for (int k = 0; k < 4; k++) {
			int nx = dx + CityPlan.DX[k], nz = dz + CityPlan.DY[k];
			if (nx < 0 || nz < 0 || nx >= W || nz >= Dp || cat[nx + nz * W] == 0)
				continue;
			int d = dist[nx + nz * W];
			if (d != Integer.MAX_VALUE && d > bestD) {
				bestD = d;
				best = k;
			}
		}
		return CityPlan.DIR[best < 0 ? 0 : best];
	}

	/* ------------------------------------------------------------ stockade */

	/** the gate in the palisade (the room's entrance item), and the prisoners' camp scattered over the yard */
	private void stockade(WorldWriter w, SyxMap.Furniture f) {
		int x0 = p.blockX(f.x()), z0 = p.blockZ(f.y()), W = f.w() * s, Dp = f.h() * s;
		boolean wallAlongZ = f.w() < f.h();
		int top = B + H + 1;
		for (int dz = 0; dz < Dp; dz++)
			for (int dx = 0; dx < W; dx++) {
				int x = x0 + dx, z = z0 + dz;
				w.set(x, B + 1, z, P("minecraft:spruce_fence_gate[facing=" + (wallAlongZ ? "east" : "south") + "]"));
				for (int y = B + 2; y < top - 1; y++)
					w.set(x, y, z, P("minecraft:air"));
				w.set(x, top - 1, z, P("minecraft:spruce_log[axis=" + (wallAlongZ ? "z" : "x") + "]"));
				w.set(x, top, z, P("minecraft:spruce_fence"));
			}
		SyxMap.Room r = f.room() > 0 && f.room() <= m.rooms.size() ? m.rooms.get(f.room() - 1) : null;
		if (r == null || r.x2() <= r.x1())
			return;
		String[] stuff = { "minecraft:barrel[facing=up]", "minecraft:hay_block", "minecraft:cauldron", "minecraft:crafting_table",
				"minecraft:campfire[lit=true]", "minecraft:composter", "minecraft:chest[facing=south]" };
		int bx0 = p.blockX(r.x1()), bz0 = p.blockZ(r.y1()), bx1 = p.blockX(r.x2()) - 1, bz1 = p.blockZ(r.y2()) - 1;
		for (int z = bz0 + 2; z <= bz1 - 2; z += 4)
			for (int x = bx0 + 2; x <= bx1 - 2; x += 4) {
				long h = CityPlan.hash(x * 7 + 1, z * 3 + 5);
				int px = x + (int) (h % 2), pz = z + (int) ((h >> 4) % 2);
				if (!inRoom(px, pz, f.room()) || !inRoom(px + 1, pz + 1, f.room()) || !inRoom(px - 1, pz - 1, f.room())
						|| !w.get(px, B + 1, pz).isAir())
					continue;
				int kind = (int) ((h >> 8) % 10);
				if (kind < 3) { // a bed, head and foot
					boolean nsBed = ((h >> 12) & 1) == 0;
					int fx = px + (nsBed ? 0 : 1), fz = pz + (nsBed ? 1 : 0);
					if (!w.get(fx, B + 1, fz).isAir() || !inRoom(fx, fz, f.room()))
						continue;
					String facing = nsBed ? "north" : "west";
					w.set(px, B + 1, pz, P("minecraft:brown_bed[part=head,facing=" + facing + "]"), WorldWriter.FLAGS_RAW);
					w.set(fx, B + 1, fz, P("minecraft:brown_bed[part=foot,facing=" + facing + "]"), WorldWriter.FLAGS_RAW);
				} else if (kind < 5) { // a table
					w.set(px, B + 1, pz, P("minecraft:spruce_fence"));
					w.set(px, B + 2, pz, P("minecraft:spruce_pressure_plate"));
				} else
					w.set(px, B + 1, pz, P(stuff[(int) ((h >> 16) % stuff.length)]));
			}
	}

	private boolean inRoom(int x, int z, int room) {
		int tx = p.tileOfBlockX(x - p.X0), ty = p.tileOfBlockZ(z - p.Z0);
		return p.inRegion(tx, ty) && m.roomId(m.idx(tx, ty)) == room;
	}

	/* ---------------------------------------------------------------- pump */

	/**
	 * Water pump, after its sprite: stone floor, a rimmed basin in the middle, the pump housings before and after
	 * it, rows of barrels along both long sides, and an outlet channel at the end.
	 */
	private void pump(WorldWriter w, SyxMap.Furniture f) {
		int x0 = p.blockX(f.x()), z0 = p.blockZ(f.y()), bw = f.w() * s, bh = f.h() * s;
		boolean alongZ = bh >= bw;
		int A = alongZ ? bw : bh, L = alongZ ? bh : bw;
		int b1 = (int) Math.round(L * 0.3), b2 = (int) Math.round(L * 0.62), mid = A / 2;
		for (int b = 0; b < L; b++)
			for (int a = 0; a < A; a++) {
				int x = alongZ ? x0 + a : x0 + b, z = alongZ ? z0 + b : z0 + a;
				w.set(x, B, z, P("minecraft:polished_andesite"));
				boolean side = a == 0 || a == A - 1;
				boolean basin = !side && b >= b1 && b <= b2;
				boolean rim = basin && (a == 1 || a == A - 2 || b == b1 || b == b2);
				boolean outlet = a == mid && b > b2;
				if (side) {
					if (b > 0 && b < L - 1 && (b & 1) == 1)
						w.set(x, B + 1, z, P("minecraft:barrel[facing=up]"));
				} else if (rim) {
					w.set(x, B + 1, z, P("minecraft:smooth_stone_slab[type=bottom]"));
				} else if (basin || outlet) {
					w.set(x, B, z, P("minecraft:water"));
					if (basin) {
						w.set(x, B - 1, z, P("minecraft:water"));
						w.set(x, B - 2, z, P("minecraft:mud"));
					} else
						w.set(x, B - 1, z, P("minecraft:stone_bricks"));
				} else if (a >= 2 && a <= A - 3 && ((b >= 1 && b <= b1 - 2) || (b >= b2 + 2 && b <= L - 3))) {
					// pump housings
					w.set(x, B + 1, z, P("minecraft:polished_granite"));
					boolean centre = a == mid && (b == (1 + b1 - 2) / 2 || b == (b2 + 2 + L - 3) / 2);
					w.set(x, B + 2, z, P(centre ? "minecraft:piston[facing=up]" : "minecraft:granite_slab[type=bottom]"));
				}
			}
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
					int hb = p.heightAt(x0 + dx - p.X0, z0 + dz - p.Z0); // up to this building's ceiling
					for (int y = B + 1; y <= B + hb; y++)
						w.set(x0 + dx, y, z0 + dz, wall);
					taken.add(key(x0 + dx, z0 + dz));
				}
			houseDoor(w, f, item, bp, x0, z0, bw, bh, taken);
			int lx = x0 + bw / 2, lz = z0 + bh / 2, hb = p.heightAt(lx - p.X0, lz - p.Z0);
			for (int y = B + H + 1; y <= B + hb; y++) // a taller house hangs its lantern on a chain
				w.set(lx, y, lz, P("minecraft:iron_chain"));
			w.set(lx, B + H, lz, P("minecraft:lantern[hanging=true]"));
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
					w.set(x, B + 1, z, P("minecraft:polished_andesite"));
					w.set(x, B + 2, z, P("minecraft:chiseled_quartz_block"));
					w.set(x, B + 3, z, P("minecraft:campfire[lit=true]"));
				} else
					w.set(x, B + 1, z, P("minecraft:smooth_quartz_slab[type=bottom]"));
			}
	}

	/**
	 * The humidifier (MONUMENT_BLOB), after its Songs of Syx sprite: a lumpy, porous vessel of mauve clay with a pale
	 * rim round its open top and round holes in its sides, dark red flesh showing inside. Bulbous low down, narrowing to
	 * a neck under the rim, a little taller than it is wide.
	 */
	private void humidifier(WorldWriter w, SyxMap.Furniture f) {
		humidifier(w, p.blockX(f.x()), p.blockZ(f.y()), f.w() * s, f.h() * s, B);
	}

	/** a humidifier filling bw x bh blocks at x0/z0, standing on the floor at B */
	static void humidifier(WorldWriter w, int x0, int z0, int bw, int bh, int B) {
		int n = Math.min(bw, bh);
		long h = CityPlan.hash(x0 * 13 + 5, z0 * 7 + 3);
		BlockState body = P("minecraft:light_gray_terracotta"), pale = P("minecraft:white_terracotta"),
				rim = P("minecraft:smooth_sandstone"), flesh = P("minecraft:nether_wart_block");
		if (n == 1) {
			// one block across: a mauve lump with its rim on top
			for (int dz = 0; dz < bh; dz++)
				for (int dx = 0; dx < bw; dx++) {
					w.set(x0 + dx, B + 1, z0 + dz, (h & 1) == 0 ? body : flesh);
					w.set(x0 + dx, B + 2, z0 + dz, rim);
				}
			return;
		}
		int top = Math.max(3, (int) Math.round(n * 1.3)); // levels
		double cx = bw / 2.0, cz = bh / 2.0;
		// the holes in its sides: a direction round the body and a height each
		int holes = n <= 2 ? 2 : 4;
		double[] ha = new double[holes], hy = new double[holes];
		for (int k = 0; k < holes; k++) {
			ha[k] = 2 * Math.PI * (k + 0.25 * ((h >> (k * 4)) & 3)) / holes;
			hy[k] = k % 2 == 0 ? 0.3 : 0.55;
		}
		for (int k = 0; k < top; k++) {
			double ny = top == 1 ? 0 : k / (double) (top - 1);
			// bulbous low down, a neck under the rim, the rim flaring out a little
			double r = ny < 0.35 ? 0.85 + 0.2 * ny / 0.35 : ny < 0.8 ? 1.05 - 0.25 * (ny - 0.35) / 0.45 : 0.8 + 0.12 * (ny - 0.8) / 0.2;
			for (int dz = 0; dz < bh; dz++)
				for (int dx = 0; dx < bw; dx++) {
					double nx = (dx + 0.5 - cx) / cx, nz = (dz + 0.5 - cz) / cz, a = Math.atan2(nz, nx);
					double lump = 0.07 * Math.sin(3 * a + (h & 7)) + 0.04 * Math.sin(5 * a + k);
					double d = Math.sqrt(nx * nx + nz * nz);
					if (n > 2 && d > r + lump)
						continue;
					int x = x0 + dx, y = B + 1 + k, z = z0 + dz;
					boolean inner = n > 3 && d < r + lump - 2.2 / n; // not on the surface
					BlockState b;
					if (k == top - 1)
						b = inner ? net.minecraft.world.level.block.Blocks.AIR.defaultBlockState() : rim; // the open mouth
					else if (inner)
						b = k == top - 2 ? flesh : body; // the maw seen from above
					else {
						b = ((CityPlan.hash(x, y * 31 + z) & 7) == 0) ? pale : body; // porous flecks
						for (int j = 0; j < holes; j++) {
							double da = Math.abs(Math.atan2(Math.sin(a - ha[j]), Math.cos(a - ha[j])));
							double dy = Math.abs(ny - hy[j]) * top;
							double size = n <= 2 ? 0.9 : 0.35 + 0.6 / n;
							if (da < size && dy < 0.6)
								b = flesh; // the hole
							else if (n > 2 && da < size + 0.45 && dy < 1.4)
								b = pale; // its pale lip
						}
					}
					w.set(x, y, z, b);
				}
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

	/** a bed per tile; with several colours each bed picks one (both halves of a bed share it) */
	private Piece bed(String... colours) {
		return (w, x, y, z, u, v, d, h) -> {
			String colour = colours[(int) (CityPlan.hash(x, z - v) % colours.length)];
			if (s >= 2) {
				if (u != 0)
					return;
				w.set(x, y, z, P("minecraft:" + colour + "_bed[part=" + (v == 0 ? "head" : "foot") + ",facing=north]"), WorldWriter.FLAGS_RAW);
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

	/** a lantern in the first free block above the floor at y0 (the ground, or a quarry's bottom) */
	private void light(WorldWriter w, int x, int z, int y0) {
		for (int y = y0 + 1; y <= y0 + H; y++) {
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
