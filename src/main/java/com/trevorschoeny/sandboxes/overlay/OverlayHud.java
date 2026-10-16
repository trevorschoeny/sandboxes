package com.trevorschoeny.sandboxes.overlay;

import com.trevlar.menukit.api.element.TextLabel;
import com.trevlar.menukit.api.hud.HudPanel;
import com.trevlar.menukit.api.panel.InsideRegion;
import com.trevlar.menukit.api.panel.PanelStyle;

/**
 * The overlay's lines at the top of the screen: which world it compares with,
 * and, under it, what differs at the block you're looking at.
 */
public final class OverlayHud {

    private OverlayHud() {}

    public static void install() {
        HudPanel.builder("sandboxes:overlay")
                .region(InsideRegion.TOP_CENTER)
                .autoSize().padding(0)
                .style(PanelStyle.NONE)
                .visibleWhen(Overlay::active)
                .element(TextLabel.builder().text(Overlay::hudLabel).color(0xFFE0E0E0).shadow(true).build())
                .build();
        // Under it, the difference under the crosshair: "Stone → Oak Planks".
        HudPanel.builder("sandboxes:overlay-look")
                .region(InsideRegion.TOP_CENTER)
                .autoSize().padding(0)
                .style(PanelStyle.NONE)
                .visibleWhen(OverlayLook::showing)
                .element(TextLabel.builder().text(OverlayLook::label).color(0xFFFFFFFF).shadow(true).build())
                .build();
    }
}
