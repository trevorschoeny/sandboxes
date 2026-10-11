package com.trevorschoeny.sandboxes.ui;

import com.trevorschoeny.sandboxes.config.SandboxesConfig;
import com.trevorschoeny.sandboxes.sandbox.CurrentWorld;
import com.trevorschoeny.sandboxes.sandbox.Sandbox;
import com.trevorschoeny.sandboxes.sandbox.SandboxStore;
import com.trevorschoeny.sandboxes.sandbox.WorldSwitch;

import com.trevlar.menukit.api.element.Button;
import com.trevlar.menukit.api.panel.InsideRegion;
import com.trevlar.menukit.api.panel.Panel;
import com.trevlar.menukit.api.panel.PanelPosition;
import com.trevlar.menukit.api.panel.PanelStyle;
import com.trevlar.menukit.api.panel.VanillaScreenPanelAdapter;

import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.List;

/**
 * The quick-sandbox button: one small button in the pause menu's top-right
 * corner. In a world it jumps to the sandbox used last; in a sandbox it goes
 * back. It stays hidden when the world has no sandbox to jump to.
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
     * Cache for "does this world have a sandbox", with the world it was read for.
     *
     * <p>Visibility is asked every frame and the honest answer is a directory
     * listing, so the answer is held briefly. A sandbox created or deleted
     * elsewhere shows up within a second, well inside the time it takes to reopen
     * this menu.
     */
    private static final long CACHE_MILLIS = 1000L;
    private static String cachedWorldId = null;
    private static boolean cachedHasSandboxes = false;
    private static long cachedAt = 0L;

    /** Builds the panel and registers it. Call once at client init. */
    public static void install() {
        Panel panel = Panel.builder("sandboxes.quick")
                .style(PanelStyle.NONE)
                .position(PanelPosition.screenAnchor(InsideRegion.TOP_RIGHT))
                .add(Button.builder()
                        .icon(() -> CurrentWorld.inSandbox() ? BACK_SPRITE : SPRITE)
                        .size(SIZE, SIZE)
                        .onClick(QuickButton::click)
                        .tooltip(Component.translatable("sandboxes.quick.tooltip"))
                        .build())
                .build()
                .visibleWhen(QuickButton::shouldShow);

        new VanillaScreenPanelAdapter(panel, 0)
                .on(PauseScreen.class);
    }

    /** In a sandbox: go back. In a world: join the sandbox used last. */
    private static void click() {
        String levelId = CurrentWorld.levelId();
        if (levelId == null) return;

        if (SandboxStore.isSandbox(levelId)) {
            WorldSwitch.openLevel(SandboxStore.parentOf(levelId));
            return;
        }
        List<Sandbox> sandboxes = SandboxStore.list(levelId);
        if (sandboxes.isEmpty()) return;      // the button shouldn't be visible anyway
        Sandbox target = sandboxes.get(0);    // list() is most-recently-joined first
        WorldSwitch.enterSandbox(target);
    }

    private static boolean shouldShow() {
        if (!SandboxesConfig.quickButton()) return false;
        String levelId = CurrentWorld.levelId();
        if (levelId == null) return false;
        // Inside a sandbox the button always has somewhere to go: back.
        if (SandboxStore.isSandbox(levelId)) return true;
        return hasSandboxes(levelId);
    }

    private static boolean hasSandboxes(String worldId) {
        long now = System.currentTimeMillis();
        if (!worldId.equals(cachedWorldId) || now - cachedAt > CACHE_MILLIS) {
            cachedWorldId = worldId;
            cachedHasSandboxes = !SandboxStore.list(worldId).isEmpty();
            cachedAt = now;
        }
        return cachedHasSandboxes;
    }
}
