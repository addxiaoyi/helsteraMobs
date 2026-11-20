package dev.helstera.test.randomspawn;

import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.world.*;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import java.nio.file.*;
import java.util.*;

/** Observe ordinary unload/reload timing without altering native handlers or clocks. */
public final class RapidReloadProbe extends JavaPlugin implements Listener {
    private static final List<String> MODES=List.of("NORMAL","CHUNK","NPC","NEVER","PERSISTENT");
    private static final List<Integer> DELAYS=List.of(1,4,5,6,10,11,40);
    private static final int Z=256;
    private static final class Cell {
        final String key,mode,path,backend; final int x,delay,round; final UUID uuid;
        String phase="initial"; int unloadTick,despawns,deaths; boolean done;
        Cell(String key,String mode,String path,String backend,int x,int delay,int round,UUID uuid){
            this.key=key;this.mode=mode;this.path=path;this.backend=backend;this.x=x;this.delay=delay;this.round=round;this.uuid=uuid;
        }
        boolean retained(){return mode.equals("NEVER")||mode.equals("PERSISTENT");}
    }
    private Plugin runtime,mythic; private Object manager; private World world;
    private Class<?> extras,registryType,treeType,snapshotType;
    private final Map<UUID,Cell> all=new LinkedHashMap<>();
    private final Map<Integer,Cell> cells=new LinkedHashMap<>();
    private final Map<Object,Integer> tokens=Collections.synchronizedMap(new IdentityHashMap<>());
    private int wave,rows; private boolean failed;
    @FunctionalInterface private interface Step {void run() throws Exception;}
    private void fail(Throwable e){failed=true;getLogger().severe("RRELOAD_FAILED|error="+e);e.printStackTrace();}
    private void later(long ticks,Step step){Bukkit.getScheduler().runTaskLater(this,()->{if(!failed)try{step.run();}catch(Throwable e){fail(e);}},ticks);}
    private Class<?> rt(String name)throws Exception{return Class.forName(name,true,runtime.getClass().getClassLoader());}
    private Class<?> nt(String name)throws Exception{return Class.forName(name,true,mythic.getClass().getClassLoader());}
    private int token(Object o){if(o==null)return 0;return tokens.computeIfAbsent(o,k->tokens.size()+1);}
    private Object instances()throws Exception{return runtime.getClass().getMethod("instances").invoke(runtime);}
    private Object instance(UUID id)throws Exception{
        for(Object i:(Collection<?>)instances().getClass().getMethod("allImpl").invoke(instances()))
            if(((Optional<?>)i.getClass().getMethod("boundEntityId").invoke(i)).filter(id::equals).isPresent())return i;
        return null;
    }
    private Object active(UUID id)throws Exception{return ((Optional<?>)manager.getClass().getMethod("getActiveMob",UUID.class).invoke(manager,id)).orElse(null);}
    @Override public void onEnable(){
        runtime=Bukkit.getPluginManager().getPlugin("helsteraMobs");mythic=Bukkit.getPluginManager().getPlugin("MythicMobs");Bukkit.getPluginManager().registerEvents(this,this);
        try{
            @SuppressWarnings("unchecked") Class<? extends Event> type=(Class<? extends Event>)nt("io.lumine.mythic.bukkit.events.MythicMobDespawnEvent");
            Bukkit.getPluginManager().registerEvent(type,this,EventPriority.MONITOR,(listener,e)->{
                try{Entity entity=(Entity)e.getClass().getMethod("getEntity").invoke(e);Cell c=all.get(entity.getUniqueId());
                    if(c!=null)getLogger().info("RRELOAD_NATIVE_EVENT|key="+c.key+"|uuid="+c.uuid+"|active="+token(e.getClass().getMethod("getMob").invoke(e))+"|tick="+Bukkit.getCurrentTick()+"|thread="+Thread.currentThread().getName()+"|stack="+Arrays.toString(Thread.currentThread().getStackTrace()));
                }catch(ReflectiveOperationException ex){throw new EventException(ex);}
            },this);
        }catch(Exception e){fail(e);}
        later(100,this::begin);
    }
    private void begin()throws Exception{
        manager=mythic.getClass().getMethod("getMobManager").invoke(mythic);
        extras=rt("dev.helstera.ai.skill.SkillExtras");registryType=rt("dev.helstera.api.behavior.ScopedVariableSnapshotStore$Registry");treeType=rt("dev.helstera.api.behavior.SkillTreeContext");snapshotType=rt("dev.helstera.api.behavior.SkillTreeContext$Snapshot");
        world=new WorldCreator("rapid-reload").generator(new ChunkGenerator(){@Override public ChunkData generateChunkData(World w,Random r,int x,int z,BiomeGrid b){return createChunkData(w);}}).generateStructures(false).createWorld();
        world.setKeepSpawnInMemory(false);world.setGameRule(GameRule.DO_MOB_SPAWNING,false);world.setGameRule(GameRule.MAX_ENTITY_CRAMMING,0);
        configure();next();
    }
    private void configure()throws Exception{
        Object importer=rt("dev.helstera.migration.importer.MythicMobsImporter").getConstructor().newInstance();
        @SuppressWarnings("unchecked") List<Map<String,Object>> plans=(List<Map<String,Object>>)importer.getClass().getMethod("convert",Path.class).invoke(importer,mythic.getDataFolder().toPath().resolve("Mobs/rapid.yml"));
        for(String mode:MODES)for(String path:List.of("direct","imported")){
            String id="rr_"+mode.toLowerCase(Locale.ROOT)+"_"+path;var cfg=new YamlConfiguration();
            if(path.equals("imported")){
                cfg.loadFromString(plans.stream().filter(p->id.equals(p.get("source-mob-id"))&&p.containsKey("target-file")).findFirst().orElseThrow().get("content").toString());
                for(String event:List.of("despawn","death"))cfg.set("mob.triggers.on-"+event+".do",cfg.getStringList("mob.triggers.on-"+event+".do").stream().map(s->s.replace("hook native","hook runtime")).toList());
            }else{
                cfg.set("entity.type","COW");cfg.set("entity.no-ai",true);cfg.set("entity.gravity",false);cfg.set("entity.invulnerable",true);cfg.set("mob.despawn",mode);
                for(String event:List.of("despawn","death"))cfg.set("ai.triggers.on-"+event+".do",List.of("command \"rapidreloadprobe hook runtime "+event+" <caster.uuid>\" @self"));
            }
            cfg.set("mob.despawn-distance",0);cfg.set("model","example/emberling");cfg.save(runtime.getDataFolder().toPath().resolve("mobs/"+id+".yml").toFile());
            getLogger().info("RRELOAD_MAPPING|id="+id+"|mode="+cfg.getString("mob.despawn")+"|path="+path+"|trigger="+(cfg.isList("mob.triggers.on-despawn.do")||cfg.isList("ai.triggers.on-despawn.do")));
        }
    }
    private void load(Cell c){var chunk=world.getChunkAt(c.x,Z);chunk.getEntities();chunk.addPluginChunkTicket(this);}
    private void next()throws Exception{
        if(wave==3*DELAYS.size()){
            extras.getMethod("saveScopedVariables",java.io.File.class).invoke(null,getDataFolder().toPath().resolve("final-scoped.yml").toFile());
            world.removePluginChunkTickets(this);boolean ok=Bukkit.unloadWorld(world,true);getLogger().info("RRELOAD_SEAL|accepted="+ok);if(!ok)throw new IllegalStateException("World unload denied");
            later(40,()->getLogger().info("RRELOAD_SUMMARY|rows="+rows+"|entities="+all.size()+"|completed=true"));return;
        }
        cells.clear();int index=0,delay=DELAYS.get(wave%DELAYS.size()),round=wave/DELAYS.size()+1;
        for(String mode:MODES)for(String path:List.of("direct","imported"))for(String backend:List.of("native","runtime")){
            int x=256+index++*4;var chunk=world.getChunkAt(x,Z);chunk.getEntities();chunk.addPluginChunkTicket(this);
            String id="rr_"+mode.toLowerCase(Locale.ROOT)+"_"+path,key="r"+round+"_d"+delay+"_"+mode.toLowerCase(Locale.ROOT)+"_"+path;
            Location at=new Location(world,x*16+2.25,110,Z*16+2.75);LivingEntity entity;
            if(backend.equals("native")){
                Object api=mythic.getClass().getMethod("getAPIHelper").invoke(mythic);entity=(LivingEntity)api.getClass().getMethod("spawnMythicMob",String.class,Location.class).invoke(api,id,at);
                Object a=active(entity.getUniqueId()),vars=a.getClass().getMethod("getVariables").invoke(a);Class<?> type=nt("io.lumine.mythic.core.skills.variables.VariableType"),variable=nt("io.lumine.mythic.core.skills.variables.Variable");
                Object integer=Arrays.stream(type.getEnumConstants()).filter(t->t.toString().equals("INTEGER")).findFirst().orElseThrow(),value=variable.getMethod("ofType",type,Object.class,boolean.class).invoke(null,integer,73,true);
                vars.getClass().getMethod("put",String.class,variable).invoke(vars,"Marker",value);
            }else{
                int i=((Number)runtime.getClass().getMethod("spawnMobAt",String.class,Location.class,String.class).invoke(runtime,id,at,null)).intValue();Object inst=instances().getClass().getMethod("impl",int.class).invoke(instances(),i);entity=(LivingEntity)((Optional<?>)inst.getClass().getMethod("baseEntity").invoke(inst)).orElseThrow();
                Object registry=registryType.getConstructor(Map.class,Map.class,Map.class,Map.class).newInstance(Map.of("Marker",73),Map.of(),Map.of("Marker",0L),Map.of());extras.getMethod("restoreScopedVariables",String.class,registryType).invoke(null,"entity:"+entity.getUniqueId(),registry);
                Object tree=treeType.getConstructor(Entity.class,List.class,Location.class).newInstance(entity,List.of(entity),at);treeType.getMethod("setVariable",String.class,Object.class).invoke(tree,"ReloadTree",91);Object service=runtime.getClass().getMethod("skillService").invoke(runtime);service.getClass().getMethod("restoreSkillTreeSnapshot",int.class,snapshotType).invoke(service,i,treeType.getMethod("snapshot").invoke(tree));
            }
            Cell c=new Cell(key,mode,path,backend,x,delay,round,entity.getUniqueId());cells.put(x,c);all.put(c.uuid,c);
            getLogger().info("RRELOAD_ID|key="+key+"|backend="+backend+"|uuid="+c.uuid+"|x="+x+"|z="+Z+"|mode="+mode+"|path="+path+"|delay="+delay+"|round="+round);
        }
        later(20,()->{for(Cell c:cells.values()){observe(c,"initial");request(c,"rapid-unload");}int expected=wave;later(220,()->{if(wave==expected)throw new IllegalStateException("Wave timeout");});});
    }
    private void request(Cell c,String phase){
        c.phase=phase;world.setChunkForceLoaded(c.x,Z,false);world.getChunkAt(c.x,Z).removePluginChunkTicket(this);
        boolean ok=world.unloadChunkRequest(c.x,Z);getLogger().info("RRELOAD_REQUEST|key="+c.key+"|backend="+c.backend+"|phase="+phase+"|accepted="+ok+"|tick="+Bukkit.getCurrentTick());if(!ok)throw new IllegalStateException("Unload rejected");
    }
    @EventHandler(priority=EventPriority.MONITOR) public void chunkUnload(ChunkUnloadEvent e){
        if(!e.getWorld().equals(world)||e.getChunk().getZ()!=Z)return;Cell c=cells.get(e.getChunk().getX());if(c==null)return;
        String phase=c.phase;getLogger().info("RRELOAD_CHUNK_UNLOAD|key="+c.key+"|backend="+c.backend+"|phase="+phase+"|save="+e.isSaveChunk()+"|tick="+Bukkit.getCurrentTick());
        if(!phase.equals("rapid-unload")&&!phase.equals("stable-unload"))return;
        c.unloadTick=Bukkit.getCurrentTick();later(phase.equals("rapid-unload")?c.delay:40,()->{
            int delta=Bukkit.getCurrentTick()-c.unloadTick;boolean absent=!world.isChunkLoaded(c.x,Z);c.phase=phase.equals("rapid-unload")?"reload":"stable-reload";load(c);
            getLogger().info("RRELOAD_LOAD_REQUEST|key="+c.key+"|backend="+c.backend+"|phase="+c.phase+"|delta="+delta+"|wasAbsent="+absent+"|tick="+Bukkit.getCurrentTick());
            if(c.phase.equals("reload")){
                observe(c,"reload");for(int offset:List.of(1,5,10))later(offset,()->observe(c,"after"+offset));
                later(40,()->{observe(c,"settled");request(c,"stable-unload");});
            }else later(20,()->{
                observe(c,"stable");c.phase="killing";if(Bukkit.getEntity(c.uuid) instanceof LivingEntity living){living.setInvulnerable(false);living.setHealth(0);}
                later(70,()->{observe(c,"cleaned");c.done=true;if(cells.values().stream().allMatch(v->v.done)){wave++;later(10,this::next);}});
            });
        });
    }
    private void entityEvent(Entity e,String name){Cell c=all.get(e.getUniqueId());if(c!=null)getLogger().info("RRELOAD_ENTITY_EVENT|key="+c.key+"|backend="+c.backend+"|event="+name+"|uuid="+c.uuid+"|object="+token(e)+"|phase="+c.phase+"|tick="+Bukkit.getCurrentTick());}
    @EventHandler(priority=EventPriority.MONITOR) public void loaded(EntitiesLoadEvent e){for(Entity entity:e.getEntities())entityEvent(entity,"load");}
    @EventHandler(priority=EventPriority.MONITOR) public void unloaded(EntitiesUnloadEvent e){for(Entity entity:e.getEntities())entityEvent(entity,"unload");}
    @EventHandler(priority=EventPriority.HIGHEST) public void death(EntityDeathEvent e){if(all.containsKey(e.getEntity().getUniqueId())){e.getDrops().clear();e.setDroppedExp(0);entityEvent(e.getEntity(),"death");}}
    private void observe(Cell c,String phase)throws Exception{
        Entity entity=Bukkit.getEntity(c.uuid),bound=null;Object registry=c.backend.equals("native")?active(c.uuid):instance(c.uuid),marker=null,tree=null;
        if(c.backend.equals("native")&&registry!=null){Object vars=registry.getClass().getMethod("getVariables").invoke(registry),v=vars.getClass().getMethod("get",String.class).invoke(vars,"Marker");if(v!=null)marker=v.getClass().getMethod("get").invoke(v);Object adapter=registry.getClass().getMethod("getEntity").invoke(registry);bound=(Entity)adapter.getClass().getMethod("getBukkitEntity").invoke(adapter);}
        if(c.backend.equals("runtime")){
            Object vars=extras.getMethod("scopedVariableSnapshot",String.class).invoke(null,"entity:"+c.uuid);marker=((Map<?,?>)registryType.getMethod("variables").invoke(vars)).get("Marker");
            if(registry!=null){bound=(Entity)((Optional<?>)registry.getClass().getMethod("baseEntity").invoke(registry)).orElse(null);Object service=runtime.getClass().getMethod("skillService").invoke(runtime);Optional<?> snap=(Optional<?>)service.getClass().getMethod("snapshotOf",int.class).invoke(service,((Number)registry.getClass().getMethod("instanceId").invoke(registry)).intValue());if(snap.isPresent())tree=((Map<?,?>)snapshotType.getMethod("variables").invoke(snap.get())).get("ReloadTree");}
        }
        boolean loaded=world.isChunkLoaded(c.x,Z);long visuals=loaded?Arrays.stream(world.getChunkAt(c.x,Z).getEntities()).filter(e->e instanceof Display||e instanceof Interaction||e instanceof ArmorStand).count():0;
        String checkpoint=entity==null?null:entity.getPersistentDataContainer().get(NamespacedKey.fromString(c.backend.equals("native")?"mythicmobs:variables":"helsteramobs:persistent_mob_variables"),PersistentDataType.STRING);
        getLogger().info("RRELOAD_ROW|key="+c.key+"|backend="+c.backend+"|phase="+phase+"|uuid="+c.uuid+"|entity="+(entity!=null)+"|valid="+(entity!=null&&entity.isValid())+"|registry="+(registry!=null)+"|marker="+(marker==null?"absent":marker)+"|tree="+(tree==null?"absent":tree)+"|visuals="+visuals+"|loaded="+loaded+"|active="+token(registry)+"|bound="+token(bound)+"|live="+token(entity)+"|boundValid="+(bound!=null&&bound.isValid())+"|despawns="+c.despawns+"|deaths="+c.deaths+"|checkpoint="+(checkpoint==null?"absent":Base64.getEncoder().encodeToString(checkpoint.getBytes(java.nio.charset.StandardCharsets.UTF_8)))+"|tick="+Bukkit.getCurrentTick());rows++;
    }
    @Override public boolean onCommand(CommandSender sender,Command cmd,String label,String[] args){
        if(!(sender instanceof ConsoleCommandSender)||args.length!=4||!args[0].equals("hook"))return false;
        try{Cell c=all.get(UUID.fromString(args[3]));if(c==null||!c.backend.equals(args[1]))throw new IllegalStateException("Unknown callback");int count=args[2].equals("death")?++c.deaths:++c.despawns;
            getLogger().info("RRELOAD_HOOK|key="+c.key+"|backend="+c.backend+"|event="+args[2]+"|uuid="+c.uuid+"|count="+count+"|phase="+c.phase+"|tick="+Bukkit.getCurrentTick()+"|thread="+Thread.currentThread().getName());
            if(count>1)getLogger().info("RRELOAD_DUPLICATE_STACK|key="+c.key+"|backend="+c.backend+"|event="+args[2]+"|stack="+Arrays.toString(Thread.currentThread().getStackTrace()));return true;
        }catch(Exception e){fail(e);return true;}
    }
}
