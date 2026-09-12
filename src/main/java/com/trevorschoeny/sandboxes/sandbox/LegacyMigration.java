package com.trevorschoeny.sandboxes.sandbox;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

import com.trevorschoeny.sandboxes.SandboxesClient;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Brings a Sandboxes 1.0.0 player's sandboxes forward.
 *
 * <p>v1 kept each sandbox as its own top-level world, {@code saves/__sandbox__<id>},
 * and kept the list of them in the parent world's player NBT under
 * {@code trevormod_sandboxes}. v2 keeps sandboxes inside the world they belong
 * to, so each listed folder moves to {@code saves/<world>/sandboxes/<id>/} and
 * its name and time are written beside it.
 *
 * <p>This runs at startup, before any world is open, because it reads and
 * rewrites level.dat files that a running server would otherwise own. It is a
 * move, never a copy and never a delete: a folder that can't be moved is left
 * exactly where it is and tried again next launch. Folders that no world claims
 * are left alone too, because they are the player's files and this mod can't
 * account for them.
 */
public final class LegacyMigration {

    private LegacyMigration() {}

    private static final String LEGACY_PREFIX = "__sandbox__";
    private static final String LEGACY_KEY = "trevormod_sandboxes";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** One v1 list entry. */
    private record Entry(String id, String name, String parentWorldId, long lastSynced) {}

    /**
     * Moves every v1 sandbox it can account for. Safe to call on every launch:
     * with no v1 folders present it costs one directory listing.
     */
    public static void run() {
        Path saves = SandboxStore.savesDir();
        if (!Files.isDirectory(saves)) return;

        List<Path> legacyFolders = new ArrayList<>();
        try (Stream<Path> children = Files.list(saves)) {
            for (Path child : children.toList()) {
                if (Files.isDirectory(child)
                        && child.getFileName().toString().startsWith(LEGACY_PREFIX)) {
                    legacyFolders.add(child);
                }
            }
        } catch (IOException e) {
            SandboxesClient.LOGGER.error("[migrate] couldn't list {}", saves, e);
            return;
        }
        if (legacyFolders.isEmpty()) return;

        SandboxesClient.LOGGER.info("[migrate] {} v1 sandbox folder(s) present", legacyFolders.size());

        // Only worlds that still carry the v1 key have anything to say about
        // which folder belongs to whom.
        try (Stream<Path> worlds = Files.list(saves)) {
            for (Path world : worlds.toList()) {
                String worldId = world.getFileName().toString();
                if (!Files.isDirectory(world) || worldId.startsWith(LEGACY_PREFIX)) continue;
                migrateWorld(saves, world, worldId);
            }
        } catch (IOException e) {
            SandboxesClient.LOGGER.error("[migrate] couldn't walk {}", saves, e);
        }
    }

    private static void migrateWorld(Path saves, Path world, String worldId) {
        Path levelFile = world.resolve("level.dat");
        if (!Files.exists(levelFile)) return;

        CompoundTag root;
        CompoundTag player;
        try {
            root = NbtIo.readCompressed(levelFile, NbtAccounter.uncompressedQuota());
            CompoundTag data = root.getCompound("Data").orElse(null);
            player = data == null ? null : data.getCompound("Player").orElse(null);
        } catch (IOException e) {
            SandboxesClient.LOGGER.error("[migrate] couldn't read {}", levelFile, e);
            return;
        }
        if (player == null || !player.contains(LEGACY_KEY)) return;

        List<Entry> entries = readEntries(player);
        if (entries.isEmpty()) {
            stripKey(root, player, levelFile);
            return;
        }

        boolean allMoved = true;
        for (Entry entry : entries) {
            if (!entry.parentWorldId().equals(worldId)) continue;  // another world's row
            Path from = saves.resolve(LEGACY_PREFIX + entry.id());
            if (!Files.isDirectory(from)) continue;                // already gone; nothing owed
            Path to = SandboxStore.sandboxesDir(worldId).resolve(entry.id());
            try {
                Files.createDirectories(to.getParent());
                Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
                writeMeta(to, entry);
                SandboxesClient.LOGGER.info("[migrate] {} -> {}", from.getFileName(), to);
            } catch (IOException e) {
                // Leave it where it is and try again next launch rather than
                // half-moving a world the player still has.
                SandboxesClient.LOGGER.error("[migrate] couldn't move {}", from, e);
                allMoved = false;
            }
        }

        // The key goes only once every folder this world claims has moved, so a
        // failure keeps the information needed to retry.
        if (allMoved) stripKey(root, player, levelFile);
    }

    private static List<Entry> readEntries(CompoundTag player) {
        List<Entry> out = new ArrayList<>();
        CompoundTag legacy = player.getCompound(LEGACY_KEY).orElse(null);
        if (legacy == null) return out;
        for (var tag : legacy.getListOrEmpty("sandboxes")) {
            if (!(tag instanceof CompoundTag row)) continue;
            String id = row.getStringOr("id", "");
            String parent = row.getStringOr("parentWorldId", "");
            if (id.isEmpty() || parent.isEmpty()) continue;
            out.add(new Entry(id, row.getStringOr("name", id), parent,
                    row.getLongOr("lastSynced", 0L)));
        }
        return out;
    }

    private static void writeMeta(Path dir, Entry entry) throws IOException {
        JsonObject meta = new JsonObject();
        meta.addProperty("name", entry.name());
        // v1 tracked when a sandbox was last synced, not joined. It is the only
        // time it has, and it orders the list the same way.
        meta.addProperty("lastJoined", entry.lastSynced());
        Files.writeString(dir.resolve("sandbox.json"), GSON.toJson(meta));
    }

    private static void stripKey(CompoundTag root, CompoundTag player, Path levelFile) {
        player.remove(LEGACY_KEY);
        try {
            NbtIo.writeCompressed(root, levelFile);
        } catch (IOException e) {
            // Harmless if it fails: the folders are already moved, and a second
            // run finds nothing left to move.
            SandboxesClient.LOGGER.warn("[migrate] couldn't rewrite {}", levelFile, e);
        }
    }
}
