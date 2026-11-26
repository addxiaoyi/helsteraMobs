"""Independently verify targeter identities, selected positions and actual mutation evidence."""
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
        'run-targeter-context-fixture.py', 'run-player-variable-fixture.py',
        'audit-targeter-context.py', 'test-targeter-context-audit.py', 'targeter-context-client.js',
        'random-spawn-fixture/src/main/java/dev/helstera/test/randomspawn/TargeterContextProbe.java',
        'random-spawn-fixture/src/main/java/dev/helstera/test/randomspawn/VariablePlaceholderAudit.java')
} | {
    'helstera-api/src/main/java/dev/helstera/api/behavior/' + name for name in (
        'Targeter.java', 'Targeters.java', 'NamedPlayerTargeter.java', 'InheritedTargetLocations.java',
        'BehaviorContext.java', 'SkillTreeContext.java')
} | {
    'helstera-ai/src/main/java/dev/helstera/ai/skill/SkillService.java',
    'helstera-ai/src/main/java/dev/helstera/ai/skill/Placeholders.java',
    'helstera-migration/src/main/java/dev/helstera/migration/importer/MythicMobsImporter.java',
}
WORLD = 'integration-world'
DATA = 'plugins/HelsteraTargeterContextProbe/observations.yml'
TAG = 'helstera_targeter_probe'


def positions(*points):
    return sorted({WORLD + ':' + ','.join(f'{value:.6f}' for value in point) for point in points})


FIRST = (32.25, 180.75, 1.25, 0, 0)
SECOND = (64.25, 180.75, 1.25, 0, 0)
POINTS = positions(FIRST, SECOND)
# Expectations are fixed independently from the producer's expected/matched fields.
CASES = {
    'player-exact': ('@PlayerByName{name=TargeterTester}', ['player']),
    'player-case': ('@specificplayer{n=targetertester}', ['player']),
    'player-prefix': ('@playerbyname{n=TargeterT}', []),
    'player-missing': ('@playerbyname{n=MissingTargeter}', []),
    'player-default': ('@playerbyname', []),
    'player-placeholder': ('@playerbyname{n=<trigger.name>}', ['player']),
    'player-filtered': ('@playerbyname{n=TargeterTester;targetPlayers=false}', []),
    'player-crossworld': ('@specificplayer{n=TargeterTester}', ['player']),
    'interaction-attack': ('@interactionLastAttacker', ['player']),
    'interaction-attacker-alias': ('@lastAttacker', ['player']),
    'interaction-interact': ('@interactionLastInteract', ['player']),
    'interaction-interact-alias': ('@lastInteract', ['player']),
    'interaction-missing-attack': ('@lastAttacker', []),
    'interaction-missing-interact': ('@lastInteract', []),
    'interaction-offline-attack': ('@lastAttacker', []),
    'interaction-wrong-caster': ('@lastInteract', []),
    'interaction-player-disabled': ('@lastInteract{targetPlayers=false}', []),
    'lot-inherited': ('@locationsOfTargets', POINTS),
    'lot-short-alias': ('@LOT', POINTS),
    'lot-singular-alias': ('@locationOfTarget', POINTS),
    'lot-duplicate': ('@LOT', POINTS),
    'lot-empty': ('@LOT', []),
    'lot-location-fallback': ('@LOT', positions((80.25, 181.25, 3.25, 0, 0))),
    'lot-location-empty-entities': ('@LOT', []),
    'lot-offset': ('@LOT{x=1;y=2;z=-1}', positions((33.25, 182.75, .25, 0, 0), (65.25, 182.75, .25, 0, 0))),
    'lot-length': ('@LOT{length=3}', positions((32.25, 180.75, 4.25, 0, 0), (64.25, 180.75, 4.25, 0, 0))),
    'lot-forward': ('@LOT{fo=2}', positions((32.25, 180.75, -.75, 0, 0), (64.25, 180.75, -.75, 0, 0))),
    'lot-coordinates': ('@LOT{cx=96;cy=181;cz=4;cyaw=90;cpitch=15}', positions((96, 181, 4, 90, 15))),
    'lot-centered': ('@LOT{centered=true}', positions((32.5, 180.75, 1.5, 0, 0), (64.5, 180.75, 1.5, 0, 0))),
    'lot-nearest': ('@LOT{limit=1;sort=NEAREST}', positions(FIRST)),
    'lot-furthest': ('@LOT{limit=1;sortby=FURTHEST}', positions(SECOND)),
    'lot-block-ignore': ('@LOT{blockignore=AIR}', []),
    'lot-block-include': ('@LOT{blocktypes=AIR}', POINTS),
}


def require(value, message):
    if not value:
        raise ValueError(message)


def digest(value):
    return hashlib.sha256(value).hexdigest()


def source(case):
    return ('setchunkforceloaded{loaded=true}' if case.startswith('lot-') else f'addtag{{tag={TAG}}}') + ' ' + CASES[case][0]


def binding(case):
    selector = CASES[case][0]
    head, brace, body = selector[1:].partition('{')
    canonical = {
        'specificplayer': 'playerbyname', 'lastattacker': 'interactionlastattacker',
        'lastinteract': 'interactionlastinteract', 'lot': 'locationsoftargets',
        'locationoftarget': 'locationsoftargets',
    }.get(head.lower(), head.lower())
    return ('set-chunk-force-loaded{loaded="true"}' if case.startswith('lot-') else f'add-tag {TAG}') + ' @' + canonical + brace + body


def effects(case):
    selected = CASES[case][1]
    if not case.startswith('lot-'):
        return selected
    result = set()
    for point in selected:
        world, numbers = point.split(':')
        x, _, z, _, _ = map(float, numbers.split(','))
        result.add(f'{world}:{int(x // 16)},{int(z // 16)}')
    return sorted(result)


def audit(report, files):
    require(type(report.get('schemaVersion')) is int and report['schemaVersion'] == 1, 'schema')
    require(report.get('evidenceMode') == 'observation' and report.get('parityStatus') == 'unassessed', 'scope')
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
    require(yaml.safe_load(files['plugins/MythicMobs/Mobs/location.yml']) == {
        'HelsteraRadiusAuditMob': {'Type': 'COW', 'Options': {'NoAI': True, 'Despawn': 'PERSISTENT'}}}, 'mob configuration')
    count = 2 * len(CASES)
    log = files['server.log'].decode()
    require(log.count(f'TARGETERS_SUMMARY|observations={count}|passed=true') == 1 and 'TARGETERS_FAILED|' not in log, 'completion')
    logged = Counter(re.findall(r'TARGETERS_ROW\|path=(direct|imported)\|case=([^|\r\n]+)\|matched=true', log))
    clients = [json.loads(line.split('TARGETERS_CLIENT ', 1)[1]) for line in files['client.log'].decode().splitlines()
               if 'TARGETERS_CLIENT ' in line]
    require(any(row.get('event') == 'join' and row.get('name') == 'TargeterTester'
                and re.fullmatch('[0-9a-f-]{36}', row.get('uuid', '')) for row in clients), 'client join')
    require(sum(row.get('event') == 'done' and row.get('name') == 'TargeterTester' for row in clients) == 1, 'client done')
    require([row for row in clients if row.get('event') == 'summary'] == [{'event': 'summary', 'passed': True}], 'client summary')
    snapshot = yaml.safe_load(files[DATA])
    require(set(snapshot) == {'observations'}, 'snapshot schema')
    rows = snapshot['observations']
    require(len(rows) == report.get('observationCount') == count, 'row count')
    seen = set()
    for row in rows:
        require(set(row) == {'path', 'case', 'selector', 'source', 'binding', 'nativeSelected', 'runtimeSelected',
                            'expected', 'nativeEffects', 'runtimeEffects', 'expectedEffects', 'nativeAccepted',
                            'runtimeAccepted', 'matched'}, 'row schema')
        key = (row['path'], row['case'])
        require(key[0] in ('direct', 'imported') and key[1] in CASES and key not in seen, 'case identity')
        seen.add(key)
        selector, selected = CASES[key[1]]
        require(row['selector'] == selector and row['source'] == source(key[1]), 'source contract')
        require(row['binding'] == (source(key[1]) if key[0] == 'direct' else binding(key[1])), 'binding')
        require(all(row[field] == selected for field in ('nativeSelected', 'runtimeSelected', 'expected')), 'selected outcome')
        require(all(row[field] == effects(key[1]) for field in ('nativeEffects', 'runtimeEffects', 'expectedEffects')), 'mutation outcome')
        require(all(row[field] is True for field in ('nativeAccepted', 'runtimeAccepted', 'matched')), 'accepted')
        require(logged[key] == 1, 'log correspondence')
    require(seen == {(path, case) for path in ('direct', 'imported') for case in CASES}, 'case coverage')
    return {'observations': count, 'nativeComparisons': count, 'parityStatus': 'unassessed'}


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
