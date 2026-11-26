"""Semantic tamper controls, including jointly altered native/runtime results."""
import copy
import importlib.util
from pathlib import Path
import sys

import yaml

sys.dont_write_bytecode = True
spec = importlib.util.spec_from_file_location('condition_audit', Path(__file__).with_name('audit-condition-action.py'))
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
    except (ValueError, TypeError, KeyError):
        controls += 1
        return
    raise AssertionError('Accepted altered evidence: ' + name)


def row_change(predicate, mutate):
    def change(report, files):
        for path, content in files.items():
            if '/observations/' not in path:
                continue
            row = yaml.safe_load(content)
            if predicate(row):
                mutate(row)
                files[path] = yaml.safe_dump(row).encode()
                return
        raise AssertionError('Missing tamper subject')
    return change


for field, value in [('schemaVersion', 0), ('evidenceMode', 'comparison'), ('parityStatus', 'passed'),
                     ('exitCode', 1), ('validationResult', 'failed'), ('observationCount', 155),
                     ('paperJarSha256', '0' * 64), ('mythicMobsSha256', '0' * 64), ('pluginSha256', 'missing')]:
    reject(field, lambda r, f, key=field, value=value: r.update({key: value}))
for action in audit.ACTIONS:
    for predicate in (False, True):
        reject(action + str(predicate), row_change(
            lambda r, action=action, predicate=predicate: r['action'] == action and r['predicate'] is predicate,
            lambda r: r.update(nativePassed=not r['nativePassed'], runtimePassed=not r['runtimePassed'],
                               runtimeParent=int(not r['nativePassed']))))
for field, value in [('runtimeChildren', 10), ('nativeChildren', 10), ('runtimePower', 10), ('nativePower', 10),
                     ('previewUntouched', False), ('matched', False), ('source', 'burning CAST wrong'),
                     ('mapped', 'on-fire'), ('subject', '00000000-0000-0000-0000-000000000000'),
                     ('nativeChildTargets', ['targets=0'])]:
    reject(field, row_change(lambda r: r['action'] == 'CAST' and r['predicate'] is True,
                            lambda r, key=field, value=value: r.update({key: value})))
reject('agreed incorrect power', row_change(lambda r: r['action'] == 'POWER' and r['predicate'] is True,
                                           lambda r: r.update(nativePower=3, runtimePower=3)))
reject('missing child still called', row_change(lambda r: r['action'] == 'CAST' and r['availability'] == 'missing',
                                               lambda r: r.update(nativeChildren=1, runtimeChildren=1)))
reject('missing row', lambda r, f: f.pop(next(path for path in f if '/observations/' in path)))
reject('missing completion', lambda r, f: f.update({'server.log': f['server.log'].replace(b'CACTION_SUMMARY', b'NO_SUMMARY')}))
reject('changed row log', lambda r, f: f.update({'server.log': f['server.log'].replace(b'CACTION_ROW|id=0|', b'CACTION_ROW|id=999|')}))
reject('missing source', lambda r, f: r['sourceHashes'].pop(next(iter(r['sourceHashes']))))
print(f'condition-action tamper controls passed: {controls}')
