"""Validate the archived BowTension comparison evidence without starting Paper."""
import hashlib
import io
import json
from pathlib import Path
import re
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[2]
SOURCE_NAMES = {
    'tools/paper-integration/run-bow-tension-fixture.py',
    'tools/paper-integration/bow-tension-client.js',
    'tools/paper-integration/validate-bow-tension-evidence.py',
    'tools/paper-integration/test-bow-tension-evidence.py',
    'tools/paper-integration/bow-tension-fixture/pom.xml',
    'tools/paper-integration/bow-tension-fixture/src/main/resources/plugin.yml',
    'tools/paper-integration/bow-tension-fixture/src/main/java/dev/helstera/test/bowtension/BowTensionProbe.java',
}
PAPER_HASH = '39bd8c00b9e18de91dcabd3cc3dcfa5328685a53b7187a2f63280c22e2d287b9'
MYTHIC_HASH = '9672457cafc56ff841606bfbb710947329c08720ffc207746b60130d43bf902f'
SCHEMA = {'schemaVersion', 'fixtureDirectory', 'evidenceMode', 'generatedAtUtc', 'paperJarSha256',
          'mythicMobsSha256', 'pluginSha256', 'probeJarSha256', 'comparisonScope', 'sourceHashes',
          'observations', 'clientSummary', 'bootExitCode', 'files', 'logArchive', 'logArchiveSha256'}


def require(test, message):
    if not test:
        raise ValueError(message)


def sha(data):
    return hashlib.sha256(data).hexdigest()


def source_hashes(root=ROOT):
    return {name: sha((root / name).read_bytes()) for name in SOURCE_NAMES}


def ensure_redacted(text):
    patterns = (
        r'(?i)([?&]token=)(?!\[REDACTED\])[^&\s"\'<>]+',
        r'(?i)(__cf_bm=)(?!\[REDACTED\])[^;\s"\'<>]+',
        r'(令牌[ \t]*[:：][ \t]*)(?!\[REDACTED\])[A-Za-z0-9._~-]+',
        r'(?i)(\bauthorization\s*:\s*bearer\s+)(?!\[REDACTED\])[^\s"\'<>]+',
    )
    require(not any(re.search(pattern, text) for pattern in patterns), 'unredacted credential in archived log')


def validate(report, archive_data):
    require(set(report) == SCHEMA, 'unexpected report fields')
    require(report['schemaVersion'] == 1 and report['evidenceMode'] == 'strict-condition-comparison',
            'wrong schema or observer-only evidence')
    require(report['paperJarSha256'] == PAPER_HASH and report['mythicMobsSha256'] == MYTHIC_HASH,
            'wrong pinned server or Mythic artifact')
    require(report['bootExitCode'] == 0, 'Paper did not stop cleanly')
    require('does not claim MythicMobs native player trigger dispatch' in report['comparisonScope'],
            'comparison scope overclaims native trigger dispatch')
    require(sha(archive_data) == report['logArchiveSha256'], 'log archive hash mismatch')
    require(re.fullmatch(r'srv-paper-bow-tension-[0-9]{8}-r[1-9][0-9]*', report['fixtureDirectory']) is not None,
            'fixture path is invalid')
    require(report['logArchive'] == report['fixtureDirectory'].replace('srv-paper-bow-tension-',
            'paper-1.21.1-bow-tension-strict-') + '-logs.zip', 'archive path does not match fixture')
    require(re.fullmatch(r'[a-f0-9]{64}', report['pluginSha256']) is not None and
            re.fullmatch(r'[a-f0-9]{64}', report['probeJarSha256']) is not None and
            all(re.fullmatch(r'[a-f0-9]{64}', value) for value in report['sourceHashes'].values()),
            'invalid artifact or source hashes')
    require(set(report['sourceHashes']) == SOURCE_NAMES, 'incomplete or unexpected source identity')
    require(report['sourceHashes'] == source_hashes(), 'fixture source changed after evidence generation')
    with zipfile.ZipFile(io.BytesIO(archive_data)) as archive:
        names = archive.namelist()
        require(set(names) == {'server.log', 'client.log'} and len(names) == len(set(names)),
                'unexpected archive members')
        files = {name: archive.read(name) for name in names}
    require(len(report['files']) == 2 and all(set(item) == {'path', 'sha256'} for item in report['files']),
            'invalid log manifest')
    require({item['path']: item['sha256'] for item in report['files']} ==
            {name: sha(data) for name, data in files.items()}, 'archived log manifest mismatch')
    for data in files.values():
        ensure_redacted(data.decode(errors='replace'))
    server_lines = files['server.log'].decode().splitlines()
    rows = [line for line in server_lines if 'BOW_TENSION_ROW|' in line]
    require(rows == report['observations'] and len(rows) == 6, 'expected six distinct real shot observations')
    forces = []
    for index, line in enumerate(rows, 1):
        match = re.search(r'BOW_TENSION_ROW\|index=(\d+)\|force=([0-9.]+)\|hel=([01]{3})\|mythic=([01]{3})\|helNoEvent=(true|false)\|mythicNoForce=(true|false)$', line)
        require(match is not None, 'malformed condition observation')
        row_index, raw_force, hel, mythic, hel_missing, mythic_missing = match.groups()
        force = float(raw_force)
        require(int(row_index) == index and 0 <= force <= 1, 'shot ordering or force range invalid')
        expected = ''.join('1' if value else '0' for value in (force > .5, force < .5, force == .5))
        require(hel == expected and mythic == expected, 'runtime/native condition result differs from force')
        require(hel_missing == 'true' and mythic_missing == 'true', 'missing-event controls did not fail closed')
        forces.append(force)
    require(min(forces) < .5 < max(forces), 'real shots did not exercise both sides of the threshold')
    client_lines = files['client.log'].decode().splitlines()
    client_summaries = [line for line in client_lines if line.startswith('BOW_TENSION_CLIENT|')]
    require(client_summaries == report['clientSummary'] and len(client_summaries) == 1 and
            client_summaries[0].endswith('|shots=6|charges=3,4,5,6,7,8|passed=true'),
            'client did not finish all acknowledged shots')
    require(any(re.search(r'BOW_TENSION_SUMMARY\|observations=6\|below=[1-5]\|above=[1-5]\|failures=0\|passed=true$', line)
                for line in server_lines), 'Paper fixture summary failed')
    return forces


def main(path):
    report_path = Path(path)
    report = json.loads(report_path.read_text(encoding='utf-8'))
    archive_path = report_path.parent / report['logArchive']
    forces = validate(report, archive_path.read_bytes())
    print(f'Validated six real Paper bow shots and both condition implementations; force range {min(forces):.3f}..{max(forces):.3f}.')


if __name__ == '__main__':
    main(sys.argv[1])
