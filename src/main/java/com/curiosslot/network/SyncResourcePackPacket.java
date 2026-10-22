package com.curiosslot.network;

import com.curiosslot.CuriosSlotMod;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.loading.FMLPaths;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 服务端 -> 客户端：把注册/清除饰品栏位后生成的客户端资源包
 * （{@code resourcepacks/curiosslot_slots.zip}，含槽位图标与中文名本地化）的字节下发到客户端。
 * 客户端收到后写进<strong>客户端自己的</strong>游戏根目录 {@code resourcepacks/} 并加载，
 * 使多人联机时图标与中文名在客户端正常显示。
 * 未安装 mod 的客户端收不到此包、无此功能。
 */
public record SyncResourcePackPacket(byte[] zipBytes) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<SyncResourcePackPacket> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("curiosslot", "sync_resource_pack"));
    public static final StreamCodec<ByteBuf, SyncResourcePackPacket> STREAM_CODEC =
            ByteBufCodecs.byteArray(10 * 1024 * 1024)
                    .map(SyncResourcePackPacket::new, SyncResourcePackPacket::zipBytes);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(SyncResourcePackPacket p, net.neoforged.neoforge.network.IPayloadContext context) {
        context.enqueueWork(() -> {
            try {
                Path packsDir = FMLPaths.GAMEDIR.get().resolve("resourcepacks");
                Files.createDirectories(packsDir);
                Files.write(packsDir.resolve("curiosslot_slots.zip"), p.zipBytes());
                CuriosSlotMod.enableCustomPack();
            } catch (Exception ignored) {
            }
        });
    }
}
