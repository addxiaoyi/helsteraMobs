"""Mutation tests for terminal variable evidence; diagnostic controls never write evidence.

Usage: python -B test-terminal-variable-evidence.py REPORT_OR_DIRECTORY
Before a final comparison exists, --diagnostic-observer tests a synthetic in-memory
control derived from an observer. Its target corrections and simulated unset hooks
are printed explicitly and cannot promote the original report or archive.
"""
import argparse
import copy
from datetime import datetime, timezone
import importlib.util
import io
import json
from pathlib import Path
import re
import sys
from unittest.mock import patch
import warnings
import zipfile

import yaml

sys.dont_write_bytecode = True
spec = importlib.util.spec_from_file_location('terminal_validator', Path(__file__).with_name('validate-terminal-variable.py'))
v = importlib.util.module_from_spec(spec)
spec.loader.exec_module(v)


def rejected(label, call, reason=None):
    try:
        call()
    except (ValueError, KeyError) as exc:
        if reason is not None:
            assert reason in str(exc), (label, str(exc), reason)
        return str(exc)
    raise AssertionError('Accepted invalid evidence: ' + label)


def rehash(report, payloads):
    report['files'] = [{'path': name, 'sha256': v.digest(data)} for name, data in sorted(payloads.items())]
    report['observations'] = [line for line in payloads['server.log'].decode().splitlines() if 'TVAR_ROW|' in line]


def replace_fields(line, **changes):
    for name, value in changes.items():
        line, count = re.subn(r'(?<=\|)' + re.escape(name) + r'=[^|\n]*', name + '=' + str(value), line)
        assert count == 1, (name, line)
    return line


def diagnostic_control(report, payloads):
    """Explicit r2-to-final test adaptation, solely in memory; never a validator exception."""
    assert report['evidenceMode'] == 'observation', 'diagnostic option requires an observer'
    rejected('original observer', lambda: v.validate(report, payloads), 'observer rejected')
    r, p = copy.deepcopy(report), dict(payloads)
    r.update(evidenceMode='comparison-with-exception', parityStatus='scoped-comparison',
             knownExceptions=list(v.PARITY_EXCEPTIONS))
    r.pop('validationResult', None)
    root = Path(__file__).resolve().parents[2]
    r['sourceHashes'] = {'tools/paper-integration/' + name:
                        v.digest((root / 'tools/paper-integration' / name).read_bytes()) for name in v.SOURCES}
    lines = p['server.log'].decode().splitlines()
    hook_lines = [line for line in lines if 'TVAR_HOOK|' in line]
    old_contract = not any('|label=targetunset|' in line for line in hook_lines)
    if old_contract:
        assert len(hook_lines) == 400, 'only the complete pre-unset observer can be adapted'
        for filename, native in (('source-skills.yml', True), ('imports/skills.yml', False)):
            document = v.doc(p[filename])
            skills = document if native else document['skills']
            for path in ('direct', 'imported'):
                field = 'Skills' if native else 'on-decision'
                terminal = skills['tv_terminal_' + path][field]
                assert len(terminal) == 9
                terminal += [f'skill{{s=tv_targetunset_{path};delay=145}} @self',
                             terminal[-1].replace('targetset', 'targetunset').replace('delay=137', 'delay=147')]
                unset = ('variableunset{var=target.Marker} @self' if native or path == 'direct'
                         else 'variable-unset var=target.Marker @self')
                skills['tv_targetunset_' + path] = {field: [unset]}
            p[filename] = yaml.safe_dump(document, sort_keys=False).encode()
    corrections, inserted = [], []
    for i, line in enumerate(lines):
        if 'TVAR_HOOK|' in line:
            row = v.fields(line, 'TVAR_HOOK|')
            live = row['key'].startswith(('control_', 'cancelled_'))
            if old_contract and row['label'] == 'targetset':
                new = replace_fields(line, label='targetunset', Marker='absent' if live else '73',
                                     tick=int(row['tick']) + 10, millis=int(row['millis']) + 500)
                stamp = datetime.fromtimestamp((int(row['millis']) + 500) / 1000, timezone.utc).strftime('%H:%M:%S')
                new = re.sub(r'^\[[0-9:]+\]', '[' + stamp + ']', new)
                inserted.append(new)
            if not live and row['backend'] == 'runtime' and row['Marker'] != '73':
                assert row['label'] in ('targetadd', 'expired', 'afteradd', 'finish', 'targetset', 'targetunset')
                assert row['Marker'] in ('80', '91', 'absent')
                corrections.append((row['key'], row['label'], row['Marker']))
                line = replace_fields(line, Marker='73')
            if old_contract and live and row['label'] == 'pending':
                assert row['Marker'] == '91'
                line = replace_fields(line, Marker='absent')
        elif old_contract and 'TVAR_ROW|' in line:
            row = v.fields(line, 'TVAR_ROW|')
            if row['key'].startswith(('control_', 'cancelled_')) and row['phase'] == 'settled':
                assert row['marker'] == '91'
                line = replace_fields(line, marker='absent', liveVariables=1 if row['backend'] == 'runtime' else 0)
        lines[i] = line
    for line in sorted(inserted, key=lambda l: int(v.fields(l, 'TVAR_HOOK|')['millis'])):
        row = v.fields(line, 'TVAR_HOOK|')
        for i, existing in enumerate(lines):
            tick = re.search(r'\|tick=([0-9]+)', existing) if 'TVAR_' in existing else None
            if tick and int(tick[1]) > int(row['tick']):
                lines.insert(i, line)
                break
        else:
            raise AssertionError('No insertion point for simulated targetunset')
    p['server.log'] = ('\n'.join(lines) + '\n').encode()
    rehash(r, p)
    # Demonstrate that the observed target mismatch alone fails the final contract.
    if corrections:
        raw_target = dict(p)
        target_lines = raw_target['server.log'].decode().splitlines()
        key, label, value = corrections[0]
        at = next(i for i, line in enumerate(target_lines) if 'TVAR_HOOK|' in line
                  and f'key={key}|' in line and '|backend=runtime|' in line and f'|label={label}|' in line)
        target_lines[at] = replace_fields(target_lines[at], Marker=value)
        raw_target['server.log'] = ('\n'.join(target_lines) + '\n').encode()
        raw_report = copy.deepcopy(r)
        rehash(raw_report, raw_target)
        reason = rejected('observed removed-target mismatch', lambda: v.validate(raw_report, raw_target), 'Marker expected 73')
        print('Observed mismatch remains rejected:', reason)
    print(f'DIAGNOSTIC ONLY: {len(corrections)} observed removed-target hook values corrected in memory; '
          f'{len(inserted)} targetunset hooks simulated. Original evidence remains observation and rejected.', flush=True)
    v.validate(r, p)
    return r, p


def run_mutations(report, payloads):
    count = 0

    def check(label, mutate, reason=None, reseal=True):
        nonlocal count
        r, p = copy.deepcopy(report), dict(payloads)
        mutate(r, p)
        assert json.dumps(r, sort_keys=True) != json.dumps(report, sort_keys=True) or p != payloads, 'no-op mutation: ' + label
        if reseal:
            rehash(r, p)
        rejected(label, lambda: v.validate(r, p), reason)
        count += 1

    def event(label, marker, select, change, reason=None):
        def mutate(r, p):
            lines = p['server.log'].decode().splitlines()
            i = next(i for i, line in enumerate(lines) if marker in line and
                     all(v.fields(line, marker).get(k) == str(value) for k, value in select.items()))
            after = change(lines[i])
            assert after != lines[i], label
            lines[i] = after
            p['server.log'] = ('\n'.join(lines) + '\n').encode()
        check(label, mutate, reason)

    def hook(key, backend, label, name, value):
        event(f'{key}/{backend}/{label}/{name}', 'TVAR_HOOK|',
              dict(key=key, backend=backend, label=label),
              lambda line: replace_fields(line, **{name: value}), name + ' expected')

    # Each backend and import path has independently checked variable semantics.
    for backend in v.BACKENDS:
        for path in ('direct', 'imported'):
            key = 'death_cow_' + path
            for label, changes in {
                'seeded': dict(Marker='absent', Pre='absent', Counter='absent', Short='absent', Keep='absent',
                               Refresh='absent', Reset='absent', Unset='absent', Scratch='absent', Flow='absent'),
                'mutated': dict(Counter='1', Keep='21', Refresh='31', Reset='41', Unset='51', Flow='5'),
                'expired': dict(Pre='17', Short='11', Keep='23', Refresh='absent', Reset='absent', Scratch='absent'),
                'afteradd': dict(Short='9'),
                'finish': dict(Counter='3', Scratch='61', Refresh='33', Reset='absent', Flow='absent'),
            }.items():
                for name, value in changes.items():
                    hook(key, backend, label, name, value)
    # Test target add/set/unset for all 24 cases, not merely equality between backends.
    for key, (scenario, kind, path) in v.CASES.items():
        for backend in v.BACKENDS:
            for label, value in (('targetadd', '73' if scenario in ('control', 'cancelled') else '80'),
                                 ('targetset', '73' if scenario in ('control', 'cancelled') else '91'),
                                 ('targetunset', '91' if scenario in ('control', 'cancelled') else 'absent')):
                hook(key, backend, label, 'Marker', value)
    for backend in v.BACKENDS:
        hook('control_cow_direct', backend, 'pending', 'Flow', 'absent')
        hook('cancelled_cow_direct', backend, 'pending', 'Flow', '8')
        hook('control_cow_direct', backend, 'pending', 'Marker', '91')
        for phase in ('initial', 'settled', 'cleaned'):
            key = 'control_cow_direct'
            for name, value in dict(entity='false' if phase != 'cleaned' else 'true',
                                    registry='false' if phase != 'cleaned' else 'true',
                                    marker='absent' if phase == 'initial' else '73', visuals='99',
                                    liveVariables='99', liveLocations='1', terminalExecutions='1',
                                    ownerRecords='1', minionRecords='1').items():
                event(f'cleanup/{backend}/{phase}/{name}', 'TVAR_ROW|', dict(key=key, backend=backend, phase=phase),
                      lambda line, n=name, val=value: replace_fields(line, **{n: val}))
        event('removed parent resurrected/' + backend, 'TVAR_ROW|',
              dict(key='death_cow_direct', backend=backend, phase='settled'),
              lambda line: replace_fields(line, entity='true'))
        for label in v.DELAYS:
            event('wrong delay/' + backend + '/' + label, 'TVAR_HOOK|',
                  dict(key='death_cow_direct', backend=backend, label=label),
                  lambda line: replace_fields(line, tick=int(v.fields(line, 'TVAR_HOOK|')['tick']) + 5))
    for marker in v.EVENT_FIELDS:
        event('missing ' + marker, 'TVAR_' + marker + '|', {}, lambda line: '')
        event('duplicate ' + marker, 'TVAR_' + marker + '|', {}, lambda line: line + '\n' + line)
    for marker, select, changes in [
        ('ID', {}, dict(uuid='not-a-uuid')), ('SEED', {}, dict(uuid='00000000-0000-0000-0000-000000000000')),
        ('SEED', {}, dict(deadline=1)), ('SEED', {}, dict(millis=1)),
        ('ID', dict(backend='runtime'), dict(instance=0)), ('ID', dict(backend='native'), dict(instance=1)),
        ('HOOK', {}, dict(uuid='00000000-0000-0000-0000-000000000000')),
        ('HOOK', {}, dict(cleaning='true')), ('HOOK', {}, dict(primary='false')),
        ('HOOK', {}, dict(label='unknown')), ('HOOK', {}, dict(backend='other')),
        ('HOOK', {}, dict(key='unknown')), ('HOOK', {}, dict(millis=1)),
        ('ROW', {}, dict(uuid='00000000-0000-0000-0000-000000000000')),
        ('ROW', dict(phase='settled'), dict(tick=1)), ('ROW', dict(phase='cleaned'), dict(phase='settled')),
        ('ACTION', {}, dict(scenario='unknown')), ('ACTION', {}, dict(tick=1)),
        ('REQUEST', {}, dict(accepted='false')), ('REQUEST', {}, dict(tick=1)),
        ('UNLOAD', {}, dict(uuid='00000000-0000-0000-0000-000000000000')), ('UNLOAD', {}, dict(tick=1)),
        ('COMMAND', {}, dict(result='false')), ('COMMAND', dict(key='suspended_cow_direct'), dict(loaded='true')),
        ('COMMAND', dict(key='command_cow_direct'), dict(loaded='false')), ('COMMAND', {}, dict(tick=1)),
        ('DEATH', dict(key='cancelled_cow_direct'), dict(cancelled='false')),
        ('DEATH', dict(key='death_cow_direct'), dict(cancelled='true')), ('DEATH', {}, dict(reviveHealth='1.0')),
        ('DEATH', {}, dict(tick=1)), ('DEATH', {}, dict(key='control_cow_direct')),
        ('SEAL', {}, dict(accepted='false')), ('SUMMARY', {}, dict(rows=143)),
        ('SUMMARY', {}, dict(cases=23)), ('SUMMARY', {}, dict(completed='false')),
    ]:
        event(str((marker, select, changes)), 'TVAR_' + marker + '|', select,
              lambda line, changes=changes: replace_fields(line, **changes))
    event('duplicate field', 'TVAR_HOOK|', {}, lambda line: line + '|Marker=73')
    event('unknown event', 'TVAR_HOOK|', {}, lambda line: line.replace('TVAR_HOOK|', 'TVAR_OTHER|'))
    event('unexpected pending after removal', 'TVAR_HOOK|', dict(key='death_cow_direct', label='finish'),
          lambda line: line.replace('|label=finish|', '|label=pending|'))
    def premature_expiry(r, p):
        lines = p['server.log'].decode().splitlines()
        seeded = min(int(v.fields(line, 'TVAR_HOOK|')['millis']) for line in lines
                     if 'TVAR_HOOK|key=death_cow_direct|' in line and '|label=seeded|' in line)
        for i, line in enumerate(lines):
            if 'TVAR_HOOK|key=death_cow_direct|' in line and any('|label=' + label + '|' in line for label in ('expired', 'afteradd')):
                lines[i] = replace_fields(line, millis=seeded + (1900 if '|label=expired|' in line else 1950))
        p['server.log'] = ('\n'.join(lines) + '\n').encode()
    check('expiry before actual duration elapsed', premature_expiry, 'duration/refresh expiry window')
    for bad in ('TVAR_FAILED|', 'Error occurred while', 'Exception:', 'Variable checkpoint load failed',
                'Variable checkpoint save failed', 'SkillTree unload save failed'):
        check(bad, lambda r, p, bad=bad: p.update({'server.log': p['server.log'] + ('\n' + bad).encode()}))
    check('missing shutdown', lambda r, p: p.update({'server.log': p['server.log'].replace(b']: Stopping server', b']: Other event')}))
    check('premature shutdown', lambda r, p: p.update({'server.log': b']: Stopping server\n' + p['server.log']}))
    check('missing Paper boot', lambda r, p: p.update({'server.log': p['server.log'].replace(b'Starting minecraft server version ', b'Starting other version ')}))
    check('missing ready event', lambda r, p: p.update({'server.log': p['server.log'].replace(b'Done (', b'Other (')}))

    def yaml_change(label, filename, change):
        def mutate(r, p):
            document = v.doc(p[filename])
            change(document)
            p[filename] = yaml.safe_dump(document, sort_keys=False).encode()
        check(label, mutate)

    for filename in ('snapshots/final-scoped.yml', 'snapshots/scoped-variables.yml'):
        yaml_change('orphan snapshot/' + filename, filename, lambda d: d['registries'].update(orphan={'scope': 'entity:orphan'}))
        check('duplicate YAML key/' + filename, lambda r, p, f=filename: p.update({f: p[f] + b'\nregistries: {}\n'}))
    yaml_change('native pending stimulus', 'source-mobs.yml',
                lambda d: d['tv_control_cow_direct']['Skills'].pop(0))
    yaml_change('native cancellation sync lost', 'source-mobs.yml',
                lambda d: d['tv_cancelled_cow_direct']['Skills'].__setitem__(-1, 'cancelevent ~onDeath'))
    for path in ('direct', 'imported'):
        for skill in ('terminal', 'seed', 'mutate', 'expired', 'finish', 'targetadd', 'targetset', 'targetunset'):
            yaml_change('native stimulus/' + skill + '/' + path, 'source-skills.yml',
                        lambda d, name='tv_' + skill + '_' + path: d[name]['Skills'].pop())
            yaml_change('mapped stimulus/' + skill + '/' + path, 'imports/skills.yml',
                        lambda d, name='tv_' + skill + '_' + path: d['skills'][name]['on-decision'].pop())
        filename = 'imports/tv_cancelled_cow_' + path + '.yml'
        for section, name, value in [('mob', 'revive-health', 1), ('mob', 'despawn', 'CHUNK'),
                                     ('mob', 'despawn-distance', 42), ('entity', 'no-ai', False),
                                     ('entity', 'gravity', True), ('entity', 'invulnerable', False)]:
            yaml_change(str((path, section, name)), filename,
                        lambda d, s=section, n=name, val=value: d[s].update({n: val}))
        yaml_change('mapped cancellation removed/' + path, filename,
                    lambda d, section='mob' if path == 'imported' else 'ai': d[section]['triggers']['on-death']['do'].pop())
        yaml_change('mapped extra option/' + path, filename, lambda d: d.update(unexpected=True))
    yaml_change('wrong CHUNK import', 'imports/tv_chunk_cow_imported.yml', lambda d: d['mob'].update(despawn='NEVER'))
    yaml_change('wrong suspended import', 'imports/tv_suspended_cow_imported.yml', lambda d: d['mob'].update(despawn='CHUNK'))

    for name, value in [('schemaVersion', 0), ('schemaVersion', True), ('evidenceMode', 'observation'),
                        ('evidenceMode', 'diagnostic-known-gap'), ('parityStatus', 'complete'),
                        ('knownExceptions', []), ('knownExceptions', v.PARITY_EXCEPTIONS + ['target-variable-gap']),
                        ('gapCases', ['death_cow_direct']), ('validationResult', 'rejected: mismatch'),
                        ('paperJarSha256', '0' * 64), ('mythicMobsSha256', '0' * 64),
                        ('pluginSha256', 'bad'), ('probeJarSha256', 'bad'), ('sourceHashes', {}),
                        ('fixtureDirectory', '../other'), ('generatedAtUtc', '2000-01-01T00:00:00')]:
        check('metadata/' + name + '/' + str(value), lambda r, p, n=name, val=value: r.update({n: val}))
    for source in v.SOURCES:
        check('missing source/' + source, lambda r, p, s=source: r['sourceHashes'].pop('tools/paper-integration/' + s))
    check('bad source hash', lambda r, p: r['sourceHashes'].update({'tools/paper-integration/validate-terminal-variable.py': 'not-a-hash'}))
    for name, value in [('exitCode', -9), ('exitCode', False), ('stoppedAtUtc', report['boot']['startedAtUtc']),
                        ('startedAtUtc', report['boot']['stoppedAtUtc']), ('stoppedAtUtc', '2000-01-01T00:00:00')]:
        check('boot/' + name + '/' + str(value), lambda r, p, n=name, val=value: r['boot'].update({n: val}))
    check('report rows disagree', lambda r, p: r['observations'].pop(), reseal=False)
    check('wrong member hash', lambda r, p: r['files'][0].update(sha256='0' * 64), reseal=False)
    check('duplicate manifest', lambda r, p: r['files'].append(r['files'][0]), reseal=False)
    check('missing manifest', lambda r, p: r['files'].pop(), reseal=False)
    check('unlisted disk state', lambda r, p: p.update({'snapshots/orphan.yml': b'orphan'}), reseal=False)
    check('missing disk state', lambda r, p: p.pop('snapshots/final-scoped.yml'), reseal=False)
    for name in ('../outside', '/absolute', 'imports\\bad.yml', './server.log', 'imports//bad.yml', 'C:outside'):
        check('unsafe manifest/' + name, lambda r, p, n=name: r['files'][0].update(path=n), reseal=False)
    return count


def transport_mutations(report, payloads):
    count = 0

    def packed(items):
        buffer = io.BytesIO()
        with warnings.catch_warnings():
            warnings.simplefilter('ignore', UserWarning)
            with zipfile.ZipFile(buffer, 'w', zipfile.ZIP_DEFLATED) as archive:
                for name, data in items:
                    archive.writestr(name, data)
        return buffer.getvalue()

    original = packed(payloads.items())
    r = copy.deepcopy(report)
    r.update(logArchive='evidence.zip', logArchiveSha256=v.digest(original))
    assert v.archive_payloads(r, original) == payloads
    for label, items in [
        ('extra archive member', list(payloads.items()) + [('snapshots/orphan.yml', b'orphan')]),
        ('missing archive member', list(payloads.items())[1:]),
        ('duplicate archive member', list(payloads.items()) + [next(iter(payloads.items()))]),
        ('unsafe archive member', list(payloads.items()) + [('../outside', b'bad')]),
        ('changed archive payload', [(name, data + b'changed' if name == 'server.log' else data) for name, data in payloads.items()]),
    ]:
        data = packed(items)
        changed = dict(r, logArchiveSha256=v.digest(data))
        rejected(label, lambda: v.archive_payloads(changed, data))
        count += 1
    rejected('changed archive digest', lambda: v.archive_payloads(dict(r, logArchiveSha256='0' * 64), original))
    count += 1
    # Exercise the public reader/CLI paths using virtual files; nothing is written.
    report_path = Path('/virtual/evidence.json')
    with patch.object(v.Path, 'is_file', return_value=True), \
         patch.object(v.Path, 'read_text', return_value=json.dumps(r)), \
         patch.object(v.Path, 'read_bytes', return_value=original):
        loaded, files = v.read_evidence(report_path)
        assert loaded == r and files == payloads
    for name in ('../evidence.zip', '/evidence.zip', 'sub/evidence.zip', 'sub\\evidence.zip', '.'):
        with patch.object(v.Path, 'is_file', return_value=True), \
             patch.object(v.Path, 'read_text', return_value=json.dumps(dict(r, logArchive=name))):
            rejected('unsafe archive reference/' + name, lambda: v.read_evidence(report_path))
        count += 1
    with patch.object(v.Path, 'is_file', return_value=True), \
         patch.object(v.Path, 'read_text', return_value='{"schemaVersion":1,"schemaVersion":1}'):
        rejected('duplicate report key', lambda: v.read_evidence(report_path), 'duplicate key')
    count += 1
    for artifact in ('paper.jar', 'plugins/helsteraMobs.jar', 'plugins/MythicMobs.jar',
                     'plugins/HelsteraTerminalVariableProbe.jar'):
        local = copy.deepcopy(report)
        fields = {'paper.jar': 'paperJarSha256', 'plugins/helsteraMobs.jar': 'pluginSha256',
                  'plugins/MythicMobs.jar': 'mythicMobsSha256', 'plugins/HelsteraTerminalVariableProbe.jar': 'probeJarSha256'}
        for field in fields.values():
            local[field] = v.digest(b'original')
        root = Path('/virtual') / local['fixtureDirectory']
        def read(path):
            return b'changed' if str(path.relative_to(root)) == artifact else b'original'
        with patch.object(v.Path, 'is_file', return_value=False), \
             patch.object(v.Path, 'read_text', return_value=json.dumps(local)), \
             patch.object(v.Path, 'read_bytes', read):
            rejected('changed local artifact/' + artifact, lambda: v.read_evidence(root), 'artifact changed: ' + artifact)
        count += 1
    return count


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('evidence')
    parser.add_argument('--diagnostic-observer', action='store_true')
    args = parser.parse_args()
    report, payloads = v.read_evidence(args.evidence)
    if args.diagnostic_observer:
        report, payloads = diagnostic_control(report, payloads)
    else:
        v.validate(report, payloads)
    count = run_mutations(report, payloads) + transport_mutations(report, payloads)
    qualifier = 'synthetic diagnostic control; no evidence promoted' if args.diagnostic_observer else 'validated comparison'
    print(f'Rejected {count} terminal-variable evidence mutations ({qualifier}).')


if __name__ == '__main__':
    main()
