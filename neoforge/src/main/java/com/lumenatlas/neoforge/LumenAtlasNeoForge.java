package com.lumenatlas.neoforge;

import com.lumenatlas.Keybinds;
import com.lumenatlas.LumenAtlas;
import java.nio.file.Path;
import net.minecraft.client.KeyMapping;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ClientChatReceivedEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import net.neoforged.neoforge.common.NeoForge;

/** NeoForge entry point: wires NeoForge events into {@link LumenAtlas}. */
@Mod(value = LumenAtlas.MOD_ID, dist = Dist.CLIENT)
public class LumenAtlasNeoForge {
    private static final KeyMapping.Category CATEGORY = new KeyMapping.Category(Keybinds.CATEGORY_ID);

    public LumenAtlasNeoForge(IEventBus modBus) {
        LumenAtlas.init(new LumenAtlas.Platform() {
            @Override
            public Path configDir() {
                return FMLPaths.CONFIGDIR.get();
            }

            @Override
            public String loaderName() {
                return "NeoForge";
            }
        }, CATEGORY);

        modBus.addListener(FMLClientSetupEvent.class, event -> event.enqueueWork(LumenAtlas::onClientSetup));
        modBus.addListener(RegisterKeyMappingsEvent.class, event -> {
            event.registerCategory(CATEGORY);
            for (KeyMapping key : Keybinds.all()) event.register(key);
        });
        // Below the chat and boss bars, above the hotbar and status effects.
        modBus.addListener(RegisterGuiLayersEvent.class, event ->
            event.registerBelow(VanillaGuiLayers.CHAT, LumenAtlas.HUD_LAYER_ID, LumenAtlas::renderHud));
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, event -> LumenAtlas.onClientTick());
        NeoForge.EVENT_BUS.addListener(ClientChatReceivedEvent.System.class, event -> {
            if (!event.isOverlay()) LumenAtlas.onSystemMessage(event.getMessage());
        });
    }
}
