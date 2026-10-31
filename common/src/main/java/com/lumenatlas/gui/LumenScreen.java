package com.lumenatlas.gui;

import com.lumenatlas.compat.Compat;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Base for the dialog-style screens: a centered dark panel with a title bar, returning to the parent on close. */
public abstract class LumenScreen extends Screen {
    protected final Screen parent;
    protected int panelLeft;
    protected int panelTop;
    protected int panelRight;
    protected int panelBottom;

    protected LumenScreen(Screen parent, Component title) {
        super(title);
        this.parent = parent;
    }

    /** Preferred panel size; it shrinks to fit small windows and large GUI scales. */
    protected int panelWidth() {
        return 380;
    }

    protected int panelHeight() {
        return 260;
    }

    @Override
    protected void init() {
        int w = Math.min(panelWidth(), width - 12);
        int h = Math.min(panelHeight(), height - 12);
        panelLeft = (width - w) / 2;
        panelTop = (height - h) / 2;
        panelRight = panelLeft + w;
        panelBottom = panelTop + h;
        initContent();
    }

    protected abstract void initContent();

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
        g.fill(0, 0, width, height, Theme.BACKDROP);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
        Draw.card(g, panelLeft, panelTop, panelRight, panelBottom);
        g.text(font, title, panelLeft + 10, panelTop + 9, Theme.TEXT, false);
        g.fill(panelLeft + 1, panelTop + 24, panelRight - 1, panelTop + 25, Theme.BORDER);
        extractContent(g, mouseX, mouseY, a);
        super.extractRenderState(g, mouseX, mouseY, a);
        extractOverlay(g, mouseX, mouseY, a);
    }

    /** Drawn below the widgets. */
    protected void extractContent(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {}

    /** Drawn above the widgets. */
    protected void extractOverlay(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {}

    @Override
    public void onClose() {
        Compat.setScreen(minecraft, parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
