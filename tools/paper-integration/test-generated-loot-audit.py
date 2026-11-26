"""Alter real evidence, recompute hashes, and require semantic rejection."""
from pathlib import Path
import copy
from functools import lru_cache
import json
import importlib.util
import sys
import yaml

# Reuse parsing of byte-identical inputs; every mutation still runs the entire
# semantic audit, hashes and client checks. Parsed values are never mutated.
yaml.safe_load = lru_cache(maxsize=2048)(yaml.safe_load)
json.loads = lru_cache(maxsize=16384)(json.loads)

spec=importlib.util.spec_from_file_location('audit',Path(__file__).with_name('audit-generated-loot.py'))
a=importlib.util.module_from_spec(spec);spec.loader.exec_module(a)
report,files=a.read_report(Path(sys.argv[1]));a.audit(report,files)
rejected=0

def reject_file(name, data):
    global rejected
    edited=dict(files);edited[name]=data;updated=copy.deepcopy(report)
    for entry in updated['files']:
        if entry['path']==name: entry['sha256']=a.digest(data)
    try: a.audit(updated,edited)
    except (ValueError,KeyError,TypeError): rejected+=1
    else: raise AssertionError('accepted altered evidence '+name)

for name in sorted(files):
    if '/observations/' not in name: continue
    original=yaml.safe_load(files[name])
    mutations=[]
    for field in ('xp','score','observerXp','observerScore'):
        changed=copy.deepcopy(original);changed['after'][field]+=1;mutations.append(changed)
    changed=copy.deepcopy(original);changed['origin']['x']+=1;mutations.append(changed)
    changed=copy.deepcopy(original);changed['caster']=original['target'];mutations.append(changed)
    changed=copy.deepcopy(original);changed['endTick']+=1;mutations.append(changed)
    if original['after']['receiver']:
        changed=copy.deepcopy(original);changed['after']['receiver'][0]['model']+=1;mutations.append(changed)
    for i in range(len(original['after']['drops'])):
        for field,value in [('owner','null'),('thrower','null'),('canMobPickup',True),('amount',42),('model',-1)]:
            changed=copy.deepcopy(original);changed['after']['drops'][i][field]=value;mutations.append(changed)
    for changed in mutations:
        reject_file(name,yaml.safe_dump(changed).encode())
events=[json.loads(line[len('ITEM_COPY_CLIENT '):]) for line in files['client.log'].decode().splitlines()
        if line.startswith('ITEM_COPY_CLIENT ')]

def reject_events(changed):
    reject_file('client.log',(''.join('ITEM_COPY_CLIENT '+json.dumps(e)+'\n' for e in changed)).encode())

for event_index,event in enumerate(events):
    if event.get('index') not in (1,85): continue
    mutations=[]
    if event['event']=='packet':
        if event['packetType']=='spawn_entity':
            altered=copy.deepcopy(event);altered['data']['x']+=1;mutations.append(altered)
            altered=copy.deepcopy(event);altered['role']='observer';mutations.append(altered)
        elif event['packetType']=='collect':
            for key,value in [('collectorEntityId',-1),('pickupItemCount',4)]:
                altered=copy.deepcopy(event);altered['data'][key]=value;mutations.append(altered)
            altered=copy.deepcopy(event);altered['tick']=1;mutations.append(altered)
        elif event['packetType']=='entity_metadata':
            for k,entry in enumerate(event['data']['metadata']):
                if entry['key']==8:
                    for key,value in [('itemId',1),('itemCount',42)]:
                        altered=copy.deepcopy(event);altered['data']['metadata'][k]['value'][key]=value;mutations.append(altered)
                    for component_index,component in enumerate(entry['value'].get('components',[])):
                        if component['type']=='custom_model_data':
                            altered=copy.deepcopy(event)
                            altered['data']['metadata'][k]['value']['components'][component_index]['data']=-1
                            mutations.append(altered)
    elif event['event']=='end' and event['role']=='receiver' and event['inventory']:
        altered=copy.deepcopy(event);altered['inventory'][0]['count']+=1;mutations.append(altered)
    for altered in mutations:
        changed=list(events);changed[event_index]=altered;reject_events(changed)
for kind in ('summary','done','join','tick'):
    location=next(i for i,e in enumerate(events) if e['event']==kind)
    changed=list(events);changed.pop(location);reject_events(changed)
print('rejected',rejected,'rehashed semantic mutations')
