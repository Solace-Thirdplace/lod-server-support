# LOD Server Support

Enables players with [Voxy](https://modrinth.com/mod/voxy) to see fully rendered terrain out to hundreds of chunks on multiplayer servers without needing to explore the world first. Also includes **Far Players**: players far beyond normal render distance appear in the LOD terrain with name tags, equipment, and mounts.

**Try it live**: join `lod-server-support.modrinth.gg` with Voxy and this mod installed. Supports Minecraft 26.2, 26.1, 1.21.11, and 1.21.1.

https://github.com/user-attachments/assets/721fb344-890e-4e03-ab36-539444427f7b

## Compatibility

Use the matching Fabric or shipped NeoForge artifact. Server support and renderer availability are separate; see the generated [compatibility matrix](docs/compatibility.md). Supported servers:

<!-- LSS SERVER MATRIX START -->
| Minecraft line | Fabric / Paper | Folia | NeoForge shipping | Neo far renderer | Sponge |
| --- | --- | --- | --- | --- | --- |
| 1.21.1 | maintained | unsupported | shipped; best-effort | available | not available |
| 1.21.10 | maintained | unsupported | maintained build only | unsupported | not available |
| 1.21.11 | maintained | experimental | maintained build only | unsupported | not available |
| 26.1 | maintained | experimental | shipped; best-effort | unsupported | not available |
| 26.2 | maintained | experimental | shipped; best-effort | unsupported | not available |
<!-- LSS SERVER MATRIX END -->

**Sponge (experimental):** a SpongeVanilla server plugin ships on the lines the matrix marks shipped. It speaks the same wire as every other server, so any Fabric or NeoForge client with LSS joins it normally. SpongeNeo and SpongeForge servers should use the NeoForge jar instead.

NeoForge 1.21.1 has distinct native and Connector dependency routes. The recorded 2026-09-08 native Voxy 0.2.9-alpha trial was rejected; older successful reports do not establish current compatibility or its failure's upstream cause. Xaero-only legacy Sodium and the modern Connector Voxy route are separate profiles. Use the [dated profile inventory](docs/testing/astra-live-profiles.md) and exact dependency locks; do not combine their jars by filename.

The in-game settings page (Sodium's Video Settings → the LSS entry or tabs; on Fabric also ModMenu's Configure button) renders on both Sodium generations from v0.13.0: on Sodium 0.8+ it appears under LSS's own entry in the settings screen; on Sodium 0.6/0.7 (MC ≤1.21.10 and the 1.21.1 Voxy-fork pairing) it appears as LSS tabs beside Sodium's own. On NeoForge the page renders on both generations too — the 0.6/0.7 tabs on the Voxy-fork pairing, and LSS's own entry on native NeoForge Sodium 0.8+ builds (what the Connector stack pairs with) — without the far-player render options, which NeoForge does not render yet.

Compatible with [AntiXray](https://modrinth.com/mod/anti-xray), [Moonrise](https://modrinth.com/mod/moonrise-opt), [C2ME](https://modrinth.com/mod/c2me-fabric), [ViaVersion](https://modrinth.com/plugin/viaversion)/[ViaBackwards](https://modrinth.com/plugin/viabackwards), and most other mods. Can be run alongside Distant Horizons on the same server to support DH clients and Voxy clients simultaneously. 

For a Voxy player on an existing DH server, ask the admin to add LSS alongside DH. This provides native LSS terrain while preserving support for DH clients.

**Experimental: terrain from Distant Horizons servers (Fabric client on MC 1.21.11).** A Voxy client can show distant terrain from a server that runs the official [Distant Horizons](https://modrinth.com/mod/distanthorizons) server mod but has no LSS. It is on by default. To turn it off, disable "Allow DH Server Terrain" on the LSS settings page, or set `lod.source: lss` in `lss-client-experimental.yaml`. For the best results, servers should still install LSS. What to expect:

- Native LSS always wins. If the server offers LSS service (even disabled), the DH path stays idle.
- Voxy is required, and the Distant Horizons *client* mod must not be installed.
- Changing the option applies on reconnect. This line's NeoForge build is not shipped and has no Voxy route, so the DH path is a Fabric client feature here.
- Supported servers: official DH 3.3.x (protocol 16) and DH Support 15. Older official DH (3.2.x) does not answer this client; `/lss diag` then shows "no DH reply" (the server may run DH 3.2.x or have DH networking off) and nothing further is sent.
- DH sends a block-resolution approximation. It can overwrite more detailed Voxy terrain you built up nearby with vanilla, except chunks your client loaded with vanilla during the current connection.
- Sync on load and live updates follow the server's settings and `lod.dh.sync_on_load` / `lod.dh.real_time_updates`. With sync on load, an edit missed while you are far away or the client is busy appears when that terrain comes back into view after the 20-minute load interval, after `/lss reset`, or on rejoin; with sync off it appears only after `/lss reset`.
- When the world can be identified (the server sends DH's world key and seed), the client keeps small per-server timestamp receipts, so a rejoin does not download terrain it already has. With sync on load, changed tiles are rechecked and refreshed; with sync turned off (on the server or in `lod.dh.sync_on_load`), terrain you already have is kept as it is, like the official DH client, and edits made while you were away are not fetched. `/lss reset` clears receipts.
- With the option on, the client advertises the optional `distant_horizons:msg` channel to every server; on a server without DH it stays idle and sends nothing.
- The experimental keys live in `lss-client-experimental.yaml` next to `lss-client-config.yaml`, so downgrading to a build without this feature keeps the main settings file readable. That file holds only changed values: turning the option off writes `source: lss` there.
- `/lss trace` files include block coordinates; review before sharing. This feature is not endorsed by the Distant Horizons team.

With [Xaero's World Map](https://modrinth.com/mod/xaeros-world-map) 1.42.0 or newer installed on the client, downloaded LOD terrain is also written into the world map, so the map fills in far beyond vanilla render distance (multiplayer only — for single-player worldgen use [Xaero WorldGen](https://modrinth.com/mod/voxyworldgenxaero-bridge) instead). This works even without Voxy: Xaero's Map plus this mod alone will download and map the server's terrain. The bridge is OFF by default (map writes are saved map data — chunks near you stay Xaero's own and Xaero redraws its tiles whenever you revisit an area, but distant LOD-drawn tiles, slightly simplified and matching any anti-x-ray masking the server applies, persist until you do): turn it on with the "Write LODs to Xaero's Map" toggle on the LSS Sodium options page, or `integrations.xaero.enabled` in `lss-client-config.yaml`, then run `/lss reload` and reconnect. Tiles go to the map's surface layer; while the map is showing a cave layer (underground with auto cave mode, and the Nether by default) terrain that arrives is not written to the map and is not retried — revisiting the area (or `/lss clearcache`) backfills it — and the bridge follows Xaero's own "Load New Chunks" / "Update Chunks" switches. While the map is catching up on a big download, LOD delivery is paced to what the map can draw (`enableXaeroMapBackpressure`, default on), so the map fills in completely as it goes at a slightly slower rate — including brief full pauses of the LOD download (a few seconds) while the map itself is busy writing the terrain around you. On a server you had already explored before installing, run `/lss clearcache` once while connected to re-stream the terrain and backfill the map (a full re-download).

LOD Server Support is backwards and forwards compatible from v0.4.0 through the current version. Server operators can freely update to take advantage of improvements without breaking clients on older versions, and clients can update without breaking compatibility with older servers.

**Far players.** Players beyond your normal render distance are drawn as player models in the LOD terrain, lit as if under open sky (or at full brightness, mounts included, with the "Full Bright Far Players" option / `far_players.full_bright` in `lss-client-config.yaml`), with their name tags (also drawn over players still in normal range once they are past the game's own 64-block tag distance) and, within about 80 blocks, their skin overlay layers (farther out the depth buffer cannot separate that thin shell from the body). Two limits worth knowing: players past the game's far plane (your render distance × 64 blocks; Iris shaders extend it) are not drawn at all, whatever the far-player render limit says; and with Iris shaders, packs whose Voxy integration keeps LOD depth out of the vanilla depth buffer (Complementary, for one) draw far players on top of LOD terrain instead of behind it.

[Voxy Server Side](https://modrinth.com/plugin/voxy-server-side) is the same mod. Voxy Server Side clients are compatible with LOD Server Support servers and vice versa.

## Installation

Install **LOD Server Support** on **both** the **server** (Fabric, Paper, NeoForge on a line that ships it, or the experimental Sponge plugin on SpongeVanilla) and **every participating client** (the matching Fabric or NeoForge LSS mod, with Voxy or the enabled Xaero map bridge). The server and client both need LSS for its terrain download service.

NeoForge ships on MC 1.21.1, 26.1 and 26.2; the 1.21.10/1.21.11 modules remain maintained builds. NeoForge far-player rendering is live on 1.21.1 and remains an intentional stub on the other lines. See the [loader/artifact matrix](docs/planning/per-version-surfaces.md#current-loader-and-artifact-surfaces-2026-09-08) and [validation profiles](docs/testing/astra-live-profiles.md) for the separate packaging, consumer and live-test boundaries.

On SpongeVanilla, put the Sponge jar in the server's `mods/` folder. At startup Sponge prints a notice that the plugin uses Mixins; that is expected.

## Commands

### Server (Fabric, NeoForge, Paper, and Sponge)

- `/lsslod stats` - Show per-player transfer statistics
- `/lsslod diag` - Show detailed diagnostics (config, bandwidth, queue depths)
- `/lsslod reload` - Validate server YAML and apply supported changes; report restart requirements
- `/lsslod store status` - Show LOD store status (state, hit/miss counters, size)
- `/lsslod store backfill start|stop|status` - Control the background pre-warm walk (not on Paper)
- `/lsslod help` - List all commands

### Client (Fabric and supported NeoForge clients)

- `/lss reload` - Read saved client YAML and apply supported changes; report reconnect requirements

- `/lss clearcache` - Clear the local column cache, forcing all chunks to be re-requested from the server
- `/lss reset` - Wipe this server's LODs (local cache and Voxy's stored data) and re-stream them fresh
- `/lss reset voxy-force` - Same, but for the case where another mod has redirected Voxy's storage (a replay mod, or any other storage override) and the ordinary reset therefore left Voxy's disk data alone. Shows both storage paths first and deletes nothing until you run `/lss reset voxy-force confirm` within 60 seconds on the same connection
- `/lss diag` - Show client-side diagnostics (connection, throughput, scan progress, request budget)
- `/lss trace` - Start or stop a local event trace for native LSS or experimental DH terrain loading. Traces include movement coordinates and stay in the instance's `logs` directory.

## Configuration

Settings use annotated YAML: `config/lss-server-config.yaml` and
`config/lss-client-config.yaml` on Fabric/NeoForge (the server file also on Sponge), or
`plugins/LodServerSupport/lss-server-config.yaml` on Paper. VSS uses the corresponding
`vss-` names, adopting an existing file across brands. Startup migrates an old JSON
file once and retains both the original and an exact-byte backup. Existing distance
choices are preserved; fresh servers use **512 chunks in the Overworld and End,
64 in the Nether**.

Migration accepts comments and the old loader's loose JSON syntax. It is one-way:
v0.15.x reads the retained JSON and ignores YAML edits, so copy any desired changes
back into JSON before downgrading. `/lsslod set` was removed; edit YAML and run
`/lsslod reload` instead.

Edit the file and run `/lsslod reload` on the server or `/lss reload` on the client.
Sodium Apply **saves and reloads automatically**. Direct YAML edits still need the
reload command. The menus support English, Simplified Chinese and Traditional Chinese.
Invalid files leave active settings unchanged. Reload reports values that require a
restart or reconnect; it does not rewrite comments or normalized values.

| Setting group | Purpose |
| --- | --- |
| `lod.distance` | Server distance defaults and dimension overrides; Paper adds exact world-name overrides. |
| `generation` | Enable generation, bound concurrent work, and set ticket timeouts in ticks. Disabling stops new admissions while existing work drains. |
| `network` | Bandwidth limits in MiB/s before compression, queues, pacing and yielding to vanilla traffic. |
| `storage.disk` | Read concurrency and reader-pool configuration. Concurrency reloads; changing the pool requires a restart. |
| `storage.lod_store` | Cache processed columns to save repeated parsing/serialization CPU, using additional disk space that can be comparable to the world itself. Cap, resweep and backfill controls reload; enabling the store requires a restart. |
| `privacy.xray` | Anti-xray policy and fallback masking. Changes require a restart. |
| `far_players` | Distant player display/sharing policies and distances in blocks. Players control local rendering manually. |

The client cache uses `cache.use_world_sub_buckets` to separate remote worlds and
`cache.address_aliases` to group equivalent addresses. With Voxy, aliases also need
matching LoD Mirror/storage corroboration; otherwise LSS retains separate address
caches. Changes take effect on the next physical connection. `/lss diag` shows the
active identity. See the [complete settings reference](docs/reference/settings.md)
for exact keys, units, limits, defaults, zero meanings and activation timing.

### Server Performance Tuning

See [current performance diagnosis and tuning](docs/operations/performance.md) for workload-based checks, setting semantics and the measurement requirements for tuning. The [settings reference](docs/reference/settings.md) records exact domains and apply timing.

## Redistribution

This mod is MIT-licensed, redistribution with attribution is welcome, and modpacks can reference the official Modrinth project directly. Per Modrinth's reupload policy: [XANTHA](https://modrinth.com/user/XANTHA) via [Voxy Server Side](https://modrinth.com/plugin/voxy-server-side) has the copyright holder's explicit permission to distribute this mod, and derivatives of it, on Modrinth.

<!-- LSS COMPATIBILITY START -->
Current platform, shipping and renderer facts: [compatibility matrix](docs/compatibility.md). Dependency locks describe candidates; dated feature evidence establishes tested combinations.
<!-- LSS COMPATIBILITY END -->

Thanks to SeeU for the inspiration for distant player rendering.
