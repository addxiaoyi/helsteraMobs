package dev.helstera.test.randomspawn;

import io.papermc.paper.event.world.StructuresLocateEvent;
import io.papermc.paper.math.Position;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.nio.file.Files;
import java.util.*;

/** Real Paper query events plus unmodified seeded-world searches and block effects. */
@SuppressWarnings("deprecation")
public final class StructureTargeterProbe extends JavaPlugin implements Listener {
    private final List<Map<String,Object>> rows = new ArrayList<>();
    private final List<Map<String,Object>> queries = new ArrayList<>();
    private VariablePlaceholderAudit.Harness h;
    private World world;
    private Player player;
    private String path, mode, phase;
    private Class<?> nativeLocation;

    @Override public void onEnable() {
        Bukkit.getPluginManager().registerEvents(this, this);
        new BukkitRunnable() {
            int attempts;
            @Override public void run() {
                Player joined = Bukkit.getPlayerExact("StructureTester");
                if (joined != null && joined.isOnline()) { cancel(); execute(joined); }
                else if (++attempts >= 120) { cancel(); getLogger().severe("STRUCTURE_FAILED|missing-player"); }
            }
        }.runTaskTimer(this, 100, 20);
    }

    private void execute(Player joined) {
        player = joined; world = player.getWorld();
        try {
            if (!world.getName().equals("integration-world") || world.getSeed() != 281474976710655L)
                throw new IllegalStateException("Unexpected fixture world/seed");
            for (String route : List.of("direct", "imported")) run(route);
            getLogger().info("STRUCTURE_SUMMARY|observations="+rows.size()+"|passed=true");
            player.sendMessage("STRUCTURE_DONE");
        } catch (Throwable failure) {
            getLogger().severe("STRUCTURE_FAILED|"+failure); failure.printStackTrace();
        } finally {
            phase = null;
            try {
                var yaml = new YamlConfiguration(); yaml.set("observations", rows);
                Files.createDirectories(getDataFolder().toPath());
                yaml.save(getDataFolder().toPath().resolve("observations.yml").toFile());
            } catch (Exception failure) { getLogger().severe("STRUCTURE_FAILED|snapshot="+failure); }
        }
    }

    @EventHandler
    public void query(StructuresLocateEvent event) {
        if (phase == null || event.getWorld() != world) return;
        var observation = new LinkedHashMap<String,Object>();
        observation.put("phase",phase); observation.put("origin",location(event.getOrigin()));
        observation.put("types",event.getStructures().stream().map(type->type.getKey().toString()).sorted().toList());
        observation.put("radius",event.getRadius()); observation.put("unexplored",event.shouldFindUnexplored());
        observation.put("mode",mode); queries.add(observation);
        if (mode.equals("cancel")) event.setCancelled(true);
        else if (!mode.equals("natural")) event.setResult(new StructuresLocateEvent.Result(
                Position.block(mode.equals("alternate") ? 12 : 8,180,0),event.getStructures().getFirst()));
    }

    private Location point(double x,double y,double z) { return new Location(world,x,y,z); }

    private void run(String route) throws Exception {
        path=route;
        try (var harness = new VariablePlaceholderAudit.Harness(this,
                Bukkit.getPluginManager().getPlugin("helsteraMobs"), Bukkit.getPluginManager().getPlugin("MythicMobs"),
                point(128,150,128),false)) {
            h=harness; nativeLocation=h.nativeType("io.lumine.mythic.api.adapters.AbstractLocation");
            h.a.teleport(point(40.25,180,.25));
            mode="result";
            row("default","@nearestStructure");
            row("type-alias","@nearestStructure{t=VILLAGE}");
            row("type-priority","@nearestStructure{t=VILLAGE;type=STRONGHOLD}");
            row("type-invalid","@nearestStructure{type=missing;t=village}");
            row("type-quoted","@nearestStructure{type=\"village\"}");
            row("type-namespaced","@nearestStructure{type=minecraft:village}");
            row("type-dynamic","@nearestStructure{type=<caster.var.type>}");
            for (String type : new TreeSet<>(StructureType.getStructureTypes().keySet()))
                row("type-"+type,"@nearestStructure{type="+type+"}");
            row("radius-alias","@nearestStructure{r=2}");
            row("radius-priority","@nearestStructure{r=2;radius=3}");
            row("radius-invalid","@nearestStructure{radius=bad;r=2}");
            row("radius-quoted","@nearestStructure{radius=\"2\"}");
            row("radius-dynamic","@nearestStructure{r=<caster.var.radius>}");
            row("radius-range","@nearestStructure{r=2to2}");
            row("radius-zero","@nearestStructure{r=0}");
            row("radius-negative","@nearestStructure{r=-1}");
            row("unexplored","@nearestStructure{unexplored=true}");
            row("unexplored-alias","@nearestStructure{u=true}");
            row("unexplored-priority","@nearestStructure{unexplored=false;u=true}");
            row("unexplored-quoted","@nearestStructure{u=\"true\"}");
            row("unexplored-invalid","@nearestStructure{u=bad}");
            row("offsets-noop","@nearestStructure{x=4;y=6;z=8;centered=true}");
            row("filter-stone","@nearestStructure{blocktypes=STONE}");
            row("filter-reject","@nearestStructure{blockignores=STONE}");
            row("limit","@nearestStructure{limit=1;sort=NEAREST}");
            row("guard-true","@nearestStructure{conditions=[ - distance{d=0to10000} true ]}");
            row("guard-false","@nearestStructure{conditions=[ - distance{d=10000to20000} true ]}");
            h.a.teleport(point(-40.25,180,-.25)); row("moved-caster","@nearestStructure");
            h.a.teleport(point(40.25,180,.25));
            mode="alternate"; row("changed-provider","@nearestStructure");
            mode="cancel"; row("event-cancel","@nearestStructure");
            mode="natural";
            row("natural-stronghold","@nearestStructure{r=0}");
            row("natural-stronghold-filter","@nearestStructure{r=0;blocktypes=STONE}");
            row("natural-missing","@nearestStructure{type=fortress;r=0}");
        } finally { phase=null; }
    }

    private void row(String id,String selector) throws Exception {
        queries.clear();
        Location origin=point(800.25,180,800.25);
        Object metadata=h.meta(h.a,player);
        h.skillMetadata.getMethod("setOrigin",nativeLocation).invoke(metadata,adapt(origin));
        Object context=h.context(h.a,null,player,origin,null);
        context=h.contextType.getMethod("withLocationTargets",List.class).invoke(context,List.of(point(16,180,0)));
        Object targeter=h.nativeSkills.getClass().getMethod("getTargeter",String.class).invoke(h.nativeSkills,selector);
        var parsed = new LinkedHashMap<String,Object>();
        for(String field:List.of("structureType","radius","unexplored")) {
            var member=targeter.getClass().getDeclaredField(field);member.setAccessible(true);Object value=member.get(targeter);
            parsed.put(field,value instanceof StructureType type ? type.getName() : value);
        }
        Map<Block,BlockData> before=new LinkedHashMap<>();
        if (!mode.equals("natural")) for(int x:new int[]{8,12}) {
            Block block=world.getBlockAt(x,180,0); before.put(block,block.getBlockData().clone());block.setType(Material.STONE,false);
        }
        Map<Block,BlockData> scene=new LinkedHashMap<>();
        try {
            phase="native";
            @SuppressWarnings("unchecked") Collection<Object> picked=(Collection<Object>)targeter.getClass().getMethod("getLocations",h.skillMetadata).invoke(targeter,metadata);
            List<Location> nativeRaw=points(picked);
            // Natural found structures have a fixed marker material for filter/effect checks.
            for(Location location:nativeRaw) {
                Block block=location.getBlock(); before.putIfAbsent(block,block.getBlockData().clone());
                block.setType(Material.STONE,false);
            }
            h.skillMetadata.getMethod("setLocationTargets",Collection.class).invoke(metadata,picked);
            targeter.getClass().getMethod("filter",h.skillMetadata).invoke(targeter,metadata);
            @SuppressWarnings("unchecked") Collection<Object> selected=(Collection<Object>)h.skillMetadata.getMethod("getLocationTargets").invoke(metadata);
            List<Location> nativePoints=points(selected);
            phase="runtime";
            @SuppressWarnings("unchecked") List<Location> runtimePoints=(List<Location>)h.runtimeType("dev.helstera.api.behavior.Targeters")
                    .getMethod("resolveLocations",String.class,h.contextType).invoke(null,selector,context);
            for(Block block:before.keySet())scene.put(block,block.getBlockData().clone());
            for(Location location:runtimePoints)scene.putIfAbsent(location.getBlock(),location.getBlock().getBlockData().clone());
            String mechanic="setblock{type=DIAMOND_BLOCK}", source=mechanic+" "+selector,binding=map(source);
            Object action=h.nativeType("io.lumine.mythic.core.skills.mechanics.SetBlockTypeMechanic")
                    .getConstructor(h.executorType,java.io.File.class,String.class,h.configType)
                    .newInstance(h.nativeSkills,h.source.toFile(),mechanic,h.configImpl.getConstructor(String.class).newInstance(mechanic));
            phase=null;boolean nativeAccepted=true;
            for(Object location:selected)nativeAccepted &= "SUCCESS".equals(action.getClass().getMethod("castAtLocation",h.skillMetadata,nativeLocation).invoke(action,metadata,location).toString());
            List<String> nativeEffects=changed(scene);restore(scene);
            Object key=h.skills.getClass().getMethod("bindAction",String.class).invoke(h.skills,binding);
            phase="effect";
            boolean accepted=key!=null && Boolean.TRUE.equals(h.skills.getClass().getMethod("runAction",String.class,h.contextType).invoke(h.skills,key,context));
            phase=null;
            List<String> runtimeEffects=changed(scene);restore(scene);
            List<String> structures=new ArrayList<>();
            if(mode.equals("natural"))for(Location location:nativeRaw)
                for(var generated:world.getStructures(location.getBlockX()>>4,location.getBlockZ()>>4))
                    structures.add(generated.getStructure().getKey().toString());
            var row=new LinkedHashMap<String,Object>();
            row.put("path",path);row.put("case",id);row.put("selector",selector);row.put("source",source);row.put("binding",binding);
            row.put("mode",mode);row.put("seed",world.getSeed());row.put("caster",location(h.a.getLocation()));row.put("origin",location(origin));
            row.put("parsed",parsed);row.put("queries",new ArrayList<>(queries));row.put("naturalStructures",structures.stream().sorted().toList());
            row.put("nativeRaw",locations(nativeRaw));row.put("nativeSelected",locations(nativePoints));row.put("runtimeSelected",locations(runtimePoints));
            row.put("nativeEffects",nativeEffects);row.put("runtimeEffects",runtimeEffects);row.put("nativeAccepted",nativeAccepted);row.put("runtimeAccepted",accepted);
            rows.add(row);getLogger().info("STRUCTURE_ROW|path="+path+"|case="+id+"|observed=true");
        } finally {phase=null;restore(before);}
    }

    private Object adapt(Location location)throws Exception{return h.nativeType("io.lumine.mythic.bukkit.BukkitAdapter").getMethod("adapt",Location.class).invoke(null,location);}
    private List<Location> points(Collection<?> values)throws Exception{
        var result=new ArrayList<Location>();for(Object value:values)result.add((Location)h.nativeType("io.lumine.mythic.bukkit.BukkitAdapter").getMethod("adapt",nativeLocation).invoke(null,value));return result;
    }
    private static String location(Location point){return String.format(Locale.ROOT,"%s:%.6f,%.6f,%.6f,%.6f,%.6f",point.getWorld().getName(),point.getX(),point.getY(),point.getZ(),(double)point.getYaw(),(double)point.getPitch());}
    private static List<String> locations(List<Location> points){return points.stream().map(StructureTargeterProbe::location).sorted().toList();}
    private static void restore(Map<Block,BlockData> blocks){blocks.forEach((block,data)->{if(!block.getBlockData().equals(data))block.setBlockData(data,false);});}
    private static List<String> changed(Map<Block,BlockData> before){return before.entrySet().stream().filter(entry->!entry.getKey().getBlockData().equals(entry.getValue())).map(entry->location(entry.getKey().getLocation())+"="+entry.getKey().getType()).sorted().toList();}
    private String map(String source)throws Exception{
        if(path.equals("direct"))return source;
        var yaml=new YamlConfiguration();yaml.set("write.Skills",List.of(source));yaml.save(h.source.toFile());
        @SuppressWarnings("unchecked") var plans=(List<Map<String,Object>>)h.importer.getClass().getMethod("convert",java.nio.file.Path.class).invoke(h.importer,h.source);
        var plan=plans.stream().filter(p->"skills.yml".equals(p.get("target-file"))).findFirst().orElseThrow();
        yaml.loadFromString(plan.get("content").toString());var actions=yaml.getStringList("skills.write.on-decision");
        if(actions.size()!=1||plans.stream().anyMatch(p->"WARN".equals(p.get("severity"))))throw new IllegalStateException("Import rejected "+source);
        return actions.getFirst();
    }
}
