"""Rehashed negative controls for the independent variable equation archive audit."""
import copy
import importlib.util
from pathlib import Path
import sys

import yaml

sys.dont_write_bytecode = True
spec = importlib.util.spec_from_file_location('math_audit', Path(__file__).with_name('audit-variable-math.py'))
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


for field, value in [('schemaVersion', 99), ('parityStatus', 'passed'), ('evidenceMode', 'comparison'),
                     ('exitCode', 1), ('observationCount', 299), ('mythicMobsSha256', '0' * 64),
                     ('paperJarSha256', '0' * 64)]:
    reject(field, lambda r, f, k=field, v=value: r.update({k: v}))
base = lambda r: r['scope'] == 'target' and r['case'] == 'equation' and r['path'] == 'native-direct'
for field, value in [('mapped', 'variable-math var=rewritten equation="x"'), ('bindingSource', 'rewritten'),
                     ('resolvedEquation', 'x'), ('nativeResult', 'INVALID_CONFIG'), ('runtimeResult', 'rejected'),
                     ('source', 'variablemath{var=Wrong;equation=x}'), ('matched', False),
                     ('bindingDiagnostics', ['unexpected warning']), ('nativeDurationMillis', 60000)]:
    reject(field, change_row(base, lambda r, k=field, v=value: r.update({k: v})))


def wrong_registry(row):
    for field in ('expectedNativeAfter', 'expectedRuntimeAfter', 'nativeAfter', 'runtimeAfter'):
        row[field]['global/' + row['key']] = 'Integer:15'


reject('agreed wrong registry', change_row(base, wrong_registry))
reject('missing variable created', change_row(lambda r: r['case'] == 'missing', wrong_registry))
reject('double changed', change_row(lambda r: r['case'] == 'double', wrong_registry))
reject('lost trigger registry', change_row(base, lambda r: r['runtimeBefore'].pop('trigger/' + r['key'])))
reject('lost other world', change_row(base, lambda r: r['nativeAfter'].pop('other-world/' + r['key'])))
reject('lost native NaN', change_row(lambda r: r['case'] == 'nan-float',
                                   lambda r: r['nativeAfter'].update({r['scope'] + '/' + r['key']: 'Float:7.0'})))
reject('nonfinite runtime write', change_row(lambda r: r['case'] == 'nan-float',
                                            lambda r: r['runtimeAfter'].update({r['scope'] + '/' + r['key']: 'Float:NaN'})))
reject('hidden difference', change_row(lambda r: r['case'] == 'nan-integer', lambda r: r.update(sameValue=True)))
reject('wrong duplicate equation', change_row(lambda r: r['case'] == 'duplicate', lambda r: r.update(resolvedEquation='bad')))
reject('wrong e duration', change_row(lambda r: r['case'] == 'duration-e-numeric', lambda r: r.update(nativeDurationMillis=1)))
reject('cleared preserved deadline', change_row(base, lambda r: r.update(runtimeAfterExpiration=0)))
reject('unrefreshed positive deadline', change_row(lambda r: r['case'] == 'duration-positive',
                                                lambda r: r.update(runtimeAfterExpiration=r['runtimeBeforeExpiration'])))
reject('unbounded clock', change_row(base, lambda r: r.update(runtimeEnd=r['runtimeStart'] - 1)))
reject('canonicalized direct path', change_row(base, lambda r: r.update(path='imported')))
reject('missing observation', lambda r, f: f.pop(next(n for n in f if '/observations/' in n)))
reject('lost native diagnostic', lambda r, f: f.update({'server.log': f['server.log'].replace(
    b'Could not perform math operation on non-numeric variable type', b'ignored type', 1)}))
reject('lost summary', lambda r, f: f.update({'server.log': f['server.log'].replace(b'VMATH_SUMMARY', b'MISSING_SUMMARY')}))


def repeated_identity(report, files):
    name = next(n for n in files if '/identities-' in n)
    identities = yaml.safe_load(files[name])
    identities['entity-1'] = identities['entity-0']
    files[name] = yaml.safe_dump(identities).encode()


reject('reused target identity', repeated_identity)


def wrong_manifest(report, files):
    name = next(iter(report['sourceHashes']))
    report['sourceHashes']['unrelated-source.java'] = report['sourceHashes'].pop(name)


reject('substituted source manifest', wrong_manifest)
reject('invalid source digest', lambda r, f: r['sourceHashes'].update({next(iter(r['sourceHashes'])): 'missing'}))
print(f'Variable math audit: {controls} altered-evidence controls rejected')
