package dev.sos2mc.syxcraft;

import java.awt.image.BufferedImage;
import java.nio.file.Path;

import javax.imageio.ImageIO;

import dev.sos2mc.syxcraft.place.PlacementJob;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/** Debug/preview output: a top-down map-colour picture of a placed city. */
final class Render {

	private Render() {
	}

	static void topDown(PlacementJob j, Path out) throws Exception {
		ServerLevel level = j.w.level;
		int pad = 20;
		int x0 = j.plan.X0 - pad, z0 = j.plan.Z0 - pad, w = j.plan.bw + 2 * pad, h = j.plan.bh + 2 * pad;
		int[] top = new int[w * h];
		BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int z = 0; z < h; z++)
			for (int x = 0; x < w; x++)
				top[x + z * w] = j.w.anyTop(x0 + x, z0 + z);
		for (int z = 0; z < h; z++)
			for (int x = 0; x < w; x++) {
				int y = top[x + z * w];
				BlockState s = level.getBlockState(pos.set(x0 + x, y, z0 + z));
				int col = s.getMapColor(level, pos).col;
				int north = z > 0 ? top[x + (z - 1) * w] : y;
				double shade = y > north ? 1.15 : y < north ? 0.8 : 1.0;
				int r = clamp((int) (((col >> 16) & 255) * shade)), g = clamp((int) (((col >> 8) & 255) * shade)),
						b = clamp((int) ((col & 255) * shade));
				boolean border = x == pad - 1 || z == pad - 1 || x == w - pad || z == h - pad;
				img.setRGB(x, z, border ? 0xFF00FF : (r << 16) | (g << 8) | b);
			}
		ImageIO.write(img, "png", out.toFile());
	}

	/** floor plan: what's at y = ground + dy over the whole placement, 3 px per block, air shows the floor dimmed */
	static void plan(PlacementJob j, int dy, Path out) throws Exception {
		ServerLevel level = j.w.level;
		int k = 3, y = j.plan.B + dy;
		int w = j.plan.bw, h = j.plan.bh;
		BufferedImage img = new BufferedImage(w * k, h * k, BufferedImage.TYPE_INT_RGB);
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int z = 0; z < h; z++)
			for (int x = 0; x < w; x++) {
				int X = j.plan.X0 + x, Z = j.plan.Z0 + z;
				BlockState s = level.getBlockState(pos.set(X, y, Z));
				int col;
				if (s.isAir()) {
					BlockState below = level.getBlockState(pos.set(X, j.plan.B, Z));
					col = mix(mix(below.getMapColor(level, pos).col, 0xFFFFFF), 0xFFFFFF);
				} else {
					col = s.getMapColor(level, pos).col;
					if (!s.canOcclude())
						col = mix(col, 0xFF0000); // non-full blocks (doors, panes, furniture) tinted red
				}
				for (int a = 0; a < k; a++)
					for (int b = 0; b < k; b++)
						img.setRGB(x * k + a, z * k + b, col);
			}
		ImageIO.write(img, "png", out.toFile());
	}

	/** side view: an x/y slice at world z, from 16 below to 24 above the ground, 4 px per block */
	static void slice(PlacementJob j, int z, int xFrom, int xTo, Path out) throws Exception {
		ServerLevel level = j.w.level;
		int y0 = j.plan.B - 16, y1 = j.plan.B + 24, k = 4;
		int w = (xTo - xFrom + 1), h = (y1 - y0 + 1);
		BufferedImage img = new BufferedImage(w * k, h * k, BufferedImage.TYPE_INT_RGB);
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int x = 0; x < w; x++)
			for (int y = 0; y < h; y++) {
				BlockState s = level.getBlockState(pos.set(xFrom + x, y1 - y, z));
				int col = s.isAir() ? 0xDDEEFF : s.getMapColor(level, pos).col;
				if (!s.isAir() && !s.canOcclude())
					col = mix(col, 0xFFFFFF);
				for (int dy = 0; dy < k; dy++)
					for (int dx = 0; dx < k; dx++)
						img.setRGB(x * k + dx, y * k + dy, (dx == 0 || dy == 0) && !s.isAir() ? mix(col, 0) : col);
			}
		ImageIO.write(img, "png", out.toFile());
	}

	private static int mix(int a, int b) {
		return ((((a >> 16) & 255) + ((b >> 16) & 255)) / 2 << 16) | ((((a >> 8) & 255) + ((b >> 8) & 255)) / 2 << 8)
				| (((a & 255) + (b & 255)) / 2);
	}

	/** checks every placed door: double-door hinges as vanilla pairs them, and no runs of three or more */
	static String checkDoors(PlacementJob j) {
		ServerLevel level = j.w.level;
		int y = j.plan.B + 1, doors = 0, pairs = 0, badPairs = 0, triples = 0;
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int z = j.plan.Z0; z < j.plan.Z0 + j.plan.bh; z++)
			for (int x = j.plan.X0; x < j.plan.X0 + j.plan.bw; x++) {
				BlockState s = level.getBlockState(pos.set(x, y, z));
				if (!(s.getBlock() instanceof net.minecraft.world.level.block.DoorBlock)
						|| s.getValue(net.minecraft.world.level.block.DoorBlock.HALF) != net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER)
					continue;
				doors++;
				net.minecraft.core.Direction f = s.getValue(net.minecraft.world.level.block.DoorBlock.FACING);
				net.minecraft.core.Direction cw = f.getClockWise();
				BlockState n = level.getBlockState(pos.set(x + cw.getStepX(), y, z + cw.getStepZ()));
				if (n.getBlock() instanceof net.minecraft.world.level.block.DoorBlock && n.getValue(net.minecraft.world.level.block.DoorBlock.FACING) == f) {
					pairs++;
					if (s.getValue(net.minecraft.world.level.block.DoorBlock.HINGE) != net.minecraft.world.level.block.state.properties.DoorHingeSide.LEFT
							|| n.getValue(net.minecraft.world.level.block.DoorBlock.HINGE) != net.minecraft.world.level.block.state.properties.DoorHingeSide.RIGHT)
						badPairs++;
					BlockState n2 = level.getBlockState(pos.set(x + 2 * cw.getStepX(), y, z + 2 * cw.getStepZ()));
					if (n2.getBlock() instanceof net.minecraft.world.level.block.DoorBlock && n2.getValue(net.minecraft.world.level.block.DoorBlock.FACING) == f)
						triples++;
				}
			}
		// glass panes: how many have no connection at all (they render as loose posts)
		int panes = 0, loose = 0, houseWalls = 0;
		for (int yy = j.plan.B + 1; yy <= j.plan.B + j.plan.maxHeight(); yy++)
			for (int z = j.plan.Z0; z < j.plan.Z0 + j.plan.bh; z++)
				for (int x = j.plan.X0; x < j.plan.X0 + j.plan.bw; x++) {
					BlockState s = level.getBlockState(pos.set(x, yy, z));
					if (s.is(net.minecraft.world.level.block.Blocks.GLASS_PANE)) {
						panes++;
						if (!s.getValue(net.minecraft.world.level.block.CrossCollisionBlock.NORTH) && !s.getValue(net.minecraft.world.level.block.CrossCollisionBlock.SOUTH)
								&& !s.getValue(net.minecraft.world.level.block.CrossCollisionBlock.EAST) && !s.getValue(net.minecraft.world.level.block.CrossCollisionBlock.WEST))
							loose++;
					} else if (yy == j.plan.B + 1 && s.is(net.minecraft.world.level.block.Blocks.SPRUCE_PLANKS))
						houseWalls++;
				}
		// attics: air between the ceiling and the roof over buildings
		int attic = 0;
		for (int z = j.plan.Z0; z < j.plan.Z0 + j.plan.bh; z++)
			for (int x = j.plan.X0; x < j.plan.X0 + j.plan.bw; x++) {
				int R = j.plan.B + j.plan.heightAt(x - j.plan.X0, z - j.plan.Z0) + 1;
				if (j.plan.roofDistAt(x - j.plan.X0, z - j.plan.Z0) < 0 || !level.getBlockState(pos.set(x, R, z)).isSolidRender())
					continue; // only under building roofs (hills with trees on them aren't attics)
				int top = j.w.anyTop(x, z);
				for (int yy = R + 1; yy < top; yy++)
					if (level.getBlockState(pos.set(x, yy, z)).isAir())
						attic++;
			}
		// water above city level never belongs there (it shows as standing water pillars)
		int highWater = 0;
		for (int z = j.plan.Z0; z < j.plan.Z0 + j.plan.bh; z++)
			for (int x = j.plan.X0; x < j.plan.X0 + j.plan.bw; x++) {
				int top = j.w.anyTop(x, z);
				for (int yy = j.plan.B + 1; yy <= top; yy++)
					if (!level.getBlockState(pos.set(x, yy, z)).getFluidState().isEmpty()) {
						if (highWater++ < 5)
							dev.sos2mc.syxcraft.Syxcraft.LOG.info("high water at {} {} {}", x, yy, z);
						break;
					}
			}
		// leftover natural terrain high over the city (floating mountain tips): nothing built reaches B + 96
		int floating = 0;
		for (int z = j.plan.Z0; z < j.plan.Z0 + j.plan.bh; z++)
			for (int x = j.plan.X0; x < j.plan.X0 + j.plan.bw; x++)
				if (j.w.anyTop(x, z) > j.plan.B + 96)
					floating++;
		return String.format("doors=%d doublePairs=%d singles=%d wrongHinges=%d runsOf3=%d panes=%d loosePanes=%d houseWallBlocks=%d atticAir=%d floatingColumns=%d highWaterColumns=%d",
				doors, pairs, doors - 2 * pairs, badPairs, triples, panes, loose, houseWalls, attic, floating, highWater);
	}

	private static int clamp(int v) {
		return Math.max(0, Math.min(255, v));
	}
}
