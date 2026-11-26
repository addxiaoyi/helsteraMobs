"""Validate the explicit Helstera model action contract, never native same-name parity."""
from collections import Counter
from datetime import datetime, timedelta
import hashlib
import importlib.util
import io
import json
from pathlib import Path, PurePosixPath
import re
import sys
import uuid
import zipfile
import yaml

sys.dont_write_bytecode = True


def module(name, filename):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(filename))
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


c = module('actions_contract', 'model-actions-cases.py')
base = module('actions_base', 'validate-player-variables.py')
require, fields, unique = base.require, base.fields, base.unique_pairs
FILES = {'server.log', 'source-mobs.yml', 'source-skills.yml', 'source-integrations.yml', 'reloaded-skills.yml'}
SOURCES = {'run-model-actions-fixture.py', 'run-player-variable-fixture.py', 'model-actions-cases.py',
           'validate-model-actions.py', 'validate-player-variables.py', 'test-model-actions-evidence.py',
           'random-spawn-fixture/src/main/java/dev/helstera/test/randomspawn/ModelActionsProbe.java'}


def digest(data):
    return hashlib.sha256(data).hexdigest()


def hash_value(value):
    return isinstance(value, str) and re.fullmatch('[a-f0-9]{64}', value) is not None


def safe_path(value):
    require(isinstance(value, str) and value and '\\' not in value and ':' not in value, 'unsafe path')
    path = PurePosixPath(value)
    require(not path.is_absolute() and '..' not in path.parts and str(path) == value, 'unsafe path')
    return value


def manifest(report):
    result = {}
    for item in report['files']:
        require(set(item) == {'path', 'sha256'}, 'invalid file entry')
        name = safe_path(item['path'])
        require(name not in result and hash_value(item['sha256']), 'duplicate member or invalid hash')
        result[name] = item['sha256']
    require(set(result) == FILES, 'missing/orphan manifest member')
    return result


def verify_payloads(report, payloads):
    entries = manifest(report)
    require(set(payloads) == set(entries), 'missing/orphan payload')
    for name, value in entries.items():
        require(digest(payloads[name]) == value, 'member changed: ' + name)


def archive_payloads(report, data):
    require(hash_value(report['logArchiveSha256']) and digest(data) == report['logArchiveSha256'], 'archive changed')
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        names = archive.namelist()
        require(len(names) == len(set(names)) and set(names) == set(manifest(report)), 'archive membership changed')
        payloads = {name: archive.read(name) for name in names}
    verify_payloads(report, payloads)
    return payloads


def doc(data):
    return yaml.load(data, Loader=base.UniqueYaml)


def utc(text):
    result = datetime.fromisoformat(text)
    require(result.utcoffset() == timedelta(0), 'non-UTC timestamp')
    return result


def native_loaded_line(line):
    # Pinned MM 5.7.2 encodes the literal dash before firing MythicMechanicLoadEvent.
    return line.replace('-', '<&da>')


def validate(report, payloads):
    require(type(report['schemaVersion']) is int and report['schemaVersion'] == 1
            and report['evidenceMode'] == 'scoped-runtime', 'observer or wrong schema rejected')
    require(report['extensionContract'] == 'helstera-defined' and report['nativeParity'] is False,
            'custom extension scope misrepresented')
    require('validationResult' not in report, 'rejected evidence')
    require(report['paperJarSha256'] == '39bd8c00b9e18de91dcabd3cc3dcfa5328685a53b7187a2f63280c22e2d287b9', 'wrong Paper')
    require(report['mythicMobsSha256'] == '9672457cafc56ff841606bfbb710947329c08720ffc207746b60130d43bf902f', 'wrong native artifact')
    require(all(hash_value(report[key]) for key in ('pluginSha256', 'probeJarSha256')), 'invalid artifact hash')
    require(set(report['sourceHashes']) == {'tools/paper-integration/' + name for name in SOURCES}
            and all(hash_value(value) for value in report['sourceHashes'].values()), 'source identity incomplete')
    boot = report['boot']
    require(type(boot['exitCode']) is int and boot['exitCode'] == 0, 'unclean shutdown')
    require(utc(report['generatedAtUtc']) <= utc(boot['startedAtUtc']) < utc(boot['stoppedAtUtc']), 'invalid boot window')
    require(re.fullmatch('srv-paper-model-actions-[0-9]{8}-r[1-9][0-9]*', report['fixtureDirectory']) is not None,
            'invalid fixture')
    verify_payloads(report, payloads)
    require(doc(payloads['source-mobs.yml']) == c.native_mobs(), 'native mobs changed')
    require(doc(payloads['source-skills.yml']) == c.native_skills(), 'native skills changed')
    require(doc(payloads['reloaded-skills.yml']) == c.native_skills('3'), 'missing reload stimulus')
    require(doc(payloads['source-integrations.yml']) == {'mythicmobs': {'mobs': {}}}, 'unexpected automatic binding')
    lines = payloads['server.log'].decode().splitlines()
    require(not any('ACTIONS_FAILED|' in line for line in lines), 'probe failed')
    raw = [line for line in lines if 'ACTIONS_ROW|' in line]
    require(raw == report['observations'], 'raw observation mismatch')
    identity_lines = [(index, fields(line, 'ACTIONS_IDENTITIES|')) for index, line in enumerate(lines)
                      if 'ACTIONS_IDENTITIES|' in line]
    require(len(identity_lines) == 1, 'missing/extra identities')
    identities = identity_lines[0][1]
    require(set(identities) == {'caster', 'target', 'other', 'vehicle', 'dead', 'nonDamageable'}, 'wrong identities')
    for value in identities.values():
        uuid.UUID(value)
    require(len(set(identities.values())) == len(identities), 'identity collision')
    rows, directs, indexes, direct_indexes, loads, cancels = {}, {}, {}, {}, [], []
    last_tick = -1
    for index, line in enumerate(lines):
        if 'ACTIONS_LOAD|' in line:
            load = fields(line, 'ACTIONS_LOAD|')
            require(set(load) == {'name', 'line', 'registered'} and load['registered'] == 'true', 'unregistered native definition')
            loads.append((index, load))
        if 'ACTIONS_CANCEL|' in line:
            cancelled = fields(line, 'ACTIONS_CANCEL|')
            require(set(cancelled) == {'kind', 'caster', 'target'} and cancelled['caster'] == identities['caster']
                    and cancelled['target'] == identities['target'], 'wrong cancellation identity')
            cancels.append((index, cancelled['kind']))
        if 'ACTIONS_ROW|' in line:
            row = fields(line, 'ACTIONS_ROW|'); key = row['key']
            require(key in c.EXPECTED and key not in rows, 'unknown/duplicate state row')
            rows[key] = row; indexes[key] = index
            expected = c.EXPECTED[key]
            require(set(row) == set(expected) | {'key', 'tick', 'caster'}, 'wrong row fields: ' + key)
            require(row['caster'] == identities['caster'], 'caster changed')
            for field, value in expected.items():
                if field in ('target', 'vehicle', 'targetVehicle'):
                    value = identities.get(value, value)
                elif field in ('passengers', 'casterPassengers'):
                    value = ','.join(sorted(identities[role] for role in value)) or 'none'
                require(row[field] == value, f'{key}: wrong {field}: {row[field]} != {value}')
            tick = int(row['tick']); require(tick >= last_tick, 'time regressed'); last_tick = tick
        if 'ACTIONS_DIRECT|' in line:
            row = fields(line, 'ACTIONS_DIRECT|'); key = row['key']
            require(key in c.DIRECT and key not in directs, 'unknown/duplicate direct row')
            require(set(row) == {'key', 'result', 'primary', 'tick'}, 'wrong direct fields')
            require((row['result'], row['primary']) == c.DIRECT[key], 'direct guard failure: ' + key)
            tick = int(row['tick']); require(tick >= last_tick, 'time regressed'); last_tick = tick
            directs[key] = row; direct_indexes[key] = index
    require(set(rows) == set(c.EXPECTED) and set(directs) == set(c.DIRECT), 'incomplete observations')
    require(list(rows) == list(c.EXPECTED), 'state lifecycle order differs')
    require(list(directs) == list(c.DIRECT), 'direct lifecycle order differs')
    require(identity_lines[0][0] < indexes['initial'], 'late identities')
    for begin, end, amount in [(-1, indexes['initial'], '2.5'),
                               (indexes['reenabled_after_cast'], indexes['reload_heal'], '4'),
                               (direct_indexes['reloaded_old_heal'], indexes['live_reload_heal'], '3')]:
        expected = Counter((line.split('{')[0], native_loaded_line(line)) for item in c.native_skills(amount).values()
                           for line in [item['Skills'][0].split(' @')[0]])
        actual = Counter((value['name'].lower(), value['line']) for index, value in loads if begin < index < end)
        require(actual == expected, 'missing/duplicate/wrong native mechanic load')
    require(len(loads) == len(c.native_skills()) * 3, 'extra native load')
    require([kind for _, kind in cancels] == ['mount', 'unmount'], 'missing/extra mount cancellation')
    require(indexes['bad_unknown'] < cancels[0][0] < indexes['cancel_mount']
            and indexes['mount_after_cancel'] < cancels[1][0] < indexes['cancel_unmount'], 'cancel ordering differs')
    require(indexes['reset_before_async'] < direct_indexes['async_mount'] < direct_indexes['async_unmount']
            < direct_indexes['async_heal'] < indexes['async_heal'], 'async lifecycle order differs')
    require(int(rows['async_heal']['tick']) > int(directs['async_heal']['tick']), 'native async cast did not settle')
    for phase, before, after in [('disabled', 'non_damageable', 'disabled_after_cast'),
                                  ('reenabled', 'disabled_after_cast', 'reenabled_after_cast'),
                                  ('reloaded_old', 'reload_heal', 'live_reload_heal'),
                                  ('live_reload_old', 'reload_heal', 'live_reload_heal')]:
        require(indexes[before] < direct_indexes[phase + '_mount'] < direct_indexes[phase + '_unmount']
                < direct_indexes[phase + '_heal'] < indexes[after], 'cached proxy order differs: ' + phase)
    boundaries = [(index, fields(line, 'ACTIONS_BOUNDARY|')) for index, line in enumerate(lines)
                  if 'ACTIONS_BOUNDARY|' in line]
    require(len(boundaries) == 2, 'missing/extra boundary stimulus')
    for (_, row), key, role, entity_type in zip(boundaries, ('non_damageable', 'dead'),
                                               ('nonDamageable', 'dead'), ('INTERACTION', 'HUSK')):
        require(row == {'key': key, 'target': identities[role], 'type': entity_type, 'valid': 'true',
                        'chunkLoaded': 'true', 'spawnAccepted': 'true', 'bindings': '1', 'primary': 'true'},
                'invalid boundary stimulus: ' + key)
    non_damageable = [(index, fields(line, 'ACTIONS_NON_DAMAGEABLE|')) for index, line in enumerate(lines)
                      if 'ACTIONS_NON_DAMAGEABLE|' in line]
    require(len(non_damageable) == 1 and non_damageable[0][1] == {'target': identities['nonDamageable'],
            'nativeAccepted': 'true', 'result': 'INVALID_TARGET'}, 'non-damageable target accepted or stimulus absent')
    require(indexes['async_heal'] < boundaries[0][0] < non_damageable[0][0] < indexes['non_damageable']
            < boundaries[1][0], 'boundary stimulus order differs')
    dead_rows = [(index, fields(line, 'ACTIONS_DEAD|')) for index, line in enumerate(lines) if 'ACTIONS_DEAD|' in line]
    require(len(dead_rows) == 1 and dead_rows[0][1] == {'target': identities['dead'], 'bindingsBefore': '1',
            'health': '0.0', 'dead': 'true', 'result': 'INVALID_TARGET'}, 'dead target revived or stimulus absent')
    require(boundaries[1][0] < dead_rows[0][0] < direct_indexes['disabled_mount'], 'dead test order differs')
    cleanups = [(index, fields(line, 'ACTIONS_CLEANUP|')) for index, line in enumerate(lines) if 'ACTIONS_CLEANUP|' in line]
    require(len(cleanups) == 1 and cleanups[0][1] == {'bindings': '0', 'visuals': '0', 'ownedTasks': '0', 'chunkTickets': '0'}
            and cleanups[0][0] > max(*indexes.values(), *direct_indexes.values()), 'incomplete cleanup')
    summaries = [(index, fields(line, 'ACTIONS_SUMMARY|')) for index, line in enumerate(lines) if 'ACTIONS_SUMMARY|' in line]
    require(len(summaries) == 1 and summaries[0][1] == {'rows': str(len(rows)), 'directs': str(len(directs)), 'completed': 'true'}
            and summaries[0][0] > cleanups[0][0], 'missing complete summary')
    stops = [index for index, line in enumerate(lines) if ']: Stopping server' in line]
    require(len(stops) == 1 and stops[0] > summaries[0][0], 'premature/missing shutdown')
    return len(rows) + len(directs)


def read_evidence(path):
    root = Path(path); archived = root.is_file()
    report = json.loads((root if archived else root / 'model-actions-report.json').read_text(), object_pairs_hook=unique)
    manifest(report)
    if archived:
        name = safe_path(report['logArchive'])
        require(PurePosixPath(name).name == name and name.endswith('.zip'), 'unsafe archive')
        payloads = archive_payloads(report, (root.parent / name).read_bytes())
    else:
        require(root.name == report['fixtureDirectory'], 'fixture directory differs')
        for name, key in [('paper.jar', 'paperJarSha256'), ('plugins/helsteraMobs.jar', 'pluginSha256'),
                          ('plugins/MythicMobs.jar', 'mythicMobsSha256'), ('plugins/HelsteraModelActionsProbe.jar', 'probeJarSha256')]:
            require(digest((root / name).read_bytes()) == report[key], 'artifact changed: ' + name)
        payloads = {name: (root / name).read_bytes() for name in manifest(report)}
        verify_payloads(report, payloads)
    return report, payloads


def main(path):
    report, payloads = read_evidence(path)
    count = validate(report, payloads)
    print(f'Validated {count} Helstera model action observations via native dispatch; no native same-name parity claimed.')
    return report, payloads


if __name__ == '__main__':
    main(sys.argv[1])
