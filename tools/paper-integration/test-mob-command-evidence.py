"""Reject mutually wrong selection, permission bypass, replay and orphan evidence."""
import copy,importlib.util,sys
from pathlib import Path
sys.dont_write_bytecode=True
s=importlib.util.spec_from_file_location('v',Path(__file__).with_name('validate-mob-command.py'));v=importlib.util.module_from_spec(s);s.loader.exec_module(v)
report,texts=v.main(sys.argv[1]);controls=[]
def log(label,file,change):
 logs=dict(texts);logs[file]=change(logs[file]);assert logs[file]!=texts[file],label;controls.append((label,report,logs))
def row(label,predicate,change):
 r=copy.deepcopy(report);logs=dict(texts);i=next(i for i,l in enumerate(r['observations']) if predicate(l));before=r['observations'][i];after=change(before);assert before!=after,label;r['observations'][i]=after
 for name in logs:
  if name.endswith('.server.log'):logs[name]=logs[name].replace(before,after)
 controls.append((label,r,logs))
row('missing row',lambda _:True,lambda _:'')
for scenario,key in [('denied','cmd_normal_direct_near'),('bulk','cmd_persistent_imported_near'),('radius','cmd_normal_direct_far'),('radius','cmd_normal_direct_boundary'),('radius_force','cmd_npc_direct_cross')]:
 row('wrong selection '+scenario+key,lambda l,s=scenario,k=key:f'scenario={s}|' in l and f'key={k}|' in l and 'phase=after|' in l,lambda l:l.replace('Entity=true','Entity=WRONG').replace('Entity=false','Entity=WRONG'))
row('variable retained',lambda l:'scenario=force|' in l and 'phase=after|' in l,lambda l:l.replace('variable=null','variable=73'))
row('uuid snapshot retained',lambda l:'scenario=force|' in l and 'phase=after|' in l,lambda l:l.replace('uuidFile=false','uuidFile=true'))
row('numeric snapshot retained',lambda l:'scenario=force|' in l and 'phase=after|' in l,lambda l:l.replace('instanceFile=false','instanceFile=true'))
row('tree retained',lambda l:'scenario=force|' in l and 'phase=after|' in l,lambda l:l.replace('tree=false','tree=true'))
row('forced chunk retained',lambda l:'phase=cleaned|' in l,lambda l:l.replace('forced=false','forced=true'))
row('mob returns on restart',lambda l:'DCMD_RESTART|' in l,lambda l:l.replace('entity=false','entity=true'))
row('both lost callbacks',lambda l:'scenario=force|' in l and 'phase=after|' in l,lambda l:l.replace('Hooks=1','Hooks=0'))
log('control visuals missing','write.server.log',lambda s:s.replace('|rendered=10','|rendered=0',1))
log('non-owned model removed','write.server.log',lambda s:s.replace('|apiRejected=true','|apiRejected=false',1))
log('wrong permission','write.server.log',lambda s:s.replace('nativeAdmin=false','nativeAdmin=true',1))
log('wrong callback context','write.server.log',lambda s:s.replace('marker=73','marker=undefined',1))
log('death instead of removal','write.server.log',lambda s:s.replace('event=despawn','event=death',1))
log('duplicate callback','write.server.log',lambda s:s+'\n'+next(l for l in s.splitlines() if 'DCMD_HOOK|' in l))
log('missing source','source.yml',lambda s:s.replace('Despawn: NPC','Despawn: NORMAL',1))
log('snapshot resurrected','remaining-tree-files.json',lambda _:'["entity-orphan.yml"]')
log('client error','write.client.log',lambda s:s+'\nDCMD_CLIENT {"event":"error","message":"test"}')
log('wrong packet exception','write.client.log',lambda s:s.replace('declare_recipes','position'))
log('missing client','write.client.log',lambda s:'\n'.join(l for l in s.splitlines() if '"event":"join"' not in l))
for field,value in [('paperJarSha256','0'*64),('mythicMobsSha256','0'*64),('boots',report['boots'][:-1]),('extensions',[]),('observations',[])]:
 r=copy.deepcopy(report);r[field]=value;controls.append((field,r,texts))
for label,r,logs in controls:
 try:v.validate(r,logs)
 except (ValueError,KeyError):continue
 raise AssertionError('Mutation accepted: '+label)
print(f'Rejected all {len(controls)} command evidence mutations.')
