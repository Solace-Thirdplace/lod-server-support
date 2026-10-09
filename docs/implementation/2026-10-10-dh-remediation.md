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
| D-1 | Fixed by removing timestamp fences (`UpdateFence` deleted). Ordering is arrival order: every identified push (applied or deferred) cancels pending requests for its tile, and a whole reply is written only while its request is still pending. (The per-tile push-sequence belt of the first round was redundant and is deleted.) |
| D-2 | Each assembly buffer carries the loss epoch it started under; a buffer spanning a loss is discarded, never decoded with a gap. |
| D-3 | A lost push is reacquired by tile: a discarded or orphaned buffer's first fragment names its tile (`DhAssembly.drainLostClaims`), and a push that fails to decode defers the tile its header names. Only a push whose first fragment was lost falls back to revalidating held tiles within `min(effectiveRange, updateDistance) + 8` chunks (cancelling requests in flight there). Slot or memory pressure discards incomplete buffers (`discardIncomplete`) and never triggers window recovery. |
| D-4 | `DhPosition.hashCode` mixes the packed key. `DhPositionHashTest` bounds collisions. |
| D-5 | No readback loop exists any more. The presence probe runs once per rejoin; if it is unusable, receipts are trusted and the status says `RECEIPTS_UNVERIFIED`. |
| D-6 | Obsolete: the conversion identity is gone. Receipts carry `CONVERSION_VERSION` only. |
| D-7 | Reset treats the connection as a DH terrain session when DH is selected, the server advertises the DH channel and the source has not stopped; it then discards receipts and resumes the session, with DH-specific feedback. Otherwise the confirm prompt stays (a vanilla or DH 3.2.x server cannot refill), and a confirmed wipe still resumes the session. |
| D-8 | Obsolete with the gate/wait diagnostics (X-14). |
| D-9 | An out-of-interest push forgets the tile's receipt, so the next load re-requests it. |
| D-10, D-17–D-20 | Obsolete: the legacy trial, cooldown and transfer hook are deleted (X-4). |
| D-11 | Separate `VANILLA_LIMIT` block reason; the cap is now 65,536 regions (bitset), not 262,144 columns. |
| D-12 | `ZstdException` is classified MALFORMED. |
| D-13 | Tile-intrinsic impossibilities (blob over budget, spans over height or limit, mapping count or bytes, decoded size) are MALFORMED: a reply takes a cooldown strike, a push defers its tile. Only the decompressor dictionary budget stays RESOURCE_LIMIT. Span limit raised to 2,200,000. |
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
| D-31 | Dead code deleted (`DhAcquisition`, `DhTerrainCache`, trial/transfer, `legacyEvidence`, `VoxyTerrainProbe.isCurrent`, `unchanged()`, informational waits). `DhClientSession.status()` stays as the fixtures' text seam. `ClientSessionGate.nativeSource` is still a constant-`true` parameter in production (left; harmless). |
| X-1 | A tile takes two main-thread hops (prefilter, then batched ingest and accept) instead of one per column. |
| X-2 | See D-1. |
| X-3 | Scoped recovery (D-2/D-3); a malformed tile costs that tile a cooldown, not the session. Control decoding tolerates unknown messages and trailing bytes; terrain decoding stays strict. |
| X-4 | Official-15 support cut (decision above). Same-connection support switching for Support 15 stays. |
| X-5 | Receipts: tile → timestamp + conversion version, per server/world/dimension/seed/profile/shape key, atomic file, LRU of 64 buckets × 65,536 entries. Kept and restored only where sync is available (otherwise they could never learn of offline edits). On rejoin a probe checks all four Voxy sections of each tile (`acquireIfExists` where Voxy has it); transient probe failures retry, repeated ones drop the receipts. Legacy DTO files are swept (own files only). |
| X-6 | Fabric registers the DH receiver per connection when opted in, and treats the channel as usable only where that receiver exists; NeoForge registers at startup when opted in, otherwise reports `RESTART_REQUIRED`. The fringe pump runs only during a native session or a negotiated DH session (config received), and with `lod.voxy_border_fill` (default on, which keeps the pre-existing native behaviour; it is not a new native change). |
| X-7 | Paper excludes DH and XZ; `release_check` pins their absence. |
| X-8 | Middle path (decision above). |
| X-9 | Label "Allow DH Server Terrain"; honest tooltip; translations updated. |
| X-10 | Sidecar YAML. Downgraded builds ignore it and read the main file normally; startup moves development-build keys out of the main file once. The sidecar can never disable the main settings: unknown, retired or invalid content is dropped with a warning, and a failed main publish rolls the sidecar back. |
| X-11 | Not changed (user decision). |
| X-12 | The load debounce lives in the connection state, per dimension. |
| X-13 | Resume counts as a load: revalidate when the server allows sync; without sync no receipt is restored at all (see X-5). |
| X-14 | Status is state + reason + activity + counters. Gate detail stays in the trace. |
| X-15 | Voxy ingest gate 1024 sections (above one tile); fringe gate 2048; vanilla cap per region (D-11). Request caps unchanged. |
| X-16 | Open; see "Not done". |

## Known limits after remediation

- I1 still applies: DH's approximation can overwrite better Voxy terrain outside
  the current connection's vanilla regions.
- Upstream request coalescing can hand a request an older in-flight read; the push
  sequence covers the push case, a later load check covers the rest.
- An edit missed while out of range or under load appears when that tile re-enters
  view after the 20-minute load interval (per dimension and connection), on reset,
  or on rejoin. A tile that stays in view is not rechecked.
- Without sync (DH Support, or sync off), every connection downloads the whole range.
- `/lss diag` shows "last reply age -1 ms" before the first reply (cosmetic).

## Verification (2026-10-09, commit `236bbba4`)

Run from an isolated clone outside the parent repository (the source-path contract
tests walk up five directories and see the parent repo's trees from a nested clone),
under the harness lock, with the manual DH server stopped through its console and
resumed afterwards:

- `:common:test` 1046/0, `:fabric:test` 1799/0 (4 skipped), `:paper:test` 536/0
- Fabric server gametests 78/78; NeoForge gametest smoke 8/8
- `:neoforge:build`, `:paper:shadowJar`, `vssJars`; `release_check.py` OK
  (LSS + VSS × Fabric/Paper/NeoForge); `--selftest` 162; `check_artifacts.py`;
  catalog validate + render check; `tools/tests` 30/30

Not run: any live rig. The adapted dh-live (`rejoin`, `updates`, `service`) and
dh-p4 (`benchmark`, `configuration`) cases compile but have not been executed
against the remediated client.

## Review panel round (2026-10-10)

A 4-Opus + 2-Fable panel reviewed `fa17926b`. All verified findings were folded on
`fix/dh-review-panel` (rows above are updated to the folded state). Highlights:

- A push deferred under pressure could let an in-flight pre-push reply re-hold its
  tile (O1, F1): replies are now written only while their request is pending
  (mutation-tested).
- Without sync, restored receipts froze terrain forever, and a reception resume or
  a dimension round trip mishandled the persisted file (O2, F1, F2): receipts are
  sync-only and survive round trips.
- An unknown key in the experimental file disabled all LSS client settings (O3).
- The adapted live-rig mixins could not load (receive's new loss-epoch argument) and
  the converter hooks targeted an unused overload (O4); both fixed and compiled.
- Unconfirmed `/lss reset` wiped Voxy on servers that cannot refill (O4, F2).
- Push-loss recovery was effectively whole-disc; it is now per tile (F1, F2).
- Long flights ignored persisted receipts after policy eviction; the first portal
  return revalidated everything; the requested update distance ignored the LOD
  distance (F2).

Not changed: the session-wide stop after two unanswered sync requests (upstream leaks
a rate-limit permit per silent failure; kept, F1-7), the cosmetic `-1 ms`, and the
`nativeSource` constant parameter.

## Verification after the panel round (commit `602ee1a5`)

Same isolated-clone procedure: `:common:test` 1047/0, `:fabric:test` 1810/0 (4 skipped),
`:paper:test` 536/0, Fabric gametests 78/78, NeoForge gametest smoke 8/8;
`release_check.py` OK (LSS + VSS × three loaders), `--selftest` 162, `check_artifacts.py`,
catalog checks, `tools/tests` 30/30, dh-p4 tests 14/14. The adapted live-rig fixtures
compile against the merged sources; no live rig was run.
