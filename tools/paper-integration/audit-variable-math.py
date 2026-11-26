"""Independent semantic audit for variable equation observations; no release promotion."""
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
# arguments, resolved equation, seed type, native value, runtime value, result, duration ticks
CASES = {}

def integer(name, arguments, equation, result, ticks=0):
    CASES[name] = (arguments, equation, 'Integer', 'Integer:' + str(result), 'Integer:' + str(result), 'SUCCESS', ticks)

integer('default', '', 'x', 7)
integer('legacy-options', 'op=*;value=3', 'x', 7)
integer('equation', 'equation=x*2+1', 'x*2+1', 15)
integer('eq', 'eq=x/2', 'x/2', 3)
integer('e', 'e=x+2', 'x+2', 9)
integer('quoted', 'equation="x + 2"', 'x + 2', 9)
integer('single-quoted', "equation='x+2'", 'x+2', 9)
integer('alias-priority', 'equation=x+1;eq=x+2;e=x+3', 'x+1', 8)
integer('duplicate', 'equation=bad;EQUATION=x+3', 'x+3', 10)
integer('parentheses', 'equation=(x+1)*2', '(x+1)*2', 16)
integer('power', 'equation=2^3^2', '2^3^2', 512)
integer('unary', 'equation=-x/2', '-x/2', -3)
CASES['float'] = ('equation=x/2', 'x/2', 'Float', 'Float:3.5', 'Float:3.5', 'SUCCESS', 0)
integer('functions', 'equation=max(2,min(x,4))', 'max(2,min(x,4))', 4)
CASES['missing'] = ('equation=x+1', 'x+1', None, None, None, 'INVALID_CONFIG', 0)
CASES['double'] = ('equation=x+1', 'x+1', 'Double', 'Double:7.0', 'Double:7.0', 'INVALID_CONFIG', 0)
CASES['string'] = ('equation=x+1', 'x+1', 'String', 'String:7', 'String:7', 'INVALID_CONFIG', 0)
integer('duration-preserve', 'equation=x+1;save=false', 'x+1', 8)
integer('duration-positive', 'equation=x+1;duration=1200', 'x+1', 8, 1200)
integer('duration-zero-save', 'equation=x+1;duration=0;save=true', 'x+1', 8)
integer('duration-negative', 'equation=x+1;duration=-4', 'x+1', 8, -4)
integer('duration-e-numeric', 'e=1200', '1200', 1200, 1200)
integer('duration-alias-priority', 'equation=x+1;duration=1200;d=2400', 'x+1', 8, 1200)
integer('duration-invalid-first', 'equation=x+1;duration=bad;d=1200', 'x+1', 8)
integer('duration-duplicate', 'equation=x+1;duration=1200;DURATION=0', 'x+1', 8)
CASES['division-zero'] = ('equation=x/0', 'x/0', 'Integer', 'Integer:7', 'Integer:7', 'exception:ArithmeticException', 0)
CASES['nan-integer'] = ('equation=sqrt(-1)', 'sqrt(-1)', 'Integer', 'Integer:0', 'Integer:7', 'SUCCESS', 0)
CASES['nan-float'] = ('equation=sqrt(-1)', 'sqrt(-1)', 'Float', 'Float:NaN', 'Float:7.0', 'SUCCESS', 0)
CASES['overflow-float'] = ('equation=10^1000', '10^1000', 'Float', 'Float:Infinity', 'Float:7.0', 'SUCCESS', 0)
integer('finite-int-saturation', 'equation=10^12', '10^12', 2147483647)
SOURCE_FILES = {
    'tools/paper-integration/run-variable-math-fixture.py',
    'tools/paper-integration/run-player-variable-fixture.py',
    'tools/paper-integration/random-spawn-fixture/src/main/java/dev/helstera/test/randomspawn/VariableMathProbe.java',
    'tools/paper-integration/random-spawn-fixture/src/main/java/dev/helstera/test/randomspawn/VariablePlaceholderAudit.java',
    'helstera-migration/src/main/java/dev/helstera/migration/importer/MythicMobsImporter.java',
    'helstera-migration/src/main/java/dev/helstera/migration/importer/MythicSpec.java',
    'helstera-ai/src/main/java/dev/helstera/ai/skill/MythicSkillSpec.java',
    'helstera-ai/src/main/java/dev/helstera/ai/skill/SkillService.java',
    'helstera-ai/src/main/java/dev/helstera/ai/skill/SkillExtras.java',
    'helstera-api/src/main/java/dev/helstera/api/util/MythicVariableReference.java',
    'helstera-api/src/main/java/dev/helstera/api/util/MythicVariableMath.java',
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
    for item in report['files']:
        require(digest(files[item['path']]) == item['sha256'], 'file digest: ' + item['path'])
    ids = []
    identity_paths = {'plugins/HelsteraVariableMathProbe/identities-' + path + '.yml' for path in PATHS}
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
    require(log.count('VMATH_SUMMARY|observations=300|passed=true') == 1 and 'VMATH_FAILED|' not in log, 'completion')
    require(log.count('Could not perform math operation on non-numeric variable type') == 20, 'non-numeric diagnostics')
    log_rows = Counter(re.findall(r'VMATH_ROW\|case=([^|\r\n]+)\|scope=([^|\r\n]+)\|path=([^|\r\n]+)\|matched=true', log))
    seen, keys = set(), set()
    differences = 0
    for row in rows:
        path, scope, case, key = row['path'], row['scope'], row['case'], row['key']
        identity = (path, scope, case)
        require(identity in expected and identity not in seen, 'case coverage')
        seen.add(identity)
        require(re.fullmatch(r'MathAudit\d+', key) and key not in keys, 'unique observation key')
        keys.add(key)
        require(log_rows[(case, scope, path)] == 1 and row['matched'] is True, 'log correspondence')
        arguments, equation, kind, native_value, runtime_value, native_result, ticks = CASES[case]
        before = {candidate + '/' + key: 'Integer:' + str(10 + i) for i, candidate in enumerate(REGISTRIES)}
        field = scope + '/' + key
        if kind is None:
            del before[field]
        else:
            before[field] = kind + (':7.0' if kind in ('Float', 'Double') else ':7')
        require(row['nativeBefore'] == row['runtimeBefore'] == before, 'registry seeds')
        source = 'variablemath{var=' + scope + '.' + key + (';' + arguments if arguments else '') + '}'
        require(row['source'] == source, 'source case')
        if path == 'native-direct':
            mapped = source
        else:
            mapped = 'variable-math var=' + scope + '.' + key + ' equation="' + equation + '"'
            if re.search(r'(?:^|;)(?:duration|d|expire|e)=', arguments, re.I):
                mapped += ' duration=' + str(ticks)
        require(row['mapped'] == row['bindingSource'] == mapped, 'binding source')
        require(row['resolvedEquation'] == equation, 'equation resolution')
        require(row['nativeResult'] == native_result and row['runtimeResult'] == 'dispatched', 'dispatch results')
        require(row['bindingDiagnostics'] == [], 'unexpected binding diagnostics')
        expected_millis = ticks * 50 if ticks != 0 else (0 if case == 'duration-zero-save' else 1)
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
    print(f'Variable math audit passed: {count} observations; 30 explicit non-finite differences; observation-only')
