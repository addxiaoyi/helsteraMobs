"""Rehash mutations to ensure evidence validation tests semantics, not only hashes."""
import copy,importlib.util,json,sys,zipfile,yaml
from pathlib import Path
spec=importlib.util.spec_from_file_location('audit',Path(__file__).with_name('audit-ai-target-selector.py'));a=importlib.util.module_from_spec(spec);spec.loader.exec_module(a)
p=Path(sys.argv[1]);report=json.loads(p.read_text())
with zipfile.ZipFile(p.with_name(report['logArchive'])) as z:files={name:z.read(name) for name in z.namelist()}
a.audit(report,files);count=0
for name,raw in files.items():
    if '/observations/' not in name:continue
    row=yaml.safe_load(raw)
    def leaves(value,path=()):
        if isinstance(value,dict):
            for k,v in value.items():yield from leaves(v,path+(k,))
        elif isinstance(value,list):
            for i,v in enumerate(value):yield from leaves(v,path+(i,))
        else:yield path,value
    for path,value in leaves(row):
        # Wall-clock ticks and asynchronously sampled controller caches are not native parity fields.
        # Explicit controller observation/close fields and all event semantics are checked.
        if path[-1]=='tick':continue
        if path[-1]=='controllerTarget' and path[0] not in ('afterControllerTick','afterClose'):continue
        changed=copy.deepcopy(row);cursor=changed
        for key in path[:-1]:cursor=cursor[key]
        cursor[path[-1]]=not value if type(value) is bool else value+1 if type(value) is int else str(value)+'_MUTATED'
        payload=yaml.safe_dump(changed,sort_keys=False).encode();altered=dict(files);altered[name]=payload;r=copy.deepcopy(report)
        for entry in r['files']:
            if entry['path']==name:entry['sha256']=a.digest(payload)
        try:a.audit(r,altered)
        except (ValueError,KeyError,TypeError):count+=1
        else:raise AssertionError('Accepted mutation '+name+' '+str(path))
print('rejected',count,'rehashed mutations')
