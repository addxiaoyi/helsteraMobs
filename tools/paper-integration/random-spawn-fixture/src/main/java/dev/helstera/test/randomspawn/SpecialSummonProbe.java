package dev.helstera.test.randomspawn;

import org.bukkit.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.EntityMountEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.util.*;
import java.util.function.Consumer;

/** Native constructors and production direct/imported execution, including real server ticks. */
@SuppressWarnings("deprecation")
public final class SpecialSummonProbe extends JavaPlugin implements Listener {
    private VariablePlaceholderAudit.Harness h;
    private List<Map<?,?>> cases;
    private final List<Object> nativeActions=new ArrayList<>();
    private final List<String> constructionErrors=new ArrayList<>();
    private final List<Entity> children=new ArrayList<>(),seeds=new ArrayList<>();
    private final List<Map<String,Object>> events=new ArrayList<>();
    private Map<?,?> sample;
    private String path,source;
    private int index;
    private Player observer;
    private YamlConfiguration row;
    private Entity caster;
    private Object runtimeCaster;
    private Location origin;
    private boolean observing;
    private Consumer<Object> spawnListener;
    private Object bus;
    private Class<?> spawnEvent;
    @Override public void onEnable() {
        Bukkit.getPluginManager().registerEvents(this,this);
        later(100,this::begin);
    }
    @Override public void onDisable() {
        if(bus!=null && spawnListener!=null) try { bus.getClass().getMethod("unregister",Class.class,Consumer.class).invoke(bus,spawnEvent,spawnListener); }
        catch(Exception failure) { getLogger().warning(failure.toString()); }
    }
    private interface Task { void run() throws Exception; }
    private void later(long ticks,Task task) { Bukkit.getScheduler().runTaskLater(this,()->{try{task.run();}catch(Throwable e){fail(e);}},ticks); }
    private void fail(Throwable e) { getLogger().severe("SPECIAL_SUMMON_FAILED|"+e); e.printStackTrace(); }
    private String text(String key) { return String.valueOf(sample.get(key)); }
    private int integer(String key) { return ((Number)sample.get(key)).intValue(); }
    private void begin() throws Exception {
        observer=Bukkit.getPlayerExact("SummonObserver");
        if(observer==null){later(5,this::begin);return;}
        observer.setGameMode(GameMode.SPECTATOR);observer.setGravity(false);
        observer.teleport(new Location(observer.getWorld(),115,180,4));
        for(int x=6;x<=8;x++)for(int z=-1;z<=0;z++) {
            var chunk=observer.getWorld().getChunkAt(x,z);chunk.setForceLoaded(true);chunk.getEntities();
        }

        var runtime=Bukkit.getPluginManager().getPlugin("helsteraMobs");
        var mythic=Bukkit.getPluginManager().getPlugin("MythicMobs");
        h=new VariablePlaceholderAudit.Harness(this,runtime,mythic,new Location(Bukkit.getWorlds().getFirst(),0,150,0),false);
        origin=new Location(h.a.getWorld(),120,170,0);
        h.a.teleport(new Location(origin.getWorld(),100,170,0)); h.b.teleport(new Location(origin.getWorld(),110,170,0));
        h.c.teleport(new Location(origin.getWorld(),130,175,0)); h.item.teleport(new Location(origin.getWorld(),130,175,0));
        cases=YamlConfiguration.loadConfiguration(getDataFolder().toPath().resolve("cases.yml").toFile()).getMapList("cases");
        @SuppressWarnings("unchecked") var plans=(List<Map<String,Object>>)h.importer.getClass().getMethod("convert",java.nio.file.Path.class)
                .invoke(h.importer,mythic.getDataFolder().toPath().resolve("Mobs/special-summon.yml"));
        for(var plan:plans) if(String.valueOf(plan.get("target-file")).startsWith("mobs/")) {
            var config=new YamlConfiguration(); config.loadFromString(plan.get("content").toString());
            config.set("model","example/emberling"); config.set("mob.despawn-distance",0);
            String target=plan.get("target-file").toString();
            Files.createDirectories(runtime.getDataFolder().toPath().resolve(target).getParent());
            config.save(runtime.getDataFolder().toPath().resolve(target).toFile());
            Files.createDirectories(getDataFolder().toPath().resolve("imports"));
            config.save(getDataFolder().toPath().resolve("imports/"+java.nio.file.Path.of(target).getFileName()).toFile());
        }
        bus=runtime.getClass().getMethod("bus").invoke(runtime); spawnEvent=h.runtimeType("dev.helstera.api.event.ModelSpawnEvent");
        spawnListener=event->{if(observing)try {
            Object inst=event.getClass().getMethod("instance").invoke(event);
            Entity entity=(Entity)((Optional<?>)h.modelType.getMethod("baseEntity").invoke(inst)).orElseThrow();
            events.add(event("spawn",entity,null,false));
        }catch(Exception e){throw new IllegalStateException(e);}};
        bus.getClass().getMethod("register",Class.class,Consumer.class).invoke(bus,spawnEvent,spawnListener);
        @SuppressWarnings("unchecked") Class<? extends Event> nativeSpawn=(Class<? extends Event>)h.nativeType("io.lumine.mythic.bukkit.events.MythicMobSpawnEvent");
        Bukkit.getPluginManager().registerEvent(nativeSpawn,this,EventPriority.MONITOR,(listener,event)->{
            if(observing)try {
                Entity entity=(Entity)event.getClass().getMethod("getEntity").invoke(event);
                var state=event("spawn",entity,null,false);
                state.put("nativeReason",event.getClass().getMethod("getSpawnReason").invoke(event).toString());
                state.put("level",event.getClass().getMethod("getMobLevel").invoke(event)); events.add(state);
            }catch(Exception e){throw new EventException(e);}
        },this);
        for(var entry:cases) {
            String raw=entry.get("mechanic")+"{"+entry.get("arguments")+"}";
            String name=switch(entry.get("kind").toString()){case "cloud"->"SummonAreaEffectCloudMechanic";case "fall"->"SummonFallingBlockMechanic";default->"SummonPassengerMechanic";};
            try {
                nativeActions.add(h.nativeType("io.lumine.mythic.core.skills.mechanics."+name)
                    .getConstructor(h.executorType,java.io.File.class,String.class,h.configType)
                    .newInstance(h.nativeSkills,h.source.toFile(),raw,h.configImpl.getConstructor(String.class).newInstance(raw)));
                constructionErrors.add("");
            }catch(InvocationTargetException e){nativeActions.add(null);constructionErrors.add(e.getCause().getClass().getSimpleName());}
        }
        Object clock=mythic.getClass().getMethod("getClock").invoke(mythic);
        clock.getClass().getMethod("runSecondPass").invoke(clock); clock.getClass().getMethod("runPostReload").invoke(clock);
        var particles=new YamlConfiguration();
        Class<?> nativeParticles=h.nativeType("io.lumine.mythic.bukkit.adapters.BukkitParticle");
        for(Object particle:nativeParticles.getEnumConstants()) {
            Object actual=nativeParticles.getMethod("toBukkitParticle").invoke(particle);
            particles.set(particle.toString(),actual==null?"missing":actual.toString());
        }
        particles.save(getDataFolder().toPath().resolve("particles.yml").toFile());
        later(8,this::phase);
    }
    @EventHandler(priority=EventPriority.HIGHEST) public void mount(EntityMountEvent event) {
        if(!observing)return;
        if(Boolean.TRUE.equals(sample.get("cancel")))event.setCancelled(true);
        try{events.add(event("mount",event.getEntity(),event.getMount(),event.isCancelled()));}
        catch(Exception e){throw new IllegalStateException(e);}
    }
    private Map<String,Object> event(String kind,Entity entity,Entity vehicle,boolean cancelled)throws Exception {
        var data=new LinkedHashMap<String,Object>();data.put("event",kind);data.put("entity",entity.getUniqueId().toString());
        data.put("type",entity.getType().name());data.put("vehicle",vehicle==null?"none":vehicle.getUniqueId().toString());
        data.put("owner",owner(entity));data.put("level",level(entity));data.put("cancelled",cancelled);data.put("tick",Bukkit.getCurrentTick());return data;
    }
    private Object instances()throws Exception{return h.runtime.getClass().getMethod("instances").invoke(h.runtime);}
    private Object runtimeInstance(Entity entity)throws Exception {
        for(Object instance:(Collection<?>)instances().getClass().getMethod("allInstances").invoke(instances())) {
            if(((Optional<?>)h.modelType.getMethod("baseEntity").invoke(instance)).orElse(null)==entity)return instance;
        }return null;
    }
    private Object nativeInstance(Entity entity)throws Exception{return h.manager.getClass().getMethod("getMythicMobInstance",Entity.class).invoke(h.manager,entity);}
    private String owner(Entity entity)throws Exception {
        if(path.equals("native")) {
            Object active=nativeInstance(entity); if(active==null)return "none";
            Object opt=active.getClass().getMethod("getOwner").invoke(active);
            return (boolean)opt.getClass().getMethod("isPresent").invoke(opt)?opt.getClass().getMethod("get").invoke(opt).toString():"none";
        }
        Object id=h.runtimeType("dev.helstera.api.behavior.MobRelations").getMethod("ownerId",Entity.class).invoke(null,entity);
        return id==null?"none":id.toString();
    }
    private String parent(Entity entity)throws Exception {
        if(path.equals("native")) {
            Object active=nativeInstance(entity); if(active==null)return "none";
            Object opt=active.getClass().getMethod("getParent").invoke(active);
            if(!(boolean)opt.getClass().getMethod("isPresent").invoke(opt))return "none";
            Object parent=opt.getClass().getMethod("get").invoke(opt);
            return h.abstractEntity.getMethod("getUniqueId").invoke(parent).toString();
        }
        Object id=h.runtimeType("dev.helstera.api.behavior.MobRelations").getMethod("parentId",Entity.class).invoke(null,entity);
        return id==null?"none":id.toString();
    }
    private double level(Entity entity)throws Exception {
        if(path.equals("native")){Object active=nativeInstance(entity);return active==null?0:((Number)active.getClass().getMethod("getLevel").invoke(active)).doubleValue();}
        Object instance=runtimeInstance(entity);return instance==null?0:((Number)instance.getClass().getField("level").get(instance)).doubleValue();
    }
    private void cleanup()throws Exception {
        for(Entity entity:children){Object instance=runtimeInstance(entity);if(instance!=null)instances().getClass().getMethod("despawn",int.class).invoke(instances(),h.modelType.getMethod("instanceId").invoke(instance));entity.remove();}
        for(Entity entity:seeds)entity.remove();children.clear();seeds.clear();
        if(runtimeCaster!=null){instances().getClass().getMethod("despawn",int.class).invoke(instances(),h.modelType.getMethod("instanceId").invoke(runtimeCaster));runtimeCaster=null;}
        h.a.eject();h.b.eject();h.b.getActivePotionEffects().forEach(effect->h.b.removePotionEffect(effect.getType()));
        for(int x=116;x<=124;x++)for(int z=-4;z<=4;z++)origin.getWorld().getBlockAt(x,165,z).setType(Material.AIR,false);
        for(int y=166;y<=171;y++)origin.getWorld().getBlockAt(120,y,0).setType(Material.AIR,false);
        for(Entity entity:origin.getWorld().getNearbyEntities(origin,3,10,3))if(entity instanceof Item)entity.remove();
    }
    private void phase()throws Exception {
        cleanup();
        if(index>=cases.size()*3){observer.sendMessage("SPECIAL_SUMMON_DONE");getLogger().info("SPECIAL_SUMMON_SUMMARY|observations="+index+"|completed=true");return;}
        sample=cases.get(index/3);path=List.of("native","direct","imported").get(index%3);events.clear();
        h.item.teleport(new Location(origin.getWorld(),130,175,0));h.item.setVelocity(new org.bukkit.util.Vector());
        caster=h.a;
        if(text("kind").equals("passenger")) {
            if(path.equals("native"))h.active.get(h.a).getClass().getMethod("setLevel",double.class).invoke(h.active.get(h.a),(double)integer("level"));
            else {
                var cfg=new YamlConfiguration();cfg.set("model","example/emberling");cfg.set("entity.type","COW");cfg.set("entity.no-ai",true);cfg.set("entity.gravity",false);
                cfg.set("ai.level",integer("level"));cfg.set("persistent",false);cfg.save(h.runtime.getDataFolder().toPath().resolve("mobs/summoncaster.yml").toFile());
                int id=(int)h.runtime.getClass().getMethod("spawnMobAt",String.class,Location.class,String.class).invoke(h.runtime,"summoncaster",h.a.getLocation(),null);
                runtimeCaster=instances().getClass().getMethod("impl",int.class).invoke(instances(),id);
                caster=(Entity)((Optional<?>)h.modelType.getMethod("baseEntity").invoke(runtimeCaster)).orElseThrow();
            }
            Entity parent=caster;
            for(int i=0;i<integer("seed");i++){var seed=origin.getWorld().spawn(caster.getLocation(),Pig.class);seed.setAI(false);seed.setGravity(false);parent.setPassenger(seed);seeds.add(seed);parent=seed;}
        }
        if(text("kind").equals("fall")) {
            // Natural item velocity can carry a drop off a one-block support before
            // the settled snapshot. A real floor keeps its normal physics observable.
            for(int x=116;x<=124;x++)for(int z=-4;z<=4;z++)origin.getWorld().getBlockAt(x,165,z).setType(Material.STONE,false);
            if(Boolean.TRUE.equals(sample.get("torch")))origin.getWorld().getBlockAt(120,166,0).setType(Material.TORCH,false);
        }
        source=text("mechanic")+"{"+text("arguments")+"} @"+text("targeter");row=new YamlConfiguration();
        row.set("index",index);row.set("path",path);row.set("case",text("key"));row.set("source",source);row.set("primary",Bukkit.isPrimaryThread());
        row.set("caster",caster.getUniqueId().toString());row.set("target",h.b.getUniqueId().toString());row.set("trigger",h.item.getUniqueId().toString());
        row.set("origin",point(origin));row.set("casterLocation",point(caster.getLocation()));row.set("targetLocation",point(h.b.getLocation()));row.set("triggerLocation",point(h.item.getLocation()));
        row.set("seeds",seeds.stream().map(e->e.getUniqueId().toString()).toList());
        row.set("startTick",Bukkit.getCurrentTick());
        Set<UUID> before=new HashSet<>();for(Entity e:origin.getWorld().getEntities())before.add(e.getUniqueId());
        observing=true;String result;
        try{result=cast();}finally{observing=false;}
        for(Entity e:origin.getWorld().getEntities())if(!before.contains(e.getUniqueId())&&(e instanceof AreaEffectCloud||e instanceof FallingBlock||e instanceof Mob))children.add(e);
        row.set("result",result);row.set("after",snapshot());row.set("events",events);
        later(Math.max(1,integer("wait")),()->{row.set("settled",snapshot());row.set("endTick",Bukkit.getCurrentTick());
            Files.createDirectories(getDataFolder().toPath().resolve("observations"));row.save(getDataFolder().toPath().resolve(String.format("observations/%03d.yml",index)).toFile());
            index++;later(1,this::phase);});
    }
    private String cast()throws Exception {
        row.set("mapped",source);
        if(path.equals("native")) {
            if(nativeActions.get(index/3)==null)return "CONSTRUCTOR:"+constructionErrors.get(index/3);
            Object meta=h.meta(h.a,h.item),action=nativeActions.get(index/3);
            try {
                Object result;
                if(text("kind").equals("passenger"))result=action.getClass().getMethod("cast",h.skillMetadata).invoke(action,meta);
                else if(text("targeter").equals("origin")) {
                    var abstractLocation=h.nativeType("io.lumine.mythic.api.adapters.AbstractLocation");
                    Object at=h.nativeType("io.lumine.mythic.bukkit.BukkitAdapter").getMethod("adapt",Location.class).invoke(null,origin);
                    result=action.getClass().getMethod("castAtLocation",h.skillMetadata,abstractLocation).invoke(action,meta,at);
                }else result=action.getClass().getMethod("castAtEntity",h.skillMetadata,h.abstractEntity).invoke(action,meta,h.adapt.invoke(null,text("targeter").equals("trigger")?h.item:h.b));
                return result.toString();
            }catch(InvocationTargetException e){return "ERROR:"+e.getCause().getClass().getSimpleName();}
        }
        String mapped=source;
        if(path.equals("imported")) {
            var input=new YamlConfiguration();input.set("Audit.Skills",List.of(source));input.save(h.source.toFile());
            @SuppressWarnings("unchecked")var plans=(List<Map<String,Object>>)h.importer.getClass().getMethod("convert",java.nio.file.Path.class).invoke(h.importer,h.source);
            var plan=plans.stream().filter(p->"skills.yml".equals(p.get("target-file"))).findFirst().orElseThrow();
            var cfg=new YamlConfiguration();cfg.loadFromString(plan.get("content").toString());
            var lines=cfg.getStringList("skills.audit.on-decision");
            if(lines.isEmpty()){row.set("mapped","UNSUPPORTED");return "UNSUPPORTED";}if(lines.size()!=1)throw new IllegalStateException("wrong import count");mapped=lines.getFirst();
        }
        row.set("mapped",mapped);Object key=h.skills.getClass().getMethod("bindAction",String.class).invoke(h.skills,mapped);if(key==null)return "UNBOUND";
        Object tree=h.newTree(h.item,h.b,origin),context;
        if(runtimeCaster==null)context=h.context(caster,h.b,h.item,origin,tree);
        else context=h.contextType.getMethod("continueExecution",h.modelType,LivingEntity.class,double.class,double.class,long.class,String.class,String.class,Entity.class,List.class,Location.class,h.treeType)
                .invoke(null,runtimeCaster,h.b,1d,0d,0L,"summons",null,h.item,List.of(h.b),origin,tree);
        if(!(boolean)h.skills.getClass().getMethod("runAction",String.class,h.contextType).invoke(h.skills,key,context))throw new IllegalStateException("action failed to execute");
        return "EXECUTED";
    }
    private static List<Double> point(Location at){return List.of(at.getX(),at.getY(),at.getZ());}
    private Object particle(AreaEffectCloud cloud)throws Exception {
        Object handle=cloud.getClass().getMethod("getHandle").invoke(cloud);
        Object data=handle.getClass().getMethod("getParticle").invoke(handle);
        Class<?> type=data.getClass();
        if(cloud.getParticle().getDataType()==Void.class)return "none";
        return switch(cloud.getParticle()) {
            case DUST -> Map.of("color",vector(type.getMethod("getColor").invoke(data)),"size",type.getMethod("getScale").invoke(data));
            case DUST_COLOR_TRANSITION -> Map.of("from",vector(type.getMethod("getFromColor").invoke(data)),"to",vector(type.getMethod("getToColor").invoke(data)),"size",type.getMethod("getScale").invoke(data));
            case ENTITY_EFFECT -> List.of(type.getMethod("getRed").invoke(data),type.getMethod("getGreen").invoke(data),type.getMethod("getBlue").invoke(data),type.getMethod("getAlpha").invoke(data));
            case SHRIEK -> type.getMethod("getDelay").invoke(data);
            case ITEM -> {
                Object nms=type.getMethod("getItem").invoke(data);
                ItemStack stack=(ItemStack)Class.forName("org.bukkit.craftbukkit.inventory.CraftItemStack").getMethod("asBukkitCopy",nms.getClass()).invoke(null,nms);
                yield stack.getType().name();
            }
            case FALLING_DUST, BLOCK -> {
                Object nms=type.getMethod("getState").invoke(data);
                var block=(org.bukkit.block.data.BlockData)Class.forName("org.bukkit.craftbukkit.block.data.CraftBlockData").getMethod("fromData",nms.getClass()).invoke(null,nms);
                yield block.getAsString();
            }
            default -> throw new IllegalStateException("Unobserved particle data: "+cloud.getParticle());
        };
    }
    private static List<Float> vector(Object value){var v=(org.joml.Vector3f)value;return List.of(v.x,v.y,v.z);}
    private List<Map<String,Object>> effects(Collection<PotionEffect> effects){return effects.stream().map(e->Map.<String,Object>of("type",e.getType().getKey().toString(),"duration",e.getDuration(),"amplifier",e.getAmplifier(),"ambient",e.isAmbient(),"particles",e.hasParticles(),"icon",e.hasIcon())).toList();}
    private Map<String,Object> snapshot()throws Exception {
        var entities=new ArrayList<Map<String,Object>>();
        for(Entity entity:children) {
            var data=new LinkedHashMap<String,Object>();data.put("uuid",entity.getUniqueId().toString());data.put("entityId",entity.getEntityId());data.put("type",entity.getType().name());
            data.put("valid",entity.isValid());data.put("location",point(entity.getLocation()));data.put("ticks",entity.getTicksLived());
            if(entity instanceof AreaEffectCloud cloud){
                data.put("particle",cloud.getParticle().name());data.put("particleData",particle(cloud));data.put("effects",effects(cloud.getCustomEffects()));
                data.put("duration",cloud.getDuration());data.put("durationOnUse",cloud.getDurationOnUse());data.put("radius",cloud.getRadius());data.put("radiusOnUse",cloud.getRadiusOnUse());
                data.put("radiusPerTick",cloud.getRadiusPerTick());data.put("wait",cloud.getWaitTime());data.put("reapplication",cloud.getReapplicationDelay());data.put("source",cloud.getSource()==null?"none":cloud.getSource().toString());
            }else if(entity instanceof FallingBlock block){data.put("block",block.getBlockData().getAsString());data.put("drop",block.getDropItem());data.put("hurt",block.canHurtEntities());}
            else {data.put("owner",owner(entity));data.put("parent",parent(entity));data.put("level",level(entity));data.put("vehicle",entity.getVehicle()==null?"none":entity.getVehicle().getUniqueId().toString());
                if(entity instanceof Tameable tameable)data.put("tamed",tameable.isTamed());}
            entities.add(data);
        }
        var passengers=new LinkedHashMap<String,Object>();passengers.put("caster",caster.getPassengers().stream().map(e->e.getUniqueId().toString()).toList());
        for(int i=0;i<seeds.size();i++)passengers.put("seed"+i,seeds.get(i).getPassengers().stream().map(e->e.getUniqueId().toString()).toList());
        var drops=new ArrayList<String>();for(Entity e:origin.getWorld().getNearbyEntities(origin,3,10,3))if(e instanceof Item item)drops.add(item.getItemStack().getType().name()+":"+item.getItemStack().getAmount());
        return Map.of("entities",entities,"passengers",passengers,"victimEffects",effects(h.b.getActivePotionEffects()),"landingBlock",origin.getWorld().getBlockAt(120,166,0).getBlockData().getAsString(),"drops",drops);
    }
}
