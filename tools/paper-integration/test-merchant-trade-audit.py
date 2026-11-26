"""Semantic evidence changes must be rejected even with recomputed hashes."""
from pathlib import Path
from functools import lru_cache
import copy
import importlib.util
import json
import sys
import yaml

yaml.safe_load=lru_cache(maxsize=4096)(yaml.safe_load)
json.loads=lru_cache(maxsize=32768)(json.loads)
spec=importlib.util.spec_from_file_location('audit',Path(__file__).with_name('audit-merchant-trade.py'))
a=importlib.util.module_from_spec(spec); spec.loader.exec_module(a)
report,files=a.read_report(Path(sys.argv[1])); a.audit(report,files); rejected=0

def reject(name,data):
    global rejected
    changed=dict(files); changed[name]=data; updated=copy.deepcopy(report)
    for entry in updated['files']:
        if entry['path']==name: entry['sha256']=a.digest(data)
    try: a.audit(updated,changed)
    except (ValueError,KeyError,TypeError): rejected+=1
    else: raise AssertionError('accepted altered evidence '+name)

for name in sorted(files):
    if '/observations/' not in name: continue
    row=yaml.safe_load(files[name])
    if row['index'] not in (0,24,54,57,66,69,72,78,90,96,99,102,108,111,126): continue
    for label in ('before','after','opened','settled'):
        for subject in ('merchant','control'):
            for field in ('profession','trader','recipes'):
                altered=copy.deepcopy(row)
                altered[label][subject][field]=[] if field=='recipes' else 'tampered'
                if altered!=row: reject(name,yaml.safe_dump(altered).encode())
            for number,recipe in enumerate(row[label][subject]['recipes']):
                for field in recipe:
                    altered=copy.deepcopy(row); target=altered[label][subject]['recipes'][number]
                    if field=='result': target[field]['amount']+=1
                    elif field=='ingredients': target[field][0]['amount']+=1
                    elif type(target[field]) is bool: target[field]=not target[field]
                    else: target[field]+=1
                    reject(name,yaml.safe_dump(altered).encode())
        for role in ('receiver','observer'):
            for field in ('type','real','closes'):
                altered=copy.deepcopy(row); original=altered[label][role][field]
                altered[label][role][field]='CHEST' if field=='type' else not original if field=='real' else original+1
                reject(name,yaml.safe_dump(altered).encode())
    for field in ('result','caster','target','trigger','mapped','endTick'):
        altered=copy.deepcopy(row); altered[field]=row[field]+1 if field=='endTick' else 'tampered'
        reject(name,yaml.safe_dump(altered).encode())

events=[json.loads(line[len('MERCHANT_TRADE_CLIENT '):]) for line in files['client.log'].decode().splitlines() if line.startswith('MERCHANT_TRADE_CLIENT ')]
def reject_events(changed): reject('client.log',''.join('MERCHANT_TRADE_CLIENT '+json.dumps(e)+'\n' for e in changed).encode())
for index,event in enumerate(events):
    if event['event']=='packet' and (event['packetType'] in ('trade_list','open_window')
            or event['packetType']=='close_window' and event['data']['windowId']!=0):
        altered=list(events); altered.pop(index); reject_events(altered)
    if event['event']=='packet' and event['packetType']=='trade_list':
        for field in ('nbTradeUses','maximumNbTradeUses','xp','specialPrice','priceMultiplier','demand'):
            altered=list(events); altered[index]=copy.deepcopy(event); altered[index]['data']['trades'][0][field]+=1; reject_events(altered)
for kind in ('summary','done','join','tick','sent-close','opened','end'):
    index=next(i for i,e in enumerate(events) if e['event']==kind)
    altered=list(events); altered.pop(index); reject_events(altered)
print('rejected',rejected,'rehashed semantic mutations')
