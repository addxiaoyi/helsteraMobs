"""Independent expectations for dropped Item selection, real mutations and unchanged chunk loading."""
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
    'tools/paper-integration/' + name for name in (
        'run-item-targeter-fixture.py', 'run-player-variable-fixture.py',
        'audit-item-targeter.py', 'test-item-targeter-audit.py', 'item-targeter-client.js',
        'random-spawn-fixture/src/main/java/dev/helstera/test/randomspawn/ItemTargeterProbe.java',
        'random-spawn-fixture/src/main/java/dev/helstera/test/randomspawn/VariablePlaceholderAudit.java')
} | {
    'helstera-api/src/main/java/dev/helstera/api/behavior/' + name for name in (
        'Targeter.java', 'Targeters.java', 'DroppedItemTargeter.java', 'NamedPlayerTargeter.java',
        'InheritedTargetLocations.java', 'BehaviorContext.java', 'SkillTreeContext.java',
        'ManagedMobIdentity.java', 'MobRelations.java', 'Factions.java', 'FactionTable.java')
} | {
    'helstera-ai/src/main/java/dev/helstera/ai/skill/SkillService.java',
    'helstera-ai/src/main/java/dev/helstera/ai/skill/Placeholders.java',
    'helstera-ai/src/main/java/dev/helstera/ai/skill/SkillExtras.java',
    'helstera-ai/src/main/java/dev/helstera/ai/AiManager.java',
    'helstera-migration/src/main/java/dev/helstera/migration/importer/MythicMobsImporter.java',
}
DATA = 'plugins/HelsteraItemTargeterProbe/observations.yml'
TAG = 'helstera_item_targeter_probe'
BASE = ['far', 'near', 'negative']
BOX = ['beyond', 'edge', 'far', 'near', 'negative']
SELF = ['beyond', 'edge', 'far', 'negative']
# A single member is intentionally unspecified for native HashSet health ties/NONE and RANDOM.
ONE_OF_BASE = {'members': BASE, 'count': 1}
CASES = {
    'iir-default': ('@itemsInRadius', BASE), 'iir-alias': ('@IIR', BASE),
    'ino-default': ('@itemsNearOrigin', BASE), 'ino-alias': ('@INO', BASE),
    'radius-long': ('@IIR{radius=2}', ['near']), 'radius-short': ('@IIR{r=2}', ['near']),
    'iir-alias-priority': ('@IIR{r=2;radius=5}', BASE),
    'ino-alias-priority': ('@INO{radius=5;r=2}', ['near']),
    'iir-invalid-priority': ('@IIR{radius=bad;r=2}', BASE),
    'ino-invalid-priority': ('@INO{radius=2;r=bad}', ['near']),
    'radius-quoted': ('@IIR{r="2"}', BASE), 'radius-placeholder': ('@IIR{r=<caster.level>}', BASE),
    'radius-repeat': ('@IIR{r=5;r=2}', ['near']), 'radius-zero': ('@IIR{r=0}', []),
    'radius-negative': ('@IIR{r=-2}', ['near']), 'radius-nan': ('@IIR{r=NaN}', []),
    'bounding-box': ('@IIR{bb=true}', BOX), 'bounding-box-alias': ('@IIR{useboundingbox=true}', BOX),
    'bounding-box-priority': ('@IIR{bb=false;useboundingbox=true}', BASE),
    'target-water': ('@IIR{target=water}', []), 'target-flying': ('@IIR{target=flying}', []),
    'target-ground': ('@IIR{target=ground}', BASE), 'target-players': ('@IIR{target=players}', BASE),
    'ignore-type-noop': ('@IIR{ignoretype=ITEM}', BASE), 'unique-zero': ('@IIR{unique=0}', BASE),
    'ignore-vanilla': ('@IIR{ignore=vanilla}', []), 'nonmythic-off': ('@IIR{targetnonmythic=false}', []),
    'vanilla-priority': ('@IIR{targetvanilla=false;targetnonmythic=true}', []),
    'vanilla-override': ('@IIR{ignore=vanilla;targetvanilla=true}', BASE),
    'factionless': ('@IIR{targetsamefaction=false}', ['far', 'near']),
    'ignore-faction': ('@IIR{ignore=faction}', ['far', 'near']),
    'nearest': ('@IIR{sort=NEAREST;limit=1}', ['near']), 'furthest': ('@IIR{sortby=FURTHEST;limit=1}', ['far']),
    'sort-priority': ('@IIR{sort=NEAREST;sortby=FURTHEST;limit=1}', ['near']),
    'sort-unlimited': ('@IIR{sort=FURTHEST}', BASE), 'limit-negative': ('@IIR{sort=FURTHEST;limit=-1}', BASE),
    'limit-invalid': ('@IIR{sort=NEAREST;limit=bad}', ['near']),
    'limit-decimal': ('@IIR{sort=NEAREST;limit=2.9}', ['near']),
    'limit-quoted': ('@IIR{sort=NEAREST;limit="2"}', ['near']),
    'limit-range-fixed': ('@IIR{sort=NEAREST;limit=2to2}', ['near', 'negative']),
    'sort-random': ('@IIR{sort=RANDOM;limit=1}', ONE_OF_BASE),
    'sort-health-high': ('@IIR{sort=HIGHEST_HEALTH;limit=1}', ONE_OF_BASE),
    'sort-health-low': ('@IIR{sort=LOWEST_HEALTH;limit=1}', ONE_OF_BASE),
    'sort-invalid': ('@IIR{sort=bad;limit=1}', ONE_OF_BASE),
    'origin-query': ('@INO', ['origin']), 'origin-independent': ('@IIR', BASE),
    'origin-sort': ('@IIR{sort=NEAREST;limit=1}', ['far']),
    'self-excluded': ('@IIR', SELF), 'self-included': ('@IIR{target=caster}', BOX),
    'self-override': ('@IIR{target=caster;targetself=invalid}', SELF),
    'unloaded-origin': ('@INO', []),
    'chunk-bounding-box': ('@IIR{r=4.9;bb=true}', BOX + ['same-chunk']),
    'negative-reversed': ('@IIR{r=-2}', []),
    'owner-excluded': ('@IIR{targetowner=false}', ['far', 'negative']),
    'owner-override': ('@IIR{ignore=owner;targetowner=true}', BASE),
    'guard-tag': ('@IIR{conditions=[ - hastag{t=eligible} true ]}', BASE),
    'guard-before-limit': ('@IIR{conditions=[ - hastag{t=eligible} true ];sort=NEAREST;limit=1}', ['near']),
    'guard-inverted': ('@IIR{conditions=[ - hastag{t=eligible} false ]}', BASE),
    'guard-caster-negative': ('@IIR{conditions=[ - hastag{t=caster-only} true ]}', BASE),
    'guard-priority': ('@IIR{targetconditions=[ - hastag{t=eligible} true ];conditions=[ - hastag{t=caster-only} true ]}', BASE),
    'guard-type': ('@IIR{conditions=[ - entitytype{t=ITEM} true ]}', BASE),
    'guard-distance': ('@IIR{conditions=[ - distance{d=<2} true ]}', BASE),
    'guard-unsupported-inverted': ('@IIR{conditions=[ - health{h=>0} false ]}', BASE),
    'threat-high': ('@IIR{sort=HIGHEST_THREAT;limit=1}', ['far']),
    'threat-low': ('@IIR{sort=LOWEST_THREAT;limit=1}', ['negative']),
    'threat-unlimited': ('@IIR{sort=HIGHEST_THREAT}', BASE),
}
EXTENSIONS = {
    'guard-tag': ['far'], 'guard-before-limit': ['far'], 'guard-inverted': ['near', 'negative'],
    'guard-caster-negative': [], 'guard-priority': ['far'], 'guard-distance': ['near'],
    'guard-unsupported-inverted': [],
}
POINTS = {'near': (9,180,8), 'far': (12,180,8), 'negative': (6,180,8), 'edge': (13,180,8),
          'beyond': (13.0625,180,8), 'high': (8,185,8), 'same-chunk': (15.984375,180,8),
          'next-chunk': (16.015625,180,8), 'origin': (32,180,8), 'cross-item': (8,180,8)}


def require(value, message):
    if not value:
        raise ValueError(message)


def digest(value):
    return hashlib.sha256(value).hexdigest()


def source(case):
    return f'addtag{{tag={TAG}}} ' + CASES[case][0]


def binding(case):
    head, brace, body = CASES[case][0][1:].partition('{')
    name = {'iir': 'itemsinradius', 'ino': 'itemsnearorigin'}.get(head.lower(), head.lower())
    return f'add-tag {TAG} @' + name + brace + body


def matches(actual, expected):
    if not isinstance(actual, list) or actual != sorted(set(actual)):
        return False
    if isinstance(expected, dict):
        return len(actual) == expected['count'] and set(actual) <= set(expected['members'])
    return actual == expected


def audit(report, files):
    require(report.get('schemaVersion') == 1 and report.get('evidenceMode') == 'observation'
            and report.get('parityStatus') == 'unassessed', 'schema/scope')
    require(report.get('validationResult') == 'passed' and report.get('exitCode') == 0
            and report.get('clientExitCode') == 0, 'process result')
    require(report.get('paperJarSha256') == '39bd8c00b9e18de91dcabd3cc3dcfa5328685a53b7187a2f63280c22e2d287b9', 'Paper pin')
    require(report.get('mythicMobsSha256') == '9672457cafc56ff841606bfbb710947329c08720ffc207746b60130d43bf902f', 'native pin')
    for key in ('pluginSha256', 'probeJarSha256'):
        require(re.fullmatch('[0-9a-f]{64}', report.get(key, '')), key)
    require(set(report.get('sourceHashes', {})) == SOURCE_FILES, 'source coverage')
    require(all(re.fullmatch('[0-9a-f]{64}', value) for value in report['sourceHashes'].values()), 'source hashes')
    expected_files = {'server.log', 'client.log', 'plugins/MythicMobs/Mobs/location.yml', DATA}
    require(set(files) == expected_files, 'archive paths')
    manifest = report.get('files', [])
    require(len(manifest) == len(expected_files) and {item['path'] for item in manifest} == expected_files, 'manifest')
    for item in manifest:
        require(digest(files[item['path']]) == item['sha256'], 'file digest')
    require(yaml.safe_load(files['plugins/MythicMobs/Mobs/location.yml']) == {'HelsteraRadiusAuditMob': {
        'Type': 'COW', 'ThreatTable': {'Enabled': True, 'DecayUnreachable': False},
        'Options': {'NoAI': True, 'Despawn': 'PERSISTENT'}}}, 'mob configuration')
    count = 2 * len(CASES)
    log = files['server.log'].decode()
    require(log.count(f'ITEMS_SUMMARY|observations={count}|passed=true') == 1 and 'ITEMS_FAILED|' not in log, 'completion')
    logged = Counter(re.findall(r'ITEMS_ROW\|path=(direct|imported)\|case=([^|\r\n]+)\|observed=true', log))
    clients = [json.loads(line.split('ITEMS_CLIENT ', 1)[1]) for line in files['client.log'].decode().splitlines() if 'ITEMS_CLIENT ' in line]
    require(any(row.get('event') == 'join' and row.get('name') == 'ItemTester'
                and re.fullmatch('[0-9a-f-]{36}', row.get('uuid', '')) for row in clients), 'client join')
    require(sum(row.get('event') == 'done' and row.get('name') == 'ItemTester' for row in clients) == 1, 'client done')
    require([row for row in clients if row.get('event') == 'summary'] == [{'event': 'summary', 'passed': True}], 'client summary')
    snapshot = yaml.safe_load(files[DATA])
    require(set(snapshot) == {'observations'}, 'snapshot schema')
    rows = snapshot['observations']
    require(len(rows) == report.get('observationCount') == count, 'row count')
    seen = set()
    for row in rows:
        require(set(row) == {'path', 'case', 'selector', 'source', 'binding', 'nativeSelected', 'runtimeSelected',
                            'nativeEffects', 'runtimeEffects', 'nativeAccepted', 'runtimeAccepted', 'loadedChunksAdded', 'entities', 'nativeThreatScores'}, 'row schema')
        key = (row['path'], row['case'])
        require(key[0] in ('direct', 'imported') and key[1] in CASES and key not in seen, 'case identity')
        seen.add(key)
        case = key[1]
        require(row['nativeThreatScores'] == ({'near': 5.0, 'far': 10.0, 'living': 100.0} if case.startswith('threat-') else {}), 'native threat preparation')
        selector, native = CASES[case]
        runtime = EXTENSIONS.get(case, native)
        require(row['selector'] == selector and row['source'] == source(case), 'source contract: ' + case)
        require(row['binding'] == (source(case) if key[0] == 'direct' else binding(case)), 'binding: ' + case)
        for field, expected in [('nativeSelected', native), ('nativeEffects', native),
                                ('runtimeSelected', runtime), ('runtimeEffects', runtime)]:
            require(matches(row[field], expected), f'{case}/{key[0]}/{field}: {row[field]} != {expected}')
        require(row['nativeEffects'] == row['nativeSelected'], 'native mutation recipients')
        if case != 'sort-random':
            require(row['runtimeEffects'] == row['runtimeSelected'], 'runtime mutation recipients')
        require(row['nativeAccepted'] is True and row['runtimeAccepted'] is True, 'accepted')
        require(row['loadedChunksAdded'] == [], 'queries loaded chunks')
        entities = {item['label']: item for item in row['entities']}
        require(len(entities) == len(row['entities']) == 16
                and set(entities) == set(POINTS) | {'caster', 'living', 'other-living', 'cross-living', 'harness-item', 'player'}, 'entity identities')
        require(len({item['uuid'] for item in entities.values()}) == 16
                and all(re.fullmatch('[0-9a-f-]{36}', item['uuid']) for item in entities.values()), 'entity UUIDs')
        for label, point in POINTS.items():
            item = entities[label]
            require(item['type'] == 'ITEM' and all(abs(item[axis] - value) < 1e-7 for axis, value in zip(('x','y','z'), point)), 'item geometry: ' + label)
            require((item['world'] == 'integration-world') == (label != 'cross-item'), 'world isolation')
        require(entities['caster']['type'] == 'COW' and entities['player']['type'] == 'PLAYER', 'negative control entities')
        require(logged[key] == 1, 'log correspondence')
    require(seen == {(path, case) for path in ('direct', 'imported') for case in CASES}, 'case coverage')
    return {'observations': count, 'nativeComparisons': count, 'intentionalDifferences': 2 * len(EXTENSIONS), 'parityStatus': 'unassessed'}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('report', type=Path)
    parser.add_argument('--check-current', action='store_true')
    args = parser.parse_args()
    report = json.loads(args.report.read_text())
    archive = args.report.parent / report['logArchive']
    require(archive.name == report['logArchive'], 'archive traversal')
    require(digest(archive.read_bytes()) == report['logArchiveSha256'], 'archive hash')
    with zipfile.ZipFile(archive) as packed:
        require(len(packed.namelist()) == len(set(packed.namelist())), 'duplicate archive files')
        files = {name: packed.read(name) for name in packed.namelist()}
    result = audit(report, files)
    if args.check_current:
        for name, expected in report['sourceHashes'].items():
            require(digest((ROOT / name).read_bytes()) == expected, 'current source drift: ' + name)
        require(digest((ROOT / 'helstera-plugin/target/helsteraMobs-1.0.0-full.jar').read_bytes()) == report['pluginSha256'], 'current JAR')
    print(json.dumps(result, sort_keys=True))


if __name__ == '__main__':
    main()
