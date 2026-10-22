package com.curiosslot.network;

import com.curiosslot.CuriosSlotMod;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.ServerPayloadContext;

/**
 * 客户端 -> 服务端：清除饰品栏位界面打开时请求已注册槽位 id 列表。
 * 服务端读取全局数据包后以 {@link RegisteredSlotListPacket} 回发。
 */
public record RequestRegisteredSlotsPacket() implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<RequestRegisteredSlotsPacket> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("curiosslot", "request_registered_slots"));
    public static final StreamCodec<ByteBuf, RequestRegisteredSlotsPacket> STREAM_CODEC =
            StreamCodec.of((buf, p) -> {
            }, buf -> new RequestRegisteredSlotsPacket());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(RequestRegisteredSlotsPacket p, net.neoforged.neoforge.network.handling.IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context instanceof ServerPayloadContext spc) {
                ServerPlayer sp = spc.getPlayer();
                if (sp != null) {
                    CuriosSlotMod.sendRegisteredSlots(sp);
                }
            }
        });
    }
}
