# DH P4 coverage and performance checkpoint

Date: 2026-10-07. Local MC1.21.1 work on `feat/dh-codec-p1-mc1.21.1`.
This is the historical r5 packaged checkpoint. The subsequent
[full-feature review fixes](2026-10-07-dh-full-feature-review-disposition.md)
change source and the reporting below; their normal artifact/live qualification
is still outstanding. The server restoration described below predates the later
authorized stop. It is currently stopped, with the same world saved.
Final packaged checks pass; manual restoration status is recorded below. This does not close the Voxy I1 completion/persistence
and ordering limitation, upstream outreach or release gates.

## Performance result

Twelve native Windows Minecraft runs completed: three paired cold-server runs and
three paired warm-server runs, with alternating arm order. Each arm requested the
same nine detail6 tiles, a 144-chunk-coordinate area west of spawn. Canonical replay
of the actual received frames proves identical complete blocks, biomes and raw
lighting in every pair. The official arm uses the installed DH3.3.3 queue, decoder,
provider and renderer, with private test hooks limiting acquisition to the registered
area. It contains neither LSS nor Voxy. The other arm runs the normal LSS request,
decode and Voxy delivery pipeline with a test-only area filter. Both use stock
DH3.3.3 servers and independent worlds with seed8675309.

Medians of three runs per cell:

| Server cache | Client | Server CPU, seconds | Acquisition, seconds | Frame p99, ms | Peak working set, GiB |
| --- | --- | ---: | ---: | ---: | ---: |
| Cold | official | 27.27 | 5.124 | 9.300 | 2.04 |
| Cold | lss | 24.08 | 10.945 | 9.307 | 2.48 |
| Warm | official | 0.80 | 3.394 | 9.301 | 2.12 |
| Warm | lss | 1.68 | 6.841 | 9.314 | 2.46 |

These are per-arm recorded completion windows; their start/stop boundaries differ,
so the apparent approximately2× ratio is not an end-to-end acquisition comparison.
The measured r3 scheduler also imposed a500ms per-request floor and500ms scan
cadence (Support1s; unavailable backlog probe2s), in addition to one generation
request in flight and two total. The floor dominates warm request throughput;
concurrency alone does not explain the measurements. Cold server CPU is lower in
these samples; warm server CPU is higher. CPU is the whole server-process delta from
first actual terrain request to last terrain encoding, excluding boot but including
ambient ticks and test-hook overhead. It is not isolated generation-worker CPU.
The unequal acquisition-window lengths also accumulate unequal ambient tick CPU.
These results do not establish a general performance advantage or native-LSS parity.

Warm server-window throughput is18.85 tiles/s official and1.47 tiles/s LSS
(medians of9 divided by each server window). Official injects all nine requests at
t0 into the genuine queue, its best-case schedule. Both arms perform synchronous
raw-frame capture inside the timed windows; that overhead is symmetric but not
isolated. Cold median private committed memory is2.49GiB official versus3.69GiB
LSS; warm2.45GiB versus3.69GiB, with the same3GiB maximum Java heap. Working set
alone understates that difference. No larger-area or updated-binary performance
result is claimed. The follow-up separates request-class pacing and lowers the
known-backlog official sync floor to50ms; that change has not been benchmarked.

Both clients caused nine actual DH generation events in each cold run and zero in
each warm run. All144 target native MCA records were missing before cold startup.
Afterwards108 proto records were saved (96 structure-starts,12 biomes), with36 still
missing and zero FULL chunks. Complete DH LOD data therefore must not be reported
as144 persisted vanilla FULL chunks. Warm means a cloned closed producer world,
including its DH SQLite cache, with a fresh client/cache on each arm; it is not a
client rejoin benchmark.

Cold terrain payloads were217983 bytes for both arms. Warm payloads were218035 bytes
for official and217983 for LSS. Bandwidth comes from actually received DH splits
and replies, including DH headers, excluding control/gameplay/Minecraft framing/TCP.
NeoForge invokes encoding more than once per packet; the encoding invocation totals
are retained separately and are not mistaken for received bandwidth.

Native GPU: RTX3080Ti, OpenGL4.6, Microsoft Java21.0.7, Windows11. Server: Java21 on
WSL, Ryzen7800X3D. Both launchers request1280×720, vanilla RD4, a120FPS cap and the
same steady camera. Renderer quality uses each mod's defaults; DH radius32. Each
frame measurement lasts60 seconds after complete source/readback/provider checks,
a15-second settle and actual loaded render geometry/buffers. Frame intervals are
almost identical at this cap. GL timer spans include submission gaps and the cap;
they do not isolate GPU busy time or compositor/display latency. Loading/conversion
hitches, uncapped throughput, shaders, other GPUs and full-radius acquisition are
not qualified by these steady-camera samples.

The user confirmed an initial idle-PC window. This is an authorization timer flag,
not a measured GPU-idleness guarantee. All cold runs are marked confirmed;
later warm runs are marked unconfirmed where they exceeded that window. The pending
optional extension was never treated as approval. Warm results retain that limit.
Every Windows JVM used a fresh non-input desktop, exact PID/creation identity and
a kill-on-close job; the input desktop remained Default. No foreground input,
clipboard or desktop switching was used.

LSS conversion for144 columns took a median0.352 CPU seconds and0.447 wall seconds;
allocation counter median25.33MiB. Peak explicitly owned terrain memory was33.54MiB
in every performance run, returning to zero before frame measurement. Its nine
source records occupy218525 bytes. After shutdown, Voxy cache footprints were about
4.7–6.2MiB; official DH client databases about0.64–0.88MiB, including local vanilla
terrain. These caches serve different roles and are not equivalent storage budgets.

Measured candidate was NeoForge `0.16.0-dh-p4.dev20261007.3`, SHA256
`80518508d7b964d54d962129a7f2657e192cedbb15351463d22ee93d46b5b67f`.
The later candidate includes UI corrections and a SURFACE-only AIR compatibility
fix. Its final artifact and acceptance are recorded separately. No measurements
are relabeled as a different binary's benchmark.

Raw paired summary and CSV:
`/home/vox/projects/voxel-server-support/build/dh-neoforge/2026-10-07/p4-coverage-performance/paired-summary.json`
and `paired-measurements.csv`. Registration, private linked DH hooks, receipts,
frames, request/CPU/generation telemetry and ownership records remain in that
ignored local evidence root. Private upstream-linked code is not in the MIT source
or shipping artifacts. Public fixture/checker code lives in `test-fixtures/dh-p4`.

## Coverage and fixes

Five candidate-r4 content cells pass: official16 with sky and without sky, official15,
Support FullBuilder and Support FastOverworldBuilder. They compare every stored
voxel at Voxy levels0–4 with independently expanded raw-source L0:10,785,024 actual
comparisons, zero block/biome/light differences. The oracle is independent of
conversion/readback comparison, but receives the production-decoded source tile;
it is not independent decoder verification. The scene has four biome quadrants,
negative tile corners, section boundaries, dense material/light planes, water and
plants, leaves, glass, a lit cave, stairs and slabs. The actual producer palettes
are retained; simplified Support omits stairs/slabs/torch, and stock official's
compression omits the enclosed slab. This verifies faithful conversion of producer
output, not recovery of details the producer removed. Higher levels use the pinned
Voxy reducer on independent L0 and check stored propagation, not independent
correctness of that reducer. Height512/min-128 and height384/min-64 are covered.

A real128-chunk configured/effective-range run crosses Overworld→Nether→End→Overworld.
It injects8384 representative records across three explicitly synthetic identity
buckets, reaching8192 records and199571432 bytes with verified eviction. Actual
acquisition makes progress in each real dimension; retired source budgets reach
zero. Overworld return made13 requests/four cache writes without an observed source
cache hit. This proves bounded return progress, not full-radius fill or wholly warm
reuse after pressure. The injected records are not8384 real server downloads.

Verified production corrections:

* Stock official15/16 can emit a sole AIR voxel at the exclusive ceiling. Only that
  verified marker is removed; an otherwise empty column stays UNKNOWN. Solid/water
  overshoots remain rejected. Original producer frames and regressions are frozen.
* Stock official16's rough SURFACE generator uses `getHeight()` as absolute maxY,
  then subtracts minY again. Its -64/384 AIR ends at448. Only the exact upper AIR band
  with generation metadata5 is clipped; quality5 is retained. Original packet and
  pre-fix red test are retained; other stages, materials, profiles and larger
  overshoots are rejection controls. Already accepted output is unchanged, so no
  conversion-cache version bump or forced Voxy reset is needed.
* Legacy Sodium0.6.13 tabs use short localized DH/Players labels. When Sodium's
  donation controls collide, they move to the free footer without changing its
  saved preference. The private rebuild method and descriptors are pinned against
  the real jar; both loader twins remain identical. Native Auto-scale3 verifies
  actual tab/text bounds, three checkboxes, dependency and shared Apply/save.

The r4 range failure before configuration READY, demanded cache-hit premise after
pressure, and wrong Support generation-disable fixture flag and hard-coded official profile
in the Support cache-readback observer remain retained.
Fixture corrections do not change production admission/eviction rules. The first
UI contract red and inherited stale-jar packaging rejection are retained too.

## Final artifact, cache checks and manual handoff

Final NeoForge version `0.16.0-dh-p4.dev20261007.5` has SHA256
`f165513dab605962683a9641b884ae4633e07eb3c53a86259da479d86c8fc94d`. Matching Fabric SHA256:
`27fa74531138f3030488bcf62d7680d915b54d2876883617a740d57b464b70b7`.
Normal Java21 builds pass3345 unit/observer tests (3341 production plus four fixture
observer tests),78 Fabric and8 NeoForge server gametests, and six LSS/VSS packaging
checks plus release self-tests. The eight new Python guard/metric tests pass.

Six fresh final-binary packaged cells pass: official16 SURFACE_ONLY (nine complete
source tiles/144 Voxy columns and real geometry), generation-disabled missing
terrain (30 seconds, zero requests), Support dummy terrain with generation of new
chunks disabled (nine tiles/144 actual readback columns, synthetic bedrock/stone/water
palette confirmed), current512-height independent content, native Windows legacy
Sodium0.6.13 Auto3 menu, and Fabric client→stock official16 service/storage smoke.
The final content row adds2,396,672 comparisons with zero differences. Earlier
cross-profile/no-sky/range/configuration rows remain explicitly r4 evidence.

NTFS uses current production cache code and validated real-source DTOs, relocated
only to synthetic positions in three owned buckets. Each case performs8320 writes,
256 validated reads, cold indexing, actual per-record force/atomic rename, quota
pressure, a fully pinned four-entry refusal/release and a Windows NOSHARE_DELETE
replacement refusal/unlock recovery. Both pass:

| DTO / limiting quota | Retained records / bytes | Write p50 / p99, ms | Read p50 / p99, ms | Cold index, ms | Workload wall / CPU, seconds |
| --- | --- | --- | --- | ---: | --- |
| 981B /8192 files (r3) | 8192 /8822784 | 2.243 /37.620 | 4.148 /9.177 | 703.1 | 56.52 /21.33 |
| 37438B /256MiB (r5) | 7151 /268405634 | 2.381 /34.302 | 4.159 /15.362 | 597.6 | 85.85 /51.09 |

These write tails mostly measure quota eviction. The r5 pre-quota p99 is5.578ms;
the1169 writes after reaching quota have p50/p99 of32.799/38.591ms. The r3
file-quota case has pre-quota p99 of5.458ms and128 post-quota writes with
p50/p99 of38.120/53.470ms. These distributions come from retained chronological
`write_nanos`, not a new run. Later cross-JVM cache locking is also unmeasured.

Storage cleanup removed10.96GiB of inactive historical Linux dependency copies
only after canonical hash verification,7.32GiB from an obsolete inactive Windows
test profile's derived Voxy cache, and0.56GiB of superseded Windows calibration/UI
library copies. Another1.21GiB of derived Voxy caches from explicitly obsolete
v0.8.1/v0.9.1 test profiles provided the final restart headroom. Worlds, configurations, current paired-run inputs, captures,
results and failures are retained. Cleanup receipts give recovery sources. Linux
logical bytes/fstrim are not misreported as equal physical C: reclamation.

The explicitly authorized P4-only admission profile reserves2GiB growth and
monitors physical capacity every5 seconds and owned growth every30 seconds. It
keeps the50GiB floor and stops a run exceeding2GiB growth. Generic rigs/manual
server requirements remain8GiB growth; no general storage guard was weakened.

The tested r5 NeoForge jar is installed in Windows **LSS dev —1.21.1 NeoForge -
Copy For Far Testing**, with the previous jar backed up at
`/mnt/c/Users/Ian/AppData/Roaming/PrismLauncher/instances/LSS dev — 1.21.1 NeoForge - Copy For Far Testing/minecraft/lss-dh-backups/20261007T083428.458235Z`. Client preferences and every other mod hash are unchanged.
No foreground client was launched. The same **dh-neoforge-playable** world resumes
at **[::1]:25585**, with OP preserved, stock DH3.3.3 and no native LSS server mod.
Generation32/SURFACE_THEN_CHUNKS, sync on load and live updates remain enabled.
Fresh current-owner list/save acknowledgements, Minecraft protocol767 and Windows
IPv6 TCP pass. Private process retirement verifies183 recorded Linux identities
retired and zero remaining private Windows JVMs. Current owner PID
414965 is recorded with boot/start identity; read-time
liveness remains authoritative.

The first restart was correctly rejected just below the unchanged58GiB admission
requirement; the obsolete versioned test caches above were reclaimed before the
successful restart. The direct script invocation lacked its executable bit; the
standard `bash start-server.sh` path then ran the owned launcher. Neither failure
was bypassed by weakening the manual server guard.

## Remaining plan limits

Strict Voxy per-write completion/persistence and absolute ordering/vanilla authority
remain open. This checkpoint covers selected biome/light/material scenes and bounded
128-range progress, not every biome/mod/resource-pack combination or full128-radius
saturation. Deeper final Fabric counterparts, legacy/other producer configurations,
long running multi-player/world-key/proxy cases and uncapped/loading frame studies
remain release/coverage work. Outreach is still pending; no merge or publication
was performed.
