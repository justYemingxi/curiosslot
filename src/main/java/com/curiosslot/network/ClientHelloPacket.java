package com.curiosslot.network;

import com.curiosslot.CuriosSlotMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 客户端登录后主动发给服务端的握手包：告知服务端"本客户端已安装 curiosslot"。
 * 客户端未装 mod 时不会注册本通道，也就不会发出此包，服务端据此识别未安装的客户端。
 */
public record ClientHelloPacket() {

    public static void encode(ClientHelloPacket msg, FriendlyByteBuf buf) {
    }

    public static ClientHelloPacket decode(FriendlyByteBuf buf) {
        return new ClientHelloPacket();
    }

    public static void handle(ClientHelloPacket msg, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer sp = ctx.get().getSender();
            if (sp != null) {
                CuriosSlotMod.markClientHasMod(sp.getUUID());
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
