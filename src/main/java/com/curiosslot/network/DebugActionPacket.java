package com.curiosslot.network;

import com.curiosslot.CuriosSlotMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;
import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.type.capability.ICuriosItemHandler;
import top.theillusivec4.curios.api.type.inventory.ICurioStacksHandler;
import top.theillusivec4.curios.api.type.util.ISlotHelper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 客户端 -> 服务端：调试界面上的一个操作。
 * action：create（为该生物类型创建新栏位）/ set（设置已有栏位数量）/ add（增加已有栏位数量）。
 */
public record DebugActionPacket(UUID uuid, String action, String slot, int count) {

    public static void encode(DebugActionPacket p, FriendlyByteBuf buf) {
        buf.writeUUID(p.uuid());
        buf.writeUtf(p.action());
        buf.writeUtf(p.slot());
        buf.writeInt(p.count());
    }

    public static DebugActionPacket decode(FriendlyByteBuf buf) {
        return new DebugActionPacket(buf.readUUID(), buf.readUtf(32), buf.readUtf(64), buf.readInt());
    }

    public static void handle(DebugActionPacket p, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context c = ctx.get();
        c.enqueueWork(() -> {
            ServerPlayer sp = c.getSender();
            if (sp != null) {
                handleServer(sp, p);
            }
        });
        c.setPacketHandled(true);
    }

    private static void handleServer(ServerPlayer sp, DebugActionPacket p) {
        MinecraftServer server = sp.server;
        LivingEntity target = findEntity(server, p.uuid());
        if (target == null) {
            sp.sendSystemMessage(Component.literal("[curiosslot] 目标实体已不存在（可能已卸载或消失）。"));
            return;
        }
        ISlotHelper helper = CuriosApi.getSlotHelper();
        switch (p.action()) {
            case "create" -> {
                EntityType<?> type = target.getType();
                if (helper.getSlotType(p.slot()).isEmpty()) {
                    sp.sendSystemMessage(Component.literal("[curiosslot] 槽位类型未注册: " + p.slot()));
                    break;
                }
                if (CuriosApi.getEntitySlots(type).containsKey(p.slot())) {
                    sp.sendSystemMessage(Component.literal("[curiosslot] 该实体类型已拥有 " + p.slot() + " 栏位，无需创建。"));
                    break;
                }
                try {
                    boolean reloaded = CuriosSlotMod.applyCreateSlot(
                            (ServerLevel) target.level(), type, p.slot(), Math.max(1, p.count()));
                    sp.sendSystemMessage(Component.literal("[curiosslot] 已为实体类型 " + EntityType.getKey(type)
                            + " 创建槽位 " + p.slot() + "（默认数量 " + Math.max(1, p.count()) + "）。"
                            + (reloaded ? "已自动重载生效。" : "请执行 /reload 后生效。")
                            + " 注意：已生成且没有该饰品栏位的生物，需重新进入存档后才会加载该栏位。"));
                } catch (Exception e) {
                    sp.sendSystemMessage(Component.literal("[curiosslot] 创建槽位出错: " + e.getMessage()));
                }
            }
            case "set", "add" -> {
                var inv = CuriosApi.getCuriosInventory(target);
                if (!inv.isPresent()) {
                    sp.sendSystemMessage(Component.literal("[curiosslot] 目标实体没有 Curios 饰品栏。"));
                    break;
                }
                ICuriosItemHandler handler = inv.resolve().orElse(null);
                if (handler == null || handler.getStacksHandler(p.slot()).isEmpty()) {
                    sp.sendSystemMessage(Component.literal("[curiosslot] 该实体没有 " + p.slot() + " 栏位，无法修改。"));
                    break;
                }
                if ("set".equals(p.action())) {
                    helper.setSlotsForType(p.slot(), target, Math.max(0, p.count()));
                } else {
                    helper.growSlotType(p.slot(), Math.max(1, p.count()), target);
                }
                CuriosSlotMod.markTouched(server, target, p.slot());
                sp.sendSystemMessage(Component.literal("[curiosslot] 已" + ("set".equals(p.action()) ? "设置" : "增加")
                        + " " + p.slot() + " x " + p.count() + " → " + target.getName().getString()));
            }
            case "setDefault" -> {
                int n = Math.max(0, p.count());
                CuriosSlotMod.setDefault(server, target.getType(), p.slot(), n);
                sp.sendSystemMessage(Component.literal("[curiosslot] 已设置实体类型 " + EntityType.getKey(target.getType())
                        + " 的 " + p.slot() + " 默认数量为 " + n
                        + "。已生成的生物需 /reload 或重新进入存档后按新默认值重置。"));
            }
            default -> {
            }
        }
        refresh(sp, target);
    }

    /** 在服务端所有世界中按 UUID 找到目标实体（LivingEntity）。 */
    private static LivingEntity findEntity(MinecraftServer server, UUID uuid) {
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity e : level.getEntities().getAll()) {
                if (e instanceof LivingEntity le && le.getUUID().equals(uuid)) {
                    return le;
                }
            }
        }
        return null;
    }

    /** 重新收集目标实体的栏位信息，发回给玩家刷新界面。 */
    private static void refresh(ServerPlayer sp, LivingEntity target) {
        Map<String, Integer> existing = new LinkedHashMap<>();
        var inv = CuriosApi.getCuriosInventory(target);
        if (inv.isPresent()) {
            ICuriosItemHandler handler = inv.resolve().orElse(null);
            if (handler != null) {
                for (Map.Entry<String, ICurioStacksHandler> e : handler.getCurios().entrySet()) {
                    existing.put(e.getKey(), e.getValue().getSlots());
                }
            }
        }
        Map<String, Integer> defaults = CuriosSlotMod.collectDefaults(target);
        List<String> creatable = new ArrayList<>();
        Set<String> all = CuriosApi.getSlotHelper().getSlotTypeIds();
        for (String s : all) {
            if (!existing.containsKey(s)) {
                creatable.add(s);
            }
        }
        CuriosSlotNetworking.CHANNEL.send(
                PacketDistributor.PLAYER.with(() -> sp),
                new DebugRefreshPacket(target.getUUID(), target.getName().getString(), existing, defaults, creatable));
    }
}
