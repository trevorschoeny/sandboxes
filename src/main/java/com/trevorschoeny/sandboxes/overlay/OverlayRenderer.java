package com.trevorschoeny.sandboxes.overlay;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.world.phys.Vec3;

/**
 * Draws the overlay's tinted boxes: step one of two (plan.md). Step two swaps
 * the boxes for see-through ghosts of the real blocks.
 *
 * <p>Drawn through Fabric's level render events and vanilla's own debug-quad
 * render type, never raw OpenGL, so it runs the same on the OpenGL and Vulkan
 * backends, on Mac and Windows, and alongside Sodium, which replaces vanilla's
 * chunk rendering but not these events.
 *
 * ponytail: the boxes' vertices are written every frame from the cached
 * results. With faces between same-coloured neighbours already hidden, that's
 * cheap for a build's worth of blocks; the ceiling is tens of thousands of
 * visible faces. Step two moves the geometry into GPU buffers built once per
 * section change, as vanilla's cloud renderer does.
 */
public final class OverlayRenderer {

    private OverlayRenderer() {}

    /** Pushes the box faces a hair outward, so a box over a real block doesn't flicker into it. */
    private static final float INFLATE = 0.003f;

    public static void install() {
        LevelRenderEvents.COLLECT_SUBMITS.register(context -> {
            if (!Overlay.active() || Overlay.DIFFS.isEmpty()) return;
            Vec3 camera = context.levelState().cameraRenderState.pos;
            double maxDistance = (Minecraft.getInstance().options.getEffectiveRenderDistance() + 1) * 16.0;
            context.submitNodeCollector().submitCustomGeometry(context.poseStack(), RenderTypes.debugQuads(),
                    (pose, consumer) -> draw(pose, consumer, camera, maxDistance));
        });
    }

    private static void draw(PoseStack.Pose pose, VertexConsumer out, Vec3 camera, double maxDistance) {
        for (Overlay.SectionDiff diff : Overlay.DIFFS.values()) {
            double originX = diff.sectionX() * 16.0, originY = diff.sectionY() * 16.0, originZ = diff.sectionZ() * 16.0;
            if (Math.abs(originX + 8 - camera.x) > maxDistance || Math.abs(originZ + 8 - camera.z) > maxDistance) continue;

            for (int n = 0; n < diff.indices().length; n++) {
                int i = diff.indices()[n];
                int color = Overlay.Kind.values()[diff.kinds()[n]].color;
                int faces = diff.exposedFaces()[n];
                // Positions relative to the camera, which is what the pose expects.
                float x0 = (float) (originX + (i & 15) - camera.x) - INFLATE;
                float y0 = (float) (originY + (i >> 8) - camera.y) - INFLATE;
                float z0 = (float) (originZ + ((i >> 4) & 15) - camera.z) - INFLATE;
                float x1 = x0 + 1 + 2 * INFLATE, y1 = y0 + 1 + 2 * INFLATE, z1 = z0 + 1 + 2 * INFLATE;
                box(pose, out, color, faces, x0, y0, z0, x1, y1, z1);
            }
        }
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
