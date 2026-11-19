"""Mutation controls against a completed real fixture, never synthetic passing evidence."""
import copy
import importlib.util
import json
import pathlib
import sys

sys.dont_write_bytecode = True
path = pathlib.Path(__file__).with_name('validate-variable-persistence.py')
spec = importlib.util.spec_from_file_location('persistence_validator', path)
validator = importlib.util.module_from_spec(spec)
spec.loader.exec_module(validator)
root = pathlib.Path(sys.argv[1])
validator.main(root)
report = json.loads((root / 'variable-persistence-report.json').read_text(encoding='utf-8-sig'))
texts = {e['path']: (root / e['path']).read_text(encoding='utf-8-sig') for e in report['logs'] + report['snapshots']}
controls = []


def row_control(label, stage, predicate, transform):
    changed, logs = copy.deepcopy(report), dict(texts)
    probe = next(p for p in changed['probes'] if p['id'].endswith('.' + stage))
    index = next(i for i, row in enumerate(probe['observations']) if predicate(row))
    before = probe['observations'][index]
    after = transform(before)
    probe['observations'][index] = after
    logs[f'persistence-{stage}.log'] = logs[f'persistence-{stage}.log'].replace(before, after)
    controls.append((label, changed, logs))


row_control('mismatch', 'write', lambda _: True, lambda r: r.replace('matched=true', 'matched=false'))
row_control('both absent', 'verify', lambda r: '|case=Save|' in r, lambda r: r.replace('Present=true', 'Present=false'))
row_control('world transient retained', 'verify', lambda r: '|scope=world|case=Default|' in r, lambda r: r.replace('Present=false', 'Present=true'))
row_control('wrong scalar', 'verify', lambda r: '|case=Save|' in r, lambda r: r.replace('native=7|runtime=7', 'native=8|runtime=8'))
row_control('deadline reset', 'verify', lambda r: '|case=TimedSave|' in r,
            lambda r: r.replace('runtimeDeadline=', 'runtimeDeadline=1'))
row_control('resurrection', 'final', lambda _: True, lambda r: r.replace('Present=false', 'Present=true'))
row_control('duplicate row', 'write', lambda _: True, lambda _: report['probes'][0]['observations'][1])
changed = copy.deepcopy(report); changed['probes'].pop(); controls.append(('missing boot', changed, texts))
changed = copy.deepcopy(report); changed['evidenceMode'] = 'native-observation'; controls.append(('observation-only', changed, texts))
changed = copy.deepcopy(report); changed['pluginSha256'] = ''; controls.append(('missing artifact', changed, texts))
logs = dict(texts); logs['persistence-final.log'] = logs['persistence-final.log'].replace('mode=PERSISTENT', 'mode=NORMAL', 1)
controls.append(('lost identity', report, logs))
logs = dict(texts); logs['persistence-final.yml'] += 'unexpected: value\n'; controls.append(('resaved data', report, logs))
for label, changed, logs in controls:
    try:
        validator.validate(changed, logs)
    except ValueError:
        continue
    raise AssertionError('accepted invalid evidence: ' + label)
print(f'Rejected all {len(controls)} persistence evidence mutation controls.')
