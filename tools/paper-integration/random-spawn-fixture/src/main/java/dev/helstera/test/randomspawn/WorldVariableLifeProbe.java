package dev.helstera.test.randomspawn;

import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.world.*;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import java.util.*;

/** Actual managed instances and native ActiveMobs across real world/chunk unloads. */
public final class WorldVariableLifeProbe extends JavaPlugin implements Listener {
    private static final String WORLD = "variable-lifecycle";
    private static final List<String> SCOPES = List.of("caster", "target", "world", "global");
    private static final List<String> CASES = List.of("Saved", "Transient", "Negative", "Timed", "Expired", "Float", "String", "Location");
    private record Pair(VariablePlaceholderAudit.Harness h, UUID nativeId, UUID runtimeId) { }
    private final List<Pair> pairs = new ArrayList<>();
    private final Map<String, String> values = new HashMap<>();
    private final Map<String, Long> deadlines = new HashMap<>();
    private Plugin runtime;
    private World world;
    private UUID worldId;
    private String phase = "write";
    private boolean failed;
    private int rows;
    private int cx, cz;
    private long visualCount;
    @Override public void onEnable() {
        Bukkit.getPluginManager().registerEvents(this, this);
        later(100, this::begin);
    }
    @FunctionalInterface private interface Step { void run() throws Exception; }
    private void later(long ticks, Step step) {
        Bukkit.getScheduler().runTaskLater(this, () -> { if (failed) return; try { step.run(); }
            catch (Throwable error) { failed = true; getLogger().severe("WORLD_VAR_FAILED|phase=" + phase + "|error=" + error); error.printStackTrace(); } }, ticks);
    }
    private World createWorld() {
        return Objects.requireNonNull(new WorldCreator(WORLD).generator(new ChunkGenerator() {
            @Override public ChunkData generateChunkData(World w, Random r, int x, int z, BiomeGrid b) { return createChunkData(w); }
        }).generateStructures(false).createWorld());
    }
    private void begin() throws Exception {
        runtime = Bukkit.getPluginManager().getPlugin("helsteraMobs");
        world = createWorld(); worldId = world.getUID(); world.setKeepSpawnInMemory(false);
        world.setGameRule(GameRule.DO_MOB_SPAWNING, false);
        Plugin mythic = Bukkit.getPluginManager().getPlugin("MythicMobs");
        for (boolean imported : List.of(false, true)) {
            var h = new VariablePlaceholderAudit.Harness(this, runtime, mythic, new Location(world, 4002, 90, 4002), imported);
            for (var cow : h.mobs) { cow.setPersistent(true); cow.setRemoveWhenFarAway(false); cow.getChunk().setForceLoaded(true); }
            Location point = h.a.getLocation().clone().add(1, 0, imported ? 2 : 0);
            int id = ((Number) runtime.getClass().getMethod("spawnMobAt", String.class, Location.class, String.class)
                    .invoke(runtime, "world_variable_probe", point, null)).intValue();
            Object instance = instances().getClass().getMethod("impl", int.class).invoke(instances(), id);
            if (instance == null) throw new IllegalStateException("Managed instance not spawned");
            LivingEntity entity = (LivingEntity) ((Optional<?>) instance.getClass().getMethod("baseEntity").invoke(instance)).orElseThrow();
            entity.setGravity(false); entity.setAI(false); entity.setInvulnerable(true);
            pairs.add(new Pair(h, h.a.getUniqueId(), entity.getUniqueId()));
            getLogger().info("WORLD_VAR_ID|path=" + path(h) + "|native=" + h.a.getUniqueId() + "|runtime=" + entity.getUniqueId() + "|world=" + worldId);
        }
        Location point = pairs.getFirst().h.a.getLocation(); cx = point.getBlockX() >> 4; cz = point.getBlockZ() >> 4;
        later(20, this::unloadChunk);
    }
    private void unloadChunk() throws Exception {
        observe();
        // Drop only the test's persistent-mob force-load flag, then invoke the real chunk API.
        world.getChunkAt(cx, cz).setForceLoaded(false);
        world.save();
        boolean accepted = world.unloadChunkRequest(cx, cz);
        getLogger().info("WORLD_VAR_CHUNK_REQUEST|accepted=" + accepted + "|x=" + cx + "|z=" + cz);
        if (!accepted) throw new IllegalStateException("Chunk unload rejected");
        later(70, () -> {
            boolean absent = pairs.stream().allMatch(p -> Bukkit.getEntity(p.nativeId) == null && Bukkit.getEntity(p.runtimeId) == null);
            getLogger().info("WORLD_VAR_CHUNK_UNLOADED|loaded=" + world.isChunkLoaded(cx, cz) + "|entitiesAbsent=" + absent);
            if (world.isChunkLoaded(cx, cz) || !absent) throw new IllegalStateException("Chunk not actually unloaded");
            world.getChunkAt(cx, cz).setForceLoaded(true); world.getChunkAt(cx, cz).load(); world.getChunkAt(cx, cz).getEntities();
            phase = "chunk"; later(30, () -> { observe(); unloadWorld("world"); });
        });
    }
    private Object instances() throws Exception { return runtime.getClass().getMethod("instances").invoke(runtime); }
    private Object instance(UUID id) throws Exception {
        for (Object instance : (Collection<?>) instances().getClass().getMethod("allImpl").invoke(instances())) {
            if (((Optional<?>) instance.getClass().getMethod("boundEntityId").invoke(instance)).filter(id::equals).isPresent()) return instance;
        }
        return null;
    }
    private void unloadWorld(String next) throws Exception {
        boolean accepted = Bukkit.unloadWorld(world, true);
        boolean absent = pairs.stream().allMatch(p -> Bukkit.getEntity(p.nativeId) == null && Bukkit.getEntity(p.runtimeId) == null);
        getLogger().info("WORLD_VAR_WORLD_REQUEST|next=" + next + "|accepted=" + accepted + "|absent=" + (Bukkit.getWorld(worldId) == null)+"|entitiesAbsent="+absent);
        if (!accepted || Bukkit.getWorld(worldId) != null || !absent) throw new IllegalStateException("World not actually unloaded");
        later(30, () -> {
            world = createWorld();
            if (!worldId.equals(world.getUID())) throw new IllegalStateException("World identity changed");
            world.getChunkAt(cx, cz).setForceLoaded(true); world.getChunkAt(cx, cz).load(); world.getChunkAt(cx, cz).getEntities(); phase = next;
            later(30, () -> {
                observe();
                if (next.equals("world")) { clear(); unloadWorld("final"); }
                else {
                    for (Pair p : pairs) {
                        ((LivingEntity) Objects.requireNonNull(Bukkit.getEntity(p.nativeId))).setInvulnerable(false);
                        ((LivingEntity) Objects.requireNonNull(Bukkit.getEntity(p.runtimeId))).setInvulnerable(false);
                        ((LivingEntity) Bukkit.getEntity(p.nativeId)).setHealth(0);
                        ((LivingEntity) Bukkit.getEntity(p.runtimeId)).setHealth(0);
                    }
                    later(30, this::deathCleanup);
                }
            });
        });
    }
    private String path(VariablePlaceholderAudit.Harness h) { return h.imported ? "imported" : "direct"; }
    private String name(VariablePlaceholderAudit.Harness h, String scope, String kind) { return "WorldLife" + (h.imported ? "I" : "D") + scope + kind; }
    private Object context(Pair p, LivingEntity target) throws Exception {
        Object inst = instance(p.runtimeId);
        if (inst == null) throw new IllegalStateException("Managed instance missing after " + phase + ":" + p.runtimeId);
        var h = p.h;
        return h.contextType.getMethod("continueExecution", h.modelType, LivingEntity.class, double.class, double.class,
                        long.class, String.class, String.class, Entity.class, List.class, Location.class, h.treeType)
                .invoke(null, inst, target, 1.0, 0.0, 0L, "variables", null, target, List.of(target), origin(), h.newTree(target, target, origin()));
    }
    private Location origin() { return new Location(world, 4100.25, 90.5, 4001.75); }
    private Object meta(Pair p, LivingEntity nativeEntity) throws Exception {
        var h = p.h; Object active = h.manager.getClass().getMethod("getMythicMobInstance", Entity.class).invoke(h.manager, nativeEntity);
        if (active == null) throw new IllegalStateException("Native instance missing after " + phase);
        h.active.put(nativeEntity, active);
        Object meta = h.meta(nativeEntity, nativeEntity);
        h.metadataType.getMethod("setEntityTarget", h.abstractEntity).invoke(meta, h.adapt.invoke(null, nativeEntity));
        var location = h.nativeType("io.lumine.mythic.api.adapters.AbstractLocation");
        h.metadataType.getMethod("setOrigin", location).invoke(meta, h.nativeType("io.lumine.mythic.bukkit.BukkitAdapter").getMethod("adapt", Location.class).invoke(null, Objects.requireNonNull(Bukkit.getEntity(p.runtimeId)).getLocation()));
        return meta;
    }
    private void observe() throws Exception {
        long visuals = world.getEntities().stream().filter(e -> e instanceof Display || e instanceof Interaction || e instanceof ArmorStand).count();
        if (phase.equals("write")) visualCount = visuals;
        getLogger().info("WORLD_VAR_VISUALS|phase="+phase+"|expected="+visualCount+"|actual="+visuals);
        if (visuals != visualCount || visuals == 0) throw new IllegalStateException("Visuals lost or duplicated");
        for (Pair p : pairs) {
            var h = p.h;
            LivingEntity nEntity = (LivingEntity) Objects.requireNonNull(Bukkit.getEntity(p.nativeId), "Native UUID missing");
            LivingEntity rEntity = (LivingEntity) Objects.requireNonNull(Bukkit.getEntity(p.runtimeId), "Runtime UUID missing");
            Object ctx = context(p, rEntity), meta = meta(p, nEntity);
            Object service = runtime.getClass().getMethod("skillService").invoke(runtime);
            int instanceId = ((Number) instance(p.runtimeId).getClass().getMethod("instanceId").invoke(instance(p.runtimeId))).intValue();
            if (phase.equals("write")) {
                Object tree = h.newTree(rEntity, rEntity, origin());
                h.treeType.getMethod("setVariable", String.class, Object.class).invoke(tree, "WorldTree", 23);
                Object snapshot = h.treeType.getMethod("snapshot").invoke(tree);
                service.getClass().getMethod("restoreSkillTreeSnapshot", int.class, snapshot.getClass()).invoke(service, instanceId, snapshot);
            }
            Optional<?> saved = (Optional<?>) service.getClass().getMethod("snapshotOf", int.class).invoke(service, instanceId);
            boolean treeOk = saved.isPresent() && (phase.equals("final")
                    ? ((Map<?,?>) saved.get().getClass().getMethod("variables").invoke(saved.get())).isEmpty()
                    : Integer.valueOf(23).equals(((Map<?,?>) saved.get().getClass().getMethod("variables").invoke(saved.get())).get("WorldTree")));
            getLogger().info("WORLD_VAR_TREE|phase="+phase+"|path="+path(h)+"|passed="+treeOk);
            if (!treeOk) throw new IllegalStateException("Skill tree lost after "+phase);
            if (!phase.equals("write")) {
                var snapshot = runtime.getDataFolder().toPath().resolve("skill-trees/entity-"+p.runtimeId+".yml");
                var evidence = getDataFolder().toPath().resolve("snapshots/"+phase+"-"+path(h)+".yml");
                java.nio.file.Files.createDirectories(evidence.getParent());
                java.nio.file.Files.copy(snapshot,evidence);
            }
            getLogger().info("WORLD_VAR_ATTACHED|phase=" + phase + "|path=" + path(h) + "|native=" + nEntity.getUniqueId() + "|runtime=" + rEntity.getUniqueId() + "|world=" + world.getUID());
            for (String scope : SCOPES) for (String kind : CASES) {
                String name = name(h, scope, kind), variable = scope + "." + name;
                if (phase.equals("write")) {
                    String type = kind.equals("Float") ? "FLOAT" : kind.equals("String") ? "STRING" : "INTEGER";
                    String value = kind.equals("Float") ? "3.25" : kind.equals("String") ? "world-value" : "7";
                    String args = switch (kind) { case "Transient" -> ";save=false"; case "Negative" -> ";save=false;duration=-1";
                        case "Timed" -> ";save=true;duration=12000"; case "Expired" -> ";save=true;duration=40"; default -> ";save=true"; };
                    h.execute(kind.equals("Location") ? "setvariablelocation{var=" + variable + ";value=@origin;save=true}"
                            : "setvariable{var=" + variable + ";type=" + type + ";value=" + value + args + "}",
                            kind.equals("Location") ? "VariableSetLocationMechanic" : "VariableSetMechanic", meta, ctx, nEntity);
                }
                Object manager = h.mythic.getClass().getMethod("getVariableManager").invoke(h.mythic);
                Class<?> scopeType = h.nativeType("io.lumine.mythic.core.skills.variables.VariableScope");
                Object registry = manager.getClass().getMethod("getRegistry", scopeType, h.placeholderMeta, h.abstractEntity)
                        .invoke(manager, scopeType.getField(scope.toUpperCase(Locale.ROOT)).get(null), meta, h.adapt.invoke(null, nEntity));
                Object nv = registry.getClass().getMethod("get", String.class).invoke(registry, name);
                Object n = nv == null ? null : nv.getClass().getMethod("get").invoke(nv);
                Class<?> extras = h.runtimeType("dev.helstera.ai.skill.SkillExtras");
                var raw = extras.getDeclaredMethod("placeholderVariable", h.contextType, String.class, String.class); raw.setAccessible(true);
                Object r = raw.invoke(null, ctx, scope, name);
                var ref = Arrays.stream(extras.getDeclaredClasses()).filter(t -> t.getSimpleName().equals("VariableRef")).findFirst().orElseThrow();
                var ctor = ref.getDeclaredConstructor(String.class, String.class); ctor.setAccessible(true);
                var expiration = extras.getDeclaredMethod("variableExpiration", h.contextType, ref); expiration.setAccessible(true);
                long nd = nv == null ? 0 : ((Number) nv.getClass().getMethod("getExpirationTime").invoke(nv)).longValue();
                long rd = ((Number) expiration.invoke(null, ctx, ctor.newInstance(name, scope))).longValue();
                String nt = render(n), rt = render(r);
                boolean expected = !phase.equals("final") && (phase.equals("write") || !kind.equals("Expired"));
                if (phase.equals("write")) { values.put(name, nt); deadlines.put(name+"n", nd); deadlines.put(name+"r", rd); }
                boolean match = expected == (n != null) && expected == (r != null) && nt.equals(rt);
                if (expected) match &= n != null && r != null && nt.equals(values.get(name)) && (kind.equals("Location") || n.getClass().equals(r.getClass()))
                        && (phase.equals("write") ? nd > 1 ? Math.abs(nd-rd)<1000 : nd==rd : nd==deadlines.get(name+"n") && rd==deadlines.get(name+"r"));
                getLogger().info("WORLD_VAR|phase=" + phase + "|path=" + path(h) + "|scope=" + scope + "|case=" + kind + "|expected=" + expected
                        + "|native=" + nt + "|runtime=" + rt + "|nativeType=" + (n==null?"absent":n.getClass().getSimpleName())
                        + "|runtimeType=" + (r==null?"absent":r.getClass().getSimpleName()) + "|nativeDeadline=" + nd + "|runtimeDeadline=" + rd + "|matched=" + match);
                if (!match) throw new IllegalStateException("Variable mismatch: " + variable); rows++;
            }
        }
        getLogger().info("WORLD_VAR_PHASE|phase=" + phase + "|observations=64|passed=true");
    }
    private void clear() throws Exception {
        for (Pair p : pairs) {
            LivingEntity n = (LivingEntity) Bukkit.getEntity(p.nativeId), r = (LivingEntity) Bukkit.getEntity(p.runtimeId);
            Object ctx = context(p,r), meta = meta(p,n);
            for (String scope : SCOPES) for (String kind : CASES) p.h.unset(scope+"."+name(p.h,scope,kind),meta,ctx,n);
            p.h.execute("setvariable{var=caster.DeathCleanup;value=9;save=true}","VariableSetMechanic",meta,ctx,n);
            p.h.execute("setvariablelocation{var=caster.DeathCleanupPoint;value=@origin;save=true}","VariableSetLocationMechanic",meta,ctx,n);
            Object service = runtime.getClass().getMethod("skillService").invoke(runtime);
            int instanceId = ((Number) instance(p.runtimeId).getClass().getMethod("instanceId").invoke(instance(p.runtimeId))).intValue();
            service.getClass().getMethod("clearSkillTree", int.class).invoke(service, instanceId);
        }
    }
    private void deathCleanup() throws Exception {
        for (Pair p : pairs) {
            Object n = p.h.manager.getClass().getMethod("getActiveMob", UUID.class).invoke(p.h.manager,p.nativeId);
            Object r = p.h.runtimeType("dev.helstera.ai.skill.SkillExtras").getMethod("scopedVariableSnapshot", String.class).invoke(null,"entity:"+p.runtimeId);
            boolean nativeGone = ((Optional<?>) n).isEmpty();
            boolean runtimeGone = ((Map<?,?>) r.getClass().getMethod("variables").invoke(r)).isEmpty() && ((Map<?,?>) r.getClass().getMethod("locations").invoke(r)).isEmpty();
            boolean snapshotGone = !java.nio.file.Files.exists(runtime.getDataFolder().toPath().resolve("skill-trees/entity-"+p.runtimeId+".yml"));
            getLogger().info("WORLD_VAR_DEAD|path="+path(p.h)+"|nativeGone="+nativeGone+"|runtimeGone="+runtimeGone+"|instanceGone="+(instance(p.runtimeId)==null)+"|snapshotGone="+snapshotGone);
            if (!nativeGone || !runtimeGone || instance(p.runtimeId)!=null || !snapshotGone) throw new IllegalStateException("Death cleanup failed");
        }
        getLogger().info("WORLD_VAR_SUMMARY|observations="+rows+"|passed=true");
    }
    private String render(Object value) throws Exception {
        if (value==null) return "absent";
        if (value instanceof Location p) return p.getWorld().getName()+","+p.getX()+","+p.getY()+","+p.getZ();
        if (value.getClass().getSimpleName().contains("Location")) {
            Object w=value.getClass().getMethod("getWorld").invoke(value);
            return w.getClass().getMethod("getName").invoke(w)+","+value.getClass().getMethod("getX").invoke(value)+","+value.getClass().getMethod("getY").invoke(value)+","+value.getClass().getMethod("getZ").invoke(value);
        }
        return value.toString();
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void worldUnload(WorldUnloadEvent e) {
        if (e.getWorld().getName().equals(WORLD)) getLogger().info("WORLD_VAR_EVENT|event=world-unload|world="+e.getWorld().getUID());
    }
    @EventHandler(priority=EventPriority.MONITOR) public void entitiesUnload(EntitiesUnloadEvent e) {
        for (Entity entity:e.getEntities()) for (Pair p:pairs) if (entity.getUniqueId().equals(p.nativeId)||entity.getUniqueId().equals(p.runtimeId))
            getLogger().info("WORLD_VAR_EVENT|event=entity-unload|uuid="+entity.getUniqueId());
    }
    @EventHandler(priority=EventPriority.MONITOR) public void entitiesLoad(EntitiesLoadEvent e) {
        for (Entity entity:e.getEntities()) for (Pair p:pairs) if (entity.getUniqueId().equals(p.nativeId)||entity.getUniqueId().equals(p.runtimeId))
            getLogger().info("WORLD_VAR_EVENT|event=entity-load|uuid="+entity.getUniqueId());
    }
    @EventHandler(priority=EventPriority.MONITOR,ignoreCancelled=true) public void death(EntityDeathEvent e) {
        for (Pair p:pairs) if (e.getEntity().getUniqueId().equals(p.nativeId)||e.getEntity().getUniqueId().equals(p.runtimeId))
            getLogger().info("WORLD_VAR_EVENT|event=death|uuid="+e.getEntity().getUniqueId());
    }
}
