"""Execute native/imported mob cleanup commands and restart the actual Paper process."""
import argparse
from datetime import datetime,timezone
import importlib.util,json,re,shutil,subprocess,sys,time,zipfile
from pathlib import Path
sys.dont_write_bytecode=True
ROOT=Path(__file__).resolve().parents[2];TOOLS=ROOT/'tools/paper-integration'
def module(name,path):
 s=importlib.util.spec_from_file_location(name,path);m=importlib.util.module_from_spec(s);s.loader.exec_module(m);return m
common=module('common',TOOLS/'run-player-variable-fixture.py');contract=module('contract',TOOLS/'reentrant-command-cases.py');digest,redact=common.digest,common.redact

def main():
 parser=argparse.ArgumentParser();parser.add_argument('--revision',required=True);parser.add_argument('--cache-directory',required=True);parser.add_argument('--observe-only',action='store_true');args=parser.parse_args()
 import yaml
 if not re.fullmatch(r'r[1-9][0-9]*',args.revision):raise ValueError('Invalid revision')
 validator=None if args.observe_only else module('validator',TOOLS/'validate-reentrant-command.py')
 day=datetime.now(timezone.utc).strftime('%Y%m%d');fixture=f'srv-paper-reentrant-command-{day}-{args.revision}';server=ROOT/fixture
 if server.exists():raise ValueError('Refusing to reuse fixture')
 paper=ROOT/'srv-1.21.1/paper.jar';native=TOOLS/'.external-cache/MythicMobs-5.7.2-FTWvGuE5.jar';plugin=ROOT/'helstera-plugin/target/helsteraMobs-1.0.0-full.jar'
 if digest(paper)!=common.PAPER_HASH or digest(native)!=common.NATIVE_HASH:raise ValueError('Wrong dependencies')
 subprocess.run([str(ROOT/'mvnw'),'-q','-f',str(TOOLS/'random-spawn-fixture/pom.xml'),'package'],check=True,cwd=ROOT)
 server.mkdir()
 for name in ('cache','libraries','versions'):shutil.copytree(ROOT/args.cache_directory/name,server/name)
 shutil.copy2(paper,server/'paper.jar');shutil.copy2(TOOLS/'server.properties',server/'server.properties');(server/'eula.txt').write_text('eula=true\n')
 plugins=server/'plugins';plugins.mkdir();shutil.copy2(plugin,plugins/'helsteraMobs.jar');shutil.copy2(native,plugins/'MythicMobs.jar')
 probe_jar=plugins/'HelsteraReentrantCommandProbe.jar'
 with zipfile.ZipFile(TOOLS/'random-spawn-fixture/target/random-spawn-paper-fixture-1.0.0.jar') as src,zipfile.ZipFile(probe_jar,'w',zipfile.ZIP_DEFLATED) as dest:
  for entry in src.infolist():
   if entry.filename!='plugin.yml':dest.writestr(entry,src.read(entry.filename))
  dest.writestr('plugin.yml',"name: HelsteraReentrantCommandProbe\nversion: '1.0'\napi-version: '1.21'\nmain: dev.helstera.test.randomspawn.ReentrantCommandProbe\ndepend: [helsteraMobs, MythicMobs]\ncommands:\n  reentrantcommandprobe: {}\n")
 probe_data=plugins/'HelsteraReentrantCommandProbe';(probe_data/'Mobs').mkdir(parents=True);native_data=plugins/'MythicMobs';(native_data/'Mobs').mkdir(parents=True)
 mobs={}
 for key,(mode,path) in contract.pairs().items():
  mobs[key]={'Type':'COW','Options':{'Despawn':mode,'NoAI':True,'NoGravity':True,'Invincible':True,'Collidable':False},
   'Skills':[f'command{{c="reentrantcommandprobe hook native {event} {key} <caster.uuid> <caster.var.Marker>"}} @self ~on{event.title()}' for event in ('despawn','death')]}
 for path in (probe_data/'Mobs/cases.yml',native_data/'Mobs/cases.yml'):path.write_text(yaml.safe_dump(mobs,sort_keys=False))
 data=plugins/'helsteraMobs';data.mkdir();config=(TOOLS/'config.yml').read_text();config=re.sub(r'(?m)^  (self-test(?:-[A-Za-z0-9-]+)?):\s*true\s*$',r'  \1: false',config)
 config=re.sub(r'(?m)^  trace-triggers:.*$','  trace-triggers: false',config);config=re.sub(r'(?m)^  mythicmobs: false\s*$','  mythicmobs: true',config)
 if re.search(r'(?m)^web:\s*\n[ \t]+enabled:\s*true',config):raise ValueError('Web must stay disabled')
 (data/'config.yml').write_text(config)
 for name in ('items.yml','loot.yml'):shutil.copy2(TOOLS/name,data/name)
 (data/'skills.yml').write_text('skills: {}\n');(data/'spawners.yml').write_text('spawners: {}\n');(data/'mobs').mkdir()
 sources=[Path(__file__).resolve(),TOOLS/'run-player-variable-fixture.py',TOOLS/'reentrant-command-cases.py',TOOLS/'mob-command-client.js',TOOLS/'random-spawn-fixture/src/main/java/dev/helstera/test/randomspawn/ReentrantCommandProbe.java']
 if validator:sources.extend([TOOLS/'validate-reentrant-command.py',TOOLS/'test-reentrant-command-evidence.py',TOOLS/'validate-player-variables.py'])
 report={'schemaVersion':1,'fixtureDirectory':fixture,'evidenceMode':'observation' if args.observe_only else 'comparison-with-exception',
  'knownExceptions':validator.EXCEPTIONS if validator else [],
  'clientKnownIssues':['minecraft-protocol-1.21.1-declare-recipes-partial-read'],'generatedAtUtc':datetime.now(timezone.utc).isoformat(),
  'pluginSha256':digest(plugin),'paperJarSha256':common.PAPER_HASH,'mythicMobsSha256':common.NATIVE_HASH,'probeJarSha256':digest(probe_jar),
  'sourceHashes':{str(p.relative_to(ROOT)):digest(p) for p in sources},'boots':[],'observations':[],'files':[]}
 for stage in ('write','verify','final'):
  (probe_data/'config.yml').write_text(yaml.safe_dump({'stage':stage,'cases':contract.CASES},sort_keys=False));latest=server/'logs/latest.log'
  if latest.exists():latest.rename(server/f'before-{stage}.log')
  client=None
  with (server/f'{stage}.stdout.log').open('w') as out,(server/f'{stage}.client.log').open('w') as client_out:
   process=subprocess.Popen(['java','-Xms1G','-Xmx2G','-jar','paper.jar','nogui'],cwd=server,stdin=subprocess.PIPE,stdout=out,stderr=subprocess.STDOUT,text=True);started=datetime.now(timezone.utc).isoformat()
   try:
    deadline=time.monotonic()+900
    while time.monotonic()<deadline and process.poll() is None:
     text=latest.read_text(errors='replace') if latest.exists() else ''
     if 'RCMD_FAILED|' in text:raise RuntimeError('Command probe failed')
     if stage=='write' and 'Done (' in text and client is None:client=subprocess.Popen(['node',str(TOOLS/'mob-command-client.js'),'900000'],cwd=TOOLS,stdout=client_out,stderr=subprocess.STDOUT)
     if f'RCMD_SUMMARY|stage={stage}|' in text:break
     if client and client.poll() is not None:raise RuntimeError('Client exited early')
     time.sleep(.25)
    else:raise RuntimeError('Command probe timeout')
   finally:
    if process.poll() is None:
     process.stdin.write('stop\n');process.stdin.flush()
     try:process.wait(timeout=40)
     except subprocess.TimeoutExpired:process.kill();process.wait()
    if client:
     try:client.wait(timeout=10)
     except subprocess.TimeoutExpired:client.terminate();client.wait(timeout=10)
    if latest.exists():(server/f'{stage}.server.log').write_text(redact(latest.read_text(errors='replace')))
   if process.returncode or client and client.returncode:raise RuntimeError('Unclean process')
  path=server/f'{stage}.client.log';path.write_text(redact(path.read_text(errors='replace')))
  report['boots'].append({'stage':stage,'startedAtUtc':started,'stoppedAtUtc':datetime.now(timezone.utc).isoformat(),'exitCode':process.returncode})
  report['observations'] += [l for l in (server/f'{stage}.server.log').read_text().splitlines() if 'RCMD_ROW|' in l]
  print('Completed command boot:',stage,flush=True)
 for name in ('identities.yml','scoped-write.yml','scoped-verify.yml','scoped-final.yml'):shutil.copy2(probe_data/name,server/name)
 shutil.copy2(data/'scoped-variables.yml',server/'scoped-shutdown.yml')
 shutil.copy2(probe_data/'Mobs/cases.yml',server/'source.yml')
 shutil.copytree(probe_data/'imports',server/'imports')
 (server/'remaining-tree-files.json').write_text(json.dumps(sorted(p.name for p in (data/'skill-trees').glob('*.yml'))))
 for name,h in report['sourceHashes'].items():
  if digest(ROOT/name)!=h:raise ValueError('Source changed during fixture')
 for path,h in [(plugins/'helsteraMobs.jar',report['pluginSha256']),(plugins/'MythicMobs.jar',report['mythicMobsSha256']),(server/'paper.jar',report['paperJarSha256']),(probe_jar,report['probeJarSha256'])]:
  if digest(path)!=h:raise ValueError('Artifact changed')
 names=['source.yml','identities.yml','scoped-write.yml','scoped-verify.yml','scoped-final.yml','scoped-shutdown.yml','remaining-tree-files.json']
 names += [stage+kind for stage in ('write','verify','final') for kind in ('.server.log','.client.log')]
 names += [str(p.relative_to(server)) for folder in ('imports',) for p in sorted((server/folder).glob('*.yml'))]
 for name in names:report['files'].append({'path':name,'sha256':digest(server/name)})
 local=server/'reentrant-command-report.json';local.write_text(json.dumps(report,indent=2)+'\n');error=None
 if validator:
  try:validator.main(server)
  except (ValueError,KeyError) as e:error=e;report.update(evidenceMode='observation',validationResult='rejected: '+str(e))
 name=f'paper-1.21.1-reentrant-command-{day}-{args.revision}';rp=TOOLS/'reports'/(name+'.json');ap=TOOLS/'reports'/(name+'-logs.zip')
 if rp.exists() or ap.exists():raise ValueError('Evidence exists')
 with zipfile.ZipFile(ap,'x',zipfile.ZIP_DEFLATED) as z:
  for f in report['files']:z.write(server/f['path'],f['path'])
 report.update(logArchive=ap.name,logArchiveSha256=digest(ap));rp.write_text(json.dumps(report,indent=2)+'\n')
 if error:raise RuntimeError('Rejected evidence archived: '+str(error)) from error
 if validator:validator.main(rp)
 print('Command evidence:',rp)
if __name__=='__main__':main()
