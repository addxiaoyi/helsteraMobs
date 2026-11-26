"""Observe dropped Item queries, filters and real mutations on pinned Paper."""
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
    fixture = f'srv-paper-item-targeter-{day}-{args.revision}'
    server = ROOT / fixture
    report_path = TOOLS / 'reports' / f'paper-1.21.1-item-targeter-{day}-{args.revision}.json'
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
    probe_jar = plugins / 'HelsteraItemTargeterProbe.jar'
    with zipfile.ZipFile(TOOLS / 'random-spawn-fixture/target/random-spawn-paper-fixture-1.0.0.jar') as source, \
            zipfile.ZipFile(probe_jar, 'w', zipfile.ZIP_DEFLATED) as dest:
        for item in source.infolist():
            if item.filename != 'plugin.yml':
                dest.writestr(item, source.read(item.filename))
        dest.writestr('plugin.yml', "name: HelsteraItemTargeterProbe\nversion: '1.0'\napi-version: '1.21'\n"
                      'main: dev.helstera.test.randomspawn.ItemTargeterProbe\ndepend: [helsteraMobs, MythicMobs]\n')
    mobs = plugins / 'MythicMobs/Mobs'
    mobs.mkdir(parents=True)
    (mobs / 'location.yml').write_text('HelsteraRadiusAuditMob:\n  Type: COW\n  ThreatTable:\n    Enabled: true\n    DecayUnreachable: false\n  Options:\n    NoAI: true\n    Despawn: PERSISTENT\n')
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
    source_names = {
        'tools/paper-integration/run-item-targeter-fixture.py',
        'tools/paper-integration/run-player-variable-fixture.py',
        'tools/paper-integration/audit-item-targeter.py',
        'tools/paper-integration/test-item-targeter-audit.py',
        'tools/paper-integration/item-targeter-client.js',
        'tools/paper-integration/random-spawn-fixture/src/main/java/dev/helstera/test/randomspawn/ItemTargeterProbe.java',
        'tools/paper-integration/random-spawn-fixture/src/main/java/dev/helstera/test/randomspawn/VariablePlaceholderAudit.java',
        'helstera-api/src/main/java/dev/helstera/api/behavior/Targeters.java',
        'helstera-api/src/main/java/dev/helstera/api/behavior/InheritedTargetLocations.java',
        'helstera-api/src/main/java/dev/helstera/api/behavior/NativeEntityTargetFilters.java',
        'helstera-api/src/main/java/dev/helstera/api/behavior/BehaviorContext.java',
        'helstera-api/src/main/java/dev/helstera/api/behavior/SkillTreeContext.java',
        'helstera-ai/src/main/java/dev/helstera/ai/skill/SkillService.java',
        'helstera-ai/src/main/java/dev/helstera/ai/skill/Placeholders.java',
        'helstera-ai/src/main/java/dev/helstera/ai/skill/SkillExtras.java',
        'helstera-ai/src/main/java/dev/helstera/ai/AiManager.java',
        'helstera-api/src/main/java/dev/helstera/api/behavior/ManagedMobIdentity.java',
        'helstera-api/src/main/java/dev/helstera/api/behavior/MobRelations.java',
        'helstera-api/src/main/java/dev/helstera/api/behavior/Factions.java',
        'helstera-api/src/main/java/dev/helstera/api/behavior/FactionTable.java',
        'helstera-migration/src/main/java/dev/helstera/migration/importer/MythicMobsImporter.java',
    }
    source_names.update(str(path.relative_to(ROOT)) for path in
                        (ROOT / 'helstera-api/src/main/java/dev/helstera/api/behavior').glob('*Targeter*.java'))
    report = {'schemaVersion': 1, 'fixtureDirectory': fixture, 'evidenceMode': 'observation',
              'parityStatus': 'unassessed', 'generatedAtUtc': datetime.now(timezone.utc).isoformat(),
              'pluginSha256': common.digest(plugin), 'paperJarSha256': common.PAPER_HASH,
              'mythicMobsSha256': common.NATIVE_HASH, 'probeJarSha256': common.digest(probe_jar),
              'sourceHashes': {name: common.digest(ROOT / name) for name in sorted(source_names)},
              'limitations': ['Covers Item radius/origin queries, filters and actual TagAdd mutations on the pinned server.',
                              'Native targeter constructors are invoked directly; full native metaskill scheduling is outside this slice.',
                              'One online client supplies join evidence; item rendering, pickup and full dispatcher scheduling are outside this slice.',
                              'Inline targeter guards are a Helstera extension: the public native build ignores them.',
                              'Results are scoped observations and do not establish whole-catalog parity or release readiness.']}
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
                    client = subprocess.Popen(['node', str(TOOLS / 'item-targeter-client.js')],
                                              cwd=TOOLS, stdout=client_output, stderr=subprocess.STDOUT)
                if 'ITEMS_FAILED|' in log:
                    raise RuntimeError('Location probe failed')
                if 'ITEMS_SUMMARY|' in log:
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
    probe_data = plugins / 'HelsteraItemTargeterProbe'
    snapshot = probe_data / 'observations.yml'
    rows = yaml.safe_load(snapshot.read_text()).get('observations', []) if snapshot.exists() else []
    summary = re.findall(r'ITEMS_SUMMARY\|observations=(\d+)\|passed=true', log)
    if (process.returncode != 0 or not rows or summary != [str(len(rows))]
            or any(row.get('nativeAccepted') is not True or row.get('runtimeAccepted') is not True for row in rows)):
        failure = failure or 'Incomplete or mismatching targeter observations'
    if len({(row['path'], row['case']) for row in rows}) != len(rows):
        failure = 'Duplicate targeter observations'
    report.update(exitCode=process.returncode, clientExitCode=client.returncode if client else None, observationCount=len(rows),
                  validationResult='passed' if failure is None else 'rejected: ' + failure)
    for path, expected in report['sourceHashes'].items():
        if common.digest(ROOT / path) != expected:
            raise RuntimeError('Source changed during fixture: ' + path)
    for path, key in [(server / 'paper.jar', 'paperJarSha256'), (plugins / 'MythicMobs.jar', 'mythicMobsSha256'),
                      (plugins / 'helsteraMobs.jar', 'pluginSha256'), (probe_jar, 'probeJarSha256')]:
        if common.digest(path) != report[key]:
            raise RuntimeError('Artifact changed during fixture')
    files = [server / 'server.log', server / 'client.log', mobs / 'location.yml']
    if snapshot.exists():
        files.append(snapshot)
    report['files'] = [{'path': str(path.relative_to(server)), 'sha256': common.digest(path)} for path in files]
    with zipfile.ZipFile(archive_path, 'x', zipfile.ZIP_DEFLATED) as archive:
        for path in files:
            archive.write(path, str(path.relative_to(server)))
    report.update(logArchive=archive_path.name, logArchiveSha256=common.digest(archive_path))
    report_path.write_text(json.dumps(report, indent=2) + '\n')
    print(report_path, report['validationResult'], flush=True)
    if failure:
        raise RuntimeError(failure)
    audit = subprocess.run([sys.executable, str(TOOLS / 'audit-item-targeter.py'), str(report_path)])
    if audit.returncode:
        report['validationResult'] = 'rejected: independent semantic audit'
        report_path.write_text(json.dumps(report, indent=2) + '\n')
        raise RuntimeError('Independent item-targeter audit rejected the observations')


if __name__ == '__main__':
    main()
