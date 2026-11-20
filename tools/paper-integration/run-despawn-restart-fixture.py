"""Compare actual managed/native mobs through three process boots and lazy chunk loads."""
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
def module(name,path):
    spec=importlib.util.spec_from_file_location(name,path)
    result=importlib.util.module_from_spec(spec);spec.loader.exec_module(result);return result
common=module('player_runner',TOOLS/'run-player-variable-fixture.py')
digest,redact=common.digest,common.redact

def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--revision',required=True)
    parser.add_argument('--cache-directory',required=True)
    parser.add_argument('--observe-only',action='store_true')
    args=parser.parse_args()
    import yaml
    if not 6<=int(yaml.__version__.split('.')[0])<7:raise RuntimeError('Install requirements-player-variables.txt')
    if not re.fullmatch(r'r[1-9][0-9]*',args.revision):raise ValueError('Invalid revision')
    validator=None if args.observe_only else module('restart_validator',TOOLS/'validate-despawn-restart.py')
    day=datetime.now(timezone.utc).strftime('%Y%m%d')
    fixture=f'srv-paper-despawn-restart-{day}-{args.revision}';server=ROOT/fixture
    if server.exists():raise ValueError('Refusing to reuse fixture')
    paper=ROOT/'srv-1.21.1/paper.jar';native=TOOLS/'.external-cache/MythicMobs-5.7.2-FTWvGuE5.jar'
    plugin=ROOT/'helstera-plugin/target/helsteraMobs-1.0.0-full.jar'
    if digest(paper)!=common.PAPER_HASH or digest(native)!=common.NATIVE_HASH:raise ValueError('Wrong pinned dependencies')
    subprocess.run(['mvn','-q','-f',str(TOOLS/'random-spawn-fixture/pom.xml'),'package'],check=True,cwd=ROOT)
    server.mkdir()
    for name in ('cache','libraries','versions'):shutil.copytree(Path(args.cache_directory)/name,server/name)
    shutil.copy2(paper,server/'paper.jar');shutil.copy2(TOOLS/'server.properties',server/'server.properties')
    (server/'eula.txt').write_text('eula=true\n')
    plugins=server/'plugins';plugins.mkdir()
    shutil.copy2(plugin,plugins/'helsteraMobs.jar');shutil.copy2(native,plugins/'MythicMobs.jar')
    probe_data=plugins/'HelsteraDespawnRestartProbe';probe_data.mkdir()
    probe_jar=plugins/'HelsteraDespawnRestartProbe.jar'
    with zipfile.ZipFile(TOOLS/'random-spawn-fixture/target/random-spawn-paper-fixture-1.0.0.jar') as source,zipfile.ZipFile(probe_jar,'w',zipfile.ZIP_DEFLATED) as dest:
        for item in source.infolist():
            if item.filename!='plugin.yml':dest.writestr(item,source.read(item.filename))
        dest.writestr('plugin.yml',"name: HelsteraDespawnRestartProbe\nversion: '1.0'\napi-version: '1.21'\nmain: dev.helstera.test.randomspawn.DespawnRestartProbe\ndepend: [helsteraMobs, MythicMobs]\n")
    mobs=plugins/'MythicMobs/Mobs';mobs.mkdir(parents=True)
    native_mobs={'HelsteraRadiusAuditMob':{'Type':'COW','Options':{'NoAI':True,'Despawn':'PERSISTENT'}}}
    for mode in ('NORMAL','CHUNK','NPC','NEVER','PERSISTENT'):
        for path in ('direct','imported'):
            key=mode.lower()+'_'+path
            native_mobs[key]={'Type':'COW','Options':{'NoAI':True,'NoGravity':True,'Invincible':True,'Despawn':mode},
                'Skills':[f'command{{c="say DRESTART_HOOK native {event} {key}"}} @self ~on{event.title()}' for event in ('despawn','death')]}
    (mobs/'restart.yml').write_text(yaml.safe_dump(native_mobs,sort_keys=False))
    config_dir=plugins/'MythicMobs/config';config_dir.mkdir()
    (config_dir/'config-general.yml').write_text('Configuration:\n  Clock:\n    Saving: 300\n')
    data=plugins/'helsteraMobs';data.mkdir()
    config=(TOOLS/'config.yml').read_text()
    config=re.sub(r'(?m)^  (self-test(?:-[A-Za-z0-9-]+)?):\s*true\s*$',r'  \1: false',config)
    config=re.sub(r'(?m)^  trace-triggers:.*$','  trace-triggers: false',config)
    config=re.sub(r'(?m)^  mythicmobs: false\s*$','  mythicmobs: true',config)
    if re.search(r'(?m)^web:\s*\n[ \t]+enabled:\s*true',config):raise ValueError('Web must remain disabled')
    (data/'config.yml').write_text(config)
    for name in ('items.yml','loot.yml'):shutil.copy2(TOOLS/name,data/name)
    (data/'skills.yml').write_text('skills: {}\n');(data/'spawners.yml').write_text('spawners: {}\n');(data/'mobs').mkdir()
    sources=[Path(__file__).resolve(),TOOLS/'run-player-variable-fixture.py',
        TOOLS/'random-spawn-fixture/src/main/java/dev/helstera/test/randomspawn/DespawnRestartProbe.java',
        TOOLS/'random-spawn-fixture/src/main/java/dev/helstera/test/randomspawn/VariablePlaceholderAudit.java']
    if validator:sources.extend([TOOLS/'validate-despawn-restart.py',TOOLS/'validate-player-variables.py',TOOLS/'test-despawn-restart-evidence.py'])
    report={'schemaVersion':1,'fixtureDirectory':fixture,'evidenceMode':'observation' if args.observe_only else 'comparison-with-exception',
        'knownExceptions':['MM-5.7.2-shutdown-command-scheduling'],
        'generatedAtUtc':datetime.now(timezone.utc).isoformat(),'pluginSha256':digest(plugin),'paperJarSha256':common.PAPER_HASH,
        'mythicMobsSha256':common.NATIVE_HASH,'probeJarSha256':digest(probe_jar),
        'sourceHashes':{str(p.relative_to(ROOT)):digest(p) for p in sources},'observations':[],'boots':[],'files':[]}
    for stage in ('write','verify','final'):
        (probe_data/'config.yml').write_text('stage: '+stage+'\n')
        latest=server/'logs/latest.log'
        if latest.exists():latest.rename(server/f'before-{stage}.log')
        with (server/f'{stage}.stdout.log').open('w') as output:
            process=subprocess.Popen(['java','-Xms1G','-Xmx2G','-jar','paper.jar','nogui'],cwd=server,stdin=subprocess.PIPE,stdout=output,stderr=subprocess.STDOUT,text=True)
            started=datetime.now(timezone.utc).isoformat()
            try:
                deadline=time.monotonic()+180
                while time.monotonic()<deadline and process.poll() is None:
                    text=latest.read_text(errors='replace') if latest.exists() else ''
                    if 'DRESTART_FAILED|' in text:raise RuntimeError('Restart probe failed; inspect retained log')
                    if f'DRESTART_SUMMARY|stage={stage}|' in text:break
                    time.sleep(.25)
                else:raise RuntimeError('Restart probe timed out')
            finally:
                if process.poll() is None:
                    process.stdin.write('stop\n');process.stdin.flush()
                    try:process.wait(timeout=30)
                    except subprocess.TimeoutExpired:process.kill();process.wait()
                if latest.exists():(server/f'{stage}.server.log').write_text(redact(latest.read_text(errors='replace')))
            if process.returncode:raise RuntimeError('Paper shutdown failed')
        report['boots'].append({'stage':stage,'startedAtUtc':started,'stoppedAtUtc':datetime.now(timezone.utc).isoformat(),'exitCode':process.returncode})
        text=(server/f'{stage}.server.log').read_text();report['observations'] += [l for l in text.splitlines() if 'DRESTART_ROW|' in l]
        dest=server/'snapshots'/stage;dest.mkdir(parents=True)
        shutil.copy2(data/'scoped-variables.yml',dest/'scoped.yml')
        if (data/'skill-trees').exists():
            for f in (data/'skill-trees').glob('*.yml'):shutil.copy2(f,dest/f.name)
        print('Completed restart stage:',stage,flush=True)
    shutil.copy2(probe_data/'identities.yml',server/'identities.yml')
    for name,expected in report['sourceHashes'].items():
        if digest(ROOT/name)!=expected:raise RuntimeError('Source changed during fixture')
    for p,expected in [(plugins/'helsteraMobs.jar',report['pluginSha256']),(plugins/'MythicMobs.jar',report['mythicMobsSha256']),
                       (server/'paper.jar',report['paperJarSha256']),(probe_jar,report['probeJarSha256'])]:
        if digest(p)!=expected:raise RuntimeError('Artifact changed during fixture')
    for p in [server/(s+'.server.log') for s in ('write','verify','final')]+[server/'identities.yml']+sorted((server/'snapshots').rglob('*.yml')):
        report['files'].append({'path':str(p.relative_to(server)),'sha256':digest(p)})
    local=server/'despawn-restart-report.json';local.write_text(json.dumps(report,indent=2)+'\n')
    error=None
    if validator:
        try:validator.main(server)
        except (ValueError,KeyError) as e:
            error=e;report['evidenceMode']='observation';report['validationResult']='rejected: '+str(e)
            local.write_text(json.dumps(report,indent=2)+'\n')
    name=f'paper-1.21.1-despawn-restart-{day}-{args.revision}'
    report_path=TOOLS/'reports'/(name+'.json');archive_path=TOOLS/'reports'/(name+'-logs.zip')
    if report_path.exists() or archive_path.exists():raise RuntimeError('Refusing to overwrite evidence')
    with zipfile.ZipFile(archive_path,'x',zipfile.ZIP_DEFLATED) as archive:
        for f in report['files']:archive.write(server/f['path'],f['path'])
    report['logArchive']=archive_path.name;report['logArchiveSha256']=digest(archive_path)
    report_path.write_text(json.dumps(report,indent=2)+'\n')
    if error:raise RuntimeError('Rejected evidence archived: '+str(error)) from error
    if validator:validator.main(report_path)
    print('Despawn restart evidence:',report_path)

if __name__=='__main__':main()
