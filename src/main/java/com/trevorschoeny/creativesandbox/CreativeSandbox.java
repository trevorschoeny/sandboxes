package com.trevorschoeny.creativesandbox;

import com.trevorschoeny.creativesandbox.mixin.MinecraftServerAccessor;
import com.trevorschoeny.creativesandbox.ui.SandboxScreen;
import com.trevorschoeny.menukit.MKContext;
import com.trevorschoeny.menukit.MKPanel;
import java.util.List;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

public class CreativeSandbox {

    /** Keybind to open the sandbox UI from anywhere in-game. Unbound by default. */
    public static KeyMapping sandboxKey;

    /** Sandbox button icon — shown in main world. */
    private static final Identifier SANDBOX_ICON =
            Identifier.fromNamespaceAndPath("sandboxes", "sandbox");

    /** Sandbox back icon — shown when inside a sandbox. */
    private static final Identifier SANDBOX_BACK_ICON =
            Identifier.fromNamespaceAndPath("sandboxes", "sandbox_back");

    /** Called from the main ModInitializer — server-safe setup only. */
    public static void init() {
        CreativeSandboxMod.LOGGER.info("[Sandboxes] Sandbox initialized.");
    }

    /**
     * Called from CreativeSandboxClient (ClientModInitializer) — registers the keybind,
     * the per-tick handler, and the inventory button panels.
     */
    public static void initClient(KeyMapping.Category category) {
        // ── Keybind ──────────────────────────────────────────────────────────
        sandboxKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.sandboxes.open_sandbox",
                GLFW.GLFW_KEY_UNKNOWN,
                category
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (sandboxKey.consumeClick()) {
                openSandboxScreen(client);
            }
        });

        // ── Sandbox button (main world) ──────────────────────────────────────
        // Opens the most recently used sandbox. Hidden when in a sandbox.
        MKPanel.builder("sandbox_button")
                .showIn(MKContext.PERSONAL)
                .posAboveRight()
                .autoSize()
                .style(MKPanel.Style.NONE)
                .disabledWhen(() -> {
                    Minecraft mc = Minecraft.getInstance();
                    return mc.getSingleplayerServer() == null
                            || !CreativeSandboxConfig.get().showSandboxButton
                            || isInSandbox();
                })
                .column()
                    .button()
                        .icon(SANDBOX_ICON)
                        .iconSize(11)
                        .size(11, 11)
                        .tooltip("Enter Recent Sandbox")
                        .onClick(btn -> enterRecentSandbox(Minecraft.getInstance()))
                        .done()
                .build();

        // ── Sandbox back button (inside sandbox) ─────────────────────────────
        // Returns to the parent world. Hidden when not in a sandbox.
        MKPanel.builder("sandbox_back_button")
                .showIn(MKContext.PERSONAL)
                .posAboveRight()
                .autoSize()
                .style(MKPanel.Style.NONE)
                .disabledWhen(() -> {
                    Minecraft mc = Minecraft.getInstance();
                    return mc.getSingleplayerServer() == null
                            || !CreativeSandboxConfig.get().showSandboxButton
                            || !isInSandbox();
                })
                .column()
                    .button()
                        .icon(SANDBOX_BACK_ICON)
                        .iconSize(11)
                        .size(11, 11)
                        .tooltip("Return to World")
                        .onClick(btn -> openSandboxScreen(Minecraft.getInstance()))
                        .done()
                .build();

        // ── "SANDBOX MODE" label (inside sandbox) ────────────────────────────
        // Red text label above the inventory, visible only when in a sandbox.
        MKPanel.builder("sandbox_mode_label")
                .showIn(MKContext.PERSONAL)
                .posAboveRight()
                .autoSize()
                .style(MKPanel.Style.NONE)
                .disabledWhen(() -> !isInSandbox())
                .column()
                    .text()
                        .content("SANDBOX MODE")
                        .color(0xFFFF4444) // red
                        .done()
                .build();
    }

    /** Returns true if the player is currently inside a sandbox world. */
    public static boolean isInSandbox() {
        Minecraft mc = Minecraft.getInstance();
        IntegratedServer server = mc.getSingleplayerServer();
        if (server == null) return false;
        String worldFolder = ((MinecraftServerAccessor) server)
                .getStorageSource().getLevelId();
        return SandboxManager.get().getParentWorldId(worldFolder) != null;
    }

    /**
     * Opens the sandbox screen, or returns to the parent world if inside a sandbox.
     * Used by the keybind — acts as a toggle between main world and sandbox.
     */
    private static void openSandboxScreen(Minecraft mc) {
        IntegratedServer server = mc.getSingleplayerServer();
        if (server == null) return;

        String worldFolder = ((MinecraftServerAccessor) server)
                .getStorageSource().getLevelId();
        String parentWorld = SandboxManager.get().getParentWorldId(worldFolder);
        boolean inSandbox  = parentWorld != null;

        if (inSandbox) {
            SandboxManager.disconnectAndOpen(parentWorld);
        } else {
            enterRecentSandbox(mc);
        }
    }

    /**
     * Opens the most recently used sandbox for the current world.
     * If no sandboxes exist, opens the sandbox management screen instead
     * so the user can create one.
     */
    private static void enterRecentSandbox(Minecraft mc) {
        IntegratedServer server = mc.getSingleplayerServer();
        if (server == null) return;

        String worldFolder = ((MinecraftServerAccessor) server)
                .getStorageSource().getLevelId();

        // getSandboxesForWorld returns sorted most-recently-synced first
        List<SandboxMetadata> sandboxes = SandboxManager.get().getSandboxesForWorld(worldFolder);
        if (!sandboxes.isEmpty()) {
            SandboxManager.disconnectAndOpen(sandboxes.get(0).folderName());
        } else {
            // No sandboxes yet — open the management screen so user can create one
            mc.setScreen(new SandboxScreen(mc.screen, worldFolder));
        }
    }
}
