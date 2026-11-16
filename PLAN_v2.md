# InputReplay — Design and Implementation Plan (v2)

Supersedes `PLAN.md`. Changes from v1 are the decisions taken in review:

| Decision | Outcome |
|---|---|
| Chat / command packets | Not required to match byte for byte. The diff tool compares them by class and tick only (default ignore list, §8). |
| Recording size | No size constraints. Default packet policy is **full payload for every packet**, including chunks and light. The per-class policy stays available as an opt-in filter; cleanup of old recordings is the user's responsibility. |
| Key bindings on replay | Each key event is recorded with both the physical key and the resolved key-mapping names. On replay, if the recording's bindings differ from the current ones, injection happens by **mapping name**; otherwise by physical key (§5.2, §7.1). |
| Tick 0 | The first client tick in which `ClientPacketListener.hasClientLoaded()` is true and `Minecraft.player` is non-null (§5.1). |

Target: Fabric client mod for Minecraft **26.3** (unobfuscated Mojang names, Java 25, Fabric Loader 0.20.x,
Fabric API 0.166.x).

Purpose: reproduce, with a real Minecraft client, the exact sequence of player inputs from a previous
server session, while capturing every packet in both directions, so that server-side physics and
movement handling can be analysed by comparing what the client sent and received across runs.

Every Minecraft class or method named below was verified against
`fabric-loom/26.3/minecraft-client.jar` in the local Gradle cache. ReplayMod (in `references/`) is the
reference for Netty packet capture and for the pitfalls of injecting into the client loop.

---

## 1. Summary of the design

| Topic | Decision |
|---|---|
| What is recorded | (a) player inputs at Minecraft's own input-event boundary, (b) every inbound packet (raw bytes + decoded class name + `toString()` summary), (c) every outbound packet, (d) a per-tick snapshot of the local player's physics state |
| Frame of reference | the **client tick index**, anchored at the player-loaded tick, plus a monotonic nanosecond clock and a global per-record sequence number |
| Replay unit | per tick: all key / mouse-button / scroll / text events and all mouse-look deltas recorded between the previous tick and this one are injected right before `Minecraft.tick()` runs |
| How inputs are injected | by calling the same public methods SDL calls (`KeyboardHandler.keyPress`, `MouseHandler.onButton`, `MouseHandler.onScroll`, …) and `LocalPlayer.turn` for look; real hardware input is cancelled by mixins while a replay is active |
| Mode selection | a screen shown **before** connecting (intercept `ConnectScreen.startConnecting`) so handshake, login and configuration packets are captured too |
| Verification | the replay run also records; the analysis script diffs the serverbound `ServerboundMovePlayerPacket` stream and the per-tick snapshots of the two recordings to find the first tick where the runs diverge |
| File format | one zip (`.irr`) with `metadata.json` and several gzip'd binary streams; a standalone Python script converts to JSONL/CSV with filters, without Minecraft protocol knowledge, because the mod stores a decoded summary per packet |
| Packet replay (ReplayMod style) | optional later "offline deterministic" mode that feeds recorded inbound packets at their recorded tick boundaries; not needed for the primary use case |

Why inputs rather than packets are the primary replay source: the goal is to let the live client run its
own physics (entity pushing, knockback, collision, sprint and jump timing) and let the server react to it.
Replaying packets would reproduce the *display* of a session, not re-execute the simulation.

---

## 2. Facts about the 26.3 client that shape the design

### 2.1 Frame and tick loop (`Minecraft.runTick(boolean)`)

Per frame, in this order:

1. `DeltaTracker.Timer.advanceGameTime(Util.getMillis())` decides how many ticks to run (0..10).
2. `PacketProcessor.processQueuedPackets()` applies every packet that arrived on the Netty thread since the last frame.
3. `runAllTasks()` runs scheduled main-thread tasks.
4. `tick()` is run N times.
5. `MouseHandler.handleAccumulatedMovement()` applies accumulated mouse deltas to the player (`LocalPlayer.turn`).
6. `renderFrame(boolean)`.

Inside `Minecraft.tick()` (relevant subset, in order): `clientTickCount++` → `MultiPlayerGameMode.tick()` →
`pick(1.0f)` (recomputes `hitResult` / `crosshairPickEntity`) → `Gui.tick()` → `handleKeybinds()`
(attack / use / drop / inventory / hotbar via `KeyMapping.isDown()` and `consumeClick()`) →
`GameRenderer.tick()` → `ClientLevel.tickEntities()` (runs `LocalPlayer.tick()` → `aiStep()`, which calls
`ClientInput.tick()` and applies movement) → `LocalPlayer.sendChanges()` (sends
`ServerboundMovePlayerPacket.*` and sprint packets) → `ClientLevel.tick()` → particles →
`connection.send(ServerboundClientTickEndPacket)` → `Connection.tick()` → `KeyboardHandler.tick()`.

Consequences:

- Packets are applied at frame boundaries; ticks happen inside frames. The tick at which a packet is
  applied is the natural frame of reference and is only known on the main thread.
- Mouse look is applied **per frame**, not per tick. `MouseHandler.turnPlayer` scales deltas by
  sensitivity and by real time since the last frame (`Blaze3D.getTime()`), with optional smoothing. Raw
  mouse deltas are therefore not reproducible; the arguments of `LocalPlayer.turn(dx, dy)` are.
  `Entity.turn` multiplies by 0.15, clamps pitch to ±90 and adjusts `xRotO` / `yRotO`, so replaying the
  recorded `turn` calls in order reproduces rotation exactly.
- The client sends `ServerboundClientTickEndPacket` at the end of every tick: a per-tick marker visible to
  the server and in the recorded outbound stream.
- `LocalPlayer.tick()` returns early until `ClientPacketListener.hasClientLoaded()` is true. That is tick 0.

### 2.2 Input path

`SDLEventHandler.pollEvents()` calls exactly these public methods:

- `KeyboardHandler.keyPress(long window, int action, KeyEvent)` and `KeyboardHandler.textInput(long, String)`
  (`charTyped` is reached from there),
- `MouseHandler.onButton(long window, MouseButtonInfo, int action)`,
- `MouseHandler.onScroll(long, double, double)`,
- `MouseHandler.onMove(long, double x, double y, double dx, double dy)`,
- `MouseHandler.onDrop(long, List<String>)`.

`keyPress` and `onButton` end in `KeyMapping.set(key, down)` / `KeyMapping.click(key)`, or in the open
`Screen`. `KeyboardInput.tick()` builds the `Input` record (forward/backward/left/right/jump/shift/sprint)
from `Options.keyUp/keyDown/keyLeft/keyRight/keyJump/keyShift/keySprint.isDown()`.
`Minecraft.handleKeybinds()` consumes `keyAttack`, `keyUse`, `keyPickItem`, `keyDrop`, `keyInventory`,
`keySwapOffhand`, `keyHotbarSlots[]`, `keyTogglePerspective`, `keySmoothCamera` and others.

`KeyMapping` exposes everything needed for binding-independent replay: the static map from
`InputConstants.Key` to the list of mappings bound to it (`KeyMapping.MAP`, access-widened), `getName()`,
`get(String name)`, `setDown(boolean)`, and the private `clickCount` field (access-widened for `click`).

### 2.3 Network pipeline (`Connection`)

Client pipeline, head → tail: `timeout`, `splitter`, [`decompress`], `decoder`, [`unbundler`],
`prepender`, [`compress`], `encoder`, `hackfix`, `packet_handler` (the `Connection` itself).
Before a protocol is configured, `inbound_config` / `outbound_config` placeholders stand in for
`decoder` / `encoder`; `Connection.setupInboundProtocol` swaps in the real `PacketDecoder` and inserts a
`PacketBundlePacker` per protocol phase. Compression handlers are inserted later by
`Connection.setupCompression`.

Adopted from ReplayMod: a **raw** inbound handler right before `decoder` (uncompressed `ByteBuf` =
varint packet id + payload) and a **decoded** inbound handler right after `decoder` (the `Packet<?>`
object, giving class name and phase). The decoded handler re-attaches after every protocol swap via an
`@Inject` at the tail of `Connection.setupInboundProtocol`.

Packets are handed to the main thread by `PacketProcessor.scheduleIfPossible(listener, packet)` and run
by `PacketProcessor.ListenerAndPacket.handle()` inside `processQueuedPackets()`; that is where the
"applied at tick N" stamp is taken.

Outbound: `Connection.send(Packet)` → `sendPacket` → `doSendPacket`, called on the main thread from
`ClientPacketListener.send`. A handler added with `pipeline.addBefore("encoder", …)` sees the encoded,
uncompressed `ByteBuf` on the way out.

### 2.4 Sources of non-determinism and mitigations

| Source | Impact on movement | Mitigation |
|---|---|---|
| Mouse look is frame-timed | high | record `LocalPlayer.turn` arguments per tick; replay them at the tick boundary (§5.2) |
| Key events land in a tick depending on real time | high | record each event with the tick it precedes; replay before that tick |
| Packet arrival vs. tick boundary (network jitter) | high, and **this is what is under test** | record the apply tick for every packet in both runs; the diff tool reports when a packet moved relative to the inputs; the optional offline mode (§10) removes it entirely |
| Server-side randomness, other players, mobs | high | controlled test server (flat world, no mobs, only the test client); outside the mod's scope |
| `Entity.random`, `ClientLevel.random`, particles, sounds | cosmetic | ignore; optionally seed the local player's `RandomSource` in both runs |
| Window size / GUI scale affects screen clicks | GUI only | stored in metadata, warn on mismatch; GUI clicks are best-effort in v1 |
| Key bindings differ between record and replay | high | resolved by name-based injection (§7.1) |
| Other `options.txt` values (toggle sneak/sprint, auto-jump, sensitivity) | medium | options snapshot stored; toggle and auto-jump mismatches are warnings the user can override; sensitivity is irrelevant because `turn` arguments are post-sensitivity |
| Tick rate changes (`TickRateManager`), frozen ticks | medium | recorded as events; replay is per tick so it stays aligned |
| Client catch-up (several ticks in one frame) | none | inputs are keyed by tick, not frame |

---

## 3. Modes and user flow

### 3.1 Arming a mode before connecting

Mixin `ConnectScreen.startConnecting(Screen, Minecraft, ServerAddress, ServerData, boolean, TransferState)`
at HEAD: if no mode is armed and the prompt is enabled, cancel, open `ModeSelectScreen`, then re-invoke
`startConnecting` with the chosen mode armed. Choices:

1. **Record**: connect; recording starts as soon as the `Connection` exists, tick 0 is set when the player is loaded.
2. **Replay**: open `RecordingListScreen` (files in `<game dir>/inputreplay/recordings/`) showing server,
   date, duration in ticks, MC version and whether the key bindings match the current ones. On pick:
   connect to the chosen server, arm the replayer, and also record (the replay run produces a second
   `.irr`, which the analysis compares against the original).
3. **Plain**: connect normally.

A client command `/inputreplay record|replay <file>|plain|off` (Fabric `fabric-command-api-v2`) and the
JVM properties `-Dinputreplay.mode=replay -Dinputreplay.file=<path> -Dinputreplay.autoconnect=<host:port>`
arm the same state without the screen, for scripted runs (the client still needs a window; Xvfb on CI).

`TransferState` (server-initiated transfer): the session continues, a `TRANSFER` event is logged, and
the pipeline hooks are re-installed on the new `Connection`.

### 3.2 Record mode

- Recording starts when the client `Connection` is configured: `Connection.configureSerialization` at
  RETURN installs the raw/decoded handlers and the outbound handler.
- HUD (top-left): `REC  tick 1234  in 5678 pkts  out 2345 pkts  12.3 MB`.
- Inputs, packets and per-tick snapshots are appended to streams on a single writer thread with a
  bounded queue. The queue never blocks the render or Netty threads; on overflow the mod logs, sets
  `overflow: true` in metadata and keeps going rather than stalling the game. Because full chunk payloads
  are now recorded by default, the queue is sized generously (default 64 MiB of pending bytes) and the
  HUD shows the file size so the user can see growth.
- The session ends on `Connection.channelInactive` (any disconnect, including kicks). Metadata receives
  `durationTicks` and `endReason`; the file is finalized. A `.partial` suffix is used while writing so a
  crash leaves an identifiable file that the reader can still open (streams are self-delimiting).

### 3.3 Replay mode

- Hardware input is blocked by cancelling `KeyboardHandler.keyPress/textInput/charTyped` and
  `MouseHandler.onButton/onScroll/onMove` unless the call comes from the replayer (a thread-confined
  `injecting` flag). One escape hotkey (default F10, configurable) is let through and aborts the replay.
  The mouse stays grabbed.
- Before each client tick N the replayer applies the recorded turns and events for tick N (§7.1), then
  vanilla `tick()` runs.
- The replay run records as well, with `metadata.replayOf = <source file>` and `metadata.bindingMode`
  set to `physical` or `byName`.
- HUD: `REPLAY  tick 1234 / 9876   Δpos 0.000  Δvel 0.000  bindings: byName`.
- At the recorded end tick the replayer calls `Minecraft.disconnectFromWorld(Component)`; the session
  finalizes like a recording.
- Death, respawn and dimension change replace `Minecraft.player`; the replayer re-reads
  `minecraft.player` each tick and injects through `minecraft.keyboardHandler` / `mouseHandler`, which
  are stable objects.

---

## 4. Code layout

```
src/client/java/me/biquaternions/inputreplay/client/
  InputreplayClient.java            entrypoint: events, commands, key binding, config
  Config.java                       prompt on/off, abort key, packet policy (default: all full), snapshot options, queue size
  session/
    SessionManager.java             IDLE → ARMED(mode) → RECORDING | REPLAYING → FINALIZING
    TickClock.java                  tick index anchored on player-loaded, frame counter, nanoTime base
    PlayerSnapshotter.java          per-tick LocalPlayer state → SNAPSHOT record
    BindingResolver.java            physical key ↔ mapping names, mismatch detection, options snapshot
  capture/
    InputCapture.java               receives events from the input mixins, tags with tick/frame/seq, resolves mapping names
    PacketCapture.java              Netty handlers (RawInbound, DecodedInbound, Outbound) + apply-tick stamping
    PacketSummarizer.java           class name + toString() + size; per-class policy (default full)
  replay/
    Replayer.java                   per-tick injection, lockout flag, end handling, abort
    InputInjector.java              physical-key mode: KeyboardHandler/MouseHandler calls
                                    byName mode: KeyMapping.setDown / click by name, plus screen routing
    DriftMonitor.java               live snapshot vs. source snapshot, feeds HUD
  io/
    RecordingWriter.java            zip container, gzip streams, single writer thread, .partial handling
    RecordingReader.java            loads metadata + inputs + snapshots + options
    Records.java                    binary record definitions (§6)
  gui/
    ModeSelectScreen.java, RecordingListScreen.java, SessionHud.java
  mixin/client/
    ConnectScreenMixin              arm/prompt before connecting
    ConnectionMixin                 pipeline install, protocol swap re-attach, send() capture, channelInactive
    PacketProcessorMixin            stamp apply-tick in ListenerAndPacket.handle()
    KeyboardHandlerMixin            record / block keyPress, textInput, charTyped
    MouseHandlerMixin               record / block onButton, onScroll, onMove; wrap the turn call in turnPlayer
    MinecraftMixin                  frame counter; tick HEAD/TAIL if Fabric events prove insufficient
    LocalPlayerMixin                snapshot after aiStep / sendChanges
    KeyMappingAccessor              MAP, clickCount, setDown
    ClientPacketListenerMixin       (optional) teleport, respawn, disconnect reason hooks
src/main/resources/
  inputreplay.accesswidener         Connection.channel, Minecraft.clientTickCount, Minecraft.pick,
                                    MouseHandler.xpos/ypos/accumulatedDX/DY, KeyMapping.MAP/clickCount
tools/
  irr_dump.py                       standalone converter / filter / diff (§8)
  irr_format.md                     byte-level spec of the container
```

The `main` source set only holds the `ModInitializer`; the mod is client-only.

---

## 5. Recording details

### 5.1 Clock and anchors

- **Tick 0** is the first `Minecraft.tick()` in which `minecraft.getConnection().hasClientLoaded()` is
  true and `minecraft.player != null`. The `PLAYER_LOADED` event stores the `seq` of that tick.
- Records written before the anchor carry negative tick values counted backwards from the anchor
  (resolved when the anchor is known: the writer keeps a provisional counter from session start and the
  reader subtracts `anchorTick` from every record). This keeps login, configuration, chunk batch and the
  initial teleport in the timeline with a consistent axis.
- `frame` = frames since session start (incremented in `runTick`).
- `t` = `System.nanoTime() - sessionStartNanos`.
- Every record carries `(seq, t, tick, frame)`; `seq` is global across all streams.

### 5.2 Input stream (`inputs.bin`)

All records are tagged with the tick they **precede**, i.e. the first tick that will observe them.

| Type | Fields | Source hook |
|---|---|---|
| `KEY` | action (press/release/repeat), key, scancode, modifiers, **mappingNames[]** (names of every `KeyMapping` bound to that key at the time, e.g. `key.forward`, `key.sprint`; empty if none) | `KeyboardHandler.keyPress` HEAD |
| `TEXT` | string | `KeyboardHandler.textInput` HEAD |
| `CHAR` | codepoint, modifiers | `KeyboardHandler.charTyped` HEAD (only if not already covered by TEXT) |
| `MOUSE_BUTTON` | button, action, modifiers, cursor x/y (window px), **mappingNames[]** | `MouseHandler.onButton` HEAD |
| `SCROLL` | dx, dy | `MouseHandler.onScroll` HEAD |
| `CURSOR` | x, y (absolute, window px) | `MouseHandler.onMove` HEAD, only while a `Screen` is open or a button is held; coalesced per frame |
| `TURN` | dx, dy (the exact doubles passed to `LocalPlayer.turn`) | `@WrapOperation` of the `LocalPlayer.turn` call inside `MouseHandler.turnPlayer` |
| `TICK_MARK` | tick, frame, t, framesSinceLastTick, screenOpen (class name or none) | start of each `Minecraft.tick()` |
| `KEY_STATE` | bitset of all registered `KeyMapping.isDown()` in `KeyMapping.ALL` order (names stored once in metadata) + `player.input.keyPresses` | end of each tick; verification and fallback injection |

Hotkeys of the mod itself (abort, HUD toggle) are excluded from the recording.

`mappingNames` costs a map lookup per event and makes the recording binding-independent. The full
binding table (`mapping name → physical key`) and the relevant options are also written to
`options.txt` inside the container so the replay side can detect mismatches.

Recording `TURN` rather than `onMove` deltas: `turnPlayer` folds sensitivity, frame time, smooth-camera
smoothing and the spyglass state into the two doubles handed to `turn`. Replaying those doubles in the same
order before the same tick gives the same yaw, pitch, `xRotO` and `yRotO`.

### 5.3 Packet streams (`packets_in.bin`, `packets_out.bin`)

| Field | Notes |
|---|---|
| `seq`, `t`, `tick`, `frame` | `t` = time received on the Netty thread (inbound) or time `send()` was called (outbound) |
| `applyTick`, `applyFrame`, `applySeq` | inbound only; stamped in `PacketProcessor.ListenerAndPacket.handle()`, matched to the receive record by packet object identity (`IdentityHashMap`) |
| `phase` | HANDSHAKE / STATUS / LOGIN / CONFIGURATION / PLAY (from `ProtocolInfo.id()` of the active decoder/encoder) |
| `packetId` | varint id as on the wire |
| `className` | e.g. `ClientboundPlayerPositionPacket`; bundle members are recorded individually with a `bundleIndex` |
| `summary` | `packet.toString()`; most 26.x packets are records so this lists all fields; truncated at a configurable limit (default 16 KiB, since size is not a concern) |
| `size` | wire size |
| `payload` | raw bytes (id + body) |

**Default policy: `full` for every class.** The per-class policy (`full` / `summary` / `skip`) remains in
`Config` for users who want smaller files, and whatever policy was active is written into
`metadata.packetPolicy` so the tool can tell what is missing. Filtering for analysis happens in
`irr_dump.py`, not at record time.

Outbound packets are captured at `Connection.send(Packet, ChannelFutureListener, boolean)` HEAD (main
thread, exact tick) plus the encoded bytes from the outbound Netty handler, matched by identity.
`ServerboundMovePlayerPacket`, `ServerboundPlayerInputPacket`, `ServerboundPlayerCommandPacket` and
`ServerboundClientTickEndPacket` are what the analysis relies on.

### 5.4 Per-tick snapshot stream (`snapshots.bin`)

Taken at the end of every tick (after `sendChanges`) from `LocalPlayer`:

`tick, x, y, z, yRot, xRot, yHeadRot, vx, vy, vz (getDeltaMovement), onGround, horizontalCollision,
verticalCollision, isSprinting, pose ordinal, isSwimming, isFallFlying, fallDistance, jumpRidingScale,
sprintTriggerTime, vehicleId, input.keyPresses bits, input.moveVector (x, y), lastSentInput bits,
hitResult type/pos/entityId, selectedHotbarSlot, heldItem id`.

About 100 bytes per tick. The drift monitor and the diff tool use this stream first. It is also the
source for the "state override" fallback: if events alone prove insufficient, the replayer can force
`player.input.keyPresses` right after `KeyboardInput.tick()` and force yaw/pitch at tick start.

### 5.5 Events stream (`events.bin`)

`SESSION_START`, `CONNECTED`, `PLAYER_LOADED` (anchor), `RESPAWN`, `DIMENSION_CHANGE`,
`SCREEN_OPEN/CLOSE` (class name), `WINDOW_RESIZE`, `TICKRATE_CHANGE`, `TRANSFER`, `DISCONNECT` (reason),
`REPLAY_ABORTED`, `BINDING_MISMATCH` (list of differing mapping names), `QUEUE_OVERFLOW`.

---

## 6. Container format (`.irr`)

Zip file (`java.util.zip`), each stream gzip'd independently:

```
metadata.json       written at start, rewritten at end
inputs.bin.gz
packets_in.bin.gz
packets_out.bin.gz
snapshots.bin.gz
events.bin.gz
options.txt         key bindings (mapping name → key), sensitivity, toggle sneak/sprint, auto-jump, gui scale
```

Record framing, identical for every stream:

```
u8   recordType
uvarint length          (bytes that follow)
uvarint seq
uvarint tNanos (delta from previous record in the same stream)
svarint tick
uvarint frame
...type-specific fields (little-endian; strings = uvarint length + UTF-8; bytes = uvarint length + raw;
   string arrays = uvarint count + strings)
```

`metadata.json`:

```json
{
  "formatVersion": 1,
  "generator": "InputReplay 1.0",
  "mcVersion": "26.3", "protocolVersion": 0, "loaderVersion": "0.20.1",
  "mode": "record" | "replay",
  "replayOf": "2026-11-15_10-02-11.irr",
  "bindingMode": "physical" | "byName",
  "server": {"address": "localhost:25565", "name": "test"},
  "startedAt": "2026-11-15T10:02:11Z", "endedAt": "...",
  "anchorSeq": 1234, "anchorTick": 57, "durationTicks": 9876, "endReason": "user_disconnect",
  "window": {"width": 1920, "height": 1080, "guiScale": 2, "fullscreen": false},
  "keyMappingNames": ["key.forward", "key.left", "..."],
  "optionsHash": "sha256...",
  "packetPolicy": {},
  "modList": [{"id": "fabric-api", "version": "..."}],
  "overflow": false
}
```

`.mcpr` from ReplayMod is not reused: it stores only clientbound packets with millisecond timestamps and
needs ReplayStudio/ViaVersion for decoding; this project needs inputs, outbound packets, tick indices and
snapshots, and gets readable packet contents from `toString()` without a protocol library.

---

## 7. Replay engine details

### 7.1 Injection order within a tick and binding modes

```
START_CLIENT_TICK (tick N):
  for each TURN(dx,dy) tagged N (seq order):            player.turn(dx, dy)
  for each KEY/TEXT/CHAR/MOUSE_BUTTON/SCROLL/CURSOR tagged N (seq order):
       injecting = true;  InputInjector.inject(record);  injecting = false
  vanilla Minecraft.tick():  pick → handleKeybinds → aiStep (KeyboardInput.tick) → sendChanges → …
END_CLIENT_TICK:
  DriftMonitor.compare(snapshotOfTick(N), sourceSnapshot(N))
```

Binding mode is chosen once when the replay is armed, by `BindingResolver` comparing the recording's
`options.txt` binding table with the current `Options`:

- **physical** (tables identical): `KEY` → `keyboardHandler.keyPress(window, action, new KeyEvent(key, scancode, modifiers))`,
  `MOUSE_BUTTON` → `mouseHandler.onButton(window, new MouseButtonInfo(button, modifiers), action)`. This
  reproduces everything including screen handling and debug keys.
- **byName** (any difference): for events that carry `mappingNames`, the injector resolves each name
  with `KeyMapping.get(name)` and, for press/release, calls `setDown(true/false)`; for a press it also
  increments `clickCount` (what `KeyMapping.click` does), so `consumeClick()` behaves as it did. If a
  `Screen` is open, the event is additionally routed to the screen through the current physical key of
  that mapping (`mapping.getKey()`), so GUI shortcuts (e.g. inventory close) still work. Events with no
  mapping names (typed text, scroll, cursor, GUI clicks) are injected as in physical mode. Mappings that
  exist in the recording but not in the current client (removed mod) are logged once per name and skipped;
  the `BINDING_MISMATCH` event lists them.
- The user can force `physical` in `RecordingListScreen` (e.g. to reproduce a debug-key sequence) and the
  HUD shows the active mode.

`handleAccumulatedMovement` is neutralised during replay (`accumulatedDX/DY` stay 0 because `onMove` is
blocked), so no extra rotation sneaks in between ticks.

For `MOUSE_BUTTON` while a screen is open, recorded `CURSOR` events restore `MouseHandler.xpos/ypos`
before the click so slot clicks hit the same widget; this needs the same window size and GUI scale, hence
the metadata check.

### 7.2 Replay pacing

The timer is untouched: the client ticks at the normal rate and the server sees a normal client. If the
client falls behind, inputs are still consumed one tick at a time, so the sequence stays identical; only
wall-clock timing differs, which the packet apply-tick records expose.

`pauseOnDrift` (default off) freezes the replay when Δpos exceeds a threshold so the state can be
inspected in-game.

### 7.3 End conditions

- recorded `DISCONNECT` tick reached → `minecraft.disconnectFromWorld(...)`, finalize both files.
- server kicks the client earlier → finalize, `endReason = "server_disconnect_during_replay"`.
- abort key → finalize, `endReason = "aborted"`.
- `minecraft.player` null for more than a configurable number of ticks → abort with reason.

---

## 8. Analysis tooling (`tools/irr_dump.py`)

Pure Python 3.11+, standard library only (`zipfile`, `gzip`, `json`, `argparse`, `csv`, `fnmatch`).

```
irr_dump.py info      file.irr                         # metadata, counts per stream, per-class packet histogram, bytes per class
irr_dump.py dump      file.irr [--stream inputs|in|out|snapshots|events|all]
                      [--tick 100:250] [--class 'ClientboundPlayerPositionPacket,Serverbound*']
                      [--exclude-class 'Clientbound*Chunk*,ClientboundLightUpdatePacket']
                      [--phase play] [--no-payload] [--format jsonl|csv|text] [-o out.jsonl]
irr_dump.py timeline  file.irr --tick 100:250          # merged, seq-ordered view of inputs + packets + snapshots
irr_dump.py diff      a.irr b.irr [--align tick]        # first divergent tick; per-tick Δpos/Δvel/Δrot table,
                      [--ignore-class ...]              # outbound MovePlayer mismatches, inbound packets whose
                      [--strict]                        # apply-tick changed between runs
irr_dump.py export-moveplayer file.irr -o moves.csv     # tick, x, y, z, yaw, pitch, onGround from ServerboundMovePlayerPacket
irr_dump.py strip     file.irr -o small.irr --exclude-class 'Clientbound*Chunk*,ClientboundLightUpdatePacket'
                                                        # rewrite a recording with payloads dropped for the given classes
```

Because recordings now contain full chunk and light payloads, `strip` exists to produce a lighter copy
for sharing or quick inspection; the original stays untouched.

`diff` default ignore list (compared by class name and tick only, bytes not compared):
`ServerboundChatPacket`, `ServerboundChatCommandSignedPacket`, `ServerboundChatCommandPacket`,
`ServerboundChatAckPacket`, `ServerboundChatSessionUpdatePacket`, `ServerboundKeepAlivePacket`,
`ServerboundPongPacket`, `ServerboundClientInformationPacket`, `ServerboundCustomPayloadPacket`.
`--strict` disables the list; `--ignore-class` extends it.

The script never decodes Minecraft packets: it prints the summary the mod stored, and raw bytes are
available as hex for anything that needs deeper decoding later.

An in-game `/inputreplay export <file> [filters]` running the same conversion in Java is optional; the
Python tool is primary because it runs anywhere.

---

## 9. Build and project housekeeping

- Git has `build.gradle` and `settings.gradle` staged as deleted while the `.kts` versions are untracked;
  commit the migration first.
- `build.gradle.kts`: keep the full `fabric-api` dependency; add
  `loom { accessWidenerPath.set(file("src/main/resources/inputreplay.accesswidener")) }` and the
  `"accessWidener"` entry in `fabric.mod.json`. MixinExtras (`@WrapOperation`) is bundled with Fabric Loader.
- Mixin json `compatibilityLevel` is `JAVA_25`; keep `defaultRequire: 1` so a failed injection fails fast
  after a Mojang rename.
- Test server: a 26.3 dedicated server under `run/server/` with `level-type=flat`, `spawn-monsters=false`,
  `online-mode=false`, `view-distance=4`, plus `start-server.sh`. Loom's `runClient` with the
  `-Dinputreplay.*` properties is the automation entry point.
- Recordings live in `<game dir>/inputreplay/recordings/`. No automatic cleanup; the recording list
  screen shows file sizes and offers a delete button.

---

## 10. Alternatives considered and optional extensions

1. **Headless bot** (rejected): would require re-implementing client physics.
2. **Packet replay only (ReplayMod style)**: reproduces what the client *saw*, not what it *did*. Kept
   as an optional **offline deterministic mode**: feed `packets_in` through an `EmbeddedChannel` with the
   real `PacketDecoder` / `ClientPacketListener` while replaying inputs, applying each packet exactly
   before the tick it was applied in the original run. If offline replay matches the original snapshots,
   any divergence in live replay is caused by the server or by timing. ReplayMod's `FullReplaySender`
   shows the required hacks (fake login, closing the level loading screen, timer control).
3. **Recording raw SDL events**: native structs, frame-bound timing, and replay would still go through
   the same handler methods. Not worth it.
4. **Recording key-mapping state per tick instead of events**: loses press+release inside one tick and
   typed text. Kept as the `KEY_STATE` verification record and fallback override.
5. **Tick-locked look option** (recommended extension): apply mouse deltas only at tick boundaries in
   both record and replay; removes frame-rate dependence from the recording itself.
6. **Server-side companion** (future): a small server mod logging the server's view of the player per
   tick, anchored on `ServerboundClientTickEndPacket`, turning client/server comparison into one diff.

---

## 11. Implementation phases

### Phase 0 — Scaffolding (½ day)
- Commit the Gradle migration; add access widener, config, logger.
- `SessionManager` + `TickClock` with Fabric `ClientTickEvents` and `ClientPlayConnectionEvents`;
  anchor on `hasClientLoaded()`.
- Check: `runClient` starts; HUD shows tick count after joining the local test server, starting at 0 the
  moment the player can move.

### Phase 1 — Container + snapshots + events (1 day)
- `RecordingWriter`/`Reader`, record framing, metadata, `.partial` handling, writer thread with bounded queue.
- `PlayerSnapshotter`; `events.bin`; `options.txt` export; `BindingResolver` table.
- `tools/irr_dump.py info|dump` for snapshots/events.
- Check: join, walk, disconnect → `.irr` produced; `irr_dump.py dump --stream snapshots` shows a smooth
  trace; tick 0 corresponds to the `PLAYER_LOADED` event.

### Phase 2 — Packet capture (1–2 days)
- `ConnectionMixin`: raw/decoded inbound handlers at `configureSerialization` RETURN; re-attach after
  `setupInboundProtocol`; outbound handler before `encoder`; `send()` HEAD capture.
- `PacketProcessorMixin`: apply-tick stamping.
- `PacketSummarizer`, bundle unpacking, full-payload default, writer throughput check with chunk-heavy
  joins (teleport across the map while recording).
- Check: every `ServerboundClientTickEndPacket` carries the tick of the enclosing snapshot; chunk
  packets have full payloads; no `QUEUE_OVERFLOW` during a 10-minute session with `view-distance=12`.

### Phase 3 — Input capture (1 day)
- `KeyboardHandlerMixin`, `MouseHandlerMixin` (`@WrapOperation` on the `turn` call), `mappingNames`
  resolution, `CURSOR` coalescing, `KEY_STATE`, screen open/close events.
- Mode prompt, record HUD, `/inputreplay` command, system properties.
- Check: `irr_dump.py timeline` shows key presses with mapping names in the ticks before movement
  starts; `TURN` sums per tick match yaw/pitch deltas between consecutive snapshots.

### Phase 4 — Replay engine (2–3 days)
- `Replayer`, `InputInjector` (physical and byName), lockout mixins with `injecting` flag and abort key,
  `RecordingListScreen` with binding-mismatch indicator and delete button, end-of-recording disconnect,
  `DriftMonitor` HUD, replay-run recording with `replayOf` / `bindingMode`.
- Check (project acceptance test): on the flat test server, record 60 s of walking, sprint jumping,
  sneaking, turning and a few block interactions; replay; `irr_dump.py diff` reports zero divergence in
  `ServerboundMovePlayerPacket` and snapshots. Repeat after rebinding forward/sprint keys to confirm
  byName mode gives the same result. Then add knockback from a second client or a boat pushing the
  player, and confirm the divergence tick coincides with a changed apply-tick of the related packet.

### Phase 5 — Analysis polish (1 day)
- `diff` with the ignore list, `export-moveplayer`, `strip`, CSV output, glob filters, `irr_format.md`.
- Optional in-game `/inputreplay export`.

### Phase 6 — Optional
- Tick-locked look option; offline deterministic replay; server-side companion; fixed-seed
  `RandomSource` for the local player.

Total for v1 (phases 0–5): roughly 7–9 working days.

---

## 12. Remaining risks

- **Mojang renames between 26.x releases**: all hooks are listed in §4; `defaultRequire: 1` surfaces
  breakage at startup. Keep the hook list in one place and re-verify with `javap` on upgrades.
- **Netty handler placement vs. other mods** (ViaFabric, packet loggers): placement by handler name, as
  ReplayMod does; log the pipeline on install and expose `/inputreplay pipeline`.
- **GUI replay fidelity**: inventory clicks depend on window geometry and on server-side container
  state; best-effort in v1, with mismatches flagged via `SCREEN_OPEN` events and the drift monitor.
- **byName injection and screens**: a mapping whose key is unbound in the current client cannot be routed
  to a screen; the injector logs it and falls back to `setDown` only. Rare in practice (movement and
  action keys are always bound).
- **Writer throughput with full payloads**: chunk batches on join can reach tens of MB in a second; the
  bounded queue plus gzip on the writer thread should keep up, and `QUEUE_OVERFLOW` makes any loss
  visible rather than silent. If it becomes a problem, the per-class policy is the escape hatch.
