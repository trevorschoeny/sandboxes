package com.trevorschoeny.sandboxes.overlay;

import com.trevlar.menukit.api.element.TextLabel;
import com.trevlar.menukit.api.hud.HudPanel;
import com.trevlar.menukit.api.panel.InsideRegion;
import com.trevlar.menukit.api.panel.PanelStyle;

/**
 * One line at the top of the screen while the overlay is on: which world it
 * compares with, and whether it shows sandbox work only or every difference.
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
    }
}
