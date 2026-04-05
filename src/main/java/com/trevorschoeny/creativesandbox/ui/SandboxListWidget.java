package com.trevorschoeny.creativesandbox.ui;

import com.trevorschoeny.creativesandbox.SandboxMetadata;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.function.Consumer;

public class SandboxListWidget extends ObjectSelectionList<SandboxListWidget.SandboxEntry> {

    private final Consumer<SandboxMetadata> onSelectionChanged;

    public SandboxListWidget(Minecraft mc, int width, int height, int y, int itemHeight,
                              Consumer<SandboxMetadata> onSelectionChanged) {
        super(mc, width, height, y, itemHeight);
        this.onSelectionChanged = onSelectionChanged;
    }

    public void setSandboxes(List<SandboxMetadata> sandboxes) {
        this.clearEntries();
        for (SandboxMetadata meta : sandboxes) {
            this.addEntry(new SandboxEntry(meta));
        }
    }

    /** Use instead of getSelected() to avoid return type conflict with parent. */
    public SandboxMetadata getSelectedSandbox() {
        SandboxEntry entry = super.getSelected();
        return entry != null ? entry.meta : null;
    }

    public void selectById(String id) {
        this.children().stream()
                .filter(e -> e.meta.id.equals(id))
                .findFirst()
                .ifPresent(entry -> {
                    this.setSelected(entry);
                    onSelectionChanged.accept(entry.meta);
                });
    }

    public class SandboxEntry extends ObjectSelectionList.Entry<SandboxEntry> {
        final SandboxMetadata meta;

        SandboxEntry(SandboxMetadata meta) {
            this.meta = meta;
        }

        @Override
        public Component getNarration() {
            return Component.literal(meta.name);
        }

        @Override
        public void renderContent(GuiGraphics graphics, int mouseX, int mouseY,
                                  boolean hovered, float delta) {
            int x = this.getContentX();
            int y = this.getContentY();
            int w = this.getContentWidth();

            // Semi-transparent background per entry
            graphics.fill(x, y, x + w, y + 24, hovered ? 0x50FFFFFF : 0x50000000);

            // Colors must include full alpha (0xFF prefix) — drawString silently
            // discards text when ARGB.alpha(color) == 0, so 0xFFFFFF is invisible!
            graphics.drawString(SandboxListWidget.this.minecraft.font,
                    meta.name, x + 4, y + 2, 0xFFFFFFFF);
            graphics.drawString(SandboxListWidget.this.minecraft.font,
                    "Synced: " + meta.formattedLastSynced(),
                    x + 4, y + 13, 0xFFAAAAAA);
        }

        @Override
        public boolean mouseClicked(MouseButtonEvent event, boolean bl) {
            SandboxListWidget.this.setSelected(this);
            onSelectionChanged.accept(meta);
            return true;
        }
    }
}
