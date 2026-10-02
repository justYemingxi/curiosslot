package com.curiosslot;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.curiosslot.network.CuriosSlotNetworking;
import com.curiosslot.network.DebugOpenPacket;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
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

    // 客户端未安装 curiosslot 的玩家（其调试手杖被禁用并移除，避免未知物品）
    private static final Set<UUID> CLIENT_NO_MOD = new HashSet<>();
    // 已发来 hello 包、确认客户端装了 mod 的玩家
    private static final Set<UUID> CLIENT_HAS_MOD = new HashSet<>();
    // 登录后等待 hello 包到达的玩家 -> 登录 tick（超过 100 tick 仍未收到则视为未装）
    private static final Map<UUID, Integer> LOGIN_PENDING = new HashMap<>();

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
            Files.write(p, new Gson().toJson(root).getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }

    private static void refreshCaches(MinecraftServer server) {
        REGISTERED = scanRegistered(server);
        loadState(server);
        cachePrimed = true;
    }

    // 留痕（单个实体）：记录这一只生物 (UUID,槽位) 已被 set/add 手动修改，之后不再强制默认数量。
    public static void markTouched(MinecraftServer server, LivingEntity le, String slot) {
        if (!cachePrimed) refreshCaches(server);
        TOUCHED.add(keyOfEntity(le, slot));
        saveState(server);
    }

    // 记录/更新某 (实体类型, 槽位) 的默认栏位数量。
    public static void setDefault(MinecraftServer server, EntityType<?> type, String slot, int count) {
        if (!cachePrimed) refreshCaches(server);
        DEFAULTS.put(keyOf(type, slot), count);
        saveState(server);
    }

    // 该 (实体类型, 槽位) 的默认数量（未设置时为 1）。
    private static int defaultCount(EntityType<?> type, String slot) {
        Integer d = DEFAULTS.get(keyOf(type, slot));
        return d == null ? 1 : d;
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
        if (slots == null || slots.isEmpty()) return;
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

        // 登录延迟判定：客户端 hello 包应在登录后 100 tick 内到达，未到则视为未装 mod，
        // 记录为未装客户端（open 调试界面时会提示需要客户端安装）
        if (!LOGIN_PENDING.isEmpty()) {
            int now = event.getServer().getTickCount();
            var it = LOGIN_PENDING.entrySet().iterator();
            while (it.hasNext()) {
                var e = it.next();
                if (now - e.getValue() >= 100) {
                    UUID uuid = e.getKey();
                    it.remove();
                    if (!CLIENT_HAS_MOD.contains(uuid)) {
                        CLIENT_NO_MOD.add(uuid);
                    }
                }
            }
        }

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
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, CONFIG_SPEC);
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
    public void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp && !sp.level().isClientSide()) {
            // 单机（集成服务器）：客户端与服务端同进程，mod 必然已装，直接视为已装，跳过延迟判定
            if (!sp.server.isDedicatedServer()) {
                CLIENT_HAS_MOD.add(sp.getUUID());
                return;
            }
            // 专用服务器：记录登录 tick，稍后在服务端 tick 中延迟判定客户端是否装了 mod（等 hello 包到达）
            LOGIN_PENDING.put(sp.getUUID(), sp.server.getTickCount());
        }
    }

    /** 客户端发来 hello 包后调用：标记该客户端已安装 curiosslot。 */
    public static void markClientHasMod(UUID uuid) {
        CLIENT_HAS_MOD.add(uuid);
        LOGIN_PENDING.remove(uuid);
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
            if (CLIENT_NO_MOD.contains(sp.getUUID())) {
                sp.sendSystemMessage(Component.literal(
                        "[curiosslot] 调试界面需要客户端也安装 curiosslot 才能打开。"));
                return 1;
            }

            UUID uuid = living.getUUID();
            String name = living.getName().getString();
            Map<String, Integer> existing = collectExisting(living);
            Map<String, Integer> defaults = collectDefaults(living);
            List<String> creatable = collectCreatable(living);
            CuriosSlotNetworking.CHANNEL.send(
                    PacketDistributor.PLAYER.with(() -> sp),
                    new DebugOpenPacket(uuid, name, existing, defaults, creatable));
            src.sendSuccess(() -> Component.literal("已打开 " + name + " 的调试界面。"), true);
            return 1;
        } catch (Exception e) {
            src.sendFailure(Component.literal("[curiosslot] open 出错: " + e.getMessage()));
            return 0;
        }
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

            // 拦截：若该实体类型当前已拥有此槽位（来自 Curios 内置、其他数据包、或本 mod 之前注册），
            // 就无需再创建，避免冗余写入；但若指定了数量，仍会更新该 (类型,槽位) 的默认数量。
            EntityType<?> type = ForgeRegistries.ENTITY_TYPES.getValue(ResourceLocation.tryParse(typeId));
            if (type != null && CuriosApi.getEntitySlots(type).containsKey(slot)) {
                // 仅更新默认数量，不触发 /reload（实测 reload 无法让已生成的无栏位生物获得栏位）
                setDefault(src.getServer(), type, slot, count);
                src.sendSuccess(
                        () -> Component.literal("该实体类型 " + typeId + " 已拥有 " + slot
                                + " 栏位，无需注册。默认数量已设为 " + count + "。"),
                        true);
                return 1;
            }

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
