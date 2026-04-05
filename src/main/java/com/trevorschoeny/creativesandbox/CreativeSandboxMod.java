package com.trevorschoeny.creativesandbox;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CreativeSandboxMod implements ModInitializer {
    public static final String MOD_ID = "sandboxes";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        CreativeSandboxConfig.load();
        CreativeSandbox.init();

        LOGGER.info("[Sandboxes] Loaded successfully!");
    }
}
