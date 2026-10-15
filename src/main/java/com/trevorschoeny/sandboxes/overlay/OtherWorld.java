package com.trevorschoeny.sandboxes.overlay;

import com.trevorschoeny.sandboxes.SandboxesClient;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import net.minecraft.world.level.dimension.DimensionType;

import org.jspecify.annotations.Nullable;

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Reads blocks out of the world that isn't running.
 *
 * <p>Only one world runs at a time, so the other one is files nothing is
 * writing. Each chunk is read and decoded once per overlay session and then
 * kept, in the same compact palette form Minecraft stores it in.
 *
 * <p>Used from the overlay's worker thread only.
 */
final class OtherWorld implements AutoCloseable {

    /** A section that exists only as air on disk, or isn't stored at all. */
    private static final Section AIR = new Section(new BlockState[] {Blocks.AIR.defaultBlockState()}, null, 0);

    private final Path root;
    private final ResourceKey<Level> dimension;
    private final Map<Long, RegionFile> regions = new HashMap<>();
    /** Decoded chunks; a null value means "not in the other world, nothing to compare". */
    private final Long2ObjectOpenHashMap<Map<Integer, Section>> chunks = new Long2ObjectOpenHashMap<>();

    OtherWorld(Path root, ResourceKey<Level> dimension) {
        this.root = root;
        this.dimension = dimension;
    }

    /**
     * The other world's section at these section coordinates, or null when the
     * other world never generated that chunk. A chunk explored only after the
     * fork has nothing to compare against, so it counts as "the same".
     */
    @Nullable Section section(int sectionX, int sectionY, int sectionZ) {
        long chunkKey = ChunkPos.pack(sectionX, sectionZ);
        Map<Integer, Section> chunk;
        if (chunks.containsKey(chunkKey)) {
            chunk = chunks.get(chunkKey);
        } else {
            chunk = readChunk(new ChunkPos(sectionX, sectionZ));
            chunks.put(chunkKey, chunk);
        }
        if (chunk == null) return null;
        return chunk.getOrDefault(sectionY, AIR);
    }

    /** Decodes one chunk's block sections, keyed by section Y. Null when absent or unreadable. */
    private @Nullable Map<Integer, Section> readChunk(ChunkPos pos) {
        try {
            RegionFile region = region(pos);
            if (region == null) return null;
            try (DataInputStream in = region.getChunkDataInputStream(pos)) {
                if (in == null) return null;
                CompoundTag tag = NbtIo.read(in);
                Map<Integer, Section> out = new HashMap<>();
                for (Tag entry : tag.getListOrEmpty("sections")) {
                    if (!(entry instanceof CompoundTag sectionTag)) continue;
                    int y = sectionTag.getByteOr("Y", (byte) 0);
                    Section section = decode(sectionTag.getCompoundOrEmpty("block_states"));
                    if (section == null) return null;   // a block we can't read: skip the chunk
                    out.put(y, section);
                }
                return out;
            }
        } catch (IOException e) {
            SandboxesClient.LOGGER.warn("[overlay] couldn't read chunk {} of {}", pos, root, e);
            return null;
        }
    }

    private @Nullable RegionFile region(ChunkPos pos) throws IOException {
        long key = ChunkPos.pack(pos.getRegionX(), pos.getRegionZ());
        if (regions.containsKey(key)) return regions.get(key);
        Path dir = DimensionType.getStorageFolder(dimension, root).resolve("region");
        Path file = dir.resolve("r." + pos.getRegionX() + "." + pos.getRegionZ() + ".mca");
        RegionFile region = null;
        if (Files.exists(file)) {
            // Read-only use: nothing writes this world while the other one runs.
            region = new RegionFile(new RegionStorageInfo("sandboxes-overlay", dimension, "chunk"),
                    file, dir, false);
        }
        regions.put(key, region);
        return region;
    }

    /**
     * Decodes a section's palette and packed indices.
     *
     * <p>Minecraft packs each block as an index into the section's palette,
     * using at least 4 bits, with values never split across two longs. A palette
     * of one entry has no data at all.
     */
    private static @Nullable Section decode(CompoundTag blockStates) {
        var paletteTags = blockStates.getListOrEmpty("palette");
        if (paletteTags.isEmpty()) return AIR;
        BlockState[] palette = new BlockState[paletteTags.size()];
        for (int i = 0; i < palette.length; i++) {
            BlockState state = BlockState.CODEC.parse(NbtOps.INSTANCE, paletteTags.get(i)).result().orElse(null);
            if (state == null) return null;
            palette[i] = state;
        }
        if (palette.length == 1) return new Section(palette, null, 0);
        long[] data = blockStates.getLongArray("data").orElse(null);
        if (data == null) return null;
        int bits = Math.max(4, 32 - Integer.numberOfLeadingZeros(palette.length - 1));
        return new Section(palette, data, bits);
    }

    @Override
    public void close() {
        for (RegionFile region : regions.values()) {
            if (region == null) continue;
            try {
                region.close();
            } catch (IOException e) {
                SandboxesClient.LOGGER.warn("[overlay] couldn't close a region file", e);
            }
        }
        regions.clear();
        chunks.clear();
    }

    /** One 16x16x16 section in its stored form. Index is y * 256 + z * 16 + x. */
    record Section(BlockState[] palette, long @Nullable [] data, int bits) {
        BlockState get(int index) {
            if (data == null) return palette[0];
            int perLong = 64 / bits;
            long word = data[index / perLong];
            int value = (int) ((word >>> ((index % perLong) * bits)) & ((1L << bits) - 1));
            return value < palette.length ? palette[value] : palette[0];
        }
    }
}
