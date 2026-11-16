# Deviations from PLAN_v2.md

Where the implementation departs from `PLAN_v2.md`, and why. Section numbers refer to the plan.
New entries are added per implementation step.

## Step 1: record side (phases 0–3)

### Packet summaries for classes without `toString()` (§5.3)

The plan assumes `summary = packet.toString()` lists all fields because "most 26.x packets are
records". Several packets the analysis depends on are not records and do not override `toString()`:
`ServerboundMovePlayerPacket` (and its `$Pos`, `$Rot`, `$PosRot`, `$StatusOnly` subclasses) and
`ServerboundPlayerCommandPacket`. Their summary would be `ServerboundMovePlayerPacket$PosRot@1a2b3c`.

**Change:** `PacketSummarizer` checks once per class whether `toString()` is inherited from
`Object`. If it is, the summary is built reflectively as `Name[field=value, ...]` over the
instance fields of the class hierarchy. Byte arrays are printed as `byte[n]`. The field lists are
cached per class. `irr_dump.py export-moveplayer` parses this format.

### Class names keep the outer class (§5.3)

`Class.getSimpleName()` gives `PosRot` for `ServerboundMovePlayerPacket$PosRot`, which is ambiguous.

**Change:** `className` is the binary name without the package (`ServerboundMovePlayerPacket$PosRot`).
Per-class policy keys and the tool's `--class` globs use the same form.

### Inbound/outbound capture at the codec, not with extra Netty handlers (§2.3, §5.3)

The plan places a raw handler before `decoder`, a decoded handler after it, re-attaches the
decoded handler after every `setupInboundProtocol`, and adds an outbound handler before `encoder`.

In 26.3 the decoder/encoder are swapped by `UnconfiguredPipelineHandler` tasks running on the Netty
event loop (`pipeline.replace`), and `PacketBundlePacker` is inserted with
`addAfter("decoder", "bundler")`. A handler placed after `decoder` therefore ends up after the
bundler and sees `BundlePacket`s, and re-attaching from the main thread after
`setupInboundProtocol` returns races with packets that are already flowing (auto-read is turned
back on inside the task).

**Change:** mixins on `PacketDecoder.decode` (HEAD copies the input frame, RETURN takes the decoded
packet) and `PacketEncoder.encode` (HEAD records the writer index, RETURN copies the written
bytes). Each call handles exactly one wire frame, so bytes and object pair up 1:1. The bytes are
decompressed and unframed (varint id + body), before bundling. There is no handler placement, no
re-attaching and no dependence on handler names. Bundle membership is tracked from the
`BundleDelimiterPacket`s seen by the decoder.

### Choosing the captured connection (§3.2)

The plan installs capture at `Connection.configureSerialization` RETURN. That static method also
runs for the server-list status pings, which are client connections too, so it cannot tell which
connection belongs to the session.

**Change:** a `@WrapOperation` on `Connection.initiateServerboundPlayConnection` inside
`ConnectScreen$1.run` (ConnectScreen's connect thread) hands over the exact `Connection` right
before the handshake is sent, so before any session packet flows. The `Channel` is taken from it
(or from `channelActive` if it is not set yet), and the codec mixins filter on that channel. This
also covers server transfers, which go through `ConnectScreen.startConnecting` again.

### Apply tick as a separate record (§5.3)

The plan puts `applyTick/applyFrame/applySeq` inside the inbound packet record. The record is
written at decode time on the Netty thread, when the apply tick is not known yet. Filling it in
later would mean holding every packet record (including multi-MB chunk payloads) in memory until
the main thread handles it, and anything still held would be lost in a crash.

**Change:** `PACKET_IN` (Netty thread, carries a per-session `pid`) and `PACKET_APPLY` (main
thread, body is just the `pid`; its header is the apply stamp). `irr_dump.py` joins them back into
`applyTick` / `applyFrame` / `applySeq`. Packets handled on the Netty thread (login phase, some
keep-alives) simply have no `PACKET_APPLY`. Stale identity-map entries are dropped after 1200 ticks.

### Signed time delta in the record header (§6)

The plan's framing has `uvarint tNanos (delta from previous record in the same stream)`. Records of
one stream can come from different threads (e.g. `PACKET_IN` from Netty and `PACKET_APPLY` from the
main thread), so their order in the writer queue does not always match their timestamps, and the
delta can be negative.

**Change:** `svarint dtNanos`. `seq` is likewise unique but not monotonic within a stream.

### Raw ticks stored, anchored by the reader (§5.1)

Following the plan's "provisional counter" option: every record stores the raw tick counted from
session start. `metadata.anchorTick` (also in the `PLAYER_LOADED` event, for crashed files) is
subtracted by readers. The session starts at `ConnectScreen.startConnecting`, so the connect screen,
login and configuration all get negative anchored ticks.

### Stream files and the `.partial` suffix (§3.2, §6)

A `ZipOutputStream` can only write one entry at a time, but five streams are written concurrently.

**Change:** while recording, each stream is a gzip file in `<name>.irr.partial/` (sync-flushed once
per second). At the end the writer thread assembles `<name>.irr` (gzip entries stored, metadata and
options deflated) and deletes the directory. After a crash the directory stays and `irr_dump.py`
reads it directly.

### Mixin accessors instead of an access widener (§4, §9)

The plan lists an `inputreplay.accesswidener`. Loom 1.19 uses the `classTweaker v1 official`
format for this. Every member needed so far (`KeyMapping.MAP/ALL/clickCount/key`,
`Connection.channel`, `LocalPlayer.sprintTriggerTime/lastSentInput`,
`MouseHandler.xpos/ypos/accumulatedDX/DY`) is reached through mixin `@Accessor` interfaces. These
fail at startup just like `defaultRequire: 1` injections and need no build configuration.

### Snapshots and key state from Fabric tick events (§4, §5.4)

`LocalPlayerMixin` was not needed. `ClientTickEvents.END_CLIENT_TICK` runs after
`LocalPlayer.sendChanges()`, so the `SNAPSHOT` and `KEY_STATE` records are written there.
`TICK_MARK`, the anchor check and the per-tick change detection (respawn, dimension, window size,
tick rate) run in `START_CLIENT_TICK`. Screen open/close come from a `Gui.setScreen` HEAD mixin
(in 26.3 screens live on `Minecraft.gui`, not `Minecraft.screen`).

### 26.3 input constants (§5.2)

`InputConstants.REPEAT` is `-1` (press 1, release 0), so the action is stored as `svarint`. Key
codes are the 26.3 SDL-based values (`KEY_F10 = 67`, `KEY_W = 26`), which sets the default abort key.
`CharacterEvent` carries only a codepoint (no modifiers), so `CHAR` has no modifier field.
`textInput` calls `charTyped` per codepoint, so nested `charTyped` calls are not recorded separately.

### Zero turns are not recorded (§5.2)

`handleAccumulatedMovement` calls `turnPlayer` every frame while the mouse is grabbed, mostly with
zero deltas. `TURN(0, 0)` is not recorded because `Entity.turn(0, 0)` changes no state.

### `skip` packet policy (§5.3)

The plan does not define what `skip` keeps. Under `skip` the record is still written (class, phase,
id, size, tick) with an empty summary and no payload, so packet counts and timing stay complete.

### End reasons (§3.2, §7.3)

`endReason` is `server_disconnect` if a `Clientbound*Disconnect*` packet was decoded,
`user_disconnect` if `Connection.disconnect` was called locally first, otherwise `connection_lost`.
Explicit reasons (`client_stopping`, `superseded`, `connect_failed`, and the replay reasons in step 2)
take precedence. A session whose connection was never established is discarded.

### Additions not in the plan

- `-Dinputreplay.auditMixins=true` runs `MixinEnvironment.audit()` at client start (dev check that
  every injection applies).
- `build.gradle.kts` passes `-Dinputreplay.*` properties given to Gradle on to `runClient`, which
  makes the automation entry point of §9 work as written.
- `/inputreplay prompt on|off` and `/inputreplay status`.
- `tools/start-test-server.sh` sets up `run/server/` as described in §9 (running it accepts the
  Minecraft EULA).

## Step 2: replay engine (phase 4)

### Input blocking starts at tick 0, not at connect (§3.3)

Blocking hardware input from the moment the replay is armed would also block the connect screen
(cancel button, Escape) during login and configuration. Replayed inputs only start at tick 0 anyway.

**Change:** `InputGate.replayActive` is switched on at the first anchored tick with a player, and
off when the replay completes, aborts or pauses.

### Pre-anchor inputs are not replayed; held keys are restored (§7.1)

Inputs with negative anchored ticks were made on the connect / loading screens. Replaying them
would click into screens that may not exist at the same moment in the new run.

**Change:** they are skipped. At tick 0 the replayer calls `KeyMapping.releaseAll()` (dropping
anything physically held), then sets down every mapping that was down in the recording's last
pre-anchor `KEY_STATE`. A key held through the world loading in the original run is therefore
held at tick 0 in the replay too; its release is a normal recorded event.

### Pause on focus loss is disabled during a replay (§2.4)

With `pauseOnLostFocus`, alt-tabbing during a replay opens the pause screen and breaks the run,
because the original run had no such screen.

**Change:** `Options.pauseOnLostFocus` is set to false for the duration of the replay and restored
afterwards.

### End of replay and abort (§7.3)

- The disconnect at the recorded end is requested at tick start but performed from the
  `Minecraft.runTick` HEAD hook, so it happens outside any tick (the same context as the pause-menu
  Disconnect button).
- The abort key (and `/inputreplay stop`) stops the replay and finalizes the replay recording, but
  **stays connected** so the state can be inspected. The plan only said "finalize".
- `pauseOnDrift` cannot freeze the server, so "pause" means: stop driving, release all keys and give
  control back to the user. The recording continues until disconnect with
  `endReason = "paused_on_drift"`.
- Disconnects not caused by the replayer end with `server_disconnect_during_replay` (a
  disconnect packet was seen) or `disconnect_during_replay`.

### By-name injection details (§7.1)

- With no screen open, events carrying mapping names call `setDown` and increment `clickCount`
  directly. The `KeyMapping.set`/`click` bytecode in 26.3 does exactly that. Because these calls
  bypass the input mixins, the original record is re-emitted into the replay recording unchanged.
- Some keys are handled inside `KeyboardHandler.keyPress` through `KeyMapping.matches(KeyEvent)`
  rather than `isDown`/`consumeClick` (screenshot, fullscreen, hide GUI, spectator shader). Under
  by-name injection with no screen open these do not fire. They are cosmetic and do not affect
  physics.
- Events recorded **without** mapping names are injected physically, except when that physical key
  is bound to a mapping in the current client while no screen is open. Injecting it would trigger
  a mapping the original run never triggered, so such events are skipped (logged once per key).
- Mouse buttons while a screen is open are always injected physically at the recorded cursor
  position (GUI clicks are positional).

### Turns are applied before the other events of a tick (§7.1)

As in the plan, all TURNs tagged N are applied before the key/mouse events tagged N, so within a
tick the replay order can differ from the original seq order. The replayer records each TURN
itself (it calls `LocalPlayer.turn` directly, bypassing the `turnPlayer` hook).

### Not implemented: the `KEY_STATE` state-override fallback (§5.4)

The plan offers forcing `player.input.keyPresses` from `KEY_STATE` "if events alone prove
insufficient". It is not implemented until a real replay shows it is needed; `diff` reports
key-state differences so that it can be judged.

### `irr_dump.py diff` moved up from phase 5 (§8, §11)

The phase 4 acceptance test needs it. Details:

- Default tick range: 0 to the shorter recording's duration minus one.
- Snapshots: exact by default (`--epsilon` to relax). Compares position, velocity, rotation, and
  flags, pose, input bits, slot, held item and vehicle.
- Outbound packets: per tick, in seq order, by class and summary. The default ignore list (and
  `--ignore-class`) compares those classes by class and tick only. `--strict` disables the list.
- Inputs: per tick as a multiset (see "Turns are applied before..."), ignoring `mappingNames`
  (binding-dependent) and the cursor position of clicks (only restored inside screens). Key states
  are compared per tick.
- Inbound packets are matched across runs by (class, occurrence index) in the play phase, and
  listed when their apply tick changed, closest to the first snapshot divergence first.
- Exit code 1 when snapshots or outbound packets diverge, so it can gate scripted runs.

### Recording list screen and commands (§3.1, §9)

- The list is a page of row buttons with Replay / Replay (physical) / Delete / Back. Delete asks for
  a second click. Crashed `.partial` recordings are listed too and can be replayed.
- The recording is loaded and validated when Replay is clicked (errors are shown on the screen), and
  the loaded reader is handed to the session. When the replay is armed by command or system property,
  it is loaded at connect time, and a failure is logged and the connection proceeds without a session.
- The list warns about option differences (toggle sneak/sprint/attack/use, auto-jump, GUI scale) and
  about a recording made on a different server address. The plan's "user can override" for option
  mismatches is simply proceeding despite the warning.
- `/inputreplay replay <file> [physical]` (file name in the recordings directory, or a quoted
  path) and `/inputreplay stop`.

### Additional metadata and events

`metadata.bindingMode`, `metadata.replayDurationTicks`, `metadata.replaySummary` (compared ticks, max
Δpos, first divergent tick, missing mappings). Events: `REPLAY_ARMED`, `REPLAY_STARTED`
(`heldAtStart`), `REPLAY_COMPLETE`, `REPLAY_ABORTED`, `REPLAY_PAUSED_ON_DRIFT`, `REPLAY_SUMMARY`,
`BINDING_MISMATCH`, `OPTION_MISMATCH`.

## Step 3: analysis polish (phase 5)

`diff` already came with step 2 (see above). `export-moveplayer`, CSV output, glob filters and
`irr_format.md` came with step 1. This step adds `strip`, the in-game export and tests.

### `strip` (§8)

- It rewrites records in place by byte offset: for each `PACKET_IN` / `PACKET_OUT` whose class
  matches `--exclude-class`, `payloadMode` becomes 0 and the payload becomes empty. Every other byte
  is copied unchanged (seq, time deltas, ticks, all other records), so `diff` between the original
  and the stripped copy reports no divergence.
- `--drop-summary` (not in the plan) also empties the summaries of those packets.
- The input may be a crashed `.partial` directory; the output is always a finished zip with complete
  gzip streams.
- `metadata.stripped` records the source file and the patterns. The tool refuses to write over its
  input.

### In-game `/inputreplay export` (§8, optional)

- `/inputreplay export <file> [stream] [classes]` writes `<name>.<stream>.jsonl` next to the
  recording on a background thread and reports in chat when done. `classes` is a comma-separated
  list of globs, and `!glob` excludes. There are no tick or phase filters; use `irr_dump.py` for
  those.
- The output has the same records and field names as `irr_dump.py dump --format jsonl` (apply stamps
  joined into `PACKET_IN`, `PACKET_APPLY` omitted, `all` ordered by seq). `f32` fields are written
  widened to double so the values match Python's exactly. Only `tMs` can differ in the last digit
  (rounding).
- The implementation (`io/RecordingExporter`) is pure Java with a `main`, so it also runs outside
  Minecraft. The tests use this.

### Tests (not in the plan)

`tools/test_irr_dump.py` (stdlib `unittest`) builds recordings with its own encoder, written from
`irr_format.md` rather than shared with the decoder. It covers field decoding, filters, CSV/text,
`export-moveplayer`, crashed directories with a truncated gzip stream, `diff` (identical, divergent,
epsilon, tick range, ignore list vs `--strict`, moved apply ticks) and `strip`. With
`IRR_JAVA_CP=<client classes>:<gson>:<slf4j>` it also runs the Java exporter on the same files and
requires record-for-record equality with `irr_dump.py dump`, which keeps the two decoders from
drifting apart.

## Step 4: fixes after the first real-server test

Analysis of the first record/replay pair (survival world, 824 ticks): both runs were bit-identical
through tick 181. At tick 181 the player landed and took fall damage. The server sends the
fall-damage `ClientboundDamageEventPacket` while handling the move, but the velocity reset
(`ClientboundSetEntityMotionPacket` for the player) only on its next tick's entity sync. In the
original it arrived 8 ms into client tick 181. In the replay the server's tick phase sat at the
client's tick boundary (its per-tick packets landed at 90–100% of each client tick versus 0–20% in
the original), so the packet arrived 0.6 ms after tick 182 started and was applied one tick late.
The replay is open loop, so the 0.09-block error then grew (wrong block faces, then wrong blocks,
missed jumps). The flower breaks at tick 213 were a consequence, not the cause.

### Simulated keyboard during replays (bug fix; extends §3.3)

26.3 polls the hardware directly via `InputConstants.isKeyDown` (`SDL_GetKeyboardState`):
`Minecraft.hasControlDown/hasShiftDown/hasAltDown` (drop whole stack with Ctrl+Q, pick block with
data, ...) and `KeyMapping.setAll()` (called on mouse grab and focus regain, re-reading every
mapping from the hardware). In the test, Ctrl+Q (`DROP_ALL_ITEMS`) replayed as `DROP_ITEM`. An
alt-tab during a replay would also release every replayed held key.

**Change:** `InputConstantsMixin` answers `isKeyDown` from `VirtualKeyboard` while a replay is
active. Physical mode tracks the recorded key codes. By-name mode tracks the current key of every
driven mapping, plus modifier keys (SDL scancodes 224–231) by their recorded code, since
`hasControlDown()` and friends test fixed codes. Keys held at tick 0 are included. The state is
cleared when the replay ends.

### `diff` noise (§8)

- Ignored classes (keep-alive, chat, ...) are no longer part of the per-tick outbound sequence; they
  are only counted per class. Previously a keep-alive landing one tick later was reported as the
  first outbound mismatch (tick 15 in the test).
- Cursor moves injected by the replayer are recorded one by one instead of being merged per frame,
  so the replay run's input stream matches the source. Replays recorded before this change still
  show `CURSOR` differences.
- New section "Player packets": the inbound packets that can change the player's physics, matched
  across runs by position in that sequence, with apply-tick changes flagged as "likely cause" when
  they are at the first divergence. The all-packets list is behind `--all-inbound` (matching by
  class + occurrence is meaningless with mobs around).

### Packet-sync replay mode (new; addresses §2.4 "packet arrival vs. tick boundary")

Opt-in: "Packet sync" toggle in the recording list, `/inputreplay replay <file> sync`, or
`-Dinputreplay.sync=true`.

- **Which packets:** `PlayerPackets` (shared rule with `irr_dump.py`). These are
  `SetEntityMotion`, `UpdateAttributes`, `SetEntityData`, `Update/RemoveMobEffect`,
  `TeleportEntity` and `EntityPositionSync` whose first field is the player's entity id (from
  `ClientboundLoginPacket.playerId`), plus `PlayerPosition`, `PlayerRotation`, `Explode`,
  `PlayerAbilities` and `Respawn`. Classification uses the class name and summary only, so the live
  run and the recording are classified identically.
- **Matching:** the k-th player packet of the replay corresponds to the k-th of the recording. A class
  mismatch, or more packets than the recording has, disables sync for the rest of the run
  (`SYNC_DISABLED` event, HUD).
- **Early packets** are held at `ListenerAndPacket.handle` (the call is cancelled and re-run at the
  start of the recorded tick, before inputs are injected). Player packets stay in order behind a held
  one. The apply stamp in the replay recording is taken when the packet actually runs.
- **Late packets:** before each client tick, if a packet the recording applied by that tick has not
  been handled, the call to `Minecraft.tick()` in `runTick` is skipped (`@WrapOperation`). Frames
  keep rendering, and the session clock and Fabric tick events do not advance. After
  `syncTimeoutMs` (default 3000) sync gives up.
- Packets the recording applied before tick 0, or never, are not gated.
- Trade-off: sync removes network/tick-phase timing as a source of divergence. That makes the client
  side reproducible when the server sends the same packets, but it hides timing sensitivity. That is
  why it is a toggle. `metadata.replaySync` and `replaySummary.packetSync` (held, stalls, stall ms,
  disabled reason) record what happened.
- Checked offline: the player-packet sequences of the test pair match class-for-class up to the
  point where the replay had diverged physically. A scripted harness covers late (tick stalls, then
  the packet applies before the recorded tick), early (held, released at the recorded tick), on-time,
  and class mismatch (sync disabled).
