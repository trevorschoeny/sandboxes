package com.trevorschoeny.sandboxes;

import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sandboxes: creative-mode copies of a singleplayer world, joined and left from
 * the pause menu. Client-only: every sandbox runs on the integrated server, so
 * nothing here needs a dedicated-server side.
 *
 * Design: Leadership drive, mods/sandboxes/plan.md.
 */
public class SandboxesClient implements ClientModInitializer {
    public static final String MOD_ID = "sandboxes";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitializeClient() {
        LOGGER.info("[Sandboxes] client init");
    }
}
