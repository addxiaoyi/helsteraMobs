"""Compare all native 1.21.1 path costs and restore imported costs after a real restart."""
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
PATH_TYPES = ('BLOCKED OPEN WALKABLE WALKABLE_DOOR TRAPDOOR POWDER_SNOW DANGER_POWDER_SNOW '
              'FENCE LAVA WATER WATER_BORDER RAIL UNPASSABLE_RAIL DANGER_FIRE DAMAGE_FIRE '
              'DANGER_OTHER DAMAGE_OTHER DOOR_OPEN DOOR_WOOD_CLOSED DOOR_IRON_CLOSED BREACH '
              'LEAVES STICKY_HONEY COCOA DAMAGE_CAUTIOUS DANGER_TRAPDOOR').split()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--revision', required=True)
    parser.add_argument('--cache-directory', required=True, type=Path)
    parser.add_argument('--skip-build', action='store_true')
    args = parser.parse_args()
    if not re.fullmatch(r'r[1-9][0-9]*', args.revision):
        raise ValueError('Invalid revision')
    day = datetime.now(timezone.utc).strftime('%Y%m%d')
    fixture = f'srv-paper-pathfinding-malus-{day}-{args.revision}'
    server = ROOT / fixture
    report_path = TOOLS / 'reports' / f'paper-1.21.1-pathfinding-malus-{day}-{args.revision}.json'
    archive_path = report_path.with_name(report_path.stem + '-logs.zip')
    if server.exists() or report_path.exists() or archive_path.exists():
        raise ValueError('Refusing to overwrite fixture or evidence')
    paper = ROOT / 'srv-1.21.1/paper.jar'
    native = TOOLS / '.external-cache/MythicMobs-5.7.2-FTWvGuE5.jar'
    plugin = ROOT / 'helstera-plugin/target/helsteraMobs-1.0.0-full.jar'
    if common.digest(paper) != common.PAPER_HASH or common.digest(native) != common.NATIVE_HASH:
        raise ValueError('Wrong pinned dependencies')
    if not args.skip_build:
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
    probe_jar = plugins / 'HelsteraPathfindingMalusProbe.jar'
    with zipfile.ZipFile(TOOLS / 'random-spawn-fixture/target/random-spawn-paper-fixture-1.0.0.jar') as source, \
            zipfile.ZipFile(probe_jar, 'w', zipfile.ZIP_DEFLATED) as dest:
        if 'dev/helstera/test/randomspawn/PathfindingMalusProbe.class' not in source.namelist():
            raise ValueError('Build the current fixture sources before using --skip-build')
        for item in source.infolist():
            if item.filename != 'plugin.yml':
                dest.writestr(item, source.read(item.filename))
        dest.writestr('plugin.yml', "name: HelsteraPathfindingMalusProbe\nversion: '1.0'\napi-version: '1.21'\n"
                      'main: dev.helstera.test.randomspawn.PathfindingMalusProbe\ndepend: [helsteraMobs, MythicMobs]\n')
    mobs = plugins / 'MythicMobs/Mobs'
    mobs.mkdir(parents=True)
    expected = {name: {'WATER': -1, 'DANGER_FIRE': 0, 'DAMAGE_FIRE': 12.5}.get(name, i + 1.25)
                for i, name in enumerate(PATH_TYPES)}
    mob_source = {'MalusNative': {'Type': 'COW', 'Options': {'NoAI': True, 'NoGravity': True, 'Despawn': 'PERSISTENT'},
                                'AIPathfindingMalus': [f'{name} {value}' for name, value in expected.items()]},
                  'MalusMap': {'Type': 'COW', 'Options': {'NoAI': True, 'NoGravity': True},
                               'AIPathfindingMalus': {'WATER': -1, 'FIRE': 0}}}
    source_path = mobs / 'pathfinding-malus.yml'
    source_path.write_text(yaml.safe_dump(mob_source, sort_keys=False))
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
    probe_data = plugins / 'HelsteraPathfindingMalusProbe'
    probe_data.mkdir()
    sources = [Path(__file__).resolve(), TOOLS / 'run-player-variable-fixture.py',
               TOOLS / 'random-spawn-fixture/src/main/java/dev/helstera/test/randomspawn/PathfindingMalusProbe.java',
               ROOT / 'helstera-migration/src/main/java/dev/helstera/migration/importer/MythicMobsImporter.java',
               ROOT / 'helstera-api/src/main/java/dev/helstera/api/util/MythicPathfindingMalus.java',
               ROOT / 'helstera-runtime/src/main/java/dev/helstera/runtime/instance/PaperPathfindingMalus.java',
               ROOT / 'helstera-plugin/src/main/java/dev/helstera/plugin/HelsteraPlugin.java']
    report = {'schemaVersion': 1, 'fixtureDirectory': fixture, 'evidenceMode': 'observation',
              'parityStatus': 'unassessed', 'generatedAtUtc': datetime.now(timezone.utc).isoformat(),
              'pluginSha256': common.digest(plugin), 'paperJarSha256': common.PAPER_HASH,
              'mythicMobsSha256': common.NATIVE_HASH, 'probeJarSha256': common.digest(probe_jar),
              'sourceHashes': {str(p.relative_to(ROOT)): common.digest(p) for p in sources},
              'limitations': ['Covers all 26 native cost-table entries on Paper 1.21.1 and MythicMobs 5.7.2.',
                              'Checks production importer, spawn, fail-closed rejection, and saved entity rebinding after restart.',
                              'Does not claim route-choice equivalence, other server versions, or full exact-artifact release gates.'],
              'stages': []}
    files = [source_path]
    failure = None
    for stage in ('write', 'verify'):
        (probe_data / 'config.yml').write_text(f'stage: {stage}\n')
        latest = server / 'logs/latest.log'
        if latest.exists():
            latest.unlink()
        with (server / f'{stage}.stdout.log').open('w') as output:
            process = subprocess.Popen(['java', '-Xms1G', '-Xmx2G', '-jar', 'paper.jar', 'nogui'], cwd=server,
                                       stdin=subprocess.PIPE, stdout=output, stderr=subprocess.STDOUT, text=True)
            try:
                deadline = time.monotonic() + 240
                while process.poll() is None and time.monotonic() < deadline:
                    log = latest.read_text(errors='replace') if latest.exists() else ''
                    if 'MALUS_FAILED|' in log:
                        raise RuntimeError(f'{stage} probe failed')
                    if f'MALUS_SUMMARY|stage={stage}|' in log:
                        break
                    time.sleep(.25)
                else:
                    raise RuntimeError(f'{stage} probe exited or timed out')
            except RuntimeError as error:
                failure = str(error)
            finally:
                if process.poll() is None:
                    process.stdin.write('stop\n')
                    process.stdin.flush()
                    try:
                        process.wait(timeout=40)
                    except subprocess.TimeoutExpired:
                        process.kill()
                        process.wait()
        log = common.redact(latest.read_text(errors='replace') if latest.exists() else '')
        log_path = server / f'{stage}.server.log'
        log_path.write_text(log)
        files.append(log_path)
        snapshot = probe_data / f'{stage}-observations.yml'
        rows = yaml.safe_load(snapshot.read_text())['observations'] if snapshot.exists() else []
        count = 59 if stage == 'write' else 27
        if (process.returncode != 0 or len(rows) != count
                or f'MALUS_SUMMARY|stage={stage}|observations={count}|passed=true' not in log
                or len({row['id'] for row in rows}) != count
                or any(row['expected'] != row['actual'] or row['matched'] is not True for row in rows)):
            failure = failure or f'{stage} incomplete or mismatching pathfinding observations'
        for name, value in expected.items():
            prefixes = ('native-', 'imported-') if stage == 'write' else ('restart-',)
            for prefix in prefixes:
                if not any(row['id'] == prefix + name and row['actual'] == value for row in rows):
                    failure = failure or f'Missing cost observation {stage}/{prefix}{name}'
        if snapshot.exists():
            files.append(snapshot)
        report['stages'].append({'stage': stage, 'exitCode': process.returncode, 'observationCount': len(rows)})
        if failure:
            break
    report['validationResult'] = 'passed' if failure is None else 'rejected: ' + failure
    for path, expected_hash in report['sourceHashes'].items():
        if common.digest(ROOT / path) != expected_hash:
            raise RuntimeError('Source changed during fixture: ' + path)
    for path, key in [(server / 'paper.jar', 'paperJarSha256'), (plugins / 'MythicMobs.jar', 'mythicMobsSha256'),
                      (plugins / 'helsteraMobs.jar', 'pluginSha256'), (probe_jar, 'probeJarSha256')]:
        if common.digest(path) != report[key]:
            raise RuntimeError('Artifact changed during fixture')
    files.extend(sorted(probe_data.glob('identities.yml')))
    files.extend(sorted((data / 'mobs').glob('*malus*.yml')))
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
