"""Reject incomplete player lifecycle evidence, including absent/premature disk writes."""
import hashlib
import itertools
import json
import pathlib
import re
import sys
import uuid
import zipfile
import yaml

NAMES = ('VariableOwner', 'VariablePeer')
CASES = ('Default', 'Save', 'Transient', 'ZeroSave', 'ZeroTransient', 'Negative', 'TimedSave',
         'TimedTransient', 'Expired', 'ReplaceTransient', 'ReplaceSaved', 'AddSave', 'AddTransient',
         'AddRefresh', 'Float', 'Double', 'String', 'Location')
TRANSIENT = {'Default', 'Transient', 'ZeroTransient', 'ReplaceTransient', 'AddSave'}
TIMED = {'TimedSave', 'TimedTransient', 'Expired', 'AddRefresh'}
PHASES = {'write': ('write', 'reconnect'), 'verify': ('verify', 'cleared'), 'final': ('final',)}


def require(condition, message):
    if not condition: raise ValueError(message)


def unique_pairs(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result, f'duplicate key: {key}')
        result[key] = value
    return result


class UniqueYaml(yaml.SafeLoader): pass


def yaml_mapping(loader, node):
    return unique_pairs((loader.construct_object(k), loader.construct_object(v)) for k, v in node.value)


UniqueYaml.add_constructor(yaml.resolver.BaseResolver.DEFAULT_MAPPING_TAG, yaml_mapping)


def fields(line, marker):
    return unique_pairs(pair.split('=', 1) for pair in line.split(marker, 1)[1].split('|'))


def offline_id(name):
    return str(uuid.UUID(bytes=hashlib.md5(('OfflinePlayer:' + name).encode()).digest(), version=3))


def runtime_registry(text, identity):
    doc = yaml.load(text, Loader=UniqueYaml)
    require(doc['registry-format'] == 1 and len(doc['registries']) == 1, 'missing player registry')
    registry = next(iter(doc['registries'].values()))
    require(registry['scope'] == 'player:' + identity, 'wrong player scope')
    snapshot = registry['snapshot']
    require(snapshot['snapshot-format'] == 4, 'wrong typed snapshot format')
    entries = {}
    for kind in ('variables', 'locations'):
        retention = unique_pairs((v['name'], v['expires-at']) for v in registry['variable-retention' if kind == 'variables' else 'location-retention'].values())
        items = snapshot[kind]['entries']
        for item in items.values():
            name = item['name']; require(name not in entries, 'duplicate variable name')
            value = item['value'] if kind == 'variables' else {'type': 'LOCATION', 'value': {k: item[k] for k in ('world', 'x', 'y', 'z', 'yaw', 'pitch')}}
            entries[name] = {'type': value['type'], 'value': value['value'], 'deadline': retention.get(name, 0)}
        require(set(retention) <= {item['name'] for item in items.values()}, 'orphan retention')
    return entries


def validate(report, texts):
    require(report['schemaVersion'] == 1, 'wrong evidence schema')
    require(report['evidenceMode'] == 'comparison', 'observation-only evidence')
    require(report['paperJarSha256'] == '39bd8c00b9e18de91dcabd3cc3dcfa5328685a53b7187a2f63280c22e2d287b9', 'wrong Paper')
    require(report['mythicMobsSha256'] == '9672457cafc56ff841606bfbb710947329c08720ffc207746b60130d43bf902f', 'wrong native oracle')
    for field in ('pluginSha256', 'probeJarSha256'):
        require(re.fullmatch('[0-9a-f]{64}', report[field]) is not None, 'invalid artifact hash')
    ids = {name: offline_id(name) for name in NAMES}
    require(len(report['stages']) == 3, 'missing boot')
    written = {}
    keys = set(itertools.product(range(2), ('caster', 'target'), CASES, ('direct', 'imported')))
    for stage, entry in zip(PHASES, report['stages']):
        require(entry['stage'] == stage and entry['passed'], 'wrong stage')
        text = texts[f'{stage}.server.log']
        require('PLAYER_VAR_SUMMARY|passed=false' not in text, 'failed lifecycle')
        rows = [line for line in text.splitlines() if 'PLAYER_VAR|' in line or 'PLAYER_VAR_HOOK|' in line]
        require(rows == entry['observations'] and len(rows) == 148 * len(PHASES[stage]), 'incomplete observations')
        for phase in PHASES[stage]:
            observations = [fields(row, 'PLAYER_VAR|') for row in rows if 'PLAYER_VAR|' in row and f'|phase={phase}|' in row]
            require(len(observations) == 144, 'missing variable row')
            seen = set()
            for row in observations:
                key = int(row['actor']), row['scope'], row['case'], row['path']
                require(key in keys and key not in seen, 'duplicate or unknown row'); seen.add(key)
                actor, scope, kind, path = key
                require(row['uuid'] == ids[NAMES[actor if scope == 'caster' else 1 - actor]], 'wrong player identity')
                present = phase == 'write' or phase in ('reconnect', 'verify') and kind != 'Expired'
                require(all(row[field] == str(present).lower() for field in ('expected', 'native', 'runtime')), 'incorrect retention')
                require(row['isolated'] == row['matched'] == 'true', 'runtime mismatch or cross-player leak')
                native, runtime = int(row['nativeDeadline']), int(row['runtimeDeadline'])
                if phase == 'write':
                    if kind in TIMED: require(native > 1_000_000_000_000 and abs(native - runtime) < 1000, 'invalid deadline')
                    else: require(native == runtime == (1 if kind in TRANSIENT else -50 if kind == 'Negative' else 0), 'invalid retention marker')
                    written[key] = native, runtime
                elif present: require((native, runtime) == written[key], 'deadline reset')
                else: require(native == runtime == 0, 'expired data retained metadata')
            hooks = [fields(row, 'PLAYER_VAR_HOOK|') for row in rows if 'PLAYER_VAR_HOOK|' in row and f'|phase={phase}|' in row]
            require(len(hooks) == 4 and {(r['name'], r['path']) for r in hooks} == set(itertools.product(NAMES, ('direct', 'imported'))), 'missing quit hook control')
            for row in hooks:
                expected = 30 if phase == 'final' and row['name'] == 'VariablePeer' else ('write', 'reconnect', 'verify', 'cleared', 'final').index(phase)
                require(all(row[k] == str(expected) for k in ('expected', 'native', 'runtime')) and row['passed'] == 'true', 'lost quit/shutdown mutation')
            require(text.count(f'PLAYER_VAR_PHASE|stage={stage}|phase={phase}|observations=148|passed=true') == 1, 'phase not complete')
        require(text.count(f'PLAYER_VAR_SUMMARY|stage={stage}|observations={148 * len(PHASES[stage])}|passed=true') == 1, 'boot not complete')
        sessions = [fields(line, 'PLAYER_VAR_SESSION|') for line in text.splitlines() if 'PLAYER_VAR_SESSION|' in line]
        expected_sessions = {(event, name, str(i)) for event in ('join', 'quit') for name in NAMES for i in range(1, len(PHASES[stage]) + 1)}
        if stage == 'verify': expected_sessions.remove(('quit', 'VariablePeer', '2'))
        require(len(sessions) == len(expected_sessions) and {(s['event'], s['name'], s['join']) for s in sessions} == expected_sessions, 'missing actual join/quit events')
        require(all(s['uuid'] == ids[s['name']] and s['stage'] == stage for s in sessions), 'session identity changed')
        unloaded = [fields(line, 'PLAYER_VAR_UNLOADED|') for line in text.splitlines() if 'PLAYER_VAR_UNLOADED|' in line]
        quits = [s for s in sessions if s['event'] == 'quit']
        require(len(unloaded) == len(quits) and all(s['native'] == s['runtime'] == s['passed'] == 'true' for s in unloaded), 'offline registry not released')
        require(sorted(r['uuid'] for r in unloaded) == sorted(r['uuid'] for r in quits), 'unload identity differs')
        events = [json.loads(line.split('PLAYER_VAR_CLIENT ', 1)[1]) for line in texts[f'{stage}.client.log'].splitlines() if 'PLAYER_VAR_CLIENT ' in line]
        require(sum(e['event'] == 'summary' and e['passed'] for e in events) == 1 and all(e['stage'] == stage for e in events), 'client did not finish')
        logins = [e for e in events if e['event'] == 'join']
        require(len(logins) == 2 * len(PHASES[stage]) and {(e['name'], str(e['attempt'])) for e in logins} == {(s['name'], s['join']) for s in sessions if s['event'] == 'join'}, 'missing client connection')
        require(all(e['uuid'] == ids[e['name']] for e in logins), 'client UUID differs')
        require(sum(e['event'] == 'ready-online' for e in events) == (1 if stage == 'verify' else 0), 'missing online shutdown client')
        writes = [fields(line, 'PLAYER_VAR_QUIT_WRITE|') for line in text.splitlines() if 'PLAYER_VAR_QUIT_WRITE|' in line]
        expected_writes = {(s['name'], path, str(int(s['join']) + (0 if stage == 'write' else 2 if stage == 'verify' else 4 if s['name'] == 'VariableOwner' else 30)))
                           for s in quits for path in ('direct', 'imported')}
        require(len(writes) == len(expected_writes) and {(w['name'], w['path'], w['value']) for w in writes} == expected_writes
                and all(w['stage'] == stage and w['passed'] == 'true' for w in writes), 'quit writes missing or incorrect')
        if stage == 'verify':
            online = [fields(line, 'PLAYER_VAR_ONLINE_WRITE|') for line in text.splitlines() if 'PLAYER_VAR_ONLINE_WRITE|' in line]
            require(len(online) == 2 and {o['path'] for o in online} == {'direct', 'imported'} and all(o['value'] == '30' and o['passed'] == 'true' for o in online), 'online mutation missing')
        for name in NAMES:
            identity = ids[name]
            for shutdown in (False, True):
                prefix = f'{stage}-logout/' + ('shutdown-' if shutdown else '')
                native_doc = json.loads(texts[prefix + 'native-' + identity + '.json'], object_pairs_hook=unique_pairs)
                require(native_doc['uniqueId'] == identity and native_doc['name'] == name, 'disk identity differs')
                native = native_doc['variables']['entries']
                runtime = runtime_registry(texts[prefix + 'runtime-' + identity + '.yml'], identity)
                expected_names = {'QuitHookD', 'QuitHookI'}
                if stage == 'write':
                    expected_names |= {'Player' + ('D' if path == 'direct' else 'I') + str(actor) + scope + kind
                                       for actor, scope, kind, path in keys if kind != 'Expired' and ids[NAMES[actor if scope == 'caster' else 1 - actor]] == identity}
                require(set(native) == set(runtime) == expected_names, 'disk lost values or retained deleted/expired values')
                for variable in expected_names:
                    n, r = native[variable], runtime[variable]
                    expected_type = next((kind.upper() for kind in ('Float', 'Double', 'String', 'Location') if variable.endswith(kind)), 'INTEGER')
                    require(n['type'].upper() == r['type'] == expected_type, 'disk type changed')
                    if variable.startswith('QuitHook'):
                        expected = 2 if stage == 'write' else (5 if name == 'VariableOwner' else 31) if stage == 'final' else 4 if name == 'VariableOwner' else 30 if shutdown else 3
                        require(n['value'] == r['value'] == expected and n['expirationTime'] == r['deadline'] == 0, 'disk lost final lifecycle write')
                        continue
                    actor = int(variable[7]); scope = 'caster' if variable[8:].startswith('caster') else 'target'; kind = variable[8 + len(scope):]
                    path = 'direct' if variable[6] == 'D' else 'imported'
                    require((n['expirationTime'], r['deadline']) == written[(actor, scope, kind, path)], 'disk deadline reset')
                    if kind == 'Location':
                        point = dict(n['value']); point['world'] = point['world']['name']
                        require(point == r['value'], 'disk location changed')
                    else:
                        expected = 3.25 + actor if kind in ('Float', 'Double') else 'saved-' + str(actor) if kind == 'String' else (9 if kind.startswith(('Add', 'Replace')) else 7) + actor * 10
                        require(n['value'] == r['value'] == expected, 'disk scalar changed')
        shared = yaml.load(texts[f'{stage}-logout/scoped-variables.yml'], Loader=UniqueYaml)
        require(not any(r['scope'].startswith('player:') for r in shared['registries'].values()), 'shared snapshot duplicates player state')
    return 740


def main(directory):
    root = pathlib.Path(directory)
    archived = root.is_file()
    report = json.loads((root if archived else root / 'player-variable-report.json').read_text(), object_pairs_hook=unique_pairs)
    if archived:
        archive_name = report['logArchive']
        require(pathlib.PurePosixPath(archive_name).name == archive_name and '\\' not in archive_name, 'unsafe archive path')
        archive_path = root.parent / archive_name
        require(hashlib.sha256(archive_path.read_bytes()).hexdigest() == report['logArchiveSha256'], 'archive hash mismatch')
        with zipfile.ZipFile(archive_path) as archive:
            names = archive.namelist()
            require(len(names) == len(set(names)) and set(names) == {e['path'] for e in report['files']}, 'archive members differ')
            payloads = {name: archive.read(name) for name in names}
    else:
        for path, field in (('paper.jar', 'paperJarSha256'), ('plugins/MythicMobs.jar', 'mythicMobsSha256'),
                            ('plugins/helsteraMobs.jar', 'pluginSha256'), ('plugins/HelsteraPlayerVariableProbe.jar', 'probeJarSha256')):
            require(hashlib.sha256((root / path).read_bytes()).hexdigest() == report[field], 'artifact hash mismatch')
    texts = {}
    for entry in report['files']:
        path = pathlib.PurePosixPath(entry['path']); require(not path.is_absolute() and '..' not in path.parts, 'unsafe evidence path')
        require(entry['path'] not in texts, 'duplicate evidence file')
        data = payloads[entry['path']] if archived else (root / entry['path']).read_bytes()
        require(hashlib.sha256(data).hexdigest() == entry['sha256'], 'evidence hash mismatch')
        texts[entry['path']] = data.decode('utf-8-sig')
    print(f'Validated {validate(report, texts)} player observations, logout snapshots and online shutdown writes.')
    return report, texts


if __name__ == '__main__': main(sys.argv[1])
