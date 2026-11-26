"""Fixed scene expectations for block selection and real native/runtime mutations."""
import argparse
from collections import Counter
import hashlib
from itertools import product
import json
import math
from pathlib import Path
import re
import zipfile
import yaml

ROOT = Path(__file__).resolve().parents[2]
SOURCE_FILES = {
    'tools/paper-integration/' + name for name in (
        'run-block-targeter-fixture.py', 'run-player-variable-fixture.py',
        'audit-block-targeter.py', 'test-block-targeter-audit.py', 'block-targeter-client.js',
        'random-spawn-fixture/src/main/java/dev/helstera/test/randomspawn/BlockTargeterProbe.java',
        'random-spawn-fixture/src/main/java/dev/helstera/test/randomspawn/VariablePlaceholderAudit.java')
} | {
    'helstera-api/src/main/java/dev/helstera/api/behavior/' + name for name in (
        'Targeter.java', 'Targeters.java', 'DroppedItemTargeter.java', 'NamedPlayerTargeter.java',
        'InheritedTargetLocations.java', 'BehaviorContext.java', 'SkillTreeContext.java',
        'AimingInheritedTargeter.java', 'BlockLocationTargeter.java', 'NativeLocationTargetFilters.java',
        'NativeTargeterOptions.java', 'NativeEntityTargetFilters.java', 'ManagedMobIdentity.java',
        'MobRelations.java', 'Factions.java', 'FactionTable.java')
} | {
    'helstera-ai/src/main/java/dev/helstera/ai/skill/SkillService.java',
    'helstera-ai/src/main/java/dev/helstera/ai/skill/Placeholders.java',
    'helstera-ai/src/main/java/dev/helstera/ai/skill/SkillExtras.java',
    'helstera-ai/src/main/java/dev/helstera/ai/AiManager.java',
    'helstera-ai/src/main/java/dev/helstera/ai/AiController.java',
    'helstera-migration/src/main/java/dev/helstera/migration/importer/MythicMobsImporter.java',
}
DATA = 'plugins/HelsteraBlockTargeterProbe/observations.yml'
WORLD = 'block-query-fixture'


def point(x, y=180, z=.25, yaw=0, pitch=0):
    return f'{WORLD}:{x:.6f},{y:.6f},{z:.6f},{yaw:.6f},{pitch:.6f}'


TERRAIN = {
    (-2,180,2): 'STONE',
    (0,180,0): 'STONE', (1,180,0): 'DIRT', (-1,180,0): 'CAVE_AIR',
    (0,181,0): 'GOLD_BLOCK', (0,180,1): 'GLASS', (2,180,0): 'EMERALD_ORE',
    (0,180,-2): 'IRON_ORE', (1,181,1): 'DIAMOND_ORE', (0,190,0): 'STONE',
    (8,180,0): 'DIAMOND_ORE', (24,180,0): 'EMERALD_ORE',
    (-16,180,-16): 'STONE', (-1,180,-1): 'DIRT', (40,180,0): 'COPPER_BLOCK',
}
OBSTRUCTIONS = [(40,180,1),(40,180,0),(40,181,1),(40,182,1),(41,180,1),
                (41,181,1),(41,182,1),(39,180,1),(39,181,1),(39,182,1)]
VEIN = {(0,180,0): 'STONE', (1,180,0): 'STONE', (-1,179,-1): 'STONE',
        (2,181,1): 'STONE', (10,180,0): 'STONE', (0,180,1): 'DIRT'}
# Independent, finite coordinate sets; no runtime selector implementation is called.
R2_BLOCKS = [(x,y,z) for x,y,z in TERRAIN if x*x+(y-180)**2+z*z <= 4]
R1_BLOCKS = [(0,180,0),(1,180,0),(-1,180,0),(0,181,0),(0,180,1)]
R1_ALL = R1_BLOCKS + [(0,179,0),(0,180,-1)]
C0 = [p for p in TERRAIN if p[0]//16 == p[2]//16 == 0]


def fractional(blocks):
    return [point(x+.25,y,z+.25) for x,y,z in blocks]


R1, R2 = fractional(R1_BLOCKS), fractional(R2_BLOCKS)
CHUNK = [point(*p) for p in C0] + [point(x,320,z) for x,z in product(range(16),repeat=2)]
SECOND_CHUNK = [point(24,180,0)]+[point(x,320,z) for x,z in product(range(16,32),range(16))]
NEIGHBORS = fractional([(1,180,0),(-1,179,-1)])
CONNECTED = fractional([(0,180,0),(1,180,0),(-1,179,-1),(2,181,1)])
INHERITED = [point(8.25),point(24.25)]
CASES = {}


def case(name, selector, selected, raw=None):
    CASES[name] = (selector, sorted(selected), len(selected) if raw is None else raw)


case('highest-default', '@highestblock', [point(0,190,0)])
case('highest-mutate', '@highestblock{y=2;centered=true}', [point(.5,192,.5)])
case('highest-filter', '@highestblock{blocktypes=STONE}', [point(0,190,0)])
case('highest-reject', '@highestblock{blockignores=STONE}', [], 1)
case('highest-origin', '@highestblock', [point(24,180,0)])
case('bir-empty', '@BIR', [])
case('bic-empty', '@BIC', [])
case('bir-entities', '@blocksInRadius', INHERITED)
case('bir-alias', '@BIR', INHERITED)
case('bir-origin', '@BIR{nearorigin=true;r=1}', R1)
case('bir-origin-priority', '@BIR{nearorigin=false;no=true}', INHERITED)
case('bic-entities', '@blocksInChunk', CHUNK+SECOND_CHUNK)
case('bic-alias', '@BIC', CHUNK+SECOND_CHUNK)
case('bir-locations', '@BIR', INHERITED)
case('bic-locations', '@BIC', CHUNK+SECOND_CHUNK)
case('bno-default', '@blocksNearOrigin', R2)
case('bno-alias', '@BNO', R2)
case('radius-priority', '@BNO{r=2;radius=1}', R1)
case('radius-quoted', '@BNO{r="2"}', R1)
case('radius-invalid', '@BNO{r=bad}', R1)
case('radius-range', '@BNO{r=1to1}', R1)
case('radius-fractional', '@BNO{r=1.5;na=false}',
     [point(x,y,z) for x,y,z in product((-.25,.75),(179.5,180.5),(-.25,.75))])
case('radius-zero', '@BNO{r=0;na=false}', [point(.25)])
case('radius-negative', '@BNO{r=-1;na=false}', [])
CUBE = [point(x+.25,y+180,z+.25) for x,y,z in product((-1,0,1),repeat=3)]
case('radius-cube', '@BNO{r=1;shape=CUBE;na=false}', CUBE)
case('shape-invalid', '@BNO{r=1;shape=bad;na=false}', CUBE)
case('shape-priority', '@BNO{r=1;shape=SPHERE;s=CUBE;na=false}', fractional(R1_ALL))
case('vertical-priority', '@BNO{r=1;radiusy=0;ry=1;shape=CUBE;na=false}',
     [point(x+.25,180,z+.25) for x,z in product((-1,0,1),repeat=2)])
case('vertical-zero-sphere', '@BNO{r=1;ry=0;na=false}',
     [point(x+.25,180,z+.25) for x,z in product((-1,0,1),repeat=2)])
case('vertical-alias', '@BNO{r=1;yr=2;na=false}', fractional(R1_ALL+[(0,178,0),(0,182,0)]))
case('mask-only-air', '@BNO{r=1;onlyair=true;noair=true}', fractional([(0,179,0),(0,180,-1)]))
case('mask-all', '@BNO{r=1;noair=false}', fractional(R1_ALL))
case('mask-priority', '@BNO{r=1;noair=true;na=false}', R1)
case('mask-after-offset', '@BNO{r=0;shape=CUBE;x=2}', [point(2.25)])
case('filter-stone', '@BNO{blocktypes=STONE}', [point(.25)], len(R2))
case('filter-pattern', '@BNO{blocktypes=#ORE}', fractional([(2,180,0),(0,180,-2),(1,181,1)]), len(R2))
case('filter-ignore', '@BNO{blockignores=STONE,DIRT}',
     fractional([p for p in R2_BLOCKS if TERRAIN[p] not in ('STONE','DIRT')]),len(R2))
case('limit-nearest', '@BNO{limit=1;sort=NEAREST}', [point(.25)],len(R2))
case('limit-furthest', '@BNO{limit=1;sort=FURTHEST;blocktypes=EMERALD_ORE}',[point(2.25)],len(R2))
case('limit-quoted', '@BNO{r=1;limit="1"}', R1)
case('noise-zero', '@BNO{r=1;noise=0}', R1)
case('noise-one', '@BNO{r=1;noise=1}', R1)
case('noise-negative', '@BNO{r=1;noise=-1}', R1)
case('guard-true', '@BNO{r=1;conditions=[ - distance{d=0to1000} true ]}', R1)
case('guard-false', '@BNO{r=1;conditions=[ - distance{d=1000to2000} true ]}', R1)
case('bic-origin', '@BIC{no=true}', CHUNK)
case('bic-mutate-noop', '@BIC{no=true;x=5;y=8;centered=true}', CHUNK)
case('bic-filter', '@BIC{no=true;blocktypes=GOLD_BLOCK}', [point(0,181,0)], len(CHUNK))
case('bic-all-filter', '@BIC{no=true;na=false;blocktypes=GOLD_BLOCK}', [point(0,181,0)], 98560)
case('bic-only-air', '@BIC{no=true;oa=true;blocktypes=STONE}', [], 98560-len(CHUNK))
case('bic-nearest', '@BIC{no=true;limit=1;sort=NEAREST}', [point(0,180,0)], len(CHUNK))
case('bic-maxheight', '@BIC{no=true;na=false;limit=1;sort=FURTHEST}', [point(15,320,15)], 98560)
case('bic-negative', '@BIC{no=true}', [point(-16,180,-16),point(-1,180,-1)]
     +[point(x,320,z) for x,z in product(range(-16,0),repeat=2)])
for index, position in enumerate(OBSTRUCTIONS):
    case(f'obstruction-{index}', '@obstructingBlock', [point(*position)])
case('obstruction-empty', '@obstructingBlock', [])
case('obstruction-mutate-noop', '@obstructingBlock{x=9;y=9;centered=true}', [point(*OBSTRUCTIONS[0])])
case('obstruction-filter', '@obstructingBlock{blockignores=STONE}', [],1)
YAW_POINTS = {90:(39,180,0), -90:(41,180,0), 180:(40,180,-1),
              360:(40,180,1), -360:(40,180,1), 44:(40,180,1), 45:(39,180,0)}
for yaw, position in YAW_POINTS.items():
    case('obstruction-yaw-'+str(yaw), '@obstructingBlock', [point(*position)])
case('vein-default', '@blockVein', CONNECTED)
case('vein-alias', '@vein', CONNECTED)
case('vein-short', '@bv', CONNECTED)
case('vein-budget-one', '@bv{l=1}', NEIGHBORS)
case('vein-max-alias', '@bv{max=1}', NEIGHBORS)
case('vein-material-budget-alias', '@bv{m=STONE}', NEIGHBORS)
case('vein-final-limit', '@bv{limit=1;sort=NEAREST}', [point(1.25)],2)
case('vein-zero', '@bv{l=0}', [])
case('vein-negative', '@bv{l=-1}', [])
case('vein-mutate-noop', '@bv{x=10;y=5;centered=true}', CONNECTED)
case('vein-material', '@bv{t=DIRT;match=false}', [point(.25,180,1.25)])
case('vein-material-priority', '@bv{blocktypes=STONE;t=DIRT}', CONNECTED)
case('vein-blocktype-filter', '@bv{blocktype=DIRT;match=false}', [],4)
case('vein-origin-reject', '@bv', [])
case('vein-origin-optional', '@bv{originMustMatch=false}', [p for p in CONNECTED if p != point(.25)])
case('vein-origin-priority', '@bv{originMustMatch=true;match=false}', [])
case('vein-isolated', '@bv', [])


def require(value, message):
    if not value: raise ValueError(message)


def digest(value):
    return hashlib.sha256(value).hexdigest()


def source(case):
    return 'setblock{type=DIAMOND_BLOCK} '+CASES[case][0]


def binding(case):
    head, brace, body = CASES[case][0][1:].partition('{')
    head = {'bir':'blocksinradius','bno':'blocksnearorigin','bic':'blocksinchunk',
            'bv':'blockvein','vein':'blockvein'}.get(head.lower(),head.lower())
    return 'set-block DIAMOND_BLOCK @'+head+brace+body


def effects(case, runtime=False):
    if case == 'bic-maxheight' or (runtime and case == 'guard-false'): return []
    result = set()
    for location in CASES[case][1]:
        x,y,z,_,_ = map(float,location.split(':')[1].split(','))
        if y < -64 or y >= 320: continue
        result.add(point(math.floor(x),math.floor(y),math.floor(z))+'=DIAMOND_BLOCK')
    return sorted(result)


def inputs(case):
    if case in ('bir-entities','bir-alias','bir-origin','bir-origin-priority','bic-entities','bic-alias'):
        return INHERITED+[INHERITED[0]], []
    if case in ('bir-locations','bic-locations'):
        return [], sorted(INHERITED+[INHERITED[0]])
    return [], []


def preparation(case):
    origin = point(.25)
    if case == 'highest-origin': origin = point(24.25)
    if case == 'bic-maxheight': origin = point(.25,-64)
    if case == 'bic-negative': origin = point(-.25,180,-.25)
    caster, scene, blocks = point(40.25), 'terrain', TERRAIN
    if case == 'obstruction-empty': scene,blocks = 'empty',{}
    elif case.startswith('obstruction-yaw-'):
        yaw = int(case.removeprefix('obstruction-yaw-'))
        scene,blocks,caster = 'yaw-'+str(yaw),{YAW_POINTS[yaw]:'STONE'},point(40.25,yaw=yaw)
    elif case.startswith('obstruction-'):
        index = int(case.rsplit('-',1)[1]) if case.rsplit('-',1)[1].isdigit() else 0
        scene,blocks = 'ob-'+str(index),{OBSTRUCTIONS[index]:'STONE'}
    elif case.startswith('vein-'):
        scene,blocks = 'vein',VEIN
        if case.startswith('vein-origin-'):
            scene,blocks = 'vein-open',{p:t for p,t in VEIN.items() if p != (0,180,0)}
        if case == 'vein-isolated': scene,blocks = 'vein-isolated',{(0,180,0):'STONE'}
    return origin,caster,scene,sorted(point(*p)+'='+t for p,t in blocks.items())


def audit(report, files):
    require(report.get('schemaVersion') == 1 and report.get('evidenceMode') == 'observation'
            and report.get('parityStatus') == 'unassessed', 'schema/scope')
    require(report.get('validationResult') == 'passed' and report.get('exitCode') == 0
            and report.get('clientExitCode') == 0, 'process result')
    require(report.get('paperJarSha256') == '39bd8c00b9e18de91dcabd3cc3dcfa5328685a53b7187a2f63280c22e2d287b9', 'Paper pin')
    require(report.get('mythicMobsSha256') == '9672457cafc56ff841606bfbb710947329c08720ffc207746b60130d43bf902f', 'native pin')
    for key in ('pluginSha256','probeJarSha256'):
        require(re.fullmatch('[0-9a-f]{64}',report.get(key,'')),key)
    require(set(report.get('sourceHashes',{})) == SOURCE_FILES, 'source coverage')
    require(all(re.fullmatch('[0-9a-f]{64}',v) for v in report['sourceHashes'].values()), 'source hashes')
    expected_files = {'server.log','client.log','plugins/MythicMobs/Mobs/location.yml',DATA}
    require(set(files) == expected_files, 'archive paths')
    manifest = report.get('files',[])
    require(len(manifest) == len(expected_files) and {item['path'] for item in manifest} == expected_files,'manifest')
    for item in manifest: require(digest(files[item['path']]) == item['sha256'],'file digest')
    require(yaml.safe_load(files['plugins/MythicMobs/Mobs/location.yml']) == {'HelsteraRadiusAuditMob': {
        'Type':'COW','ThreatTable':{'Enabled':True,'DecayUnreachable':False},
        'Options':{'NoAI':True,'Despawn':'PERSISTENT'}}},'mob configuration')
    count = 2*len(CASES)
    log = files['server.log'].decode()
    require(log.count(f'BLOCKS_SUMMARY|observations={count}|passed=true') == 1 and 'BLOCKS_FAILED|' not in log,'completion')
    logged = Counter(re.findall(r'BLOCKS_ROW\|path=(direct|imported)\|case=([^|\r\n]+)\|observed=true',log))
    clients = [json.loads(line.split('BLOCKS_CLIENT ',1)[1]) for line in files['client.log'].decode().splitlines() if 'BLOCKS_CLIENT ' in line]
    require(any(row.get('event') == 'join' and row.get('name') == 'BlockTester'
                and re.fullmatch('[0-9a-f-]{36}',row.get('uuid','')) for row in clients),'client join')
    require(sum(row.get('event') == 'done' and row.get('name') == 'BlockTester' for row in clients) == 1,'client done')
    require([row for row in clients if row.get('event') == 'summary'] == [{'event':'summary','passed':True}],'client summary')
    snapshot = yaml.safe_load(files[DATA])
    require(set(snapshot) == {'observations'},'snapshot schema')
    rows = snapshot['observations']
    require(len(rows) == report.get('observationCount') == count,'row count')
    seen = set()
    for row in rows:
        require(set(row) == {'path','case','selector','source','binding','scene','blocks','origin','caster',
                            'entityInputs','locationInputs','nativeEntityInputs','nativeLocationInputs',
                            'nativeRawCount','nativeSelected','runtimeSelected','nativeEffects','runtimeEffects',
                            'nativeAccepted','runtimeAccepted','mutations','loadedBefore','loadedAfter'},'row schema')
        key = (row['path'],row['case'])
        require(key[0] in ('direct','imported') and key[1] in CASES and key not in seen,'case identity')
        seen.add(key); case = key[1]
        selector,selected,raw = CASES[case]
        require(row['selector'] == selector and row['source'] == source(case),'source: '+case)
        require(row['binding'] == (source(case) if key[0] == 'direct' else binding(case)),'binding: '+case)
        for field,expected in [('nativeSelected',selected),('runtimeSelected',selected),('nativeRawCount',raw),
                               ('nativeEffects',effects(case)),('runtimeEffects',effects(case,True))]:
            require(row[field] == expected,f'{case}/{key[0]}/{field}: {row[field]} != {expected}')
        require(row['nativeAccepted'] is True and row['runtimeAccepted'] is True,'accepted: '+case)
        entities,locations = inputs(case)
        require((row['entityInputs'],row['locationInputs']) == (entities,locations),'inputs: '+case)
        require((row['nativeEntityInputs'],row['nativeLocationInputs']) == (sorted(entities),locations),'native inputs: '+case)
        require((row['origin'],row['caster'],row['scene'],row['blocks']) == preparation(case),'scene: '+case)
        require(row['mutations'] is (case != 'bic-maxheight'),'mutation scope: '+case)
        require(row['loadedBefore'] == sorted(set(row['loadedBefore'])) and
                row['loadedAfter'] == row['loadedBefore'],'chunk load mutation: '+case)
        require({f'{x},{z}' for x in range(-2,4) for z in range(-2,3)} <= set(row['loadedBefore']),'prepared chunks')
        require(all(re.fullmatch(r'-?\d+,-?\d+',value) for value in row['loadedBefore']),'chunk schema')
        require(logged[key] == 1,'log correspondence')
    require(seen == {(path,case) for path in ('direct','imported') for case in CASES},'case coverage')
    return {'observations':count,'nativeComparisons':count,'intentionalDifferences':2,'parityStatus':'unassessed'}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('report',type=Path)
    parser.add_argument('--check-current',action='store_true')
    args = parser.parse_args()
    report = json.loads(args.report.read_text())
    archive = args.report.parent/report['logArchive']
    require(archive.name == report['logArchive'],'archive traversal')
    require(digest(archive.read_bytes()) == report['logArchiveSha256'],'archive hash')
    with zipfile.ZipFile(archive) as packed:
        require(len(packed.namelist()) == len(set(packed.namelist())),'duplicate archive files')
        files = {name:packed.read(name) for name in packed.namelist()}
    result = audit(report,files)
    if args.check_current:
        for name,expected in report['sourceHashes'].items():
            require(digest((ROOT/name).read_bytes()) == expected,'current source drift: '+name)
        require(digest((ROOT/'helstera-plugin/target/helsteraMobs-1.0.0-full.jar').read_bytes()) == report['pluginSha256'],'current JAR')
    print(json.dumps(result,sort_keys=True))


if __name__ == '__main__': main()
