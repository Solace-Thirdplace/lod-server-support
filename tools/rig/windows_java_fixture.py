#!/usr/bin/env python3
"""Stage cached exact NeoForge closure into an owned Windows root, offline subject only.
No accounts, hooks, personal instances, desktop input or dependency downloads.
"""
import argparse,json,hashlib,shutil,subprocess,zipfile,sys
from pathlib import Path
from storage_guard import preflight,capacity,RESERVE,GIB
sys.path.insert(0,str(Path(__file__).resolve().parents[2]/"test-fixtures/dh-p4"))
from bounded_storage import install
install()
ROOT=Path(__file__).resolve().parents[2]
ASSETS=Path('/home/vox/.local/share/lss-rig-prism-auth/assets')
WIN=Path('/mnt/c/Users/Ian/AppData/Roaming/PrismLauncher'); META=Path('/home/vox/.local/share/lss-rig-prism-auth/meta')
p=argparse.ArgumentParser();p.add_argument('--root',type=Path,required=True);p.add_argument('--game-source',type=Path,required=True);p.add_argument('--case',default='legacy-menu');p.add_argument('--backend',default='lss');p.add_argument('--endpoint',default='[::1]:25586');p.add_argument('--window-width',type=int,default=1280);p.add_argument('--window-height',type=int,default=720);a=p.parse_args()
if not 640<=a.window_width<=4096 or not 480<=a.window_height<=2160:raise ValueError('Private window size outside bounded fixture range')
if a.root.exists():raise SystemExit('New immutable Windows attempt required')
# Admission covers both host volumes and a conservative private closure copy budget.
storage=preflight({'artifacts':[]},{},a.root,{'schema_version':2,'copy_bytes':GIB,'growth_bytes':2*GIB,'copied_files':0,'bounded_profile':'dh-p4-2g-v1'})
if capacity(a.root.parent)['free_bytes']<RESERVE+3*GIB:raise RuntimeError('Windows stage requires full physical headroom')
a.root.mkdir(parents=True);(a.root/'storage-preflight.json').write_text(json.dumps(storage,indent=2)+'\n');game=a.root/'minecraft';game.mkdir();(game/'config').mkdir();libs=a.root/'libraries';libs.mkdir()
win=lambda p:subprocess.check_output(['wslpath','-w',str(p.resolve())],text=True).strip()
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
inputs={}
def copy(src,dst,expected=None):
 if not src.is_file():raise RuntimeError('Missing cached dependency '+str(src))
 if expected and hashlib.sha1(src.read_bytes()).hexdigest()!=expected:raise RuntimeError('Cached SHA1 mismatch '+str(src))
 dst.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(src,dst);inputs[str(dst.relative_to(a.root))]={'sha256':sha(dst),'source':str(src)};return dst
for n in ['org.lwjgl3/3.3.3.json','net.minecraft/1.21.1.json','net.neoforged/21.1.248.json']:copy(META/n,a.root/'metadata'/n)
def allowed(item):
 rules=item.get('rules');result=not rules
 for rule in rules or []:
  os=rule.get('os',{});match=os.get('name','windows')=='windows' and os.get('arch','x86_64') in ['x86_64','amd64']
  if rule.get('features'):match=False
  if match:result=rule['action']=='allow'
 return result
def rel(item):
 if item.get('downloads',{}).get('artifact',{}).get('path'):return Path(item['downloads']['artifact']['path'])
 group,artifact,version,*classifier=item['name'].split(':');ext='jar'
 if '@' in version:version,ext=version.split('@')
 return Path(group.replace('.','/'))/artifact/version/(artifact+'-'+version+('-'+classifier[0] if classifier else '')+'.'+ext)
items={};maven={};mc=None;neo=None
for n in ['org.lwjgl3/3.3.3.json','net.minecraft/1.21.1.json','net.neoforged/21.1.248.json']:
 j=json.loads((META/n).read_text())
 for item in j.get('libraries',[]):
  if allowed(item):
   name=item['name'].split(':');items[(name[0],name[1],tuple(name[3:]))]=item
 for item in j.get('mavenFiles',[]):maven[str(rel(item))]=item
 if j.get('uid')=='net.minecraft':mc=j
 if j.get('uid')=='net.neoforged':neo=j
classpath=[]
for item in items.values():
 dest=copy(WIN/'libraries'/rel(item),libs/rel(item),item.get('downloads',{}).get('artifact',{}).get('sha1'));classpath.append(dest)
for item in maven.values():
 dest=libs/rel(item)
 if not dest.exists():copy(WIN/'libraries'/rel(item),dest,item.get('downloads',{}).get('artifact',{}).get('sha1'))
main=copy(WIN/'libraries'/rel(mc['mainJar']),libs/rel(mc['mainJar']),mc['mainJar']['downloads']['artifact']['sha1']);classpath.append(main)
bootstrap=copy(Path('/mnt/c/Users/Ian/AppData/Local/Programs/PrismLauncher/jars/NewLaunch.jar'),a.root/'NewLaunch.jar');classpath.insert(0,bootstrap)
for d in ['mods','config']:
 for f in (a.game_source/d).glob('*'):
  if f.is_file():copy(f,game/d/f.name)
copy(a.game_source/'options.txt',game/'options.txt');options=(game/'options.txt').read_text();options=options.replace('maxFps:30','maxFps:120').replace('guiScale:2','guiScale:0');(game/'options.txt').write_text(options)
# No credentials are consumed. The fixture server is explicitly offline and accepts this synthetic subject.
values={'auth_player_name':'LSSP4','version_name':'1.21.1','game_directory':win(game),'assets_root':win(ASSETS),'assets_index_name':mc['assetIndex']['id'],'auth_uuid':'c966098a7fa13a4bad102b3f2b25ca6a','auth_access_token':'0','user_type':'legacy','version_type':'owned-fixture'}
raw=neo['minecraftArguments'];argv=raw.split()
for k,v in values.items():argv=[x.replace('${'+k+'}',v) for x in argv]
protocol=['launcher standard','launcherBrand PrismLauncher','launcherVersion 11.1','instanceName LSS owned P4','mainClass '+neo['mainClass'],'windowParams '+str(a.window_width)+'x'+str(a.window_height),*['param '+v for v in argv],'launch']
(a.root/'protocol.txt').write_text('\n'.join(protocol)+'\n');classes=a.root/'bootstrap-classes';classes.mkdir();copy(ROOT/'tools/rig/OwnedJavaBootstrap.java',a.root/'OwnedJavaBootstrap.java')
subprocess.run(['/usr/lib/jvm/java-21-openjdk-amd64/bin/javac','--release','21','-d',str(classes),str(a.root/'OwnedJavaBootstrap.java')],check=True);classpath.insert(0,classes)
inputs['bootstrap-classes/OwnedJavaBootstrap.class']={'sha256':sha(classes/'OwnedJavaBootstrap.class'),'source':'compiled from receipt-bound OwnedJavaBootstrap.java with Java21'}
asset_inputs={}
index=ASSETS/'indexes'/(mc['assetIndex']['id']+'.json')
if hashlib.sha1(index.read_bytes()).hexdigest()!=mc['assetIndex']['sha1']:raise RuntimeError('Cached asset index SHA1 mismatch')
asset_inputs[str(index)]={'sha256':sha(index)}
for item in json.loads(index.read_text())['objects'].values():
 f=ASSETS/'objects'/item['hash'][:2]/item['hash']
 if hashlib.sha1(f.read_bytes()).hexdigest()!=item['hash']:raise RuntimeError('Cached asset object SHA1 mismatch')
 asset_inputs[str(f)]={'sha256':sha(f)}
java=WIN/'java/java-runtime-delta/bin/java.exe';copy(ROOT/'tools/rig/windows_private_desktop.ps1',a.root/'launch.ps1')
args=['-Xms512M','-Xmx3G','-XX:ActiveProcessorCount=4','-Dforgewrapper.librariesDir='+win(libs),'-Djava.io.tmpdir='+win(a.root/'tmp'),'-Dlss.p4.windowsNative=true','-Dlss.p4.case='+a.case,'-Dlss.p4.backend='+a.backend,'-Dlss.rig.scenario=p4','-Dlss.rig.initialEndpoint='+a.endpoint,'-Dlss.rig.profile=OFFICIAL_16','-Dlss.rig.captureDir='+win(a.root/'captures'),'-cp',';'.join(map(win,classpath)),'OwnedJavaBootstrap',win(a.root/'protocol.txt'),win(a.root/'client.log')]
(a.root/'tmp').mkdir();(a.root/'captures').mkdir()
(a.root/'java.args').write_text('\n'.join('"'+x.replace('\\','\\\\').replace('"','\\"')+'"' for x in args)+'\n')
(a.root/'launch.json').write_text(json.dumps({'application':win(java),'command_line':'"'+win(java)+'" "@'+win(a.root/'java.args')+'"','working_directory':win(game),'evidence':win(a.root/'ownership'),'deadline_seconds':900},indent=2)+'\n')
(a.root/'inputs.json').write_text(json.dumps({'inputs':inputs,'shared_readonly_assets':asset_inputs,'java_sha256':sha(java),'subject':'synthetic LSSP4 on owned offline local server','personal_accounts_copied':False,'case':a.case,'backend':a.backend,'generated':{str(f.relative_to(a.root)):sha(f) for f in [a.root/'java.args',a.root/'protocol.txt',a.root/'launch.json',game/'options.txt']}},indent=2)+'\n');print(a.root)
