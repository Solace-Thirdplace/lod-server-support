# DH sync on load and live updates

Date: 2026-10-06. User-requested local MC1.21.1 development on Fabric and NeoForge.
The periodic refresh timer is removed in favor of the official DH behaviors.
Outreach, release and the strict P2/P4 commit/authority gates remain outstanding.

## Behavior

The Distant Horizons Sodium page contains three checkboxes: Use DH, Sync on load
and Live updates. Sync/live default on and apply immediately
through the existing save/reload transaction. Changes renegotiate the session;
server permissions and distances remain authoritative. Use DH is permission for
fallback on the next physical connection; native LSS wins. Protocol selection is
automatic and has no menu control. Retired `lod.dh.refresh_seconds` YAML has no runtime effect;
its value is no longer in the active settings inventory.

Sync on load checks retained source timestamps before restoration. Unchanged
terrain preserves existing Voxy contents, including prior vanilla refinement;
changed or missing source uses ordinary acquisition. Newly entering the LOD
interest region can check an accepted tile again after the official client's
20-minute load debounce. Elapsed time while stationary never causes polling.

Live updates accept negotiated message-9 partial DTOs and their message-10
fragments without needing an outstanding request. The active logical world,
profile, position, border and subscribed distance are checked. Fully described
Minecraft columns enter the existing Voxy sink; unobserved columns are preserved.
An accepted existing tile remains held after a complete edit rather than being
needlessly downloaded again. Incomplete/cold source uses ordinary acquisition.
Partial data invalidates its old whole-source cache entry and never masquerades
as a complete persistent snapshot. On the next connection those affected source
tiles may need a full download; unchanged validated cached tiles still preserve
existing Voxy terrain. A push does not replay every cached tile into Voxy.

Older in-flight replies are retired. A bounded per-column update history prevents
an older/equal database snapshot from replacing newly pushed columns or being
cached as their complete source. Unknown update timestamps remain protected until
ordering can be established. History leases are released on retirement/reset.
A busy Voxy sink or timed-out client handoff now invalidates the affected tile
and schedules acquisition without stopping DH. Raw-fragment pressure purges the
current source bucket and receipts because a dropped fragment has no trusted tile
identity; it reacquires terrain when server permissions permit. Queue, fragment,
decoder and memory limits remain in force. Diagnostics report
requested/available settings and received/enqueued updates, not acknowledged
completion or persistence. The existing asynchronous Voxy commit limitation remains.

DH Support exposes only services it advertises; enabling a checkbox cannot add
an upstream service. Protocol 15/16 frame parsing and the loader transports are
shared; the current real server target remains official DH 3.3.3.

## Validation

Focused tests cover existing reconnect cache preservation, hot negotiation,
load-only synchronization, partial pushes without outstanding requests, untouched
columns, wrong-world/local-off rejection, older replies, unknown timestamps,
source retention and full release of update ownership. They use frozen real
producer DTOs through the production worker/converter/ingest bridge. The normal versioned build passes 3,305 unit tests (four skipped), 78 Fabric
and eight NeoForge server tests. All six LSS/VSS packaging checks, 126 release
self-tests and nine SQLite helper tests pass. The observer follow-up compiles the
new live-update scenario against the actual loader ABI and passes Fabric T1.
Current receipts are in
the outer checkout's `build/dh-neoforge/2026-10-06/live-updates/`.
Private client verification was initially pending physical-host disk admission. The first
admitted observer run was stopped to fix missing command routing for the new
scenario. The combined observer then failed before feature checks because it read
`cache` instead of the actual `terrainCache` field. Both rig issues are corrected
and the final observer compiles; neither attempt is a completed feature/UI pass.

After the [vanilla-border fix](2026-10-06-vanilla-lod-border-gap.md), the final
`0.16.0-dh-boundary.dev20261006` candidate passes 3,316 unit tests (four skips),
the same 86 server tests and packaging gates. Its packaged private NeoForge
combined case passes **unchanged target reuse with zero payloads/conversions**,
push-only edits and air removal with sync off and **zero new terrain requests**,
30 seconds of local update-off preservation, and hot re-enabling on the same
connection/session. The historical observer reports **1,200 derived comparisons** (`samples × 12`),
not an instrumented read count. The follow-up observer counts actual comparison
operations, including repeated settling reads. Actual
Sodium controls pass at Auto scale 3, including the separate DH page, shared Apply,
reception dependency and diagnostics export.

Current proof is in the outer checkout's
`build/dh-neoforge/2026-10-06/vanilla-boundary/updates-qualification-acknowledged/`,
bound to the final artifact SHA below. Supplemental observer failures are retained:
a transient title-screen wait, an aborted cold-export premise, and a cancelled
settings Apply before disconnect. The observer now primes known terrain after DH
starts, awaits the negotiated off state before disconnect, and checks a complete
source/destination baseline before unchanged rejoin. These were test-only changes;
the production jar was unchanged during qualification. The earlier live-updates
jar is not retroactively qualified by this result. No general Voxy completion,
independent biome/light coverage or Windows GPU performance claim is made.

## Playable setup

The tested packaged candidate is installed in Windows Prism's
**LSS dev — 1.21.1 NeoForge - Copy For Far Testing**, SHA256
`1dec0001b839aa241cb1a8ac0428a8f3937c15b9b01b4910a3265ebb721f1cb1`.
Preferences and other mods are unchanged; the previous jar has a dated backup.
The candidate settings codec reads the old client YAML without editing it; the
retired timer is absent from effective settings and both new defaults are true.

The stock official-DH NeoForge server resumes **dh-neoforge-playable** at
**[::1]:25585**, retaining OP and generation32/SURFACE_THEN_CHUNKS. The only
server configuration change enables real-time updates; synchronization was
already on. Minecraft protocol767, Windows IPv6 TCP and fresh save/list commands
pass. Private supervisor/server/client/display owners are stopped. The
[manual guide](2026-10-05-dh-manual-test-guide.md) now tests pushed edits with
sync off instead of periodic refresh. Release readiness remains false.

Disk housekeeping retained verified backups of unreferenced official assets and
two obsolete NeoForge client-cache jars unused by installed profiles. No personal
worlds, profiles, preferences, other mods or historical test evidence was deleted.
Reclamation receipts and compressed backups are retained in the evidence directory.

Follow-up source admission waits for accepted delivery and matching actual Voxy
readback. Queued/unobserved data is not published as an unchanged source snapshot.
Cache identity and entries include conversion semantics version 2; older source
buckets are not reused for conditional validation. This causes fresh source
acquisition once per affected area, without deleting Voxy storage. General Voxy
completion/persistence and late-write ordering remain the separate I1 gate.
Current build/setup results supersede this historical checkpoint in the
[follow-up disposition](2026-10-06-dh-follow-up-review-disposition.md).
