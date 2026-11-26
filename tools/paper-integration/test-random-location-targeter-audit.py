"""Negative controls for independently recalculated random selection evidence."""
import copy
import importlib.util
import json
from pathlib import Path
import unittest
import uuid
import yaml

TOOLS=Path(__file__).resolve().parent
spec=importlib.util.spec_from_file_location('random_audit',TOOLS/'audit-random-location-targeter.py')
audit=importlib.util.module_from_spec(spec);spec.loader.exec_module(audit)


def manifest(report,files):report['files']=[{'path':name,'sha256':audit.digest(data)} for name,data in files.items()]


def fixture():
    rows=[];logs=[]
    for path in ('direct','imported'):
        for name,c in audit.CASES.items():
            raw,selected=audit.expected(c,77);rng=audit.Rng(c['seed'],77)
            rows.append({'path':path,'case':name,'selector':c['selector'],'source':audit.source(c),
                         'binding':audit.source(c) if path=='direct' else audit.binding(c),
                         'scene':c['scene'],'inputs':c['inputs'],'seed':c['seed'],'threadId':77,
                         'rngProbe':[rng.next() for _ in range(3)],
                         'parsed':{'amount':c['amount'],'maxRadius':c['radius'],'minRadius':c['minimum'],'spacing':c['spacing']},
                         'nativeRaw':sorted(audit.point(p) for p in raw),'nativeSelected':sorted(audit.point(p) for p in selected),
                         'runtimeSelected':sorted(audit.point(p) for p in selected),'nativeEffects':audit.effects(selected),
                         'runtimeEffects':audit.effects(selected) if c['guard'] else [],'nativeAccepted':True,'runtimeAccepted':True})
            logs.append(f'RANDOM_LOCATION_ROW|path={path}|case={name}|observed=true')
    logs.append(f'RANDOM_LOCATION_SUMMARY|observations={len(rows)}|passed=true')
    events=[{'event':'join','name':'RandomTester','uuid':str(uuid.uuid4())},{'event':'done','name':'RandomTester'},{'event':'summary','passed':True}]
    files={'server.log':'\n'.join(logs).encode(),'client.log':'\n'.join('RANDOM_LOCATION_CLIENT '+json.dumps(e) for e in events).encode(),
           'plugins/MythicMobs/Mobs/location.yml':yaml.safe_dump({'HelsteraRadiusAuditMob':{
               'Type':'COW','ThreatTable':{'Enabled':True,'DecayUnreachable':False},'Options':{'NoAI':True,'Despawn':'PERSISTENT'}}}).encode(),
           audit.DATA:yaml.safe_dump({'observations':rows}).encode()}
    report={'schemaVersion':1,'evidenceMode':'observation','parityStatus':'unassessed','validationResult':'passed',
            'exitCode':0,'clientExitCode':0,'observationCount':len(rows),'pluginSha256':'a'*64,'probeJarSha256':'b'*64,
            'paperJarSha256':'39bd8c00b9e18de91dcabd3cc3dcfa5328685a53b7187a2f63280c22e2d287b9',
            'mythicMobsSha256':'9672457cafc56ff841606bfbb710947329c08720ffc207746b60130d43bf902f',
            'sourceHashes':{name:'c'*64 for name in audit.SOURCE_FILES}}
    manifest(report,files);return report,files


class AuditTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):cls.baseline=fixture()

    def test_complete_synthetic_evidence(self):self.assertEqual(2*len(audit.CASES),audit.audit(*copy.deepcopy(self.baseline))['observations'])

    def mutate(self,operation):
        report,files=copy.deepcopy(self.baseline);rows=yaml.safe_load(files[audit.DATA])['observations'];operation(rows)
        files[audit.DATA]=yaml.safe_dump({'observations':rows}).encode();manifest(report,files)
        with self.assertRaises(ValueError):audit.audit(report,files)

    def change(self,name,field,value):self.mutate(lambda rows:next(r for r in rows if r['case']==name).__setitem__(field,value))

    def test_every_field_matters(self):
        for field,value in {'path':'missing','case':'missing','selector':'@origin','source':'noop','binding':'noop','scene':'solid',
                            'inputs':'empty','seed':0,'threadId':78,'rngProbe':[0,0,0],'parsed':{},'nativeRaw':[],'nativeSelected':[],
                            'runtimeSelected':[],'nativeEffects':[],'runtimeEffects':[],'nativeAccepted':False,'runtimeAccepted':False}.items():
            with self.subTest(field=field):self.change('alias-RLO',field,value)

    def test_duplicate_or_missing_observations(self):self.mutate(lambda rows:rows.__setitem__(1,copy.deepcopy(rows[0])))

    def test_consistently_fabricated_coordinates_and_effects_are_rejected(self):
        def corrupt(rows):
            row=rows[0]
            for field in ('nativeRaw','nativeSelected','runtimeSelected'):row[field]=[audit.point((900,180,0))]*3
            for field in ('nativeEffects','runtimeEffects'):row[field]=audit.effects([(900,180,0)])
        self.mutate(corrupt)

    def test_duplicate_multiplicity_is_required(self):
        for field in ('nativeRaw','nativeSelected','runtimeSelected'):
            self.change('duplicates',field,[audit.point((-40.25,181.25,.25))])

    def test_mutation_spacing_and_collision_oracles_are_not_only_equality_checks(self):
        for case in ('mixed-RLO','solid-RLO','minimum-RLO','spacing-global','spacing-mutated','mutation-RLNC','limit'):
            def corrupt(rows):
                row=next(r for r in rows if r['case']==case)
                for field in ('nativeRaw','nativeSelected','runtimeSelected'):row[field]=[audit.point((0,180,0))]
                for field in ('nativeEffects','runtimeEffects'):row[field]=audit.effects([(0,180,0)])
            with self.subTest(case=case):self.mutate(corrupt)

    def test_empty_selection_and_guards_cannot_create_effects(self):
        for case in ('empty','amount-zero','amount-negative','min-before-mutation','filter-reject','guard-false'):
            self.change(case,'runtimeEffects',audit.effects([(0,180,0)]))
        self.change('guard-false','nativeEffects',[])

    def test_height_orientation_and_world_are_checked(self):
        for value in ('integration-world:0,180,0,1,0','wrong-world:0,180,0,0,0','integration-world:NaN,180,0,0,0'):
            self.change('solid-RLO','nativeRaw',[value])

    def test_extra_effects_are_rejected(self):
        def corrupt(rows):rows[0]['runtimeEffects'].append(audit.effects([(0,0,0)])[0])
        self.mutate(corrupt)

    def test_arguments_are_independently_required(self):
        for field in ('amount','maxRadius','minRadius','spacing'):
            def corrupt(rows):rows[0]['parsed'][field]+=1
            self.mutate(corrupt)

    def test_logs_client_sources_and_hashes_are_required(self):
        for changed in ('server.log','client.log','sourceHashes','digest'):
            report,files=copy.deepcopy(self.baseline)
            if changed=='sourceHashes':report[changed].pop(next(iter(report[changed])))
            elif changed=='digest':files['server.log']+=b' altered';self.assertRaises(ValueError,audit.audit,report,files);continue
            else:files[changed]=b''
            manifest(report,files)
            with self.assertRaises(ValueError):audit.audit(report,files)


if __name__=='__main__':unittest.main()
