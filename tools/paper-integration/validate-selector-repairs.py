"""Strict entity, lifecycle, direction and client evidence for scoped selector repairs."""
import hashlib
import importlib.util
import io
import json
import math
from pathlib import Path, PurePosixPath
import re
import sys
import uuid
import zipfile
import yaml

sys.dont_write_bytecode = True


def module(name, filename):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(filename))
    result = importlib.util.module_from_spec(spec); spec.loader.exec_module(result)
    return result


base = module('selector_base', 'validate-player-variables.py')
c = module('selector_contract', 'selector-repairs-cases.py')
require, fields = base.require, base.fields
FILES = {'server.log', 'client.log', 'source-profiles.yml'}
SOURCES = {'run-selector-repairs-fixture.py', 'run-player-variable-fixture.py', 'selector-repairs-cases.py',
           'validate-selector-repairs.py', 'validate-player-variables.py', 'test-selector-repairs-evidence.py',
           'selector-repairs-client.js',
           'random-spawn-fixture/src/main/java/dev/helstera/test/randomspawn/SelectorRepairsProbe.java'}


def digest(data): return hashlib.sha256(data).hexdigest()
def hashed(value): return isinstance(value, str) and re.fullmatch('[a-f0-9]{64}', value) is not None


def manifest(report):
    result = {}
    for item in report['files']:
        require(set(item) == {'path', 'sha256'} and item['path'] in FILES and item['path'] not in result
                and hashed(item['sha256']), 'bad/duplicate manifest entry')
        result[item['path']] = item['sha256']
    require(set(result) == FILES, 'incomplete manifest')
    return result


def verify_payloads(report, payloads):
    entries = manifest(report); require(set(payloads) == set(entries), 'payload members differ')
    for name, value in entries.items(): require(digest(payloads[name]) == value, 'payload changed: ' + name)


def archive_payloads(report, data):
    require(hashed(report['logArchiveSha256']) and digest(data) == report['logArchiveSha256'], 'archive changed')
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        names = archive.namelist()
        require(len(names) == len(set(names)) and set(names) == FILES, 'invalid archive membership')
        result = {name: archive.read(name) for name in names}
    verify_payloads(report, result)
    return result


def number(value):
    result = float(value); require(math.isfinite(result), 'nonfinite observation')
    return result


def validate(report, payloads):
    require(type(report['schemaVersion']) is int and report['schemaVersion'] == 1 and report['evidenceMode'] == 'scoped-runtime'
            and 'validationResult' not in report, 'wrong/rejected evidence schema')
    require(report['contract'] == 'helstera-selector-repairs' and report['nativeGoalParity'] is False, 'scope misrepresented')
    require(report['paperJarSha256'] == '39bd8c00b9e18de91dcabd3cc3dcfa5328685a53b7187a2f63280c22e2d287b9', 'wrong Paper')
    require(report['mythicMobsSha256'] == '9672457cafc56ff841606bfbb710947329c08720ffc207746b60130d43bf902f', 'wrong native artifact')
    require(hashed(report['pluginSha256']) and hashed(report['probeJarSha256']), 'wrong artifact identity')
    require(type(report['exitCode']) is int and report['exitCode'] == 0
            and type(report['clientExitCode']) is int and report['clientExitCode'] == 0, 'server/client failure')
    require(re.fullmatch('srv-paper-selector-repairs-[0-9]{8}-r[1-9][0-9]*', report['fixtureDirectory']) is not None, 'wrong fixture')
    require(set(report['sourceHashes']) == {'tools/paper-integration/' + name for name in SOURCES}
            and all(hashed(value) for value in report['sourceHashes'].values()), 'source identity incomplete')
    verify_payloads(report, payloads)
    require(yaml.load(payloads['source-profiles.yml'], Loader=base.UniqueYaml) == c.profiles(), 'profile stimulus changed')
    lines = payloads['server.log'].decode().splitlines()
    observed = [line for line in lines if 'SELECTOR_' in line]
    require(observed == report['observations'] and not any('SELECTOR_FAILED|' in line for line in lines), 'missing/raw mismatch/failure')
    markers = {}
    allowed = {'IDENTITIES', 'POSITIONS', 'DOOR', 'FLAGS', 'FLEE', 'LOOK', 'DIAGNOSTIC', 'UNSUPPORTED_DOOR', 'CLEANUP', 'SUMMARY'}
    for index, line in enumerate(lines):
        if 'SELECTOR_' not in line: continue
        match = re.search(r'SELECTOR_([A-Z_]+)\|', line)
        require(match is not None and match[1] in allowed, 'unknown marker')
        kind = match[1]; markers.setdefault(kind, []).append((index, fields(line, 'SELECTOR_' + kind + '|')))
    require(set(markers) == allowed, 'missing evidence markers')
    for kind in allowed - {'DOOR', 'FLEE', 'FLAGS', 'LOOK'}: require(len(markers[kind]) == 1, 'duplicate marker: ' + kind)
    identities = markers['IDENTITIES'][0][1]
    require(set(identities) == {'player', 'zombie', 'mob', 'wolf', 'look'} and len(set(identities.values())) == 5, 'wrong identities')
    for value in identities.values(): uuid.UUID(value)
    require(markers['POSITIONS'][0][1] == {'mobX': '0.0', 'playerX': '4.0', 'wolfX': '-4.0',
                                         'mobZ': '0.0', 'playerZ': '0.0', 'wolfZ': '0.0'}, 'direction stimulus changed')
    require(len(markers['DOOR']) == len(c.DOORS), 'wrong door count')
    indexes = {}; ticks = []
    for (index, row), (key, (enabled, controller)) in zip(markers['DOOR'], c.DOORS.items()):
        require(set(row) == {'key', 'mob', 'supported', 'enabled', 'controller', 'alive', 'tick'}, 'door fields differ')
        require(row['key'] == key and row['mob'] == identities['zombie'] and row['supported'] == 'true'
                and row['enabled'] == enabled and row['controller'] == controller and row['alive'] == 'true', 'door lifecycle differs: ' + key)
        indexes[key] = index; ticks.append((index, int(row['tick'])))
    require(len(markers['FLAGS']) == 2, 'wrong flags count')
    for (index, row), key in zip(markers['FLAGS'], ('all_then_clear', 'all_then_reset')):
        require(row == {'key': key, 'float': 'false', 'open': 'false', 'pass': 'false'} and indexes[key] < index, 'cleared flags leaked')
    require(len(markers['FLEE']) == len(c.FLEE), 'wrong flee count')
    for (index, row), (key, (target, state, vx)) in zip(markers['FLEE'], c.FLEE.items()):
        require(set(row) == {'key', 'mob', 'target', 'state', 'vx', 'vz', 'mode', 'tick'}, 'flee fields differ')
        require(row['key'] == key and row['mob'] == identities['mob'] and row['target'] == identities.get(target, target)
                and row['state'] == state and math.isclose(number(row['vx']), vx, abs_tol=1e-6)
                and math.isclose(number(row['vz']), 0, abs_tol=1e-6), 'flee target or direction differs: ' + key)
        mode = key.upper() if key in ('creative', 'spectator') else 'SURVIVAL'
        require(row['mode'] == mode, 'player mode differs')
        indexes['flee_' + key] = index; ticks.append((index, int(row['tick'])))
    require(len(markers['LOOK']) == 2, 'wrong look count')
    for (index, row), (key, random) in zip(markers['LOOK'], c.LOOK.items()):
        require(set(row) == {'key', 'mob', 'yaw', 'alignment', 'moved'} and row['key'] == key
                and row['mob'] == identities['look'] and row['moved'] == 'false', 'look identity/position differs')
        yaw, alignment = number(row['yaw']), number(row['alignment'])
        expected = math.degrees(math.atan2(-math.cos(0.6465821602909256 * math.tau), math.sin(0.6465821602909256 * math.tau))) if random else 0
        expected_alignment = (-math.sin(math.radians(expected)) * 4 - math.cos(math.radians(expected)) * 8) / math.sqrt(80)
        require(math.isclose(yaw, expected, abs_tol=1e-4) and -1 <= alignment <= 1
                and math.isclose(alignment, expected_alignment, abs_tol=1e-6), 'random look follows player or clear leaked')
        indexes['look_' + key] = index
    require(markers['DIAGNOSTIC'][0][1] == {'climb': 'true', 'closeDoor': 'true'}, 'unsupported goal diagnostics absent')
    require(markers['UNSUPPORTED_DOOR'][0][1] == {'reported': 'true'}, 'unsupported door species absent')
    require(markers['CLEANUP'][0][1] == {'bindings': '0', 'controllers': '0', 'visuals': '0'}, 'cleanup incomplete')
    require(markers['SUMMARY'][0][1] == {'doors': '12', 'flee': '7', 'look': '2', 'completed': 'true'}, 'summary incomplete')
    order = [markers['IDENTITIES'][0][0], markers['POSITIONS'][0][0], indexes['baseline_false'], indexes['detach_true'],
             indexes['all_then_clear'], indexes['all_then_reset'], indexes['flee_players'], indexes['flee_spectator'],
             indexes['look_random'], indexes['look_clear'], markers['DIAGNOSTIC'][0][0], markers['UNSUPPORTED_DOOR'][0][0],
             markers['CLEANUP'][0][0], markers['SUMMARY'][0][0]]
    require(order == sorted(set(order)), 'lifecycle ordering changed')
    tick_values = [tick for _, tick in sorted(ticks)]
    require(all(tick >= 0 for tick in tick_values) and tick_values == sorted(tick_values), 'tick order changed')
    stops = [index for index, line in enumerate(lines) if ']: Stopping server' in line]
    require(len(stops) == 1 and stops[0] > markers['SUMMARY'][0][0], 'premature/missing shutdown')
    client = [json.loads(line.split('SELECTOR_CLIENT ', 1)[1], object_pairs_hook=base.unique_pairs)
              for line in payloads['client.log'].decode().splitlines() if 'SELECTOR_CLIENT ' in line]
    require(client == [{'event': 'join', 'name': 'SelectorPlayer', 'uuid': identities['player']},
                       {'event': 'done'}, {'event': 'summary', 'passed': True}], 'real player lifecycle differs')
    return len(c.DOORS) + len(c.FLEE) + len(c.LOOK)


def main(path):
    path = Path(path); report = json.loads(path.read_text(), object_pairs_hook=base.unique_pairs)
    archive_name = report['logArchive']
    require(isinstance(archive_name, str) and PurePosixPath(archive_name).name == archive_name
            and '\\' not in archive_name and archive_name.endswith('.zip'), 'unsafe archive path')
    payloads = archive_payloads(report, (path.parent / archive_name).read_bytes())
    print('Validated', validate(report, payloads), 'scoped Paper selector repair observations.')
    return report, payloads


if __name__ == '__main__': main(sys.argv[1])
