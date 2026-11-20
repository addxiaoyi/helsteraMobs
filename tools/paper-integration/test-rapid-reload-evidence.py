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
 ('rejected evidence relabelled',lambda r:r.update(validationResult='rejected: missing/duplicate callback')),
 ('empty validation result',lambda r:r.update(validationResult=None)),
 ('undocumented exception',lambda r:r.update(knownExceptions=[])),('hidden native race',lambda r:r.update(nativeExceptions=[])),('wrong server',lambda r:r.update(paperJarSha256='0'*64)),
 ('wrong native oracle',lambda r:r.update(mythicMobsSha256='0'*64)),('missing sources',lambda r:r['sourceHashes'].clear()),
 ('unclean shutdown',lambda r:r['boot'].update(exitCode=-9)),('invalid boot timing',lambda r:r['boot'].update(stoppedAtUtc=r['boot']['startedAtUtc']))]:
    r=copy.deepcopy(report);change(r);controls.append((label,r,payloads))
p=dict(payloads);p['snapshots/entity-orphan.yml']=b'bad';controls.append(('orphan tree file',report,p))
if report['schemaVersion'] in (2,3):
    r=copy.deepcopy(report);r['nativeDuplicateCallbacks']=[{'key':'invented'}];controls.append(('invented duplicate exception',r,payloads))
    text('runtime duplicate callback','server.log',lambda t:t+'\n'+next(l for l in t.splitlines() if 'RRELOAD_HOOK|' in l and '|backend=runtime|' in l))
for label,r,p in controls:
    try:v.validate(r,p)
    except (ValueError,KeyError):continue
    raise AssertionError('Accepted invalid evidence: '+label)
print('Rejected',len(controls),'rapid reload evidence mutations.')

# Replay a frozen rejected capture only inside this test. Its on-disk report
# remains observation-only; it must never be accepted by main() or a release gate.
for diagnostic_arg in sys.argv[2:]:
    diagnostic=Path(diagnostic_arg);frozen=diagnostic.read_bytes();original=v.js(frozen)
    assert original['evidenceMode']=='observation'
    normal_capture=original['validationResult']=='rejected: missing/duplicate callback'
    assert normal_capture or original['validationResult']=='rejected: failed fixture: RRELOAD_DUPLICATE_STACK|'
    try:v.main(diagnostic)
    except ValueError:pass
    else:raise AssertionError('Accepted frozen observation as release evidence')
    archive=diagnostic.parent/original['logArchive'];assert v.digest(archive.read_bytes())==original['logArchiveSha256']
    with v.zipfile.ZipFile(archive) as z:
        assert len(z.namelist())==len(set(z.namelist()))
        raw={n:z.read(n) for n in z.namelist()}
    assert set(raw)=={f['path'] for f in original['files']}
    for f in original['files']:assert v.digest(raw[f['path']])==f['sha256']
    replay=copy.deepcopy(original)
    replay.pop('validationResult')  # Only this in-memory replay loses the rejection marker.
    replay.update(schemaVersion=3 if normal_capture else 2,evidenceMode='comparison-with-exception',
                  knownExceptions=v.NORMAL_EXCEPTIONS if normal_capture else v.EXCEPTIONS)
    lines=raw['server.log'].decode().splitlines()
    hooks=[v.fields(l,'RRELOAD_HOOK|') for l in lines if 'RRELOAD_HOOK|' in l]
    rows=[v.fields(l,'RRELOAD_ROW|') for l in lines if 'RRELOAD_ROW|' in l]
    replay['nativeDuplicateCallbacks']=sorted([{'key':h['key'],'uuid':h['uuid'],
        'active':next(r['active'] for r in rows if r['key']==h['key'] and r['backend']=='native' and r['phase']=='initial'),
        'tick':int(h['tick']),'callbacks':2,'nativeEvents':2}
        for h in hooks if h['backend']=='native' and h['count']=='2'],key=lambda r:r['key'])
    assert len(replay['nativeDuplicateCallbacks'])==2
    if normal_capture:
        assert original['schemaVersion']==2 and original['nativeDuplicateCallbacks']==replay['nativeDuplicateCallbacks']
        assert {r['key'] for r in replay['nativeDuplicateCallbacks']}=={'r2_d10_chunk_direct','r2_d10_normal_imported'}
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
    if normal_capture:
        normal_key='r2_d10_normal_imported'
        def matching(line,marker,key=normal_key,backend=None):
            return marker in line and f'|key={key}|' in line and (backend is None or f'|backend={backend}|' in line)
        def value(line,name,replacement):
            return v.re.sub(r'\|'+name+r'=[^|]*',lambda _:f'|{name}={replacement}',line)
        def normal_entry(r):
            return next(e for e in r['nativeDuplicateCallbacks'] if e['key']==normal_key)
        def mutate_log(label,change,reason,metadata=lambda r:None):
            r=copy.deepcopy(replay);p=dict(raw);changed=change(list(lines))
            assert changed!=lines,label
            p['server.log']=('\n'.join(changed)+'\n').encode()
            # Keep the report correlated so failures exercise callback bounds,
            # rather than merely detecting disagreement with the archived log.
            r['observations']=[l for l in changed if 'RRELOAD_ROW|' in l]
            metadata(r);mutations.append((label,r,p,reason))
        def extra_hook(log,key,backend):
            at=max(i for i,l in enumerate(log) if matching(l,'RRELOAD_HOOK|',key,backend))
            hook=v.fields(log[at],'RRELOAD_HOOK|');count=int(hook['count'])+1
            log.insert(at+1,value(log[at],'count',count))
            field='despawns' if hook['event']=='despawn' else 'deaths'
            for i in range(at+2,len(log)):
                if matching(log[i],'RRELOAD_ROW|',key,backend):log[i]=value(log[i],field,count)
            return log
        for label,change,reason in [
            ('schema2 cannot admit NORMAL',lambda r:r.update(schemaVersion=2,knownExceptions=v.EXCEPTIONS),'missing/duplicate callback'),
            ('schema3 requires NORMAL exception',lambda r:r.update(knownExceptions=v.EXCEPTIONS),'undocumented exceptions'),
            ('relabelled rejected NORMAL capture',lambda r:r.update(validationResult=original['validationResult']),'rejected evidence cannot be compared'),
            ('empty rejected NORMAL marker',lambda r:r.update(validationResult=''),'rejected evidence cannot be compared'),
            ('hidden NORMAL metadata',lambda r:r.update(nativeDuplicateCallbacks=[e for e in r['nativeDuplicateCallbacks'] if e['key']!=normal_key]),'hidden or altered native duplicate callbacks'),
            ('missing duplicate metadata',lambda r:r.pop('nativeDuplicateCallbacks'),None),
            ('wrong NORMAL metadata tick',lambda r:normal_entry(r).update(tick=2416),'hidden or altered native duplicate callbacks')]:
            r=copy.deepcopy(replay);change(r);mutations.append((label,r,raw,reason))
        mutate_log('NORMAL runtime duplicate',lambda log:extra_hook(log,normal_key,'runtime'),'missing/duplicate callback')
        for mode in ('never','persistent'):
            mutate_log('retained '+mode+' duplicate',lambda log,m=mode:extra_hook(log,'r2_d10_'+m+'_imported','native'),'missing/duplicate callback')
        def third_pair(log):
            log=extra_hook(log,normal_key,'native')
            at=max(i for i,l in enumerate(log) if matching(l,'RRELOAD_NATIVE_EVENT|'))
            log.insert(at+1,log[at]);return log
        mutate_log('three paired NORMAL callbacks',third_pair,'missing/duplicate callback',
                   lambda r:normal_entry(r).update(callbacks=3,nativeEvents=3))
        unload=next(v.fields(l,'RRELOAD_CHUNK_UNLOAD|') for l in lines
                    if matching(l,'RRELOAD_CHUNK_UNLOAD|',backend='native') and '|phase=rapid-unload|' in l)
        assert int(unload['tick'])==2412 and normal_entry(replay)['tick']==2415
        late_tick=int(unload['tick'])+6
        mutate_log('late paired NORMAL callbacks',lambda log:[value(l,'tick',late_tick)
                   if matching(l,'RRELOAD_NATIVE_EVENT|') or matching(l,'RRELOAD_HOOK|',backend='native') else l for l in log],
                   'duplicate events outside bounded dispatch window',lambda r:normal_entry(r).update(tick=late_tick))
        mutate_log('NORMAL callbacks on different ticks',lambda log:[value(l,'tick',2416)
                   if matching(l,'RRELOAD_HOOK|',backend='native') and '|count=2|' in l else l for l in log],
                   'duplicate callback outside original unload')
        mutate_log('NORMAL callbacks outside original phase',lambda log:[value(l,'phase','stable-unload')
                   if matching(l,'RRELOAD_HOOK|',backend='native') else l for l in log],
                   'duplicate callback outside original unload')
        def unpaired(log):
            at=next(i for i,l in enumerate(log) if matching(l,'RRELOAD_NATIVE_EVENT|'))
            log.pop(at);return log
        mutate_log('unpaired NORMAL native event',unpaired,'native events/callbacks do not pair',
                   lambda r:normal_entry(r).update(nativeEvents=1))
        mutate_log('NORMAL event on different tick',lambda log:[value(l,'tick',2416)
                   if matching(l,'RRELOAD_NATIVE_EVENT|') else l for l in log],
                   'duplicate events outside bounded dispatch window')
        for label,marker,before,after,reason in [
            ('NORMAL native origin','RRELOAD_NATIVE_EVENT|','handleMobDespawnEvent','wrongOrigin','invalid native event origin'),
            ('NORMAL command origin','RRELOAD_DUPLICATE_STACK|','CommandMechanic.castAtEntity','OtherMechanic.castAtEntity','missing/invalid duplicate dispatch origin'),
            ('NORMAL scheduler origin','RRELOAD_DUPLICATE_STACK|','CraftScheduler.mainThreadHeartbeat','wrongScheduler','missing/invalid duplicate dispatch origin')]:
            mutate_log(label,lambda log,m=marker,b=before,a=after:[l.replace(b,a) if matching(l,m) else l for l in log],reason)
    for label,r,p,*reason in mutations:
        try:v.validate(r,p)
        except (ValueError,KeyError) as exc:
            if reason and reason[0]:assert reason[0] in str(exc),(label,str(exc))
            continue
        raise AssertionError('Accepted invalid duplicate diagnostic replay: '+label)
    assert diagnostic.read_bytes()==frozen
    assert v.digest(archive.read_bytes())==original['logArchiveSha256']
    print('Rejected',len(mutations),'duplicate diagnostic mutations; original capture remains observation-only.')
