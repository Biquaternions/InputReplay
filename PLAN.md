# InputReplay — Design and Implementation Plan

Target: Fabric client mod for Minecraft **26.3** (unobfuscated Mojang names, Java 25, Fabric Loader 0.20.x, Fabric API 0.166.x).

Purpose: reproduce, with a real Minecraft client, the exact sequence of player inputs from a previous
server session, while capturing every packet in both directions, so that server-side physics and
movement handling can be analysed by comparing what the client sent/received across runs.

Everything below that names a Minecraft class or method was verified against the
`fabric-loom/26.3/minecraft-client.jar` in the local Gradle cache. ReplayMod (in `references/`) was used
as the reference for Netty packet capture and for the pitfalls of injecting into the client loop.

---

## 1. Summary of the recommended design

| Topic | Decision |
|---|---|
| What is recorded | (a) player inputs at Minecraft's own input-event boundary, (b) every inbound packet (raw bytes + decoded class name + `toString()` summary), (c) every outbound packet, (d) a per-tick snapshot of the local player's physics state |
| Frame of reference | the **client tick index** (counted from the first tick in which `ClientPacketListener.hasClientLoaded()` is true), plus a monotonic nanosecond clock and a per-packet sequence number |
| Replay unit | per tick: all key/mouse-button/scroll events and all mouse-look deltas recorded between the previous tick and this one are injected right before `Minecraft.tick()` runs |
| How inputs are injected | by calling the same public methods SDL calls (`KeyboardHandler.keyPress`, `MouseHandler.onButton`, `MouseHandler.onScroll`, …) and by calling `LocalPlayer.turn` for look; real hardware input is cancelled by mixins while a replay is active |
| Mode selection | a screen shown **before** connecting (intercept `ConnectScreen.startConnecting`), not after joining, so login/configuration packets are captured too |
| Verification | the replay also records; the analysis script diffs the serverbound `ServerboundMovePlayerPacket` stream and the per-tick snapshots of the two recordings to find the first tick where the runs diverge |
| File format | one zip (`.irr`) with `metadata.json` and several gzip'd binary streams; a standalone Python script converts to JSONL/CSV with filters, no Minecraft protocol knowledge required because the mod stores a decoded summary per packet |
| Packet replay (ReplayMod style) | kept as an optional later mode ("offline replay") that feeds recorded inbound packets at their recorded tick boundaries; not needed for the primary use case |

Why inputs, not packets, are the primary replay source: the whole point is to let the live client run
its own physics (entity pushing, knockback, collision, sprint/jump timing, …) and let the server react
to it. Replaying packets would reproduce the *display* of a session, not re-execute the simulation.

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
(attack / use / drop / inventory / hotbar, via `KeyMapping.isDown()` and `consumeClick()`) →
`GameRenderer.tick()` → `ClientLevel.tickEntities()` (runs `LocalPlayer.tick()` → `aiStep()` which calls
`ClientInput.tick()` and applies movement) → `LocalPlayer.sendChanges()` (sends
`ServerboundMovePlayerPacket.*`, sprint packets) → `ClientLevel.tick()` → particles →
`connection.send(ServerboundClientTickEndPacket)` → `Connection.tick()` → `KeyboardHandler.tick()`.

Consequences:

- Packets are applied at frame boundaries, ticks happen inside frames. The tick index at which a packet
  is applied is the natural "frame of reference" and can only be determined on the main thread.
- Mouse look is applied **per frame**, not per tick, and `MouseHandler.turnPlayer` scales deltas by
  sensitivity and by the real time since the last frame (`Blaze3D.getTime()`), with optional smoothing.
  Raw mouse deltas are therefore not reproducible; the *result* of `turnPlayer`, i.e. the arguments of
  `LocalPlayer.turn(dx, dy)`, is. `Entity.turn` multiplies by 0.15, clamps pitch to ±90 and adjusts
  `xRotO/yRotO`, so recording the `turn` calls themselves and replaying them in order reproduces the
  rotation exactly.
- The client sends `ServerboundClientTickEndPacket` at the end of every tick. This is a per-tick
  marker visible to the server and in the recorded outbound stream; it anchors ticks to packets.
- `LocalPlayer.tick()` returns early until `ClientPacketListener.hasClientLoaded()` is true. That tick is
  the right "tick 0" for a recording because before it the player cannot move anyway.

### 2.2 Input path

SDL events are pumped by `SDLEventHandler.pollEvents()` which calls exactly these public methods:

- `KeyboardHandler.keyPress(long window, int action, KeyEvent)` and `KeyboardHandler.textInput(long, String)`
  (`charTyped` is reached from there), 
- `MouseHandler.onButton(long window, MouseButtonInfo, int action)`,
- `MouseHandler.onScroll(long, double, double)`,
- `MouseHandler.onMove(long, double x, double y, double dx, double dy)`,
- `MouseHandler.onDrop(long, List<String>)`.

`keyPress` and `onButton` end in `KeyMapping.set(key, down)` / `KeyMapping.click(key)` (or in the open
`Screen`). `KeyboardInput.tick()` builds the `Input` record (forward/backward/left/right/jump/shift/sprint)
from `Options.keyUp/keyDown/keyLeft/keyRight/keyJump/keyShift/keySprint.isDown()`.
`Minecraft.handleKeybinds()` consumes `keyAttack`, `keyUse`, `keyPickItem`, `keyDrop`, `keyInventory`,
`keySwapOffhand`, `keyHotbarSlots[]`, `keyTogglePerspective`, `keySmoothCamera`, etc.

Recording at this boundary (instead of at the SDL layer) is faithful, independent of the user's key
bindings only if the same `options.txt` is used (see §9), and lets replay drive GUIs (chat, inventory)
for free because the same methods route to the open screen.

### 2.3 Network pipeline (`Connection`)

Client pipeline, head → tail: `timeout`, `splitter`, [`decompress`], `decoder`, [`unbundler`],
`prepender`, [`compress`], `encoder`, `hackfix`, `packet_handler` (the `Connection` itself).
Before a protocol is configured, `inbound_config` / `outbound_config` placeholders stand in for
`decoder` / `encoder`; `Connection.setupInboundProtocol` swaps in the real `PacketDecoder` and inserts a
`PacketBundlePacker` ("unbundler") per protocol phase. Compression handlers are inserted later by
`Connection.setupCompression`.

ReplayMod's approach, which we adopt: insert a **raw** inbound handler right before `decoder`
(sees the uncompressed `ByteBuf` = varint packet id + payload) and a **decoded** inbound handler right
after `decoder` (sees the `Packet<?>` object, gives class name and phase). The decoded handler
re-attaches itself after every protocol swap (ReplayMod did this by wrapping the decoder transition task;
here the equivalent hook is an `@Inject` at the tail of `Connection.setupInboundProtocol`).

Packets are handed to the main thread by `PacketProcessor.scheduleIfPossible(listener, packet)` and
executed by `PacketProcessor.ListenerAndPacket.handle()` from `processQueuedPackets()`; that is the hook
for stamping the "applied at tick N" field.

Outbound: `Connection.send(Packet)` → `sendPacket` → `doSendPacket` (main thread in practice, from
`ClientPacketListener.send`). A handler added with `pipeline.addBefore("encoder", …)` sees the encoded
but uncompressed `ByteBuf` on the way out.

### 2.4 Sources of non-determinism (and what we do about them)

| Source | Impact on movement | Mitigation |
|---|---|---|
| Mouse look is frame-timed | high | record `LocalPlayer.turn` arguments per tick and replay them at the tick boundary (§5.2) |
| Key events land in a particular tick depending on real time | high | record each event with the tick it preceded; replay before that tick |
| Packet arrival vs. tick boundary (network jitter) | high, but **this is the thing under test** | record the apply tick for every packet in both runs; the diff tool reports when a packet moved relative to the inputs. Optional offline mode (§10) removes it entirely |
| Server-side randomness, other players, mobs | high | use a controlled test server (flat world, no mobs, only the test client); out of scope for the mod |
| `Entity.random`, `ClientLevel.random`, particles, sounds | cosmetic only | ignore; optionally seed the local player's `RandomSource` with a fixed seed in both runs |
| Window size / GUI scale affects screen clicks | GUI only | stored in metadata, warn on mismatch; v1 scope is gameplay input, GUI clicks are best-effort |
| `options.txt` (sensitivity, key bindings, toggle sneak/sprint, auto-jump) | high | store a snapshot of the relevant options in metadata; refuse to replay on mismatch unless forced (sensitivity does not matter because `turn` arguments are post-sensitivity) |
| Tick rate changes (`TickRateManager`), frozen ticks | medium | recorded as events; replay is per tick so it stays aligned; sprint/freeze commands from the server are visible in the packet stream |
| Client catch-up (several ticks in one frame) | none | inputs are keyed by tick, not frame |

---

## 3. Modes and user flow

### 3.1 Arming a mode before connecting

Mixin `ConnectScreen.startConnecting(Screen parent, Minecraft, ServerAddress, ServerData, boolean, TransferState)`
at HEAD: if no mode is armed and the prompt is enabled, cancel, open `ModeSelectScreen`, and re-invoke
`startConnecting` with the chosen mode armed. Choices:

1. **Record** — proceeds to connect; a recording starts as soon as the `Connection` is created (so
   handshake/login/configuration packets are included), and tick 0 is set when the player is loaded.
2. **Replay** — opens `RecordingListScreen` (files in `.minecraft/inputreplay/recordings/`), shows
   metadata (server, date, duration in ticks, MC version, options hash). On pick: connect to the server
   the user clicked, arm the replayer, *and also record* (the replay run produces a second `.irr`, which
   is what the analysis compares against).
3. **Plain** — connect normally.

A `/inputreplay record|replay <file>|plain|off` client command (Fabric `fabric-command-api-v2`) and the
JVM property `-Dinputreplay.mode=replay -Dinputreplay.file=<path> -Dinputreplay.autoconnect=<host:port>`
arm the same state without the screen, so scripted/automated runs are possible (the client still needs a
window; use Xvfb on CI).

Also handle `TransferState` (server-initiated transfer to another host): keep the same session and log a
`TRANSFER` event; the connection object changes, so the pipeline hooks must be re-installed on the new
`Connection`.

### 3.2 Record mode

- Session starts in the `Connection` constructor path for the client (`Connection.connectToServer`
  returns the object; hook `Connection.configureSerialization` at RETURN to insert the raw/decoded
  handlers, or hook `ClientHandshakePacketListenerImpl` construction like ReplayMod does).
- HUD overlay (top-left): `REC  tick 1234  in 5678 pkts  out 2345 pkts`.
- Inputs, packets, and per-tick snapshots are appended to streams on a single writer thread (bounded
  queue, never blocks the render or Netty threads; if the queue overflows, log and set an `overflow`
  flag in metadata rather than stalling the game).
- Session ends on `Connection.channelInactive` (any disconnect, including kicks). Metadata gets
  `durationTicks`, `endReason`, and the file is finalized. A `.partial` suffix is used while writing so
  crashes leave an identifiable file.

### 3.3 Replay mode

- All hardware input is blocked by cancelling `KeyboardHandler.keyPress/textInput/charTyped`,
  `MouseHandler.onButton/onScroll/onMove` unless the call comes from the replayer (a thread-confined
  `injecting` flag). One escape hotkey (default F10, configurable) is let through and aborts the replay.
  Mouse grab is kept so the cursor doesn't fight the window.
- Before each client tick N (Fabric `ClientTickEvents.START_CLIENT_TICK`, or a mixin at HEAD of
  `Minecraft.tick`), the replayer:
  1. applies every recorded `turn(dx, dy)` for tick N in order,
  2. injects every recorded key/mouse-button/scroll/text event for tick N in order,
  3. sets the per-tick overrides that cannot be reproduced through events (see §5.4),
  4. then lets vanilla `tick()` run, which does `pick`, `handleKeybinds`, `KeyboardInput.tick`, movement.
- The replay also records, with `metadata.replayOf = <source file>`.
- HUD: `REPLAY  tick 1234 / 9876   Δpos 0.000  Δvel 0.000` where Δ is the distance between the live
  player's snapshot and the source recording's snapshot for the same tick, so drift is visible live.
- At the recorded end tick the replayer calls `Minecraft.disconnectFromWorld(Component)` (same path a
  user disconnect takes) and the session finalizes like a recording.
- Death / respawn / dimension change replace `Minecraft.player`; the replayer always re-reads
  `minecraft.player` and injects through `minecraft.keyboardHandler` / `mouseHandler`, which are stable.

---

## 4. Code layout

```
src/client/java/me/biquaternions/inputreplay/client/
  InputreplayClient.java            entrypoint: registers events, commands, key binding, config
  Config.java                       prompt on/off, abort key, packet filters, snapshot options
  session/
    SessionManager.java             state machine: IDLE → ARMED(mode) → RECORDING | REPLAYING → FINALIZING
    TickClock.java                  tick index, "player loaded" anchor, frame counter, nanoTime base
    PlayerSnapshotter.java          per-tick LocalPlayer state → SNAPSHOT record
  capture/
    InputCapture.java               receives events from the input mixins, tags with tick/frame/seq
    PacketCapture.java              Netty handlers (RawInbound, DecodedInbound, Outbound) + apply-tick stamping
    PacketSummarizer.java           class name + toString() + size, with per-class policy (full/summary/skip)
  replay/
    Replayer.java                   per-tick injection, lockout flag, end-of-recording handling, abort
    InputInjector.java              calls KeyboardHandler/MouseHandler/LocalPlayer.turn
    DriftMonitor.java               compares live snapshot with source snapshot, feeds HUD
  io/
    RecordingWriter.java            zip container, gzip streams, single writer thread
    RecordingReader.java            loads metadata + inputs + snapshots (packets are not needed to replay inputs)
    Records.java                    binary record definitions (see §6)
  gui/
    ModeSelectScreen.java, RecordingListScreen.java, SessionHud.java
  mixin/client/
    ConnectScreenMixin              arm/prompt before connecting
    ConnectionMixin                 pipeline install, protocol swap re-attach, send() capture, channelInactive
    PacketProcessorMixin            stamp apply-tick on ListenerAndPacket.handle()
    KeyboardHandlerMixin            record / block keyPress, textInput, charTyped
    MouseHandlerMixin               record / block onButton, onScroll, onMove; record turnPlayer→turn args
    MinecraftMixin                  tick HEAD/TAIL hooks (if not using Fabric events), frame counter
    LocalPlayerMixin                hasClientLoaded anchor, snapshot after aiStep
    ClientPacketListenerMixin       (optional) hooks for handleMovePlayer teleports, respawn, disconnect reason
src/main/resources/
  inputreplay.accesswidener         Connection.channel, Minecraft.clientTickCount, MouseHandler internals,
                                    LocalPlayer.sendPosition (if needed), Minecraft.pick
tools/
  irr_dump.py                       standalone converter / filter / diff (see §8)
  irr_format.md                     byte-level spec of the container
```

The `main` source set stays empty apart from the `ModInitializer`; the mod is client-only
(`"environment": "client"` is already set in `fabric.mod.json`).

---

## 5. Recording details

### 5.1 Clock and anchors

- `tick` = number of `Minecraft.tick()` calls since the anchor; `-1` before the anchor. The anchor is the
  first tick in which `minecraft.getConnection().hasClientLoaded()` is true and `minecraft.player != null`.
  Everything before that (login, configuration, chunk batch, initial teleport) is still recorded with
  negative or pre-anchor tick values so the analysis can see it.
- `frame` = frames since session start (incremented in `runTick`).
- `t` = `System.nanoTime() - sessionStartNanos`.
- Every record carries `(seq, t, tick, frame)`. `seq` is global across all streams so the full timeline
  can be merged by the tool.

### 5.2 Input stream (`inputs.bin`)

Record types (all tagged with the tick they **precede**, i.e. the tick that will first observe them):

| Type | Fields | Source hook |
|---|---|---|
| `KEY` | action (press/release/repeat), key, scancode, modifiers | `KeyboardHandler.keyPress` HEAD |
| `TEXT` | string | `KeyboardHandler.textInput` HEAD |
| `CHAR` | codepoint, modifiers | `KeyboardHandler.charTyped` HEAD (only if not already covered by TEXT) |
| `MOUSE_BUTTON` | button, action, modifiers, cursor x/y (window px) | `MouseHandler.onButton` HEAD |
| `SCROLL` | dx, dy | `MouseHandler.onScroll` HEAD |
| `CURSOR` | x, y (absolute, window px) | `MouseHandler.onMove` HEAD, only while a `Screen` is open or a button is held (GUI drags); coalesced per frame |
| `TURN` | dx, dy (the exact doubles passed to `LocalPlayer.turn`) | `@Redirect`/`@WrapOperation` of the `LocalPlayer.turn` call inside `MouseHandler.turnPlayer` |
| `TICK_MARK` | tick, frame, t | START of each `Minecraft.tick()` — delimits input groups, also records the number of frames since the last tick |
| `KEY_STATE` | bitset of all registered `KeyMapping.isDown()` + `Input` record from `player.input.keyPresses` | END of each tick, used for verification and as a fallback injection mode |

Hotkeys of the mod itself (abort, HUD toggle) are filtered out of the recording.

Rationale for recording `TURN` rather than `onMove` deltas: `turnPlayer` depends on sensitivity,
frame time, smooth-camera smoothing and the spyglass state; all of that is folded into the two doubles
handed to `turn`. Replaying those doubles in the same order before the same tick gives the same yaw/pitch
and the same `xRotO/yRotO`.

### 5.3 Packet streams (`packets_in.bin`, `packets_out.bin`)

| Field | Notes |
|---|---|
| `seq`, `t`, `tick`, `frame` | `t` = time received on the Netty thread (inbound) or time `send()` was called (outbound) |
| `applyTick`, `applyFrame`, `applySeq` | inbound only; stamped in `PacketProcessor.ListenerAndPacket.handle()` (matched to the receive record by packet object identity in an `IdentityHashMap`, since the same object is queued) |
| `phase` | HANDSHAKE / STATUS / LOGIN / CONFIGURATION / PLAY (from `ProtocolInfo.id()` of the active decoder/encoder) |
| `packetId` | varint id as on the wire |
| `className` | e.g. `ClientboundPlayerPositionPacket`; for bundles, the inner packets are recorded individually with a `bundleIndex` |
| `summary` | `packet.toString()` — most 26.x packets are Java records, so this already yields all fields; truncated to a configurable max (default 4 KiB) |
| `size` | wire size |
| `payload` | raw bytes (id + body) unless the per-class policy says `summary` or `skip` |

Default per-class policy (configurable in `Config`, and exported into metadata so the tool knows what
was dropped): full payload for everything except `ClientboundLevelChunkWithLightPacket`,
`ClientboundLightUpdatePacket`, `ClientboundChunksBiomesPacket`, `ClientboundSectionBlocksUpdatePacket`
(summary only, since summaries are enough to see *that* they arrived and when), and
`ClientboundCustomPayloadPacket` resource-pack blobs (size only). Keep-alive/ping are kept in full because
they are small and show the RTT.

Outbound packets are captured at `Connection.send(Packet, ChannelFutureListener, boolean)` HEAD
(main thread, so `tick` is exact) plus the encoded bytes from the outbound Netty handler, matched by
identity. `ServerboundMovePlayerPacket`, `ServerboundPlayerInputPacket`, `ServerboundPlayerCommandPacket`
and `ServerboundClientTickEndPacket` are the ones the analysis relies on.

### 5.4 Per-tick snapshot stream (`snapshots.bin`)

Taken at the END of every tick (after `sendChanges`) from `LocalPlayer`:

`tick, x, y, z, yRot, xRot, yHeadRot, vx, vy, vz (getDeltaMovement), onGround, horizontalCollision,
verticalCollision, isSprinting, isCrouching/pose ordinal, isSwimming, isFallFlying, fallDistance,
jumpRidingScale, sprintTriggerTime, vehicleId, input.keyPresses bits, input.moveVector (x, y),
lastSentInput bits, hitResult type/pos/entityId, selectedHotbarSlot, heldItem id`.

This stream is small (~100 bytes/tick) and is what the drift monitor and the diff tool use first. It is also
the fallback for the "state override" injection: if a replay must be as exact as possible and events
alone prove insufficient (e.g. a mod-added key binding), the replayer can force `player.input.keyPresses`
right after `KeyboardInput.tick()` and force yaw/pitch at tick start from the snapshot.

### 5.5 Events stream (`events.bin`)

Low-volume markers: `SESSION_START`, `CONNECTED`, `PLAYER_LOADED` (anchor), `RESPAWN`,
`DIMENSION_CHANGE`, `SCREEN_OPEN/CLOSE` (class name), `WINDOW_RESIZE`, `TICKRATE_CHANGE`,
`TRANSFER`, `DISCONNECT` (reason text), `REPLAY_ABORTED`, `QUEUE_OVERFLOW`.

---

## 6. Container format (`.irr`)

Zip file (`java.util.zip`, STORED or DEFLATED entries, streams written with their own gzip):

```
metadata.json       written at start, rewritten at end (ReplayMod does the same with metaData.json)
inputs.bin.gz
packets_in.bin.gz
packets_out.bin.gz
snapshots.bin.gz
events.bin.gz
options.txt         copy of the relevant options (key bindings, sensitivity, toggles, auto-jump, gui scale)
```

Binary record framing, identical for every stream, so `irr_dump.py` needs one reader:

```
u8   recordType
uvarint length          (bytes that follow)
uvarint seq
uvarint tNanos (delta from previous record in the same stream)
svarint tick
uvarint frame
...type-specific fields (little-endian; strings = uvarint length + UTF-8; bytes = uvarint length + raw)
```

Record type numbers, field order and versions are listed in `tools/irr_format.md`. `metadata.json`:

```json
{
  "formatVersion": 1,
  "generator": "InputReplay 1.0",
  "mcVersion": "26.3", "protocolVersion": 0, "loaderVersion": "0.20.1",
  "mode": "record" | "replay",
  "replayOf": "2026-11-15_10-02-11.irr",
  "server": {"address": "localhost:25565", "name": "test"},
  "startedAt": "2026-11-15T10:02:11Z", "endedAt": "...",
  "anchorSeq": 1234, "durationTicks": 9876, "endReason": "user_disconnect",
  "window": {"width": 1920, "height": 1080, "guiScale": 2, "fullscreen": false},
  "optionsHash": "sha256...", "packetPolicy": {"ClientboundLevelChunkWithLightPacket": "summary"},
  "modList": [{"id": "fabric-api", "version": "..."}],
  "overflow": false
}
```

Why not reuse ReplayMod's `.mcpr`: it stores only clientbound packets with millisecond timestamps and
relies on ReplayStudio/ViaVersion for decoding; we need inputs, outbound packets, tick indices and
snapshots, and we can get readable packet contents from `toString()` without a protocol library.

---

## 7. Replay engine details

### 7.1 Injection order within a tick

```
START_CLIENT_TICK (tick N):
  for each TURN(dx,dy) tagged N (in seq order):     player.turn(dx, dy)
  for each KEY/TEXT/CHAR/MOUSE_BUTTON/SCROLL/CURSOR tagged N (in seq order):
       injecting = true; keyboardHandler.keyPress(window, action, keyEvent) / mouseHandler.onButton(...) / ...; injecting = false
  vanilla Minecraft.tick():  pick → handleKeybinds → aiStep (KeyboardInput.tick) → sendChanges → …
END_CLIENT_TICK:
  DriftMonitor.compare(snapshotOfTick(N), sourceSnapshot(N))
```

`handleAccumulatedMovement` is neutralised during replay (`accumulatedDX/DY` are always 0 because
`onMove` is blocked), so no extra rotation sneaks in between ticks.

For `MOUSE_BUTTON` events while no screen is open, the cursor position is irrelevant; while a screen is
open, the recorded `CURSOR` events restore `MouseHandler.xpos/ypos` (access widener) before the click so
slot clicks hit the same widget. This only works at the same window size and GUI scale, hence the metadata
check.

### 7.2 Replay pacing

The replayer does not alter the timer: the client ticks at the normal rate and the server sees a normal
client. If the client falls behind (catch-up ticks), inputs are still consumed one tick at a time, so the
sequence stays identical; only wall-clock timing differs, which the packet apply-tick records expose.

A `pauseOnDrift` option (default off) can freeze the replay when Δpos exceeds a threshold so the user can
inspect the state in-game; useful while debugging the mod itself.

### 7.3 End conditions

- recorded `DISCONNECT` tick reached → `minecraft.disconnectFromWorld(...)`, finalize both files.
- server kicks the client earlier → finalize, `endReason = "server_disconnect_during_replay"`.
- abort key → finalize, `endReason = "aborted"`.
- `minecraft.player` becomes null for more than X ticks (e.g. stuck on a death screen the recording never
  had) → abort with reason.

---

## 8. Analysis tooling (`tools/irr_dump.py`)

Pure Python 3.11+, standard library only (`zipfile`, `gzip`, `json`, `argparse`, `csv`).

```
irr_dump.py info      file.irr                         # metadata, counts per stream, per-class packet histogram
irr_dump.py dump      file.irr [--stream inputs|in|out|snapshots|events|all]
                      [--tick 100:250] [--class ClientboundPlayerPositionPacket,Serverbound*]
                      [--exclude-class 'Clientbound*Chunk*,ClientboundLightUpdatePacket']
                      [--phase play] [--no-payload] [--format jsonl|csv|text] [-o out.jsonl]
irr_dump.py timeline  file.irr --tick 100:250          # merged, seq-ordered view of inputs + packets + snapshots
irr_dump.py diff      a.irr b.irr [--align tick]        # first divergent tick; per-tick table of Δpos/Δvel/Δrot,
                                                        # outbound MovePlayer mismatch, inbound packets that
                                                        # changed apply-tick between runs
irr_dump.py export-moveplayer file.irr -o moves.csv     # tick, x, y, z, yaw, pitch, onGround from ServerboundMovePlayerPacket
```

Filtering is by glob on class name, by phase, by tick range, by direction, and `--no-payload` drops raw
bytes but keeps the summary. The script never needs to decode Minecraft packets: it prints the summary
the mod stored; raw bytes are available as hex for anything that needs deeper decoding later.

An in-game `/inputreplay export <file> [filters]` command that runs the same conversion in Java is
optional; the Python tool is the primary one because it works on any machine without Minecraft.

---

## 9. Build and project housekeeping

- Git currently has `build.gradle` and `settings.gradle` staged as deleted while the `.kts` versions are
  untracked; commit the migration before starting.
- `build.gradle.kts`: add the Fabric API modules actually used (`fabric-api-base`,
  `fabric-lifecycle-events-v1`, `fabric-key-binding-api-v1`, `fabric-screen-api-v1`,
  `fabric-command-api-v2`, `fabric-networking-api-v1` for client-connection events) or keep the full
  `fabric-api` dependency (simplest); add `loom { accessWidenerPath.set(file("src/main/resources/inputreplay.accesswidener")) }`
  and the `"accessWidener"` entry in `fabric.mod.json`. Add MixinExtras (`@WrapOperation`) which Loom
  already ships via Fabric Loader.
- Mixin json `compatibilityLevel` is `JAVA_25`; keep `defaultRequire: 1` so a failed injection fails fast
  when Mojang renames something.
- Test server: a Fabric or vanilla 26.3 dedicated server under `run/server/` with `level-type=flat`,
  `spawn-monsters=false`, `online-mode=false`, `view-distance=4`, plus a `start-server.sh`.
  Loom's `runClient` with `-Dinputreplay.mode=... ` properties is the automation entry point.
- Store recordings in `<game dir>/inputreplay/recordings/`.

---

## 10. Alternatives considered and optional extensions

1. **Headless bot** (rejected, as the user already concluded): would require re-implementing client
   physics; any deviation invalidates the test.
2. **Packet replay only (ReplayMod style)**: reproduces what the client *saw*, not what it *did*; the
   server is not involved. Not the goal, but valuable as an **offline deterministic mode** later: feed
   `packets_in` through an `EmbeddedChannel` with the real `PacketDecoder`/`ClientPacketListener` while
   replaying inputs, applying each packet exactly before the tick it was applied in the original run. This
   isolates client-side determinism from server/network effects: if offline replay matches the original
   snapshots, any divergence in live replay is caused by the server or by timing. ReplayMod's
   `FullReplaySender` shows the required hacks (fake login, closing the level loading screen, timer
   control); budget this as its own phase.
3. **Recording raw SDL events**: more faithful in theory, but SDL structs are native, event timing is
   frame-bound, and replay would still have to go through the same handler methods. Not worth it.
4. **Recording key-mapping state per tick instead of events**: simpler and robust against GUI
   differences, but loses press+release inside one tick (`consumeClick` counts) and typed text. Kept as the
   `KEY_STATE` verification record and fallback override, not as the primary mechanism.
5. **Tick-locked look option** (recommended extension): a config flag that, in both record and replay,
   applies mouse deltas only at tick boundaries. Removes frame-rate dependence from the *recording* itself
   and makes rotation identical across machines with different FPS. Costs a little input latency.
6. **Server-side companion** (future): a tiny server mod that logs the server's view of the player per tick
   (position, velocity, `ServerGamePacketListenerImpl` move handling) with the same tick anchor via
   `ServerboundClientTickEndPacket`; would turn client/server comparison into a single diff. Not required
   for v1.
7. **Rendering off / speed-up**: not applicable; the server dictates real time.

---

## 11. Implementation phases

Each phase ends with something runnable and a check that proves it.

### Phase 0 — Scaffolding (½ day)
- Commit the Gradle Kotlin migration; add access widener, MixinExtras, config file, logger.
- `SessionManager` + `TickClock` with Fabric `ClientTickEvents` and `ClientPlayConnectionEvents`.
- Check: `runClient` starts, HUD shows tick count after joining the local test server.

### Phase 1 — Container + snapshots + events (1 day)
- `RecordingWriter`/`Reader`, record framing, metadata, writer thread with bounded queue.
- `PlayerSnapshotter` at END_CLIENT_TICK; `events.bin`; anchor on `hasClientLoaded()`.
- `tools/irr_dump.py info|dump` for snapshots/events.
- Check: join, walk around, disconnect → `.irr` produced; `irr_dump.py dump --stream snapshots` shows a
  smooth position trace; tick count matches HUD.

### Phase 2 — Packet capture (1–2 days)
- `ConnectionMixin`: install raw/decoded inbound handlers at `configureSerialization` RETURN; re-attach
  after `setupInboundProtocol`; outbound handler before `encoder`; `send()` HEAD capture.
- `PacketProcessorMixin`: apply-tick stamping.
- `PacketSummarizer` with per-class policy; summary truncation; bundle unpacking.
- Check: packet counts match the vanilla debug log (`-Dminecraft.network.logPackets` style spot
  check); every `ServerboundClientTickEndPacket` has `tick` equal to the enclosing snapshot tick;
  `ClientboundLevelChunkWithLightPacket` appears as summary only; file size for a 5-minute session is
  reasonable (< 50 MB with chunks excluded).

### Phase 3 — Input capture (1 day)
- `KeyboardHandlerMixin`, `MouseHandlerMixin` (`@WrapOperation` on the `turn` call), `CURSOR` coalescing,
  `KEY_STATE` at tick end, screen open/close events.
- Mode prompt (`ConnectScreenMixin` + `ModeSelectScreen`), record HUD, `/inputreplay` command,
  system properties.
- Check: `irr_dump.py timeline` shows key presses in the ticks before movement starts; `TURN` sums per
  tick match the yaw/pitch deltas between consecutive snapshots.

### Phase 4 — Replay engine (2–3 days)
- `Replayer`, `InputInjector`, lockout mixins with `injecting` flag and abort key, `RecordingListScreen`,
  metadata/options compatibility check, end-of-recording disconnect, `DriftMonitor` HUD, replay-run
  recording with `replayOf`.
- Check (the acceptance test of the project): on the flat test server, record 60 s of walking, sprint
  jumping, sneaking, turning and a few block interactions; replay; `irr_dump.py diff` reports zero
  divergence in `ServerboundMovePlayerPacket` and snapshots up to the tick where server-side effects
  (if any) differ. Repeat with knockback from a second client or `/summon` of a boat pushing the player,
  and confirm that the divergence tick coincides with a changed apply-tick of the corresponding packet.

### Phase 5 — Analysis polish (1 day)
- `diff`, `export-moveplayer`, CSV output, glob filters, `--no-payload`, `irr_format.md`.
- Optional in-game `/inputreplay export`.

### Phase 6 — Optional
- Tick-locked look option; offline deterministic replay mode; server-side companion mod; fixed-seed
  `RandomSource` for the local player.

Total for v1 (phases 0–5): roughly 7–9 working days.

---

## 12. Risks and open questions

- **Mojang renames between 26.3 snapshots/releases**: all hooks are listed in §4; `defaultRequire: 1`
  surfaces breakage at startup. Keep the hook list in one place to re-verify with `javap` on upgrades.
- **Netty handler placement vs. other mods** (e.g. ViaFabric, packet loggers): placement by handler name
  is the same strategy ReplayMod uses; log the pipeline on install and expose `/inputreplay pipeline`.
- **GUI replay fidelity**: inventory clicks depend on window geometry and on the server's container
  state; treat as best-effort in v1 and flag mismatches in the drift monitor via `SCREEN_OPEN` events.
- **Chat/commands with signatures**: `ServerboundChatPacket` carries timestamps/salts that will not match
  byte for byte; the diff tool compares those by class only (configurable "ignore classes" list).
- **Large recordings**: streams are gzip'd on the fly; with chunk payloads excluded, ~5–20 MB per
  10 minutes is expected. Memory stays bounded by the writer queue.
- **Decision needed**: should replay require the exact same `options.txt`/key bindings, or should the mod
  translate recorded physical keys to key-mapping names (`KeyMapping.getName()`) so that replays work with
  different bindings? Recording *both* (physical key and the resolved mapping names) costs nothing and
  leaves the choice to the replay side; recommended default: inject by mapping name when a binding
  mismatch is detected, otherwise by physical key.
- **Decision needed**: whether tick 0 should be the player-loaded tick (recommended) or the first PLAY
  packet. Player-loaded is more stable across servers with different login/chunk-batch timing.
