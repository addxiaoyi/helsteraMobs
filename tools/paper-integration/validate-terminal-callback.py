"""Verify a known compatibility gap; this diagnostic must never be called parity success."""
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
contract=module('callback_contract','terminal-callback-cases.py')
require,fields,unique=registry.require,registry.fields,registry.unique_pairs
BACKENDS=('native','runtime')
EXCEPTIONS=['Helstera-terminal-continuations-cancelled','MM-5.7.2-deferred-terminal-command-dispatch']
SOURCES={'run-terminal-callback-fixture.py','run-player-variable-fixture.py','terminal-callback-cases.py',
         'validate-terminal-callback.py','validate-player-variables.py','test-terminal-callback-evidence.py',
         'random-spawn-fixture/src/main/java/dev/helstera/test/randomspawn/TerminalCallbackProbe.java'}
def js(s):return json.loads(s,object_pairs_hook=unique)
def doc(s):return yaml.load(s,Loader=registry.UniqueYaml)
def digest(b):return hashlib.sha256(b).hexdigest()
def flag(b):return str(bool(b)).lower()

def validate(report,payloads):
    require(report['schemaVersion']==1 and report['evidenceMode']=='diagnostic-known-gap','not a known-gap diagnostic')
    require(report['knownExceptions']==EXCEPTIONS and report['parityStatus']=='incomplete','gap hidden or called parity')
    require(report['paperJarSha256']=='39bd8c00b9e18de91dcabd3cc3dcfa5328685a53b7187a2f63280c22e2d287b9','wrong Paper')
    require(report['mythicMobsSha256']=='9672457cafc56ff841606bfbb710947329c08720ffc207746b60130d43bf902f','wrong native oracle')
    require(all(re.fullmatch('[a-f0-9]{64}',report[k]) for k in ('pluginSha256','probeJarSha256')),'invalid artifact hash')
    require(set(report['sourceHashes'])=={'tools/paper-integration/'+s for s in SOURCES} and all(re.fullmatch('[a-f0-9]{64}',s) for s in report['sourceHashes'].values()),'missing source hashes')
    boot=report['boot'];require(boot['exitCode']==0 and datetime.fromisoformat(boot['startedAtUtc'])<datetime.fromisoformat(boot['stoppedAtUtc']),'unclean or invalid boot')
    expected_files={'server.log','source-mobs.yml','source-skills.yml','snapshots/final-scoped.yml','snapshots/scoped-variables.yml','imports/skills.yml'}
    expected_files|={'imports/tc_'+key+'.yml' for key in contract.CASES}|{'imports/mythic_tc_child_'+mode+'.yml' for mode in ('never','persistent')}
    require(set(payloads)==expected_files,'missing/orphan disk state')
    for name in ('final-scoped.yml','scoped-variables.yml'):require(doc(payloads['snapshots/'+name])=={'registry-format':1,'registries':{}},'orphan final variable registry')
    require(doc(payloads['source-mobs.yml'])==contract.native_mobs(),'native mobs differ from stimuli')
    require(doc(payloads['source-skills.yml'])==contract.native_skills(),'native skills differ from stimuli')
    for key,(scenario,kind,path) in contract.CASES.items():
        cfg=doc(payloads['imports/tc_'+key+'.yml']);mob=cfg['mob'];entity=cfg['entity']
        require(mob['despawn']==('CHUNK' if scenario=='chunk' else 'NEVER') and mob['despawn-distance']==0,'wrong runtime removal mode')
        require((mob['entity-type'] if path=='imported' else entity['type'])==kind and entity['no-ai'] and not entity['gravity'] and entity['invulnerable'] and cfg['model']=='example/emberling','wrong runtime entity/options')
        triggers=(mob if path=='imported' else cfg['ai'])['triggers']
        pending='command{delay=80} "terminalprobe hook runtime pending <caster.uuid> <caster.var.Marker>" @self' if path=='imported' else 'command{c="terminalprobe hook runtime pending <caster.uuid> <caster.var.Marker>";delay=80} @self'
        expected={'on-spawn':{'do':[pending]}}
        if scenario=='control':expected['on-spawn']['do'].append('skill{s=tc_terminal;delay=10} @self')
        else:
            for event in ('despawn','death'):expected['on-'+event]={'do':['skill{s=tc_terminal} @self' if path=='imported' else 'cast-skill tc_terminal']}
        require(triggers==expected,'missing or changed mapped callback/negative control')
    for mode in ('never','persistent'):
        cfg=doc(payloads['imports/mythic_tc_child_'+mode+'.yml'])
        require(cfg['mob']['despawn']==mode.upper() and cfg['entity']['type']=='COW' and cfg['ai']['triggers']['on-spawn']['do']==[f'command "terminalprobe child runtime {mode.upper()} <caster.uuid>" @self'],'wrong summon child configuration')
    expected_skills={'tc_terminal':{'on-decision':[
        'command "terminalprobe hook runtime immediate <caster.uuid> <caster.var.Marker>" @self',
        'command{delay=1} "terminalprobe hook runtime delay1 <caster.uuid> <caster.var.Marker>" @self',
        'command{delay=10} "terminalprobe hook runtime delay10 <caster.uuid> <caster.var.Marker>" @self',
        'command{repeat=2;repeatinterval=5} "terminalprobe hook runtime repeat <caster.uuid> <caster.var.Marker>" @self',
        'skill{s=tc_nested;delay=10} @self']},'tc_nested':{'on-decision':[
        'command "terminalprobe hook runtime nested <caster.uuid> <caster.var.Marker>" @self',
        'summon mythic_tc_child_never 1 0 @self','summon mythic_tc_child_persistent 1 0 @self',
        'command "terminalprobe hook runtime afterchild <caster.uuid> <caster.var.Marker>" @self']}}
    require(doc(payloads['imports/skills.yml'])['skills']==expected_skills,'mapped delay/repeat/nested summon changed')
    text=payloads['server.log'].decode();lines=text.splitlines()
    for bad in ('TCALL_FAILED|','Error occurred while','Exception:','Variable checkpoint load failed','Variable checkpoint save failed','SkillTree unload save failed'):
        require(bad not in text,'failed diagnostic: '+bad)
    def marked(marker):return [(i,fields(l,marker)) for i,l in enumerate(lines) if marker in l]
    ids=unique(((r['key'],r['backend']),(i,r)) for i,r in marked('TCALL_ID|'))
    require(set(ids)==set(itertools.product(contract.CASES,BACKENDS)),'missing/duplicate parent identity')
    require(len({r['uuid'] for _,r in ids.values()})==40,'duplicate parent UUID')
    for (key,b),(_,r) in ids.items():require(str(uuid.UUID(r['uuid']))==r['uuid'] and (r['instance']=='0' if b=='native' else int(r['instance'])>0),'invalid identity')
    rows=marked('TCALL_ROW|');require(len(rows)==120 and report['observations']==[l for l in lines if 'TCALL_ROW|' in l],'report/log observations differ')
    rowmap=unique(((r['key'],r['backend'],r['phase']),(i,r)) for i,r in rows)
    require(set(rowmap)==set(itertools.product(contract.CASES,BACKENDS,('initial','settled','cleaned'))),'missing/duplicate observation')
    hooks=marked('TCALL_HOOK|');children=marked('TCALL_CHILD|');child_states=marked('TCALL_CHILD_STATE|')
    actions=unique((r['key'],(i,r)) for i,r in marked('TCALL_ACTION|'));seals=unique((r['key'],(i,r)) for i,r in marked('TCALL_SEAL|'))
    require(set(actions)==set(seals)==set(contract.CASES),'missing/duplicate scenario action or seal')
    for marker in ('TCALL_HOOK|','TCALL_CHILD|','TCALL_REQUEST|','TCALL_UNLOAD|','TCALL_COMMAND|'):
        for _,r in marked(marker):require((r['key'],r['backend']) in ids,'unknown event identity')
    child_ids=unique((r['uuid'],(i,r)) for i,r in children)
    require(len(child_ids)==48 and not set(child_ids)&{r['uuid'] for _,r in ids.values()},'missing/duplicate child UUID')
    require(len(hooks)==216 and len(child_states)==96,'missing/duplicate callback or child observations')
    gaps=[]
    for key,(scenario,kind,path) in contract.CASES.items():
        action_at,action=actions[key];action_tick=int(action['tick']);seal_at,seal=seals[key]
        require(action['scenario']==scenario and seal['accepted']=='true','wrong action or failed world seal')
        for b in BACKENDS:
            parent_at,parent=ids[(key,b)];initial_at,initial=rowmap[(key,b,'initial')];settled_at,settled=rowmap[(key,b,'settled')];clean_at,clean=rowmap[(key,b,'cleaned')]
            require(parent_at<initial_at<action_at<settled_at<clean_at<seal_at,'wrong lifecycle ordering')
            spawn_tick=int(parent['tick']);require(int(initial['tick'])==spawn_tick and int(settled['tick'])==action_tick+130 and int(clean['tick'])==action_tick+230,'wrong observation times')
            live=scenario=='control';child_count=2 if b=='native' or live else 0
            for phase,present,count in [('initial',True,0),('settled',live,child_count),('cleaned',False,0)]:
                _,r=rowmap[(key,b,phase)]
                require(r['uuid']==parent['uuid'] and r['entity']==r['registry']==flag(present) and r['marker']==('73' if present else 'absent'),'parent lost, resurrected or stale')
                require(r['children']==str(count) and r['visuals']==str((int(present)+count)*11 if b=='runtime' else 0),'missing/orphan children or visuals')
            hs=[(i,r) for i,r in hooks if r['key']==key and r['backend']==b]
            require(all(r['uuid']==parent['uuid'] and r['marker']=='73' and r['primary']=='true' and r['cleaning']=='false' and initial_at<i<settled_at for i,r in hs),'wrong callback context or cleanup replay')
            labels={name:[int(r['tick']) for _,r in hs if r['label']==name] for name in ('immediate','delay1','delay10','repeat','nested','afterchild','pending')}
            full=b=='native' or live
            require(len(labels['immediate'])==1,'terminal callback missing or replayed');start=labels['immediate'][0]
            if scenario=='control':require(start==spawn_tick+10 and action_tick==spawn_tick+25,'wrong positive-control timing')
            elif scenario in ('command','death','suspended'):require(action_tick<=start<=action_tick+(2 if b=='native' else 0),'terminal callback outside removal window')
            else:require(action_tick-1<=start<=action_tick+6,'chunk callback outside unload window')
            if full:
                require([len(labels[k]) for k in ('delay1','delay10','repeat','nested','afterchild')]==[1,1,3,1,1],'lost delayed/repeated/nested callback')
                for label,delay in [('delay1',1),('delay10',10),('nested',10),('afterchild',10)]:
                    delta=labels[label][0]-start
                    require(delta in ([delay-1,delay,delay+1] if b=='native' else [delay]),'wrong callback delay')
                require(all(abs(t-(start+5*i))<=(1 if b=='native' else 0) for i,t in enumerate(labels['repeat'])),'wrong repeat timing')
                require(labels['nested'][0]==labels['afterchild'][0],'nested actions split across ticks')
            else:
                require(all(not labels[k] for k in ('delay1','delay10','repeat','nested','afterchild')),'baseline terminal-gap behavior changed; reclassify with evidence')
                gaps.append(key)
            require(labels['pending']==([spawn_tick+80] if live else []),'ordinary pending work survives removal or positive control lost')
            require(len(hs)==(9 if live else 8 if b=='native' else 1),'unexpected callback label')
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
            req=[(i,r) for i,r in marked('TCALL_REQUEST|') if r['key']==key and r['backend']==b]
            unload=[(i,r) for i,r in marked('TCALL_UNLOAD|') if r['key']==key and r['backend']==b]
            cmds=[(i,r) for i,r in marked('TCALL_COMMAND|') if r['key']==key and r['backend']==b]
            require(len(req)==len(unload)==(1 if scenario in ('chunk','suspended') else 0),'missing/extra actual unload')
            if req:
                require(req[0][1]['accepted']=='true' and initial_at<req[0][0]<unload[0][0]<action_at and unload[0][1]['uuid']==parent['uuid'] and int(unload[0][1]['tick'])==action_tick-1,'incorrect unload boundary')
            require(len(cmds)==(1 if scenario in ('command','suspended') else 0),'missing/extra removal command')
            if cmds:require(cmds[0][1]['result']=='true' and cmds[0][1]['loaded']==flag(scenario=='command') and int(cmds[0][1]['tick'])==action_tick and action_at<cmds[0][0]<settled_at,'wrong command context')
    require(report['gapCases']==sorted(gaps),'omitted or relabeled compatibility gap')
    deaths=marked('TCALL_DEATH|');require(len(deaths)==8,'wrong real-death count')
    for at,r in deaths:
        key=r['key'];require(contract.CASES[key][0]=='death' and r['uuid'] in {ids[(key,b)][1]['uuid'] for b in BACKENDS} and r['tick']==actions[key][1]['tick'],'wrong death event')
    require(len({r['uuid'] for _,r in deaths})==8,'duplicate death event')
    summary=marked('TCALL_SUMMARY|');require(len(summary)==1 and summary[0][1]=={'rows':'120','cases':'20','completed':'true'} and max(i for i,_ in seals.values())<summary[0][0],'incomplete summary')
    require(text.count(']: Stopping server')==1 and next(i for i,l in enumerate(lines) if ']: Stopping server' in l)>summary[0][0],'premature shutdown')
    return len(rows)

def main(path):
    p=Path(path);archived=p.is_file();report=js((p if archived else p/'terminal-callback-report.json').read_text())
    if archived:
        name=report['logArchive'];require(Path(name).name==name and '\\' not in name,'unsafe archive');archive=p.parent/name;require(digest(archive.read_bytes())==report['logArchiveSha256'],'archive changed')
        with zipfile.ZipFile(archive) as z:
            names=z.namelist();require(len(names)==len(set(names)) and set(names)=={f['path'] for f in report['files']},'archive membership changed');payloads={n:z.read(n) for n in names}
    else:
        for name,key in [('paper.jar','paperJarSha256'),('plugins/helsteraMobs.jar','pluginSha256'),('plugins/MythicMobs.jar','mythicMobsSha256'),('plugins/HelsteraTerminalCallbackProbe.jar','probeJarSha256')]:require(digest((p/name).read_bytes())==report[key],'artifact changed')
        payloads={}
    seen=set()
    for f in report['files']:
        name=f['path'];require(not Path(name).is_absolute() and '..' not in Path(name).parts and '\\' not in name and name not in seen,'unsafe/duplicate member');seen.add(name)
        if not archived:payloads[name]=(p/name).read_bytes()
        require(digest(payloads[name])==f['sha256'],'member changed')
    print(f'Validated {validate(report,payloads)} observations, 40 parents, 48 children; 16 terminal callback cases remain incompatible.');return report,payloads
if __name__=='__main__':main(sys.argv[1])
