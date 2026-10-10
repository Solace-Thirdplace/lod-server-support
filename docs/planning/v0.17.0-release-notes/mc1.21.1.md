### New Features

- **Experimental: terrain from Distant Horizons servers** — Fabric and NeoForge clients with Voxy can show distant terrain from servers running Distant Horizons 3.3.x or DH Support without LSS. Off by default; turn on "Allow DH Server Terrain" in the LSS settings.
- **Fast rejoins on DH servers** — The client remembers which DH terrain it already has, so rejoining downloads only what changed.

### Support and Compatibility

- **Platforms** — Fabric, Paper/Purpur, and NeoForge on Minecraft 1.21.1. The whole line is best-effort; Fabric and NeoForge distant-player rendering are available. Folia is unavailable on this line.
- **Compatibility** — Native LSS always takes priority, and the LSS network protocol is unchanged. Do not install the Distant Horizons client mod alongside; older DH 3.2.x servers are not supported. Not endorsed by the Distant Horizons team.
- **Known limitations** — DH terrain is a block-resolution approximation. Existing Xaero first-spawn gaps, region-boundary shading seams, and shader/LOD-depth limitations remain.
