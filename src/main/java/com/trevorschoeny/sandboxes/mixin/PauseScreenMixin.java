package com.trevorschoeny.sandboxes.mixin;

import com.trevorschoeny.sandboxes.sandbox.CurrentWorld;
import com.trevorschoeny.sandboxes.sandbox.SandboxStore;
import com.trevorschoeny.sandboxes.sandbox.WorldSwitch;
import com.trevorschoeny.sandboxes.ui.SandboxesScreens;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Puts one button into the pause menu's own column, directly above Save and
 * Quit to Title: <b>Sandboxes</b> in a world, <b>Return to &lt;world&gt;</b>
 * inside a sandbox.
 *
 * <p>MenuKit deliberately leaves the pause menu's button column alone, so this
 * mod owns the mixin. Vanilla's own Save and Quit
 * button is a field, so this finds it without matching any text, then moves it
 * and everything below it down one row to make space.
 */
@Mixin(PauseScreen.class)
public abstract class PauseScreenMixin extends Screen {

    /** Row pitch in vanilla's pause layout: a 20px button plus its 4px gap. */
    private static final int ROW_PITCH = 24;

    protected PauseScreenMixin() {
        super(Component.empty());
    }

    @Shadow
    private Button disconnectButton;

    @Inject(method = "createPauseMenu", at = @At("TAIL"))
    private void sandboxes$addSandboxesButton(CallbackInfo ci) {
        Minecraft minecraft = Minecraft.getInstance();

        // Singleplayer only: a sandbox is a copy of a world folder this client owns.
        if (minecraft.getSingleplayerServer() == null) return;

        String levelId = CurrentWorld.levelId();
        Button quitButton = this.disconnectButton;
        if (levelId == null || quitButton == null) return;

        int x = quitButton.getX();
        int y = quitButton.getY();
        int width = quitButton.getWidth();

        // Push Save and Quit, and anything laid out below it, down one row.
        for (var child : this.children()) {
            if (child instanceof AbstractWidget widget && widget.getY() >= y) {
                widget.setY(widget.getY() + ROW_PITCH);
            }
        }

        Screen self = (Screen) (Object) this;
        Button ours;
        if (SandboxStore.isSandbox(levelId)) {
            String parentId = SandboxStore.parentOf(levelId);
            // Long world names are left to vanilla's button label, which scrolls
            // a label too wide for its button rather than clipping it.
            ours = Button.builder(
                            Component.translatable("sandboxes.pause.return", parentId),
                            b -> WorldSwitch.openLevel(parentId))
                    .bounds(x, y, width, 20).build();
        } else {
            ours = Button.builder(
                            Component.translatable("sandboxes.pause.open"),
                            b -> minecraft.gui.setScreen(SandboxesScreens.sandboxes(self, levelId)))
                    .bounds(x, y, width, 20).build();
        }
        addRenderableWidget(ours);
    }
}
