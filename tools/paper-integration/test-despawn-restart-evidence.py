"""Reject incomplete, inconsistent and resurrected restart evidence."""
import copy
import importlib.util
from pathlib import Path
import sys
sys.dont_write_bytecode=True
spec=importlib.util.spec_from_file_location('validator',Path(__file__).with_name('validate-despawn-restart.py'))
v=importlib.util.module_from_spec(spec);spec.loader.exec_module(v)
report,texts=v.main(sys.argv[1]);controls=[]
def log(label,path,change):
    logs=dict(texts);logs[path]=change(logs[path]);assert logs[path]!=texts[path],label;controls.append((label,report,logs))
def row(label,predicate,change):
    r=copy.deepcopy(report);logs=dict(texts);i=next(i for i,l in enumerate(r['observations']) if predicate(l));before=r['observations'][i];after=change(before)
    assert before!=after,label;r['observations'][i]=after
    for name in logs:
        if name.endswith('.server.log'):logs[name]=logs[name].replace(before,after)
    controls.append((label,r,logs))
row('missing row',lambda _:True,lambda _:'')
row('duplicate row',lambda _:True,lambda _:report['observations'][1])
row('both wrong scalar',lambda l:'phase=write|' in l,lambda l:l.replace('Saved=7','Saved=8'))
row('orphan returns',lambda l:'phase=verify|' in l and 'key=normal_direct|' in l,lambda l:l.replace('runtimeEntity=false','runtimeEntity=true'))
row('lost never instance',lambda l:'phase=verify|' in l and 'key=never_direct|' in l,lambda l:l.replace('runtimeInstance=true','runtimeInstance=false'))
row('old tree returns',lambda l:'phase=final|' in l and 'key=never_imported|' in l,lambda l:l.replace('tree=absent','tree=30'))
row('deleted scalar returns',lambda l:'phase=final|' in l and 'key=never_imported|' in l,lambda l:l.replace('runtimeSaved=absent','runtimeSaved=14'))
row('dead entity returns',lambda l:'phase=final|' in l and 'key=never_direct|' in l,lambda l:l.replace('runtimeEntity=false','runtimeEntity=true'))
row('duplicated visuals',lambda l:'phase=verify|' in l and 'key=never_direct|' in l,lambda l:l.replace('visuals=11','visuals=22'))
row('changed location',lambda l:'phase=verify|' in l and 'key=never_direct|' in l,lambda l:l.replace(',110.0,',',111.0,'))
log('eager never chunk','verify.server.log',lambda t:t.replace('|loaded=false','|loaded=true',1))
log('missing cold control','verify.server.log',lambda t:t.replace('DRESTART_COLD|','UNKNOWN|',1))
log('missing entity load','verify.server.log',lambda t:t.replace('|event=load|','|event=unknown|',1))
log('missing death','verify.server.log',lambda t:t.replace('|event=death|','|event=unknown|',1))
log('missing callback','verify.server.log',lambda t:t.replace('DRESTART_HOOK runtime death','UNKNOWN runtime death',1))
log('missing shutdown callback','write.server.log',lambda t:t.replace('DRESTART_HOOK runtime despawn','UNKNOWN runtime despawn',1))
log('missing shutdown exception','write.server.log',lambda t:t.replace('org.bukkit.plugin.IllegalPluginAccessException:','other:',1))
log('wrong exception source','write.server.log',lambda t:t.replace('MythicMobs.jar//io.lumine.mythic.bukkit.utils.tasks.LumineExecutors$BukkitSyncExecutor.execute','OtherPlugin.execute'))
log('missing native disable','write.server.log',lambda t:t.replace('[MythicMobs] Disabling MythicMobs','[MythicMobs] Other event'))
log('exception before native disable','write.server.log',lambda t:'\n'.join(l for l in t.splitlines() if '[MythicMobs] Disabling MythicMobs' not in l)+'\n'+next(l for l in t.splitlines() if '[MythicMobs] Disabling MythicMobs' in l))
log('despawn before stop','write.server.log',lambda t:'\n'.join(l for l in t.splitlines() if ']: Stopping server' not in l)+'\n'+next(l for l in t.splitlines() if ']: Stopping server' in l))
log('unexpected later exception','verify.server.log',lambda t:t+'\norg.bukkit.plugin.IllegalPluginAccessException: Plugin attempted to register task while disabled\n')
log('runtime shutdown error','write.server.log',lambda t:t+'\nError occurred while disabling helsteraMobs\n')
log('missing mapping','write.server.log',lambda t:t.replace('DRESTART_MAPPING|','UNKNOWN|',1))
log('wrong mapping','write.server.log',lambda t:t.replace('|trigger=true','|trigger=false',1))
log('failed stage','final.server.log',lambda t:t+'\nDRESTART_FAILED|error=injected\n')
log('missing registry','snapshots/write/scoped.yml',lambda _:'registry-format: 1\nregistries: {}\n')
log('deleted scalar saved again','snapshots/verify/scoped.yml',lambda t:t.replace('name: Updated','name: Saved',1))
log('retention changed','snapshots/write/scoped.yml',lambda t:t.replace('expires-at: 1','expires-at: 0',1))
tree=next(n for n in texts if n.startswith('snapshots/write/entity-'))
log('lost disk tree',tree,lambda t:t.replace('name: RestartTree','name: WrongTree'))
log('duplicate YAML',tree,lambda t:t+'\nsnapshot-format: 4\n')
for label,change in [('missing boot',lambda r:r['boots'].pop()),('wrong boot order',lambda r:r['boots'].reverse()),
                     ('failed shutdown',lambda r:r['boots'][1].update(exitCode=1)),('missing artifact',lambda r:r.update(pluginSha256='')),
                     ('hidden exception',lambda r:r.update(knownExceptions=[]))]:
    r=copy.deepcopy(report);change(r);controls.append((label,r,texts))
for label,r,t in controls:
    try:v.validate(r,t)
    except (ValueError,KeyError):continue
    raise AssertionError('Accepted invalid evidence: '+label)
print(f'Rejected all {len(controls)} restart evidence mutation controls.')
