package com.curiosslot.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.network.NetworkEvent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Supplier;

/**
 * 服务端 -> 客户端：把注册/清除饰品栏位后生成的客户端资源包
 * （{@code resourcepacks/curiosslot_slots.zip}，含槽位图标与中文名本地化）的字节下发到客户端。
 * 客户端收到后写进<strong>客户端自己的</strong>游戏根目录 {@code resourcepacks/} 并加载，
 * 使多人联机时图标与中文名在客户端正常显示。
 * 未安装 mod 的客户端收不到此包、无此功能。
 */
public record SyncResourcePackPacket(byte[] zipBytes) {

    public static void encode(SyncResourcePackPacket p, FriendlyByteBuf buf) {
        buf.writeByteArray(p.zipBytes());
    }

    public static SyncResourcePackPacket decode(FriendlyByteBuf buf) {
        return new SyncResourcePackPacket(buf.readByteArray(10 * 1024 * 1024));
    }

    public static void handle(SyncResourcePackPacket p, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context c = ctx.get();
        c.enqueueWork(() -> {
            try {
                Path packsDir = FMLPaths.GAMEDIR.get().resolve("resourcepacks");
                Files.createDirectories(packsDir);
                Files.write(packsDir.resolve("curiosslot_slots.zip"), p.zipBytes());
                com.curiosslot.CuriosSlotMod.enableCustomPack();
            } catch (Exception ignored) {
            }
        });
        c.setPacketHandled(true);
    }
}
