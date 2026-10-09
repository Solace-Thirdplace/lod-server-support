# Claude Code review: DH changes since the full-feature review (followups candidate)

**Date:** 2026-10-08. **Briefing:** `2026-10-08-dh-post-review-claude-briefing.md`.

**Candidate:** `0.16.0-dh-followups.dev20261008.1`, build copy
`/tmp/lss-dh-followups-20261008-r1`.

| Artifact | SHA-256 prefix |
| --- | --- |
| NeoForge jar | `f053642d…` |
| Fabric jar | `cdabf667…` |

**Scope:** the r6 corrections, the r7 trace and command changes, the r9 parity and
official-scheduling changes, and M1–M3. Everything was judged against the integrated
feature.

**Reviewers.** Ten reviewers ran in three batches to stay under rate limits:

| Batch | Reviewer | Area |
| --- | --- | --- |
| 1 | R1 | M2 expiry |
| 1 | R2 | M3 ownership |
| 1 | R3 | ramp, admission and rates |
| 1 | F1 | evidence audit |
| 2 | R4 | diagnostics, trace and commands |
| 2 | R5 | detection, reconnect and native parity |
| 2 | R6 | cache, recovery and authority |
| 3 | R7 | lighting and the fringe pump |
| 3 | R8 | downgrade, legacy Sodium and packaging |
| 3 | F2 | integrated adversarial pass |

**Constraints observed.** No edits, staging, installs, server operations, Windows
operations or publication. Earlier reviews are preserved.

## 1. Bottom line

This increment fixes most of what the October 7 review found, and the reviewers
re-ran its reproductions against the new code to confirm it:

| Fix | Result |
| --- | --- |
| Play-phase transfer reconnect loop (S-1) | 0 reconnects in 4 transfers |
| Backend-switch family (S-2) | fixed |
| Ambiguous v15 (S-3) | no terrain is ever accepted under an unconfirmed family |
| Retry timing (S-4) | fires at 5.0 s |
| Downgrade of defaults and native-only saves (U-1) | the published 0.16.0 parser accepts them |
| Global cache LRU and IO containment (C-1–C-5) | fixed |
| Push-fence retention (P-2), loss accounting (P-7) and resource pressure (P-8) | fixed |
| Solid-floor face light (V-1) | 0 mismatches on about 16k faces |
| Legacy Sodium footer and tabs (U-2/U-3) | fixed |
| Packaging gates (K-1) | 22 of 23 mutation tests caught; the one escape is inert |
| M3 `Split` array retention | sound: never mutated or escaped, released exactly once, fuzz returns budget to zero |
| M2 arrival-cutoff | core fix sound; the original bug was reproduced on the pre-fix code |

Native wire, payload, server and far-player classes stay byte-identical to the
published 0.16.0.

New or residual defects, all **LOW to MEDIUM-LOW**:
- three small reconnect and timeout traps (V1, V2, D1-R5);
- a fringe-pump regression at low frame rates (R7-D1);
- three diagnostics-truthfulness defects in M1 (R4);
- warm-rejoin pacing that never reaches the new floor (R3-1);
- strike and receipt bookkeeping slips (R3-2/3);
- two protection or ownership races (R1-D1, R2-1);
- cache robustness gaps (R6);
- evidence and receipt staleness, including the manual server, which is down (F1-1).

**None reopens the accepted Voxy ordering limitation**, and the two main-thread I1
checks are intact on every write path.

**Readiness.** The candidate is suitable for continued supervised NeoForge play. Fix
V1, V2, R1-D1, D1-R5, R6-D1 and R7-D1 before wider testing, and make a moving-trace
decision on the 256-section ingest gate (§4).

## 2. What was run vs inspected

**Coordinator.**
- `git diff --check`: clean.
- Confirmed the manual server is **not running**:
  - `stop-server.py --status-json` reports `owner_alive:false`;
  - no listener on `[::1]:25585`;
  - the harness lock is unheld.
- The detached supervisor log ends with "[DH test storage] P4 physical 50 GiB reserve
  reached" at 15:30Z, and host C: shows 50 GiB free.
- Because C: is at its reserve floor and the WSL disk lives on it, **no Gradle build
  or large copy was run**. Reviewers were held to tiny scratch use.
- Confirmed V1, R7-D1 and the 256-section gate in source.

**Reviewers (scratch only, against the candidate's compiled classes).**
- **Session-fixture harnesses:** R1 (P/E/N, plus R1Old against the compiled `before/`
  sources), R4, R5 (O4 S1–S4 re-runs plus config/cooldown variants, R5Evidence,
  R5Dim) and F2 (A–D).
- **Assembly and budget harness with fuzz:** R2.
- **Policy probe, plus ramp and warm-fill models:** R3.
- **Cache harnesses, scaled down:** R6 (cross-JVM, halt-with-lock, read-only clear).
- **Lighting and fringe:** R7's Air oracle with a v2/v3 toggle, and a fringe replica
  that models MC's light queue.
- **Packaging and settings (R8):**
  - downgrade probe: the candidate's writer against the published 0.16.0 reader;
  - 23-mutation `release_check --version` harness;
  - jar diffs against published 0.16.0;
  - tab-geometry replica.
- **Evidence (F1):** recounts of XML/logs/manifests and a fix-verification table.
- **javap:** MC 1.21.1 client classes, DH 3.3.3 `ColumnBox`, Voxy 0.2.15, and Sodium
  0.6.13/0.7.3/0.8.13.

**Not run.** No Gradle and no live clients or servers, so there is no fresh green
claim for this candidate. The recorded 3,408 tests and 78+8 gametests were
**recounted** by F1, and every test task ran on the final sources. All 400+1,021
manifest entries and both jar hashes match. Nothing was run on Windows.

## 3. Verified defects

### Reconnect and detection

**V1 MEDIUM-LOW (new; S-1 cooldown combined with S-3 hint inertness): a relog
within 120 s of a successful legacy trial leaves DH stopped for that session (F2,
harness A).**
- **Where:** `DhClientSession.java:120-123` arms the per-address cooldown when the
  trial *starts*, not when it fails (coordinator confirmed). `:169` then refuses the
  trial while the cooldown is live. `probeProfile()` (`:112-118`) ignores
  `protocolHints`, which is write-only (`:52`), and confirmation is keyed to the
  discarded listener.
- **Failure sequence:**
  1. Join a 3.2.0-b server; protocol 16 is silent.
  2. The trial reconnect confirms OFFICIAL_15.
  3. The player relogs within 120 s.
  4. The fresh join probes 16 again and gets silence.
  5. The trial is blocked, so the session fails with "Advertised DH channel did not
     establish a session config".
  6. Diagnostics show `SOURCE_STOPPED`.
- **Fix:** arm the cooldown only on trial failure, or clear it when the family is
  confirmed. Add a diagnostics reason for a suppressed trial.

**D1-R5 LOW-MEDIUM (new residual of S-1): transfers during the configuration phase
are not recorded (R5, harness).**
- **Where:** `xplat/.../mixin/ClientTransferHook.java:15` calls `transferred()`, which
  records `Minecraft.getCurrentServer()` (`DhClientSession.java:106-108`). That value
  is null while `player==null`, i.e. throughout configuration. Confirmed via javap of
  MC 1.21.1.
- **Consequences:**
  - one cookie-less automatic reconnect to the hub per manual join behind a load
    balancer that transfers during configuration;
  - after the 120 s cooldown lapses (a hub dwell over 120 s), the S-1 loop returns at
    one reconnect per cycle.
- **Why tests miss it:** the test's `TestMinecraft.getCurrentServer()` is never null.
- **Fix:** shadow the listener's `serverData` in the mixin and pass it explicitly.
  Add a regression where `getCurrentServer()` is null.

**V2 LOW-MEDIUM (pre-existing policy that M2 makes easier to reach): cancelled
generation timeouts count toward the two-unanswered stop (F2, harness B).**
- **Where:** `:760-771` increments `unanswered` for generation even though it sends
  `Cancel` (`:767`), so the sync-permit rationale at `:709-710` doesn't apply. Only a
  `Response` resets the counter; a `Refusal` does not.
- **Consequence:** two silent generation waits with no full reply in between stop DH
  for the dimension. Before M2, this was masked by the `frames.isEmpty()` guard.
- **Fix:** count only sync timeouts, or reset on any server reply. Pin generation in
  a test.

**R1-D1 LOW (pre-existing; M2's "processing frames remain protected" overstates
it): a delivered reply loses protection between frame close and pending removal
(R1, harness P/E).**
- **Where:** the worker queues `pending.remove` on the main thread (`:1262`, `:1210`),
  then closes the frame (`:321`). The END-tick `expireRequests` (`:760`) and
  `pruneGeneration`'s guard (`:788`) can run in between.
- **Consequence:**
  - prune sends `Cancel` for a completed request and resets its accepted receipt
    (10/10 trials);
  - expiry counts a timely reply as a timeout (4/5 trials).
- **Fix:** mark the request answered on the worker before `close()`.

**R1-D2 LOW (M2 parity gap): the NeoForge twin stamps arrival outside the lock and
uses the test seam in production** (`neoforge/.../DhClientTransport.java:33,72` →
`capture(buf, arrived)`).
- **Consequence:** the claim that "timestamps are assigned under the lock on both
  twins" is false. The effect is a microsecond-scale boundary inconsistency (harness
  N), with no wrong acceptance.
- **Fix:** call `capture(buf)` and make the overload test-only.

### Scheduling, admission and recovery

**R3-1 MEDIUM-LOW (performance; incomplete P-1): warm cached acquisition proceeds one
tile at a time, so the 50 ms floor never binds.**
- **Mechanism:**
  - A cached tile starts `restoreCache` and returns without setting `lastRequest`
    (`:1047-1053`), so `nextScanAt` stays at +500 ms (`:755-758`).
  - The callback's send (`:569`) never lowers `nextScanAt`; only a reply does
    (`:1204`).
- **Modelled warm range-128 fill:** about 270 s at 20 ms RTT, rising to about 1,500–1,800 s at 300 ms.
  The 2-slot bound would be about 211 s.
- **Fix:** after a cache-path request, set `nextScanAt=min(nextScanAt, at+50ms)`.
- **Regression:** two cached tiles should both be in flight before the first reply.

**R3-2/R3-3 LOW (r6 P-4): `DhSessionPolicy.defer` (`:93-97`) resets `failures` to 0
and drops `snapshot`.**
- **Consequence:** interleaved transient and permanent refusals never exhaust (61
  rejections without parking), and transient revalidations of held tiles become full
  (stamp 0) re-downloads.
- **Fix:** carry `failures(p)` and `snapshot(p)` forward.

**R2-1 LOW (r5 code; P-8 makes it reachable more often): recovery can orphan an
assembly owner.**
- **Mechanism:** a queued `expect` task (registered while the id is still in
  `pending`, `:1103`) runs after `discardAll()` but before the main-thread
  `pending.clear()`.
- **Consequence:** about 7 orphans within one 60/120 s lifetime trigger `expect`
  `RESOURCE_LIMIT`, then `fail()` (`:1108`).
- **Fix:** cancel the cleared ids on the worker after recovery, as `deferUpdate`
  does.

Related unproven detail: a failed `take()` reservation leaves the buffer charged
until idle expiry, about 10 s. The fix is to `discard(id)` on failure.

### Diagnostics (M1) — truthfulness only; admission behaviour is unchanged (R4, harness)

**R4-1 MEDIUM: parked or other per-tile informational gates mask the real blocker.**
- **Where:** `:499-500` takes the lowest-ordinal bit from a mask aggregated over
  every scanned tile, and any unknown wait turns the whole wait into -1.
- **Consequence:** on official servers, where missing tiles beyond 32 chunks park,
  `/lss diag` routinely shows `PARKED, -1` instead of the real
  slot/rate/manual/local-pacing gate.
- **Test gap:** the existing test uses a single tile.
- **Fix:** take the primary reason and wait from the first candidate that reached
  admission.

**R4-2 LOW-MEDIUM: the primary reason and the wait can come from different gates.**
- **Where:** `DhAcquisition.and` uses the minimum ordinal for the reason but the
  maximum for the wait.
- **Example:** `SERVER_RATE, 490 ms`, where the 490 ms is actually LSS's own 500 ms
  floor. Between scans it shows `LOCAL_PACING, 499 ms`, which is the scan cadence.
- **Fix:** the primary reason should be the gate that owns the reported wait.

**R4-3 LOW-MEDIUM: between scans, nearly every sample collapses to `SCAN_CADENCE ≤500 ms`.**
- **Where:** the observation is cleared each tick (`:672`), and only the rate/slot
  flags are rechecked (`:748-753`).
- **Consequence:** about 9 in 10 `dh_status` and `/lss diag` samples hide `DEFERRED`,
  `IN_FLIGHT` and `NO_ELIGIBLE_WORK`, which falls short of the plan's
  idle-vs-outstanding acceptance.
- **Fix:** keep the last scan's terminal reasons, aged, and add `SCAN_CADENCE`
  alongside them.

### Cache (R6)

**R6-D1 MEDIUM-LOW (new residual of C-3): one undeletable oldest file disables quota
enforcement for every bucket.**
- **Where:** `DhTerrainCache.java:281` (`trim` → `deleteIfExists` throws after the new
  file was already moved into place).
- **Consequence:** each later write persists and then throws ("declined"), so the
  cache grows all session. The next session's `positions()` throws and the cache is
  disabled.
- **Evidence:** quota 6 → 23 files.
- **Fix:** per-file catch in `trim`; skip and continue.

**R6-D2 LOW (pre-existing): an unsafe cache state blocks `/lss reset` and
`/lss clearcache` in DH mode.**
- **Triggers:** a linked root, a symlinked `!dh-terrain-v1`, or a damaged
  `quota.lock`. `clear` throws, which reaches `drainForReset` and then `resetFailed`.
- **Why this is unnecessary:** a refused root never held cache data.
- **Fix:** treat a refused root as owning nothing, and allow `clear` to reset an
  owned malformed revision file.

### Lighting and fringe (R7)

**R7-D1 MEDIUM-LOW (regression of V-2/V-3): the fringe pump stalls during fast travel
at lower frame rates.**
- **Where:** `VoxyVanillaCoverage.java:98` returns whenever `!isLightUpdateQueueEmpty()`
  (coordinator confirmed).
- **Mechanism:** MC drains only `max(10, n/10)` light entries per frame, and chunk
  *unloads* also enqueue entries. At RD32 sprint the queue is never empty below about
  24 fps (about 35 fps on a diagonal).
- **Replica results:** at 30 fps, 687 chunks were never refreshed versus 263 on r5. At
  20 fps, 1,368 versus 352, with exposures of up to 13 s.
- **Test gap:** the unit test stubs the queue as always empty.
- **Fix:** decide readiness per chunk (Voxy's own `getDebugSectionType`/`lightOnInSection`
  check), and skip unready chunks for one tick instead of gating globally.

**R7-D2 LOW (unfixed part of V-1): air above water or lava pools without sky is still
too dark.**
- **Where:** `DhColumnConverter.java:92` requires `waterSkyLoss==0`.
- **Evidence:** 333 block-light faces darker by up to 14 levels in the official
  ocean capture.
- **Fix:** apply the floor rule to block light only over a fluid floor, mirror it in
  `matchesCache`, and bump the conversion version.

**R7-D3 LOW (new DH-parity mismatch): in a 1-voxel air gap the ceiling now takes the
floor's light.**
- **Evidence:** 267/463 official ceiling faces differ (was 45), and meadow sky 25/414
  (was 0).
- **Recommendation:** arguably closer to vanilla. Document it as an accepted tradeoff
  and pin it with a test.

### Settings and UI (R8)

**R8-1 LOW: tab pagination can skip a page when extra pages are installed.**
- **Where:** `LegacySodiumPage.java:346` (both twins); a later, narrower tab can fill
  the gap left by a tab that didn't fit.
- **Example:** with Sodium Extra's pages, the visible row is
  `Advanced, Animations, Particles, Details, LSS` with "Render" and "Extra" hidden.
- **Severity:** misleading only. Every page remains reachable, and base and Iris
  stacks are contiguous.
- **Fix:** stop at the first tab that doesn't fit.

**R8-2 LOW (documented U-1 residual).** "Sync on load" and "Live updates" are enabled
whenever reception is on (`ClientOptionCatalog.java:221-235`). A native-only user who
turns off Live updates writes `lod.dh.real_time_updates: false`, which makes the
file unreadable by 0.16.0. This is documented in `settings.md`. An optional fix is to
gate both options on Use DH.

### Evidence and receipts (F1)

**F1-1 MEDIUM: the manual-server receipts are stale, and the server cannot currently
be restarted.**
- **Stale claims:** `manual-server-restoration.json`, `completion.json` and the
  briefing ("now owns the shared harness") all say "ready".
- **What actually happened:** the storage watchdog sent SIGTERM at 15:30:46Z when C:
  dropped to 51.2 GiB.
  - No stop or save was logged, because Log4j shut down concurrently.
  - World file mtimes at 15:30:46.5–.8Z show the stop-path save ran.
  - No exit code or stop receipt exists.
- **Restart blocked:** a restart requires 52 GiB free and C: has 50 GiB.
- **Fix:**
  - add a dated liveness caveat;
  - have the watchdog write a `server-stopped.json`;
  - preferably issue `save-all` and `stop` before SIGTERM so the stop is logged.

**F1-2…F1-6 LOW.**
- **Disposition overstated:** it says brand phrases were removed, but
  "Native LSS…"/"LSS's DH source" remain in the dead `status()` path. That path has
  no production consumer, but the strings are compiled into the VSS jars.
- **Unlogged claim:** "catalog checks pass" has no retained log.
- **Receipt chain:** the root `verification.json` timestamp predates the observer-r2
  recomposition. 4 of the 10 case rows are bound to observer r1, which is defensible
  (the r1→r2 delta affects only `updates`) but not stated.
- **Stale disposition rows:** E-1 and U-2/U-3 still say "unrun" although the r6
  validation ran them.
- **Checkout droppings:** `config/lss-client-config.yaml` and
  `.lss/cache/!dh-terrain-v1/quota.lock` sit at the checkout root. The index holds an
  **empty staged blob** for the deleted `dh-support-0.14.0-ocean.dto`, so committing
  the index as-is would ship an empty orphan.

**R2-2 LOW: the allocation probe measured a pre-final `DhAssembly`.** It recorded
`fb3ab820…` while the shipped source is `fb028c6a…`. The `accept()` code is
identical, so the byte savings stand, but this isn't disclosed. The CPU ratios are
noise-dominated, so cite bytes only.

**F2-V3 LOW (overclaim): the bandwidth ramp never opens on bursty delivery.**
`DhBandwidthRamp.java:38-49` credits only continuous 1 s windows, and tiles of 500 KB
or less with gaps stall it (harness D). So "new work returns to 60 s once the ramp
opens" may never happen, and `requestLifetime` stays at 120 s for the whole
connection. This behaviour is pinned as intended, but the consequence is undisclosed.

## 4. Unproven concerns and the movement pause

**Unproven.**
- **Ramp state survives a same-connection backend switch** (`:30/:367`). A Support
  backend with an open ramp skips its 5 s spacing.
- **A manual rejoin of the hub within 300 s disables the trial**
  (`TRANSFER_RETRY_DISABLED`).
- **The pending-reconnect and cooldown states are invisible in diagnostics.**
- **Lock contention can cascade to `disableCache`** on a shared cache root.
- **`fail()` joins the worker on the main thread for up to 2 s.**
- **Conversion-identity hashing runs on the main thread** for about 26.6k states per
  session construction (~50–100 ms one-off, unmeasured).
- **Withdrawal is per session**, so the dimension change re-subscribes. This is
  benign but contradicts the "once per listener" wording.
- **Dead state:** `protocolHints`, `retryJoinDeadline` and `rejoinChecks`.
- **Iris and Sodium Extra legacy widgets** are unverified.

**Movement-pause candidates (F2 ranking from code; nothing confirmed).**
1. **The DH ingest gate at 256 Voxy *sections*** (`:743`, coordinator confirmed;
   push deferral at `:949`), against native's 6,144-section halt. Vanilla chunk
   loading alone (24 sections per chunk) crosses 256 at about 11 queued chunks, and
   the fringe pump keeps Voxy's queue near 128. Expected signature: DH stalls during
   flight and resumes when stationary.
   **Measure:** the fraction of `dh_status` samples with
   `acquisition_wait=INGEST_PRESSURE` while moving; A/B a private build gated at 1,536.
2. **Range-refusal re-arm on every chunk crossing** (`RangeContext` includes the
   player position, `:1014-1017`; `reconcileRange` un-parks all refused tiles). Check
   the refusal rate moving vs stationary.
3. **The single generation slot and server generation latency** (by design).
4. **V2's permanent stop.**
5. **Client hitches:** session-construction identity hashing, `VoxyTerrainCache.open`
   on the main thread every 25 ms for up to 2 s per accepted tile, and the R7-D1 pump
   behaviour.

**Proposal:** a spark profile of the Render thread during a reproduced pause, plus a
`dh_main_ms` accumulator in `dh_status`.

## 5. Verdicts on the r6/r9 corrections

| ID | Verdict (independent) |
| --- | --- |
| S-1 | **Fixed for play-phase transfers** (0/4 reconnects; no reconnect on a proxy backend switch). Residuals: D1-R5 (configuration phase) and V1 (cooldown blocks a later session). |
| S-2 | **Fixed.** Both directions recover on the same connection with no loop and no reconnect. |
| S-3 | **Fixed.** Unconfirmed families accept no terrain, cache or pushes; `legacyEvidence` rejects Support terrain under official 15. |
| S-4 | **Fixed.** Fires at 5,006 ms, which clears Paper's 4 s throttle. |
| S-5 | **Fixed** (`BETWEEN_SESSIONS`). F1 notes there is no behavioural test. |
| S-6 | **Fixed.** Vanilla ownership is recorded while native is advertised. |
| P-1 | **Floors changed; unpinned.** Warm cached fill does not reach them (R3-1). |
| P-2 / P-6 / P-7 / P-8 | **Fixed.** Residuals: R2-1, and the R6 note on pending-but-not-held out-of-range pushes. |
| P-3 | **Fixed** (registration guard). Residual: R2-1. |
| P-4 / P-5 | **Fixed in intent.** `defer` bookkeeping slips (R3-2/3). Separate clocks verified. |
| C-1 | **Fixed within one JVM.** Cross-JVM protection is not shared (costs re-downloads only); the disposition should say "within one JVM". |
| C-2 / C-3 | **Session containment fixed** (no IO path reaches `fail()`). Residuals: R6-D1/D2. |
| C-4 | **Fixed.** The two-JVM quota holds and a stale lock after a halt is harmless. Concurrent first creation can decline once; every lock acquisition bumps the revision (performance only). |
| C-5 | **Fixed.** Identity is frozen per session and includes fallbacks, sorted overrides, block states and biomes. Light-dampening changes on the same registry are not covered (low). |
| V-1 | **Fixed for non-fluid floors**, and `CONVERSION_VERSION=3` is enforced on read and write. Residuals: R7-D2/D3. `ContentOracle` still re-derives light with the converter's own rule. |
| V-2 / V-3 | **V-2 fixed** (0% first-attempt rejects). **V-3 improved at 60 fps but regressed below about 45 fps at RD32** (R7-D1). |
| U-1 | **Fixed for defaults and native-only saves.** Residual: R8-2. Opted-in users can't downgrade, which is documented. |
| U-2 / U-3 / U-5 | **Fixed.** The twins are identical, the hook is a no-op on Sodium 0.7/0.8, the obsolete binders and stubs are gone, and the tooltips are accurate. Residual: R8-1. |
| U-4 | Typed reasons are fixed. The `status()` strings are still unconsumed (F1-2). |
| K-1 | **Fixed.** 22 of 23 mutations are caught; the escape is an inert fixture mixin JSON. |
| E-1–E-3 | Supported by evidence (F1). The disposition rows are stale. |

**Accepted decisions not reopened:**
- Voxy queued-write ordering, completion and persistence. The tooltips and status are
  honest, and "remains authoritative" is gone.
- No discovery reconnect after reconfiguration.
- A native offer wins until the next join.
- The strict pruning drain guard.
- The one-way ramp.
- Support ignores its bandwidth field.
- Preserve-on-mismatch.

## 6. Recommended order
1. V1, V2, R1-D1 and D1-R5 (reconnect and timeout traps), plus R2-1.
2. R7-D1 (fringe regression) and R6-D1 (quota disabled by one file).
3. A moving `dh_status` trace to decide the 256-section gate and the range re-arm,
   which bears on the movement pause.
4. R4-1..3 (diagnostics truthfulness), R3-1..3 (warm pacing and `defer`
   bookkeeping), and R6-D2.
5. Evidence and receipt fixes:
   - the manual-server liveness caveat and watchdog stop receipt;
   - the stale disposition rows;
   - the empty staged blob and checkout droppings;
   - binding the allocation probe to the shipped source.
6. When C: has headroom, an independent Gradle gauntlet on a fresh copy. It is owed
   this round, and is blocked by the 50 GiB storage reserve.
