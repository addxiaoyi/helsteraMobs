"""Corrupt correlated report/log evidence to exercise semantic rejection paths."""
import copy
import importlib.util
from pathlib import Path
import sys
sys.dont_write_bytecode=True
s=importlib.util.spec_from_file_location('v',Path(__file__).with_name('validate-rapid-reload.py'));v=importlib.util.module_from_spec(s);s.loader.exec_module(v)
report,payloads=v.main(sys.argv[1]);controls=[]
def row(label,key,backend,phase,change):
    r=copy.deepcopy(report);p=dict(payloads);i=next(i for i,l in enumerate(r['observations']) if f'key={key}|' in l and f'backend={backend}|' in l and f'phase={phase}|' in l)
    before=r['observations'][i];after=change(before);assert before!=after,label;r['observations'][i]=after;p['server.log']=p['server.log'].replace(before.encode(),after.encode());controls.append((label,r,p))
def text(label,name,change):
    p=dict(payloads);p[name]=change(p[name].decode()).encode();assert p[name]!=payloads[name],label;controls.append((label,report,p))
for label,b,phase,change in [
 ('missing row','runtime','initial',lambda t:''),
 ('lost live entity','runtime','settled',lambda t:t.replace('|entity=true','|entity=false')),
 ('native race falsely called recovered','native','settled',lambda t:t.replace('|registry=false','|registry=true')),
 ('runtime copies native race','runtime','settled',lambda t:t.replace('|registry=true','|registry=false')),
 ('lost marker','runtime','reload',lambda t:t.replace('|marker=73','|marker=absent')),
 ('lost tree','runtime','reload',lambda t:t.replace('|tree=91','|tree=absent')),
 ('orphan visuals','runtime','cleaned',lambda t:t.replace('|visuals=0','|visuals=11')),
 ('lost visuals','runtime','reload',lambda t:t.replace('|visuals=11','|visuals=0')),
 ('stale controller','runtime','reload',lambda t:t.replace('|boundValid=true','|boundValid=false')),
 ('native race not recovered','native','stable',lambda t:t.replace('|registry=true','|registry=false')),
 ('dead mob resurrected','runtime','cleaned',lambda t:t.replace('|entity=false','|entity=true')),
 ('no actual loaded chunk','runtime','reload',lambda t:t.replace('|loaded=true','|loaded=false')),
 ('fake callback count','runtime','cleaned',lambda t:t.replace('|deaths=1','|deaths=2')),
 ('wrong UUID','native','reload',lambda t:t.replace('|uuid=','|uuid=bad-')),
 ('missing checkpoint','runtime','settled',lambda t:v.re.sub(r'\|checkpoint=[^|]+','|checkpoint=absent',t)),
 ('duplicate phase','runtime','after1',lambda t:t.replace('|phase=after1','|phase=reload'))]:
    row(label,'r1_d1_never_direct',b,phase,change)
row('slow control becomes a race','r1_d40_never_direct','native','settled',lambda t:t.replace('|registry=true','|registry=false'))
row('boundary control becomes a race','r1_d11_never_direct','native','reload',lambda t:t.replace('|registry=true','|registry=false'))
row('disposable retained','r1_d1_normal_direct','runtime','settled',lambda t:t.replace('|entity=false','|entity=true'))
for label,source,target in [
 ('missing identity','RRELOAD_ID|','UNKNOWN|'),('duplicate UUID key','|backend=native|uuid=','|backend=runtime|uuid='),
 ('missing mapping','RRELOAD_MAPPING|','UNKNOWN|'),('wrong mapping','|trigger=true','|trigger=false'),
 ('missing request','RRELOAD_REQUEST|','UNKNOWN|'),('rejected request','|accepted=true','|accepted=false'),
 ('missing chunk event','RRELOAD_CHUNK_UNLOAD|','UNKNOWN|'),('no chunk save','|save=true','|save=false'),
 ('chunk still present','|wasAbsent=true','|wasAbsent=false'),('wrong reload delay','|delta=1|','|delta=2|'),
 ('missing reload','RRELOAD_LOAD_REQUEST|','UNKNOWN|'),('missing entity event','RRELOAD_ENTITY_EVENT|','UNKNOWN|'),
 ('missing callback','RRELOAD_HOOK|','UNKNOWN|'),('callback wrong UUID','|count=1|','|count=2|'),
 ('missing native event','RRELOAD_NATIVE_EVENT|','UNKNOWN|'),('missing native origin','handleMobDespawnEvent','wrongOrigin'),
 ('missing summary','RRELOAD_SUMMARY|','UNKNOWN|'),('wrong summary','|entities=420|','|entities=419|'),
 ('missing seal','RRELOAD_SEAL|','UNKNOWN|'),('shutdown missing',']: Stopping server',']: Other event')]:
    text(label,'server.log',lambda t,s=source,d=target:t.replace(s,d,1))
text('duplicate callback stack','server.log',lambda t:t+'\nRRELOAD_DUPLICATE_STACK|\n')
text('failed fixture','server.log',lambda t:t+'\nRRELOAD_FAILED|\n')
text('checkpoint load failed','server.log',lambda t:t+'\nVariable checkpoint load failed\n')
text('orphan registry','snapshots/scoped-variables.yml',lambda t:t.replace('registries: {}','registries: {orphan: bad}'))
for label,change in [('wrong schema',lambda r:r.update(schemaVersion=0)),('observation passed as verified',lambda r:r.update(evidenceMode='observation')),
 ('undocumented exception',lambda r:r.update(knownExceptions=[])),('hidden native race',lambda r:r.update(nativeExceptions=[])),('wrong server',lambda r:r.update(paperJarSha256='0'*64)),
 ('wrong native oracle',lambda r:r.update(mythicMobsSha256='0'*64)),('missing sources',lambda r:r['sourceHashes'].clear()),
 ('unclean shutdown',lambda r:r['boot'].update(exitCode=-9)),('invalid boot timing',lambda r:r['boot'].update(stoppedAtUtc=r['boot']['startedAtUtc']))]:
    r=copy.deepcopy(report);change(r);controls.append((label,r,payloads))
p=dict(payloads);p['snapshots/entity-orphan.yml']=b'bad';controls.append(('orphan tree file',report,p))
if report['schemaVersion']==2:
    r=copy.deepcopy(report);r['nativeDuplicateCallbacks']=[{'key':'invented'}];controls.append(('invented duplicate exception',r,payloads))
    text('runtime duplicate callback','server.log',lambda t:t+'\n'+next(l for l in t.splitlines() if 'RRELOAD_HOOK|' in l and '|backend=runtime|' in l))
for label,r,p in controls:
    try:v.validate(r,p)
    except (ValueError,KeyError):continue
    raise AssertionError('Accepted invalid evidence: '+label)
print('Rejected',len(controls),'rapid reload evidence mutations.')

# Replay a frozen rejected capture only inside this test. Its on-disk report
# remains observation-only; it must never be accepted by main() or a release gate.
if len(sys.argv)>2:
    diagnostic=Path(sys.argv[2]);original=v.js(diagnostic.read_text())
    assert original['evidenceMode']=='observation' and original['validationResult']=='rejected: failed fixture: RRELOAD_DUPLICATE_STACK|'
    archive=diagnostic.parent/original['logArchive'];assert v.digest(archive.read_bytes())==original['logArchiveSha256']
    with v.zipfile.ZipFile(archive) as z:
        assert len(z.namelist())==len(set(z.namelist()))
        raw={n:z.read(n) for n in z.namelist()}
    assert set(raw)=={f['path'] for f in original['files']}
    for f in original['files']:assert v.digest(raw[f['path']])==f['sha256']
    replay=copy.deepcopy(original);replay.update(schemaVersion=2,evidenceMode='comparison-with-exception',knownExceptions=v.EXCEPTIONS)
    lines=raw['server.log'].decode().splitlines()
    hooks=[v.fields(l,'RRELOAD_HOOK|') for l in lines if 'RRELOAD_HOOK|' in l]
    rows=[v.fields(l,'RRELOAD_ROW|') for l in lines if 'RRELOAD_ROW|' in l]
    replay['nativeDuplicateCallbacks']=sorted([{'key':h['key'],'uuid':h['uuid'],
        'active':next(r['active'] for r in rows if r['key']==h['key'] and r['backend']=='native' and r['phase']=='initial'),
        'tick':int(h['tick']),'callbacks':2,'nativeEvents':2}
        for h in hooks if h['backend']=='native' and h['count']=='2'],key=lambda r:r['key'])
    assert len(replay['nativeDuplicateCallbacks'])==2
    assert v.validate(replay,raw)==3360
    mutations=[]
    for label,change in [
        ('hidden duplicate',lambda r:r.update(nativeDuplicateCallbacks=[])),
        ('wrong active object',lambda r:r['nativeDuplicateCallbacks'][0].update(active='0')),
        ('wrong duplicate UUID',lambda r:r['nativeDuplicateCallbacks'][0].update(uuid='bad')),
        ('wrong event count',lambda r:r['nativeDuplicateCallbacks'][0].update(nativeEvents=1)),
        ('wrong duplicate count',lambda r:r['nativeDuplicateCallbacks'][0].update(callbacks=1)),
        ('wrong duplicate tick',lambda r:r['nativeDuplicateCallbacks'][0].update(tick=-1)),
        ('undocumented duplicate',lambda r:r.update(knownExceptions=v.LEGACY_EXCEPTIONS)),
        ('historical schema relabel',lambda r:r.update(schemaVersion=1,knownExceptions=v.LEGACY_EXCEPTIONS)),
        ('observer promoted',lambda r:r.update(evidenceMode='observation'))]:
        r=copy.deepcopy(replay);change(r);mutations.append((label,r,raw))
    for label,before,after in [
        ('missing duplicate stack','RRELOAD_DUPLICATE_STACK|','UNKNOWN|'),
        ('duplicate origin changed','CommandMechanic.castAtEntity','OtherMechanic.castAtEntity'),
        ('missing native event','RRELOAD_NATIVE_EVENT|','UNKNOWN|')]:
        p=dict(raw);p['server.log']=raw['server.log'].replace(before.encode(),after.encode(),1);mutations.append((label,replay,p))
    for label,r,p in mutations:
        try:v.validate(r,p)
        except (ValueError,KeyError):continue
        raise AssertionError('Accepted invalid duplicate diagnostic replay: '+label)
    assert v.js(diagnostic.read_text())==original
    print('Rejected',len(mutations),'duplicate diagnostic mutations; original capture remains observation-only.')
