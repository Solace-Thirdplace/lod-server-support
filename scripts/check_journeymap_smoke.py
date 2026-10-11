#!/usr/bin/env python3
"""Verdict for the JourneyMap map-ingestion live smoke (map-ingestion plan §6.4 L1).

Reads what JourneyMap itself wrote — the day-map region PNGs under the soak client's
``journeymap/data/mp/<server>/<dimension>/day/`` — and checks, per chunk, that:

* every chunk inside the LOD disc (Euclidean radius ``lod - margin`` around the player's
  chunk) is fully opaque: LSS columns reached JourneyMap's map, not just its live radius;
* each marker the scenario timeline built (its ``fill`` commands, one source of truth)
  shows the marker's colour class at the EXACT block pixels, and grass around it, so a
  one-chunk shift or an x/z transposition fails;
* the client snapshot's ``journeymap`` group reports an active backend with no decode,
  render or column failures, and the client log has no JourneyMap snapshot WARN/ERROR.

With ``--world-id`` (the world-id race arm: JourneyMap also on the server), the map must
land in a ``<server>_<worldId>`` directory and no LSS-only chunk may be opaque in an
id-less directory.

Stdlib only (own PNG decoder). ``--selftest`` builds synthetic region images.
"""
from __future__ import annotations

import argparse
import json
import math
import os
import re
import struct
import sys
import tempfile
import zlib
from pathlib import Path

PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"
REGION_CHUNKS = 32
REGION_PIXELS = 512

MARKER_CLASSES = {
    "minecraft:red_wool": "red",
    "minecraft:lapis_block": "blue",
    "minecraft:gold_block": "yellow",
}


# ---- PNG ----

def _paeth(a: int, b: int, c: int) -> int:
    p = a + b - c
    pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
    if pa <= pb and pa <= pc:
        return a
    return b if pb <= pc else c


def decode_png(data: bytes) -> tuple[int, int, list[bytes]]:
    """Width, height and RGBA rows of an 8-bit RGB/RGBA non-interlaced PNG."""
    if not data.startswith(PNG_SIGNATURE):
        raise ValueError("not a PNG")
    pos = len(PNG_SIGNATURE)
    width = height = None
    color_type = None
    idat = bytearray()
    while pos < len(data):
        length, kind = struct.unpack(">I4s", data[pos:pos + 8])
        body = data[pos + 8:pos + 8 + length]
        pos += 12 + length
        if kind == b"IHDR":
            width, height, depth, color_type, _, _, interlace = struct.unpack(">IIBBBBB", body)
            if depth != 8 or color_type not in (2, 6) or interlace != 0:
                raise ValueError(f"unsupported PNG (depth {depth}, colour type {color_type}, interlace {interlace})")
        elif kind == b"IDAT":
            idat += body
        elif kind == b"IEND":
            break
    if width is None:
        raise ValueError("PNG without IHDR")
    channels = 4 if color_type == 6 else 3
    raw = zlib.decompress(bytes(idat))
    stride = width * channels
    rows: list[bytes] = []
    prev = bytearray(stride)
    offset = 0
    for _ in range(height):
        kind = raw[offset]
        line = bytearray(raw[offset + 1:offset + 1 + stride])
        offset += 1 + stride
        for i in range(stride if kind else 0):
            left = line[i - channels] if i >= channels else 0
            up = prev[i]
            upleft = prev[i - channels] if i >= channels else 0
            if kind == 1:
                line[i] = (line[i] + left) & 0xFF
            elif kind == 2:
                line[i] = (line[i] + up) & 0xFF
            elif kind == 3:
                line[i] = (line[i] + ((left + up) >> 1)) & 0xFF
            elif kind == 4:
                line[i] = (line[i] + _paeth(left, up, upleft)) & 0xFF
            elif kind != 0:
                raise ValueError(f"bad PNG filter {kind}")
        prev = line
        if channels == 3:
            rgba = bytearray()
            for i in range(0, stride, 3):
                rgba += line[i:i + 3] + b"\xff"
            rows.append(bytes(rgba))
        else:
            rows.append(bytes(line))
    return width, height, rows


def encode_png(width: int, height: int, pixel, filter_type: int = 0) -> bytes:
    """Selftest helper: an RGBA PNG from a pixel function, every row with ``filter_type``."""
    rows = [b"".join(bytes(pixel(x, y)) for x in range(width)) for y in range(height)]
    return encode_png_rows(width, height, rows, filter_type)


def encode_png_rows(width: int, height: int, rows: list, filter_type: int = 0) -> bytes:
    """Selftest helper: an RGBA PNG from RGBA rows, every row with ``filter_type`` (0-4)."""
    raw = bytearray()
    prev = bytearray(width * 4)
    for y in range(height):
        line = bytearray(rows[y])
        if filter_type == 0:
            raw += b"\x00" + line
            prev = line
            continue
        out = bytearray()
        for i in range(len(line)):
            left = line[i - 4] if i >= 4 else 0
            up = prev[i]
            upleft = prev[i - 4] if i >= 4 else 0
            predictor = (0, left, up, (left + up) >> 1, _paeth(left, up, upleft))[filter_type]
            out.append((line[i] - predictor) & 0xFF)
        raw += bytes([filter_type]) + out
        prev = line

    def chunk(kind: bytes, body: bytes) -> bytes:
        return struct.pack(">I", len(body)) + kind + body + struct.pack(">I", zlib.crc32(kind + body) & 0xFFFFFFFF)

    return (PNG_SIGNATURE + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(bytes(raw))) + chunk(b"IEND", b""))


# ---- colour classes ----

def colour_class(r: int, g: int, b: int, a: int) -> str:
    if a < 255:
        return "transparent"
    if r > 140 and g > 110 and b < 0.6 * g:
        return "yellow"
    if r > 70 and r > 1.6 * g and r > 1.6 * b:
        return "red"
    if b > 70 and b > 1.25 * r and b > 1.05 * g:
        return "blue"
    if g > r and g > b:
        return "green"
    return "other"


# ---- the map on disk ----

class RegionMap:
    """Day-map pixels by block coordinate, loaded lazily per region file."""

    def __init__(self, day_dir: Path):
        self.day_dir = day_dir
        self.cache: dict[tuple[int, int], list[bytes] | None] = {}

    def _region(self, rx: int, rz: int):
        key = (rx, rz)
        if key not in self.cache:
            path = self.day_dir / f"{rx},{rz}.png"
            if path.is_file():
                w, h, rows = decode_png(path.read_bytes())
                if (w, h) != (REGION_PIXELS, REGION_PIXELS):
                    raise ValueError(f"{path}: {w}x{h}, expected {REGION_PIXELS}x{REGION_PIXELS}")
                self.cache[key] = rows
            else:
                self.cache[key] = None
        return self.cache[key]

    def pixel(self, bx: int, bz: int) -> tuple[int, int, int, int]:
        rows = self._region(bx >> 9, bz >> 9)
        if rows is None:
            return (0, 0, 0, 0)
        px, pz = bx & 511, bz & 511
        o = px * 4
        row = rows[pz]
        return row[o], row[o + 1], row[o + 2], row[o + 3]

    def chunk_opaque(self, cx: int, cz: int) -> bool:
        return all(self.pixel(cx * 16 + x, cz * 16 + z)[3] == 255 for z in range(16) for x in range(16))


# ---- scenario facts ----

FILL = re.compile(r"^fill\s+(-?\d+)\s+(-?\d+)\s+(-?\d+)\s+(-?\d+)\s+(-?\d+)\s+(-?\d+)\s+(\S+)")


def markers_from_scenario(scenario: dict) -> dict[tuple[int, int], str]:
    """Block (x, z) → expected colour class, from the timeline's marker ``fill`` commands."""
    blocks: dict[tuple[int, int], str] = {}
    for step in scenario.get("steps", []):
        m = FILL.match(step.get("cmd", ""))
        if not m:
            continue
        x1, _, z1, x2, _, z2 = (int(v) for v in m.groups()[:6])
        cls = MARKER_CLASSES.get(m.group(7))
        if cls is None:
            continue
        for x in range(min(x1, x2), max(x1, x2) + 1):
            for z in range(min(z1, z2), max(z1, z2) + 1):
                blocks[(x, z)] = cls
    return blocks


def lod_from_config(text: str) -> int:
    m = re.search(r'"minecraft:overworld":\s*(\d+)', text) or re.search(r"default_chunks:\s*(\d+)", text)
    if not m:
        raise ValueError("no LOD distance in the scenario config")
    return int(m.group(1))


# ---- the verdict ----

def check_map(region_map: RegionMap, markers: dict[tuple[int, int], str], lod: int, margin: int) -> list[str]:
    problems: list[str] = []
    radius = lod - margin
    holes = []
    for cz in range(-radius, radius + 1):
        for cx in range(-radius, radius + 1):
            if math.hypot(cx, cz) <= radius and not region_map.chunk_opaque(cx, cz):
                holes.append((cx, cz))
    if holes:
        problems.append(f"{len(holes)} chunk(s) inside the disc are not fully mapped, e.g. {holes[:8]}")
    marker_chunks = {(x >> 4, z >> 4) for (x, z) in markers}
    for cx, cz in sorted(marker_chunks):
        wrong = []
        for z in range(cz * 16, cz * 16 + 16):
            for x in range(cx * 16, cx * 16 + 16):
                expected = markers.get((x, z), "green")
                got = colour_class(*region_map.pixel(x, z))
                if got != expected:
                    wrong.append((x, z, expected, got))
        if wrong:
            problems.append(f"marker chunk ({cx},{cz}): {len(wrong)} pixel(s) with the wrong colour class,"
                            f" e.g. {wrong[:4]}")
    return problems


def check_metrics(snapshots: list[dict]) -> list[str]:
    groups = [s["journeymap"] for s in snapshots if isinstance(s.get("journeymap"), dict)]
    if not groups:
        return ["no client snapshot carries a journeymap group (was JourneyMap on the client runtime?)"]
    last = groups[-1]
    problems = []
    if last.get("state") not in ("active", "waiting(settle)"):
        problems.append(f"backend state at the end: {last.get('state')!r}")
    for key in ("decode_failures", "render_failed", "column_failures"):
        if last.get(key, 0) != 0:
            problems.append(f"journeymap.{key} = {last.get(key)}")
    if last.get("rendered", 0) <= 0:
        problems.append("journeymap.rendered = 0: no LSS column reached JourneyMap")
    return problems


def check_log(text: str) -> list[str]:
    bad = [line for line in text.splitlines()
           if "JMChunkSnapshot" in line and re.search(r"\b(WARN|ERROR)\b", line)]
    return [f"JourneyMap rejected snapshot bytes: {bad[0].strip()[:240]}"] if bad else []


def export_crop(region_map: RegionMap, lod: int, path: Path) -> None:
    """The LOD disc's square from JourneyMap's day map, one pixel per block."""
    half = (lod + 1) * 16
    rows = []
    for bz in range(-half, half):
        row = bytearray()
        for bx in range(-half, half):
            row += bytes(region_map.pixel(bx, bz))
        rows.append(bytes(row))
    path.write_bytes(encode_png_rows(2 * half, 2 * half, rows))


def find_day_dirs(jm_dir: Path) -> list[Path]:
    return sorted(p for p in (jm_dir / "data" / "mp").glob("*/*/day")
                  if p.is_dir() and "overworld" in p.parent.name.lower())


def verdict(args) -> list[str]:
    scenario = json.loads(Path(args.scenario).read_text(encoding="utf-8"))
    lod = lod_from_config(Path(args.config).read_text(encoding="utf-8"))
    markers = markers_from_scenario(scenario)
    if not markers:
        return ["the scenario builds no markers (no fill with a marker block)"]
    day_dirs = find_day_dirs(Path(args.jm_dir))
    if not day_dirs:
        return [f"no overworld day map under {Path(args.jm_dir) / 'data' / 'mp'}"]
    problems: list[str] = []
    if args.world_id:
        with_id = [d for d in day_dirs if "_" in d.parent.parent.name]
        without = [d for d in day_dirs if "_" not in d.parent.parent.name]
        if len(with_id) != 1:
            return problems + [f"expected exactly one <server>_<worldId> map, found {[str(d) for d in with_id]}"]
        target = with_id[0]
        for d in without:
            leaked = [(cx, cz) for cz in range(-lod, lod + 1) for cx in range(-lod, lod + 1)
                      if max(abs(cx), abs(cz)) > args.vanilla + 2 and math.hypot(cx, cz) <= lod
                      and RegionMap(d).chunk_opaque(cx, cz)]
            if leaked:
                problems.append(f"{len(leaked)} LSS-only chunk(s) landed in the id-less map {d}, e.g. {leaked[:6]}")
    else:
        if len(day_dirs) != 1:
            return [f"expected one overworld day map, found {[str(d) for d in day_dirs]}"]
        target = day_dirs[0]
    problems += check_map(RegionMap(target), markers, lod, args.margin)
    if args.export_crop:
        export_crop(RegionMap(target), lod, Path(args.export_crop))
        print(f"[journeymap-smoke] map crop: {args.export_crop}")
    snapshots = []
    for line in Path(args.client_jsonl).read_text(encoding="utf-8").splitlines():
        if line.strip():
            snapshots.append(json.loads(line))
    problems += check_metrics(snapshots)
    if args.client_log:
        problems += check_log(Path(args.client_log).read_text(encoding="utf-8", errors="replace"))
    return problems


# ---- selftest ----

def _selftest() -> int:
    cases = 0

    def check(cond: bool, what: str):
        nonlocal cases
        cases += 1
        if not cond:
            raise AssertionError(what)

    # PNG round trip, every filter type, RGB and RGBA.
    for f in range(5):
        data = encode_png(7, 5, lambda x, y: (x * 30 % 256, y * 50 % 256, (x * y) % 256, 255 if x else 0), f)
        w, h, rows = decode_png(data)
        check((w, h) == (7, 5), "png size")
        check(rows[3][4 * 4:4 * 4 + 4] == bytes((120, 150, 12, 255)), f"png filter {f} pixel")
        check(rows[2][3] == 0, f"png filter {f} alpha")

    scenario = {"steps": [
        {"anchor": 1, "at": 20, "cmd": "fill 224 -61 48 239 -61 51 minecraft:red_wool"},
        {"anchor": 1, "at": 20, "cmd": "fill 224 -61 48 227 -61 63 minecraft:red_wool"},
        {"anchor": 1, "at": 20, "cmd": "fill -100 -61 0 -100 -61 0 minecraft:stone"},
    ]}
    markers = markers_from_scenario(scenario)
    check(len(markers) == 16 * 4 + 4 * 12 and markers[(224, 63)] == "red", "markers from fills")
    check(lod_from_config('lod:\n  distance:\n    by_dimension:\n      "minecraft:overworld": 24\n') == 24, "lod parse")

    colours = {"green": (89, 125, 39, 255), "red": (153, 40, 36, 255), "blue": (31, 64, 140, 255),
               "yellow": (240, 205, 60, 255), "transparent": (0, 0, 0, 0)}
    for name, rgba in colours.items():
        check(colour_class(*rgba) == name, f"class {name}")

    def write_map(root: Path, server: str, overrides: dict) -> Path:
        """Green everywhere, then ``overrides`` (block (x, z) → RGBA)."""
        day = root / "data" / "mp" / server / "overworld" / "day"
        day.mkdir(parents=True)
        green_row = bytes(colours["green"]) * 512
        for rx in (-1, 0):
            for rz in (-1, 0):
                rows = [bytearray(green_row) for _ in range(512)]
                for (bx, bz), rgba in overrides.items():
                    if (bx >> 9, bz >> 9) == (rx, rz):
                        o = (bx & 511) * 4
                        rows[bz & 511][o:o + 4] = bytes(rgba)
                (day / f"{rx},{rz}.png").write_bytes(encode_png_rows(512, 512, rows))
        return day

    good = {pos: colours[c] for pos, c in markers.items()}
    shifted = {(x + 16, z): colours[c] for (x, z), c in markers.items()}
    transposed = {(z, x): colours[c] for (x, z), c in markers.items()}
    holed = dict(good)
    holed.update({(5 * 16 + x, -9 * 16 + z): colours["transparent"] for x in range(16) for z in range(16)})

    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        for name, painter, ok in (("good", good, True), ("shift", shifted, False),
                                  ("transpose", transposed, False), ("hole", holed, False)):
            jm = root / name
            day = write_map(jm, "Soak_Server", painter)
            problems = check_map(RegionMap(day), markers, 16, 2)
            check((not problems) == ok, f"map case {name}: {problems}")
            if name == "good":
                crop = root / "crop.png"
                export_crop(RegionMap(day), 16, crop)
                w, h, rows = decode_png(crop.read_bytes())
                check((w, h) == (544, 544), "crop size")
                check(colour_class(*rows[48 + 272][(224 + 272) * 4:(224 + 272) * 4 + 4]) == "red", "crop marker pixel")
            check(find_day_dirs(jm) == [day], f"day dir discovery {name}")

    ok_snaps = [{"journeymap": {"state": "active", "rendered": 900, "decode_failures": 0,
                                "render_failed": 0, "column_failures": 0}}]
    check(not check_metrics(ok_snaps), "metrics ok")
    check(check_metrics([{"journeymap": dict(ok_snaps[0]["journeymap"], decode_failures=2)}]), "decode failures caught")
    check(check_metrics([{"journeymap": dict(ok_snaps[0]["journeymap"], rendered=0)}]), "nothing rendered caught")
    check(check_metrics([{}]), "missing group caught")
    check(check_log("[12:00] [JM-Task/WARN] (journeymap) JMChunkSnapshot.decode: invalid payload"), "log WARN caught")
    check(not check_log("[12:00] [Render thread/INFO] JMChunkSnapshot ok"), "log INFO ignored")
    print(f"check_journeymap_smoke selftest OK: {cases} cases")
    return 0


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--selftest", action="store_true")
    parser.add_argument("--jm-dir", help="the soak client's journeymap/ directory")
    parser.add_argument("--scenario", help="scripts/soak-scenarios/journeymap-map.json")
    parser.add_argument("--config", help="the scenario's server config (LOD distance)")
    parser.add_argument("--client-jsonl", help="soak-results/<run>/client-run1.jsonl")
    parser.add_argument("--client-log", help="soak-results/<run>/client-run1.log")
    parser.add_argument("--margin", type=int, default=2, help="chunks inside the LOD radius left unchecked")
    parser.add_argument("--vanilla", type=int, default=8, help="the client's vanilla view distance")
    parser.add_argument("--world-id", action="store_true", help="JourneyMap also ran on the server")
    parser.add_argument("--export-crop", help="write the disc's day-map crop to this PNG")
    args = parser.parse_args(argv)
    if args.selftest:
        return _selftest()
    for name in ("jm_dir", "scenario", "config", "client_jsonl"):
        if getattr(args, name) is None:
            parser.error(f"--{name.replace('_', '-')} is required")
    problems = verdict(args)
    for p in problems:
        print(f"[journeymap-smoke] FAIL: {p}")
    if not problems:
        print("[journeymap-smoke] OK: the LOD disc and every marker are on JourneyMap's map")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
