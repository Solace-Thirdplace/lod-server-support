# Claude review brief — changes since the full DH feature review

Date: 2026-10-08. Review all changes since
[the October 7 full-feature Claude review](2026-10-07-dh-full-feature-claude-review.md),
through `0.16.0-dh-followups.dev20261008.1`. Review source, regressions and evidence
together. Write findings to `docs/implementation/2026-10-08-dh-post-review-claude-review.md`.
Do not implement fixes, install mods, stop/start servers, alter the index, merge,
publish or contact upstream. Work remains local and outreach is pending.

Normal validation, ten packaged cases, Prism installation and manual restoration
are complete. The normal manual server now owns the shared harness; review source
and evidence without interrupting it. Coordinate any new exclusive tests first.
This is a review of the
post-review increment, with enough earlier context to judge the integrated feature.

## Checkout and exact scope

```sh
cd /home/vox/projects/voxel-server-support/.worktrees/dh-client-p1
git status --short
git rev-parse HEAD
git diff --check
git ls-files --others --exclude-standard
```

Branch: `feat/dh-codec-p1-mc1.21.1`. HEAD remains the pre-feature
`6677fe93dfc35b8ca1e158972d4cf58eb9703bd2`. This checkout is a separate clone;
the feature contains staged, unstaged and untracked work. Preserve it all. A diff
against HEAD includes the whole DH feature and is not the requested increment;
an unstaged diff alone misses important new classes and tests.

The last Claude review examined r5:
`/tmp/lss-dh-p4-20261007-r5`, version `0.16.0-dh-p4.dev20261007.5`.
Its original source/test bindings live under the parent repository's
`build/dh-neoforge/2026-10-07/p4-coverage-performance/r5-final/`.

Current candidate build: `/tmp/lss-dh-followups-20261008-r1`.
Current evidence root, **outside this separate clone**:

```text
/home/vox/projects/voxel-server-support/build/dh-neoforge/2026-10-08/official-followups/
```

Read `changes-since-r5.json` and `changes-since-r5.patch` there: **100 actual
production/build/test/tooling input differences**, with a 6,015-line text patch.
Every originally bound r5 input is checked against its original hash. Existing
r5 snapshot inputs missing that old binding are identified separately; their
retained bytes provide comparison context, not a retroactive build receipt.
The expanded binding-only inputs are not presented as new code changes.
Binary changes are represented by hashes, not fabricated text diffs.

`source-manifest.json`, `test-source-manifest.json`, `verification.json`, unit XML
and fixture receipts bind the current candidate. Inspect untracked files directly,
especially `DhBandwidthRamp`, `DhAcquisition`, `DhClientTrace`, their regressions
and new documentation. The patch covers compiled/test/tooling inputs; the dated
documents below carry subsequent decisions and evidence qualifications.

Final production artifact SHA256:

| Loader | SHA256 |
| --- | --- |
| NeoForge | `f053642d09296b57f95f61521223088c5e16538c621a6bbb9deb9dbfc03c788b` |
| Fabric | `cdabf6677c4e9fa34a62c96524a126337240cd1392f4375584aff85c307810f8` |

## Read these accounts first

1. [Full-feature review disposition](2026-10-07-dh-full-feature-review-disposition.md)
   and [recovered normal/live validation](2026-10-07-dh-full-feature-validation.md).
2. [Accepted Voxy limitation](2026-10-07-dh-accepted-limitations.md).
3. [Trace recording and command cleanup](2026-10-07-dh-trace.md).
4. [Native-client parity review](2026-10-07-dh-native-parity-review.md),
   [parity fixes/validation](2026-10-07-dh-parity-disposition.md), and
   [official-client behavior review](2026-10-07-dh-official-behavior-review.md).
5. [The follow-up plan](../planning/2026-10-08-dh-official-behavior-improvements-plan.md)
   and [its implementation/evidence](2026-10-08-dh-official-behavior-followups.md).
6. The [original compatibility plan](../planning/2026-09-29-dh-client-compatibility-plan.md),
   using its dated amendments to resolve older proposal text.

Historical documents retain obsolete blocked-environment, manual-profile,
periodic-refresh and I1-blocker descriptions. Current dated decisions and exact
candidate receipts take precedence; do not treat those historical states as the
current implementation or invent passing proof for an accepted limitation.

## Changes since your last review

### Full-feature review corrections, r6

The disposition maps each original finding to its correction. Review the
integrated behavior, especially these groups:

* **S-1–S-6:** transfer-aware reconnect suppression/cooldowns, listener-owned
  family evidence, reconfiguration rediscovery, strict ambiguous-v15 handling,
  minimum retry timing and truthful availability. Vanilla ownership is observed
  while fallback is allowed, even if native service currently wins.
* **P-1–P-8:** shorter official sync scheduling floor, separate sync/generation
  clocks, cancellation/registration guards, non-exhausting transient retries,
  preservation of push ordering fences through recovery, out-of-interest push
  invalidation, loss accounting, and contained assembly/decode resource pressure.
* **C-1–C-5:** global source-cache LRU/protection, optional-cache IO containment,
  no-follow cross-process quota ownership/index revisions, and conversion identity
  derived from a frozen settings/registry snapshot. Source validity never becomes
  a persistent successful-Voxy-write receipt.
* **V-1–V-3:** solid-face light in the AIR voxel above the floor, conversion
  version 3, and bounded vanilla-fringe pumping that waits for light settlement
  and retries at render end.
* **U-1–U-5:** native/default YAML downgrade behavior, both legacy Sodium twins'
  narrow geometry/pagination/footer handling, sanitized status, layout-warning
  ownership, truthful tooltips and removal of obsolete binders/stubs.
* **K-1 / E-1–E-3:** recursive packaging checks and XZ notices, broader gate/input
  bindings, honest physical-rejoin versus same-connection evidence, corrected
  performance qualifications, corpus assertions and isolated test-only overlays.

The r6 validation document contains the later executed functional cells that
supersede the disposition's initial environment-blocked/unrun descriptions.
Original measurement files remain historical; no new FPS or server-load benefit
is claimed from the later scheduler changes.

### Trace and command fixes, r7

DH acquisition now participates in opt-in trace recording: requests, matched
replies, timeout/refusal/cancellation, sampled state and distinct pressure causes.
Trace startup flushes a header even when no terrain event arrives. The empty trace
was a missing DH integration, not proof that the adapter was idle.

The Sodium “Use DH” tooltip was shortened. Redundant `/lss dhstatus` was removed;
`/lss diag` remains the diagnostic command. Review both loader command registration
and locale resources, including stop/restart sampling and privacy boundaries.

### Native parity and official scheduling adaptations, r9

All seven native-parity findings are addressed: reset requires an actual refill
service; receive OFF/native takeover withdraws server subscriptions once; the
manual cap is aggregate; range refusals park separately from permanent failures;
conflict/unavailable reasons are typed; DH gets generic vanilla network context
in its traces; and shared options describe their actual source support.

The existing Adaptive Rate + Slow Start options now apply to DH. Official profiles
advertise a one-way 50–500 decimal KB/s ramp. State belongs to the physical
connection, so dimension/reception changes keep earned progress. Normal requests
use a 60-second birth lifetime; official startup requests/buffers retain bounded
120-second lifetimes. Idle/count/byte limits stay intact.

Support 0.14 explicitly ignores its bandwidth field. It uses local request spacing
for five seconds: 4× minimum for 2.5 seconds, 2× for 2.5 seconds, then normal.
This does not throttle individual response bursts. Its actual plugin evidence is
retained with the review; do not apply the official bandwidth claim to Support.

Admission respects per-service rate/concurrency limits, one generation/two total
requests and one shared manual 16-column tile charge, including cached checks.
Temporary refusal produces service-specific backoff. Generation outside interest
is pruned after two seconds with 12 chunks of spatial hysteresis; sync ownership
and captured replies are protected. Unrelated preference changes preserve load
debounce. Xaero remains native-only with source-aware descriptions.

This is optional startup pacing, not the entire official congestion controller.
Sparse/idle samples do not lower the budget to 1 KB/s. Hot OFF opens immediately;
hot ON applies on the next physical connection. Native precedence and automatic
protocol selection remain authoritative.

### Follow-up plan implementation, current candidate

**M1:** allowlisted primary acquisition reason, bounded secondary gate mask and
remaining local gate wait reach `/lss diag`, local export and sampled `dh_status`.
Unknown readiness/slots use -1; waits are not delivery estimates. Actual scans and
shared admission checks provide observations, with cheap service rechecks between
scans. There are no diagnostic-only terrain scans, new settings or per-tick events.
Review known/unknown timing, reason priority, cached checks, privacy, state clearing
and whether observation changes admission behavior.

**M2:** a deterministic red test reproduced unrelated captured traffic retaining
silent requests and old buffers. Expiry now uses the oldest outstanding arrival
as a cutoff, on the existing ownership locks/worker. Actual capture timestamps
are assigned under that lock before loader queuing. Review races with publication,
processing, retirement and cancellation; timely queued frames must survive delayed
processing, while post-deadline traffic must not require global quiet for cleanup.
Explicit capture timestamps are a test seam. Sync timeout cannot manufacture free
upstream permits; the bounded unanswered/reconnect policy stays intact. Generation
pruning retains its stricter drain guard because fragments are anonymous.

**M3:** assembly retains the internally immutable `Split` array under its own lease,
removing one clone. Public construction/accessors remain defensive. Inspect every
package-private borrower for mutation/escape, reservation-before-retention, overlap
accounting and exact release on pressure, cancellation, failure and retirement.
Flattening/decompression/cache APIs and limits are unchanged.

## Validation and claim limits

The final normal Java 21 build passes **3,408 tests**, no failures/errors, four
optional skips; **78 Fabric + 8 NeoForge gametests** and all six artifact/release
checks pass. Counts are 1,040 common, 1,809 Fabric passes, 536 Paper, 23 NeoForge.
Focused checks are subsets, not additional passes. No Tier 3 client gametest or
Folia task exists on this support line.

The packaged matrix uses the actual current production jars and separately bound
observers. It checks official 3.3.3 on both loaders, legacy official 3.2.0-b,
Support, movement, dual-service native precedence, live/load/rejoin/reception
controls, no-service reset protection and real-DH conflict. A private pressure
overlay injects bounded synthetic split frames at the packaged capture boundary;
its source/class/config hashes and result are distinct from natural server traffic.
All ten case names have passing results. The pressure case retains the same
connection/session, clears incomplete buffers and resumes terrain. Four separate
Java observer tests and seven bounded-storage tests pass. Current root bindings use
observer revision 2; older per-case observer bindings/failures are preserved. The
shipping artifact is unchanged across that observer correction, so do not collapse
those distinct observer versions into an invented single fixture identity.

The installed Prism candidate and restored same-world/OP server at `[::1]:25585`
match their receipts. `completion.json`, `live-matrix.json`, `live-retirement.json`,
`prism-installation.json` and `manual-server-restoration.json` carry final results.

`allocation-comparison.json` records an offline probe of four retained actual
payloads: three alternating JVM pairs, 30 warmups/corpus, five samples of twelve
tiles per pair. Assembly allocation drops by 10,528–49,792 bytes/tile; normalized
hashes and accounted peaks match. CPU ratios are noise-dominated; no CPU speedup is claimed.
Capture/envelope/assembly/flatten/decode/cache phases are separate. This narrow
microbenchmark is not an end-to-end, GPU frame-time, Windows-cache, network or
server-generation performance result. Full composite decode and aggressive
congestion reductions remain deferred.

First failures are retained. The first timeout-test draft registered an owner
twice; the corrected red assertion is `m2-before-corrected.xml`. Early diagnostics
tests exposed incomplete Voxy fixture setup; scoped readiness fixtures now make
the isolated class pass. The fixture build initially lacked its compile-classpath
export; exporting it allowed normal separately compiled observers. Do not count
setup failures as a product reproduction or replace final evidence with older runs.
The first live-update run measured from before an asynchronous pause applied;
revision 2 anchors OFF assertions after application and retirement. Push-only edit
assertions remain intact, and the new run records zero requests/pushes during applied
OFF and zero target payloads/conversions on unchanged rejoin. Capture observation
now matches only the owning overload on both named and intermediary loaders.
Storage-rejected preparations created no new run. The first private pressure overlay
failed; its corrected version waits for capture room and keeps the service observer's
scripted frontier active while checking recovery. Both overlay recipes/results remain.

Process creation identities gate owned retirement and dependency-copy cleanup.
Raw logs/proofs, mods/worlds and original distributions are retained. The bounded
storage policy remains 50 GiB reserve and 2 GiB owned growth; generic rig policy
was not weakened. No Windows foreground automation or automatic uploads occur.

## Review priorities and output

Focus first on arrival-cutoff safety, array ownership/accounting, diagnostic
truthfulness, physical-connection ramp state, service/global rate limits, and the
interaction between recovery, cache freshness and vanilla authority. Then check
loader twins, command/menu behavior and whether evidence supports each claim.

The user accepted the existing queued-Voxy-write ordering/completion/persistence
boundary for now. It is a known risk, **not a release blocker or a fixed guarantee**.
Do not reopen it solely because the old r5 brief called it a blocker. New regressions
in the implemented guards remain valid findings. Real proxy-transfer qualification,
remaining original plan gates and upstream outreach are not certified by this increment.
The ordinary-play movement pause still has no confirmed cause.

For each finding, provide severity, exact file/line, concrete failure sequence,
supporting evidence/test, whether it is new or a regression, and a suggested narrow
fix. Separate verified issues from unproven concerns and accepted decisions.
Read existing behavior pins before proposing changes. Report no findings if that
is the result; do not invent issues to make the review longer.

## October 8 review clarification

The r1 manual server's ready receipt was a point-in-time observation. Its storage
watchdog stopped it at 15:30Z; read [the subsequent fixes and current status](2026-10-08-dh-post-review-fixes.md). Do not interpret an old ready flag as ongoing liveness.

The root verification timestamp records the original normal build, before observer
r2 was recomposed. Four passing cells retain observer r1: Support, official15,
moving DH and native precedence. Their production artifact was identical to r2's;
the observer revisions remain distinct in the case receipts. The allocation probe
used pre-final assembly source `fb3ab820…` with an identical `accept()` implementation
to shipped `fb028c6a…`; only the allocation savings support a performance claim.

A one-way official ramp can stay below 500 KB/s throughout a sparse or bursty
connection. In that case new requests retain the bounded 120-second birth lifetime;
the 10-second idle deadline and all count/byte caps still apply. Support uses its
separate local startup pacing.
