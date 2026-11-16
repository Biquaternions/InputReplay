#!/usr/bin/env python3
"""Tests for irr_dump.py, using recordings built by an independent encoder that follows irr_format.md.

    python3 tools/test_irr_dump.py
    IRR_JAVA_CP=<client classes>:<gson.jar>:<slf4j.jar> python3 tools/test_irr_dump.py   # + Java parity

With IRR_JAVA_CP set, the Java exporter (RecordingExporter, the code behind /inputreplay export) is
run on the same files and its JSONL must match irr_dump.py dump record for record.
"""
from __future__ import annotations

import contextlib
import gzip
import io
import json
import os
import shutil
import struct
import subprocess
import sys
import tempfile
import unittest
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import irr_dump  # noqa: E402

# ----------------------------------------------------------------------------- encoder (per spec)


def uv(v: int) -> bytes:
    out = bytearray()
    while True:
        b = v & 0x7F
        v >>= 7
        out.append(b | 0x80 if v else b)
        if not v:
            return bytes(out)


def sv(v: int) -> bytes:
    return uv((v << 1) ^ (v >> 63) if v >= 0 else ((-v) << 1) - 1)


def s_(x: str) -> bytes:
    b = x.encode()
    return uv(len(b)) + b


def by(b: bytes) -> bytes:
    return uv(len(b)) + b


def ss(lst: list[str]) -> bytes:
    return uv(len(lst)) + b"".join(s_(x) for x in lst)


def f32(v: float) -> bytes:
    return struct.pack("<f", v)


def f64(v: float) -> bytes:
    return struct.pack("<d", v)


STREAM_FILES = {"inputs": "inputs.bin.gz", "in": "packets_in.bin.gz", "out": "packets_out.bin.gz",
                "snapshots": "snapshots.bin.gz", "events": "events.bin.gz"}


class Builder:
    def __init__(self, anchor: int = 3, names: list[str] | None = None):
        self.anchor = anchor
        self.names = names or ["key.forward", "key.jump", "key.sprint"]
        self.recs: dict[str, list] = {k: [] for k in STREAM_FILES}
        self.seq = 0

    def add(self, stream: str, rtype: int, raw_tick: int, fields: bytes, seq: int | None = None, t: int | None = None):
        if seq is None:
            seq = self.seq
            self.seq += 1
        t = seq * 1000 if t is None else t
        self.recs[stream].append((rtype, seq, t, raw_tick, raw_tick * 2, fields))
        return seq

    def snapshot(self, tick: int, x: float, vy: float = 0.0, flags: int = 1):
        self.add("snapshots", 30, tick + self.anchor,
                 f64(x) + f64(64.0) + f64(0.5) + f32(90.0) + f32(10.0) + f32(90.0) + f64(0.1) + f64(vy) + f64(0.0)
                 + uv(flags) + bytes([0]) + f64(0.0) + f32(0.0) + sv(0) + sv(-1) + bytes([1]) + f32(0.0) + f32(1.0)
                 + bytes([1]) + bytes([2]) + f64(1.0) + f64(2.0) + f64(3.0) + sv(-1) + sv(0) + s_("minecraft:stone"))

    def move(self, tick: int, x: float):
        summary = f"ServerboundMovePlayerPacket$PosRot[x={x}, y=64.0, z=0.5, yRot=90.0, xRot=10.0, onGround=true, " \
                  f"horizontalCollision=false, hasPos=true, hasRot=true]"
        self.add("out", 22, tick + self.anchor, s_("play") + sv(0x1D) + s_("ServerboundMovePlayerPacket$PosRot")
                 + uv(30) + bytes([1]) + bytes([1]) + s_(summary) + by(bytes(30)))

    def packet_in(self, tick: int, pid: int, cls: str, payload: bytes, apply_tick: int | None, bundle: int = -1):
        self.add("in", 20, tick + self.anchor, uv(pid) + s_("play") + sv(0x40) + s_(cls) + sv(bundle)
                 + uv(len(payload)) + bytes([1]) + s_(cls + "[...]") + by(payload))
        if apply_tick is not None:
            self.add("in", 21, apply_tick + self.anchor, uv(pid))

    def standard(self, x_shift: float = 0.0):
        b = self
        b.add("events", 40, b.anchor, s_("PLAYER_LOADED") + s_(json.dumps({"rawTick": b.anchor})))
        b.add("inputs", 9, b.anchor - 1, by(bytes([0b001])) + bytes([1]))  # forward held while loading
        for t in range(0, 6):
            b.add("inputs", 8, t + b.anchor, uv(1) + s_(""))
            if t == 1:
                b.add("inputs", 1, t + b.anchor, sv(1) + sv(44) + sv(44) + sv(0) + ss(["key.jump"]))
                b.add("inputs", 7, t + b.anchor, f64(1.5) + f64(-0.25))
            if t == 2:
                b.add("inputs", 1, t + b.anchor, sv(0) + sv(44) + sv(44) + sv(0) + ss(["key.jump"]))
                b.add("inputs", 4, t + b.anchor, sv(1) + sv(1) + sv(0) + f64(10.0) + f64(20.0) + ss(["key.attack"]))
                b.add("inputs", 2, t + b.anchor, s_("hé"))
            b.packet_in(t, 100 + t, "ClientboundSetEntityMotionPacket", bytes([0x40, t]), t)
            b.packet_in(t, 200 + t, "ClientboundLevelChunkWithLightPacket", bytes(range(50)), t)
            b.snapshot(t, 1.0 + t * 0.1 + (x_shift if t >= 4 else 0.0), vy=-0.08)
            b.move(t, 1.0 + t * 0.1 + (x_shift if t >= 4 else 0.0))
            b.add("out", 22, t + b.anchor, s_("play") + sv(0x0C) + s_("ServerboundKeepAlivePacket") + uv(9)
                  + bytes([1]) + bytes([1]) + s_(f"ServerboundKeepAlivePacket[id={t * 7 + int(x_shift * 1000)}]") + by(bytes(9)))
            b.add("inputs", 9, t + b.anchor, by(bytes([0b001 | (0b010 if t == 1 else 0)])) + bytes([1]))
        b.packet_in(-2, 1, "ClientboundLoginFinishedPacket", b"\x02", None)
        return b

    def metadata(self) -> dict:
        return {"formatVersion": 1, "mode": "record", "anchorTick": self.anchor, "durationTicks": 6,
                "keyMappingNames": self.names, "server": {"address": "localhost:25565", "name": "t"}}

    def streams(self) -> dict[str, bytes]:
        out = {}
        for k, recs in self.recs.items():
            buf = bytearray()
            last_t = 0
            for rtype, seq, t, tick, frame, fields in recs:
                body = uv(seq) + sv(t - last_t) + sv(tick) + uv(frame) + fields
                last_t = t
                buf += bytes([rtype]) + uv(len(body)) + body
            out[k] = bytes(buf)
        return out

    def write_zip(self, path: str, meta: dict | None = None) -> str:
        with zipfile.ZipFile(path, "w") as z:
            z.writestr("metadata.json", json.dumps(meta or self.metadata()))
            z.writestr("options.txt", "key:key.forward=key.keyboard.w\n")
            for k, data in self.streams().items():
                z.writestr(STREAM_FILES[k], gzip.compress(data), compress_type=zipfile.ZIP_STORED)
        return path

    def write_partial(self, path: str, truncate: bool = True) -> str:
        os.makedirs(path)
        with open(os.path.join(path, "metadata.json"), "w") as f:
            m = self.metadata()
            del m["anchorTick"], m["durationTicks"]  # as if crashed before the PLAYER_LOADED rewrite
            json.dump(m, f)
        for k, data in self.streams().items():
            z = gzip.compress(data)
            if truncate and k == "snapshots":
                z = z[: len(z) - 12]  # lose the gzip trailer and the tail of the deflate stream
            with open(os.path.join(path, STREAM_FILES[k]), "wb") as f:
                f.write(z)
        return path


def run(*argv: str) -> tuple[int, str]:
    buf = io.StringIO()
    with contextlib.redirect_stdout(buf):
        code = irr_dump.main(list(argv))
    return code, buf.getvalue()


def dump_jsonl(path: str, *extra: str) -> list[dict]:
    code, out = run("dump", path, "--stream", "all", *extra)
    assert code == 0
    return [json.loads(line) for line in out.splitlines() if line]


# ----------------------------------------------------------------------------- tests


class IrrDumpTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp(prefix="irr_test_")

    def tearDown(self):
        shutil.rmtree(self.dir, ignore_errors=True)

    def p(self, name: str) -> str:
        return os.path.join(self.dir, name)

    def test_decodes_every_field(self):
        f = Builder().standard().write_zip(self.p("a.irr"))
        rows = dump_jsonl(f)
        by_type = {}
        for r in rows:
            by_type.setdefault(r["type"], []).append(r)
        key = by_type["KEY"][0]
        self.assertEqual((key["tick"], key["action"], key["key"], key["mappingNames"]), (1, "press", 44, ["key.jump"]))
        self.assertEqual(by_type["KEY"][1]["action"], "release")
        self.assertEqual(by_type["TEXT"][0]["text"], "hé")
        mb = by_type["MOUSE_BUTTON"][0]
        self.assertEqual((mb["button"], mb["x"], mb["y"], mb["mappingNames"]), (1, 10.0, 20.0, ["key.attack"]))
        self.assertEqual((by_type["TURN"][0]["dx"], by_type["TURN"][0]["dy"]), (1.5, -0.25))
        ks = [r for r in by_type["KEY_STATE"] if r["tick"] == 1][0]
        self.assertEqual((ks["down"], ks["input"]), (["key.forward", "key.jump"], ["forward"]))
        snap = by_type["SNAPSHOT"][2]
        self.assertAlmostEqual(snap["x"], 1.2)
        self.assertEqual((snap["flags"], snap["pose"], snap["hitType"], snap["heldItem"]), (["onGround"], "STANDING", "BLOCK", "minecraft:stone"))
        pin = [r for r in by_type["PACKET_IN"] if r["className"] == "ClientboundSetEntityMotionPacket"][3]
        self.assertEqual((pin["tick"], pin["applyTick"], pin["payload"]), (3, 3, "4003"))
        login = [r for r in by_type["PACKET_IN"] if r["className"] == "ClientboundLoginFinishedPacket"][0]
        self.assertEqual((login["tick"], login["applyTick"]), (-2, None))
        self.assertNotIn("PACKET_APPLY", by_type)
        self.assertEqual(by_type["EVENT"][0]["details"], {"rawTick": 3})
        self.assertEqual([r["seq"] for r in rows], sorted(r["seq"] for r in rows))

    def test_filters(self):
        f = Builder().standard().write_zip(self.p("a.irr"))
        rows = dump_jsonl(f, "--class", "Clientbound*Motion*", "--tick", "2:3", "--no-payload")
        pk = [r for r in rows if r["stream"] in ("in", "out")]
        self.assertEqual([r["tick"] for r in pk], [2, 3])
        self.assertTrue(all("payload" not in r for r in pk))
        self.assertTrue(any(r["stream"] == "snapshots" for r in rows))  # non-packet streams unaffected by --class
        rows = dump_jsonl(f, "--exclude-class", "*Chunk*,ServerboundKeepAlive*")
        self.assertFalse(any("Chunk" in r.get("className", "") or "KeepAlive" in r.get("className", "") for r in rows))

    def test_csv_and_text(self):
        f = Builder().standard().write_zip(self.p("a.irr"))
        code, out = run("dump", f, "--stream", "snapshots", "--format", "csv")
        self.assertEqual(code, 0)
        lines = out.strip().splitlines()
        self.assertEqual(len(lines), 7)
        self.assertIn('"[""onGround""]"', lines[1])
        code, out = run("timeline", f, "--tick", "1:1")
        self.assertIn("KEY", out)
        self.assertIn("ClientboundSetEntityMotionPacket", out)

    def test_export_moveplayer(self):
        f = Builder().standard().write_zip(self.p("a.irr"))
        code, out = run("export-moveplayer", f)
        rows = out.strip().splitlines()
        self.assertEqual(rows[0].split(",")[:5], ["tick", "seq", "frame", "class", "x"])
        self.assertEqual(len(rows), 7)
        self.assertEqual(rows[3].split(",")[4], "1.2")

    def test_partial_directory_with_truncated_stream(self):
        f = Builder().standard().write_partial(self.p("c.irr.partial"))
        rec = irr_dump.Recording(f)
        self.assertEqual(rec.anchor, 3)  # from the PLAYER_LOADED event
        snaps = [r for r in rec.records("snapshots")]
        self.assertGreaterEqual(len(snaps), 1)
        self.assertLessEqual(len(snaps), 6)
        self.assertEqual(len([r for r in rec.records("inputs") if r.type == "TURN"]), 1)
        code, out = run("info", f)
        self.assertIn("PARTIAL", out)

    def test_diff_identical_and_divergent(self):
        a = Builder().standard().write_zip(self.p("a.irr"))
        b = Builder().standard().write_zip(self.p("b.irr"))
        code, out = run("diff", a, b)
        self.assertEqual(code, 0, out)
        self.assertIn("no divergence", out)
        c = Builder().standard(x_shift=0.001).write_zip(self.p("c.irr"))
        code, out = run("diff", a, c)
        self.assertEqual(code, 1)
        self.assertIn("FIRST DIVERGENCE at tick 4", out)
        self.assertIn("FIRST MISMATCH at tick 4", out)
        # keep-alive ids differ from tick 0 on, but the class-only comparison (default ignore list) means
        # the first outbound mismatch is the MovePlayer change at tick 4, and keep-alives show no summary
        self.assertIn("A: ServerboundKeepAlivePacket  \n", out)
        code, out = run("diff", a, c, "--tick", "0:3")
        self.assertEqual(code, 0, out)
        code, out = run("diff", a, c, "--epsilon", "0.01")
        self.assertIn("Snapshots", out)
        self.assertNotIn("FIRST DIVERGENCE", out)
        code, out = run("diff", a, c, "--tick", "0:3", "--strict")
        self.assertEqual(code, 1)
        self.assertIn("ServerboundKeepAlivePacket", out)

    def test_diff_apply_tick_moved(self):
        a = Builder().standard()
        b = Builder()
        b.add("events", 40, b.anchor, s_("PLAYER_LOADED") + s_(json.dumps({"rawTick": b.anchor})))
        for t in range(6):
            b.packet_in(t, 100 + t, "ClientboundSetEntityMotionPacket", bytes([0x40, t]), t + (1 if t == 2 else 0))
            b.snapshot(t, 1.0 + t * 0.1, vy=-0.08)
        fa, fb = a.write_zip(self.p("a.irr")), b.write_zip(self.p("b.irr"))
        code, out = run("diff", fa, fb)
        self.assertIn("ClientboundSetEntityMotionPacket#2: A applied @2, B @3 (+1)", out)

    def test_strip(self):
        a = Builder().standard().write_zip(self.p("a.irr"))
        s = self.p("s.irr")
        code, out = run("strip", a, "-o", s, "--exclude-class", "*Chunk*")
        self.assertEqual(code, 0)
        self.assertIn("Stripped 6 packet records", out)
        ra, rs = dump_jsonl(a), dump_jsonl(s)
        self.assertEqual(len(ra), len(rs))
        for x, y in zip(ra, rs):
            if "Chunk" in x.get("className", ""):
                self.assertEqual((y["payloadMode"], y["payload"], y["summary"]), (0, "", x["summary"]))
                y = dict(y, payloadMode=x["payloadMode"], payload=x["payload"])
            self.assertEqual(x, y)
        self.assertEqual(run("diff", a, s)[0], 0)
        meta = json.loads(zipfile.ZipFile(s).read("metadata.json"))
        self.assertEqual(meta["stripped"]["excludeClass"], ["*Chunk*"])
        code, _ = run("strip", a, "-o", self.p("s2.irr"), "--exclude-class", "*Chunk*", "--drop-summary")
        rows = [r for r in dump_jsonl(self.p("s2.irr")) if "Chunk" in r.get("className", "")]
        self.assertTrue(all(r["summary"] == "" for r in rows))
        with contextlib.redirect_stderr(io.StringIO()):
            self.assertEqual(run("strip", a, "-o", a, "--exclude-class", "*")[0], 2)

    def test_strip_partial_repairs_stream(self):
        f = Builder().standard().write_partial(self.p("c.irr.partial"))
        s = self.p("s.irr")
        self.assertEqual(run("strip", f, "-o", s, "--exclude-class", "*Chunk*")[0], 0)
        with zipfile.ZipFile(s) as z:
            gzip.decompress(z.read("snapshots.bin.gz"))  # a complete gzip stream again

    @unittest.skipUnless(os.environ.get("IRR_JAVA_CP"), "set IRR_JAVA_CP to run the Java parity check")
    def test_java_exporter_parity(self):
        for name, path in (("zip", Builder().standard().write_zip(self.p("a.irr"))),
                           ("partial", Builder().standard().write_partial(self.p("c.irr.partial")))):
            with self.subTest(name):
                out = self.p(name + ".jsonl")
                subprocess.run(["java", "-cp", os.environ["IRR_JAVA_CP"],
                                "me.biquaternions.inputreplay.client.io.RecordingExporter", path, out],
                               check=True, capture_output=True)
                with open(out, encoding="utf-8") as fh:
                    java = [json.loads(line) for line in fh if line.strip()]
                py = dump_jsonl(path)
                self.assertEqual(len(java), len(py))
                for j, p in zip(java, py):
                    j.pop("tMs"), p.pop("tMs")
                    self.assertEqual(j, p)


if __name__ == "__main__":
    unittest.main(verbosity=1)
