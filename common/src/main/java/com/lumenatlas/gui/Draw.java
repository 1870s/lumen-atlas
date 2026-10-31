package com.lumenatlas.gui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/** Small drawing helpers for the themed UI. */
public final class Draw {
    private Draw() {}

    /** Filled rectangle with a 1 px border. */
    public static void panel(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, int fill, int border) {
        g.fill(x0, y0, x1, y1, fill);
        g.outline(x0, y0, x1 - x0, y1 - y0, border);
    }

    /** Panel with a thin accent line on top, used for dialogs and side bars. */
    public static void card(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1) {
        panel(g, x0, y0, x1, y1, Theme.PANEL, Theme.BORDER);
        g.fill(x0 + 1, y0 + 1, x1 - 1, y0 + 2, Theme.ACCENT_DARK);
    }

    /** Text with a dark rounded-ish backdrop, readable over any map color. */
    public static void label(GuiGraphicsExtractor g, Font font, String text, int x, int y, int color) {
        int w = font.width(text);
        g.fill(x - 2, y - 2, x + w + 2, y + 9, 0xB0100E18);
        g.text(font, text, x, y, color, false);
    }

    public static void labelCentered(GuiGraphicsExtractor g, Font font, String text, int cx, int y, int color) {
        label(g, font, text, cx - font.width(text) / 2, y, color);
    }

    public static void title(GuiGraphicsExtractor g, Font font, Component text, int x, int y) {
        g.text(font, text, x, y, Theme.TEXT, false);
    }

    public static boolean inside(double mx, double my, int x0, int y0, int x1, int y1) {
        return mx >= x0 && mx < x1 && my >= y0 && my < y1;
    }
}
