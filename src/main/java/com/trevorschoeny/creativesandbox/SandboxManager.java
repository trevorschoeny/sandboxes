package com.trevorschoeny.creativesandbox;

import com.trevorschoeny.creativesandbox.mixin.MinecraftServerAccessor;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * Manages sandbox metadata (which sandboxes exist for a world) and world-switching.
 *
 * Persistence: sandbox metadata is stored in the player's NBT save data
 * (trevormod_sandboxes), so it travels with the world. When a sandbox is
 * created via world-copy, patchToCreative() strips the trevormod_sandboxes
 * tag from the copied playerdata so sandboxes don't inherit the parent's list.
 */
public class SandboxManager {
    private static final SandboxManager INSTANCE = new SandboxManager();
    private static final Path SAVES_DIR = FabricLoader.getInstance().getGameDir().resolve("saves");

    // ── In-memory sandbox metadata ────────────────────────────────────────────
    // CopyOnWriteArrayList: reads (render thread every frame) vastly outnumber writes
    // (create/delete/load). Lock-free reads, synchronized writes — no CME risk.
    private final List<SandboxMetadata> sandboxes = new CopyOnWriteArrayList<>();

    private SandboxManager() {}

    public static SandboxManager get() {
        return INSTANCE;
    }

    // ── World switching ───────────────────────────────────────────────────────

    // How long to wait for the old server thread to die before giving up
    private static final long SERVER_SHUTDOWN_TIMEOUT_MS = 30_000;

    /**
     * Disconnect from the current world and open a different one.
     *
     * The core challenge: mc.disconnect() triggers FastQuit, which keeps the
     * server thread alive for background saving. This prevents the server from
     * ever reaching isShutdown(), and XaeroLib throws "Multiple servers running"
     * when we try to open a new world.
     *
     * Solution: kill the server BEFORE disconnect, so FastQuit has nothing to save.
     *
     * 1. Pre-save: flush all data to disk while the server is still fully active
     * 2. Halt: tell the server to stop via halt(false), bypassing FastQuit
     * 3. Join: wait for the server thread to die (session.lock released)
     * 4. Disconnect + open: client cleanup then open the target world
     */
    public static void disconnectAndOpen(String folderName) {
        Minecraft mc = Minecraft.getInstance();
        IntegratedServer oldServer = mc.getSingleplayerServer();

        // Show immediate feedback
        mc.setScreen(new GenericMessageScreen(Component.literal("Switching worlds...")));

        new Thread(() -> {
            // ── 1. Pre-save ───────────────────────────────────────────────
            // Flush all world data to disk while the server is still fully active.
            if (oldServer != null) {
                CreativeSandboxMod.LOGGER.info("[Sandboxes] Pre-saving world before switch...");
                try {
                    oldServer.submit(() -> oldServer.saveEverything(false, true, true)).get();
                    CreativeSandboxMod.LOGGER.info("[Sandboxes] Pre-save complete.");
                } catch (Exception e) {
                    CreativeSandboxMod.LOGGER.error("[Sandboxes] Pre-save failed, proceeding anyway", e);
                }
            }

            // ── 2. Halt ───────────────────────────────────────────────────
            // By calling halt() BEFORE mc.disconnect(), the server shuts down
            // through its normal code path. FastQuit only intercepts disconnect(),
            // so it never gets a chance to keep the server alive.
            if (oldServer != null) {
                CreativeSandboxMod.LOGGER.info("[Sandboxes] Halting server...");
                oldServer.halt(false);
            }

            // ── 3. Join ───────────────────────────────────────────────────
            // Wait for the server thread to actually die. We must NOT use
            // isStopped() here — in runServer() the order is:
            //
            //   stopped = true;    ← isStopped() returns true here
            //   stopServer();      ← session.lock released here (can take 60s+)
            //   onServerExit();    ← thread dies after this
            //
            // Thread.join() is the proper way to wait — same check vanilla's
            // disconnect() uses (isShutdown = !serverThread.isAlive()).
            if (oldServer != null) {
                Thread serverThread = ((MinecraftServerAccessor) oldServer).getServerThread();
                CreativeSandboxMod.LOGGER.info("[Sandboxes] Waiting for server thread to die...");
                long waitStart = System.currentTimeMillis();

                try {
                    serverThread.join(SERVER_SHUTDOWN_TIMEOUT_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    CreativeSandboxMod.LOGGER.warn("[Sandboxes] Switch thread interrupted");
                    return;
                }

                if (serverThread.isAlive()) {
                    CreativeSandboxMod.LOGGER.error(
                            "[Sandboxes] Server thread still alive after {}ms — aborting world switch",
                            SERVER_SHUTDOWN_TIMEOUT_MS
                    );
                    mc.execute(() -> mc.setScreen(new TitleScreen()));
                    return;
                }

                long totalWait = System.currentTimeMillis() - waitStart;
                CreativeSandboxMod.LOGGER.info("[Sandboxes] Server thread died after {}ms", totalWait);

                // Safety net: if stopServer() threw an exception (e.g. "Entity is
                // already tracked!"), storageSource.close() never ran and the
                // session.lock is permanently held. Close it explicitly so the
                // target world can acquire its own lock.
                try {
                    ((MinecraftServerAccessor) oldServer).getStorageSource().close();
                } catch (Exception ignored) {
                    // Already closed by stopServer() — expected in the normal case
                }
            }

            // ── 4. Disconnect + open ──────────────────────────────────────
            // Server is dead. Disconnect does client-side cleanup (clears level,
            // player, etc), then we open the target world — all in one render
            // thread task, no latch needed.
            mc.execute(() -> {
                CreativeSandboxMod.LOGGER.info("[Sandboxes] Disconnecting and opening: {}", folderName);
                mc.disconnect(
                        new GenericMessageScreen(Component.literal("Switching worlds...")),
                        false
                );
                clearFastQuitSavingWorlds();

                try {
                    mc.createWorldOpenFlows().openWorld(folderName, () ->
                            CreativeSandboxMod.LOGGER.error("[Sandboxes] Failed to open world: {}", folderName));
                } catch (Exception e) {
                    CreativeSandboxMod.LOGGER.error("[Sandboxes] Exception opening world: {}", folderName, e);
                    mc.setScreen(new TitleScreen());
                }
            });
        }, "sandboxes-switch").start();
    }

    /**
     * Clear FastQuit's savingWorlds map via reflection so it releases the server
     * thread for shutdown. Fails gracefully if FastQuit is not installed.
     */
    @SuppressWarnings("unchecked")
    private static void clearFastQuitSavingWorlds() {
        try {
            Class<?> fastQuitClass = Class.forName("me.contaria.fastquit.FastQuit");
            Field savingWorldsField = fastQuitClass.getDeclaredField("savingWorlds");
            savingWorldsField.setAccessible(true);
            Map<?, ?> savingWorlds = (Map<?, ?>) savingWorldsField.get(null);
            int count = savingWorlds.size();
            savingWorlds.clear();
            CreativeSandboxMod.LOGGER.info("[Sandboxes] Cleared FastQuit savingWorlds ({} entries)", count);
        } catch (ClassNotFoundException e) {
            // FastQuit not installed — nothing to clear
            CreativeSandboxMod.LOGGER.debug("[Sandboxes] FastQuit not found, skipping savingWorlds clear");
        } catch (Exception e) {
            CreativeSandboxMod.LOGGER.warn("[Sandboxes] Could not clear FastQuit savingWorlds", e);
        }
    }

    // ── Metadata queries ──────────────────────────────────────────────────────

    /**
     * Returns sandboxes for the given parent world, sorted most-recently-synced first.
     */
    public List<SandboxMetadata> getSandboxesForWorld(String worldFolderName) {
        return sandboxes.stream()
                .filter(s -> worldFolderName.equals(s.parentWorldId))
                .sorted(Comparator.comparingLong((SandboxMetadata s) -> s.lastSynced).reversed())
                .collect(Collectors.toList());
    }

    /**
     * Look up the parent survival world folder for a sandbox.
     * Returns null if the folder isn't a sandbox or isn't in our metadata.
     */
    public String getParentWorldId(String sandboxFolder) {
        if (!isSandboxFolder(sandboxFolder)) return null;
        return sandboxes.stream()
                .filter(s -> sandboxFolder.equals(s.folderName()))
                .map(s -> s.parentWorldId)
                .findFirst()
                .orElse(null);
    }

    // ── Core operations ───────────────────────────────────────────────────────

    /** Create a new sandbox by copying the current survival world. */
    public SandboxMetadata createSandbox(String parentWorldFolder) throws IOException {
        // Pre-save: flush all chunk/entity/POI data to disk before copying.
        // Without this, entities in loaded-but-not-yet-autosaved chunks (e.g. villagers
        // that recently moved) will appear at their old on-disk position in the sandbox.
        // This is called from a background thread, so blocking on server.submit() is safe.
        presaveIfServerRunning();

        String id = UUID.randomUUID().toString().substring(0, 8);
        String name = "Sandbox — " + LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("MMM d, h:mm a"));
        SandboxMetadata meta = new SandboxMetadata(id, name, parentWorldFolder);

        Path source = SAVES_DIR.resolve(parentWorldFolder);
        Path dest = SAVES_DIR.resolve(meta.folderName());
        copyWorld(source, dest);
        patchToCreative(source, dest);

        sandboxes.add(meta);

        CreativeSandboxMod.LOGGER.info("[Sandboxes] Created sandbox '{}' from '{}'", name, parentWorldFolder);
        return meta;
    }

    /** Re-sync an existing sandbox from the survival world. */
    public void syncSandbox(SandboxMetadata meta, String parentWorldFolder) throws IOException {
        // Same pre-save as createSandbox — ensures the latest entity state is on disk
        // before we overwrite the sandbox with a fresh copy.
        presaveIfServerRunning();

        Path source = SAVES_DIR.resolve(parentWorldFolder);
        Path dest = SAVES_DIR.resolve(meta.folderName());

        // Delete old sandbox contents
        deleteDirectory(dest);
        copyWorld(source, dest);
        patchToCreative(source, dest);

        // Update lastSynced in our in-memory list
        sandboxes.stream().filter(s -> s.id.equals(meta.id)).findFirst()
                .ifPresent(s -> s.lastSynced = System.currentTimeMillis());

        CreativeSandboxMod.LOGGER.info("[Sandboxes] Synced sandbox '{}'", meta.name);
    }

    /** Delete a sandbox. */
    public void deleteSandbox(SandboxMetadata meta) throws IOException {
        Path dest = SAVES_DIR.resolve(meta.folderName());
        deleteDirectory(dest);

        sandboxes.removeIf(s -> s.id.equals(meta.id));

        CreativeSandboxMod.LOGGER.info("[Sandboxes] Deleted sandbox '{}'", meta.name);
    }

    /** Rename a sandbox. */
    public void renameSandbox(SandboxMetadata meta, String newName) {
        sandboxes.stream().filter(s -> s.id.equals(meta.id)).findFirst()
                .ifPresent(s -> s.name = newName);
        meta.name = newName;
    }

    // ── NBT persistence ───────────────────────────────────────────────────────

    /**
     * Save sandbox metadata to the player's NBT data.
     * Format: [{id, name, parentWorldId, lastSynced}]
     */
    public void saveToNbt(ValueOutput output) {
        if (!sandboxes.isEmpty()) {
            ValueOutput.ValueOutputList list = output.childrenList("sandboxes");
            for (SandboxMetadata meta : sandboxes) {
                ValueOutput child = list.addChild();
                child.putString("id", meta.id);
                child.putString("name", meta.name);
                child.putString("parentWorldId", meta.parentWorldId);
                child.putLong("lastSynced", meta.lastSynced);
            }
        }
    }

    /**
     * Load sandbox metadata from the player's NBT data.
     * Clears existing data first, then populates from NBT.
     * If NBT is empty, attempts one-time migration from the old sandboxes.json file.
     */
    public void loadFromNbt(ValueInput input) {
        sandboxes.clear();

        for (ValueInput child : input.childrenListOrEmpty("sandboxes")) {
            Optional<String> id = child.getString("id");
            Optional<String> name = child.getString("name");
            Optional<String> parentWorldId = child.getString("parentWorldId");
            if (id.isEmpty() || name.isEmpty() || parentWorldId.isEmpty()) continue;

            SandboxMetadata meta = new SandboxMetadata(id.get(), name.get(), parentWorldId.get());
            meta.lastSynced = child.getLongOr("lastSynced", 0L);
            sandboxes.add(meta);
        }

        CreativeSandboxMod.LOGGER.info("[Sandboxes] Loaded from player NBT: {} sandboxes", sandboxes.size());
    }

    /**
     * Clears all sandbox metadata. Called when joining a world with no saved
     * NBT data, to ensure stale data from a previous world doesn't leak through.
     */
    public void clearData() {
        sandboxes.clear();
    }

    // ── Orphan cleanup ───────────────────────────────────────────────────────

    /**
     * Removes orphaned sandboxes on world load. Two kinds of orphans:
     *
     * 1. Metadata orphans — sandbox metadata whose parent world folder no longer
     *    exists on disk. These are removed from the in-memory list.
     *
     * 2. Folder orphans — __sandbox__* folders in the saves directory that have
     *    no corresponding metadata entry. These are deleted from disk.
     *
     * Called after loadFromNbt() during world join.
     */
    public void cleanupOrphans() {
        // Phase 1: remove metadata entries whose parent world is gone
        // Collect orphans first, then batch-remove — CopyOnWriteArrayList doesn't
        // support iterator.remove(), and this avoids per-element copy overhead.
        List<SandboxMetadata> metaOrphans = sandboxes.stream()
                .filter(meta -> !Files.isDirectory(SAVES_DIR.resolve(meta.parentWorldId)))
                .toList();

        for (SandboxMetadata meta : metaOrphans) {
            CreativeSandboxMod.LOGGER.info("[Sandboxes] Orphan cleanup: removing '{}' — parent '{}' no longer exists",
                    meta.name, meta.parentWorldId);
            try {
                deleteDirectory(SAVES_DIR.resolve(meta.folderName()));
            } catch (IOException e) {
                CreativeSandboxMod.LOGGER.warn("[Sandboxes] Could not delete orphaned sandbox folder: {}",
                        meta.folderName(), e);
            }
        }
        int metaRemoved = metaOrphans.size();
        sandboxes.removeAll(metaOrphans);

        // Phase 2: delete stray __sandbox__* folders with no metadata
        int folderRemoved = 0;
        Set<String> knownFolders = sandboxes.stream()
                .map(SandboxMetadata::folderName)
                .collect(Collectors.toSet());

        try (var dirs = Files.list(SAVES_DIR)) {
            for (Path dir : dirs.filter(Files::isDirectory).toList()) {
                String name = dir.getFileName().toString();
                if (isSandboxFolder(name) && !knownFolders.contains(name)) {
                    CreativeSandboxMod.LOGGER.info("[Sandboxes] Orphan cleanup: deleting stray folder '{}'", name);
                    try {
                        deleteDirectory(dir);
                        folderRemoved++;
                    } catch (IOException e) {
                        CreativeSandboxMod.LOGGER.warn("[Sandboxes] Could not delete stray folder: {}", name, e);
                    }
                }
            }
        } catch (IOException e) {
            CreativeSandboxMod.LOGGER.warn("[Sandboxes] Could not scan saves directory for orphans", e);
        }

        if (metaRemoved > 0 || folderRemoved > 0) {
            CreativeSandboxMod.LOGGER.info("[Sandboxes] Orphan cleanup complete: {} metadata removed, {} folders deleted",
                    metaRemoved, folderRemoved);
        }
    }

    // ── Pre-save helper ───────────────────────────────────────────────────────

    /**
     * If an integrated server is currently running, submit a blocking saveEverything()
     * call on the server thread and wait for it to complete.
     *
     * Must be called from a background thread (not the render thread or server thread),
     * since it blocks until the save is done. Both createSandbox() and syncSandbox() are
     * invoked from background threads in SandboxScreen, so this is safe.
     *
     * Without this, entities in loaded-but-not-yet-autosaved chunks won't be on disk
     * at copy time, causing them to appear at stale positions in the sandbox.
     */
    private static void presaveIfServerRunning() {
        IntegratedServer server = Minecraft.getInstance().getSingleplayerServer();
        if (server == null) return; // not in a world — nothing to save

        CreativeSandboxMod.LOGGER.info("[Sandboxes] Pre-saving world before sandbox copy...");
        try {
            // server.submit() queues a task on the server main thread and returns a
            // CompletableFuture. Calling .get() blocks this background thread until
            // the save is complete, ensuring all chunk/entity/POI data is on disk.
            server.submit(() -> server.saveEverything(false, true, true)).get();
            CreativeSandboxMod.LOGGER.info("[Sandboxes] Pre-save complete.");
        } catch (Exception e) {
            CreativeSandboxMod.LOGGER.warn("[Sandboxes] Pre-save failed, proceeding anyway — " +
                    "sandbox may not reflect the latest entity state", e);
        }
    }

    // ── File helpers ──────────────────────────────────────────────────────────

    /**
     * Copy a world folder, skipping session.lock.
     *
     * On macOS (APFS), uses {@code cp -c -a} for copy-on-write cloning —
     * nearly instant regardless of world size because only metadata is copied
     * and data blocks are shared. Falls back to Java file-by-file copy on
     * other platforms.
     */
    private static void copyWorld(Path source, Path dest) throws IOException {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("mac")) {
            // APFS clone: cp -c (clone) -a (archive/recursive + preserve attrs)
            // Copies metadata only — data blocks are shared copy-on-write.
            try {
                int exit = new ProcessBuilder("cp", "-c", "-a",
                        source.toString(), dest.toString())
                        .inheritIO()
                        .start()
                        .waitFor();
                if (exit != 0) {
                    throw new IOException("cp -c -a exited with code " + exit);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted during world copy", e);
            }
        } else {
            // Fallback: Java file-by-file copy for non-macOS platforms
            Files.createDirectories(dest);
            Files.walkFileTree(source, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    Files.createDirectories(dest.resolve(source.relativize(dir)));
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if (!file.getFileName().toString().equals("session.lock")) {
                        Files.copy(file, dest.resolve(source.relativize(file)),
                                StandardCopyOption.REPLACE_EXISTING);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        }

        // Remove session.lock from the copy (cp -c copies everything)
        Files.deleteIfExists(dest.resolve("session.lock"));
    }

    /**
     * Patch a sandbox world to Creative mode.
     *
     * Changes made to the copied sandbox:
     * 1. level.dat GameType -> set to Creative (1)
     * 2. level.dat allowCommands -> enable command blocks (1)
     * 3. level.dat hardcore -> preserve from parent world
     * 4. playerdata/*.dat playerGameType -> set each player to Creative
     * 5. playerdata/*.dat trevormod_sandboxes -> REMOVED so sandbox doesn't
     *    inherit the parent's sandbox list
     *
     * @param parentWorldDir The original survival world (to read hardcore flag from)
     * @param sandboxWorldDir The new sandbox copy (to patch)
     */
    private static void patchToCreative(Path parentWorldDir, Path sandboxWorldDir) throws IOException {
        // Read parent's hardcore flag so we can preserve difficulty level
        boolean parentHardcore = false;
        Path parentLevelDat = parentWorldDir.resolve("level.dat");
        if (Files.exists(parentLevelDat)) {
            try {
                CompoundTag parentRoot = NbtIo.readCompressed(parentLevelDat, NbtAccounter.unlimitedHeap());
                CompoundTag parentData = parentRoot.getCompoundOrEmpty("Data");
                parentHardcore = parentData.getBoolean("hardcore").orElse(false);
            } catch (IOException e) {
                CreativeSandboxMod.LOGGER.warn("[Sandboxes] Could not read parent world hardcore flag, defaulting to false", e);
            }
        }

        // ── Sandbox level.dat ─────────────────────────────────────────────────
        Path levelDat = sandboxWorldDir.resolve("level.dat");
        if (Files.exists(levelDat)) {
            CompoundTag root = NbtIo.readCompressed(levelDat, NbtAccounter.unlimitedHeap());
            CompoundTag data = root.getCompoundOrEmpty("Data");
            data.putInt("GameType", 1);           // Creative mode
            data.putByte("allowCommands", (byte) 1); // Enable command blocks

            // If parent was hardcore, the sandbox is hard mode (not hardcore, but hard difficulty)
            // This prevents the sandbox from being unrecoverable on death
            if (parentHardcore) {
                data.putBoolean("hardcore", false);
                data.putInt("Difficulty", 3); // Hard difficulty
                CreativeSandboxMod.LOGGER.info("[Sandboxes] Parent was hardcore — setting sandbox to hard mode (not hardcore)");
            }

            // Singleplayer stores player data inline at Data.Player, not in
            // playerdata/*.dat. Patch the embedded player record too so the
            // player actually spawns in creative when opening the sandbox.
            CompoundTag player = data.getCompoundOrEmpty("Player");
            if (!player.isEmpty()) {
                player.putInt("playerGameType", 1);
                // Strip sandbox metadata so the sandbox doesn't inherit the parent's list
                player.remove("trevormod_sandboxes");
                data.put("Player", player);
            }

            root.put("Data", data);
            NbtIo.writeCompressed(root, levelDat);
        }

        // ── Sandbox playerdata ──────────────────────────────────────────────────
        // Iterate every .dat file (single-player has one, but handle all to be safe).
        // - Set playerGameType to Creative (1)
        // - Remove trevormod_sandboxes so the sandbox starts with an empty sandbox list
        Path playerdataDir = sandboxWorldDir.resolve("playerdata");
        if (Files.exists(playerdataDir)) {
            try (var stream = Files.list(playerdataDir)) {
                stream.filter(p -> p.getFileName().toString().endsWith(".dat"))
                        .forEach(p -> {
                            try {
                                CompoundTag tag = NbtIo.readCompressed(p, NbtAccounter.unlimitedHeap());
                                tag.putInt("playerGameType", 1);
                                // Strip sandbox metadata so the sandbox doesn't inherit
                                // the parent world's sandbox list
                                tag.remove("trevormod_sandboxes");
                                NbtIo.writeCompressed(tag, p);
                            } catch (IOException e) {
                                CreativeSandboxMod.LOGGER.warn("[Sandboxes] Could not patch playerdata: {}", p, e);
                            }
                        });
            }
        }
    }

    /** Recursively delete a directory. */
    private static void deleteDirectory(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path d, IOException e) throws IOException {
                Files.delete(d);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /** Returns true if a world folder name is a sandbox (should be hidden). */
    public static boolean isSandboxFolder(String folderName) {
        return folderName.startsWith("__sandbox__");
    }
}
