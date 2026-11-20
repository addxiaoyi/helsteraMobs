"""Observe three save boundaries, then SIGKILL only the fresh disposable Paper process."""
import argparse
from datetime import datetime, timezone
import importlib.util
import json
from pathlib import Path
import re
import os
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

def capture(server,dest):
    data=server/'plugins/helsteraMobs'
    copied=[]
    for f in [data/'scoped-variables.yml',*sorted((data/'skill-trees').glob('*.yml'))]:
        if f.is_file():shutil.copy2(f,dest/f.name);copied.append(f.name)
    from io import BytesIO
    import gzip,nbtlib,struct,uuid,zlib
    regions={}
    def plain(value):
        if isinstance(value,dict):return {str(k):plain(v) for k,v in value.items()}
        if isinstance(value,(list,tuple)) or hasattr(value,'tolist'):return [plain(v) for v in value]
        if isinstance(value,str):return str(value)
        if isinstance(value,(int,float)):return value
        if hasattr(value,'item'):return value.item()
        return str(value)
    for world in sorted(server.glob('despawn-crash-*')):
        entities=[];files=[]
        for f in sorted((world/'entities').glob('*.mca')):
            raw_path=dest/'regions'/world.name/f.name;raw_path.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(f,raw_path)
            raw=f.read_bytes();files.append({'name':f.name,'sha256':digest(f),'size':len(raw)})
            for slot in range(1024):
                loc=int.from_bytes(raw[4*slot:4*slot+4],'big');offset=loc>>8
                if not offset:continue
                start=offset*4096;length=int.from_bytes(raw[start:start+4],'big');kind=raw[start+4];payload=raw[start+5:start+4+length]
                if kind==2:payload=zlib.decompress(payload)
                elif kind==1:payload=gzip.decompress(payload)
                elif kind!=3:raise ValueError('Unexpected region compression '+str(kind))
                nbt=nbtlib.File.parse(BytesIO(payload));chunk=plain(nbt)
                for entity in chunk.get('Entities',[]):
                    parts=entity.get('UUID');identity=str(uuid.UUID(bytes=b''.join((int(i)&0xffffffff).to_bytes(4,'big') for i in parts))) if parts else None
                    entities.append({'uuid':identity,'chunk':chunk.get('Position'),'entity':entity})
        regions[world.name]={'files':files,'entities':entities}
    (dest/'entities.json').write_text(json.dumps(regions,indent=2)+'\n')
    (dest/'inventory.json').write_text(json.dumps(copied,indent=2)+'\n')


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--revision',required=True)
    parser.add_argument('--cache-directory',required=True)
    parser.add_argument('--observe-only',action='store_true')
    args=parser.parse_args()
    import yaml
    if not 6<=int(yaml.__version__.split('.')[0])<7:raise RuntimeError('Install requirements-player-variables.txt')
    if not re.fullmatch(r'r[1-9][0-9]*',args.revision):raise ValueError('Invalid revision')
    if os.name!='posix':raise ValueError('SIGKILL evidence needs POSIX')
    import nbtlib
    validator=None if args.observe_only else module('crash_validator',TOOLS/'validate-despawn-crash.py')
    day=datetime.now(timezone.utc).strftime('%Y%m%d')
    fixture=f'srv-paper-despawn-crash-{day}-{args.revision}';server=ROOT/fixture
    if server.exists():raise ValueError('Refusing to reuse fixture')
    paper=ROOT/'srv-1.21.1/paper.jar';native=TOOLS/'.external-cache/MythicMobs-5.7.2-FTWvGuE5.jar'
    plugin=ROOT/'helstera-plugin/target/helsteraMobs-1.0.0-full.jar'
    if digest(paper)!=common.PAPER_HASH or digest(native)!=common.NATIVE_HASH:raise ValueError('Wrong pinned dependencies')
    subprocess.run([str(ROOT/'mvnw'),'-q','-f',str(TOOLS/'random-spawn-fixture/pom.xml'),'package'],check=True,cwd=ROOT)
    server.mkdir()
    for name in ('cache','libraries','versions'):shutil.copytree(Path(args.cache_directory)/name,server/name)
    shutil.copy2(paper,server/'paper.jar');shutil.copy2(TOOLS/'server.properties',server/'server.properties')
    (server/'eula.txt').write_text('eula=true\n')
    plugins=server/'plugins';plugins.mkdir()
    shutil.copy2(plugin,plugins/'helsteraMobs.jar');shutil.copy2(native,plugins/'MythicMobs.jar')
    probe_data=plugins/'HelsteraDespawnCrashProbe';probe_data.mkdir()
    probe_jar=plugins/'HelsteraDespawnCrashProbe.jar'
    with zipfile.ZipFile(TOOLS/'random-spawn-fixture/target/random-spawn-paper-fixture-1.0.0.jar') as source,zipfile.ZipFile(probe_jar,'w',zipfile.ZIP_DEFLATED) as dest:
        for item in source.infolist():
            if item.filename!='plugin.yml':dest.writestr(item,source.read(item.filename))
        dest.writestr('plugin.yml',"name: HelsteraDespawnCrashProbe\nversion: '1.0'\napi-version: '1.21'\nmain: dev.helstera.test.randomspawn.DespawnCrashProbe\ndepend: [helsteraMobs, MythicMobs]\ncommands:\n  despawncrashprobe: {}\n")
    mobs=plugins/'MythicMobs/Mobs';mobs.mkdir(parents=True)
    native_mobs={'HelsteraRadiusAuditMob':{'Type':'COW','Options':{'NoAI':True,'Despawn':'PERSISTENT'}}}
    for group in ('active','unloaded','resumed'):
        for mode in ('NORMAL','CHUNK','NPC','NEVER','PERSISTENT'):
            for path in ('direct','imported'):
                key=group+'_'+mode.lower()+'_'+path
                native_mobs[key]={'Type':'COW','Options':{'NoAI':True,'NoGravity':True,'Invincible':True,'Despawn':mode},
                    'Skills':[f'command{{c="say DCRASH_HOOK native {event} {key}"}} @self ~on{event.title()}' for event in ('despawn','death')]}
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
        TOOLS/'random-spawn-fixture/src/main/java/dev/helstera/test/randomspawn/DespawnCrashProbe.java',
        TOOLS/'random-spawn-fixture/src/main/java/dev/helstera/test/randomspawn/VariablePlaceholderAudit.java']
    if validator:sources.extend([TOOLS/'validate-despawn-crash.py',TOOLS/'validate-player-variables.py',TOOLS/'test-despawn-crash-evidence.py'])
    report={'schemaVersion':2,'fixtureDirectory':fixture,'evidenceMode':'observation' if args.observe_only else 'comparison-with-exception',
        'knownExceptions':[] if validator is None else validator.EXCEPTIONS,
        'generatedAtUtc':datetime.now(timezone.utc).isoformat(),'pluginSha256':digest(plugin),'paperJarSha256':common.PAPER_HASH,
        'mythicMobsSha256':common.NATIVE_HASH,'probeJarSha256':digest(probe_jar),
        'sourceHashes':{str(p.relative_to(ROOT)):digest(p) for p in sources},'observations':[],'boots':[],'files':[]}
    for stage in ('write','recover','final'):
        (probe_data/'config.yml').write_text('stage: '+stage+'\n')
        latest=server/'logs/latest.log'
        if latest.exists():latest.rename(server/f'before-{stage}.log')
        with (server/f'{stage}.stdout.log').open('w') as output:
            process=subprocess.Popen(['java','-Xms1G','-Xmx2G','-jar','paper.jar','nogui'],cwd=server,stdin=subprocess.PIPE,stdout=output,stderr=subprocess.STDOUT,text=True)
            started=datetime.now(timezone.utc).isoformat()
            def wait_for(marker):
                deadline=time.monotonic()+240
                while time.monotonic()<deadline and process.poll() is None:
                    text=latest.read_text(errors='replace') if latest.exists() else ''
                    if 'DCRASH_FAILED|' in text:raise RuntimeError('Crash probe failed; inspect retained log')
                    if marker in text:return
                    time.sleep(.25)
                raise RuntimeError('Probe timeout or exited')
            def command(value):process.stdin.write('despawncrashprobe '+value+'\n');process.stdin.flush()
            try:
                wait_for('DCRASH_READY|stage='+stage+'|')
                if stage!='write':
                    command('clear');wait_for('DCRASH_CLEARED|stage='+stage)
                command('flush');wait_for('DCRASH_CHECKPOINT|stage='+stage+'|')
                dest=server/'snapshots'/(stage+'-online');dest.mkdir(parents=True)
                capture(server,dest)
                if stage!='final':
                    process.kill();process.wait(timeout=15)
                    if process.returncode!=-9:raise RuntimeError('Expected only SIGKILL')
                else:
                    process.stdin.write('stop\n');process.stdin.flush();process.wait(timeout=40)
                    if process.returncode:raise RuntimeError('Unclean final shutdown')
                dest=server/'snapshots'/(stage+'-stopped');dest.mkdir(parents=True);capture(server,dest)
            finally:
                if process.poll() is None:
                    process.stdin.write('stop\n');process.stdin.flush()
                    try:process.wait(timeout=40)
                    except subprocess.TimeoutExpired:process.kill();process.wait()
                if latest.exists():(server/f'{stage}.server.log').write_text(redact(latest.read_text(errors='replace')))
        report['boots'].append({'stage':stage,'startedAtUtc':started,'stoppedAtUtc':datetime.now(timezone.utc).isoformat(),'exitCode':process.returncode,'termination':'graceful' if stage=='final' else 'SIGKILL'})
        text=(server/f'{stage}.server.log').read_text();report['observations'] += [l for l in text.splitlines() if 'DCRASH_ROW|' in l]
        print('Completed crash stage:',stage,flush=True)
    shutil.copy2(probe_data/'identities.yml',server/'identities.yml')
    for name,expected in report['sourceHashes'].items():
        if digest(ROOT/name)!=expected:raise RuntimeError('Source changed during fixture')
    for p,expected in [(plugins/'helsteraMobs.jar',report['pluginSha256']),(plugins/'MythicMobs.jar',report['mythicMobsSha256']),
                       (server/'paper.jar',report['paperJarSha256']),(probe_jar,report['probeJarSha256'])]:
        if digest(p)!=expected:raise RuntimeError('Artifact changed during fixture')
    for p in [server/(s+'.server.log') for s in ('write','recover','final')]+[server/'identities.yml']+sorted(p for p in (server/'snapshots').rglob('*') if p.is_file()):
        report['files'].append({'path':str(p.relative_to(server)),'sha256':digest(p)})
    local=server/'despawn-crash-report.json';local.write_text(json.dumps(report,indent=2)+'\n')
    error=None
    if validator:
        try:validator.main(server)
        except (ValueError,KeyError) as e:
            error=e;report['evidenceMode']='observation';report['validationResult']='rejected: '+str(e)
            local.write_text(json.dumps(report,indent=2)+'\n')
    name=f'paper-1.21.1-despawn-crash-{day}-{args.revision}'
    report_path=TOOLS/'reports'/(name+'.json');archive_path=TOOLS/'reports'/(name+'-logs.zip')
    if report_path.exists() or archive_path.exists():raise RuntimeError('Refusing to overwrite evidence')
    with zipfile.ZipFile(archive_path,'x',zipfile.ZIP_DEFLATED) as archive:
        for f in report['files']:archive.write(server/f['path'],f['path'])
    report['logArchive']=archive_path.name;report['logArchiveSha256']=digest(archive_path)
    report_path.write_text(json.dumps(report,indent=2)+'\n')
    if error:raise RuntimeError('Rejected evidence archived: '+str(error)) from error
    if validator:validator.main(report_path)
    print('Despawn crash evidence:',report_path)

if __name__=='__main__':main()
