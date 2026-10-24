# Paper version guard and store build marker (Discord report, 2026-10-23)

Status: reviewed (2 Opus + 1 Fable, §8), awaiting the user's approval.

## 1. Report

A Paper operator moved a 26.2 server to Paper 26.3 (world and `lss-lod/` kept). Their client got no
distant LODs for known areas; newly explored areas worked and persisted. Deleting `lss-lod/` fixed
it.

## 2. Root cause (reproduced 2026-10-23)

1. **Paper has no upper version guard.** `plugin.yml` sets `api-version` (templated from
   `minecraft_version`, `paper/build.gradle:74-83`), which only refuses a server *older* than the
   jar. Fabric (`minecraft_dependency`) and NeoForge (`[minecraft_version]`) refuse a newer one.
   The 26.2 LSS Paper jar enabled on Paper 26.3 without a warning.
2. **The 26.2 jar reads unconverted 26.2 chunks as air on 26.3.** 26.3's block-state codec rejects
   the old `Name`/`Properties` keys; the 26.3 line handles them (`upgradeLegacyBlockStateEntry`,
   `PaperNbtSectionSerializer`), the 26.2 line doesn't, so every palette entry became air (one
   throttled WARN: `Section block_states parse error … No key id in MapLike[{Name:…}]`). Chunks
   saved by 26.3 decode fine, which is why new areas worked.
3. **The store kept the air.** The store's `mc_version` and registry fingerprints come from the
   running server (`PaperRequestProcessingService` ~598-605), so the 26.2 jar dropped the 26.2
   store correctly and rebuilt it stamped 26.3. Schema, wire and codec match across lines, so the
   correct 26.3 jar later kept the store (`SqliteLodStore.evaluateMeta`) and served the air rows.
4. Deleting `lss-lod/` removed the air rows; the 26.3 jar re-read every chunk correctly.

Repro (disposable Paper 26.3 rig, copy of a 26.2 world): phase 1 (26.2 jar) wrote 4353 air-only
rows over old terrain and none over new; phase 2 (0.19.1+26.3 jar, store kept) served 4258 of them
unchanged; phase 3 (store deleted) had none.

The user's idea, recording the Minecraft version the store was generated with, is already there.
The gap is that it records the server's version, not the version the jar was built for.

## 3. Fix

### 3.1 Paper/Folia version guard (the cause)
On enable, check the running version (`Bukkit.getMinecraftVersion()`, Bukkit API only, already
used in `PaperCommands`) against the line's `minecraft_dependency`, the same rule Fabric uses:
- a bare version (1.21.x lines) = exact match;
- a range `>=A <B-` (26.x lines, e.g. `>=26.3 <26.4-`) = numeric-tuple compare, so 26.3.1 is
  accepted and 26.4 refused.

The rule is baked into `plugin.yml` at build time (a new templated key read with
`getDescription()`; never `lss-brand.properties`, which the VSS tasks replace). On a mismatch: one
ERROR naming the running version, the jar's supported versions and the file to download, then
`getServer().getPluginManager().disablePlugin(this)`. It runs as the first `EnableSteps` step after
`loadBranding` and before `registerChannels`/`loadConfig`, so a refused plugin registers no channel,
creates no config and opens no store. Folia and Purpur use the same path. No override setting.

Sponge needs no guard: its `spongeapi [21.0.0-SNAPSHOT,22.0.0)` dependency tracks the Minecraft
minor (confirm on the rig).

Behaviour change to call out in the release notes: on 1.21.x, a Paper jar for one 1.21.x version
no longer starts on another 1.21.x server (U1).

### 3.2 Store build marker (heals affected Paper servers)
New meta keys written on create and on adopt: `build_mc` (the jar's `minecraft_version`) and
`platform` (paper/folia/fabric/neoforge/sponge, evidence for next time).
- Present and different `build_mc` → drop and rebuild, like any core drift.
- **Absent** (every store written before this release), decided once at the top of
  `openOrRecreateWriter`, **before** `maybeLazyUpgradeFromV19` (otherwise its `writeMeta` would
  stamp the key on a v0.9 Paper store):
  - Paper/Folia → DROP once ("store written before build tracking; rebuilding once"), because a
    mismatched jar could have written it and a poisoned store carries the same meta as a healthy
    one (no cheaper check: a content scan can't tell poisoned air from real air such as End void).
  - Fabric/NeoForge/Sponge → REFRESH: stamp the keys, keep the rows (these loaders refuse a
    wrong-version jar). The Modrinth test server keeps its 11.5 GB store.
- The choice comes from a `LoaderServices` seam (`legacyStoreMigration()`, a one-shot migration
  rule, not a trust model) because Fabric, NeoForge and Sponge build the store environment in
  shared code (`RequestProcessingService` ~436). The store `Environment` keeps defaults for its
  existing constructors.
- Older 0.19.x jars ignore unknown meta keys, so a downgrade still opens the store.
- Paper has no background backfill: a rebuilt Paper store refills as columns are served.

### 3.3 Clients that already received air
A store rebuild does not heal them: the freshness rungs answer "up to date" from region headers and
the served-stamp cache, which the store never touches. Automatic healing needs an invalidation
floor in those rungs or a wire change, out of scope here. Players who joined while the wrong jar
ran run `/lss clearcache` once (per server, no confirm). The live rerun checks this with a client
attached, and the reply says it plainly.

### 3.4 Considered and dropped
- **"Serve but don't store" on a palette decode error.** Air substitution for renamed blocks is a
  deliberate keep-the-section rule (`NbtSectionSerializer` ~600-611, ~776-795); skipping the store
  would leave upgraded worlds uncached forever, it misses backfill deposits (`StoreBackfill`), and
  a correct jar never hits this incident. Routing an all-entries-failed column to the miss ladder
  (Fable) is a sound follow-up but touches a pinned decision; not in this plan.

### 3.5 Accepted residuals
- An older unguarded jar (0.19.x) run on a newer Paper after this release can still poison a store
  that carries the new marker: undetectable, and the guard ships in every new jar.
- A Paper store copied into a Fabric server is adopted. The `platform` key records it from now on.

## 4. Tests
- Guard: unit tests of the rule (26.3/26.3.1 accepted and 26.4 refused against `>=26.3 <26.4-`;
  26.1/26.1.1/26.1.2 against the 26.1 range; exact on 1.21.x); a plugin contract test that a
  mismatch disables before any channel, config or store (`EnableSteps`/`RecordingSteps` in
  `LSSPaperPluginGlueTest`); `PluginYmlContractTest` for the new templated key; mutation: skip the
  check.
- Store: rows for present-equal (open), present-different (drop), absent + Paper (drop, keys
  stamped), absent + Fabric (refresh, rows kept, keys stamped), and a v0.9-shaped Paper store
  (dropped, not lazily upgraded). Update `SqliteLodStoreTest` (drift detail),
  `SqliteLodStoreMigrationTest`, `StoreEnvironmentContractTest`,
  `PaperStoreEnvironmentContractTest`. Mutation: treat absent as trusted everywhere.
- Live: rerun the repro rig with newly built guarded jars: the 26.2 jar refuses on Paper 26.3; the
  phase-1 poisoned store is dropped on first open by the fixed 26.3 jar; a client that joined during
  phase 1 shows the gap until `/lss clearcache`, then fills.

## 5. Lines and docs
All six lines (shared code; the rule and build version come from each line's `gradle.properties`).
Six-line re-pin. Docs: README (Paper refuses a jar for another Minecraft version; Paper stores
rebuild once after this update), CLAUDE.md (one line), release notes.

## 6. Reply (Discord, for the user to post)

> Thanks, found it. At some point the 26.2 version of LSS ran on your 26.3 server; Paper doesn't
> stop that, and on 26.3 that version read your older chunks as empty and saved them to the store.
> The next release refuses to start on the wrong Minecraft version and rebuilds Paper stores once.
> Deleting the store was the right fix. Players who joined while the old version ran should run
> `/lss clearcache` once to get those areas back.

## 7. Decisions for the user
- U1: refuse to start on a version mismatch (recommended; reviewers agree). This includes 1.21.x
  Paper jars on a different 1.21.x server.
- U2: one-time rebuild of Paper/Folia stores after the update (recommended; reviewers agree).
- U3: ship as 0.20.0 with the map-ingestion permission and the merged #364 far-plane option
  (recommended; reviewers agree).

## 8. Plan review (2 Opus + 1 Fable, 2026-10-23)

| Finding | Severity | Disposition |
| --- | --- | --- |
| "Exact version" contradicts Fabric's `minecraft_dependency` family on 26.x | MAJOR (both) | Folded §3.1: same rule as Fabric, baked from `minecraft_dependency` |
| The guard newly refuses 1.21.x jars on other 1.21.x Paper servers | MAJOR | Folded §3.1 note + U1 |
| Absent-key decision bypassed by the v0.9 lazy upgrade; absent as plain core drift drops every store | MAJOR | Folded §3.2: decided first, Paper drop / others refresh |
| Decode-error skip undoes a pinned keep-the-section rule, misses backfill, still serves air | MAJOR (both) | Dropped §3.4; miss-ladder routing noted as a follow-up |
| Clients don't self-heal; reply must say so | MINOR (both) | Folded §3.3, §6 (`/lss clearcache`), live check |
| Build version source: `plugin.yml`, not brand properties (VSS replaces them) | MINOR | Folded §3.1 |
| Guard placement and disable call | MINOR/NIT | Folded §3.1 |
| Trusted flag needs a loader seam | MINOR | Folded §3.2 |
| Add a `platform` key; name the flag a migration | MINOR | Folded §3.2 |
| Sponge: dependency already tracks the MC minor | NIT/MINOR | Folded §3.1 (confirm on rig) |
| Older unguarded jar can re-poison; moved stores | MINOR/NIT | §3.5 accepted residuals |
| Live test needs newly built guarded jars | NIT | Folded §4 |
| `lss-timestamps.bin` holds served stamps, not modification times | MINOR | Corrected (§3.3) |

## 9. As built (2026-10-23, 26.3 line)

**Guard (§3.1).** `plugin.yml` gains `lss-minecraft-dependency: '${minecraft_dependency}'`,
expanded by `:paper:processResources` from `gradle.properties` (the value `fabric.mod.json`
declares). The `vssJar` rewrite leaves the line verbatim; `release_check.py` pins it as a VSS
identity line and checks the shipped value against `minecraft_dependency`. The rule parser is
`common`'s `MinecraftVersionRule` (pure, Java 21 tests): a bare version is exact, comparators
are ANDed, versions compare as zero-padded numeric tuples, a pre-release sorts below its
release and a bound ending in a bare `-` is below every build of that version (so `<26.4-`
refuses 26.4 pre-releases, as Fabric does). `EnableSteps.checkMinecraftVersion()` runs right
after `loadBranding`; on a mismatch it logs one ERROR (running version, supported range,
"Download the <plugin> build for Minecraft <running> from <website>", brand-correct for VSS)
and calls `disablePlugin(this)`, and `runEnablePlan` returns before config, channels or the
store exist.

**Store marker (§3.2).** `Environment` gains `buildMc`, `platform` and
`legacyStoreMigration` (`LegacyStoreMigration.REFRESH|DROP`); the existing constructors
default them to the running version, `"unknown"` and `REFRESH`. `evaluateBuildMarker()` runs
first in `openOrRecreateWriter`, before `maybeLazyUpgradeFromV19`: absent + `DROP` returns the
drop verdict ("store written before build tracking; rebuilding once"); absent + `REFRESH`
stamps `build_mc`/`platform` in place, logs one INFO and continues down the ordinary ladder.
`build_mc` is a core key in `evaluateMeta`; `platform` is written by `writeMeta` and never
compared. Fabric/NeoForge/Sponge pass `BuildInfo.minecraftVersion()`,
`LoaderServices.storePlatform()` and `LoaderServices.legacyStoreMigration()` (default
`REFRESH`); Paper passes `BuildInfo.minecraftVersion()`, `paper`/`folia` (`FoliaSupport`) and
`DROP`. The soak store downgrade also deletes the two keys, to stay the exact v0.9.x shape.

Deviations and choices:
- **Build version source.** `BuildInfo` reads `dev/vox/lss/common/lss-build.properties`, a
  resource `:common:processResources` expands from `minecraft_version` (the
  `SqliteDriverRuntime` descriptor precedent: a package-local resource read through
  `Class.getResourceAsStream`, which works in Fabric's nested jar, NeoForge's module and the
  flattened Paper/Sponge jars). Identical on every line, so ports carry it unchanged. Paper
  could have read `api-version`, but one source for all platforms is simpler to pin.
- **Missing or malformed rule fails closed.** A Paper jar whose `plugin.yml` lacks the rule (or
  carries an unexpanded or unparseable one) refuses to enable with its own ERROR, rather than
  running unguarded. `release_check.py` and `PluginYmlContractTest` keep that unreachable for a
  built jar. Likewise a missing or unexpanded `lss-build.properties` throws (a packaging bug).
- **`storePlatform()` is abstract** on `LoaderServices` (all three loaders override it; the
  NeoForge/Sponge seam-completeness tests require it), while `legacyStoreMigration()` is a
  default (`REFRESH`) since no shared-code loader differs.
- **Sponge needs no guard (metadata evidence, not rig-confirmed).** Each line's
  `sponge_api_dependency` spans exactly one SpongeAPI major, and each major pairs with one
  Minecraft release in the line's `spongevanilla_version` pin (`1.21.1-12`, `1.21.10-17`,
  `1.21.11-18`, `26.1.2-19`, `26.2-20`, `26.3-21`); Sponge refuses to load a plugin whose
  dependency range excludes the running API. The mixins (`ChunkLoadHook` and friends) also bind
  version-specific targets.

Verification (local, 2026-10-23): `:common:test` 1160, `:fabric:test` 2157 (4 skipped),
`:paper:test` 582, `:neoforge:test` 24, `:sponge:test` 25, all passing; `:fabric:runGameTest`
88/88 on the second attempt (the first went red once on
`SerializerParityGameTests.storeHitBytesMatchNbtServedBytesThroughTheReaderPath`, "waiting for
the deposit to commit (batcher) on tick 1202"; that test uses the unchanged default
constructor on a fresh store, and the signature is not in the flake catalog yet).
`release_check.py` (all eight jars) and `--selftest` pass. Mutation checks, each red: absent
marker trusted everywhere; marker decided after the lazy upgrade; `build_mc` not a core key;
the refresh not stamping; the guard step skipped; the refusal never firing; Paper passing
`REFRESH`; the Fabric site passing the running version; `plugin.yml` losing the key; the rule's
`-` bound and upper bound. The live rerun (§4) is still to do.
