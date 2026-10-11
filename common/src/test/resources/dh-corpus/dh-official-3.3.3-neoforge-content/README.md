# Official DH custom-height/removal corpus

Eight frames from a passed private NeoForge 1.21.1 run: full initial sync,
owned unchanged conditional reply, and owned changed conditional removal.
Requests are sanitized reconstructions; terrain and response bytes are verbatim.
See capture.json for exact producer/fixture/candidate hashes and seed/edit commands.

The independent expectations are the seeded lime/cyan planes, their air/purple
replacement, gold sentinels, and the flat bedrock/stone layers. The standalone
replay checks seven heights in all 4096 columns for both changed tiles: 57,344
block assertions, two increasing receipts, one unchanged reply, and zero owned
bytes afterward. normalized.properties records regression hashes from LSS;
those hashes are not an independent oracle.

No private level key or generic control frame is part of this corpus.
