package dev.sos2mc.syxcraft.place;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

import dev.sos2mc.syxcraft.Syxcraft;
import dev.sos2mc.syxcraft.map.SyxMap;
import net.minecraft.server.level.ServerLevel;

/**
 * Builds a city over many server ticks within a time budget, so the game keeps running. Stages: pick the ground
 * height, build every block column chunk by chunk, blend the edges into the terrain, sweep away stray water and
 * lava, then trees, furniture and citizens.
 */
public final class PlacementJob {

	public enum Stage {
		HEIGHT, COLUMNS, BLEND, SWEEP, DETAILS, DONE, CANCELLED, FAILED
	}

	public static final int BLEND_WIDTH = 16;

	public final CityPlan plan;
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
		int margin = st.has(PlaceSettings.BLEND_EDGES) ? BLEND_WIDTH : 0;
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
						continue;
					}
					columnsOfChunk(chunkOrder[chunkIdx++], false);
				}
				case BLEND -> {
					if (chunkIdx >= chunkOrder.length) {
						stage = Stage.SWEEP;
						chunkIdx = 0;
						continue;
					}
					columnsOfChunk(chunkOrder[chunkIdx++], true);
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
			var box = new net.minecraft.world.phys.AABB(plan.X0 - BLEND_WIDTH - 2, w.minY(), plan.Z0 - BLEND_WIDTH - 2,
					plan.X0 + plan.bw + BLEND_WIDTH + 2, w.maxY(), plan.Z0 + plan.bh + BLEND_WIDTH + 2);
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
					if (ring >= 1 && ring <= BLEND_WIDTH)
						blendTop[(x - cx0 * 16) + (z - cz0 * 16) * chunksX * 16] = plan.blendColumn(w, x, z, ring, BLEND_WIDTH);
				}
			}
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
		return stage == Stage.HEIGHT || stage == Stage.COLUMNS || stage == Stage.BLEND || stage == Stage.SWEEP
				|| stage == Stage.DETAILS;
	}

	public float progress() {
		return switch (stage) {
		case HEIGHT -> 0.02f * sampleIdx / Math.max(1, samplePoints.size());
		case COLUMNS -> 0.02f + 0.76f * chunkIdx / Math.max(1, chunkOrder.length);
		case BLEND -> 0.78f + 0.04f * chunkIdx / Math.max(1, chunkOrder.length);
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
		case SWEEP -> "Sweeping away stray water and lava";
		case DETAILS -> "Trees, furniture and citizens";
		case DONE -> "Done";
		case CANCELLED -> "Cancelled";
		case FAILED -> "Failed (see log)";
		};
	}
}
