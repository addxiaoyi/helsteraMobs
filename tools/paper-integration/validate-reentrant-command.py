"""Pin nested command outcomes, callback UUID/context, reload and final restart absence."""
from datetime import datetime
import hashlib,importlib.util,itertools,json,re,sys,zipfile
from pathlib import Path
import yaml
sys.dont_write_bytecode=True
def module(name,file):
 s=importlib.util.spec_from_file_location(name,Path(__file__).with_name(file));m=importlib.util.module_from_spec(s);s.loader.exec_module(m);return m
registry=module('registry','validate-player-variables.py');contract=module('contract','reentrant-command-cases.py')
require,fields,unique=registry.require,registry.fields,registry.unique_pairs
EXCEPTIONS=['native-deferred-command-callback']
PHASES={'write':('before','ready','command-after','settled','reloaded'),'verify':('restart','cleaned'),'final':('final',)}
def flag(v):return str(bool(v)).lower()
def doc(text):return yaml.load(text,Loader=registry.UniqueYaml)
def spawned(case,role):return role!='child' or 'spawn' in case['action'].split('-')
def survives(case,role):
 if role=='lead':return False
 actions=case['action'].split('-')
 if role=='child':return 'spawn' in actions and not any(a in ('force','radius') for a in actions[actions.index('spawn')+1:])
 return not case['command'].startswith('killall') and not any(a in ('force','radius') or a=='peer' and role=='peer' for a in actions)
def nested(case):return [dict(self='kill -e rc_lead',peer='kill -e rc_peer',force='killall -p',radius='killall -p 10000')[a] for a in case['action'].split('-') if a not in ('spawn','load')]

def validate(report,texts):
 require(report['schemaVersion']==1 and report['evidenceMode']=='comparison-with-exception','observer/wrong schema')
 require(report['knownExceptions']==EXCEPTIONS,'undeclared timing difference')
 require(report['clientKnownIssues']==['minecraft-protocol-1.21.1-declare-recipes-partial-read'],'client exception')
 require(report['paperJarSha256']=='39bd8c00b9e18de91dcabd3cc3dcfa5328685a53b7187a2f63280c22e2d287b9','wrong Paper')
 require(report['mythicMobsSha256']=='9672457cafc56ff841606bfbb710947329c08720ffc207746b60130d43bf902f','wrong MM')
 require(all(re.fullmatch('[0-9a-f]{64}',report[k]) for k in ('pluginSha256','probeJarSha256')),'artifact hash')
 sources={'run-reentrant-command-fixture.py','reentrant-command-cases.py','random-spawn-fixture/src/main/java/dev/helstera/test/randomspawn/ReentrantCommandProbe.java','run-player-variable-fixture.py','mob-command-client.js','validate-player-variables.py','validate-reentrant-command.py','test-reentrant-command-evidence.py'}
 require(set(report['sourceHashes'])=={'tools/paper-integration/'+s for s in sources} and all(re.fullmatch('[0-9a-f]{64}',h) for h in report['sourceHashes'].values()),'source hashes')
 require([b['stage'] for b in report['boots']]==list(PHASES),'missing/reordered boot');prev=None
 for b in report['boots']:
  start,end=(datetime.fromisoformat(b[k]) for k in ('startedAtUtc','stoppedAtUtc'))
  require(b['exitCode']==0 and start<end and (prev is None or prev<start),'unclean/overlapping boots');prev=end
 cases=contract.CASES;roles=contract.ROLES
 expected_files={'identities.yml','source.yml','scoped-write.yml','scoped-verify.yml','scoped-final.yml','scoped-shutdown.yml','remaining-tree-files.json'}|{stage+kind for stage in PHASES for kind in ('.server.log','.client.log')}|{'imports/'+path+'-rc_'+role+'.yml' for path in ('direct','imported') for role in roles}
 require(set(texts)==expected_files,'evidence members')
 source=doc(texts['source.yml']);require(set(source)=={'rc_'+r for r in roles},'native catalog')
 for role in roles:
  key='rc_'+role;mode='PERSISTENT' if role=='protected' else 'NEVER'
  require(source[key]=={'Type':'COW','Options':{'Despawn':mode,'NoAI':True,'NoGravity':True,'Invincible':True,'Collidable':False},'Skills':[f'command{{c="reentrantcommandprobe hook native {e} {key} <caster.uuid> <caster.var.Marker>"}} @self ~on{e.title()}' for e in ('despawn','death')]},'native callback definition')
  for path in ('direct','imported'):
   cfg=doc(texts['imports/'+path+'-'+key+'.yml']);require(cfg['mob']['despawn']==mode and cfg['model']=='example/emberling','mode/model mapping')
   require(cfg['mob' if path=='imported' else 'ai']['triggers']=={'on-'+e:{'do':[f'command "reentrantcommandprobe hook runtime {e} {key} <caster.uuid> <caster.var.Marker>" @self']} for e in ('despawn','death')},'callback mapping')
 ids=doc(texts['identities.yml']);require(list(ids)==list(cases),'identity scenarios');uuids=[];observations=[];total=0
 for scenario,case in cases.items():
  wanted={b+'_'+r for b in ('native','runtime') for r in roles if spawned(case,r)}
  require(set(ids[scenario]['mobs'])==wanted,'spawn coverage')
  for key,identity in ids[scenario]['mobs'].items():
   require(re.fullmatch('[0-9a-f-]{36}',identity['uuid']) and (identity['instance']>0 if key.startswith('runtime_') else identity['instance']==0),'UUID/instance identity');uuids.append(identity['uuid'])
 require(len(uuids)==len(set(uuids)),'UUID reused')
 for stage,phases in PHASES.items():
  text=texts[stage+'.server.log'];lines=text.splitlines()
  markers={}
  def marked(marker):
   if marker not in markers:markers[marker]=[(i,fields(l,marker)) for i,l in enumerate(lines) if marker in l]
   return markers[marker]
  require(all(m not in text for m in ('RCMD_FAILED|','RCMD_REPLAY|','RCMD_DEATH|')),'failure/replay/death')
  rows=marked('RCMD_ROW|');total+=len(rows);observations += [l for l in lines if 'RCMD_ROW|' in l]
  require([(r['scenario'],r['phase'],r['role']) for _,r in rows]==list(itertools.product(cases,phases,roles)),'row coverage/order')
  require([r for _,r in marked('RCMD_SUMMARY|')]==[{'stage':stage,'rows':str(len(cases)*len(phases)*len(roles)),'completed':'true'}],'summary')
  require([r for _,r in marked('RCMD_SEAL|')]==[{'stage':stage,'scenario':s,'accepted':'true'} for s in cases],'world save')
  for scenario,case in cases.items():
   subset=[(i,r) for i,r in rows if r['scenario']==scenario];hooks=[(i,r) for i,r in marked('RCMD_HOOK|') if r['scenario']==scenario]
   want_hooks={(b,role) for b in ('native','runtime') for role in roles if spawned(case,role) and (not survives(case,role) if stage=='write' else survives(case,role) if stage=='verify' else False)}
   require(len(hooks)==len(want_hooks) and {(h['backend'],h['role']) for _,h in hooks}==want_hooks,'lost/replayed callback '+stage+'/'+scenario)
   for i,h in hooks:
    identity=ids[scenario]['mobs'][h['backend']+'_'+h['role']]
    require(h['stage']==stage and h['uuid']==identity['uuid'] and h['marker']=='73' and h['event']=='despawn' and h['count']=='1' and h['primary']=='true','callback context/identity')
    require(h['phase'] in ('command','command-after') if stage=='write' else h['phase']=='cleanup','callback outside command/cleanup')
    require(int(h['depth'])>=1 if h['backend']=='runtime' else h['depth']=='0','callback timing contract')
   commands=[(i,r) for i,r in marked('RCMD_COMMAND|') if r['scenario']==scenario]
   returns=[(i,r) for i,r in marked('RCMD_RETURN|') if r['scenario']==scenario]
   for b in ('native','runtime'):
    require([r['suffix'] for _,r in commands if r['backend']==b]==([case['command']]+nested(case) if stage=='write' else ['killall -p'] if stage=='verify' else []),'nested command sequence')
   stack=[]
   for i,r in sorted(commands+returns):
    if 'suffix' in r:
     stack.append(r['backend']);require(int(r['depth'])==len(stack),'command recursion depth')
    else:require(stack and stack.pop()==r['backend'] and int(r['depth'])==len(stack)+1 and r['result']=='true','command returned/error')
   require(not stack,'unterminated command')
   if stage=='write':
    spawned_rows=[(i,r) for i,r in marked('RCMD_ID|') if r['scenario']==scenario]
    require(len(spawned_rows)==len(ids[scenario]['mobs']) and {r['backend']+'_'+r['role'] for _,r in spawned_rows}==set(ids[scenario]['mobs']),'spawn event coverage')
    for i,r in spawned_rows:
     identity=ids[scenario]['mobs'][r['backend']+'_'+r['role']]
     require(r['uuid']==identity['uuid'] and int(r['instance'])==identity['instance'] and r['world']==ids[scenario]['world'],'spawn identity drift')
     if r['role']=='child':require(any(j<i and h['backend']==r['backend'] and h['role']=='lead' for j,h in hooks),'child outside real callback')
     else:require(r['phase']=='prepare','initial spawn phase')
    requests=[r for _,r in marked('RCMD_REQUEST|') if r['scenario']==scenario]
    unloads=[(i,r) for i,r in marked('RCMD_UNLOAD|') if r['scenario']==scenario]
    if case['state']=='unloading':
     require(len(requests)==2 and {r['backend'] for r in requests}=={'native','runtime'} and all(r['accepted']=='true' and int(r['x'])==(256 if r['backend']=='native' else 512) for r in requests),'independent unload requests')
     require(len(unloads)>=2 and {r['backend'] for _,r in unloads[:2]}=={'native','runtime'} and all(r['count']=='3' for _,r in unloads[:2]),'real unload membership')
     for b in ('native','runtime'):
      command=next(r for _,r in commands if r['backend']==b);event=next(r for _,r in unloads if r['backend']==b)
      require(int(command['tick'])-int(event['tick'])==1,'transition boundary missed')
    else:require(not requests and not unloads,'unexpected unload')
   callback_loads=[r for _,r in marked('RCMD_CALLBACK_LOAD|') if r['scenario']==scenario]
   require([r['backend'] for r in callback_loads]==(['runtime','native'] if stage=='write' and 'load' in case['action'].split('-') else []),'callback chunk load coverage')
   require(all(r['loaded']=='true' for r in callback_loads),'callback chunk load failed')
   for i,r in subset:
    role,phase=r['role'],r['phase'];require(r['stage']==stage,'row stage')
    for b in ('native','runtime'):
     spawned_at=[j for j,s in marked('RCMD_ID|') if s['scenario']==scenario and s['role']==role and s['backend']==b]
     exists=spawned(case,role) and (stage!='write' or any(j<i for j in spawned_at))
     require(r[b+'Id']==(ids[scenario]['mobs'][b+'_'+role]['uuid'] if exists else 'absent'),'row identity/time')
     require(r[b+'Hooks']==str(sum(j<i and h['role']==role and h['backend']==b for j,h in hooks)),'hook counter/log mismatch')
    if phase in ('reloaded','restart','cleaned','final'):
     alive=survives(case,role) and phase in ('reloaded','restart')
     for b in ('native','runtime'):
      require(r[b+'Entity']==r[b+'Registry']==flag(alive) and r[b+'Variable']==('73' if alive else 'absent'),f'wrong {scenario}/{phase}/{role}/{b}')
     require(r['tree']==('91' if alive else 'absent'),'tree lost/orphan')
     if not alive or phase=='restart':require(r['file']==flag(alive),'snapshot lost/orphan')
     require(r['nativeLoaded']==r['runtimeLoaded']=='true','reload not executed')
    elif phase in ('before','ready'):
     alive=role!='child';loaded=phase=='before' or case['state']=='loaded'
     require(r['nativeEntity']==r['runtimeEntity']==flag(alive and loaded),'initial/unloaded entity')
     require(r['nativeRegistry']==flag(alive) and r['runtimeRegistry']==flag(alive and loaded),'initial registry')
     require(r['nativeVariable']==r['runtimeVariable']==('73' if alive else 'absent') and r['tree']==('91' if alive and loaded else 'absent'),'initial context')
     require(r['nativeLoaded']==r['runtimeLoaded']==flag(loaded),'unload precondition')
    if phase=='command-after' and not survives(case,role):
     require(r['runtimeRegistry']=='false','removed caster/child was reactivated during nested callback')
    if phase=='settled':
     for b in ('native','runtime'):
      alive=survives(case,role);loaded=r[b+'Loaded']=='true'
      require(r[b+'Entity']==r[b+'Registry']==flag(alive and loaded),'settled entity/registry')
      require(r[b+'Variable']==('73' if alive and (loaded or b=='runtime') else 'absent'),'settled variable')
     alive=survives(case,role);loaded=r['runtimeLoaded']=='true'
     require(r['tree']==('91' if alive and loaded else 'absent'),'settled tree lost/orphan')
     if alive and not loaded:require(r['file']=='true','pending spawn lost saved tree')
   visuals=[r for _,r in marked('RCMD_VISUALS|') if r['scenario']==scenario];require([r['phase'] for r in visuals]==list(phases),'visual coverage')
   for v in visuals:
    if v['phase']=='command-after':continue
    live=sum(r['runtimeRegistry']=='true' for _,r in subset if r['phase']==v['phase'])
    require(int(v['count'])==live*11,'missing/orphan visuals')
 require(report['observations']==observations,'report/log mismatch')
 for name in ('scoped-write.yml','scoped-verify.yml','scoped-final.yml','scoped-shutdown.yml'):
  cfg=doc(texts[name]);regs=cfg.get('registries') or {};require(cfg['registry-format']==1,'registry schema')
  scopes=[r['scope'] for r in regs.values()];wanted={'entity:'+ids[s]['mobs']['runtime_'+role]['uuid'] for s,c in cases.items() for role in roles if survives(c,role)} if name=='scoped-write.yml' else set()
  require(len(scopes)==len(set(scopes)) and set(scopes)==wanted,'saved registry coverage')
  for r in regs.values():require(r['snapshot']['variables']['entries']=={'kTWFya2Vy':{'name':'Marker','value':{'type':'INTEGER','value':73}}},'persisted variable value')
 require(json.loads(texts['remaining-tree-files.json'])==[],'snapshot orphan after final restart')
 events=[json.loads(l.split('DCMD_CLIENT ',1)[1]) for l in texts['write.client.log'].splitlines() if l.startswith('DCMD_CLIENT ')]
 require([e['event'] for e in events if e['event']!='packet-error']==['join','end'],'real client failed/missing')
 errors=[e for e in events if e['event']=='packet-error'];require(len(errors)==1 and errors[0]['packet']=='declare_recipes' and errors[0]['state']=='play' and errors[0]['partial'] is True,'unexplained client error')
 require(texts['verify.client.log']==texts['final.client.log']=='','unexpected restart client')
 return total

def main(path):
 root=Path(path);archived=root.is_file();report=json.loads((root if archived else root/'reentrant-command-report.json').read_text(),object_pairs_hook=unique)
 if archived:
  name=report['logArchive'];require(Path(name).name==name and '\\' not in name,'unsafe archive');archive=root.parent/name
  require(hashlib.sha256(archive.read_bytes()).hexdigest()==report['logArchiveSha256'],'archive changed')
  with zipfile.ZipFile(archive) as z:
   names=z.namelist();require(len(names)==len(set(names)) and set(names)=={f['path'] for f in report['files']},'archive members');payloads={n:z.read(n) for n in names}
 texts={}
 for f in report['files']:
  name=f['path'];require(not Path(name).is_absolute() and '..' not in Path(name).parts and '\\' not in name and name not in texts,'unsafe/duplicate member')
  data=payloads[name] if archived else (root/name).read_bytes();require(hashlib.sha256(data).hexdigest()==f['sha256'],'member changed');texts[name]=data.decode('utf-8-sig')
 print(f'Validated {validate(report,texts)} reentrant command observations across three boots.');return report,texts
if __name__=='__main__':main(sys.argv[1])
