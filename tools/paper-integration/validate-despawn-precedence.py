"""Verify modes against a source truth table, actual UUIDs/flags/restart/death and warnings."""
from datetime import datetime
import hashlib
import importlib.util
import itertools
import json
from pathlib import Path
import re
import sys
import zipfile
import yaml
sys.dont_write_bytecode=True
def module(name,file):
    spec=importlib.util.spec_from_file_location(name,Path(__file__).with_name(file));m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m);return m
registry=module('registry','validate-player-variables.py');contract=module('contract','despawn-precedence-cases.py')
require,fields,unique=registry.require,registry.fields,registry.unique_pairs
RETAINED={'NEVER','PERSISTENT'}
def doc(text):return yaml.load(text,Loader=registry.UniqueYaml)
def flag(value):return str(value).lower()
def typed_equal(a,b):return json.dumps(a,sort_keys=True)==json.dumps(b,sort_keys=True)
def lookup(data,path):
    for key in path.split('.'):
        if not isinstance(data,dict) or key not in data:raise ValueError('missing field '+path)
        data=data[key]
    return data

def validate(report,texts):
    require(report['schemaVersion']==1 and report['evidenceMode']=='comparison-with-exception','wrong schema/mode')
    require(report['knownExceptions']==['MM-5.7.2-shutdown-command-scheduling'],'wrong exception set')
    require(report['paperJarSha256']=='39bd8c00b9e18de91dcabd3cc3dcfa5328685a53b7187a2f63280c22e2d287b9','wrong Paper')
    require(report['mythicMobsSha256']=='9672457cafc56ff841606bfbb710947329c08720ffc207746b60130d43bf902f','wrong MM')
    require(all(re.fullmatch('[0-9a-f]{64}',report[k]) for k in ('pluginSha256','probeJarSha256')),'missing artifact hash')
    require(len(report['sourceHashes'])>=7 and all(re.fullmatch('[0-9a-f]{64}',v) for v in report['sourceHashes'].values()),'missing source hashes')
    cases=contract.cases();count=len(cases);groups=contract.GLOBALS
    require([(b['group'],b['stage']) for b in report['boots']]==list(itertools.product(groups,('write','verify'))),'missing/reordered boots')
    previous=None
    for b in report['boots']:
        start,end=(datetime.fromisoformat(b[k]) for k in ('startedAtUtc','stoppedAtUtc'))
        require(b['exitCode']==0 and start<end and (previous is None or previous<start),'unclean/overlapping boots');previous=end
    expected_files={f'{g}/{name}' for g in groups for name in ('write.log','verify.log','defaults.yml','source.yml','identities.yml','migration.yml')}
    expected_files|={f'{g}/imports/{key}.yml' for g in groups for key in cases}
    require(set(texts)==expected_files,'wrong evidence members')
    observations=[];all_ids=set()
    for group,value in groups.items():
        ids=doc(texts[f'{group}/identities.yml']);source=doc(texts[f'{group}/source.yml']);defaults=doc(texts[f'{group}/defaults.yml'])
        require(set(ids)=={'world'}|set(cases) and set(source)==set(cases),'wrong case identities')
        fresh={ids[k][b] for k in cases for b in ('native','runtime')}|{ids['world']}
        require(len(fresh)==count*2+1 and not fresh&all_ids and all(re.fullmatch('[0-9a-f-]{36}',v) for v in fresh),'duplicate/bad UUIDs');all_ids|=fresh
        expected_defaults={} if group=='absent' else {'Despawn':value}
        require(typed_equal(defaults,{'Configuration':{'DefaultMobOptions':expected_defaults}}),'wrong global source')
        migration=doc(texts[f'{group}/migration.yml'])['entries']
        retained={key for key,c in cases.items() if contract.expected_mode(c,group) in RETAINED}
        for key,c in cases.items():
            expected=json.loads(json.dumps(c['source']));expected['Type']='COW';expected.setdefault('Options',{}).update(NoAI=True,NoGravity=True,Invincible=True,Collidable=False)
            expected['Skills']=[f'command{{c="say DPREC_HOOK native {event} {key}"}} @self ~on{event.title()}' for event in ('despawn','death')]
            require(typed_equal(source[key],expected),'changed source case '+key)
            cfg=doc(texts[f'{group}/imports/{key}.yml']);mode=contract.expected_mode(c,group)
            require(lookup(cfg,'mob.despawn')==mode,'wrong imported mode '+key)
            unsupported=list(c['unsupported'])
            if group in ('chunk','quoted_false','zero'):unsupported.append('DefaultMobOptions.Despawn')
            for path in unsupported:
                raw=value if path.startswith('DefaultMobOptions.') else lookup(c['source'],path)
                require(typed_equal(lookup(cfg,'mob.unsupported.'+path),raw),'lost invalid source '+key+'/'+path)
                source_key='config-mobs.yml.Configuration.'+path if path.startswith('DefaultMobOptions.') else path
                require(any(e.get('source-key')==source_key and e.get('status')=='unsupported' and e.get('severity')=='WARN' for e in migration),'missing warning '+source_key)
            for event in ('death','despawn'):
                require(lookup(cfg,'mob.triggers.on-'+event+'.do')==[f'command "say DPREC_HOOK runtime {event} {key}" @self'],'lost callback mapping')
        for stage,phases in [('write',('write',)),('verify',('restored','dead'))]:
            text=texts[f'{group}/{stage}.log'];lines=text.splitlines()
            def marked(marker):return [(i,fields(l,marker)) for i,l in enumerate(lines) if marker in l]
            require('DPREC_FAILED|' not in text and text.count('DPREC_SUMMARY|')==1,'failed/incomplete boot')
            globals_seen=marked('DPREC_GLOBAL|');require(len(globals_seen)==1 and globals_seen[0][1]=={'value':flag(group!='false')},'native global boolean differs')
            summary=marked('DPREC_SUMMARY|')[0];require(summary[1]=={'stage':stage,'rows':str(count*len(phases)),'completed':'true'},'wrong boot count')
            rows=marked('DPREC_ROW|');require([(r['phase'],r['key']) for _,r in rows]==list(itertools.product(phases,cases)),'missing/duplicate/reordered rows')
            observations += [dict(group=group,line=l) for l in lines if 'DPREC_ROW|' in l]
            for i,r in rows:
                key=r['key'];mode=contract.expected_mode(cases[key],group);alive=r['phase']=='write' or r['phase']=='restored' and key in retained
                expected={'phase':r['phase'],'key':key,'native':ids[key]['native'],'runtime':ids[key]['runtime'],
                    'nativeEntity':flag(alive),'runtimeEntity':flag(alive),'nativeRegistry':flag(alive),'runtimeInstance':flag(alive)}
                for backend in ('native','runtime'):
                    expected.update({backend+'Mode':mode if alive else 'absent',backend+'Save':flag(mode in RETAINED) if alive else 'absent',backend+'Far':flag(mode=='NORMAL') if alive else 'absent'})
                require(r==expected,'wrong state '+group+'/'+key+'/'+r['phase']);require(i<summary[0],'row after summary')
            mappings=marked('DPREC_MAPPING|')
            require([r['key'] for _,r in mappings]==(list(cases) if stage=='write' else []),'missing/duplicate mappings')
            for _,r in mappings:require(r['mode']==contract.expected_mode(cases[r['key']],group),'mapping mode differs')
            deaths=marked('DPREC_DEATH|');expected_deaths={(k,b) for k in retained for b in ('native','runtime')} if stage=='verify' else set()
            require(len(deaths)==len(expected_deaths) and {(r['key'],r['backend']) for _,r in deaths}==expected_deaths,'missing/duplicate/unexpected deaths')
            for i,r in deaths:
                key,backend=r['key'],r['backend'];require(r['uuid']==ids[key][backend],'death UUID changed')
                before=next(j for j,v in rows if v['key']==key and v['phase']=='restored');after=next(j for j,v in rows if v['key']==key and v['phase']=='dead')
                require(before<i<after,'death outside lifecycle')
            callbacks=re.findall(r'DPREC_HOOK (native|runtime) death (\w+)',text)
            require(len(callbacks)==len(expected_deaths) and {(k,b) for b,k in callbacks}==expected_deaths,'death callback lost/duplicated')
            stop=[i for i,l in enumerate(lines) if ']: Stopping server' in l];disabled=[i for i,l in enumerate(lines) if '[MythicMobs] Disabling MythicMobs v5.7.2-' in l]
            require(len(stop)==len(disabled)==1 and summary[0]<stop[0]<disabled[0],'missing clean shutdown ordering')
            callbacks=re.findall(r'DPREC_HOOK (native|runtime) despawn (\w+)',text);expected_callbacks={('runtime',k) for k in cases if k not in retained} if stage=='write' else set()
            require(len(callbacks)==len(expected_callbacks) and set(callbacks)==expected_callbacks,'despawn callback lost/duplicated')
            require(all(i>stop[0] for i,l in enumerate(lines) if 'DPREC_HOOK runtime despawn ' in l),'despawn before shutdown')
            exception='org.bukkit.plugin.IllegalPluginAccessException: Plugin attempted to register task while disabled'
            errors=[i for i,l in enumerate(lines) if exception in l]
            require(len(errors)==len(expected_callbacks) and all(i>disabled[0] for i in errors),'native shutdown exception differs')
            for i in errors:
                prefix=re.search(r'\[(lumine-scheduler-\d+)/WARN\]:',lines[i]);require(prefix is not None,'exception from wrong plugin/thread');stack=[]
                for line in lines[i+1:]:
                    if prefix[0] not in line:continue
                    if exception in line or '\tat ' not in line:break
                    stack.append(line)
                for frame in ('org.bukkit.craftbukkit.scheduler.CraftScheduler.validate','MythicMobs.jar//io.lumine.mythic.bukkit.utils.tasks.LumineExecutors$BukkitSyncExecutor.execute','MythicMobs.jar//io.lumine.mythic.core.skills.SkillMechanic.runMechanic'):
                    require(any(frame in l for l in stack),'missing native scheduling stack')
            require('Error occurred while disabling helsteraMobs' not in text,'runtime shutdown failure')
    require(report['observations']==observations,'report/log observations differ')
    return len(observations)

def main(path):
    root=Path(path);archived=root.is_file();report=json.loads((root if archived else root/'despawn-precedence-report.json').read_text(),object_pairs_hook=unique)
    if archived:
        name=report['logArchive'];require(Path(name).name==name and '\\' not in name,'unsafe archive')
        p=root.parent/name;require(hashlib.sha256(p.read_bytes()).hexdigest()==report['logArchiveSha256'],'archive changed')
        with zipfile.ZipFile(p) as z:
            names=z.namelist();require(len(names)==len(set(names)) and set(names)=={f['path'] for f in report['files']},'archive members changed');payloads={n:z.read(n) for n in names}
    texts={}
    for entry in report['files']:
        name=entry['path'];require(not Path(name).is_absolute() and '..' not in Path(name).parts and '\\' not in name and name not in texts,'unsafe/duplicate member')
        data=payloads[name] if archived else (root/name).read_bytes();require(hashlib.sha256(data).hexdigest()==entry['sha256'],'member changed');texts[name]=data.decode('utf-8-sig')
    print(f'Validated {validate(report,texts)} precedence observations, six globals, twelve boots and native/imported flags/deaths.')
    return report,texts
if __name__=='__main__':main(sys.argv[1])
