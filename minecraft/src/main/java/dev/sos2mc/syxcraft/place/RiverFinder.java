package dev.sos2mc.syxcraft.place;

import java.util.BitSet;
import java.util.List;
import java.util.stream.IntStream;

import net.minecraft.core.QuartPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.biome.BiomeResolver;

/**
 * Looks for the spot near a centre where a scale-1 city's river lines up best with a Minecraft river. Reads the
 * river biome from the world generator's noise (no chunks are generated, so it can search thousands of blocks around
 * and runs off the server thread), then slides the city's outline over it: Minecraft river where the Songs of Syx
 * river leaves the area scores, Minecraft river running into the city's dry edge costs.
 */
public final class RiverFinder {

	/** how far the centre may move, in blocks */
	public static final int RADIUS = 2048;
	/** cell size of the search, in blocks */
	static final int C = 8;

	/** the city's new centre and how much of its river's exits meet a Minecraft river (0..1) */
	public record Result(int x, int z, double exitsMet) {
	}

	private RiverFinder() {
	}

	/**
	 * region: the placed area in tiles (scale 1, so tiles are blocks); exits: from Rivers.find. Blocks; call off the
	 * server thread.
	 */
	public static Result find(ServerLevel level, BitSet water, int mapWidth, int[] region, List<Rivers.Exit> exits, int cx, int cz) {
		int rw = region[2] - region[0] + 1, rh = region[3] - region[1] + 1;
		int pad = 64;
		// Minecraft river cells over everything the outline can cover
		int gx0 = cx - rw / 2 - RADIUS - pad, gz0 = cz - rh / 2 - RADIUS - pad;
		int nx = (rw + 2 * RADIUS + 2 * pad) / C + 1, nz = (rh + 2 * RADIUS + 2 * pad) / C + 1;
		boolean[] mc = new boolean[nx * nz];
		var source = level.getChunkSource().getGenerator().getBiomeSource();
		var random = level.getChunkSource().randomState();
		int qy = QuartPos.fromBlock(level.getSeaLevel() - 1);
		IntStream.range(0, nz).parallel().forEach(j -> {
			BiomeResolver res = source.createUncachedResolver(random);
			for (int i = 0; i < nx; i++) {
				int x = gx0 + i * C + C / 2, z = gz0 + j * C + C / 2;
				mc[i + j * nx] = res.getNoiseBiome(QuartPos.fromBlock(x), qy, QuartPos.fromBlock(z)).is(BiomeTags.IS_RIVER);
			}
		});

		// what the outline wants under it, cell by cell (relative to the region's NW corner): weight = gain where a
		// Minecraft river is minus gain where there isn't
		int cw = (rw + C - 1) / C, ch = (rh + C - 1) / C;
		IntListBuilder cells = new IntListBuilder();
		int exitCells = 0;
		// two rings just outside the edge (the blend zone): river exits want river, the rest of the edge wants land
		for (int ring : new int[] { 1, 4 })
			for (int side = 0; side < 4; side++) {
				int len = side == Rivers.N || side == Rivers.S ? cw : ch;
				for (int a = 0; a < len; a++) {
					int along = a * C + C / 2; // tile offset along the side
					boolean exit = false;
					for (Rivers.Exit e : exits)
						if (e.side() == side) {
							int off = side == Rivers.N || side == Rivers.S ? region[0] : region[1];
							if (along + off >= e.from() - 2 && along + off <= e.to() + 2)
								exit = true;
						}
					int x = switch (side) {
					case Rivers.N, Rivers.S -> a;
					case Rivers.E -> cw - 1 + ring;
					default -> -ring;
					};
					int z = switch (side) {
					case Rivers.E, Rivers.W -> a;
					case Rivers.S -> ch - 1 + ring;
					default -> -ring;
					};
					cells.add(x, z, exit ? 30 : -15);
					if (exit)
						exitCells++;
				}
			}
		// inside: the city's water over Minecraft river keeps the two running the same way
		for (int z = 0; z < ch; z++)
			for (int x = 0; x < cw; x++) {
				int n = 0;
				for (int dz = 0; dz < C; dz++)
					for (int dx = 0; dx < C; dx++) {
						int tx = region[0] + x * C + dx, ty = region[1] + z * C + dz;
						if (tx <= region[2] && ty <= region[3] && water.get(tx + ty * mapWidth))
							n++;
					}
				if (n * 2 >= C * C)
					cells.add(x, z, 2);
			}
		int[] cx_ = cells.xs(), cz_ = cells.zs(), cwt = cells.ws();

		// slide: offsets in cells from the starting spot, within the radius
		int R = RADIUS / C, baseX = (rw / 2 + RADIUS + pad - rw / 2) / C, baseZ = (rh / 2 + RADIUS + pad - rh / 2) / C;
		long[] best = { Long.MIN_VALUE, 0, 0 };
		int[][] rows = new int[2 * R + 1][];
		IntStream.rangeClosed(-R, R).parallel().forEach(oz -> {
			int bestScore = Integer.MIN_VALUE, bestOx = 0;
			for (int ox = -R; ox <= R; ox++) {
				if (ox * ox + oz * oz > R * R)
					continue;
				int sc = 0, gx = baseX + ox, gz = baseZ + oz;
				for (int k = 0; k < cx_.length; k++) {
					int x = gx + cx_[k], z = gz + cz_[k];
					if (x >= 0 && z >= 0 && x < nx && z < nz && mc[x + z * nx])
						sc += cwt[k];
				}
				// a little pull towards the start, so equal spots go to the nearest
				sc -= (int) Math.sqrt(ox * ox + oz * oz) / 8;
				if (sc > bestScore) {
					bestScore = sc;
					bestOx = ox;
				}
			}
			rows[oz + R] = new int[] { bestScore, bestOx };
		});
		for (int oz = -R; oz <= R; oz++) {
			int[] r = rows[oz + R];
			if (r[0] > best[0]) {
				best[0] = r[0];
				best[1] = r[1];
				best[2] = oz;
			}
		}
		int ox = (int) best[1], oz = (int) best[2];
		int met = 0;
		for (int k = 0; k < cx_.length; k++) {
			int x = baseX + ox + cx_[k], z = baseZ + oz + cz_[k];
			if (cwt[k] == 30 && x >= 0 && z >= 0 && x < nx && z < nz && mc[x + z * nx])
				met++;
		}
		return new Result(cx + ox * C, cz + oz * C, exitCells == 0 ? 0 : met / (double) exitCells);
	}

	/** three parallel int lists */
	private static final class IntListBuilder {
		private int[] x = new int[256], z = new int[256], w = new int[256];
		private int n;

		void add(int xv, int zv, int wv) {
			if (n == x.length) {
				x = java.util.Arrays.copyOf(x, n * 2);
				z = java.util.Arrays.copyOf(z, n * 2);
				w = java.util.Arrays.copyOf(w, n * 2);
			}
			x[n] = xv;
			z[n] = zv;
			w[n++] = wv;
		}

		int[] xs() {
			return java.util.Arrays.copyOf(x, n);
		}

		int[] zs() {
			return java.util.Arrays.copyOf(z, n);
		}

		int[] ws() {
			return java.util.Arrays.copyOf(w, n);
		}
	}
}
