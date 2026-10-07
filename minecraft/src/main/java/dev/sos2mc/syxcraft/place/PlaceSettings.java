package dev.sos2mc.syxcraft.place;

import net.minecraft.network.FriendlyByteBuf;

/** Everything the placement screen (or /syx place) can configure. Mutable so the GUI can edit it in place. */
public final class PlaceSettings {

	public enum Origin {
		/** city centre at the player */
		PLAYER,
		/** city centre at x/z */
		COORDS
	}

	public enum Height {
		/** ground at the median terrain height under the city */
		AUTO,
		/** water surface at sea level, so SoS water lines up with the ocean and rivers */
		SEA_LEVEL,
		/** ground at y */
		CUSTOM
	}

	public enum Area {
		/** the built-up part of the map plus a margin */
		CITY,
		/** the whole 768x768 settlement map */
		WHOLE_MAP
	}

	public enum Roof {
		/** sloping up one block per block from every wall, flat-topped past 6 (4 at scale 1) */
		HIPPED,
		/** steep: two blocks per block, up to a sharp ridge */
		POINTED,
		/** rounded: steep at the walls, curving over to a round top, sized to each building */
		DOMED,
		FLAT,
		/** each building picks hipped, pointed or domed */
		MIXED
	}

	public enum Topography {
		/** all ground at one level (as before 0.8.0) */
		FLAT,
		/** rolling hills in the open land around and between the settlement */
		RANDOM,
		/** open land follows the Minecraft terrain that was there */
		LOCAL
	}

	// layer toggles
	public static final int TERRAIN = 1, CLEAR_ABOVE = 2, FILL_BELOW = 4, VEGETATION = 8, BUILDINGS = 16, ROOFS = 32,
			FURNITURE = 64, QUARRIES = 128, BLEND_EDGES = 256, WATER = 512,
			CITIZENS = 1024;
	public static final int ALL_LAYERS = TERRAIN | CLEAR_ABOVE | FILL_BELOW | VEGETATION | BUILDINGS | ROOFS | FURNITURE
			| QUARRIES | BLEND_EDGES | WATER;

	public String file = "";
	public Origin origin = Origin.PLAYER;
	public int x, z;
	public Height height = Height.AUTO;
	public int y = 64;
	public int scale = 2;
	public Area area = Area.CITY;
	public int margin = 32;
	public int layers = ALL_LAYERS;
	public int quarryDepth = 12;
	/** clear interior height of buildings in blocks (0 = automatic: 3 at scale 1, 4 at scale 2) */
	public int wallHeight = 0;
	public Roof roof = Roof.HIPPED;
	public Topography topography = Topography.FLAT;
	/** topography: the most open land may rise above the city level, in blocks */
	public int hills = 16;
	/** topography: the most open land may sink below the city level, in blocks */
	public int valleys = 5;
	/** mountains ignore the Hills cap: Songs of Syx mountains rise into real peaks, and local terrain keeps its height */
	public boolean peaks = false;
	/** most villagers the CITIZENS layer spawns; a big city is sampled evenly down to this (0 = everyone) */
	public int citizenCap = 100;
	/** each building's wooden roof in a random wood instead of its material's */
	public boolean roofWoods = false;
	/** most extra wall height a building may get, in blocks at scale 2 (scaled with the scale; 0 = all the same) */
	public int heightVariety = 0;

	public boolean has(int layer) {
		return (layers & layer) != 0;
	}

	public void set(int layer, boolean on) {
		layers = on ? layers | layer : layers & ~layer;
	}

	/** the most extra height a building gets at this scale */
	public int maxExtraHeight() {
		return (int) Math.round(heightVariety * scale / 2.0);
	}

	public int interiorHeight() {
		return wallHeight > 0 ? wallHeight : (scale >= 2 ? 4 : 3);
	}

	public PlaceSettings copy() {
		PlaceSettings s = new PlaceSettings();
		s.file = file;
		s.origin = origin;
		s.x = x;
		s.z = z;
		s.height = height;
		s.y = y;
		s.scale = scale;
		s.area = area;
		s.margin = margin;
		s.topography = topography;
		s.hills = hills;
		s.valleys = valleys;
		s.peaks = peaks;
		s.citizenCap = citizenCap;
		s.roofWoods = roofWoods;
		s.heightVariety = heightVariety;
		s.layers = layers;
		s.quarryDepth = quarryDepth;
		s.wallHeight = wallHeight;
		s.roof = roof;
		return s;
	}

	public void write(FriendlyByteBuf b) {
		b.writeUtf(file);
		b.writeVarInt(origin.ordinal());
		b.writeInt(x);
		b.writeInt(z);
		b.writeVarInt(height.ordinal());
		b.writeInt(y);
		b.writeVarInt(scale);
		b.writeVarInt(area.ordinal());
		b.writeVarInt(margin);
		b.writeVarInt(layers);
		b.writeVarInt(quarryDepth);
		b.writeVarInt(wallHeight);
		b.writeVarInt(roof.ordinal());
		b.writeVarInt(topography.ordinal());
		b.writeVarInt(hills);
		b.writeVarInt(valleys);
		b.writeVarInt(citizenCap);
		b.writeBoolean(roofWoods);
		b.writeVarInt(heightVariety);
		b.writeBoolean(peaks);
	}

	public static PlaceSettings read(FriendlyByteBuf b) {
		PlaceSettings s = new PlaceSettings();
		s.file = b.readUtf();
		s.origin = Origin.values()[b.readVarInt()];
		s.x = b.readInt();
		s.z = b.readInt();
		s.height = Height.values()[b.readVarInt()];
		s.y = b.readInt();
		s.scale = Math.max(1, Math.min(4, b.readVarInt()));
		s.area = Area.values()[b.readVarInt()];
		s.margin = Math.max(0, Math.min(256, b.readVarInt()));
		s.layers = b.readVarInt();
		s.quarryDepth = Math.max(1, Math.min(64, b.readVarInt()));
		s.wallHeight = Math.max(0, Math.min(16, b.readVarInt()));
		s.roof = Roof.values()[b.readVarInt()];
		s.topography = Topography.values()[b.readVarInt()];
		s.hills = Math.max(1, Math.min(64, b.readVarInt()));
		s.valleys = Math.max(0, Math.min(64, b.readVarInt()));
		s.citizenCap = Math.max(0, Math.min(100000, b.readVarInt()));
		s.roofWoods = b.readBoolean();
		s.heightVariety = Math.max(0, Math.min(64, b.readVarInt()));
		s.peaks = b.readBoolean();
		return s;
	}
}
