"""Independent semantic audit of ordinary player location-value observations."""
import argparse
from collections import Counter
import hashlib
import json
import math
from pathlib import Path
import re
import uuid
import zipfile

import yaml

ROOT = Path(__file__).resolve().parents[2]
SCOPES = ['skill', 'caster', 'target', 'world', 'global']
REGISTRIES = ['skill', 'caster', 'target', 'trigger', 'aimed', 'peer', 'world', 'other-world', 'global']
PATHS = ['native-direct', 'imported']
DISPATCHES = ['entity', 'location']
# arguments, normalized selector, expected role, wall, peer mode, aimed miss, turn, deadline
CASES = {
    'entity-hit': ('value=@target', '@target', 'aimed', 'AIR', 'off', False, False, 1),
    'entity-short': ('value=@T', '@target', 'aimed', 'AIR', 'off', False, False, 1),
    'entity-miss': ('value=@target', '@target', 'origin', 'AIR', 'off', True, False, 1),
    'entity-stone': ('value=@target', '@target', 'origin', 'STONE', 'off', False, False, 1),
    'entity-glass': ('value=@target', '@target', 'origin', 'GLASS', 'off', False, False, 1),
    'entity-barrier': ('value=@target', '@target', 'aimed', 'BARRIER', 'off', False, False, 1),
    'entity-creative': ('value=@target', '@target', 'aimed', 'AIR', 'CREATIVE', False, False, 1),
    'entity-player': ('value=@target', '@target', 'peer', 'AIR', 'SURVIVAL', False, False, 1),
    'location-stone': ('value=@targetlocation', '@targetlocation', 'near-hit', 'STONE', 'off', False, False, 1),
    'location-empty': ('value=@targetlocation', '@targetlocation', 'endpoint', 'AIR', 'off', False, False, 1),
    'location-glass': ('value=@targetlocation', '@targetlocation', 'far-hit', 'GLASS', 'off', False, False, 1),
    'location-barrier': ('value=@targetLoc', '@targetlocation', 'far-hit', 'BARRIER', 'off', False, False, 1),
    'location-short': ('value=@TL', '@targetlocation', 'endpoint', 'AIR', 'off', False, False, 1),
    'location-turned': ('value=@targetlocation', '@targetlocation', 'turned-endpoint', 'AIR', 'off', False, True, 1),
    'location-saved': ('value=@targetlocation;save=true', '@targetlocation', 'near-hit', 'STONE', 'off', False, False, 0),
}
SOURCE_FILES = {
    'tools/paper-integration/run-variable-location-player-fixture.py',
    'tools/paper-integration/run-player-variable-fixture.py',
    'tools/paper-integration/audit-variable-location-player.py',
    'tools/paper-integration/test-variable-location-player-audit.py',
    'tools/paper-integration/variable-location-player-client.js',
    'tools/paper-integration/random-spawn-fixture/src/main/java/dev/helstera/test/randomspawn/VariableLocationPlayerProbe.java',
    'tools/paper-integration/random-spawn-fixture/src/main/java/dev/helstera/test/randomspawn/VariablePlaceholderAudit.java',
    'helstera-migration/src/main/java/dev/helstera/migration/importer/MythicMobsImporter.java',
    'helstera-migration/src/main/java/dev/helstera/migration/importer/MythicSpec.java',
    'helstera-ai/src/main/java/dev/helstera/ai/skill/MythicSkillSpec.java',
    'helstera-ai/src/main/java/dev/helstera/ai/skill/SkillService.java',
    'helstera-ai/src/main/java/dev/helstera/ai/skill/SkillExtras.java',
    'helstera-ai/src/main/java/dev/helstera/ai/skill/MythicPlayerTargeting.java',
    'helstera-api/src/main/java/dev/helstera/api/util/MythicVariableReference.java',
    'helstera-api/src/main/java/dev/helstera/api/util/MythicVariableLocation.java',
    'helstera-api/src/main/java/dev/helstera/api/util/VariableLifetime.java',
    'helstera-api/src/main/java/dev/helstera/api/behavior/VariableLocations.java',
}


def require(value, message):
    if not value:
        raise ValueError(message)


def digest(value):
    return hashlib.sha256(value).hexdigest()


def position(value):
    require(isinstance(value, str) and value.startswith('Location:'), 'location encoding')
    parts = value[9:].split(',')
    require(len(parts) == 6 and parts[0] == 'integration-world', 'location world')
    result = tuple(float(v) for v in parts[1:])
    require(all(math.isfinite(v) for v in result), 'finite position')
    return result


def same_position(value, expected, label):
    actual = position(value)
    require(all(abs(a - b) < 1e-7 for a, b in zip(actual, expected)), label)


def offline_id(name):
    return str(uuid.UUID(bytes=hashlib.md5(('OfflinePlayer:' + name).encode()).digest(), version=3))


def audit(report, files):
    require(type(report.get('schemaVersion')) is int and report['schemaVersion'] == 1, 'schema version')
    require(report.get('evidenceMode') == 'observation' and report.get('parityStatus') == 'unassessed', 'evidence boundary')
    require(report.get('validationResult') == 'passed' and report.get('exitCode') == report.get('clientExitCode') == 0, 'fixture completion')
    require(report.get('paperJarSha256') == '39bd8c00b9e18de91dcabd3cc3dcfa5328685a53b7187a2f63280c22e2d287b9', 'Paper pin')
    require(report.get('mythicMobsSha256') == '9672457cafc56ff841606bfbb710947329c08720ffc207746b60130d43bf902f', 'native pin')
    for key in ('pluginSha256', 'probeJarSha256'):
        require(re.fullmatch('[0-9a-f]{64}', report.get(key, '')), key)
    require(set(report.get('sourceHashes', {})) == SOURCE_FILES, 'source manifest')
    require(all(re.fullmatch('[0-9a-f]{64}', value) for value in report['sourceHashes'].values()), 'source digest')
    prefix = 'plugins/HelsteraVariableLocationPlayerProbe/'
    identity_paths = {prefix + 'identities-' + path + '.yml' for path in PATHS}
    observation_paths = {prefix + 'observations/' + f'{i:04d}.yml' for i in range(300)}
    expected_files = identity_paths | observation_paths | {'server.log', 'client.log', 'plugins/MythicMobs/Mobs/location.yml'}
    require(set(files) == expected_files and len(files) == len(report['files']) == 305, 'archive paths/cardinality')
    require({item['path'] for item in report['files']} == set(files), 'file manifest')
    for item in report['files']:
        require(digest(files[item['path']]) == item['sha256'], 'file digest: ' + item['path'])
    ids = []
    for name in identity_paths:
        values = yaml.safe_load(files[name])
        require(set(values) == {'caster', 'peer'} | {'entity-' + str(i) for i in range(5)}, 'identity roles')
        require(values['caster'] == offline_id('LocationCaster') and values['peer'] == offline_id('LocationPeer'), 'shared real player identities')
        ids.extend(str(uuid.UUID(values['entity-' + str(i)])) for i in range(5))
    require(len(ids) == len(set(ids)) == 10 and not set(ids) & {offline_id('LocationCaster'), offline_id('LocationPeer')}, 'distinct mobs')
    clients = [json.loads(line.split('VLOCATION_PLAYER_CLIENT ', 1)[1]) for line in files['client.log'].decode().splitlines() if 'VLOCATION_PLAYER_CLIENT ' in line]
    require(Counter(item.get('event') for item in clients) == {'join': 2, 'done': 2, 'summary': 1}, 'client event coverage')
    for name in ('LocationCaster', 'LocationPeer'):
        require(sum(item.get('event') == 'join' and item.get('name') == name and item.get('uuid') == offline_id(name) for item in clients) == 1, 'real player join')
        require(sum(item.get('event') == 'done' and item.get('name') == name for item in clients) == 1, 'real player completion')
    require(next(item for item in clients if item['event'] == 'summary').get('passed') is True, 'client passed')
    rows = [yaml.safe_load(files[name]) for name in sorted(observation_paths)]
    expected = {(path, dispatch, scope, case) for path in PATHS for dispatch in DISPATCHES for scope in SCOPES for case in CASES}
    require(len(rows) == report.get('observationCount') == len(expected) == 300, 'observation count')
    log = files['server.log'].decode()
    require(log.count('VLOCATION_PLAYER_SUMMARY|observations=300|passed=true') == 1 and 'VLOCATION_PLAYER_FAILED|' not in log, 'completion marker')
    require(log.count('Failed to get variable registry') == 30, 'unavailable registry diagnostics')
    logs = Counter(re.findall(r'VLOCATION_PLAYER_ROW\|case=([^|\r\n]+)\|scope=([^|\r\n]+)\|dispatch=([^|\r\n]+)\|path=([^|\r\n]+)\|matched=true', log))
    seen, results, roles = set(), Counter(), Counter()
    for index, row in enumerate(rows):
        path, dispatch, scope, case, key = (row[k] for k in ('path', 'dispatch', 'scope', 'case', 'key'))
        identity = (path, dispatch, scope, case)
        require(identity in expected and identity not in seen, 'case coverage')
        seen.add(identity)
        require(key == 'LocationPlayerAudit' + str(index), 'observation key')
        require(logs[(case, scope, dispatch, path)] == 1 and row['matched'] is True, 'log correspondence')
        arguments, canonical, role, wall, mode, miss, turn, deadline = CASES[case]
        require(row['selected'] == canonical and row['expectedRole'] == role, 'value selector/role')
        require(row['wall'] == wall and row['rearWall'] == ('STONE' if role == 'far-hit' else 'AIR'), 'block setup')
        require(row['peerMode'] == ('SURVIVAL' if mode == 'SURVIVAL' else 'CREATIVE'), 'candidate player mode')
        caster = (300.5, 170, 300.5, 90 if turn else 0, 0)
        same_position(row['casterBeforeBinding'], (300.5, 170, 300.5, 0, 0), 'aim before binding')
        same_position(row['casterAtDispatch'], caster, 'aim at dispatch')
        same_position(row['eyeAtDispatch'], (300.5, 171.62, 300.5, caster[3], 0), 'eye at dispatch')
        positions = {'caster': caster, 'recipient': (310, 170, 300, 70, 15), 'trigger': (290, 170, 300, 120, 25),
                     'aimed': (320.5 if miss else 300.5, 170, 312.5, 30, 20),
                     'peer': (320.5 if mode == 'off' else 300.5, 170, 308.5, 135, 15),
                     'origin': (330, 174, 320, 160, 35), 'outer': (340, 178, 326, 210, 45)}
        require(set(row['positions']) == set(positions), 'position roles')
        for label, value in positions.items():
            same_position(row['positions'][label], value, 'distinct role: ' + label)
        eye_y = position(row['eyeAtDispatch'])[1]
        point = positions.get(role)
        if point is None:
            point = {'near-hit': (300.5, eye_y, 305, 0, 0), 'far-hit': (300.5, eye_y, 315, 0, 0),
                     'endpoint': (300.5, eye_y, 364.5, 0, 0), 'turned-endpoint': (236.5, eye_y, 300.5, 0, 0)}[role]
        before = {candidate + '/' + key: 'Integer:' + str(10 + i) for i, candidate in enumerate(REGISTRIES)}
        deadlines = {field: 1 for field in before}
        require(row['nativeBefore'] == row['runtimeBefore'] == before, 'seed values')
        require(row['nativeBeforeDeadlines'] == row['runtimeBeforeDeadlines'] == deadlines, 'seed deadlines')
        source = 'setvariablelocation{var=' + scope + '.' + key + ';' + arguments + '}'
        require(row['source'] == source, 'source case')
        mapped = source if path == 'native-direct' else 'variable-location-set var=' + scope + '.' + key + ' value=' + canonical + (' save=true' if deadline == 0 else '')
        require(row['mapped'] == row['bindingSource'] == mapped and row['bindingDiagnostics'] == [], 'exact binding source')
        writes = not (scope == 'target' and dispatch == 'location')
        result = 'SUCCESS' if writes else 'INVALID_CONFIG'
        require(row['nativeResult'] == result and row['runtimeResult'] == 'dispatched', 'dispatch outcomes')
        results[result] += 1
        field = scope + '/' + key
        if writes:
            roles[role] += 1
            deadlines[field] = deadline
        for kind in ('nativeStoredAfter', 'nativeAfter', 'runtimeAfter'):
            values = dict(row[kind])
            if writes:
                require(field in values, 'missing write')
                same_position(values.pop(field), point, 'stored player target point')
                untouched = {k: v for k, v in before.items() if k != field}
            else:
                untouched = before
            require(values == untouched, 'untouched registry keys')
            require(row[kind + 'Deadlines'] == deadlines and all(type(v) is int for v in row[kind + 'Deadlines'].values()), 'retained deadlines')
        require(row['nativeAfter'] == row['runtimeAfter'], 'native/runtime values')
    require(seen == expected and sum(logs.values()) == 300, 'complete coverage')
    require(results == {'SUCCESS': 270, 'INVALID_CONFIG': 30}, 'result coverage')
    require(roles == {'aimed': 72, 'origin': 54, 'peer': 18, 'near-hit': 36, 'endpoint': 36, 'far-hit': 36, 'turned-endpoint': 18}, 'geometric case coverage')
    return len(rows)


def load(path):
    report = json.loads(path.read_text())
    archive = path.parent / report['logArchive']
    require(digest(archive.read_bytes()) == report['logArchiveSha256'], 'archive digest')
    with zipfile.ZipFile(archive) as source:
        require(len(source.namelist()) == len(set(source.namelist())), 'duplicate archive members')
        files = {name: source.read(name) for name in source.namelist()}
    return report, files


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('report', type=Path)
    parser.add_argument('--check-current', action='store_true')
    args = parser.parse_args()
    report, files = load(args.report)
    count = audit(report, files)
    if args.check_current:
        for name, expected in report['sourceHashes'].items():
            require(digest((ROOT / name).read_bytes()) == expected, 'current source: ' + name)
        require(digest((ROOT / 'helstera-plugin/target/helsteraMobs-1.0.0-full.jar').read_bytes()) == report['pluginSha256'], 'current plugin')
    print(f'Variable location player audit passed: {count} observations; 270 successful writes, 30 unavailable registries; observation-only')
