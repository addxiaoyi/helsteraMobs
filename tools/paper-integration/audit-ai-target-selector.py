"""Independent checks of captured native scheduler state and runtime lifecycle ownership."""
import argparse,copy,hashlib,importlib.util,json,functools
from pathlib import Path
import zipfile,yaml
ROOT=Path(__file__).resolve().parents[2]
spec=importlib.util.spec_from_file_location('ai_cases',Path(__file__).with_name('ai-target-selector-cases.py'))
c=importlib.util.module_from_spec(spec);spec.loader.exec_module(c)
PROBE='plugins/HelsteraAiTargetSelectorProbe/'
def require(value,message):
    if not value:raise ValueError(message)
def digest(data):return hashlib.sha256(data).hexdigest()
@functools.lru_cache(maxsize=512)
def document(data):return yaml.safe_load(data)
def clean(value):
    if isinstance(value,dict):return {k:clean(v) for k,v in value.items() if k not in ('tick','controllerTarget')}
    if isinstance(value,list):return [clean(v) for v in value]
    return value
def equal(a,b,where):require(a==b,where+': '+str(a)+' != '+str(b))
def snapshot(state):
    require(set(state) in ({'tick','target','starts','stops','recipientTarget','goals'},{'tick','target','starts','stops','recipientTarget','goals','controllerTarget'}),'snapshot fields')
    require(type(state['tick']) is int and state['tick']>0,'tick')
    require(state['recipientTarget']=='none','recipient was mutated')
    require(type(state['starts']) is int and type(state['stops']) is int and state['starts']>=0 and state['stops']>=0,'callback counts')
    for goal in state['goals']:
        fields={'class','priority','running','types'}
        if goal['class']=='NearestAttackableTargetGoal':fields|={'targetType','interval','mustSee','mustReach'}
        equal(set(goal),fields,'goal fields');require(type(goal['priority']) is int and goal['priority']>=0,'priority')
        require(type(goal['running']) is bool and goal['types'] in (['TARGET'],['UNKNOWN_BEHAVIOR']),'goal running/types')
        if 'targetType' in goal:
            require(type(goal['interval']) is int and type(goal['mustSee']) is bool and type(goal['mustReach']) is bool,'nearest fields')
def check_row(row,case,index,path):
    require(row['index']==index and row['case']==case['key'] and row['path']==path and row['primary'] is True,'row identity')
    require(row['casterType']==case['caster'],'caster type')
    require(set(row)=={'case','index','path','primary','casterType','baseline','afterRebind','events','eventsBeforeRebind'}|({'stages'} if case['stages'] else set())|({'afterControllerTick','afterClose'} if path!='native' and not case.get('generic') and case['caster'] in ('COW','WOLF','SLIME') else set()),'row fields')
    for key in ('baseline','afterRebind','afterControllerTick','afterClose'):
        if key in row:snapshot(row[key])
    for i,stage in enumerate(case['stages']):
        actual=row['stages']['s'+str(i)]
        equal(set(actual),{'source','before','result','immediate','settled'}|({'mapped'} if path!='native' else set()),'stage fields')
        equal(actual['source'],stage['source'],'source')
        result='EXECUTED' if path!='native' else 'SUCCESS'
        equal(actual['result'],result,'result')
        if path!='native':
            mapped=stage['source']
            if path=='imported':mapped='run-ai-target-selector'+mapped[len(mapped.split('{')[0].split(' ')[0]):]
            equal(actual['mapped'],mapped,'mapped')
        for label in ('before','immediate','settled'):snapshot(actual[label])
        require(actual['before']['tick']==actual['immediate']['tick'],'synchronous cast')
        equal(actual['settled']['tick']-actual['immediate']['tick'],stage['wait']+1,'settle ticks')
        previous=row['baseline'] if i==0 else row['stages']['s'+str(i-1)]['settled']
        equal(actual['before'],previous,'stage continuity')
    key=case['key'];final=row['afterRebind'];goals=final['goals']
    if key in ('quoted_rule','single_quoted_rule','double_space','overflow_priority','missing_selector','unknown_rule','dynamic_string','wrong_field','missing_field','owner_wrong_type','noncreature','player_caster','nonliving_caster'):
        require(not goals and final['target']=='none','inert control '+key)
    if key in ('players','monsters','villagers','golems'):
        require(len(goals)==1,'one target goal');g=goals[0]
        require(g['priority']==3 and g['targetType']==case['goalTarget'] and g['interval']==5 and g['mustReach'] is False,'vanilla constructor')
        equal(g['mustSee'],key in ('players','monsters'),'vanilla sight')
    if case.get('expectedTarget'):equal(final['target'],case['expectedTarget'],key+' target')
    if key=='duplicate_rules':equal([g['priority'] for g in goals],[1,1],'duplicate priorities')
    if key=='alias_precedence':require(len(goals)==1 and goals[0]['priority']==4 and goals[0]['targetType']=='IronGolem','alias priority')
    if key=='conditional_alias_precedence':require(len(goals)==1 and goals[0]['interval']==4 and goals[0]['mustSee'] is False and goals[0]['mustReach'] is True,'conditional aliases')
    if key=='preemption':
        equal(row['stages']['s1']['settled']['target'],'villagers','initial low priority');equal(final['target'],'monsters','priority preemption')
    if key=='equal_priority':equal(final['target'],'villagers','equal priority does not preempt')
    if key in ('clear_running','reset_running'):
        require(row['baseline']['starts']==1 and row['baseline']['goals'][0]['running'] is True,'seed running')
        require(final['goals']==[] and final['target']=='none' and final['stops']==0,'raw clear must not stop')
    if key=='noninterruptible':require(final['target']=='none' and final['stops']==0 and final['goals'][0]['running'] is True,'noninterruptible')
    if key=='interruptible':require(final['target']=='players' and final['stops']==1 and final['goals'][0]['running'] is False,'interruptible')
    if key=='profile_order':equal([g['targetType'] for g in goals],['Villager'],'profile source order')
    if key=='profile_implicit':equal([g['priority'] for g in goals],[2,3],'profile implicit index')
    if key=='profile_rebind':equal([g['targetType'] for g in goals],['Monster'],'dynamic mutation replay')
    if key in ('conditional_default','conditional_players','conditional_nonlist','conditional_alias_precedence','faction_nearestOtherFaction','priority_zero','duplicate_rules','interruptible','profile_implicit'):
        equal(final['target'],'players',key+' selection')
    if key in ('conditional_unknown','conditional_unknown_false','faction_generic_caster','quoted_faction'):
        equal(final['target'],'none',key+' rejection')
    if key=='conditional_reject_players':equal(final['target'],'cow','conditional nonplayer')
    if key in ('faction_otherFactionMonsters','faction_specificFaction','faction_nearestSpecificFactionMonsters'):
        equal(final['target'],'monsters','faction selection')
    if key=='alias_precedence':equal(final['target'],'golems','alias selection')
    if key=='profile_order':equal(final['target'],'villagers','profile selection')
    if key=='profile_spacing':equal(goals,[],'malformed spacing aborts profile')
    if key=='profile_overflow':require(len(goals)==1 and goals[0]['targetType']=='Player' and goals[0]['priority']==1,'overflow aborts remaining profile')
    previous=row['stages']['s'+str(len(case['stages'])-1)]['settled'] if case['stages'] else row['baseline']
    if 'rebind' not in case or path=='native':equal(final,previous,'final continuity')
    else:
        expected=copy.deepcopy(previous);expected['target']='none'
        expected['goals'][0]['running']=False
        equal(final,expected,'rebind stops and recreates dynamic goal')
    if 'afterControllerTick' in row:
        equal(clean(row['afterControllerTick']),clean(final),'controller must preserve native scheduler')
        equal(row['afterControllerTick']['controllerTarget'],final['target'],'controller observes native target')
        expected=copy.deepcopy(row['afterControllerTick']);expected['target']='none';expected['goals']=[]
        if case.get('sentinel'):
            expected['goals']=[dict(row['baseline']['goals'][0],running=key=='noninterruptible')]
        elif case['caster']=='WOLF':expected['goals']=row['baseline']['goals']
        equal(row['afterClose'],expected,'owned cleanup and original selector restoration')
    for events in (row['eventsBeforeRebind'],row['events']):
        for event in events:
            equal(set(event),{'target','reason','cancelled','tick'},'event fields')
            require(event['cancelled'] is False and type(event['tick']) is int and event['tick']>0,'event types/cancellation')
            require(event['target'] in ('none','players','monsters','cow','villagers','golems'),'event target')
            require(event['reason'] in ('FORGOT_TARGET','CLOSEST_PLAYER','CLOSEST_ENTITY','TARGET_ATTACKED_ENTITY','OWNER_ATTACKED_TARGET','TARGET_ATTACKED_OWNER'),'event reason')
        require([e['tick'] for e in events]==sorted(e['tick'] for e in events),'event order')
    before=row['eventsBeforeRebind'];equal(row['events'][:len(before)],before,'retained event prefix')
    tail=[]
    if 'afterClose' in row and previous['target']!='none':
        tail=[{'target':'none','reason':'FORGOT_TARGET','cancelled':False,'tick':final['tick']}]
    equal(row['events'][len(before):],tail,'rebind/close events')
    for event in before:require(event['tick']<=final['tick'],'event before snapshot')
    observed=[(e['target'],e['reason']) for e in before]
    if key=='preemption':equal(observed,[('villagers','CLOSEST_ENTITY'),('none','FORGOT_TARGET'),('monsters','CLOSEST_ENTITY')],'preemption events')
    elif key=='profile_rebind':equal(observed,[('villagers','CLOSEST_ENTITY'),('none','FORGOT_TARGET'),('monsters','CLOSEST_ENTITY')],'profile mutation events')
    elif key=='profile_implicit':require(observed in ([('players','CLOSEST_PLAYER')],[('monsters','CLOSEST_ENTITY'),('none','FORGOT_TARGET'),('players','CLOSEST_PLAYER')]),'profile scheduling events')
    elif final['target']=='none':equal(observed,[],'inert events')
    else:
        reason={'hurt_by':'TARGET_ATTACKED_ENTITY','owner_target':'OWNER_ATTACKED_TARGET','owner_hurt':'OWNER_ATTACKED_TARGET','owner_hurt_by':'TARGET_ATTACKED_OWNER'}.get(key,'CLOSEST_PLAYER' if final['target']=='players' else 'CLOSEST_ENTITY')
        equal(observed,[(final['target'],reason)],'selection event')
def audit(report,files):
    require(report['schemaVersion']==1 and report['exitCode']==0 and report['clientExitCode']==0,'process status')
    require(report['paperJarSha256']=='39bd8c00b9e18de91dcabd3cc3dcfa5328685a53b7187a2f63280c22e2d287b9','pinned Paper')
    require(report['mythicMobsSha256']=='9672457cafc56ff841606bfbb710947329c08720ffc207746b60130d43bf902f','pinned native')
    require(report['observationCount']==len(c.CASES)*3,'count')
    require(len(report['files'])==len(files),'manifest count')
    equal({item['path'] for item in report['files']},set(files),'manifest paths')
    for item in report['files']:require(item['path'] in files and digest(files[item['path']])==item['sha256'],'file hash '+item['path'])
    equal(document(files[PROBE+'cases.yml']),{'cases':c.CASES},'case source')
    require('AI_TARGET_SUMMARY|observations='+str(len(c.CASES)*3)+'|completed=true' in files['server.log'].decode(),'server completion')
    require('"passed":true' in files['client.log'].decode(),'client completion')
    survey=document(files[PROBE+'survey.yml']);equal(survey['catalog']['OWNERHURT'],'OwnerHurtGoal','loaded alias')
    rows={}
    for i,case in enumerate(c.CASES):
        for offset,path in enumerate(('native','direct','imported')):
            idx=i*3+offset;row=document(files[PROBE+f'observations/{idx:03d}.yml']);check_row(row,case,idx,path);rows[(case['key'],path)]=row
        native=rows[(case['key'],'native')]
        for path in ('direct','imported'):
            row=rows[(case['key'],path)]
            equal(clean(row['baseline']),clean(native['baseline']),case['key']+'/'+path+'/baseline')
            for j in range(len(case['stages'])):
                for phase in ('immediate','settled'):
                    equal(clean(row['stages']['s'+str(j)][phase]),clean(native['stages']['s'+str(j)][phase]),case['key']+'/'+path+'/'+phase)
            if 'rebind' not in case:equal(clean(row['afterRebind']),clean(native['afterRebind']),case['key']+'/'+path+'/final')
    return rows

def main():
    p=argparse.ArgumentParser();p.add_argument('report',type=Path);p.add_argument('--check-current',action='store_true');args=p.parse_args()
    report=json.loads(args.report.read_text());archive=args.report.with_name(report['logArchive']);require(digest(archive.read_bytes())==report['logArchiveSha256'],'archive hash')
    with zipfile.ZipFile(archive) as z:files={name:z.read(name) for name in z.namelist()}
    audit(report,files)
    if args.check_current:
        require(digest((ROOT/'helstera-plugin/target/helsteraMobs-1.0.0-full.jar').read_bytes())==report['pluginSha256'],'current jar')
        for name,sha in report['sourceHashes'].items():require(digest((ROOT/name).read_bytes())==sha,'current source '+name)
    print('passed',report['observationCount'],'observations')
if __name__=='__main__':main()
