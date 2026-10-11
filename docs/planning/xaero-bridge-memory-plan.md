# Xaero bridge memory: why an unfocused window runs the client out of heap, and the ways to fix it (brainstorm, 2026-09-17)

Status: BRAINSTORM v1 — research folded (three read-only investigations: Xaero World Map
1.44.2 internals decompiled from the installed jar, Dynamic FPS 3.11.9 / Voxy / Distant
Horizons / the Xaero-WorldGen bridge, and our own `XaeroMapCompat` hook points). Nothing
implemented. Decision points in §6.

## 0. The measured failure

Live on the Modrinth rig (smp-26.2 client, main @ 2b2a0df7, Xaero WM 1.44.2, Voxy 0.2.18,
Dynamic FPS 3.11.9 with `unfocused.frame_rate_target = 30`, heap 6 GB, LOD 512,
`enableXaeroMapBridge = true`). Live-object censuses (forced full GC):

| time | window | live heap | `xaero.map.region.MapBlock` | loaded tile chunks |
|---|---|---|---|---|
| +100 s | focused | 2.13 GB | 11.3 M | 3,584 |
| +2 min unfocused | unfocused | 3.05 GB | 23.3 M | 10,880 |
| +6 min unfocused | unfocused | 3.79 GB | 32.2 M | 13,056 |
| +95 s after refocus | focused | 3.17 GB | 19.7 M | 16,768 |

Growth ≈ 2.3 M blocks/min (≈ 90 MB/min) in both phases; **zero release while unfocused**;
12.5 M blocks (≈ 500 MB) released within 95 s of refocus while writes continued. The earlier
session's `OutOfMemoryError: Java heap space` at 11 min (first on Xaero's region loader
thread) is this curve reaching 6 GB. LSS's own queues held 39 columns at the time — our
backpressure signal was structurally blind. Voxy's executor then spun the render thread
forever (`PerThreadContextExecutor.shutdown` waits for a counter a worker killed by an
`Error` never decremented), which is why the game hung instead of dying.

## 1. The mechanism, consolidated (evidence in the three reports; class:line = decompiled 1.44.2)

**Fill is tick-denominated.** Our pump runs on `ClientTickEvents.END_CLIENT_TICK` (20 Hz),
commits up to 64 columns per pump within a 2 ms budget, and each committed column allocates
**256 `MapBlock` objects (40 B each)** into a `MapTile` → `MapTileChunk` → `MapRegion`. A
fully populated region is 262,144 blocks ≈ **12 MB of source data**; post-release residency
is ≈ 1 MB heap + 1 MB direct + 1 MB VRAM (12:1 — the whole OOM is source data).

**Release is frame-denominated.** `MapTileChunk.clean()` is the only routine that frees
tiles, and its only steady-state caller is `LeafRegionTexture.postUpload`, called only from
`MapProcessor.onRenderProcess:486`, which runs only from `GameRenderer.extract` RETURN —
once per **rendered** frame. `postUpload` cleans a tile chunk iff all of:

```
region.loadState >= 2
&& (loadState == 3 || (!region.isBeingWritten() && timeSinceVisit > 1000 ms))
&& !region.isRefreshing()
&& !tileChunk.toUpdateBuffers
&& tileChunk.loadState != 3
```

The blockers, in the order a fix can attack them:
1. `isBeingWritten` — cleared **only** by the 60 s save on Xaero's processor thread
   (`MapSaveLoad.updateSave`, ≤ 3 regions per 40–100 ms pass). Cannot be cleared by us:
   the saver throws `"Saving a weird region"` on a queued region that reports false.
2. `timeSinceVisit > 1 s` — **we defeat it**: `registerVisit` on every commit, and
   `keepOwedRegionsVisited` at 20 Hz on every region holding an owed rebuild (pinned by
   `owedRegionsAreKeptVisitedSoXaeroCannotParkThem`; added so Xaero cannot park a region
   whose texture we still have to rebuild — the retained-texture bug).
3. `isRefreshing` — `handleRefresh` runs one region per processor loop and re-arms
   `toUpdateBuffers` on all 64 tile chunks after any save that found the cache unprepared.
4. `toUpdateBuffers` — cleared only by `preUpload` **inside the per-frame upload budget**.

Then `onProcessingEnd` (loadState 4, render thread) is the only thing that makes a region
evictable by `MapLimiter.applyLimit` (processor thread, cap `max_loaded_regions` default
300, min 50, max 1000). The map GUI never triggers release; `regionsFitOnScreen` only raises
the cap. There is no focus check anywhere in Xaero.

**Dynamic FPS** wraps `Minecraft.renderFrame` and skips it outright below 15 FPS targets;
the shipped defaults are **1 FPS unfocused, 0 FPS invisible** (this rig's 30 is unusually
generous). The client tick loop is untouched in every state, so our fill continues at full
rate while Xaero's release runs at 1 Hz or not at all. Several per-frame quotas
(`RegionTexture.timer`, `GpuObjectDeleter` 5/frame, one `uploadTextures` per frame,
`FRAME_MAX_REBUILDS = 1` in our own frame flush) do not scale with frame time, so even at
30 FPS regions-released-per-second falls with FPS. Dynamic FPS exposes no API; only the
all-or-nothing FREX `flawless_frames` token disables it.

**The native writer never has this problem** because it is frame-driven too: writes and
releases throttle together, and its window is `loadDistance ≤ 32` chunks ≈ 4–9 regions
(~110 MB worst case). An external writer covering a 1024-region disc at LOD 512 inverts both
properties.

**Our bridge amplifies it** in three places: (a) `setBeingWritten(true)` on every touched
region, never cleared (and not clearable, see 1); (b) the visit latch (2), which becomes
permanent when rebuilds (1 per frame) cannot keep up with commits (64 per tick) and the
owed set fills; (c) the owed set re-requests loads for up to 256 far regions for 10 min
(`probeOwed → waiting → grantLoads`), a residency amplifier exactly when memory is scarce.
`PendingUpdate` entries also hold strong `region`/`tileChunk` references (≤ 1024).

**Neighbours**: Voxy caps its section cache by a hard-coded constant (2048 × 256 KB = 512 MB
on ≥ 4 GB heaps), has an **unbounded** ingest queue, GPU uploads that are hard frame-gated,
an FPS-keyed quality auto-balancer that ratchets and persists under a frame cap, and two
shutdown-spin bugs (`PerThreadContextExecutor.run` catches `Exception`, not `Throwable`;
`UnifiedServiceThreadPool.workerThread` acquires outside `finally`). Distant Horizons has no
memory-pressure code at all (its `LodUtil.checkRamUsage` is dead code); its backpressure is
admission gating on proxies (queue depth, camera speed). The Xaero-WorldGen bridge
(archived, single-player) never touches `MapWriter`: it feeds synthesized chunk NBT to
Xaero's world-save reader and relies entirely on Xaero's limiter — no memory logic.

## 2. Design constraints (from the bridge plan §14–§16 and the crash list)

- Main-thread-only for every Xaero call; extraction stays on the decode thread.
- Lock order `renderThreadPauseSync → [mainStuffSync] → writerThreadPauseSync → region`;
  `queueLock` and `owedLock` are leaves; never take `MapProcessor.this`/`uiSync`.
- **Never** `setToUpdateBuffers` (the saver crash), **never** `setBeingWritten(false)`
  (the "weird region" throw), **never** clear `isRefreshing`, **never** `updateBuffers`
  after `requestCache` (the cache-not-prepared crash — the `isResting` gate stays),
  **never** `shouldAllowAnotherRegionToLoad`/`setNextToLoadByViewing` (deadlock / veto).
- New reflective handles bind optionally and fail **open** to today's behaviour.
- Every Xaero call is contained; `Error` rethrows; the 5-throw death latch stays.
- Harness clients are property-gated; nothing here may shift soak baselines.

## 3. Approaches

### A. Residency-bounded writing (a bridge "open region" window)  — **recommended core**
Keep a bridge-local set of regions we have written that Xaero has not yet parked (loadState
still 2, observed via the already-bound `getLoadState` under the region monitor, probed ≤ 8
per pump like the owed set). Cap it at `N` (default ~32 ≈ 400 MB of source data). When the
set is full, **do not commit into new regions**: entries stay queued, occupancy rises, and
the existing §12 report turns that into the taper and the halt. The server's hybrid walk is
already region-major, so the fill front normally needs 1–2 active regions plus stragglers;
the cap binds only when Xaero's drain falls behind, which is exactly the OOM condition.
- Pro: no new Xaero surface; bounds heap to ≈ N × 12 MB regardless of frame rate; expresses
  "the map may pace the fill" in the vocabulary that already exists.
- Con: at 1 FPS unfocused the download effectively pauses — which is correct, but the
  §12 wedge time-box ("never stop") must recognise a residency halt as legitimate.
- Effort: moderate (bridge-local state, one probe path, the report term, pins).

### B. Measure Xaero's real drain instead of inferring it
Bind two more members: `LayeredRegionManager.loadedCount()` per dimension and the
`toProcessLevels` sizes. Report backlog when `loadedCount` climbs toward
`max_loaded_regions` while `toProcess` stays non-empty.
- Pro: the true bottleneck signal, frame-rate agnostic.
- Con: more internals to drift; counts native regions too; must fail open. Complements A
  (A bounds what *we* open; B sees what Xaero holds for any reason).

### C. Post-collection heap backstop  — **recommended belt**
A 1 Hz sample on the client tick of the old generation's `MemoryPoolMXBean.getCollectionUsage()`
(after-GC occupancy — instantaneous usage is meaningless on G1); report the full halt
backlog above ~80 % of max, resume below ~65 %; `heap=` in `/lss diag`; kill switch
`enableHeapBackpressure`. Consumer-agnostic: also covers Voxy's unbounded ingest queue and
any future consumer.
- Pro: small; the first mod in this stack to actually notice memory; catches what A/B miss.
- Con: G1-specific pool naming (ZGC/Shenandoah differ — resolve by pool type, fail open);
  throttles LOD for memory another mod allocated (arguably right); needs a conservative
  default and a soak-pinned boundary so it never fires on a healthy heap.

### D. Stop defeating Xaero's park conditions
(i) Bound the keep-visited set: visit only regions whose rebuild will run within the next
few pumps (the frame budget tells us how many), and let the rest park — a parked region's
pending rebuild becomes an owed re-serve, which the owed machinery already handles.
`owedRegionsAreKeptVisitedSoXaeroCannotParkThem` is re-stated as "the next K are kept
visited", not deleted. (ii) Make the rebuild budget match commits when frames are scarce:
the tick flush already runs a borrow-topped budget when no frame ran; extend it to run when
frames are *rare* (EWMA of frame intervals), so `pendingUpdates` drains at ~20 Hz instead of
1/frame, and the visit latch releases sooner. (iii) Gate the owed set's load re-requests on
A's window so far regions are not reloaded while memory is tight.
- Pro: attacks the two blockers we own; no new surface.
- Con: (i) re-opens the retained-texture hazard the latch fixed — the owed re-serve must be
  proven to repaint a parked region correctly (live gate).

### E. Actively release regions the fill front has left
E-lite: Xaero's own idiom `region.setLastSaveTime(now − 60000)` marks a region "save me
now"; applying it to regions we finished writing shortens the `beingWritten` window from
60 s to the next processor pass (≤ 3 regions per 40–100 ms), so `postUpload` can clean on
the next frame instead of a minute later. E-full: after the save lands (observe
`isBeingWritten() == false` and not refreshing), call `MapRegion.clearRegion(mapProcessor)`
on the main thread — the one self-contained wholesale release that does **not** need a
rendered frame (GL deletion is deferred to Xaero's own frame-drained deleter, small).
- Pro: the only approach that repairs the fill/drain asymmetry itself; E-lite is cheap.
- Con: E-full is the riskiest thing on this list: preconditions (not in `toSave`,
  `regionsToCache`, `toRefresh`; saved) are internal state read across threads; a wrong
  call silently discards unsaved tiles or trips the crash handler; version drift.

### F. Offline region files (write Xaero's `.zip`/`region.xaero` directly)
Bypass memory entirely: write region files on disk and let Xaero load them lazily on view
(`detectRegions` at session start / dimension entry; `requestLoad` for live ones).
- Pro: memory bounded by Xaero's own policy; the map fills "for free" on the next visit.
- Con: a private, palette-encoded, `minorSaveVersion`-gated binary format with no
  negotiation — a wrong byte produces corrupt tiles, not an error; fights the `.zip.temp`
  swap and the map file lock; Xaero bumps the version freely. Not v1; a research item.

### G. Frame-scarcity / focus proxy
EWMA of our own `frameFlush` interval (never `Minecraft.getFps()` — Voxy's auto-balancer is
the cautionary tale): below ~20 FPS scale the report toward the halt; or read GLFW focus
directly. Cheap; catches the default-1-FPS/0-FPS cases where nothing can drain; blunt (fires
on weak GPUs and shaders). Tertiary term only, config-gated, with a diag token.

### H. Consumer-death latch + upstream fixes
Wrap `VoxyCompat.rawIngest` and the bridge's Xaero calls in a `Throwable` catch that arms a
per-consumer degraded latch (fail-stop: report the halt, stop feeding that consumer for the
session, surface in `/lss diag`, clear only via `/lss reset`). Upstream/Foxy: Voxy's two
`finally`-decrement fixes and the FPS-ratchet report; recommend `-XX:+ExitOnOutOfMemoryError`
in the README's troubleshooting section so an OOM ends the game instead of hanging it.

### I. Ask Xaero
File the trace upstream: run `postUpload`/`clean` on the processor thread when frames are
scarce, or expose an external-writer API with a residency budget. Long-term; no v1 dependency.

## 4. Recommended program

| phase | content | why first |
|---|---|---|
| **P0 observability** | `xaero.*` exporter group; `heap=`, `open_regions=`, `fps_ewma=` diag tokens; the jcmd sampler pattern as the live gate (heap + `MapBlock`/`MapTileChunk` counts every 15/90 s) | nothing below can be accepted without it |
| **P1 belts** (small) | C heap backstop; H death latch + upstream reports; README note | stops the OOM class for every consumer today |
| **P2 the fix** (moderate) | A open-region window + D(i)/(iii) bounded visits and owed loads + G frame-scarcity term, all composed inside `reportBackpressure`; the §12 wedge recognises a residency halt | bounds Xaero source data to N × 12 MB regardless of frame rate; the download paces to the map's real drain |
| **P3 throughput** | E-lite (`setLastSaveTime` idiom) + D(ii) rebuild budget under frame scarcity; A/B on the rig | the map keeps filling while alt-tabbed instead of merely not crashing |
| **P4 research** | B drain measurement; E-full `clearRegion`; F offline files; I upstream | each its own note |

Acceptance for P2 (live rig, tracer-style): 15 minutes unfocused at Dynamic FPS's shipped
default (1 FPS) with a 6 GB heap and the bridge on at LOD 512 — no OOM, live `MapBlock`
count plateaus at ≈ N × 262k, the download resumes and the map completes within a few
minutes of refocus, `stamp_heal.sh`/`warm-rejoin` unchanged with the bridge off (harness
clients are gated). Pins to re-state: `owedRegionsAreKeptVisitedSoXaeroCannotParkThem`
(bounded form), the §12 scaling/wedge tests, `describeRendersTheHouseStyle`,
`ClientOptionCatalogTest` if a row is added, the exporter contract triplet.

## 5. What this is not

- Not a dev-build regression: any LSS version with the bridge on will do this; 0.13.1
  survived the same load earlier that day because the player stayed focused.
- Not a Xaero bug in the strict sense: its release is frame-driven by design and its own
  writer is too. The asymmetry is ours.
- Not fixable by "pause the bridge when unfocused" alone: users deliberately backfill while
  alt-tabbed, and the bridge's queue would just shed to the owed set.

## 6. Decision points

1. Default `N` for the open-region window (32 ≈ 400 MB? derive from max heap?).
2. Heap backstop thresholds (80/65 %?) and whether it is on by default on all lines.
3. Whether E-lite ships in P2 or waits for a measured P3.
4. Whether to pursue E-full at all given the crash surface.
5. Whether the bridge's default stays off until P2 lands (it is off today; the README
   already warns it is a full re-download).
