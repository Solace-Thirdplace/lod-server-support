### New Features

- **Nether maps in Xaero's World Map** — LOD terrain now fills Xaero's cave maps, so the Nether map shows distant terrain at whatever height you're at. Changing height refills the map from a local copy (about 65 MB at the default distance; `/lss clearcache` removes it).
- **Cave maps in the Overworld** — Terrain that arrives while you're under a roof fills the cave map you're viewing.
- **Keep LOD terrain off players' maps** — Servers can set `privacy.request_no_map_ingestion` (off by default) to ask clients not to add LOD terrain to Xaero's World Map. Clients older than 0.19.0 get no LODs while it's on and are told to update.

### Support

- **Platforms** — Fabric, Paper/Purpur and Sponge (experimental) for Minecraft 1.21.10.
- **Downgrading** — Server settings files created by 0.19.0 don't load on 0.18 or older.
