"""Independent scoped generated-loot server and real-client evidence checks."""
from collections import Counter
from pathlib import Path
import argparse
import base64
import hashlib
import importlib.util
import json
import zipfile
import yaml

ROOT = Path(__file__).resolve().parents[2]
TOOLS = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('generated_cases', TOOLS / 'generated-loot-cases.py')
c = importlib.util.module_from_spec(spec); spec.loader.exec_module(c)
PROBE = 'plugins/HelsteraGeneratedLootProbe/'
ITEMS = {'DIAMOND':805, 'EMERALD':806, 'STONE':1}

def require(value, message):
    if not value: raise ValueError(message)

def digest(value): return hashlib.sha256(value).hexdigest()

def inventory(values):
    require(isinstance(values,list), 'inventory list')
    slots=[]; counts=Counter()
    for entry in values:
        require(set(entry)=={'slot','type','amount','model','data'}, 'inventory fields')
        require(type(entry['slot']) is int and 0<=entry['slot']<36, 'storage slot')
        require(type(entry['amount']) is int and 1<=entry['amount']<=64 and entry['type'] in ITEMS, 'stack')
        require(type(entry['model']) is int and len(base64.b64decode(entry['data'],validate=True))>10,'item metadata evidence')
        slots.append(entry['slot']); counts[entry['type']]+=entry['amount']
    require(slots==sorted(set(slots)), 'unique ordered inventory')
    return counts

def check_row(row, case, index, path, ids):
    require(set(row)=={'index','case','path','source','mapped','caster','target','origin','casterLocation',
                      'before','after','settled','startTick','endTick','primary','result','entityIds'}, 'row fields')
    source=case['mechanic']+'{'+case['arguments']+'} @'+('origin' if case['target']=='location' else 'trigger')
    mapped=source if path!='imported' else 'give-item-from-target'+source[len(case['mechanic']):]
    require((row['index'],row['case'],row['path'],row['source'],row['mapped'])==(index,case['key'],path,source,mapped),'row identity/mapping')
    require(row['primary'] is True and row['result']==('SUCCESS' if path=='native' else 'EXECUTED'),'invocation')
    require(type(row['startTick']) is int and row['endTick']-row['startTick']==10, 'tick span')
    caster=ids['cow' if case['source']=='cow' else 'receiver']['uuid']
    target=ids[{'cow':'targetCow','nonliving':'nonliving'}.get(case['target'],'observer')]['uuid']
    require((row['caster'],row['target'])==(caster,target),'caster/target direction')
    positions={'observer':(112,170,0),'location':(113,170,3),'nonliving':(114,170,3),'cow':(110,170,4)}
    require(tuple(row['origin'][axis] for axis in ('x','y','z'))==positions[case['target']],'animation origin')
    require(tuple(row['casterLocation'][axis] for axis in ('x','y','z'))==(110,170,0),'caster location')
    require(row['origin']['world']==row['casterLocation']['world'],'origin world')
    for label in ('before','after','settled'):
        state=row[label]
        require(set(state)=={'receiver','observer','xp','observerXp','score','observerScore','op','drops'},'state fields')
        receiver=inventory(state['receiver']); require(inventory(state['observer'])=={},'observer inventory')
        require(state['observerXp']==state['observerScore']==0 and state['op'] is False,'observer/permissions unchanged')
        for key in ('xp','score'): require(type(state[key]) is int, 'numeric state')
        drops=Counter()
        for drop in state['drops']:
            require(set(drop)=={'entityId','type','amount','owner','thrower','canMobPickup','location','model','data'},'drop fields')
            require(drop['model']==case.get('model',0) and len(base64.b64decode(drop['data'],validate=True))>10,'overflow metadata')
            require(drop['owner']==drop['thrower']==ids['receiver']['uuid'] and drop['canMobPickup'] is False,'overflow ownership')
            require(type(drop['amount']) is int and 0<drop['amount']<=64 and drop['type'] in ITEMS,'overflow stack')
            require(type(drop['entityId']) is int and drop['entityId'] in row['entityIds'],'physical overflow entity')
            require(drop['location']['world']==row['casterLocation']['world'],'overflow world')
            require(all(abs(drop['location'][axis]-row['casterLocation'][axis])<=1.5 for axis in ('x','y','z')),'overflow location')
            drops[drop['type']]+=drop['amount']
        seed=Counter() if case['inventory']=='empty' else Counter(STONE=64*36)
        if case['inventory']=='partial': seed=Counter(STONE=64*35,DIAMOND=62)
        if label=='before':
            require(receiver==seed and not drops and state['xp']==state['score']==0,'clean initial state')
        else:
            for entry in state['receiver']:
                require(entry['model']==(case.get('model',0) if entry['type']!='STONE' else 0),'inventory metadata')
            require(receiver+drops==seed+Counter(case['expected']),'reward conservation '+case['key'])
            require(state['xp']==case['xp'] and state['score']==case['score'],'xp/command '+case['key'])
            expected_overflow=Counter(case['expected']) if case['inventory']=='full' else Counter(DIAMOND=3) if case['inventory']=='partial' else Counter()
            require(drops==expected_overflow,'overflow amount')
    require(row['after']==row['settled'],'post-grant state changed')

def clients(events, rows, ids):
    require(not any(e['event'] in ('packet-error','world-reset') for e in events),'client decode/world failure')
    summaries=[e for e in events if e['event']=='summary']
    require(len(summaries)==1 and summaries[0]['passed'] is True and summaries[0]['error'] is None
            and summaries[0]['doneRoles']==['observer','receiver'],'two-client completion')
    for role in ('receiver','observer'):
        joined=[e for e in events if e['event']=='join' and e.get('role')==role]
        require(len(joined)==1 and joined[0]['uuid']==ids[role]['uuid'] and joined[0]['entityId']==ids[role]['entityId'],'client identity')
        require(len([e for e in events if e['event']=='done' and e.get('role')==role])==1,'client done')
    for row in rows:
        index=row['index']; case=c.CASES[index//3]
        phase=[e for e in events if e.get('index')==index]
        for role in ('receiver','observer'):
            selected=[e for e in phase if e.get('role')==role]
            starts=[e for e in selected if e['event']=='phase']; ends=[e for e in selected if e['event']=='end']
            require(len(starts)==len(ends)==1,'client phase/end')
            require(all((e['path'],e['case'])==(row['path'],case['key']) for e in selected),'client phase identity')
            require([e['tick'] for e in selected if e['event']=='tick']==list(range(11)),'client ticks')
            if role=='receiver':
                def model(item): return next((comp['data'] for comp in item.get('components',[]) if comp['type']=='custom_model_data'),0)
                actual=sorted((e['slot'],e['name'].upper(),e['count'],model(e)) for e in ends[0]['inventory'])
                expected=sorted((e['slot']+36 if e['slot']<9 else e['slot'],e['type'],e['amount'],e['model']) for e in row['settled']['receiver'])
                require(actual==expected,'client inventory agrees with server')
            packets=[e for e in selected if e['event']=='packet']
            spawns=[e for e in packets if e['packetType']=='spawn_entity' and e['data']['entityId'] not in row['entityIds']]
            require(len(spawns)==(1 if case['fake'] and role=='receiver' else 0),'private visual count')
            for spawn in spawns:
                data=spawn['data']; entity_id=data['entityId']; wanted=next(iter(case['expected']))
                require(data['type']==58 and all(data[axis]==row['origin'][axis] for axis in ('x','y','z')),'visual spawn position')
                related=[e for e in packets if e['data'].get('entityId')==entity_id or e['data'].get('collectedEntityId')==entity_id
                         or entity_id in e['data'].get('entityIds',[])]
                metadata=[m['value'] for e in related if e['packetType']=='entity_metadata' for m in e['data']['metadata'] if m['key']==8]
                require(len(metadata)==1 and metadata[0]['itemId']==ITEMS[wanted]
                        and metadata[0]['itemCount']==case['expected'][wanted],'visual stack')
                actual_model=next((comp['data'] for comp in metadata[0].get('components',[]) if comp['type']=='custom_model_data'),0)
                require(actual_model==case.get('model',0),'visual metadata')
                collect=[e for e in related if e['packetType']=='collect']
                require(len(collect)==1 and collect[0]['tick'] in (4,5)
                        and collect[0]['data']['collectorEntityId']==ids['receiver']['entityId']
                        and collect[0]['data']['pickupItemCount']==1,'five-tick collection')
                if row['path']!='native':
                    require(entity_id not in ends[0]['phaseTrackedItemIds'],'visual cleanup')
                require(not any(e.get('role')=='observer' and (e.get('data',{}).get('entityId')==entity_id
                            or e.get('data',{}).get('collectedEntityId')==entity_id
                            or entity_id in e.get('data',{}).get('entityIds',[])) for e in events),'observer privacy')

def audit(report, files):
    require(report['evidenceMode']=='observation' and report['parityStatus']=='unassessed','claim scope')
    require(report['exitCode']==report['clientExitCode']==0,'process completion')
    require(report['paperJarSha256']=='39bd8c00b9e18de91dcabd3cc3dcfa5328685a53b7187a2f63280c22e2d287b9','pinned Paper')
    require(report['mythicMobsSha256']=='9672457cafc56ff841606bfbb710947329c08720ffc207746b60130d43bf902f','pinned native build')
    require(report['observationCount']==len(c.CASES)*3,'observation count')
    require(set(files)=={f['path'] for f in report['files']},'file inventory')
    for file in report['files']: require(digest(files[file['path']])==file['sha256'],'file hash')
    require(yaml.safe_load(files[PROBE+'cases.yml'])==c.source_cases(),'case definition')
    require(yaml.safe_load(files['plugins/MythicMobs/DropTables/generated-loot.yml'])==c.TABLES,'native table definition')
    require(yaml.safe_load(files['plugins/MythicMobs/Items/generated-loot.yml'])==c.ITEM_DEFINITIONS,'native item definition')
    ids=yaml.safe_load(files[PROBE+'identities.yml']); rows=[]
    paths=sorted(p for p in files if p.startswith(PROBE+'observations/'))
    require(len(paths)==len(c.CASES)*3,'row files')
    for index,path in enumerate(paths):
        row=yaml.safe_load(files[path]); check_row(row,c.CASES[index//3],index,('native','direct','imported')[index%3],ids); rows.append(row)
    log=files['server.log'].decode()
    require('GENERATED_LOOT_FAILED|' not in log and f'GENERATED_LOOT_SUMMARY|observations={len(rows)}|completed=true' in log,'server completion')
    client_log=files['client.log'].decode()
    events=[json.loads(line[len('ITEM_COPY_CLIENT '):]) for line in client_log.splitlines() if line.startswith('ITEM_COPY_CLIENT ')]
    clients(events,rows,ids)
    return rows

def read_report(path, check_current=False):
    report=json.loads(path.read_text()); archive=path.with_name(report['logArchive'])
    require(digest(archive.read_bytes())==report['logArchiveSha256'],'archive hash')
    with zipfile.ZipFile(archive) as packed:
        require(len(packed.namelist())==len(set(packed.namelist())),'duplicate archive member')
        files={name:packed.read(name) for name in packed.namelist()}
    if check_current:
        require(digest((ROOT/'helstera-plugin/target/helsteraMobs-1.0.0-full.jar').read_bytes())==report['pluginSha256'],'current artifact')
        for name,expected in report['sourceHashes'].items(): require(digest((ROOT/name).read_bytes())==expected,'current source '+name)
    return report,files

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('report',type=Path);parser.add_argument('--check-current',action='store_true')
    args=parser.parse_args();report,files=read_report(args.report,args.check_current)
    rows=audit(report,files);print('passed',len(rows),'generated-loot observations')
