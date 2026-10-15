package com.trevorschoeny.sandboxes.sandbox;

import com.trevorschoeny.sandboxes.SandboxesClient;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Every block position that changed while the player was inside a sandbox, per
 * dimension.
 *
 * <p>A plain comparison of two worlds can't tell "built in the sandbox" from
 * "changed in the main world after the fork": both are just "different". This
 * record answers that, so the overlay can show sandbox work alone.
 *
 * <p>It records exact blocks, not 16x16x16 sections. A section-sized record
 * let main-world changes near any sandbox work show up as sandbox work (found in
 * testing, 2026-10-15). The sections containing recorded blocks are kept too,
 * so the overlay still only scans those.
 *
 * <p>The file's presence matters. A sandbox made before this record existed has
 * no file, and is treated as untracked rather than "nothing changed", until its
 * next sync starts a fresh record.
 *
 * <p>Methods are synchronized: the integrated server adds blocks on its thread
 * while the overlay reads them on the client's.
 */
public final class TouchedBlocks {

    private static final String FILE = "touched-blocks.dat";
    private static final int FORMAT = 1;

    /** Dimension id ("minecraft:overworld") to packed block positions. */
    private final Map<String, LongOpenHashSet> blocks = new HashMap<>();
    /** The same, rounded to sections: what the overlay needs to scan. Derived, never saved. */
    private final Map<String, LongOpenHashSet> sections = new HashMap<>();

    /** True if this block wasn't recorded before. */
    public synchronized boolean add(String dimension, long blockPos) {
        boolean added = blocks.computeIfAbsent(dimension, d -> new LongOpenHashSet()).add(blockPos);
        if (added) {
            sections.computeIfAbsent(dimension, d -> new LongOpenHashSet())
                    .add(SectionPos.asLong(BlockPos.of(blockPos)));
        }
        return added;
    }

    public synchronized boolean containsBlock(String dimension, long blockPos) {
        LongOpenHashSet set = blocks.get(dimension);
        return set != null && set.contains(blockPos);
    }

    public synchronized boolean containsSection(String dimension, long sectionPos) {
        LongOpenHashSet set = sections.get(dimension);
        return set != null && set.contains(sectionPos);
    }

    // Disk: gzipped, per dimension its id, a count, then the packed positions.

    /** The sandbox's record, or empty when the sandbox predates records (untracked). */
    public static Optional<TouchedBlocks> load(Path sandboxDir) {
        Path file = sandboxDir.resolve(FILE);
        if (!Files.exists(file)) return Optional.empty();
        TouchedBlocks out = new TouchedBlocks();
        try (DataInputStream in = new DataInputStream(new GZIPInputStream(Files.newInputStream(file)))) {
            if (in.readInt() != FORMAT) throw new IOException("unknown format");
            int dimensions = in.readInt();
            for (int d = 0; d < dimensions; d++) {
                String dimension = in.readUTF();
                int count = in.readInt();
                for (int i = 0; i < count; i++) out.add(dimension, in.readLong());
            }
        } catch (IOException e) {
            // An unreadable record must not cost the player the overlay: an
            // empty one shows nothing as sandbox work, which the "all
            // differences" switch still gets around.
            SandboxesClient.LOGGER.error("[sandboxes] couldn't read {}", file, e);
        }
        return Optional.of(out);
    }

    public synchronized void save(Path sandboxDir) throws IOException {
        try (DataOutputStream out = new DataOutputStream(
                new GZIPOutputStream(Files.newOutputStream(sandboxDir.resolve(FILE))))) {
            out.writeInt(FORMAT);
            out.writeInt(blocks.size());
            for (Map.Entry<String, LongOpenHashSet> entry : blocks.entrySet()) {
                out.writeUTF(entry.getKey());
                out.writeInt(entry.getValue().size());
                for (long pos : entry.getValue()) out.writeLong(pos);
            }
        }
    }

    /** Starts an empty record, so the sandbox counts as tracked from its first join. */
    public static void startFresh(Path sandboxDir) throws IOException {
        new TouchedBlocks().save(sandboxDir);
    }
}
