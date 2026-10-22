package com.curiosslot.network;

import com.curiosslot.CuriosSlotMod;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.ServerPayloadContext;

/**
 * 客户端 -> 服务端：清除饰品栏位界面点击"清除该栏位"后，请求服务端执行 unregisterslot。
 * 服务端会校验操作者 4 级权限。
 */
public record UnregisterSlotRequestPacket(String id) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<UnregisterSlotRequestPacket> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("curiosslot", "unregister_slot_request"));
    public static final StreamCodec<ByteBuf, UnregisterSlotRequestPacket> STREAM_CODEC =
            StreamCodec.composite(ByteBufCodecs.STRING_UTF8, UnregisterSlotRequestPacket::id, UnregisterSlotRequestPacket::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(UnregisterSlotRequestPacket p, net.neoforged.neoforge.network.handling.IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context instanceof ServerPayloadContext spc) {
                ServerPlayer sp = spc.getPlayer();
                if (sp != null) {
                    CuriosSlotMod.unregisterSlotFromClient(sp, p.id());
                }
            }
        });
    }
}
