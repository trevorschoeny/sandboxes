package com.trevorschoeny.sandboxes.overlay;

import com.trevorschoeny.sandboxes.SandboxesClient;
import com.trevorschoeny.sandboxes.sandbox.CurrentWorld;
import com.trevorschoeny.sandboxes.sandbox.Sandbox;
import com.trevorschoeny.sandboxes.sandbox.SandboxStore;

import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import org.jspecify.annotations.Nullable;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The diff overlay: shows, inside the world you're in, where the other world
 * (a sandbox, or the main world from inside a sandbox) differs.
 *
 * <p>Only one world runs at a time, so the other one is frozen on disk for the
 * whole session. That makes the work small:
 * <ol>
 *   <li>When the overlay turns on, every nearby section is compared once, on a
 *       worker thread, against the other world read straight from its files.</li>
 *   <li>After that, a block change re-checks only the 16x16x16 section it's in.
 *       Nothing else is ever compared again.</li>
 *   <li>Each frame draws results that are already worked out (see
 *       {@link OverlayRenderer}).</li>
 * </ol>
 * Off, it does nothing at all.
 *
 * <p>Colours describe the other world from where you stand (see
 * {@link OverlayRenderer} for how each is drawn): green, the other world has a
 * block where you have air; red, you have a block where it has air; yellow, a
 * different block.
 *
 * <p>Every difference shows, whoever made it. Telling sandbox work apart from
 * main-world changes since the fork was tried and dropped: once the player
 * starts building from the overlay, which side "meant" a change can't be known,
 * and hiding any difference hides the feedback a builder needs. A main world
 * that has moved on is what Sync is for.
 *
 * ponytail: compares blocks, not block states, so a rotated stair or a
 * different redstone power level doesn't show. Comparing full states lit up
 * every grown crop and flowing water level; a fourth "same block, different
 * state" colour is the upgrade if that's missed.
 */
public final class Overlay {

    private Overlay() {}

    /** What the other world has at a position, seen from the world you're in. RGB; the alpha is the opacity setting. */
    enum Kind {
        ADDED(0x60FF60),     // other world: block, here: air
        REMOVED(0xFF5050),   // other world: air, here: block
        CHANGED(0xFFE040);   // both: a block, but different ones

        final int rgb;

        Kind(int rgb) {
            this.rgb = rgb;
        }
    }

    /**
     * One section's differences, in local block indices (y * 256 + z * 16 + x),
     * ascending, with what the other world has at each.
     */
    record SectionDiff(int sectionX, int sectionY, int sectionZ,
                       short[] indices, byte[] kinds, byte[] exposedFaces, BlockState[] theirs) {}

    /** One difference: its kind, and the other world's block there. */
    record Difference(Kind kind, BlockState theirs) {}

    /** The difference at a position, or null where the worlds agree (or it isn't compared yet). */
    static @Nullable Difference at(BlockPos pos) {
        SectionDiff diff = DIFFS.get(SectionPos.asLong(pos));
        if (diff == null) return null;
        int local = ((pos.getY() & 15) << 8) | ((pos.getZ() & 15) << 4) | (pos.getX() & 15);
        int n = Arrays.binarySearch(diff.indices(), (short) local);
        return n < 0 ? null : new Difference(Kind.values()[diff.kinds()[n]], diff.theirs()[n]);
    }

    /**
     * What the overlay compares against.
     *
     * @param sandboxId the sandbox being shown, or null when showing the main world from inside a sandbox
     */
    private record Target(String label, Path otherRoot, @Nullable String sandboxId) {}

    /** Per-tick budget for handing sections to the worker, so turning on never hitches. */
    private static final long TICK_BUDGET_NANOS = 2_000_000L;

    /** Results the renderer draws. Written by the worker, read by the render thread. */
    static final Map<Long, SectionDiff> DIFFS = new ConcurrentHashMap<>();

    private static volatile @Nullable Target target = null;
    private static @Nullable ClientLevel level = null;
    private static final LongLinkedOpenHashSet PENDING = new LongLinkedOpenHashSet();

    /** Bumped on every reset, so the worker can drop results from a previous session. */
    private static volatile int generation = 0;
    private static @Nullable ExecutorService worker = null;
    /** Owned by the worker thread only. */
    private static @Nullable OtherWorld other = null;

    public static void install() {
        ClientTickEvents.END_CLIENT_TICK.register(Overlay::tick);
        ClientChunkEvents.CHUNK_LOAD.register((lvl, chunk) -> {
            if (target != null && lvl == level) enqueueChunk(chunk);
        });
        ClientChunkEvents.CHUNK_UNLOAD.register((lvl, chunk) -> {
            if (target != null && lvl == level) dropChunk(chunk.getPos());
        });
    }

    // Toggling

    /** The list's Overlay button: show this sandbox, or turn it off if it's already showing. */
    public static void toggleFor(Sandbox sandbox) {
        if (showing(sandbox)) {
            off();
            return;
        }
        start(new Target(sandbox.name(), sandbox.dir(), sandbox.id()));
    }

    /**
     * Inside a sandbox: compare it with the main world, or turn that off. This is
     * what a sandbox's own key does while the player is inside it.
     */
    public static void toggleAgainstParent() {
        if (target != null) {
            off();
            return;
        }
        String levelId = CurrentWorld.levelId();
        if (levelId == null || !SandboxStore.isSandbox(levelId)) return;
        String parent = SandboxStore.parentOf(levelId);
        start(new Target(parent, SandboxStore.savesDir().resolve(parent), null));
    }

    public static boolean showing(Sandbox sandbox) {
        Target current = target;
        return current != null && sandbox.id().equals(current.sandboxId());
    }

    /** A sandbox about to be synced or deleted can't stay on screen. */
    public static void forget(Sandbox sandbox) {
        if (showing(sandbox)) off();
    }

    public static boolean active() {
        return target != null;
    }

    public static void off() {
        target = null;
        reset(null);
    }

    /** The overlay's line under the HUD's sandbox text. */
    public static Component hudLabel() {
        Target current = target;
        if (current == null) return Component.empty();
        return Component.translatable("sandboxes.overlay.hud", current.label());
    }

    private static void start(Target next) {
        target = next;
        reset(Minecraft.getInstance().level);
        SandboxesClient.LOGGER.info("[overlay] showing {}", next.label());
    }

    // Session

    /** Throws away every result and, with a level, rescans it from scratch. */
    private static void reset(@Nullable ClientLevel newLevel) {
        generation++;
        DIFFS.clear();
        PENDING.clear();
        level = newLevel;

        Target current = target;
        if (current == null || newLevel == null) {
            if (worker != null) {
                worker.submit(Overlay::closeOther);
                worker.shutdown();
                worker = null;
            }
            return;
        }
        if (worker == null) {
            worker = Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "sandboxes-overlay");
                thread.setDaemon(true);
                return thread;
            });
        }
        var dimension = newLevel.dimension();
        worker.submit(() -> {
            closeOther();
            other = new OtherWorld(current.otherRoot(), dimension);
        });

        // Every loaded chunk within render distance, nearest first via the
        // pending set's insertion order being a square walk out from the player.
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        int radius = mc.options.getEffectiveRenderDistance();
        ChunkPos center = mc.player.chunkPosition();
        for (int ring = 0; ring <= radius; ring++) {
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;
                    LevelChunk chunk = newLevel.getChunkSource()
                            .getChunk(center.x() + dx, center.z() + dz, ChunkStatus.FULL, false);
                    if (chunk != null) enqueueChunk(chunk);
                }
            }
        }
    }

    private static void closeOther() {
        if (other != null) {
            other.close();
            other = null;
        }
    }

    // Feeding the worker

    private static void enqueueChunk(LevelChunk chunk) {
        ClientLevel lvl = level;
        if (lvl == null) return;
        ChunkPos pos = chunk.getPos();
        int minY = lvl.getMinSectionY();
        for (int i = 0; i < lvl.getSectionsCount(); i++) {
            long sectionPos = SectionPos.asLong(pos.x(), minY + i, pos.z());
            PENDING.add(sectionPos);
        }
    }

    private static void dropChunk(ChunkPos pos) {
        DIFFS.values().removeIf(d -> d.sectionX() == pos.x() && d.sectionZ() == pos.z());
    }

    /** A block changed in the live world: re-check its section, and nothing else. */
    public static void onBlockChanged(BlockPos pos) {
        if (target == null || level == null) return;
        long sectionPos = SectionPos.asLong(pos);
        PENDING.add(sectionPos);
    }

    /**
     * Hands pending sections to the worker, under a time budget.
     *
     * <p>The live section is copied here, on the client thread, so the worker
     * never reads a chunk the game is changing at the same moment. A copy is a
     * few hundred bytes to a couple of kilobytes.
     */
    private static void tick(Minecraft mc) {
        if (target == null) return;
        if (mc.level == null) {
            off();       // left the world
            return;
        }
        if (mc.level != level) {
            reset(mc.level);   // changed dimension
            return;
        }
        ExecutorService exec = worker;
        if (exec == null || PENDING.isEmpty()) return;

        long deadline = System.nanoTime() + TICK_BUDGET_NANOS;
        int gen = generation;
        while (!PENDING.isEmpty() && System.nanoTime() < deadline) {
            long sectionPos = PENDING.removeFirstLong();
            int sx = SectionPos.x(sectionPos), sy = SectionPos.y(sectionPos), sz = SectionPos.z(sectionPos);
            LevelChunk chunk = level.getChunkSource().getChunk(sx, sz, ChunkStatus.FULL, false);
            if (chunk == null) continue;
            int index = level.getSectionIndexFromSectionY(sy);
            if (index < 0 || index >= chunk.getSections().length) continue;
            LevelChunkSection section = chunk.getSections()[index];
            PalettedContainer<BlockState> live = section.getStates().copy();
            exec.submit(() -> compare(gen, sectionPos, sx, sy, sz, live));
        }
    }

    // The comparison (worker thread)

    private static void compare(int gen, long sectionPos, int sx, int sy, int sz,
                                PalettedContainer<BlockState> live) {
        if (gen != generation || other == null) return;
        OtherWorld.Section there = other.section(sx, sy, sz);
        if (there == null) {
            DIFFS.remove(sectionPos);   // nothing to compare against: treat as the same
            return;
        }

        // Fast path: the other side is one block throughout and so is this one.
        if (there.data() == null) {
            var only = there.palette()[0].getBlock();
            if (!live.maybeHas(state -> state.getBlock() != only)) {
                DIFFS.remove(sectionPos);
                return;
            }
        }

        byte[] kindAt = new byte[4096];   // 0 = same, otherwise Kind ordinal + 1
        BlockState[] theirsAt = new BlockState[4096];
        // What the renderer draws at each difference: the other world's block for a
        // ghost, the block here for a tint. Decides which faces a neighbour hides.
        BlockState[] shownAt = new BlockState[4096];
        int count = 0;
        for (int i = 0; i < 4096; i++) {
            BlockState here = live.get(i & 15, i >> 8, (i >> 4) & 15);
            BlockState theirs = there.get(i);
            if (here.getBlock() == theirs.getBlock()) continue;
            Kind kind = here.isAir() ? Kind.ADDED : theirs.isAir() ? Kind.REMOVED : Kind.CHANGED;
            kindAt[i] = (byte) (kind.ordinal() + 1);
            theirsAt[i] = theirs;
            shownAt[i] = kind == Kind.ADDED ? theirs : here;
            count++;
        }
        if (gen != generation) return;
        if (count == 0) {
            DIFFS.remove(sectionPos);
            return;
        }

        short[] indices = new short[count];
        byte[] kinds = new byte[count];
        byte[] faces = new byte[count];
        BlockState[] theirs = new BlockState[count];
        int n = 0;
        for (int i = 0; i < 4096; i++) {
            if (kindAt[i] == 0) continue;
            indices[n] = (short) i;
            kinds[n] = (byte) (kindAt[i] - 1);
            faces[n] = exposedFaces(kindAt, shownAt, i);
            theirs[n] = theirsAt[i];
            n++;
        }
        DIFFS.put(sectionPos, new SectionDiff(sx, sy, sz, indices, kinds, faces, theirs));
    }

    /**
     * Which of a block's six faces to draw. A face is hidden behind a neighbour of
     * the same colour that would cover it: a full solid block, or, where both are
     * drawn as boxes (water, lava), the box beside it. So a solid build draws as
     * one see-through shell, and a plank behind a torch keeps its face. Faces on
     * the section's edge are always drawn.
     * Bits: 0 down, 1 up, 2 north, 3 south, 4 west, 5 east.
     */
    private static byte exposedFaces(byte[] kindAt, BlockState[] shownAt, int i) {
        int x = i & 15, z = (i >> 4) & 15, y = i >> 8;
        int mask = 0;
        if (y == 0 || !covers(kindAt, shownAt, i, i - 256)) mask |= 1;
        if (y == 15 || !covers(kindAt, shownAt, i, i + 256)) mask |= 1 << 1;
        if (z == 0 || !covers(kindAt, shownAt, i, i - 16)) mask |= 1 << 2;
        if (z == 15 || !covers(kindAt, shownAt, i, i + 16)) mask |= 1 << 3;
        if (x == 0 || !covers(kindAt, shownAt, i, i - 1)) mask |= 1 << 4;
        if (x == 15 || !covers(kindAt, shownAt, i, i + 1)) mask |= 1 << 5;
        return (byte) mask;
    }

    /** Whether the difference at {@code j} hides the face of the one at {@code i} that touches it. */
    private static boolean covers(byte[] kindAt, BlockState[] shownAt, int i, int j) {
        if (kindAt[j] != kindAt[i]) return false;
        BlockState mine = shownAt[i], theirs = shownAt[j];
        if (theirs.isSolidRender()) return true;
        // ponytail: "drawn as a box" guessed from the render shape; a model-less
        // block-entity block (a chest) still draws its inner box faces.
        return mine.getRenderShape() == RenderShape.INVISIBLE && theirs.getRenderShape() == RenderShape.INVISIBLE;
    }
}
