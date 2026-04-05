package com.trevorschoeny.creativesandbox.mixin;

import com.trevorschoeny.creativesandbox.CreativeSandbox;
import com.trevorschoeny.creativesandbox.SandboxManager;
import com.trevorschoeny.creativesandbox.ui.SandboxScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adds a "Return to World: [name]" button to the pause menu when the player
 * is inside a sandbox. Positioned above the "Save and Quit to Title" button.
 * Long world names are truncated with ellipsis to fit the button width.
 */
@Mixin(PauseScreen.class)
public abstract class PauseScreenMixin extends Screen {

    protected PauseScreenMixin() {
        super(Component.empty());
    }

    @Inject(method = "createPauseMenu", at = @At("TAIL"))
    private void creativeSandbox$addSandboxButtons(CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        IntegratedServer server = mc.getSingleplayerServer();
        if (server == null) return;

        String worldFolder = ((MinecraftServerAccessor) server)
                .getStorageSource().getLevelId();
        String parentWorld = SandboxManager.get().getParentWorldId(worldFolder);
        boolean inSandbox = parentWorld != null;

        // Find the "Save and Quit to Title" button by its translatable text
        String quitText = Component.translatable("menu.returnToMenu").getString();
        Button quitButton = null;

        for (var child : this.children()) {
            if (child instanceof Button btn && btn.getMessage().getString().equals(quitText)) {
                quitButton = btn;
                break;
            }
        }

        if (quitButton == null) return;

        int quitY = quitButton.getY();
        int btnX = quitButton.getX();
        int btnW = quitButton.getWidth();

        if (inSandbox) {
            // ── Inside sandbox: "Return to World: [name]" + "Sandboxes" ──────
            // Build the return label, truncated if too long
            String prefix = "Return to World: ";
            int maxTextWidth = btnW - 16;
            String fullLabel = prefix + parentWorld;
            String returnLabel;
            if (mc.font.width(fullLabel) > maxTextWidth) {
                String ellipsis = "...";
                int prefixWidth = mc.font.width(prefix + ellipsis);
                int availableForName = maxTextWidth - prefixWidth;
                String trimmedName = parentWorld;
                while (mc.font.width(trimmedName) > availableForName && !trimmedName.isEmpty()) {
                    trimmedName = trimmedName.substring(0, trimmedName.length() - 1);
                }
                returnLabel = prefix + trimmedName + ellipsis;
            } else {
                returnLabel = fullLabel;
            }

            // Shift quit button (and below) down by 24px to make room
            for (var child : this.children()) {
                if (child instanceof AbstractWidget w && w.getY() >= quitY) {
                    w.setY(w.getY() + 24);
                }
            }

            // Insert "Return to World" where the quit button was
            this.addRenderableWidget(Button.builder(
                    Component.literal(returnLabel),
                    btn -> SandboxManager.disconnectAndOpen(parentWorld)
            ).bounds(btnX, quitY, btnW, 20).build());
        } else {
            // ── Main world: "Sandboxes" button ───────────────────────────────
            // Shift quit button (and below) down by 24px
            for (var child : this.children()) {
                if (child instanceof AbstractWidget w && w.getY() >= quitY) {
                    w.setY(w.getY() + 24);
                }
            }

            // Insert "Sandboxes" where the quit button was
            this.addRenderableWidget(Button.builder(
                    Component.literal("Sandboxes"),
                    btn -> mc.setScreen(new SandboxScreen(
                            (Screen) (Object) this, worldFolder))
            ).bounds(btnX, quitY, btnW, 20).build());
        }
    }

    /**
     * Renders "SANDBOX MODE" in red at the top of the pause menu
     * when the player is inside a sandbox world.
     */
    @Inject(method = "render", at = @At("RETURN"))
    private void creativeSandbox$renderSandboxLabel(GuiGraphics graphics, int mouseX,
                                               int mouseY, float delta, CallbackInfo ci) {
        if (!CreativeSandbox.isInSandbox()) return;
        graphics.drawCenteredString(this.font, "SANDBOX MODE",
                this.width / 2, 6, 0xFFFF4444);
    }
}
