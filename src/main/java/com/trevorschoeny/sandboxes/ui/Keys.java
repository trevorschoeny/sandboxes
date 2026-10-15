package com.trevorschoeny.sandboxes.ui;

import com.mojang.blaze3d.platform.InputConstants;

import com.trevorschoeny.sandboxes.sandbox.WorldSwitch;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;

/**
 * Sandboxes' key in Controls. It ships unbound: most keys are already taken
 * by vanilla and other mods, so the player picks one. Each sandbox's overlay
 * key lives in the Sandboxes list instead (see OverlayKeys).
 */
public final class Keys {

    private Keys() {}

    public static void install() {
        KeyMapping.Category category =
                KeyMapping.Category.register(Identifier.fromNamespaceAndPath("sandboxes", "sandboxes"));

        // Hop: into the sandbox joined last, or back to the main world from inside one.
        KeyMapping hop = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.sandboxes.hop", InputConstants.Type.KEYBOARD, InputConstants.UNKNOWN.getValue(), category));

        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            while (hop.consumeClick()) WorldSwitch.hop();
        });
    }
}
