package com.trevorschoeny.sandboxes.mixin;

import com.trevorschoeny.sandboxes.sandbox.TouchRecorder;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Feeds the touched-sections record. Every block change in a loaded chunk goes
 * through {@code LevelChunk.setBlockState}, whatever caused it: the player,
 * explosions, pistons, flowing water.
 */
@Mixin(LevelChunk.class)
public abstract class LevelChunkMixin {

    @Inject(method = "setBlockState(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Lnet/minecraft/world/level/block/state/BlockState;", at = @At("RETURN"))
    private void sandboxes$recordTouch(BlockPos pos, BlockState state, int flags,
                                       CallbackInfoReturnable<BlockState> cir) {
        // Null means nothing changed. The client keeps its own copies of chunks;
        // only the server's count as the sandbox's real state.
        if (cir.getReturnValue() == null) return;
        if (!(((LevelChunk) (Object) this).getLevel() instanceof ServerLevel level)) return;
        TouchRecorder.touch(level.dimension(), pos);
    }
}
