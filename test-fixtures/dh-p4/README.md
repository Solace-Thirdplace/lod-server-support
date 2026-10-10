# Local DH P4 coverage and measurements

These are test-only overlays for the MC1.21.1 NeoForge rig. They never belong in a
shipping jar. `build.py` binds the overlay to a normal versioned candidate and the
existing independently built `dh-live` observer. Every run uses a fresh owned
world, game/cache root, artifact hashes, shared harness lock, deadlines and exact
process identities. Failed attempts remain evidence.

`content` expands a complete raw 64×64 source tile and compares every stored voxel
at Voxy levels0–4, including block state, biome and light. Expected L0 never reads
converted `SectionData` or production conversion helpers. Higher levels use
the pinned Voxy reducer on that independent L0; this checks stored propagation,
not independent correctness of Voxy's reducer. The scene covers four biomes,
vertical material/light planes, water and water plants, custom height and no-sky.
The Support arm uses the ordinary 384-height world and its actual producer output.

`legacy-menu` opens actual Sodium0.6.13, checks General’s Allow DH Server Terrain checkbox, dependency,
shared Apply/save preserving config-only sync/live preferences, Auto scale and actual tab/text bounds. Inspect its screenshot
separately.

The October 9 remediation replaced the persisted DH terrain (DTO) cache with
timestamp receipts and a Voxy presence probe. The `cache-pressure` and
`cache-rejoin-range` cases and `NtfsCacheBenchmark` existed only for that cache
and are removed; their historical receipts stay as evidence. Receipt persistence,
Voxy-loss detection, reset and receipt loss are qualified by `dh-live --scenario rejoin`.

`benchmark` filters only the desired acquisition area; the normal LSS request,
rate-limit, decoder and ingest pipeline still runs. It requires a complete-tile
receipt per target, Voxy presence in every target column, and actual render geometry before measuring. The real official
DH arm and server hooks are private linked validation code under the ignored run
root, not part of this MIT fixture or shipping build. Both arms request the same
nine registered tiles in generation mode, use independent identical server
worlds, and record actual requests, CPU, received payload bytes, generation events,
and persisted chunk statuses (FULL, proto or missing). Complete DH LOD data does
not imply persisted vanilla FULL chunks. NeoForge can invoke encoding more than
once per packet; bandwidth comes from received raw frames replayed by
`CapturedTerrainSummary`, with encoding totals retained separately. The metric checker rejects wrong areas, incomplete
terrain, software rendering and missing process/window ownership.

The Windows owner creates a fresh non-input desktop, assigns its exact suspended
Java process to a kill-on-close job, records creation identity and resource
samples, and checks that the input desktop stays unchanged. No foreground input,
clipboard access or desktop switching is used. A test-only mouse guard prevents
Minecraft grabbing the host cursor. Native GL timers and frame entry intervals
are collected asynchronously; they are not compositor/display latency. Timer spans
include submission gaps and the frame cap, rather than isolating GPU busy time. Shared
PC activity must be disclosed. The standard rig remains observation-only; this
narrow native fixture has separate per-attempt ownership and GPU evidence.

The user explicitly authorized smaller storage requirements for these bounded
runs. `bounded_storage.py` uses a versioned reserve: new v2 runs retain 40GiB; existing v1 receipts retain 50GiB, admits at most2GiB of
copies plus2GiB growth, checks physical capacity every5 seconds, and stops owned
work exceeding its2GiB growth cap. It is installed only in these P4 processes;
generic rig defaults remain8GiB. Source manifests, receipts and log/artifact
hashes are required alongside any claimed result.

Run the fixture/checker tests:

```sh
python3 -m unittest discover -s test-fixtures/dh-p4/tests -v
```

Use `scripts/lib/harness-lock.sh` and `harness_run_script` for builds, preparation,
clients and real-server runs. The preparer accepts explicit candidate/build roots;
its dated defaults name this local experiment, not a reusable release recipe.

`--case legacy-menu` starts sync/live enabled and verifies that Apply preserves
them while disabling DH fallback; it audits every page’s widgets,
selected tab and pagination. Use `--window-width 1024 --window-height 768` for the
342×256 Auto-scale layout, `1023`/`768` for exactly341×256, and `1280`/`1024` for
320×256. Minecraft rounds scaled dimensions up. Pass the same explicit window
dimensions to `tools/rig/windows_java_fixture.py` for native Windows staging. The
r6 follow-up passes these layouts and exact426×240 from1278×720, auditing the historical seven-page layout, with immutable run and screenshot
receipts. The October 9 menu simplification removes the separate DH page; new
checks expect two LSS pages and six pages total with this Sodium build.
