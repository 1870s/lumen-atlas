package com.lumenatlas.hud;

import com.lumenatlas.config.ConfigManager;
import com.lumenatlas.core.LumenConfig;
import com.lumenatlas.core.util.Colors;
import com.lumenatlas.core.util.MapMath;
import com.lumenatlas.core.waypoint.Waypoint;
import com.lumenatlas.core.waypoint.WaypointIcon;
import com.lumenatlas.gui.Icons;
import com.lumenatlas.world.WorldSession;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Waypoints in the 3D world. Instead of drawing into the world (which would need render hooks that performance mods
 * like Sodium replace), each waypoint is projected to the screen with the camera's view and field of view and drawn
 * as part of the HUD: an icon with the name and distance, which fades out near the crosshair so it never hides what
 * the player is looking at.
 */
public final class WaypointHud {
    private record OnScreen(Waypoint waypoint, float x, float y, double distance) {}

    private WaypointHud() {}

    public static void render(GuiGraphicsExtractor g, Minecraft mc, WorldSession session, float partialTick) {
        LumenConfig config = ConfigManager.get();
        if (!config.showWaypointsInWorld) return;
        Camera camera = com.lumenatlas.compat.Compat.camera(mc);
        Vec3 cam = camera.position();
        Matrix4f view = camera.getViewRotationMatrix(new Matrix4f());
        float fov = camera.getFov();
        if (fov <= 1f) fov = mc.options.fov().get();
        double tanHalf = Math.tan(Math.toRadians(fov) / 2);
        int w = g.guiWidth();
        int h = g.guiHeight();
        double aspect = (double) w / h;
        String dim = session.dimension();

        List<OnScreen> visible = new ArrayList<>();
        for (Waypoint wp : session.waypoints().all()) {
            if (!wp.enabled || !wp.showInWorld || !wp.dimension.equals(dim)) continue;
            double distance = wp.distanceTo(cam.x, cam.y, cam.z);
            if (distance > config.inWorldWaypointRange || distance < 2) continue;
            Vector3f v = new Vector3f((float) (wp.x + 0.5 - cam.x), (float) (wp.y + 1.2 - cam.y), (float) (wp.z + 0.5 - cam.z));
            view.transformPosition(v);
            if (v.z > -0.1f) continue; // behind the camera
            double ndcX = (v.x / -v.z) / (tanHalf * aspect);
            double ndcY = (v.y / -v.z) / tanHalf;
            if (Math.abs(ndcX) > 1.1 || Math.abs(ndcY) > 1.1) continue;
            float sx = (float) ((ndcX + 1) / 2 * w);
            float sy = (float) ((1 - ndcY) / 2 * h);
            visible.add(new OnScreen(wp, sx, sy, distance));
        }
        // Far ones first, so near labels are drawn on top.
        visible.sort((a, b) -> Double.compare(b.distance, a.distance));
        Font font = mc.font;
        for (OnScreen s : visible) {
            double dx = s.x - w / 2.0;
            double dy = s.y - h / 2.0;
            double fromCenter = Math.sqrt(dx * dx + dy * dy);
            // Slightly fade waypoints right under the crosshair so they never hide what you aim at.
            float alpha = (float) Math.max(0.6, Math.min(1, (fromCenter - 8) / 40));
            String name = s.waypoint.name;
            String dist = MapMath.distance(s.distance);
            int nw = font.width(name);
            int dw = font.width(dist);
            int boxW = Math.max(nw, dw) + 6;
            int cx = Math.max(boxW / 2 + 2, Math.min(w - boxW / 2 - 2, Math.round(s.x)));
            int cy = Math.max(8, Math.min(h - 32, Math.round(s.y)));
            int textAlpha = Math.round(255 * alpha);
            Icons.waypoint(g, WaypointIcon.byKeyOrPin(s.waypoint.icon).ordinal(), s.waypoint.color, cx, cy, 12, alpha);
            g.fill(cx - boxW / 2, cy + 8, cx + boxW / 2, cy + 29, Colors.withAlpha(0x100E18, Math.round(textAlpha * 0.7f)));
            g.text(font, name, cx - nw / 2, cy + 10, Colors.withAlpha(0xFFFFFF, textAlpha), false);
            g.text(font, dist, cx - dw / 2, cy + 20, Colors.withAlpha(s.waypoint.color, textAlpha), false);
        }
    }
}
