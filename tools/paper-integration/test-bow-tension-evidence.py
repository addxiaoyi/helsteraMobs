"""Mutation tests for the standalone BowTension evidence validator."""
import hashlib
import importlib.util
import io
import sys
import unittest
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
spec = importlib.util.spec_from_file_location('bow_tension_validator', Path(__file__).with_name('validate-bow-tension-evidence.py'))
validator = importlib.util.module_from_spec(spec)
spec.loader.exec_module(validator)
validate = validator.validate


def sha(data):
    return hashlib.sha256(data).hexdigest()


def package(forces=None, hel_override=None, hel_no_event='true'):
    forces = forces or [.1, .3, .49, .5, .51, 1.0]
    lines = []
    for index, force in enumerate(forces, 1):
        bits = ''.join('1' if value else '0' for value in
                       (force > .5, force < .5, force == .5))
        hel = hel_override if hel_override is not None and index == 1 else bits
        lines.append(f'[Server thread/INFO]: BOW_TENSION_ROW|index={index}|force={force:.6f}'
                     f'|hel={hel}|mythic={bits}|helNoEvent={hel_no_event}|mythicNoForce=true')
    lines.append('[Server thread/INFO]: BOW_TENSION_SUMMARY|observations=6|below=3|above=3|failures=0|passed=true')
    client = b'BOW_TENSION_CLIENT|name=BowTensionProbe|shots=6|charges=3,4,5,6,7,8|passed=true\n'
    server = ('\n'.join(lines) + '\n').encode()
    stream = io.BytesIO()
    with zipfile.ZipFile(stream, 'w', zipfile.ZIP_DEFLATED) as archive:
        archive.writestr('server.log', server)
        archive.writestr('client.log', client)
    archive_data = stream.getvalue()
    files = [{'path': 'server.log', 'sha256': sha(server)},
             {'path': 'client.log', 'sha256': sha(client)}]
    report = {
        'schemaVersion': 1,
        'fixtureDirectory': 'srv-paper-bow-tension-20261120-r1',
        'evidenceMode': 'strict-condition-comparison',
        'generatedAtUtc': '2026-11-20T12:00:00+00:00',
        'paperJarSha256': '39bd8c00b9e18de91dcabd3cc3dcfa5328685a53b7187a2f63280c22e2d287b9',
        'mythicMobsSha256': '9672457cafc56ff841606bfbb710947329c08720ffc207746b60130d43bf902f',
        'pluginSha256': 'a' * 64,
        'probeJarSha256': 'b' * 64,
        'comparisonScope': 'Real events; does not claim MythicMobs native player trigger dispatch.',
        'sourceHashes': validator.source_hashes(),
        'observations': [line for line in server.decode().splitlines() if 'BOW_TENSION_ROW|' in line],
        'clientSummary': [client.decode().strip()],
        'bootExitCode': 0,
        'files': files,
        'logArchive': 'paper-1.21.1-bow-tension-strict-20261120-r1-logs.zip',
        'logArchiveSha256': sha(archive_data),
    }
    return report, archive_data


class BowTensionEvidenceTest(unittest.TestCase):
    def test_accepts_real_shot_comparison_with_fail_closed_controls(self):
        report, archive = package()
        self.assertEqual(validate(report, archive), [.1, .3, .49, .5, .51, 1.0])

    def test_rejects_runtime_condition_mismatch(self):
        report, archive = package(hel_override='111')
        with self.assertRaisesRegex(ValueError, 'differs from force'):
            validate(report, archive)

    def test_rejects_missing_event_context_control_failure(self):
        report, archive = package(hel_no_event='false')
        with self.assertRaisesRegex(ValueError, 'fail closed'):
            validate(report, archive)

    def test_rejects_shots_that_do_not_straddle_threshold(self):
        report, archive = package([.1, .2, .3, .35, .4, .49])
        with self.assertRaisesRegex(ValueError, 'both sides'):
            validate(report, archive)

    def test_rejects_unredacted_credentials_in_logs(self):
        report, archive = package()
        with zipfile.ZipFile(io.BytesIO(archive)) as source:
            entries = {name: source.read(name) for name in source.namelist()}
        entries['client.log'] += b'open https://example.invalid/?token=plaintext\n'
        updated = io.BytesIO()
        with zipfile.ZipFile(updated, 'w', zipfile.ZIP_DEFLATED) as dest:
            for name, data in entries.items():
                dest.writestr(name, data)
        archive = updated.getvalue()
        report['logArchiveSha256'] = sha(archive)
        report['files'][1]['sha256'] = sha(entries['client.log'])
        with self.assertRaisesRegex(ValueError, 'unredacted credential'):
            validate(report, archive)


if __name__ == '__main__':
    unittest.main()
