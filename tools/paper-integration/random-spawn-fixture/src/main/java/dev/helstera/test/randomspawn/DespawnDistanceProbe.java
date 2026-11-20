package dev.helstera.test.randomspawn;

import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import java.nio.file.*;
import java.util.*;

/** Real player positions with ticketed chunks isolate native distance removal from unload. */
public final class DespawnDistanceProbe extends JavaPlugin implements Listener {
    private static final List<String> MODES=List.of("DEFAULT","NORMAL","CHUNK","NPC","NEVER","PERSISTENT","SPAWNING_DISTANCE");
    private static final List<String> TYPES=List.of("COW","ZOMBIE","VILLAGER","SLIME");
    private record Pair(String key,String mode,String type,String path,LivingEntity nativeEntity,LivingEntity runtimeEntity,int instance) { }
    private final List<Pair> pairs=new ArrayList<>();
    private final Map<String,Integer> hooks=new HashMap<>();
    private Plugin runtime,mythic;
    private Object manager,importer;
    private World world;
    private Player player;
    private String cohort,phase="prepare";
    private boolean failed;
    private int rows,phaseTick;
    @FunctionalInterface private interface Step {void run() throws Exception;}
    private void later(long ticks,Step step){Bukkit.getScheduler().runTaskLater(this,()->{if(failed)return;try{step.run();}catch(Throwable e){failed=true;getLogger().severe("DDIST_FAILED|error="+e);e.printStackTrace();}},ticks);}
    @Override public void onEnable(){
        runtime=Bukkit.getPluginManager().getPlugin("helsteraMobs");mythic=Bukkit.getPluginManager().getPlugin("MythicMobs");
        Bukkit.getPluginManager().registerEvents(this,this);Objects.requireNonNull(getCommand("distanceprobe")).setExecutor(this);
        later(20,this::awaitPlayer);
    }
    private void awaitPlayer() throws Exception{
        player=Bukkit.getPlayerExact("DespawnWalker");if(player==null){later(20,this::awaitPlayer);return;}
        manager=mythic.getClass().getMethod("getMobManager").invoke(mythic);
        importer=Class.forName("dev.helstera.migration.importer.MythicMobsImporter",true,runtime.getClass().getClassLoader()).getConstructor().newInstance();
        world=new WorldCreator("despawn-distance").generator(new ChunkGenerator(){
            @Override public ChunkData generateChunkData(World w,Random r,int x,int z,BiomeGrid b){ChunkData data=createChunkData(w);data.setRegion(0,109,0,16,110,16,Material.STONE);return data;}
        }).generateStructures(false).createWorld();
        world.setKeepSpawnInMemory(false);world.setGameRule(GameRule.DO_MOB_SPAWNING,false);world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE,false);world.setTime(18000);
        world.setDifficulty(Difficulty.NORMAL);
        for(int x=255;x<=269;x++)for(int z=255;z<=257;z++){var c=world.getChunkAt(x,z);c.getEntities();c.addPluginChunkTicket(this);}
        player.setGameMode(GameMode.CREATIVE);player.setAllowFlight(true);player.setFlying(true);
        checkPlayerBindings();cohort="player";move("near",16);
    }
    private void checkPlayerBindings() throws Exception {
        boolean saved=player.isPersistent();
        Class<?> optionsType=Class.forName("dev.helstera.api.instance.SpawnOptions",true,runtime.getClass().getClassLoader());
        try {
            for(String mode:List.of("NORMAL","CHUNK","NPC","NEVER","PERSISTENT"))for(boolean initial:List.of(false,true)) {
                player.setPersistent(initial);boolean remove=player.getRemoveWhenFarAway();
                Object options=optionsType.getMethod("defaults").invoke(null);
                optionsType.getMethod("despawnMode",String.class).invoke(options,mode);
                Object instance=instances().getClass().getMethod("bind",String.class,Entity.class,optionsType)
                    .invoke(instances(),"example/emberling",player,options);
                int id=((Number)instance.getClass().getMethod("instanceId").invoke(instance)).intValue();
                boolean after=player.isPersistent(),afterRemove=player.getRemoveWhenFarAway();
                instances().getClass().getMethod("despawn",int.class).invoke(instances(),id);
                getLogger().info("DDIST_PLAYER_BIND|mode="+mode+"|beforePersistent="+initial+"|afterPersistent="+after
                    +"|beforeRemove="+remove+"|afterRemove="+afterRemove+"|uuid="+player.getUniqueId()
                    +"|entityValid="+player.isValid()+"|instanceGone="+(instances().getClass().getMethod("impl",int.class).invoke(instances(),id)==null));
            }
        } finally {player.setPersistent(saved);}
    }
    private Location center(){return new Location(world,4096.5,110,4096.5);}
    private void move(String next,double distance){
        phase=next;phaseTick=Bukkit.getCurrentTick();player.teleport(center().add(distance,0,0));
        player.sendMessage("DDIST_CONTROL "+phase);
    }
    @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] args){
        if(sender instanceof ConsoleCommandSender&&args.length==4&&args[0].equals("hook")){
            String k=args[1]+":"+args[2]+":"+args[3];int n=hooks.merge(k,1,Integer::sum);
            getLogger().info("DDIST_HOOK|phase="+phase+"|backend="+args[1]+"|event="+args[2]+"|key="+args[3]+"|count="+n+"|tick="+Bukkit.getCurrentTick());return true;
        }
        if(sender==player&&args.length==2&&args[0].equals("ack")&&args[1].equals(phase)){
            getLogger().info("DDIST_ACK|phase="+phase+"|uuid="+player.getUniqueId()+"|mode="+player.getGameMode()+"|world="+player.getWorld().getName()+"|x="+player.getX()+"|y="+player.getY()+"|z="+player.getZ()+"|tick="+Bukkit.getCurrentTick());
            later(1,()->{
                if(phase.equals("near")||phase.equals("spectator")){spawn();observe(phase+"-initial");}
                later(80,this::advance);
            });return true;
        }
        return false;
    }
    private void spawn() throws Exception{
        phaseTick=Bukkit.getCurrentTick();int index=0;
        for(String type:TYPES)for(String mode:MODES)for(String path:List.of("direct","imported")){
            String key=cohort+"_"+type.toLowerCase(Locale.ROOT)+"_"+mode.toLowerCase(Locale.ROOT)+"_"+path;
            var source=new YamlConfiguration();source.set(key+".Type",type);
            if(!mode.equals("DEFAULT")&&!mode.equals("SPAWNING_DISTANCE"))source.set(key+".Options.Despawn",mode);
            if(mode.equals("SPAWNING_DISTANCE"))source.set(key+".Options.SpawningDistance",8);
            source.set(key+".Options.Collidable",false);source.set(key+".Options.NoAI",true);source.set(key+".Options.NoGravity",true);source.set(key+".Options.Invincible",true);source.set(key+".Options.PreventSlimeSplit",true);
            source.set(key+".Skills",List.of("command{c=\"distanceprobe hook runtime despawn "+key+"\"} @self ~onDespawn","command{c=\"distanceprobe hook runtime death "+key+"\"} @self ~onDeath"));
            var cfg=new YamlConfiguration();
            if(path.equals("imported")){
                Path file=getDataFolder().toPath().resolve("Mobs/"+key+".yml");Files.createDirectories(file.getParent());source.save(file.toFile());
                @SuppressWarnings("unchecked") List<Map<String,Object>> plans=(List<Map<String,Object>>)importer.getClass().getMethod("convert",Path.class).invoke(importer,file);
                cfg.loadFromString(plans.stream().filter(v->String.valueOf(v.get("target-file")).startsWith("mobs/")).findFirst().orElseThrow().get("content").toString());
            }else{
                cfg.set("entity.collidable",false);cfg.set("entity.type",type);cfg.set("entity.no-ai",true);cfg.set("entity.gravity",false);cfg.set("entity.invulnerable",true);cfg.set("mob.prevent-slime-split",true);
                if(!mode.equals("DEFAULT")&&!mode.equals("SPAWNING_DISTANCE"))cfg.set("mob.despawn",mode);
                if(mode.equals("SPAWNING_DISTANCE"))cfg.set("mob.spawning-distance",8);
                for(String event:List.of("despawn","death"))cfg.set("ai.triggers.on-"+event+".do",List.of("command \"distanceprobe hook runtime "+event+" "+key+"\""));
            }
            cfg.set("model","example/emberling");String mobId="distance_"+key;
            cfg.save(runtime.getDataFolder().toPath().resolve("mobs/"+mobId+".yml").toFile());
            Location at=center().add(0,0,(index++%8)*.25);
            Object api=mythic.getClass().getMethod("getAPIHelper").invoke(mythic);
            var n=(LivingEntity)api.getClass().getMethod("spawnMythicMob",String.class,Location.class).invoke(api,key,at);
            int id=((Number)runtime.getClass().getMethod("spawnMobAt",String.class,Location.class,String.class).invoke(runtime,mobId,at.clone(),null)).intValue();
            Object inst=instances().getClass().getMethod("impl",int.class).invoke(instances(),id);
            var r=(LivingEntity)((Optional<?>)inst.getClass().getMethod("baseEntity").invoke(inst)).orElseThrow();
            pairs.add(new Pair(key,mode,type,path,n,r,id));
            Object active=manager.getClass().getMethod("getMythicMobInstance",Entity.class).invoke(manager,n);
            getLogger().info("DDIST_ID|key="+key+"|mode="+mode+"|type="+type+"|path="+path+"|native="+n.getUniqueId()+"|runtime="+r.getUniqueId()+"|nativeMode="+active.getClass().getMethod("getDespawnMode").invoke(active)+"|runtimeMode="+cfg.getString("mob.despawn","NORMAL")+"|runtimeRadius="+cfg.getDouble("mob.spawning-distance",0)+"|unsupportedDistance="+cfg.getInt("mob.unsupported.Options.SpawningDistance",0));
        }
    }
    private Object instances() throws Exception{return runtime.getClass().getMethod("instances").invoke(runtime);}
    private void observe(String stage) throws Exception{
        for(Pair p:pairs){
            boolean n=p.nativeEntity.isValid(),r=p.runtimeEntity.isValid();
            Object active=manager.getClass().getMethod("getActiveMob",UUID.class).invoke(manager,p.nativeEntity.getUniqueId());
            Object instance=instances().getClass().getMethod("impl",int.class).invoke(instances(),p.instance);
            Object ai=runtime.getClass().getMethod("ai").invoke(runtime);
            boolean controller=ai.getClass().getMethod("controllerById",int.class).invoke(ai,p.instance)!=null;
            getLogger().info("DDIST_ROW|cohort="+cohort+"|phase="+stage+"|key="+p.key+"|elapsed="+(Bukkit.getCurrentTick()-phaseTick)
                +"|nativeEntity="+n+"|runtimeEntity="+r+"|nativeRegistry="+((Optional<?>)active).isPresent()+"|runtimeInstance="+(instance!=null)
                +"|runtimeController="+controller+"|nativeRemove="+p.nativeEntity.getRemoveWhenFarAway()+"|runtimeRemove="+p.runtimeEntity.getRemoveWhenFarAway()
                +"|nativePersistent="+p.nativeEntity.isPersistent()+"|runtimePersistent="+p.runtimeEntity.isPersistent()
                +"|nativeDespawn="+hooks.getOrDefault("native:despawn:"+p.key,0)+"|runtimeDespawn="+hooks.getOrDefault("runtime:despawn:"+p.key,0)
                +"|nativeDeath="+hooks.getOrDefault("native:death:"+p.key,0)+"|runtimeDeath="+hooks.getOrDefault("runtime:death:"+p.key,0)
                +position("native",p.nativeEntity)+position("runtime",p.runtimeEntity)+position("player",player)+"|playerMode="+player.getGameMode()+"|players="+world.getPlayers().size()+"|chunkLoaded="+world.isChunkLoaded(256,256)+"|tick="+Bukkit.getCurrentTick());rows++;
        }
    }
    private String position(String prefix,Entity entity){
        Location at=entity.getLocation();
        return "|"+prefix+"World="+at.getWorld().getName()+"|"+prefix+"X="+at.getX()+"|"+prefix+"Y="+at.getY()+"|"+prefix+"Z="+at.getZ()
            +"|"+prefix+"Distance="+(entity.getWorld()==player.getWorld()?at.distance(player.getLocation()):-1);
    }
    private void advance() throws Exception{
        observe(phase);
        switch(phase){
            case "near"->move("middle",64);
            case "middle"->move("far",192);
            case "far"->{cleanup();phase="empty";cohort="empty";player.teleport(Bukkit.getWorlds().getFirst().getSpawnLocation());later(20,()->{spawn();observe("empty-initial");later(80,this::advance);});}
            case "empty"->{cleanup();cohort="spectator";player.setGameMode(GameMode.SPECTATOR);move("spectator",192);}
            case "spectator"->{cleanup();getLogger().info("DDIST_SUMMARY|rows="+rows+"|completed=true");}
            default->throw new IllegalStateException(phase);
        }
    }
    private void cleanup() throws Exception{
        phase="cleanup";
        for(Pair p:pairs){
            var active=(Optional<?>)manager.getClass().getMethod("getActiveMob",UUID.class).invoke(manager,p.nativeEntity.getUniqueId());
            if(active.isPresent())active.get().getClass().getMethod("remove").invoke(active.get());
            instances().getClass().getMethod("despawn",int.class).invoke(instances(),p.instance);
            if(p.runtimeEntity.isValid())p.runtimeEntity.remove();
        }
        pairs.clear();
    }
    @EventHandler(priority=EventPriority.MONITOR) public void removed(EntityRemoveEvent e){event(e.getEntity(),"remove",e.getCause().name());}
    @EventHandler(priority=EventPriority.MONITOR) public void died(EntityDeathEvent e){event(e.getEntity(),"death","death");}
    private void event(Entity e,String kind,String cause){for(Pair p:pairs)for(String b:List.of("native","runtime"))if(e.getUniqueId().equals((b.equals("native")?p.nativeEntity:p.runtimeEntity).getUniqueId()))getLogger().info("DDIST_EVENT|phase="+phase+"|key="+p.key+"|backend="+b+"|event="+kind+"|cause="+cause+"|uuid="+e.getUniqueId()+position("entity",e)+"|tick="+Bukkit.getCurrentTick());}
}
