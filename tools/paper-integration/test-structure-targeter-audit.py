"""Tampered parameter, query-event and structure-effect evidence controls."""
import copy
import importlib.util
import json
from pathlib import Path
import unittest
import uuid
import yaml

TOOLS = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('structure_audit',TOOLS/'audit-structure-targeter.py')
audit = importlib.util.module_from_spec(spec);spec.loader.exec_module(audit)


def manifest(report,files): report['files'] = [{'path':n,'sha256':audit.digest(d)} for n,d in files.items()]


def fixture():
    rows,logs = [],[]
    for path in ('direct','imported'):
        for case,(selector,structure,radius,unexplored,mode) in audit.CASES.items():
            natural = case.startswith('natural-stronghold')
            raw = [audit.SEEDED_STRONGHOLD] if natural else [] if mode == 'cancel' or case == 'natural-missing' else [audit.point(12 if mode == 'alternate' else 8)]
            selected = [] if case == 'filter-reject' else raw
            effects = [p+'=DIAMOND_BLOCK' for p in selected]
            queries = [{'phase':phase,'origin':audit.point(-41,180,-1) if case == 'moved-caster' else audit.point(40),
                        'types':sorted('minecraft:'+t for t in audit.STRUCTURES[structure]),
                        'radius':radius,'unexplored':unexplored,'mode':mode} for phase in ('native','runtime','effect')]
            rows.append({'path':path,'case':case,'selector':selector,'source':audit.source(case),
                         'binding':audit.source(case) if path == 'direct' else audit.binding(case),'mode':mode,
                         'seed':281474976710655,'caster':audit.point(-40.25,180,-.25) if case == 'moved-caster' else audit.point(40.25,180,.25),
                         'origin':audit.point(800.25,180,800.25),'parsed':{'structureType':structure,'radius':radius,'unexplored':unexplored},
                         'queries':queries,'naturalStructures':['minecraft:stronghold'] if natural else [],
                         'nativeRaw':raw,'nativeSelected':selected,'runtimeSelected':selected,
                         'nativeEffects':effects,'runtimeEffects':[] if case == 'guard-false' else effects,
                         'nativeAccepted':True,'runtimeAccepted':True})
            logs.append(f'STRUCTURE_ROW|path={path}|case={case}|observed=true')
    logs.append(f'STRUCTURE_SUMMARY|observations={len(rows)}|passed=true')
    events = [{'event':'join','name':'StructureTester','uuid':str(uuid.uuid4())},{'event':'done','name':'StructureTester'},
              {'event':'summary','passed':True}]
    files = {'server.log':'\n'.join(logs).encode(),
             'client.log':'\n'.join('STRUCTURE_CLIENT '+json.dumps(e) for e in events).encode(),
             'plugins/MythicMobs/Mobs/location.yml':yaml.safe_dump({'HelsteraRadiusAuditMob':{
                 'Type':'COW','ThreatTable':{'Enabled':True,'DecayUnreachable':False},'Options':{'NoAI':True,'Despawn':'PERSISTENT'}}}).encode(),
             audit.DATA:yaml.safe_dump({'observations':rows}).encode()}
    report = {'schemaVersion':1,'evidenceMode':'observation','parityStatus':'unassessed','validationResult':'passed',
              'exitCode':0,'clientExitCode':0,'observationCount':len(rows),'pluginSha256':'a'*64,'probeJarSha256':'b'*64,
              'paperJarSha256':'39bd8c00b9e18de91dcabd3cc3dcfa5328685a53b7187a2f63280c22e2d287b9',
              'mythicMobsSha256':'9672457cafc56ff841606bfbb710947329c08720ffc207746b60130d43bf902f',
              'sourceHashes':{n:'c'*64 for n in audit.SOURCE_FILES}}
    manifest(report,files);return report,files


class AuditTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls): cls.baseline = fixture()

    def test_complete_synthetic_evidence(self): self.assertEqual(2*len(audit.CASES),audit.audit(*copy.deepcopy(self.baseline))['observations'])

    def mutate(self,operation):
        report,files = copy.deepcopy(self.baseline);rows = yaml.safe_load(files[audit.DATA])['observations'];operation(rows)
        files[audit.DATA] = yaml.safe_dump({'observations':rows}).encode();manifest(report,files)
        with self.assertRaises(ValueError):audit.audit(report,files)

    def change(self,case,field,value): self.mutate(lambda rows:next(r for r in rows if r['case'] == case).__setitem__(field,value))

    def test_every_observed_field_is_required(self):
        for field,value in {'path':'other','case':'other','selector':'@origin','source':'noop','binding':'noop','mode':'natural','seed':1,
                            'caster':audit.point(0),'origin':audit.point(0),'parsed':{},'queries':[],
                            'naturalStructures':['minecraft:stronghold'],'nativeRaw':[],'nativeSelected':[],'runtimeSelected':[],
                            'nativeEffects':[],'runtimeEffects':[],'nativeAccepted':False,'runtimeAccepted':False}.items():
            with self.subTest(field=field):self.change('default',field,value)

    def test_duplicate_missing_case(self):self.mutate(lambda rows:rows.__setitem__(1,copy.deepcopy(rows[0])))

    def test_query_arguments_are_observed_on_each_route(self):
        for index in range(3):
            for field,value in [('radius',1),('unexplored',True),('types',[]),('origin',audit.point(800)),('mode','cancel')]:
                def changed(rows): rows[0]['queries'][index][field] = value
                with self.subTest(index=index,field=field): self.mutate(changed)

    def test_consistent_wrong_coordinates_and_effects_are_rejected(self):
        def changed(rows):
            for field in ('nativeRaw','nativeSelected','runtimeSelected'):rows[0][field] = [audit.point(900)]
            for field in ('nativeEffects','runtimeEffects'):rows[0][field] = [audit.point(900)+'=DIAMOND_BLOCK']
        self.mutate(changed)

    def test_cancelled_search_has_no_result_or_effect(self):
        self.change('event-cancel','runtimeSelected',[audit.point(8)])
        self.change('event-cancel','runtimeEffects',[audit.point(8)+'=DIAMOND_BLOCK'])

    def test_natural_and_supplied_searches_cannot_be_conflated(self):
        self.change('natural-stronghold','naturalStructures',[])
        self.change('natural-stronghold','nativeRaw',[])
        self.change('natural-missing','runtimeSelected',[audit.point(8)])

    def test_native_guard_difference_is_explicit(self):
        self.change('guard-false','nativeEffects',[])
        self.change('guard-false','runtimeEffects',[audit.point(8)+'=DIAMOND_BLOCK'])

    def test_repeated_seeded_queries_keep_their_position(self): self.change('natural-stronghold-filter','nativeRaw',[audit.point(500)])

    def test_consistently_fabricated_natural_structure_coordinates_are_rejected(self):
        def changed(rows):
            for row in rows:
                if row['case'].startswith('natural-stronghold'):
                    for field in ('nativeRaw','nativeSelected','runtimeSelected'): row[field] = [audit.point(999)]
                    for field in ('nativeEffects','runtimeEffects'): row[field] = [audit.point(999)+'=DIAMOND_BLOCK']
        self.mutate(changed)

    def test_logs_client_hash_and_source_coverage_are_required(self):
        for changed in ('server.log','client.log','sourceHashes'):
            report,files = copy.deepcopy(self.baseline)
            if changed == 'sourceHashes':report[changed].pop(next(iter(report[changed])))
            else:files[changed] = b''
            manifest(report,files)
            with self.assertRaises(ValueError):audit.audit(report,files)


if __name__ == '__main__':unittest.main()
