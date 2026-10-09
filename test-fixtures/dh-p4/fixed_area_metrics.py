"""Identity/correctness checks and fixed-area metric summaries; no fabricated idle/GPU proof."""
import json,hashlib,sys,math
from collections import Counter
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2];sys.path.insert(0,str(ROOT/'tools/rig'))
from mca_fixture import chunk
TARGETS={(6,x,z) for x in range(-5,-2) for z in range(-1,2)}
def unpack(p):
 p&=(1<<64)-1;x=(p>>8)&0xfffffff;z=p>>36
 return (p&255,x-(1<<28) if x&(1<<27) else x,z-(1<<28) if z&(1<<27) else z)
def terrain(world):
 region=Path(world)/'region';rows={}
 for x in range(-20,-8):
  for z in range(-4,8):
   v=chunk(region,x,z)
   if v and (v.get('xPos'),v.get('zPos'))!=(x,z):raise ValueError('Native MCA coordinate mismatch')
   rows[f'{x},{z}']=v.get('Status') if v else None
 return rows
def quantiles(values):
 if not values or any(type(x) not in [int,float] or not math.isfinite(x) or x<0 for x in values):raise ValueError('Invalid actual metric samples')
 a=sorted(values)
 def q(p):return a[min(len(a)-1,math.ceil(len(a)*p)-1)]
 return {'samples':len(a),'p50':q(.5),'p95':q(.95),'p99':q(.99),'max':a[-1],'mean':sum(a)/len(a)}
def persisted_generation(before,after):
 if set(before)!=set(after):raise ValueError('Different persisted terrain coordinates')
 return {'new_full_target_chunks':sum(v=='minecraft:full' and before[k]!='minecraft:full' for k,v in after.items()),'full_target_chunks_after':sum(v=='minecraft:full' for v in after.values()),'new_persisted_target_records':sum(v is not None and before[k] is None for k,v in after.items()),'persisted_target_status_counts':dict(sorted(Counter(v or 'missing' for v in after.values()).items()))}
def validate(result,server,frames,owner,arm):
 if result.get('pass') is not True or result.get('arm')!=arm:raise ValueError('Actual client proof missing')
 if len(server.get('actual_requests',[]))!=9 or {unpack(r['packed']) for r in server['actual_requests']}!=TARGETS:raise ValueError('Different actual request area or request count')
 if any(r['timestamp']!='generation' for r in server['actual_requests']):raise ValueError('Request mode differs')
 if not server.get('terrain_payload_bytes',0)>153 or server.get('generation_events_failed')!=0 or server.get('generation_events_started')!=server.get('generation_events_completed'):raise ValueError('Incomplete server telemetry/generation')
 if server['cpu_last_terrain_send_nanos']<=server['cpu_start_nanos'] or server['wall_last_terrain_send_nanos']<=server['wall_start_nanos']:raise ValueError('CPU/request interval absent')
 if owner.get('timeout') is not False or owner.get('exit_code')!=0 or not owner.get('owned_top_level_windows',0)>0 or owner.get('input_desktop_before')!=owner.get('input_desktop_after'):raise ValueError('Native process/window ownership failed')
 renderer=frames.get('gpu_renderer','').lower()
 if not renderer or any(word in renderer for word in ['llvmpipe','software']) or len(frames.get('gpu_elapsed_ns',[]))<200 or len(frames.get('frame_entry_interval_ns',[]))<200:raise ValueError('Native rendered GPU/frame proof absent')
 if arm=='lss' and (not result.get('all_source_complete_and_voxy_readback') or result.get('actual_loaded_geometry_sections',0)<=0):raise ValueError('Actual Voxy terrain absent')
 if arm=='official' and (not result.get('genuine_installed_dh_queue_decoder_provider') or not result.get('all_provider_updates_complete') or result.get('actual_loaded_render_buffers',0)<=0):raise ValueError('Genuine installed official terrain absent')
 return {'server_cpu_seconds':(server['cpu_last_terrain_send_nanos']-server['cpu_start_nanos'])/1e9,'server_terrain_wall_seconds':(server['wall_last_terrain_send_nanos']-server['wall_start_nanos'])/1e9,'terrain_payload_bytes':server['terrain_payload_bytes'],'generation_events':server['generation_events_completed'],'acquisition_wall_seconds':result['acquisition_wall_seconds'],'frame_interval_ms':quantiles([x/1e6 for x in frames['frame_entry_interval_ns']]),'gpu_elapsed_ms':quantiles([x/1e6 for x in frames['gpu_elapsed_ns']]),'gpu_renderer':frames['gpu_renderer'],'scope':frames['condition']}
def collect(run):
 run=Path(run);runtime=json.loads((run/'runtime.json').read_text());win=Path(runtime['windows_client']);game=win/'minecraft';load=lambda p:json.loads(p.read_text(encoding='utf-8-sig'))
 result,server,frames,owner=map(load,[game/'dh-result.json',run/'evidence/benchmark-server.json',game/'dh-frame-metrics.json',win/'ownership.json']);metrics=validate(result,server,frames,owner,runtime['benchmark_arm'])
 replay=load(run/'evidence/benchmark-source-replay.json')
 if set(replay.get('tiles',{}))!={f'{x},{z}' for _,x,z in TARGETS} or any(row.get('complete_columns')!=4096 for row in replay['tiles'].values()):raise ValueError('Received source replay is incomplete or from another area')
 actual_bytes=replay.get('terrain_payload_bytes',0)
 if not 153<actual_bytes<=metrics['terrain_payload_bytes']:raise ValueError('Received bytes do not agree with encoding telemetry')
 metrics['terrain_encode_invocation_bytes']=metrics['terrain_payload_bytes'];metrics['terrain_payload_bytes']=actual_bytes;metrics['terrain_payload_packets']=replay['terrain_payload_packets'];metrics['bandwidth_scope']='Actual received DH payload bytes, including DH headers, splits and responses; excludes Minecraft framing/TCP overhead. Encode invocation totals retained separately.'
 before=load(run/'evidence/benchmark-native-before.json');after=terrain(run/'server/world');(run/'evidence/benchmark-native-after.json').write_text(json.dumps(after,indent=2)+'\n');metrics.update(persisted_generation(before,after))
 # Artifacts and immutable dependency closure are rechecked after the actual measurement.
 inputs=load(win/'inputs.json')
 for rel,row in inputs['inputs'].items():
  if rel.startswith('minecraft/config/') or rel=='minecraft/options.txt':continue
  if hashlib.sha256((win/rel).read_bytes()).hexdigest()!=row['sha256']:raise ValueError('Windows immutable input changed: '+rel)
 for name,row in inputs['shared_readonly_assets'].items():
  if hashlib.sha256(Path(name).read_bytes()).hexdigest()!=row['sha256']:raise ValueError('Shared asset changed')
 resources=load(win/'ownership.resources.json')
 if any(resources.get(key)!=owner.get(key) for key in ['pid','creation_filetime']) or not resources.get('samples'):raise ValueError('Resource samples have different process ownership')
 samples=resources['samples'];metrics['client_peak_working_set_bytes']=max(r['working_set_bytes'] for r in samples);metrics['client_peak_private_bytes']=max(r['private_bytes'] for r in samples);metrics['client_sampled_process_cpu_seconds_including_boot']=(samples[-1]['kernel_cpu_100ns']+samples[-1]['user_cpu_100ns'])/1e7
 for key in ['conversionCpu','conversionWall','conversionSamples','conversionAllocation','peak_owned_bytes']:
  if key in result:metrics[key]=result[key]
 files=list((game/'.lss/cache/!dh-terrain-v1').rglob('*.dht'));metrics['source_cache_record_bytes_after']=sum(f.stat().st_size for f in files);metrics['source_cache_records_after']=len(files)
 for folder,key in [('Distant_Horizons_server_data','official_client_cache_bytes_after'),('.voxy','voxy_cache_bytes_after')]:
  metrics[key]=sum(f.stat().st_size for f in (game/folder).rglob('*') if f.is_file())
 metrics.update(arm=runtime['benchmark_arm'],run=str(run),windows_root=str(win),runtime_sha256=hashlib.sha256((run/'runtime.json').read_bytes()).hexdigest(),windows_inputs_sha256=hashlib.sha256((win/'inputs.json').read_bytes()).hexdigest(),proof_sha256=hashlib.sha256((game/'dh-result.json').read_bytes()).hexdigest(),idle_machine_confirmed=runtime.get('idle_machine_confirmed') is True)
 (run/'evidence/benchmark-metrics.json').write_text(json.dumps(metrics,indent=2)+'\n');return metrics
if __name__=='__main__':print(json.dumps(collect(sys.argv[1]),indent=2))
