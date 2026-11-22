package com.createshop;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CreateShopMod implements ModInitializer {
	public static final String MOD_ID = "createshop";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		ServerLifecycleEvents.SERVER_STARTED.register(ShopManager::load);
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> ShopManager.save());
		CommandRegistrationCallback.EVENT.register((dispatcher, buildContext, environment) ->
				ShopCommands.register(dispatcher, buildContext));
		UseBlockCallback.EVENT.register(ShopCommands::useBlock);
		LOGGER.info("CreateShop loaded");
	}
}
