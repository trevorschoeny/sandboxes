package com.trevorschoeny.sandboxes;

import com.trevorschoeny.sandboxes.config.SandboxesConfig;
import com.trevorschoeny.sandboxes.sandbox.LegacyMigration;
import com.trevorschoeny.sandboxes.ui.InSandboxHud;
import com.trevorschoeny.sandboxes.ui.QuickButton;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sandboxes: creative-mode copies of a singleplayer world, made and joined from
 * the pause menu. Client-only, because every sandbox runs on this client's own
 * integrated server.
 */
public class SandboxesClient implements ClientModInitializer {
    public static final String MOD_ID = "sandboxes";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitializeClient() {
        SandboxesConfig.load();
        QuickButton.install();
        InSandboxHud.install();

        // Bring any Sandboxes 1.0.0 layout forward once the client is up: still
        // before any world is open, so the level.dat files it rewrites are
        // nobody else's, but late enough that the saves folder is known (during
        // mod init the level source doesn't exist yet). Off-thread so a big
        // saves folder never delays the title screen; with nothing to migrate
        // this is one directory listing.
        ClientLifecycleEvents.CLIENT_STARTED.register(client ->
                Thread.ofPlatform().name("sandboxes-migrate").start(LegacyMigration::run));

        LOGGER.info("[Sandboxes] client init");
    }
}
