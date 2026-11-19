"""Validate complete native/runtime persistence observations across three actual boots."""
import hashlib
import itertools
import json
import pathlib
import re
import sys

SCOPES = ('caster', 'target', 'world', 'global')
CASES = ('Default', 'Save', 'Transient', 'ZeroSave', 'ZeroTransient', 'Negative', 'TimedSave',
         'TimedTransient', 'Expired', 'ReplaceTransient', 'ReplaceSaved', 'AddSave', 'AddTransient',
         'AddRefresh', 'Float', 'Double', 'String', 'Location')
TRANSIENT = {'Default', 'Transient', 'ZeroTransient', 'ReplaceTransient', 'AddSave'}
TIMED = {'TimedSave', 'TimedTransient', 'Expired', 'AddRefresh'}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def fields(line, marker):
    tail = line.split(marker, 1)[1]
    result = {}
    for pair in tail.split('|'):
        key, value = pair.split('=', 1)
        require(key not in result, f'duplicate field: {key}')
        result[key] = value
    return result


def validate(report, texts):
    require(report.get('evidenceMode') == 'comparison', 'native observations alone do not prove runtime parity')
    require(report.get('paperJarSha256') == '39bd8c00b9e18de91dcabd3cc3dcfa5328685a53b7187a2f63280c22e2d287b9', 'wrong Paper artifact')
    require(report.get('mythicMobsSha256') == '9672457cafc56ff841606bfbb710947329c08720ffc207746b60130d43bf902f', 'wrong native artifact')
    for field in ('pluginSha256', 'paperJarSha256', 'mythicMobsSha256', 'probeSourceSha256',
                  'probeJarSha256', 'harnessSourceSha256', 'runnerSourceSha256'):
        require(re.fullmatch('[0-9a-f]{64}', report.get(field, '')) is not None, f'invalid {field}')
    expected_keys = set(itertools.product(SCOPES, CASES, ('direct', 'imported')))
    probes = report['probes']
    require(len(probes) == 3, 'requires three stages')
    written = {}
    identities = None
    for stage, probe in zip(('write', 'verify', 'final'), probes):
        require(probe['id'] == f'variable.persistence.{stage}' and probe['status'] == 'passed', 'invalid stage')
        text = texts[f'persistence-{stage}.log']
        summary = f'VARIABLE_PERSIST_SUMMARY|stage={stage}|observations=144|mode=comparison|passed=true'
        require(text.count('VARIABLE_PERSIST_SUMMARY|') == 1 and summary in text, 'incomplete stage summary')
        rows = [line for line in text.splitlines() if 'VARIABLE_PERSIST|' in line]
        require(rows == probe['observations'], 'report does not match server observations')
        require(len(rows) == 144, 'incomplete observation count')
        seen = set()
        for line in rows:
            row = fields(line, 'VARIABLE_PERSIST|')
            key = row['scope'], row['case'], row['path']
            require(key not in seen and key in expected_keys and row['stage'] == stage, 'duplicate or unknown observation')
            seen.add(key)
            scope, kind, _ = key
            present = stage == 'write' or stage == 'verify' and kind != 'Expired' and not (scope == 'world' and kind in TRANSIENT)
            expected = str(present).lower()
            require(all(row[field] == expected for field in ('expectedPresent', 'nativePresent', 'runtimePresent')), 'incorrect retention')
            require(row['matched'] == 'true', 'runtime/native mismatch')
            value = 'location' if kind == 'Location' else 'absent' if not present else (
                '3.25' if kind in ('Float', 'Double') else 'saved-string' if kind == 'String'
                else '9' if kind.startswith(('Add', 'Replace')) else '7')
            require(row['native'] == row['runtime'] == value, 'incorrect value')
            native, runtime = int(row['nativeDeadline']), int(row['runtimeDeadline'])
            if stage == 'write':
                if kind in TIMED:
                    require(native > 1_000_000_000_000 and abs(native - runtime) < 1000, 'incorrect timed deadline')
                else:
                    marker = 1 if kind in TRANSIENT else -50 if kind == 'Negative' else 0
                    require(native == runtime == marker, 'incorrect save marker')
                written[key] = native, runtime
            elif present:
                require((native, runtime) == written[key], 'deadline reset on restore')
            else:
                require(native == runtime == 0, 'absent value retains deadline')
        require(seen == expected_keys, 'missing observation')
        entities = [fields(line, 'VARIABLE_PERSIST_ENTITY|') for line in text.splitlines() if 'VARIABLE_PERSIST_ENTITY|' in line]
        ids = {entry['id'] for entry in entities}
        require(len(entities) == len(ids) == 8 and all(e['stage'] == stage and e['mode'] == 'PERSISTENT' for e in entities), 'missing persistent identities')
        if identities is None:
            identities = ids
        require(ids == identities, 'entities changed across boots')
    for stage in ('verify', 'final'):
        require(re.fullmatch(r'registry-format: 1\s+registries: \{\}\s*', texts[f'persistence-{stage}.yml']) is not None,
                'deleted variables were resaved')
    require('name: PersistDSave' in texts['persistence-write.yml'], 'write snapshot has no saved control')
    return 432


def main(directory):
    root = pathlib.Path(directory)
    report = json.loads((root / 'variable-persistence-report.json').read_text(encoding='utf-8-sig'))
    for name, field in (('paper.jar', 'paperJarSha256'), ('plugins/helsteraMobs.jar', 'pluginSha256'),
                        ('plugins/MythicMobs.jar', 'mythicMobsSha256'), ('plugins/HelsteraVariablePersistenceProbe.jar', 'probeJarSha256')):
        require(hashlib.sha256((root / name).read_bytes()).hexdigest() == report[field], 'artifact differs from report')
    texts = {}
    for kind, suffix in (('logs', '.log'), ('snapshots', '.yml')):
        entries = report[kind]
        require({e['path'] for e in entries} == {f'persistence-{s}{suffix}' for s in ('write', 'verify', 'final')}
                and len(entries) == 3, f'incomplete {kind}')
        for entry in entries:
            data = (root / entry['path']).read_bytes()
            require(hashlib.sha256(data).hexdigest() == entry['sha256'], 'changed evidence file')
            texts[entry['path']] = data.decode('utf-8-sig')
    print(f'Validated {validate(report, texts)} persistence observations, eight stable UUIDs and three shutdown snapshots.')


if __name__ == '__main__':
    main(sys.argv[1])
