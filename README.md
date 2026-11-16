# InputReplay

A Fabric client mod for Minecraft 26.3 for testing server-side physics with a real, vanilla client.

Testers can't repeat the exact same inputs by hand, and bot frameworks don't run the vanilla client's
own movement code. InputReplay records everything you do on a server and replays those inputs later,
tick for tick, so you can compare how the server reacted in each run.

## What it does

When you join a server, the mod asks how to connect:

- **Record**: plays normally and records until you leave the server.
- **Replay**: pick a previous recording. The mod drives the player with the recorded inputs (your
  own keyboard and mouse are blocked; **F10** aborts), and records this run too, so it can be
  compared with the original.
- **Plain**: connects without recording.

Each recording (`.irr` file in `<game dir>/inputreplay/recordings/`) contains:

- your inputs (keys, mouse buttons, camera turns, typed text), tagged with the client tick they
  happened before;
- every packet received from and sent to the server, with a readable summary and the raw bytes;
- the player's position, velocity, rotation and state at the end of every tick;
- session events (screens, respawns, disconnects, ...).

Ticks are counted from **tick 0**, the first tick in which the player is loaded and can move.
Anything before it (login, world loading) has negative tick numbers.

A replay is "open loop": it sends the same inputs regardless of what happens. If one server packet
lands a tick later than in the original (network timing), the player ends up slightly elsewhere and
the difference grows from there. Turn on **Packet sync** in the recording list to apply packets
that affect the player (velocity, teleports, attributes, effects, explosions) at the same tick as in
the original. It pauses client ticks briefly when one arrives late.

The HUD (top left) shows the current tick, packet counts and file size, and during a replay the
drift from the original run.

Useful commands: `/inputreplay record | plain | replay <file> [physical] [sync] | stop | status | hud`.
For scripted runs:

```
gradle runClient -Dinputreplay.mode=replay -Dinputreplay.file=<file.irr> -Dinputreplay.autoconnect=localhost:25565
```

## Analysing recordings: `tools/irr_dump.py`

Plain Python 3.11+, no dependencies. It reads `.irr` files and also the `.irr.partial` folders left
behind if the game crashes.

```
python3 tools/irr_dump.py <command> <file.irr> [options]
```

| Command | What it does |
|---|---|
| `info` | metadata, record counts per stream, packet counts and sizes per class |
| `timeline` | everything that happened, in order, in a human-readable form (best place to start) |
| `dump` | export records as JSONL (default), CSV or text, for your own scripts or a spreadsheet |
| `diff a.irr b.irr` | compare two runs (e.g. original vs. replay) and find the first tick where they differ |
| `export-moveplayer` | CSV of the position/rotation the client sent to the server, one row per packet |
| `strip` | write a smaller copy with the raw bytes of chosen packet classes removed |

### Reading the output

`timeline` and `dump --format text` print one line per record:

```
 tick      seq  frame   stream    type          details
  181    27830 f1210    out       PACKET_OUT    [play] ServerboundMovePlayerPacket$PosRot (34 B)  ServerboundMovePlayerPacket$PosRot[x=28.59…, y=64.0, …]
  181    27832 f1210    snapshots SNAPSHOT      pos=(28.59539, 64.00000, 90.98719) vel=(0.00726, -0.07840, -0.09195) rot=(129.000, 34.050) STANDING onGround,…
  182    27880 f1211    in        PACKET_IN     [play] ClientboundSetEntityMotionPacket (8 B) applied@182  ClientboundSetEntityMotionPacket[id=108, movement=(0.0, -0.078…, 0.0)]
```

- **tick**: the client tick (0 = player loaded). For inputs, the tick they were applied before.
- **seq**: global order of all records; lines are sorted by it.
- **frame**: rendered frame number. Several frames can fall within one tick.
- **stream / type**: `inputs` (`KEY`, `TURN`, `MOUSE_BUTTON`, ...), `in` / `out` (packets),
  `snapshots` (player state at the end of the tick), `events`.
- For received packets, `applied@N` is the tick before which the game actually processed the packet.
  That can be later than when it arrived. `applied@None` means it was handled off the main thread
  (login phase, keep-alives).
- Packet lines show the protocol phase, class name, size in bytes, and the packet's fields.

`diff` prints a report per section: the first tick where the player's state diverged (with a small
table of position/velocity/rotation differences around it), the first differing outbound packet, any
input differences, and player packets that were applied at a different tick. Those are marked
`likely cause` when they are at the first divergence. It exits with code 1 if the runs diverged.

### Filtering packets

Packet filters take comma-separated class names, with `*` as a wildcard. Class names are shown in
`info` and in the timeline.

Chunk packets (`ClientboundLevelChunkWithLightPacket`) are by far the largest part of a recording and
are rarely interesting. Hide them, and other noise, with `--exclude-class`:

```
# timeline of ticks 150-200 without chunk, light and entity-movement noise
python3 tools/irr_dump.py timeline rec.irr --tick 150:200 \
    --exclude-class 'ClientboundLevelChunkWithLightPacket,ClientboundLightUpdatePacket,ClientboundMoveEntity*,ClientboundRotateHead*'

# only chunk packets, without their raw bytes
python3 tools/irr_dump.py dump rec.irr --stream in --class ClientboundLevelChunkWithLightPacket --no-payload --format text

# everything the client sent, as CSV
python3 tools/irr_dump.py dump rec.irr --stream out --class 'Serverbound*' --no-payload --format csv -o out.csv

# a copy without chunk payloads, for sharing
python3 tools/irr_dump.py strip rec.irr -o small.irr --exclude-class 'ClientboundLevelChunkWithLightPacket,ClientboundLightUpdatePacket'
```

Other filters: `--tick 100:250` (also `100:`, `:50` or a single tick), `--stream inputs|in|out|snapshots|events|all`,
`--phase play`, and `--no-payload` to leave out raw bytes. Run any command with `-h` for all options.

The byte-level file format is described in [`tools/irr_format.md`](tools/irr_format.md), and design
decisions in [`notes/implementation-deviations.md`](notes/implementation-deviations.md).
