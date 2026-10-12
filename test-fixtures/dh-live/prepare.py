#!/usr/bin/env python3
"""Stage only distribution files and test artifacts into a fresh private run."""
import argparse,hashlib,json,os,shutil,sys,tempfile,zipfile,socket
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
sys.path[:0]=[str(ROOT/'tools/rig'),str(ROOT/'tools/settings')]
import storage_guard
from prepare_prism import launcher_inputs
from settings_file import render_files
from line import LINE
p=argparse.ArgumentParser();p.add_argument('--artifact',required=True,type=Path);p.add_argument('--fixture',required=True,type=Path);p.add_argument('--dh',required=True,type=Path);p.add_argument('--base',required=True,type=Path);p.add_argument('--scenario',choices=['lifecycle','menu','absence','content','service','race','rejoin','updates','boundary','load','movement'],default='lifecycle');p.add_argument('--arm',choices=['lss','official','dh-range128','dh-range32','dh-off'],default='lss');p.add_argument('--load-range',type=int,default=32);p.add_argument('--cold-seconds',type=int,default=420);p.add_argument('--warm-seconds',type=int,default=180)
p.add_argument('--server-family',choices=['official','support','vanilla'],default='official')
p.add_argument('--profile',choices=['dh_support_15','official_15','official_16'],default='official_16')
p.add_argument('--expect',choices=['ready','no-service','conflict','ocean','old-protocol'],default='ready')
p.add_argument('--terrain-source',choices=['dh','native'],default='dh')
p.add_argument('--moving-border',action='store_true')
p.add_argument('--bounded-storage',action='store_true')
p.add_argument('--slow-config',action='store_true')
p.add_argument('--allow-dh',action='store_true',help='Enable DH fallback even in the native boundary case')
p.add_argument('--source-unset',action='store_true',help='Write no experimental client key (no lod.source, no sidecar): the client runs on the shipped defaults')
p.add_argument('--boundary-expect',choices=['report','covered'],default='covered')
p.add_argument('--builder',choices=['FullBuilder','FastOverworldBuilder'],default='FullBuilder')
p.add_argument('--loader',choices=['neoforge','fabric'],default='neoforge');a=p.parse_args()
if a.bounded_storage:
 sys.path.insert(0,str(Path(__file__).resolve().parent))
 import bounded_storage
 bounded_storage.install()
if a.slow_config:
 with zipfile.ZipFile(a.fixture) as fixture:
  if 'dev/vox/lssfixture/dh/mixin/SlowConfigMixin.class' not in fixture.namelist():
   raise ValueError('--slow-config requires a separately compiled private DH-linked overlay; the public observer no longer contains it')
if a.scenario=='load' and a.arm not in ['lss','official'] or a.scenario=='movement' and a.arm not in ['dh-range128','dh-range32','dh-off']:raise ValueError('Arm does not belong to this measurement scenario')
if a.scenario=='content':raise ValueError('Forced source-cycle scenario retired: native LSS now wins on dual servers; use native boundary --allow-dh')
if LINE['client_mods'][a.loader] is None:raise ValueError('This line has no private '+a.loader+' client route to Voxy; run the Fabric client')
# 1.21.11/1.21.10: the Fabric observer also drives updates, lifecycle and DH-terrain boundary; the native
# boundary arm stages the candidate on the NeoForge server, which needs the NeoForge artifact.
if a.loader=='fabric' and a.scenario not in ['service','rejoin','menu','updates','lifecycle','boundary']:raise ValueError('Fabric extension covers service, rejoin, menu, updates, lifecycle and boundary scenarios only')
if a.loader=='fabric' and a.scenario=='boundary' and a.terrain_source=='native':raise ValueError('The native boundary arm stages the candidate on the NeoForge server; it needs the NeoForge artifact')
if a.scenario!='service' and (a.server_family!='official' or a.profile!='official_16' or a.expect!='ready'):raise ValueError('Alternate service inputs require the service scenario')
if a.expect=='ocean' and (a.scenario!='service' or a.server_family!='official'):raise ValueError('Seeded ocean checks require an official service scenario')
with socket.socket(socket.AF_INET6,socket.SOCK_STREAM) as probe:
 probe.setsockopt(socket.SOL_SOCKET,socket.SO_REUSEADDR,1)
 probe.bind(('::1',25586))
source=LINE['neoforge_server']
context=Path('/home/vox/.local/share/lss-rig-prism-auth')
clientmods=LINE['client_mods'][a.loader]
inputs=[(f,'server/libraries/'+str(f.relative_to(source/'libraries'))) for f in (source/'libraries').rglob('*') if f.is_file()]
if a.server_family=='support':
 paper=LINE['paper_server']
 plugin=LINE['dh_support_plugin']
 for file,digest in [(paper/'paper.jar',LINE['paper_sha256']),(plugin,LINE['dh_support_sha256'])]:
  if hashlib.sha256(file.read_bytes()).hexdigest()!=digest:raise ValueError('Pinned Paper/Support artifact changed')
 inputs=[(paper/'paper.jar','server/paper.jar'),(plugin,'server/plugins/'+plugin.name)]
 for directory in ['cache','libraries','versions']:inputs.extend((file,'server/'+directory+'/'+str(file.relative_to(paper/directory))) for file in (paper/directory).rglob('*') if file.is_file())
if a.slow_config:inputs.append((a.fixture,'server/mods/'+a.fixture.name))
if a.server_family=='official':inputs.append((a.dh,'server/mods/'+a.dh.name))
inputs+=[(a.artifact,'instances/dh-neoforge/minecraft/mods/lod-server-support-neoforge.jar'),(a.fixture,'instances/dh-neoforge/minecraft/mods/'+a.fixture.name)]
if a.expect=='conflict' or a.scenario=='load' and a.arm=='official':inputs.append((a.dh,'instances/dh-neoforge/minecraft/mods/'+a.dh.name))
if a.scenario=='content' or a.scenario=='boundary' and a.terrain_source=='native':inputs.append((a.artifact,'server/mods/lod-server-support-neoforge.jar'))
for pattern,digest in LINE['client_mod_pins'][a.loader]:
 matches=list(clientmods.glob(pattern))
 if len(matches)!=1:raise ValueError('Expected exactly one tested dependency: '+pattern)
 if hashlib.sha256(matches[0].read_bytes()).hexdigest()!=digest:raise ValueError('Pinned client dependency changed: '+matches[0].name)
 inputs.append((matches[0],'instances/dh-neoforge/minecraft/mods/'+matches[0].name))
a.base.mkdir(parents=True,exist_ok=True)
pre=storage_guard.preflight({'artifacts':[]},{'cache':{},'stage_files':[{'source':str(f)} for f,t in inputs]},a.base)
run=Path(tempfile.mkdtemp(prefix='attempt-',dir=a.base));(run/'evidence').mkdir();(run/'captures').mkdir();(run/'client').mkdir()
(run/'evidence/storage-preflight.json').write_text(json.dumps(pre,indent=2))
for f,t in inputs:
 dest=run/t;dest.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(f,dest)
profile={'components':LINE['prism_components'][a.loader]}
(runtime:={"scenario":a.scenario,"slow_config":a.slow_config,"moving_border":a.moving_border,"terrain_source":a.terrain_source,"boundary_expect":a.boundary_expect,"loader":a.loader,"server_family":a.server_family,"profile":a.profile,"expectation":a.expect,"builder":a.builder,"launcher_inputs":launcher_inputs(context,profile),"source_inputs":[{'target':t,'sha256':hashlib.file_digest(f.open('rb'),'sha256').hexdigest()} for f,t in inputs]})
if a.scenario=='content':
 hashes={item['target']:item['sha256'] for item in runtime['source_inputs']}
 if hashes['server/mods/lod-server-support-neoforge.jar']!=hashes['instances/dh-neoforge/minecraft/mods/lod-server-support-neoforge.jar']:raise ValueError('Client/server candidate hash mismatch')
(run/'runtime.json').write_text(json.dumps(runtime,indent=2))
instance=run/'instances/dh-neoforge';game=instance/'minecraft';(game/'config').mkdir(parents=True,exist_ok=True)
(instance/'mmc-pack.json').write_text(json.dumps({'formatVersion':1,'components':profile['components']}))
(instance/'instance.cfg').write_text('[General]\nInstanceType=OneSix\nname=DH NeoForge private verification\nOverrideJavaLocation=true\nJavaPath='+LINE['java']+'\nOverrideJavaArgs=true\nJvmArgs=-XX:ActiveProcessorCount=4 -Dlss.rig.runId='+run.name+' -Dlss.rig.scenario='+a.scenario+' -Dlss.rig.expect='+a.expect+' -Dlss.rig.profile='+{'dh_support_15':'SUPPORT_15','official_15':'OFFICIAL_15','official_16':'OFFICIAL_16'}[a.profile]+' -Dlss.rig.boundaryExpect='+a.boundary_expect+' -Dlss.rig.movingBorder='+str(a.moving_border).lower()+' -Dlss.rig.slowConfig='+str(a.slow_config).lower()+' -Dlss.rig.nativePreferred='+str(a.scenario=='boundary' and a.terrain_source=='native').lower()+' -Dlss.rig.initialEndpoint=[::1]:'+('25587' if a.scenario=='load' else '25586')+' -Dlss.rig.arm='+a.arm+' -Dlss.rig.coldSeconds='+str(a.cold_seconds)+' -Dlss.rig.warmSeconds='+str(a.warm_seconds)+(' -Xlog:gc:file='+str(run/'instances/dh-neoforge/minecraft/gc.log')+':uptime' if a.scenario=='movement' else '')+' -Dlss.rig.captureDir='+str(run/'captures')+'\nOverrideMemory=true\nMinMemAlloc=512\nMaxMemAlloc=3072\nOverrideWindow=true\nMinecraftWinWidth='+str(1280 if a.scenario in ['menu','updates'] else 960)+'\nMinecraftWinHeight='+str(720 if a.scenario in ['menu','updates'] else 540)+'\nLaunchMaximized=false\nOverrideCommands=true\nPreLaunchCommand=\nPostExitCommand=\nWrapperCommand=\n')
(game/'options.txt').write_text('maxFps:30\nrenderDistance:'+str(12 if a.scenario=='boundary' else 4)+'\nsimulationDistance:5\npauseOnLostFocus:false\nenableVsync:false\nguiScale:'+str(0 if a.scenario in ['menu','updates'] else 2)+'\nonboardAccessibility:false\n')
client_settings=render_files({**({} if a.source_unset else {'lod.source':'lss' if a.scenario=='content' or a.scenario=='boundary' and a.terrain_source=='native' and not a.allow_dh or a.arm=='dh-off' else 'distant_horizons'}),'lod.receive':True,**({} if a.source_unset else {'lod.dh.sync_on_load':True,'lod.dh.real_time_updates':a.scenario in ['menu','updates']}),'lod.distance_chunks':a.load_range if a.scenario=='load' else 0 if a.arm=='dh-range128' else 40 if a.scenario=='absence' else 12 if a.scenario in ['content','service','race','rejoin','updates','boundary'] else 32,'integrations.map_ingestion.enabled':False},side='client')
(game/'config/lss-client-config.yaml').write_text(client_settings['main'])
if a.scenario=='load' and a.arm=='official':
 # Same LOD radius as the LSS arm; everything else stays at DH's shipped defaults.
 (game/'config/DistantHorizons.toml').write_text('[client.advanced.graphics.quality]\nlodChunkRenderDistanceRadius = '+str(a.load_range)+'\n')
if client_settings['sidecar'] is not None:(game/'config/lss-client-experimental.yaml').write_text(client_settings['sidecar'])
if a.source_unset:
 # The shipped default must be what runs: no source key in main and no sidecar at all.
 written=[l for l in client_settings['main'].splitlines() if l.strip().startswith('source:')]
 if written or client_settings['sidecar'] is not None:raise ValueError('--source-unset wrote an experimental setting: '+repr(written))
 (run/'client-source-setting.json').write_text(json.dumps({'lod_source_written':False,'sidecar_written':False},indent=2)+'\n')
(run/'server/eula.txt').write_text('eula=true\n')
if a.scenario=='absence':
 (run/'server/config').mkdir(exist_ok=True)
 (run/'server/config/DistantHorizons.toml').write_text('_version = 5\n[server]\nmaxGenerationRequestDistance = 8\n')
if a.server_family=='support':
 with zipfile.ZipFile(plugin) as archive:default=archive.read('config.yml').decode()
 start=default.index('worlds:\n');end=default.index('# List of events',start)
 config=default[:start]+'worlds: {}\n\n'+default[end:]
 config=config.replace('render_distance: 1024','render_distance: 12').replace('scheduler_threads: 4','scheduler_threads: 2').replace('builder_type: FullBuilder','builder_type: '+a.builder)
 config+='\ncheck_for_updates: false\n'
 (run/'server/plugins/DHSupport').mkdir(exist_ok=True)
 (run/'server/plugins/DHSupport/config.yml').write_text(config)
if a.scenario in ['content','race','rejoin','updates','boundary']:
 # The line's vanilla overworld type, extended both below and above the standard range.
 pack=run/'server/world/datapacks/lss-content-fixture'
 (pack/'data/lssfixture/dimension_type').mkdir(parents=True)
 (pack/'data/lssfixture/dimension').mkdir(parents=True)
 (pack/'pack.mcmeta').write_text(json.dumps({'pack':{**LINE['datapack_format'],'description':'Private DH height/removal fixture'}}))
 dimension_type={**LINE['overworld_dimension_type'](),'height':512,'logical_height':512,'min_y':-128}
 (pack/'data/lssfixture/dimension_type/tall.json').write_text(json.dumps(dimension_type))
 (pack/'data/lssfixture/dimension/tall.json').write_text(json.dumps({'type':'lssfixture:tall','generator':{'type':'minecraft:flat','settings':{'biome':'minecraft:plains','lakes':False,'features':False,'layers':[{'block':'minecraft:bedrock','height':1},{'block':'minecraft:stone','height':7}], 'structure_overrides':[]}}}))
 (run/'server/config').mkdir(exist_ok=True)
 # Only the updates scenario subscribes to real-time pushes. Other scenarios
 # require conditional load checks. Generation never competes with this fixture.
 (run/'server/config/DistantHorizons.toml').write_text('_version = 5\n[server]\nenableRealTimeUpdates = '+str(a.scenario=='updates').lower()+'\nmaxGenerationRequestDistance = 0\n[common.worldGenerator]\ngeneratorPlan = "DISABLED"\n')
(run/'server/server.properties').write_text('server-ip=::1\nserver-port=25586\nonline-mode=false\nwhite-list=false\nlevel-seed=8675309\ngamemode=creative\nview-distance='+str(8 if a.scenario=='boundary' else 4)+'\nsimulation-distance=4\nspawn-protection=0\nallow-flight=true\nenforce-secure-profile=false\nmax-tick-time=120000\n')
print(run)
