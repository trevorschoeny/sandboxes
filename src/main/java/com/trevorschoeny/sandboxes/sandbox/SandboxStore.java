package com.trevorschoeny.sandboxes.sandbox;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import com.trevorschoeny.sandboxes.SandboxesClient;

import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Every sandbox of a world lives in {@code saves/<world>/sandboxes/<uuid>/}.
 *
 * <p>Nesting them inside the parent world does two jobs at once: vanilla's world
 * list only scans the direct children of {@code saves/}, so sandboxes never show
 * up there, and deleting or moving a world takes its sandboxes with it, so there
 * are no orphans to track.
 *
 * <p>Each sandbox's own display name and last-joined time live in a small
 * {@code sandbox.json} beside its level data, rather than inside its level.dat.
 * Drawing the list then costs one small text read per sandbox instead of
 * decompressing every sandbox's level.dat.
 */
public final class SandboxStore {

    private SandboxStore() {}

    /** Folder under a world that holds its sandboxes. Also the marker in a level id. */
    public static final String SANDBOX_DIR = "sandboxes";

    /** Per-sandbox metadata file, inside the sandbox's own world folder. */
    private static final String META_FILE = "sandbox.json";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /**
     * Generated names are "Sandbox 1", "Sandbox 2", and so on. A name still in
     * this shape is untouched, so a duplicate takes the next number instead of
     * "(copy)"; anything else is the player's own name.
     */
    private static final Pattern GENERATED_NAME = Pattern.compile("Sandbox \\d+");
    /** v2 generated names were the date and time. */
    private static final Pattern DATED_GENERATED_NAME =
            Pattern.compile("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}");
    /**
     * The world's own sandbox state, in its sandboxes folder: for now the next
     * sandbox number, kept on disk so a number is never handed out twice, even
     * after the newest is deleted.
     */
    private static final String WORLD_FILE = "world.json";
    private static final int WORLD_FILE_VERSION = 1;
    /** v1 generated names looked like "Sandbox — Apr 5, 7:31 PM"; also not custom. */
    private static final Pattern LEGACY_GENERATED_NAME =
            Pattern.compile("Sandbox [—-] \\w{3} \\d{1,2}, \\d{1,2}:\\d{2} [AP]M");

    // Locations

    /** {@code saves/}. */
    public static Path savesDir() {
        return Minecraft.getInstance().getLevelSource().getBaseDir();
    }

    /** {@code saves/<world>/sandboxes/}, whether or not it exists yet. */
    public static Path sandboxesDir(String worldId) {
        return savesDir().resolve(worldId).resolve(SANDBOX_DIR);
    }

    /** True when this level id names a sandbox rather than a top-level world. */
    public static boolean isSandbox(String levelId) {
        return levelId.contains("/" + SANDBOX_DIR + "/");
    }

    /** The parent world's level id for a sandbox level id. */
    public static String parentOf(String sandboxLevelId) {
        return sandboxLevelId.substring(0, sandboxLevelId.indexOf("/" + SANDBOX_DIR + "/"));
    }

    // Reading

    /**
     * Every sandbox of a world, most recently joined first: what the quick
     * button's "last used" target reads. Never throws: a listing that fails
     * reads as empty, because this runs while a screen is being drawn.
     */
    public static List<Sandbox> list(String worldId) {
        Path dir = sandboxesDir(worldId);
        if (!Files.isDirectory(dir)) return List.of();

        List<Sandbox> out = new ArrayList<>();
        try (Stream<Path> children = Files.list(dir)) {
            for (Path child : children.toList()) {
                // A sandbox is a directory holding a real level. Anything else
                // in here (a stray file, a half-deleted folder) is ignored
                // rather than shown as a broken row.
                if (!Files.isDirectory(child) || !Files.exists(child.resolve("level.dat"))) continue;
                out.add(read(child, worldId));
            }
        } catch (IOException e) {
            SandboxesClient.LOGGER.error("[sandboxes] couldn't list {}", dir, e);
            return List.of();
        }
        out.sort(Comparator.comparingLong(Sandbox::lastJoined).reversed());
        return out;
    }

    /** Reads one sandbox folder, falling back to its folder name if metadata is unreadable. */
    private static Sandbox read(Path dir, String worldId) {
        String id = dir.getFileName().toString();
        String name = id;
        long lastJoined = 0L;
        long createdAt = 0L;
        String overlayKey = "";
        Path meta = dir.resolve(META_FILE);
        if (Files.exists(meta)) {
            try {
                JsonObject root = JsonParser.parseString(Files.readString(meta)).getAsJsonObject();
                if (root.has("name")) name = root.get("name").getAsString();
                if (root.has("lastJoined")) lastJoined = root.get("lastJoined").getAsLong();
                // Missing on a sandbox made before this field existed; 0 sorts
                // it to the end, which is the right place for it.
                if (root.has("createdAt")) createdAt = root.get("createdAt").getAsLong();
                if (root.has("overlayKey")) overlayKey = root.get("overlayKey").getAsString();
            } catch (Exception e) {
                // A corrupt metadata file must not hide a sandbox the player can
                // still join, so the folder name stands in for the display name.
                SandboxesClient.LOGGER.error("[sandboxes] couldn't read {}", meta, e);
            }
        }
        return new Sandbox(id, name, lastJoined, createdAt, dir, worldId, overlayKey);
    }

    private static void write(Path dir, String name, long lastJoined, long createdAt, String overlayKey)
            throws IOException {
        JsonObject root = new JsonObject();
        root.addProperty("name", name);
        root.addProperty("lastJoined", lastJoined);
        root.addProperty("createdAt", createdAt);
        if (!overlayKey.isEmpty()) root.addProperty("overlayKey", overlayKey);
        Files.writeString(dir.resolve(META_FILE), GSON.toJson(root));
    }

    // Operations

    /**
     * Copies the world into a new sandbox folder and returns it. The caller
     * saves the world first; this only reads what is already on disk.
     */
    public static Sandbox create(String worldId) throws IOException {
        Path source = savesDir().resolve(worldId);
        String id = UUID.randomUUID().toString();
        Path dest = sandboxesDir(worldId).resolve(id);
        Files.createDirectories(dest.getParent());

        WorldFiles.copyWorld(source, dest);
        String name = generatedName(worldId);
        WorldFiles.makeSandboxLevel(source, dest, name);
        long createdAt = System.currentTimeMillis();
        write(dest, name, 0L, createdAt, "");

        SandboxesClient.LOGGER.info("[sandboxes] created {} of {}", id, worldId);
        return new Sandbox(id, name, 0L, createdAt, dest, worldId, "");
    }

    /**
     * Throws the sandbox's contents away and takes a fresh copy of the world.
     * It keeps its name.
     */
    public static void sync(Sandbox sandbox) throws IOException {
        Path source = savesDir().resolve(sandbox.parentId());
        String name = sandbox.name();

        WorldFiles.deleteDirectory(sandbox.dir());
        WorldFiles.copyWorld(source, sandbox.dir());
        WorldFiles.makeSandboxLevel(source, sandbox.dir(), name);
        write(sandbox.dir(), name, sandbox.lastJoined(), sandbox.createdAt(), sandbox.overlayKey());

        SandboxesClient.LOGGER.info("[sandboxes] synced {}", sandbox.id());
    }

    /**
     * Copies a sandbox as it is now into a new row: its blocks and its own player
     * come along. Nothing is re-forked from the main world.
     *
     * <p>A name the player chose becomes "name (copy)"; a still-generated one
     * gets the next number instead.
     */
    public static Sandbox duplicate(Sandbox source) throws IOException {
        String id = UUID.randomUUID().toString();
        Path dest = sandboxesDir(source.parentId()).resolve(id);
        WorldFiles.copyWorld(source.dir(), dest);

        String name = isGeneratedName(source.name()) ? generatedName(source.parentId()) : source.name() + " (copy)";
        // The key stays with the original: one key showing two sandboxes would be ambiguous.
        long createdAt = System.currentTimeMillis();
        write(dest, name, 0L, createdAt, "");

        SandboxesClient.LOGGER.info("[sandboxes] duplicated {} as {}", source.id(), id);
        return new Sandbox(id, name, 0L, createdAt, dest, source.parentId(), "");
    }

    public static void delete(Sandbox sandbox) throws IOException {
        WorldFiles.deleteDirectory(sandbox.dir());
        SandboxesClient.LOGGER.info("[sandboxes] deleted {}", sandbox.id());
    }

    public static void rename(Sandbox sandbox, String newName) throws IOException {
        write(sandbox.dir(), newName, sandbox.lastJoined(), sandbox.createdAt(), sandbox.overlayKey());
    }

    /** Stamps the join time, so the quick button follows the player. */
    public static void markJoined(Sandbox sandbox) {
        try {
            write(sandbox.dir(), sandbox.name(), System.currentTimeMillis(), sandbox.createdAt(), sandbox.overlayKey());
        } catch (IOException e) {
            // Losing this only costs the quick button's target, never data: don't fail the join.
            SandboxesClient.LOGGER.error("[sandboxes] couldn't stamp join time for {}", sandbox.id(), e);
        }
    }

    /** Sets this sandbox's overlay key; "" clears it. */
    public static void setOverlayKey(Sandbox sandbox, String overlayKey) throws IOException {
        write(sandbox.dir(), sandbox.name(), sandbox.lastJoined(), sandbox.createdAt(), overlayKey);
    }

    /** The sandbox with this level id, read fresh from disk. */
    public static Sandbox readSandbox(String sandboxLevelId) {
        return read(savesDir().resolve(sandboxLevelId), parentOf(sandboxLevelId));
    }

    // Names

    /**
     * "Sandbox N" with the world's next number, which is then used up. A world
     * with no counter yet (its sandboxes made before numbering) starts after the
     * highest "Sandbox N" it already has.
     */
    private static synchronized String generatedName(String worldId) throws IOException {
        Path file = sandboxesDir(worldId).resolve(WORLD_FILE);
        int next = 0;
        if (Files.exists(file)) {
            try {
                JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
                if (root.has("nextNumber")) next = root.get("nextNumber").getAsInt();
            } catch (Exception e) {
                // The names on disk still say where counting got to.
                SandboxesClient.LOGGER.warn("[sandboxes] unreadable {}, counting from the names", file, e);
            }
        }
        for (Sandbox sandbox : list(worldId)) next = Math.max(next, numberOf(sandbox.name()) + 1);
        next = Math.max(next, 1);
        JsonObject root = new JsonObject();
        root.addProperty("version", WORLD_FILE_VERSION);
        root.addProperty("nextNumber", next + 1);
        Files.createDirectories(file.getParent());
        Files.writeString(file, GSON.toJson(root));
        return "Sandbox " + next;
    }

    /** N for a "Sandbox N" name, else 0. */
    private static int numberOf(String name) {
        if (!GENERATED_NAME.matcher(name).matches()) return 0;
        try {
            return Integer.parseInt(name.substring("Sandbox ".length()));
        } catch (NumberFormatException e) {   // more digits than an int holds
            return 0;
        }
    }

    /** True when the name is one this mod would have generated (any version's shape). */
    public static boolean isGeneratedName(String name) {
        return GENERATED_NAME.matcher(name).matches()
                || DATED_GENERATED_NAME.matcher(name).matches()
                || LEGACY_GENERATED_NAME.matcher(name).matches();
    }
}
