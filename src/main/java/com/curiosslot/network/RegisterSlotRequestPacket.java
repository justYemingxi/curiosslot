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
 * 客户端 -> 服务端：注册饰品栏位界面点击"注册饰品栏"后，请求服务端执行 registerslot。
 * 服务端会校验操作者 4 级权限。
 */
public record RegisterSlotRequestPacket(String id, String displayName) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<RegisterSlotRequestPacket> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("curiosslot", "register_slot_request"));
    public static final StreamCodec<ByteBuf, RegisterSlotRequestPacket> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, RegisterSlotRequestPacket::id,
            ByteBufCodecs.STRING_UTF8, RegisterSlotRequestPacket::displayName,
            RegisterSlotRequestPacket::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(RegisterSlotRequestPacket p, net.neoforged.neoforge.network.handling.IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context instanceof ServerPayloadContext spc) {
                ServerPlayer sp = spc.player();
                if (sp != null) {
                    CuriosSlotMod.registerSlotFromClient(sp, p.id(), p.displayName());
                }
            }
        });
    }
}
