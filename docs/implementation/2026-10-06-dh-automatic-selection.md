# Automatic DH fallback and protocol detection

Date: 2026-10-06. Local Minecraft 1.21.1 implementation in `.worktrees/dh-client-p1`.
This supersedes the explicit source/profile choice described by earlier checkpoints.

Use DH permits a fallback. Native LSS takes priority whenever its handshake channel
is advertised or a native session-config offer arrives. A native offer that disables
service or rejects the negotiated version still prevents DH from bypassing it.
Turning Use DH off permits only native LSS. The receive master remains independent.

| Server | Use DH on | Use DH off |
| --- | --- | --- |
| Native LSS and DH | Native LSS | Native LSS |
| Native LSS only | Native LSS | Native LSS |
| DH only | Automatically detected DH family/version | No remote LOD acquisition |
| Neither | Bounded unavailable-channel stop; ordinary Minecraft stays connected | No remote LOD acquisition |

The client starts native discovery on every eligible join. Fabric uses the remote
`lss:handshake` channel advertisement; NeoForge uses the corresponding negotiated
channel. A late native offer retires the DH worker and cancels a pending protocol
reconnect. This does not revoke data already handed to Voxy's upstream queue;
that limitation remains the separate I1 gate.

There is no DH server selector in either Sodium menu generation. The DH section
contains three checkboxes: Use DH, Sync on load and Live updates. The existing
`lod.source: distant_horizons` value now means permission to use the fallback;
`lss` means native only. This permission remains scoped to the next physical
connection. Sync/live preferences still reload immediately. Retired YAML
`lod.dh.profile` and `lod.dh.refresh_seconds` values are accepted and preserved
when editing other keys, but neither is an active setting or affects behavior.

## Upstream constraints

[DH 3.2.0-b declares protocol 15](https://gitlab.com/distant-horizons-team/distant-horizons-core/-/raw/3.2.0b/api/src/main/java/com/seibel/distanthorizons/coreapi/ModInfo.java).
[DH 3.3.0 declares protocol 16](https://gitlab.com/distant-horizons-team/distant-horizons-core/-/raw/3.3.0/api/src/main/java/com/seibel/distanthorizons/coreapi/ModInfo.java)
and [was released September 17, 2026](https://modrinth.com/mod/distanthorizons/version/Sa0ttGJr).
The official client checks one exact version rather than negotiating backwards:
[packet decoder](https://gitlab.com/distant-horizons-team/distant-horizons/-/raw/3.3.3/common/src/main/java/com/seibel/distanthorizons/common/AbstractPluginPacketSender.java).
The official server's source registers a mismatch CloseReason handler and closes
DH state after rejection. That source-level path is **not reached by the stock
3.2.0-b or 3.3.3 jars on MC 1.21.1**: their Log4j 2.22.1 mismatch logging throws
before the handler, and the error logging throws again before `close(e)`. The
retained stock 3.3.3 mismatch log and both jars' `javap` output confirm this.
Our strict official CloseReason decoder remains a defensive control for peers
with corrected logging; its tests are self-encoder controls, not captured
stock producer replies. Config and level identity still require active requests.

DH Support 0.14.0 uses protocol 15 with different encodings, including a four-byte
CloseReason string prefix instead of official DH's two-byte prefix. Its pinned
producer jar and corpus are the existing local primary evidence. Its timestamp
order and mapping-string encoding also differ, so matching the numeric version
alone cannot safely select its terrain decoder.

## Detection and ownership

Every fresh physical connection initially probes official protocol 16. A valid
protocol-16 config proceeds directly. A complete, strictly decoded protocol-15
rejection uniquely distinguishes official 15 from Support 15. Ambiguous ASCII
protocol-15 config/level frames never guess a family; unknown versions, truncated
controls and terrain fragments never authorize reconnecting.

Both loaders check the remotely advertised DH channel before sending and again
before any legacy trial. Missing or withdrawn channels stop only the adapter;
ordinary Minecraft stays connected. A quiet official-16 probe waits fifteen seconds
and three idle client ticks, with no captured, queued or decoding frame and no
recent arrival. Any DH-channel response disables this silence trial. Slow valid
configuration therefore does not trigger a reconnect at the former five-second
boundary. A registered but disabled official service can still consume the one
bounded trial; silence is not proof that the service implements protocol 15.

DH Support's uniquely decoded wide-string rejection changes the immutable session
codec **on the same physical Minecraft connection**. The pinned 0.14.0 producer
does not close its DH state on mismatch. No reconnect or proxy login throttle is
needed for this family. Stock official 3.2.0-b remains silent on mismatch and needs
one fresh connection to try official 15. Terrain requires a valid config and world
identity on that trial. A silent trial stops the adapter without a second reconnect;
an answered-but-unconfigured service is labelled separately from a missing channel.

The family is confirmed per physical `Connection`, including dimension replacement,
respawn and a temporary null world. Once config has been accepted, discovery never
initiates a reconnect on that connection. Reconfiguration keeps the family and the
consumed reconnect allowance. A slow automatic join still consumes that allowance;
it has no sixty-second expiry that re-arms another retry. Automatic retries require
the original connection/level/server identity, initial play, receiving, the advertised
DH channel and no native offer. Memory hints are bounded and do not skip protocol-16
validation on a fresh user join. Each epoch's codec remains immutable.

Detection parses only bounded control frames, charges temporary parsing to the DH
memory budget, and uses the current epoch's main-thread callback guard. The rejected or unanswered
probe never receives a config acceptance, source-cache identity or terrain receipt.
Each successfully established session has an immutable codec/profile, including
its source-cache partition. Existing Voxy data and raw-source caches survive the
negotiation reconnect; normal sync-on-load/missing-terrain behavior then applies.

Validation and exact artifact receipts are recorded in
`build/dh-neoforge/2026-10-06/follow-up-fixes/` in the outer checkout. The [review disposition](2026-10-06-dh-follow-up-review-disposition.md) records
qualification and separates it from historical runs.
