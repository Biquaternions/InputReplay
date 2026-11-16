# `.irr` container format (formatVersion 1)

A finished recording is a zip file. While recording (or after a crash) the same files live in a
directory named `<name>.irr.partial/`; `irr_dump.py` accepts either.

| Entry | Compression | Content |
|---|---|---|
| `metadata.json` | deflate | session metadata, written at start, updated at `PLAYER_LOADED`, rewritten at end |
| `options.txt` | deflate | key bindings and relevant options at session start |
| `inputs.bin.gz` | gzip, stored | input records |
| `packets_in.bin.gz` | gzip, stored | inbound packet records |
| `packets_out.bin.gz` | gzip, stored | outbound packet records |
| `snapshots.bin.gz` | gzip, stored | per-tick local player state |
| `events.bin.gz` | gzip, stored | session events |

Gzip streams are sync-flushed once per second, so a truncated stream decodes up to the last flush.

## Primitive encodings

| Name | Encoding |
|---|---|
| `u8` | one byte |
| `uvarint` | unsigned LEB128 (7 bits per byte, low first, high bit = continuation) |
| `svarint` | zig-zag (`(v << 1) ^ (v >> 63)`) then `uvarint` |
| `f32` / `f64` | IEEE 754, little-endian |
| `bytes` | `uvarint` length + raw bytes |
| `str` | `bytes` holding UTF-8 |
| `strs` | `uvarint` count + `str` × count |
| `bool` | `u8` 0/1 |

## Record framing (every stream)

```
u8       recordType
uvarint  length            bytes that follow, up to the end of the record
uvarint  seq               global sequence number across all streams of the session
svarint  dtNanos           t minus the t of the previous record in the same stream (first: t itself)
svarint  tick              raw tick (see below)
uvarint  frame             frames since session start
...      body              type-specific, below
```

`t` is `System.nanoTime()` relative to session start. Readers skip unknown types using `length`.

**Ticks.** The raw tick counts `Minecraft.tick()` calls since the session started (the session starts
when the connection is initiated, so login and configuration are included). Inside a tick it is the
index of that tick; between ticks it is the index of the next tick, i.e. the first tick that
observes an input. `metadata.anchorTick` is the raw index of the first tick where
`ClientPacketListener.hasClientLoaded()` was true and `Minecraft.player` was non-null; readers report
`tick - anchorTick` (negative before the player was loaded). If `anchorTick` is missing from the
metadata (crash before the rewrite), use `rawTick` of the `PLAYER_LOADED` event.

**Thread origin.** Records from the Netty thread (`PACKET_IN`, the bytes of `PACKET_OUT`) carry an
approximate tick read from the main thread's clock. `PACKET_APPLY` and the `PACKET_OUT` send stamp
are taken on the main thread and are exact. `seq` is unique but not monotonic within a stream for
records produced on different threads.

## Bodies

### `inputs.bin`

| Type | Name | Body |
|---|---|---|
| 1 | `KEY` | `svarint action` (1 press, 0 release, -1 repeat), `svarint key`, `svarint scancode`, `svarint modifiers`, `strs mappingNames` |
| 2 | `TEXT` | `str text` (from `KeyboardHandler.textInput`; the `charTyped` calls it makes are not recorded separately) |
| 3 | `CHAR` | `svarint codepoint` (a `charTyped` call not coming from `textInput`) |
| 4 | `MOUSE_BUTTON` | `svarint action`, `svarint button`, `svarint modifiers`, `f64 cursorX`, `f64 cursorY` (window px), `strs mappingNames` |
| 5 | `SCROLL` | `f64 dx`, `f64 dy` |
| 6 | `CURSOR` | `f64 x`, `f64 y`, `f64 dx`, `f64 dy`; only while a screen is open or a button is held; last move per frame |
| 7 | `TURN` | `f64 dx`, `f64 dy`: the exact arguments of `LocalPlayer.turn` (zero turns are not recorded) |
| 8 | `TICK_MARK` | `uvarint framesSinceLastTick`, `str screenClass` (empty if none); written at the start of each tick |
| 9 | `KEY_STATE` | `bytes bitset` (bit *i* = `metadata.keyMappingNames[i]` is down), `u8 inputBits`; written at the end of each tick |

`mappingNames` are the names of every `KeyMapping` bound to the physical key at the time, sorted.
`key` / `scancode` are the `KeyEvent.key()` / `KeyEvent.keycode()` values of the 26.3 client.

`inputBits`: bit 0 forward, 1 backward, 2 left, 3 right, 4 jump, 5 shift, 6 sprint.

### `packets_in.bin`

| Type | Name | Body |
|---|---|---|
| 20 | `PACKET_IN` | `uvarint pid`, `str phase`, `svarint packetId`, `str className`, `svarint bundleIndex`, `uvarint size`, `u8 payloadMode`, `str summary`, `bytes payload` |
| 21 | `PACKET_APPLY` | `uvarint pid`; its header (`seq`, `tick`, `frame`, `t`) is when the main thread handled the packet |

`PACKET_IN` is written when the packet is decoded (Netty thread); `PACKET_APPLY` when
`PacketProcessor.ListenerAndPacket.handle()` runs it. Bundle members (`bundleIndex >= 0`) are
recorded individually and each gets its own `PACKET_APPLY` when the bundle is handled. Packets
handled directly on the Netty thread (login phase, some keep-alives) and bundle delimiters have no
`PACKET_APPLY`.

### `packets_out.bin`

| Type | Name | Body |
|---|---|---|
| 22 | `PACKET_OUT` | `str phase`, `svarint packetId`, `str className`, `uvarint size`, `bool sendStamped`, `u8 payloadMode`, `str summary`, `bytes payload` |

The header is taken at `Connection.send` (main thread) when `sendStamped` is true, otherwise at
encode time.

### Common packet fields

- `phase`: `ConnectionProtocol.id()`: `handshake`, `status`, `login`, `configuration`, `play`.
- `className`: binary class name without the package, e.g. `ServerboundMovePlayerPacket$PosRot`.
- `size`: bytes of `payload` on the wire (uncompressed, unframed: varint id + body).
- `payloadMode`: 1 = `payload` holds those bytes; 0 = no payload (`summary` / `skip` policy).
- `summary`: `toString()` for records; for classes that do not override `toString()`, a reflective
  `Name[field=value, ...]` over the instance fields. Empty under the `skip` policy. Truncated at
  `metadata.summaryLimit` characters.

### `snapshots.bin`

| Type | Name | Body |
|---|---|---|
| 30 | `SNAPSHOT` | see below; written at the end of every tick while `Minecraft.player` exists |

```
f64 x, y, z
f32 yRot, xRot, yHeadRot
f64 vx, vy, vz                      getDeltaMovement()
uvarint flags                        bit 0 onGround, 1 horizontalCollision, 2 verticalCollision, 3 sprinting,
                                     4 swimming, 5 fallFlying, 6 shiftKeyDown, 7 crouching, 8 usingItem,
                                     9 minorHorizontalCollision
u8 pose                              Pose ordinal (STANDING, FALL_FLYING, SLEEPING, SWIMMING, SPIN_ATTACK, CROUCHING, ...)
f64 fallDistance
f32 jumpRidingScale
svarint sprintTriggerTime
svarint vehicleId                    -1 if none
u8 inputBits                         player.input.keyPresses
f32 moveX, moveY                     player.input.getMoveVector()
u8 lastSentInputBits
u8 hitType                           0 none, 1 MISS, 2 BLOCK, 3 ENTITY
f64 hitX, hitY, hitZ
svarint hitEntityId                  -1 if not an entity hit
svarint selectedSlot
str heldItem                         item id, empty if nothing held
```

### `events.bin`

| Type | Name | Body |
|---|---|---|
| 40 | `EVENT` | `str name`, `str detailsJson` |

Names: `SESSION_START`, `CONNECTED`, `PLAYER_LOADED` (`rawTick`, `anchorSeq`), `RESPAWN`,
`DIMENSION_CHANGE`, `SCREEN_OPEN` / `SCREEN_CLOSE` (`screen`), `WINDOW_RESIZE`, `TICKRATE_CHANGE`,
`TRANSFER`, `TRANSFER_OLD_CONNECTION_CLOSED`, `DISCONNECT` (`reason`, `byServer`), `QUEUE_OVERFLOW`,
`SESSION_END` (`reason`). Replay runs add `REPLAY_ARMED` (`file`, `bindingMode`, `durationTicks`),
`REPLAY_STARTED` (`heldAtStart`), `REPLAY_COMPLETE`, `REPLAY_ABORTED` (`reason`, `tick`),
`REPLAY_PAUSED_ON_DRIFT` (`tick`, `dpos`), `REPLAY_SUMMARY`, `BINDING_MISMATCH` (`mappings`) and
`OPTION_MISMATCH` (`options`), and in packet-sync mode `SYNC_DISABLED` (`reason`).

## `options.txt`

One entry per line: `key:<mapping name>=<key name>` (e.g. `key:key.forward=key.keyboard.w`) or
`option:<name>=<value>`. Lines starting with `#` are comments.
