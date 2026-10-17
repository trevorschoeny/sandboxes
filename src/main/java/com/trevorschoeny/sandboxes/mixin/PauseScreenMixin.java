package com.trevorschoeny.sandboxes.mixin;

import com.trevorschoeny.sandboxes.sandbox.CurrentWorld;
import com.trevorschoeny.sandboxes.sandbox.DiskJobs;
import com.trevorschoeny.sandboxes.sandbox.SandboxStore;
import com.trevorschoeny.sandboxes.sandbox.WorldSwitch;
import com.trevorschoeny.sandboxes.ui.SandboxesScreen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.SpriteIconButton;
import net.minecraft.client.gui.layouts.LayoutElement;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adds one square icon button to the pause menu's row of small buttons (bug
 * report, feedback, friends, player reporting): <b>Sandboxes</b> in a world,
 * <b>Return to &lt;world&gt;</b> inside a sandbox. The name shows as its tooltip.
 *
 * <p>The row is a horizontal layout that vanilla centres once it's full, so
 * adding the button to it before it goes into the grid is all the layout this
 * needs. MenuKit deliberately leaves the pause menu's own buttons alone, so
 * this mod owns the mixin.
 */
@Mixin(PauseScreen.class)
public abstract class PauseScreenMixin extends Screen {

    private static final Identifier SPRITE = Identifier.fromNamespaceAndPath("sandboxes", "pause");

    protected PauseScreenMixin() {
        super(Component.empty());
    }

    @Shadow
    private Button disconnectButton;

    /**
     * The icon row is the second thing added to the grid with its own cell
     * settings; the first is Back to Game.
     */
    @ModifyArg(method = "createPauseMenu",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/layouts/GridLayout$RowHelper;addChild(Lnet/minecraft/client/gui/layouts/LayoutElement;ILnet/minecraft/client/gui/layouts/LayoutSettings;)Lnet/minecraft/client/gui/layouts/LayoutElement;",
                    ordinal = 1),
            index = 0)
    private LayoutElement sandboxes$addToIconRow(LayoutElement row) {
        Minecraft minecraft = Minecraft.getInstance();
        String levelId = CurrentWorld.levelId();
        // Singleplayer only: a sandbox is a copy of a world folder this client owns.
        if (!(row instanceof LinearLayout icons) || minecraft.getSingleplayerServer() == null || levelId == null) {
            return row;
        }

        Screen self = (Screen) (Object) this;
        Component label;
        Button.OnPress press;
        if (SandboxStore.isSandbox(levelId)) {
            String parentId = SandboxStore.parentOf(levelId);
            label = Component.translatable("sandboxes.pause.return", parentId);
            press = b -> WorldSwitch.openLevel(parentId);
        } else {
            label = Component.translatable("sandboxes.pause.open");
            press = b -> minecraft.gui.setScreen(SandboxesScreen.forWorld(self, levelId));
        }
        SpriteIconButton ours = SpriteIconButton.builder(label, press, true)
                .width(20).sprite(SPRITE, 14, 15).withTootip().build();
        // Leaving closes the world and saves it again under a copy that's still
        // reading it, so no way out while one runs. DiskJobs re-lays this menu out
        // when the copy finishes, which turns it back on.
        ours.active = !DiskJobs.running();
        icons.addChild(ours);
        return row;
    }

    /** Save and Quit is off for the same reason while a copy runs. */
    @Inject(method = "createPauseMenu", at = @At("TAIL"))
    private void sandboxes$lockQuitWhileCopying(CallbackInfo ci) {
        if (this.disconnectButton != null && DiskJobs.running()) {
            this.disconnectButton.active = false;
        }
    }
}
