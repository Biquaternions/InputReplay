#!/usr/bin/env python3
"""InputReplay recording (.irr) converter / filter / inspector.

Standard library only (Python 3.11+). Never decodes Minecraft packets: it prints the summary the
mod stored and, on request, the raw payload as hex. See irr_format.md for the byte layout.

Accepts a finished ``.irr`` zip or a crashed ``.irr.partial`` directory.

    irr_dump.py info      file.irr
    irr_dump.py dump      file.irr [--stream inputs|in|out|snapshots|events|all] [--tick 100:250]
                          [--class 'ClientboundPlayerPositionPacket,Serverbound*'] [--exclude-class ...]
                          [--phase play] [--no-payload] [--format jsonl|csv|text] [-o out.jsonl]
    irr_dump.py timeline  file.irr [--tick 100:250] [--class ...] [--exclude-class ...]
    irr_dump.py export-moveplayer file.irr [-o moves.csv]
"""
from __future__ import annotations

import argparse
import csv
import fnmatch
import gzip
import io
import json
import os
import struct
import sys
import zipfile
import zlib
from collections import Counter, defaultdict
from dataclasses import dataclass, field
from typing import Any, Iterable, Iterator

STREAMS = {
    "inputs": "inputs.bin.gz",
    "in": "packets_in.bin.gz",
    "out": "packets_out.bin.gz",
    "snapshots": "snapshots.bin.gz",
    "events": "events.bin.gz",
}

TYPE_NAMES = {
    1: "KEY", 2: "TEXT", 3: "CHAR", 4: "MOUSE_BUTTON", 5: "SCROLL", 6: "CURSOR", 7: "TURN",
    8: "TICK_MARK", 9: "KEY_STATE",
    20: "PACKET_IN", 21: "PACKET_APPLY", 22: "PACKET_OUT",
    30: "SNAPSHOT",
    40: "EVENT",
}

POSES = ["STANDING", "FALL_FLYING", "SLEEPING", "SWIMMING", "SPIN_ATTACK", "CROUCHING", "LONG_JUMPING",
         "DYING", "CROAKING", "USING_TONGUE", "SITTING", "ROARING", "SNIFFING", "EMERGING", "DIGGING",
         "SLIDING", "SHOOTING", "INHALING"]
HIT_TYPES = ["NONE", "MISS", "BLOCK", "ENTITY"]
INPUT_BITS = ["forward", "backward", "left", "right", "jump", "shift", "sprint"]
SNAP_FLAGS = ["onGround", "horizontalCollision", "verticalCollision", "sprinting", "swimming", "fallFlying",
              "shiftKeyDown", "crouching", "usingItem", "minorHorizontalCollision"]
KEY_ACTIONS = {1: "press", 0: "release", -1: "repeat"}


# --------------------------------------------------------------------------------------- decoding

class Reader:
    __slots__ = ("b", "p")

    def __init__(self, b: bytes, p: int = 0):
        self.b = b
        self.p = p

    def u8(self) -> int:
        v = self.b[self.p]
        self.p += 1
        return v

    def uvarint(self) -> int:
        shift = 0
        result = 0
        while True:
            byte = self.b[self.p]
            self.p += 1
            result |= (byte & 0x7F) << shift
            if not byte & 0x80:
                return result
            shift += 7

    def svarint(self) -> int:
        v = self.uvarint()
        return (v >> 1) ^ -(v & 1)

    def f32(self) -> float:
        v = struct.unpack_from("<f", self.b, self.p)[0]
        self.p += 4
        return v

    def f64(self) -> float:
        v = struct.unpack_from("<d", self.b, self.p)[0]
        self.p += 8
        return v

    def raw(self, n: int) -> bytes:
        v = self.b[self.p:self.p + n]
        self.p += n
        return v

    def bytes_(self) -> bytes:
        return self.raw(self.uvarint())

    def str_(self) -> str:
        return self.bytes_().decode("utf-8", "replace")

    def strs(self) -> list[str]:
        return [self.str_() for _ in range(self.uvarint())]


def bits_to_names(bits: int, names: list[str]) -> list[str]:
    return [n for i, n in enumerate(names) if bits & (1 << i)]


def decode_body(rtype: int, r: Reader, meta: dict) -> dict[str, Any]:
    if rtype == 1:
        a = r.svarint()
        return {"action": KEY_ACTIONS.get(a, a), "key": r.svarint(), "scancode": r.svarint(),
                "modifiers": r.svarint(), "mappingNames": r.strs()}
    if rtype == 2:
        return {"text": r.str_()}
    if rtype == 3:
        return {"codepoint": r.svarint()}
    if rtype == 4:
        a = r.svarint()
        return {"action": KEY_ACTIONS.get(a, a), "button": r.svarint(), "modifiers": r.svarint(),
                "x": r.f64(), "y": r.f64(), "mappingNames": r.strs()}
    if rtype == 5:
        return {"dx": r.f64(), "dy": r.f64()}
    if rtype == 6:
        return {"x": r.f64(), "y": r.f64(), "dx": r.f64(), "dy": r.f64()}
    if rtype == 7:
        return {"dx": r.f64(), "dy": r.f64()}
    if rtype == 8:
        return {"framesSinceLastTick": r.uvarint(), "screen": r.str_() or None}
    if rtype == 9:
        bits = r.bytes_()
        names = meta.get("keyMappingNames", [])
        down = [n for i, n in enumerate(names) if i // 8 < len(bits) and bits[i // 8] & (1 << (i % 8))]
        return {"down": down, "input": bits_to_names(r.u8(), INPUT_BITS)}
    if rtype == 20:
        d = {"pid": r.uvarint(), "phase": r.str_(), "packetId": r.svarint(), "className": r.str_(),
             "bundleIndex": r.svarint(), "size": r.uvarint()}
        d["payloadMode"] = r.u8()
        d["summary"] = r.str_()
        d["payload"] = r.bytes_()
        return d
    if rtype == 21:
        return {"pid": r.uvarint()}
    if rtype == 22:
        d = {"phase": r.str_(), "packetId": r.svarint(), "className": r.str_(), "size": r.uvarint(),
             "sendStamped": bool(r.u8())}
        d["payloadMode"] = r.u8()
        d["summary"] = r.str_()
        d["payload"] = r.bytes_()
        return d
    if rtype == 30:
        d = {"x": r.f64(), "y": r.f64(), "z": r.f64(), "yRot": r.f32(), "xRot": r.f32(), "yHeadRot": r.f32(),
             "vx": r.f64(), "vy": r.f64(), "vz": r.f64()}
        d["flags"] = bits_to_names(r.uvarint(), SNAP_FLAGS)
        pose = r.u8()
        d["pose"] = POSES[pose] if pose < len(POSES) else pose
        d["fallDistance"] = r.f64()
        d["jumpRidingScale"] = r.f32()
        d["sprintTriggerTime"] = r.svarint()
        d["vehicleId"] = r.svarint()
        d["input"] = bits_to_names(r.u8(), INPUT_BITS)
        d["moveX"] = r.f32()
        d["moveY"] = r.f32()
        d["lastSentInput"] = bits_to_names(r.u8(), INPUT_BITS)
        ht = r.u8()
        d["hitType"] = HIT_TYPES[ht] if ht < len(HIT_TYPES) else ht
        d["hitX"], d["hitY"], d["hitZ"] = r.f64(), r.f64(), r.f64()
        d["hitEntityId"] = r.svarint()
        d["selectedSlot"] = r.svarint()
        d["heldItem"] = r.str_()
        return d
    if rtype == 40:
        name = r.str_()
        try:
            details = json.loads(r.str_())
        except json.JSONDecodeError:
            details = {}
        return {"name": name, "details": details}
    return {}


@dataclass
class Rec:
    stream: str
    type: str
    seq: int
    t: int  # nanoseconds since session start
    raw_tick: int
    frame: int
    data: dict[str, Any]
    tick: int = 0  # anchored


def gunzip_tolerant(data: bytes) -> bytes:
    """Decompresses a gzip stream, returning whatever is decodable if it is truncated (crash)."""
    try:
        return gzip.decompress(data)
    except (EOFError, OSError, zlib.error):
        d = zlib.decompressobj(wbits=31)
        out = io.BytesIO()
        try:
            out.write(d.decompress(data))
        except zlib.error:
            pass
        return out.getvalue()


class Recording:
    def __init__(self, path: str):
        self.path = path
        self.is_dir = os.path.isdir(path)
        self._zip = None if self.is_dir else zipfile.ZipFile(path)
        self.meta: dict = json.loads(self._read("metadata.json") or b"{}")
        self.partial = self.is_dir
        self._anchor: int | None = self.meta.get("anchorTick")
        if self._anchor is None:
            for rec in self._iter_raw("events"):
                if rec.data.get("name") == "PLAYER_LOADED":
                    self._anchor = rec.data["details"].get("rawTick")
                    break
        self.anchor = self._anchor if self._anchor is not None else 0
        self._apply: dict[int, Rec] | None = None

    def _read(self, name: str) -> bytes | None:
        if self.is_dir:
            p = os.path.join(self.path, name)
            if not os.path.exists(p):
                return None
            with open(p, "rb") as f:
                return f.read()
        try:
            return self._zip.read(name)
        except KeyError:
            return None

    def options_text(self) -> str:
        return (self._read("options.txt") or b"").decode("utf-8", "replace")

    def _iter_raw(self, stream: str) -> Iterator[Rec]:
        data = self._read(STREAMS[stream])
        if not data:
            return
        buf = gunzip_tolerant(data)
        r = Reader(buf)
        t = 0
        n = len(buf)
        while r.p < n:
            start = r.p
            try:
                rtype = r.u8()
                length = r.uvarint()
                end = r.p + length
                if end > n:
                    break  # truncated tail
                body = Reader(buf, r.p)
                seq = body.uvarint()
                t += body.svarint()
                raw_tick = body.svarint()
                frame = body.uvarint()
                try:
                    data_ = decode_body(rtype, body, self.meta)
                except (IndexError, struct.error):
                    data_ = {"error": "undecodable body"}
                r.p = end
            except IndexError:
                r.p = start
                break
            yield Rec(stream, TYPE_NAMES.get(rtype, f"TYPE_{rtype}"), seq, t, raw_tick, frame, data_)

    def records(self, stream: str) -> Iterator[Rec]:
        for rec in self._iter_raw(stream):
            rec.tick = rec.raw_tick - self.anchor
            yield rec

    def apply_index(self) -> dict[int, Rec]:
        if self._apply is None:
            self._apply = {}
            for rec in self.records("in"):
                if rec.type == "PACKET_APPLY":
                    self._apply[rec.data["pid"]] = rec
        return self._apply

    def packets_in(self) -> Iterator[Rec]:
        """PACKET_IN records with applyTick/applyFrame/applySeq joined from PACKET_APPLY."""
        applies = self.apply_index()
        for rec in self.records("in"):
            if rec.type != "PACKET_IN":
                continue
            a = applies.get(rec.data["pid"])
            rec.data["applyTick"] = a.tick if a else None
            rec.data["applyFrame"] = a.frame if a else None
            rec.data["applySeq"] = a.seq if a else None
            yield rec


# --------------------------------------------------------------------------------------- filters

def parse_tick_range(s: str | None) -> tuple[int | None, int | None]:
    if not s:
        return None, None
    if ":" not in s:
        v = int(s)
        return v, v
    a, b = s.split(":", 1)
    return (int(a) if a else None), (int(b) if b else None)


def globs(s: str | None) -> list[str]:
    return [g.strip() for g in s.split(",") if g.strip()] if s else []


@dataclass
class Filter:
    tick_lo: int | None = None
    tick_hi: int | None = None
    include: list[str] = field(default_factory=list)
    exclude: list[str] = field(default_factory=list)
    phase: str | None = None

    @staticmethod
    def from_args(a) -> "Filter":
        lo, hi = parse_tick_range(getattr(a, "tick", None))
        return Filter(lo, hi, globs(getattr(a, "cls", None)), globs(getattr(a, "exclude_cls", None)),
                      getattr(a, "phase", None))

    def ok(self, rec: Rec) -> bool:
        tick = rec.tick  # for PACKET_IN: the receive tick (applyTick is a separate field)
        if self.tick_lo is not None and tick < self.tick_lo:
            return False
        if self.tick_hi is not None and tick > self.tick_hi:
            return False
        cls = rec.data.get("className")
        if cls is not None:
            if self.include and not any(fnmatch.fnmatchcase(cls, g) for g in self.include):
                return False
            if self.exclude and any(fnmatch.fnmatchcase(cls, g) for g in self.exclude):
                return False
            if self.phase and rec.data.get("phase") != self.phase:
                return False
        elif self.include and rec.stream in ("in", "out"):
            return False
        return True


def record_dict(rec: Rec, payload: bool) -> dict[str, Any]:
    d = {"stream": rec.stream, "type": rec.type, "seq": rec.seq, "tick": rec.tick, "rawTick": rec.raw_tick,
         "frame": rec.frame, "tMs": round(rec.t / 1e6, 3)}
    for k, v in rec.data.items():
        if k == "payload":
            if payload:
                d["payload"] = v.hex()
            continue
        d[k] = v
    return d


def iter_stream(rec: Recording, stream: str) -> Iterator[Rec]:
    if stream == "in":
        return rec.packets_in()
    return rec.records(stream)


# --------------------------------------------------------------------------------------- commands

def cmd_info(a) -> int:
    rec = Recording(a.file)
    m = rec.meta
    print(f"File:        {a.file}{'  (PARTIAL: crashed or still recording)' if rec.partial else ''}")
    for k in ("mode", "replayOf", "bindingMode", "mcVersion", "protocolVersion", "generator", "startedAt",
              "endedAt", "endReason", "anchorTick", "durationTicks", "overflow", "droppedRecords"):
        if k in m:
            print(f"{k + ':':13}{m[k]}")
    if "server" in m:
        print(f"{'server:':13}{m['server'].get('address')} ({m['server'].get('name')})")
    if "window" in m:
        w = m["window"]
        print(f"{'window:':13}{w.get('width')}x{w.get('height')} guiScale={w.get('guiScale')} fullscreen={w.get('fullscreen')}")
    print()
    for s in STREAMS:
        types = Counter()
        lo = hi = None
        for r in rec.records(s):
            types[r.type] += 1
            lo = r.tick if lo is None else min(lo, r.tick)
            hi = r.tick if hi is None else max(hi, r.tick)
        span = f"ticks {lo}..{hi}" if lo is not None else "empty"
        print(f"{s:10} {sum(types.values()):9} records  {span}   " + ", ".join(f"{k}={v}" for k, v in types.most_common()))
    print()
    for s, title in (("in", "Inbound"), ("out", "Outbound")):
        counts: Counter = Counter()
        sizes: Counter = Counter()
        not_applied = 0
        for r in iter_stream(rec, s):
            if r.type not in ("PACKET_IN", "PACKET_OUT"):
                continue
            counts[r.data["className"]] += 1
            sizes[r.data["className"]] += r.data["size"]
            if s == "in" and r.data.get("applyTick") is None:
                not_applied += 1
        print(f"{title} packets by class ({sum(counts.values())} total, {sum(sizes.values()) / 1e6:.2f} MB on the wire)")
        for cls, n in counts.most_common(a.top):
            print(f"  {n:8}  {sizes[cls] / 1e3:11.1f} kB  {cls}")
        if s == "in":
            print(f"  ({not_applied} inbound packets have no apply stamp: handled on the network thread or never applied)")
        print()
    return 0


def open_out(path: str | None):
    return open(path, "w", newline="", encoding="utf-8") if path else sys.stdout


def cmd_dump(a) -> int:
    rec = Recording(a.file)
    flt = Filter.from_args(a)
    streams = list(STREAMS) if a.stream == "all" else [a.stream]
    rows: Iterable[dict] = (record_dict(r, not a.no_payload)
                            for s in streams for r in iter_stream(rec, s)
                            if flt.ok(r) and (a.raw_apply or r.type != "PACKET_APPLY"))
    if a.stream == "all":
        rows = sorted(rows, key=lambda d: d["seq"])
    out = open_out(a.output)
    try:
        if a.format == "jsonl":
            for d in rows:
                out.write(json.dumps(d, ensure_ascii=False) + "\n")
        elif a.format == "csv":
            rows = list(rows)
            keys: list[str] = []
            for d in rows:
                for k in d:
                    if k not in keys:
                        keys.append(k)
            w = csv.DictWriter(out, fieldnames=keys, extrasaction="ignore")
            w.writeheader()
            for d in rows:
                w.writerow({k: json.dumps(v) if isinstance(v, (list, dict)) else v for k, v in d.items()})
        else:
            for d in rows:
                out.write(text_line(d) + "\n")
    finally:
        if out is not sys.stdout:
            out.close()
    return 0


def text_line(d: dict) -> str:
    head = f"{d['tick']:>7} {d['seq']:>9} f{d['frame']:<7} {d['stream']:9} {d['type']:13}"
    t = d["type"]
    if t in ("PACKET_IN", "PACKET_OUT"):
        extra = f" applied@{d['applyTick']}" if t == "PACKET_IN" else ""
        summary = d.get("summary", "")
        if len(summary) > 160:
            summary = summary[:160] + "…"
        return f"{head} [{d['phase']}] {d['className']} ({d['size']} B){extra}  {summary}"
    if t == "EVENT":
        return f"{head} {d['name']} {json.dumps(d['details'], ensure_ascii=False)}"
    if t == "SNAPSHOT":
        return (f"{head} pos=({d['x']:.5f}, {d['y']:.5f}, {d['z']:.5f}) vel=({d['vx']:.5f}, {d['vy']:.5f}, {d['vz']:.5f}) "
                f"rot=({d['yRot']:.3f}, {d['xRot']:.3f}) {d['pose']} {','.join(d['flags'])} in={','.join(d['input'])}")
    skip = {"stream", "type", "seq", "tick", "rawTick", "frame", "tMs"}
    return head + " " + " ".join(f"{k}={v}" for k, v in d.items() if k not in skip)


def cmd_timeline(a) -> int:
    rec = Recording(a.file)
    flt = Filter.from_args(a)
    rows = [record_dict(r, False) for s in STREAMS for r in iter_stream(rec, s)
            if flt.ok(r) and r.type not in ("PACKET_APPLY",) and (a.key_state or r.type != "KEY_STATE")]
    rows.sort(key=lambda d: d["seq"])
    out = open_out(a.output)
    try:
        last_tick = None
        for d in rows:
            if d["type"] == "TICK_MARK":
                if not a.tick_marks:
                    continue
            if last_tick is not None and d["tick"] != last_tick and not a.tick_marks:
                out.write("\n")
            last_tick = d["tick"]
            out.write(text_line(d) + "\n")
    finally:
        if out is not sys.stdout:
            out.close()
    return 0


def parse_reflective_summary(s: str) -> dict[str, str]:
    """Parses 'Name[a=1, b=2]' / record 'Name[a=1, b=2]' summaries into a flat dict of strings."""
    lb, rb = s.find("["), s.rfind("]")
    if lb < 0 or rb < lb:
        return {}
    out: dict[str, str] = {}
    depth = 0
    cur = ""
    for ch in s[lb + 1:rb] + ",":
        if ch in "[({":
            depth += 1
        elif ch in "])}":
            depth -= 1
        if ch == "," and depth == 0:
            if "=" in cur:
                k, v = cur.split("=", 1)
                out[k.strip()] = v.strip()
            cur = ""
        else:
            cur += ch
    return out


def cmd_export_moveplayer(a) -> int:
    rec = Recording(a.file)
    out = open_out(a.output)
    w = csv.writer(out)
    w.writerow(["tick", "seq", "frame", "class", "x", "y", "z", "yRot", "xRot", "onGround", "horizontalCollision",
                "hasPos", "hasRot"])
    try:
        for r in rec.records("out"):
            if r.type != "PACKET_OUT" or not r.data["className"].startswith("ServerboundMovePlayerPacket"):
                continue
            f = parse_reflective_summary(r.data["summary"])
            w.writerow([r.tick, r.seq, r.frame, r.data["className"]] +
                       [f.get(k, "") for k in ("x", "y", "z", "yRot", "xRot", "onGround", "horizontalCollision",
                                               "hasPos", "hasRot")])
    finally:
        if out is not sys.stdout:
            out.close()
    return 0


def add_filters(p: argparse.ArgumentParser) -> None:
    p.add_argument("--tick", help="anchored tick range, e.g. 100:250, 100:, :50 or 42")
    p.add_argument("--class", dest="cls", help="comma-separated class-name globs to include (packets)")
    p.add_argument("--exclude-class", dest="exclude_cls", help="comma-separated class-name globs to exclude")
    p.add_argument("--phase", help="protocol phase (handshake, login, configuration, play)")
    p.add_argument("-o", "--output", help="output file (default: stdout)")


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)

    p = sub.add_parser("info", help="metadata, record counts, per-class packet histogram")
    p.add_argument("file")
    p.add_argument("--top", type=int, default=40, help="classes to list per direction")
    p.set_defaults(fn=cmd_info)

    p = sub.add_parser("dump", help="convert one or all streams to jsonl / csv / text")
    p.add_argument("file")
    p.add_argument("--stream", default="all", choices=list(STREAMS) + ["all"])
    p.add_argument("--no-payload", action="store_true", help="omit raw packet bytes")
    p.add_argument("--raw-apply", action="store_true", help="also output PACKET_APPLY records")
    p.add_argument("--format", default="jsonl", choices=["jsonl", "csv", "text"])
    add_filters(p)
    p.set_defaults(fn=cmd_dump)

    p = sub.add_parser("timeline", help="merged, seq-ordered human-readable view")
    p.add_argument("file")
    p.add_argument("--tick-marks", action="store_true", help="show TICK_MARK records")
    p.add_argument("--key-state", action="store_true", help="show KEY_STATE records")
    add_filters(p)
    p.set_defaults(fn=cmd_timeline)

    p = sub.add_parser("export-moveplayer", help="CSV of ServerboundMovePlayerPacket fields per tick")
    p.add_argument("file")
    p.add_argument("-o", "--output")
    p.set_defaults(fn=cmd_export_moveplayer)

    a = ap.parse_args(argv)
    try:
        return a.fn(a)
    except BrokenPipeError:
        return 0


if __name__ == "__main__":
    sys.exit(main())
