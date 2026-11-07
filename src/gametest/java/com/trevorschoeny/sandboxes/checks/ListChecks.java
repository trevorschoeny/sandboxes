package com.trevorschoeny.sandboxes.checks;

import com.mojang.blaze3d.platform.InputConstants;
import com.trevorschoeny.sandboxes.sandbox.CurrentWorld;
import com.trevorschoeny.sandboxes.sandbox.SandboxStore;
import com.trevorschoeny.sandboxes.ui.SandboxesScreen;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;

/**
 * The sandbox list changes in place: "+ New sandbox" adds a card and Delete
 * removes one while the screen stays open, the same screen throughout. New
 * sandboxes are numbered, and a deleted number isn't handed out again.
 */
public class ListChecks implements FabricClientGameTest {

    @Override
    public void runTest(ClientGameTestContext ctx) {
        try (TestSingleplayerContext world = ctx.worldBuilder().create()) {
            world.getConnection().waitForChunksRender();
            run(ctx, ctx.computeOnClient(mc -> CurrentWorld.levelId()));
        }
    }

    private static void run(ClientGameTestContext ctx, String worldId) {
        ctx.setScreen(() -> SandboxesScreen.forWorld(new PauseScreen(true), worldId));
        ctx.waitTicks(5);
        Screen screen = ctx.computeOnClient(mc -> mc.gui.screen());
        int before = sandboxes(ctx, worldId);

        click(ctx, "+ New sandbox");
        ctx.waitFor(mc -> SandboxStore.list(worldId).size() == before + 1, 20 * 60);
        // A new card brings a new Join button with it.
        ctx.waitFor(mc -> count(mc, "Join") == before + 1, 40);
        same(ctx, screen, "creating a sandbox");
        ctx.takeScreenshot("sandboxes-list-created");

        click(ctx, "✕");
        ctx.waitTicks(3);
        ctx.takeScreenshot("sandboxes-list-confirm");
        click(ctx, "Confirm");
        ctx.waitFor(mc -> SandboxStore.list(worldId).size() == before, 20 * 60);
        ctx.waitFor(mc -> count(mc, "Join") == before, 40);
        same(ctx, screen, "deleting a sandbox");
        ctx.takeScreenshot("sandboxes-list-deleted");

        // Numbers count on past a deleted sandbox: the next one is Sandbox 2, not 1 again.
        click(ctx, "+ New sandbox");
        ctx.waitFor(mc -> SandboxStore.list(worldId).size() == before + 1, 20 * 60);
        String name = ctx.computeOnClient(mc -> SandboxStore.list(worldId).get(0).name());
        if (!name.equals("Sandbox 2")) {
            throw new AssertionError("after making and deleting Sandbox 1, the next should be Sandbox 2; got " + name);
        }

        // Neither card has been joined, so the list can only be ordering by
        // creation: the one just made (Sandbox 3) must sit above the older one.
        click(ctx, "+ New sandbox");
        ctx.waitFor(mc -> SandboxStore.list(worldId).size() == before + 2, 20 * 60);
        ctx.waitFor(mc -> SandboxesScreen.displayOrder().size() == before + 2, 40);
        String top = ctx.computeOnClient(mc -> SandboxesScreen.displayOrder().get(0).name());
        if (!top.equals("Sandbox 3")) {
            throw new AssertionError("Sandbox 3 (just made) should list above Sandbox 2; list starts with " + top);
        }

        ctx.setScreen(() -> null);
    }

    private static int sandboxes(ClientGameTestContext ctx, String worldId) {
        return ctx.computeOnClient(mc -> SandboxStore.list(worldId).size());
    }

    private static void same(ClientGameTestContext ctx, Screen screen, String what) {
        if (ctx.computeOnClient(mc -> mc.gui.screen()) != screen) {
            throw new AssertionError(what + " replaced the Sandboxes screen; the list should change in place");
        }
    }

    /** Live MenuKit controls carry a stand-in widget named after their label. */
    static long count(Minecraft mc, String label) {
        return mc.gui.screen().children().stream()
                .filter(c -> c instanceof AbstractWidget w && w.isActive() && w.getMessage().getString().equals(label))
                .count();
    }

    /** Clicks the first live control with this label, with the real mouse. */
    static void click(ClientGameTestContext ctx, String label) {
        int[] at = ctx.computeOnClient(mc -> mc.gui.screen().children().stream()
                .filter(c -> c instanceof AbstractWidget w && w.isActive() && w.getMessage().getString().equals(label))
                .map(c -> (AbstractWidget) c)
                .findFirst()
                .map(w -> new int[] { w.getX() + w.getWidth() / 2, w.getY() + w.getHeight() / 2 })
                .orElse(null));
        if (at == null) throw new AssertionError("no \"" + label + "\" control on the Sandboxes screen");
        moveTo(ctx, at);
        ctx.waitTicks(1);
        ctx.getInput().pressMouse(InputConstants.MOUSE_BUTTON_LEFT);   // SDL numbering in 26.x, not GLFW's 0
        ctx.waitTicks(2);
    }

    /** Moves the cursor to a point in GUI pixels (Fabric's cursor takes window coordinates). */
    private static void moveTo(ClientGameTestContext ctx, int[] gui) {
        double[] win = ctx.computeOnClient(mc -> {
            var w = mc.getWindow();
            return new double[] {
                    gui[0] * (double) w.getScreenWidth() / w.getGuiScaledWidth(),
                    gui[1] * (double) w.getScreenHeight() / w.getGuiScaledHeight() };
        });
        ctx.getInput().setCursorPos(win[0], win[1]);
    }
}
