"""Retain native/direct/imported target Goal observations on pinned Paper."""
import argparse
from datetime import datetime, timezone
import importlib.util
import json
from pathlib import Path
import re
import shutil
import socket
import subprocess
import sys
import time
import zipfile
import yaml
sys.dont_write_bytecode=True
ROOT=Path(__file__).resolve().parents[2]
TOOLS=ROOT/'tools/paper-integration'
def module(name,file):
    spec=importlib.util.spec_from_file_location(name,TOOLS/file)
    result=importlib.util.module_from_spec(spec);spec.loader.exec_module(result);return result
common=module('player_runner','run-player-variable-fixture.py')
cases=module('ai_target_cases','ai-target-selector-cases.py')

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--revision',required=True);parser.add_argument('--cache-directory',required=True,type=Path);args=parser.parse_args()
    if not re.fullmatch(r'r[1-9][0-9]*',args.revision):raise ValueError('Invalid revision')
    day=datetime.now(timezone.utc).strftime('%Y%m%d');fixture=f'srv-paper-ai-target-selector-{day}-{args.revision}';server=ROOT/fixture
    report_path=TOOLS/'reports'/f'paper-1.21.1-ai-target-selector-{day}-{args.revision}.json';archive_path=report_path.with_name(report_path.stem+'-logs.zip')
    if any(p.exists() for p in [server,report_path,archive_path]):raise ValueError('Refusing to overwrite evidence')
    paper=ROOT/'srv-1.21.1/paper.jar';native=TOOLS/'.external-cache/MythicMobs-5.7.2-FTWvGuE5.jar';plugin=ROOT/'helstera-plugin/target/helsteraMobs-1.0.0-full.jar'
    if common.digest(paper)!=common.PAPER_HASH or common.digest(native)!=common.NATIVE_HASH:raise ValueError('Wrong pinned dependencies')
    subprocess.run([str(ROOT/'mvnw'),'-q','-o','-f',str(TOOLS/'random-spawn-fixture/pom.xml'),'package'],cwd=ROOT,check=True)
    with socket.socket() as connection:
        if connection.connect_ex(('127.0.0.1',25731))==0:raise RuntimeError('Fixture port occupied')
    server.mkdir()
    for name in ['cache','libraries','versions']:shutil.copytree(args.cache_directory/name,server/name)
    shutil.copy2(paper,server/'paper.jar');shutil.copy2(TOOLS/'server.properties',server/'server.properties');(server/'eula.txt').write_text('eula=true\n')
    plugins=server/'plugins';plugins.mkdir();shutil.copy2(plugin,plugins/'helsteraMobs.jar');shutil.copy2(native,plugins/'MythicMobs.jar')
    probe=plugins/'HelsteraAiTargetSelectorProbe.jar'
    goal_classes=server/'native-test-goal';goal_classes.mkdir()
    classpath=':'.join(str(p) for directory in ['libraries','versions'] for p in (args.cache_directory/directory).rglob('*.jar'))
    subprocess.run(['javac','-cp',classpath,'-d',str(goal_classes),str(TOOLS/'native-ai-goal/PinnedTestGoal.java')],check=True)

    with zipfile.ZipFile(TOOLS/'random-spawn-fixture/target/random-spawn-paper-fixture-1.0.0.jar') as source,zipfile.ZipFile(probe,'w',zipfile.ZIP_DEFLATED) as dest:
        for item in source.infolist():
            if item.filename!='plugin.yml':dest.writestr(item,source.read(item.filename))
        for p in goal_classes.rglob('*.class'):dest.write(p,str(p.relative_to(goal_classes)))
        dest.writestr('plugin.yml',"name: HelsteraAiTargetSelectorProbe\nversion: '1.0'\napi-version: '1.21'\nmain: dev.helstera.test.randomspawn.AiTargetSelectorProbe\ndepend: [helsteraMobs, MythicMobs]\n")
    mobs=plugins/'MythicMobs/Mobs';mobs.mkdir(parents=True);(mobs/'ai-target-selector.yml').write_text(yaml.safe_dump(cases.MOBS,sort_keys=False))
    data=plugins/'helsteraMobs';data.mkdir()
    config=(TOOLS/'config.yml').read_text();config=re.sub(r'(?m)^  (self-test(?:-[A-Za-z0-9-]+)?):\s*true\s*$',r'  \1: false',config)
    config=re.sub(r'(?m)^  trace-triggers:.*$','  trace-triggers: false',config);config=re.sub(r'(?m)^  mythicmobs: false\s*$','  mythicmobs: true',config)
    if re.search(r'(?m)^web:\s*\n[ \t]+enabled:\s*true',config):raise ValueError('Web must be disabled')
    (data/'config.yml').write_text(config)
    for name in ['items.yml','loot.yml']:shutil.copy2(TOOLS/name,data/name)
    (data/'skills.yml').write_text('skills: {}\n');(data/'spawners.yml').write_text('spawners: {}\n')
    probe_data=plugins/'HelsteraAiTargetSelectorProbe';probe_data.mkdir();(probe_data/'cases.yml').write_text(yaml.safe_dump({'cases':cases.CASES},sort_keys=False))
    sources=['tools/paper-integration/'+name for name in ['run-ai-target-selector-fixture.py','ai-target-selector-cases.py','ai-target-selector-client.js','paper-1.21.1-client-protocol.js','audit-ai-target-selector.py','test-ai-target-selector-audit.py','run-player-variable-fixture.py','native-ai-goal/PinnedTestGoal.java','random-spawn-fixture/src/main/java/dev/helstera/test/randomspawn/AiTargetSelectorProbe.java','random-spawn-fixture/src/main/java/dev/helstera/test/randomspawn/VariablePlaceholderAudit.java']]
    sources+=['helstera-ai/src/main/java/dev/helstera/ai/'+name+'.java' for name in ['AiController','AiProfile','AiManager','NativeTargetSelectors','SelectorPlan','FactionService']]
    sources+=['helstera-ai/src/main/java/dev/helstera/ai/skill/'+name+'.java' for name in ['AiTargetActions','SkillService','SkillExtras','MythicSkillSpec']]
    sources+=['helstera-api/src/main/java/dev/helstera/api/util/AiTargetSelectorSpec.java','helstera-runtime/src/main/java/dev/helstera/runtime/instance/PaperTargetGoals.java','helstera-migration/src/main/java/dev/helstera/migration/importer/MythicMobsImporter.java']
    report=dict(schemaVersion=1,fixtureDirectory=fixture,evidenceMode='observation',parityStatus='unassessed',generatedAtUtc=datetime.now(timezone.utc).isoformat(),pluginSha256=common.digest(plugin),paperJarSha256=common.PAPER_HASH,mythicMobsSha256=common.NATIVE_HASH,probeJarSha256=common.digest(probe),sourceHashes={p:common.digest(ROOT/p) for p in sources},limitations=['Paper 1.21.1 target-selector scheduler only; movement Goal composition and threat switching require separate acceptance.','One observer keeps chunks ticking; native constructors/direct casts do not cover the full event dispatcher.','Controller rebind and cleanup are Helstera extensions; persistence across server restart remains outside this fixture.'])
    failure=None;latest=server/'logs/latest.log';client=None
    with (server/'stdout.log').open('w') as output,(server/'client.log').open('w') as client_output:
        process=subprocess.Popen(['java','-Xms1G','-Xmx2G','-jar','paper.jar','nogui'],cwd=server,stdin=subprocess.PIPE,stdout=output,stderr=subprocess.STDOUT,text=True)
        try:
            deadline=time.monotonic()+600
            while process.poll() is None and time.monotonic()<deadline:
                log=latest.read_text(errors='replace') if latest.exists() else ''
                if client is None and 'Done (' in log:client=subprocess.Popen(['node',str(TOOLS/'ai-target-selector-client.js')],cwd=TOOLS,stdout=client_output,stderr=subprocess.STDOUT)
                if 'AI_TARGET_FAILED|' in log:raise RuntimeError('Probe failed')
                if 'AI_TARGET_SUMMARY|' in log:
                    try:client.wait(timeout=15)
                    except subprocess.TimeoutExpired:raise RuntimeError('Client did not finish')
                    if client.returncode!=0:raise RuntimeError('Client failed')
                    break
                if client is not None and client.poll() is not None:raise RuntimeError('Client exited early')
                time.sleep(.25)
            else:raise RuntimeError('Probe timed out or exited')
        except RuntimeError as e:failure=str(e)
        finally:
            if client is not None and client.poll() is None:
                client.terminate()
                try:client.wait(timeout=10)
                except subprocess.TimeoutExpired:client.kill();client.wait()
            if process.poll() is None:
                process.stdin.write('stop\n');process.stdin.flush()
                try:process.wait(timeout=40)
                except subprocess.TimeoutExpired:process.kill();process.wait()
    log=common.redact(latest.read_text(errors='replace') if latest.exists() else '');(server/'server.log').write_text(log)
    snapshots=sorted((probe_data/'observations').glob('*.yml'))
    if process.returncode!=0 or len(snapshots)!=len(cases.CASES)*3 or f'AI_TARGET_SUMMARY|observations={len(cases.CASES)*3}|completed=true' not in log:failure=failure or 'Incomplete observations'
    report.update(exitCode=process.returncode,clientExitCode=client.returncode if client else None,observationCount=len(snapshots),validationResult='passed' if failure is None else 'rejected: '+failure)
    for path,expected in report['sourceHashes'].items():
        if common.digest(ROOT/path)!=expected:raise RuntimeError('Source changed during fixture: '+path)
    for path,key in [(server/'paper.jar','paperJarSha256'),(plugins/'MythicMobs.jar','mythicMobsSha256'),(plugins/'helsteraMobs.jar','pluginSha256'),(probe,'probeJarSha256')]:
        if common.digest(path)!=report[key]:raise RuntimeError('Artifact changed during fixture')
    (server/'client.log').write_text(common.redact((server/'client.log').read_text(errors='replace')))
    files=[server/'server.log',server/'client.log',probe_data/'survey.yml',mobs/'ai-target-selector.yml',probe_data/'cases.yml',*snapshots,*sorted((probe_data/'imports').glob('*.yml'))]
    report['files']=[dict(path=str(p.relative_to(server)),sha256=common.digest(p)) for p in files]
    if failure is None:
        try:module('ai_target_audit','audit-ai-target-selector.py').audit(report,{str(p.relative_to(server)):p.read_bytes() for p in files})
        except (ValueError,KeyError,TypeError) as e:failure=str(e);report['validationResult']='rejected: '+failure
    with zipfile.ZipFile(archive_path,'x',zipfile.ZIP_DEFLATED) as archive:
        for path in files:archive.write(path,str(path.relative_to(server)))
    report.update(logArchive=archive_path.name,logArchiveSha256=common.digest(archive_path));report_path.write_text(json.dumps(report,indent=2)+'\n')
    print(report_path,report['validationResult'],flush=True)
    if failure:raise RuntimeError(failure)
if __name__=='__main__':main()
