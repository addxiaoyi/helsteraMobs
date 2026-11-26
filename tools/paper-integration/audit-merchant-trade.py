"""Independent recipe, actor, window-packet and client-close evidence checks."""
from pathlib import Path
import argparse
import copy
import hashlib
import importlib.util
import json
import zipfile
import yaml
ROOT=Path(__file__).resolve().parents[2]
spec=importlib.util.spec_from_file_location('merchant_cases',Path(__file__).with_name('merchant-trade-cases.py'))
c=importlib.util.module_from_spec(spec); spec.loader.exec_module(c)
PROBE='plugins/HelsteraMerchantTradeProbe/'
ITEMS={'STONE':1,'BREAD':855,'EMERALD':806,'APPLE':800,'GOLD_INGOT':815,'DIAMOND':805}

def require(value,message):
    if not value: raise ValueError(message)
def digest(data): return hashlib.sha256(data).hexdigest()
def view(opened=False,real=False,closes=0):
    return dict(type='MERCHANT' if opened else 'CRAFTING',real=real,recipes=copy.deepcopy(c.SEED) if opened else [],closes=closes)
def expected(case,path,label,ids):
    mutate=case['kind']=='mutate'; before=label=='before'; settled=label=='settled'
    profession='WANDERING' if case['merchant']=='wandering' else case['profession']
    control=case['profession']
    if mutate and not before and case['target']=='merchant' and profession=='NONE': profession='NITWIT'
    recipes=copy.deepcopy(c.SEED if before else case.get('nativeExpected',case['expected']) if path=='native' else case['expected'])
    takeover=case.get('takeover',False); opens=case.get('opens',False) and not before
    real=case.get('real',False)
    receiver=view(opens and not settled,opens and real and not settled,int(opens and settled))
    observer_open=takeover and (before or not (opens and real)) and not settled
    observer=view(observer_open,observer_open,int(takeover and (settled or opens and real)))
    trader='none'
    if not settled:
        if opens and real: trader=ids['receiver']['uuid']
        elif takeover: trader=ids['observer']['uuid']
    return dict(merchant=dict(profession=profession,recipes=recipes,trader=trader),
                control=dict(profession=control,recipes=copy.deepcopy(c.SEED),trader='none'),
                receiver=receiver,observer=observer,score=0)

def check_row(row,case,index,path,ids):
    require(set(row)=={'index','path','case','source','merchantUuid','merchantId','controlUuid','before','startTick','primary',
                       'caster','target','trigger','mapped','result','after','opened','settled','endTick'},'row fields')
    source=case['mechanic']+'{'+case['arguments']+'} @targets'
    canonical='open-trades' if case['kind']=='open' else 'merchant-trade'
    mapped=canonical+source[len(case['mechanic']):] if path=='imported' else source
    require((row['index'],row['case'],row['path'],row['source'],row['mapped'])==(index,case['key'],path,source,mapped),'row identity/mapping')
    require(row['primary'] is True and type(row['startTick']) is int and row['endTick']-row['startTick']==15,'thread/tick span')
    require(type(row['merchantId']) is int and row['merchantUuid']!=row['controlUuid'],'independent merchants')
    caster=ids['caster']['uuid'] if case['kind']=='mutate' else ids['receiver']['uuid'] if case['caster']=='receiver' else row['merchantUuid']
    recipient=row['merchantUuid'] if case['target']=='merchant' else ids[case['target']]['uuid']
    require((row['caster'],row['target'],row['trigger'])==(caster,recipient,ids['receiver']['uuid']),'actor/trigger direction')
    require(row['result']==(case['nativeResult'] if path=='native' else 'EXECUTED'),'invocation '+case['key']+'/'+path)
    for label in ('before','after','opened','settled'):
        wanted=expected(case,path,label,ids)
        require(row[label]==wanted,'state '+case['key']+'/'+path+'/'+label+' expected '+str(wanted)+' actual '+str(row[label]))

def slot(actual,wanted):
    require(actual is not None and actual['itemId']==ITEMS[wanted['material']] and actual['itemCount']==wanted['amount'],'client item')
    require(actual.get('components',[])==[],'unexpected client item components')

def trades(actual):
    require(len(actual)==len(c.SEED),'client trade count')
    for trade,wanted in zip(actual,c.SEED):
        slot(trade['inputItem1'],wanted['ingredients'][0]); slot(trade['outputItem'],wanted['result'])
        require(trade.get('inputItem2') is None,'unexpected second input')
        require(trade['tradeDisabled'] is False,'disabled trade')
        for field,key in {'nbTradeUses':'uses','maximumNbTradeUses':'maxUses','xp':'villagerExperience',
                          'specialPrice':'specialPrice','priceMultiplier':'priceMultiplier','demand':'demand'}.items():
            require(trade[field]==wanted[key],'client recipe '+key)

def clients(events,rows,ids):
    require(not any(e['event'] in ('packet-error','world-reset') for e in events),'client decode/world reset')
    summary=[e for e in events if e['event']=='summary']
    require(len(summary)==1 and summary[0]['passed'] is True and summary[0]['error'] is None
            and summary[0]['doneRoles']==['observer','receiver'],'two-client completion')
    for role in ('receiver','observer'):
        joins=[e for e in events if e['event']=='join' and e['role']==role]
        require(len(joins)==1 and joins[0]['uuid']==ids[role]['uuid'] and joins[0]['entityId']==ids[role]['entityId'],'client identity')
        require(len([e for e in events if e['event']=='done' and e['role']==role])==1,'client done')
        require([e['index'] for e in events if e['event']=='phase' and e['role']==role]==list(range(len(rows))),'ordered phases')
    by_phase={}
    for event in events:
        by_phase.setdefault((event.get('index'),event.get('role')),[]).append(event)
    for row in rows:
        index=row['index']; case=c.CASES[index//3]
        for role in ('receiver','observer'):
            phase=by_phase.get((index,role),[])
            require(all((e['path'],e['case'])==(row['path'],row['case']) for e in phase),'client phase identity')
            require([e['tick'] for e in phase if e['event']=='tick']==list(range(16)),'client ticks')
            opens=[e for e in phase if e['event']=='packet' and e['packetType']=='open_window']
            expected_opens=int(case.get('opens',False) if role=='receiver' else case.get('takeover',False))
            require(len(opens)==expected_opens,'open packets '+row['case']+'/'+role)
            listings=[e for e in phase if e['event']=='packet' and e['packetType']=='trade_list']
            require(len(listings)==expected_opens,'trade list packets')
            for opening,listing in zip(opens,listings):
                require(opening['data']['windowId']==listing['data']['windowId'] and opening['data']['inventoryType']==19,'window identity/type')
                require(opening['tick']==(-1 if role=='observer' else 0),'window timing')
                trades(listing['data']['trades'])
                actual_real=role=='observer' or case.get('real',False)
                require(listing['data']['villagerLevel']==1 and listing['data']['experience']==0
                        and listing['data']['isRegularVillager'] is actual_real
                        and listing['data']['canRestock'] is actual_real,'merchant UI flags')
                title=opening['data']['windowTitle']
                title_text=title['value'] if title['type']=='string' else title['value']['text']['value']
                require(title_text=='Trade audit','merchant title')
            reconstructed=None; observed=False
            for event in phase:
                if event['event']=='packet':
                    data=event['data']
                    if event['packetType']=='open_window':
                        reconstructed=dict(id=data['windowId'],type=data['inventoryType'],title=data['windowTitle'],trades=None)
                    elif event['packetType']=='trade_list':
                        require(reconstructed is not None and reconstructed['id']==data['windowId'],'trade list without window')
                        reconstructed['trades']=data['trades']
                    elif event['packetType']=='close_window':
                        require((reconstructed is not None and reconstructed['id']==data['windowId']) or (reconstructed is None and data['windowId']==0), 'close without window')
                        reconstructed=None
                elif event['event']=='opened':
                    require(not observed and event['tick']==5,'opened marker'); observed=True
                    require(event['window']==reconstructed,'reconstructed opened window')
                    require((reconstructed is not None)==(row['opened'][role]['type']=='MERCHANT'),'client/server open window')
                elif event['event']=='sent-close':
                    require(observed and event['tick']==5 and reconstructed is not None and event['windowId']==reconstructed['id'],'client close request')
                    reconstructed=None
                elif event['event']=='end':
                    require(reconstructed is None and event['window'] is None and event['inventory']==[],'final window/inventory')
            require(observed and len([e for e in phase if e['event']=='end'])==1,'opened/end coverage')
            require(len([e for e in phase if e['event']=='sent-close'])==int(row['opened'][role]['type']=='MERCHANT'),'close coverage')

def audit(report,files):
    require(report['evidenceMode']=='observation' and report['parityStatus']=='unassessed','claim scope')
    require(report['exitCode']==report['clientExitCode']==0,'process completion')
    require(report['paperJarSha256']=='39bd8c00b9e18de91dcabd3cc3dcfa5328685a53b7187a2f63280c22e2d287b9','pinned Paper')
    require(report['mythicMobsSha256']=='9672457cafc56ff841606bfbb710947329c08720ffc207746b60130d43bf902f','pinned native')
    require(report['observationCount']==len(c.CASES)*3,'count')
    require(len(report['files'])==len(files) and set(files)=={f['path'] for f in report['files']},'file inventory')
    for entry in report['files']: require(digest(files[entry['path']])==entry['sha256'],'file hash')
    require(yaml.safe_load(files[PROBE+'cases.yml'])=={'cases':c.CASES},'case inputs')
    require(yaml.safe_load(files['plugins/MythicMobs/DropTables/merchant-trade.yml'])==c.TABLES,'native table inputs')
    require(yaml.safe_load(files['plugins/MythicMobs/Items/merchant-trade.yml'])==c.ITEM_DEFINITIONS,'native item inputs')
    ids=yaml.safe_load(files[PROBE+'identities.yml']); rows=[]
    paths=sorted(name for name in files if name.startswith(PROBE+'observations/'))
    require(len(paths)==len(c.CASES)*3,'row count')
    for index,path in enumerate(paths):
        row=yaml.safe_load(files[path]); check_row(row,c.CASES[index//3],index,('native','direct','imported')[index%3],ids); rows.append(row)
    log=files['server.log'].decode()
    require('MERCHANT_TRADE_FAILED|' not in log and f'MERCHANT_TRADE_SUMMARY|observations={len(rows)}|completed=true' in log,'server completion')
    events=[json.loads(line[len('MERCHANT_TRADE_CLIENT '):]) for line in files['client.log'].decode().splitlines() if line.startswith('MERCHANT_TRADE_CLIENT ')]
    clients(events,rows,ids)
    return rows

def read_report(path,check_current=False):
    report=json.loads(path.read_text()); archive=path.with_name(report['logArchive'])
    require(digest(archive.read_bytes())==report['logArchiveSha256'],'archive hash')
    with zipfile.ZipFile(archive) as packed:
        require(len(packed.namelist())==len(set(packed.namelist())),'duplicate archive member')
        files={name:packed.read(name) for name in packed.namelist()}
    if check_current:
        require(digest((ROOT/'helstera-plugin/target/helsteraMobs-1.0.0-full.jar').read_bytes())==report['pluginSha256'],'current artifact')
        for name,wanted in report['sourceHashes'].items(): require(digest((ROOT/name).read_bytes())==wanted,'current source '+name)
    return report,files
if __name__=='__main__':
    parser=argparse.ArgumentParser(); parser.add_argument('report',type=Path); parser.add_argument('--check-current',action='store_true')
    args=parser.parse_args(); report,files=read_report(args.report,args.check_current)
    print('passed',len(audit(report,files)),'merchant-trade observations')
