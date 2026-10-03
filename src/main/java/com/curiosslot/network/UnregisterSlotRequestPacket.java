package com.curiosslot.network;

import com.curiosslot.CuriosSlotMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 客户端 -> 服务端：清除饰品栏位界面点击"清除该栏位"后，请求服务端执行 unregisterslot。
 * 服务端会校验操作者 4 级权限。
 */
public record UnregisterSlotRequestPacket(String id) {

    public static void encode(UnregisterSlotRequestPacket p, FriendlyByteBuf buf) {
        buf.writeUtf(p.id());
    }

    public static UnregisterSlotRequestPacket decode(FriendlyByteBuf buf) {
        return new UnregisterSlotRequestPacket(buf.readUtf(64));
    }

    public static void handle(UnregisterSlotRequestPacket p, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context c = ctx.get();
        c.enqueueWork(() -> {
            var sender = c.getSender();
            if (sender != null) {
                CuriosSlotMod.unregisterSlotFromClient(sender, p.id());
            }
        });
        c.setPacketHandled(true);
    }
}
