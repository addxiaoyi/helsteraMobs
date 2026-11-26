"""Semantic tamper checks; --self-test builds only synthetic in-memory data, never real evidence."""
import copy
import functools
import importlib.util
import json
from pathlib import Path
import sys
import uuid
import yaml
sys.dont_write_bytecode=True
spec=importlib.util.spec_from_file_location('scoreboard_audit',Path(__file__).with_name('audit-scoreboard-actions.py'))
v=importlib.util.module_from_spec(spec);spec.loader.exec_module(v)
# Each mutation changes one immutable bytes payload; cache unchanged YAML during exhaustive replay.
v.doc=functools.lru_cache(maxsize=8192)(v.doc)

def rehash(report, files): report['files']=[{'path':name,'sha256':v.digest(value)} for name,value in sorted(files.items())]
def reject(action):
    try: action()
    except (ValueError,KeyError,TypeError): return
    raise AssertionError('Invalid evidence accepted')

def test(report,files):
    assert v.audit(report,files)==len(v.c.CASES)*2
    count=0
    def mutate(name,data):
        nonlocal count
        r=copy.deepcopy(report);f=dict(files);f[name]=data;rehash(r,f);reject(lambda:v.audit(r,f));count+=1
    for name,data in files.items():
        if '/observations/' not in name: continue
        row=v.doc(data)
        for field in row:
            bad=copy.deepcopy(row)
            bad[field]='tampered'
            mutate(name,yaml.safe_dump(bad,sort_keys=False).encode())
        for field in ('nativeBefore','runtimeBefore','nativeAfter','runtimeAfter'):
            for objective,scores in row[field].items():
                if not isinstance(scores,dict):continue
                for holder in scores:
                    bad=copy.deepcopy(row);bad[field][objective][holder]+=1
                    mutate(name,yaml.safe_dump(bad,sort_keys=False).encode())
        bad=copy.deepcopy(row);bad['unexpected']=True
        mutate(name,yaml.safe_dump(bad).encode())
    for name in files:
        if '/observations/' in name:continue
        mutate(name,b'{}\n')
    for field,bad in [('evidenceMode','scoped-runtime'),('parityStatus','passed'),('exitCode',True),
                      ('clientExitCode',1),('validationResult','rejected'),('sourceHashes',{}),('observationCount',0)]:
        r=copy.deepcopy(report);r[field]=bad;reject(lambda:v.audit(r,files));count+=1
    print(f'Rejected {count} scoreboard semantic mutations.')

def synthetic():
    files={}
    player=str(uuid.uuid4())
    log=[];i=0
    for path in v.PATHS:
        ids={role:str(uuid.uuid4()) for role in ('caster','target','trigger','other','interaction')}
        ids.update(player=player,playerName='ScoreboardPlayer')
        files[v.PROBE+'identities-'+path+'.yml']=yaml.safe_dump(ids).encode()
        for sample in v.c.CASES:
            objective,sentinel,source,before,native_after,runtime_after=v.expected(sample,i,ids)
            same=native_after==runtime_after
            row=dict(case=sample['key'],path=path,source=source,
                     mapped=source if path=='direct' else v.c.canonical(sample['mechanic'])+source[len(sample['mechanic']):],
                     objective=objective,sentinel=sentinel,nativeResult=sample['result'],
                     nativeBefore=before,runtimeBefore=before,nativeAfter=native_after,runtimeAfter=runtime_after,
                     sameState=same,primary=True,tick=100)
            files[v.PROBE+f'observations/{i:04d}.yml']=yaml.safe_dump(row,sort_keys=False).encode()
            log.append(f'SCOREBOARD_ROW|case={sample["key"]}|path={path}|nativeResult={sample["result"]}|sameState={str(same).lower()}')
            i+=1
    log.extend([f'SCOREBOARD_SUMMARY|observations={i}|completed=true','[00:00:00 INFO]: Stopping server'])
    files['server.log']=('\n'.join(log)+'\n').encode()
    files['client.log']=('\n'.join('SCOREBOARD_CLIENT '+json.dumps(value) for value in [
        dict(event='join',name='ScoreboardPlayer',uuid=player),dict(event='summary',passed=True)])+'\n').encode()
    files[v.PROBE+'cases.yml']=yaml.safe_dump(v.c.source_cases(),sort_keys=False).encode()
    files['plugins/MythicMobs/Mobs/scoreboard.yml']=yaml.safe_dump({'HelsteraRadiusAuditMob':{'Type':'COW','Options':{'NoAI':True,'Despawn':'PERSISTENT'}}}).encode()
    report=dict(schemaVersion=1,evidenceMode='observation',parityStatus='unassessed',validationResult='passed',exitCode=0,clientExitCode=0,
                paperJarSha256='39bd8c00b9e18de91dcabd3cc3dcfa5328685a53b7187a2f63280c22e2d287b9',
                mythicMobsSha256='9672457cafc56ff841606bfbb710947329c08720ffc207746b60130d43bf902f',
                pluginSha256='0'*64,probeJarSha256='1'*64,sourceHashes={name:'2'*64 for name in v.SOURCE_FILES},observationCount=i)
    rehash(report,files)
    return report,files

if __name__=='__main__':
    if sys.argv[1]=='--self-test':
        test(*synthetic());print('Synthetic in-memory audit selfcheck only; no real evidence created or accepted.')
    else:test(*v.load(sys.argv[1]))
