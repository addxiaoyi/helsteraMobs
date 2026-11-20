"""Reject manipulated world/chunk lifecycle evidence from a real completed fixture."""
import copy
import importlib.util
import json
from pathlib import Path
import sys
sys.dont_write_bytecode=True
spec=importlib.util.spec_from_file_location('world_validator',Path(__file__).with_name('validate-world-variable-life.py'))
validator=importlib.util.module_from_spec(spec);spec.loader.exec_module(validator)
report,texts=validator.main(sys.argv[1]);controls=[]


def row(label,predicate,transform):
    changed=copy.deepcopy(report);logs=dict(texts)
    index=next(i for i,r in enumerate(changed['observations']) if predicate(r))
    before=changed['observations'][index];after=transform(before);assert before!=after,label
    changed['observations'][index]=after;logs['server.log']=logs['server.log'].replace(before,after)
    controls.append((label,changed,logs))


row('missing row',lambda _:True,lambda _:'')
row('duplicate row',lambda _:True,lambda _:report['observations'][1])
row('wrong scalar',lambda r:'|case=Saved|' in r,lambda r:r.replace('native=7|runtime=7','native=8|runtime=8'))
row('expired value returns',lambda r:'|phase=chunk|' in r and '|case=Expired|' in r,lambda r:r.replace('native=absent|runtime=absent','native=7|runtime=7'))
row('deleted value returns',lambda r:'|phase=final|' in r and '|case=Saved|' in r,lambda r:r.replace('native=absent|runtime=absent','native=7|runtime=7'))
row('wrong type',lambda r:'|case=Saved|' in r,lambda r:r.replace('runtimeType=Integer','runtimeType=Double'))
row('reset deadline',lambda r:'|phase=world|' in r and '|case=Timed|' in r,lambda r:r.replace('runtimeDeadline=','runtimeDeadline=1'))
row('wrong location',lambda r:'|case=Location|' in r,lambda r:r.replace('4113.0','4114.0'))
row('wrong marker',lambda r:'|case=Transient|' in r,lambda r:r.replace('Deadline=1','Deadline=0'))


def log(label,path,transform):
    changed=dict(texts);changed[path]=transform(changed[path]);assert changed[path]!=texts[path],label
    controls.append((label,report,changed))


log('failed probe','server.log',lambda t:t+'\nWORLD_VAR_FAILED|error=injected\n')
log('missing chunk unload','server.log',lambda t:t.replace('WORLD_VAR_EVENT|event=entity-unload','WORLD_VAR_EVENT|event=unknown',1))
log('missing entity load','server.log',lambda t:t.replace('WORLD_VAR_EVENT|event=entity-load','WORLD_VAR_EVENT|event=unknown',1))
log('missing world unload','server.log',lambda t:t.replace('WORLD_VAR_EVENT|event=world-unload','WORLD_VAR_EVENT|event=unknown',1))
log('world still loaded','server.log',lambda t:t.replace('|absent=true','|absent=false',1))
log('entities still present','server.log',lambda t:t.replace('|entitiesAbsent=true','|entitiesAbsent=false',1))
log('missing death','server.log',lambda t:t.replace('WORLD_VAR_EVENT|event=death','WORLD_VAR_EVENT|event=unknown',1))
log('dead registry retained','server.log',lambda t:t.replace('|runtimeGone=true','|runtimeGone=false',1))
log('dead snapshot retained','server.log',lambda t:t.replace('|snapshotGone=true','|snapshotGone=false',1))
log('lost tree','server.log',lambda t:t.replace('WORLD_VAR_TREE|phase=chunk|path=direct|passed=true','WORLD_VAR_TREE|phase=chunk|path=direct|passed=false'))
log('missing visual observation','server.log',lambda t:t.replace('WORLD_VAR_VISUALS|phase=chunk|','WORLD_VAR_UNKNOWN|phase=chunk|'))
log('duplicated displays','server.log',lambda t:t.replace('|expected=22|actual=22','|expected=22|actual=44',1))
log('changed visual baseline','server.log',lambda t:t.replace('|expected=22|actual=22','|expected=44|actual=44',1))
log('changed reattached identity','server.log',lambda t:t.replace('WORLD_VAR_ATTACHED|phase=chunk|path=direct|native=','WORLD_VAR_ATTACHED|phase=chunk|path=direct|native=0'))
log('stale cleared tree','snapshots/final-direct.yml',lambda _:texts['snapshots/world-direct.yml'])
log('wrong disk value','snapshots/world-direct.yml',lambda t:t.replace('value: 23','value: 24'))
log('duplicate YAML key','snapshots/chunk-direct.yml',lambda t:t+'\nsnapshot-format: 4\n')
log('resurrected scoped data','shutdown-scoped.yml',lambda t:t.replace('registries: {}','registries: {stale: {value: 7}}'))
changed=copy.deepcopy(report);changed['pluginSha256']='';controls.append(('missing artifact',changed,texts))
for label,changed,logs in controls:
    try:validator.validate(changed,logs)
    except ValueError:continue
    raise AssertionError('Accepted invalid evidence: '+label)
print(f'Rejected all {len(controls)} world lifecycle evidence mutation controls.')
