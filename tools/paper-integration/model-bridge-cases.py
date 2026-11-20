"""Pinned native model bridge stimuli and independently expected observable state."""
MECHANICS = {
    'spawn': 'modelspawn{model=example/emberling;scale=1.25;animation=idle;loop=true}',
    'scale': 'modelscale{m=2}',
    'play': 'modelplay{m=walk;loop=true;priority=8}',
    'stop': 'modelstop{}',
    'stop_named': 'modelstop{animation=walk}',
    'stop_other': 'modelstop{animation=idle}',
    'native_ignored_tail': 'modelscale{m=2} bogus',
    'remove': 'modelremove{}',
    'bad_model': 'modelspawn{m=missing/model}',
    'bad_spawn_animation': 'modelspawn{m=example/emberling;animation=missing}',
    'bad_animation': 'modelplay{m=missing}',
    'bad_scale': 'modelscale{scale=NaN}',
    'duplicate': 'modelscale{scale=3;scale=4}',
    'alias_conflict': 'modelscale{m=3;scale=4}',
    'bad_boolean': 'modelplay{m=walk;loop=yes}',
    'bad_integer': 'modelplay{m=walk;priority=1.5}',
    'bad_key': 'modelscale{scale=3;typo=1}',
    'empty_target': 'modelscale{scale=9}',
}
PREDICATES = {'spawned':'modelspawned', 'removed':'modelremoved', 'playing':'modelplaying',
    'walk':'modelplaying{m=walk}', 'unknown':'modelplaying{m=missing}', 'invalid':'modelplaying{typo=yes}'}
def native_skills():
    result = {'mb_'+k:{'Skills':[v+(' @PIR{r=1}' if k=='empty_target' else ' @trigger')]} for k,v in MECHANICS.items()}
    for name,condition in PREDICATES.items():
        for suffix in ('true','false'):
            for kind in ('caster','target'):
                key=f'{kind}_{name}_{suffix}'
                result['mb_'+key]={'Conditions' if kind=='caster' else 'TargetConditions':[condition+' '+suffix],
                    'Skills':[f'command{{c="modelbridgeprobe mark {key}"}} @trigger']}
    result['mb_unsupported_action']={'Conditions':['modelremoved cast mb_spawn'],'Skills':['command{c="modelbridgeprobe mark unsupported_action"} @trigger']}
    return result

def native_mobs():
    return {name:{'Type':'HUSK','Health':20,'Options':{'NoAI':True,'Silent':True,'Invincible':True,'Despawn':'PERSISTENT'}}
            for name in ('MbCaster','MbAuto','MbUnmatched','MbCancelled','MbBadScale','MbBadAnimation')}

EXPECTED = {}
def expect(key, **values): EXPECTED[key] = {k:str(v).lower() if isinstance(v,bool) else str(v) for k,v in values.items()}
def bound(key,scale=1.25,animation='idle',state='PLAYING'):
    expect(key,bindings=1,scale=scale,animation=animation,state=state,valid=True,casterBindings=0)
def absent(key): expect(key,bindings=0,scale='absent',animation='absent',state='absent',valid=True,casterBindings=0)
expect('cold_capabilities',skills=True,conditions=True,binding=True)
for key in ('initial','bad_model','bad_spawn_animation'): absent(key)
for key in ('spawn','repeat_spawn'): bound(key)
bound('scale',2.0);bound('play',2.0,'walk');bound('stop',2.0,'absent','IDLE')
for key in ('bad_animation','bad_scale','duplicate','alias_conflict','bad_boolean','bad_integer','bad_key','empty_target'):
    bound(key,2.0,'absent','IDLE')
for phase,truth in [('pre',{'spawned':False,'removed':True,'playing':False}),
                    ('playing',{'spawned':True,'removed':False,'playing':True,'walk':True}),
                    ('removed',{'spawned':False,'removed':True,'playing':False})]:
    for kind in ('caster','target'):
        for name,value in truth.items():
            actual=value if kind=='target' else name=='removed'
            for suffix in ('true','false'):expect(f'{phase}_{kind}_{name}_{suffix}',hooks=int(actual==(suffix=='true')))
for name in ('unknown','invalid'):
    for suffix in ('true','false'):expect(f'playing_target_{name}_{suffix}',hooks=0)
expect('automatic',bindings=1,scale=1.5,animation='walk',state='PLAYING',valid=True,casterBindings=0)
for key in ('unmatched','invalid_auto_scale','invalid_auto_animation'):absent(key)
expect('cancelled_auto',bindings=0,valid=False)
expect('async_mechanic',bindings=1,scale=2.0,animation='absent',state='IDLE',valid=True,casterBindings=0)
expect('async_condition_true',result=False);expect('async_condition_false',result=False)
expect('disabled_capabilities',skills=False,conditions=False,binding=False)
for suffix in ('true','false'):expect('disabled_condition_'+suffix,result=False)
expect('disabled_cached',result='MISSING_COMPATIBILITY')
expect('reenabled_capabilities',skills=False,conditions=False,binding=True)
expect('reenabled_cached',result='MISSING_COMPATIBILITY')
expect('reloaded_capabilities',skills=True,conditions=True,binding=True)
bound('reload_scale',3.0,'absent','IDLE')
expect('reloaded_old_proxy',result='MISSING_COMPATIBILITY')
absent('removed')
expect('cleanup',bindings=0,visuals=0,ownedTasks=0)

expect('automatic_duplicate',bindings=1,scale=1.5,animation='walk',state='PLAYING',valid=True,casterBindings=0)
bound('disabled_after_cast',1.75,'absent','IDLE')
bound('reenabled_after_cast',1.75,'absent','IDLE')
bound('after_old_proxy',3.0,'absent','IDLE')
expect('live_reload_old_proxy',result='MISSING_COMPATIBILITY')
bound('live_reload_scale',4.0,'absent','IDLE')
absent('repeat_remove')

bound('stop_other',2.0,'walk');bound('stop_named',2.0,'absent','IDLE');bound('native_ignored_tail',2.0,'absent','IDLE')
expect('paused_target_playing_true',hooks=0);expect('paused_target_playing_false',hooks=1)
expect('unsupported_action',hooks=0)
