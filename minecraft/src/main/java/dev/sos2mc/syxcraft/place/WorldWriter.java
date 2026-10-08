package dev.sos2mc.syxcraft.place;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/** Thin block access over a ServerLevel that skips writes which wouldn't change anything. */
public final class WorldWriter {

	public static final int FLAGS = Block.UPDATE_CLIENTS;
	/** for double blocks (doors, beds) whose halves must not be validated against each other mid-placement */
	/** true while a placement step runs on the server thread: item drops are suppressed then (NoDropsWhilePlacingMixin) */
	public static volatile boolean placing;
	public static final int FLAGS_RAW = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

	public final ServerLevel level;
	private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
	public long writes;

	public WorldWriter(ServerLevel level) {
		this.level = level;
	}

	public int minY() {
		return level.getMinY();
	}

	public int maxY() {
		return level.getMaxY();
	}

	public BlockState get(int x, int y, int z) {
		return level.getBlockState(pos.set(x, y, z));
	}

	public void set(int x, int y, int z, BlockState s) {
		set(x, y, z, s, FLAGS);
	}

	public void set(int x, int y, int z, BlockState s, int flags) {
		if (y < minY() || y > maxY())
			return;
		pos.set(x, y, z);
		// connecting blocks (panes, fences, walls, stairs) only get their connections from neighbours placed
		// after them; work out the connections to what's already there too
		if ((flags & Block.UPDATE_KNOWN_SHAPE) == 0 && connects(s))
			s = Block.updateFromNeighbourShapes(s, level, pos);
		if (level.getBlockState(pos) == s)
			return;
		level.setBlock(pos, s, flags);
		writes++;
	}

	public void setIfAir(int x, int y, int z, BlockState s) {
		if (get(x, y, z).isAir())
			set(x, y, z, s);
	}

	/**
	 * Quietly take the plants off a column's natural ground (leaf litter, grass, flowers, snow) before it's rebuilt.
	 * Writing the ground under them makes Minecraft ask them whether they can still stand there, and they'd break and
	 * drop as items: leaf litter by the ten thousand in a forest, in chunks nothing cleans up later.
	 */
	public void clearFragile(int x, int z) {
		int g = groundTop(x, z);
		for (int y = g + 2; y > g; y--) {
			BlockState s = get(x, y, z);
			if (!s.isAir() && s.getFluidState().isEmpty() && (s.canBeReplaced() || s.is(Blocks.LEAF_LITTER)))
				set(x, y, z, Blocks.AIR.defaultBlockState(), FLAGS_RAW);
		}
	}

	/**
	 * Remove what can't stay where it is at the top of a column (leaf litter, plants, snow left on ground that was
	 * rebuilt under them). Blocks are written without telling their neighbours, so these would otherwise hang on until
	 * something nudges them, then pop off as item stacks, piles of them in a forest.
	 */
	public void clearUnsupported(int x, int z) {
		int top = anyTop(x, z);
		for (int y = top; y > top - 3 && y > minY(); y--) {
			BlockState s = get(x, y, z);
			if (!s.isAir() && s.getFluidState().isEmpty() && !s.canSurvive(level, pos.set(x, y, z)))
				set(x, y, z, Blocks.AIR.defaultBlockState());
		}
	}

	public void air(int x, int y, int z) {
		if (!get(x, y, z).isAir())
			set(x, y, z, Blocks.AIR.defaultBlockState());
	}

	/** y of the topmost solid-or-fluid block ignoring leaves (natural ground), loads/generates the chunk. */
	public int groundTop(int x, int z) {
		return height(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
	}

	/** y of the natural ground surface: like groundTop, but looking through trees, tall plants, snow and falls */
	public int naturalGround(int x, int z) {
		int y = groundTop(x, z);
		while (y > minY()) {
			BlockState s = get(x, y, z);
			// a source block is a lake or sea surface; flowing water/lava (falls, spills) is looked through
			if (s.getFluidState().isSource() || !(s.isAir() || !s.getFluidState().isEmpty() || s.canBeReplaced() || growth(s)))
				break;
			y--;
		}
		return y;
	}

	/** y of the topmost solid block: looks through all water and lava, trees, plants and snow */
	public int solidGround(int x, int z) {
		int y = groundTop(x, z);
		while (y > minY()) {
			BlockState s = get(x, y, z);
			if (!(s.isAir() || !s.getFluidState().isEmpty() || s.canBeReplaced() || growth(s)))
				break;
			y--;
		}
		return y;
	}

	private static final java.util.Set<Block> GROWTH = java.util.Set.of(Blocks.BAMBOO, Blocks.BAMBOO_SAPLING, Blocks.SUGAR_CANE,
			Blocks.CACTUS, Blocks.CACTUS_FLOWER, Blocks.BROWN_MUSHROOM_BLOCK, Blocks.RED_MUSHROOM_BLOCK, Blocks.MUSHROOM_STEM,
			Blocks.BEE_NEST, Blocks.COCOA, Blocks.BIG_DRIPLEAF, Blocks.BIG_DRIPLEAF_STEM, Blocks.POINTED_DRIPSTONE,
			Blocks.MANGROVE_ROOTS, Blocks.PUMPKIN, Blocks.MELON);

	/** things growing on the ground rather than ground: trees, bamboo (up to 16 tall), cane, cactus, flowers... */
	static boolean growth(BlockState s) {
		return s.is(net.minecraft.tags.BlockTags.LOGS) || s.is(net.minecraft.tags.BlockTags.LEAVES)
				|| s.is(net.minecraft.tags.BlockTags.FLOWERS) || s.is(net.minecraft.tags.BlockTags.SAPLINGS) || GROWTH.contains(s.getBlock());
	}

	/**
	 * Stop natural water and lava around a chunk from flowing while it's rebuilt: load its neighbours (so their
	 * fluids' pending ticks exist now) and drop the pending fluid ticks of all nine chunks. Otherwise water and lava
	 * from terrain that isn't cut yet pours into columns that are, and once its source is cut away the fall is left
	 * standing in the air (our writes don't send neighbour updates).
	 */
	public void freezeFluids(int cx, int cz) {
		for (int dz = -1; dz <= 1; dz++)
			for (int dx = -1; dx <= 1; dx++)
				level.getChunk(cx + dx, cz + dz);
		level.getFluidTicks().clearArea(new net.minecraft.world.level.levelgen.structure.BoundingBox(cx * 16 - 16, minY(),
				cz * 16 - 16, cx * 16 + 31, maxY(), cz * 16 + 31));
	}

	/** y of the topmost non-air block of any kind (trees included). */
	public int anyTop(int x, int z) {
		return height(Heightmap.Types.WORLD_SURFACE, x, z);
	}

	/** Level.getHeight answers minY for chunks that aren't loaded, so load (or generate) the chunk first */
	public int height(Heightmap.Types type, int x, int z) {
		return level.getChunk(x >> 4, z >> 4).getHeight(type, x & 15, z & 15);
	}

	static boolean connects(BlockState s) {
		Block b = s.getBlock();
		return b instanceof net.minecraft.world.level.block.CrossCollisionBlock || b instanceof net.minecraft.world.level.block.WallBlock
				|| b instanceof net.minecraft.world.level.block.StairBlock || b instanceof net.minecraft.world.level.block.FenceGateBlock;
	}

	/** true for blocks a filled-in ground column may replace: air, fluids, plants, leaves, snow. */
	public static boolean soft(BlockState s) {
		return s.isAir() || !s.getFluidState().isEmpty() || s.canBeReplaced() || s.is(net.minecraft.tags.BlockTags.LEAVES)
				|| s.is(net.minecraft.tags.BlockTags.LOGS);
	}
}
