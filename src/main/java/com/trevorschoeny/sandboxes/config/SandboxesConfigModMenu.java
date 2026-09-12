package com.trevorschoeny.sandboxes.config;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/** ModMenu entry point: opens the Sandboxes config screen from the mods list. */
public final class SandboxesConfigModMenu implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return SandboxesConfigScreen::create;
    }
}
