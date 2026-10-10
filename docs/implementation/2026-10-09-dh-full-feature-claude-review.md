# DH full-feature Claude review: implementation and decisions (2026-10-09)

This review covers the candidate described in `2026-10-09-dh-full-feature-claude-review-briefing.md`: the Oct 8 r2 classes, plus the Oct 9 menu simplification and the build `0.16.0-dh-menu.dev20261009.1+1.21.1` (`/tmp/lss-dh-menu-20261009-r1`).

At the user's request it is larger than any previous round. It is also explicitly critical of the **decisions**, not only of the implementation.

This document reports findings only. It does not mark the plan, any phase, or any release complete. No fixes were made.

## Reviewer panel

There were 15 reviewers (12 Opus, 3 Fable), run in four batches to stay under rate limits. Each batch received the verified findings of the batches before it.

| Batch | Reviewer | Lens |
| --- | --- | --- |
| 1 | A1 | Ownership, lease release, malformed-input containment (codec, assembly, budget) |
| 1 | A2 | Source/connection takeover, lifecycle, invariants I1–I4, reset, LAN host |
| 1 | A4 | Pacing, scheduling, admission, recovery liveness, throughput |
| 1 | F1 (Fable) | Evidence integrity, test recount, Oct 8 fix verification |
| 2 | A3 | Protocol detection, legacy-trial reconnect, transfers, loader transport twins |
| 2 | A5 | DH source cache, Voxy readback, preserve-on-mismatch, quota, identity |
| 2 | A6 | Complete/unknown/update authority, pushes, sync on load, receive OFF/resume, hidden settings |
| 2 | A7 | Conversion fidelity, lighting, fringe pump (`VoxyVanillaCoverage`) |
| 3 | A8 | Menu (both Sodium generations, both loaders), YAML, downgrade, labels |
| 3 | A9 | Diagnostics, status, trace, export privacy |
| 3 | A10 | Packaging, release gate, native byte-identity, mixin/reflection resolution, brand |
| 3 | F2 (Fable) | Integrated adversarial review, the movement pause, user-facing truthfulness |
| 4 | D1 | Architecture decisions |
| 4 | D2 | Product, process, upstream and ecosystem decisions |
| 4 | F3 (Fable) | Strategic synthesis and red-team of the reviews themselves |

Every A/F reviewer ran Java 21 harnesses under `nice` against the exact candidate classes. They used read-only classpaths from `/tmp/lss-dh-menu-20261009-r1`; F1 and A4 confirmed that those sources are byte-identical to the worktree.

No reviewer ran Gradle. The user's manual server holds the shared harness lock, so it was left alone. No reviewer touched a server, client, Prism instance or process, and none edited the project. All scratch work lives under the session scratchpad `oct9/<reviewer>/`.

---

## 1. Summary

**What is good.**
- The `common/` layer (codec, frame assembly, decoder, memory budget) is well built:
  - A1's 54,896 lease-return checks over real corpora found no leak.
  - About 14,000 mutations were contained, apart from one exception class (D-9).
- Native wire, server, far-player, Xaero and `LSSApi` classes are byte-identical to published v0.16.0 (A10).
- Invariants I2/I3/I4 hold.
- Every Oct 8 fix that was claimed as fixed is fixed (F1, A1–A9).

**What is wrong.** The highest-severity problems this round are not corner bugs. They are interactions between mechanisms added after the plan:
- A push timestamp of 0 freezes live updates (D-1).
- One dropped fragment stops DH entirely (D-2).
- One dropped frame purges the whole cache (D-3).
- Delivery throughput is bounded by frame rate (decision X-1).
- Every chunk crossing at range 128 costs 26 ms (D-4).

**The trajectory.** F3's recurrence map shows a new HIGH or MEDIUM finding in every defect class in round 8, so the fix rate is not converging. The work is also still uncommitted (§6.1).

**Recommendation.** Freeze features, commit the work, and make the scope decisions in §5 before another fix round. If the user continues anyway, the minimum before any wider testing is D-1 + D-2 + D-3 + D-4 and the per-tile main-thread handoff (X-1).

---

## 2. Verified defects (actual defects, ranked)

New/regression labels are relative to the Oct 8 review. "Reproduced" means a reviewer harness reproduced the defect against the candidate classes.

### HIGH

**D-1 HIGH — a push with timestamp 0 permanently fences its columns, so later edits never land ("friend builds behind you"). Regression in effect of Oct 7 P-2.**

- **Where:** `xplat/.../DhClientSession.java`
  - `:437-442`: `UpdateFence.protects` returns true whenever `timestamp[c]<=0`, and `supersededBy` can never release a fence that holds a 0.
  - `:1036`: the push records `max(...,0)=0`.
  - `:1407-1409`: whole-source delivery honours the fence, so the column is counted as "reused" and not ingested.
  - I re-read all of these lines myself.
- **Upstream fact (A6):** every official 3.3.3 push carries `modifiedTimestamp()==0`. A6 confirmed this by scanning the 3.3.3 jar's bytecode: pushed sources are built through `createEmpty` and never stamped. The server stamps its database row and pushes the unstamped object.
- **Trigger:** a tile receives one push. The next change to that chunk then misses the push path, because of any of:
  - the backlog gate;
  - a 5 s main-thread timeout;
  - capture or pressure loss;
  - an out-of-interest drop (the player flew away);
  - a live-updates off→on window.
- **Expected:** the reacquired, strictly newer whole reply applies the edit.
- **Actual:** 0 columns ingested, the receipt is re-held at the new stamp, and the fence stays.
  - The same block applies to sync-on-load changed replies after an absence, to recovery re-downloads and to cache-miss full requests.
  - The tile heals only on a later direct push, a dimension change, receive OFF→ON, or a relog.
  - Pressure recovery cannot heal it either, because P-2 made fences survive recovery.
- **Reproduced:**
  - A6 E1/E1b (backlog-deferral and pressure-recovery paths): ts0 ingests 0, control ingests 16.
  - F2 `F2Story` A: the full production story (push → fly away → out-of-interest defer → return → newer reply), ts0 ingests 0, control ingests 16.
- **Narrow fix:** fence by causal order, not by content stamp. Record the local arrival sequence when a push is applied. A reply to a request *sent* after that point is newer on the one TCP stream and must win. Never let `ts<=0` mean "forever".
  - Caveat (A6): upstream request coalescing can hand a new request an in-flight older read. Document that fallback.
- **Regression tests:** A6 E1/E1b and F2 story A.

**D-2 HIGH (new) — a capture-side terrain drop can stop DH for the session instead of triggering recovery.**

- **Mechanism:**
  - `capture` drops terrain frames once 24 are queued (`:285-288`).
  - Recovery's `assembly.discardAll()` (`:950`) runs on the worker.
  - Fragments of the same buffer that were captured *before* the drop are still queued. They are processed after the discard and re-create the buffer (`DhAssembly.java:60-64`). Later fragments append to it.
  - The assembled payload has a gap, so `DhTerrainDecoder` throws MALFORMED ("Negative DH blob length", `:30`).
  - Only UNOWNED/EXPIRED/UNSUPPORTED are tolerated (`:1058`). Any other exception rethrows and calls `fail()` (`:320-321`).
  - A1 found a second window: while a recovery is queued, `recoverPushPressure` (`:945`) is a no-op, so drops in that window never get a `discardAll` at all (A1 D2, same class).
  - The response path (`:1337`) has the same exposure.
- **Expected vs actual:** DH should keep running. Instead `active=false`, and the user sees nothing in game.
- **Reproduced:**
  - A4 harness D: 3 of 3 runs stopped.
  - A4 harness R: it also happened naturally at 30 FPS with 2 pushes/s at the official sender's 2.5 KB split size, during one tile delivery.
  - A1 Probe5: gapped assembly → MALFORMED on official 3.2.0-b and Support corpora.
- **Fix:** stamp each buffer with the loss epoch it started under, and discard any buffer started before the latest loss, even while a recovery is queued. A decode failure on a buffer that spans a loss should be treated as pressure.
- **Test:** promote A4 harness D, and A1's two-drops-in-one-window sequence.

### MEDIUM

**D-3 MEDIUM (new; liveness, made reachable by X-1) — one dropped frame or decode `RESOURCE_LIMIT` on the push path wipes every receipt and the whole persisted source bucket.**

- **Where:** `recoverPushPressure` calls `terrainCache.invalidateAll()` (`:952`), then `policy.invalidateAll(); cachedPositions.clear()` (`:964`) whenever updates are allowed. Re-read by me.
- **Reproduced (A4 harness R):** at 30 FPS and 1 push/s during one fresh tile delivery, the inbound queue hit 24 and 3 fragments were dropped. Held receipts went from 1600 to 0, and cache files from 1 to 0.
- **Consequence:**
  - A range-128 disc must be re-downloaded at the X-1 rate, about an hour at 30–60 FPS.
  - The next rejoin also has nothing to revalidate.
- **Fix:** pushes only arrive within `updateDistance`, so scope invalidation to that window plus movement since the last scan. On a decode `RESOURCE_LIMIT`, the tile's position is already known from the DTO header, so `deferUpdate(position)` is enough.

**D-4 MEDIUM (new; performance, the leading candidate for the reported movement pause) — a 25–29 ms client-thread hitch on every chunk crossing at DH range 128.**

- **Where:** `observeLoaded` (`:911-932`) builds `Set.copyOf(next)` twice (`:926`, `:931`) over about 4,489 `DhPosition` records. `DhPosition` (`common/.../dh/DhPosition.java:4`) uses the default record hash, which collides heavily: 4,225 tiles map to 2,049 distinct hashes. `ImmutableCollections.SetN` uses linear probing, so the collisions become long probe chains.
- **Measured:**
  - A4 in-situ: 25.4–29.1 ms.
  - F2 re-run: 26.3 ms, and 27.7 ms while walking.
  - The JFR profile is dominated by `SetN.probe`.
  - Isolated `Set.copyOf` costs 3.30 ms, against 0.09 ms with `Long` keys.
  - Range 32: 0.01 ms; range 64: 0.09 ms.
- **Reach:** this is the default on official servers. The client distance is 0, so the range is `min(128, sync distance)`.
- **Fix:** give `DhPosition` a well-mixed `hashCode`, for example `Long.hashCode(mix(packed()))`, or use `HashSet`/packed-long keys.
- **Test:** a probe-count or timing bound on `observeLoaded` at range 128.

**D-5 MEDIUM (new) — an unusable Voxy reader makes the client re-request every cached tile every 2 s indefinitely.**

- **Where:**
  - `deliver` with reuse and `cached==null` (`:1380-1383`) calls `retryCache(...,true)`.
  - That calls `deferUnverified` (2 s, no backoff, `:622`).
  - The scanner (`:1105-1109`) then runs `restoreCache`, which sends another conditional sync request (`:590-603`).
- **Trigger:** Voxy's ingest works but the read-side internals (`WorldEngine.acquire`, `WorldSection.copyData`, `Mapper` entries) do not. Those are deeper internals than `rawIngest`.
- **Reproduced (A5 `A5Session`):** a stationary client sent 6 of 6 conditional requests over 6 windows for the same tile.
- **This contradicts the brief:** "no periodic refresh / stationary polling".
- **Fix:** park the tile until the destination engine or interest changes.

**D-6 MEDIUM (new, inspection) — the conversion identity depends on registry order, so the DH cache never survives a per-boot registry permutation.**

- **Where:** `DhColumnConverter.java:107-108` hashes `Block.BLOCK_STATE_REGISTRY` and the biome holder id map in id order. Re-read by me.
- **Why order doesn't matter to the output:** ids round-trip within a boot, and readback compares `BlockState` identity.
- **Trigger:** VisualWorkbench-class mods, the same precedent that produced v0.13.1's store-permutation fix in this repo.
- **Consequence:** every launch creates a new bucket, so every rejoin is a full download that overwrites vanilla-refined Voxy terrain across the whole range (the H-1 exposure). Dead buckets accumulate until LRU removes them.
- **Fix:** digest a sorted set, as v0.13.1 did.
- **Test:** a permuted registry must give the same digest.

**D-7 MEDIUM-LOW (new) — a confirmed `/lss reset` while DH is selected but not acquiring kills DH and keeps stale receipts.**

- **Where:**
  - `ClientCommandActions.java:69-92` captures `dhActive=canAcquire()`.
  - When that is false, `ResetCoordinator.java:132-145` takes the "no session" branch. `drainForReset` retires the session, but `resumeAfterReset()` is never called (it lives in `lssFlush`, `:89-91`).
- **Triggers:**
  - WAIT_CONFIG or WAIT_WORLD;
  - a push-only server;
  - a stopped session.
- **Actual (A2 `ResetHarness`):**
  - The static session stays retired until a dimension change or reconnect.
  - `hasSnapshot`/`!due` receipts survive, so wiped tiles are treated as held. Support servers and no-sync servers keep the holes.
  - Feedback says "No LSS server on this connection".
- **Fix:** call `resumeAfterReset()` after any successful `drainForReset` in a DH context, in both branches, and use DH-aware wording.

### LOW-MEDIUM

**D-8 — informational waits across separate tiles combine by the longest wait, so a known retry shows as "parked, -1"** (A9 D1; the open part of R4-1).
- **Where:** `informationalWait` (`:551-554`) uses `DhAcquisition.and`, which picks the longest wait. That is right for gates blocking one request, but wrong across tiles.
- **Reproduced:** tile B parked plus tile A deferred 2 s gives `PARKED, -1`.
- **Fix:** combine informational waits across tiles by the shortest wait.

**D-9 — the out-of-interest push band leaves an old DTO for tiles that are cached but not held** (A6 D2; residual of P-6).
- **Where:** `:998-1001` defers only when the tile has a snapshot or a fence. LSS measures distance to the tile centre, while upstream measures to the tile edge, which leaves a band of about 2 chunks.
- **Consequence:** with Sync on Load off, a later restore goes through `restoreCache` → `deliver(...,true)` and the edit is lost.
- **Reproduced:** A6 E3, push stage only; the restore step is by inspection.

**D-10 — the V1 residual: the trial cooldown is cleared only on the first non-empty terrain** (A3-1; A2 D3 is the same class).
- **Where:** `:493`, `:1295-1301`.
- **Effect:** a relog before the first non-empty tile, or any trial session that never gets one, or a trial cancelled before it fires, suppresses the next trial for 120 s. The reason shown is `LEGACY_TRIAL_SUPPRESSED`.
- **Reproduced:** A3 H2.
- **Why tests miss it:** the pinned test calls `confirmProfile` directly.

**D-11 — after the vanilla cap, `/lss diag` says the world changed** (F2-2).
- **Where:** `DhConnectionState.java:158-161` puts the vanilla cap into the same `blocked` set as a world-key change. The diag then shows `BLOCKED` with `WORLD_CHANGED` ("logical world changed; rejoin required").
- **Reproduced:** 262,145 `vanillaLoaded` calls.
- **Why it matters:** A4 estimates the cap is reached after about 87 minutes of elytra flight, so beta testers will see this.
- **Fix:** add a separate `VANILLA_CAP` reason.

### LOW

| ID | Defect | Where | Source |
| --- | --- | --- | --- |
| D-12 | Corrupt Zstd escapes as `ZstdException` (a `RuntimeException`), not `DhCodecException`, because the `Zstd.isError` branch is dead. Leases are still released and the session effect today equals MALFORMED. 6,120 of the harness's mutations escaped this way. | `DhTerrainDecoder.java:282-283` | A1 D1, reproduced |
| D-13 | Tile-intrinsic impossibilities (span count > height, blob > tile budget) are `RESOURCE_LIMIT`, so they defer every 2 s forever (on pushes, see D-3). A valid 384-high tile with 245 spans per column is rejected. | `DhTerrainDecoder.java:193-194`, `:31` | A1 D3, reproduced |
| D-14 | An expired or unmatched response keeps its buffer charged and holds 1 of 8 slots until idle expiry (≤10 s). | `DhAssembly.response` `:148-153` | A1 D4, measured |
| D-15 | A transient quota-lock timeout in `read()` deletes a valid cache entry (two JVMs sharing a cache root). | `DhTerrainCache.java:112-115`, `:320` | A5 D3, reproduced |
| D-16 | Moving between scans forces an immediate admission scan, a scheduling change made by the R4-3 diagnostics fix. It costs 1.4–26 ms per crossing at range 128 and contradicts "no extra terrain scans". | `:789` | A9 D2, reproduced |
| D-17 | `TRANSFER_RETRY_DISABLED` shows on healthy sessions after a transfer (`transferredServer` never cleared; null==null in singleplayer). Conversely, `SOURCE_STOPPED` hides it when it matters. | `:529-532`, `:146` | A9 D3, A2 D2 |
| D-18 | The automatic reconnect uses `TitleScreen` as the parent, so later disconnects go to the title screen, not the server list. | `:198-200` | A3-2, javap |
| D-19 | The cooldown key is the raw `ServerData.ip`, so aliases each cost another forced reconnect. | `:122`, `:170`, `:493` | A3-3, reproduced |
| D-20 | If a v16 server's reply stalls past the 15 s quiet window, DH is lost for the session after one pointless reconnect. | discovery window | A3-4, reproduced (real-server frequency unknown) |
| D-21 | Receive OFF skips the withdrawal for sessions that announced but are not yet confirmed. | `:677`, `:719-720` | A6 D3 |
| D-22 | `SERVER_DISABLED` is reported when the *user* turned Sync on Load off. | `:1111` | A6 D4 |
| D-23 | An expected outcome (no DH on this server, or a reconnect pending) is shown as "stopped; inspect the local log". | status mapping | A9 D4 |
| D-24 | The DH-mode JSON export fakes native fields (`rendererAvailable=false` is wrong on 1.21.1). | `ClientStatus.java:83-91` | A9 D5 |
| D-25 | On Sodium 0.8 the Xaero "native-only" tooltip is frozen at game start (`registerConfigsLate` runs once), on both loaders. | `LSSConfigMenu.java` ~`:108`, `MenuContext.java:60-63` | A8, javap |
| D-26 | The shared `client.yaml` template now says "native LSS", and VSS ships it unbranded (common must stay byte-identical). Regression of U-5. | `common/.../settings/client.yaml:24`, `:67` | A10 L-1 |
| D-27 | Release-gate escapes: stub 0BSD licence (token only); notice reduced to three tokens; legacy Sodium mixin flipped to `required:true`; a listed mixin class missing; DH/P4 fixture mixin JSON in VSS jars. | `scripts/release_check.py` | A10 L-2, 37-case mutation harness |
| D-28 | Fringe budgets in the brief and in the border-gap doc are half the code's values (64/8/128 vs 128/16/256). | `VoxyVanillaCoverage.java:19-20` | A7 L1 |
| D-29 | Support-only: side faces under overhangs are darker than DH draws them (8,926 of 11,102 upper side faces in the meadow corpus; official producers unaffected). A fix needs a conversion version bump, which interacts with X-5. | `DhColumnConverter.java:84`, `:88-96` | A7 L2 |
| D-30 | `vanillaLoaded` is recorded when DH can never be selected (native latched, integrated server), up to about 15 MB of boxed longs. | `:256-263` | A2 D4 |
| D-31 | Dead code, some still pinned by tests: `protocolHints`, `retryJoinDeadline`, `rejoinChecks`, `DhSessionPolicy.unchanged()`, `ClientSessionGate.nativeSource`, `status()`/`describe()`. The comment at `:49-51` describes behaviour that no longer exists. | various | A2, A3, A6, A9, A10 |

### Evidence-integrity defects (F1, all LOW, no product impact)

- Gametest counts are hard-coded literals in `finalize-build.py`. The values are true per the logs.
- The catalog and settings-reference checks have no exit-code receipt (empty logs). F1 re-ran them: rc=0.
- Briefing wording: "only three class files differ" is right for classes, but lang files, `client.yaml` (comment-only change) and metadata also changed.
- Two of three modern-menu screenshots have a tooltip covering the claimed rows. The NeoForge 0.8.12 and legacy shots are clean.
- Some live "zero" fields in `UpdatesProbe.java:153-160` are literals implied by throw-gates rather than measured deltas.
- The index's "staged" entries are intent-to-add placeholders (`CE_INTENT_TO_ADD`). Nothing is actually staged, and the Oct 8 "empty staged blob would ship" mechanism does not apply.
- Fabric rig cases label the Fabric jar with a `…neoforge.jar` path. The hash is correct.
- Stale test name `walksTheCatalogIntoThreePages…` (catalog size is 2).

---

## 3. Unproven performance concerns and coverage gaps

**Unproven performance:**
- **Real official push rate during flight.** Unmeasured. Above about FPS/23 pushes per second, pushes defer (X-1), so this decides D-3's reach.
- **Real Voxy commit latency for verify retries.** Each retry costs 18 main-thread tasks plus 25 ms.
- **Readback below about 18 FPS.** Up to 18 main-thread round trips per pass and up to 2 s of worker block per tile, so cache admission is likely declined more often (A7).
- **GC churn from `readColumn` copies.**
  - Each column copies whole 256 KiB sections, four times per tile: about 48 MiB per tile pass against a 12 MiB minimum (A7).
  - F2 estimates 80–320 MiB allocated per tile during warm rejoin. Unprofiled.
- **Session-constructor hash.**
  - Measured at 15 ms warm and 58 ms cold under `nice` (A2), once per Session.
  - It scales with block-state count and is masked by loading screens.
- **Fringe pump.**
  - Always running, about 0.2 ms per tick because `ready()` runs before the `accepted` check (A7).
  - Its gate of 128 vs DH's 256 inverts priority behind far DH terrain.
  - Effect on border fill is unmeasured.
- **Decoder over-reservation.** About 34.5 MB reserved per tile against 0.5–50 KB real tiles, which makes `RESOURCE_LIMIT` reachable at about 220 MiB held (A1).

**Coverage gaps:**
- **Missing live cells:**
  - No live Fabric official-15 or Fabric Support cell.
  - No NeoForge client against a Fabric DH server.
  - No proxy or transfer cell.
  - No Support 0.14 receive OFF→ON.
  - Withdrawal is live-proven on official 3.3.3 only.
  - Resume revalidation is not live-proven (`UpdatesProbe` phase 15 never checks it).
- **NeoForge DH live cases ran Fabric Voxy 0.2.15 through Sinytra Connector**, not the native NeoForge Voxy fork that CLAUDE.md names as the pairing. Member presence was verified on all three Voxy jars, but DH acquisition on the native fork is not live-proven (A10).
- **Sodium versions:** NeoForge modern Sodium was 0.8.12-beta.1, not 0.8.13. Fabric legacy 0.6.13 is unrun; the twins are byte-identical (F1).
- **Lighting engines:** no 1.21.1 Moonrise/ScalableLux client. `VoxyVanillaCoverage.lightReady` depends on `getDebugSectionType`, mirroring Voxy (A10).
- **Unreproduced DH Support risk:** a buffer-id reset on every re-announce (up to 9× during slow start) could misassemble into MALFORMED → `fail()` (A6).
- **Unreproduced Voxy races:**
  - engine replacement mid-delivery (three rejected tiles stop DH);
  - an `acquireRef`/`free()` race at disconnect (A2).
- **Not run:** `release_check` on a mutated real build. Only the 37-case sandbox was run.
- **Not audited:** structural or paste similarity of `DhTerrainDecoder`/`DhAssembly` against GPL upstream (D2).

---

## 4. The accepted Voxy limitation (I1)

**Status.** Per the briefing, the enqueue-only `rawIngest` boundary is accepted and is not a release blocker. This review does not reopen it.

**What this round did check: whether the guards inside it work.**
- **They do.** A2 confirmed there is a single ingest site, a main-thread eligible/!loaded check before conversion (`:1404`) and again at handoff (`:1445`), and a cache-reuse recheck (`:1423`).

**Three points sit beside the acceptance:**
1. **D-1, D-3, D-6, the X-5 cache identity and D-15 are separate staleness or overwrite mechanisms.** The acceptance's basis (one artificial 4,516 ms delay) did not cover them. D2 and F3 both recommend that the user re-confirm the acceptance with D-1 in view.
2. **The guard cost is fully kept.** The per-column, double main-thread authority check is what makes throughput frame-rate-bound (X-1). That cost is paid even though the race it targets is now accepted as unclosable.
   - D1 and F3 recommend one batched main-thread handoff per tile. A4 says this would give "the same or stronger I1 atomicity", because `rawIngest` only enqueues.
   - This is a change to the guard design, not a reopening of the limitation.
3. **Neither the user nor this review has evaluated race avoidance.** D2 notes the acceptance was framed as "the API cannot fence it". The avoidance option is a geometric exclusion of vanilla distance plus a margin, so DH columns are never ingested near vanilla terrain.
   - That framing also omitted that the 1.21.1 Voxy route is an unofficial, all-rights-reserved community port, so "revisit when the API exists" may in practice mean never.

---

## 5. Decision critique

These are judgements, labelled strong, debatable or weak. Where reviewers agree, they are merged. Each lists the cost and the alternative.

### X-1 STRONG — per-column main-thread round trips bound throughput to the frame rate (A4, D1, F2, F3)

Each column's authority check and ingest is a separate blocking `onMain` call. `onMain` posts through `Minecraft.execute`, with a 5 s wait at `:1496`, and `runAllTasks` runs exactly once per frame (F2, checked in the `runTick` bytecode). A4's measurements of main-thread tasks per tile, frame-paced:

| Path | Tasks per tile | 30 / 60 / 144 FPS |
| --- | ---: | --- |
| Fresh, no cache | 35 | 1233 / 600 / 253 ms |
| Fresh + readback | 53 | 1839 / 932 / 507 ms |
| Unchanged cached revalidation | 36 | 1288 / 621 / 374 ms |
| Push | 23 | — |

**Consequences:**
- **Range-128 warm rejoin:** about 88 min at 30 FPS, about 44 min at 60 FPS and about 18 min at 144 FPS, against about 4 min for official DH (18.9 tiles/s).
- **Push capacity:** about FPS/23 per second.
- **The bandwidth ramp never reaches 500 KB/s** in any `RampSim` cell. 120 s lifetimes then persist, and pushes stay at 2.5 KB splits, which feeds D-2 and D-3.
- **The 50/500 ms floors and the R3-1 fix cannot bind.**

**Alternative:** about 3 main-thread tasks per tile — one authority check for all 16 columns plus enqueue, and one for verify — or the worker-side geometric exclusion with a per-tick snapshot (D1). The cost is a local refactor of `deliver`/verify. The Oct 1 review raised this as a MINOR finding, and the Oct 5 disposition deferred it. F3 calls that deferral the single most expensive unfixed structural item.

### X-2 STRONG — timestamp-based fencing on a producer that sends timestamp 0 (A6, D1, F2, F3)

This is the design root of D-1. F3 counts four freshness authorities:
- per-tile `Receipt`, with 9 fields;
- per-column `UpdateFence`;
- the DTO header timestamp;
- Voxy's actual storage.

They are reconciled at about 20 sites, and `0` means three different things across them. Every staleness fix since Oct 5 (M-4, D-6/D-7, P-2, P-4, P-6, R3-2/3) patched one comparison without reducing the number of authorities.

**Alternative:** order by a local monotonic push/request sequence (D1, F2). If live updates are not cut, reduce to two authorities: a receipt timestamp and the local push order.

### X-3 STRONG — recovery scope larger than the fault, and a single session-wide `fail()` (A1, A4, D1, F3)

- **The scope problem:**
  - One dropped frame purges the whole bucket and all receipts (D-3).
  - Two unanswered requests end the session (`:748`).
  - Unknown control ids or trailing bytes end the session, so a same-protocol DH minor release disables DH.
- **The pattern:** every round relabels one or two causes as transient (R-3, P3-3, L-2, D-8, P-8, V2) while new terminal paths appear (D-2). F3: "the container is wrong, not the labels".
- **Alternative:**
  - Make codec and assembly failures tile-scoped: poison the buffer, retry the request.
  - Decode control messages tolerantly: ignore unknown ids with a counter, but keep terrain decoding strict.
  - Never purge caches on transport faults.
  - When the session does stop, show an in-game line (F2).

### X-4 STRONG — forced reconnect for official 3.2.0-b (A3, A8, D1, F2, F3)

**Measured cost:**
- A silent forced reconnect on *every* join (5/5), about 18 s before DH starts, about 90 server ERROR lines per join.
- The DH server-side session for that player goes dead.
- Open screens and typed chat are discarded.
- No check for combat, elytra flight or vehicles: a combat-log risk.

**What the machinery produced.** It serves one superseded beta (3.3.0 shipped 2026-09-17), and produced S-1 (HIGH), D1-R5, V1, S-3 and now D-10 and D-18 to D-20.

**The user is told less than the truth.** The tooltip says "older servers may reconnect once"; it is every join.

**Alternatives:**
- **(recommended)** Keep the same-connection Support switch, drop the official-15 trial, and show "server runs DH 3.2.0-b; ask the admin to update".
- **(otherwise)** Never reconnect automatically. Show a one-time "rejoin to use this server's older DH protocol" message, and use the address hint on the next *manual* join.

Either way, deleting the trial removes `Retry`/`pendingRetry`, the cooldowns, `protocolHints`, `retryJoinDeadline` and possibly the required `ClientTransferHook` mixin.

### X-5 STRONG — the DTO source cache with preserve-on-mismatch (A5, A7, D1, F3)

- **The comparison is decision-neutral.** `if(matches || preserve)` (`:1422`) takes the same action either way, so the full per-voxel `matchesCache` comparison only feeds counters. Only `isDefault()` affects anything.
- **What the cache buys over a timestamp-only receipt store.** About 8 B per tile is enough for sync. The only DTO-specific benefit is rebuilding Voxy-lost (default) columns without a download, a rare case.
- **What it costs:**
  - about 200 MB of disk;
  - identity, quota, cross-process lock, reparse and revision-repair code (412 lines in `DhTerrainCache` alone);
  - up to 2 s of readback per tile;
  - quota eviction of the *nearest* rings, about 1.94 range-128 discs total;
  - every evicted tile is a full re-download that overwrites refined terrain.
- **The identity defeats the cache's purpose.** It hashes the whole registry in order (D-6), plus `CONVERSION_VERSION` in the directory identity. Any mod change, any lighting tweak or a per-boot permutation therefore discards the "raw source we can re-convert" (A7).
- **History.** The plan's own §5.3 scoped a persistent receipt index *out*. It was added 2026-10-05, and F3 estimates it has since drawn about 40% of all findings, through five eviction designs in four days.
- **Alternatives:**
  - **(D1)** A persistent tile→timestamp map per (server, DH world key, dimension), about 1 MB per 100k tiles. On rejoin, probe `isDefault` for a few columns per tile.
  - **(A5)** If the DTO is kept, persist a vanilla-observed bitmap per chunk so the DTO actually decides something. Also keep the version per entry, not in the directory identity (A7).

### X-6 STRONG-ISH — always-registered DH channel and always-on fringe pump (A2, A7, A10, D1, D2)

- **The channel.**
  - `distant_horizons:msg` is registered unconditionally (`LSSClient.java:17`; NeoForge `playBidirectional`).
  - Every LSS user, including those with Use DH off, looks like a DH client to mod-policing servers.
  - The NeoForge integrated server advertises DH to LAN guests and never answers. The impact on a real DH guest is unverified.
- **The fringe pump** changes Voxy behaviour for every Voxy+LSS user — singleplayer, vanilla servers and native mode — from inside an experimental DH feature, under a setting named for server LODs.
- **Alternative:**
  - Register the channel only when `lod.source` allows DH. `lod.source` is already session-timed, so a restart or reconnect requirement is acceptable for an experimental feature.
  - Give the pump its own flag and decision record.
  - Also make `ClientTransferHook` non-required (A10).

### X-7 STRONG — Paper ships about 281 KB of dead DH/XZ code, and the release gate requires it (A10, D1, D2)

The Paper jar grew from 8,321,235 to 8,602,639 B. No server path reaches the added classes, and XZ ships unused encoders too.

**Alternative:** exclude `common/dh/**` and `internal/xz/**` from the Paper shadow jar and require their absence there. The cost is small.

### X-8 STRONG (packaging) / DEBATABLE (approach) — bundling the adapter in LSS vs an optional add-on mod (A10, D1, D2, F3)

**Measured cost of bundling:**
- size: +363 KB Fabric, +393 KB NeoForge, duplicated in the VSS jars;
- two new required client mixins;
- the always-registered channel;
- about 10k lines only on the best-effort 1.21.1 line, editing hot native files (`ClientNetGlue`, `ClientSessionGate`, `ColumnCacheStore`, `VoxyCompat`). That raises the fold cost of every future backport — the same cost that sank single-branch consolidation (77 + 85 files on this line);
- "Voxy Server Side" now ships a DH client adapter, which muddles the brand;
- strict control decoding ties LSS releases to DH protocol bumps (11 → 16 in about 14 months).

**History.** The plan's §12 dissent priced a standalone mod (option D) at "about half" the cost.

**Counterpoint.** The integration seams — precedence, reset drain, settings, Voxy bridge, cache namespace — need a small internal API, and one jar is simpler for users.

**Verdict:** if the feature survives, an add-on mod is the best value (D1 estimates 3–5 days to repackage). The middle path is X-6 + X-7.

### X-9 STRONG (label) / DEBATABLE (structure) — the Oct 9 menu (A8)

- **Collapsing to one checkbox is mostly sound** (many keys are already file-only). It does remove the user-level workaround for D-1: toggling live updates now needs a YAML edit plus `/lss reload`.
- **The label "Use Distant Horizons" is misleading.**
  - It only *allows a fallback*; native still wins.
  - Its tooltip says "requires Voxy *without* the DH client".
  - The zh_cn/zh_tw translations say "Enable DH".
  - Suggested labels: "Allow DH Server Terrain" (122 px) or "Load from DH Servers" (113 px). Both fit under the Xaero label's 130 px.

### X-10 DEBATABLE, leaning weak — downgrade policy (A8, D1)

- **Ticking the one checkbox makes the YAML unreadable by v0.16.0**, which then turns LSS off. The reader is fatal on unknown keys.
- **Unticking it later does not help** if any hidden DH key is false (A8 probe case 5c).
- **Retired keys keep the file unreadable.** `lod.dh.refresh_seconds`/`profile` are skipped silently and do the same.
- **The people most likely to downgrade are this build's testers.**
- **Cheapest robust option:** a sidecar YAML for `lod.source` + `lod.dh.*`. Otherwise, state the policy in the release notes and the tooltip, not only in a YAML comment.

### X-11 DEBATABLE — native always wins, including disabled or denied offers (A2, F2)

- **Arguments for:** it is conservative, simple, and never bypasses an admin's denial. A permission denial and "disabled" arrive on the same rung.
- **F2's added cost:**
  - The README recommends "add LSS alongside DH".
  - With `requireServicePermission` armed, an ungranted player is retired off DH and gets nothing.
  - A server with LSS `enabled:false` alongside DH also gives LSS users nothing.
- **Alternative:** a DISABLED or denied native offer permits DH fallback, and only an enabled native offer wins. This reverses a recorded user decision, so it is the user's call.

### X-12 STRONG — sync-on-load debounce is per Session, not per connection (A2, A6, F2, D1)

- **The mechanism:** `loadedAt` is a Session field (`:416`) and `tick()` builds a new Session per level (`:215`). Every dimension return, receive toggle or reset therefore revalidates the whole disc.
- **Combined with X-1:** at range 128 every portal return is a 44-minute revalidation churn at 60 FPS.
- **Upstream parity:** it matches upstream's per-level `visitedPositions` (A6), but upstream's delivery is about 10× faster.
- **Alternative:** move `loadedAt` into `DhConnectionState`, keyed per dimension.

### X-13 STRONG — resume with retained receipts (A6)

- **With sync on:** resume revalidates everything anyway, and held tiles without a cached source take a worse path (unchanged → `deferUnverified` → a second full request). Pushed tiles always take this route.
- **With sync off:** resumed receipts stay held forever, so edits made while OFF never appear.
- **Alternative:** treat resume as a load when the server permits sync, and otherwise clear the receipts.

### X-14 DEBATABLE, leaning strong — the diagnostics system is larger than its user value (A9, D1, F3)

- **Size:** 27 gates, 12 reasons, 10 states, a 64-bit mask, first-candidate ownership, and aged observations — all running in the per-tile scan loop.
- **Churn:** three rounds of corrections (R4-1/2/3, then D-8).
- **Feedback into scheduling:** the diagnostics now change admission timing (D-16).
- **What users see:** one line, plus a raw mask integer (`gates=6291456`) nobody can decode.
- **Misleading values:** "-1 ms" reads as a fault on healthy and parked sessions.
- **Not explained:** the movement pause is still unexplained after three diagnostic rounds.
- **Alternative:** a user-facing "working / waiting for server / waiting for Voxy / idle / stopped (reason)" plus counters, with gate detail in the trace only. Also delete `status()`, which has had no production consumer since Oct 7.

### X-15 DEBATABLE — admission caps and the Voxy gate (A4)

- **Caps.** 1 generation / 2 total requests against official defaults of 20/50. Raising them is pointless until X-1 is fixed.
- **Support pacing.** Support is capped at about 1 tile/s, so 289 tiles take at least 5 minutes.
- **The Voxy gate is smaller than one tile.** The 256-section gate is below one tile's 384 sections, and air sections are sent too. Each delivered tile therefore trips `INGEST_PRESSURE` and defers pushes. Raise it to at least 384, or skip air sections.
- **The vanilla cap is a hard stop.** At 262,144 columns DH stops for the session, after about 87 minutes of elytra flight. A per-region bitset (128 B per region) removes it.

### X-16 STRONG — process and strategy (D2, F3)

- **Gates skipped.** The plan's pre-code gates were skipped or waived:
  - DH outreach, which the plan made a kill criterion if refused. "Outreach pending" appears in 36 docs over 10 days.
  - Recorded demand: still zero known requests.
  - The S0 server-load ratio: never measured, although it is a kill criterion.
  - The 5-day spike box: overrun without a recorded stop decision.
  - Kill criteria: only I1 was ever recorded, and that by waiving it.
  - The protocol watcher: not built.
- **Deferred items built anyway.** Automatic LSS/DH choice, the persistent receipt index and applying partial updates were all on the plan's deferred list. All three were built before the first-release gates, and they are where today's HIGH and MEDIUM findings cluster: D-1, D-3, D-6, X-4 and X-5.
- **Cost:**
  - About 10 days and about 11 review rounds, with at least 58 reviewer runs including this one.
  - 43 DH docs: about 9,200 lines, more prose than production code.
  - 45 GB of local evidence.
  - `DhClientSession.java` is 1,522 lines, with about 70 mutable fields of mixed thread ownership.
- **Trajectory (F3):** every defect class had a new HIGH or MEDIUM in round 8, so the fix rate is not converging.
- **F3's red-team of the reviews themselves (the reviews share responsibility):**
  - Oct 5 offered preserve-on-mismatch as a peer of the vanilla bitmap, and that became a permanent-staleness problem.
  - Oct 6 listed fences as "checked and sound" without asking what establishes order. Oct 7 P-2 then made the D-1 freeze survive recovery.
  - Oct 8 R4-3 asked for truthful diagnostics and produced a scheduling change (D-16).
  - The cache protection ladder went through five designs in four days.
  - No review after Sept 29 recommended a freeze. Each one said "suitable for continued supervised play" and was followed by a new feature.
  - The one-regression-test-per-finding policy built a 125-test pin forest around mechanisms now slated for removal.
- **Storage reserve.** The bounded-storage reserve for the `dh-p4-2g-v2` profile was lowered from 50 GiB to 40 GiB with no recorded user decision (`test-fixtures/dh-p4/bounded_storage.py:8-9`). The lower floor was introduced after the blocker had already cleared (F1).

---

## 6. Recommendations (for the user to decide; nothing here was done)

### 6.1 Immediately

- **Commit the work to a branch and push it to a private remote** (D2). HEAD is still `6677fe93`. About 228 status entries (338 files by F1's count) exist only as intent-to-add, modified and untracked files in one clone, whose only remote is the parent repo on the same disk. One `git clean` or disk fault loses ten days of work.
- **Ratify the 40 GiB reserve or revert it to 50** (F1).

### 6.2 Before another fix round: a scope decision (F3's top recommendation, supported by D1 and D2)

1. **Freeze features.**
2. **Send the DH outreach** and record the answer.
3. **Measure the two numbers a go/no-go needs:**
   - one server-load ratio against a real DH client;
   - the movement pause, using the §7 plan.
4. **Decide, and re-confirm the I1 acceptance with D-1 in view.**

### 6.3 If continuing: the minimal core all four strategic reviewers converge on

- **Cut:**
  - the official-15 auto-reconnect (X-4);
  - live updates, unless X-2's local-sequence fencing lands;
  - the DTO cache, in favour of a timestamp-only receipt map (X-5) — or at minimum fix D-6 and take the version out of the directory identity.
- **Fix:**
  - D-1 (if live updates stay);
  - D-2;
  - D-3;
  - D-4, which is trivial and the likely reported pause;
  - X-1's per-tile handoff;
  - X-12's per-connection debounce;
  - D-7.
- **Gate:**
  - channel registration and the fringe pump on opt-in (X-6);
  - remove Paper's DH payload (X-7);
  - move to a sidecar YAML (X-10);
  - rename the checkbox (X-9).
- **Shrink:** diagnostics to state + one reason + counters (X-14). Delete the dead code (D-31).
- **Write:**
  - a README section, which does not exist today (F2);
  - release notes naming the tier, cuts, stale-edit and ordering limitations, and the downgrade note;
  - an honest tooltip;
  - a protocol watcher.
- **Packaging:** strongly consider moving the feature into an optional add-on (X-8).
- **Review:** then do one consolidated review with three reviewers, not fifteen.

D1 estimates its deletion list removes 1,500–2,500 production lines and directly closes D-1, D-2 and X-4.

### 6.4 The lowest-risk ordering (D1)

| # | Change | Risk |
| --- | --- | --- |
| 1 | Replace the full readback comparison with `isDefault()` | None (decision-neutral by construction) |
| 2 | Drop the official-15 reconnect machinery | Low |
| 3 | Collapse diagnostics; remove the scan coupling | Low |
| 4 | Turn the vanilla set into a bitset; remove the 262k stop | Low |
| 5 | Exclude DH/XZ from Paper; gate the fringe pump | Low |
| 6 | Local-sequence fences | Medium (fixes D-1) |
| 7 | Per-buffer recovery | Medium (fixes D-2 and D-3) |
| 8 | Timestamp map instead of the DTO cache | Medium-high effort, biggest payoff |

### 6.5 What can be honestly promised today (D2, F3)

"Experimental, opt-in, 1.21.1 only. Lets a Voxy client show terrain from a server running official DH 3.3.3 / 3.2.0-b or DH Support 0.14.0. Slower than DH's own client. Distant edits may go stale. Not endorsed by the DH team."

**Not promisable today:**
- live updates (D-1);
- fast rejoin at large ranges (X-1);
- correctness against newer vanilla terrain (I1);
- any future DH version;
- hitch-free movement (D-4).

---

## 7. The movement pause: ranking and measurement plan

F2 ranked the candidates in DH mode, using A4 measurements and its own re-runs:

| # | Candidate | Magnitude | Correlated with movement? |
| --- | --- | --- | --- |
| 1 | `observeLoaded` per chunk crossing at range 128 (D-4) | 26.3 ms per crossing (0.01 ms at range 32) | Yes — exactly on chunk boundaries, 2–3 per second on elytra |
| 2 | Session-constructor registry hash | 15–58 ms once per Session | No (loading screens) |
| 3 | GC from readback copies during rejoin or sync | Periodic young-GC pauses | Coincides with arriving somewhere new |
| 4 | Ingest gates (256 / fringe 128) | A stall in filling, not a frame hitch | Yes |
| 5 | Re-scan after a crossing (D-16) | 1.4–26 ms per crossing (1.6 ms steady state) | Yes |
| 6 | `VoxyTerrainCache.open` mapper snapshot | Under 1 ms even at 26.6k entries — demoted | No |

**In native mode (Use DH off), none of 1, 2, 3, 5 or 6 exists.** Only the fringe pump runs, as a steady tax of about 1–3 ms.

**Plan.** The user runs this; ask before scheduling, as the briefing requires. No code changes are needed.
1. Run spark on the client with `profiler start --thread "Render thread" --interval 1`.
2. Walk the same straight 20-chunk line with the frametime graph showing.
3. Repeat in three arms:
   - (A) DH, `lod.distance_chunks: 0` (range 128);
   - (B) DH, `lod.distance_chunks: 32`;
   - (C) Use DH off.

**How to read it:**
- **Spikes at chunk-boundary cadence**, with samples under `observeLoaded`/`ImmutableCollections$SetN`, present in arm A but absent in arm B: candidate #1.
- **Spikes that persist in arm C:** not DH. Look at Voxy's own vanilla ingest or vanilla chunk loading.
- **Stalls with no render-thread stack:** GC. Confirm with `-Xlog:gc`.
- **Candidate #4:** correlate the existing `/lss trace` `dh_status` rows (`ingestBacklog`, `INGEST_PRESSURE`, chunk x/z) with the movement.

---

## 8. Prior-finding verdicts

These consolidate reviewer verdicts. All Oct 8 fixes claimed as fixed were located, read and verified (F1, using final JUnit XML). None was falsely claimed.

### Oct 8

| ID | Verdict | Note |
| --- | --- | --- |
| V1 | **PARTIAL** | The confirmed path is fixed. The unconfirmed-trial residual (D-10) is open. |
| D1-R5 | FIXED | 0 reconnects on config-phase transfers |
| V2 | FIXED | — |
| R1-D1, R1-D2 | FIXED | — |
| R2-1 | FIXED | — |
| `take()` reservation | FIXED | — |
| R3-1 | FIXED (mechanism) | The premise is superseded by X-1 |
| R3-2/R3-3 | FIXED | — |
| R4-1 | FIXED for admission candidates; **PARTIAL** overall | D-8 |
| R4-2 | FIXED | — |
| R4-3 | FIXED as named | It introduced D-16 |
| R6-D1, R6-D2 | FIXED | — |
| R7-D1, R7-D2 | FIXED | — |
| R7-D3 | FIXED | Documented approximation |
| R8-1 | FIXED | — |
| R8-2 | FIXED, then superseded | The controls were removed |
| F1-1, F1-2, F1-4, F1-5, R2-2, F2-V3 | FIXED | F2-V3 is fixed as a test; its 120 s lifetime persists *by design* (A4 RampSim) |
| F1-3 | PARTIAL | Logs have no rc |
| F1-6 | PARTIAL / clarified | The blob was intent-to-add |
| M3 borrowed `Split` array | Sound | Two copies remain (efficiency only) |

### Oct 7

| ID | Verdict | Note |
| --- | --- | --- |
| S-1 … S-6 | FIXED | — |
| C-1 … C-4 | FIXED | C-1 cross-JVM protection is not shared, as documented; D-15 is a residual |
| C-5 | FIXED for contents | Order sensitivity is a new over-invalidation (D-6); light-dampening is still excluded |
| P-2 | FIXED as specified, **REGRESSED in effect** | D-1 |
| P-3 | FIXED | — |
| P-6 | **PARTIAL** | D-9 |
| P-7 | FIXED | Scope remains a critique (X-3) |
| P-8 | **PARTIAL** | D-13 |
| U-4 | **NOT FIXED** | `status()` still has no production consumer; D-11 |
| U-5 | FIXED for Java strings, **REGRESSED** via the YAML template | D-26 |
| K-1 | FIXED for the 23 R8 mutations; **PARTIAL** overall | D-27 |
| U-1, U-2, U-3 | FIXED | U-1's opted-in residual stands by design (X-10) |
| V-1 (dark floors) | FIXED for upward faces | Side faces: D-29 |
| Oct 7/8 process items (outreach, gates before code, watcher, kill criteria, load ratio) | **NOT DONE** | — |

---

## 9. Checked and sound (selected)

- **Lease release.** Exactly once on every probed path. All 54,896 budget-return checks pass. There is no allocation without a reservation and no integer overflow (A1).
- **I1 guard sites, I2 strict invalidation before any request, I3 single source, I4 drain before teardown.**
  - Identity binding of request ids, LevelInit, pushes, withdrawal and Cancel.
  - The LAN host cannot collide with DH (A2).
- **Same-connection Support rediscovery; transfer hook descriptors.** The transfer hook resolves to the correct mojmap and intermediary names on both loaders (A3, A10).
- **Cache integrity.** Atomic writes, `NOFOLLOW`, junction refusal, a hard quota, link-root and read-only fail-closed handling, and preserved inodes on repair (A5).
- **Column conversion.** Only complete columns are handed off, and UNKNOWN never becomes AIR. Upward faces show 0 light mismatches across all corpora. Official side faces match on sky light (A6, A7).
- **Native byte-identity.** Native wire, server, router, store, far-player, Xaero bridge and `LSSApi` are byte-identical to v0.16.0. All MC, Sodium and Voxy member references resolve on both loaders and all three Voxy jars (A10).
- **Menu.** 11 controls; locales 173/173/173. Tab geometry never hides the current tab across 17 window/scale combinations and every width from 300 to 1200. `lod.source` is session-timed (A8, F2).
- **Diagnostics.**
  - Trace-off hooks check `enabled()` before building any string.
  - The export privacy allowlist holds.
  - `/lss dhstatus` is fully removed (A9).
- **Evidence.**
  - Unit recount: 3,421 passed (common 1044, Fabric 1822 + 4 skipped tools, Paper 536, NeoForge 23).
  - All 51 tasks executed.
  - Jar hashes are bound across evidence, the build copy and Prism.
  - Manifests (400 + 1023 entries) show 0 mismatches.
  - `review-scope.json` set-equals live `git status` (F1).

---

## 10. What was run versus inspected

**Run** (Java 21 under `nice`, against the read-only candidate classpaths in `/tmp/lss-dh-menu-20261009-r1`; all outputs under the scratchpad's `oct9/`):
- **A1:** a corpus replay, mutation and ownership harness; capacity sweeps; span-limit and gapped-assembly probes.
- **A2:** the pr-R5/R1/O3 harnesses; `ResetHarness`, `CtorTime`, `H2Interrupt`.
- **A3:** R5/O4/CrossFamily/F2 harnesses; `A3Harness` H1–H5; javap of vanilla transfer and connect paths.
- **A4:** `A4Harness` modes H, U, R, D and S; `RampSim`; `HashProbe`; one JFR profile, afterwards deleted.
- **A5:** R6/O2 cache harnesses (scaled), including two-JVM runs; `A5New` N1–N8; `A5Session`; lock-busy deletion.
- **A6:** `A6Harness` E1, E1b, E3, E4; a bytecode scan of the DH 3.3.3 jar.
- **A7:** the `Sweep5` fringe matrix; the `R8Air` converter-vs-DH-oracle over five corpora; the `Snap` microbenchmark.
- **A8:** javap and constant greps of the menu classes; locale alignment; the glyph-width geometry simulation; a downgrade probe against the published v0.16.0 reader.
- **A9:** the pr-R4 harness; R4b and R4c; javap of `DhClientTrace`.
- **A10:** per-entry diffs of six jars against published 0.16.0; classfile reference resolution against the pinned MC, NeoForge, Sodium and Voxy jars; a 37-case `release_check` mutation sandbox; `--selftest`.
- **F1:** a recount of 300 JUnit XML files; hashing of jars, manifests and screenshots; a git intent-to-add experiment in a scratch repo; read-only `catalog.py`/`reference.py --check`; `stop-server.py --status-json` (status only).
- **F2:** `F2Story` (stories A×2 and B); the `OpenCost` benchmark; a re-run of A4's probes; javap of `Minecraft.runTick` and Fabric API networking.
- **D1/D2/F3:** read-only `wc`/`grep`/`git diff --numstat`/`du`, plus targeted re-checks of the fence, cache, main-thread hop and registration claims.
- **Me (consolidation):** I re-read `DhClientSession.java:426-444` (fence), `:1030-1040` (push stamp), `:1404-1410` (fence honoured), `:948-966` (whole-bucket purge), `DhColumnConverter.java:98-111` (ordered identity) and `DhPosition.java`; and I checked `git status` (228 entries) and HEAD `6677fe93`.

**Not run:**
- **Gradle.** The manual server holds the shared harness lock.
- **Any live session.** No live client, server, Prism, Windows or real Voxy session.
- **The spark measurement.** It needs the user's approval and the user's client.

**Inspected only:** everything not listed above as run, including all review and disposition history, the plan with all amendments, the upstream DH, DH Support and Voxy sources under `research/`, the README and the manual guide.

---

## 11. Examined areas, evidence reproduced, untested claims, remaining confidence limits

**Examined areas:**
- **Codec, assembly, budget and leases.**
- **Session lifecycle and recovery:** source and connection takeover; discovery, legacy trial, transfers and both transport twins; reset and LAN host.
- **Acquisition and data paths:** scheduling, pacing, ramp, admission and recovery; DH source cache, Voxy readback and identity; pushes, fences, sync on load and receive OFF/resume.
- **Rendering inputs:** conversion fidelity and light; the fringe pump.
- **User-facing surfaces:** the menu on both Sodium generations and both loaders; YAML and downgrade; diagnostics, status, trace and export privacy.
- **Packaging:** the release gate, native byte-identity and brand.
- **Evidence integrity.**
- **The movement pause.**
- **Architecture, product, process and upstream decisions.**
- **The trajectory of the review process itself.**

**Evidence reproduced (harness, against the candidate classes):**
- D-1 (A6 E1/E1b; F2 story A)
- D-2 (A4 D 3/3 plus a natural occurrence at 30 FPS; A1 Probe5)
- D-3 (A4 R)
- D-4 (A4 and F2, 25–29 ms; JFR)
- D-5 (A5, 6/6)
- D-7 (A2 `ResetHarness`)
- D-8, D-16, D-17 (A9)
- D-10, D-19, D-20 (A3)
- D-11 (F2 story B)
- D-12, D-13, D-14 (A1)
- D-15 (A5, two JVMs)
- D-25 (A8, javap)
- D-26, D-27 (A10)
- The X-1 throughput table (A4); the X-4 reconnect cost (A3 H1, 5/5, plus the Oct 8 live log); the X-10 downgrade matrix (A8 against the real v0.16.0 reader).
- The 3,421 test recount (F1).

**Untested claims:**
- D-6 is by inspection; there is no permuted-registry run.
- D-9's restore step is by inspection.
- D-21 and D-22 are by inspection.
- The real-world frequency of D-2 and D-3 depends on the official push rate during flight, which is unmeasured.
- The cause of the movement pause is unconfirmed (§7).
- The throughput gain from batched handoff is modelled, not built.
- The reach of X-6's channel advertisement into mod-policing servers, and its effect on real DH LAN guests, are unverified.
- Demand, the DH team's stance and the server-load ratio are all unknown.
- Paste or structural similarity against GPL upstream is not audited.

**Remaining confidence limits:**
- **Isolation:** every reproduction ran in isolated harnesses using the production classes through the test fixture, not in a live client. Real Voxy, real frame timing and real servers could shift magnitudes.
- **FPS-bound figures:** these model one main-thread task per frame. That is confirmed in bytecode, but a fast worker re-enqueueing within a single drain could do better.
- **Prior live evidence:** the NeoForge live evidence ran Voxy through Connector, not the native fork.
- **Decision critique:** sections 5 and 6 are judgement. The reviewers agree strongly on X-1 to X-7 and X-16, while X-8, X-10, X-11 and X-15 are genuinely debatable and are the user's call.
- **Completeness:** this review does not mark the plan, any phase or any release complete.
