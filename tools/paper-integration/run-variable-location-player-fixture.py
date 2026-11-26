"""Observe ordinary player target location values on pinned Paper; no parity promotion."""
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
    fixture = f'srv-paper-variable-location-player-{day}-{args.revision}'
    server = ROOT / fixture
    report_path = TOOLS / 'reports' / f'paper-1.21.1-variable-location-player-{day}-{args.revision}.json'
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
    probe_jar = plugins / 'HelsteraVariableLocationPlayerProbe.jar'
    with zipfile.ZipFile(TOOLS / 'random-spawn-fixture/target/random-spawn-paper-fixture-1.0.0.jar') as source, \
            zipfile.ZipFile(probe_jar, 'w', zipfile.ZIP_DEFLATED) as dest:
        for item in source.infolist():
            if item.filename != 'plugin.yml':
                dest.writestr(item, source.read(item.filename))
        dest.writestr('plugin.yml', "name: HelsteraVariableLocationPlayerProbe\nversion: '1.0'\napi-version: '1.21'\n"
                      'main: dev.helstera.test.randomspawn.VariableLocationPlayerProbe\ndepend: [helsteraMobs, MythicMobs]\n')
    mobs = plugins / 'MythicMobs/Mobs'
    mobs.mkdir(parents=True)
    (mobs / 'location.yml').write_text('HelsteraRadiusAuditMob:\n  Type: COW\n  Options:\n    NoAI: true\n    Despawn: PERSISTENT\n')
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
    (data / 'skills.yml').write_text('skills: {}\n')
    (data / 'spawners.yml').write_text('spawners: {}\n')
    sources = [Path(__file__).resolve(), TOOLS / 'run-player-variable-fixture.py',
               TOOLS / 'audit-variable-location-player.py', TOOLS / 'test-variable-location-player-audit.py',
               TOOLS / 'variable-location-player-client.js',
               TOOLS / 'random-spawn-fixture/src/main/java/dev/helstera/test/randomspawn/VariableLocationPlayerProbe.java',
               TOOLS / 'random-spawn-fixture/src/main/java/dev/helstera/test/randomspawn/VariablePlaceholderAudit.java',
               ROOT / 'helstera-migration/src/main/java/dev/helstera/migration/importer/MythicMobsImporter.java',
               ROOT / 'helstera-migration/src/main/java/dev/helstera/migration/importer/MythicSpec.java',
               ROOT / 'helstera-ai/src/main/java/dev/helstera/ai/skill/MythicSkillSpec.java',
               ROOT / 'helstera-ai/src/main/java/dev/helstera/ai/skill/SkillService.java',
               ROOT / 'helstera-api/src/main/java/dev/helstera/api/util/MythicVariableReference.java',
               ROOT / 'helstera-api/src/main/java/dev/helstera/api/util/MythicVariableLocation.java',
               ROOT / 'helstera-api/src/main/java/dev/helstera/api/util/VariableLifetime.java',
               ROOT / 'helstera-api/src/main/java/dev/helstera/api/behavior/VariableLocations.java',
               ROOT / 'helstera-ai/src/main/java/dev/helstera/ai/skill/SkillExtras.java',
               ROOT / 'helstera-ai/src/main/java/dev/helstera/ai/skill/MythicPlayerTargeting.java']
    report = {'schemaVersion': 1, 'fixtureDirectory': fixture, 'evidenceMode': 'observation',
              'parityStatus': 'unassessed', 'generatedAtUtc': datetime.now(timezone.utc).isoformat(),
              'pluginSha256': common.digest(plugin), 'paperJarSha256': common.PAPER_HASH,
              'mythicMobsSha256': common.NATIVE_HASH, 'probeJarSha256': common.digest(probe_jar),
              'sourceHashes': {str(p.relative_to(ROOT)): common.digest(p) for p in sources},
              'limitations': ['Covers ordinary player profiles using default MM 5.7.2 aiming, not ActiveMob-wrapped players or threat-backed player models.',
                              'Native constructors and castAtEntity/castAtLocation are invoked directly; full native skill parsing/scheduling is not exercised.',
                              'Both paths share two real players but use separate native mob identities; all observations execute in one main-thread task.',
                              'Transparent materials follow the pinned native default set; external plugin mutations of that set are not covered.',
                              'Snapshots cover case keys in nine candidate registries, not all server state; no parity or release promotion.']}
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
                    client = subprocess.Popen(['node', str(TOOLS / 'variable-location-player-client.js')],
                                              cwd=TOOLS, stdout=client_output, stderr=subprocess.STDOUT)
                if 'VLOCATION_PLAYER_FAILED|' in log:
                    raise RuntimeError('Location probe failed')
                if 'VLOCATION_PLAYER_SUMMARY|' in log:
                    if client is None: raise RuntimeError('No player client')
                    try: client.wait(timeout=15)
                    except subprocess.TimeoutExpired: raise RuntimeError('Player client did not finish')
                    if client.returncode != 0: raise RuntimeError('Player client failed')
                    break
                if client is not None and client.poll() is not None:
                    raise RuntimeError('Player client exited before completion')
                time.sleep(.25)
            else:
                raise RuntimeError('Location probe exited or timed out')
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
    probe_data = plugins / 'HelsteraVariableLocationPlayerProbe'
    snapshots = sorted((probe_data / 'observations').glob('*.yml'))
    rows = [yaml.safe_load(path.read_text()) for path in snapshots]
    if (process.returncode != 0 or len(rows) != 300
            or 'VLOCATION_PLAYER_SUMMARY|observations=300|passed=true' not in log
            or any(row['nativeBefore'] != row['runtimeBefore']
                   or row['nativeAfter'] != row['runtimeAfter']
                   or row['nativeAfterDeadlines'] != row['runtimeAfterDeadlines'] or row['matched'] is not True for row in rows)):
        failure = failure or 'Incomplete or mismatching registry observations'
    if len({(row['path'], row['dispatch'], row['scope'], row['case']) for row in rows}) != len(rows):
        failure = 'Duplicate location observations'
    report.update(exitCode=process.returncode, clientExitCode=client.returncode if client else None, observationCount=len(rows),
                  validationResult='passed' if failure is None else 'rejected: ' + failure)
    for path, expected in report['sourceHashes'].items():
        if common.digest(ROOT / path) != expected:
            raise RuntimeError('Source changed during fixture: ' + path)
    for path, key in [(server / 'paper.jar', 'paperJarSha256'), (plugins / 'MythicMobs.jar', 'mythicMobsSha256'),
                      (plugins / 'helsteraMobs.jar', 'pluginSha256'), (probe_jar, 'probeJarSha256')]:
        if common.digest(path) != report[key]:
            raise RuntimeError('Artifact changed during fixture')
    files = [server / 'server.log', server / 'client.log', mobs / 'location.yml', *snapshots, *sorted(probe_data.glob('identities-*.yml'))]
    report['files'] = [{'path': str(path.relative_to(server)), 'sha256': common.digest(path)} for path in files]
    with zipfile.ZipFile(archive_path, 'x', zipfile.ZIP_DEFLATED) as archive:
        for path in files:
            archive.write(path, str(path.relative_to(server)))
    report.update(logArchive=archive_path.name, logArchiveSha256=common.digest(archive_path))
    report_path.write_text(json.dumps(report, indent=2) + '\n')
    print(report_path, report['validationResult'], flush=True)
    if failure:
        raise RuntimeError(failure)


if __name__ == '__main__':
    main()
