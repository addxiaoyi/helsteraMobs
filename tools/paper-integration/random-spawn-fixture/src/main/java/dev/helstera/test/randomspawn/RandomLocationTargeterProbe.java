package dev.helstera.test.randomspawn;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.ThreadLocalRandom;

/** Same-thread scoped RNG seeds; real native/Paper queries and block effects. */
public final class RandomLocationTargeterProbe extends JavaPlugin {
    private final List<Map<String,Object>> rows=new ArrayList<>();
    private VariablePlaceholderAudit.Harness h;
    private World world;
    private Player player;
    private String path;
    private Class<?> nativeLocation;
    private Field seedField;

    @Override public void onEnable() {
        new BukkitRunnable() {
            int attempts;
            @Override public void run() {
                Player joined=Bukkit.getPlayerExact("RandomTester");
                if(joined!=null&&joined.isOnline()){cancel();execute(joined);}
                else if(++attempts>=120){cancel();getLogger().severe("RANDOM_LOCATION_FAILED|missing-player");}
            }
        }.runTaskTimer(this,100,20);
    }

    private void execute(Player joined) {
        player=joined;world=joined.getWorld();
        try {
            ThreadLocalRandom.current();
            seedField=Thread.class.getDeclaredField("threadLocalRandomSeed");seedField.setAccessible(true);
            // Keep all exercised samples in an explicit high-altitude air scene.
            for(int x=-70;x<=120;x++)for(int y=177;y<=185;y++)for(int z=-8;z<=8;z++)
                world.getBlockAt(x,y,z).setType(Material.AIR,false);
            for(String route:List.of("direct","imported"))run(route);
            getLogger().info("RANDOM_LOCATION_SUMMARY|observations="+rows.size()+"|passed=true");
            player.sendMessage("RANDOM_LOCATION_DONE");
        } catch(Throwable failure) {
            getLogger().severe("RANDOM_LOCATION_FAILED|"+failure);failure.printStackTrace();
        } finally {
            try {
                var yaml=new YamlConfiguration();yaml.set("observations",rows);Files.createDirectories(getDataFolder().toPath());
                yaml.save(getDataFolder().toPath().resolve("observations.yml").toFile());
            } catch(Exception failure){getLogger().severe("RANDOM_LOCATION_FAILED|snapshot="+failure);}
        }
    }

    private Location point(double x){return new Location(world,x,180.25,.25,90,30);}

    private void run(String route)throws Exception {
        path=route;
        try(var harness=new VariablePlaceholderAudit.Harness(this,Bukkit.getPluginManager().getPlugin("helsteraMobs"),
                Bukkit.getPluginManager().getPlugin("MythicMobs"),point(128),false)) {
            h=harness;nativeLocation=h.nativeType("io.lumine.mythic.api.adapters.AbstractLocation");
            h.a.teleport(point(40.25));h.b.teleport(point(80.25));h.c.teleport(point(100.25));h.item.teleport(point(100.25));
            for(String alias:List.of("randomLocationsNearOrigin","RLO","randomLocationsOrigin","RLNO",
                    "randomLocationsNearTargets","RLNT","randomLocationsNearCaster","RLNC","randomLocations"))
                row("alias-"+alias,"@"+alias+"{a=3}","air","entities",19);
            for(long seed:new long[]{7331,20261126})for(String alias:List.of("RLO","RLNT","RLNC"))
                row("seed-"+alias+"-"+seed,"@"+alias,"air","entities",seed);
            row("locations","@RLNT{a=3}","air","locations",19);
            row("empty","@RLNT","air","empty",19);
            row("entity-reselection","@RLNT{a=3}","air","reselected-entities",19);
            row("nonliving","@RLNT{a=3}","air","nonliving",19);
            for(String alias:List.of("RLO","RLNT","RLNC")) {
                row("solid-"+alias,"@"+alias+"{a=3;r=2}","solid","entities",19);
                row("mixed-"+alias,"@"+alias+"{a=5;r=2}","mixed","entities",7331);
                row("minimum-"+alias,"@"+alias+"{a=8;r=3;minr=3}","air","entities",19);
                row("spacing-"+alias,"@"+alias+"{a=5;r=2;s=3}","air","entities",19);
                row("mutation-"+alias,"@"+alias+"{a=3;r=2;x=4;y=2;z=-2;centered=true}","air","entities",19);
                row("overflow-min-"+alias,"@"+alias+"{a=1;minr=50000}","air","entities",19);
            }
            row("duplicates","@RLNT{a=3;r=2}","solid","duplicates",19);
            row("spacing-global","@RLNT{a=3;r=2;s=1}","solid","duplicates",19);
            row("spacing-mutated","@RLNT{a=3;r=2;s=1;x=4}","solid","duplicates",19);
            row("min-before-mutation","@RLO{a=3;r=1;minr=100;x=200}","air","entities",19);
            row("priority","@RLO{a=1;amount=3;r=1;radius=2;maxradius=4;minradius=0;minr=9;spacing=0;s=100}","air","entities",19);
            row("max-alias","@RLO{a=3;maxr=2}","air","entities",19);
            row("amount-zero","@RLO{a=0}","air","entities",19);
            row("amount-negative","@RLO{a=-1}","air","entities",19);
            row("amount-invalid","@RLO{a=bad}","air","entities",19);
            row("amount-quoted","@RLO{a=\"3\"}","air","entities",19);
            row("amount-dynamic","@RLO{a=<caster.var.amount>}","air","entities",19);
            row("amount-range","@RLNT{a=3to3;r=2}","solid","entities",19);
            row("radius-zero","@RLO{a=3;r=0}","air","entities",19);
            row("radius-negative","@RLO{a=3;r=-2}","air","entities",19);
            for(String alias:List.of("RLO","RLNT"))for(String value:List.of("bad","\"2\"","<caster.var.radius>"))
                row("radius-"+alias+"-"+value,"@"+alias+"{a=3;r="+value+"}","air","entities",19);
            row("radius-range-origin","@RLO{a=3;r=2to2}","air","entities",19);
            row("radius-range-targets","@RLNT{a=3;r=2to2}","solid","entities",19);
            row("static-min","@RLO{a=3;r=2;minr=bad}","solid","entities",19);
            row("placeholder-min","@RLNT{a=3;r=2;minr=bad}","solid","entities",19);
            row("spacing-overflow","@RLNT{a=3;r=2;s=50000}","solid","duplicates",19);
            row("filter-allow","@RLO{a=3;r=2;blocktypes=AIR}","air","entities",19);
            row("filter-reject","@RLO{a=3;r=2;blockignores=AIR}","air","entities",19);
            row("limit","@RLNT{a=5;r=2;limit=2;sort=NEAREST}","air","entities",19);
            row("limit-duplicates","@RLNT{a=3;r=2;limit=2;sort=NEAREST}","solid","duplicates",19);
            row("guard-true","@RLO{a=3;r=2;conditions=[ - distance{d=0to1000} true ]}","air","entities",19);
            row("guard-false","@RLO{a=3;r=2;conditions=[ - distance{d=1000to2000} true ]}","air","entities",19);
        }
    }

    private void row(String id,String selector,String scene,String inputs,long seed)throws Exception {
        Location origin=point(.25);
        List<Location> inherited=inputs.equals("duplicates")?List.of(point(-40.25),point(-40.25)):List.of(point(-40.25),point(-60.25));
        Object metadata=h.meta(h.a,player);h.skillMetadata.getMethod("setOrigin",nativeLocation).invoke(metadata,adapt(origin));
        var entities=inputs.equals("nonliving")?List.of(h.item):List.of(h.b,h.c);
        boolean entityInput=List.of("entities","reselected-entities","nonliving").contains(inputs);
        List<Object> adapted=new ArrayList<>();if(entityInput)for(var entity:entities)adapted.add(h.adapt.invoke(null,entity));
        h.skillMetadata.getMethod("setEntityTargets",Collection.class).invoke(metadata,adapted);
        if(List.of("locations","reselected-entities","duplicates").contains(inputs)) {
            List<Object> locations=new ArrayList<>();for(Location location:inherited)locations.add(adapt(location));
            h.skillMetadata.getMethod("setLocationTargets",Collection.class).invoke(metadata,locations);
        }
        // Native metadata setters replace the opposing collection; exercise that real transition.
        if(inputs.equals("reselected-entities"))h.skillMetadata.getMethod("setEntityTargets",Collection.class).invoke(metadata,adapted);
        Object context=h.context(h.a,null,player,origin,null);
        if(inputs.equals("reselected-entities"))context=h.contextType.getMethod("withLocationTargets",List.class).invoke(context,inherited);
        if(entityInput)context=h.contextType.getMethod("withEntityTargets",List.class).invoke(context,entities);
        else if(!inputs.equals("empty"))context=h.contextType.getMethod("withLocationTargets",List.class).invoke(context,inherited);
        Object targeter=h.nativeSkills.getClass().getMethod("getTargeter",String.class).invoke(h.nativeSkills,selector);
        var parsed=new LinkedHashMap<String,Object>();
        for(String field:List.of("amount","maxRadius","minRadius","spacing")) {
            var member=targeter.getClass().getDeclaredField(field);member.setAccessible(true);Object value=member.get(targeter);
            if(!(value instanceof Number))value=value.getClass().getMethod("get",h.placeholderMeta).invoke(value,metadata);
            parsed.put(field,value);
        }
        Map<Block,BlockData> preparation=new LinkedHashMap<>();
        if(!scene.equals("air"))for(Location center:List.of(origin,h.a.getLocation(),h.b.getLocation(),h.c.getLocation(),point(-40.25),point(-60.25)))
            for(int x=center.getBlockX()-5;x<=center.getBlockX()+5;x++)for(int z=-5;z<=5;z++)for(int y=179;y<=181;y++) {
                Block block=world.getBlockAt(x,y,z);preparation.putIfAbsent(block,block.getBlockData().clone());
                block.setType(scene.equals("solid")||(x&1)==0?Material.STONE:Material.AIR,false);
            }
        try {
            @SuppressWarnings("unchecked") Collection<Object> raw=seeded(seed,()->(Collection<Object>)targeter.getClass().getMethod("getLocations",h.skillMetadata).invoke(targeter,metadata));
            List<Location> nativeRaw=points(raw);
            h.skillMetadata.getMethod("setLocationTargets",Collection.class).invoke(metadata,raw);
            targeter.getClass().getMethod("filter",h.skillMetadata).invoke(targeter,metadata);
            @SuppressWarnings("unchecked") Collection<Object> selected=(Collection<Object>)h.skillMetadata.getMethod("getLocationTargets").invoke(metadata);
            List<Location> nativeSelected=points(selected);Object ctx=context;
            @SuppressWarnings("unchecked") List<Location> runtime=seeded(seed,()->(List<Location>)h.runtimeType("dev.helstera.api.behavior.Targeters")
                    .getMethod("resolveLocations",String.class,h.contextType).invoke(null,selector,ctx));
            // Observe every block in the prepared volume, including unexpected dispatcher effects.
            Map<Block,BlockData> before=new LinkedHashMap<>();
            for(int x=-70;x<=120;x++)for(int y=177;y<=185;y++)for(int z=-8;z<=8;z++) {
                Block block=world.getBlockAt(x,y,z);before.put(block,block.getBlockData().clone());
            }
            String mechanic="setblock{type=DIAMOND_BLOCK}",source=mechanic+" "+selector,binding=map(source);
            Object action=h.nativeType("io.lumine.mythic.core.skills.mechanics.SetBlockTypeMechanic")
                    .getConstructor(h.executorType,java.io.File.class,String.class,h.configType)
                    .newInstance(h.nativeSkills,h.source.toFile(),mechanic,h.configImpl.getConstructor(String.class).newInstance(mechanic));
            boolean nativeAccepted=true;
            for(Object p:selected)nativeAccepted&="SUCCESS".equals(action.getClass().getMethod("castAtLocation",h.skillMetadata,nativeLocation).invoke(action,metadata,p).toString());
            List<String> nativeEffects=changed(before);restore(before);
            Object key=h.skills.getClass().getMethod("bindAction",String.class).invoke(h.skills,binding);
            boolean runtimeAccepted=key!=null&&seeded(seed,()->Boolean.TRUE.equals(h.skills.getClass().getMethod("runAction",String.class,h.contextType).invoke(h.skills,key,ctx)));
            List<String> runtimeEffects=changed(before);restore(before);
            var row=new LinkedHashMap<String,Object>();
            row.put("path",path);row.put("case",id);row.put("selector",selector);row.put("source",source);row.put("binding",binding);
            row.put("scene",scene);row.put("inputs",inputs);row.put("seed",seed);row.put("threadId",Thread.currentThread().threadId());
            row.put("rngProbe",seeded(seed,()->List.of(ThreadLocalRandom.current().nextDouble(),ThreadLocalRandom.current().nextDouble(),ThreadLocalRandom.current().nextDouble())));
            row.put("parsed",parsed);row.put("nativeRaw",locations(nativeRaw));row.put("nativeSelected",locations(nativeSelected));row.put("runtimeSelected",locations(runtime));
            row.put("nativeEffects",nativeEffects);row.put("runtimeEffects",runtimeEffects);row.put("nativeAccepted",nativeAccepted);row.put("runtimeAccepted",runtimeAccepted);
            rows.add(row);getLogger().info("RANDOM_LOCATION_ROW|path="+path+"|case="+id+"|observed=true");
        } finally {restore(preparation);}
    }

    private <T>T seeded(long seed,Callable<T> query)throws Exception {
        Thread thread=Thread.currentThread();long previous=seedField.getLong(thread);seedField.setLong(thread,seed);
        try{return query.call();}finally{seedField.setLong(thread,previous);}
    }
    private Object adapt(Location p)throws Exception{return h.nativeType("io.lumine.mythic.bukkit.BukkitAdapter").getMethod("adapt",Location.class).invoke(null,p);}
    private List<Location> points(Collection<?> values)throws Exception {
        var result=new ArrayList<Location>();for(Object p:values)result.add((Location)h.nativeType("io.lumine.mythic.bukkit.BukkitAdapter").getMethod("adapt",nativeLocation).invoke(null,p));return result;
    }
    private static String location(Location p){return String.format(Locale.ROOT,"%s:%.9f,%.9f,%.9f,%.9f,%.9f",p.getWorld().getName(),p.getX(),p.getY(),p.getZ(),p.getYaw()==0?0d:(double)p.getYaw(),p.getPitch()==0?0d:(double)p.getPitch());}
    private static List<String> locations(List<Location> points){return points.stream().map(RandomLocationTargeterProbe::location).sorted().toList();}
    private static void restore(Map<Block,BlockData> blocks){blocks.forEach((block,data)->{if(!block.getBlockData().equals(data))block.setBlockData(data,false);});}
    private static List<String> changed(Map<Block,BlockData> blocks){return blocks.entrySet().stream().filter(e->!e.getKey().getBlockData().equals(e.getValue())).map(e->location(e.getKey().getLocation())+"="+e.getKey().getType()).sorted().toList();}
    private String map(String source)throws Exception {
        if(path.equals("direct"))return source;
        var yaml=new YamlConfiguration();yaml.set("write.Skills",List.of(source));yaml.save(h.source.toFile());
        @SuppressWarnings("unchecked")var plans=(List<Map<String,Object>>)h.importer.getClass().getMethod("convert",java.nio.file.Path.class).invoke(h.importer,h.source);
        var plan=plans.stream().filter(p->"skills.yml".equals(p.get("target-file"))).findFirst().orElseThrow();
        yaml.loadFromString(plan.get("content").toString());var actions=yaml.getStringList("skills.write.on-decision");
        if(actions.size()!=1||plans.stream().anyMatch(p->"WARN".equals(p.get("severity"))))throw new IllegalStateException("Import rejected "+source);
        return actions.getFirst();
    }
}
