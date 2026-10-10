#!/usr/bin/env python3
import argparse,os,subprocess,hashlib,json,zipfile
from pathlib import Path
root=Path(__file__).resolve().parents[2]
setup=Path('/home/vox/projects/voxel-server-support/build/dh-neoforge/2026-10-01')
p=argparse.ArgumentParser()
p.add_argument('--artifact',type=Path,required=True)
p.add_argument('--fixture',type=Path,required=True)
p.add_argument('--dh',type=Path,default=setup/'DistantHorizons-3.3.3-1.21.1-fabric-neoforge.jar')
p.add_argument('--base',type=Path,default=setup/'private-tests')
p.add_argument('--scenario',choices=['lifecycle','menu','absence','content','service','race','rejoin','updates','boundary','load','movement'],default='lifecycle');p.add_argument('--arm',choices=['lss','official','dh-range128','dh-range32','dh-off'],default='lss');p.add_argument('--load-range',type=int,default=32);p.add_argument('--cold-seconds',type=int,default=420);p.add_argument('--warm-seconds',type=int,default=180)
p.add_argument('--server-family',choices=['official','support','vanilla'],default='official')
p.add_argument('--profile',choices=['dh_support_15','official_15','official_16'],default='official_16')
p.add_argument('--expect',choices=['ready','no-service','conflict','ocean','old-protocol'],default='ready')
p.add_argument('--terrain-source',choices=['dh','native'],default='dh')
p.add_argument('--moving-border',action='store_true')
p.add_argument('--bounded-storage',action='store_true')
p.add_argument('--slow-config',action='store_true')
p.add_argument('--allow-dh',action='store_true')
p.add_argument('--source-unset',action='store_true')
p.add_argument('--boundary-expect',choices=['report','covered'],default='covered')
p.add_argument('--builder',choices=['FullBuilder','FastOverworldBuilder'],default='FullBuilder')
p.add_argument('--loader',choices=['neoforge','fabric'],default='neoforge')
p.add_argument('--build',type=Path,required=True)
p.add_argument('--verification',type=Path,required=True)
a=p.parse_args()
def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest()
verify=json.loads(a.verification.read_text());receipt=json.loads(a.fixture.with_suffix('.receipt.json').read_text())
if not verify.get('normal_build_passed') or verify.get('candidate_artifact_sha256')!=sha(a.artifact) or verify.get('fixture_sha256')!=sha(a.fixture):raise ValueError('Candidate/fixture does not match normal-build verification receipt')
if receipt['production_artifact_sha256']!=sha(a.artifact) or receipt['fixture_sha256']!=sha(a.fixture) or Path(receipt['build']).resolve()!=a.build.resolve():raise ValueError('Fixture ABI/build provenance mismatch')
for path,digest in receipt['sources'].items():
 if sha(root/'test-fixtures/dh-live'/path)!=digest:raise ValueError('Fixture sources changed since compilation: '+path)
for path,digest in receipt.get('named_loader_classes',{}).items():
 if sha(a.build/path)!=digest:raise ValueError('Named Fabric ABI input changed since compilation: '+path)
source_manifest=a.verification.parent/'source-manifest.json';test_manifest=a.verification.parent/'test-source-manifest.json'
if sha(source_manifest)!=verify['source_manifest_sha256'] or sha(test_manifest)!=verify['test_source_manifest_sha256']:raise ValueError('Source manifest hash mismatch')
for path,digest in json.loads(test_manifest.read_text()).items():
 if sha(root/path)!=digest:raise ValueError('Test/rig sources changed since verification binding: '+path)
production=json.loads(source_manifest.read_text())
bound=set(production)|set(json.loads(test_manifest.read_text()))
gates={'scripts/release_check.py', 'tools/dh/assemble_playable.py'}
gates.update(str(path.relative_to(root)) for path in (root/'tools/packaging').rglob('*') if path.is_file() and '__pycache__' not in path.parts)
if not gates<=bound:raise ValueError('Verification manifests omit release/packaging gates: '+', '.join(sorted(gates-bound)))
for path,digest in production.items():
 if sha(root/path)!=digest or sha(a.build/path)!=digest:raise ValueError('Production source differs from candidate build: '+path)
cp=str(a.build/'common/build/libs'/('common-'+verify['build_version']+'.jar'))
if not Path(cp).is_file():raise ValueError('Missing matching common artifact')
for module in ['org.slf4j/slf4j-api','com.google.code.gson/gson']:
 cp+=':'+str(next((Path('/home/vox/.gradle/caches/modules-2/files-2.1')/module).rglob('*.jar')))
env=os.environ.copy();env['LSS_SETTINGS_CLASSPATH']=cp;env['JAVA_HOME']='/usr/lib/jvm/java-21-openjdk-amd64'
cmd=['python3',str(root/'test-fixtures/dh-live/prepare.py'),'--artifact',str(a.artifact),'--fixture',str(a.fixture),'--dh',str(a.dh),'--base',str(a.base),'--scenario',a.scenario]
cmd+=['--server-family',a.server_family,'--profile',a.profile,'--expect',a.expect,'--builder',a.builder]
cmd+=['--arm',a.arm,'--load-range',str(a.load_range),'--cold-seconds',str(a.cold_seconds),'--warm-seconds',str(a.warm_seconds)]
cmd+=['--loader',a.loader,'--terrain-source',a.terrain_source,'--boundary-expect',a.boundary_expect]
if a.bounded_storage:cmd.append('--bounded-storage')
if a.moving_border:cmd.append('--moving-border')
if a.slow_config:cmd.append('--slow-config')
if a.allow_dh:cmd.append('--allow-dh')
if a.source_unset:cmd.append('--source-unset')
p=subprocess.run(cmd,env=env,text=True,capture_output=True)
if p.returncode:
 a.base.mkdir(parents=True,exist_ok=True)
 failure=a.base/'preparation-failure.log';failure.write_text(p.stderr)
 raise RuntimeError('Private preparation failed; see '+str(failure))
print(p.stdout,flush=True);a.base.mkdir(parents=True,exist_ok=True);(a.base/'private-run-path.txt').write_text(p.stdout.strip()+'\n')
subprocess.run(['python3',str(root/'test-fixtures/dh-live/run.py'),p.stdout.strip()],check=True)
