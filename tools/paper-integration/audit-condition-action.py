"""Independent semantic and artifact audit of condition-action observations."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import uuid
import zipfile

import yaml

ROOT = Path(__file__).resolve().parents[2]
ACTIONS = ('REQUIRED', 'CANCEL', 'CAST', 'CASTINSTEAD', 'ORELSECAST', 'POWER', 'LEVEL')
SOURCES = {
    'tools/paper-integration/run-condition-action-fixture.py',
    'tools/paper-integration/run-player-variable-fixture.py',
    'tools/paper-integration/random-spawn-fixture/src/main/java/dev/helstera/test/randomspawn/ConditionActionProbe.java',
    'tools/paper-integration/random-spawn-fixture/src/main/java/dev/helstera/test/randomspawn/VariablePlaceholderAudit.java',
    'helstera-migration/src/main/java/dev/helstera/migration/importer/MythicMobsImporter.java',
    'helstera-migration/src/main/java/dev/helstera/migration/importer/MmConditions.java',
    'helstera-ai/src/main/java/dev/helstera/ai/skill/MythicSkillSpec.java',
    'helstera-ai/src/main/java/dev/helstera/ai/skill/SkillService.java',
    'helstera-api/src/main/java/dev/helstera/api/util/MythicConditionAction.java',
    'helstera-api/src/main/java/dev/helstera/api/behavior/BehaviorContext.java',
    'helstera-api/src/main/java/dev/helstera/api/util/NumericMechanicSpec.java',
    'helstera-ai/src/main/java/dev/helstera/ai/skill/SkillCatalog.java',
    'helstera-ai/src/main/java/dev/helstera/ai/skill/Placeholders.java',
}


def require(value, message):
    if not value:
        raise ValueError(message)


def digest(data):
    return hashlib.sha256(data).hexdigest()


def audit(report, files):
    require(report.get('schemaVersion') == 2, 'schema')
    require(report.get('evidenceMode') == 'observation' and report.get('parityStatus') == 'unassessed', 'evidence boundary')
    require(report.get('exitCode') == 0 and report.get('validationResult') == 'passed', 'result')
    require(report.get('paperJarSha256') == '39bd8c00b9e18de91dcabd3cc3dcfa5328685a53b7187a2f63280c22e2d287b9', 'Paper pin')
    require(report.get('mythicMobsSha256') == '9672457cafc56ff841606bfbb710947329c08720ffc207746b60130d43bf902f', 'native pin')
    for field in ('pluginSha256', 'probeJarSha256'):
        require(re.fullmatch('[0-9a-f]{64}', report.get(field, '')), field)
    require(set(report.get('sourceHashes', {})) == SOURCES, 'source manifest')
    require(all(re.fullmatch('[0-9a-f]{64}', value) for value in report['sourceHashes'].values()), 'source digest')
    require(len(report.get('files', [])) == len(files) == 160, 'archive cardinality')
    require({entry['path'] for entry in report['files']} == set(files), 'file manifest')
    for entry in report['files']:
        require(digest(files[entry['path']]) == entry['sha256'], 'file hash')
    expected_files = {'server.log', 'plugins/MythicMobs/Mobs/conditions.yml'}
    expected_files |= {f'plugins/HelsteraConditionActionProbe/identities-{path}.yml' for path in ('direct', 'imported')}
    expected_files |= {f'plugins/HelsteraConditionActionProbe/observations/{i:04d}.yml' for i in range(156)}
    require(set(files) == expected_files, 'file paths')
    identities = {}
    for path in ('direct', 'imported'):
        values = yaml.safe_load(files[f'plugins/HelsteraConditionActionProbe/identities-{path}.yml'])
        require(set(values) == {f'entity-{i}' for i in range(4)}, 'identity roles')
        identities[path] = {key: str(uuid.UUID(value)) for key, value in values.items()}
    require(len({value for values in identities.values() for value in values.values()}) == 8, 'distinct identities')
    expected = {(path, scope, action, predicate, availability)
                for path in ('direct', 'imported') for scope in ('caster', 'target', 'trigger')
                for action in ACTIONS for predicate in (False, True)
                for availability in (('ready', 'missing', 'rejected') if 'CAST' in action else ('ready',))}
    require(report.get('observationCount') == len(expected) == 156, 'observation count')
    log = files['server.log'].decode()
    require(log.count('CACTION_SUMMARY|observations=156|passed=true') == 1 and 'CACTION_FAILED|' not in log, 'completion')
    require(len(re.findall(r'CACTION_ROW\|id=\d+\|matched=true', log)) == 156, 'row logs')
    seen = set()
    for i in range(156):
        row = yaml.safe_load(files[f'plugins/HelsteraConditionActionProbe/observations/{i:04d}.yml'])
        key = (row['path'], row['scope'], row['action'], row['predicate'], row['availability'])
        require(type(row['predicate']) is bool and key in expected and key not in seen, 'case coverage')
        seen.add(key)
        path, scope, action, predicate, availability = key
        argument = f'conditionchild{i}' if 'CAST' in action else '2' if action in ('POWER', 'LEVEL') else ''
        source = 'burning ' + action + (' ' + argument if argument else '')
        require(row['source'] == source, 'source')
        mapped = source
        if path == 'imported':
            mapped = 'on-fire' if action == 'REQUIRED' else '!on-fire' if action == 'CANCEL' else (
                'condition-action ' + action + ' ' + b'on-fire'.hex() + ' ' + argument.encode().hex())
        require(row['mapped'] == mapped, 'mapped action')
        role = {'caster': 'entity-0', 'target': 'entity-1', 'trigger': 'entity-2'}[scope]
        require(row['subject'] == identities[path][role], 'condition subject')
        passed = (predicate if action in ('REQUIRED', 'ORELSECAST') else not predicate
                  if action in ('CANCEL', 'CASTINSTEAD') else True)
        child = int(availability == 'ready' and (predicate if action in ('CAST', 'CASTINSTEAD')
                    else not predicate if action == 'ORELSECAST' else False))
        power = 2.0 if action == 'POWER' and predicate else 1.0
        require(row['nativePassed'] is passed and row['runtimePassed'] is passed, 'continuation decision')
        require(row['nativeChildren'] == row['runtimeChildren'] == child, 'branch invocation')
        require(row['runtimeParent'] == int(passed), 'parent execution')
        require(row['nativePower'] == row['runtimePower'] == power, 'power')
        require(row['nativeChildTargets'] == ['targets=1'] * child, 'branch metadata')
        require(row['previewUntouched'] is True and row['matched'] is True, 'preview isolation')
        require(len(re.findall(rf'CACTION_ROW\|id={i}\|matched=true(?:\r?\n|$)', log)) == 1, 'row correspondence')
    require(seen == expected, 'complete coverage')


def load(path):
    report = json.loads(path.read_text())
    archive = path.parent / report['logArchive']
    require(archive.parent == path.parent and digest(archive.read_bytes()) == report['logArchiveSha256'], 'archive hash')
    with zipfile.ZipFile(archive) as zipped:
        require(len(zipped.namelist()) == len(set(zipped.namelist())), 'duplicate archive entries')
        files = {name: zipped.read(name) for name in zipped.namelist()}
    return report, files


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('report', type=Path)
    parser.add_argument('--check-current', action='store_true')
    args = parser.parse_args()
    report, files = load(args.report)
    audit(report, files)
    if args.check_current:
        require(digest((ROOT / 'helstera-plugin/target/helsteraMobs-1.0.0-full.jar').read_bytes()) == report['pluginSha256'], 'current plugin')
        for path, expected in report['sourceHashes'].items():
            require(digest((ROOT / path).read_bytes()) == expected, 'current source: ' + path)
    print('condition-action audit passed: 156 observations')
