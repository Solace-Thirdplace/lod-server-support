#!/usr/bin/env python3
"""Prepare bounded fresh-world two-client recipes from frozen dependency templates. No launch."""
import argparse,copy,json,os,shutil,sys,zipfile
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'compat'))
from catalog import inspect_jar
from rig import sha,digest,plan,write
from rig_settings import render

REMOVED_FIXTURES=('lss-rig-fabric-concurrent.jar','lss-rig-paper-concurrent.jar','lss-rig-folia-sources.jar')
def product(row):
    m=row.get('metadata',{})
    return m.get('fabric',{}).get('id')=='lss' or m.get('paper',{}).get('name') in ('LodServerSupport','VoxyServerSide')
def prepare(source,output,client,server,observer,platform,port):
    if not os.environ.get('LSS_SETTINGS_CLASSPATH'):raise ValueError('freeze LSS_SETTINGS_CLASSPATH before recipe preparation')
    output.mkdir(parents=True,exist_ok=False,mode=0o700)
    runtime=json.loads((source/'runtime.json').read_text());profile=json.loads((source/'profile.json').read_text())
    server_profile=json.loads(Path(runtime['server_profile']['path']).read_text())
    replacements={}
    for role,candidate,rows in [('client',client,runtime['candidate_artifacts']),('server',server,runtime['server_profile']['candidate_artifacts'])]:
        selected=[row for row in rows if product(row)]
        if len(selected)!=1:raise ValueError('exact candidate required for '+role)
        frozen=output/(role+'-'+candidate.name);shutil.copyfile(candidate,frozen)
        metadata=inspect_jar(frozen)
        replacements[selected[0]['sha256']]=dict(path=str(frozen),sha256=metadata['sha256'],metadata=metadata['metadata'])
    removed_hashes={row['sha256'] for row in runtime['stage_files'] if Path(row['target']).name in REMOVED_FIXTURES}
    def refresh(value):
        if isinstance(value,list):return [refresh(x) for x in value if not isinstance(x,dict) or x.get('sha256') not in removed_hashes]
        if not isinstance(value,dict):return value
        new={k:refresh(v) for k,v in value.items()};replacement=replacements.get(value.get('sha256'))
        if replacement:
            new['sha256']=replacement['sha256']
            if 'target' in value:new['source']=replacement['path']
            elif 'metadata' in value:
                new['metadata']=replacement['metadata'];new['source']='cache:sha256:'+replacement['sha256']
                new['version']=str(replacement['metadata'].get('fabric',replacement['metadata'].get('paper',{})).get('version','locked'))
        return new
    runtime=refresh(runtime);profile=refresh(profile);server_profile=refresh(server_profile)
    for old,new in replacements.items():runtime['cache'].pop(old,None);runtime['cache'][new['sha256']]=new['path']
    for old in removed_hashes:runtime['cache'].pop(old,None)
    profile['status']=server_profile['status']='unverified'
    write(output/'profile.json',profile);write(output/'server-profile.json',server_profile)
    runtime['server_profile'].update(path=str(output/'server-profile.json'),profile_hash=digest(server_profile))
    runtime['client_profiles']=[row for row in runtime.get('client_profiles',[]) if row['role'] in ('client-A','client-B')]
    for row in runtime['client_profiles']:row.update(path=str(output/'profile.json'),profile_hash=digest(profile))
    runtime['stage_files']=[row for row in runtime['stage_files'] if not row['target'].startswith(('server/world/','server/world_nether/','server/world_the_end/','client-C/','client-D/'))]
    runtime.pop('world_digest',None)
    runtime['immutable_trees']={key:{name:value for name,value in tree.items() if value not in removed_hashes} for key,tree in runtime.get('immutable_trees',{}).items() if not key.startswith(('server/world','client-C','client-D'))}
    runtime['launches']=[row for row in runtime['launches'] if row['id'] in ('server','client-A','client-B')]
    for launch in runtime['launches']:
        launch['argv']=[x for x in launch['argv'] if not x.startswith(('-Dlss.rig.seedSnapshotDigest=','-Dlss.rig.nativeAutoSaveInterval='))]
        for at,arg in enumerate(launch['argv']):
            if at and launch['argv'][at-1]=='-cp':launch['argv'][at]=':'.join(x for x in arg.split(':') if Path(x).name not in REMOVED_FIXTURES)
        if launch['id']=='server':launch['ready_marker']='Done ('
    runtime['ready_conditions']=[]
    generated={name:text for name,text in runtime['generated_files'].items() if not name.startswith(('client-C/','client-D/')) and name!='debt-bounds.json' and not name.endswith(('-client-config.json','-server-config.json','-client-config.yaml','-server-config.yaml'))}
    config='server/config/lss-server-config.yaml' if platform=='fabric' else 'server/plugins/LodServerSupport/lss-server-config.yaml'
    changes={'generation.enabled':True,'generation.concurrency.global':2,'generation.concurrency.per_player':1,
        'generation.timeout_ticks':1200,'lod.distance.default_chunks':16,'lod.distance.by_dimension':{},'storage.lod_store.enabled':True,
        'storage.lod_store.max_size_mib':256,'storage.lod_store.backfill.enabled':False}
    generated[config]=render(changes,platform='mod' if platform=='fabric' else 'paper')
    for subject in 'AB':generated['client-'+subject+'/config/lss-client-config.yaml']=render({'lod.receive':True,'lod.distance_chunks':16,'integrations.map_ingestion.enabled':True,'lod.download.max_columns_per_second':40},side='client')
    props=generated['server/server.properties']
    # Folia rechecks pending login reservations; two clients need admission headroom.
    login_capacity=4 if platform=='folia' else 2
    generated['server/server.properties']='\n'.join(line for line in props.splitlines() if not line.startswith(('server-ip=','server-port=','level-seed=','max-players=','level-type=','view-distance=','simulation-distance=','generator-settings=','gamemode=','force-gamemode=')))+f'\nserver-ip=127.0.0.1\nserver-port={port}\nlevel-seed=yaml-settings-bounded-1\nmax-players={login_capacity}\ngamemode=creative\nforce-gamemode=true\nlevel-type=minecraft:normal\nview-distance=3\nsimulation-distance=3\n'
    runtime['generated_files']=generated;runtime['bind_endpoint']=runtime['client_endpoint']='127.0.0.1:'+str(port)
    identity=json.loads((observer/'identity.json').read_text())
    if identity['candidate_sha256']!=sha(server):raise ValueError('observer was built for different candidate bytes')
    for name in ('lss-rig-settings-agent.jar','lss-rig-settings-observer.jar'):
        source_jar=observer/name;frozen=output/name;shutil.copyfile(source_jar,frozen)
        runtime['stage_files'].append(dict(source=str(frozen),target='server/'+name,sha256=sha(frozen)))
    launch=next(row for row in runtime['launches'] if row['id']=='server')
    flags=['-javaagent:{run}/server/lss-rig-settings-agent.jar','-Dlss.rig.settingsObserver={run}/server/lss-rig-settings-observer.jar']
    if platform=='fabric':
        # Knot must share the bootstrap recorder used by premain, preserving any
        # libraries the source recipe already exposes to the game classloader.
        prefix='-Dfabric.systemLibraries='
        libraries=[path for arg in launch['argv'] if arg.startswith(prefix)
                   for path in arg[len(prefix):].split(':') if path]
        libraries += ['{run}/server/lss-rig-settings-agent.jar','{run}/server/lss-rig-settings-observer.jar']
        launch['argv']=[arg for arg in launch['argv'] if not arg.startswith(prefix)]
        flags.append(prefix+':'.join(dict.fromkeys(libraries)))
    flags += ['-Dlss.rig.settings.'+kind+'Sha256='+checksum for kind,checksum in identity['target_sha256'].items()]
    launch['argv'][1:1]=flags
    runtime['settings_observer']=dict(identity,config_relative=config)
    # These are fresh mutable worlds; only immutable assets/dependencies are shared as inputs.
    for row in runtime['stage_files']:
        if not Path(row['source']).is_file():raise ValueError('missing stage input: '+row['target'])
    assertions=['generation_admission_drain','normal_serving_continues','backfill_policy_progress' if platform=='fabric' else 'owned_reload_acknowledgments']
    if platform=='folia':assertions.append('reload_window_distinct_owning_regions')
    scenario=dict(schema_version=1,id=platform+'-yaml-reload',version=1,checker='yaml-reload',server_platform=platform,timeout_seconds=540,observe_seconds=420,
        assertions=assertions,required_test_count=len(assertions),human_reviews=[])
    write(output/'runtime.json',runtime);write(output/'scenario.json',scenario)
    result=plan(profile,runtime,scenario);write(output/'plan.json',result)
    return result

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    for name in ('source','output','client','server','observer'):p.add_argument('--'+name,type=Path,required=True)
    p.add_argument('--platform',choices=['fabric','paper','folia'],required=True);p.add_argument('--port',type=int,default=25628);a=p.parse_args()
    result=prepare(a.source,a.output,a.client,a.server,a.observer,a.platform,a.port)
    print(json.dumps({'status':result['status'],'recipe':str(a.output),'missing':len(result.get('missing',[]))}))
