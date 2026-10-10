# DH first playable: NeoForge 1.21.1

The current target is Minecraft 1.21.1, Java 21 and NeoForge 21.1.248. The client
runs LSS and the community Voxy port; the server runs official Distant Horizons
3.3.3, with no LSS server mod. Work is local and uncommitted on
`feat/dh-codec-p1-mc1.21.1` in `.worktrees/dh-client-p1`. Outreach remains pending.

The user extended the P1 codec task until a playable build existed, requested a
NeoForge port and required self-testing before manual preparation. This record
supersedes the earlier Fabric incremental artifact and Paper/DH Support server.
Their receipts under `build/dh-playable/` remain historical evidence.

## Artifact and manual setup

Normal Gradle artifact:
`build/dh-neoforge/2026-10-01/lod-server-support-neoforge-dh-dev-mc1.21.1.jar`.
Internal metadata remains 0.16.0; the external filename identifies this local dev
build. Adjacent receipts bind its hash, tests, installation and server ownership.
The private test fixture is separate and never installed in the user's profile.

Selected Windows Prism instance: **LSS dev — 1.21.1 NeoForge - Copy For Far Testing**.
Its compatible pair is `voxy-0.2.15-beta-sodium0.8.12.jar` and
`sodium-neoforge-0.8.12-beta.1+mc1.21.1.jar`, with its existing Connector and
Forgified Fabric API. There is no DH client mod. Existing JSON preferences are
migrated with the packaged settings codec and preserved; `prism-installation.json`
records the original jar/settings backup outside the mods directory.

Launch the instance manually and connect to **`[::1]:25585`**. The fresh server uses
creative mode, seed 8675309, view distance 8 and simulation distance 4. Run
`/lss dhstatus`, fly beyond vanilla distance, check solid terrain and movement,
and exercise `/lss reset`. Status labels its counts **enqueued**, not stored.

```yaml
lod:
  source: distant_horizons
  receive: true
  distance_chunks: 32
  dh:
    profile: official_16
    refresh_seconds: 0
```

DH downloads replace eligible Voxy terrain. Accepted receipts remain held until
reset/reconnect by default. Source/profile changes require reconnect; refresh
changes now reset existing refresh timers (see the continuation below). To return to native LSS, set `lod.source: lss`,
reload and reconnect. The chosen distance 32 also applies after switching back.

## Self-test evidence

Java 21 normal Gradle builds run from `/tmp/lss-dh-neo-build`, an isolated source
copy outside the parent repository. This resolves the review's duplicate source
locator ambiguity while retaining the source contract tests. The shared harness
lock serializes builds and live rigs.

The official server artifact was verified against its Modrinth SHA512. Official
**3.3.3** is available for MC1.21.1; the plan's 3.3.4 reference is not available for
this game version. The actual negotiation is `official_16`, NeoForge channel `1`.

The private rig uses an owned Xvfb display and llvmpipe software graphics.
Production code establishes the connection, acquires terrain and ingests detached
sections through Voxy. A separate observer compares actual Voxy storage, then
exercises movement and reset. The first successful run compared **34,560** block
states, including **10,949** solid samples, across 15 distant columns and 72 Voxy
snapshots. Movement acquired ten further tiles. Reset returned the DH budget to
zero and reacquired terrain in a new session. Final settled rendering, lifecycle
and build results are in the artifact's `verification.json`.

The final same-artifact run passed **73,728** comparisons, **22,996** solid samples,
32 distant columns and 168 Voxy snapshots. Movement acquired eleven further tiles;
reset passed again. The settled view aims at accepted chunk `(5,7)` beyond vanilla
authority. A test-only mixin suppresses Voxy's actual geometry passes for the
off control while leaving vanilla rendering/storage intact. The distant solid
hillside disappears in that control; 140,367 pixels in the lower two thirds change.
Voxy reports 92,279 quads after restoring rendering. A config flag alone was not a
valid off control because this Voxy version consults it at renderer creation.

The Windows jar/YAML update is installed and hash-verified. Both legacy JSON
candidates and the original jar are backed up under
`minecraft/lss-dh-backups/20261001T070155.546227Z`. The previous Paper/DH Support
test server stopped cleanly. The replacement NeoForge/official-DH server is running
at `[::1]:25585`; Minecraft status and Windows IPv6 TCP reachability were verified.
`test-setup-completed.json` records its owned process and tool session. The server
holds the shared harness lock; stop it cleanly before heavy verification.

Official frames are frozen in
`common/src/test/resources/dh-corpus/dh-official-3.3.3-neoforge-partial/`. They exposed
three corrected defects: official `AIR`/empty-state mappings, the exact one-block
ceiling-air quirk, and generation scheduling for unknown columns inside a successful
sync tile. Unknown coverage is never fabricated as air. The normalized hash is a
regression pin; separate counts were independently decoded from captured bytes.

Common, Fabric, NeoForge and Paper unit suites passed normal Gradle runs. Fabric's
78 and NeoForge's eight required server gametests passed. The
[review disposition](2026-10-01-dh-review-disposition.md) records Claude findings
and corrections; the original review is preserved.

## Limits of this checkpoint

The user still needs to observe native Windows rendering. The software control
does not fix or validate the earlier WSL D3D12/NVIDIA water-only path. Enqueue counts
are not storage acknowledgements; the private observer separately verifies storage.
Voxy's asynchronous vanilla-overwrite race measurement remains owed.

This does not complete S0–S2/load gates or the full release checklist. Official
protocol15/3.2.0b gameplay, Sodium source/profile selectors, custom-height live
sessions, dimension round trips and source switch-back gameplay remain follow-up
gates. No other MC line is ported. DH mode does not establish native LSS far-player,
summary or Xaero sessions. Vanilla authority is retained across DH reset; visited
chunks repopulate through vanilla loading. Its bounded connection-wide set is
separate from the DH payload memory budget.

Use the updated [Claude briefing](2026-10-01-dh-claude-review-briefing.md) for an
independent review of this checkpoint.

## Current continuation

The user subsequently reported the Windows client working. The
[refresh/settings checkpoint](2026-10-01-dh-refresh-settings.md) supersedes the
artifact and open UI/lifecycle items above. Its final tested jar is installed in
the same Prism instance; the owned server resumes the same `[::1]:25585` world.
