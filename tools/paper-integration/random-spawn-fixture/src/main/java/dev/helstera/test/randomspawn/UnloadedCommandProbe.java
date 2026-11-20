package dev.helstera.test.randomspawn;

import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.world.*;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import java.nio.file.*;
import java.util.*;

/** Public unload events and actual command dispatch; no reflective mutation of native lifecycle state. */
public final class UnloadedCommandProbe extends JavaPlugin implements Listener {
    private static final int CX=256,RX=512,CZ=256;
    private record Pair(String key,String mode,String path,UUID nativeId,UUID runtimeId,int initialId) {}
    private Plugin runtime,mythic;private Object manager;private Class<?> extras,registryType,treeType,snapshotType;
    private World world;private Player player;private String stage,scenario,phase;private List<String> cases;
    private final List<Pair> pairs=new ArrayList<>();private final Map<String,Integer> hooks=new HashMap<>();
    private final YamlConfiguration identities=new YamlConfiguration();
    private int index,rows,unloadTick;private final Map<String,Integer> unloadTicks=new HashMap<>();private boolean failed,requested,handled;
    @FunctionalInterface private interface Step{void run()throws Exception;}
    private void later(long ticks,Step step){Bukkit.getScheduler().runTaskLater(this,()->{if(failed)return;try{step.run();}catch(Throwable e){failed=true;getLogger().severe("UCMD_FAILED|scenario="+scenario+"|error="+e);e.printStackTrace();}},ticks);}
    private Class<?> rt(String name)throws Exception{return Class.forName(name,true,runtime.getClass().getClassLoader());}
    private Class<?> nt(String name)throws Exception{return Class.forName(name,true,mythic.getClass().getClassLoader());}
    private Object instances()throws Exception{return runtime.getClass().getMethod("instances").invoke(runtime);}
    private Object instance(UUID uuid)throws Exception{
        for(Object i:(Collection<?>)instances().getClass().getMethod("allImpl").invoke(instances()))
            if(((Optional<?>)i.getClass().getMethod("boundEntityId").invoke(i)).filter(uuid::equals).isPresent())return i;return null;
    }
    @Override public void onEnable(){
        runtime=Bukkit.getPluginManager().getPlugin("helsteraMobs");mythic=Bukkit.getPluginManager().getPlugin("MythicMobs");
        stage=getConfig().getString("stage");cases=new ArrayList<>(getConfig().getConfigurationSection("cases").getKeys(false));
        Bukkit.getPluginManager().registerEvents(this,this);later(100,this::begin);
    }
    private World makeWorld(){
        World w=new WorldCreator("unloaded-command-"+scenario).generator(new ChunkGenerator(){
            @Override public ChunkData generateChunkData(World w,Random r,int x,int z,BiomeGrid b){return createChunkData(w);}
        }).generateStructures(false).createWorld();w.setKeepSpawnInMemory(false);w.setGameRule(GameRule.DO_MOB_SPAWNING,false);w.setGameRule(GameRule.MAX_ENTITY_CRAMMING,0);return w;
    }
    private void begin()throws Exception{
        manager=mythic.getClass().getMethod("getMobManager").invoke(mythic);
        extras=rt("dev.helstera.ai.skill.SkillExtras");registryType=rt("dev.helstera.api.behavior.ScopedVariableSnapshotStore$Registry");treeType=rt("dev.helstera.api.behavior.SkillTreeContext");snapshotType=rt("dev.helstera.api.behavior.SkillTreeContext$Snapshot");
        if(!stage.equals("write")){identities.load(getDataFolder().toPath().resolve("identities.yml").toFile());next();return;}
        player=Bukkit.getPlayerExact("CommandTester");if(player==null){later(20,this::begin);return;}
        player.setOp(true);player.setGameMode(GameMode.CREATIVE);next();
    }
    private void next()throws Exception{
        if(index==cases.size()){
            if(stage.equals("write"))identities.save(getDataFolder().toPath().resolve("identities.yml").toFile());
            extras.getMethod("saveScopedVariables",java.io.File.class).invoke(null,getDataFolder().toPath().resolve("scoped-"+stage+".yml").toFile());
            getLogger().info("UCMD_SUMMARY|stage="+stage+"|rows="+rows+"|completed=true");return;
        }
        scenario=cases.get(index);phase="prepare";pairs.clear();hooks.clear();requested=false;handled=false;unloadTicks.clear();world=makeWorld();
        if(stage.equals("write")){player.teleport(new Location(world,0,110,0));spawn();return;}
        for(String key:identities.getConfigurationSection(scenario+".mobs").getKeys(false)){
            String p=scenario+".mobs."+key;String[] bits=key.split("_");pairs.add(new Pair(key,bits[1].toUpperCase(Locale.ROOT),bits[2],UUID.fromString(identities.getString(p+".native")),UUID.fromString(identities.getString(p+".runtime")),identities.getInt(p+".instance")));
        }
        if(!world.getUID().toString().equals(identities.getString(scenario+".world")))throw new IllegalStateException("world UUID changed");
        loadChunk();later(40,()->{
            observe(stage.equals("verify")?"restart":"final");
            if(stage.equals("verify")){phase="cleanup";for(Pair p:pairs)for(String b:List.of("native","runtime"))Bukkit.dispatchCommand(Bukkit.getConsoleSender(),(b.equals("native")?"mythicmobs mobs kill -e ":"helstera mob kill -e ")+p.key);
                later(40,()->{observe("cleaned");seal();});}
            else seal();
        });
    }
    private void loadChunk(){for(int x:List.of(CX,RX)){var chunk=world.getChunkAt(x,CZ);chunk.getEntities();chunk.addPluginChunkTicket(this);}}
    private void spawn()throws Exception{
        loadChunk();for(int cx:List.of(CX,RX))for(int x=0;x<16;x++)for(int z=0;z<16;z++)world.getBlockAt(cx*16+x,109,CZ*16+z).setType(Material.STONE);
        Object importer=rt("dev.helstera.migration.importer.MythicMobsImporter").getConstructor().newInstance();
        @SuppressWarnings("unchecked") List<Map<String,Object>> plans=(List<Map<String,Object>>)importer.getClass().getMethod("convert",Path.class).invoke(importer,getDataFolder().toPath().resolve("Mobs/cases.yml"));
        int pos=0;
        for(String mode:List.of("NORMAL","CHUNK","NPC","NEVER","PERSISTENT"))for(String path:List.of("direct","imported")){
            String key="uc_"+mode.toLowerCase(Locale.ROOT)+"_"+path;var cfg=new YamlConfiguration();
            if(path.equals("imported")){
                cfg.loadFromString(plans.stream().filter(p->key.equals(p.get("source-mob-id"))&&p.containsKey("target-file")).findFirst().orElseThrow().get("content").toString());
                for(String e:List.of("despawn","death"))cfg.set("mob.triggers.on-"+e+".do",cfg.getStringList("mob.triggers.on-"+e+".do").stream().map(s->s.replace("hook native","hook runtime")).toList());
            }else{
                cfg.set("entity.type","COW");cfg.set("entity.no-ai",true);cfg.set("entity.gravity",false);cfg.set("entity.invulnerable",true);cfg.set("entity.collidable",false);cfg.set("mob.despawn",mode);
                for(String e:List.of("despawn","death"))cfg.set("ai.triggers.on-"+e+".do",List.of("command \"unloadedcommandprobe hook runtime "+e+" "+key+" <caster.var.Marker>\" @self"));
            }
            cfg.set("model","example/emberling");Path target=runtime.getDataFolder().toPath().resolve("mobs/"+key+".yml");cfg.save(target.toFile());
            Path imported=getDataFolder().toPath().resolve("imports/"+key+".yml");Files.createDirectories(imported.getParent());cfg.save(imported.toFile());
            Location at=new Location(world,CX*16+1+(pos%4)*3,110,CZ*16+1+(pos/4)*4);pos++;
            Object api=mythic.getClass().getMethod("getAPIHelper").invoke(mythic);var n=(LivingEntity)api.getClass().getMethod("spawnMythicMob",String.class,Location.class).invoke(api,key,at);
            int id=((Number)runtime.getClass().getMethod("spawnMobAt",String.class,Location.class,String.class).invoke(runtime,key,at.clone().add((RX-CX)*16,0,0),null)).intValue();Object inst=instances().getClass().getMethod("impl",int.class).invoke(instances(),id);var r=(LivingEntity)((Optional<?>)inst.getClass().getMethod("baseEntity").invoke(inst)).orElseThrow();
            Pair p=new Pair(key,mode,path,n.getUniqueId(),r.getUniqueId(),id);pairs.add(p);
            Object active=manager.getClass().getMethod("getMythicMobInstance",Entity.class).invoke(manager,n),vars=active.getClass().getMethod("getVariables").invoke(active);
            Class<?> type=nt("io.lumine.mythic.core.skills.variables.VariableType"),variable=nt("io.lumine.mythic.core.skills.variables.Variable");Object integer=Arrays.stream(type.getEnumConstants()).filter(v->v.toString().equals("INTEGER")).findFirst().orElseThrow();
            Object value=variable.getMethod("ofType",type,Object.class,boolean.class).invoke(null,integer,73,true);vars.getClass().getMethod("put",String.class,variable).invoke(vars,"Marker",value);
            Object registry=registryType.getConstructor(Map.class,Map.class,Map.class,Map.class).newInstance(Map.of("Marker",73),Map.of(),Map.of("Marker",0L),Map.of());extras.getMethod("restoreScopedVariables",String.class,registryType).invoke(null,"entity:"+r.getUniqueId(),registry);
            Object tree=treeType.getConstructor(Entity.class,List.class,Location.class).newInstance(r,List.of(r),at);treeType.getMethod("setVariable",String.class,Object.class).invoke(tree,"UnloadTree",91);Object snapshot=treeType.getMethod("snapshot").invoke(tree),service=runtime.getClass().getMethod("skillService").invoke(runtime);service.getClass().getMethod("restoreSkillTreeSnapshot",int.class,snapshotType).invoke(service,id,snapshot);
            String prefix=scenario+".mobs."+key;identities.set(prefix+".native",n.getUniqueId().toString());identities.set(prefix+".runtime",r.getUniqueId().toString());identities.set(prefix+".instance",id);identities.set(scenario+".world",world.getUID().toString());
            getLogger().info("UCMD_ID|scenario="+scenario+"|key="+key+"|mode="+mode+"|path="+path+"|native="+n.getUniqueId()+"|runtime="+r.getUniqueId()+"|instance="+id+"|world="+world.getUID());
        }
        later(20,()->{
            observe("before");phase="unloading";requested=true;
            // Force PERSISTENT through a genuine unload, as an administrator can.
            for(int x:List.of(CX,RX)){
                boolean forced=world.isChunkForceLoaded(x,CZ);world.setChunkForceLoaded(x,CZ,false);world.getChunkAt(x,CZ).removePluginChunkTicket(this);
                boolean accepted=world.unloadChunkRequest(x,CZ);getLogger().info("UCMD_REQUEST|scenario="+scenario+"|backend="+(x==CX?"native":"runtime")+"|x="+x+"|z="+CZ+"|accepted="+accepted+"|wasForced="+forced+"|tick="+Bukkit.getCurrentTick());
            }
            String waitingScenario=scenario;later(100,()->{if(waitingScenario.equals(scenario)&&!handled)throw new IllegalStateException("No entities unload event");});
        });
    }
    @EventHandler(priority=EventPriority.MONITOR)public void chunk(ChunkUnloadEvent e){
        if(requested&&e.getWorld().equals(world)&&List.of(CX,RX).contains(e.getChunk().getX())&&e.getChunk().getZ()==CZ)getLogger().info("UCMD_CHUNK_EVENT|scenario="+scenario+"|backend="+(e.getChunk().getX()==CX?"native":"runtime")+"|tick="+Bukkit.getCurrentTick()+"|save="+e.isSaveChunk());
    }
    @EventHandler(priority=EventPriority.MONITOR)public void loaded(ChunkLoadEvent e){
        if(requested&&e.getWorld().equals(world)&&List.of(CX,RX).contains(e.getChunk().getX())&&e.getChunk().getZ()==CZ)getLogger().info("UCMD_LOAD_EVENT|scenario="+scenario+"|backend="+(e.getChunk().getX()==CX?"native":"runtime")+"|tick="+Bukkit.getCurrentTick()+"|phase="+phase);
    }
    @EventHandler(priority=EventPriority.MONITOR)public void entities(EntitiesUnloadEvent e){
        if(!requested||!e.getWorld().equals(world)||!List.of(CX,RX).contains(e.getChunk().getX())||e.getChunk().getZ()!=CZ)return;
        String backend=e.getChunk().getX()==CX?"native":"runtime";
        getLogger().info("UCMD_ENTITIES_EVENT|scenario="+scenario+"|backend="+backend+"|tick="+Bukkit.getCurrentTick()+"|nativeCount="+pairs.stream().filter(p->e.getEntities().stream().anyMatch(x->x.getUniqueId().equals(p.nativeId))).count()+"|runtimeCount="+pairs.stream().filter(p->e.getEntities().stream().anyMatch(x->x.getUniqueId().equals(p.runtimeId))).count());
        if(handled)return;unloadTicks.put(backend,Bukkit.getCurrentTick());
        if(unloadTicks.size()==2){handled=true;unloadTick=Bukkit.getCurrentTick();if(!unloadTicks.get("native").equals(unloadTicks.get("runtime")))throw new IllegalStateException("Unload ticks differed");later(1,()->sample(1));}
    }
    private void sample(int number)throws Exception{
        observe("tick"+number);
        if(number==getConfig().getInt("cases."+scenario+".delay")){
            String suffix=getConfig().getString("cases."+scenario+".command");CommandSender sender=getConfig().getBoolean("cases."+scenario+".player")?player:Bukkit.getConsoleSender();
            if(scenario.equals("wrong_world"))player.teleport(Bukkit.getWorlds().getFirst().getSpawnLocation());
            getLogger().info("UCMD_COMMAND|scenario="+scenario+"|tick="+Bukkit.getCurrentTick()+"|elapsed="+(Bukkit.getCurrentTick()-unloadTick)+"|suffix="+suffix+"|player="+(sender instanceof Player)+"|senderWorld="+(sender instanceof Player sp?sp.getWorld().getUID():"console")+"|nativeLoaded="+world.isChunkLoaded(CX,CZ)+"|runtimeLoaded="+world.isChunkLoaded(RX,CZ));
            if(!suffix.equals("none"))for(String b:List.of("native","runtime"))getLogger().info("UCMD_DISPATCH|scenario="+scenario+"|backend="+b+"|result="+Bukkit.dispatchCommand(sender,(b.equals("native")?"mythicmobs mobs ":"helstera mob ")+suffix));
            observe("command-after");
        }
        if(number<10){later(1,()->sample(number+1));return;}
        later(20,()->{observe("unloaded");loadChunk();later(40,()->{observe("reloaded");seal();});});
    }
    private void seal()throws Exception{
        requested=false;if(player!=null)player.teleport(Bukkit.getWorlds().getFirst().getSpawnLocation());
        world.removePluginChunkTickets(this);boolean ok=Bukkit.unloadWorld(world,true);getLogger().info("UCMD_SEAL|stage="+stage+"|scenario="+scenario+"|accepted="+ok+"|absent="+(Bukkit.getWorld(world.getUID())==null));
        if(!ok)throw new IllegalStateException("World did not unload");later(20,()->{index++;next();});
    }
    private String nativeValue(Object registry)throws Exception{if(registry==null)return "absent";Object v=registry.getClass().getMethod("get",String.class).invoke(registry,"Marker");return v==null?"absent":v.getClass().getMethod("get").invoke(v).toString();}
    private void observe(String checkpoint)throws Exception{
        phase=checkpoint;boolean nativeLoaded=world.isChunkLoaded(CX,CZ),runtimeLoaded=world.isChunkLoaded(RX,CZ);
        for(Pair p:pairs){
            Object active=((Optional<?>)manager.getClass().getMethod("getActiveMob",UUID.class).invoke(manager,p.nativeId)).orElse(null),nr=active==null?null:active.getClass().getMethod("getVariables").invoke(active);
            Object registry=extras.getMethod("scopedVariableSnapshot",String.class).invoke(null,"entity:"+p.runtimeId);Object rv=((Map<?,?>)registryType.getMethod("variables").invoke(registry)).get("Marker");
            Object inst=instance(p.runtimeId),service=runtime.getClass().getMethod("skillService").invoke(runtime);Optional<?> tree=inst==null?Optional.empty():(Optional<?>)service.getClass().getMethod("snapshotOf",int.class).invoke(service,((Number)inst.getClass().getMethod("instanceId").invoke(inst)).intValue());Object tv=tree.isEmpty()?null:((Map<?,?>)snapshotType.getMethod("variables").invoke(tree.get())).get("UnloadTree");
            Path file=runtime.getDataFolder().toPath().resolve("skill-trees/entity-"+p.runtimeId+".yml");
            getLogger().info("UCMD_ROW|stage="+stage+"|scenario="+scenario+"|phase="+phase+"|key="+p.key+"|elapsed="+(Bukkit.getCurrentTick()-unloadTick)+"|nativeLoaded="+nativeLoaded+"|runtimeLoaded="+runtimeLoaded+"|nativeEntity="+(Bukkit.getEntity(p.nativeId)!=null)+"|runtimeEntity="+(Bukkit.getEntity(p.runtimeId)!=null)+"|nativeRegistry="+(active!=null)+"|runtimeInstance="+(inst!=null)+"|nativeVariable="+nativeValue(nr)+"|runtimeVariable="+(rv==null?"absent":rv)+"|tree="+(tv==null?"absent":tv)+"|file="+Files.exists(file)+"|nativeHooks="+hooks.getOrDefault("native:"+p.key,0)+"|runtimeHooks="+hooks.getOrDefault("runtime:"+p.key,0));rows++;
            if(Files.exists(file)&&List.of("unloaded","reloaded","restart").contains(phase)){Path copy=getDataFolder().toPath().resolve("snapshots/"+stage+"-"+scenario+"-"+phase+"-"+p.key+".yml");Files.createDirectories(copy.getParent());Files.copy(file,copy);}
        }
        long visuals=world.getEntities().stream().filter(e->List.of(CX,RX).contains(e.getLocation().getBlockX()>>4)&&(e.getLocation().getBlockZ()>>4)==CZ).filter(e->e instanceof Display||e instanceof Interaction||e instanceof ArmorStand).count();getLogger().info("UCMD_VISUALS|stage="+stage+"|scenario="+scenario+"|phase="+phase+"|count="+visuals);
    }
    @Override public boolean onCommand(CommandSender sender,Command cmd,String label,String[] args){
        if(args.length==5&&args[0].equals("hook")){hooks.merge(args[1]+":"+args[3],1,Integer::sum);getLogger().info("UCMD_HOOK|stage="+stage+"|scenario="+scenario+"|phase="+phase+"|backend="+args[1]+"|event="+args[2]+"|key="+args[3]+"|marker="+args[4]+"|tick="+Bukkit.getCurrentTick());return true;}return false;
    }
    @EventHandler(priority=EventPriority.MONITOR)public void death(EntityDeathEvent e){for(Pair p:pairs)if(p.nativeId.equals(e.getEntity().getUniqueId())||p.runtimeId.equals(e.getEntity().getUniqueId()))getLogger().severe("UCMD_DEATH|key="+p.key);}
}
