package com.curiosslot.network;

import com.curiosslot.screen.RegisterSlotScreen;
import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * 服务端 -> 客户端：icons 文件夹内的槽位图标文件名（去 .png 后缀），供注册饰品栏位界面选择。
 */
public record SlotIconListPacket(List<String> ids) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<SlotIconListPacket> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("curiosslot", "slot_icon_list"));

    private static final StreamCodec<ByteBuf, List<String>> STR_LIST = StreamCodec.of(
            (buf, list) -> {
                buf.writeInt(list.size());
                for (String s : list) {
                    buf.writeUtf(s);
                }
            },
            (buf) -> {
                int n = buf.readInt();
                List<String> l = new ArrayList<>();
                for (int i = 0; i < n; i++) {
                    l.add(buf.readUtf(64));
                }
                return l;
            });

    public static final StreamCodec<ByteBuf, SlotIconListPacket> STREAM_CODEC =
            StreamCodec.composite(STR_LIST, SlotIconListPacket::ids, SlotIconListPacket::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(SlotIconListPacket p, net.neoforged.neoforge.network.IPayloadContext context) {
        context.enqueueWork(() -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.screen instanceof RegisterSlotScreen s) {
                s.setIconIds(p.ids());
            }
        });
    }
}
