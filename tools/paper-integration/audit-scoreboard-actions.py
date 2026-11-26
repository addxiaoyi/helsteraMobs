"""Strict main-scoreboard semantic audit; records public-native dynamic operand differences."""
from collections import Counter
import hashlib
import importlib.util
import json
from pathlib import Path, PurePosixPath
import re
import sys
import uuid
import zipfile
import yaml
sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[2]
TOOLS = ROOT / 'tools/paper-integration'
spec = importlib.util.spec_from_file_location('scoreboard_cases', TOOLS / 'scoreboard-actions-cases.py')
c = importlib.util.module_from_spec(spec); spec.loader.exec_module(c)
PROBE = 'plugins/HelsteraScoreboardActionsProbe/'
PATHS = ['direct', 'imported']
SOURCE_FILES = {
    'tools/paper-integration/' + name for name in ['run-scoreboard-actions-fixture.py','run-player-variable-fixture.py',
    'scoreboard-actions-cases.py','scoreboard-actions-client.js','audit-scoreboard-actions.py','test-scoreboard-actions-audit.py',
    'random-spawn-fixture/src/main/java/dev/helstera/test/randomspawn/ScoreboardActionsProbe.java',
    'random-spawn-fixture/src/main/java/dev/helstera/test/randomspawn/VariablePlaceholderAudit.java']
} | {
    'helstera-migration/src/main/java/dev/helstera/migration/importer/MythicMobsImporter.java',
    'helstera-migration/src/main/java/dev/helstera/migration/importer/MythicSpec.java',
    'helstera-ai/src/main/java/dev/helstera/ai/skill/MythicSkillSpec.java',
    'helstera-ai/src/main/java/dev/helstera/ai/skill/SkillService.java',
    'helstera-ai/src/main/java/dev/helstera/ai/skill/SkillExtras.java',
    'helstera-ai/src/main/java/dev/helstera/ai/skill/ScoreboardActions.java',
    'helstera-ai/src/main/java/dev/helstera/ai/skill/Placeholders.java',
    'helstera-api/src/main/java/dev/helstera/api/util/NumericExpression.java',
    'helstera-api/src/main/java/dev/helstera/api/behavior/BehaviorContext.java',
    'helstera-api/src/main/java/dev/helstera/api/util/MythicScoreboardSpec.java'}

def require(value, label):
    if not value: raise ValueError(label)

def digest(value): return hashlib.sha256(value).hexdigest()

def safe_path(value):
    require(isinstance(value,str) and value and '\\' not in value and ':' not in value, 'unsafe path')
    path = PurePosixPath(value)
    require(not path.is_absolute() and '..' not in path.parts and str(path) == value, 'unsafe path')
    return value

class UniqueYaml(yaml.SafeLoader): pass

def unique_yaml(loader, node):
    pairs = loader.construct_pairs(node, deep=True)
    result = {}
    for key, value in pairs:
        require(key not in result, 'duplicate YAML field')
        result[key] = value
    return result
UniqueYaml.add_constructor(yaml.resolver.BaseResolver.DEFAULT_MAPPING_TAG, unique_yaml)

def doc(value): return yaml.load(value, Loader=UniqueYaml)

def strict_equal(actual, expected):
    if type(actual) is not type(expected): return False
    if isinstance(expected, dict):
        return actual.keys() == expected.keys() and all(strict_equal(actual[key], value) for key,value in expected.items())
    if isinstance(expected, list): return len(actual) == len(expected) and all(strict_equal(a,b) for a,b in zip(actual,expected))
    return actual == expected

def unique_json(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result, 'duplicate JSON field'); result[key] = value
    return result

def expected(sample, index, ids):
    family = c.family(sample['mechanic'])
    selected = ids['player'] if sample['recipient'] == 'player' else ids['interaction'] if sample['recipient'] == 'interaction' else ids['target']
    trigger = selected if sample['recipient'] in ('player','interaction') else ids['trigger']
    mode = sample['objective']
    objective = selected if mode == 'target' else trigger if mode == 'trigger' else '<target.uuid>' if mode=='no-target' else '' if 'empty' in mode else f'ScoreAudit{index}'
    argument = 'o=<target.uuid>' if mode in ('target','no-target') else 'o=<trigger.uuid>' if mode=='trigger' else '' if 'empty' in mode else 'o='+objective
    args = argument + (';' if argument and sample['arguments'] else '') + sample['arguments']
    source = sample['mechanic'] + '{' + args + '}' + sample['suffix']
    sentinel = f'Sentinel{index}'
    seeds = {'__GLOBAL__':101,ids['caster']:102,ids['target']:103,ids['trigger']:104,ids['other']:105,
             ids['playerName']:106,ids['player']:107,ids['interaction']:108,'dummy':109,'custom':110,'other':111}
    if 'global' in family: holders = ['__GLOBAL__']
    elif 'mob' in family: holders = [ids['caster']]
    elif 'target' in family:
        holders = [ids['target'],ids['trigger']] if sample['recipient'] == 'multiple' else [ids['playerName'] if sample['recipient'] == 'player' else selected]
    else: holders = [trigger if sample['holder'] == 'trigger' else ids['caster'] if sample['holder']=='caster' else sample['holder'] or 'dummy']
    if sample['seed'] is None: seeds = {}
    else:
        for holder in holders: seeds[holder] = sample['seed']
    before = {objective:seeds if sample['seed'] is not None else 'absent',sentinel:{'unrelated':713}}
    def after(value):
        scores = dict(seeds)
        if value is not None:
            for holder in holders: scores[holder] = value
        return {objective:scores,sentinel:{'unrelated':713}}
    return objective,sentinel,source,before,after(sample['native']),after(sample['runtime'])

def audit(report, files):
    require(type(report.get('schemaVersion')) is int and report['schemaVersion']==1,'schema')
    require(report.get('evidenceMode')=='observation' and report.get('parityStatus')=='unassessed','evidence boundary')
    require(report.get('validationResult')=='passed' and type(report.get('exitCode')) is int and report['exitCode']==0
            and type(report.get('clientExitCode')) is int and report['clientExitCode']==0,'fixture result')
    require(report.get('paperJarSha256')=='39bd8c00b9e18de91dcabd3cc3dcfa5328685a53b7187a2f63280c22e2d287b9','Paper pin')
    require(report.get('mythicMobsSha256')=='9672457cafc56ff841606bfbb710947329c08720ffc207746b60130d43bf902f','native pin')
    for key in ('pluginSha256','probeJarSha256'): require(re.fullmatch('[a-f0-9]{64}',report.get(key,'')),key)
    require(set(report.get('sourceHashes',{}))==SOURCE_FILES and all(re.fullmatch('[a-f0-9]{64}',value)
            for value in report['sourceHashes'].values()),'source manifest')
    n = len(c.CASES) * 2
    paths = {'server.log','client.log','plugins/MythicMobs/Mobs/scoreboard.yml',PROBE+'cases.yml'}
    paths.update(PROBE+'identities-'+path+'.yml' for path in PATHS)
    paths.update(PROBE+f'observations/{index:04d}.yml' for index in range(n))
    require(set(files)==paths and len(report.get('files',[]))==len(files),'exact archive membership')
    manifest = {}
    for entry in report['files']:
        require(set(entry)=={'path','sha256'},'manifest fields')
        name = safe_path(entry['path']); require(name not in manifest,'duplicate manifest')
        manifest[name] = entry['sha256']
    require(set(manifest)==set(files),'manifest membership')
    for name,value in files.items(): require(digest(value)==manifest[name],'file hash '+name)
    require(doc(files[PROBE+'cases.yml'])==c.source_cases(),'source cases')
    require(doc(files['plugins/MythicMobs/Mobs/scoreboard.yml'])=={'HelsteraRadiusAuditMob':{'Type':'COW','Options':{'NoAI':True,'Despawn':'PERSISTENT'}}},'mob source')
    log = files['server.log'].decode()
    require(log.count(f'SCOREBOARD_SUMMARY|observations={n}|completed=true')==1 and 'SCOREBOARD_FAILED|' not in log,'completion')
    require(log.index('SCOREBOARD_SUMMARY|') < log.index(']: Stopping server'),'shutdown ordering')
    log_rows = Counter(re.findall(r'SCOREBOARD_ROW\|case=([^|\r\n]+)\|path=([^|\r\n]+)\|nativeResult=([^|\r\n]+)\|sameState=(true|false)',log))
    require(sum(log_rows.values())==n and report.get('observationCount')==n,'row cardinality')
    client = [json.loads(line.split('SCOREBOARD_CLIENT ',1)[1]) for line in files['client.log'].decode().splitlines() if 'SCOREBOARD_CLIENT ' in line]
    require(len(client)==2 and client[0].get('event')=='join' and client[1]=={'event':'summary','passed':True},'real player lifecycle')
    ids_by_path = {path:doc(files[PROBE+'identities-'+path+'.yml']) for path in PATHS}
    mob_ids = []
    for ids in ids_by_path.values():
        require(set(ids)=={'caster','target','trigger','other','interaction','player','playerName'},'identities')
        require(ids['playerName']=='ScoreboardPlayer' and ids['player']==client[0]['uuid']
                and client[0]['name']==ids['playerName'],'player identity')
        for role,value in ids.items():
            if role!='playerName': uuid.UUID(value)
        require(len(set(ids.values()))==7,'role collision')
        mob_ids.extend(ids[role] for role in ('caster','target','trigger','other','interaction'))
    require(len(set(mob_ids))==10,'path identity collision')
    differences = 0; last_tick = -1
    for index,(path,sample) in enumerate((path,sample) for path in PATHS for sample in c.CASES):
        row = doc(files[PROBE+f'observations/{index:04d}.yml'])
        require(set(row)=={'case','path','source','mapped','objective','sentinel','nativeResult','nativeBefore',
                           'runtimeBefore','nativeAfter','runtimeAfter','sameState','primary','tick','nativePreparedTick'},'row fields')
        require(row['case']==sample['key'] and row['path']==path,'case order')
        objective,sentinel,source,before,native_after,runtime_after = expected(sample,index,ids_by_path[path])
        require((row['objective'],row['sentinel'],row['source'])==(objective,sentinel,source),'source identity')
        mapped = source if path=='direct' else c.canonical(sample['mechanic'])+source[len(sample['mechanic']):]
        require(row['mapped']==mapped,'binding source')
        require(row['nativeResult']==sample['result'],'native result '+sample['key'])
        require(strict_equal(row['nativeBefore'],before) and strict_equal(row['runtimeBefore'],before),'scoreboard seeds '+sample['key'])
        require(strict_equal(row['nativeAfter'],native_after) and strict_equal(row['runtimeAfter'],runtime_after),'scoreboard mutation '+sample['key'])
        same = native_after==runtime_after
        require(row['sameState'] is same and row['primary'] is True,'state difference/thread')
        require(type(row['tick']) is int and row['tick']>=last_tick>=-1,'tick'); last_tick=row['tick']
        require(type(row['nativePreparedTick']) is int and row['nativePreparedTick'] >= 0
                and row['tick'] - row['nativePreparedTick'] >= 6, 'native placeholder initialization')
        require(log.count(f"SCOREBOARD_PREPARED|actions={n}|tick={row['nativePreparedTick']}") == 1,
                'native preparation log')
        require(log_rows[(sample['key'],path,sample['result'],str(same).lower())]==1,'raw observation')
        differences += not same
    require(differences==8,'explicit public-native differences')
    return n

def load(path):
    report=json.loads(Path(path).read_text(),object_pairs_hook=unique_json)
    name=safe_path(report['logArchive']); require(PurePosixPath(name).name==name,'archive path')
    data=(Path(path).parent/name).read_bytes(); require(digest(data)==report['logArchiveSha256'],'archive digest')
    with zipfile.ZipFile(__import__('io').BytesIO(data)) as archive:
        names=archive.namelist(); require(len(set(names))==len(names),'duplicate archive member')
        files={safe_path(name):archive.read(name) for name in names}
    return report,files

if __name__=='__main__':
    report,files=load(sys.argv[1]); count=audit(report,files)
    if '--check-current' in sys.argv:
        for name,value in report['sourceHashes'].items(): require(digest((ROOT/name).read_bytes())==value,'current source '+name)
        require(digest((ROOT/'helstera-plugin/target/helsteraMobs-1.0.0-full.jar').read_bytes())==report['pluginSha256'],'current plugin')
    print(f'Scoreboard audit passed: {count} observations, 8 explicit dynamic-integer differences; observation only.')
