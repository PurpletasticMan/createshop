package com.createshop;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public final class ShopManager {
	/** money = true means the price is paid with money, not items (cost is then just a placeholder). */
	public record Shop(int id, String name, ItemStack product, ItemStack cost, int price,
					   String dim, int x, int y, int z, String color, boolean money) {}

	private static final Map<Integer, Shop> SHOPS = new TreeMap<>();
	private static int nextId = 1;
	private static MinecraftServer server;
	private static Path file;

	private ShopManager() {}

	public static Shop add(String name, ItemStack product, ItemStack cost, int price,
						   String dim, int x, int y, int z, String color, boolean money) {
		Shop shop = new Shop(nextId++, name, product, cost, price, dim, x, y, z, color, money);
		SHOPS.put(shop.id(), shop);
		save();
		return shop;
	}

	public static boolean remove(int id) {
		boolean removed = SHOPS.remove(id) != null;
		if (removed) save();
		return removed;
	}

	public static Shop get(int id) {
		return SHOPS.get(id);
	}

	public static Shop at(String dim, int x, int y, int z) {
		for (Shop s : SHOPS.values()) {
			if (s.dim().equals(dim) && s.x() == x && s.y() == y && s.z() == z) return s;
		}
		return null;
	}

	public static boolean hasMoneyShops() {
		for (Shop s : SHOPS.values()) {
			if (s.money()) return true;
		}
		return false;
	}

	public static Collection<Shop> all() {
		return SHOPS.values();
	}

	public static List<String> ids() {
		List<String> out = new ArrayList<>();
		for (int id : SHOPS.keySet()) out.add(String.valueOf(id));
		return out;
	}

	public static void load(MinecraftServer s) {
		server = s;
		file = s.getWorldPath(LevelResource.ROOT).resolve("createshop.json");
		SHOPS.clear();
		nextId = 1;
		if (!Files.exists(file)) return;

		RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, s.registryAccess());
		try {
			JsonArray array = JsonParser.parseString(Files.readString(file)).getAsJsonArray();
			for (JsonElement element : array) {
				try {
					JsonObject o = element.getAsJsonObject();
					int id = o.get("id").getAsInt();
					Shop shop = new Shop(
							id,
							o.get("name").getAsString(),
							ItemStack.CODEC.parse(ops, o.get("product")).getOrThrow(),
							ItemStack.CODEC.parse(ops, o.get("cost")).getOrThrow(),
							o.get("price").getAsInt(),
							o.get("dim").getAsString(),
							o.get("x").getAsInt(),
							o.get("y").getAsInt(),
							o.get("z").getAsInt(),
							o.has("color") ? o.get("color").getAsString() : "white",
							o.has("money") && o.get("money").getAsBoolean());
					SHOPS.put(id, shop);
					nextId = Math.max(nextId, id + 1);
				} catch (Exception e) {
					CreateShopMod.LOGGER.warn("Skipping a shop that could not be loaded (item from a removed mod?): {}", e.toString());
				}
			}
		} catch (Exception e) {
			CreateShopMod.LOGGER.error("Could not read {}", file, e);
		}
	}

	public static void save() {
		if (server == null || file == null) return;
		RegistryOps<JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, server.registryAccess());
		JsonArray array = new JsonArray();
		for (Shop shop : SHOPS.values()) {
			JsonObject o = new JsonObject();
			o.addProperty("id", shop.id());
			o.addProperty("name", shop.name());
			o.addProperty("price", shop.price());
			o.addProperty("dim", shop.dim());
			o.addProperty("x", shop.x());
			o.addProperty("y", shop.y());
			o.addProperty("z", shop.z());
			o.addProperty("color", shop.color());
			o.addProperty("money", shop.money());
			o.add("product", ItemStack.CODEC.encodeStart(ops, shop.product()).getOrThrow());
			o.add("cost", ItemStack.CODEC.encodeStart(ops, shop.cost()).getOrThrow());
			array.add(o);
		}
		try {
			Files.writeString(file, array.toString());
		} catch (IOException e) {
			CreateShopMod.LOGGER.error("Could not save {}", file, e);
		}
	}
}
