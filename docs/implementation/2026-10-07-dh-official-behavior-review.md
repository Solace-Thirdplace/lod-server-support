# Official DH client behavior review and adaptations

Three subagents reviewed scheduling, lifecycle and transport/pressure behavior against
actual DH 3.3.3 and 3.2.0-b client classes. This supplements the
[native parity review](2026-10-07-dh-native-parity-review.md). Candidate validation
and installation results belong in the [parity disposition](2026-10-07-dh-parity-disposition.md).

## Adopted behavior

| Observation | LSS adaptation | Regression |
| --- | --- | --- |
| Official DH starts adaptive transfer at 50,000 bytes/s, increasing by 50,000 with traffic | Existing Adaptive Rate + Slow Start settings enable a one-way 50–500 decimal KB/s join ramp in DH configuration messages. Both official protocols enforce the bandwidth hint. Support 0.14 carries but ignores it; that path uses a five-second local request-spacing ramp (4×, 2×, then its normal minimum). | `DhBandwidthRampTest`; `dhSlowStartUsesWirePreferencesAndSurvivesSessionReplacement` |
| Official congestion state belongs to `ClientNetworkState`, retained when a level unloads | Ramp state belongs to the physical connection; dimension replacement and reception pauses keep earned progress. A fresh physical connection gets new state. | Session replacement regression |
| Official request queues and server limiters use the service limit for both rate and concurrency | Shared admission respects each service's outstanding limit as well as its request spacing. Generation stays at one and total requests at two. Cached conditional checks use the same admission. | `syncConcurrencyAtRateOneKeepsTheIndependentGenerationSlotAvailable` |
| Official rate refusals exhaust the affected queue's available burst allowance | A bounded one-second pause affects the refused service, including fresh and cached work. Generation and synchronization remain independent. This is a conservative adaptation: official burst draining is not an exponential sustained-rate reduction. | `rateRefusalPausesItsWholeServiceButNotGenerationOrPermanentRetryBudget` |
| Official retrieval work is pruned as render sections leave interest | After two seconds, official generation requests wholly outside interest plus 12 chunks of hysteresis are cancelled. Sync ownership stays intact; Support has no cancel handler. Already captured/processing replies finish under their existing ownership. Anonymous fragments remain bounded and are discarded by late replies or expiry. | Three movement/captured-reply regressions |
| Official config updates preserve the synchronization visited-position debounce | Live-only and bandwidth-only changes preserve load observation history. Sync OFF→ON starts a fresh load observation. | `changingLiveUpdatesAlonePreservesLoadDebounceAndHeldReceipts`; bandwidth-only regression |

The optional ramp is **startup pacing, not a copy of the entire official congestion
controller**. Official DH also reduces its budget on undersaturated samples, down
to 1 KB/s. LSS does not interpret sparse generation replies or idle periods as
congestion, avoiding a prolonged throttle. Turning either controlling setting off
opens the budget immediately; turning it back on applies to the next connection,
consistent with LSS's join behavior. Official hot adaptive enable resets its
controller immediately; this is an intentional difference.

Official startup requests and buffers retain a bounded 120-second birth-time lifetime.
Support keeps its 60-second transfer lifetime because its ramp delays admission
rather than throttling an in-progress response.
New work returns to 60 seconds once the ramp opens. The ten-second idle bound,
byte limits, fragment limits, two-request cap and eight-buffer cap stay intact.
The official server's independent bandwidth maximum remains authoritative.
Support 0.14 explicitly marks `max_data_transfer_speed` unsupported; actual
fragment bursts remain server-controlled. Local pacing reduces startup request
pressure, and preserves earned startup time across level/reception replacement.
This exception is verified against the installed plugin artifact and retained in
`parity-fixes/dh-support-bandwidth-evidence.json`.

## Follow-ups requiring separate evidence

The [2026-10-08 implementation plan](../planning/2026-10-08-dh-official-behavior-improvements-plan.md)
orders these follow-ups and defines their evidence and acceptance gates.

* Official DH retains composite buffer components; LSS currently copies fragments
  and flattens the completed payload. Removing an internal copy or adding segmented
  parsing may reduce allocation costs. The copies are verified; their performance
  impact was not measured by this review. Preserve defensive public ownership and
  existing memory reservations before changing this.
* Current expiry deliberately waits for captured frames to drain so timely frames
  delayed in the worker survive. Sustained unrelated traffic could postpone cleanup;
  this is an investigation candidate, not a reproduced ordinary-play defect. A
  deterministic test and bounded arrival-watermark design are needed before changing
  the pinned arrival-time contract.
* Explicit acquisition-wait reasons would help diagnose movement pauses. Existing
  queue, permit, request age, cache and recovery trace fields remain available.

The reviewers found no missing memory safety bound relative to official DH.
Official 3.3.3's payload receiver uses a 30-second idle cache without visible byte
or buffer-count quotas. LSS keeps its stronger accounting, decompressor limits,
source-cache identity/checksums and Voxy readback requirements. The
[accepted Voxy queued-write limitation](2026-10-07-dh-accepted-limitations.md)
remains accepted; native precedence and the DH Voxy-only sink are intentional.

## Provenance

Actual installed artifacts inspected:

* DH 3.3.3: `864e70def5b0d54d619b940b66bb2784e91d3f3fc253064fdc1b3fc5786cbfab`
* DH 3.2.0-b: `d6a7a963f794501678113e2545d8b25c90e123ef6b9256bb5716907d059e1ef5`

`javap` excerpts and upstream files are retained under
`build/dh-neoforge/2026-10-07/parity-fixes/official-review-evidence/`, with hashes
in `official-review-evidence.json`. Actual artifacts are the version-specific
reference; moving upstream main source corroborates the algorithms:
[congestion controller](https://gitlab.com/distant-horizons-team/distant-horizons-core/-/raw/main/core/src/main/java/com/seibel/distanthorizons/core/multiplayer/client/ClientCongestionControl.java),
[request queues](https://gitlab.com/distant-horizons-team/distant-horizons-core/-/raw/main/core/src/main/java/com/seibel/distanthorizons/core/multiplayer/client/AbstractFullDataNetworkRequestQueue.java),
[movement pruning](https://gitlab.com/distant-horizons-team/distant-horizons-core/-/raw/main/core/src/main/java/com/seibel/distanthorizons/core/render/QuadTree/LodQuadTree.java).

No new performance or Windows GPU frame-time benchmarks were run for this review.
