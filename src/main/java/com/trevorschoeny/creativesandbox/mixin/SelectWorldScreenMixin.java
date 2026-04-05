package com.trevorschoeny.creativesandbox.mixin;

import com.trevorschoeny.creativesandbox.SandboxManager;
import net.minecraft.client.gui.screens.worldselection.WorldSelectionList;
import net.minecraft.world.level.storage.LevelSummary;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.util.List;
import java.util.stream.Collectors;

// Targets WorldSelectionList (not SelectWorldScreen) — that's where level entries
// are actually received and displayed. We intercept the list in handleNewLevels()
// before it gets stored or rendered, filtering out __sandbox__ folders.
@Mixin(WorldSelectionList.class)
public class SelectWorldScreenMixin {

    /** Filter out sandbox worlds from the level list before it's displayed. */
    @ModifyVariable(
        method = "handleNewLevels",
        at = @At("HEAD"),
        argsOnly = true
    )
    private List<LevelSummary> filterSandboxWorlds(List<LevelSummary> levels) {
        // handleNewLevels is called with null while levels are still loading async
        if (levels == null) return null;
        return levels.stream()
                .filter(level -> !SandboxManager.isSandboxFolder(level.getLevelId()))
                .collect(Collectors.toList());
    }
}
