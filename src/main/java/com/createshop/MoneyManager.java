package com.createshop;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/** Player balances plus the rules that decide how money is earned. Saved to <world>/createshop_money.json */
public final class MoneyManager {
	private static final Map<UUID, Long> BALANCES = new HashMap<>();
	private static final Map<UUID, Long> HUD_HOLD = new HashMap<>();

	/** block id -> money for breaking it. The key "all" is used for every block that has no rule of its own. */
	public static final Map<String, Long> MINE = new TreeMap<>();
	/** entity id -> money for killing it. The key "all" is used for every mob that has no rule of its own. */
	public static final Map<String, Long> KILL = new TreeMap<>();

	public static long timeMinutes = 0;
	public static long timeAmount = 0;

	private static Path file;
	private static boolean dirty;
	private static long ticks;

	private MoneyManager() {}

	// ---------------------------------------------------------------- balances

	public static long balance(UUID id) {
		return BALANCES.getOrDefault(id, 0L);
	}

	public static long add(UUID id, long amount) {
		long now = Math.max(0, balance(id) + amount);
		BALANCES.put(id, now);
		dirty = true;
		return now;
	}

	public static long set(UUID id, long amount) {
		long now = Math.max(0, amount);
		BALANCES.put(id, now);
		dirty = true;
		return now;
	}

	public static String fmt(long value) {
		return "$" + String.format(Locale.ROOT, "%,d", value);
	}

	// ---------------------------------------------------------------- earning

	public static void earnMine(ServerPlayer player, String blockId) {
		reward(player, MINE, blockId);
	}

	public static void earnKill(ServerPlayer player, String entityId) {
		reward(player, KILL, entityId);
	}

	private static void reward(ServerPlayer player, Map<String, Long> rules, String id) {
		Long value = rules.get(id);
		if (value == null) value = rules.get("all");
		if (value == null || value <= 0) return;
		add(player.getUUID(), value);
	}

	public static void markDirty() {
		dirty = true;
	}

	public static void clearRules() {
		MINE.clear();
		KILL.clear();
		timeMinutes = 0;
		timeAmount = 0;
		dirty = true;
	}

	/** $1 for every block, more for rarer ores. */
	public static void presetMining() {
		MINE.put("all", 1L);
		mine("coal_ore", 2);
		mine("deepslate_coal_ore", 2);
		mine("copper_ore", 3);
		mine("deepslate_copper_ore", 3);
		mine("iron_ore", 5);
		mine("deepslate_iron_ore", 5);
		mine("nether_gold_ore", 5);
		mine("nether_quartz_ore", 5);
		mine("lapis_ore", 8);
		mine("deepslate_lapis_ore", 8);
		mine("redstone_ore", 8);
		mine("deepslate_redstone_ore", 8);
		mine("gold_ore", 15);
		mine("deepslate_gold_ore", 15);
		mine("diamond_ore", 50);
		mine("deepslate_diamond_ore", 50);
		mine("emerald_ore", 75);
		mine("deepslate_emerald_ore", 75);
		mine("ancient_debris", 200);
		dirty = true;
	}

	/** $2 for every mob, more for tougher ones. */
	public static void presetMobs() {
		KILL.put("all", 2L);
		kill("zombie", 3);
		kill("skeleton", 3);
		kill("spider", 3);
		kill("creeper", 5);
		kill("blaze", 8);
		kill("enderman", 10);
		kill("witch", 10);
		kill("ghast", 15);
		kill("wither_skeleton", 15);
		kill("piglin_brute", 20);
		kill("evoker", 25);
		kill("ravager", 40);
		kill("elder_guardian", 200);
		kill("warden", 500);
		kill("wither", 1000);
		kill("ender_dragon", 1000);
		dirty = true;
	}

	private static void mine(String block, long value) {
		MINE.put("minecraft:" + block, value);
	}

	private static void kill(String mob, long value) {
		KILL.put("minecraft:" + mob, value);
	}

	// ---------------------------------------------------------------- money display (action bar)

	/** Called after a shop message so the money display does not cover it straight away. */
	public static void holdHud(UUID id) {
		HUD_HOLD.put(id, ticks + 60);
	}

	private static boolean isActive() {
		return !MINE.isEmpty() || !KILL.isEmpty() || timeAmount > 0 || ShopManager.hasMoneyShops();
	}

	public static void tick(MinecraftServer server) {
		ticks++;

		if (dirty && ticks % 600 == 0) save();

		// pay everyone for time played
		if (timeMinutes > 0 && timeAmount > 0 && ticks % (timeMinutes * 1200) == 0) {
			for (ServerPlayer p : server.getPlayerList().getPlayers()) {
				add(p.getUUID(), timeAmount);
			}
		}

		// show the balance once a second
		if (ticks % 20 == 0 && isActive()) {
			for (ServerPlayer p : server.getPlayerList().getPlayers()) {
				Long hold = HUD_HOLD.get(p.getUUID());
				if (hold != null && ticks < hold) continue;
				ShopCommands.rawActionBar(p, "{text:\"" + fmt(balance(p.getUUID())) + "\",color:\"green\"}");
			}
		}
	}

	// ---------------------------------------------------------------- saving

	public static void load(MinecraftServer server) {
		file = server.getWorldPath(LevelResource.ROOT).resolve("createshop_money.json");
		BALANCES.clear();
		MINE.clear();
		KILL.clear();
		timeMinutes = 0;
		timeAmount = 0;
		dirty = false;
		if (!Files.exists(file)) return;

		try {
			JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
			if (root.has("balances")) {
				for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("balances").entrySet()) {
					BALANCES.put(UUID.fromString(e.getKey()), e.getValue().getAsLong());
				}
			}
			readRules(root, "mine", MINE);
			readRules(root, "kill", KILL);
			if (root.has("timeMinutes")) timeMinutes = root.get("timeMinutes").getAsLong();
			if (root.has("timeAmount")) timeAmount = root.get("timeAmount").getAsLong();
		} catch (Exception e) {
			CreateShopMod.LOGGER.error("Could not read {}", file, e);
		}
	}

	private static void readRules(JsonObject root, String key, Map<String, Long> out) {
		if (!root.has(key)) return;
		for (Map.Entry<String, JsonElement> e : root.getAsJsonObject(key).entrySet()) {
			out.put(e.getKey(), e.getValue().getAsLong());
		}
	}

	private static JsonObject rulesToJson(Map<String, Long> rules) {
		JsonObject o = new JsonObject();
		for (Map.Entry<String, Long> e : rules.entrySet()) {
			o.addProperty(e.getKey(), e.getValue());
		}
		return o;
	}

	public static void save() {
		if (file == null) return;
		JsonObject root = new JsonObject();
		JsonObject balances = new JsonObject();
		for (Map.Entry<UUID, Long> e : BALANCES.entrySet()) {
			balances.addProperty(e.getKey().toString(), e.getValue());
		}
		root.add("balances", balances);
		root.add("mine", rulesToJson(MINE));
		root.add("kill", rulesToJson(KILL));
		root.addProperty("timeMinutes", timeMinutes);
		root.addProperty("timeAmount", timeAmount);
		try {
			Files.writeString(file, root.toString());
			dirty = false;
		} catch (IOException e) {
			CreateShopMod.LOGGER.error("Could not save {}", file, e);
		}
	}
}
