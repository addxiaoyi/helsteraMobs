"""Audit Display state with independent vector/quaternion arithmetic and client packets."""
from pathlib import Path
import argparse
import hashlib
import importlib.util
import json
import math
import zipfile
import yaml

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('display_cases', Path(__file__).with_name('display-transform-cases.py'))
c = importlib.util.module_from_spec(spec); spec.loader.exec_module(c)
PROBE = 'plugins/HelsteraDisplayTransformProbe/'
SEED = {'translation': [1, 2, 3], 'scale': [2, 3, 4], 'left_rotation': [1, 2, 3, 4],
        'right_rotation': [-2, 3, 1, 2], 'delay': 3, 'duration': 12, 'teleport': 2}
METADATA = {'translation': 11, 'scale': 12, 'left_rotation': 13, 'right_rotation': 14,
            'delay': 8, 'duration': 9, 'teleport': 10}
ENTITY_TYPES = {'block': 9, 'item': 59, 'text': 105}


def require(value, message):
    if not value:
        raise ValueError(message)


def digest(data):
    return hashlib.sha256(data).hexdigest()


def close(actual, expected, message):
    require(len(actual) == len(expected) and all(isinstance(x, (int, float)) and math.isfinite(x)
            and math.isclose(x, y, rel_tol=2e-5, abs_tol=2e-5) for x, y in zip(actual, expected)), message)


def multiply(q, p):
    x, y, z, w = q; a, b, d, e = p
    return [w*a+x*e+y*d-z*b, w*b-x*d+y*e+z*a, w*d+x*b-y*a+z*e, w*e-x*a-y*b-z*d]


def expected(case, path):
    wanted = dict(SEED)
    if not case['success']:
        return wanted
    component = case['component']; action = case['action']
    values = case.get('nativeValues', case['values']) if path == 'native' else case['values']
    previous = SEED[component]
    if action == 'SET':
        value = values
    elif action == 'ADD':
        value = [x+y for x, y in zip(previous, values)]
    elif 'rotation' in component:
        if action == 'DIVIDE':
            norm = sum(x*x for x in values)
            values = [-values[0]/norm, -values[1]/norm, -values[2]/norm, values[3]/norm]
        value = multiply(previous, values)
    else:
        value = [x*y if action == 'MULTIPLY' else x/y for x, y in zip(previous, values)]
    wanted[component] = value
    return wanted


def state(actual, wanted, message):
    require(set(actual) == set(SEED), message+' fields')
    for key, value in wanted.items():
        if isinstance(value, list):
            close(actual[key], value, message+'/'+key)
        else:
            require(type(actual[key]) is int and actual[key] == value, message+'/'+key)


def check_row(row, case, index, path, ids):
    require(set(row) == {'index', 'path', 'case', 'source', 'displayId', 'displayUuid', 'displayType', 'controlId',
                         'before', 'startTick', 'primary', 'caster', 'target', 'mapped', 'result', 'after', 'settled', 'endTick'}, 'row fields')
    source = case['mechanic']+'{'+case['arguments']+'}'+('' if case['selector'] == 'none' else ' @trigger')
    mapped = source if path != 'imported' else 'UNSUPPORTED' if case.get('invalid') else 'display-transformation'+source[len(case['mechanic']):]
    require((row['index'], row['path'], row['case'], row['source'], row['mapped']) == (index, path, case['key'], source, mapped), 'row identity/mapping')
    require(row['primary'] is True and type(row['startTick']) is int and row['endTick'] - row['startTick'] == 15, 'thread/tick span')
    require(row['displayType'] == case['display'].upper()+'_DISPLAY' and row['displayId'] != row['controlId'], 'display type/control')
    require(row['caster'] == (row['displayUuid'] if case['selector'] == 'none' else ids['caster']['uuid']), 'caster direction')
    require(row['target'] == (ids['cow']['uuid'] if case['selector'] == 'cow' else row['displayUuid']), 'recipient direction')
    if path == 'native':
        result = 'ArrayIndexOutOfBoundsException' if case.get('invalid') else 'SUCCESS' if case['success'] else 'INVALID_TARGET'
    else:
        result = 'REJECTED' if case.get('invalid') else 'EXECUTED'
    require(row['result'] == result, 'invocation result '+case['key']+'/'+path)
    for label in ('before', 'after', 'settled'):
        require(set(row[label]) == {'display', 'control'}, 'state entities')
        state(row[label]['control'], SEED, 'unselected control')
        state(row[label]['display'], SEED if label == 'before' else expected(case, path), case['key']+'/'+path+'/'+label)


def packet_state(metadata, wanted, message):
    for name, key in METADATA.items():
        require(str(key) in metadata, message+' missing '+name)
        value = metadata[str(key)]; expected_value = wanted[name]
        if isinstance(expected_value, list):
            if isinstance(value, dict):
                value = [value[axis] for axis in ('x', 'y', 'z', 'w')[:len(expected_value)]]
            close(value, expected_value, message+'/'+name)
        else:
            require(value == expected_value, message+'/'+name)


def clients(events, rows, ids):
    require(not any(e['event'] in ('packet-error', 'world-reset') for e in events), 'client decoding/world')
    summary = [e for e in events if e['event'] == 'summary']
    require(len(summary) == 1 and summary[0]['passed'] is True and summary[0]['error'] is None
            and summary[0]['doneRoles'] == ['observer', 'receiver'], 'two-client completion')
    for role in ('receiver', 'observer'):
        joins = [e for e in events if e['event'] == 'join' and e['role'] == role]
        require(len(joins) == 1 and joins[0]['uuid'] == ids[role]['uuid'] and joins[0]['entityId'] == ids[role]['entityId'], 'client identity')
        require(len([e for e in events if e['event'] == 'done' and e['role'] == role]) == 1, 'client done')
        require([e['index'] for e in events if e['event'] == 'phase' and e['role'] == role] == list(range(len(rows))), 'ordered client phases')
    for row in rows:
        index = row['index']; case = c.CASES[index // 3]; wanted = expected(case, row['path'])
        for role in ('receiver', 'observer'):
            phase = [e for e in events if e.get('index') == index and e.get('role') == role]
            require(all((e['path'], e['case']) == (row['path'], row['case']) for e in phase), 'client phase identity')
            require([e['tick'] for e in phase if e['event'] == 'tick'] == list(range(16)), 'client ticks')
            target = [e for e in phase if e['event'] == 'target']
            require(len(target) == 1 and target[0]['entityId'] == row['displayId'] and target[0]['controlId'] == row['controlId'], 'target declaration')
            ends = [e for e in phase if e['event'] == 'end']; require(len(ends) == 1, 'client end')
            end = ends[0]
            for field, entity_id, entity_type, expected_state in (
                    ('target', row['displayId'], ENTITY_TYPES[case['display']], wanted),
                    ('control', row['controlId'], 9, SEED)):
                require(end[field] is not None and end[field]['entityId'] == entity_id and end[field]['type'] == entity_type, 'tracked Display')
                packet_state(end[field]['metadata'], expected_state, 'client final '+field)
                # Rebuild metadata independently, including spawn-time packets before the phase marker.
                spawns = [e for e in events if e.get('role') == role and e['event'] == 'packet'
                          and e['packetType'] == 'spawn_entity' and e['data']['entityId'] == entity_id]
                require(len(spawns) == 1 and spawns[0]['data']['type'] == entity_type, 'client spawn')
                metadata_events = [e for e in events if e.get('role') == role and e['event'] == 'packet'
                                   and e['packetType'] == 'entity_metadata' and e['data']['entityId'] == entity_id]
                reconstructed = {}; before = {}; changed = []
                for event in metadata_events:
                    for entry in event['data']['metadata']:
                        reconstructed[str(entry['key'])] = entry['value']
                        if event.get('index') != index or event['tick'] < 0:
                            before[str(entry['key'])] = entry['value']
                    if event.get('index') == index and event['tick'] >= 0:
                        changed.extend(entry['key'] for entry in event['data']['metadata'])
                packet_state(before, SEED, 'client initial '+field)
                packet_state(reconstructed, expected_state, 'reconstructed '+field)
                if field == 'control' or not case['success']:
                    require(not set(changed).intersection(METADATA.values()), 'unmodified Display packets')
                else:
                    require(8 in changed and METADATA[case['component']] in changed, 'transformation/interpolation update packets')


def audit(report, files):
    require(report['evidenceMode'] == 'observation' and report['parityStatus'] == 'unassessed', 'claim scope')
    require(report['exitCode'] == report['clientExitCode'] == 0, 'process completion')
    require(report['paperJarSha256'] == '39bd8c00b9e18de91dcabd3cc3dcfa5328685a53b7187a2f63280c22e2d287b9', 'pinned Paper')
    require(report['mythicMobsSha256'] == '9672457cafc56ff841606bfbb710947329c08720ffc207746b60130d43bf902f', 'pinned native')
    require(report['observationCount'] == len(c.CASES)*3, 'observation count')
    require(set(files) == {f['path'] for f in report['files']}, 'file inventory')
    for entry in report['files']:
        require(digest(files[entry['path']]) == entry['sha256'], 'file hash')
    require(yaml.safe_load(files[PROBE+'cases.yml']) == {'cases': c.CASES}, 'case definition')
    ids = yaml.safe_load(files[PROBE+'identities.yml']); rows = []
    paths = sorted(name for name in files if name.startswith(PROBE+'observations/'))
    require(len(paths) == len(c.CASES)*3, 'row files')
    for index, path in enumerate(paths):
        row = yaml.safe_load(files[path]); check_row(row, c.CASES[index//3], index, ('native', 'direct', 'imported')[index%3], ids); rows.append(row)
    log = files['server.log'].decode()
    require('DISPLAY_TRANSFORM_FAILED|' not in log and f'DISPLAY_TRANSFORM_SUMMARY|observations={len(rows)}|completed=true' in log, 'server completion')
    events = [json.loads(line[len('DISPLAY_TRANSFORM_CLIENT '):]) for line in files['client.log'].decode().splitlines()
              if line.startswith('DISPLAY_TRANSFORM_CLIENT ')]
    clients(events, rows, ids)
    return rows


def read_report(path, check_current=False):
    report = json.loads(path.read_text()); archive = path.with_name(report['logArchive'])
    require(digest(archive.read_bytes()) == report['logArchiveSha256'], 'archive hash')
    with zipfile.ZipFile(archive) as packed:
        require(len(packed.namelist()) == len(set(packed.namelist())), 'duplicate archive member')
        files = {name: packed.read(name) for name in packed.namelist()}
    if check_current:
        require(digest((ROOT/'helstera-plugin/target/helsteraMobs-1.0.0-full.jar').read_bytes()) == report['pluginSha256'], 'current artifact')
        for name, expected_hash in report['sourceHashes'].items():
            require(digest((ROOT/name).read_bytes()) == expected_hash, 'current source '+name)
    return report, files


if __name__ == '__main__':
    parser = argparse.ArgumentParser(); parser.add_argument('report', type=Path); parser.add_argument('--check-current', action='store_true')
    args = parser.parse_args(); report, files = read_report(args.report, args.check_current)
    rows = audit(report, files); print('passed', len(rows), 'display-transform observations')
