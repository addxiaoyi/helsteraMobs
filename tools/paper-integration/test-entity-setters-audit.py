"""Malformed setter evidence must fail independently of the producer's matched flag."""
import copy
import importlib.util
from pathlib import Path
import unittest
import yaml

TOOLS = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('setter_audit', TOOLS / 'audit-entity-setters.py')
audit = importlib.util.module_from_spec(spec)
spec.loader.exec_module(audit)


def fixture():
    rows = []
    logs = []
    for path in ('direct', 'imported'):
        for case, (source, imported, before, expected) in audit.CASES.items():
            invalid = imported is None
            rows.append({'path': path, 'case': case, 'source': source,
                         'binding': source if path == 'direct' else 'preserved-unsupported' if invalid else imported,
                         'before': before, 'nativeResult': 'not-invoked-invalid' if invalid else 'SUCCESS',
                         'nativeAfter': 'not-invoked-invalid' if invalid else [1.0, 0.0] if case == 'interaction-placeholder' else expected,
                         'runtimeAfter': expected, 'expected': expected, 'matched': True, 'rejected': invalid,
                         'nativeExpected': [1.0, 0.0] if case == 'interaction-placeholder' else expected,
                         'difference': 'public-placeholder-float-premium-gate' if case == 'interaction-placeholder' else 'none'})
            logs.append(f'SETTERS_ROW|path={path}|case={case}|matched=true')
    logs.append('SETTERS_SUMMARY|observations=42|passed=true')
    files = {'server.log': '\n'.join(logs).encode(),
             'plugins/MythicMobs/Mobs/location.yml': yaml.safe_dump({'HelsteraRadiusAuditMob': {
                 'Type': 'COW', 'Options': {'NoAI': True, 'Despawn': 'PERSISTENT'}}}).encode(),
             'plugins/HelsteraEntitySettersProbe/observations.yml': yaml.safe_dump({'observations': rows}).encode()}
    report = {'schemaVersion': 1, 'evidenceMode': 'observation', 'parityStatus': 'unassessed',
              'validationResult': 'passed', 'exitCode': 0, 'observationCount': 42,
              'paperJarSha256': '39bd8c00b9e18de91dcabd3cc3dcfa5328685a53b7187a2f63280c22e2d287b9',
              'mythicMobsSha256': '9672457cafc56ff841606bfbb710947329c08720ffc207746b60130d43bf902f',
              'pluginSha256': 'a' * 64, 'probeJarSha256': 'b' * 64,
              'sourceHashes': {name: 'c' * 64 for name in audit.SOURCE_FILES}}
    manifest(report, files)
    return report, files


def manifest(report, files):
    report['files'] = [{'path': name, 'sha256': audit.digest(data)} for name, data in files.items()]


class AuditTest(unittest.TestCase):
    def test_complete_fixture(self):
        self.assertEqual(34, audit.audit(*fixture())['matchingNativeComparisons'])

    def mutate(self, operation):
        report, files = fixture()
        name = 'plugins/HelsteraEntitySettersProbe/observations.yml'
        rows = yaml.safe_load(files[name])['observations']
        operation(rows)
        files[name] = yaml.safe_dump({'observations': rows}).encode()
        manifest(report, files)
        with self.assertRaises(ValueError):
            audit.audit(report, files)

    def test_duplicate_and_missing_case(self):
        self.mutate(lambda rows: rows.__setitem__(1, copy.deepcopy(rows[0])))

    def test_self_reported_matching_wrong_outcome(self):
        def change(rows):
            rows[0]['runtimeAfter'] = rows[0]['nativeAfter'] = rows[0]['expected'] = 7.0
        self.mutate(change)

    def test_unvalidated_binding(self):
        self.mutate(lambda rows: rows[21].__setitem__('binding', 'some-other-action @self'))

    def test_boolean_is_not_numeric_zero(self):
        self.mutate(lambda rows: rows[5].__setitem__('runtimeAfter', 0))

    def test_invalid_native_claim(self):
        self.mutate(lambda rows: next(row for row in rows if row['case'] == 'invalid-dimension')
                    .__setitem__('nativeResult', 'SUCCESS'))

    def test_public_premium_gate_cannot_be_claimed_as_matching(self):
        self.mutate(lambda rows: next(row for row in rows if row['case'] == 'interaction-placeholder')
                    .__setitem__('difference', 'none'))

    def test_missing_log_correspondence(self):
        report, files = fixture()
        files['server.log'] = files['server.log'].replace(b'SETTERS_ROW|path=direct|case=malus-value|matched=true', b'')
        manifest(report, files)
        with self.assertRaises(ValueError): audit.audit(report, files)

    def test_source_manifest_and_archive_digest(self):
        report, files = fixture()
        report['sourceHashes'].pop(next(iter(report['sourceHashes'])))
        with self.assertRaises(ValueError): audit.audit(report, files)
        report, files = fixture()
        files['server.log'] += b'changed'
        with self.assertRaises(ValueError): audit.audit(report, files)


if __name__ == '__main__':
    unittest.main()
