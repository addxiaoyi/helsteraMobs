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

/** Actual retained/accepted chunks and cancelled/accepted world unloads, native and managed runtime mobs. */
public final class DespawnLifecycleProbe extends JavaPlugin implements Listener {
    private static final List<String> MODES = List.of("NORMAL", "CHUNK", "NPC", "NEVER", "PERSISTENT");
    private record Pair(String key, String mode, String path, UUID nativeId, UUID runtimeId,
                        VariablePlaceholderAudit.Harness harness) { }
    private final List<Pair> pairs = new ArrayList<>();
    private final Map<String,Integer> hooks = new HashMap<>();
    private final Map<String,VariablePlaceholderAudit.Harness> harnesses = new HashMap<>();
    private Plugin runtime, mythic;
    private World world;
    private UUID worldId;
    private String scenario, phase;
    private boolean cancel, failed;
    private int rows, mismatches, cancelledEvents;
    private long baselineVisuals;
    private static final int CX=256, CZ=256;
    @FunctionalInterface private interface Step { void run() throws Exception; }
    private void later(long ticks, Step step) {
        Bukkit.getScheduler().runTaskLater(this, () -> { if (failed) return; try { step.run(); }
            catch (Throwable e) { failed=true; getLogger().severe("DESPAWN_FAILED|error="+e); e.printStackTrace(); } }, ticks);
    }
    @Override public void onEnable() {
        Bukkit.getPluginManager().registerEvents(this,this);
        Objects.requireNonNull(getCommand("despawnprobe")).setExecutor(this);
        runtime=Bukkit.getPluginManager().getPlugin("helsteraMobs"); mythic=Bukkit.getPluginManager().getPlugin("MythicMobs");
        try {
            @SuppressWarnings("unchecked") Class<? extends Event> type=(Class<? extends Event>)Class.forName(
                    "io.lumine.mythic.bukkit.events.MythicMobDespawnEvent",true,mythic.getClass().getClassLoader());
            Bukkit.getPluginManager().registerEvent(type,this,EventPriority.MONITOR,(listener,event)->{
                try {
                    Entity entity=(Entity)event.getClass().getMethod("getEntity").invoke(event);
                    Object mob=event.getClass().getMethod("getMob").invoke(event);
                    for(Pair p:pairs)if(p.nativeId.equals(entity.getUniqueId()))
                        getLogger().info("DESPAWN_NATIVE_EVENT|key="+p.key+"|uuid="+entity.getUniqueId()+"|activeObject="+System.identityHashCode(mob));
                } catch(ReflectiveOperationException e){throw new org.bukkit.event.EventException(e);}
            },this);
        }catch(ReflectiveOperationException e){throw new IllegalStateException(e);}
        later(100,()->begin("chunk"));
    }
    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof ConsoleCommandSender) || args.length!=3 || !List.of("native","runtime").contains(args[0])
                || !List.of("despawn","death").contains(args[1])) return false;
        String key=args[0]+":"+args[1]+":"+args[2]; int count=hooks.merge(key,1,Integer::sum);
        if(count>1)getLogger().info("DESPAWN_DUPLICATE_STACK|key="+key+"|stack="+Arrays.toString(Thread.currentThread().getStackTrace()));
        getLogger().info("DESPAWN_HOOK|backend="+args[0]+"|event="+args[1]+"|key="+args[2]+"|count="+count); return true;
    }
    private World createWorld() {
        return Objects.requireNonNull(new WorldCreator("despawn-"+scenario).generator(new ChunkGenerator() {
            @Override public ChunkData generateChunkData(World w, Random random, int x, int z, BiomeGrid b) { return createChunkData(w); }
        }).generateStructures(false).createWorld());
    }
    private void begin(String which) throws Exception {
        scenario=which;phase="prepare";pairs.clear();harnesses.clear();cancelledEvents=0;
        world=createWorld();worldId=world.getUID();world.setKeepSpawnInMemory(false);world.setGameRule(GameRule.DO_MOB_SPAWNING,false);
        var chunk=world.getChunkAt(CX,CZ);chunk.setForceLoaded(true);chunk.getEntities();
        // Helper cows are isolated from the tested chunk and are not observations.
        for (boolean imported:List.of(false,true)) harnesses.put(imported?"imported":"direct",
                new VariablePlaceholderAudit.Harness(this,runtime,mythic,new Location(world,4500,90,4500),imported));
        later(40,this::spawnPairs);
    }
    private void spawnPairs() throws Exception {
        int index=0;
        for (String mode:MODES) for (String path:List.of("direct","imported")) {
            String key=scenario+"_"+mode.toLowerCase(Locale.ROOT)+"_"+path;
            var h=harnesses.get(path);
            Path source=getDataFolder().toPath().resolve("Mobs/"+key+".yml");Files.createDirectories(source.getParent());
            var sourceYaml=new YamlConfiguration();sourceYaml.set(key+".Type","COW");sourceYaml.set(key+".Options.Despawn",mode);
            sourceYaml.set(key+".Options.NoAI",true);sourceYaml.set(key+".Options.NoGravity",true);sourceYaml.set(key+".Options.Invincible",true);
            sourceYaml.set(key+".Skills",List.of("command{c=\"despawnprobe runtime despawn "+key+"\"} @self ~onDespawn",
                    "command{c=\"despawnprobe runtime death "+key+"\"} @self ~onDeath"));sourceYaml.save(source.toFile());
            var config=new YamlConfiguration();String mobId="despawn_"+key;
            if (path.equals("imported")) {
                @SuppressWarnings("unchecked") List<Map<String,Object>> plans=(List<Map<String,Object>>)h.importer.getClass().getMethod("convert",Path.class).invoke(h.importer,source);
                var plan=plans.stream().filter(p->String.valueOf(p.get("target-file")).startsWith("mobs/")).findFirst().orElseThrow();
                config.loadFromString(plan.get("content").toString());
            } else {
                config.set("entity.type","COW");config.set("entity.no-ai",true);config.set("entity.gravity",false);config.set("entity.invulnerable",true);
                config.set("mob.despawn",mode);
                config.set("ai.triggers.on-despawn.do",List.of("command \"despawnprobe runtime despawn "+key+"\""));
                config.set("ai.triggers.on-death.do",List.of("command \"despawnprobe runtime death "+key+"\""));
            }
            config.set("model","example/emberling");
            // Isolate unload policy from Helstera's separate player-distance despawn rule.
            config.set("mob.despawn-distance",0);
            config.save(runtime.getDataFolder().toPath().resolve("mobs/"+mobId+".yml").toFile());
            getLogger().info("DESPAWN_MAPPING|key="+key+"|mode="+config.getString("mob.despawn")+"|trigger="+(config.isList("ai.triggers.on-despawn.do")||config.isList("mob.triggers.on-despawn.do")));
            Location point=new Location(world,CX*16+1+(index%4)*3,110,CZ*16+1+(index/4)*4);index++;
            Object api=mythic.getClass().getMethod("getAPIHelper").invoke(mythic);
            var n=(LivingEntity)api.getClass().getMethod("spawnMythicMob",String.class,Location.class).invoke(api,key,point);
            int id=((Number)runtime.getClass().getMethod("spawnMobAt",String.class,Location.class,String.class).invoke(runtime,mobId,point.clone().add(0,0,1),null)).intValue();
            Object inst=instances().getClass().getMethod("impl",int.class).invoke(instances(),id);
            var r=(LivingEntity)((Optional<?>)inst.getClass().getMethod("baseEntity").invoke(inst)).orElseThrow();
            var pair=new Pair(key,mode,path,n.getUniqueId(),r.getUniqueId(),h);pairs.add(pair);
            h.active.put(n,h.manager.getClass().getMethod("getMythicMobInstance",Entity.class).invoke(h.manager,n));
            Object meta=h.meta(n,n),ctx=context(pair,r);
            h.metadataType.getMethod("setEntityTarget",h.abstractEntity).invoke(meta,h.adapt.invoke(null,n));
            Object origin=h.nativeType("io.lumine.mythic.bukkit.BukkitAdapter").getMethod("adapt",Location.class).invoke(null,r.getLocation());
            h.metadataType.getMethod("setOrigin",h.nativeType("io.lumine.mythic.api.adapters.AbstractLocation")).invoke(meta,origin);
            h.execute("setvariable{var=caster.LifeValue;value=7;save=true}","VariableSetMechanic",meta,ctx,n);
            h.execute("setvariablelocation{var=caster.LifePoint;value=@origin;save=true}","VariableSetLocationMechanic",meta,ctx,n);
            Object service=runtime.getClass().getMethod("skillService").invoke(runtime);
            Object tree=h.newTree(r,r,r.getLocation());h.treeType.getMethod("setVariable",String.class,Object.class).invoke(tree,"DespawnTree",23);
            Object snapshot=h.treeType.getMethod("snapshot").invoke(tree);
            service.getClass().getMethod("restoreSkillTreeSnapshot",int.class,snapshot.getClass()).invoke(service,id,snapshot);
            getLogger().info("DESPAWN_ID|key="+key+"|native="+n.getUniqueId()+"|runtime="+r.getUniqueId()+"|world="+worldId);
        }
        later(20,()->{
            baselineVisuals=visuals();observe("initial");
            if(scenario.equals("world")){cancel=false;phase="accepted";requestUnload();later(70,this::afterUnload);return;}
            cancel=true;phase="cancel";
            requestUnload();later(50,()->{
                if ((!scenario.equals("chunk") && cancelledEvents==0) || Bukkit.getWorld(worldId)==null || !world.isChunkLoaded(CX,CZ))throw new IllegalStateException("Cancellation did not keep world/chunk loaded");
                observe(scenario.equals("chunk")?"retained":"cancelled");
                if(scenario.equals("cancelled")){
                    for(var h:harnesses.values())h.close();
                    getLogger().info("DESPAWN_SUMMARY|observations="+rows+"|mismatches="+mismatches+"|passed="+(mismatches==0));return;
                }
                cancel=false;world.getChunkAt(CX,CZ).setForceLoaded(true);
                phase="accepted";requestUnload();later(70,this::afterUnload);
            });
        });
    }
    private void requestUnload() {
        boolean accepted;
        if (scenario.equals("chunk")) { if(!cancel)world.getChunkAt(CX,CZ).setForceLoaded(false);accepted=world.unloadChunkRequest(CX,CZ); }
        else accepted=Bukkit.unloadWorld(world,true);
        getLogger().info("DESPAWN_REQUEST|scenario="+scenario+"|phase="+phase+"|accepted="+accepted+"|worldAbsent="+(Bukkit.getWorld(worldId)==null));
    }
    private void afterUnload() throws Exception {
        boolean absent=pairs.stream().allMatch(p->Bukkit.getEntity(p.nativeId)==null && Bukkit.getEntity(p.runtimeId)==null);
        boolean unloaded=scenario.equals("chunk")?!world.isChunkLoaded(CX,CZ):Bukkit.getWorld(worldId)==null;
        getLogger().info("DESPAWN_UNLOADED|scenario="+scenario+"|unloaded="+unloaded+"|entitiesAbsent="+absent);
        if(!absent || !unloaded)throw new IllegalStateException("Real unload did not complete");
        if(scenario.equals("world"))world=createWorld();
        if(!world.getUID().equals(worldId))throw new IllegalStateException("World UUID changed");
        world.getChunkAt(CX,CZ).setForceLoaded(true);world.getChunkAt(CX,CZ).getEntities();
        later(40,()->{
            observe("reloaded");
            for(Pair p:pairs)for(UUID id:List.of(p.nativeId,p.runtimeId))if(Bukkit.getEntity(id) instanceof LivingEntity e) {e.setInvulnerable(false);e.setHealth(0);}
            later(70,()->{observe("dead");
                for(var h:harnesses.values())h.close();
                if(scenario.equals("chunk"))begin("world");
                else begin("cancelled");
            });
        });
    }
    private Object instances() throws Exception {return runtime.getClass().getMethod("instances").invoke(runtime);}
    private Object instance(UUID id) throws Exception {
        for(Object inst:(Collection<?>)instances().getClass().getMethod("allImpl").invoke(instances()))
            if(((Optional<?>)inst.getClass().getMethod("boundEntityId").invoke(inst)).filter(id::equals).isPresent())return inst;
        return null;
    }
    private Object context(Pair p, LivingEntity entity) throws Exception {
        var h=p.harness;return h.contextType.getMethod("continueExecution",h.modelType,LivingEntity.class,double.class,double.class,long.class,String.class,String.class,Entity.class,List.class,Location.class,h.treeType)
                .invoke(null,instance(p.runtimeId),entity,1d,0d,0L,"despawn-probe",null,entity,List.of(entity),entity.getLocation(),h.newTree(entity,entity,entity.getLocation()));
    }
    private long visuals(){return world.getEntities().stream().filter(e->e.getLocation().getBlockX()>>4==CX).filter(e->e.getLocation().getBlockZ()>>4==CZ).filter(e->e instanceof Display || e instanceof Interaction || e instanceof ArmorStand).count();}
    private int hook(Pair p,String backend,String event){return hooks.getOrDefault(backend+":"+event+":"+p.key,0);}
    private void observe(String checkpoint) throws Exception {
        phase=checkpoint;
        for(Pair p:pairs){
            boolean retained=p.mode.equals("NEVER")||p.mode.equals("PERSISTENT");
            boolean expected=checkpoint.equals("initial")||(checkpoint.equals("cancelled")||checkpoint.equals("retained"))||checkpoint.equals("reloaded")&&retained;
            Object nativeActive=((Optional<?>)p.harness.manager.getClass().getMethod("getActiveMob",UUID.class).invoke(p.harness.manager,p.nativeId)).orElse(null);
            Object nValue=null,nPoint=null;
            if(nativeActive!=null){Object registry=nativeActive.getClass().getMethod("getVariables").invoke(nativeActive);
                nValue=registry.getClass().getMethod("get",String.class).invoke(registry,"LifeValue");nPoint=registry.getClass().getMethod("get",String.class).invoke(registry,"LifePoint");}
            Object registry=p.harness.runtimeType("dev.helstera.ai.skill.SkillExtras").getMethod("scopedVariableSnapshot",String.class).invoke(null,"entity:"+p.runtimeId);
            Object rValue=((Map<?,?>)registry.getClass().getMethod("variables").invoke(registry)).get("LifeValue");
            Object runtimePoint=((Map<?,?>)registry.getClass().getMethod("locations").invoke(registry)).get("LifePoint");
            boolean rPoint=runtimePoint!=null;
            String nativeLocation=nPoint==null?"absent":renderPoint(nPoint.getClass().getMethod("get").invoke(nPoint),false);
            String runtimeLocation=runtimePoint==null?"absent":renderPoint(runtimePoint,true);
            String nv=nValue==null?"absent":nValue.getClass().getMethod("get").invoke(nValue).toString();
            String rv=rValue==null?"absent":rValue.toString();
            boolean nEntity=Bukkit.getEntity(p.nativeId)!=null && !Bukkit.getEntity(p.nativeId).isDead();
            boolean rEntity=Bukkit.getEntity(p.runtimeId)!=null && !Bukkit.getEntity(p.runtimeId).isDead();
            boolean runtimeInstance=instance(p.runtimeId)!=null;
            Object inst=instance(p.runtimeId),service=runtime.getClass().getMethod("skillService").invoke(runtime);
            Optional<?> tree=inst==null?Optional.empty():(Optional<?>)service.getClass().getMethod("snapshotOf",int.class)
                    .invoke(service,((Number)inst.getClass().getMethod("instanceId").invoke(inst)).intValue());
            Object treeValue=tree.isEmpty()?null:((Map<?,?>)tree.get().getClass().getMethod("variables").invoke(tree.get())).get("DespawnTree");
            Path snapshot=runtime.getDataFolder().toPath().resolve("skill-trees/entity-"+p.runtimeId+".yml");
            if(checkpoint.equals("reloaded")&&retained){
                Path dest=getDataFolder().toPath().resolve("snapshots/"+p.key+".yml");Files.createDirectories(dest.getParent());Files.copy(snapshot,dest);
            }
            boolean snapshotGone=!Files.exists(snapshot);
            int despawns=(checkpoint.equals("reloaded")||checkpoint.equals("dead"))&&!retained?1:0;
            int deaths=checkpoint.equals("dead")&&retained?1:0;
            // 5.7.2 ignores WorldUnloadEvent cancellation: record its behavior without copying it.
            boolean nativeCancellation=scenario.equals("cancelled")&&checkpoint.equals("cancelled");
            boolean nativeExpected=nativeCancellation?false:expected;
            boolean match=nEntity==(nativeCancellation?retained:expected) && rEntity==expected && (nativeActive!=null)==nativeExpected && runtimeInstance==expected
                    && nv.equals(nativeExpected?"7":"absent") && rv.equals(expected?"7":"absent") && (nPoint!=null)==nativeExpected && rPoint==expected
                    && hook(p,"native","despawn")==((nativeCancellation&&!retained)?1:despawns) && hook(p,"runtime","despawn")==despawns
                    && hook(p,"native","death")==deaths && hook(p,"runtime","death")==deaths
                    && Objects.equals(treeValue,expected?Integer.valueOf(23):null) && (!checkpoint.equals("dead")||snapshotGone);
            getLogger().info("DESPAWN_ROW|scenario="+scenario+"|phase="+checkpoint+"|key="+p.key+"|mode="+p.mode+"|path="+p.path+"|expected="+expected
                    +"|nativeEntity="+nEntity+"|runtimeEntity="+rEntity+"|nativeRegistry="+(nativeActive!=null)+"|runtimeInstance="+runtimeInstance
                    +"|nativeValue="+nv+"|runtimeValue="+rv+"|nativePoint="+(nPoint!=null)+"|runtimePoint="+rPoint
                    +"|nativeDespawn="+hook(p,"native","despawn")+"|runtimeDespawn="+hook(p,"runtime","despawn")
                    +"|nativeDeath="+hook(p,"native","death")+"|runtimeDeath="+hook(p,"runtime","death")+"|nativeLocation="+nativeLocation+"|runtimeLocation="+runtimeLocation+"|runtimeTree="+(treeValue==null?"absent":treeValue)+"|snapshotGone="+snapshotGone+"|knownNativeCancellation="+nativeCancellation+"|matched="+match);
            rows++;if(!match)mismatches++;
        }
        long expectedVisuals=checkpoint.equals("dead")?0:checkpoint.equals("reloaded")?baselineVisuals*2/5:baselineVisuals;
        long actual=visuals();getLogger().info("DESPAWN_VISUALS|scenario="+scenario+"|phase="+checkpoint+"|expected="+expectedVisuals+"|actual="+actual);
        if(actual!=expectedVisuals)mismatches++;
    }
    private String renderPoint(Object value,boolean snapshot) throws Exception {
        if(snapshot)return value.getClass().getMethod("world").invoke(value)+","+value.getClass().getMethod("x").invoke(value)
                +","+value.getClass().getMethod("y").invoke(value)+","+value.getClass().getMethod("z").invoke(value);
        Object w=value.getClass().getMethod("getWorld").invoke(value);
        return w.getClass().getMethod("getName").invoke(w)+","+value.getClass().getMethod("getX").invoke(value)
                +","+value.getClass().getMethod("getY").invoke(value)+","+value.getClass().getMethod("getZ").invoke(value);
    }
    @EventHandler(priority=EventPriority.MONITOR) public void chunkEvent(ChunkUnloadEvent e){
        if(e.getWorld()==world && e.getChunk().getX()==CX && e.getChunk().getZ()==CZ)getLogger().info("DESPAWN_EVENT|scenario="+scenario+"|phase="+phase+"|event=chunk-unload");
    }
    @EventHandler(priority=EventPriority.LOWEST) public void cancelWorld(WorldUnloadEvent e){if(e.getWorld()==world && cancel){e.setCancelled(true);cancelledEvents++;}}
    @EventHandler(priority=EventPriority.MONITOR) public void worldEvent(WorldUnloadEvent e){if(e.getWorld()==world)getLogger().info("DESPAWN_EVENT|scenario="+scenario+"|phase="+phase+"|event=world-unload|cancelled="+e.isCancelled());}
    @EventHandler(priority=EventPriority.MONITOR) public void loaded(EntitiesLoadEvent e){for(Entity entity:e.getEntities())entityEvent(entity,"load");}
    @EventHandler(priority=EventPriority.MONITOR) public void unloaded(EntitiesUnloadEvent e){for(Entity entity:e.getEntities())entityEvent(entity,"unload");}
    @EventHandler(priority=EventPriority.MONITOR) public void death(EntityDeathEvent e){entityEvent(e.getEntity(),"death");}
    private void entityEvent(Entity entity,String event){for(Pair p:pairs)if(entity.getUniqueId().equals(p.nativeId)||entity.getUniqueId().equals(p.runtimeId))getLogger().info("DESPAWN_ENTITY_EVENT|scenario="+scenario+"|phase="+phase+"|event="+event+"|uuid="+entity.getUniqueId());}
}
