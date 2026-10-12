### New Features

- **Minecraft 26.3** — Now supported. Voxy has no 26.3 release yet, so until it does you get maps and far players but no LOD terrain.
- **JourneyMap** — LOD terrain now fills JourneyMap too, not just Xaero's World Map. On by default for new installs.
- **LODs off per world** — Set a world's LOD distance to 0 to turn LODs off there.
- **Sponge (experimental)** — Runs on SpongeVanilla servers; put the jar in `mods/`. Thanks to StealWonders for the original port.

### Bug Fixes

- **Black terrain on Distant Horizons servers** — Terrain just past your view distance no longer turns black as you move. Areas already affected clear up when you next visit them.
- **Adding Voxy after map-only use** — Map-only terrain now downloads again for Voxy instead of leaving gaps. For older caches, run `/lss clearcache` once.

### Support

- **Platforms** — Fabric, Paper/Purpur, NeoForge and Sponge (experimental) for Minecraft 26.3.
