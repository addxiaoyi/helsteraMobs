"""Audit archived Paper terminal conditions and location selections as observations."""
import argparse
import collections
from datetime import datetime
import importlib.util
import itertools
import json
from pathlib import Path
import re
import sys
import uuid

sys.dont_write_bytecode = True


def module(name, filename):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(filename))
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


boundary = module('terminal_boundary_audit', 'audit-terminal-boundary.py')
cases = module('terminal_condition_location_cases', 'terminal-condition-location-cases.py')
require, sha = boundary.require, boundary.sha
BACKENDS = ('native', 'runtime')
PHASES = ('initial', 'settled', 'cleaned')
ROLES = ('Caster', 'Target')
LIVE = {'control', 'cancelled'}
GRACE = {'death', 'selfdeath', 'peerdeath', 'chunk'}
UNLOAD = {'chunk', 'suspended'}
COMMAND = {'command', 'suspended'}
SOURCES = {'tools/paper-integration/' + name for name in (
    'run-terminal-read-fixture.py', 'run-player-variable-fixture.py', 'terminal-read-cases.py',
    'terminal-condition-location-cases.py',
    'audit-terminal-condition-location.py', 'test-terminal-condition-location-audit.py',
    'audit-terminal-boundary.py',
    'random-spawn-fixture/src/main/java/dev/helstera/test/randomspawn/TerminalReadProbe.java',
)}
EVENTS = dict(
    ID='key backend uuid instance tick', SEED='key backend uuid deadline millis',
    ROW='key backend phase uuid entity registry marker visuals terminalExecutions ownerRecords minionRecords '
        'liveVariables liveLocations tick millis',
    POINTS='key backend phase uuid role offset world x y z material parentChunk',
    ACTION='key scenario tick', REQUEST='key backend accepted tick', UNLOAD='key backend uuid tick',
    COMMAND='key backend result loaded tick', DEATH='key uuid cancelled reviveHealth tick',
    ATTACKER='key backend uuid tick', SEAL='key accepted', SUMMARY='rows cases completed',
    HOOK='key backend label uuid reads registry liveVariables terminalExecutions cleaning tick millis primary',
)
CONDITION_FIELDS = {'Conditions': 'require', 'TargetConditions': 'target-conditions',
                    'TriggerConditions': 'trigger-conditions'}
IMPORTED_GATES = {
    'caster': 'variable-is-set{var=Marker;scope=caster}',
    'target': 'variable-is-set{var=Marker;scope=target}',
    'selected': 'variable-equals{var=Marker;scope=target;value=73}',
    'trigger': 'variable-in-range{var=Marker;scope=target;value=29to73}',
    'pre': 'variable-is-set{var=Pre;scope=target}',
    'point': 'variable-is-set{var=TargetPoint2;scope=target}',
}


def boolean(value):
    return str(value).lower()


def expected_gates(scenario, backend, offset):
    attached = scenario in LIVE or offset == 2 and scenario in GRACE
    gates = dict(caster=True, target=attached, pre=attached and offset != 70, point=attached)
    if scenario in LIVE:
        gates['selected'] = True
    # No trigger entity means neither branch dispatches. MythicMobs also
    # dispatches neither branch when VariableInRange throws without a registry.
    if scenario == 'peerdeath' or attached and scenario in {'selfdeath', 'chunk'}:
        gates['trigger'] = True
    elif backend == 'runtime' and scenario in {'selfdeath', 'chunk', *COMMAND}:
        gates['trigger'] = False
    return {f'{gate}{offset}{boolean(value)}' for gate, value in gates.items()}


def missing(role, suffix):
    return f'<{role}.var.{suffix}|absent>' if suffix != 'Raw' else f'<{role}.var.Marker>'


def expected_reads(scenario, identifier, attacker, attached, before_deadline):
    reads = dict(casterUuid=identifier, targetUuid=identifier, triggerUuid=attacker)
    for role, available in (
            ('caster', True), ('target', attached),
            ('trigger', scenario == 'peerdeath' or attached and scenario in {'selfdeath', 'chunk'})):
        marker = '29' if role == 'trigger' and scenario == 'peerdeath' else '73'
        for suffix, value in (
                ('Marker', marker), ('Raw', marker), ('Counter', 'absent'), ('Missing', 'absent'),
                ('Pre', '17' if before_deadline and marker == '73' else 'absent')):
            reads[role + suffix] = value if available else missing(role, suffix)
    return reads


def audit_inputs(payloads):
    import yaml
    source_mobs = yaml.safe_load(payloads['source-mobs.yml'])
    source_skills = yaml.safe_load(payloads['source-skills.yml'])
    runtime = yaml.safe_load(payloads['imports/skills.yml'])['skills']
    require(source_mobs == cases.native_mobs() and source_skills == cases.native_skills(),
            'unexpected native stimulus')
    require(set(runtime) == set(source_skills), 'missing/orphan runtime skill')
    for name, source in source_skills.items():
        observed = runtime[name]
        direct = name.endswith('_direct')
        decisions = [value.replace('hook native', 'hook runtime') for value in source['Skills']]
        if not direct:
            decisions = ['command ' + value.removeprefix('command{c=').removesuffix('} @self') + ' @self'
                         if value.startswith('command{c=') else
                         value.replace('setblock{material=GOLD_BLOCK}', 'set-block GOLD_BLOCK') for value in decisions]
        require(observed['on-decision'] == decisions, 'unexpected runtime action: ' + name)
        for mythic, key in CONDITION_FIELDS.items():
            if mythic not in source:
                require(key not in observed, 'unexpected runtime condition: ' + name)
                continue
            expression, value = source[mythic][0].rsplit(' ', 1)
            if direct:
                condition, arguments = expression.split('{', 1)
                require(arguments.endswith('}'), 'malformed direct condition: ' + name)
                runtime_name = {
                    'variableisset': 'variable-is-set',
                    'variableequals': 'variable-equals',
                    'variableinrange': 'variable-in-range',
                }.get(condition)
                require(runtime_name is not None, 'unexpected direct condition: ' + name)
                expression = runtime_name + '{' + arguments[:-1] + '}'
            gate = re.match(r'tr_([a-z]+)\d', name)[1]
            require(observed[key] == [(
                '' if value == 'true' else '!') + (expression if direct else IMPORTED_GATES[gate])],
                'unexpected runtime gate: ' + name)
    for key, scenario in ((key, value[0]) for key, value in cases.CASES.items()):
        mob = yaml.safe_load(payloads['imports/tr_' + key + '.yml'])
        direct = key.endswith('_direct')
        require(mob['model'] == 'example/emberling'
                and (mob['entity']['type'] if direct else mob['mob']['entity-type']) == 'COW',
                'unexpected runtime mob: ' + key)
        triggers = mob['ai']['triggers'] if direct else mob['mob']['triggers']
        event = 'spawn' if scenario == 'control' else 'death' if scenario in GRACE - {'chunk'} | {'cancelled'} else 'despawn'
        commands = triggers['on-' + event]['do']
        path = key.rsplit('_', 1)[1]
        require(sum(bool(re.search(r'\btr_terminal_' + path + r'\b', command)) for command in commands) == 1,
                'missing runtime trigger: ' + key)


def audit(report, payloads, plugin_jar=None):
    require(report['schemaVersion'] == 1 and report['evidenceMode'] == 'observation'
            and report['parityStatus'] == 'unassessed' and report['knownExceptions'] == []
            and report['gapCases'] == [], 'expected observation-only report')
    require(re.fullmatch(r'srv-paper-terminal-condition-location-\d{8}-r[1-9]\d*',
                         report['fixtureDirectory']), 'wrong fixture name')
    require(report['paperJarSha256'] == boundary.PAPER and report['mythicMobsSha256'] == boundary.MYTHIC,
            'wrong pinned native environment')
    require(set(report['sourceHashes']) == SOURCES, 'source manifest mismatch')
    for name, expected in report['sourceHashes'].items():
        source = boundary.ROOT / name
        require(source.is_file() and sha(source.read_bytes()) == expected, 'source changed: ' + name)
    require(all(re.fullmatch(r'[a-f0-9]{64}', value) for value in [
        report['pluginSha256'], report['probeJarSha256'], *report['sourceHashes'].values()]), 'invalid artifact digest')
    plugin = Path(plugin_jar) if plugin_jar is not None else boundary.ROOT / 'helstera-plugin/target/helsteraMobs-1.0.0-full.jar'
    require(plugin.is_file() and sha(plugin.read_bytes()) == report['pluginSha256'], 'wrong production artifact')
    require(report['boot']['exitCode'] == 0 and 'validationResult' not in report, 'unclean fixture boot')
    snapshots = {'snapshots/final-scoped.yml', 'snapshots/scoped-variables.yml'}
    expected_files = {'server.log', 'source-mobs.yml', 'source-skills.yml', 'imports/skills.yml'} | snapshots | {
        'imports/tr_' + key + '.yml' for key in cases.CASES}
    require(set(payloads) == expected_files, 'missing/orphan archive member')
    require(all(payloads[name] == b'registry-format: 1\nregistries: {}\n' for name in snapshots),
            'orphan final variable registry')
    audit_inputs(payloads)
    generated, started, stopped = (datetime.fromisoformat(value) for value in (
        report['generatedAtUtc'], report['boot']['startedAtUtc'], report['boot']['stoppedAtUtc']))
    require(all(value.utcoffset().total_seconds() == 0 for value in (generated, started, stopped))
            and generated <= started < stopped, 'invalid boot time')

    lines = payloads['server.log'].decode().splitlines()
    ready = [at for at, line in enumerate(lines) if re.search(r'Done \([0-9.]+s\)! For help,', line)]
    require(len(ready) == 1 and sum('Starting minecraft server version 1.21.1' in l for l in lines) == 1
            and not any('TREAD_FAILED|' in line for line in lines), 'unclean fixture log')
    require(report['observations'] == [line for line in lines if 'TREAD_ROW|' in line or 'TREAD_POINTS|' in line],
            'report observations disagree with archive')
    events = collections.defaultdict(list)
    last_tick, last_millis = 0, 0
    for at, line in enumerate(lines):
        match = re.search(r'TREAD_([A-Z]+)\|', line)
        if not match:
            continue
        name = match[1]
        require(at > ready[0] and name in EVENTS and line.count('TREAD_') == 1, 'unknown or misplaced probe event')
        row = boundary.parse_event(line, name)
        require(set(row) == set(EVENTS[name].split()), 'wrong fields for ' + name)
        for field in ('tick', 'millis', 'deadline', 'instance', 'liveVariables', 'terminalExecutions'):
            if field in row:
                require(re.fullmatch(r'0|[1-9][0-9]*', row[field]), 'invalid ' + field)
        tick, millis = int(row.get('tick', last_tick)), int(row.get('millis', last_millis))
        require(tick >= last_tick and millis >= last_millis, 'probe clock reversed')
        if 'millis' in row:
            require(started.timestamp() * 1000 <= millis <= stopped.timestamp() * 1000, 'probe outside recorded boot')
        last_tick, last_millis = tick, millis
        events[name].append((at, row))

    def rows(kind, fields, expected):
        values = {tuple(event[field] for field in fields): (at, event) for at, event in events[kind]}
        require(len(values) == len(events[kind]) and set(values) == set(expected),
                'missing/duplicate/unexpected ' + kind + ' event')
        return values

    pairs = set(itertools.product(cases.CASES, BACKENDS))
    identities = rows('ID', ('key', 'backend'), pairs)
    seeds = rows('SEED', ('key', 'backend'), pairs)
    parent_uuids = {event['uuid'] for _, event in identities.values()}
    require(len(parent_uuids) == len(identities), 'duplicate entity identity')
    actions = rows('ACTION', ('key',), ((key,) for key in cases.CASES))
    seals = rows('SEAL', ('key',), actions)
    life = rows('ROW', ('key', 'backend', 'phase'), itertools.product(cases.CASES, BACKENDS, PHASES))
    points = rows('POINTS', ('key', 'backend', 'phase', 'role', 'offset'),
                  itertools.product(cases.CASES, BACKENDS, PHASES, ROLES, map(str, cases.TICKS)))
    summary_at, summary = rows('SUMMARY', (), [()])[()]
    require(summary == {'rows': str(len(life)), 'cases': str(len(cases.CASES)), 'completed': 'true'}
            and summary_at > max(at for at, _ in seals.values())
            and any(']: Stopping server' in line for line in lines[summary_at:]), 'missing/invalid completion')

    def pairs_for(scenarios):
        return {pair for pair in pairs if cases.CASES[pair[0]][0] in scenarios}

    attackers = rows('ATTACKER', ('key', 'backend'), pairs_for({'selfdeath', 'peerdeath'}))
    requests = rows('REQUEST', ('key', 'backend'), pairs_for(UNLOAD))
    unloads = rows('UNLOAD', ('key', 'backend'), pairs_for(UNLOAD))
    commands = rows('COMMAND', ('key', 'backend'), pairs_for(COMMAND))
    deaths = rows('DEATH', ('key', 'uuid'), {
        (key, identities[key, backend][1]['uuid']) for key, backend in pairs_for(
            GRACE - {'chunk'} | {'cancelled'})})
    hooks = rows('HOOK', ('key', 'backend', 'label'), {
        (key, backend, label) for key, backend in pairs for offset in cases.TICKS
        for label in {f'check{offset}'} | expected_gates(cases.CASES[key][0], backend, offset)})
    native_location_errors, native_range_errors = 0, 0
    peer_uuids = set()
    for key, backend in sorted(pairs):
        scenario = cases.CASES[key][0]
        id_at, identity = identities[key, backend]
        seed_at, seed = seeds[key, backend]
        action_at, action = actions[key,]
        seal_at, seal = seals[key,]
        initial_at, initial = life[key, backend, 'initial']
        settled_at, _ = life[key, backend, 'settled']
        identifier = identity['uuid']
        spawn_tick, action_tick = int(identity['tick']), int(action['tick'])
        start, deadline = int(seed['millis']), int(seed['deadline'])
        require(str(uuid.UUID(identifier)) == seed['uuid'] == identifier and seed_at < id_at < initial_at < action_at
                and int(initial['millis']) >= start and 2980 <= deadline - start <= 3000, 'invalid registry seed')
        require(action['scenario'] == scenario and seal['accepted'] == 'true', 'wrong action or world cleanup')
        if (key, backend) in unloads:
            request_at, request = requests[key, backend]
            unload_at, unload = unloads[key, backend]
            require(initial_at < request_at < unload_at < action_at and request['accepted'] == 'true'
                    and int(request['tick']) == spawn_tick + 25 and unload['uuid'] == identifier
                    and int(request['tick']) <= int(unload['tick']) < action_tick, 'wrong unload context')
        else:
            require(action_tick == spawn_tick + 25, 'wrong action tick')
        if (key, backend) in commands:
            command_at, command = commands[key, backend]
            require(action_at < command_at < settled_at and int(command['tick']) == action_tick
                    and command['result'] == 'true' and command['loaded'] == boolean(scenario == 'command'),
                    'wrong command context')
        if (key, identifier) in deaths:
            death_at, death = deaths[key, identifier]
            require(action_at < death_at < settled_at and int(death['tick']) == action_tick
                    and death['cancelled'] == boolean(scenario == 'cancelled') and death['reviveHealth'] == '20.0',
                    'wrong death context')

        attacker = identifier if scenario in {'chunk', *COMMAND} else '<trigger.uuid>'
        if (key, backend) in attackers:
            at, attack = attackers[key, backend]
            attacker = attack['uuid']
            require(action_at < at < deaths[key, identifier][0] and int(attack['tick']) == action_tick
                    and str(uuid.UUID(attacker)) == attacker and (attacker == identifier) == (scenario == 'selfdeath'),
                    'wrong attacker UUID')
            if scenario == 'peerdeath':
                require(attacker not in peer_uuids | parent_uuids, 'duplicate peer UUID')
                peer_uuids.add(attacker)

        for phase in PHASES:
            at, row = life[key, backend, phase]
            live = phase == 'initial' or phase == 'settled' and scenario in LIVE
            expected_tick = {'initial': spawn_tick, 'settled': action_tick + 115, 'cleaned': action_tick + 135}[phase]
            require(id_at < at < seal_at and int(row['tick']) == expected_tick
                    and row['uuid'] == identifier and row['entity'] == row['registry'] == boolean(live)
                    and row['marker'] == ('73' if live else 'absent'), f'{key}/{backend}/{phase}: lifecycle changed')
            require(row['liveLocations'] == ('6' if backend == 'runtime' and live else '0')
                    and row['liveVariables'] == (('2' if phase == 'initial' else '1') if backend == 'runtime' and live else '0'),
                    'live variable registry changed')
            require(all(row[field] == '0' for field in ('terminalExecutions', 'ownerRecords', 'minionRecords'))
                    and row['visuals'] == ('11' if backend == 'runtime' and (
                        live or scenario == 'peerdeath' and phase == 'settled') else '0'), 'terminal state leaked')
            for role, offset in itertools.product(ROLES, cases.TICKS):
                point_at, dest = points[key, backend, phase, role, str(offset)]
                x = (256 if backend == 'native' else 512) + 4
                expected_x = x * 16 + 2 + cases.TICKS.index(offset) * 2
                require(id_at < point_at < at and dest['uuid'] == identifier and dest['world'] == 'terminal-read-' + key
                        and int(dest['x']) == expected_x and int(dest['y']) == (112 if role == 'Caster' else 114)
                        and int(dest['z']) == 256 * 16 + 10
                        and dest['parentChunk'] == boolean(phase != 'settled' or scenario not in UNLOAD),
                        'wrong location destination or chunk state')
                expected_gold = phase == 'settled' and (role == 'Caster' or scenario in LIVE
                                                        or offset == 2 and scenario in GRACE)
                require(dest['material'] == ('GOLD_BLOCK' if expected_gold else 'AIR'),
                        f'{key}/{backend}/{phase}/{role}/{offset}: wrong location material')

        for index, offset in enumerate(cases.TICKS):
            check_at, check = hooks[key, backend, f'check{offset}']
            stop_at = hooks[key, backend, f'check{cases.TICKS[index + 1]}'][0] if index + 1 < len(cases.TICKS) else settled_at
            start_tick = (spawn_tick + 10 if scenario == 'control' else
                          int(unloads[key, backend][1]['tick']) if scenario == 'chunk' and backend == 'runtime' else action_tick)
            before_deadline = offset != 70
            attached = scenario in LIVE or offset == 2 and scenario in GRACE
            native_registry = backend == 'native' and attached
            runtime_registry = backend == 'runtime' and scenario in LIVE
            require((int(check['millis']) < deadline) == before_deadline, 'unproven expiry crossing')
            for label in {f'check{offset}'} | expected_gates(scenario, backend, offset):
                at, row = hooks[key, backend, label]
                require(initial_at < check_at <= at < stop_at and int(row['tick']) == start_tick + offset
                        and row['uuid'] == identifier and row['primary'] == 'true' and row['cleaning'] == 'false',
                        f'{key}/{backend}/{label}: wrong callback identity/timing')
                require(row['registry'] == boolean(native_registry or runtime_registry)
                        and row['liveVariables'] == (('2' if before_deadline else '1') if runtime_registry else '0'),
                        f'{key}/{backend}/{label}: unexpected live registry')
                require((int(row['millis']) < deadline) == before_deadline, 'unproven gate expiry crossing')
                values = boundary.decode_hook(row)
                stamp = values.pop('stamp', '')
                require(stamp == (f'{row["tick"]},{row["registry"]}' if backend == 'native' else '<trstamp>'),
                        'wrong callback stamp')
                require(values == expected_reads(scenario, identifier, attacker, attached, before_deadline),
                        f'{key}/{backend}/{label}: wrong caster, target or trigger reads')
            if backend == 'native':
                diagnostic = '\n'.join(lines[check_at:stop_at])
                path = key.rsplit('_', 1)[1]
                location_error = f"Couldn't execute skill 'tr_targetpoint{offset}_{path}'"
                require((location_error in diagnostic) == (not attached), 'wrong native location diagnostic')
                require(('Failed to process VariableIsSet condition.' in diagnostic) == (not attached),
                        'wrong native variable-is-set diagnostic')
                range_error = (not attached and scenario in {'selfdeath', 'chunk', *COMMAND})
                require(('VariableInRangeCondition.check(' in diagnostic) == range_error, 'wrong native range diagnostic')
                native_location_errors += not attached
                native_range_errors += range_error

    return dict(evidenceMode='observation', parityStatus='unassessed', cases=len(cases.CASES),
                entities=len(identities), lifecycleRows=len(life), locationRows=len(points), callbacks=len(hooks),
                nativeLocationErrorPhases=native_location_errors, nativeRangeErrorPhases=native_range_errors)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('report')
    parser.add_argument('--plugin-jar', type=Path, help='verify the observed production artifact')
    args = parser.parse_args()
    report, payloads = boundary.read_report(args.report)
    print(json.dumps(audit(report, payloads, args.plugin_jar), indent=2))
