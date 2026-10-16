package com.trevorschoeny.sandboxes.sandbox;

import com.trevorschoeny.sandboxes.SandboxesClient;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.concurrent.ExecutionException;

/** Leaving one world and opening another, and flushing a world to disk before it's copied. */
public final class WorldSwitch {

    private WorldSwitch() {}

    /**
     * Saves and closes the current world, then opens {@code levelId}. Does
     * nothing while a copy, sync or delete is running ({@link DiskJobs}).
     *
     * <p>Vanilla's own disconnect is what waits for the integrated server to
     * finish and release the world's lock, so the world being opened can take
     * it. Call on the client thread.
     */
    public static void openLevel(String levelId) {
        Minecraft mc = Minecraft.getInstance();
        if (DiskJobs.running()) {
            // Closing the world now would save it under a copy that's still reading it.
            SandboxesClient.LOGGER.info("[sandboxes] not switching to {} while a copy is running", levelId);
            return;
        }
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
     * the quick button) so they can't drift apart.
     *
     * <p>Nothing about the player crosses at this point. A sandbox is forked
     * once, at create or sync, and from then on it is its own world with its own
     * position and inventory: joining puts the player back where they left off
     * inside it, not where they are standing in the main world.
     */
    public static void enterSandbox(Sandbox sandbox) {
        if (DiskJobs.running()) return;   // see openLevel
        SandboxStore.markJoined(sandbox);
        openLevel(sandbox.levelId());
    }

    /**
     * The quick hop between a world and its sandbox, shared by the pause-menu
     * quick button and the hop key so they can't drift apart.
     *
     * <p>Inside a sandbox it returns to the main world. In a world it joins the
     * sandbox joined most recently. In a world with no sandboxes it does nothing:
     * making a whole world copy from a stray keypress would be too much.
     */
    public static void hop() {
        Hop target = hopTarget();
        if (target != null) target.go().run();
    }

    /** Where {@link #hop()} would go: a name to show, and the way there. */
    public record Hop(Component label, Runnable go) {}

    /**
     * Where {@link #hop()} goes right now, or null when it would do nothing.
     * The quick button's tooltip reads this, so what it says is where it goes.
     */
    public static @Nullable Hop hopTarget() {
        String levelId = CurrentWorld.levelId();
        if (levelId == null) return null;
        if (SandboxStore.isSandbox(levelId)) {
            String parent = SandboxStore.parentOf(levelId);
            return new Hop(Component.translatable("sandboxes.quick.back", parent), () -> openLevel(parent));
        }
        List<Sandbox> sandboxes = SandboxStore.list(levelId);
        if (sandboxes.isEmpty()) return null;
        Sandbox latest = sandboxes.get(0);    // list() is most recently joined first
        return new Hop(Component.translatable("sandboxes.quick.to", latest.name()), () -> enterSandbox(latest));
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
