package com.curiosslot.screen;

import com.curiosslot.network.CuriosSlotNetworking;
import com.curiosslot.network.DebugActionPacket;
import com.curiosslot.network.RequestSlotIconsPacket;
import com.curiosslot.network.RequestRegisteredSlotsPacket;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.lwjgl.glfw.GLFW;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Curios 栏位调试界面（纯客户端 Screen，不依赖服务器注册的容器/物品）。
 * <p>
 * 数据由服务端 {@code /curiosslot open} 触发 {@code DebugOpenPacket} 提供，
 * 操作（set/create）经 {@code DebugActionPacket} 发到服务端，处理后再由
 * {@code DebugRefreshPacket} 刷新本界面。
 * 主视图：实体名 + UUID，已有栏位列表（槽位名 + 数量输入框 + 设置/- /+ 按钮）；
 * 底部"+ 创建栏位"进入选择模式：列出可新建槽位 + 数量输入框 + 创建按钮。
 */
@OnlyIn(Dist.CLIENT)
public class CuriosDebugScreen extends Screen {

    private static final int WIDTH = 270;
    private static final int HEIGHT = 214;
    private static final int LIST_TOP = 46;
    private static final int LIST_BOTTOM = 170;
    private static final int ROW_H = 18;

    private final UUID targetUuid;
    private final boolean canRegister;
    private String entityName;
    private Map<String, Integer> existingSlots = new LinkedHashMap<>();
    private Map<String, Integer> defaultSlots = new LinkedHashMap<>();
    private List<String> creatableSlots = List.of();
    private int version = 0;

    private boolean selectMode = false;
    private int scrollExisting = 0;
    private int scrollCreatable = 0;
    private int lastVersion = -1;

    private int leftPos;
    private int topPos;

    // 数量输入框的展示值（槽位名 -> 文本）；重建按钮时保留，避免用户输入被刷新清掉
    private final Map<String, String> existingInputs = new LinkedHashMap<>();
    private final Map<String, String> creatableInputs = new LinkedHashMap<>();
    private final Map<String, String> defaultInputs = new LinkedHashMap<>();
    // 当前渲染的数量输入框（用于回车提交定位）
    private final Map<String, EditBox> existingBoxes = new LinkedHashMap<>();
    private final Map<String, EditBox> creatableBoxes = new LinkedHashMap<>();
    private final Map<String, EditBox> defaultBoxes = new LinkedHashMap<>();

    public CuriosDebugScreen(UUID uuid, String name, Map<String, Integer> existing,
                             Map<String, Integer> defaults, List<String> creatable, boolean canRegister) {
        super(Component.literal("Curios 栏位调试 - " + name));
        this.targetUuid = uuid;
        this.canRegister = canRegister;
        this.entityName = name;
        this.existingSlots = existing;
        this.defaultSlots = defaults;
        this.creatableSlots = creatable;
    }

    public UUID getTargetUuid() {
        return targetUuid;
    }

    /** 收到刷新包后更新本地数据（version 递增触发重绘）。 */
    public void updateData(String name, Map<String, Integer> existing,
                           Map<String, Integer> defaults, List<String> creatable) {
        this.entityName = name;
        this.existingSlots = existing;
        this.defaultSlots = defaults;
        this.creatableSlots = creatable;
        this.version++;
    }

    @Override
    protected void init() {
        this.leftPos = (this.width - WIDTH) / 2;
        this.topPos = (this.height - HEIGHT) / 2;
        lastVersion = version;
        rebuildButtons();
    }

    private void rebuildButtons() {
        clearWidgets();
        existingBoxes.clear();
        creatableBoxes.clear();
        defaultBoxes.clear();
        int maxRows = (LIST_BOTTOM - LIST_TOP) / ROW_H;
        if (selectMode) {
            for (int i = 0; i < maxRows; i++) {
                int idx = scrollCreatable + i;
                if (idx >= creatableSlots.size()) break;
                String slot = creatableSlots.get(idx);
                int y = this.topPos + LIST_TOP + i * ROW_H;
                EditBox box = new EditBox(this.font, this.leftPos + 82, y + 2, 42, 12, Component.literal("数量"));
                box.setValue(creatableInputs.computeIfAbsent(slot, s -> "1"));
                box.setFilter(s -> s.matches("[0-9]*"));
                addRenderableWidget(box);
                creatableBoxes.put(slot, box);
                addRenderableWidget(Button.builder(Component.literal("创建"), b -> {
                    int n = parseCount(box.getValue(), 1, 1, 64);
                    creatableInputs.put(slot, String.valueOf(n));
                    createSlot(slot, n);
                }).bounds(this.leftPos + 126, y + 1, 48, 14).build());
            }
            addRenderableWidget(Button.builder(Component.literal("← 返回"), b -> {
                selectMode = false;
                rebuildButtons();
            }).bounds(this.leftPos + 8, this.topPos + HEIGHT - 38, WIDTH - 16, 16).build());
        } else {
            List<String> keys = List.copyOf(existingSlots.keySet());
            for (int i = 0; i < maxRows; i++) {
                int idx = scrollExisting + i;
                if (idx >= keys.size()) break;
                String slot = keys.get(idx);
                int current = existingSlots.getOrDefault(slot, 0);
                int defVal = defaultSlots.getOrDefault(slot, 1);
                int y = this.topPos + LIST_TOP + i * ROW_H;
                // 实体数量
                EditBox box = new EditBox(this.font, this.leftPos + 82, y + 2, 34, 12, Component.literal("数量"));
                box.setValue(existingInputs.computeIfAbsent(slot, s -> String.valueOf(current)));
                box.setFilter(s -> s.matches("[0-9]*"));
                addRenderableWidget(box);
                existingBoxes.put(slot, box);
                addRenderableWidget(Button.builder(Component.literal("设置"), b -> {
                    int n = parseCount(box.getValue(), 0, 0, Integer.MAX_VALUE);
                    existingInputs.put(slot, String.valueOf(n));
                    setCount(slot, n);
                }).bounds(this.leftPos + 118, y + 1, 26, 14).build());
                addRenderableWidget(Button.builder(Component.literal("-"), b -> {
                    int n = Math.max(0, current - 1);
                    existingInputs.put(slot, String.valueOf(n));
                    setCount(slot, n);
                }).bounds(this.leftPos + 146, y + 1, 12, 14).build());
                addRenderableWidget(Button.builder(Component.literal("+"), b -> {
                    int n = current + 1;
                    existingInputs.put(slot, String.valueOf(n));
                    setCount(slot, n);
                }).bounds(this.leftPos + 160, y + 1, 12, 14).build());
                // 该实体类型默认数量
                EditBox defBox = new EditBox(this.font, this.leftPos + 176, y + 2, 34, 12, Component.literal("默认"));
                defBox.setValue(defaultInputs.computeIfAbsent(slot, s -> String.valueOf(defVal)));
                defBox.setFilter(s -> s.matches("[0-9]*"));
                addRenderableWidget(defBox);
                defaultBoxes.put(slot, defBox);
                addRenderableWidget(Button.builder(Component.literal("默认"), b -> {
                    int n = parseCount(defBox.getValue(), 0, 0, Integer.MAX_VALUE);
                    defaultInputs.put(slot, String.valueOf(n));
                    setDefaultCount(slot, n);
                }).bounds(this.leftPos + 212, y + 1, 30, 14).build());
            }
            if (!canRegister) {
                addRenderableWidget(Button.builder(Component.literal("＋ 创建栏位（选择槽位）"), b -> {
                    selectMode = true;
                    scrollCreatable = 0;
                    rebuildButtons();
                }).bounds(this.leftPos + 8, this.topPos + HEIGHT - 38, WIDTH - 16, 16).build());
            } else {
                int w3 = (WIDTH - 16) / 3;
                addRenderableWidget(Button.builder(Component.literal("＋ 创建栏位"), b -> {
                    selectMode = true;
                    scrollCreatable = 0;
                    rebuildButtons();
                }).bounds(this.leftPos + 8, this.topPos + HEIGHT - 38, w3, 16).build());
                // 注册饰品栏位：打开选择 icons 文件的界面（仅 4 级权限可见）
                addRenderableWidget(Button.builder(Component.literal("注册饰品栏"), b -> {
                    CuriosSlotNetworking.CHANNEL.sendToServer(new RequestSlotIconsPacket());
                    net.minecraft.client.Minecraft.getInstance().setScreen(new RegisterSlotScreen(this));
                }).bounds(this.leftPos + 8 + w3, this.topPos + HEIGHT - 38, w3, 16).build());
                // 清除饰品栏位：打开已注册栏位列表（仅 4 级权限可见）
                addRenderableWidget(Button.builder(Component.literal("清除饰品栏"), b -> {
                    CuriosSlotNetworking.CHANNEL.sendToServer(new RequestRegisteredSlotsPacket());
                    net.minecraft.client.Minecraft.getInstance().setScreen(new UnregisterSlotScreen(this));
                }).bounds(this.leftPos + 8 + w3 * 2, this.topPos + HEIGHT - 38, WIDTH - 16 - w3 * 2, 16).build());
            }
            // 全局重置：清除所有留痕，被 set/add 改过的生物将按默认数量重置（醒目标红，执行 /curiosslot reset）
            addRenderableWidget(Button.builder(
                    Component.literal("全局重置默认值").withStyle(ChatFormatting.RED, ChatFormatting.BOLD), b -> {
                        net.minecraft.client.Minecraft.getInstance().player
                                .connection.sendCommand("curiosslot reset");
                    }).bounds(this.leftPos + 8, this.topPos + HEIGHT - 22, (WIDTH - 16) / 2, 16).build());
            // 清空配置：清除当前存档所有 curiosslot 数据包，回归原始数据（执行 /curiosslot clear）
            addRenderableWidget(Button.builder(
                    Component.literal("清空配置").withStyle(ChatFormatting.RED, ChatFormatting.BOLD), b -> {
                        net.minecraft.client.Minecraft.getInstance().player
                                .connection.sendCommand("curiosslot clear");
                    }).bounds(this.leftPos + 8 + (WIDTH - 16) / 2, this.topPos + HEIGHT - 22,
                            (WIDTH - 16) / 2, 16).build());
        }
    }

    private void setCount(String slot, int count) {
        if (count < 0) count = 0;
        CuriosSlotNetworking.CHANNEL.sendToServer(new DebugActionPacket(targetUuid, "set", slot, count));
    }

    private void setDefaultCount(String slot, int count) {
        if (count < 0) count = 0;
        CuriosSlotNetworking.CHANNEL.sendToServer(new DebugActionPacket(targetUuid, "setDefault", slot, count));
    }

    private void createSlot(String slot, int count) {
        CuriosSlotNetworking.CHANNEL.sendToServer(new DebugActionPacket(targetUuid, "create", slot, Math.max(1, count)));
    }

    private static int parseCount(String s, int def, int min, int max) {
        try {
            int n = Integer.parseInt(s.trim());
            return Math.max(min, Math.min(max, n));
        } catch (Exception e) {
            return def;
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            for (var e : existingBoxes.entrySet()) {
                if (e.getValue().isFocused()) {
                    int n = parseCount(e.getValue().getValue(), 0, 0, Integer.MAX_VALUE);
                    existingInputs.put(e.getKey(), String.valueOf(n));
                    setCount(e.getKey(), n);
                    return true;
                }
            }
            for (var e : defaultBoxes.entrySet()) {
                if (e.getValue().isFocused()) {
                    int n = parseCount(e.getValue().getValue(), 0, 0, Integer.MAX_VALUE);
                    defaultInputs.put(e.getKey(), String.valueOf(n));
                    setDefaultCount(e.getKey(), n);
                    return true;
                }
            }
            for (var e : creatableBoxes.entrySet()) {
                if (e.getValue().isFocused()) {
                    int n = parseCount(e.getValue().getValue(), 1, 1, 64);
                    creatableInputs.put(e.getKey(), String.valueOf(n));
                    createSlot(e.getKey(), n);
                    return true;
                }
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void tick() {
        super.tick();
        if (version != lastVersion) {
            lastVersion = version;
            if (selectMode) {
                int m = creatableSlots.size();
                int maxRows = (LIST_BOTTOM - LIST_TOP) / ROW_H;
                if (scrollCreatable > Math.max(0, m - maxRows)) scrollCreatable = Math.max(0, m - maxRows);
            } else {
                int m = existingSlots.size();
                int maxRows = (LIST_BOTTOM - LIST_TOP) / ROW_H;
                if (scrollExisting > Math.max(0, m - maxRows)) scrollExisting = Math.max(0, m - maxRows);
            }
            rebuildButtons();
        }
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        if (selectMode) {
            int m = creatableSlots.size();
            int maxRows = (LIST_BOTTOM - LIST_TOP) / ROW_H;
            int cap = Math.max(0, m - maxRows);
            scrollCreatable = Math.max(0, Math.min(cap, scrollCreatable - (int) delta));
        } else {
            int m = existingSlots.size();
            int maxRows = (LIST_BOTTOM - LIST_TOP) / ROW_H;
            int cap = Math.max(0, m - maxRows);
            scrollExisting = Math.max(0, Math.min(cap, scrollExisting - (int) delta));
        }
        rebuildButtons();
        return true;
    }

    @Override
    public void render(GuiGraphics gfx, int mx, int my, float partialTick) {
        // 背景与分隔线（与控件同一面板基准 leftPos/topPos）
        gfx.fill(this.leftPos, this.topPos, this.leftPos + WIDTH, this.topPos + HEIGHT, 0xC0101010);
        gfx.fill(this.leftPos, this.topPos + LIST_TOP - 6, this.leftPos + WIDTH, this.topPos + LIST_TOP - 4, 0xFF55FFFF);
        gfx.fill(this.leftPos, this.topPos + LIST_BOTTOM + 2, this.leftPos + WIDTH, this.topPos + LIST_BOTTOM + 4, 0xFF55FFFF);
        super.render(gfx, mx, my, partialTick);

        // 标题与 UUID（都加 leftPos/topPos，与控件对齐）
        gfx.drawString(this.font, Component.literal(entityName), this.leftPos + 8, this.topPos + 8, 0xFF55FFFF, false);
        gfx.drawString(this.font, Component.literal("UUID: " + targetUuid), this.leftPos + 8, this.topPos + 20, 0xFFAAAAAA, false);

        int maxRows = (LIST_BOTTOM - LIST_TOP) / ROW_H;
        if (selectMode) {
            gfx.drawString(this.font, Component.literal("输入默认数量后点创建:"), this.leftPos + 8, this.topPos + 34, 0xFFFFFFFF, false);
            for (int i = 0; i < maxRows; i++) {
                int idx = scrollCreatable + i;
                if (idx >= creatableSlots.size()) break;
                int y = LIST_TOP + i * ROW_H;
                gfx.drawString(this.font, creatableSlots.get(idx), this.leftPos + 8, this.topPos + y + 2, 0xFFFFFFFF, false);
            }
        } else {
            gfx.drawString(this.font, Component.literal("左=本实体数量(设置/-/+)，右=类型默认数量(默认)"), this.leftPos + 8, this.topPos + 34, 0xFFFFFFFF, false);
            List<String> keys = List.copyOf(existingSlots.keySet());
            for (int i = 0; i < maxRows; i++) {
                int idx = scrollExisting + i;
                if (idx >= keys.size()) break;
                int y = LIST_TOP + i * ROW_H;
                gfx.drawString(this.font, keys.get(idx), this.leftPos + 8, this.topPos + y + 2, 0xFFFFFFFF, false);
            }
        }
        if (creatableSlots.isEmpty()) {
            gfx.drawString(this.font, Component.literal("（没有可新建的槽位，所有已注册槽位均已有）"),
                    this.leftPos + 8, this.topPos + LIST_BOTTOM - 14, 0x88AAAAAA, false);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
