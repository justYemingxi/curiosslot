package com.curiosslot.screen;

import com.curiosslot.network.CuriosSlotNetworking;
import com.curiosslot.network.RequestRegisteredSlotsPacket;
import com.curiosslot.network.UnregisterSlotRequestPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * 清除饰品栏位界面：列出全局数据包中已注册的槽位 id（点选），点"清除该栏位"
 * 向服务端请求执行 unregisterslot（服务端校验 4 级权限），支持返回上一界面。
 */
@OnlyIn(Dist.CLIENT)
public class UnregisterSlotScreen extends Screen {

    private static final int WIDTH = 270;
    private static final int HEIGHT = 214;
    private static final int LIST_TOP = 34;
    private static final int ROW_H = 18;

    private final Screen previous;
    private List<String> slotIds = new ArrayList<>();
    private String selected = null;
    private int scroll = 0;
    private int version = 0;
    private int lastVersion = -1;

    private int leftPos;
    private int topPos;

    public UnregisterSlotScreen(Screen previous) {
        super(Component.literal("清除饰品栏位"));
        this.previous = previous;
    }

    /** 收到服务端已注册槽位列表后更新（version 递增触发重绘）。 */
    public void setSlotIds(List<String> ids) {
        this.slotIds = ids;
        if (this.selected == null && !ids.isEmpty()) {
            this.selected = ids.get(0);
        }
        this.version++;
    }

    @Override
    protected void init() {
        this.leftPos = (this.width - WIDTH) / 2;
        this.topPos = (this.height - HEIGHT) / 2;
        CuriosSlotNetworking.CHANNEL.sendToServer(new RequestRegisteredSlotsPacket());
        lastVersion = version;
        rebuildButtons();
    }

    private void rebuildButtons() {
        clearWidgets();
        int maxRows = (HEIGHT - LIST_TOP - 62) / ROW_H;
        for (int i = 0; i < maxRows; i++) {
            int idx = scroll + i;
            if (idx >= slotIds.size()) break;
            String id = slotIds.get(idx);
            int y = this.topPos + LIST_TOP + i * ROW_H;
            boolean sel = id.equals(selected);
            Button b = Button.builder(Component.literal((sel ? "> " : "  ") + id), btn -> {
                selected = id;
                rebuildButtons();
            }).bounds(this.leftPos + 8, y + 1, WIDTH - 16, 16).build();
            addRenderableWidget(b);
        }

        // 清除该栏位
        addRenderableWidget(Button.builder(Component.literal("清除该栏位"), b -> {
            if (selected == null) return;
            CuriosSlotNetworking.CHANNEL.sendToServer(new UnregisterSlotRequestPacket(selected));
        }).bounds(this.leftPos + 8, this.topPos + 150, (WIDTH - 16) / 2, 16).build());

        // 返回上一界面
        addRenderableWidget(Button.builder(Component.literal("← 返回"), b -> {
            Minecraft.getInstance().setScreen(previous);
        }).bounds(this.leftPos + 8 + (WIDTH - 16) / 2, this.topPos + 150, (WIDTH - 16) / 2, 16).build());
    }

    @Override
    public void tick() {
        super.tick();
        if (version != lastVersion) {
            lastVersion = version;
            int m = slotIds.size();
            int maxRows = (HEIGHT - LIST_TOP - 62) / ROW_H;
            if (scroll > Math.max(0, m - maxRows)) scroll = Math.max(0, m - maxRows);
            rebuildButtons();
        }
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        int m = slotIds.size();
        int maxRows = (HEIGHT - LIST_TOP - 62) / ROW_H;
        int cap = Math.max(0, m - maxRows);
        scroll = Math.max(0, Math.min(cap, scroll - (int) delta));
        rebuildButtons();
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            if (selected != null) {
                CuriosSlotNetworking.CHANNEL.sendToServer(new UnregisterSlotRequestPacket(selected));
                return true;
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void render(GuiGraphics gfx, int mx, int my, float partialTick) {
        gfx.fill(this.leftPos, this.topPos, this.leftPos + WIDTH, this.topPos + HEIGHT, 0xC0101010);
        gfx.fill(this.leftPos, this.topPos + LIST_TOP - 6, this.leftPos + WIDTH, this.topPos + LIST_TOP - 4, 0xFFFF5555);
        super.render(gfx, mx, my, partialTick);

        gfx.drawString(this.font, Component.literal("清除饰品栏位（需 4 级权限）"),
                this.leftPos + 8, this.topPos + 8, 0xFFFF5555, false);
        gfx.drawString(this.font, Component.literal("点选要清除的已注册栏位："),
                this.leftPos + 8, this.topPos + LIST_TOP - 4, 0xFFFFFFFF, false);
        if (slotIds.isEmpty()) {
            gfx.drawString(this.font, Component.literal("（暂无已注册的饰品栏位）"),
                    this.leftPos + 8, this.topPos + LIST_TOP + 8, 0x88AAAAAA, false);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
