"""Cold-boot base Aura lifecycle, charge, callback and BossBar contract."""
import argparse
from datetime import datetime, timezone
import importlib.util
import json
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time
import zipfile

import yaml

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[2]
TOOLS = ROOT / 'tools/paper-integration'
spec = importlib.util.spec_from_file_location('player_runner', TOOLS / 'run-player-variable-fixture.py')
common = importlib.util.module_from_spec(spec)
spec.loader.exec_module(common)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--revision', required=True)
    parser.add_argument('--cache-directory', required=True, type=Path)
    args = parser.parse_args()
    if not re.fullmatch(r'r[1-9][0-9]*', args.revision):
        raise ValueError('Invalid revision')
    day = datetime.now(timezone.utc).strftime('%Y%m%d')
    fixture = f'srv-paper-aura-lifecycle-{day}-{args.revision}'
    server = ROOT / fixture
    report_path = TOOLS / 'reports' / f'paper-1.21.1-aura-lifecycle-{day}-{args.revision}.json'
    archive_path = report_path.with_name(report_path.stem + '-logs.zip')
    if server.exists() or report_path.exists() or archive_path.exists():
        raise ValueError('Refusing to overwrite fixture or evidence')
    paper = ROOT / 'srv-1.21.1/paper.jar'
    native = TOOLS / '.external-cache/MythicMobs-5.7.2-FTWvGuE5.jar'
    plugin = ROOT / 'helstera-plugin/target/helsteraMobs-1.0.0-full.jar'
    if common.digest(paper) != common.PAPER_HASH or common.digest(native) != common.NATIVE_HASH:
        raise ValueError('Wrong pinned dependencies')
    subprocess.run([str(ROOT / 'mvnw'), '-q', '-f', str(TOOLS / 'random-spawn-fixture/pom.xml'), 'package'],
                   cwd=ROOT, check=True)
    server.mkdir()
    for name in ('cache', 'libraries', 'versions'):
        shutil.copytree(args.cache_directory / name, server / name)
    shutil.copy2(paper, server / 'paper.jar')
    shutil.copy2(TOOLS / 'server.properties', server / 'server.properties')
    (server / 'eula.txt').write_text('eula=true\n')
    plugins = server / 'plugins'
    plugins.mkdir()
    shutil.copy2(plugin, plugins / 'helsteraMobs.jar')
    shutil.copy2(native, plugins / 'MythicMobs.jar')
    probe_jar = plugins / 'HelsteraAuraLifecycleProbe.jar'
    with zipfile.ZipFile(TOOLS / 'random-spawn-fixture/target/random-spawn-paper-fixture-1.0.0.jar') as source, \
            zipfile.ZipFile(probe_jar, 'w', zipfile.ZIP_DEFLATED) as dest:
        for item in source.infolist():
            if item.filename != 'plugin.yml':
                dest.writestr(item, source.read(item.filename))
        dest.writestr('plugin.yml', "name: HelsteraAuraLifecycleProbe\nversion: '1.0'\napi-version: '1.21'\n"
                      'main: dev.helstera.test.randomspawn.AuraLifecycleProbe\ndepend: [helsteraMobs, MythicMobs]\n')
    data = plugins / 'helsteraMobs'
    data.mkdir()
    config = (TOOLS / 'config.yml').read_text()
    config = re.sub(r'(?m)^  (self-test(?:-[A-Za-z0-9-]+)?):\s*true\s*$', r'  \1: false', config)
    config = re.sub(r'(?m)^  trace-triggers:.*$', '  trace-triggers: false', config)
    config = re.sub(r'(?m)^  mythicmobs: false\s*$', '  mythicmobs: true', config)
    if re.search(r'(?m)^web:\s*\n[ \t]+enabled:\s*true', config):
        raise ValueError('Web interface must be disabled')
    (data / 'config.yml').write_text(config)
    for name in ('items.yml', 'loot.yml'):
        shutil.copy2(TOOLS / name, data / name)
    (data / 'skills.yml').write_text(yaml.safe_dump({'skills': {name: {'on-decision': []} for name in ('AuraAuditStart', 'AuraAuditTick', 'AuraAuditEnd')}}, sort_keys=False))
    (data / 'spawners.yml').write_text('spawners: {}\n')
    probe_data = plugins / 'HelsteraAuraLifecycleProbe'
    probe_data.mkdir()
    mobs = plugins / 'MythicMobs/Mobs'
    mobs.mkdir(parents=True)
    (mobs / 'aura.yml').write_text('HelsteraRadiusAuditMob:\n  Type: COW\n  Options:\n    NoAI: true\n    Despawn: PERSISTENT\n')
    shutil.copy2(data / 'skills.yml', server / 'source-skills.yml')
    sources = ['run-aura-lifecycle-fixture.py', 'run-player-variable-fixture.py',
               'validate-aura-lifecycle.py', 'validate-player-variables.py', 'test-aura-lifecycle-evidence.py',
               'aura-lifecycle-client.js',
               'random-spawn-fixture/src/main/java/dev/helstera/test/randomspawn/AuraLifecycleProbe.java',
               'random-spawn-fixture/src/main/java/dev/helstera/test/randomspawn/VariablePlaceholderAudit.java']
    report = {'schemaVersion': 1, 'fixtureDirectory': fixture, 'evidenceMode': 'scoped-runtime',
              'contract': 'base-aura-lifecycle', 'fullAuraParity': False,
              'deathBoundary': 'MONITOR-ignoreCancelled',
              'generatedAtUtc': datetime.now(timezone.utc).isoformat(),
              'pluginSha256': common.digest(plugin), 'paperJarSha256': common.PAPER_HASH,
              'mythicMobsSha256': common.NATIVE_HASH, 'probeJarSha256': common.digest(probe_jar),
              'sourceHashes': {'tools/paper-integration/' + name: common.digest(TOOLS / name) for name in sources}}
    latest = server / 'logs/latest.log'
    failure = None
    client = None
    with (server / 'stdout.log').open('w') as output, (server / 'client.log').open('w') as client_output:
        process = subprocess.Popen(['java', '-Xms1G', '-Xmx2G', '-jar', 'paper.jar', 'nogui'], cwd=server,
                                   stdin=subprocess.PIPE, stdout=output, stderr=subprocess.STDOUT, text=True)
        try:
            deadline = time.monotonic() + 300
            while process.poll() is None and time.monotonic() < deadline:
                log = latest.read_text(errors='replace') if latest.exists() else ''
                if client is None and 'Done (' in log:
                    client = subprocess.Popen(['node', str(TOOLS / 'aura-lifecycle-client.js')],
                                              cwd=TOOLS, stdout=client_output, stderr=subprocess.STDOUT)
                if 'AURALIFE_FAILED|' in log:
                    raise RuntimeError('Aura lifecycle probe failed')
                if 'AURALIFE_SUMMARY|' in log:
                    if client is None: raise RuntimeError('No player client')
                    try: client.wait(timeout=15)
                    except subprocess.TimeoutExpired: raise RuntimeError('Player client did not finish')
                    if client.returncode != 0: raise RuntimeError('Player client failed')
                    break
                if client is not None and client.poll() is not None and client.returncode != 0:
                    raise RuntimeError('Player client exited before completion')
                time.sleep(.25)
            else:
                raise RuntimeError('Aura lifecycle probe exited or timed out')
        except RuntimeError as error:
            failure = str(error)
        finally:
            if client is not None and client.poll() is None:
                client.terminate()
                try: client.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    client.kill(); client.wait()
            if process.poll() is None:
                process.stdin.write('stop\n')
                process.stdin.flush()
                try:
                    process.wait(timeout=40)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait()
    client_log = server / 'client.log'
    client_log.write_text(common.redact(client_log.read_text(errors='replace')))
    log = common.redact(latest.read_text(errors='replace') if latest.exists() else '')
    (server / 'server.log').write_text(log)
    report.update(exitCode=process.returncode, clientExitCode=client.returncode if client else None,
                  observations=[line for line in log.splitlines() if 'AURALIFE_' in line])
    if process.returncode != 0: failure = failure or 'Unclean server shutdown'
    for path, expected in report['sourceHashes'].items():
        if common.digest(ROOT / path) != expected:
            raise RuntimeError('Source changed during fixture: ' + path)
    for path, key in [(server / 'paper.jar', 'paperJarSha256'), (plugins / 'MythicMobs.jar', 'mythicMobsSha256'),
                      (plugins / 'helsteraMobs.jar', 'pluginSha256'), (probe_jar, 'probeJarSha256')]:
        if common.digest(path) != report[key]:
            raise RuntimeError('Artifact changed during fixture')
    files = [server / 'server.log', server / 'client.log', server / 'source-skills.yml']
    report['files'] = [{'path': str(path.relative_to(server)), 'sha256': common.digest(path)} for path in files]
    spec = importlib.util.spec_from_file_location('aura_validator', TOOLS / 'validate-aura-lifecycle.py')
    validator = importlib.util.module_from_spec(spec); spec.loader.exec_module(validator)
    if failure is None:
        try: validator.validate(report, {path.name: path.read_bytes() for path in files})
        except (ValueError, KeyError) as error: failure = str(error)
    if failure:
        report['evidenceMode'] = 'observation'; report['validationResult'] = 'rejected: ' + failure
    with zipfile.ZipFile(archive_path, 'x', zipfile.ZIP_DEFLATED) as archive:
        for path in files:
            archive.write(path, str(path.relative_to(server)))
    report.update(logArchive=archive_path.name, logArchiveSha256=common.digest(archive_path))
    report_path.write_text(json.dumps(report, indent=2) + '\n')
    print(report_path, report.get('validationResult', 'passed'), flush=True)
    if failure:
        raise RuntimeError(failure)


if __name__ == '__main__':
    main()
