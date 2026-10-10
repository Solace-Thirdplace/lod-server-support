# DH P4 review disposition and corrected candidate

Date: 2026-10-05. The verified code, test-fixture and documentation findings from
[the preserved Claude review](2026-10-02-dh-p4-claude-review.md) are addressed.
Normal build, regression and packaging checks pass. After disk capacity recovered,
both corrected private content and lifecycle runs passed against the exact candidate.
`candidate_verified=true` now qualifies the local NeoForge 1.21.1 / official DH
3.3.3 candidate. `release_ready=false` remains intentional: broader plan gates are
still open. The verified mod is installed in the selected Windows Prism instance,
and the same manual test-server world is running again.

Work stays local, uncommitted and unpublished in the separate implementation clone
at `/home/vox/projects/voxel-server-support/.worktrees/dh-client-p1`, branch
`feat/dh-codec-p1-mc1.21.1`. The outer checkout's unrelated changes are preserved.
Upstream outreach remains pending.

## Findings and corrections

| Finding | Verification and disposition |
| --- | --- |
| P4-1: session restart resets refresh age | Confirmed with failing new policy and real-session regressions against the old implementation. The connection-owned policy stores its applied interval and last acceptance time. Repeating an interval leaves deadlines intact; changing an active interval uses acceptance time plus the new interval. Enabling held successful snapshots still starts at `now+R`, preserving the existing pin. Both regressions and the full suite pass after correction. |
| P4-2: unsettled acquisition and undersized deadline | Verified the production scanner's acquisition-first ordering and the old range/deadline. Content is staged at range 12. Before refresh it requires no untouched eligible frontier, no pending requests, and five seconds with a stable request count. Refresh phases receive `30 + completedTiles*10 + 90` seconds. Client/supervisor ceilings are aligned at 1800/1860 seconds. The corrected live run settled 13 completed tiles and accepted the edited target after 132.6 seconds, within its derived deadline. This pass does not retrospectively establish the old timeout's cause. |
| P4-3: retained content can satisfy source claims | Confirmed the old scenario compared identical native/DH blocks. Source changes now happen while physically disconnected: red/blue becomes lime/cyan before DH, removal also changes cyan to purple, and native reconnect receives yellow at the upper level. The live run passed source-specific checks on all 16 converted DH columns and all 16 new native deliveries, together with actual Voxy storage checks. |
| P4-4: timeout attribution overclaimed | Corrected the content record, fixture README, plan and historical verification JSON. The first live run missed its target conditional request deadline; frontier preemption and fixed-order starvation both fit. The independent regression demonstrates the latter mechanism. No new claim of a proved live cause is made. |
| P4-5: missing sections count as air | Removed zero-array substitution. Storage checks require each section to exist. A gold slab at Y=340 remains in every upper 32³ section, alongside the removed upper slab. The lower slab must change to purple. A separate observer test pins that a missing section cannot satisfy the air expectation. |
| P4-6: observer errors stop production DH | Capture/send/receive/conversion exceptions are contained in `callbackFailure`; the content tick reports them promptly. Receive takes one level snapshot and rejects obsolete epoch ownership before inspecting pending work. Established unchanged/removal phases also fail immediately on disconnect or inactive epoch. Real Fabric-loader observer tests pass for capture overflow, write failure and absent level. |
| P4-7: missing scheduler pins | Added actual session tests for ring-order ties, a five-second no-send interval, older vanilla/border/range-ineligible candidates, disabled synchronization/zero sync rate, and a refresh still pending after eleven seconds. The session-replacement regression retains the same policy and requires a complete first sweep before any repeat. All 24 session tests pass in the normal build. |
| P4-8: standalone bootstrap leaks | Verified mtimes and untracked status. Archived and removed nine identified 2026-10-01 17:xx Sodium config/processedMods/log files; preserved the earlier unrelated log and tracked configuration. Historical standalone scripts now create/use runtime cwd before JVM launch. Corrected scope wording in the brief, record and verification JSON. The deleted temporary P3 classpath still prevents replaying the old runner; its logs are historical evidence. |
| P4-9: assorted rig/assertion gaps | Requires usable synchronization and disabled generation/pushes; continuously checks native suppression; requires v20 at both native connections. Changed content must match an owned response ID, its epoch, 16 converted columns and the accepted receipt timestamp after pending removal. Teleport waits for permission and retries. Staging waits for all three fills, the forceload and save. Storage reads are limited to 1 Hz. Explicit candidate/fixture/build/verification inputs, compiled-fixture provenance, source manifests and same client/server hashes are checked; an old P3 artifact is demonstrably rejected before staging. Content mode skips generic control captures and records target requests, fragments and changed/unchanged responses. Request keys are explicitly sanitized reconstructions. The stale P1 oracle path is corrected. |

The observer also requires a successful owned unchanged target exchange before
editing, so the planned corpus covers both unchanged and changed conditional replies.
Per-request JSONL records position, timestamp, wall time, monotonic time, phase and
changed/unchanged/empty outcome. Requests from inactive/obsolete session owners are
ignored. Client/server source-switch commands and save acknowledgements use atomic
files within the owned fresh run; personal worlds are never selected.

The [scoped correction diff](evidence/2026-10-05-dh-p4-review-fixes.diff) and
[file/hash manifest](evidence/2026-10-05-dh-p4-review-fixes.json) compare the initial
review corrections with the independent reviewer's frozen build copy. The later
captured-content regression and corpus are bound by `test-source-manifest.json`
and the corpus provenance described below.

## Exact build and test evidence

Evidence is in the **outer checkout**:

```text
/home/vox/projects/voxel-server-support/build/dh-neoforge/2026-10-05/p4-review-fixes/
```

`verification.json`, `source-manifest.json` and `test-source-manifest.json` bind the
candidate, current production inputs, test/rig inputs and compiled fixture. The
normal build used `/tmp/lss-dh-p4-review-fixes`, Java 21 and the shared harness lock:

```bash
CI=true JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
  bash tools/verify/run-gradle.sh --no-parallel \
  -Pmod_version=0.16.0-dh-p4.dev20261005 \
  :common:test :fabric:build :paper:test :paper:shadowJar \
  :neoforge:build :neoforge:runGameTestServer vssJars
python3 scripts/release_check.py --version 0.16.0-dh-p4.dev20261005
python3 scripts/release_check.py --selftest
```

| Check | Result |
| --- | --- |
| Common | 993 passed, zero failures/errors/skips; whole suite rerun after adding the captured-content regression |
| Fabric unit | 1687 passed, four opt-in experiment tools skipped, zero failures/errors |
| Paper | 536 passed, zero failures/errors/skips |
| NeoForge unit | 23 passed, zero failures/errors/skips |
| Session suite within normal Fabric run | 24 passed, zero skips |
| Fabric server gametests | All 78 required tests passed |
| NeoForge server gametests | All eight required tests passed |
| Versioned packaging preflight | All six normal LSS/VSS artifacts pass |
| Packaging selftest | 126 cases plus nine SQLite helper tests pass |
| Separately compiled private observer | Compiles against the exact corrected candidate and exported NeoForge compile classpath |
| Private observer tests, separate real Fabric-loader run | Four passed, zero skips/failures/errors |
| Private NeoForge content run | Passed custom height, owned unchanged/removal exchanges and distinct DH/native source content |
| Private NeoForge lifecycle run | Passed render control, movement, reset, refresh, receive toggle, Nether/End, return and source switch |
| Standalone captured-content replay | 57,344 independently seeded block checks passed; two changed tiles, one unchanged reply, zero retained owned bytes |
| Old artifact admission | P3 artifact rejected before staging/child launch |
| Python syntax / whitespace | Staging scripts compile; `git diff --check` clean |

The current normal unit total is **3239 passed**, with four skips. The four private observer
cases are additional tests and are not included in that total. First-attempt normal
XML is preserved under `test-results/` before the separate observer task overwrites
scratch results. That original full build passed 992 Common tests; the later whole
Common suite passed 993, with XML under `test-results-content-corpus/common/` and
log `common-content-corpus-test.log`. Production inputs and the built candidate did
not change between those checks. The two demonstrated pre-fix reds are retained as
`before-policy.xml`, `before-session.xml` and their logs; they are expected
regression failures, not failures of the corrected candidate.

The NeoForge development artifact is:

```text
lod-server-support-neoforge-dh-dev-mc1.21.1.jar
embedded version: 0.16.0-dh-p4.dev20261005
SHA256: d6317a65a62a5a79d3e0ca3b4a10b9986866b02e6c90ed154a312ca03bc547fb
```

The fixture's old hard-coded merged-JAR compile path is no longer reliable with
current MDG artifact layout. `classpath.gradle` exports the actual named NeoForge
compile classpath; `build.py` records those inputs and the candidate/fixture hashes.
The initial ABI compile failed on missing named methods; its inspected diagnosis
is recorded in `fixture-compile-first-attempt-record.json` (a reconstructed record,
not a raw first-attempt log). The corrected compile log is `fixture-build.log`. Private fixture sources stay
outside all shipping jars. `fixture-tests.gradle` includes them only in the test
source set of the separate observer check.

## Corrected private live checks and captured corpus

Both runs used private Xvfb/llvmpipe clients and fresh owned worlds, serially under
the shared harness lock. Neither launched a foreground Windows client. The content
client and native test server used the exact candidate hash above; the lifecycle
server carried official DH only. The client had Voxy and LSS without a DH client mod.

The content evidence is under `content-runs/attempt-jo288t5f/`. Its skylit custom
dimension spans Y=-128 through 383. The target tile covers X=-192 through -129 and
Z=0 through 63, outside vanilla authority. It passed 300 storage comparisons in
each of the distinct DH, removal and native switch-back phases. All 16 warm native
cache stamps were invalidated before DH acquisition; all 16 DH columns matched the
lime/cyan seed. A successful owned unchanged exchange preceded the edit. The same
DH session then accepted request 58 at the new receipt timestamp and stored air at
Y=350/351 and purple at Y=-118/-117, with gold sentinels proving the upper sections
were still present. Native v20 reconnect delivered yellow through 16 new columns,
with no further DH asks and zero retired owned budget.

The lifecycle evidence is under `lifecycle-runs/attempt-4qcblj6t/`. Actual Voxy
geometry and the software render control passed. Sodium hot enable/disable,
movement, reset, receive toggling, Nether, End, return and source switching all
passed. Hot disable observed 45 seconds without another conditional request.
Retired sessions released their owned budget, and receipt policy survived reception
and world transitions. The client log also contains a nonfatal Voxy/ConnectorExtras
config-screen factory warning; it did not fail these checks. Both private servers
saved their dimensions; all owned server/client/supervisor/display processes are
confirmed stopped in `verification.json`.

Eight target frames are frozen in the
[official 3.3.3 content corpus](/home/vox/projects/voxel-server-support/.worktrees/dh-client-p1/common/src/test/resources/dh-corpus/dh-official-3.3.3-neoforge-content/README.md).
Requests have sanitized dimension keys; upstream response/fragment bytes are
verbatim. Provenance binds upstream and candidate artifacts, fixture, run, seed/edit
commands and frame hashes. A standalone replay using the exact candidate and its
extracted nested Zstd library passed 57,344 known-block checks across seven heights
and all 4096 columns in each changed tile. The normalized hashes are regression
pins, not independent oracles. Initial standalone classpath setup failures remain
preserved separately from the successful replay. The permanent Common test
`customHeightCaptureReplaysSeededPlanesAndOwnedConditionalRemoval` now exercises
this corpus, requiring complete columns, owned unchanged/removal responses,
increasing timestamps and zero owned bytes on completion.

## Disk capacity and manual setup

`host-storage.json`, `content-admission.log` and
`content-runs/preparation-failure.log` preserve the initial **49.78 GiB** C: snapshot
and pre-staging rejection. After capacity recovered, actual Windows VHD-host
admission found **89.04 GiB** free against **58.31 GiB** required for the complete
staged run. The 50 GiB runtime floor was retained. No unrelated personal files were
removed. After both tests and installation, C: still has about **88 GiB** free and
the Linux filesystem about **360 GiB**.

`prism-installation.json` records installation and readback of the corrected
`d6317a65…` artifact in **LSS dev — 1.21.1 NeoForge - Copy For Far Testing**.
The previous P3 jar and configuration were backed up under
`minecraft/lss-dh-backups/20261005T072842.135936Z`. Preferences and legacy settings
were preserved; the YAML SHA remains
`70eabf5317a83ebfe6435dbbcc40b4da733695edb06153ded670f06d184b0bb6`.

The manual server resumed the existing **dh-neoforge-playable** world on
**[::1]:25585**, running NeoForge 21.1.248 / Minecraft 1.21.1 and official DH 3.3.3
without an LSS server mod. `test-setup-completed.json` binds the exact server owner,
server identity, selected client artifact and storage records. Its Minecraft status
handshake and Windows IPv6 TCP connection both passed. The existing owner-aware
`build/dh-neoforge/2026-10-01/stop-server.py` now records the current JVM; the server
is left running for manual testing.

## Remaining validation

The broader Support/official × Fabric/NeoForge matrix, official-15, real DH-client
load comparison, asynchronous vanilla overwrite and CPU/frame-time measurements,
public Voxy completion acknowledgement, other Minecraft lines and outreach remain
plan gates. The review's scan-cost and refusal-window concerns have no new measured
regression or demonstrated recurrence here. The P3 command-feedback observation
and additional reset-coordinator coverage suggestion are review limits rather than
P4 verified defects; the production reset-sweep failure test still passes.
