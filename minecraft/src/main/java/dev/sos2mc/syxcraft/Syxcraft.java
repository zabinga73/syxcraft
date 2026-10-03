package dev.sos2mc.syxcraft;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Syxcraft implements ModInitializer {
	public static final String MOD_ID = "syxcraft";
	public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		SyxServer.init();
		dev.sos2mc.syxcraft.place.Seats.register();
		LOG.info("Syxcraft loaded");
	}
}
