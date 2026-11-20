"""Verify native/runtime registry and actual managed-instance unload/reload evidence."""
import hashlib
import importlib.util
import itertools
import json
from pathlib import Path
import re
import sys
import zipfile
import yaml
sys.dont_write_bytecode=True
spec=importlib.util.spec_from_file_location('registry_validator',Path(__file__).with_name('validate-player-variables.py'))
registry=importlib.util.module_from_spec(spec);spec.loader.exec_module(registry)
require,fields,unique=registry.require,registry.fields,registry.unique_pairs
PHASES=('write','chunk','world','final')
PATHS=('direct','imported')
SCOPES=('caster','target','world','global')
CASES=('Saved','Transient','Negative','Timed','Expired','Float','String','Location')


def validate(report,texts):
    require(report['schemaVersion']==1 and report['evidenceMode']=='comparison','wrong schema/mode')
    require(report['paperJarSha256']=='39bd8c00b9e18de91dcabd3cc3dcfa5328685a53b7187a2f63280c22e2d287b9','wrong Paper')
    require(report['mythicMobsSha256']=='9672457cafc56ff841606bfbb710947329c08720ffc207746b60130d43bf902f','wrong MM')
    require(all(re.fullmatch('[0-9a-f]{64}',report[k]) for k in ('pluginSha256','probeJarSha256')),'missing artifact')
    expected_files={'server.log','shutdown-scoped.yml'}|{f'snapshots/{phase}-{path}.yml' for phase,path in itertools.product(PHASES[1:],PATHS)}
    require(set(texts)==expected_files,'wrong evidence files')
    text=texts['server.log']; lines=text.splitlines()
    def marked(marker):return [(i,fields(l,marker)) for i,l in enumerate(lines) if marker in l]
    require('WORLD_VAR_FAILED|' not in text and text.count('WORLD_VAR_SUMMARY|observations=256|passed=true')==1,'incomplete scenario')
    rows=[l for l in lines if 'WORLD_VAR|' in l]
    require(rows==report['observations'] and len(rows)==256,'incomplete rows')
    identities=marked('WORLD_VAR_ID|')
    require(len(identities)==2 and {r['path'] for _,r in identities}==set(PATHS),'missing identities')
    ids={r['path']:r for _,r in identities}; world_ids={r['world'] for _,r in identities}
    entity_ids={r[k] for _,r in identities for k in ('native','runtime')}
    require(len(world_ids)==1 and len(entity_ids)==4,'identity collision')
    require(all(re.fullmatch('[0-9a-f-]{36}',v) for v in entity_ids|world_ids),'invalid identity')
    expected_keys=set(itertools.product(PATHS,SCOPES,CASES)); original={}
    phases=marked('WORLD_VAR_PHASE|')
    require([r['phase'] for _,r in phases]==list(PHASES) and all(r['observations']=='64' and r['passed']=='true' for _,r in phases),'phase order/count')
    attached=marked('WORLD_VAR_ATTACHED|'); trees=marked('WORLD_VAR_TREE|')
    require(len(attached)==len(trees)==8,'missing reattachment/tree')
    require({(r['phase'],r['path']) for _,r in attached}=={(r['phase'],r['path']) for _,r in trees}==set(itertools.product(PHASES,PATHS)),'duplicate attachment/tree')
    for _,r in attached:require(all(r[k]==ids[r['path']][k] for k in ('native','runtime','world')),'identity changed on reload')
    require(all(r['passed']=='true' for _,r in trees),'tree lost')
    visuals=marked('WORLD_VAR_VISUALS|')
    require([r['phase'] for _,r in visuals]==list(PHASES) and all(int(r['expected'])>0 and r['expected']==r['actual'] for _,r in visuals),'visuals lost/duplicated')
    require(len({r['actual'] for _,r in visuals})==1,'visual count changed')
    for phase in PHASES:
        observed=[fields(l,'WORLD_VAR|') for l in rows if f'|phase={phase}|' in l]
        require(len(observed)==64,'missing phase rows');seen=set()
        for r in observed:
            key=r['path'],r['scope'],r['case'];require(key in expected_keys and key not in seen,'duplicate/unknown variable');seen.add(key)
            path,scope,kind=key;present=phase!='final' and (phase=='write' or kind!='Expired')
            require(r['expected']==str(present).lower() and r['matched']=='true','presence/mismatch')
            if not present:require(all(r[k]=='absent' for k in ('native','runtime','nativeType','runtimeType')) and r['nativeDeadline']==r['runtimeDeadline']=='0','deleted/expired value returns')
            else:
                value='3.25' if kind=='Float' else 'world-value' if kind=='String' else '7'
                if kind=='Location':
                    point=r['native'].split(',');require(len(point)==4 and point[0]=='variable-lifecycle','wrong location')
                    require([float(v) for v in point[1:]]==[4113.,110.,4002. if path=='direct' else 4004.],'location changed')
                else:require(r['native']==value,'wrong value')
                require(r['native']==r['runtime'],'value mismatch')
                kind_type=kind if kind in ('Float','String','Location') else 'Integer'
                require(r['runtimeType']==kind_type and r['nativeType']==('AbstractLocation' if kind=='Location' else kind_type),'wrong type')
                nd,rd=int(r['nativeDeadline']),int(r['runtimeDeadline'])
                if phase=='write':
                    if kind in ('Timed','Expired'):require(nd>1_000_000_000_000 and abs(nd-rd)<1000,'bad deadline')
                    else:require(nd==rd== (1 if kind=='Transient' else -50 if kind=='Negative' else 0),'bad marker')
                else:require((nd,rd)==original[key],'deadline reset')
            if phase=='write':original[key]=(int(r['nativeDeadline']),int(r['runtimeDeadline']))
    chunk=marked('WORLD_VAR_CHUNK_UNLOADED|')
    require(len(chunk)==1 and chunk[0][1]=={'loaded':'false','entitiesAbsent':'true'},'chunk never unloaded')
    requests=marked('WORLD_VAR_WORLD_REQUEST|')
    require([r['next'] for _,r in requests]==['world','final'] and all(r['accepted']==r['absent']==r['entitiesAbsent']=='true' for _,r in requests),'world never unloaded')
    events=marked('WORLD_VAR_EVENT|')
    for index,(phase,low,high) in enumerate((('chunk',phases[0][0],phases[1][0]),('world',phases[1][0],phases[2][0]),('final',phases[2][0],phases[3][0]))):
        for event in (('entity-unload','entity-load') if phase=='chunk' else ('entity-load',)):
            found=[(i,r) for i,r in events if low<i<high and r['event']==event]
            require(len(found)==4 and {r['uuid'] for _,r in found}==entity_ids,'missing actual entity transition')
        for identity in entity_ids:
            unload=next(i for i,r in events if low<i<high and r['event']=='entity-unload' and r['uuid']==identity) if phase=='chunk' else next(i for i,r in requests if r['next']==phase)
            load=next(i for i,r in events if low<i<high and r['event']=='entity-load' and r['uuid']==identity)
            require(unload<load,'entity load preceded unload')
        if phase!='chunk':
            found=[r for i,r in events if low<i<high and r['event']=='world-unload']
            require(len(found)==1 and found[0]['world'] in world_ids,'missing actual world unload')
    deaths=[r for i,r in events if i>phases[-1][0] and r['event']=='death']
    require(len(deaths)==4 and {r['uuid'] for r in deaths}==entity_ids,'missing deaths')
    cleaned=marked('WORLD_VAR_DEAD|')
    require(len(cleaned)==2 and {r['path'] for _,r in cleaned}==set(PATHS) and all(all(r[k]=='true' for k in ('nativeGone','runtimeGone','instanceGone','snapshotGone')) for _,r in cleaned),'death cleanup failed')
    for phase,path in itertools.product(PHASES[1:],PATHS):
        doc=yaml.load(texts[f'snapshots/{phase}-{path}.yml'],Loader=registry.UniqueYaml)
        require(doc['snapshot-format']==4,'snapshot format')
        entries=list(doc['variables']['entries'].values())
        require(entries==([] if phase=='final' else [{'name':'WorldTree','value':{'type':'INTEGER','value':23}}]),'saved tree changed')
    scoped=yaml.load(texts['shutdown-scoped.yml'],Loader=registry.UniqueYaml)
    require(scoped['registry-format']==1 and not scoped['registries'],'dead/deleted scoped state saved again')
    return 256


def main(path):
    root=Path(path);archived=root.is_file()
    report=json.loads((root if archived else root/'world-variable-report.json').read_text(),object_pairs_hook=unique)
    if archived:
        name=report['logArchive'];require(Path(name).name==name and '\\' not in name,'unsafe archive')
        archive_path=root.parent/name;require(hashlib.sha256(archive_path.read_bytes()).hexdigest()==report['logArchiveSha256'],'archive changed')
        with zipfile.ZipFile(archive_path) as archive:
            names=archive.namelist();require(len(names)==len(set(names)) and set(names)=={e['path'] for e in report['files']},'archive members changed')
            payloads={name:archive.read(name) for name in names}
    else:
        for name,key in (('paper.jar','paperJarSha256'),('plugins/MythicMobs.jar','mythicMobsSha256'),('plugins/helsteraMobs.jar','pluginSha256'),('plugins/HelsteraWorldVariableLifeProbe.jar','probeJarSha256')):
            require(hashlib.sha256((root/name).read_bytes()).hexdigest()==report[key],'artifact changed')
    texts={}
    for entry in report['files']:
        name=entry['path'];require(not Path(name).is_absolute() and '..' not in Path(name).parts and '\\' not in name and name not in texts,'unsafe/duplicate member')
        data=payloads[name] if archived else (root/name).read_bytes()
        require(hashlib.sha256(data).hexdigest()==entry['sha256'],'member changed');texts[name]=data.decode('utf-8-sig')
    print(f'Validated {validate(report,texts)} world/chunk variable comparisons, eight trees, four deaths and six disk snapshots.')
    return report,texts


if __name__=='__main__':main(sys.argv[1])
