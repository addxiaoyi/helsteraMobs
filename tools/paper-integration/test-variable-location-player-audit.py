"""Rehashed negative controls for independent real-player aiming evidence."""
import copy
import importlib.util
from pathlib import Path
import sys

import yaml

sys.dont_write_bytecode = True
spec = importlib.util.spec_from_file_location('location_player_audit', Path(__file__).with_name('audit-variable-location-player.py'))
audit = importlib.util.module_from_spec(spec)
spec.loader.exec_module(audit)
report, files = audit.load(Path(sys.argv[1]))
audit.audit(report, files)
controls = 0


def reject(name, mutate):
    global controls
    altered, changed = copy.deepcopy(report), dict(files)
    mutate(altered, changed)
    for entry in altered['files']:
        if entry['path'] in changed:
            entry['sha256'] = audit.digest(changed[entry['path']])
    try:
        audit.audit(altered, changed)
    except (ValueError, KeyError, TypeError):
        controls += 1
        return
    raise AssertionError('Accepted altered evidence: ' + name)


def change_row(predicate, change):
    def mutate(report, files):
        for name, content in files.items():
            if '/observations/' not in name:
                continue
            row = yaml.safe_load(content)
            if predicate(row):
                change(row)
                files[name] = yaml.safe_dump(row).encode()
                return
        raise AssertionError('Control case missing')
    return mutate


def replace_value(row, value, scope=None):
    field = (scope or row['scope']) + '/' + row['key']
    for name in ('nativeStoredAfter', 'nativeAfter', 'runtimeAfter'):
        row[name][field] = value


def replace_deadline(row, value):
    for name in ('nativeStoredAfterDeadlines', 'nativeAfterDeadlines', 'runtimeAfterDeadlines'):
        row[name][row['scope'] + '/' + row['key']] = value


for field, value in [('schemaVersion', 99), ('parityStatus', 'passed'), ('evidenceMode', 'comparison'),
                     ('exitCode', 1), ('clientExitCode', 1), ('observationCount', 299),
                     ('mythicMobsSha256', '0' * 64), ('paperJarSha256', '0' * 64)]:
    reject(field, lambda r, f, k=field, v=value: r.update({k: v}))
base = lambda r: r['scope'] == 'target' and r['dispatch'] == 'entity' and r['case'] == 'entity-hit' and r['path'] == 'native-direct'
for field, value in [('mapped', 'variable-location-set var=rewritten value=@self'), ('bindingSource', 'rewritten'),
                     ('selected', '@self'), ('nativeResult', 'INVALID_CONFIG'), ('runtimeResult', 'rejected'),
                     ('source', 'setvariablelocation{var=Wrong;value=@target}'), ('matched', False),
                     ('bindingDiagnostics', ['unexpected warning']), ('expectedRole', 'origin'),
                     ('wall', 'STONE'), ('rearWall', 'STONE'), ('peerMode', 'SURVIVAL'),
                     ('casterBeforeBinding', 'Location:integration-world,300.5,170,300.5,90,0'),
                     ('casterAtDispatch', 'Location:integration-world,300.5,170,300.5,90,0'),
                     ('eyeAtDispatch', 'Location:integration-world,300.5,170,300.5,0,0')]:
    reject(field, change_row(base, lambda r, k=field, v=value: r.update({k: v})))
for role in ('caster', 'recipient', 'trigger', 'origin', 'outer', 'peer'):
    reject('agreed wrong value from ' + role, change_row(base, lambda r, role=role: replace_value(r, r['positions'][role])))
reject('agreed wrong registry', change_row(base, lambda r: replace_value(r, r['positions']['aimed'], 'global')))
reject('lost world', change_row(base, lambda r: replace_value(r, r['positions']['aimed'].replace('integration-world', 'other-world'))))
reject('lost orientation', change_row(base, lambda r: replace_value(r, 'Location:integration-world,300.5,170,312.5,0,0')))
reject('rewritten source positions', change_row(base, lambda r: r['positions'].update({'aimed': r['positions']['recipient']})))
for case in ('entity-miss', 'entity-stone', 'entity-glass'):
    reject(case + ' borrowed candidate', change_row(lambda r, case=case: r['case'] == case and r['scope'] == 'skill',
                                                   lambda r: replace_value(r, r['positions']['aimed'])))
reject('creative candidate selected', change_row(lambda r: r['case'] == 'entity-creative' and r['scope'] == 'skill',
                                                lambda r: replace_value(r, r['positions']['peer'])))
reject('survival candidate skipped', change_row(lambda r: r['case'] == 'entity-player' and r['scope'] == 'skill',
                                               lambda r: replace_value(r, r['positions']['aimed'])))
reject('barrier blocked entity', change_row(lambda r: r['case'] == 'entity-barrier' and r['scope'] == 'skill',
                                           lambda r: replace_value(r, r['positions']['origin'])))
for case in ('location-glass', 'location-barrier'):
    reject(case + ' blocked location', change_row(lambda r, case=case: r['case'] == case and r['scope'] == 'skill',
                                                 lambda r: replace_value(r, 'Location:integration-world,300.5,171.62,305,0,0')))
reject('miss uses origin', change_row(lambda r: r['case'] == 'location-empty' and r['scope'] == 'skill',
                                     lambda r: replace_value(r, r['positions']['origin'])))
reject('miss uses 32 blocks', change_row(lambda r: r['case'] == 'location-empty' and r['scope'] == 'skill',
                                        lambda r: replace_value(r, 'Location:integration-world,300.5,171.62,332.5,0,0')))
reject('stale aim after binding', change_row(lambda r: r['case'] == 'location-turned' and r['scope'] == 'skill',
                                            lambda r: replace_value(r, 'Location:integration-world,300.5,171.62,364.5,0,0')))
reject('ray orientation copied from caster', change_row(lambda r: r['case'] == 'location-turned' and r['scope'] == 'skill',
                                                       lambda r: replace_value(r, 'Location:integration-world,236.5,171.62,300.5,90,0')))
reject('missing target registry invented', change_row(lambda r: r['scope'] == 'target' and r['dispatch'] == 'location',
                                                     lambda r: replace_value(r, r['positions']['aimed'])))
reject('missing registry failure concealed', change_row(lambda r: r['scope'] == 'target' and r['dispatch'] == 'location',
                                                       lambda r: r.update(nativeResult='SUCCESS')))
reject('saved deadline lost', change_row(lambda r: r['case'] == 'location-saved' and r['scope'] == 'skill', lambda r: replace_deadline(r, 1)))
reject('ordinary deadline lost', change_row(base, lambda r: replace_deadline(r, 0)))
for scope in ('caster', 'trigger', 'aimed', 'peer', 'other-world'):
    reject('lost registry ' + scope, change_row(base, lambda r, scope=scope: r['nativeAfter'].pop(scope + '/' + r['key'])))
reject('seed deadline changed', change_row(base, lambda r: r['runtimeBeforeDeadlines'].update({'target/' + r['key']: 0})))
reject('canonicalized direct path', change_row(base, lambda r: r.update(path='imported')))
reject('import dropped value', change_row(lambda r: r['path'] == 'imported' and r['case'] == 'entity-hit',
                                         lambda r: r.update(mapped='variable-location-set var=' + r['scope'] + '.' + r['key'])))
reject('missing observation', lambda r, f: f.pop(next(n for n in f if '/observations/' in n)))
reject('lost native diagnostic', lambda r, f: f.update({'server.log': f['server.log'].replace(b'Failed to get variable registry', b'ignored registry', 1)}))
reject('lost summary', lambda r, f: f.update({'server.log': f['server.log'].replace(b'VLOCATION_PLAYER_SUMMARY', b'MISSING_SUMMARY')}))
reject('lost log row', lambda r, f: f.update({'server.log': f['server.log'].replace(b'VLOCATION_PLAYER_ROW', b'MISSING_ROW', 1)}))
reject('lost client join', lambda r, f: f.update({'client.log': f['client.log'].replace(b'"join"', b'"missing"', 1)}))
reject('client failure concealed', lambda r, f: f.update({'client.log': f['client.log'].replace(b'"passed":true', b'"passed":false')}))


def repeated_identity(report, files):
    name = next(n for n in files if '/identities-' in n)
    identities = yaml.safe_load(files[name])
    identities['entity-4'] = identities['entity-1']
    files[name] = yaml.safe_dump(identities).encode()


def player_identity(report, files):
    name = next(n for n in files if '/identities-' in n)
    identities = yaml.safe_load(files[name])
    identities['caster'] = identities['peer']
    files[name] = yaml.safe_dump(identities).encode()


reject('aimed cow reused recipient identity', repeated_identity)
reject('caster reused peer identity', player_identity)


def wrong_manifest(report, files):
    name = next(iter(report['sourceHashes']))
    report['sourceHashes']['unrelated-source.java'] = report['sourceHashes'].pop(name)


reject('substituted source manifest', wrong_manifest)
reject('invalid source digest', lambda r, f: r['sourceHashes'].update({next(iter(r['sourceHashes'])): 'missing'}))
print(f'Variable location player audit: {controls} altered-evidence controls rejected')
