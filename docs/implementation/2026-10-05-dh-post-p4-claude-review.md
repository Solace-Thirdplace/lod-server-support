# Claude Code review: DH changes since the P4 review (source cache, Voxy reuse, lighting, P4 fixes)

Date: 2026-10-05. Briefing: `2026-10-05-dh-post-p4-claude-review-briefing.md`.

The user asked for a critical review that is not limited to the briefing's
framing. Five passes ran:

- **Opus A:** the persistent source cache.
- **Opus B:** Voxy destination reuse and the reconnect handoff.
- **Opus C:** policy and scheduler, the protocol-15 normalization, water light, and
  the P4 disposition.
- **Fable D:** an adversarial evidence audit and the open I1 boundary.
- **Coordinator:** a full normal Gradle gauntlet plus checks of rig and lock state.

This review edited, committed, installed and published nothing. It made no upstream
contact and changed neither Windows nor Prism. Earlier reports are preserved.

## Bottom line

The production increment builds and passes every normal gate. The P4 corrections
are real, the protocol-15 ceiling rule is exactly scoped, and Voxy reuse produces
**no false match**: comparison semantics were checked against the pinned port's
bytecode.

The new reconnect cache, however, has a design-level problem and several
demonstrated defects that undermine its headline claims.

- **H-1 (design, I1):** restore rebuilds any Voxy column that differs from the DH
  approximation. This overwrites terrain refined by vanilla in a previous session
  on every reconnect, offline and with no server gate. Vanilla authority does not
  survive disconnect.
- **H-2:** exceeding the quota by one tile drops cache reuse to zero, permanently.
  This happens at max official range or with two dimensions.
- **M-1:** a superseded cached tile survives newer downloads. Rejoin then writes
  stale terrain back into Voxy, permanently when sync is off. Three reviewers found
  this independently.
- **M-2:** stale content is enqueued before validation. Reconnect checks also wait
  behind the entire restore sweep, estimated at tens of minutes at range 128.
- **M-3:** "zero payloads on rejoin" is target-scoped only. Partial tiles are never
  cached and are re-downloaded every join; in the final run that was 13 full tiles
  per rejoin.
- **The manual server is dead.** The setup receipts and the manual guide still say
  "ready".

The strict P2/P4/I1 gates remain open, and nothing in this increment closes them.
H-1 enlarges their practical impact.

**Readiness.** The candidate is fine for supervised manual play. It should not be
described as delivering "only changed terrain on rejoin" or as preserving explored
terrain until H-1, H-2 and M-1 are addressed.

## What was independently run vs inspected

### Coordinator, run

- **Diff and manifests.** `git diff --check` is clean. The untracked inventory
  matches the briefing.
- **Rig state (read-only):**
  - The briefing's manual-server pid 100350 no longer exists, nothing listens on
    `[::1]:25585`, and the harness lock `/tmp/lss-harness-1000/port-25565.lock` is
    held by nobody.
  - `voxy-cache-reuse/manual-server.log` ends at 15:43:24 with a jline `stty`
    `IOError` and a JVM shutdown, with no "Stopping server" line.
  - Because the rig was idle and unlocked, the full gauntlet was run on it (below).
- **Full versioned gauntlet.** Source was a fresh rsync of the current clone,
  including untracked inputs and excluding `.git`, `.gradle`, `build`, `logs` and
  `.fabric`, into `/tmp/lss-dh-postp4-claude-build.bdinrZ`. Settings: Java 21,
  `CI=true`, and a distinct review version, `-Pmod_version=0.16.0-dh-claudereview.20261005`.
  - Command: `run-gradle.sh --no-parallel :common:test :fabric:build :paper:test :paper:shadowJar :neoforge:build :neoforge:runGameTestServer vssJars`.
  - Result: **BUILD SUCCESSFUL**.

  | Suite | Passed | Failed | Skipped |
  | --- | --- | --- | --- |
  | common | 1005 | 0 | 0 |
  | Fabric | 1715 | 0 | 4 (opt-in tools) |
  | Paper | 536 | 0 | 0 |
  | NeoForge | 23 | 0 | 0 |
  | **Total** | **3279** | 0 | 4 |

  - Fabric gametests: "All 78 required tests passed". NeoForge gametests: "All 8 required
    tests passed".
  - `release_check.py --version 0.16.0-dh-claudereview.20261005`:
    `OK: release artifacts clean`, with LSS and VSS jars for Fabric, Paper and NeoForge.
  - `release_check.py --selftest`: 126 cases OK.
  - The `exportDhFixtureClasspath` tasks were not run. Fixture compilation and the
    private live rejoin were not rerun.

### Reviewers, run (scratch only)

All harnesses ran against the compiled classes of the latest tested build copy
`/tmp/lss-dh-voxy-cache-20261005`. Its relevant sources hash-match the checkout.

- **Opus A:** a cache harness (7 cases), a ring-order cascade harness, a
  two-dimension harness, and a brute force of the address hash.
- **Opus B:** `javap` of the pinned Voxy port (`024d5807…`) across `WorldEngine`,
  `ActiveSectionTracker`, `WorldSection`, `Mapper`, `VoxyInstance`, `WorldUpdater`,
  `VoxelIngestService`, `ClientSessionEvents` and `MixinMinecraft`; an `onMain`
  open/close race harness (200k iterations); a reflection-cost microbenchmark.
- **Opus C:** policy simulations (restart starvation, a 4225-tile frontier,
  interval flips, revalidate sequences); a decode and run-pair analysis of the
  frozen ocean DTO.
- **Fable D:** hash recomputation of every candidate, fixture and Voxy jar in the
  final runs; an XML recount; parsing of `verification`, `live-matrix`,
  `successful-live-cases` and `dh-requests.jsonl`; a world and DH SQLite mtime
  survey.

### Not run

No private live rejoin, matrix or ocean run against this review's build. No
Windows or NTFS test. No benchmark at range 128.

## Verified findings

### H-1 HIGH (design / I1): restore overwrites previously vanilla-refined Voxy terrain on every reconnect (Fable D; coordinator confirmed the code)

**Where:**
- `DhConnectionState.java:45-47`: `disconnect()` calls `policies.clear()`, so the
  vanilla-held set is per connection and in memory only.
- `DhClientSession.java:555`: the authority test is `policy.eligible(x,z) && !loaded(level,x,z)`.
- `:558-572`: when the reuse comparison fails, the code falls through to convert and
  then ingest (`:573-593`).
- `DhTerrainCache` persists nothing about vanilla ownership.

**Trigger:**
1. In session 1, the player explores an area. Voxy ingests the exact vanilla chunks,
   and the DH tile for that area is cached.
2. The player disconnects.
3. In session 2, `restoreCache` compares Voxy against the DH approximation. Caves,
   block simplification and light differ, so all differing columns are rebuilt from
   DH.

**Consequence:**
- Explored terrain degrades to the DH approximation on each reconnect, offline,
  before any server timestamp check.
- The pre-cache network path already re-downloaded and overwrote those columns each
  join (the original review noted the missing §7 warning). The new path keeps that
  behaviour and makes a *mismatch* the rebuild signal, which is exactly when Voxy
  probably holds better data.
- The docs' "conservatively rebuilt" is the wrong direction with respect to I1.
- The manual guide's step 2 → 3 (fly the course, reconnect) is precisely this
  sequence.
- Restore starts at ring 0, where vanilla chunks are also arriving, which is the
  worst place for the open commit gap.

**Coverage:** no test or live stage visits a target with vanilla and then reconnects.
`RejoinProbe` deliberately keeps targets outside vanilla.

**Fix (pick one):**
- Persist a per-column vanilla-held bitmap keyed like the DH cache (server, DH world
  key, dimension; 1 bit per column) and consult it at `:555`.
- Or rebuild on restore only when the Voxy section is default or missing, or when
  the server reports the tile changed, never merely on a content mismatch.

Either way, add the §7 overwrite warning to the docs.

**Regression tests:**
- A session test that loads vanilla in connection 1, reconnects, has a fake reader
  report a mismatch, and asserts zero ingests.
- A `RejoinProbe` stage that visits the target with vanilla before disconnecting.

### H-2 HIGH: quota overflow by one tile disables reuse permanently (Opus A, demonstrated by harness)

**Where:** `DhTerrainCache.java:132-149` (`trim` evicts the oldest mtime), together
with the ring-ordered restore in `DhClientSession.java:395-411` and `:258-285`.

**Mechanism:**
1. Restores and unchanged replies never refresh an entry's mtime, so the oldest
   files are the current world's earliest, nearest tiles. Those are exactly what the
   next ring-ordered rejoin needs first.
2. Each near-tile miss is downloaded and written.
3. That write evicts the next tile the scan was about to restore, and so on.

**Evidence** (cascade harness against the real cache):

| Quota (tiles) | Scenario | Restored | Re-downloaded |
| --- | --- | --- | --- |
| 121 | 121 tiles | 121 | 0 |
| 120 | 121 tiles | 0 | 121, every session |
| 150 | overworld 121, then nether 49, then overworld rejoin | 0 | 121 |

**Reachability:**
- One dimension at official range 128 spans 4096–4225 tiles against a quota of 4096.
- Two dimensions share the global quota.

**Cost:** each over-quota write rescans the directory: about 34 ms on WSL ext4, and
79 s of worker time to fill 4225 tiles. NTFS with Defender is unmeasured and likely
much slower.

**Fix:**
- Make eviction true LRU: touch the entry on restore and on unchanged replies.
- Evict other buckets, and tiles far from the player, before the active working set.
- Raise the count quota to at least 4225 plus headroom.
- Keep an in-memory quota index instead of rescanning.

**Regression test:** the single-bucket and two-bucket cascade harnesses, asserting
restores continue after a one-tile overflow.

### M-1 MEDIUM: superseded cached tiles survive and roll Voxy back on the next rejoin (Opus A demonstrated; Opus B and C independently)

**Where:** `DhClientSession.java:505-508`. The cache is written only for a complete
tile with a successful write. Nothing removes the older file when:
- the write throws (Windows AV or indexer lock, disk full, read-only directory);
- the newer reply is partial;
- the server reports the tile absent (`:466-479`).

**Consequence:** the next rejoin restores the older tile. Voxy holds the newer
content, so the comparison mismatches and the stale source is ingested over it.
With sync on, the rejoin check heals this later, after a visible regression. With
sync off, which the docs say reuses the cache without checks, it is permanent.

**Evidence:** after a newer write fails with `AccessDeniedException`, the cache
still returns the old entry (`timestamp=5`).

**Fix:** invalidate the entry whenever a received tile for that position is not
successfully cached.

**Regression test:** a session test that forces a write failure or a partial newer
tile, then asserts the position is gone from `positions()`.

### M-2 MEDIUM: restore-before-validate ordering, and reconnect checks starved behind the restore sweep (Fable D, Opus B, Opus C)

**Stale content is enqueued before validation.**
- `DhClientSession.java:266` delivers, and so ingests differing columns, before
  `:269-272` schedules the conditional check.
- A changed reply then ingests the new content (`:509`).
- Voxy's ingest queue has no per-position order (the commit-gap mechanism). An old
  write committing last therefore leaves stale terrain for the session; with refresh
  Off nothing heals it.
- Even when ordering works out, this causes flicker and wasted ingest on every
  changed tile.

**Reconnect checks wait behind every restore.**
- Cached, receipt-less tiles are priority 0, and `requestNext` returns while any
  restore is in flight (`:408-411`).
- Restores serialise at no more than one per 500 ms scan. Each costs about 34
  main-thread round trips plus up to 16 × 16 Voxy section acquires and 256 KiB
  copies.
- Each 32³ section is copied four times, once per chunk column, so about 192 copies
  (≈48 MB) per tile.
- Reconnect checks are priority 1, so offline edits near the player stay stale until
  every in-range cached tile has been restored.
- Estimate (unmeasured): roughly 35–40 minutes at about 4096 tiles. New uncached
  terrain outside the cached rings waits as well.

**Fix:**
- Validate first: send the conditional request with the cached stamp. On "unchanged",
  restore and compare from the cache. On "changed", deliver new content and replace
  the cache entry. This is cheap on official servers and matches official DH's
  sync-on-load order.
- Use one main-thread hop per tile, and reuse each section copy across its four
  columns.

**Regression tests:**
- An actual-scanner test that drives a restore through `requestNext` (none exists;
  `warmSourceReplay…` calls `request(...)` directly).
- A time-to-first-check bound with N cached tiles.

### M-3 MEDIUM (overclaim + efficiency): partial tiles are never cached and are re-downloaded every join (Fable D, from the final run's own data)

**Where:** `DhClientSession.java:505` (`completeTile` only).

**Evidence:**
- NeoForge `attempt-c4dbrvhg/.../dh-requests.jsonl`: every rejoin phase contains
  **13 changed full (ts=0) replies, 17 empty full replies and 2 conditional ones**.
  The instance cache holds 2 `.dht` files in total.
- Official-16 service run: `incompleteTiles: 12`, `unknownColumns: 30720`. Partial
  tiles are routine at the LOD edge.

**Overclaims:**
- `2026-10-05-dh-voxy-cache-reuse.md:66-68` says "zero target terrain payloads,
  zero conversions and zero ingests" without stating the target scope; session-wide
  counts were not zero.
- `2026-10-05-dh-manual-test-guide.md:45` says "downloads full terrain only when
  changed or missing". That is false for partial tiles.

**Fix:** cache partial tiles with their coverage mask and restore them with the
same partial semantics. At minimum, correct the wording in both documents.

### M-4 MEDIUM: changing the refresh interval cancels or defers outstanding reconnect checks (Opus C, demonstrated by simulation)

**Where:** `DhSessionPolicy.java:43-47`, `:55-61`. `revalidate` makes a receipt due
now but leaves no marker, so `configureRefresh` treats it as an ordinary active
snapshot.

**Evidence:**
- Refresh Off, then accepted, then revalidate, then switching to 600 s: `due=false`,
  deferred to 610 s.
- Refresh 600 s, then revalidate, then Off: `due(MAX)=false`. The restored,
  possibly stale tile is never checked again on this connection.

Combined with M-2's long restore backlog, any Sodium refresh change during it
silently drops the "checked even with refresh Off" guarantee.

**Fix:** add a `revalidate` flag to `Receipt`. `configureRefresh` should keep it due
and unheld; `accepted`, `cooldown` and `unchanged` should clear it. Add the two
sequences above as regression tests.

### M-5 MEDIUM: manual rig state is wrong and its lifecycle is fragile (coordinator + Fable D)

**Facts:**
- pid 100350 is gone, there is no listener, and the lock is unheld.
- The log ends at 15:43:24 with the jline `stty` EIO and no stop or save lines.
- Fable's file evidence suggests the shutdown hook still saved: `level.dat`, region,
  entities and data were rewritten at 15:43:25.1–25.4, 52 s before the next
  autosave.
- `DistantHorizons.sqlite` was last modified at 13:53 with no WAL or SHM files, so
  it is consistent. No players had been online since 15:25.

**Cause:** `start-server.sh:9-14` binds the JVM's stdin to the launching console
session (`<&0 &`, no `setsid` or detached stdin). When that session's pty closed,
the JVM died.

**Stale artifacts claiming "ready":**
- `voxy-cache-reuse/test-setup-completed.json`
- `verification.json.manual_setup`
- `2026-10-01/server-owner.json`
- `2026-10-05-dh-manual-test-guide.md:11-13` ("the server is running")

**Fix:**
- Launch detached (`setsid`, stdin from a FIFO or `/dev/null`, plus RCON or a
  console FIFO).
- Have receipts state liveness at read time.
- Add a "verify the server is up" step to the guide.
- The world and DH database can be resumed as they are.

### L-1 LOW-MEDIUM: kelp and seagrass seabeds stay too bright; water attenuation only covers `Blocks.WATER` (Opus C, demonstrated on the frozen ocean tile)

**Where:** `DhColumnConverter.java:64-65`.

**Evidence** (frozen ocean tile):

| Run type over a floor | Matches DH's floor-light oracle |
| --- | --- |
| Water | 5923 of 5923 |
| Water-containing non-water runs (kelp, kelp_plant, seagrass, tall_seagrass), current code | 1224 pairs; **380 too bright**, by up to 6 levels for kelp_plant |
| Same pairs with one level of loss per voxel | 1224 of 1224 |

**Gaps:** the unit test and `OceanLightProbe` both skip non-water runs, and the
user's visual confirmation covers open-water floors only.

**Fix:** attenuate any state with a non-empty fluid state, or more generally any
non-air state with light-block ≥ 1. `matchesCache` shares `sky()`, so stored data is
rebuilt once.

**Regression test:** generalise the corpus test to every non-air run sitting on a
floor. It fails on 380 pairs today.

### L-2 LOW-MEDIUM: transient failures during restore discard a valid cache entry (Opus B, Fable D)

- **Budget limit read as corruption.** `DhCodecException(RESOURCE_LIMIT)` is an
  `IOException`. It escapes the verification catch (`DhClientSession.java:538/560/574`)
  and reaches `restoreCache`'s corrupt-file branch (`:279-281`), which deletes a
  valid file. Columns already enqueued in that tile are then delivered twice.
- **Timeout drops the tile from the cache.** A 5 s main-thread `TimeoutException`
  during restore (`:277-278`) leaves the position removed from `cachedPositions`
  (`:259`). The "cached terrain retry scheduled" retry therefore becomes a full
  ts=0 network request. Join hitches are exactly when restores run.
- **The 60 s restore deadline is terminal.** `cacheWorkStarted` (`:367-369`) ends
  the session, while every other per-tile timeout only cools the tile down.
  Thirty-four hops each just under 5 s already exceed it.

**Fix:** treat a resource limit or a main-thread timeout as a transient tile
cooldown that keeps the file and re-adds the position. Make the restore deadline
per tile, not terminal.

### L-3 LOW: identity and file-hygiene gaps in the source cache (Opus A, demonstrated)

**Seed 0 accepted as identity.**
- `DhClientSession.java:244-245` checks only `seedAvailable()`, whereas native
  `WorldSubKey.java:124` treats 0 as ambiguous.
- On a shared address with identical or empty DH keys, a tile from world A can
  restore into world B; with sync off, without a check.
- Fix: add `|| seed.seed()==0L`.

**`.pending-*.tmp` leaks.** `DhTerrainCache.java:96-105`: after a crash or a failed
cleanup, temporary files of up to 16 MiB stay forever. They are not in the quota
and are not swept. Sweep stale pending files and count them toward the quota.

### L-4 LOW: reuse counter double-counts (Opus B)

`DhClientSession.java:567-569` increments `cacheReusedColumns` even when the second
authority check fails and the column is counted as `skipped`. Increment it only in
the matching branch.

## Suspected risks (not demonstrated)

**Cache corruption and clearing**
- **A checksum-valid but undecodable entry fails every session** until `/lss reset`.
  `restoreCache` catches the error with `catch(Exception)`, calls `fail`, and keeps
  the file. Version 1 does not bind the decoder or limits version, so a future,
  stricter decoder would turn old entries into permanent failures. Remove the entry
  on decode failure and put a decoder version in the key.
- **`/lss clearcache` in DH mode clears every server.** No native request manager
  exists, so the code falls into `ColumnCacheStore.clearAll()`. The user feedback
  says "all servers", but the docs say "current".
- **`clearAll` composition:**
  - a symlinked `dh-terrain-v1` makes the DH clear throw before any native bucket is
    cleared;
  - `deleteBucketDir(dh-terrain-v1)` removes foreign top-level files, contradicting
    "foreign-file preservation";
  - a foreign subdirectory aborts the loop part-way.
- **Single-player with DH selected:** `getCurrentServer()` is null, so
  `invalidateCurrentNativeCache` throws in `drainForReset`, and reset and clear fail.
- **A late write from a previous session can recreate data after a clear.** A
  previous session's `retire()` result is ignored. If its worker is blocked for more
  than 2 s after its `active` check (a slow trim, or a move blocked on Windows), it
  can write after the clear.
- **Timestamp-0 tiles are never re-checked.** They are restored, but the reconnect
  check is gated on `refreshTimestamp>0`, so with refresh Off they are never
  checked. A negative-timestamp write silently no-ops but still counts in
  `cacheWrites`.

**Privacy and filesystem**
- **Unsalted address hash.** `[::1]:25586` was recovered from the evidence bucket
  name in milliseconds. This is not a regression (the native cache uses plaintext
  names), but the docs' privacy wording overstates it.
- **Symlinks, junctions and namespace collisions:**
  - a symlinked `.lss/cache` disables the DH cache silently while the native cache
    follows it;
  - containment against Windows junctions is unproven;
  - `dh-terrain-v1` shares the namespace of native server buckets.

**Voxy interaction and cost**
- **Voxy shutdown freeze.** `VoxyInstance.shutdown` spins while our engine reference
  is held, and that reference spans `onMain` hops. The client can freeze for up to
  about 5 s (Voxy config menu or `/voxy` commands), after which L-2 drops the tile.
  Take and release the reference per column on the worker.
- **Untracked Voxy memory.** Reading missing sections allocates 256 KiB defaults
  into Voxy's LRU without charging the LSS budget. A storage load error is silently
  treated as missing, and logged four times per tile.
- **Client-thread mapping snapshot:** 1–5 ms per tile for 20k–65k entries.
- **Silent fallbacks.** ABI fallback and the 65,536-mapping cap disable reuse
  without any status. "Checked even with refresh Off" depends on server
  `synchronize && syncRate>0`. Without sync, restored tiles are never refreshed and
  nothing in diagnostics says so.
- **Revalidation race.** `revalidate` is posted as a separate `main()` after
  `accepted`. A session replaced in between loses the check. Revalidate inside the
  accepting `onMain`.
- **`VoxyTerrainCache.close()` can throw** in a narrow `cleanIdle` race and kill the
  session. Make it never throw.

## P4 disposition audit (Opus C; Fable D concurs where it overlapped)

| Item | Verdict | Evidence |
| --- | --- | --- |
| P4-1 | **VERIFIED** | Simulation: 25 tiles with restarts every 120 s for 1 h, and 4225 tiles with restarts every 10 min for 24 h, both give 0 never-refreshed tiles (maximum staleness one sweep). Interval flips likewise give 0. The session test fails on the old code. |
| P4-2 | VERIFIED | Range 12, settled idle, deadline sized to the sweep. Minor caveat: the deadline ignores cooldown retries during the phase. |
| P4-3 | VERIFIED (inspected) | Distinct content per stage; request ID, epoch, mask, stamp and timestamp bound together. |
| P4-4 | VERIFIED | Both mechanisms named. |
| P4-5 | VERIFIED for `ContentProbe` | Generic `Probe` still treats absent sections as air, but counts them separately. |
| P4-6 | PARTIAL (inspected) | Catch-all into `failure`, checked promptly. |
| P4-7 | VERIFIED by reasoning | The `<=` tie, the deleted ceiling, the pending filter, and the vanilla/border/range filters moved after selection would each be caught by a red test. Moving `refreshAllowed` is an equivalent mutant. |
| P4-8 | VERIFIED | Leaks removed. |
| P4-9 | VERIFIED (spot) | — |

## Evidence audit (Fable D)

**Sound**
- **Hash binding:** both final runs' `mods/` contain exactly the candidates
  (NeoForge `b69426d3…`, Fabric `d7b204f1…`), the fixtures and Voxy `024d5807…`.
  The server runs DH `864e70de…`.
- **Failed attempts:** the three failed NeoForge attempts ran the pre-fix jar
  `5b167600…` and are correctly excluded.
- **Counts:** the XML recount is 3279 and matches. The eight stages appear at the
  wire: 8 target request/response pairs, 4 full and 4 nine-byte unchanged.
  "One damaged column → 1 conversion" and "Voxy loss → 16" are hard assertions.
- **Voxy-default handling:** it was live-falsified twice before the fix, which is
  real evidence for the light packing.
- **No side effects from reading:** reads cause no persistence (sections are never
  marked dirty).
- **Race experiment:** `RaceProbe` is honest and is gated to the race mode only by
  the plugin.
- **Ocean oracle:** independent of the converter.

**Labelling issues**
- **`full_destination_content_verified` is not independent.** It is computed by the
  **production** `matchesCache` / `VoxyTerrainCache` via `DhCacheProbe`. Only the 300
  `knownBlocks` samples per stage are independent, and those check block states only
  (no light, no biome).
- **Wrong hash for the Fabric ocean row.**
  `ocean-lighting/verification.json` embeds the `fabric-ocean` row under the NeoForge
  artifact SHA `885e22e5`. The Fabric run used `01bce007…`.
- **Failed attempt not disclosed in prose.** `p2-p4-final/live-matrix.json` retains a
  failed `fabric-support` attempt ("Insufficient solid readback: 0 columns"); the
  continuation doc's prose does not mention it.
- **No session-level test drives the reuse branch** (`DhClientSession.java:558-573`).

**Checked and sound in code**
- Protocol-15 normalization: air only, exactly one voxel above the exclusive
  ceiling. Overshoot by two, solid spans and wholly-outside spans are still rejected,
  and corners are checked before the trim.
- Water attenuation for water itself.
- Stale-callback and world-key guards.
- No conditional request built from partial or failed content.
- `onMain` closes exactly once (harness: opened equals closed over 200k iterations).
- Voxy section indexing, light nibble order, quart biome and the append-only mapping
  snapshot.
- The ABI fallback never latches the ingest bridge dead.
- Header parsing is bounded before allocation, the checksum is verified before an
  `Entry` exists, and writes are atomic.

## Remaining gates

1. **H-1 design decision:** a persistent vanilla-ownership record, or "no rebuild on
   mismatch". Add the cross-session vanilla regression and live stage.
2. Fix H-2, M-1, M-2, M-4, L-1, L-2 and L-3, each with the listed regression test.
3. Re-verify live on a new candidate:
   - a rejoin beyond quota at range 128;
   - a multi-dimension rejoin;
   - a write failure;
   - an offline edit with sync off;
   - kelp and seagrass seabed light;
   - time-to-first-check with a large cache.
4. Windows/NTFS cache behaviour: atomic replace under antivirus, junctions, coarse
   mtimes, trim cost.
5. Correct the documentation: M-3 scoping, the M-5 rig state and liveness, the
   "conservatively rebuilt" wording, the address-hash privacy wording, and the Fable
   D labelling issues.
6. Still open from the plan:
   - strict P2 completion and I1 (the Voxy commit gap, no completion API), and their
     frequency in ordinary play;
   - the official-client load comparison;
   - GPU frame time;
   - a full-range reuse benchmark and derived-mip validation;
   - other lines;
   - upstream outreach.
