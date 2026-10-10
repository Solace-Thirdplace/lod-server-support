# Claude Code review: DH P4 refresh fairness and content validation

Date: 2026-10-05, reviewing the briefing `2026-10-02-dh-p4-claude-review-briefing.md`.

**Reviewers.** Four reviewers ran in parallel, plus the coordinator:
- Opus 1: correctness of the scheduler fix.
- Opus 2: validity of the regression test and of the offline evidence.
- Opus 3: the `ContentProbe` fixture and the first live run.
- Fable: an adversarial cross-check, a P3 disposition audit, and DH 3.3.3 server semantics.
- Coordinator: manifest checks and the full normal Gradle gauntlet.

**Constraints.** Nothing in the checkout was edited, committed, installed or published.
No upstream contact, no Prism change, and no normal server was touched. Earlier reports
are preserved.

## Bottom line

- **The P4 production change is correct.**
  - Earliest-deadline selection with ring order breaking ties guarantees that every due
    tile is refreshed before any tile repeats, for a stable eligible set.
  - Every prior filter is still applied before selection.
  - The extracted request helper preserves its bookkeeping.
  - The full normal Gradle gauntlet passes on the current source (first run of it for P4).
- **One production defect is demonstrated, in unchanged code that the fix depends on**
  (P4-1). Restarting a session within a connection resets every refresh deadline to
  the same value. Near-first ring order then wins again, which brings back far-tile
  staleness.
- **The content scenario, as staged, cannot qualify the fix** (P4-2), and three of its
  content claims are not attributable to the source being tested (P4-3). The live
  timeout's attribution to "refresh starvation" is not demonstrated (P4-4).
- **Not ready to replace the installed P3 jar on scheduler grounds alone**, until P4-1
  is fixed and the content rerun has been re-staged. `verification.json`'s
  `candidate_verified=false` and `release_ready=false` remain correct.

## What was independently run vs inspected

**Coordinator, run:**
- `git diff --check`: clean.
- Re-hashed `source-manifest.json` (375 inputs) and `test-source-manifest.json` (15
  inputs) against the current tree: 0 missing, 0 changed. Both file SHA-256s match the
  briefing.
- Ran the briefing's **full normal command** from a fresh rsync copy outside the
  parent checkout (`/tmp/lss-dh-p4-claude-build.3iqSY2`), Java 21, on 2026-10-05:
  `run-gradle.sh --no-parallel :common:test :fabric:build :paper:test :paper:shadowJar :neoforge:build :neoforge:runGameTestServer vssJars`.
  Result: **BUILD SUCCESSFUL**.

  | Suite | Result |
  | --- | --- |
  | common | 991/0 |
  | Fabric unit | 1686, 0 failed, 4 skipped (the opt-in experiment tools) |
  | Paper | 536/0 |
  | NeoForge unit | 23/0 |
  | Fabric server gametests | "All 78 required tests passed" |
  | NeoForge gametests | "All 8 required tests passed" |
  | `DhClientSessionTest` | **19/19** under real Gradle, including `everyDueTileRefreshesEvenWhenNearTilesBecomeDueAgainBeforeTheSweepFinishes` |

- `python3 scripts/release_check.py` on that copy:
  `OK: release artifacts clean`, lss and vss present for fabric, paper and neoforge.
  These are unversioned local names, not a `--version` pre-flight.
- That run's NeoForge jar has SHA-256 `d1194ea8…d74f`. It is a review build, not a
  candidate artifact.

**Reviewers, run (scratch only, nothing in the checkout):**
- Opus 1: a simulation that replicates the priority-2 selection loop against the real
  compiled `DhSessionPolicy`, comparing old and new selection at 25 and 4225 tiles,
  with and without failures and session restarts, plus a timing comparison.
- Opus 2: `javap` and `cmp` of the before/after overlay classes; a three-way manifest
  diff; a scratch model replaying the regression with three mutants.
- Opus 3: a Python decode of the first run's 64 captured frames, and log and mtime
  timelines.
- Fable: `javap` of the DH 3.3.3 jar (`864e70de…`) and of the first-run fixture
  classes; an immutable SQLite query of the retained server DH database.

**Not run:**
- No live rig, no content rerun.
- The offline before/after runner was not re-run: its P3 snapshot classpath
  (`/tmp/lss-dh-p3-fixes.7gigtl56`) no longer exists. Only its logs were inspected.

The normal Gradle result above supersedes the standalone runner as behaviour evidence
for the session suite.

## Verified defects

### P4-1 MEDIUM (production): a session restart re-equalises refresh deadlines and recreates starvation (Opus 1, demonstrated by simulation)

**Where.**
- `xplat/.../DhClientSession.java:184` initialises `refreshNanos=-1`, so every new
  `Session` calls `policy.configureRefresh(refreshNanos, now)` on its first tick
  (`:259-261`).
- `common/.../DhSessionPolicy.java:42-46` then sets every successful snapshot's
  deadline to `now+refresh`.
- The policy itself is retained for the whole connection (`DhConnectionState`).

**Trigger.** Any of these recreates the session and resets all deadlines:
- returning to a dimension (a new `ClientLevel`);
- turning receive or source off and on;
- Voxy becoming available again;
- a hot change to the refresh interval.

**Consequence.** All deadlines become equal, so the tie-break (ring order, nearest
first) decides again. If sessions restart more often than one full sweep (N tiles ×
10 s), far tiles are never refreshed, and every restart pushes overdue tiles out by
another full interval. This is the far-tile staleness that P4 targets.

**Evidence.** Replica simulation against the real policy:
- 25 tiles with a restart every 120 s: **13 of 25 never refreshed**.
- 4225 tiles with a restart every hour: **3865 never refreshed**.
- Without restarts, both cases refresh every tile.

The new regression sets `refreshNanos` directly, so it never exercises this path.

**Correction.**
- Keep the applied interval in the policy, and skip `configureRefresh` when it has not
  changed.
- For a real change, preserve age order by recording acceptance time in `Receipt` and
  setting `due = at + newRefresh`.
- Keep the pinned behaviour of `refreshReloadReleasesHeldSnapshotsAndDisablingItStopsExistingTimers`
  (held snapshots get `now+R`).

**Regression test.** Seed 25 receipts, run part of a sweep, then construct a new
`Session` on the same policy (or reconfigure with the same interval). Require every
tile to be refreshed before any repeat.

### P4-2 HIGH (qualification): the content scenario as staged is bounded by the untouched frontier, so a rerun cannot attribute pass or fail to the fix (Opus 3 + Fable, independently)

**Where.**
- `test-fixtures/dh-live/prepare.py:34` stages content at `lod.distance_chunks` 32.
- `ContentProbe.java:123` sets a 240 s per-phase deadline.
- `ContentProbe.java:149-160` starts the edit and enables refresh with no check that
  initial acquisition has finished.
- `DhClientSession.java:301-309` allows at most 1 request per 500 ms and 2 in flight.
- `:320` always serves priority 0 before priority 2.

**Evidence from the first run's own artifacts** (`private-tests/attempt-3qd_07b9`):

1. **The frontier.** Range 32 gives 256 in-range tiles, about 252 of them requestable.
   Each first ask costs at least 500 ms, and the decoded captures show about 1.05 tiles
   per second (16 columns × two `onMain` hops at the frame cap).
2. **Timing.** The target is the 25th tile in ring order: buffer 24, arriving at
   17:06:54.757. Removal started at 17:06:56. About 227 untouched tiles remained,
   roughly 140–215 s of a ~233 s window (the 240 s deadline reads short in the log
   because of WSL2 clock steps).
3. **After the fix.** With the corrected jar, the ~13 accepted tiles then refresh at
   the 10 s ceiling with **equal** deadlines (one `configureRefresh` at 17:06:56), in
   ring order. The target at ring 3 is served roughly 10th–13th, which lands at or
   after the deadline.

A rerun is a coin flip, and neither outcome would be attributable to the scheduler.

**Correction.**
- Stage content at distance 12–16 (the target's centre is at Chebyshev distance 10).
- Start the phase-5 clock only once initial acquisition is idle: no untouched in-range
  tiles, nothing pending, and the request count stable for at least 5 s.
- Size the deadline as 30 s + completed tiles × 10 s + a margin.
- Write per-request telemetry from `Probe.sent`: position, timestamp, wall time, and
  whether the reply was changed, unchanged or empty.

### P4-3 HIGH (claim validity): three content checks can pass on Voxy data retained from the previous source (Opus 3 + Fable)

**Where.** `ContentProbe.java:149` (phase-4 DH custom height), `:165` (blue retained in
phase 5) and `:173-175` (phase-7 native switch-back).

**Trigger.** Native LSS already wrote identical red and blue concrete at the same
positions. Voxy ingestion is asynchronous, and acceptance only means the block was
enqueued. `dh-custom-height.json` was written about 0.3 s after the target's DH
response frame.

**Consequence.**
- These are not attributable to the source under test: the "200 actual Voxy block
  comparisons" credited to DH, the blue slab retained at Y=-118, and the native
  switch-back storage reads.
- **Red turning to air in the same DH epoch is attributable**, because the converter
  emits every section and air differs from the retained red.

**Correction.** Change content at each source switch so the expected values differ from
what Voxy already holds:
- before the DH reconnect: red→lime, blue→cyan;
- in the removal edit, also cyan→purple, so the changed reply proves negative-Y
  decoding;
- before the native reconnect, Y=350→yellow.

Alternatively, assert the DH-converted target columns through `Probe.converted`. Until
then, label phase 4 "DH tile accepted (enqueued) + Voxy retains known blocks".

### P4-4 MEDIUM (overclaim): blaming the live timeout on refresh starvation is not demonstrated (Opus 2, Opus 3, Fable)

**Where.**
- `docs/implementation/2026-10-01-dh-p4-content-validation.md`: the "Live finding" and
  "Correction" sections.
- `test-fixtures/dh-live/README.md:68-70`: "fails … because refresh repeatedly selects
  nearby tiles".

**Evidence.** The run recorded no per-request data:
- `dh-content-status.txt` has only `requests=0; target refresh=0 changed=0`.
- All 64 captures fall between 17:06:31 and 17:06:59. The three 9-byte frames are empty
  replies to first-time ts=0 asks, not refreshes.
- `client.log` and the server log have no per-request lines.

Two mechanisms are consistent with the outcome: frontier preemption (P4-2, unchanged by
P4) and fixed-order starvation (fixed by P4).

**Correction.** Reword as: "no conditional request for the target arrived before the
deadline; analysis identified two contributing mechanisms; P4 addresses one; the
offline regression reproduces that one." The prepared `ContentProbe` counts only target
requests, so add the per-request log from P4-2.

### P4-5 MEDIUM: a missing or unreadable Voxy section reads as air (Opus 3)

**Where.** `ContentProbe.java:106-107`: a null section becomes `new long[32768]`, that
is id 0, which is air.

**Why.** In Voxy (`research/voxy`), `acquireIfExists` uses `nullOnEmpty`, and
`ActiveSectionTracker` maps both "not stored" and a load error to null. So red→air
cannot distinguish a real overwrite from a lost section.

**Mitigation already present.** The newer-receipt and changed-reply gates still require
a DH change. The storage claim is what remains unproven.

**Correction.** Place one block that stays put in each relevant 32³ section, for
example at Y=340. Require that the section is present and that block is intact,
alongside red→air.

### P4-6 MEDIUM: a fixture exception kills the production DH session, and the content phase then waits out its full deadline (Opus 3)

**Where.** `ContentProbe.received` (`:54-68`) runs at the head of `Session.receive` on
the decode worker. A throw from it leads to `Inbound.receive` and then `owner.fail(...)`.

**Triggers.**
- the 64-frame cap at `:67`;
- a write error;
- a null-level race between `:54` and its re-read.

The content tick has no `running()` fail-fast (the generic path has one at
`Probe.java:304`), so the failure surfaces as a 240 s timeout.

**Correction.** Contain these into `callbackFailure`, as `nativeColumn` does, and fail
the content tick immediately once the session goes inactive.

### P4-7 LOW-MEDIUM (test gaps): the tie-break and the pre-selection filters are unpinned (Opus 2, mutant analysis)

**Surviving mutants.**
- Changing `due<oldestDue` to `<=` at `DhClientSession.java:349` (ties go farthest
  first) passes the new regression and every other test. After every
  `configureRefresh`, the tie-break alone decides sweep order (see P4-1).
- Applying the in-flight, vanilla, border, range or `refreshAllowed` checks *after*
  selection would also survive. This is realistic: a pending refresh keeps its old
  deadline, so if its reply arrives more than 10 s late it is still the oldest tile.
- Deleting the 10-s refresh ceiling would survive too, because every test steps
  exactly 10 s.

**Regression tests to add.**
- The first refresh after seeding is `(6,0,0)`, and ring 1 follows in order.
- An older-deadline tile that is ineligible (all-vanilla, out of border or out of
  range) beside a newer eligible tile: the eligible one is chosen.
- One refresh still pending after more than 10 s: the next refresh picks a different
  tile.
- A `requestNext` at +5 s sends nothing.

### P4-8 LOW (hygiene + inaccurate scope wording): the standalone runner bootstrapped Fabric against the checkout root (Fable)

**Where.** `offline-suite.py:6` and `offline-check.py:69` run with `cwd=` set to the
checkout. `OfflineRunner.java` repoints `gameDir`/`configDir` only *after* the loader
bootstraps.

**What leaked.**
- `config/sodium-mixins.properties`, created at 17:44:45 by Sodium's preLaunch during
  the third loader-setup attempt. It is untracked inside a tracked `config/` directory,
  so a broad `git add` would pick it up.
- `.fabric/processedMods/`.
- `logs/latest.log` plus its rotations.

**Consequence.** "Supplies test-owned Fabric game/config directories" is true only for
LSS's own paths.

**Correction.** Delete the leaked files, launch from the runtime directory, and reword
the scope in the briefing, the content doc and `verification.json`.

### P4-9 LOW: assorted fixture and rig issues (Opus 3, Fable)

**Content-phase assertions**
- Phase 4 does not assert `synchronize && syncRate>0 && syncDistance>=range`, which
  refresh requires. A server without sync would become a silent timeout.
- Native negotiation being absent is checked only once, at one point in phase 3.
- Phase 7 records but does not assert native protocol 20.
- `targetChanged` and `newStamp` are not tied to the same request ID that was actually
  accepted; a reply discarded by the session still counts.

**Rig timing**
- The phase-0 teleport races `run.py`'s once-a-second op grant (it succeeded by about
  1 s). Pre-seed `ops.json` or retry the teleport.
- `run.py:39` treats seeding as ready on the first "Successfully filled" line. Wait for
  both fills, the forceload and "Saved the game".
- `knownBlocks` copies up to 8 Voxy sections per render tick until it passes, which can
  delay production main-thread handoffs. Throttle it to 1 Hz.
- Extending the phase-5 deadline runs into `Probe`'s global 600 s and `run.py`'s
  660 s deadlines.

**Candidate binding and captures**
- `exercise.py`'s default `--artifact` (`a1f79954…`) is neither the P3 jar nor a
  corrected one, and `--build` defaults to `/tmp`. Make `--artifact`, `--fixture` and
  `--build` required, check them against `verification.json`, and assert that the
  client and server jar hashes in `runtime.json` are equal.
- The generic `ReceiveMixin` capture still runs in content mode, so `received-0001`
  and `0002` contain SessionConfig and the LevelInit, including the opaque level key.
- The targeted capture omits Request frames and unchanged replies, so it is not a
  complete conditional exchange.

**Stale documentation**
- `2026-09-30-dh-codec-p1.md:76-77` says the P1 oracle remains under
  `/tmp/lss-dh-p1-oracle`. Neither that nor `/tmp/lss-dh-import-research` still exists.

## Suspected risks (not demonstrated)

- **Scan cost.** Completing the priority-2 scan costs about 0.29 ms per full
  4225-tile pass in the replica, against about 0.1 µs for the old early return. This
  is unmeasured on the real loop, which adds the `pending` stream, `inBorder`,
  `refreshAllowed` and boxed lookups. It is not a new worst case: when nothing is due,
  the priority-0 and priority-1 passes already scan the whole disc every 0.5 s.
- **Refusal window.** `pending.remove` runs on the worker thread before `cooldown` runs
  on the main thread. A tick in between could reselect the tile under its old deadline,
  which earliest-deadline selection now prefers. The 10 s ceiling makes this
  practically unreachable, and the old code had the same window.
- **Coverage of the regression.** It covers only the first sweep, with equal seeded
  deadlines. Later sweeps, failures mixed into a sweep, movement, Support-profile
  priority-2 (always `gen`) and the inbound reply path in this scenario are untested.
  Owned-unchanged-reply renewal itself is covered elsewhere.
- **Wall-clock timelines.** WSL2 backward clock steps make log-time timelines read
  about 3% short.

## DH 3.3.3 server semantics (settled; Fable)

With `generatorPlan=DISABLED`, `enableRealTimeUpdates=false` and
`maxGenerationRequestDistance=0`, DH 3.3.3 **does** re-LOD the edited tile on chunk
save.

**From the retained database:**
- The target FullData row has `LastModified 17:06:57.999` (created 17:05:59.423).
- Its parents were re-stamped at 17:06:58–59.
- All 16 ChunkHash rows were updated at 17:06:57.999–58.000, after the 17:06:56
  removal save.

**From the 3.3.3 bytecode:**
- The save path runs `MixinChunkMap` → `MixinChunkMapCommon_neoforge.onChunkSave`
  → `ServerApi.serverChunkSaveEvent` → `SharedApi.applyChunkUpdate`. No server-config
  gate sits on it; `enableRealTimeUpdates` gates only the unsolicited push.
- The sync handler answers non-empty if and only if `serverTs > clientTs`.
- The TOML value `0` loads without range validation, so the fixture's
  `generationDistance()==0` assertion holds.

**Conclusion.** "Same epoch + newer receipt + changed reply" is achievable. The removal
scenario is passable once P4-2 is addressed.

**Caveat.** The on-disk upstream source under `research/` is 2.4.6 (protocol 13). Every
statement above was confirmed against the 3.3.3 bytecode, not that source.

## P3 disposition audit (Fable; all corrections confirmed real, with no P4 regression)

| Finding | Correction in the current code | Pin |
| --- | --- | --- |
| P3-1 absent polling | `absent(…,false)` parks; `reconcileGeneration` unparks only reachable work; the three-incomplete-retries rule | `stationaryOfficialAbsence*`, `threeIncompleteRetries…`, policy parking tests. Moving the 10 s gate changes nothing, because priorities 0 and 1 still reconcile. |
| P3-2 vacuous refresh-off check | `dueWithin` premise, in-session Sodium Apply, 45 s observation | fixture only |
| P3-3 main-thread timeout | the tile is cancelled and cooled down; the session stays active | `stalledMainThreadAbortsOnlyItsTile…` (a real 5 s wait) |
| P3-4 reset failure | status and problem are set, `session=null`, `resetFailed` | `resetSweepFailureRetiresTheSession…`. Gaps: `dispatchReset` has no catch (chat shows the raw Brigadier error), and `ResetCoordinatorTest` has no throwing-drain case. |
| P3-5 NEED_VOXY mislabel | `BLOCKED` state, three locales | `aConnectionAuthorityBlock…` |
| P3-6 negative refresh UI | a value `<=0` maps to 0 | the catalog test's -5 case |
| P3-7 wording | provenance, tooltip and YAML wording; `dhstatus` routed to the shared diagnostics | inspected |

## Checked and found sound (condensed)

**Selection**
- Every served tile moves to `≥ now+R` or is held, so a due tile is reached after at
  most the number of tiles with an equal or earlier deadline. There is no monopoly.
  Cooldown, absent, generate, timeout and refusal paths all advance or hold the tile.
- Replica: the new selection completes the first sweep in exactly N picks with zero
  repeats, with and without 10% failures, at 25 and 4225 tiles. The old selection left
  22 of 25 tiles unserved.

**Filters and the request helper**
- Every filter still `continue`s before the selection block (`:330-348`), identical to
  the old code for Support-15, official without sync, and official with sync.
- `request()` (`:364-377`) preserves:
  - ID exhaustion;
  - the positive conditional timestamp or the null generation timestamp;
  - `pending` populated before `lastRequest`/`lastRefresh`;
  - `retryBurst`;
  - worker rejection leading to `fail`;
  - the epoch guards.
- Official cancel-on-retire is untouched.

**Moving the 10 s gate**
- Its only side effect was an idempotent `reconcileGeneration`, which priorities 0 and
  1 already run. Off and hot changes are unaffected apart from P4-1.

**Regression validity**
- Pre-fix, the test fails at the `visited.add` assertion on `(6,0,0)`,
  `DhClientSessionTest.java:239`, not on a setup error. It kills the revert-to-ring and
  pick-newest mutants.
- The near-tile-due-again condition really is exercised at 30 s, and the synthetic
  clock does not decide the verdict.
- The production reply path calls the same `policy.unchanged`, only with the real
  clock.
- The overlay runs' before/after test classes are byte-identical; only the production
  classes differ.
- The six loader-setup logs and one agent-setup log per side are setup failures and
  are not misreported.
- `fairness-before-fix.log` is a Gradle socket failure, not a failing test.

**Fixture**
- Dimension: min Y -128, height 512, skylit, with the negotiated height asserted.
- The forceload and fills match the tile exactly. The samples cover all four corners
  and all 16 chunk columns.
- Vanilla exclusion is asserted per column. Native suppression throws continuously in
  DH mode, and cache invalidation is real ("Cleared 1 bucket").
- The removal assertions (newer stamp + changed reply to a target conditional + air
  vs blue + same epoch) cannot be satisfied by unchanged replies or stale callbacks.
- The first-run fixture classes contain no SessionConfig assertions, which confirms the
  briefing's attribution note.
- Rig: a fresh `mkdtemp` directory, a private Xvfb with refuse-adoption, a storage
  preflight, recorded owner identities and Popen cleanup. Windows Prism is only read.

**Evidence binding**
- The nine `after_sha256` values in the P4 review manifest and the diff SHA all match.
- The prepared fixture jar `829fcf3b…` matches.
- The P3 and P4 source manifests differ in exactly the two stated production files.

## Remaining qualification gates

1. Fix P4-1 and add its regression. Add the P4-7 pins.
2. Re-stage the content scenario (P4-2, P4-3, P4-5, P4-6, P4-9). Then rerun on a
   corrected candidate:
   - removal in the same epoch;
   - a native switch-back that genuinely negotiates v20, delivers the target and
     stops DH;
   - the retired DH budget released.
3. Build a normal candidate artifact with a versioned pre-flight
   (`CI=true … -Pmod_version` + `release_check.py --version`), and run the lifecycle
   rerun on it. This review's Gradle run is review evidence only; it is not a
   candidate.
4. Freeze the custom-height/removal capture corpus, including Request frames and the
   unchanged replies.
5. Still open from the plan:
   - the Support/official × Fabric/NeoForge live matrix;
   - official-15;
   - the real-DH-client load comparison;
   - measurement of asynchronous vanilla overwrites;
   - the stricter P2 Voxy completion gate (enqueue is still not completion);
   - CPU and frame-time measurement;
   - other MC lines;
   - upstream outreach.
6. Correct the documentation: P4-4, P4-8 and the stale P1 oracle path.
