package com.curiosslot.network;

import com.curiosslot.CuriosSlotMod;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

/** Curios Slot 专用网络通道（版本 "1"，双端接受任意协议版本）。 */
public final class CuriosSlotNetworking {

    public static final String PROTOCOL = "1";
    public static final SimpleChannel CHANNEL = NetworkRegistry.ChannelBuilder
            .named(new ResourceLocation(CuriosSlotMod.MODID, "main"))
            .networkProtocolVersion(() -> PROTOCOL)
            .clientAcceptedVersions(s -> true)
            .serverAcceptedVersions(s -> true)
            .simpleChannel();

    public static void register() {
        CHANNEL.messageBuilder(DebugActionPacket.class, 0, NetworkDirection.PLAY_TO_SERVER)
                .encoder(DebugActionPacket::encode)
                .decoder(DebugActionPacket::decode)
                .consumerMainThread(DebugActionPacket::handle)
                .add();
        CHANNEL.messageBuilder(DebugRefreshPacket.class, 1, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(DebugRefreshPacket::encode)
                .decoder(DebugRefreshPacket::decode)
                .consumerMainThread(DebugRefreshPacket::handle)
                .add();
        CHANNEL.messageBuilder(DebugOpenPacket.class, 3, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(DebugOpenPacket::encode)
                .decoder(DebugOpenPacket::decode)
                .consumerMainThread(DebugOpenPacket::handle)
                .add();
        CHANNEL.messageBuilder(EnablePackPacket.class, 4, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(EnablePackPacket::encode)
                .decoder(EnablePackPacket::decode)
                .consumerMainThread(EnablePackPacket::handle)
                .add();
        CHANNEL.messageBuilder(RequestSlotIconsPacket.class, 5, NetworkDirection.PLAY_TO_SERVER)
                .encoder(RequestSlotIconsPacket::encode)
                .decoder(RequestSlotIconsPacket::decode)
                .consumerMainThread(RequestSlotIconsPacket::handle)
                .add();
        CHANNEL.messageBuilder(SlotIconListPacket.class, 6, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(SlotIconListPacket::encode)
                .decoder(SlotIconListPacket::decode)
                .consumerMainThread(SlotIconListPacket::handle)
                .add();
        CHANNEL.messageBuilder(RegisterSlotRequestPacket.class, 7, NetworkDirection.PLAY_TO_SERVER)
                .encoder(RegisterSlotRequestPacket::encode)
                .decoder(RegisterSlotRequestPacket::decode)
                .consumerMainThread(RegisterSlotRequestPacket::handle)
                .add();
        CHANNEL.messageBuilder(RequestRegisteredSlotsPacket.class, 8, NetworkDirection.PLAY_TO_SERVER)
                .encoder(RequestRegisteredSlotsPacket::encode)
                .decoder(RequestRegisteredSlotsPacket::decode)
                .consumerMainThread(RequestRegisteredSlotsPacket::handle)
                .add();
        CHANNEL.messageBuilder(RegisteredSlotListPacket.class, 9, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(RegisteredSlotListPacket::encode)
                .decoder(RegisteredSlotListPacket::decode)
                .consumerMainThread(RegisteredSlotListPacket::handle)
                .add();
        CHANNEL.messageBuilder(UnregisterSlotRequestPacket.class, 10, NetworkDirection.PLAY_TO_SERVER)
                .encoder(UnregisterSlotRequestPacket::encode)
                .decoder(UnregisterSlotRequestPacket::decode)
                .consumerMainThread(UnregisterSlotRequestPacket::handle)
                .add();
        CHANNEL.messageBuilder(SyncResourcePackPacket.class, 11, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(SyncResourcePackPacket::encode)
                .decoder(SyncResourcePackPacket::decode)
                .consumerMainThread(SyncResourcePackPacket::handle)
                .add();
    }

    private CuriosSlotNetworking() {
    }
}
