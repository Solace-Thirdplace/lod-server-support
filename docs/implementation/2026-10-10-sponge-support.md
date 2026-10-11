# Sponge server support: decision record (2026-10-10)

The plan is [2026-10-10-sponge-support-plan.md](../planning/2026-10-10-sponge-support-plan.md). Its §10 records the 4-Opus + 1-Fable review and its §11 the PR #314 closure; the plan stays normative. This record covers what was decided and measured during implementation.

## Provenance

External PR #314 (StealWonders, EmpireWar fork) added a Sponge plugin on the 1.21.11 line only, against an already-released branch. It proved three designs live, and all three are kept:
- the Sponge raw-data-channel transport;
- the private-dispatcher `/lsslod` bridge;
- the FULL-status load hook.

The user asked for a five-line implementation that fits repo conventions, and decided (mid-implementation) that the 1.21.11 branch is based on the PR head so merging it closes the PR. The PR's separate, unsubmitted `feat/column-access-api` branch (a server-side column filter) is not part of this program.

## Decisions

| Decision | Reason |
| --- | --- |
| SpongeVanilla only; a guard disables the plugin on SpongeNeo/SpongeForge | SpongeNeo runs the NeoForge jar, so a second service would double-serve; SpongeForge (MinecraftForge) has no LSS jar at all |
| Experimental + best-effort on every line | Three of five pins are Sponge RC builds; the exit criterion is in plan §0.2 |
| `sponge/` byte-identical across lines except `SpongeLineSurface` and the two twins | The cross-line catalog proves it; one flavor file is the TestPositions pattern |
| Mixin `compatibilityLevel: JAVA_21` everywhere | Sponge bundles Mixin 0.8.7; its enum ends at JAVA_21 and a JAVA_25 literal fails a required config |
| FULL lambda selected by descriptor, `require = 1, allow = 1` | Synthetic names differ per MC version; fabric-api's 1.21.x names are intermediary |
| Service pump at `tickServer` TAIL via a mixin, not the Sponge scheduler | Sponge's sync scheduler runs at `tickServer` HEAD; every other loader pumps at the end of the tick |
| Shared config root (`config/lss-server-config.yaml`) | The plugin id differs per brand; a per-plugin directory would fork the config across an LSS↔VSS swap |
| DH codec excluded from the jar (the Paper rule) | A server needs no DH decoder |
| `LoaderServices.vanishVisibility()` seam | Melius-only vanish leaked Sponge-vanished players to far viewers |
| Permission descriptions registered at `StartingEngineEvent`, both brand spellings | Sponge has no descriptor defaults; this mirrors plugin.yml after a replacement permission service is final |
| VanillaGradle 0.3.2 on 26.x, 0.2.2 on 1.21.x (via `settings.gradle`) | 0.3.x requires Gradle itself to run on Java 25 |
| Test runtime uses a signature-stripped copy of VanillaGradle's MC jar | VanillaGradle keeps MOJANGCS signatures in the access-widened 26.x jar, and the JVM rejects the widened classes |
| `BenchmarkMetricsExporter` client half split into `ClientMetricsExporter` | The Sponge soak reuses the server half verbatim on a server-only classpath, so the schema has one source (the textual twin Paper needed is avoided) |

## Findings during validation

- **The first Sponge soak settled every session on protocol 19.** Fabric's client sends its handshake on `JOIN`, before its own `minecraft:register` (fabric-networking `ClientPlayNetworkAddon.onServerReady`). Sponge runs the handshake handler at once, and an `isSupportedBy` pre-check in the send adapter dropped the v20 SessionConfig reply. The client then stepped down its discovery ladder about 10 s later. Sponge's `sendTo` checks only the play phase, so the pre-check was removed; this matches Fabric's `ServerPlayNetworking.send`. `SpongeChannelsTest` pins the absence at classfile level, and the soak's `session-version` law is the live pin. Found independently by the soak and by the 26.2 code review.
- SpongeForge runs on MinecraftForge, for which LSS has no jar; the docs and the guard message now say SpongeForge is unsupported, and SpongeNeo uses the NeoForge jar on lines that ship one.

## S0 measurements (spike: `~/projects/lss-sponge/spike`)

SpongeVanilla 1.21.1-12.0.3 and 26.2-20.0.0-RC2743 both did all of the following:
- loaded manifest `MixinConfigs` and `Access-Widener`;
- ran a `tickServer` TAIL mixin (99/100 ticks);
- fired a descriptor-selected FULL hook;
- read a widened field at runtime;
- honored registered permission defaults.

SpongeVanilla also refuses a plugin version not starting with a digit.

## Gates

Each line's full pre-flight passes:
- `:fabric:build` (Tier 1 + 2), `:paper:test`, `:neoforge:build`;
- `:sponge:build` and the two-phase `:sponge:bootSmoke` on the line's pinned SpongeVanilla server;
- `release_check --version`.

The live server-smoke records and the minimal soak are recorded in plan §3 phases S3/S4b.
