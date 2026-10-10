# Claude Code review: experimental DH client (Fabric 1.21.1, first playable)

Date: 2026-10-01. Requested by the user per `2026-10-01-dh-claude-review-briefing.md`.
Reviewers: 3 Opus subagents (A input bounds and ownership; B threading, authority,
reset and native regressions; C scheduler, terrain and packaging) and 2 Fable
subagents (D protocol fidelity and evidence; E adversarial end to end and play-test
readiness), plus the coordinator's own Gradle run and spot checks. Scope:
`feat/dh-codec-p1-mc1.21.1` against `6677fe93dfc3`, with the untracked plan,
evidence and dev artifact. Nothing was edited, committed, published or sent upstream.
Scratch harnesses live outside the repository, in the session scratchpad.

## Verdict

**The artifact is ready for the limited, supervised local Fabric 1.21.1 + Paper
1.21.1 / DH Support 0.14.0 play test, with the mitigations below. It is not ready
for anything beyond that.**

- No reviewer found a client crash, deadlock, netty-thread block or memory-bound
  escape.
- The real remapped jar links cleanly. There are no named-mapping leftovers, no
  stock `org/tukaani` classes and no POC classes, and the SQLite capsule is
  byte-identical to the published v0.16.0+1.21.1 release asset.
- The nested common jar decodes both frozen Support captures to their pinned
  hashes.
- With defaults, native LSS behaviour is unchanged, with one caveat: the DH channel
  is announced on the wire (see m-W1).

Pre-test mitigations, none of which needs code changes:

1. Set `lod.dh.refresh_seconds: 3600` in the prepared YAML. At the current value
   of 120, a stationary player keeps DH Support regenerating about 60 tiles per
   minute forever (D-2).
2. Keep the world name ASCII (`dh-playable` is). Non-ASCII names break Support
   requests (R-2).
3. Confirm Voxy ingest is enabled for the world before judging acquisition (R-1).
4. If `/lss dhstatus` reports "DH source stopped", record the reason and reconnect
   (R-3). This is client brittleness, not a server fault.
5. Join with an explicit `127.0.0.1:25585` first; if that fails, widen the bind.
   The server binds IPv4 loopback only, and earlier rigs needed `[::1]` from Windows.

## Checks actually run

| Check | Result |
| --- | --- |
| Java 21 `tools/verify/run-gradle.sh :common:test :common:shadowJar :fabric:test :paper:test :neoforge:compileJava` (first real Gradle run on this branch) | common **964/0**, paper **536/0**, `common:shadowJar` and `neoforge:compileJava` OK; fabric **1659 run, 30 failed, 4 skipped** |
| The 28 fabric failures from contract/source-scanning tests | **Environmental**: this clone is nested inside the outer checkout, so the source locator finds every file twice ("exists in more than one tree"). An rsync copy outside the repo re-ran those classes: 162/164 pass |
| The remaining 2 fabric failures, `DhColumnConverterTest` capture tests | **Genuine defect (D-1)**: they also fail in the isolated copy |
| Dev jar SHA-256 | `090ef71d…28e9`, matches the briefing |
| Jar linkage and contents (E, C) | `javap` constant-pool audit of 13,714 LSS member refs, intermediary-only MC refs, nested jars, 0BSD notice, private XZ, SQLite capsule equality with the release asset: all OK |
| Frozen captures decoded from the shipped nested common jar with stock XZ absent (E) | both hashes match |
| Scheduler simulations over the real `DhSessionPolicy` (C) | see D-2 and D-3 |
| Codec harness for assembly deadlines and cancel/close leases (A) | see D-4 |
| xz-java unfinished-stream probe (D) | see D-5 |
| Corpus hashes against provenance; retained patch against the tree (D) | all match; the tree differs only by the doc appendix |

Not run: live client join, rendering, Prism install, a normal `:fabric:build`
(Tier 2), the release pre-flight and `release_check.py` against a full jar set, and
anything on NeoForge.

## Findings: demonstrated defects

### D-1 MAJOR: `DhColumnConverterTest` fails under real Gradle
- **Where:** `fabric/src/test/java/dev/vox/lss/networking/client/DhColumnConverterTest.java:33,42`.
- **Cause:** the test loads `/dh-corpus/...` from the classpath. That corpus lives in
  `common/src/test/resources`, which is not on `:fabric:test`'s classpath (the
  fabric build only adds `testFixtures(project(':common'))`).
- **Effect:** both capture tests fail with "Missing capture response". The briefing's
  "88 native gate/reset/converter tests, zero failures" came from
  `/tmp/lss-dh-p1-checks/run_client_tests.py`, which adds `common/src/test/resources`
  by hand. That helper also adds `/home/vox/projects/lss-dh-poc/fabric/build/classes/java/test`,
  so the earlier result may depend on POC test classes.
- **Fix:** move the corpus into `common/src/testFixtures/resources`, or add it as a
  fabric test resource directory. Treat the helper-only results as not reproduced.

### D-2 MAJOR: Support refresh never stops for a stationary player (C, simulation; code confirmed)
- **Where:** `DhClientSession.java:207,225,309`, `DhSessionPolicy.java:31`, `SettingsSchema.java:217`
  (range 30–3600, no off value).
- **Cause:** every Support request takes the generation path. Accepted tiles come
  due again after `max(30, refresh)`. At range 32 / view distance 12 there are 220
  eligible tiles, so at refresh 120 s the client sends about **60 FullBuilder
  rebuilds per minute indefinitely**, each one re-transferred and re-ingested.
- **Plan conflict:** §7 says 0 disables refresh, and §5.3 forbids unconditional
  repeat requests as a substitute for sync.
- **Fix:** default refresh off for Support (allow 0), budget refresh below the
  request cap, and add a test bounding stationary steady-state requests per minute.

### D-3 MAJOR (official profiles only): the receipt LRU cap is smaller than the scan area
- **Where:** `DhSessionPolicy.java:41-44` (cap 4096) against range 128, which gives
  65² = 4225 tiles.
- **Effect:** in a 2 h simulation, 10,303 of 14,400 requests re-fetched tiles that
  had been evicted. Insertion-order eviction drops the near tiles first.
- **Fix:** size the cap to at least (2r+1)², or evict by distance.

### D-4 MINOR: assembly deadlines use processing time, not arrival time (A, harness)
- **Where:** `DhAssembly.java:168,176,207-211`.
- **Effect:** a worker stall longer than 10 s turns a timely response into
  `EXPIRED`, which kills the session.
- **Fix:** stamp `nanoTime` in `capture()` and carry it through `Inbound`.

### D-5 MINOR: the Support unfinished-XZ exception is wider than it needs to be (D, probe)
- **Where:** `DhTerrainDecoder.java:229,281`.
- **Cause:** for Support mode 3 the trailing-data check is skipped entirely.
  xz-java returns bytes if anything follows the flushed data.
- **Fix:** accept only EOF / `EOFException` as the end, and add a test with trailing
  bytes after a Support XZ stream.

## Findings: reasoned risks (not reproduced)

### MAJOR

**R-1: a rejecting Voxy causes an endless request loop.** Reviewers B (M1) and C (m1)
found this independently.
- **Where:** gate at `DhClientSession.java:45`, handoff at `:288-302`, cooldown at `DhSessionPolicy.java:27-30`.
- **Trigger:** `isVoxyBridgeActive()` only means Voxy initialised. `rawIngest`
  returns false when there is no world id or engine, when ingest is disabled, or
  after the DH latch `dhBridgeDead`.
- **Effect:** every tile cools down for 30 s and is re-requested forever. On Support
  each request is a generation request, roughly one per second. This breaks the P2
  "no requests without a working sink" gate.
- **Fix:** probe sink readiness before requesting, stop after N consecutive whole-tile
  rejections, and end the session on the latch.
- **Missing test:** an always-reject stub must produce a bounded number of requests.
  Never-acceptable PARTIAL/UNKNOWN columns share the same 30 s forever loop; give
  them an escalating backoff.

**R-2: the Support request string prefix is written for the wrong direction.** (D, MAJOR-1)
- **Where:** `DhProtocolCodec.java:151` writes UTF-16 units for Support, matching the
  plugin's encoder. The plugin's decoder `readShortString` reads that many **bytes**.
- **Trigger:** `Request.level` echoes `LevelInit.levelKey` (the Bukkit world name).
  A non-ASCII name gives a wrong prefix, the plugin misparses the request, two
  silent timeouts follow and the session ends.
- **Test status:** `DhProtocolCodecTest.literalUnicodeLengthsDifferByFamily:49` pins
  the defect.
- **Fix:** write a byte length for client-to-Support strings, and re-parse encoded
  frames the way the plugin does in the test.

**R-3: transient conditions are terminal, and recovery is reconnect only.** (E M1/M4, A m8, B 5/7/8, C m2)
- Any `DhCodecException` from ordered receive ends the session (`DhClientSession.java:98-99`).
  That includes:
  - UNOWNED from a stray post-reset or post-dimension-change fragment (`DhAssembly.java:47-51`);
  - a response landing just past the 60 s lifetime;
  - more than 8 incomplete buffers.
- `unanswered` never decays (`:192,197`), so two slow cold generations hours apart
  end the session.
- A failed `drainForReset` (`:108-111,145-149`) leaves an inactive session that still
  reports "acquiring terrain"; `retire()` never sets `problem`.
- **Fix:**
  - drop and cool down the owning tile for UNOWNED, EXPIRED and per-tile MALFORMED;
  - keep PROTOCOL_MISMATCH, CloseReason and RESOURCE_LIMIT terminal;
  - decay `unanswered` on successful responses;
  - set `problem` on every retire or abort path and rebuild after a cooldown.

**R-4: a DH-mode reset can leave native cache stamps behind.** (B M2)
- **Where:** `ClientCommandActions.java:82-85`, `DhClientSession.java:290,326-332`.
- **Cause:** the native caches are invalidated lazily, on the first DH ingest. A
  `/lss reset` that runs before any ingest wipes Voxy but leaves the old native stamps.
- **Effect:** after switching back to `lss`, the client re-declares with ts>0, gets
  `up_to_date`, and the holes stay permanent. This breaks I2.
- **Fix:** sweep `ColumnCacheStore.clearForServers(dhCacheSweep(ip))` in the DH reset
  branch, and invalidate once at session start, off the per-column handoff. Only mark
  success when the delete actually succeeded; today an `IOException` still sets
  `cacheInvalidated = true`.

**R-5: the vanilla-authority set is per dimension visit, not per session.** (B M3, E m5)
- **Where:** `DhClientSession.java:34-38,46`.
- **Cause:** a portal round trip nulls `authority`.
- **Effect:** DH then overwrites Voxy's exact unload-time copies of columns vanilla
  held earlier in that dimension. Plan §5.4 asks for a per-session set.
- **Fix:** key by `ResourceKey<Level>` for the connection lifetime.

**R-6 (a decision, not a defect): post-reset holes along the earlier route.** (B M4)
- `vanillaAuthoritySurvivesRefreshAndReceiptReset` pins that the vanilla set survives
  reset. After a Voxy wipe, though, the unload copies it protects no longer exist,
  so the route the player already walked stays a hole until reconnect.
- **Recommendation:** rebuild the set from the currently loaded chunks after a
  successful wipe, and amend the pin with a recorded rationale.

**R-7: there are no Tier 1 tests for `DhClientSession`.**
- None of the following is tested: P2 I1/I2/I4 against the session, epoch drops,
  receive-off teardown, budget back to zero after fail/disconnect/replacement/reset
  with frames queued, stale-session frames, or the bounded-retry cases above.
- Only policy-level and gate-level tests exist.

### MINOR

**Session, threading and bounds**
- A full worker queue silently drops a frame, because `RejectedExecutionException`
  leads to `close()` rather than `fail()`. Maintenance `expire()` tasks are not
  coalesced. (`DhClientSession.java:133-138,183,235-238`)
- The 32-frame inbound cap is a hard failure, reachable while the worker is blocked
  on its 32 per-column `onMain` round trips. Official fragment sizes are unknown.
  (`:115`)
- Worker lambdas catch `Exception`, not `Throwable`. A `LinkageError` (for example
  missing relocated XZ) or an `UnsatisfiedLinkError` from zstd leaves the session
  "active" until two 60 s timeouts, which hides packaging faults. (`:136,275`)
- Per column, the session makes 2 blocking main-thread round trips with a 5 s
  timeout. A 5 s main-thread stall ends the session, and throughput is tied to frame
  rate. Batch the authority check and handoff into one main-thread task per tile.
  (`:322-340`)
- `probeIngestBacklog() == -1` disables pressure gating; treat -1 as unknown and
  apply conservative pacing. Owned-bytes accounting stops at the Voxy handoff; say so
  in the docs.
- The vanilla authority set (262,144 entries, about 12–16 MB) sits outside the
  budget, and hitting its cap stops DH (`DhSessionPolicy.java:75-78`). Document this.
- On a LAN-hosted integrated server, C2S `distant_horizons:msg` from guests decodes
  through the host client's `capture()`, charging its budget. Use a decode-only C2S
  codec that skips the payload. (`DhClientTransport.java:27-28`)
- Server-supplied `CloseReason`/`Refusal` text reaches the log and chat
  unsanitised. Strip control characters and `§`, and truncate. (`:192,285,299`)
- Replacement sessions do not send `Cancel` for official generation requests on
  retire (plan §4.3 step 6).
- A late official `LevelInit` (over 3 s) kills the session when a request is pending.
- The Support 3 s fallback sends the dimension id where Support expects the Bukkit
  world name. Use no fallback for Support. (`:188`)
- An official tile marked `generate=true` stays stuck when generation is impossible
  (beyond `min(32, genDistance)`, or `generationRate == 0`). (`:227,271`)
- The main thread rescans the full grid every tick while nothing is sendable (about
  12.7k positions at official range 128). Cache "nothing due until T".
- Enqueue success counts as "accepted": `rawIngest == true` only means queued. Call it
  "enqueued" in status, receipts and docs. The P2 wording "accepted receipts imply
  completed ingestion" is not literally met.
- A residual Voxy ordering race remains after the final authority check; it heals on
  unload. The S1 measurement is still owed.

**Native surface and wiring (W)**
- **m-W1:** `distant_horizons:msg` is registered and announced on every Fabric
  client, even with `lod.source: lss`. Native mode is therefore not byte-identical on
  the wire. The plan accepts this in §4.2, but how DH Support's ad-message handling
  treats native clients is unverified. Consider registering only when DH is selected.
- **m-W2:** `DhClientTransport.init()` runs before `LSSClientNetworking.init()` with no
  try/catch, so a registration failure takes native networking down too.
  (`LSSClient.java:17`)
- **m-W3:** selecting `distant_horizons` on NeoForge, or alongside a real DH mod,
  turns native LSS off and gives no terrain. The setting is `Platform.ALL`.
- **m-W4:** in DH mode `/lss status` reports the native gate. A refusal string stays
  in `problem` after later successes, and receive-off shows a stale string.

**Protocol (D)**
- A Support `CloseReason` body has an i32 length prefix upstream; LSS reads a u16.
  This is latent, because the version check fails first.
- Support mapping strings are UTF-16-unit-prefixed plain UTF-8, not modified UTF-8.
  This only differs for non-ASCII state values. Family-select the rule and add a
  Support non-ASCII control.
- Ceiling-air quirk: correctly scoped to FullBuilder's deterministic `H+1` overrun.
  Missing negative controls: a 2-block overrun, a span entirely above the ceiling,
  and `cave_air`.
- `inBorder` tests the tile centre where the plugin tests extent overlap. This is
  conservative: some edge tiles are refused.
- OFFICIAL_15 message layouts against 3.2.0b are assumed, not verified (only
  ModInfo, SessionConfig and the DTO are hashed). The official-16
  `SURFACE_THEN_CHUNKS` choice has no evidence behind it.

**Terrain and packaging (C)**
- There is no nonuniform-biome fixture (plan §6.3). Sampling at (2,2,2) per cell is
  unpinned.
- Materials are re-resolved per column, 16× per tile.
- The session's tile-to-column mapping is consistent but unpinned.
- The top-level `THIRD-PARTY-NOTICES` in the dev jar is the baseline copy and omits
  XZ (the nested jar has the 0BSD text). `release_check.py` does not check it.
- `common/src/test/resources/dh-corpus/dh-support-0.14.0-ocean.dto` is unreferenced.

**Docs and evidence (D, E)**
- "Independently derived normalized hashes" overstates it. Both hashes were produced
  by `DhTerrainTile.normalizedSha256()` itself, so they are regression pins asserted
  against the frozen captures. The genuinely independent check is the POC decoder's
  agreement on span and identity counts.
- No upstream-generated format-2 bytes are committed; oracle parity `ff9d6c49…` ran
  once offline. The P1 "independently produced upstream bytes" gate is met in-repo
  only for Support / format 1. Commit the oracle's generated blobs as data.
- "Mappings use modified UTF-8" is true for the official profile only.
  "All ten control layouts" is 9 of 10 for Support.
- `packaging-playable.log`'s Fabric PASS came from a synthetic jar. The real-artifact
  proof is `actual-artifact-smoke.json`, which E re-verified on the real jar.
- The `lod.source` YAML comment omits the §7 warning that DH re-downloads and
  overwrites Voxy terrain. The prepared `distance_chunks: 32` persists into native
  mode.
- **Setup state is out of date.** `build/dh-playable/2026-10-01/test-setup-receipt.json`
  (00:38) says "not staged or launched". But `prepare-server.py`, `start-server.sh`
  and `watch-server-storage.py` were modified at 01:06, and `server/` shows a Paper
  1.21.1-133 + DHS 0.14.0 first start: bound to `127.0.0.1:25585`, `Done (14.187s)`
  at 01:02:46, clean stop at 01:06:42, logged in `server-first-start-and-shutdown.log`.
  This was done concurrently by another session, not by this review. E's
  installer/launcher review read the scripts before the 01:06 edits, so re-inspect
  the current versions before applying them. The client install is still unapplied.

## Checked and found sound (condensed)
- **Reserve before allocate on every path:**
  - capture `size+256` on the netty thread;
  - decode `4×frame+512`;
  - fragment `len+160`;
  - join peak `size+64`;
  - mapping, string and span pre-charges;
  - zstd work and output;
  - the XZ 32 MiB dictionary.
- **Bomb and hostile-input limits:**
  - 64 MiB aggregate decoded output;
  - canonical varints;
  - 1 MiB frame cap at both capture and decode;
  - 4096 fragments, 8 buffers, 8 request owners;
  - map ids bounded;
  - overlap and out-of-height span checks;
  - corner equality;
  - returned position equals the requested one;
  - UTF-16 unit scan without splitting a surrogate pair.
- **Release paths:** all closes are idempotent, interrupts break the
  main/worker mutual wait, and stale main tasks are gated by `active && session==this && level==level`.
- **Wire layout:**
  - message ids for both families, config orders and the exact 43 B config;
  - position packing, including negative coordinates;
  - DTO envelope and family-specific timestamp slots;
  - format 2 (flags, zigzag, light byte, predictor carried across columns, edges);
  - compression ids, with official XZ finished and Support XZ flush-only.
- **Scheduler:** priorities run untouched → incomplete → refresh, and fill completes.
  Support caps hold: range 32, one outstanding generation request, at least 1 s
  spacing, 16 columns per tile with a 500 ms floor. Official sync with ts 0 precedes
  generation. No-Voxy, no-transport, DH-conflict and receive-off cases retire the
  session without requests.
- **Terrain:**
  - min Y and height come from the level;
  - a 256-column COMPLETE check runs before replacement;
  - UNKNOWN and PARTIAL are never treated as air;
  - no-sky dimensions get an explicit zero layer;
  - light bit placement is correct;
  - `floorDiv` tiles.
- **Native defaults:**
  - source `lss`;
  - the old `onJoin` and `Deps` constructors keep native behaviour;
  - every new gate reduces to the baseline when `selected()` is false;
  - source and profile are reconnect-scoped, receive is live;
  - I3 one source.
- **Installer** (as read before 01:06): hash-pinned, refuses a real DH mod or any
  drift, backups and rollback.
- **Prepared YAML:** validated by the packaged codec.

## Remaining gates (explicit)
1. Fix D-1 and get a green normal `:fabric:build` (Tier 1 + Tier 2) on a non-nested
   clone. The 28 nested-clone contract failures must be shown green there.
2. Release pre-flight shape: `CI=true … :fabric:build :paper:test :paper:shadowJar :neoforge:build`
   plus `release_check.py --version`, covering all families. This is not done; the
   dev artifact is incremental.
3. Live play test, per the briefing:
   - Windows Prism install applied and verified;
   - server readiness re-verified on the current scripts;
   - `/lss dhstatus` counters increasing;
   - solid terrain beyond vanilla distance on native Windows graphics (the WSL
     D3D12 water-only result is unresolved);
   - movement, teleport, dimension round trip, reset, reconnect, and switch-back to
     native with no stale holes (R-4).
4. P2 session tests (R-7) and the bounded-retry/refresh tests for D-2 and R-1.
5. S0: an official-DH proxy capture and load ratio. S1: 3×3 tiles, live edge/corner
   checks, the vanilla-overwrite race measurement. S2 not started.
6. P1: commit upstream format-2/official bytes; fix R-2 and the CloseReason/mapping
   string family rules.
7. Official profiles: live validation, D-3, the stuck-generate case, Cancel on retire.
8. P3/P4: Sodium source/profile selectors, custom-height live validation, load
   comparisons.
9. NeoForge transport, and other MC lines (none started; shared build changes do not
   establish them).
10. DH-team outreach (pending; local work continues under the user's override).
