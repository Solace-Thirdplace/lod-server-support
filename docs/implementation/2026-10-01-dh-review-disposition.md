# DH review corrections and NeoForge verification

This records the follow-up to [Claude's review](2026-10-01-dh-claude-review.md). The original review is preserved. Work is local and experimental; outreach remains pending.

| Verified finding | Correction |
| --- | --- |
| D1: capture resources absent from Fabric's normal Gradle test classpath | The common test-fixtures artifact carries the capture corpus under its original `dh-corpus/` paths. Both converter captures pass in normal Gradle tests. The nested-checkout source-locator failures disappear in an isolated source copy outside the parent repository; source contracts were retained. |
| D2: endless successful-tile regeneration | DH refresh now defaults to 0. Accepted receipts stay held until reset/reconnect. Explicit refresh permits 30–3600 seconds; the scheduler also limits accepted-tile refreshes to one per ten seconds. Reload affects future receipts. |
| D3: 4096-receipt cap smaller than a 4225-tile frontier | Receipt capacity is 8192, with regression coverage for the maximum frontier. |
| D4: queued decode time mistaken for peer idleness | Capture the arrival timestamp in each transport and pass it into assembly. Defer expiry while captured frames remain; a busy decoder does not turn an on-time response into a silent timeout. |
| D5: unfinished XZ did not check decoded trailing bytes | Each compressed blob must reach its terminal read; only the documented unfinished-stream EOF is accepted. Decoded trailing bytes remain malformed. |
| R1: rejecting Voxy retried indefinitely | Wait for a live Voxy world and available ingest service, stop on the bridge's ABI latch, and stop after three whole rejected tiles. Tile failures back off for 30/60/120 seconds, then remain parked after four attempts. Unknown backlog remains supported with conservative pacing. |
| R2: Support string direction and modified-UTF mismatch | Outbound Support strings use UTF-8 byte lengths; inbound Support retains UTF-16-unit lengths. Mapping strings use plain UTF-8 with the appropriate prefix. Unicode alignment and aggregate byte-budget tests were added. |
| R3: transient assembly failures killed acquisition | Expired/unowned responses cancel and park their own request. Valid responses clear the unanswered streak. Queue overflow and linkage failure remain visible terminal errors. Server text is bounded and strips controls/formatting. |
| R4: reset/native timestamps survived an unsuccessful wipe | A strict alias sweep runs before acquisition or DH reset, including reset before first ingest and an inactive selected DH source. Failed deletion, inaccessible roots, interruption and IO timeout are observable; the session cannot claim a completed invalidation. The native non-strict behavior stays unchanged. |
| R5: portal travel lost vanilla authority | Authority belongs to the connection and is partitioned by dimension, with an aggregate bound. Returning to a dimension retains its vanilla set. Changing its logical world key parks acquisition until reconnect. |
| R6: retaining vanilla authority after a Voxy wipe | Retained as the deliberate authority policy. Reset clears DH receipts, not vanilla ownership; visited vanilla chunks repopulate through vanilla loading. Connection/policy tests pin this tradeoff. |
| R7: missing session lifecycle regression tests | Follow-up unit and private live verification exercise ownership, cancellation, movement and reset. Exact executed results are recorded with the final artifact receipt. |
| Terminal retirement left official generation queued | Best-effort Cancel is independent of the active flag, tied to the original connection, and pending owners are cleared. |
| Support CloseReason prefix narrowed to 16 bits | Decode its 32-bit length with bounded UTF validation. |
| NeoForge bidirectional decoder charged guest packets to host DH | Direction-neutral retained frames are bounded separately until the NETWORK callback identifies flow. Only clientbound frames enter DH ownership. Logout and a five-second orphan cleanup release retained frames idempotently. |
| Support world-key fallback could request the wrong world | Wait for the server's level initialization; no dimension-name fallback is used. A missing reply stops acquisition with a visible reason. |
| Official generation unavailable for an absent tile | Park absence without generation rather than spinning permanently on an impossible generation request. |
| Idle scan/expiry tasks churned | Scan at most twice per second; coalesce expiry work and apply ingress backpressure. |
| Documentation treated enqueue counts as completed storage | Status labels enqueue counts explicitly. The live fixture reads actual Voxy storage before accepting the proof. |
| Corpus hash described as an independent oracle | Normalized hashes are implementation regression pins. The POC's independently measured identities/spans/readback are separate evidence; they do not make those hashes an independent byte oracle. |
| Official format-2 wire corpus was missing | Freeze actual DH 3.3.3 NeoForge terrain fragments and response, excluding opaque handshake IDs. Independently decoded coverage/span/voxel counts accompany the regression hash. |
| Actual official mapping/height quirks rejected valid terrain | Accept official `AIR` and stateless `_STATE_` suffixes; scope exactly one H+1 ceiling AIR voxel to the evidenced Support/official-16 builders. Opaque, two-block and entirely-above-height negative controls remain strict. |
| Official partial sync tiles did not schedule missing coverage | Preserve known columns, schedule bounded generation for unknown eligible columns, and prioritize the required generation without treating unknown cells as air or bridge rejection. |
| Source template omitted Voxy replacement behavior | The packaged YAML now says that DH downloads replace Voxy terrain and do not provide native LSS services. |

Official DH 3.3.3 is the available Minecraft 1.21.1 server build used for this port, with protocol profile `official_16` and NeoForge negotiation version `1`. The plan's 3.3.4 artifact is not available for this Minecraft version. This is an explicit coverage difference, not a claim to have run that reference version.

Builds use Java 21 and the shared harness lock. The isolated source copy at `/tmp/lss-dh-neo-build` avoids the review's parent/child source-locator ambiguity. Production jars are normal Gradle artifacts; the live fixture is a separate, test-only jar and is never installed in the user's profile.

Final normal Gradle unit results: common **983**, Fabric **1672** (four existing
opt-in experiment/capture tool skips), Paper **536**, NeoForge **23**, zero failures/errors.
The **10** actual session lifecycle tests pass without skips, including the full
Fabric suite that checks their global-state restoration. Fabric **78** and
NeoForge **8** required server gametests passed. Normal LSS/VSS artifacts on all
three loaders pass `release_check.py`; its **124** synthetic cases and **9** SQLite
packaging helper tests pass. These checks do not establish every live release gate.

The final production-jar NeoForge/official-16 run passed **73,728** storage samples
with **22,996** solids, movement and reset, and a settled geometry suppression
control that visibly removes the distant solid hillside. The client includes the
same Voxy/Sodium/Connector/Xaero jars as the selected Windows profile. The software
render proof uses llvmpipe, not native Windows graphics. The tested jar is installed
in the idle Prism target with backups; the replacement NeoForge server is running
and Windows-reachable. Exact hashes/results are in
`build/dh-neoforge/2026-10-01/verification.json` and `test-setup-completed.json` in
the outer repository.
