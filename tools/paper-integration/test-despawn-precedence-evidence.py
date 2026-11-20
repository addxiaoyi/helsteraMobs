"""Mutation controls against incomplete and mutually wrong precedence observations."""
import copy
import importlib.util
from pathlib import Path
import sys
sys.dont_write_bytecode=True
spec=importlib.util.spec_from_file_location('validator',Path(__file__).with_name('validate-despawn-precedence.py'));v=importlib.util.module_from_spec(spec);spec.loader.exec_module(v)
report,texts=v.main(sys.argv[1]);controls=[]
def log(label,path,change):
    logs=dict(texts);logs[path]=change(logs[path]);assert logs[path]!=texts[path],label;controls.append((label,report,logs))
def row(label,predicate,change):
    r=copy.deepcopy(report);logs=dict(texts);i=next(i for i,o in enumerate(r['observations']) if predicate(o['line']));before=r['observations'][i]['line'];after=change(before)
    assert before!=after,label;r['observations'][i]['line']=after
    for name in logs:
        if name.endswith('.log'):logs[name]=logs[name].replace(before,after)
    controls.append((label,r,logs))
row('missing row',lambda _:True,lambda _:'')
row('both wrong mode',lambda l:'key=normal_options_true|' in l and 'phase=write|' in l,lambda l:l.replace('Mode=PERSISTENT','Mode=NORMAL'))
row('both wrong saving',lambda l:'key=chunk_root_true|' in l and 'phase=write|' in l,lambda l:l.replace('Save=true','Save=false'))
row('both wrong distance flag',lambda l:'key=normal_options_false|' in l and 'phase=write|' in l,lambda l:l.replace('Far=true','Far=false'))
row('lost retained mob',lambda l:'key=chunk_root_true|' in l and 'phase=restored|' in l,lambda l:l.replace('runtimeEntity=true','runtimeEntity=false'))
row('unexpected restored mob',lambda l:'key=chunk_options_false|' in l and 'phase=restored|' in l,lambda l:l.replace('runtimeEntity=false','runtimeEntity=true'))
row('dead registry remains',lambda l:'key=chunk_root_true|' in l and 'phase=dead|' in l,lambda l:l.replace('runtimeInstance=false','runtimeInstance=true'))
row('wrong UUID',lambda _:True,lambda l:l.replace('|native=','|native=0'))
for group in v.contract.GLOBALS:
    log('global flip '+group,group+'/write.log',lambda s:s.replace('DPREC_GLOBAL|value=true','DPREC_GLOBAL|value=WRONG').replace('DPREC_GLOBAL|value=false','DPREC_GLOBAL|value=WRONG'))
log('wrong config', 'false/defaults.yml',lambda s:s.replace('Despawn: false','Despawn: true'))
log('duplicate death', 'true/verify.log',lambda s:s+'\n'+next(l for l in s.splitlines() if 'DPREC_DEATH|' in l))
log('lost death callback','true/verify.log',lambda s:s.replace('DPREC_HOOK runtime death chunk_root_true','REMOVED',1))
log('lost despawn callback','true/write.log',lambda s:s.replace('DPREC_HOOK runtime despawn chunk_options_false','REMOVED',1))
log('wrong root source','true/source.yml',lambda s:s.replace('Despawn: NORMAL','Despawn: NPC',1))
log('removed warning','true/migration.yml',lambda s:s.replace('severity: WARN','severity: INFO'))
log('changed import','true/imports/normal_options_true.yml',lambda s:s.replace('despawn: PERSISTENT','despawn: NORMAL'))
log('dropped unsupported','true/imports/invalid_persistent_quoted_true.yml',lambda s:s.replace('unsupported:', 'lost:'))
log('missing native shutdown stack','true/write.log',lambda s:s.replace('MythicMobs.jar//io.lumine.mythic.core.skills.SkillMechanic.runMechanic','other'))
log('numeric zero is not false','zero/defaults.yml',lambda s:s.replace('Despawn: 0','Despawn: false'))
log('numeric one is not true','true/source.yml',lambda s:s.replace('Despawn: 1','Despawn: true',1))
for field,value in [('paperJarSha256','0'*64),('mythicMobsSha256','0'*64),('knownExceptions',[]),('observations',[]),('boots',report['boots'][:-1])]:
    r=copy.deepcopy(report);r[field]=value;controls.append((field,r,texts))
for label,r,logs in controls:
    try:v.validate(r,logs)
    except (ValueError,KeyError):continue
    raise AssertionError('Mutation accepted: '+label)
print(f'Rejected all {len(controls)} precedence evidence mutations.')
