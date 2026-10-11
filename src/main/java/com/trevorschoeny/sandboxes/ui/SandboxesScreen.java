package com.trevorschoeny.sandboxes.ui;

import com.trevorschoeny.sandboxes.SandboxesClient;
import com.trevorschoeny.sandboxes.config.SandboxesConfigScreen;
import com.trevorschoeny.sandboxes.sandbox.Sandbox;
import com.trevorschoeny.sandboxes.sandbox.SandboxStore;
import com.trevorschoeny.sandboxes.sandbox.WorldFiles;
import com.trevorschoeny.sandboxes.sandbox.WorldSwitch;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * The sandbox list for one world, with the actions underneath.
 *
 * <p>Shaped after vanilla's world select on purpose: select a row, then use the
 * buttons. Anything that touches the disk runs on its own thread, because
 * copying a world takes long enough to freeze the frame on a filesystem that
 * can't clone.
 */
public class SandboxesScreen extends Screen {

    private static final DateTimeFormatter JOINED_FORMAT =
            DateTimeFormatter.ofPattern("MMM d, HH:mm");

    private static final int ROW_HEIGHT = 32;
    private static final int LIST_TOP = 32;
    private static final int FOOTER_HEIGHT = 64;

    private final Screen parent;
    private final String worldId;

    private SandboxList list;
    private Button joinButton;
    private Button syncButton;
    private Button renameButton;
    private Button deleteButton;
    private Button createButton;

    /** Set while a copy or delete is running: the buttons stay off until it lands. */
    private volatile boolean busy = false;
    /** What to tell the player while busy, or null. */
    private volatile @Nullable Component status = null;

    /**
     * A screen to open on the next tick rather than inside a button's press.
     *
     * <p>Vanilla fires a button on mouse-press, so a screen opened from inside
     * that call is already up when the matching release arrives, and the release
     * lands on whatever sits under the cursor on the new screen. That is what
     * made v1's Settings button appear to do nothing. Opening a tick later means
     * the release lands here, where it belongs.
     */
    private @Nullable Screen pendingScreen = null;

    public SandboxesScreen(Screen parent, String worldId) {
        super(Component.translatable("sandboxes.screen.title", worldId));
        this.parent = parent;
        this.worldId = worldId;
    }

    @Override
    protected void init() {
        list = new SandboxList(this, minecraft, width, height - LIST_TOP - FOOTER_HEIGHT,
                LIST_TOP, ROW_HEIGHT);
        addRenderableWidget(list);

        int centerX = width / 2;
        int actionsY = height - FOOTER_HEIGHT + 8;
        int actionWidth = 72;
        int gap = 4;
        int actionsLeft = centerX - (actionWidth * 4 + gap * 3) / 2;

        joinButton = addRenderableWidget(Button.builder(
                        Component.translatable("sandboxes.button.join"), b -> withSelected(this::join))
                .bounds(actionsLeft, actionsY, actionWidth, 20).build());
        syncButton = addRenderableWidget(Button.builder(
                        Component.translatable("sandboxes.button.sync"), b -> withSelected(this::confirmSync))
                .bounds(actionsLeft + actionWidth + gap, actionsY, actionWidth, 20).build());
        renameButton = addRenderableWidget(Button.builder(
                        Component.translatable("sandboxes.button.rename"), b -> withSelected(this::rename))
                .bounds(actionsLeft + (actionWidth + gap) * 2, actionsY, actionWidth, 20).build());
        deleteButton = addRenderableWidget(Button.builder(
                        Component.translatable("sandboxes.button.delete"), b -> withSelected(this::confirmDelete))
                .bounds(actionsLeft + (actionWidth + gap) * 3, actionsY, actionWidth, 20).build());

        int bottomY = actionsY + 24;
        createButton = addRenderableWidget(Button.builder(
                        Component.translatable("sandboxes.button.create"), b -> create())
                .bounds(centerX - 154, bottomY, 150, 20).build());
        addRenderableWidget(Button.builder(
                        Component.translatable("sandboxes.button.settings"),
                        b -> pendingScreen = SandboxesConfigScreen.create(this))
                .bounds(centerX + 4, bottomY, 72, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
                .bounds(centerX + 80, bottomY, 74, 20).build());

        refresh();
        updateButtons();
    }

    // Loading the list

    /**
     * Rebuilds the rows. The listing and the per-sandbox size both read the
     * disk, so they run off-thread and the rows are handed back to the client
     * thread once they're ready.
     */
    public void refresh() {
        Thread.ofPlatform().name("sandboxes-list").start(() -> {
            List<Sandbox> sandboxes = SandboxStore.list(worldId);
            List<Component> details = new ArrayList<>(sandboxes.size());
            for (Sandbox sandbox : sandboxes) {
                details.add(detailFor(sandbox));
            }
            minecraft.execute(() -> {
                // The player may have left this screen while the disk was read.
                if (minecraft.gui.screen() != this || list == null) return;
                List<SandboxList.Row> rows = new ArrayList<>(sandboxes.size());
                for (int i = 0; i < sandboxes.size(); i++) {
                    rows.add(list.new Row(sandboxes.get(i), details.get(i)));
                }
                list.setRows(rows);
                updateButtons();
            });
        });
    }

    /** "joined Sep 11, 14:02 · 412 MB", or that it has never been joined. */
    private Component detailFor(Sandbox sandbox) {
        String size = humanSize(WorldFiles.sizeOf(sandbox.dir()));
        if (sandbox.lastJoined() == 0L) {
            return Component.translatable("sandboxes.row.never", size);
        }
        String when = LocalDateTime.ofInstant(
                        Instant.ofEpochMilli(sandbox.lastJoined()), ZoneId.systemDefault())
                .format(JOINED_FORMAT);
        return Component.translatable("sandboxes.row.joined", when, size);
    }

    private static String humanSize(long bytes) {
        if (bytes >= 1024L * 1024L * 1024L) {
            return String.format("%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0));
        }
        return (bytes / (1024L * 1024L)) + " MB";
    }

    // Actions

    private void withSelected(java.util.function.Consumer<Sandbox> action) {
        Sandbox selected = list == null ? null : list.selected();
        if (selected != null && !busy) action.accept(selected);
    }

    /** Joins a sandbox. WorldSwitch owns the arrival: stamp the join, save, open. */
    public void join(Sandbox sandbox) {
        if (busy) return;
        WorldSwitch.enterSandbox(sandbox);
    }

    private void create() {
        if (busy) return;
        runOnDisk(Component.translatable("sandboxes.status.creating"), () -> {
            // Flush the world first: a copy only sees what's on disk.
            WorldSwitch.saveCurrentWorld();
            SandboxStore.create(worldId);
        });
    }

    private void confirmSync(Sandbox sandbox) {
        // Sync throws away everything built in the sandbox, so it asks first.
        minecraft.gui.setScreen(new ConfirmScreen(
                confirmed -> {
                    minecraft.gui.setScreen(this);
                    if (confirmed) {
                        runOnDisk(Component.translatable("sandboxes.status.syncing"), () -> {
                            WorldSwitch.saveCurrentWorld();
                            SandboxStore.sync(sandbox);
                        });
                    }
                },
                Component.translatable("sandboxes.confirm.sync.title"),
                Component.translatable("sandboxes.confirm.sync.body", sandbox.name())));
    }

    private void confirmDelete(Sandbox sandbox) {
        minecraft.gui.setScreen(new ConfirmScreen(
                confirmed -> {
                    minecraft.gui.setScreen(this);
                    if (confirmed) {
                        runOnDisk(Component.translatable("sandboxes.status.deleting"),
                                () -> SandboxStore.delete(sandbox));
                    }
                },
                Component.translatable("sandboxes.confirm.delete.title"),
                Component.translatable("sandboxes.confirm.delete.body", sandbox.name())));
    }

    private void rename(Sandbox sandbox) {
        pendingScreen = new RenameScreen(this, sandbox);
    }

    /** Anything that writes to the disk: off-thread, with the buttons off and a message up. */
    private void runOnDisk(Component message, DiskWork work) {
        busy = true;
        status = message;
        updateButtons();
        Thread.ofPlatform().name("sandboxes-io").start(() -> {
            try {
                work.run();
            } catch (Exception e) {
                SandboxesClient.LOGGER.error("[sandboxes] {} failed", message.getString(), e);
            } finally {
                busy = false;
                status = null;
                minecraft.execute(() -> {
                    if (minecraft.gui.screen() == this) refresh();
                });
            }
        });
    }

    private interface DiskWork {
        void run() throws Exception;
    }

    // Screen plumbing

    private void updateButtons() {
        boolean hasSelection = list != null && list.selected() != null;
        boolean enabled = hasSelection && !busy;
        if (joinButton != null) joinButton.active = enabled;
        if (syncButton != null) syncButton.active = enabled;
        if (renameButton != null) renameButton.active = enabled;
        if (deleteButton != null) deleteButton.active = enabled;
        if (createButton != null) createButton.active = !busy;
    }

    @Override
    public void tick() {
        super.tick();
        updateButtons();
        if (pendingScreen != null) {
            Screen next = pendingScreen;
            pendingScreen = null;
            minecraft.gui.setScreen(next);
        }
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(parent);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                                   float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        graphics.text(font, title, (width - font.width(title)) / 2, 12, 0xFFFFFFFF, true);

        Component message = status;
        if (message != null) {
            graphics.text(font, message, (width - font.width(message)) / 2,
                    height - FOOTER_HEIGHT - 14, 0xFFFFFF55, true);
        } else if (list != null && list.children().isEmpty()) {
            Component empty = Component.translatable("sandboxes.screen.empty");
            graphics.text(font, empty, (width - font.width(empty)) / 2, height / 2 - 8,
                    0xFFA0A0A0, true);
        }
    }
}
