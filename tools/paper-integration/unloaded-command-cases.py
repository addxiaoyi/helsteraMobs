"""Command boundaries after a real EntitiesUnloadEvent, without loading the target."""
MODES=('NORMAL','CHUNK','NPC','NEVER','PERSISTENT')
PATHS=('direct','imported')
CASES={'baseline':{'command':'none','delay':10,'player':False}}
for timing,delay in [('transition',1),('edge4',4),('edge5',5),('edge6',6),('stable',10)]:
 for name,command,player in [('named','kill uc_',False),('bulk','killall',False),('force','killall -p',False),('radius','killall -p 10000',True)]:
  CASES[timing+'_'+name]={'command':command,'delay':delay,'player':player}
CASES['outside_radius']={'command':'killall -p 100','delay':1,'player':True}
CASES['wrong_world']={'command':'killall -p 10000','delay':1,'player':True}
def pairs():return {f'uc_{m.lower()}_{p}':(m,p) for m in MODES for p in PATHS}
