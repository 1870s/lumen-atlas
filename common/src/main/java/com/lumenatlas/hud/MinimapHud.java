package com.lumenatlas.hud;

import com.lumenatlas.LumenAtlas;
import com.lumenatlas.config.ConfigManager;
import com.lumenatlas.core.LumenConfig;
import com.lumenatlas.core.map.BiomePalette;
import com.lumenatlas.core.map.MapRegion;
import com.lumenatlas.core.map.MapShading;
import com.lumenatlas.core.map.MapView;
import com.lumenatlas.core.marker.Marker;
import com.lumenatlas.core.util.Colors;
import com.lumenatlas.core.util.MapMath;
import com.lumenatlas.core.waypoint.Waypoint;
import com.lumenatlas.core.waypoint.WaypointIcon;
import com.lumenatlas.gui.Icons;
import com.lumenatlas.gui.Theme;
import com.lumenatlas.map.MapStore;
import com.lumenatlas.seed.SeedMapService;
import com.lumenatlas.structure.StructureKind;
import com.lumenatlas.structure.StructureKinds;
import com.lumenatlas.world.WorldSession;
import com.mojang.blaze3d.platform.NativeImage;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * The minimap. The map image is composed on the CPU into a texture with the exact pixel size it covers on screen
 * (so rotation and the round shape stay crisp at every GUI scale), then markers, waypoints and entities are drawn
 * on top as normal GUI elements. Render thread only.
 */
public final class MinimapHud {
    private static final Identifier TEXTURE_ID = LumenAtlas.id("minimap");
    private static final int BORDER_OUTER = 0xFF0E0C16;
    private static final int BORDER_INNER = 0xFF8B6FE8;
    private static final int UNKNOWN_COLOR = 0xFF15131D;

    private static DynamicTexture texture;
    private static int textureSize;
    private static long lastCompose;
    private static final CaveLayer CAVE = new CaveLayer();
    private static final int[] HEIGHT = new int[1];
    private static boolean caveActive;
    private static String biomeName = "";
    private static long biomeCheckedAt;

    private MinimapHud() {}

    /** Changes the zoom by {@code steps} levels (negative = closer). */
    public static void zoom(int steps) {
        LumenConfig config = ConfigManager.get();
        int z = Math.max(0, Math.min(LumenConfig.MINIMAP_ZOOMS.length - 1, config.minimapZoom + steps));
        if (z != config.minimapZoom) {
            config.minimapZoom = z;
            ConfigManager.save();
            lastCompose = 0;
        }
    }

    /** Top-left corner and size of the minimap in GUI pixels: {x, y, size}. */
    public static int[] layout(int screenW, int screenH, LumenConfig config) {
        int size = Math.min(config.minimapSize, Math.min(screenW, screenH) - 40);
        int infoHeight = infoLines(config) * 10;
        int x = switch (config.minimapCorner) {
            case TOP_LEFT, BOTTOM_LEFT -> config.minimapMarginX;
            case TOP_RIGHT, BOTTOM_RIGHT -> screenW - config.minimapMarginX - size;
        };
        int y = switch (config.minimapCorner) {
            case TOP_LEFT, TOP_RIGHT -> config.minimapMarginY;
            case BOTTOM_LEFT, BOTTOM_RIGHT -> screenH - config.minimapMarginY - size - infoHeight;
        };
        return new int[] {x, y, size};
    }

    private static int infoLines(LumenConfig config) {
        int n = 0;
        if (config.showCoordinates) n++;
        if (config.showBiome) n++;
        return n;
    }

    public static void render(GuiGraphicsExtractor g, Minecraft mc, WorldSession session, float partialTick) {
        LumenConfig config = ConfigManager.get();
        if (!config.minimapEnabled || mc.getDebugOverlay().showDebugScreen()) return;
        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;
        int[] box = layout(g.guiWidth(), g.guiHeight(), config);
        int x0 = box[0];
        int y0 = box[1];
        int size = box[2];
        double guiScale = mc.getWindow().getGuiScale();
        int resolution = (int) Math.max(64, Math.min(512, Math.round(size * guiScale)));
        double px = player.xo + (player.getX() - player.xo) * partialTick;
        double pz = player.zo + (player.getZ() - player.zo) * partialTick;
        float yaw = player.getViewYRot(partialTick);
        boolean circle = config.minimapShape == LumenConfig.Shape.CIRCLE;
        double rotation = config.minimapRotate ? yaw + 180 : 0;
        double blocksPerGuiPixel = config.minimapBlocksPerPixel();

        // Compose the map image (throttled to the configured refresh rate).
        long now = System.nanoTime();
        if (texture == null || textureSize != resolution || now - lastCompose >= 1_000_000_000L / config.minimapFps) {
            ensureTexture(mc, resolution);
            caveActive = shouldUseCave(config, level, player);
            if (caveActive) CAVE.beginFrame(level, (int) Math.floor(player.getY()) + 1);
            compose(session.currentMap(), level, px, pz, rotation, blocksPerGuiPixel * size / resolution, circle, config);
            texture.upload();
            lastCompose = now;
        }

        float opacity = config.minimapOpacity;
        int white = Colors.withAlpha(0xFFFFFF, Math.round(255 * opacity));
        g.blit(RenderPipelines.GUI_TEXTURED, TEXTURE_ID, x0, y0, 0, 0, size, size, resolution, resolution, resolution, resolution, white);

        double cx = x0 + size / 2.0;
        double cy = y0 + size / 2.0;
        double radius = size / 2.0 - 2;
        String dim = session.dimension();

        // Seed-map predictions (dimmer) first, then discovered structures, entities, waypoints, player.
        if (config.structuresOnMinimap) {
            double range = radius * blocksPerGuiPixel * 1.5;
            SeedMapService seeds = session.seedMap();
            if (config.showPredictedStructures && seeds.isActive(dim)) {
                seeds.request(0, dim, (int) (px - range), (int) (pz - range), (int) (px + range), (int) (pz + range));
                seeds.forEachVisible(dim, (int) (px - range), (int) (pz - range), (int) (px + range), (int) (pz + range), session.markers(),
                    p -> drawStructure(g, p.kind(), p.x(), p.z(), px, pz, rotation, blocksPerGuiPixel, cx, cy, radius, circle, true, config));
            }
            if (config.showDiscoveredStructures) {
                for (Marker m : session.markers().all()) {
                    if (m.hidden || !m.dimension.equals(dim)) continue;
                    drawStructure(g, StructureKinds.byKey(m.type), m.x, m.z, px, pz, rotation, blocksPerGuiPixel, cx, cy, radius, circle, false, config);
                }
            }
        }
        drawEntities(g, mc, level, player, px, pz, rotation, blocksPerGuiPixel, cx, cy, radius, circle, config);
        if (config.showWaypointsOnMinimap) drawWaypoints(g, session, dim, px, pz, rotation, blocksPerGuiPixel, cx, cy, radius, circle);
        drawCardinals(g, mc.font, rotation, cx, cy, radius, circle, config);

        // Player arrow in the center.
        g.pose().pushMatrix();
        g.pose().translate((float) cx, (float) cy);
        if (!config.minimapRotate) g.pose().rotate((float) Math.toRadians(yaw + 180));
        Icons.drawCentered(g, Icons.PLAYER_ARROW, 10, 0xFFFFFFFF);
        g.pose().popMatrix();

        drawInfo(g, mc, player, level, x0, y0, size, config);
    }

    private static void ensureTexture(Minecraft mc, int resolution) {
        if (texture != null && textureSize == resolution) return;
        if (texture != null) mc.getTextureManager().release(TEXTURE_ID);
        texture = new DynamicTexture(() -> "Lumen Atlas minimap", resolution, resolution, true);
        mc.getTextureManager().register(TEXTURE_ID, texture);
        textureSize = resolution;
    }

    private static boolean shouldUseCave(LumenConfig config, ClientLevel level, LocalPlayer player) {
        if (config.caveMode == LumenConfig.CaveMode.OFF) return false;
        if (level.dimensionType().hasCeiling()) return true;
        if (level.dimension() == Level.END) return false;
        BlockPos head = BlockPos.containing(player.getX(), player.getEyeY(), player.getZ());
        if (level.canSeeSky(head)) return false;
        int surface = level.getHeight(Heightmap.Types.WORLD_SURFACE, head.getX(), head.getZ());
        return head.getY() < surface - 4;
    }

    private static void compose(MapStore store, ClientLevel level, double px, double pz, double rotation, double blocksPerTexel,
                                boolean circle, LumenConfig config) {
        NativeImage image = texture.getPixels();
        int res = textureSize;
        double half = res / 2.0;
        double r = Math.toRadians(rotation);
        double cos = Math.cos(r);
        double sin = Math.sin(r);
        int alpha = Math.round(255 * config.minimapOpacity);
        double borderWidth = Math.max(1.5, res / 90.0);
        double outer2 = half * half;
        double inner = half - borderWidth;
        double inner2 = inner * inner;
        double accent = half - borderWidth * 0.55;
        double accent2 = accent * accent;
        MapView view = config.minimapView;
        RegionCursor cursor = new RegionCursor(store);
        for (int ty = 0; ty < res; ty++) {
            double sy = ty - half + 0.5;
            for (int tx = 0; tx < res; tx++) {
                double sx = tx - half + 0.5;
                int color;
                if (circle) {
                    double d2 = sx * sx + sy * sy;
                    if (d2 > outer2) {
                        image.setPixel(tx, ty, 0);
                        continue;
                    }
                    if (d2 > inner2) {
                        image.setPixel(tx, ty, d2 > accent2 ? BORDER_OUTER : BORDER_INNER);
                        continue;
                    }
                } else {
                    double m = Math.max(Math.abs(sx), Math.abs(sy));
                    if (m > inner) {
                        image.setPixel(tx, ty, m > accent ? BORDER_OUTER : BORDER_INNER);
                        continue;
                    }
                }
                double dx = (sx * cos - sy * sin) * blocksPerTexel;
                double dz = (sx * sin + sy * cos) * blocksPerTexel;
                int wx = (int) Math.floor(px + dx);
                int wz = (int) Math.floor(pz + dz);
                color = caveActive ? CAVE.pixel(wx, wz, view) : cursor.pixel(wx, wz, view);
                if (color == 0) color = UNKNOWN_COLOR;
                image.setPixel(tx, ty, Colors.withAlpha(color, alpha));
            }
        }
    }

    /** Fast column lookups that remember the last region. */
    private static final class RegionCursor {
        private final MapStore store;
        private int rx = Integer.MIN_VALUE;
        private int rz = Integer.MIN_VALUE;
        private MapRegion region;

        RegionCursor(MapStore store) {
            this.store = store;
        }

        private MapRegion region(int x, int z) {
            int nrx = MapRegion.regionCoord(x);
            int nrz = MapRegion.regionCoord(z);
            if (nrx != rx || nrz != rz) {
                rx = nrx;
                rz = nrz;
                region = store.get(rx, rz);
            }
            return region;
        }

        private int height(int x, int z) {
            MapRegion reg = region(x, z);
            if (reg == null) return MapShading.NO_NEIGHBOR;
            int h = reg.height(MapRegion.index(MapRegion.local(x), MapRegion.local(z)));
            return h == MapRegion.UNKNOWN ? MapShading.NO_NEIGHBOR : h;
        }

        int pixel(int x, int z, MapView view) {
            int north = height(x, z - 1);
            int west = height(x - 1, z);
            MapRegion reg = region(x, z);
            if (reg == null) return 0;
            int i = MapRegion.index(MapRegion.local(x), MapRegion.local(z));
            int h = reg.height(i);
            if (h == MapRegion.UNKNOWN) return 0;
            int biome = view == MapView.BIOMES ? BiomePalette.color(reg.biome(i)) : 0;
            return MapShading.pixel(view, reg.surface(i), h, north, west, biome);
        }
    }

    /** World position to minimap screen position; returns null when outside (unless {@code clamp}). */
    private static double[] project(double wx, double wz, double px, double pz, double rotation, double bpp,
                                    double cx, double cy, double radius, boolean circle, boolean clamp) {
        double[] s = MapMath.rotate((wx - px) / bpp, (wz - pz) / bpp, rotation);
        boolean inside = circle ? s[0] * s[0] + s[1] * s[1] <= radius * radius : Math.abs(s[0]) <= radius && Math.abs(s[1]) <= radius;
        if (!inside) {
            if (!clamp) return null;
            s = circle ? MapMath.clampToCircle(s[0], s[1], radius) : MapMath.clampToSquare(s[0], s[1], radius);
            return new double[] {cx + s[0], cy + s[1], 1};
        }
        return new double[] {cx + s[0], cy + s[1], 0};
    }

    private static void drawStructure(GuiGraphicsExtractor g, StructureKind kind, int x, int z, double px, double pz, double rotation,
                                      double bpp, double cx, double cy, double radius, boolean circle, boolean predicted, LumenConfig config) {
        if (!config.isStructureTypeVisible(kind.key())) return;
        double[] s = project(x + 0.5, z + 0.5, px, pz, rotation, bpp, cx, cy, radius - 5, circle, false);
        if (s == null) return;
        int size = Math.round(12 * config.iconScale);
        Icons.structure(g, kind, (int) Math.round(s[0]), (int) Math.round(s[1]), size, predicted, false);
    }

    private static void drawWaypoints(GuiGraphicsExtractor g, WorldSession session, String dim, double px, double pz, double rotation,
                                      double bpp, double cx, double cy, double radius, boolean circle) {
        for (Waypoint w : session.waypoints().all()) {
            if (!w.enabled || !w.dimension.equals(dim)) continue;
            double[] s = project(w.x + 0.5, w.z + 0.5, px, pz, rotation, bpp, cx, cy, radius - 4, circle, true);
            int sx = (int) Math.round(s[0]);
            int sy = (int) Math.round(s[1]);
            if (s[2] > 0) {
                // Off the map: a small arrow on the edge pointing at it.
                double angle = Math.atan2(sy - cy, sx - cx) + Math.PI / 2;
                g.pose().pushMatrix();
                g.pose().translate(sx, sy);
                g.pose().rotate((float) angle);
                Icons.drawCentered(g, Icons.POINTER, 8, Colors.opaque(w.color));
                g.pose().popMatrix();
            } else {
                Icons.waypoint(g, WaypointIcon.byKeyOrPin(w.icon).ordinal(), w.color, sx, sy, 11, 1f);
            }
        }
    }

    private static void drawEntities(GuiGraphicsExtractor g, Minecraft mc, ClientLevel level, LocalPlayer self, double px, double pz,
                                     double rotation, double bpp, double cx, double cy, double radius, boolean circle, LumenConfig config) {
        if (!config.showPlayers && !config.showHostileMobs && !config.showPassiveMobs) return;
        double range = radius * bpp;
        for (Entity e : level.entitiesForRendering()) {
            if (e == self || !e.isAlive()) continue;
            int color;
            if (e instanceof Player) {
                if (!config.showPlayers) continue;
                color = 0xFFFFFFFF;
            } else {
                MobCategory category = e.getType().getCategory();
                if (category == MobCategory.MONSTER) {
                    if (!config.showHostileMobs) continue;
                    color = 0xFFF87171;
                } else if (category != MobCategory.MISC) {
                    if (!config.showPassiveMobs) continue;
                    color = 0xFF86EFAC;
                } else {
                    continue;
                }
            }
            if (Math.abs(e.getX() - px) > range || Math.abs(e.getZ() - pz) > range) continue;
            double[] s = project(e.getX(), e.getZ(), px, pz, rotation, bpp, cx, cy, radius - 2, circle, false);
            if (s == null) continue;
            int sx = (int) Math.round(s[0]);
            int sy = (int) Math.round(s[1]);
            Icons.draw(g, Icons.DOT, sx - 3, sy - 3, 6, color);
            if (e instanceof Player p && config.showPlayers) {
                String name = p.getName().getString();
                var font = mc.font;
                g.text(font, name, sx - font.width(name) / 2, sy - 11, 0xFFFFFFFF, true);
            }
        }
    }

    private static void drawCardinals(GuiGraphicsExtractor g, Font font, double rotation, double cx, double cy, double radius,
                                      boolean circle, LumenConfig config) {
        if (!config.showCardinals) return;
        String[] names = {"N", "E", "S", "W"};
        double[][] dirs = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}};
        for (int i = 0; i < 4; i++) {
            double[] s = MapMath.rotate(dirs[i][0], dirs[i][1], rotation);
            double edge = radius - 1;
            double[] p = circle ? new double[] {s[0] * edge, s[1] * edge} : MapMath.clampToSquare(s[0] * edge * 2, s[1] * edge * 2, edge);
            int x = (int) Math.round(cx + p[0]);
            int y = (int) Math.round(cy + p[1]);
            g.fill(x - 5, y - 5, x + 5, y + 5, 0xE0100E18);
            g.text(font, names[i], x - font.width(names[i]) / 2 + 1, y - 4, i == 0 ? Theme.ACCENT : 0xFFD8D4E8, false);
        }
    }

    private static void drawInfo(GuiGraphicsExtractor g, Minecraft mc, LocalPlayer player, ClientLevel level, int x0, int y0, int size,
                                 LumenConfig config) {
        Font font = mc.font;
        int y = y0 + size + 3;
        int cx = x0 + size / 2;
        if (config.showCoordinates) {
            String coords = player.getBlockX() + ", " + player.getBlockY() + ", " + player.getBlockZ();
            if (config.showFacing) coords += "  " + MapMath.facing(player.getYRot());
            if (caveActive) coords += "  ▼";
            drawCentered(g, font, coords, cx, y, Theme.TEXT);
            y += 10;
        }
        if (config.showBiome) {
            long now = System.currentTimeMillis();
            if (now - biomeCheckedAt > 500) {
                biomeCheckedAt = now;
                biomeName = level.getBiome(player.blockPosition()).unwrapKey()
                    .map(k -> Component.translatable("biome." + k.identifier().getNamespace() + "." + k.identifier().getPath()).getString())
                    .orElse("");
            }
            drawCentered(g, font, biomeName, cx, y, Theme.TEXT_DIM);
        }
    }

    private static void drawCentered(GuiGraphicsExtractor g, Font font, String text, int cx, int y, int color) {
        int w = font.width(text);
        g.fill(cx - w / 2 - 3, y - 1, cx + w / 2 + 3, y + 9, 0xA0100E18);
        g.text(font, text, cx - w / 2, y, color, false);
    }

    /** Frees the texture (resource reload / leaving the game). */
    public static void reset() {
        CAVE.clear();
        lastCompose = 0;
    }

    static String lower(Enum<?> e) {
        return e.name().toLowerCase(Locale.ROOT);
    }
}
