"""Independent audit of Creature target location selection observations; no parity promotion."""
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
REGISTRIES = ['skill', 'caster', 'target', 'trigger', 'ai-target', 'changed-target', 'world', 'other-world', 'global']
PATHS = ['native-direct', 'imported']
DISPATCHES = ['entity', 'location']
# Original arguments, normalized selector, target state at dispatch, raw deadline.
CASES = {
    'target': ('value=@target', '@target', 'first', 1),
    'target-short': ('value=@T', '@target', 'first', 1),
    'target-location': ('value=@targetlocation', '@targetlocation', 'first', 1),
    'location-alias': ('value=@targetLoc', '@targetlocation', 'first', 1),
    'location-short': ('value=@TL', '@targetlocation', 'first', 1),
    'target-changed': ('value=@target', '@target', 'changed', 1),
    'location-changed': ('value=@targetlocation', '@targetlocation', 'changed', 1),
    'target-cleared': ('value=@target', '@target', 'missing', 1),
    'location-cleared': ('value=@targetlocation', '@targetlocation', 'missing', 1),
    'target-alias-priority': ('val=@origin;value=@target', '@target', 'first', 1),
    'location-duplicate': ('value=@target;VALUE=@targetlocation', '@targetlocation', 'missing', 1),
    'saved-location': ('value=@targetlocation;save=true', '@targetlocation', 'changed', 0),
}
POSITIONS = {
    'caster': 'Location:integration-world,110.0,170.0,0.0,20.0,10.0',
    'recipient': 'Location:integration-world,110.0,170.0,4.0,70.0,15.0',
    'trigger': 'Location:integration-world,114.0,170.0,0.0,120.0,25.0',
    'ai-target': 'Location:integration-world,118.0,170.0,8.0,-25.0,32.0',
    'changed-target': 'Location:integration-world,122.0,171.0,10.0,-65.0,-22.0',
    'origin': 'Location:integration-world,130.0,174.0,12.0,160.0,35.0',
    'outer': 'Location:integration-world,140.0,178.0,16.0,210.0,45.0',
}
SOURCE_FILES = {
    'tools/paper-integration/run-variable-location-target-fixture.py',
    'tools/paper-integration/run-player-variable-fixture.py',
    'tools/paper-integration/audit-variable-location-target.py',
    'tools/paper-integration/test-variable-location-target-audit.py',
    'tools/paper-integration/random-spawn-fixture/src/main/java/dev/helstera/test/randomspawn/VariableLocationTargetProbe.java',
    'tools/paper-integration/random-spawn-fixture/src/main/java/dev/helstera/test/randomspawn/VariablePlaceholderAudit.java',
    'helstera-migration/src/main/java/dev/helstera/migration/importer/MythicMobsImporter.java',
    'helstera-migration/src/main/java/dev/helstera/migration/importer/MythicSpec.java',
    'helstera-ai/src/main/java/dev/helstera/ai/skill/MythicSkillSpec.java',
    'helstera-ai/src/main/java/dev/helstera/ai/skill/SkillService.java',
    'helstera-ai/src/main/java/dev/helstera/ai/skill/SkillExtras.java',
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
    require(len(report['files']) == len(files) == 244, 'archive cardinality')
    require({item['path'] for item in report['files']} == set(files), 'file manifest')
    for item in report['files']:
        require(digest(files[item['path']]) == item['sha256'], 'file digest: ' + item['path'])
    prefix = 'plugins/HelsteraVariableLocationTargetProbe/'
    identity_paths = {prefix + 'identities-' + path + '.yml' for path in PATHS}
    observation_paths = {prefix + 'observations/' + f'{i:04d}.yml' for i in range(240)}
    require(set(files) == identity_paths | observation_paths | {'server.log', 'plugins/MythicMobs/Mobs/location.yml'}, 'archive paths')
    ids = []
    identities = {}
    for name in identity_paths:
        values = yaml.safe_load(files[name])
        require(set(values) == {'entity-' + str(i) for i in range(6)}, 'identity roles')
        identities[name.rsplit('identities-', 1)[1][:-4]] = values
        ids.extend(str(uuid.UUID(value)) for value in values.values())
    require(len(ids) == len(set(ids)) == 12, 'unique identities')
    rows = [yaml.safe_load(files[name]) for name in sorted(observation_paths)]
    expected = {(path, dispatch, scope, case) for path in PATHS for dispatch in DISPATCHES for scope in SCOPES for case in CASES}
    require(len(rows) == report.get('observationCount') == len(expected) == 240, 'observation count')
    log = files['server.log'].decode()
    require(log.count('VLOCATION_TARGET_SUMMARY|observations=240|passed=true') == 1 and 'VLOCATION_TARGET_FAILED|' not in log, 'completion')
    require(log.count('Failed to get variable registry') == 24, 'unavailable registry diagnostics')
    log_rows = Counter(re.findall(r'VLOCATION_TARGET_ROW\|case=([^|\r\n]+)\|scope=([^|\r\n]+)\|dispatch=([^|\r\n]+)\|path=([^|\r\n]+)\|matched=true', log))
    seen, keys = set(), set()
    results = Counter()
    origin_fallbacks = 0
    changed_target_writes = 0
    for index, row in enumerate(rows):
        path, dispatch, scope, case, key = (row[k] for k in ('path', 'dispatch', 'scope', 'case', 'key'))
        identity = (path, dispatch, scope, case)
        require(identity in expected and identity not in seen, 'case coverage')
        seen.add(identity)
        require(key == 'LocationTargetAudit' + str(index) and key not in keys, 'unique observation key')
        keys.add(key)
        require(log_rows[(case, scope, dispatch, path)] == 1 and row['matched'] is True, 'log correspondence')
        arguments, canonical, target_state, deadline = CASES[case]
        require(row['positions'] == POSITIONS, 'distinct source and recipient positions')
        require(row['selected'] == canonical and row['targetState'] == target_state, 'value selection')
        require(row['nativeHasThreatTable'] is False, 'unsupported threat caster')
        require(row['targetBeforeBinding'] == identities[path]['entity-4'], 'target before binding')
        expected_target = 'none' if target_state == 'missing' else identities[path]['entity-5' if target_state == 'changed' else 'entity-4']
        require(row['targetAtDispatch'] == expected_target, 'live AI target at dispatch')
        before = {candidate + '/' + key: 'Integer:' + str(10 + i) for i, candidate in enumerate(REGISTRIES)}
        before_deadlines = {field: 1 for field in before}
        require(row['nativeBefore'] == row['runtimeBefore'] == before, 'registry seeds')
        require(row['nativeBeforeDeadlines'] == row['runtimeBeforeDeadlines'] == before_deadlines, 'seed deadlines')
        source = 'setvariablelocation{var=' + scope + '.' + key + (';' + arguments if arguments else '') + '}'
        require(row['source'] == source, 'source case')
        if path == 'native-direct':
            mapped = source
        else:
            mapped = 'variable-location-set var=' + scope + '.' + key + ' value=' + canonical
            mapped += ' save=true' if case == 'saved-location' else ''
        require(row['mapped'] == row['bindingSource'] == mapped, 'binding source')
        unavailable_registry = dispatch == 'location' and scope == 'target'
        missing_entity = target_state == 'missing' and canonical == '@target'
        result = 'INVALID_CONFIG' if unavailable_registry else 'exception:NullPointerException' if missing_entity else 'SUCCESS'
        require(row['nativeResult'] == result and row['runtimeResult'] == 'dispatched', 'explicit dispatch results')
        require(row['bindingDiagnostics'] == [], 'unexpected binding diagnostics')
        require(type(row['nativeDurationMillis']) is int and row['nativeDurationMillis'] == deadline, 'native duration selection')
        results[result] += 1
        stored, stored_deadlines = dict(before), dict(before_deadlines)
        field = scope + '/' + key
        writes = not unavailable_registry and not missing_entity
        if writes:
            role = 'origin' if target_state == 'missing' else 'changed-target' if target_state == 'changed' else 'ai-target'
            origin_fallbacks += role == 'origin'
            changed_target_writes += role == 'changed-target'
            stored[field] = POSITIONS[role]
            stored_deadlines[field] = deadline
        require(row['nativeStoredAfter'] == stored and row['nativeStoredAfterDeadlines'] == stored_deadlines, 'raw native write')
        after, after_deadlines = dict(stored), dict(stored_deadlines)
        require(row['nativeAfter'] == row['runtimeAfter'] == after, 'live registry mutation')
        require(row['nativeAfterDeadlines'] == row['runtimeAfterDeadlines'] == after_deadlines, 'live deadlines')
        for name in ('nativeBeforeDeadlines', 'runtimeBeforeDeadlines', 'nativeStoredAfterDeadlines', 'nativeAfterDeadlines', 'runtimeAfterDeadlines'):
            require(all(type(value) is int for value in row[name].values()), 'deadline types')
    require(seen == expected and sum(log_rows.values()) == 240, 'complete coverage')
    require(results == {'SUCCESS': 198, 'INVALID_CONFIG': 24, 'exception:NullPointerException': 18}, 'native result coverage')
    require(origin_fallbacks == 36, 'origin fallback coverage')
    require(changed_target_writes == 54, 'changed target coverage')
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
    print(f'Variable location target audit passed: {count} observations; 24 unavailable registries, 18 missing-target exceptions, 36 origin fallbacks, 54 changed-target writes; observation-only')
