"""Altered-evidence controls for the independent block selection/effect audit."""
import copy
import importlib.util
import json
from pathlib import Path
import unittest
import uuid
import yaml

TOOLS = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('block_audit', TOOLS/'audit-block-targeter.py')
audit = importlib.util.module_from_spec(spec)
spec.loader.exec_module(audit)


def manifest(report, files):
    report['files'] = [{'path':name,'sha256':audit.digest(data)} for name,data in files.items()]


def fixture():
    rows,logs = [],[]
    chunks = sorted(f'{x},{z}' for x in range(-2,4) for z in range(-2,3))
    for path in ('direct','imported'):
        for case,(selector,expected,raw) in audit.CASES.items():
            entities,locations = audit.inputs(case)
            origin,caster,scene,blocks = audit.preparation(case)
            rows.append({'path':path,'case':case,'selector':selector,'source':audit.source(case),
                         'binding':audit.source(case) if path == 'direct' else audit.binding(case),
                         'nativeSelected':expected,'runtimeSelected':expected,'nativeRawCount':raw,
                         'nativeEffects':audit.effects(case),'runtimeEffects':audit.effects(case,True),
                         'nativeAccepted':True,'runtimeAccepted':True,'scene':scene,'blocks':blocks,
                         'entityInputs':entities,'locationInputs':locations,'origin':origin,'caster':caster,
                         'nativeEntityInputs':sorted(entities),'nativeLocationInputs':locations,
                         'mutations':case != 'bic-maxheight','loadedBefore':chunks,'loadedAfter':chunks})
            logs.append(f'BLOCKS_ROW|path={path}|case={case}|observed=true')
    count = len(rows)
    logs.append(f'BLOCKS_SUMMARY|observations={count}|passed=true')
    events = [{'event':'join','name':'BlockTester','uuid':str(uuid.uuid4())},
              {'event':'done','name':'BlockTester'},{'event':'summary','passed':True}]
    files = {'server.log':'\n'.join(logs).encode(),
             'client.log':'\n'.join('BLOCKS_CLIENT '+json.dumps(event) for event in events).encode(),
             'plugins/MythicMobs/Mobs/location.yml':yaml.safe_dump({'HelsteraRadiusAuditMob':{
                 'Type':'COW','ThreatTable':{'Enabled':True,'DecayUnreachable':False},
                 'Options':{'NoAI':True,'Despawn':'PERSISTENT'}}}).encode(),
             audit.DATA:yaml.safe_dump({'observations':rows}).encode()}
    report = {'schemaVersion':1,'evidenceMode':'observation','parityStatus':'unassessed',
              'validationResult':'passed','exitCode':0,'clientExitCode':0,'observationCount':count,
              'paperJarSha256':'39bd8c00b9e18de91dcabd3cc3dcfa5328685a53b7187a2f63280c22e2d287b9',
              'mythicMobsSha256':'9672457cafc56ff841606bfbb710947329c08720ffc207746b60130d43bf902f',
              'pluginSha256':'a'*64,'probeJarSha256':'b'*64,
              'sourceHashes':{name:'c'*64 for name in audit.SOURCE_FILES}}
    manifest(report,files)
    return report,files


class AuditTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.baseline = fixture()

    def test_complete_synthetic_evidence(self):
        self.assertEqual(2*len(audit.CASES),audit.audit(*copy.deepcopy(self.baseline))['observations'])

    def mutate(self, operation):
        report,files = copy.deepcopy(self.baseline)
        rows = yaml.safe_load(files[audit.DATA])['observations']
        operation(rows)
        files[audit.DATA] = yaml.safe_dump({'observations':rows}).encode()
        manifest(report,files)
        with self.assertRaises(ValueError): audit.audit(report,files)

    def change(self, case, field, value):
        self.mutate(lambda rows:next(row for row in rows if row['case'] == case).__setitem__(field,value))

    def test_duplicate_or_missing_case(self):
        self.mutate(lambda rows:rows.__setitem__(1,copy.deepcopy(rows[0])))

    def test_every_observation_field_matters(self):
        for field,value in {'path':'other','case':'other','selector':'@self','source':'noop','binding':'noop',
                            'nativeSelected':[],'runtimeSelected':[],'nativeEffects':[],'runtimeEffects':[],
                            'nativeRawCount':0,'nativeAccepted':False,'runtimeAccepted':False,
                            'scene':'wrong','blocks':[],'origin':audit.point(10),'caster':audit.point(10),
                            'entityInputs':['invented'],'locationInputs':['invented'],
                            'nativeEntityInputs':['invented'],'nativeLocationInputs':['invented'],
                            'mutations':False,'loadedBefore':[],'loadedAfter':[]}.items():
            with self.subTest(field=field): self.change('highest-default',field,value)

    def test_consistent_wrong_selection_and_effects_are_rejected(self):
        def changed(rows):
            for field in ('nativeSelected','runtimeSelected','nativeEffects','runtimeEffects'): rows[0][field] = []
        self.mutate(changed)

    def test_inheritance_must_be_prepared_on_both_sides(self):
        for case,fields in [('bir-entities',('entityInputs','nativeEntityInputs')),
                           ('bic-locations',('locationInputs','nativeLocationInputs'))]:
            for field in fields:
                with self.subTest(case=case,field=field): self.change(case,field,[])

    def test_empty_inheritance_cannot_fall_back_to_caster(self):
        self.change('bir-empty','runtimeSelected',[audit.point(40.25)])

    def test_fractional_selection_is_distinct_from_block_effect_coordinates(self):
        self.change('radius-fractional','runtimeSelected',[audit.point(0)])
        self.change('radius-fractional','runtimeEffects',audit.CASES['radius-fractional'][1])

    def test_chunk_and_obstruction_offsets_are_not_applied(self):
        self.change('obstruction-mutate-noop','runtimeSelected',[audit.point(49,189,1)])
        self.change('bic-mutate-noop','runtimeEffects',[audit.point(5,188,0)+'=DIAMOND_BLOCK'])

    def test_air_only_and_negative_chunk_selection_are_enforced(self):
        self.change('mask-only-air','nativeSelected',audit.R1)
        self.change('bic-negative','runtimeSelected',audit.CHUNK)

    def test_guard_difference_cannot_be_hidden(self):
        self.change('guard-false','nativeEffects',[])
        self.change('guard-false','runtimeEffects',audit.effects('guard-false'))

    def test_vein_budget_is_not_a_final_count(self):
        self.change('vein-budget-one','runtimeSelected',[audit.NEIGHBORS[0]])
        self.change('vein-isolated','runtimeSelected',[audit.point(.25)])

    def test_maxheight_is_query_only_and_inclusive(self):
        self.change('bic-maxheight','runtimeSelected',[audit.point(15,319,15)])
        self.change('bic-maxheight','runtimeEffects',[audit.point(15,320,15)+'=DIAMOND_BLOCK'])

    def test_mutation_outside_selection_is_rejected(self):
        self.change('bic-empty','runtimeEffects',audit.effects('highest-default'))

    def test_new_chunk_loading_is_rejected(self):
        def changed(rows): rows[0]['loadedAfter'] = sorted(rows[0]['loadedAfter']+['100,100'])
        self.mutate(changed)

    def test_logs_client_and_source_manifest_are_required(self):
        for changed in ('server.log','client.log','sourceHashes'):
            report,files = copy.deepcopy(self.baseline)
            if changed == 'sourceHashes': report[changed].pop(next(iter(report[changed])))
            else: files[changed] = b''
            manifest(report,files)
            with self.assertRaises(ValueError): audit.audit(report,files)


if __name__ == '__main__': unittest.main()
