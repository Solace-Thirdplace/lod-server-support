# Contributor guide

This checkout is the Minecraft **1.21.10** support line. Use **Java 21** for the platform build and **Java 21** for common tests. Actual platform MC targets, shipping and renderer availability are checked by [the compatibility catalog](docs/compatibility.md), with `.github/line.env` and build definitions authoritative. Run client gametests explicitly alongside the Fabric build; preserve this line’s loader and rendering adaptations.

## Commands

```sh
python3 tools/verify/verify.py full --run
bash tools/verify/run-gradle.sh vssJars
bash tools/verify/run-gradle.sh :sponge:build :sponge:bootSmoke
python3 scripts/release_check.py
python3 tools/tests/check_artifacts.py
python3 tools/compat/catalog.py validate
python3 tools/compat/catalog.py render --check
python3 tools/verify/verify.py fast path/to/changed/file
```

Fast selection is advisory. Never weaken required build/release gates because a fast selection passed. Run heavy builds and real-server tests sequentially. Every background condition wait needs a deadline and preserved failure evidence.

## Invariants and pinned decisions

- Read the behavior test before changing a pinned decision. Wire fidelity is never tiered; protocol and cross-brand adoption remain unchanged.
- `common` has no Minecraft/loader dependency and emits Java 21 classes. `xplat` is shared source, with narrow loader twins and explicit per-line API seams.
- The server owns generation. Permanent terminal responses and temporary pressure have different retry semantics. Preserve request ownership, receipt release and old-session rejection.
- Receive OFF retires acquisition but preserves committed Xaero native rebuilds. Disconnect/world replacement retires native ownership. Preserve queue locks, budgets and same-dimension replacement guards.
- Settings retain defaults, sentinels, upgrade/adoption behavior, privacy, clamps, ownership and applied-but-unsaved feedback. Correlated changes validate on scratch and publish consistently before side effects.
- Best-effort support and Folia’s experimental correctness status are distinct. A bounded soak does not remove the experimental label. Neo shipping, terrain consumers and far-player rendering are separate capabilities.
- Never operate the user’s Windows foreground for an unattended test. Use private-display rigs, owned process identities, exact artifacts and the shared harness lock. Preserve normal servers, personal profiles/worlds and credentials. No automatic diagnostic/evidence upload.
- Use isolated branches/worktrees for ports. No reset of dirty trees, blind keep-ours, target-branch merge, push, deployment or publication is implied by local validation.

## Distant Horizons adapter

Experimental, client-side: lets Voxy clients show terrain from servers running Distant Horizons without LSS. On by default since 0.17.0 (`lod.source: distant_horizons`, user decision 2026-10-10). Native LSS always wins on any server that offers it. The DH settings live in the experimental sidecar (`lss-client-experimental.yaml`), which stores only nondefault values, so the main client file stays readable by older versions — with one exception: a client file saved with `integrations.map_ingestion` fails to load on 0.17.0 and older until the group is renamed back to `xaero_map`. The private live rig is `test-fixtures/dh-live` (see its PORTING.md); `dh-p4` is 1.21.1-only. This line needs the four-argument NeoForge `playBidirectional` (21.10+ registers the three-argument handler for the serverbound flow only); `NeoForgeDhTransportContractTest` pins it.

## Map ingestion

Client-side and reflection-only: writes delivered LOD columns into Xaero's World Map (1.42.0+) or JourneyMap (6.0.0+). **On this line JourneyMap is unsupported** (only 6.0.0-beta.58 exists for 1.21.10, without the snapshot layer): it reports `state=unsupported`, `suggests` omits it, and the JourneyMap smoke does not apply. The setting is neutral: `integrations.map_ingestion.enabled` (new files default ON; upgraded files keep the value written in them — a hand-trimmed file without the key follows the new default) plus `integrations.map_ingestion.backpressure`. The old `integrations.xaero_map` group is a read alias, rewritten to the canonical name only by a save with changes. JourneyMap is All Rights Reserved: never copy, decompile into the tree or ship its code. Resolve its classes with `Class.forName(name, false, loader)` so LSS never runs JourneyMap's static initializers. Bind members by exact descriptor; the binding-surface test checks them against the golden jars at the classfile level (on 1.21.x the intermediary Fabric jar is compared with Minecraft types erased). The day map is required; topography and biome are best-effort. The [plan](docs/planning/2026-10-11-map-ingestion-journeymap-plan.md) §11 records the as-built design and the live findings. `scripts/journeymap_smoke.sh` is the live gate. `map-throughput` and `xaero-memory` are the measurement soaks; both need a real-terrain `SOAK_WORLD_FROM`.

## Sponge server plugin

Experimental and best-effort on every line (exit criterion: plan §0.2). `sponge/` is a SpongeVanilla-only server plugin that compiles the server half of `xplat`; the client excludes are derived in `sponge/build.gradle` and pinned both ways by `SpongeServerHalfContractTest`. The glue: Sponge raw data channels carry exactly the payload codec bytes; `/lsslod` runs on a private brigadier dispatcher behind `Command.Raw`; permission descriptions mirror Paper's plugin.yml defaults; three mixins (the `ChunkSaveDataHook` twin, the descriptor-selected FULL-status `ChunkLoadHook`, `ServerTickHook` at `tickServer` TAIL). The mixin config stays `JAVA_21` on every line (Sponge bundles Mixin 0.8.7). `SpongeLineSurface` is the only per-line flavor file. Sponge pins live in `gradle.properties` (VanillaGradle 0.3.x where Gradle runs on Java 25, 0.2.x on Java 21), the ship flag in `.github/line.env` (`LINE_SHIP_SPONGE`). `:sponge:bootSmoke` boots the built release jar on the pinned SpongeVanilla server in two phases. Far-player vanish goes through the `LoaderServices.vanishVisibility()` seam. Decisions and cuts: [Sponge support plan](docs/planning/2026-10-10-sponge-support-plan.md); local rig `./test-server.sh run-sponge` (port 25570).

## Known Test Flakes & Environmental Failures

[The canonical flake catalog](docs/operations/test-flakes.md) preserves exact signatures and actions. A different assertion is a regression, not permission to rerun away a failure. Preserve first-attempt XML/logs before any justified retry and record actual test identities/counts.

## Architecture

[Current ownership](docs/architecture/ownership.md), [version surfaces](docs/planning/per-version-surfaces.md), [test placement](docs/testing/test-placement.md), [settings reference](docs/reference/settings.md), and [implementation ledger](docs/implementation/project-improvements-ledger.md).

## Local Test Servers

Use [maintained disposable rigs](docs/operations/disposable-rigs.md) for automated testing and [support-line tooling](docs/operations/support-lines.md) for exact dependency/ref checks. Windows client-visible loopback may require `[::1]:port`; configure bind and visible endpoints separately. Never select a normal server for cleanup merely because its port matches.

## Releasing

Existing publishing and branding rules are unchanged. Follow the [preserved release discipline](docs/history/contributor-guide-before-project-improvements.md#releasing) and current release scripts. Do not rerun a partially published release; recover from its original attached artifacts. Sponge publishes from release.yml only where `LINE_SHIP_SPONGE=true` (the last Modrinth step); run `:sponge:build --refresh-dependencies` right before tagging, because an expired RC or snapshot pin fails there, and recover a stranded Sponge publish by hand-uploading the GitHub-attached jar. VSS Sponge jars publish locally with the other VSS jars. This improvement task does not authorize merges or publication.

The [previous complete guide](docs/history/contributor-guide-before-project-improvements.md) preserves historical explanations and decisions. Its dated compatibility claims do not override current build definitions/catalogs. Keep stable links and historical evidence intact.
