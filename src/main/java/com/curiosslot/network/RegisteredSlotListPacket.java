package com.curiosslot.network;

import com.curiosslot.screen.UnregisterSlotScreen;
import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * 服务端 -> 客户端：已注册槽位 id 列表，供清除饰品栏位界面选择。
 */
public record RegisteredSlotListPacket(List<String> ids) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<RegisteredSlotListPacket> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("curiosslot", "registered_slot_list"));

    private static final StreamCodec<ByteBuf, List<String>> STR_LIST = StreamCodec.of(
            (buf, list) -> {
                buf.writeInt(list.size());
                for (String s : list) {
                    ByteBufCodecs.STRING_UTF8.encode(buf, s);
                }
            },
            (buf) -> {
                int n = buf.readInt();
                List<String> l = new ArrayList<>();
                for (int i = 0; i < n; i++) {
                    l.add(ByteBufCodecs.STRING_UTF8.decode(buf));
                }
                return l;
            });

    public static final StreamCodec<ByteBuf, RegisteredSlotListPacket> STREAM_CODEC =
            StreamCodec.composite(STR_LIST, RegisteredSlotListPacket::ids, RegisteredSlotListPacket::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(RegisteredSlotListPacket p, net.neoforged.neoforge.network.handling.IPayloadContext context) {
        context.enqueueWork(() -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.screen instanceof UnregisterSlotScreen s) {
                s.setSlotIds(p.ids());
            }
        });
    }
}
