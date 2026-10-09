import sys,unittest
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from fixed_area_metrics import validate,quantiles,TARGETS,persisted_generation
class PerformanceProof(unittest.TestCase):
 def data(self):
  packed=lambda p:(p[0]|((p[1]&0xfffffff)<<8)|((p[2]&0xfffffff)<<36))
  return {'pass':True,'arm':'official','acquisition_wall_seconds':2,'genuine_installed_dh_queue_decoder_provider':True,'all_provider_updates_complete':True,'actual_loaded_render_buffers':1},{'actual_requests':[{'packed':packed(p),'timestamp':'generation'} for p in TARGETS],'terrain_payload_bytes':300,'generation_events_failed':0,'generation_events_started':9,'generation_events_completed':9,'cpu_start_nanos':1,'cpu_last_terrain_send_nanos':2,'wall_start_nanos':1,'wall_last_terrain_send_nanos':2},{'gpu_renderer':'NVIDIA test','gpu_elapsed_ns':[1]*200,'frame_entry_interval_ns':[1]*200,'condition':'private desktop'},{'timeout':False,'exit_code':0,'owned_top_level_windows':1,'input_desktop_before':'Default','input_desktop_after':'Default'}
 def test_real_area_and_ownership_required(self):
  r,s,f,o=self.data();validate(r,s,f,o,'official')
  s['actual_requests'].append(s['actual_requests'][0])
  with self.assertRaisesRegex(ValueError,'request'):validate(r,s,f,o,'official')
 def test_software_and_changed_input_desktop_rejected(self):
  r,s,f,o=self.data();f['gpu_renderer']='llvmpipe'
  with self.assertRaises(ValueError):validate(r,s,f,o,'official')
  f['gpu_renderer']='NVIDIA test';o['input_desktop_after']='foreign'
  with self.assertRaises(ValueError):validate(r,s,f,o,'official')
 def test_queued_only_terrain_and_failed_generation_rejected(self):
  r,s,f,o=self.data();r['actual_loaded_render_buffers']=0
  with self.assertRaises(ValueError):validate(r,s,f,o,'official')
  r['actual_loaded_render_buffers']=1;s['generation_events_failed']=1
  with self.assertRaises(ValueError):validate(r,s,f,o,'official')
 def test_percentiles_retain_actual_sample_count(self):
  q=quantiles(list(range(1,101)));self.assertEqual((100,50,95,99,100),(q['samples'],q['p50'],q['p95'],q['p99'],q['max']))
 def test_proto_records_are_not_full_generation(self):
  r=persisted_generation({'a':None,'b':None,'c':'minecraft:full'},{'a':'minecraft:biomes','b':None,'c':'minecraft:full'})
  self.assertEqual(0,r['new_full_target_chunks']);self.assertEqual(1,r['full_target_chunks_after']);self.assertEqual(1,r['new_persisted_target_records']);self.assertEqual({'minecraft:biomes':1,'minecraft:full':1,'missing':1},r['persisted_target_status_counts'])
  with self.assertRaises(ValueError):persisted_generation({'a':None},{'b':None})
if __name__=='__main__':unittest.main()
