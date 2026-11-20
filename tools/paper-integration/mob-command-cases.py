"""Pinned command outcomes, including explicitly rejected dangerous native syntax."""
MODES=('NORMAL','CHUNK','NPC','NEVER','PERSISTENT')
PATHS=('direct','imported')
ZONES=('near','boundary','far','cross')
CASES={
 'denied':{'command':'killall -p','player':True},
 'bulk':{'command':'killall','player':False},
 'force':{'command':'killall -p','player':False},
 'radius':{'command':'killall 16','player':True},
 'radius_force':{'command':'killall -p 16','player':True},
 'substring':{'command':'kill cmd_persistent','player':False},
 'substring_case':{'command':'kill CMD_PERSISTENT','player':False},
 'exact':{'command':'kill -e CMD_PERSISTENT_IMPORTED_NEAR','player':False},
 'faction':{'command':'kill -f cmd','player':False},
 'faction_intersection':{'command':'kill -f other','player':False},
 'named_radius_rejected':{'command':'kill cmd_persistent 16','player':True},
 'zero_radius_rejected':{'command':'killall 0','player':True},
 'console_radius_rejected':{'command':'killall 16','player':False},
}
def pairs():
 return {f'cmd_{m.lower()}_{p}_{z}':(m,p,z) for m in MODES for p in PATHS for z in ZONES}
def removed(case,key,backend):
 m,p,z=pairs()[key]
 if case=='denied' or case in ('substring_case','faction_intersection'):return False
 if case in ('bulk','zero_radius_rejected','console_radius_rejected'):
  return m not in ('NPC','PERSISTENT') and (backend=='native' or case=='bulk')
 if case=='force':return True
 if case=='radius':return m not in ('NPC','PERSISTENT') and z in ('near','boundary')
 if case=='radius_force':return z in ('near','boundary')
 if case=='substring':return m=='PERSISTENT'
 if case=='exact':return key=='cmd_persistent_imported_near'
 if case=='faction':return z in ('near','far')
 if case=='named_radius_rejected':return backend=='native' and m=='PERSISTENT'
 raise ValueError(case)
