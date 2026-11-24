package com.createshop;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Sent from the server to players who have the mod: their balance and how much it just changed. */
public record MoneyPayload(long balance, long change) implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<MoneyPayload> TYPE =
			new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("createshop", "money"));
	public static final StreamCodec<RegistryFriendlyByteBuf, MoneyPayload> CODEC =
			CustomPacketPayload.codec(MoneyPayload::write, MoneyPayload::new);

	public MoneyPayload(RegistryFriendlyByteBuf buf) {
		this(buf.readLong(), buf.readLong());
	}

	public void write(RegistryFriendlyByteBuf buf) {
		buf.writeLong(balance);
		buf.writeLong(change);
	}

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
