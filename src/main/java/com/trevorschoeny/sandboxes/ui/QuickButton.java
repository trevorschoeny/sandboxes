package com.trevorschoeny.sandboxes.ui;

import com.trevorschoeny.sandboxes.config.SandboxesConfig;
import com.trevorschoeny.sandboxes.sandbox.CurrentWorld;
import com.trevorschoeny.sandboxes.sandbox.WorldSwitch;

import com.trevlar.menukit.api.element.Button;
import com.trevlar.menukit.api.panel.InsideRegion;
import com.trevlar.menukit.api.panel.OutsideRegion;
import com.trevlar.menukit.api.panel.Panel;
import com.trevlar.menukit.api.panel.PanelPosition;
import com.trevlar.menukit.api.panel.PanelStyle;
import com.trevlar.menukit.api.panel.ScreenPanelAdapter;
import com.trevlar.menukit.api.panel.VanillaScreenPanelAdapter;

import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import org.jspecify.annotations.Nullable;

import java.util.function.BooleanSupplier;


/**
 * The quick-sandbox button: one small button in the pause menu's top-right
 * corner, and optionally above the inventory too. In a world it jumps to the sandbox used last; in a sandbox it goes
 * back. It stays hidden when the world has no sandbox to jump to. Its tooltip names where it goes.
 *
 * <p>This is the one piece built with MenuKit, which supports a panel on
 * {@code PauseScreen} and whose {@code visibleWhen} hides rendering and clicks
 * together.
 */
public final class QuickButton {

    private QuickButton() {}

    private static final Identifier SPRITE =
            Identifier.fromNamespaceAndPath("sandboxes", "sandbox");
    private static final Identifier BACK_SPRITE =
            Identifier.fromNamespaceAndPath("sandboxes", "sandbox_back");

    private static final int SIZE = 20;

    /**
     * Cache for where the button goes, with the world it was read for.
     *
     * <p>Visibility is asked every frame and the honest answer is a directory
     * listing, so the answer is held briefly. A sandbox created or deleted
     * elsewhere shows up within a second, well inside the time it takes to reopen
     * this menu.
     */
    private static final long CACHE_MILLIS = 1000L;
    private static String cachedWorldId = null;
    private static WorldSwitch.@Nullable Hop cachedTarget = null;
    private static long cachedAt = 0L;

    /** Builds the panels and registers them. Call once at client init. */
    public static void install() {
        // The pause menu's top-right corner.
        new VanillaScreenPanelAdapter(panel("sandboxes.quick",
                PanelPosition.screenAnchor(InsideRegion.TOP_RIGHT), SandboxesConfig::quickButton), 0)
                .on(PauseScreen.class);
        // Above the inventory's top-right corner, survival and creative alike; the
        // region follows the frame (creative's tab rows, an open recipe book) and
        // shares the edge with other mods' buttons without overlapping.
        new ScreenPanelAdapter(panel("sandboxes.quick.inventory",
                PanelPosition.region(OutsideRegion.TOP_ALIGN_RIGHT), SandboxesConfig::quickButtonInventory))
                .on(InventoryScreen.class, CreativeModeInventoryScreen.class);
    }

    /** One quick button, shown while its setting is on and there's somewhere to jump. */
    private static Panel panel(String id, PanelPosition position, BooleanSupplier enabled) {
        return Panel.builder(id)
                .style(PanelStyle.NONE)
                .position(position)
                .add(Button.builder()
                        .icon(() -> CurrentWorld.inSandbox() ? BACK_SPRITE : SPRITE)
                        .size(SIZE, SIZE)
                        .onClick(WorldSwitch::hop)
                        // Says where it goes: "Jump to <sandbox>" or "Back to <world>".
                        .tooltip(() -> {
                            WorldSwitch.Hop target = target();
                            return target == null ? Component.empty() : target.label();
                        })
                        .build())
                .build()
                .visibleWhen(() -> enabled.getAsBoolean() && target() != null);
    }

    private static WorldSwitch.@Nullable Hop target() {
        String levelId = CurrentWorld.levelId();
        if (levelId == null) return null;
        long now = System.currentTimeMillis();
        if (!levelId.equals(cachedWorldId) || now - cachedAt > CACHE_MILLIS) {
            cachedWorldId = levelId;
            cachedTarget = WorldSwitch.hopTarget();
            cachedAt = now;
        }
        return cachedTarget;
    }
}
