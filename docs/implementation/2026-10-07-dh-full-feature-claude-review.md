# Claude Code review: complete DH compatibility feature (r5 candidate)

**Date:** 2026-10-07.
**Briefing:** `2026-10-07-dh-full-feature-claude-review-briefing.md`.
**Scope:** the whole cumulative feature, from baseline `6677fe93` (v0.16.0+mc1.21.1)
through the r5 development candidate `0.16.0-dh-p4.dev20261007.5`:
- NeoForge `f165513d…`
- Fabric `27fa7453…`
- exact build copy `/tmp/lss-dh-p4-20261007-r5`

**Reviewers:** ten, run in three batches to stay under rate limits.

| Batch | Reviewer | Area |
| --- | --- | --- |
| 1 | O1 | untrusted wire input |
| 1 | O3 | lifecycle, ownership and invariants I1–I4 |
| 1 | O5 | pushes, scheduler and sync |
| 1 | F1 | evidence honesty |
| 2 | O2 | persistent cache |
| 2 | O4 | automatic detection and retry |
| 2 | O6 | conversion, Voxy and the vanilla fringe |
| 3 | O7 | settings, UI and status |
| 3 | O8 | packaging and native regressions |
| 3 | F2 | performance, operations and the integrated I1 verdict |

The coordinator verified identity and spot-checked the key mechanisms. Nothing was
edited, staged, installed, deployed or published. The live manual server, the
Prism instance and personal worlds were left untouched. Earlier reports are
preserved.

## 1. Integrated conclusion

The feature is a serious, mostly well-engineered piece of work.

**What holds:**
- **Hostile input:** the codec bounds it carefully. Allocation is reserved before
  use, compression is capped, and the three narrow AIR exceptions only shrink spans.
- **Isolation:** DH output reaches only the narrow Voxy sink.
- **Native wire:** the native surface (payloads, `LSSConstants`, server, far-player
  and Xaero classes) is byte-identical to the published 0.16.0.
- **Packaging:** clean. XZ is relocated with its license, and no stubs, fixtures or
  DH classes ship.
- **Evidence:** honest to an unusual degree. Recounts reproduce 3345 passes and the
  13,181,696 comparisons, first failures are retained, and r3 performance is not
  relabelled as r5.
- **Prior fixes:** most of the October 5/6 findings are genuinely fixed and pinned.

**Release readiness remains false, for four independent reasons:**

1. **The known I1 gate is a real blocker.** It is not new, and not merely missing
   coverage. Voxy commits last-writer-wins with no epoch or fence, and nothing can
   revoke an enqueued write. Five production write paths share this race (§4).
   `release_blocker=true` is correct.
2. **New demonstrated defects that are user-visible** (§2):
   - endless reconnect loops on transfer-packet networks;
   - DH lost or looping after a same-connection backend switch;
   - downgrading to 0.16.0 silently disables LSS;
   - floors under overhangs and around light sources render too dark;
   - the legacy Sodium page has unclickable or off-screen controls;
   - cross-dimension and cross-server cache eviction is unfair;
   - several "stops DH for the session" overreactions.
3. **Performance at real range is unproven.** A coded 500 ms request floor implies
   roughly 10× slower full-radius fills than the official client. The headline
   "about 2×" figure is a 9-tile artifact (§2, P-1).
4. **Qualification gaps:**
   - no Fabric performance cell;
   - no cross-connection warm-reuse cell at range;
   - no live proxy or transfer cells;
   - a single 64×64 content tile per cell;
   - no uncapped frame measurements;
   - no fuzzing of the decoder (see O1's note in §6).

The candidate is suitable for **continued supervised NeoForge manual play** at the
installed settings. It should not be handed to general testers who use proxies,
transfer-packet networks or the legacy Sodium page, or who might downgrade.

## 2. Verified defects (demonstrated by harness or by direct code/bytecode evidence)

Severity is ordered within each group. "Repro" names the scratch harness under the
session scratchpad (`full-O*/`, `full-F*/`). Every harness ran against the exact r5
compiled classes.

### Session, detection and reconnect

**S-1 HIGH: endless automatic-reconnect loop on transfer-packet networks (O4, harness S1).**
- **Where:** `xplat/.../DhClientSession.java:128-131, 143-154`. The retry target is
  `ServerAddress.parseString(retry.server.ip)` (`:153`). The allowance resets on
  every fresh join (`:73-76`).
- **Root cause:** in 1.21.1, `handleTransfer` reconnects with the original
  `ServerData` (confirmed with javap). After a transfer, `getCurrentServer().ip` is
  therefore still the hub.
- **Trigger:** a hub with no DH transfers the player to a backend whose DH channel
  is registered but silent on protocol 16. That is stock 3.2.0-b, exactly the case
  the trial exists for.
- **Consequence:** about 16 s later the client reconnects to the *hub*. The hub
  transfers again, which arms a fresh allowance, and this loops (4 cycles in 4
  attempts). The user is yanked back to the hub roughly every 17 s with no in-game
  explanation, and cookies are dropped.
- **Fix:** record the connection's actual target. Refuse an automatic reconnect when
  that target differs from `ServerData`, or when the connection was set up by a
  transfer. Remember a spent or silent trial per address for a cooldown; this only
  suppresses reconnects and never selects a decoder.
- **Regression:** replay S1 and assert at most one reconnect in total.

**S-2 MEDIUM: a same-connection backend switch keeps a stale protocol family (O3 + O4, harnesses).**
- **Where:** `DhClientSession.java:84-86`. `probeProfile()` prefers
  `confirmedProfiles[connection]` over the new discovery, and the Support branch
  (`:834-838`) never clears it. The family is keyed by the physical `Connection`,
  which survives proxy reconfiguration.
- **Consequences:**
  - **official → Support:** a ~1 s renegotiation loop for the whole connection. Two
    control frames are sent per round, there is no terrain and nothing is logged.
  - **Support → official 16:** the SUPPORT_15 session goes silent and DH is lost.
- **Fix:** key the confirmed/discovered family by the `ClientPacketListener` (it
  survives dimension changes and respawns, but not reconfiguration). Restart
  discovery at 16 on a same-connection `onJoin`. Keep `initialPlay=false` so
  reconnects stay blocked.
- **Regression:** confirm v16 → same-connection `onJoin` → Support rejection, then
  the next session must be SUPPORT_15 and accept a v15 config.

**S-3 MEDIUM: the guarded trial accepts an ambiguous v15 config under a guessed family (O4, harness S3 + CrossFamily).**
- **Where:** `:87-89` (on the trial, the hint OFFICIAL_15 becomes the profile), then
  `:848` (`advertised()==null` falls through to a normal decode) and `:857`
  (confirmed).
- **Evidence:** DH Support's captured config and LevelInit decode under both v15
  families. The trial confirms them as OFFICIAL_15. No terrain was accepted, but only
  because the terrain decoders happened to fail closed on these captures. No
  Zstd or uncompressed Support capture exists to show the same holds there.
- **Claim falsified:** "remembered hints cannot authorize a guessed decoder."
- **Fix:** require positive family evidence before confirming on a hint-chosen epoch.
- **Regression:** feed the Support captures on the trial and expect no confirmation.

**S-4 LOW-MEDIUM: rejection-triggered reconnects have no minimum delay (O4, S4).** A
reconnect fires about 1.06 s after login. Paper's 4000 ms throttle exempts only the
literal `127.0.0.1`, so this would be throttled. It is reachable only for peers whose
rejection actually arrives (stock 1.21.1 servers stay silent). **Fix:** wait at
least 4–5 s after the original login.

**S-5 LOW: DH state falls through to `NEED_VOXY`** whenever no session object exists
(`DhClientSession.java:102-107`): between sessions, after a reset, and during each
S-2 round (O3). **Fix:** derive the state from `DhVoxyBridge.available()` and add a
between-sessions state.

**S-6 LOW (I1 recording): vanilla chunks are not recorded while DH isn't selected.**
`vanillaLoaded` returns early at `:183`. If the native channel is withdrawn
mid-connection, DH becomes selected, and columns vanilla held earlier become
DH-eligible (O3, harness H4). **Fix:** record whenever `fallbackEnabled()` is true.

### Pushes and scheduler (O5)

**P-1 MEDIUM (performance claim): a 500 ms per-request floor, not concurrency, dominates warm acquisition (F2; coordinator confirmed `DhClientSession.java:596-601`).**
- **Mechanism:** `interval=max(500 ms, …)`. It is raised to 1 s on Support and 2 s
  when the backlog probe fails. The scan cadence is also 500 ms. `pending` is only
  released after decode, conversion, Voxy readback polling (up to 2 s) and the cache
  fsync.
- **Measured windows:**
  - warm LSS server windows of 5.25–6.23 s ≈ 9 × 0.5 s, i.e. interval-bound;
  - official 0.47–0.51 s ≈ 218 KB at the server's 500 KiB/s cap, i.e. bandwidth-bound.
- **Derived estimate (not measured), warm fill at range 128 (4225 tiles):**

  | Client | Lower bound |
  | --- | --- |
  | official_16 profile | ≥ 2112 s |
  | Support profile | ≥ 4225 s |
  | backlog probe unavailable | ≥ 8450 s |
  | official client | ~200 s |

- **Conclusion:** "about 2× slower" is a 9-tile artifact.
- **Fix:** name the floor in the performance text, report tiles/s, and measure one
  larger-area warm cell. Revisit the floor as a deliberate tradeoff.

**P-2 MEDIUM: pressure recovery drops the ordering fences while wiping receipts (inspection).**
- **Where:** `recoverPushPressure` (`:655`) closes every fence, then calls
  `invalidateAll` (`:661`).
- **Consequence:** a database reply older than an already-applied push bypasses both
  `:921` and `fence.protects`. It re-ingests over the pushed columns and is admitted
  to the cache (`fence==null` at `:940`). This contradicts
  `anAcceptedTileStaysHeldAfterItsLiveEditAndAnOlderNewRequestCannotReplacePushedColumns`.
- **Fix:** keep the fences, which cost at most 4 MiB.
- **Regression:** push at T2 → recovery → reply at T1, and assert no re-ingest.

**P-3 LOW-MEDIUM: a push can cancel a request before it is registered (harness, Part A).**
- **Where:** the push cancels at `:705-714` before the request task's `expect` runs
  (`:814-818`).
- **Consequence:** an orphaned owner survives until `expire()`, which only runs on
  an idle queue. At 8 owners, `expect` throws and DH stops for the connection. This
  happens within 18–65 s under sustained pushes to a pending tile.
- **Fix:** queue the cancel behind the request task, or make `expect` refuse ids no
  longer in `pending`.

**P-4 LOW-MEDIUM: transient causes permanently park tiles (harness, Part B).** After
4 `cooldown()` calls the tile is never due again on this connection. The causes
include:
- rate-limited refusals;
- main-thread stalls;
- busy cache readback;
- "Voxy destination changed";
- reply older than a push;
- all columns now vanilla.

Also, a readback timeout *after* successful delivery is converted into a cooldown
(`:954/958`; O2-4 is the same). **Fix:** reserve strikes for real sink rejection and
permanent refusals; give transient causes a non-exhausting backoff.

**P-5 LOW: one shared `lastRequest` starves generation when the server rate is below 2/s (harness, Part C).** With rate 1, generation waits behind every sync request: 145 s for 289 tiles, about 35 min at range 128. **Fix:** separate timestamps for sync and generation.

**P-6 LOW-MEDIUM: a dropped push is masked by a later push (harness, Part E).**
- **Where:** `:694` drops a push with no state change. `:733` then advances the
  stamp, so load checks report unchanged. The likely trigger is the tile-centre
  distance filter.
- **Fix:** on a range/border drop for a held tile, invalidate the receipt and cache
  entry and raise the fence.

**P-7 LOW-MEDIUM (inspection): any dropped terrain frame purges the whole source bucket and all receipts.** This happens even with live updates off, because Split frames count as terrain (`:202-206`). The deferrals counter is shared with the recovery-exit test (`:663/:698`). **Fix:** skip the purge when `!updatesAllowed()` and use separate counters.

**P-8 MEDIUM (O1, inspection): resource-limit errors from assembly or decode still end the session.** The causes are: the 256 MiB budget, a 9th incomplete buffer, or more than 4096 fragments. `receiveUpdate` and the response path tolerate only UNOWNED/EXPIRED(/UNSUPPORTED), which contradicts the briefing's "pressure must release terrain … without stopping a healthy adapter". **Fix:** route RESOURCE_LIMIT through `pushRecovery`/`deferUpdate`.

### Persistent cache (O2)

**C-1 MEDIUM: eviction is not a shared LRU; other buckets are wiped first (harness S3).**
- **Where:** `DhTerrainCache.java:229-233` sorts other directories ahead of `usedAt`.
- **Evidence:** 8000 stale Overworld tiles plus 121 recent Nether tiles. After two
  Overworld trips the Nether bucket is at 0 while 7950 stale Overworld tiles
  survive. Every Nether return then restores 0 and downloads 121, re-exposing the
  H-1 overwrite there.
- **Fix:** keep the protected set shared across instances, checked by every `trim`,
  and order globally by `usedAt`.

**C-2 MEDIUM: a symlinked or junctioned cache root stops DH for the session (harness `O2LinkHarness`).**
- **Where:** `checkDirectories` refuses the root on every operation, but the session
  keeps the handle (`:406`). The pre-delivery remove then calls
  `fail("Unable to invalidate superseded DH cache")` (`:925-934`), and likewise at
  `:465/:573/:667/:702`.
- **Contrast:** native LSS follows the link and keeps working.
- **Fix:** if the root or bucket is refused, set `terrainCache=null` and continue
  without a cache.

**C-3 MEDIUM-LOW: any failure to delete one tile stops DH (harness `O2IoHarness`).**
Triggers are a Windows sharing lock, a read-only bucket, or a foreign directory named
like a tile. A stale source can never overwrite newer Voxy data (unchanged replies
compare timestamps; preserve-on-mismatch keeps non-default columns), so stopping the
session is over-conservative. **Fix:** mark the position untrusted in memory and drop
the handle on persistent failure.

**C-4 LOW: an admission-readback timeout cools down an already accepted tile** (`:984/996` → `:954`). The fix is to catch `TimeoutException` in `verifyCacheDestination`. The quota is also per JVM (harness `O2TwoJvm`: two clients sharing one root admit 200 files against a quota of 100); this is low impact.

**C-5 MEDIUM-LOW (inspection): conversion inputs outside `CONVERSION_VERSION`.** The
user setting `compatibility.block_fallbacks` and the installed-mod registry both
change conversion output (`ClientIdentityResolver.java:57-59` → `DhColumnConverter.java:63`).
Cached areas then mismatch as non-default and are preserved, so the setting never
takes effect without `/lss reset`. **Fix:** include both in the identity or digest.

### Conversion and fringe (O6)

**V-1 MEDIUM-LOW (visual): floors under overhangs and near light sources come out too dark (harness `Air.java`).**
- **Where:** `DhColumnConverter.java:49-50, 71-74`, mirrored in `matchesCache` (`:101`).
- **Mechanism:** an air run is lit with the light DH recorded *above* the run. Voxy
  shades a floor face from the bottom air voxel. DH's own renderer
  (`ColumnBox.addQuadUp`, DH 3.3.3) uses the floor datapoint's own light.

  | Capture | Light | Floor faces darker than DH | Worst |
  | --- | --- | --- | --- |
  | Support meadow | sky | 143 / 5918 | up to 14 levels |
  | Support meadow | block | 203 | up to 6 levels |
  | Official ocean | block | 2076 / 8485 | up to 14 levels |

  Torch-lit ground under open sky follows from the same mechanism but has no corpus
  sample.
- **Why the oracle misses it:** `ContentOracle.java:72-73` re-derives light with the
  converter's own rule.
- **Fix:** write the lower span's recorded light into the upper span's bottom voxel
  (the same change in `matchesCache`) and bump `CONVERSION_VERSION` to 3.
- **Regression:** the meadow air-over-floor pairs, plus a synthetic torch-lit case.
  The fluid pairs already match and are unchanged.

**V-2 LOW: the first fringe submit usually fails because light isn't applied yet (harness `Sweep3`).** 1.21.1 queues chunk light into `renderLevel`, while the pump runs in the same tick, so Voxy returns false. That wastes the credit and parks the chunk for 1 s: 18% of attempts at RD12 / 30 b/s. **Fix:** check `isLightUpdateQueueEmpty()` or defer one tick.

**V-3 (prior D-10, partly closed).** All directions now hold 1–5% missing at RD12/RD32 sprint. At RD32 and 30 b/s the pump sits at its cap (about 136/s), and about 53% of the band is never refreshed while exposed (the leading side 93–95%). `VoxyVanillaCoverageTest` can't fail at that speed.

### Settings, UI and status (O7, O8)

**U-1 MEDIUM (O8, harness): downgrading to 0.16.0 silently disables LSS.**
- **Where:** `common/src/main/resources/dev/vox/lss/settings/client.yaml:11-14`
  always writes `lod.source` and `lod.dh.*`, while `config_version` stays at 1
  (coordinator confirmed).
- **Consequence:** the published 0.16.0 parser throws "Unknown settings path:
  lod.source" and falls back to inactive settings: receive off and far players off,
  with no hint of a version mismatch. This was reproduced with the published nested
  common jar on both the LSS and VSS templates.
- **Fix:** write those keys only when they differ from the default, and document the
  opt-in case.
- **Regression:** parse the shipped default template with the 0.16.0 descriptor set.

**U-2 MEDIUM-LOW: legacy Sodium 0.6.13 donation-button relocation covers General/Quality options (O7, geometry replica from javap).**
- **Where:** `LegacySodiumPage.java:281-306`. At 1280×720 Auto (426×240), which is
  the r5 rig size, the buttons move to y 210–230, x 6–130.
- **Consequence:** they draw over "Attack Indicator"/"Autosave Indicator", and the
  options take the clicks, so the donate and dismiss buttons are unclickable.
- **Why the r5 observer missed it:** it checks only y≠6, on the 3-option DH page.
- **Fix:** relocate only if no `children()` rectangle intersects; otherwise use the
  hide fallback. Observer regression on every page at 426×240 and 341×256.

**U-3 LOW-MEDIUM: the third legacy tab pushes "LSS Players" off-screen at 341 and 320 px widths** (`:259-270`; tab positions 299–345 and 351–424). Share My Position becomes unreachable on 1024×768 and 1280×1024 at Auto scale, where it was previously partly visible. Confirm by running the observer at 341×256.

**U-4 LOW-MEDIUM (status honesty): `DhClientSession.status()`/`describe()` are never called in production** (`:80, :373`; only test-fixture probes call them). The native-suppressed, retry/world-changed reasons and the readback-confirmed reuse counters are therefore never shown. The disposition's "status covers native suppression" holds only for fixtures. **Fix:** add a sanitized reason enum and the readback counters to `DhStatusSnapshot` and render them.

**U-5 LOW: smaller status and UI issues.**
- The localized DH status omits the pressure-drop line (`ClientStatusText.java:23-34`).
- `fitDonationButtons` consumes the shared `warnOnce` latch.
- The Live-updates tooltip still says "Loaded vanilla terrain remains authoritative" (all locales).
- The Use-DH tooltip does not say "reconnect".
- `lss.settings.lod.source` is dead and its zh wording is stale.
- The r5 legacy cell's "Live updates false" Apply was a no-op, because `prepare.py:17` had already set it false.
- Brand leaks: "LSS's DH source" and "Native LSS…" are hardcoded in VSS jars.
- `EnumSpec`/`bindEnum` have no live user but ship.

### Packaging (O8)

**K-1 LOW-MEDIUM: `release_check.py` misses mutations.** Mutation runs on both twins
passed "OK" while these were present:
- stock `org/tukaani` references outside `DhTerrainDecoder`, or inside
  `META-INF/versions/**` (NeoForge is Multi-Release, and Voxy ships stock XZ, so a
  leak would silently cross-bind);
- `privatep4/`, `com/seibel/` or Fabric `net/caffeinemc/` content;
- a missing XZ paragraph in `THIRD-PARTY-NOTICES`.

**Fix:** scan every class at every archive level, extend the forbidden lists, and
check the notice. The r5 manifests also don't bind `release_check.py` or the
packaging tools.

### Evidence (F1, F2)

**E-1 MEDIUM: the range-128 pressure cell is mislabelled and cannot observe warm reuse.**
- The run's own cache shows the first-visit records untouched, with original mtimes.
  Receipts are per connection per dimension and survive the dimension trip, so the
  return extended the frontier instead.
- The "reacquisition_after_global_eviction" label (`CachePressure.java:265-269`) is
  refuted, and the relaxed premise means the cell passes whether or not a hit occurs.
- **Fix:** relabel, and add a genuine cross-connection range-128 rejoin cell.

**E-2 MEDIUM (perf claims): reported comparisons differ in scope between arms.**
- **Server CPU:** the warm ratio of 2.1–2.6× is mostly window length. Adjusted for
  ambient tick CPU it is about 1.5× (0.75 vs 1.13 CPU-s).
- **Memory:** commit memory is omitted. LSS 3.69 GiB vs official 2.49 GiB median
  (+1.2 GiB) at identical `-Xmx3G`.
- **Frame timing:** frames sit at the 120 FPS cap (p50 8.337 ms in all 12 runs), and
  the "GPU elapsed" bracket includes the limiter wait, so the frame column carries no
  renderer comparison.
- **Idle confirmation:** it is a 20-minute timer flag, not a measurement, and warm
  pair 1 is split.
- **Official arm:** it injects all nine requests directly at t0, which is best case.
- **Acquisition clocks:** they start and stop at different points per arm.
- **Raw capture:** synchronous per-frame writes are active in both arms inside the
  timed windows (symmetric, but undisclosed).
- **NTFS p99:** dominated by post-quota eviction writes (about 33 ms per write at
  quota in r5; pre-quota p99 ≈ 5.6 ms).

**E-3 LOW: smaller evidence issues.**
- The "independent raw source" oracle receives the production-decoded tile. It is
  independent of conversion, `matchesCache` and Voxy, but shares the decoder.
- The manual guide still says "1,200 independent comparisons" and "one reconnect"
  for Support.
- The "eight Python tests" have no retained run log. F2 ran them: 8/8 pass, and
  `owned-process` grace passes 2/2.
- The Support-dummy palette claim had no artifact. F1 replayed it and it holds.
- A fixture failure stack was not retained.
- The `dh-support-0.14.0-ocean.dto` corpus file is an orphan.
- Raw-frame SHA256s are not asserted by tests (F1 verified all 34 files).
- The `.voxy` cleanup receipts under three user Prism instances record no
  inactive-process check.
- `SlowConfigMixin.java` (untracked) mixes into `com.seibel…`. Committing it would
  conflict with plan §8.

## 3. Suspected defects (need reproduction)

**Transport and lifecycle**
- **NeoForge sweep vs handler:** if the NeoForge 5 s sweep closes a frame before its
  handler runs, the frame is silently lost and the tile corrupts or never completes
  (O1).
- **Fabric frames that never reach the worker:** Fabric frames whose callback never
  runs are never aged out, so enough of them reach the 32-frame cap (O1).
- **I4 drain gated on `selected()`:** after a late native offer, `/lss reset` skips
  `drainForReset` while a retiring worker is still alive. No actual harm was found
  (writes are main-thread and gated, and readers hold engine refs), so this violates
  "unsafe retirement must refuse" in form only (O3).
- **Strict native sweep on the main thread:** it runs on every new DH session, up to
  the 30 s IO wait (O3).

**Producer-dependent push behaviour**
- Whether official DH pushes for tiles it is still generating, which would cause a
  Cancel loop.
- Whether DH database stamps lag push stamps, which would park tiles via `:921`.
- Whether the update-distance filter works by chunk or by tile centre (O5).

**Readback and the fringe pump**
- `verifyCacheDestination` waits up to 2 s per tile; under Voxy backlog tiles are
  declined and the worker stalls (O2/F2).
- `readColumn` copies each 256 KiB section four times (up to about 48 MiB per tile,
  O6).
- The fringe pump stalls behind Voxy's backlog of 128 or more during a native LSS
  backfill (O6).

**Settings and downgrade**
- Downgrading when the template omits the keys (U-1 fix) still needs the opt-in
  user documented.
- Legacy Iris/Sodium Extra footer collisions (O7).
- Use DH on against a native Paper server during the join window: one redundant
  native handshake (O8/O3).

## 4. The known I1 / P2 / P4 gate: integrated assessment (F2, O3)

**What the counterexample shows.** `RaceProbe` (attempts `dncofn81`/`83xgnag5`) held
an owned red DH section before Voxy's `insertUpdate` for 4.5–4.7 s. Storage then read
the newer yellow vanilla value, and later the red DH write. Voxy's commit is
last-writer-wins with no epoch. The natural window is Voxy's queue latency: LSS
stops requesting only above a 256-section backlog, and one 384-height tile is 384
sections, with up to two tiles in flight. A load or teleport into a tile about one
second after its enqueue is therefore plausible at the frontier. Frequency is
unmeasured.

**Production write paths that share the race.** The main-thread checks at `:1031`
and `:1072` exclude only currently held or loaded columns, and nothing cancels after
`rawIngest`:
1. a new full payload (no readback compare);
2. a changed-timestamp replacement (the source is removed first, then path 1);
3. cache restore of fully-default columns;
4. partial and live pushes;
5. on the newer-vanilla side, Voxy's own load/unload ingest and the fringe pump,
   all entering the same deque unfenced.

Additionally, reconnect clears the held set, so columns vanilla held in a previous
session become eligible again. That is the documented §5.4 tradeoff.

**What else stays open.** O3 found a new trigger of the same gate: Voxy's
`VoxelIngestService.shutdown()` drops queued writes. An unchanged sync reply then
re-confirms a receipt for data that was never stored, leaving a hole until
reconnect. Cross-connection paths recover via actual readback.

**Verdict.**
- The ordering gap is a **true release blocker** for any build claiming vanilla
  authority. The right boundary is a Voxy API with per-write ownership and epoch,
  atomic cancellation and typed completion. No upstream work has happened.
- Missing persistence receipts are a qualification gap *as long as nothing claims
  them*. The status strings ("completion and persistence remain unacknowledged") and
  the docs are honest. The Live-updates tooltip line (U-5) is the one remaining
  overclaim.
- These are deliberate tradeoffs and should be presented as such, with consequences
  disclosed:
  - the 500 ms floor;
  - one generation request in flight;
  - approximations over unloaded earlier terrain.

## 5. Prior findings: verdicts on the integrated system

| Item | Verdict |
| --- | --- |
| Oct 6 D-1 (Fabric `canSend`) | **Fixed.** Announce, the trial deadline and retry execution all re-check it. Play-phase register semantics not tested live. |
| Oct 6 D-2 / Oct 5 H-2 (full bucket freezes) | **Fixed for a single bucket** (S2 harness: sessions 3–4 restore 121/121). **Regressed across buckets** (C-1). |
| Oct 6 D-3 (unreachable rejection) | Closed on documentation. S-4 applies to the defensive path. |
| Oct 6 D-4 (quiet probe ignores arrivals) | **Fixed** (15 s + 3 idle ticks, no queued/captured/decoding work, `wireBytes` blocks it). Tiny race at the exact deadline. |
| Oct 6 D-5 (dimension change re-arms) | **Fixed for reconnects.** Carrying the family across backend switches is now S-2/S-3. |
| Oct 6 D-6 (cache before delivery) | **Fixed.** Admission comes after accepted delivery plus a matching readback. Residuals: readback is in-memory not persisted; C-4. |
| Oct 6 D-7 (converter version) | **Fixed for converter code** (in the key digest and header; keeping v2 at r5 is justified). Not for settings/registry inputs (C-5). |
| Oct 6 D-8 / D-9 (push timeout and burst) | **Fixed as claimed.** Qualified by P-2, P-4, P-7 and P-8. |
| Oct 6 D-10 (fringe vs movement) | **Partly closed** (V-3). |
| Oct 6 D-11 (supervisor SIGKILL) | **Fixed.** Grace clamped to 3–120 s, manual supervisor passes 90 s, test 2/2 passes. |
| Oct 6 D-12 (assorted) | Latch fixed (LinkageError only). Tooltip line and dead keys still open (U-5). |
| Oct 5 H-1 (overwrite of refined terrain) | **Partial by design.** Every uncached, evicted, declined, pushed or readback-deferred tile still overwrites through the full path. C-1 enlarges that set. |
| Oct 5 L-1 / fluid light | **Holds:** 7311/7311 official and 4374/4374 Support fluid pairs. It was the fluid instance of the general face-light issue V-1. |
| Oct 5 L-3 (seed 0, temp files) | Fixed. |

## 6. What was run vs inspected, and the review's limits

**Coordinator, run:**
- `git diff --check`: clean.
- All 434 `source-manifest.json` and 597 `test-source-manifest.json` entries
  re-hashed against the checkout: 0 mismatches. The manifest SHA256s `e18cccb4…`
  and `83c2e23f…` match the briefing.
- Both r5 jar SHA256s confirmed.
- `stop-server.py --status-json`: the live manual server pid 414965 is ready and
  owns the harness lock.
- Code confirmation of P-1, U-1 and the S-2 precedence.

**Reviewers, run (scratch only, against r5 compiled classes):**
- Session-fixture harnesses: O3 H1–H4; O4 S1–S4 and CrossFamily.
- Policy and assembly harnesses: O5 Parts A–E plus leak and queue models.
- Cache harnesses: O2 Cache, Io, Link and TwoJvm.
- Oracles: O6 `Air` and `Light`.
- Fringe-pump replica: O6 `Sweep3`.
- Legacy-layout geometry replica from javap (O7).
- Jar diffs against the published v0.16.0 GitHub/Modrinth assets and constant-pool
  scans (O8).
- `release_check.py --selftest` (126 OK) and `--version` against the r5 jars (OK),
  plus the K-1 mutation runs (O8).
- `tools.packaging.test_sqlite_capsule` (9 OK) (O8).
- Python suites: `dh-p4` 8/8 and `owned-process` grace 2/2 (F2).
- javap of Voxy, MC 1.21.1 client classes, DH 3.3.3, Sodium 0.6.13/0.7.3/0.8.x and
  Paper's handshake listener.

**Not run:**
- **The normal Gradle gauntlet.** The harness lock is legitimately held by the live
  manual server, and the briefing forbids bypassing it. This review therefore makes
  **no independent build/test green claim** for r5. The recorded 3345 / 78 / 8 were
  re-counted from XML and logs only (F1).
- No live clients or servers.
- No Windows run.
- No decoder fuzzing. O1's attempt to craft hostile inputs was cut short by a safety
  check, so the decoder review is code-reading only. It found no crash or bypass, but
  that is not fuzz evidence.

## 7. Remaining gates and their status

| Gate | Classification |
| --- | --- |
| I1 commit ordering (Voxy epoch/fence/cancel API) | **Release blocker** |
| S-1 transfer reconnect loop; U-1 downgrade disable | **Release blockers** (user-facing breakage) |
| S-2/S-3 backend-switch family; V-1 floor light; U-2/U-3 legacy layout; C-1/C-2/C-3 cache; P-2/P-8 pressure handling | Must-fix before a wider test |
| Full-radius performance (P-1), Fabric perf arm, uncapped frames, measured idleness | Qualification gaps (the 500 ms floor is a deliberate tradeoff to disclose) |
| Cross-connection warm reuse at range (E-1), proxies/transfers/Realms live cells, Support Zstd/raw under the wrong family, multi-tile content oracle, decoder fuzzing, NeoForge modern 0.8.13 and Fabric legacy 0.6.13 menus | Qualification gaps |
| Per-write completion/persistence receipts | Qualification gap while unclaimed |
| Independent Gradle run on r5 | Owed; needs a coordinated stop of the manual server |
| Other MC lines, upstream outreach, release decision | Pending by plan |
