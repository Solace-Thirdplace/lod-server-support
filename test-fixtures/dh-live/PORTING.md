# Porting the private DH live rig to another MC line

(This copy is the **26.1** rig; the 26.2 tables below are the source it was ported from,
and the "26.1 port" section records every 26.1 value.)

The rig in this directory is the 1.21.1 `test-fixtures/dh-live` rig (branch
`feat/dh-codec-p1-mc1.21.1`, see README.md for the verification model) adapted to
**MC 26.2**. Every line-specific value lives in `line.py`; the scripts read nothing
version-specific elsewhere. To adapt 26.1, 1.21.11 or 1.21.10, copy this directory,
edit `line.py`, apply the Java API column below, and rerun the fixture build.

## `line.py` parameters (26.2 values)

| Key | 26.2 | Notes for other lines |
| --- | --- | --- |
| `minecraft` | `26.2` | Fabric observer `depends.minecraft`; remap receipt label. |
| `java`, `javac`, `java_release` | JDK 25 (`~/.local/jdk/jdk-25.0.2+10`), release 25 | 26.1: same JDK 25. 1.21.10/1.21.11: Java 21 (`/usr/lib/jvm/java-21-openjdk-amd64`), release 21, and mixin `compatibilityLevel` `JAVA_21`. Server, Prism client and settings codec all use this JDK. |
| `neoforge`, `neoforge_server`, `neoforge_server_args` | `26.2.0.59`, `lss-multi-test/servers/neoforge-26.2`, `@libraries/net/neoforged/neoforge/26.2.0.59/unix_args.txt` | The official DH server runs on NeoForge because DH 3.3.3 (26.2) needs Fabric Loader >= 0.19.5 and the cached Fabric 26.2 server ships 0.19.3. Check each line's DH `fabric.mod.json`/`neoforge.mods.toml` first. Cached server dirs: `neoforge-26.1`, `neoforge-1.21.11` exist; there is no `neoforge-1.21.10` or `fabric-1.21.10`, so 1.21.10 needs a new server dir (NeoForge installer under `lss-multi-test/downloads` if one is cached, else a Fabric server with loader >= DH's floor plus a Fabric launch branch in `run.py`). Libraries are copied, never run in place. |
| `paper_server`, `paper_sha256` | `servers/paper-26.2`, `defe82c1…` | Copied `paper.jar` + `cache/`, `libraries/`, `versions/`. |
| `dh_support_plugin`, `dh_support_sha256` | DH Support 0.14.0 (`3dd299bb…`) | Same plugin on every line (Bukkit `api-version: 1.16`). |
| `dh_default`, `dh_default_sha256` | `build/dh-ports/assets/dh-26.2.jar` (`abd4b850…`) | Per-line jar in `build/dh-ports/assets` (SHA256SUMS). |
| `client_mods`, `client_mod_pins` | Prism `lss-test-26.2` (Fabric: Voxy 0.2.18, Fabric API 0.155.2, Sodium 0.9.1) and `lss-test-neo-26.2` (NeoForge: Voxy 0.2.18, Foxy 1.1.0, Sodium 0.9.1) | Read-only copies from `/mnt/c/.../PrismLauncher/instances`; every jar is SHA-pinned. 1.21.10/1.21.11 have no NeoForge Voxy route: run Fabric only. |
| `prism_components` | lwjgl3 3.4.1, minecraft 26.2, neoforged 26.2.0.59 / intermediary 26.2 + fabric-loader 0.19.3 | Copy from the Prism instance's `mmc-pack.json`; the component metadata and libraries must already be cached in `~/.local/share/lss-rig-prism-auth` (`meta/<uid>/<version>.json`), because the launcher closure is frozen at prepare time. |
| `fabric_loader_floor` | `>=0.19.3` | Fabric observer `depends.fabricloader`. |
| `datapack_format` | `{'min_format':[107,1],'max_format':107}` | From the vanilla jar's `version.json` `pack_version.data_major/minor`; copy the line's own vanilla `pack.mcmeta` form (1.21.1 used `{'pack_format':48}`). |
| `overworld_dimension_type` | vanilla 26.2 `dimension_type/overworld.json` (environment-attribute schema) | The tall fixture overrides only `height`/`logical_height` 512 and `min_y` -128. 1.21.1 used the flat-field schema (`effects`, `bed_works`, `natural`, …); 26.2 uses `attributes`/`timelines`. Copy the line's own vanilla `data/minecraft/dimension_type/overworld.json` (e.g. from `~/.gradle/caches/fabric-loom/<mc>/minecraft-extracted_server.jar`). |
| `fabric_remap` | `None` | 26.x is unobfuscated: the Fabric observer compiles against Mojang names and ships unremapped. 1.21.x lines need `{'mappings': <loom mappings.tiny>, 'remapper': <tiny-remapper fat jar>}` (named → intermediary), as on 1.21.1. |
| `fixture_link_libraries` | NeoForge server libraries + private Prism libraries | Extra javac link path for transitive MC/loader types. |

## Java API adaptations in `src/` (1.21.1 → 26.2)

Found by compiling against the 26.2 classpath; 26.1 shares nearly all of them, while
1.21.10/1.21.11 sit in between (check each with `javap` on the line's client jar).

| 1.21.1 | 26.2 |
| --- | --- |
| `ResourceLocation`, `ResourceKey.location()` | `Identifier`, `identifier()` (also the Sodium `getOption(Identifier)` lookups) |
| `getMinBuildHeight()` / `getMaxBuildHeight()` | `getMinY()` / `getMaxY()+1` (inclusive max) |
| `mc.screen`, `mc.setScreen(s)` | `mc.gui.screen()`, `mc.gui.setScreen(s)` |
| `mc.options.hideGui=true` | `if(!mc.gui.hud.isHidden())mc.gui.hud.toggle()` |
| `mc.level.disconnect(); mc.disconnect();` | `mc.disconnectFromWorld(ClientLevel.DEFAULT_QUIT_MESSAGE)` (vanilla PauseScreen path) |
| `Screenshot.grab(dir,name,mc.getMainRenderTarget(),cb)` | `Screenshot.grab(dir,name,mc.gameRenderer.mainRenderTarget(),1,cb)` |
| `player.hasPermissions(4)` / `(2)` | `player.permissions().hasPermission(Permissions.COMMANDS_OWNER/GAMEMASTER)` |
| `ChunkPos.x`/`.z`, `ChunkPos.asLong(x,z)` | `x()`/`z()`, `ChunkPos.pack(x,z)` |
| `Blocks.RED_CONCRETE` etc. | `Blocks.CONCRETE.red()` (`ColorCollection`) |
| `gameRenderer.getMainCamera().getPosition()` | `gameRenderer.mainCamera().position()` |
| mixin `compatibilityLevel` `JAVA_21` | `JAVA_25` |

Reflected third-party members were checked with `javap` against the pinned jars and
are unchanged: Voxy 0.2.18 (`WorldIdentifier.of/getNullable`, `WorldEngine.acquire*`,
`Mapper.getBlockId/getBlockStateFromBlockId`, `WorldSection.copyData/release`,
`RenderStatistics`, `VoxyRenderSystem.renderOpaque` — `renderTranslucent` no longer
exists, the multi-name injector still matches one target) and Sodium 0.9.1 (nested in
`META-INF/jarjar` on NeoForge; `ConfigManager.CONFIG`, `getOption`, `getModOptions`,
`VideoSettingsScreen.createScreen/jumpToPage`, `StatefulOption.modifyValue/getAppliedValue`).

## Behavioral line parameters found live

- **Sodium menu control count** is derived, not fixed: 11 when the loader's
  `FarPlayerRenderer.RENDER_AVAILABLE` is true, else 7 (four far-player render options
  are visibility-gated). 26.2 NeoForge has no far-player renderer → 7; 26.2 Fabric and
  1.21.1 both loaders → 11. `dh-menu.json` records `lss_controls` and
  `far_player_render_available`.
- **Game rule ids**: 26.2 uses `gamerule spawn_mobs false` (1.21.1: `doMobSpawning`);
  `line.py` `mob_spawning_off`. Check the line's `GameRules` ids; a wrong id only logs
  "Incorrect argument" in `server.log`.
- **World spawn** of seed 8675309 differs per line (26.2: chunk 1,4). The lifecycle
  readback now prunes samples that became vanilla-held after the teleport, as the
  service observer already did.
- **End skylight**: 26.2's `the_end` dimension type has `has_skylight: true` (1.21.1:
  none; check the line's file), so the lifecycle End step expects sky-lit sections and zero no-sky samples when
  `level.dimensionType().hasSkyLight()`; the Nether stays the no-sky check.
- **NeoForge 26.x payload registrar**: `playBidirectional(type,codec,handler)` registers
  the handler for the serverbound flow only (also NeoForge 26.1, 21.11, 21.10; not
  21.1). This was a product defect in the DH transport (fixed on this branch, see
  `NeoForgeDhTransportContractTest`); every NeoForge line newer than 21.1 needs the
  four-argument form.

## 26.1 port (2026-10-10)

`line.py` 26.1 values, all checked against the line's own artifacts:

| Key | 26.1 | How it was checked |
| --- | --- | --- |
| `minecraft` | `26.1.2` | Fabric version id of the Prism `net.minecraft` component. |
| `java*` | JDK 25, release 25 | `version.json` `java_version` 25. |
| `neoforge*` | `26.1.2.95`, `servers/neoforge-26.1` | DH 3.3.3 for 26.1.x needs Fabric Loader >= 0.18.5 only (a Fabric server would also work), but the rig's official branch stays NeoForge. |
| `paper_*` | `servers/paper-26.1` (26.1.2 build 74), `1d70b1da…` | `version_history.json`. |
| `dh_default` | `assets/dh-26.1.2.jar` (`3df8273f…`) | SHA256SUMS; `minecraft` `[26.1.0],[26.1.1],[26.1.2]`. |
| `client_mod_pins` | NeoForge: Voxy **0.2.16**, Foxy 1.1.0, Sodium **0.8.12**; Fabric: Voxy 0.2.18, Fabric API 0.155.2, Sodium 0.9.1 (all `+26.1.2` jars) | Prism `lss-test-neo-26.1` / `lss-test-26.1`. Voxy 0.2.16 has every reflected member and mixin target (`renderOpaque(Viewport)` single-arg, the multi-name injector still matches one target; `processJob`'s `ConcurrentLinkedDeque.pop`/`WorldUpdater.insertUpdate` call sites). Sodium 0.8.12 (nested in `META-INF/jarjar`) has the same `ConfigManager.CONFIG`, `Config.options`/`getOption(Identifier)`/`getModOptions`/`applyAllOptions`, `ModOptions.configId/pages`, `Page.name`, `VideoSettingsScreen.createScreen/jumpToPage`, `StatefulOption.modifyValue/getAppliedValue` surface. |
| `prism_components` | lwjgl3 3.4.1, minecraft 26.1.2, neoforged **26.1.2.48-beta** / intermediary 26.1.2 + fabric-loader 0.19.3 | Instance `mmc-pack.json`; metadata cached in the private Prism context. |
| `fabric_loader_floor` | `>=0.19.3` | unchanged. |
| `mob_spawning_off` | `gamerule spawn_mobs false` | `GameRules` constant pool (`spawn_mobs`). |
| `datapack_format` | `{'min_format':[101,1],'max_format':101}` | `version.json` `data_major/minor` 101.1; vanilla packs use `min_format`/`max_format`. |
| `overworld_dimension_type` | identical to 26.2 | Equality-checked against the 26.1.2 server jar's `overworld.json`. |
| End skylight | `has_skylight: true` (as 26.2) | 26.1.2 `the_end.json`; the lifecycle End branch is already derived from `hasSkyLight()`. |
| `fabric_remap` | `None` | 26.x is unobfuscated. |
| `fixture_link_libraries` | `servers/neoforge-26.1/libraries` + private Prism libraries | |

Java API column (26.2 → 26.1), found with `javap` on the 26.1.2 merged jar and by
compiling both observers against the exported 26.1 classpaths:

| 26.2 | 26.1 |
| --- | --- |
| `mc.gui.screen()`, `mc.gui.setScreen(s)` | `mc.screen`, `mc.setScreen(s)` |
| `if(!mc.gui.hud.isHidden())mc.gui.hud.toggle()` | `mc.options.hideGui=true` |
| `mc.gameRenderer.mainRenderTarget()` | `mc.getMainRenderTarget()` (the 5-arg `Screenshot.grab(dir,name,target,1,cb)` is unchanged) |
| `Blocks.CONCRETE.red()` etc. | `Blocks.RED_CONCRETE` etc. |
| `gameRenderer.mainCamera().position()` | `gameRenderer.getMainCamera().position()` |

Unchanged from 26.2: `Identifier`, `getMinY/getMaxY`, `disconnectFromWorld(DEFAULT_QUIT_MESSAGE)`,
`player.permissions()`, `ChunkPos.x()/z()/pack`, mixin `JAVA_25`.

Behavioral: 26.1 NeoForge has no far-player renderer (`RENDER_AVAILABLE=false`), so the
NeoForge Sodium menu shows 7 LSS controls; Fabric shows 11. The 26.x NeoForge
`playBidirectional` four-argument fix applies here too (cherry-picked as `1f11f64f`).

## Build/driver changes

- `bounded_storage.py` moved here from `test-fixtures/dh-p4` (not ported); `prepare.py`
  and `run.py` import it from this directory.
- `exercise.py --base` is required (the 1.21.1 default pointed at a 1.21.1 manual-test
  directory); `--dh` defaults to the pinned line jar and is hash-checked.

## Driving it (26.2 evidence layout)

The 26.2 drivers live outside the repo in `build/dh-ports/26.2/` (adapted from the 1.21.1
`build/dh-neoforge/2026-10-10/remediation/` scripts):

1. `build-candidate.py <copy> <version>` rsyncs the worktree to `/tmp/lss-dh-port-26.2-*`
   and runs the normal build plus `-I test-fixtures/dh-live/classpath.gradle
   :neoforge:exportDhFixtureClasspath :fabric:exportDhFixtureClasspath` through
   `gradle-when-free.sh` (shared harness lock).
2. `finalize-build.py <copy> <version> [--fixture-only]` records unit totals,
   release_check/check_artifacts, the production/test manifests, compiles both loader
   observers with `build.py` and writes `<loader>/verification.json`.
3. `run-locked.sh <log> <case...>` holds the harness lock (bounded wait) and runs
   `run-live.py` under `harness_run_script`; each case is `exercise.py --bounded-storage`
   in a fresh owned run dir, and `live-matrix.json` collects the proofs.
