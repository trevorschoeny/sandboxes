package com.trevorschoeny.sandboxes.sandbox;

import com.trevorschoeny.sandboxes.SandboxesClient;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

import java.nio.file.Path;

import java.util.concurrent.ExecutionException;

/** Leaving one world and opening another, and flushing a world to disk before it's copied. */
public final class WorldSwitch {

    private WorldSwitch() {}

    /**
     * Saves and closes the current world, then opens {@code levelId}.
     *
     * <p>Vanilla's own disconnect is what waits for the integrated server to
     * finish and release the world's lock, so the world being opened can take
     * it. Call on the client thread.
     */
    public static void openLevel(String levelId) {
        Minecraft mc = Minecraft.getInstance();
        SandboxesClient.LOGGER.info("[sandboxes] switching to {}", levelId);

        mc.disconnect(new GenericMessageScreen(Component.translatable("menu.savingLevel")), false);
        mc.createWorldOpenFlows().openWorld(levelId, () -> {
            // openWorld only calls this when it gives up (a held lock, an
            // unreadable level). Land on the title screen rather than a black
            // one, and say why in the log.
            SandboxesClient.LOGGER.error("[sandboxes] couldn't open {}", levelId);
            mc.gui.setScreen(new TitleScreen());
        });
    }

    /**
     * Joins a sandbox.
     *
     * <p>Every way in goes through here (the list's Join, a double-clicked row,
     * the quick button), so the arrival is the same however the player got here:
     * the world is saved, the player is put where they are standing right now,
     * and only then does the sandbox open.
     */
    public static void enterSandbox(Sandbox sandbox) {
        Minecraft mc = Minecraft.getInstance();
        SandboxStore.markJoined(sandbox);
        Path parentDir = SandboxStore.savesDir().resolve(sandbox.parentId());

        // Saving blocks until the server has written everything, so it can't run
        // on the client thread.
        Thread.ofPlatform().name("sandboxes-enter").start(() -> {
            saveCurrentWorld();
            WorldFiles.placePlayerLikeParent(parentDir, sandbox.dir());
            mc.execute(() -> openLevel(sandbox.levelId()));
        });
    }

    /**
     * Flushes the running world to disk and waits for it.
     *
     * <p>A copy reads files, so anything still only in memory would be missing
     * from the sandbox: chunks the autosave hasn't reached, and entities that
     * have moved since (a villager would appear at its last saved position).
     * Call off the client thread; this blocks until the server has saved.
     */
    public static void saveCurrentWorld() {
        MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
        if (server == null) return;
        try {
            server.submit(() -> server.saveEverything(true, true, false)).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            // Copying a slightly stale world beats refusing to make a sandbox,
            // so this is logged and the copy goes ahead.
            SandboxesClient.LOGGER.error("[sandboxes] pre-copy save failed", e);
        }
    }
}
