package dev.sos2mc.syxcraft.place;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

/**
 * Finds the rivers of a Songs of Syx map within the placed area: water that leaves the area in two or more places
 * through openings much narrower than the area's side (a coast or a sea filling the area doesn't count). Pure Java,
 * shared by the placer screen (for its check mark) and the server (to line the city up with a Minecraft river).
 */
public final class Rivers {

	private Rivers() {
	}

	/** sides of the area: north (y = ty0), east (x = tx1), south (y = ty1), west (x = tx0) */
	public static final int N = 0, E = 1, S = 2, W = 3;

	/**
	 * Where a river leaves the area: on a side, tiles from..to along it (x for north/south, y for east/west, map tile
	 * coordinates, inclusive).
	 */
	public record Exit(int side, int from, int to) {
		public int width() {
			return to - from + 1;
		}

		public double centre() {
			return (from + to) / 2.0;
		}
	}

	/** an exit narrower than this many tiles is a creek, not worth lining up */
	static final int MIN_EXIT = 3;

	/** the placed area in map tiles (x0, y0, x1, y1 inclusive), the same maths as CityPlan */
	public static int[] region(int width, int height, int[] cityBounds, PlaceSettings.Area area, int margin) {
		if (area == PlaceSettings.Area.WHOLE_MAP)
			return new int[] { 0, 0, width - 1, height - 1 };
		return new int[] { Math.max(0, cityBounds[0] - margin), Math.max(0, cityBounds[1] - margin),
				Math.min(width - 1, cityBounds[2] + margin), Math.min(height - 1, cityBounds[3] + margin) };
	}

	/** the exits of every river in the region (empty: no river) */
	public static List<Exit> find(BitSet water, int width, int[] r) {
		int x0 = r[0], y0 = r[1], x1 = r[2], y1 = r[3], rw = x1 - x0 + 1, rh = y1 - y0 + 1;
		// water bodies inside the region
		int[] label = new int[rw * rh];
		List<Integer> area = new ArrayList<>();
		area.add(0);
		ArrayDeque<Integer> q = new ArrayDeque<>();
		for (int y = 0; y < rh; y++)
			for (int x = 0; x < rw; x++) {
				if (label[x + y * rw] != 0 || !water.get(x0 + x + (y0 + y) * width))
					continue;
				int id = area.size(), n = 0;
				label[x + y * rw] = id;
				q.add(x + y * rw);
				while (!q.isEmpty()) {
					int i = q.poll(), cx = i % rw, cy = i / rw;
					n++;
					for (int k = 0; k < 4; k++) {
						int nx = cx + (k == 1 ? 1 : k == 3 ? -1 : 0), ny = cy + (k == 2 ? 1 : k == 0 ? -1 : 0);
						if (nx < 0 || ny < 0 || nx >= rw || ny >= rh)
							continue;
						int j = nx + ny * rw;
						if (label[j] == 0 && water.get(x0 + nx + (y0 + ny) * width)) {
							label[j] = id;
							q.add(j);
						}
					}
				}
				area.add(n);
			}
		// runs of water along each side, by water body
		List<List<Exit>> exits = new ArrayList<>();
		for (int i = 0; i < area.size(); i++)
			exits.add(new ArrayList<>());
		boolean[] tooWide = new boolean[area.size()];
		for (int side = 0; side < 4; side++) {
			int len = side == N || side == S ? rw : rh;
			int start = -1, startId = 0;
			for (int a = 0; a <= len; a++) {
				int id = 0;
				if (a < len) {
					int x = side == N || side == S ? a : side == E ? rw - 1 : 0;
					int y = side == E || side == W ? a : side == S ? rh - 1 : 0;
					id = label[x + y * rw];
				}
				if (id == startId)
					continue;
				if (startId != 0 && a - start >= MIN_EXIT) {
					int off = side == N || side == S ? x0 : y0;
					exits.get(startId).add(new Exit(side, off + start, off + a - 1));
					// a sea or a coast: an opening as wide as half the side
					if (a - start > len / 2)
						tooWide[startId] = true;
				}
				start = a;
				startId = id;
			}
		}
		List<Exit> out = new ArrayList<>();
		for (int id = 1; id < area.size(); id++)
			if (exits.get(id).size() >= 2 && !tooWide[id] && area.get(id) <= 0.6 * rw * rh && crosses(exits.get(id), r))
				out.addAll(exits.get(id));
		return out;
	}

	/**
	 * Do two of the exits lie far apart round the area's edge? A lake or bay clipped by a corner of the area leaves
	 * through two openings close together; a river runs across.
	 */
	private static boolean crosses(List<Exit> exits, int[] r) {
		int rw = r[2] - r[0] + 1, rh = r[3] - r[1] + 1, per = 2 * (rw + rh);
		double best = 0;
		for (int i = 0; i < exits.size(); i++)
			for (int j = i + 1; j < exits.size(); j++) {
				double d = Math.abs(perimeter(exits.get(i), r) - perimeter(exits.get(j), r));
				best = Math.max(best, Math.min(d, per - d));
			}
		return best >= Math.min(rw, rh) / 2.0;
	}

	/** distance of an exit's middle clockwise round the edge from the NW corner */
	private static double perimeter(Exit e, int[] r) {
		int rw = r[2] - r[0] + 1, rh = r[3] - r[1] + 1;
		return switch (e.side()) {
		case N -> e.centre() - r[0];
		case E -> rw + e.centre() - r[1];
		case S -> rw + rh + (r[2] - e.centre());
		default -> 2 * rw + rh + (r[3] - e.centre());
		};
	}

	/** packs a bit set into exactly n bits' worth of bytes (for the network) */
	public static byte[] pack(BitSet b, int n) {
		byte[] out = new byte[(n + 7) / 8];
		byte[] raw = b.toByteArray();
		System.arraycopy(raw, 0, out, 0, Math.min(raw.length, out.length));
		return out;
	}
}
