package com.createshop;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.stream.Stream;

public final class MoneyCommands {
	private MoneyCommands() {}

	public static void register(CommandDispatcher<CommandSourceStack> d) {
		d.register(Commands.literal("money")
				// /money -> your balance (everyone)
				.executes(MoneyCommands::showBalance)

				// /money give <player> <amount>   and   /money set <player> <amount>   (ops)
				.then(Commands.literal("give")
						.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
						.then(Commands.argument("player", EntityArgument.player())
								.then(Commands.argument("amount", LongArgumentType.longArg(1))
										.executes(ctx -> change(ctx, false)))))
				.then(Commands.literal("set")
						.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
						.then(Commands.argument("player", EntityArgument.player())
								.then(Commands.argument("amount", LongArgumentType.longArg(0))
										.executes(ctx -> change(ctx, true)))))

				// /money earn ...   (ops)
				.then(Commands.literal("earn")
						.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
						.then(Commands.literal("list").executes(MoneyCommands::listRules))
						.then(Commands.literal("clear").executes(MoneyCommands::clearRules))
						.then(Commands.literal("preset")
								.then(Commands.literal("mining").executes(ctx -> preset(ctx, true)))
								.then(Commands.literal("mobs").executes(ctx -> preset(ctx, false))))
						.then(Commands.literal("mine")
								.then(Commands.argument("amount", LongArgumentType.longArg(0))
										.then(Commands.argument("target", StringArgumentType.greedyString())
												.suggests((c, b) -> SharedSuggestionProvider.suggest(targets(true), b))
												.executes(ctx -> setRule(ctx, true)))))
						.then(Commands.literal("kill")
								.then(Commands.argument("amount", LongArgumentType.longArg(0))
										.then(Commands.argument("target", StringArgumentType.greedyString())
												.suggests((c, b) -> SharedSuggestionProvider.suggest(targets(false), b))
												.executes(ctx -> setRule(ctx, false)))))
						.then(Commands.literal("time")
								.then(Commands.argument("minutes", IntegerArgumentType.integer(1))
										.then(Commands.argument("amount", LongArgumentType.longArg(0))
												.executes(MoneyCommands::setTime))))));
	}

	private static Stream<String> targets(boolean mine) {
		Stream<String> ids;
		if (mine) {
			ids = BuiltInRegistries.BLOCK.keySet().stream().map(Object::toString);
		} else {
			ids = BuiltInRegistries.ENTITY_TYPE.keySet().stream().map(Object::toString);
		}
		return Stream.concat(Stream.of("all"), ids);
	}

	private static boolean exists(boolean mine, String id) {
		if (mine) {
			return BuiltInRegistries.BLOCK.keySet().stream().anyMatch(k -> k.toString().equals(id));
		}
		return BuiltInRegistries.ENTITY_TYPE.keySet().stream().anyMatch(k -> k.toString().equals(id));
	}

	private static int showBalance(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer player = ctx.getSource().getPlayerOrException();
		long balance = MoneyManager.balance(player.getUUID());
		ctx.getSource().sendSuccess(() -> Component.literal("You have " + MoneyManager.fmt(balance)), false);
		return 1;
	}

	private static int change(CommandContext<CommandSourceStack> ctx, boolean set) throws CommandSyntaxException {
		ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
		long amount = LongArgumentType.getLong(ctx, "amount");
		long now = set
				? MoneyManager.set(target.getUUID(), amount)
				: MoneyManager.add(target.getUUID(), amount);
		ctx.getSource().sendSuccess(() -> Component.literal(
				target.getName().getString() + " now has " + MoneyManager.fmt(now)), false);
		return 1;
	}

	private static int setRule(CommandContext<CommandSourceStack> ctx, boolean mine) {
		CommandSourceStack source = ctx.getSource();
		long amount = LongArgumentType.getLong(ctx, "amount");
		String target = StringArgumentType.getString(ctx, "target").trim();

		String key = target.equals("all") ? "all" : (target.contains(":") ? target : "minecraft:" + target);
		if (!key.equals("all") && !exists(mine, key)) {
			source.sendFailure(Component.literal("Unknown " + (mine ? "block" : "mob") + ": " + key));
			return 0;
		}

		Map<String, Long> rules = mine ? MoneyManager.MINE : MoneyManager.KILL;
		String what = (mine ? "Mining " : "Killing ") + key;
		if (amount <= 0) {
			rules.remove(key);
			source.sendSuccess(() -> Component.literal("Removed rule: " + what), false);
		} else {
			rules.put(key, amount);
			source.sendSuccess(() -> Component.literal(what + " now pays " + MoneyManager.fmt(amount)), false);
		}
		MoneyManager.markDirty();
		return 1;
	}

	private static int setTime(CommandContext<CommandSourceStack> ctx) {
		int minutes = IntegerArgumentType.getInteger(ctx, "minutes");
		long amount = LongArgumentType.getLong(ctx, "amount");
		MoneyManager.timeMinutes = minutes;
		MoneyManager.timeAmount = amount;
		MoneyManager.markDirty();
		ctx.getSource().sendSuccess(() -> Component.literal(amount <= 0
				? "Playtime pay turned off"
				: "Everyone online now gets " + MoneyManager.fmt(amount) + " every " + minutes + " minute(s)"), false);
		return 1;
	}

	private static int preset(CommandContext<CommandSourceStack> ctx, boolean mining) {
		if (mining) {
			MoneyManager.presetMining();
		} else {
			MoneyManager.presetMobs();
		}
		ctx.getSource().sendSuccess(() -> Component.literal(
				(mining ? "Mining" : "Mob") + " preset applied. Use /money earn list to see it."), false);
		return 1;
	}

	private static int clearRules(CommandContext<CommandSourceStack> ctx) {
		MoneyManager.clearRules();
		ctx.getSource().sendSuccess(() -> Component.literal("All earning rules cleared."), false);
		return 1;
	}

	private static int listRules(CommandContext<CommandSourceStack> ctx) {
		StringBuilder sb = new StringBuilder("--- Earning rules ---");
		sb.append("\nMining:");
		if (MoneyManager.MINE.isEmpty()) sb.append(" none");
		for (Map.Entry<String, Long> e : MoneyManager.MINE.entrySet()) {
			sb.append("\n  ").append(e.getKey()).append(" = ").append(MoneyManager.fmt(e.getValue()));
		}
		sb.append("\nKilling:");
		if (MoneyManager.KILL.isEmpty()) sb.append(" none");
		for (Map.Entry<String, Long> e : MoneyManager.KILL.entrySet()) {
			sb.append("\n  ").append(e.getKey()).append(" = ").append(MoneyManager.fmt(e.getValue()));
		}
		sb.append("\nPlaytime: ");
		if (MoneyManager.timeAmount > 0) {
			sb.append(MoneyManager.fmt(MoneyManager.timeAmount)).append(" every ")
					.append(MoneyManager.timeMinutes).append(" minute(s)");
		} else {
			sb.append("off");
		}
		String text = sb.toString();
		ctx.getSource().sendSuccess(() -> Component.literal(text), false);
		return 1;
	}
}
