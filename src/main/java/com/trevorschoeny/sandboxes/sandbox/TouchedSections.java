package com.trevorschoeny.sandboxes.sandbox;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import com.trevorschoeny.sandboxes.SandboxesClient;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Which 16x16x16 sections had a block change while the player was inside a
 * sandbox, per dimension.
 *
 * <p>A plain comparison of two worlds can't tell "built in the sandbox" from
 * "changed in the main world after the fork": both are just "different". This
 * record answers that, so the overlay can show sandbox work alone. It also
 * shrinks the overlay's comparison to the sections listed here.
 *
 * <p>The file's presence matters. A sandbox made before this record existed has
 * no file, and is treated as untracked rather than "nothing changed", until its
 * next sync starts a fresh record.
 *
 * <p>Methods are synchronized: the integrated server adds sections on its
 * thread while the overlay reads them on the client's.
 */
public final class TouchedSections {

    private static final String FILE = "touched-sections.json";
    private static final Gson GSON = new GsonBuilder().create();

    /** Dimension id ("minecraft:overworld") to packed section positions. */
    private final Map<String, LongOpenHashSet> byDimension = new HashMap<>();

    /** True if this section wasn't recorded before. */
    public synchronized boolean add(String dimension, long sectionPos) {
        return byDimension.computeIfAbsent(dimension, d -> new LongOpenHashSet()).add(sectionPos);
    }

    public synchronized boolean contains(String dimension, long sectionPos) {
        LongOpenHashSet set = byDimension.get(dimension);
        return set != null && set.contains(sectionPos);
    }

    /** A copy safe to hand to another thread. */
    public synchronized TouchedSections copy() {
        TouchedSections out = new TouchedSections();
        byDimension.forEach((dim, set) -> out.byDimension.put(dim, new LongOpenHashSet(set)));
        return out;
    }

    // Disk

    /** The sandbox's record, or empty when the sandbox predates records (untracked). */
    public static Optional<TouchedSections> load(Path sandboxDir) {
        Path file = sandboxDir.resolve(FILE);
        if (!Files.exists(file)) return Optional.empty();
        TouchedSections out = new TouchedSections();
        try {
            JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            JsonObject dims = root.getAsJsonObject("dimensions");
            if (dims != null) {
                for (Map.Entry<String, JsonElement> entry : dims.entrySet()) {
                    LongOpenHashSet set = new LongOpenHashSet();
                    for (JsonElement value : entry.getValue().getAsJsonArray()) set.add(value.getAsLong());
                    out.byDimension.put(entry.getKey(), set);
                }
            }
        } catch (Exception e) {
            // An unreadable record must not cost the player the overlay: an
            // empty one shows nothing as sandbox work, which the "all
            // differences" switch still gets around.
            SandboxesClient.LOGGER.error("[sandboxes] couldn't read {}", file, e);
        }
        return Optional.of(out);
    }

    public synchronized void save(Path sandboxDir) throws IOException {
        JsonObject dims = new JsonObject();
        byDimension.forEach((dim, set) -> {
            JsonArray values = new JsonArray();
            set.forEach(values::add);
            dims.add(dim, values);
        });
        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
        root.add("dimensions", dims);
        Files.writeString(sandboxDir.resolve(FILE), GSON.toJson(root));
    }

    /** Starts an empty record, so the sandbox counts as tracked from its first join. */
    public static void startFresh(Path sandboxDir) throws IOException {
        new TouchedSections().save(sandboxDir);
    }
}
