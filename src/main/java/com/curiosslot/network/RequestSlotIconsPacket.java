package com.curiosslot.network;

import com.curiosslot.CuriosSlotMod;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.ServerPayloadContext;

/**
 * 客户端 -> 服务端：注册饰品栏位界面打开时请求 icons 文件夹内的图标文件列表。
 * 服务端读取后以 {@link SlotIconListPacket} 回发。
 */
public record RequestSlotIconsPacket() implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<RequestSlotIconsPacket> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("curiosslot", "request_slot_icons"));
    public static final StreamCodec<ByteBuf, RequestSlotIconsPacket> STREAM_CODEC =
            StreamCodec.of((buf, p) -> {
            }, buf -> new RequestSlotIconsPacket());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(RequestSlotIconsPacket p, net.neoforged.neoforge.network.IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context instanceof ServerPayloadContext spc) {
                ServerPlayer sp = spc.getPlayer();
                if (sp != null) {
                    CuriosSlotMod.sendSlotIcons(sp);
                }
            }
        });
    }
}
