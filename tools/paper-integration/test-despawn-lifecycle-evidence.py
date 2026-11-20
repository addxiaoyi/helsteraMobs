"""Mutation controls against actual, archived Despawn lifecycle evidence."""
import copy
import importlib.util
from pathlib import Path
import sys
sys.dont_write_bytecode=True
spec=importlib.util.spec_from_file_location('despawn_validator',Path(__file__).with_name('validate-despawn-lifecycle.py'))
validator=importlib.util.module_from_spec(spec);spec.loader.exec_module(validator)
report,texts=validator.main(sys.argv[1]);controls=[]

def row(label,predicate,transform):
    changed=copy.deepcopy(report);logs=dict(texts)
    index=next(i for i,r in enumerate(changed['observations']) if predicate(r))
    before=changed['observations'][index];after=transform(before);assert before!=after,label
    changed['observations'][index]=after;logs['server.log']=logs['server.log'].replace(before,after)
    controls.append((label,changed,logs))

def log(label,path,transform):
    changed=dict(texts);changed[path]=transform(changed[path]);assert changed[path]!=texts[path],label
    controls.append((label,report,changed))

row('missing row',lambda _:True,lambda _:'')
row('duplicate row',lambda _:True,lambda _:report['observations'][1])
row('both wrong scalar',lambda r:'|phase=initial|' in r,lambda r:r.replace('Value=7','Value=8'))
row('lost never registry',lambda r:'|phase=reloaded|' in r and '|mode=NEVER|' in r,lambda r:r.replace('runtimeValue=7','runtimeValue=absent'))
row('orphan base returns',lambda r:'|phase=reloaded|' in r and '|mode=CHUNK|' in r,lambda r:r.replace('runtimeEntity=false','runtimeEntity=true'))
row('missing instance',lambda r:'|phase=reloaded|' in r and '|mode=NEVER|' in r,lambda r:r.replace('runtimeInstance=true','runtimeInstance=false'))
row('lost tree',lambda r:'|phase=reloaded|' in r and '|mode=NEVER|' in r,lambda r:r.replace('runtimeTree=23','runtimeTree=absent'))
row('wrong location',lambda r:'|phase=reloaded|' in r and '|mode=NEVER|' in r,lambda r:r.replace(',110.0,',',111.0,'))
row('lost location',lambda r:'|phase=reloaded|' in r and '|mode=NEVER|' in r,lambda r:r.replace('runtimePoint=true','runtimePoint=false'))
row('death dispatches despawn',lambda r:'|phase=dead|' in r and '|mode=PERSISTENT|' in r,lambda r:r.replace('runtimeDespawn=0','runtimeDespawn=1'))
row('no despawn callback',lambda r:'|phase=reloaded|' in r and '|mode=NORMAL|' in r,lambda r:r.replace('runtimeDespawn=1','runtimeDespawn=0'))
row('dead snapshot retained',lambda r:'|phase=dead|' in r,lambda r:r.replace('snapshotGone=true','snapshotGone=false'))
row('cancelled runtime cleaned',lambda r:'|phase=cancelled|' in r,lambda r:r.replace('runtimeEntity=true','runtimeEntity=false'))
row('native exception hidden',lambda r:'|phase=cancelled|' in r,lambda r:r.replace('nativeRegistry=false','nativeRegistry=true'))
row('exception outside cancellation',lambda r:'|phase=initial|' in r,lambda r:r.replace('knownNativeCancellation=false','knownNativeCancellation=true'))
log('failed probe','server.log',lambda t:t+'\nDESPAWN_FAILED|error=injected\n')
log('missing hook','server.log',lambda t:t.replace('DESPAWN_HOOK|','DESPAWN_UNKNOWN|',1))
log('duplicated hook','server.log',lambda t:t.replace(next(l for l in t.splitlines() if 'DESPAWN_HOOK|' in l),next(l for l in t.splitlines() if 'DESPAWN_HOOK|' in l)+'\n'+next(l for l in t.splitlines() if 'DESPAWN_HOOK|' in l)))
log('missing mapping','server.log',lambda t:t.replace('DESPAWN_MAPPING|','DESPAWN_UNKNOWN|',1))
log('bad mapping','server.log',lambda t:t.replace('|trigger=true','|trigger=false',1))
log('missing chunk event','server.log',lambda t:t.replace('|event=chunk-unload','|event=unknown',1))
log('missing world event','server.log',lambda t:t.replace('|event=world-unload','|event=unknown',1))
log('fake unload','server.log',lambda t:t.replace('|unloaded=true','|unloaded=false',1))
log('missing load','server.log',lambda t:t.replace('|event=load','|event=unknown',1))
log('missing death','server.log',lambda t:t.replace('|event=death|uuid=','|event=unknown|uuid=',1))
log('cancellation ignored','server.log',lambda t:t.replace('|cancelled=true','|cancelled=false',1))
log('duplicated visuals','server.log',lambda t:t.replace('|expected=44|actual=44','|expected=44|actual=88',1))
log('tampered baseline','server.log',lambda t:t.replace('|expected=110|actual=110','|expected=220|actual=220',1))
log('missing snapshot','snapshots/chunk_never_direct.yml',lambda t:t.replace('value: 23','value: 24'))
log('duplicate YAML','snapshots/chunk_never_direct.yml',lambda t:t+'\nsnapshot-format: 4\n')
log('cancelled registry lost','shutdown-scoped.yml',lambda _: 'registry-format: 1\nregistries: {}\n')
changed=copy.deepcopy(report);changed['pluginSha256']='';controls.append(('missing artifact',changed,texts))
changed=copy.deepcopy(report);changed['knownExceptions']=[];controls.append(('exception undocumented',changed,texts))
for label,changed,logs in controls:
    try:validator.validate(changed,logs)
    except (ValueError,KeyError):continue
    raise AssertionError('Accepted invalid evidence: '+label)
print(f'Rejected all {len(controls)} Despawn evidence mutation controls.')
