package com.lumenatlas.dev;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;

/** Version specific calls used only by the self test (26.3 variant). Not part of the release jars. */
final class DevCompat {
    private DevCompat() {}

    static RenderTarget mainRenderTarget(Minecraft mc) {
        return mc.gameRenderer.mainRenderTarget();
    }
}
