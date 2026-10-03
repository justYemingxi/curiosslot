package com.curiosslot.network;

import com.curiosslot.CuriosSlotMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 客户端 -> 服务端：注册饰品栏位界面点击"注册饰品栏"后，请求服务端执行 registerslot。
 * 服务端会校验操作者 4 级权限。
 */
public record RegisterSlotRequestPacket(String id, String displayName) {

    public static void encode(RegisterSlotRequestPacket p, FriendlyByteBuf buf) {
        buf.writeUtf(p.id());
        buf.writeUtf(p.displayName());
    }

    public static RegisterSlotRequestPacket decode(FriendlyByteBuf buf) {
        return new RegisterSlotRequestPacket(buf.readUtf(64), buf.readUtf(256));
    }

    public static void handle(RegisterSlotRequestPacket p, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context c = ctx.get();
        c.enqueueWork(() -> {
            var sender = c.getSender();
            if (sender != null) {
                CuriosSlotMod.registerSlotFromClient(sender, p.id(), p.displayName());
            }
        });
        c.setPacketHandled(true);
    }
}
