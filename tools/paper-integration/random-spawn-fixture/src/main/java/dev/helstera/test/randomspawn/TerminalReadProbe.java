package dev.helstera.test.randomspawn;

import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.world.EntitiesUnloadEvent;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import java.nio.file.*;
import java.util.*;

/** Observe actual native/runtime role reads after terminal events, without imposing expected parity. */
public final class TerminalReadProbe extends JavaPlugin implements Listener {
    private static final int Z=256;
    private static final List<Integer> POINT_TICKS=List.of(2,9,70);
    private Plugin runtime,mythic; private Object manager,service; private World world;
    private Class<?> extras,registryType;
    private final Map<String,UUID> parents=new LinkedHashMap<>();
    private final Map<String,LivingEntity> peers=new LinkedHashMap<>();
    private final Map<String,Integer> unloadTicks=new HashMap<>();
    private List<String> cases; private String key,scenario; private int index,rows; private boolean failed,cleaning,executed;
    @FunctionalInterface private interface Step{void run()throws Exception;}
    private void fail(Throwable e){failed=true;getLogger().severe("TREAD_FAILED|key="+key+"|error="+e);e.printStackTrace();}
    private void later(long ticks,Step s){Bukkit.getScheduler().runTaskLater(this,()->{if(!failed)try{s.run();}catch(Throwable e){fail(e);}},ticks);}
    private Class<?> rt(String n)throws Exception{return Class.forName(n,true,runtime.getClass().getClassLoader());}
    private Class<?> nt(String n)throws Exception{return Class.forName(n,true,mythic.getClass().getClassLoader());}
    private Object instances()throws Exception{return runtime.getClass().getMethod("instances").invoke(runtime);}
    private Object instance(UUID id)throws Exception{
        for(Object i:(Collection<?>)instances().getClass().getMethod("allImpl").invoke(instances()))
            if(((Optional<?>)i.getClass().getMethod("boundEntityId").invoke(i)).filter(id::equals).isPresent())return i;
        return null;
    }
    private Object active(UUID id)throws Exception{return ((Optional<?>)manager.getClass().getMethod("getActiveMob",UUID.class).invoke(manager,id)).orElse(null);}
    private int x(String backend){return backend.equals("native")?256:512;}
    @Override public void onEnable(){
        runtime=Bukkit.getPluginManager().getPlugin("helsteraMobs");mythic=Bukkit.getPluginManager().getPlugin("MythicMobs");
        if(getConfig().getBoolean("boundary")||getConfig().getBoolean("condition-location"))try{
            // A public read-only placeholder records evaluation time, separately
            // from the later console callback. Never alter native scheduling.
            Object placeholders=mythic.getClass().getMethod("getPlaceholderManager").invoke(mythic);
            Class<?> placeholder=nt("io.lumine.mythic.core.skills.placeholders.Placeholder");
            java.util.function.BiFunction<Object,String,String> stamp=(meta,arg)->{
                try{
                    Object caster=meta.getClass().getMethod("getCaster").invoke(meta);
                    Object entity=caster.getClass().getMethod("getEntity").invoke(caster);
                    UUID uuid=(UUID)entity.getClass().getMethod("getUniqueId").invoke(entity);
                    return Bukkit.getCurrentTick()+","+(active(uuid)!=null);
                }catch(Exception e){throw new IllegalStateException(e);}
            };
            Object value=placeholder.getMethod("meta",java.util.function.BiFunction.class).invoke(null,stamp);
            placeholders.getClass().getMethod("register",String.class,placeholder).invoke(placeholders,"trstamp",value);
        }catch(Exception e){fail(e);return;}
        cases=getConfig().getStringList("cases");Bukkit.getPluginManager().registerEvents(this,this);later(100,this::begin);
    }
    private void begin()throws Exception{
        manager=mythic.getClass().getMethod("getMobManager").invoke(mythic);service=runtime.getClass().getMethod("skillService").invoke(runtime);
        extras=rt("dev.helstera.ai.skill.SkillExtras");registryType=rt("dev.helstera.api.behavior.ScopedVariableSnapshotStore$Registry");
        configure();next();
    }
    @SuppressWarnings("unchecked") private void configure()throws Exception{
        Object importer=rt("dev.helstera.migration.importer.MythicMobsImporter").getConstructor().newInstance();
        List<Map<String,Object>> plans=(List<Map<String,Object>>)importer.getClass().getMethod("convert",Path.class).invoke(importer,mythic.getDataFolder().toPath().resolve("Mobs/terminal-read.yml"));
        var source=YamlConfiguration.loadConfiguration(mythic.getDataFolder().toPath().resolve("Mobs/terminal-read.yml").toFile());
        for(String id:source.getKeys(false)){
            var cfg=new YamlConfiguration();
            if(id.equals("tr_peer")){
                cfg.set("entity.type","COW");cfg.set("entity.no-ai",true);cfg.set("entity.gravity",false);
                cfg.set("entity.invulnerable",true);cfg.set("mob.despawn","NEVER");cfg.set("model","example/emberling");
                cfg.save(runtime.getDataFolder().toPath().resolve("mobs/tr_peer.yml").toFile());
                continue;
            }
            if(id.endsWith("_imported")){
                cfg.loadFromString(plans.stream().filter(p->id.equals(p.get("source-mob-id"))&&p.containsKey("target-file")).findFirst().orElseThrow().get("content").toString().replace("hook native","hook runtime"));
            }else{
                cfg.set("entity.type",source.getString(id+".Type"));cfg.set("entity.no-ai",true);cfg.set("entity.gravity",false);cfg.set("entity.invulnerable",true);
                cfg.set("mob.despawn",source.getString(id+".Options.Despawn"));
                cfg.set("entity.health",20);cfg.set("mob.revive-health",20);
                List<String> spawn=new ArrayList<>();spawn.add(hook("pending",";delay=180"));
                if(id.startsWith("tr_control_"))spawn.add("skill{s=tr_terminal_direct;delay=10} @self");
                else {
                    String event=id.startsWith("tr_death_")||id.startsWith("tr_selfdeath_")||id.startsWith("tr_peerdeath_")||id.startsWith("tr_cancelled_")?"death":"despawn";
                    cfg.set("ai.triggers.on-"+event+".do",id.startsWith("tr_cancelled_")?List.of("cast-skill tr_terminal_direct","cancel-event"):List.of("cast-skill tr_terminal_direct"));
                }
                cfg.set("ai.triggers.on-spawn.do",spawn);
            }
            cfg.set("model","example/emberling");cfg.set("mob.despawn-distance",0);
            String outputId=id;
            Path file=runtime.getDataFolder().toPath().resolve("mobs/"+outputId+".yml");cfg.save(file.toFile());
            Path archive=getDataFolder().toPath().resolve("imports/"+outputId+".yml");Files.createDirectories(archive.getParent());cfg.save(archive.toFile());
        }
        List<Map<String,Object>> skills=(List<Map<String,Object>>)importer.getClass().getMethod("convert",Path.class).invoke(importer,mythic.getDataFolder().toPath().resolve("Skills/terminal-read.yml"));
        var cfg=new YamlConfiguration();cfg.loadFromString(skills.stream().filter(p->"skills.yml".equals(p.get("target-file"))).findFirst().orElseThrow().get("content").toString().replace("hook native","hook runtime"));
        // Exercise both original runtime mechanic spelling and production import output.
        var sourceSkills=YamlConfiguration.loadConfiguration(mythic.getDataFolder().toPath().resolve("Skills/terminal-read.yml").toFile());
        for(String name:sourceSkills.getKeys(false))if(name.endsWith("_direct")){
            cfg.set("skills."+name+".on-decision",sourceSkills.getStringList(name+".Skills").stream().map(a->a.replace("hook native","hook runtime")).toList());
            if(getConfig().getBoolean("condition-location"))for(var field:Map.of(
                    "Conditions","require","TargetConditions","target-conditions","TriggerConditions","trigger-conditions").entrySet()){
                List<String> conditions=sourceSkills.getStringList(name+"."+field.getKey());
                if(!conditions.isEmpty())cfg.set("skills."+name+"."+field.getValue(),conditions.stream().map(TerminalReadProbe::directCondition).toList());
            }
        }
        cfg.save(getDataFolder().toPath().resolve("imports/skills.yml").toFile());cfg.save(runtime.getDataFolder().toPath().resolve("skills.yml").toFile());
        service.getClass().getMethod("loadSkills",ConfigurationSection.class).invoke(service,cfg.getConfigurationSection("skills"));
    }
    private static String directCondition(String nativeLine){
        int opening=nativeLine.indexOf('{'),end=nativeLine.lastIndexOf('}');
        String name=switch(nativeLine.substring(0,opening)){
            case "variableisset"->"variable-is-set";
            case "variableequals"->"variable-equals";
            case "variableinrange"->"variable-in-range";
            default->throw new IllegalArgumentException("Unrecognized probe condition: "+nativeLine);
        };
        String prefix=switch(nativeLine.substring(end+1)){
            case " true"->"";
            case " false"->"!";
            default->throw new IllegalArgumentException("Missing probe condition result: "+nativeLine);
        };
        return prefix+name+nativeLine.substring(opening,end+1);
    }
    private void load(){for(String b:List.of("native","runtime")){var c=world.getChunkAt(x(b),Z);c.getEntities();c.addPluginChunkTicket(this);}}
    private void next()throws Exception{
        if(index==cases.size()){
            extras.getMethod("saveScopedVariables",java.io.File.class).invoke(null,getDataFolder().toPath().resolve("final-scoped.yml").toFile());
            getLogger().info("TREAD_SUMMARY|rows="+rows+"|cases="+cases.size()+"|completed=true");return;
        }
        key=cases.get(index);scenario=key.split("_")[0];parents.clear();peers.clear();unloadTicks.clear();cleaning=false;executed=false;
        world=new WorldCreator("terminal-read-"+key).generator(new ChunkGenerator(){@Override public ChunkData generateChunkData(World w,Random r,int x,int z,BiomeGrid b){return createChunkData(w);}}).generateStructures(false).createWorld();
        world.setKeepSpawnInMemory(false);world.setGameRule(GameRule.DO_MOB_SPAWNING,false);world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE,false);world.setTime(18000);world.setGameRule(GameRule.MAX_ENTITY_CRAMMING,0);load();
        for(String backend:List.of("native","runtime")){
            Location at=new Location(world,x(backend)*16+4,110,Z*16+4);LivingEntity e;int instanceId=0;
            if(backend.equals("native")){
                Object api=mythic.getClass().getMethod("getAPIHelper").invoke(mythic);e=(LivingEntity)api.getClass().getMethod("spawnMythicMob",String.class,Location.class).invoke(api,"tr_"+key,at);
                Object a=active(e.getUniqueId()),vars=a.getClass().getMethod("getVariables").invoke(a);Class<?> type=nt("io.lumine.mythic.core.skills.variables.VariableType"),variable=nt("io.lumine.mythic.core.skills.variables.Variable");
                Object integer=Arrays.stream(type.getEnumConstants()).filter(t->t.toString().equals("INTEGER")).findFirst().orElseThrow();Object v=variable.getMethod("ofType",type,Object.class,boolean.class).invoke(null,integer,73,true);vars.getClass().getMethod("put",String.class,variable).invoke(vars,"Marker",v);
                Object pre=variable.getMethod("ofType",type,Object.class,boolean.class).invoke(null,integer,17,true);
                long deadline=System.currentTimeMillis()+3000;variable.getMethod("setExpirationTime",long.class).invoke(pre,deadline);
                vars.getClass().getMethod("put",String.class,variable).invoke(vars,"Pre",pre);
                if(getConfig().getBoolean("condition-location"))seedNativePoints(backend,vars,variable);
                getLogger().info("TREAD_SEED|key="+key+"|backend="+backend+"|uuid="+e.getUniqueId()+"|deadline="+deadline+"|millis="+System.currentTimeMillis());
            }else{
                instanceId=((Number)runtime.getClass().getMethod("spawnMobAt",String.class,Location.class,String.class).invoke(runtime,"tr_"+key,at,null)).intValue();Object inst=instances().getClass().getMethod("impl",int.class).invoke(instances(),instanceId);
                e=(LivingEntity)((Optional<?>)inst.getClass().getMethod("baseEntity").invoke(inst)).orElseThrow();
                long deadline=System.currentTimeMillis()+3000;
                Map<String,Object> points=new LinkedHashMap<>();
                if(getConfig().getBoolean("condition-location")){
                    var snapshot=rt("dev.helstera.api.behavior.SkillTreeContext$LocationSnapshot")
                            .getConstructor(String.class,double.class,double.class,double.class,float.class,float.class);
                    for(String role:List.of("Caster","Target"))for(int tick:POINT_TICKS){
                        Location atPoint=point(backend,role,tick);
                        points.put(role+"Point"+tick,snapshot.newInstance(world.getName(),atPoint.getX(),atPoint.getY(),atPoint.getZ(),0f,0f));
                    }
                }
                Object registry=registryType.getConstructor(Map.class,Map.class,Map.class,Map.class).newInstance(Map.of("Marker",73,"Pre",17),points,Map.of("Marker",0L,"Pre",deadline),Map.of());extras.getMethod("restoreScopedVariables",String.class,registryType).invoke(null,"entity:"+e.getUniqueId(),registry);
                getLogger().info("TREAD_SEED|key="+key+"|backend="+backend+"|uuid="+e.getUniqueId()+"|deadline="+deadline+"|millis="+System.currentTimeMillis());
            }
            parents.put(backend,e.getUniqueId());getLogger().info("TREAD_ID|key="+key+"|backend="+backend+"|uuid="+e.getUniqueId()+"|instance="+instanceId+"|tick="+Bukkit.getCurrentTick());
        }
        observe("initial");later(25,()->{
            if(scenario.equals("chunk")||scenario.equals("suspended")){
                for(String b:List.of("native","runtime")){world.getChunkAt(x(b),Z).removePluginChunkTicket(this);boolean ok=world.unloadChunkRequest(x(b),Z);getLogger().info("TREAD_REQUEST|key="+key+"|backend="+b+"|accepted="+ok+"|tick="+Bukkit.getCurrentTick());if(!ok)throw new IllegalStateException("Unload denied");}
                String waiting=key;later(120,()->{if(key.equals(waiting)&&!executed)throw new IllegalStateException("Missing unload events");});
            }else execute();
        });
    }
    private Location point(String backend,String role,int tick){
        return new Location(world,(x(backend)+4)*16+2+POINT_TICKS.indexOf(tick)*2,
                role.equals("Caster")?112:114,Z*16+10);
    }
    private void seedNativePoints(String backend,Object vars,Class<?> variable)throws Exception{
        var adapter=nt("io.lumine.mythic.bukkit.BukkitAdapter").getMethod("adapt",Location.class);
        var constructor=nt("io.lumine.mythic.core.skills.variables.types.LocationVariable")
                .getConstructor(nt("io.lumine.mythic.api.adapters.AbstractLocation"));
        var put=vars.getClass().getMethod("put",String.class,variable);
        for(String role:List.of("Caster","Target"))for(int tick:POINT_TICKS){
            Location at=point(backend,role,tick);
            put.invoke(vars,role+"Point"+tick,constructor.newInstance(adapter.invoke(null,at)));
        }
    }
    private void observePoints(String phase){
        for(String backend:List.of("native","runtime")){
            var chunk=world.getChunkAt(x(backend)+4,Z);
            chunk.addPluginChunkTicket(this);
            for(String role:List.of("Caster","Target"))for(int tick:POINT_TICKS){
                Location at=point(backend,role,tick);
                getLogger().info("TREAD_POINTS|key="+key+"|backend="+backend+"|phase="+phase
                        +"|uuid="+parents.get(backend)+"|role="+role+"|offset="+tick+"|world="+world.getName()
                        +"|x="+at.getBlockX()+"|y="+at.getBlockY()+"|z="+at.getBlockZ()
                        +"|material="+at.getBlock().getType()+"|parentChunk="+world.isChunkLoaded(x(backend),Z));
                if(phase.equals("settled"))at.getBlock().setType(Material.AIR,false);
            }
        }
    }
    @EventHandler(priority=EventPriority.MONITOR) public void unload(EntitiesUnloadEvent e){
        if(!e.getWorld().equals(world)||cleaning||executed||e.getChunk().getZ()!=Z)return;
        String b=e.getChunk().getX()==256?"native":e.getChunk().getX()==512?"runtime":null;if(b==null)return;
        if(e.getEntities().stream().noneMatch(entity->entity.getUniqueId().equals(parents.get(b))))return;
        unloadTicks.put(b,Bukkit.getCurrentTick());getLogger().info("TREAD_UNLOAD|key="+key+"|backend="+b+"|uuid="+parents.get(b)+"|tick="+Bukkit.getCurrentTick());
        if(unloadTicks.size()==2)later(1,this::execute);
    }
    private void execute()throws Exception{
        if(executed)return;executed=true;
        getLogger().info("TREAD_ACTION|key="+key+"|scenario="+scenario+"|tick="+Bukkit.getCurrentTick());
        for(String b:List.of("native","runtime")){
            if(scenario.equals("command")||scenario.equals("suspended")){
                boolean ok=Bukkit.dispatchCommand(Bukkit.getConsoleSender(),(b.equals("native")?"mythicmobs mobs kill -e ":"helstera mob kill -e ")+"tr_"+key);
                getLogger().info("TREAD_COMMAND|key="+key+"|backend="+b+"|result="+ok+"|loaded="+world.isChunkLoaded(x(b),Z)+"|tick="+Bukkit.getCurrentTick());
            }else if(scenario.equals("selfdeath")||scenario.equals("peerdeath")){
                LivingEntity e=(LivingEntity)Bukkit.getEntity(parents.get(b));
                LivingEntity attacker=scenario.equals("selfdeath")?e:spawnPeer(b,e.getLocation().clone().add(3,0,0));
                getLogger().info("TREAD_ATTACKER|key="+key+"|backend="+b+"|uuid="+attacker.getUniqueId()+"|tick="+Bukkit.getCurrentTick());
                e.setInvulnerable(false);e.damage(100,attacker);
                if(!e.isDead())throw new IllegalStateException("Damage did not kill parent");
            }else if((scenario.equals("death")||scenario.equals("cancelled"))){LivingEntity e=(LivingEntity)Bukkit.getEntity(parents.get(b));e.setInvulnerable(false);e.setHealth(0);}
        }
        if(getConfig().getBoolean("condition-location")){
            later(90,()->{observePoints("settled");load();later(25,()->{observe("settled");cleanup();});});
        }else later(180,()->{load();later(30,()->{observe("settled");cleanup();});});
    }
    private LivingEntity spawnPeer(String backend,Location at)throws Exception{
        LivingEntity peer;
        if(backend.equals("native")){
            Object api=mythic.getClass().getMethod("getAPIHelper").invoke(mythic);
            peer=(LivingEntity)api.getClass().getMethod("spawnMythicMob",String.class,Location.class).invoke(api,"tr_peer",at);
            Object a=active(peer.getUniqueId()),vars=a.getClass().getMethod("getVariables").invoke(a);
            Class<?> type=nt("io.lumine.mythic.core.skills.variables.VariableType"),variable=nt("io.lumine.mythic.core.skills.variables.Variable");
            Object integer=Arrays.stream(type.getEnumConstants()).filter(t->t.toString().equals("INTEGER")).findFirst().orElseThrow();
            Object marker=variable.getMethod("ofType",type,Object.class,boolean.class).invoke(null,integer,29,true);
            vars.getClass().getMethod("put",String.class,variable).invoke(vars,"Marker",marker);
        }else{
            int id=((Number)runtime.getClass().getMethod("spawnMobAt",String.class,Location.class,String.class).invoke(runtime,"tr_peer",at,null)).intValue();
            Object inst=instances().getClass().getMethod("impl",int.class).invoke(instances(),id);
            peer=(LivingEntity)((Optional<?>)inst.getClass().getMethod("baseEntity").invoke(inst)).orElseThrow();
            Object registry=registryType.getConstructor(Map.class,Map.class,Map.class,Map.class).newInstance(Map.of("Marker",29),Map.of(),Map.of(),Map.of());
            extras.getMethod("restoreScopedVariables",String.class,registryType).invoke(null,"entity:"+peer.getUniqueId(),registry);
        }
        peers.put(backend,peer);return peer;
    }
    private void cleanup()throws Exception{
        cleaning=true;
        for(String b:List.of("native","runtime"))Bukkit.dispatchCommand(Bukkit.getConsoleSender(),b.equals("native")?"mythicmobs mobs killall -p":"helstera mob killall -p");
        later(20,()->{observe("cleaned");world.removePluginChunkTickets(this);boolean ok=Bukkit.unloadWorld(world,true);getLogger().info("TREAD_SEAL|key="+key+"|accepted="+ok);if(!ok)throw new IllegalStateException("Seal failed");index++;later(10,this::next);});
    }
    private void observe(String phase)throws Exception{
        if(getConfig().getBoolean("condition-location")&&!phase.equals("settled"))observePoints(phase);
        for(String b:List.of("native","runtime")){
            UUID uuid=parents.get(b);Object reg=b.equals("native")?active(uuid):instance(uuid);Object marker=null;
            if(b.equals("native")&&reg!=null){Object vars=reg.getClass().getMethod("getVariables").invoke(reg),v=vars.getClass().getMethod("get",String.class).invoke(vars,"Marker");if(v!=null)marker=v.getClass().getMethod("get").invoke(v);}
            else if(b.equals("runtime")){Object vars=extras.getMethod("scopedVariableSnapshot",String.class).invoke(null,"entity:"+uuid);marker=((Map<?,?>)registryType.getMethod("variables").invoke(vars)).get("Marker");}
            long visuals=Arrays.stream(world.getChunkAt(x(b),Z).getEntities()).filter(e->e instanceof Display||e instanceof Interaction||e instanceof ArmorStand).count();
            int terminalExecutions=((Number)service.getClass().getMethod("terminalExecutionCount").invoke(service)).intValue();
            Object ai=runtime.getClass().getMethod("ai").invoke(runtime),minions=ai.getClass().getMethod("minions").invoke(ai);
            int ownerRecords=((Number)minions.getClass().getMethod("trackedOwnerCount").invoke(minions)).intValue();
            int minionRecords=((Number)minions.getClass().getMethod("totalMinions").invoke(minions)).intValue();
            Object liveSnapshot=extras.getMethod("scopedVariableSnapshot",String.class).invoke(null,"entity:"+uuid);
            int liveVariables=((Map<?,?>)registryType.getMethod("variables").invoke(liveSnapshot)).size();
            int liveLocations=((Map<?,?>)registryType.getMethod("locations").invoke(liveSnapshot)).size();
            getLogger().info("TREAD_ROW|key="+key+"|backend="+b+"|phase="+phase+"|uuid="+uuid+"|entity="+(Bukkit.getEntity(uuid)!=null)+"|registry="+(reg!=null)+"|marker="+(marker==null?"absent":marker)+"|visuals="+visuals+"|terminalExecutions="+terminalExecutions+"|ownerRecords="+ownerRecords+"|minionRecords="+minionRecords+"|liveVariables="+liveVariables+"|liveLocations="+liveLocations+"|tick="+Bukkit.getCurrentTick()+"|millis="+System.currentTimeMillis());rows++;
        }
    }
    private static String hook(String label,String timing){
        List<String> values=new ArrayList<>();
        for(String role:List.of("caster","target","trigger")){
            values.add(role+"Uuid=<"+role+".uuid>");
            for(String name:List.of("Marker","Counter","Pre","Missing"))values.add(role+name+"=<"+role+".var."+name+"|absent>");
            values.add(role+"Raw=<"+role+".var.Marker>");
        }
        return "command{c=\"terminalreadprobe hook runtime "+label+" <caster.uuid> "+String.join(";",values)+"\""+timing+"} @self";
    }
    @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] args){
        if(!(sender instanceof ConsoleCommandSender))return false;
        try{
            if(args.length>=5&&args[0].equals("hook")){
                UUID uuid=UUID.fromString(args[3]);if(!uuid.equals(parents.get(args[1])))throw new IllegalStateException("Wrong parent UUID");
                String values=Base64.getEncoder().encodeToString(String.join(" ",Arrays.copyOfRange(args,4,args.length)).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                Object reg=args[1].equals("native")?active(uuid):instance(uuid);
                Object live=extras.getMethod("scopedVariableSnapshot",String.class).invoke(null,"entity:"+uuid);
                int liveVariables=((Map<?,?>)registryType.getMethod("variables").invoke(live)).size();
                int executions=((Number)service.getClass().getMethod("terminalExecutionCount").invoke(service)).intValue();
                getLogger().info("TREAD_HOOK|key="+key+"|backend="+args[1]+"|label="+args[2]+"|uuid="+uuid+"|reads="+values+"|registry="+(reg!=null)+"|liveVariables="+liveVariables+"|terminalExecutions="+executions+"|cleaning="+cleaning+"|tick="+Bukkit.getCurrentTick()+"|millis="+System.currentTimeMillis()+"|primary="+Bukkit.isPrimaryThread());return true;
            }
            return false;
        }catch(Throwable e){fail(e);return true;}
    }
    @EventHandler(priority=EventPriority.MONITOR) public void death(EntityDeathEvent e){
        if(parents.containsValue(e.getEntity().getUniqueId())){e.getDrops().clear();e.setDroppedExp(0);getLogger().info("TREAD_DEATH|key="+key+"|uuid="+e.getEntity().getUniqueId()+"|cancelled="+e.isCancelled()+"|reviveHealth="+e.getReviveHealth()+"|tick="+Bukkit.getCurrentTick());}
    }
}

