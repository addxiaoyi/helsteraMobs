"""Mutate completed Paper evidence to exercise the independent lifecycle validator."""
import copy
import hashlib
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import zipfile

sys.dont_write_bytecode = True
spec = importlib.util.spec_from_file_location('life_validator', Path(__file__).with_name('validate-player-life.py'))
validator = importlib.util.module_from_spec(spec); spec.loader.exec_module(validator)
report, texts = validator.main(sys.argv[1])
controls = []


def row_control(label, predicate, transform):
    changed, logs = copy.deepcopy(report), dict(texts)
    index = next(i for i,row in enumerate(changed['observations']) if predicate(row))
    before = changed['observations'][index]; after = transform(before)
    assert before != after, label
    changed['observations'][index] = after
    logs['server.log'] = logs['server.log'].replace(before,after)
    controls.append((label,changed,logs))


row_control('missing row',lambda _:True,lambda _:'')
row_control('duplicate row',lambda _:True,lambda _:report['observations'][1])
row_control('wrong owner',lambda _:True,lambda r:r.replace(validator.registry.offline_id('VariableOwner'),validator.registry.offline_id('VariablePeer')))
row_control('stale generation',lambda r:'|phase=reconnect2|' in r and '|case=Generation|' in r,lambda r:r.replace('native=3|runtime=3','native=2|runtime=2'))
row_control('wrong scalar',lambda r:'|phase=respawn|' in r and '|case=Saved|' in r,lambda r:r.replace('native=7|runtime=7','native=8|runtime=8'))
row_control('death expiry returns',lambda r:'|phase=respawn|' in r and '|case=DeadExpiry|' in r,lambda r:r.replace('native=absent|runtime=absent','native=7|runtime=7'))
row_control('deletion returns',lambda r:'|phase=final|' in r and '|case=Saved|' in r,lambda r:r.replace('native=absent|runtime=absent','native=7|runtime=7'))
row_control('deadline reset',lambda r:'|phase=reconnect1|' in r and '|case=Timed|' in r,lambda r:r.replace('runtimeDeadline=','runtimeDeadline=1'))
row_control('save marker changed',lambda r:'|phase=write|' in r and '|case=Default|' in r,lambda r:r.replace('Deadline=1','Deadline=0'))
row_control('player leak',lambda _:True,lambda r:r.replace('isolated=true','isolated=false'))
row_control('native type changed',lambda r:'|case=Saved|' in r,lambda r:r.replace('nativeType=Integer','nativeType=Float'))
row_control('runtime type changed',lambda r:'|case=Saved|' in r,lambda r:r.replace('runtimeType=Integer','runtimeType=Float'))
row_control('wrong location world',lambda r:'|case=Location|' in r,lambda r:r.replace('integration-world','elsewhere'))
row_control('lost respawn write',lambda r:'PLAYER_LIFE_HOOK|phase=respawn|' in r,lambda r:r.replace('native=2|runtime=2','native=1|runtime=1'))
for key,value in [('pluginSha256',''),('saveIntervalSeconds',0),('mythicMobsSha256','0'*64)]:
    changed=copy.deepcopy(report);changed[key]=value;controls.append(('wrong '+key,changed,texts))


def log_control(label,path,transform):
    logs=dict(texts);logs[path]=transform(logs[path]);assert logs[path]!=texts[path],label
    controls.append((label,report,logs))


log_control('missing clock','server.log',lambda t:t.replace('nativeSeconds=300','nativeSeconds=0'))
log_control('missing phase','server.log',lambda t:t.replace('PLAYER_LIFE_PHASE|phase=cleared','PLAYER_LIFE_PHASE|phase=unknown'))
log_control('missing death','server.log',lambda t:t.replace('PLAYER_LIFE_EVENT|event=death','PLAYER_LIFE_EVENT|event=unknown',1))
log_control('missing respawn','server.log',lambda t:t.replace('PLAYER_LIFE_EVENT|event=respawn','PLAYER_LIFE_EVENT|event=unknown',1))
log_control('lost callback write','server.log',lambda t:t.replace('|value=1|native=1|runtime=1','|value=1|native=1|runtime=0',1))
log_control('offline retained','server.log',lambda t:t.replace('|attempt=1|native=true|runtime=true','|attempt=1|native=true|runtime=false',1))
log_control('missing quit','server.log',lambda t:t.replace('PLAYER_LIFE_SESSION|event=quit','PLAYER_LIFE_SESSION|event=unknown',1))
log_control('failed probe','server.log',lambda t:t+'\nPLAYER_LIFE_FAILED|error=injected\n')
log_control('missing client login','client.log',lambda t:t.replace('"event":"join"','"event":"unknown"',1))
log_control('missing client death','client.log',lambda t:t.replace('"event":"death"','"event":"unknown"',1))
log_control('missing client respawn','client.log',lambda t:t.replace('"event":"respawned"','"event":"unknown"',1))
log_control('client failed','client.log',lambda t:t.replace('"passed":true','"passed":false'))


def client_time(text,predicate,transform):
    lines=text.splitlines()
    index=next(i for i,line in enumerate(lines) if 'PLAYER_LIFE_CLIENT ' in line and predicate(json.loads(line.split('PLAYER_LIFE_CLIENT ',1)[1])))
    event=json.loads(lines[index].split('PLAYER_LIFE_CLIENT ',1)[1]);transform(event)
    lines[index]='PLAYER_LIFE_CLIENT '+json.dumps(event)
    return '\n'.join(lines)+'\n'


log_control('no dead wait','client.log',lambda t:client_time(t,lambda e:e['event']=='respawn-request',lambda e:e.update(elapsedMillis=0)))
log_control('delayed reconnect','client.log',lambda t:client_time(t,lambda e:e['event']=='connect' and e['attempt']==2,lambda e:e.update(elapsedMillis=e['elapsedMillis']+500)))
identity=validator.registry.offline_id('VariableOwner');path='logout/native-'+identity+'.json'
for kind,change in [('type',lambda v:v.update(type='FLOAT')),('value',lambda v:v.update(value=99)),('deadline',lambda v:v.update(expirationTime=1))]:
    doc=json.loads(texts[path]);change(doc['variables']['entries']['LifeD0casterGeneration'])
    log_control('wrong disk '+kind,path,lambda _,doc=doc:json.dumps(doc))
doc=json.loads(texts[path]);doc['variables']['entries']['LifeD0casterDeleted']=dict(doc['variables']['entries']['LifeD0casterGeneration'])
log_control('deleted disk data returns',path,lambda _:json.dumps(doc))
log_control('duplicate JSON key',path,lambda t:t.rstrip()[:-1]+',"name":"VariableOwner"}')
runtime_path='logout/runtime-'+identity+'.yml'
log_control('duplicate YAML key',runtime_path,lambda t:t+'\nregistry-format: 1\n')
for label,changed,logs in controls:
    try:validator.validate(changed,logs)
    except ValueError:continue
    raise AssertionError('accepted invalid evidence: '+label)

# Exercise archive/member hashes independently of semantic row checks.
with tempfile.TemporaryDirectory(prefix='player-life-controls-') as directory:
    control_root=Path(directory); archive_path=control_root/'evidence.zip'; report_path=control_root/'report.json'
    for kind in ('archive-hash','member-hash','duplicate-member','extra-member'):
        changed=copy.deepcopy(report);changed['logArchive']=archive_path.name
        with zipfile.ZipFile(archive_path,'w',zipfile.ZIP_DEFLATED) as archive:
            for name,body in texts.items():archive.writestr(name,body+(' ' if kind=='member-hash' and name=='server.log' else ''))
            if kind=='duplicate-member':
                import warnings
                with warnings.catch_warnings():
                    warnings.simplefilter('ignore',UserWarning);archive.writestr('server.log',texts['server.log'])
            if kind=='extra-member':archive.writestr('extra.log','unexpected')
        changed['logArchiveSha256']='0'*64 if kind=='archive-hash' else hashlib.sha256(archive_path.read_bytes()).hexdigest()
        report_path.write_text(json.dumps(changed))
        try:validator.main(report_path)
        except ValueError:continue
        raise AssertionError('accepted invalid evidence: '+kind)
print(f'Rejected all {len(controls)+4} player life evidence mutation controls.')
