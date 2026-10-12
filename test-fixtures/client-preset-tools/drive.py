"""Actual owned command route; launch only after native DirectConnect and inspected world frame."""
import argparse,json,time,sys,hashlib
from pathlib import Path
import checks
from verify import verify
from checks import allowed_change,fresh_feedback,verify_exports,verify_tool_identity,verify_active_rig
p=argparse.ArgumentParser();p.add_argument('root',type=Path);p.add_argument('--joined-layout',required=True,type=Path);p.add_argument('--repo',required=True,type=Path);a=p.parse_args();r=a.root.resolve();tree=verify_tool_identity(r,a.repo,{'entrypoint':__file__,'checks.py':checks.__file__,'verify.py':verify.__code__.co_filename});sys.path.insert(0,str(tree/'tools/rig'))
from rig import read,write,inside,regular,sha,alive
from native_window import find
from private_input import XInput
from ui_snapshot_wait import observe_after_action
from rig_settings import edit,values
verify_active_rig(tree,{name:sys.modules[name] for name in ('rig','native_window','private_input','ui_snapshot_wait')})
manifest=read(r/'manifest.json');layout=read(a.joined_layout)
if layout['run_hash']!=manifest['run_hash'] or sha(regular(inside(r/'evidence',layout['inspected_screenshot'])))!=layout['inspected_screenshot_sha256']:raise ValueError('actual inspected joined-screen binding required')
window,owner=find(r,'instances/lss-rig-client/minecraft');d=XInput(r,window,owner);g=r/'instances/lss-rig-client/minecraft';cfg=g/'config/lss-client-config.yaml';log=g/'logs/latest.log';e=r/'evidence/standalone-settings';e.mkdir(exist_ok=False);original=cfg.read_bytes();baseline=values(original.decode(),side='client');assert baseline['lod.receive']is False and baseline['integrations.map_ingestion.enabled']is False
receipt={k:manifest[k]for k in ['run_id','run_hash','profile_hash','scenario_hash']};receipt.update(steps=[],snapshots={},configs={},screens={},assertions={},status='running');deadline=time.monotonic()+600;action_ms=0
def save():write(e/'receipt.json',receipt)
def wait(f):
 while time.monotonic()<deadline:
  if not alive(read(r/'owner.json')):raise ValueError('owned supervisor ended')
  v=f()
  if v:return v
  time.sleep(.1)
 raise ValueError('native command deadline')
def config(label):
 raw=cfg.read_bytes();(e/(label+'-config.yaml')).write_bytes(raw);receipt['configs'][label]=values(raw.decode(),side='client');return receipt['configs'][label]
def command(text,feedback=None):
 global action_ms
 offset=log.stat().st_size;d.key('t');time.sleep(.4);d.text(text);time.sleep(.15);d.key('Return');action_ms=time.time_ns()//1000000
 if feedback:wait(lambda:fresh_feedback(log.read_bytes()[offset:].decode(errors='replace'),feedback))
 receipt['steps'].append({'command':text,'feedback':feedback,'log_offset':offset,'action_completed_ms':action_ms});save();time.sleep(.2)
def capture(name):
 d.focus();time.sleep(.2);d.capture('standalone-settings/'+name+'.png');receipt['screens'][name]={'artifact':'standalone-settings/'+name+'.png','sha256':sha(e/(name+'.png')),'window':window,'process':owner};save()
def export(label,receive,writes=None,button=False):
 def request(end):
  before={x.name for x in (g/'lss-diagnostics').glob('*.json')}
  if button:d.click(*layout['status_export'])
  else:command('/lss diagnostics export','Diagnostics exported:') # Path-bearing feedback handled below instead.
  while time.monotonic()<end:
   files=[x for x in (g/'lss-diagnostics').glob('*.json')if x.name not in before]
   if len(files)>1:raise ValueError('ambiguous export')
   if files:return files[0].read_bytes()
   time.sleep(.1)
  raise ValueError('export deadline')
 def retain(i,raw):(e/(label+'-export-'+str(i)+'.json')).write_bytes(raw)
 raw,timing=observe_after_action(request,action_ms,receive,retain,timeout=8);snapshot=json.loads(raw);verify_exports(snapshot,receive,writes)
 if snapshot.get('connected')is not True:raise ValueError('native connected state missing')
 receipt['snapshots'][label]=dict(snapshot=snapshot,timing=timing);save();return snapshot
# Diagnostics path contains a dynamic suffix. Require native [CHAT] prefix, never arbitrary echoed user text.
original_feedback=fresh_feedback
def fresh_feedback(text,expected):
 if expected in ('Diagnostics exported:','Settings unchanged:'):return any(('[CHAT] '+expected+' ')in line for line in text.splitlines())
 return original_feedback(text,expected)
def reconnect(label):
 global action_ms
 offset=log.stat().st_size
 for route in ('disconnect_to_title','rejoin'):
  for action in layout[route]:
   if action.startswith('key:'):d.key(action[4:])
   else:d.click(*layout[action])
   time.sleep(.5)
 action_ms=time.time_ns()//1000000
 wait(lambda:layout['joined_log_marker'] in log.read_bytes()[offset:].decode(errors='replace'))
 receipt.setdefault('reconnects',[]).append({'label':label,'log_offset':offset,'log_end':log.stat().st_size,'joined_marker':layout['joined_log_marker']})
 time.sleep(.5)
 capture(label)

try:
 config('baseline');command('/lss status');time.sleep(.7);capture('standalone-status');export('standalone',False,False,button=True);d.click(*layout['status_done']);time.sleep(.3)
 edit(cfg,{'lod.receive':True},side='client');config('saved-only');export('saved-only',False,False)
 command('/lss reload','Client settings reloaded.');config('hot-reloaded');export('hot-reloaded',True,False)
 edit(cfg,{'integrations.map_ingestion.enabled':True},side='client');config('session-saved');export('session-saved',True,False)
 command('/lss reload','No active client settings changed.');export('session-pending',True,False)
 reconnect('reconnected');export('reconnected',True,True)
 accepted=cfg.read_bytes();cfg.write_bytes(accepted+b'\nlod: [broken\n')
 (e/'rejected-config.yaml').write_bytes(cfg.read_bytes());receipt['rejected_document_sha256']=sha(cfg)
 command('/lss reload','Settings unchanged:');export('invalid-rejected',True,True)
 cfg.write_bytes(accepted);command('/lss reload','No active client settings changed.');export('repaired',True,True)
 cfg.write_bytes(original);command('/lss reload','Client settings reloaded.');config('restored');export('restored-connected',False,True)
 reconnect('restored-connection');export('restored',False,False)
 assert cfg.read_bytes()==original
 receipt['assertions']=dict.fromkeys(('standalone_export','saved_only','hot_reload','session_boundary','invalid_rejected','restored'),True)
 receipt['status']='raw-controls-complete';save();verify(r,tree)
 write(e/'pending-screen-review.json',{'run_hash':manifest['run_hash'],'artifact':'standalone-settings/standalone-status.png','sha256':sha(e/'standalone-status.png'),'status':'raw controls passed; actual screen inspection required before finalization'})
except Exception as error:receipt['status']='failed';receipt['error']=str(error);save();raise
finally:
 cfg.write_bytes(original)
 d.close()
