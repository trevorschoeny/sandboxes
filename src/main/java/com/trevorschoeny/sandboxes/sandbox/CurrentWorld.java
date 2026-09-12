package com.trevorschoeny.sandboxes.sandbox;

import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.world.level.storage.LevelResource;

import org.jspecify.annotations.Nullable;

import java.nio.file.Path;

/**
 * Which world the player is in, as the level id vanilla opens worlds with.
 *
 * <p>A top-level world's id is its folder name ({@code Riverbend}); a sandbox's
 * is the path down to it ({@code Riverbend/sandboxes/<uuid>}). Vanilla resolves
 * a level id straight against {@code saves/}, so the nested form opens the same
 * way a normal world does.
 */
public final class CurrentWorld {

    private CurrentWorld() {}

    /** The running world's level id, or null on the title screen or a server. */
    public static @Nullable String levelId() {
        IntegratedServer server = Minecraft.getInstance().getSingleplayerServer();
        if (server == null) return null;
        Path root = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        Path saves = SandboxStore.savesDir().toAbsolutePath().normalize();
        if (!root.startsWith(saves)) return null;
        // Always '/' so the id matches what vanilla resolves and what a sandbox
        // path is built from, on Windows too.
        return saves.relativize(root).toString().replace('\\', '/');
    }

    /** True when the player is inside a sandbox right now. */
    public static boolean inSandbox() {
        String id = levelId();
        return id != null && SandboxStore.isSandbox(id);
    }

    /**
     * The world whose sandbox list belongs on screen: the current world, or the
     * parent when the player is already inside a sandbox.
     */
    public static @Nullable String hostWorldId() {
        String id = levelId();
        if (id == null) return null;
        return SandboxStore.isSandbox(id) ? SandboxStore.parentOf(id) : id;
    }

    /** Display name of the parent world, for the "Return to <world>" button. */
    public static @Nullable String parentDisplayName() {
        String id = levelId();
        if (id == null || !SandboxStore.isSandbox(id)) return null;
        return SandboxStore.parentOf(id);
    }
}
