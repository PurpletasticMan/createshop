package com.createshop;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

import java.util.Locale;

/** The on-screen money counter (only runs on players' own games). */
public class CreateShopClient implements ClientModInitializer {
	private static volatile long balance = 0;
	private static volatile boolean hasData = false;
	private static volatile long deltaValue = 0;
	private static volatile long deltaTime = 0; // milliseconds

	private static final int SCALE = 2; // size of one pixel of the banknote

	// '#' dark outline, '+' light edge, ':' mid green, '.' bill color, ' ' empty
	private static final String[] BILL = {
			" ########### ",
			"#+++++++++++#",
			"#+:::::::::+#",
			"#+:.......:+#",
			"#+:...:...:+#",
			"#+:..:::..:+#",
			"#+:...:...:+#",
			"#+:.......:+#",
			"#+:.......:+#",
			"#+:.......:+#",
			"#+:.......:+#",
			"#+:.......:+#",
			"#+:.......:+#",
			"#+:...:...:+#",
			"#+:..:::..:+#",
			"#+:...:...:+#",
			"#+:.......:+#",
			"#+:::::::::+#",
			"#+++++++++++#",
			" ########### "
	};

	@Override
	public void onInitializeClient() {
		ClientPlayNetworking.registerGlobalReceiver(MoneyPayload.TYPE, (payload, context) -> {
			balance = payload.balance();
			hasData = true;
			if (payload.change() != 0) {
				long now = System.currentTimeMillis();
				boolean recent = deltaValue != 0 && now - deltaTime < 2500;
				deltaValue = recent ? deltaValue + payload.change() : payload.change();
				deltaTime = now;
			}
		});

		ClientPlayConnectionEvents.DISCONNECT.register((listener, client) -> {
			hasData = false;
			deltaValue = 0;
		});

		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("createshop", "money"), CreateShopClient::render);
	}

	private static String fmt(long value) {
		return "$" + String.format(Locale.ROOT, "%,d", value);
	}

	private static int color(char c) {
		return switch (c) {
			case '#' -> 0xFF0F5A24;
			case '+' -> 0xFF8AF7A6;
			case ':' -> 0xFF2FB955;
			case '.' -> 0xFF4BDB6F;
			default -> 0;
		};
	}

	private static void render(GuiGraphicsExtractor g, DeltaTracker tracker) {
		if (!hasData) return;
		var font = Minecraft.getInstance().font;

		String text = fmt(balance);
		int textW = font.width(text);

		int billW = BILL[0].length() * SCALE;
		int billH = BILL.length * SCALE;
		int barW = textW + 12;
		int barH = 13;
		int widgetW = Math.max(billW, barW);

		int x0 = g.guiWidth() - widgetW - 6;
		int billX = x0 + (widgetW - billW) / 2;
		int billY = g.guiHeight() / 2 - billH / 2;
		int barX = x0 + (widgetW - barW) / 2;
		int barY = billY + (billH - barH) / 2;

		// the banknote
		for (int row = 0; row < BILL.length; row++) {
			for (int col = 0; col < BILL[row].length(); col++) {
				int c = color(BILL[row].charAt(col));
				if (c == 0) continue;
				int px = billX + col * SCALE;
				int py = billY + row * SCALE;
				g.fill(px, py, px + SCALE, py + SCALE, c);
			}
		}

		// the dark bar with the amount
		g.fill(barX - 1, barY - 1, barX + barW + 1, barY + barH + 1, 0xFF0B3D1A);
		g.fill(barX, barY, barX + barW, barY + barH, 0xE6000000);
		g.text(font, text, barX + (barW - textW) / 2, barY + 3, 0xFF55FF55);

		// +$100 / -$100 under the bill, fading out
		long age = System.currentTimeMillis() - deltaTime;
		long change = deltaValue;
		if (change != 0 && age < 2500) {
			String d = (change > 0 ? "+" : "-") + fmt(Math.abs(change));
			int alpha = age < 1700 ? 255 : (int) (255 * (2500 - age) / 800.0);
			if (alpha >= 8) {
				int rgb = change > 0 ? 0x55FF55 : 0xFF5555;
				int dw = font.width(d);
				g.text(font, d, x0 + (widgetW - dw) / 2, billY + billH + 4, (alpha << 24) | rgb);
			}
		}
	}
}
