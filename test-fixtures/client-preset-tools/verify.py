import argparse,json,hashlib,sys
import checks
from pathlib import Path
from checks import jar_mod_ids,FORBIDDEN,allowed_change,fresh_feedback,verify_exports

def verify(root,repo):
 checks.verify_tool_identity(root,repo,{'entrypoint':__file__,'checks.py':checks.__file__})
 sys.path.insert(0,str(Path(repo)/'tools/rig'))
 from rig_settings import values
 root=Path(root);read=lambda p:json.loads(Path(p).read_text());m=read(root/'manifest.json');runtime=read(root/'runtime.json');e=root/'evidence/standalone-settings';r=read(e/'receipt.json')
 for k in ['run_id','run_hash','profile_hash','scenario_hash']:
  if r[k]!=m[k]:raise ValueError('receipt identity differs')
 if r['status']!='raw-controls-complete' or set(r['assertions'])!={'standalone_export','saved_only','hot_reload','session_boundary','invalid_rejected','restored'} or not all(v is True for v in r['assertions'].values()):raise ValueError('incomplete native controls')
 stage={f['target']:f for f in runtime['stage_files']}
 for f in runtime['stage_files']:
  if f['target'].startswith(('settings-tools/','instances/lss-rig-client/minecraft/mods/')):
   data=(root/f['target']).read_bytes()
   if hashlib.sha256(data).hexdigest()!=f['sha256']:raise ValueError('changed staged source/mod')
   if f['target'].endswith('.jar') and jar_mod_ids(data)&FORBIDDEN:raise ValueError('Sodium/Voxy/Connector in actual staged runtime')
 log=(root/'instances/lss-rig-client/minecraft/logs/latest.log').read_bytes()
 if [row['label'] for row in r.get('reconnects',[])]!=['reconnected','restored-connection']:raise ValueError('physical reconnect observations absent')
 for row in r['reconnects']:
  if row['joined_marker'] not in log[row['log_offset']:row['log_end']].decode(errors='replace'):raise ValueError('fresh rejoin log observation absent')
 for i,s in enumerate(r['steps']):
  if not s['feedback']:continue
  end=r['steps'][i+1]['log_offset'] if i+1<len(r['steps'])else len(log);text=log[s['log_offset']:end].decode(errors='replace');expected=s['feedback']
  ok=any(('[CHAT] '+expected+' ')in line for line in text.splitlines()) if expected in ('Diagnostics exported:','Settings unchanged:') else fresh_feedback(text,expected)
  if not ok:raise ValueError('native command receipt not present after action')
 for label,value in r['configs'].items():
  if values(e/(label+'-config.yaml'),side='client')!=value:raise ValueError('config evidence differs from receipt')
 b=r['configs']['baseline']
 allowed_change(b,r['configs']['saved-only'],{'lod.receive':True})
 allowed_change(b,r['configs']['hot-reloaded'],{'lod.receive':True})
 allowed_change(b,r['configs']['session-saved'],{'lod.receive':True,'integrations.map_ingestion.enabled':True})
 allowed_change(b,r['configs']['restored'],{})
 invalid=e/'rejected-config.yaml'
 if hashlib.sha256(invalid.read_bytes()).hexdigest()!=r['rejected_document_sha256']:raise ValueError('rejected YAML evidence changed')
 try:values(invalid,side='client')
 except RuntimeError:pass
 else:raise ValueError('rejection control must contain invalid YAML')
 for label,receive,writes in [('standalone',False,False),('saved-only',False,False),('hot-reloaded',True,False),('session-saved',True,False),('session-pending',True,False),('reconnected',True,True),('invalid-rejected',True,True),('repaired',True,True),('restored-connected',False,True),('restored',False,False)]:
  v=r['snapshots'][label];raw=read(e/(label+'-export-'+str(v['timing']['export_attempts'])+'.json'))
  if raw!=v['snapshot']:raise ValueError('typed export evidence differs from receipt')
  verify_exports(raw,receive,writes)
  if v['snapshot']['capturedAtMillis']<=v['timing']['action_completed_ms']:raise ValueError('stale export used')
 for x in r['screens'].values():
  if hashlib.sha256((root/'evidence'/x['artifact']).read_bytes()).hexdigest()!=x['sha256']:raise ValueError('changed screen evidence')
 return r
if __name__=='__main__':
 p=argparse.ArgumentParser();p.add_argument('root',type=Path);p.add_argument('--repo',required=True,type=Path);a=p.parse_args();verify(a.root,a.repo)
