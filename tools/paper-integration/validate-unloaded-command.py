"""Independent unload/command boundary, disk state and three-boot evidence checks."""
from datetime import datetime
import hashlib, importlib.util, itertools, json, re, sys, zipfile
from pathlib import Path
import yaml
sys.dont_write_bytecode = True
def module(name, file):
    s = importlib.util.spec_from_file_location(name, Path(__file__).with_name(file))
    m = importlib.util.module_from_spec(s); s.loader.exec_module(m); return m
registry = module('registry', 'validate-player-variables.py')
contract = module('contract', 'unloaded-command-cases.py')
require, fields, unique = registry.require, registry.fields, registry.unique_pairs
EXCEPTIONS = ['native-deferred-despawn-callback', 'inactive-native-registry-runtime-retained-snapshot']
def flag(v): return str(bool(v)).lower()
def doc(text): return yaml.load(text, Loader=registry.UniqueYaml)
def effective(s): return contract.CASES[s]['delay'] <= 5 and s not in ('baseline', 'outside_radius', 'wrong_world')
def selected(s, mode): return effective(s) and (not s.endswith('_bulk') or mode not in ('NPC', 'PERSISTENT'))
def survives(s, mode): return mode in ('NEVER', 'PERSISTENT') and not selected(s, mode)
def phases(stage, scenario):
    if stage != 'write': return ['restart', 'cleaned'] if stage == 'verify' else ['final']
    result = ['before']
    for t in range(1, 11):
        result.append('tick'+str(t))
        if t == contract.CASES[scenario]['delay']: result.append('command-after')
    return result + ['unloaded', 'reloaded']

def validate(report, texts):
    require(report['schemaVersion'] == 1 and report['evidenceMode'] == 'comparison-with-exception', 'observation/wrong schema')
    require(report['knownExceptions'] == EXCEPTIONS, 'unexplained representation/timing differences')
    require(report['clientKnownIssues'] == ['minecraft-protocol-1.21.1-declare-recipes-partial-read'], 'client exception')
    require(report['paperJarSha256'] == '39bd8c00b9e18de91dcabd3cc3dcfa5328685a53b7187a2f63280c22e2d287b9', 'wrong Paper')
    require(report['mythicMobsSha256'] == '9672457cafc56ff841606bfbb710947329c08720ffc207746b60130d43bf902f', 'wrong native reference')
    require(all(re.fullmatch('[0-9a-f]{64}', report[k]) for k in ('pluginSha256', 'probeJarSha256')), 'bad artifact')
    sources = {'run-unloaded-command-fixture.py', 'run-player-variable-fixture.py', 'unloaded-command-cases.py', 'mob-command-client.js', 'validate-unloaded-command.py', 'test-unloaded-command-evidence.py', 'validate-player-variables.py', 'random-spawn-fixture/src/main/java/dev/helstera/test/randomspawn/UnloadedCommandProbe.java'}
    require(set(report['sourceHashes']) == {'tools/paper-integration/'+p for p in sources} and all(re.fullmatch('[0-9a-f]{64}', h) for h in report['sourceHashes'].values()), 'source provenance')
    stages = ('write', 'verify', 'final')
    require([b['stage'] for b in report['boots']] == list(stages), 'missing/reordered boot')
    previous = None
    for b in report['boots']:
        start, end = (datetime.fromisoformat(b[k]) for k in ('startedAtUtc', 'stoppedAtUtc'))
        require(b['exitCode'] == 0 and start < end and (previous is None or previous < start), 'unclean/overlapping boot'); previous = end
    pairs, cases = contract.pairs(), contract.CASES
    source = doc(texts['source.yml']); require(set(source) == set(pairs), 'wrong catalog')
    for key, (mode, path) in pairs.items():
        require(source[key] == {'Type':'COW', 'Options':{'Despawn':mode, 'NoAI':True, 'NoGravity':True, 'Invincible':True, 'Collidable':False}, 'Skills':[f'command{{c="unloadedcommandprobe hook native {e} {key} <caster.var.Marker>"}} @self ~on{e.title()}' for e in ('despawn', 'death')]}, 'native source drift')
        cfg = doc(texts['imports/'+key+'.yml'])
        require(cfg['mob']['despawn'] == mode and cfg['model'] == 'example/emberling', 'wrong imported mode/model')
        triggers = cfg['mob' if path == 'imported' else 'ai']['triggers']
        require(triggers == {'on-'+e:{'do':[f'command "unloadedcommandprobe hook runtime {e} {key} <caster.var.Marker>" @self']} for e in ('despawn', 'death')}, 'callback mapping drift')
    identities = doc(texts['identities.yml']); require(list(identities) == list(cases), 'wrong saved scenarios')
    all_ids = []; snapshots = set(); observations = []; total = 0
    for stage in stages:
        text = texts[stage+'.server.log']; lines = text.splitlines()
        def marked(marker): return [(i, fields(l, marker)) for i, l in enumerate(lines) if marker in l]
        require('UCMD_FAILED|' not in text and 'UCMD_DEATH|' not in text, 'probe failure/death')
        rows = marked('UCMD_ROW|'); total += len(rows)
        observations += [l for l in lines if 'UCMD_ROW|' in l]
        expected_keys = [(s, p, k) for s in cases for p in phases(stage, s) for k in pairs]
        require([(r['scenario'], r['phase'], r['key']) for _, r in rows] == expected_keys, 'missing/reordered/duplicate rows')
        require([r for _, r in marked('UCMD_SUMMARY|')] == [{'stage':stage, 'rows':str(len(expected_keys)), 'completed':'true'}], 'boot summary')
        require([r for _, r in marked('UCMD_SEAL|')] == [{'stage':stage, 'scenario':s, 'accepted':'true', 'absent':'true'} for s in cases], 'world save failed')
        if stage == 'write':
            ids = [r for _, r in marked('UCMD_ID|')]
            require([(r['scenario'], r['key']) for r in ids] == list(itertools.product(cases, pairs)), 'identity coverage')
            for r in ids:
                s, key = r['scenario'], r['key']; mode, path = pairs[key]
                require(r['mode'] == mode and r['path'] == path and int(r['instance']) > 0, 'identity metadata')
                require(identities[s]['world'] == r['world'] and identities[s]['mobs'][key] == {'native':r['native'], 'runtime':r['runtime'], 'instance':int(r['instance'])}, 'identity file changed')
                all_ids += [r['native'], r['runtime']]
            require(len(set(all_ids)) == len(all_ids) and all(re.fullmatch('[0-9a-f-]{36}', u) for u in all_ids), 'invalid/reused UUID')
        for s, case in cases.items():
            subset = [(i, r) for i, r in rows if r['scenario'] == s]
            hooks = [(i, r) for i, r in marked('UCMD_HOOK|') if r['scenario'] == s]
            expected_hooks = {(b, k) for b in ('native', 'runtime') for k, (m, _) in pairs.items() if (not survives(s, m) if stage == 'write' else survives(s, m) if stage == 'verify' else False)}
            require(len(hooks) == len(expected_hooks) and {(r['backend'], r['key']) for _, r in hooks} == expected_hooks, 'lost/replayed callback '+stage+'/'+s)
            for _, h in hooks:
                require(h['stage'] == stage and h['event'] == 'despawn' and h['marker'] == '73', 'callback context/death')
                require(h['phase'] == 'cleanup' if stage == 'verify' else h['phase'] != 'before', 'callback outside lifecycle')
            if stage == 'write':
                requests = [(i, r) for i, r in marked('UCMD_REQUEST|') if r['scenario'] == s]
                require(len(requests) == 2 and {r['backend'] for _, r in requests} == {'native', 'runtime'}, 'unload request missing')
                for _, r in requests:
                    require(r['accepted'] == 'true' and r['wasForced'] == flag(r['backend'] == 'runtime') and int(r['x']) == (256 if r['backend'] == 'native' else 512) and r['z'] == '256', 'unload/shared-chunk isolation')
                events = [(i, r) for i, r in marked('UCMD_ENTITIES_EVENT|') if r['scenario'] == s]
                first = events[:2]
                require(len(first) == 2 and {r['backend'] for _, r in first} == {'native', 'runtime'} and len({r['tick'] for _, r in first}) == 1, 'actual simultaneous unload missing')
                tick = int(first[0][1]['tick'])
                for i, r in first:
                    require(int(r['nativeCount']) == (10 if r['backend'] == 'native' else 0) and int(r['runtimeCount']) == (10 if r['backend'] == 'runtime' else 0), 'unload entity membership')
                    require(all(j < i for j, _ in requests), 'event before request')
                commands = [(i, r) for i, r in marked('UCMD_COMMAND|') if r['scenario'] == s]
                require(len(commands) == 1, 'command missing/duplicate'); command_i, command = commands[0]
                require(command['suffix'] == case['command'] and command['player'] == flag(case['player']) and int(command['elapsed']) == case['delay'] and int(command['tick']) == tick+case['delay'] and command['nativeLoaded'] == command['runtimeLoaded'] == 'false', 'command timing/loaded precondition')
                require(command['senderWorld'] == 'console' if not case['player'] else (command['senderWorld'] != identities[s]['world'] if s == 'wrong_world' else command['senderWorld'] == identities[s]['world']), 'wrong sender world')
                dispatch = [(i, r) for i, r in marked('UCMD_DISPATCH|') if r['scenario'] == s]
                require([r['backend'] for _, r in dispatch] == ([] if case['command'] == 'none' else ['native', 'runtime']) and all(r['result'] == 'true' and i > command_i for i, r in dispatch), 'dispatch failed')
                loads = [r for i, r in marked('UCMD_LOAD_EVENT|') if r['scenario'] == s and command_i < i < next(j for j, r in subset if r['phase'] == 'command-after')]
                require({r['backend'] for r in loads} == ({'native','runtime'} if effective(s) and s.endswith('_radius') else set()), 'radius load/missing isolation')
            for i, r in subset:
                key, p = r['key'], r['phase']; mode, _ = pairs[key]; retained = mode in ('NEVER', 'PERSISTENT'); survivor = survives(s, mode)
                require(r['stage'] == stage, 'wrong row stage')
                for b in ('native', 'runtime'):
                    count = sum(j < i and h['key'] == key and h['backend'] == b for j, h in hooks)
                    require(r[b+'Hooks'] == str(count), 'callback counter/log disagreement')
                if p == 'before':
                    expected = dict(nativeEntity='true', runtimeEntity='true', nativeRegistry='true', runtimeInstance='true', nativeVariable='73', runtimeVariable='73', tree='91', file='false', nativeLoaded='true', runtimeLoaded='true')
                elif stage != 'write' or p == 'reloaded':
                    alive = survivor and p in ('reloaded', 'restart')
                    expected = dict(nativeEntity=flag(alive), runtimeEntity=flag(alive), nativeRegistry=flag(alive), runtimeInstance=flag(alive), nativeVariable='73' if alive else 'absent', runtimeVariable='73' if alive else 'absent', tree='91' if alive else 'absent', file=flag(alive), nativeLoaded='true', runtimeLoaded='true')
                else:
                    elapsed = int(r['elapsed']); require(elapsed == (int(p[4:]) if p.startswith('tick') else case['delay'] if p == 'command-after' else 30), 'sample delay')
                    after = elapsed > case['delay'] or p == 'command-after'
                    removed = after and selected(s, mode)
                    active = elapsed <= 5 and not removed
                    present = retained and not removed
                    expected = dict(nativeEntity='false', runtimeEntity='false', nativeRegistry=flag(active), runtimeInstance='false', nativeVariable='73' if active else 'absent', runtimeVariable='73' if present else 'absent', tree='absent', file=flag(present))
                    if not after or not (effective(s) and s.endswith('_radius')): expected.update(nativeLoaded='false', runtimeLoaded='false')
                    elif p == 'command-after': expected.update(nativeLoaded='true', runtimeLoaded='true')
                for name, value in expected.items(): require(r[name] == value, f'wrong {stage}/{s}/{p}/{key}/{name}: {r[name]} != {value}')
                if r['file'] == 'true' and p in ('unloaded', 'reloaded', 'restart'):
                    name = f'snapshots/{stage}-{s}-{p}-{key}.yml'; snapshots.add(name)
                    snapshot = doc(texts[name]); require(snapshot['snapshot-format'] == 4 and snapshot['variables']['entries'] == {'kVW5sb2FkVHJlZQ':{'name':'UnloadTree','value':{'type':'INTEGER','value':91}}}, 'snapshot content lost')
            visuals = [r for _, r in marked('UCMD_VISUALS|') if r['scenario'] == s]
            require([r['phase'] for r in visuals] == phases(stage, s), 'visual coverage')
            for v in visuals:
                n = len(pairs) if v['phase'] == 'before' else sum(survives(s,m) for m,_ in pairs.values()) if v['phase'] in ('reloaded','restart') else 0
                require(v == {'stage':stage, 'scenario':s, 'phase':v['phase'], 'count':str(n*11)}, 'missing/orphan visuals')
    base_files = {'source.yml','identities.yml','scoped-write.yml','scoped-verify.yml','scoped-final.yml','scoped-shutdown.yml','remaining-tree-files.json'} | {s+k for s in stages for k in ('.server.log','.client.log')} | {'imports/'+k+'.yml' for k in pairs}
    require(set(texts) == base_files | snapshots, 'unexpected/missing evidence files')
    for name in ('scoped-write.yml','scoped-verify.yml','scoped-final.yml','scoped-shutdown.yml'):
        cfg = doc(texts[name]); require(cfg['registry-format'] == 1, 'registry schema')
        regs = cfg.get('registries') or {}; scopes = [r['scope'] for r in regs.values()]
        wanted = {'entity:'+identities[s]['mobs'][k]['runtime'] for s in cases for k,(m,_) in pairs.items() if survives(s,m)} if name == 'scoped-write.yml' else set()
        require(len(scopes) == len(set(scopes)) and set(scopes) == wanted, 'saved variable registry selection')
        for r in regs.values(): require(r['snapshot']['variables']['entries'] == {'kTWFya2Vy':{'name':'Marker','value':{'type':'INTEGER','value':73}}}, 'saved variable content')
    require(json.loads(texts['remaining-tree-files.json']) == [], 'orphan snapshot')
    require(report['observations'] == observations, 'report/log disagreement')
    events = [json.loads(l.split('DCMD_CLIENT ',1)[1]) for l in texts['write.client.log'].splitlines() if l.startswith('DCMD_CLIENT ')]
    require([e['event'] for e in events if e['event'] != 'packet-error'] == ['join','end'], 'missing/failed real client')
    errors = [e for e in events if e['event'] == 'packet-error']
    require(len(errors) == 1 and errors[0]['packet'] == 'declare_recipes' and errors[0]['state'] == 'play' and errors[0]['partial'] is True, 'unexpected client exception')
    require(texts['verify.client.log'] == texts['final.client.log'] == '', 'unexpected restart client')
    return total

def main(path):
    root = Path(path); archived = root.is_file()
    report = json.loads((root if archived else root/'unloaded-command-report.json').read_text(), object_pairs_hook=unique)
    if archived:
        name = report['logArchive']; require(Path(name).name == name and '\\' not in name, 'unsafe archive')
        archive = root.parent/name; require(hashlib.sha256(archive.read_bytes()).hexdigest() == report['logArchiveSha256'], 'archive changed')
        with zipfile.ZipFile(archive) as z:
            names = z.namelist(); require(len(names) == len(set(names)) and set(names) == {f['path'] for f in report['files']}, 'archive members')
            payloads = {n:z.read(n) for n in names}
    texts = {}
    for f in report['files']:
        name = f['path']; require(not Path(name).is_absolute() and '..' not in Path(name).parts and '\\' not in name and name not in texts, 'unsafe/duplicate member')
        data = payloads[name] if archived else (root/name).read_bytes()
        require(hashlib.sha256(data).hexdigest() == f['sha256'], 'member changed'); texts[name] = data.decode('utf-8-sig')
    print(f'Validated {validate(report,texts)} unloaded command observations across three boots.')
    return report, texts
if __name__ == '__main__': main(sys.argv[1])
