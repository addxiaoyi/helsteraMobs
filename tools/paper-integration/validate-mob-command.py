"""Verify command selection, permissions, callback context, disk cleanup and restart absence."""
from datetime import datetime
import hashlib,importlib.util,itertools,json,re,sys,zipfile
from pathlib import Path
import yaml
sys.dont_write_bytecode=True
def module(name,file):
 s=importlib.util.spec_from_file_location(name,Path(__file__).with_name(file));m=importlib.util.module_from_spec(s);s.loader.exec_module(m);return m
registry=module('registry','validate-player-variables.py');contract=module('contract','mob-command-cases.py')
require,fields,unique=registry.require,registry.fields,registry.unique_pairs
def flag(v):return str(v).lower()
def doc(text):return yaml.load(text,Loader=registry.UniqueYaml)

def validate(report,texts):
 require(report['schemaVersion']==1 and report['evidenceMode']=='comparison-with-extension','wrong schema/mode')
 require(report['extensions']==['reject-named-kill-radius','reject-zero-killall-radius','reject-console-killall-radius'],'unexplained exception')
 require(report['clientKnownIssues']==['minecraft-protocol-1.21.1-declare-recipes-partial-read'],'wrong client issues')
 require(report['paperJarSha256']=='39bd8c00b9e18de91dcabd3cc3dcfa5328685a53b7187a2f63280c22e2d287b9','wrong Paper')
 require(report['mythicMobsSha256']=='9672457cafc56ff841606bfbb710947329c08720ffc207746b60130d43bf902f','wrong MM')
 require(all(re.fullmatch('[0-9a-f]{64}',report[k]) for k in ('pluginSha256','probeJarSha256')),'bad artifact')
 require(len(report['sourceHashes'])>=8 and all(re.fullmatch('[0-9a-f]{64}',h) for h in report['sourceHashes'].values()),'missing source hashes')
 require([b['stage'] for b in report['boots']]==['write','verify'],'missing/reordered boots');previous=None
 for b in report['boots']:
  start,end=(datetime.fromisoformat(b[k]) for k in ('startedAtUtc','stoppedAtUtc'))
  require(b['exitCode']==0 and start<end and (previous is None or previous<start),'unclean/overlapping boots');previous=end
 require(set(texts)=={'write.server.log','verify.server.log','write.client.log','verify.client.log','source.yml','identities.yml','scoped-after.yml','scoped-final.yml','remaining-tree-files.json'},'wrong evidence files')
 pairs=contract.pairs();cases=contract.CASES;count=len(pairs);source=doc(texts['source.yml']);require(set(source)==set(pairs),'wrong mob catalog')
 for key,(mode,_,zone) in pairs.items():
  require(source[key]=={'Type':'COW','Faction':'cmd' if zone in ('near','far') else 'other','Options':{'Despawn':mode,'NoAI':True,'NoGravity':True,'Invincible':True,'Collidable':False},'Skills':[f'command{{c="mobcommandprobe hook native {e} {key} <caster.var.Marker>"}} @self ~on{e.title()}' for e in ('despawn','death')]},'source drift')
 text=texts['write.server.log'];lines=text.splitlines()
 def marked(marker):return [(i,fields(l,marker)) for i,l in enumerate(lines) if marker in l]
 require('DCMD_FAILED|' not in text and 'DCMD_UNEXPECTED_DEATH|' not in text,'failed/death instead of despawn')
 require(text.count('DCMD_SUMMARY|')==1 and marked('DCMD_SUMMARY|')[0][1]=={'stage':'write','rows':str(count*len(cases)*3),'completed':'true'},'missing summary')
 rows=marked('DCMD_ROW|');require([(r['scenario'],r['phase'],r['key']) for _,r in rows]==list(itertools.product(cases,('before','after','cleaned'),pairs)),'wrong rows')
 identities=marked('DCMD_ID|');require([(r['scenario'],r['key']) for _,r in identities]==list(itertools.product(cases,pairs)),'wrong identities')
 ids={};uuids=[]
 for i,r in identities:
  scenario,key=r['scenario'],r['key'];mode,path,zone=pairs[key]
  require(r['mode']==mode and r['path']==path and r['zone']==zone and int(r['instance'])>0,'identity metadata')
  ids[scenario,key]=r;uuids.extend([r['native'],r['runtime']])
 require(len(set(uuids))==len(uuids) and all(re.fullmatch('[0-9a-f-]{36}',u) for u in uuids),'reused/invalid UUID')
 require(doc(texts['identities.yml'])=={'uuids':uuids},'restart identity set changed')
 for scenario,case in cases.items():
  phases=[(i,r) for i,r in marked('DCMD_PHASE|') if r['scenario']==scenario]
  require([r['phase'] for _,r in phases]==['spawn','command','cleanup'],'phase order')
  command=phases[1];require(command[1]=={'scenario':scenario,'phase':'command','player':flag(case['player']),'suffix':case['command'],'nativeAdmin':flag(scenario!='denied'),'nativePermission':flag(scenario!='denied'),'runtimePermission':flag(scenario!='denied')},'permission/sender/command mismatch')
  dispatch=[(i,r) for i,r in marked('DCMD_DISPATCH|') if r['scenario']==scenario]
  require([r['backend'] for _,r in dispatch]==['native','runtime'] and all(r['result']=='true' and command[0]<i<phases[2][0] for i,r in dispatch),'command not dispatched')
  for phase in ('before','after','cleaned'):
   subset=[(i,r) for i,r in rows if r['scenario']==scenario and r['phase']==phase]
   for i,r in subset:
    key=r['key'];mode,path,zone=pairs[key]
    alive={b:phase=='before' or phase=='after' and not contract.removed(scenario,key,b) for b in ('native','runtime')}
    expected={'scenario':scenario,'phase':phase,'key':key}
    for b in ('native','runtime'):
     expected.update({b+'Entity':flag(alive[b]),b+('Registry' if b=='native' else 'Instance'):flag(alive[b]),b+'Hooks':str(int(not alive[b])),b+'X':str(float({'near':8,'boundary':16,'far':32,'cross':8}[zone])) if alive[b] else 'absent'})
    # Each zone occupies its own runtime force-loaded chunk. It stays forced only
    # while a PERSISTENT instance in that zone survives the command.
    forced=any(m=='PERSISTENT' and z==zone and (phase=='before' or phase=='after' and not contract.removed(scenario,k,'runtime')) for k,(m,_,z) in pairs.items())
    expected.update(variable='73' if alive['runtime'] else 'null',tree=flag(alive['runtime']),uuidFile=flag(alive['runtime']),instanceFile=flag(alive['runtime']),forced=flag(forced))
    require(r==expected,'wrong command outcome '+scenario+'/'+phase+'/'+key)
    low,high=(phases[0][0],command[0]) if phase=='before' else (command[0],phases[2][0]) if phase=='after' else (phases[2][0],marked('DCMD_SUMMARY|')[0][0])
    require(low<i<high,'row outside phase')
   visual=[r for _,r in marked('DCMD_VISUALS|') if r['scenario']==scenario and r['phase']==phase]
   survivors=sum(phase=='before' or phase=='after' and not contract.removed(scenario,k,'runtime') for k in pairs)
   require(visual==[{'scenario':scenario,'phase':phase,'count':str(survivors*11+31)}],'orphan/missing visuals')
   control=[r for _,r in marked('DCMD_CONTROL|') if r['scenario']==scenario and r['phase']==phase]
   require(len(control)==3 and {r['kind'] for r in control}=={'player','unmanaged','model'} and all(r=={'scenario':scenario,'phase':phase,'kind':r['kind'],'instance':'true','rendered':'11' if r['kind']=='model' else '10','apiRejected':'true','base':'true'} for r in control),'cleanup touched non-owned entity/model')
  hooks=[(i,r) for i,r in marked('DCMD_HOOK|') if r['scenario']==scenario]
  require(len(hooks)==count*2 and {(r['backend'],r['key']) for _,r in hooks}==set(itertools.product(('native','runtime'),pairs)),'lost/duplicate callback')
  for i,r in hooks:
   require(r['event']=='despawn' and r['marker']=='73','callback lost live context/used death')
   executed=contract.removed(scenario,r['key'],r['backend'])
   before=command[0] if executed else phases[2][0]
   after=next(j for j,row in rows if row['scenario']==scenario and row['key']==r['key'] and row['phase']==('after' if executed else 'cleaned'))
   require(before<i<after,'callback outside command/cleanup phase')
 require(json.loads(texts['remaining-tree-files.json'])==[],'snapshot files remain')
 for name in ('scoped-after.yml','scoped-final.yml'):
  cfg=doc(texts[name]);require(cfg['registry-format']==1 and not cfg.get('registries'),'entity variables survived removal')
 verify=texts['verify.server.log'];require('DCMD_FAILED|' not in verify and verify.count('DCMD_SUMMARY|')==1,'restart incomplete')
 restart=[fields(l,'DCMD_RESTART|') for l in verify.splitlines() if 'DCMD_RESTART|' in l]
 require(restart==[{'uuid':u,'entity':'false','instance':'false','variables':'0'} for u in uuids],'mob/state resurrected')
 require(next(fields(l,'DCMD_SUMMARY|') for l in verify.splitlines() if 'DCMD_SUMMARY|' in l)=={'stage':'verify','rows':str(len(uuids)),'completed':'true'},'restart count')
 require(not any('DCMD_HOOK|' in l for l in verify.splitlines()),'restart callback replay')
 require(report['observations']==[l for stage in ('write','verify') for l in texts[stage+'.server.log'].splitlines() if 'DCMD_ROW|' in l or 'DCMD_RESTART|' in l],'report/log mismatch')
 client=texts['write.client.log'];events=[json.loads(l.split('DCMD_CLIENT ',1)[1]) for l in client.splitlines() if l.startswith('DCMD_CLIENT ')]
 require(len([e for e in events if e['event']=='join'])==1 and len([e for e in events if e['event']=='end'])==1,'missing real client')
 require([e['event'] for e in events if e['event']!='packet-error']==['join','end'],'unexpected client error/events')
 errors=[e for e in events if e['event']=='packet-error'];require(len(errors)==1 and errors[0]['packet']=='declare_recipes' and errors[0]['state']=='play' and errors[0]['partial'] is True,'unexplained packet error')
 require(texts['verify.client.log']=='','unexpected restart client')
 return len(rows)+len(restart)

def main(path):
 root=Path(path);archive=root.is_file();report=json.loads((root if archive else root/'mob-command-report.json').read_text(),object_pairs_hook=unique)
 if archive:
  name=report['logArchive'];require(Path(name).name==name and '\\' not in name,'unsafe archive');p=root.parent/name
  require(hashlib.sha256(p.read_bytes()).hexdigest()==report['logArchiveSha256'],'archive changed')
  with zipfile.ZipFile(p) as z:
   names=z.namelist();require(len(names)==len(set(names)) and set(names)=={f['path'] for f in report['files']},'wrong archive members');payloads={n:z.read(n) for n in names}
 texts={}
 for entry in report['files']:
  name=entry['path'];require(not Path(name).is_absolute() and '..' not in Path(name).parts and '\\' not in name and name not in texts,'unsafe/duplicate member')
  data=payloads[name] if archive else (root/name).read_bytes();require(hashlib.sha256(data).hexdigest()==entry['sha256'],'member changed');texts[name]=data.decode('utf-8-sig')
 print(f'Validated {validate(report,texts)} command/restart observations, permissions, callbacks and snapshot cleanup.')
 return report,texts
if __name__=='__main__':main(sys.argv[1])
