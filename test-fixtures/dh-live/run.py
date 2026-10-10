#!/usr/bin/env python3
"""Own the private display, server and launcher; record evidence, then stop every child."""
import argparse,json,os,re,signal,subprocess,sys,time
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'tools/rig'))
import storage_guard
sys.path.insert(0,str(Path(__file__).resolve().parent))
from line import LINE
from rig import identity
p=argparse.ArgumentParser();p.add_argument('run',type=Path);a=p.parse_args();run=a.run.resolve()
pre=json.loads((run/'evidence/storage-preflight.json').read_text())
if pre['estimate'].get('bounded_profile') in ('dh-p4-2g-v1','dh-p4-2g-v2'):
 import bounded_storage
 bounded_storage.install()
storage_guard.preflight({'artifacts':[]},{},run,pre['estimate'],phase='run');monitor=storage_guard.Monitor(run,pre)
children=[];server=None
resource_samples=[]
def sample_server():
 if server is None:return
 try:
  proc=Path('/proc')/str(server.pid)
  stat=(proc/'stat').read_text().rsplit(')',1)[1].split()
  status=dict(line.split(':',1) for line in (proc/'status').read_text().splitlines() if ':' in line)
  resource_samples.append({'wall_monotonic':time.monotonic(),'epoch_ms':int(time.time()*1000),'cpu_seconds':(int(stat[11])+int(stat[12]))/os.sysconf('SC_CLK_TCK'),'rss_kib':int(status['VmRSS'].split()[0]),'peak_rss_kib':int(status['VmHWM'].split()[0])})
 except (OSError,KeyError,ValueError):pass
(run/'evidence/supervisor-owner.json').write_text(json.dumps(identity(os.getpid())))
proxy_bytes={}  # epoch second -> [server_to_client, client_to_server]; load scenario only
def start_counting_proxy():
 import socket,threading
 listener=socket.socket(socket.AF_INET6,socket.SOCK_STREAM);listener.setsockopt(socket.SOL_SOCKET,socket.SO_REUSEADDR,1)
 listener.bind(('::1',25587));listener.listen(4);lock=threading.Lock()
 def pump(src,dst,index):
  try:
   while True:
    data=src.recv(65536)
    if not data:break
    with lock:proxy_bytes.setdefault(int(time.time()),[0,0])[index]+=len(data)
    dst.sendall(data)
  except OSError:pass
  finally:
   for sock in (src,dst):
    try:sock.shutdown(socket.SHUT_RDWR)
    except OSError:pass
 def accept():
  while True:
   try:client_sock,_=listener.accept()
   except OSError:return
   upstream=socket.create_connection(('::1',25586))
   threading.Thread(target=pump,args=(upstream,client_sock,0),daemon=True).start()
   threading.Thread(target=pump,args=(client_sock,upstream,1),daemon=True).start()
 threading.Thread(target=accept,daemon=True).start();return listener
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
 proxy=start_counting_proxy() if runtime['scenario']=='load' else None
 args=[LINE['java'],'-Xms512M','-Xmx3G','-XX:ActiveProcessorCount=4']
 if runtime.get('slow_config'):args+=['-Dlss.rig.slowConfig=true']
 args+=['-jar','paper.jar','--nogui'] if runtime.get('server_family')=='support' else [LINE['neoforge_server_args'],'nogui']
 server=subprocess.Popen(args,cwd=run/'server',stdin=subprocess.PIPE,stdout=serverlog,stderr=subprocess.STDOUT,text=True);children.append(server)
 (run/'evidence/server-owner.json').write_text(json.dumps(identity(server.pid)))
 deadline=time.monotonic()+180
 while 'Done (' not in (run/'server.log').read_text(errors='replace'):
  if server.poll() is not None:raise RuntimeError('Server exited before ready')
  if time.monotonic()>deadline:raise TimeoutError('Server readiness')
  monitor.check();time.sleep(.5)
 server.stdin.write(LINE['mob_spawning_off']+'\ntime set noon\nweather clear\n');server.stdin.flush()
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
 if json.loads((run/'runtime.json').read_text())['scenario'] in ['content','race','rejoin','updates']:
  seed_start=len((run/'server.log').read_text(errors='replace'))
  server.stdin.write('execute in lssfixture:tall run forceload add -192 0 -129 63\nexecute in lssfixture:tall run fill -192 350 0 -129 351 63 minecraft:red_concrete\nexecute in lssfixture:tall run fill -192 -118 0 -129 -117 63 minecraft:blue_concrete\nexecute in lssfixture:tall run fill -192 340 0 -129 340 63 minecraft:gold_block\nsave-all flush\n');server.stdin.flush()
  deadline=time.monotonic()+60
  while True:
   seeded=(run/'server.log').read_text(errors='replace')[seed_start:]
   if seeded.count('Successfully filled 8192 block(s)')==2 and 'Successfully filled 4096 block(s)' in seeded and 'Marked 16 chunks' in seeded and 'Saved the game' in seeded:break
   if server.poll() is not None or time.monotonic()>deadline:raise TimeoutError('Custom-height terrain seeding')
   monitor.check();time.sleep(.5)
 clientlog=(run/'client.log').open('w')
 client=subprocess.Popen([sys.executable,str(ROOT/'tools/rig/launch_prism.py'),'--context','/home/vox/.local/share/lss-rig-prism-auth','--run',str(run),'--java',LINE['java'],'--prism','/home/vox/.local/bin/prismlauncher','--instance','dh-neoforge'],cwd=run/'client',env=env,stdout=clientlog,stderr=subprocess.STDOUT,start_new_session=True);children.append(client)
 (run/'evidence/client-owner.json').write_text(json.dumps(identity(client.pid)))
 content=json.loads((run/'runtime.json').read_text())['scenario']=='content'
 race=json.loads((run/'runtime.json').read_text())['scenario'] in ['race','rejoin','updates','boundary']
 rejoin=runtime['scenario']=='rejoin';restarts=0
 deadline=time.monotonic()+(1860 if content or runtime['scenario'] in ['load','movement'] else 1000 if rejoin else 660);positioned=False;handled=set();result=run/'instances/dh-neoforge/minecraft/dh-result.json'
 while not result.exists():
  game=run/'instances/dh-neoforge/minecraft'
  latest=game/'logs/latest.log'
  if list((game/'crash-reports').glob('*.txt')):raise RuntimeError('Private Minecraft crash report; inspect owned run')
  if latest.is_file() and 'Incompatible mods found!' in latest.read_text(errors='replace'):
   raise RuntimeError('Private Minecraft loader rejected the staged mods; inspect owned log')
  if client.poll() is not None:
   if rejoin and restarts==0 and (game/'dh-restart.json').is_file():
    restarts+=1
    (run/'evidence/client-owner-first.json').write_text((run/'evidence/client-owner.json').read_text())
    clientlog=(run/'client-restarted.log').open('w')
    client=subprocess.Popen([sys.executable,str(ROOT/'tools/rig/launch_prism.py'),'--context','/home/vox/.local/share/lss-rig-prism-auth','--run',str(run),'--java',LINE['java'],'--prism','/home/vox/.local/bin/prismlauncher','--instance','dh-neoforge'],cwd=run/'client',env=env,stdout=clientlog,stderr=subprocess.STDOUT,start_new_session=True);children.append(client)
    (run/'evidence/client-owner.json').write_text(json.dumps(identity(client.pid)))
    continue
   raise RuntimeError('Private client exited before result')
  if server.poll() is not None:raise RuntimeError('Server died during client verification')
  if time.monotonic()>deadline:raise TimeoutError('Client verification')
  if not positioned:
   log=(run/'server.log').read_text(errors='replace');joined=re.findall(r'([A-Za-z0-9_]{1,16})\[.*?\] logged in with entity id',log)
   if joined:
    player=joined[-1];server.stdin.write('op '+player+'\n'+('' if content or race else 'tp '+player+' 0 100 0 90 15\n'));server.stdin.flush();positioned=True
  command=run/'instances/dh-neoforge/minecraft/dh-server-command.json'
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
finally:
 sample_server()
 (run/'evidence/server-resources.json').write_text(json.dumps({'includes_boot_and_generation':True,'samples':resource_samples},indent=2)+'\n')
 if proxy_bytes:(run/'evidence/proxy-bytes.json').write_text(json.dumps({'unit':'bytes per epoch second; [server_to_client, client_to_server]','seconds':{str(k):v for k,v in sorted(proxy_bytes.items())}})+'\n')
 if server is not None and server.poll() is None:
  try:server.stdin.write('stop\n');server.stdin.flush();server.wait(timeout=40)
  except (OSError,subprocess.TimeoutExpired):server.terminate()
 if 'client' in locals():
  try:os.killpg(client.pid,signal.SIGTERM)
  except ProcessLookupError:pass
 for child in reversed(children):
  if child.poll() is None:
   child.terminate()
   try:child.wait(timeout=10)
   except subprocess.TimeoutExpired:child.kill();child.wait(timeout=10)
