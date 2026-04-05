package com.trevorschoeny.creativesandbox.mixin;

import com.trevorschoeny.creativesandbox.CreativeSandboxMod;
import com.trevorschoeny.creativesandbox.SandboxManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Optional;

@Mixin(ServerPlayer.class)
public class ServerPlayerMixin {

    @Inject(method = "addAdditionalSaveData", at = @At("RETURN"))
    private void creativeSandbox$saveModData(ValueOutput output, CallbackInfo ci) {
        SandboxManager sm = SandboxManager.get();
        sm.saveToNbt(output.child("trevormod_sandboxes"));
    }

    @Inject(method = "readAdditionalSaveData", at = @At("RETURN"))
    private void creativeSandbox$loadModData(ValueInput input, CallbackInfo ci) {
        ServerPlayer self = (ServerPlayer) (Object) this;
        String worldFolder = ((MinecraftServerAccessor) self.level().getServer())
                .getStorageSource().getLevelId();
        boolean inSandbox = SandboxManager.isSandboxFolder(worldFolder);

        if (!inSandbox) {
            Optional<ValueInput> sandboxInput = input.child("trevormod_sandboxes");
            if (sandboxInput.isPresent()) {
                SandboxManager.get().loadFromNbt(sandboxInput.get());
            } else {
                SandboxManager.get().clearData();
            }
            SandboxManager.get().cleanupOrphans();
        } else {
            CreativeSandboxMod.LOGGER.info("[Sandboxes] Inside sandbox '{}' — keeping parent metadata", worldFolder);
        }
    }
}
