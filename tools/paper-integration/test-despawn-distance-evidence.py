"""Reject corrupted outcomes even when the report and server log agree."""
import copy
import importlib.util
from pathlib import Path
import sys
sys.dont_write_bytecode = True
spec = importlib.util.spec_from_file_location('validator', Path(__file__).with_name('validate-despawn-distance.py'))
v = importlib.util.module_from_spec(spec); spec.loader.exec_module(v)
report, texts = v.main(sys.argv[1]); controls = []
def row(label, predicate, transform):
    changed = copy.deepcopy(report); logs = dict(texts)
    i = next(i for i, line in enumerate(changed['observations']) if predicate(line))
    before = changed['observations'][i]; after = transform(before); assert before != after, label
    changed['observations'][i] = after; logs['server.log'] = logs['server.log'].replace(before, after)
    controls.append((label, changed, logs))
def log(label, filename, transform):
    logs = dict(texts); logs[filename] = transform(logs[filename]); assert logs[filename] != texts[filename], label
    controls.append((label, report, logs))
def isrow(phase, key): return lambda r: '|phase='+phase+'|' in r and key in r
row('missing row', lambda _: True, lambda _: '')
row('duplicate row', lambda _: True, lambda _: report['observations'][1])
row('both near disappear', isrow('near','cow_normal_direct'), lambda r: r.replace('Entity=true', 'Entity=false'))
row('fixed radius remains', isrow('middle','zombie_normal_direct'), lambda r: r.replace('runtimeEntity=true','runtimeEntity=false'))
row('cow follows zombie policy', isrow('far','cow_normal_direct'), lambda r: r.replace('Entity=true','Entity=false'))
row('zombie never despawns', isrow('far','zombie_normal_direct'), lambda r: r.replace('Entity=false','Entity=true'))
row('never removed', isrow('far','slime_never_direct'), lambda r: r.replace('runtimeEntity=true','runtimeEntity=false'))
row('empty world removal', isrow('empty','cow_default_direct'), lambda r: r.replace('runtimeEntity=true','runtimeEntity=false'))
row('spectator removal', isrow('spectator','slime_normal_direct'), lambda r: r.replace('runtimeEntity=true','runtimeEntity=false'))
row('orphan controller', isrow('far','zombie_normal_direct'), lambda r: r.replace('runtimeController=false','runtimeController=true'))
row('wrong remove flag', isrow('near','cow_normal_direct'), lambda r: r.replace('runtimeRemove=true','runtimeRemove=false'))
row('wrong save flag', isrow('near','zombie_never_direct'), lambda r: r.replace('runtimePersistent=true','runtimePersistent=false'))
row('no callback', isrow('far','zombie_normal_direct'), lambda r: r.replace('runtimeDespawn=1','runtimeDespawn=0'))
row('despawn counted as death', isrow('far','zombie_normal_direct'), lambda r: r.replace('runtimeDeath=0','runtimeDeath=1'))
row('lost extension', isrow('near','cow_spawning_distance_direct'), lambda r: r.replace('runtimeEntity=false','runtimeEntity=true'))
row('invented import radius', isrow('near','cow_spawning_distance_imported'), lambda r: r.replace('runtimeEntity=true','runtimeEntity=false'))
row('fallen through void', isrow('near','cow_normal_direct'), lambda r: r.replace('runtimeY=110.0','runtimeY=10.0'))
row('player did not move', isrow('far','cow_normal_direct'), lambda r: r.replace('playerX=4288.5','playerX=4112.5'))
row('faked distance', isrow('far','cow_normal_direct'), lambda r: r.replace('runtimeDistance=192.','runtimeDistance=16.'))
row('missing player exclusion', isrow('empty','cow_normal_direct'), lambda r: r.replace('players=0','players=1'))
row('spectator not spectator', isrow('spectator','cow_normal_direct'), lambda r: r.replace('playerMode=SPECTATOR','playerMode=CREATIVE'))
row('chunk unloaded', lambda _: True, lambda r: r.replace('chunkLoaded=true','chunkLoaded=false'))
row('observation too early', isrow('far','cow_normal_direct'), lambda r: v.re.sub(r'elapsed=\d+', 'elapsed=1',r))
log('missing hook', 'server.log', lambda t: t.replace('DDIST_HOOK|','DDIST_UNKNOWN|',1))
log('duplicate hook', 'server.log', lambda t: t.replace(next(l for l in t.splitlines() if 'DDIST_HOOK|' in l), next(l for l in t.splitlines() if 'DDIST_HOOK|' in l)+'\n'+next(l for l in t.splitlines() if 'DDIST_HOOK|' in l)))
log('missing cause', 'server.log', lambda t: t.replace('|cause=DESPAWN','|cause=PLUGIN',1))
log('missing identity', 'server.log', lambda t: t.replace('DDIST_ID|','DDIST_UNKNOWN|',1))
log('import warning lost', 'server.log', lambda t: t.replace('|unsupportedDistance=8','|unsupportedDistance=0',1))
log('extension radius lost', 'server.log', lambda t: t.replace('|runtimeRadius=8.0','|runtimeRadius=0.0',1))
log('wrong server ack', 'server.log', lambda t: t.replace('DDIST_ACK|phase=far','DDIST_ACK|phase=near',1))
log('client ack missing', 'client.log', lambda t: t.replace('DDIST_CLIENT {"event":"ack"','DDIST_UNKNOWN {"event":"ack"',1))
log('unexpected packet error', 'client.log', lambda t: t.replace('"packet":"declare_recipes"','"packet":"position"'))
log('unreported packet error', 'client.log', lambda t: t.replace('DDIST_CLIENT {"event":"packet-error"','DDIST_UNKNOWN {"event":"packet-error"'))
log('probe failed', 'server.log', lambda t: t+'\nDDIST_FAILED|error=injected\n')
for key, value in [('pluginSha256',''),('extensions',[]),('clientKnownIssues',[]),('evidenceMode','observation'),('sourceHashes',{})]:
    changed = copy.deepcopy(report); changed[key] = value; controls.append(('metadata '+key, changed, texts))
if report['schemaVersion'] >= 2:
    binding = next(l for l in texts['server.log'].splitlines() if 'DDIST_PLAYER_BIND|' in l)
    log('missing player binding', 'server.log', lambda t: t.replace(binding, ''))
    log('duplicated player binding', 'server.log', lambda t: t.replace(binding, binding+'\n'+binding))
    log('player saving overwritten', 'server.log', lambda t: t.replace(binding, binding.replace('afterPersistent=false','afterPersistent=true')))
    log('player removed during unbind', 'server.log', lambda t: t.replace(binding, binding.replace('entityValid=true','entityValid=false')))
    log('player binding leaked', 'server.log', lambda t: t.replace(binding, binding.replace('instanceGone=true','instanceGone=false')))
for label, changed, logs in controls:
    try: v.validate(changed, logs)
    except (ValueError, KeyError): continue
    raise AssertionError('Accepted invalid evidence: '+label)
print(f'Rejected all {len(controls)} distance evidence mutation controls.')
