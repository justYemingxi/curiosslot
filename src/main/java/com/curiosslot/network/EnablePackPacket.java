package com.curiosslot.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 服务端 -> 客户端：单机/集成服务器注册新饰品栏位后，通知客户端启用生成的资源包
 * （<游戏根>/resourcepacks/curiosslot_slots.zip，含槽位图标与中文名本地化）。
 * 专用服务器不走此包（由服务器资源包自动下发）；未安装 mod 的客户端收不到此包、无此功能。
 */
public record EnablePackPacket() {

    public static void encode(EnablePackPacket p, FriendlyByteBuf buf) {
    }

    public static EnablePackPacket decode(FriendlyByteBuf buf) {
        return new EnablePackPacket();
    }

    public static void handle(EnablePackPacket p, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context c = ctx.get();
        c.enqueueWork(() -> {
            com.curiosslot.CuriosSlotMod.enableCustomPack();
        });
        c.setPacketHandled(true);
    }
}
