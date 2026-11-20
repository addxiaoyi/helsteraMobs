"""Validate reload races without counting native lost registrations as parity matches."""
import base64
from datetime import datetime
import hashlib
import importlib.util
import itertools
import json
from pathlib import Path
import re
import sys
import uuid
import zipfile
import yaml
sys.dont_write_bytecode=True
spec=importlib.util.spec_from_file_location('registry_validator',Path(__file__).with_name('validate-player-variables.py'))
registry=importlib.util.module_from_spec(spec);spec.loader.exec_module(registry)
require,fields,unique=registry.require,registry.fields,registry.unique_pairs
MODES=('NORMAL','CHUNK','NPC','NEVER','PERSISTENT')
DELAYS=(1,4,5,6,10,11,40)
PATHS=('direct','imported');BACKENDS=('native','runtime')
PHASES=('initial','reload','after1','after5','after10','settled','stable','cleaned')
KEYS={f'r{r}_d{d}_{m.lower()}_{p}':(r,d,m,p) for r,d,m,p in itertools.product(range(1,4),DELAYS,MODES,PATHS)}
LEGACY_EXCEPTIONS=['MM-5.7.2-rapid-reload-inactive-registry','MM-5.7.2-deferred-disposable-cleanup']
EXCEPTIONS=LEGACY_EXCEPTIONS+['MM-5.7.2-duplicate-native-unload-callback']
SOURCES={'run-rapid-reload-fixture.py','run-player-variable-fixture.py','validate-rapid-reload.py','validate-player-variables.py','test-rapid-reload-evidence.py','random-spawn-fixture/src/main/java/dev/helstera/test/randomspawn/RapidReloadProbe.java'}
def js(s):return json.loads(s,object_pairs_hook=unique)
def doc(s):return yaml.load(s,Loader=registry.UniqueYaml)
def digest(b):return hashlib.sha256(b).hexdigest()
def flag(x):return str(bool(x)).lower()

def validate(report,payloads):
    require(report['schemaVersion'] in (1,2) and report['evidenceMode']=='comparison-with-exception','wrong schema/mode')
    require(report['knownExceptions']==(EXCEPTIONS if report['schemaVersion']==2 else LEGACY_EXCEPTIONS),'undocumented exceptions')
    require(report['paperJarSha256']=='39bd8c00b9e18de91dcabd3cc3dcfa5328685a53b7187a2f63280c22e2d287b9','wrong Paper')
    require(report['mythicMobsSha256']=='9672457cafc56ff841606bfbb710947329c08720ffc207746b60130d43bf902f','wrong native oracle')
    require(all(re.fullmatch('[a-f0-9]{64}',report[k]) for k in ('pluginSha256','probeJarSha256')),'invalid artifact hashes')
    require(set(report['sourceHashes'])=={'tools/paper-integration/'+s for s in SOURCES} and all(re.fullmatch('[a-f0-9]{64}',s) for s in report['sourceHashes'].values()),'missing source hashes')
    boot=report['boot'];require(boot['exitCode']==0 and datetime.fromisoformat(boot['startedAtUtc'])<datetime.fromisoformat(boot['stoppedAtUtc']),'unclean or invalid boot')
    require(set(payloads)=={'server.log','snapshots/final-scoped.yml','snapshots/scoped-variables.yml'},'missing/orphan disk state')
    for name in ('final-scoped.yml','scoped-variables.yml'):require(doc(payloads['snapshots/'+name])=={'registry-format':1,'registries':{}},'orphan final registry')
    text=payloads['server.log'].decode();lines=text.splitlines()
    for bad in ('RRELOAD_FAILED|','Error occurred while','Variable checkpoint load failed','Variable checkpoint save failed','SkillTree unload save failed','Exception:'):require(bad not in text,'failed fixture: '+bad)
    def marked(marker):return [(i,fields(l,marker)) for i,l in enumerate(lines) if marker in l]
    identities=marked('RRELOAD_ID|');ids=unique(((r['key'],r['backend']),(i,r)) for i,r in identities)
    require(set(ids)==set(itertools.product(KEYS,BACKENDS)),'missing/duplicate identities')
    require(len({r['uuid'] for _,r in identities})==420,'duplicate UUID')
    for (key,b),(_,r) in ids.items():
        rep,delay,mode,path=KEYS[key];x=256+4*(MODES.index(mode)*4+PATHS.index(path)*2+BACKENDS.index(b))
        require(r=={'key':key,'backend':b,'uuid':str(uuid.UUID(r['uuid'])),'x':str(x),'z':'256','mode':mode,'path':path,'delay':str(delay),'round':str(rep)},'invalid identity metadata')
    mappings=marked('RRELOAD_MAPPING|')
    require(len(mappings)==10 and {r['id'] for _,r in mappings}=={f'rr_{m.lower()}_{p}' for m,p in itertools.product(MODES,PATHS)},'incomplete mappings')
    for _,r in mappings:require(r['id']==f"rr_{r['mode'].lower()}_{r['path']}" and r['trigger']=='true','wrong mapping')
    rows=marked('RRELOAD_ROW|');require(report['observations']==[l for l in lines if 'RRELOAD_ROW|' in l] and len(rows)==3360,'report/log row mismatch')
    by_cell={key:[] for key in ids}
    for i,r in rows:
        key=(r['key'],r['backend']);require(key in ids and r['uuid']==ids[key][1]['uuid'],'row UUID mismatch');by_cell[key].append((i,r))
    requests=marked('RRELOAD_REQUEST|');chunks=marked('RRELOAD_CHUNK_UNLOAD|');loads=marked('RRELOAD_LOAD_REQUEST|');events=marked('RRELOAD_ENTITY_EVENT|');hooks=marked('RRELOAD_HOOK|');native_events=marked('RRELOAD_NATIVE_EVENT|')
    def group(entries):
        result={key:[] for key in ids}
        for i,r in entries:
            key=(r['key'],r['backend']);require(key in ids,'unknown event identity');result[key].append((i,r))
        return result
    reqs,unloads,load_requests,entity_events,callbacks=[group(e) for e in (requests,chunks,loads,events,hooks)]
    duplicate_stacks=unique(((r['key'],r['backend']),(i,r)) for i,r in marked('RRELOAD_DUPLICATE_STACK|'))
    require(report['schemaVersion']==2 or not duplicate_stacks,'historical schema cannot admit duplicate callbacks')
    duplicate_cases=[]
    exceptions=[]
    for key,cell_rows in by_cell.items():
        name,b=key;rep,delay,mode,path=KEYS[name];retained=mode in ('NEVER','PERSISTENT');identity=ids[key][1]['uuid']
        require([r['phase'] for _,r in cell_rows]==list(PHASES),'missing/duplicate/reordered phases')
        rmap={r['phase']:(i,r) for i,r in cell_rows};initial=rmap['initial'][1]
        req,unload,load=reqs[key],unloads[key],load_requests[key]
        require([r['phase'] for _,r in req]==['rapid-unload','stable-unload'],'missing/repeated unload requests')
        require([r['phase'] for _,r in unload]==['rapid-unload','stable-unload'],'missing/repeated real chunk events')
        require([r['phase'] for _,r in load]==['reload','stable-reload'],'missing/repeated reload requests')
        for index,(req_at,request) in enumerate(req):
            unload_at,exit=unload[index];load_at,entry=load[index];expected_delay=delay if index==0 else 40
            require(request['accepted']=='true' and exit['save']=='true' and entry['wasAbsent']=='true','unload not completed/saved')
            require(req_at<unload_at<load_at and int(request['tick'])<=int(exit['tick']) and int(entry['tick'])-int(exit['tick'])==int(entry['delta'])==expected_delay,'wrong public event/reload timing')
        require(rmap['initial'][0]<req[0][0]<load[0][0]<rmap['reload'][0] and rmap['settled'][0]<req[1][0]<load[1][0]<rmap['stable'][0],'wrong checkpoint ordering')
        reload_tick=int(load[0][1]['tick']);stable_tick=int(load[1][1]['tick'])+20
        previous=-1;lost=False;checkpoint_seen=False
        for at,r in cell_rows:
            phase=r['phase'];tick=int(r['tick']);require(tick>previous,'non-increasing observations');previous=tick
            if phase in ('reload','after1','after5','after10','settled'):require(tick==reload_tick+{'reload':0,'after1':1,'after5':5,'after10':10,'settled':40}[phase],'wrong observation offset')
            if phase in ('stable','cleaned'):require(tick==stable_tick+(70 if phase=='cleaned' else 0),'wrong recovery/cleanup timing')
            for f in ('entity','valid','registry','loaded','boundValid'):require(r[f] in ('true','false'),'invalid state flag')
            require(r['loaded']=='true','observation loaded a missing chunk')
            present=phase!='cleaned' and (retained or phase=='initial')
            if b=='native' and not retained and phase=='reload' and delay==1:present=r['entity']=='true'
            require(r['entity']==flag(present) and r['valid']==flag(present),'unexpected entity presence')
            active=present
            if b=='native':
                if phase=='cleaned' or not retained and phase in ('after10','settled','stable'):active=False
                elif retained and phase not in ('initial','stable'):
                    if delay==40:active=True
                    elif phase in ('after10','settled') and delay in (4,5,6,10):active=False
                    else:active=r['registry']=='true'
                    if lost:require(not active,'native registry returned without a reload')
                    lost |= not active
                elif not retained and phase not in ('initial','stable'):active=r['registry']=='true'
            require(r['registry']==flag(active),'wrong registry state')
            require(r['marker']==('73' if active else 'absent'),'lost/stale marker')
            require(r['tree']==('91' if b=='runtime' and present else 'absent'),'lost/orphan SkillTree')
            require(r['visuals']==('11' if b=='runtime' and present else '0'),'orphan/missing visuals')
            require(int(r['active'])>0 if active else r['active']=='0','invalid active object')
            require(int(r['live'])>0 if present else r['live']=='0','invalid live entity object')
            require(int(r['bound'])>0 if active else r['bound']=='0','invalid bound entity object')
            stale=active and present and r['bound']!=r['live']
            if stale:
                require(b=='native' and retained and phase in ('reload','after1','after5','after10','settled') and delay<40 and r['active']==initial['active'] and r['bound']==initial['bound'], 'unexpected stale entity binding')
                exceptions.append({'key':name,'phase':phase,'kind':'stale-binding'})
            require(r['boundValid']==flag(active and present and not stale),'invalid binding validity')
            if b=='native' and retained and present and not active:exceptions.append({'key':name,'phase':phase,'kind':'inactive-registry'})
            if retained and phase not in ('initial','cleaned'):
                if r['checkpoint']=='absent':
                    require(b=='native' and delay<40 and phase!='stable' and active and not checkpoint_seen and r['active']==initial['active'] and r['live']==initial['live'], 'missing retained checkpoint')
                    exceptions.append({'key':name,'phase':phase,'kind':'no-checkpoint-yet'})
                    saved=None
                else:saved=base64.b64decode(r['checkpoint'],validate=True);checkpoint_seen=True
                if b=='native' and saved is not None:require(js(saved)=={'entries':{'Marker':{'value':73,'expirationTime':0,'type':'Integer'}}},'native checkpoint lost')
                elif b=='runtime':
                    d=doc(saved);require(d['registry-format']==1 and len(d['registries'])==1,'invalid runtime checkpoint');reg=next(iter(d['registries'].values()));snap=reg['snapshot']
                    require(reg['scope']=='entity:'+identity and reg['variable-retention']==reg['location-retention']=={},'wrong checkpoint identity/retention')
                    require(snap['snapshot-format']==4 and list(snap['variables']['entries'].values())==[{'name':'Marker','value':{'type':'INTEGER','value':73}}] and snap['locations']['entries']=={} and snap['branches']=={'entries':{},'unlocked':[]} and snap['damage-tags']==[],'wrong runtime checkpoint')
            else:require(r['checkpoint']=='absent','unexpected entity checkpoint')
            for label,field in [('despawn','despawns'),('death','deaths')]:
                require(int(r[field])==len([e for j,e in callbacks[key] if j<at and e['event']==label]),'callback count disagrees with raw commands')
        if retained:
            require(rmap['stable'][1]['active']!=initial['active'],'stable reload reused old registry')
            for phase in ('reload','after1','after5','after10','settled'):require(rmap[phase][1]['live']==rmap['reload'][1]['live'],'entity changed without unload/load')
        expected_hook='death' if retained else 'despawn';cell_hooks=callbacks[key]
        repeated=len(cell_hooks)==2
        require(len(cell_hooks)==1 or report['schemaVersion']==2 and b=='native' and mode in ('CHUNK','NPC') and repeated,'missing/duplicate callback')
        for ordinal,(at,h) in enumerate(cell_hooks,1):
            require(h['uuid']==identity and h['event']==expected_hook and h['count']==str(ordinal) and h['thread']=='Server thread','wrong callback identity/type/thread')
            require((rmap['stable'][0] if retained else req[0][0])<at<rmap['cleaned' if retained else 'settled'][0],'callback outside lifecycle')
        events_for_cell=[(i,e) for i,e in native_events if e['key']==name] if b=='native' else []
        if b=='native' and not retained:require(len(events_for_cell)==len(cell_hooks),'native events/callbacks do not pair')
        if repeated:
            first,second=[h for _,h in cell_hooks]
            require(first['tick']==second['tick'] and first['phase']==second['phase']=='rapid-unload','duplicate callback outside original unload')
            tick=int(first['tick']);unload_tick=int(unload[0][1]['tick'])
            require(unload_tick<=tick<=unload_tick+5 and all(e['tick']==first['tick'] for _,e in events_for_cell),'duplicate events outside bounded dispatch window')
            stack_at,stack=duplicate_stacks[key]
            require(stack['event']=='despawn' and 'CommandMechanic.castAtEntity' in stack['stack'] and 'CraftScheduler.mainThreadHeartbeat' in stack['stack'] and cell_hooks[1][0]<stack_at<rmap['settled'][0],'missing/invalid duplicate dispatch origin')
            duplicate_cases.append({'key':name,'uuid':identity,'active':initial['active'],'tick':tick,'callbacks':2,'nativeEvents':2})
        else:require(key not in duplicate_stacks,'unexpected duplicate stack')
        evs=entity_events[key]
        for at,e in evs:
            require(e['uuid']==identity and e['event'] in ('unload','load','death') and int(e['object'])>0,'invalid entity event')
            if e['event']=='death':require(retained and rmap['stable'][0]<at<rmap['cleaned'][0] and e['object']==rmap['stable'][1]['live'],'unexpected/premature death')
        if retained:
            require([(e['event'],e['phase']) for _,e in evs]==[('unload','rapid-unload'),('load','reload'),('unload','stable-unload'),('load','stable-reload'),('death','killing')],'missing/duplicate retained entity events')
            require(req[0][0]<evs[0][0]<load[0][0] and req[1][0]<evs[2][0]<load[1][0],'entity unload outside requests')
            require(evs[0][1]['object']==initial['live'] and evs[1][1]['object']==rmap['reload'][1]['live'] and evs[2][1]['object']==rmap['settled'][1]['live'] and evs[3][1]['object']==rmap['stable'][1]['live'],'entity event objects disagree')
        else:
            require(len([e for _,e in evs if e['event']=='unload'])==1 and all(e['phase']=='rapid-unload' if e['event']=='unload' else e['event']=='load' and e['phase']=='reload' and b=='native' and delay==1 for _,e in evs),'unexpected disposable events')
    require(report['nativeExceptions']==sorted(exceptions,key=lambda r:(r['key'],r['phase'],r['kind'])), 'native exceptions omitted or altered')
    require(set(duplicate_stacks)=={(r['key'],'native') for r in duplicate_cases},'orphan duplicate stack')
    if report['schemaVersion']==2:require(report['nativeDuplicateCallbacks']==sorted(duplicate_cases,key=lambda r:r['key']),'hidden or altered native duplicate callbacks')
    require(len(native_events)==126+len(duplicate_cases) and {r['key'] for _,r in native_events}=={k for k,v in KEYS.items() if v[2] not in ('NEVER','PERSISTENT')},'missing/duplicate native despawn events')
    for at,e in native_events:
        key=(e['key'],'native');initial=by_cell[key][0][1]
        require(e['uuid']==ids[key][1]['uuid'] and e['active']==initial['active'] and e['thread']=='Server thread' and 'handleMobDespawnEvent' in e['stack'],'invalid native event origin')
        require(reqs[key][0][0]<at<by_cell[key][5][0],'native despawn outside lifecycle')
    summary=marked('RRELOAD_SUMMARY|');seal=marked('RRELOAD_SEAL|')
    require(len(summary)==len(seal)==1 and summary[0][1]=={'rows':'3360','entities':'420','completed':'true'} and seal[0][1]=={'accepted':'true'},'incomplete summary/seal')
    require(max(i for i,_ in rows)<seal[0][0]<summary[0][0] and text.count(']: Stopping server')==1 and next(i for i,l in enumerate(lines) if ']: Stopping server' in l)>summary[0][0],'premature shutdown')
    return len(rows)

def main(path):
    p=Path(path);archived=p.is_file();report=js((p if archived else p/'rapid-reload-report.json').read_text())
    if archived:
        name=report['logArchive'];require(Path(name).name==name and '\\' not in name,'unsafe archive');archive=p.parent/name;require(digest(archive.read_bytes())==report['logArchiveSha256'],'archive changed')
        with zipfile.ZipFile(archive) as z:
            names=z.namelist();require(len(names)==len(set(names)) and set(names)=={f['path'] for f in report['files']},'archive membership changed');payloads={n:z.read(n) for n in names}
    else:
        for name,key in [('paper.jar','paperJarSha256'),('plugins/helsteraMobs.jar','pluginSha256'),('plugins/MythicMobs.jar','mythicMobsSha256'),('plugins/HelsteraRapidReloadProbe.jar','probeJarSha256')]:require(digest((p/name).read_bytes())==report[key],'artifact changed')
        payloads={}
    seen=set()
    for f in report['files']:
        name=f['path'];require(not Path(name).is_absolute() and '..' not in Path(name).parts and '\\' not in name and name not in seen,'unsafe/duplicate member');seen.add(name)
        if not archived:payloads[name]=(p/name).read_bytes()
        require(digest(payloads[name])==f['sha256'],'member changed')
    print(f'Validated {validate(report,payloads)} observations, 420 UUIDs, seven reload delays and final cleanup.');return report,payloads
if __name__=='__main__':main(sys.argv[1])
