"""Independent seeded coordinate, multiplicity, collision, filter and effect oracle."""
import argparse
from collections import Counter
import hashlib
import json
import math
from pathlib import Path
import re
import zipfile
import yaml

ROOT = Path(__file__).resolve().parents[2]
DATA = 'plugins/HelsteraRandomLocationTargeterProbe/observations.yml'
SOURCE_FILES = {
    'tools/paper-integration/'+name for name in (
        'run-random-location-targeter-fixture.py','run-player-variable-fixture.py',
        'audit-random-location-targeter.py','test-random-location-targeter-audit.py','random-location-targeter-client.js',
        'random-spawn-fixture/src/main/java/dev/helstera/test/randomspawn/RandomLocationTargeterProbe.java',
        'random-spawn-fixture/src/main/java/dev/helstera/test/randomspawn/VariablePlaceholderAudit.java')
} | {
    'helstera-api/src/main/java/dev/helstera/api/behavior/'+name for name in (
        'Targeter.java','Targeters.java','DroppedItemTargeter.java','NamedPlayerTargeter.java',
        'InheritedTargetLocations.java','BehaviorContext.java','SkillTreeContext.java',
        'AimingInheritedTargeter.java','BlockLocationTargeter.java','StructureLocationTargeter.java','RandomLocationTargeter.java',
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

CASES = {}
ORIGIN = {'randomlocationsnearorigin','rlo','randomlocationsorigin','rlno'}
TARGETS = {'randomlocationsneartargets','rlnt'}


def case(name,selector,amount=5,radius=5,minimum=0,spacing=0,scene='air',inputs='entities',seed=19,offset=(0,0,0),centered=False,limit=0,reject=False,guard=True):
    CASES[name] = dict(selector=selector,amount=amount,radius=radius,minimum=minimum,spacing=spacing,
                       scene=scene,inputs=inputs,seed=seed,offset=offset,centered=centered,limit=limit,reject=reject,guard=guard)


for alias in ('randomLocationsNearOrigin','RLO','randomLocationsOrigin','RLNO','randomLocationsNearTargets','RLNT',
              'randomLocationsNearCaster','RLNC','randomLocations'):
    case('alias-'+alias,'@'+alias+'{a=3}',amount=3)
for seed in (7331,20261126):
    for alias in ('RLO','RLNT','RLNC'):case(f'seed-{alias}-{seed}','@'+alias,seed=seed)
case('locations','@RLNT{a=3}',amount=3,inputs='locations')
case('empty','@RLNT',inputs='empty')
case('entity-reselection','@RLNT{a=3}',amount=3,inputs='reselected-entities')
case('nonliving','@RLNT{a=3}',amount=3,inputs='nonliving')
for alias in ('RLO','RLNT','RLNC'):
    case('solid-'+alias,'@'+alias+'{a=3;r=2}',amount=3,radius=2,scene='solid')
    case('mixed-'+alias,'@'+alias+'{a=5;r=2}',radius=2,scene='mixed',seed=7331)
    case('minimum-'+alias,'@'+alias+'{a=8;r=3;minr=3}',amount=8,radius=3,minimum=3)
    case('spacing-'+alias,'@'+alias+'{a=5;r=2;s=3}',radius=2,spacing=3)
    case('mutation-'+alias,'@'+alias+'{a=3;r=2;x=4;y=2;z=-2;centered=true}',amount=3,radius=2,offset=(4,2,-2),centered=True)
    case('overflow-min-'+alias,'@'+alias+'{a=1;minr=50000}',amount=1,minimum=50000)
case('duplicates','@RLNT{a=3;r=2}',amount=3,radius=2,scene='solid',inputs='duplicates')
case('spacing-global','@RLNT{a=3;r=2;s=1}',amount=3,radius=2,spacing=1,scene='solid',inputs='duplicates')
case('spacing-mutated','@RLNT{a=3;r=2;s=1;x=4}',amount=3,radius=2,spacing=1,scene='solid',inputs='duplicates',offset=(4,0,0))
case('min-before-mutation','@RLO{a=3;r=1;minr=100;x=200}',amount=3,radius=1,minimum=100,offset=(200,0,0))
case('priority','@RLO{a=1;amount=3;r=1;radius=2;maxradius=4;minradius=0;minr=9;spacing=0;s=100}',amount=3,radius=2)
case('max-alias','@RLO{a=3;maxr=2}',amount=3,radius=2)
case('amount-zero','@RLO{a=0}',amount=0)
case('amount-negative','@RLO{a=-1}',amount=-1)
case('amount-invalid','@RLO{a=bad}',amount=1)
case('amount-quoted','@RLO{a="3"}',amount=1)
case('amount-dynamic','@RLO{a=<caster.var.amount>}',amount=1)
case('amount-range','@RLNT{a=3to3;r=2}',amount=3,radius=2,scene='solid')
case('radius-zero','@RLO{a=3;r=0}',amount=3,radius=0)
case('radius-negative','@RLO{a=3;r=-2}',amount=3,radius=-2)
for alias in ('RLO','RLNT'):
    for value in ('bad','"2"','<caster.var.radius>'):
        case(f'radius-{alias}-{value}','@'+alias+'{a=3;r='+value+'}',amount=3,radius=5 if alias=='RLO' else 1)
case('radius-range-origin','@RLO{a=3;r=2to2}',amount=3)
case('radius-range-targets','@RLNT{a=3;r=2to2}',amount=3,radius=2,scene='solid')
case('static-min','@RLO{a=3;r=2;minr=bad}',amount=3,radius=2,scene='solid')
case('placeholder-min','@RLNT{a=3;r=2;minr=bad}',amount=3,radius=2,minimum=1,scene='solid')
case('spacing-overflow','@RLNT{a=3;r=2;s=50000}',amount=3,radius=2,spacing=50000,scene='solid',inputs='duplicates')
case('filter-allow','@RLO{a=3;r=2;blocktypes=AIR}',amount=3,radius=2)
case('filter-reject','@RLO{a=3;r=2;blockignores=AIR}',amount=3,radius=2,reject=True)
case('limit','@RLNT{a=5;r=2;limit=2;sort=NEAREST}',radius=2,limit=2)
case('limit-duplicates','@RLNT{a=3;r=2;limit=2;sort=NEAREST}',amount=3,radius=2,scene='solid',inputs='duplicates',limit=2)
case('guard-true','@RLO{a=3;r=2;conditions=[ - distance{d=0to1000} true ]}',amount=3,radius=2)
case('guard-false','@RLO{a=3;r=2;conditions=[ - distance{d=1000to2000} true ]}',amount=3,radius=2,guard=False)


def digest(value):return hashlib.sha256(value).hexdigest()
def require(value,message):
    if not value:raise ValueError(message)


class Rng:
    """JDK 21 ThreadLocalRandom -> Random.nextDouble, independently calculated."""
    def __init__(self,seed,thread):self.seed=seed;self.step=(thread<<1)-7046029254386353131
    def bits(self,n):
        mask=(1<<64)-1
        self.seed=(self.seed+self.step)&mask
        z=((self.seed^(self.seed>>33))*-49064778989728563)&mask
        z=((z^(z>>33))*-4265267296055464877)&mask
        return (z>>32)>>(32-n)
    def next(self):return ((self.bits(26)<<27)+self.bits(27))/(1<<53)


def dist(a,b):return sum((x-y)**2 for x,y in zip(a,b))
def signed_square(n):return (n*n+(1<<31))%(1<<32)-(1<<31)
def point(p):return 'integration-world:'+','.join(f'{x:.9f}' for x in (*p,0,0))
def effects(points):return sorted({point(tuple(math.floor(v) for v in p))+'=DIAMOND_BLOCK' for p in points})


def expected(case,thread):
    alias=case['selector'].split('{')[0][1:].lower();targets=alias in TARGETS;vertical=alias not in ORIGIN|TARGETS
    inputs=case['inputs'];amount=case['amount'];radius=case['radius'] if case['radius']>0 else 1
    centers=[.25] if alias in ORIGIN else [40.25] if vertical else {
        'entities':[80.25,100.25],'reselected-entities':[80.25,100.25],'nonliving':[100.25],
        'locations':[-40.25,-60.25],'duplicates':[-40.25,-40.25],'empty':[]}[inputs]
    rng=Rng(case['seed'],thread);result=[]
    minimum=case['minimum']**2 if targets else signed_square(case['minimum']);spacing=signed_square(case['spacing'])
    for x0 in centers:
        center=(x0,180.25,.25);accepted=0
        for _ in range(max(0,amount)**2):
            if accepted>=amount:break
            p=None
            for trial in range(11):
                x=x0-radius+rng.next()*2*radius;z=.25-radius+rng.next()*2*radius
                y=179.25+rng.next()*2 if vertical else 180.25
                if trial==10:p=(x0,181.25,.25);break
                if case['scene']=='air' or (case['scene']=='mixed' and math.floor(x)%2!=0):p=(x,y,z);break
            if case['minimum']>0 and dist(p,center)<minimum:continue
            if case['spacing']>0 and any(dist(p,old)<spacing for old in result):continue
            p=tuple(v+d for v,d in zip(p,case['offset']))
            if case['centered']:p=(math.floor(p[0])+.5,p[1],math.floor(p[2])+.5)
            if targets or p not in result:result.append(p)
            accepted+=1
    selected=[] if case['reject'] else list(result)
    if case['limit']:selected=sorted(selected,key=lambda p:dist(p,(.25,180.25,.25)))[:case['limit']]
    return result,selected


def source(case):return 'setblock{type=DIAMOND_BLOCK} '+case['selector']
def binding(case):
    name=case['selector'].split('{')[0][1:];alias=name.lower()
    canonical='randomlocationsnearorigin' if alias in ORIGIN else 'randomlocationsneartargets' if alias in TARGETS else 'randomlocationsnearcaster'
    return 'set-block DIAMOND_BLOCK '+case['selector'].replace('@'+name,'@'+canonical,1)


def coordinates(actual,expected,label):
    require(isinstance(actual,list) and len(actual)==len(expected),label+' count')
    parsed=[]
    for value in actual:
        require(isinstance(value,str) and value.startswith('integration-world:'),label+' world')
        parts=value.split(':',1)[1].split(',');require(len(parts)==5,label+' shape')
        nums=tuple(float(p) for p in parts);require(all(math.isfinite(n) for n in nums),label+' finite')
        require(nums[3:]==(0,0),label+' orientation');parsed.append(nums[:3])
    for a,b in zip(sorted(parsed),sorted(expected)):
        require(all(abs(x-y)<2e-9 for x,y in zip(a,b)),f'{label}: {a} != {b}')


def audit(report,files):
    require(report.get('schemaVersion')==1 and report.get('evidenceMode')=='observation' and report.get('parityStatus')=='unassessed','schema/scope')
    require(report.get('validationResult')=='passed' and report.get('exitCode')==0 and report.get('clientExitCode')==0,'process result')
    require(report.get('paperJarSha256')=='39bd8c00b9e18de91dcabd3cc3dcfa5328685a53b7187a2f63280c22e2d287b9','Paper pin')
    require(report.get('mythicMobsSha256')=='9672457cafc56ff841606bfbb710947329c08720ffc207746b60130d43bf902f','native pin')
    for key in ('pluginSha256','probeJarSha256'):require(re.fullmatch('[0-9a-f]{64}',report.get(key,'')),key)
    require(set(report.get('sourceHashes',{}))==SOURCE_FILES,'source coverage')
    require(all(re.fullmatch('[0-9a-f]{64}',v) for v in report['sourceHashes'].values()),'source hashes')
    paths={'server.log','client.log','plugins/MythicMobs/Mobs/location.yml',DATA}
    require(set(files)==paths,'archive paths');manifest=report.get('files',[])
    require(len(manifest)==len(paths) and {item['path'] for item in manifest}==paths,'manifest')
    for item in manifest:require(digest(files[item['path']])==item['sha256'],'file hash')
    require(yaml.safe_load(files['plugins/MythicMobs/Mobs/location.yml'])=={'HelsteraRadiusAuditMob':{
        'Type':'COW','ThreatTable':{'Enabled':True,'DecayUnreachable':False},'Options':{'NoAI':True,'Despawn':'PERSISTENT'}}},'mob config')
    count=2*len(CASES);log=files['server.log'].decode()
    require(log.count(f'RANDOM_LOCATION_SUMMARY|observations={count}|passed=true')==1 and 'RANDOM_LOCATION_FAILED|' not in log,'completion')
    logged=Counter(re.findall(r'RANDOM_LOCATION_ROW\|path=(direct|imported)\|case=([^|\r\n]+)\|observed=true',log))
    clients=[json.loads(line.split('RANDOM_LOCATION_CLIENT ',1)[1]) for line in files['client.log'].decode().splitlines() if 'RANDOM_LOCATION_CLIENT ' in line]
    require(any(e.get('event')=='join' and e.get('name')=='RandomTester' and re.fullmatch('[0-9a-f-]{36}',e.get('uuid','')) for e in clients),'client join')
    require(sum(e.get('event')=='done' and e.get('name')=='RandomTester' for e in clients)==1,'client done')
    require([e for e in clients if e.get('event')=='summary']==[{'event':'summary','passed':True}],'client summary')
    snapshot=yaml.safe_load(files[DATA]);require(set(snapshot)=={'observations'},'snapshot schema');rows=snapshot['observations']
    require(len(rows)==report.get('observationCount')==count,'row count');seen=set();threads=set()
    for row in rows:
        require(set(row)=={'path','case','selector','source','binding','scene','inputs','seed','threadId','rngProbe','parsed','nativeRaw',
                          'nativeSelected','runtimeSelected','nativeEffects','runtimeEffects','nativeAccepted','runtimeAccepted'},'row schema')
        key=row['path'],row['case'];require(key[0] in ('direct','imported') and key[1] in CASES and key not in seen,'case identity');seen.add(key)
        c=CASES[key[1]]
        for field in ('selector','scene','inputs','seed'):require(row[field]==c[field],field+': '+key[1])
        require(row['source']==source(c) and row['binding']==(source(c) if key[0]=='direct' else binding(c)),'source/binding')
        require(type(row['threadId']) is int and row['threadId']>0,'thread id');threads.add(row['threadId'])
        rng=Rng(c['seed'],row['threadId']);require(row['rngProbe']==[rng.next() for _ in range(3)],'RNG witness')
        require(row['parsed']=={'amount':c['amount'],'maxRadius':c['radius'],'minRadius':c['minimum'],'spacing':c['spacing']},'native arguments: '+key[1])
        raw,selected=expected(c,row['threadId'])
        for field,points in (('nativeRaw',raw),('nativeSelected',selected),('runtimeSelected',selected)):coordinates(row[field],points,'/'.join((*key,field)))
        require(row['nativeEffects']==effects(selected),'native effects: '+key[1])
        require(row['runtimeEffects']==(effects(selected) if c['guard'] else []),'runtime effects: '+key[1])
        require(row['nativeAccepted'] is True and row['runtimeAccepted'] is True,'action acceptance')
        require(logged[key]==1,'log correspondence')
    require(len(threads)==1,'single server thread')
    require(seen=={(path,case) for path in ('direct','imported') for case in CASES},'case coverage')
    return {'observations':count,'nativeComparisons':count,'intentionalDifferences':2,'parityStatus':'unassessed'}


def main():
    parser=argparse.ArgumentParser();parser.add_argument('report',type=Path);parser.add_argument('--check-current',action='store_true')
    args=parser.parse_args();report=json.loads(args.report.read_text());archive=args.report.parent/report['logArchive']
    require(archive.name==report['logArchive'],'archive traversal');require(digest(archive.read_bytes())==report['logArchiveSha256'],'archive hash')
    with zipfile.ZipFile(archive) as packed:
        require(len(packed.namelist())==len(set(packed.namelist())),'duplicate archive files');files={name:packed.read(name) for name in packed.namelist()}
    result=audit(report,files)
    if args.check_current:
        for name,expected_hash in report['sourceHashes'].items():require(digest((ROOT/name).read_bytes())==expected_hash,'source drift: '+name)
        require(digest((ROOT/'helstera-plugin/target/helsteraMobs-1.0.0-full.jar').read_bytes())==report['pluginSha256'],'current JAR')
    print(json.dumps(result,sort_keys=True))


if __name__=='__main__':main()
