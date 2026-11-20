"""Verify cancelled versus confirmed death callbacks, live state and complete cleanup."""
from datetime import datetime
import hashlib
import importlib.util
import itertools
import json
from pathlib import Path
import re
import sys
import uuid
import zipfile
import yaml

sys.dont_write_bytecode=True
def module(name, filename):
    spec=importlib.util.spec_from_file_location(name,Path(__file__).with_name(filename))
    result=importlib.util.module_from_spec(spec);spec.loader.exec_module(result);return result
registry=module('registry_validator','validate-player-variables.py')
contract=module('callback_contract','cancelled-death-cases.py')
require,fields,unique=registry.require,registry.fields,registry.unique_pairs
BACKENDS=('native','runtime')
PARITY_EXCEPTIONS=['MM-5.7.2-deferred-terminal-command-dispatch','MM-5.7.2-late-cancelled-death-orphan']
SOURCES={'run-cancelled-death-fixture.py','run-player-variable-fixture.py','cancelled-death-cases.py',
         'validate-cancelled-death.py','validate-player-variables.py','test-cancelled-death-evidence.py',
         'random-spawn-fixture/src/main/java/dev/helstera/test/randomspawn/CancelledDeathProbe.java'}
def js(s):return json.loads(s,object_pairs_hook=unique)
def doc(s):return yaml.load(s,Loader=registry.UniqueYaml)
def digest(b):return hashlib.sha256(b).hexdigest()
def flag(b):return str(bool(b)).lower()

def validate(report,payloads):
    require(report['schemaVersion']==1 and report['evidenceMode']=='comparison-with-exception','wrong schema or mode')
    require(report['knownExceptions']==PARITY_EXCEPTIONS and report['parityStatus']=='scoped-comparison','wrong parity scope')
    require(report['paperJarSha256']=='39bd8c00b9e18de91dcabd3cc3dcfa5328685a53b7187a2f63280c22e2d287b9','wrong Paper')
    require(report['mythicMobsSha256']=='9672457cafc56ff841606bfbb710947329c08720ffc207746b60130d43bf902f','wrong native oracle')
    require(all(re.fullmatch('[a-f0-9]{64}',report[k]) for k in ('pluginSha256','probeJarSha256')),'invalid artifact hash')
    require(set(report['sourceHashes'])=={'tools/paper-integration/'+s for s in SOURCES} and all(re.fullmatch('[a-f0-9]{64}',s) for s in report['sourceHashes'].values()),'missing source hashes')
    boot=report['boot'];require(boot['exitCode']==0 and datetime.fromisoformat(boot['startedAtUtc'])<datetime.fromisoformat(boot['stoppedAtUtc']),'unclean or invalid boot')
    expected_files={'server.log','source-mobs.yml','source-skills.yml','snapshots/final-scoped.yml','snapshots/scoped-variables.yml','imports/skills.yml'}
    expected_files|={'imports/cd_'+key+'.yml' for key in contract.CASES}|{'imports/mythic_cd_child_'+mode+'.yml' for mode in ('never','persistent')}
    require(set(payloads)==expected_files,'missing/orphan disk state')
    for name in ('final-scoped.yml','scoped-variables.yml'):require(doc(payloads['snapshots/'+name])=={'registry-format':1,'registries':{}},'orphan final variable registry')
    require(doc(payloads['source-mobs.yml'])==contract.native_mobs(),'native mobs differ from stimuli')
    require(doc(payloads['source-skills.yml'])==contract.native_skills(),'native skills differ from stimuli')
    for key,(scenario,kind,path) in contract.CASES.items():
        cfg=doc(payloads['imports/cd_'+key+'.yml']);mob=cfg['mob'];entity=cfg['entity']
        require(mob['despawn']=='NEVER' and mob['despawn-distance']==0,'wrong runtime removal mode')
        require((mob['entity-type'] if path=='imported' else entity['type'])==kind and entity['no-ai'] and not entity['gravity'] and entity['invulnerable'] and cfg['model']=='example/emberling','wrong runtime entity/options')
        require((mob['health'] if path=='imported' else entity['health'])==20,'wrong parent health')
        triggers=(mob if path=='imported' else cfg['ai'])['triggers']
        pending='command{delay=80} "cancelleddeathprobe hook runtime pending <caster.uuid> <caster.var.Marker>" @self' if path=='imported' else 'command{c="cancelleddeathprobe hook runtime pending <caster.uuid> <caster.var.Marker>";delay=80} @self'
        expected={'on-spawn':{'do':[pending]}}
        if scenario=='control':expected['on-spawn']['do'].append('skill{s=cd_terminal;delay=10} @self')
        else:
            for event in ('death',):expected['on-'+event]={'do':['skill{s=cd_terminal} @self' if path=='imported' else 'cast-skill cd_terminal']}
        if scenario=='skillcancelled':expected['on-death']['do'].append('cancel-event')
        require(mob['revive-health']==20,'wrong mapped revive health')
        require(triggers==expected,'missing or changed mapped callback/negative control')
    for mode in ('never','persistent'):
        cfg=doc(payloads['imports/mythic_cd_child_'+mode+'.yml'])
        require(cfg['mob']['despawn']==mode.upper() and cfg['entity']['type']=='COW' and cfg['ai']['triggers']['on-spawn']['do']==[f'command "cancelleddeathprobe child runtime {mode.upper()} <caster.uuid>" @self'],'wrong summon child configuration')
    expected_skills={'cd_terminal':{'on-decision':[
        'command "cancelleddeathprobe hook runtime immediate <caster.uuid> <caster.var.Marker>" @self',
        'command{delay=1} "cancelleddeathprobe hook runtime delay1 <caster.uuid> <caster.var.Marker>" @self',
        'command{delay=10} "cancelleddeathprobe hook runtime delay10 <caster.uuid> <caster.var.Marker>" @self',
        'command{repeat=2;repeatinterval=5} "cancelleddeathprobe hook runtime repeat <caster.uuid> <caster.var.Marker>" @self',
        'skill{s=cd_nested;delay=10} @self']},'cd_nested':{'on-decision':[
        'command "cancelleddeathprobe hook runtime nested <caster.uuid> <caster.var.Marker>" @self',
        'summon mythic_cd_child_never 1 0 @self','summon mythic_cd_child_persistent 1 0 @self',
        'command "cancelleddeathprobe hook runtime afterchild <caster.uuid> <caster.var.Marker>" @self']}}
    require(doc(payloads['imports/skills.yml'])['skills']==expected_skills,'mapped delay/repeat/nested summon changed')
    text=payloads['server.log'].decode();lines=text.splitlines()
    for bad in ('CDEATH_FAILED|','Error occurred while','Exception:','Variable checkpoint load failed','Variable checkpoint save failed','SkillTree unload save failed'):
        require(bad not in text,'failed diagnostic: '+bad)
    def marked(marker):return [(i,fields(l,marker)) for i,l in enumerate(lines) if marker in l]
    ids=unique(((r['key'],r['backend']),(i,r)) for i,r in marked('CDEATH_ID|'))
    require(set(ids)==set(itertools.product(contract.CASES,BACKENDS)),'missing/duplicate parent identity')
    require(len({r['uuid'] for _,r in ids.values()})==32,'duplicate parent UUID')
    for (key,b),(_,r) in ids.items():require(str(uuid.UUID(r['uuid']))==r['uuid'] and (r['instance']=='0' if b=='native' else int(r['instance'])>0),'invalid identity')
    rows=marked('CDEATH_ROW|');require(len(rows)==96 and report['observations']==[l for l in lines if 'CDEATH_ROW|' in l],'report/log observations differ')
    rowmap=unique(((r['key'],r['backend'],r['phase']),(i,r)) for i,r in rows)
    require(set(rowmap)==set(itertools.product(contract.CASES,BACKENDS,('initial','settled','cleaned'))),'missing/duplicate observation')
    hooks=marked('CDEATH_HOOK|');children=marked('CDEATH_CHILD|');child_states=marked('CDEATH_CHILD_STATE|')
    actions=unique((r['key'],(i,r)) for i,r in marked('CDEATH_ACTION|'));seals=unique((r['key'],(i,r)) for i,r in marked('CDEATH_SEAL|'))
    require(set(actions)==set(seals)==set(contract.CASES),'missing/duplicate scenario action or seal')
    for marker in ('CDEATH_HOOK|','CDEATH_CHILD|'):
        for _,r in marked(marker):require((r['key'],r['backend']) in ids,'unknown event identity')
    child_ids=unique((r['uuid'],(i,r)) for i,r in children)
    require(len(child_ids)==64 and not set(child_ids)&{r['uuid'] for _,r in ids.values()},'missing/duplicate child UUID')
    require(len(hooks)==276 and len(child_states)==128,'missing/duplicate callback or child observations')
    teardowns=unique(((r['key'],r['backend']),(i,r)) for i,r in marked('CDEATH_TEARDOWN|'))
    require(set(teardowns)==set(ids),'missing/duplicate final teardown')
    cancellation_exceptions=[]
    for key,(scenario,kind,path) in contract.CASES.items():
        action_at,action=actions[key];action_tick=int(action['tick']);seal_at,seal=seals[key]
        require(action['scenario']==scenario and seal['accepted']=='true','wrong action or failed world seal')
        for b in BACKENDS:
            parent_at,parent=ids[(key,b)];initial_at,initial=rowmap[(key,b,'initial')];settled_at,settled=rowmap[(key,b,'settled')];clean_at,clean=rowmap[(key,b,'cleaned')]
            require(parent_at<initial_at<action_at<settled_at<clean_at<seal_at,'wrong lifecycle ordering')
            spawn_tick=int(parent['tick']);require(int(initial['tick'])==spawn_tick and int(settled['tick'])==action_tick+130 and int(clean['tick'])==action_tick+230,'wrong observation times')
            live=scenario in ('control','cancelled','skillcancelled');child_count=2
            late_native=b=='native' and scenario=='cancelled'
            for phase,present,count in [('initial',True,0),('settled',live,child_count),('cleaned',late_native,0)]:
                _,r=rowmap[(key,b,phase)]
                registered=present and not (late_native and phase!='initial')
                require(r['uuid']==parent['uuid'] and r['entity']==flag(present) and r['registry']==flag(registered) and r['marker']==('73' if registered else 'absent'),'parent lost, resurrected or stale')
                require(r['children']==str(count) and r['visuals']==str((int(present)+count)*11 if b=='runtime' else 0),'missing/orphan children or visuals')
                require(r['health']==('20.0' if present else 'absent'),'lost revive health or stale parent')
                require(r['terminalExecutions']=='0','terminal execution leaked across checkpoints')
                require(r['ownerRecords']==('1' if phase=='settled' else '0') and r['minionRecords']==('2' if phase=='settled' else '0'),'orphan or missing summon ownership')
            teardown_at,teardown=teardowns[(key,b)]
            require(teardown['uuid']==parent['uuid'] and teardown['entityBefore']==flag(late_native) and teardown['entityAfter']=='false' and clean_at<teardown_at<seal_at,'missing or incorrect orphan teardown')
            if late_native:cancellation_exceptions.append({'key':key,'uuid':parent['uuid'],'registryLost':True,'pendingCancelled':True,'commandCleanupOrphan':True})
            hs=[(i,r) for i,r in hooks if r['key']==key and r['backend']==b]
            require(all(r['uuid']==parent['uuid'] and r['marker']=='73' and r['primary']=='true' and r['cleaning']=='false' and initial_at<i<settled_at for i,r in hs),'wrong callback context or cleanup replay')
            labels={name:[int(r['tick']) for _,r in hs if r['label']==name] for name in ('immediate','delay1','delay10','repeat','nested','afterchild','pending')}
            require(len(labels['immediate'])==1,'terminal callback missing or replayed');start=labels['immediate'][0]
            if scenario=='control':require(start==spawn_tick+10 and action_tick==spawn_tick+25,'wrong positive-control timing')
            else:require(action_tick<=start<=action_tick+(2 if b=='native' else 0),'death callback outside event window')
            require([len(labels[k]) for k in ('delay1','delay10','repeat','nested','afterchild')]==[1,1,3,1,1],'lost delayed/repeated/nested callback')
            for label,delay in [('delay1',1),('delay10',10),('nested',10),('afterchild',10)]:
                delta=labels[label][0]-start
                require(delta in ([delay-1,delay,delay+1] if b=='native' else [delay]),'wrong callback delay')
            require(all(abs(t-(start+5*i))<=(1 if b=='native' else 0) for i,t in enumerate(labels['repeat'])),'wrong repeat timing')
            require(labels['nested'][0]==labels['afterchild'][0],'nested actions split across ticks')
            require(labels['pending']==([spawn_tick+80] if live and not late_native else []),'ordinary pending work survives removal or positive control lost')
            require(len(hs)==(9 if live and not late_native else 8),'unexpected callback label')
            born=[(i,r) for i,r in children if r['key']==key and r['backend']==b]
            require(len(born)==child_count and {r['mode'] for _,r in born}==({'NEVER','PERSISTENT'} if child_count else set()),'wrong callback children')
            for born_at,child in born:
                # MM can defer the child's onSpawn console command by one tick,
                # just as it defers terminal command dispatch after removal.
                delta=int(child['tick'])-labels['nested'][0]
                require(str(uuid.UUID(child['uuid']))==child['uuid'] and child['cleaning']=='false' and child['primary']=='true' and delta in ([0,1] if b=='native' else [0]) and initial_at<born_at<settled_at,'wrong child origin')
                states=[(i,r) for i,r in child_states if r['uuid']==child['uuid']]
                require([r['phase'] for _,r in states]==['settled','cleaned'],'missing/duplicate child state')
                for at,r in states:
                    present=r['phase']=='settled'
                    require(r['key']==key and r['kind']==b+'_'+child['mode'] and r['entity']==flag(present) and r['nativeRegistry']==flag(present and b=='native') and r['runtimeRegistry']==flag(present and b=='runtime'),'child not managed or cleanup leaked')
                    require((settled_at if present else clean_at)<at<seal_at,'child state outside checkpoint')
    require(report['nativeCancellationExceptions']==sorted(cancellation_exceptions,key=lambda r:r['key']),'hidden or altered native cancellation difference')
    require(report['gapCases']==[],'unexpected compatibility gap')
    deaths=unique((r['uuid'],(i,r)) for i,r in marked('CDEATH_DEATH|'))
    cancellations=unique((r['uuid'],(i,r)) for i,r in marked('CDEATH_CANCEL|'))
    require(set(deaths)=={r['uuid'] for (key,b),(_,r) in ids.items() if contract.CASES[key][0]!='control'},'missing/extra death event')
    require(set(cancellations)=={r['uuid'] for (key,b),(_,r) in ids.items() if contract.CASES[key][0]=='cancelled'},'missing/extra cancellation')
    for (key,b),(_,parent) in ids.items():
        scenario=contract.CASES[key][0]
        if scenario=='control':continue
        at,death=deaths[parent['uuid']];action_at,action=actions[key]
        require(death['key']==key and death['tick']==action['tick'] and death['cancelled']==flag(scenario in ('cancelled','skillcancelled')),'wrong actual death event')
        require(action_at<at<rowmap[(key,b,'settled')][0],'death event outside action')
        if scenario in ('cancelled','skillcancelled'):require(death['reviveHealth']=='20.0','wrong cancelled revive health')
        if scenario=='cancelled':
            cancel_at,cancel=cancellations[parent['uuid']]
            require(cancel['key']==key and cancel['tick']==death['tick'] and cancel['reviveHealth']==death['reviveHealth']=='20.0' and action_at<cancel_at<at,'missing or overwritten cancellation/revive health')
    summary=marked('CDEATH_SUMMARY|');require(len(summary)==1 and summary[0][1]=={'rows':'96','cases':'16','completed':'true'} and max(i for i,_ in seals.values())<summary[0][0],'incomplete summary')
    require(text.count(']: Stopping server')==1 and next(i for i,l in enumerate(lines) if ']: Stopping server' in l)>summary[0][0],'premature shutdown')
    return len(rows)

def main(path):
    p=Path(path);archived=p.is_file();report=js((p if archived else p/'cancelled-death-report.json').read_text())
    if archived:
        name=report['logArchive'];require(Path(name).name==name and '\\' not in name,'unsafe archive');archive=p.parent/name;require(digest(archive.read_bytes())==report['logArchiveSha256'],'archive changed')
        with zipfile.ZipFile(archive) as z:
            names=z.namelist();require(len(names)==len(set(names)) and set(names)=={f['path'] for f in report['files']},'archive membership changed');payloads={n:z.read(n) for n in names}
    else:
        for name,key in [('paper.jar','paperJarSha256'),('plugins/helsteraMobs.jar','pluginSha256'),('plugins/MythicMobs.jar','mythicMobsSha256'),('plugins/HelsteraCancelledDeathProbe.jar','probeJarSha256')]:require(digest((p/name).read_bytes())==report[key],'artifact changed')
        payloads={}
    seen=set()
    for f in report['files']:
        name=f['path'];require(not Path(name).is_absolute() and '..' not in Path(name).parts and '\\' not in name and name not in seen,'unsafe/duplicate member');seen.add(name)
        if not archived:payloads[name]=(p/name).read_bytes()
        require(digest(payloads[name])==f['sha256'],'member changed')
    count=validate(report,payloads)
    print(f"Validated {count} observations, 32 parents; mode={report['evidenceMode']}; unresolved cases={len(report['gapCases'])}.");return report,payloads
if __name__=='__main__':main(sys.argv[1])
