# Follow-up improvements from official DH client behavior

Date: 2026-10-08. Status: M1–M4 implemented and validated locally.
Scope: local Minecraft 1.21.1 Fabric and NeoForge DH compatibility. This extends
the [compatibility plan](2026-09-29-dh-client-compatibility-plan.md), using the
[official-client review](../implementation/2026-10-07-dh-official-behavior-review.md)
and its retained, version-specific evidence.

[Completed implementation and validation](../implementation/2026-10-08-dh-official-behavior-followups.md)
and [Claude review brief](../implementation/2026-10-08-dh-post-review-claude-briefing.md).
The tested candidate is installed; the same-world manual server is restored.
The sequence below records the agreed scope and acceptance requirements.

## Recommendation

Implement clear acquisition-wait diagnostics first. Then reproduce and, if
confirmed, fix timeout cleanup that can be postponed by unrelated incoming
traffic. Finally, measure fragment allocation costs and remove one redundant
internal copy if the benefit is established. Keep each change independently
reviewable and verify the final packaged clients before preparing manual tests.

The purpose is to explain loading pauses, keep obsolete work from holding request
slots or memory, and reduce avoidable allocation. None of these benefits has yet
been demonstrated to fix the user's movement-related pause; diagnostics should
establish its cause before further scheduler changes.

| Order | Work | Evidence today | Decision |
| --- | --- | --- | --- |
| M1 | Explain acquisition waits in diagnostics and traces | Existing counters omitted the actual admission gate | Complete |
| M2 | Make expiry progress despite unrelated traffic | A deterministic regression reproduced postponed cleanup | Fixed and validated |
| M3 | Reduce fragment copies without changing public ownership | Four retained payloads showed reduced allocation; CPU ratios are too noisy for a speedup claim | Narrow copy removal complete |
| M4 | Validate the combined candidate and prepare a short manual course | Normal gates, ten packaged checks, installation and restoration pass | Complete |

**Subsequent review, October 8:** the
[post-review corrections](../implementation/2026-10-08-dh-post-review-fixes.md)
pass 3,421 production unit tests, 86 server gametests and ten fresh packaged
checks. The older M4 ready/restoration observations describe the r1 capture time;
its watchdog subsequently stopped the manual server. The corrected r2 candidate
was temporarily blocked by a sandbox denying loopback sockets and Windows writes.
After unrestricted access returned, the additional live pressure check passed,
Prism was updated, and the same manual world was restored. A real console-first
save/stop/restart rehearsal also passed. Final readiness has its own time-stamped
owner, network and save acknowledgements.

M1 adapts the reviewed scheduling behavior to LSS diagnostics; it is not an
official DH feature-parity claim. M2 and M3 address specific differences observed
in the official receiver and request lifecycle. A disproved concern or a change
without a useful measured benefit gets a documented disposition, not speculative
production code.

## Baseline already implemented

The [r9 disposition](../implementation/2026-10-07-dh-parity-disposition.md) records
the completed adaptations and validation. Do not implement them again:

* Optional 50–500 decimal KB/s startup ramp, retained across dimension and
  reception changes on the same physical connection.
* Server service limits applied to request rate and outstanding requests; shared
  manual limits also cover cached conditional checks.
* Service-specific temporary-refusal backoff and separate range/permanent refusal
  handling.
* Cancellation of obsolete official generation with spatial and time hysteresis;
  synchronization ownership and already captured replies remain protected.
* Sync-on-load observation survives unrelated preference changes. Reception OFF
  withdraws server subscriptions; reconnect respects Voxy's existing content and
  source-cache validation.

DH Support 0.14 ignores its advertised bandwidth setting. Its implemented local
startup spacing is 4× the normal minimum for the first 2.5 seconds, 2× for the next
2.5 seconds, then normal. Response bursts remain server-controlled. Preserve this
verified distinction from the two official protocol families.

## M1 — Explain why acquisition is waiting

### Implementation

1. Represent the result of existing acquisition/admission checks as a small,
   typed observation. Record it where the real decision happens in
   `DhClientSession`; do not run another terrain scan just for diagnostics.
   Cached conditional requests and ordinary acquisition must use the same rate,
   slot and backoff reasoning.
2. Distinguish waiting for channel/config/world initialization, Voxy readiness,
   cache work, recovery, decode/ingest pressure, total or service request slots,
   server pacing, the shared manual limit, Support startup pacing, refusal
   backoff, scan cadence, and no eligible work in current interest. Server-disabled
   services, range/border restrictions and parked/deferred tiles must not be
   reported as proof that all desired terrain is present.
3. Expose a stable primary reason and bounded secondary reasons where several
   gates apply. Report remaining gate time in milliseconds only for known local
   deadlines. A service with a slot/readiness dependency has no known admission
   time; its value is unavailable. This is never an ETA for terrain delivery.
4. Include the observation in the shared DH status used by `/lss diag`, local
   diagnostic export and opt-in `dh_status` traces. Add concise translated text
   where status displays it. Preserve existing fields and unavailable sentinels.
   Clear stale observations on request admission, source/world/session changes,
   disabled reception and disconnect.
5. Keep the existing one-second trace sampling. Any transition event must be
   coalesced and rate-bounded so alternating reasons cannot produce per-tick spam.
   Use allowlisted reason codes and numbers; no addresses, world keys, payloads
   or server-supplied text. Tracing OFF must not format events or send new probes.

Primary files: `DhClientSession`, `DhClientTrace`, `DhStatusSnapshot`, shared
status text/export and locale resources. No new settings or command alias.

### Acceptance

* Deterministic session tests force each meaningful gate and verify its reason,
  known/unknown wait, and transition back to admission. Include generation versus
  synchronization, cached checks, rate refusal, manual cap and Support startup.
* A controlled loading pause can be explained from one status snapshot and its
  trace interval. Idle current interest is distinguishable from an outstanding
  request, recovery or pressure stall.
* Diagnostic privacy tests, trace stop/reset behavior and existing source
  precedence tests pass. Exported codes and translated status agree.
* Diagnostic observation does not change request timing, retry budgets, cache
  freshness, source selection or admission order.

## M2 — Expire silent work without waiting for global quiet

### Establish the problem first

Add a deterministic session regression with a silent request and an incomplete
buffer while a bounded stream of unrelated frames keeps capture ownership
nonempty. Advance the test clock through idle and absolute deadlines while
allowing the worker to progress. Record whether pending requests, buffer leases
and slot ownership remain beyond their intended cleanup opportunity.

Also read and retain `DhAssemblyTest`'s arrival-time pins: a frame captured before
its deadline must survive delayed processing, while a late arrival cannot extend
the request's birth-time deadline. Passing those tests is a prerequisite.

### Implementation if confirmed

Replace the blanket `frames.isEmpty()` expiry condition with a conservative
arrival cutoff shared by pending-request timeout and worker-owned assembly
maintenance. A candidate is the earliest outstanding captured/processing arrival,
or current time when none remain. Prove capture publication, queue ordering and
concurrent arrivals cannot let cleanup pass a still-timely frame before selecting
the exact API and locking protocol. Treat this as the design gate, not permission
to expire everything by worker wall time.

Run assembly mutation and expiry on its existing single worker. A processing
frame remains protected until it finishes. Unrelated frames arriving after a
deadline must not perpetually protect an older silent request or buffer. Preserve
60-second normal and 120-second official startup birth-time lifetimes, the
ten-second buffer idle limit, and all count/byte limits.

Keep protocol-specific ownership intact: official generation may be cancelled;
official synchronization cannot be assumed to release an upstream permit on
local timeout; Support has no usable cancel handler. Preserve the existing
bounded unanswered-request/reconnect policy. Anonymous fragments cannot be
assigned to a request until the response names their buffer.

Consider the generation-pruning quiet guard in the same investigation. Change
it only if the same proven capture barrier can protect timely replies; otherwise
retain the current guard and record the reason. Do not reorder control and terrain
frames or introduce a second decode worker to solve this issue.

### Acceptance and stop condition

* With a progressing worker and continuing post-deadline traffic, eligible
  cleanup runs on the next safe maintenance turn rather than waiting for total
  traffic silence. Record cleanup delay in the deterministic fixture.
* Timely queued fragments/replies survive client and worker delays; genuinely
  late arrivals expire. Test idle versus birth deadlines, startup lifetime changes,
  interleaved buffers, processing frames and captures racing maintenance.
* Late responses, cancellation, pressure recovery, receiver OFF and dimension
  replacement release leases exactly once and cannot deliver old-session terrain.
* Silent sync does not trigger unbounded retries or pretend server slots are free.
* If the concern cannot be reproduced, retain the regression investigation and
  document the actual bound. If safe cleanup cannot be proved, keep the existing
  behavior, report the wait through M1 and record a specific remaining limitation.
  Do not weaken the timely-arrival contract.

## M3 — Remove a redundant fragment copy if worthwhile

### Measurement and narrow change

Use the retained terrain corpus in an offline Java 21 allocation/CPU comparison.
Replay identical single- and multi-fragment payloads for both official families
and Support, with warmup and repeated runs. Separate capture, envelope decode,
assembly, flattening, terrain decode and cache costs; preserve corpus hashes and
record payload sizes. No Windows GPU timing or new server-load benchmark is
needed for this decision.

First consider sharing the internally immutable `DhMessage.Split` contents with
assembly instead of cloning them again. The public constructor and byte accessor
must remain defensive. No writable backing array may escape or be mutated after
publication. Assembly must acquire its own reservation before retaining data and
keep it until release; codec/capture reservations must cover overlapping ownership.

Keep completed-payload flattening, decompression and cache interfaces unchanged
in this milestone. Composite/segmented decode is a separate proposal only if the
measurements show flattening remains a material cost worth the larger ownership
and API change. Do not claim allocation improvements imply better GPU frame times.

### Acceptance and stop condition

* Measurement confirms removal of a fragment-sized allocation/copy on the real
  path, reduces allocated bytes for fragmented payloads and shows no repeatable
  decode/assembly CPU regression. Investigate noisy or conflicting results before
  claiming an improvement.
* Mutation of constructor inputs or public accessor results cannot affect decoded
  terrain. Fragment interleaving, compressed formats, malformed input, resource
  rejection, cancellation and retirement preserve content and lease accounting.
* Owned-memory high-water accounting remains conservative, limits are unchanged,
  and failure/close paths release everything exactly once.
* If ownership requires a larger refactor or measurement shows no useful gain,
  document the result and defer the optimization.

## M4 — Validate and prepare manual testing

For each production change, run focused behavioral tests first. After the final
candidate, run normal Java 21 verification, both platform server gametest suites,
artifact/release checks and the affected packaged private-client cases. Use the
maintained verification wrapper and existing harness ownership/storage policy;
run heavy checks sequentially. Retain first failures, source/artifact hashes and
actual counts. There is no Tier 3 client gametest task or Folia gate on this line.

Packaged coverage must include official 3.3.3 on both loaders, official 3.2.0-b
and Support 0.14, plus targeted movement, load/rejoin, live traffic, reception
OFF/resume and pressure behavior. Verify diagnostic/export privacy and native
LSS precedence. Preserve the current protocol selection and wire contracts.

After validation, prepare a candidate-installation receipt and a short manual
course: fly into new terrain, stand still during a pause, then rejoin. Collect an
opt-in trace and `/lss diag` during a pause and after recovery; the expected result
is an identifiable wait followed by admitted requests when its gate clears.
Prism installation requires the selected Minecraft instance to be closed.
Preserve preferences, other mods and the manual server's world/OP/configuration.
Planning itself leaves the installed r9 client and running server unchanged.

Close this follow-up when M1 is implemented and verified, M2/M3 each have an
evidence-backed implementation or deferral, and the changed candidate passes M4.
This does not waive remaining qualification or the original upstream-outreach
prerequisite; work and evidence remain local.

## Behaviors deliberately deferred

| Behavior | Reason to defer / revisit trigger |
| --- | --- |
| Official adaptive decreases down to 1 KB/s | Sparse generation and idle samples do not establish congestion. Require evidence of sustained congestion and a controller compatible with fixed response lifetimes. |
| Resetting adaptive state immediately when enabled mid-connection | Existing hot OFF opens the budget; hot ON applies next connection, matching native LSS join behavior. No demonstrated benefit justifies changing that preference contract. |
| More than one generation or two total outstanding requests | Requires measured throughput benefit without server generation or client pressure regressions. Larger queues are not a demonstrated cure for pauses. |
| Broad queue-priority changes or parallel decoding | Require a reproduced starvation case and an ordering-safe design. Preserve existing control/data relationships and single-worker ownership. |
| Full segmented/composite decoding | Measure the narrow copy removal first; only pursue if residual allocation cost warrants the complexity. |
| Relaxing memory quotas or adopting official receiver timeouts verbatim | LSS's stronger bounds and startup lifetime distinction protect this different rendering/ingest path. |

The [accepted Voxy queued-write ordering/completion limitation](../implementation/2026-10-07-dh-accepted-limitations.md)
remains accepted. Revisit it when supported fencing/completion APIs become
available or ordinary play makes the visual effect unacceptable; this plan does
not promise per-write persistence or revoke that decision.
