"""Authenticate and audit tick-boundary observations without release promotion."""
import base64
import collections
import itertools
import json
from pathlib import Path
import re
import sys
import uuid
import zipfile

ROOT = Path(__file__).resolve().parents[2]
PAPER = '39bd8c00b9e18de91dcabd3cc3dcfa5328685a53b7187a2f63280c22e2d287b9'
MYTHIC = '9672457cafc56ff841606bfbb710947329c08720ffc207746b60130d43bf902f'
SCENARIOS = ('selfdeath', 'peerdeath', 'control', 'command', 'death', 'cancelled', 'chunk', 'suspended')
BACKENDS = ('native', 'runtime')


def require(condition, message):
    if not condition:
        raise ValueError(message)


def sha(data):
    import hashlib
    return hashlib.sha256(data).hexdigest()


def read_report(path):
    path = Path(path)
    report = json.loads(path.read_text())
    archive_name = Path(report['logArchive'])
    require(archive_name.name == report['logArchive'] and report['logArchive'].endswith('-logs.zip'),
            'unsafe archive name')
    archive_path = path.parent / archive_name
    archive_bytes = archive_path.read_bytes()
    require(re.fullmatch(r'[a-f0-9]{64}', report['logArchiveSha256']) is not None
            and sha(archive_bytes) == report['logArchiveSha256'], 'archive hash mismatch')
    with zipfile.ZipFile(archive_path) as archive:
        names = archive.namelist()
        require(len(names) == len(set(names)), 'duplicate archive path')
        payloads = {name: archive.read(name) for name in names}
    manifest = {entry['path']: entry['sha256'] for entry in report['files']}
    require(len(manifest) == len(report['files']) and set(manifest) == set(payloads), 'archive membership mismatch')
    for name, data in payloads.items():
        member = Path(name)
        require(not member.is_absolute() and '..' not in member.parts
                and re.fullmatch(r'[a-f0-9]{64}', manifest[name]) is not None
                and sha(data) == manifest[name], 'archive member hash mismatch: ' + name)
    return report, payloads


def parse_event(line, name):
    marker = 'TREAD_' + name + '|'
    value = line.split(marker, 1)[1]
    result = {}
    for item in value.split('|'):
        key, separator, field = item.partition('=')
        require(separator and key not in result, 'malformed/duplicate event field')
        result[key] = field
    return result


def decode_hook(row):
    raw = base64.b64decode(row['reads'], validate=True).decode()
    result = {}
    for item in raw.split(';'):
        key, separator, value = item.partition('=')
        require(separator and key not in result, 'malformed/duplicate hook value')
        result[key] = value
    return result


def audit(report, payloads):
    require(report['schemaVersion'] == 1 and report['evidenceMode'] == 'observation'
            and report['parityStatus'] == 'unassessed' and not report['knownExceptions'],
            'boundary evidence must remain observation-only')
    require(report['paperJarSha256'] == PAPER and report['mythicMobsSha256'] == MYTHIC,
            'wrong pinned native environment')
    required_sources = {
        'tools/paper-integration/run-terminal-read-fixture.py',
        'tools/paper-integration/run-player-variable-fixture.py',
        'tools/paper-integration/terminal-read-cases.py',
        'tools/paper-integration/terminal-boundary-cases.py',
        'tools/paper-integration/audit-terminal-boundary.py',
        'tools/paper-integration/test-terminal-boundary-audit.py',
        'tools/paper-integration/random-spawn-fixture/src/main/java/dev/helstera/test/randomspawn/TerminalReadProbe.java',
    }
    require(set(report['sourceHashes']) == required_sources, 'source manifest mismatch')
    for name, expected in report['sourceHashes'].items():
        require((ROOT / name).is_file() and sha((ROOT / name).read_bytes()) == expected,
                'source changed: ' + name)
    plugin = ROOT / 'helstera-plugin/target/helsteraMobs-1.0.0-full.jar'
    require(plugin.is_file() and sha(plugin.read_bytes()) == report['pluginSha256'],
            'fixture artifact does not match current release JAR')
    require(report['boot']['exitCode'] == 0 and 'validationResult' not in report, 'unclean fixture run')
    text = payloads['server.log'].decode()
    require('TREAD_FAILED|' not in text and 'TREAD_SUMMARY|rows=192|cases=32|completed=true' in text,
            'incomplete probe run')
    require(']: Stopping server' in text, 'server did not stop cleanly')
    require(report['observations'] == [line for line in text.splitlines() if 'TREAD_ROW|' in line],
            'report rows disagree with the archived log')
    identities = {f'{scenario}_{kind}_{path}' for scenario, kind, path in itertools.product(
        SCENARIOS, ('cow', 'zombie'), ('direct', 'imported'))}
    events = collections.defaultdict(list)
    for line in text.splitlines():
        for name in ('ID', 'ACTION', 'ROW', 'HOOK', 'UNLOAD', 'SUMMARY'):
            marker = 'TREAD_' + name + '|'
            if marker in line:
                events[name].append(parse_event(line, name))
                break
    ids = {(row['key'], row['backend']): row for row in events['ID']}
    require(len(ids) == len(events['ID']) and set(ids) == set(itertools.product(identities, BACKENDS)),
            'missing/duplicate backend identity')
    require(len({row['uuid'] for row in ids.values()}) == 64, 'duplicate parent UUID')
    for row in ids.values():
        require(str(uuid.UUID(row['uuid'])) == row['uuid'], 'invalid parent UUID')
    rows = {(row['key'], row['backend'], row['phase']): row for row in events['ROW']}
    require(len(rows) == len(events['ROW']) == 192 and set(rows) == set(itertools.product(identities, BACKENDS,
            ('initial', 'settled', 'cleaned'))), 'missing/extra lifecycle row')
    actions = {(row['key']): row for row in events['ACTION']}
    require(len(actions) == len(events['ACTION']) and set(actions) == identities, 'missing/extra terminal action')
    hooks = collections.defaultdict(list)
    for row in events['HOOK']:
        hooks[(row['key'], row['backend'], row['label'])].append(row)
    expected_callbacks = 0
    differences = []
    native_boundary_cases = 0
    for key in sorted(identities):
        scenario = key.split('_', 1)[0]
        for backend in BACKENDS:
            expected = {f'before{i}' for i in range(9)} | {'repeat', 'late'}
            if scenario in ('control', 'cancelled'):
                expected |= {f'after{i}' for i in range(9)}
            elif scenario == 'chunk' and backend == 'runtime':
                expected |= {f'after{i}' for i in range(6)}
            elif scenario in ('selfdeath', 'peerdeath', 'death', 'chunk'):
                expected |= {f'after{i}' for i in range(5)}
            if scenario in ('control', 'cancelled'):
                expected.add('pending')
            actual = {label for candidate, selected, label in hooks if candidate == key and selected == backend}
            require(actual == expected, f'{key}/{backend}: callback boundary mismatch {actual ^ expected}')
            require(len(hooks[(key, backend, 'repeat')]) == 8, f'{key}/{backend}: repeated callback count changed')
            require(all(len(hooks[(key, backend, label)]) == 1 for label in expected - {'repeat'}),
                    f'{key}/{backend}: duplicate one-shot callback')
            expected_callbacks += sum(len(hooks[(key, backend, label)]) for label in expected)
            identity = ids[(key, backend)]
            for phase in ('initial', 'settled', 'cleaned'):
                row = rows[(key, backend, phase)]
                require(row['uuid'] == identity['uuid'], 'lifecycle UUID mismatch')
                live = scenario in ('control', 'cancelled') and phase == 'settled'
                expected_marker = '73' if phase == 'initial' else '82' if live else 'absent'
                require(row['entity'] == row['registry'] == str(phase == 'initial' or live).lower()
                        and row['marker'] == expected_marker,
                        f'{key}/{backend}/{phase}: unexpected parent lifecycle state')
                expected_live_variables = '2' if backend == 'runtime' and phase == 'initial' else (
                    '1' if backend == 'runtime' and live else '0')
                require(row['liveVariables'] == expected_live_variables and row['liveLocations'] == '0',
                        f'{key}/{backend}/{phase}: live variable cleanup mismatch')
                expected_visuals = '11' if backend == 'runtime' and (
                    phase == 'initial' or live or scenario == 'peerdeath' and phase == 'settled') else '0'
                require(row['visuals'] == expected_visuals, 'visual cleanup mismatch')
                if phase == 'cleaned':
                    require(row['terminalExecutions'] == row['ownerRecords'] == row['minionRecords'] == '0',
                            'terminal state leaked after cleanup')
                    require(row['entity'] == row['registry'] == 'false', 'cleaned entity/registry reappeared')
            for label in sorted(expected):
                for hook in hooks[(key, backend, label)]:
                    require(hook['uuid'] == identity['uuid'] and hook['primary'] == 'true'
                            and hook['cleaning'] == 'false', 'callback identity/state mismatch')
                    values = decode_hook(hook)
                    if backend == 'native' and label != 'pending':
                        stamp = values.get('stamp', '')
                        match = re.fullmatch(r'(\d+),(true|false)', stamp)
                        require(match is not None, 'native placeholder evaluation stamp missing')
                        registered = match[2] == 'true'
                        require(int(match[1]) == int(hook['tick']), 'native stamp/hook tick mismatch')
                        require((hook['registry'] == 'true') == registered,
                                'native active registry flag/stamp mismatch')
                        if scenario in ('command', 'suspended'):
                            require(not registered, 'immediate removal retained native registry')
                        elif scenario in ('control', 'cancelled'):
                            require(registered, 'living/cancelled parent lost native registry')
                        else:
                            native_boundary_cases += 1
                            if label.startswith('before') and int(label[6:]) <= 4:
                                expected_registered = True
                            elif label.startswith('before'):
                                expected_registered = False
                            elif label.startswith('after'):
                                expected_registered = True
                            elif label == 'repeat':
                                expected_registered = registered
                            else:
                                expected_registered = False
                            if label != 'repeat':
                                require(registered == expected_registered, f'{key}/{label}: wrong native 5-tick boundary')
                    if backend == 'runtime' and label != 'pending':
                        require(values.get('stamp') == '<trstamp>', 'runtime executed native-only stamp placeholder')
                    if scenario == 'selfdeath' and label != 'pending':
                        if label.startswith('before') and int(label[6:]) >= 5:
                            require(values['targetMarker'] == '<target.var.Marker|absent>'
                                    and values['triggerMarker'] == '<trigger.var.Marker|absent>',
                                    'expired self registry supplied target/trigger fallback')
                    if scenario == 'peerdeath' and label != 'pending':
                        require(values['triggerMarker'] == '29', 'live independent peer trigger was lost')
                    if backend == 'native' and label.startswith('before') and label[6:].isdigit():
                        index = int(label[6:])
                        expected_marker = '<target.var.Marker|absent>' if index >= 5 and scenario not in ('control', 'cancelled') else None
                        if expected_marker:
                            require(values['targetMarker'] == expected_marker, 'removed target placeholder resolved')
    require(len(events['SUMMARY']) == 1, 'duplicate/missing completion summary')
    require(len(events['HOOK']) == expected_callbacks and expected_callbacks > 1400, 'unexpected callback total')
    require(native_boundary_cases > 0, 'no native boundary observations')
    # Suspended-removal scenarios also deliberately unload their chunks before
    # the removal command. Keep those observations, but only chunk scenarios
    # define a registry-expiry boundary.
    unloads = {}
    for row in events['UNLOAD']:
        key, backend = row['key'], row['backend']
        require(key in identities and backend in BACKENDS,
                'unload event has unknown identity')
        require((key, backend) not in unloads, 'duplicate unload event')
        require(row['uuid'] == ids[(key, backend)]['uuid'],
                'unload event parent identity mismatch')
        unloads[(key, backend)] = row
    chunk_keys = {key for key in identities if key.startswith('chunk_')}
    suspended_keys = {key for key in identities if key.startswith('suspended_')}
    expected_unloads = set(itertools.product(chunk_keys | suspended_keys, BACKENDS))
    require(set(unloads) == expected_unloads,
            'missing/extra chunk or suspension unload observation')
    for key in sorted(identities):
        scenario = key.split('_', 1)[0]
        if scenario not in ('selfdeath', 'peerdeath', 'death', 'chunk'):
            continue
        for backend in BACKENDS:
            if scenario == 'chunk':
                start = int(unloads[(key, backend)]['tick'])
                require(unloads[(key, backend)]['uuid'] == ids[(key, backend)]['uuid'],
                        'chunk unload parent identity mismatch')
                registry_end = start + 6
            else:
                start = int(actions[key]['tick'])
                registry_end = start + 5
            for label in (f'before{i}' for i in range(9)):
                hook = hooks[(key, backend, label)][0]
                tick = int(hook['tick'])
                values = decode_hook(hook)
                expected_available = tick < registry_end
                if backend == 'native':
                    actual_available = values['stamp'].endswith(',true')
                else:
                    actual_available = values['targetMarker'] != '<target.var.Marker|absent>'
                require(actual_available == expected_available,
                        f'{key}/{backend}/{label}: wrong removal tick boundary')
    for key in sorted(identities):
        scenario = key.split('_', 1)[0]
        if scenario not in ('selfdeath', 'peerdeath', 'death', 'chunk'):
            continue
        for backend in BACKENDS:
            before_five = hooks[(key, backend, 'before5')][0]
            expired_label = 'before6' if scenario == 'chunk' and backend == 'runtime' else 'before5'
            expired = hooks[(key, backend, expired_label)][0]
            require(decode_hook(expired)['targetMarker'] == '<target.var.Marker|absent>',
                    f'{key}/{backend}: target registry remained readable at expiry boundary')
            after_four = hooks[(key, backend, 'after4')]
            last_available = before_five if scenario == 'chunk' and backend == 'runtime' else after_four[0]
            require((scenario == 'chunk' and backend == 'runtime' or len(after_four) == 1)
                    and decode_hook(last_available)['targetMarker'] !=
                    '<target.var.Marker|absent>', f'{key}/{backend}: target registry expired before tick +5')
            require(len(hooks[(key, backend, 'repeat')]) == 8,
                    f'{key}/{backend}: independent repeated callbacks were cancelled')
            late = hooks[(key, backend, 'late')][0]
            require(decode_hook(late)['targetMarker'] == '<target.var.Marker|absent>',
                    f'{key}/{backend}: terminal snapshot leaked to a late target read')
    # Evaluation order remains an explicit observation, not a parity assertion.
    for key in sorted(identities):
        first = decode_hook(hooks[(key, 'native', 'before0')][0])
        runtime = decode_hook(hooks[(key, 'runtime', 'before0')][0])
        if first.get('casterMarker') != runtime.get('casterMarker'):
            differences.append(dict(case=key, label='before0', field='casterMarker',
                                    native=first.get('casterMarker'), runtime=runtime.get('casterMarker')))
    return dict(evidenceMode='observation', parityStatus='unassessed', cases=len(identities),
                identities=len(ids), rows=len(rows), callbacks=len(events['HOOK']),
                nativeBoundarySamples=native_boundary_cases, initialEvaluationDifferences=differences)


def main(path):
    report, payloads = read_report(path)
    result = audit(report, payloads)
    print(json.dumps(result, indent=2))


if __name__ == '__main__':
    main(sys.argv[1])
