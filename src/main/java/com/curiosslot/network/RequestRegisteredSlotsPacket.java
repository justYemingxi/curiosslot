package com.curiosslot.network;

import com.curiosslot.CuriosSlotMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 客户端 -> 服务端：清除饰品栏位界面打开时请求已注册槽位 id 列表。
 * 服务端读取全局数据包后以 {@link RegisteredSlotListPacket} 回发。
 */
public record RequestRegisteredSlotsPacket() {

    public static void encode(RequestRegisteredSlotsPacket p, FriendlyByteBuf buf) {
    }

    public static RequestRegisteredSlotsPacket decode(FriendlyByteBuf buf) {
        return new RequestRegisteredSlotsPacket();
    }

    public static void handle(RequestRegisteredSlotsPacket p, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context c = ctx.get();
        c.enqueueWork(() -> {
            var sender = c.getSender();
            if (sender != null) {
                CuriosSlotMod.sendRegisteredSlots(sender);
            }
        });
        c.setPacketHandled(true);
    }
}
