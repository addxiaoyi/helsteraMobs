"""Pinned native/imported precedence, flags and death callbacks over six real restarts."""
import argparse
from datetime import datetime,timezone
import importlib.util
import json
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time
import zipfile
sys.dont_write_bytecode=True
ROOT=Path(__file__).resolve().parents[2];TOOLS=ROOT/'tools/paper-integration'
def module(name,path):
    spec=importlib.util.spec_from_file_location(name,path);m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m);return m
common=module('common',TOOLS/'run-player-variable-fixture.py');contract=module('contract',TOOLS/'despawn-precedence-cases.py')
digest,redact=common.digest,common.redact

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--revision',required=True);parser.add_argument('--cache-directory',required=True)
    parser.add_argument('--observe-only',action='store_true');args=parser.parse_args()
    import yaml
    if not re.fullmatch(r'r[1-9][0-9]*',args.revision):raise ValueError('Invalid revision')
    validator=None if args.observe_only else module('validator',TOOLS/'validate-despawn-precedence.py')
    day=datetime.now(timezone.utc).strftime('%Y%m%d');fixture=f'srv-paper-despawn-precedence-{day}-{args.revision}';base=ROOT/fixture
    if base.exists():raise ValueError('Refusing to reuse fixture')
    paper=ROOT/'srv-1.21.1/paper.jar';native=TOOLS/'.external-cache/MythicMobs-5.7.2-FTWvGuE5.jar';plugin=ROOT/'helstera-plugin/target/helsteraMobs-1.0.0-full.jar'
    if digest(paper)!=common.PAPER_HASH or digest(native)!=common.NATIVE_HASH:raise ValueError('Wrong pinned dependencies')
    subprocess.run([str(ROOT/'mvnw'),'-q','-f',str(TOOLS/'random-spawn-fixture/pom.xml'),'package'],check=True,cwd=ROOT)
    base.mkdir();evidence=base/'evidence';evidence.mkdir()
    probe=base/'probe.jar'
    with zipfile.ZipFile(TOOLS/'random-spawn-fixture/target/random-spawn-paper-fixture-1.0.0.jar') as src,zipfile.ZipFile(probe,'w',zipfile.ZIP_DEFLATED) as dest:
        for item in src.infolist():
            if item.filename!='plugin.yml':dest.writestr(item,src.read(item.filename))
        dest.writestr('plugin.yml',"name: HelsteraDespawnPrecedenceProbe\nversion: '1.0'\napi-version: '1.21'\nmain: dev.helstera.test.randomspawn.DespawnPrecedenceProbe\ndepend: [helsteraMobs, MythicMobs]\n")
    sources=[Path(__file__).resolve(),TOOLS/'despawn-precedence-cases.py',TOOLS/'run-player-variable-fixture.py',TOOLS/'random-spawn-fixture/src/main/java/dev/helstera/test/randomspawn/DespawnPrecedenceProbe.java']
    if validator:sources.extend([TOOLS/'validate-despawn-precedence.py',TOOLS/'test-despawn-precedence-evidence.py',TOOLS/'validate-player-variables.py'])
    report={'schemaVersion':1,'fixtureDirectory':fixture,'evidenceMode':'observation' if args.observe_only else 'comparison-with-exception',
        'knownExceptions':['MM-5.7.2-shutdown-command-scheduling'],'generatedAtUtc':datetime.now(timezone.utc).isoformat(),
        'pluginSha256':digest(plugin),'paperJarSha256':common.PAPER_HASH,'mythicMobsSha256':common.NATIVE_HASH,'probeJarSha256':digest(probe),
        'sourceHashes':{str(p.relative_to(ROOT)):digest(p) for p in sources},'boots':[],'observations':[],'files':[]}
    for group,value in contract.GLOBALS.items():
        server=base/group;server.mkdir();output_dir=evidence/group;output_dir.mkdir()
        for name in ('cache','libraries','versions'):shutil.copytree(ROOT/args.cache_directory/name,server/name)
        shutil.copy2(paper,server/'paper.jar');shutil.copy2(TOOLS/'server.properties',server/'server.properties');(server/'eula.txt').write_text('eula=true\n')
        plugins=server/'plugins';plugins.mkdir();shutil.copy2(plugin,plugins/'helsteraMobs.jar');shutil.copy2(native,plugins/'MythicMobs.jar');shutil.copy2(probe,plugins/'HelsteraDespawnPrecedenceProbe.jar')
        probe_data=plugins/'HelsteraDespawnPrecedenceProbe';(probe_data/'Mobs').mkdir(parents=True);(probe_data/'config').mkdir()
        native_data=plugins/'MythicMobs';(native_data/'Mobs').mkdir(parents=True);(native_data/'config').mkdir()
        (native_data/'config/config-general.yml').write_text('Configuration:\n  Clock:\n    Saving: 300\n')
        defaults={'Configuration':{'DefaultMobOptions':{}}}
        if group!='absent':defaults['Configuration']['DefaultMobOptions']['Despawn']=value
        for path in (probe_data/'config/config-mobs.yml',native_data/'config/config-mobs.yml',output_dir/'defaults.yml'):path.write_text(yaml.safe_dump(defaults,sort_keys=False))
        mobs={}
        for key,case in contract.cases().items():
            fields=json.loads(json.dumps(case['source']));fields['Type']='COW';fields.setdefault('Options',{}).update(NoAI=True,NoGravity=True,Invincible=True,Collidable=False)
            fields['Skills']=[f'command{{c="say DPREC_HOOK native {event} {key}"}} @self ~on{event.title()}' for event in ('despawn','death')];mobs[key]=fields
        for path in (probe_data/'Mobs/cases.yml',native_data/'Mobs/cases.yml',output_dir/'source.yml'):path.write_text(yaml.safe_dump(mobs,sort_keys=False))
        data=plugins/'helsteraMobs';data.mkdir();config=(TOOLS/'config.yml').read_text()
        config=re.sub(r'(?m)^  (self-test(?:-[A-Za-z0-9-]+)?):\s*true\s*$',r'  \1: false',config)
        config=re.sub(r'(?m)^  trace-triggers:.*$','  trace-triggers: false',config);config=re.sub(r'(?m)^  mythicmobs: false\s*$','  mythicmobs: true',config)
        if re.search(r'(?m)^web:\s*\n[ \t]+enabled:\s*true',config):raise ValueError('Web must stay disabled')
        (data/'config.yml').write_text(config)
        for name in ('items.yml','loot.yml'):shutil.copy2(TOOLS/name,data/name)
        (data/'skills.yml').write_text('skills: {}\n');(data/'spawners.yml').write_text('spawners: {}\n');(data/'mobs').mkdir()
        for stage in ('write','verify'):
            (probe_data/'config.yml').write_text('stage: '+stage+'\n');latest=server/'logs/latest.log'
            if latest.exists():latest.rename(server/f'before-{stage}.log')
            with (server/f'{stage}.stdout.log').open('w') as out:
                process=subprocess.Popen(['java','-Xms1G','-Xmx2G','-jar','paper.jar','nogui'],cwd=server,stdin=subprocess.PIPE,stdout=out,stderr=subprocess.STDOUT,text=True)
                started=datetime.now(timezone.utc).isoformat()
                try:
                    deadline=time.monotonic()+240
                    while time.monotonic()<deadline and process.poll() is None:
                        text=latest.read_text(errors='replace') if latest.exists() else ''
                        if 'DPREC_FAILED|' in text:raise RuntimeError(f'Probe failed: {group}/{stage}')
                        if f'DPREC_SUMMARY|stage={stage}|' in text:break
                        time.sleep(.25)
                    else:raise RuntimeError(f'Probe timeout: {group}/{stage}')
                finally:
                    if process.poll() is None:
                        process.stdin.write('stop\n');process.stdin.flush()
                        try:process.wait(timeout=40)
                        except subprocess.TimeoutExpired:process.kill();process.wait()
                    if latest.exists():(output_dir/f'{stage}.log').write_text(redact(latest.read_text(errors='replace')))
                if process.returncode:raise RuntimeError('Unclean Paper shutdown')
            report['boots'].append({'group':group,'stage':stage,'startedAtUtc':started,'stoppedAtUtc':datetime.now(timezone.utc).isoformat(),'exitCode':process.returncode})
            report['observations'] += [dict(group=group,line=l) for l in (output_dir/f'{stage}.log').read_text().splitlines() if 'DPREC_ROW|' in l]
            print('Completed precedence boot:',group,stage,flush=True)
        for name in ('identities.yml','migration.yml'):shutil.copy2(probe_data/name,output_dir/name)
        shutil.copytree(probe_data/'imports',output_dir/'imports')
        for path,expected in [(plugins/'helsteraMobs.jar',report['pluginSha256']),(plugins/'MythicMobs.jar',report['mythicMobsSha256']),(server/'paper.jar',report['paperJarSha256']),(plugins/'HelsteraDespawnPrecedenceProbe.jar',report['probeJarSha256'])]:
            if digest(path)!=expected:raise RuntimeError('Artifact changed')
    for path,expected in report['sourceHashes'].items():
        if digest(ROOT/path)!=expected:raise RuntimeError('Source changed during fixture')
    for p in sorted(evidence.rglob('*')):
        if p.is_file():report['files'].append({'path':str(p.relative_to(evidence)),'sha256':digest(p)})
    local=evidence/'despawn-precedence-report.json';local.write_text(json.dumps(report,indent=2)+'\n');error=None
    if validator:
        try:validator.main(evidence)
        except (ValueError,KeyError) as e:error=e;report['evidenceMode']='observation';report['validationResult']='rejected: '+str(e)
    name=f'paper-1.21.1-despawn-precedence-{day}-{args.revision}';report_path=TOOLS/'reports'/(name+'.json');archive_path=TOOLS/'reports'/(name+'-logs.zip')
    if report_path.exists() or archive_path.exists():raise RuntimeError('Evidence already exists')
    with zipfile.ZipFile(archive_path,'x',zipfile.ZIP_DEFLATED) as archive:
        for entry in report['files']:archive.write(evidence/entry['path'],entry['path'])
    report['logArchive']=archive_path.name;report['logArchiveSha256']=digest(archive_path);report_path.write_text(json.dumps(report,indent=2)+'\n')
    if error:raise RuntimeError('Rejected evidence archived: '+str(error)) from error
    if validator:validator.main(report_path)
    print('Precedence report:',report_path)
if __name__=='__main__':main()
