"""Reject semantic tampering even after manifest/observation rehashing."""
import copy
import importlib.util
import io
import json
from pathlib import Path
import re
import sys
import warnings
import zipfile
sys.dont_write_bytecode=True
spec=importlib.util.spec_from_file_location('model_validator',Path(__file__).with_name('validate-model-bridge.py'));v=importlib.util.module_from_spec(spec);spec.loader.exec_module(v)
def rejected(label,action):
    try:action()
    except (ValueError,KeyError):return
    raise AssertionError('Accepted invalid evidence: '+label)
def rehash(report,payloads):
    report['files']=[{'path':n,'sha256':v.digest(data)} for n,data in sorted(payloads.items())]
    report['observations']=[l for l in payloads['server.log'].decode().splitlines() if 'MODEL_ROW|' in l]
def main(path):
    report,payloads=v.main(path);count=0
    def reject_payload(label,transform):
        nonlocal count
        r,p=copy.deepcopy(report),dict(payloads);transform(p);rehash(r,p);rejected(label,lambda:v.validate(r,p));count+=1
    rows=[v.fields(l,'MODEL_ROW|') for l in report['observations']]
    for row in rows:
        key=row['key']
        original=next(l for l in report['observations'] if v.fields(l,'MODEL_ROW|')['key']==key)
        reject_payload('missing '+key,lambda p,line=original:p.update({'server.log':p['server.log'].replace((line+'\n').encode(),b'')}))
        reject_payload('duplicate '+key,lambda p,line=original:p.update({'server.log':p['server.log'].replace(line.encode(),(line+'\n'+line).encode())}))
        for field in v.c.EXPECTED[key]:
            bad='false' if row[field]=='true' else 'true' if row[field]=='false' else '999'
            replacement=re.sub(r'(?<=\|)'+re.escape(field)+r'=[^|\n]*',field+'='+bad,original)
            reject_payload(key+'/'+field,lambda p,a=original,b=replacement:p.update({'server.log':p['server.log'].replace(a.encode(),b.encode())}))
        if 'uuid' in row:
            replacement=original.replace('uuid='+row['uuid'],'uuid='+rows[1].get('caster',row['uuid']))
            if replacement!=original:reject_payload('wrong identity '+key,lambda p,a=original,b=replacement:p.update({'server.log':p['server.log'].replace(a.encode(),b.encode())}))
    for marker in ('MODEL_HOOK|','MODEL_LOAD|','MODEL_SUMMARY|','MODEL_NATIVE_EXCEPTION|'):
        line=next(l for l in payloads['server.log'].decode().splitlines() if marker in l)
        reject_payload('missing '+marker,lambda p,l=line:p.update({'server.log':p['server.log'].replace((l+'\n').encode(),b'')}))
        reject_payload('extra '+marker,lambda p,l=line:p.update({'server.log':p['server.log'].replace(l.encode(),(l+'\n'+l).encode())}))
    for name in v.FILES-{'server.log'}:reject_payload('source '+name,lambda p,n=name:p.update({n:b'{}\n'}))
    for field,value in [('evidenceMode','observation'),('schemaVersion',2),('schemaVersion',True),('paperJarSha256','0'*64),('mythicMobsSha256','0'*64),('pluginSha256','invalid'),('probeJarSha256','invalid'),('sourceHashes',{}),('observations',[]),('validationResult','failed')]:
        r=copy.deepcopy(report);r[field]=value;rejected(field,lambda:v.validate(r,payloads));count+=1
    for case in ('missing','extra','duplicate','unsafe','changed'):
        with io.BytesIO() as buffer:
            with warnings.catch_warnings():
                warnings.simplefilter('ignore',UserWarning)
                with zipfile.ZipFile(buffer,'w') as archive:
                    for name,data in payloads.items():
                        if case=='missing' and name=='server.log':continue
                        archive.writestr(name,data if case!='changed' else data+b'changed')
                    if case=='extra':archive.writestr('orphan.txt','x')
                    if case=='duplicate':archive.writestr('server.log',payloads['server.log'])
                    if case=='unsafe':archive.writestr('../outside','x')
            data=buffer.getvalue()
        r=copy.deepcopy(report);r['logArchiveSha256']=v.digest(data);rejected('archive '+case,lambda:v.archive_payloads(r,data));count+=1
    print(f'Rejected {count} model bridge semantic/archive mutations.')
if __name__=='__main__':main(sys.argv[1])
