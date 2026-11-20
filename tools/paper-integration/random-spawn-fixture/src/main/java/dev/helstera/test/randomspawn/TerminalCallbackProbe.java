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

/** Observe scheduled terminal skills through real commands, deaths and entity unloads. */
public final class TerminalCallbackProbe extends JavaPlugin implements Listener {
    private static final int Z=256;
    private Plugin runtime,mythic; private Object manager,service; private World world;
    private Class<?> extras,registryType;
    private final Map<String,UUID> parents=new LinkedHashMap<>();
    private final Map<UUID,String> children=new LinkedHashMap<>();
    private final Map<String,Integer> unloadTicks=new HashMap<>();
    private List<String> cases; private String key,scenario; private int index,rows; private boolean failed,cleaning,executed;
    @FunctionalInterface private interface Step{void run()throws Exception;}
    private void fail(Throwable e){failed=true;getLogger().severe("TCALL_FAILED|key="+key+"|error="+e);e.printStackTrace();}
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
        cases=getConfig().getStringList("cases");Bukkit.getPluginManager().registerEvents(this,this);later(100,this::begin);
    }
    private void begin()throws Exception{
        manager=mythic.getClass().getMethod("getMobManager").invoke(mythic);service=runtime.getClass().getMethod("skillService").invoke(runtime);
        extras=rt("dev.helstera.ai.skill.SkillExtras");registryType=rt("dev.helstera.api.behavior.ScopedVariableSnapshotStore$Registry");
        configure();next();
    }
    @SuppressWarnings("unchecked") private void configure()throws Exception{
        Object importer=rt("dev.helstera.migration.importer.MythicMobsImporter").getConstructor().newInstance();
        List<Map<String,Object>> plans=(List<Map<String,Object>>)importer.getClass().getMethod("convert",Path.class).invoke(importer,mythic.getDataFolder().toPath().resolve("Mobs/terminal.yml"));
        var source=YamlConfiguration.loadConfiguration(mythic.getDataFolder().toPath().resolve("Mobs/terminal.yml").toFile());
        for(String id:source.getKeys(false)){
            var cfg=new YamlConfiguration();
            if(id.endsWith("_imported")){
                cfg.loadFromString(plans.stream().filter(p->id.equals(p.get("source-mob-id"))&&p.containsKey("target-file")).findFirst().orElseThrow().get("content").toString().replace("hook native","hook runtime"));
            }else{
                cfg.set("entity.type",source.getString(id+".Type"));cfg.set("entity.no-ai",true);cfg.set("entity.gravity",false);cfg.set("entity.invulnerable",true);
                cfg.set("mob.despawn",source.getString(id+".Options.Despawn"));
                if(id.startsWith("tc_child_"))cfg.set("ai.triggers.on-spawn.do",List.of("command \"terminalprobe child runtime "+source.getString(id+".Options.Despawn")+" <caster.uuid>\" @self"));
                else{
                    List<String> spawn=new ArrayList<>();spawn.add("command{c=\"terminalprobe hook runtime pending <caster.uuid> <caster.var.Marker>\";delay=80} @self");
                    if(id.startsWith("tc_control_"))spawn.add("skill{s=tc_terminal;delay=10} @self");
                    else for(String event:List.of("despawn","death"))cfg.set("ai.triggers.on-"+event+".do",List.of("cast-skill tc_terminal"));
                    cfg.set("ai.triggers.on-spawn.do",spawn);
                }
            }
            cfg.set("model","example/emberling");cfg.set("mob.despawn-distance",0);
            // Summon mappings retain the importer's mythic_ namespace. The
            // positive control must use the same converted child identifier.
            String outputId=id.startsWith("tc_child_")?"mythic_"+id:id;
            Path file=runtime.getDataFolder().toPath().resolve("mobs/"+outputId+".yml");cfg.save(file.toFile());
            Path archive=getDataFolder().toPath().resolve("imports/"+outputId+".yml");Files.createDirectories(archive.getParent());cfg.save(archive.toFile());
        }
        List<Map<String,Object>> skills=(List<Map<String,Object>>)importer.getClass().getMethod("convert",Path.class).invoke(importer,mythic.getDataFolder().toPath().resolve("Skills/terminal.yml"));
        var cfg=new YamlConfiguration();cfg.loadFromString(skills.stream().filter(p->"skills.yml".equals(p.get("target-file"))).findFirst().orElseThrow().get("content").toString().replace("hook native","hook runtime"));
        cfg.save(getDataFolder().toPath().resolve("imports/skills.yml").toFile());cfg.save(runtime.getDataFolder().toPath().resolve("skills.yml").toFile());
        service.getClass().getMethod("loadSkills",ConfigurationSection.class).invoke(service,cfg.getConfigurationSection("skills"));
    }
    private void load(){for(String b:List.of("native","runtime")){var c=world.getChunkAt(x(b),Z);c.getEntities();c.addPluginChunkTicket(this);}}
    private void next()throws Exception{
        if(index==cases.size()){
            extras.getMethod("saveScopedVariables",java.io.File.class).invoke(null,getDataFolder().toPath().resolve("final-scoped.yml").toFile());
            getLogger().info("TCALL_SUMMARY|rows="+rows+"|cases="+cases.size()+"|completed=true");return;
        }
        key=cases.get(index);scenario=key.split("_")[0];parents.clear();children.clear();unloadTicks.clear();cleaning=false;executed=false;
        world=new WorldCreator("terminal-"+key).generator(new ChunkGenerator(){@Override public ChunkData generateChunkData(World w,Random r,int x,int z,BiomeGrid b){return createChunkData(w);}}).generateStructures(false).createWorld();
        world.setKeepSpawnInMemory(false);world.setGameRule(GameRule.DO_MOB_SPAWNING,false);world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE,false);world.setTime(18000);world.setGameRule(GameRule.MAX_ENTITY_CRAMMING,0);load();
        for(String backend:List.of("native","runtime")){
            Location at=new Location(world,x(backend)*16+4,110,Z*16+4);LivingEntity e;int instanceId=0;
            if(backend.equals("native")){
                Object api=mythic.getClass().getMethod("getAPIHelper").invoke(mythic);e=(LivingEntity)api.getClass().getMethod("spawnMythicMob",String.class,Location.class).invoke(api,"tc_"+key,at);
                Object a=active(e.getUniqueId()),vars=a.getClass().getMethod("getVariables").invoke(a);Class<?> type=nt("io.lumine.mythic.core.skills.variables.VariableType"),variable=nt("io.lumine.mythic.core.skills.variables.Variable");
                Object integer=Arrays.stream(type.getEnumConstants()).filter(t->t.toString().equals("INTEGER")).findFirst().orElseThrow();Object v=variable.getMethod("ofType",type,Object.class,boolean.class).invoke(null,integer,73,true);vars.getClass().getMethod("put",String.class,variable).invoke(vars,"Marker",v);
            }else{
                instanceId=((Number)runtime.getClass().getMethod("spawnMobAt",String.class,Location.class,String.class).invoke(runtime,"tc_"+key,at,null)).intValue();Object inst=instances().getClass().getMethod("impl",int.class).invoke(instances(),instanceId);
                e=(LivingEntity)((Optional<?>)inst.getClass().getMethod("baseEntity").invoke(inst)).orElseThrow();
                Object registry=registryType.getConstructor(Map.class,Map.class,Map.class,Map.class).newInstance(Map.of("Marker",73),Map.of(),Map.of("Marker",0L),Map.of());extras.getMethod("restoreScopedVariables",String.class,registryType).invoke(null,"entity:"+e.getUniqueId(),registry);
            }
            parents.put(backend,e.getUniqueId());getLogger().info("TCALL_ID|key="+key+"|backend="+backend+"|uuid="+e.getUniqueId()+"|instance="+instanceId+"|tick="+Bukkit.getCurrentTick());
        }
        observe("initial");later(25,()->{
            if(scenario.equals("chunk")||scenario.equals("suspended")){
                for(String b:List.of("native","runtime")){world.getChunkAt(x(b),Z).removePluginChunkTicket(this);boolean ok=world.unloadChunkRequest(x(b),Z);getLogger().info("TCALL_REQUEST|key="+key+"|backend="+b+"|accepted="+ok+"|tick="+Bukkit.getCurrentTick());if(!ok)throw new IllegalStateException("Unload denied");}
                String waiting=key;later(120,()->{if(key.equals(waiting)&&!executed)throw new IllegalStateException("Missing unload events");});
            }else execute();
        });
    }
    @EventHandler(priority=EventPriority.MONITOR) public void unload(EntitiesUnloadEvent e){
        if(!e.getWorld().equals(world)||cleaning||executed||e.getChunk().getZ()!=Z)return;
        String b=e.getChunk().getX()==256?"native":e.getChunk().getX()==512?"runtime":null;if(b==null)return;
        if(e.getEntities().stream().noneMatch(entity->entity.getUniqueId().equals(parents.get(b))))return;
        unloadTicks.put(b,Bukkit.getCurrentTick());getLogger().info("TCALL_UNLOAD|key="+key+"|backend="+b+"|uuid="+parents.get(b)+"|tick="+Bukkit.getCurrentTick());
        if(unloadTicks.size()==2)later(1,this::execute);
    }
    private void execute()throws Exception{
        if(executed)return;executed=true;
        getLogger().info("TCALL_ACTION|key="+key+"|scenario="+scenario+"|tick="+Bukkit.getCurrentTick());
        for(String b:List.of("native","runtime")){
            if(scenario.equals("command")||scenario.equals("suspended")){
                boolean ok=Bukkit.dispatchCommand(Bukkit.getConsoleSender(),(b.equals("native")?"mythicmobs mobs kill -e ":"helstera mob kill -e ")+"tc_"+key);
                getLogger().info("TCALL_COMMAND|key="+key+"|backend="+b+"|result="+ok+"|loaded="+world.isChunkLoaded(x(b),Z)+"|tick="+Bukkit.getCurrentTick());
            }else if(scenario.equals("death")){LivingEntity e=(LivingEntity)Bukkit.getEntity(parents.get(b));e.setInvulnerable(false);e.setHealth(0);}
        }
        later(100,()->{load();later(30,()->{observe("settled");cleanup();});});
    }
    private void cleanup()throws Exception{
        cleaning=true;
        for(String b:List.of("native","runtime"))Bukkit.dispatchCommand(Bukkit.getConsoleSender(),b.equals("native")?"mythicmobs mobs killall -p":"helstera mob killall -p");
        later(100,()->{observe("cleaned");world.removePluginChunkTickets(this);boolean ok=Bukkit.unloadWorld(world,true);getLogger().info("TCALL_SEAL|key="+key+"|accepted="+ok);if(!ok)throw new IllegalStateException("Seal failed");index++;later(10,this::next);});
    }
    private void observe(String phase)throws Exception{
        for(String b:List.of("native","runtime")){
            UUID uuid=parents.get(b);Object reg=b.equals("native")?active(uuid):instance(uuid);Object marker=null;
            if(b.equals("native")&&reg!=null){Object vars=reg.getClass().getMethod("getVariables").invoke(reg),v=vars.getClass().getMethod("get",String.class).invoke(vars,"Marker");if(v!=null)marker=v.getClass().getMethod("get").invoke(v);}
            else if(b.equals("runtime")){Object vars=extras.getMethod("scopedVariableSnapshot",String.class).invoke(null,"entity:"+uuid);marker=((Map<?,?>)registryType.getMethod("variables").invoke(vars)).get("Marker");}
            long childCount=children.entrySet().stream().filter(e->e.getValue().startsWith(b+"_")&&Bukkit.getEntity(e.getKey())!=null).count();
            long visuals=Arrays.stream(world.getChunkAt(x(b),Z).getEntities()).filter(e->e instanceof Display||e instanceof Interaction||e instanceof ArmorStand).count();
            getLogger().info("TCALL_ROW|key="+key+"|backend="+b+"|phase="+phase+"|uuid="+uuid+"|entity="+(Bukkit.getEntity(uuid)!=null)+"|registry="+(reg!=null)+"|marker="+(marker==null?"absent":marker)+"|children="+childCount+"|visuals="+visuals+"|tick="+Bukkit.getCurrentTick());rows++;
        }
        for(var e:children.entrySet())getLogger().info("TCALL_CHILD_STATE|key="+key+"|phase="+phase+"|kind="+e.getValue()+"|uuid="+e.getKey()+"|entity="+(Bukkit.getEntity(e.getKey())!=null)+"|nativeRegistry="+(active(e.getKey())!=null)+"|runtimeRegistry="+(instance(e.getKey())!=null));
    }
    @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] args){
        if(!(sender instanceof ConsoleCommandSender))return false;
        try{
            if(args.length==5&&args[0].equals("hook")){
                UUID uuid=UUID.fromString(args[3]);if(!uuid.equals(parents.get(args[1])))throw new IllegalStateException("Wrong parent UUID");
                getLogger().info("TCALL_HOOK|key="+key+"|backend="+args[1]+"|label="+args[2]+"|uuid="+uuid+"|marker="+args[4]+"|cleaning="+cleaning+"|tick="+Bukkit.getCurrentTick()+"|primary="+Bukkit.isPrimaryThread());return true;
            }
            if(args.length==4&&args[0].equals("child")){
                UUID uuid=UUID.fromString(args[3]);if(children.putIfAbsent(uuid,args[1]+"_"+args[2])!=null)throw new IllegalStateException("Repeated child callback");
                getLogger().info("TCALL_CHILD|key="+key+"|backend="+args[1]+"|mode="+args[2]+"|uuid="+uuid+"|cleaning="+cleaning+"|tick="+Bukkit.getCurrentTick()+"|primary="+Bukkit.isPrimaryThread());return true;
            }
            return false;
        }catch(Throwable e){fail(e);return true;}
    }
    @EventHandler(priority=EventPriority.MONITOR) public void death(EntityDeathEvent e){
        if(parents.containsValue(e.getEntity().getUniqueId())){e.getDrops().clear();e.setDroppedExp(0);getLogger().info("TCALL_DEATH|key="+key+"|uuid="+e.getEntity().getUniqueId()+"|tick="+Bukkit.getCurrentTick());}
    }
}
