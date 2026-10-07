package dev.sos2mc.syxcraft.client;

import com.mojang.blaze3d.platform.InputConstants;

import dev.sos2mc.syxcraft.Syxcraft;
import dev.sos2mc.syxcraft.net.SyxNet;
import dev.sos2mc.syxcraft.place.PlaceSettings;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.Identifier;

public class SyxcraftClient implements ClientModInitializer {

	static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(Syxcraft.MOD_ID, "main"));
	static KeyMapping openKey;

	/** remembered between openings of the screen */
	static final PlaceSettings settings = new PlaceSettings();
	static SyxNet.FileList files;
	static SyxNet.MapInfo info;
	static SyxNet.Progress progress;

	/** preview outline: x0, z0, x1, z1 (inclusive) and y, or null */
	static int[] preview;
	static boolean showPreview = true;
	private static int tick;

	@Override
	public void onInitializeClient() {
		openKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.syxcraft.open", InputConstants.Type.KEYBOARD,
				InputConstants.KEY_Y, CATEGORY));

		ClientPlayNetworking.registerGlobalReceiver(SyxNet.FileList.TYPE, (p, ctx) -> {
			files = p;
			if (ctx.client().gui.screen() instanceof SyxPlaceScreen s)
				s.onFiles();
		});
		ClientPlayNetworking.registerGlobalReceiver(SyxNet.MapInfo.TYPE, (p, ctx) -> {
			info = p;
			if (ctx.client().gui.screen() instanceof SyxPlaceScreen s)
				s.onInfo();
		});
		ClientPlayNetworking.registerGlobalReceiver(SyxNet.Progress.TYPE, (p, ctx) -> progress = p);
		ClientPlayNetworking.registerGlobalReceiver(SyxNet.OpenPlacer.TYPE, (p, ctx) -> {
			// Regenerate city: same settings, centred where the last city went (with River lineup: no new search)
			settings.origin = PlaceSettings.Origin.COORDS;
			settings.x = p.x();
			settings.z = p.z();
			settings.keepSpot = settings.river;
			ctx.client().gui.setScreen(new SyxPlaceScreen());
		});

		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			while (openKey.consumeClick())
				if (mc.player != null && mc.gui.screen() == null)
					mc.gui.setScreen(new SyxPlaceScreen());
			if (++tick % 5 == 0)
				drawPreview(mc);
		});
	}

	/** particle outline of where the city will go (every couple of blocks, near the player only) */
	private static void drawPreview(Minecraft mc) {
		int[] p = preview;
		if (!showPreview || p == null || mc.level == null || mc.player == null)
			return;
		double px = mc.player.getX(), pz = mc.player.getZ();
		int y = p[4];
		int step = 2;
		for (int x = p[0]; x <= p[2]; x += step) {
			edge(mc, x, y, p[1], px, pz);
			edge(mc, x, y, p[3], px, pz);
		}
		for (int z = p[1]; z <= p[3]; z += step) {
			edge(mc, p[0], y, z, px, pz);
			edge(mc, p[2], y, z, px, pz);
		}
	}

	private static void edge(Minecraft mc, int x, int y, int z, double px, double pz) {
		double dx = x - px, dz = z - pz;
		if (dx * dx + dz * dz > 96 * 96)
			return;
		mc.level.addParticle(ParticleTypes.END_ROD, x + 0.5, y + 1.2, z + 0.5, 0, 0.01, 0);
	}
}
