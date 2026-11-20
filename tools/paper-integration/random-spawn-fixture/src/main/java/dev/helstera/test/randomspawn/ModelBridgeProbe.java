package dev.helstera.test.randomspawn;

import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import java.lang.reflect.*;
import java.util.*;

/** Real native skill dispatch, condition inversion, automatic binding and retained-proxy lifecycle. */
public final class ModelBridgeProbe extends JavaPlugin implements Listener {
    private Plugin runtime, mythic; private Object adapter, manager, helper, instances, skillManager;
    private Entity caster,target,cancelledEntity; private Object cachedMechanic,conditionTrue,conditionFalse,liveMechanic; private Event autoEvent;
    private final Map<String,Integer> hooks=new HashMap<>(); private final List<Entity> entities=new ArrayList<>();
    private World world; private boolean failed; private int rows;
    @FunctionalInterface private interface Step {void run() throws Exception;}
    private void later(long ticks,Step step){Bukkit.getScheduler().runTaskLater(this,()->{if(!failed)try{step.run();}catch(Throwable e){failed=true;getLogger().severe("MODEL_FAILED|error="+e);e.printStackTrace();}},ticks);}
    private Class<?> nt(String name)throws Exception{return Class.forName("io.lumine.mythic."+name,true,mythic.getClass().getClassLoader());}
    private static Object call(Object object,String method)throws Exception{return object.getClass().getMethod(method).invoke(object);}
    private void row(String key,String details){getLogger().info("MODEL_ROW|key="+key+"|"+details+"|tick="+Bukkit.getCurrentTick());rows++;}
    @Override public void onEnable(){
        runtime=Bukkit.getPluginManager().getPlugin("helsteraMobs");mythic=Bukkit.getPluginManager().getPlugin("MythicMobs");
        try {
            Class<? extends Event> load=nt("bukkit.events.MythicMechanicLoadEvent").asSubclass(Event.class);
            Bukkit.getPluginManager().registerEvent(load,this,EventPriority.MONITOR,(l,e)->{
                try {String name=call(e,"getMechanicName").toString();String line=call(call(e,"getConfig"),"getLine").toString();
                    getLogger().info("MODEL_LOAD|kind=mechanic|name="+name+"|line="+line+"|source="+call(call(e,"getContainer"),"getConfigLine")+"|registered="+((Optional<?>)call(e,"getMechanic")).isPresent());
                    if(name.equalsIgnoreCase("modelscale")&&line.equals("modelscale{scale=3}"))liveMechanic=((Optional<?>)call(e,"getMechanic")).orElse(null);
                    if(cachedMechanic==null&&name.equalsIgnoreCase("modelscale")&&line.contains("m=2"))cachedMechanic=((Optional<?>)call(e,"getMechanic")).orElse(null);
                }catch(Exception x){throw new EventException(x);}
            },this);
            Class<? extends Event> conditionLoad=nt("bukkit.events.MythicConditionLoadEvent").asSubclass(Event.class);
            Bukkit.getPluginManager().registerEvent(conditionLoad,this,EventPriority.MONITOR,(l,e)->{
                try {getLogger().info("MODEL_LOAD|kind=condition|name="+call(e,"getConditionName")+"|line="+call(call(e,"getConfig"),"getLine")+"|source="+call(call(e,"getContainer"),"getLine")+"|registered="+((Optional<?>)call(e,"getCondition")).isPresent());}
                catch(Exception x){throw new EventException(x);}
            },this);
            Class<? extends Event> spawn=nt("bukkit.events.MythicMobSpawnEvent").asSubclass(Event.class);
            Bukkit.getPluginManager().registerEvent(spawn,this,EventPriority.MONITOR,(l,e)->{
                try {String name=call(call(e,"getMobType"),"getInternalName").toString();
                    if(name.equals("MbAuto"))autoEvent=e;
                    if(name.equals("MbCancelled")){cancelledEntity=(Entity)call(e,"getEntity");((Cancellable)e).setCancelled(true);}
                }catch(Exception x){throw new EventException(x);}
            },this);
            later(100,this::begin);
        }catch(Exception e){failed=true;getLogger().severe("MODEL_FAILED|error="+e);}
    }
    private Object instance(Entity entity)throws Exception{
        for(Object i:(Collection<?>)call(instances,"allImpl"))if(((Optional<?>)call(i,"boundEntityId")).filter(entity.getUniqueId()::equals).isPresent())return i;
        return null;
    }
    private long bindings(Entity entity)throws Exception{
        long n=0;for(Object i:(Collection<?>)call(instances,"allImpl"))if(((Optional<?>)call(i,"boundEntityId")).filter(entity.getUniqueId()::equals).isPresent())n++;return n;
    }
    private void state(String key,Entity entity)throws Exception{
        Object i=instance(entity),animation=i==null?null:call(i,"animation");
        row(key,"uuid="+entity.getUniqueId()+"|caster="+caster.getUniqueId()+"|bindings="+bindings(entity)
            +"|casterBindings="+bindings(caster)+"|scale="+(i==null?"absent":call(i,"getScale"))
            +"|animation="+(animation==null?"absent":((Optional<?>)call(animation,"currentAnimation")).map(Object::toString).orElse("absent"))
            +"|state="+(animation==null?"absent":call(animation,"state"))+"|valid="+entity.isValid());
    }
    private void capabilities(String key)throws Exception{
        Set<?> caps=(Set<?>)call(adapter,"capabilities");Set<String> names=new HashSet<>();for(Object c:caps)names.add(c.toString());
        row(key,"skills="+names.contains("SKILLS")+"|conditions="+names.contains("CONDITIONS")+"|binding="+names.contains("MOB_MODEL_BINDING"));
        getLogger().info("MODEL_STATUS|key="+key+"|report="+call(adapter,"statusReport"));
    }
    private Entity spawn(String name)throws Exception{
        Entity e=(Entity)helper.getClass().getMethod("spawnMythicMob",String.class,Location.class).invoke(helper,name,new Location(world,entities.size()*3,120,0));
        if(e!=null){e.setGravity(false);entities.add(e);}return e;
    }
    private void cast(String name)throws Exception{
        helper.getClass().getMethod("castSkill",Entity.class,String.class,Entity.class,Location.class,Collection.class,Collection.class,float.class)
            .invoke(helper,caster,"mb_"+name,target,caster.getLocation(),List.of(target),List.of(),1F);
    }
    private Object wrap(Entity entity)throws Exception{return nt("bukkit.BukkitAdapter").getMethod("adapt",Entity.class).invoke(null,entity);}
    private Object evaluate(Object condition)throws Exception{
        return nt("core.skills.SkillCondition").getMethod("evaluateEntity",nt("api.adapters.AbstractEntity")).invoke(condition,wrap(target));
    }
    private Object cached()throws Exception{ return cached(cachedMechanic); }
    private Object cached(Object mechanic)throws Exception{
        return nt("api.skills.ITargetedEntitySkill").getMethod("castAtEntity",nt("api.skills.SkillMetadata"),nt("api.adapters.AbstractEntity")).invoke(mechanic,null,wrap(target));
    }
    private void gate(String phase,String kind,String name,String suffix)throws Exception{
        String k=kind+"_"+name+"_"+suffix;hooks.remove(k);cast(k);row(phase+"_"+k,"hooks="+hooks.getOrDefault(k,0));
    }
    private void gates(String phase,boolean playing)throws Exception{
        for(String kind:List.of("caster","target"))for(String name:playing?List.of("spawned","removed","playing","walk"):List.of("spawned","removed","playing"))
            for(String suffix:List.of("true","false"))gate(phase,kind,name,suffix);
    }
    private void begin()throws Exception{
        instances=call(runtime,"instances");Object registry=call(runtime,"integrationRegistry");
        adapter=((Optional<?>)registry.getClass().getMethod("get",String.class).invoke(registry,"MythicMobs")).orElseThrow();
        helper=call(mythic,"getAPIHelper");manager=call(mythic,"getMobManager");skillManager=call(mythic,"getSkillManager");
        world=Bukkit.getWorlds().getFirst();world.setGameRule(GameRule.DO_MOB_SPAWNING,false);world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE,false);
        caster=spawn("MbCaster");target=world.spawn(new Location(world,8,120,0),Husk.class,e->{e.setAI(false);e.setGravity(false);e.setInvulnerable(true);e.setPersistent(true);});entities.add(target);
        capabilities("cold_capabilities");state("initial",target);gates("pre",false);
        for(String skill:List.of("bad_model","bad_spawn_animation")){cast(skill);state(skill,target);}
        cast("spawn");state("spawn",target);cast("spawn");state("repeat_spawn",target);
        cast("scale");state("scale",target);cast("play");state("play",target);gates("playing",true);
        for(String name:List.of("unknown","invalid"))for(String suffix:List.of("true","false"))gate("playing","target",name,suffix);
        call(call(instance(target),"animation"),"pause");
        gate("paused","target","playing","true");gate("paused","target","playing","false");
        call(call(instance(target),"animation"),"resume");
        cast("stop_other");state("stop_other",target);cast("stop_named");state("stop_named",target);cast("play");
        cast("stop");state("stop",target);cast("native_ignored_tail");state("native_ignored_tail",target);
        hooks.remove("unsupported_action");cast("unsupported_action");row("unsupported_action","hooks="+hooks.getOrDefault("unsupported_action",0));
        for(String skill:List.of("bad_animation","bad_scale","duplicate","alias_conflict","bad_boolean","bad_integer","bad_key","empty_target")){cast(skill);state(skill,target);}
        conditionTrue=skillManager.getClass().getMethod("getCondition",String.class).invoke(skillManager,"modelremoved true");
        conditionFalse=skillManager.getClass().getMethod("getCondition",String.class).invoke(skillManager,"modelremoved false");
        Entity auto=spawn("MbAuto"),unmatched=spawn("MbUnmatched"),badScale=spawn("MbBadScale"),badAnimation=spawn("MbBadAnimation");
        try { spawn("MbCancelled"); }
        catch (InvocationTargetException e) {
            if (!(e.getCause() instanceof NullPointerException) || cancelledEntity == null) throw e;
            getLogger().info("MODEL_NATIVE_EXCEPTION|case=cancelled-spawn|type=NullPointerException");
        }
        later(5,()->{
            state("automatic",auto);Bukkit.getPluginManager().callEvent(autoEvent);Bukkit.getPluginManager().callEvent(autoEvent);
            later(2,()->state("automatic_duplicate",auto));state("unmatched",unmatched);state("invalid_auto_scale",badScale);state("invalid_auto_animation",badAnimation);
            row("cancelled_auto","uuid="+cancelledEntity.getUniqueId()+"|bindings="+bindings(cancelledEntity)+"|valid="+cancelledEntity.isValid());
            cast("spawn"); // Existing binding remains stable; reset scale before async stimulus.
            Object i=instance(target);i.getClass().getMethod("setScale",double.class).invoke(i,1.25);
            Bukkit.getScheduler().runTaskAsynchronously(this,()->{
                try{cast("scale");row("async_condition_true","result="+evaluate(conditionTrue));row("async_condition_false","result="+evaluate(conditionFalse));
                    later(5,this::lifecycle);
                }catch(Throwable e){failed=true;getLogger().severe("MODEL_FAILED|error="+e);e.printStackTrace();}
            });
        });
    }
    private void lifecycle()throws Exception{
        state("async_mechanic",target);
        if(cachedMechanic==null)throw new IllegalStateException("Cold native proxy not captured");
        Object i=instance(target);i.getClass().getMethod("setScale",double.class).invoke(i,1.75);
        call(adapter,"disable");capabilities("disabled_capabilities");
        row("disabled_condition_true","result="+evaluate(conditionTrue));row("disabled_condition_false","result="+evaluate(conditionFalse));
        row("disabled_cached","result="+cached());cast("scale");state("disabled_after_cast",target);
        adapter.getClass().getMethod("enable",Plugin.class).invoke(adapter,runtime);adapter.getClass().getMethod("enable",Plugin.class).invoke(adapter,runtime);capabilities("reenabled_capabilities");row("reenabled_cached","result="+cached());cast("scale");state("reenabled_after_cast",target);
        var file=mythic.getDataFolder().toPath().resolve("Skills/model-bridge.yml").toFile();var source=YamlConfiguration.loadConfiguration(file);
        source.set("mb_scale.Skills",List.of("modelscale{scale=3} @trigger"));source.save(file);
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(),"mythicmobs reload");
        later(60,()->{
            capabilities("reloaded_capabilities");cast("scale");state("reload_scale",target);row("reloaded_old_proxy","result="+cached());
            state("after_old_proxy",target);
            if(liveMechanic==null)throw new IllegalStateException("Live scale proxy missing");
            source.set("mb_scale.Skills",List.of("modelscale{scale=4} @trigger"));source.save(file);
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(),"mythicmobs reload");
            later(60,()->{
                row("live_reload_old_proxy","result="+cached(liveMechanic));
                cast("scale");state("live_reload_scale",target);
                cast("remove");state("removed",target);cast("remove");state("repeat_remove",target);gates("removed",false);cleanup();
            });
        });
    }
    private void cleanup()throws Exception{
        for(Entity e:entities){Object i=instance(e);if(i!=null)instances.getClass().getMethod("despawn",int.class).invoke(instances,((Number)call(i,"instanceId")).intValue());e.remove();}
        later(5,()->{
            long visuals=world.getEntities().stream().filter(e->e instanceof Display||e instanceof Interaction).count();
            Field bridgeField=adapter.getClass().getDeclaredField("mechanics");bridgeField.setAccessible(true);Object bridge=bridgeField.get(adapter);
            Field pending=bridge.getClass().getDeclaredField("pending");pending.setAccessible(true);
            Field autoPending=adapter.getClass().getDeclaredField("spawnTasks");autoPending.setAccessible(true);
            row("cleanup","bindings="+call(instances,"activeCount")+"|visuals="+visuals+"|ownedTasks="+(((Set<?>)pending.get(bridge)).size()+((Set<?>)autoPending.get(adapter)).size()));
            getLogger().info("MODEL_SUMMARY|rows="+rows+"|completed=true");
        });
    }
    @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] args){
        if(args.length==2&&args[0].equals("mark")){hooks.merge(args[1],1,Integer::sum);getLogger().info("MODEL_HOOK|key="+args[1]+"|thread="+Bukkit.isPrimaryThread());return true;}return false;
    }
}
