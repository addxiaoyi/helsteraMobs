"""Rehashed negative controls for the independent static location archive audit."""
import copy
import importlib.util
from pathlib import Path
import sys

import yaml

sys.dont_write_bytecode = True
spec = importlib.util.spec_from_file_location('location_audit', Path(__file__).with_name('audit-variable-location.py'))
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
                     ('exitCode', 1), ('observationCount', 299), ('mythicMobsSha256', '0' * 64),
                     ('paperJarSha256', '0' * 64)]:
    reject(field, lambda r, f, k=field, v=value: r.update({k: v}))
base = lambda r: r['scope'] == 'target' and r['dispatch'] == 'entity' and r['case'] == 'origin' and r['path'] == 'native-direct'
for field, value in [('mapped', 'variable-location-set var=rewritten value=@self'), ('bindingSource', 'rewritten'),
                     ('selected', '@self'), ('nativeResult', 'INVALID_CONFIG'), ('runtimeResult', 'rejected'),
                     ('source', 'setvariablelocation{var=Wrong;value=@origin}'), ('matched', False),
                     ('bindingDiagnostics', ['unexpected warning']), ('nativeDurationMillis', 1000)]:
    reject(field, change_row(base, lambda r, k=field, v=value: r.update({k: v})))
for role in ('caster', 'recipient', 'outer'):
    reject('agreed wrong value from ' + role, change_row(base, lambda r, role=role: replace_value(r, audit.POSITIONS[role])))
reject('agreed wrong registry', change_row(base, lambda r: replace_value(r, audit.POSITIONS['origin'], 'global')))
reject('lost world', change_row(base, lambda r: replace_value(r, audit.POSITIONS['origin'].replace('integration-world', 'other-world'))))
reject('lost orientation', change_row(base, lambda r: replace_value(r, 'Location:integration-world,130.0,174.0,12.0,0.0,0.0')))
reject('rewritten source positions', change_row(base, lambda r: r['positions'].update(origin=audit.POSITIONS['recipient'])))
reject('wrong default', change_row(lambda r: r['case'] == 'default', lambda r: replace_value(r, audit.POSITIONS['recipient'])))
reject('wrong value alias priority', change_row(lambda r: r['case'] == 'value-priority', lambda r: replace_value(r, audit.POSITIONS['trigger'])))
reject('wrong duplicate value', change_row(lambda r: r['case'] == 'duplicate', lambda r: replace_value(r, audit.POSITIONS['caster'])))
reject('lost amount alias', change_row(lambda r: r['case'] == 'alias-a', lambda r: r.update(selected='@self')))
reject('missing trigger overwritten', change_row(lambda r: r['case'] == 'missing-trigger', lambda r: replace_value(r, audit.POSITIONS['caster'])))
reject('missing trigger concealed', change_row(lambda r: r['case'] == 'missing-trigger', lambda r: r.update(missingTrigger=False)))
reject('target location recipient invented', change_row(lambda r: r['scope'] == 'target' and r['dispatch'] == 'location',
                                                       lambda r: replace_value(r, audit.POSITIONS['caster'])))
reject('target location failure concealed', change_row(lambda r: r['scope'] == 'target' and r['dispatch'] == 'location',
                                                      lambda r: r.update(nativeResult='SUCCESS')))
reject('missing trigger exception concealed', change_row(lambda r: r['case'] == 'missing-trigger' and r['scope'] == 'skill',
                                                        lambda r: r.update(nativeResult='SUCCESS')))
expired = lambda r: r['case'] == 'duration-expired' and r['scope'] == 'skill'
reject('raw expired write removed', change_row(expired, lambda r: r['nativeStoredAfter'].pop(r['scope'] + '/' + r['key'])))
reject('expired value retained by read', change_row(expired, lambda r: replace_value(r, audit.POSITIONS['origin'])))
reject('raw deadline converted to relative', change_row(expired,
                                                      lambda r: r['nativeStoredAfterDeadlines'].update({r['scope'] + '/' + r['key']: 1_800_000_001_000})))
reject('saved location deadline lost', change_row(lambda r: r['case'] == 'save-location' and r['scope'] == 'skill', lambda r: replace_deadline(r, 1)))
reject('negative deadline normalized', change_row(lambda r: r['case'] == 'negative-duration' and r['scope'] == 'skill', lambda r: replace_deadline(r, 0)))
reject('lost trigger registry', change_row(base, lambda r: r['runtimeBefore'].pop('trigger/' + r['key'])))
reject('lost other world', change_row(base, lambda r: r['nativeAfter'].pop('other-world/' + r['key'])))
reject('seed deadline changed', change_row(base, lambda r: r['runtimeBeforeDeadlines'].update({'target/' + r['key']: 0})))
reject('canonicalized direct path', change_row(base, lambda r: r.update(path='imported')))
reject('import dropped value', change_row(lambda r: r['path'] == 'imported' and r['case'] == 'default',
                                         lambda r: r.update(mapped='variable-location-set var=' + r['scope'] + '.' + r['key'])))
reject('missing observation', lambda r, f: f.pop(next(n for n in f if '/observations/' in n)))
reject('lost native diagnostic', lambda r, f: f.update({'server.log': f['server.log'].replace(
    b'Failed to get variable registry', b'ignored registry', 1)}))
reject('lost summary', lambda r, f: f.update({'server.log': f['server.log'].replace(b'VLOCATION_SUMMARY', b'MISSING_SUMMARY')}))
reject('lost log row', lambda r, f: f.update({'server.log': f['server.log'].replace(b'VLOCATION_ROW', b'MISSING_ROW', 1)}))


def repeated_identity(report, files):
    name = next(n for n in files if '/identities-' in n)
    identities = yaml.safe_load(files[name])
    identities['entity-1'] = identities['entity-0']
    files[name] = yaml.safe_dump(identities).encode()


reject('reused recipient identity', repeated_identity)


def wrong_manifest(report, files):
    name = next(iter(report['sourceHashes']))
    report['sourceHashes']['unrelated-source.java'] = report['sourceHashes'].pop(name)


reject('substituted source manifest', wrong_manifest)
reject('invalid source digest', lambda r, f: r['sourceHashes'].update({next(iter(r['sourceHashes'])): 'missing'}))
print(f'Variable location audit: {controls} altered-evidence controls rejected')
