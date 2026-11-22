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
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
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
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class ShopCommands {
	private ShopCommands() {}

	private record Pending(String name, int price, ItemStack cost) {}

	private static final Map<UUID, Pending> PENDING = new HashMap<>();
	private static CommandDispatcher<CommandSourceStack> dispatcher;

	public static void register(CommandDispatcher<CommandSourceStack> d, CommandBuildContext context) {
		dispatcher = d;

		d.register(Commands.literal("createshop")
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.then(Commands.argument("name", StringArgumentType.string())
						.then(Commands.argument("price", IntegerArgumentType.integer(1, 2304))
								.then(Commands.argument("cost", ItemArgument.item(context))
										.executes(ShopCommands::createShop)))));

		d.register(Commands.literal("removeshop")
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.then(Commands.argument("id", IntegerArgumentType.integer(1))
						.suggests((c, b) -> SharedSuggestionProvider.suggest(ShopManager.ids().stream(), b))
						.executes(ShopCommands::removeShop)));

		d.register(Commands.literal("shop")
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.executes(ShopCommands::listShops));
	}

	// /createshop "name" price cost  -> then right-click the block you want the shop on
	private static int createShop(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		CommandSourceStack source = ctx.getSource();
		ServerPlayer player = source.getPlayerOrException();

		if (player.getMainHandItem().isEmpty()) {
			source.sendFailure(Component.literal("Hold the item you want to sell in your main hand first."));
			return 0;
		}

		String name = StringArgumentType.getString(ctx, "name");
		int price = IntegerArgumentType.getInteger(ctx, "price");
		ItemStack cost = ItemArgument.getItem(ctx, "cost").createItemStack(1);

		PENDING.put(player.getUUID(), new Pending(name, price, cost));
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
			source.sendSuccess(() -> Component.literal("#" + shop.id() + " " + shop.name() + " - "
					+ shop.price() + " ").append(shop.cost().getHoverName())
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
			product.set(DataComponents.CUSTOM_NAME, Component.literal(pending.name()));

			ShopManager.Shop shop = ShopManager.add(pending.name(), product, pending.cost(), pending.price(),
					dim, pos.getX(), pos.getY(), pos.getZ());
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
		CommandSourceStack source = player.createCommandSourceStack();
		Item costItem = shop.cost().getItem();

		int have = count(player, costItem);
		if (have < shop.price()) {
			source.sendFailure(Component.literal("You need " + (shop.price() - have) + " more ")
					.append(shop.cost().getHoverName()));
			return;
		}

		Inventory inv = player.getInventory();
		if (inv.getFreeSlot() == -1) {
			source.sendFailure(Component.literal("Your inventory is full."));
			return;
		}

		take(player, costItem, shop.price());
		inv.setItem(inv.getFreeSlot(), shop.product().copy());

		source.sendSuccess(() -> Component.literal("Bought ").append(shop.product().getHoverName())
				.append(" for " + shop.price() + " ").append(shop.cost().getHoverName()), false);
	}

	// Floating item + text above the block, made with vanilla display entities.
	private static void spawnHologram(ServerPlayer player, ShopManager.Shop shop) {
		CommandSourceStack src = player.createCommandSourceStack().withSuppressedOutput();
		String tags = "Tags:[\"createshop\",\"createshop_" + shop.id() + "\"]";

		String itemPos = String.format(Locale.ROOT, "%.2f %.2f %.2f", shop.x() + 0.5, shop.y() + 1.35, shop.z() + 0.5);
		String textPos = String.format(Locale.ROOT, "%.2f %.2f %.2f", shop.x() + 0.5, shop.y() + 1.95, shop.z() + 0.5);

		run(src, "summon minecraft:item_display " + itemPos + " {" + tags
				+ ",billboard:\"vertical\",item_display:\"ground\""
				+ ",transformation:{left_translation:[0f,0f,0f],right_translation:[0f,0f,0f]"
				+ ",left_rotation:[0f,0f,0f,1f],right_rotation:[0f,0f,0f,1f],scale:[1.3f,1.3f,1.3f]}}");
		run(src, "item replace entity @e[type=minecraft:item_display,tag=createshop_" + shop.id()
				+ ",limit=1] contents from entity @s weapon.mainhand");

		StringBuilder text = new StringBuilder("[");
		text.append("{text:\"").append(esc(shop.name())).append("\",color:\"gold\",bold:true},");
		text.append("{text:\"\\n\"},");
		text.append("{text:\"").append(shop.price()).append(" x \",color:\"yellow\"},");
		text.append("{translate:\"").append(shop.cost().getItem().getDescriptionId()).append("\",color:\"yellow\"}");
		if (shop.product().getCount() > 1) {
			text.append(",{text:\"  (gives ").append(shop.product().getCount()).append(")\",color:\"gray\"}");
		}
		text.append("]");

		run(src, "summon minecraft:text_display " + textPos + " {" + tags
				+ ",billboard:\"center\",text:" + text + "}");
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
