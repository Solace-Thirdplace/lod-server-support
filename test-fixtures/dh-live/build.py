#!/usr/bin/env python3
"""Compile a loader-matched test-only observer; never add it to production jars."""
import argparse,subprocess,zipfile,shutil,hashlib,json
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--build',required=True,type=Path);p.add_argument('--output',required=True,type=Path);p.add_argument('--artifact',required=True,type=Path);p.add_argument('--loader',choices=['neoforge','fabric'],default='neoforge');a=p.parse_args()
root=Path(__file__).resolve().parent
import sys
sys.path.insert(0,str(root))
from line import LINE
classes=a.output.parent/'fixture-classes'
if classes.exists():shutil.rmtree(classes)
classes.mkdir(parents=True,exist_ok=True)
classpath=a.build/(a.loader+'/build/dh-fixture-classpath.txt')
if not classpath.is_file():raise ValueError('Export the matching loader compile classpath with classpath.gradle first')
cp=[a.artifact,*map(Path,classpath.read_text().strip().split(':'))]
if a.loader=='fabric':
 # Compile shared observer calls against Fabric's actual named output, before
 # the shipping intermediary artifact and the matching MC named classpath.
 cp.insert(0,a.build/'fabric/build/classes/java/main')
 cp.insert(1,a.build/'common/build/classes/java/main')
remap_cp=list(cp)
for directory in LINE['fixture_link_libraries']:cp.extend(p for p in directory.rglob('*.jar') if zipfile.is_zipfile(p))
args=['-proc:none','--release',LINE['java_release'],'-classpath',':'.join(map(str,cp)),'-d',str(classes),*map(str,(root/'src').rglob('*.java'))]
argfile=a.output.parent/'fixture-javac.args';argfile.write_text('\n'.join('"'+v.replace('\\','\\\\').replace('"','\\"')+'"' for v in args))
subprocess.run([LINE['javac'],'@'+str(argfile)],check=True)
remapping=a.loader=='fabric' and LINE['fabric_remap'] is not None
named=a.output.with_suffix('.named.jar') if remapping else a.output
with zipfile.ZipFile(named,'w',zipfile.ZIP_DEFLATED) as z:
 for base in [classes,root/'resources']:
  for path in base.rglob('*'):
   if path.is_file() and not (a.loader=='fabric' and str(path.relative_to(base)).startswith('META-INF/neoforge')):z.write(path,str(path.relative_to(base)))
 if a.loader=='fabric':z.writestr('fabric.mod.json',json.dumps({'schemaVersion':1,'id':'lss_dh_live_fixture','version':'1.0.0-test-only','name':'Private DH observer','environment':'client','mixins':['lss-dh-fixture.mixins.json'],'depends':{'fabricloader':LINE['fabric_loader_floor'],'minecraft':LINE['minecraft'],'lss':'*'}}))
remap=None
if remapping:
 mappings=LINE['fabric_remap']['mappings']
 remapper=LINE['fabric_remap']['remapper']
 subprocess.run([LINE['java'],'-XX:ActiveProcessorCount=4','-Xmx2g','-jar',str(remapper),str(named),str(a.output),str(mappings),'named','intermediary',*map(str,remap_cp)],check=True)
 remap={'named_observer_sha256':hashlib.sha256(named.read_bytes()).hexdigest(),'mappings_sha256':hashlib.sha256(mappings.read_bytes()).hexdigest(),'remapper_sha256':hashlib.sha256(remapper.read_bytes()).hexdigest(),'from':'named','to':'intermediary','minecraft':LINE['minecraft']}
receipt={'production_artifact_sha256':hashlib.sha256(a.artifact.read_bytes()).hexdigest(),'fixture_sha256':hashlib.sha256(a.output.read_bytes()).hexdigest(),'build':str(a.build.resolve()),'classpath':{str(path):hashlib.sha256(path.read_bytes()).hexdigest() for path in cp if path.is_file()},'sources':{str(path.relative_to(root)):hashlib.sha256(path.read_bytes()).hexdigest() for path in [root/'build.py',root/'line.py',root/'classpath.gradle',*(root/'src').rglob('*'),*(root/'resources').rglob('*')] if path.is_file()}}
a.output.with_suffix('.receipt.json').write_text(json.dumps(receipt,indent=2)+'\n')
if a.loader=='fabric':
 receipt['loader']='fabric';receipt['remap']=remap
 receipt['named_loader_classes']={str(path.relative_to(a.build)):hashlib.sha256(path.read_bytes()).hexdigest() for base in cp[:2] for path in base.rglob('*.class')}
 a.output.with_suffix('.receipt.json').write_text(json.dumps(receipt,indent=2)+'\n')
print(a.output)
