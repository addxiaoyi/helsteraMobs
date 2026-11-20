"""Commands executed by a real onDespawn command callback, then world/process reload."""
ACTIONS={
 'named_self':('kill -e rc_lead','self'),
 'named_peer':('kill -e rc_lead','peer'),
 'named_force':('kill -e rc_lead','force'),
 'named_radius':('kill -e rc_lead','radius'),
 'force_self':('killall -p','self'),
 'force_spawn':('killall -p','spawn'),
 'force_spawn_peer':('killall -p','spawn-peer'),
 'radius_spawn':('killall -p 10000','spawn'),
 'named_spawn_force':('kill -e rc_lead','spawn-force'),
 'named_force_spawn':('kill -e rc_lead','force-spawn'),
 'named_spawn':('kill -e rc_lead','spawn'),
 'force_load_spawn':('killall -p','load-spawn'),
 'named_load_self':('kill -e rc_lead','load-self'),
 'named_spawn_load_peer':('kill -e rc_lead','spawn-load-peer'),
 'named_spawn_radius':('kill -e rc_lead','spawn-radius'),
}
CASES={f'{state}_{name}_{path}':{'state':state,'path':path,'command':command,'action':action}
 for state in ('loaded','unloading') for name,(command,action) in ACTIONS.items() for path in ('direct','imported')}
ROLES=('lead','peer','protected','child')
def pairs():return {'rc_'+r:('PERSISTENT' if r=='protected' else 'NEVER','imported') for r in ROLES}
