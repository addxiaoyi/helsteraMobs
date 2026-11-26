"""Rehash altered real observations and require semantic rejection."""
from pathlib import Path
from functools import lru_cache
import copy
import importlib.util
import json
import sys
import yaml

yaml.safe_load = lru_cache(maxsize=2048)(yaml.safe_load)
json.loads = lru_cache(maxsize=16384)(json.loads)
spec = importlib.util.spec_from_file_location('shield_audit', Path(__file__).with_name('audit-shield-break.py'))
a = importlib.util.module_from_spec(spec); spec.loader.exec_module(a)
report, files = a.read_report(Path(sys.argv[1])); a.audit(report, files)
rejected = 0


def reject(name, data):
    global rejected
    edited = dict(files); edited[name] = data; updated = copy.deepcopy(report)
    for entry in updated['files']:
        if entry['path'] == name:
            entry['sha256'] = a.digest(data)
    try:
        a.audit(updated, edited)
    except (ValueError, KeyError, TypeError):
        rejected += 1
    else:
        raise AssertionError('accepted altered evidence ' + name)


for name in sorted(files):
    if '/observations/' not in name:
        continue
    row = yaml.safe_load(files[name]); mutations = []
    for field in ('restoreTick', 'endTick'):
        changed = copy.deepcopy(row); changed[field] += 1; mutations.append(changed)
    for label in ('before', 'after', 'intermediate', 'restored', 'settled'):
        changed = copy.deepcopy(row); changed[label]['cooldown'] += 1; mutations.append(changed)
        changed = copy.deepcopy(row); changed[label]['blocking'] = not changed[label]['blocking']; mutations.append(changed)
        if row[label]['inventory']:
            for field in ('amount', 'model', 'damage'):
                changed = copy.deepcopy(row); changed[label]['inventory'][0][field] += 1; mutations.append(changed)
    changed = copy.deepcopy(row); changed['before']['blocking'] = not changed['before']['blocking']; mutations.append(changed)
    changed = copy.deepcopy(row); changed['caster'] = row['target']; mutations.append(changed)
    for label in ('restored', 'settled'):
        for index, drop in enumerate(row[label]['drops']):
            for key, value in (('amount', 42), ('owner', row['caster']), ('x', 0)):
                changed = copy.deepcopy(row); changed[label]['drops'][index][key] = value; mutations.append(changed)
    for changed in mutations:
        reject(name, yaml.safe_dump(changed).encode())

events = [json.loads(line[len('SHIELD_BREAK_CLIENT '):]) for line in files['client.log'].decode().splitlines()
          if line.startswith('SHIELD_BREAK_CLIENT ')]


def reject_events(changed):
    reject('client.log', ''.join('SHIELD_BREAK_CLIENT ' + json.dumps(event) + '\n' for event in changed).encode())


for index, event in enumerate(events):
    if event.get('index') not in (1, 4, 49):
        continue
    mutations = []
    if event['event'] == 'packet' and event['tick'] >= 0:
        if event['packetType'] == 'set_slot' and event['index'] in (1, 4) and event['data']['item'].get('itemId') == 1162:
            changed = copy.deepcopy(event); changed['data']['item']['itemCount'] += 1; mutations.append(changed)
        if event['packetType'] == 'set_cooldown':
            changed = copy.deepcopy(event); changed['data']['cooldownTicks'] += 7; mutations.append(changed)
        if event['packetType'] == 'sound_effect' and a.sound_name(event['data']) in ('minecraft:item.shield.block', 'minecraft:item.shield.break'):
            for field in ('volume', 'pitch', 'x'):
                changed = copy.deepcopy(event); changed['data'][field] += 1; mutations.append(changed)
            changed = copy.deepcopy(event); changed['role'] = 'observer'; mutations.append(changed)
    if event['event'] == 'input-packet' and event['packetType'] == 'use_item':
        changed = copy.deepcopy(event); changed['data']['hand'] = 1 - changed['data']['hand']; mutations.append(changed)
    if event['event'] == 'end' and event['inventory']:
        changed = copy.deepcopy(event); changed['inventory'][0]['count'] += 1; mutations.append(changed)
    for changed in mutations:
        altered = list(events); altered[index] = changed; reject_events(altered)
for kind in ('summary', 'done', 'join', 'tick', 'input-packet'):
    index = next(i for i, event in enumerate(events) if event['event'] == kind
                 and (kind != 'input-packet' or event['packetType'] == 'use_item'))
    altered = list(events); altered.pop(index); reject_events(altered)
print('rejected', rejected, 'rehashed semantic mutations')
