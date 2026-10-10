# Official 3.3.3 ocean lighting fixture

`database-tile.dto` contains the exact eight compressed blobs and metadata from
the official DH 3.3.3 MC1.21.1 server's detail-0 FullData row (4,-3), placed in the
protocol-16 full-data DTO envelope. This is an upstream database reconstruction,
not a captured network response. `provenance.json` binds its bytes and producer.
The world shape is vanilla Overworld: min Y -64, height 384, with skylight.

The actual producer's `LodDataBuilder` samples each run's lighting at the block
above its top and merges water without splitting at light changes. A water run
therefore contains its incoming surface skylight, while the floor run records
the neighboring bottom water's skylight. All 178031 checked water/floor pairs
across 54 server tiles agree with one skylight level lost per water voxel.

The converter regression checks Voxy's neighboring water voxel against the
independently supplied floor-run light, including deep zero-sky floors. It does
not alter the protocol decoder or normalized wire values. Source inspection,
exact producer bytecode and manual diagnostic counters remain local under
`build/dh-neoforge/2026-10-05/ocean-lighting/` in the outer checkout.
