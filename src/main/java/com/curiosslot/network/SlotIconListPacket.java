package com.curiosslot.network;

import com.curiosslot.screen.RegisterSlotScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 服务端 -> 客户端：icons 文件夹内的槽位图标文件名（去 .png 后缀），供注册饰品栏位界面选择。
 */
public record SlotIconListPacket(List<String> ids) {

    public static void encode(SlotIconListPacket p, FriendlyByteBuf buf) {
        buf.writeInt(p.ids().size());
        for (String s : p.ids()) {
            buf.writeUtf(s);
        }
    }

    public static SlotIconListPacket decode(FriendlyByteBuf buf) {
        int n = buf.readInt();
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            ids.add(buf.readUtf(64));
        }
        return new SlotIconListPacket(ids);
    }

    public static void handle(SlotIconListPacket p, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context c = ctx.get();
        c.enqueueWork(() -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.screen instanceof RegisterSlotScreen s) {
                s.setIconIds(p.ids());
            }
        });
        c.setPacketHandled(true);
    }
}
