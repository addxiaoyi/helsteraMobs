"""Real managed instances and native variable lifetime across world/chunk unloads."""
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
digest, redact = common.digest, common.redact
validator = module('world_validator','validate-world-variable-life.py')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--revision', required=True)
    parser.add_argument('--cache-directory', required=True)
    args = parser.parse_args()
    import yaml
    if not 6 <= int(yaml.__version__.split('.')[0]) < 7: raise RuntimeError('Install requirements-player-variables.txt')
    if not re.fullmatch(r'r[1-9][0-9]*', args.revision): raise ValueError('Invalid revision')
    fixture = f'srv-paper-world-variable-life-{datetime.now(timezone.utc):%Y%m%d}-{args.revision}'
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
    probe_data = plugins/'HelsteraWorldVariableLifeProbe'; probe_data.mkdir()
    probe_jar = plugins/'HelsteraWorldVariableLifeProbe.jar'
    with zipfile.ZipFile(TOOLS/'random-spawn-fixture/target/random-spawn-paper-fixture-1.0.0.jar') as source, zipfile.ZipFile(probe_jar, 'w', zipfile.ZIP_DEFLATED) as dest:
        for item in source.infolist():
            if item.filename != 'plugin.yml': dest.writestr(item, source.read(item.filename))
        dest.writestr('plugin.yml', "name: HelsteraWorldVariableLifeProbe\nversion: '1.0'\napi-version: '1.21'\nmain: dev.helstera.test.randomspawn.WorldVariableLifeProbe\ndepend: [helsteraMobs, MythicMobs]\n")
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
    (data/'mobs').mkdir()
    (data/'mobs/world_variable_probe.yml').write_text('name: World Variable Probe\nmodel: example/emberling\nentity:\n  type: COW\n  health: 20\n  silent: true\nmob:\n  despawn: PERSISTENT\n  persistent: true\n')
    sources=[Path(__file__).resolve(), TOOLS/'validate-world-variable-life.py', TOOLS/'validate-player-variables.py',
             TOOLS/'run-player-variable-fixture.py', TOOLS/'random-spawn-fixture/src/main/java/dev/helstera/test/randomspawn/WorldVariableLifeProbe.java',
             TOOLS/'random-spawn-fixture/src/main/java/dev/helstera/test/randomspawn/VariablePlaceholderAudit.java']
    report={'schemaVersion':1,'fixtureDirectory':fixture,'evidenceMode':'comparison','generatedAtUtc':datetime.now(timezone.utc).isoformat(),
            'pluginSha256':digest(plugin),'paperJarSha256':common.PAPER_HASH,'mythicMobsSha256':common.NATIVE_HASH,'probeJarSha256':digest(probe_jar),
            'sourceHashes':{str(p.relative_to(ROOT)):digest(p) for p in sources},'observations':[],'files':[]}
    latest = server/'logs/latest.log'
    with (server/'stdout.log').open('w') as output:
        process = subprocess.Popen(['java','-Xms1G','-Xmx2G','-jar','paper.jar','nogui'], cwd=server, stdin=subprocess.PIPE, stdout=output, stderr=subprocess.STDOUT, text=True)
        try:
            deadline=time.monotonic()+180
            while time.monotonic()<deadline and process.poll() is None:
                text=latest.read_text(errors='replace') if latest.exists() else ''
                if 'WORLD_VAR_FAILED|' in text:raise RuntimeError('World lifecycle probe failed; inspect retained log')
                if 'WORLD_VAR_SUMMARY|observations=256|passed=true' in text:break
                time.sleep(.25)
            else:raise RuntimeError('World lifecycle probe timed out')
        finally:
            if process.poll() is None:
                process.stdin.write('stop\n');process.stdin.flush()
                try:process.wait(timeout=30)
                except subprocess.TimeoutExpired:process.kill();process.wait()
            if latest.exists():(server/'server.log').write_text(redact(latest.read_text(errors='replace')))
    if process.returncode:raise RuntimeError('Paper shutdown failed')
    snapshots=server/'snapshots';shutil.copytree(probe_data/'snapshots',snapshots)
    shutil.copy2(data/'scoped-variables.yml',server/'shutdown-scoped.yml')
    report['observations']=[l for l in (server/'server.log').read_text().splitlines() if 'WORLD_VAR|' in l]
    for path in [server/'server.log',server/'shutdown-scoped.yml',*sorted(snapshots.iterdir())]:
        report['files'].append({'path':str(path.relative_to(server)),'sha256':digest(path)})
    for name,expected in report['sourceHashes'].items():
        if digest(ROOT/name)!=expected:raise RuntimeError('Source changed during fixture')
    (server/'world-variable-report.json').write_text(json.dumps(report,indent=2)+'\n');validator.main(server)
    report_name=f'paper-1.21.1-world-variable-life-{datetime.now(timezone.utc):%Y%m%d}-{args.revision}'
    report_path=TOOLS/'reports'/(report_name+'.json');archive_path=TOOLS/'reports'/(report_name+'-logs.zip')
    if report_path.exists() or archive_path.exists():raise RuntimeError('Refusing to overwrite evidence')
    with zipfile.ZipFile(archive_path,'x',zipfile.ZIP_DEFLATED) as archive:
        for entry in report['files']:archive.write(server/entry['path'],entry['path'])
    report['logArchive']=archive_path.name;report['logArchiveSha256']=digest(archive_path)
    report_path.write_text(json.dumps(report,indent=2)+'\n');validator.main(report_path)
    print('World variable lifecycle passed:',fixture)


if __name__=='__main__':main()
