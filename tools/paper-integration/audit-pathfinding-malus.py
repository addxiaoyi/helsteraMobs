"""Independent strict audit of pinned malus writes, rejection and restart observations."""
import argparse
from datetime import datetime
import hashlib
import json
import math
from pathlib import Path
import re
import uuid
import zipfile
import yaml

ROOT = Path(__file__).resolve().parents[2]
PATH_TYPES = ('BLOCKED OPEN WALKABLE WALKABLE_DOOR TRAPDOOR POWDER_SNOW DANGER_POWDER_SNOW '
              'FENCE LAVA WATER WATER_BORDER RAIL UNPASSABLE_RAIL DANGER_FIRE DAMAGE_FIRE '
              'DANGER_OTHER DAMAGE_OTHER DOOR_OPEN DOOR_WOOD_CLOSED DOOR_IRON_CLOSED BREACH '
              'LEAVES STICKY_HONEY COCOA DAMAGE_CAUTIOUS DANGER_TRAPDOOR').split()
COSTS = {name: {'WATER': -1.0, 'DANGER_FIRE': 0.0, 'DAMAGE_FIRE': 12.5}.get(name, index + 1.25)
         for index, name in enumerate(PATH_TYPES)}
SOURCE_FILES = {
    'tools/paper-integration/run-pathfinding-malus-fixture.py',
    'tools/paper-integration/run-player-variable-fixture.py',
    'tools/paper-integration/random-spawn-fixture/src/main/java/dev/helstera/test/randomspawn/PathfindingMalusProbe.java',
    'helstera-migration/src/main/java/dev/helstera/migration/importer/MythicMobsImporter.java',
    'helstera-api/src/main/java/dev/helstera/api/util/MythicPathfindingMalus.java',
    'helstera-runtime/src/main/java/dev/helstera/runtime/instance/PaperPathfindingMalus.java',
    'helstera-plugin/src/main/java/dev/helstera/plugin/HelsteraPlugin.java',
}
PREFIX = 'plugins/HelsteraPathfindingMalusProbe/'
REQUIRED_FILES = {
    'plugins/MythicMobs/Mobs/pathfinding-malus.yml',
    PREFIX + 'write-observations.yml', PREFIX + 'verify-observations.yml', PREFIX + 'identities.yml',
    'write.server.log', 'verify.server.log',
    'plugins/helsteraMobs/mobs/mythic_malusnative.yml', 'plugins/helsteraMobs/mobs/mythic_malusmap.yml',
}
WRITE_CONTROLS = {'map-preserved': True, 'native-map-ignored': 8.0, 'unsupported-entity': False,
                  'invalid-runtime-rejected': False, 'invalid-runtime-no-partial-write': -1.0,
                  'fire-rejected': False, 'version-gate': False}


def require(value, message):
    if not value:
        raise ValueError(message)


def digest(data):
    return hashlib.sha256(data).hexdigest()


def equal(actual, expected):
    if isinstance(expected, bool):
        return type(actual) is bool and actual is expected
    return type(actual) in (int, float) and math.isfinite(actual) and actual == expected


def validate_costs(values, label):
    require(isinstance(values, dict) and set(values) == set(COSTS), label + ' PathTypes')
    require(all(equal(values[name], value) for name, value in COSTS.items()), label + ' cost values')


def audit(report, files):
    require(type(report.get('schemaVersion')) is int and report['schemaVersion'] == 1, 'schema')
    require(report.get('evidenceMode') == 'observation' and report.get('parityStatus') == 'unassessed', 'evidence boundary')
    require(report.get('validationResult') == 'passed', 'fixture result')
    require(re.fullmatch(r'srv-paper-pathfinding-malus-\d{8}-r[1-9]\d*', report.get('fixtureDirectory', '')), 'fixture directory')
    generated = datetime.fromisoformat(report.get('generatedAtUtc', ''))
    require(generated.tzinfo is not None, 'timestamp timezone')
    require(isinstance(report.get('limitations'), list) and len(report['limitations']) >= 3
            and all(isinstance(text, str) and text.strip() for text in report['limitations']), 'limitations')
    require(report.get('paperJarSha256') == '39bd8c00b9e18de91dcabd3cc3dcfa5328685a53b7187a2f63280c22e2d287b9', 'Paper pin')
    require(report.get('mythicMobsSha256') == '9672457cafc56ff841606bfbb710947329c08720ffc207746b60130d43bf902f', 'native pin')
    for key in ('pluginSha256', 'probeJarSha256', 'logArchiveSha256'):
        require(re.fullmatch('[0-9a-f]{64}', report.get(key, '')), key)
    require(set(report.get('sourceHashes', {})) == SOURCE_FILES, 'source coverage')
    require(all(re.fullmatch('[0-9a-f]{64}', value) for value in report['sourceHashes'].values()), 'source hashes')
    stages = report.get('stages')
    require(type(stages) is list and len(stages) == 2, 'two stages')
    for stage, name, count in zip(stages, ('write', 'verify'), (59, 27)):
        require(set(stage) == {'stage', 'exitCode', 'observationCount'} and stage['stage'] == name
                and type(stage['exitCode']) is int and stage['exitCode'] == 0
                and type(stage['observationCount']) is int and stage['observationCount'] == count,
                'two successful ordered stages')
    require(set(files) == REQUIRED_FILES, 'archive files')
    manifest = report.get('files', [])
    require(len(manifest) == len(REQUIRED_FILES) and {item['path'] for item in manifest} == REQUIRED_FILES, 'file manifest')
    for item in manifest:
        require(set(item) == {'path', 'sha256'} and digest(files[item['path']]) == item['sha256'], 'file digest')
    source = yaml.safe_load(files['plugins/MythicMobs/Mobs/pathfinding-malus.yml'])
    require(set(source) == {'MalusNative', 'MalusMap'}, 'source mobs')
    native = source['MalusNative']
    require(native['Type'] == 'COW' and native['Options'] == {'NoAI': True, 'NoGravity': True, 'Despawn': 'PERSISTENT'}, 'native options')
    native_lines = native['AIPathfindingMalus']
    require(type(native_lines) is list and len(native_lines) == 26, 'native list format')
    parsed = {}
    for line in native_lines:
        require(type(line) is str and len(line.split()) == 2, 'native cost syntax')
        name, value = line.split()
        require(name not in parsed, 'duplicate native PathType')
        parsed[name] = float(value)
    validate_costs(parsed, 'native input')
    require(source['MalusMap'] == {'Type': 'COW', 'Options': {'NoAI': True, 'NoGravity': True},
                                  'AIPathfindingMalus': {'WATER': -1, 'FIRE': 0}}, 'unsupported map source')
    imported = yaml.safe_load(files['plugins/helsteraMobs/mobs/mythic_malusnative.yml'])
    validate_costs(imported.get('ai', {}).get('pathfinding-malus'), 'imported runtime')
    require(imported.get('model') == 'example/emberling', 'fixture model')
    map_mob = yaml.safe_load(files['plugins/helsteraMobs/mobs/mythic_malusmap.yml'])
    require('pathfinding-malus' not in map_mob.get('ai', {}), 'unsupported map must not mutate runtime costs')
    require(map_mob.get('mob', {}).get('unsupported', {}).get('AIPathfindingMalus') == {'WATER': -1, 'FIRE': 0}, 'map source preservation')
    identity = yaml.safe_load(files[PREFIX + 'identities.yml'])
    require(set(identity) == {'runtime', 'costs'}, 'restart identity schema')
    require(str(uuid.UUID(identity['runtime'])) == identity['runtime'], 'restart entity UUID')
    validate_costs(identity['costs'], 'restart expected costs')
    total = 0
    for stage, count in (('write', 59), ('verify', 27)):
        content = yaml.safe_load(files[PREFIX + stage + '-observations.yml'])
        require(set(content) == {'stage', 'observations'} and content['stage'] == stage, 'snapshot stage')
        log = files[stage + '.server.log'].decode()
        require(log.count(f'MALUS_SUMMARY|stage={stage}|observations={count}|passed=true') == 1
                and 'MALUS_FAILED|' not in log, 'stage completion')
        expected = ({prefix + name: value for prefix in ('native-', 'imported-') for name, value in COSTS.items()}
                    | WRITE_CONTROLS if stage == 'write' else
                    {'restart-' + name: value for name, value in COSTS.items()} | {'saved-entity-rebound': True})
        rows = content['observations']
        require(type(rows) is list and len(rows) == count == len(expected), 'observation count')
        seen = set()
        for row in rows:
            require(set(row) == {'id', 'expected', 'actual', 'matched'}, 'row schema')
            name = row['id']
            require(name in expected and name not in seen, 'required unique observation id')
            seen.add(name)
            require(row['matched'] is True and equal(row['expected'], expected[name])
                    and equal(row['actual'], expected[name]), 'independent expected value: ' + name)
        require(seen == set(expected), 'complete observations')
        total += len(rows)
    return {'observations': total, 'nativeCosts': 26, 'importedCosts': 26, 'restoredCosts': 26,
            'rejectionAndIntegrityControls': 8, 'parityStatus': 'unassessed',
            'boundary': 'Prevalidation rejects invalid costs without partial write; simulated setter-failure rollback remains unit evidence.'}


def load_report(report_path):
    report = json.loads(report_path.read_text())
    archive_name = report['logArchive']
    require(isinstance(archive_name, str) and Path(archive_name).name == archive_name, 'archive traversal')
    archive = report_path.parent / archive_name
    require(digest(archive.read_bytes()) == report['logArchiveSha256'], 'archive hash')
    with zipfile.ZipFile(archive) as packed:
        require(len(packed.namelist()) == len(set(packed.namelist())), 'duplicate archive entry')
        files = {name: packed.read(name) for name in packed.namelist()}
    return report, files


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('report', type=Path)
    parser.add_argument('--check-current-sources', action='store_true')
    args = parser.parse_args()
    report, files = load_report(args.report)
    result = audit(report, files)
    if args.check_current_sources:
        for name, expected in report['sourceHashes'].items():
            require(digest((ROOT / name).read_bytes()) == expected, 'current source drift: ' + name)
    print(json.dumps(result, sort_keys=True))


if __name__ == '__main__':
    main()
