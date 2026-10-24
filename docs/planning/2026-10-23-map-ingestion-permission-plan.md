# Per-player map ingestion permission (Discord request, 2026-10-23)

Status: reviewed (2 Opus + 1 Fable, §11), awaiting the user's approval. Builds on the 0.19.0
server map policy ([plan](2026-10-13-map-policy-and-xaero-cave-layers-plan.md) Part A).

## 1. Request

A Paper operator: "Would it be possible to get the map ingestion to be gated behind a permission?
… I would love to give it to my admins but encourage more natural exploration for normal players."

Today `privacy.request_no_map_ingestion` is server-wide: on, it asks every client not to write LOD
terrain into Xaero's World Map or JourneyMap; off, everyone may. There is no per-player choice.

## 2. Design: an exemption permission, no new setting

Keep `privacy.request_no_map_ingestion` as the only setting, unchanged (default false). While it
is on, a player holding the new permission is **exempt**: the server treats the policy as off for
that player.

```yaml
privacy:
  # Ask clients not to add distant terrain to their world maps (Xaero's World Map, JourneyMap).
  # Players with the lss.map_ingestion (vss.map_ingestion) permission are exempt; on Fabric that
  # needs a permissions mod such as LuckPerms. Older clients that can't follow this get no
  # distant terrain until they update, unless exempt. Honest clients follow it; it is a request,
  # not enforcement.
  request_no_map_ingestion: true
```

| Brand | Node | Default |
| --- | --- | --- |
| LSS | `lss.map_ingestion` | false (nobody) |
| VSS | `vss.map_ingestion` | false |

Both spellings are declared in both jars and honored with OR, like `farplayers.hidden`. A
LuckPerms `lss.*` wildcard includes the node; the plugin.yml description says what it does.

**Why no mode setting** (`map_ingestion: allow|deny|permission`): a new key is refused by 0.19
("Unknown settings path"), which adds a downgrade break; it needs a bool→enum migration and moves
the schema pins; and "permission" mode is just "deny" plus the node.

**Why default false, not op:** servers that turned the policy on in 0.19.0 keep exactly today's
behaviour until someone grants the node; op-by-default would silently exempt every op there and
need per-line op-level code. No op fallback on Fabric without a permissions mod (behaviour would
differ by install).

## 3. Server

1. **A fail-closed exemption check.** Every existing permission read in the service gate fails
   OPEN (answers yes on error or with no provider), so the exemption must not reuse them. Add
   `PlayerServiceGate.mapIngestionExempt()` (default false; `OPEN` answers false) with fail-closed
   implementations: Fabric/NeoForge/Sponge `checkPermission(player, node, false)` for both
   spellings, Paper `hasPermission` with a catch that answers false (Folia read races). A separate
   sweep probe and test seam, default false.
2. **One decision point.** `denyMapIngestionFor(player, cfg)` = policy on ∧ ¬exempt, used at every
   flag-carrying send and policy decision:
   - xplat: handshake decision and reply, repush, dimension change, the map-policy sweep,
     `mapPolicyCleared`, and the service-gate revocation push (`RequestProcessingService` ~1220);
   - Paper: handshake decision, `refusedAtSend` (`LSSPaperPlugin` ~749), the plugin sender lambda
     and the service's own sender lambda (`PaperRequestProcessingService` ~951-956), the grant
     sweep's `mapPolicyCleared` (~1107/1114), the map sweep (~1167), the drain's `honorsMapPolicy`
     (~1210).
3. **Live changes for current clients** (map-policy-capable, 0.19+): the 10 s map-policy sweep
   re-evaluates each such session while the policy is on and pushes one flags-only SessionConfig
   only when the result differs from the last flags sent. The last-sent value lives in a UUID-keyed
   map in `ServiceGateState` (cleared on disconnect, written at every flag-carrying send), not in
   `PlayerRequestState`, which is rebuilt on every dimension change. A grant or revoke takes effect
   within about 10 s; the client applies a flags-only change on its fast path, and a map-only
   parked session un-parks on grant and re-parks on revoke (existing behaviour).
4. **Older clients** (no `CAPABILITY_MAP_POLICY`, including legacy v16/18/19 dialects): exemption
   is decided at handshake only. An exempt player is served instead of refused; a non-exempt one is
   refused as today. A grant or revoke applies at their next join. This keeps the existing "legacy
   sessions are never revoked mid-session" rule and needs no new hysteresis or memo path.
5. **Diag:** the `MapPolicy:` line gains `exempt=<n>`, a count cached by the sweep (no permission
   reads at command time), and on Fabric the permission provider token. No boot WARN when no
   provider is installed: arming the policy asks for nothing permission-related, and the template
   comment and diag cover it.

Cost: one or two permission reads per current capable session every 10 s, only while the policy is
on.

## 4. Client

No change. A player granted mid-session writes terrain that arrives from then on; terrain that
arrived while writing was blocked is not written later (Part A §A4). `/lss clearcache` re-streams
it.

## 5. Protocol

None. Reuses `sessionFlags` bit 0x1; frames to sessions without the capability stay
byte-identical.

## 6. Tests

- `MapPolicyWiringContractTest`: both cores assign the armed decision from the helper; a census of
  every production `requestNoMapIngestion()` read with an allowlist (sweep guard, reconcile
  adoption, diag, reload feedback).
- `LoaderPermissionSeamContractTest.everyServiceGateCallSitePassesDefaultTrue`: narrow to an
  allowlist with the exemption reads as named fail-closed exceptions.
- Gametests beside the existing `mapPolicy*` ones in `ServiceLifecycleGameTests`: grant and revoke
  within one sweep for a capable client; no redundant push when nothing changed (and none after a
  dimension change); an exempt old client served at handshake, a non-exempt one refused; Fabric
  with no provider (nobody exempt).
- Paper/Folia twins in `PaperMapPolicyTest`, including a throwing read = not exempt.
- Pins: `PluginYmlContractTest`, `LSSNeoPermissionsContractTest` (exact `addNodes` list and
  `nodeFor` mapping), `SpongePermissionsTest` (widen its node regex to allow `_`),
  `release_check.py` dual-spelling loop plus a `default: false` check for the new pair (an `op`
  default would exempt every op), its self-test fixtures, `MapPolicyGateTest`,
  `DiagnosticsFormatterTest`.
- Mutations: drop the exemption from one send site; make a failed read exempt; keep the last-sent
  map in per-dimension state.

## 7. Lines and docs

Same change on all six lines (26.x server cores are byte-identical; the 1.21.x diffs don't touch
map-policy code; permission files are identical). Six-line re-pin. Docs: generated settings
reference, server.yaml/paper.yaml template comments, README map-policy paragraph, CLAUDE.md
map-policy paragraph, release notes.

## 8. Validation

Six local gates. Live: Paper 26.3 with LuckPerms (official build) granting and revoking the node
for a soak client with Xaero (diag and the client's map state); Fabric 26.3 without a provider
(nobody exempt). Astra: one row on 26.3 (an exempt player's Xaero map fills, a non-exempt one's
doesn't).

## 9. Reply (Discord, for the user to post)

> This is coming in the next release. With `privacy.request_no_map_ingestion: true`, players with
> the `lss.map_ingestion` permission (`vss.map_ingestion` for VSS) can still add distant terrain to
> their maps and everyone else can't. Grant it with your permission plugin; it applies within about
> 10 seconds (at the next join for players on older LSS versions), and a newly granted player's map
> fills from then on. It's a request the LSS client honors, so a modified client could ignore it.

## 10. Decisions for the user

- U1: default `false` (recommended; reviewers agree) or `op`.
- U2: ship as 0.20.0 together with the Paper version guard and the merged #364 far-plane option
  (recommended; reviewers agree).

## 11. Plan review (2 Opus + 1 Fable, 2026-10-23)

| Finding | Severity | Disposition |
| --- | --- | --- |
| Every existing permission read fails open; reusing them exempts everyone on provider-less Fabric and on Folia throws | MAJOR | Folded §3.1: separate fail-closed member and probe |
| "Last flags sent" has no home; `PlayerRequestState` dies on every dimension change | MAJOR | Folded §3.3: UUID-keyed map in `ServiceGateState` |
| Old-client mid-session revoke needs a new hysteresis and memo path | MINOR (both) | Simplified §3.4: handshake only, rejoin applies |
| Paper site list incomplete | MINOR | Folded §3.2 |
| Census accepts raw policy reads | MINOR | Folded §6 |
| Fabric no-provider WARN can't reuse the service-gate one / is noise | MINOR (both) | Dropped the WARN; diag + template cover it (§3.5) |
| `SpongePermissionsTest` regex rejects `_` | MINOR | Folded §6 |
| Legacy dialects are affected (exempt ones are now served) | MINOR | Folded §3.4 |
| NeoForge `nodeFor` mapping; HandshakeGate rows add nothing; diag count cached; template wording; release_check default check; reply details | NIT | Folded §3.5, §6, §2, §9 |
| Node name could be `lss.map_ingestion.exempt` | NIT | Kept `lss.map_ingestion` (reads as "may ingest"); described in plugin.yml |
| Release vehicle 0.20.0 | MINOR | Recommended (U2) |
