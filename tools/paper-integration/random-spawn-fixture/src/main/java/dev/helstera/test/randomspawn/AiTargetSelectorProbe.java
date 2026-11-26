package dev.helstera.test.randomspawn;

import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import com.destroystokyo.paper.entity.ai.*;
import java.nio.file.*;
import java.lang.reflect.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import java.util.*;

/** Records the loaded public native catalog before comparing target goal execution. */
public final class AiTargetSelectorProbe extends JavaPlugin implements Listener {
    private VariablePlaceholderAudit.Harness h;
    private Player observer;
    private Location origin;
    private List<Map<?,?>> cases;
    private Map<?,?> sample;
    private String path;
    private int index, stage, stops, starts;
    private Entity caster, monster;
    private Object sentinel;
    private Object instance,controller,instances,ai,handler;
    private YamlConfiguration row;
    private final List<Entity> owned=new ArrayList<>();
    private final List<Map<String,Object>> events=new ArrayList<>();
    private final Map<UUID,String> roles=new HashMap<>();
    private final List<Object> nativeActions=new ArrayList<>();
    private interface Task {void run()throws Exception;}
    private void later(long ticks,Task task){Bukkit.getScheduler().runTaskLater(this,()->{try{task.run();}catch(Throwable error){getLogger().severe("AI_TARGET_FAILED|"+error);error.printStackTrace();}},ticks);}
    private static Object call(Object value,String name)throws Exception{return value.getClass().getMethod(name).invoke(value);}
    private Object handle(Entity entity)throws Exception{return call(entity,"getHandle");}
    private void seedRandom()throws Exception {
        if(!(caster instanceof Mob))return;
        // Paper's shared source explicitly ignores setSeed. Replace only this fixture entity's source.
        Field random=Class.forName("net.minecraft.world.entity.Entity").getField("random");random.setAccessible(true);
        random.set(handle(caster),Class.forName("net.minecraft.util.RandomSource").getMethod("create",long.class).invoke(null,702L));
    }
    private Object field(Object value,String name)throws Exception{for(Class<?> c=value.getClass();c!=null;c=c.getSuperclass())try{Field f=c.getDeclaredField(name);f.setAccessible(true);return f.get(value);}catch(NoSuchFieldException ignored){}throw new NoSuchFieldException(name);}
    private String role(Entity entity){return entity==null?"none":roles.getOrDefault(entity.getUniqueId(),entity.getType().name());}
    @EventHandler public void target(EntityTargetLivingEntityEvent event){if(event.getEntity()!=caster)return;events.add(Map.of("target",role(event.getTarget()),"reason",event.getReason().name(),"cancelled",event.isCancelled(),"tick",Bukkit.getCurrentTick()));}

    @Override public void onEnable() {
        Bukkit.getScheduler().runTaskLater(this, () -> {
            try { survey(); if(new java.io.File(getDataFolder(), "cases.yml").exists()){Bukkit.getPluginManager().registerEvents(this,this);begin();}else getLogger().info("AI_TARGET_SURVEY_DONE"); }
            catch (Exception failure) { getLogger().severe("AI_TARGET_FAILED|" + failure); failure.printStackTrace(); }
        }, 100);
    }
    private void survey() throws Exception {
        var plugin = Bukkit.getPluginManager().getPlugin("MythicMobs");
        var loader = plugin.getClass().getClassLoader();
        Object volatileCode = plugin.getClass().getMethod("getVolatileCodeHandler").invoke(plugin);
        Object handler = volatileCode.getClass().getMethod("getAIHandler").invoke(volatileCode);
        var field = handler.getClass().getDeclaredField("AI_TARGETS"); field.setAccessible(true);
        var result = new YamlConfiguration();
        for (var entry : ((Map<?, ?>) field.get(handler)).entrySet())
            result.set("catalog." + entry.getKey(), ((Class<?>) entry.getValue()).getSimpleName());
        var config = Class.forName("io.lumine.mythic.core.config.MythicLineConfigImpl", true, loader);
        int index = 0;
        for (String value : List.of("players", "1 players", "\"1 players\"", "'1 players'",
                "nearestConditionalTarget{conditions=[ - isplayer true ];i=0}",
                " 1 players", "1  players", "specificFaction{f=Red,Blue}")) {
            String line = "runaitargetselector{target=" + value + "}";
            Object parsed = config.getConstructor(String.class).newInstance(line);
            String raw = (String) config.getMethod("getString", String[].class, String.class, String[].class)
                    .invoke(parsed, new String[]{"aitargetselector", "targetselector", "target", "string", "s"}, null, new String[0]);
            result.set("strings.c" + index + ".source", line);
            result.set("strings.c" + index + ".raw", raw);
            result.set("strings.c" + index + ".unparsed", config.getMethod("unparseBlock", String.class).invoke(null, raw));
            index++;
        }
        getDataFolder().mkdirs(); result.save(new java.io.File(getDataFolder(), "survey.yml"));
    }
    private void begin()throws Exception {
        observer=Bukkit.getPlayerExact("AiObserver");if(observer==null){later(5,this::begin);return;}
        observer.setGameMode(GameMode.SURVIVAL);observer.setGravity(false);observer.setInvulnerable(false);
        var world=Bukkit.getWorlds().getFirst();world.setGameRule(GameRule.DO_MOB_SPAWNING,false);world.setTime(18000);
        origin=new Location(world,100,170,0);
        for(int x=94;x<=116;x++)for(int z=-5;z<=5;z++)world.getBlockAt(x,169,z).setType(Material.STONE,false);
        world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE,false);observer.teleport(origin.clone().add(4,0,0));
        for(int x=5;x<=8;x++)for(int z=-1;z<=0;z++){world.getChunkAt(x,z).setForceLoaded(true);world.getChunkAt(x,z).getEntities();}
        h=new VariablePlaceholderAudit.Harness(this,Bukkit.getPluginManager().getPlugin("helsteraMobs"),Bukkit.getPluginManager().getPlugin("MythicMobs"),new Location(world,0,150,0),false);
        instances=call(h.runtime,"instances");ai=call(h.runtime,"ai");handler=call(call(h.mythic,"getVolatileCodeHandler"),"getAIHandler");
        h.a.teleport(origin.clone().add(80,0,0));h.c.teleport(origin.clone().add(80,0,0));h.item.teleport(origin.clone().add(80,0,0));
        cases=YamlConfiguration.loadConfiguration(new java.io.File(getDataFolder(),"cases.yml")).getMapList("cases");
        next();
    }
    private Object profile(List<String> targets)throws Exception {
        YamlConfiguration cfg=new YamlConfiguration();cfg.set("native-targets",true);cfg.set("targets",targets);
        cfg.set("can-attack",false);cfg.set("can-chase",false);cfg.set("can-patrol",false);cfg.set("goals",List.of("clear"));
        return h.runtimeType("dev.helstera.ai.AiProfile").getMethod("fromSection",String.class,org.bukkit.configuration.ConfigurationSection.class).invoke(null,"audit",cfg);
    }
    private Object bind(Entity entity)throws Exception {
        var optionsType=h.runtimeType("dev.helstera.api.instance.SpawnOptions");Object options=optionsType.getMethod("defaults").invoke(null);
        optionsType.getMethod("noAi",boolean.class).invoke(options,false);optionsType.getMethod("gravity",boolean.class).invoke(options,false);
        return instances.getClass().getMethod("bind",String.class,Entity.class,optionsType).invoke(instances,"example/emberling",entity,options);
    }
    private Entity nativeSpawn(String name,Location location)throws Exception {
        Object api=call(h.mythic,"getAPIHelper");Entity entity=(Entity)api.getClass().getMethod("spawnMythicMob",String.class,Location.class).invoke(api,name,location);
        owned.add(entity);return entity;
    }
    private void seed(Entity entity,String role){roles.put(entity.getUniqueId(),role);entity.setGravity(false);entity.setInvulnerable(false);entity.setSilent(true);if(entity instanceof LivingEntity living)living.setAI(false);}
    @SuppressWarnings("unchecked") private List<String> strings(String key){return (List<String>)sample.getOrDefault(key,null);}
    private void next()throws Exception {
        if(instance!=null){instances.getClass().getMethod("despawn",int.class).invoke(instances,h.modelType.getMethod("instanceId").invoke(instance));instance=null;controller=null;}
        for(Entity entity:owned){h.manager.getClass().getMethod("unregisterActiveMob",UUID.class).invoke(h.manager,entity.getUniqueId());entity.remove();}owned.clear();
        if(index>=cases.size()*3){observer.sendMessage("AI_TARGET_DONE");getLogger().info("AI_TARGET_SUMMARY|observations="+index+"|completed=true");return;}
        sample=cases.get(index/3);path=List.of("native","direct","imported").get(index%3);stage=0;stops=0;starts=0;sentinel=null;events.clear();roles.clear();nativeActions.clear();
        seed(observer,"players");observer.teleport(origin.clone().add(4,0,0));observer.setGameMode(GameMode.SURVIVAL);
        // New casters isolate selector running state and native damage timestamps between paths.
        String kind=String.valueOf(sample.get("caster"));
        if(Boolean.TRUE.equals(sample.get("generic"))){caster=origin.getWorld().spawnEntity(origin,EntityType.COW);owned.add(caster);}
        else caster=switch(kind){case "WOLF"->nativeSpawn("AiWolf",origin);case "SLIME"->nativeSpawn("AiSlime",origin);case "ARMOR_STAND"->nativeSpawn("AiStand",origin);case "PLAYER"->observer;default->nativeSpawn("HelsteraRadiusAuditMob",origin);};
        seed(caster,"caster");if(caster instanceof Mob mob){Bukkit.getMobGoals().removeAllGoals(mob);mob.setTarget(null);mob.setRemoveWhenFarAway(false);}
        seed(h.b,"cow");h.b.teleport(origin.clone().add(6,0,0));
        monster=nativeSpawn("AiMonster",origin.clone().add(8,0,0));seed(monster,"monsters");
        Entity villager=origin.getWorld().spawnEntity(origin.clone().add(10,0,0),EntityType.VILLAGER);owned.add(villager);seed(villager,"villagers");
        Entity golem=origin.getWorld().spawnEntity(origin.clone().add(12,0,0),EntityType.IRON_GOLEM);owned.add(golem);seed(golem,"golems");
        if(caster instanceof Wolf wolf)wolf.setOwner(observer);
        if(Boolean.TRUE.equals(sample.get("generic")))h.manager.getClass().getMethod("unregisterActiveMob",UUID.class).invoke(h.manager,caster.getUniqueId());
        if(!path.equals("native")&&caster instanceof Mob&&!Boolean.TRUE.equals(sample.get("generic"))){instance=bind(caster);Object profile=profile(strings("profile")==null?List.of():strings("profile"));
            if(path.equals("imported")&&strings("profile")!=null){
                var source=getDataFolder().toPath().resolve("source/Mobs/profile.yml");Files.createDirectories(source.getParent());
                var input=new YamlConfiguration();input.set("Guard.Type",kind);input.set("Guard.AITargetSelectors",strings("profile"));input.save(source.toFile());
                @SuppressWarnings("unchecked")var plans=(List<Map<String,Object>>)h.importer.getClass().getMethod("convert",Path.class).invoke(h.importer,source);
                var plan=plans.stream().filter(p->String.valueOf(p.get("target-file")).startsWith("mobs/")).findFirst().orElseThrow();
                var cfg=new YamlConfiguration();cfg.loadFromString(plan.get("content").toString());cfg.set("ai.goals",List.of("clear"));
                cfg.set("ai.can-attack",false);cfg.set("ai.can-chase",false);cfg.set("ai.can-patrol",false);
                Files.createDirectories(getDataFolder().toPath().resolve("imports"));cfg.save(getDataFolder().toPath().resolve("imports/"+index+".yml").toFile());
                profile=h.runtimeType("dev.helstera.ai.AiProfile").getMethod("fromSection",String.class,org.bukkit.configuration.ConfigurationSection.class).invoke(null,"audit",cfg.getConfigurationSection("ai"));
            }
            ai.getClass().getMethod("attach",h.runtimeType("dev.helstera.runtime.instance.ModelInstanceImpl"),h.runtimeType("dev.helstera.ai.AiProfile")).invoke(ai,instance,profile);
            controller=instance.getClass().getField("aiController").get(instance);
        }
        if(path.equals("native")&&strings("profile")!=null)handler.getClass().getMethod("addTargetGoals",LivingEntity.class,List.class).invoke(handler,caster,strings("profile"));
        if(Boolean.TRUE.equals(sample.get("factions"))){
            Object active=h.manager.getClass().getMethod("getMythicMobInstance",Entity.class).invoke(h.manager,caster);if(active!=null)active.getClass().getMethod("setFaction",String.class).invoke(active,"Red");
            Object factions=call(ai,"factions");factions.getClass().getMethod("bindInstance",UUID.class,String.class).invoke(factions,caster.getUniqueId(),"Red");
            factions.getClass().getMethod("bindInstance",UUID.class,String.class).invoke(factions,monster.getUniqueId(),"Blue");
            factions.getClass().getMethod("bindInstance",UUID.class,String.class).invoke(factions,h.b.getUniqueId(),"Red");
            h.active.get(h.b).getClass().getMethod("setFaction",String.class).invoke(h.active.get(h.b),"Red");
        }
        if(Boolean.TRUE.equals(sample.get("sentinel"))&&caster instanceof Mob mob){boolean interruptible=!Boolean.FALSE.equals(sample.get("interruptible"));
            sentinel=Class.forName("dev.helstera.test.randomspawn.PinnedTestGoal",true,getClassLoader()).getConstructor(Class.forName("net.minecraft.world.entity.Mob"),boolean.class).newInstance(handle(mob),interruptible);
            @SuppressWarnings("unchecked") var goal=(Goal<Mob>)call(sentinel,"asPaperVanillaGoal");Bukkit.getMobGoals().addGoal(mob,8,goal);
        }

        row=new YamlConfiguration();row.set("case",sample.get("key"));row.set("index",index);row.set("path",path);row.set("primary",Bukkit.isPrimaryThread());row.set("casterType",caster.getType().name());
        seedRandom();
        if(caster instanceof LivingEntity living)living.setAI(true);
        later(sample.containsKey("profile")?80:6,()->{seedDamage();row.set("baseline",snapshot());step();});
    }
    private void seedDamage()throws Exception {
        if(!sample.containsKey("damage"))return;
        String mode=String.valueOf(sample.get("damage"));Entity damaged=mode.equals("caster")?caster:observer;
        String method=mode.equals("owner_attack")?"setLastHurtMob":"setLastHurtByMob";
        Class<?> parameter=Class.forName("net.minecraft.world.entity."+(mode.equals("owner_attack")?"Entity":"LivingEntity"));
        handle(damaged).getClass().getMethod(method,parameter).invoke(handle(damaged),handle(monster));
    }
    @SuppressWarnings("unchecked") private void step()throws Exception {
        var stages=(List<Map<String,Object>>)sample.get("stages");
        if(stage>=stages.size()){
            row.set("eventsBeforeRebind",new ArrayList<>(events));
            if(sample.containsKey("rebind")&&!path.equals("native"))controller.getClass().getMethod("rebindProfile",h.runtimeType("dev.helstera.ai.AiProfile")).invoke(controller,profile(strings("rebind")));
            row.set("afterRebind",snapshot());
            if(controller!=null){call(controller,"tick");row.set("afterControllerTick",snapshot());call(controller,"close");row.set("afterClose",snapshot());}
            row.set("events",events);Files.createDirectories(getDataFolder().toPath().resolve("observations"));row.save(getDataFolder().toPath().resolve("observations/"+String.format("%03d",index)+".yml").toFile());
            index++;later(1,this::next);return;
        }
        var spec=stages.get(stage);String source=String.valueOf(spec.get("source"));String prefix="stages.s"+stage;
        seedRandom();
        row.set(prefix+".source",source);row.set(prefix+".before",snapshot());row.set(prefix+".result",cast(source,prefix));row.set(prefix+".immediate",snapshot());
        later(((Number)spec.get("wait")).intValue()+1,()->{row.set(prefix+".settled",snapshot());stage++;step();});
    }
    private String cast(String source,String prefix)throws Exception {
        if(path.equals("native")){
            Object action=h.nativeType("io.lumine.mythic.core.skills.mechanics.RunAITargetSelectorMechanic").getConstructor(h.executorType,java.io.File.class,String.class,h.configType).newInstance(h.nativeSkills,h.source.toFile(),source,h.configImpl.getConstructor(String.class).newInstance(source));
            Object active=h.manager.getClass().getMethod("getMythicMobInstance",Entity.class).invoke(h.manager,caster);
            if(active==null)active=h.nativeType("io.lumine.mythic.api.mobs.GenericCaster").getConstructor(h.abstractEntity).newInstance(h.adapt.invoke(null,caster));
            Object meta=h.metadataType.getConstructor(h.triggerType,h.skillCaster,h.abstractEntity).newInstance(h.triggerType.getMethod("get",String.class).invoke(null,"API"),active,h.adapt.invoke(null,h.b));
            try{return action.getClass().getMethod("cast",h.skillMetadata).invoke(action,meta).toString();}
            catch(InvocationTargetException failure){return "ERROR:"+failure.getCause().getClass().getSimpleName();}
        }
        String mapped=source;
        if(path.equals("imported")){
            var input=new YamlConfiguration();input.set("Audit.Skills",List.of(source));input.save(h.source.toFile());
            @SuppressWarnings("unchecked")var plans=(List<Map<String,Object>>)h.importer.getClass().getMethod("convert",Path.class).invoke(h.importer,h.source);
            var plan=plans.stream().filter(p->"skills.yml".equals(p.get("target-file"))).findFirst().orElseThrow();
            var config=new YamlConfiguration();config.loadFromString(plan.get("content").toString());var lines=config.getStringList("skills.audit.on-decision");if(lines.size()!=1)throw new IllegalStateException("Wrong import count");mapped=lines.getFirst();
        }
        row.set(prefix+".mapped",mapped);Object key=h.skills.getClass().getMethod("bindAction",String.class).invoke(h.skills,mapped);if(key==null)return "UNBOUND";
        Object tree=h.newTree(h.b,h.b,origin),ctx=instance==null?h.context(caster,h.b,h.b,origin,tree):h.contextType.getMethod("continueExecution",h.modelType,LivingEntity.class,double.class,double.class,long.class,String.class,String.class,Entity.class,List.class,Location.class,h.treeType).invoke(null,instance,h.b,1d,0d,0L,"ai",null,h.b,List.of(h.b),origin,tree);
        if(!(boolean)h.skills.getClass().getMethod("runAction",String.class,h.contextType).invoke(h.skills,key,ctx))throw new IllegalStateException("Runtime action failed");return "EXECUTED";
    }
    private Map<String,Object> snapshot()throws Exception {
        Map<String,Object> result=new LinkedHashMap<>();result.put("tick",Bukkit.getCurrentTick());result.put("target",caster instanceof Mob mob?role(mob.getTarget()):"none");result.put("starts",sentinel==null?0:field(sentinel,"starts"));result.put("stops",sentinel==null?0:field(sentinel,"stops"));
        result.put("recipientTarget",role(h.b.getTarget()));
        List<Map<String,Object>> goals=new ArrayList<>();
        if(caster instanceof Mob){Object selector=field(handle(caster),"targetSelector");
            for(Object wrapper:(Set<?>)call(selector,"getAvailableGoals")){
                Object goal=call(wrapper,"getGoal");Map<String,Object> g=new LinkedHashMap<>();g.put("class",goal.getClass().getSimpleName());g.put("priority",call(wrapper,"getPriority"));g.put("running",call(wrapper,"isRunning"));
                if(goal.getClass().getSimpleName().equals("NearestAttackableTargetGoal")){g.put("targetType",((Class<?>)field(goal,"targetType")).getSimpleName());g.put("interval",field(goal,"randomInterval"));g.put("mustSee",field(goal,"mustSee"));g.put("mustReach",field(goal,"mustReach"));}
                g.put("types",((Goal<?>)call(goal,"asPaperVanillaGoal")).getTypes().stream().map(Enum::name).sorted().toList());goals.add(g);
            }
        }
        result.put("goals",goals);if(controller!=null)result.put("controllerTarget",role((Entity)call(controller,"targetEntity")));return result;
    }
}
