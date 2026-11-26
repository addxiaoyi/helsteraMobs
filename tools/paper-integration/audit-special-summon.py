"""Independent actor, lifecycle, argument and native/runtime comparison checks."""
import argparse
import copy
import hashlib
import importlib.util
import json
import math
from pathlib import Path
import re
from uuid import UUID
import zipfile
import yaml
ROOT=Path(__file__).resolve().parents[2]
spec=importlib.util.spec_from_file_location('special_cases',Path(__file__).with_name('special-summon-cases.py'))
c=importlib.util.module_from_spec(spec);spec.loader.exec_module(c)
PROBE='plugins/HelsteraSpecialSummonProbe/'
def require(value,message):
    if not value:raise ValueError(message)
def digest(data):return hashlib.sha256(data).hexdigest()
def same(actual,expected,where):
    if isinstance(expected,dict):
        require(isinstance(actual,dict) and set(actual)==set(expected),where+' fields')
        for key,value in expected.items():same(actual[key],value,where+'/'+key)
    elif isinstance(expected,list):
        require(isinstance(actual,list) and len(actual)==len(expected),where+' length')
        for i,value in enumerate(expected):same(actual[i],value,where+'/'+str(i))
    elif type(expected) in (int,float) and type(actual) in (int,float):
        require(math.isfinite(actual) and abs(actual-expected)<=1e-5*max(1,abs(expected)),where+': '+str(actual)+' != '+str(expected))
    else:require(type(actual)==type(expected) and actual==expected,where+': '+str(actual)+' != '+str(expected))
def normalize(row,state):
    labels={row['caster']:'caster',row['target']:'target',row['trigger']:'trigger'}
    labels.update({uid:'seed'+str(i) for i,uid in enumerate(row['seeds'])})
    labels.update({e['uuid']:'child'+str(i) for i,e in enumerate(row['after']['entities'])})
    def visit(value):
        if isinstance(value,dict):return {k:visit(v) for k,v in value.items() if k not in ('entityId','ticks','tick','nativeReason')}
        if isinstance(value,list):return [visit(v) for v in value]
        return labels.get(value,value) if isinstance(value,str) else value
    return visit(copy.deepcopy(state))
def check_cloud(entity,case,path):
    key=case['key'];early=key in ('cloud_invalid_effect','cloud_sculk_native_error','cloud_bad_item')
    require(entity['type']=='AREA_EFFECT_CLOUD' and entity['valid'] is True,'cloud entity')
    require(entity['source']=='none' and entity['wait']==20 and entity['reapplication']==20,'cloud source/timers')
    require(entity['duration']==(600 if early else 200) or key in ('cloud_location','cloud_alias_priority','cloud_integer_range','cloud_trigger_placeholder','cloud_expiry','cloud_effect_application'),'cloud duration')
    if early:
        require(entity['effects']==[] and entity['duration']==600 and entity['radius']==3,'partial cloud state')
        same(entity['particle'],'FLAME' if key=='cloud_invalid_effect' else 'ENTITY_EFFECT',key+'/partial particle')
        same(entity['particleData'],'none' if key=='cloud_invalid_effect' else [1.,1.,1.,1.],key+'/partial particle data')
        return
    potion=dict(type='minecraft:slowness',duration=100,amplifier=1,ambient=True,particles=True,icon=True)
    duration=200;radius=2;on_use=0;radius_use=0;radius_tick=0
    if key=='cloud_location':potion.update(type='minecraft:speed',duration=80,amplifier=2);duration=35;radius=4;on_use=-7;radius_use=-.25;radius_tick=-.02
    if key=='cloud_alias_priority':potion.update(duration=120,amplifier=2);duration=70;radius=3
    if key=='cloud_typo':on_use=-6
    if key=='cloud_signed_changes':on_use=4;radius_use=.5;radius_tick=.125
    if key=='cloud_integer_range':potion.update(duration=80,amplifier=2);duration=31;radius=4
    if key=='cloud_float_range':radius_use=1;radius_tick=.125
    if key=='cloud_trigger_placeholder':duration=1 if path=='native' else 175
    if key=='cloud_expiry':duration=20
    if key=='cloud_effect_application':potion.update(amplifier=2);duration=120;radius=3;on_use=-10;radius_use=-.25
    same(entity['effects'],[potion],key+'/potion')
    for field,value in dict(duration=duration,radius=radius,durationOnUse=on_use,radiusOnUse=radius_use,radiusPerTick=radius_tick).items():same(entity[field],value,key+'/'+field)
    particle='CLOUD';data='none'
    if key=='cloud_defaults':particle='DUST';data=dict(color=[1.,0.,0.],size=1.)
    elif key=='cloud_dust_rgba':particle='DUST';data=dict(color=[18/255,52/255,86/255],size=2.)
    elif key=='cloud_dust_transition':particle='DUST_COLOR_TRANSITION';data=dict(from_=[17/255,34/255,51/255],to=[68/255,85/255,102/255],size=.5);data['from']=data.pop('from_')
    elif key=='cloud_entity_color':particle='ENTITY_EFFECT';data=[0x8b/255,0xaf/255,0xe0/255,1.]
    elif key=='cloud_item':particle='ITEM';data='DIAMOND'
    elif key=='cloud_block_data':particle='FALLING_DUST';data='minecraft:red_sand'
    elif key=='cloud_alias_collision':particle='BLOCK';data='minecraft:stone'
    elif key=='cloud_shriek':particle='SHRIEK';data=0
    same(entity['particle'],particle,key+'/particle');same(entity['particleData'],data,key+'/particleData')
def check_row(row,case,index,path):
    require(set(row)=={'index','path','case','source','primary','caster','target','trigger','origin','casterLocation','targetLocation','triggerLocation','seeds','startTick','mapped','result','after','events','settled','endTick'},'row fields')
    source=case['mechanic']+'{'+case['arguments']+'} @'+case['targeter']
    require((row['index'],row['case'],row['path'],row['source'])==(index,case['key'],path,source),'identity/source')
    require(row['primary'] is True and row['endTick']-row['startTick']==max(1,case['wait']),'thread/tick span')
    require(len({row['caster'],row['target'],row['trigger']})==3 and len(row['seeds'])==case['seed'],'actors/seeds')
    for uid in (row['caster'],row['target'],row['trigger'],*row['seeds']):
        require(str(UUID(uid))==uid,'actor UUID')
    same([row['casterLocation'],row['targetLocation'],row['triggerLocation'],row['origin']],[[100.,170.,0.],[110.,170.,0.],[130.,175.,0.],[120.,170.,0.]],'actor locations')
    if path=='imported':
        if case.get('constructorError'):mapped='UNSUPPORTED'
        elif case['kind']=='passenger':
            params=case['arguments'].split(';');mob='MissingRider' if case['key']=='passenger_missing' else 'AuditWolf' if case['key']=='passenger_wolf_owner' else 'AuditCat' if case['key']=='passenger_cat_not_tamed' else 'AuditRider'
            params=[p for p in params if not p.startswith('passenger=')]+['passenger=mythic_'+mob.lower()]
            mapped='summon-passenger{'+ ';'.join(params)+'} @'+case['targeter']
        else:mapped={'cloud':'summon-cloud','fall':'summon-falling-block'}[case['kind']]+source[len(case['mechanic']):]
        require(row['mapped']==mapped,'import mapping '+case['key'])
    else:require(row['mapped']==source,'direct/native mapping')
    if case.get('constructorError'):result='CONSTRUCTOR:NullPointerException' if path=='native' else 'UNSUPPORTED' if path=='imported' else 'UNBOUND'
    elif path!='native':result='EXECUTED'
    elif case['key']=='passenger_missing':result='INVALID_CONFIG'
    elif case['key'] in ('fall_invalid','fall_dynamic_native_inert'):result='ERROR:NullPointerException'
    elif case['key'] in ('cloud_sculk_native_error','cloud_bad_item'):result='ERROR:IllegalArgumentException'
    else:result='SUCCESS'
    require(row['result']==result,'result '+case['key']+'/'+path+': '+row['result'])
    wanted=0 if case.get('constructorError') else case['count'];entities=row['after']['entities']
    require(len(entities)==wanted and len(row['settled']['entities'])==wanted,'spawn count '+case['key'])
    for label in ('after','settled'):
        require(set(row[label])=={'entities','passengers','victimEffects','landingBlock','drops'},'snapshot fields')
    for i,entity in enumerate(entities):
        require(str(UUID(entity['uuid']))==entity['uuid'],'child UUID')
        require(isinstance(entity['entityId'],int) and entity['entityId']>0 and entity['ticks']==0,'entity identity/age')
        settled=row['settled']['entities'][i];require(entity['uuid']==settled['uuid'] and entity['entityId']==settled['entityId'],'entity continuity')
        fields={'uuid','entityId','type','valid','location','ticks'}
        fields|=({'particle','particleData','effects','duration','durationOnUse','radius','radiusOnUse','radiusPerTick','wait','reapplication','source'} if case['kind']=='cloud'
                 else {'block','drop','hurt'} if case['kind']=='fall' else {'owner','parent','level','vehicle'})
        if case['key'] in ('passenger_wolf_owner','passenger_cat_not_tamed'):fields.add('tamed')
        require(set(entity)==fields and set(settled)==fields,'entity fields')
        require(entity['valid'] is True,'new entity validity')
        same(entity['location'],row['casterLocation'] if case['kind']=='passenger' else row['origin'] if case['targeter']=='origin' else row['triggerLocation'] if case['targeter']=='trigger' else row['targetLocation'],'spawn direction')
        age={'cloud_expiry':40,'cloud_effect_application':30,'fall_landing':15,'fall_item_drop':15}.get(case['key'],1)
        require(settled['ticks']==age,'settled entity age')
        require(entity['uuid'] not in (row['caster'],row['target'],row['trigger'],*row['seeds']),'new entity')
        if case['kind']=='cloud':check_cloud(entity,case,path)
        if case['kind']=='fall':
            require(entity['type']=='FALLING_BLOCK' and entity['drop'] is True and entity['hurt'] is False,'falling flags')
            block={'fall_defaults':'minecraft:dirt','fall_namespaced':'minecraft:red_sand','fall_state':'minecraft:oak_log[axis=x]', 'fall_priority':'minecraft:stone_bricks','fall_air':'minecraft:air','fall_nonliving':'minecraft:gravel','fall_landing':'minecraft:sand','fall_item_drop':'minecraft:red_sand'}[case['key']]
            require(entity['block']==block,'block state')
        if case['kind']=='passenger':
            require(entity['owner']==row['caster'] and entity['parent']=='none' and entity['level']==case['level'],'passenger owner/level')
            require(entity['type']==('WOLF' if case['key']=='passenger_wolf_owner' else 'CAT' if case['key']=='passenger_cat_not_tamed' else 'PIG'),'passenger type')
            if 'tamed' in entity:require(entity['tamed'] is False,'unexpected taming')
        if case.get('difference')=='public_integer':
            require(settled['duration']==(1 if path=='native' else 175),'settled public integer extension')
    for event in row['events']:
        fields={'event','entity','type','vehicle','owner','level','cancelled','tick'}
        if path=='native' and event['event']=='spawn':fields.add('nativeReason')
        require(set(event)==fields and event['tick']==row['startTick'],'event fields/timing')
    if case['kind']=='passenger':
        expected={'caster':[]};expected.update({'seed'+str(i):[] for i in range(case['seed'])})
        if case['seed']:expected['caster']=[row['seeds'][0]];expected['seed0']=[row['seeds'][1]]
        if wanted and not case['cancel']:
            if case['key']=='passenger_stack':expected['caster']=[];expected['seed0']=[entities[0]['uuid']]
            else:expected['caster']=[entities[0]['uuid']]
        same(row['after']['passengers'],expected,'passenger replacement '+case['key'])
        if wanted:
            spawn=[e for e in row['events'] if e['event']=='spawn' and e['entity']==entities[0]['uuid']]
            require(len(spawn)==1 and spawn[0]['owner']=='none' and spawn[0]['level']==case['level'],'spawn before owner/level')
            if path=='native':require(spawn[0]['nativeReason']=='SUMMON','native summon reason')
            mounts=[e for e in row['events'] if e['event']=='mount' and e['entity']==entities[0]['uuid']]
            require(len(mounts)==1 and mounts[0]['owner']=='none' and mounts[0]['cancelled']==case['cancel'],'mount before owner/cancellation')
            require([e['event'] for e in row['events']]==['spawn','mount'],'spawn/mount ordering')
    if case['key']=='cloud_expiry':require(row['settled']['entities'][0]['valid'] is False,'cloud expiration')
    if case['key']=='fall_air':require(row['settled']['entities'][0]['valid'] is False and row['settled']['drops']==[],'air disappears without a drop')
    if case['key']=='cloud_effect_application':
        effects=row['settled']['victimEffects'];require(len(effects)==1 and effects[0]['type']=='minecraft:slowness' and effects[0]['amplifier']==2,'cloud applies potion')
        require(row['settled']['entities'][0]['radius']<3 and row['settled']['entities'][0]['duration']<120,'cloud use reductions')
    if case['key'] in ('fall_landing','fall_item_drop'):
        require(row['settled']['entities'][0]['valid'] is False,'falling terminal')
        if case['key']=='fall_landing':require(row['settled']['landingBlock']=='minecraft:sand' and row['settled']['drops']==[],'falling placement')
        else:require(row['settled']['drops']==['RED_SAND:1'] and row['settled']['landingBlock']=='minecraft:torch','falling item conservation')
    return row

def audit(report,files):
    require(report['schemaVersion']==1 and report['evidenceMode']=='observation' and report['parityStatus']=='unassessed','report mode')
    require(report['paperJarSha256']=='39bd8c00b9e18de91dcabd3cc3dcfa5328685a53b7187a2f63280c22e2d287b9' and report['mythicMobsSha256']=='9672457cafc56ff841606bfbb710947329c08720ffc207746b60130d43bf902f','pinned artifacts')
    require(report['clientExitCode']==0,'observer exit')
    require(report['exitCode']==0 and report['observationCount']==len(c.CASES)*3,'report count/exit')
    entries=report['files'];require(len(entries)==len({e['path'] for e in entries}) and set(files)=={e['path'] for e in entries},'archive inventory')
    expected={'server.log','client.log',PROBE+'particles.yml',PROBE+'cases.yml','plugins/MythicMobs/Mobs/special-summon.yml'}
    expected|={PROBE+f'observations/{i:03}.yml' for i in range(len(c.CASES)*3)}
    expected|={PROBE+'imports/mythic_'+name.lower()+'.yml' for name in c.MOBS}
    require(set(files)==expected,'required archive members')
    for key in ('pluginSha256','probeJarSha256','paperJarSha256','mythicMobsSha256'):
        require(re.fullmatch('[0-9a-f]{64}',report[key]) is not None,'artifact digest '+key)
    require(len(report['sourceHashes'])==24 and all(re.fullmatch('[0-9a-f]{64}',v) for v in report['sourceHashes'].values()),'source hash inventory')
    for entry in entries:require(digest(files[entry['path']])==entry['sha256'],'file hash')
    require(yaml.safe_load(files[PROBE+'cases.yml'])=={'cases':c.CASES},'case manifest')
    require(yaml.safe_load(files['plugins/MythicMobs/Mobs/special-summon.yml'])==c.MOBS,'native mob definitions')
    particles=yaml.safe_load(files[PROBE+'particles.yml'])
    require(len(particles)==106,'particle survey count')
    remapped={'SUSPENDED_DEPTH':'UNDERWATER','SPELL_MOB_AMBIENT':'ENTITY_EFFECT','BLOCK_DUST':'BLOCK','BARRIER':'missing','LIGHT':'missing'}
    for name,value in particles.items():require(value==remapped.get(name,name),'native particle conversion')
    client=[json.loads(line.split(' ',1)[1]) for line in files['client.log'].decode().splitlines() if line.startswith('SPECIAL_SUMMON_CLIENT ')]
    require(any(e.get('event')=='spawn' and e.get('username')=='SummonObserver' for e in client) and client[-1]==dict(event='summary',passed=True,error=None),'observer lifecycle')
    log=files['server.log'].decode();require(f'SPECIAL_SUMMON_SUMMARY|observations={len(c.CASES)*3}|completed=true' in log and 'SPECIAL_SUMMON_FAILED|' not in log,'completion marker')
    all_ids=set()
    for i,case in enumerate(c.CASES):
        rows=[]
        for p,path in enumerate(('native','direct','imported')):
            index=3*i+p;row=yaml.safe_load(files[PROBE+f'observations/{index:03}.yml']);check_row(row,case,index,path);rows.append(row)
            for entity in row['after']['entities']:
                require(entity['uuid'] not in all_ids,'entity reused across observations');all_ids.add(entity['uuid'])
        for label in ('after','settled','events'):
            native=normalize(rows[0],rows[0][label])
            for row in rows[1:]:
                actual=normalize(row,row[label])
                if case.get('difference')=='public_integer' and label in ('after','settled'):
                    native=copy.deepcopy(native);native['entities'][0]['duration']=175
                same(actual,native,case['key']+'/'+row['path']+'/'+label)
    return len(c.CASES)*3

def main():
    p=argparse.ArgumentParser();p.add_argument('report',type=Path);p.add_argument('--check-current',action='store_true');a=p.parse_args();report=json.loads(a.report.read_text());archive=a.report.with_name(report['logArchive'])
    require(digest(archive.read_bytes())==report['logArchiveSha256'],'archive hash')
    with zipfile.ZipFile(archive) as z:
        require(len(z.namelist())==len(set(z.namelist())),'duplicate archive member');files={n:z.read(n) for n in z.namelist()}
    if a.check_current:
        require(digest((ROOT/'helstera-plugin/target/helsteraMobs-1.0.0-full.jar').read_bytes())==report['pluginSha256'],'current plugin hash')
        for path,expected in report['sourceHashes'].items():require(digest((ROOT/path).read_bytes())==expected,'current source '+path)
    print('Special summon audit passed:',audit(report,files),'observations')
if __name__=='__main__':main()
