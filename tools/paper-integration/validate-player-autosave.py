"""Independent evidence checks for periodic player saves and two abrupt process deaths."""
import hashlib
import importlib.util
import itertools
import json
from pathlib import Path
import re
import sys
import zipfile

sys.dont_write_bytecode = True
spec = importlib.util.spec_from_file_location('player_registry_validator', Path(__file__).with_name('validate-player-variables.py'))
registry = importlib.util.module_from_spec(spec); spec.loader.exec_module(registry)
require, fields, unique = registry.require, registry.fields, registry.unique_pairs
NAMES = registry.NAMES
CASES = ('Default', 'Saved', 'Transient', 'Negative', 'Timed', 'OfflineExpiry', 'Float', 'Double', 'String', 'Location', 'Deleted', 'Generation')
PHASES = {'write': ('write', 'updated'), 'recover': ('recover', 'cleared'), 'final': ('final',)}


def disk_values(texts, prefix, rows):
    """Validate an online checkpoint against explicit phase expectations and observed deadlines."""
    for player in NAMES:
        identity = registry.offline_id(player)
        native_doc = json.loads(texts[f'{prefix}/native-{identity}.json'], object_pairs_hook=unique)
        require(native_doc['uniqueId'] == identity and native_doc['name'] == player, 'wrong disk player')
        native = native_doc['variables']['entries']
        runtime = registry.runtime_registry(texts[f'{prefix}/runtime-{identity}.yml'], identity)
        expected = {}
        for row in rows:
            if row['uuid'] != identity or row['expected'] != 'true': continue
            name = 'Auto' + ('D' if row['path'] == 'direct' else 'I') + row['actor'] + row['scope'] + row['case']
            expected[name] = row
        require(set(native) == set(runtime) == set(expected), 'checkpoint missing/extra values')
        for name, row in expected.items():
            n, r = native[name], runtime[name]
            kind = row['case']; expected_type = kind.upper() if kind in ('Float', 'Double', 'String', 'Location') else 'INTEGER'
            require(n['type'].upper() == r['type'] == expected_type, 'checkpoint wrong type')
            require((n['expirationTime'], r['deadline']) == (int(row['nativeDeadline']), int(row['runtimeDeadline'])), 'checkpoint wrong deadline')
            if kind == 'Location':
                point = dict(n['value']); point['world'] = point['world']['name']
                require(point == r['value'], 'checkpoint location differs')
                require(','.join(str(point[k]) for k in ('world', 'x', 'y', 'z')) == row['native'], 'checkpoint location changed')
            else: require(str(n['value']) == str(r['value']) == row['native'], 'checkpoint stale value')


def validate(report, texts):
    require(report['schemaVersion'] == 1 and report['evidenceMode'] == 'comparison', 'wrong mode')
    require(report['paperJarSha256'] == '39bd8c00b9e18de91dcabd3cc3dcfa5328685a53b7187a2f63280c22e2d287b9', 'wrong Paper')
    require(report['mythicMobsSha256'] == '9672457cafc56ff841606bfbb710947329c08720ffc207746b60130d43bf902f', 'wrong MM')
    require(all(re.fullmatch('[0-9a-f]{64}', report[key]) for key in ('pluginSha256','probeJarSha256')), 'missing artifact hash')
    require(report['saveIntervalSeconds'] == 5 and len(report['stages']) == 3, 'missing boots or clock config')
    expected_keys = set(itertools.product(range(2), ('caster', 'target'), CASES, ('direct', 'imported')))
    original = {}; phase_rows = {}
    for stage, entry in zip(PHASES, report['stages']):
        require(entry['stage'] == stage, 'wrong boot order')
        text = texts[stage + '.server.log']
        require('PLAYER_AUTO_FAILED|' not in text, 'probe failed')
        require(text.count('PLAYER_AUTO_CLOCK|nativeSeconds=5|runtimeSeconds=5') == 1, 'clock not configured')
        rows = [line for line in text.splitlines() if 'PLAYER_AUTO|' in line]
        require(rows == entry['observations'] and len(rows) == 96 * len(PHASES[stage]), 'incomplete observations')
        joins = [fields(line, 'PLAYER_AUTO_SESSION|') for line in text.splitlines() if 'PLAYER_AUTO_SESSION|event=join' in line]
        require(len(joins) == 2 and {r['name'] for r in joins} == set(NAMES), 'missing actual player sessions')
        require(all(r['uuid'] == registry.offline_id(r['name']) for r in joins), 'session identity differs')
        events = [json.loads(l.split('PLAYER_AUTO_CLIENT ', 1)[1]) for l in texts[stage + '.client.log'].splitlines() if 'PLAYER_AUTO_CLIENT ' in l]
        require(all(e['stage'] == stage for e in events), 'client stage differs')
        for event in ('join', 'end'):
            actual = [e for e in events if e['event'] == event]
            require(len(actual) == 2 and {e['name'] for e in actual} == set(NAMES), 'missing client connection/end')
        require(all(e['uuid'] == registry.offline_id(e['name']) for e in events if e['event'] == 'join'), 'client identity differs')
        require(sum(e['event'] == 'summary' and e['passed'] for e in events) == 1, 'client incomplete')
        if stage != 'final':
            require(entry['termination'] == 'SIGKILL' and entry['exitCode'] == -9, 'not an abrupt process death')
            require('PLAYER_AUTO_DISABLE|' not in text and 'PLAYER_AUTO_SESSION|event=quit' not in text and 'Stopping server' not in text, 'graceful save contaminated crash evidence')
        else: require(entry['termination'] == 'graceful' and entry['exitCode'] == 0, 'final server failed')
        for phase in PHASES[stage]:
            require(text.count(f'PLAYER_AUTO_PHASE|phase={phase}|observations=96|online=2|passed=true') == 1, 'phase incomplete')
            observed = [fields(line, 'PLAYER_AUTO|') for line in rows if f'|phase={phase}|' in line]
            require(len(observed) == 96, 'missing phase rows'); seen = set()
            for row in observed:
                key = int(row['actor']), row['scope'], row['case'], row['path']
                require(key in expected_keys and key not in seen, 'unknown/duplicate row'); seen.add(key)
                actor, scope, kind, path = key
                require(row['uuid'] == registry.offline_id(NAMES[actor if scope == 'caster' else 1 - actor]), 'wrong variable owner')
                present = kind == 'Generation' or phase == 'write' or phase == 'updated' and kind != 'Deleted' or phase == 'recover' and kind not in ('Deleted', 'OfflineExpiry')
                require(row['expected'] == str(present).lower() and row['matched'] == row['isolated'] == 'true', 'wrong presence or contamination')
                if not present: require(row['native'] == row['runtime'] == row['nativeType'] == 'absent' and row['nativeDeadline'] == row['runtimeDeadline'] == '0', 'deleted/expired value resurrected')
                else:
                    require(row['nativeType'] == ('AbstractLocation' if kind == 'Location' else kind if kind in ('Float','Double','String') else 'Integer'), 'runtime type changed')
                    value = str(actor * 10 + (1 if phase == 'write' else 2 if phase in ('updated', 'recover') else 3)) if kind == 'Generation' else str(3.25 + actor) if kind in ('Float', 'Double') else 'saved-' + str(actor) if kind == 'String' else str(actor * 10 + 7)
                    if kind == 'Location':
                        if phase == 'write': require(row['native'].startswith('integration-world,'), 'wrong location world')
                        else: require(row['native'] == original[key]['native'], 'location changed')
                    else: require(row['native'] == value, 'wrong scalar value')
                    require(row['native'] == row['runtime'], 'value mismatch')
                    nd, rd = int(row['nativeDeadline']), int(row['runtimeDeadline'])
                    if phase == 'write':
                        if kind in ('Timed', 'OfflineExpiry'): require(nd > 1_000_000_000_000 and abs(nd-rd) < 1000, 'bad timer')
                        else: require(nd == rd == (1 if kind in ('Default', 'Transient') else -50 if kind == 'Negative' else 0), 'bad retention marker')
                    else: require((nd, rd) == (int(original[key]['nativeDeadline']), int(original[key]['runtimeDeadline'])), 'deadline reset')
                if phase == 'write': original[key] = row
            phase_rows[phase] = observed
    require([c['phase'] for c in report['checkpoints']] == ['write', 'updated', 'cleared'], 'missing online checkpoint')
    for checkpoint in report['checkpoints']:
        phase = checkpoint['phase']; disk_values(texts, phase + '-online', phase_rows[phase])
        require(checkpoint['onlinePlayers'] == 2, 'checkpoint not online')
        if phase != 'write':
            # Files read while online must be identical after the process was killed.
            for player in NAMES:
                identity = registry.offline_id(player)
                for prefix, suffix in (('native-', '.json'), ('runtime-', '.yml')):
                    name = prefix + identity + suffix
                    require(texts[phase + '-online/' + name] == texts[phase + '-killed/' + name], 'crash checkpoint changed')
    return 480


def main(path):
    root = Path(path); archived = root.is_file()
    report = json.loads((root if archived else root/'player-autosave-report.json').read_text(), object_pairs_hook=unique)
    if archived:
        name = report['logArchive']; require(Path(name).name == name and '\\' not in name, 'unsafe archive')
        archive_path = root.parent/name; require(hashlib.sha256(archive_path.read_bytes()).hexdigest() == report['logArchiveSha256'], 'archive hash mismatch')
        with zipfile.ZipFile(archive_path) as archive:
            names = archive.namelist(); require(len(names) == len(set(names)) and set(names) == {e['path'] for e in report['files']}, 'archive members differ')
            payloads = {name: archive.read(name) for name in names}
    else:
        for name, key in (('paper.jar','paperJarSha256'),('plugins/MythicMobs.jar','mythicMobsSha256'),('plugins/helsteraMobs.jar','pluginSha256'),('plugins/HelsteraPlayerAutosaveProbe.jar','probeJarSha256')):
            require(hashlib.sha256((root/name).read_bytes()).hexdigest() == report[key], 'artifact changed')
    texts = {}
    for entry in report['files']:
        name = entry['path']; require(not Path(name).is_absolute() and '..' not in Path(name).parts and name not in texts, 'unsafe/duplicate member')
        data = payloads[name] if archived else (root/name).read_bytes()
        require(hashlib.sha256(data).hexdigest() == entry['sha256'], 'member hash mismatch'); texts[name] = data.decode('utf-8-sig')
    print(f'Validated {validate(report, texts)} autosave observations, three online checkpoints and two SIGKILL recoveries.')
    return report, texts


if __name__ == '__main__': main(sys.argv[1])
