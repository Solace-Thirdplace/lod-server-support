#!/usr/bin/env bash
# JourneyMap map-ingestion live smoke (map-ingestion plan §6.4 L1).
#
#   ./scripts/journeymap_smoke.sh              # JourneyMap on the client only
#   ./scripts/journeymap_smoke.sh world-id     # also on the server: the late world-id race
#
# Stages the soak client's settings (map ingestion ON, explicitly — the fixture must not
# depend on the fresh-file default), clears JourneyMap's map data, runs the journeymap-map
# soak with JourneyMap (Modrinth, All Rights Reserved, never redistributed) on the dev
# client runtime, then judges what JourneyMap wrote with check_journeymap_smoke.py and
# exports a crop of the map for a visual check. Exit code = the first failing stage.
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CLIENT_RUN_DIR="$PROJECT_ROOT/fabric/build/run/soak-client"
CLIENT_CONFIG="$CLIENT_RUN_DIR/config/lss-client-config.yaml"
MODE="${1:-client}"
case "$MODE" in
    client|world-id) ;;
    *) echo "usage: $0 [client|world-id]" >&2; exit 2 ;;
esac

echo "[jm-smoke] Staging the soak client's settings (integrations.map_ingestion.enabled=true)"
mkdir -p "$(dirname "$CLIENT_CONFIG")"
if [[ ! -f "$CLIENT_CONFIG" ]]; then
    python3 "$PROJECT_ROOT/tools/settings/settings_file.py" create --side client --path "$CLIENT_CONFIG" > /dev/null
fi
python3 "$PROJECT_ROOT/tools/settings/settings_file.py" edit --side client --path "$CLIENT_CONFIG" \
    --set integrations.map_ingestion.enabled=true --set integrations.map_ingestion.backpressure=true > /dev/null

# A missing or stale base world would be auto-built by soak.sh INSIDE this run's environment —
# fresh-backfill with JourneyMap installed, pre-filling the very map this smoke judges. Build it
# first, without the JourneyMap levers.
BASE_VERSION_FILE="$PROJECT_ROOT/soak-worlds/base/mc-version"
LINE_VERSION="$(sed -n 's/^minecraft_version=//p' "$PROJECT_ROOT/gradle.properties")"
if [[ ! -f "$BASE_VERSION_FILE" || "$(cat "$BASE_VERSION_FILE")" != "$LINE_VERSION" ]]; then
    echo "[jm-smoke] Base world missing or stale — building it first (no JourneyMap)"
    env -u SOAK_CLIENT_GRADLE_ARGS -u SOAK_EXTRA_GRADLE_ARGS "$PROJECT_ROOT/scripts/soak.sh" fresh-backfill
fi

echo "[jm-smoke] Clearing JourneyMap's map data"
rm -rf "$CLIENT_RUN_DIR/journeymap/data"

STARTED_EPOCH=$(date +%s)
export SOAK_CLIENT_GRADLE_ARGS="${SOAK_CLIENT_GRADLE_ARGS:-} -Plss.journeymapRuntime=true"
if [[ "$MODE" == "world-id" ]]; then
    export SOAK_EXTRA_GRADLE_ARGS="${SOAK_EXTRA_GRADLE_ARGS:-} -Plss.journeymapRuntime=true"
fi
SOAK_EXIT=0
"$PROJECT_ROOT/scripts/soak.sh" journeymap-map || SOAK_EXIT=$?

RESULTS_DIR=""
for dir in "$PROJECT_ROOT"/soak-results/journeymap-map-*; do
    [[ -d "$dir" && $(stat -c %Y "$dir") -ge $STARTED_EPOCH ]] && RESULTS_DIR="$dir"
done
if [[ -z "$RESULTS_DIR" ]]; then
    echo "[jm-smoke] FAIL: no journeymap-map results directory from this run" >&2
    exit 1
fi

CHECK_ARGS=(--jm-dir "$CLIENT_RUN_DIR/journeymap"
            --scenario "$PROJECT_ROOT/scripts/soak-scenarios/journeymap-map.json"
            --config "$PROJECT_ROOT/scripts/soak-scenarios/journeymap-map-config.yaml"
            --client-jsonl "$RESULTS_DIR/client-run1.jsonl"
            --client-log "$RESULTS_DIR/client-run1.log"
            --client-log "$CLIENT_RUN_DIR/journeymap/journeymap.log"
            --export-crop "$RESULTS_DIR/journeymap-day.png")
[[ "$MODE" == "world-id" ]] && CHECK_ARGS+=(--world-id)
MAP_EXIT=0
python3 "$PROJECT_ROOT/scripts/check_journeymap_smoke.py" "${CHECK_ARGS[@]}" | tee "$RESULTS_DIR/journeymap-verdict.txt" \
    || MAP_EXIT=${PIPESTATUS[0]}
cp -r "$CLIENT_RUN_DIR/journeymap/data" "$RESULTS_DIR/journeymap-data" 2>/dev/null || true
cp "$CLIENT_RUN_DIR/journeymap/journeymap.log" "$RESULTS_DIR/journeymap.log" 2>/dev/null || true

echo "[jm-smoke] results: $RESULTS_DIR (soak checker exit $SOAK_EXIT, map verdict exit $MAP_EXIT)"
if (( SOAK_EXIT != 0 )); then exit "$SOAK_EXIT"; fi
exit "$MAP_EXIT"
