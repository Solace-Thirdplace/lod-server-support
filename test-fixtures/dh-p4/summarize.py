#!/usr/bin/env python3
"""Summarize the registered three cold and three warm pairs, retaining raw runs."""
import argparse
import hashlib
import json
import statistics
from pathlib import Path
from fixed_area_metrics import collect


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def summarize(base):
    rows, pairs = [], []
    for condition in ('cold', 'warm'):
        for number in range(1, 4):
            paired = []
            source = None
            warm_identity = None
            for arm in ('official', 'lss'):
                parent = base / f'{condition}-pair{number}-{arm}'
                run = Path((parent / 'private-run-path.txt').read_text().strip())
                metrics = collect(run)
                runtime = json.loads((run / 'runtime.json').read_text())
                replay = json.loads((run / 'evidence/benchmark-source-replay.json').read_text())
                if source is not None and source != replay['tiles']:
                    raise ValueError(f'Different blocks/biomes/light in {condition} pair {number}')
                source = replay['tiles']
                before = json.loads((run / 'evidence/benchmark-native-before.json').read_text())
                if condition == 'cold' and any(v is not None for v in before.values()):
                    raise ValueError('Cold target had pre-existing native records')
                if condition == 'warm':
                    current = runtime.get('warm_world_sha256')
                    if not current or warm_identity is not None and current != warm_identity:
                        raise ValueError('Warm producer world inputs differ')
                    warm_identity = current
                metrics.update(condition=condition, pair=number,
                               replay_sha256=sha(run / 'evidence/benchmark-source-replay.json'))
                rows.append(metrics)
                paired.append(metrics)
            pairs.append({'condition': condition, 'pair': number,
                          'identical_complete_source_blocks_biomes_light': True,
                          'identical_received_bytes': paired[0]['terrain_payload_bytes'] == paired[1]['terrain_payload_bytes'],
                          'server_cpu_lss_over_official': paired[1]['server_cpu_seconds'] / paired[0]['server_cpu_seconds'],
                          'acquisition_lss_over_official': paired[1]['acquisition_wall_seconds'] / paired[0]['acquisition_wall_seconds']})
    summaries = []
    for condition in ('cold', 'warm'):
        for arm in ('official', 'lss'):
            group = [r for r in rows if r['condition'] == condition and r['arm'] == arm]
            values = {
                'server_cpu_seconds': [r['server_cpu_seconds'] for r in group],
                'acquisition_wall_seconds': [r['acquisition_wall_seconds'] for r in group],
                'frame_p99_ms': [r['frame_interval_ms']['p99'] for r in group],
                'gl_timer_p99_ms': [r['gpu_elapsed_ms']['p99'] for r in group],
                'peak_working_set_mib': [r['client_peak_working_set_bytes'] / 2**20 for r in group],
                'peak_private_commit_gib': [r['client_peak_private_bytes'] / 2**30 for r in group],
                'server_window_tiles_per_second': [9/r['server_terrain_wall_seconds'] for r in group],
                'per_arm_completion_tiles_per_second': [9/r['acquisition_wall_seconds'] for r in group],
            }
            summaries.append({'condition': condition, 'arm': arm, 'runs': len(group),
                              'metrics': {k: {'median': statistics.median(v), 'min': min(v), 'max': max(v)} for k, v in values.items()},
                              'received_bytes': [r['terrain_payload_bytes'] for r in group],
                              'generation_events': [r['generation_events'] for r in group]})
    return {'scope': 'Nine fixed tiles; warm means warm producer and fresh client. Three pairs per condition. '
                     '120fps cap, steady camera after loading, private non-input Windows desktop. '
                     'GL timer spans include submission gaps/cap; these are not isolated GPU busy time or display latency. '
                     'Per-arm acquisition clocks differ. Official requests are injected at t0 into the actual queue. '
                     'Both arms synchronously capture raw frames in the timed window; idle confirmation is an authorization timer, not a GPU-idleness sensor.',
            'all_idle_confirmed': all(r['idle_machine_confirmed'] for r in rows),
            'pairs': pairs, 'summary': summaries, 'runs': rows}


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('base', type=Path)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    result = summarize(args.base)
    args.output.write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result['summary'], indent=2))
