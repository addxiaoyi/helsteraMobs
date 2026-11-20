"""Independent checks for real death/respawn and four immediate client reconnects."""
import hashlib
import importlib.util
import itertools
import json
import math
from pathlib import Path
import re
import sys
import zipfile

sys.dont_write_bytecode = True
spec = importlib.util.spec_from_file_location('player_registry_validator', Path(__file__).with_name('validate-player-variables.py'))
registry = importlib.util.module_from_spec(spec); spec.loader.exec_module(registry)
require, fields, unique = registry.require, registry.fields, registry.unique_pairs
NAMES = registry.NAMES
CASES = ('Default', 'Saved', 'Transient', 'Negative', 'Timed', 'DeadExpiry', 'Float', 'Double', 'String', 'Location', 'Deleted', 'Generation')
PHASES = ('write', 'respawn', 'reconnect1', 'reconnect2', 'reconnect3', 'cleared', 'final')


def disk_values(texts):
    for player in NAMES:
        identity = registry.offline_id(player)
        native_doc = json.loads(texts[f'logout/native-{identity}.json'], object_pairs_hook=unique)
        require(native_doc['uniqueId'] == identity and native_doc['name'] == player, 'wrong disk player')
        native = native_doc['variables']['entries']
        runtime = registry.runtime_registry(texts[f'logout/runtime-{identity}.yml'], identity)
        expected = {'LifeHookD': 2, 'LifeHookI': 2}
        for actor, scope, path in itertools.product(range(2), ('caster', 'target'), ('D', 'I')):
            if NAMES[actor if scope == 'caster' else 1-actor] == player:
                expected[f'Life{path}{actor}{scope}Generation'] = actor*10+5
        require(set(native) == set(runtime) == set(expected), 'disk missing values or resurrected deletion')
        for name, value in expected.items():
            n, r = native[name], runtime[name]
            require(n['type'].upper() == r['type'] == 'INTEGER', 'wrong disk type')
            require(type(n['value']) is int and type(r['value']) is int and n['value'] == r['value'] == value, 'stale disk value')
            require(n['expirationTime'] == r['deadline'] == 0, 'wrong disk deadline')


def validate(report, texts):
    require(report['schemaVersion'] == 1 and report['evidenceMode'] == 'comparison', 'wrong mode')
    require(report['paperJarSha256'] == '39bd8c00b9e18de91dcabd3cc3dcfa5328685a53b7187a2f63280c22e2d287b9', 'wrong Paper')
    require(report['mythicMobsSha256'] == '9672457cafc56ff841606bfbb710947329c08720ffc207746b60130d43bf902f', 'wrong MM')
    require(all(re.fullmatch('[0-9a-f]{64}', report[k]) for k in ('pluginSha256', 'probeJarSha256')), 'missing artifact hash')
    require(report['saveIntervalSeconds'] == 300, 'wrong save interval')
    expected_files = {'server.log', 'client.log'} | {f'logout/{kind}-{registry.offline_id(n)}.{ext}' for n in NAMES for kind, ext in (('native', 'json'), ('runtime', 'yml'))}
    require(set(texts) == expected_files, 'missing/extra evidence files')
    text = texts['server.log']; lines = text.splitlines()
    require('PLAYER_LIFE_FAILED|' not in text, 'probe failed')
    require(text.count('PLAYER_LIFE_CLOCK|nativeSeconds=300|runtimeSeconds=300') == 1, 'clock not configured')
    require(text.count('PLAYER_LIFE_SUMMARY|observations=700|passed=true') == 1, 'missing summary')
    rows = [line for line in lines if 'PLAYER_LIFE|' in line or 'PLAYER_LIFE_HOOK|' in line]
    require(rows == report['observations'] and len(rows) == 700, 'incomplete observations')
    expected_keys = set(itertools.product(range(2), ('caster', 'target'), CASES, ('direct', 'imported')))
    original = {}; phase_positions = []
    for phase in PHASES:
        marker = f'PLAYER_LIFE_PHASE|phase={phase}|observations=100|online=2|passed=true'
        require(text.count(marker) == 1, 'phase incomplete'); phase_positions.append(text.index(marker))
        observed = [fields(line, 'PLAYER_LIFE|') for line in rows if 'PLAYER_LIFE|' in line and f'|phase={phase}|' in line]
        require(len(observed) == 96, 'missing variable rows'); seen = set()
        for row in observed:
            key = int(row['actor']), row['scope'], row['case'], row['path']
            require(key in expected_keys and key not in seen, 'unknown/duplicate row'); seen.add(key)
            actor, scope, kind, path = key
            require(row['uuid'] == registry.offline_id(NAMES[actor if scope == 'caster' else 1-actor]), 'wrong variable owner')
            present = kind == 'Generation' or phase == 'write' or phase not in ('cleared', 'final') and kind not in ('Deleted', 'DeadExpiry')
            require(row['expected'] == str(present).lower() and row['matched'] == row['isolated'] == 'true', 'wrong presence or contamination')
            if not present:
                require(row['native'] == row['runtime'] == row['nativeType'] == row['runtimeType'] == 'absent' and row['nativeDeadline'] == row['runtimeDeadline'] == '0', 'deleted/expired value resurrected')
            else:
                kind_type = kind if kind in ('Float', 'Double', 'String', 'Location') else 'Integer'
                require(row['nativeType'] == ('AbstractLocation' if kind == 'Location' else kind_type) and row['runtimeType'] == kind_type, 'value type changed')
                generation = {'write':1, 'respawn':1, 'reconnect1':2, 'reconnect2':3, 'reconnect3':4, 'cleared':5, 'final':5}[phase]
                value = str(actor*10+generation) if kind == 'Generation' else str(3.25+actor) if kind in ('Float', 'Double') else f'saved-{actor}' if kind == 'String' else str(actor*10+7)
                if kind == 'Location':
                    point = row['native'].split(',')
                    require(len(point) == 4 and point[0] == 'integration-world' and all(math.isfinite(float(v)) for v in point[1:]), 'invalid location')
                    if phase != 'write': require(row['native'] == original[key]['native'], 'location changed')
                else: require(row['native'] == value, 'wrong scalar value')
                require(row['native'] == row['runtime'], 'value mismatch')
                nd, rd = int(row['nativeDeadline']), int(row['runtimeDeadline'])
                if phase == 'write':
                    if kind in ('Timed', 'DeadExpiry'): require(nd > 1_000_000_000_000 and abs(nd-rd) < 1000, 'bad timer')
                    else: require(nd == rd == (1 if kind in ('Default', 'Transient') else -50 if kind == 'Negative' else 0), 'bad retention marker')
                else: require((nd, rd) == (int(original[key]['nativeDeadline']), int(original[key]['runtimeDeadline'])), 'deadline reset')
            if phase == 'write': original[key] = row
        hooks = [fields(line, 'PLAYER_LIFE_HOOK|') for line in rows if 'PLAYER_LIFE_HOOK|' in line and f'|phase={phase}|' in line]
        require(len(hooks) == 4 and {(r['name'], r['path']) for r in hooks} == set(itertools.product(NAMES, ('direct', 'imported'))), 'missing event hook')
        require(all(r['native'] == r['runtime'] == ('0' if phase == 'write' else '2') and r['passed'] == 'true' for r in hooks), 'lost event write')
    require(phase_positions == sorted(phase_positions), 'phase order changed')

    def marked(marker): return [(i, fields(line, marker)) for i, line in enumerate(lines) if marker in line]
    sessions = marked('PLAYER_LIFE_SESSION|')
    expected_sessions = set(itertools.product(('join', 'quit'), NAMES, map(str, range(1,6))))
    require(len(sessions) == 20 and {(s['event'], s['name'], s['attempt']) for _,s in sessions} == expected_sessions, 'missing/duplicate sessions')
    require(all(s['uuid'] == registry.offline_id(s['name']) for _,s in sessions), 'session identity differs')
    unloaded = marked('PLAYER_LIFE_UNLOADED|')
    require(len(unloaded) == 10 and {(s['name'], s['attempt']) for _,s in unloaded} == set(itertools.product(NAMES, map(str, range(1,6))))
            and all(s['native'] == s['runtime'] == 'true' for _,s in unloaded), 'offline registry retained')
    for name in NAMES:
        actual = [(s['event'], s['attempt']) for _,s in sessions if s['name'] == name]
        require(actual == [(event,str(attempt)) for attempt in range(1,6) for event in ('join','quit')], 'session order changed')
        for i,s in sessions:
            if s['name'] != name: continue
            if s['event'] == 'join': require(int(s['gapMillis']) == -1 if s['attempt'] == '1' else int(s['gapMillis']) >= 0, 'invalid reconnect gap')
            else: require(next(j for j,u in unloaded if u['name'] == name and u['attempt'] == s['attempt']) > i, 'unload preceded quit')
    deaths = marked('PLAYER_LIFE_EVENT|')
    require(len(deaths) == 4 and {(s['event'],s['name']) for _,s in deaths} == set(itertools.product(('death','respawn'),NAMES)), 'missing actual death/respawn')
    require(all(s['uuid'] == registry.offline_id(s['name']) for _,s in deaths), 'death/respawn identity differs')
    writes = marked('PLAYER_LIFE_EVENT_WRITE|')
    require(len(writes) == 8 and {(s['name'],s['path'],s['value']) for _,s in writes} == set(itertools.product(NAMES,('direct','imported'),('1','2'))), 'missing event writes')
    require(all(s['value'] == s['native'] == s['runtime'] for _,s in writes), 'event write differs')
    write_end = next(i for i,l in enumerate(lines) if 'PLAYER_LIFE_PHASE|phase=write|' in l)
    respawn_end = next(i for i,l in enumerate(lines) if 'PLAYER_LIFE_PHASE|phase=respawn|' in l)
    for name in NAMES:
        death = next(i for i,s in deaths if s['name'] == name and s['event'] == 'death')
        respawn = next(i for i,s in deaths if s['name'] == name and s['event'] == 'respawn')
        require(write_end < death < respawn < respawn_end, 'death/respawn order changed')
        for i,s in writes:
            if s['name'] == name: require(death < i < respawn if s['value'] == '1' else respawn < i < respawn_end, 'event write outside callback interval')

    events = [json.loads(line.split('PLAYER_LIFE_CLIENT ',1)[1], object_pairs_hook=unique) for line in texts['client.log'].splitlines() if 'PLAYER_LIFE_CLIENT ' in line]
    require(len(events) == 47 and events[-1]['event'] == 'summary' and events[-1]['passed'] is True, 'client incomplete')
    times = [e['elapsedMillis'] for e in events]
    require(all(type(t) in (float,int) and math.isfinite(t) and t >= 0 for t in times) and times == sorted(times) and times[-1] < 120000, 'invalid client duration')
    for name in NAMES:
        own = [e for e in events if e.get('name') == name]
        expected = []
        for attempt in range(1,6):
            expected += [(event,attempt) for event in ('connect','join')]
            if attempt == 1: expected += [(event,attempt) for event in ('death','respawn-request','respawned')]
            expected += [(event,attempt) for event in ('control','end')]
        require([(e['event'],e['attempt']) for e in own] == expected, 'client session/death sequence differs')
        require(all(e['uuid'] == registry.offline_id(name) for e in own if e['event'] in ('join','respawned')), 'client identity differs')
        death = next(e for e in own if e['event'] == 'death'); respawn = next(e for e in own if e['event'] == 'respawn-request')
        require(respawn['elapsedMillis']-death['elapsedMillis'] >= 3900, 'client did not wait dead for expiry')
        for e in own:
            if e['event'] == 'control':
                require(e['action'] == ('done' if e['attempt'] == 5 else 'reconnect') and e['phase'] == ('respawn','reconnect1','reconnect2','cleared','final')[e['attempt']-1], 'wrong client control')
            if e['event'] == 'connect' and e['attempt'] > 1:
                previous = next(p for p in own if p['event'] == 'end' and p['attempt'] == e['attempt']-1)
                require(0 <= e['elapsedMillis']-previous['elapsedMillis'] < 100, 'reconnect was delayed')
    disk_values(texts)
    return 700


def main(path):
    root = Path(path); archived = root.is_file()
    report = json.loads((root if archived else root/'player-life-report.json').read_text(), object_pairs_hook=unique)
    if archived:
        name = report['logArchive']; require(Path(name).name == name and '\\' not in name, 'unsafe archive')
        archive_path = root.parent/name; require(hashlib.sha256(archive_path.read_bytes()).hexdigest() == report['logArchiveSha256'], 'archive hash mismatch')
        with zipfile.ZipFile(archive_path) as archive:
            names = archive.namelist(); require(len(names) == len(set(names)) and set(names) == {e['path'] for e in report['files']}, 'archive members differ')
            payloads = {name:archive.read(name) for name in names}
    else:
        for name,key in (('paper.jar','paperJarSha256'),('plugins/MythicMobs.jar','mythicMobsSha256'),('plugins/helsteraMobs.jar','pluginSha256'),('plugins/HelsteraPlayerLifeProbe.jar','probeJarSha256')):
            require(hashlib.sha256((root/name).read_bytes()).hexdigest() == report[key], 'artifact changed')
    texts = {}
    for entry in report['files']:
        name = entry['path']; require(not Path(name).is_absolute() and '..' not in Path(name).parts and '\\' not in name and name not in texts, 'unsafe/duplicate member')
        data = payloads[name] if archived else (root/name).read_bytes()
        require(hashlib.sha256(data).hexdigest() == entry['sha256'], 'member hash mismatch'); texts[name] = data.decode('utf-8-sig')
    print(f'Validated {validate(report,texts)} player life observations, real death/respawn and eight immediate reconnects.')
    return report,texts


if __name__ == '__main__': main(sys.argv[1])
