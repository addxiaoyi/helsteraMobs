"""Reject altered evidence from a completed real autosave/crash fixture."""
import copy
import importlib.util
import json
from pathlib import Path
import sys

sys.dont_write_bytecode = True
spec = importlib.util.spec_from_file_location('autosave_validator', Path(__file__).with_name('validate-player-autosave.py'))
validator = importlib.util.module_from_spec(spec); spec.loader.exec_module(validator)
report, texts = validator.main(sys.argv[1])
controls = []


def row_control(label, stage, predicate, transform):
    changed, logs = copy.deepcopy(report), dict(texts)
    entry = next(s for s in changed['stages'] if s['stage'] == stage)
    index = next(i for i, row in enumerate(entry['observations']) if predicate(row))
    before = entry['observations'][index]; after = transform(before)
    assert before != after, label
    entry['observations'][index] = after
    logs[stage+'.server.log'] = logs[stage+'.server.log'].replace(before, after)
    controls.append((label, changed, logs))


row_control('missing row', 'write', lambda _:True, lambda _:'')
row_control('duplicate row', 'write', lambda _:True, lambda _:report['stages'][0]['observations'][1])
row_control('changed UUID', 'write', lambda _:True, lambda r:r.replace(validator.registry.offline_id('VariableOwner'),validator.registry.offline_id('VariablePeer')))
row_control('stale recovered generation', 'recover', lambda r:'|case=Generation|' in r, lambda r:r.replace('native=2|runtime=2','native=1|runtime=1'))
row_control('wrong scalar', 'recover', lambda r:'|case=Saved|' in r, lambda r:r.replace('native=7|runtime=7','native=8|runtime=8'))
row_control('expired value returns', 'recover', lambda r:'|case=OfflineExpiry|' in r, lambda r:r.replace('native=absent|runtime=absent','native=7|runtime=7'))
row_control('deleted value returns', 'final', lambda r:'|case=Saved|' in r, lambda r:r.replace('native=absent|runtime=absent','native=7|runtime=7'))
row_control('deadline reset', 'recover', lambda r:'|case=Timed|' in r, lambda r:r.replace('runtimeDeadline=','runtimeDeadline=1'))
row_control('wrong save marker', 'write', lambda r:'|case=Default|' in r, lambda r:r.replace('Deadline=1','Deadline=0'))
row_control('cross-player leak', 'write', lambda _:True, lambda r:r.replace('isolated=true','isolated=false'))
row_control('changed runtime type', 'write', lambda r:'|case=Saved|' in r, lambda r:r.replace('nativeType=Integer','nativeType=Float'))
changed=copy.deepcopy(report);changed['stages'].pop();controls.append(('missing boot',changed,texts))
changed=copy.deepcopy(report);changed['pluginSha256']='';controls.append(('missing artifact',changed,texts))
changed=copy.deepcopy(report);changed['stages'][0]['termination']='graceful';controls.append(('graceful first boot',changed,texts))
changed=copy.deepcopy(report);changed['stages'][1]['exitCode']=0;controls.append(('normal second exit',changed,texts))
changed=copy.deepcopy(report);changed['checkpoints'].pop();controls.append(('missing checkpoint',changed,texts))
changed=copy.deepcopy(report);changed['checkpoints'][0]['onlinePlayers']=0;controls.append(('offline checkpoint',changed,texts))


def log_control(label, path, transform):
    logs=dict(texts);logs[path]=transform(logs[path]);assert logs[path]!=texts[path],label
    controls.append((label,report,logs))


log_control('quit before kill','write.server.log',lambda t:t+'\nPLAYER_AUTO_SESSION|event=quit|name=VariableOwner\n')
log_control('plugin disabled before kill','recover.server.log',lambda t:t+'\nPLAYER_AUTO_DISABLE|stage=recover\n')
log_control('missing clock','write.server.log',lambda t:t.replace('nativeSeconds=5','nativeSeconds=300'))
log_control('missing client join','write.client.log',lambda t:t.replace('"event":"join"','"event":"unknown"',1))
log_control('missing phase','recover.server.log',lambda t:t.replace('PLAYER_AUTO_PHASE|phase=cleared','PLAYER_AUTO_PHASE|phase=wrong'))
identity=validator.registry.offline_id('VariableOwner')
name='native-'+identity+'.json'
log_control('stale online file','updated-online/'+name,lambda _:texts['write-online/'+name])
log_control('changed file after kill','updated-killed/'+name,lambda t:t+' ')
for kind, change in [('type',lambda v:v.update(type='FLOAT')),('value',lambda v:v.update(value=99)),('deadline',lambda v:v.update(expirationTime=1))]:
    path='write-online/'+name
    doc=json.loads(texts[path]);change(doc['variables']['entries']['AutoD0casterSaved'])
    log_control('wrong checkpoint '+kind,path,lambda _,doc=doc:json.dumps(doc))
log_control('duplicate JSON key','write-online/'+name,lambda t:t[:-1]+',"name":"VariableOwner"}')
path='write-online/runtime-'+identity+'.yml'
log_control('duplicate YAML key',path,lambda t:t+'\nregistry-format: 1\n')
for label, changed, logs in controls:
    try:validator.validate(changed,logs)
    except ValueError:continue
    raise AssertionError('accepted invalid evidence: '+label)
print(f'Rejected all {len(controls)} autosave evidence mutation controls.')
