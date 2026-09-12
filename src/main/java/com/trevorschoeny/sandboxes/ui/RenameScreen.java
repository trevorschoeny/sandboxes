package com.trevorschoeny.sandboxes.ui;

import com.trevorschoeny.sandboxes.SandboxesClient;
import com.trevorschoeny.sandboxes.sandbox.Sandbox;
import com.trevorschoeny.sandboxes.sandbox.SandboxStore;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.io.IOException;

/**
 * Renames one sandbox. A plain screen with a text box, the way Edit World is a
 * plain screen with a text box, which also sidesteps typing inside a popup.
 */
public class RenameScreen extends Screen {

    private static final int MAX_NAME_LENGTH = 64;

    private final SandboxesScreen parent;
    private final Sandbox sandbox;
    private EditBox nameBox;

    public RenameScreen(SandboxesScreen parent, Sandbox sandbox) {
        super(Component.translatable("sandboxes.rename.title"));
        this.parent = parent;
        this.sandbox = sandbox;
    }

    @Override
    protected void init() {
        int centerX = width / 2;
        nameBox = new EditBox(font, centerX - 100, height / 2 - 20, 200, 20,
                Component.translatable("sandboxes.rename.field"));
        nameBox.setMaxLength(MAX_NAME_LENGTH);
        nameBox.setValue(sandbox.name());
        addRenderableWidget(nameBox);
        setInitialFocus(nameBox);

        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> save())
                .bounds(centerX - 100, height / 2 + 12, 98, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> onClose())
                .bounds(centerX + 2, height / 2 + 12, 98, 20).build());
    }

    private void save() {
        String newName = nameBox.getValue().trim();
        // An empty name would leave a blank row with nothing to click on, so it
        // falls back to the name the sandbox already had.
        if (!newName.isEmpty() && !newName.equals(sandbox.name())) {
            try {
                SandboxStore.rename(sandbox, newName);
            } catch (IOException e) {
                SandboxesClient.LOGGER.error("[sandboxes] couldn't rename {}", sandbox.id(), e);
            }
        }
        onClose();
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(parent);
        parent.refresh();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                                   float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        graphics.text(font, title, (width - font.width(title)) / 2, height / 2 - 44,
                0xFFFFFFFF, true);
    }
}
