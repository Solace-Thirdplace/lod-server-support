#!/usr/bin/env python3
"""Own the private display, server and launcher; record evidence, then stop every child."""
import shutil
import argparse,json,os,re,signal,subprocess,sys,time
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'tools/rig'))
import storage_guard
from bounded_storage import install
install()
from rig import identity
p=argparse.ArgumentParser();p.add_argument('run',type=Path);a=p.parse_args();run=a.run.resolve()
pre=json.loads((run/'evidence/storage-preflight.json').read_text());storage_guard.preflight({'artifacts':[]},{},run,pre['estimate'],phase='run');monitor=storage_guard.Monitor(run,pre)
children=[];server=None
resource_samples=[]
def sample_server():
 if server is None:return
 try:
  proc=Path('/proc')/str(server.pid)
  stat=(proc/'stat').read_text().rsplit(')',1)[1].split()
  status=dict(line.split(':',1) for line in (proc/'status').read_text().splitlines() if ':' in line)
  resource_samples.append({'wall_monotonic':time.monotonic(),'cpu_seconds':(int(stat[11])+int(stat[12]))/os.sysconf('SC_CLK_TCK'),'rss_kib':int(status['VmRSS'].split()[0]),'peak_rss_kib':int(status['VmHWM'].split()[0])})
 except (OSError,KeyError,ValueError):pass
(run/'evidence/supervisor-owner.json').write_text(json.dumps(identity(os.getpid())))
def interrupted(signum,frame):raise KeyboardInterrupt()
signal.signal(signal.SIGTERM,interrupted)
try:
 env=os.environ.copy()
 for key in ['DISPLAY','WAYLAND_DISPLAY','XAUTHORITY','MESA_D3D12_DEFAULT_ADAPTER_NAME','DRI_PRIME']:env.pop(key,None)
 auth=run/'Xauthority';auth.touch(mode=0o600);display=':'+str(1000+int.from_bytes(os.urandom(2),'big'))
 subprocess.run(['xauth','-f',str(auth),'add',display,'.',os.urandom(16).hex()],check=True,capture_output=True)
 xvfb=subprocess.Popen(['Xvfb',display,'-screen','0','1280x720x24','-nolisten','tcp','-auth',str(auth)],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL);children.append(xvfb)
 env.update(DISPLAY=display,XAUTHORITY=str(auth),ALSOFT_DRIVERS='null',GALLIUM_DRIVER='llvmpipe',MESA_GL_VERSION_OVERRIDE='4.6',MESA_GLSL_VERSION_OVERRIDE='460')
 time.sleep(.5)
 if xvfb.poll() is not None:raise RuntimeError('Owned Xvfb exited; refusing display adoption')
 subprocess.run(['xprop','-root'],env=env,check=True,capture_output=True)
 (run/'evidence/gpu-control.txt').write_text(subprocess.check_output(['glxinfo','-B'],env=env,text=True))
 (run/'evidence/display.json').write_text(json.dumps({'display':display,'xvfb':identity(xvfb.pid)}))
 serverlog=(run/'server.log').open('w')
 runtime=json.loads((run/'runtime.json').read_text())
 if runtime.get('benchmark_arm'):
  from fixed_area_metrics import terrain
  (run/'evidence/benchmark-native-before.json').write_text(json.dumps(terrain(run/'server/world'),indent=2)+'\n')
 args=['/usr/lib/jvm/java-21-openjdk-amd64/bin/java','-Xms512M','-Xmx3G','-XX:ActiveProcessorCount=4']
 if runtime.get('benchmark_arm'):args+=['-Dlss.p4.serverMetricsPath='+str(run/'evidence/benchmark-server.json')]
 if runtime.get('slow_config'):args+=['-Dlss.rig.slowConfig=true']
 args+=['-jar','paper.jar','--nogui'] if runtime.get('server_family')=='support' else ['@libraries/net/neoforged/neoforge/21.1.248/unix_args.txt','nogui']
 server=subprocess.Popen(args,cwd=run/'server',stdin=subprocess.PIPE,stdout=serverlog,stderr=subprocess.STDOUT,text=True);children.append(server)
 (run/'evidence/server-owner.json').write_text(json.dumps(identity(server.pid)))
 deadline=time.monotonic()+180
 while 'Done (' not in (run/'server.log').read_text(errors='replace'):
  if server.poll() is not None:raise RuntimeError('Server exited before ready')
  if time.monotonic()>deadline:raise TimeoutError('Server readiness')
  monitor.check();time.sleep(.5)
 server.stdin.write('gamerule doMobSpawning false\ngamerule doDaylightCycle false\ngamerule doWeatherCycle false\nkill @e[type=!minecraft:player]\ntime set noon\nweather clear\n');server.stdin.flush()
 if runtime.get('expectation')=='ocean':
  # Two owned tiles outside vanilla view: 24-deep and 5-deep water, at section seams.
  marker=len((run/'server.log').read_text(errors='replace'))
  server.stdin.write('forceload add -192 0 -129 127\n');server.stdin.flush();time.sleep(8)
  commands=[]
  for z,floor in [(0,255),(64,274)]:
   for y in range(255,320,8):commands.append(f'fill -192 {y} {z} -129 {min(y+7,319)} {z+63} minecraft:air')
   commands.append(f'fill -192 {floor-1} {z} -129 {floor-1} {z+63} minecraft:stone')
   commands.append(f'fill -192 {floor} {z} -129 {floor} {z+63} minecraft:sand')
   for y in range(floor+1,280,8):commands.append(f'fill -192 {y} {z} -129 {min(y+7,279)} {z+63} minecraft:water')
   for x in [-192,-129]:commands.append(f'fill {x} {floor+1} {z} {x} 279 {z+63} minecraft:blue_concrete')
   for edge in [z,z+63]:commands.append(f'fill -192 {floor+1} {edge} -129 279 {edge} minecraft:blue_concrete')
  commands+=['save-all flush','say LSS_PRIVATE_OCEAN_SEEDED']
  server.stdin.write('\n'.join(commands)+'\n');server.stdin.flush()
  deadline=time.monotonic()+90
  while True:
   seeded=(run/'server.log').read_text(errors='replace')[marker:]
   if 'LSS_PRIVATE_OCEAN_SEEDED' in seeded and 'Saved the game' in seeded and seeded.count('Successfully filled 4096 block(s)')==4:break
   if server.poll() is not None or time.monotonic()>deadline:raise TimeoutError('Private ocean seeding')
   monitor.check();time.sleep(.5)
 if json.loads((run/'runtime.json').read_text())['scenario'] in ['content','race','rejoin','updates','p4']:
  seed_start=len((run/'server.log').read_text(errors='replace'))
  if runtime.get('p4_case')=='content':
   dimension=runtime['p4_dimension']; support=dimension=='minecraft:overworld'; low=-64 if support else -128; high=319 if support else 383
   commands=['gamerule doMobSpawning false','gamerule randomTickSpeed 0','gamerule commandModificationBlockLimit 5000000',f'execute in {dimension} run forceload add -192 0 -129 63']
   if support:
    for lower,upper,block in [(-64,191,'stone'),(192,223,'red_concrete'),(224,255,'sea_lantern'),(256,271,'glass'),(272,287,'oak_leaves'),(288,295,'stone'),(296,311,'water'),(312,319,'air')]:commands.append(f'execute in {dimension} run fill -192 {lower} 0 -129 {upper} 63 minecraft:{block}')
   for x,z,biome in [(-192,0,'desert'),(-160,0,'swamp'),(-192,32,'snowy_taiga'),(-160,32,'plains')]:commands.append(f'execute in {dimension} run fillbiome {x} {low} {z} {x+31} {high} {z+31} minecraft:{biome}')
   plant_low=300 if support else 360; plant_top=310 if support else 382
   commands+=[f'execute in {dimension} run fill -184 {plant_low} 8 -184 {plant_top-1} 8 minecraft:kelp_plant',f'execute in {dimension} run setblock -184 {plant_top} 8 minecraft:kelp',f'execute in {dimension} run setblock -176 {plant_low} 16 minecraft:seagrass',
    f'execute in {dimension} run fill -192 {low+16} 0 -181 {low+19} 7 minecraft:air',
    f'execute in {dimension} run setblock -190 {low+16} 1 minecraft:torch',
    f'execute in {dimension} run fill -188 {low+16} 2 -185 {low+16} 5 minecraft:oak_stairs[facing=east,half=top,shape=straight,waterlogged=false]',
    f'execute in {dimension} run fill -184 {low+32} 8 -177 {low+32} 15 minecraft:smooth_stone_slab[type=top,waterlogged=false]',
    'save-all flush']
   server.stdin.write('\n'.join(commands)+'\n');server.stdin.flush();time.sleep(10)
  elif runtime.get('p4_case') in ['benchmark','cache-pressure','cache-rejoin-range','legacy-menu','configuration']:
   server.stdin.write('gamerule doMobSpawning false\ngamerule randomTickSpeed 0\nsave-all flush\n');server.stdin.flush();time.sleep(3)
  else:
   server.stdin.write('execute in lssfixture:tall run forceload add -192 0 -129 63\nexecute in lssfixture:tall run fill -192 350 0 -129 351 63 minecraft:red_concrete\nexecute in lssfixture:tall run fill -192 -118 0 -129 -117 63 minecraft:blue_concrete\nexecute in lssfixture:tall run fill -192 340 0 -129 340 63 minecraft:gold_block\nsave-all flush\n');server.stdin.flush()
  deadline=time.monotonic()+60
  while True:
   seeded=(run/'server.log').read_text(errors='replace')[seed_start:]
   if runtime.get('p4_case') and 'Saved the game' in seeded and (runtime.get('p4_case')!='content' or seeded.count(('6144' if runtime['server_family']=='support' else '8192')+' biome entry/entries set')==4 and ('Successfully filled 10 block(s)' if runtime['server_family']=='support' else 'Successfully filled 22 block(s)') in seeded and 'Marked 16 chunks' in seeded):break
   if not runtime.get('p4_case') and seeded.count('Successfully filled 8192 block(s)')==2 and 'Successfully filled 4096 block(s)' in seeded and 'Marked 16 chunks' in seeded and 'Saved the game' in seeded:break
   if server.poll() is not None or time.monotonic()>deadline:raise TimeoutError('Custom-height terrain seeding')
   monitor.check();time.sleep(.5)
 clientlog=(run/'client.log').open('w')
 windows=Path(runtime['windows_client']) if runtime.get('windows_client') else None
 if windows:
  game=windows/'minecraft'; spec=subprocess.check_output(['wslpath','-w',str(windows/'launch.json')],text=True).strip(); launcher=subprocess.check_output(['wslpath','-w',str(windows/'launch.ps1')],text=True).strip()
  client=subprocess.Popen(['/mnt/c/Windows/System32/WindowsPowerShell/v1.0/powershell.exe','-NoProfile','-NonInteractive','-ExecutionPolicy','Bypass','-File',launcher,'-Spec',spec],stdout=clientlog,stderr=subprocess.STDOUT,start_new_session=True)
 else:
  game=run/'instances/dh-neoforge/minecraft'
  client=subprocess.Popen([sys.executable,str(ROOT/'tools/rig/launch_prism.py'),'--context','/home/vox/.local/share/lss-rig-prism-auth','--run',str(run),'--java','/usr/lib/jvm/java-21-openjdk-amd64/bin/java','--prism','/home/vox/.local/bin/prismlauncher','--instance','dh-neoforge'],cwd=run/'client',env=env,stdout=clientlog,stderr=subprocess.STDOUT,start_new_session=True)
 children.append(client)
 (run/'evidence/client-owner.json').write_text(json.dumps(identity(client.pid)))
 content=runtime['scenario'] in ['content','p4']
 race=json.loads((run/'runtime.json').read_text())['scenario'] in ['race','rejoin','updates','boundary']
 rejoin=runtime['scenario']=='rejoin';restarts=0
 deadline=time.monotonic()+(3660 if runtime.get('p4_case')=='cache-rejoin-range' else 1860 if content else 1000 if rejoin else 660);positioned=False;handled=set();result=game/'dh-result.json'
 while not result.exists():
  latest=game/'logs/latest.log'
  if list((game/'crash-reports').glob('*.txt')):raise RuntimeError('Private Minecraft crash report; inspect owned run')
  if latest.is_file() and 'Incompatible mods found!' in latest.read_text(errors='replace'):
   raise RuntimeError('Private Minecraft loader rejected the staged mods; inspect owned log')
  if client.poll() is not None:
   if rejoin and restarts==0 and (game/'dh-restart.json').is_file():
    restarts+=1
    (run/'evidence/client-owner-first.json').write_text((run/'evidence/client-owner.json').read_text())
    clientlog=(run/'client-restarted.log').open('w')
    client=subprocess.Popen([sys.executable,str(ROOT/'tools/rig/launch_prism.py'),'--context','/home/vox/.local/share/lss-rig-prism-auth','--run',str(run),'--java','/usr/lib/jvm/java-21-openjdk-amd64/bin/java','--prism','/home/vox/.local/bin/prismlauncher','--instance','dh-neoforge'],cwd=run/'client',env=env,stdout=clientlog,stderr=subprocess.STDOUT,start_new_session=True);children.append(client)
    (run/'evidence/client-owner.json').write_text(json.dumps(identity(client.pid)))
    continue
   raise RuntimeError('Private client exited before result')
  if server.poll() is not None:raise RuntimeError('Server died during client verification')
  if time.monotonic()>deadline:raise TimeoutError('Client verification')
  if not positioned:
   log=(run/'server.log').read_text(errors='replace');joined=re.findall(r'([A-Za-z0-9_]{1,16})\[.*?\] logged in with entity id',log)
   if joined:
    player=joined[-1];commands=['op '+player]
    if runtime.get('p4_case')=='content':commands.append('execute in '+runtime['p4_dimension']+' run tp '+player+' 0 '+('340' if runtime['server_family']=='support' else '410')+' 0 90 15')
    elif runtime.get('p4_case'):commands.append('tp '+player+' 0 100 0 90 15')
    elif not(content or race):commands.append('tp '+player+' 0 100 0 90 15')
    server.stdin.write('\n'.join(commands)+'\n');server.stdin.flush();positioned=True
  command=game/'dh-server-command.json'
  if (content or race) and command.exists():
   change=json.loads(command.read_text())
   if change['id'] not in handled:
    # Commands come only from this run-owned fixture; never another server/world.
    marker=len((run/'server.log').read_text(errors='replace'))
    server.stdin.write('\n'.join(change['commands'])+'\n');server.stdin.flush()
    edit_deadline=time.monotonic()+60
    while 'Saved the game' not in (run/'server.log').read_text(errors='replace')[marker:]:
     if server.poll() is not None or time.monotonic()>edit_deadline:raise TimeoutError('Source-switch save')
     monitor.check();time.sleep(.5)
    time.sleep(3) # allow the official server's asynchronous save-to-LOD update
    ack=command.parent/'dh-server-ack.tmp';ack.write_text(json.dumps({'id':change['id'],'saved':True}));ack.replace(command.parent/'dh-server-ack.json')
    handled.add(change['id'])
  sample_server();monitor.check();time.sleep(1)
 print(result.read_text())
 if not json.loads(result.read_text())['pass']:raise RuntimeError('Live proof failed')
 client.wait(timeout=30)
 if windows:
  owner=json.loads((windows/'ownership.json').read_text(encoding='utf-8-sig'));assert not owner['timeout'] and owner['exit_code']==0 and owner['owned_top_level_windows']>0 and owner['input_desktop_before']==owner['input_desktop_after'];(run/'evidence/windows-owner.json').write_text(json.dumps(owner,indent=2)+'\n');shutil.copy2(windows/'ownership.resources.json',run/'evidence/windows-resources.json')
  for name in ['dh-result.json','dh-p4.json','dh-frame-metrics.json']:
   if (game/name).exists():shutil.copy2(game/name,run/'evidence'/('windows-'+name))
finally:
 sample_server()
 (run/'evidence/server-resources.json').write_text(json.dumps({'includes_boot_and_generation':True,'samples':resource_samples},indent=2)+'\n')
 if server is not None and server.poll() is None:
  try:server.stdin.write('stop\n');server.stdin.flush();server.wait(timeout=40)
  except (OSError,subprocess.TimeoutExpired):server.terminate()
 if 'windows' in locals() and windows and client.poll() is None:
  (windows/'ownership.stop').write_text('Owned run cleanup requested\n')
  try:client.wait(timeout=20)
  except subprocess.TimeoutExpired:pass
 if 'client' in locals():
  try:os.killpg(client.pid,signal.SIGTERM)
  except ProcessLookupError:pass
 for child in reversed(children):
  if child.poll() is None:
   child.terminate()
   try:child.wait(timeout=10)
   except subprocess.TimeoutExpired:child.kill();child.wait(timeout=10)
