package com.trevorschoeny.sandboxes.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

import com.trevorschoeny.sandboxes.SandboxesClient;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Sandboxes config. One option today: the quick-sandbox button in the pause
 * menu's top-right corner. On by default, because it sits in the pause menu
 * rather than the shared inventory corner and so doesn't compete with other
 * mods for space.
 *
 * <p>Same static-field and JSON shape as the other Trevlar mods, persisted to
 * {@code config/sandboxes/config.json}.
 */
public final class SandboxesConfig {

    private SandboxesConfig() {}

    private static final int CURRENT_VERSION = 1;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static boolean quickButton = true;
    /** The same button above the inventory too. Off by default: that edge is where inventory mods put their own buttons. */
    private static boolean quickButtonInventory = false;

    private static boolean loaded = false;

    private static Path filePath() {
        return FabricLoader.getInstance().getConfigDir()
                .resolve("sandboxes")
                .resolve("config.json");
    }

    public static void load() {
        if (loaded) return;
        loaded = true;
        Path path = filePath();
        if (!Files.exists(path)) {
            SandboxesClient.LOGGER.info("[config] no config at {}, using defaults", path);
            return;
        }
        try {
            JsonObject root = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
            quickButton = root.has("quickButton") && root.get("quickButton").isJsonPrimitive()
                    ? root.get("quickButton").getAsBoolean() : quickButton;
            quickButtonInventory = root.has("quickButtonInventory") && root.get("quickButtonInventory").isJsonPrimitive()
                    ? root.get("quickButtonInventory").getAsBoolean() : quickButtonInventory;
            SandboxesClient.LOGGER.info("[config] loaded from {}", path);
        } catch (IOException | JsonSyntaxException | IllegalStateException e) {
            SandboxesClient.LOGGER.error("[config] failed to read {}, using defaults", path, e);
        }
    }

    private static void save() {
        Path path = filePath();
        try {
            Files.createDirectories(path.getParent());
            JsonObject root = new JsonObject();
            root.addProperty("version", CURRENT_VERSION);
            root.addProperty("quickButton", quickButton);
            root.addProperty("quickButtonInventory", quickButtonInventory);
            Files.writeString(path, GSON.toJson(root));
        } catch (IOException e) {
            SandboxesClient.LOGGER.error("[config] failed to write {}, changes won't persist", path, e);
        }
    }

    public static boolean quickButton() { return quickButton; }
    public static void setQuickButton(boolean v) { quickButton = v; save(); }
    public static boolean quickButtonInventory() { return quickButtonInventory; }
    public static void setQuickButtonInventory(boolean v) { quickButtonInventory = v; save(); }

}
