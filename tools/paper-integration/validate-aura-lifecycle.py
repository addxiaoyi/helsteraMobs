"""Validate scoped base Aura evidence; cancelled death deliberately differs from native."""
import hashlib
import importlib.util
import io
import json
import math
from pathlib import Path, PurePosixPath
import re
import sys
import uuid
import zipfile
import yaml

sys.dont_write_bytecode = True
spec = importlib.util.spec_from_file_location('aura_base', Path(__file__).with_name('validate-player-variables.py'))
base = importlib.util.module_from_spec(spec); spec.loader.exec_module(base)
require, fields = base.require, base.fields
FILES = {'server.log', 'client.log', 'source-skills.yml'}
SOURCES = {'run-aura-lifecycle-fixture.py', 'run-player-variable-fixture.py',
           'validate-aura-lifecycle.py', 'validate-player-variables.py', 'test-aura-lifecycle-evidence.py',
           'aura-lifecycle-client.js',
           'random-spawn-fixture/src/main/java/dev/helstera/test/randomspawn/AuraLifecycleProbe.java',
           'random-spawn-fixture/src/main/java/dev/helstera/test/randomspawn/VariablePlaceholderAudit.java'}
# active, native, starts, ends, live charges/stacks, same tracker, cancelled event, end charge snapshots
CASES = {
 'take': ('false','false',1,1,'none','none','false','false','0'),
 'cancelled_take': ('false','false',1,1,'none','none','false','true','0'),
 'event_suppressed': ('false','false',1,1,'none','none','false','false','0'),
 'give': ('false','false',1,1,'none','none','false','false','0'),
 'projectile': ('true','true',1,0,'0','1','false','false','none'),
 'cancelled_death': ('true','false',1,0,'0','1','false','true','none'),
 'death': ('false','false',1,1,'none','none','false','false','0'),
 'caster_death': ('false','false',1,1,'none','none','false','false','0'),
 'remove_suppressed': ('false','false',1,0,'none','none','false','false','none'),
 'merge_policy': ('true','none',1,0,'0','2','true','false','none'),
 'overwrite': ('false','none',2,2,'none','none','false','false','2,5'),
 'separate': ('false','none',2,2,'none','none','false','false','2,5'),
 'teleport': ('false','false',1,1,'none','none','false','true','0'),
 'world_change': ('false','false',1,1,'none','none','false','true','0'),
 'charges': ('false','false',1,1,'none','none','true','false','0'),
 'expiry': ('false','none',1,1,'none','none','false','false','3'),
 'bar_remove': ('false','none',1,0,'none','none','false','false','none'),
 'bar_clear': ('false','none',1,0,'none','none','false','false','none'),
 'quit': ('false','false',1,1,'none','none','false','false','0'),
}
BAR_KEYS = ('expiry','bar_remove','bar_clear')


def digest(data): return hashlib.sha256(data).hexdigest()
def hashed(value): return isinstance(value,str) and re.fullmatch('[a-f0-9]{64}',value) is not None


def manifest(report):
    result = {}
    for item in report['files']:
        require(set(item)=={'path','sha256'} and item['path'] in FILES and item['path'] not in result
                and hashed(item['sha256']), 'bad/duplicate manifest')
        result[item['path']] = item['sha256']
    require(set(result)==FILES,'missing manifest members')
    return result


def verify_payloads(report,payloads):
    entries=manifest(report); require(set(payloads)==set(entries),'payload membership differs')
    for name,value in entries.items(): require(digest(payloads[name])==value,'payload changed: '+name)


def archive_payloads(report,data):
    require(hashed(report['logArchiveSha256']) and digest(data)==report['logArchiveSha256'],'archive changed')
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        names=archive.namelist(); require(len(names)==len(set(names)) and set(names)==FILES,'archive membership differs')
        payloads={name:archive.read(name) for name in names}
    verify_payloads(report,payloads); return payloads


def validate(report,payloads):
    require(type(report['schemaVersion']) is int and report['schemaVersion']==1 and report['evidenceMode']=='scoped-runtime'
            and 'validationResult' not in report,'wrong/rejected schema')
    require(report['contract']=='base-aura-lifecycle' and report['fullAuraParity'] is False
            and report['deathBoundary']=='MONITOR-ignoreCancelled','scope/death boundary changed')
    require(report['paperJarSha256']=='39bd8c00b9e18de91dcabd3cc3dcfa5328685a53b7187a2f63280c22e2d287b9','wrong Paper')
    require(report['mythicMobsSha256']=='9672457cafc56ff841606bfbb710947329c08720ffc207746b60130d43bf902f','wrong native')
    require(hashed(report['pluginSha256']) and hashed(report['probeJarSha256']),'bad artifact identity')
    require(type(report['exitCode']) is int and report['exitCode']==0 and type(report['clientExitCode']) is int
            and report['clientExitCode']==0,'server/client failed')
    require(re.fullmatch('srv-paper-aura-lifecycle-[0-9]{8}-r[1-9][0-9]*',report['fixtureDirectory']) is not None,'wrong fixture')
    require(set(report['sourceHashes'])=={'tools/paper-integration/'+name for name in SOURCES}
            and all(hashed(value) for value in report['sourceHashes'].values()),'incomplete source manifest')
    verify_payloads(report,payloads)
    require(yaml.load(payloads['source-skills.yml'],Loader=base.UniqueYaml)=={'skills':{name:{'on-decision':[]} for name in
            ('AuraAuditStart','AuraAuditTick','AuraAuditEnd')}},'callback skill stimulus changed')
    lines=payloads['server.log'].decode().splitlines()
    require(report['observations']==[line for line in lines if 'AURALIFE_' in line],'raw evidence differs')
    kinds={'IDENTITIES','CALLBACK','CASE','PROJECTILE','TICKS','REFRESH','CONSUME','CLEANUP','SUMMARY'}
    records={kind:[] for kind in kinds}
    for index,line in enumerate(lines):
        if 'AURALIFE_' not in line: continue
        match=re.search(r'AURALIFE_([A-Z_]+)\|',line)
        require(match is not None and match[1] in kinds,'unknown/failure marker')
        records[match[1]].append((index,fields(line,'AURALIFE_'+match[1]+'|')))
    for kind in ('IDENTITIES','CLEANUP','SUMMARY'): require(len(records[kind])==1,'missing/duplicate '+kind)
    ids=records['IDENTITIES'][0][1]
    require(set(ids)=={'player','directCaster','directTarget','directNative','importedCaster','importedTarget','importedNative'}
            and len(set(ids.values()))==7,'identity coverage differs')
    for value in ids.values(): uuid.UUID(value)
    require(records['CLEANUP'][0][1]=={'targets':'0','entities':'0'},'cleanup leaked')
    summary_tick=int(records['SUMMARY'][0][1]['tick'])
    require(records['SUMMARY'][0][1]=={'observations':str(2*len(CASES)),'completed':'true','tick':str(summary_tick)},'summary differs')
    require(len(records['CASE'])==2*len(CASES),'case count differs')
    cases={}; tick_order=[]
    for index,row in records['CASE']:
        require(set(row)=={'path','key','target','caster','active','native','starts','ticks','ends','charges','stacks','sameId','cancelled','endCharges','tick'},'case fields differ')
        path,key=row['path'],row['key']; require(path in ('direct','imported') and key in CASES and (path,key) not in cases,'unknown/duplicate case')
        target=ids['player'] if key in ('teleport','world_change','quit',*BAR_KEYS) else ids[path+'Caster'] if key=='projectile' else ids[path+'Target']
        require(row['target']==target and row['caster']==ids[path+'Caster'],'case entity differs')
        names=('active','native','starts','ends','charges','stacks','sameId','cancelled','endCharges')
        require(all(row[n]==str(v) for n,v in zip(names,CASES[key])),'case state differs: '+path+'/'+key)
        ticks=int(row['ticks']); require(0 <= ticks <= 100,'bad tick count')
        require((ticks >= 1) if key in ('charges',*BAR_KEYS) else ticks==0,'periodic behavior differs: '+key)
        cases[path,key]=(index,row); tick_order.append((index,int(row['tick'])))
    callbacks={}
    for index,row in records['CALLBACK']:
        require(set(row)=={'key','phase','target','name','type','charges','remaining','millis','stacks','delegated','tick'},'callback fields differ')
        path,key=row['key'].split('_',1); require((path,key) in cases,'orphan callback')
        require(row['name']==row['key'] and row['type']=='Audit' and row['delegated']=='true'
                and row['phase'] in ('start','tick','end') and row['target']==cases[path,key][1]['target'],'callback context differs')
        remaining=int(row['remaining']); require(remaining>=0 and int(row['millis'])==remaining*50,'duration variables differ')
        expected_duration=16 if key=='expiry' else 80 if key in BAR_KEYS else 200
        require(remaining<=expected_duration and (row['phase']!='start' or remaining==expected_duration),'duration bounds differ')
        require(int(row['stacks'])==(2 if key=='charges' and row['phase']=='end' else 1),'stack variable differs')
        expected_charges=3 if key in BAR_KEYS else 2 if key=='charges' and row['phase']!='end' else 0
        if key not in ('overwrite','separate'): require(int(row['charges'])==expected_charges,'charge variable differs')
        if key=='expiry' and row['phase']=='end': require(remaining==0,'expiry ended too early')
        if key not in BAR_KEYS and key not in ('charges','quit'): require(remaining==200,'synchronous event duration differs')
        require(index<cases[path,key][0],'callback arrived after case snapshot')
        callbacks.setdefault((path,key),[]).append((index,row)); tick_order.append((index,int(row['tick'])))
    for pair,(index,row) in cases.items():
        observed=callbacks.get(pair,[])
        for phase,count_name in [('start','starts'),('tick','ticks'),('end','ends')]:
            require(sum(r['phase']==phase for _,r in observed)==int(row[count_name]),'callback count differs')
        require(observed and observed[0][1]['phase']=='start','missing initial start')
        ends=[r['charges'] for _,r in observed if r['phase']=='end']
        require((','.join(ends) if ends else 'none')==row['endCharges'],'final state snapshots differ')
        if pair[1] in ('overwrite','separate'):
            require([r['charges'] for _,r in observed if r['phase']=='start']==['2','5'],'independent start states differ')
        tick_callbacks=[r for _,r in observed if r['phase']=='tick']
        if tick_callbacks:
            start_tick=int(observed[0][1]['tick']); duration=int(observed[0][1]['remaining'])
            require(all(int(r['remaining'])==duration-(int(r['tick'])-start_tick) for r in tick_callbacks),'tick snapshot clock differs')
    for kind in ('PROJECTILE','TICKS','REFRESH','CONSUME'):
        require(len(records[kind])==2 and {row['path'] for _,row in records[kind]}=={'direct','imported'},'missing/duplicate '+kind)
    for _,row in records['PROJECTILE']:
        require(set(row)=={'path','projectile','shooter'} and row['shooter']==ids[row['path']+'Caster']
                and row['projectile'] not in ids.values(),'projectile identity changed'); uuid.UUID(row['projectile'])
    for index,row in records['TICKS']:
        require(set(row)=={'path','charges','nativeCharges','ticks'} and row['charges']==row['nativeCharges']=='2'
                and int(row['ticks'])==int(cases[row['path'],'charges'][1]['ticks']),'plain tick consumed a charge')
    for index,row in records['REFRESH']:
        require(row=={'path':row['path'],'sameId':'true','charges':'2','nativeCharges':'2','stacks':'2','nativeStacks':'2'},'refresh contract differs')
    for index,row in records['CONSUME']:
        require(row=={'path':row['path'],'repeated':'false'},'removed tracker consumed again')
    values=[tick for _,tick in sorted(tick_order)]
    require(all(0<=t<=summary_tick for t in values) and values==sorted(values) and max(values)==summary_tick,'tick chronology differs')
    require(records['IDENTITIES'][0][0]<min(index for index,_ in records['CALLBACK'])
            and max(index for index,_ in records['CASE'])<records['CLEANUP'][0][0]<records['SUMMARY'][0][0],'lifecycle order differs')
    stops=[i for i,line in enumerate(lines) if ']: Stopping server' in line]
    require(len(stops)==1 and stops[0]>records['SUMMARY'][0][0],'shutdown before completion')
    client=[json.loads(line.split('AURALIFE_CLIENT ',1)[1],object_pairs_hook=base.unique_pairs)
            for line in payloads['client.log'].decode().splitlines() if 'AURALIFE_CLIENT ' in line]
    require(len(client)>=15 and client[0]=={'event':'join','name':'AuraPlayer','uuid':ids['player']}
            and client[-2:]==[{'event':'quit_requested','activeBars':0},{'event':'summary','passed':True}],'client lifecycle differs')
    active={}; added={}; removed=set(); progress={}
    titles={'AuraAudit_'+path+'_'+key for path in ('direct','imported') for key in BAR_KEYS}
    for row in client[1:-2]:
        event=row['event']; identity=row['id']; require(isinstance(identity,str),'bad BossBar UUID'); uuid.UUID(identity)
        if event=='bar_add':
            require(set(row)=={'event','id','title','health','color','dividers'} and row['title'] in titles
                    and identity not in added and row['title'] not in added.values() and type(row['health']) in (int,float)
                    and row['health']==1 and row['color']==2 and row['dividers']==0,'BossBar add/defaults differ')
            added[identity]=row['title']; active[identity]=row['title']; progress[identity]=[]
        elif event=='bar_progress':
            require(set(row)=={'event','id','health'} and identity in active and type(row['health']) in (int,float)
                    and math.isfinite(row['health']) and 0<=row['health']<=1,'invalid/orphan progress')
            progress[identity].append(row['health'])
        elif event=='bar_remove':
            require(set(row)=={'event','id','title'} and identity in active and row['title']==active[identity],'orphan removal')
            del active[identity]; removed.add(identity)
        else: require(False,'unknown client event')
    require(not active and set(added.values())==titles and set(added)==removed,'BossBar cleanup/coverage differs')
    for identity,values in progress.items():
        require(values and any(0<value<1 for value in values) and values==sorted(values,reverse=True),'BossBar did not advance monotonically')
    return len(cases)


def main(path):
    path=Path(path); report=json.loads(path.read_text(),object_pairs_hook=base.unique_pairs)
    name=report['logArchive']; require(isinstance(name,str) and PurePosixPath(name).name==name and '\\' not in name and name.endswith('.zip'),'unsafe archive path')
    payloads=archive_payloads(report,(path.parent/name).read_bytes())
    print('Validated',validate(report,payloads),'base Aura lifecycle cases and six real-client BossBars.')
    return report,payloads


if __name__=='__main__': main(sys.argv[1])
