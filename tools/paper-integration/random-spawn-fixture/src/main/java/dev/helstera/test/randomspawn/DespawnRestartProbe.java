package dev.helstera.test.randomspawn;

import org.bukkit.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import java.nio.file.*;
import java.util.*;

/** Three real process boots, including lazy NEVER reattachment and disposable controls. */
public final class DespawnRestartProbe extends JavaPlugin implements Listener {
    private static final List<String> MODES=List.of("NORMAL","CHUNK","NPC","NEVER","PERSISTENT");
    private record Pair(String key,String mode,String path,int index,UUID nativeId,UUID runtimeId) {
        boolean retained(){return mode.equals("NEVER")||mode.equals("PERSISTENT");}
        int chunkX(){return 300+index*4;}
    }
    private final List<Pair> pairs=new ArrayList<>();
    private final Map<String,VariablePlaceholderAudit.Harness> harnesses=new HashMap<>();
    private Plugin runtime,mythic;
    private World world;
    private String stage;
    private boolean failed;
    private int rows;
    @FunctionalInterface private interface Step{void run() throws Exception;}
    private void later(long ticks,Step step){Bukkit.getScheduler().runTaskLater(this,()->{
        if(failed)return;try{step.run();}catch(Throwable e){failed=true;getLogger().severe("DRESTART_FAILED|stage="+stage+"|error="+e);e.printStackTrace();}
    },ticks);}
    @Override public void onEnable(){
        stage=getConfig().getString("stage");
        runtime=Bukkit.getPluginManager().getPlugin("helsteraMobs");mythic=Bukkit.getPluginManager().getPlugin("MythicMobs");
        Bukkit.getPluginManager().registerEvents(this,this);later(100,this::begin);
    }
    private void begin() throws Exception{
        world=new WorldCreator("despawn-restart").generator(new ChunkGenerator(){
            @Override public ChunkData generateChunkData(World w,Random r,int x,int z,BiomeGrid b){return createChunkData(w);}
        }).generateStructures(false).createWorld();
        world.setKeepSpawnInMemory(false);world.setGameRule(GameRule.DO_MOB_SPAWNING,false);
        var ids=YamlConfiguration.loadConfiguration(getDataFolder().toPath().resolve("identities.yml").toFile());
        if(!stage.equals("write")){
            if(!world.getUID().toString().equals(ids.getString("world")))throw new IllegalStateException("World identity changed");
            int index=0;
            for(String mode:MODES)for(String path:List.of("direct","imported")){
                String key=mode.toLowerCase(Locale.ROOT)+"_"+path;
                pairs.add(new Pair(key,mode,path,index++,UUID.fromString(ids.getString(key+".native")),UUID.fromString(ids.getString(key+".runtime"))));
            }
        }
        // Helpers live in the ordinary worlds, outside every tested chunk.
        for(boolean imported:List.of(false,true)){
            Location center=Bukkit.getWorlds().getFirst().getSpawnLocation().clone().add(imported?40:0,20,40);
            Location base=center.clone().add(110,20,0),cross=base.clone();
            cross.setWorld(Bukkit.getWorlds().stream().filter(w->w!=base.getWorld()).findFirst().orElseThrow());
            for(Location at:List.of(base,base.clone().add(0,0,4),base.clone().add(4,0,0),cross)){
                at.getChunk().getEntities();at.getChunk().addPluginChunkTicket(this);
            }
            harnesses.put(imported?"imported":"direct",new VariablePlaceholderAudit.Harness(this,runtime,mythic,center,imported));
        }
        later(40,()->{
            if(stage.equals("write")){
                for(int index=0;index<10;index++){var chunk=world.getChunkAt(300+index*4,300);chunk.getEntities();chunk.addPluginChunkTicket(this);}
                later(40,this::spawn);
            }else{
                for(Pair p:pairs)if(p.mode.equals("NEVER"))getLogger().info("DRESTART_COLD|stage="+stage+"|key="+p.key
                        +"|loaded="+world.isChunkLoaded(p.chunkX(),300)+"|forced="+world.isChunkForceLoaded(p.chunkX(),300)
                        +"|nativeEntity="+(Bukkit.getEntity(p.nativeId)!=null)+"|runtimeEntity="+(Bukkit.getEntity(p.runtimeId)!=null)
                        +"|runtimeInstance="+(instance(p.runtimeId)!=null));
                for(Pair p:pairs){var chunk=world.getChunkAt(p.chunkX(),300);chunk.getEntities();chunk.addPluginChunkTicket(this);}
                later(40,this::restored);
            }
        });
    }
    private Location point(Pair p){return new Location(world,p.chunkX()*16+2.25,110,4802.75);}
    private void spawn() throws Exception{
        int index=0;var ids=new YamlConfiguration();ids.set("world",world.getUID().toString());
        for(String mode:MODES)for(String path:List.of("direct","imported")){
            String key=mode.toLowerCase(Locale.ROOT)+"_"+path,mobId="restart_"+key;
            var h=harnesses.get(path);var cfg=new YamlConfiguration();
            if(path.equals("imported")){
                var source=new YamlConfiguration();source.set(key+".Type","COW");source.set(key+".Options.Despawn",mode);
                source.set(key+".Options.NoAI",true);source.set(key+".Options.NoGravity",true);source.set(key+".Options.Invincible",true);
                source.set(key+".Skills",List.of("command{c=\"say DRESTART_HOOK runtime despawn "+key+"\"} @self ~onDespawn",
                        "command{c=\"say DRESTART_HOOK runtime death "+key+"\"} @self ~onDeath"));
                Path file=getDataFolder().toPath().resolve("Mobs/"+key+".yml");Files.createDirectories(file.getParent());source.save(file.toFile());
                @SuppressWarnings("unchecked") List<Map<String,Object>> plans=(List<Map<String,Object>>)h.importer.getClass().getMethod("convert",Path.class).invoke(h.importer,file);
                cfg.loadFromString(plans.stream().filter(v->String.valueOf(v.get("target-file")).startsWith("mobs/")).findFirst().orElseThrow().get("content").toString());
            }else{
                cfg.set("entity.type","COW");cfg.set("entity.no-ai",true);cfg.set("entity.gravity",false);cfg.set("entity.invulnerable",true);cfg.set("mob.despawn",mode);
                for(String event:List.of("despawn","death"))cfg.set("ai.triggers.on-"+event+".do",List.of("command \"say DRESTART_HOOK runtime "+event+" "+key+"\""));
            }
            cfg.set("model","example/emberling");cfg.set("mob.despawn-distance",0);
            cfg.save(runtime.getDataFolder().toPath().resolve("mobs/"+mobId+".yml").toFile());
            var stub=new Pair(key,mode,path,index,null,null);Location at=point(stub);
            Object api=mythic.getClass().getMethod("getAPIHelper").invoke(mythic);
            var n=(LivingEntity)api.getClass().getMethod("spawnMythicMob",String.class,Location.class).invoke(api,key,at);
            int id=((Number)runtime.getClass().getMethod("spawnMobAt",String.class,Location.class,String.class).invoke(runtime,mobId,at.clone().add(1,0,0),null)).intValue();
            Object inst=instances().getClass().getMethod("impl",int.class).invoke(instances(),id);
            var r=(LivingEntity)((Optional<?>)inst.getClass().getMethod("baseEntity").invoke(inst)).orElseThrow();
            var p=new Pair(key,mode,path,index++,n.getUniqueId(),r.getUniqueId());pairs.add(p);
            ids.set(key+".native",p.nativeId.toString());ids.set(key+".runtime",p.runtimeId.toString());
            getLogger().info("DRESTART_ID|key="+key+"|mode="+mode+"|path="+path+"|native="+p.nativeId+"|runtime="+p.runtimeId+"|world="+world.getUID()+"|chunk="+p.chunkX()+",300");
            getLogger().info("DRESTART_MAPPING|key="+key+"|mode="+cfg.getString("mob.despawn")+"|trigger="+(cfg.isList("ai.triggers.on-despawn.do")||cfg.isList("mob.triggers.on-despawn.do")));
            execute(p,"setvariable{var=caster.Saved;type=INTEGER;value="+(7+p.index)+";save=true}",false);
            execute(p,"setvariable{var=caster.Transient;type=INTEGER;value=99;save=false}",false);
            execute(p,"setvariablelocation{var=caster.Point;value=@origin;save=true}",true);
            Object tree=h.newTree(r,r,point(p));h.treeType.getMethod("setVariable",String.class,Object.class).invoke(tree,"RestartTree",23+p.index);
            Object snapshot=h.treeType.getMethod("snapshot").invoke(tree),service=runtime.getClass().getMethod("skillService").invoke(runtime);
            service.getClass().getMethod("restoreSkillTreeSnapshot",int.class,snapshot.getClass()).invoke(service,id,snapshot);
        }
        ids.save(getDataFolder().toPath().resolve("identities.yml").toFile());
        later(20,()->{observe("write");finish();});
    }
    private void restored() throws Exception{
        observe(stage);
        if(stage.equals("verify")){
            for(Pair p:pairs)if(p.retained()){
                if(p.path.equals("direct"))kill(p);
                else{
                    execute(p,"unsetvariable{var=caster.Saved}",false);execute(p,"unsetvariable{var=caster.Point}",false);
                    execute(p,"setvariable{var=caster.Updated;value="+(31+p.index)+";save=true}",false);
                    Object inst=instance(p.runtimeId),service=runtime.getClass().getMethod("skillService").invoke(runtime);
                    service.getClass().getMethod("clearSkillTree",int.class).invoke(service,((Number)inst.getClass().getMethod("instanceId").invoke(inst)).intValue());
                }
            }
            later(70,()->{observe("changed");finish();});
        }else{
            for(Pair p:pairs)if(p.retained()&&p.path.equals("imported"))kill(p);
            later(70,()->{observe("dead");finish();});
        }
    }
    private void kill(Pair p){for(UUID id:List.of(p.nativeId,p.runtimeId))if(Bukkit.getEntity(id) instanceof LivingEntity e){e.setInvulnerable(false);e.setHealth(0);}}
    private Object instances() throws Exception{return runtime.getClass().getMethod("instances").invoke(runtime);}
    private Object instance(UUID id) throws Exception{
        for(Object i:(Collection<?>)instances().getClass().getMethod("allImpl").invoke(instances()))
            if(((Optional<?>)i.getClass().getMethod("boundEntityId").invoke(i)).filter(id::equals).isPresent())return i;
        return null;
    }
    private void execute(Pair p,String line,boolean location) throws Exception{
        var h=harnesses.get(p.path);var n=(LivingEntity)Objects.requireNonNull(Bukkit.getEntity(p.nativeId));var r=(LivingEntity)Objects.requireNonNull(Bukkit.getEntity(p.runtimeId));
        h.active.put(n,h.manager.getClass().getMethod("getMythicMobInstance",Entity.class).invoke(h.manager,n));
        Object meta=h.meta(n,n),origin=h.nativeType("io.lumine.mythic.bukkit.BukkitAdapter").getMethod("adapt",Location.class).invoke(null,r.getLocation());
        h.metadataType.getMethod("setOrigin",h.nativeType("io.lumine.mythic.api.adapters.AbstractLocation")).invoke(meta,origin);
        h.metadataType.getMethod("setEntityTarget",h.abstractEntity).invoke(meta,h.adapt.invoke(null,n));
        Object ctx=h.contextType.getMethod("continueExecution",h.modelType,LivingEntity.class,double.class,double.class,long.class,String.class,String.class,Entity.class,List.class,Location.class,h.treeType)
                .invoke(null,instance(p.runtimeId),r,1d,0d,0L,"restart",null,r,List.of(r),point(p),h.newTree(r,r,point(p)));
        h.execute(line,location?"VariableSetLocationMechanic":line.startsWith("unset")?"VariableUnsetMechanic":"VariableSetMechanic",meta,ctx,n);
    }
    private String nativeValue(Object registry,String name) throws Exception{
        if(registry==null)return "absent";Object v=registry.getClass().getMethod("get",String.class).invoke(registry,name);
        if(v==null)return "absent";return render(v.getClass().getMethod("get").invoke(v));
    }
    private String render(Object v) throws Exception{
        if(v==null)return "absent";
        if(v instanceof Number)return v.toString();
        if(v.getClass().getSimpleName().equals("LocationSnapshot"))return v.getClass().getMethod("world").invoke(v)+","+v.getClass().getMethod("x").invoke(v)+","+v.getClass().getMethod("y").invoke(v)+","+v.getClass().getMethod("z").invoke(v);
        Object w=v.getClass().getMethod("getWorld").invoke(v);
        return w.getClass().getMethod("getName").invoke(w)+","+v.getClass().getMethod("getX").invoke(v)+","+v.getClass().getMethod("getY").invoke(v)+","+v.getClass().getMethod("getZ").invoke(v);
    }
    private void observe(String phase) throws Exception{
        for(Pair p:pairs){
            var h=harnesses.get(p.path);Object nativeActive=((Optional<?>)h.manager.getClass().getMethod("getActiveMob",UUID.class).invoke(h.manager,p.nativeId)).orElse(null);
            Object nr=nativeActive==null?null:nativeActive.getClass().getMethod("getVariables").invoke(nativeActive);
            Object snapshot=h.runtimeType("dev.helstera.ai.skill.SkillExtras").getMethod("scopedVariableSnapshot",String.class).invoke(null,"entity:"+p.runtimeId);
            Map<?,?> rv=(Map<?,?>)snapshot.getClass().getMethod("variables").invoke(snapshot),rl=(Map<?,?>)snapshot.getClass().getMethod("locations").invoke(snapshot);
            Object inst=instance(p.runtimeId),service=runtime.getClass().getMethod("skillService").invoke(runtime);
            Optional<?> tree=inst==null?Optional.empty():(Optional<?>)service.getClass().getMethod("snapshotOf",int.class).invoke(service,((Number)inst.getClass().getMethod("instanceId").invoke(inst)).intValue());
            Object tv=tree.isEmpty()?null:((Map<?,?>)tree.get().getClass().getMethod("variables").invoke(tree.get())).get("RestartTree");
            long visuals=Arrays.stream(world.getChunkAt(p.chunkX(),300).getEntities()).filter(e->e instanceof Display||e instanceof Interaction||e instanceof ArmorStand).count();
            getLogger().info("DRESTART_ROW|phase="+phase+"|key="+p.key+"|nativeEntity="+(Bukkit.getEntity(p.nativeId)!=null)+"|runtimeEntity="+(Bukkit.getEntity(p.runtimeId)!=null)
                    +"|nativeRegistry="+(nativeActive!=null)+"|runtimeInstance="+(inst!=null)+"|nativeSaved="+nativeValue(nr,"Saved")+"|runtimeSaved="+render(rv.get("Saved"))
                    +"|nativeTransient="+nativeValue(nr,"Transient")+"|runtimeTransient="+render(rv.get("Transient"))+"|nativeUpdated="+nativeValue(nr,"Updated")+"|runtimeUpdated="+render(rv.get("Updated"))
                    +"|nativePoint="+nativeValue(nr,"Point")+"|runtimePoint="+render(rl.get("Point"))+"|tree="+(tv==null?"absent":tv)+"|visuals="+visuals
                    +"|forced="+world.isChunkForceLoaded(p.chunkX(),300));rows++;
        }
    }
    private void finish() throws Exception{
        for(var h:harnesses.values())h.close();
        getLogger().info("DRESTART_SUMMARY|stage="+stage+"|rows="+rows+"|completed=true");
    }
    @EventHandler(priority=EventPriority.MONITOR) public void loaded(EntitiesLoadEvent e){for(Entity entity:e.getEntities())event(entity,"load");}
    @EventHandler(priority=EventPriority.MONITOR) public void died(EntityDeathEvent e){event(e.getEntity(),"death");}
    private void event(Entity entity,String event){for(Pair p:pairs)for(String backend:List.of("native","runtime"))if(entity.getUniqueId().equals(backend.equals("native")?p.nativeId:p.runtimeId))
        getLogger().info("DRESTART_EVENT|stage="+stage+"|key="+p.key+"|backend="+backend+"|event="+event+"|uuid="+entity.getUniqueId());}
}
