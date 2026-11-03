package com.trevorschoeny.sandboxes.checks;

import com.trevorschoeny.sandboxes.sandbox.CurrentWorld;
import com.trevorschoeny.sandboxes.sandbox.SandboxStore;
import com.trevorschoeny.sandboxes.ui.SandboxesScreen;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.PauseScreen;

/**
 * Inside a sandbox the pause menu's Sandboxes button opens the list, with the
 * main world pinned on top: its Join is the live one, while the sandbox you're
 * in has Join and Sync greyed out and no Delete. The quick button shows on the
 * list as well, naming where it goes.
 */
public class PinnedMainChecks implements FabricClientGameTest {

    @Override
    public void runTest(ClientGameTestContext ctx) {
        try (TestSingleplayerContext world = ctx.worldBuilder().create()) {
            world.getConnection().waitForChunksRender();
            String worldId = ctx.computeOnClient(mc -> CurrentWorld.levelId());
            // One sandbox. The copy reads what's on disk, so save first.
            world.getServer().runOnServer(server -> server.saveEverything(true, true, false));
            try {
                ctx.<java.io.IOException>runOnClient(mc -> SandboxStore.create(worldId));
            } catch (java.io.IOException e) {
                throw new AssertionError("couldn't make a sandbox of " + worldId, e);
            }

            // In the main world: its own Join is greyed, the sandbox's is live.
            openFromPause(ctx);
            expect(ctx, "Join", 1, "in the main world, only the sandbox's Join should be live");
            expect(ctx, "Jump to Sandbox 1", 1, "the list shows the quick button too");
            ctx.takeScreenshot("sandboxes-pinned-main-world");

            ListChecks.click(ctx, "Join");
            ctx.waitFor(mc -> mc.player != null && mc.gui.screen() == null && CurrentWorld.inSandbox(), 20 * 60);
            ctx.waitTicks(20);

            // Inside the sandbox: same list, the main world's Join is the live one.
            openFromPause(ctx);
            expect(ctx, "Join", 1, "inside the sandbox, only the main world's Join should be live");
            expect(ctx, "Sync", 0, "the sandbox you're in can't be synced");
            expect(ctx, "✕", 0, "the sandbox you're in can't be deleted");
            expect(ctx, "Back to " + worldId, 1, "inside a sandbox the list's quick button goes back");
            ctx.takeScreenshot("sandboxes-pinned-in-sandbox");

            // Join on the main world, taking you home, stays in Trev's walk: Fabric's
            // test harness doesn't reopen the world it created once something else
            // has closed it (the screen sits on "Saving world"). The same switch
            // works in the game, through the quick button's "Back to".
        }
    }

    /** Esc menu, then its Sandboxes icon (named by its tooltip), and the list must be what opens. */
    private static void openFromPause(ClientGameTestContext ctx) {
        ctx.setScreen(() -> new PauseScreen(true));
        ctx.waitTicks(5);
        ListChecks.click(ctx, "Sandboxes");
        ctx.waitFor(mc -> mc.gui.screen() instanceof SandboxesScreen, 40);
        ctx.waitTicks(5);
    }

    private static void expect(ClientGameTestContext ctx, String label, long n, String why) {
        long live = ctx.computeOnClient(mc -> ListChecks.count(mc, label));
        if (live != n) throw new AssertionError(why + ": found " + live + " live \"" + label + "\"");
    }
}
