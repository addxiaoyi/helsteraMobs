"""Independent server-state and real-client audit of the bounded M18 fixture."""
from pathlib import Path
import argparse
import base64
import hashlib
import importlib.util
import json
import zipfile
import yaml

ROOT = Path(__file__).resolve().parents[2]
TOOLS = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('shield_cases', TOOLS / 'shield-break-cases.py')
c = importlib.util.module_from_spec(spec); spec.loader.exec_module(c)
PROBE = 'plugins/HelsteraShieldBreakProbe/'
ITEMS = {'SHIELD': 1162, 'EMERALD': 806, 'STONE': 1}


def require(value, message):
    if not value:
        raise ValueError(message)


def digest(value):
    return hashlib.sha256(value).hexdigest()


def inventory(entries):
    require(isinstance(entries, list), 'inventory list')
    result = {}
    for item in entries:
        require(set(item) == {'slot', 'type', 'amount', 'model', 'damage', 'data'}, 'inventory fields')
        slot = item['slot']
        require(type(slot) is int and (0 <= slot < 36 or slot == 40) and slot not in result, 'inventory slot')
        require(item['type'] in ITEMS and type(item['amount']) is int and 0 < item['amount'] <= 64, 'stack')
        require(type(item['model']) is int and type(item['damage']) is int, 'metadata types')
        require(len(base64.b64decode(item['data'], validate=True)) > 10, 'serialized metadata')
        result[slot] = (item['type'], item['amount'], item['model'], item['damage'])
    require(list(result) == sorted(result), 'inventory order')
    return result


def check_row(row, case, index, path, ids):
    fields = {'index', 'path', 'case', 'source', 'mapped', 'caster', 'target', 'before', 'startTick',
              'primary', 'result', 'after', 'intermediate', 'restored', 'restoreTick', 'settled', 'endTick'}
    if case['lifecycle'] == 'close' and path != 'native':
        fields.add('cleanup')
    require(set(row) == fields, 'row fields')
    source = case['mechanic'] + '{' + case['arguments'] + '} @trigger'
    mapped = source if path != 'imported' else 'disable-shield' + source[len(case['mechanic']):]
    require((row['index'], row['path'], row['case'], row['source'], row['mapped']) ==
            (index, path, case['key'], source, mapped), 'row identity/mapping')
    require(row['primary'] is True and row['caster'] == ids['caster']['uuid'], 'caster/thread')
    require(row['target'] == ids['cow' if case['target'] == 'cow' else 'receiver']['uuid'], 'recipient')
    require(type(row['startTick']) is int and row['restoreTick'] - row['startTick'] == 1
            and row['endTick'] - row['startTick'] == 10, 'one-tick restoration / observation span')
    expected_result = 'EXECUTED'
    if path == 'native':
        expected_result = ('SUCCESS' if case['success'] else 'IllegalArgumentException' if case['key'] == 'negative'
                           else 'INVALID_TARGET' if case['target'] == 'cow' else 'CONDITION_FAILED')
    require(row['result'] == expected_result, 'invocation result')
    seed = {}
    if case['hands'] in ('main', 'both'):
        seed[0] = ('SHIELD', 1, 4321, 7)
    if case['hands'] in ('off', 'both'):
        seed[40] = ('SHIELD', 1, 5432, 7)
    if case['replacement'] == 'full':
        seed.update({slot: ('STONE', 64, 0, 0) for slot in range(1, 36)})
    if case['replacement'] == 'hotbar':
        seed[1] = ('EMERALD', 3, 0, 0)
    chosen = 40 if case['hands'] == 'off' else 0
    after = dict(seed)
    if case['success']:
        del after[chosen]
    intermediate = dict(after)
    if case['replacement'] in ('space', 'full'):
        intermediate[0] = ('EMERALD', 3, 0, 0)
    restored = dict(seed)
    if case['replacement'] == 'hotbar':
        restored = {1: seed[0]}
        if path != 'native':
            restored[0] = ('EMERALD', 3, 0, 0)
    if case['replacement'] == 'space' and path != 'native':
        restored[1] = ('EMERALD', 3, 0, 0)
    if 'cleanup' in row:
        intermediate = restored
    duration = case['nativeTicks'] if path == 'native' else case['ticks']
    states = {'before': seed, 'after': after, 'intermediate': intermediate,
              'restored': restored, 'settled': restored}
    if 'cleanup' in row:
        states['cleanup'] = restored
    original_shields = {item['model']: item['data'] for item in row['before']['inventory'] if item['type'] == 'SHIELD'}
    for label, expected in states.items():
        state = row[label]
        require(set(state) == {'inventory', 'observer', 'blocking', 'using', 'cooldown', 'heldSlot', 'drops'}, 'state fields')
        require(inventory(state['inventory']) == expected, case['key'] + '/' + path + '/' + label + ' inventory')
        require(inventory(state['observer']) == {}, 'observer inventory')
        require(type(state['blocking']) is bool and type(state['using']) is bool, 'use state')
        using = case['use'] != 'none'
        if label in ('restored', 'settled') and case['success']:
            using = case['key'] == 'both-off' or ('cleanup' in row)
        require(state['blocking'] == using and state['using'] == using, 'blocking/use transition')
        tick = 1 if label == 'restored' else 10 if label == 'settled' else 0
        cooldown = max(0, duration - tick) if case['success'] and label != 'before' else 0
        require(type(state['cooldown']) is int and state['cooldown'] == cooldown,
                case['key'] + '/' + path + '/' + label + ' cooldown')
        held = 1 if case['replacement'] == 'hotbar' and label not in ('before', 'after') else 0
        require(state['heldSlot'] == held, 'held slot')
        for item in state['inventory']:
            if item['type'] == 'SHIELD':
                require(item['data'] == original_shields[item['model']], 'shield serialization conserved')
        drops = state['drops']
        require(len(drops) == (1 if case['replacement'] == 'full' and label in ('restored', 'settled') else 0), 'drop count')
        for drop in drops:
            require(set(drop) == {'type', 'amount', 'model', 'damage', 'data', 'entityId', 'owner', 'thrower', 'x', 'y', 'z'}, 'drop fields')
            require((drop['type'], drop['amount'], drop['model'], drop['damage']) == ('EMERALD', 3, 0, 0), 'displaced drop')
            require(type(drop['entityId']) is int and drop['owner'] == drop['thrower'] == 'null', 'native drop ownership')
            require(len(base64.b64decode(drop['data'], validate=True)) > 10, 'drop metadata')
            require(all(abs(drop[axis] - expected_position) < 2 for axis, expected_position in zip(('x', 'y', 'z'), (110, 170, 0))), 'drop position')
    require(row['restored']['inventory'] == row['settled']['inventory'] and row['restored']['drops'] == row['settled']['drops'], 'stable restored items')


def sound_name(data):
    sound = data['sound']
    if 'data' in sound:
        return sound['data']['soundName']
    return {1232: 'minecraft:item.shield.block', 1233: 'minecraft:item.shield.break'}.get(sound.get('soundId'))


def clients(events, rows, ids):
    require(not any(e['event'] in ('packet-error', 'world-reset') for e in events), 'client decode/world failure')
    summaries = [e for e in events if e['event'] == 'summary']
    require(len(summaries) == 1 and summaries[0]['passed'] is True and summaries[0]['error'] is None
            and summaries[0]['doneRoles'] == ['observer', 'receiver'], 'two-client completion')
    for role in ('receiver', 'observer'):
        joined = [e for e in events if e['event'] == 'join' and e.get('role') == role]
        require(len(joined) == 1 and joined[0]['uuid'] == ids[role]['uuid']
                and joined[0]['entityId'] == ids[role]['entityId'], 'client identity')
        require(len([e for e in events if e['event'] == 'done' and e.get('role') == role]) == 1, 'client done')
        require([e['index'] for e in events if e['event'] == 'phase' and e.get('role') == role] == list(range(len(rows))), 'ordered client phases')
    for row in rows:
        index = row['index']; case = c.CASES[index // 3]
        phase = [e for e in events if e.get('index') == index]
        for role in ('receiver', 'observer'):
            selected = [e for e in phase if e.get('role') == role]
            ends = [e for e in selected if e['event'] == 'end']
            require(len(ends) == 1, 'client end')
            require(all((e['path'], e['case']) == (row['path'], case['key']) for e in selected), 'client phase identity')
            require([e['tick'] for e in selected if e['event'] == 'tick'] == list(range(11)), 'client ticks')
            def component(item, name):
                return next((part['data'] for part in item.get('components', []) if part['type'] == name), 0)
            actual = sorted((item['slot'], item['name'].upper(), item['count'], component(item, 'custom_model_data'), component(item, 'damage'))
                            for item in ends[0]['inventory'])
            expected = sorted((item['slot'] + 36 if item['slot'] < 9 else 45 if item['slot'] == 40 else item['slot'],
                               item['type'], item['amount'], item['model'], item['damage'])
                              for item in row['settled']['inventory' if role == 'receiver' else 'observer'])
            require(actual == expected, f'client/server inventory agreement {index}/{role}')
            inputs = [e for e in selected if e['event'] == 'input']
            require(len(inputs) == (1 if role == 'receiver' else 0), 'input count')
            if inputs:
                require(inputs[0]['hand'] == case['use'], 'input hand')
            use_packets = [e for e in selected if e['event'] == 'input-packet' and e['packetType'] == 'use_item']
            require(len(use_packets) == (1 if role == 'receiver' and case['use'] != 'none' else 0), 'actual use-item packet')
            if use_packets:
                require(use_packets[0]['data']['hand'] == (1 if case['use'] == 'off' else 0), 'actual use-item hand')
            packets = [e for e in selected if e['event'] == 'packet']
            if role == 'receiver' and case['success'] and case['replacement'] == case['lifecycle'] == 'none':
                slot = 45 if case['hands'] == 'off' else 36
                changes = [e for e in packets if e['packetType'] == 'set_slot' and e['data']['windowId'] == 0
                           and e['data']['slot'] == slot and e['tick'] >= 0]
                removals = [i for i, e in enumerate(changes) if e['data']['item']['itemCount'] == 0 and e['tick'] == 0]
                require(removals, 'client temporary shield removal')
                returned = [e['data']['item'] for e in changes[removals[0] + 1:] if e['tick'] in (0, 1)
                            and e['data']['item'].get('itemId') == 1162]
                require(returned, 'client shield restoration')
                for item in returned:
                    require(item['itemCount'] == 1 and component(item, 'custom_model_data') == (5432 if slot == 45 else 4321)
                            and component(item, 'damage') == 7, 'client restored shield metadata')
            cooldowns = [e for e in packets if e['packetType'] == 'set_cooldown' and e['tick'] >= 0]
            if role == 'receiver' and case['success']:
                duration = case['nativeTicks'] if row['path'] == 'native' else case['ticks']
                require(any(e['data'] == {'itemID': 1162, 'cooldownTicks': duration} and e['tick'] in (0, 1) for e in cooldowns), 'cooldown packet')
                require(all(e['data']['itemID'] == 1162 and e['data']['cooldownTicks'] in (0, duration) for e in cooldowns), 'cooldown packet values')
            else:
                require(not cooldowns, 'unexpected private cooldown')
            sounds = [e for e in packets if e['packetType'] == 'sound_effect' and sound_name(e['data']) in
                      ('minecraft:item.shield.block', 'minecraft:item.shield.break')]
            expected_sounds = ['minecraft:item.shield.block', 'minecraft:item.shield.break'] if role == 'receiver' and case['success'] else []
            require([sound_name(e['data']) for e in sounds] == expected_sounds, 'private shield sounds')
            for event in sounds:
                data = event['data']
                require(data['volume'] == data['pitch'] == 1 and data['soundCategory'] == 'master'
                        and (data['x'], data['y'], data['z']) == (880, 1360, 0), 'sound position/volume/pitch/category')
            drop_ids = {d['entityId'] for d in row['settled']['drops']}
            require(set(ends[0]['phaseTrackedItemIds']) == drop_ids, 'client physical drops')
            for entity_id in drop_ids:
                metadata = [part['value'] for event in packets if event['packetType'] == 'entity_metadata'
                            and event['data']['entityId'] == entity_id for part in event['data']['metadata'] if part['key'] == 8]
                require(any(item['itemId'] == 806 and item['itemCount'] == 3 for item in metadata), 'client drop stack')


def audit(report, files):
    require(report['evidenceMode'] == 'observation' and report['parityStatus'] == 'unassessed', 'claim scope')
    require(report['exitCode'] == report['clientExitCode'] == 0, 'process completion')
    require(report['paperJarSha256'] == '39bd8c00b9e18de91dcabd3cc3dcfa5328685a53b7187a2f63280c22e2d287b9', 'pinned Paper')
    require(report['mythicMobsSha256'] == '9672457cafc56ff841606bfbb710947329c08720ffc207746b60130d43bf902f', 'pinned native')
    require(report['observationCount'] == len(c.CASES) * 3, 'observation count')
    require(set(files) == {f['path'] for f in report['files']}, 'file inventory')
    for entry in report['files']:
        require(digest(files[entry['path']]) == entry['sha256'], 'file hash')
    require(yaml.safe_load(files[PROBE + 'cases.yml']) == {'cases': c.CASES}, 'case definitions')
    ids = yaml.safe_load(files[PROBE + 'identities.yml']); rows = []
    paths = sorted(name for name in files if name.startswith(PROBE + 'observations/'))
    require(len(paths) == len(c.CASES) * 3, 'row files')
    for index, name in enumerate(paths):
        row = yaml.safe_load(files[name])
        check_row(row, c.CASES[index // 3], index, ('native', 'direct', 'imported')[index % 3], ids)
        rows.append(row)
    log = files['server.log'].decode()
    require('SHIELD_BREAK_FAILED|' not in log and f'SHIELD_BREAK_SUMMARY|observations={len(rows)}|completed=true' in log, 'server completion')
    events = [json.loads(line[len('SHIELD_BREAK_CLIENT '):]) for line in files['client.log'].decode().splitlines()
              if line.startswith('SHIELD_BREAK_CLIENT ')]
    clients(events, rows, ids)
    return rows


def read_report(path, check_current=False):
    report = json.loads(path.read_text()); archive = path.with_name(report['logArchive'])
    require(digest(archive.read_bytes()) == report['logArchiveSha256'], 'archive hash')
    with zipfile.ZipFile(archive) as packed:
        require(len(packed.namelist()) == len(set(packed.namelist())), 'duplicate archive member')
        files = {name: packed.read(name) for name in packed.namelist()}
    if check_current:
        require(digest((ROOT / 'helstera-plugin/target/helsteraMobs-1.0.0-full.jar').read_bytes()) == report['pluginSha256'], 'current artifact')
        for name, expected in report['sourceHashes'].items():
            require(digest((ROOT / name).read_bytes()) == expected, 'current source ' + name)
    return report, files


if __name__ == '__main__':
    parser = argparse.ArgumentParser(); parser.add_argument('report', type=Path); parser.add_argument('--check-current', action='store_true')
    args = parser.parse_args(); report, files = read_report(args.report, args.check_current)
    rows = audit(report, files); print('passed', len(rows), 'shield-break observations')
