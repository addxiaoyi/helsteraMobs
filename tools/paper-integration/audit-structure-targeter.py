"""Independent nearest-structure parameter, Paper event, generation and effect checks."""
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import re
import zipfile
import yaml

ROOT = Path(__file__).resolve().parents[2]
DATA = 'plugins/HelsteraStructureTargeterProbe/observations.yml'
SOURCE_FILES = {
    'tools/paper-integration/'+name for name in (
        'run-structure-targeter-fixture.py','run-player-variable-fixture.py',
        'audit-structure-targeter.py','test-structure-targeter-audit.py','structure-targeter-client.js',
        'random-spawn-fixture/src/main/java/dev/helstera/test/randomspawn/StructureTargeterProbe.java',
        'random-spawn-fixture/src/main/java/dev/helstera/test/randomspawn/VariablePlaceholderAudit.java')
} | {
    'helstera-api/src/main/java/dev/helstera/api/behavior/'+name for name in (
        'Targeter.java','Targeters.java','DroppedItemTargeter.java','NamedPlayerTargeter.java',
        'InheritedTargetLocations.java','BehaviorContext.java','SkillTreeContext.java',
        'AimingInheritedTargeter.java','BlockLocationTargeter.java','StructureLocationTargeter.java',
        'NativeLocationTargetFilters.java','NativeTargeterOptions.java','NativeEntityTargetFilters.java',
        'ManagedMobIdentity.java','MobRelations.java','Factions.java','FactionTable.java')
} | {
    'helstera-ai/src/main/java/dev/helstera/ai/skill/SkillService.java',
    'helstera-ai/src/main/java/dev/helstera/ai/skill/Placeholders.java',
    'helstera-ai/src/main/java/dev/helstera/ai/skill/SkillExtras.java',
    'helstera-ai/src/main/java/dev/helstera/ai/AiManager.java',
    'helstera-ai/src/main/java/dev/helstera/ai/AiController.java',
    'helstera-migration/src/main/java/dev/helstera/migration/importer/MythicMobsImporter.java',
}
STRUCTURES = {
    'mineshaft':['mineshaft','mineshaft_mesa'],
    'village':['village_desert','village_plains','village_savanna','village_snowy','village_taiga'],
    'fortress':['fortress'], 'stronghold':['stronghold'], 'jungle_pyramid':['jungle_pyramid'],
    'ocean_ruin':['ocean_ruin_cold','ocean_ruin_warm'], 'desert_pyramid':['desert_pyramid'],
    'igloo':['igloo'], 'swamp_hut':['swamp_hut'], 'monument':['monument'], 'end_city':['end_city'],
    'mansion':['mansion'], 'buried_treasure':['buried_treasure'], 'shipwreck':['shipwreck','shipwreck_beached'],
    'pillager_outpost':['pillager_outpost'], 'nether_fossil':['nether_fossil'],
    'ruined_portal':['ruined_portal','ruined_portal_desert','ruined_portal_jungle','ruined_portal_mountain',
                     'ruined_portal_nether','ruined_portal_ocean','ruined_portal_swamp'],
    'bastion_remnant':['bastion_remnant'],
}
CASES = {}


def case(name, args='', structure='stronghold', radius=5000, unexplored=False, mode='result'):
    CASES[name] = ('@nearestStructure'+('{' + args + '}' if args else ''),structure,radius,unexplored,mode)


case('default')
case('type-alias','t=VILLAGE','village')
case('type-priority','t=VILLAGE;type=STRONGHOLD')
case('type-invalid','type=missing;t=village')
case('type-quoted','type="village"')
case('type-namespaced','type=minecraft:village')
case('type-dynamic','type=<caster.var.type>')
for name in sorted(STRUCTURES): case('type-'+name,'type='+name,name)
case('radius-alias','r=2',radius=2)
case('radius-priority','r=2;radius=3',radius=3)
case('radius-invalid','radius=bad;r=2')
case('radius-quoted','radius="2"')
case('radius-dynamic','r=<caster.var.radius>')
case('radius-range','r=2to2')
case('radius-zero','r=0',radius=0)
case('radius-negative','r=-1',radius=-1)
case('unexplored','unexplored=true',unexplored=True)
case('unexplored-alias','u=true',unexplored=True)
case('unexplored-priority','unexplored=false;u=true')
case('unexplored-quoted','u="true"')
case('unexplored-invalid','u=bad')
case('offsets-noop','x=4;y=6;z=8;centered=true')
case('filter-stone','blocktypes=STONE')
case('filter-reject','blockignores=STONE')
case('limit','limit=1;sort=NEAREST')
case('guard-true','conditions=[ - distance{d=0to10000} true ]')
case('guard-false','conditions=[ - distance{d=10000to20000} true ]')
case('moved-caster')
case('changed-provider',mode='alternate')
case('event-cancel',mode='cancel')
case('natural-stronghold','r=0',radius=0,mode='natural')
case('natural-stronghold-filter','r=0;blocktypes=STONE',radius=0,mode='natural')
case('natural-missing','type=fortress;r=0',structure='fortress',radius=0,mode='natural')


def point(x,y=180,z=0):
    return f'integration-world:{x:.6f},{y:.6f},{z:.6f},0.000000,0.000000'


SEEDED_STRONGHOLD = point(-224,0,1376)


def source(case): return 'setblock{type=DIAMOND_BLOCK} '+CASES[case][0]
def binding(case): return 'set-block DIAMOND_BLOCK '+CASES[case][0].replace('nearestStructure','neareststructure')
def digest(value): return hashlib.sha256(value).hexdigest()


def require(value,message):
    if not value: raise ValueError(message)


def audit(report,files):
    require(report.get('schemaVersion') == 1 and report.get('evidenceMode') == 'observation'
            and report.get('parityStatus') == 'unassessed','schema/scope')
    require(report.get('validationResult') == 'passed' and report.get('exitCode') == 0
            and report.get('clientExitCode') == 0,'process result')
    require(report.get('paperJarSha256') == '39bd8c00b9e18de91dcabd3cc3dcfa5328685a53b7187a2f63280c22e2d287b9','Paper pin')
    require(report.get('mythicMobsSha256') == '9672457cafc56ff841606bfbb710947329c08720ffc207746b60130d43bf902f','native pin')
    for key in ('pluginSha256','probeJarSha256'): require(re.fullmatch('[0-9a-f]{64}',report.get(key,'')),key)
    require(set(report.get('sourceHashes',{})) == SOURCE_FILES,'source coverage')
    require(all(re.fullmatch('[0-9a-f]{64}',v) for v in report['sourceHashes'].values()),'source hashes')
    expected_files = {'server.log','client.log','plugins/MythicMobs/Mobs/location.yml',DATA}
    require(set(files) == expected_files,'archive paths')
    manifest = report.get('files',[])
    require(len(manifest) == len(expected_files) and {item['path'] for item in manifest} == expected_files,'manifest')
    for item in manifest: require(digest(files[item['path']]) == item['sha256'],'file digest')
    require(yaml.safe_load(files['plugins/MythicMobs/Mobs/location.yml']) == {'HelsteraRadiusAuditMob': {
        'Type':'COW','ThreatTable':{'Enabled':True,'DecayUnreachable':False},
        'Options':{'NoAI':True,'Despawn':'PERSISTENT'}}},'mob configuration')
    count = 2*len(CASES)
    log = files['server.log'].decode()
    require(log.count(f'STRUCTURE_SUMMARY|observations={count}|passed=true') == 1 and 'STRUCTURE_FAILED|' not in log,'completion')
    logged = Counter(re.findall(r'STRUCTURE_ROW\|path=(direct|imported)\|case=([^|\r\n]+)\|observed=true',log))
    clients = [json.loads(line.split('STRUCTURE_CLIENT ',1)[1]) for line in files['client.log'].decode().splitlines() if 'STRUCTURE_CLIENT ' in line]
    require(any(row.get('event') == 'join' and row.get('name') == 'StructureTester'
                and re.fullmatch('[0-9a-f-]{36}',row.get('uuid','')) for row in clients),'client join')
    require(sum(row.get('event') == 'done' and row.get('name') == 'StructureTester' for row in clients) == 1,'client done')
    require([row for row in clients if row.get('event') == 'summary'] == [{'event':'summary','passed':True}],'client summary')
    snapshot = yaml.safe_load(files[DATA]);require(set(snapshot) == {'observations'},'snapshot schema')
    rows = snapshot['observations'];require(len(rows) == report.get('observationCount') == count,'row count')
    seen = set();natural_position = None
    for row in rows:
        require(set(row) == {'path','case','selector','source','binding','mode','seed','caster','origin','parsed',
                            'queries','naturalStructures','nativeRaw','nativeSelected','runtimeSelected',
                            'nativeEffects','runtimeEffects','nativeAccepted','runtimeAccepted'},'row schema')
        key = row['path'],row['case']
        require(key[0] in ('direct','imported') and key[1] in CASES and key not in seen,'case identity');seen.add(key)
        case = key[1];selector,structure,radius,unexplored,mode = CASES[case]
        require(row['selector'] == selector and row['source'] == source(case),'source: '+case)
        require(row['binding'] == (source(case) if key[0] == 'direct' else binding(case)),'binding: '+case)
        require(row['mode'] == mode and row['seed'] == 281474976710655,'world preparation: '+case)
        caster = point(-40.25,180,-.25) if case == 'moved-caster' else point(40.25,180,.25)
        event_origin = point(-41,180,-1) if case == 'moved-caster' else point(40)
        require(row['caster'] == caster and row['origin'] == point(800.25,180,800.25),'caster/origin: '+case)
        require(row['parsed'] == {'structureType':structure,'radius':radius,'unexplored':unexplored},'native parsing: '+case)
        expected_queries = [{'phase':phase,'origin':event_origin,'types':sorted('minecraft:'+t for t in STRUCTURES[structure]),
                             'radius':radius,'unexplored':unexplored,'mode':mode} for phase in ('native','runtime','effect')]
        require(row['queries'] == expected_queries,'query events: '+case)
        if case.startswith('natural-stronghold'):
            require(len(row['nativeRaw']) == 1,'natural result count')
            raw = [SEEDED_STRONGHOLD]
            require(re.fullmatch(r'integration-world:-?\d+\.000000,-?\d+\.000000,-?\d+\.000000,0\.000000,0\.000000',raw[0]),'natural coordinate')
            require(row['naturalStructures'] == ['minecraft:stronghold'],'real generated stronghold')
            if natural_position is None: natural_position = raw
            require(raw == natural_position,'seeded result consistency')
        else:
            raw = [] if mode == 'cancel' or case == 'natural-missing' else [point(12 if mode == 'alternate' else 8)]
            require(row['naturalStructures'] == [],'synthetic/natural scope: '+case)
        selected = [] if case == 'filter-reject' else raw
        native_effects = [p+'=DIAMOND_BLOCK' for p in selected]
        runtime_effects = [] if case == 'guard-false' else native_effects
        for field,expected in [('nativeRaw',raw),('nativeSelected',selected),('runtimeSelected',selected),
                               ('nativeEffects',native_effects),('runtimeEffects',runtime_effects)]:
            require(row[field] == expected,f'{case}/{key[0]}/{field}: {row[field]} != {expected}')
        require(row['nativeAccepted'] is True and row['runtimeAccepted'] is True,'accepted: '+case)
        require(logged[key] == 1,'log correspondence')
    require(seen == {(path,case) for path in ('direct','imported') for case in CASES},'case coverage')
    return {'observations':count,'nativeComparisons':count,'intentionalDifferences':2,
            'naturalObservations':6,'parityStatus':'unassessed'}


def main():
    parser = argparse.ArgumentParser();parser.add_argument('report',type=Path);parser.add_argument('--check-current',action='store_true')
    args = parser.parse_args();report = json.loads(args.report.read_text());archive = args.report.parent/report['logArchive']
    require(archive.name == report['logArchive'],'archive traversal')
    require(digest(archive.read_bytes()) == report['logArchiveSha256'],'archive hash')
    with zipfile.ZipFile(archive) as packed:
        require(len(packed.namelist()) == len(set(packed.namelist())),'duplicate archive files')
        files = {name:packed.read(name) for name in packed.namelist()}
    result = audit(report,files)
    if args.check_current:
        for name,expected in report['sourceHashes'].items(): require(digest((ROOT/name).read_bytes()) == expected,'current source drift: '+name)
        require(digest((ROOT/'helstera-plugin/target/helsteraMobs-1.0.0-full.jar').read_bytes()) == report['pluginSha256'],'current JAR')
    print(json.dumps(result,sort_keys=True))


if __name__ == '__main__': main()
