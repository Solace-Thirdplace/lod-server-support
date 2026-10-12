### New Features

- **JourneyMap support** — Downloaded LOD terrain now fills JourneyMap's map as well as Xaero's World Map, with or without Voxy. Map filling is on by default for new installs.
- **Turn LODs off per world** — Server admins can set a world's LOD distance to 0 to switch LOD service off there; players are told why.
- **Sponge server support (experimental)** — LSS now runs on SpongeVanilla servers; drop the jar into `mods/`. Thanks to StealWonders for the original port.

### Bug Fixes

- **Adding Voxy after map-only use** — Terrain downloaded only for a map is now downloaded again when you add Voxy, instead of leaving gaps. For a map-only cache from 0.17 or older, run `/lss clearcache` once.

### Support

- **Platforms** — Fabric, Paper/Purpur, NeoForge and Sponge (experimental) for Minecraft 1.21.1.
