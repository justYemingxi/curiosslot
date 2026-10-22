package com.curiosslot.network;

import com.curiosslot.CuriosSlotMod;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 服务端 -> 客户端：单机/集成服务器注册新饰品栏位后，通知客户端启用生成的资源包
 * （<游戏根>/resourcepacks/curiosslot_slots.zip，含槽位图标与中文名本地化）。
 * 专用服务器不走此包（由服务器资源包自动下发）；未安装 mod 的客户端收不到此包、无此功能。
 */
public record EnablePackPacket() implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<EnablePackPacket> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("curiosslot", "enable_pack"));
    public static final StreamCodec<ByteBuf, EnablePackPacket> STREAM_CODEC =
            StreamCodec.of((buf, p) -> {
            }, buf -> new EnablePackPacket());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(EnablePackPacket p, net.neoforged.neoforge.network.IPayloadContext context) {
        context.enqueueWork(CuriosSlotMod::enableCustomPack);
    }
}
