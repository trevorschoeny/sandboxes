package com.trevorschoeny.sandboxes.ui;

import com.trevorschoeny.sandboxes.sandbox.Sandbox;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * The sandbox list, shaped like vanilla's world-select list: rows are selected,
 * and the buttons underneath act on the selection. Double-clicking a row joins
 * it, as double-clicking a world opens it.
 */
public class SandboxList extends ObjectSelectionList<SandboxList.Row> {

    private final SandboxesScreen screen;

    public SandboxList(SandboxesScreen screen, Minecraft minecraft,
                       int width, int height, int y, int itemHeight) {
        super(minecraft, width, height, y, itemHeight);
        this.screen = screen;
    }

    /** Replaces every row. Rows are built off-thread, so this only swaps them in. */
    public void setRows(List<Row> rows) {
        replaceEntries(rows);
    }

    /** The selected sandbox, or null when nothing is selected. */
    public Sandbox selected() {
        Row row = getSelected();
        return row == null ? null : row.sandbox;
    }

    /** One sandbox: name on top, when it was last joined and how big it is underneath. */
    public class Row extends ObjectSelectionList.Entry<Row> {

        final Sandbox sandbox;
        private final Component name;
        private final Component detail;

        public Row(Sandbox sandbox, Component detail) {
            this.sandbox = sandbox;
            this.name = Component.literal(sandbox.name());
            this.detail = detail;
        }

        @Override
        public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                                   boolean hovered, float partialTick) {
            int x = getContentX();
            int y = getContentY();
            graphics.text(minecraft.font, name, x, y + 2, 0xFFFFFFFF, true);
            graphics.text(minecraft.font, detail, x, y + 14, 0xFFA0A0A0, true);
        }

        @Override
        public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
            setSelected(this);
            if (doubleClick) {
                screen.join(sandbox);
                return true;
            }
            return super.mouseClicked(event, doubleClick);
        }

        @Override
        public Component getNarration() {
            return name;
        }
    }
}
