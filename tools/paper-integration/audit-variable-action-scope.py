"""Independent audit of variable action scope observations; no release promotion."""
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
REGISTRIES = ['skill', 'caster', 'target', 'world', 'global']
SEEDS = dict(skill=10, caster=11, target=12, trigger=13, world=14, **{'other-world': 15}, global_=16)
SEEDS['global'] = SEEDS.pop('global_')
CASES = {'default': ('var={key}', 'skill')}
for scope in REGISTRIES:
    CASES['prefix-' + scope] = ('var=' + scope + '.{key}', scope)
    CASES['explicit-' + scope] = ('var={key};scope=' + scope, scope)
    CASES['override-' + scope] = ('var=skill.{key};s=' + scope.upper(), scope)
for alias in ['name', 'n', 'variable', 'var', 'key', 'k']:
    CASES['alias-' + alias] = (alias + '=ignored.{key};s=target', 'target')
CASES.update({
    'duplicate-name': ('var=skill.{key};VAR=target.{key}', 'target'),
    'alias-order': ('var=skill.{key};variable=caster.{key}', 'caster'),
    'name-priority': ('var=skill.{key};variable=caster.{key};name=world.{key}', 'world'),
    'scope-priority': ('var={key};s=caster;scope=target', 'target'),
    'duplicate-scope': ('var=skill.{key};scope=bad;SCOPE=global', 'global'),
    'invalid-prefix-overridden': ('var=trigger.{key};scope=world', 'world'),
    'empty-prefix-overridden': ('var=.{key};scope=caster', 'caster'),
    'dotted-key-prefix': ('var=global.{key}', 'global'),
    'dotted-key-override': ('var=skill.{key};scope=target', 'target'),
    'invalid-prefix': ('var=unknown.{key}', None),
    'trigger-prefix': ('var=trigger.{key}', None),
    'invalid-scope': ('var=skill.{key};scope=bad', None),
    'empty-scope': ('var=skill.{key};scope=', None),
    'trigger-scope': ('var=skill.{key};scope=trigger', None),
    'duplicate-invalid': ('var=skill.{key};scope=caster;scope=bad', None),
    'quoted-scope': ('var=skill.{key};scope="target"', None),
})
OPERATIONS = {'set': ('setvariable', ';value=7'), 'add': ('variableadd', ';amount=7'),
              'unset': ('variableunset', ''), 'location': ('setvariablelocation', ';value=@origin')}
SOURCE_FILES = {
    'tools/paper-integration/run-variable-action-scope-fixture.py',
    'tools/paper-integration/run-player-variable-fixture.py',
    'tools/paper-integration/random-spawn-fixture/src/main/java/dev/helstera/test/randomspawn/VariableActionScopeProbe.java',
    'tools/paper-integration/random-spawn-fixture/src/main/java/dev/helstera/test/randomspawn/VariablePlaceholderAudit.java',
    'helstera-migration/src/main/java/dev/helstera/migration/importer/MythicMobsImporter.java',
    'helstera-migration/src/main/java/dev/helstera/migration/importer/MythicSpec.java',
}
DIRECT_SOURCE_FILES = {
    'helstera-ai/src/main/java/dev/helstera/ai/skill/MythicSkillSpec.java',
    'helstera-ai/src/main/java/dev/helstera/ai/skill/SkillService.java',
    'helstera-api/src/main/java/dev/helstera/api/util/MythicVariableReference.java',
}


def require(value, message):
    if not value:
        raise ValueError(message)


def digest(value):
    return hashlib.sha256(value).hexdigest()


def audit(report, files):
    version = report.get('schemaVersion')
    require(type(version) is int and version in (1, 2), 'schema version')
    paths = ('canonical-direct', 'imported') if version == 1 else ('canonical-direct', 'native-direct', 'imported')
    count = 276 if version == 1 else 428
    archive_count = 280 if version == 1 else 433
    native_diagnostics = 28 if version == 1 else 56
    require(report.get('evidenceMode') == 'observation' and report.get('parityStatus') == 'unassessed', 'evidence boundary')
    require(report.get('validationResult') == 'passed' and report.get('exitCode') == 0, 'fixture result')
    require(report.get('paperJarSha256') == '39bd8c00b9e18de91dcabd3cc3dcfa5328685a53b7187a2f63280c22e2d287b9', 'Paper pin')
    require(report.get('mythicMobsSha256') == '9672457cafc56ff841606bfbb710947329c08720ffc207746b60130d43bf902f', 'native pin')
    for key in ('pluginSha256', 'probeJarSha256'):
        require(re.fullmatch('[0-9a-f]{64}', report.get(key, '')), key)
    source_files = SOURCE_FILES if version == 1 else SOURCE_FILES | DIRECT_SOURCE_FILES
    require(set(report.get('sourceHashes', {})) == source_files, 'source manifest')
    require(all(re.fullmatch('[0-9a-f]{64}', value) for value in report['sourceHashes'].values()), 'source digest')
    require(len(report['files']) == len(files) == archive_count, 'archive cardinality')
    require({item['path'] for item in report['files']} == set(files), 'file manifest')
    for item in report['files']:
        require(digest(files[item['path']]) == item['sha256'], 'file digest: ' + item['path'])
    ids = []
    identity_paths = {'plugins/HelsteraVariableActionScopeProbe/identities-' + path + '.yml'
                      for path in (('false', 'true') if version == 1 else paths)}
    require({name for name in files if '/identities-' in name} == identity_paths, 'identity paths')
    for name, data in files.items():
        if '/identities-' in name:
            values = yaml.safe_load(data)
            require(set(values) == {'entity-0', 'entity-1', 'entity-2', 'entity-3'}, 'identity roles')
            ids.extend(str(uuid.UUID(value)) for value in values.values())
    require(len(ids) == len(set(ids)) == 4 * len(paths), 'unique identities')
    rows = [yaml.safe_load(data) for name, data in files.items() if '/observations/' in name]
    expected = {(path, op, case) for path in paths for op in OPERATIONS
                for case, (_, scope) in CASES.items() if scope is not None or path != 'canonical-direct'}
    require(len(rows) == report.get('observationCount') == len(expected) == count, 'observation count')
    seen = set()
    seen_keys = set()
    log = files['server.log'].decode()
    log_rows = Counter(re.findall(r'VSCOPE_ROW\|case=([^|\r\n]+)\|operation=([^|\r\n]+)\|path=([^|\r\n]+)\|matched=true', log))
    require(log.count(f'VSCOPE_SUMMARY|observations={count}|passed=true') == 1 and 'VSCOPE_FAILED|' not in log, 'completion')
    require(log.count('is not a valid variable scope.') == native_diagnostics, 'native diagnostics')
    for row in rows:
        case, op, path = row['case'], row['operation'], row['path']
        identity = (path, op, case)
        require(identity in expected and identity not in seen, 'case coverage')
        seen.add(identity)
        require(log_rows[(case, op, path)] == 1 and row['matched'] is True, 'log correspondence')
        arguments, scope = CASES[case]
        keys = {key.split('/', 1)[1] for key in row['nativeBefore']}
        require(len(keys) == 1, 'seed variable key')
        key = keys.pop()
        require(re.fullmatch(r'ScopeAudit\d+' + (r'\.Part' if case.startswith('dotted-key-') else ''), key), 'key format')
        if version == 2:
            require(key not in seen_keys, 'unique observation keys')
            seen_keys.add(key)
        before = {name + '/' + key: 'Integer:' + str(value) for name, value in SEEDS.items()}
        require(row['nativeBefore'] == row['runtimeBefore'] == before, 'registry seeds')
        mechanic, options = OPERATIONS[op]
        source = mechanic + '{' + arguments.replace('{key}', key) + options + '}'
        require(row['source'] == source, 'source case')
        after = dict(before)
        if scope is None:
            require(row.get('mapped') is None and row['parsedScope'] == 'null'
                    and row['nativeResult'] == 'exception:NullPointerException', 'unsupported action')
            require(log.count('Mechanic Line: ' + source) == 1, 'source diagnostic')
        else:
            require(row['parsedScope'] == scope.upper() and row['nativeResult'] == 'SUCCESS', 'parsed scope')
            if path == 'native-direct':
                mapped = source
            else:
                explicit = path == 'canonical-direct' or re.search(r';(?:scope|s)=', arguments, re.I)
                reference = ('var=' + key + ' scope=' + scope) if explicit else 'var=' + ('' if case == 'default' else scope + '.') + key
                mapped = {'set': 'set-variable', 'add': 'variable-add', 'unset': 'variable-unset',
                          'location': 'variable-location-set'}[op] + ' ' + reference
                if op == 'set':
                    mapped += ' type=INTEGER value=' + ('"7"' if path == 'imported' else '7')
                elif op == 'add':
                    mapped += ' value=7'
            require(row.get('mapped') == mapped, 'mapped action')
            field = scope + '/' + key
            if op == 'unset':
                del after[field]
            elif op == 'location':
                after[field] = 'location:integration-world,110.0,170.0,4.0'
            else:
                after[field] = 'Integer:' + str(7 if op == 'set' else SEEDS[scope] + 7)
        if version == 2:
            require(row['runtimeResult'] == ('rejected' if scope is None else 'SUCCESS'), 'runtime result')
            require(row.get('bindingSource') == (source if path == 'native-direct' else row.get('mapped')), 'binding source')
            if scope is None and path == 'native-direct':
                diagnostics = row['bindingDiagnostics']
                require(isinstance(diagnostics, list) and len(diagnostics) == 1
                        and 'invalid Mythic variable reference' in diagnostics[0]
                        and source in diagnostics[0], 'binding rejection diagnostic')
                require(log.count(diagnostics[0]) == 1, 'binding diagnostic log')
            else:
                require(row['bindingDiagnostics'] == [], 'unexpected binding diagnostics')
            if scope is None and path == 'imported':
                require(row['importDiagnostics'] == ['Invalid or unsupported variable name or mutation scope; original line preserved.'],
                        'import rejection diagnostic')
            else:
                require(row['importDiagnostics'] == [], 'unexpected import diagnostics')
        require(row['expectedAfter'] == row['nativeAfter'] == row['runtimeAfter'] == after, 'registry mutation')
    require(seen == expected and sum(log_rows.values()) == count, 'complete log coverage')
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
    print(f'Variable action scope audit passed: {count} observations; observation-only')
