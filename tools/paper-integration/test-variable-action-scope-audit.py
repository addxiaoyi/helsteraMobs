"""Tamper controls for the archived scope comparison, including rehashed mutations."""
import copy
import importlib.util
from pathlib import Path
import sys

import yaml

sys.dont_write_bytecode = True
spec = importlib.util.spec_from_file_location('scope_audit', Path(__file__).with_name('audit-variable-action-scope.py'))
audit = importlib.util.module_from_spec(spec)
spec.loader.exec_module(audit)
report, files = audit.load(Path(sys.argv[1]))
audit.audit(report, files)
controls = 0


def reject(name, mutate):
    global controls
    altered, changed = copy.deepcopy(report), dict(files)
    mutate(altered, changed)
    # Recompute per-file checksums: semantic corruption must fail independently of hashes.
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


for field, value in [('schemaVersion', 999), ('parityStatus', 'passed'), ('evidenceMode', 'comparison'), ('exitCode', 1),
                     ('observationCount', 275), ('mythicMobsSha256', '0' * 64), ('paperJarSha256', '0' * 64)]:
    reject(field, lambda r, f, k=field, v=value: r.update({k: v}))
for field, value in [('mapped', 'set-variable var=Wrong scope=global value=7'), ('parsedScope', 'GLOBAL'),
                     ('nativeResult', 'INVALID_CONFIG'), ('source', 'setvariable{var=Wrong;value=7}'), ('matched', False)]:
    reject(field, change_row(lambda r: r['case'] == 'explicit-target', lambda r, k=field, v=value: r.update({k: v})))


def wrong_registry(row):
    key = next(iter(row['nativeBefore'])).split('/', 1)[1]
    for field in ('expectedAfter', 'nativeAfter', 'runtimeAfter'):
        row[field]['skill/' + key] = 'Integer:7'


reject('agreed wrong registry', change_row(lambda r: r['case'] == 'explicit-target' and r['operation'] == 'set', wrong_registry))
reject('unsupported writes', change_row(lambda r: r['case'] == 'trigger-prefix', wrong_registry))
reject('lost trigger registry', change_row(lambda r: True, lambda r: r['runtimeBefore'].pop(next(k for k in r['runtimeBefore'] if k.startswith('trigger/')))))
reject('lost other world', change_row(lambda r: True, lambda r: r['nativeAfter'].pop(next(k for k in r['nativeAfter'] if k.startswith('other-world/')))))
reject('missing observation', lambda r, f: f.pop(next(n for n in f if '/observations/' in n)))
reject('lost native diagnostic', lambda r, f: f.update({'server.log': f['server.log'].replace(b'is not a valid variable scope.', b'ignored scope', 1)}))
reject('lost summary', lambda r, f: f.update({'server.log': f['server.log'].replace(b'VSCOPE_SUMMARY', b'MISSING_SUMMARY')}))


def repeated_identity(report, files):
    name = next(n for n in files if '/identities-' in n)
    identities = yaml.safe_load(files[name])
    identities['entity-1'] = identities['entity-0']
    files[name] = yaml.safe_dump(identities).encode()


reject('reused target identity', repeated_identity)


def wrong_source_manifest(report, files):
    name = next(iter(report['sourceHashes']))
    report['sourceHashes']['unrelated-source.java'] = report['sourceHashes'].pop(name)


reject('substituted source manifest', wrong_source_manifest)
reject('invalid source digest', lambda r, f: r['sourceHashes'].update({next(iter(r['sourceHashes'])): 'missing'}))


def rename_identity(report, files):
    name = next(n for n in files if '/identities-' in n)
    renamed = name.replace('identities-', 'identities-unrelated-')
    files[renamed] = files.pop(name)
    next(entry for entry in report['files'] if entry['path'] == name)['path'] = renamed


reject('renamed identity path', rename_identity)

if report['schemaVersion'] == 2:
    direct_valid = lambda r: r['path'] == 'native-direct' and r['case'] == 'explicit-target'
    direct_invalid = lambda r: r['path'] == 'native-direct' and r['case'] == 'trigger-prefix'
    for field, value in [('mapped', 'set-variable var=canonical scope=target type=INTEGER value=7'),
                         ('bindingSource', 'setvariable{var=rewritten;scope=target;value=7}'),
                         ('bindingDiagnostics', ['unexpected warning']), ('importDiagnostics', ['unexpected warning']),
                         ('runtimeResult', 'rejected')]:
        reject('native direct ' + field, change_row(direct_valid, lambda r, k=field, v=value: r.update({k: v})))
    for field, value in [('bindingSource', None), ('bindingDiagnostics', []),
                         ('bindingDiagnostics', ['invalid Mythic variable reference without original source']),
                         ('runtimeResult', 'SUCCESS')]:
        reject('native rejection ' + field, change_row(direct_invalid, lambda r, k=field, v=value: r.update({k: v})))
    reject('missing native binding source', change_row(direct_valid, lambda r: r.pop('bindingSource')))
    reject('canonicalized third path', change_row(direct_valid, lambda r: r.update(path='canonical-direct')))
    reject('missing import rejection diagnostic', change_row(
        lambda r: r['path'] == 'imported' and r['case'] == 'trigger-prefix',
        lambda r: r.update(importDiagnostics=[])))
    reject('lost binding diagnostic log', lambda r, f: f.update({
        'server.log': f['server.log'].replace(b'invalid Mythic variable reference', b'ignored Mythic variable reference', 1)}))
print(f'Variable action scope audit: {controls} altered-evidence controls rejected')
