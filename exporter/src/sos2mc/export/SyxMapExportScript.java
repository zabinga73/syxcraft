package sos2mc.export;

import script.SCRIPT;
import snake2d.LOG;
import snake2d.Renderer;
import snake2d.util.file.FileGetter;
import snake2d.util.file.FilePutter;
import game.GAME;
import game.save.SaveFile;

/**
 * Exports the loaded settlement to a .syxmap file a short moment after every game start/load.
 * Saves nothing into the savegame, so removing the mod later is harmless.
 */
public final class SyxMapExportScript implements SCRIPT {

	public SyxMapExportScript() {
		ModLog.ln("script loaded by game (" + game.VERSION.VERSION_STRING + ")");
	}

	@Override
	public CharSequence name() {
		return "Syx Map Exporter (sos2mc)";
	}

	@Override
	public CharSequence desc() {
		return "Writes the settlement map to <user dir>/sos2mc/*.syxmap for the Minecraft converter.";
	}

	@Override
	public boolean isSelectable() {
		return false;
	}

	@Override
	public boolean forceInit() {
		return true;
	}

	@Override
	public SCRIPT_INSTANCE createInstance() {
		ModLog.ln("createInstance");
		return new Instance();
	}

	static final class Instance implements SCRIPT_INSTANCE {

		private final long created = System.nanoTime();
		private boolean done = false;
		/** name of the loaded save ("Asallamu"), null for a fresh game */
		private String saveName = null;

		Instance() {
			try {
				// instances are created before GAME.saver().load(..) runs, so this fires for the save being loaded
				GAME.saver().onAfterLoad(p -> {
					String f = p.getFileName().toString();
					if (f.lastIndexOf('.') > 0)
						f = f.substring(0, f.lastIndexOf('.'));
					saveName = SaveFile.name(f);
					ModLog.ln("loaded save: " + p.getFileName() + " -> name " + saveName);
				});
			} catch (Throwable e) {
				ModLog.err("could not hook save loading", e);
			}
		}

		@Override
		public void update(double ds) {
			tryExport();
		}

		// render runs every frame even while the game is paused (it loads paused), update does not
		@Override
		public void render(Renderer r, float ds) {
			tryExport();
		}

		private void tryExport() {
			if (done || System.nanoTime() - created < 2_000_000_000L)
				return;
			done = true;
			ModLog.ln("export start");
			try {
				long t0 = System.currentTimeMillis();
				String out = new Exporter(saveName).export();
				ModLog.ln("export done in " + (System.currentTimeMillis() - t0) + " ms: " + out);
				LOG.ln("sos2mc: exported " + out);
				GAME.Notify("Syx map exported for Minecraft:\n" + out);
			} catch (Throwable e) {
				ModLog.err("export FAILED", e);
				LOG.err("sos2mc: export failed");
				e.printStackTrace();
				GAME.Notify("Syx map export FAILED: " + e + "\nSee sos2mc/exporter.log");
			}
		}

		@Override
		public void save(FilePutter file) {
		}

		@Override
		public void load(FileGetter file) {
		}

		@Override
		public boolean handleBrokenSavedState() {
			return true;
		}
	}
}
