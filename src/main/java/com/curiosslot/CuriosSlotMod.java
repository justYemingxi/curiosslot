package com.curiosslot;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.curiosslot.network.CuriosSlotNetworking;
import com.curiosslot.network.EnablePackPacket;
import com.curiosslot.network.SlotIconListPacket;
import com.curiosslot.network.RegisteredSlotListPacket;
import com.curiosslot.network.DebugOpenPacket;
import com.curiosslot.network.SyncResourcePackPacket;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.entity.player.ItemTooltipEvent;
import java.nio.file.StandardCopyOption;
import net.minecraft.server.dedicated.DedicatedServer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.registries.ForgeRegistries;
import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.type.capability.ICuriosItemHandler;
import top.theillusivec4.curios.api.type.inventory.ICurioStacksHandler;
import top.theillusivec4.curios.api.type.util.ISlotHelper;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Curios Slot —— 局内为实体添加/设置 Curios 饰品栏位的 Forge mod (1.20.1)。
 * <p>
 * 局内指令（需 OP / 作弊）：
 *   /curiosslot set <槽位类型> <数量> <目标实体>     —— 将已有栏位数量设为给定值
 *   /curiosslot add <槽位类型> <数量> <目标实体>     —— 在已有基础上增加栏位数量
 *   /curiosslot register <槽位类型> <目标实体>       —— 为目标的整个生物类型创建新饰品栏位
 * <p>
 * 例：给最近的一只女仆把戒指位设为 4 个
 *   /curiosslot set ring 4 @e[type=touhou_little_maid:maid,limit=1,sort=nearest]
 * <p>
 * 例：给指定名字的女仆额外增加 1 个项链位
 *   /curiosslot add necklace 1 @e[type=touhou_little_maid:maid,name=小夜,limit=1]
 * <p>
 * 例：给所有牛创建戒指栏位（基于 Curios 数据包 curios/entities 机制）
 *   /curiosslot register ring @e[type=minecraft:cow,limit=1,sort=nearest]
 * <p>
 * 适用目标：玩家，以及被 Curios 集成的实体（典型如车万女仆）。
 * set/add 只能调整实体"已有"的栏位；要给普通生物（牛、僵尸等）创建
 * 原本不存在的槽位，请使用 register（对目标的整个生物类型生效）。
 * 槽位类型须为 Curios 已注册的：curio / back / belt / body / bracelet / charm /
 * head / hands / necklace / ring / feet / scroll / spellbook / accessory 等。
 */
@Mod(CuriosSlotMod.MODID)
public class CuriosSlotMod {

    public static final String MODID = "curiosslot";

    // 配置：register 写入数据包后是否自动触发 /reload（默认 true）。
    // 关闭时只写映射，需玩家手动 /reload 或重进存档后生效，可避免 /reload 卡顿。
    private static final ForgeConfigSpec.Builder CONFIG_BUILDER = new ForgeConfigSpec.Builder();
    private static final ForgeConfigSpec.BooleanValue AUTO_RELOAD = CONFIG_BUILDER
            .comment("register 指令写入数据包后是否自动触发 /reload。",
                    "true：写入后自动重载（立即生效，但 /reload 会卡一下）；",
                    "false：只写入映射，需玩家手动执行 /reload 或重进存档后生效（避免卡顿）。",
                    "默认：true")
            .define("autoReload", true);
    private static final ForgeConfigSpec.IntValue SWEEP_INTERVAL_SECONDS = CONFIG_BUILDER
            .comment("兜底扫描间隔（秒）。生成钩子已让新实体出生即为 1 个，此扫描仅用于兜底",
                    "register 之前就已存在的旧实体，可设得较疏以降低开销。设为 0 表示关闭兜底扫描。",
                    "范围 0~3600，默认：10 秒")
            .defineInRange("sweepIntervalSeconds", 10, 0, 3600);
    private static final ForgeConfigSpec CONFIG_SPEC = CONFIG_BUILDER.build();

    public static boolean autoReload() {
        return AUTO_RELOAD.get();
    }

    // ========== 指令留痕 + 默认数量 + 持续兜底 ==========

    // 状态文件：<存档>/datapacks/curiosslot/curiosslot_state.json
    // {
    //   "touched": ["<实体UUID>#<槽位>", ...],        // 被 set/add 手动修改的单个实体，不再强制
    //   "defaults": {"<实体类型ID>#<槽位>": <数量>, ...}  // 该 (类型,槽位) 的默认栏位数量
    // }
    private static Path statePath(MinecraftServer server) {
        return server.getWorldPath(LevelResource.DATAPACK_DIR)
                .resolve("curiosslot").resolve("curiosslot_state.json");
    }

    private static String keyOf(EntityType<?> type, String slot) {
        ResourceLocation id = ForgeRegistries.ENTITY_TYPES.getKey(type);
        return (id != null ? id.toString() : "?") + "#" + slot;
    }

    // 单个实体的留痕 key：只保护这一只被 set/add 动过的生物。
    private static String keyOfEntity(LivingEntity le, String slot) {
        return le.getUUID() + "#" + slot;
    }

    // ========== 缓存（避免扫描时每轮读盘）==========
    // REGISTERED：已注册 实体类型 -> 槽位集合（权威来源为数据包 entities 文件）；
    // TOUCHED：被 set/add 留痕的单个实体 key（UUID#槽位）；DEFAULTS：各 (类型,槽位) 默认数量。
    // 只在 register / set / add / 首次 tick 时刷新，扫描轮次不再碰磁盘。
    private static Map<EntityType<?>, Set<String>> REGISTERED = new HashMap<>();
    private static Set<String> TOUCHED = new HashSet<>();
    private static Map<String, Integer> DEFAULTS = new HashMap<>();
    private static boolean cachePrimed = false;
    // 当前存档是否已应用过全局配置。已应用的存档在玩家修改默认值后不会再被全局配置反复覆盖，
    // 直到玩家用 clear（清空配置）删掉现有配置后，才按全局配置重新应用。
    private static boolean GLOBAL_APPLIED = false;

    private static void loadState(MinecraftServer server) {
        TOUCHED = new HashSet<>();
        DEFAULTS = new HashMap<>();
        Path p = statePath(server);
        if (!Files.exists(p)) return;
        try (InputStreamReader r = new InputStreamReader(Files.newInputStream(p), StandardCharsets.UTF_8)) {
            JsonObject obj = JsonParser.parseReader(r).getAsJsonObject();
            if (obj.has("touched")) {
                for (JsonElement e : obj.getAsJsonArray("touched")) {
                    TOUCHED.add(e.getAsString());
                }
            }
            if (obj.has("defaults")) {
                JsonObject def = obj.getAsJsonObject("defaults");
                for (Map.Entry<String, JsonElement> e : def.entrySet()) {
                    DEFAULTS.put(e.getKey(), e.getValue().getAsInt());
                }
            }
            if (obj.has("globalApplied")) {
                GLOBAL_APPLIED = obj.get("globalApplied").getAsBoolean();
            }
        } catch (Exception ignored) {
        }
    }

    private static void saveState(MinecraftServer server) {
        try {
            Path p = statePath(server);
            Files.createDirectories(p.getParent());
            JsonObject root = new JsonObject();
            JsonArray arr = new JsonArray();
            for (String s : TOUCHED) arr.add(s);
            root.add("touched", arr);
            JsonObject def = new JsonObject();
            for (Map.Entry<String, Integer> e : DEFAULTS.entrySet()) {
                def.addProperty(e.getKey(), e.getValue());
            }
            root.add("defaults", def);
            root.addProperty("globalApplied", GLOBAL_APPLIED);
            Files.write(p, new Gson().toJson(root).getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }

    private static void refreshCaches(MinecraftServer server) {
        REGISTERED = scanRegistered(server);
        loadState(server); // 刷新 TOUCHED / DEFAULTS
        // 补充：凡是设过默认数量的 (类型,槽位)——即使该类型原本已拥有此槽位（register 只更新默认、未写数据包），
        // 也纳入扫描清单，让生成钩子与兜底扫描能对其重载默认数量。
        for (String key : DEFAULTS.keySet()) {
            int idx = key.lastIndexOf('#');
            if (idx <= 0 || idx >= key.length() - 1) continue;
            EntityType<?> type = ForgeRegistries.ENTITY_TYPES
                    .getValue(ResourceLocation.tryParse(key.substring(0, idx)));
            if (type != null) {
                REGISTERED.computeIfAbsent(type, k -> new HashSet<>()).add(key.substring(idx + 1));
            }
        }
        cachePrimed = true;
    }

    // 留痕（单个实体）：记录这一只生物 (UUID,槽位) 已被 set/add 手动修改，之后不再强制默认数量。
    public static void markTouched(MinecraftServer server, LivingEntity le, String slot) {
        if (!cachePrimed) refreshCaches(server);
        TOUCHED.add(keyOfEntity(le, slot));
        saveState(server);
    }

    // 全局重置：清除所有留痕（UUID 白名单），所有被 set/add 改过的生物都不再受保护，
    // 并立即把已加载实体的栏位重置为类型默认 size（不再依赖周期兜底扫描，清除后即时生效）。
    public static void clearAllTouched(MinecraftServer server) {
        if (!cachePrimed) refreshCaches(server);
        TOUCHED.clear();
        saveState(server);
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity e : level.getEntities().getAll()) {
                if (e instanceof LivingEntity le) {
                    resetEntitySlotsToDefault(le);
                }
            }
        }
    }

    // 清空当前存档所有 curiosslot 数据包（entities 映射 + 状态文件 + pack.mcmeta），回归原始数据。
    // 删除后清空内存缓存，并按 autoReload 触发重载让 Curios 不再认这些映射。
    public static void clearAllConfig(MinecraftServer server) {
        Path packRoot = server.getWorldPath(LevelResource.DATAPACK_DIR).resolve("curiosslot");
        try {
            if (Files.exists(packRoot)) {
                try (java.util.stream.Stream<Path> stream = Files.walk(packRoot)) {
                    stream.sorted(java.util.Comparator.reverseOrder())
                            .forEach(p -> {
                                try {
                                    Files.deleteIfExists(p);
                                } catch (Exception ignored) {
                                }
                            });
                }
            }
        } catch (Exception ignored) {
        }
        // 清空内存缓存
        REGISTERED = new HashMap<>();
        TOUCHED = new HashSet<>();
        DEFAULTS = new HashMap<>();
        cachePrimed = true;
        // 需求：清空配置后，若存在全局配置，则改为全局配置；否则回归原始数据。
        if (hasGlobalConfig(server)) {
            try {
                applyGlobalConfigToWorld(server);
                return;
            } catch (Exception ignored) {
            }
        }
        // 无全局配置（或应用失败）：回归原始
        GLOBAL_APPLIED = false;
        saveState(server);
        // 重置所有已加载实体的 Curios 槽位为槽位类型默认 size（回归原始，清除 setSlotsForType 的持久化遗留）
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity e : level.getEntities().getAll()) {
                if (e instanceof LivingEntity le) {
                    resetEntitySlotsToDefault(le);
                }
            }
        }
        if (autoReload()) {
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "reload");
        }
    }

    // ========== 全局配置（跨存档共享的 curiosslot 数据包配置）==========
    // 全局配置存于游戏根目录 curiosslot_global/curiosslot/（含 pack.mcmeta、data/curiosslot/curios/entities/*.json、
    // curiosslot_state.json 的 defaults）。saveglobal 保存当前存档配置；clearglobal 清除全局配置；
    // onServerStarted 与 clear 后会自动把全局配置应用到当前存档（仅对未标记 globalApplied 的存档套用）。

    private static Path globalConfigRoot(MinecraftServer server) {
        return server.getServerDirectory().toPath().resolve("curiosslot_global");
    }

    public static boolean hasGlobalConfig(MinecraftServer server) {
        return Files.exists(globalConfigRoot(server).resolve("curiosslot").resolve("pack.mcmeta"));
    }

    private static void setGlobalApplied(MinecraftServer server, boolean v) {
        GLOBAL_APPLIED = v;
        saveState(server);
    }

    /** /curiosslot saveglobal：把当前存档的 curiosslot 数据包配置（entities 映射 + defaults）保存为全局配置。 */
    public static int saveGlobalConfig(CommandSourceStack src) {
        MinecraftServer server = src.getServer();
        try {
            Path packRoot = server.getWorldPath(LevelResource.DATAPACK_DIR).resolve("curiosslot");
            if (!Files.exists(packRoot.resolve("pack.mcmeta"))) {
                src.sendFailure(Component.literal("当前存档尚未创建任何 curiosslot 配置（请先用 /curiosslot register 创建栏位）。"));
                return 0;
            }
            Path global = globalConfigRoot(server).resolve("curiosslot");
            if (Files.exists(global)) deleteRecursive(global);
            copyRecursive(packRoot.resolve("data"), global.resolve("data"));
            Files.copy(packRoot.resolve("pack.mcmeta"), global.resolve("pack.mcmeta"),
                    StandardCopyOption.REPLACE_EXISTING);
            // 仅保存 defaults（跨存档有效），不含单实体留痕 touched（UUID 无跨存档意义）
            JsonObject gstate = new JsonObject();
            Gson gson = new Gson();
            try {
                if (Files.exists(packRoot.resolve("curiosslot_state.json"))) {
                    JsonObject st = gson.fromJson(
                            new InputStreamReader(Files.newInputStream(packRoot.resolve("curiosslot_state.json")), StandardCharsets.UTF_8),
                            JsonObject.class);
                    if (st != null && st.has("defaults")) gstate.add("defaults", st.getAsJsonObject("defaults"));
                }
            } catch (Exception ignored) {
            }
            if (!gstate.has("defaults")) gstate.add("defaults", new JsonObject());
            Files.write(global.resolve("curiosslot_state.json"),
                    gson.toJson(gstate).getBytes(StandardCharsets.UTF_8));
            src.sendSuccess(() -> Component.literal("已把当前存档的 curiosslot 配置保存为全局配置。"
                    + "新存档 / 尚未应用全局配置的存档进入时，将自动套用该全局配置。"), true);
            return 1;
        } catch (Exception e) {
            src.sendFailure(Component.literal("[curiosslot] saveglobal 出错: " + e.getMessage()));
            return 0;
        }
    }

    /** /curiosslot clearglobal：清除已保存的全局配置（不主动改动各存档内现有配置）。 */
    public static int clearGlobalConfig(CommandSourceStack src) {
        MinecraftServer server = src.getServer();
        Path global = globalConfigRoot(server);
        if (!Files.exists(global)) {
            src.sendFailure(Component.literal("当前没有已保存的全局配置。"));
            return 0;
        }
        try {
            deleteRecursive(global);
        } catch (Exception e) {
            src.sendFailure(Component.literal("[curiosslot] clearglobal 出错: " + e.getMessage()));
            return 0;
        }
        src.sendSuccess(() -> Component.literal("已清除全局配置。各存档现有的配置不会被改动；"
                + "如需让某个存档回归原始，用 /curiosslot clear 清空其配置。"), true);
        return 1;
    }

    /** 把全局配置应用到当前存档：清空存档现有 curiosslot 数据包 → 复制全局配置 → 标记 globalApplied → reload。 */
    public static void applyGlobalConfigToWorld(MinecraftServer server) throws java.io.IOException {
        // 全局配置只覆盖"类型默认栏位"，不应清掉单实体手动修改的留痕（touched）。
        // 清空数据包会连 curiosslot_state.json 一起删，先暂存当前 TOUCHED，套用后再写回。
        if (!cachePrimed) refreshCaches(server);
        Set<String> keepTouched = new HashSet<>(TOUCHED);
        Path global = globalConfigRoot(server).resolve("curiosslot");
        Path packRoot = server.getWorldPath(LevelResource.DATAPACK_DIR).resolve("curiosslot");
        if (Files.exists(packRoot)) deleteRecursive(packRoot);
        copyRecursive(global, packRoot);
        // 必须先 refreshCaches 加载全局 state 的 defaults，再标记 globalApplied：
        // setGlobalApplied 的 saveState 会用内存 DEFAULTS 写回 state.json，若先标记会覆盖刚复制的全局 defaults。
        refreshCaches(server);
        // 恢复单实体保护留痕（全局配置不含 touched，套用后不应丢失手动保护）
        TOUCHED.addAll(keepTouched);
        setGlobalApplied(server, true);
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity e : level.getEntities().getAll()) {
                if (e instanceof LivingEntity le) resetEntitySlotsToDefault(le);
            }
        }
        if (autoReload()) {
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "reload");
        }
    }

    // 把单个实体的所有 Curios 槽位重置为槽位类型默认 size（清除 setSlotsForType 的持久化遗留，回归原始）。
    public static void resetEntitySlotsToDefault(LivingEntity le) {
        var inv = CuriosApi.getCuriosInventory(le);
        if (!inv.isPresent()) return;
        ICuriosItemHandler handler = inv.resolve().orElse(null);
        if (handler == null) return;
        for (Map.Entry<String, ICurioStacksHandler> en : handler.getCurios().entrySet()) {
            // 该实体的该槽位已被 set/add 手动留痕：尊重手动设置，不重置。
            if (TOUCHED.contains(keyOfEntity(le, en.getKey()))) continue;
            int def = CuriosApi.getSlot(en.getKey())
                    .map(top.theillusivec4.curios.api.type.ISlotType::getSize).orElse(1);
            if (en.getValue().getSlots() != def) {
                CuriosApi.getSlotHelper().setSlotsForType(en.getKey(), le, def);
            }
        }
    }

    // 记录/更新某 (实体类型, 槽位) 的默认栏位数量。
    public static void setDefault(MinecraftServer server, EntityType<?> type, String slot, int count) {
        if (!cachePrimed) refreshCaches(server);
        DEFAULTS.put(keyOf(type, slot), count);
        saveState(server);
    }

    // 该 (实体类型, 槽位) 的默认数量。
    // 未 register 覆盖时回退到 Curios 配置的真实默认（有的槽位默认不止 1，玩家也可能改过 config，不硬编码 1）。
    private static int defaultCount(EntityType<?> type, String slot) {
        Integer d = DEFAULTS.get(keyOf(type, slot));
        if (d != null) return d;
        return CuriosApi.getSlot(slot)
                .map(top.theillusivec4.curios.api.type.ISlotType::getSize)
                .orElse(1);
    }

    /** 目标实体所有已有栏位对应的"该实体类型默认数量"，供调试界面显示。 */
    public static Map<String, Integer> collectDefaults(LivingEntity living) {
        Map<String, Integer> defs = new LinkedHashMap<>();
        var inv = CuriosApi.getCuriosInventory(living);
        if (inv.isPresent()) {
            ICuriosItemHandler h = inv.resolve().orElse(null);
            if (h != null) {
                for (String s : h.getCurios().keySet()) {
                    defs.put(s, defaultCount(living.getType(), s));
                }
            }
        }
        return defs;
    }

    // 扫描自己写的数据包 entities 文件，得到 实体类型 -> 槽位集合（"已注册"的权威来源）。
    private static Map<EntityType<?>, Set<String>> scanRegistered(MinecraftServer server) {
        Map<EntityType<?>, Set<String>> map = new HashMap<>();
        Path dir = server.getWorldPath(LevelResource.DATAPACK_DIR)
                .resolve("curiosslot/data/curiosslot/curios/entities");
        if (!Files.isDirectory(dir)) return map;
        try (java.util.stream.Stream<Path> stream = Files.list(dir)) {
            stream.filter(p -> p.toString().endsWith(".json")).forEach(file -> {
                try (InputStreamReader r = new InputStreamReader(Files.newInputStream(file), StandardCharsets.UTF_8)) {
                    JsonObject obj = JsonParser.parseReader(r).getAsJsonObject();
                    for (JsonElement ent : obj.getAsJsonArray("entities")) {
                        EntityType<?> type = ForgeRegistries.ENTITY_TYPES.getValue(ResourceLocation.tryParse(ent.getAsString()));
                        if (type == null) continue;
                        Set<String> slots = map.computeIfAbsent(type, k -> new HashSet<>());
                        for (JsonElement s : obj.getAsJsonArray("slots")) {
                            slots.add(s.getAsString());
                        }
                    }
                } catch (Exception ignored) {
                }
            });
        } catch (Exception ignored) {
        }
        return map;
    }

    // ========== 服务端启动完成：强制用当前(新)存档的数据重建缓存 ==========
    // static 缓存（REGISTERED/DEFAULTS/TOUCHED）跨存档不清空，单机切档/重进时服务端重启但 static 保留旧值，
    // 必须在每个新服务端启动时刷新，否则会沿用上一个存档的默认栏位设定。
    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        // 确保图标输入文件夹存在（游戏根目录 curiosslot_slots/icons/），方便玩家放入栏位图标
        try {
            Files.createDirectories(server.getServerDirectory().toPath()
                    .resolve("curiosslot_slots").resolve("icons"));
        } catch (Exception ignored) {
        }
        // 把全局数据包仓库复制到当前世界（新存档/重进自动带上已注册的全局栏位）
        try {
            syncGlobalToWorld(server);
        } catch (Exception ignored) {
        }
        refreshCaches(server); // 读取当前存档 state（含 globalApplied 标记）
        // 需求：已保存全局配置且当前存档尚未应用过（新存档 / 未套用的存档）时，自动套用全局配置。
        // 已标记 globalApplied 的存档（玩家在存档内改过默认值）不会被全局配置反复覆盖。
        try {
            if (hasGlobalConfig(server) && !GLOBAL_APPLIED) {
                applyGlobalConfigToWorld(server);
            }
        } catch (Exception ignored) {
        }
    }

    // ========== 生成时挂钩（主机制）：实体一进世界，就把"已注册且未留痕"的槽位数量设为默认值 ==========
    // 这样新实体生成时直接就是默认数量（通常 1），扫描轮次几乎永远不需要重设，避免大批量重设卡顿。
    @SubscribeEvent
    public void onEntityJoinLevel(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) return;
        if (!(event.getEntity() instanceof LivingEntity le)) return;
        MinecraftServer server = ((ServerLevel) event.getLevel()).getServer();
        if (server == null) return;
        if (!cachePrimed) refreshCaches(server);
        EntityType<?> type = le.getType();
        Set<String> slots = REGISTERED.get(type);
        if (slots == null || slots.isEmpty()) {
            // 全局 REGISTERED 为空 = 已清空配置 / 从未注册：把该实体（含离线玩家遗留的持久化槽位数量）
            // 全部重置为 Curios 槽位类型默认 size，回归原始。
            if (REGISTERED.isEmpty()) {
                resetEntitySlotsToDefault(le);
            }
            return;
        }
        ISlotHelper slotHelper = CuriosApi.getSlotHelper();
        for (String slot : slots) {
            if (TOUCHED.contains(keyOfEntity(le, slot))) continue; // 该个体已留痕，尊重手动设置
            int def = defaultCount(type, slot);
            var inv = CuriosApi.getCuriosInventory(le);
            if (!inv.isPresent()) continue;
            ICuriosItemHandler handler = inv.resolve().orElse(null);
            if (handler == null) continue;
            var stacksOpt = handler.getStacksHandler(slot);
            if (stacksOpt.isEmpty()) continue;
            if (stacksOpt.get().getSlots() != def) {
                slotHelper.setSlotsForType(slot, le, def);
            }
        }
    }

    private static int sweepTicks = 0;

    // 每 sweepIntervalSeconds 秒：兜底扫描——主要处理 register 之前就已存在、且未能被生成钩子覆盖的实体。
    // 数据来自缓存（仅 register/set/add/首次 tick 刷新），实体用按类型索引查询，开销很小；
    // 因生成钩子已让新实体出生即为默认数量，稳态下几乎不会有重设动作，不会出现大批量重设卡顿。
    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        MinecraftServer server = event.getServer();

        int interval = SWEEP_INTERVAL_SECONDS.get() * 20; // 秒 -> tick
        if (interval <= 0) return; // 设为 0 表示关闭兜底扫描
        if (++sweepTicks % interval != 0) return;
        if (!cachePrimed) refreshCaches(server);
        if (REGISTERED.isEmpty()) return;
        for (Map.Entry<EntityType<?>, Set<String>> entry : REGISTERED.entrySet()) {
            EntityType<?> type = entry.getKey();
            for (String slot : entry.getValue()) {
                enforceSlotCount(server, type, slot, defaultCount(type, slot));
            }
        }
    }

    // 把该类型所有"未留痕"实体的该槽位数量设为给定默认值。
    // EntityType 本身实现了 EntityTypeTest，可直接作为按类型索引查询的过滤器。
    private static void enforceSlotCount(MinecraftServer server, EntityType<?> type, String slot, int def) {
        ISlotHelper slotHelper = CuriosApi.getSlotHelper();
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity e : level.getEntities(type, ent -> ent instanceof LivingEntity)) {
                LivingEntity le = (LivingEntity) e;
                if (TOUCHED.contains(keyOfEntity(le, slot))) continue; // 该个体已留痕，尊重手动设置
                var inv = CuriosApi.getCuriosInventory(le);
                if (!inv.isPresent()) continue;
                ICuriosItemHandler handler = inv.resolve().orElse(null);
                if (handler == null) continue;
                var stacksOpt = handler.getStacksHandler(slot);
                if (stacksOpt.isEmpty()) continue;
                if (stacksOpt.get().getSlots() != def) {
                    slotHelper.setSlotsForType(slot, le, def);
                }
            }
        }
    }

    public CuriosSlotMod(FMLJavaModLoadingContext context) {
        IEventBus modEventBus = context.getModEventBus();
        CuriosSlotNetworking.register();
        MinecraftForge.EVENT_BUS.register(this);
        MinecraftForge.EVENT_BUS.register(ClientPackEvents.class);
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, CONFIG_SPEC);
    }

    /** 向单个客户端下发当前生成的 curiosslot_slots 资源包（服务端已有 zip 时），供其写盘加载。 */
    public static void sendResourcePackToClient(ServerPlayer sp) {
        try {
            Path zip = sp.server.getServerDirectory().toPath()
                    .resolve("resourcepacks").resolve("curiosslot_slots.zip");
            if (!Files.exists(zip)) return;
            CuriosSlotNetworking.CHANNEL.send(PacketDistributor.PLAYER.with(() -> sp),
                    new SyncResourcePackPacket(Files.readAllBytes(zip)));
        } catch (Exception ignored) {
        }
    }

    /** 把当前生成的 curiosslot_slots 资源包广播给所有在线玩家（专用服务器/单机联机均可）。 */
    public static void broadcastResourcePack(MinecraftServer server) {
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            sendResourcePackToClient(p);
        }
    }

    /** 登录时把已生成的资源包同步给该客户端，保证后加入的玩家也能正常显示图标与中文名。 */
    @SubscribeEvent
    public void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp && !sp.level().isClientSide()) {
            sendResourcePackToClient(sp);
        }
    }

    /** 客户端启用生成的 curiosslot_slots 资源包（槽位图标 + 中文名）。可被 EnablePackPacket 与进世界兜底调用。 */
    public static void enableCustomPack() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null) return;
            PackRepository repo = mc.getResourcePackRepository();
            if (repo == null) return;
            repo.reload();
            String fileId = "file/curiosslot_slots.zip";
            // 实际 pack id 可能带或不带 .zip 后缀，用包含匹配以兼容两种
            var packOpt = repo.getAvailablePacks().stream()
                    .filter(p -> p.getId().contains("curiosslot_slots")).findFirst();
            if (packOpt.isPresent()) {
                String pid = packOpt.get().getId();
                if (!repo.getSelectedIds().contains(pid)) {
                    Set<String> sel = new HashSet<>(repo.getSelectedIds());
                    sel.add(pid);
                    repo.setSelected(sel);
                    // 持久化用 Minecraft 标准的 file/ 前缀格式，下次启动才会自动加载
                    if (!mc.options.resourcePacks.contains(fileId)) {
                        mc.options.resourcePacks.add(fileId);
                        mc.options.save();
                    }
                    mc.reloadResourcePacks();
                }
            }
        } catch (Exception ignored) {
        }
    }

    /** 客户端进世界时兜底启用 curiosslot_slots 资源包，保证重启/重进存档后图标与中文名必然生效。 */
    public static final class ClientPackEvents {
        @net.minecraftforge.eventbus.api.SubscribeEvent
        public static void onClientJoin(net.minecraftforge.client.event.ClientPlayerNetworkEvent.LoggingIn ev) {
            enableCustomPack();
        }
    }

    /** 单机 registerslot 时，把 curiosslot_slots 持久化进 options.txt 的 resourcePacks 列表，使下次启动自动加载。
     *  注意必须用 Minecraft 标准的 file/ 前缀格式（file/curiosslot_slots.zip），裸 id 启动时不会被正确匹配。 */
    private static void addToOptionsTxt(Path options) {
        try {
            if (!Files.exists(options)) return;
            List<String> lines = Files.readAllLines(options, StandardCharsets.UTF_8);
            boolean changed = false;
            Gson g = new Gson();
            String fileId = "file/curiosslot_slots.zip";
            for (int i = 0; i < lines.size(); i++) {
                String ln = lines.get(i);
                if (ln.startsWith("resourcePacks:")) {
                    String arr = ln.substring("resourcePacks:".length());
                    JsonArray ja = null;
                    try {
                        ja = g.fromJson(arr, JsonArray.class);
                    } catch (Exception ignored) {
                    }
                    if (ja == null) ja = new JsonArray();
                    boolean has = false;
                    for (JsonElement e : ja) {
                        if (e.isJsonPrimitive() && e.getAsString().contains("curiosslot_slots")) {
                            has = true;
                            break;
                        }
                    }
                    if (!has) {
                        ja.add(fileId);
                        lines.set(i, "resourcePacks:" + g.toJson(ja));
                        changed = true;
                    }
                    break;
                }
            }
            if (changed) {
                Files.write(options, lines, StandardCharsets.UTF_8);
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * 把某槽位写入世界数据包 {@code curios/entities} 映射（对目标的整个生物类型生效），
     * 记录默认数量，并按配置触发数据重载。供调试界面（{@code DebugActionPacket}）调用。
     *
     * @return 是否触发了数据重载（受配置 autoReload 控制）。
     */
    public static boolean applyCreateSlot(ServerLevel level, EntityType<?> type, String slot, int count) throws Exception {
        MinecraftServer server = level.getServer();
        Path packRoot = server.getWorldPath(LevelResource.DATAPACK_DIR).resolve("curiosslot");
        Path dir = packRoot.resolve("data/curiosslot/curios/entities");
        Files.createDirectories(dir);

        // 数据包根目录必须有 pack.mcmeta，否则不会被 Minecraft 识别为数据包。
        Path mcmeta = packRoot.resolve("pack.mcmeta");
        if (!Files.exists(mcmeta)) {
            Files.write(mcmeta,
                    ("{\"pack\":{\"pack_format\":15,\"description\":\"curiosslot dynamic datapack\"}}")
                            .getBytes(StandardCharsets.UTF_8));
        }

        String typeId = EntityType.getKey(type).toString();
        String safe = typeId.replace(':', '_');
        Path file = dir.resolve(safe + ".json");

        Gson gson = new Gson();
        JsonObject root;
        Set<String> slots = new LinkedHashSet<>();
        if (Files.exists(file)) {
            root = gson.fromJson(new InputStreamReader(Files.newInputStream(file), StandardCharsets.UTF_8),
                    JsonObject.class);
            if (root == null || !root.has("slots") || !root.get("slots").isJsonArray()) {
                root = new JsonObject();
            } else {
                for (JsonElement e : root.getAsJsonArray("slots")) {
                    slots.add(e.getAsString());
                }
            }
        } else {
            root = new JsonObject();
        }

        slots.add(slot);
        JsonArray arr = new JsonArray();
        for (String s : slots) {
            arr.add(s);
        }
        root.add("entities", gson.toJsonTree(List.of(typeId)));
        root.add("slots", arr);
        Files.write(file, gson.toJson(root).getBytes(StandardCharsets.UTF_8));

        boolean reloaded = false;
        if (autoReload()) {
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "reload");
            reloaded = true;
        }
        setDefault(server, type, slot, count);
        refreshCaches(server);
        return reloaded;
    }

    /** 收集某实体当前已有的栏位及数量（槽位名 -> 数量）。 */
    private static Map<String, Integer> collectExisting(LivingEntity living) {
        Map<String, Integer> map = new LinkedHashMap<>();
        var inv = CuriosApi.getCuriosInventory(living);
        if (inv.isPresent()) {
            ICuriosItemHandler handler = inv.resolve().orElse(null);
            if (handler != null) {
                for (Map.Entry<String, ICurioStacksHandler> e : handler.getCurios().entrySet()) {
                    map.put(e.getKey(), e.getValue().getSlots());
                }
            }
        }
        return map;
    }

    /** 收集该实体可新建的已注册槽位（全部已注册槽位，排除它已有的）。 */
    private static List<String> collectCreatable(LivingEntity living) {
        Map<String, Integer> existing = collectExisting(living);
        List<String> creatable = new ArrayList<>();
        for (String s : CuriosApi.getSlotHelper().getSlotTypeIds()) {
            if (!existing.containsKey(s)) {
                creatable.add(s);
            }
        }
        return creatable;
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("curiosslot")
                        .requires(src -> src.hasPermission(2))
                        .then(Commands.literal("clear")
                                .executes(ctx -> {
                                    clearAllConfig(ctx.getSource().getServer());
                                    ctx.getSource().sendSuccess(
                                            () -> Component.literal(hasGlobalConfig(ctx.getSource().getServer())
                                                    ? "已清空当前存档配置并套用全局配置。"
                                                    : "已清空当前存档所有 curiosslot 数据包，回归原始数据。"),
                                            true);
                                    return 1;
                                }))
                        .then(Commands.literal("reset")
                                .executes(ctx -> {
                                    clearAllTouched(ctx.getSource().getServer());
                                    ctx.getSource().sendSuccess(
                                            () -> Component.literal("已清除所有留痕（UUID 记录），"
                                                    + "所有被 set/add 改过的生物将被重置回默认数量。"),
                                            true);
                                    return 1;
                                }))
                        .then(Commands.literal("saveglobal")
                                .executes(ctx -> CuriosSlotMod.saveGlobalConfig(ctx.getSource())))
                        .then(Commands.literal("clearglobal")
                                .executes(ctx -> CuriosSlotMod.clearGlobalConfig(ctx.getSource())))
                        .then(Commands.literal("set")
                                .then(Commands.argument("slot", StringArgumentType.word())
                                        .then(Commands.argument("count", IntegerArgumentType.integer())
                                                .then(Commands.argument("target", EntityArgument.entity())
                                                        .executes(ctx -> run(ctx, "set", false)))
                                                .then(Commands.literal("nearest")
                                                        .executes(ctx -> run(ctx, "set", true))))))
                        .then(Commands.literal("add")
                                .then(Commands.argument("slot", StringArgumentType.word())
                                        .then(Commands.argument("count", IntegerArgumentType.integer())
                                                .then(Commands.argument("target", EntityArgument.entity())
                                                        .executes(ctx -> run(ctx, "add", false)))
                                                .then(Commands.literal("nearest")
                                                        .executes(ctx -> run(ctx, "add", true))))))
                        .then(Commands.literal("register")
                                .then(Commands.argument("slot", StringArgumentType.word())
                                        .then(Commands.argument("type", StringArgumentType.string())
                                                .executes(ctx -> registerType(ctx, 1))
                                                .then(Commands.argument("count", IntegerArgumentType.integer(1, 64))
                                                        .executes(ctx -> registerType(ctx,
                                                                IntegerArgumentType.getInteger(ctx, "count")))))
                                        .then(Commands.literal("from")
                                                .then(Commands.argument("target", EntityArgument.entity())
                                                        .executes(ctx -> registerSlot(ctx, 1, false))
                                                        .then(Commands.argument("count", IntegerArgumentType.integer(1, 64))
                                                                .executes(ctx -> registerSlot(ctx,
                                                                        IntegerArgumentType.getInteger(ctx, "count"), false))))
                                                .then(Commands.literal("nearest")
                                                        .executes(ctx -> registerSlot(ctx, 1, true))
                                                        .then(Commands.argument("count", IntegerArgumentType.integer(1, 64))
                                                                .executes(ctx -> registerSlot(ctx,
                                                                        IntegerArgumentType.getInteger(ctx, "count"), true)))))))
                        .then(Commands.literal("registerslot")
                                .requires(src -> canManageSlots(src))
                                .then(Commands.argument("id", StringArgumentType.word())
                                        .executes(ctx -> registerSlotType(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "id"), null))
                                        .then(Commands.argument("displayName", StringArgumentType.greedyString())
                                                .executes(ctx -> registerSlotType(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "id"),
                                                        StringArgumentType.getString(ctx, "displayName"))))))
                        .then(Commands.literal("unregisterslot")
                                .requires(src -> canManageSlots(src))
                                .then(Commands.argument("id", StringArgumentType.word())
                                        .executes(ctx -> unregisterSlotType(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "id")))))
                        .then(Commands.literal("open")
                                .then(Commands.argument("target", EntityArgument.entity())
                                        .executes(ctx -> open(ctx, false)))
                                .then(Commands.literal("nearest")
                                        .executes(ctx -> open(ctx, true))))
        );
    }

    /** 最近的一个非玩家实体（只在执行者所处的维度内查找，按到执行者的距离）。无则返回 null。 */
    private static LivingEntity nearestNonPlayer(CommandSourceStack src) {
        var pos = src.getPosition();
        LivingEntity best = null;
        double bestD = Double.MAX_VALUE;
        for (Entity e : src.getLevel().getEntities().getAll()) {
            if (!(e instanceof LivingEntity le)) continue;
            if (le instanceof net.minecraft.world.entity.player.Player) continue;
            double d = le.distanceToSqr(pos);
            if (d < bestD) {
                bestD = d;
                best = le;
            }
        }
        return best;
    }

    /**
     * /curiosslot open <目标实体|nearest>
     * <p>
     * 为操作者打开目标实体的调试界面（纯客户端 GUI，由数据包驱动）。
     * 若操作者是专用服务器上未安装 curiosslot 的客户端，则无法显示 GUI，仅给出提示。
     */
    private int open(CommandContext<CommandSourceStack> ctx, boolean useNearest) {
        CommandSourceStack src = ctx.getSource();
        try {
            LivingEntity living;
            if (useNearest) {
                living = nearestNonPlayer(src);
                if (living == null) {
                    src.sendFailure(Component.literal("没有找到任何非玩家实体。"));
                    return 0;
                }
            } else {
                Entity target = EntityArgument.getEntity(ctx, "target");
                if (!(target instanceof LivingEntity le)) {
                    src.sendFailure(Component.literal("目标不是 LivingEntity，无法打开调试界面。"));
                    return 0;
                }
                living = le;
            }

            if (!(src.getEntity() instanceof ServerPlayer sp)) {
                src.sendFailure(Component.literal("只有玩家能打开调试界面。"));
                return 0;
            }
            openFor(sp, living);
            return 1;
        } catch (Exception e) {
            src.sendFailure(Component.literal("[curiosslot] open 出错: " + e.getMessage()));
            return 0;
        }
    }

    /** 注册/删除全新槽位仅服务端侧可用：
     *  专用服务器：仅服务端 console（无实体执行者）可执行，联机客户端（即使 4 级管理员）不可用；
     *  单机集成服务器：仅主机玩家可用。 */
    private static boolean canManageSlots(CommandSourceStack src) {
        if (!src.hasPermission(4)) return false;
        MinecraftServer server = src.getServer();
        if (server.isDedicatedServer()) {
            return src.getEntity() == null;
        }
        if (src.getEntity() instanceof net.minecraft.world.entity.player.Player p) {
            return server.isSingleplayerOwner(p.getGameProfile());
        }
        return false;
    }

    private static boolean canManageSlots(ServerPlayer sp) {
        if (!sp.hasPermissions(4)) return false;
        MinecraftServer server = sp.server;
        if (server.isDedicatedServer()) return false;
        return server.isSingleplayerOwner(sp.getGameProfile());
    }

    /** 打开某实体的调试界面（open 指令与"饰品配置手杖"右键共用）。 */
    private static boolean openFor(ServerPlayer sp, LivingEntity living) {
        try {
            UUID uuid = living.getUUID();
            String name = living.getName().getString();
            Map<String, Integer> existing = collectExisting(living);
            Map<String, Integer> defaults = collectDefaults(living);
            List<String> creatable = collectCreatable(living);
            CuriosSlotNetworking.CHANNEL.send(
                    PacketDistributor.PLAYER.with(() -> sp),
                    new DebugOpenPacket(uuid, name, existing, defaults, creatable, canManageSlots(sp)));
            // 不再发送"已打开"提示：客户端未安装 curiosslot 时收不到该包，提示会误导（GUI 实际未弹出）
            return true;
        } catch (Exception e) {
            sp.sendSystemMessage(Component.literal("[curiosslot] 打开调试界面出错: " + e.getMessage()));
            return false;
        }
    }

    /** 是否为激活的饰品配置手杖：绊线勾且在铁砧重命名为"饰品配置手杖"。 */
    private static boolean isSlotWand(ItemStack stack) {
        if (stack.getItem() != Items.TRIPWIRE_HOOK) return false;
        net.minecraft.network.chat.Component name = stack.getHoverName();
        return name != null && "饰品配置手杖".equals(name.getString());
    }

    /**
     * 手持"饰品配置手杖"（绊线勾重命名）右键实体时，直接打开该实体的调试界面。
     * 目标就是右键的那个实体（玩家或生物），不取最近、不排除玩家。
     */
    @SubscribeEvent
    public void onWandInteract(PlayerInteractEvent.EntityInteractSpecific event) {
        if (event.getLevel().isClientSide()) return;
        if (!(event.getEntity() instanceof ServerPlayer sp)) return;
        if (!isSlotWand(event.getItemStack())) return;
        if (!(event.getTarget() instanceof LivingEntity le)) return;
        event.setCanceled(true); // 取消对实体的原版交互（绊线勾右键实体本无行为）
        openFor(sp, le);
    }

    /** 饰品配置手杖的工具提示：提示悬停玩家确认改名正确（客户端显示）。 */
    @SubscribeEvent
    public void onItemTooltip(ItemTooltipEvent event) {
        if (!isSlotWand(event.getItemStack())) return;
        event.getToolTip().add(Component.literal("§a饰品配置手杖：右键实体（含玩家）打开其饰品配置界面"));
    }

    /**
     * /curiosslot register <槽位> from <目标实体>
     * <p>
     * 取目标的生物类型，把该槽位写入世界数据包 {@code curios/entities} 映射，
     * 使<strong>该类型的所有生物</strong>都拥有该饰品栏位，随后触发数据重载。
     */
    private int registerSlot(CommandContext<CommandSourceStack> ctx, int count, boolean useNearest) {
        CommandSourceStack src = ctx.getSource();
        try {
            String slot = StringArgumentType.getString(ctx, "slot");
            LivingEntity living;
            if (useNearest) {
                living = nearestNonPlayer(src);
                if (living == null) {
                    src.sendFailure(Component.literal("没有找到任何非玩家实体。"));
                    return 0;
                }
            } else {
                Entity target = EntityArgument.getEntity(ctx, "target");
                if (!(target instanceof LivingEntity le)) {
                    src.sendFailure(Component.literal("目标不是 LivingEntity，无法注册槽位。"));
                    return 0;
                }
                living = le;
            }

            String typeId = EntityType.getKey(living.getType()).toString(); // 如 minecraft:cow
            return registerForType(src, slot, typeId, count);
        } catch (Exception e) {
            src.sendFailure(Component.literal("[curiosslot] register 出错: " + e.getMessage()));
            return 0;
        }
    }

    /**
     * /curiosslot register <槽位> <实体类型ID> [数量]
     * <p>
     * 直接按实体类型注册槽位，无需指定已生成的实体。例如：
     *   /curiosslot register ring minecraft:cow 2
     * 数量可选：不填默认 1，填写后该类型所有未留痕实体的该栏位数量将保持为这个默认值。
     */
    private int registerType(CommandContext<CommandSourceStack> ctx, int count) {
        CommandSourceStack src = ctx.getSource();
        try {
            String slot = StringArgumentType.getString(ctx, "slot");
            String typeId = StringArgumentType.getString(ctx, "type");

            ResourceLocation rl = ResourceLocation.tryParse(typeId);
            if (rl == null) {
                src.sendFailure(Component.literal("实体类型ID格式不正确: " + typeId));
                return 0;
            }
            EntityType<?> et = ForgeRegistries.ENTITY_TYPES.getValue(rl);
            if (et == null) {
                src.sendFailure(Component.literal("实体类型不存在: " + typeId));
                return 0;
            }
            return registerForType(src, slot, rl.toString(), count);
        } catch (Exception e) {
            src.sendFailure(Component.literal("[curiosslot] register 出错: " + e.getMessage()));
            return 0;
        }
    }

    /** 把槽位写入世界数据包 curios/entities 映射、记录默认数量并触发数据重载（对目标的整个生物类型生效）。 */
    private int registerForType(CommandSourceStack src, String slot, String typeId, int count) {
        try {
            ISlotHelper slotHelper = CuriosApi.getSlotHelper();
            if (slotHelper.getSlotType(slot).isEmpty()) {
                src.sendFailure(Component.literal("槽位类型未注册: " + slot));
                return 0;
            }

            // 无论该类型是否原本已拥有此槽位，register 一律写数据包 entities 映射 + 记录默认数量，
            // 让 Curios 认识该 (类型,槽位)，再叠加默认数量（defaultCount 无覆盖时回退 Curios 全局配置）。
            EntityType<?> type = ForgeRegistries.ENTITY_TYPES.getValue(ResourceLocation.tryParse(typeId));

            var server = src.getServer();

            // 世界数据包目录：<世界文件夹>/datapacks/curiosslot/data/curiosslot/curios/entities/
            Path packRoot = server.getWorldPath(LevelResource.DATAPACK_DIR).resolve("curiosslot");
            Path dir = packRoot.resolve("data/curiosslot/curios/entities");
            Files.createDirectories(dir);

            // 关键：数据包根目录必须有 pack.mcmeta，否则该文件夹不会被 Minecraft 识别为数据包，
            // 里面所有映射都不会被加载。首次写入时自动创建。
            Path mcmeta = packRoot.resolve("pack.mcmeta");
            if (!Files.exists(mcmeta)) {
                Files.write(mcmeta,
                        ("{\"pack\":{\"pack_format\":15,\"description\":\"curiosslot dynamic datapack\"}}")
                                .getBytes(StandardCharsets.UTF_8));
            }

            String safe = typeId.replace(':', '_'); // minecraft:cow -> minecraft_cow
            Path file = dir.resolve(safe + ".json");

            Gson gson = new Gson();
            JsonObject root;
            Set<String> slots = new LinkedHashSet<>();
            if (Files.exists(file)) {
                root = gson.fromJson(new InputStreamReader(Files.newInputStream(file), StandardCharsets.UTF_8),
                        JsonObject.class);
                if (root == null || !root.has("slots") || !root.get("slots").isJsonArray()) {
                    root = new JsonObject();
                } else {
                    for (JsonElement e : root.getAsJsonArray("slots")) {
                        slots.add(e.getAsString());
                    }
                }
            } else {
                root = new JsonObject();
            }

            slots.add(slot);

            JsonArray arr = new JsonArray();
            for (String s : slots) {
                arr.add(s);
            }
            root.add("entities", gson.toJsonTree(List.of(typeId)));
            root.add("slots", arr);
            Files.write(file, gson.toJson(root).getBytes(StandardCharsets.UTF_8));

            // 触发数据重载（受配置 autoReload 控制），让 Curios 重新读取 entities 映射
            boolean reloaded = false;
            if (autoReload()) {
                server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "reload");
                reloaded = true;
            }
            // 记录默认数量并刷新缓存，让该 (类型, 槽位) 立即进入"按默认数量"的扫描。
            setDefault(server, type, slot, count);
            refreshCaches(server);

            String note = reloaded
                    ? "已触发数据重载，立即生效。"
                    : "已关闭自动重载：请执行 /reload 或重进存档后生效。";
            src.sendSuccess(
                    () -> Component.literal("已为实体类型 " + typeId + " 注册槽位 " + slot
                            + "（默认数量 " + count + "），将对该类型所有生物生效。" + note
                            + " 该类型当前槽位: " + slots
                            + " 注意：已生成且没有该饰品栏位的生物，需重新进入存档后才会加载该栏位。"),
                    true);
            return 1;
        } catch (Exception e) {
            src.sendFailure(Component.literal("[curiosslot] register 出错: " + e.getMessage()));
            return 0;
        }
    }

    /**
     * /curiosslot registerslot <id> [中文名]
     * <p>
     * 注册一个全新的 Curios 饰品栏位类型：写入数据包 data/curios/curios/slots/<id>.json；
     * 图标从游戏根目录 curiosslot_slots/icons/<id>.png 读取并作为槽位图标（客户端资源）；
     * 中文名写入本地化键 curios.identifier.<id>（GUI 显示）。
     * 单机/集成服务器：datapack 的 assets 自动作为资源加载；专用服务器：额外生成服务器资源包自动下发客户端。
     */
    public static int registerSlotType(CommandSourceStack src, String id, String displayName) {
        MinecraftServer server = src.getServer();
        if (!id.matches("[a-z0-9_\\-.]{1,64}")) {
            src.sendFailure(Component.literal("栏位 id 必须为小写字母/数字/下划线/连字符/点，长度 1~64。"));
            return 0;
        }
        Path gameRoot = server.getServerDirectory().toPath();
        Path iconsDir = gameRoot.resolve("curiosslot_slots").resolve("icons");
        Path iconSrc = iconsDir.resolve(id + ".png");
        if (!Files.exists(iconSrc)) {
            src.sendFailure(Component.literal("未找到图标 " + iconSrc
                    + "。请先把图标图片（文件名=栏位 id，如 " + id + ".png）放入该文件夹。"));
            return 0;
        }

        try {
            // 全局数据包目录（游戏根/datapacks/curiosslot_slots，已通过 AddPackFindersEvent 注册为全局源，新存档共享）
            Path packRoot = gameRoot.resolve("datapacks").resolve("curiosslot_slots");

            // 1) 槽位定义 data/curios/curios/slots/<id>.json
            Path slotDir = packRoot.resolve("data/curios/curios/slots");
            Files.createDirectories(slotDir);
            long order;
            try (var s = Files.list(slotDir)) {
                order = 3000 + s.filter(p -> p.getFileName().toString().endsWith(".json")).count();
            }
            Gson gson = new Gson();
            JsonObject slotJson = new JsonObject();
            slotJson.addProperty("size", 1);
            slotJson.addProperty("order", order);
            slotJson.addProperty("icon", "curios:slot/empty_" + id + "_slot");
            Files.write(slotDir.resolve(id + ".json"), gson.toJson(slotJson).getBytes(StandardCharsets.UTF_8));

            // 2) 图标 assets/curios/textures/slot/empty_<id>_slot.png（Curios 约定：curios 命名空间 + empty_ 前缀）
            Path texDir = packRoot.resolve("assets/curios/textures/slot");
            Files.createDirectories(texDir);
            Files.copy(iconSrc, texDir.resolve("empty_" + id + "_slot.png"), StandardCopyOption.REPLACE_EXISTING);

            // 3) 本地化名称 assets/curios/lang/{zh_cn,en_us}.json（合并 Curios 内置，键 curios.identifier.<id>）
            Path langDir = packRoot.resolve("assets/curios/lang");
            Files.createDirectories(langDir);
            String name = (displayName == null || displayName.isEmpty()) ? id : displayName;
            writeCuriosLang(server, langDir, "zh_cn", id, name);
            writeCuriosLang(server, langDir, "en_us", id, id);

            // 4) entities 绑定：静态挂到玩家（教程 mcmod/post/4676 做法，否则玩家饰品栏看不到新槽位）。
            //    其他生物通过 GUI「创建栏位」或 register 指令动态挂载。
            Path entDir = packRoot.resolve("data/curios/curios/entities");
            Files.createDirectories(entDir);
            JsonObject entJson = new JsonObject();
            entJson.add("conditions", new JsonArray());
            JsonArray entEntities = new JsonArray();
            entEntities.add("minecraft:player");
            entJson.add("entities", entEntities);
            JsonArray entSlots = new JsonArray();
            entSlots.add(id);
            entJson.add("slots", entSlots);
            Files.write(entDir.resolve("player_" + id + ".json"),
                    gson.toJson(entJson).getBytes(StandardCharsets.UTF_8));

            // 4) pack.mcmeta
            Path mcmeta = packRoot.resolve("pack.mcmeta");
            if (!Files.exists(mcmeta)) {
                Files.write(mcmeta,
                        ("{\"pack\":{\"pack_format\":15,\"description\":\"curiosslot custom slots\"}}")
                                .getBytes(StandardCharsets.UTF_8));
            }

            // 资源下发：专用服务器生成服务器资源包自动下发；单机/集成服务器生成客户端资源包并通知客户端启用
            Path packsDir = server.getServerDirectory().toPath().resolve("resourcepacks");
            Files.createDirectories(packsDir);
            zipAssets(packRoot, packsDir.resolve("curiosslot_slots.zip"));
            if (server instanceof DedicatedServer) {
                server.getPackRepository().reload();
            } else {
                // 单机：把资源包写进 options.txt 持久化启用（下次启动自动加载，原版机制最可靠），并即时尝试启用
                addToOptionsTxt(server.getServerDirectory().toPath().resolve("options.txt"));
                if (src.getEntity() instanceof ServerPlayer sp) {
                    CuriosSlotNetworking.CHANNEL.send(
                            PacketDistributor.PLAYER.with(() -> sp), new EnablePackPacket());
                }
            }

            // 同步到所有世界并 reload，使槽位立即全局生效（含当前世界；新存档由 onServerStarted 兜底）
            syncGlobalToAllWorlds(server);
            // 把资源包广播给所有在线客户端，保证多人联机下图标与中文名正常显示
            broadcastResourcePack(server);

            String label = (displayName == null || displayName.isEmpty()) ? id : displayName;
            src.sendSuccess(
                    () -> Component.literal("已注册饰品栏位 " + id + "（" + label + "）。"
                            + "玩家已默认获得该栏位，其他生物可用 /curiosslot register 添加。"
                            + "图标使用 " + iconSrc),
                    true);
            return 1;
        } catch (Exception e) {
            src.sendFailure(Component.literal("[curiosslot] registerslot 出错: " + e.getMessage()));
            return 0;
        }
    }

    /** 把全局数据包仓库（游戏根/datapacks/curiosslot_slots）复制到当前世界 datapacks/curiosslot_slots，并 reload 使生效。
     *  这样新存档/重进都会自动带上已注册的全局栏位，且当前世界立即生效。 */
    public static void syncGlobalToWorld(MinecraftServer server) throws java.io.IOException {
        Path global = FMLPaths.GAMEDIR.get().resolve("datapacks").resolve("curiosslot_slots");
        if (!Files.exists(global.resolve("pack.mcmeta"))) return;
        Path worldDp = server.getWorldPath(LevelResource.DATAPACK_DIR).resolve("curiosslot_slots");
        if (Files.exists(worldDp)) deleteRecursive(worldDp);
        copyRecursive(global, worldDp);
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "reload");
    }

    private static void deleteRecursive(Path p) throws java.io.IOException {
        if (Files.isDirectory(p)) {
            try (var s = Files.list(p)) {
                for (Path c : s.toList()) deleteRecursive(c);
            }
        }
        Files.deleteIfExists(p);
    }

    private static void copyRecursive(Path from, Path to) throws java.io.IOException {
        Files.createDirectories(to);
        try (var s = Files.list(from)) {
            for (Path c : s.toList()) {
                Path target = to.resolve(from.relativize(c).toString());
                if (Files.isDirectory(c)) {
                    copyRecursive(c, target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(c, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    /** 把全局数据包仓库同步到游戏根 saves 下的所有世界（每个世界 datapacks/curiosslot_slots），
     *  并 reload 当前世界。这样注册/删除槽位对任意存档都立即生效，其他世界下次进入也已就绪。 */
    public static void syncGlobalToAllWorlds(MinecraftServer server) throws java.io.IOException {
        Path global = FMLPaths.GAMEDIR.get().resolve("datapacks").resolve("curiosslot_slots");
        boolean hasGlobal = Files.exists(global.resolve("pack.mcmeta"));
        Path saves = FMLPaths.GAMEDIR.get().resolve("saves");
        if (Files.isDirectory(saves)) {
            try (var ws = Files.list(saves)) {
                for (Path w : ws.toList()) {
                    if (!Files.isDirectory(w)) continue;
                    Path wDp = w.resolve("datapacks").resolve("curiosslot_slots");
                    if (Files.exists(wDp)) deleteRecursive(wDp);
                    if (hasGlobal) copyRecursive(global, wDp);
                }
            }
        }
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "reload");
    }

    /** 服务端读取 icons 文件夹内的槽位图标文件名（去 .png 后缀），发给客户端注册界面。 */
    public static void sendSlotIcons(ServerPlayer sp) {
        try {
            Path iconsDir = sp.server.getServerDirectory().toPath()
                    .resolve("curiosslot_slots").resolve("icons");
            List<String> ids = new ArrayList<>();
            if (Files.exists(iconsDir)) {
                try (var s = Files.list(iconsDir)) {
                    s.filter(p -> p.getFileName().toString().endsWith(".png"))
                     .sorted()
                     .forEach(p -> ids.add(p.getFileName().toString().replaceAll("\\.png$", "")));
                }
            }
            CuriosSlotNetworking.CHANNEL.send(PacketDistributor.PLAYER.with(() -> sp),
                    new SlotIconListPacket(ids));
        } catch (Exception ignored) {
        }
    }

    /** 客户端注册界面触发的 registerslot：先校验"仅房主/管理员"，再执行注册。 */
    public static void registerSlotFromClient(ServerPlayer sp, String id, String name) {
        if (!canManageSlots(sp)) {
            sp.sendSystemMessage(Component.literal("[curiosslot] 你没有 4 级权限，无法注册饰品栏位。"));
            return;
        }
        registerSlotType(sp.createCommandSourceStack(), id, name);
    }

    /** 清除一个已注册的 Curios 饰品栏位：删除全局数据包定义/图标/翻译键，并重建资源包。 */
    public static int unregisterSlotType(CommandSourceStack src, String id) {
        MinecraftServer server = src.getServer();
        if (!id.matches("[a-z0-9_\\-.]{1,64}")) {
            src.sendFailure(Component.literal("栏位 id 必须为小写字母/数字/下划线/连字符/点，长度 1~64。"));
            return 0;
        }
        try {
            Path gameRoot = server.getServerDirectory().toPath();
            Path packRoot = gameRoot.resolve("datapacks").resolve("curiosslot_slots");
            boolean any = false;
            Path slotFile = packRoot.resolve("data/curios/curios/slots").resolve(id + ".json");
            if (Files.exists(slotFile)) {
                Files.delete(slotFile);
                any = true;
            }
            Path entFile = packRoot.resolve("data/curios/curios/entities").resolve("player_" + id + ".json");
            if (Files.exists(entFile)) {
                Files.delete(entFile);
                any = true;
            }
            Path icon = packRoot.resolve("assets/curios/textures/slot").resolve("empty_" + id + "_slot.png");
            if (Files.exists(icon)) {
                Files.delete(icon);
                any = true;
            }
            removeLangKey(packRoot.resolve("assets/curios/lang"), id);
            if (!any) {
                src.sendFailure(Component.literal("未找到已注册的饰品栏位 " + id + "。"));
                return 0;
            }
            // 重建资源包 zip（客户端图标/翻译移除）
            Path packsDir = server.getServerDirectory().toPath().resolve("resourcepacks");
            Path zip = packsDir.resolve("curiosslot_slots.zip");
            if (Files.exists(packRoot.resolve("pack.mcmeta"))) {
                zipAssets(packRoot, zip);
            }
            // 数据包 reload + 通知客户端重载资源
            if (server instanceof DedicatedServer) {
                server.getPackRepository().reload();
            } else {
                addToOptionsTxt(server.getServerDirectory().toPath().resolve("options.txt"));
                if (src.getEntity() instanceof ServerPlayer sp) {
                    CuriosSlotNetworking.CHANNEL.send(
                            PacketDistributor.PLAYER.with(() -> sp), new EnablePackPacket());
                }
            }
            // 同步到所有世界并 reload，使槽位移除对所有存档立即生效
            syncGlobalToAllWorlds(server);
            // 把重建后的资源包广播给所有在线客户端，多人联机下同步移除图标/中文
            broadcastResourcePack(server);
            src.sendSuccess(() -> Component.literal("已清除饰品栏位 " + id + "。"), true);
            return 1;
        } catch (Exception e) {
            src.sendFailure(Component.literal("[curiosslot] unregisterslot 出错: " + e.getMessage()));
            return 0;
        }
    }

    /** 从全局数据包 lang 文件中移除某槽位的翻译键（读回→删键→写回），文件不存在则跳过。 */
    private static void removeLangKey(Path langDir, String id) {
        if (!Files.exists(langDir)) return;
        Gson gson = new Gson();
        try (var s = Files.list(langDir)) {
            s.filter(p -> p.getFileName().toString().endsWith(".json")).forEach(p -> {
                try {
                    JsonObject obj = gson.fromJson(
                            new InputStreamReader(Files.newInputStream(p), StandardCharsets.UTF_8), JsonObject.class);
                    if (obj == null) return;
                    obj.remove("curios.identifier." + id);
                    Files.write(p, gson.toJson(obj).getBytes(StandardCharsets.UTF_8));
                } catch (Exception ignored) {
                }
            });
        } catch (Exception ignored) {
        }
    }

    /** 服务端读取全局数据包已注册槽位 id 列表，发给客户端清除界面。 */
    public static void sendRegisteredSlots(ServerPlayer sp) {
        try {
            Path slotDir = sp.server.getServerDirectory().toPath()
                    .resolve("datapacks").resolve("curiosslot_slots").resolve("data/curios/curios/slots");
            List<String> ids = new ArrayList<>();
            if (Files.exists(slotDir)) {
                try (var s = Files.list(slotDir)) {
                    s.filter(p -> p.getFileName().toString().endsWith(".json"))
                     .sorted()
                     .forEach(p -> ids.add(p.getFileName().toString().replaceAll("\\.json$", "")));
                }
            }
            CuriosSlotNetworking.CHANNEL.send(PacketDistributor.PLAYER.with(() -> sp),
                    new RegisteredSlotListPacket(ids));
        } catch (Exception ignored) {
        }
    }

    /** 客户端清除界面触发的 unregisterslot：先校验"仅房主/管理员"，再执行清除。 */
    public static void unregisterSlotFromClient(ServerPlayer sp, String id) {
        if (!canManageSlots(sp)) {
            sp.sendSystemMessage(Component.literal("[curiosslot] 你没有 4 级权限，无法清除饰品栏位。"));
            return;
        }
        unregisterSlotType(sp.createCommandSourceStack(), id);
    }

    /** 向 curios 命名空间的本地化文件写入/合并一个键值，先合并 Curios 内置翻译以免覆盖其他槽位名。 */
    private static void writeCuriosLang(MinecraftServer server, Path langDir, String lang,
                                        String slotId, String display) throws java.io.IOException {
        Files.createDirectories(langDir);
        Gson gson = new Gson();
        JsonObject obj = new JsonObject();
        try {
            Optional<net.minecraft.server.packs.resources.Resource> res = server.getResourceManager()
                    .getResource(new ResourceLocation("curios", "lang/" + lang + ".json"));
            if (res.isPresent()) {
                try (InputStream in = res.get().open()) {
                    JsonObject base = gson.fromJson(
                            new InputStreamReader(in, StandardCharsets.UTF_8), JsonObject.class);
                    if (base != null) obj = base;
                }
            }
        } catch (Exception ignored) {
        }
        obj.addProperty("curios.identifier." + slotId, display);
        Files.write(langDir.resolve(lang + ".json"), gson.toJson(obj).getBytes(StandardCharsets.UTF_8));
    }

    /** 把数据包里的 assets（含 pack.mcmeta）打包成服务器资源包 zip。 */
    private static void zipAssets(Path packRoot, Path zipFile) throws java.io.IOException {
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zipFile))) {
            zos.putNextEntry(new ZipEntry("pack.mcmeta"));
            Files.copy(packRoot.resolve("pack.mcmeta"), zos);
            zos.closeEntry();
            Path assets = packRoot.resolve("assets");
            if (Files.isDirectory(assets)) {
                try (var stream = Files.walk(assets)) {
                    for (Path p : stream.filter(Files::isRegularFile).toList()) {
                        String rel = packRoot.relativize(p).toString().replace('\\', '/');
                        zos.putNextEntry(new ZipEntry(rel));
                        Files.copy(p, zos);
                        zos.closeEntry();
                    }
                }
            }
        }
    }

    private int run(CommandContext<CommandSourceStack> ctx, String mode, boolean useNearest) {
        CommandSourceStack src = ctx.getSource();
        try {
            String slot = StringArgumentType.getString(ctx, "slot");
            int count = IntegerArgumentType.getInteger(ctx, "count");

            LivingEntity living;
            if (useNearest) {
                living = nearestNonPlayer(src);
                if (living == null) {
                    src.sendFailure(Component.literal("没有找到任何非玩家实体。"));
                    return 0;
                }
            } else {
                Entity target = EntityArgument.getEntity(ctx, "target");
                if (!(target instanceof LivingEntity le)) {
                    src.sendFailure(Component.literal("目标不是 LivingEntity，无法添加 Curios 栏位。"));
                    return 0;
                }
                living = le;
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

            // 指令留痕（单个实体）：记录该生物 (UUID, 槽位) 已被手动修改，之后不再被强制默认数量。
            markTouched(src.getServer(), living, slot);

            src.sendSuccess(
                    () -> Component.literal(("set".equals(mode) ? "已设置 " : "已增加 ")
                            + slot + " x " + count + " → " + living.getName().getString()),
                    true);
            return 1;
        } catch (Exception e) {
            src.sendFailure(Component.literal("[curiosslot] 执行出错: " + e.getMessage()));
            return 0;
        }
    }
}
