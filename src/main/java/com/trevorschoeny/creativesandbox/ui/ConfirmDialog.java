package com.trevorschoeny.creativesandbox.ui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Simple modal confirmation dialog. Shows a title, a message line,
 * and Confirm / Cancel buttons. Cancel returns to the parent screen.
 */
public class ConfirmDialog extends Screen {

    private final Screen parent;
    private final Component message;
    private final Runnable onConfirm;

    public ConfirmDialog(Screen parent, Component title, Component message, Runnable onConfirm) {
        super(title);
        this.parent = parent;
        this.message = message;
        this.onConfirm = onConfirm;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int btnY = this.height / 2 + 20;
        int btnW = 100;
        int gap = 8;

        // Confirm button — runs the action and returns to parent
        this.addRenderableWidget(Button.builder(
                Component.literal("Confirm"),
                btn -> {
                    onConfirm.run();
                    this.minecraft.setScreen(parent);
                }
        ).bounds(centerX - btnW - gap / 2, btnY, btnW, 20).build());

        // Cancel button — just returns to parent
        this.addRenderableWidget(Button.builder(
                Component.literal("Cancel"),
                btn -> this.minecraft.setScreen(parent)
        ).bounds(centerX + gap / 2, btnY, btnW, 20).build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        super.render(graphics, mouseX, mouseY, delta);
        // Title
        graphics.drawCenteredString(this.font, this.title, this.width / 2, this.height / 2 - 30, 0xFFFFFFFF);
        // Message
        graphics.drawCenteredString(this.font, this.message, this.width / 2, this.height / 2 - 10, 0xFFAAAAAA);
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(parent);
    }
}
