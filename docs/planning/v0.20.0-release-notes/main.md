### New Features

- **Draw far players farther** — The new "Extend Far Player Draw Range" option on the Far Players page (off by default) draws far players past the game's normal draw distance when you use an LOD mod such as Voxy. Fog can still hide very distant players; turn off your LOD mod's fog if they fade out.
- **Map permission** — With `privacy.request_no_map_ingestion` on, players with the `lss.map_ingestion` permission can still add LOD terrain to their maps (needs a permissions plugin such as LuckPerms).

### Bug Fixes

- **Paper version check** — The Paper plugin no longer starts on a Minecraft version it wasn't built for, which could fill the LOD store with empty terrain. Paper LOD stores rebuild once after updating; if distant terrain went missing after a Paper update, players who joined during that time should run `/lss clearcache` once while connected.

### Support

- **Platforms** — Fabric, Paper/Purpur/Folia (experimental), NeoForge and Sponge (experimental) for Minecraft 26.3.
