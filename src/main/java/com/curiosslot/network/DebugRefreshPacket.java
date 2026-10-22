package com.curiosslot.network;

import com.curiosslot.screen.CuriosDebugScreen;
import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 服务端 -> 客户端：某次操作（set/add/create/setDefault）后，把目标实体最新的栏位数据发回客户端，
 * 刷新已打开的 {@link CuriosDebugScreen}。界面由 {@link DebugOpenPacket} 打开。
 */
public record DebugRefreshPacket(UUID uuid, String name,
                                 Map<String, Integer> existing,
                                 Map<String, Integer> defaults,
                                 List<String> creatable) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<DebugRefreshPacket> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("curiosslot", "debug_refresh"));

    private static final StreamCodec<ByteBuf, Map<String, Integer>> INT_MAP = StreamCodec.of(
            (buf, map) -> {
                buf.writeInt(map.size());
                for (Map.Entry<String, Integer> e : map.entrySet()) {
                    buf.writeUtf(e.getKey());
                    buf.writeInt(e.getValue());
                }
            },
            (buf) -> {
                int n = buf.readInt();
                Map<String, Integer> m = new LinkedHashMap<>();
                for (int i = 0; i < n; i++) {
                    m.put(buf.readUtf(64), buf.readInt());
                }
                return m;
            });

    private static final StreamCodec<ByteBuf, List<String>> STR_LIST = StreamCodec.of(
            (buf, list) -> {
                buf.writeInt(list.size());
                for (String s : list) {
                    buf.writeUtf(s);
                }
            },
            (buf) -> {
                int n = buf.readInt();
                List<String> l = new ArrayList<>();
                for (int i = 0; i < n; i++) {
                    l.add(buf.readUtf(64));
                }
                return l;
            });

    public static final StreamCodec<ByteBuf, DebugRefreshPacket> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.UUID, DebugRefreshPacket::uuid,
            ByteBufCodecs.STRING_UTF8, DebugRefreshPacket::name,
            INT_MAP, DebugRefreshPacket::existing,
            INT_MAP, DebugRefreshPacket::defaults,
            STR_LIST, DebugRefreshPacket::creatable,
            DebugRefreshPacket::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(DebugRefreshPacket p, net.neoforged.neoforge.network.IPayloadContext context) {
        context.enqueueWork(() -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.screen instanceof CuriosDebugScreen s && s.getTargetUuid().equals(p.uuid())) {
                s.updateData(p.name(), p.existing(), p.defaults(), p.creatable());
            }
        });
    }
}
