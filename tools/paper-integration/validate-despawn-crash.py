"""Validate actual SIGKILL boots, live observations and the raw saved entity regions independently."""
from datetime import datetime
from io import BytesIO
import gzip
import hashlib
import importlib.util
import itertools
import json
from pathlib import Path
import re
import sys
import uuid
import zipfile
import zlib
import nbtlib
import yaml
sys.dont_write_bytecode = True
spec = importlib.util.spec_from_file_location('registry_validator', Path(__file__).with_name('validate-player-variables.py'))
registry = importlib.util.module_from_spec(spec); spec.loader.exec_module(registry)
require, fields, unique = registry.require, registry.fields, registry.unique_pairs
GROUPS = ('active', 'unloaded', 'resumed')
MODES = ('NORMAL', 'CHUNK', 'NPC', 'NEVER', 'PERSISTENT')
PATHS = ('direct', 'imported')
STAGES = ('write', 'recover', 'final')
RETAINED = {'NEVER', 'PERSISTENT'}
EXCEPTIONS = ['MM-5.7.2-inactive-entity-registry']
KEYS = {f'{g}_{m.lower()}_{p}': (g, m, p, i) for g in GROUPS
        for i, (m, p) in enumerate(itertools.product(MODES, PATHS))}
PDC = 'helsteramobs:persistent_mob_variables'
def flag(v): return str(v).lower()
def doc(text): return yaml.load(text, Loader=registry.UniqueYaml)
def js(text): return json.loads(text, object_pairs_hook=unique)
def digest(data): return hashlib.sha256(data).hexdigest()
def location(g, i): return f'despawn-crash-{g},{(300+i*4)*16+3.25},110.0,4802.75'
def read_region(raw):
    require(len(raw) >= 8192, 'invalid region size')
    entities = []
    for slot in range(1024):
        record = int.from_bytes(raw[slot*4:slot*4+4], 'big')
        if not record: continue
        offset, sectors = record >> 8, record & 255
        start = offset * 4096
        require(offset >= 2 and sectors and start+5 <= len(raw), 'invalid region sector')
        size = int.from_bytes(raw[start:start+4], 'big')
        require(1 < size <= sectors*4096-4 and start+4+size <= len(raw), 'invalid chunk length')
        kind = raw[start+4]; payload = raw[start+5:start+4+size]
        require(kind in (1, 2, 3), 'unsupported region compression')
        if kind == 1: payload = gzip.decompress(payload)
        if kind == 2: payload = zlib.decompress(payload)
        chunk = nbtlib.File.parse(BytesIO(payload)).unpack(json=True)
        for entity in chunk.get('Entities', []):
            parts = entity['UUID']
            identity = str(uuid.UUID(bytes=b''.join((i & 0xffffffff).to_bytes(4, 'big') for i in parts)))
            entities.append({'uuid': identity, 'chunk': chunk['Position'], 'entity': entity})
    return entities

def validate(report, payloads):
    require(report['schemaVersion'] == 2 and report['evidenceMode'] == 'comparison-with-exception', 'wrong schema/mode')
    require(report['knownExceptions'] == EXCEPTIONS, 'undocumented exceptions')
    require(report['paperJarSha256'] == '39bd8c00b9e18de91dcabd3cc3dcfa5328685a53b7187a2f63280c22e2d287b9', 'wrong Paper')
    require(report['mythicMobsSha256'] == '9672457cafc56ff841606bfbb710947329c08720ffc207746b60130d43bf902f', 'wrong MM')
    require(all(re.fullmatch('[a-f0-9]{64}', report[k]) for k in ('pluginSha256', 'probeJarSha256')), 'missing artifact hash')
    sources = {'run-despawn-crash-fixture.py', 'run-player-variable-fixture.py', 'validate-despawn-crash.py',
               'validate-player-variables.py', 'test-despawn-crash-evidence.py',
               'random-spawn-fixture/src/main/java/dev/helstera/test/randomspawn/DespawnCrashProbe.java',
               'random-spawn-fixture/src/main/java/dev/helstera/test/randomspawn/VariablePlaceholderAudit.java'}
    require(set(report['sourceHashes']) == {'tools/paper-integration/'+p for p in sources}
            and all(re.fullmatch('[a-f0-9]{64}', h) for h in report['sourceHashes'].values()), 'wrong source hashes')
    require([b['stage'] for b in report['boots']] == list(STAGES), 'missing/reordered boots')
    previous = None
    for b in report['boots']:
        start, end = [datetime.fromisoformat(b[k]) for k in ('startedAtUtc', 'stoppedAtUtc')]
        require(start < end and (previous is None or previous < start), 'overlapping/reversed boots'); previous = end
        require((b['exitCode'], b['termination']) == ((0, 'graceful') if b['stage'] == 'final' else (-9, 'SIGKILL')), 'not a real forced crash')
    texts = {n: b.decode('utf-8-sig') for n, b in payloads.items() if not n.endswith('.mca')}
    ids = doc(texts['identities.yml']); require(set(ids) == set(GROUPS), 'wrong identity groups')
    all_ids = set()
    for g in GROUPS:
        require(set(ids[g]) == {'world', 'mobs'} and set(ids[g]['mobs']) == {k for k,v in KEYS.items() if v[0] == g}, 'wrong identity keys')
        all_ids.add(ids[g]['world'])
        for pair in ids[g]['mobs'].values():
            require(set(pair) == {'native', 'runtime'}, 'wrong identity backend'); all_ids.update(pair.values())
    require(len(all_ids) == 63 and all(str(uuid.UUID(v)) == v for v in all_ids), 'duplicate/invalid UUID')
    observations = []; identities = []; mappings = []
    for stage in STAGES:
        text = texts[stage+'.server.log']; lines = text.splitlines()
        def marked(marker): return [(i, fields(l, marker)) for i,l in enumerate(lines) if marker in l]
        for error in ('DCRASH_FAILED|', 'Error occurred while', 'Variable checkpoint load failed',
                      'Variable checkpoint save failed', 'SkillTree unload save failed', 'Exception:'):
            require(error not in text, 'failed stage: '+error)
        rows = marked('DCRASH_ROW|')
        order = []
        for g in GROUPS:
            phases = ['seeded'] if stage == 'write' else [stage]
            if stage == 'write' and g != 'active': phases += ['unloaded']
            if stage == 'write' and g == 'resumed': phases += ['resumed', 'changed']
            order += [(phase,k) for phase in phases for k,v in KEYS.items() if v[0] == g]
        if stage != 'write': order += [('cleared',k) for k in KEYS]
        require([(r['phase'],r['key']) for _,r in rows] == order, 'missing/duplicate/reordered rows')
        observations += [l for l in lines if 'DCRASH_ROW|' in l]
        for _, r in rows:
            phase, key = r['phase'], r['key']; g,m,p,i = KEYS[key]
            retained = m in RETAINED
            alive = phase == 'seeded' or retained and (phase in ('resumed','changed','recover') or stage != 'write' and p == 'imported' and (phase == 'final' or stage == 'recover'))
            original = phase in ('seeded','resumed','changed') or phase == 'recover' and g != 'active'
            updated = alive and (phase == 'final' or phase == 'cleared')
            expected = {'nativeEntity':flag(alive), 'runtimeEntity':flag(alive), 'nativeRegistry':flag(alive), 'runtimeInstance':flag(alive),
                        'tree':str((123 if phase == 'changed' else 23)+i) if alive and original else 'absent',
                        'visuals':'11' if alive else '0', 'forced':flag(alive and m == 'PERSISTENT')}
            for b in ('native','runtime'):
                has_original = alive and original or b == 'runtime' and phase == 'unloaded' and retained
                expected.update({b+'Saved': str((107 if phase == 'changed' else 7)+i) if has_original else 'absent',
                                 b+'Transient':'99' if has_original else 'absent', b+'Point':location(g,i) if has_original else 'absent',
                                 b+'Updated':str(31+i) if updated else 'absent'})
            require(set(r) == {'phase','key'} | set(expected), 'wrong row fields')
            for name,want in expected.items(): require(r[name] == want, f'{stage}/{key}/{phase}/{name}: {r[name]} != {want}')
        ready = marked('DCRASH_READY|'); flush = marked('DCRASH_FLUSH|'); checkpoint = marked('DCRASH_CHECKPOINT|')
        require(len(ready) == len(flush) == len(checkpoint) == 1, 'missing/duplicate checkpoints')
        require(ready[0][1] == {'stage':stage,'rows':str(70 if stage == 'write' else 30)}, 'wrong ready count')
        require(flush[0][1]['stage'] == stage and flush[0][1]['accepted'] == 'true', 'flush rejected')
        require(checkpoint[0][1] == {'stage':stage,'rows':str(len(rows))} and rows[-1][0] < flush[0][0] < checkpoint[0][0], 'checkpoint before observations')
        require(ready[0][0] < flush[0][0] and 'Saved the game' in text, 'no world flush')
        cleared = marked('DCRASH_CLEARED|'); cold = marked('DCRASH_COLD|')
        require(len(cleared) == (0 if stage == 'write' else 1), 'missing clear completion')
        if cleared: require(cleared[0][1] == {'stage':stage} and ready[0][0] < cleared[0][0] < flush[0][0], 'clear ordering')
        require(len(cold) == (0 if stage == 'write' else 6), 'missing cold controls')
        if cold:
            require({r['key'] for _,r in cold} == {k for k,v in KEYS.items() if v[1] == 'NEVER'}, 'wrong cold coverage')
            for at,r in cold:
                require(r['stage'] == stage and r['loaded'] == 'false', 'NEVER loaded eagerly')
                require(at < next(j for j,row in rows if row['key'] == r['key']), 'late cold check')
        unloads = marked('DCRASH_WORLD_UNLOAD|')
        require([r['group'] for _,r in unloads] == list(GROUPS[1:] if stage == 'write' else GROUPS), 'wrong unload coverage')
        require(all(r['stage'] == stage and r['accepted'] == 'true' for _,r in unloads), 'rejected unload')
        for at,r in unloads: require(at < checkpoint[0][0], 'unload after checkpoint')
        callbacks = re.findall(r'DCRASH_HOOK (native|runtime) (despawn|death) (\w+)', text)
        want = {(b,'despawn',k) for b in ('native','runtime') for k,(g,m,p,i) in KEYS.items() if g != 'active' and m not in RETAINED} if stage == 'write' else {
            (b,'death',k) for b in ('native','runtime') for k,(g,m,p,i) in KEYS.items() if m in RETAINED and p == ('direct' if stage == 'recover' else 'imported')}
        require(len(callbacks) == len(want) and set(callbacks) == want, 'missing/duplicate/unexpected callbacks')
        events = marked('DCRASH_EVENT|'); deaths = []
        for at,r in events:
            require(r['key'] in KEYS and r['backend'] in ('native','runtime') and r['stage'] == stage, 'invalid event')
            g,m,p,i = KEYS[r['key']]
            require(r['uuid'] == ids[g]['mobs'][r['key']][r['backend']] and r['event'] in ('load','death'), 'changed event UUID')
            if r['event'] == 'death':
                deaths.append((r['backend'],'death',r['key']))
                require(ready[0][0] < at < next(j for j,row in rows if row['key'] == r['key'] and row['phase'] == 'cleared'), 'death outside cleanup')
        expected_deaths = {c for c in want if c[1] == 'death'}
        require(len(deaths) == len(expected_deaths) and set(deaths) == expected_deaths, 'missing/duplicate death events')
        for at,r in cold:
            if stage == 'final' and KEYS[r['key']][2] == 'direct': continue
            end = next(j for j,row in rows if row['key'] == r['key'])
            for b in ('native','runtime'):
                require(len([v for j,v in events if at < j < end and v['key'] == r['key'] and v['backend'] == b and v['event'] == 'load']) == 1, 'missing lazy load event')
        if stage != 'final':
            require('Stopping server' not in text and 'DCRASH_DISABLE|' not in text and 'Disabling helsteraMobs' not in text, 'graceful shutdown passed as crash')
        else:
            stop = [i for i,l in enumerate(lines) if ']: Stopping server' in l]
            require(len(stop) == 1 and stop[0] > checkpoint[0][0] and text.count('DCRASH_DISABLE|stage=final') == 1, 'missing final graceful shutdown')
        identities += [r for _,r in marked('DCRASH_ID|')]; mappings += [r for _,r in marked('DCRASH_MAPPING|')]
    require(report['observations'] == observations and len(observations) == 190, 'report/log disagreement')
    require(len(identities) == 30 and {r['key'] for r in identities} == set(KEYS), 'identity log incomplete')
    for r in identities:
        g,m,p,i = KEYS[r['key']]
        require(r == {'key':r['key'],'mode':m,'path':p,'native':ids[g]['mobs'][r['key']]['native'],
                      'runtime':ids[g]['mobs'][r['key']]['runtime'],'world':ids[g]['world'],'chunk':f'{300+i*4},300'}, 'identity metadata mismatch')
    require(len(mappings) == 30 and {r['key'] for r in mappings} == set(KEYS) and all(r['mode'] == KEYS[r['key']][1] and r['trigger'] == 'true' for r in mappings), 'mapping mismatch')
    expected_files = {'identities.yml'} | {s+'.server.log' for s in STAGES}
    for stage, moment in itertools.product(STAGES, ('online','stopped')):
        prefix = f'snapshots/{stage}-{moment}/'
        expected_files |= {prefix+'entities.json', prefix+'inventory.json'}
        regions = js(texts[prefix+'entities.json']); require(set(regions) == {'despawn-crash-'+g for g in GROUPS}, 'missing region groups')
        tree_keys = {k for k,(g,m,p,i) in KEYS.items() if m in RETAINED and (stage == 'write' and g != 'active' or stage == 'recover' and p == 'imported')}
        inventory = ['entity-'+ids[KEYS[k][0]]['mobs'][k]['runtime']+'.yml' for k in tree_keys]
        if stage == 'final' and moment == 'stopped': inventory += ['scoped-variables.yml']
        require(sorted(js(texts[prefix+'inventory.json'])) == sorted(inventory), 'orphan/missing disk state')
        expected_files |= {prefix+n for n in inventory}
        for k in tree_keys:
            tree = doc(texts[prefix+'entity-'+ids[KEYS[k][0]]['mobs'][k]['runtime']+'.yml'])
            require(tree['snapshot-format'] == 4, 'wrong tree format')
            require(list(tree['variables']['entries'].values()) == ([{'name':'CrashTree','value':{'type':'INTEGER','value':23+KEYS[k][3]}}] if stage == 'write' else []), 'stale/missing tree checkpoint')
            g,_,_,i = KEYS[k]
            require(list(tree['locations']['entries'].values()) == ([{'name':'origin','world':'despawn-crash-'+g,'x':(300+i*4)*16+2.25,'y':110.,'z':4802.75,'yaw':0.,'pitch':0.}] if stage == 'write' else []), 'wrong tree origin')
        if 'scoped-variables.yml' in inventory: require(doc(texts[prefix+'scoped-variables.yml']) == {'registry-format':1,'registries':{}}, 'orphan final registry')
        for g in GROUPS:
            region = regions['despawn-crash-'+g]; parsed = []
            require(len({f['name'] for f in region['files']}) == len(region['files']), 'duplicate region files')
            for f in region['files']:
                require(re.fullmatch(r'r\.-?\d+\.-?\d+\.mca', f['name']) is not None, 'invalid region filename')
                name = prefix+'regions/despawn-crash-'+g+'/'+f['name']; expected_files.add(name)
                raw = payloads[name]; require(len(raw) == f['size'] and digest(raw) == f['sha256'], 'changed raw region')
                parsed += read_region(raw)
            require(parsed == region['entities'], 'region projection differs from raw NBT')
            by_id = unique((e['uuid'],e) for e in parsed)
            living = {k for k,(group,m,p,i) in KEYS.items() if group == g and m in RETAINED and (stage == 'write' or stage == 'recover' and p == 'imported')}
            require(set(by_id) == {ids[g]['mobs'][k][b] for k in living for b in ('native','runtime')}, 'saved orphan/lost entity or visuals')
            for k in living:
                _,m,p,i = KEYS[k]
                for b in ('native','runtime'):
                    e = by_id[ids[g]['mobs'][k][b]]
                    require(e['chunk'] == [300+i*4,300] and e['entity']['id'] == 'minecraft:cow', 'wrong saved entity')
                    data = e['entity'].get('BukkitValues', {})
                    if b == 'runtime':
                        require(data['helsteramobs:persistent_mob_id'] == 'crash_'+k, 'wrong persistent mob marker')
                        saved = data.get(PDC)
                        if stage == 'write' and g == 'active': require(saved is None, 'active entity gained unsolicited checkpoint'); continue
                        require(isinstance(saved,str), 'missing entity variable checkpoint')
                        registry_doc = doc(saved); require(registry_doc['registry-format'] == 1, 'wrong checkpoint format')
                        entries = list(registry_doc['registries'].values()); require(len(entries) == 1 and entries[0]['scope'] == 'entity:'+e['uuid'], 'wrong checkpoint UUID')
                        snapshot = entries[0]['snapshot']; require(snapshot['snapshot-format'] == 4, 'wrong variable snapshot')
                        values = unique((v['name'],v['value']) for v in snapshot['variables']['entries'].values())
                        want = {'Saved':{'type':'INTEGER','value':7+i},'Transient':{'type':'INTEGER','value':99}} if stage == 'write' else {'Updated':{'type':'INTEGER','value':31+i}}
                        require(values == want, 'wrong saved scalar checkpoint')
                        points = list(snapshot['locations']['entries'].values())
                        require(points == ([{'name':'Point','world':'despawn-crash-'+g,'x':(300+i*4)*16+3.25,'y':110.,'z':4802.75,'yaw':0.,'pitch':0.}] if stage == 'write' else []), 'wrong location checkpoint')
                        require(list(entries[0]['variable-retention'].values()) == ([{'name':'Transient','expires-at':1}] if stage == 'write' else []) and entries[0]['location-retention'] == {}, 'wrong retention checkpoint')
                    else:
                        require(data['mythicmobs:type'] == k, 'wrong native marker')
                        saved = data.get('mythicmobs:variables')
                        if stage == 'write' and g == 'active': require(saved is None, 'native active checkpoint changed'); continue
                        entries = js(saved)['entries']
                        require(set(entries) == ({'Saved','Transient','Point'} if stage == 'write' else {'Updated'}), 'wrong native saved names')
                        for name,value in ({'Saved':7+i,'Transient':99} if stage == 'write' else {'Updated':31+i}).items():
                            require(entries[name] == {'value':value,'expirationTime':1 if name == 'Transient' else 0,'type':'Integer'}, 'wrong native scalar checkpoint')
                        if stage == 'write':
                            require(entries['Point'] == {'value':{'world':{'uuid':ids[g]['world'],'name':'despawn-crash-'+g},'x':(300+i*4)*16+3.25,'y':110.,'z':4802.75,'yaw':0.,'pitch':0.},'expirationTime':0,'type':'Location'}, 'wrong native location checkpoint')
        if stage != 'final' and moment == 'stopped':
            for name in list(expected_files):
                if name.startswith(prefix): require(payloads[name] == payloads[name.replace('-stopped/','-online/')], 'disk changed after SIGKILL')
    require(set(payloads) == expected_files, 'missing/extra evidence files')
    return 190

def main(path):
    root = Path(path); archived = root.is_file()
    report = js((root if archived else root/'despawn-crash-report.json').read_text())
    if archived:
        name = report['logArchive']; require(Path(name).name == name and '\\' not in name, 'unsafe archive')
        archive = root.parent/name; require(digest(archive.read_bytes()) == report['logArchiveSha256'], 'archive changed')
        with zipfile.ZipFile(archive) as z:
            names = z.namelist(); require(len(names) == len(set(names)) and set(names) == {f['path'] for f in report['files']}, 'archive membership changed')
            payloads = {n:z.read(n) for n in names}
    else:
        payloads = {}
        for name,key in [('paper.jar','paperJarSha256'),('plugins/MythicMobs.jar','mythicMobsSha256'),('plugins/helsteraMobs.jar','pluginSha256'),('plugins/HelsteraDespawnCrashProbe.jar','probeJarSha256')]:
            require(digest((root/name).read_bytes()) == report[key], 'artifact changed')
    seen = set()
    for f in report['files']:
        name = f['path']; require(not Path(name).is_absolute() and '..' not in Path(name).parts and '\\' not in name and name not in seen, 'unsafe/duplicate member'); seen.add(name)
        if not archived: payloads[name] = (root/name).read_bytes()
        require(digest(payloads[name]) == f['sha256'], 'member changed')
    print(f'Validated {validate(report,payloads)} observations, two SIGKILL crashes, raw entity NBT, 60 UUIDs and final cleanup.')
    return report,payloads
if __name__ == '__main__': main(sys.argv[1])
