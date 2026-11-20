"""Reject erased gaps and correlated falsifications of terminal lifecycle evidence."""
import copy
import importlib.util
from pathlib import Path
import sys
sys.dont_write_bytecode=True
s=importlib.util.spec_from_file_location('v',Path(__file__).with_name('validate-terminal-callback.py'));v=importlib.util.module_from_spec(s);s.loader.exec_module(v)
report,payloads=v.main(sys.argv[1]);controls=[]
def row(label,key,backend,phase,change):
    r=copy.deepcopy(report);p=dict(payloads);i=next(i for i,l in enumerate(r['observations']) if f'key={key}|' in l and f'backend={backend}|' in l and f'phase={phase}|' in l)
    before=r['observations'][i];after=change(before);assert before!=after,label;r['observations'][i]=after;p['server.log']=p['server.log'].replace(before.encode(),after.encode());controls.append((label,r,p))
def text(label,name,change):
    p=dict(payloads);p[name]=change(p[name].decode()).encode();assert p[name]!=payloads[name],label;controls.append((label,report,p))
for label,b,phase,change in [
 ('missing observation','runtime','initial',lambda t:''),
 ('wrong parent UUID','runtime','initial',lambda t:t.replace('|uuid=','|uuid=bad-')),
 ('missing live marker','runtime','initial',lambda t:t.replace('|marker=73','|marker=absent')),
 ('resurrected parent','runtime','settled',lambda t:t.replace('|entity=false','|entity=true')),
 ('orphan controller','runtime','cleaned',lambda t:t.replace('|registry=false','|registry=true')),
 ('orphan marker','runtime','cleaned',lambda t:t.replace('|marker=absent','|marker=73')),
 ('orphan visuals','runtime','cleaned',lambda t:t.replace('|visuals=0','|visuals=11')),
 ('native delayed children lost','native','settled',lambda t:t.replace('|children=2','|children=0')),
 ('gap called matched','runtime','settled',lambda t:t.replace('|children=0','|children=2')),
 ('native child cleanup lost','native','cleaned',lambda t:t.replace('|children=0','|children=2')),
 ('phase repeated','runtime','settled',lambda t:t.replace('|phase=settled','|phase=initial'))]:
    row(label,'command_cow_direct',b,phase,change)
row('positive summon control broken','control_cow_direct','runtime','settled',lambda t:t.replace('|children=2','|children=0'))
row('positive visual control broken','control_cow_direct','runtime','settled',lambda t:t.replace('|visuals=33','|visuals=11'))
for label,source,target in [
 ('missing identity','TCALL_ID|','UNKNOWN|'),('missing callback','TCALL_HOOK|','UNKNOWN|'),
 ('wrong callback marker','|marker=73|cleaning=','|marker=72|cleaning='),
 ('callback ran during cleanup','|cleaning=false|','|cleaning=true|'),
 ('off-thread command','|primary=true','|primary=false'),
 ('wrong callback label','|label=delay10|','|label=other|'),
 ('missing child origin','TCALL_CHILD|','UNKNOWN|'),('missing child state','TCALL_CHILD_STATE|','UNKNOWN|'),
 ('wrong child mode','|mode=NEVER|','|mode=NORMAL|'),
 ('missing action','TCALL_ACTION|','UNKNOWN|'),('missing removal command','TCALL_COMMAND|','UNKNOWN|'),
 ('failed command','|result=true|','|result=false|'),
 ('missing unload request','TCALL_REQUEST|','UNKNOWN|'),('missing real unload','TCALL_UNLOAD|','UNKNOWN|'),
 ('failed unload','|accepted=true|','|accepted=false|'),
 ('missing real death','TCALL_DEATH|','UNKNOWN|'),
 ('missing world seal','TCALL_SEAL|','UNKNOWN|'),('missing summary','TCALL_SUMMARY|','UNKNOWN|'),
 ('wrong case count','|cases=20|','|cases=19|'),
 ('missing shutdown',']: Stopping server',']: Other event')]:
    text(label,'server.log',lambda t,s=source,d=target:t.replace(s,d,1))
text('wrong delay','server.log',lambda t:v.re.sub(r'(TCALL_HOOK\|[^\n]+\|label=delay10\|[^\n]+\|tick=)(\d+)',lambda m:m[1]+str(int(m[2])+10),t,count=1))
text('wrong repeat interval','server.log',lambda t:v.re.sub(r'(TCALL_HOOK\|[^\n]+\|label=repeat\|[^\n]+\|tick=)(\d+)',lambda m:m[1]+str(int(m[2])+10),t,count=1))
text('duplicate child origin','server.log',lambda t:t+'\n'+next(l for l in t.splitlines() if 'TCALL_CHILD|' in l))
text('duplicate terminal callback','server.log',lambda t:t+'\n'+next(l for l in t.splitlines() if 'TCALL_HOOK|' in l and '|label=immediate|' in l))
text('fixture failed','server.log',lambda t:t+'\nTCALL_FAILED|\n')
text('orphan disk registry','snapshots/scoped-variables.yml',lambda t:t.replace('registries: {}','registries: {orphan: bad}'))
text('native pending control changed','source-mobs.yml',lambda t:t.replace('delay=80','delay=81',1))
text('native terminal stimulus changed','source-skills.yml',lambda t:t.replace('delay=10','delay=11',1))
text('mapped child name wrong','imports/skills.yml',lambda t:t.replace('mythic_tc_child_never','tc_child_never'))
text('mapped trigger lost','imports/tc_command_cow_imported.yml',lambda t:t.replace('on-despawn:','on-other:'))
text('wrong persistent child mode','imports/mythic_tc_child_persistent.yml',lambda t:t.replace('despawn: PERSISTENT','despawn: NEVER'))
for label,change in [
 ('wrong schema',lambda r:r.update(schemaVersion=0)),('observer passed as validated',lambda r:r.update(evidenceMode='observation')),
 ('gap called parity evidence',lambda r:r.update(evidenceMode='comparison-with-exception')),
 ('gap called complete',lambda r:r.update(parityStatus='complete')),('hidden gap cases',lambda r:r.update(gapCases=[])),
 ('undocumented difference',lambda r:r.update(knownExceptions=[])),('wrong Paper',lambda r:r.update(paperJarSha256='0'*64)),
 ('wrong oracle',lambda r:r.update(mythicMobsSha256='0'*64)),('missing source hashes',lambda r:r['sourceHashes'].clear()),
 ('unclean exit',lambda r:r['boot'].update(exitCode=-9)),('invalid boot timing',lambda r:r['boot'].update(stoppedAtUtc=r['boot']['startedAtUtc']))]:
    r=copy.deepcopy(report);change(r);controls.append((label,r,payloads))
p=dict(payloads);p['snapshots/entity-orphan.yml']=b'bad';controls.append(('orphan tree',report,p))
for label,r,p in controls:
    try:v.validate(r,p)
    except (ValueError,KeyError):continue
    raise AssertionError('Accepted invalid evidence: '+label)
print('Rejected',len(controls),'terminal callback evidence mutations.')
