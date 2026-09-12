package com.trevorschoeny.sandboxes.ui;

import com.trevorschoeny.sandboxes.sandbox.CurrentWorld;

import com.trevlar.menukit.core.PanelStyle;
import com.trevlar.menukit.core.TextLabel;
import com.trevlar.menukit.hud.MKHudAnchor;
import com.trevlar.menukit.hud.MKHudPanel;

import net.minecraft.network.chat.Component;

/**
 * Red text across the top of the screen while the player is inside a sandbox,
 * naming the world it was copied from. The whole feature rests on the player
 * always knowing which of the two worlds they're standing in.
 */
public final class InSandboxHud {

    private InSandboxHud() {}

    private static final int RED = 0xFFFF4444;

    public static void install() {
        MKHudPanel.builder("sandboxes:in-sandbox")
                .anchor(MKHudAnchor.TOP_CENTER, 0, 4)
                .autoSize().padding(0)
                .style(PanelStyle.NONE)
                .showWhen(CurrentWorld::inSandbox)
                .element(new TextLabel(0, 0, InSandboxHud::label, RED, true))
                .build();
    }

    private static Component label() {
        String parent = CurrentWorld.parentDisplayName();
        return Component.translatable("sandboxes.hud.in_sandbox", parent == null ? "?" : parent);
    }
}
