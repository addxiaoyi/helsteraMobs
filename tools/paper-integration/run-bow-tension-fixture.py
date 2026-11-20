"""Compare Helstera and Mythic BowTension conditions against real Paper bow shots."""
import argparse
from datetime import datetime, timezone
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time
import zipfile

ROOT = Path(__file__).resolve().parents[2]
TOOLS = ROOT / 'tools/paper-integration'
FIXTURE = TOOLS / 'bow-tension-fixture'
PAPER_HASH = '39bd8c00b9e18de91dcabd3cc3dcfa5328685a53b7187a2f63280c22e2d287b9'
MYTHIC_HASH = '9672457cafc56ff841606bfbb710947329c08720ffc207746b60130d43bf902f'
SOURCES = [
    Path(__file__).resolve(), TOOLS / 'bow-tension-client.js',
    TOOLS / 'validate-bow-tension-evidence.py', TOOLS / 'test-bow-tension-evidence.py',
    FIXTURE / 'pom.xml', FIXTURE / 'src/main/resources/plugin.yml',
    FIXTURE / 'src/main/java/dev/helstera/test/bowtension/BowTensionProbe.java',
]


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def source_hashes():
    return {str(path.relative_to(ROOT)): digest(path) for path in SOURCES}


def redact(text):
    patterns = (
        (r'(?i)([?&]token=)[^&\s"\'<>]+', r'\1[REDACTED]'),
        (r'(?i)(__cf_bm=)[^;\s"\'<>]+', r'\1[REDACTED]'),
        (r'(令牌[ \t]*[:：][ \t]*)[A-Za-z0-9._~-]+', r'\1[REDACTED]'),
        (r'(?i)(\bauthorization\s*:\s*bearer\s+)[^\s"\'<>]+', r'\1[REDACTED]'),
    )
    for pattern, replacement in patterns:
        text = re.sub(pattern, replacement, text)
    return text


def wait_for(predicate, process, timeout, message):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline and process.poll() is None:
        if predicate():
            return
        time.sleep(.25)
    if process.poll() is not None:
        raise RuntimeError(f'{message}; Paper exited with {process.returncode}')
    raise RuntimeError(message)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--revision', required=True)
    parser.add_argument('--cache-directory', required=True)
    args = parser.parse_args()
    if not re.fullmatch(r'r[1-9][0-9]*', args.revision):
        raise ValueError('Revision must look like r1')
    day = datetime.now(timezone.utc).strftime('%Y%m%d')
    fixture_name = f'srv-paper-bow-tension-{day}-{args.revision}'
    server = ROOT / fixture_name
    report_path = TOOLS / 'reports' / f'paper-1.21.1-bow-tension-strict-{day}-{args.revision}.json'
    archive_path = TOOLS / 'reports' / f'paper-1.21.1-bow-tension-strict-{day}-{args.revision}-logs.zip'
    if server.exists() or report_path.exists() or archive_path.exists():
        raise ValueError('Refusing to overwrite fixture or evidence')

    paper = ROOT / 'srv-1.21.1/paper.jar'
    mythic = TOOLS / '.external-cache/MythicMobs-5.7.2-FTWvGuE5.jar'
    plugin = ROOT / 'helstera-plugin/target/helsteraMobs-1.0.0-full.jar'
    if digest(paper) != PAPER_HASH or digest(mythic) != MYTHIC_HASH:
        raise ValueError('Paper or MythicMobs does not match the pinned artifact')
    if not plugin.is_file():
        raise ValueError('Build the full Helstera plugin before running this fixture')
    captured_source_hashes = source_hashes()
    cache = Path(args.cache_directory).resolve()
    for name in ('cache', 'libraries', 'versions'):
        if not (cache / name).is_dir():
            raise ValueError(f'Missing Paper runtime cache directory: {name}')

    subprocess.run([str(ROOT / 'mvnw'), '-q', '-f', str(FIXTURE / 'pom.xml'), 'package'],
                   check=True, cwd=ROOT)
    probe = FIXTURE / 'target/bow-tension-paper-fixture-1.0.0.jar'
    server.mkdir()
    for name in ('cache', 'libraries', 'versions'):
        shutil.copytree(cache / name, server / name)
    shutil.copy2(paper, server / 'paper.jar')
    shutil.copy2(TOOLS / 'server.properties', server / 'server.properties')
    (server / 'eula.txt').write_text('eula=true\n', encoding='utf-8')
    plugins = server / 'plugins'
    plugins.mkdir()
    shutil.copy2(plugin, plugins / 'helsteraMobs.jar')
    shutil.copy2(mythic, plugins / 'MythicMobs.jar')
    shutil.copy2(probe, plugins / 'HelsteraBowTensionProbe.jar')

    data = plugins / 'helsteraMobs'
    data.mkdir()
    config = (TOOLS / 'config.yml').read_text(encoding='utf-8')
    config = re.sub(r'(?m)^  (self-test(?:-[A-Za-z0-9-]+)?):\s*true\s*$', r'  \1: false', config)
    config = re.sub(r'(?m)^  trace-triggers:.*$', '  trace-triggers: false', config)
    config = re.sub(r'(?m)^  mythicmobs: false\s*$', '  mythicmobs: true', config)
    if re.search(r'(?m)^web:\s*\n[ \t]+enabled:\s*true', config):
        raise ValueError('Web interface must remain disabled in the isolated fixture')
    (data / 'config.yml').write_text(config, encoding='utf-8')
    for name in ('items.yml', 'loot.yml'):
        shutil.copy2(TOOLS / name, data / name)
    (data / 'skills.yml').write_text('skills: {}\n', encoding='utf-8')
    (data / 'spawners.yml').write_text('spawners: {}\n', encoding='utf-8')

    latest = server / 'logs/latest.log'
    client_log = server / 'client.log'
    with (server / 'console.log').open('w', encoding='utf-8') as console:
        process = subprocess.Popen(['java', '-Xms1G', '-Xmx2G', '-jar', 'paper.jar', 'nogui'],
                                   cwd=server, stdin=subprocess.PIPE, stdout=console,
                                   stderr=subprocess.STDOUT, text=True)
        client = None
        try:
            wait_for(lambda: latest.is_file() and 'Done (' in latest.read_text(errors='replace'),
                     process, 300, 'Paper startup timed out')
            with client_log.open('w', encoding='utf-8') as output:
                client = subprocess.Popen(['node', str(TOOLS / 'bow-tension-client.js')], cwd=TOOLS,
                                          stdout=output, stderr=subprocess.STDOUT)
                wait_for(lambda: client.poll() is not None, process, 120, 'Bow client timed out')
                if client.returncode != 0:
                    raise RuntimeError('Bow client failed; inspect retained local fixture logs')
            wait_for(lambda: latest.is_file() and
                     len(re.findall(r'BOW_TENSION_ROW\|index=', latest.read_text(errors='replace'))) == 6,
                     process, 15, 'Paper did not observe all six acknowledged client shots')
            process.stdin.write('stop\n')
            process.stdin.flush()
            try:
                process.wait(timeout=45)
            except subprocess.TimeoutExpired as error:
                raise RuntimeError('Paper did not stop cleanly after the client completed') from error
        finally:
            if client is not None and client.poll() is None:
                client.kill()
                client.wait()
            if process.poll() is None:
                process.stdin.write('stop\n')
                process.stdin.flush()
                try:
                    process.wait(timeout=45)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait()

    raw_server_log = latest.read_text(errors='replace') if latest.exists() else ''
    server_log = redact(raw_server_log)
    cleaned_client = redact(client_log.read_text(errors='replace')) if client_log.exists() else ''
    (server / 'server.log').write_text(server_log, encoding='utf-8')
    (server / 'client.log').write_text(cleaned_client, encoding='utf-8')
    report = {
        'schemaVersion': 1,
        'fixtureDirectory': fixture_name,
        'evidenceMode': 'strict-condition-comparison',
        'generatedAtUtc': datetime.now(timezone.utc).isoformat(),
        'paperJarSha256': PAPER_HASH,
        'mythicMobsSha256': MYTHIC_HASH,
        'pluginSha256': digest(plugin),
        'probeJarSha256': digest(probe),
        'comparisonScope': 'Real player EntityShootBowEvent; compare Helstera runtime condition and MythicMobs 5.7.2 BowTensionCondition after BukkitTriggerMetadata.apply. This does not claim MythicMobs native player trigger dispatch.',
        'sourceHashes': captured_source_hashes,
        'observations': [line for line in server_log.splitlines() if 'BOW_TENSION_ROW|' in line],
        'clientSummary': [line for line in cleaned_client.splitlines() if line.startswith('BOW_TENSION_CLIENT|')],
        'bootExitCode': process.returncode,
        'files': [],
    }
    for name in ('server.log', 'client.log'):
        report['files'].append({'path': name, 'sha256': digest(server / name)})
    if source_hashes() != captured_source_hashes:
        raise RuntimeError('Fixture source changed during the Paper run')
    with zipfile.ZipFile(archive_path, 'x', zipfile.ZIP_DEFLATED) as archive:
        for entry in report['files']:
            archive.write(server / entry['path'], entry['path'])
    report['logArchive'] = archive_path.name
    report['logArchiveSha256'] = digest(archive_path)
    spec = importlib.util.spec_from_file_location(
        'bow_tension_validator', TOOLS / 'validate-bow-tension-evidence.py')
    validator = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(validator)
    validator.validate(report, archive_path.read_bytes())
    report_path.write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
    subprocess.run([sys.executable, str(TOOLS / 'validate-bow-tension-evidence.py'), str(report_path)],
                   check=True, cwd=ROOT)
    print(f'BowTension Paper evidence: {report_path}')


if __name__ == '__main__':
    main()
