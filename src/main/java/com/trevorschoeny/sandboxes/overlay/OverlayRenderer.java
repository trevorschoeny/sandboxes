package com.trevorschoeny.sandboxes.overlay;

import com.trevorschoeny.sandboxes.config.SandboxesConfig;

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
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Draws the overlay (Trev, 2026-10-15):
 * <ul>
 *   <li><b>Added</b> (the other world has a block, you have air): a see-through
 *       green ghost of the other world's block, its real model and texture.</li>
 *   <li><b>Removed</b> (you have a block, the other world has air): your block
 *       tinted red, its own shape, not a box.</li>
 *   <li><b>Changed</b> (a different block): your block tinted yellow. Break it and
 *       the spot becomes "added", so the green ghost of what goes there appears.</li>
 * </ul>
 * A tint over a real block is its own model drawn a hair larger, so it wraps the
 * block instead of flickering into it. A block with no model of its own (water,
 * lava, a chest, which draw some other way) falls back to a tinted box. How
 * see-through all of it is comes from the opacity setting.
 *
 * <p>Drawn through Fabric's level render events and vanilla's own render types
 * (the moving-block one pistons use, and debug quads for the boxes), never raw
 * OpenGL, so it runs the same on the OpenGL and Vulkan backends, on Mac and
 * Windows, and alongside Sodium, which replaces vanilla's chunk rendering but
 * not these events. Ghosts are full-bright so they read at night, and hide
 * behind real walls like any block.
 *
 * ponytail: every visible difference's quads are written each frame from the
 * cached results. With faces between same-coloured neighbours hidden, that's
 * cheap for a build's worth of blocks; the ceiling is tens of thousands of
 * visible faces. The upgrade is GPU buffers built once per section change, as
 * vanilla's cloud renderer does.
 */
public final class OverlayRenderer {

    private OverlayRenderer() {}

    /** Pushes a tint or box a hair outward, so it doesn't flicker into the real block under it. */
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
            int alpha = Math.round(SandboxesConfig.overlayOpacity() * 2.55f) << 24;
            // Two passes over the same results: models with the block atlas, and boxes for what has no model.
            context.submitNodeCollector().submitCustomGeometry(context.poseStack(), RenderTypes.translucentMovingBlock(),
                    (pose, out) -> draw(pose, out, level, camera, maxDistance, alpha, true));
            context.submitNodeCollector().submitCustomGeometry(context.poseStack(), RenderTypes.debugQuads(),
                    (pose, out) -> draw(pose, out, level, camera, maxDistance, alpha, false));
        });
    }

    /** One pass: {@code models} draws every difference that has a model, otherwise every one that doesn't. */
    private static void draw(PoseStack.Pose pose, VertexConsumer out, ClientLevel level, Vec3 camera,
                             double maxDistance, int alpha, boolean models) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (Overlay.SectionDiff diff : Overlay.DIFFS.values()) {
            int originX = diff.sectionX() * 16, originY = diff.sectionY() * 16, originZ = diff.sectionZ() * 16;
            if (Math.abs(originX + 8 - camera.x) > maxDistance || Math.abs(originZ + 8 - camera.z) > maxDistance) continue;

            for (int n = 0; n < diff.indices().length; n++) {
                int i = diff.indices()[n];
                Overlay.Kind kind = Overlay.Kind.values()[diff.kinds()[n]];
                pos.set(originX + (i & 15), originY + (i >> 8), originZ + ((i >> 4) & 15));
                // A ghost shows the other world's block; a tint wraps the one that's here.
                BlockState shown = kind == Overlay.Kind.ADDED ? diff.theirs()[n] : level.getBlockState(pos);
                List<BlockStateModelPart> parts = parts(shown);
                if (parts.isEmpty() == models) continue;

                // Positions relative to the camera, which is what the pose expects.
                float x = (float) (pos.getX() - camera.x), y = (float) (pos.getY() - camera.y), z = (float) (pos.getZ() - camera.z);
                int color = alpha | kind.rgb;
                if (models) {
                    // A ghost sits exactly in its cell; a tint over a real block a hair larger, from its centre.
                    float grow = kind == Overlay.Kind.ADDED ? 1f : 1f + 2 * INFLATE;
                    SCRATCH.set(pose);
                    SCRATCH.translate(x + 0.5f, y + 0.5f, z + 0.5f);
                    SCRATCH.scale(grow, grow, grow);
                    SCRATCH.translate(-0.5f, -0.5f, -0.5f);
                    model(SCRATCH, out, parts, color, diff.exposedFaces()[n]);
                } else {
                    box(pose, out, color, diff.exposedFaces()[n],
                            x - INFLATE, y - INFLATE, z - INFLATE, x + 1 + INFLATE, y + 1 + INFLATE, z + 1 + INFLATE);
                }
            }
        }
    }

    /**
     * A block's model, every quad tinted one colour. A face that vanilla would
     * cull against a solid neighbour is skipped when the neighbour is drawn in
     * the same colour, so a wall of ghosts is one shell.
     */
    private static void model(PoseStack.Pose pose, VertexConsumer out, List<BlockStateModelPart> parts, int color, int exposed) {
        QUAD.setColor(color);
        QUAD.setLightCoords(LightCoordsUtil.FULL_BRIGHT);
        QUAD.setOverlayCoords(OverlayTexture.NO_OVERLAY);
        for (BlockStateModelPart part : parts) {
            for (BakedQuad quad : part.getQuads(null)) out.putBakedQuad(pose, quad, QUAD);
            for (Direction side : Direction.values()) {
                if ((exposed & (1 << side.ordinal())) == 0) continue;
                // exposedFaces' bits follow Direction's order: down, up, north, south, west, east.
                for (BakedQuad quad : part.getQuads(side)) out.putBakedQuad(pose, quad, QUAD);
            }
        }
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

    private static void box(PoseStack.Pose pose, VertexConsumer out, int color, int faces,
                            float x0, float y0, float z0, float x1, float y1, float z1) {
        if ((faces & 1) != 0)      quad(pose, out, color, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1); // down
        if ((faces & 1 << 1) != 0) quad(pose, out, color, x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0); // up
        if ((faces & 1 << 2) != 0) quad(pose, out, color, x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0); // north
        if ((faces & 1 << 3) != 0) quad(pose, out, color, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1); // south
        if ((faces & 1 << 4) != 0) quad(pose, out, color, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0); // west
        if ((faces & 1 << 5) != 0) quad(pose, out, color, x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1); // east
    }

    private static void quad(PoseStack.Pose pose, VertexConsumer out, int color,
                             float ax, float ay, float az, float bx, float by, float bz,
                             float cx, float cy, float cz, float dx, float dy, float dz) {
        out.addVertex(pose, ax, ay, az).setColor(color);
        out.addVertex(pose, bx, by, bz).setColor(color);
        out.addVertex(pose, cx, cy, cz).setColor(color);
        out.addVertex(pose, dx, dy, dz).setColor(color);
    }
}
