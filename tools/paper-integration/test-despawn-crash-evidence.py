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
 ('missing unload','DCRASH_WORLD_UNLOAD|','UNKNOWN|'),
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
for label,r,p in controls:
    try:v.validate(r,p)
    except (ValueError,KeyError):continue
    raise AssertionError('Accepted invalid evidence: '+label)
print('Rejected',len(controls),'crash evidence mutations.')
