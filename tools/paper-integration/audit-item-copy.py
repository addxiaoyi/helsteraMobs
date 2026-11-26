"""Independent M04a inventory/metadata/private-packet evidence audit; observation only."""
from collections import Counter
import base64
import copy
import hashlib
import importlib.util
import json
from pathlib import Path, PurePosixPath
import re
import sys
import uuid
import yaml
sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[2]
TOOLS = ROOT / 'tools/paper-integration'
spec = importlib.util.spec_from_file_location('item_copy_cases', TOOLS / 'item-copy-cases.py')
c = importlib.util.module_from_spec(spec); spec.loader.exec_module(c)
PROBE = 'plugins/HelsteraItemCopyProbe/'
PATHS = ['native', 'direct', 'imported']
SLOTS = list(c.SLOTS)
MATERIALS = ['EMERALD', 'DIAMOND', 'AMETHYST_SHARD', 'LAPIS_LAZULI', 'IRON_INGOT', 'GOLD_INGOT']
SOURCE_FILES = {'tools/paper-integration/' + name for name in [
    'run-item-copy-fixture.py','run-player-variable-fixture.py','item-copy-cases.py','item-copy-client.js',
    'audit-item-copy.py','test-item-copy-audit.py',
    'random-spawn-fixture/src/main/java/dev/helstera/test/randomspawn/ItemCopyProbe.java',
    'random-spawn-fixture/src/main/java/dev/helstera/test/randomspawn/VariablePlaceholderAudit.java']}
SOURCE_FILES |= {'helstera-ai/src/main/java/dev/helstera/ai/skill/' + name + '.java' for name in
                 ['ItemCopyActions','FakeItemLoot','PaperFakeItemLoot','SkillService','SkillExtras','MythicSkillSpec']}
SOURCE_FILES |= {'helstera-api/src/main/java/dev/helstera/api/util/MythicItemCopySpec.java',
    'helstera-api/src/main/java/dev/helstera/api/behavior/BehaviorContext.java',
    'helstera-migration/src/main/java/dev/helstera/migration/importer/MythicMobsImporter.java',
    'helstera-migration/src/main/java/dev/helstera/migration/importer/MythicSpec.java'}

def require(value, label):
    if not value: raise ValueError(label)
def digest(value): return hashlib.sha256(value).hexdigest()
def unique(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result, 'duplicate field'); result[key] = value
    return result
class UniqueYaml(yaml.SafeLoader): pass
UniqueYaml.add_constructor(yaml.resolver.BaseResolver.DEFAULT_MAPPING_TAG,
                           lambda loader,node: unique(loader.construct_pairs(node, deep=True)))
def doc(data): return yaml.load(data, Loader=UniqueYaml)
def strict(actual, expected):
    if type(actual) is not type(expected): return False
    if isinstance(expected,dict): return actual.keys()==expected.keys() and all(strict(actual[k],v) for k,v in expected.items())
    if isinstance(expected,list): return len(actual)==len(expected) and all(strict(a,b) for a,b in zip(actual,expected))
    return actual == expected
def safe_path(raw):
    require(isinstance(raw,str) and raw and ':' not in raw and '\\' not in raw,'unsafe path')
    path=PurePosixPath(raw)
    require(not path.is_absolute() and '..' not in path.parts and str(path)==raw,'unsafe path')
    return raw

def stack(value):
    require(isinstance(value,dict),'stack object')
    if value.get('type')=='AIR':
        require(strict(value,{'type':'AIR','amount':0}),'air stack'); return
    require(set(value)=={'type','amount','fingerprint','data','name','lore','model','proof','damage'},'stack fields')
    require(type(value['amount']) is int and value['amount']>0,'stack amount')
    data=base64.b64decode(value['data'],validate=True)
    require(digest(data)==value['fingerprint'] and len(data)>10,'serialized metadata hash')
    require(isinstance(value['name'],str) and isinstance(value['lore'],list)
            and all(isinstance(v,str) for v in value['lore']),'metadata fields')
    require(type(value['model']) is int and type(value['damage']) is int and isinstance(value['proof'],str),'typed metadata')

def template(value,slot,sample):
    stack(value); i=SLOTS.index(slot)
    damageable=sample['variant']=='damageable' and slot=='HAND'
    require(value['type']==('DIAMOND_SWORD' if damageable else MATERIALS[i]),'template material '+slot)
    require(value['amount']==(1 if damageable else 7),'template amount')
    require(value['name']=='Copy-'+slot and value['lore']==['Line one','Line two']
            and value['model']==100+i and value['proof']=='private-meta-'+str(i)
            and value['damage']==(17 if damageable else 0),'template metadata '+slot)

def units(items):
    total=Counter()
    for entry in items:
        value=entry['item']; stack(value)
        total[(value['type'],value['fingerprint'])]+=value['amount']
    return total

def state(value):
    require(set(value)=={'caster','target','inventory','observerInventory','drops'},'state fields')
    for role in ('caster','target'):
        require(set(value[role]) in (set(),set(SLOTS)),'equipment slots')
        for item in value[role].values(): stack(item)
    for role in ('inventory','observerInventory'):
        slots=[]
        for entry in value[role]:
            require(set(entry)=={'slot','item'} and type(entry['slot']) is int and 0<=entry['slot']<36,'inventory slot')
            slots.append(entry['slot']); stack(entry['item'])
        require(slots==sorted(set(slots)),'inventory order')
    for entry in value['drops']:
        require(set(entry)=={'item','owner','thrower','canMobPickup','location','entityId'},'drop fields')
        stack(entry['item']); require(type(entry['entityId']) is int,'drop id')
        require(entry['owner']=='none' and entry['thrower']=='none' and entry['canMobPickup'] is True,'overflow ownership')

def check_row(row,sample,index,path,ids):
    fields={'index','path','case','source','mapped','primary','startTick','endTick','origin','receiverLocation',
            'caster','target','before','after','afterSourceMutation','settled','entityIds','result'}
    cleanup=sample['cleanup']=='world' or sample['cleanup']=='disable' and path!='native'
    if cleanup: fields.add('cleanupTick')
    require(set(row)==fields,'row fields')
    source=sample['mechanic']+'{'+sample['arguments']+'} @trigger'
    require((row['index'],row['path'],row['case'],row['source'])==(index,path,sample['key'],source),'case source')
    mapped=source if path!='imported' else c.canonical(sample['mechanic'])+source[len(sample['mechanic']):]
    require(row['mapped']==mapped and row['primary'] is True,'mapping/thread')
    require(row['result']==(sample['result'] if path=='native' else 'EXECUTED'),'execution result '+sample['key'])
    require(type(row['startTick']) is int and type(row['endTick']) is int and row['endTick']-row['startTick']==10,'phase tick span')
    if cleanup: require(row['cleanupTick']-row['startTick']==2,'cleanup tick')
    require(row['caster']==ids['receiver' if sample['source']=='player' else 'nonliving' if sample['source']=='nonliving' else 'caster']['uuid'],'caster identity')
    require(row['target']==ids['receiver' if sample['target'] in ('player','self') else 'nonliving' if sample['target']=='nonliving' else 'target']['uuid'],'target identity')
    require(row['origin']=={'world':row['origin']['world'],'x':112.0,'y':170.0,'z':0.0},'origin differs from caster')
    for field in ('before','after','afterSourceMutation','settled'): state(row[field])
    before,after=row['before'],row['after']
    require(before['drops']==[] and before['observerInventory']==[],'clean setup')
    if sample['copy']:
        if sample['target']!='nonliving':
            for slot in SLOTS:
                if sample['variant']=='air' and slot=='HEAD': require(before['target'][slot]=={'type':'AIR','amount':0},'air seed')
                else: template(before['target'][slot],slot,sample)
        expected=copy.deepcopy(before)
        if sample['source']!='nonliving' and sample['target']!='nonliving': expected['caster']=before['target']
        require(strict(after,expected),'copy direction/all slots/source isolation '+sample['key'])
    else:
        if sample['source']!='nonliving':
            for slot in SLOTS: template(before['caster'][slot],slot,sample)
        require(strict(after['caster'],before['caster']),'source consumed')
        # A selected player sees its HAND equipment change if the inventory's selected slot receives items.
        if sample['target'] not in ('player','self'): require(strict(after['target'],before['target']),'wrong target equipment')
        if sample['result']!='SUCCESS': require(strict(after,before),'invalid grant changed state')
        else:
            original=before['caster'][sample['slot']]
            total=units(after['inventory'])+units([{'item':drop['item']} for drop in after['drops']])
            expected=units(before['inventory']); expected[(original['type'],original['fingerprint'])]+=original['amount']
            require(total==expected,'item/metadata conservation '+sample['key'])
            overflow=original['amount'] if sample['fill']=='full' else max(0,original['amount']-2) if sample['fill']=='partial' else 0
            require(sum(drop['item']['amount'] for drop in after['drops'])==overflow,'overflow amount')
            require(len(after['drops'])==(1 if overflow else 0),'overflow entities')
            for drop in after['drops']:
                loc=drop['location']; base=row['receiverLocation']
                require(loc['world']==base['world'] and all(0<=loc[a]-base[a]<=1 for a in ('x','y','z')),'overflow location')
    require(after['observerInventory']==[],'observer was given items')
    mutated=row['afterSourceMutation']
    if sample['variant']=='mutate':
        expected=copy.deepcopy(after); expected['caster']['HAND']=mutated['caster']['HAND']
        item=mutated['caster']['HAND']
        require(item['amount']==1 and item['name']=='Changed source' and item['fingerprint']!=after['caster']['HAND']['fingerprint'],'source mutation boundary')
        require(strict(mutated,expected),'source metadata mutation leaked into copy')
    else: require(strict(mutated,after),'unexpected post-grant mutation')
    # Gravity was disabled for real overflow immediately after grant, so ten ticks cannot move it.
    require(strict(row['settled'],mutated),'settled item state changed')
    require(all(type(v) is int for v in row['entityIds']) and row['entityIds']==sorted(set(row['entityIds'])),'entity id set')

def packets(events,rows,ids):
    joins={event['role']:event for event in events if event.get('event')=='join'}
    require(set(joins)=={'receiver','observer'},'two real clients')
    for role,name in [('receiver','CopyReceiver'),('observer','CopyObserver')]:
        require(joins[role]['name']==name and joins[role]['uuid']==ids[role]['uuid']
                and joins[role]['entityId']==ids[role]['id'],'client identity')
    for row in rows:
        index=row['index']; sample=c.CASES[index%len(c.CASES)]
        phase=[e for e in events if e.get('index')==index]
        for role in ('receiver','observer'):
            own=[e for e in phase if e.get('role')==role]
            markers=[e for e in own if e.get('event')=='phase']
            require(len(markers)==1 and markers[0]['path']==row['path'] and markers[0]['case']==row['case'],'client phase')
            ticks=[e['tick'] for e in own if e.get('event')=='tick']
            require(ticks==list(range(11)),'client tick markers')
            require(len([e for e in own if e.get('event')=='end'])==1,'client end')
        raw=[e for e in phase if e.get('event')=='packet']
        actual={drop['entityId'] for drop in row['after']['drops']}
        fake_spawns=[e for e in raw if e['packetType']=='spawn_entity' and e['data']['entityId'] not in actual]
        require(len(fake_spawns)==(1 if sample['fake'] else 0),'fake item spawn count '+row['case'])
        if not sample['fake']: continue
        spawn=fake_spawns[0]; item_id=spawn['data']['entityId']
        require(spawn['role']=='receiver' and item_id not in row['entityIds'],'fake privacy/server entity boundary')
        require(all(spawn['data'][axis]==row['origin'][axis] for axis in ('x','y','z')),'fake origin')
        require(spawn['tick'] in (0,1),'fake spawn timing')
        related=[e for e in raw if e['data'].get('entityId')==item_id or e['data'].get('collectedEntityId')==item_id
                 or item_id in e['data'].get('entityIds',[])]
        require(all(e['role']=='receiver' for e in related),'observer received private packet')
        metadata=[entry for e in related if e['packetType']=='entity_metadata' for entry in e['data']['metadata'] if entry['key']==8]
        require(len(metadata)==1,'fake item metadata')
        item=metadata[0]['value']; require(item['itemCount']==7,'fake snapshot amount')
        require(item.get('addedComponentCount',len(item.get('components',[])))>0,'fake metadata components')
        collects=[e for e in related if e['packetType']=='collect']
        cancelled=sample['cleanup'] in ('disable','world') and row['path']!='native'
        require(len(collects)==(0 if cancelled else 1),'fake collect/cancel count')
        if collects:
            collect=collects[0]
            require(collect['tick'] in (4,5) and collect['data']['collectorEntityId']==ids['receiver']['id']
                    and collect['data']['pickupItemCount']==1,'five tick private collection')
        removes=[e for e in related if e['packetType']=='entity_destroy']
        if row['path']!='native' and sample['cleanup']!='world':
            require(len(removes)==1 and removes[0]['tick'] in ((2,) if sample['cleanup']=='disable' else (4,5)),'fake cleanup packet')
        if row['path']!='native':
            end=next(e for e in phase if e.get('event')=='end' and e['role']=='receiver')
            require(item_id not in end['trackedItemIds'],'fake entity survived cleanup')

def audit(report,files):
    require(report.get('schemaVersion')==1 and report.get('evidenceMode')=='observation'
            and report.get('parityStatus')=='unassessed','evidence scope')
    require(report.get('validationResult')=='passed' and type(report.get('exitCode')) is int and report['exitCode']==0
            and type(report.get('clientExitCode')) is int and report['clientExitCode']==0,'execution status')
    require(report.get('paperJarSha256')=='39bd8c00b9e18de91dcabd3cc3dcfa5328685a53b7187a2f63280c22e2d287b9','paper pin')
    require(report.get('mythicMobsSha256')=='9672457cafc56ff841606bfbb710947329c08720ffc207746b60130d43bf902f','native pin')
    for key in ('pluginSha256','probeJarSha256'): require(re.fullmatch('[a-f0-9]{64}',report.get(key,'')),key)
    require(set(report.get('sourceHashes',{}))==SOURCE_FILES and all(re.fullmatch('[a-f0-9]{64}',v) for v in report['sourceHashes'].values()),'source manifest')
    n=len(c.CASES)*3
    names={'server.log','client.log','plugins/MythicMobs/Mobs/item-copy.yml',PROBE+'cases.yml',PROBE+'identities.yml'}
    names.update(PROBE+f'observations/{i:04d}.yml' for i in range(n))
    require(set(files)==names and len(report.get('files',[]))==len(names),'archive membership')
    manifest=unique((safe_path(e['path']),e['sha256']) for e in report['files'])
    require(set(manifest)==names,'manifest membership')
    for name,data in files.items(): require(digest(data)==manifest[name],'file digest '+name)
    require(strict(doc(files[PROBE+'cases.yml']),c.source_cases()),'case source')
    require(doc(files['plugins/MythicMobs/Mobs/item-copy.yml'])=={'HelsteraRadiusAuditMob':{
        'Type':'COW','Options':{'NoAI':True,'Despawn':'PERSISTENT'}}},'native mob source')
    ids=doc(files[PROBE+'identities.yml']); require(set(ids)=={'caster','target','nonliving','receiver','observer'},'identities')
    for identity in ids.values():
        require(set(identity)=={'uuid','id'} and type(identity['id']) is int,'identity fields'); uuid.UUID(identity['uuid'])
    require(len({v['uuid'] for v in ids.values()})==5 and len({v['id'] for v in ids.values()})==5,'identity collisions')
    log=files['server.log'].decode()
    require(log.count(f'ITEM_COPY_SUMMARY|observations={n}|completed=true')==1 and 'ITEM_COPY_FAILED|' not in log,'completion')
    require(log.index('ITEM_COPY_SUMMARY|')<log.index(']: Stopping server'),'shutdown ordering')
    logged=Counter(re.findall(r'ITEM_COPY_ROW\|index=(\d+)\|path=([^|\r\n]+)\|case=([^|\r\n]+)\|result=([^\r\n]+)',log))
    require(sum(logged.values())==n and report.get('observationCount')==n,'observation count')
    rows=[]
    for i,(path,sample) in enumerate((path,sample) for path in PATHS for sample in c.CASES):
        row=doc(files[PROBE+f'observations/{i:04d}.yml']); check_row(row,sample,i,path,ids); rows.append(row)
        require(logged[(str(i),path,sample['key'],row['result'])]==1,'raw row log')
    events=[json.loads(line.split('ITEM_COPY_CLIENT ',1)[1],object_pairs_hook=unique)
            for line in files['client.log'].decode().splitlines() if 'ITEM_COPY_CLIENT ' in line]
    require(not any(e.get('event') in ('error','packet-error') for e in events),'client packet failure')
    summaries=[e for e in events if e.get('event')=='summary']
    require(len(summaries)==1 and summaries[0].get('passed') is True,'client completion')
    packets(events,rows,ids)
    return n

def load(path):
    report=json.loads(Path(path).read_text(),object_pairs_hook=unique)
    name=safe_path(report['logArchive']); require(PurePosixPath(name).name==name,'archive path')
    data=(Path(path).parent/name).read_bytes(); require(digest(data)==report['logArchiveSha256'],'archive digest')
    import io,zipfile
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        names=archive.namelist(); require(len(names)==len(set(names)),'duplicate archive member')
        files={safe_path(name):archive.read(name) for name in names}
    return report,files

if __name__=='__main__':
    report,files=load(sys.argv[1]); count=audit(report,files)
    if '--check-current' in sys.argv:
        for name,value in report['sourceHashes'].items(): require(digest((ROOT/name).read_bytes())==value,'current source '+name)
        require(digest((ROOT/'helstera-plugin/target/helsteraMobs-1.0.0-full.jar').read_bytes())==report['pluginSha256'],'current plugin')
    print(f'Item copy audit passed: {count} real observations; M04a only, no M04b parity claim.')
