package com.curiosslot.network;

import com.curiosslot.screen.CuriosDebugScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

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
                              boolean canRegister) {

    public static void encode(DebugOpenPacket p, FriendlyByteBuf buf) {
        buf.writeUUID(p.uuid());
        buf.writeUtf(p.name());
        writeIntMap(buf, p.existing());
        writeIntMap(buf, p.defaults());
        buf.writeInt(p.creatable().size());
        for (String s : p.creatable()) {
            buf.writeUtf(s);
        }
        buf.writeBoolean(p.canRegister());
    }

    private static void writeIntMap(FriendlyByteBuf buf, Map<String, Integer> m) {
        buf.writeInt(m.size());
        for (Map.Entry<String, Integer> e : m.entrySet()) {
            buf.writeUtf(e.getKey());
            buf.writeInt(e.getValue());
        }
    }

    private static Map<String, Integer> readIntMap(FriendlyByteBuf buf) {
        int n = buf.readInt();
        Map<String, Integer> m = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            m.put(buf.readUtf(64), buf.readInt());
        }
        return m;
    }

    public static DebugOpenPacket decode(FriendlyByteBuf buf) {
        UUID uuid = buf.readUUID();
        String name = buf.readUtf(256);
        Map<String, Integer> existing = readIntMap(buf);
        Map<String, Integer> defaults = readIntMap(buf);
        int cr = buf.readInt();
        List<String> creatable = new ArrayList<>();
        for (int i = 0; i < cr; i++) {
            creatable.add(buf.readUtf(64));
        }
        boolean canRegister = buf.readBoolean();
        return new DebugOpenPacket(uuid, name, existing, defaults, creatable, canRegister);
    }

    public static void handle(DebugOpenPacket p, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context c = ctx.get();
        c.enqueueWork(() -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.screen instanceof CuriosDebugScreen s && s.getTargetUuid().equals(p.uuid())) {
                s.updateData(p.name(), p.existing(), p.defaults(), p.creatable());
            } else {
                mc.setScreen(new CuriosDebugScreen(p.uuid(), p.name(), p.existing(), p.defaults(),
                        p.creatable(), p.canRegister()));
            }
        });
        c.setPacketHandled(true);
    }
}
