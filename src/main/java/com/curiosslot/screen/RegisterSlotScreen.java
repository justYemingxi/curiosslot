package com.curiosslot.screen;

import com.curiosslot.network.CuriosSlotNetworking;
import com.curiosslot.network.RegisterSlotRequestPacket;
import com.curiosslot.network.RequestSlotIconsPacket;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * 注册饰品栏位界面：列出 icons 文件夹内的图标文件（点选自动取 id），可输入中文名，
 * 点"注册饰品栏"向服务端请求执行 registerslot（服务端校验 4 级权限），支持返回上一界面。
 */
@OnlyIn(Dist.CLIENT)
public class RegisterSlotScreen extends Screen {

    private static final int WIDTH = 270;
    private static final int HEIGHT = 214;
    private static final int LIST_TOP = 34;
    private static final int ROW_H = 18;

    private final Screen previous;
    private List<String> iconIds = new ArrayList<>();
    private String selected = null;
    private int scroll = 0;
    private int version = 0;
    private int lastVersion = -1;

    private int leftPos;
    private int topPos;

    private EditBox nameBox;
    private EditBox idBox;

    public RegisterSlotScreen(Screen previous) {
        super(Component.literal("注册饰品栏位"));
        this.previous = previous;
    }

    /** 收到服务端图标列表后更新（version 递增触发重绘）。 */
    public void setIconIds(List<String> ids) {
        this.iconIds = ids;
        if (this.selected == null && !ids.isEmpty()) {
            this.selected = ids.get(0);
        }
        this.version++;
    }

    @Override
    protected void init() {
        this.leftPos = (this.width - WIDTH) / 2;
        this.topPos = (this.height - HEIGHT) / 2;
        // 打开时向服务端请求图标列表
        CuriosSlotNetworking.CHANNEL.sendToServer(new RequestSlotIconsPacket());
        lastVersion = version;
        rebuildButtons();
    }

    private void rebuildButtons() {
        clearWidgets();
        int maxRows = (HEIGHT - LIST_TOP - 62) / ROW_H;
        for (int i = 0; i < maxRows; i++) {
            int idx = scroll + i;
            if (idx >= iconIds.size()) break;
            String id = iconIds.get(idx);
            int y = this.topPos + LIST_TOP + i * ROW_H;
            boolean sel = id.equals(selected);
            Button b = Button.builder(Component.literal((sel ? "> " : "  ") + id), btn -> {
                selected = id;
                if (idBox != null) idBox.setValue(id);
                rebuildButtons();
            }).bounds(this.leftPos + 8, y + 1, WIDTH - 16, 16).build();
            addRenderableWidget(b);
        }

        // 所选 id（只读展示 + 自动填）
        idBox = new EditBox(this.font, this.leftPos + 58, this.topPos + 108, WIDTH - 66, 14,
                Component.literal("id"));
        idBox.setValue(selected == null ? "" : selected);
        idBox.setEditable(false);
        addRenderableWidget(idBox);

        // 中文名（可选）
        nameBox = new EditBox(this.font, this.leftPos + 58, this.topPos + 128, WIDTH - 66, 14,
                Component.literal("中文名"));
        addRenderableWidget(nameBox);

        // 注册饰品栏
        addRenderableWidget(Button.builder(Component.literal("注册饰品栏"), b -> {
            String id = idBox.getValue().trim();
            if (id.isEmpty()) return;
            String name = nameBox.getValue().trim();
            CuriosSlotNetworking.CHANNEL.sendToServer(new RegisterSlotRequestPacket(id, name));
        }).bounds(this.leftPos + 8, this.topPos + 150, (WIDTH - 16) / 2, 16).build());

        // 返回上一界面
        addRenderableWidget(Button.builder(Component.literal("← 返回"), b -> {
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            mc.setScreen(previous);
        }).bounds(this.leftPos + 8 + (WIDTH - 16) / 2, this.topPos + 150, (WIDTH - 16) / 2, 16).build());
    }

    @Override
    public void tick() {
        super.tick();
        if (version != lastVersion) {
            lastVersion = version;
            int m = iconIds.size();
            int maxRows = (HEIGHT - LIST_TOP - 62) / ROW_H;
            if (scroll > Math.max(0, m - maxRows)) scroll = Math.max(0, m - maxRows);
            rebuildButtons();
        }
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        int m = iconIds.size();
        int maxRows = (HEIGHT - LIST_TOP - 62) / ROW_H;
        int cap = Math.max(0, m - maxRows);
        scroll = Math.max(0, Math.min(cap, scroll - (int) delta));
        rebuildButtons();
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            if (nameBox != null && nameBox.isFocused()) {
                String id = idBox.getValue().trim();
                if (!id.isEmpty()) {
                    CuriosSlotNetworking.CHANNEL.sendToServer(
                            new RegisterSlotRequestPacket(id, nameBox.getValue().trim()));
                }
                return true;
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void render(GuiGraphics gfx, int mx, int my, float partialTick) {
        gfx.fill(this.leftPos, this.topPos, this.leftPos + WIDTH, this.topPos + HEIGHT, 0xC0101010);
        gfx.fill(this.leftPos, this.topPos + LIST_TOP - 6, this.leftPos + WIDTH, this.topPos + LIST_TOP - 4, 0xFF55FFFF);
        super.render(gfx, mx, my, partialTick);

        gfx.drawString(this.font, Component.literal("注册饰品栏位（需 4 级权限）"),
                this.leftPos + 8, this.topPos + 8, 0xFF55FFFF, false);
        gfx.drawString(this.font, Component.literal("点选图标文件（自动取 id）："),
                this.leftPos + 8, this.topPos + LIST_TOP - 4, 0xFFFFFFFF, false);
        gfx.drawString(this.font, Component.literal("id"), this.leftPos + 8, this.topPos + 110, 0xFFFFFFFF, false);
        gfx.drawString(this.font, Component.literal("中文名"), this.leftPos + 8, this.topPos + 130, 0xFFFFFFFF, false);
        if (iconIds.isEmpty()) {
            gfx.drawString(this.font, Component.literal("（icons 文件夹暂无 .png 图标，请先放入）"),
                    this.leftPos + 8, this.topPos + LIST_TOP + 8, 0x88AAAAAA, false);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
