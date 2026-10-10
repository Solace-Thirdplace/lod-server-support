# DH P2/P4 continuation and manual checkpoint

Date: 2026-10-05. Status: final packaged functional validation passed; Prism updated
and manual server/course ready. Work remains
local in `.worktrees/dh-client-p1`, on `feat/dh-codec-p1-mc1.21.1`. No publication,
merge, upstream outreach or Windows foreground automation is part of this work.

The P2 session/scheduler implementation is exercised more deeply, and P4 expands
to both client loaders and both available server families. A real official-15
compatibility defect was found and corrected. Strict P2 completion/write-authority
and the remaining P4 resource/visual gates must stay open until their evidence exists.

Later on the same day, the [ocean lighting correction](2026-10-05-dh-ocean-lighting.md)
supersedes the installed candidate and manual-server configuration below. The
server now allows distant generation within 32 chunks at the user’s request.
The earlier build and receipts remain historical evidence.

## Production correction

Official DH **3.2.0-b for MC1.21.1**, using protocol 15 / format 2 / Zstd, was
rejected with `DH span outside world height`. Its captured builder output has the
same single outside ceiling-air voxel convention as the previously verified Support
and official-16 builders. Inspection of the exact upstream `LodDataBuilder` class
and the captured raw spans establishes that all 4100 outside spans, including four
repeated corners, are AIR ending at relative Y=385 in a height-384 world.

`DhTerrainDecoder` now admits the existing narrow normalization for `OFFICIAL_15`
too. It clips one ceiling-air voxel only when the span starts within the world;
solid overshoots, larger overshoots, wholly outside spans, zero heights and overlaps
remain errors. Three regressions failed against the old code and pass after the
correction. The previous official-15 rejection pin was based on an unverified
builder family; the real producer/capture evidence justifies updating that pin.

The new
[protocol-15 corpus](/home/vox/projects/voxel-server-support/.worktrees/dh-client-p1/common/src/test/resources/dh-corpus/dh-official-3.2.0-b-neoforge/README.md)
contains verbatim fragments and their owned response, with producer/candidate/run
hashes and bytecode/span inspection provenance. It is a wire/height regression,
not a durable-storage or visual oracle. The corrected packaged NeoForge client
already passed against that upstream artifact, including 11,520 actual Voxy storage
samples and 8,519 solid samples.

Compared with the previously verified P4 candidate, the final jar has the same 985
shipping classes; **only `DhTerrainDecoder.class` changed**. The eleven new real-session
tests and all private observers remain outside the shipping jars.

## P2 regression coverage

`DhClientSessionTest` now has 35 cases. The eleven additions drive the actual
session worker, captured terrain, conversion, main-thread handoff, Voxy acceptance
bridge and receipt policy. They cover all eligible columns, one rejected column
and recovery, failed refresh timestamp invalidation, partial captured coverage,
vanilla arrival before conversion and before handoff, retirement while conversion
is active, duplicate responses, three wholly rejected tiles, refusal ownership,
and a changed world key on the same Minecraft connection.

These establish enqueue ownership and bounded failure behavior. They do not turn
Voxy queue acceptance into a completion or persistence acknowledgement. The
installed `rawIngest` returns a boolean for enqueue acceptance. Its backlog probe
counts queued permits, which are consumed before workers finish; zero backlog is
not a completion barrier. Diagnostics and receipts remain explicitly session-local.

## Normal build and packaging

The final candidate is a normal Java-21 Gradle build, version
`0.16.0-dh-p2p4.dev20261005.1`. Its NeoForge artifact is
`lod-server-support-neoforge-dh-dev-mc1.21.1.jar`, SHA256:

```text
b42dd5800cc9645434f810a1a6911d4400ab9addbcde35c53c589121001d044b
```

Evidence lives in the outer checkout:

```text
build/dh-neoforge/2026-10-05/p2-p4-final/
```

`verification.json`, source/test manifests, fixture receipts and normal XML bind
exact candidate and build inputs. The initial development attempts and pre-fix
reds remain separately preserved in `p2-p4-completion/`; they are not candidate
passes or known test flakes.
The compact [checkpoint receipt](evidence/2026-10-05-dh-p2-p4-checkpoint.json)
records the final cases, artifact, manual setup and remaining gates.

| Check | Final result |
| --- | --- |
| Common unit | 994 passed |
| Fabric unit | 1698 passed; four existing opt-in skips |
| Paper unit | 536 passed |
| NeoForge unit | 23 passed |
| Total normal unit | **3251 passed**, zero failures/errors |
| Fabric server gametests | All 78 required tests passed |
| NeoForge server gametests | All eight required tests passed |
| LSS/VSS packaging | All six versioned artifacts pass |
| Packaging selftest | 126 cases and nine SQLite helper tests pass |
| Private observer tests | Four passed separately; normal XML preserved before that task |
| Private functional live matrix | Eleven final scenarios pass; see `live-matrix.json` |
| Controlled Voxy queue-order experiment | Counterexample reproduced; I1 correctness gate fails |

Heavy builds and live clients run serially under the shared harness lock, with owned
Xvfb/llvmpipe displays, exact process identities, fresh disposable worlds, frozen
launcher inputs and disk admission/runtime monitoring. The dedicated manual server
was paused during those checks. All 42 recorded private process identities are
stopped, and the existing manual world has now been resumed.

## Live evidence and fixture development

Final passing cells cover NeoForge Support FullBuilder/FastOverworldBuilder,
official protocol 15/16, vanilla/no-service, a wrong profile and the real-DH-client
conflict, plus Fabric Support/official-16 and NeoForge lifecycle/content checks.
Official 3.3.4 still has no matching MC1.21.1 artifact in the checked primary
Modrinth API; 3.3.3 is the explicitly recorded available protocol-16 pairing.

The final lifecycle test verifies movement, reset, refresh on/off, receive on/off,
Nether/End with zero skylight, return, source switching and ordinary connection
survival. Its initial readback checks 8640 real storage samples, including 6915
solids across nine columns. The software rendering control reports 112924 quads
and 123 visible sections; that is not a hardware frame-time measurement.

The final content test verifies custom height -128..383, source-distinct native/DH
colours, same-session unchanged and removal replies, and reconnection to native
protocol 20. Removal compares 300 block samples with present-section/gold sentinels
and verifies the lower terrain also changed; native reconnect delivers 16 columns,
compares 300 source-specific samples and retires the DH budget to zero.

The final `live-matrix.json` also retains a failed first `fabric-support` attempt:
"Insufficient solid readback: 0 columns". It is excluded from the eleven passing
cells; its log and run directory remain evidence, not a production success.

Several early fixture failures were diagnosed and preserved: incomplete compile
classpath, an oversized remapper input, use of NeoForge classes for Fabric remapping,
a stale Fabric Loader version, Support's valid zero timestamp, air-state
canonicalization, omitted all-air Voxy sections, and the bounded sample set filling
with columns subsequently claimed by vanilla. The fixtures now use exact loader
compile classpaths, Fabric Loader 0.18.4, receipt snapshot state and ongoing
main-thread removal of vanilla-owned observations.

Generic storage comparisons canonicalize air variants and exclude missing air
sections from evidence; they still fail on missing expected solid sections. They
record how many air samples were unobserved. The independent custom-height/removal
observer keeps its strict section-existence requirement and gold sentinels.
The strengthened queue-order experiment tracks every DH-owned section separately
from vanilla, checks real yellow vanilla storage, and then releases the older red
DH write. It reproduced red overwriting yellow after a controlled 4516 ms delay.
Its counterexample result is an I1 correctness failure, even though the experiment
completed successfully. See the [commit gap and proposed API boundary](2026-10-05-dh-voxy-commit-gap.md).

`dh-resources.json` records converter CPU/wall time, allocated bytes, production
wire/queue counters and peak owned memory. Server process CPU/RSS samples include
startup and generation; these are descriptive measurements. They do not establish
a matched-area comparison with an official DH client or Windows GPU frame times.

## Manual setup and remaining gates

**Historical setup below.** Later builds/configuration superseded this receipt;
the corrected post-review client is now installed and the same server has resumed.
Use the [manual guide](2026-10-05-dh-manual-test-guide.md) for current status.

The selected Windows Prism instance is
**LSS dev — 1.21.1 NeoForge - Copy For Far Testing**. The manual server remains
**[::1]:25585**, resuming **dh-neoforge-playable**. A 192×192 course in a previously
unused region has nine DH tiles, seams/corners at negative coordinates, water,
glass, leaves, stairs/slabs, a hollow building, lights and a removable landmark.
Its server profile synchronizes pre-existing terrain with distant generation and
unsolicited updates off; the previous DH configuration is backed up. Vanilla
terrain generation remains available. This keeps the intended course checks bounded.

The [manual test guide](2026-10-05-dh-manual-test-guide.md) covers Windows rendering,
movement across vanilla/DH boundaries, refresh/removal, reset/rejoin and frame times.
The final tested jar is installed and hash-verified, with a backup under
`minecraft/lss-dh-backups/20261005T162451.495024Z`; client preferences are unchanged.
`prism-installation.json` records the transaction. The server is running, its
Minecraft status is protocol 767, and the Windows IPv6 TCP check passes.
`test-setup-completed.json` records its exact owner and console session 56687.

All 185 course commands executed, the world was saved, and a combined server-side
block check verifies the nine coloured floors, water, glass, leaves, stairs/slabs,
hollow building, lights, gold landmark and overhead air. The 144 course chunks
remain force-loaded so edits can run while the viewer stays distant. Nine detail-0
DH database rows and all nine expected floor mappings are present; those checks
are not a decoded-content oracle. Daylight and weather cycles are paused at clear
noon for visual comparisons.
The course and config receipts are `manual-course.json`,
`manual-course-dh-database.json` and `manual-server-configuration.json`.

Final storage admission has 351.33 GiB Linux free and 74.60 GiB on the actual WSL
VHD host volume; the 50 GiB runtime reserve remains intact. Private failure evidence,
build copies and existing/manual worlds are retained.

P2 cannot be marked complete while accepted receipts lack a per-write completion
acknowledgement and the absolute vanilla-authority invariant is unresolved inside
Voxy's asynchronous queue. P4 also retains the matched official-client load
comparison, hardware frame-time check, exhaustive live configuration/failure matrix,
and any uncovered artifact/version cases. The additional Minecraft support-line
ports remain P5, outside this batch. Outreach is still pending.
