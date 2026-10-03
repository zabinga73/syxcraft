package dev.sos2mc.syxcraft.place;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import dev.sos2mc.syxcraft.Syxcraft;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Songs of Syx key -> Minecraft block state. Defaults live here; every entry can be overridden in
 * config/syxcraft/palette.json (written with all defaults on first use so it can be edited).
 */
public final class Palette {

	private static final Map<String, String> DEFAULTS = new LinkedHashMap<>();

	static {
		// open ground by SoS ground type
		d("ground.NORMAL", "minecraft:grass_block");
		d("ground.PASTURE", "minecraft:grass_block");
		d("ground.FOREST", "minecraft:grass_block");
		d("ground.INFERTILE", "minecraft:coarse_dirt");
		d("ground.ROCK", "minecraft:stone");
		d("ground.SAND", "minecraft:sand");
		d("ground.worn", "minecraft:dirt_path");
		d("ground.subsoil", "minecraft:dirt");
		d("ground.deep", "minecraft:stone");
		d("ground.indoors", "minecraft:packed_mud");

		// floors and roads (SoS floor keys)
		d("floor.DIRT", "minecraft:gravel");
		d("floor._DEFAULT_ROAD", "minecraft:gravel");
		d("floor._MAIN_ROAD", "minecraft:gravel");
		d("floor._MAIN_TOAD", "minecraft:gravel");
		d("floor.GRASS", "minecraft:grass_block");
		d("floor.SAND", "minecraft:sand");
		d("floor.MINE", "minecraft:gravel");
		d("floor.MINE_MUD", "minecraft:mud");
		d("floor.NATURE1", "minecraft:moss_block");
		d("floor.NATURE2", "minecraft:rooted_dirt");
		d("floor.STONE1", "minecraft:cobblestone");
		d("floor.STONE2", "minecraft:stone");
		d("floor.STONE3", "minecraft:andesite");
		d("floor.STONE_BRICK", "minecraft:stone_bricks");
		d("floor.STONE_CROSS", "minecraft:chiseled_stone_bricks");
		d("floor.STONE_LARGE_DARK", "minecraft:polished_deepslate");
		d("floor.STONE_MEDIUM_DARK", "minecraft:deepslate_tiles");
		d("floor.WOOD", "minecraft:spruce_planks");
		d("floor.WOOD_SQUARE", "minecraft:oak_planks");
		d("floor.DARK1", "minecraft:dark_oak_planks");
		d("floor.DARK2", "minecraft:polished_blackstone_bricks");
		d("floor.DARK3", "minecraft:deepslate_bricks");
		d("floor.DECOR1", "minecraft:terracotta");
		d("floor.DECOR2", "minecraft:white_terracotta");
		d("floor.DECOR3", "minecraft:light_gray_terracotta");
		d("floor.worn", "minecraft:coarse_dirt");

		// building structures (SoS structure keys: _MUD, WOOD, STONE, GRAND, _MOUNTAIN)
		structure("_MUD", "minecraft:mud_bricks", "minecraft:packed_mud", "minecraft:stripped_spruce_log",
				"minecraft:spruce_stairs", "minecraft:spruce_planks", "minecraft:spruce_door", "minecraft:glass_pane",
				"minecraft:spruce_planks");
		structure("WOOD", "minecraft:spruce_planks", "minecraft:cobblestone", "minecraft:spruce_log",
				"minecraft:dark_oak_stairs", "minecraft:dark_oak_planks", "minecraft:dark_oak_door", "minecraft:glass_pane",
				"minecraft:dark_oak_planks");
		structure("STONE", "minecraft:stone_bricks", "minecraft:cobblestone", "minecraft:chiseled_stone_bricks",
				"minecraft:deepslate_tile_stairs", "minecraft:deepslate_tiles", "minecraft:spruce_door",
				"minecraft:glass_pane", "minecraft:spruce_planks");
		structure("GRAND", "minecraft:smooth_quartz", "minecraft:polished_andesite", "minecraft:quartz_pillar",
				"minecraft:brick_stairs", "minecraft:bricks", "minecraft:birch_door", "minecraft:glass_pane",
				"minecraft:birch_planks");
		structure("_MOUNTAIN", "minecraft:stone", "minecraft:stone", "minecraft:stone", "minecraft:stone_stairs",
				"minecraft:stone", "minecraft:spruce_door", "minecraft:glass_pane", "minecraft:stone");
		structure("default", "minecraft:oak_planks", "minecraft:cobblestone", "minecraft:oak_log",
				"minecraft:spruce_stairs", "minecraft:spruce_planks", "minecraft:oak_door", "minecraft:glass_pane",
				"minecraft:oak_planks");

		// the thin wooden walls between houses packed wall to wall
		d("houseWall", "minecraft:spruce_planks");

		// fences and fortifications (SoS keys after FENCE_ / FORTIFICATION_)
		d("fence.WOOD", "minecraft:oak_fence");
		d("fence.STONE", "minecraft:cobblestone_wall");
		d("fence.BARRICADE", "minecraft:dark_oak_fence");
		d("fence.default", "minecraft:oak_fence");
		d("fort.WOOD", "minecraft:stripped_spruce_log");
		d("fort.STONE", "minecraft:stone_bricks");
		d("fort.GRAND", "minecraft:polished_deepslate");
		d("fort.default", "minecraft:stone_bricks");
		d("fortTop.WOOD", "minecraft:spruce_fence");
		d("fortTop.STONE", "minecraft:stone_brick_wall");
		d("fortTop.GRAND", "minecraft:polished_deepslate_wall");
		d("fortTop.default", "minecraft:stone_brick_wall");

		// nature
		d("water", "minecraft:water");
		d("waterBed", "minecraft:sand");
		d("waterBedDeep", "minecraft:gravel");
		d("bridge", "minecraft:spruce_planks");
		d("mountain", "minecraft:stone");
		d("mountainTop", "minecraft:andesite");
		d("rock", "minecraft:cobblestone");
		d("rockMoss", "minecraft:mossy_cobblestone");
		d("log", "minecraft:oak_log");
		d("logBirch", "minecraft:birch_log");
		d("logDark", "minecraft:dark_oak_log");
		d("leaves", "minecraft:oak_leaves[persistent=true]");
		d("leavesBirch", "minecraft:birch_leaves[persistent=true]");
		d("leavesDark", "minecraft:dark_oak_leaves[persistent=true]");
		d("bush", "minecraft:azalea_leaves[persistent=true]");
		d("bushFlower", "minecraft:flowering_azalea_leaves[persistent=true]");

		// mines: pit lining per mineral (SoS mineral / mine keys)
		d("ore.CLAY", "minecraft:clay");
		d("ore.COAL", "minecraft:coal_ore");
		d("ore.ORE", "minecraft:iron_ore");
		d("ore.GEM", "minecraft:emerald_ore");
		d("ore.STONE", "minecraft:stone");
		d("ore.SITHILON", "minecraft:amethyst_block");
		d("ore.default", "minecraft:stone");
	}

	/**
	 * Defaults from earlier versions. palette.json is written with every default, so a value equal to an old
	 * default means "never edited" and is upgraded to the current default; anything else is the user's choice.
	 */
	private static final Map<String, String> OLD_DEFAULTS = Map.of(
			"floor.DIRT", "minecraft:dirt_path", // < 0.5.0
			"floor._DEFAULT_ROAD", "minecraft:dirt_path", // < 0.5.0
			"ground.worn", "minecraft:coarse_dirt"); // < 0.5.0

	private static void structure(String k, String wall, String base, String pillar, String roofStairs, String roofBlock,
			String door, String window, String ceiling) {
		d("wall." + k, wall);
		d("wallBase." + k, base);
		d("pillar." + k, pillar);
		d("roof." + k, roofStairs);
		d("roofBlock." + k, roofBlock);
		d("door." + k, door);
		d("window." + k, window);
		d("ceiling." + k, ceiling);
	}

	private static void d(String k, String v) {
		DEFAULTS.put(k, v);
	}

	private final Map<String, String> entries = new LinkedHashMap<>(DEFAULTS);
	private final Map<String, BlockState> cache = new HashMap<>();

	public static Palette load() {
		Palette p = new Palette();
		Path file = FabricLoader.getInstance().getConfigDir().resolve("syxcraft").resolve("palette.json");
		Gson gson = new GsonBuilder().setPrettyPrinting().create();
		try {
			if (Files.exists(file)) {
				try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
					JsonObject o = JsonParser.parseReader(r).getAsJsonObject();
					for (String k : o.keySet()) {
						String v = o.get(k).getAsString();
						if (v.equals(OLD_DEFAULTS.get(k)))
							continue; // an untouched old default: keep the new one
						p.entries.put(k, v);
					}
				}
			}
			// (re)write so new default keys show up for the user to edit
			Files.createDirectories(file.getParent());
			try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
				gson.toJson(p.entries, w);
			}
		} catch (Exception e) {
			Syxcraft.LOG.warn("palette.json could not be read, using defaults: {}", e.toString());
		}
		return p;
	}

	/** Block for key, or the fallback key's block, or stone. */
	public BlockState get(String key, String fallbackKey) {
		BlockState s = cache.get(key);
		if (s != null)
			return s;
		String v = entries.get(key);
		if (v == null && fallbackKey != null)
			return get(fallbackKey, null);
		s = parse(v);
		cache.put(key, s);
		return s;
	}

	public BlockState get(String key) {
		return get(key, null);
	}

	public boolean has(String key) {
		return entries.containsKey(key);
	}

	public static BlockState parse(String v) {
		if (v == null)
			return Blocks.STONE.defaultBlockState();
		try {
			return BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, v, false).blockState();
		} catch (Exception e) {
			Syxcraft.LOG.warn("bad block '{}' in palette: {}", v, e.getMessage());
			return Blocks.STONE.defaultBlockState();
		}
	}
}
