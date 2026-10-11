# Status, local diagnostics and settings

`/lss status` opens the standalone client status screen. The screen shows effective reception and any saved/pending choice; **More** cycles text pages on small screens. VSS uses `/vss` for these local commands. `/lss diag`
retains the detailed troubleshooting counters and also describes OFF, dormant,
negotiation and missing-consumer states without creating a request manager.

Status collects on the client tick at most twice per second, including the detailed
CLI counters. `-Dlss.test.disableStatusCollection=true` disables collection for a separate
diagnostic A/B comparison; it is a test-only JVM switch, not a user setting.
The accepted V27 comparison uses a qualified corrected reference sharing the candidate’s correctness fixes; see [performance scope](performance.md). A disconnect or a
replacement world invalidates its cached data. Progress on the new screen counts
from the first status sample in the current world; existing detailed diagnostics
retain their established connection counters. Queue presence is an observation,
not proof of a bottleneck. Rate gating is an interval delta of the composed local
manual/adaptive gate, never attribution to the manual setting alone. Remote disk/generation causes remain unknown because
the protocol does not report them. Renderer stubs are unavailable capabilities.

`/lss diagnostics export` writes JSON and a text summary under `lss-diagnostics`
in the game directory (`vss-diagnostics` for VSS). The server operator equivalent is
`/lsslod diagnostics export` (VSS: `/vsslod diagnostics export`). Reports contain
typed counters, capability/state facts and cached versions of named loader/mod components. Addresses, names, UUIDs, world
identifiers, seeds, aliases, personal paths and raw exception messages are not
accepted by the exporter. The command displays the local output path; the report
does not contain that path. Nothing uploads automatically. The exporter retains
at most ten reports and admits one active plus one queued export.

Client JSON reports use schema version 4 (map ingestion fields: `xaeroAvailability`, `journeyMapAvailability`, `journeyMapPending`). Their `settings` section separates the
last successfully read or saved values (`saved`), the values accepted at startup or
reload (`configured`), and current effective values (`effective`). It includes
pending reload/reconnect/adoption paths, published/adopted revisions, and a small scalar allowlist: reception, distance,
manual column rate, map ingestion and sharing. Unsaved menu edits are excluded.
The saved observation does not watch the file: an external edit becomes known at
an explicit read, reload or menu open. Sensitive collection values stay private.

The server command acknowledges the queued target path after admission. Check the
server log for completion or a sanitized write failure; the queued message does
not mean the files have been written. Client completion feedback is shown only
while its originating session remains current.

Settings activate at startup, through Sodium Apply on the client, or through
`/lss reload` on the client and `/lsslod reload` on the server. VSS uses `/vss reload` and `/vsslod reload`.
The old `set`, preset preview/apply/undo and reception-toggle paths are removed.

Sodium edits a draft. Apply validates and atomically saves YAML, then automatically
runs the same reload used by `/lss reload`. Hot settings activate after reload
completes; session settings such as map ingestion wait for reconnect. Direct YAML
edits still need `/lss reload`. Sodium uses its normal layout, without an LSS banner
or status button. The standalone status screen shows accepted and effective values.
A failed save retains the draft without activation; a successful Retry or Rebase
also requests reload. If the file changed elsewhere, reload/rebase or discard the draft
explicitly before retrying; unrelated file edits must not be overwritten.

For an already generated world, set `generation.enabled: false`, then reload the
server. This prevents new generation admissions while admitted Minecraft work
drains. It does not prove that terrain exists. To reproduce the historical 32/4/1
operating point, edit `lod.distance.default_chunks`, the applicable dimension/world
overrides, and `generation.concurrency.global`/`per_player`, then reload. Those
measurements establish that operating point only; they are not a comparison with
fresh defaults or a recommendation for every server.

Reload first validates the complete candidate. Syntax errors, unknown keys or an
unsupported `config_version` leave the active snapshot unchanged. Valid reloads
report applied paths/counts, normalization, inactive platform settings and pending
restart/reconnect paths. Comments and hand-authored values remain on disk. A
subsystem adoption timeout is reported as pending rather than as successful rollback.

Client cache identity, protocol compatibility and map ingestion wait for a new
physical connection. A dimension switch or server session-config refresh does not
activate them. Server reader pools, store enabling, wire compression, compatibility,
masking and Paper event selection wait for a restart. See the
[settings reference](../reference/settings.md) for every field's boundary.

For setup and diagnosis, see [installation](installation.md), [troubleshooting](troubleshooting.md) and [performance](performance.md). Maintainers use the [live status/settings checklist](status-settings-live-acceptance.md) for exact-artifact acceptance.
