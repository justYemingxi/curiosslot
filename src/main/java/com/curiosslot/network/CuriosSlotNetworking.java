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
        CHANNEL.messageBuilder(ClientHelloPacket.class, 2, NetworkDirection.PLAY_TO_SERVER)
                .encoder(ClientHelloPacket::encode)
                .decoder(ClientHelloPacket::decode)
                .consumerMainThread(ClientHelloPacket::handle)
                .add();
        CHANNEL.messageBuilder(DebugOpenPacket.class, 3, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(DebugOpenPacket::encode)
                .decoder(DebugOpenPacket::decode)
                .consumerMainThread(DebugOpenPacket::handle)
                .add();
    }

    private CuriosSlotNetworking() {
    }
}
