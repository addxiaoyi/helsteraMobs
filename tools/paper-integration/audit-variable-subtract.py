"""Independent semantic audit for variable subtraction observations; no release promotion."""
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import re
import uuid
import zipfile

import yaml

ROOT = Path(__file__).resolve().parents[2]
SCOPES = ['skill', 'caster', 'target', 'world', 'global']
REGISTRIES = ['skill', 'caster', 'target', 'trigger', 'world', 'other-world', 'global']
PATHS = ['native-direct', 'imported']
# Raw source and expected semantics are independent of probe-generated expectations.
# mechanic, arguments, selected amount, resolved float, seed type/value,
# native value, runtime value, result, duration ticks
CASES = {}


def integer(name, arguments, amount, operand, result, ticks=0, mechanic='variableSubtract', initial='7'):
    CASES[name] = (mechanic, arguments, amount, operand, 'Integer', initial,
                   'Integer:' + str(result), 'Integer:' + str(result), 'SUCCESS', ticks)


integer('default', '', '0', '0.0', 7)
for alias in ('value', 'val', 'v', 'amount', 'a'):
    integer('value-alias-' + alias, alias + '=2.5', '2.5', '2.5', 5)
for alias in ('variableSub', 'subtractVariable', 'subVar', 'reduceVariable'):
    integer('mechanic-' + alias, 'value=2', '2', '2.0', 5, mechanic=alias)
integer('negative', 'value=-2.9', '-2.9', '-2.9', 9)
integer('priority', 'a=9;amount=8;v=7;val=6;value=2', '2', '2.0', 5)
integer('duplicate', 'value=bad;VALUE=3', '3', '3.0', 4)
CASES['quoted'] = ('variableSubtract', 'value="2.5"', '2.5', '1.0', 'Integer', '7',
                   'Integer:6', 'Integer:5', 'SUCCESS', 0)
CASES['placeholder'] = ('variableSubtract', 'value=<target.health>', '<target.health>', '1.0', 'Integer', '7',
                        'Integer:6', 'Integer:-3', 'SUCCESS', 0)
CASES['float'] = ('variableSubtract', 'value=0.5', '0.5', '0.5', 'Float', '1.25',
                  'Float:0.75', 'Float:0.75', 'SUCCESS', 0)
CASES['float-negative'] = ('variableSubtract', 'value=-0.5', '-0.5', '-0.5', 'Float', '1.25',
                           'Float:1.75', 'Float:1.75', 'SUCCESS', 0)
integer('integer-overflow', 'value=1', '1', '1.0', 2147483647, initial='-2147483648')
integer('positive-saturation', 'value=340282350000000000000000000000000000000', '340282350000000000000000000000000000000', '3.4028235E38', -2147483640)
integer('negative-saturation', 'value=-340282350000000000000000000000000000000', '-340282350000000000000000000000000000000', '-3.4028235E38', -2147483641)
CASES['float-overflow'] = ('variableSubtract', 'value=-340282350000000000000000000000000000000', '-340282350000000000000000000000000000000', '-3.4028235E38',
                           'Float', '3.4028235E38', 'Float:Infinity', 'Float:3.4028235E38', 'SUCCESS', 0)
CASES['missing'] = ('variableSubtract', 'value=2', '2', '2.0', None, '7', None, None, 'INVALID_CONFIG', 0)
CASES['double'] = ('variableSubtract', 'value=2', '2', '2.0', 'Double', '7.0',
                   'Double:7.0', 'Double:7.0', 'INVALID_CONFIG', 0)
CASES['string'] = ('variableSubtract', 'value=2', '2', '2.0', 'String', '7',
                   'String:7', 'String:7', 'INVALID_CONFIG', 0)
integer('duration-positive', 'value=2;duration=1200', '2', '2.0', 5, 1200)
integer('duration-zero', 'value=2;duration=0;save=true', '2', '2.0', 5)
integer('duration-negative', 'value=2;duration=-4', '2', '2.0', 5, -4)
integer('duration-priority', 'value=2;duration=1200;d=2400', '2', '2.0', 5, 1200)
integer('duration-invalid', 'value=2;duration=bad;d=1200', '2', '2.0', 5)
integer('duration-duplicate', 'value=2;duration=bad;DURATION=1200', '2', '2.0', 5, 1200)
SOURCE_FILES = {
    'tools/paper-integration/run-variable-subtract-fixture.py',
    'tools/paper-integration/run-player-variable-fixture.py',
    'tools/paper-integration/random-spawn-fixture/src/main/java/dev/helstera/test/randomspawn/VariableSubtractProbe.java',
    'tools/paper-integration/random-spawn-fixture/src/main/java/dev/helstera/test/randomspawn/VariablePlaceholderAudit.java',
    'helstera-migration/src/main/java/dev/helstera/migration/importer/MythicMobsImporter.java',
    'helstera-migration/src/main/java/dev/helstera/migration/importer/MythicSpec.java',
    'helstera-ai/src/main/java/dev/helstera/ai/skill/MythicSkillSpec.java',
    'helstera-ai/src/main/java/dev/helstera/ai/skill/SkillService.java',
    'helstera-ai/src/main/java/dev/helstera/ai/skill/SkillExtras.java',
    'helstera-api/src/main/java/dev/helstera/api/util/MythicVariableReference.java',
    'helstera-api/src/main/java/dev/helstera/api/util/MythicVariableArithmetic.java',
    'helstera-api/src/main/java/dev/helstera/api/util/NumericExpression.java',
}


def require(value, message):
    if not value:
        raise ValueError(message)


def digest(value):
    return hashlib.sha256(value).hexdigest()


def audit(report, files):
    require(type(report.get('schemaVersion')) is int and report['schemaVersion'] == 1, 'schema version')
    require(report.get('evidenceMode') == 'observation' and report.get('parityStatus') == 'unassessed', 'evidence boundary')
    require(report.get('validationResult') == 'passed' and report.get('exitCode') == 0, 'fixture result')
    require(report.get('paperJarSha256') == '39bd8c00b9e18de91dcabd3cc3dcfa5328685a53b7187a2f63280c22e2d287b9', 'Paper pin')
    require(report.get('mythicMobsSha256') == '9672457cafc56ff841606bfbb710947329c08720ffc207746b60130d43bf902f', 'native pin')
    for key in ('pluginSha256', 'probeJarSha256'):
        require(re.fullmatch('[0-9a-f]{64}', report.get(key, '')), key)
    require(set(report.get('sourceHashes', {})) == SOURCE_FILES, 'source manifest')
    require(all(re.fullmatch('[0-9a-f]{64}', value) for value in report['sourceHashes'].values()), 'source digest')
    require(len(report['files']) == len(files) == 304, 'archive cardinality')
    require({item['path'] for item in report['files']} == set(files), 'file manifest')
    expected_paths = {'server.log', 'plugins/MythicMobs/Mobs/subtract.yml'}
    expected_paths.update('plugins/HelsteraVariableSubtractProbe/identities-' + path + '.yml' for path in PATHS)
    expected_paths.update(f'plugins/HelsteraVariableSubtractProbe/observations/{i:04d}.yml' for i in range(300))
    require(set(files) == expected_paths, 'exact archive paths')
    require(yaml.safe_load(files['plugins/MythicMobs/Mobs/subtract.yml']) == {
        'HelsteraRadiusAuditMob': {'Type': 'COW', 'Options': {'NoAI': True, 'Despawn': 'PERSISTENT'}}}, 'native mob configuration')
    for item in report['files']:
        require(digest(files[item['path']]) == item['sha256'], 'file digest: ' + item['path'])
    ids = []
    identity_paths = {'plugins/HelsteraVariableSubtractProbe/identities-' + path + '.yml' for path in PATHS}
    require({name for name in files if '/identities-' in name} == identity_paths, 'identity paths')
    for name in identity_paths:
        values = yaml.safe_load(files[name])
        require(set(values) == {'entity-0', 'entity-1', 'entity-2', 'entity-3'}, 'identity roles')
        ids.extend(str(uuid.UUID(value)) for value in values.values())
    require(len(ids) == len(set(ids)) == 8, 'unique identities')
    rows = [yaml.safe_load(data) for name, data in files.items() if '/observations/' in name]
    expected = {(path, scope, case) for path in PATHS for scope in SCOPES for case in CASES}
    require(len(rows) == report.get('observationCount') == len(expected) == 300, 'observation count')
    log = files['server.log'].decode()
    require(log.count('VSUB_SUMMARY|observations=300|passed=true') == 1 and 'VSUB_FAILED|' not in log, 'completion')
    require(log.count('Could not perform math operation on non-numeric variable type') == 20, 'non-numeric diagnostics')
    log_rows = Counter(re.findall(r'VSUB_ROW\|case=([^|\r\n]+)\|scope=([^|\r\n]+)\|path=([^|\r\n]+)\|matched=true', log))
    seen, keys = set(), set()
    differences = 0
    for row in rows:
        path, scope, case, key = row['path'], row['scope'], row['case'], row['key']
        identity = (path, scope, case)
        require(identity in expected and identity not in seen, 'case coverage')
        seen.add(identity)
        require(re.fullmatch(r'SubtractAudit\d+', key) and key not in keys, 'unique observation key')
        keys.add(key)
        require(log_rows[(case, scope, path)] == 1 and row['matched'] is True, 'log correspondence')
        mechanic, arguments, amount, operand, kind, initial, native_value, runtime_value, native_result, ticks = CASES[case]
        before = {candidate + '/' + key: 'Integer:' + str(10 + i) for i, candidate in enumerate(REGISTRIES)}
        field = scope + '/' + key
        if kind is None:
            del before[field]
        else:
            before[field] = kind + ':' + initial
        require(row['nativeBefore'] == row['runtimeBefore'] == before, 'registry seeds')
        source = mechanic + '{var=' + scope + '.' + key + (';' + arguments if arguments else '') + '}'
        require(row['source'] == source, 'source case')
        if path == 'native-direct':
            mapped = source
        else:
            mapped = 'variable-subtract var=' + scope + '.' + key + ' value="' + amount + '"'
            if re.search(r'(?:^|;)(?:duration|d|expire|e)=', arguments, re.I):
                mapped += ' duration=' + str(ticks)
        require(row['mapped'] == row['bindingSource'] == mapped, 'binding source')
        require(row['resolvedOperand'] == operand, 'operand resolution')
        require(type(row['targetHealth']) in (int, float) and row['targetHealth'] == 10.0, 'placeholder target health')
        require(row['nativeResult'] == native_result and row['runtimeResult'] == 'dispatched', 'dispatch results')
        require(row['bindingDiagnostics'] == [], 'unexpected binding diagnostics')
        expected_millis = ticks * 50 if ticks != 0 else (0 if case == 'duration-zero' else 1)
        require(row['nativeDurationMillis'] == expected_millis, 'native duration selection')
        native_after, runtime_after = dict(before), dict(before)
        if native_value is not None:
            native_after[field] = native_value
            runtime_after[field] = runtime_value
        require(row['expectedNativeAfter'] == row['nativeAfter'] == native_after, 'native mutation')
        require(row['expectedRuntimeAfter'] == row['runtimeAfter'] == runtime_after, 'runtime mutation')
        require(row['sameValue'] is (native_after == runtime_after), 'explicit difference')
        differences += native_after != runtime_after
        start, end, runtime_start, runtime_end = (row[k] for k in ('nativeStart', 'nativeEnd', 'runtimeStart', 'runtimeEnd'))
        require(all(type(value) is int for value in (start, end, runtime_start, runtime_end))
                and 1_500_000_000_000 < start <= end <= runtime_start <= runtime_end
                and runtime_end - start < 30_000, 'dispatch clock bounds')
        native_before, runtime_before = row['nativeBeforeExpiration'], row['runtimeBeforeExpiration']
        require(type(native_before) is int and type(runtime_before) is int and native_before == runtime_before, 'seed deadlines')
        require(native_before == 0 if kind is None else runtime_end < native_before <= start + 120_000, 'live seed deadline')
        for backend, left, right in [('native', start, end), ('runtime', runtime_start, runtime_end)]:
            after = row[backend + 'AfterExpiration']
            require(type(after) is int, 'deadline type')
            if ticks > 0:
                require(left + ticks * 50 <= after <= right + ticks * 50, 'replacement deadline')
            else:
                require(after == native_before, 'preserved deadline')
    require(log.count('Math and variables in numeric values require premium!') == 20, 'public numeric parser gate')
    require(seen == expected and sum(log_rows.values()) == 300 and differences == 30, 'complete coverage and differences')
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
    print(f'Variable subtraction audit passed: {count} observations; 20 public-parser and 10 float-overflow differences; observation-only')
