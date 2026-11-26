"""Rehashed negative controls for independent saved threat target location evidence."""
import copy
import importlib.util
from pathlib import Path
import sys

import yaml

sys.dont_write_bytecode = True
spec = importlib.util.spec_from_file_location('location_threat_audit', Path(__file__).with_name('audit-variable-location-threat.py'))
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
                     ('exitCode', 1), ('observationCount', 239), ('mythicMobsSha256', '0' * 64),
                     ('paperJarSha256', '0' * 64)]:
    reject(field, lambda r, f, k=field, v=value: r.update({k: v}))
base = lambda r: r['scope'] == 'target' and r['dispatch'] == 'entity' and r['case'] == 'target' and r['path'] == 'native-direct'
for field, value in [('mapped', 'variable-location-set var=rewritten value=@self'), ('bindingSource', 'rewritten'),
                     ('selected', '@self'), ('nativeResult', 'INVALID_CONFIG'), ('runtimeResult', 'rejected'),
                     ('source', 'setvariablelocation{var=Wrong;value=@target}'), ('matched', False),
                     ('bindingDiagnostics', ['unexpected warning']), ('nativeDurationMillis', 1000),
                     ('targetState', 'missing'), ('targetAtDispatch', 'none'), ('targetBeforeBinding', 'none'),
                     ('nativeHasThreatTable', False)]:
    reject(field, change_row(base, lambda r, k=field, v=value: r.update({k: v})))
for role in ('caster', 'recipient', 'trigger', 'origin', 'outer', 'changed-target', 'vanilla-target'):
    reject('agreed wrong value from ' + role, change_row(base, lambda r, role=role: replace_value(r, audit.POSITIONS[role])))
reject('agreed wrong registry', change_row(base, lambda r: replace_value(r, audit.POSITIONS['threat-target'], 'global')))
reject('lost world', change_row(base, lambda r: replace_value(r, audit.POSITIONS['threat-target'].replace('integration-world', 'other-world'))))
reject('lost orientation', change_row(base, lambda r: replace_value(r, 'Location:integration-world,118.0,170.0,8.0,0.0,0.0')))
reject('rewritten source positions', change_row(base, lambda r: r['positions'].update({'threat-target': audit.POSITIONS['recipient']})))
reject('wrong value alias priority', change_row(lambda r: r['case'] == 'target-alias-priority', lambda r: replace_value(r, audit.POSITIONS['origin'])))
reject('wrong duplicate value', change_row(lambda r: r['case'] == 'location-duplicate', lambda r: replace_value(r, audit.POSITIONS['threat-target'])))
reject('missing target overwritten', change_row(lambda r: r['case'] == 'target-cleared', lambda r: replace_value(r, audit.POSITIONS['origin'])))
reject('missing target exception concealed', change_row(lambda r: r['case'] == 'target-cleared' and r['scope'] == 'skill',
                                                        lambda r: r.update(nativeResult='SUCCESS')))
reject('location fallback missing', change_row(lambda r: r['case'] == 'location-cleared' and r['scope'] == 'skill',
                                              lambda r: replace_value(r, r['nativeBefore']['skill/' + r['key']])))
reject('location fallback borrowed recipient', change_row(lambda r: r['case'] == 'location-cleared' and r['scope'] == 'skill',
                                                        lambda r: replace_value(r, audit.POSITIONS['recipient'])))
reject('target location recipient invented', change_row(lambda r: r['scope'] == 'target' and r['dispatch'] == 'location',
                                                       lambda r: replace_value(r, audit.POSITIONS['threat-target'])))
reject('target location failure concealed', change_row(lambda r: r['scope'] == 'target' and r['dispatch'] == 'location',
                                                      lambda r: r.update(nativeResult='SUCCESS')))
reject('stale target after binding', change_row(lambda r: r['case'] == 'target-changed' and r['scope'] == 'skill',
                                               lambda r: replace_value(r, audit.POSITIONS['threat-target'])))
reject('stale targetlocation after binding', change_row(lambda r: r['case'] == 'location-changed' and r['scope'] == 'skill',
                                                       lambda r: replace_value(r, audit.POSITIONS['threat-target'])))
reject('saved location deadline lost', change_row(lambda r: r['case'] == 'saved-location' and r['scope'] == 'skill', lambda r: replace_deadline(r, 1)))
reject('ordinary deadline lost', change_row(base, lambda r: replace_deadline(r, 0)))
for scope in ('trigger', 'threat-target', 'changed-target', 'vanilla-target', 'other-world'):
    reject('lost registry ' + scope, change_row(base, lambda r, scope=scope: r['nativeAfter'].pop(scope + '/' + r['key'])))
reject('seed deadline changed', change_row(base, lambda r: r['runtimeBeforeDeadlines'].update({'target/' + r['key']: 0})))
reject('canonicalized direct path', change_row(base, lambda r: r.update(path='imported')))
reject('import dropped value', change_row(lambda r: r['path'] == 'imported' and r['case'] == 'target',
                                         lambda r: r.update(mapped='variable-location-set var=' + r['scope'] + '.' + r['key'])))
reject('missing observation', lambda r, f: f.pop(next(n for n in f if '/observations/' in n)))
reject('lost native diagnostic', lambda r, f: f.update({'server.log': f['server.log'].replace(
    b'Failed to get variable registry', b'ignored registry', 1)}))
reject('lost summary', lambda r, f: f.update({'server.log': f['server.log'].replace(b'VLOCATION_THREAT_SUMMARY', b'MISSING_SUMMARY')}))
reject('lost log row', lambda r, f: f.update({'server.log': f['server.log'].replace(b'VLOCATION_THREAT_ROW', b'MISSING_ROW', 1)}))


def repeated_identity(report, files):
    name = next(n for n in files if '/identities-' in n)
    identities = yaml.safe_load(files[name])
    identities['entity-4'] = identities['entity-1']
    files[name] = yaml.safe_dump(identities).encode()


reject('AI target reused recipient identity', repeated_identity)


def wrong_manifest(report, files):
    name = next(iter(report['sourceHashes']))
    report['sourceHashes']['unrelated-source.java'] = report['sourceHashes'].pop(name)


reject('substituted source manifest', wrong_manifest)
reject('invalid source digest', lambda r, f: r['sourceHashes'].update({next(iter(r['sourceHashes'])): 'missing'}))
for field in ('nativeTargetAfter', 'runtimeTargetBefore', 'runtimeTargetAfter', 'vanillaTargetBefore', 'vanillaTargetAfter'):
    reject('changed ' + field, change_row(base, lambda r, field=field: r.update({field: 'none'})))
for field in ('nativeThreatBefore', 'nativeThreatAfter', 'runtimeThreatBefore', 'runtimeThreatAfter'):
    reject('changed ' + field, change_row(base, lambda r, field=field: r[field].update({next(iter(r[field])): 999.0})))
reject('silently reselected highest threat', change_row(base, lambda r: replace_value(r, audit.POSITIONS['changed-target'])))
reject('threat read dropped all scores', change_row(base, lambda r: [r.update({field: {}}) for field in
    ('nativeThreatBefore', 'nativeThreatAfter', 'runtimeThreatBefore', 'runtimeThreatAfter')]))
reject('native prep concealed', change_row(base, lambda r: r.update(nativePreparation='selectTarget(first)')))
reject('runtime prep claimed native policy', change_row(base, lambda r: r.update(runtimePreparation=r['nativePreparation'])))
reject('missing threat fell back to vanilla', change_row(lambda r: r['case'] == 'location-cleared' and r['scope'] == 'skill',
    lambda r: replace_value(r, audit.POSITIONS['vanilla-target'])))
reject('native threat disabled', lambda r, f: f.update({'plugins/MythicMobs/Mobs/location.yml':
    f['plugins/MythicMobs/Mobs/location.yml'].replace(b'Enabled: true', b'Enabled: false')}))
print(f'Variable location threat audit: {controls} altered-evidence controls rejected')
