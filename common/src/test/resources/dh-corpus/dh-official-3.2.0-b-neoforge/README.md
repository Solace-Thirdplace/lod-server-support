# Official DH 3.2.0-b protocol-15 ceiling corpus

Verbatim format-2/Zstd terrain and its owned response from the bounded private
NeoForge test. The original production decoder rejected it. Provenance is in
`capture.json`; no private world key or generic control frame is copied here.

Inspection of the exact producer builder and raw captured spans establishes the
one-voxel ceiling-air convention: all 4100 outside spans (including four repeated
corners) end at relative Y=385 in a height-384 world and are air. The normalization
clips only that single outside air voxel. Solid out-of-bounds spans, larger
overshoots, wholly outside spans and overlaps must still fail. This corpus is
a wire/height regression, not an independent visual or durable-storage oracle.
