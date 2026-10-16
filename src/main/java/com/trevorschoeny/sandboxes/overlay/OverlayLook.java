package com.trevorschoeny.sandboxes.overlay;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * What the overlay difference under the crosshair is, as "Stone → Oak Planks":
 * the block here, then the other world's. Ghosts are air in this world, so the
 * game's own crosshair target can't see them; this walks the look ray block by
 * block instead, stopping at the first difference, or at a real block that's
 * the same in both worlds (it hides anything behind it).
 */
public final class OverlayLook {

    private OverlayLook() {}

    /** How far the readout looks: further than reach, since you check a build from a step back. */
    private static final double RANGE = 48.0;

    private static Component label = Component.empty();

    public static void install() {
        ClientTickEvents.END_CLIENT_TICK.register(mc -> label = look(mc));
    }

    /** The readout, or empty when the crosshair isn't on a difference. */
    public static Component label() {
        return label;
    }

    public static boolean showing() {
        return !label.getString().isEmpty();
    }

    private static Component look(Minecraft mc) {
        if (!Overlay.active() || mc.level == null || mc.player == null) return Component.empty();
        Vec3 from = mc.player.getEyePosition();
        Vec3 dir = mc.player.getViewVector(1f);

        // Step through the cells the ray crosses, in order (Amanatides and Woo).
        BlockPos.MutableBlockPos cell = new BlockPos.MutableBlockPos(
                Mth.floor(from.x), Mth.floor(from.y), Mth.floor(from.z));
        int stepX = (int) Math.signum(dir.x), stepY = (int) Math.signum(dir.y), stepZ = (int) Math.signum(dir.z);
        double tDeltaX = stepX == 0 ? Double.MAX_VALUE : Math.abs(1 / dir.x);
        double tDeltaY = stepY == 0 ? Double.MAX_VALUE : Math.abs(1 / dir.y);
        double tDeltaZ = stepZ == 0 ? Double.MAX_VALUE : Math.abs(1 / dir.z);
        double tMaxX = stepX == 0 ? Double.MAX_VALUE : (stepX > 0 ? cell.getX() + 1 - from.x : from.x - cell.getX()) * tDeltaX;
        double tMaxY = stepY == 0 ? Double.MAX_VALUE : (stepY > 0 ? cell.getY() + 1 - from.y : from.y - cell.getY()) * tDeltaY;
        double tMaxZ = stepZ == 0 ? Double.MAX_VALUE : (stepZ > 0 ? cell.getZ() + 1 - from.z : from.z - cell.getZ()) * tDeltaZ;

        double t = 0;
        while (t <= RANGE) {
            Overlay.Difference difference = Overlay.at(cell);
            BlockState here = mc.level.getBlockState(cell);
            if (difference != null) {
                return Component.translatable("sandboxes.overlay.change",
                        here.getBlock().getName(), difference.theirs().getBlock().getName());
            }
            // A real, solid block both worlds share hides whatever is behind it. Water and plants don't.
            if (here.canOcclude()) return Component.empty();

            if (tMaxX < tMaxY && tMaxX < tMaxZ) {
                t = tMaxX; tMaxX += tDeltaX; cell.move(stepX, 0, 0);
            } else if (tMaxY < tMaxZ) {
                t = tMaxY; tMaxY += tDeltaY; cell.move(0, stepY, 0);
            } else {
                t = tMaxZ; tMaxZ += tDeltaZ; cell.move(0, 0, stepZ);
            }
        }
        return Component.empty();
    }
}
