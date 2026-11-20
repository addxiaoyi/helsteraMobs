"""Real death/respawn and immediate reconnects against untouched native MM profiles."""
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
validator = module('life_validator', 'validate-player-life.py')
digest, redact = common.digest, common.redact


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--revision', required=True)
    parser.add_argument('--cache-directory', required=True)
    args = parser.parse_args()
    import yaml
    if not 6 <= int(yaml.__version__.split('.')[0]) < 7: raise RuntimeError('Install requirements-player-variables.txt')
    if not re.fullmatch(r'r[1-9][0-9]*', args.revision): raise ValueError('Invalid revision')
    fixture = f'srv-paper-player-life-{datetime.now(timezone.utc):%Y%m%d}-{args.revision}'
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
    probe_data = plugins/'HelsteraPlayerLifeProbe'; probe_data.mkdir()
    probe_jar = plugins/'HelsteraPlayerLifeProbe.jar'
    with zipfile.ZipFile(TOOLS/'random-spawn-fixture/target/random-spawn-paper-fixture-1.0.0.jar') as source, zipfile.ZipFile(probe_jar, 'w', zipfile.ZIP_DEFLATED) as dest:
        for item in source.infolist():
            if item.filename != 'plugin.yml': dest.writestr(item, source.read(item.filename))
        dest.writestr('plugin.yml', "name: HelsteraPlayerLifeProbe\nversion: '1.0'\napi-version: '1.21'\nmain: dev.helstera.test.randomspawn.PlayerLifeProbe\ndepend: [helsteraMobs, MythicMobs]\n")
    mobs = plugins/'MythicMobs/Mobs'; mobs.mkdir(parents=True)
    (mobs/'variables.yml').write_text('HelsteraRadiusAuditMob:\n  Type: COW\n  Options:\n    NoAI: true\n    Despawn: PERSISTENT\n')
    native_config = plugins/'MythicMobs/config'; native_config.mkdir()
    (native_config/'config-general.yml').write_text('Configuration:\n  Clock:\n    Saving: 300\n')
    data = plugins/'helsteraMobs'; data.mkdir()
    config = (TOOLS/'config.yml').read_text()
    config = re.sub(r'(?m)^  (self-test(?:-[A-Za-z0-9-]+)?):\s*true\s*$', r'  \1: false', config)
    config = re.sub(r'(?m)^  trace-triggers:.*$', '  trace-triggers: false', config)
    config = re.sub(r'(?m)^  mythicmobs: false\s*$', '  mythicmobs: true', config)
    assert not re.search(r'(?m)^web:\s*\n[ \t]+enabled:\s*true', config)
    config += '\nvariables:\n  player-autosave-seconds: 300\n'
    (data/'config.yml').write_text(config)
    for name in ('items.yml', 'loot.yml'): shutil.copy2(TOOLS/name, data/name)
    (data/'skills.yml').write_text('skills: {}\n'); (data/'spawners.yml').write_text('spawners: {}\n')
    sources = [Path(__file__).resolve(), TOOLS/'player-life-client.js', TOOLS/'run-player-variable-fixture.py',
               TOOLS/'validate-player-life.py', TOOLS/'validate-player-variables.py', TOOLS/'requirements-player-variables.txt',
               TOOLS/'random-spawn-fixture/src/main/java/dev/helstera/test/randomspawn/PlayerLifeProbe.java',
               TOOLS/'random-spawn-fixture/src/main/java/dev/helstera/test/randomspawn/VariablePlaceholderAudit.java']
    report = {'schemaVersion':1, 'fixtureDirectory':fixture, 'evidenceMode':'comparison', 'generatedAtUtc':datetime.now(timezone.utc).isoformat(),
              'pluginSha256':digest(plugin), 'paperJarSha256':common.PAPER_HASH, 'mythicMobsSha256':common.NATIVE_HASH, 'probeJarSha256':digest(probe_jar),
              'saveIntervalSeconds':300, 'pythonVersion':sys.version.split()[0], 'pyyamlVersion':yaml.__version__,
              'sourceHashes':{str(p.relative_to(ROOT)):digest(p) for p in sources}, 'observations':[], 'files':[]}
    latest = server/'logs/latest.log'
    client = None
    with (server/'stdout.log').open('w') as output, (server/'client.log').open('w') as client_output:
        process = subprocess.Popen(['java','-Xms1G','-Xmx2G','-jar','paper.jar','nogui'], cwd=server, stdin=subprocess.PIPE, stdout=output, stderr=subprocess.STDOUT, text=True)
        def wait_for(predicate, timeout=120):
            deadline = time.monotonic()+timeout
            while time.monotonic()<deadline and process.poll() is None:
                text=latest.read_text(errors='replace') if latest.exists() else ''
                if 'PLAYER_LIFE_FAILED|' in text: raise RuntimeError('Lifecycle probe failed; inspect retained log')
                if predicate(text): return
                time.sleep(.1)
            raise RuntimeError('Lifecycle timeout or Paper exited')
        try:
            wait_for(lambda t:'Done (' in t)
            client=subprocess.Popen(['node',str(TOOLS/'player-life-client.js')], cwd=TOOLS,stdout=client_output,stderr=subprocess.STDOUT)
            wait_for(lambda t:'PLAYER_LIFE_SUMMARY|observations=700|passed=true' in t)
            client.wait(timeout=15)
            if client.returncode: raise RuntimeError('Client failed')
            wait_for(lambda t:sum('PLAYER_LIFE_UNLOADED|' in line for line in t.splitlines())==10, 10)
            # Native quit saves are asynchronous; observe their actual completion without forcing them.
            deadline=time.monotonic()+10
            while True:
                snapshots={}
                try:
                    for player in validator.NAMES:
                        identity=validator.registry.offline_id(player)
                        snapshots['logout/native-'+identity+'.json']=(plugins/'MythicMobs/data/players'/f'{identity}.json').read_text()
                        snapshots['logout/runtime-'+identity+'.yml']=(data/'player-variables'/f'{identity}.yml').read_text()
                    validator.disk_values(snapshots)
                    break
                except (ValueError,KeyError,FileNotFoundError):
                    if time.monotonic()>deadline:raise
                    time.sleep(.1)
            (server/'logout').mkdir()
            for name,text in snapshots.items():(server/name).write_text(text)
            process.stdin.write('stop\n');process.stdin.flush();process.wait(timeout=30)
            if process.returncode:raise RuntimeError('Paper shutdown failed')
            text=redact(latest.read_text(errors='replace'));(server/'server.log').write_text(text)
            report['observations']=[line for line in text.splitlines() if 'PLAYER_LIFE|' in line or 'PLAYER_LIFE_HOOK|' in line]
        finally:
            if client is not None and client.poll() is None:client.terminate();client.wait(timeout=10)
            if process.poll() is None:
                process.stdin.write('stop\n');process.stdin.flush()
                try:process.wait(timeout=30)
                except subprocess.TimeoutExpired:process.kill();process.wait()
    for name in ('stdout.log','client.log'):
        path=server/name;path.write_text(redact(path.read_text(errors='replace')))
    for path in [server/'server.log',server/'client.log',*sorted((server/'logout').iterdir())]:
        report['files'].append({'path':str(path.relative_to(server)),'sha256':digest(path)})
    (server/'player-life-report.json').write_text(json.dumps(report,indent=2)+'\n');validator.main(server)
    for name,expected in report['sourceHashes'].items():
        if digest(ROOT/name)!=expected:raise RuntimeError('Source changed during fixture')
    report_name=f'paper-1.21.1-player-life-{datetime.now(timezone.utc):%Y%m%d}-{args.revision}'
    report_path=TOOLS/'reports'/(report_name+'.json');archive_path=TOOLS/'reports'/(report_name+'-logs.zip')
    if report_path.exists() or archive_path.exists():raise RuntimeError('Refusing to overwrite evidence')
    with zipfile.ZipFile(archive_path,'x',zipfile.ZIP_DEFLATED) as archive:
        for entry in report['files']:archive.write(server/entry['path'],entry['path'])
    report['logArchive']=archive_path.name;report['logArchiveSha256']=digest(archive_path)
    report_path.write_text(json.dumps(report,indent=2)+'\n');validator.main(report_path)
    print('Player death/respawn and reconnect comparison passed:',fixture,flush=True)


if __name__=='__main__':main()
