"""Independent distance-policy checks; explicit Helstera radius is not native parity."""
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
spec = importlib.util.spec_from_file_location('registry', Path(__file__).with_name('validate-player-variables.py'))
registry = importlib.util.module_from_spec(spec); spec.loader.exec_module(registry)
require, fields, unique = registry.require, registry.fields, registry.unique_pairs
MODES = ('DEFAULT', 'NORMAL', 'CHUNK', 'NPC', 'NEVER', 'PERSISTENT', 'SPAWNING_DISTANCE')
TYPES = ('COW', 'ZOMBIE', 'VILLAGER', 'SLIME')
PATHS = ('direct', 'imported')
PHASES = {'player': ('near-initial', 'near', 'middle', 'far'),
          'empty': ('empty-initial', 'empty'), 'spectator': ('spectator-initial', 'spectator')}
NORMAL = {'DEFAULT', 'NORMAL', 'SPAWNING_DISTANCE'}
RETAINED = {'NEVER', 'PERSISTENT'}
def flag(value): return str(value).lower()
def close(actual, expected):
    value = float(actual)
    require(math.isfinite(value) and abs(value - expected) < 0.001, 'position/distance changed')

def validate(report, texts):
    require(report['schemaVersion'] in (1, 2) and report['evidenceMode'] == 'comparison-with-extension', 'wrong schema/mode')
    require(report['paperJarSha256'] == '39bd8c00b9e18de91dcabd3cc3dcfa5328685a53b7187a2f63280c22e2d287b9', 'wrong Paper')
    require(report['mythicMobsSha256'] == '9672457cafc56ff841606bfbb710947329c08720ffc207746b60130d43bf902f', 'wrong MM')
    require(all(re.fullmatch('[0-9a-f]{64}', report[k]) for k in ('pluginSha256', 'probeJarSha256')), 'missing artifact')
    required_sources = {'run-despawn-distance-fixture.py', 'despawn-distance-client.js', 'DespawnDistanceProbe.java',
                        'validate-despawn-distance.py', 'test-despawn-distance-evidence.py', 'validate-player-variables.py', 'run-player-variable-fixture.py'}
    require({Path(n).name for n in report['sourceHashes']} == required_sources and
            all(re.fullmatch('[0-9a-f]{64}', v) for v in report['sourceHashes'].values()), 'source hashes missing')
    require(report['extensions'] == ['direct-mob.spawning-distance'], 'extension boundary missing')
    require(report['clientKnownIssues'] == ['minecraft-protocol-1.21.1-declare-recipes-partial-read'], 'client limitation missing')
    require(set(texts) == {'server.log', 'client.log'}, 'wrong evidence files')
    lines = texts['server.log'].splitlines()
    def marked(marker): return [(i, fields(l, marker)) for i, l in enumerate(lines) if marker in l]
    require('DDIST_FAILED|' not in texts['server.log'], 'probe failed')
    summaries = marked('DDIST_SUMMARY|')
    require(len(summaries) == 1 and summaries[0][1] == {'rows': '448', 'completed': 'true'}, 'incomplete scenario')
    summary = summaries[0][0]
    keys = {'_'.join((c, t.lower(), m.lower(), p)): (c, t, m, p)
            for c, t, m, p in itertools.product(PHASES, TYPES, MODES, PATHS)}
    identities = marked('DDIST_ID|'); ids = {r['key']: r for _, r in identities}
    require(len(identities) == len(ids) == 168 and set(ids) == set(keys), 'missing/duplicate identity')
    uuids = {r[b] for r in ids.values() for b in ('native', 'runtime')}
    require(len(uuids) == 336 and all(re.fullmatch('[0-9a-f-]{36}', u) for u in uuids), 'identity collision')
    for key, (c, typ, mode, path) in keys.items():
        r = ids[key]; expected_mode = 'NORMAL' if mode in NORMAL else mode
        require((r['mode'], r['type'], r['path']) == (mode, typ, path), 'wrong identity metadata')
        require(r['nativeMode'] == r['runtimeMode'] == expected_mode, 'wrong mode/default')
        close(r['runtimeRadius'], 8 if mode == 'SPAWNING_DISTANCE' and path == 'direct' else 0)
        require(r['unsupportedDistance'] == ('8' if mode == 'SPAWNING_DISTANCE' and path == 'imported' else '0'), 'import boundary changed')
    raw_rows = [l for l in lines if 'DDIST_ROW|' in l]
    require(raw_rows == report['observations'] and len(raw_rows) == 448, 'missing rows')
    seen = {}; final_hooks = {}; expected_event_phase = {}
    for i, r in marked('DDIST_ROW|'):
        key, phase = r['key'], r['phase']; require(key in keys, 'unknown key')
        cohort, typ, mode, path = keys[key]
        require(r['cohort'] == cohort and phase in PHASES[cohort] and (key, phase) not in seen, 'phase coverage/duplicate')
        seen[key, phase] = (i, r)
        initial = phase.endswith('-initial')
        require(int(r['elapsed']) == 0 if initial else 80 <= int(r['elapsed']) <= 85, 'wrong observation timing')
        native_gone = cohort == 'player' and phase == 'far' and typ in ('ZOMBIE', 'SLIME') and mode in NORMAL
        extension = mode == 'SPAWNING_DISTANCE' and path == 'direct'
        runtime_gone = native_gone or extension and not initial
        expected = {'nativeEntity': flag(not native_gone), 'nativeRegistry': flag(not native_gone),
                    'runtimeEntity': flag(not runtime_gone), 'runtimeInstance': flag(not runtime_gone),
                    'runtimeController': flag(not runtime_gone), 'chunkLoaded': 'true',
                    'players': '0' if cohort == 'empty' else '1',
                    'playerMode': 'SPECTATOR' if cohort == 'spectator' else 'CREATIVE'}
        for b, gone in (('native', native_gone), ('runtime', runtime_gone)):
            expected.update({b+'Remove': flag(mode in NORMAL), b+'Persistent': flag(mode in RETAINED),
                             b+'Despawn': str(int(gone)), b+'Death': '0', b+'World': 'despawn-distance'})
            final_hooks[b, key] = int(gone)
            if gone: expected_event_phase[b, key] = ('near' if cohort == 'player' else cohort) if b == 'runtime' and extension else 'far'
        require(all(r[k] == v for k, v in expected.items()), 'wrong outcome '+key+'/'+phase)
        index = TYPES.index(typ)*14 + MODES.index(mode)*2 + PATHS.index(path)
        z = 4096.5+(index % 8)*.25
        distance = 16 if phase.startswith('near') else 64 if phase == 'middle' else 192
        for b in ('native', 'runtime'):
            close(r[b+'X'], 4096.5); close(r[b+'Y'], 110); close(r[b+'Z'], z)
            close(r[b+'Distance'], -1 if cohort == 'empty' else math.hypot(distance, z-4096.5))
        close(r['playerDistance'], 0)
        if cohort == 'empty': require(r['playerWorld'] != 'despawn-distance', 'empty world has player')
        else:
            require(r['playerWorld'] == 'despawn-distance', 'player in wrong world')
            close(r['playerX'], 4096.5+distance); close(r['playerY'], 110); close(r['playerZ'], 4096.5)
    require(set(seen) == {(k, p) for k, (c, _, _, _) in keys.items() for p in PHASES[c]}, 'missing phase')
    for key, (c, _, _, _) in keys.items():
        rows = [seen[key, p] for p in PHASES[c]]
        require([i for i, _ in rows] == sorted(i for i, _ in rows), 'phase order')
        require(all(int(a[1]['tick']) < int(b[1]['tick']) for a, b in zip(rows, rows[1:])), 'tick order')
    hooks = [(i, r) for i, r in marked('DDIST_HOOK|') if i < summary and r['phase'] != 'cleanup']
    expected_hooks = {k for k, count in final_hooks.items() if count}
    require(len(hooks) == len(expected_hooks) == 34, 'hook count')
    hook_keys = set()
    for i, r in hooks:
        bk = r['backend'], r['key']; require(bk in expected_hooks and bk not in hook_keys, 'unknown/duplicate hook')
        hook_keys.add(bk); c = keys[r['key']][0]; phase = expected_event_phase[bk]
        require(r['event'] == 'despawn' and r['count'] == '1' and r['phase'] == phase, 'wrong callback')
        require(seen[r['key'], PHASES[c][0]][0] < i < seen[r['key'], phase][0], 'callback outside phase')
    require(hook_keys == expected_hooks, 'missing hook')
    events = [(i, r) for i, r in marked('DDIST_EVENT|') if i < summary and r['phase'] != 'cleanup']
    removal_keys = set()
    for i, r in events:
        bk = r['backend'], r['key']; require(bk in expected_hooks, 'unexpected removal')
        require(r['event'] == 'remove' and r['uuid'] == ids[r['key']][r['backend']], 'wrong removal identity/death')
        c, typ, mode, path = keys[r['key']]; phase = expected_event_phase[bk]
        extension = r['backend'] == 'runtime' and mode == 'SPAWNING_DISTANCE' and path == 'direct'
        require(r['phase'] == phase and r['cause'] in ('DESPAWN', 'PLUGIN'), 'wrong removal phase/cause')
        # MM also calls remove() while unregistering its naturally removed mob.
        if r['backend'] == 'native' and r['cause'] == 'PLUGIN': continue
        require(r['cause'] == ('PLUGIN' if extension else 'DESPAWN') and bk not in removal_keys, 'missing natural cause/duplicate event')
        removal_keys.add(bk)
        require(seen[r['key'], PHASES[c][0]][0] < i < seen[r['key'], phase][0], 'removal outside phase')
        for suffix in ('X', 'Y', 'Z', 'Distance'):
            close(r['entity'+suffix], float(seen[r['key'], phase][1][r['backend']+suffix]))
    require(removal_keys == expected_hooks, 'missing server removal event')
    acks = marked('DDIST_ACK|')
    require([r['phase'] for _, r in acks] == ['near', 'middle', 'far', 'spectator'], 'server acknowledgement coverage')
    client = [json.loads(l.split('DDIST_CLIENT ', 1)[1], object_pairs_hook=unique)
              for l in texts['client.log'].splitlines() if l.startswith('DDIST_CLIENT ')]
    errors = [r for r in client if r['event'] == 'packet-error']
    require(len(errors) <= 1, 'client parser error count')
    if errors:
        require(client[0] == errors[0] and errors[0] == {'event': 'packet-error', 'state': 'play', 'packet': 'declare_recipes', 'partial': True, 'bytes': 108271}, 'unexpected client parser error')
    require(texts['client.log'].count('PartialReadError: Read error') == len(errors), 'unrecorded client parser error')
    client = [r for r in client if r['event'] != 'packet-error']
    require([r['event'] for r in client] == ['join', 'ack', 'ack', 'ack', 'ack', 'end'], 'client events/error')
    uuid = client[0]['uuid']; require(re.fullmatch('[0-9a-f-]{36}', uuid) and uuid not in uuids, 'bad player identity')
    if report['schemaVersion'] >= 2:
        bindings = marked('DDIST_PLAYER_BIND|')
        expected_bindings = {(m, flag(b)) for m, b in itertools.product(MODES[1:6], (False, True))}
        require(len(bindings) == 10 and {(r['mode'], r['beforePersistent']) for _, r in bindings} == expected_bindings, 'player binding coverage')
        for i, r in bindings:
            require(r['uuid'] == uuid and r['beforePersistent'] == r['afterPersistent'] and
                    r['beforeRemove'] == r['afterRemove'] == 'false' and r['entityValid'] == r['instanceGone'] == 'true', 'player binding changes saving/removal')
            require(i < acks[0][0], 'player binding outside isolated phase')
    for (_, server), remote, distance in zip(acks, client[1:5], (16, 64, 192, 192)):
        require(server['uuid'] == uuid and server['phase'] == remote['phase'], 'client/server ack mismatch')
        require(server['world'] == 'despawn-distance' and server['mode'] == ('SPECTATOR' if distance == 192 and server['phase'] == 'spectator' else 'CREATIVE'), 'ack context')
        for axis, value in (('x', 4096.5+distance), ('y', 110), ('z', 4096.5)):
            close(server[axis], value); close(remote['position'][axis], value)
    return 448


def main(path):
    root = Path(path); archived = root.is_file()
    report = json.loads((root if archived else root/'despawn-distance-report.json').read_text(), object_pairs_hook=unique)
    if archived:
        name = report['logArchive']; require(Path(name).name == name and '\\' not in name, 'unsafe archive')
        archive_path = root.parent/name
        require(hashlib.sha256(archive_path.read_bytes()).hexdigest() == report['logArchiveSha256'], 'archive changed')
        with zipfile.ZipFile(archive_path) as archive:
            names = archive.namelist()
            require(len(names) == len(set(names)) and set(names) == {e['path'] for e in report['files']}, 'archive members changed')
            payloads = {name: archive.read(name) for name in names}
    else:
        for name, key in (('paper.jar', 'paperJarSha256'), ('plugins/MythicMobs.jar', 'mythicMobsSha256'),
                          ('plugins/helsteraMobs.jar', 'pluginSha256'), ('plugins/HelsteraDespawnDistanceProbe.jar', 'probeJarSha256')):
            require(hashlib.sha256((root/name).read_bytes()).hexdigest() == report[key], 'artifact changed')
    texts = {}
    for entry in report['files']:
        name = entry['path']; require(name in ('server.log', 'client.log') and name not in texts, 'unsafe/duplicate member')
        data = payloads[name] if archived else (root/name).read_bytes()
        require(hashlib.sha256(data).hexdigest() == entry['sha256'], 'member changed'); texts[name] = data.decode('utf-8-sig')
    print(f'Validated {validate(report, texts)} distance observations: 416 native parity, 32 explicit-radius extension controls, 34 callbacks.')
    if report['schemaVersion'] >= 2: print('Validated 10 real-player binding controls; mob flags preserve both player saving states.')
    return report, texts

if __name__ == '__main__': main(sys.argv[1])
