package dev.helstera.test.randomspawn;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.nio.file.Files;
import java.util.*;

/** Native and runtime observations, checked by an independent geometry/effect auditor. */
public final class BlockTargeterProbe extends JavaPlugin {
    private final List<Map<String, Object>> rows = new ArrayList<>();
    private final Map<Block, BlockData> sceneBlocks = new LinkedHashMap<>();
    private VariablePlaceholderAudit.Harness h;
    private World world;
    private Player player;
    private String path, scene;
    private Location origin;
    private List<Entity> entities = List.of();
    private List<Location> locations = List.of();
    private Object runtimeContext;
    private Class<?> nativeLocation;

    @Override public void onEnable() {
        new BukkitRunnable() {
            int attempts;
            @Override public void run() {
                Player joined = Bukkit.getPlayerExact("BlockTester");
                if (joined != null && joined.isOnline()) { cancel(); execute(joined); }
                else if (++attempts >= 120) { cancel(); getLogger().severe("BLOCKS_FAILED|missing-player"); }
            }
        }.runTaskTimer(this, 100, 20);
    }

    private void execute(Player joined) {
        player = joined;
        Location previous = player.getLocation(); GameMode mode = player.getGameMode();
        try {
            world = new WorldCreator("block-query-fixture").generateStructures(false).generator(new ChunkGenerator() {
                @Override public ChunkData generateChunkData(World w, Random random, int x, int z, BiomeGrid biomes) {
                    return createChunkData(w);
                }
            }).createWorld();
            if (world == null || world.getMinHeight() != -64 || world.getMaxHeight() != 320)
                throw new IllegalStateException("Unexpected fixture height");
            world.setGameRule(GameRule.DO_MOB_SPAWNING, false);
            for (int x = -2; x <= 3; x++) for (int z = -2; z <= 2; z++) world.getChunkAt(x, z).setForceLoaded(true);
            player.setGameMode(GameMode.SPECTATOR); player.teleport(new Location(world, 40.25, 180, 40.25));
            for (String route : List.of("direct", "imported")) run(route);
            getLogger().info("BLOCKS_SUMMARY|observations=" + rows.size() + "|passed=true");
            player.sendMessage("BLOCKS_DONE");
        } catch (Throwable failure) {
            getLogger().severe("BLOCKS_FAILED|" + failure); failure.printStackTrace();
        } finally {
            try {
                var yaml = new YamlConfiguration(); yaml.set("observations", rows);
                Files.createDirectories(getDataFolder().toPath());
                yaml.save(getDataFolder().toPath().resolve("observations.yml").toFile());
            } catch (Exception failure) { getLogger().severe("BLOCKS_FAILED|snapshot=" + failure); }
            clearScene(); player.teleport(previous); player.setGameMode(mode);
            if (world != null) for (Chunk chunk : world.getLoadedChunks()) chunk.setForceLoaded(false);
        }
    }

    private Location point(double x, double y, double z) { return new Location(world, x, y, z); }
    private Location base() { return point(.25, 180, .25); }

    private void run(String route) throws Exception {
        path = route;
        try (var harness = new VariablePlaceholderAudit.Harness(this,
                Bukkit.getPluginManager().getPlugin("helsteraMobs"), Bukkit.getPluginManager().getPlugin("MythicMobs"),
                point(128, 150, 128), false)) {
            h = harness;
            nativeLocation = h.nativeType("io.lumine.mythic.api.adapters.AbstractLocation");
            h.a.teleport(point(40.25, 180, .25)); h.b.teleport(point(8.25, 180, .25)); h.c.teleport(point(24.25, 180, .25));
            origin = base(); entities = List.of(); locations = List.of(); setScene("terrain");
            row("highest-default", "@highestblock");
            row("highest-mutate", "@highestblock{y=2;centered=true}");
            row("highest-filter", "@highestblock{blocktypes=STONE}");
            row("highest-reject", "@highestblock{blockignores=STONE}");
            origin = point(24.25, 180, .25); row("highest-origin", "@highestblock"); origin = base();
            row("bir-empty", "@BIR"); row("bic-empty", "@BIC");
            entities = List.of(h.b, h.c, h.b);
            row("bir-entities", "@blocksInRadius"); row("bir-alias", "@BIR");
            row("bir-origin", "@BIR{nearorigin=true;r=1}");
            row("bir-origin-priority", "@BIR{nearorigin=false;no=true}");
            row("bic-entities", "@blocksInChunk"); row("bic-alias", "@BIC");
            entities = List.of(); locations = List.of(h.b.getLocation(), h.c.getLocation(), h.b.getLocation());
            row("bir-locations", "@BIR"); row("bic-locations", "@BIC");
            locations = List.of();
            row("bno-default", "@blocksNearOrigin"); row("bno-alias", "@BNO");
            row("radius-priority", "@BNO{r=2;radius=1}");
            row("radius-quoted", "@BNO{r=\"2\"}"); row("radius-invalid", "@BNO{r=bad}");
            row("radius-range", "@BNO{r=1to1}"); row("radius-fractional", "@BNO{r=1.5;na=false}");
            row("radius-zero", "@BNO{r=0;na=false}"); row("radius-negative", "@BNO{r=-1;na=false}");
            row("radius-cube", "@BNO{r=1;shape=CUBE;na=false}");
            row("shape-invalid", "@BNO{r=1;shape=bad;na=false}");
            row("shape-priority", "@BNO{r=1;shape=SPHERE;s=CUBE;na=false}");
            row("vertical-priority", "@BNO{r=1;radiusy=0;ry=1;shape=CUBE;na=false}");
            row("vertical-zero-sphere", "@BNO{r=1;ry=0;na=false}");
            row("vertical-alias", "@BNO{r=1;yr=2;na=false}");
            row("mask-only-air", "@BNO{r=1;onlyair=true;noair=true}");
            row("mask-all", "@BNO{r=1;noair=false}");
            row("mask-priority", "@BNO{r=1;noair=true;na=false}");
            row("mask-after-offset", "@BNO{r=0;shape=CUBE;x=2}");
            row("filter-stone", "@BNO{blocktypes=STONE}");
            row("filter-pattern", "@BNO{blocktypes=#ORE}");
            row("filter-ignore", "@BNO{blockignores=STONE,DIRT}");
            row("limit-nearest", "@BNO{limit=1;sort=NEAREST}");
            row("limit-furthest", "@BNO{limit=1;sort=FURTHEST;blocktypes=EMERALD_ORE}");
            row("limit-quoted", "@BNO{r=1;limit=\"1\"}");
            row("noise-zero", "@BNO{r=1;noise=0}"); row("noise-one", "@BNO{r=1;noise=1}");
            row("noise-negative", "@BNO{r=1;noise=-1}");
            row("guard-true", "@BNO{r=1;conditions=[ - distance{d=0to1000} true ]}");
            row("guard-false", "@BNO{r=1;conditions=[ - distance{d=1000to2000} true ]}");
            row("bic-origin", "@BIC{no=true}");
            row("bic-mutate-noop", "@BIC{no=true;x=5;y=8;centered=true}");
            row("bic-filter", "@BIC{no=true;blocktypes=GOLD_BLOCK}");
            row("bic-all-filter", "@BIC{no=true;na=false;blocktypes=GOLD_BLOCK}");
            row("bic-only-air", "@BIC{no=true;oa=true;blocktypes=STONE}");
            row("bic-nearest", "@BIC{no=true;limit=1;sort=NEAREST}");
            origin = point(.25, -64, .25);
            row("bic-maxheight", "@BIC{no=true;na=false;limit=1;sort=FURTHEST}", false);
            origin = point(-.25, 180, -.25); row("bic-negative", "@BIC{no=true}"); origin = base();

            for (int index = 0; index < 10; index++) {
                setScene("ob-" + index); row("obstruction-" + index, "@obstructingBlock");
            }
            setScene("empty"); row("obstruction-empty", "@obstructingBlock");
            setScene("ob-0"); row("obstruction-mutate-noop", "@obstructingBlock{x=9;y=9;centered=true}");
            row("obstruction-filter", "@obstructingBlock{blockignores=STONE}");
            for (int yaw : new int[]{90, -90, 180, 360, -360, 44, 45}) {
                Location position = point(40.25, 180, .25); position.setYaw(yaw); h.a.teleport(position);
                setScene("yaw-" + yaw); row("obstruction-yaw-" + yaw, "@obstructingBlock");
            }
            h.a.teleport(point(40.25, 180, .25));
            setScene("vein");
            row("vein-default", "@blockVein"); row("vein-alias", "@vein"); row("vein-short", "@bv");
            row("vein-budget-one", "@bv{l=1}"); row("vein-max-alias", "@bv{max=1}");
            row("vein-material-budget-alias", "@bv{m=STONE}");
            row("vein-final-limit", "@bv{limit=1;sort=NEAREST}");
            row("vein-zero", "@bv{l=0}"); row("vein-negative", "@bv{l=-1}");
            row("vein-mutate-noop", "@bv{x=10;y=5;centered=true}");
            row("vein-material", "@bv{t=DIRT;match=false}");
            row("vein-material-priority", "@bv{blocktypes=STONE;t=DIRT}");
            row("vein-blocktype-filter", "@bv{blocktype=DIRT;match=false}");
            setScene("vein-open"); row("vein-origin-reject", "@bv");
            row("vein-origin-optional", "@bv{originMustMatch=false}");
            row("vein-origin-priority", "@bv{originMustMatch=true;match=false}");
            setScene("vein-isolated"); row("vein-isolated", "@bv");
        } finally { clearScene(); }
    }

    private void setScene(String name) {
        clearScene(); scene = name;
        if (name.equals("terrain")) {
            // A section containing only air variants is optimized to AIR by Paper.
            put(-2,180,2,Material.STONE);
            put(0,180,0,Material.STONE); put(1,180,0,Material.DIRT); put(-1,180,0,Material.CAVE_AIR);
            put(0,181,0,Material.GOLD_BLOCK); put(0,180,1,Material.GLASS); put(2,180,0,Material.EMERALD_ORE);
            put(0,180,-2,Material.IRON_ORE); put(1,181,1,Material.DIAMOND_ORE); put(0,190,0,Material.STONE);
            put(8,180,0,Material.DIAMOND_ORE); put(24,180,0,Material.EMERALD_ORE);
            put(-16,180,-16,Material.STONE); put(-1,180,-1,Material.DIRT); put(40,180,0,Material.COPPER_BLOCK);
            if (world.getBlockAt(-1,180,0).getType() != Material.CAVE_AIR
                    || world.getBlockAt(0,320,0).getType() != Material.VOID_AIR)
                throw new IllegalStateException("Unexpected air variant fixture");
        } else if (name.startsWith("ob-")) {
            int[][] points = {{40,180,1},{40,180,0},{40,181,1},{40,182,1},{41,180,1},
                    {41,181,1},{41,182,1},{39,180,1},{39,181,1},{39,182,1}};
            int[] selected = points[Integer.parseInt(name.substring(3))]; put(selected[0],selected[1],selected[2],Material.STONE);
        } else if (name.startsWith("yaw-")) {
            int yaw = Integer.parseInt(name.substring(4));
            int quadrant = Math.round(yaw / 90f);
            int dx = quadrant == 1 ? -1 : quadrant == -1 ? 1 : 0;
            int dz = quadrant == 2 ? -1 : dx == 0 ? 1 : 0;
            put(40+dx,180,dz,Material.STONE);
        } else if (name.startsWith("vein")) {
            if (!name.equals("vein-open")) put(0,180,0,Material.STONE);
            if (!name.equals("vein-isolated")) {
                put(1,180,0,Material.STONE); put(-1,179,-1,Material.STONE); put(2,181,1,Material.STONE);
                put(10,180,0,Material.STONE); put(0,180,1,Material.DIRT);
            }
        }
    }
    private void put(int x,int y,int z,Material material) { Block block=world.getBlockAt(x,y,z); sceneBlocks.putIfAbsent(block,block.getBlockData().clone()); block.setType(material,false); }
    private void clearScene() { restore(sceneBlocks); sceneBlocks.clear(); }

    private void row(String id, String selector) throws Exception { row(id, selector, true); }
    private void row(String id, String selector, boolean effects) throws Exception {
        Object nativeCaster = h.active.get(h.a);
        Object metadata = h.metadataType.getConstructor(h.triggerType,h.skillCaster,h.abstractEntity).newInstance(
                h.triggerType.getMethod("get",String.class).invoke(null,"API"),nativeCaster,h.adapt.invoke(null,player));
        h.skillMetadata.getMethod("setOrigin",nativeLocation).invoke(metadata,adapt(origin.clone()));
        List<Object> inputEntities=new ArrayList<>(); for(Entity entity:entities)inputEntities.add(h.adapt.invoke(null,entity));
        h.skillMetadata.getMethod("setEntityTargets",Collection.class).invoke(metadata,inputEntities);
        if(!locations.isEmpty()) {List<Object> points=new ArrayList<>();for(Location point:locations)points.add(adapt(point.clone()));h.skillMetadata.getMethod("setLocationTargets",Collection.class).invoke(metadata,points);}
        List<String> nativeEntityInputs = nativeInputs(metadata, true), nativeLocationInputs = nativeInputs(metadata, false);
        runtimeContext=h.context(h.a,null,player,origin,null);
        runtimeContext=h.contextType.getMethod("withEntityTargets",List.class).invoke(runtimeContext,entities);
        if(!locations.isEmpty())runtimeContext=h.contextType.getMethod("withLocationTargets",List.class).invoke(runtimeContext,locations);
        Object targeter=h.nativeSkills.getClass().getMethod("getTargeter",String.class).invoke(h.nativeSkills,selector);
        @SuppressWarnings("unchecked") Collection<Object> picked=(Collection<Object>)targeter.getClass().getMethod("getLocations",h.skillMetadata).invoke(targeter,metadata);
        int rawCount=picked.size();h.skillMetadata.getMethod("setLocationTargets",Collection.class).invoke(metadata,picked);
        targeter.getClass().getMethod("filter",h.skillMetadata).invoke(targeter,metadata);
        @SuppressWarnings("unchecked") Collection<Object> filtered=(Collection<Object>)h.skillMetadata.getMethod("getLocationTargets").invoke(metadata);
        List<Location> nativePoints=new ArrayList<>();for(Object point:filtered)nativePoints.add(point(point));
        List<String> beforeChunks=chunks();
        @SuppressWarnings("unchecked") List<Location> actual=(List<Location>)h.runtimeType("dev.helstera.api.behavior.Targeters").getMethod("resolveLocations",String.class,h.contextType).invoke(null,selector,runtimeContext);
        List<String> afterChunks=chunks();
        Map<Block,BlockData> before=new LinkedHashMap<>();
        for(int x=-3;x<=44;x++)for(int y=178;y<=193;y++)for(int z=-3;z<=3;z++){Block block=world.getBlockAt(x,y,z);before.put(block,block.getBlockData().clone());}
        for(Block block:sceneBlocks.keySet())before.putIfAbsent(block,block.getBlockData().clone());
        for(Location point:nativePoints)if(point.getY()>=-64&&point.getY()<320){Block block=point.getBlock();before.putIfAbsent(block,block.getBlockData().clone());}
        for(Location point:actual)if(point.getY()>=-64&&point.getY()<320){Block block=point.getBlock();before.putIfAbsent(block,block.getBlockData().clone());}
        String mechanic="setblock{type=DIAMOND_BLOCK}", source=mechanic+" "+selector,binding=map(source);
        Object action=h.nativeType("io.lumine.mythic.core.skills.mechanics.SetBlockTypeMechanic")
                .getConstructor(h.executorType,java.io.File.class,String.class,h.configType)
                .newInstance(h.nativeSkills,h.source.toFile(),mechanic,h.configImpl.getConstructor(String.class).newInstance(mechanic));
        boolean accepted=true;
        if(effects)for(Object point:filtered)accepted &= "SUCCESS".equals(action.getClass().getMethod("castAtLocation",h.skillMetadata,nativeLocation).invoke(action,metadata,point).toString());
        List<String> nativeEffects=changed(before);restore(before);
        Object key=h.skills.getClass().getMethod("bindAction",String.class).invoke(h.skills,binding);
        boolean runtimeAccepted=key!=null;
        if(effects&&runtimeAccepted)runtimeAccepted=Boolean.TRUE.equals(h.skills.getClass().getMethod("runAction",String.class,h.contextType).invoke(h.skills,key,runtimeContext));
        List<String> runtimeEffects=changed(before);restore(before);
        var row=new LinkedHashMap<String,Object>();
        row.put("path",path);row.put("case",id);row.put("selector",selector);row.put("source",source);row.put("binding",binding);
        row.put("scene",scene);row.put("blocks",sceneBlocks.keySet().stream().map(block->location(block.getLocation())+"="+block.getType()).sorted().toList());
        row.put("origin",location(origin));row.put("caster",location(h.a.getLocation()));
        row.put("entityInputs",entities.stream().map(entity->location(entity.getLocation())).toList());row.put("locationInputs",locations(locations));
        row.put("nativeEntityInputs", nativeEntityInputs);row.put("nativeLocationInputs", nativeLocationInputs);
        row.put("nativeRawCount",rawCount);row.put("nativeSelected",locations(nativePoints));row.put("runtimeSelected",locations(actual));
        row.put("nativeEffects",nativeEffects);row.put("runtimeEffects",runtimeEffects);row.put("nativeAccepted",accepted);row.put("runtimeAccepted",runtimeAccepted);
        row.put("mutations",effects);row.put("loadedBefore",beforeChunks);row.put("loadedAfter",afterChunks);
        rows.add(row);getLogger().info("BLOCKS_ROW|path="+path+"|case="+id+"|observed=true");
    }
    private List<String> chunks(){return Arrays.stream(world.getLoadedChunks()).map(chunk->chunk.getX()+","+chunk.getZ()).sorted().toList();}
    private List<String> nativeInputs(Object metadata, boolean entity) throws Exception {
        var values = (Collection<?>) h.skillMetadata.getMethod(entity ? "getEntityTargets" : "getLocationTargets").invoke(metadata);
        List<Location> points = new ArrayList<>();
        if (values != null) for (Object value : values)
            points.add(point(entity ? h.abstractEntity.getMethod("getLocation").invoke(value) : value));
        return locations(points);
    }
    private Object adapt(Location point)throws Exception{return h.nativeType("io.lumine.mythic.bukkit.BukkitAdapter").getMethod("adapt",Location.class).invoke(null,point);}
    private Location point(Object point)throws Exception{return (Location)h.nativeType("io.lumine.mythic.bukkit.BukkitAdapter").getMethod("adapt",nativeLocation).invoke(null,point);}
    private static String location(Location point){return String.format(Locale.ROOT,"%s:%.6f,%.6f,%.6f,%.6f,%.6f",point.getWorld().getName(),point.getX(),point.getY(),point.getZ(),(double)point.getYaw(),(double)point.getPitch());}
    private static List<String> locations(List<Location> points){return points.stream().map(BlockTargeterProbe::location).sorted().toList();}
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
