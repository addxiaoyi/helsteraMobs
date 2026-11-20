"""Verify terminal variable evidence; removed targets never become writable casters."""
from datetime import datetime, timedelta
import hashlib
import importlib.util
import io
import itertools
import json
from pathlib import Path, PurePosixPath
import re
import sys
import uuid
import zipfile

import yaml

sys.dont_write_bytecode = True


def module(name, filename):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(filename))
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


registry = module('terminal_variable_registry', 'validate-player-variables.py')
contract = module('terminal_variable_contract', 'terminal-variable-cases.py')
require, fields, unique = registry.require, registry.fields, registry.unique_pairs
BACKENDS = ('native', 'runtime')
CASES = {f'{s}_{k.lower()}_{p}': (s, k, p) for s, k, p in itertools.product(
    ('control', 'command', 'death', 'cancelled', 'chunk', 'suspended'),
    ('COW', 'ZOMBIE'), ('direct', 'imported'))}
NAMES = ('Marker', 'Pre', 'Counter', 'Short', 'Keep', 'Refresh', 'Reset', 'Unset', 'Scratch')
DELAYS = dict(start=0, seeded=3, mutated=13, targetadd=25, expired=65,
              afteradd=67, finish=125, targetset=137, targetunset=147)
PARITY_EXCEPTIONS = ['MM-5.7.2-deferred-terminal-command-dispatch']
SOURCES = {'run-terminal-variable-fixture.py', 'run-player-variable-fixture.py',
           'terminal-variable-cases.py', 'validate-terminal-variable.py',
           'validate-player-variables.py', 'test-terminal-variable-evidence.py',
           'random-spawn-fixture/src/main/java/dev/helstera/test/randomspawn/TerminalVariableProbe.java'}
FILES = {'server.log', 'source-mobs.yml', 'source-skills.yml', 'imports/skills.yml',
         'snapshots/final-scoped.yml', 'snapshots/scoped-variables.yml'} | {
             'imports/tv_' + key + '.yml' for key in CASES}
EVENT_FIELDS = {
    'SEED': 'key backend uuid deadline millis', 'ID': 'key backend uuid instance tick',
    'ROW': 'key backend phase uuid entity registry marker visuals terminalExecutions ownerRecords minionRecords liveVariables liveLocations tick millis',
    'HOOK': 'key backend label uuid ' + ' '.join(NAMES) + ' Flow cleaning tick millis primary',
    'ACTION': 'key scenario tick', 'REQUEST': 'key backend accepted tick',
    'UNLOAD': 'key backend uuid tick', 'COMMAND': 'key backend result loaded tick',
    'DEATH': 'key uuid cancelled reviveHealth tick', 'SEAL': 'key accepted',
    'SUMMARY': 'rows cases completed',
}


def js(value):
    return json.loads(value, object_pairs_hook=unique)


def doc(value):
    return yaml.load(value, Loader=registry.UniqueYaml)


def digest(value):
    return hashlib.sha256(value).hexdigest()


def flag(value):
    return str(bool(value)).lower()


def hash_value(value):
    return isinstance(value, str) and re.fullmatch('[a-f0-9]{64}', value) is not None


def safe_path(value):
    require(isinstance(value, str) and value and '\\' not in value and ':' not in value,
            'unsafe member path')
    path = PurePosixPath(value)
    require(not path.is_absolute() and '..' not in path.parts and str(path) == value,
            'unsafe member path')
    return value


def manifest(report):
    entries = report['files']
    require(isinstance(entries, list), 'invalid file manifest')
    result = {}
    for entry in entries:
        require(set(entry) == {'path', 'sha256'}, 'invalid file entry')
        name = safe_path(entry['path'])
        require(name not in result and hash_value(entry['sha256']), 'duplicate member or invalid hash')
        result[name] = entry['sha256']
    require(set(result) == FILES, 'missing/orphan manifest member')
    return result


def verify_payloads(report, payloads):
    entries = manifest(report)
    require(set(payloads) == set(entries), 'missing/orphan disk state')
    for name, expected in entries.items():
        require(digest(payloads[name]) == expected, 'member changed: ' + name)


def archive_payloads(report, data):
    require(hash_value(report['logArchiveSha256']) and digest(data) == report['logArchiveSha256'],
            'archive changed')
    entries = manifest(report)
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        names = archive.namelist()
        require(len(names) == len(set(names)) and set(names) == set(entries), 'archive membership changed')
        payloads = {name: archive.read(name) for name in names}
    verify_payloads(report, payloads)
    return payloads


def runtime_hook(label, path, delay=0):
    values = ' '.join('<caster.var.' + name + '|absent>' for name in NAMES)
    command = f'terminalvarprobe hook runtime {label} <caster.uuid> {values} <skill.var.Flow|absent>'
    if path == 'direct':
        return 'command{c="' + command + '"' + (f';delay={delay}' if delay else '') + '} @self'
    return 'command' + (f'{{delay={delay}}}' if delay else '') + f' "{command}" @self'


def expected_mob(key):
    """Independent mapping contract, including importer defaults and direct spelling."""
    scenario, kind, path = CASES[key]
    triggers = {'on-spawn': {'do': [runtime_hook('pending', path, 180)]}}
    if scenario == 'control':
        triggers['on-spawn']['do'].append(f'skill{{s=tv_terminal_{path};delay=10}} @self')
    else:
        event = 'death' if scenario in ('death', 'cancelled') else 'despawn'
        actions = [f'skill{{s=tv_terminal_imported}} @self' if path == 'imported'
                   else 'cast-skill tv_terminal_direct']
        if scenario == 'cancelled':
            actions.append('cancel-event')
        triggers['on-' + event] = {'do': actions}
    mob = {'despawn': 'CHUNK' if scenario == 'chunk' else 'NEVER',
           'revive-health': 20, 'despawn-distance': 0}
    entity = {'no-ai': True, 'gravity': False, 'invulnerable': True}
    result = {'model': 'example/emberling', 'mob': mob, 'entity': entity}
    if path == 'imported':
        result.update({'schema-version': 1, 'id': 'mythic_tv_' + key})
        mob.update({'entity-type': kind, 'health': 20, 'interactable': False,
                    'prevent-leashing': True, 'prevent-transformation': True,
                    'triggers': triggers, 'ai-profile': 'default'})
        entity.update({'prevent-sunburn': True, 'prevent-item-pickup': True, 'no-damage-ticks': 10})
    else:
        entity.update({'type': kind, 'health': 20})
        result['ai'] = {'triggers': triggers}
    return result


def expected_skills():
    """Do not infer expected import output from the archived output or importer."""
    result = {}
    for path in ('direct', 'imported'):
        def hook(label, delay=0):
            return runtime_hook(label, path, delay)

        def setvar(name, value, duration=0):
            if path == 'direct':
                return f'setvariable{{var={name};type=INTEGER;value={value}' + (
                    f';duration={duration}' if duration else '') + '} @self'
            return f'set-variable var={name} type=INTEGER value="{value}"' + (
                f' duration={duration}' if duration else '') + ' @self'

        def add(name, value, duration=0):
            if path == 'direct':
                return f'variableadd{{var={name};amount={value}' + (
                    f';duration={duration}' if duration else '') + '} @self'
            return f'variable-add var={name} value={value}' + (
                f' duration={duration}' if duration else '') + ' @self'

        def unset(name):
            return (f'variableunset{{var={name}}}' if path == 'direct'
                    else f'variable-unset var={name}') + ' @self'

        def skill(name, delay):
            return f'skill{{s=tv_{name}_{path};delay={delay}}} @self'

        actions = {
            'terminal': [hook('start'), skill('seed', 3), skill('mutate', 13),
                         skill('targetadd', 23), hook('targetadd', 25), skill('expired', 65),
                         skill('finish', 125), skill('targetset', 135), hook('targetset', 137),
                         skill('targetunset', 145), hook('targetunset', 147)],
            'seed': [setvar('caster.Counter', 1), setvar('caster.Short', 11, 40),
                     setvar('caster.Keep', 21, 40), setvar('caster.Refresh', 31, 40),
                     setvar('caster.Reset', 41, 40), setvar('caster.Unset', 51),
                     setvar('caster.Scratch', 61), setvar('skill.Flow', 5), hook('seeded')],
            'mutate': [add('caster.Counter', 2), add('caster.Keep', 2), add('caster.Refresh', 2, 100),
                       setvar('caster.Reset', 43), unset('caster.Unset'), add('skill.Flow', 3), hook('mutated')],
            'expired': [hook('expired'), add('caster.Short', 9), hook('afteradd', 2)],
            'finish': [unset('caster.Counter'), unset('caster.Scratch'), hook('finish')],
            'targetadd': [add('target.Marker', 7)], 'targetset': [setvar('target.Marker', 91)],
            'targetunset': [unset('target.Marker')],
        }
        result.update({f'tv_{name}_{path}': {'on-decision': value} for name, value in actions.items()})
    return {'schema-version': 1, 'skills': result}


def utc(value):
    result = datetime.fromisoformat(value)
    require(result.utcoffset() == timedelta(0), 'timestamp must have UTC offset')
    return result


def validate(report, payloads):
    require(type(report['schemaVersion']) is int and report['schemaVersion'] == 1
            and report['evidenceMode'] == 'comparison-with-exception', 'wrong schema or mode (observer rejected)')
    require(report['knownExceptions'] == PARITY_EXCEPTIONS and report['parityStatus'] == 'scoped-comparison',
            'wrong parity scope')
    require(report['gapCases'] == [] and 'validationResult' not in report, 'unresolved/rejected evidence')
    require(report['paperJarSha256'] == '39bd8c00b9e18de91dcabd3cc3dcfa5328685a53b7187a2f63280c22e2d287b9', 'wrong Paper')
    require(report['mythicMobsSha256'] == '9672457cafc56ff841606bfbb710947329c08720ffc207746b60130d43bf902f', 'wrong native oracle')
    require(all(hash_value(report[k]) for k in ('pluginSha256', 'probeJarSha256')), 'invalid artifact hash')
    require(set(report['sourceHashes']) == {'tools/paper-integration/' + s for s in SOURCES}
            and all(hash_value(h) for h in report['sourceHashes'].values()), 'missing/invalid source hashes')
    boot = report['boot']
    generated, started, stopped = (utc(t) for t in (report['generatedAtUtc'], boot['startedAtUtc'], boot['stoppedAtUtc']))
    require(type(boot['exitCode']) is int and boot['exitCode'] == 0 and generated <= started < stopped,
            'unclean or invalid boot')
    require(re.fullmatch('srv-paper-terminal-variable-' + generated.strftime('%Y%m%d') + r'-r[1-9][0-9]*',
                         report['fixtureDirectory']) is not None, 'invalid fixture identity')
    verify_payloads(report, payloads)
    require(contract.CASES == CASES and contract.NAMES == NAMES, 'fixture contract changed')
    for name in ('final-scoped.yml', 'scoped-variables.yml'):
        require(doc(payloads['snapshots/' + name]) == {'registry-format': 1, 'registries': {}},
                'orphan final variable registry')
    require(doc(payloads['source-mobs.yml']) == contract.native_mobs(), 'native mobs differ from stimuli')
    require(doc(payloads['source-skills.yml']) == contract.native_skills(), 'native skills differ from stimuli')
    for key in CASES:
        require(doc(payloads['imports/tv_' + key + '.yml']) == expected_mob(key), 'wrong mapped mob: ' + key)
    require(doc(payloads['imports/skills.yml']) == expected_skills(), 'wrong mapped variable skills')

    text = payloads['server.log'].decode()
    lines = text.splitlines()
    for bad in ('TVAR_FAILED', 'Error occurred while', 'Exception:', 'Variable checkpoint load failed',
                'Variable checkpoint save failed', 'SkillTree unload save failed'):
        require(bad not in text, 'failed diagnostic: ' + bad)
    starts = [i for i, line in enumerate(lines) if 'Starting minecraft server version ' in line]
    ready = [i for i, line in enumerate(lines) if re.search(r'Done \([0-9.]+s\)! For help,', line)]
    require(len(starts) == len(ready) == 1 and starts[0] < ready[0]
            and 'Starting minecraft server version 1.21.1' in lines[starts[0]], 'missing/duplicate Paper boot')
    events = {name: [] for name in EVENT_FIELDS}
    last_tick, last_millis = -1, 0
    for at, line in enumerate(lines):
        if 'TVAR_' not in line:
            continue
        require(at > ready[0], 'probe event before server ready')
        match = re.search(r'TVAR_([A-Z]+)\|', line)
        require(match is not None and match[1] in events and line.count('TVAR_') == 1, 'unknown/malformed probe event')
        name = match[1]
        row = fields(line, 'TVAR_' + name + '|')
        require(set(row) == set(EVENT_FIELDS[name].split()), 'wrong fields: ' + name)
        if 'key' in row:
            require(row['key'] in CASES, 'unknown case identity')
        if 'backend' in row:
            require(row['backend'] in BACKENDS, 'unknown backend')
        for field in ('tick', 'millis', 'deadline', 'instance'):
            if field in row:
                require(re.fullmatch(r'0|[1-9][0-9]*', row[field]) is not None, 'invalid ' + field)
        if 'tick' in row:
            require(int(row['tick']) >= last_tick, 'event ticks reversed')
            last_tick = int(row['tick'])
        if 'millis' in row:
            millis = int(row['millis'])
            require(started.timestamp() * 1000 <= millis <= stopped.timestamp() * 1000
                    and millis >= last_millis, 'event wall clock outside boot or reversed')
            last_millis = millis
        events[name].append((at, row))

    def indexed(name, keys):
        return unique((tuple(row[k] for k in keys), (at, row)) for at, row in events[name])

    ids, seeds = (indexed(name, ('key', 'backend')) for name in ('ID', 'SEED'))
    identities = set(itertools.product(CASES, BACKENDS))
    require(set(ids) == set(seeds) == identities and len({r['uuid'] for _, r in ids.values()}) == 48,
            'missing/duplicate parent identity or seed')
    runtime_ids = [r['instance'] for (_, b), (_, r) in ids.items() if b == 'runtime']
    require(len(set(runtime_ids)) == 24 and all(int(i) > 0 for i in runtime_ids), 'invalid/reused runtime instance')
    rows = indexed('ROW', ('key', 'backend', 'phase'))
    require(set(rows) == set(itertools.product(CASES, BACKENDS, ('initial', 'settled', 'cleaned')))
            and report['observations'] == [l for l in lines if 'TVAR_ROW|' in l], 'report/log observations differ or missing phase')
    hooks = indexed('HOOK', ('key', 'backend', 'label'))
    expected_hooks = {(k, b, label) for k, b in identities for label in DELAYS}
    expected_hooks |= {(k, b, 'pending') for k, b in identities if CASES[k][0] in ('control', 'cancelled')}
    require(set(hooks) == expected_hooks and len(hooks) == 448, 'missing/extra callback or pending removal work')
    actions, seals = (indexed(name, ('key',)) for name in ('ACTION', 'SEAL'))
    require(set(actions) == set(seals) == {(k,) for k in CASES}, 'missing action or seal')
    requests, unloads, commands = (indexed(name, ('key', 'backend')) for name in ('REQUEST', 'UNLOAD', 'COMMAND'))
    require(set(requests) == set(unloads) == {(k, b) for k, b in identities if CASES[k][0] in ('chunk', 'suspended')},
            'missing/extra actual unload')
    require(set(commands) == {(k, b) for k, b in identities if CASES[k][0] in ('command', 'suspended')},
            'missing/extra actual command')
    deaths = indexed('DEATH', ('uuid',))
    require(set(deaths) == {(r['uuid'],) for (k, b), (_, r) in ids.items() if CASES[k][0] in ('death', 'cancelled')},
            'missing/extra actual death')
    previous_seal = -1
    for key, (scenario, kind, path) in CASES.items():
        action_at, action = actions[(key,)]
        seal_at, seal = seals[(key,)]
        action_tick = int(action['tick'])
        live = scenario in ('control', 'cancelled')
        require(action['scenario'] == scenario and seal['accepted'] == 'true', 'wrong action or failed world seal')
        require(len({ids[(key, b)][1]['tick'] for b in BACKENDS}) == 1, 'backend spawn ticks differ')
        for backend in BACKENDS:
            identity = key, backend
            parent_at, parent = ids[identity]
            seed_at, seed = seeds[identity]
            spawn = int(parent['tick'])
            deadline = int(seed['deadline'])
            require(str(uuid.UUID(parent['uuid'])) == parent['uuid'] and
                    (backend != 'native' or parent['instance'] == '0'), 'invalid parent UUID/instance')
            require(seed['uuid'] == parent['uuid'] and 2900 <= deadline - int(seed['millis']) <= 3000,
                    'wrong seeded identity or Pre deadline')
            initial_at, initial = rows[(key, backend, 'initial')]
            settled_at, settled = rows[(key, backend, 'settled')]
            clean_at, cleaned = rows[(key, backend, 'cleaned')]
            require(previous_seal < seed_at < parent_at < initial_at < action_at < settled_at < clean_at < seal_at,
                    'wrong lifecycle ordering')
            require(int(seed['millis']) <= int(initial['millis']) < deadline, 'Pre expired before initial observation')
            for phase, present, tick in (('initial', True, spawn), ('settled', live, action_tick + 210),
                                         ('cleaned', False, action_tick + 230)):
                _, row = rows[(key, backend, phase)]
                require(row['uuid'] == parent['uuid'] and int(row['tick']) == tick, 'wrong observation identity/timing')
                require(row['entity'] == row['registry'] == flag(present) and
                        row['marker'] == ('73' if phase == 'initial' else 'absent'), 'parent lost, resurrected or stale marker')
                require(row['visuals'] == str(11 if present and backend == 'runtime' else 0), 'missing/orphan visuals')
                expected_variables = (2 if phase == 'initial' else 1) if present and backend == 'runtime' else 0
                require(row['liveVariables'] == str(expected_variables) and all(row[n] == '0' for n in
                        ('terminalExecutions', 'ownerRecords', 'minionRecords', 'liveLocations')), 'live state or cleanup leaked')
            if scenario in ('chunk', 'suspended'):
                request_at, request = requests[identity]
                unload_at, unload = unloads[identity]
                require(request['accepted'] == 'true' and int(request['tick']) == spawn + 25
                        and initial_at < request_at < unload_at < action_at and unload['uuid'] == parent['uuid'],
                        'wrong unload stimulus/identity')
                require(spawn + 25 <= int(unload['tick']) < action_tick < spawn + 145
                        and action_tick == max(int(unloads[(key, b)][1]['tick']) for b in BACKENDS) + 1,
                        'unload outside request window')
            else:
                require(action_tick == spawn + 25, 'wrong action timing')
            if identity in commands:
                command_at, command = commands[identity]
                require(action_at < command_at < settled_at and int(command['tick']) == action_tick
                        and command['result'] == 'true' and command['loaded'] == flag(scenario == 'command'),
                        'wrong actual loaded/unloaded command')
            if scenario in ('death', 'cancelled'):
                death_at, death = deaths[(parent['uuid'],)]
                require(action_at < death_at < settled_at and death['key'] == key
                        and int(death['tick']) == action_tick and death['cancelled'] == flag(scenario == 'cancelled')
                        and death['reviveHealth'] == '20.0', 'wrong actual death/cancellation/revive health')

            hs = {label: hooks[(key, backend, label)] for label in DELAYS}
            if live:
                hs['pending'] = hooks[(key, backend, 'pending')]
            start_at, start = hs['start']
            start_tick = int(start['tick'])
            if scenario == 'control':
                require(start_tick == spawn + 10 and start_at < action_at, 'wrong control start')
            else:
                event_tick = action_tick
                if scenario == 'chunk':
                    _, unload = unloads[identity]
                    event_tick = int(unload['tick'])
                require(event_tick <= start_tick <= event_tick + (2 if backend == 'native' else 0), 'callback outside event window')
                # A synchronous runtime callback may precede the MONITOR unload/death log.
                require(initial_at < start_at and (scenario == 'chunk' or action_at < start_at), 'callback before stimulus')
            previous_hook, previous_time = initial_at, int(initial['millis'])
            for label, (at, row) in hs.items():
                context = f'{key}/{backend}/{label}'
                require(row['uuid'] == parent['uuid'] and row['primary'] == 'true' and row['cleaning'] == 'false',
                        'wrong callback context: ' + context)
                require(previous_hook < at < settled_at and int(row['millis']) >= previous_time, 'callback order: ' + context)
                previous_hook, previous_time = at, int(row['millis'])
                expected_tick = spawn + 180 if label == 'pending' else start_tick + DELAYS[label]
                tolerance = 1 if backend == 'native' and label not in ('start', 'pending') else 0
                require(abs(int(row['tick']) - expected_tick) <= tolerance, 'wrong callback delay: ' + context)
                expected = dict.fromkeys(NAMES + ('Flow',), 'absent')
                expected['Marker'] = '73'
                early = label in ('start', 'seeded', 'mutated', 'targetadd')
                require((int(row['millis']) < deadline) == early, 'Pre deadline not crossed at expected phase: ' + context)
                expected['Pre'] = '17' if early else 'absent'
                if label != 'start':
                    expected.update(Counter='1', Short='11', Keep='21', Refresh='31', Reset='41', Unset='51', Scratch='61', Flow='5')
                if label not in ('start', 'seeded'):
                    expected.update(Counter='3', Keep='23', Refresh='33', Reset='43', Unset='absent', Flow='8')
                if not early:
                    expected.update(Short='absent', Keep='absent')
                if label in ('finish', 'targetset', 'targetunset', 'pending'):
                    expected.update(Counter='absent', Refresh='absent', Scratch='absent')
                if live and label not in ('start', 'seeded', 'mutated'):
                    expected['Marker'] = '80' if label in ('targetadd', 'expired', 'afteradd', 'finish') else '91'
                    if label in ('targetunset', 'pending'):
                        expected['Marker'] = 'absent'
                if label == 'pending' and scenario == 'cancelled':
                    expected['Flow'] = 'absent'  # Separate onSpawn and onDeath skill contexts.
                for name, value in expected.items():
                    require(row[name] == value, f'{context}: {name} expected {value}, got {row[name]}')
            seeded_ms = int(hs['seeded'][1]['millis'])
            mutated_ms = int(hs['mutated'][1]['millis'])
            target_ms = int(hs['targetadd'][1]['millis'])
            expired_ms = int(hs['expired'][1]['millis'])
            after_ms = int(hs['afteradd'][1]['millis'])
            finish_ms = int(hs['finish'][1]['millis'])
            # Hooks run just after the mutations (native console dispatch can lag a tick).
            # Require margins around the 40-tick (2s) and refreshed 100-tick (5s) TTLs.
            require(target_ms - seeded_ms < 1900 and expired_ms - seeded_ms >= 2000
                    and after_ms - mutated_ms < 4900 and finish_ms - mutated_ms >= 5000,
                    'unproven duration/refresh expiry window: ' + key + '/' + backend)
        previous_seal = seal_at
    summary = events['SUMMARY']
    require(len(summary) == 1 and summary[0][1] == {'rows': '144', 'cases': '24', 'completed': 'true'}
            and previous_seal < summary[0][0] == max(at for values in events.values() for at, _ in values), 'incomplete summary')
    stops = [i for i, line in enumerate(lines) if ']: Stopping server' in line]
    require(len(stops) == 1 and stops[0] > summary[0][0], 'missing/premature shutdown')
    return len(rows)


def read_evidence(path):
    """Read and hash-check without promotion; validate() still rejects every observer."""
    root = Path(path)
    archived = root.is_file()
    report = js((root if archived else root / 'terminal-variable-report.json').read_text())
    manifest(report)
    if archived:
        name = safe_path(report['logArchive'])
        require(PurePosixPath(name).name == name and name.endswith('.zip'), 'unsafe archive')
        payloads = archive_payloads(report, (root.parent / name).read_bytes())
    else:
        require(root.name == report['fixtureDirectory'], 'fixture directory differs')
        for name, field in (('paper.jar', 'paperJarSha256'), ('plugins/helsteraMobs.jar', 'pluginSha256'),
                            ('plugins/MythicMobs.jar', 'mythicMobsSha256'),
                            ('plugins/HelsteraTerminalVariableProbe.jar', 'probeJarSha256')):
            require(digest((root / name).read_bytes()) == report[field], 'artifact changed: ' + name)
        payloads = {name: (root / name).read_bytes() for name in manifest(report)}
        for directory in ('imports', 'snapshots'):
            actual = {str(p.relative_to(root)) for p in (root / directory).rglob('*') if p.is_file()}
            require(actual == {n for n in FILES if n.startswith(directory + '/')}, 'orphan evidence directory')
        verify_payloads(report, payloads)
    return report, payloads


def main(path):
    report, payloads = read_evidence(path)
    count = validate(report, payloads)
    print(f'Validated {count} observations, 48 identities, 448 hooks; mode={report["evidenceMode"]}.')
    return report, payloads


if __name__ == '__main__':
    main(sys.argv[1])
