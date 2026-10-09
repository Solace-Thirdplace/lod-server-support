# DH client adapter: remediation of the 2026-10-09 review

Branch `feat/dh-codec-p1-mc1.21.1` (1.21.1 line). This records how each finding of
[the 2026-10-09 review](2026-10-09-dh-full-feature-claude-review.md) was handled and
the decisions taken with it. Commit `20ace8c5` is the reviewed baseline, committed
unchanged so the remediation diff is reviewable.

## Decisions (user, 2026-10-09)

The user accepted the review's decision verdicts and chose:

| Topic | Decision |
| --- | --- |
| Live updates (X-2) | **Keep, fix the fencing.** Timestamp fences are removed; ordering is arrival order on the one connection. |
| DTO source cache (X-5) | **Replaced by timestamp receipts** (`DhReceiptStore`), checked against Voxy by a presence probe on rejoin. |
| Packaging (X-6/X-7/X-8) | **Middle path.** DH channel registration gated on opt-in, the fringe pump has its own flag, Paper ships no DH/XZ code, the transfer mixin is deleted. No separate add-on mod. |
| Official protocol 15 (X-4) | **Cut.** No forced reconnect. An official 3.2.x server is detected and reported (`OLD_DH_PROTOCOL`, "ask the admin to update to DH 3.3.x"). Supported: official 16 and DH Support 15. This is a feature cut on a best-effort line, recorded here as its dated decision. |
| DH outreach | Handled by the user. |

Not done, deliberately:

- **X-11** (let a disabled or denied native offer permit DH fallback) reverses a
  recorded user decision; native still always wins. User's call.
- **Storage reserve.** `test-fixtures/dh-p4/bounded_storage.py` still uses the
  40 GiB reserve for `dh-p4-2g-v2`. It needs the user's ratification or a revert to 50 GiB.
- The X-16 process gates (outreach answer, S0 server-load ratio, movement-pause
  measurement per review §7) are still open.

## Commits

| Commit | Content |
| --- | --- |
| `8659889e` | DH settings reads go through `LSSClientConfig` accessors |
| `9e78fac9`, `08f1e0b2` | Sidecar `lss-client-experimental.yaml` (X-10); checkbox renamed "Allow DH Server Terrain" (X-9); tooltips resolved at display (D-25); brand-neutral `client.yaml` (D-26) |
| `577a74c8` | Paper strips `common/dh`, XZ and its licence (X-7); release-gate hardening (D-27) |
| `f6469c47` | Session rebuilt: receipts, batched handoff, scoped recovery, causal fencing |
| `812f7b00` | Opt-in channel and fringe gating, truthful exports, reset fixes |
| `e8e036a5` | Test suites rebuilt for the new design |

Fixture and documentation adaptation follow in later commits on the branch.

## Findings

| ID | Resolution |
| --- | --- |
| D-1 | Fixed by removing timestamp fences (`UpdateFence` deleted). A whole reply to a request sent after a push wins by arrival order; pushes cancel pending requests for their tile, and a per-tile push sequence rejects a reply issued before the latest push. |
| D-2 | Each assembly buffer carries the loss epoch it started under; a buffer from an older epoch is discarded and its tile revalidated. Drops while a recovery is queued bump the epoch too. |
| D-3 | Recovery is scoped to held tiles within `min(effectiveRange, updateDistance) + 8` chunks. Decode `RESOURCE_LIMIT` on a push defers that tile only. Resource pressure also discards incomplete buffers (`discardIncomplete`) so they cannot occupy every slot. |
| D-4 | `DhPosition.hashCode` mixes the packed key. `DhPositionHashTest` bounds collisions. |
| D-5 | No readback loop exists any more. The presence probe runs once per rejoin; if it is unusable, receipts are trusted and the status says `RECEIPTS_UNVERIFIED`. |
| D-6 | Obsolete: the conversion identity is gone. Receipts carry `CONVERSION_VERSION` only. |
| D-7 | Reset passes "DH selected and connected" as the DH context, discards receipts in both branches and resumes the session; DH-specific feedback. |
| D-8 | Obsolete with the gate/wait diagnostics (X-14). |
| D-9 | An out-of-interest push forgets the tile's receipt, so the next load re-requests it. |
| D-10, D-17–D-20 | Obsolete: the legacy trial, cooldown and transfer hook are deleted (X-4). |
| D-11 | Separate `VANILLA_LIMIT` block reason; the cap is now 65,536 regions (bitset), not 262,144 columns. |
| D-12 | `ZstdException` is classified MALFORMED. |
| D-13 | Tile-intrinsic impossibilities (blob over budget, spans over height or limit) are MALFORMED, with a cooldown strike, not a 2 s retry. Span limit raised to 2,200,000. |
| D-14 | Unmatched or expired responses discard their buffer. |
| D-15 | Obsolete with the DTO cache. |
| D-16 | Scans run on a fixed 500 ms cadence (50 ms after a request); movement no longer forces a scan. |
| D-21 | Withdrawal runs whenever preferences were announced on the connection. |
| D-22 | `SYNC_DISABLED_LOCALLY` when the user turned sync off. |
| D-23 | One chat notice per connection on a stop, naming the reason; expected states (no service, native preferred) are not "stopped". |
| D-24 | The DH-mode export reports the real native fields. |
| D-25, D-26, D-27 | See commits above. |
| D-28 | Fringe budget prose corrected to the code's values. |
| D-29 | Support side-face sky follows DH's floor rule; `CONVERSION_VERSION` 5 invalidates old receipts. |
| D-30 | `vanillaLoaded` returns early when not opted in, after a native offer, or in singleplayer. |
| D-31 | Dead code deleted (`DhAcquisition`, `DhTerrainCache`, trial/transfer, `unchanged()`, informational waits). `DhClientSession.status()` stays as the fixtures' text seam. `ClientSessionGate.nativeSource` is live (DH selection disables native reception) and stays. |
| X-1 | A tile takes two main-thread hops (prefilter, then batched ingest and accept) instead of one per column. |
| X-2 | See D-1. |
| X-3 | Scoped recovery (D-2/D-3); a malformed tile costs that tile a cooldown, not the session. Control decoding tolerates unknown messages and trailing bytes; terrain decoding stays strict. |
| X-4 | Official-15 support cut (decision above). Same-connection support switching for Support 15 stays. |
| X-5 | Receipts: tile → timestamp + conversion version, per server/world/dimension/seed/profile/shape key, atomic file, LRU of 512 buckets × 65,536 entries. Legacy DTO files are swept (own files only). |
| X-6 | Fabric registers the DH receiver per connection when opted in; NeoForge registers at startup when opted in, otherwise reports `RESTART_REQUIRED`. The fringe pump runs only during an active terrain session and with `lod.voxy_border_fill`. |
| X-7 | Paper excludes DH and XZ; `release_check` pins their absence. |
| X-8 | Middle path (decision above). |
| X-9 | Label "Allow DH Server Terrain"; honest tooltip; translations updated. |
| X-10 | Sidecar YAML. Downgraded builds ignore it and read the main file normally. |
| X-11 | Not changed (user decision). |
| X-12 | The load debounce lives in the connection state, per dimension. |
| X-13 | Resume counts as a load: revalidate when the server allows sync, otherwise clear receipts. |
| X-14 | Status is state + reason + activity + counters. Gate detail stays in the trace. |
| X-15 | Voxy ingest gate 1024 sections (above one tile); fringe gate 2048; vanilla cap per region (D-11). Request caps unchanged. |
| X-16 | Open; see "Not done". |

## Known limits after remediation

- I1 still applies: DH's approximation can overwrite better Voxy terrain outside
  the current connection's vanilla regions.
- Upstream request coalescing can hand a request an older in-flight read; the push
  sequence covers the push case, a later load check covers the rest.
- Edits missed while out of range appear at the next load check (debounced 20 min
  per dimension and connection), on reset, or on rejoin.
