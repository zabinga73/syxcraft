package dev.sos2mc.syxcraft.place;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

import dev.sos2mc.syxcraft.Syxcraft;
import dev.sos2mc.syxcraft.map.SyxMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;

/**
 * Builds a city over many server ticks within a time budget, so the game keeps running. Stages: pick the ground
 * height, build every block column chunk by chunk, blend the edges into the terrain, sweep away stray water and
 * lava, then trees, furniture and citizens.
 */
public final class PlacementJob {

	public enum Stage {
		HEIGHT, COLUMNS, BLEND, BED, SWEEP, DETAILS, DONE, CANCELLED, FAILED
	}

	public static final int BLEND_WIDTH = 16;

	public final CityPlan plan;
	/** River lineup: where the city's river leaves the area (map tiles), carried out through the blend ring */
	public List<Rivers.Exit> rivers;
	public final WorldWriter w;
	private final Consumer<String> chat;

	public Stage stage = Stage.HEIGHT;
	private final long started = System.currentTimeMillis();
	private long startTick = Long.MIN_VALUE;

	// HEIGHT
	private final List<int[]> samplePoints = new ArrayList<>();
	private final List<Integer> samples = new ArrayList<>();
	private int sampleIdx;

	// COLUMNS / BLEND: chunk-ordered lists of columns
	private final int[] chunkOrder; // packed chunk x/z offsets relative to region
	private int chunkIdx;
	private final int chunksX, chunksZ, cx0, cz0;
	/** per footprint block outside the region: the ground y blending left (MIN_VALUE: not blended) */
	private final int[] blendTop;

	// DETAILS
	private List<Runnable> details;
	private int detailIdx;

	public PlacementJob(ServerLevel level, SyxMap map, PlaceSettings st, Palette pal, int centreX, int centreZ,
			Consumer<String> chat) {
		this.plan = new CityPlan(map, st, pal, centreX, centreZ);
		this.w = new WorldWriter(level);
		this.chat = chat;
		int margin = st.has(PlaceSettings.BLEND_EDGES) ? plan.blendWidth() : 0;
		cx0 = Math.floorDiv(plan.X0 - margin, 16);
		cz0 = Math.floorDiv(plan.Z0 - margin, 16);
		int cx1 = Math.floorDiv(plan.X0 + plan.bw - 1 + margin, 16), cz1 = Math.floorDiv(plan.Z0 + plan.bh - 1 + margin, 16);
		chunksX = cx1 - cx0 + 1;
		chunksZ = cz1 - cz0 + 1;
		chunkOrder = new int[chunksX * chunksZ];
		for (int i = 0; i < chunkOrder.length; i++)
			chunkOrder[i] = i;
		blendTop = new int[chunksX * 16 * chunksZ * 16];
		Arrays.fill(blendTop, Integer.MIN_VALUE);

		switch (st.height) {
		case SEA_LEVEL -> {
			plan.B = level.getSeaLevel();
			stage = Stage.COLUMNS;
		}
		case CUSTOM -> {
			plan.B = st.y;
			stage = Stage.COLUMNS;
		}
		default -> {
			for (int z = 8; z < plan.bh; z += 24)
				for (int x = 8; x < plan.bw; x += 24)
					samplePoints.add(new int[] { plan.X0 + x, plan.Z0 + z });
			if (samplePoints.isEmpty())
				samplePoints.add(new int[] { plan.X0, plan.Z0 });
		}
		}
		if (stage == Stage.COLUMNS)
			startColumns();
	}

	private void startColumns() {
		plan.computeRoofDistances();
		chat.accept(String.format("Syx: building %s (%dx%d tiles = %dx%d blocks) at x=%d..%d z=%d..%d, ground y=%d",
				plan.map.save != null ? plan.map.save : plan.map.city, plan.tx1 - plan.tx0 + 1, plan.ty1 - plan.ty0 + 1,
				plan.bw, plan.bh, plan.X0, plan.X0 + plan.bw - 1, plan.Z0, plan.Z0 + plan.bh - 1, plan.B));
	}

	/** run for up to budgetMs; returns false once finished */
	public boolean tick(long budgetMs) {
		long end = System.nanoTime() + budgetMs * 1_000_000L;
		if (startTick == Long.MIN_VALUE)
			startTick = w.level.getGameTime();
		try {
			while (System.nanoTime() < end) {
				switch (stage) {
				case HEIGHT -> {
					if (sampleIdx < samplePoints.size()) {
						int[] p = samplePoints.get(sampleIdx++);
						samples.add(w.groundTop(p[0], p[1]));
					} else {
						int[] a = samples.stream().mapToInt(Integer::intValue).toArray();
						Arrays.sort(a);
						plan.B = Math.max(w.level.getSeaLevel(), a[a.length / 2]);
						stage = Stage.COLUMNS;
						startColumns();
					}
				}
				case COLUMNS -> {
					if (chunkIdx >= chunkOrder.length) {
						stage = plan.st.has(PlaceSettings.BLEND_EDGES) ? Stage.BLEND : Stage.SWEEP;
						chunkIdx = 0;
						if (stage == Stage.BLEND && plan.st.river && rivers != null)
							plan.prepareFunnels(w, rivers);
						continue;
					}
					columnsOfChunk(chunkOrder[chunkIdx++], false);
				}
				case BLEND -> {
					if (chunkIdx >= chunkOrder.length) {
						stage = plan.st.river ? Stage.BED : Stage.SWEEP;
						chunkIdx = 0;
						continue;
					}
					columnsOfChunk(chunkOrder[chunkIdx++], true);
				}
				case BED -> {
					// River lineup: read every water column's depth, smooth the riverbed, then write it back
					if (chunkIdx >= chunkOrder.length) {
						if (!bedSmoothed) {
							smoothBed();
							bedSmoothed = true;
							chunkIdx = 0;
						} else {
							stage = Stage.SWEEP;
							chunkIdx = 0;
						}
						continue;
					}
					bedChunk(chunkOrder[chunkIdx++], bedSmoothed);
				}
				case SWEEP -> {
					if (chunkIdx >= chunkOrder.length) {
						stage = Stage.DETAILS;
						details = detailJobs();
						continue;
					}
					sweepChunk(chunkOrder[chunkIdx++]);
				}
				case DETAILS -> {
					if (detailIdx >= details.size()) {
						stage = Stage.DONE;
						chat.accept(String.format("Syx: done in %ds, %,d blocks changed.",
								(System.currentTimeMillis() - started) / 1000, w.writes));
						if (plan.st.topography != PlaceSettings.Topography.FLAT) {
							int lo = 0, hi = 0;
							for (int d : plan.lift) {
								lo = Math.min(lo, d);
								hi = Math.max(hi, d);
							}
							chat.accept(String.format("Syx: topography moved open land from %d to +%d blocks around the city level.", lo, hi));
						}
						return false;
					}
					details.get(detailIdx++).run();
				}
				default -> {
					return false;
				}
				}
			}
		} catch (Throwable e) {
			stage = Stage.FAILED;
			Syxcraft.LOG.error("placement failed", e);
			chat.accept("Syx: placement failed: " + e);
			return false;
		}
		return true;
	}

	private List<Runnable> detailJobs() {
		List<Runnable> jobs = new Details(plan).jobs(w);
		jobs.addAll(plan.plantCrops(w));
		// grass, flowers and mushrooms whose ground was rebuilt pop off as items: clear away what dropped during this
		// placement (an item's age is saved, so older items lying here stay)
		jobs.add(() -> {
			long ticks = w.level.getGameTime() - startTick;
			int bl = plan.blendWidth() + 2;
			var box = new net.minecraft.world.phys.AABB(plan.X0 - bl, w.minY(), plan.Z0 - bl, plan.X0 + plan.bw + bl, w.maxY(),
					plan.Z0 + plan.bh + bl);
			for (var item : w.level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, box, e -> e.getAge() <= ticks))
				item.discard();
		});
		if (plan.st.has(PlaceSettings.CITIZENS))
			jobs.addAll(new Citizens(plan, chat).jobs(w));
		return jobs;
	}

	private void columnsOfChunk(int c, boolean blend) {
		int cx = cx0 + c % chunksX, cz = cz0 + c / chunksX;
		w.freezeFluids(cx, cz);
		for (int z = cz * 16; z < cz * 16 + 16; z++)
			for (int x = cx * 16; x < cx * 16 + 16; x++) {
				int bx = x - plan.X0, bz = z - plan.Z0;
				boolean inside = bx >= 0 && bz >= 0 && bx < plan.bw && bz < plan.bh;
				if (!blend) {
					if (inside)
						plan.column(w, bx, bz);
				} else if (!inside) {
					int ring = Math.max(Math.max(-bx, bx - plan.bw + 1), Math.max(-bz, bz - plan.bh + 1));
					if (ring >= 1 && ring <= plan.blendWidth())
						blendTop[(x - cx0 * 16) + (z - cz0 * 16) * chunksX * 16] = plan.blendColumn(w, x, z, ring, plan.blendWidth());
				}
			}
	}

	/* River lineup: riverbed smoothing over the footprint (region and blend ring) */

	/** per footprint column: 0 land, 1 water whose bed may move, 2 water that stays (bridges); and its depth */
	private byte[] bedKind;
	private byte[] bedDepth, bedNew;
	private boolean bedSmoothed;
	/** how far the riverbed is averaged, in blocks: the wider, the gentler */
	static final int BED_RADIUS = 6;

	private int footIdx(int x, int z) {
		return (x - cx0 * 16) + (z - cz0 * 16) * chunksX * 16;
	}

	/** read (write = false) or rewrite one chunk's water columns */
	private void bedChunk(int c, boolean write) {
		int cx = cx0 + c % chunksX, cz = cz0 + c / chunksX, top = plan.B - 1;
		if (bedKind == null) {
			bedKind = new byte[chunksX * 16 * chunksZ * 16];
			bedDepth = new byte[bedKind.length];
		}
		w.freezeFluids(cx, cz);
		for (int z = cz * 16; z < cz * 16 + 16; z++)
			for (int x = cx * 16; x < cx * 16 + 16; x++) {
				int i = footIdx(x, z);
				if (!write) {
					int k = plan.waterKindAt(w, x - plan.X0, z - plan.Z0);
					bedKind[i] = (byte) k;
					if (k != 0) {
						int d = 0;
						while (d < 24 && w.get(x, top - d - 1, z).getFluidState().is(net.minecraft.tags.FluidTags.WATER))
							d++;
						bedDepth[i] = (byte) (d + 1); // the surface block counts
					}
					continue;
				}
				if (bedKind[i] != 1 || bedNew[i] == bedDepth[i])
					continue;
				int od = bedDepth[i], nd = bedNew[i];
				if (nd > od) {
					// deeper: the old bed becomes water, on a bed of sand where there's nothing solid
					for (int y = top - nd + 1; y <= top - od; y++)
						w.set(x, y, z, plan.pal.get("water"));
					if (WorldWriter.soft(w.get(x, top - nd, z)))
						w.set(x, top - nd, z, Blocks.SAND.defaultBlockState());
				} else {
					// shallower: fill up with sand
					for (int y = top - od + 1; y <= top - nd; y++)
						w.set(x, y, z, Blocks.SAND.defaultBlockState());
				}
			}
	}

	/**
	 * The new riverbed: each water column's depth averaged over its surroundings (land counts as depth 0, so the bed
	 * shelves up to the banks). Two box blurs, so the city's deep channel, the funnels and the Minecraft river's own bed
	 * run into each other without steps or walls.
	 */
	private void smoothBed() {
		int W = chunksX * 16, H = chunksZ * 16;
		float[] d = new float[W * H], t = new float[W * H];
		for (int i = 0; i < d.length; i++)
			d[i] = bedKind[i] == 0 ? 0 : bedDepth[i];
		for (int pass = 0; pass < 2; pass++) {
			blur(d, t, W, H, BED_RADIUS, true);
			blur(t, d, W, H, BED_RADIUS, false);
		}
		bedNew = new byte[d.length];
		int changed = 0;
		for (int i = 0; i < d.length; i++) {
			if (bedKind[i] != 1)
				continue;
			// out in the ring, fade back to the bed that was there, so it meets the untouched river beyond
			int x = cx0 * 16 + i % W - plan.X0, z = cz0 * 16 + i / W - plan.Z0;
			int ring = Math.max(Math.max(-x, x - plan.bw + 1), Math.max(-z, z - plan.bh + 1)), half = plan.blendWidth() / 2;
			double f = ring <= half ? 0 : Math.min(1, (ring - half) / (double) half);
			f = f * f * (3 - 2 * f);
			bedNew[i] = (byte) Math.max(1, Math.min(14, Math.round(d[i] * (1 - f) + bedDepth[i] * f)));
			if (bedNew[i] != bedDepth[i])
				changed++;
		}
		Syxcraft.LOG.info("riverbed smoothing: {} water columns changed", changed);
	}

	/** box blur along x (or z), clamping at the edges */
	private static void blur(float[] in, float[] out, int W, int H, int r, boolean alongX) {
		int n = alongX ? W : H, m = alongX ? H : W;
		for (int j = 0; j < m; j++) {
			double sum = 0;
			for (int k = -r; k <= r; k++)
				sum += in[at(Math.max(0, Math.min(n - 1, k)), j, W, alongX)];
			for (int i = 0; i < n; i++) {
				out[at(i, j, W, alongX)] = (float) (sum / (2 * r + 1));
				sum += in[at(Math.min(n - 1, i + r + 1), j, W, alongX)] - in[at(Math.max(0, i - r), j, W, alongX)];
			}
		}
	}

	private static int at(int i, int j, int W, boolean alongX) {
		return alongX ? i + j * W : j + i * W;
	}

	private void sweepChunk(int c) {
		int cx = cx0 + c % chunksX, cz = cz0 + c / chunksX;
		w.freezeFluids(cx, cz);
		for (int z = cz * 16; z < cz * 16 + 16; z++)
			for (int x = cx * 16; x < cx * 16 + 16; x++) {
				int bx = x - plan.X0, bz = z - plan.Z0;
				boolean inside = bx >= 0 && bz >= 0 && bx < plan.bw && bz < plan.bh;
				int expect = inside ? plan.builtTop[bx + bz * plan.bw] : blendTop[(x - cx0 * 16) + (z - cz0 * 16) * chunksX * 16];
				if (expect != Integer.MIN_VALUE && expect != Integer.MAX_VALUE)
					CityPlan.sweepFluids(w, x, z, expect);
			}
	}

	public void cancel() {
		if (stage != Stage.DONE && stage != Stage.FAILED) {
			stage = Stage.CANCELLED;
			chat.accept(String.format("Syx: cancelled, %,d blocks changed so far.", w.writes));
		}
	}

	public boolean running() {
		return stage == Stage.HEIGHT || stage == Stage.COLUMNS || stage == Stage.BLEND || stage == Stage.BED || stage == Stage.SWEEP
				|| stage == Stage.DETAILS;
	}

	public float progress() {
		return switch (stage) {
		case HEIGHT -> 0.02f * sampleIdx / Math.max(1, samplePoints.size());
		case COLUMNS -> 0.02f + 0.76f * chunkIdx / Math.max(1, chunkOrder.length);
		case BLEND -> 0.78f + 0.04f * chunkIdx / Math.max(1, chunkOrder.length);
		case BED -> 0.82f;
		case SWEEP -> 0.82f + 0.03f * chunkIdx / Math.max(1, chunkOrder.length);
		case DETAILS -> 0.85f + 0.15f * detailIdx / Math.max(1, details.size());
		default -> 1f;
		};
	}

	public String status() {
		return switch (stage) {
		case HEIGHT -> "Measuring terrain";
		case COLUMNS -> "Building terrain and buildings";
		case BLEND -> "Blending edges";
		case BED -> bedSmoothed ? "Smoothing the riverbed" : "Measuring the riverbed";
		case SWEEP -> "Sweeping away stray water and lava";
		case DETAILS -> "Trees, furniture and citizens";
		case DONE -> "Done";
		case CANCELLED -> "Cancelled";
		case FAILED -> "Failed (see log)";
		};
	}
}
