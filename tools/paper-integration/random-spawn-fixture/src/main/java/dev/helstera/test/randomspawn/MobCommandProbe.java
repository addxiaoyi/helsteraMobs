package dev.helstera.test.randomspawn;

import org.bukkit.*;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import java.nio.file.*;
import java.util.*;

/** Actual command dispatch, live callback context, UUID snapshots and process restart. */
public final class MobCommandProbe extends JavaPlugin implements Listener {
    private record Pair(String key,String mode,String path,String zone,UUID nativeId,UUID runtimeId,int instanceId) {}
    private final List<Pair> pairs=new ArrayList<>();
    private final List<UUID> history=new ArrayList<>();
    private final Map<String,Integer> hooks=new HashMap<>();
    private Plugin runtime,mythic;private Object manager;private World world,cross;private Player player;
    private List<String> cases;private int index,rows;private String scenario,stage;private boolean failed;
    private final Map<String,Integer> controls=new LinkedHashMap<>();private LivingEntity controlCow;
    private Class<?> treeType,snapshotType,extras,registryType;
    @FunctionalInterface private interface Step{void run() throws Exception;}
    private void later(long ticks,Step step){Bukkit.getScheduler().runTaskLater(this,()->{
        if(failed)return;try{step.run();}catch(Throwable e){failed=true;getLogger().severe("DCMD_FAILED|scenario="+scenario+"|error="+e);e.printStackTrace();}
    },ticks);}
    private Class<?> rt(String name)throws Exception{return Class.forName(name,true,runtime.getClass().getClassLoader());}
    @Override public void onEnable(){
        runtime=Bukkit.getPluginManager().getPlugin("helsteraMobs");mythic=Bukkit.getPluginManager().getPlugin("MythicMobs");stage=getConfig().getString("stage");
        Bukkit.getPluginManager().registerEvents(this,this);later(100,this::begin);
    }
    private World makeWorld(String name){return new WorldCreator(name).generator(new ChunkGenerator(){
        @Override public ChunkData generateChunkData(World w,Random r,int x,int z,BiomeGrid b){return createChunkData(w);}
    }).generateStructures(false).createWorld();}
    private void begin() throws Exception{
        manager=mythic.getClass().getMethod("getMobManager").invoke(mythic);world=makeWorld("command-world");cross=makeWorld("command-cross");
        for(World w:List.of(world,cross)){w.setKeepSpawnInMemory(false);w.setGameRule(GameRule.DO_MOB_SPAWNING,false);w.setGameRule(GameRule.MAX_ENTITY_CRAMMING,0);
            for(int x=0;x<4;x++){var c=w.getChunkAt(x,0);c.getEntities();c.addPluginChunkTicket(this);}
            for(int x=0;x<40;x++)for(int z=0;z<4;z++)w.getBlockAt(x,109,z).setType(Material.STONE);
        }
        extras=rt("dev.helstera.ai.skill.SkillExtras");registryType=rt("dev.helstera.api.behavior.ScopedVariableSnapshotStore$Registry");
        treeType=rt("dev.helstera.api.behavior.SkillTreeContext");snapshotType=rt("dev.helstera.api.behavior.SkillTreeContext$Snapshot");
        if(stage.equals("verify")){later(60,this::verifyRestart);return;}
        cases=new ArrayList<>(getConfig().getConfigurationSection("cases").getKeys(false));later(20,this::awaitPlayer);
    }
    private void awaitPlayer() throws Exception{
        player=Bukkit.getPlayerExact("CommandTester");if(player==null){later(20,this::awaitPlayer);return;}
        player.setGameMode(GameMode.CREATIVE);player.setOp(true);player.teleport(new Location(world,0,110,0));
        Class<?> options=rt("dev.helstera.api.instance.SpawnOptions");
        Object defaults=options.getMethod("defaults").invoke(null),im=instances();
        controlCow=world.spawn(new Location(world,4,110,2),Cow.class);controlCow.setAI(false);controlCow.setGravity(false);
        for(var entry:Map.of("player",(Entity)player,"unmanaged",(Entity)controlCow).entrySet()){
            Object model=im.getClass().getMethod("bind",String.class,Entity.class,options).invoke(im,"example/emberling",entry.getValue(),defaults);
            controls.put(entry.getKey(),((Number)model.getClass().getMethod("instanceId").invoke(model)).intValue());
        }
        Object model=im.getClass().getMethod("spawn",String.class,Location.class,options).invoke(im,"example/emberling",new Location(world,4,110,3),defaults);
        controls.put("model",((Number)model.getClass().getMethod("instanceId").invoke(model)).intValue());
        later(20,this::spawn);
    }
    private Location point(String zone){return new Location(zone.equals("cross")?cross:world,switch(zone){case "near","cross"->8;case "boundary"->16;default->32;},110,0);}
    private Object instances()throws Exception{return runtime.getClass().getMethod("instances").invoke(runtime);}
    private Object instance(UUID uuid)throws Exception{
        for(Object i:(Collection<?>)instances().getClass().getMethod("allImpl").invoke(instances()))
            if(((Optional<?>)i.getClass().getMethod("boundEntityId").invoke(i)).filter(uuid::equals).isPresent())return i;return null;
    }
    private void spawn()throws Exception{
        scenario=cases.get(index);pairs.clear();hooks.clear();
        getLogger().info("DCMD_PHASE|scenario="+scenario+"|phase=spawn");
        var source=YamlConfiguration.loadConfiguration(getDataFolder().toPath().resolve("Mobs/cases.yml").toFile());
        Object importer=rt("dev.helstera.migration.importer.MythicMobsImporter").getConstructor().newInstance();
        @SuppressWarnings("unchecked") List<Map<String,Object>> plans=(List<Map<String,Object>>)importer.getClass().getMethod("convert",Path.class).invoke(importer,getDataFolder().toPath().resolve("Mobs/cases.yml"));
        for(String mode:List.of("NORMAL","CHUNK","NPC","NEVER","PERSISTENT"))for(String path:List.of("direct","imported"))for(String zone:List.of("near","boundary","far","cross")){
            String key="cmd_"+mode.toLowerCase(Locale.ROOT)+"_"+path+"_"+zone;var cfg=new YamlConfiguration();
            if(path.equals("imported")){
                cfg.loadFromString(plans.stream().filter(e->key.equals(e.get("source-mob-id"))&&e.containsKey("target-file")).findFirst().orElseThrow().get("content").toString());
                for(String event:List.of("despawn","death"))cfg.set("mob.triggers.on-"+event+".do",cfg.getStringList("mob.triggers.on-"+event+".do").stream().map(s->s.replace("hook native","hook runtime")).toList());
            }else{
                cfg.set("entity.type","COW");cfg.set("entity.no-ai",true);cfg.set("entity.gravity",false);cfg.set("entity.invulnerable",true);cfg.set("entity.collidable",false);cfg.set("mob.despawn",mode);
                cfg.set("ai.faction",zone.equals("near")||zone.equals("far")?"cmd":"other");
                for(String event:List.of("despawn","death"))cfg.set("ai.triggers.on-"+event+".do",List.of("command \"mobcommandprobe hook runtime "+event+" "+key+" <caster.var.Marker>\" @self"));
            }
            cfg.set("model","example/emberling");cfg.save(runtime.getDataFolder().toPath().resolve("mobs/"+key+".yml").toFile());
            Object api=mythic.getClass().getMethod("getAPIHelper").invoke(mythic);
            var n=(LivingEntity)api.getClass().getMethod("spawnMythicMob",String.class,Location.class).invoke(api,key,point(zone));
            int id=((Number)runtime.getClass().getMethod("spawnMobAt",String.class,Location.class,String.class).invoke(runtime,key,point(zone),null)).intValue();
            Object inst=instances().getClass().getMethod("impl",int.class).invoke(instances(),id);
            var r=(LivingEntity)((Optional<?>)inst.getClass().getMethod("baseEntity").invoke(inst)).orElseThrow();
            var pair=new Pair(key,mode,path,zone,n.getUniqueId(),r.getUniqueId(),id);pairs.add(pair);history.add(n.getUniqueId());history.add(r.getUniqueId());
            Object active=manager.getClass().getMethod("getMythicMobInstance",Entity.class).invoke(manager,n),vars=active.getClass().getMethod("getVariables").invoke(active);
            vars.getClass().getMethod("putInt",String.class,int.class).invoke(vars,"Marker",73);
            Object registry=registryType.getConstructor(Map.class,Map.class,Map.class,Map.class).newInstance(Map.of("Marker",73),Map.of(),Map.of(),Map.of());
            extras.getMethod("restoreScopedVariables",String.class,registryType).invoke(null,"entity:"+r.getUniqueId(),registry);
            Object tree=treeType.getConstructor(Entity.class,List.class,Location.class).newInstance(r,List.of(r),point(zone));treeType.getMethod("setVariable",String.class,Object.class).invoke(tree,"CommandTree",91);
            Object snapshot=treeType.getMethod("snapshot").invoke(tree),service=runtime.getClass().getMethod("skillService").invoke(runtime);
            service.getClass().getMethod("restoreSkillTreeSnapshot",int.class,snapshotType).invoke(service,id,snapshot);
            Path store=runtime.getDataFolder().toPath().resolve("skill-trees");Files.createDirectories(store);
            for(String file:List.of("entity-"+r.getUniqueId()+".yml",id+".yml"))rt("dev.helstera.api.behavior.SkillTreeSnapshotStore").getMethod("save",java.io.File.class,snapshotType).invoke(null,store.resolve(file).toFile(),snapshot);
            getLogger().info("DCMD_ID|scenario="+scenario+"|key="+key+"|mode="+mode+"|path="+path+"|zone="+zone+"|native="+n.getUniqueId()+"|runtime="+r.getUniqueId()+"|instance="+id);
        }
        later(20,()->{observe("before");commands();});
    }
    private void commands()throws Exception{
        boolean denied=scenario.equals("denied");var permissions=player.addAttachment(this);
        permissions.setPermission("mythicmobs.admin",!denied);permissions.setPermission("mythicmobs.command.mobs.killall",!denied);permissions.setPermission("helstera.mob",!denied);
        String suffix=getConfig().getString("cases."+scenario+".command");CommandSender sender=getConfig().getBoolean("cases."+scenario+".player")?player:Bukkit.getConsoleSender();
        getLogger().info("DCMD_PHASE|scenario="+scenario+"|phase=command|player="+(sender instanceof Player)+"|suffix="+suffix+"|nativeAdmin="+sender.hasPermission("mythicmobs.admin")+"|nativePermission="+sender.hasPermission("mythicmobs.command.mobs.killall")+"|runtimePermission="+sender.hasPermission("helstera.mob"));
        for(String backend:List.of("native","runtime")){
            String command=(backend.equals("native")?"mythicmobs mobs ":"helstera mob ")+suffix;
            getLogger().info("DCMD_DISPATCH|scenario="+scenario+"|backend="+backend+"|result="+Bukkit.dispatchCommand(sender,command));
        }
        player.removeAttachment(permissions);
        later(40,()->{observe("after");cleanup();});
    }
    private void cleanup()throws Exception{
        getLogger().info("DCMD_PHASE|scenario="+scenario+"|phase=cleanup");
        for(Pair p:pairs){
            Object active=((Optional<?>)manager.getClass().getMethod("getActiveMob",UUID.class).invoke(manager,p.nativeId)).orElse(null);
            if(active!=null){active.getClass().getMethod("setDespawned").invoke(active);Entity e=Bukkit.getEntity(p.nativeId);if(e!=null)e.remove();}
            runtime.getClass().getMethod("removeMobByCommand",int.class).invoke(runtime,p.instanceId);
        }
        later(40,()->{observe("cleaned");index++;if(index<cases.size())spawn();else finishWrite();});
    }
    private void observe(String phase)throws Exception{
        for(var entry:controls.entrySet()){
            Object model=instances().getClass().getMethod("impl",int.class).invoke(instances(),entry.getValue());
            Object renderer=instances().getClass().getMethod("renderer").invoke(instances());
            int rendered=((Number)renderer.getClass().getMethod("renderedEntityCount",int.class).invoke(renderer,entry.getValue())).intValue();
            boolean rejected=!(Boolean)runtime.getClass().getMethod("removeMobByCommand",int.class).invoke(runtime,entry.getValue());
            getLogger().info("DCMD_CONTROL|scenario="+scenario+"|phase="+phase+"|kind="+entry.getKey()+"|instance="+(model!=null)+"|rendered="+rendered+"|apiRejected="+rejected+"|base="+(entry.getKey().equals("player")?player.isOnline():entry.getKey().equals("unmanaged")?controlCow.isValid():true));
        }
        for(Pair p:pairs){
            Entity n=Bukkit.getEntity(p.nativeId),r=Bukkit.getEntity(p.runtimeId);Object inst=instance(p.runtimeId);
            boolean active=((Optional<?>)manager.getClass().getMethod("getActiveMob",UUID.class).invoke(manager,p.nativeId)).isPresent();
            Object registry=extras.getMethod("scopedVariableSnapshot",String.class).invoke(null,"entity:"+p.runtimeId);Map<?,?> vars=(Map<?,?>)registryType.getMethod("variables").invoke(registry);
            Object service=runtime.getClass().getMethod("skillService").invoke(runtime);boolean tree=((Optional<?>)service.getClass().getMethod("snapshotOf",int.class).invoke(service,p.instanceId)).isPresent();
            Path store=runtime.getDataFolder().toPath().resolve("skill-trees");Location at=point(p.zone);
            getLogger().info("DCMD_ROW|scenario="+scenario+"|phase="+phase+"|key="+p.key+"|nativeEntity="+(n!=null)+"|runtimeEntity="+(r!=null)+"|nativeRegistry="+active+"|runtimeInstance="+(inst!=null)
                +"|variable="+vars.getOrDefault("Marker",null)+"|tree="+tree+"|uuidFile="+Files.exists(store.resolve("entity-"+p.runtimeId+".yml"))+"|instanceFile="+Files.exists(store.resolve(p.instanceId+".yml"))
                +"|nativeHooks="+hooks.getOrDefault("native:"+p.key,0)+"|runtimeHooks="+hooks.getOrDefault("runtime:"+p.key,0)+"|forced="+at.getWorld().isChunkForceLoaded(at.getBlockX()>>4,0)
                +"|nativeX="+(n==null?"absent":n.getLocation().getX())+"|runtimeX="+(r==null?"absent":r.getLocation().getX()));rows++;
        }
        long visuals=java.util.stream.Stream.of(world,cross).flatMap(w->w.getEntities().stream()).filter(e->e instanceof Display||e instanceof Interaction||e instanceof ArmorStand).count();
        getLogger().info("DCMD_VISUALS|scenario="+scenario+"|phase="+phase+"|count="+visuals);
    }
    private void finishWrite()throws Exception{
        for(int id:controls.values())instances().getClass().getMethod("despawn",int.class).invoke(instances(),id);
        controlCow.remove();
        var ids=new YamlConfiguration();ids.set("uuids",history.stream().map(UUID::toString).toList());ids.save(getDataFolder().toPath().resolve("identities.yml").toFile());
        extras.getMethod("saveScopedVariables",java.io.File.class).invoke(null,getDataFolder().toPath().resolve("scoped-after.yml").toFile());
        getLogger().info("DCMD_SUMMARY|stage=write|rows="+rows+"|completed=true");
    }
    private void verifyRestart()throws Exception{
        var ids=YamlConfiguration.loadConfiguration(getDataFolder().toPath().resolve("identities.yml").toFile());
        for(String raw:ids.getStringList("uuids")){UUID id=UUID.fromString(raw);Object registry=extras.getMethod("scopedVariableSnapshot",String.class).invoke(null,"entity:"+id);
            getLogger().info("DCMD_RESTART|uuid="+id+"|entity="+(Bukkit.getEntity(id)!=null)+"|instance="+(instance(id)!=null)+"|variables="+((Map<?,?>)registryType.getMethod("variables").invoke(registry)).size());rows++;}
        getLogger().info("DCMD_SUMMARY|stage=verify|rows="+rows+"|completed=true");
    }
    @Override public boolean onCommand(CommandSender sender,org.bukkit.command.Command cmd,String label,String[] args){
        if(args.length==5&&args[0].equals("hook")){
            String key=args[1]+":"+args[3];hooks.merge(key,1,Integer::sum);
            getLogger().info("DCMD_HOOK|scenario="+scenario+"|backend="+args[1]+"|event="+args[2]+"|key="+args[3]+"|marker="+args[4]);return true;
        }return false;
    }
    @EventHandler(priority=EventPriority.MONITOR)public void death(EntityDeathEvent e){for(Pair p:pairs)if(e.getEntity().getUniqueId().equals(p.nativeId)||e.getEntity().getUniqueId().equals(p.runtimeId))getLogger().severe("DCMD_UNEXPECTED_DEATH|key="+p.key);}
}
