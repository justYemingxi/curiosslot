package com.curiosslot.network;

import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Curios Slot 专用网络注册（NeoForge Payload 系统）。
 * 协议版本 "1"：双端接受任意协议版本。
 */
public final class CuriosSlotNetworking {

    public static final String PROTOCOL = "1";

    public static void register(final RegisterPayloadHandlersEvent event) {
        final PayloadRegistrar registrar = event.registrar(PROTOCOL);

        // 客户端 -> 服务端
        registrar.playToServer(DebugActionPacket.TYPE, DebugActionPacket.STREAM_CODEC, DebugActionPacket::handle);
        registrar.playToServer(RequestSlotIconsPacket.TYPE, RequestSlotIconsPacket.STREAM_CODEC, RequestSlotIconsPacket::handle);
        registrar.playToServer(RegisterSlotRequestPacket.TYPE, RegisterSlotRequestPacket.STREAM_CODEC, RegisterSlotRequestPacket::handle);
        registrar.playToServer(RequestRegisteredSlotsPacket.TYPE, RequestRegisteredSlotsPacket.STREAM_CODEC, RequestRegisteredSlotsPacket::handle);
        registrar.playToServer(UnregisterSlotRequestPacket.TYPE, UnregisterSlotRequestPacket.STREAM_CODEC, UnregisterSlotRequestPacket::handle);

        // 服务端 -> 客户端
        registrar.playToClient(DebugRefreshPacket.TYPE, DebugRefreshPacket.STREAM_CODEC, DebugRefreshPacket::handle);
        registrar.playToClient(DebugOpenPacket.TYPE, DebugOpenPacket.STREAM_CODEC, DebugOpenPacket::handle);
        registrar.playToClient(EnablePackPacket.TYPE, EnablePackPacket.STREAM_CODEC, EnablePackPacket::handle);
        registrar.playToClient(SlotIconListPacket.TYPE, SlotIconListPacket.STREAM_CODEC, SlotIconListPacket::handle);
        registrar.playToClient(RegisteredSlotListPacket.TYPE, RegisteredSlotListPacket.STREAM_CODEC, RegisteredSlotListPacket::handle);
        registrar.playToClient(SyncResourcePackPacket.TYPE, SyncResourcePackPacket.STREAM_CODEC, SyncResourcePackPacket::handle);
    }

    /** 客户端 -> 服务端发送（Screen 等客户端侧调用）。 */
    public static void sendToServer(CustomPacketPayload payload) {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.getConnection() != null) {
            mc.getConnection().send(payload);
        }
    }

    private CuriosSlotNetworking() {
    }
}
