package com.trevorschoeny.sandboxes.ui;

import com.trevorschoeny.sandboxes.SandboxesClient;
import com.trevorschoeny.sandboxes.config.SandboxesConfig;
import com.trevorschoeny.sandboxes.overlay.Overlay;
import com.trevorschoeny.sandboxes.overlay.OverlayKeys;
import com.trevorschoeny.sandboxes.sandbox.Sandbox;
import com.trevorschoeny.sandboxes.sandbox.SandboxStore;
import com.trevorschoeny.sandboxes.sandbox.WorldFiles;
import com.trevorschoeny.sandboxes.sandbox.WorldSwitch;
import com.trevorschoeny.keybindery.api.Chord;

import com.trevlar.menukit.api.dialog.ConfirmDialog;
import com.trevlar.menukit.api.element.Button;
import com.trevlar.menukit.api.element.Checkbox;
import com.trevlar.menukit.api.element.Flow;
import com.trevlar.menukit.api.element.PanelElement;
import com.trevlar.menukit.api.element.Tabs;
import com.trevlar.menukit.api.element.TextField;
import com.trevlar.menukit.api.element.TextLabel;
import com.trevlar.menukit.api.element.TextRole;
import com.trevlar.menukit.api.element.Toggle;
import com.trevlar.menukit.api.element.WidgetHost;
import com.trevlar.menukit.api.panel.MKScreen;
import com.trevlar.menukit.api.panel.OutsideRegion;
import com.trevlar.menukit.api.panel.Panel;
import com.trevlar.menukit.api.panel.PanelPosition;
import com.trevlar.menukit.api.panel.PanelStyle;
import com.trevlar.menukit.api.panel.ScreenKind;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The compact Sandboxes screen, built with MenuKit, to compare with the
 * vanilla-style list (Trev, 2026-10-15).
 *
 * <p>Every row carries its own controls, because players switch between worlds
 * from this menu far more often than they open a world from the game menu, so
 * one click beats select-then-click. Two tabs: Sandboxes, and Settings. Opened
 * with no world (from Mod Menu on the title screen) only Settings shows, since
 * sandboxes exist inside their world.
 *
 * <p>This is also the screen that goes into Mod Hub as a tab later; for now it
 * stands alone.
 *
 * ponytail: MenuKit freezes a panel's elements once built, so any change to the
 * rows (a sandbox made, renamed, synced, deleted) rebuilds the screen, keeping
 * the tab and scroll. MenuKit's coming list that changes while open replaces
 * this.
 */
public final class CompactSandboxesScreen extends MKScreen {

    private static final DateTimeFormatter JOINED_FORMAT = DateTimeFormatter.ofPattern("MMM d");

    // Screen state, kept across rebuilds (and across opens, which is fine: one screen at a time).
    private static String tab = "sandboxes";
    private static final Map<String, Double> SCROLL = new HashMap<>();
    /** The sandbox whose name is being edited, and the text so far. */
    private static @Nullable String editingId = null;
    private static String draftName = "";
    /** Rows doing disk work right now: sandbox id to what it's doing. */
    private static final Map<String, Component> BUSY = new ConcurrentHashMap<>();
    /** A sandbox being copied into existence (create or duplicate), shown as a placeholder row. */
    private static volatile @Nullable Component copying = null;
    /** Sizes, filled in off-thread; a row shows "…" until its size is known. */
    private static final Map<String, String> SIZES = new ConcurrentHashMap<>();
    /** What the confirmation dialog is asking about, or null when it's closed. */
    private static @Nullable Confirm confirm = null;

    private record Confirm(Sandbox sandbox, boolean delete) {}

    private final Screen parent;
    private final @Nullable String worldId;

    /**
     * @param worldId the world whose sandboxes to list, or null when there's no
     *                world (only the Settings tab shows)
     */
    public CompactSandboxesScreen(Screen parent, @Nullable String worldId, String openTab) {
        this(parent, worldId, openTab, worldId == null ? List.of() : SandboxStore.list(worldId));
    }

    private CompactSandboxesScreen(Screen parent, @Nullable String worldId, String openTab, List<Sandbox> sandboxes) {
        super(Component.translatable("sandboxes.compact.title"), ScreenKind.OPTIONS,
                panels(parent, worldId, openTab, sandboxes));
        this.parent = parent;
        this.worldId = worldId;
        setReturnAction(() -> Minecraft.getInstance().gui.setScreen(parent));
        measureSizes(sandboxes);
    }

    /** Rebuilds this screen in place, for after anything changed the rows. */
    private static void rebuild() {
        Minecraft mc = Minecraft.getInstance();
        if (!(mc.gui.screen() instanceof CompactSandboxesScreen current)) return;
        mc.gui.setScreen(new CompactSandboxesScreen(current.parent, current.worldId, tab));
    }

    // Panels

    private static List<Panel> panels(Screen parent, @Nullable String worldId, String openTab, List<Sandbox> sandboxes) {
        tab = worldId == null ? "settings" : openTab;

        Tabs tabs = Tabs.builder()
                .mode(Tabs.Mode.SIDE_SCROLL)
                .state(() -> tab, id -> tab = id)
                .bodyScroll(SCROLL::get, SCROLL::put)
                .tab(Tabs.tab("sandboxes")
                        .label(Component.translatable("sandboxes.compact.tab.sandboxes"))
                        .visibleWhen(() -> worldId != null)
                        .body(() -> sandboxesBody(worldId, sandboxes)))
                .tab(Tabs.tab("settings")
                        .label(Component.translatable("sandboxes.compact.tab.settings"))
                        .body(CompactSandboxesScreen::settingsBody))
                .build();

        Panel main = Panel.builder("sandboxes:compact")
                .style(PanelStyle.NONE)
                .position(PanelPosition.main())
                .add(tabs)
                .build();

        // ponytail: comparison only. The switch to the vanilla-style list goes
        // once Trev picks one.
        Flow.Builder footer = Flow.builder().gap(4);
        if (worldId != null) {
            footer.add(Button.builder().label(Component.translatable("sandboxes.compact.classic")).size(100, 20)
                    .onClick(() -> {
                        SandboxesScreens.preferCompact(false);
                        Minecraft.getInstance().gui.setScreen(new SandboxesScreen(parent, worldId));
                    }).build());
        }
        footer.add(Button.builder().label(CommonComponents.GUI_DONE).size(150, 20)
                .onClick(() -> Minecraft.getInstance().gui.setScreen(parent)).build());
        Panel done = Panel.builder("sandboxes:compact-done")
                .style(PanelStyle.NONE)
                .position(PanelPosition.region(OutsideRegion.BOTTOM_CENTER))
                .add(footer.build())
                .build();

        Panel dialog = ConfirmDialog.builder()
                .title(Component.translatable(confirm != null && confirm.delete()
                        ? "sandboxes.confirm.delete.title" : "sandboxes.confirm.sync.title"))
                .body(confirm == null ? Component.empty() : Component.translatable(confirm.delete()
                        ? "sandboxes.confirm.delete.body" : "sandboxes.confirm.sync.body", confirm.sandbox().name()))
                .onConfirm(CompactSandboxesScreen::confirmed)
                .onCancel(() -> {
                    confirm = null;
                    rebuild();
                })
                .build()
                .visibleWhen(() -> confirm != null);

        return List.of(main, done, dialog);
    }

    // The Sandboxes tab

    private static List<PanelElement> sandboxesBody(@Nullable String worldId, List<Sandbox> sandboxes) {
        List<PanelElement> body = new ArrayList<>();
        if (worldId == null) return body;
        int y = 0;
        if (sandboxes.isEmpty() && copying == null) {
            body.add(TextLabel.builder().text(Component.translatable("sandboxes.screen.empty"))
                    .role(TextRole.MUTED).at(0, y).build());
            y += 20;
        }
        for (Sandbox sandbox : sandboxes) {
            body.add(row(sandbox, y));
            y += 24;
        }
        Component inProgress = copying;
        if (inProgress != null) {
            body.add(TextLabel.builder().text(inProgress).role(TextRole.MUTED).at(0, y + 6).build());
            y += 24;
        }
        body.add(Button.builder().label(Component.translatable("sandboxes.compact.new")).size(140, 20)
                .disabledWhen(() -> copying != null)
                .onClick(() -> createIn(worldId))
                .at(0, y + 4).build());
        return body;
    }

    /** One sandbox on one line: who it is, then everything you can do to it. */
    private static PanelElement row(Sandbox sandbox, int y) {
        Flow.Builder row = Flow.builder().at(0, y).gap(4);
        Component busy = BUSY.get(sandbox.id());

        if (sandbox.id().equals(editingId)) {
            row.add(TextField.builder().size(140, 20).maxLength(64)
                    .state(() -> draftName, text -> draftName = text)
                    .onSubmit(text -> finishRename(sandbox))
                    .build());
        } else {
            row.add(TextLabel.builder().text(Component.literal(sandbox.name())).build());
        }
        row.add(TextLabel.builder().text(detail(sandbox)).role(TextRole.MUTED).build());
        row.add(Flow.spacer());

        if (busy != null) {
            // While it copies, the row says so and offers nothing that would collide.
            row.add(TextLabel.builder().text(busy).role(TextRole.MUTED).build());
            return row.build();
        }

        row.add(Checkbox.builder().label(Component.translatable("sandboxes.compact.overlay"))
                .state(() -> Overlay.showing(sandbox), on -> Overlay.toggleFor(sandbox))
                .build());
        if (OverlayKeys.available()) {
            row.add(WidgetHost.builder()
                    .widget(() -> OverlayKeys.button(0, 0, 50, 20, sandbox, chord -> setKey(sandbox, chord)))
                    .size(50, 20)
                    .build());
        }
        row.add(Button.builder().label(Component.translatable("sandboxes.button.join")).size(40, 20)
                .onClick(() -> WorldSwitch.enterSandbox(sandbox)).build());
        row.add(Button.builder().label(Component.translatable("sandboxes.button.sync")).size(40, 20)
                .onClick(() -> {
                    confirm = new Confirm(sandbox, false);
                    rebuild();
                }).build());
        boolean editing = sandbox.id().equals(editingId);
        row.add(Button.builder().label(Component.literal(editing ? "✓" : "✎")).size(20, 20)
                .tooltip(Component.translatable(editing ? "sandboxes.compact.rename.save" : "sandboxes.button.rename"))
                .onClick(() -> {
                    if (editing) {
                        finishRename(sandbox);
                    } else {
                        editingId = sandbox.id();
                        draftName = sandbox.name();
                        rebuild();
                    }
                }).build());
        row.add(Button.builder().label(Component.literal("❐")).size(20, 20)
                .tooltip(Component.translatable("sandboxes.button.duplicate"))
                .disabledWhen(() -> copying != null)
                .onClick(() -> duplicate(sandbox)).build());
        row.add(Button.builder().label(Component.literal("✕")).size(20, 20)
                .tooltip(Component.translatable("sandboxes.button.delete"))
                .onClick(() -> {
                    confirm = new Confirm(sandbox, true);
                    rebuild();
                }).build());
        return row.build();
    }

    /** "Oct 14 · 412 MB", or "never joined · 412 MB". */
    private static Component detail(Sandbox sandbox) {
        String size = SIZES.getOrDefault(sandbox.id(), "…");
        if (sandbox.lastJoined() == 0L) return Component.translatable("sandboxes.row.never", size);
        String when = LocalDateTime.ofInstant(Instant.ofEpochMilli(sandbox.lastJoined()), ZoneId.systemDefault())
                .format(JOINED_FORMAT);
        return Component.translatable("sandboxes.compact.detail", when, size);
    }

    // The Settings tab

    private static List<PanelElement> settingsBody() {
        return List.of(Flow.builder().at(0, 0)
                .add(TextLabel.builder().text(Component.translatable("sandboxes.settings.quick")).build())
                .add(Flow.spacer())
                .add(Toggle.builder().size(60, 20)
                        .state(SandboxesConfig::quickButton, SandboxesConfig::setQuickButton).build())
                .build(),
                TextLabel.builder().text(Component.translatable("sandboxes.settings.quick.desc"))
                        .role(TextRole.MUTED).at(0, 24).build());
    }

    // Actions

    private static void finishRename(Sandbox sandbox) {
        String name = draftName.trim();
        editingId = null;
        // An empty name would leave a row with nothing to read; keep the old one.
        if (!name.isEmpty() && !name.equals(sandbox.name())) {
            try {
                SandboxStore.rename(sandbox, name);
                OverlayKeys.refresh();   // the key's label names the sandbox
            } catch (IOException e) {
                SandboxesClient.LOGGER.error("[sandboxes] couldn't rename {}", sandbox.id(), e);
            }
        }
        rebuild();
    }

    private static void setKey(Sandbox sandbox, Chord chord) {
        try {
            SandboxStore.setOverlayKey(sandbox, chord.serialize());
        } catch (IOException e) {
            SandboxesClient.LOGGER.error("[sandboxes] couldn't save the overlay key for {}", sandbox.id(), e);
            return;
        }
        OverlayKeys.refresh();
        rebuild();
    }

    private static void confirmed() {
        Confirm pending = confirm;
        confirm = null;
        if (pending == null) return;
        Sandbox sandbox = pending.sandbox();
        Overlay.forget(sandbox);
        if (pending.delete()) {
            onDisk(sandbox.id(), Component.translatable("sandboxes.status.deleting"), () -> SandboxStore.delete(sandbox));
        } else {
            onDisk(sandbox.id(), Component.translatable("sandboxes.status.syncing"), () -> {
                WorldSwitch.saveCurrentWorld();
                SandboxStore.sync(sandbox);
            });
        }
    }

    private static void createIn(String worldId) {
        onDisk(null, Component.translatable("sandboxes.status.creating"), () -> {
            WorldSwitch.saveCurrentWorld();   // a copy only sees what's on disk
            SandboxStore.create(worldId);
        });
    }

    private static void duplicate(Sandbox sandbox) {
        onDisk(null, Component.translatable("sandboxes.status.duplicating"), () -> SandboxStore.duplicate(sandbox));
    }

    /**
     * Disk work off the client thread. With a sandbox id, that row shows the
     * message in place of its buttons; without one, a placeholder row stands
     * where the new sandbox will appear. The rest of the list stays usable.
     */
    private static void onDisk(@Nullable String sandboxId, Component message, DiskWork work) {
        if (sandboxId != null) BUSY.put(sandboxId, message);
        else copying = message;
        rebuild();
        Thread.ofPlatform().name("sandboxes-io").start(() -> {
            try {
                work.run();
            } catch (Exception e) {
                SandboxesClient.LOGGER.error("[sandboxes] {} failed", message.getString(), e);
            } finally {
                if (sandboxId != null) {
                    BUSY.remove(sandboxId);
                    SIZES.remove(sandboxId);
                } else {
                    copying = null;
                }
                Minecraft.getInstance().execute(() -> {
                    OverlayKeys.refresh();
                    rebuild();
                });
            }
        });
    }

    private interface DiskWork {
        void run() throws Exception;
    }

    /** Fills in sizes the rows don't know yet; a world folder can take a moment to walk. */
    private static void measureSizes(List<Sandbox> sandboxes) {
        List<Sandbox> unknown = sandboxes.stream().filter(s -> !SIZES.containsKey(s.id())).toList();
        if (unknown.isEmpty()) return;
        Thread.ofPlatform().name("sandboxes-sizes").start(() -> {
            for (Sandbox sandbox : unknown) SIZES.put(sandbox.id(), humanSize(WorldFiles.sizeOf(sandbox.dir())));
            Minecraft.getInstance().execute(CompactSandboxesScreen::rebuild);
        });
    }

    private static String humanSize(long bytes) {
        if (bytes >= 1024L * 1024L * 1024L) return String.format("%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0));
        return (bytes / (1024L * 1024L)) + " MB";
    }
}
