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

	public void air(int x, int y, int z) {
		if (!get(x, y, z).isAir())
			set(x, y, z, Blocks.AIR.defaultBlockState());
	}

	/** y of the topmost solid-or-fluid block ignoring leaves (natural ground), loads/generates the chunk. */
	public int groundTop(int x, int z) {
		return height(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
	}

	/** y of the natural ground surface: like groundTop, but looking through tree trunks, plants and snow */
	public int naturalGround(int x, int z) {
		int y = groundTop(x, z);
		while (y > minY()) {
			BlockState s = get(x, y, z);
			if (!s.getFluidState().isEmpty() || !(s.isAir() || s.canBeReplaced() || s.is(net.minecraft.tags.BlockTags.LOGS)
					|| s.is(net.minecraft.tags.BlockTags.LEAVES)))
				break;
			y--;
		}
		return y;
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
