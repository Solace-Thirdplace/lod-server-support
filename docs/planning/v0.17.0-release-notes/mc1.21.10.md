### New Features

- **Experimental: terrain from Distant Horizons servers** — Fabric clients with Voxy can show distant terrain from servers running Distant Horizons 3.3.x or DH Support without LSS. Off by default; turn on "Allow DH Server Terrain" in the LSS settings.
- **Fast rejoins on DH servers** — The client remembers which DH terrain it already has, so rejoining downloads only what changed.

### Support and Compatibility

- **Platforms** — Fabric and Paper/Purpur for Minecraft 1.21.10, with correct-not-perfect support; Folia is unavailable.
- **Compatibility** — Native LSS always takes priority, and the LSS network protocol is unchanged. Do not install the Distant Horizons client mod alongside; older DH 3.2.x servers are not supported. Not endorsed by the Distant Horizons team.
- **Known limitations** — DH terrain is a block-resolution approximation. Existing Xaero first-spawn gaps, region-boundary shading seams, and shader/LOD-depth limitations remain.
