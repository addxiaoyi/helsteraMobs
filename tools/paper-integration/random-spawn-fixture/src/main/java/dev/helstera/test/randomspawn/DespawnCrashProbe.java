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

/** Isolate loaded/unloaded/resumed save boundaries before two externally forced process crashes. */
public final class DespawnCrashProbe extends JavaPlugin implements Listener {
    private static final List<String> MODES=List.of("NORMAL","CHUNK","NPC","NEVER","PERSISTENT");
    private record Pair(String group,String key,String mode,String path,int index,UUID nativeId,UUID runtimeId) {
        boolean retained(){return mode.equals("NEVER")||mode.equals("PERSISTENT");}
        int chunkX(){return 300+index*4;}
    }
    private final List<Pair> pairs=new ArrayList<>();
    private final Map<String,VariablePlaceholderAudit.Harness> harnesses=new HashMap<>();
    private Plugin runtime,mythic;
    private World world;
    private String group;
    private int groupIndex;
    private final Map<String,World> worlds=new LinkedHashMap<>();
    private static final List<String> GROUPS=List.of("active","unloaded","resumed");
    private final YamlConfiguration identities=new YamlConfiguration();
    private String stage;
    private boolean chunkBoundary;
    private int clockWrites;
    private boolean failed;
    private int rows;
    @FunctionalInterface private interface Step{void run() throws Exception;}
    private void later(long ticks,Step step){Bukkit.getScheduler().runTaskLater(this,()->{
        if(failed)return;try{step.run();}catch(Throwable e){failed=true;getLogger().severe("DCRASH_FAILED|stage="+stage+"|error="+e);e.printStackTrace();}
    },ticks);}
    @Override public void onEnable(){
        stage=getConfig().getString("stage");
        chunkBoundary=getConfig().getString("boundary","world").equals("chunk");
        runtime=Bukkit.getPluginManager().getPlugin("helsteraMobs");mythic=Bukkit.getPluginManager().getPlugin("MythicMobs");
        Bukkit.getPluginManager().registerEvents(this,this);later(100,this::begin);
    }
    @Override public void onDisable(){getLogger().info("DCRASH_DISABLE|stage="+stage);}
    private World makeWorld(String name){
        World w=new WorldCreator("despawn-crash-"+name).generator(new ChunkGenerator(){
            @Override public ChunkData generateChunkData(World w,Random r,int x,int z,BiomeGrid b){return createChunkData(w);}
        }).generateStructures(false).createWorld();
        w.setKeepSpawnInMemory(false);w.setGameRule(GameRule.DO_MOB_SPAWNING,false);
        worlds.put(name,w);return w;
    }
    private void begin() throws Exception{
        if(!stage.equals("write"))identities.load(getDataFolder().toPath().resolve("identities.yml").toFile());
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
        later(40,this::nextGroup);
    }
    private void nextGroup() throws Exception{
        if(groupIndex==GROUPS.size()){
            if(stage.equals("write"))identities.save(getDataFolder().toPath().resolve("identities.yml").toFile());
            getLogger().info("DCRASH_READY|stage="+stage+"|rows="+rows);return;
        }
        group=GROUPS.get(groupIndex++);world=makeWorld(group);
        if(stage.equals("write"))identities.set(group+".world",world.getUID().toString());
        else {
            if(!world.getUID().toString().equals(identities.getString(group+".world")))throw new IllegalStateException("Changed world");
            int index=0;
            for(String mode:MODES)for(String path:List.of("direct","imported")){
                String key=group+"_"+mode.toLowerCase(Locale.ROOT)+"_"+path;
                pairs.add(new Pair(group,key,mode,path,index++,UUID.fromString(identities.getString(group+".mobs."+key+".native")),UUID.fromString(identities.getString(group+".mobs."+key+".runtime"))));
            }
            for(Pair p:pairs)if(p.group.equals(group)&&p.mode.equals("NEVER"))getLogger().info("DCRASH_COLD|stage="+stage+"|key="+p.key+"|loaded="+world.isChunkLoaded(p.chunkX(),300));
        }
        for(int index=0;index<10;index++){var c=world.getChunkAt(300+index*4,300);c.getEntities();c.addPluginChunkTicket(this);}
        later(40,()->{if(stage.equals("write"))spawn();else {observe(stage);nextGroup();}});
    }
    private Location point(Pair p){return new Location(world,p.chunkX()*16+2.25,110,4802.75);}
    private void spawn() throws Exception{
        int index=0;
        for(String mode:MODES)for(String path:List.of("direct","imported")){
            String key=group+"_"+mode.toLowerCase(Locale.ROOT)+"_"+path,mobId="crash_"+key;
            var h=harnesses.get(path);var cfg=new YamlConfiguration();
            if(path.equals("imported")){
                var source=new YamlConfiguration();source.set(key+".Type","COW");source.set(key+".Options.Despawn",mode);
                source.set(key+".Options.NoAI",true);source.set(key+".Options.NoGravity",true);source.set(key+".Options.Invincible",true);
                source.set(key+".Skills",List.of("command{c=\"say DCRASH_HOOK runtime despawn "+key+"\"} @self ~onDespawn",
                        "command{c=\"say DCRASH_HOOK runtime death "+key+"\"} @self ~onDeath"));
                Path file=getDataFolder().toPath().resolve("Mobs/"+key+".yml");Files.createDirectories(file.getParent());source.save(file.toFile());
                @SuppressWarnings("unchecked") List<Map<String,Object>> plans=(List<Map<String,Object>>)h.importer.getClass().getMethod("convert",Path.class).invoke(h.importer,file);
                cfg.loadFromString(plans.stream().filter(v->String.valueOf(v.get("target-file")).startsWith("mobs/")).findFirst().orElseThrow().get("content").toString());
            }else{
                cfg.set("entity.type","COW");cfg.set("entity.no-ai",true);cfg.set("entity.gravity",false);cfg.set("entity.invulnerable",true);cfg.set("mob.despawn",mode);
                for(String event:List.of("despawn","death"))cfg.set("ai.triggers.on-"+event+".do",List.of("command \"say DCRASH_HOOK runtime "+event+" "+key+"\""));
            }
            cfg.set("model","example/emberling");cfg.set("mob.despawn-distance",0);
            cfg.save(runtime.getDataFolder().toPath().resolve("mobs/"+mobId+".yml").toFile());
            var stub=new Pair(group,key,mode,path,index,null,null);Location at=point(stub);
            Object api=mythic.getClass().getMethod("getAPIHelper").invoke(mythic);
            var n=(LivingEntity)api.getClass().getMethod("spawnMythicMob",String.class,Location.class).invoke(api,key,at);
            int id=((Number)runtime.getClass().getMethod("spawnMobAt",String.class,Location.class,String.class).invoke(runtime,mobId,at.clone().add(1,0,0),null)).intValue();
            Object inst=instances().getClass().getMethod("impl",int.class).invoke(instances(),id);
            var r=(LivingEntity)((Optional<?>)inst.getClass().getMethod("baseEntity").invoke(inst)).orElseThrow();
            var p=new Pair(group,key,mode,path,index++,n.getUniqueId(),r.getUniqueId());pairs.add(p);
            identities.set(group+".mobs."+key+".native",p.nativeId.toString());identities.set(group+".mobs."+key+".runtime",p.runtimeId.toString());
            getLogger().info("DCRASH_ID|key="+key+"|mode="+mode+"|path="+path+"|native="+p.nativeId+"|runtime="+p.runtimeId+"|world="+world.getUID()+"|chunk="+p.chunkX()+",300");
            getLogger().info("DCRASH_MAPPING|key="+key+"|mode="+cfg.getString("mob.despawn")+"|trigger="+(cfg.isList("ai.triggers.on-despawn.do")||cfg.isList("mob.triggers.on-despawn.do")));
            execute(p,"setvariable{var=caster.Saved;type=INTEGER;value="+(7+p.index)+";save=true}",false);
            execute(p,"setvariable{var=caster.Transient;type=INTEGER;value=99;save=false}",false);
            execute(p,"setvariablelocation{var=caster.Point;value=@origin;save=true}",true);
            Object tree=h.newTree(r,r,point(p));h.treeType.getMethod("setVariable",String.class,Object.class).invoke(tree,"CrashTree",23+p.index);
            Object snapshot=h.treeType.getMethod("snapshot").invoke(tree),service=runtime.getClass().getMethod("skillService").invoke(runtime);
            service.getClass().getMethod("restoreSkillTreeSnapshot",int.class,snapshot.getClass()).invoke(service,id,snapshot);
        }
        later(20,()->{
            observe("seeded");
            if(group.equals("active")){nextGroup();return;}
            unloadCurrent();
            awaitUnloaded(List.of(group),0,()->{
                observe("unloaded");
                if(group.equals("unloaded")){nextGroup();return;}
                world=makeWorld(group);for(int i=0;i<10;i++){var c=world.getChunkAt(300+i*4,300);c.getEntities();c.addPluginChunkTicket(this);}
                later(40,()->{
                    observe("resumed");
                    for(Pair p:pairs)if(p.group.equals(group)&&p.retained()){
                        execute(p,"setvariable{var=caster.Saved;type=INTEGER;value="+(107+p.index)+";save=true}",false);
                        Object inst=instance(p.runtimeId),hTree=harnesses.get(p.path).newTree(Bukkit.getEntity(p.runtimeId),(LivingEntity)Bukkit.getEntity(p.runtimeId),point(p));
                        var h=harnesses.get(p.path);h.treeType.getMethod("setVariable",String.class,Object.class).invoke(hTree,"CrashTree",123+p.index);
                        Object service=runtime.getClass().getMethod("skillService").invoke(runtime);service.getClass().getMethod("restoreSkillTreeSnapshot",int.class,h.treeType.getMethod("snapshot").getReturnType()).invoke(service,((Number)inst.getClass().getMethod("instanceId").invoke(inst)).intValue(),h.treeType.getMethod("snapshot").invoke(hTree));
                    }
                    observe("changed");nextGroup();
                });
            });
        });
    }
    private void unloadCurrent(){
        if(chunkBoundary){
            for(int index=0;index<10;index++){
                int x=300+index*4;
                boolean forced=world.isChunkForceLoaded(x,300);
                world.setChunkForceLoaded(x,300,false);
                world.getChunkAt(x,300).removePluginChunkTicket(this);
                boolean accepted=world.unloadChunkRequest(x,300);
                getLogger().info("DCRASH_CHUNK_REQUEST|stage="+stage+"|group="+group+"|x="+x+"|z=300|accepted="+accepted+"|wasForced="+forced+"|tick="+Bukkit.getCurrentTick());
                if(!accepted)throw new IllegalStateException("Chunk unload rejected");
            }
            return;
        }
        world.removePluginChunkTickets(this);boolean ok=Bukkit.unloadWorld(world,true);
        getLogger().info("DCRASH_WORLD_UNLOAD|stage="+stage+"|group="+group+"|accepted="+ok+"|tick="+Bukkit.getCurrentTick());
        if(!ok)throw new IllegalStateException("World unload rejected");
    }
    private void awaitUnloaded(List<String> groups,int waited,Step then){
        later(1,()->{
            if(chunkBoundary){
                boolean unloaded=true;
                for(String g:groups){
                    World w=worlds.get(g);
                    if(Bukkit.getWorld(w.getUID())!=w)throw new IllegalStateException("World unloaded during chunk test");
                    for(int i=0;i<10;i++)unloaded&=!w.isChunkLoaded(300+i*4,300);
                }
                if(!unloaded){
                    if(waited>=200)throw new IllegalStateException("Chunk unload timeout");
                    awaitUnloaded(groups,waited+1,then);return;
                }
                for(String g:groups)getLogger().info("DCRASH_CHUNKS_ABSENT|stage="+stage+"|group="+g+"|worldPresent=true|chunks=10|tick="+Bukkit.getCurrentTick());
            }
            // Let the native deferred unregister complete before comparing registries.
            later(40,then);
        });
    }
    @Override public boolean onCommand(org.bukkit.command.CommandSender sender,org.bukkit.command.Command command,String label,String[] args){
        if(!(sender instanceof org.bukkit.command.ConsoleCommandSender)||args.length!=1)return false;
        try{
            if(args[0].equals("clock")){
                int value=(stage.equals("write")?100:stage.equals("recover")?200:300)+ ++clockWrites;
                Object manager=mythic.getClass().getMethod("getVariableManager").invoke(mythic);
                Object variables=manager.getClass().getMethod("getGlobalRegistry").invoke(manager);
                var h=harnesses.get("direct");Class<?> type=h.nativeType("io.lumine.mythic.core.skills.variables.VariableType");
                Class<?> variable=h.nativeType("io.lumine.mythic.core.skills.variables.Variable");
                Object integer=Arrays.stream(type.getEnumConstants()).filter(t->t.toString().equals("INTEGER")).findFirst().orElseThrow();
                Object marker=variable.getMethod("ofType",type,Object.class,boolean.class).invoke(null,integer,value,true);
                variables.getClass().getMethod("put",String.class,variable).invoke(variables,"CrashClockControl",marker);
                getLogger().info("DCRASH_CLOCK_SET|stage="+stage+"|sequence="+clockWrites+"|value="+value+"|tick="+Bukkit.getCurrentTick());return true;
            }
            if(args[0].equals("flush")){
                boolean accepted=Bukkit.dispatchCommand(Bukkit.getConsoleSender(),"save-all flush");
                getLogger().info("DCRASH_FLUSH|stage="+stage+"|accepted="+accepted+"|tick="+Bukkit.getCurrentTick());
                later(5,()->getLogger().info("DCRASH_CHECKPOINT|stage="+stage+"|rows="+rows));return true;
            }
            if(args[0].equals("clear")){
                for(String g:GROUPS){group=g;world=worlds.get(g);
                    for(Pair p:pairs)if(p.group.equals(g)&&p.retained()){
                        if(p.path.equals("direct")||stage.equals("final"))kill(p);
                        else{
                            execute(p,"unsetvariable{var=caster.Saved}",false);execute(p,"unsetvariable{var=caster.Transient}",false);execute(p,"unsetvariable{var=caster.Point}",false);
                            execute(p,"setvariable{var=caster.Updated;value="+(31+p.index)+";save=true}",false);
                            Object inst=instance(p.runtimeId),service=runtime.getClass().getMethod("skillService").invoke(runtime);
                            if(inst!=null)service.getClass().getMethod("clearSkillTree",int.class).invoke(service,((Number)inst.getClass().getMethod("instanceId").invoke(inst)).intValue());
                        }
                    }
                }
                later(70,()->{
                    for(String g:GROUPS){group=g;world=worlds.get(g);observe("cleared");unloadCurrent();}
                    awaitUnloaded(GROUPS,0,()->getLogger().info("DCRASH_CLEARED|stage="+stage));
                });return true;
            }
        }catch(Throwable e){failed=true;getLogger().severe("DCRASH_FAILED|stage="+stage+"|error="+e);e.printStackTrace();}
        return false;
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
            if(!p.group.equals(group))continue;
            var h=harnesses.get(p.path);Object nativeActive=((Optional<?>)h.manager.getClass().getMethod("getActiveMob",UUID.class).invoke(h.manager,p.nativeId)).orElse(null);
            Object nr=nativeActive==null?null:nativeActive.getClass().getMethod("getVariables").invoke(nativeActive);
            Object snapshot=h.runtimeType("dev.helstera.ai.skill.SkillExtras").getMethod("scopedVariableSnapshot",String.class).invoke(null,"entity:"+p.runtimeId);
            Map<?,?> rv=(Map<?,?>)snapshot.getClass().getMethod("variables").invoke(snapshot),rl=(Map<?,?>)snapshot.getClass().getMethod("locations").invoke(snapshot);
            Object inst=instance(p.runtimeId),service=runtime.getClass().getMethod("skillService").invoke(runtime);
            Optional<?> tree=inst==null?Optional.empty():(Optional<?>)service.getClass().getMethod("snapshotOf",int.class).invoke(service,((Number)inst.getClass().getMethod("instanceId").invoke(inst)).intValue());
            Object tv=tree.isEmpty()?null:((Map<?,?>)tree.get().getClass().getMethod("variables").invoke(tree.get())).get("CrashTree");
            long visuals=Bukkit.getWorld(world.getUID())==null||!world.isChunkLoaded(p.chunkX(),300)?0:Arrays.stream(world.getChunkAt(p.chunkX(),300).getEntities()).filter(e->e instanceof Display||e instanceof Interaction||e instanceof ArmorStand).count();
            getLogger().info("DCRASH_ROW|phase="+phase+"|key="+p.key+"|nativeEntity="+(Bukkit.getEntity(p.nativeId)!=null)+"|runtimeEntity="+(Bukkit.getEntity(p.runtimeId)!=null)
                    +"|nativeRegistry="+(nativeActive!=null)+"|runtimeInstance="+(inst!=null)+"|nativeSaved="+nativeValue(nr,"Saved")+"|runtimeSaved="+render(rv.get("Saved"))
                    +"|nativeTransient="+nativeValue(nr,"Transient")+"|runtimeTransient="+render(rv.get("Transient"))+"|nativeUpdated="+nativeValue(nr,"Updated")+"|runtimeUpdated="+render(rv.get("Updated"))
                    +"|nativePoint="+nativeValue(nr,"Point")+"|runtimePoint="+render(rl.get("Point"))+"|tree="+(tv==null?"absent":tv)+"|visuals="+visuals
                    +"|forced="+(Bukkit.getWorld(world.getUID())!=null&&world.isChunkForceLoaded(p.chunkX(),300)));rows++;
        }
    }
    private void finish() throws Exception{
        for(var h:harnesses.values())h.close();
        getLogger().info("DCRASH_SUMMARY|stage="+stage+"|rows="+rows+"|completed=true");
    }
    @EventHandler(priority=EventPriority.MONITOR) public void saved(org.bukkit.event.world.WorldSaveEvent e){if(e.getWorld().getName().startsWith("despawn-crash-"))getLogger().info("DCRASH_WORLD_SAVE|stage="+stage+"|world="+e.getWorld().getName()+"|tick="+Bukkit.getCurrentTick());}
    @EventHandler(priority=EventPriority.MONITOR) public void loaded(EntitiesLoadEvent e){for(Entity entity:e.getEntities())event(entity,"load");}
    @EventHandler(priority=EventPriority.MONITOR) public void chunkUnloaded(org.bukkit.event.world.ChunkUnloadEvent e){
        if(!chunkBoundary||!worlds.containsValue(e.getWorld())||e.getChunk().getZ()!=300)return;
        int x=e.getChunk().getX();if(x<300||x>336||(x-300)%4!=0)return;
        getLogger().info("DCRASH_CHUNK_EVENT|stage="+stage+"|group="+e.getWorld().getName().substring("despawn-crash-".length())+"|x="+x+"|z=300|save="+e.isSaveChunk()+"|tick="+Bukkit.getCurrentTick());
    }
    @EventHandler(priority=EventPriority.MONITOR) public void entitiesUnloaded(org.bukkit.event.world.EntitiesUnloadEvent e){
        if(chunkBoundary)for(Entity entity:e.getEntities())event(entity,"unload");
    }
    @EventHandler(priority=EventPriority.MONITOR) public void worldUnloaded(org.bukkit.event.world.WorldUnloadEvent e){
        if(chunkBoundary&&worlds.containsValue(e.getWorld()))getLogger().info("DCRASH_WORLD_EVENT|stage="+stage+"|world="+e.getWorld().getName()+"|cancelled="+e.isCancelled());
    }
    @EventHandler(priority=EventPriority.HIGHEST) public void died(EntityDeathEvent e){
        // This fixture asserts entity absence; vanilla cow loot is outside that contract.
        if(pairs.stream().anyMatch(p->p.nativeId.equals(e.getEntity().getUniqueId())||p.runtimeId.equals(e.getEntity().getUniqueId()))){
            e.getDrops().clear();e.setDroppedExp(0);
        }
        event(e.getEntity(),"death");
    }
    private void event(Entity entity,String event){for(Pair p:pairs)for(String backend:List.of("native","runtime"))if(entity.getUniqueId().equals(backend.equals("native")?p.nativeId:p.runtimeId))
        getLogger().info("DCRASH_EVENT|stage="+stage+"|key="+p.key+"|backend="+backend+"|event="+event+"|uuid="+entity.getUniqueId());}
}
