package dev.sos2mc.syxcraft.map;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

import com.google.gson.stream.JsonReader;

import net.fabricmc.loader.api.FabricLoader;

/** Finds .syxmap exports: the game's own export folder (native, Windows or Proton) and <gameDir>/syxmaps. */
public final class SyxMapFiles {

	private SyxMapFiles() {
	}

	/** Short description of one export, cheap to compute (reads only the JSON header). */
	public record Entry(String name, String city, String save, String exported, int width, int height, long size,
			long modified) {
	}

	public static List<Path> searchDirs() {
		Set<Path> dirs = new LinkedHashSet<>();
		dirs.add(FabricLoader.getInstance().getGameDir().resolve("syxmaps"));
		String home = System.getProperty("user.home");
		String appdata = System.getenv("APPDATA");
		if (appdata != null)
			dirs.add(Paths.get(appdata, "songsofsyx", "sos2mc"));
		// Songs of Syx under Steam Proton (Linux), and the native Linux / macOS user folders
		dirs.add(Paths.get(home, ".local/share/Steam/steamapps/compatdata/1162750/pfx/drive_c/users/steamuser/AppData/Roaming/songsofsyx/sos2mc"));
		dirs.add(Paths.get(home, ".steam/steam/steamapps/compatdata/1162750/pfx/drive_c/users/steamuser/AppData/Roaming/songsofsyx/sos2mc"));
		dirs.add(Paths.get(home, ".var/app/com.valvesoftware.Steam/.local/share/Steam/steamapps/compatdata/1162750/pfx/drive_c/users/steamuser/AppData/Roaming/songsofsyx/sos2mc"));
		dirs.add(Paths.get(home, ".local/share/songsofsyx/sos2mc"));
		dirs.add(Paths.get(home, "Library/Application Support/songsofsyx/sos2mc"));
		List<Path> res = new ArrayList<>();
		for (Path d : dirs)
			if (Files.isDirectory(d))
				res.add(d);
		return res;
	}

	public static List<Path> list() {
		List<Path> res = new ArrayList<>();
		for (Path d : searchDirs()) {
			try (Stream<Path> s = Files.list(d)) {
				s.filter(p -> p.getFileName().toString().endsWith(".syxmap")).forEach(res::add);
			} catch (IOException e) {
				// skip unreadable folder
			}
		}
		res.sort(Comparator.comparingLong((Path p) -> {
			try {
				return Files.getLastModifiedTime(p).toMillis();
			} catch (IOException e) {
				return 0L;
			}
		}).reversed());
		return res;
	}

	public static Path find(String name) {
		for (Path p : list())
			if (p.getFileName().toString().equals(name))
				return p;
		return null;
	}

	public static List<Entry> entries() {
		List<Entry> res = new ArrayList<>();
		for (Path p : list()) {
			try {
				res.add(header(p));
			} catch (Exception e) {
				// not readable, skip
			}
		}
		return res;
	}

	/** Streams just the leading scalar fields of the JSON (they come before the big layers). */
	static Entry header(Path p) throws IOException {
		String city = null, save = null, exported = null;
		int w = 0, h = 0;
		try (Reader r = new InputStreamReader(new GZIPInputStream(Files.newInputStream(p)), StandardCharsets.UTF_8);
				JsonReader jr = new JsonReader(r)) {
			jr.beginObject();
			while (jr.hasNext()) {
				String k = jr.nextName();
				switch (k) {
				case "city" -> city = nullableString(jr);
				case "save" -> save = nullableString(jr);
				case "exported" -> exported = nullableString(jr);
				case "width" -> w = jr.nextInt();
				case "height" -> h = jr.nextInt();
				case "palettes" -> {
					jr.close();
					return new Entry(p.getFileName().toString(), city, save, exported, w, h, Files.size(p),
							Files.getLastModifiedTime(p).toMillis());
				}
				default -> jr.skipValue();
				}
			}
		}
		return new Entry(p.getFileName().toString(), city, save, exported, w, h, Files.size(p),
				Files.getLastModifiedTime(p).toMillis());
	}

	private static String nullableString(JsonReader jr) throws IOException {
		if (jr.peek() == com.google.gson.stream.JsonToken.NULL) {
			jr.nextNull();
			return null;
		}
		return jr.nextString();
	}
}
