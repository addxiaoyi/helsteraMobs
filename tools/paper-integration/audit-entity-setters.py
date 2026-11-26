"""Independent exact-case audit of public setter observations; no parity promotion."""
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import re
import zipfile
import yaml

ROOT = Path(__file__).resolve().parents[2]
SOURCE_FILES = {
    'tools/paper-integration/run-entity-setters-fixture.py',
    'tools/paper-integration/audit-entity-setters.py',
    'tools/paper-integration/run-player-variable-fixture.py',
    'tools/paper-integration/random-spawn-fixture/src/main/java/dev/helstera/test/randomspawn/EntitySettersProbe.java',
    'tools/paper-integration/random-spawn-fixture/src/main/java/dev/helstera/test/randomspawn/VariablePlaceholderAudit.java',
    'helstera-api/src/main/java/dev/helstera/api/util/EntitySetterSpec.java',
    'helstera-api/src/main/java/dev/helstera/api/behavior/BehaviorContext.java',
    'helstera-runtime/src/main/java/dev/helstera/runtime/instance/PaperPathfindingMalus.java',
    'helstera-api/src/main/java/dev/helstera/api/util/MythicPathfindingMalus.java',
    'helstera-ai/src/main/java/dev/helstera/ai/skill/EntitySetterActions.java',
    'helstera-ai/src/main/java/dev/helstera/ai/skill/Placeholders.java',
    'helstera-ai/src/main/java/dev/helstera/ai/skill/SkillService.java',
    'helstera-ai/src/main/java/dev/helstera/ai/skill/SkillExtras.java',
    'helstera-ai/src/main/java/dev/helstera/ai/skill/MythicSkillSpec.java',
    'helstera-migration/src/main/java/dev/helstera/migration/importer/MythicMobsImporter.java',
    'helstera-migration/src/main/java/dev/helstera/migration/importer/MythicSpec.java',
}
# source, exact imported binding, seed, independently expected result
CASES = {
    'malus-value': ('setmalus{type=WATER;amount=-1} @trigger', 'set-pathfinding-malus{malus="WATER";value="-1.0"} @trigger', 8.0, -1.0),
    'malus-default': ('malus{} @trigger', 'set-pathfinding-malus{malus="OPEN";value="0.0"} @trigger', 5.0, 0.0),
    'leash-set': ('setleasher{} @trigger', 'set-leash-holder{remove="false"} @trigger', 'none', 'recipient'),
    'leash-remove': ('setleashholder{r=true} @trigger', 'set-leash-holder{remove="true"} @trigger', 'recipient', 'none'),
    'chunk-enable': ('setchunkforceloaded{} @origin', 'set-chunk-force-loaded{loaded="true"} @origin', False, True),
    'chunk-disable': ('setchunkforceloaded{l=false} @origin', 'set-chunk-force-loaded{loaded="false"} @origin', True, False),
    'text-placeholder': ('settext{t=<red>Y=<target.l.y></red>} @trigger', 'set-text-display{text="<red>Y=<target.l.y></red>"} @trigger', ['', 'none'], ['Y=170.0', '#ff5555']),
    'text-clear': ('settextdisplay{} @trigger', 'set-text-display @trigger', ['before', 'none'], ['', 'none']),
    'item-set': ('setdisplayentityitem{mat=DIAMOND} @trigger', 'set-display-entity-item{item="DIAMOND"} @trigger', 'STONE', 'DIAMOND'),
    'item-clear': ('setdisplayentityitem{} @trigger', 'set-display-entity-item{item="AIR"} @trigger', 'STONE', 'AIR'),
    'interaction-placeholder': ('interactionsize{h=<target.l.y>;w=0} @trigger', 'set-interaction-size{height="<target.l.y>";width="0"} @trigger', [2.0, 3.0], [170.0, 0.0]),
    'interaction-static': ('interactionsize{h=4.5;w=2.25} @trigger', 'set-interaction-size{height="4.5";width="2.25"} @trigger', [2.0, 3.0], [4.5, 2.25]),
    'interaction-default': ('setinteractionsize{} @trigger', 'set-interaction-size{height="1";width="1"} @trigger', [2.0, 3.0], [1.0, 1.0]),
    'interaction-negative': ('setinteractionsize{h=-2;w=-3} @trigger', 'set-interaction-size{height="-2";width="-3"} @trigger', [2.0, 3.0], [-2.0, -3.0]),
    'raider-can': ('setcanjoinraid{c=false} @trigger', 'set-raider-can-join-raid{bool="false"} @trigger', True, False),
    'raider-leader': ('setraiderleader{} @trigger', 'set-raider-patrol-leader{bool="true"} @trigger', False, True),
    'raider-patrol': ('setraiderblock{b=@selflocation} @trigger', 'set-raider-patrol-block{location="@selflocation"} @trigger', 'integration-world:122,170,0', 'integration-world:110,170,0'),
    'wrong-entity': ('setraiderleader{} @trigger', 'set-raider-patrol-leader{bool="true"} @trigger', False, False),
    'invalid-dimension': ('setinteractionsize{w=Infinity} @trigger', None, [2.0, 3.0], [2.0, 3.0]),
    'unknown-field': ('setinteractionsize{unknown=9} @trigger', None, [2.0, 3.0], [2.0, 3.0]),
    'missing-patrol': ('setraiderblock @trigger', None, [2.0, 3.0], [2.0, 3.0]),
}


def require(value, message):
    if not value:
        raise ValueError(message)


def digest(value):
    return hashlib.sha256(value).hexdigest()


def exact(actual, expected):
    # Python considers False == 0; the evidence schema must preserve the type.
    return type(actual) is type(expected) and (all(exact(a, b) for a, b in zip(actual, expected))
        and len(actual) == len(expected) if isinstance(expected, list) else actual == expected)


def audit(report, files):
    require(type(report.get('schemaVersion')) is int and report['schemaVersion'] == 1, 'schema')
    require(report.get('evidenceMode') == 'observation' and report.get('parityStatus') == 'unassessed', 'boundary')
    require(report.get('validationResult') == 'passed' and report.get('exitCode') == 0, 'result')
    require(report.get('paperJarSha256') == '39bd8c00b9e18de91dcabd3cc3dcfa5328685a53b7187a2f63280c22e2d287b9', 'Paper pin')
    require(report.get('mythicMobsSha256') == '9672457cafc56ff841606bfbb710947329c08720ffc207746b60130d43bf902f', 'native pin')
    for key in ('pluginSha256', 'probeJarSha256'):
        require(re.fullmatch('[0-9a-f]{64}', report.get(key, '')), key)
    require(set(report.get('sourceHashes', {})) == SOURCE_FILES, 'source coverage')
    require(all(re.fullmatch('[0-9a-f]{64}', value) for value in report['sourceHashes'].values()), 'source hashes')
    expected_paths = {'server.log', 'plugins/MythicMobs/Mobs/location.yml', 'plugins/HelsteraEntitySettersProbe/observations.yml'}
    require(set(files) == expected_paths, 'archive paths')
    manifest = report.get('files', [])
    require(len(manifest) == 3 and {item['path'] for item in manifest} == expected_paths, 'manifest')
    for item in manifest:
        require(digest(files[item['path']]) == item['sha256'], 'file digest')
    mob = yaml.safe_load(files['plugins/MythicMobs/Mobs/location.yml'])
    require(mob == {'HelsteraRadiusAuditMob': {'Type': 'COW', 'Options': {'NoAI': True, 'Despawn': 'PERSISTENT'}}}, 'native caster fixture')
    log = files['server.log'].decode()
    require(log.count('SETTERS_SUMMARY|observations=42|passed=true') == 1 and 'SETTERS_FAILED|' not in log, 'completion')
    logged = Counter(re.findall(r'SETTERS_ROW\|path=(direct|imported)\|case=([^|\r\n]+)\|matched=true', log))
    observations = yaml.safe_load(files['plugins/HelsteraEntitySettersProbe/observations.yml'])
    require(set(observations) == {'observations'}, 'observation schema')
    rows = observations['observations']
    require(len(rows) == report.get('observationCount') == 42, 'count')
    seen = set()
    for row in rows:
        require(set(row) == {'path', 'case', 'source', 'binding', 'before', 'nativeResult', 'nativeAfter',
                            'runtimeAfter', 'expected', 'rejected', 'matched', 'nativeExpected', 'difference'}, 'row schema')
        key = (row['path'], row['case'])
        require(key[0] in ('direct', 'imported') and key[1] in CASES and key not in seen, 'case identity')
        seen.add(key)
        source, imported, before, expected = CASES[key[1]]
        invalid = imported is None
        premium = key[1] == 'interaction-placeholder'
        native_expected = [1.0, 0.0] if premium else expected
        require(exact(row['nativeExpected'], native_expected), 'native expectation')
        require(row['difference'] == ('public-placeholder-float-premium-gate' if premium else 'none'), 'difference boundary')
        require(row['source'] == source, 'case source')
        binding = source if key[0] == 'direct' else 'preserved-unsupported' if invalid else imported
        require(row['binding'] == binding, 'binding source')
        require(exact(row['before'], before), 'seed')
        require(exact(row['runtimeAfter'], expected) and exact(row['expected'], expected), 'runtime outcome')
        require(row['matched'] is True and row['rejected'] is invalid, 'rejection status')
        require(logged[key] == 1, 'log correspondence')
        if invalid:
            require(row['nativeResult'] == row['nativeAfter'] == 'not-invoked-invalid', 'invalid native boundary')
        else:
            require(row['nativeResult'] == 'SUCCESS' and exact(row['nativeAfter'], native_expected), 'native outcome')
    require(seen == {(path, case) for path in ('direct', 'imported') for case in CASES}, 'full coverage')
    return {'observations': 42, 'matchingNativeComparisons': 34, 'explicitDifferences': 2, 'invalidControls': 6, 'parityStatus': 'unassessed'}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('report', type=Path)
    parser.add_argument('--check-current-sources', action='store_true')
    args = parser.parse_args()
    report = json.loads(args.report.read_text())
    archive = args.report.parent / report['logArchive']
    require(archive.name == report['logArchive'], 'archive traversal')
    require(digest(archive.read_bytes()) == report['logArchiveSha256'], 'archive hash')
    with zipfile.ZipFile(archive) as packed:
        require(len(packed.namelist()) == len(set(packed.namelist())), 'duplicate archive entries')
        files = {name: packed.read(name) for name in packed.namelist()}
    result = audit(report, files)
    if args.check_current_sources:
        for name, expected in report['sourceHashes'].items():
            require(digest((ROOT / name).read_bytes()) == expected, 'current source drift: ' + name)
    print(json.dumps(result, sort_keys=True))


if __name__ == '__main__':
    main()
