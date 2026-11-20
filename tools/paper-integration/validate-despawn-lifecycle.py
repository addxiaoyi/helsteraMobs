"""Validate lifecycle evidence independently of the probe's matched flags.

World cancellation intentionally retains runtime mobs despite MM 5.7.2's cleanup bug.
This is a documented exception, not counted as native parity.
"""
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
MODES=('NORMAL','CHUNK','NPC','NEVER','PERSISTENT')
PATHS=('direct','imported')
PHASES={'chunk':('initial','retained','reloaded','dead'),'world':('initial','reloaded','dead'),'cancelled':('initial','cancelled')}
RETAINED={'NEVER','PERSISTENT'}
def flag(v):return str(v).lower()

def validate(report,texts):
    require(report['schemaVersion']==1 and report['evidenceMode']=='comparison-with-exception','wrong schema/mode')
    require(report['paperJarSha256']=='39bd8c00b9e18de91dcabd3cc3dcfa5328685a53b7187a2f63280c22e2d287b9','wrong Paper')
    require(report['mythicMobsSha256']=='9672457cafc56ff841606bfbb710947329c08720ffc207746b60130d43bf902f','wrong MM')
    require(all(re.fullmatch('[0-9a-f]{64}',report[k]) for k in ('pluginSha256','probeJarSha256')),'missing artifact')
    require(len(report['sourceHashes'])>=6 and all(re.fullmatch('[0-9a-f]{64}',v) for v in report['sourceHashes'].values()),'source hashes missing')
    require(report['knownExceptions']==['MM-5.7.2-cancelled-world-unload'],'undocumented exception')
    keys={f'{s}_{m.lower()}_{p}':(s,m,p) for s,m,p in itertools.product(PHASES,MODES,PATHS)}
    snapshots={f'snapshots/{k}.yml' for k,(s,m,p) in keys.items() if s!='cancelled' and m in RETAINED}
    require(set(texts)=={'server.log','shutdown-scoped.yml'}|snapshots,'wrong evidence files')
    text=texts['server.log'];lines=text.splitlines()
    def marked(marker):return [(i,fields(l,marker)) for i,l in enumerate(lines) if marker in l]
    require('DESPAWN_FAILED|' not in text and text.count('DESPAWN_SUMMARY|observations=90|mismatches=0|passed=true')==1,'incomplete scenario')
    rows=[l for l in lines if 'DESPAWN_ROW|' in l]
    require(rows==report['observations'] and len(rows)==90,'missing rows')
    identities=marked('DESPAWN_ID|');ids={r['key']:r for _,r in identities}
    require(len(identities)==len(ids)==30 and set(ids)==set(keys),'missing/duplicate identities')
    entity_ids={r[b] for r in ids.values() for b in ('native','runtime')}
    worlds={r['world'] for r in ids.values()}
    require(len(entity_ids)==60 and len(worlds)==3,'identity collision')
    require(all(re.fullmatch('[0-9a-f-]{36}',v) for v in entity_ids|worlds),'invalid UUID')
    for scenario in PHASES:require(len({ids[k]['world'] for k,(s,_,_) in keys.items() if s==scenario})==1,'world identity changed')
    mappings=marked('DESPAWN_MAPPING|')
    require(len(mappings)==30 and {r['key'] for _,r in mappings}==set(keys),'missing mapping')
    for _,r in mappings:require(r['mode']==keys[r['key']][1] and r['trigger']=='true','import not mapped')
    seen=set();row_indices={};expected_final_hooks={}
    for i,r in marked('DESPAWN_ROW|'):
        key=r['key'];require(key in keys,'unknown key');scenario,mode,path=keys[key];phase=r['phase']
        require((scenario,mode,path)==(r['scenario'],r['mode'],r['path']) and phase in PHASES[scenario],'wrong row metadata')
        require((key,phase) not in seen,'duplicate row');seen.add((key,phase));row_indices[key,phase]=i
        retained=mode in RETAINED;exception=scenario=='cancelled' and phase=='cancelled'
        alive=phase in ('initial','retained','cancelled') or phase=='reloaded' and retained
        native_alive=retained if exception else alive;native_registry=False if exception else alive
        despawns=int(phase in ('reloaded','dead') and not retained);deaths=int(phase=='dead' and retained)
        nd=1 if exception and not retained else despawns
        expected={'expected':flag(alive),'nativeEntity':flag(native_alive),'runtimeEntity':flag(alive),
            'nativeRegistry':flag(native_registry),'runtimeInstance':flag(alive),
            'nativeValue':'7' if native_registry else 'absent','runtimeValue':'7' if alive else 'absent',
            'nativePoint':flag(native_registry),'runtimePoint':flag(alive),'runtimeTree':'23' if alive else 'absent',
            'nativeDespawn':str(nd),'runtimeDespawn':str(despawns),'nativeDeath':str(deaths),'runtimeDeath':str(deaths),
            'knownNativeCancellation':flag(exception),'matched':'true'}
        require(all(r[k]==v for k,v in expected.items()),'wrong lifecycle outcome '+key+'/'+phase)
        require(r['snapshotGone']==flag(not (phase=='reloaded' and retained)),'unexpected snapshot lifecycle')
        index=MODES.index(mode)*2+PATHS.index(path)
        point=f'despawn-{scenario},{4097+(index%4)*3}.0,110.0,{4098+(index//4)*4}.0'
        require(r['nativeLocation']==(point if native_registry else 'absent')
                and r['runtimeLocation']==(point if alive else 'absent'),'wrong location value')
        for backend,event,count in [('native','despawn',nd),('runtime','despawn',despawns),('native','death',deaths),('runtime','death',deaths)]:
            expected_final_hooks[backend,event,key]=count
    require(seen=={(key,phase) for key,(scenario,_,_) in keys.items() for phase in PHASES[scenario]},'phase coverage')
    for key,(scenario,_,_) in keys.items():
        require([row_indices[key,p] for p in PHASES[scenario]]==sorted(row_indices[key,p] for p in PHASES[scenario]),'phase order')
    summary=next(i for i,l in enumerate(lines) if 'DESPAWN_SUMMARY|' in l)
    hooks=[(i,r) for i,r in marked('DESPAWN_HOOK|') if i<summary];seen_hooks=set()
    for i,r in hooks:
        key=r['backend'],r['event'],r['key'];require(key in expected_final_hooks and key not in seen_hooks,'unknown/duplicate hook');seen_hooks.add(key)
        require(r['count']=='1' and expected_final_hooks[key]==1,'unexpected hook')
        scenario,mode,path=keys[r['key']]
        phase='cancelled' if scenario=='cancelled' else 'dead' if r['event']=='death' else 'reloaded'
        require(row_indices[r['key'],'initial']<i<row_indices[r['key'],phase],'hook outside lifecycle')
    require(seen_hooks=={k for k,n in expected_final_hooks.items() if n},'missing hook execution')
    visuals=marked('DESPAWN_VISUALS|');checkpoints=[(s,p) for s,phases in PHASES.items() for p in phases]
    require([(r['scenario'],r['phase']) for _,r in visuals]==checkpoints,'visual checkpoint order')
    for _,r in visuals:
        expected=0 if r['phase']=='dead' else 44 if r['phase']=='reloaded' else 110
        require(int(r['expected'])==int(r['actual'])==expected,'visuals lost/duplicated')
    requests=marked('DESPAWN_REQUEST|')
    require([(r['scenario'],r['phase']) for _,r in requests]==[('chunk','cancel'),('chunk','accepted'),('world','accepted'),('cancelled','cancel')],'unload request coverage')
    for _,r in requests:
        require(r['accepted']==flag(r['scenario']!='cancelled') and r['worldAbsent']==flag(r['scenario']=='world'),'unload acceptance')
    unloaded=marked('DESPAWN_UNLOADED|')
    require([r['scenario'] for _,r in unloaded]==['chunk','world'] and all(r['unloaded']==r['entitiesAbsent']=='true' for _,r in unloaded),'actual unload missing')
    events=marked('DESPAWN_EVENT|');entity_events=marked('DESPAWN_ENTITY_EVENT|')
    for scenario in ('chunk','world'):
        relevant=[(i,r) for i,r in events if r['scenario']==scenario and r['phase']=='accepted' and r['event']==scenario+'-unload']
        require(len(relevant)==1,'missing accepted event')
        if scenario=='world':require(relevant[0][1]['cancelled']=='false','world unload cancelled')
        unload_index=next(i for i,r in unloaded if r['scenario']==scenario)
        require(relevant[0][0]<unload_index,'unload order')
        for key,(s,mode,path) in keys.items():
            if s!=scenario:continue
            for backend in ('native','runtime'):
                identity=ids[key][backend]
                loaded=[i for i,r in entity_events if r['uuid']==identity and r['event']=='load']
                deaths=[i for i,r in entity_events if r['uuid']==identity and r['event']=='death']
                if mode in RETAINED:
                    # Initial load may also be observed. Require the post-unload transition explicitly.
                    require(len([i for i in loaded if unload_index<i<row_indices[key,'reloaded']])==1,'missing reload')
                    require(len(deaths)==1 and row_indices[key,'reloaded']<deaths[0]<row_indices[key,'dead'],'missing death')
                else:require(not deaths and not [i for i in loaded if i>unload_index],'despawned mob returns/dies')
    # Shutdown events after the summary are outside this comparison.
    cancelled=[r for i,r in events if i<summary and r['scenario']=='cancelled' and r['event']=='world-unload']
    require(len(cancelled)==1 and cancelled[0]['cancelled']=='true','real cancellation missing')
    for name in snapshots:
        doc=yaml.load(texts[name],Loader=registry.UniqueYaml)
        require(doc['snapshot-format']==4 and list(doc['variables']['entries'].values())==[{'name':'DespawnTree','value':{'type':'INTEGER','value':23}}],'wrong saved tree')
    scoped=yaml.load(texts['shutdown-scoped.yml'],Loader=registry.UniqueYaml)
    require(scoped['registry-format']==1,'wrong registry format')
    scopes={entry['scope'] for entry in scoped['registries'].values()}
    require(len(scoped['registries'])==10,'duplicate shutdown registry')
    require(scopes=={'entity:'+ids[k]['runtime'] for k,(s,_,_) in keys.items() if s=='cancelled'},'dead state saved/cancelled state lost')
    return 90


def main(path):
    root=Path(path);archived=root.is_file()
    report=json.loads((root if archived else root/'despawn-report.json').read_text(),object_pairs_hook=unique)
    if archived:
        name=report['logArchive'];require(Path(name).name==name and '\\' not in name,'unsafe archive')
        archive_path=root.parent/name;require(hashlib.sha256(archive_path.read_bytes()).hexdigest()==report['logArchiveSha256'],'archive changed')
        with zipfile.ZipFile(archive_path) as archive:
            names=archive.namelist();require(len(names)==len(set(names)) and set(names)=={e['path'] for e in report['files']},'archive members changed')
            payloads={name:archive.read(name) for name in names}
    else:
        for name,key in (('paper.jar','paperJarSha256'),('plugins/MythicMobs.jar','mythicMobsSha256'),('plugins/helsteraMobs.jar','pluginSha256'),('plugins/HelsteraDespawnLifecycleProbe.jar','probeJarSha256')):
            require(hashlib.sha256((root/name).read_bytes()).hexdigest()==report[key],'artifact changed')
    texts={}
    for entry in report['files']:
        name=entry['path'];require(not Path(name).is_absolute() and '..' not in Path(name).parts and '\\' not in name and name not in texts,'unsafe/duplicate member')
        data=payloads[name] if archived else (root/name).read_bytes()
        require(hashlib.sha256(data).hexdigest()==entry['sha256'],'member changed');texts[name]=data.decode('utf-8-sig')
    print(f'Validated {validate(report,texts)} despawn lifecycle observations (80 parity, 10 documented cancellation exceptions), eight trees and 46 actual hook executions.')
    return report,texts


if __name__=='__main__':main(sys.argv[1])
