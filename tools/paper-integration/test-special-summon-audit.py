"""Rehash each altered observation so only independent semantic checks reject it."""
import copy
from functools import lru_cache
import importlib.util
import json
from pathlib import Path
import sys
import zipfile
import yaml
spec=importlib.util.spec_from_file_location('special_audit',Path(__file__).with_name('audit-special-summon.py'))
a=importlib.util.module_from_spec(spec);spec.loader.exec_module(a)
# Only the changed YAML document needs parsing on each audit. The auditor
# treats parsed documents as immutable and normalizes deep copies.
yaml.safe_load=lru_cache(maxsize=256)(yaml.safe_load)
def leaves(value,path=()):
    if isinstance(value,dict):
        for k,v in value.items():yield from leaves(v,path+(k,))
    elif isinstance(value,list):
        for i,v in enumerate(value):yield from leaves(v,path+(i,))
    else:yield path,value
def main():
    report_path=Path(sys.argv[1]);report=json.loads(report_path.read_text())
    with zipfile.ZipFile(report_path.with_name(report['logArchive'])) as z:files={n:z.read(n) for n in z.namelist()}
    a.audit(report,files);rejected=0
    def reject(metadata,payload,label):
        nonlocal rejected
        try:a.audit(metadata,payload)
        except (ValueError,KeyError,TypeError,IndexError):rejected+=1
        else:raise AssertionError('Mutation escaped: '+label)
    def replace(name,data,label):
        payload=dict(files);payload[name]=data;metadata=copy.deepcopy(report)
        for entry in metadata['files']:
            if entry['path']==name:entry['sha256']=a.digest(data)
        reject(metadata,payload,label)
    for name,data in files.items():
        if '/observations/' not in name:continue
        row=yaml.safe_load(data)
        for path,value in leaves(row):
            altered=copy.deepcopy(row);cursor=altered
            for k in path[:-1]:cursor=cursor[k]
            cursor[path[-1]]=not value if isinstance(value,bool) else value+1 if isinstance(value,(int,float)) else value+'__changed' if isinstance(value,str) else 'changed'
            replace(name,yaml.safe_dump(altered,sort_keys=False).encode(),name+' '+str(path))
    for key,value in dict(schemaVersion=2,evidenceMode='parity',parityStatus='passed',exitCode=1,clientExitCode=1,
                          observationCount=1,paperJarSha256='0'*64,mythicMobsSha256='0'*64,sourceHashes={}).items():
        metadata=copy.deepcopy(report);metadata[key]=value;reject(metadata,files,'report '+key)
    for name in (a.PROBE+'observations/000.yml',a.PROBE+'particles.yml','client.log'):
        payload=dict(files);payload.pop(name);metadata=copy.deepcopy(report)
        metadata['files']=[e for e in metadata['files'] if e['path']!=name]
        reject(metadata,payload,'missing '+name)
    metadata=copy.deepcopy(report);metadata['files'].append(metadata['files'][0]);reject(metadata,files,'duplicate inventory')
    payload=dict(files);payload['unexpected.txt']=b'unexpected';metadata=copy.deepcopy(report)
    metadata['files'].append(dict(path='unexpected.txt',sha256=a.digest(payload['unexpected.txt'])))
    reject(metadata,payload,'unexpected archive evidence')
    replace('server.log',files['server.log'].replace(b'SPECIAL_SUMMON_SUMMARY|',b'INCOMPLETE|'),'completion marker')
    replace('client.log',files['client.log'].replace(b'"passed":true',b'"passed":false'),'observer failure')
    name=a.PROBE+'observations/000.yml';row=copy.deepcopy(yaml.safe_load(files[name]));row['extra']='unobserved'
    replace(name,yaml.safe_dump(row).encode(),'extra row field')
    name=a.PROBE+'particles.yml';particles=copy.deepcopy(yaml.safe_load(files[name]));particles['BLOCK_DUST']='DUST'
    replace(name,yaml.safe_dump(particles).encode(),'native particle conversion')
    print('Rejected rehashed semantic mutations:',rejected)
if __name__=='__main__':main()
