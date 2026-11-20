"""Measure terminal callback scheduling on a disposable, pinned Paper/MM server."""
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

sys.dont_write_bytecode=True
ROOT=Path(__file__).resolve().parents[2]
TOOLS=ROOT/'tools/paper-integration'
def module(name,filename):
    spec=importlib.util.spec_from_file_location(name,TOOLS/filename)
    result=importlib.util.module_from_spec(spec);spec.loader.exec_module(result);return result
common=module('player_runner','run-player-variable-fixture.py')
digest,redact=common.digest,common.redact

def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--revision',required=True)
    parser.add_argument('--cache-directory',required=True)
    parser.add_argument('--observe-only',action='store_true')
    parser.add_argument('--expect-parity',action='store_true')
    args=parser.parse_args()
    import yaml
    if not re.fullmatch(r'r[1-9][0-9]*',args.revision):raise ValueError('Invalid revision')
    validator=None if args.observe_only else module('reload_validator','validate-terminal-callback.py')
    day=datetime.now(timezone.utc).strftime('%Y%m%d');fixture=f'srv-paper-terminal-callback-{day}-{args.revision}';server=ROOT/fixture
    if server.exists():raise ValueError('Refusing to reuse fixture')
    paper=ROOT/'srv-1.21.1/paper.jar';native=TOOLS/'.external-cache/MythicMobs-5.7.2-FTWvGuE5.jar';plugin=ROOT/'helstera-plugin/target/helsteraMobs-1.0.0-full.jar'
    if digest(paper)!=common.PAPER_HASH or digest(native)!=common.NATIVE_HASH:raise ValueError('Wrong pinned dependencies')
    subprocess.run([str(ROOT/'mvnw'),'-q','-f',str(TOOLS/'random-spawn-fixture/pom.xml'),'package'],check=True,cwd=ROOT)
    server.mkdir()
    for name in ('cache','libraries','versions'):shutil.copytree(Path(args.cache_directory)/name,server/name)
    shutil.copy2(paper,server/'paper.jar');shutil.copy2(TOOLS/'server.properties',server/'server.properties');(server/'eula.txt').write_text('eula=true\n')
    plugins=server/'plugins';plugins.mkdir();shutil.copy2(plugin,plugins/'helsteraMobs.jar');shutil.copy2(native,plugins/'MythicMobs.jar')
    probe_data=plugins/'HelsteraTerminalCallbackProbe';probe_data.mkdir();probe_jar=plugins/'HelsteraTerminalCallbackProbe.jar'
    with zipfile.ZipFile(TOOLS/'random-spawn-fixture/target/random-spawn-paper-fixture-1.0.0.jar') as source,zipfile.ZipFile(probe_jar,'w',zipfile.ZIP_DEFLATED) as dest:
        for item in source.infolist():
            if item.filename!='plugin.yml':dest.writestr(item,source.read(item.filename))
        dest.writestr('plugin.yml',"name: HelsteraTerminalCallbackProbe\nversion: '1.0'\napi-version: '1.21'\nmain: dev.helstera.test.randomspawn.TerminalCallbackProbe\ndepend: [helsteraMobs, MythicMobs]\ncommands:\n  terminalprobe: {}\n")
    contract=module('callback_cases','terminal-callback-cases.py')
    (probe_data/'config.yml').write_text(yaml.safe_dump({'cases':list(contract.CASES)}))
    mobs=plugins/'MythicMobs/Mobs';mobs.mkdir(parents=True)
    (mobs/'terminal.yml').write_text(yaml.safe_dump(contract.native_mobs(),sort_keys=False))
    native_skills=plugins/'MythicMobs/Skills';native_skills.mkdir()
    (native_skills/'terminal.yml').write_text(yaml.safe_dump(contract.native_skills(),sort_keys=False))
    native_config=plugins/'MythicMobs/config';native_config.mkdir();(native_config/'config-general.yml').write_text('Configuration:\n  Clock:\n    Saving: 300\n')
    data=plugins/'helsteraMobs';data.mkdir();config=(TOOLS/'config.yml').read_text()
    config=re.sub(r'(?m)^  (self-test(?:-[A-Za-z0-9-]+)?):\s*true\s*$',r'  \1: false',config)
    config=re.sub(r'(?m)^  trace-triggers:.*$','  trace-triggers: false',config);config=re.sub(r'(?m)^  mythicmobs: false\s*$','  mythicmobs: true',config)
    if re.search(r'(?m)^web:\s*\n[ \t]+enabled:\s*true',config):raise ValueError('Web interface must be disabled')
    (data/'config.yml').write_text(config+'\nvariables:\n  player-autosave-seconds: 300\n')
    for name in ('items.yml','loot.yml'):shutil.copy2(TOOLS/name,data/name)
    (data/'skills.yml').write_text('skills: {}\n');(data/'spawners.yml').write_text('spawners: {}\n');(data/'mobs').mkdir()
    sources=[Path(__file__).resolve(),TOOLS/'run-player-variable-fixture.py',TOOLS/'terminal-callback-cases.py',TOOLS/'random-spawn-fixture/src/main/java/dev/helstera/test/randomspawn/TerminalCallbackProbe.java']
    if validator:sources.extend(TOOLS/n for n in ('validate-terminal-callback.py','validate-player-variables.py','test-terminal-callback-evidence.py'))
    report={'schemaVersion':3,'fixtureDirectory':fixture,'evidenceMode':'observation' if args.observe_only else 'comparison-with-exception' if args.expect_parity else 'diagnostic-known-gap','knownExceptions':[] if validator is None else validator.PARITY_EXCEPTIONS if args.expect_parity else validator.EXCEPTIONS,
        'parityStatus':'unassessed' if args.observe_only else 'scoped-match' if args.expect_parity else 'incomplete',
        'generatedAtUtc':datetime.now(timezone.utc).isoformat(),'pluginSha256':digest(plugin),'paperJarSha256':common.PAPER_HASH,'mythicMobsSha256':common.NATIVE_HASH,'probeJarSha256':digest(probe_jar),
        'sourceHashes':{str(p.relative_to(ROOT)):digest(p) for p in sources},'observations':[],'files':[]}
    latest=server/'logs/latest.log';error=None;started=datetime.now(timezone.utc).isoformat()
    with (server/'stdout.log').open('w') as output:
        process=subprocess.Popen(['java','-Xms1G','-Xmx2G','-jar','paper.jar','nogui'],cwd=server,stdin=subprocess.PIPE,stdout=output,stderr=subprocess.STDOUT,text=True)
        try:
            deadline=time.monotonic()+1200
            while time.monotonic()<deadline and process.poll() is None:
                text=latest.read_text(errors='replace') if latest.exists() else ''
                if 'TCALL_FAILED|' in text:raise RuntimeError('Terminal callback probe failed')
                if 'TCALL_SUMMARY|' in text:break
                time.sleep(.25)
            else:raise RuntimeError('Terminal callback probe timed out or exited')
        except RuntimeError as exc:error=exc
        finally:
            if process.poll() is None:
                process.stdin.write('stop\n');process.stdin.flush()
                try:process.wait(timeout=40)
                except subprocess.TimeoutExpired:process.kill();process.wait()
            if latest.exists():(server/'server.log').write_text(redact(latest.read_text(errors='replace')))
    if process.returncode:error=RuntimeError('Unclean shutdown')
    report['boot']={'startedAtUtc':started,'stoppedAtUtc':datetime.now(timezone.utc).isoformat(),'exitCode':process.returncode}
    snapshot=server/'snapshots';snapshot.mkdir()
    for f in [probe_data/'final-scoped.yml',data/'scoped-variables.yml',*sorted((data/'skill-trees').glob('*.yml'))]:
        if f.exists():shutil.copy2(f,snapshot/f.name)
    report['observations']=[l for l in (server/'server.log').read_text().splitlines() if 'TCALL_ROW|' in l]
    report['gapCases']=[]
    for line in report['observations']:
        row=dict(pair.split('=',1) for pair in line.split('TCALL_ROW|',1)[1].split('|'))
        if row['backend']=='runtime' and row['phase']=='settled' and row['children']=='0' and not row['key'].startswith('control_'):
            report['gapCases'].append(row['key'])
    report['gapCases'].sort()
    shutil.copytree(probe_data/'imports',server/'imports')
    shutil.copy2(mobs/'terminal.yml',server/'source-mobs.yml')
    shutil.copy2(native_skills/'terminal.yml',server/'source-skills.yml')
    for name,expected in report['sourceHashes'].items():
        if digest(ROOT/name)!=expected:raise RuntimeError('Source changed during fixture')
    for p,key in [(server/'paper.jar','paperJarSha256'),(plugins/'MythicMobs.jar','mythicMobsSha256'),(plugins/'helsteraMobs.jar','pluginSha256'),(probe_jar,'probeJarSha256')]:
        if digest(p)!=report[key]:raise RuntimeError('Artifact changed during fixture')
    for f in [server/'server.log',server/'source-mobs.yml',server/'source-skills.yml',*sorted(snapshot.iterdir()),*sorted((server/'imports').iterdir())]:report['files'].append({'path':str(f.relative_to(server)),'sha256':digest(f)})
    local=server/'terminal-callback-report.json';local.write_text(json.dumps(report,indent=2)+'\n')
    if validator and error is None:
        try:validator.main(server)
        except (ValueError,KeyError) as exc:error=exc
    if error:report['evidenceMode']='observation';report['validationResult']='rejected: '+str(error);local.write_text(json.dumps(report,indent=2)+'\n')
    name=f'paper-1.21.1-terminal-callback-{day}-{args.revision}';report_path=TOOLS/'reports'/(name+'.json');archive_path=TOOLS/'reports'/(name+'-logs.zip')
    if report_path.exists() or archive_path.exists():raise RuntimeError('Refusing to overwrite evidence')
    with zipfile.ZipFile(archive_path,'x',zipfile.ZIP_DEFLATED) as archive:
        for f in report['files']:archive.write(server/f['path'],f['path'])
    report['logArchive']=archive_path.name;report['logArchiveSha256']=digest(archive_path);report_path.write_text(json.dumps(report,indent=2)+'\n')
    print('Terminal callback evidence:',report_path,flush=True)
    if error:raise RuntimeError('Rejected evidence archived: '+str(error)) from error
    if validator:validator.main(report_path)

if __name__=='__main__':main()
