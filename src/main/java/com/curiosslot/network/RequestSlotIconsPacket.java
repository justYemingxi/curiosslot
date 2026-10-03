package com.curiosslot.network;

import com.curiosslot.CuriosSlotMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 客户端 -> 服务端：注册饰品栏位界面打开时请求 icons 文件夹内的图标文件列表。
 * 服务端读取后以 {@link SlotIconListPacket} 回发。
 */
public record RequestSlotIconsPacket() {

    public static void encode(RequestSlotIconsPacket p, FriendlyByteBuf buf) {
    }

    public static RequestSlotIconsPacket decode(FriendlyByteBuf buf) {
        return new RequestSlotIconsPacket();
    }

    public static void handle(RequestSlotIconsPacket p, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context c = ctx.get();
        c.enqueueWork(() -> {
            var sender = c.getSender();
            if (sender != null) {
                CuriosSlotMod.sendSlotIcons(sender);
            }
        });
        c.setPacketHandled(true);
    }
}
