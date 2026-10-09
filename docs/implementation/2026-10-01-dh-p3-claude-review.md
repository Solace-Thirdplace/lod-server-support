# Claude Code review: DH refresh, settings and diagnostics (P3)

Date: 2026-10-01. This review follows `2026-10-01-dh-p3-claude-review-briefing.md`.
Four reviewers ran in parallel: Opus 1 (refresh and receipts), Opus 2 (Sodium
settings, diagnostics, privacy), Opus 3 (integration, transports, conversion,
packaging) and Fable (evidence audit, official_16 against DH 3.3.3). The
coordinator ran its own light checks.

The review was read-only. Nothing in the checkout was edited, committed or
installed, and no upstream contact was made. The running manual server on
`[::1]:25585` and the Prism instance were not touched. Earlier reports are
preserved.

## Bottom line

- **One demonstrated MAJOR defect.** On official profiles, absent tiles are polled
  forever. This starves refresh (P3-1).
- **One MEDIUM evidence defect.** The live "refresh Off stops requests" check cannot
  fail (P3-2).
- **Several LOW lifecycle, UX and documentation defects** (below).

No critical defect was found. Packaging, native wire and brand fidelity, privacy of
the typed export, the official_16 wire layout, and most prior-review corrections
hold.

**Local playable readiness:** the installed `c01f259d…` jar is fine for further
supervised manual testing at the profile's distance of 32. Do not raise
`distance_chunks` (or set it to 0) on official servers until P3-1 is fixed.

**Plan and release readiness:** not reached. `verification.json`'s
`release_ready: false` is correct.

## What was independently run vs only inspected

**Run by the coordinator:**
- `git diff --check 6677fe93` (clean).
- The untracked-file listing.
- SHA-256 of the jar: `c01f259d…eab0b`, matches.
- Re-hashed all **375** `source-manifest.json` inputs against the current tree:
  0 missing, 0 changed.
- Confirmed every changed or untracked `src/main` file is in the manifest,
  including `DhStatusSnapshot.java`.
- Confirmed the P3-1 code path by reading it.

**Run by the reviewers (scratch only):**
- Opus 1: a Java replica of the scan and reply loop, driving the real
  `DhSessionPolicy` (6 simulations).
- Opus 2: a harness against the recorded build's `common-0.16.0.jar`; a
  programmatic diff of the three language files; `javap` on Sodium 0.8.13 and
  0.6.13 option classes.
- Opus 3: `release_check.py --selftest` (124 OK); `check_dh_codec` and a
  forbidden-prefix scan of the dev jar; a recursive diff against the published
  v0.16.0+1.21.1 NeoForge jar (`404ac7b3…`).
- Fable: tallies of the JUnit XML; independent Python decoders over the committed
  corpus and the 64 live captures; `javap` on the DH 3.3.3 server jar; a PIL diff
  of the render screenshots; an mtime timeline.

**Not run:** Gradle (no build or tests this round, per the briefing's
coordinate-downtime rule), any live server or client, and any Windows-side check.
All suite counts below are recorded results that were audited, not re-executed.

## Verified defects

### P3-1 MAJOR: absent official tiles are re-polled every ~120 s forever and starve refresh (Opus 1, simulation; coordinator confirmed the code)

**Where:**
- `common/.../dh/DhSessionPolicy.java:49-51`: `absent()` always sets `due = now + 2 s | 120 s` and never parks.
- `xplat/.../DhClientSession.java:196-197`: official range is `min(128, syncDistance)`.
- `:312-320` and `:368-375`: once a tile is beyond `min(32, generationDistance)`, it goes back to `absent(…, false)`.
- `:304`: priority 1 is always tried before refresh at priority 2.

**Trigger:** an official profile with sync on, where the server's DH data does not
cover the effective range. This is the default for `distance_chunks: 0`, because
range is then `min(128, syncDistance)` and the server sync distance is 2048 live.

**Cycle:**
1. The client sends a sync with ts 0.
2. The server finds no row and returns an empty reply.
3. The tile is marked `absent(gen=true)` and is due in 2 s.
4. The tile is beyond generation reach, so it becomes `absent(gen=false)` and due in 120 s.
5. The cycle repeats indefinitely.

**Consequence:** with the player stationary, the client sends a steady **120
requests/min for 30–60 min** (server data radius 32–40, range 48–128). That
happens even with refresh 0, and with refresh 300 s it gives **0 refresh requests
per hour**. With full coverage it settles to 6/min. This conflicts with plan §5.3
("a bounded full request") and with the disposition's "park absence … rather than
spinning permanently".

**Why live evidence missed it:** the runs used distance 8, and the profile uses 32.
Both keep every tile within generation reach.

**Fix:** after an empty reply for a tile that generation cannot reach, escalate the
backoff and park it until reset, reconnect, or until it comes within reach. Give
priority-1 retries a budget so refresh is not starved.

**Regression test:** a stationary steady-state requests-per-minute bound when the
server data radius is smaller than range, for refresh 0 and refresh > 0. Assert
that refresh still fires while absent tiles exist.

### P3-2 MEDIUM: the live "disable refresh → requests stop" observation is vacuous (Fable + Opus 1, independently)

**Where:** `test-fixtures/dh-live/src/dev/vox/lssfixture/dh/Probe.java:262-268` against
`DhClientSession.java:304`.

**Trigger:**
- Phase 6 sets refresh to 0 on the first unchanged reply.
- Phase 7 checks for new conditional requests only about 3 s later.
- But every conditional request is a priority-2 refresh, and refreshes are
  rate-limited to 1 per 10 s from `lastRefresh`.

The mtime timeline confirms the check window falls inside that lockout.

**Consequence:** `dh-refresh.json {pass:true}` and the briefing's "then disables
refresh and observes requests stop" do not demonstrate the hot disable. Only unit
tests do (`DhSessionPolicyTest`, `DhClientSessionTest.hotRefreshReload…`).

**Fix:** observe for more than 10 s plus the request interval, with a snapshot
provably due at the moment of disable. Alternatively, assert the policy state
(`!due(p, MAX)` for every held receipt). Reword the briefing and
`dh-refresh-settings.md`.

### P3-3 LOW-MEDIUM: a main-thread stall over 5 s stops the DH source until a dimension change, receive toggle or reconnect (Opus 3, code reading)

**Where:** `DhClientSession.java:456` (`onMain` 5 s `get`), reached from `:351`,
`:388` and `:396`. A `TimeoutException` lands in the catch-all at `:126`, which
calls `owner.fail`. `tick()` (`:57-63`) never rebuilds a failed session on the same
level.

**Trigger:** on Windows, dragging or resizing the window (the GLFW modal loop), a
long GC pause, or a hitch, during conversion. No terrain is corrupted, because
late tasks see `!active`.

**Fix:** treat a main-thread timeout as a transient failure of that tile (cooldown
or abort the tile), or poll in short slices while `active`.

**Regression test:** hold the main-task queue for more than 5 s mid-tile, then
release it. Assert the session is still active and the tile is re-requested.

### P3-4 LOW: a reset that fails after retirement leaves a stale session and status (Opus 3)

**Where:** `DhClientSession.java:135-139` and the DH branch of `ClientCommandActions`.

**Trigger:** `invalidateCurrentNativeCache()` throws after `retire()` succeeds.
Causes include:
- the strict-sweep IO timeout;
- a locked file on Windows;
- a null `getCurrentServer()` in singleplayer.

**Consequence:** the Voxy wipe is correctly skipped. But `resumeAfterReset()` never
runs, so `session` still points at the retired object. `problem` keeps its earlier
text (for example "acquiring terrain") while the typed state reads STOPPED.

**Fix:** catch post-retirement failures in `drainForReset`, set `problem`, and null
the session. Add a test where the strict sweep fails.

### P3-5 LOW-MEDIUM: typed state reports `NEED_VOXY` when the real blocker is the authority limit (Opus 2)

**Where:** `DhClientSession.diagnostics()` around `:41-47` and `tick()` around `:60-64`.

**Trigger:** `connectionState.blocked(dim)`, or a `policy()` that throws "DH
dimension authority limit reached; reconnect". The session stays null while the
transport is present.

**Consequence:** `/lss status`, `/lss diag` and the exported support artifact send
the user to troubleshoot Voxy when the actual fix is to reconnect.

**Fix:** add a `BLOCKED` (reconnect required) state with lang keys in all three
locales, plus a test of the blocked path.

### P3-6 LOW: a negative `refresh_seconds` displays as "30" while refresh is effectively Off (Opus 2, harness)

**Where:** `ClientOptionCatalog.generalPage()`, the refresh getter
`v==0 ? 0 : max(1, min(120, (v+15)/30))`.

**Evidence:** the harness printed `yaml=-5 configured=-5 effective=0 uiIndex=1 uiLabel=30`.
Selecting 30 is a no-op because Sodium sees no change.

**Fix:** return 0 for any `v<=0`, and add a -5 case next to
`dhSelectorsSaveStringsAndRefreshDoesNotRewriteAnUntouchedNonStopValue`. The 15→30,
45→60 and 3601→3600 cases are consistent with the briefing.

### P3-7 LOW: documentation and evidence wording (Fable, Opus 2)

- **Skip labels.** The 4 Fabric skips are the four `*ExperimentTool` / `XverLiveCorpusCaptureTool`
  opt-in tools, not "Sodium availability cases". This mislabel is in the briefing,
  `dh-refresh-settings.md:61` and the disposition.
- **The "174,027 changed pixels" figure.** No recorded method produces it. An
  independent exact-RGB diff of the lower two thirds gives on/off **235,048**,
  off/restored 247,167 and on/restored 85,767 (noise). The control's direction and
  magnitude are confirmed. Record the threshold and region, or drop the exact figure.
- **"Independently derived" hashes.** `DhTerrainDecoderTest.java:39` still says
  "independently derived expected value". The official corpus `p1-provenance.json`
  credits an "independent Python format-2 reader" that is not present anywhere.
  Align both with the "regression pin" wording.
- **Literal fixture JSON fields.** `vanilla_authority:false`, `zero_sky:true`,
  `old_budget:0`, `software_control:true` and `session_receipts:true` are literals,
  true by construction of the surrounding checks. Say so, or record real
  observations.
- **UP-TO-DATE suites.** `lss-dh-p3-final.log` shows `:common:test` and `:paper:test`
  as UP-TO-DATE; the XMLs come from earlier executions on identical inputs.
  Legitimate, but say so.
- **Reload wording.** The YAML and doc comment "Reload resets existing refresh
  timers" overstates it. Only an interval change reconfigures receipts.
- **Obsolete `/lss dhstatus`.** It is still registered on both loaders
  (`LSSClientCommands.java:34`, `LSSNeoClientBootstrap.java:93`) and prints
  untranslated `describe()` text. The `.txt` export drops the Versions and settings
  lines in DH mode. Refresh Off displays as "refresh 0 s".

## Suspected risks (not demonstrated)

**Refresh and receipts**
- **Redundant first refresh after generation.** Generation-acquired tiles probably
  pay one redundant full re-download at their first refresh: the server re-stamps
  `LastModified` at save, while the DTO carries the in-memory value. This is
  consistent with "3 conditional / 1 unchanged". It self-heals after one round.
  Support has an analogous second-straddle quirk.
- **Deleted rows look unchanged.** An owned conditional request whose server row
  was deleted returns empty and is treated as unchanged. This is the plan §5.3
  accepted ambiguity, and the "refresh after removal" gate is still open.
- **Effective refresh period.** With at most 1 refresh per 10 s, the real per-tile
  period is `max(interval, N × 10 s)`: about 48 min for 289 tiles and 11.7 h for
  4225. The "30 s" UI stop is a minimum; the tooltip should say so.
- **Unanswered streak.** A refusal does not reset `unanswered`, and two pending
  timeouts on the same tick end the session.
- **Partially known tiles.** A tile with server-unknown columns parks after 4
  attempts, even when every attempted column was accepted.
- **syncRate 0 with sync on (Support only).** Acquisition stalls while status still
  says "acquiring". It correctly does not fall back to generation.
- **Receipt eviction.** It is by last write, beyond 8192 distinct tiles. It can evict
  in-frontier held receipts (safe direction) or effectively unpark a parked tile on
  long travel loops.

**Connection and lifecycle**
- **Proxy backend switch.** A play→config reconfiguration (for example Velocity)
  does not clear `DhConnectionState`. Only `bindWorld`'s key compare guards it, and
  an empty key falls back to the dimension.
- **Singleplayer and LAN-guest noise.** There is no `hasSingleplayerServer` gate, so
  a "No DH config reply" warning appears after 15 s. Fabric sends without `canSend`
  (it degrades to the timeout), whereas NeoForge fails fast.
- **Main-thread block after a native→DH reconnect.** The strict sweep's
  `runIoAndWait` (up to 30 s) runs on the main thread at the first DH tick.

**Diagnostics**
- **Torn counters.** `Session.diagnostics()` reads about 15 atomics separately while
  the worker updates them, so counts can be internally inconsistent within one
  sample. The published record itself is immutable.
- **Counter scope and labels.** Counters reset per dimension and nothing says so.
  English "columns" is ambiguous (DH block columns vs chunk columns).
- **No unchanged counter.** There is no typed counter for unchanged replies.

**Build, packaging and protocol**
- **testFixtures include filter.** `common/build.gradle:45` puts `.include('dh-corpus/**')`
  on the whole testFixtures resource set, which would silently drop future fixture
  resources.
- **Fixture namespace not forbidden.** `release_check.py` does not list
  `dev/vox/lssfixture/`; the fixture is excluded only by build separation (it is
  absent from the artifact).
- **Beacon layout.** Round-trip tested only; no capture contains beacons.

## Incomplete plan gates (unchanged by this review)
1. Fix P3-1 and prove it live at range beyond the generation cap (for example
   distance 0 or 128 against official 3.3.3).
2. A non-vacuous live refresh-Off observation (P3-2).
3. An in-session Sodium Apply. Menu evidence ran at the TitleScreen with the state
   PAUSED, and gameplay switches used `saveDraft` + `reload`, so hot refresh and
   reconnect-pending source through Sodium have unit coverage only.
4. A live-session DH diagnostics export (READY state) and a privacy pin test for the
   `dh` field. The production `ClientStatus` DH branch is pinned only by the fixture.
5. Official_15 corpus and gameplay; DH Support on the current artifact; a real-DH
   client handshake and load-ratio comparison; DH 3.3.4 (no MC 1.21.1 build).
6. The asynchronous Voxy vanilla-overwrite race measurement, custom-height live
   sessions, and refresh after removal.
7. Voxy completion/persistence acknowledgement. This is still **open**: nothing in
   the code relabels enqueue as completion, and labels say "enqueued" / "ingestion
   unacknowledged".
8. Official-16 `AIR` / `_STATE_` converter unit coverage (live only today).
9. Measurements: CPU, allocation, frame time and server load. Other MC lines and
   Windows graphics-backend qualification. Upstream outreach.

## Checked and found sound (condensed)

**Refresh semantics** (Opus 1, Fable)
- Default 0.
- `configureRefresh` keeps timestamps and touches only `snapshot && !generate &&
  failures==0`. It never unparks failed work. A positive interval reconciles
  immediately and the first refresh comes one interval later (test-pinned).
- `refreshTimestamp` is returned only for complete, failure-free receipts with
  ts > 0. Units: server ms for timestamps, nanoseconds for scheduling, with no
  clock-offset re-adjustment. This is correct, because the official client's offset
  exists only for its own re-stamped local rows.
- `unchanged` requires an owned request, ts > 0, and an exact receipt match, and it
  renews without enqueueing. Empty initial, stale, reset or invalid replies never
  establish acceptance.
- With sync off, no generation substitute is used.
- Priorities run 0 → 1 → 2. Refresh is limited to 1 per 10 s.
- Receipt cap 8192 ≥ 4225. Backoff 30/60/120 s, parking on the 4th attempt.
- **Prior fixes confirmed in this area:**
  - Support refresh storm (D-2);
  - the rejecting-Voxy loop is bounded (R-1);
  - `unanswered` decays on a response (R-3);
  - stuck-generate tiles fixed;
  - the Support world-name fallback is removed.

**Settings** (Opus 2)
- Strings are persisted, never indices; the schema rejects unknown strings.
- Source and profile are `Timing.S`, pinned at `beginSession`. `onJoin` and
  `reconcileReception` mask native when DH is selected, so there are never two
  terrain sources. Refresh is `Timing.H`, reconciled on the next tick.
- The untouched-45 guarantee holds on both Sodium generations (bytecode-verified
  skip of unchanged options, plus path-scoped `saveDraft`).
- Native stays the default.
- One catalog serves both generations; the NeoForge legacy page twin is identical.
- The three locales have 0 key or placeholder mismatches; NeoForge reuses the
  Fabric lang files.

**Diagnostics and privacy** (Opus 2, Fable)
- Schema 3 is purely additive: `dh` is nullable and omitted in native mode.
- `DhStatusSnapshot` holds only numbers, booleans and enums. Peer text,
  addresses, keys and coordinates never reach the export.
- DH mode reports protocol 0, negotiated and server-enabled false, Xaero DISABLED
  and renderer false.
- Labels say "enqueued" and "ingestion unacknowledged".

**Integration and transport** (Opus 3)
- **NeoForge retained slices:** one `readRetainedSlice` per frame with an idempotent
  close; flow is checked before `capture`, so guest C2S data never reaches the
  budget; 32 × 1 MiB caps; released on logout and after the 5 s orphan expiry;
  `registrar("1").optional()`, skipped when DH is installed.
- **Fabric guest C2S codec:** a separate, discarding codec.
- **Capture and retire:** the `active` check under the frames lock closes the race.
  Retire interrupts the worker and closes the assembly only after it terminates.
  Cancel is sent on the captured connection only.
- **Cache sweep:** the strict sweep throws on failure; the native, non-strict
  behaviour is unchanged.
- **Vanilla authority and reset:** authority is per connection and per dimension,
  bounded at 16 dimensions × 262,144 columns. Reset keeping vanilla ownership is
  pinned at `DhClientSessionTest:196`.
- **Converter:** uses actual min Y and height; complete columns only; fresh
  sections; quart-centre biomes; zero skylight without sky.
- **Decoder:** the H+1 exception is limited to Support and official-16, and H+2
  rejects. `AIR` and `_STATE_` are official-only.

**Packaging** (Opus 3)
- No `org/tukaani`, fixture, test-stub or DH classes, and no references to them.
  Relocated XZ, its 0BSD license and the notices entry are present.
- Compared with the published 0.16.0 NeoForge jar, entries are additions only.
  `neoforge.mods.toml`, manifest, brand, jarjar, mixin configs and AT are
  byte-identical, and all wire, payload and server classes are byte-identical.

**Evidence binding** (Fable)
- **Jar identity:** both final attempts loaded `c01f259d…` (FML log), not the
  prelabel `57adcdb9…`. The fixture hash, server DH jar hash, manifest hash and
  Prism receipt all match.
- **Suite counts:** they match the XML exactly (988 / 1674 + 4 skips / 23 / 536;
  `DhClientSessionTest` 12, no skips; gametests 78 and 8).
- **The unchanged reply is genuine:** the receive and send mixins only observe, and
  Probe decodes type-8 frames independently.
- **Render-off control:** `VoxyRenderMixin` is a valid attribution control, and the
  screenshots confirm the distant terrain vanishes and returns.

**Protocol** (Fable)
- **Official 16 matches DH 3.3.3:**
  - message ids, u16-byte UTF-8 strings, the 12-field SessionConfig with its
    one-byte generator-plan ordinal;
  - Request, Response, Split, Exception and CloseReason layouts;
  - all 37 live splits and 25 responses parse with nothing left over;
  - timestamp slot order (modified, created);
  - the server's `<=` comparison.
- **Official corpus:** `dh-official-3.3.3-neoforge-partial` is a genuine capture
  (assembled SHA matches provenance) and is asserted by
  `officialNeoForgeCapturePreservesItsCompleteAndUnknownHalves`.
- **Prior protocol fixes are in and pinned:** R-2 (Support outbound byte-length
  strings), Support CloseReason i32, Support mapping text, D-5 XZ terminal read.
