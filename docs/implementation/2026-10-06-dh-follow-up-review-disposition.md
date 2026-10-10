# DH follow-up review disposition

Date: 2026-10-06. Addresses the independent
[follow-up review](2026-10-06-dh-follow-up-claude-review.md) in the local
MC1.21.1 Fabric/NeoForge development clone. No release, merge or outreach.
Evidence is retained in the outer checkout's
`build/dh-neoforge/2026-10-06/follow-up-fixes/`.

| Finding | Verified correction | Regression / qualification |
| --- | --- | --- |
| D-1 | Both transports check the remote DH channel; initial discovery, trial deadline and retry execution recheck it. An absent channel never schedules reconnect. | Missing/withdrawn channel unit controls; packaged Fabric no-service cell. |
| D-2 | Cache opening protects current interest and in-flight validation, not every historical file. Movement updates protection; unvisited history remains evictable. Diagnostics count declined admissions. | Real 8192-file flight/home quota test: home joins 3+ retain at least 120/121. |
| D-3 | Documentation now distinguishes registered source handlers from stock runtime reachability. Both stock versions' logging failures are recorded. Official rejection tests are labelled defensive self-encoder controls. | Retained stock 3.3.3 mismatch log and both jars' bytecode; real official-15 quiet recovery. |
| D-4 | Quiet discovery requires fifteen seconds and three idle ticks, no captured/queued/decoding work and no recent arrival. An answered-but-unconfigured peer is not labelled a missing channel. | Captured-config/hitch unit; packaged real official config delayed 6.5 seconds. |
| D-5 | Confirmed/discovered family is retained per physical Minecraft Connection. Config acceptance and same-connection replacement prevent mid-play discovery reconnects. | Null-world tick plus same-connection reconfiguration unit; no second retry after confirmation. |
| D-6 | Changed source is invalidated before handoff and admitted only after accepted delivery and matching actual Voxy readback. Rejected, timed-out or still queued data cannot publish a newer unchanged source snapshot. | Captured DTO with rejected changed column and reconnect; accepted-but-uncommitted queue with reconnect; real unchanged reuse. |
| D-7 | Storage layout and conversion semantics are versioned separately; conversion version 2 participates in the entry and identity. Old source cannot authorize conditional reuse. Voxy storage is retained and fresh source is acquired. | Obsolete conversion header rejection and full budget release. |
| D-8 | Push handoff timeouts invalidate the affected tile, retain its ordering fence and schedule acquisition instead of stopping DH. | Five-second blocked-client push test; only the bounded 512-byte fence remains after terrain release. |
| D-9 | Push delivery checks Voxy/worker backlog. Known-tile drops remove source/receipt, cancel competing requests and retain the required timestamp. Raw fragment pressure keeps control headroom and triggers current-bucket/receipt recovery. | Backlog257 push with competing full request; 40-frame burst retains 24/drops 16 and resumes with reusable assembly and released budget. |
| D-10 | Border retries accept chunk-load events and retain the sweep in absolute world coordinates. A token budget allows 128 submissions/s and at most 8/tick, with 64 actual chunk lookups and 256 geometric candidates/tick. Accepted chunk identities do not repeat. | Continuously exposed moving-border bound at RD12/RD32 sprint and RD12 30-block/s flight; packaged moving native-priority cell. |
| D-11 | Supervisor accepts an explicit bounded termination grace. The owned manual server selects 90 seconds; the default for other commands remains unchanged. | Owned worker handles SIGTERM, saves after 3.4 seconds and exits before its 5-second grace. Invalid NaN never launches a child. |
| D-12 | Transient border exceptions defer; only ABI linkage errors latch. Native-offer retirement is asynchronous. Slow retry joins keep their consumed allowance. Native suppression status includes disabled/incompatible offers. Obsolete selector strings are removed, all locales carry the overwrite caveat, and docs reflect automatic selection. | Status/null-world/slow-retry tests; lazy legacy enum resolution test; locale parity and packaged UI; corrected evidence labels below. |

DH Support 0.14 switches codec on the existing Minecraft connection after its
uniquely decoded rejection. Inspection of the producer confirms that mismatch
closes no Support player state. This removes the reviewed Paper/Velocity retry
throttle hazard for Support. Fresh user joins still validate protocol16 first;
address hints alone do not select a version15 terrain decoder. An official quiet
legacy trial remains limited to initial play and one connection retry.

Additional straightforward guards reject pushes older than an accepted whole-tile
stamp and prevent cancelled requests from being transmitted later. Legacy Sodium
resolves its optional CyclingControl constructor only when an enum actually needs
it. A foreign unsafe cache bucket is skipped during indexing rather than disabling
all otherwise safe server buckets; current-owned cache paths still fail closed.

Source admission uses actual **current readback**, not a Voxy completion or
persistence acknowledgement. A later queued write or a crash losing destination
persistence can still cross that observation. General I1 ordering/persistence,
partial-light acceptance, complete biome/light/resource matrices and official
client load comparison remain open P2/P4 gates. Source-less/partial/pushed/no-cache
terrain can still replace previous unloaded vanilla refinement. The translated
UI caveat is qualified accordingly. With sync off, cached offline edits are never
checked merely because time passes.

The border pump also operates on current loaded vanilla fringe in single-player
and on servers without LSS; it does not request or generate chunks. Its bounded
work is intentional. The lazy legacy-page regression is not a live Sodium 0.6
three-tab qualification. Quiet advertised-but-disabled official networking can
still consume the one bounded trial; there is no passive stock version discovery.

Historical evidence corrections: the previous Fabric 1755 passes include four
fixture observer tests. The old "1200 independent comparisons" label was the
`samples × 12` formula. The current observer increments a real comparison counter,
including settling retries. Native-priority zero DH counts cover **terrain Request**
frames, not every control packet. Readiness receipts describe their timestamps;
current identity/TCP status and fresh save/list acknowledgements are checked at
handoff. Earlier logs, artefacts and review reports remain unchanged.

## Validation and deployment

The final normal build passes 3,340 unit/observer tests: common 1,014, Fabric 1,767,
Paper 536 and NeoForge 23, with four unrelated Fabric skips. Four Fabric passes are
fixture observer tests; 3,336 passes belong to the production suites. Fabric 78 and
NeoForge 8 server tests pass. All six LSS/VSS packaging checks, 126 release self-tests,
nine SQLite helper tests, two supervisor-grace tests and the generated settings
reference check pass. Common is legitimately reused from the preceding full
attempt because no common source changed; the platform builds and server suites
execute for the final candidate.

The first compile failure (a nonexistent assembly collection), moving-border
failures (relative scan coordinates), asynchronous lease-close assertion and the
backlog test handle-signature error are retained. The initial settings-reference
invocation used the wrong classpath path; the harness refused its attempted nested
build. The final check uses the exported matching classpath and passes.

NeoForge version `0.16.0-dh-reviewfix.dev20261006` has SHA256
`e3600f00489e7d1d237a8c9c514115cd317219d8733e87fd49868aa48b1445c4`.
The matching Fabric jar has SHA256
`7ab633bcccf1f96eb0b6d9cedb5017af95e38786d95e5bb89a03064594bb68e1`.
Source/test manifests bind 434 and 557 files respectively to the build copy and the
checkout. Six packaged private cells pass against the exact candidate:

| Cell | Observed result |
| --- | --- |
| Fabric / no DH or LSS server | Ordinary Minecraft remains connected; zero reconnect asserted; no further terrain requests for30 seconds after adapter stop. |
| NeoForge / official16 config delayed 6.5 seconds | Both server delay markers present, zero reconnect, 12 accepted tiles and matching Voxy storage. |
| NeoForge / official15 stock 3.2.0-b | Exactly one reconnect, correct detected family, matching Voxy storage. |
| NeoForge / DH Support 0.14 | Zero reconnect, correct family, 13 accepted tiles and 32 sampled Voxy columns. |
| NeoForge / native LSS + DH, moving | Native 20 throughout, zero DH terrain Request frames, 172.2 blocks over 30.75 seconds of motion, 73 sampled border columns present and 657 final stone comparisons. Two-second sampling checks observed missing-column persistence; this is not a frame-by-frame visual guarantee. |
| NeoForge / official16 unchanged rejoin, edits and menu | Zero target payloads/conversions on unchanged rejoin; one edit and one air-removal push, zero new terrain requests during pushes; 30 seconds of local update-off preservation;11332 actual independent block comparisons including settling reads; actual Sodium 0.8.12 Auto scale 3 controls pass. |

The moving-border and longest-menu screenshots were inspected. All 24 recorded
private supervisor/server/client/display identities are retired, with no process
left in their run directories. The tested NeoForge jar is installed in Windows
**LSS dev — 1.21.1 NeoForge - Copy For Far Testing**. Its previous jar is backed up;
client preferences and all other mods are byte-for-byte unchanged. No foreground
client was launched. The unchanged **dh-neoforge-playable** world resumes at
**[::1]:25585**, with OP, stock DH 3.3.3, generation 32/SURFACE_THEN_CHUNKS,
sync and live updates retained. The server still has no native LSS mod.

Fresh current-owner list/save acknowledgements, Minecraft protocol 767, Windows
IPv6 TCP, original world/OP, storage admission and the installed artifact hash
pass. Read-time server liveness is authoritative; this dated receipt is not a
promise of future uptime. The new source/conversion cache identity requires fresh
DH source acquisition for previously cached areas; no Voxy reset is performed.
Release readiness and the remaining plan gates stay open.

The incremental changes are available as a [scoped diff](evidence/2026-10-06-dh-follow-up-fixes.diff)
and [hash manifest](evidence/2026-10-06-dh-follow-up-fixes-manifest.json). The exact
reviewed baseline hashes are verified; five noncompiled documentation snapshot
files were reconstructed from the retained review diff/receipts without changing
the baseline's production sources or compiled artifacts.
