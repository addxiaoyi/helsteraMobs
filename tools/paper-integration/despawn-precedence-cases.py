"""Explicit source/expected contract for the pinned MM 5.7.2 precedence comparison."""
MODES=('NORMAL','CHUNK','NPC','NEVER','PERSISTENT')
GLOBALS={'absent':None,'true':True,'false':False,'chunk':'CHUNK','quoted_false':'false','zero':0}
def cases():
    out={}
    def add(key,source,mode,unsupported=()):out[key]={'source':source,'mode':mode,'unsupported':list(unsupported)}
    add('default',{},'GLOBAL')
    for mode in MODES:
        key=mode.lower()
        for prefix in ('root','options'):
            add(key+'_'+prefix,{'Despawn':mode} if prefix=='root' else {'Options':{'Despawn':mode}},mode)
            for value in (True,False):
                fields={'Despawn':mode,'Persistent':value}
                add(key+'_'+prefix+'_'+str(value).lower(),fields if prefix=='root' else {'Options':fields},'PERSISTENT' if value else mode)
        add(key+'_root_true_options_false',{'Despawn':mode,'Persistent':True,'Options':{'Persistent':False}},mode)
        add(key+'_root_false_options_true',{'Despawn':mode,'Persistent':False,'Options':{'Persistent':True}},'PERSISTENT')
        add(key+'_options_wins',{'Despawn':'NEVER' if mode!='NEVER' else 'NPC','Options':{'Despawn':mode}},mode)
        add(key+'_invalid_persistent_inherits',{'Despawn':mode,'Persistent':True,'Options':{'Persistent':'false'}},'PERSISTENT',('Options.Persistent',))
    for key,value in [('zero',0),('one',1),('quoted_zero','0'),('quoted_one','1'),('spaced',' CHUNK '),('unknown','UNKNOWN'),('list',['NEVER']),('map',{'mode':'NEVER'})]:
        add('invalid_despawn_'+key,{'Despawn':'NEVER','Options':{'Despawn':value}},'NORMAL',('Options.Despawn',))
    for key,value in [('quoted_true','true'),('quoted_false','false'),('one',1),('zero',0),('unknown','maybe'),('list',[True]),('map',{'value':True})]:
        add('invalid_persistent_'+key,{'Options':{'Persistent':value}},'GLOBAL',('Options.Persistent',))
    for key,value,mode in [('true',True,'NORMAL'),('false',False,'NEVER'),('yes','yes','NORMAL'),('no','no','NEVER'),('lower','chunk','CHUNK')]:
        add('alias_'+key,{'Options':{'Despawn':value}},mode)
    add('invalid_overridden',{'Despawn':'bad','Persistent':'true','Options':{'Despawn':'CHUNK','Persistent':True}},'PERSISTENT',('Despawn','Persistent'))
    return out

def expected_mode(case,group):
    return case['mode'] if case['mode']!='GLOBAL' else ('NEVER' if group=='false' else 'NORMAL')
