package com.trevorschoeny.creativesandbox.ui;

import com.trevorschoeny.creativesandbox.CreativeSandboxMod;
import com.trevorschoeny.creativesandbox.SandboxManager;
import com.trevorschoeny.creativesandbox.SandboxMetadata;
import com.trevorschoeny.menukit.MenuKit;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.util.List;

/**
 * Standalone screen for managing sandboxes.
 * Only shown from the main survival world — inside a sandbox, the button/keybind
 * returns directly to the parent world (no screen needed).
 */
public class SandboxScreen extends Screen {

    private final Screen parent;
    private final String parentWorldFolder;

    private SandboxListWidget listWidget;
    private Button openButton;
    private Button syncButton;
    private Button renameButton;
    private Button deleteButton;
    private Button newButton;
    private Button settingsButton;

    // Rename mode — inline edit field replaces the Rename button label
    private EditBox renameField;
    private boolean renamingMode = false;

    // Resync confirm mode — first click shows "Confirm", second click executes
    private boolean syncConfirmMode = false;

    // Delete confirm mode — first click shows "Confirm", second click executes
    private boolean deleteConfirmMode = false;

    // Busy state — shown while a background operation (create/sync/delete) is running
    private String busyMessage = null;

    public SandboxScreen(Screen parent, String parentWorldFolder) {
        super(Component.literal("Sandboxes"));
        this.parent = parent;
        this.parentWorldFolder = parentWorldFolder;
    }

    @Override
    protected void init() {
        int listTop    = 32;
        int listBottom = this.height - 64;

        listWidget = new SandboxListWidget(this.minecraft, this.width - 20, listBottom - listTop,
                listTop, 28, this::onSelectionChanged);
        listWidget.setX(10);
        this.addRenderableWidget(listWidget);
        refreshList();

        // ── Action buttons ────────────────────────────────────────────────
        int btnY   = this.height - 52;
        int btnW   = 90;
        int gap    = 5;
        int totalW = (btnW + gap) * 5 - gap;
        int startX = (this.width - totalW) / 2;

        newButton    = Button.builder(Component.literal("+ New"),    btn -> onNew())
                .bounds(startX,                    btnY, btnW, 20).build();
        openButton   = Button.builder(Component.literal("Open"),     btn -> onOpen())
                .bounds(startX + (btnW + gap),     btnY, btnW, 20).build();
        syncButton   = Button.builder(Component.literal("Re-sync"),  btn -> onSync())
                .bounds(startX + (btnW + gap) * 2, btnY, btnW, 20).build();
        renameButton = Button.builder(Component.literal("Rename"),   btn -> onRename())
                .bounds(startX + (btnW + gap) * 3, btnY, btnW, 20).build();
        deleteButton = Button.builder(Component.literal("Delete"),   btn -> onDelete())
                .bounds(startX + (btnW + gap) * 4, btnY, btnW, 20).build();

        // Inline rename field — hidden by default
        renameField = new EditBox(this.font, this.width / 2 - 120, this.height - 28,
                240, 18, Component.literal("Sandbox name"));
        renameField.setMaxLength(64);
        renameField.setVisible(false);

        this.addRenderableWidget(newButton);
        this.addRenderableWidget(openButton);
        this.addRenderableWidget(syncButton);
        this.addRenderableWidget(renameButton);
        this.addRenderableWidget(deleteButton);
        this.addRenderableWidget(renameField);

        // ── Settings button (top-right) ───────────────────────────────────
        // Opens the YACL config screen focused on the Sandbox tab
        settingsButton = Button.builder(
                Component.literal("\u2699 Settings"),
                btn -> {
                    Screen configScreen = MenuKit.family("trevmods")
                            .buildConfigScreen(this, CreativeSandboxMod.MOD_ID);
                    if (configScreen != null) this.minecraft.setScreen(configScreen);
                }
        ).bounds(this.width - 90, 6, 80, 20).build();
        this.addRenderableWidget(settingsButton);

        updateButtonStates();
    }

    private void refreshList() {
        SandboxMetadata selected = listWidget.getSelectedSandbox();
        List<SandboxMetadata> sandboxes = SandboxManager.get().getSandboxesForWorld(parentWorldFolder);
        listWidget.setSandboxes(sandboxes);
        if (selected != null) listWidget.selectById(selected.id);
        updateButtonStates();
    }

    private void onSelectionChanged(SandboxMetadata selected) {
        cancelInlineEditing();
        updateButtonStates();
    }

    private void updateButtonStates() {
        SandboxMetadata selected = listWidget != null ? listWidget.getSelectedSandbox() : null;
        boolean has = selected != null;
        boolean busy = busyMessage != null;

        // While a background op is running, disable everything
        if (newButton    != null) newButton.active    = !busy;
        if (openButton   != null) openButton.active   = has && !busy;
        if (syncButton   != null) syncButton.active   = has && !busy;
        if (renameButton != null) renameButton.active = has && !busy;
        if (deleteButton != null) deleteButton.active = has && !busy;
    }

    // ── Busy state ──────────────────────────────────────────────────────────

    private void setBusy(String message) {
        busyMessage = message;
        updateButtonStates();
    }

    private void clearBusy() {
        busyMessage = null;
        refreshList();
    }

    /** Cancel any active inline editing (rename, sync confirm, delete confirm). */
    private void cancelInlineEditing() {
        renamingMode = false;
        renameField.setVisible(false);
        if (renameButton != null) renameButton.setMessage(Component.literal("Rename"));

        syncConfirmMode = false;
        if (syncButton != null) syncButton.setMessage(Component.literal("Re-sync"));

        deleteConfirmMode = false;
        if (deleteButton != null) deleteButton.setMessage(Component.literal("Delete"));
    }

    // ── New sandbox ──────────────────────────────────────────────────────────

    private void onNew() {
        cancelInlineEditing();
        doCreateSandbox();
    }

    private void doCreateSandbox() {
        setBusy("Creating sandbox...");
        new Thread(() -> {
            try {
                SandboxManager.get().createSandbox(parentWorldFolder);
            } catch (IOException e) {
                CreativeSandboxMod.LOGGER.error("[Sandboxes] Failed to create sandbox", e);
            }
            this.minecraft.execute(this::clearBusy);
        }, "sandboxes-create").start();
    }

    // ── Open ─────────────────────────────────────────────────────────────────

    private void onOpen() {
        SandboxMetadata selected = listWidget.getSelectedSandbox();
        if (selected == null) return;
        SandboxManager.disconnectAndOpen(selected.folderName());
    }

    // ── Resync ───────────────────────────────────────────────────────────────

    private void onSync() {
        SandboxMetadata selected = listWidget.getSelectedSandbox();
        if (selected == null) return;

        if (!syncConfirmMode) {
            // First click — show confirmation label
            cancelInlineEditing();
            syncConfirmMode = true;
            syncButton.setMessage(Component.literal("Confirm"));
        } else {
            // Second click — execute resync
            syncConfirmMode = false;
            syncButton.setMessage(Component.literal("Re-sync"));
            doSync(selected);
        }
    }

    private void doSync(SandboxMetadata meta) {
        setBusy("Resyncing...");
        new Thread(() -> {
            try {
                SandboxManager.get().syncSandbox(meta, parentWorldFolder);
            } catch (IOException e) {
                CreativeSandboxMod.LOGGER.error("[Sandboxes] Failed to sync sandbox", e);
            }
            this.minecraft.execute(this::clearBusy);
        }, "sandboxes-sync").start();
    }

    // ── Rename ───────────────────────────────────────────────────────────────

    private void onRename() {
        SandboxMetadata selected = listWidget.getSelectedSandbox();
        if (selected == null) return;

        if (!renamingMode) {
            cancelInlineEditing();
            renamingMode = true;
            renameField.setVisible(true);
            renameField.setValue(selected.name);
            renameField.setFocused(true);
            renameButton.setMessage(Component.literal("Confirm"));
        } else {
            String newName = renameField.getValue().trim();
            if (!newName.isEmpty()) SandboxManager.get().renameSandbox(selected, newName);
            renamingMode = false;
            renameField.setVisible(false);
            renameButton.setMessage(Component.literal("Rename"));
            refreshList();
        }
    }

    // ── Delete ───────────────────────────────────────────────────────────────

    private void onDelete() {
        SandboxMetadata selected = listWidget.getSelectedSandbox();
        if (selected == null) return;

        if (!deleteConfirmMode) {
            cancelInlineEditing();
            deleteConfirmMode = true;
            deleteButton.setMessage(Component.literal("Confirm"));
        } else {
            deleteConfirmMode = false;
            deleteButton.setMessage(Component.literal("Delete"));
            doDelete(selected);
        }
    }

    private void doDelete(SandboxMetadata meta) {
        setBusy("Deleting...");
        new Thread(() -> {
            try {
                SandboxManager.get().deleteSandbox(meta);
            } catch (IOException e) {
                CreativeSandboxMod.LOGGER.error("[Sandboxes] Failed to delete sandbox", e);
            }
            this.minecraft.execute(this::clearBusy);
        }, "sandboxes-delete").start();
    }

    // ── Rendering ────────────────────────────────────────────────────────────

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        super.render(graphics, mouseX, mouseY, delta);

        // Title + world context
        graphics.drawCenteredString(this.font, "Sandboxes — " + parentWorldFolder,
                this.width / 2, 12, 0xFFFFFFFF);

        // Busy indicator — centered below title
        if (busyMessage != null) {
            graphics.drawCenteredString(this.font, busyMessage, this.width / 2, 22, 0xFFFFAA00);
        }
    }

    // ── Input handling ───────────────────────────────────────────────────────

    @Override
    public boolean keyPressed(KeyEvent event) {
        int keyCode = event.key();
        if (keyCode == 256) { // ESC
            if (renamingMode || syncConfirmMode || deleteConfirmMode) {
                cancelInlineEditing();
                return true;
            }
            this.minecraft.setScreen(parent);
            return true;
        }
        // Enter confirms rename
        if (keyCode == 257) {
            if (renamingMode) { onRename(); return true; }
        }
        return super.keyPressed(event);
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(parent);
    }
}
