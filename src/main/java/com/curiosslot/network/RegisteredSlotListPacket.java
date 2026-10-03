package com.curiosslot.network;

import com.curiosslot.screen.UnregisterSlotScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 服务端 -> 客户端：已注册槽位 id 列表，供清除饰品栏位界面选择。
 */
public record RegisteredSlotListPacket(List<String> ids) {

    public static void encode(RegisteredSlotListPacket p, FriendlyByteBuf buf) {
        buf.writeInt(p.ids().size());
        for (String s : p.ids()) {
            buf.writeUtf(s);
        }
    }

    public static RegisteredSlotListPacket decode(FriendlyByteBuf buf) {
        int n = buf.readInt();
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            ids.add(buf.readUtf(64));
        }
        return new RegisteredSlotListPacket(ids);
    }

    public static void handle(RegisteredSlotListPacket p, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context c = ctx.get();
        c.enqueueWork(() -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.screen instanceof UnregisterSlotScreen s) {
                s.setSlotIds(p.ids());
            }
        });
        c.setPacketHandled(true);
    }
}
