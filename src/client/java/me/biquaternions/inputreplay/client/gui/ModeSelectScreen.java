package me.biquaternions.inputreplay.client.gui;

import me.biquaternions.inputreplay.client.session.Mode;
import me.biquaternions.inputreplay.client.session.SessionManager;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.TransferState;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;

/** Shown before connecting: record, replay a recording, or connect normally. */
public class ModeSelectScreen extends Screen {
    private final Screen parent;
    private final ServerAddress address;
    private final ServerData data;
    private final boolean quickPlay;
    private final TransferState transfer;

    public ModeSelectScreen(Screen parent, ServerAddress address, ServerData data, boolean quickPlay, TransferState transfer) {
        super(Component.literal("InputReplay"));
        this.parent = parent;
        this.address = address;
        this.data = data;
        this.quickPlay = quickPlay;
        this.transfer = transfer;
    }

    @Override
    protected void init() {
        int w = 200;
        int x = (width - w) / 2;
        int y = height / 2 - 50;
        addRenderableWidget(Button.builder(Component.literal("Record"), b -> connect(Mode.RECORD, null, false))
                .bounds(x, y, w, 20)
                .tooltip(Tooltip.create(Component.literal("Record inputs, packets and player state until you leave the server")))
                .build());
        Button replay = addRenderableWidget(Button.builder(Component.literal("Replay..."), b -> {})
                .bounds(x, y + 24, w, 20)
                .tooltip(Tooltip.create(Component.literal("Replay engine: not implemented yet (step 2)")))
                .build());
        replay.active = false;
        addRenderableWidget(Button.builder(Component.literal("Plain"), b -> connect(Mode.PLAIN, null, false))
                .bounds(x, y + 48, w, 20)
                .tooltip(Tooltip.create(Component.literal("Connect without recording")))
                .build());
        addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> onClose())
                .bounds(x, y + 84, w, 20)
                .build());
    }

    /** Arms the chosen mode and re-invokes the connect that was intercepted. */
    public void connect(Mode mode, Path replayFile, boolean forcePhysical) {
        SessionManager.arm(mode, replayFile, forcePhysical);
        ConnectScreen.startConnecting(parent, minecraft, address, data, quickPlay, transfer);
    }

    public ServerAddress address() {
        return address;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        g.centeredText(font, title, width / 2, height / 2 - 90, 0xFFFFFFFF);
        g.centeredText(font, Component.literal("Connecting to " + address), width / 2, height / 2 - 74, 0xFFA0A0A0);
    }

    @Override
    public void onClose() {
        minecraft.gui.setScreen(parent);
    }
}
