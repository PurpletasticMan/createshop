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
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
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

	private record Pending(int price, ItemStack cost, String color, boolean money) {}

	private static final Map<UUID, Pending> PENDING = new HashMap<>();
	private static final Map<UUID, Long> LAST_CLICK = new HashMap<>();
	private static final Map<UUID, Long> CREATED_AT = new HashMap<>();
	private static CommandDispatcher<CommandSourceStack> dispatcher;
	private static long spinTicks = 0;

	public static void register(CommandDispatcher<CommandSourceStack> d, CommandBuildContext context) {
		dispatcher = d;

		// /createshop <price> <cost item> [color]
		// /createshop <price> money [color]
		d.register(Commands.literal("createshop")
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.then(Commands.argument("price", IntegerArgumentType.integer(1))
						.then(Commands.literal("money")
								.executes(ctx -> createShop(ctx, "white", true))
								.then(colorArg(true)))
						.then(Commands.argument("cost", ItemArgument.item(context))
								.executes(ctx -> createShop(ctx, "white", false))
								.then(colorArg(false)))));

		d.register(Commands.literal("removeshop")
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.then(Commands.argument("id", IntegerArgumentType.integer(1))
						.suggests((c, b) -> SharedSuggestionProvider.suggest(ShopManager.ids().stream(), b))
						.executes(ShopCommands::removeShop)));

		// /setshop  (ops): remember where you are standing as the shop spot
		d.register(Commands.literal("setshop")
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.executes(ShopCommands::setShopSpot));

		// /shop  (everyone): teleport to the shop spot
		d.register(Commands.literal("shop")
				.executes(ShopCommands::goToShop));

		// /shops list  (ops): list every shop
		d.register(Commands.literal("shops")
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.then(Commands.literal("list")
						.executes(ShopCommands::listShops)));

		MoneyCommands.register(d);
	}

	private static RequiredArgumentBuilder<CommandSourceStack, String> colorArg(boolean money) {
		return Commands.argument("color", StringArgumentType.word())
				.suggests((c, b) -> SharedSuggestionProvider.suggest(COLORS.stream(), b))
				.executes(ctx -> createShop(ctx, StringArgumentType.getString(ctx, "color"), money));
	}

	// ---------------------------------------------------------------- shop spot (/setshop and /shop)

	private static int setShopSpot(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		CommandSourceStack source = ctx.getSource();
		ServerPlayer player = source.getPlayerOrException();

		// the dimension's name looks like "ResourceKey[minecraft:dimension / minecraft:overworld]"
		String raw = player.level().dimension().toString();
		int slash = raw.indexOf("/ ");
		String dimId = slash >= 0 ? raw.substring(slash + 2).replace("]", "").trim() : raw;

		WarpManager.set(source.getServer(), new WarpManager.Warp(
				dimId, player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot()));
		source.sendSuccess(() -> Component.literal("Shop spot set. Everyone can now use /shop to come here."), false);
		return 1;
	}

	private static int goToShop(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		CommandSourceStack source = ctx.getSource();
		ServerPlayer player = source.getPlayerOrException();

		WarpManager.Warp warp = WarpManager.get(source.getServer());
		if (warp == null) {
			source.sendFailure(Component.literal("The shop spot has not been set yet. Ask an op to run /setshop."));
			return 0;
		}

		String coords = String.format(Locale.ROOT, "%.3f %.3f %.3f %.2f %.2f",
				warp.x(), warp.y(), warp.z(), warp.yaw(), warp.pitch());
		run(source.getServer().createCommandSourceStack().withSuppressedOutput(),
				"execute in " + warp.dimId() + " run tp " + player.getUUID() + " " + coords);
		source.sendSuccess(() -> Component.literal("Teleported to the shop."), false);
		return 1;
	}

	// ---------------------------------------------------------------- creating and removing shops

	private static int createShop(CommandContext<CommandSourceStack> ctx, String color, boolean money) throws CommandSyntaxException {
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

		int price = IntegerArgumentType.getInteger(ctx, "price");
		if (!money && price > 2304) {
			source.sendFailure(Component.literal("Item prices can be at most 2304."));
			return 0;
		}
		ItemStack cost = money
				? new ItemStack(Items.GOLD_INGOT)
				: ItemArgument.getItem(ctx, "cost").createItemStack(1);

		PENDING.put(player.getUUID(), new Pending(price, cost, color, money));
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
			source.sendSuccess(() -> {
				Component price = shop.money()
						? Component.literal(MoneyManager.fmt(shop.price()))
						: Component.literal(shop.price() + " ").append(shop.cost().getHoverName());
				return Component.literal("#" + shop.id() + " ")
						.append(shop.product().getHoverName())
						.append(" - ")
						.append(price)
						.append(" at " + shop.x() + " " + shop.y() + " " + shop.z());
			}, false);
		}
		return ShopManager.all().size();
	}

	// ---------------------------------------------------------------- clicks

	/** Right-click on a block: only used to place a new shop (after /createshop). */
	public static InteractionResult useBlock(Player player, Level level, InteractionHand hand, BlockHitResult hit) {
		if (!(player instanceof ServerPlayer sp) || hand != InteractionHand.MAIN_HAND) {
			return InteractionResult.PASS;
		}

		Pending pending = PENDING.remove(sp.getUUID());
		if (pending == null) return InteractionResult.PASS;

		// This click only places the shop. Stop the game from also placing the held block
		// or using the item, and put the player's inventory back in sync.
		CREATED_AT.put(sp.getUUID(), spinTicks);
		placeShop(sp, level, hit.getBlockPos(), pending);
		sp.containerMenu.sendAllDataToRemote();
		return InteractionResult.SUCCESS;
	}

	private static void placeShop(ServerPlayer sp, Level level, BlockPos pos, Pending pending) {
		CommandSourceStack source = sp.createCommandSourceStack();
		String dim = level.dimension().toString();

		ItemStack held = sp.getMainHandItem();
		if (held.isEmpty()) {
			source.sendFailure(Component.literal("You need to hold the item. Run /createshop again."));
			return;
		}
		if (ShopManager.at(dim, pos.getX(), pos.getY(), pos.getZ()) != null) {
			source.sendFailure(Component.literal("There is already a shop on that block."));
			return;
		}

		// The shop uses the item exactly as you hold it (rename it on an anvil for a custom name).
		ItemStack product = held.copy();
		String shopName = product.has(DataComponents.CUSTOM_NAME)
				? product.getHoverName().getString()
				: ""; // empty = use the item's own (translated) name

		ShopManager.Shop shop = ShopManager.add(shopName, product, pending.cost(), pending.price(),
				dim, pos.getX(), pos.getY(), pos.getZ(), pending.color(), pending.money());
		spawnHologram(sp, shop);
		source.sendSuccess(() -> Component.literal("Created shop #" + shop.id()), false);
	}

	/** Right-click on the shop's invisible box (where the item floats): buy. */
	public static InteractionResult useEntity(Player player, Level level, InteractionHand hand, Entity entity) {
		if (!(player instanceof ServerPlayer sp)) return InteractionResult.PASS;
		if (!BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString().equals("minecraft:interaction")) {
			return InteractionResult.PASS;
		}

		// only boxes that sit right above one of our shops count
		BlockPos p = entity.blockPosition();
		ShopManager.Shop shop = ShopManager.at(level.dimension().toString(), p.getX(), p.getY() - 1, p.getZ());
		if (shop == null) return InteractionResult.PASS;

		// whatever happens, this click belongs to the shop: nothing else may use it
		if (hand != InteractionHand.MAIN_HAND) return InteractionResult.SUCCESS;

		// the game sends two packets for one click, so ignore a second one right after the first
		long last = LAST_CLICK.getOrDefault(sp.getUUID(), -100L);
		if (spinTicks - last < 4) return InteractionResult.SUCCESS;
		LAST_CLICK.put(sp.getUUID(), spinTicks);

		buy(sp, shop);
		return InteractionResult.SUCCESS;
	}

	/** Blocks item use (bows, shields, sword abilities...) for a moment right after placing a shop. */
	public static InteractionResult useItem(Player player, Level level, InteractionHand hand) {
		if (player instanceof ServerPlayer sp && spinTicks - CREATED_AT.getOrDefault(sp.getUUID(), -100L) < 6) {
			sp.containerMenu.sendAllDataToRemote();
			return InteractionResult.FAIL;
		}
		return InteractionResult.PASS;
	}

	private static void buy(ServerPlayer player, ShopManager.Shop shop) {
		int price = shop.price();
		Inventory inv = player.getInventory();

		// ---- paid with money
		if (shop.money()) {
			long balance = MoneyManager.balance(player.getUUID());
			if (balance < price) {
				actionBar(player, "{text:\"You need " + MoneyManager.fmt(price - balance) + " more\",color:\"red\"}");
				return;
			}
			if (inv.getFreeSlot() == -1) {
				actionBar(player, "{text:\"Your inventory is full\",color:\"red\"}");
				return;
			}
			MoneyManager.add(player.getUUID(), -price);
			// always an empty slot, so the item never merges into an existing stack
			inv.setItem(inv.getFreeSlot(), shop.product().copy());
			actionBar(player, "[{text:\"Bought \",color:\"green\"}," + nameJson(shop.product(), "green", false)
					+ ",{text:\" for " + MoneyManager.fmt(price) + "\",color:\"green\"}]");
			return;
		}

		// ---- paid with items
		Item costItem = shop.cost().getItem();
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

		// always an empty slot, so the item never merges into an existing stack
		inv.setItem(inv.getFreeSlot(), shop.product().copy());

		actionBar(player, "[{text:\"Bought \",color:\"green\"}," + nameJson(shop.product(), "green", false)
				+ ",{text:\" for " + price + " \",color:\"green\"}," + nameJson(shop.cost(), "green", false) + "]");
	}

	// ---------------------------------------------------------------- hologram

	private static void spawnHologram(ServerPlayer player, ShopManager.Shop shop) {
		CommandSourceStack src = player.createCommandSourceStack().withSuppressedOutput();
		String tags = "Tags:[\"createshop\",\"createshop_" + shop.id() + "\"";
		String c = shop.color();
		double x = shop.x() + 0.5;
		double z = shop.z() + 0.5;
		double y = shop.y();

		// 1) the clickable area: an invisible 1x1x1 box in the space above the clicked block.
		//    "response" makes the game treat a click on it as used, so held blocks and weapons do nothing.
		run(src, "summon minecraft:interaction " + pos(x, y + 1.0, z) + " {" + tags
				+ ",\"createshop_click\"],width:1.0f,height:1.0f,response:1b}");

		// 2) the item, floating in the middle of that box (copied exactly from your hand, so modded items work).
		//    The extra "createshop_spin" tag makes it spin and bob like a dropped item.
		run(src, "summon minecraft:item_display " + pos(x, y + 1.5, z) + " {" + tags
				+ ",\"createshop_spin\"],item_display:\"ground\",interpolation_duration:10," + transform(1.3) + "}");
		run(src, "item replace entity @e[type=minecraft:item_display,tag=createshop_" + shop.id()
				+ ",limit=1] contents from entity @s weapon.mainhand");

		// 3) the text above the box: the name line uses the shop color and is bold; the price line is always white
		String name = nameJson(shop.product(), c, true);
		if (shop.product().getCount() > 1) {
			name = "[" + name + ",{text:\" x" + shop.product().getCount() + "\",color:\"" + c + "\"}]";
		}
		String priceLine = shop.money()
				? "{text:\"" + MoneyManager.fmt(shop.price()) + "\",color:\"white\"}"
				: "{text:\"" + shop.price() + " x \",color:\"white\"}," + nameJson(shop.cost(), "white", false);
		String text = "[" + name + ",{text:\"\\n\"}," + priceLine + "]";
		run(src, "summon minecraft:text_display " + pos(x, y + 2.1, z) + " {" + tags
				+ "],billboard:\"center\",text:" + text + "}");
	}

	// Called every server tick: every 10 ticks, turn the floating items a little and bob them up and down.
	public static void spinTick(MinecraftServer server) {
		spinTicks++;
		if (spinTicks % 10 != 0 || dispatcher == null || ShopManager.all().isEmpty()) return;

		double angle = spinTicks / 20.0;                  // about 1 radian per second, like a dropped item
		double bob = 0.1 * Math.sin(spinTicks / 10.0);    // gentle up and down
		String merge = "{start_interpolation:0,interpolation_duration:10,transformation:{"
				+ "left_translation:[0f," + f(bob) + ",0f],right_translation:[0f,0f,0f],"
				+ "left_rotation:[0f," + f(Math.sin(angle / 2)) + ",0f," + f(Math.cos(angle / 2)) + "],"
				+ "right_rotation:[0f,0f,0f,1f],scale:[1.3f,1.3f,1.3f]}}";
		run(server.createCommandSourceStack().withSuppressedOutput(),
				"execute as @e[type=minecraft:item_display,tag=createshop_spin] run data merge entity @s " + merge);
	}

	private static String transform(double scale) {
		String s = f(scale);
		return "transformation:{left_translation:[0f,0f,0f],right_translation:[0f,0f,0f],"
				+ "left_rotation:[0f,0f,0f,1f],right_rotation:[0f,0f,0f,1f],scale:[" + s + "," + s + "," + s + "]}";
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

	/** Shop message on the action bar. */
	public static void actionBar(ServerPlayer player, String componentSnbt) {
		MoneyManager.holdHud(player.getUUID());
		rawActionBar(player, componentSnbt);
	}

	public static void rawActionBar(ServerPlayer player, String componentSnbt) {
		CommandSourceStack serverSource = player.createCommandSourceStack().getServer()
				.createCommandSourceStack().withSuppressedOutput();
		run(serverSource, "title " + player.getUUID() + " actionbar " + componentSnbt);
	}

	private static String esc(String s) {
		return s.replace("\\", "\\\\").replace("\"", "\\\"");
	}

	public static void run(CommandSourceStack src, String command) {
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
