"""Synthetic negative controls for the independent aiming evidence auditor."""
import copy
import importlib.util
import json
from pathlib import Path
import unittest
import uuid
import yaml

TOOLS = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('item_audit', TOOLS / 'audit-aiming-targeter.py')
audit = importlib.util.module_from_spec(spec)
spec.loader.exec_module(audit)


def manifest(report, files):
    report['files'] = [{'path': name, 'sha256': audit.digest(data)} for name, data in files.items()]


def fixture():
    rows, logs = [], []
    for path in ('direct', 'imported'):
        for case, (selector, expected) in audit.CASES.items():
            runtime = audit.EXTENSIONS.get(case, expected)
            entities, locations = audit.inputs(case)
            mode = 'SURVIVAL' if case == 'entity-player-survival' else 'CREATIVE' if case == 'entity-player-creative' else 'SPECTATOR'
            rows.append({'path': path, 'case': case, 'selector': selector, 'source': audit.source(case),
                         'binding': audit.source(case) if path == 'direct' else audit.binding(case),
                         'nativeSelected': sorted(expected), 'runtimeSelected': sorted(runtime),
                         'nativeEffects': audit.effects(case, expected), 'runtimeEffects': audit.effects(case, runtime),
                         'nativeAccepted': True, 'runtimeAccepted': True, 'scene': audit.scene(case),
                         'entityInputs': entities, 'locationInputs': locations, 'playerMode': mode,
                         'nativeEntityInputs': entities.copy(), 'nativeLocationInputs': locations.copy(),
                         'player': audit.point(.25), 'eye': audit.point(.25, 181.62)})
            logs.append(f'AIMING_ROW|path={path}|case={case}|observed=true')
    count = len(rows)
    logs.append(f'AIMING_SUMMARY|observations={count}|passed=true')
    events = [{'event': 'join', 'name': 'AimTester', 'uuid': str(uuid.uuid4())},
              {'event': 'done', 'name': 'AimTester'}, {'event': 'summary', 'passed': True}]
    files = {'server.log': '\n'.join(logs).encode(),
             'client.log': '\n'.join('AIMING_CLIENT ' + json.dumps(event) for event in events).encode(),
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
                             'nativeAccepted':False, 'runtimeAccepted':False, 'scene':'wrong', 'entityInputs':[], 'locationInputs':['invented'],
                             'nativeEntityInputs':[], 'nativeLocationInputs':['invented']}.items():
            with self.subTest(field=field): self.mutate(lambda rows: rows[0].__setitem__(field, value))

    def test_consistent_wrong_outcomes_are_rejected(self):
        def mutate(rows):
            for field in ('nativeSelected', 'runtimeSelected', 'nativeEffects', 'runtimeEffects'): rows[0][field] = []
        self.mutate(mutate)

    def test_native_ignored_guard_cannot_be_relabelled_as_parity(self):
        def mutate(rows):
            row = next(row for row in rows if row['case'] == 'entity-guard')
            row['nativeSelected'] = row['nativeEffects'] = ['far']
        self.mutate(mutate)

    def test_location_set_cannot_be_replaced_with_entity_projection(self):
        def mutate(rows):
            row = next(row for row in rows if row['case'] == 'location-not-entity-projection')
            row['nativeSelected'] = row['runtimeSelected'] = audit.LOCS
            row['nativeEffects'] = row['runtimeEffects'] = audit.effects(row['case'], audit.LOCS)
        self.mutate(mutate)

    def test_block_queries_require_real_player_geometry(self):
        for field, value in [('player', audit.point(10)), ('eye', audit.point(.25)), ('playerMode', 'CREATIVE')]:
            def mutate(rows):
                next(row for row in rows if row['case'] == 'block-default')[field] = value
            with self.subTest(field=field): self.mutate(mutate)

    def test_mutation_outside_selection_is_rejected(self):
        def mutate(rows):
            next(row for row in rows if row['case'] == 'location-empty')['runtimeEffects'] = audit.effects('location-empty', audit.LOCS)
        self.mutate(mutate)

    def test_logs_clients_and_source_manifest_are_required(self):
        for changed in ('server.log', 'client.log', 'sourceHashes'):
            report, files = fixture()
            if changed == 'sourceHashes': report[changed].pop(next(iter(report[changed])))
            else: files[changed] = b''
            manifest(report, files)
            with self.assertRaises(ValueError): audit.audit(report, files)


if __name__ == '__main__': unittest.main()
