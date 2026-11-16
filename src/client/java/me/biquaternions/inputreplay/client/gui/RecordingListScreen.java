package me.biquaternions.inputreplay.client.gui;

import com.google.gson.JsonObject;
import me.biquaternions.inputreplay.client.io.RecordingReader;
import me.biquaternions.inputreplay.client.session.BindingResolver;
import me.biquaternions.inputreplay.client.session.SessionManager;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Recordings in {@code <game dir>/inputreplay/recordings/}: server, date, duration, MC version, size
 * and whether the key bindings match the current ones. Replay (bindings chosen automatically),
 * replay forcing physical keys, or delete.
 */
public class RecordingListScreen extends Screen {
    private static final Logger LOG = LoggerFactory.getLogger("InputReplay/List");
    private static final int ROW_H = 22;

    private final ModeSelectScreen parent;
    private final List<Entry> entries = new ArrayList<>();
    private int page;
    private int selected = -1;
    private boolean confirmDelete;
    private static boolean syncPackets;
    private String message = "";
    private Button replayButton, physicalButton, deleteButton;

    private record Entry(Path path, RecordingReader.Header header, String label, List<String> bindingDiff,
                         List<String> optionDiff, long size) {}

    public RecordingListScreen(ModeSelectScreen parent) {
        super(Component.literal("Choose a recording to replay"));
        this.parent = parent;
        reload();
    }

    private void reload() {
        entries.clear();
        Path dir = SessionManager.recordingsDir();
        if (!Files.isDirectory(dir)) return;
        List<Path> paths;
        try (Stream<Path> s = Files.list(dir)) {
            paths = s.filter(p -> p.getFileName().toString().endsWith(".irr") || p.getFileName().toString().endsWith(".irr.partial"))
                    .sorted(Comparator.comparing(RecordingListScreen::mtime).reversed()).toList();
        } catch (IOException e) {
            message = "Cannot list " + dir + ": " + e.getMessage();
            return;
        }
        for (Path p : paths) {
            try {
                RecordingReader.Header h = RecordingReader.readHeader(p);
                BindingResolver.Snapshot opts = BindingResolver.parse(h.optionsText());
                List<String> bd = BindingResolver.bindingDifferences(opts.bindings(), BindingResolver.currentBindings());
                List<String> od = BindingResolver.relevantOptionDifferences(opts.options(), BindingResolver.currentOptions());
                entries.add(new Entry(p, h, label(h, bd, size(p)), bd, od, size(p)));
            } catch (Exception e) {
                LOG.warn("Unreadable recording {}", p, e);
            }
        }
    }

    private static String label(RecordingReader.Header h, List<String> bindingDiff, long size) {
        String started = h.str("startedAt", "?").replace('T', ' ');
        if (started.length() > 19) started = started.substring(0, 19);
        JsonObject server = h.metadata().has("server") ? h.metadata().getAsJsonObject("server") : new JsonObject();
        String addr = server.has("address") ? server.get("address").getAsString() : "?";
        String mode = h.str("mode", "?");
        return String.format("%s%s  %s  %s  %d ticks  %s  bindings %s  %.1f MB",
                h.partial() ? "[partial] " : "", started, addr, mode, h.integer("durationTicks", 0), h.str("mcVersion", "?"),
                bindingDiff.isEmpty() ? "match" : bindingDiff.size() + " differ", size / (1024.0 * 1024.0));
    }

    private static long mtime(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return 0;
        }
    }

    private static long size(Path p) {
        try {
            if (!Files.isDirectory(p)) return Files.size(p);
            try (Stream<Path> s = Files.walk(p)) {
                return s.filter(Files::isRegularFile).mapToLong(f -> f.toFile().length()).sum();
            }
        } catch (IOException e) {
            return 0;
        }
    }

    private int rowsPerPage() {
        return Math.max(1, (height - 36 - 100) / ROW_H);
    }

    @Override
    protected void init() {
        clearWidgets();
        int rows = rowsPerPage();
        int pages = Math.max(1, (entries.size() + rows - 1) / rows);
        page = Math.min(page, pages - 1);
        int w = Math.min(width - 20, 460);
        int x = (width - w) / 2;
        int y = 36;
        for (int i = page * rows; i < Math.min(entries.size(), (page + 1) * rows); i++) {
            final int idx = i;
            Entry e = entries.get(i);
            String text = (i == selected ? "> " : "") + e.label();
            addRenderableWidget(Button.builder(Component.literal(text), b -> select(idx)).bounds(x, y, w, 20).build());
            y += ROW_H;
        }
        int by = height - 30;
        Button prev = addRenderableWidget(Button.builder(Component.literal("<"), b -> {
            page--;
            init();
        }).bounds(x, by, 20, 20).build());
        prev.active = page > 0;
        Button next = addRenderableWidget(Button.builder(Component.literal(">"), b -> {
            page++;
            init();
        }).bounds(x + w - 20, by, 20, 20).build());
        next.active = page < pages - 1;

        int bw = (w - 60) / 4;
        int bx = x + 24;
        replayButton = addRenderableWidget(Button.builder(Component.literal("Replay"), b -> replay(false)).bounds(bx, by, bw, 20).build());
        physicalButton = addRenderableWidget(Button.builder(Component.literal("Replay (physical)"), b -> replay(true))
                .bounds(bx + bw + 4, by, bw, 20).build());
        deleteButton = addRenderableWidget(Button.builder(Component.literal(confirmDelete ? "Confirm delete" : "Delete"), b -> delete())
                .bounds(bx + 2 * (bw + 4), by, bw, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Back"), b -> onClose()).bounds(bx + 3 * (bw + 4), by, bw, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Packet sync: " + (syncPackets ? "ON" : "off")), b -> {
                    syncPackets = !syncPackets;
                    init();
                }).bounds(x + w - 150, 8, 150, 20)
                .tooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal(
                        "Apply packets that affect the player (velocity, teleports, attributes, effects, explosions) at the "
                                + "same tick as in the recording, pausing client ticks while one is late. Removes network "
                                + "timing as a source of divergence.")))
                .build());
        boolean sel = selected >= 0 && selected < entries.size();
        replayButton.active = sel;
        physicalButton.active = sel;
        deleteButton.active = sel;
    }

    private void select(int idx) {
        selected = idx;
        confirmDelete = false;
        message = "";
        init();
    }

    private void replay(boolean forcePhysical) {
        Entry e = entries.get(selected);
        RecordingReader reader;
        try {
            reader = new RecordingReader(e.path());
        } catch (Exception ex) {
            message = "Cannot replay: " + ex.getMessage();
            LOG.error("Cannot load {}", e.path(), ex);
            return;
        }
        if (reader.durationTicks <= 0) {
            message = "Nothing to replay: the recording has no ticks after the player loaded";
            return;
        }
        parent.connectReplay(reader, forcePhysical, syncPackets);
    }

    private void delete() {
        if (!confirmDelete) {
            confirmDelete = true;
            init();
            return;
        }
        Entry e = entries.get(selected);
        try {
            if (Files.isDirectory(e.path())) {
                try (Stream<Path> s = Files.walk(e.path())) {
                    for (Path p : s.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
                }
            } else {
                Files.deleteIfExists(e.path());
            }
            message = "Deleted " + e.path().getFileName();
        } catch (IOException ex) {
            message = "Delete failed: " + ex.getMessage();
        }
        selected = -1;
        confirmDelete = false;
        reload();
        init();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        g.text(font, title, (width - Math.min(width - 20, 460)) / 2, 14, 0xFFFFFFFF);
        if (entries.isEmpty()) {
            g.centeredText(font, Component.literal("No recordings in " + SessionManager.recordingsDir()), width / 2, 60, 0xFFA0A0A0);
        }
        int y = height - 96;
        if (selected >= 0 && selected < entries.size()) {
            Entry e = entries.get(selected);
            g.centeredText(font, Component.literal(e.path().getFileName().toString()), width / 2, y, 0xFFFFFFFF);
            y += 11;
            List<String> warn = new ArrayList<>();
            if (!e.bindingDiff().isEmpty()) warn.add("bindings differ (" + String.join(", ", first(e.bindingDiff(), 4))
                    + "): Replay injects by mapping name");
            if (!e.optionDiff().isEmpty()) warn.add("options differ: " + String.join(", ", e.optionDiff()));
            JsonObject server = e.header().metadata().has("server") ? e.header().metadata().getAsJsonObject("server") : null;
            String recAddr = server != null && server.has("address") ? server.get("address").getAsString() : null;
            if (recAddr != null && !recAddr.equals(parent.address().toString())) warn.add("recorded on " + recAddr);
            if (e.header().partial()) warn.add("partial recording (crashed)");
            for (String w : warn) {
                g.centeredText(font, Component.literal(w), width / 2, y, 0xFFFFAA00);
                y += 11;
            }
        }
        if (!message.isEmpty()) g.centeredText(font, Component.literal(message), width / 2, height - 9, 0xFFFF5555);
    }

    private static List<String> first(List<String> l, int n) {
        if (l.size() <= n) return l;
        List<String> out = new ArrayList<>(l.subList(0, n));
        out.add("+" + (l.size() - n));
        return out;
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(parent);
    }
}
