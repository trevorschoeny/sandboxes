package com.trevorschoeny.sandboxes.mixin;

import com.trevorschoeny.sandboxes.overlay.Overlay;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tells the overlay which section to re-check when a block changes in the
 * world you're in. This is the same call vanilla uses to rebuild a chunk's
 * mesh, so it sees every change the player can see.
 */
@Mixin(ClientLevel.class)
public abstract class ClientLevelMixin {

    @Inject(method = "sendBlockUpdated", at = @At("HEAD"))
    private void sandboxes$recheckOverlay(BlockPos pos, BlockState oldState, BlockState newState,
                                          int flags, CallbackInfo ci) {
        // The overlay compares blocks, not states: a crop growing or a wire powering isn't a change.
        if (oldState.getBlock() != newState.getBlock()) Overlay.onBlockChanged(pos);
    }
}
