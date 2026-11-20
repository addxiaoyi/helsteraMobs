"""Native variable mutations and expiry in living and terminal skill executions."""
import itertools
CASES={f'{scenario}_{kind.lower()}_{path}':(scenario,kind,path)
       for scenario,kind,path in itertools.product(
           ('control','command','death','cancelled','chunk','suspended'),('COW','ZOMBIE'),('direct','imported'))}
NAMES=('Marker','Pre','Counter','Short','Keep','Refresh','Reset','Unset','Scratch')

def command(label,timing=''):
    values=' '.join('<caster.var.'+name+'|absent>' for name in NAMES)
    return f'command{{c="terminalvarprobe hook native {label} <caster.uuid> {values} <skill.var.Flow|absent>"{timing}}} @self'

def native_mobs():
    result={}
    for key,(scenario,kind,path) in CASES.items():
        skills=[command('pending',';delay=180')+' ~onSpawn']
        if scenario=='control':skills+=[f'skill{{s=tv_terminal_{path};delay=10}} @self ~onSpawn']
        else:
            event='Death' if scenario in ('death','cancelled') else 'Despawn'
            skills+=[f'skill{{s=tv_terminal_{path}}} @self ~on{event}']
            if scenario=='cancelled':skills+=['cancelevent{sync=true} ~onDeath']
        result['tv_'+key]={'Type':kind,'Health':20,'Options':{'NoAI':True,'NoGravity':True,'Invincible':True,
            'PreventSunburn':True,'Despawn':'CHUNK' if scenario=='chunk' else 'NEVER','ReviveHealth':20},'Skills':skills}
    return result

def native_skills():
    base = {
      'tv_terminal':{'Skills':[command('start'),'skill{s=tv_seed;delay=3} @self',
          'skill{s=tv_mutate;delay=13} @self','skill{s=tv_targetadd;delay=23} @self',command('targetadd',';delay=25'),'skill{s=tv_expired;delay=65} @self','skill{s=tv_finish;delay=125} @self','skill{s=tv_targetset;delay=135} @self',command('targetset',';delay=137'),'skill{s=tv_targetunset;delay=145} @self',command('targetunset',';delay=147')]},
      'tv_seed':{'Skills':[
          'setvariable{var=caster.Counter;type=INTEGER;value=1} @self',
          'setvariable{var=caster.Short;type=INTEGER;value=11;duration=40} @self',
          'setvariable{var=caster.Keep;type=INTEGER;value=21;duration=40} @self',
          'setvariable{var=caster.Refresh;type=INTEGER;value=31;duration=40} @self',
          'setvariable{var=caster.Reset;type=INTEGER;value=41;duration=40} @self',
          'setvariable{var=caster.Unset;type=INTEGER;value=51} @self',
          'setvariable{var=caster.Scratch;type=INTEGER;value=61} @self',
          'setvariable{var=skill.Flow;type=INTEGER;value=5} @self',command('seeded')]},
      'tv_mutate':{'Skills':[
          'variableadd{var=caster.Counter;amount=2} @self',
          'variableadd{var=caster.Keep;amount=2} @self','variableadd{var=caster.Refresh;amount=2;duration=100} @self',
          'setvariable{var=caster.Reset;type=INTEGER;value=43} @self','variableunset{var=caster.Unset} @self',
          'variableadd{var=skill.Flow;amount=3} @self',command('mutated')]},
      'tv_expired':{'Skills':[command('expired'),'variableadd{var=caster.Short;amount=9} @self',command('afteradd',';delay=2')]},
      'tv_finish':{'Skills':['variableunset{var=caster.Counter} @self',
          'variableunset{var=caster.Scratch} @self',command('finish')]},
      'tv_targetadd':{'Skills':['variableadd{var=target.Marker;amount=7} @self']},
      'tv_targetset':{'Skills':['setvariable{var=target.Marker;type=INTEGER;value=91} @self']},
      'tv_targetunset':{'Skills':['variableunset{var=target.Marker} @self']}}
    result={}
    for path in ('direct','imported'):
        for name,value in base.items():
            actions=[]
            for action in value['Skills']:
                for skill in base:action=action.replace('s='+skill+';', 's='+skill+'_'+path+';').replace('s='+skill+'}', 's='+skill+'_'+path+'}')
                actions.append(action)
            result[name+'_'+path]={'Skills':actions}
    return result
