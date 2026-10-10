#!/usr/bin/env python3
"""Prepare new P4 worlds on top of the bounded existing rig; never mutate a prior run."""
import argparse,os,json,subprocess,shutil,hashlib,sys
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2];OUTER=Path('/home/vox/projects/voxel-server-support/build/dh-neoforge')
p=argparse.ArgumentParser();p.add_argument('--case',choices=['content','benchmark','legacy-menu','configuration'],required=True);p.add_argument('--base',type=Path,required=True);p.add_argument('--fixture',type=Path,required=True);p.add_argument('--profile',choices=['official_16','official_15','dh_support_15'],default='official_16');p.add_argument('--plan',choices=['DISABLED','CHUNKS_ONLY','SURFACE_THEN_CHUNKS','SURFACE_ONLY'],default='DISABLED');p.add_argument('--range',type=int,default=12);p.add_argument('--no-sky',action='store_true');p.add_argument('--builder',choices=['FullBuilder','FastOverworldBuilder'],default='FullBuilder');p.add_argument('--dummy-chunk',action='store_true');p.add_argument('--expect',choices=['complete','partial','missing','surface'],default='complete');p.add_argument('--build',type=Path,default=Path('/tmp/lss-dh-p4-20261007-r3'));p.add_argument('--candidate-dir',type=Path,default=OUTER/'2026-10-07/p4-coverage-performance');p.add_argument("--window-width",type=int,default=1280);p.add_argument("--window-height",type=int,default=720);a=p.parse_args()
if not 640<=a.window_width<=4096 or not 480<=a.window_height<=2160:raise ValueError("Private window size outside bounded fixture range")
previous=a.candidate_dir;build=a.build;env=os.environ.copy();env['LSS_SETTINGS_CLASSPATH']=(build/'common/build/settings-cli/classpath.txt').read_text().strip()
family='support' if a.profile=='dh_support_15' else 'official';dh=OUTER/'2026-10-01/server/mods/DistantHorizons-3.3.3-1.21.1-fabric-neoforge.jar'
if a.profile=='official_15':dh=OUTER/'2026-10-05/p2-p4-completion/official-15-runs/attempt-n2p837js/server/mods/DistantHorizons-3.2.0-b-1.21.1-fabric-neoforge.jar'
scenario='menu' if a.case=='legacy-menu' else 'service' if family=='support' or a.profile=='official_15' else 'updates'
bootstrap="import sys,runpy;sys.path[:0]=[sys.argv.pop(1),sys.argv.pop(1)];import bounded_storage;bounded_storage.install();sys.argv[0]=sys.argv.pop(1);runpy.run_path(sys.argv[0],run_name='__main__')"
argv=['python3','-c',bootstrap,str(ROOT/'test-fixtures/dh-p4'),str(ROOT/'tools/rig'),str(ROOT/'test-fixtures/dh-live/prepare.py'),'--artifact',str(previous/'lod-server-support-neoforge-dh-dev-mc1.21.1.jar'),'--fixture',str(a.fixture),'--dh',str(dh),'--base',str(a.base),'--scenario',scenario,'--profile',a.profile,'--server-family',family,'--builder',a.builder]
out=subprocess.check_output(argv,cwd=ROOT,env=env,text=True);run=Path(out.strip());(a.base/'private-run-path.txt').write_text(str(run)+'\n');runtime=json.loads((run/'runtime.json').read_text());runtime.update(p4_case=a.case,p4_plan=a.plan,p4_range=a.range,p4_no_sky=a.no_sky,scenario='p4')
config=run/'instances/dh-neoforge/instance.cfg';text=config.read_text();text=text.replace('-Dlss.rig.scenario='+scenario,'-Dlss.rig.scenario=p4 -Dlss.p4.case='+a.case+' -Dlss.p4.range='+str(a.range)+' -Dlss.p4.expect='+a.expect);config.write_text(text)
settings=run/'instances/dh-neoforge/minecraft/config/lss-client-config.yaml';sysroot=ROOT/'tools/settings'
import sys;sys.path.insert(0,str(sysroot));from settings_file import edit
os.environ['LSS_SETTINGS_CLASSPATH']=env['LSS_SETTINGS_CLASSPATH'];edit(settings,{'lod.distance_chunks':a.range,'lod.dh.real_time_updates':a.case=='legacy-menu'},side='client')
if a.case=='legacy-menu':
 config.write_text(config.read_text().replace('MinecraftWinWidth=1280','MinecraftWinWidth='+str(a.window_width)).replace('MinecraftWinHeight=720','MinecraftWinHeight='+str(a.window_height)))
 runtime['legacy_window_pixels']=[a.window_width,a.window_height]
 mods=run/'instances/dh-neoforge/minecraft/mods';legacy=Path('/mnt/c/Users/Ian/AppData/Roaming/PrismLauncher/instances/lss-astra-validation-neoforge-legacy-1.21.1/minecraft/mods');removed=[]
 for f in list(mods.glob('*.jar')):
  if f.name.startswith(('voxy-','sodium-','connector-','ConnectorExtras-')):removed.append(str(f.relative_to(run)));f.unlink()
 runtime['source_inputs']=[r for r in runtime['source_inputs'] if r['target'] not in removed]
 for name in ['sodium-neoforge-0.6.13+mc1.21.1.jar','voxy-0.2.9-alpha.jar.disabled']:
  source=legacy/name;target=mods/name.removesuffix('.disabled');shutil.copy2(source,target);runtime['source_inputs'].append({'target':str(target.relative_to(run)),'sha256':hashlib.sha256(source.read_bytes()).hexdigest()})
if a.case in ['benchmark','configuration']:edit(settings,{'lod.dh.sync_on_load':False},side='client')
if a.case=='content':
 if family=='support' and a.no_sky:raise ValueError('No-sky content needs the owned custom dimension')
 pack=run/'server/world/datapacks/lss-content-fixture'
 if not pack.exists():
  shutil.copytree(OUTER/'2026-10-07/p4-coverage-performance/content-official16/attempt-hq5nhrxy/server/world/datapacks/lss-content-fixture',pack)
 dtype=pack/'data/lssfixture/dimension_type/tall.json';data=json.loads(dtype.read_text());data['has_skylight']=not a.no_sky;dtype.write_text(json.dumps(data))
 dim=pack/'data/lssfixture/dimension/tall.json';data=json.loads(dim.read_text());data['generator']['settings']['layers']=[{'block':'minecraft:stone','height':256},{'block':'minecraft:red_concrete','height':32},{'block':'minecraft:sea_lantern','height':32},{'block':'minecraft:glass','height':32},{'block':'minecraft:oak_leaves','height':32},{'block':'minecraft:stone','height':104},{'block':'minecraft:water','height':24}];dim.write_text(json.dumps(data));runtime['p4_dimension']='minecraft:overworld' if family=='support' else 'lssfixture:tall'
 config.write_text(config.read_text().replace('-Dlss.p4.case=content','-Dlss.p4.case=content -Dlss.p4.dimension='+runtime['p4_dimension']))
if family=='support':
 plugin_config=run/'server/plugins/DHSupport/config.yml';text=plugin_config.read_text().replace('render_distance: 12','render_distance: '+str(a.range))
 if a.dummy_chunk:
  text=text.replace('#generate_new_chunks: false','generate_new_chunks: false')
  dummy='#dummy_chunk:\n#  - "0-4,bedrock"\n#  - "5-79,stone"\n#  - "80-126,water"'
  if dummy not in text:raise ValueError('Pinned Support dummy config shape differs')
  text=text.replace(dummy,'dummy_chunk:\n  - "0-4,bedrock"\n  - "5-79,stone"\n  - "80-126,water"')
 plugin_config.write_text(text);runtime['dummy_chunk_enabled']=a.dummy_chunk
if family=='official':
 (run/'server/config').mkdir(exist_ok=True)
 (run/'server/config/DistantHorizons.toml').write_text('_version = 5\n[server]\nenableRealTimeUpdates = false\nmaxGenerationRequestDistance = '+('0' if a.plan=='DISABLED' else '32')+'\nmaxSyncOnLoadRequestDistance = 128\n[common.worldGenerator]\ngeneratorPlan = "'+a.plan+'"\n')
# Bind every owned distribution input to current content; generated world/config hashes are separate.
for row in runtime['source_inputs']:
 f=run/row['target'];assert hashlib.sha256(f.read_bytes()).hexdigest()==row['sha256']
runtime['generated_inputs']={str(f.relative_to(run)):hashlib.sha256(f.read_bytes()).hexdigest() for f in [config,settings,*((run/'server/world/datapacks').rglob('*.json')),*((run/'server/config').glob('*.toml')),*((run/'server/plugins/DHSupport').glob('config.yml'))] if f.is_file()}
(run/'runtime.json').write_text(json.dumps(runtime,indent=2)+'\n');print(run)
