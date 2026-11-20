"""Evidence mutations for nested selection, replay, callback context and restart state."""
import copy,importlib.util,sys
from pathlib import Path
sys.dont_write_bytecode=True
s=importlib.util.spec_from_file_location('v',Path(__file__).with_name('validate-reentrant-command.py'));v=importlib.util.module_from_spec(s);s.loader.exec_module(v)
report,texts=v.main(sys.argv[1]);controls=[]
def log(label,file,change):
 t=dict(texts);t[file]=change(t[file]);assert t[file]!=texts[file],label;controls.append((label,report,t))
def row(label,stage,scenario,phase,role,change):
 r=copy.deepcopy(report);t=dict(texts);i=next(i for i,l in enumerate(r['observations']) if all(f'{k}={a}|' in l for k,a in [('stage',stage),('scenario',scenario),('phase',phase),('role',role)]))
 old=r['observations'][i];new=change(old);assert old!=new,label;r['observations'][i]=new;t[stage+'.server.log']=t[stage+'.server.log'].replace(old,new);controls.append((label,r,t))
for scenario,role in [('loaded_named_self_direct','lead'),('loaded_named_peer_imported','peer'),('loaded_force_spawn_direct','child'),('loaded_named_force_spawn_imported','child'),('unloading_force_spawn_direct','peer'),('unloading_named_spawn_direct','lead'),('unloading_named_spawn_force_imported','child'),('unloading_named_radius_direct','protected')]:
 row('wrong selection '+scenario,'write',scenario,'reloaded',role,lambda l:l.replace('Entity=true','Entity=WRONG').replace('Entity=false','Entity=WRONG'))
row('restored removed UUID','verify','loaded_named_self_direct','restart','lead',lambda l:l.replace('Entity=false','Entity=true'))
row('final resurrection','final','loaded_named_spawn_direct','final','child',lambda l:l.replace('Entity=false','Entity=true'))
row('lost tree','verify','loaded_force_spawn_direct','restart','child',lambda l:l.replace('tree=91','tree=absent'))
row('lost variable','verify','loaded_force_spawn_direct','restart','child',lambda l:l.replace('runtimeVariable=73','runtimeVariable=absent'))
row('lost snapshot','verify','loaded_force_spawn_direct','restart','child',lambda l:l.replace('file=true','file=false'))
row('replayed callback','write','loaded_named_self_direct','reloaded','lead',lambda l:l.replace('Hooks=1','Hooks=2'))
row('wrong row identity','write','loaded_force_spawn_direct','reloaded','child',lambda l:l.replace('runtimeId=','runtimeId=WRONG'))
row('no real unload','write','unloading_named_self_direct','ready','lead',lambda l:l.replace('Entity=false','Entity=true'))
row('removed caster reattached','write','unloading_named_load_self_direct','command-after','lead',lambda l:l.replace('runtimeRegistry=false','runtimeRegistry=true'))
row('pending child state lost','write','unloading_force_spawn_direct','settled','child',lambda l:l.replace('runtimeVariable=73','runtimeVariable=absent'))
row('pending child snapshot lost','write','unloading_force_spawn_direct','settled','child',lambda l:l.replace('file=true','file=false'))
log('callback load missing','write.server.log',lambda t:t.replace('RCMD_CALLBACK_LOAD|','LOST_CALLBACK_LOAD|',1))
log('callback load failed','write.server.log',lambda t:t.replace('|loaded=true','|loaded=false',1))
log('missing callback','write.server.log',lambda t:t.replace('RCMD_HOOK|','LOST_HOOK|',1))
log('callback duplicate','write.server.log',lambda t:t+'\n'+next(l for l in t.splitlines() if 'RCMD_HOOK|' in l))
log('callback UUID changed','write.server.log',lambda t:t.replace('|event=despawn|uuid=','|event=despawn|uuid=wrong',1))
log('callback marker lost','write.server.log',lambda t:t.replace('|marker=73|','|marker=undefined|',1))
log('callback death','write.server.log',lambda t:t.replace('|event=despawn|','|event=death|',1))
log('nested dispatch missing','write.server.log',lambda t:t.replace('|suffix=kill -e rc_peer|','|suffix=kill -e rc_missing|',1))
log('command failed','write.server.log',lambda t:t.replace('|result=true','|result=false',1))
log('wrong depth','write.server.log',lambda t:t.replace('|depth=2|','|depth=3|',1))
log('child spawn missing','write.server.log',lambda t:t.replace('|role=child|uuid=','|role=child_missing|uuid=',1))
log('chunk isolation lost','write.server.log',lambda t:t.replace('|x=512|','|x=256|',1))
log('unload failed','write.server.log',lambda t:t.replace('|accepted=true|tick=','|accepted=false|tick=',1))
log('world save failed','verify.server.log',lambda t:t.replace('|accepted=true','|accepted=false',1))
log('orphan visuals','final.server.log',lambda t:t.replace('|count=0','|count=11',1))
log('source changed','source.yml',lambda t:t.replace('Despawn: PERSISTENT','Despawn: NEVER',1))
log('import changed','imports/imported-rc_lead.yml',lambda t:t.replace('despawn: NEVER','despawn: NORMAL',1))
log('orphan tree file','remaining-tree-files.json',lambda _:'["entity-orphan.yml"]')
log('saved variables changed','scoped-write.yml',lambda t:t.replace('value: 73','value: 74',1))
log('client missing','write.client.log',lambda t:'\n'.join(l for l in t.splitlines() if '"event":"join"' not in l))
log('unknown packet exception','write.client.log',lambda t:t.replace('declare_recipes','position'))
for key,value in [('paperJarSha256','0'*64),('mythicMobsSha256','0'*64),('knownExceptions',[]),('sourceHashes',{}),('boots',report['boots'][:-1]),('observations',[])]:
 r=copy.deepcopy(report);r[key]=value;controls.append((key,r,texts))
for label,r,t in controls:
 try:v.validate(r,t)
 except (ValueError,KeyError):continue
 raise AssertionError('Accepted mutation: '+label)
print(f'Rejected all {len(controls)} reentrant command evidence mutations.')
