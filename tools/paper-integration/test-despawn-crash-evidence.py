"""Negative controls must still fail if matching live rows or NBT projections are forged together."""
import copy
import importlib.util
import json
from pathlib import Path
import sys
sys.dont_write_bytecode = True
spec=importlib.util.spec_from_file_location('v',Path(__file__).with_name('validate-despawn-crash.py'))
v=importlib.util.module_from_spec(spec);spec.loader.exec_module(v)
report,payloads=v.main(sys.argv[1]); controls=[]
def text(label,name,change):
    p=dict(payloads);p[name]=change(p[name].decode()).encode();assert p[name]!=payloads[name],label;controls.append((label,report,p))
def row(label,predicate,change):
    r=copy.deepcopy(report);p=dict(payloads);i=next(i for i,l in enumerate(r['observations']) if predicate(l))
    before=r['observations'][i];after=change(before);assert after!=before,label;r['observations'][i]=after
    for name in p:
        if name.endswith('.server.log'):p[name]=p[name].replace(before.encode(),after.encode())
    controls.append((label,r,p))
row('missing row',lambda _:True,lambda _:'')
row('duplicate row',lambda _:True,lambda _:report['observations'][1])
row('both wrong scalar',lambda l:'phase=seeded|' in l,lambda l:l.replace('Saved=7','Saved=8'))
row('active crash overclaim',lambda l:'phase=recover|key=active_never_direct|' in l,lambda l:l.replace('Saved=absent','Saved=13'))
row('lost unloaded scalar',lambda l:'phase=recover|key=unloaded_never_direct|' in l,lambda l:l.replace('runtimeSaved=13','runtimeSaved=absent'))
row('resumed mutation falsely durable',lambda l:'phase=recover|key=resumed_never_direct|' in l,lambda l:l.replace('Saved=13','Saved=113'))
row('old deleted variable resurrects',lambda l:'phase=final|key=resumed_never_imported|' in l,lambda l:l.replace('runtimeSaved=absent','runtimeSaved=14'))
row('updated checkpoint lost',lambda l:'phase=final|key=unloaded_never_imported|' in l,lambda l:l.replace('runtimeUpdated=38','runtimeUpdated=absent'))
row('old tree resurrects',lambda l:'phase=final|key=unloaded_never_imported|' in l,lambda l:l.replace('tree=absent','tree=30'))
row('dead mob resurrects',lambda l:'phase=final|key=unloaded_never_direct|' in l,lambda l:l.replace('runtimeEntity=false','runtimeEntity=true'))
row('disposable survives crash',lambda l:'phase=recover|key=active_normal_direct|' in l,lambda l:l.replace('runtimeEntity=false','runtimeEntity=true'))
row('orphan visuals',lambda l:'phase=recover|key=active_normal_direct|' in l,lambda l:l.replace('visuals=0','visuals=11'))
row('wrong location',lambda l:'phase=recover|key=unloaded_never_direct|' in l,lambda l:l.replace(',110.0,',',111.0,'))
row('NEVER forced',lambda l:'phase=recover|key=unloaded_never_direct|' in l,lambda l:l.replace('forced=false','forced=true'))
for label,source,target in [
 ('graceful shutdown passed as crash','DCRASH_CHECKPOINT|','Stopping server\nDCRASH_CHECKPOINT|'),
 ('missing checkpoint','DCRASH_CHECKPOINT|','UNKNOWN|'),
 ('flush rejected','|accepted=true','|accepted=false'),
 ('world save missing','Saved the game','Other event'),
 ('missing unload','DCRASH_CHUNK_REQUEST|' if report.get('unloadBoundary') == 'chunk' else 'DCRASH_WORLD_UNLOAD|','UNKNOWN|'),
 ('missing mapping','DCRASH_MAPPING|','UNKNOWN|'),
 ('wrong mapping','|trigger=true','|trigger=false'),
 ('missing callback','DCRASH_HOOK runtime despawn','UNKNOWN runtime despawn'),
 ('duplicate callback','DCRASH_READY|','DCRASH_HOOK runtime despawn unloaded_normal_direct\nDCRASH_READY|')]:
    text(label,'write.server.log',lambda t,s=source,d=target:t.replace(s,d,1))
for label,source,target in [
 ('eager NEVER','|loaded=false','|loaded=true'),('missing cold control','DCRASH_COLD|','UNKNOWN|'),
 ('missing lazy load','|event=load|','|event=invalid|'),('missing death','|event=death|','|event=invalid|'),
 ('missing death callback','DCRASH_HOOK runtime death','UNKNOWN runtime death'),
 ('missing clear completion','DCRASH_CLEARED|','UNKNOWN|')]:
    text(label,'recover.server.log',lambda t,s=source,d=target:t.replace(s,d,1))
text('failed stage','final.server.log',lambda t:t+'\nDCRASH_FAILED|\n')
text('checkpoint load failure','recover.server.log',lambda t:t+'\nVariable checkpoint load failed\n')
text('missing inventory','snapshots/write-stopped/inventory.json',lambda _:'[]')
text('orphan final scope','snapshots/final-stopped/scoped-variables.yml',lambda t:t.replace('registries: {}','registries: {orphan: bad}'))
tree=next(n for n in payloads if n.startswith('snapshots/write-online/entity-'))
text('wrong disk tree',tree,lambda t:t.replace('name: CrashTree','name: WrongTree'))
for label,change in [('wrong exit',lambda r:r['boots'][0].update(exitCode=0)),
 ('wrong termination',lambda r:r['boots'][0].update(termination='graceful')),
 ('missing boot',lambda r:r['boots'].pop()),
 ('unknown exception',lambda r:r['knownExceptions'].clear()),
 ('missing source',lambda r:r['sourceHashes'].pop(next(iter(r['sourceHashes'])))),
 ('observation masquerades as verified',lambda r:r.update(evidenceMode='observation'))]:
    r=copy.deepcopy(report);change(r);controls.append((label,r,payloads))
p=dict(payloads);name=next(n for n in p if n.endswith('.mca'));p[name]=b'bad';controls.append(('corrupt raw region',report,p))
p=dict(payloads);name='snapshots/write-online/entities.json';d=json.loads(p[name]);d['despawn-crash-unloaded']['entities'].pop();p[name]=(json.dumps(d)+'\n').encode();controls.append(('forged entity projection',report,p))
p=dict(payloads);p.pop(next(n for n in p if n.endswith('.mca')));controls.append(('missing raw NBT',report,p))
if report['schemaVersion'] == 3:
    for label,change in [
        ('wrong unload boundary',lambda r:r.update(unloadBoundary='world' if r['unloadBoundary']=='chunk' else 'chunk')),
        ('invalid boundary',lambda r:r.update(unloadBoundary='invalid')),
        ('wrong save interval',lambda r:r.update(saveIntervalSeconds=300 if r['saveIntervalSeconds']==5 else 5)),
        ('invalid save interval',lambda r:r.update(saveIntervalSeconds=0)),
        ('downgraded schema',lambda r:r.update(schemaVersion=2))]:
        r=copy.deepcopy(report);change(r);controls.append((label,r,payloads))
    text('wrong native clock config','native-clock.yml',lambda t:t.replace('Saving:', 'Wrong:'))
    if report['saveIntervalSeconds'] == 5:
        for label,change in [
            ('missing periodic save',lambda r:r['clockCheckpoints'].pop()),
            ('duplicate periodic save',lambda r:r['clockCheckpoints'].append(r['clockCheckpoints'][0])),
            ('wrong clock value',lambda r:r['clockCheckpoints'][0].update(value=0)),
            ('clock captured after exit',lambda r:r['clockCheckpoints'][0].update(processAlive=False)),
            ('clock captured outside boot',lambda r:r['clockCheckpoints'][0].update(capturedAtUtc=r['boots'][0]['stoppedAtUtc'])),
            ('clock captures reversed',lambda r:r['clockCheckpoints'][0].update(capturedAtUtc=r['clockCheckpoints'][1]['capturedAtUtc']))]:
            r=copy.deepcopy(report);change(r);controls.append((label,r,payloads))
        for label,source,target in [
            ('missing clock mutation','DCRASH_CLOCK_SET|','UNKNOWN|'),
            ('wrong clock sequence','|sequence=1|','|sequence=2|'),
            ('wrong live clock value','|value=101|','|value=102|')]:
            text(label,'write.server.log',lambda t,s=source,d=target:t.replace(s,d,1))
        text('stale periodic file','snapshots/write-clock-2/global-data.json',lambda t:t.replace('102','101'))
        p=dict(payloads);p.pop('snapshots/write-clock-1/global-data.json');controls.append(('missing periodic file',report,p))
    if report['unloadBoundary'] == 'chunk':
        for label,source,target in [
            ('missing chunk event','DCRASH_CHUNK_EVENT|','UNKNOWN|'),
            ('chunk saving disabled','|save=true|','|save=false|'),
            ('force control lost','|wasForced=true|','|wasForced=false|'),
            ('missing chunk absence','DCRASH_CHUNKS_ABSENT|','UNKNOWN|'),
            ('world absent','|worldPresent=true|','|worldPresent=false|'),
            ('missing chunk count','|chunks=10|','|chunks=9|'),
            ('missing retained unload event','|event=unload|','|event=load|')]:
            text(label,'write.server.log',lambda t,s=source,d=target:t.replace(s,d,1))
        text('world unload masquerades as chunk unload','write.server.log',lambda t:t.replace('DCRASH_READY|','DCRASH_WORLD_EVENT|stage=write|world=despawn-crash-unloaded|cancelled=false\nDCRASH_READY|',1))
        text('chunk event before request','write.server.log',lambda t:t.replace('DCRASH_CHUNK_REQUEST|','DCRASH_CHUNK_EVENT|stage=write|group=unloaded|x=300|z=300|save=true|tick=0\nDCRASH_CHUNK_REQUEST|',1))
for label,r,p in controls:
    try:v.validate(r,p)
    except (ValueError,KeyError):continue
    raise AssertionError('Accepted invalid evidence: '+label)
print('Rejected',len(controls),'crash evidence mutations.')
