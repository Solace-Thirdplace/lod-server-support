# Complete DH compatibility feature — Claude review briefing

Date: 2026-10-09. Review the **entire cumulative feature** against the pre-feature
v0.16.0 baseline, including the October 8 review fixes and October 9 menu change.
Produce findings only; preserve the index and working files. Do not implement
fixes, install mods, stop the manual server, merge, publish or contact upstream.
Work remains local and outreach is pending.

## Checkout and complete scope

```sh
cd /home/vox/projects/voxel-server-support/.worktrees/dh-client-p1
git status --short
git rev-parse HEAD
git diff --check
git diff 6677fe93dfc35b8ca1e158972d4cf58eb9703bd2 --stat
git diff 6677fe93dfc35b8ca1e158972d4cf58eb9703bd2 --
git ls-files --others --exclude-standard
```

Branch: `feat/dh-codec-p1-mc1.21.1`. HEAD remains
`6677fe93dfc35b8ca1e158972d4cf58eb9703bd2`, the pre-feature
`v0.16.0+mc1.21.1` baseline. This is a separate clone despite its `.worktrees`
name. Changes include staged additions, unstaged modifications and **untracked
production code, tests, fixtures and captured corpora**. Inspect untracked files
directly: neither a plain diff nor a baseline diff includes them. Existing
incremental evidence diffs are review aids, not the whole feature. The current
evidence directory also has `review-scope.json`, listing/hash-binding 338 changed
or untracked files, including the full review documentation and binary corpora.

Read `CLAUDE.md` and the pinned tests before proposing a behavioral change.
Scope is Minecraft **1.21.1**, **Java 21**, Fabric and NeoForge clients. The real
producers qualified locally are official DH **3.3.3 / protocol 16**, official
**3.2.0-b / protocol 15**, and Paper **DH Support 0.14.0 / Support protocol 15**.
Other Minecraft lines, future DH versions and arbitrary server configurations
are not certified. There is no Tier 3 client gametest or Folia task on this line.

## Current behavior and superseding decisions

LSS can acquire DH server terrain and hand it to Voxy without a DH client mod or
LSS on the server. The adapter is opt-in; installing the real DH client disables
it to avoid owning the same channel. Missing Voxy, disabled reception, a missing
remote service and malformed input must preserve ordinary Minecraft usability.

**Native LSS takes precedence.** General’s **Use Distant Horizons** permits DH
fallback; it does not force DH. Native remote-channel/session-config offers win,
including disabled or incompatible native offers. Late native offers retire DH
and cancel pending discovery retries. Far players and Xaero remain native-only.
DH does not dispatch approximate columns into `LSSApi`/third-party native consumers.

**Protocol selection is automatic.** Each physical connection starts with the
protocol-16 probe and requires valid server configuration and world identity.
Strict control decoding distinguishes `OFFICIAL_16`, `OFFICIAL_15` and
`SUPPORT_15`. The protocol-15 families differ in strings and timestamps; ASCII
ambiguity, terrain and address hints cannot authorize a guessed decoder.
Support’s unambiguous rejection permits switching on the same connection.
Official 3.2.0-b can fail while logging a mismatch before sending its rejection;
a bounded quiet trial waits 15 seconds plus three idle client ticks, with no
captured/decoding work or recent arrival, before at most one guarded initial-play
reconnect. Any response disables that trial. Confirmation survives dimension,
respawn and same-connection world replacement. This is LSS’s compatibility
strategy, not stock DH backward-version negotiation.

**Rejoin uses both caches.** The bounded DH source cache stores original validated
DTOs, separately from Voxy’s terrain. Load synchronization validates timestamps
before restore. Unchanged matching destination columns cause zero conversion or
ingest; default missing columns can rebuild. Differing nondefault destination
terrain is preserved and an unavailable reader defers. Changed/missing sources
require data. Rejoin does not blanket-download or reimport every cached tile.
Successful source admission includes actual matching destination readback, which
is still not a persistence acknowledgement.

**Synchronization is event-driven.** Optional sync on load follows the upstream
20-minute load/re-entry debounce. Optional live updates accept negotiated pushes.
There is no periodic refresh timer or stationary polling. Receive OFF withdraws
subscriptions and stops acquisition without erasing destination terrain. Resume
can use retained same-connection receipts and preferences.

**The October 9 UI is authoritative:** General and Far Players are the two LSS
pages. General ends with Xaero and **Use Distant Horizons**, a checkbox retaining
`lss:lod_source` and `lod.source` identity. The separate DH page, Sync on Load and
Live Updates controls are removed from modern and legacy Sodium on both loaders.
`lod.dh.sync_on_load` and `lod.dh.real_time_updates` remain file-only, default
**true**, and hot-reload through `/lss reload`. Existing false choices are preserved.
Use Distant Horizons remains default false and requires reconnect. Default DH
keys are omitted during YAML saves for native-only v0.16.0 downgrade compatibility;
nondefault DH keys require a DH-capable client. Retired timer/profile settings
remain preserved but inactive. All three locale key sets stay aligned.

## Source map and contracts

| Area | Files and responsibilities |
| --- | --- |
| Pure codec | `common/src/main/java/dev/vox/lss/common/dh/`: profiles/input/messages, strict framing/discovery, positions, fragment assembly, limits/leases, normalized materials/terrain. No Minecraft, loader or upstream runtime dependency. |
| Acquisition policy | Same directory: `DhConnectionState`, `DhSessionPolicy`, `DhBandwidthRamp`, `DhAcquisition`; lifecycle, timestamp/coverage/refusal state, pacing and diagnostic gates. |
| Persistent sources | `DhTerrainCache` in that directory: identity, complete DTO admission, atomic IO, corruption handling, shared quotas, protected working sets, clear/revision ownership. |
| Runtime and ownership | `xplat/src/main/java/dev/vox/lss/networking/client/`: `DhClientSession`, `ClientNetGlue`, `ClientSessionGate`, `ClientIdentityResolver`, `ResetCoordinator`, `ClientCommandActions`, `ColumnCacheStore`. |
| Conversion/destination | `DhColumnConverter`; `xplat/.../compat/{DhVoxyBridge,VoxyCompat,VoxyTerrainCache,ModCompat}`: registry resolution, complete-column checks, vanilla authority and real destination readback. |
| Vanilla fringe | `xplat/.../compat/VoxyVanillaCoverage`: actual loaded chunks outside the renderer’s exclusion, per-chunk light readiness, moving/reset retries and budgets. |
| Loader seams | Fabric/NeoForge `DhClientTransport` twins, client/bootstrap entrypoints, command and channel-advertisement wiring, `ClientTransferHook` for connection transfer ownership. |
| Settings/UI | Common `ClientSettings`, schema/specs and YAML; shared `ClientOptionCatalog`; both `LSSConfigMenu`, `LegacySodiumPage`, legacy hook; three locale resources and generated references. |
| Status/trace | Common `DhStatusSnapshot`/status text; xplat `DhClientTrace`, `ClientNetTrace`, `ClientTraceLog`, `ClientStatus` and local exports. |
| Packaging/operations | Gradle, XZ notice/license, release/artifact gates, LSS/VSS branding, SQLite capsule; `test-fixtures/dh-live`, `test-fixtures/dh-p4`, `tools/dh`, owned-process/storage/private-display tooling. |

All six LSS/VSS artifacts must exclude observers, private upstream-linked hooks
and compile-only Sodium stubs. XZ is relocated with its license/notice; common
remains Minecraft-free Java 21. NeoForge SQLite stays a stock nested jarJar library
for Voxy module deduplication. Display/local branding changes must not change the
`lss:*` wire surface or native protocol fidelity.

### Codec and terrain

Review both terrain formats, compression 0/3/4 (none/XZ/Zstd), prediction,
negative tile coordinates, edges/corners, mapping IDs, family-specific UTF and
timestamps. Limits include 1 MiB frames, 16 MiB tiles, 64 MiB decoded data,
256 MiB explicitly owned bytes, eight incomplete buffers and a 4096-block height
ceiling. Idle expiry is 10 seconds; normal birth lifetime is 60 seconds and
optional official startup work is bounded at 120 seconds. Capture, envelope,
assembly, completed payload and decoder ownership overlap must be accounted.

Only fully described Minecraft columns can be handed off. Unknown coverage is
not AIR; DH terrain remains the producer’s block-resolution approximation.
Captured corpus/provenance in `common/src/test/resources/dh-corpus/` supplies
external oracles beyond self-round-trips. Narrow top-AIR/exclusive-ceiling and
official16 SURFACE-stage overshoot exceptions must not accept solids/water or
invent coverage. Profiles/stages/materials outside those captured exceptions
remain rejected. Synthetic CloseReason tests are defensive controls, not proof
that stock mismatch logging reaches the handler.

Check biome/property fallback, packed lights, height bounds and column coverage.
Ocean attenuation includes flowing/waterlogged water and water plants; no-sky,
block light and section seams stay distinct. Air above a fluid floor copies that
floor’s block light while retaining its own skylight. Source cache conversion
identity is **4**. A one-voxel air gap’s two faces share Voxy’s single light value;
the floor rule is a documented approximation, not exact official-DH face lighting.

### Scheduling, cleanup and invariants

DH uses its own tile scheduler. Server generation/sync rates, ranges, borders,
bandwidth and transport/consumer pressure remain authoritative. Current caps are
128 chunks for official interest/live reach, 32 for generation, and 32 for
Support interest (subject to server limits); the larger shared UI range is not
a promise of larger DH acquisition. Local policy
allows one generation and two total requests; the manual cap charges 16 columns
per tile across request classes and cached conditional validation. Official sync
has a 50 ms minimum, generation 500 ms; server/manual/refusal constraints can
extend these. Support has its own minimum and startup request spacing.

Temporary pressure/refusal preserves valid source snapshots and permanent
rejection strikes. Unverified deferral clears unusable conditional timestamps;
range refusal parks separately and re-arms on changed interest. Only official
generation outside interest is cancellable, after two seconds plus 12 chunks of
hysteresis; local sync expiry must not pretend an upstream permit was released.

Adaptive Rate plus Slow Start optionally advertises a one-way **50–500 decimal
KB/s** official ramp owned by the physical connection. Dimension/reception changes
retain earned progress. Sparse delivery can keep it below 500 for the connection;
startup requests still have a bounded 120-second birth lifetime. Hot OFF opens
immediately; hot ON waits for the next physical connection. Support 0.14 ignores
the bandwidth field and instead gets five seconds of local request spacing
(4× for 2.5 seconds, then 2× for 2.5 seconds). This is not burst throttling or the
entire stock adaptive congestion controller.

Check the plan invariants together: vanilla-held columns excluded at admission
and handoff (I1); DH invalidates native freshness hints before switching back
(I2); one active source through takeover/reception/reconfiguration (I3); reset
retires and drains DH workers before Voxy teardown, refusing unsafe retirement
(I4). Epoch/Connection/level/engine identity must reject stale work. Cancellation,
queue rejection, timeout, malformed input and retirement release leases exactly once.

October 8 cleanup uses the oldest owned captured/processing arrival as a safe
cutoff, or current time when no capture remains. Capture publication and cleanup
share a lock. Post-deadline unrelated traffic cannot indefinitely protect silent
work while the worker progresses; timely queued replies remain protected. A hung
pre-deadline capture can still delay expiry. Generation-pruning keeps its quiet
capture guard because anonymous fragments cannot yet identify the generation owner.
Pressure recovery closes admission until worker cancellation of cleared request
owners completes, including a registration queued during recovery. A failed
completed-buffer reservation discards its specific retained fragments immediately.

Fragment assembly borrows Split’s internally immutable backing array under its
own reservation, removing one clone. Public construction/accessors stay defensive.
Flattening/decompression/cache interfaces and resource limits are unchanged.

### Source cache, pushes, fringe and diagnostics

The `!dh-terrain-v1` layout version is **2**, independent of conversion version 4.
Identity includes server/address/world/dimension/seed/profile/shape. Shared quotas
are **8192 files / 256 MiB** across owned buckets. Working-set protection is shared
within one JVM, not across processes. Unvisited history remains evictable.
Review atomic force/rename, hashes/size/layout/identity checks, no-follow IO and
Windows junction/reparse refusal. Quota trim skips undeletable files and reserves
space before committing. Clear skips foreign/refused roots; repairing an owned
damaged revision file preserves its inode under the existing cross-process lock.
Read-only/transient failures must not corrupt receipts or exceed quotas.

Push messages 9/10 require negotiation, profile/key/distance/timestamp validation,
bounded assembly and full-column masks. Partial updates invalidate old complete
sources; per-column fences and whole-tile stamps have different authority.
Competing requests, timeout and pressure must recover without publishing false
unchanged/complete source state. Inspect receiver OFF subscription withdrawal,
native takeover, reset and same-connection resume.

The fringe pump ingests actual vanilla chunks even in single-player/no-LSS worlds.
It never requests/generates terrain. Budgets are 64 actual lookups/tick,
256 geometric candidates/tick, eight submissions/tick and 128 submissions/second.
Light readiness is per chunk/section; unrelated queued lighting cannot stall all
ready border columns. World replacement/reset and weak references remain guarded.

`/lss diag` is the shared command; redundant `/lss dhstatus` was removed.
Opt-in traces flush a start row and record DH request/reply/pressure and sampled
`dh_status` rows, plus generic vanilla network context. Acquisition diagnostics
report an allowlisted primary gate, secondary mask and known local wait, not a
terrain-delivery ETA. Unknown waits are -1. The first candidate reaching admission
owns primary reason/time; observations age between scans and invalidate on movement,
service/preferences/cache changes. No extra terrain scans or probes are added.
Trace OFF must avoid formatting/probes; local export must not expose addresses,
world keys, payloads or server-controlled strings.

## Accepted limitation and remaining qualification

The user’s [October 7 acceptance](2026-10-07-dh-accepted-limitations.md) is current:
Voxy `rawIngest` only acknowledges enqueue; it cannot cancel/fence an accepted
write or give per-write completion/persistence. An artificially delayed older DH
write overwrote newer vanilla terrain. The absolute I1/completion guarantee at
that supported-API boundary is **accepted for now and not a release blocker**.
Do not report acceptance as a passing ordering guarantee or re-open it solely
because the API lacks those facilities. Defects in implemented guards remain valid
findings; all other correctness/release requirements remain.

The user’s movement-related pause is not proven reproduced/fixed by bounded
progress tests. Potential 256-section pressure, moving-range rearming, registry
hash/cache-poll cost and backend identity effects need representative evidence
before policy changes. Larger/full-range fills, additional server/proxy-transfer
configurations, shader/Iris/Sodium Extra layouts, loading/uncapped FPS studies and
S0–S2 outreach/release prerequisites are not certified as complete.

## Validation and performance evidence

Current candidate: **0.16.0-dh-menu.dev20261009.1**.
Build copy: `/tmp/lss-dh-menu-20261009-r1`. Evidence root (in the parent repository):

```text
/home/vox/projects/voxel-server-support/build/dh-neoforge/2026-10-09/menu-simplification
```

| Loader | Artifact relative to evidence root | SHA-256 |
| --- | --- | --- |
| NeoForge | `lod-server-support-neoforge-dh-dev-mc1.21.1.jar` | `77923e2f0f944aebf3d71a2079c72d0c13ae3388cd940c84313d6ccd6e22abd8` |
| Fabric | `fabric/lod-server-support-fabric-dh-dev-mc1.21.1.jar` | `f5a1df877ab9c60e0de849e07915d412531a0c5a44e4c71b6a5597504f542c0a` |

`verification.json` and the Fabric counterpart bind **400 production/build inputs**
and **1023 test/corpus/tool inputs**. Production manifest SHA-256:
`3af96d194396455e5175669ff9a19547ae7ac85ec778c7111987edc402fb45a1`.
Test/tool manifest SHA-256:
`4f61e61732b33f5a8ce0f6613d9774061b5c255ffa7c6d62a0dda93f41aae045`.
The latter was re-bound after permitting the Fabric menu-only fixture scenario;
production/unit inputs and shipping jars were unchanged by that fixture-tool edit.
Later prose is not compiled-source identity. Exact build, fixture and case receipts
retain their separate timestamps.

Normal Java 21 validation passed **3,421 production unit tests**:
1,044 common, 1,818 Fabric (1,822 found, four optional skips), 536 Paper and
23 NeoForge; zero failures/errors. **78 Fabric + eight NeoForge server gametests**
and all six LSS/VSS release/artifact checks passed. Catalog validation/render and
generated settings-reference checks passed. Normal XML is retained in `unit-xml/`.

**Four fresh packaged checks passed:** modern Sodium on NeoForge and Fabric,
NeoForge load/rejoin/live/reception through config-file reloads, and actual legacy
Sodium 0.6.13. Menus run at 1280×720 / Auto scale 3 on private Linux software
rendering. Screenshots were visually inspected: the full checkbox label fits,
there is no DH page or sync/live control, and Apply preserves both preferences on.
Legacy audits its six total pages’ tab/pagination/donation bounds.

The live case records 10,411 actual block comparisons including settling retries,
zero target payloads/conversions on unchanged rejoin, push-only edits/removal,
30 seconds of retained terrain while updates are off, and zero requests/pushes
while reception is applied OFF. One request while OFF was pending is recorded
separately. See `live-matrix.json`, individual immutable attempts and fixture receipts.
All private owners retired (`live-retirement.json`). No new Windows/GPU or
server-load performance measurement was run for this UI increment.

The tested NeoForge jar is installed in **LSS dev — 1.21.1 NeoForge - Copy For Far
Testing**. Minecraft was closed; the old jar is backed up and preferences/other
mods hash-preserved (`prism-installation.json`). The same **dh-neoforge-playable**
world resumed at **[::1]:25585**. Owner identity, OP/DH config hashes, semantic
properties, fresh console list/save acknowledgements and the protocol-767 status
reply passed at **2026-10-09T16:12:36.143841+00:00**. These are point-in-time receipts;
check current liveness independently before joining. New bounded DH runs reserve
40 GiB plus 2 GiB growth; historical v1/generic policies retain their own limits.
[Completion receipt](evidence/2026-10-09-dh-menu-simplification.json) and
[menu implementation](2026-10-09-dh-menu-simplification.md) record this handoff.


`class-comparison.json` compares this build with the October 8 tested r2 jars:
only `ClientOptionCatalog`, `LegacySodiumPage` and its Bounds class differ per
shipping loader. The runtime DH pipeline and native classes are byte-identical,
including Fabric’s nested common codec. The October 8 ten-case functional matrix
remains evidence of those unchanged runtime classes, not ten fresh runs of this
menu binary. It covers both official families, Support, both loaders, movement,
native precedence, live/load/rejoin/reception, no-service, conflict and synthetic
capture-pressure recovery on the same connection/session.


Historical full pipeline and P4 receipts cover independent raw-source blocks,
biomes/lights and Voxy levels 0–4, no-sky/custom heights/partial generation,
large-range dimension/cache pressure, NTFS quotas/locks/junctions and narrow legacy
menus. Higher-level expectations use Voxy’s reducer on independently derived L0;
they verify stored propagation, not independent correctness of Voxy’s reducer.
Synthetic cache records and injected pressure fragments are labelled, not claimed
as thousands of genuine downloads or naturally occurring server pressure.

The [October 7 measurement report](2026-10-07-dh-p4-coverage-performance.md) binds
its **older r3 binary**, not this candidate. Paired official-DH/LSS runs requested
the same nine tiles / 144 columns. Cold generation events and payload bytes match;
windows, whole-server CPU, request scheduling and equal 120 FPS caps limit inference.
The LSS scheduler was subsequently changed. Windows RTX 3080 Ti steady-frame p99
was about 9.3 ms for both arms; these samples do not establish loading hitches,
uncapped speed or GPU busy time. Warm samples exceeded the initially confirmed
idle-PC window and retain that disclosure. Cache costs and NTFS eviction tails
are measured, but the source and renderer caches serve different roles.

The [October 8 allocation report](2026-10-08-dh-official-behavior-followups.md)
records savings of 10,528–49,792 allocated bytes/tile across four real corpora.
The measured assembly prototype differed from the final source but had the same
`accept()` implementation. CPU ratios are noise-dominated; no CPU/FPS speedup is
claimed. Neither this menu change nor later review fixes reran GPU/server benchmarks.

Read the original plan’s dated amendments, the October 7 full-feature disposition,
[October 8 fixes](2026-10-08-dh-post-review-fixes.md), and this briefing’s current
candidate receipts. Historical artifacts, failures, blocker fields and old menu
shapes retain their capture-time meaning. Do not silently relabel old results
as tests of the current binary or treat earlier superseded behavior as a requirement.

## Review priorities and output

Prioritize ownership/lease release and malformed-input containment; source and
connection takeover; codec/cache validity; complete/unknown/update authority;
reconnect preservation; shared pacing and recovery liveness; trace privacy and
truthful diagnostics. Review native/client/server/wire and branding regressions,
loader twins and the actual API descriptors, packaging exclusions, settings defaults
and downgrade behavior. Verify the new General checkbox and hidden settings on
both menu generations rather than expecting the retired DH page.

Read test javadocs before calling a deliberate pinned tradeoff a bug. Verify each
finding against production paths with a concrete trigger, expected/actual result,
file/line references and a focused reproduction. Classify actual defects separately
from accepted limitations, unproven performance concerns and coverage gaps. Check
observer premises: accepted enqueue, current readback and persistence differ;
missing all-air storage cannot count as an independent air match.

Use Java 21 and the maintained wrapper for any needed tests. Heavy builds and
private clients need the shared harness exclusively; do not stop the user’s manual
server or contend with another run as part of this read-only handoff. Ask the user
before scheduling another performance measurement or changing the running setup.
The recipes are in the fixture READMEs and current evidence directory. Preserve
failed attempts and exact source/jar/observer hashes; report actual test counts.

Write findings to:
`docs/implementation/2026-10-09-dh-full-feature-claude-review.md`.
End with examined areas, evidence reproduced, untested claims and the remaining
confidence limits. Do not mark the entire original plan or a release complete.
