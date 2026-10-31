package com.lumenatlas.config;

import com.lumenatlas.LumenAtlas;
import com.lumenatlas.core.LumenConfig;
import com.lumenatlas.core.io.AtomicFiles;
import com.lumenatlas.world.IoWorker;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/** Loads and saves {@code config/lumenatlas.json}. */
public final class ConfigManager {
    private static LumenConfig config = new LumenConfig();

    private ConfigManager() {}

    public static LumenConfig get() {
        return config;
    }

    private static Path file() {
        return LumenAtlas.platform().configDir().resolve(LumenAtlas.MOD_ID + ".json");
    }

    public static void load() {
        try {
            LumenConfig loaded = AtomicFiles.read(file(), bytes -> LumenConfig.fromJson(new String(bytes, StandardCharsets.UTF_8)));
            config = loaded != null ? loaded : new LumenConfig();
        } catch (IOException e) {
            LumenAtlas.LOGGER.warn("Could not read the Lumen Atlas config, using defaults", e);
            config = new LumenConfig();
        }
        save();
    }

    /** Saves in the background; the JSON is made on the calling thread so later changes cannot race the write. */
    public static void save() {
        byte[] json = config.sanitize().toJson().getBytes(StandardCharsets.UTF_8);
        Path target = file();
        IoWorker.submit("config", () -> AtomicFiles.write(target, json));
    }
}
