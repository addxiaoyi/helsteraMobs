"""Observe caster/target/trigger reads during living and terminal executions."""
import itertools
CASES={f'{scenario}_{kind.lower()}_{path}':(scenario,kind,path)
       for scenario,kind,path in itertools.product(
           ('selfdeath','peerdeath','control','command','death','cancelled','chunk','suspended'),('COW','ZOMBIE'),('direct','imported'))}
READS={}
for role in ('caster','target','trigger'):
    READS[role+'Uuid']='<'+role+'.uuid>'
    for name in ('Marker','Counter','Pre','Missing'):
        READS[role+name]='<'+role+'.var.'+name+'|absent>'
    READS[role+'Raw']='<'+role+'.var.Marker>'

def command(label,timing=''):
    values=';'.join(key+'='+value for key,value in READS.items())
    return f'command{{c="terminalreadprobe hook native {label} <caster.uuid> {values}"{timing}}} @self'

def native_mobs():
    result={'tr_peer': {'Type':'COW','Health':20,'Options':{'NoAI':True,'NoGravity':True,'Invincible':True,'Despawn':'NEVER'}}}
    for key,(scenario,kind,path) in CASES.items():
        skills=[command('pending',';delay=180')+' ~onSpawn']
        if scenario=='control':skills+=[f'skill{{s=tr_terminal_{path};delay=10}} @self ~onSpawn']
        else:
            event='Death' if scenario in ('death','selfdeath','peerdeath','cancelled') else 'Despawn'
            skills+=[f'skill{{s=tr_terminal_{path}}} @self ~on{event}']
            if scenario=='cancelled':skills+=['cancelevent{sync=true} ~onDeath']
        result['tr_'+key]={'Type':kind,'Health':20,'Options':{'NoAI':True,'NoGravity':True,'Invincible':True,
            'PreventSunburn':True,'Despawn':'CHUNK' if scenario=='chunk' else 'NEVER','ReviveHealth':20},'Skills':skills}
    return result

def native_skills():
    base = {
      'tr_terminal':{'Skills':[command('start'),'skill{s=tr_seed;delay=3} @self',
          'skill{s=tr_mutate;delay=13} @self','skill{s=tr_targetadd;delay=23} @self',command('targetadd',';delay=25'),'skill{s=tr_expired;delay=65} @self','skill{s=tr_finish;delay=125} @self','skill{s=tr_targetset;delay=135} @self',command('targetset',';delay=137'),'skill{s=tr_targetunset;delay=145} @self',command('targetunset',';delay=147')]},
      'tr_seed':{'Skills':[
          'setvariable{var=caster.Counter;type=INTEGER;value=1} @self',
          'setvariable{var=caster.Short;type=INTEGER;value=11;duration=40} @self',
          'setvariable{var=caster.Keep;type=INTEGER;value=21;duration=40} @self',
          'setvariable{var=caster.Refresh;type=INTEGER;value=31;duration=40} @self',
          'setvariable{var=caster.Reset;type=INTEGER;value=41;duration=40} @self',
          'setvariable{var=caster.Unset;type=INTEGER;value=51} @self',
          'setvariable{var=caster.Scratch;type=INTEGER;value=61} @self',
          'setvariable{var=skill.Flow;type=INTEGER;value=5} @self',command('seeded')]},
      'tr_mutate':{'Skills':[
          'variableadd{var=caster.Counter;amount=2} @self',
          'variableadd{var=caster.Keep;amount=2} @self','variableadd{var=caster.Refresh;amount=2;duration=100} @self',
          'setvariable{var=caster.Reset;type=INTEGER;value=43} @self','variableunset{var=caster.Unset} @self',
          'variableadd{var=skill.Flow;amount=3} @self',command('mutated')]},
      'tr_expired':{'Skills':[command('expired'),'variableadd{var=caster.Short;amount=9} @self',command('afteradd',';delay=2')]},
      'tr_finish':{'Skills':['variableunset{var=caster.Counter} @self',
          'variableunset{var=caster.Scratch} @self',command('finish')]},
      'tr_targetadd':{'Skills':['variableadd{var=target.Marker;amount=7} @self']},
      'tr_targetset':{'Skills':['setvariable{var=target.Marker;type=INTEGER;value=91} @self']},
      'tr_targetunset':{'Skills':['variableunset{var=target.Marker} @self']}}
    result={}
    for path in ('direct','imported'):
        for name,value in base.items():
            actions=[]
            for action in value['Skills']:
                for skill in base:action=action.replace('s='+skill+';', 's='+skill+'_'+path+';').replace('s='+skill+'}', 's='+skill+'_'+path+'}')
                actions.append(action)
            result[name+'_'+path]={'Skills':actions}
    return result

