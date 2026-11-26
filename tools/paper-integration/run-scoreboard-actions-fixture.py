"""Observe native and runtime main-scoreboard actions on pinned Paper; no parity promotion."""
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
    fixture = f'srv-paper-scoreboard-actions-{day}-{args.revision}'
    server = ROOT / fixture
    report_path = TOOLS / 'reports' / f'paper-1.21.1-scoreboard-actions-{day}-{args.revision}.json'
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
    probe_jar = plugins / 'HelsteraScoreboardActionsProbe.jar'
    with zipfile.ZipFile(TOOLS / 'random-spawn-fixture/target/random-spawn-paper-fixture-1.0.0.jar') as source, \
            zipfile.ZipFile(probe_jar, 'w', zipfile.ZIP_DEFLATED) as dest:
        for item in source.infolist():
            if item.filename != 'plugin.yml':
                dest.writestr(item, source.read(item.filename))
        dest.writestr('plugin.yml', "name: HelsteraScoreboardActionsProbe\nversion: '1.0'\napi-version: '1.21'\n"
                      'main: dev.helstera.test.randomspawn.ScoreboardActionsProbe\ndepend: [helsteraMobs, MythicMobs]\n')
    mobs = plugins / 'MythicMobs/Mobs'
    mobs.mkdir(parents=True)
    (mobs / 'scoreboard.yml').write_text('HelsteraRadiusAuditMob:\n  Type: COW\n  Options:\n    NoAI: true\n    Despawn: PERSISTENT\n')
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
    case_spec = importlib.util.spec_from_file_location('scoreboard_cases', TOOLS / 'scoreboard-actions-cases.py')
    cases = importlib.util.module_from_spec(case_spec); case_spec.loader.exec_module(cases)
    probe_data = plugins / 'HelsteraScoreboardActionsProbe'; probe_data.mkdir()
    (probe_data / 'cases.yml').write_text(yaml.safe_dump(cases.source_cases(), sort_keys=False))
    source_names = [
        'tools/paper-integration/run-scoreboard-actions-fixture.py',
        'tools/paper-integration/run-player-variable-fixture.py',
        'tools/paper-integration/scoreboard-actions-cases.py',
        'tools/paper-integration/scoreboard-actions-client.js',
        'tools/paper-integration/audit-scoreboard-actions.py',
        'tools/paper-integration/test-scoreboard-actions-audit.py',
        'tools/paper-integration/random-spawn-fixture/src/main/java/dev/helstera/test/randomspawn/ScoreboardActionsProbe.java',
        'tools/paper-integration/random-spawn-fixture/src/main/java/dev/helstera/test/randomspawn/VariablePlaceholderAudit.java',
        'helstera-migration/src/main/java/dev/helstera/migration/importer/MythicMobsImporter.java',
        'helstera-migration/src/main/java/dev/helstera/migration/importer/MythicSpec.java',
        'helstera-ai/src/main/java/dev/helstera/ai/skill/MythicSkillSpec.java',
        'helstera-ai/src/main/java/dev/helstera/ai/skill/SkillService.java',
        'helstera-api/src/main/java/dev/helstera/api/util/MythicScoreboardSpec.java',
        'helstera-api/src/main/java/dev/helstera/api/util/NumericExpression.java',
        'helstera-api/src/main/java/dev/helstera/api/behavior/BehaviorContext.java',
        'helstera-ai/src/main/java/dev/helstera/ai/skill/ScoreboardActions.java',
        'helstera-ai/src/main/java/dev/helstera/ai/skill/Placeholders.java',
        'helstera-ai/src/main/java/dev/helstera/ai/skill/SkillExtras.java']
    sources = [ROOT / name for name in source_names]
    report = {'schemaVersion': 1, 'fixtureDirectory': fixture, 'evidenceMode': 'observation',
              'parityStatus': 'unassessed', 'generatedAtUtc': datetime.now(timezone.utc).isoformat(),
              'pluginSha256': common.digest(plugin), 'paperJarSha256': common.PAPER_HASH,
              'mythicMobsSha256': common.NATIVE_HASH, 'probeJarSha256': common.digest(probe_jar),
              'sourceHashes': {str(p.relative_to(ROOT)): common.digest(p) for p in sources},
              'limitations': ['Native constructors and cast/castAtEntity are invoked directly; full native dispatcher scheduling is not covered.',
                              'Dynamic setTargetScore operands are independently implemented by Helstera; pinned public native substitutes one.',
                              'Uses real main scoreboard and a real player, with source-defined cases and direct/imported runtime paths.',
                              'Scoreboard state is observed before and after each action; no release or complete native parity claim.']}
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
                    client = subprocess.Popen(['node', str(TOOLS / 'scoreboard-actions-client.js')],
                                              cwd=TOOLS, stdout=client_output, stderr=subprocess.STDOUT)
                if 'SCOREBOARD_FAILED|' in log:
                    raise RuntimeError('Scoreboard probe failed')
                if 'SCOREBOARD_SUMMARY|' in log:
                    if client is None: raise RuntimeError('No player client')
                    try: client.wait(timeout=15)
                    except subprocess.TimeoutExpired: raise RuntimeError('Player client did not finish')
                    if client.returncode != 0: raise RuntimeError('Player client failed')
                    break
                if client is not None and client.poll() is not None:
                    raise RuntimeError('Player client exited before completion')
                time.sleep(.25)
            else:
                raise RuntimeError('Scoreboard probe exited or timed out')
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
    probe_data = plugins / 'HelsteraScoreboardActionsProbe'
    snapshots = sorted((probe_data / 'observations').glob('*.yml'))
    rows = [yaml.safe_load(path.read_text()) for path in snapshots]
    if (process.returncode != 0 or len(rows) != len(cases.CASES) * 2
            or f'SCOREBOARD_SUMMARY|observations={len(cases.CASES)*2}|completed=true' not in log):
        failure = failure or 'Incomplete scoreboard observations'
    if len({(row['path'], row['case']) for row in rows}) != len(rows):
        failure = 'Duplicate scoreboard observations'
    report.update(exitCode=process.returncode, clientExitCode=client.returncode if client else None, observationCount=len(rows),
                  validationResult='passed' if failure is None else 'rejected: ' + failure)
    for path, expected in report['sourceHashes'].items():
        if common.digest(ROOT / path) != expected:
            raise RuntimeError('Source changed during fixture: ' + path)
    for path, key in [(server / 'paper.jar', 'paperJarSha256'), (plugins / 'MythicMobs.jar', 'mythicMobsSha256'),
                      (plugins / 'helsteraMobs.jar', 'pluginSha256'), (probe_jar, 'probeJarSha256')]:
        if common.digest(path) != report[key]:
            raise RuntimeError('Artifact changed during fixture')
    files = [server / 'server.log', server / 'client.log', mobs / 'scoreboard.yml', probe_data / 'cases.yml', *snapshots, *sorted(probe_data.glob('identities-*.yml'))]
    report['files'] = [{'path': str(path.relative_to(server)), 'sha256': common.digest(path)} for path in files]
    audit_spec = importlib.util.spec_from_file_location('scoreboard_audit', TOOLS / 'audit-scoreboard-actions.py')
    auditor = importlib.util.module_from_spec(audit_spec); audit_spec.loader.exec_module(auditor)
    if failure is None:
        try: auditor.audit(report, {str(path.relative_to(server)): path.read_bytes() for path in files})
        except (ValueError, KeyError, TypeError) as error:
            failure = str(error); report['validationResult'] = 'rejected: ' + failure
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
