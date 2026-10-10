# Porting the private DH live rig to another MC line

(This copy is the **1.21.10** rig, ported from the 1.21.11 copy. The 26.2 tables below are the
original source, the "26.1 port" and "1.21.11 port" sections record the intermediate steps, and
the "1.21.10 port" section records every 1.21.10 value.)

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

## 1.21.11 port (2026-10-10)

`line.py` 1.21.11 values, all checked against the line's own artifacts:

| Key | 1.21.11 | How it was checked |
| --- | --- | --- |
| `minecraft` | `1.21.11` | Prism `net.minecraft` component; `version.json` id. |
| `java*` | Java 21 (`/usr/lib/jvm/java-21-openjdk-amd64`), release 21 | `version.json` `java_version` 21; mixin `compatibilityLevel` `JAVA_21`. Server, client and settings codec all run on it. |
| `neoforge*` | `21.11.45`, `servers/neoforge-1.21.11` | DH 3.3.3 for 1.21.11 (`assets/dh-1.21.11.jar`) declares `neoforge [*,)` and Fabric Loader `>=0.17.3`; the official branch stays NeoForge. Only `libraries/` is copied. |
| `paper_*` | `servers/paper-1.21.11` (1.21.11 build 132, `c5eb079`), `5ffef465…` | `version_history.json`, `sha256sum paper.jar`. |
| `dh_default` | `assets/dh-1.21.11.jar` (`96c2e887…`) | SHA256SUMS; `minecraft` `[1.21.11]`. |
| `client_mods`, `client_mod_pins` | Fabric only: Voxy **0.2.9-alpha**, Fabric API **0.141.1**, Sodium **0.8.2** (Prism `lss-test-1.21.11`); `neoforge` is `None` | No NeoForge client route to Voxy on this line; `prepare.py` refuses `--loader neoforge`. Voxy 0.2.9 has every reflected member (`WorldIdentifier.of/getNullable`, `WorldEngine.getMapper/acquireIfExists`, `WorldSection.copyData/release`, `Mapper.getBlockId/getBlockStateFromBlockId`, `RenderStatistics.enabled`, `VoxyRenderSystem.renderOpaque(Viewport)`; the race-only `VoxelIngestService.rawIngest0/processJob` and `WorldUpdater.insertUpdate`). Sodium 0.8.2 has `ConfigManager.CONFIG`, `Config.options`/`getOption(Identifier)`/`getModOptions`/`applyAllOptions`, `ModOptions.configId/pages`, `Page.name`, `VideoSettingsScreen.createScreen/jumpToPage`, `StatefulOption.modifyValue/getAppliedValue`, `Option.isEnabled/getName`. All checked with `javap` (intermediary descriptors). |
| `prism_components` | lwjgl3 **3.3.3**, minecraft 1.21.11, intermediary 1.21.11, fabric-loader **0.18.4** | Instance `mmc-pack.json`; metadata cached in the private Prism context. |
| `fabric_loader_floor` | `>=0.18.4` | The instance's loader. |
| `mob_spawning_off` | `gamerule spawn_mobs false` | `GameRules` (`eua`) constant pool in the extracted server jar. |
| `datapack_format` | `{'min_format':[94,1],'max_format':94}` | `version.json` `data_major/minor` 94.1. |
| `overworld_dimension_type` | 1.21.11 vanilla `overworld.json`: the attribute schema, but **without** `minecraft:visual/ambient_light_color` and `default_clock` (26.x adds both) | Copied from the 1.21.11 extracted server jar. |
| End skylight | `has_skylight: true` (as 26.x) | 1.21.11 `the_end.json`; the lifecycle End branch is derived from `hasSkyLight()`. |
| `fabric_remap` | loom `mappings.tiny` (`loom.mappings.1_21_11.layered+hash.2198-v2`, `fee2824b…`) + tiny-remapper 0.10.3 fat jar, named → intermediary | As on 1.21.1; `build.py` records the remap receipt (mappings/remapper/named observer hashes). |
| `fixture_link_libraries` | `servers/neoforge-1.21.11/libraries` + private Prism libraries | |

Java API column (26.1 → 1.21.11), found with `javap` on the line's named merged jar and by
compiling the Fabric observer against the exported 1.21.11 classpath:

| 26.1 | 1.21.11 |
| --- | --- |
| `ChunkPos.x()`/`.z()` | fields `ChunkPos.x`/`.z` |
| `ChunkPos.pack(x,z)` | `ChunkPos.asLong(x,z)` |
| mixin `compatibilityLevel` `JAVA_25` | `JAVA_21` |

Unchanged from 26.1: `Identifier`, `getMinY/getMaxY`, `mc.screen`/`mc.setScreen`,
`mc.options.hideGui`, `mc.getMainRenderTarget()` with the 5-arg `Screenshot.grab`,
`mc.disconnectFromWorld(ClientLevel.DEFAULT_QUIT_MESSAGE)`, `player.permissions()` +
`Permissions.COMMANDS_OWNER/GAMEMASTER`, `gameRenderer.getMainCamera().position()`,
`Blocks.RED_CONCRETE` etc.

Behavioral, found live: the Fabric Sodium menu shows **11** LSS controls (the 1.21.11 Fabric
far-player renderer is available); the End has skylight (lifecycle `dimension_has_skylight:
true`, zero no-sky samples); the official DH server and DH Support both serve without any
other line-specific change. Every Fabric case of the matrix (service, Support, rejoin, menu,
no-service, conflict, updates, lifecycle, moving boundary) passed on the first attempt.

Driver/rig changes for this line:

- **Fabric scenario coverage widened.** `prepare.py` now also lets the Fabric observer run
  `updates`, `lifecycle` and DH-terrain `boundary` (the observer code is loader-neutral);
  the native boundary arm stays NeoForge-only because it stages the candidate on the NeoForge
  server. `no-service` and `conflict` were already `--scenario service` expectations; the
  conflict case stages the official DH 3.3.3 combined jar into the Fabric client.
- **Storage.** The generic rig unittests (`tools/rig`) refuse when the Windows host volume is
  below the 50 GiB reserve (they run the real preflight). The live cases use
  `--bounded-storage` (40 GiB reserve + 2 GiB growth). The drivers record the refusal as
  environmental only when every failure carries that exact message.

## 1.21.10 port (2026-10-10)

`line.py` 1.21.10 values, all checked against the line's own artifacts:

| Key | 1.21.10 | How it was checked |
| --- | --- | --- |
| `minecraft` | `1.21.10` | Prism `net.minecraft` component; `version.json` id. |
| `java*` | Java 21 (`/usr/lib/jvm/java-21-openjdk-amd64`), release 21 | `version.json` `java_version` 21; mixin `compatibilityLevel` `JAVA_21`. |
| `neoforge*` | `21.10.64`, a **new private server** `build/dh-ports/1.21.10/neoforge-server-1.21.10` | No cached 1.21.10 NeoForge or Fabric server existed. DH 3.3.3 for 1.21.10 (`assets/dh-1.21.10.jar`) declares `neoforge [21.10.6-beta,)` and Fabric Loader `>=0.17.3`; NeoForge 21.10.64 (the line's own `neoforge_version`) keeps `run.py` unchanged. Built with `java -jar neoforge-21.10.64-installer.jar --install-server` (Java 21) from `https://maven.neoforged.net/releases/net/neoforged/neoforge/21.10.64/neoforge-21.10.64-installer.jar`, sha256 `6adb1f063358b22b050834323a4f68df47c93d082bc49a8a0a97aec22b789427` (sha1 `fbf109b7…`; both equal the published `.sha256`/`.sha1`). The installer fetched the vanilla server from `piston-data.mojang.com` (`95495a7f…/server.jar`, 1.21.10). Installed `neoforge-21.10.64-universal.jar` sha256 `51dc4f84…`, `unix_args.txt` `0cb4f8e8…`; the full library manifest (82 files) is `server-provenance/libraries-sha256.json`. EULA is accepted only in each owned run copy; only `libraries/` is staged. |
| `paper_*` | `servers/paper-1.21.10` (1.21.10 build 130, `8043efd`), `158703f7…` | `version_history.json`, `sha256sum paper.jar`. Copied, never run in place. |
| `dh_default` | `assets/dh-1.21.10.jar` (`e987a0ab…`) | SHA256SUMS; `minecraft` `[1.21.10]`. |
| `client_mods`, `client_mod_pins` | Fabric only: Voxy **0.2.9-alpha** (`+1.21.10`), Fabric API **0.138.4**, Sodium **0.7.3** (Prism `lss-test-1.21.10`); `neoforge` is `None` | Voxy 0.2.9 for 1.21.10 is `javap`-identical to the 1.21.11 build for every reflected class (`WorldIdentifier`, `WorldEngine`, `WorldSection`, `Mapper`, `WorldUpdater`, `VoxelizedSection`, `VoxyRenderSystem`, `VoxelIngestService`, `RenderStatistics`) and the `processJob` call sites (`ConcurrentLinkedDeque.pop`, `WorldUpdater.insertUpdate`) are unchanged. |
| `prism_components` | lwjgl3 3.3.3, minecraft 1.21.10, intermediary 1.21.10, fabric-loader 0.18.4 | Instance `mmc-pack.json`; metadata and libraries already cached in the private Prism context. |
| `fabric_loader_floor` | `>=0.18.4` | The instance's loader. |
| `mob_spawning_off` | `gamerule doMobSpawning false` | 1.21.10 still has the camelCase ids (`doMobSpawning` in the server jar; no `spawn_mobs`). |
| `datapack_format` | `{'min_format':[88,0],'max_format':88}` | `version.json` `data_major/minor` 88.0 (1.21.9+ `min_format`/`max_format` form). |
| `overworld_dimension_type` | 1.21.10 vanilla `overworld.json`: the **flat-field** schema (`effects`, `bed_works`, `natural`, `has_raids`, `piglin_safe`, `ultrawarm`, `cloud_height`, …) | Copied from the 1.21.10 extracted server jar (1.21.11 moved to the attribute schema). |
| End skylight | `has_skylight: true` | 1.21.10 `the_end.json`; the lifecycle End branch is derived from `hasSkyLight()`. |
| `fabric_remap` | loom `mappings.tiny` (`loom.mappings.1_21_10.layered+hash.2198-v2`, `113202bb…`) + tiny-remapper 0.10.3 fat jar, named → intermediary | As on 1.21.11. |
| `fixture_link_libraries` | the private server's `libraries/` + private Prism libraries | |

Java API column (1.21.11 → 1.21.10), found with `javap` on the line's named merged jar and by
compiling the Fabric observer against the exported 1.21.10 classpath:

| 1.21.11 | 1.21.10 |
| --- | --- |
| `Identifier`, `Identifier.parse`, `ResourceKey.identifier()` | `ResourceLocation`, `ResourceLocation.parse`, `location()` |
| `player.permissions().hasPermission(Permissions.COMMANDS_OWNER/GAMEMASTER)` | `player.hasPermissions(4)` / `(2)` |
| Sodium 0.8 public config API (`ConfigManager.CONFIG`, `getOption(Identifier)`, `VideoSettingsScreen`) | legacy `SodiumOptionsGUI` page list (below) |

Unchanged from 1.21.11: `getMinY/getMaxY`, `mc.screen`/`mc.setScreen`, `mc.options.hideGui`,
`mc.getMainRenderTarget()` with the 5-arg `Screenshot.grab`, `mc.disconnectFromWorld(ClientLevel.DEFAULT_QUIT_MESSAGE)`,
`gameRenderer.getMainCamera().position()`, fields `ChunkPos.x/.z` + `ChunkPos.asLong`, `Blocks.RED_CONCRETE` etc.

**Legacy Sodium menu probe.** Sodium stops at 0.7.3 on 1.21.10, so LSS renders its options
through `LegacySodiumPage` + the `SodiumLegacyOptionsHook` constructor mixin into the internal
`SodiumOptionsGUI` page list. `Probe.menu` and `Probe.sodiumApply` (used by `updates`) now drive
that screen: `SodiumOptionsGUI.createScreen` (instance-checked), the injected pages via
`LegacyOptionsScreenHandle.lss$injectedPages()` (each must be identity-present in the screen's
`pages`), option lookup by the catalog's translated name key (legacy options carry no id),
`setValue`/`getValue`/`isAvailable` on `OptionImpl`, `setPage` + `currentPage` for the page jump,
and the screen's private `applyChanges()` for Apply. The probe refuses if the 0.8 `ConfigManager`
is present. Checks kept from the 0.8 arm: the LSS control count (11 with the far-player renderer,
else 7), only catalog controls render (the config-only DH profile/sync/real-time keys have no
catalog entry), `Allow DH Server Terrain` is a `TickBoxControl` that greys with `Receive Server LODs`
(staged value) and re-enables, the active value loads as `true`, Auto GUI scale at 1280x720,
screenshots, diagnostics export, and Apply persisting `source: lss` with sync/updates preserved.
The legacy tab names are `LSS` and `LSS Players` (the brand prefix + `lss.config.far_players.legacy_tab`).

Live status at the port commit: the gate build is green and the observer compiles and remaps
against the 1.21.10 classpath unchanged apart from the column above. The official DH 3.3.3
service case passed on the new NeoForge 21.10.64 server (READY, 64 requests / 63 matched replies,
29 tiles, 400 columns converted, 29,760 Voxy storage samples read back, 0 failed columns). Every
other case was refused before staging by the bounded storage preflight: the Windows host volume
had fallen to ~41.8 GiB free, below the 40 GiB reserve + 2 GiB growth + copy budget (~42.25 GiB),
and each owned run grows the VHD by ~0.25 GiB that freeing files does not return. The legacy
Sodium menu probe is therefore compiled and wired but not yet live-proven on this line.

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

The 1.21.11 drivers live in `build/dh-ports/1.21.11/` with the same three steps. Its
`build-candidate.py` builds with Java 21 and includes `:fabric:runClientGameTest` on a private
Xvfb display; `finalize-build.py` builds only the Fabric observer and additionally runs the
catalog checks and the `tools/*` unittests; `run-live.py` lists the Fabric-only cases.

The 1.21.10 drivers live in `build/dh-ports/1.21.10/` (copied from 1.21.11 with the line
substituted: Java 21, `:fabric:runClientGameTest` on a private Xvfb display, Fabric-only cases),
together with `server-provenance/` (installer, published checksums, install log, library manifest)
and the private `neoforge-server-1.21.10/` the official DH case runs from.
