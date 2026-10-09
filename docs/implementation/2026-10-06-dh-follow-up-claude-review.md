# Claude Code review: DH follow-up (automatic selection, Sync/Live, source-cache corrections, border, UI)

Date: 2026-10-06. This review answers `2026-10-06-dh-follow-up-claude-review-briefing.md`
and was critical by request.

**Reviewers:**
- Opus 1: automatic selection and protocol discovery.
- Opus 2: source cache, sync on load and live updates.
- Opus 3: UI, lighting, vanilla border and packaging.
- Fable: adversarial evidence, upstream semantics and operations.
- Coordinator: scope and rig checks.

**Read-only:** this review made no implementation, installation or publication change.
It did not touch the live manual server, the Windows/Prism instance or personal worlds.
Earlier reports are preserved.

## Bottom line

The increment is substantial, and much of it is careful:
- strict discovery that never guesses;
- one reconnect per user join;
- the L-1 fluid lighting fix (exact against DH's own floor light across about 7,300
  pairs);
- stub-free packaging;
- tolerant retirement of old settings;
- I3 holding when native wins.

It nonetheless has **three high-severity problems**:

1. **Fabric auto-reconnects on every server without DH** (two reviewers found this
   independently; the coordinator confirmed it in the code). With Use DH on, a Fabric
   client is disconnected and rejoined about 6 s into every join on any vanilla or
   plugin server. NeoForge checks for the channel and does not do this.
2. **The source cache freezes once a world's bucket is full** (coordinator confirmed
   the code). Every bucket file is pinned when the cache opens. Once full, new tiles
   are silently refused, so the home area is re-downloaded on every join, and the H-1
   overwrite returns for those tiles. **H-2 is regressed in a new form.**
3. **Discovery's "official rejection" branch is unreachable on real 1.21.1 servers.**
   Stock 3.3.3, not only 3.2.0-b, fails in its logger and goes silent. So the
   automatic-selection account's javap-verified CloseReason claim is wrong, and the
   5-second silence heuristic is in fact the only official mechanism.

**Readiness:** acceptable for continued supervised NeoForge manual play. Not acceptable
to hand a Fabric user "Use DH" until D-1 is fixed. The cache claims from the
disposition ("H-2 fixed", "stored data rebuilt once") do not hold.

## What was independently run vs inspected

**Coordinator, run:**
- `git diff --check`: clean.
- Re-hashed all 86 `after_sha256` entries of
  `evidence/2026-10-06-dh-follow-up-review-manifest.json` against the current tree:
  0 missing, 0 changed.
- Confirmed the manual server is live and owns the harness lock: pid 215509 on
  `[::1]:25585`; `stop-server.py --status-json` reports `ready: true` at 18:39:50Z.
- Confirmed findings D-1, D-2 and D-3 below in the source.

**Not run this round:** the briefing's normal Gradle gauntlet. The shared harness lock
is legitimately held by the live manual server, and the briefing forbids bypassing it.
This review therefore makes **no independent green claim** for this candidate. The
3326-test figure is the implementer's, audited by recount only (see the evidence
section). A full rerun needs a coordinated, clean stop of the manual server.

**Reviewers, run (scratch only):**
- Opus 1: `FuHarness` against the compiled build copy `/tmp/lss-dh-auto-20261006`
  (hash-identical to the checkout), reusing `DhClientSessionTest`'s fixture; `javap`
  of Fabric `ClientPlayNetworking.send`; log timing from the official-15 and
  Support-15 attempts.
- Opus 2: `QuotaHarness`, `FlightHarness` and `MiscHarness` against the build copy's
  common classes; `javap` of the pinned Voxy port (`024d5807…`).
- Opus 3: `unzip`/`javap` of the installed NeoForge candidate (`c0713646…`, identical
  to the build copy's jar) and the Fabric jar; `javap` of Sodium 0.6.13, 0.7.3 and
  0.8.13; a lang-parity script; the fluid-light replica oracle `Light.java` on the
  official 3.3.3 and Support 0.14 ocean tiles; a border-sweep replica (`Sweep2.java`).
- Fable: hash binding across all attempts and builds; XML recounts; three-way manifest
  diffs; `javap` of DH 3.2.0-b and 3.3.3 (`DhLogger`, `NetworkSession`), the Paper
  1.21.1 handshake listener and Fabric API `send`; a grep of every retained server log;
  `/proc` inspection; `stop-server.py --status-json` (read first and confirmed to have
  no side effects).

## Verified defects

### D-1 HIGH: Fabric forces a disconnect and reconnect on every server without LSS or DH (Opus 1 harness, Fable; coordinator confirmed)

**Where:**
- `fabric/src/main/java/dev/vox/lss/networking/client/DhClientTransport.java:41`
  installs a bare `ClientPlayNetworking.send(...)`. The `javap` of Fabric API
  0.116.15 shows `send` does no `canSend` check and does not throw.
- `xplat/.../DhClientSession.java:500-501` calls `probeLegacy` after 5 s of silence
  (`:112-124`). Then `:128-141` disconnects and calls
  `ConnectScreen.startConnecting(new TitleScreen(), …)`, and the official-15 trial
  times out at 15 s.
- The NeoForge twin (`neoforge/.../DhClientTransport.java:106-110`) checks `hasChannel`
  and fails fast.

**Trigger:** a Fabric client with Voxy and Use DH on joins any vanilla, Paper-without-DH,
LAN or Realms server.

**Consequence:** an unexplained kick to the title screen and an automatic rejoin about
6 s into every join. This contradicts the account's "Neither: ordinary Minecraft stays
connected" (`2026-10-06-dh-automatic-selection.md:16`). Every automatic-selection cell
in this increment is NeoForge-only.

**Harness:** the probe sent 2 frames with no exception. A silent server then gave
`active=false pendingRetry=true`.

**Fix:** gate the Fabric sender, and re-check at the `probeLegacy` deadline, on
`ClientPlayNetworking.canSend(Payload.TYPE)`. Bukkit plugins may register in play
phase, so re-read it at the deadline.

**Regression:** a remote-channel predicate returning false yields no `pendingRetry` and
a stopped status naming the missing channel. Add a live Fabric vanilla no-service cell.

### D-2 HIGH: a full source-cache bucket never admits new tiles (H-2 regressed; Opus 2 harness; coordinator confirmed)

**Where:**
- `DhClientSession.java:381-384`: on open, `terrainCache.protect(terrainCache.positions())`
  pins **every file in the bucket**.
- Files are unpinned only after a successful restore (`:413`, `:441`).
- `DhTerrainCache.trim` skips protected files (`DhTerrainCache.java:221`).

**Trigger:** one world's bucket reaches 8192 files or 256 MiB. About 4 km of elytra
travel at range 128 is enough, as is ordinary multi-session play.

**Consequence:**
- Out-of-range tiles stay pinned forever and are re-pinned every session. Only tiles
  touched in the current session are evictable, which is the opposite of LRU.
- Each new tile is written in full, fsynced and renamed, then declined and deleted.
  `write` returns false and nothing logs or counts it.
- Uncached tiles take the full-reply, non-comparing path. The home area is therefore
  re-downloaded every join, and **H-1's overwrite of vanilla-refined terrain returns
  for those tiles**.

**Evidence:**
- `FlightHarness` (real 8192 quota): home is 121 tiles, followed by an 8192-tile
  flight. Home sessions 2-4 each show `restored=0 downloaded=121 declinedWrites=121`.
- `QuotaHarness` S3: steady state is 86 re-downloads and 86 declined writes per
  session, while unrevisited area A keeps all 121 files forever.
- The existing pin `pinnedWorkingSetSurvivesOneTileOverflow…` covers only a single
  bucket with no unvisited remainder.

**Fix:**
- Protect only tiles inside the current effective range, re-evaluated on movement or
  capped to the nearest K.
- Evict out-of-range and never-validated files before files touched this session.
- Count and report declined admissions.

**Regression:** `FlightHarness` home sessions 3+ restore at least 120/121, and area A is
evicted first.

### D-3 HIGH (upstream overclaim + reachability): stock official servers on 1.21.1 never send the rejection discovery relies on (Fable)

**Evidence:**
- `build/dh-neoforge/2026-10-05/p2-p4-final/profile-mismatch-runs/attempt-sw6_py54/server.log:141-142`
  (stock **3.3.3**) shows the same `found 1 argument placeholders… IncompatibleMessageInternalEvent{}`
  logger failure as 3.2.0-b, and the client saw "No DH config reply", never a
  CloseReason.
- `javap`: `DhLogger.log` and `NetworkSession.tryHandleMessage` are identical in 3.2.0-b
  and 3.3.3. The catch block's second `LOGGER.error` throws again, so `close(e)` is
  never reached. The server bundles log4j 2.22.1 (MC 1.21.1).

**Consequence:**
- The strict official-15 rejection branch of `DhProtocolDiscovery.advertised`
  (`common/.../DhProtocolDiscovery.java:22-33`) is unreachable against any real 1.21.1
  official server. It is exercised only against LSS's own encoder
  (`DhProtocolDiscoveryTest`, `DhClientSessionTest.rejection`); no producer-emitted
  CloseReason exists in the corpus.
- A false legacy trial against a 3.3.3 server (see D-4) goes silent and is
  misreported as "No DH service replied".
- The account's "checked against the stock 3.3.3 jar with javap" statement is wrong.

**Fix:** rewrite the account so silence is the official mechanism and the rejection
branch is defensive. Relabel the rejection tests as self-encoder controls. Treat a
silent legacy trial on a server that answered *something* on the DH channel
differently from "no service".

### D-4 MEDIUM: the 5 s quiet-probe deadline ignores frames that already arrived (Opus 1 harness; coordinator confirmed)

**Where:** `DhClientSession.java:500-501` tests only `config==null`. It does not check
captured, queued or decoding frames. The request-timeout path guards on
`awaitingFrames`; this one does not.

**Trigger:**
- A client hitch of more than about 5 s at join (world load, Voxy or shader start-up).
  Fabric receivers run on the main thread, so the frame is captured but the same
  frame's `tick` fires before the worker decodes it.
- A server that takes more than 5 s to reply. DH handles messages on the server thread.

**Harness:** a valid OFFICIAL_16 config was captured (`frames=1`) and `tick` then gave
`pendingRetry=true`, with the config discarded.

**Consequence:** a forced reconnect, then the official-15 trial goes silent (D-3). DH is
lost for that join and misdiagnosed as "no service".

**Fix:** don't fire while frames are captured, queued or decoding. Require the deadline
to persist across several idle ticks, and consider a longer deadline.

**Regression:** a late-but-valid v16 config arriving at about 5.5 s yields no retry.

### D-5 MEDIUM-HIGH: a dimension change re-arms the automatic reconnect after a completed handshake (Opus 1 harness)

**Where:** `Session(...)` takes its profile from `probeProfile()` (`:336`).
`fallbackProbe` is set only in `onJoin`. On a fresh connection `retryUsed=false`, so a
portal or respawn creates a session that probes 16 again with `probeLegacy` armed.

**Harness:** an established v16 session, then a switch to the Nether, gave a new session
with `OFFICIAL_16, retryUsed=false, fallbackProbe=null`. A config slower than 5 s then
gave `pendingRetry=true`.

**Consequence:** an automatic disconnect mid-play at a portal or respawn, exactly when
stalls are likeliest. Combat-log plugins may punish it. Whether official DH even answers
a second config on the same connection is unverified; if it doesn't, every dimension
change triggers this.

**Fix:** remember the established family per physical connection (keyed by `Connection`)
and never auto-reconnect after any valid config on that connection.

### D-6 MEDIUM-HIGH: the cache is written before delivery, and preserve-on-mismatch then locks in stale Voxy content (Opus 2)

**Where:** `DhClientSession.java:818` caches the new source (`completed.cache`) **before**
`deliver` (`:828`). On reconnect, `deliver(reuseCache)` preserves any non-default
differing column (`:897`, `:903-909`).

**Trigger:** a changed full reply (v2) is cached while Voxy still holds v1, because of
any of:
- a disconnect or quit with the ingest still queued. The pinned port drops queued
  ingests on shutdown (`Service.shutdown`, `javap` of `024d5807`; research
  `VoxelIngestService.shutdown`).
- a rejected column;
- a 5 s `onMain` timeout part-way through a tile.

The next session's check says unchanged against v2, so the restore preserves v1.

**Consequence:** stale terrain stays until the server changes that tile again or the
user runs `/lss reset`, **even with sync on**. Before H-1's correction a mismatch
rebuilt the column, so this healed.

**Fix:** cache only after delivery is accepted. Treat "equals what DH last delivered"
(for example a per-column hash stored with the entry) as rebuildable.

**Regression:** cache a changed reply, reject or time out one column, reconnect, and
assert that column is rebuilt.

### D-7 MEDIUM: converter changes never reach already-cached terrain (Opus 2)

**Where:** `DhTerrainCache.VERSION=1` is unchanged, and legacy `dh-terrain-v1` buckets
are adopted. Meanwhile `DhColumnConverter.java:64` changed attenuation semantics (L-1).
Old light now mismatches, but non-default content is preserved, not rebuilt.

**Consequence:**
- The L-1 fix does not apply to cached tiles.
- The previous disposition's "stored data is rebuilt once" is false.
- Every future converter fix will inherit the same problem.

**Fix:** put a converter-semantics version in the entry and identity, and bump it on any
change to conversion output. Optionally treat a light-only mismatch (blocks and biome
equal) as rebuildable.

### D-8 MEDIUM: a client-thread stall over 5 s during a live push stops DH for the connection (Opus 2)

**Where:** `receiveUpdate` (`:590-646`) catches only `DhCodecException`. A
`TimeoutException` from any of its roughly 20-40 `onMain` hops reaches
`Inbound.receive`'s `catch(Exception)`, which calls `owner.fail`. The response path
(`:834`) and the restore path (`retryCache`) treat the same timeout as transient.

**Fix:** catch the timeout, invalidate that tile's policy entry, and continue. Add a
regression test.

### D-9 MEDIUM-LOW: live pushes bypass the Voxy backlog gate, and a burst kills the session (Opus 2)

**Where:** `tick()` gates requests on `backlog>256` (`:540`), but `receiveUpdate`
enqueues into Voxy without checking the backlog.

**Trigger:** pushes are server-paced (TNT, WorldEdit, other players' generation). Each
push costs many main-thread hops on the worker, against 32 inbound frames (`:187`) and a
40-slot worker queue. A burst reaches "inbound queue limit" or "worker queue full" and
DH stops (fail-closed; memory stays budget-bounded).

**Fix:** under backlog or queue pressure, drop the push and `policy.invalidate` plus
remove the cache entry, so the tile is re-acquired later.

**Regression:** a push-burst test.

### D-10 MEDIUM-LOW: the border pump cannot keep up with a moving player (Opus 3, replica simulation)

**Where:** `xplat/.../compat/VoxyVanillaCoverage.java:88-89` resets `cursor=0` on every
chunk crossing, and the sweep is x-major, so +x is visited last. `:93` (a cooldown early
return) and `:107` limit it to one submit per 125 ms. At 20 TPS that is 6.67/s, **not**
the documented 8/s. It also resubmits every held border chunk.

**Evidence:** `Sweep2.java`, assuming every ingest succeeds:

| Case | West | East | North | South |
| --- | --- | --- | --- | --- |
| Stationary, RD 12 | heals within 30 s | heals within 30 s | heals within 30 s | heals within 30 s |
| Sprint east, RD 12 | 12/44 missing | 44/44 missing | 42/42 missing | 42/42 missing |
| Elytra, RD 12 | 43/44 missing | 48/48 missing | 44/44 missing | 44/44 missing |
| Sprint, RD 32 | 119/128 missing | 128/128 missing | 124/124 missing | 124/124 missing |

`VoxyVanillaCoverageTest` has no moving-camera case.

**Fix:** keep the cursor across recenters, or better, make it event-driven: queue on
client chunk load and retry when light becomes ready. Correct the 8/s claim.

**Regression:** a moving-camera bound per side.

### D-11 MEDIUM (ops): the supervisor escalates to SIGKILL after 3 s (Fable)

**Where:** `scripts/lib/owned-process.py:144-152`. On SIGTERM, SIGINT or SIGHUP to the
supervisor, on PDEATHSIG when `start-server.sh` dies, or on `harness_cleanup`'s `kill`,
the group gets SIGTERM and then **SIGKILL at 3 s**. The 60 s `request_runner_stop`
grace applies only with `--rig-run-root`, which the manual server does not pass.

**Consequence:** a world save plus DH SQLite close routinely exceeds 3 s, so the server
is killed mid-save. `server-launch.json` advertises the bash `supervisor_pid`, which is
the obvious thing to kill. Only `stop-server.py --stop` is safe.

**Fix:** use a 60-120 s grace in manual-server mode, or forward signals to java only and
wait.

### D-12 LOW (assorted, verified)

**Restore and border runtime**
- One transient Voxy exception (for example "WorldEngine that was not alive" during
  `/lss reset` or leaving a world) sets `VoxyVanillaCoverage.disabled=true` for the
  whole launch (`:123-126`). `clear()` never resets it. Latch only on `LinkageError`.
- `nativeOffer` calls `retire()` synchronously, so the main thread can block for up to
  2 s.
- A retry join that takes more than 60 s counts as a fresh join and spends another
  automatic reconnect. This repeats on servers whose joins consistently exceed 60 s.
- `fallbackProbe` is effectively unreachable: ticks retire the session while
  `level==null` during reconfiguration. There is no reconfiguration test.

**Status, docs and strings**
- The status reads "Native LSS available; DH fallback idle" even when native
  **declined** service (a disabled offer or a gate denial suppresses DH). Say so in the
  status and release notes.
- `zh_cn`/`zh_tw` `lss.config.lod_source.tooltip` omit "DH approximations can replace
  saved vanilla terrain", the only storage-overwrite warning.
- `en_us.json:121`'s "an older server may need one automatic reconnect" is wrong: DH
  Support 0.14.0 is current and always needs it.
- `2026-10-06-dh-sync-live-updates.md:9-12` still describes a "DH server" control and
  "Source/profile require reconnecting". Manual guide line 11 keeps a static "Setup
  status: ready".
- Dead `lss.config.dh_profile*` strings were rewritten this increment for a selector
  that does not exist. `lss.config.lod_source.lss/.distant_horizons` and
  `lss.settings.lod.dh.profile/refresh_seconds` are also unused.

**Evidence labelling**
- Fabric's 1759/1755 includes 4 fixture observer tests (`ContentProbeTest` via
  `-I fixture-tests.gradle`); the briefing omits this.
- "1,200 independent block comparisons" is `SAMPLES.size()*12`, a formula
  (`UpdatesProbe.java:133`), not a read counter.
- "Zero DH requests" in the dual cell counts only terrain `Request` frames.

## Suspected risks (not demonstrated)

**Reconnect hazards**
- **Throttle versus the automatic reconnect.** Paper's `connection-throttle` (4000 ms)
  exempts only the literal `127.0.0.1`, so `::1` and every remote player are subject to
  it. Velocity's `login-ratelimit` is 3000 ms.
  - The passing Support-15 cell's two handshakes were **5.03 s** apart under software
    OpenGL. A faster real client can land inside 4 s: throttled, kicked to the title
    screen, and the window re-armed.
  - DH Support needs this reconnect on every fresh join. DH Support closes nothing on a
    mismatch (plan §4.1), so switching codec within the connection for Support-15 would
    remove the reconnect entirely.
  - Otherwise: enforce at least about 6 s since connect, tell the user before
    disconnecting, and return to the server list.
- **The reconnect target is `ServerData.ip`** (`:137-140`). After a transfer packet or a
  proxy backend switch this moves the player off their backend (to the lobby). A
  remembered hint can then apply the wrong family decoder to a different backend.
  Version-15 ASCII configs decode under both families. Don't auto-reconnect unless this
  is the first play session of a direct connection.
- **Dual native+DH topologies.** On a Paper server with both native LSS and DH Support,
  a single tick before `minecraft:register` arrives causes a redundant native
  handshake. An official DH server with networking disabled but the channel registered
  looks silent and triggers the quiet reconnect on every join, on both loaders.

**Live updates**
- **Push stamps.** A complete-edit push advances the receipt to `max(prev,T)` and
  clears pending revalidation (`:637`). A push that is lost (dropped for distance, or
  swallowed as EXPIRED/UNOWNED/UNSUPPORTED at `:644`) is then masked for the session.
- **Late and cancelled pushes.**
  - A push older than an accepted whole-tile receipt with no fence is not checked
    against `policy.timestamp`.
  - A request cancelled by a push can still be sent.
  - A push with `requiredTimestamp` above the server's full-data stamp parks the tile
    after 4 cooldowns.

**Border pump**
- **Partial light made permanent.** Voxy `enqueueIngest` returns true if any section
  has light. The pump then marks the chunk accepted, so a partly-lit border chunk stays
  dark until it reloads.
- **I1 gap made sticky.** An older queued DH write that commits after the pump's
  vanilla ingest is never re-asserted while the chunk stays loaded (WeakReference
  identity).
- **Runs outside LSS sessions.** The pump runs in single-player and on vanilla servers.
  It submits about 200-500 redundant chunks per join; the content is correct, but the
  behaviour change is undocumented.
- **Unqualified tooltip.** "Loaded vanilla terrain remains authoritative"
  (`en_us.json:119`) is stated without qualification against the open commit gap.

**Settings, cache and ops**
- **Legacy Sodium page.** The legacy page resolves `CyclingControl(Option, Class,
  Component[])` eagerly although no enum option is used. It exists in 0.6.13 (`javap`)
  but not in 0.7.3, where the entire legacy page would fail. Resolve it lazily. The live
  menu evidence covers Sodium 0.8.12 only, and the NeoForge 0.6.13 three-tab path is
  unverified.
- **Retired and changed keys.** Retired `lod.dh.profile` and `refresh_seconds` are
  skipped silently, with no "ignored" notice. An old `source: distant_horizons` silently
  changes meaning to "fallback; native wins".
- **Cache filesystem edges.** A cache-directory removal that fails (read-only) stops
  the session every session (fail-closed, but repeating). One junction bucket makes
  `index()` throw, which disables the DH cache for every server. After 4 L-2 deferrals a
  tile is held for the session.
- **Supervisor details.** `--status-json` "ready" means identity plus a TCP connect, so
  a hung JVM reads as ready. FIFO writes are `O_NONBLOCK`, so a stalled stdin reader
  silently absorbs commands. `detached-server.log` is never rotated. WSL restart is
  handled (boot_id).

## Previous findings: verdicts

| Item | Verdict | Basis |
| --- | --- | --- |
| H-1 | **PARTIAL** | Restore now preserves differing non-default columns. Every uncached tile still overwrites refined terrain: partial tiles, pushed tiles, quota-declined tiles (D-2, unbounded), seed-0 and no-cache cases. Preserve-on-mismatch also locks in stale content (D-6, D-7). With sync off, offline edits on cached tiles are never corrected (by design; the docs should say "never"). |
| H-2 | **REGRESSED** (D-2) | The single-bucket 121/120 case and cross-dimension recovery work. Unvisited-file pinning freezes the cache. |
| M-1 | VERIFIED | When removal itself fails (read-only), the session stops; fail-closed but repeating. |
| M-2 | VERIFIED | Nothing is enqueued before validation, and restores don't starve checks. A cached tile is still read and decoded twice. |
| M-3 | Accepted, documented | Partial tiles are still uncached. |
| M-4 | Moot | The timer was removed; `Receipt.revalidate` is now dead code. |
| M-5 | VERIFIED (FIFO supervisor, read-time liveness) | New D-11. |
| L-1 | Code VERIFIED | About 7,300 fluid-bearing run pairs match DH's floor light exactly on official 3.3.3; Support 0.14 water 4374/4374. Not applied to cached tiles (D-7). |
| L-2 | VERIFIED | 4 deferrals make the tile held for the session. |
| L-3 | VERIFIED | Seed 0 refused (`:374`); pending files counted and swept on index rebuild. |
| L-4 | VERIFIED | — |

Earlier suspected risks that are now verified:
- an undecodable entry is invalidated;
- current-address clearing keeps foreign content (DH clear uses only `server.ip`, not
  alias groups);
- reset refusal works (if not retried, DH stays stopped);
- zero-stamp entries get full checks (`ts=0` receipts are never revalidated on load);
- `isOther()` junction refusal (fail-safe but global);
- per-column engine references with a no-op `close()`.

## Evidence audit (Fable)

**Binding:** `c0713646…` is the same file as the build copy's jar, the outer runtime
copy, and `mods/` in all four passing attempts. Fixture `60ad55ee…`, Voxy `024d5807…`
and the server DH jars match their declarations.

**Counts (XML recount):**

| Suite | Count | When it ran |
| --- | --- | --- |
| common | 1012 | 17:26-17:28Z, before the silent-probe fix |
| Fabric | 1759 / 4 skipped | 17:43-17:44Z, final |
| Paper | 536 | 17:30Z |
| NeoForge | 23 | 17:44Z |

"All 78" and "All 8" gametests are in `normal-build.log:543/662`. `release_check` is OK
for 6 artifacts, the selftest passed 126, and SQLite passed 9 after the disclosed import
failure.

**UP-TO-DATE reuse is legitimate.** Between the before-silent-probe-fix and final
manifests, only `xplat/.../DhClientSession.java` (plus `DhClientSessionTest` and
`ServiceProbe`) changed. Common and Paper never compile xplat, so reusing their results
is correct. Fabric and NeoForge compile, tests, shadowJar and both gametest suites
executed.

**Failures are retained and disclosed:** the schema-count red, the official-15
attempts `bfbc1bl3`/`0_e_4c49`, and the SQLite import failure.

**Oracles:** `BoundaryProbe`, `ContentProbe.knownBlocks` and `Probe.readback` compare
Voxy storage against expected states derived by the fixture, independent of the
converter. `RejoinProbe.fullyStored` uses production `matchesCache` and is labelled a
premise.

**Packaging:** the installed NeoForge jar has no `net/caffeinemc` stubs, no fixture,
test or Recording classes, and no `org/tukaani` references. XZ is relocated (142
entries) with its 0BSD license and NOTICES entry. The Fabric jar is the same. Version
is `0.16.0-dh-auto.dev20261006`.

## Checked and found sound (condensed)

**Discovery**
- A family is chosen only when exactly one profile decodes the whole frame. Official
  (2-byte) and Support (4-byte) CloseReason cannot both decode.
- Ambiguous version-15 frames and versions other than 15 or 16 never authorize a
  reconnect.
- At most one automatic reconnect per user join (`retryUsed` survives reconfiguration).
  Hints are memory-only and bounded (256 entries).
- A pending retry is re-checked when it fires; native offers and disconnects cancel it.
- No terrain is accepted before a handshake. Late frames are bound to their inactive
  session.

**Native wins without overlap (I3)**
- DH is retired before the native manager is built.
- A stale-connection SessionConfig is dropped.
- With Use DH off, native wire behaviour is unchanged apart from the pre-existing DH
  channel advertisement.

**Settings**
- Old YAML with `profile`/`refresh_seconds` parses and is preserved byte-for-byte
  through a Sodium edit. Misspelled keys are still rejected, and no path wipes user
  config.
- Pages run General → Distant Horizons → Far Players, with three receive-gated
  checkboxes. There is no `EnumSpec`, so no protocol selector.
- The legacy and modern twins are identical apart from package or loader metadata.
- The NeoForge stub signatures match the real Sodium 0.8.13 API.
- Lang keys and `%s` counts agree across all three locales and both loaders.

**Sync on load**
- Debounce state lives on the Session, so a portal re-checks all receipts in range.
- Toggles and server config flips count as a new load.
- Concurrency is at most 2 and at most `syncRate`, with 500 ms pacing: no join flood
  and no stationary polling.

**Live updates**
- Server checks: world key, detail 6, border, and `min(range, updateDistance)`.
- Only complete columns are delivered, with authority re-checked at handoff; unknown
  columns are preserved.
- A push removes the cache entry and cancels competing requests.
- Fences are bounded (8192 × 512 B, leased). Older or equal full replies cannot replace
  pushed columns.

**Border pump geometry**
- `getChunk(...,FULL,false)` never loads terrain. The backlog gate reads Voxy's real
  count, and maps are bounded and weak.

**Ops**
- The lock is held by the manual-server bash plus `owned-process`, and the java pid's
  cwd and argfile match.
- The README uses only cooperative-stop wording.

## Qualification gaps

**Automatic selection**
- **No Fabric client cell for automatic selection** (D-1 would have been caught by
  one).
- **No producer-emitted official CloseReason exists** (D-3). The official-15 pass is
  n=1.
- **Not exercised:** Paper/Velocity throttles, transfer packets, Realms, proxies with
  backend family changes, slow official-16 servers, dimension change during a DH
  session (D-5), the reconfiguration path, and a Paper native+DH-Support dual.

**Cache and live updates**
- **No session-level tests for D-2 and D-6 through D-9.**
- **Not tested:** push bursts, read-only or full cache directory, converter-version
  upgrade, and disconnect with a non-empty Voxy backlog.

**Border and settings**
- **No moving-camera border test** (D-10).
- **Live menu checks cover Sodium 0.8.12 only.** The legacy 0.6.13 page on NeoForge is
  unverified.

**Carried over and unchanged**
- Strict P2/I1: queued-write ordering and no completion acknowledgement.
- Range-128 and multi-dimension pressure.
- Windows antivirus, disk-full and reparse cases.
- Sync-disabled offline edits.
- Packaged plant-light cases.
- Complete biome, light and mip comparison.
- A matched official-client load comparison.
- Windows GPU performance.
- Other Minecraft lines and loader/Sodium cells.
- Upstream outreach.

**This review**
- No independent Gradle gauntlet (lock held by the live server; see above).

## Proposed fix order
1. D-1, D-4, D-5 (reconnect behaviour), plus a live Fabric no-service cell and a
   slow-server cell.
2. D-2, D-6, D-7 (cache admission and ordering, plus a semantics version), with the
   harness regressions.
3. D-8, D-9 (live-update resilience), then D-10 and D-12.
4. D-3 and the documentation and label corrections; D-11 supervisor grace.
5. Then a coordinated stop of the manual server and an independent full gauntlet on
   the corrected candidate.
