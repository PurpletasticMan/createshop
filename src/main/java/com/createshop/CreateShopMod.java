package com.createshop;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CreateShopMod implements ModInitializer {
	public static final String MOD_ID = "createshop";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			ShopManager.load(server);
			MoneyManager.load(server);
		});
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			ShopManager.save();
			MoneyManager.save();
		});
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			ShopCommands.spinTick(server);
			MoneyManager.tick(server);
		});
		CommandRegistrationCallback.EVENT.register((dispatcher, buildContext, environment) ->
				ShopCommands.register(dispatcher, buildContext));
		UseBlockCallback.EVENT.register(ShopCommands::useBlock);

		// Money from mining
		PlayerBlockBreakEvents.AFTER.register((level, player, pos, state, blockEntity) -> {
			if (player instanceof ServerPlayer sp && !sp.isCreative()) {
				MoneyManager.earnMine(sp, BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
			}
		});

		// Money from killing mobs
		ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
			if (source.getEntity() instanceof ServerPlayer sp && !(entity instanceof Player)) {
				MoneyManager.earnKill(sp, BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString());
			}
		});

		LOGGER.info("CreateShop loaded");
	}
}
