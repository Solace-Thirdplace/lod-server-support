### New Features

- **Minecraft 26.3** — LSS now supports Minecraft 26.3; 26.2 continues on its own release line. Voxy has no 26.3 release yet, so until it does 26.3 clients get map filling and far players but no LOD terrain.
- **JourneyMap support** — Downloaded LOD terrain now fills JourneyMap's map as well as Xaero's World Map, with or without Voxy. Map filling is on by default for new installs.
- **Turn LODs off per world** — Server admins can set a world's LOD distance to 0 to switch LOD service off there; players are told why.

### Bug Fixes

- **Adding Voxy after map-only use** — Terrain downloaded only for a map is now downloaded again when you add Voxy, instead of leaving gaps. For a map-only cache from 0.17 or older, run `/lss clearcache` once.

### Support

- **Platforms** — Fabric, Paper/Purpur and NeoForge for Minecraft 26.3. Folia has no 26.3 build yet.
