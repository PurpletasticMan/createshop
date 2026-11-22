package com.createshop;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.item.ItemArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class ShopCommands {
	private ShopCommands() {}

	private static final List<String> COLORS = List.of(
			"white", "gray", "dark_gray", "black", "red", "dark_red", "gold", "yellow",
			"green", "dark_green", "aqua", "dark_aqua", "blue", "dark_blue", "light_purple", "dark_purple");

	/** name is null when the item's own name should be used. */
	private record Pending(String name, int price, ItemStack cost, String color) {}

	private static final Map<UUID, Pending> PENDING = new HashMap<>();
	private static CommandDispatcher<CommandSourceStack> dispatcher;

	public static void register(CommandDispatcher<CommandSourceStack> d, CommandBuildContext context) {
		dispatcher = d;

		// /createshop <price> <cost> [color]              -> uses the item's own name
		// /createshop "<name>" <price> <cost> [color]     -> custom name (quotes for several words)
		d.register(Commands.literal("createshop")
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.then(Commands.argument("price", IntegerArgumentType.integer(1, 2304))
						.then(tail(context)))
				.then(Commands.argument("name", StringArgumentType.string())
						.then(Commands.argument("price", IntegerArgumentType.integer(1, 2304))
								.then(tail(context)))));

		d.register(Commands.literal("removeshop")
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.then(Commands.argument("id", IntegerArgumentType.integer(1))
						.suggests((c, b) -> SharedSuggestionProvider.suggest(ShopManager.ids().stream(), b))
						.executes(ShopCommands::removeShop)));

		d.register(Commands.literal("shop")
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.executes(ShopCommands::listShops));
	}

	private static RequiredArgumentBuilder<CommandSourceStack, ?> tail(CommandBuildContext context) {
		return Commands.argument("cost", ItemArgument.item(context))
				.executes(ctx -> createShop(ctx, "white"))
				.then(Commands.argument("color", StringArgumentType.word())
						.suggests((c, b) -> SharedSuggestionProvider.suggest(COLORS.stream(), b))
						.executes(ctx -> createShop(ctx, StringArgumentType.getString(ctx, "color"))));
	}

	private static int createShop(CommandContext<CommandSourceStack> ctx, String color) throws CommandSyntaxException {
		CommandSourceStack source = ctx.getSource();
		ServerPlayer player = source.getPlayerOrException();

		if (player.getMainHandItem().isEmpty()) {
			source.sendFailure(Component.literal("Hold the item you want to sell in your main hand first."));
			return 0;
		}
		if (!COLORS.contains(color)) {
			source.sendFailure(Component.literal("Unknown color. Options: " + String.join(", ", COLORS)));
			return 0;
		}

		String name = null;
		try {
			name = StringArgumentType.getString(ctx, "name");
		} catch (IllegalArgumentException ignored) {
			// no custom name given: the item's own name is used
		}
		int price = IntegerArgumentType.getInteger(ctx, "price");
		ItemStack cost = ItemArgument.getItem(ctx, "cost").createItemStack(1);

		PENDING.put(player.getUUID(), new Pending(name, price, cost, color));
		source.sendSuccess(() -> Component.literal(
				"Now right-click the block you want the shop on (keep holding the item)."), false);
		return 1;
	}

	private static int removeShop(CommandContext<CommandSourceStack> ctx) {
		int id = IntegerArgumentType.getInteger(ctx, "id");
		if (ShopManager.remove(id)) {
			run(ctx.getSource().withSuppressedOutput(), "kill @e[tag=createshop_" + id + "]");
			ctx.getSource().sendSuccess(() -> Component.literal("Removed shop #" + id), false);
			return 1;
		}
		ctx.getSource().sendFailure(Component.literal("No shop with id " + id));
		return 0;
	}

	private static int listShops(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack source = ctx.getSource();
		if (ShopManager.all().isEmpty()) {
			source.sendSuccess(() -> Component.literal("There are no shops yet."), false);
			return 0;
		}
		source.sendSuccess(() -> Component.literal("--- Shops ---").withStyle(ChatFormatting.GOLD), false);
		for (ShopManager.Shop shop : ShopManager.all()) {
			source.sendSuccess(() -> Component.literal("#" + shop.id() + " ")
					.append(shop.product().getHoverName())
					.append(" - " + shop.price() + " ")
					.append(shop.cost().getHoverName())
					.append(" at " + shop.x() + " " + shop.y() + " " + shop.z()), false);
		}
		return ShopManager.all().size();
	}

	// Runs for every right-click on a block (both hands, both sides).
	public static InteractionResult useBlock(Player player, Level level, InteractionHand hand, BlockHitResult hit) {
		if (!(player instanceof ServerPlayer sp) || hand != InteractionHand.MAIN_HAND) {
			return InteractionResult.PASS;
		}

		String dim = level.dimension().toString();
		BlockPos pos = hit.getBlockPos();

		Pending pending = PENDING.remove(sp.getUUID());
		if (pending != null) {
			CommandSourceStack source = sp.createCommandSourceStack();
			ItemStack held = sp.getMainHandItem();
			if (held.isEmpty()) {
				source.sendFailure(Component.literal("You need to hold the item. Run /createshop again."));
				return InteractionResult.SUCCESS;
			}
			if (ShopManager.at(dim, pos.getX(), pos.getY(), pos.getZ()) != null) {
				source.sendFailure(Component.literal("There is already a shop on that block."));
				return InteractionResult.SUCCESS;
			}

			ItemStack product = held.copy();
			String shopName;
			if (pending.name() != null) {
				product.set(DataComponents.CUSTOM_NAME, Component.literal(pending.name()));
				shopName = pending.name();
			} else if (product.has(DataComponents.CUSTOM_NAME)) {
				shopName = product.getHoverName().getString();
			} else {
				shopName = ""; // use the item's own (translated) name
			}

			ShopManager.Shop shop = ShopManager.add(shopName, product, pending.cost(), pending.price(),
					dim, pos.getX(), pos.getY(), pos.getZ(), pending.color());
			spawnHologram(sp, shop);
			source.sendSuccess(() -> Component.literal("Created shop #" + shop.id()), false);
			return InteractionResult.SUCCESS;
		}

		ShopManager.Shop shop = ShopManager.at(dim, pos.getX(), pos.getY(), pos.getZ());
		if (shop != null) {
			buy(sp, shop);
			return InteractionResult.SUCCESS;
		}
		return InteractionResult.PASS;
	}

	private static void buy(ServerPlayer player, ShopManager.Shop shop) {
		Item costItem = shop.cost().getItem();
		int price = shop.price();
		Inventory inv = player.getInventory();

		int have = count(player, costItem);
		if (have < price) {
			actionBar(player, "[{text:\"You need " + (price - have) + " more \",color:\"red\"},"
					+ nameJson(shop.cost(), "red", false) + "]");
			return;
		}
		if (inv.getFreeSlot() == -1) {
			actionBar(player, "{text:\"Your inventory is full\",color:\"red\"}");
			return;
		}

		take(player, costItem, price);
		if (have - count(player, costItem) != price) {
			CreateShopMod.LOGGER.error("Shop #{}: payment of {} could not be taken", shop.id(), price);
			actionBar(player, "{text:\"Purchase failed - nothing was taken\",color:\"red\"}");
			return;
		}

		inv.setItem(inv.getFreeSlot(), shop.product().copy());

		actionBar(player, "[{text:\"Bought \",color:\"green\"}," + nameJson(shop.product(), "green", false)
				+ ",{text:\" for " + price + " \",color:\"green\"}," + nameJson(shop.cost(), "green", false) + "]");
	}

	// ---------------------------------------------------------------- hologram

	private static void spawnHologram(ServerPlayer player, ShopManager.Shop shop) {
		CommandSourceStack src = player.createCommandSourceStack().withSuppressedOutput();
		String tags = "Tags:[\"createshop\",\"createshop_" + shop.id() + "\"]";
		String c = shop.color();
		double x = shop.x() + 0.5;
		double z = shop.z() + 0.5;
		double y = shop.y();

		// 1) the item being sold (copied exactly from your hand, so modded items work)
		run(src, "summon minecraft:item_display " + pos(x, y + 1.5, z) + " {" + tags
				+ ",billboard:\"vertical\",item_display:\"ground\"," + transform(0, 0, 0.25) + "}");
		run(src, "item replace entity @e[type=minecraft:item_display,tag=createshop_" + shop.id()
				+ ",limit=1] contents from entity @s weapon.mainhand");

		// 2) the name (bold)
		String name = shop.name().isEmpty()
				? nameJson(shop.product(), c, true)
				: "{text:\"" + esc(shop.name()) + "\",bold:true,color:\"" + c + "\"}";
		if (shop.product().getCount() > 1) {
			name = "[" + name + ",{text:\" x" + shop.product().getCount() + "\",color:\"" + c + "\"}]";
		}
		run(src, "summon minecraft:text_display " + pos(x, y + 2.0, z) + " {" + tags
				+ ",billboard:\"center\",text:" + name + "}");

		// 3) the price row: "10 x" followed by a small icon of the cost item
		int digits = String.valueOf(shop.price()).length();
		double textW = 0.025 * (6 * digits + 10);
		double iconW = 0.4;
		double gap = 0.05;
		double total = textW + gap + iconW;
		double textX = -total / 2 + textW / 2;
		double iconX = total / 2 - iconW / 2;
		double rowY = y + 1.72;
		String costId = BuiltInRegistries.ITEM.getKey(shop.cost().getItem()).toString();

		run(src, "summon minecraft:text_display " + pos(x, rowY, z) + " {" + tags
				+ ",billboard:\"center\"," + transform(textX, 0, 1.0)
				+ ",text:{text:\"" + shop.price() + " x\",color:\"" + c + "\"}}");
		run(src, "summon minecraft:item_display " + pos(x, rowY, z) + " {" + tags
				+ ",billboard:\"center\",item_display:\"gui\",item:{id:\"" + costId + "\",count:1},"
				+ transform(iconX, 0.12, iconW) + "}");
	}

	private static String transform(double tx, double ty, double scale) {
		return "transformation:{left_translation:[" + f(tx) + "," + f(ty) + ",0f],right_translation:[0f,0f,0f],"
				+ "left_rotation:[0f,0f,0f,1f],right_rotation:[0f,0f,0f,1f],scale:["
				+ f(scale) + "," + f(scale) + "," + f(scale) + "]}";
	}

	private static String f(double v) {
		return String.format(Locale.ROOT, "%.3f", v) + "f";
	}

	private static String pos(double x, double y, double z) {
		return String.format(Locale.ROOT, "%.2f %.2f %.2f", x, y, z);
	}

	// ---------------------------------------------------------------- helpers

	/** Item name as a text component: custom names as text, everything else as a translation (works for modded items). */
	private static String nameJson(ItemStack stack, String color, boolean bold) {
		String extra = ",color:\"" + color + "\"" + (bold ? ",bold:true" : "");
		if (stack.has(DataComponents.CUSTOM_NAME)) {
			return "{text:\"" + esc(stack.getHoverName().getString()) + "\"" + extra + "}";
		}
		return "{translate:\"" + stack.getItem().getDescriptionId() + "\"" + extra + "}";
	}

	private static void actionBar(ServerPlayer player, String componentSnbt) {
		CommandSourceStack serverSource = player.createCommandSourceStack().getServer()
				.createCommandSourceStack().withSuppressedOutput();
		run(serverSource, "title " + player.getUUID() + " actionbar " + componentSnbt);
	}

	private static String esc(String s) {
		return s.replace("\\", "\\\\").replace("\"", "\\\"");
	}

	private static void run(CommandSourceStack src, String command) {
		try {
			dispatcher.execute(command, src);
		} catch (CommandSyntaxException e) {
			CreateShopMod.LOGGER.debug("Command failed: {} ({})", command, e.getMessage());
		}
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
