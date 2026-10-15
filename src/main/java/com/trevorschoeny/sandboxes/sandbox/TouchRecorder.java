package com.trevorschoeny.sandboxes.sandbox;

import com.trevorschoeny.sandboxes.SandboxesClient;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Keeps a sandbox's {@link TouchedBlocks} up to date while it runs.
 *
 * <p>Only active while the integrated server is running a tracked sandbox. Every
 * block change on that server lands here (see {@code LevelChunkMixin}) and costs
 * a hash-set insert or two. The record is written when the world saves and when it
 * closes.
 *
 * <p>It notes every change, not just the player's: growing crops, flowing water
 * and the like also mark their section. That only makes the overlay look at a
 * few more sections; it still shows a block only where the two worlds differ.
 */
public final class TouchRecorder {

    private TouchRecorder() {}

    private static @Nullable Path sandboxDir = null;
    private static @Nullable TouchedBlocks record = null;
    private static boolean dirty = false;

    public static void install() {
        ServerLifecycleEvents.SERVER_STARTED.register(TouchRecorder::start);
        ServerLifecycleEvents.AFTER_SAVE.register((server, flush, force) -> flush());
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            flush();
            sandboxDir = null;
            record = null;
        });
    }

    private static void start(MinecraftServer server) {
        Path root = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        Path parent = root.getParent();
        // A sandbox is a world whose folder sits in a "sandboxes" folder.
        if (parent == null || !SandboxStore.SANDBOX_DIR.equals(parent.getFileName().toString())) return;

        // A sandbox from before records existed stays untracked until its next
        // sync. Starting a record halfway would claim "nothing else changed",
        // which isn't known.
        TouchedBlocks.load(root).ifPresent(loaded -> {
            sandboxDir = root;
            record = loaded;
            dirty = false;
        });
    }

    /** Called for every block change on the integrated server. */
    public static void touch(ResourceKey<Level> dimension, BlockPos pos) {
        TouchedBlocks current = record;
        if (current == null) return;
        if (current.add(dimension.identifier().toString(), pos.asLong())) dirty = true;
    }

    /** The live record of the sandbox being played, for the overlay; null when not in a tracked one. */
    public static @Nullable TouchedBlocks live() {
        return record;
    }

    private static void flush() {
        TouchedBlocks current = record;
        Path dir = sandboxDir;
        if (current == null || dir == null || !dirty) return;
        try {
            current.save(dir);
            dirty = false;
        } catch (IOException e) {
            SandboxesClient.LOGGER.error("[sandboxes] couldn't save touched sections for {}", dir, e);
        }
    }
}
