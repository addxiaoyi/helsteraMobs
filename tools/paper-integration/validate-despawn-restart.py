"""Independently check native/runtime entities, UUID state, lazy load and disk tombstones."""
from datetime import datetime
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
STAGES={'write':('write',),'verify':('verify','changed'),'final':('final','dead')}
RETAINED={'NEVER','PERSISTENT'}
def flag(v):return str(v).lower()
def doc(text):return yaml.load(text,Loader=registry.UniqueYaml)

def validate(report,texts):
    require(report['schemaVersion']==1 and report['evidenceMode']=='comparison-with-exception','wrong schema/mode')
    require(report['knownExceptions']==['MM-5.7.2-shutdown-command-scheduling'],'undocumented exception')
    require(report['paperJarSha256']=='39bd8c00b9e18de91dcabd3cc3dcfa5328685a53b7187a2f63280c22e2d287b9','wrong Paper')
    require(report['mythicMobsSha256']=='9672457cafc56ff841606bfbb710947329c08720ffc207746b60130d43bf902f','wrong MM')
    require(all(re.fullmatch('[0-9a-f]{64}',report[k]) for k in ('pluginSha256','probeJarSha256')),'missing artifact')
    require(len(report['sourceHashes'])>=7 and all(re.fullmatch('[0-9a-f]{64}',v) for v in report['sourceHashes'].values()),'missing source hashes')
    boots=report['boots'];require([b['stage'] for b in boots]==list(STAGES),'missing/reordered boots')
    previous=None
    for b in boots:
        start,end=(datetime.fromisoformat(b[k]) for k in ('startedAtUtc','stoppedAtUtc'))
        require(b['exitCode']==0 and start<end and (previous is None or previous<start),'unclean/overlapping process boots');previous=end
    ids=doc(texts['identities.yml']);keys={f'{m.lower()}_{p}':(m,p,i) for i,(m,p) in enumerate(itertools.product(MODES,PATHS))}
    require(set(ids)=={'world'}|set(keys),'wrong identity set')
    all_ids={ids[k][b] for k in keys for b in ('native','runtime')}
    require(len(all_ids)==20 and all(re.fullmatch('[0-9a-f-]{36}',v) for v in all_ids|{ids['world']}),'invalid/duplicate identity')
    expected_files={'identities.yml'}|{s+'.server.log' for s in STAGES}|{f'snapshots/{s}/scoped.yml' for s in STAGES}
    expected_files|={f'snapshots/{s}/entity-{ids[k]["runtime"]}.yml' for s in ('write','verify') for k,(m,p,_) in keys.items() if m in RETAINED and (s=='write' or p=='imported')}
    # Numeric snapshots are legacy in-process files, not proof of UUID restoration.
    extra=set(texts)-expected_files
    require(expected_files<=set(texts) and all(re.fullmatch(r'snapshots/(write|verify|final)/\d+\.yml',p) for p in extra),'wrong evidence files')
    observations=[];deaths=set();identities=[];mappings=[]
    for stage,phases in STAGES.items():
        text=texts[stage+'.server.log'];lines=text.splitlines()
        def marked(marker):return [(i,fields(l,marker)) for i,l in enumerate(lines) if marker in l]
        require('DRESTART_FAILED|' not in text and text.count('DRESTART_SUMMARY|')==1,'failed/incomplete stage')
        summary=marked('DRESTART_SUMMARY|')[0];require(summary[1]=={'stage':stage,'rows':str(len(phases)*10),'completed':'true'},'wrong stage count')
        identities += [r for _,r in marked('DRESTART_ID|')];mappings += [r for _,r in marked('DRESTART_MAPPING|')]
        rows=marked('DRESTART_ROW|');require(len(rows)==10*len(phases),'missing rows')
        observations += [l for l in lines if 'DRESTART_ROW|' in l]
        require([(r['phase'],r['key']) for _,r in rows]==list(itertools.product(phases,keys)),'wrong phase/key order')
        for i,r in rows:
            phase,key=r['phase'],r['key'];mode,path,index=keys[key]
            alive=phase=='write' or mode in RETAINED and (phase=='verify' or phase in ('changed','final') and path=='imported')
            original=alive and phase in ('write','verify');updated=alive and not original
            point=f'despawn-restart,{(300+index*4)*16+3.25},110.0,4802.75'
            expected={'nativeEntity':flag(alive),'runtimeEntity':flag(alive),'nativeRegistry':flag(alive),'runtimeInstance':flag(alive),
                'tree':str(23+index) if original else 'absent','visuals':'11' if alive else '0','forced':flag(alive and mode=='PERSISTENT')}
            for backend in ('native','runtime'):
                expected.update({backend+'Saved':str(7+index) if original else 'absent',backend+'Transient':'99' if alive else 'absent',
                    backend+'Updated':str(31+index) if updated else 'absent',backend+'Point':point if original else 'absent'})
            for k,v in expected.items():require(r[k]==v,f'wrong {key}/{phase}/{k}: {r[k]} != {v}')
            require(i<summary[0],'row after summary')
        cold=marked('DRESTART_COLD|')
        require(len(cold)==(0 if stage=='write' else 2),'missing lazy-load controls')
        if cold:
            require({r['key'] for _,r in cold}=={'never_direct','never_imported'},'duplicate cold control')
            for i,r in cold:
                require(r['stage']==stage and all(r[k]=='false' for k in ('loaded','forced','nativeEntity','runtimeEntity','runtimeInstance')),'NEVER eagerly loaded')
                require(i<rows[0][0],'cold control after reading chunk')
        for i,r in marked('DRESTART_EVENT|'):
            key,backend=r['key'],r['backend'];require(key in keys and backend in ('native','runtime'),'unknown event')
            require(r['uuid']==ids[key][backend] and r['stage']==stage,'event identity changed')
            if r['event']=='death':
                expected_stage='verify' if keys[key][1]=='direct' else 'final'
                require(keys[key][0] in RETAINED and stage==expected_stage and (key,backend) not in deaths,'extra/duplicate death')
                deaths.add((key,backend));before=next(j for j,v in rows if v['key']==key and v['phase']==stage)
                after=next(j for j,v in rows if v['key']==key and v['phase']==phases[-1])
                require(before<i<after,'death outside lifecycle')
        if stage=='verify':
            for key,(mode,_,_) in keys.items():
                if mode!='NEVER':continue
                low=next(i for i,r in cold if r['key']==key);high=next(i for i,r in rows if r['key']==key and r['phase']=='verify')
                for backend in ('native','runtime'):
                    require(len([r for i,r in marked('DRESTART_EVENT|') if low<i<high and r['key']==key and r['backend']==backend and r['event']=='load'])==1,'missing lazy entity load')
        # Explicit native scheduling exception; runtime terminal callbacks still execute once.
        shutdown_callbacks=re.findall(r'DRESTART_HOOK (native|runtime) despawn (\w+)',text)
        stop=[i for i,l in enumerate(lines) if ']: Stopping server' in l]
        disabled=[i for i,l in enumerate(lines) if '[MythicMobs] Disabling MythicMobs v5.7.2-' in l]
        require(len(stop)==len(disabled)==1 and summary[0]<stop[0]<disabled[0],'missing/out-of-order shutdown')
        exception='org.bukkit.plugin.IllegalPluginAccessException: Plugin attempted to register task while disabled'
        errors=[i for i,l in enumerate(lines) if exception in l]
        if stage=='write':
            expected={('runtime',k) for k,(m,_,_) in keys.items() if m not in RETAINED}
            require(len(shutdown_callbacks)==6 and set(shutdown_callbacks)==expected,'shutdown callbacks lost/duplicated')
            require(all(i>stop[0] for i,l in enumerate(lines) if 'DRESTART_HOOK runtime despawn ' in l),'despawn callback before shutdown')
            require(len(errors)==6 and all(i>disabled[0] for i in errors),'native shutdown exception missing/misplaced')
            # Native worker traces can interleave; associate each stack with its own thread.
            for i in errors:
                prefix=re.search(r'\[(lumine-scheduler-\d+)/WARN\]:',lines[i])
                require(prefix is not None,'shutdown exception from another plugin/thread')
                stack=[]
                for line in lines[i+1:]:
                    if prefix[0] not in line:continue
                    if exception in line:break
                    if '\tat ' not in line:break
                    stack.append(line)
                for frame in ('org.bukkit.craftbukkit.scheduler.CraftScheduler.validate',
                              'MythicMobs.jar//io.lumine.mythic.bukkit.utils.tasks.LumineExecutors$BukkitSyncExecutor.execute',
                              'MythicMobs.jar//io.lumine.mythic.core.skills.SkillMechanic.runMechanic'):
                    require(any(frame in line for line in stack),'shutdown exception lacks native scheduling stack')
        else:
            require(not shutdown_callbacks,'retained mob emitted despawn during death/restart')
            require(not errors,'unexpected shutdown exception in retained/dead stage')
        require('Error occurred while disabling helsteraMobs' not in text,'runtime shutdown failed')
        # Post-restart death callbacks are native parity evidence.
        if stage!='write':
            callbacks=re.findall(r'DRESTART_HOOK (native|runtime) death (\w+)',text)
            path='direct' if stage=='verify' else 'imported'
            expected={(b,m.lower()+'_'+path) for b,m in itertools.product(('native','runtime'),('NEVER','PERSISTENT'))}
            require(len(callbacks)==4 and set(callbacks)==expected,'missing/duplicate death callback')
        scoped=doc(texts[f'snapshots/{stage}/scoped.yml']);require(scoped['registry-format']==1,'wrong registry schema')
        scopes=unique((v['scope'],v) for v in scoped['registries'].values())
        retained={k for k,(m,p,_) in keys.items() if m in RETAINED and (stage=='write' or stage=='verify' and p=='imported')}
        require(set(scopes)=={'entity:'+ids[k]['runtime'] for k in retained},'orphan/lost shutdown UUID registry')
        for key in retained:
            index=keys[key][2];entry=scopes['entity:'+ids[key]['runtime']];snapshot=entry['snapshot']
            require(snapshot['snapshot-format']==4,'wrong snapshot format')
            values=unique((v['name'],v['value']) for v in snapshot['variables']['entries'].values())
            want={'Transient':{'type':'INTEGER','value':99},('Saved' if stage=='write' else 'Updated'):{'type':'INTEGER','value':(7 if stage=='write' else 31)+index}}
            require(values==want,'wrong saved scalar values')
            points=list(snapshot['locations']['entries'].values())
            want_points=[{'name':'Point','world':'despawn-restart','x':(300+index*4)*16+3.25,'y':110.,'z':4802.75,'yaw':0.,'pitch':0.}] if stage=='write' else []
            require(points==want_points,'wrong saved location')
            require(list(entry['variable-retention'].values())==[{'name':'Transient','expires-at':1}] and entry['location-retention']=={},'wrong retention markers')
            tree=doc(texts[f'snapshots/{stage}/entity-{ids[key]["runtime"]}.yml'])
            require(tree['snapshot-format']==4,'wrong tree schema')
            require(list(tree['variables']['entries'].values())==([{'name':'RestartTree','value':{'type':'INTEGER','value':23+index}}] if stage=='write' else []),'old tree resurrected or tree missing')
    require(observations==report['observations'] and len(observations)==50,'report/log rows differ')
    require(len(identities)==10 and {r['key'] for r in identities}==set(keys),'missing/duplicate identity log')
    for r in identities:
        key=r['key'];mode,path,index=keys[key]
        require(r=={'key':key,'mode':mode,'path':path,'native':ids[key]['native'],'runtime':ids[key]['runtime'],'world':ids['world'],'chunk':f'{300+index*4},300'},'identity metadata mismatch')
    require(len(mappings)==10 and {r['key'] for r in mappings}==set(keys) and all(r['mode']==keys[r['key']][0] and r['trigger']=='true' for r in mappings),'bad mapping')
    require(len(deaths)==8,'missing real deaths')
    return 50

def main(path):
    root=Path(path);archived=root.is_file();report=json.loads((root if archived else root/'despawn-restart-report.json').read_text(),object_pairs_hook=unique)
    if archived:
        name=report['logArchive'];require(Path(name).name==name and '\\' not in name,'unsafe archive')
        p=root.parent/name;require(hashlib.sha256(p.read_bytes()).hexdigest()==report['logArchiveSha256'],'archive changed')
        with zipfile.ZipFile(p) as z:
            names=z.namelist();require(len(names)==len(set(names)) and set(names)=={f['path'] for f in report['files']},'archive membership changed')
            payloads={n:z.read(n) for n in names}
    else:
        for name,key in [('paper.jar','paperJarSha256'),('plugins/MythicMobs.jar','mythicMobsSha256'),('plugins/helsteraMobs.jar','pluginSha256'),('plugins/HelsteraDespawnRestartProbe.jar','probeJarSha256')]:
            require(hashlib.sha256((root/name).read_bytes()).hexdigest()==report[key],'artifact changed')
    texts={}
    for entry in report['files']:
        name=entry['path'];require(not Path(name).is_absolute() and '..' not in Path(name).parts and '\\' not in name and name not in texts,'unsafe/duplicate member')
        data=payloads[name] if archived else (root/name).read_bytes();require(hashlib.sha256(data).hexdigest()==entry['sha256'],'member changed');texts[name]=data.decode('utf-8-sig')
    print(f'Validated {validate(report,texts)} restart observations, three boots, lazy NEVER loads, eight deaths and six UUID snapshots.')
    return report,texts

if __name__=='__main__':main(sys.argv[1])
