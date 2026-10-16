package com.trevorschoeny.sandboxes.overlay;

import com.trevorschoeny.sandboxes.SandboxesClient;
import com.trevorschoeny.sandboxes.sandbox.CurrentWorld;
import com.trevorschoeny.sandboxes.sandbox.Sandbox;
import com.trevorschoeny.sandboxes.sandbox.SandboxStore;
import com.trevorschoeny.keybindery.api.BindingConflict;
import com.trevorschoeny.keybindery.api.Chord;
import com.trevorschoeny.keybindery.api.KeybinderyAPI;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.SemanticVersion;
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.VersionParsingException;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Each sandbox's own overlay key, through Keybindery's runtime bindings.
 *
 * <p>These keys belong to a world, not to the game, so they aren't in Controls:
 * a world's sandbox keys are registered when the player enters it and removed
 * when they leave. In the main world each key shows its sandbox; inside a
 * sandbox, that sandbox's key compares it with the main world.
 *
 * <p>Keybindery is optional. Without it the cards hide the key button, and the
 * eye button still works.
 */
public final class OverlayKeys {

    private OverlayKeys() {}

    private static final List<Identifier> REGISTERED = new ArrayList<>();

    public static void install() {
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> refresh());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> clear());
    }

    /**
     * True when per-sandbox keys can work. Sandboxes bundles keybindery-api, but
     * an installed Keybindery older than 1.3.0 answers to it without having
     * runtime bindings, so it counts as absent rather than crashing on first use.
     */
    public static boolean available() {
        var container = FabricLoader.getInstance().getModContainer("keybindery");
        if (container.isEmpty()) return false;
        try {
            Version min = SemanticVersion.parse("1.3.0");
            return container.get().getMetadata().getVersion().compareTo(min) >= 0;
        } catch (VersionParsingException e) {
            return false;
        }
    }

    /** Re-registers the current world's keys, after any change in the list. */
    public static void refresh() {
        clear();
        if (!available()) return;
        String levelId = CurrentWorld.levelId();
        if (levelId == null) return;

        if (SandboxStore.isSandbox(levelId)) {
            Sandbox self = SandboxStore.readSandbox(levelId);
            register(self, Overlay::toggleAgainstParent);
        } else {
            for (Sandbox sandbox : SandboxStore.list(levelId)) {
                register(sandbox, () -> Overlay.toggleFor(sandbox));
            }
        }
    }

    private static void register(Sandbox sandbox, Runnable onPress) {
        Chord chord = Chord.deserialize(sandbox.overlayKey());
        if (chord.isUnbound()) return;
        Identifier id = idFor(sandbox);
        KeybinderyAPI.getInstance().bindings().register(id,
                Component.translatable("sandboxes.binding.overlay", sandbox.name()), chord, onPress);
        REGISTERED.add(id);
    }

    private static void clear() {
        if (!REGISTERED.isEmpty() && available()) {
            REGISTERED.forEach(id -> KeybinderyAPI.getInstance().bindings().unregister(id));
        }
        REGISTERED.clear();
    }

    /** Keybindery's key button for a sandbox, or null when Keybindery can't provide one. */
    public static @Nullable AbstractWidget button(int x, int y, int width, int height,
                                                  Sandbox sandbox, Consumer<Chord> onChange) {
        if (!available()) return null;
        return KeybinderyAPI.getInstance().captureWidget(x, y, width, height,
                Chord.deserialize(sandbox.overlayKey()), onChange);
    }

    /** What else this sandbox's key is bound to, for the card's warning line. */
    public static List<BindingConflict> conflicts(Sandbox sandbox) {
        if (!available()) return List.of();
        Chord chord = Chord.deserialize(sandbox.overlayKey());
        if (chord.isUnbound()) return List.of();
        try {
            return KeybinderyAPI.getInstance().bindings().conflicts(chord, idFor(sandbox));
        } catch (RuntimeException e) {
            SandboxesClient.LOGGER.warn("[sandboxes] couldn't check key conflicts", e);
            return List.of();
        }
    }

    /**
     * The binding's id, from the sandbox's folder name. Ours are UUIDs, but a
     * folder copied by hand ("<id> copy") can hold a space or capitals, which an
     * id can't, and a bad id would fail every join of that world; those
     * characters become underscores.
     */
    private static Identifier idFor(Sandbox sandbox) {
        String path = sandbox.id().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]", "_");
        return Identifier.fromNamespaceAndPath("sandboxes", "overlay/" + path);
    }
}
