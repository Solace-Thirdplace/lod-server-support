# Complete DH compatibility feature — final Claude review briefing

Date: 2026-10-07. Review the **entire cumulative DH compatibility feature**, from
the pre-feature v0.16.0 baseline through the current tested development candidate.
Earlier reviews covered increments; this review should assess the integrated
implementation, its interaction with native LSS, and whether its evidence supports
its claims. Produce findings without implementing fixes, deploying, merging or
publishing. Work remains local; upstream outreach is pending.

The feature is playable, with expanded functional and performance evidence.
**Release readiness remains false.** The supported Voxy ingest boundary still
lacks per-write completion, persistence and cancellation/ordering guarantees.
A controlled experiment demonstrates an old queued DH write overwriting newer
vanilla content. Treat that as a known unresolved correctness gate, not a passing
test or a limitation erased by the latest cache readback checks.

## 1. Checkout, baseline and complete review scope

```sh
cd /home/vox/projects/voxel-server-support/.worktrees/dh-client-p1
git status --short
git rev-parse HEAD
git diff --check
git diff 6677fe93dfc35b8ca1e158972d4cf58eb9703bd2 --stat
git diff 6677fe93dfc35b8ca1e158972d4cf58eb9703bd2 --
git ls-files --others --exclude-standard
```

Branch: `feat/dh-codec-p1-mc1.21.1`. This is a separate clone despite its
`.worktrees` name. HEAD and the resolved `v0.16.0+mc1.21.1` tag both identify
`6677fe93dfc35b8ca1e158972d4cf58eb9703bd2`, the pre-feature baseline. The feature
contains staged additions, unstaged changes and untracked files. A plain
`git diff`, or even a diff against HEAD alone, omits some of that scope: inspect
the listed untracked files directly, including production discovery/cache classes,
regressions, captured corpora and fixtures. Preserve the existing index and files.

The October 6/7 scoped diffs under `docs/implementation/evidence/` are incremental
review aids. **They are not a complete diff of this feature.** Do not restrict
this review to the last implementation increment.

Exact final build copy: `/tmp/lss-dh-p4-20261007-r5`. Local evidence root:

```text
/home/vox/projects/voxel-server-support/build/dh-neoforge/2026-10-07/p4-coverage-performance/
```

Its `r5-final/source-manifest.json` binds 434 production/build inputs;
`r5-final/test-source-manifest.json` binds 597 test/corpus/tooling inputs. Every
entry matches the current review checkout when this briefing is written. Manifest
SHA256s are respectively:

```text
e18cccb4a7e565d573ebd3e9d4c7da005029c8eabebf4eedaa295f38fb9ce7eb
83c2e23f9f4f81b2ee5a75360fe9e080a0f3cc3a2cc96844b5458f3fc300a3f2
```

Version: `0.16.0-dh-p4.dev20261007.5`. Verified final artifacts:

| Loader | Evidence-root artifact | SHA256 |
| --- | --- | --- |
| NeoForge | `r5-final/lod-server-support-neoforge-dh-dev-mc1.21.1.jar` | `f165513dab605962683a9641b884ae4633e07eb3c53a86259da479d86c8fc94d` |
| Fabric | `r5-final/fabric/lod-server-support-fabric-dh-dev-mc1.21.1.jar` | `27fa74531138f3030488bcf62d7680d915b54d2876883617a740d57b464b70b7` |

These hashes and all manifest entries were rechecked for this documentation
handoff. No new build or runtime test was run merely to create this briefing.
`r5-final/verification.json` records the actual build and packaged checks.
Later documentation is not part of the compiled-source identity.

## 2. Read these accounts first

1. [The plan](../planning/2026-09-29-dh-client-compatibility-plan.md), especially
   its dated amendments, shared invariants, codec specification and gates.
2. [Current coverage, fixes and measurements](2026-10-07-dh-p4-coverage-performance.md).
3. [Automatic source/protocol selection](2026-10-06-dh-automatic-selection.md)
   and [the October 6 review disposition](2026-10-06-dh-follow-up-review-disposition.md).
4. [Sync on load and live updates](2026-10-06-dh-sync-live-updates.md),
   [cache preservation corrections](2026-10-05-dh-post-p4-review-disposition.md),
   and [the Voxy completion/ordering counterexample](2026-10-05-dh-voxy-commit-gap.md).
5. [Vanilla/LOD fringe coverage](2026-10-06-vanilla-lod-border-gap.md),
   [ocean lighting](2026-10-05-dh-ocean-lighting.md), and
   [the earlier whole-pipeline qualification](2026-10-05-dh-p2-p4-continuation.md).

Earlier plan sections and checkpoints retain historical manual profile selection,
forced DH mode, periodic refresh, unconditional replay or mismatch rebuilding.
The user's later decisions supersede those behaviors. Persistent **source DTOs**
and optional partial server updates were subsequently authorized; they do not
authorize persistent claims that Voxy completed or saved a write. Official 3.3.3
is the actual MC1.21.1 test producer, because the plan's 3.3.4 has no matching build.
The dated native Windows fixture is also a later, explicitly scoped qualification;
it does not grant general Windows foreground automation.

## 3. Intended product behavior

LSS clients can obtain distant terrain from supported DH servers and render it
through Voxy without installing the real DH client or requiring LSS on the server.
This experiment targets **Minecraft 1.21.1, Java 21, Fabric and NeoForge**. DH Support
0.14.0 on Paper and stock official DH 3.2.0-b/3.3.3 are the producer families tested.
Other Minecraft lines and future DH wire versions are not implied support.

**Use DH allows fallback. Native LSS always wins** when its remote handshake
channel or native session-config offer exists, including disabled/incompatible
native offers. A late native offer retires DH and cancels pending discovery retries.
Use DH off permits native only. A real DH client installed prevents the optional
adapter from registering/working alongside it. No Voxy, receive off, missing remote
service or incompatible input must leave ordinary Minecraft usable.

There is no manual protocol selector. Each fresh physical Minecraft connection
starts with official protocol 16, checks the remote DH channel, and requires a
valid config and world identity before terrain acquisition. Strict control decoding
distinguishes `OFFICIAL_16`, `OFFICIAL_15` and `SUPPORT_15`; the two version-15
families have different string/timestamp encodings. Terrain, ambiguous ASCII
controls and remembered address hints cannot authorize a guessed decoder.

DH Support's uniquely decoded rejection switches codec on the existing Minecraft
connection, with zero reconnects. Stock official 3.2.0-b's mismatch logger can
throw before its rejection is sent. Quiet discovery waits **15 seconds and three
idle client ticks**, with no queued/captured/decoding work or recent arrival,
before spending at most one guarded initial-play reconnect to try official 15.
Any response disables that silence trial. Confirmation survives dimension/respawn,
temporary null worlds and same-connection replacement. A slow retry does not rearm
the allowance. The official clients themselves use exact versions; this is our
bounded compatibility strategy, not a claim of stock backward negotiation.

On rejoin, the client uses both its saved DH sources and **Voxy's own cache**.
When sync is enabled and permitted, source timestamp validation precedes restore.
Unchanged matching Voxy columns require zero conversion/ingest; fully default
missing columns can rebuild. Differing nondefault columns are preserved, and an
unknown/unready reader defers. Missing/changed sources require full data. There
is no blanket re-request or blanket reimport on every reconnect/JVM restart.

**Sync on load** performs optional load/re-entry checks with the upstream
20-minute load debounce. Explicit enable/config changes create load observations.
**Live updates** accepts optional server-negotiated pushes. There is no periodic
refresh timer or stationary polling. Sync off does not check offline edits merely
because time passes; live off stops applying future pushes. Receiving off stops
acquisition without erasing displayed cached terrain.

The separate Sodium DH section contains three checkboxes: **Use DH**, **Sync on
load**, **Live updates**. Defaults are native only, sync on, live on. Source
permission is reconnect-scoped; sync/live reload immediately. The receive master
and shared Apply/save must work across pages. Retired profile/timer YAML is
preserved but inactive. English, Simplified Chinese and Traditional Chinese stay
aligned. Legacy Sodium 0.6 and modern 0.8 API paths exist on both loaders.

## 4. Source map and contracts to examine

Paths below are relative to the review checkout; `xplat` is shared source compiled
by both loader modules, not a separate Gradle project.

| Area | Main files / responsibilities |
| --- | --- |
| Pure protocol core | `common/src/main/java/dev/vox/lss/common/dh/`: profiles, input, messages, framing/discovery, positions, assembly, bounds, leases, normalized terrain/materials and connection/session policy. No Minecraft, loader or runtime DH dependency. |
| Persistent source | Same directory, `DhTerrainCache.java`: complete original DTOs, identity/versioning, global quotas, atomic IO, protected working sets and clearing. |
| Source lifecycle | `xplat/src/main/java/dev/vox/lss/networking/client/`: `DhClientSession`, `ClientNetGlue`, `ClientSessionGate`, `ClientIdentityResolver`, `ClientCommandActions`, `ClientStatus`, `ResetCoordinator`, `ColumnCacheStore`. |
| Conversion/destination | `DhColumnConverter` in that client directory; `xplat/src/main/java/dev/vox/lss/compat/{DhVoxyBridge,VoxyCompat,VoxyTerrainCache,ModCompat}.java`. Registry resolution, authority, actual Voxy readback and ownership. |
| Vanilla fringe | `xplat/src/main/java/dev/vox/lss/compat/VoxyVanillaCoverage.java`: actual held chunks outside Voxy's drawing exclusion, readiness and bounded moving-world retries. |
| Loader seams | Fabric/NeoForge `DhClientTransport` twins, entrypoints/bootstrap, command/network glue and channel advertisement checks. Keep loader-specific descriptors out of common/xplat. |
| Settings/UI/status | Common `ClientSettings`, schema/specs, YAML and `diagnostics`; shared `ClientOptionCatalog`/`OptionSpec`; both `LSSConfigMenu`, `LegacySodiumPage`, `SodiumDraftRefresh`, `SodiumLegacyOptionsHook`; locale resources and generated settings references. |
| Packaging | Common/Fabric/NeoForge Gradle changes, relocated XZ license/notice, `scripts/release_check.py`; LSS/VSS identities, SQLite and compile-only Sodium exclusions. |
| Validation/operations | `test-fixtures/dh-live/`, `test-fixtures/dh-p4/`, `tools/dh/assemble_playable.py`, Windows ownership tools under `tools/rig/`, `scripts/lib/owned-process.py` and its grace regression. |

### Codec and conversion

Review both terrain formats, compression IDs 0/3/4 (none/XZ/Zstd), prediction,
negative coordinates, tile edges/corners, family-specific UTF/timestamps, mapping
IDs and all length/height checks. Default limits include 1MiB frames, 16MiB tiles,
64MiB decoded data, 256MiB explicitly owned bytes and a 4096-block height ceiling.
Assembly has request ownership, idle/lifetime expiry and explicit budget release.
Unknown coverage must stay unknown; a span describing AIR is a different fact.
Only fully described Minecraft columns may be handed off. DH producer output is
an approximation; the adapter cannot recover geometry its producer discarded.

Captured producer bytes, not self-round-trips alone, cover Support meadow/ocean,
official15, official16 partial/content/ocean, ceiling sentinels and SURFACE_ONLY.
See `common/src/test/resources/dh-corpus/` and accompanying provenance/hashes.
Self-encoded official CloseReason tests are defensive controls: the stock jars'
logging failure makes that handler unreachable in the tested mismatch path.

There are narrow captured compatibility exceptions. Existing top AIR overshoot
clipping and the exclusive-ceiling AIR sentinel must not admit solids/water or
invent coverage. Official16 SURFACE generation metadata5 can emit an upper AIR
band ending at `height - minY` because its rough generator subtracts a negative
minimum twice. Only that exact profile/stage/upper-AIR case is clipped; quality5
is retained. Rejection controls cover other profiles/stages/materials/overshoots.
Already accepted output is unchanged by that fix, so conversion version remains2.

Conversion resolves blocks/properties, biomes and packed light into current
Minecraft sections. Water skylight attenuates with depth, including flowing water,
waterlogged states, kelp and seagrass. Block light, no-sky dimensions and section
boundaries remain distinct. Compare actual stored output with independent raw
source expectations, not the converter's own expected sections alone.

### Sessions, delivery and the shared invariants

The scheduler is tile-based and separate from native scanners/receipt managers.
It respects server range, border, generation/sync rates, bandwidth and transport
pressure; deliberately permits one generation request in flight and two total.
Frontier progress, timestamp validation and transient retries must remain fair.
Unreachable absence, rejection/cooldown, dropped pressure and unchanged replies
must not silently turn into successful complete receipts.

Recheck all four plan invariants against the integrated implementation:

* **I1:** protect every vanilla-held column before conversion and at handoff.
  These checks reduce exposure but do not cancel an already accepted Voxy write.
* **I2:** DH use must invalidate appropriate native freshness hints so returning
  to native cannot preserve DH approximations through false `up_to_date` replies.
* **I3:** one active terrain source, including late native offers, disabled offers,
  receive changes, reconnect and temporary null-world/reconfiguration races.
* **I4:** reset owns and drains DH work before Voxy teardown. Unsafe retirement
  must refuse rather than tear down an engine still used by a worker.

Check worker/main-thread epochs, Connection/level/reader/engine identity, cancellation,
receipt masks, queue/control headroom, leases and reference release on every exit.
DH goes only to the narrow Voxy sink, not `LSSApi.dispatchColumn` or Xaero/third-party
native consumers. Native wire fidelity and existing far-player behavior must remain
unchanged. Enqueue receipt, current readback, in-memory completion and disk persistence
are separate claims; status/diagnostics must reflect their actual boundary.

### Persistent sources and optional pushes

`!dh-terrain-v1` stores complete validated original DTOs. Header/layout version2
and conversion version2 are separate; both affect validity/identity. Keys include
address/server/world/dimension/seed/profile/world shape. Shared LRU/indexing enforces
**8192 files / 256MiB globally** across owned buckets, with only current interest
and validation protected. Historical unvisited entries must remain evictable.

Verify hash/size/identity validation, corruption invalidation, atomic force/rename,
no-follow IO, Windows reparse/junction refusal, pending-file hygiene, quota refusal,
clear/reset ownership and read-only/transient failure recovery. No filesystem path
may follow foreign content during cache sweeping. Changed/partial/absent data
invalidates old retained sources before replacement/handoff. New source admission
requires accepted delivery plus actual matching destination readback; it still
does not create a persistent Voxy completion receipt.

Messages9/10 allow bounded unrequested update/reassembly only after negotiation.
Check profile/key/distance/timestamp validation, full-column masks, per-column
ordering fences, whole-tile stamps, competing-request cancellation and invalidation
of an older complete source when the update is partial. Main-thread timeout and
backlog/fragment pressure must release terrain and schedule bounded reacquisition,
without stopping a healthy adapter or publishing false unchanged source state.

### Fringe coverage and Sodium

The shared fringe pump also runs in single-player and on servers without LSS; it
ingests actual loaded vanilla chunks and does not request/generate distant terrain.
Current limits are **64 actual lookups/tick, 256 geometric candidates/tick,
8 submissions/tick and 128 submissions/second**. The old 8/second brief is stale.
Absolute-world sweep coordinates, chunk-load retries, weak references, transient
readiness/light retry and current-world retirement matter during flight/reset.

Legacy Sodium uses short localized DH/Players tabs. Colliding donation controls
move to the free footer after the real private `rebuildGUI` returns, preserving
Sodium's saved preference. Inspect method descriptors/mixin target, both twins,
reflection containment, narrow-window fallback and actual layout bounds. Compile-only
Sodium API stubs and all observers/private upstream-linked code must stay out of
every shipping LSS/VSS jar. NeoForge SQLite remains a stock nested jarJar library.

## 5. What has actually been tested

Final r5 normal verification: **3345 unit/observer passes**, comprising 3341
production-suite passes plus four fixture observer passes. Module passes are
Common1019, Fabric1767, Paper536 and NeoForge23; Fabric also has four skips.
Fabric78 and NeoForge8 server gametests pass, as do six LSS/VSS packaging checks,
release self-tests and eight new Python storage/metric tests.

| Evidence/candidate | Actual qualification and limit |
| --- | --- |
| October 5 pipeline and review fixes | Both loader transports, Support Full/Fast, official15/16, no-service, real-DH conflict, native regression/source changes, lifecycle/content/removal, ocean light, cache rejoin/restart/loss/reset; distinct artifact-bound historical runs. The Voxy race is a reproduced failure of I1. |
| October 6 final review-fix candidate | Fabric no-service with zero reconnect; Neo official16 config delayed6.5s with zero reconnect; stock official15 with exactly one reconnect; Support with zero reconnect; moving dual-service native priority with zero DH **terrain requests**; unchanged rejoin, real pushes/removal, live-off preservation and actual modern Sodium menu. Control traffic is not claimed zero. |
| P4 r4 content | Five raw-source cells: official16 sky/no-sky, official15, Support Full/Fast; 10,785,024 real comparisons of stored levels0–4, zero block/biome/light differences. Four biomes, custom384/512 heights, negative boundaries, light/material scenes. Producer-omitted details remain omitted. |
| P4 r4 range/pressure | Effective range128, real Overworld→Nether→End→Overworld progress, plus8384 representative synthetic records across three identity buckets;8192 retained files/199571432 bytes, eviction and retired budgets checked. Return made13 requests/four writes with no observed cache hit. Bounded progress, not full-radius fill or wholly warm reuse. |
| Final r5 packaged cells | SURFACE_ONLY full source/readback/geometry; disabled generation/missing terrain for30s with zero requests; Support dummy terrain with synthetic palette confirmed; another2,396,672 independent comparisons; native Windows Sodium0.6.13 Auto3 controls/layout; Fabric→stock official16 service/storage smoke. |
| NTFS r3/r5 | Current production cache code,8320 writes and256 validated reads per case, real force/rename/cold index, file and byte quotas, four-entry protected refusal/release, and actual Windows sharing-lock replacement refusal/recovery. Synthetic positions are labeled; this is headless IO evidence. |

Total r4+r5 content comparisons: **13,181,696**. Expected L0 comes from independent
raw source, without converted `SectionData` or production `matchesCache`. Higher
levels use the pinned Voxy reducer on independent L0: stored propagation is checked,
not independent correctness of the reducer itself. Some other historical observers
do use production comparison as a premise; distinguish those from this oracle.

Read exact per-cell provenance, private run paths and proof hashes in the current
report/verification. Linux software-GL controls do not prove native Windows rendering
for those cells. Earlier artifact passes are not fresh r5 qualification of all cells.
First compile/fixture/premise/UI/packaging failures remain evidence, including
pre-fix ceiling AIR rejection, wrong Support flags/profile, range-before-READY and
an unjustified warm-hit assertion after pressure. Do not label them benign flakes
or erase them after a corrected run.

## 6. Performance and cache measurements to audit

Performance uses **r3**, `0.16.0-dh-p4.dev20261007.3`, NeoForge SHA256
`80518508d7b964d54d962129a7f2657e192cedbb15351463d22ee93d46b5b67f`.
There are12 actual Windows runs: three paired cold and three paired warm server
runs, alternating arm order. Each requests the same nine detail6 tiles/144 chunk
coordinates against independent stockDH3.3.3 worlds with seed8675309. Actual frame
replay verifies identical complete blocks/biomes/raw light. The official arm uses
its real request queue, decoder, provider and renderer, with no LSS/Voxy; the LSS
arm uses its normal production pipeline with a test-only area restriction.

| Server | Client | Server CPU median, s | Acquisition median, s | Steady-frame p99 median, ms |
| --- | --- | ---: | ---: | ---: |
| Cold | Official DH | 27.27 | 5.124 | 9.300 |
| Cold | LSS + Voxy | 24.08 | 10.945 | 9.307 |
| Warm | Official DH | 0.80 | 3.394 | 9.301 |
| Warm | LSS + Voxy | 1.68 | 6.841 | 9.314 |

LSS acquisition is about2× slower in this small-area experiment. Its bounded
concurrency is a deliberate tradeoff to examine, not a measured performance win.
CPU is whole-server-process first request→last terrain encoding, excluding startup
but including ambient ticks/observer overhead; not isolated generation CPU. Cold
runs produce nine real DH generation events per arm; warm produce zero. Warm
means closed producer world+DH SQLite cloned, with fresh client caches, not rejoin.
Cold saved vanilla records are108 proto/36 missing/zero FULL:144 coordinates of
complete DH terrain are not144 persisted vanilla FULL chunks.

Cold actual received terrain payload is217983 bytes per arm; warm is218035 official
and217983 LSS. These count received DH splits/replies including DH headers, excluding
control/gameplay/Minecraft framing/TCP. NeoForge's repeated encoder invocations are
retained separately and must not be counted as received bandwidth.

Native Windows11/RTX3080Ti/OpenGL4.6/Java21.0.7, requested1280×720/RD4/120FPS cap,
steady identical camera and renderer defaults, DH radius32. Frame samples last60s
after source/readback/provider/real geometry checks and15s settle. GL timer spans
include submission gaps/frame cap; they do not isolate GPU busy time or display
latency. Cold runs were within the user's confirmed idle window; later warm runs
were not confirmed. Uncapped throughput, loading hitches, other GPUs, shaders and
full-radius performance remain unqualified.

LSS144-column conversion median is0.352 CPU/0.447 wall seconds,25.33MiB allocation,
33.54MiB explicitly owned peak returning to zero. Peak process working set is about
2.48GiB cold/2.46GiB warm versus official2.04/2.12GiB. Nine source records occupy
218525 bytes; Voxy disk4.7–6.2MiB and official DH client DB0.64–0.88MiB include
different roles/local terrain and are not interchangeable storage budgets.

NTFS file-quota case retains8192 records/8822784 bytes; byte-quota case retains
7151/268405634. Write p50/p99 is2.243/37.620ms and2.381/34.302ms; read p50/p99
is4.148/9.177ms and4.159/15.362ms; cold indexing703.1/597.6ms. These are bounded
synthetic-position workloads using validated real DTOs, not general filesystem
latency guarantees. Raw data is in `ntfs-cost-summary.json` and both result roots.

Audit `specification.json`, `paired-summary.json`, `paired-measurements.csv`, input
hashes, captures, telemetry, timer/window ownership and paired aggregation. Private
upstream-linked official hooks stay in the ignored local evidence root. The
`r3-r4-class-diff.json` and `r3-r5-class-diff.json` describe subsequent UI/decoder
changes; **do not relabel r3 measurements as a benchmark of the final r5 binary**.

## 7. Known open gates and review priorities

P1's common codec and captured regressions are implemented; P2's functional session
is usable, but its strict I1/completion/persistence gates remain open. P3's settings,
reset, diagnostics, source reuse and amended sync/live behavior are implemented.
P4 has substantial artifact-bound coverage and measurements, not its complete
matrix or final release acceptance. P5 ports are deferred.

The known Voxy counterexample explicitly reads newer yellow vanilla storage before
releasing an older queued red DH write, then reads red afterward. Its experiment
pass flag means reproduction succeeded; `correctness_gate_passed=false` and
`release_blocker=true` carry the product result. Backlog zero and current cache
readback cannot provide a commit fence or persistence acknowledgement. A supported
Voxy API with per-write ownership/epoch guard, atomic commit cancellation and typed
completion/persistence results is the proposed boundary. No production Voxy patch
or upstream coordination has been performed. New/source-less/partial/live terrain
can still replace older unloaded refinement outside unchanged-cache preservation.

Please prioritize:

1. Untrusted wire/cache input, allocation accounting, compression/fragment bounds,
   and every narrow decoder exception. Find a concrete failing input before
   relaxing an existing rejection or treating a producer approximation as corruption.
2. Cross-thread/source/world ownership through receive off, disconnect, native
   priority, null-world/reconfiguration, reset and engine replacement. Distinguish
   queued acceptance from actual destination state.
3. Automatic family detection and one-retry containment, including missing/withdrawn
   channels, slow configs, legacy silence, Support same-connection changes,
   late native offers and same-address backend/software replacement.
4. Rejoin/source admission, zero/old timestamps, stale partial sources, eviction and
   protection, version identity, unsafe foreign buckets, failed replacement and
   clear/reset refusal. Recheck prior fixes as an integrated system.
5. Negotiated pushes, fences, duplicate/old full replies, cancellation-before-send,
   drop/timeout recovery and eventual progress under bounded pressure.
6. Vanilla fringe coverage during movement, current readiness/light retries,
   per-tick/rate bounds and engine/epoch retirement; modern/legacy UI correctness,
   staging dependencies, preferences, translations and real ABI descriptors.
7. Shipping dependencies, relocated XZ/licenses, SQLite deduplication, bytecode/loader
   purity, LSS/VSS wire/branding contracts and complete exclusion of test/private code.
8. Test independence and evidence honesty: source correlation, actual comparison
   counts, artifact identity, generation versus saved FULL status, received bytes,
   paired fairness, CPU/GPU timing scope and storage/process ownership.

Remaining coverage includes full128-radius saturation, longer/multiplayer and
proxy/world-key stress, broader configurations and biome/material/resource-pack
combinations, deeper final Fabric counterparts/all Sodium runtime cells, hostile
filesystem/antivirus/disk-full races, and uncapped/loading frame studies. Outreach,
release decisions and other-line ports are pending. Assess which are blockers,
qualification gaps or deliberate bounded tradeoffs; do not promote samples into
universal guarantees or dismiss the reproduced I1 failure as only missing coverage.

## 8. Safe independent verification

Read [CLAUDE.md](../../CLAUDE.md),
[disposable rig instructions](../operations/disposable-rigs.md) and
[the exact-signature flake catalog](../operations/test-flakes.md). Current review
permissions allow repository and `/tmp` writes; `/mnt/c` is read-only and approval
is unavailable. This briefing authorizes review/reporting, not changing the user's
Prism installation, stopping their server, deleting retained evidence or operating
their foreground. No test requires accessing personal account credentials.

The last manual handoff installed r5 into **LSS dev — 1.21.1 NeoForge - Copy For
Far Testing** and resumed the same **dh-neoforge-playable** world at **[::1]:25585**,
with stockDH3.3.3/no LSS server, generation32/SURFACE_THEN_CHUNKS, sync/live enabled
and OP preserved. It normally owns the shared harness. Receipts record handoff
time, not present uptime; the owned `stop-server.py --status-json` in the outer
`build/dh-neoforge/2026-10-01/` setup is the read-time check.

Do read-only source/manifest/artifact review first. Heavy builds and live clients
must use the shared lock and owned supervisors, sequentially, when the harness is
available. Do not unlink/bypass locks or kill by port/process name. If unavailable,
report the unrun validation; do not silently claim it. For an independent normal
build once exclusive use is available, use a scratch source copy and Java21:

```sh
review_root=$(mktemp -d /tmp/lss-dh-full-review.XXXXXX)
rsync -a --exclude=.git --exclude=.gradle --exclude=build \
  --exclude=.worktrees --exclude=run --exclude=runs ./ "$review_root"/
cd "$review_root"
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 CI=true bash tools/verify/run-gradle.sh \
  --no-parallel -Pmod_version=0.16.0-dh-full.review \
  -I test-fixtures/dh-live/classpath.gradle \
  -I test-fixtures/dh-live/fixture-tests.gradle \
  :common:test :fabric:build :paper:build :neoforge:build \
  :neoforge:runGameTestServer vssJars
python3 scripts/release_check.py --version 0.16.0-dh-full.review
python3 scripts/release_check.py --selftest
python3 -m unittest discover -s test-fixtures/dh-p4/tests -v
python3 -m unittest tools.tests.test_owned_process_grace -v
python3 -m unittest tools.packaging.test_sqlite_capsule -v
```

The fixture-test init script adds four observer tests; report them separately
from production passes. Tier3 client gametests and Folia do not exist on this line.
Bind any new claim to its own sources, jars, fixtures, inputs and immutable attempt.
Retain first failures before a justified retry. Fixture rebuild/run recipes are in
[dh-live](../../test-fixtures/dh-live/README.md) and
[dh-p4](../../test-fixtures/dh-p4/README.md); an old fixture jar is not automatically
valid against a new build or protocol.

Windows native checks require the separately owned non-input desktop and exact
PID/creation identity/kill-on-close job, with no desktop switching, input injection
or clipboard access. The narrowly authorized P4 storage profile retains a50GiB
physical floor and2GiB owned-growth cap; generic/manual8GiB requirements are
unchanged. Cleanup receipts preserve recovery hashes/worlds/captures/results and
label removed inactive derived caches/library copies. Do not delete current paired
inputs or mistake Linux logical reclaimed bytes for equal physical C: reclamation.

## 9. Requested review output

Write `docs/implementation/2026-10-07-dh-full-feature-claude-review.md` in this
checkout. Leave previous reviews and their evidence intact. For each finding give
severity, exact file/line, trigger, consequence, independent evidence/reproduction,
the smallest useful correction and a meaningful regression check. Clearly separate
verified defects, suspected defects requiring reproduction, qualification gaps and
known unresolved gates. A prior disposition is an implementation account, not
independent proof that a finding is closed.

Include an integrated conclusion on source/protocol safety, resource/lifecycle
ownership, cache/vanilla authority, native regressions, packaging and the limits of
the performance evidence. State what you actually inspected or ran, the exact
candidate/scope, and why remaining gates do or do not block release. Review the
known I1 limitation candidly without presenting it as a newly discovered duplicate
or marking P2/P4 complete on enqueue/readback evidence. Do not implement fixes,
stage/commit, merge, contact upstream or publish as part of this review.
