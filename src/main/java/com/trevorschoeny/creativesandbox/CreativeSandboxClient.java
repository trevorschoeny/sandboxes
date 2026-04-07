package com.trevorschoeny.creativesandbox;

import com.trevorschoeny.menukit.config.MKFamily;
import com.trevorschoeny.menukit.MenuKit;
import dev.isxander.yacl3.api.*;
import dev.isxander.yacl3.api.controller.BooleanControllerBuilder;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.KeyMapping;
import net.minecraft.network.chat.Component;

/**
 * Client-side entry point for the Sandboxes mod.
 *
 * Joins the "trevmods" family for shared config screen and keybind category.
 * Registers the sandbox config category via the family API.
 */
public class CreativeSandboxClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        // Join the shared family — shared identity for all Trev's Mods
        MKFamily family = MenuKit.family("trevmods")
                .modId(CreativeSandboxMod.MOD_ID);

        // Get the shared keybind category from the family
        KeyMapping.Category category = family.getKeybindCategory();

        // Initialize client-side features (keybinds, panels, tick events)
        CreativeSandbox.initClient(category);

        // Register config category with the family
        registerCreativeSandboxConfig(family);
    }

    private void registerCreativeSandboxConfig(MKFamily family) {
        family.configCategory(CreativeSandboxMod.MOD_ID, "Sandbox", () -> {
            CreativeSandboxConfig cfg = CreativeSandboxConfig.get();
            return ConfigCategory.createBuilder()
                    .name(Component.literal("Sandbox"))
                    .tooltip(Component.literal("Settings for the Sandbox feature"))
                    .option(Option.<Boolean>createBuilder()
                            .name(Component.literal("Show Sandbox Button"))
                            .description(OptionDescription.of(Component.literal(
                                    "Show the sandbox button in the inventory and pause menu. " +
                                    "You can still open the sandbox UI via the keybind in Controls.")))
                            .binding(true, () -> cfg.showSandboxButton, val -> cfg.showSandboxButton = val)
                            .controller(BooleanControllerBuilder::create)
                            .build())
                    .build();
        }, CreativeSandboxConfig::save);
    }
}
