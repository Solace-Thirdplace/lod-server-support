# DH menu simplification

Date: 2026-10-09. The user's menu decision supersedes the earlier separate DH
page and synchronization checkbox layout.

General now includes **Use Distant Horizons**, a checkbox retaining
`lss:lod_source` / `lod.source`. It permits fallback when native LSS is absent;
automatic source/protocol selection and reconnect timing are unchanged. The
shared catalog has General and Far Players, with 11 controls total. General has
six controls in four groups; its last group contains Xaero and DH fallback.
Modern/legacy Sodium and both loader twins use that same catalog. The retired
DH page/tab and sync/live labels are removed from all three locale resources.

`lod.dh.sync_on_load` and `lod.dh.real_time_updates` remain schema/config keys,
both default true, with explicit client reload applying changes immediately.
They are advanced/file-only metadata rather than UI controls. Existing false
choices survive other menu edits. Default omission during YAML saves retains
native-only v0.16.0 downgrade compatibility. No synchronization, live-push,
protocol, codec, cache or native wire behavior changes.

The existing catalog, inventory and both renderer tests now check the two-page
layout, new control count, defaults, staged reception dependency and preservation
of file-only choices. Functional observers change hidden preferences through the
production store and validated reload, observing adoption before a second change.
The manual guide and generated settings reference describe the current controls.

## Validation and setup

Candidate: `0.16.0-dh-menu.dev20261009.1`.
Evidence root:
`/home/vox/projects/voxel-server-support/build/dh-neoforge/2026-10-09/menu-simplification`.
Build copy: `/tmp/lss-dh-menu-20261009-r1`.

The normal Java 21 build passed 3,421 production unit tests (four optional Fabric
skips), 78 Fabric and eight NeoForge server gametests, and all six LSS/VSS artifact
and release gates. Catalog validate/render and settings-reference checks pass.
Production normal-build XML is retained separately in `unit-xml/`.

Four fresh packaged checks pass: actual modern Sodium on NeoForge and Fabric at
1280×720 / Auto scale 3; NeoForge live/load/rejoin/reception using config reloads;
and actual legacy Sodium 0.6.13 at the same Auto scale. Both generations have only
two LSS pages, the fallback checkbox in General, no sync/live controls and unchanged
enabled preferences after Apply. Modern and legacy screenshots were visually
inspected; the full option name and checkbox fit. Legacy audits all six pages’
tabs, pagination and donation-control intersections. These are private Linux
software-rendered functional checks, not fresh Windows GPU performance measurements.

The live case checks 10,411 actual block comparisons including settling retries,
zero target payloads/conversions on unchanged rejoin, push-only edits/removal,
retained terrain while live updates are disabled, and zero requests/pushes while
reception is applied OFF. One request while OFF was still pending is recorded
separately. All private owners retired. Exact source/fixture/jar bindings and
per-case receipts are preserved in the evidence root.

Only three class files differ per loader from the prior October 8 r2 jars:
ClientOptionCatalog, LegacySodiumPage and its Bounds class (including shifted debug
line metadata). Runtime DH, common codec and native class bytes are unchanged;
`class-comparison.json` records the comparison. Prior specialized functional and
performance evidence retains its original binary/time labels.

The selected Windows **LSS dev — 1.21.1 NeoForge - Copy For Far Testing** instance
now contains the tested NeoForge jar. Minecraft was closed; the previous jar is
backed up, with preferences and other mods hash-preserved. SHA-256:
`77923e2f0f944aebf3d71a2079c72d0c13ae3388cd940c84313d6ccd6e22abd8`.

The same `dh-neoforge-playable` world is restored at **[::1]:25585**, with OP,
DH configuration and semantic server properties preserved. Fresh console list/save
and a real Minecraft protocol-767 status reply passed at
`2026-10-09T16:12:36.143841+00:00`. Check current liveness before joining; readiness
receipts describe their capture time. See the [completion record](evidence/2026-10-09-dh-menu-simplification.json).

The known [Voxy ordering/completion boundary](2026-10-07-dh-accepted-limitations.md)
remains accepted for now. The movement-pause cause and remaining original plan
qualification/outreach are not declared complete. No new GPU/server benchmark,
foreground client launch, merge or publication occurred.

[Full-feature Claude briefing](2026-10-09-dh-full-feature-claude-review-briefing.md).
