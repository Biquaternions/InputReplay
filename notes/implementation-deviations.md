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
