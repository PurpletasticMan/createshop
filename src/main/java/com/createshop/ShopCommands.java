package com.createshop;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.item.ItemArgument;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

public final class ShopCommands {
	private ShopCommands() {}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext context) {
		dispatcher.register(Commands.literal("createshop")
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.then(Commands.argument("name", StringArgumentType.string())
						.then(Commands.argument("price", IntegerArgumentType.integer(1, 2304))
								.then(Commands.argument("cost", ItemArgument.item(context))
										.executes(ShopCommands::createShop)))));

		dispatcher.register(Commands.literal("removeshop")
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.then(Commands.argument("id", IntegerArgumentType.integer(1))
						.suggests((c, b) -> SharedSuggestionProvider.suggest(ShopManager.ids().stream(), b))
						.executes(ShopCommands::removeShop)));

		dispatcher.register(Commands.literal("shop")
				.executes(ShopCommands::listShops)
				.then(Commands.literal("buy")
						.then(Commands.argument("id", IntegerArgumentType.integer(1))
								.suggests((c, b) -> SharedSuggestionProvider.suggest(ShopManager.ids().stream(), b))
								.executes(ShopCommands::buy))));
	}

	private static int createShop(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		CommandSourceStack source = ctx.getSource();
		ServerPlayer player = source.getPlayerOrException();

		ItemStack held = player.getMainHandItem();
		if (held.isEmpty()) {
			source.sendFailure(Component.literal("Hold the item you want to sell in your main hand first."));
			return 0;
		}

		String name = StringArgumentType.getString(ctx, "name");
		int price = IntegerArgumentType.getInteger(ctx, "price");
		ItemStack cost = ItemArgument.getItem(ctx, "cost").createItemStack(1);

		ItemStack product = held.copy();
		product.set(DataComponents.CUSTOM_NAME, Component.literal(name));

		ShopManager.Shop shop = ShopManager.add(name, product, cost, price);
		source.sendSuccess(() -> Component.literal("Created shop #" + shop.id() + ": ")
				.append(describe(shop)), false);
		return shop.id();
	}

	private static int removeShop(CommandContext<CommandSourceStack> ctx) {
		int id = IntegerArgumentType.getInteger(ctx, "id");
		if (ShopManager.remove(id)) {
			ctx.getSource().sendSuccess(() -> Component.literal("Removed shop #" + id), false);
			return 1;
		}
		ctx.getSource().sendFailure(Component.literal("No shop with id " + id));
		return 0;
	}

	private static int listShops(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack source = ctx.getSource();
		if (ShopManager.all().isEmpty()) {
			source.sendSuccess(() -> Component.literal("The shop is empty."), false);
			return 0;
		}
		source.sendSuccess(() -> Component.literal("--- Shop ---").withStyle(ChatFormatting.GOLD), false);
		for (ShopManager.Shop shop : ShopManager.all()) {
			MutableComponent line = Component.literal("#" + shop.id() + " ").withStyle(ChatFormatting.GRAY)
					.append(describe(shop))
					.append(Component.literal(" [Buy]").withStyle(Style.EMPTY
							.withColor(ChatFormatting.GREEN)
							.withClickEvent(new ClickEvent.RunCommand("shop buy " + shop.id()))
							.withHoverEvent(new HoverEvent.ShowText(Component.literal("Click to buy")))));
			source.sendSuccess(() -> line, false);
		}
		return ShopManager.all().size();
	}

	private static int buy(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		CommandSourceStack source = ctx.getSource();
		ServerPlayer player = source.getPlayerOrException();
		int id = IntegerArgumentType.getInteger(ctx, "id");

		ShopManager.Shop shop = ShopManager.get(id);
		if (shop == null) {
			source.sendFailure(Component.literal("No shop with id " + id));
			return 0;
		}

		Item costItem = shop.cost().getItem();
		int have = count(player, costItem);
		if (have < shop.price()) {
			source.sendFailure(Component.literal("You need " + (shop.price() - have) + " more ")
					.append(shop.cost().getHoverName()));
			return 0;
		}

		take(player, costItem, shop.price());

		// Put the item in the inventory; if there is no room, drop what is left at the player's feet.
		ItemStack reward = shop.product().copy();
		player.getInventory().add(reward);
		if (!reward.isEmpty()) {
			player.drop(reward, false);
		}

		source.sendSuccess(() -> Component.literal("Bought ").append(shop.product().getHoverName())
				.append(" for " + shop.price() + " ").append(shop.cost().getHoverName()), false);
		return 1;
	}

	private static MutableComponent describe(ShopManager.Shop shop) {
		return Component.literal(shop.product().getCount() + "x ")
				.append(shop.product().getHoverName())
				.append(Component.literal(" - " + shop.price() + " ").withStyle(ChatFormatting.YELLOW))
				.append(shop.cost().getHoverName());
	}

	private static int count(ServerPlayer player, Item item) {
		Inventory inv = player.getInventory();
		int total = 0;
		for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
			ItemStack stack = inv.getItem(i);
			if (stack.is(item)) total += stack.getCount();
		}
		return total;
	}

	private static void take(ServerPlayer player, Item item, int amount) {
		Inventory inv = player.getInventory();
		for (int i = 0; i < Inventory.INVENTORY_SIZE && amount > 0; i++) {
			ItemStack stack = inv.getItem(i);
			if (!stack.is(item)) continue;
			int used = Math.min(amount, stack.getCount());
			stack.shrink(used);
			amount -= used;
			if (stack.isEmpty()) inv.setItem(i, ItemStack.EMPTY);
		}
	}
}
