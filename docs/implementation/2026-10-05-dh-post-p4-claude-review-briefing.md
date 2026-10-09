# Claude Code review briefing: all DH changes since the P4 review

Prepared 2026-10-05 at the user's request. Independently review the production
changes, regression coverage and qualification evidence since
[the last Claude review](2026-10-02-dh-p4-claude-review.md). That report has an
October 2 filename but its review date is **October 5**. This briefing covers its
corrections and every subsequent implementation increment through the Voxy cache
reuse candidate. Write findings; leave implementation, installation and publication
to a subsequent task.

The local candidate works on Minecraft 1.21.1 Fabric and NeoForge. Reconnect now
loads validated retained DH source data, checks whether Voxy already contains each
eligible column, rebuilds only differing columns, and requests a timestamp check
even with continuous refresh Off. **Strict P2/P4 completion remains open:** a
controlled experiment proved an older queued DH write can overwrite newer vanilla
content inside Voxy, and its public ingest API acknowledges enqueue rather than
completion. Successful functional runs must not be presented as closing those gates.

## Checkout, comparison and report

```bash
cd /home/vox/projects/voxel-server-support/.worktrees/dh-client-p1
git status --short
git diff --check
git ls-files --others --exclude-standard
```

Branch: `feat/dh-codec-p1-mc1.21.1`. This is a separate clone despite its
`.worktrees` name. The broad Git diff contains the whole uncommitted DH feature,
including work reviewed previously. Review this clone; preserve the outer
checkout's unrelated changes.

Use the new [scoped diff](evidence/2026-10-05-dh-post-p4-review.diff) and
[file/hash manifest](evidence/2026-10-05-dh-post-p4-review-manifest.json).
They compare current repository-owned files, including untracked additions, with
the independent last review's frozen build copy:

```text
Baseline: /tmp/lss-dh-p4-claude-build.3iqSY2
Latest tested build copy: /tmp/lss-dh-voxy-cache-20261005
```

All nine hashes in the original P4 scope manifest and all fifteen before-hashes
in the subsequent correction manifest match that baseline. The new inventory
contains **75 changed or added files**: ten production inputs, six unit-test files,
19 corpus files, 22 private-fixture inputs, eleven documentation files, six
historical evidence files and the baseline review report itself. Historical evidence
and the report are inventoried but omitted from the unified diff; binary corpus
inputs have hash summaries. This preparation's new brief/diff/manifest are excluded.
The diff is a review aid, not a patch to apply.

The latest production manifest has 377 entries; its curated test/fixture manifest
has 72. All current hashes match. The new scoped manifest also verifies every changed
production, unit-test, corpus and fixture input against the latest build copy.
In particular, it includes `DhColumnConverterTest.java`, which is absent from the
existing curated 72-entry manifest. Neither curated manifest claims to inventory
every repository test.

Read `CLAUDE.md`, the [compatibility plan](../planning/2026-09-29-dh-client-compatibility-plan.md),
especially §3 I1–I4, §5.3–5.4 and §9 P2–P4, and the previous report before judging
intentional tradeoffs. The plan's appended checkpoints are chronological; later
cache checkpoints supersede unconditional replay and older installation records.

Write the new report to:
`docs/implementation/2026-10-05-dh-post-p4-claude-review.md` in this clone.
Preserve earlier reports. Lead with verified actionable findings, each with severity,
current file/line, trigger, consequence, evidence and a proposed fix/regression.
Separate demonstrated defects, suspected risks and incomplete qualification gates.
State what you ran independently, what you inspected, and the exact source/artifact
boundary. If no new defect is demonstrated, say so and describe the review limits.

## Production delta

These are the entire ten-file production increment relative to the frozen review.
Loader transports and shipping build/dependency definitions did not change in this
increment; shared xplat changes compile into both loaders.

| File | Resulting behavior |
| --- | --- |
| [DhSessionPolicy.java](../../common/src/main/java/dev/vox/lss/common/dh/DhSessionPolicy.java) | Preserve refresh age across session replacement; reconcile genuine interval changes; make a restored valid snapshot due for a reconnect check even with periodic refresh Off. |
| [DhTerrainDecoder.java](../../common/src/main/java/dev/vox/lss/common/dh/DhTerrainDecoder.java) | Admit official protocol-15's verified single ceiling-air voxel convention without relaxing solid spans or fabricating missing coverage. |
| [DhTerrainCache.java](../../common/src/main/java/dev/vox/lss/common/dh/DhTerrainCache.java) | New bounded persistent raw-source cache with full world/profile identity, payload integrity, atomic replacement and owned clearing. |
| [DhAssembly.java](../../common/src/main/java/dev/vox/lss/common/dh/DhAssembly.java) | Save an owned completed DTO without exposing its byte array or extending its lease. |
| [DhClientSession.java](../../xplat/src/main/java/dev/vox/lss/networking/client/DhClientSession.java) | Restore/validate source tiles, reuse verified Voxy columns, conditionally check server timestamps, account actual work and close abandoned client-thread reader results. |
| [DhColumnConverter.java](../../xplat/src/main/java/dev/vox/lss/networking/client/DhColumnConverter.java) | Attenuate water skylight with depth; share material/light semantics with full-column cache comparison. |
| [VoxyTerrainCache.java](../../xplat/src/main/java/dev/vox/lss/compat/VoxyTerrainCache.java) | New optional reflected destination reader with engine/section ownership, mapping snapshots and real initialized Voxy defaults. |
| [DhVoxyBridge.java](../../xplat/src/main/java/dev/vox/lss/compat/DhVoxyBridge.java) | Expose the optional cache reader through the narrow DH/Voxy boundary. |
| [ColumnCacheStore.java](../../xplat/src/main/java/dev/vox/lss/networking/client/ColumnCacheStore.java) | Share the adopted LSS/VSS cache root; explicit clearing also removes retained DH source data. |
| [ClientCommandActions.java](../../xplat/src/main/java/dev/vox/lss/networking/client/ClientCommandActions.java) | Drain DH acquisition around explicit cache clearing so old work cannot repopulate cleared data. |

### Previous-review fixes and expanded qualification

[The P4 disposition](2026-10-05-dh-p4-review-disposition.md) addresses P4-1 through
P4-9 individually. Verify those corrections rather than assuming the disposition
is an independent verdict:

- Repeating an applied refresh interval preserves deadlines across new sessions.
  A changed active interval derives deadlines from acceptance age. Enabling a held
  successful snapshot retains the existing `now + interval` pin. New actual-session
  tests cover replacement, ring-order ties, the ten-second ceiling, pending work
  and vanilla/border/range/synchronization filters.
- The content fixture settles initial acquisition at range 12 before refresh,
  checks a stable frontier for five seconds, and sizes the deadline to the sweep.
  Native → DH → native uses distinct blocks and physical disconnects. Removal
  requires present sections with retained gold sentinels. Owned request ID, epoch,
  conversion and receipt observations must agree.
- Observer exceptions are contained and fail promptly. Seeding, permissions,
  protocol-20 negotiation, candidate/fixture admission and sanitized captures have
  explicit premises. Identified standalone bootstrap leaks were archived/removed.
  The historical timeout now records two plausible mechanisms; its cause was not
  established retrospectively.

[The P2/P4 continuation](2026-10-05-dh-p2-p4-continuation.md) adds actual worker/
handoff tests for partial coverage, rejection/recovery, failed conditional receipts,
vanilla arrival, retirement, duplicates, refusal ownership and same-connection
world-key replacement. Its eleven final functional live cells cover Support Full/
Fast builders, official protocols 15/16, both client loaders, no service, wrong
profile, a real DH-client conflict, lifecycle and source-distinct content/removal.

Official **3.2.0-b** exposed the previously unverified protocol-15 ceiling convention:
4100 outside spans including repeated corners were AIR ending one voxel above the
exclusive world ceiling. The decoder now applies the already verified normalization
to that producer family. Larger/solid/wholly outside spans remain invalid. Three
pre-fix reds and a verbatim producer corpus support the correction. Official
**3.3.3** is the available MC1.21.1 protocol-16 pairing in the retained artifact
lookup; do not call it a live 3.3.4 qualification.

### Ocean light correction

[The lighting record](2026-10-05-dh-ocean-lighting.md) links the frozen producer DTO,
exact builder bytecode and the independent upstream floor-run oracle. DH samples
light above a merged water run; repeating that surface light in Voxy lit deep sand.
The converter reduces sky light through resolved `Blocks.WATER` states, including
flowing water, using this line's opacity and counting the first submerged voxel.
Block light, other materials, no-sky dimensions and normalized source values retain
their prior semantics.

Synthetic tests cover water depth, section boundaries and no-sky/block light.
Packaged Fabric and NeoForge each passed 900 actual deep/shallow water-light
comparisons. The user subsequently confirmed the Windows ocean lighting was fixed.
That is a visual report for this defect, not a measured frame-time result.

### Reconnect source cache

[The source-cache record](2026-10-05-dh-rejoin-cache.md) preserves official 3.3.3
bytecode evidence and primary-source corroboration. Official DH keeps local terrain
and performs sync-on-load timestamp checks; it does update changed terrain.
“Only missing chunks, never updates” is not its observed behavior.

The new cache retains **complete validated source DTOs**, independently of Voxy
acceptance. Its identity includes exact remote address, opaque DH server/world
keys, vanilla dimension, biome-zoom seed, profile, min Y, height and skylight shape.
Ambiguous seed identity disables reuse. Original DTO timestamps are already server
clock values and must not receive an additional official-client time offset.

Files live under the adopted cache root's `dh-terrain-v1`. A 92-byte versioned header
binds identity, position, timestamp, length and payload SHA256. Decode additionally
requires complete 4096-column coverage and body/header timestamp agreement. The
global quota is 4096 tiles / 256 MiB across owned buckets, evicting oldest writes.
Writes force then atomically replace. Symlink containment and foreign-file
preservation have explicit tests. Review filesystem failure paths as well as the
happy path.

Restoring local content establishes a session receipt before a reconnect timestamp
check, even with continuous refresh Off. An unchanged reply carries no terrain;
offline edits download a new tile. Missing/corrupt/evicted/incompatible source data
falls back to a full request. Partial or failed local delivery cannot create a
valid conditional acceptance shortcut. Saving a complete network DTO before Voxy
enqueue creates a source backup, not a persistent destination-completion claim.

Explicit reset/clear drains acquisition and clears owned source/native cache data.
Native-only timestamp invalidation preserves source data; loss of Voxy alone can
be recovered locally. Examine the 60-second cache-work deadline, worker ordering,
reset failures, address adoption and stale-epoch callbacks.

### Reuse of Voxy's destination cache

[The latest record](2026-10-05-dh-voxy-cache-reuse.md) supersedes unconditional local
replay from the earlier checkpoint. **Only cache restoration** attempts destination
reuse; new network terrain still follows normal conversion/ingestion.

The reader opens the destination on the client thread, retains an engine reference,
snapshots bounded block/biome mappings, and reads copied level-zero 32³ sections on
the worker. Each section is released after copying. At most one column's vertical
copies are retained at once, with memory reservations for mappings, copies and
material work. Compare every voxel's block state and packed sky/block light; solids
also compare the converter's quart-center biome. Air ignores biome. Negative chunk
offsets, custom heights, unsorted spans and zero-lit gaps must match conversion.

The initial implementation treated `acquireIfExists` null as zero-lit air. Warm
tests failed even after proving full target content in memory. Exact port bytecode
showed its tracker retains initialized **sky-15 air** after that miss. Ordinary
`acquire` now compares those actual defaults. Section existence alone never proves
matching content. Reading may initialize Voxy sections; the reader submits no
replacement terrain or ingest.

Matching columns skip Minecraft-section construction, Voxy conversion and enqueue;
differing columns rebuild individually. Vanilla checks occur before comparison and
again before local acceptance/handoff. `Voxy reused columns` is separate from actual
conversion/enqueue counts. Optional read ABI failures fall back without latching
the ordinary ingest bridge dead. Timeout/interruption races close an engine reader
whose waiting caller never received it; verify exactly-once cleanup on both sides
of completion/cancellation.

This still decodes source data and reads/compares full base content. It is not a
zero-IO reconnect, a derived-mip integrity guarantee, or a durable Voxy receipt.
Vanilla-refined terrain can differ from the DH approximation and be conservatively
rebuilt; there is no persistent native-ownership classification. Review both the
correctness and bounded cost of that behavior.

## Open correctness boundary

Read [the Voxy commit-gap investigation](2026-10-05-dh-voxy-commit-gap.md) and the
test-only `RaceProbe` / `VoxyIngestRaceMixin`. The experiment holds an owned red DH
section immediately before real `WorldUpdater.insertUpdate`, observes newer yellow
vanilla data in actual storage, then releases the old write and observes red.
The strengthened reproduction held it for 4516 ms and distinguishes every DH-owned
section from vanilla by ingest handle and object identity.

`dh-result.pass=true` means the **counterexample experiment completed**.
`correctness_gate_passed=false` / `release_blocker=true` are the product verdict.
The controlled delay does not establish frequency during ordinary play.

The pinned `rawIngest(...): boolean` accepts a queue entry. Backlog permits are
consumed before commit; zero backlog is not completion. Pre-handoff authority checks
cannot cancel a later write already queued in Voxy. The proposed guarded commit/
completion API is a local proposal; no supported upstream token API, Voxy production
patch or outreach was added. Cache comparisons do not close this boundary. Keep
strict I1 and per-write completion gates open and look for any new claim or path
that incorrectly relies on them.

## Exact latest candidate and evidence

Version: **`0.16.0-dh-voxycache.dev20261005`**, normal Java-21 Gradle build.

| Artifact | SHA256 |
| --- | --- |
| NeoForge LSS candidate | `b69426d30887f91fbc4e4d390345126df4a8c8b489c831a5a0995947d6d62609` |
| Fabric LSS candidate | `d7b204f16e49815ebbe9c42f17004dd6b0440918245df8a0d173c1995d559a5d` |
| Pinned community Voxy port | `024d58076649dbd348ad75c3778f90916ec35b7b9a1196b0662823a72f071a49` |

Evidence is in the **outer checkout**, rooted at:
`/home/vox/projects/voxel-server-support/build/dh-neoforge/2026-10-05/`.

| Directory | Scope and important records |
| --- | --- |
| `p4-review-fixes/` | Verified review corrections, pre-fix policy/session reds, corrected content/lifecycle runs, independent seeded replay and observer tests. |
| `p2-p4-completion/`, `p2-p4-final/` | Producer-15 pre-fix evidence, expanded live matrix, exact Voxy API bytecode and strengthened vanilla-authority counterexample. |
| `ocean-lighting/` | Independent floor-light oracle, two pre-fix reds, frozen database DTO, both loaders' private water readback and manual confirmation. |
| `rejoin-cache/` | Original source-cache candidate, identity/corruption/reset cases and seven-stage rejoin runs; this candidate unconditionally replayed cached source into Voxy. |
| `voxy-cache-reuse/` | Latest candidate, normal XML/logs, source manifests, both final eight-stage rejoin runs, retained failed attempts, classfile defaults, installation and resumed-server receipts. |

Latest `verification.json` and `fabric/verification.json` bind source, candidate and
observer hashes. `successful-live-cases.json` selects the final passing runs;
`live-matrix.json` also retains three failed NeoForge attempts. Do not select the
first row or silently count failed/setup runs as passes. Final run directories are
`neoforge-rejoin-runs/attempt-c4dbrvhg` and
`fabric-rejoin-runs/attempt-zle3mjto`.

| Latest normal verification | Result |
| --- | --- |
| Common unit | 1005 passed |
| Fabric unit | 1715 passed; four existing opt-in skips |
| Paper unit | 536 passed |
| NeoForge unit | 23 passed |
| Total unit | **3279 passed**, zero failures/errors |
| Required server gametests | 78 Fabric + eight NeoForge |
| Versioned LSS/VSS packaging | All six artifacts pass |
| Packaging selftest / SQLite helpers | 126 / nine pass |
| Latest private rejoin | Eight stages per loader, 2400 independent known-block comparisons each |

Both latest packaged clients run with periodic refresh Off. Their stages cover cold
acquisition, unchanged rejoin, one wrong voxel in an existing section, offline edits,
complete Voxy disk/memory loss, a new Minecraft JVM, explicit reset and target-source
file deletion. Unchanged rejoin and JVM restart require **zero target conversions,
ingests and full terrain payloads**. One damaged column requires exactly one
reconstruction; full Voxy loss reconstructs all sixteen target columns locally.
Reset/source deletion require timestamp-zero full requests. Full target-content
premises are checked before warm rejoin and JVM restart; independent known-block
storage samples remain separate from the production comparison implementation.

Historical full-matrix and ocean runs bind their earlier candidates. They are useful
increment evidence, **not a rerun of every live case against the latest binary**.
The latest binary has the normal full build and both rejoin runs above. Retained
fixture failures include early asynchronous all-air exports, gravity-invalid ocean
floors, Voxy-default mismatch, a NeoForge split package, ABI/classpath setup and a
test compilation error. Their records remain failures, not known flakes or passes.

The new reader adds four unit pins; comparison equivalence/every-voxel rejection
adds two; abandoned reader timeout/interruption adds two. Read all six changed
test files in the scoped diff. `ContentProbeTest` runs separately through the real
Fabric loader; its observer is never packaged into a production artifact.

## Review focus and practical limits

Prioritize source-cache identity/integrity/clearing, false destination-reuse cases,
mapping/section/engine ownership, memory and CPU bounds, completion races, refresh
Off reconnect semantics and vanilla authority. Then audit the previous-review fixes,
protocol-15 normalization, water lighting and the private fixtures' independent
oracles. Look for stale callbacks accepting another world, conditional requests
based on partial/failed content, and cache-only work miscounted as ingestion.

No matched-area official-client load comparison, Windows GPU frame-time result,
exhaustive live configuration/failure matrix, full-range cache-comparison benchmark,
every derived-mip validation or additional support-line port is claimed. Strict
P2/P4 remain incomplete; P5 remains demand-driven. User manual confirmation closes
the reported ocean-depth appearance defect only. Outreach remains pending.

The selected Windows Prism instance is
**LSS dev — 1.21.1 NeoForge - Copy For Far Testing**. Its installed mod is the exact
latest NeoForge candidate; preferences are byte-preserved. The manual server was
resumed at **[::1]:25585**, world **dh-neoforge-playable**, NeoForge 21.1.248 /
official DH 3.3.3 with no LSS server. Distant generation is bounded to 32 chunks;
the 144-chunk course and TestPlayer operator status remain. Current setup receipts
are `voxy-cache-reuse/test-setup-completed.json`, `prism-installation.json` and
`manual-settings-verified.json`; consult current owner identity before any operation.

Start with read-only review and existing evidence. The manual server owns the
shared harness lock. Coordinate exclusive rig use before heavy reruns; preserve its
world and resume it afterward. Never bypass/unlink the lock, run heavy builds in
parallel, operate the Windows foreground, repurpose a personal world or install a
review artifact into the user's instance. Use owned fresh runtimes and preserve
first-attempt evidence. Java 21 is required; Tier 3 and Folia tasks do not exist here.

Once exclusive use is arranged, reproduce normal checks in a fresh scratch copy
that includes untracked inputs and excludes old build outputs, using a distinct
review version. The recorded final build used:

```bash
CI=true JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
  bash tools/verify/run-gradle.sh --no-parallel \
  -Pmod_version=0.16.0-dh-voxycache.dev20261005 \
  :common:test :fabric:build :paper:test :paper:shadowJar \
  :neoforge:build :neoforge:runGameTestServer vssJars \
  -I /tmp/lss-dh-voxy-cache-20261005/test-fixtures/dh-live/classpath.gradle \
  :neoforge:exportDhFixtureClasspath :fabric:exportDhFixtureClasspath
python3 scripts/release_check.py --version 0.16.0-dh-voxycache.dev20261005
python3 scripts/release_check.py --selftest
```

That is a historical exact command, not permission to modify the tested build copy.
For focused tests use the normal loader environment and the harness wrapper; preserve
full-suite XML before a filtered run. Follow [the private-rig README](../../test-fixtures/dh-live/README.md)
for fixture compilation, exact artifact/verification admission, inherited lock,
private display, storage monitoring and owned cleanup. Latest rejoin verification
checks all eligible content before the warm premises; do not replace that with a
backlog-zero heuristic. The [manual guide](2026-10-05-dh-manual-test-guide.md) remains
the user's testing handoff.
