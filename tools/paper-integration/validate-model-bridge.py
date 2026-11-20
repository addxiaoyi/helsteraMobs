"""Validate native model-bridge state, actual command gates, source identity and cleanup."""
from collections import Counter
from datetime import datetime, timedelta
import hashlib
import importlib.util
import io
import json
from pathlib import Path, PurePosixPath
import re
import sys
import uuid
import zipfile
import yaml
sys.dont_write_bytecode=True

def module(name,filename):
    spec=importlib.util.spec_from_file_location(name,Path(__file__).with_name(filename));result=importlib.util.module_from_spec(spec);spec.loader.exec_module(result);return result
c=module('model_contract','model-bridge-cases.py');base=module('model_registry','validate-player-variables.py')
require,fields,unique=base.require,base.fields,base.unique_pairs
FILES={'server.log','source-mobs.yml','source-skills.yml','source-integrations.yml','reloaded-skills.yml'}
SOURCES={'run-model-bridge-fixture.py','run-player-variable-fixture.py','model-bridge-cases.py','validate-model-bridge.py','validate-player-variables.py','test-model-bridge-evidence.py',
    'random-spawn-fixture/src/main/java/dev/helstera/test/randomspawn/ModelBridgeProbe.java'}
def digest(data):return hashlib.sha256(data).hexdigest()
def hash_value(value):return isinstance(value,str) and re.fullmatch('[a-f0-9]{64}',value) is not None
def safe_path(value):
    require(isinstance(value,str) and value and '\\' not in value and ':' not in value,'unsafe path')
    path=PurePosixPath(value);require(not path.is_absolute() and '..' not in path.parts and str(path)==value,'unsafe path');return value
def manifest(report):
    result={}
    for item in report['files']:
        require(set(item)=={'path','sha256'},'invalid file entry');name=safe_path(item['path'])
        require(name not in result and hash_value(item['sha256']),'duplicate member or invalid hash');result[name]=item['sha256']
    require(set(result)==FILES,'missing/orphan manifest member');return result
def verify_payloads(report,payloads):
    entries=manifest(report);require(set(payloads)==set(entries),'missing/orphan payload')
    for name,h in entries.items():require(digest(payloads[name])==h,'member changed: '+name)
def archive_payloads(report,data):
    require(hash_value(report['logArchiveSha256']) and digest(data)==report['logArchiveSha256'],'archive changed')
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        names=archive.namelist();require(len(names)==len(set(names)) and set(names)==set(manifest(report)),'archive membership changed')
        payloads={n:archive.read(n) for n in names}
    verify_payloads(report,payloads);return payloads
def doc(data):return yaml.load(data,Loader=base.UniqueYaml)
def utc(text):
    result=datetime.fromisoformat(text);require(result.utcoffset()==timedelta(0),'non-UTC timestamp');return result

def validate(report,payloads):
    require(type(report['schemaVersion']) is int and report['schemaVersion']==1 and report['evidenceMode']=='scoped-runtime','observer or wrong schema rejected')
    require('validationResult' not in report,'rejected evidence')
    require(report['paperJarSha256']=='39bd8c00b9e18de91dcabd3cc3dcfa5328685a53b7187a2f63280c22e2d287b9','wrong Paper')
    require(report['mythicMobsSha256']=='9672457cafc56ff841606bfbb710947329c08720ffc207746b60130d43bf902f','wrong native artifact')
    require(all(hash_value(report[k]) for k in ('pluginSha256','probeJarSha256')),'invalid artifact hash')
    require(set(report['sourceHashes'])=={'tools/paper-integration/'+n for n in SOURCES} and all(hash_value(h) for h in report['sourceHashes'].values()),'source identity incomplete')
    boot=report['boot'];require(type(boot['exitCode']) is int and boot['exitCode']==0,'unclean shutdown')
    require(utc(report['generatedAtUtc'])<=utc(boot['startedAtUtc'])<utc(boot['stoppedAtUtc']),'invalid boot window')
    require(re.fullmatch('srv-paper-model-bridge-[0-9]{8}-r[1-9][0-9]*',report['fixtureDirectory']) is not None,'invalid fixture')
    verify_payloads(report,payloads)
    require(doc(payloads['source-mobs.yml'])==c.native_mobs(),'native mobs changed')
    require(doc(payloads['source-skills.yml'])==c.native_skills(),'native skills changed')
    final=c.native_skills();final['mb_scale']['Skills']=['modelscale{scale=4} @trigger'];require(doc(payloads['reloaded-skills.yml'])==final,'missing reload stimulus')
    auto={'model':'example/emberling','scale':1.5,'default-animation':'walk'}
    require(doc(payloads['source-integrations.yml'])=={'mythicmobs':{'mobs':{'MbAuto':auto,'MbCancelled':auto,'MbBadScale':dict(auto,scale='NaN'),'MbBadAnimation':dict(auto,**{'default-animation':'missing'})}}},'automatic binding stimulus changed')
    lines=payloads['server.log'].decode().splitlines();require(not any('MODEL_FAILED|' in l for l in lines),'probe failed')
    raw=[l for l in lines if 'MODEL_ROW|' in l];require(raw==report['observations'],'raw observation mismatch')
    rows={};indexes={};counts=Counter();loads=[];previous_tick=-1
    for index,line in enumerate(lines):
        if 'MODEL_LOAD|' in line:
            load=fields(line,'MODEL_LOAD|');require(set(load)=={'kind','name','line','source','registered'} and load['registered']=='true','native definition not registered');loads.append((index,load))
        if 'MODEL_HOOK|' in line:
            hook=fields(line,'MODEL_HOOK|');require(set(hook)=={'key','thread'} and hook['thread']=='true','wrong native command thread');counts[hook['key']]+=1
        if 'MODEL_ROW|' not in line:continue
        row=fields(line,'MODEL_ROW|');key=row['key'];require(key in c.EXPECTED and key not in rows,'missing/duplicate/unknown row');rows[key]=row;indexes[key]=index
        expected=c.EXPECTED[key];required=set(expected)|{'key','tick'}
        if 'scale' in expected:required|={'uuid','caster'}
        if key=='cancelled_auto':required|={'uuid'}
        require(set(row)==required,'row fields differ: '+key)
        for field,value in expected.items():require(row[field]==value,f'{key}: wrong {field}: {row[field]} != {value}')
        tick=int(row['tick']);require(tick>=previous_tick,'time regressed');previous_tick=tick
        if 'hooks' in row:
            hook_key=key if key=='unsupported_action' else key.split('_',1)[1];require(counts[hook_key]==int(row['hooks']),'command callback mismatch: '+key);counts[hook_key]=0
    require(set(rows)==set(c.EXPECTED) and not any(counts.values()),'incomplete rows or extra native callbacks')
    target=rows['initial']['uuid'];caster=rows['initial']['caster'];uuid.UUID(target);uuid.UUID(caster);require(target!=caster,'caster/target aliased')
    auto_keys={'automatic','automatic_duplicate','unmatched','invalid_auto_scale','invalid_auto_animation'}
    for key,row in rows.items():
        if 'scale' not in row:continue
        require(row['caster']==caster,'caster changed')
        require(row['uuid']==(rows['automatic']['uuid'] if key=='automatic_duplicate' else row['uuid'] if key in auto_keys else target),'wrong target')
        uuid.UUID(row['uuid'])
    identities={caster,target,*[rows[k]['uuid'] for k in auto_keys if k!='automatic_duplicate'],rows['cancelled_auto']['uuid']}
    require(len(identities)==7,'automatic/native identities collided')
    cold=indexes['cold_capabilities'];first_reload=indexes['reenabled_after_cast'];second_reload=indexes['after_old_proxy']
    for begin,end,scale in [(-1,cold,'modelscale{m=2}'),(first_reload,indexes['reloaded_capabilities'],'modelscale{scale=3}'),(second_reload,indexes['live_reload_scale'],'modelscale{scale=4}')]:
        actual=Counter((r['name'].lower(),r['line']) for i,r in loads if begin<i<end and r['kind']=='mechanic')
        expected=Counter((mechanic.split('{')[0],(scale if key=='scale' else mechanic.split(' bogus')[0])) for key,mechanic in c.MECHANICS.items())
        require(actual==expected,'missing/duplicate/wrong native mechanic load')
    require(len([1 for _,r in loads if r['kind']=='mechanic'])==len(c.MECHANICS)*3,'extra mechanic load')
    conditions=Counter(r['source'] for _,r in loads if r['kind']=='condition')
    expected_conditions=Counter({predicate+' '+suffix:6 for predicate in c.PREDICATES.values() for suffix in ('true','false')})
    expected_conditions.update({'modelremoved true':1,'modelremoved false':1,'modelremoved cast mb_spawn':3})
    require(conditions==expected_conditions,'condition registration/reload incomplete')
    require(int(rows['automatic_duplicate']['tick'])>int(rows['automatic']['tick']),'duplicate automatic event not settled')
    require(int(rows['async_mechanic']['tick'])>int(rows['async_condition_false']['tick']),'async mechanic not settled')
    for a,b in [('initial','spawn'),('spawn','repeat_spawn'),('play','stop'),('stop','async_mechanic'),('disabled_capabilities','disabled_after_cast'),('reenabled_capabilities','reenabled_after_cast'),('reloaded_capabilities','reload_scale'),('reload_scale','live_reload_scale'),('removed','cleanup')]:require(indexes[a]<indexes[b],'lifecycle order differs')
    summaries=[(i,fields(l,'MODEL_SUMMARY|')) for i,l in enumerate(lines) if 'MODEL_SUMMARY|' in l]
    require(len(summaries)==1 and summaries[0][1]=={'rows':str(len(rows)),'completed':'true'} and summaries[0][0]>max(indexes.values()),'missing complete summary')
    require(sum('MODEL_NATIVE_EXCEPTION|case=cancelled-spawn|type=NullPointerException' in l for l in lines)==1,'native cancellation behavior not observed')
    stops=[i for i,l in enumerate(lines) if ']: Stopping server' in l];require(len(stops)==1 and stops[0]>summaries[0][0],'premature/missing shutdown')
    return len(rows)

def read_evidence(path):
    root=Path(path);archived=root.is_file();report=json.loads((root if archived else root/'model-bridge-report.json').read_text(),object_pairs_hook=unique);manifest(report)
    if archived:
        name=safe_path(report['logArchive']);require(PurePosixPath(name).name==name and name.endswith('.zip'),'unsafe archive');payloads=archive_payloads(report,(root.parent/name).read_bytes())
    else:
        require(root.name==report['fixtureDirectory'],'fixture directory differs')
        for name,key in [('paper.jar','paperJarSha256'),('plugins/helsteraMobs.jar','pluginSha256'),('plugins/MythicMobs.jar','mythicMobsSha256'),('plugins/HelsteraModelBridgeProbe.jar','probeJarSha256')]:require(digest((root/name).read_bytes())==report[key],'artifact changed: '+name)
        payloads={n:(root/n).read_bytes() for n in manifest(report)};verify_payloads(report,payloads)
    return report,payloads

def main(path):
    report,payloads=read_evidence(path);n=validate(report,payloads);print(f'Validated {n} native model bridge observations; exact identity, gates, reload and cleanup.');return report,payloads
if __name__=='__main__':main(sys.argv[1])
