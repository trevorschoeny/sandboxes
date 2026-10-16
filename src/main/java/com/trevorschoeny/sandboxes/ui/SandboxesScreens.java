package com.trevorschoeny.sandboxes.ui;

import com.trevorschoeny.sandboxes.sandbox.CurrentWorld;

import net.minecraft.client.gui.screens.Screen;

/**
 * Which Sandboxes screen opens. One place, so the pause menu and the
 * comparison switch agree.
 *
 * ponytail: comparison only (Trev, 2026-10-15). Once Trev picks between the
 * compact screen and the vanilla-style list, this collapses to the winner.
 */
public final class SandboxesScreens {

    private SandboxesScreens() {}

    private static boolean compact = true;

    static void preferCompact(boolean value) {
        compact = value;
    }

    /** The sandbox list for a world, in whichever look was used last. */
    public static Screen sandboxes(Screen parent, String worldId) {
        return compact ? new CompactSandboxesScreen(parent, worldId, "sandboxes") : new SandboxesScreen(parent, worldId);
    }

    /** Sandboxes' settings: the compact screen's Settings tab. Without a world, that's the only tab. */
    public static Screen settings(Screen parent) {
        return new CompactSandboxesScreen(parent, CurrentWorld.hostWorldId(), "settings");
    }
}
