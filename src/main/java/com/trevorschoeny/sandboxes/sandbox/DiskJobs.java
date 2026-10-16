package com.trevorschoeny.sandboxes.sandbox;

import com.trevorschoeny.sandboxes.SandboxesClient;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.network.chat.Component;

import org.jspecify.annotations.Nullable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Copies, syncs and deletes running in the background, and what each one is
 * doing.
 *
 * <p>While any of them runs, nothing may leave the world: closing it saves its
 * region files again, under a copy that is still reading them, and the new
 * sandbox comes out torn. {@link WorldSwitch} asks {@link #running()} before
 * every world switch, and the screen, the quick button and the pause menu
 * (Save and Quit included) grey their ways out. Quitting the game outright
 * can't be stopped.
 */
public final class DiskJobs {

    private DiskJobs() {}

    /** Sandboxes being synced or deleted: id to what's happening to it. */
    private static final Map<String, Component> BUSY = new ConcurrentHashMap<>();
    /** A sandbox being copied into existence (create or duplicate), or null. */
    private static volatile @Nullable Component copying = null;

    public interface Work {
        void run() throws Exception;
    }

    /** True while any copy, sync or delete is under way. */
    public static boolean running() {
        return copying != null || !BUSY.isEmpty();
    }

    /** What's happening to this sandbox right now, or null when nothing is. */
    public static @Nullable Component busy(String sandboxId) {
        return BUSY.get(sandboxId);
    }

    /** What a new sandbox's copy is doing ("Creating sandbox…"), or null when none is being made. */
    public static @Nullable Component copying() {
        return copying;
    }

    /**
     * Runs {@code work} on its own thread. With a sandbox id, that sandbox is
     * marked busy; without one, it's a new sandbox being copied. Call on the
     * client thread; {@code finished} runs back on it afterwards, failed or not.
     */
    public static void run(@Nullable String sandboxId, Component message, Work work, Runnable finished) {
        if (sandboxId != null) BUSY.put(sandboxId, message);
        else copying = message;
        Thread.ofPlatform().name("sandboxes-io").start(() -> {
            try {
                work.run();
            } catch (Exception e) {
                SandboxesClient.LOGGER.error("[sandboxes] {} failed", message.getString(), e);
            } finally {
                if (sandboxId != null) BUSY.remove(sandboxId);
                else copying = null;
                Minecraft.getInstance().execute(() -> {
                    // A pause menu opened during the copy greyed its ways out; lay it
                    // out again so they come back.
                    Minecraft mc = Minecraft.getInstance();
                    if (mc.gui.screen() instanceof PauseScreen pause) pause.resize(pause.width, pause.height);
                    finished.run();
                });
            }
        });
    }
}
