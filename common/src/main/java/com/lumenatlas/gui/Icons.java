package com.lumenatlas.gui;

import com.lumenatlas.LumenAtlas;
import com.lumenatlas.core.util.Colors;
import com.lumenatlas.structure.StructureKind;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

/** Draws sprites from the icon atlas (textures/gui/icons.png, see tools/make_icons.py). */
public final class Icons {
    public static final Identifier ATLAS = LumenAtlas.id("textures/gui/icons.png");
    private static final int ATLAS_W = 256;
    private static final int ATLAS_H = 64;

    public static final int PLAYER_ARROW = 48;
    public static final int DISC = 49;
    public static final int RING = 50;
    public static final int DASHED_RING = 51;
    public static final int DOT = 52;
    public static final int POINTER = 53;
    public static final int CROSS = 54;
    public static final int WAYPOINT_BASE = 32;

    private Icons() {}

    /** Draws atlas cell {@code index} (16 x 16) at (x, y) with the given size and ARGB tint. */
    public static void draw(GuiGraphicsExtractor g, int index, int x, int y, int size, int color) {
        int u = (index % 16) * 16;
        int v = (index / 16) * 16;
        g.blit(RenderPipelines.GUI_TEXTURED, ATLAS, x, y, u, v, size, size, 16, 16, ATLAS_W, ATLAS_H, color);
    }

    /** Draws a cell centered on the current pose origin (use with pose translate/rotate). */
    public static void drawCentered(GuiGraphicsExtractor g, int index, int size, int color) {
        draw(g, index, -size / 2, -size / 2, size, color);
    }

    /**
     * A structure badge: dark disc, colored ring (dashed and dimmer for seed-map predictions) and the glyph.
     * Drawn centered at (cx, cy).
     */
    public static void structure(GuiGraphicsExtractor g, StructureKind kind, int cx, int cy, int size, boolean predicted, boolean highlight) {
        int x = cx - size / 2;
        int y = cy - size / 2;
        float alpha = predicted ? 0.78f : 1f;
        draw(g, DISC, x, y, size, Colors.withAlpha(predicted ? 0x15121F : 0x1A1626, Math.round(235 * alpha)));
        int ring = Colors.opaque(highlight ? 0xFFFFFF : kind.color());
        draw(g, predicted ? DASHED_RING : RING, x, y, size, Colors.withAlpha(ring, Math.round(255 * alpha)));
        draw(g, kind.icon(), x, y, size, Colors.withAlpha(0xFFFFFF, predicted ? 190 : 255));
    }

    /** A waypoint marker: colored disc with the white glyph on top, centered at (cx, cy). */
    public static void waypoint(GuiGraphicsExtractor g, int iconOrdinal, int rgb, int cx, int cy, int size, float alpha) {
        int x = cx - size / 2;
        int y = cy - size / 2;
        int a = Math.round(255 * alpha);
        draw(g, DISC, x, y, size, Colors.withAlpha(0x14121C, Math.round(220 * alpha)));
        draw(g, RING, x, y, size, Colors.withAlpha(rgb, a));
        draw(g, WAYPOINT_BASE + iconOrdinal, x + size / 8, y + size / 8, size - size / 4, Colors.withAlpha(rgb, a));
    }
}
