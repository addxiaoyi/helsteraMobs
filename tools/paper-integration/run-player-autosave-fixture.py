"""Use ordinary configured clocks, then SIGKILL only this fresh disposable Paper process."""
import argparse
from datetime import datetime, timezone
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time
import zipfile

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[2]
TOOLS = ROOT/'tools/paper-integration'
def module(name, filename):
    spec = importlib.util.spec_from_file_location(name, TOOLS/filename)
    result = importlib.util.module_from_spec(spec); spec.loader.exec_module(result); return result
common = module('player_runner', 'run-player-variable-fixture.py')
validator = module('autosave_validator', 'validate-player-autosave.py')
digest, redact = common.digest, common.redact


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--revision', required=True)
    parser.add_argument('--cache-directory', required=True)
    args = parser.parse_args()
    import yaml
    if not 6 <= int(yaml.__version__.split('.')[0]) < 7: raise RuntimeError('Install requirements-player-variables.txt')
    if os.name != 'posix': raise RuntimeError('SIGKILL evidence requires a POSIX host')
    if not re.fullmatch(r'r[1-9][0-9]*', args.revision): raise ValueError('Invalid revision')
    fixture = f'srv-paper-player-autosave-{datetime.now(timezone.utc):%Y%m%d}-{args.revision}'
    server = ROOT/fixture
    if server.exists(): raise ValueError('Refusing to reuse fixture')
    paper = ROOT/'srv-1.21.1/paper.jar'; native = TOOLS/'.external-cache/MythicMobs-5.7.2-FTWvGuE5.jar'
    plugin = ROOT/'helstera-plugin/target/helsteraMobs-1.0.0-full.jar'
    assert digest(paper) == common.PAPER_HASH and digest(native) == common.NATIVE_HASH
    subprocess.run(['mvn', '-q', '-f', str(TOOLS/'random-spawn-fixture/pom.xml'), 'package'], check=True, cwd=ROOT)
    server.mkdir()
    for name in ('cache', 'libraries', 'versions'): shutil.copytree(Path(args.cache_directory)/name, server/name)
    shutil.copy2(paper, server/'paper.jar'); shutil.copy2(TOOLS/'server.properties', server/'server.properties')
    (server/'eula.txt').write_text('eula=true\n')
    plugins = server/'plugins'; plugins.mkdir()
    shutil.copy2(plugin, plugins/'helsteraMobs.jar'); shutil.copy2(native, plugins/'MythicMobs.jar')
    probe_data = plugins/'HelsteraPlayerAutosaveProbe'; probe_data.mkdir()
    probe_jar = plugins/'HelsteraPlayerAutosaveProbe.jar'
    with zipfile.ZipFile(TOOLS/'random-spawn-fixture/target/random-spawn-paper-fixture-1.0.0.jar') as source, zipfile.ZipFile(probe_jar, 'w', zipfile.ZIP_DEFLATED) as dest:
        for item in source.infolist():
            if item.filename != 'plugin.yml': dest.writestr(item, source.read(item.filename))
        dest.writestr('plugin.yml', "name: HelsteraPlayerAutosaveProbe\nversion: '1.0'\napi-version: '1.21'\nmain: dev.helstera.test.randomspawn.PlayerAutosaveProbe\ndepend: [helsteraMobs, MythicMobs]\ncommands:\n  autosaveprobe:\n    description: Disposable autosave evidence probe\n")
    mobs = plugins/'MythicMobs/Mobs'; mobs.mkdir(parents=True)
    (mobs/'variables.yml').write_text('HelsteraRadiusAuditMob:\n  Type: COW\n  Options:\n    NoAI: true\n    Despawn: PERSISTENT\n')
    native_config = plugins/'MythicMobs/config'; native_config.mkdir()
    (native_config/'config-general.yml').write_text('Configuration:\n  Clock:\n    Saving: 5\n')
    data = plugins/'helsteraMobs'; data.mkdir()
    config = (TOOLS/'config.yml').read_text()
    config = re.sub(r'(?m)^  (self-test(?:-[A-Za-z0-9-]+)?):\s*true\s*$', r'  \1: false', config)
    config = re.sub(r'(?m)^  trace-triggers:.*$', '  trace-triggers: false', config)
    config = re.sub(r'(?m)^  mythicmobs: false\s*$', '  mythicmobs: true', config)
    assert not re.search(r'(?m)^web:\s*\n[ \t]+enabled:\s*true', config)
    config += '\nvariables:\n  player-autosave-seconds: 5\n'
    (data/'config.yml').write_text(config)
    for name in ('items.yml', 'loot.yml'): shutil.copy2(TOOLS/name, data/name)
    (data/'skills.yml').write_text('skills: {}\n'); (data/'spawners.yml').write_text('spawners: {}\n')
    sources = [Path(__file__).resolve(), TOOLS/'player-autosave-client.js', TOOLS/'run-player-variable-fixture.py',
               TOOLS/'validate-player-autosave.py', TOOLS/'validate-player-variables.py', TOOLS/'requirements-player-variables.txt',
               TOOLS/'random-spawn-fixture/src/main/java/dev/helstera/test/randomspawn/PlayerAutosaveProbe.java',
               TOOLS/'random-spawn-fixture/src/main/java/dev/helstera/test/randomspawn/VariablePlaceholderAudit.java']
    report = {'schemaVersion':1, 'fixtureDirectory':fixture, 'evidenceMode':'comparison', 'generatedAtUtc':datetime.now(timezone.utc).isoformat(),
              'pluginSha256':digest(plugin), 'paperJarSha256':common.PAPER_HASH, 'mythicMobsSha256':common.NATIVE_HASH, 'probeJarSha256':digest(probe_jar),
              'saveIntervalSeconds':5, 'pythonVersion':sys.version.split()[0], 'pyyamlVersion':yaml.__version__,
              'sourceHashes':{str(p.relative_to(ROOT)):digest(p) for p in sources}, 'stages':[], 'checkpoints':[], 'files':[]}
    latest = server/'logs/latest.log'
    def read_log(): return latest.read_text(errors='replace') if latest.exists() else ''
    def wait_for(process, predicate, timeout=100):
        limit = time.monotonic()+timeout
        while time.monotonic() < limit and process.poll() is None:
            text = read_log()
            if 'PLAYER_AUTO_FAILED|' in text: raise RuntimeError('Probe failed; inspect retained fixture log')
            if predicate(text): return
            time.sleep(.25)
        raise RuntimeError('Autosave fixture timed out or Paper exited')
    def snapshots():
        result = {}
        for player in validator.NAMES:
            identity = validator.registry.offline_id(player)
            result['native-'+identity+'.json'] = (plugins/'MythicMobs/data/players'/f'{identity}.json').read_text()
            result['runtime-'+identity+'.yml'] = (data/'player-variables'/f'{identity}.yml').read_text()
        return result
    def save_snapshot(prefix, contents):
        folder = server/prefix; folder.mkdir()
        for name, text in contents.items(): (folder/name).write_text(text)
    def checkpoint(process, phase):
        rows = [validator.fields(line, 'PLAYER_AUTO|') for line in read_log().splitlines() if 'PLAYER_AUTO|' in line and f'|phase={phase}|' in line]
        assert len(rows) == 96
        def saved(_):
            try:
                captured = snapshots()
                validator.disk_values({phase+'-online/'+n:t for n,t in captured.items()}, phase+'-online', rows)
            except (ValueError, KeyError, FileNotFoundError, json.JSONDecodeError): return False
            save_snapshot(phase+'-online', captured)
            report['checkpoints'].append({'phase':phase,'onlinePlayers':2,'capturedAtUtc':datetime.now(timezone.utc).isoformat()})
            return True
        wait_for(process, saved, 40)
        print('Online checkpoint verified:', phase, flush=True)
    for stage in ('write', 'recover', 'final'):
        (probe_data/'config.yml').write_text(f'stage: {stage}\n')
        if latest.exists(): latest.unlink()
        client = None
        with (server/f'{stage}.stdout.log').open('w') as output, (server/f'{stage}.client.log').open('w') as client_output:
            process = subprocess.Popen(['java','-Xms1G','-Xmx2G','-jar','paper.jar','nogui'], cwd=server, stdin=subprocess.PIPE, stdout=output, stderr=subprocess.STDOUT, text=True)
            try:
                wait_for(process, lambda t:'Done (' in t)
                client = subprocess.Popen(['node',str(TOOLS/'player-autosave-client.js'),stage], cwd=TOOLS, stdout=client_output, stderr=subprocess.STDOUT)
                wait_for(process, lambda t:f'PLAYER_AUTO_PHASE|phase={stage}|observations=96|online=2|passed=true' in t)
                if stage == 'write':
                    checkpoint(process, 'write')
                    process.stdin.write('autosaveprobe updated\n'); process.stdin.flush()
                    wait_for(process, lambda t:'PLAYER_AUTO_PHASE|phase=updated|observations=96|online=2|passed=true' in t)
                    checkpoint(process, 'updated')
                elif stage == 'recover':
                    process.stdin.write('autosaveprobe cleared\n'); process.stdin.flush()
                    wait_for(process, lambda t:'PLAYER_AUTO_PHASE|phase=cleared|observations=96|online=2|passed=true' in t)
                    checkpoint(process, 'cleared')
                if stage != 'final':
                    process.kill(); process.wait(timeout=15)
                    assert process.returncode == -9
                    save_snapshot(('updated' if stage=='write' else 'cleared')+'-killed', snapshots())
                else:
                    process.stdin.write('stop\n'); process.stdin.flush(); process.wait(timeout=30)
                    assert process.returncode == 0
                client.wait(timeout=15)
                if client.returncode: raise RuntimeError('Client failed')
                text = redact(read_log()); (server/f'{stage}.server.log').write_text(text)
                report['stages'].append({'stage':stage,'termination':'graceful' if stage=='final' else 'SIGKILL','exitCode':process.returncode,
                    'observations':[line for line in text.splitlines() if 'PLAYER_AUTO|' in line]})
            finally:
                if process.poll() is None:
                    process.stdin.write('stop\n'); process.stdin.flush()
                    try: process.wait(timeout=30)
                    except subprocess.TimeoutExpired: process.kill(); process.wait()
                if client is not None and client.poll() is None: client.terminate(); client.wait(timeout=10)
        for name in (f'{stage}.stdout.log', f'{stage}.client.log'):
            path=server/name; path.write_text(redact(path.read_text(errors='replace')))
        if stage == 'write':
            deadlines = [int(validator.fields(row,'PLAYER_AUTO|')['nativeDeadline']) for row in report['stages'][0]['observations'] if '|case=OfflineExpiry|' in row and '|phase=write|' in row]
            print('Waiting for saved short deadlines to elapse while Paper is stopped.', flush=True)
            while time.time()*1000 <= max(deadlines)+1000: time.sleep(.25)
    for pattern in ('*.server.log','*.client.log','*-online/*','*-killed/*'):
        for path in sorted(server.glob(pattern)): report['files'].append({'path':str(path.relative_to(server)),'sha256':digest(path)})
    (server/'player-autosave-report.json').write_text(json.dumps(report,indent=2)+'\n')
    validator.main(server)
    for name, expected in report['sourceHashes'].items():
        if digest(ROOT/name)!=expected: raise RuntimeError('Source changed during fixture')
    report_name = f'paper-1.21.1-player-autosave-{datetime.now(timezone.utc):%Y%m%d}-{args.revision}'
    report_path = TOOLS/'reports'/(report_name+'.json'); archive_path=TOOLS/'reports'/(report_name+'-logs.zip')
    if report_path.exists() or archive_path.exists(): raise RuntimeError('Refusing to overwrite evidence')
    with zipfile.ZipFile(archive_path,'x',zipfile.ZIP_DEFLATED) as archive:
        for entry in report['files']: archive.write(server/entry['path'],entry['path'])
    report['logArchive']=archive_path.name;report['logArchiveSha256']=digest(archive_path)
    report_path.write_text(json.dumps(report,indent=2)+'\n');validator.main(report_path)
    print('Player autosave comparison passed:',fixture,flush=True)


if __name__ == '__main__': main()
