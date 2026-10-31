package com.lumenatlas.fabric;

import com.lumenatlas.Keybinds;
import com.lumenatlas.LumenAtlas;
import java.nio.file.Path;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;

/** Fabric entry point: wires Fabric API callbacks into {@link LumenAtlas}. */
public class LumenAtlasFabric implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        KeyMapping.Category category = KeyMapping.Category.register(Keybinds.CATEGORY_ID);
        LumenAtlas.init(new LumenAtlas.Platform() {
            @Override
            public Path configDir() {
                return FabricLoader.getInstance().getConfigDir();
            }

            @Override
            public String loaderName() {
                return "Fabric";
            }
        }, category);
        for (KeyMapping key : Keybinds.all()) KeyMappingHelper.registerKeyMapping(key);
        // Below the chat and boss bars, above the hotbar and status effects.
        HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT, LumenAtlas.HUD_LAYER_ID, LumenAtlas::renderHud);
        ClientTickEvents.END_CLIENT_TICK.register(minecraft -> LumenAtlas.onClientTick());
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!overlay) LumenAtlas.onSystemMessage(message);
        });
    }
}
