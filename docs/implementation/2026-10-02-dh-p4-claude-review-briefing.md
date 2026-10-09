# Claude Code review briefing: DH P4 refresh fairness and content validation

Prepared 2026-10-02 at the user's request. This is the historical briefing for
[the preserved review](2026-10-02-dh-p4-claude-review.md). Current source differs
from its frozen manifest; see the [2026-10-05 disposition](2026-10-05-dh-p4-review-disposition.md). Independently review the P4 increment
following the corrected P3 implementation: the refresh starvation fix, its
regression, and the new custom-height/removal/native-source test scenario.
Review the code and the strength of its evidence. Write findings; do not implement
fixes or install/publish anything during this review.

## Checkout, scope and report

```bash
cd /home/vox/projects/voxel-server-support/.worktrees/dh-client-p1
git status --short
git diff --check
git ls-files --others --exclude-standard
```

Branch: `feat/dh-codec-p1-mc1.21.1`. Published baseline:
`6677fe93dfc35b8ca1e158972d4cf58eb9703bd2` (0.16.0, MC1.21.1).
This directory is a separate clone despite its `.worktrees` name. Its broad Git
diff contains the entire uncommitted DH implementation. The outer checkout has
unrelated user changes; review this clone's code.

For this increment, use the [scoped P4 diff](evidence/2026-10-02-dh-p4-review.diff)
and [file/hash manifest](evidence/2026-10-02-dh-p4-review-manifest.json). They compare
current files with the previous tested source snapshot at
`/tmp/lss-dh-p3-fixes.7gigtl56`. They are review aids, not a patch to apply or a
replacement for checking the current files. The new `ContentProbe.java` is
untracked and would be missing from an ordinary Git diff.

Exactly **two production inputs** changed since that snapshot:

| File | Change |
| --- | --- |
| `common/src/main/java/dev/vox/lss/common/dh/DhSessionPolicy.java:27` | Expose a receipt's monotonic due time for selection |
| `xplat/src/main/java/dev/vox/lss/networking/client/DhClientSession.java:316` | Select the oldest due eligible refresh, retain ring order for ties, extract the existing request/send body |

The remaining increment is the new session regression, `ContentProbe.java`,
dispatch hooks in `Probe.java`, and `exercise.py`/`prepare.py`/`run.py`/README
changes under `test-fixtures/dh-live`. Existing transport/converter/readback mixins
provide context; they are unchanged in this increment.

Read `CLAUDE.md`, the DH plan, and these records first:

- [P4 finding, correction and pending verification](2026-10-01-dh-p4-content-validation.md).
- [P3 review corrections](2026-10-01-dh-p3-review-disposition.md).
- [Preserved P3 Claude review](2026-10-01-dh-p3-claude-review.md).
- `docs/planning/2026-09-29-dh-client-compatibility-plan.md`, especially §5.1,
  §5.3–5.4, §9 P2–P4 and invariants I1–I4.

Write the new report to:
`docs/implementation/2026-10-02-dh-p4-claude-review.md` in this clone.
Preserve earlier reports. Put verified actionable findings first, with severity,
file/line, trigger, consequence, evidence and a proposed correction/regression.
Separate demonstrated bugs, suspected risks and incomplete qualification gates.
State what you independently ran versus evidence you only inspected. If no
verified defects are found, say so and state the review's practical limits.

## Problem and intended correction

Previously, refresh selection restarted in fixed distance order. With a
30-second refresh interval and a one-request-per-ten-seconds ceiling, nearby
receipts could become due again before farther receipts got a turn. A stationary
client could keep polling a small near set indefinitely while a removed distant
slab remained stale.

The new priority-2 scan keeps the eligible candidate with the earliest due time,
then submits that request through the extracted helper. Equal deadlines retain
the existing ring order. Successful renewal moves a tile behind older due work.
Initial acquisition remains first; the previous three-incomplete-retries fairness
rule and server/authority limits are intended to stay intact. Thirty seconds is
a **minimum per-tile interval**, not a promise that a large frontier finishes a
refresh sweep in thirty seconds.

## Review priorities

### Scheduler correctness and regressions

Trace `requestNext`, `request`, `dueNanos`, `acquisitionPriority`, `due`,
`refreshTimestamp`, `unchanged`, `accepted`, `cooldown` and `configureRefresh`.

- Does oldest-deadline selection guarantee progress for a stable eligible set
  when the minimum interval is shorter than the sweep? Check ties, movement,
  overdue successful receipts, retry receipts and held/exhausted work.
- Are all prior filters applied before selecting a candidate: effective range,
  border, vanilla authority, in-flight position, synchronization/generation
  availability, rate limits and outstanding-request limits? Check Support-15,
  official-15 and official-16 semantics separately where they differ.
- Does the request helper preserve request ID exhaustion, positive conditional
  timestamps, generation/null-timestamp selection, pending ownership, worker
  rejection, epoch guards, official cancellation and rate/fairness bookkeeping?
- Can moving the ten-second gate outside the priority-2 loop change missing-work
  reconciliation or retirement behavior? Check hot interval changes and Off.
- Assess the cost of completing the priority-2 scan instead of returning at the
  first candidate. Check existing range/receipt bounds and client-thread work;
  distinguish a measured regression from an unmeasured performance concern.

Read the pinned tests before recommending behavioral changes. P3 parked
unreachable absence, transient main-thread timeout handling, reset failure and
connection authority changes remain context; verify any claimed recurrence.

### Regression validity

The new test is
`DhClientSessionTest.everyDueTileRefreshesEvenWhenNearTilesBecomeDueAgainBeforeTheSweepFinishes`
at `fabric/src/test/java/dev/vox/lss/networking/client/DhClientSessionTest.java:223`.
It seeds 25 complete receipts, drives the actual scanner/worker/sender in
ten-second steps, and requires all 25 positions before any repeat.

It deliberately cancels each test-owned assembly/pending request and renews the
receipt directly through `policy.unchanged` at the same controlled monotonic
time. It does **not** drive the inbound reply handler for this regression. Assess
that coverage boundary, the real/synthetic clock separation and the existing
owned-unchanged-response tests. Verify that the pre-fix failure is the repeated
near-tile assertion and that the corrected implementation actually services all
25 tiles. Do not turn missing live qualification into a passing gate.

### Content fixture and possible false positives

Read `ContentProbe.java` end to end and its dispatch hooks. The private server
runs official DH **and the same LSS candidate**. The client has Voxy and LSS,
with no DH client. The scenario goes native → DH → native over physical reconnects.

- The fresh skylit dimension has min Y=-128 and height 512. A negative-coordinate
  64×64 tile contains red concrete at Y=350..351 and blue concrete at
  Y=-118..-117. The 10×10 samples on each plane include all four tile corners.
  Review seeding readiness, full tile coverage and actual vanilla exclusion.
- The initial native phase delivers the 16 target columns and persists real
  native server timestamps. DH must clear the warm cache bucket, suppress native
  terrain negotiation/delivery and obtain a valid target receipt.
- Known blocks already exist in Voxy from the previous source. Determine what
  distinguishes fresh DH/native delivery from retained storage, and whether the
  receipt, delivery-count and authority assertions are sufficient for each claim.
- Removal must change red concrete to actual air in the same DH epoch while
  retaining blue concrete. It requires a target conditional request, a nonempty
  changed reply and a newer server receipt. Check that missing Voxy sections,
  stale callbacks, another dimension or unsolicited updates cannot make it pass.
- The prepared fixture checks the received server config has disabled generation,
  disabled unsolicited updates and generation distance zero. Those explicit
  assertions were added **after** the first failed run; do not attribute them to
  that earlier execution. Its saved server TOML does record the intended values.
- Final native reconnection must negotiate real LSS, deliver the target columns,
  stop DH requests and release the retired DH budget. This stage has not run
  successfully yet. Neither queue depth zero nor a positive receipt acknowledges
  completed/durable Voxy ingestion; internal storage access stays test-only.
- Review callback/thread ownership, main-thread file reads, deadlines, failure
  reporting and whether the fixture itself could distort the observation.
  Audit known-tile capture selection, first-fragment assumptions, request/buffer
  tracking across epochs, the 64-frame cap and exclusion of opaque control frames.
- Check fresh-directory staging, same-candidate client/server hashes, storage
  admission, process identities, cleanup and protection of personal worlds/Prism
  instances. No Windows foreground automation is part of the rig.

## Exact evidence and what it establishes

All runtime/build evidence below is in the **outer** checkout:

```text
/home/vox/projects/voxel-server-support/build/dh-neoforge/2026-10-01/p4-content/
```

Start with `verification.json`. It deliberately records `candidate_verified=false`,
`release_ready=false` and `new_normal_artifact=null`.

Current production manifest: 375 inputs, SHA256
`9dca25096e619af460098f7f861e895f0ed10d5b367ff3157db1668f32c266b2`.
Test/fixture manifest: 15 inputs, SHA256
`2617c54053c46a98510c735d77db0125ccf912ee90f8aa27a38f1d1cccacd20c`.
Both match the current source as checked when this brief was prepared. Recheck
before relying on them if files change.

| Evidence | Scope |
| --- | --- |
| `offline-before-test.log` | Old production source plus the new regression; fails on a repeated `(detail=6,x=0,z=0)` tile |
| `offline-after-test.log` | Corrected source; one passing starvation regression |
| `offline-session-suite.log` | **19/19** passing actual session test cases, zero skips, in the supplementary standalone runner |
| `offline-check.py`, `OfflineRunner.java`, `offline-suite.py`, classpath/input manifests and compile args/logs | Reproduction and provenance of the supplemental checks |
| `fairness-before-fix.log` | Normal Gradle attempt fails before compiling because local socket/interface access is denied; it is not a red regression test |
| `private-tests/attempt-3qd_07b9/` | First content scenario against the **old P3 jar**; partial checks pass, whole scenario fails its removal deadline |
| `prepared-fixture-build.log`, `prepared-fixture/lss-dh-live-fixture-test-only.jar` | Updated fixture compiles against the prior production ABI; it has not completed a packaged content rerun |

The standalone runner overlays freshly compiled changed classes/tests onto the
previous build's compiled dependencies. The final successful execution supplies test-owned LSS game/config paths and
omits Fabric bootstrap/class transformations. Earlier bootstrap attempts used the
checkout as cwd and leaked Sodium config/processedMods/logs; see the P4 review and
2026-10-05 disposition. The full suite uses an explicit
Byte Buddy Java agent for the full suite. This is useful supplemental behavior
evidence, **not** the normal Gradle/platform/native-regression gauntlet. Earlier
loader/agent setup failures are preserved under separately named logs; inspect
actual started/successful/failed/container counts rather than an exit code alone.

The first live run used this previously tested normal P3 NeoForge jar:

```text
/home/vox/projects/voxel-server-support/build/dh-neoforge/2026-10-01/p3-review-fixes/lod-server-support-neoforge-dh-dev-mc1.21.1.jar
SHA256 83dc61c86a4e0601c699184beec8b218ae3b636875b58b396306825f22e8e040
```

It observed native protocol v20 and 16 target columns, 200 known-block comparisons
at the custom heights, and an empty native bucket after DH activation. Phase 5
then timed out after 240 seconds without a conditional request for the distant
target; switch-back was not reached. Read `dh-custom-height.json`, `dh-result.json`
and `dh-content-status.txt` under the run's
`instances/dh-neoforge/minecraft/`, plus server logs/runtime/owner evidence.
All recorded private owners stopped and the server saved all dimensions.

The initial generic 64-frame capture lacks the complete custom-tile exchange.
New targeted capture code is prepared, but its output is not yet a frozen corpus.
Do not describe a decoder's normalized hash as an independent oracle.

## Constraints and remaining qualification

Targets: **MC1.21.1, Java 21, NeoForge 21.1.248**, community Voxy 0.2.15-beta,
Sodium 0.8.12-beta.1 and the pinned Connector/FFAPI/Xaero client stack. The official
server jar is DH **3.3.3**; the plan's 3.3.4 has no MC1.21.1 artifact. The known
content server additionally installs LSS; earlier lifecycle/no-service runs did not.

As recorded in the P4 validation notes, the restricted execution sandbox prevents
local sockets and Windows writes. The manual server is stopped, its existing world preserved,
and the Windows Prism target still contains the tested P3 jar. No newer verified
playable jar exists. Review does not require changing that setup.

Static review and scoped scratch reproductions are allowed. Use Java 21 and the
shared harness lock for heavy work; run builds/server rigs sequentially. If access
permits normal validation, use a fresh source copy outside the parent checkout
and preserve failure logs/XML. The intended full command is:

```bash
bash tools/verify/run-gradle.sh --no-parallel \
  :common:test :fabric:build :paper:test :paper:shadowJar \
  :neoforge:build :neoforge:runGameTestServer vssJars
```

There is no Tier 3 client gametest task or Folia on this line. Do not weaken
checks or reclassify the standalone runner to make qualification appear complete.
Do not commit, merge, contact upstream, publish diagnostics, update Prism, restart
or replace a normal server, or modify production files during this review.

The normal build/packaging gates, corrected-candidate content/lifecycle live runs,
removal and successful native switch-back, and custom-height/removal capture corpus
remain pending. Broader Support/official × Fabric/NeoForge coverage, official-15,
the real-DH-client load comparison, asynchronous vanilla overwrite measurement and
the stricter P2 Voxy completion gate remain open. Record these as qualification
limits unless you demonstrate a new defect.
