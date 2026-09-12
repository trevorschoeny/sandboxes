package com.trevorschoeny.sandboxes.config;

import dev.isxander.yacl3.api.ConfigCategory;
import dev.isxander.yacl3.api.Option;
import dev.isxander.yacl3.api.OptionDescription;
import dev.isxander.yacl3.api.OptionGroup;
import dev.isxander.yacl3.api.YetAnotherConfigLib;
import dev.isxander.yacl3.api.controller.BooleanControllerBuilder;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** YACL screen: one tab, one toggle. */
public final class SandboxesConfigScreen {

    private SandboxesConfigScreen() {}

    public static Screen create(Screen parent) {
        Option<Boolean> quick = Option.<Boolean>createBuilder()
                .name(Component.literal("Quick sandbox button"))
                .description(OptionDescription.of(Component.literal(
                        "Shows a small button in the top-right corner of the pause menu. "
                                + "In your world it jumps straight to the sandbox you used last; "
                                + "inside a sandbox it takes you back. It stays hidden until the "
                                + "world has a sandbox to jump to.")))
                .binding(true, SandboxesConfig::quickButton, SandboxesConfig::setQuickButton)
                .controller(opt -> BooleanControllerBuilder.create(opt).yesNoFormatter())
                .build();

        ConfigCategory features = ConfigCategory.createBuilder()
                .name(Component.literal("Features"))
                .group(OptionGroup.createBuilder()
                        .name(Component.literal("Sandboxes"))
                        .description(OptionDescription.of(Component.literal(
                                "Creative-mode copies of your world.")))
                        .option(quick)
                        .build())
                .build();

        return YetAnotherConfigLib.createBuilder()
                .title(Component.literal("Sandboxes"))
                .category(features)
                .build()
                .generateScreen(parent);
    }
}
