"""Tamper rejection for Aura event, callback, charge, client and archive evidence."""
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
spec=importlib.util.spec_from_file_location('aura_validator',Path(__file__).with_name('validate-aura-lifecycle.py'))
v=importlib.util.module_from_spec(spec); spec.loader.exec_module(v)


def rejected(label,action):
    try: action()
    except (ValueError,KeyError,IndexError): return
    raise AssertionError('Accepted invalid evidence: '+label)


def rehash(report,payloads):
    report['files']=[{'path':name,'sha256':v.digest(data)} for name,data in payloads.items()]
    report['observations']=[line for line in payloads['server.log'].decode().splitlines() if 'AURALIFE_' in line]


def exercise(report,payloads):
    count=0
    def reject_payload(label,transform):
        nonlocal count
        r,p=copy.deepcopy(report),dict(payloads); transform(p); rehash(r,p)
        rejected(label,lambda:v.validate(r,p)); count+=1
    for original in report['observations']:
        marker=re.search(r'AURALIFE_[A-Z_]+\|',original)[0]
        reject_payload('missing '+marker,lambda p,line=original:p.update({'server.log':p['server.log'].replace((line+'\n').encode(),b'')}))
        reject_payload('duplicate '+marker,lambda p,line=original:p.update({'server.log':p['server.log'].replace(line.encode(),(line+'\n'+line).encode())}))
        for field,value in v.fields(original,marker).items():
            bad='false' if value=='true' else 'true' if value=='false' else '-999' if field=='tick' else '999'
            changed=re.sub(r'(?<=\|)'+re.escape(field)+r'=[^|\n]*',field+'='+bad,original)
            reject_payload(marker+field,lambda p,a=original,b=changed:p.update({'server.log':p['server.log'].replace(a.encode(),b.encode())}))
    reject_payload('skills',lambda p:p.update({'source-skills.yml':b'{}\n'}))
    for original in payloads['client.log'].decode().splitlines():
        if 'AURALIFE_CLIENT ' not in original:continue
        row=json.loads(original.split('AURALIFE_CLIENT ',1)[1]);event=row['event']
        # Dropping one optional progress packet is valid; dropping lifecycle records is not.
        if event!='bar_progress':
            reject_payload('missing client '+event,lambda p,line=original:p.update({'client.log':p['client.log'].replace((line+'\n').encode(),b'')}))
        if event not in ('bar_progress',):
            reject_payload('duplicate client '+event,lambda p,line=original:p.update({'client.log':p['client.log'].replace(line.encode(),(line+'\n'+line).encode())}))
        for key,value in row.items():
            changed=dict(row);changed[key]=False if value is True else True if value is False else 999
            line='AURALIFE_CLIENT '+json.dumps(changed)
            reject_payload('client '+event+'/'+key,lambda p,a=original,b=line:p.update({'client.log':p['client.log'].replace(a.encode(),b.encode())}))
    for key,value in [('evidenceMode','observation'),('schemaVersion',True),('fullAuraParity',True),('fullAuraParity',0),
                      ('deathBoundary','native'),('contract','all-auras'),('exitCode',1),('clientExitCode',1),
                      ('paperJarSha256','0'*64),('mythicMobsSha256','0'*64),('pluginSha256','bad'),('probeJarSha256','bad'),
                      ('sourceHashes',{}),('observations',[]),('validationResult','failed')]:
        r=copy.deepcopy(report);r[key]=value;rejected(key,lambda:v.validate(r,payloads));count+=1
    for case in ('missing','extra','duplicate','unsafe','changed'):
        with io.BytesIO() as buffer:
            with warnings.catch_warnings():
                warnings.simplefilter('ignore',UserWarning)
                with zipfile.ZipFile(buffer,'w') as archive:
                    for name,data in payloads.items():
                        if case=='missing' and name=='server.log':continue
                        archive.writestr(name,data+b'changed' if case=='changed' else data)
                    if case=='extra':archive.writestr('orphan.txt','x')
                    if case=='duplicate':archive.writestr('server.log',payloads['server.log'])
                    if case=='unsafe':archive.writestr('../outside','x')
            data=buffer.getvalue()
        r=copy.deepcopy(report);r['logArchiveSha256']=v.digest(data)
        rejected('archive '+case,lambda:v.archive_payloads(r,data));count+=1
    print('Rejected',count,'Aura lifecycle semantic/archive mutations.')


if __name__=='__main__': exercise(*v.main(sys.argv[1]))
