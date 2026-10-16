package com.trevorschoeny.sandboxes.overlay;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.QuadInstance;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Draws the overlay (Trev, 2026-10-15). Colour lives only in thick outlines,
 * one per block, because a see-through colour laid over a block mixes with it
 * (red over gold reads orange):
 * <ul>
 *   <li><b>Added</b> (the other world has a block, you have air): a see-through
 *       ghost of that block in its true colours, so oak reads as oak, outlined
 *       green.</li>
 *   <li><b>Removed</b> (you have a block, the other world has air): your block as
 *       it is, outlined red.</li>
 *   <li><b>Changed</b> (a different block): your block outlined yellow. Break it and
 *       the spot becomes "added", so the ghost of what goes there appears.</li>
 * </ul>
 * An outline follows the block's own shape (a torch's stick, a stair's step).
 * Water, lava and chests, which have no shape of their own here, get the outline
 * of a full block. A full block's edge is left out when both faces beside it
 * are covered by blocks of the same colour, so the inside of a build doesn't
 * show through the ghosts as a cage of lines.
 *
 * <p>Drawn through Fabric's level render events and vanilla's own render types
 * (the moving-block one pistons use, and the lines vanilla's block outline
 * uses), never raw OpenGL, so it runs the same on the OpenGL and Vulkan
 * backends, on Mac and Windows, and alongside Sodium, which replaces vanilla's
 * chunk rendering but not these events. Ghosts are full-bright so they read at
 * night; ghosts and lines hide behind real walls like any block.
 *
 * ponytail: every visible difference is written each frame from the cached
 * results; the ceiling is tens of thousands of visible blocks. The upgrade is
 * GPU buffers built once per section change, as vanilla's cloud renderer does.
 */
public final class OverlayRenderer {

    private OverlayRenderer() {}

    /** How solid a ghost is: the alpha laid over its own colours. */
    private static final int GHOST_ALPHA = 0x99 << 24;
    /** Thick: about twice vanilla's block outline, so it reads from a distance. */
    private static final float LINE_WIDTH = 5f;
    /** Pushes the outline a hair outward, so the block under it doesn't swallow the line. */
    private static final float INFLATE = 0.003f;

    // Render-thread only.
    private static final PoseStack.Pose SCRATCH = new PoseStack.Pose();
    private static final QuadInstance QUAD = new QuadInstance();
    /** Each block state's model parts, for the model set they came from (a resource reload replaces it). */
    private static final Map<BlockState, List<BlockStateModelPart>> PARTS = new HashMap<>();
    private static @Nullable BlockStateModelSet partsFrom = null;

    public static void install() {
        LevelRenderEvents.COLLECT_SUBMITS.register(context -> {
            if (!Overlay.active() || Overlay.DIFFS.isEmpty()) return;
            ClientLevel level = Minecraft.getInstance().level;
            if (level == null) return;
            Vec3 camera = context.levelState().cameraRenderState.pos;
            double maxDistance = (Minecraft.getInstance().options.getEffectiveRenderDistance() + 1) * 16.0;
            context.submitNodeCollector().submitCustomGeometry(context.poseStack(), RenderTypes.translucentMovingBlock(),
                    (pose, out) -> forEachVisible(level, camera, maxDistance, (diff, n, kind, pos, shown) -> {
                        if (kind == Overlay.Kind.ADDED) ghost(pose, out, camera, pos, shown, diff.exposedFaces()[n]);
                    }));
            context.submitNodeCollector().submitCustomGeometry(context.poseStack(), RenderTypes.lines(),
                    (pose, out) -> forEachVisible(level, camera, maxDistance, (diff, n, kind, pos, shown) ->
                            outline(pose, out, level, camera, pos, shown, kind, diff.exposedFaces()[n])));
        });
    }

    private interface Visit {
        void visit(Overlay.SectionDiff diff, int n, Overlay.Kind kind, BlockPos pos, BlockState shown);
    }

    /**
     * Every difference within render distance, with the block it shows: the
     * other world's for a ghost, the one here for an outline over a real block.
     */
    private static void forEachVisible(ClientLevel level, Vec3 camera, double maxDistance, Visit visit) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (Overlay.SectionDiff diff : Overlay.DIFFS.values()) {
            int originX = diff.sectionX() * 16, originY = diff.sectionY() * 16, originZ = diff.sectionZ() * 16;
            if (Math.abs(originX + 8 - camera.x) > maxDistance || Math.abs(originZ + 8 - camera.z) > maxDistance) continue;
            for (int n = 0; n < diff.indices().length; n++) {
                int i = diff.indices()[n];
                Overlay.Kind kind = Overlay.KINDS[diff.kinds()[n]];
                pos.set(originX + (i & 15), originY + (i >> 8), originZ + ((i >> 4) & 15));
                BlockState shown = kind == Overlay.Kind.ADDED ? diff.theirs()[n] : level.getBlockState(pos);
                visit.visit(diff, n, kind, pos, shown);
            }
        }
    }

    // Ghosts

    /**
     * The other world's block, see-through, in its own colours. A face that
     * vanilla would cull against a solid neighbour is skipped when that
     * neighbour is a ghost too, so a ghost wall is one shell.
     */
    private static void ghost(PoseStack.Pose pose, VertexConsumer out, Vec3 camera, BlockPos pos, BlockState state, int exposed) {
        List<BlockStateModelPart> parts = parts(state);
        if (parts.isEmpty()) return;   // water, lava, chests: the outline alone marks them
        SCRATCH.set(pose);
        // Positions relative to the camera, which is what the pose expects.
        SCRATCH.translate((float) (pos.getX() - camera.x), (float) (pos.getY() - camera.y), (float) (pos.getZ() - camera.z));
        QUAD.setColor(GHOST_ALPHA | 0xFFFFFF);
        QUAD.setLightCoords(LightCoordsUtil.FULL_BRIGHT);
        QUAD.setOverlayCoords(OverlayTexture.NO_OVERLAY);
        for (BlockStateModelPart part : parts) {
            for (BakedQuad quad : part.getQuads(null)) out.putBakedQuad(SCRATCH, quad, QUAD);
            for (Direction side : Direction.values()) {
                // exposedFaces' bits follow Direction's order: down, up, north, south, west, east.
                if ((exposed & (1 << side.ordinal())) == 0) continue;
                for (BakedQuad quad : part.getQuads(side)) out.putBakedQuad(SCRATCH, quad, QUAD);
            }
        }
    }

    // Outlines

    /** A full block's twelve edges, each with the two faces it joins (Direction ordinals) and its ends. */
    private static final int[][] CUBE_EDGES = {
            // along x: faces, then x0 y0 z0 x1 y1 z1
            {0, 2, 0, 0, 0, 1, 0, 0}, {0, 3, 0, 0, 1, 1, 0, 1}, {1, 2, 0, 1, 0, 1, 1, 0}, {1, 3, 0, 1, 1, 1, 1, 1},
            // along y
            {2, 4, 0, 0, 0, 0, 1, 0}, {2, 5, 1, 0, 0, 1, 1, 0}, {3, 4, 0, 0, 1, 0, 1, 1}, {3, 5, 1, 0, 1, 1, 1, 1},
            // along z
            {0, 4, 0, 0, 0, 0, 0, 1}, {0, 5, 1, 0, 0, 1, 0, 1}, {1, 4, 0, 1, 0, 0, 1, 1}, {1, 5, 1, 1, 0, 1, 1, 1},
    };

    private static void outline(PoseStack.Pose pose, VertexConsumer out, ClientLevel level, Vec3 camera,
                                BlockPos pos, BlockState shown, Overlay.Kind kind, int exposed) {
        int color = 0xFF000000 | kind.rgb;
        float x = (float) (pos.getX() - camera.x), y = (float) (pos.getY() - camera.y), z = (float) (pos.getZ() - camera.z);
        // A ghost isn't in this world, so its shape is read with no neighbours around it.
        VoxelShape shape = kind == Overlay.Kind.ADDED
                ? shown.getShape(EmptyBlockGetter.INSTANCE, pos) : shown.getShape(level, pos);
        if (shape.isEmpty() || shown.isSolidRender()) {
            // A full block (or one with no shape, drawn as one): skip edges buried inside the build.
            for (int[] e : CUBE_EDGES) {
                if ((exposed & (1 << e[0])) == 0 && (exposed & (1 << e[1])) == 0) continue;
                line(pose, out, color, x, y, z, e[2], e[3], e[4], e[5], e[6], e[7]);
            }
        } else {
            shape.forAllEdges((x0, y0, z0, x1, y1, z1) ->
                    line(pose, out, color, x, y, z, (float) x0, (float) y0, (float) z0, (float) x1, (float) y1, (float) z1));
        }
    }

    /** One edge, in block-local 0..1 coordinates, pushed a hair out from the block's centre. */
    private static void line(PoseStack.Pose pose, VertexConsumer out, int color, float x, float y, float z,
                             float x0, float y0, float z0, float x1, float y1, float z1) {
        x0 = grow(x0); y0 = grow(y0); z0 = grow(z0); x1 = grow(x1); y1 = grow(y1); z1 = grow(z1);
        float dx = x1 - x0, dy = y1 - y0, dz = z1 - z0;
        float length = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        dx /= length; dy /= length; dz /= length;
        out.addVertex(pose, x + x0, y + y0, z + z0).setColor(color).setNormal(pose, dx, dy, dz).setLineWidth(LINE_WIDTH);
        out.addVertex(pose, x + x1, y + y1, z + z1).setColor(color).setNormal(pose, dx, dy, dz).setLineWidth(LINE_WIDTH);
    }

    private static float grow(float v) {
        return 0.5f + (v - 0.5f) * (1 + 2 * INFLATE);
    }

    /**
     * A block state's model parts, or none when it draws no model (air, water,
     * lava, block-entity blocks such as chests).
     *
     * ponytail: one fixed random pick per state, so a block with several
     * variants (grass's rotations, stone's mirrors) ghosts as one of them
     * everywhere. Seed by position if that ever reads wrong.
     */
    private static List<BlockStateModelPart> parts(BlockState state) {
        BlockStateModelSet models = Minecraft.getInstance().getModelManager().getBlockStateModelSet();
        if (models != partsFrom) {
            PARTS.clear();
            partsFrom = models;
        }
        return PARTS.computeIfAbsent(state, s -> {
            if (s.getRenderShape() == RenderShape.INVISIBLE) return List.of();
            List<BlockStateModelPart> parts = new ArrayList<>();
            models.get(s).collectParts(RandomSource.create(42L), parts);
            boolean anyQuads = parts.stream().anyMatch(p -> !p.getQuads(null).isEmpty()
                    || Arrays.stream(Direction.values()).anyMatch(d -> !p.getQuads(d).isEmpty()));
            return anyQuads ? List.copyOf(parts) : List.of();
        });
    }

}
