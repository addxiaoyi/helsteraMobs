"""Observe native/direct/imported merchant recipes and windows on pinned Paper; no parity promotion."""
import argparse
from datetime import datetime, timezone
import importlib.util
import json
from pathlib import Path
import re
import shutil
import socket
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
    fixture = f'srv-paper-merchant-trade-{day}-{args.revision}'
    server = ROOT / fixture
    report_path = TOOLS / 'reports' / f'paper-1.21.1-merchant-trade-{day}-{args.revision}.json'
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
    with socket.socket() as connection:
        if connection.connect_ex(('127.0.0.1', 25731)) == 0:
            raise RuntimeError('Fixture port 25731 is already occupied')
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
    probe_jar = plugins / 'HelsteraMerchantTradeProbe.jar'
    with zipfile.ZipFile(TOOLS / 'random-spawn-fixture/target/random-spawn-paper-fixture-1.0.0.jar') as source, \
            zipfile.ZipFile(probe_jar, 'w', zipfile.ZIP_DEFLATED) as dest:
        for item in source.infolist():
            if item.filename != 'plugin.yml':
                dest.writestr(item, source.read(item.filename))
        dest.writestr('plugin.yml', "name: HelsteraMerchantTradeProbe\nversion: '1.0'\napi-version: '1.21'\n"
                      'main: dev.helstera.test.randomspawn.MerchantTradeProbe\ndepend: [helsteraMobs, MythicMobs]\n')
    mobs = plugins / 'MythicMobs/Mobs'
    mobs.mkdir(parents=True)
    (mobs / 'merchant-trade.yml').write_text('HelsteraRadiusAuditMob:\n  Type: COW\n  Options:\n    NoAI: true\n    Despawn: PERSISTENT\n')
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
    case_spec = importlib.util.spec_from_file_location('merchant_trade_cases', TOOLS / 'merchant-trade-cases.py')
    cases = importlib.util.module_from_spec(case_spec); case_spec.loader.exec_module(cases)
    probe_data = plugins / 'HelsteraMerchantTradeProbe'; probe_data.mkdir()
    (probe_data / 'cases.yml').write_text(yaml.safe_dump({'cases': cases.CASES}, sort_keys=False))
    drop_tables = plugins / 'MythicMobs/DropTables'; drop_tables.mkdir()
    (drop_tables / 'merchant-trade.yml').write_text(yaml.safe_dump(cases.TABLES, sort_keys=False))
    native_items = plugins / 'MythicMobs/Items'; native_items.mkdir()
    (native_items / 'merchant-trade.yml').write_text(yaml.safe_dump(cases.ITEM_DEFINITIONS, sort_keys=False))
    source_names = ['tools/paper-integration/' + name for name in [
        'run-merchant-trade-fixture.py', 'run-player-variable-fixture.py', 'merchant-trade-cases.py', 'merchant-trade-client.js',
        'paper-1.21.1-client-protocol.js', 'test-paper-1.21.1-client-protocol.js',
        'audit-merchant-trade.py', 'test-merchant-trade-audit.py',
        'random-spawn-fixture/src/main/java/dev/helstera/test/randomspawn/MerchantTradeProbe.java',
        'random-spawn-fixture/src/main/java/dev/helstera/test/randomspawn/VariablePlaceholderAudit.java']]
    source_names += ['helstera-ai/src/main/java/dev/helstera/ai/skill/' + name + '.java' for name in [
        'MerchantTradeActions', 'SkillService', 'SkillExtras', 'MythicSkillSpec', 'Placeholders']]
    source_names += ['helstera-api/src/main/java/dev/helstera/api/util/MerchantTradeSpec.java',
        'helstera-api/src/main/java/dev/helstera/api/util/MythicMessageText.java',
        'helstera-api/src/main/java/dev/helstera/api/util/ShieldBreakSpec.java',
        'helstera-api/src/main/java/dev/helstera/api/util/MythicGeneratedLootSpec.java',
        'helstera-api/src/main/java/dev/helstera/api/util/MythicRewardEntry.java',
        'helstera-ai/src/main/java/dev/helstera/ai/loot/GeneratedLoot.java',
        'helstera-ai/src/main/java/dev/helstera/ai/loot/GeneratedLootTable.java',
        'helstera-ai/src/main/java/dev/helstera/ai/loot/LootService.java',
        'helstera-ai/src/main/java/dev/helstera/ai/loot/DropTable.java',
        'helstera-migration/src/main/java/dev/helstera/migration/importer/MythicDropTableProjection.java',
        'helstera-api/src/main/java/dev/helstera/api/behavior/BehaviorContext.java',
        'helstera-migration/src/main/java/dev/helstera/migration/importer/MythicMobsImporter.java',
        'helstera-migration/src/main/java/dev/helstera/migration/importer/MythicSpec.java']
    sources = [ROOT / name for name in source_names]
    report = {'schemaVersion': 1, 'fixtureDirectory': fixture, 'evidenceMode': 'observation',
              'parityStatus': 'unassessed', 'generatedAtUtc': datetime.now(timezone.utc).isoformat(),
              'pluginSha256': common.digest(plugin), 'paperJarSha256': common.PAPER_HASH,
              'mythicMobsSha256': common.NATIVE_HASH, 'probeJarSha256': common.digest(probe_jar),
              'sourceHashes': {str(p.relative_to(ROOT)): common.digest(p) for p in sources},
              'limitations': ['Native constructors initialize before direct casts; full native dispatcher scheduling is outside this slice.',
                              'Two protocol clients record merchant windows and trade lists and send actual close requests.',
                              'Quoted/dynamic integer amounts are explicit extensions over the public native parser.',
                              'No transaction, human rendering, randomized custom metadata, reload/disconnect or full release assertion.']}
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
                    client = subprocess.Popen(['node', str(TOOLS / 'merchant-trade-client.js')],
                                              cwd=TOOLS, stdout=client_output, stderr=subprocess.STDOUT)
                if 'MERCHANT_TRADE_FAILED|' in log:
                    raise RuntimeError('Merchant-trade probe failed')
                if 'MERCHANT_TRADE_SUMMARY|' in log:
                    if client is None: raise RuntimeError('No player client')
                    try: client.wait(timeout=15)
                    except subprocess.TimeoutExpired: raise RuntimeError('Player client did not finish')
                    if client.returncode != 0: raise RuntimeError('Player client failed')
                    break
                if client is not None and client.poll() is not None:
                    raise RuntimeError('Player client exited before completion')
                time.sleep(.25)
            else:
                raise RuntimeError('Merchant-trade probe exited or timed out')
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
    probe_data = plugins / 'HelsteraMerchantTradeProbe'
    snapshots = sorted((probe_data / 'observations').glob('*.yml'))
    rows = [yaml.safe_load(path.read_text()) for path in snapshots]
    if (process.returncode != 0 or len(rows) != len(cases.CASES) * 3
            or f'MERCHANT_TRADE_SUMMARY|observations={len(cases.CASES)*3}|completed=true' not in log):
        failure = failure or 'Incomplete merchant-trade observations'
    if len({(row['path'], row['case']) for row in rows}) != len(rows):
        failure = 'Duplicate merchant-trade observations'
    report.update(exitCode=process.returncode, clientExitCode=client.returncode if client else None, observationCount=len(rows),
                  validationResult='passed' if failure is None else 'rejected: ' + failure)
    for path, expected in report['sourceHashes'].items():
        if common.digest(ROOT / path) != expected:
            raise RuntimeError('Source changed during fixture: ' + path)
    for path, key in [(server / 'paper.jar', 'paperJarSha256'), (plugins / 'MythicMobs.jar', 'mythicMobsSha256'),
                      (plugins / 'helsteraMobs.jar', 'pluginSha256'), (probe_jar, 'probeJarSha256')]:
        if common.digest(path) != report[key]:
            raise RuntimeError('Artifact changed during fixture')
    files = [server / 'server.log', server / 'client.log', mobs / 'merchant-trade.yml', probe_data / 'cases.yml', *snapshots, probe_data / 'identities.yml', probe_data / 'imported-loot.yml', drop_tables / 'merchant-trade.yml', native_items / 'merchant-trade.yml', data / 'items.yml']
    missing = [str(path.relative_to(server)) for path in files if not path.is_file()]
    if missing:
        failure = failure or 'Missing fixture files: ' + ', '.join(missing)
        report.update(validationResult='rejected: ' + failure, missingFiles=missing)
        files = [path for path in files if path.is_file()]
    report['files'] = [{'path': str(path.relative_to(server)), 'sha256': common.digest(path)} for path in files]
    audit_spec = importlib.util.spec_from_file_location('merchant_trade_audit', TOOLS / 'audit-merchant-trade.py')
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
