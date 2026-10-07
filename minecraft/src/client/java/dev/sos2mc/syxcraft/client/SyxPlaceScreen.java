package dev.sos2mc.syxcraft.client;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import dev.sos2mc.syxcraft.map.SyxMapFiles;
import dev.sos2mc.syxcraft.net.SyxNet;
import dev.sos2mc.syxcraft.place.PlaceSettings;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** The "plop a Syx city" screen: pick an export, set where/how, toggle layers, preview and place. */
public class SyxPlaceScreen extends Screen {

	private static final int ROW = 24;
	private final PlaceSettings st = SyxcraftClient.settings;

	private EditBox xBox, zBox, yBox;
	private Button placeButton;

	public SyxPlaceScreen() {
		super(Component.literal("Syxcraft " + net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer("syxcraft")
				.map(c -> "v" + c.getMetadata().getVersion().getFriendlyString()).orElse("") + ": place a Songs of Syx city"));
	}

	@Override
	public boolean isPauseScreen() {
		return false; // keep the integrated server ticking so placement progresses behind the screen
	}

	@Override
	protected void init() {
		if (SyxcraftClient.files == null)
			ClientPlayNetworking.send(new SyxNet.RequestFiles());

		int colW = 200, gap = 10;
		int left = width / 2 - colW - gap / 2, right = width / 2 + gap / 2;
		int y0 = 34;

		/* ---------------- left column: what and where ---------------- */
		int y = y0;
		List<String> names = new ArrayList<>();
		if (SyxcraftClient.files != null)
			for (SyxMapFiles.Entry e : SyxcraftClient.files.entries())
				names.add(e.name());
		if (names.isEmpty()) {
			addRenderableWidget(Button.builder(Component.literal(SyxcraftClient.files == null ? "Looking for exports..." : "No .syxmap files found (refresh)"),
					b -> refresh()).bounds(left, y, colW, 20).build());
		} else {
			if (!names.contains(st.file))
				st.file = names.get(0);
			addRenderableWidget(CycleButton.builder(this::fileLabel, st.file).withValues(names)
					.create(left, y, colW - 24, 20, Component.literal("City"), (b, v) -> {
						st.file = v;
						requestInfo();
					}));
			addRenderableWidget(Button.builder(Component.literal("⟳"), b -> refresh()).bounds(left + colW - 20, y, 20, 20)
					.tooltip(Tooltip.create(Component.literal("Rescan for new exports"))).build());
		}
		y += ROW + 12; // room for the info line

		addRenderableWidget(CycleButton.builder((PlaceSettings.Origin o) -> Component.literal(o == PlaceSettings.Origin.PLAYER ? "Centred on me" : "Centred on X/Z"), st.origin)
				.withValues(PlaceSettings.Origin.values()).create(left, y, colW, 20, Component.literal("Position"), (b, v) -> {
					st.origin = v;
					rebuildWidgets();
				}));
		y += ROW;
		xBox = numberBox(left, y, colW / 2 - 2, "X", st.origin == PlaceSettings.Origin.PLAYER ? playerX() : st.x, v -> st.x = v);
		zBox = numberBox(left + colW / 2 + 2, y, colW / 2 - 2, "Z", st.origin == PlaceSettings.Origin.PLAYER ? playerZ() : st.z, v -> st.z = v);
		xBox.setEditable(st.origin == PlaceSettings.Origin.COORDS);
		zBox.setEditable(st.origin == PlaceSettings.Origin.COORDS);
		y += ROW;

		addRenderableWidget(CycleButton.builder((PlaceSettings.Height h) -> Component.literal(switch (h) {
		case AUTO -> "Ground: match terrain";
		case SEA_LEVEL -> "Ground: water at sea level";
		case CUSTOM -> "Ground: custom Y";
		}), st.height).displayOnlyValue().withValues(PlaceSettings.Height.values())
				.withTooltip(h -> Tooltip.create(Component.literal(switch (h) {
				case AUTO -> "Ground level = the median height of the land under the city.";
				case SEA_LEVEL -> "Puts the city's water surface at sea level, so its rivers and coast line up with the ocean.";
				case CUSTOM -> "Ground level = the Y below.";
				})))
				.create(left, y, colW, 20, Component.literal("Height"), (b, v) -> {
					st.height = v;
					rebuildWidgets();
				}));
		y += ROW;
		yBox = numberBox(left, y, colW, "Ground Y", st.y, v -> st.y = v);
		yBox.setEditable(st.height == PlaceSettings.Height.CUSTOM);
		y += ROW;

		addRenderableWidget(CycleButton.builder((Integer s) -> Component.literal(s + " block" + (s > 1 ? "s" : "") + " per tile"), st.scale)
				.withValues(1, 2, 3).create(left, y, colW, 20, Component.literal("Scale"), (b, v) -> {
					st.scale = v;
					updatePreview();
				}));
		y += ROW;
		addRenderableWidget(CycleButton.builder((PlaceSettings.Area a) -> Component.literal(a == PlaceSettings.Area.CITY ? "City + margin" : "Whole map"), st.area)
				.withValues(PlaceSettings.Area.values()).create(left, y, colW / 2 + 30, 20, Component.literal("Area"), (b, v) -> {
					st.area = v;
					updatePreview();
				}));
		addRenderableWidget(CycleButton.builder((Integer m) -> Component.literal("+" + m), st.margin)
				.withValues(8, 16, 32, 64, 128).create(left + colW / 2 + 34, y, colW / 2 - 34, 20, Component.literal("Margin"), (b, v) -> {
					st.margin = v;
					updatePreview();
				}));

		y += ROW;
		addRenderableWidget(CycleButton.builder((PlaceSettings.Topography t) -> Component.literal(switch (t) {
			case FLAT -> "Flat";
			case RANDOM -> "Random";
			case LOCAL -> "Local terrain";
		}), st.topography).displayOnlyValue().withValues(PlaceSettings.Topography.values())
				.withTooltip(t -> Tooltip.create(Component.literal(switch (t) {
					case FLAT -> "All the land around the city at one level.";
					case RANDOM -> "Rolling hills in the open land around and between the settlement. Buildings and rooms stay flat; roads follow the land.";
					case LOCAL -> "Open land follows the Minecraft terrain that was there. Buildings and rooms stay flat; roads follow the land.";
				}))).create(left, y, colW, 20, Component.literal("Topography"), (b, v) -> st.topography = v));
		y += ROW;
		addRenderableWidget(CycleButton.builder((Integer h) -> Component.literal("+" + h), st.hills)
				.withValues(8, 16, 24, 32, 48)
				.withTooltip(h -> Tooltip.create(Component.literal("Topography: the most open land may rise above the city's level, in blocks.")))
				.create(left, y, colW / 2 - 2, 20, Component.literal("Hills"), (b, v) -> st.hills = v));
		addRenderableWidget(CycleButton.builder((Integer h) -> Component.literal(h == 0 ? "none" : "-" + h), st.valleys)
				.withValues(0, 2, 5, 8, 12, 16, 24)
				.withTooltip(h -> Tooltip.create(Component.literal("Topography: the most open land may sink below the city's level, in blocks.")))
				.create(left + colW / 2 + 2, y, colW / 2 - 2, 20, Component.literal("Valleys"), (b, v) -> st.valleys = v));

		/* ---------------- right column: what to build ---------------- */
		y = y0;
		toggle(right, y, colW / 2 - 2, "Terrain", PlaceSettings.TERRAIN, "Ground, mountains and rocks.");
		toggle(right + colW / 2 + 2, y, colW / 2 - 2, "Water", PlaceSettings.WATER, "Lakes, rivers and the sea.");
		y += ROW;
		toggle(right, y, colW / 2 - 2, "Clear above", PlaceSettings.CLEAR_ABOVE, "Remove hills and trees above the new ground.");
		toggle(right + colW / 2 + 2, y, colW / 2 - 2, "Fill below", PlaceSettings.FILL_BELOW, "Fill caves, water and dips under the new ground.");
		y += ROW;
		toggle(right, y, colW / 2 - 2, "Plants", PlaceSettings.VEGETATION, "Trees, bushes, flowers, wild crops, grass.");
		toggle(right + colW / 2 + 2, y, colW / 2 - 2, "Blend edges", PlaceSettings.BLEND_EDGES, "Ramp the terrain around the city into the flat ground.");
		y += ROW;
		toggle(right, y, colW / 2 - 2, "Buildings", PlaceSettings.BUILDINGS, "Walls, doors, fences, fortifications.");
		toggle(right + colW / 2 + 2, y, colW / 2 - 2, "Roofs", PlaceSettings.ROOFS, "Roofs over buildings.");
		y += ROW;
		toggle(right, y, colW / 2 - 2, "Furniture", PlaceSettings.FURNITURE, "Tables, workshops, storage barrels, shrines...");
		toggle(right + colW / 2 + 2, y, colW / 2 - 2, "Quarries", PlaceSettings.QUARRIES, "Dig mines and clay pits as quarries with a ladder.");
		y += ROW;
		addRenderableWidget(CycleButton.builder((PlaceSettings.Roof r) -> Component.literal(switch (r) {
		case HIPPED -> "Hipped";
		case POINTED -> "Pointed";
		case DOMED -> "Domed";
		case FLAT -> "Flat";
		case MIXED -> "Mixed";
		}), st.roof)
				.withValues(PlaceSettings.Roof.values()).create(right, y, colW / 2 - 2, 20, Component.literal("Roof"), (b, v) -> st.roof = v));
		addRenderableWidget(CycleButton.builder((Integer h) -> Component.literal(h == 0 ? "auto" : String.valueOf(h)), st.wallHeight)
				.withValues(0, 3, 4, 5, 6, 8).create(right + colW / 2 + 2, y, colW / 2 - 2, 20, Component.literal("Walls"), (b, v) -> st.wallHeight = v));
		y += ROW;
		addRenderableWidget(CycleButton.builder((Integer d) -> Component.literal(d + " blocks"), st.quarryDepth)
				.withValues(6, 8, 12, 16, 24, 32).create(right, y, colW / 2 - 2, 20, Component.literal("Quarry"), (b, v) -> st.quarryDepth = v));
		// citizens: off, or up to this many villagers (a big city is sampled evenly)
		int citizens = st.has(PlaceSettings.CITIZENS) ? st.citizenCap : -1;
		addRenderableWidget(CycleButton.builder((Integer n) -> Component.literal(n < 0 ? "Off" : n == 0 ? "All" : "≤" + n), citizens)
				.withValues(-1, 25, 50, 100, 200, 400, 0)
				.withTooltip(n -> Tooltip.create(Component.literal("Experimental: the city's people as villagers, named after them, with a profession from their job. "
						+ "A big city is sampled evenly down to this many, since hundreds of villagers slow the game down. Needs exporter 0.3.0.")))
				.create(right + colW / 2 + 2, y, colW / 2 - 2, 20, Component.literal("Citizens"), (b, v) -> {
					st.set(PlaceSettings.CITIZENS, v >= 0);
					if (v >= 0)
						st.citizenCap = v;
				}));
		y += ROW;
		addRenderableWidget(CycleButton.onOffBuilder(st.roofWoods)
				.withTooltip(v -> Tooltip.create(Component.literal("Each building's wooden roof in a random wood (oak, spruce, birch, jungle, acacia, dark oak, mangrove, cherry) instead of its material's.")))
				.create(right, y, colW / 2 - 2, 20, Component.literal("Mixed woods"), (b, v) -> st.roofWoods = v));
		addRenderableWidget(CycleButton.builder((Integer h) -> Component.literal(h == 0 ? "Off" : "+" + h), st.heightVariety)
				.withValues(0, 5, 10, 20, 30)
				.withTooltip(h -> Tooltip.create(Component.literal("Height variety: each building gets up to this many extra blocks of wall, most of them in the lower half. Set for scale 2 and scaled with the scale.")))
				.create(right + colW / 2 + 2, y, colW / 2 - 2, 20, Component.literal("Taller"), (b, v) -> st.heightVariety = v));
		y += ROW;
		addRenderableWidget(CycleButton.onOffBuilder(SyxcraftClient.showPreview)
				.withTooltip(v -> Tooltip.create(Component.literal("Show the city's outline in the world with particles.")))
				.create(right, y, colW / 2 - 2, 20, Component.literal("Preview"), (b, v) -> SyxcraftClient.showPreview = v));
		addRenderableWidget(CycleButton.onOffBuilder(st.peaks)
				.withTooltip(v -> Tooltip.create(Component.literal("Mountains ignore the Hills cap. The city's own mountains rise into real peaks with ridges, "
						+ "saddles and snowy tops (as high as the world allows), and Local terrain keeps its mountains at their full height.")))
				.create(right + colW / 2 + 2, y, colW / 2 - 2, 20, Component.literal("Peaks"), (b, v) -> st.peaks = v));

		/* ---------------- bottom row ---------------- */
		int by = height - 52;
		placeButton = addRenderableWidget(Button.builder(Component.literal("Place city"), b -> place())
				.bounds(width / 2 - 154, by, 100, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Cancel placement"), b -> ClientPlayNetworking.send(new SyxNet.Cancel()))
				.bounds(width / 2 - 50, by, 100, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Close"), b -> onClose()).bounds(width / 2 + 54, by, 100, 20).build());
		placeButton.active = !names.isEmpty();

		if (SyxcraftClient.info == null || !SyxcraftClient.info.file().equals(st.file))
			requestInfo();
		updatePreview();
	}

	private void toggle(int x, int y, int w, String label, int layer, String tip) {
		addRenderableWidget(CycleButton.onOffBuilder(st.has(layer)).withTooltip(v -> Tooltip.create(Component.literal(tip)))
				.create(x, y, w, 20, Component.literal(label), (b, v) -> st.set(layer, v)));
	}

	private EditBox numberBox(int x, int y, int w, String hint, int value, java.util.function.IntConsumer onChange) {
		EditBox box = new EditBox(font, x, y, w, 20, Component.literal(hint));
		box.setHint(Component.literal(hint));
		box.setValue(String.valueOf(value));
		box.setResponder(s -> {
			try {
				onChange.accept(Integer.parseInt(s.trim()));
				updatePreview();
			} catch (NumberFormatException e) {
				// keep the last valid value
			}
		});
		return addRenderableWidget(box);
	}

	private Component fileLabel(String name) {
		if (SyxcraftClient.files != null)
			for (SyxMapFiles.Entry e : SyxcraftClient.files.entries())
				if (e.name().equals(name)) {
					String save = e.save().isEmpty() ? e.city() : e.save();
					return Component.literal(save + " (" + new SimpleDateFormat("MMM d HH:mm").format(new Date(e.modified())) + ")");
				}
		return Component.literal(name);
	}

	private void refresh() {
		SyxcraftClient.files = null;
		ClientPlayNetworking.send(new SyxNet.RequestFiles());
		rebuildWidgets();
	}

	private void requestInfo() {
		if (st.file != null && !st.file.isEmpty())
			ClientPlayNetworking.send(new SyxNet.RequestInfo(st.file));
	}

	void onFiles() {
		rebuildWidgets();
	}

	void onInfo() {
		updatePreview();
	}

	private int playerX() {
		return minecraft.player == null ? 0 : minecraft.player.getBlockX();
	}

	private int playerZ() {
		return minecraft.player == null ? 0 : minecraft.player.getBlockZ();
	}

	/** footprint in blocks: x0, z0, x1, z1, y (same maths as CityPlan) */
	private int[] footprint() {
		SyxNet.MapInfo in = SyxcraftClient.info;
		if (in == null || !in.file().equals(st.file))
			return null;
		int tx0, ty0, tx1, ty1;
		if (st.area == PlaceSettings.Area.WHOLE_MAP) {
			tx0 = 0;
			ty0 = 0;
			tx1 = in.width() - 1;
			ty1 = in.height() - 1;
		} else {
			tx0 = Math.max(0, in.cx0() - st.margin);
			ty0 = Math.max(0, in.cy0() - st.margin);
			tx1 = Math.min(in.width() - 1, in.cx1() + st.margin);
			ty1 = Math.min(in.height() - 1, in.cy1() + st.margin);
		}
		int bw = (tx1 - tx0 + 1) * st.scale, bh = (ty1 - ty0 + 1) * st.scale;
		int cx = st.origin == PlaceSettings.Origin.COORDS ? st.x : playerX();
		int cz = st.origin == PlaceSettings.Origin.COORDS ? st.z : playerZ();
		int x0 = cx - bw / 2, z0 = cz - bh / 2;
		int y = st.height == PlaceSettings.Height.CUSTOM ? st.y
				: st.height == PlaceSettings.Height.SEA_LEVEL && SyxcraftClient.files != null ? SyxcraftClient.files.seaLevel()
						: (minecraft.player == null ? 64 : minecraft.player.getBlockY() - 1);
		return new int[] { x0, z0, x0 + bw - 1, z0 + bh - 1, y };
	}

	private void updatePreview() {
		SyxcraftClient.preview = footprint();
	}

	private void place() {
		if (st.origin == PlaceSettings.Origin.PLAYER) {
			st.x = playerX();
			st.z = playerZ();
		}
		ClientPlayNetworking.send(new SyxNet.Start(st.copy()));
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		super.extractRenderState(g, mouseX, mouseY, a);
		g.centeredText(font, title, width / 2, 12, 0xFFFFFFFF);

		int colW = 200, left = width / 2 - colW - 5;
		SyxNet.MapInfo in = SyxcraftClient.info;
		String line;
		if (in != null && in.file().equals(st.file))
			line = String.format("%dx%d map, %d rooms, %d furniture pieces", in.width(), in.height(), in.rooms(), in.furniture());
		else
			line = SyxcraftClient.files == null ? "" : "Reading map...";
		g.text(font, line, left, 34 + 24, 0xFFA0A0A0);

		int[] f = footprint();
		if (f != null) {
			String fp = String.format("Footprint %d x %d blocks: x %d..%d, z %d..%d", f[2] - f[0] + 1, f[3] - f[1] + 1, f[0], f[2], f[1], f[3]);
			g.centeredText(font, fp, width / 2, height - 72, 0xFFE0E0A0);
		}

		SyxNet.Progress p = SyxcraftClient.progress;
		if (p != null) {
			int bx = width / 2 - 154, by = height - 26, bw = 308;
			g.fill(bx, by, bx + bw, by + 12, 0xFF202020);
			g.fill(bx + 1, by + 1, bx + 1 + (int) ((bw - 2) * p.progress()), by + 11, p.running() ? 0xFF3A9A3A : 0xFF606060);
			g.centeredText(font, p.status() + " " + Math.round(p.progress() * 100) + "%", width / 2, by + 2, 0xFFFFFFFF);
		}
		if (placeButton != null)
			placeButton.active = SyxcraftClient.files != null && !SyxcraftClient.files.entries().isEmpty()
					&& (p == null || !p.running());
	}
}
