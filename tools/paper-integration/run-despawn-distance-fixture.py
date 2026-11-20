"""Observe native distance policy with a real player and chunks held by plugin tickets."""
import argparse
from datetime import datetime,timezone
import importlib.util,json,re,shutil,subprocess,sys,time,zipfile
from pathlib import Path
sys.dont_write_bytecode=True
ROOT=Path(__file__).resolve().parents[2];TOOLS=ROOT/'tools/paper-integration'
def module(name,path):
    spec=importlib.util.spec_from_file_location(name,path);m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m);return m
common=module('common',TOOLS/'run-player-variable-fixture.py');digest,redact=common.digest,common.redact

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--revision',required=True);parser.add_argument('--cache-directory',required=True);parser.add_argument('--observe-only',action='store_true');args=parser.parse_args()
    import yaml
    if not re.fullmatch(r'r[1-9][0-9]*',args.revision):raise ValueError('Invalid revision')
    day=datetime.now(timezone.utc).strftime('%Y%m%d');fixture=f'srv-paper-despawn-distance-{day}-{args.revision}';server=ROOT/fixture
    if server.exists():raise ValueError('Refusing to reuse fixture')
    paper=ROOT/'srv-1.21.1/paper.jar';native=TOOLS/'.external-cache/MythicMobs-5.7.2-FTWvGuE5.jar';plugin=ROOT/'helstera-plugin/target/helsteraMobs-1.0.0-full.jar'
    if digest(paper)!=common.PAPER_HASH or digest(native)!=common.NATIVE_HASH:raise ValueError('Wrong dependencies')
    validator=None if args.observe_only else module('validator',TOOLS/'validate-despawn-distance.py')
    subprocess.run(['mvn','-q','-f',str(TOOLS/'random-spawn-fixture/pom.xml'),'package'],check=True,cwd=ROOT)
    server.mkdir()
    for name in ('cache','libraries','versions'):shutil.copytree(Path(args.cache_directory)/name,server/name)
    shutil.copy2(paper,server/'paper.jar');shutil.copy2(TOOLS/'server.properties',server/'server.properties');(server/'eula.txt').write_text('eula=true\n')
    plugins=server/'plugins';plugins.mkdir();shutil.copy2(plugin,plugins/'helsteraMobs.jar');shutil.copy2(native,plugins/'MythicMobs.jar')
    probe_jar=plugins/'HelsteraDespawnDistanceProbe.jar'
    with zipfile.ZipFile(TOOLS/'random-spawn-fixture/target/random-spawn-paper-fixture-1.0.0.jar') as src,zipfile.ZipFile(probe_jar,'w',zipfile.ZIP_DEFLATED) as dest:
        for entry in src.infolist():
            if entry.filename!='plugin.yml':dest.writestr(entry,src.read(entry.filename))
        dest.writestr('plugin.yml',"name: HelsteraDespawnDistanceProbe\nversion: '1.0'\napi-version: '1.21'\nmain: dev.helstera.test.randomspawn.DespawnDistanceProbe\ndepend: [helsteraMobs, MythicMobs]\ncommands:\n  distanceprobe: {}\n")
    mobs=plugins/'MythicMobs/Mobs';mobs.mkdir(parents=True);configs={}
    for cohort in ('player','empty','spectator'):
        for typ in ('COW','ZOMBIE','VILLAGER','SLIME'):
            for mode in ('DEFAULT','NORMAL','CHUNK','NPC','NEVER','PERSISTENT','SPAWNING_DISTANCE'):
                for path in ('direct','imported'):
                    key='_'.join((cohort,typ.lower(),mode.lower(),path));options={'Collidable':False,'NoAI':True,'NoGravity':True,'Invincible':True,'PreventSlimeSplit':True}
                    if mode not in ('DEFAULT','SPAWNING_DISTANCE'):options['Despawn']=mode
                    if mode=='SPAWNING_DISTANCE':options['SpawningDistance']=8
                    configs[key]={'Type':typ,'Options':options,'Skills':[f'command{{c="distanceprobe hook native {e} {key}"}} @self ~on{e.title()}' for e in ('despawn','death')]}
    (mobs/'distance.yml').write_text(yaml.safe_dump(configs,sort_keys=False))
    data=plugins/'helsteraMobs';data.mkdir();config=(TOOLS/'config.yml').read_text()
    config=re.sub(r'(?m)^  (self-test(?:-[A-Za-z0-9-]+)?):\s*true\s*$',r'  \1: false',config)
    config=re.sub(r'(?m)^  trace-triggers:.*$','  trace-triggers: false',config);config=re.sub(r'(?m)^  mythicmobs: false\s*$','  mythicmobs: true',config)
    if re.search(r'(?m)^web:\s*\n[ \t]+enabled:\s*true',config):raise ValueError('Web must be disabled')
    (data/'config.yml').write_text(config);(data/'skills.yml').write_text('skills: {}\n');(data/'spawners.yml').write_text('spawners: {}\n');(data/'mobs').mkdir()
    for name in ('loot.yml','items.yml'):shutil.copy2(TOOLS/name,data/name)
    sources=[Path(__file__).resolve(),TOOLS/'run-player-variable-fixture.py',TOOLS/'despawn-distance-client.js',TOOLS/'random-spawn-fixture/src/main/java/dev/helstera/test/randomspawn/DespawnDistanceProbe.java']
    if validator:sources.extend([TOOLS/'validate-despawn-distance.py',TOOLS/'test-despawn-distance-evidence.py',TOOLS/'validate-player-variables.py'])
    report={'schemaVersion':2,'fixtureDirectory':fixture,'evidenceMode':'observation' if args.observe_only else 'comparison-with-extension','generatedAtUtc':datetime.now(timezone.utc).isoformat(),'paperJarSha256':digest(paper),'mythicMobsSha256':digest(native),'pluginSha256':digest(plugin),'probeJarSha256':digest(probe_jar),'sourceHashes':{str(p.relative_to(ROOT)):digest(p) for p in sources},'files':[],'observations':[],'extensions':['direct-mob.spawning-distance'],'clientKnownIssues':['minecraft-protocol-1.21.1-declare-recipes-partial-read']}
    latest=server/'logs/latest.log';client=None
    with (server/'stdout.log').open('w') as output,(server/'client.log').open('w') as client_output:
        process=subprocess.Popen(['java','-Xms1G','-Xmx2G','-jar','paper.jar','nogui'],cwd=server,stdin=subprocess.PIPE,stdout=output,stderr=subprocess.STDOUT,text=True)
        try:
            deadline=time.monotonic()+180
            while time.monotonic()<deadline and process.poll() is None:
                text=latest.read_text(errors='replace') if latest.exists() else ''
                if 'DDIST_FAILED|' in text:raise RuntimeError('Distance probe failed; inspect log')
                if 'Done (' in text and client is None:client=subprocess.Popen(['node',str(TOOLS/'despawn-distance-client.js')],cwd=TOOLS,stdout=client_output,stderr=subprocess.STDOUT)
                if 'DDIST_SUMMARY|' in text:break
                if client and client.poll() is not None:raise RuntimeError('Distance client exited early')
                time.sleep(.25)
            else:raise RuntimeError('Distance fixture timed out')
        finally:
            if process.poll() is None:
                process.stdin.write('stop\n');process.stdin.flush()
                try:process.wait(timeout=30)
                except subprocess.TimeoutExpired:process.kill();process.wait()
            if client:
                try:client.wait(timeout=10)
                except subprocess.TimeoutExpired:client.terminate();client.wait(timeout=10)
            if latest.exists():(server/'server.log').write_text(redact(latest.read_text(errors='replace')))
    if process.returncode or client is None or client.returncode:raise RuntimeError('Unclean processes')
    p=server/'client.log';p.write_text(redact(p.read_text(errors='replace')))
    for p in (server/'server.log',server/'client.log'):
        report['files'].append({'path':p.name,'sha256':digest(p)})
    report['observations']=[l for l in (server/'server.log').read_text().splitlines() if 'DDIST_ROW|' in l]
    for name,expected in report['sourceHashes'].items():
        if digest(ROOT/name)!=expected:raise RuntimeError('Source changed during fixture')
    local=server/'despawn-distance-report.json';local.write_text(json.dumps(report,indent=2)+'\n')
    error=None
    if validator:
        try:validator.main(server)
        except (ValueError,KeyError) as e:error=e;report.update(evidenceMode='observation',validationResult='rejected: '+str(e));local.write_text(json.dumps(report,indent=2)+'\n')
    base=f'paper-1.21.1-despawn-distance-{day}-{args.revision}';rp=TOOLS/'reports'/(base+'.json');ap=TOOLS/'reports'/(base+'-logs.zip')
    if rp.exists() or ap.exists():raise RuntimeError('Refusing to overwrite evidence')
    with zipfile.ZipFile(ap,'x',zipfile.ZIP_DEFLATED) as z:
        for f in report['files']:z.write(server/f['path'],f['path'])
    report.update(logArchive=ap.name,logArchiveSha256=digest(ap));rp.write_text(json.dumps(report,indent=2)+'\n')
    if error:raise RuntimeError('Rejected and archived: '+str(error)) from error
    if validator:validator.main(rp)
    print('Distance evidence archived:',rp,flush=True)
if __name__=='__main__':main()
