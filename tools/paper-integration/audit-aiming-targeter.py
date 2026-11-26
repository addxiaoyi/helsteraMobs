"""Independent expectations for aiming and inherited selection with real mutations."""
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
        'run-aiming-targeter-fixture.py', 'run-player-variable-fixture.py',
        'audit-aiming-targeter.py', 'test-aiming-targeter-audit.py', 'aiming-targeter-client.js',
        'random-spawn-fixture/src/main/java/dev/helstera/test/randomspawn/AimingTargeterProbe.java',
        'random-spawn-fixture/src/main/java/dev/helstera/test/randomspawn/VariablePlaceholderAudit.java')
} | {
    'helstera-api/src/main/java/dev/helstera/api/behavior/' + name for name in (
        'Targeter.java', 'Targeters.java', 'DroppedItemTargeter.java', 'NamedPlayerTargeter.java',
        'InheritedTargetLocations.java', 'BehaviorContext.java', 'SkillTreeContext.java',
        'AimingInheritedTargeter.java', 'NativeTargeterOptions.java', 'NativeEntityTargetFilters.java', 'ManagedMobIdentity.java', 'MobRelations.java', 'Factions.java', 'FactionTable.java')
} | {
    'helstera-ai/src/main/java/dev/helstera/ai/skill/SkillService.java',
    'helstera-ai/src/main/java/dev/helstera/ai/skill/Placeholders.java',
    'helstera-ai/src/main/java/dev/helstera/ai/skill/SkillExtras.java',
    'helstera-ai/src/main/java/dev/helstera/ai/AiManager.java',
    'helstera-ai/src/main/java/dev/helstera/ai/AiController.java',
    'helstera-migration/src/main/java/dev/helstera/migration/importer/MythicMobsImporter.java',
}
BLOCK_SOURCE_FILES = SOURCE_FILES | {
    'helstera-api/src/main/java/dev/helstera/api/behavior/BlockLocationTargeter.java',
    'helstera-api/src/main/java/dev/helstera/api/behavior/NativeLocationTargetFilters.java',
}
DATA = 'plugins/HelsteraAimingTargeterProbe/observations.yml'
TAG = 'helstera_aiming_targeter_probe'
WORLD = 'integration-world'


def point(x, y=180, z=.25, yaw=0, pitch=0):
    return f'{WORLD}:{x:.6f},{y:.6f},{z:.6f},{yaw:.6f},{pitch:.6f}'


BASE = ['display', 'far', 'item', 'near']
NEAR, FAR = point(2.25), point(8.25)
LOCS = [NEAR, FAR]
CASES = {
    'entity-default': ('@targetedTarget', BASE), 'entity-alias': ('@targeted', BASE),
    'entity-self': ('@targeted{targetself=true}', ['caster'] + BASE),
    'entity-nearest': ('@targeted{sort=NEAREST;limit=1}', ['item']),
    'entity-furthest': ('@targeted{sort=FURTHEST;limit=1}', ['far']),
    'entity-high-health': ('@targeted{sort=HIGHEST_HEALTH;limit=1}', ['far']),
    'entity-low-health': ('@targeted{sort=LOWEST_HEALTH;limit=2}', ['display', 'item']),
    'entity-sort-priority': ('@targeted{sort=NEAREST;sortby=FURTHEST;limit=1}', ['item']),
    'entity-quoted-limit': ('@targeted{sort=NEAREST;limit="2"}', ['item']),
    'entity-fixed-range': ('@targeted{sort=NEAREST;limit=2to2}', ['item', 'near']),
    'entity-sort-unlimited': ('@targeted{sort=FURTHEST}', BASE),
    'entity-ignore-animal': ('@targeted{ignore=animals}', ['display', 'item']),
    'entity-water': ('@targeted{target=water}', []), 'entity-ground': ('@targeted{target=ground}', BASE),
    'entity-ignore-type-noop': ('@targeted{ignoretype=COW}', BASE),
    'entity-nonmythic': ('@targeted{targetvanilla=false}', ['far', 'near']),
    'entity-vanilla-priority': ('@targeted{targetvanilla=false;targetnonmythic=true}', ['far', 'near']),
    'entity-guard': ('@targeted{conditions=[ - hastag{t=eligible} true ]}', BASE),
    'entity-guard-limit': ('@targeted{conditions=[ - hastag{t=eligible} true ];sort=NEAREST;limit=1}', ['item']),
    'entity-caster-guard': ('@targeted{conditions=[ - hastag{t=caster-only} true ]}', BASE),
    'entity-empty': ('@targeted', []),
    'entity-player-survival': ('@targeted', ['item', 'near', 'player']),
    'entity-player-creative': ('@targeted', ['item', 'near', 'player']),
    'entity-player-spectator': ('@targeted', ['item', 'near', 'player']),
    'entity-player-excluded': ('@targeted{targetspectator=false}', ['item', 'near']),
    'entity-stand-marker': ('@targeted{targetarmorstands=true;targetmarkers=true}', ['item', 'marker', 'near', 'player', 'stand']),
    'entity-players': ('@targeted{target=players,spectator}', ['item', 'player']),
    'entity-cross-world': ('@targeted', ['cross', 'far', 'near']),
    'location-default': ('@targetedLocation', LOCS), 'location-plural': ('@targetedLocations', LOCS),
    'location-alias': ('@targetedLoc', LOCS),
    'location-offset': ('@targetedLoc{x=1;y=2;z=3}', [point(3.25, 182, 3.25), point(9.25, 182, 3.25)]),
    'location-alias-priority': ('@targetedLoc{x=1;xoffset=3}', [point(5.25), point(11.25)]),
    'location-quoted': ('@targetedLoc{x="2"}', [point(3.25), point(9.25)]),
    'location-fixed-range': ('@targetedLoc{x=2to2}', [point(4.25), point(10.25)]),
    'location-centered': ('@targetedLoc{centered=true}', [point(2.5, z=.5), point(8.5, z=.5)]),
    'location-centered-priority': ('@targetedLoc{blockcentered=false;centered=true}', LOCS),
    'location-dedup': ('@targetedLoc{coordinatex=5}', [point(5)]),
    'location-zero-coordinate': ('@targetedLoc{cx=0;cy=0}', LOCS),
    'location-yaw-pitch': ('@targetedLoc{cyaw=45;cpitch=30}', [point(2.25, yaw=45, pitch=30), point(8.25, yaw=45, pitch=30)]),
    'location-rotation-noop': ('@targetedLoc{rotatex=45;rotatey=90;rotatez=30}', LOCS),
    'location-nearest': ('@targetedLoc{limit=1;sort=NEAREST}', [NEAR]),
    'location-furthest': ('@targetedLoc{limit=1;sortby=FURTHEST}', [FAR]),
    'location-sort-priority': ('@targetedLoc{limit=1;sort=NEAREST;sortby=FURTHEST}', [NEAR]),
    'location-quoted-limit': ('@targetedLoc{limit="1";sort=NEAREST}', LOCS),
    'location-ignore': ('@targetedLoc{blockignores=AIR}', []),
    'location-allow': ('@targetedLoc{blocktypes=AIR}', LOCS),
    'location-prefix': ('@targetedLoc{blocktypes=@AI}', LOCS),
    'location-no-match': ('@targetedLoc{blocktypes=STONE}', []),
    'location-empty': ('@targetedLoc', []), 'location-not-entity-projection': ('@targetedLoc', []),
    'block-default': ('@targetBlock', [point(0, 181, 6)]),
    'block-air-only': ('@targetBlock{it=false}', [point(0, 181, 3)]),
    'block-transparent-priority': ('@targetBlock{ignoreTransparent=true;it=false}', [point(0, 181, 6)]),
    'block-distance': ('@targetBlock{d=2}', [point(0, 181, 2)]),
    'block-distance-priority': ('@targetBlock{d=2;distance=4;max=5;maxdistance=8}', [point(0, 181, 6)]),
    'block-distance-invalid': ('@targetBlock{maxdistance=bad;d=2}', [point(0, 181, 6)]),
    'block-distance-quoted': ('@targetBlock{maxdistance="2"}', [point(0, 181, 6)]),
    'block-offset': ('@targetBlock{y=2;centered=true}', [point(.5, 183, 6.5)]),
    'block-filter': ('@targetBlock{blocktypes=STONE}', [point(0, 181, 6)]),
    'block-filter-reject': ('@targetBlock{blockignores=STONE}', []),
    'block-miss': ('@targetBlock{d=8}', [point(0, 181, 8)]),
    'block-miss-default': ('@targetBlock', [point(0, 181, 64)]),
    'block-generic-entity': ('@targetBlock', []),
    'block-threat-empty': ('@targetBlock', []),
    'block-threat': ('@targetBlock', [NEAR]),
    'block-threat-changed': ('@targetBlock{y=1}', [point(8.25, 181)]),
}
EXTENSIONS = {'entity-guard': ['far'], 'entity-guard-limit': ['far'], 'entity-caster-guard': []}


def require(value, message):
    if not value: raise ValueError(message)


def digest(value):
    return hashlib.sha256(value).hexdigest()


def source(case):
    return (f'addtag{{tag={TAG}}}' if case.startswith('entity-') else 'setblock{type=DIAMOND_BLOCK}') + ' ' + CASES[case][0]


def binding(case):
    head, brace, body = CASES[case][0][1:].partition('{')
    name = {'targeted': 'targetedtarget', 'targetedloc': 'targetedlocation', 'targetedlocations': 'targetedlocation'}.get(head.lower(), head.lower())
    return (f'add-tag {TAG}' if case.startswith('entity-') else 'set-block DIAMOND_BLOCK') + ' @' + name + brace + body


def effects(case, selected):
    if case.startswith('entity-'): return sorted(selected)
    import math
    result = []
    for value in selected:
        world, coords = value.split(':')
        x, y, z, _, _ = map(float, coords.split(','))
        result.append(point(math.floor(x), math.floor(y), math.floor(z)) + '=DIAMOND_BLOCK')
    return sorted(set(result))


def inputs(case):
    if case == 'entity-empty': return [], []
    if case == 'entity-cross-world': return ['near', 'far', 'cross'], []
    if case.startswith('entity-player') or case == 'entity-stand-marker':
        return ['item', 'near', 'stand', 'marker', 'player'], []
    if case.startswith('entity-'): return ['caster', 'near', 'far', 'item', 'display', 'item'], []
    if case == 'location-not-entity-projection': return ['near', 'far'], []
    if case.startswith('block-') or case == 'location-empty': return [], []
    return [], sorted([NEAR, FAR, NEAR])


def scene(case):
    if case == 'block-threat-empty': return 'managed-empty'
    if case == 'block-threat': return 'managed-threat-near'
    if case == 'block-threat-changed': return 'managed-threat-far'
    if case.startswith('block-miss'): return 'empty-ray'
    if case.startswith('block-') and case != 'block-generic-entity': return 'glass-stone'
    return 'inherited'


def audit(report, files):
    require(report.get('schemaVersion') == 1 and report.get('evidenceMode') == 'observation'
            and report.get('parityStatus') == 'unassessed', 'schema/scope')
    require(report.get('validationResult') == 'passed' and report.get('exitCode') == 0
            and report.get('clientExitCode') == 0, 'process result')
    require(report.get('paperJarSha256') == '39bd8c00b9e18de91dcabd3cc3dcfa5328685a53b7187a2f63280c22e2d287b9', 'Paper pin')
    require(report.get('mythicMobsSha256') == '9672457cafc56ff841606bfbb710947329c08720ffc207746b60130d43bf902f', 'native pin')
    for key in ('pluginSha256', 'probeJarSha256'):
        require(re.fullmatch('[0-9a-f]{64}', report.get(key, '')), key)
    require(set(report.get('sourceHashes', {})) in (SOURCE_FILES, BLOCK_SOURCE_FILES), 'source coverage')
    require(all(re.fullmatch('[0-9a-f]{64}', value) for value in report['sourceHashes'].values()), 'source hashes')
    expected_files = {'server.log', 'client.log', 'plugins/MythicMobs/Mobs/location.yml', DATA}
    require(set(files) == expected_files, 'archive paths')
    manifest = report.get('files', [])
    require(len(manifest) == len(expected_files) and {item['path'] for item in manifest} == expected_files, 'manifest')
    for item in manifest: require(digest(files[item['path']]) == item['sha256'], 'file digest')
    require(yaml.safe_load(files['plugins/MythicMobs/Mobs/location.yml']) == {'HelsteraRadiusAuditMob': {
        'Type': 'COW', 'ThreatTable': {'Enabled': True, 'DecayUnreachable': False},
        'Options': {'NoAI': True, 'Despawn': 'PERSISTENT'}}}, 'mob configuration')
    count = 2 * len(CASES)
    log = files['server.log'].decode()
    require(log.count(f'AIMING_SUMMARY|observations={count}|passed=true') == 1 and 'AIMING_FAILED|' not in log, 'completion')
    logged = Counter(re.findall(r'AIMING_ROW\|path=(direct|imported)\|case=([^|\r\n]+)\|observed=true', log))
    clients = [json.loads(line.split('AIMING_CLIENT ', 1)[1]) for line in files['client.log'].decode().splitlines() if 'AIMING_CLIENT ' in line]
    require(any(row.get('event') == 'join' and row.get('name') == 'AimTester'
                and re.fullmatch('[0-9a-f-]{36}', row.get('uuid', '')) for row in clients), 'client join')
    require(sum(row.get('event') == 'done' and row.get('name') == 'AimTester' for row in clients) == 1, 'client done')
    require([row for row in clients if row.get('event') == 'summary'] == [{'event': 'summary', 'passed': True}], 'client summary')
    snapshot = yaml.safe_load(files[DATA])
    require(set(snapshot) == {'observations'}, 'snapshot schema')
    rows = snapshot['observations']
    require(len(rows) == report.get('observationCount') == count, 'row count')
    seen = set()
    for row in rows:
        require(set(row) == {'path', 'case', 'selector', 'source', 'binding', 'nativeSelected', 'runtimeSelected',
                            'nativeEffects', 'runtimeEffects', 'nativeAccepted', 'runtimeAccepted',
                            'scene', 'entityInputs', 'locationInputs', 'nativeEntityInputs', 'nativeLocationInputs',
                            'playerMode', 'player', 'eye'}, 'row schema')
        key = (row['path'], row['case'])
        require(key[0] in ('direct', 'imported') and key[1] in CASES and key not in seen, 'case identity')
        seen.add(key); case = key[1]
        selector, native = CASES[case]; runtime = EXTENSIONS.get(case, native)
        require(row['selector'] == selector and row['source'] == source(case), 'source contract: ' + case)
        require(row['binding'] == (source(case) if key[0] == 'direct' else binding(case)), 'binding: ' + case)
        for field, expected in [('nativeSelected', sorted(native)), ('runtimeSelected', sorted(runtime)),
                                ('nativeEffects', effects(case, native)), ('runtimeEffects', effects(case, runtime))]:
            require(row[field] == expected, f'{case}/{key[0]}/{field}: {row[field]} != {expected}')
        require(row['nativeAccepted'] is True and row['runtimeAccepted'] is True, 'accepted')
        require((row['entityInputs'], row['locationInputs']) == inputs(case), 'inheritance preparation: ' + case)
        require((row['nativeEntityInputs'], row['nativeLocationInputs']) == inputs(case), 'native inheritance preparation: ' + case)
        require(row['scene'] == scene(case), 'scene: ' + case)
        mode = 'SURVIVAL' if case == 'entity-player-survival' else 'CREATIVE' if case == 'entity-player-creative' else 'SPECTATOR'
        require(row['playerMode'] == mode, 'player mode: ' + case)
        if case.startswith('block-'):
            require(row['player'] == point(.25) and row['eye'] == point(.25, 181.62), 'player ray: ' + case)
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
