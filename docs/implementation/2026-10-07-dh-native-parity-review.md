# Native LSS and DH client parity review

Date: 2026-10-07 (local). Reviewed the current `feat/dh-codec-p1-mc1.21.1`
checkout after the [r7 trace fix](2026-10-07-dh-trace.md).

Three subagents independently reviewed diagnostics/UI, lifecycle/cache/commands,
and acquisition/flow control. The primary agent checked their findings against
production wiring, behavior tests, the feature plan and the installed official
DH 3.3.3 bytecode. This was a source review; no new game/performance tests or
production changes were made. Findings below are not fixes or live reproductions.

The production and test manifests still match the tested r7 inputs. NeoForge
candidate SHA256:
`ac23f67b538d42ed405e8e330e6327eaf672da261e848780dba65cc577989fec`.
The complete review input binding is in
[the review receipt](evidence/2026-10-07-dh-native-parity-review.json).

## Findings

| ID | Priority | Gap |
| --- | --- | --- |
| N1 | High | DH fallback permission bypasses reset's no-service confirmation. |
| N2 | Medium | Receive OFF/native takeover does not withdraw live-update subscriptions. |
| N3 | Medium | Separate request clocks can double the aggregate manual download cap. |
| N4 | Medium | Temporary range refusals can permanently park terrain. |
| N5 | Medium | Status hides the remedy for a real DH client conflict. |
| N6 | Medium | DH traces omit shared vanilla network and movement observations. |
| N7 | Low | Shared options imply native-only behaviors work during DH acquisition. |

### N1 — Reset treats permission as an active service

With Use DH enabled, join a vanilla server with neither LSS nor DH and run
`/lss reset`. [The command wiring](../../xplat/src/main/java/dev/vox/lss/networking/client/ClientCommandActions.java#L68)
passes `DhClientSession.selected()` as `dhActive`.
[Selection](../../xplat/src/main/java/dev/vox/lss/networking/client/DhClientSession.java#L59)
checks permission and native precedence, not a channel, working transport,
established configuration or active DH service.
[The coordinator](../../xplat/src/main/java/dev/vox/lss/networking/client/ResetCoordinator.java#L94)
therefore skips its no-service confirmation and can wipe Voxy while promising
re-streaming that the server cannot provide. A stopped/unestablished DH adapter
is also incorrectly classified. Native reception OFF retires its manager, so
the native path reaches the no-service confirmation rather than this DH shortcut.

The [no-manager confirmation test](../../fabric/src/test/java/dev/vox/lss/networking/client/ResetCoordinatorTest.java#L152)
pins the intended protection. The DH coordinator test at line 95 injects a presumed
active session; it does not exercise the production permission-to-service mapping.

Separate the predicates for draining owned work, selecting an address-scoped cache,
and knowing that an established service can re-stream terrain. Retiring work still
needs draining even after native takeover. Test the real command wiring with no DH
channel, receiving disabled, a failed adapter, and an established DH session.

**Related low-priority feedback gap:**
[`clearcache`](../../xplat/src/main/java/dev/vox/lss/networking/client/ClientCommandActions.java#L38)
uses the same permission shortcut and promises new requests on a connected no-DH
server. Preserve its deliberately address-scoped clearing, but qualify the feedback
using actual acquisition availability. The existing address-scoping regression at
`DhClientSessionTest.clearCacheInDhModeClearsOnlyTheCurrentRemoteAddress` constructs
a live DH session and misses this no-service case.

### N2 — Live updates remain subscribed after local retirement

Start an established official-DH session with Live updates ON, then turn reception
OFF or receive a native LSS offer on the same connection.
[Reception retirement](../../xplat/src/main/java/dev/vox/lss/networking/client/DhClientSession.java#L183)
and [native takeover](../../xplat/src/main/java/dev/vox/lss/networking/client/DhClientSession.java#L66)
call [`beginRetire`](../../xplat/src/main/java/dev/vox/lss/networking/client/DhClientSession.java#L568),
which cancels generation requests and retires local work without sending an
updates-disabled preference. Incoming DH data is then discarded locally.
Native [reception reconciliation](../../xplat/src/main/java/dev/vox/lss/networking/client/ClientSessionGate.java#L129)
explicitly withdraws its wants.

The installed DH 3.3.3 implementation retains client preferences in
`ServerPlayerState.sessionConfig.constrainingConfig` until a `SessionConfigMessage`
updates them. `AbstractDhServerLevel.lambda$updateDataSourcesAsync$0` checks
`isRealTimeUpdatesEnabled()` and range before `sendInChunks`; its completion emits
a partial update. Thus eligible updates can still be serialized and transferred
after local retirement. This does not establish additional world generation.

Send a temporary updates-disabled preference before retiring an established
session on its still-owned connection. Preserve saved user preferences for resume;
do not send stale profile controls on a replaced/reconfigured connection. Test
both emitted controls and push cessation after an edit. The existing
[receive-toggle observer](../../test-fixtures/dh-live/src/dev/vox/lssfixture/dh/Probe.java#L443)
checks outgoing requests and retired budgets, not subscription withdrawal.

### N3 — Manual rate limiting is per service rather than aggregate

[DH request spacing](../../xplat/src/main/java/dev/vox/lss/networking/client/DhClientSession.java#L674)
charges sixteen columns per tile, but
[admission](../../xplat/src/main/java/dev/vox/lss/networking/client/DhClientSession.java#L883)
and [request bookkeeping](../../xplat/src/main/java/dev/vox/lss/networking/client/DhClientSession.java#L906)
use separate generation and synchronization clocks. Cached conditional checks use
[the synchronization clock](../../xplat/src/main/java/dev/vox/lss/networking/client/DhClientSession.java#L498).

For a manual limit of 20 columns/s, each service independently permits a sixteen-
column request every 0.8 seconds. Mixed acquisition can therefore admit a charge
of 40 columns/s if replies free the slots. This is an admission/accounting error;
actual arrival throughput also depends on decoding and server speed.
Native [cap composition](../../xplat/src/main/java/dev/vox/lss/networking/client/LodRequestManager.java#L306)
and [scanner spacing](../../xplat/src/main/java/dev/vox/lss/networking/client/SpiralScanner.java#L544)
apply one aggregate column cap.

Keep the separate upstream service clocks, but add one shared manual budget for
all requests, including cached timestamp checks. The existing
[`aRecentSyncRequestDoesNotStarveReachableGenerationAtOneRequestPerSecond`](../../fabric/src/test/java/dev/vox/lss/networking/client/DhClientSessionTest.java#L1489)
uses the unlimited manual default and remains valid. Add a mixed-service positive-
cap test; do not restore the old generation-starvation behavior.

### N4 — Range refusals consume permanent rejection strikes

[Refusal handling](../../xplat/src/main/java/dev/vox/lss/networking/client/DhClientSession.java#L991)
identifies reason 1 as range but routes it through the same cooldown as permanent
rejection. The installed DH 3.3.3 exception list independently confirms index1 is
`RequestOutOfRangeException`.

After four refusals, [the policy](../../common/src/main/java/dev/vox/lss/common/dh/DhSessionPolicy.java#L65)
holds the tile with `unreachable=false`.
[Reachability reconciliation](../../common/src/main/java/dev/vox/lss/common/dh/DhSessionPolicy.java#L59)
cannot revive that state. Movement into range or a later range/border configuration
can therefore leave a tile parked for the session. Repeated refusals are possible
when the player moves between admission and server processing.

The [plan's error policy](../planning/2026-09-29-dh-client-compatibility-plan.md#52-server-limits-and-error-semantics)
requires suppression only until movement/configuration restores eligibility.
The [current refusal test](../../fabric/src/test/java/dev/vox/lss/networking/client/DhClientSessionTest.java#L1474)
covers rate-limit 0 and permanent-rejection 2, not range 1.

Use a separate reachability-dependent parked state without permanent rejection
strikes. Test four range refusals followed by movement/configuration change and
renewed acquisition, retaining the permanent-rejection behavior for reason 2.

### N5 — Real DH conflict details disappear from ordinary status

Install the real `distanthorizons` client mod while Use DH is enabled and native
LSS is absent. Both [Fabric](../../fabric/src/main/java/dev/vox/lss/networking/client/DhClientTransport.java#L26)
and [NeoForge](../../neoforge/src/main/java/dev/vox/lss/networking/client/DhClientTransport.java#L102)
correctly disable the competing transport and retain a message explaining that the
real DH client must be removed to use this adapter.
The [typed diagnostics](../../xplat/src/main/java/dev/vox/lss/networking/client/DhClientSession.java#L115)
instead expose only `NEED_TRANSPORT` with reason `NONE`. The detailed `status()`
string has no remaining production UI consumer. `/lss diag`, `/lss status` and
export therefore lose the actionable remedy.

Retain a typed, translated client-mod-conflict reason in ordinary status/export.
Avoid exposing arbitrary exception or server-supplied text. The
[plan explicitly requires this disclosure](../planning/2026-09-29-dh-client-compatibility-plan.md#41-explicit-source-and-profile-selection).
Current state/reason translation tests do not test conflict installation through
the production status path.

### N6 — DH traces still miss protocol-neutral vanilla telemetry

The r7 fix adds actual DH events. However, the sole
[`ClientNetTrace.maybeEmit` call](../../xplat/src/main/java/dev/vox/lss/networking/client/LodRequestManager.java#L444)
still lives in the native manager, which does not run during DH acquisition.
DH traces therefore omit vanilla RTT, desired chunks per tick, runway/missing
vanilla chunks, FPS and speed. These observations help distinguish network delay
from slow vanilla chunk application during movement pauses.

Extract the protocol-neutral sampler and invoke it for either active source while
tracing. Supply DH wire counters explicitly; unsupported raw-byte/governor fields
must report unavailable rather than borrowing zero native counters. Preserve the
opt-in probe and reset rate baselines on source changes. The new
[DH trace tests](../../fabric/src/test/java/dev/vox/lss/networking/client/DhClientSessionTest.java#L156)
require DH status without a native manager but not the shared observations.
This is incomplete diagnostic parity; DH event tracing itself now works.

### N7 — Native-only options look effective on DH

[Slow Start on Join](../../xplat/src/main/java/dev/vox/lss/config/menu/ClientOptionCatalog.java#L177)
and [the Xaero bridge](../../xplat/src/main/java/dev/vox/lss/config/menu/ClientOptionCatalog.java#L191)
remain enabled whenever reception is enabled. Their affirmative tooltips depend
on [governor/Xaero availability](../../xplat/src/main/java/dev/vox/lss/config/menu/MenuContext.java#L43),
not the actual terrain source.

DH never applies the native adaptive governor/slow-start setting. It deliberately
uses [the Voxy-only sink](../../xplat/src/main/java/dev/vox/lss/compat/DhVoxyBridge.java#L6),
so enabling Xaero does not map DH downloads. Those architectural differences are
intentional; the misleading applicability is the gap.

Disclose native-LSS-only applicability in the tooltips/config documentation,
optionally indicating inactivity for actual DH acquisition. Do not key this on
Use DH alone: enabled fallback still permits native LSS to win. Add source-aware
tooltip cases without extending DH into unvalidated consumers.

## Additional diagnosis improvement

The acquisition gate returns silently for cache readiness, recovery, decoder/Voxy
backlog, pending request saturation and rate/scan spacing. Current DH status exposes
many of these quantities, so this is not another empty-trace defect. An explicit,
edge-triggered waiting reason and next permitted request time would simplify pause
analysis. Keep it bounded and distinguish normal idle completion from waiting.

## Covered or intentional differences

The reviewers found native precedence, owned worker retirement, stale-epoch
rejection, vanilla authority checks, cache identity, source validation and Voxy
readback reuse integrated on both loaders. Shared status/export, commands and the
r7 DH event hooks are present. Rejoins do not blindly re-import every cached tile.

DH's separate tile scheduler, Voxy-only consumer route and upstream-specific
limits/permit behavior are intentional. Missing native quadtree/region-summary,
Xaero, general API consumers or far-player service is not by itself a defect.
The [accepted Voxy queued-write limitation](2026-10-07-dh-accepted-limitations.md)
was excluded from findings.

Movement pruning needs an upstream-permit-aware design: official sync requests
cannot simply be cancelled like generation. Rapid dimension changes and aggregate
retiring-worker pressure remain stress-test questions, not verified defects.
None of this source review establishes the cause or frequency of the user's
movement pause. Prior r7 build/private-client passes remain historical validation
of their tested cases, not coverage of these newly identified triggers.

Recommended correction order: N1, N2, N3/N4, then N5/N6/N7 and the waiting-reason
enhancement. Each behavioral fix needs the targeted trigger regression described
above; accepted limitations and deliberate protocol differences should stay explicit.
