package com.curiosslot.network;

import com.curiosslot.screen.CuriosDebugScreen;
import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraft.core.UUIDUtil;
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
 * 服务端 -> 客户端：/curiosslot open 打开调试界面时，把目标实体最新的栏位数据发到客户端。
 * existing：实体当前各槽位数量；defaults：该实体类型各槽位默认数量。
 * canRegister：操作者是否拥有 4 级权限（决定"注册饰品栏位"按钮是否可见）。
 * 客户端收到后打开（或刷新已打开的）{@link CuriosDebugScreen}。
 */
public record DebugOpenPacket(UUID uuid, String name,
                              Map<String, Integer> existing,
                              Map<String, Integer> defaults,
                              List<String> creatable,
                              boolean canRegister) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<DebugOpenPacket> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("curiosslot", "debug_open"));

    private static final StreamCodec<ByteBuf, Map<String, Integer>> INT_MAP = StreamCodec.of(
            (buf, map) -> {
                buf.writeInt(map.size());
                for (Map.Entry<String, Integer> e : map.entrySet()) {
                    ByteBufCodecs.STRING_UTF8.encode(buf, e.getKey());
                    buf.writeInt(e.getValue());
                }
            },
            (buf) -> {
                int n = buf.readInt();
                Map<String, Integer> m = new LinkedHashMap<>();
                for (int i = 0; i < n; i++) {
                    m.put(ByteBufCodecs.STRING_UTF8.decode(buf), buf.readInt());
                }
                return m;
            });

    private static final StreamCodec<ByteBuf, List<String>> STR_LIST = StreamCodec.of(
            (buf, list) -> {
                buf.writeInt(list.size());
                for (String s : list) {
                    ByteBufCodecs.STRING_UTF8.encode(buf, s);
                }
            },
            (buf) -> {
                int n = buf.readInt();
                List<String> l = new ArrayList<>();
                for (int i = 0; i < n; i++) {
                    l.add(ByteBufCodecs.STRING_UTF8.decode(buf));
                }
                return l;
            });

    public static final StreamCodec<ByteBuf, DebugOpenPacket> STREAM_CODEC = StreamCodec.composite(
            UUIDUtil.STREAM_CODEC, DebugOpenPacket::uuid,
            ByteBufCodecs.STRING_UTF8, DebugOpenPacket::name,
            INT_MAP, DebugOpenPacket::existing,
            INT_MAP, DebugOpenPacket::defaults,
            STR_LIST, DebugOpenPacket::creatable,
            ByteBufCodecs.BOOL, DebugOpenPacket::canRegister,
            DebugOpenPacket::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(DebugOpenPacket p, net.neoforged.neoforge.network.handling.IPayloadContext context) {
        context.enqueueWork(() -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.screen instanceof CuriosDebugScreen s && s.getTargetUuid().equals(p.uuid())) {
                s.updateData(p.name(), p.existing(), p.defaults(), p.creatable());
            } else {
                mc.setScreen(new CuriosDebugScreen(p.uuid(), p.name(), p.existing(), p.defaults(),
                        p.creatable(), p.canRegister()));
            }
        });
    }
}
