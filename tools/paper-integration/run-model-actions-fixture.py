"""Cold-boot contract evidence for Helstera-defined actions dispatched by native MM 5.7.2."""
import argparse
from datetime import datetime, timezone
import importlib.util
import json
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time
import zipfile
import yaml
sys.dont_write_bytecode=True
ROOT=Path(__file__).resolve().parents[2]
TOOLS=ROOT/'tools/paper-integration'
def module(name,filename):
    spec=importlib.util.spec_from_file_location(name,TOOLS/filename)
    result=importlib.util.module_from_spec(spec);spec.loader.exec_module(result);return result
common=module('actions_common','run-player-variable-fixture.py')
cases=module('actions_cases','model-actions-cases.py')
digest,redact=common.digest,common.redact
SOURCES=['run-model-actions-fixture.py','run-player-variable-fixture.py','model-actions-cases.py',
    'random-spawn-fixture/src/main/java/dev/helstera/test/randomspawn/ModelActionsProbe.java']

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--revision',required=True);parser.add_argument('--cache-directory',required=True);parser.add_argument('--observe-only',action='store_true');args=parser.parse_args()
    if not re.fullmatch(r'r[1-9][0-9]*',args.revision):raise ValueError('Invalid revision')
    day=datetime.now(timezone.utc).strftime('%Y%m%d');fixture=f'srv-paper-model-actions-{day}-{args.revision}';server=ROOT/fixture
    if server.exists():raise ValueError('Refusing to reuse fixture')
    paper=ROOT/'srv-1.21.1/paper.jar';native=TOOLS/'.external-cache/MythicMobs-5.7.2-FTWvGuE5.jar';plugin=ROOT/'helstera-plugin/target/helsteraMobs-1.0.0-full.jar'
    if digest(paper)!=common.PAPER_HASH or digest(native)!=common.NATIVE_HASH:raise ValueError('Wrong pinned dependencies')
    subprocess.run([str(ROOT/'mvnw'),'-q','-f',str(TOOLS/'random-spawn-fixture/pom.xml'),'package'],check=True,cwd=ROOT)
    server.mkdir()
    for name in ('cache','libraries','versions'):shutil.copytree(Path(args.cache_directory)/name,server/name)
    shutil.copy2(paper,server/'paper.jar');shutil.copy2(TOOLS/'server.properties',server/'server.properties');(server/'eula.txt').write_text('eula=true\n')
    plugins=server/'plugins';plugins.mkdir();shutil.copy2(plugin,plugins/'helsteraMobs.jar');shutil.copy2(native,plugins/'MythicMobs.jar')
    probe=plugins/'HelsteraModelActionsProbe.jar'
    with zipfile.ZipFile(TOOLS/'random-spawn-fixture/target/random-spawn-paper-fixture-1.0.0.jar') as source,zipfile.ZipFile(probe,'w',zipfile.ZIP_DEFLATED) as dest:
        for item in source.infolist():
            if item.filename!='plugin.yml':dest.writestr(item,source.read(item.filename))
        dest.writestr('plugin.yml',"name: HelsteraModelActionsProbe\nversion: '1.0'\napi-version: '1.21'\nmain: dev.helstera.test.randomspawn.ModelActionsProbe\ndepend: [helsteraMobs, MythicMobs]\n")
    for kind,content in [('Mobs',cases.native_mobs()),('Skills',cases.native_skills())]:
        folder=plugins/'MythicMobs'/kind;folder.mkdir(parents=True);(folder/'model-actions.yml').write_text(yaml.safe_dump(content,sort_keys=False))
        shutil.copy2(folder/'model-actions.yml',server/f'source-{kind.lower()}.yml')
    data=plugins/'helsteraMobs';data.mkdir();config=(TOOLS/'config.yml').read_text()
    config=re.sub(r'(?m)^  (self-test(?:-[A-Za-z0-9-]+)?):\s*true\s*$',r'  \1: false',config)
    config=re.sub(r'(?m)^  trace-triggers:.*$','  trace-triggers: false',config);config=re.sub(r'(?m)^  mythicmobs: false\s*$','  mythicmobs: true',config)
    if re.search(r'(?m)^web:\s*\n[ \t]+enabled:\s*true',config):raise ValueError('Web interface must be disabled')
    (data/'config.yml').write_text(config)
    for name in ('items.yml','loot.yml'):shutil.copy2(TOOLS/name,data/name)
    (data/'skills.yml').write_text('skills: {}\n');(data/'spawners.yml').write_text('spawners: {}\n');(data/'mobs').mkdir()
    (data/'integrations.yml').write_text(yaml.safe_dump({'mythicmobs':{'mobs':{}}}))
    shutil.copy2(data/'integrations.yml',server/'source-integrations.yml')
    sources=SOURCES+([] if args.observe_only else ['validate-model-actions.py','validate-player-variables.py','test-model-actions-evidence.py'])
    report={'schemaVersion':1,'extensionContract':'helstera-defined','nativeParity':False,'fixtureDirectory':fixture,'evidenceMode':'observation' if args.observe_only else 'scoped-runtime',
        'generatedAtUtc':datetime.now(timezone.utc).isoformat(),'pluginSha256':digest(plugin),'paperJarSha256':common.PAPER_HASH,
        'mythicMobsSha256':common.NATIVE_HASH,'probeJarSha256':digest(probe),
        'sourceHashes':{str((TOOLS/p).relative_to(ROOT)):digest(TOOLS/p) for p in sources},'observations':[],'files':[]}
    latest=server/'logs/latest.log';error=None;started=datetime.now(timezone.utc).isoformat()
    with (server/'stdout.log').open('w') as output:
        process=subprocess.Popen(['java','-Xms1G','-Xmx2G','-jar','paper.jar','nogui'],cwd=server,stdin=subprocess.PIPE,stdout=output,stderr=subprocess.STDOUT,text=True)
        try:
            deadline=time.monotonic()+300
            while time.monotonic()<deadline and process.poll() is None:
                text=latest.read_text(errors='replace') if latest.exists() else ''
                if 'ACTIONS_FAILED|' in text:raise RuntimeError('Model actions probe failed')
                if 'ACTIONS_SUMMARY|' in text:break
                time.sleep(.25)
            else:raise RuntimeError('Model actions probe timed out or exited')
        except RuntimeError as exc:error=exc
        finally:
            if process.poll() is None:
                process.stdin.write('stop\n');process.stdin.flush()
                try:process.wait(timeout=40)
                except subprocess.TimeoutExpired:process.kill();process.wait()
            if latest.exists():(server/'server.log').write_text(redact(latest.read_text(errors='replace')))
    if not (server/'server.log').exists():(server/'server.log').write_text('')
    if process.returncode:error=RuntimeError('Unclean shutdown')
    report['boot']={'startedAtUtc':started,'stoppedAtUtc':datetime.now(timezone.utc).isoformat(),'exitCode':process.returncode}
    shutil.copy2(plugins/'MythicMobs/Skills/model-actions.yml',server/'reloaded-skills.yml')
    report['observations']=[l for l in (server/'server.log').read_text().splitlines() if 'ACTIONS_ROW|' in l]
    for name,expected in report['sourceHashes'].items():
        if digest(ROOT/name)!=expected:raise RuntimeError('Source changed during fixture')
    for p,key in [(server/'paper.jar','paperJarSha256'),(plugins/'MythicMobs.jar','mythicMobsSha256'),(plugins/'helsteraMobs.jar','pluginSha256'),(probe,'probeJarSha256')]:
        if digest(p)!=report[key]:raise RuntimeError('Artifact changed during fixture')
    for name in ('server.log','source-mobs.yml','source-skills.yml','source-integrations.yml','reloaded-skills.yml'):
        report['files'].append({'path':name,'sha256':digest(server/name)})
    local=server/'model-actions-report.json';local.write_text(json.dumps(report,indent=2)+'\n')
    validator=None if args.observe_only else module('actions_validator','validate-model-actions.py')
    if validator and error is None:
        try:validator.main(server)
        except (ValueError,KeyError) as exc:error=exc
    if error:report['evidenceMode']='observation';report['validationResult']='rejected: '+str(error);local.write_text(json.dumps(report,indent=2)+'\n')
    name=f'paper-1.21.1-model-actions-{day}-{args.revision}';report_path=TOOLS/'reports'/(name+'.json');archive_path=TOOLS/'reports'/(name+'-logs.zip')
    if report_path.exists() or archive_path.exists():raise RuntimeError('Refusing to overwrite evidence')
    with zipfile.ZipFile(archive_path,'x',zipfile.ZIP_DEFLATED) as archive:
        for f in report['files']:archive.write(server/f['path'],f['path'])
    report['logArchive']=archive_path.name;report['logArchiveSha256']=digest(archive_path);report_path.write_text(json.dumps(report,indent=2)+'\n')
    print('Model actions evidence:',report_path,flush=True)
    if error:raise RuntimeError('Rejected evidence archived: '+str(error)) from error
    if validator:validator.main(report_path)
if __name__=='__main__':main()
