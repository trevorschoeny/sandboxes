package com.trevorschoeny.sandboxes.ui;

import com.trevorschoeny.sandboxes.SandboxesClient;
import com.trevorschoeny.sandboxes.config.SandboxesConfig;
import com.trevorschoeny.sandboxes.overlay.Overlay;
import com.trevorschoeny.sandboxes.overlay.OverlayKeys;
import com.trevorschoeny.sandboxes.sandbox.CurrentWorld;
import com.trevorschoeny.sandboxes.sandbox.DiskJobs;
import com.trevorschoeny.sandboxes.sandbox.Sandbox;
import com.trevorschoeny.sandboxes.sandbox.SandboxStore;
import com.trevorschoeny.sandboxes.sandbox.WorldFiles;
import com.trevorschoeny.sandboxes.sandbox.WorldSwitch;
import com.trevorschoeny.keybindery.api.Chord;

import com.trevlar.menukit.api.dialog.ConfirmDialog;
import com.trevlar.menukit.api.element.Button;
import com.trevlar.menukit.api.element.Flow;
import com.trevlar.menukit.api.element.Icon;
import com.trevlar.menukit.api.element.ListView;
import com.trevlar.menukit.api.element.PanelElement;
import com.trevlar.menukit.api.element.ScrollContainer;
import com.trevlar.menukit.api.element.Tabs;
import com.trevlar.menukit.api.element.TextField;
import com.trevlar.menukit.api.element.TextLabel;
import com.trevlar.menukit.api.element.TextRole;
import com.trevlar.menukit.api.element.Toggle;
import com.trevlar.menukit.api.element.WidgetHost;
import com.trevlar.menukit.api.layout.CrossAlign;
import com.trevlar.menukit.api.panel.MKScreen;
import com.trevlar.menukit.api.panel.OutsideRegion;
import com.trevlar.menukit.api.panel.Panel;
import com.trevlar.menukit.api.panel.PanelPosition;
import com.trevlar.menukit.api.panel.PanelStyle;
import com.trevlar.menukit.api.panel.ScreenKind;

import com.mojang.blaze3d.platform.NativeImage;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.FaviconTexture;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * The Sandboxes screen, built with MenuKit: one card per sandbox.
 *
 * <p>Every row carries its own controls, because players switch between worlds
 * from this menu far more often than they open a world from the game menu, so
 * one click beats select-then-click. Two tabs: Sandboxes, and Settings. Opened
 * with no world (from Mod Menu on the title screen) only Settings shows, since
 * sandboxes exist inside their world.
 *
 * <p>The cards are a MenuKit {@code ListView} keyed by sandbox id. Each card is
 * built once and reads what can change (name, size, key warning, busy line) by
 * id, so a sandbox made, renamed, synced or deleted adds, changes or removes its
 * card in place and the screen is never rebuilt.
 */
public final class SandboxesScreen extends MKScreen {

    private static final DateTimeFormatter JOINED_FORMAT = DateTimeFormatter.ofPattern("MMM d");

    // Screen state, static because there's one screen at a time.
    private static String tab = "sandboxes";
    private static final Map<String, Double> SCROLL = new HashMap<>();
    /** The world listed, and its sandboxes as last read from disk (newest joined first). */
    private static @Nullable String listedWorld = null;
    private static List<Sandbox> listed = List.of();
    /** Each sandbox's key warning, worked out with the list rather than every frame. */
    private static final Map<String, Component> CONFLICTS = new HashMap<>();
    /** The sandbox whose name is being edited, and the text so far. */
    private static @Nullable String editingId = null;
    private static String draftName = "";
    /** Sizes, filled in off-thread; a row shows "…" until its size is known. */
    private static final Map<String, String> SIZES = new ConcurrentHashMap<>();
    /** What the confirmation dialog is asking about, or null when it's closed. */
    private static @Nullable Confirm confirm = null;
    /** World images by sandbox id, loaded on first draw (client thread only). */
    private static final Map<String, FaviconTexture> ICONS = new HashMap<>();

    private record Confirm(Sandbox sandbox, boolean delete) {}

    /**
     * @param worldId the world whose sandboxes to list, or null when there's no
     *                world (only the Settings tab shows)
     */
    public SandboxesScreen(Screen parent, @Nullable String worldId, String openTab) {
        super(Component.translatable("sandboxes.compact.title"), ScreenKind.DEFAULT,
                panels(parent, worldId, openTab));
        setReturnAction(() -> Minecraft.getInstance().gui.setScreen(parent));
    }

    /** A world's sandbox list. */
    public static Screen forWorld(Screen parent, String worldId) {
        return new SandboxesScreen(parent, worldId, "sandboxes");
    }

    /** Sandboxes' settings, the Settings tab. Without a world (the title screen's Mod Menu) it's the only tab. */
    public static Screen settings(Screen parent) {
        return new SandboxesScreen(parent, CurrentWorld.hostWorldId(), "settings");
    }

    /**
     * Reads the world's sandboxes from disk again, for after anything changed
     * them. The list reads {@link #listed} every frame, so this is all a change
     * takes: cards come, go and update on the next frame.
     */
    private static void reload() {
        listed = listedWorld == null ? List.of() : SandboxStore.list(listedWorld);
        CONFLICTS.clear();
        for (Sandbox sandbox : listed) {
            Component conflict = conflictLine(sandbox);
            if (conflict != null) CONFLICTS.put(sandbox.id(), conflict);
        }
        measureSizes(listed);
    }

    /** The sandbox with this id as last read, or null once it's gone (its card is about to go too). */
    private static @Nullable Sandbox find(String id) {
        for (Sandbox sandbox : listed) if (sandbox.id().equals(id)) return sandbox;
        return null;
    }

    // Panels

    private static List<Panel> panels(Screen parent, @Nullable String worldId, String openTab) {
        tab = worldId == null ? "settings" : openTab;
        listedWorld = worldId;
        editingId = null;
        confirm = null;
        reload();

        Tabs tabs = Tabs.builder()
                .mode(Tabs.Mode.SIDE_SCROLL)
                .state(() -> tab, id -> tab = id)
                .bodyScroll(SCROLL::get, SCROLL::put)
                .tab(Tabs.tab("sandboxes")
                        .label(Component.translatable("sandboxes.compact.tab.sandboxes"))
                        .visibleWhen(() -> worldId != null)
                        .body(() -> sandboxesBody(worldId)))
                .tab(Tabs.tab("settings")
                        .label(Component.translatable("sandboxes.compact.tab.settings"))
                        .body(SandboxesScreen::settingsBody))
                .build();

        Panel main = Panel.builder("sandboxes:screen")
                .style(PanelStyle.NONE)
                .position(PanelPosition.main())
                .add(tabs)
                .build();

        PanelElement done = Button.builder().label(CommonComponents.GUI_DONE).size(150, 20)
                .onClick(() -> Minecraft.getInstance().gui.setScreen(parent)).build();
        Panel footer = Panel.builder("sandboxes:done")
                .style(PanelStyle.NONE)
                .position(PanelPosition.region(OutsideRegion.BOTTOM_CENTER))
                .add(done)
                .build();

        // Built once; its text is read when it shows, so it names whichever sandbox asked.
        Panel dialog = ConfirmDialog.builder()
                .title(() -> Component.translatable(confirm != null && confirm.delete()
                        ? "sandboxes.confirm.delete.title" : "sandboxes.confirm.sync.title"))
                .body(() -> confirm == null ? Component.empty() : Component.translatable(confirm.delete()
                        ? "sandboxes.confirm.delete.body" : "sandboxes.confirm.sync.body", confirm.sandbox().name()))
                .onConfirm(SandboxesScreen::confirmed)
                .onCancel(() -> confirm = null)
                .build()
                .visibleWhen(() -> confirm != null);

        return List.of(main, footer, dialog);
    }

    // The Sandboxes tab

    /** "+ New sandbox" and what's being copied, fixed above the cards, which scroll on their own. */
    private static List<PanelElement> sandboxesBody(@Nullable String worldId) {
        if (worldId == null) return List.of();
        ListView<Sandbox> cards = ListView.<Sandbox>builder()
                .items(() -> listed)
                .key(Sandbox::id)
                .row(sandbox -> card(sandbox.id(), sandbox.levelId().equals(CurrentWorld.levelId())))
                .gap(4)
                .empty(TextLabel.builder().text(Component.translatable("sandboxes.screen.empty"))
                        .role(TextRole.MUTED).build())
                .build();
        Component none = Component.empty();
        return List.of(Flow.builder().vertical().gap(6)
                .add(Flow.builder().gap(8).crossAlign(CrossAlign.CENTER)
                        .add(Button.builder().label(Component.translatable("sandboxes.compact.new")).size(140, 20)
                                .disabledWhen(() -> DiskJobs.copying() != null)
                                .onClick(() -> createIn(worldId)).build())
                        .add(TextLabel.builder().role(TextRole.MUTED)
                                .text(() -> { Component c = DiskJobs.copying(); return c == null ? none : c; })
                                .build())
                        .build())
                .add(ScrollContainer.builder().fitWidth().fillHeight().content(List.of(cards)).build())
                .build());
    }

    /** The world image left of the title: vanilla's world list draws 32, this a little smaller. */
    private static final int ICON_SIZE = 28;

    private static final Identifier UNKNOWN_WORLD = Identifier.withDefaultNamespace("textures/misc/unknown_server.png");
    private static final Identifier EYE_OPEN = Identifier.fromNamespaceAndPath("sandboxes", "eye_open");
    private static final Identifier EYE_CLOSED = Identifier.fromNamespaceAndPath("sandboxes", "eye_closed");

    /**
     * One sandbox: its world image, then a stack of the name (or the rename
     * field), a grey line of when and how big, the key warning if any, and every
     * button for it, or what's being done to it while that runs.
     *
     * <p>Built once per sandbox id; every line reads the sandbox by id, and lines
     * that come and go hide, which moves the cards below.
     *
     * @param current the sandbox is the world you're in
     */
    private static PanelElement card(String id, boolean current) {
        TextField rename = TextField.builder().size(140, 20).maxLength(64)
                .state(() -> draftName, name -> draftName = name)
                .onSubmit(name -> finishRename(id))
                .onCancel(() -> editingId = null)   // Escape keeps the old name
                .visibleWhen(() -> id.equals(editingId))
                .build();
        Flow text = Flow.builder().vertical().gap(2)
                .add(TextLabel.builder().text(() -> Component.literal(name(id)))
                        .visibleWhen(() -> !id.equals(editingId)).build())
                .add(rename)
                .add(TextLabel.builder().text(() -> detail(id)).role(TextRole.MUTED).build())
                .add(TextLabel.builder().text(() -> CONFLICTS.getOrDefault(id, Component.empty()))
                        .role(TextRole.MUTED).visibleWhen(() -> CONFLICTS.containsKey(id)).build())
                // While it copies, the card says so and offers nothing that would collide.
                .add(TextLabel.builder().text(() -> { Component b = DiskJobs.busy(id); return b == null ? Component.empty() : b; })
                        .role(TextRole.MUTED).visibleWhen(() -> DiskJobs.busy(id) != null).build())
                .add(buttons(id, current, rename))
                .build();

        return Flow.builder().boxed().gap(6)
                .add(Icon.builder().texture(() -> worldIcon(id), 64).size(ICON_SIZE, ICON_SIZE).build())
                .add(text)
                .build();
    }

    private static String name(String id) {
        Sandbox sandbox = find(id);
        return sandbox == null ? "" : sandbox.name();
    }

    /**
     * Everything you can do to a sandbox, one click each. The sandbox you're
     * standing in (its card shows when Settings is opened from inside it) can't
     * be joined, synced or deleted: that would pull the world out from under you.
     */
    private static PanelElement buttons(String id, boolean current, TextField rename) {
        Flow.Builder row = Flow.builder().gap(2);
        // Eye open while this sandbox's overlay shows; it appears once the screen closes.
        // On the sandbox you're in, the eye compares it with your main world instead.
        row.add(Button.builder().size(20, 20)
                .icon(() -> {
                    Sandbox sandbox = find(id);
                    boolean on = current ? Overlay.active() : sandbox != null && Overlay.showing(sandbox);
                    return on ? EYE_OPEN : EYE_CLOSED;
                })
                .tooltip(Component.translatable("sandboxes.compact.overlay"))
                .onClick(() -> {
                    Sandbox sandbox = find(id);
                    if (current) Overlay.toggleAgainstParent();
                    else if (sandbox != null) Overlay.toggleFor(sandbox);
                }).build());
        if (OverlayKeys.available()) {
            row.add(WidgetHost.builder()
                    .widget(() -> {
                        Sandbox sandbox = find(id);
                        if (sandbox == null) return null;
                        AbstractWidget key = OverlayKeys.button(0, 0, 50, 20, sandbox, chord -> setKey(id, chord));
                        if (key != null) key.setTooltip(Tooltip.create(
                                Component.translatable("sandboxes.button.overlay_key.tooltip")));
                        return key;
                    })
                    .size(50, 20)
                    .build());
        }
        if (current) {
            row.add(TextLabel.builder().text(Component.translatable("sandboxes.row.here")).role(TextRole.MUTED).build());
        } else {
            // No leaving the world while a copy reads it (see DiskJobs).
            row.add(Button.builder().label(Component.translatable("sandboxes.button.join")).size(40, 20)
                    .disabledWhen(DiskJobs::running)
                    .onClick(() -> { Sandbox s = find(id); if (s != null) WorldSwitch.enterSandbox(s); }).build());
            row.add(Button.builder().label(Component.translatable("sandboxes.button.sync")).size(40, 20)
                    .onClick(() -> ask(id, false)).build());
        }
        row.add(Button.builder().label(Component.literal("✎")).size(20, 20)
                .tooltip(Component.translatable("sandboxes.button.rename"))
                .visibleWhen(() -> !id.equals(editingId))
                .onClick(() -> {
                    editingId = id;
                    draftName = name(id);
                    rename.focus();
                }).build());
        row.add(Button.builder().label(Component.literal("✓")).size(20, 20)
                .tooltip(Component.translatable("sandboxes.compact.rename.save"))
                .visibleWhen(() -> id.equals(editingId))
                .onClick(() -> finishRename(id)).build());
        row.add(Button.builder().label(Component.literal("❐")).size(20, 20)
                .tooltip(Component.translatable("sandboxes.button.duplicate"))
                .disabledWhen(() -> DiskJobs.copying() != null)
                .onClick(() -> { Sandbox s = find(id); if (s != null) duplicate(s, current); }).build());
        if (!current) row.add(Button.builder().label(Component.literal("✕")).size(20, 20)
                .tooltip(Component.translatable("sandboxes.button.delete"))
                .onClick(() -> ask(id, true)).build());
        return row.visibleWhen(() -> DiskJobs.busy(id) == null).build();
    }

    /** Opens the confirmation for syncing or deleting this sandbox. */
    private static void ask(String id, boolean delete) {
        Sandbox sandbox = find(id);
        if (sandbox != null) confirm = new Confirm(sandbox, delete);
    }

    /**
     * The sandbox's own icon.png, loaded the way vanilla's world list does; a
     * sandbox with none shows vanilla's unknown-world picture.
     *
     * <p>One 64x64 texture per sandbox, kept for the session (dropped on sync or
     * delete, whose icon changes). Close them with the screen if a world ever has
     * hundreds of sandboxes.
     */
    private static Identifier worldIcon(String sandboxId) {
        Sandbox sandbox = find(sandboxId);
        if (sandbox == null) return UNKNOWN_WORLD;   // its card goes this frame
        return ICONS.computeIfAbsent(sandboxId, id -> {
            FaviconTexture texture = FaviconTexture.forWorld(Minecraft.getInstance().getTextureManager(), sandbox.levelId());
            Path file = sandbox.dir().resolve("icon.png");
            if (Files.isRegularFile(file)) {
                try (InputStream in = Files.newInputStream(file)) {
                    texture.upload(NativeImage.read(in));
                } catch (Exception e) {   // unreadable, or not 64x64: show the unknown picture
                    SandboxesClient.LOGGER.warn("[sandboxes] couldn't load the icon of {}", id, e);
                }
            }
            return texture;
        }).textureLocation();
    }

    private static void forgetIcon(String sandboxId) {
        FaviconTexture texture = ICONS.remove(sandboxId);
        if (texture != null) texture.close();
    }

    /** "Overlay key also used by: Jump", or null when the key is free (or there's no Keybindery). */
    private static @Nullable Component conflictLine(Sandbox sandbox) {
        // A key another binding also uses still works, but say so where it was set.
        if (!OverlayKeys.available()) return null;
        var conflicts = OverlayKeys.conflicts(sandbox);
        return conflicts.isEmpty() ? null
                : Component.translatable("sandboxes.screen.key_conflict", conflicts.get(0).label());
    }

    /** "Oct 14 · 412 MB", or "never joined · 412 MB". */
    private static Component detail(String id) {
        Sandbox sandbox = find(id);
        if (sandbox == null) return Component.empty();
        String size = SIZES.getOrDefault(sandbox.id(), "…");
        if (sandbox.lastJoined() == 0L) return Component.translatable("sandboxes.row.never", size);
        String when = LocalDateTime.ofInstant(Instant.ofEpochMilli(sandbox.lastJoined()), ZoneId.systemDefault())
                .format(JOINED_FORMAT);
        return Component.translatable("sandboxes.compact.detail", when, size);
    }

    // The Settings tab

    private static List<PanelElement> settingsBody() {
        List<PanelElement> body = new java.util.ArrayList<>();
        body.addAll(setting("sandboxes.settings.quick", SandboxesConfig::quickButton, SandboxesConfig::setQuickButton, 0));
        body.addAll(setting("sandboxes.settings.quick_inventory",
                SandboxesConfig::quickButtonInventory, SandboxesConfig::setQuickButtonInventory, 48));
        return body;
    }

    /** A switch with its name on the left, the toggle on the right, and a grey line under it saying what it does. */
    private static List<PanelElement> setting(String key, BooleanSupplier get, Consumer<Boolean> set, int y) {
        return List.of(Flow.builder().at(0, y)
                        .add(TextLabel.builder().text(Component.translatable(key)).build())
                        .add(Flow.spacer())
                        .add(Toggle.builder().size(60, 20).state(get::getAsBoolean, set).build())
                        .build(),
                TextLabel.builder().text(Component.translatable(key + ".desc"))
                        .role(TextRole.MUTED).at(0, y + 24).build());
    }

    // Actions

    private static void finishRename(String id) {
        String name = draftName.trim();
        editingId = null;
        Sandbox sandbox = find(id);
        // An empty name would leave a row with nothing to read; keep the old one.
        if (sandbox != null && !name.isEmpty() && !name.equals(sandbox.name())) {
            try {
                SandboxStore.rename(sandbox, name);
                OverlayKeys.refresh();   // the key's label names the sandbox
            } catch (IOException e) {
                SandboxesClient.LOGGER.error("[sandboxes] couldn't rename {}", sandbox.id(), e);
            }
            reload();
        }
    }

    private static void setKey(String id, Chord chord) {
        Sandbox sandbox = find(id);
        if (sandbox == null) return;
        try {
            SandboxStore.setOverlayKey(sandbox, chord.serialize());
        } catch (IOException e) {
            SandboxesClient.LOGGER.error("[sandboxes] couldn't save the overlay key for {}", sandbox.id(), e);
            return;
        }
        OverlayKeys.refresh();
        reload();
    }

    private static void confirmed() {
        Confirm pending = confirm;
        confirm = null;
        if (pending == null) return;
        Sandbox sandbox = pending.sandbox();
        // The overlay may hold this sandbox's region files open, and Windows won't
        // delete an open file: turn it off and wait until it lets go.
        Overlay.forget(sandbox);
        if (pending.delete()) {
            onDisk(sandbox.id(), Component.translatable("sandboxes.status.deleting"), () -> {
                Overlay.awaitIdle();
                SandboxStore.delete(sandbox);
            });
        } else {
            onDisk(sandbox.id(), Component.translatable("sandboxes.status.syncing"), () -> {
                Overlay.awaitIdle();
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

    /** {@code running}: the sandbox is the world you're in, so its latest state is still in memory. */
    private static void duplicate(Sandbox sandbox, boolean running) {
        onDisk(null, Component.translatable("sandboxes.status.duplicating"), () -> {
            if (running) WorldSwitch.saveCurrentWorld();   // a copy only sees what's on disk
            SandboxStore.duplicate(sandbox);
        });
    }

    /**
     * Disk work off the client thread (see {@link DiskJobs}). With a sandbox id,
     * that card shows the message in place of its buttons; without one, the
     * message shows beside "+ New sandbox". When it's done the list is read again.
     */
    private static void onDisk(@Nullable String sandboxId, Component message, DiskJobs.Work work) {
        // A name half-typed on a sandbox about to be recopied would be written into
        // a folder that's being replaced: drop the edit.
        if (sandboxId != null && sandboxId.equals(editingId)) editingId = null;
        DiskJobs.run(sandboxId, message, work, () -> {
            if (sandboxId != null) {
                SIZES.remove(sandboxId);
                forgetIcon(sandboxId);
            }
            OverlayKeys.refresh();
            reload();
        });
    }

    /** Fills in sizes the rows don't know yet; a world folder can take a moment to walk. */
    private static void measureSizes(List<Sandbox> sandboxes) {
        List<Sandbox> unknown = sandboxes.stream().filter(s -> !SIZES.containsKey(s.id())).toList();
        if (unknown.isEmpty()) return;
        Thread.ofPlatform().name("sandboxes-sizes").start(() -> {
            // The cards read SIZES every frame, so each size shows as soon as it's in.
            for (Sandbox sandbox : unknown) SIZES.put(sandbox.id(), humanSize(WorldFiles.sizeOf(sandbox.dir())));
        });
    }

    private static String humanSize(long bytes) {
        if (bytes >= 1024L * 1024L * 1024L) return String.format("%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0));
        return (bytes / (1024L * 1024L)) + " MB";
    }
}
