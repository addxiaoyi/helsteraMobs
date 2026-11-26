"""Synthetic rejection controls for targeter evidence; not a substitute for Paper observations."""
import copy
import importlib.util
import json
from pathlib import Path
import unittest
import yaml

TOOLS = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('targeter_audit', TOOLS / 'audit-targeter-context.py')
audit = importlib.util.module_from_spec(spec)
spec.loader.exec_module(audit)


def manifest(report, files):
    report['files'] = [{'path': name, 'sha256': audit.digest(data)} for name, data in files.items()]


def fixture():
    rows, logs = [], []
    for path in ('direct', 'imported'):
        for case, (selector, selected) in audit.CASES.items():
            rows.append({'path': path, 'case': case, 'selector': selector, 'source': audit.source(case),
                         'binding': audit.source(case) if path == 'direct' else audit.binding(case),
                         'nativeSelected': selected, 'runtimeSelected': selected, 'expected': selected,
                         'nativeEffects': audit.effects(case), 'runtimeEffects': audit.effects(case),
                         'expectedEffects': audit.effects(case), 'nativeAccepted': True,
                         'runtimeAccepted': True, 'matched': True})
            logs.append(f'TARGETERS_ROW|path={path}|case={case}|matched=true')
    count = len(rows)
    logs.append(f'TARGETERS_SUMMARY|observations={count}|passed=true')
    events = [{'event': 'join', 'name': 'TargeterTester', 'uuid': '00000000-0000-0000-0000-000000000001'},
              {'event': 'done', 'name': 'TargeterTester'}, {'event': 'summary', 'passed': True}]
    files = {'server.log': '\n'.join(logs).encode(),
             'client.log': '\n'.join('TARGETERS_CLIENT ' + json.dumps(event) for event in events).encode(),
             'plugins/MythicMobs/Mobs/location.yml': yaml.safe_dump({'HelsteraRadiusAuditMob': {
                 'Type': 'COW', 'Options': {'NoAI': True, 'Despawn': 'PERSISTENT'}}}).encode(),
             audit.DATA: yaml.safe_dump({'observations': rows}).encode()}
    report = {'schemaVersion': 1, 'evidenceMode': 'observation', 'parityStatus': 'unassessed',
              'validationResult': 'passed', 'exitCode': 0, 'clientExitCode': 0, 'observationCount': count,
              'paperJarSha256': '39bd8c00b9e18de91dcabd3cc3dcfa5328685a53b7187a2f63280c22e2d287b9',
              'mythicMobsSha256': '9672457cafc56ff841606bfbb710947329c08720ffc207746b60130d43bf902f',
              'pluginSha256': 'a' * 64, 'probeJarSha256': 'b' * 64,
              'sourceHashes': {name: 'c' * 64 for name in audit.SOURCE_FILES}}
    manifest(report, files)
    return report, files


class AuditTest(unittest.TestCase):
    def test_complete_synthetic_evidence(self):
        self.assertEqual(66, audit.audit(*fixture())['observations'])

    def mutate(self, operation):
        report, files = fixture()
        rows = yaml.safe_load(files[audit.DATA])['observations']
        operation(rows)
        files[audit.DATA] = yaml.safe_dump({'observations': rows}).encode()
        manifest(report, files)
        with self.assertRaises(ValueError):
            audit.audit(report, files)

    def test_duplicate_missing_case(self):
        self.mutate(lambda rows: rows.__setitem__(1, copy.deepcopy(rows[0])))

    def test_all_row_fields_are_checked(self):
        for field, value in {
            'path': 'other', 'case': 'invented', 'selector': '@self', 'source': 'noop', 'binding': 'noop',
            'nativeSelected': ['wrong'], 'runtimeSelected': ['wrong'], 'expected': ['wrong'],
            'nativeEffects': ['wrong'], 'runtimeEffects': ['wrong'], 'expectedEffects': ['wrong'],
            'nativeAccepted': False, 'runtimeAccepted': False, 'matched': False,
        }.items():
            with self.subTest(field=field):
                self.mutate(lambda rows: rows[0].__setitem__(field, value))

    def test_self_reported_consistent_wrong_outcomes_rejected(self):
        def change(rows):
            for key in ('nativeSelected', 'runtimeSelected', 'expected'):
                rows[0][key] = []
        self.mutate(change)

    def test_location_effect_cannot_be_replaced_by_selected_entity_label(self):
        def change(rows):
            row = next(item for item in rows if item['case'] == 'lot-inherited')
            for key in ('nativeEffects', 'runtimeEffects', 'expectedEffects'):
                row[key] = ['first', 'second']
        self.mutate(change)

    def test_client_missing_or_failed_completion(self):
        for content in (b'', b'TARGETERS_CLIENT {"event":"summary","passed":false}'):
            report, files = fixture()
            files['client.log'] = content
            manifest(report, files)
            with self.assertRaises(ValueError): audit.audit(report, files)

    def test_log_must_correspond_to_each_case(self):
        report, files = fixture()
        files['server.log'] = files['server.log'].replace(b'TARGETERS_ROW|path=direct|case=player-exact|matched=true', b'')
        manifest(report, files)
        with self.assertRaises(ValueError): audit.audit(report, files)

    def test_source_manifest_must_cover_implementation(self):
        report, files = fixture()
        report['sourceHashes'].pop(next(iter(report['sourceHashes'])))
        with self.assertRaises(ValueError): audit.audit(report, files)


if __name__ == '__main__':
    unittest.main()
