package com.trevorschoeny.sandboxes.ui;

import com.mojang.blaze3d.platform.InputConstants;

import com.trevorschoeny.sandboxes.overlay.Overlay;
import com.trevorschoeny.sandboxes.sandbox.WorldSwitch;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;

/**
 * Sandboxes' keys, in their own section of Controls. Both ship unbound: most
 * keys are already taken by vanilla and other mods, so the player picks one.
 */
public final class Keys {

    private Keys() {}

    public static void install() {
        KeyMapping.Category category =
                KeyMapping.Category.register(Identifier.fromNamespaceAndPath("sandboxes", "sandboxes"));

        // Hop: into the sandbox joined last, or back to the main world from inside one.
        KeyMapping hop = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.sandboxes.hop", InputConstants.Type.KEYBOARD, InputConstants.UNKNOWN.getValue(), category));

        // ponytail: one overlay key until per-sandbox keys land with Keybindery's
        // runtime-bind support. In a world it shows the sandbox joined last;
        // inside a sandbox it compares with the main world.
        KeyMapping overlay = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.sandboxes.overlay", InputConstants.Type.KEYBOARD, InputConstants.UNKNOWN.getValue(), category));

        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            while (hop.consumeClick()) WorldSwitch.hop();
            while (overlay.consumeClick()) Overlay.toggleDefault();
        });
    }
}
