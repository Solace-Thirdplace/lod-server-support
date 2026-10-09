#!/usr/bin/env python3
"""Overlay a separately compiled P4 controller on the frozen test-only observer."""
import argparse,json,subprocess,zipfile,hashlib,shutil
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2];P4=Path(__file__).resolve().parent
p=argparse.ArgumentParser();p.add_argument('--build',type=Path,required=True);p.add_argument('--artifact',type=Path,required=True);p.add_argument('--base-fixture',type=Path,required=True);p.add_argument('--output',type=Path,required=True);a=p.parse_args()
classes=a.output.parent/'p4-classes';
if classes.exists():shutil.rmtree(classes)
classes.mkdir(parents=True)
cp=[a.artifact,a.base_fixture,*map(Path,(a.build/'neoforge/build/dh-fixture-classpath.txt').read_text().strip().split(':'))]
for directory in [Path('/home/vox/projects/lss-multi-test/servers/neoforge-1.21.1/libraries'),Path('/home/vox/.local/share/lss-rig-prism-auth/libraries')]:cp.extend(p for p in directory.rglob('*.jar') if zipfile.is_zipfile(p))
args=['-proc:none','--release','21','-classpath',':'.join(map(str,cp)),'-d',str(classes),*map(str,(P4/'src').rglob('*.java'))];argfile=a.output.parent/'p4-javac.args';argfile.write_text('\n'.join('"'+v.replace('\\','\\\\').replace('"','\\"')+'"' for v in args))
subprocess.run(['/usr/lib/jvm/java-21-openjdk-amd64/bin/javac','@'+str(argfile)],check=True)
with zipfile.ZipFile(a.base_fixture) as original,zipfile.ZipFile(a.output,'w',zipfile.ZIP_DEFLATED) as jar:
 for name in original.namelist():
  if name=='lss-dh-fixture.mixins.json':
   data=json.loads(original.read(name));data['client'].remove('TickMixin');data['client'].remove('ReceiveMixin');jar.writestr(name,json.dumps(data))
  elif name=='META-INF/neoforge.mods.toml':jar.writestr(name,original.read(name).decode()+'\n[[mixins]]\nconfig="lss-dh-p4.mixins.json"\n')
  else:jar.writestr(name,original.read(name))
 for file in classes.rglob('*.class'):jar.write(file,str(file.relative_to(classes)))
 jar.writestr('lss-dh-p4.mixins.json',json.dumps({'required':True,'package':'dev.vox.lssfixture.p4.mixin','compatibilityLevel':'JAVA_21','client':['ClientTick','SourceCapture','MouseGuard','Frames','BenchmarkArea','RawCapture','VoxyDraw','DecodedSource'],'injectors':{'defaultRequire':1}}))
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
receipt={'production_artifact_sha256':sha(a.artifact),'base_fixture_sha256':sha(a.base_fixture),'fixture_sha256':sha(a.output),'build':str(a.build.resolve()),'sources':{str(p.relative_to(ROOT)):sha(p) for p in [*P4.rglob('*.java'),P4/'build.py']},'purpose':'private P4 overlay; no production change'};a.output.with_suffix('.receipt.json').write_text(json.dumps(receipt,indent=2)+'\n');print(a.output)
