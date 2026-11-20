"""Mutations must reject mutually wrong command outcomes and missing persistence evidence."""
import copy, importlib.util, sys
from pathlib import Path
sys.dont_write_bytecode=True
s=importlib.util.spec_from_file_location('v',Path(__file__).with_name('validate-unloaded-command.py'))
v=importlib.util.module_from_spec(s);s.loader.exec_module(v)
report,texts=v.main(sys.argv[1]);controls=[]
def log(label,file,change):
    logs=dict(texts);logs[file]=change(logs[file]);assert logs[file]!=texts[file],label;controls.append((label,report,logs))
def row(label,stage,scenario,phase,key,change):
    r=copy.deepcopy(report);logs=dict(texts)
    i=next(i for i,l in enumerate(r['observations']) if all(f'{k}={val}|' in l for k,val in [('stage',stage),('scenario',scenario),('phase',phase),('key',key)]))
    before=r['observations'][i];after=change(before);assert after!=before,label;r['observations'][i]=after
    logs[stage+'.server.log']=logs[stage+'.server.log'].replace(before,after);controls.append((label,r,logs))
for scenario,mode in [('transition_named','never'),('transition_bulk','persistent'),('edge4_force','never'),('edge5_named','persistent'),('edge6_named','never'),('stable_radius','persistent'),('outside_radius','never'),('wrong_world','persistent')]:
    row('wrong removal '+scenario,'write',scenario,'reloaded','uc_'+mode+'_direct',lambda l:l.replace('Entity=true','Entity=WRONG').replace('Entity=false','Entity=WRONG'))
for name,change in [('variables',lambda l:l.replace('runtimeVariable=absent','runtimeVariable=73')),('tree file',lambda l:l.replace('file=false','file=true')),('tree context',lambda l:l.replace('tree=absent','tree=91')),('callback count',lambda l:l.replace('Hooks=1','Hooks=0'))]:
    row(name,'write','transition_force','reloaded','uc_never_imported',change)
row('early registry expiry','write','baseline','tick5','uc_never_direct',lambda l:l.replace('nativeRegistry=true','nativeRegistry=false'))
row('late registry expiry','write','baseline','tick6','uc_never_direct',lambda l:l.replace('nativeRegistry=false','nativeRegistry=true'))
row('radius failed to load','write','transition_radius','command-after','uc_never_direct',lambda l:l.replace('Loaded=true','Loaded=false'))
row('removed UUID resurrects','verify','transition_named','restart','uc_never_direct',lambda l:l.replace('Entity=false','Entity=true'))
row('cleanup resurrection','final','baseline','final','uc_never_direct',lambda l:l.replace('Entity=false','Entity=true'))
row('survivor tree lost','verify','baseline','restart','uc_never_direct',lambda l:l.replace('tree=91','tree=absent'))
log('shared chunk','write.server.log',lambda s:s.replace('|x=512|','|x=256|',1))
log('unload rejected','write.server.log',lambda s:s.replace('|accepted=true|wasForced=','|accepted=false|wasForced=',1))
log('missing unload event','write.server.log',lambda s:s.replace('UCMD_ENTITIES_EVENT|','MISSING_EVENT|',1))
log('incorrect delay','write.server.log',lambda s:s.replace('UCMD_COMMAND|scenario=transition_named|','UCMD_COMMAND|scenario=NOT_transition_named|',1))
log('callback context lost','write.server.log',lambda s:s.replace('|marker=73|','|marker=undefined|',1))
log('death callback','write.server.log',lambda s:s.replace('|event=despawn|','|event=death|',1))
log('callback replay','write.server.log',lambda s:s+'\n'+next(l for l in s.splitlines() if 'UCMD_HOOK|' in l))
log('orphan visuals','final.server.log',lambda s:s.replace('|count=0','|count=11',1))
log('failed world save','verify.server.log',lambda s:s.replace('|accepted=true|absent=true','|accepted=false|absent=false',1))
log('source mode drift','source.yml',lambda s:s.replace('Despawn: NPC','Despawn: NEVER',1))
log('import mode drift','imports/uc_never_imported.yml',lambda s:s.replace('despawn: NEVER','despawn: NORMAL',1))
log('tree snapshot corrupted','snapshots/write-baseline-unloaded-uc_never_direct.yml',lambda s:s.replace('value: 91','value: 90',1))
log('remaining tree file','remaining-tree-files.json',lambda _:'["entity-orphan.yml"]')
log('saved entity variable lost','scoped-write.yml',lambda s:s.replace('value: 73','value: 74',1))
log('identity drift','identities.yml',lambda s:s.replace('instance: 1','instance: 999',1))
log('missing client','write.client.log',lambda s:'\n'.join(l for l in s.splitlines() if '"event":"join"' not in l))
log('unexpected client error','write.client.log',lambda s:s+'\nDCMD_CLIENT {"event":"error"}')
log('wrong packet exception','write.client.log',lambda s:s.replace('declare_recipes','position'))
for field,value in [('paperJarSha256','0'*64),('mythicMobsSha256','0'*64),('boots',report['boots'][:-1]),('knownExceptions',[]),('sourceHashes',{}),('observations',[])]:
    r=copy.deepcopy(report);r[field]=value;controls.append((field,r,texts))
for label,r,logs in controls:
    try:v.validate(r,logs)
    except (ValueError,KeyError):continue
    raise AssertionError('Mutation accepted: '+label)
print(f'Rejected all {len(controls)} unloaded command evidence mutations.')
