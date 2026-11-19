"""Mutate completed real-player evidence; every altered lifecycle must be rejected."""
import copy
import hashlib
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import zipfile

sys.dont_write_bytecode = True
spec = importlib.util.spec_from_file_location('player_validator', Path(__file__).with_name('validate-player-variables.py'))
validator = importlib.util.module_from_spec(spec)
spec.loader.exec_module(validator)
report, texts = validator.main(sys.argv[1])
controls = []


def row_control(label, stage, predicate, transform):
    changed, logs = copy.deepcopy(report), dict(texts)
    entry = next(s for s in changed['stages'] if s['stage'] == stage)
    index = next(i for i, row in enumerate(entry['observations']) if predicate(row))
    before = entry['observations'][index]
    after = transform(before)
    assert before != after, label
    entry['observations'][index] = after
    logs[f'{stage}.server.log'] = logs[f'{stage}.server.log'].replace(before, after)
    controls.append((label, changed, logs))


row_control('missing row', 'write', lambda _: True, lambda _: '')
row_control('duplicate row', 'write', lambda _: True, lambda _: report['stages'][0]['observations'][1])
row_control('wrong player UUID', 'write', lambda _: True, lambda r: r.replace(validator.offline_id('VariableOwner'), validator.offline_id('VariablePeer')))
row_control('vacuous absence', 'verify', lambda r: '|case=Save|' in r, lambda r: r.replace('=true', '=false'))
row_control('expired value returns', 'verify', lambda r: '|case=Expired|' in r, lambda r: r.replace('=false', '=true'))
row_control('deleted value returns', 'final', lambda r: '|case=Save|' in r, lambda r: r.replace('=false', '=true'))
row_control('deadline reset', 'verify', lambda r: '|case=TimedSave|' in r, lambda r: r.replace('runtimeDeadline=', 'runtimeDeadline=1'))
row_control('wrong save marker', 'write', lambda r: '|case=Transient|' in r, lambda r: r.replace('Deadline=1', 'Deadline=0'))
row_control('cross-player contamination', 'write', lambda _: True, lambda r: r.replace('isolated=true', 'isolated=false'))
row_control('lost quit event write', 'verify', lambda r: 'PLAYER_VAR_HOOK|' in r, lambda r: r.replace('native=2|runtime=2', 'native=1|runtime=1'))
row_control('lost online shutdown write', 'final', lambda r: 'PLAYER_VAR_HOOK|' in r and 'VariablePeer' in r, lambda r: r.replace('native=30|runtime=30', 'native=3|runtime=3'))
changed = copy.deepcopy(report); changed['stages'].pop(); controls.append(('missing boot', changed, texts))
changed = copy.deepcopy(report); changed['evidenceMode'] = 'native'; controls.append(('observation only', changed, texts))
changed = copy.deepcopy(report); changed['pluginSha256'] = ''; controls.append(('missing artifact', changed, texts))


def log_control(label, filename, transform):
    logs = dict(texts); logs[filename] = transform(logs[filename])
    assert logs[filename] != texts[filename], label
    controls.append((label, report, logs))


for marker in ('PLAYER_VAR_SESSION|', 'PLAYER_VAR_UNLOADED|', 'PLAYER_VAR_QUIT_WRITE|', 'PLAYER_VAR_ONLINE_WRITE|'):
    log_control('missing ' + marker, 'verify.server.log', lambda text, marker=marker: '\n'.join(line for line in text.splitlines() if marker not in line))
log_control('wrong quit write', 'write.server.log', lambda text: text.replace('|value=1|passed=true', '|value=99|passed=true', 1))
log_control('missing online client', 'verify.client.log', lambda text: text.replace('"event":"ready-online"', '"event":"wrong"'))
identity = validator.offline_id('VariableOwner')
native_path = f'write-logout/native-{identity}.json'
runtime_path = f'write-logout/runtime-{identity}.yml'


def native_control(label, transform):
    doc = json.loads(texts[native_path]); transform(doc)
    log_control(label, native_path, lambda _: json.dumps(doc))


native_control('disk scalar changed', lambda d: d['variables']['entries']['PlayerD0casterSave'].update(value=8))
native_control('disk deadline changed', lambda d: d['variables']['entries']['PlayerD0casterTimedSave'].update(expirationTime=0))
native_control('disk type changed', lambda d: d['variables']['entries']['PlayerD0casterSave'].update(type='FLOAT'))
native_control('disk location changed', lambda d: d['variables']['entries']['PlayerD0casterLocation']['value'].update(x=999))
native_control('disk identity changed', lambda d: d.update(uniqueId=validator.offline_id('VariablePeer')))
log_control('duplicate JSON key', native_path, lambda text: text[:-1] + ',"name":"VariableOwner"}')
log_control('wrong runtime registry', runtime_path, lambda text: text.replace('player:' + identity, 'player:' + validator.offline_id('VariablePeer')))
log_control('duplicate YAML key', runtime_path, lambda text: text + '\nregistry-format: 1\n')
peer = validator.offline_id('VariablePeer')
log_control('stale online shutdown snapshot', f'verify-logout/shutdown-runtime-{peer}.yml', lambda _: texts[f'verify-logout/runtime-{peer}.yml'])
for label, changed, logs in controls:
    try: validator.validate(changed, logs)
    except ValueError: continue
    raise AssertionError('accepted invalid evidence: ' + label)

# A shareable archive must verify the archive itself and each individual member.
with tempfile.TemporaryDirectory() as temporary:
    directory = Path(temporary); archive_path = directory / 'evidence.zip'; report_path = directory / 'evidence.json'
    def archive_report(changed_texts):
        with zipfile.ZipFile(archive_path, 'w', zipfile.ZIP_DEFLATED) as archive:
            for name, text in changed_texts.items(): archive.writestr(name, text.encode())
        result = copy.deepcopy(report)
        result['logArchive'] = archive_path.name
        result['logArchiveSha256'] = hashlib.sha256(archive_path.read_bytes()).hexdigest()
        # Normalize only the original evidence (some platforms write UTF-8 BOMs).
        result['files'] = [{'path': name, 'sha256': hashlib.sha256(text.encode()).hexdigest()} for name, text in texts.items()]
        report_path.write_text(json.dumps(result))
    archive_report(texts); validator.main(report_path)
    for label, reseal in (('archive hash', False), ('member hash', True)):
        altered = dict(texts); altered['final.client.log'] += '\ntampered\n'
        archive_report(altered)
        if not reseal:
            with archive_path.open('ab') as output: output.write(b'tampered')
        try: validator.main(report_path)
        except ValueError: continue
        raise AssertionError('accepted invalid ' + label)
print(f'Rejected all {len(controls) + 2} player evidence mutation controls.')
