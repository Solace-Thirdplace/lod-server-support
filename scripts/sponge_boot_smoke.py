#!/usr/bin/env python3
"""Sponge boot smoke (sponge-support-plan.md §5.2) — driven by :sponge:bootSmoke.

Boots the BUILT release plugin jar on the line's pinned SpongeVanilla server, beside the
dev-only smoke companion plugin, twice on one fresh offline superflat world:

  phase 1  generation + save + the static runtime facts
  phase 2  a disk-read serve through the service's own reader + a disk re-load

The companion writes a JSON verdict per phase; this runner merges them, prints one line
per check and exits nonzero on any failure, a missing verdict, or a deadline.

The SpongeVanilla jar is downloaded once (sha1-verified) into the cache directory, and
the server's first-boot library closure (vanilla + libraries, which SpongeVanilla
downloads and remaps itself) is kept there too, so CI can cache it (build.yml).
Stdlib only.
"""
import argparse
import hashlib
import json
import os
import shutil
import socket
import subprocess
import sys
import time
import urllib.request
from pathlib import Path

REPO = "https://repo.spongepowered.org/repository/maven-releases/org/spongepowered/spongevanilla"
PHASE_DEADLINE_SECONDS = 300


def sha1(path: Path) -> str:
    h = hashlib.sha1()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def fetch_server(version: str, expected_sha1: str, cache: Path) -> Path:
    cache.mkdir(parents=True, exist_ok=True)
    jar = cache / f"spongevanilla-{version}-universal.jar"
    if jar.is_file() and sha1(jar) == expected_sha1:
        return jar
    url = f"{REPO}/{version}/spongevanilla-{version}-universal.jar"
    tmp = jar.with_suffix(".part")
    print(f"[sponge-smoke] downloading {url}", flush=True)
    # The Sponge repository refuses urllib's default User-Agent (HTTP 403).
    request = urllib.request.Request(url, headers={"User-Agent": "lod-server-support-ci/1.0"})
    with urllib.request.urlopen(request, timeout=120) as response, tmp.open("wb") as out:
        shutil.copyfileobj(response, out)
    actual = sha1(tmp)
    if actual != expected_sha1:
        tmp.unlink()
        sys.exit(f"[sponge-smoke] FAIL: {url} sha1 {actual} != pinned {expected_sha1} "
                 "(refresh spongevanilla_sha1 only after confirming the new build)")
    tmp.replace(jar)
    return jar


def free_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def stage(run_dir: Path, plugin: Path, smoke_plugin: Path, cache: Path) -> None:
    if run_dir.exists():
        shutil.rmtree(run_dir)
    (run_dir / "mods").mkdir(parents=True)
    shutil.copy2(plugin, run_dir / "mods" / plugin.name)
    shutil.copy2(smoke_plugin, run_dir / "mods" / smoke_plugin.name)
    (run_dir / "eula.txt").write_text("eula=true\n")
    (run_dir / "server.properties").write_text(
        f"server-port={free_port()}\nonline-mode=false\nlevel-type=minecraft\\:flat\n"
        "spawn-protection=0\nenable-rcon=false\nsync-chunk-writes=true\n")
    # SpongeVanilla downloads + remaps vanilla and its libraries into ./libraries on first
    # boot; keep that closure in the cache so later runs (and CI) skip it.
    libraries = cache / "libraries"
    libraries.mkdir(parents=True, exist_ok=True)
    (run_dir / "libraries").symlink_to(libraries, target_is_directory=True)


def run_phase(phase: int, java: str, server_jar: Path, run_dir: Path) -> dict:
    result = run_dir / f"smoke-phase{phase}.json"
    log = run_dir / f"console-phase{phase}.log"
    cmd = [java, "-Xmx2G", f"-Dlss.sponge.smoke.phase={phase}",
           f"-Dlss.sponge.smoke.result={result}", "-jar", str(server_jar), "nogui"]
    started = time.monotonic()
    with log.open("w") as out:
        proc = subprocess.Popen(cmd, cwd=run_dir, stdin=subprocess.DEVNULL, stdout=out,
                                stderr=subprocess.STDOUT)
        try:
            proc.wait(timeout=PHASE_DEADLINE_SECONDS)
        except subprocess.TimeoutExpired:
            proc.kill()
            proc.wait()
            return {"phase": phase, "pass": False,
                    "checks": {"deadline": {"ok": False,
                                            "detail": f"no exit within {PHASE_DEADLINE_SECONDS}s; see {log}"}}}
    elapsed = time.monotonic() - started
    if not result.is_file():
        tail = "".join(log.read_text(errors="replace").splitlines(keepends=True)[-25:])
        return {"phase": phase, "pass": False,
                "checks": {"verdict_written": {"ok": False,
                                               "detail": f"exit {proc.returncode} after {elapsed:.0f}s, no verdict:\n{tail}"}}}
    verdict = json.loads(result.read_text())
    verdict["exit_code"] = proc.returncode
    verdict["seconds"] = round(elapsed, 1)
    return verdict


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--spongevanilla", required=True)
    ap.add_argument("--sha1", required=True)
    ap.add_argument("--java", required=True)
    ap.add_argument("--plugin", required=True, type=Path)
    ap.add_argument("--smoke-plugin", required=True, type=Path)
    ap.add_argument("--run-dir", required=True, type=Path)
    ap.add_argument("--result", required=True, type=Path)
    ap.add_argument("--cache", type=Path,
                    default=Path(os.environ.get("LSS_SPONGE_CACHE", Path.home() / ".cache" / "lss-sponge")))
    args = ap.parse_args()

    cache = args.cache / args.spongevanilla
    server_jar = fetch_server(args.spongevanilla, args.sha1, cache)
    stage(args.run_dir, args.plugin, args.smoke_plugin, cache)
    phases = [run_phase(1, args.java, server_jar, args.run_dir)]
    if phases[0].get("pass"):
        phases.append(run_phase(2, args.java, server_jar, args.run_dir))

    ok = all(p.get("pass") for p in phases) and len(phases) == 2
    args.result.parent.mkdir(parents=True, exist_ok=True)
    args.result.write_text(json.dumps({"pass": ok, "spongevanilla": args.spongevanilla,
                                       "phases": phases}, indent=2) + "\n")
    for p in phases:
        for name, row in p.get("checks", {}).items():
            mark = "ok  " if row.get("ok") else "FAIL"
            print(f"[sponge-smoke] phase {p['phase']} {mark} {name}: {row.get('detail')}", flush=True)
    print(f"[sponge-smoke] {'PASS' if ok else 'FAIL'} ({args.spongevanilla}); verdict {args.result}", flush=True)
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
