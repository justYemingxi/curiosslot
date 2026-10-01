package com.curiosslot;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.type.capability.ICuriosItemHandler;
import top.theillusivec4.curios.api.type.util.ISlotHelper;

/**
 * Curios Slot —— 局内为实体添加/设置 Curios 饰品栏位的 Forge mod (1.20.1)。
 * <p>
 * 局内指令（需 OP / 作弊）：
 *   /curiosslot set <槽位类型> <数量> <目标实体>
 *   /curiosslot add <槽位类型> <数量> <目标实体>
 * <p>
 * 例：给最近的一只女仆把戒指位设为 4 个
 *   /curiosslot set ring 4 @e[type=touhou_little_maid:maid,limit=1,sort=nearest]
 * <p>
 * 例：给指定名字的女仆额外增加 1 个项链位
 *   /curiosslot add necklace 1 @e[type=touhou_little_maid:maid,name=小夜,limit=1]
 * <p>
 * 适用目标：玩家，以及被 Curios 集成的实体（典型如车万女仆）。
 * 注意：普通生物（牛、僵尸等）在 Curios 中不持有饰品栏 handler，
 * Curios 不为其提供饰品栏，无法通过 API 新增槽位（这是 Curios 的底层限制）。
 * 槽位类型须为 Curios 已注册的：curio / back / belt / body / bracelet / charm /
 * head / hands / necklace / ring / feet / scroll / spellbook / accessory 等。
 */
@Mod(CuriosSlotMod.MODID)
public class CuriosSlotMod {

    public static final String MODID = "curiosslot";

    public CuriosSlotMod() {
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("curiosslot")
                        .requires(src -> src.hasPermission(2))
                        .then(Commands.literal("set")
                                .then(Commands.argument("slot", StringArgumentType.word())
                                        .then(Commands.argument("count", IntegerArgumentType.integer())
                                                .then(Commands.argument("target", EntityArgument.entity())
                                                        .executes(ctx -> run(ctx, "set"))))))
                        .then(Commands.literal("add")
                                .then(Commands.argument("slot", StringArgumentType.word())
                                        .then(Commands.argument("count", IntegerArgumentType.integer())
                                                .then(Commands.argument("target", EntityArgument.entity())
                                                        .executes(ctx -> run(ctx, "add"))))))
        );
    }

    private int run(CommandContext<CommandSourceStack> ctx, String mode) {
        CommandSourceStack src = ctx.getSource();
        try {
            String slot = StringArgumentType.getString(ctx, "slot");
            int count = IntegerArgumentType.getInteger(ctx, "count");
            Entity target = EntityArgument.getEntity(ctx, "target");

            if (!(target instanceof LivingEntity living)) {
                src.sendFailure(Component.literal("目标不是 LivingEntity，无法添加 Curios 栏位。"));
                return 0;
            }

            ISlotHelper slotHelper = CuriosApi.getSlotHelper();
            if (slotHelper.getSlotType(slot).isEmpty()) {
                src.sendFailure(Component.literal("槽位类型未注册: " + slot));
                return 0;
            }

            // 只有持有 Curios 饰品栏 handler 的实体才能真正加槽位
            // （玩家、车万女仆等集成实体，以及部分自带栏位的生物如僵尸）。
            var inv = CuriosApi.getCuriosInventory(living);
            if (!inv.isPresent()) {
                src.sendFailure(Component.literal("目标实体没有 Curios 饰品栏，仅玩家与 Curios 集成实体（如车万女仆）可用。"));
                return 0;
            }
            ICuriosItemHandler handler = inv.resolve().orElse(null);
            if (handler == null) {
                src.sendFailure(Component.literal("无法解析目标实体的 Curios 饰品栏。"));
                return 0;
            }

            // Curios 只能调整实体"已有"的栏位，无法凭空创造它没有的栏位类型。
            // 请求的栏位该实体没有时，明确提示并列出它实际有哪些栏位。
            if (handler.getStacksHandler(slot).isEmpty()) {
                src.sendFailure(Component.literal("该实体没有 " + slot + " 栏位，无法添加。其已有栏位：" + handler.getCurios().keySet()
                        + "。仅能调整该实体已有的栏位。"));
                return 0;
            }

            if ("set".equals(mode)) {
                slotHelper.setSlotsForType(slot, living, count);
            } else {
                slotHelper.growSlotType(slot, count, living);
            }

            src.sendSuccess(
                    () -> Component.literal(("set".equals(mode) ? "已设置 " : "已增加 ")
                            + slot + " x " + count + " → " + target.getName().getString()),
                    true);
            return 1;
        } catch (Exception e) {
            src.sendFailure(Component.literal("[curiosslot] 执行出错: " + e.getMessage()));
            return 0;
        }
    }
}
