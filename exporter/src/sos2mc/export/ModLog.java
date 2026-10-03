package sos2mc.export;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * The game's stdout goes to /dev/null under Proton, so the mod keeps its own log:
 * %APPDATA%/songsofsyx/sos2mc/exporter.log (falls back to ~/sos2mc-exporter.log).
 */
final class ModLog {

	private ModLog() {
	}

	static Path dir() {
		String appdata = System.getenv("APPDATA");
		Path base = appdata != null ? Paths.get(appdata, "songsofsyx") : Paths.get(System.getProperty("user.home"));
		return base.resolve("sos2mc");
	}

	static synchronized void ln(String s) {
		try {
			Path d = dir();
			Files.createDirectories(d);
			String line = new SimpleDateFormat("HH:mm:ss").format(new Date()) + " " + s + System.lineSeparator();
			Files.writeString(d.resolve("exporter.log"), line, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
					StandardOpenOption.APPEND);
		} catch (Throwable e) {
			// logging must never break the game
		}
	}

	static void err(String s, Throwable t) {
		StringWriter w = new StringWriter();
		t.printStackTrace(new PrintWriter(w));
		ln(s + "\n" + w);
	}
}
