"""Audit terminal read observations without promoting them to release evidence."""
import argparse
import base64
import collections
import importlib.util
import itertools
import json
from pathlib import Path
import re
import sys
import uuid
import zipfile

sys.dont_write_bytecode = True
spec = importlib.util.spec_from_file_location('terminal_variable_audit', Path(__file__).with_name('validate-terminal-variable.py'))
v = importlib.util.module_from_spec(spec)
spec.loader.exec_module(v)
require, fields, unique = v.require, v.fields, v.unique
BACKENDS = ('native', 'runtime')
SCENARIOS = ('selfdeath', 'peerdeath', 'control', 'command', 'death', 'cancelled', 'chunk', 'suspended')
DELAYS = dict(start=0, seeded=3, mutated=13, targetadd=25, expired=65,
              afteradd=67, finish=125, targetset=137, targetunset=147)
READ_KEYS = {role + suffix for role in ('caster', 'target', 'trigger')
             for suffix in ('Uuid', 'Marker', 'Counter', 'Pre', 'Missing', 'Raw')}


def read(path):
    path = Path(path)
    report = v.js(path.read_text())
    name = v.safe_path(report['logArchive'])
    require(Path(name).name == name and name.endswith('.zip'), 'unsafe archive')
    archive = path.parent / name
    require(v.digest(archive.read_bytes()) == report['logArchiveSha256'], 'archive changed')
    manifest = unique((v.safe_path(f['path']), f['sha256']) for f in report['files'])
    with zipfile.ZipFile(archive) as z:
        names = z.namelist()
        require(len(names) == len(set(names)) and set(names) == set(manifest), 'archive membership changed')
        payloads = {name: z.read(name) for name in names}
    for name, data in payloads.items():
        require(v.hash_value(manifest[name]) and v.digest(data) == manifest[name], 'member changed: ' + name)
    return report, payloads


def decode(row):
    try:
        value = base64.b64decode(row['reads'], validate=True).decode()
    except (ValueError, UnicodeError) as exc:
        raise ValueError('invalid encoded reads') from exc
    result = unique(part.split('=', 1) for part in value.split(';'))
    require(set(result) == READ_KEYS, 'missing/unknown read field')
    return result


def audit(report, payloads):
    require(report['schemaVersion'] == 1 and report['evidenceMode'] == 'observation'
            and report['parityStatus'] == 'unassessed' and not report['knownExceptions'],
            'this audit accepts unpromoted observations only')
    require('validationResult' not in report and report['boot']['exitCode'] == 0, 'failed observation run')
    require(report['paperJarSha256'] == '39bd8c00b9e18de91dcabd3cc3dcfa5328685a53b7187a2f63280c22e2d287b9'
            and report['mythicMobsSha256'] == '9672457cafc56ff841606bfbb710947329c08720ffc207746b60130d43bf902f',
            'wrong pinned oracle')
    require(all(v.hash_value(report[k]) for k in ('pluginSha256', 'probeJarSha256')), 'invalid artifact hash')
    source_names = {'run-terminal-read-fixture.py', 'run-player-variable-fixture.py', 'terminal-read-cases.py',
                    'random-spawn-fixture/src/main/java/dev/helstera/test/randomspawn/TerminalReadProbe.java'}
    require(set(report['sourceHashes']) == {'tools/paper-integration/' + n for n in source_names}
            and all(v.hash_value(h) for h in report['sourceHashes'].values()), 'invalid source manifest')
    source = v.doc(payloads['source-mobs.yml'])
    expanded = 'tr_peer' in source
    cases = {f'{s}_{k}_{p}': s for s, k, p in itertools.product(
        SCENARIOS if expanded else SCENARIOS[2:], ('cow', 'zombie'), ('direct', 'imported'))}
    expected_files = {'server.log', 'source-mobs.yml', 'source-skills.yml', 'imports/skills.yml',
                      'snapshots/final-scoped.yml', 'snapshots/scoped-variables.yml'} | {
                          'imports/tr_' + key + '.yml' for key in cases}
    require(set(payloads) == expected_files, 'missing/orphan payload')
    require(set(source) == {'tr_' + key for key in cases} | ({'tr_peer'} if expanded else set()), 'wrong stimulus cases')
    for filename in ('snapshots/final-scoped.yml', 'snapshots/scoped-variables.yml'):
        require(v.doc(payloads[filename]) == {'registry-format': 1, 'registries': {}}, 'orphan final registry')
    text = payloads['server.log'].decode()
    require(not any(bad in text for bad in ('TREAD_FAILED', 'Error occurred while', 'Exception:')), 'failed probe')
    lines = text.splitlines()
    ready = [i for i, line in enumerate(lines) if re.search(r'Done \([0-9.]+s\)! For help,', line)]
    require(len(ready) == 1 and text.count('Starting minecraft server version 1.21.1') == 1, 'invalid Paper boot')
    events = collections.defaultdict(list)
    allowed = {'SEED', 'ID', 'ROW', 'HOOK', 'ACTION', 'REQUEST', 'UNLOAD', 'COMMAND', 'DEATH', 'SEAL', 'SUMMARY', 'ATTACKER'}
    event_fields = dict(v.EVENT_FIELDS, HOOK='key backend label uuid reads registry liveVariables terminalExecutions cleaning tick millis primary',
                        ATTACKER='key backend uuid tick')
    last_tick, last_millis = -1, 0
    generated, started, stopped = (v.utc(report[k]) if k == 'generatedAtUtc' else v.utc(report['boot'][k])
                                    for k in ('generatedAtUtc', 'startedAtUtc', 'stoppedAtUtc'))
    require(generated <= started < stopped, 'invalid boot time')
    for at, line in enumerate(lines):
        if 'TREAD_' not in line:
            continue
        match = re.search(r'TREAD_([A-Z]+)\|', line)
        require(match is not None and match[1] in allowed and line.count('TREAD_') == 1, 'invalid event')
        require(at > ready[0], 'event before boot')
        row = fields(line, 'TREAD_' + match[1] + '|')
        require(set(row) == set(event_fields[match[1]].split()), 'wrong event fields')
        for name in ('tick', 'millis', 'deadline', 'instance'):
            if name in row:
                require(re.fullmatch(r'0|[1-9][0-9]*', row[name]) is not None, 'invalid ' + name)
        if 'tick' in row:
            require(int(row['tick']) >= last_tick, 'event ticks reversed')
            last_tick = int(row['tick'])
        if 'millis' in row:
            current = int(row['millis'])
            require(started.timestamp() * 1000 <= current <= stopped.timestamp() * 1000 and current >= last_millis,
                    'event time outside boot/reversed')
            last_millis = current
        if 'key' in row:
            require(row['key'] in cases, 'unknown case')
        if 'backend' in row:
            require(row['backend'] in BACKENDS, 'unknown backend')
        events[match[1]].append((at, row))

    def indexed(name, keys):
        return unique((tuple(r[k] for k in keys), (at, r)) for at, r in events[name])

    ids = indexed('ID', ('key', 'backend'))
    seeds = indexed('SEED', ('key', 'backend'))
    identities = set(itertools.product(cases, BACKENDS))
    require(set(ids) == set(seeds) == identities and len({r['uuid'] for _, r in ids.values()}) == len(identities), 'missing/duplicate identity')
    rows = indexed('ROW', ('key', 'backend', 'phase'))
    require(set(rows) == set(itertools.product(cases, BACKENDS, ('initial', 'settled', 'cleaned'))), 'missing/extra lifecycle row')
    require(report['observations'] == [l for l in lines if 'TREAD_ROW|' in l], 'report/log disagree')
    hooks = indexed('HOOK', ('key', 'backend', 'label'))
    expected_hooks = {(k, b, label) for k, b in identities for label in DELAYS}
    expected_hooks |= {(k, b, 'pending') for k, b in identities if cases[k] in ('control', 'cancelled')}
    require(set(hooks) == expected_hooks, 'missing/extra callback')
    actions, seals = (indexed(n, ('key',)) for n in ('ACTION', 'SEAL'))
    require(set(actions) == set(seals) == {(k,) for k in cases}, 'missing/extra action or seal')
    attackers = indexed('ATTACKER', ('key', 'backend'))
    require(set(attackers) == {(k, b) for k, b in identities if cases[k] in ('selfdeath', 'peerdeath')}, 'wrong attacker cases')
    diffs, failures, reads = [], [], {}
    for (key, backend), (id_at, identity) in ids.items():
        scenario = cases[key]
        parent = identity['uuid']
        require(str(uuid.UUID(parent)) == parent and seeds[(key, backend)][1]['uuid'] == parent, 'wrong parent UUID')
        action_at, action = actions[(key,)]
        seal_at, seal = seals[(key,)]
        require(action['scenario'] == scenario and seal['accepted'] == 'true', 'wrong action or failed seal')
        spawn_tick, action_tick = int(identity['tick']), int(action['tick'])
        live = scenario in ('control', 'cancelled')
        for phase in ('initial', 'settled', 'cleaned'):
            at, row = rows[(key, backend, phase)]
            require(id_at < at < seal_at and row['uuid'] == parent, 'wrong row ordering/identity')
            require(int(row['tick']) == (spawn_tick if phase == 'initial' else action_tick + (210 if phase == 'settled' else 230)),
                    'wrong lifecycle timing')
            present = phase == 'initial' or phase == 'settled' and live
            require(row['entity'] == row['registry'] == str(present).lower(), 'lost/resurrected parent')
            require(row['marker'] == ('73' if phase == 'initial' else 'absent'), 'stale/missing marker')
            require(all(row[f] == '0' for f in ('terminalExecutions', 'ownerRecords', 'minionRecords', 'liveLocations')), 'leaked live state')
            expected_vars = (2 if phase == 'initial' else 1) if present and backend == 'runtime' else 0
            require(row['liveVariables'] == str(expected_vars), 'leaked/missing live variables')
            visuals = 11 if backend == 'runtime' and (present or scenario == 'peerdeath' and phase == 'settled') else 0
            require(row['visuals'] == str(visuals), 'missing/orphan model')
        for label in (*DELAYS, *(('pending',) if live else ())):
            at, row = hooks[(key, backend, label)]
            require(row['uuid'] == parent and row['primary'] == 'true' and row['cleaning'] == 'false', 'wrong hook context')
            require(id_at < at < rows[(key, backend, 'settled')][0], 'hook outside lifecycle')
            expected_tick = spawn_tick + 180 if label == 'pending' else int(hooks[(key, backend, 'start')][1]['tick']) + DELAYS[label]
            require(abs(int(row['tick']) - expected_tick) <= (1 if backend == 'native' and label not in ('start', 'pending') else 0),
                    'wrong callback delay')
            decoded = decode(row)
            reads[(key, backend, label)] = decoded
            if expanded:
                require(decoded['casterUuid'] == decoded['targetUuid'] == parent, 'wrong caster/target read UUID')
                if scenario in ('selfdeath', 'peerdeath'):
                    attacker = attackers[(key, backend)][1]['uuid']
                    require(decoded['triggerUuid'] == attacker, 'wrong trigger read UUID')
                    require((attacker == parent) == (scenario == 'selfdeath'), 'wrong attacker identity')
            early = label in ('start', 'seeded', 'mutated', 'targetadd')
            require((int(row['millis']) < int(seeds[(key, backend)][1]['deadline'])) == early, 'unproven expiry crossing')
            expected = dict(Marker='73', Counter='absent' if label in ('start', 'finish', 'targetset', 'targetunset', 'pending') else '1' if label == 'seeded' else '3',
                            Pre='17' if early else 'absent', Missing='absent')
            if live and label not in ('start', 'seeded', 'mutated'):
                expected['Marker'] = '80' if label in ('targetadd', 'expired', 'afteradd', 'finish') else '91' if label == 'targetset' else 'absent'
            for name, value in expected.items():
                require(decoded['caster' + name] == value, 'wrong retained caster ' + name)
            # A focused invariant after every native/runtime removal has settled.
            # Earlier callbacks stay in the diff; they are not hidden as passing parity.
            stable = label in ('targetadd', 'expired', 'afteradd', 'finish', 'targetset', 'targetunset')
            if stable and not live:
                require(row['registry'] == 'false' and row['liveVariables'] == '0', 'resurrected callback registry')
                for suffix in ('Marker', 'Counter', 'Pre', 'Missing', 'Raw'):
                    token = '<target.var.' + ('Marker' if suffix == 'Raw' else suffix) + ('' if suffix == 'Raw' else '|absent') + '>'
                    if decoded['target' + suffix] != token:
                        failures.append(f'{key}/{backend}/{label}/target{suffix}')
                    if scenario == 'selfdeath':
                        if decoded['trigger' + suffix] != token.replace('target.', 'trigger.'):
                            failures.append(f'{key}/{backend}/{label}/trigger{suffix}')
                if scenario == 'peerdeath':
                    require(decoded['triggerMarker'] == decoded['triggerRaw'] == '29', 'lost live peer trigger')
                if expanded and scenario in ('command', 'chunk', 'suspended') and decoded['triggerUuid'] != parent:
                    failures.append(f'{key}/{backend}/{label}/triggerUuid')
    for key, scenario in cases.items():
        for label in (*DELAYS, *(('pending',) if scenario in ('control', 'cancelled') else ())):
            a, b = reads[(key, 'native', label)], reads[(key, 'runtime', label)]
            for field in sorted(READ_KEYS):
                def normalized(backend, value):
                    if value == ids[(key, backend)][1]['uuid']:
                        return 'self'
                    if (key, backend) in attackers and value == attackers[(key, backend)][1]['uuid']:
                        return 'peer'
                    return value
                av, bv = normalized('native', a[field]), normalized('runtime', b[field])
                if av != bv:
                    diffs.append(dict(case=key, label=label, field=field, native=av, runtime=bv))
    require(len(events['SUMMARY']) == 1 and events['SUMMARY'][0][1] == {
        'rows': str(len(rows)), 'cases': str(len(cases)), 'completed': 'true'}, 'incomplete summary')
    summary_at = events['SUMMARY'][0][0]
    require(summary_at > max(at for at, _ in seals.values()) and any(']: Stopping server' in l for l in lines[summary_at:]), 'missing clean shutdown')
    return dict(evidenceMode='observation', parityStatus='unassessed', pluginSha256=report['pluginSha256'],
                cases=len(cases), identities=len(ids), rows=len(rows), callbacks=len(hooks),
                identityPlaceholdersVerified=expanded, detachedReadFailures=failures, differences=diffs)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('report')
    parser.add_argument('--require-detached-reads', action='store_true', help='require only the stable detached-read invariant; never a parity/release verdict')
    parser.add_argument('--plugin-jar', type=Path, help='verify the observation artifact against this local build')
    args = parser.parse_args()
    report, payloads = read(args.report)
    result = audit(report, payloads)
    if args.plugin_jar:
        require(v.digest(args.plugin_jar.read_bytes()) == report['pluginSha256'], 'wrong plugin artifact')
    print(json.dumps(result, indent=2))
    if args.require_detached_reads:
        require(result['identityPlaceholdersVerified'] and not result['detachedReadFailures'], 'detached-read invariant failed')


if __name__ == '__main__':
    main()
