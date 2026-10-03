package sos2mc.export;

import java.io.IOException;
import java.io.Writer;
import java.util.Collection;
import java.util.Map;

/** Minimal JSON writer (the game has no JSON-out library we can rely on). */
final class JsonOut {

	private JsonOut() {
	}

	static void write(Writer w, Object o) throws IOException {
		if (o == null) {
			w.write("null");
		} else if (o instanceof String || o instanceof CharSequence) {
			str(w, o.toString());
		} else if (o instanceof Number || o instanceof Boolean) {
			w.write(o.toString());
		} else if (o instanceof Map) {
			w.write('{');
			boolean first = true;
			for (Map.Entry<?, ?> e : ((Map<?, ?>) o).entrySet()) {
				if (!first)
					w.write(',');
				first = false;
				str(w, String.valueOf(e.getKey()));
				w.write(':');
				write(w, e.getValue());
			}
			w.write('}');
		} else if (o instanceof Collection) {
			w.write('[');
			boolean first = true;
			for (Object x : (Collection<?>) o) {
				if (!first)
					w.write(',');
				first = false;
				write(w, x);
			}
			w.write(']');
		} else {
			str(w, o.toString());
		}
	}

	private static void str(Writer w, String s) throws IOException {
		w.write('"');
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			switch (c) {
			case '"': w.write("\\\""); break;
			case '\\': w.write("\\\\"); break;
			case '\n': w.write("\\n"); break;
			case '\r': w.write("\\r"); break;
			case '\t': w.write("\\t"); break;
			default:
				if (c < 0x20)
					w.write(String.format("\\u%04x", (int) c));
				else
					w.write(c);
			}
		}
		w.write('"');
	}
}
