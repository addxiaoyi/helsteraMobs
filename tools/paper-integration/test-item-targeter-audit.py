"""Synthetic negative controls for the independent Item evidence auditor."""
import copy
import importlib.util
import json
from pathlib import Path
import unittest
import uuid
import yaml

TOOLS = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('item_audit', TOOLS / 'audit-item-targeter.py')
audit = importlib.util.module_from_spec(spec)
spec.loader.exec_module(audit)


def manifest(report, files):
    report['files'] = [{'path': name, 'sha256': audit.digest(data)} for name, data in files.items()]


def fixture():
    rows, logs = [], []
    entities = []
    for label in list(audit.POINTS) + ['caster', 'living', 'other-living', 'cross-living', 'harness-item', 'player']:
        point = audit.POINTS.get(label, (8, 180, 8))
        entities.append({'label': label, 'uuid': str(uuid.uuid4()),
                         'type': 'ITEM' if label in audit.POINTS or label == 'harness-item' else 'PLAYER' if label == 'player' else 'COW',
                         'world': 'other-world' if label == 'cross-item' else 'integration-world',
                         **dict(zip(('x','y','z'), point))})
    for path in ('direct', 'imported'):
        for case, (selector, expected) in audit.CASES.items():
            native = expected['members'][:expected['count']] if isinstance(expected, dict) else expected
            runtime = audit.EXTENSIONS.get(case, native)
            rows.append({'path': path, 'case': case, 'selector': selector, 'source': audit.source(case),
                         'binding': audit.source(case) if path == 'direct' else audit.binding(case),
                         'nativeSelected': native, 'runtimeSelected': runtime, 'nativeEffects': native, 'runtimeEffects': runtime,
                         'nativeAccepted': True, 'runtimeAccepted': True, 'loadedChunksAdded': [], 'entities': entities,
                         'nativeThreatScores': {'near': 5.0, 'far': 10.0, 'living': 100.0} if case.startswith('threat-') else {}})
            logs.append(f'ITEMS_ROW|path={path}|case={case}|observed=true')
    count = len(rows)
    logs.append(f'ITEMS_SUMMARY|observations={count}|passed=true')
    events = [{'event': 'join', 'name': 'ItemTester', 'uuid': str(uuid.uuid4())},
              {'event': 'done', 'name': 'ItemTester'}, {'event': 'summary', 'passed': True}]
    files = {'server.log': '\n'.join(logs).encode(),
             'client.log': '\n'.join('ITEMS_CLIENT ' + json.dumps(event) for event in events).encode(),
             'plugins/MythicMobs/Mobs/location.yml': yaml.safe_dump({'HelsteraRadiusAuditMob': {
                 'Type': 'COW', 'ThreatTable': {'Enabled': True, 'DecayUnreachable': False},
                 'Options': {'NoAI': True, 'Despawn': 'PERSISTENT'}}}).encode(),
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
        self.assertEqual(2 * len(audit.CASES), audit.audit(*fixture())['observations'])

    def mutate(self, operation):
        report, files = fixture()
        rows = yaml.safe_load(files[audit.DATA])['observations']
        operation(rows)
        files[audit.DATA] = yaml.safe_dump({'observations': rows}).encode()
        manifest(report, files)
        with self.assertRaises(ValueError): audit.audit(report, files)

    def test_duplicate_missing_case(self):
        self.mutate(lambda rows: rows.__setitem__(1, copy.deepcopy(rows[0])))

    def test_each_observed_field_is_checked(self):
        for field, value in {'path':'wrong', 'case':'wrong', 'selector':'@self', 'source':'noop', 'binding':'noop',
                             'nativeSelected':[], 'runtimeSelected':[], 'nativeEffects':[], 'runtimeEffects':[],
                             'nativeAccepted':False, 'runtimeAccepted':False, 'loadedChunksAdded':['1,1'], 'entities':[]}.items():
            with self.subTest(field=field): self.mutate(lambda rows: rows[0].__setitem__(field, value))

    def test_consistent_wrong_outcomes_are_rejected(self):
        def mutate(rows):
            for field in ('nativeSelected', 'runtimeSelected', 'nativeEffects', 'runtimeEffects'): rows[0][field] = []
        self.mutate(mutate)

    def test_native_ignored_guard_cannot_be_relabelled_as_parity(self):
        def mutate(rows):
            row = next(row for row in rows if row['case'] == 'guard-tag')
            row['nativeSelected'] = row['nativeEffects'] = ['far']
        self.mutate(mutate)

    def test_random_selection_must_remain_inside_eligible_items(self):
        def mutate(rows):
            row = next(row for row in rows if row['case'] == 'sort-random')
            row['runtimeSelected'] = row['runtimeEffects'] = ['living']
        self.mutate(mutate)

    def test_point_and_world_controls_are_checked(self):
        self.mutate(lambda rows: rows[0]['entities'][0].__setitem__('x', 100))
        self.mutate(lambda rows: rows[0]['entities'][0].__setitem__('world', 'other-world'))

    def test_threat_setup_cannot_be_omitted(self):
        def mutate(rows):
            next(row for row in rows if row['case'] == 'threat-high')['nativeThreatScores'] = {}
        self.mutate(mutate)

    def test_logs_clients_and_source_manifest_are_required(self):
        for changed in ('server.log', 'client.log', 'sourceHashes'):
            report, files = fixture()
            if changed == 'sourceHashes': report[changed].pop(next(iter(report[changed])))
            else: files[changed] = b''
            manifest(report, files)
            with self.assertRaises(ValueError): audit.audit(report, files)


if __name__ == '__main__': unittest.main()
