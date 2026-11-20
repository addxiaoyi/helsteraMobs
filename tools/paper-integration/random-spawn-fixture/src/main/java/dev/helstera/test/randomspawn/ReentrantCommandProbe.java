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

/** Native/runtime command callbacks invoke nested commands through public dispatch. */
public final class ReentrantCommandProbe extends JavaPlugin implements Listener {
    private static final int NX=256,RX=512,Z=256;
    private static final List<String> ROLES=List.of("lead","peer","protected","child");
    private record Identity(UUID uuid,int initialId) {}
    private final Map<String,Identity> ids=new LinkedHashMap<>();
    private final Map<String,Integer> hooks=new HashMap<>(),unloadTicks=new HashMap<>();
    private final YamlConfiguration identities=new YamlConfiguration();
    private Plugin runtime,mythic;private Object manager;private Class<?> extras,registryType,treeType,snapshotType;
    private World world;private Player player;private String stage,scenario,phase;private List<String> cases;
    private int index,rows,commandDepth;private boolean failed,unloading,started;
    @FunctionalInterface private interface Step{void run()throws Exception;}
    private void fail(Throwable e){failed=true;getLogger().severe("RCMD_FAILED|scenario="+scenario+"|error="+e);e.printStackTrace();}
    private void later(long ticks,Step step){Bukkit.getScheduler().runTaskLater(this,()->{if(!failed)try{step.run();}catch(Throwable e){fail(e);}},ticks);}
    private Class<?> rt(String name)throws Exception{return Class.forName(name,true,runtime.getClass().getClassLoader());}
    private Class<?> nt(String name)throws Exception{return Class.forName(name,true,mythic.getClass().getClassLoader());}
    private Object instances()throws Exception{return runtime.getClass().getMethod("instances").invoke(runtime);}
    private Object instance(UUID uuid)throws Exception{
        for(Object i:(Collection<?>)instances().getClass().getMethod("allImpl").invoke(instances()))
            if(((Optional<?>)i.getClass().getMethod("boundEntityId").invoke(i)).filter(uuid::equals).isPresent())return i;return null;
    }
    @Override public void onEnable(){
        runtime=Bukkit.getPluginManager().getPlugin("helsteraMobs");mythic=Bukkit.getPluginManager().getPlugin("MythicMobs");stage=getConfig().getString("stage");
        cases=new ArrayList<>(getConfig().getConfigurationSection("cases").getKeys(false));Bukkit.getPluginManager().registerEvents(this,this);later(100,this::begin);
    }
    private void begin()throws Exception{
        manager=mythic.getClass().getMethod("getMobManager").invoke(mythic);
        extras=rt("dev.helstera.ai.skill.SkillExtras");registryType=rt("dev.helstera.api.behavior.ScopedVariableSnapshotStore$Registry");treeType=rt("dev.helstera.api.behavior.SkillTreeContext");snapshotType=rt("dev.helstera.api.behavior.SkillTreeContext$Snapshot");
        if(!stage.equals("write")){identities.load(getDataFolder().toPath().resolve("identities.yml").toFile());next();return;}
        player=Bukkit.getPlayerExact("CommandTester");if(player==null){later(20,this::begin);return;}player.setOp(true);player.setGameMode(GameMode.CREATIVE);next();
    }
    private World makeWorld(){
        var w=new WorldCreator("reentrant-"+scenario).generator(new ChunkGenerator(){
            @Override public ChunkData generateChunkData(World w,Random r,int x,int z,BiomeGrid b){return createChunkData(w);}
        }).generateStructures(false).createWorld();w.setKeepSpawnInMemory(false);w.setGameRule(GameRule.DO_MOB_SPAWNING,false);w.setGameRule(GameRule.MAX_ENTITY_CRAMMING,0);return w;
    }
    private void load(){for(int x:List.of(NX,RX)){var c=world.getChunkAt(x,Z);c.getEntities();c.addPluginChunkTicket(this);}}
    private String setting(String key){return getConfig().getString("cases."+scenario+"."+key);}
    private void next()throws Exception{
        if(index==cases.size()){
            if(stage.equals("write"))identities.save(getDataFolder().toPath().resolve("identities.yml").toFile());
            extras.getMethod("saveScopedVariables",java.io.File.class).invoke(null,getDataFolder().toPath().resolve("scoped-"+stage+".yml").toFile());
            getLogger().info("RCMD_SUMMARY|stage="+stage+"|rows="+rows+"|completed=true");return;
        }
        scenario=cases.get(index);phase="prepare";ids.clear();hooks.clear();unloadTicks.clear();unloading=false;started=false;world=makeWorld();load();
        if(stage.equals("write")){player.teleport(new Location(world,0,110,0));configure();
            for(int cx:List.of(NX,RX))for(int x=0;x<16;x++)for(int z=0;z<16;z++)world.getBlockAt(cx*16+x,109,Z*16+z).setType(Material.STONE);
            for(String backend:List.of("native","runtime"))for(String role:ROLES)if(!role.equals("child"))spawn(backend,role);
            later(20,()->{observe("before");if(setting("state").equals("unloading"))unload();else execute();});return;
        }
        if(!world.getUID().toString().equals(identities.getString(scenario+".world")))throw new IllegalStateException("World identity changed");
        for(String key:identities.getConfigurationSection(scenario+".mobs").getKeys(false)){
            String p=scenario+".mobs."+key;ids.put(key,new Identity(UUID.fromString(identities.getString(p+".uuid")),identities.getInt(p+".instance")));
        }
        later(40,()->{observe(stage.equals("verify")?"restart":"final");
            if(stage.equals("verify")){phase="cleanup";for(String b:List.of("native","runtime"))dispatch(b,"killall -p");later(30,()->{observe("cleaned");seal();});}
            else seal();
        });
    }
    private void configure()throws Exception{
        Object importer=rt("dev.helstera.migration.importer.MythicMobsImporter").getConstructor().newInstance();
        @SuppressWarnings("unchecked") List<Map<String,Object>> plans=(List<Map<String,Object>>)importer.getClass().getMethod("convert",Path.class).invoke(importer,getDataFolder().toPath().resolve("Mobs/cases.yml"));
        for(String role:ROLES){String key="rc_"+role;var cfg=new YamlConfiguration();
            if(setting("path").equals("imported")){
                cfg.loadFromString(plans.stream().filter(p->key.equals(p.get("source-mob-id"))&&p.containsKey("target-file")).findFirst().orElseThrow().get("content").toString());
                for(String e:List.of("despawn","death"))cfg.set("mob.triggers.on-"+e+".do",cfg.getStringList("mob.triggers.on-"+e+".do").stream().map(s->s.replace("hook native","hook runtime")).toList());
            }else{
                cfg.set("entity.type","COW");cfg.set("entity.no-ai",true);cfg.set("entity.gravity",false);cfg.set("entity.invulnerable",true);cfg.set("entity.collidable",false);cfg.set("mob.despawn",role.equals("protected")?"PERSISTENT":"NEVER");
                for(String e:List.of("despawn","death"))cfg.set("ai.triggers.on-"+e+".do",List.of("command \"reentrantcommandprobe hook runtime "+e+" "+key+" <caster.uuid> <caster.var.Marker>\" @self"));
            }
            cfg.set("model","example/emberling");cfg.save(runtime.getDataFolder().toPath().resolve("mobs/"+key+".yml").toFile());
            Path target=getDataFolder().toPath().resolve("imports/"+setting("path")+"-"+key+".yml");Files.createDirectories(target.getParent());cfg.save(target.toFile());
        }
    }
    private void spawn(String backend,String role)throws Exception{
        String key=backend+"_"+role;if(ids.containsKey(key))throw new IllegalStateException("Repeated spawn "+key);
        Location at=new Location(world,(backend.equals("native")?NX:RX)*16+2+ROLES.indexOf(role)*3,110,Z*16+3);LivingEntity entity;int id=0;
        if(backend.equals("native")){
            Object api=mythic.getClass().getMethod("getAPIHelper").invoke(mythic);entity=(LivingEntity)api.getClass().getMethod("spawnMythicMob",String.class,Location.class).invoke(api,"rc_"+role,at);
            Object active=manager.getClass().getMethod("getMythicMobInstance",Entity.class).invoke(manager,entity),vars=active.getClass().getMethod("getVariables").invoke(active);
            Class<?> type=nt("io.lumine.mythic.core.skills.variables.VariableType"),variable=nt("io.lumine.mythic.core.skills.variables.Variable");Object integer=Arrays.stream(type.getEnumConstants()).filter(v->v.toString().equals("INTEGER")).findFirst().orElseThrow();
            Object value=variable.getMethod("ofType",type,Object.class,boolean.class).invoke(null,integer,73,true);vars.getClass().getMethod("put",String.class,variable).invoke(vars,"Marker",value);
        }else{
            id=((Number)runtime.getClass().getMethod("spawnMobAt",String.class,Location.class,String.class).invoke(runtime,"rc_"+role,at,null)).intValue();Object inst=instances().getClass().getMethod("impl",int.class).invoke(instances(),id);entity=(LivingEntity)((Optional<?>)inst.getClass().getMethod("baseEntity").invoke(inst)).orElseThrow();
            Object registry=registryType.getConstructor(Map.class,Map.class,Map.class,Map.class).newInstance(Map.of("Marker",73),Map.of(),Map.of("Marker",0L),Map.of());extras.getMethod("restoreScopedVariables",String.class,registryType).invoke(null,"entity:"+entity.getUniqueId(),registry);
            Object tree=treeType.getConstructor(Entity.class,List.class,Location.class).newInstance(entity,List.of(entity),at);treeType.getMethod("setVariable",String.class,Object.class).invoke(tree,"ReentrantTree",91);Object snap=treeType.getMethod("snapshot").invoke(tree),service=runtime.getClass().getMethod("skillService").invoke(runtime);service.getClass().getMethod("restoreSkillTreeSnapshot",int.class,snapshotType).invoke(service,id,snap);
        }
        ids.put(key,new Identity(entity.getUniqueId(),id));String prefix=scenario+".mobs."+key;identities.set(prefix+".uuid",entity.getUniqueId().toString());identities.set(prefix+".instance",id);identities.set(scenario+".world",world.getUID().toString());
        getLogger().info("RCMD_ID|scenario="+scenario+"|backend="+backend+"|role="+role+"|uuid="+entity.getUniqueId()+"|instance="+id+"|phase="+phase+"|world="+world.getUID()+"|tick="+Bukkit.getCurrentTick());
    }
    private void unload(){
        phase="unloading";unloading=true;for(int x:List.of(NX,RX)){world.setChunkForceLoaded(x,Z,false);world.getChunkAt(x,Z).removePluginChunkTicket(this);boolean accepted=world.unloadChunkRequest(x,Z);
            getLogger().info("RCMD_REQUEST|scenario="+scenario+"|backend="+(x==NX?"native":"runtime")+"|x="+x+"|accepted="+accepted+"|tick="+Bukkit.getCurrentTick());}
        String waiting=scenario;later(100,()->{if(waiting.equals(scenario)&&!started)throw new IllegalStateException("No paired unload");});
    }
    @EventHandler(priority=EventPriority.MONITOR)public void unloadEvent(EntitiesUnloadEvent event){
        if(!unloading||!event.getWorld().equals(world)||!List.of(NX,RX).contains(event.getChunk().getX())||event.getChunk().getZ()!=Z)return;
        String backend=event.getChunk().getX()==NX?"native":"runtime";
        getLogger().info("RCMD_UNLOAD|scenario="+scenario+"|backend="+backend+"|tick="+Bukkit.getCurrentTick()+"|count="+event.getEntities().stream().filter(e->ids.values().stream().anyMatch(i->i.uuid.equals(e.getUniqueId()))).count());
        if(started)return;unloadTicks.put(backend,Bukkit.getCurrentTick());
        if(unloadTicks.size()==2){started=true;later(1,this::execute);}
    }
    @EventHandler(priority=EventPriority.MONITOR)public void loadEvent(ChunkLoadEvent e){
        if(world!=null&&e.getWorld().equals(world)&&List.of(NX,RX).contains(e.getChunk().getX())&&e.getChunk().getZ()==Z)getLogger().info("RCMD_LOAD|scenario="+scenario+"|backend="+(e.getChunk().getX()==NX?"native":"runtime")+"|tick="+Bukkit.getCurrentTick()+"|phase="+phase);
    }
    private void execute()throws Exception{
        observe("ready");phase="command";for(String b:List.of("native","runtime"))dispatch(b,setting("command"));observe("command-after");
        later(40,()->{observe("settled");load();later(30,()->{observe("reloaded");seal();});});
    }
    private void dispatch(String backend,String suffix){
        CommandSender sender=suffix.matches(".*[0-9]$")?player:Bukkit.getConsoleSender();int depth=++commandDepth;
        try{getLogger().info("RCMD_COMMAND|scenario="+scenario+"|backend="+backend+"|phase="+phase+"|depth="+depth+"|suffix="+suffix+"|tick="+Bukkit.getCurrentTick()+"|nativeLoaded="+world.isChunkLoaded(NX,Z)+"|runtimeLoaded="+world.isChunkLoaded(RX,Z));
            boolean result=Bukkit.dispatchCommand(sender,(backend.equals("native")?"mythicmobs mobs ":"helstera mob ")+suffix);getLogger().info("RCMD_RETURN|scenario="+scenario+"|backend="+backend+"|depth="+depth+"|result="+result);
        }finally{commandDepth--;}
    }
    private void seal()throws Exception{
        phase="sealing";unloading=false;if(player!=null)player.teleport(Bukkit.getWorlds().getFirst().getSpawnLocation());world.removePluginChunkTickets(this);
        boolean accepted=Bukkit.unloadWorld(world,true);getLogger().info("RCMD_SEAL|stage="+stage+"|scenario="+scenario+"|accepted="+accepted);if(!accepted)throw new IllegalStateException("World unload denied");later(10,()->{index++;next();});
    }
    private void observe(String checkpoint)throws Exception{
        phase=checkpoint;
        for(String role:ROLES){StringBuilder row=new StringBuilder("RCMD_ROW|stage="+stage+"|scenario="+scenario+"|phase="+phase+"|role="+role);
            for(String b:List.of("native","runtime")){var id=ids.get(b+"_"+role);Object inst=id==null?null:instance(id.uuid);Object active=id==null?null:((Optional<?>)manager.getClass().getMethod("getActiveMob",UUID.class).invoke(manager,id.uuid)).orElse(null);Object variable=null,tree=null;
                if(b.equals("runtime")&&id!=null){Object registry=extras.getMethod("scopedVariableSnapshot",String.class).invoke(null,"entity:"+id.uuid);variable=((Map<?,?>)registryType.getMethod("variables").invoke(registry)).get("Marker");
                    if(inst!=null){Object service=runtime.getClass().getMethod("skillService").invoke(runtime);Optional<?> snap=(Optional<?>)service.getClass().getMethod("snapshotOf",int.class).invoke(service,((Number)inst.getClass().getMethod("instanceId").invoke(inst)).intValue());if(snap.isPresent())tree=((Map<?,?>)snapshotType.getMethod("variables").invoke(snap.get())).get("ReentrantTree");}}
                else if(active!=null){Object vars=active.getClass().getMethod("getVariables").invoke(active);Object v=vars.getClass().getMethod("get",String.class).invoke(vars,"Marker");if(v!=null)variable=v.getClass().getMethod("get").invoke(v);}
                row.append("|").append(b).append("Id=").append(id==null?"absent":id.uuid).append("|").append(b).append("Entity=").append(id!=null&&Bukkit.getEntity(id.uuid)!=null).append("|").append(b).append("Registry=").append(b.equals("native")?active!=null:inst!=null).append("|").append(b).append("Variable=").append(variable==null?"absent":variable).append("|").append(b).append("Hooks=").append(hooks.getOrDefault(b+"_"+role,0));
                if(b.equals("runtime"))row.append("|tree=").append(tree==null?"absent":tree).append("|file=").append(id!=null&&Files.exists(runtime.getDataFolder().toPath().resolve("skill-trees/entity-"+id.uuid+".yml")));
            }getLogger().info(row+"|tick="+Bukkit.getCurrentTick()+"|nativeLoaded="+world.isChunkLoaded(NX,Z)+"|runtimeLoaded="+world.isChunkLoaded(RX,Z));rows++;
        }
        long visuals=world.getEntities().stream().filter(e->e instanceof Display||e instanceof Interaction||e instanceof ArmorStand).count();getLogger().info("RCMD_VISUALS|stage="+stage+"|scenario="+scenario+"|phase="+phase+"|count="+visuals);
    }
    @Override public boolean onCommand(CommandSender sender,Command cmd,String label,String[] args){
        if(args.length!=6||!args[0].equals("hook"))return false;
        try{String b=args[1],role=args[3].substring(3),key=b+"_"+role;int n=hooks.merge(key,1,Integer::sum);
            getLogger().info("RCMD_HOOK|stage="+stage+"|scenario="+scenario+"|phase="+phase+"|backend="+b+"|role="+role+"|event="+args[2]+"|uuid="+args[4]+"|marker="+args[5]+"|count="+n+"|depth="+commandDepth+"|tick="+Bukkit.getCurrentTick()+"|primary="+Bukkit.isPrimaryThread());
            if(n>1){getLogger().warning("RCMD_REPLAY|scenario="+scenario+"|backend="+b+"|role="+role);return true;}
            if(stage.equals("write")&&role.equals("lead")&&!phase.equals("sealing")){
                for(String action:setting("action").split("-")){switch(action){case "spawn"->spawn(b,"child");case "load"->{world.getChunkAt(b.equals("native")?NX:RX,Z).getEntities();getLogger().info("RCMD_CALLBACK_LOAD|scenario="+scenario+"|backend="+b+"|loaded="+world.isChunkLoaded(b.equals("native")?NX:RX,Z));}case "self"->dispatch(b,"kill -e rc_lead");case "peer"->dispatch(b,"kill -e rc_peer");case "force"->dispatch(b,"killall -p");case "radius"->dispatch(b,"killall -p 10000");default->throw new IllegalStateException(action);}}
            }return true;
        }catch(Throwable e){fail(e);return true;}
    }
    @EventHandler(priority=EventPriority.MONITOR)public void death(EntityDeathEvent e){if(ids.values().stream().anyMatch(id->id.uuid.equals(e.getEntity().getUniqueId())))getLogger().severe("RCMD_DEATH|uuid="+e.getEntity().getUniqueId());}
}
