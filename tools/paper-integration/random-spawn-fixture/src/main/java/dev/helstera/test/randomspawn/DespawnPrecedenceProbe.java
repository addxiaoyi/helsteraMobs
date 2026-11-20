package dev.helstera.test.randomspawn;

import org.bukkit.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import java.nio.file.*;
import java.util.*;

/** Reads actual native/imported modes and Bukkit flags across a process restart. */
public final class DespawnPrecedenceProbe extends JavaPlugin implements Listener {
    private record Pair(String key, UUID nativeId, UUID runtimeId) {}
    private final List<Pair> pairs=new ArrayList<>();
    private Plugin runtime,mythic;
    private Object manager;
    private World world;
    private String stage;
    private boolean failed;
    private int rows;
    @FunctionalInterface private interface Step { void run() throws Exception; }
    private void later(long ticks,Step step){Bukkit.getScheduler().runTaskLater(this,()->{
        if(failed)return;try{step.run();}catch(Throwable e){failed=true;getLogger().severe("DPREC_FAILED|stage="+stage+"|error="+e);e.printStackTrace();}
    },ticks);}
    @Override public void onEnable(){
        stage=getConfig().getString("stage");runtime=Bukkit.getPluginManager().getPlugin("helsteraMobs");
        mythic=Bukkit.getPluginManager().getPlugin("MythicMobs");Bukkit.getPluginManager().registerEvents(this,this);later(100,this::begin);
    }
    private void begin() throws Exception{
        manager=mythic.getClass().getMethod("getMobManager").invoke(mythic);
        Object config=mythic.getClass().getMethod("getConfiguration").invoke(mythic);
        getLogger().info("DPREC_GLOBAL|value="+config.getClass().getMethod("getMobsDespawnByDefault").invoke(config));
        world=new WorldCreator("despawn-precedence").generator(new ChunkGenerator(){
            @Override public ChunkData generateChunkData(World w,Random r,int x,int z,BiomeGrid b){return createChunkData(w);}
        }).generateStructures(false).createWorld();
        world.setKeepSpawnInMemory(false);world.setGameRule(GameRule.DO_MOB_SPAWNING,false);
        var source=YamlConfiguration.loadConfiguration(getDataFolder().toPath().resolve("Mobs/cases.yml").toFile());
        int index=0;
        for(String key:source.getKeys(false)){var chunk=world.getChunkAt(300+index++*4,300);chunk.getEntities();chunk.addPluginChunkTicket(this);}
        later(40,()->{
            if(stage.equals("write"))spawn(source);
            else{
                var ids=YamlConfiguration.loadConfiguration(getDataFolder().toPath().resolve("identities.yml").toFile());
                if(!world.getUID().toString().equals(ids.getString("world")))throw new IllegalStateException("World UUID changed");
                for(String key:source.getKeys(false))pairs.add(new Pair(key,UUID.fromString(ids.getString(key+".native")),UUID.fromString(ids.getString(key+".runtime"))));
                later(40,()->{
                    observe("restored");
                    for(Pair p:pairs)for(UUID id:List.of(p.nativeId,p.runtimeId))if(Bukkit.getEntity(id) instanceof LivingEntity entity){entity.setInvulnerable(false);entity.setHealth(0);}
                    later(60,()->{observe("dead");finish();});
                });
            }
        });
    }
    private void spawn(YamlConfiguration source) throws Exception{
        Object importer=Class.forName("dev.helstera.migration.importer.MythicMobsImporter",true,runtime.getClass().getClassLoader()).getConstructor().newInstance();
        @SuppressWarnings("unchecked") List<Map<String,Object>> plans=(List<Map<String,Object>>)importer.getClass().getMethod("convert",Path.class,Path.class)
                .invoke(importer,getDataFolder().toPath().resolve("Mobs/cases.yml"),getDataFolder().toPath());
        Map<String,YamlConfiguration> configs=new HashMap<>();
        for(var entry:plans)if(String.valueOf(entry.get("target-file")).startsWith("mobs/")){
            var cfg=new YamlConfiguration();cfg.loadFromString(entry.get("content").toString());configs.put(entry.get("source-mob-id").toString(),cfg);
        }
        var imports=getDataFolder().toPath().resolve("imports");Files.createDirectories(imports);
        var ids=new YamlConfiguration();ids.set("world",world.getUID().toString());int index=0;
        for(String key:source.getKeys(false)){
            var cfg=Objects.requireNonNull(configs.get(key));
            // Native source is identical except the callback backend label.
            List<String> triggers=cfg.getStringList("mob.triggers.on-death.do");
            cfg.set("mob.triggers.on-death.do",triggers.stream().map(s->s.replace("DPREC_HOOK native","DPREC_HOOK runtime")).toList());
            triggers=cfg.getStringList("mob.triggers.on-despawn.do");
            cfg.set("mob.triggers.on-despawn.do",triggers.stream().map(s->s.replace("DPREC_HOOK native","DPREC_HOOK runtime")).toList());
            cfg.set("model","example/emberling");cfg.save(imports.resolve(key+".yml").toFile());
            cfg.save(runtime.getDataFolder().toPath().resolve("mobs/precedence_"+key+".yml").toFile());
            Location at=new Location(world,(300+index++*4)*16+2.25,110,4802.75);
            // A floor prevents NoAI distance controls from becoming void deaths.
            for(int x=0;x<5;x++)for(int z=0;z<5;z++)world.getBlockAt(at.getBlockX()+x,109,at.getBlockZ()+z).setType(Material.STONE);
            Object api=mythic.getClass().getMethod("getAPIHelper").invoke(mythic);
            var n=(LivingEntity)api.getClass().getMethod("spawnMythicMob",String.class,Location.class).invoke(api,key,at);
            int id=((Number)runtime.getClass().getMethod("spawnMobAt",String.class,Location.class,String.class).invoke(runtime,"precedence_"+key,at.clone().add(1,0,0),null)).intValue();
            Object inst=instances().getClass().getMethod("impl",int.class).invoke(instances(),id);
            var r=(LivingEntity)((Optional<?>)inst.getClass().getMethod("baseEntity").invoke(inst)).orElseThrow();
            pairs.add(new Pair(key,n.getUniqueId(),r.getUniqueId()));
            ids.set(key+".native",n.getUniqueId().toString());ids.set(key+".runtime",r.getUniqueId().toString());
            long warnings=plans.stream().filter(e->"unsupported".equals(e.get("status"))&&"WARN".equals(e.get("severity"))).count();
            getLogger().info("DPREC_MAPPING|key="+key+"|mode="+cfg.getString("mob.despawn")+"|warnings="+warnings);
        }
        ids.save(getDataFolder().toPath().resolve("identities.yml").toFile());
        // Archive the report, not just the selected mapping, to retain source warnings.
        var report=new YamlConfiguration();report.set("entries",plans);report.save(getDataFolder().toPath().resolve("migration.yml").toFile());
        later(20,()->{observe("write");finish();});
    }
    private Object instances() throws Exception{return runtime.getClass().getMethod("instances").invoke(runtime);}
    private Object instance(UUID uuid) throws Exception{
        for(Object i:(Collection<?>)instances().getClass().getMethod("allImpl").invoke(instances()))
            if(((Optional<?>)i.getClass().getMethod("boundEntityId").invoke(i)).filter(uuid::equals).isPresent())return i;
        return null;
    }
    private void observe(String phase) throws Exception{
        for(Pair p:pairs){
            Object n=((Optional<?>)manager.getClass().getMethod("getActiveMob",UUID.class).invoke(manager,p.nativeId)).orElse(null),r=instance(p.runtimeId);
            Entity ne=Bukkit.getEntity(p.nativeId),re=Bukkit.getEntity(p.runtimeId);
            Object opts=r==null?null:r.getClass().getMethod("options").invoke(r);
            getLogger().info("DPREC_ROW|phase="+phase+"|key="+p.key+"|native="+p.nativeId+"|runtime="+p.runtimeId
                +"|nativeEntity="+(ne!=null)+"|runtimeEntity="+(re!=null)+"|nativeRegistry="+(n!=null)+"|runtimeInstance="+(r!=null)
                +"|nativeMode="+(n==null?"absent":n.getClass().getMethod("getDespawnMode").invoke(n))
                +"|runtimeMode="+(opts==null?"absent":opts.getClass().getMethod("despawnMode").invoke(opts))
                +"|nativeSave="+(ne==null?"absent":ne.isPersistent())+"|runtimeSave="+(re==null?"absent":re.isPersistent())
                +"|nativeFar="+(ne==null?"absent":((LivingEntity)ne).getRemoveWhenFarAway())+"|runtimeFar="+(re==null?"absent":((LivingEntity)re).getRemoveWhenFarAway()));rows++;
        }
    }
    private void finish(){getLogger().info("DPREC_SUMMARY|stage="+stage+"|rows="+rows+"|completed=true");}
    @EventHandler(priority=EventPriority.MONITOR) public void died(EntityDeathEvent e){
        for(Pair p:pairs)for(String backend:List.of("native","runtime"))if(e.getEntity().getUniqueId().equals(backend.equals("native")?p.nativeId:p.runtimeId))
            getLogger().info("DPREC_DEATH|key="+p.key+"|backend="+backend+"|uuid="+e.getEntity().getUniqueId());
    }
}
