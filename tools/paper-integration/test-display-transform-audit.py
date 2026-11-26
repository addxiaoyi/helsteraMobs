"""Require rejection of semantic tampering after recomputing file hashes."""
from pathlib import Path
from functools import lru_cache
import copy
import importlib.util
import json
import sys
import yaml

yaml.safe_load = lru_cache(maxsize=2048)(yaml.safe_load)
json.loads = lru_cache(maxsize=32768)(json.loads)
spec = importlib.util.spec_from_file_location('audit', Path(__file__).with_name('audit-display-transform.py'))
a = importlib.util.module_from_spec(spec); spec.loader.exec_module(a)
report, files = a.read_report(Path(sys.argv[1])); a.audit(report, files)
rejected = 0


def reject(name, data):
    global rejected
    changed = dict(files); changed[name] = data; updated = copy.deepcopy(report)
    for entry in updated['files']:
        if entry['path'] == name:
            entry['sha256'] = a.digest(data)
    try:
        a.audit(updated, changed)
    except (ValueError, KeyError, TypeError):
        rejected += 1
    else:
        raise AssertionError('accepted altered evidence '+name)


for name in sorted(files):
    if '/observations/' not in name:
        continue
    row = yaml.safe_load(files[name])
    for label in ('before', 'after', 'settled'):
        for entity in ('display', 'control'):
            for field in ('translation', 'scale', 'left_rotation', 'right_rotation', 'delay', 'duration', 'teleport'):
                changed = copy.deepcopy(row)
                value = changed[label][entity][field]
                if isinstance(value, list):
                    value[0] += 1
                else:
                    changed[label][entity][field] += 1
                reject(name, yaml.safe_dump(changed).encode())
    changed = copy.deepcopy(row); changed['endTick'] += 1; reject(name, yaml.safe_dump(changed).encode())

events = [json.loads(line[len('DISPLAY_TRANSFORM_CLIENT '):]) for line in files['client.log'].decode().splitlines()
          if line.startswith('DISPLAY_TRANSFORM_CLIENT ')]


def reject_events(changed):
    reject('client.log', ''.join('DISPLAY_TRANSFORM_CLIENT '+json.dumps(event)+'\n' for event in changed).encode())


for index, event in enumerate(events):
    if event.get('index') not in (1, 31, 40):
        continue
    if event['event'] == 'packet' and event['packetType'] == 'entity_metadata':
        for item_index, item in enumerate(event['data']['metadata']):
            if item['key'] not in a.METADATA.values():
                continue
            changed = copy.deepcopy(event); value = changed['data']['metadata'][item_index]['value']
            if isinstance(value, dict):
                value['x'] += 1
            elif isinstance(value, list):
                value[0] += 1
            else:
                changed['data']['metadata'][item_index]['value'] += 1
            altered = list(events); altered[index] = changed; reject_events(altered)
for kind in ('summary', 'done', 'join', 'tick', 'target'):
    index = next(i for i, event in enumerate(events) if event['event'] == kind)
    altered = list(events); altered.pop(index); reject_events(altered)
print('rejected', rejected, 'rehashed semantic mutations')
