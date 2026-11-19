"""Real client reconnects and three Paper boots for native player registry persistence."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time
import zipfile
from datetime import datetime, timezone

ROOT = Path(__file__).resolve().parents[2]
TOOLS = ROOT / 'tools/paper-integration'
PAPER_HASH = '39bd8c00b9e18de91dcabd3cc3dcfa5328685a53b7187a2f63280c22e2d287b9'
NATIVE_HASH = '9672457cafc56ff841606bfbb710947329c08720ffc207746b60130d43bf902f'


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def redact(text):
    for pattern in (r'(?i)([?&]token=)[^&\s"\'<>]+', r'(?i)(__cf_bm=)[^;\s"\'<>]+',
                    r'(令牌[ \t]*[:：][ \t]*)[A-Za-z0-9._~-]+', r'(?i)(\bauthorization\s*:\s*bearer\s+)[^\s"\'<>]+'):
        text = re.sub(pattern, r'\1[REDACTED]', text)
    return text


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--revision', required=True)
    parser.add_argument('--cache-directory', required=True)
    parser.add_argument('--observe-only', action='store_true')
    args = parser.parse_args()
    # Fail before launching Paper if the independent archive validator is unavailable.
    import yaml
    if not 6 <= int(yaml.__version__.split('.')[0]) < 7:
        raise RuntimeError('Install requirements-player-variables.txt with this Python interpreter')
    if not re.fullmatch(r'r[1-9][0-9]*', args.revision): raise ValueError('Invalid revision')
    mode = 'native' if args.observe_only else 'comparison'
    fixture = f'srv-paper-player-variables-{mode}-{datetime.now(timezone.utc):%Y%m%d}-{args.revision}'
    server = ROOT / fixture
    if server.exists(): raise ValueError('Refusing to reuse fixture')
    paper = ROOT / 'srv-1.21.1/paper.jar'
    native = TOOLS / '.external-cache/MythicMobs-5.7.2-FTWvGuE5.jar'
    plugin = ROOT / 'helstera-plugin/target/helsteraMobs-1.0.0-full.jar'
    assert digest(paper) == PAPER_HASH and digest(native) == NATIVE_HASH
    subprocess.run(['mvn.cmd' if os.name == 'nt' else 'mvn', '-q', '-f', str(TOOLS / 'random-spawn-fixture/pom.xml'), 'package'], check=True, cwd=ROOT)
    server.mkdir()
    for name in ('cache', 'libraries', 'versions'):
        shutil.copytree(Path(args.cache_directory) / name, server / name)
    shutil.copy2(paper, server / 'paper.jar')
    shutil.copy2(TOOLS / 'server.properties', server / 'server.properties')
    (server / 'eula.txt').write_text('eula=true\n')
    plugins = server / 'plugins'; plugins.mkdir()
    shutil.copy2(plugin, plugins / 'helsteraMobs.jar'); shutil.copy2(native, plugins / 'MythicMobs.jar')
    probe_data = plugins / 'HelsteraPlayerVariableProbe'; probe_data.mkdir()
    probe_jar = plugins / 'HelsteraPlayerVariableProbe.jar'
    with zipfile.ZipFile(TOOLS / 'random-spawn-fixture/target/random-spawn-paper-fixture-1.0.0.jar') as source, zipfile.ZipFile(probe_jar, 'w', zipfile.ZIP_DEFLATED) as dest:
        for item in source.infolist():
            if item.filename != 'plugin.yml': dest.writestr(item, source.read(item.filename))
        dest.writestr('plugin.yml', "name: HelsteraPlayerVariableProbe\nversion: '1.0'\napi-version: '1.21'\nmain: dev.helstera.test.randomspawn.PlayerVariablePersistenceProbe\ndepend: [helsteraMobs, MythicMobs]\n")
    mobs = plugins / 'MythicMobs/Mobs'; mobs.mkdir(parents=True)
    (mobs / 'variables.yml').write_text('HelsteraRadiusAuditMob:\n  Type: COW\n  Options:\n    NoAI: true\n    Despawn: PERSISTENT\n')
    data = plugins / 'helsteraMobs'; data.mkdir()
    config = (TOOLS / 'config.yml').read_text()
    config = re.sub(r'(?m)^  (self-test(?:-[A-Za-z0-9-]+)?):\s*true\s*$', r'  \1: false', config)
    config = re.sub(r'(?m)^  trace-triggers:.*$', '  trace-triggers: false', config)
    config = re.sub(r'(?m)^  mythicmobs: false\s*$', '  mythicmobs: true', config)
    assert not re.search(r'(?m)^web:\s*\n[ \t]+enabled:\s*true', config)
    (data / 'config.yml').write_text(config)
    for name in ('items.yml', 'loot.yml'): shutil.copy2(TOOLS / name, data / name)
    (data / 'skills.yml').write_text('skills: {}\n'); (data / 'spawners.yml').write_text('spawners: {}\n')
    report = {'schemaVersion': 1, 'fixtureDirectory': fixture, 'evidenceMode': mode, 'generatedAtUtc': datetime.now(timezone.utc).isoformat(),
              'pythonVersion': sys.version.split()[0], 'pyyamlVersion': yaml.__version__, 'pluginSha256': digest(plugin),
              'paperJarSha256': PAPER_HASH, 'mythicMobsSha256': NATIVE_HASH, 'probeJarSha256': digest(probe_jar),
              'sourceHashes': {str(p.relative_to(ROOT)): digest(p) for p in [Path(__file__).resolve(), TOOLS / 'player-variable-client.js',
                  TOOLS / 'validate-player-variables.py', TOOLS / 'requirements-player-variables.txt',
                  TOOLS / 'random-spawn-fixture/src/main/java/dev/helstera/test/randomspawn/PlayerVariablePersistenceProbe.java',
                  TOOLS / 'random-spawn-fixture/src/main/java/dev/helstera/test/randomspawn/VariablePlaceholderAudit.java']},
              'stages': [], 'files': []}
    for stage in ('write', 'verify', 'final'):
        (probe_data / 'config.yml').write_text(f'stage: {stage}\nobserve-only: {str(args.observe_only).lower()}\n')
        latest = server / 'logs/latest.log'
        if latest.exists(): latest.unlink()  # Prior complete stage is already retained below.
        client = None
        with (server / f'{stage}.stdout.log').open('w') as output, (server / f'{stage}.client.log').open('w') as client_output:
            process = subprocess.Popen(['java', '-Xms1G', '-Xmx2G', '-jar', 'paper.jar', 'nogui'], cwd=server, stdin=subprocess.PIPE,
                                       stdout=output, stderr=subprocess.STDOUT, text=True)
            try:
                deadline = time.monotonic() + 120
                while time.monotonic() < deadline and process.poll() is None:
                    if latest.exists() and 'Done (' in latest.read_text(errors='replace'): break
                    time.sleep(.25)
                else: raise RuntimeError('Paper did not start')
                client = subprocess.Popen(['node', str(TOOLS / 'player-variable-client.js'), stage], cwd=TOOLS, stdout=client_output, stderr=subprocess.STDOUT)
                deadline = time.monotonic() + 170
                while time.monotonic() < deadline and process.poll() is None:
                    text = latest.read_text(errors='replace')
                    if 'PLAYER_VAR_SUMMARY|passed=false' in text: raise RuntimeError('Probe failed; retained log has details')
                    if stage == 'verify' and '"event":"ready-online"' in (server / f'{stage}.client.log').read_text(errors='replace'): break
                    if client.poll() is not None:
                        if client.returncode: raise RuntimeError('Client failed')
                        break
                    time.sleep(.25)
                else: raise RuntimeError('Player probe did not complete')
                count = 148 if stage == 'final' else 296
                text = latest.read_text(errors='replace')
                assert f'PLAYER_VAR_SUMMARY|stage={stage}|observations={count}|passed=true' in text
                rows = [line for line in text.splitlines() if 'PLAYER_VAR|' in line or 'PLAYER_VAR_HOOK|' in line]
                assert len(rows) == count and (args.observe_only or all(line.endswith(('|matched=true', '|passed=true')) for line in rows))
                # A real logout must write native player files before the server is stopped.
                time.sleep(2)
                if 'PLAYER_VAR_SUMMARY|passed=false' in latest.read_text(errors='replace'): raise RuntimeError('Quit lifecycle failed')
                snapshots = server / f'{stage}-logout'; snapshots.mkdir()
                native_files = list((plugins / 'MythicMobs/data/players').glob('*.json'))
                native_files = [p for p in native_files if json.loads(p.read_text())['name'] in ('VariableOwner', 'VariablePeer')]
                if len(native_files) != 2: raise RuntimeError('Expected both native player disk snapshots before shutdown')
                for p in native_files: shutil.copy2(p, snapshots / ('native-' + p.name))
                runtime_files = list((data / 'player-variables').glob('*.yml'))
                if not args.observe_only and len(runtime_files) != 2: raise RuntimeError('Both player files must exist before shutdown')
                for p in runtime_files: shutil.copy2(p, snapshots / ('runtime-' + p.name))
                report['stages'].append({'stage': stage, 'observations': rows, 'passed': True})
                process.stdin.write('stop\n'); process.stdin.flush(); process.wait(timeout=30)
                if process.returncode: raise RuntimeError('Paper did not stop successfully')
                client.wait(timeout=15)
                if client.returncode: raise RuntimeError('Client failed during shutdown')
                for p in native_files: shutil.copy2(p, snapshots / ('shutdown-native-' + p.name))
                for p in runtime_files: shutil.copy2(p, snapshots / ('shutdown-runtime-' + p.name))
                (server / f'{stage}.server.log').write_text(redact(latest.read_text(errors='replace')))
                shutil.copy2(probe_data / 'identities.yml', snapshots / 'identities.yml')
                if (data / 'scoped-variables.yml').exists(): shutil.copy2(data / 'scoped-variables.yml', snapshots / 'scoped-variables.yml')
            finally:
                if client is not None and client.poll() is None: client.terminate(); client.wait(timeout=10)
                if process.poll() is None:
                    process.stdin.write('stop\n'); process.stdin.flush()
                    try: process.wait(timeout=30)
                    except subprocess.TimeoutExpired: process.kill(); process.wait()
        for p in (server / f'{stage}.stdout.log', server / f'{stage}.client.log'):
            p.write_text(redact(p.read_text(errors='replace')))
        for p in [server / f'{stage}.server.log', server / f'{stage}.client.log', *snapshots.iterdir()]:
            report['files'].append({'path': str(p.relative_to(server)), 'sha256': digest(p)})
    (server / 'player-variable-report.json').write_text(json.dumps(report, indent=2) + '\n')
    if not args.observe_only:
        subprocess.run([sys.executable, str(TOOLS / 'validate-player-variables.py'), str(server)], check=True)
        for relative, expected in report['sourceHashes'].items():
            if digest(ROOT / relative) != expected: raise RuntimeError('Fixture source changed during execution')
        report_name = f'paper-1.21.1-player-variables-{datetime.now(timezone.utc):%Y%m%d}-{args.revision}'
        archive_path = TOOLS / 'reports' / (report_name + '-logs.zip')
        report_path = archive_path.with_name(report_name + '.json')
        if report_path.exists() or archive_path.exists(): raise RuntimeError('Refusing to overwrite archived evidence')
        with zipfile.ZipFile(archive_path, 'x', zipfile.ZIP_DEFLATED) as archive:
            for entry in report['files']: archive.write(server / entry['path'], entry['path'])
        report['logArchive'] = archive_path.name
        report['logArchiveSha256'] = digest(archive_path)
        report_path.write_text(json.dumps(report, indent=2) + '\n')
        subprocess.run([sys.executable, str(TOOLS / 'validate-player-variables.py'), str(report_path)], check=True)
    print(f'Player variable {mode} fixture passed: {fixture} (740 observations)')


if __name__ == '__main__': main()
