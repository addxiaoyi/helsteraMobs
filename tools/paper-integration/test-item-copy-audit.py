"""Independent M04a negative controls; synthetic selfcheck never creates live reports."""
import base64
import copy
import functools
import importlib.util
import json
from pathlib import Path
import sys
import uuid
import yaml
sys.dont_write_bytecode=True
spec=importlib.util.spec_from_file_location('item_copy_audit',Path(__file__).with_name('audit-item-copy.py'))
v=importlib.util.module_from_spec(spec);spec.loader.exec_module(v)
v.doc=functools.lru_cache(maxsize=16384)(v.doc)

def rehash(report,files): report['files']=[{'path':name,'sha256':v.digest(data)} for name,data in sorted(files.items())]
def rejected(action):
    try: action()
    except (ValueError,KeyError,TypeError,AttributeError): return
    raise AssertionError('Tampered item-copy evidence accepted')

def test(report,files,client_only=False):
    assert v.audit(report,files)==len(v.c.CASES)*3
    # doc() caches immutable parsed blobs. Retain successful argument objects
    # so their IDs cannot be reused, and skip only identical, already validated
    # row inputs. Every altered blob still gets a fresh semantic validation;
    # full manifest, log, client inventory and packet checks still run each time.
    validate_row=v.check_row
    validated={}
    def cached_row(row,sample,index,path,ids):
        key=(id(row),id(sample),index,path,id(ids))
        if key not in validated:
            validate_row(row,sample,index,path,ids)
            validated[key]=(row,sample,ids)
    v.check_row=cached_row
    count=0
    def mutate(name,data):
        nonlocal count
        r=copy.deepcopy(report);f=dict(files);f[name]=data;rehash(r,f)
        try: rejected(lambda:v.audit(r,f))
        except AssertionError as error: raise AssertionError('Accepted mutation in '+name) from error
        count+=1
    for name,data in files.items():
        if client_only or '/observations/' not in name: continue
        row=v.doc(data)
        for field in row:
            bad=copy.deepcopy(row);bad[field]='tampered';mutate(name,yaml.safe_dump(bad).encode())
        for state in ('before','after','afterSourceMutation','settled'):
            for role in ('caster','target'):
                for slot,item in row[state][role].items():
                    for field in ('amount','fingerprint','proof'):
                        if field not in item:continue
                        bad=copy.deepcopy(row);bad[state][role][slot][field]='tampered'
                        mutate(name,yaml.safe_dump(bad).encode())
            if row[state]['inventory']:
                bad=copy.deepcopy(row);bad[state]['inventory'][0]['item']['amount']+=1
                mutate(name,yaml.safe_dump(bad).encode())
            if row[state]['drops']:
                for field,value in [('owner','owned'),('thrower','owned'),('canMobPickup',False)]:
                    bad=copy.deepcopy(row);bad[state]['drops'][0][field]=value
                    mutate(name,yaml.safe_dump(bad).encode())
    events=[json.loads(line.split('ITEM_COPY_CLIENT ',1)[1]) for line in files['client.log'].decode().splitlines() if 'ITEM_COPY_CLIENT ' in line]
    physical={row['index']:{drop['entityId'] for drop in row['after']['drops']}
              for name,data in files.items() if '/observations/' in name for row in [v.doc(data)]}
    def client(changed): mutate('client.log',('\n'.join('ITEM_COPY_CLIENT '+json.dumps(e) for e in changed)+'\n').encode())
    for i,event in enumerate(events):
        if event.get('event')=='packet' and event['packetType']=='collect':
            for field,value in [('pickupItemCount',7),('collectorEntityId',-1)]:
                changed=copy.deepcopy(events);changed[i]['data'][field]=value;client(changed)
            changed=copy.deepcopy(events);changed[i]['tick']=3;client(changed)
        if (event.get('event')=='packet' and event['packetType']=='spawn_entity'
                and event['data']['entityId'] not in physical.get(event['index'],set())):
            # Real overflow entities are public. Privacy/origin mutations apply
            # only to the synthetic item whose ID is absent from server state.
            changed=copy.deepcopy(events);changed[i]['role']='observer';client(changed)
            changed=copy.deepcopy(events);changed[i]['data']['x']+=1;client(changed)
    for name in files:
        if '/observations/' not in name: mutate(name,b'{}\n')
    for field,value in [('validationResult','rejected'),('exitCode',True),('clientExitCode',1),
                        ('parityStatus','passed'),('sourceHashes',{}),('observationCount',0)]:
        bad=copy.deepcopy(report);bad[field]=value;rejected(lambda:v.audit(bad,files));count+=1
    scope='client/archive' if client_only else 'full'
    print(f'Rejected {count} item-copy {scope} evidence mutations after rehashing.')

def fake_stack(material,amount=7,slot=None,changed=False,damage=0):
    raw=('synthetic-in-memory-only:'+material+str(slot)+str(changed)).encode()
    return dict(type=material,amount=amount,fingerprint=v.digest(raw),data=base64.b64encode(raw).decode(),
                name='Changed source' if changed else 'Copy-'+slot if slot else '',
                lore=['Line one','Line two'] if slot else [],model=100+v.SLOTS.index(slot) if slot else 0,
                proof='private-meta-'+str(v.SLOTS.index(slot)) if slot else '',damage=damage)

def client_stack(entry):
    item=entry['item']; slot=entry['slot']
    return dict(slot=slot+36 if slot<9 else slot,name=item['type'].lower(),count=item['amount'],components=[
        dict(type='custom_name',data=item['name']),dict(type='lore',data=item['lore']),
        dict(type='custom_model_data',data=item['model']),dict(type='damage',data=item['damage']),
        dict(type='custom_data',data={'PublicBukkitValues':{'helsteraitemcopyprobe:proof':item['proof']}})])

def synthetic():
    files={};events=[];logs=[];rows=[]
    ids={role:{'uuid':str(uuid.uuid4()),'id':i+1} for i,role in enumerate(['caster','target','nonliving','receiver','observer'])}
    for role,name in [('receiver','CopyReceiver'),('observer','CopyObserver')]:
        events.append(dict(event='join',role=role,name=name,uuid=ids[role]['uuid'],entityId=ids[role]['id']))
    for index,(path,sample) in enumerate((p,s) for p in v.PATHS for s in v.c.CASES):
        equipped={slot:fake_stack('DIAMOND_SWORD' if sample['variant']=='damageable' and slot=='HAND' else v.MATERIALS[i],
                    1 if sample['variant']=='damageable' and slot=='HAND' else 7,slot,
                    damage=17 if sample['variant']=='damageable' and slot=='HAND' else 0) for i,slot in enumerate(v.SLOTS)}
        barrier={slot:fake_stack('BARRIER',1) for slot in v.SLOTS}
        caster={} if sample['source']=='nonliving' else copy.deepcopy(barrier if sample['copy'] else equipped)
        target={} if sample['target']=='nonliving' else copy.deepcopy(equipped if sample['copy'] else barrier)
        if sample['variant']=='air':target['HEAD']={'type':'AIR','amount':0}
        inv=[]
        if sample['source']=='player':inv=[dict(slot=0,item=copy.deepcopy(equipped['HAND']))]
        if sample['fill']!='empty':
            inv=[dict(slot=i,item=fake_stack('BARRIER',64)) for i in range(36)]
            if sample['fill']=='partial':inv[0]['item']=copy.deepcopy(equipped['HAND']);inv[0]['item']['amount']=62
        if sample['target'] in ('player','self'):
            target=copy.deepcopy(equipped) if sample['source']=='player' else {slot:{'type':'AIR','amount':0} for slot in v.SLOTS}
            target['HAND']=copy.deepcopy(next((entry['item'] for entry in inv if entry['slot']==0),{'type':'AIR','amount':0}))
        before=dict(caster=caster,target=target,inventory=inv,observerInventory=[],drops=[])
        after=copy.deepcopy(before)
        if sample['copy']:
            if sample['source']!='nonliving' and sample['target']!='nonliving': after['caster']=copy.deepcopy(target)
        elif sample['result']=='SUCCESS':
            item=copy.deepcopy(equipped[sample['slot']]);overflow=0
            if sample['fill']=='full':overflow=item['amount']
            elif sample['fill']=='partial':
                overflow=item['amount']-2;after['inventory'][0]['item']['amount']=64
            else:after['inventory'].append(dict(slot=len(inv),item=item))
            if overflow:
                item['amount']=overflow
                after['drops']=[dict(item=item,owner='none',thrower='none',canMobPickup=True,
                    location=dict(world='world',x=110.4,y=170.4,z=2.4),entityId=10000+index)]
        if sample['target'] in ('player','self'):
            after['target']['HAND']=copy.deepcopy(next((entry['item'] for entry in after['inventory'] if entry['slot']==0),{'type':'AIR','amount':0}))
        mutated=copy.deepcopy(after)
        if sample['variant']=='mutate':mutated['caster']['HAND']=fake_stack('IRON_INGOT',1,'HAND',True)
        source=sample['mechanic']+'{'+sample['arguments']+'} @trigger'
        row=dict(index=index,path=path,case=sample['key'],source=source,
            mapped=source if path!='imported' else v.c.canonical(sample['mechanic'])+source[len(sample['mechanic']):],
            primary=True,startTick=index*13,endTick=index*13+10,origin=dict(world='world',x=112.,y=170.,z=0.),
            receiverLocation=dict(world='world',x=110.,y=170.,z=2.),
            caster=ids['receiver' if sample['source']=='player' else 'nonliving' if sample['source']=='nonliving' else 'caster']['uuid'],
            target=ids['receiver' if sample['target'] in ('player','self') else 'nonliving' if sample['target']=='nonliving' else 'target']['uuid'],
            before=before,after=after,afterSourceMutation=mutated,settled=copy.deepcopy(mutated),entityIds=list(range(1,6)),
            result=sample['result'] if path=='native' else 'EXECUTED')
        if sample['cleanup']=='world' or sample['cleanup']=='disable' and path!='native':row['cleanupTick']=index*13+2
        files[v.PROBE+f'observations/{index:04d}.yml']=yaml.safe_dump(row,sort_keys=False).encode();rows.append(row)
        logs.append(f'ITEM_COPY_ROW|index={index}|path={path}|case={sample["key"]}|result={row["result"]}')
        for role in ('receiver','observer'):
            events.append(dict(event='phase',role=role,index=index,path=path,case=sample['key'],tick=-1))
            for tick in range(11):events.append(dict(event='tick',role=role,index=index,tick=tick))
            events.append(dict(event='end',role=role,index=index,trackedItemIds=[],
                               inventory=[client_stack(entry) for entry in mutated['inventory']] if role=='receiver' else []))
        if sample['fake']:
            item_id=index+1000
            def packet(kind,tick,data):events.append(dict(event='packet',role='receiver',index=index,tick=tick,packetType=kind,data=data))
            packet('spawn_entity',0,dict(entityId=item_id,type=58,x=112.,y=170.,z=0.))
            packet('entity_metadata',0,dict(entityId=item_id,metadata=[dict(key=8,value=dict(itemCount=7,addedComponentCount=5,components=[]))]))
            if path=='native' or sample['cleanup']=='normal':
                packet('collect',4,dict(collectedEntityId=item_id,collectorEntityId=ids['receiver']['id'],pickupItemCount=1))
            if path!='native' and sample['cleanup']!='world':packet('entity_destroy',2 if sample['cleanup']=='disable' else 4,dict(entityIds=[item_id]))
    for role in ('receiver','observer'): events.append(dict(event='done',role=role,index=len(rows)-1,tick=10))
    events.append(dict(event='summary',passed=True,doneRoles=['observer','receiver'],error=None))
    files['client.log']=('\n'.join('ITEM_COPY_CLIENT '+json.dumps(e) for e in events)+'\n').encode()
    logs += [f'ITEM_COPY_SUMMARY|observations={len(rows)}|completed=true','[00:00:00 INFO]: Stopping server']
    files['server.log']=('\n'.join(logs)+'\n').encode()
    files[v.PROBE+'cases.yml']=yaml.safe_dump(v.c.source_cases(),sort_keys=False).encode()
    files[v.PROBE+'identities.yml']=yaml.safe_dump(ids).encode()
    files['plugins/MythicMobs/Mobs/item-copy.yml']=yaml.safe_dump({'HelsteraRadiusAuditMob':{'Type':'COW','Options':{'NoAI':True,'Despawn':'PERSISTENT'}}}).encode()
    report=dict(schemaVersion=1,evidenceMode='observation',parityStatus='unassessed',validationResult='passed',exitCode=0,
        clientExitCode=0,observationCount=len(rows),paperJarSha256='39bd8c00b9e18de91dcabd3cc3dcfa5328685a53b7187a2f63280c22e2d287b9',
        mythicMobsSha256='9672457cafc56ff841606bfbb710947329c08720ffc207746b60130d43bf902f',
        pluginSha256='0'*64,probeJarSha256='1'*64,sourceHashes={name:'2'*64 for name in v.SOURCE_FILES})
    rehash(report,files);return report,files

if __name__=='__main__':
    if sys.argv[1]=='--self-test':
        test(*synthetic());print('Synthetic in-memory selfcheck only; no Paper/client evidence created.')
    else:test(*v.load(sys.argv[1]),client_only='--client-only' in sys.argv)
