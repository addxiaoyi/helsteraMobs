"""Reject partial, relabeled and self-consistent-but-wrong malus observations."""
import copy
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
import zipfile
import yaml

TOOLS = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('malus_audit', TOOLS / 'audit-pathfinding-malus.py')
audit = importlib.util.module_from_spec(spec)
spec.loader.exec_module(audit)


def blob(value):
    return yaml.safe_dump(value, sort_keys=False).encode()


def manifest(report, files):
    report['files'] = [{'path': name, 'sha256': audit.digest(value)} for name, value in files.items()]


def fixture():
    source = {'MalusNative': {'Type': 'COW', 'Options': {'NoAI': True, 'NoGravity': True, 'Despawn': 'PERSISTENT'},
                             'AIPathfindingMalus': [f'{name} {value}' for name, value in audit.COSTS.items()]},
              'MalusMap': {'Type': 'COW', 'Options': {'NoAI': True, 'NoGravity': True},
                           'AIPathfindingMalus': {'WATER': -1, 'FIRE': 0}}}
    files = {'plugins/MythicMobs/Mobs/pathfinding-malus.yml': blob(source),
             'plugins/helsteraMobs/mobs/mythic_malusnative.yml': blob({'model': 'example/emberling', 'ai': {'pathfinding-malus': audit.COSTS}}),
             'plugins/helsteraMobs/mobs/mythic_malusmap.yml': blob({'mob': {'unsupported': {'AIPathfindingMalus': {'WATER': -1, 'FIRE': 0}}}}),
             audit.PREFIX + 'identities.yml': blob({'runtime': '12345678-1234-5678-1234-567812345678', 'costs': audit.COSTS})}
    for stage, count in (('write', 59), ('verify', 27)):
        values = ({prefix + name: value for prefix in ('native-', 'imported-') for name, value in audit.COSTS.items()}
                  | audit.WRITE_CONTROLS if stage == 'write' else
                  {'restart-' + name: value for name, value in audit.COSTS.items()} | {'saved-entity-rebound': True})
        rows = [{'id': name, 'expected': value, 'actual': value, 'matched': True} for name, value in values.items()]
        files[audit.PREFIX + stage + '-observations.yml'] = blob({'stage': stage, 'observations': rows})
        files[stage + '.server.log'] = f'MALUS_SUMMARY|stage={stage}|observations={count}|passed=true'.encode()
    report = {'schemaVersion': 1, 'fixtureDirectory': 'srv-paper-pathfinding-malus-20261121-r1',
              'generatedAtUtc': '2026-11-21T12:00:00+00:00', 'evidenceMode': 'observation', 'parityStatus': 'unassessed',
              'validationResult': 'passed', 'limitations': ['scope', 'version', 'no route choice claim'],
              'paperJarSha256': '39bd8c00b9e18de91dcabd3cc3dcfa5328685a53b7187a2f63280c22e2d287b9',
              'mythicMobsSha256': '9672457cafc56ff841606bfbb710947329c08720ffc207746b60130d43bf902f',
              'pluginSha256': 'a' * 64, 'probeJarSha256': 'b' * 64, 'logArchiveSha256': 'd' * 64,
              'logArchive': 'evidence.zip', 'sourceHashes': {name: 'c' * 64 for name in audit.SOURCE_FILES},
              'stages': [{'stage': 'write', 'exitCode': 0, 'observationCount': 59},
                         {'stage': 'verify', 'exitCode': 0, 'observationCount': 27}]}
    manifest(report, files)
    return report, files


class AuditTest(unittest.TestCase):
    def test_complete_fixture(self):
        self.assertEqual(86, audit.audit(*fixture())['observations'])

    def mutate_snapshot(self, operation, stage='write'):
        report, files = fixture()
        name = audit.PREFIX + stage + '-observations.yml'
        content = yaml.safe_load(files[name]); operation(content)
        files[name] = blob(content); manifest(report, files)
        with self.assertRaises(ValueError): audit.audit(report, files)

    def test_duplicate_and_missing_required_id(self):
        self.mutate_snapshot(lambda content: content['observations'].__setitem__(1, copy.deepcopy(content['observations'][0])))

    def test_relabelled_control_is_not_enough(self):
        self.mutate_snapshot(lambda content: content['observations'][-1].__setitem__('id', 'unknown-control'))

    def test_self_reported_matching_wrong_cost(self):
        def change(content):
            content['observations'][0].update(actual=999.0, expected=999.0)
        self.mutate_snapshot(change)

    def test_no_partial_write_is_independent(self):
        def change(content):
            next(row for row in content['observations'] if row['id'] == 'invalid-runtime-no-partial-write').update(actual=8.0, expected=8.0)
        self.mutate_snapshot(change)

    def test_invalid_input_must_reject(self):
        def change(content):
            next(row for row in content['observations'] if row['id'] == 'invalid-runtime-rejected').update(actual=True, expected=True)
        self.mutate_snapshot(change)

    def test_zero_is_not_false(self):
        def change(content):
            next(row for row in content['observations'] if row['id'] == 'fire-rejected')['actual'] = 0
        self.mutate_snapshot(change)

    def test_nan_is_not_a_cost(self):
        self.mutate_snapshot(lambda content: content['observations'][0].__setitem__('actual', float('nan')))

    def test_rebound_identity_and_restart_costs_are_required(self):
        self.mutate_snapshot(lambda content: content['observations'][-1].update(actual=False, expected=False), 'verify')
        self.mutate_snapshot(lambda content: content.update(stage='write'), 'verify')

    def test_report_requires_both_stages(self):
        report, files = fixture(); report['stages'].pop()
        with self.assertRaises(ValueError): audit.audit(report, files)

    def test_missing_completion_and_file_digest(self):
        report, files = fixture(); files['verify.server.log'] = b'no summary'; manifest(report, files)
        with self.assertRaises(ValueError): audit.audit(report, files)
        report, files = fixture(); files['write.server.log'] += b'changed'
        with self.assertRaises(ValueError): audit.audit(report, files)

    def test_preserved_map_cannot_also_apply(self):
        report, files = fixture(); name = 'plugins/helsteraMobs/mobs/mythic_malusmap.yml'
        content = yaml.safe_load(files[name]); content['ai'] = {'pathfinding-malus': {'WATER': -1}}
        files[name] = blob(content); manifest(report, files)
        with self.assertRaises(ValueError): audit.audit(report, files)

    def test_fixed_source_manifest_and_dependency_pins(self):
        report, files = fixture(); report['sourceHashes'].pop(next(iter(report['sourceHashes'])))
        with self.assertRaises(ValueError): audit.audit(report, files)
        report, files = fixture(); report['paperJarSha256'] = 'a' * 64
        with self.assertRaises(ValueError): audit.audit(report, files)

    def test_archive_digest_and_traversal(self):
        report, files = fixture()
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary); archive = root / 'evidence.zip'; report_file = root / 'report.json'
            with zipfile.ZipFile(archive, 'w') as packed:
                for name, value in files.items(): packed.writestr(name, value)
            report['logArchiveSha256'] = audit.digest(archive.read_bytes())
            report_file.write_text(json.dumps(report))
            self.assertEqual(86, audit.audit(*audit.load_report(report_file))['observations'])
            archive.write_bytes(archive.read_bytes() + b'changed')
            with self.assertRaises(ValueError): audit.load_report(report_file)
            report['logArchive'] = '../evidence.zip'; report_file.write_text(json.dumps(report))
            with self.assertRaises(ValueError): audit.load_report(report_file)


if __name__ == '__main__':
    unittest.main()
