package dev.helstera.test.randomspawn;

import org.bukkit.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.EntityDismountEvent;
import org.bukkit.event.entity.EntityMountEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.*;
import java.util.*;
import java.util.stream.Collectors;

/** Observable contract for Helstera model actions invoked by the real native dispatcher. */
public final class ModelActionsProbe extends JavaPlugin implements Listener {
    private Plugin runtime, mythic;
    private Object adapter, helper, instances;
    private LivingEntity caster, target, other, vehicle, dead;
    private Entity nonDamageable;
    private final List<Entity> entities = new ArrayList<>();
    private final Map<String, Object> cold = new HashMap<>(), live = new HashMap<>();
    private World world;
    private boolean failed, denyMount, denyUnmount;
    private int rows, directs;

    @FunctionalInterface private interface Step { void run() throws Exception; }
    private void later(long ticks, Step step) {
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!failed) try { step.run(); } catch (Throwable error) { fail(error); }
        }, ticks);
    }
    private void fail(Throwable error) {
        failed = true; getLogger().severe("ACTIONS_FAILED|error=" + error); error.printStackTrace();
    }
    private Class<?> nt(String name) throws Exception {
        return Class.forName("io.lumine.mythic." + name, true, mythic.getClass().getClassLoader());
    }
    private static Object call(Object object, String method) throws Exception {
        return object.getClass().getMethod(method).invoke(object);
    }

    @Override public void onEnable() {
        runtime = Bukkit.getPluginManager().getPlugin("helsteraMobs");
        mythic = Bukkit.getPluginManager().getPlugin("MythicMobs");
        Bukkit.getPluginManager().registerEvents(this, this);
        try {
            Class<? extends Event> load = nt("bukkit.events.MythicMechanicLoadEvent").asSubclass(Event.class);
            Bukkit.getPluginManager().registerEvent(load, this, EventPriority.MONITOR, (listener, event) -> {
                try {
                    String name = call(event, "getMechanicName").toString();
                    String line = call(call(event, "getConfig"), "getLine").toString();
                    Object extension = ((Optional<?>) call(event, "getMechanic")).orElse(null);
                    getLogger().info("ACTIONS_LOAD|name=" + name + "|line=" + line + "|registered=" + (extension != null));
                    if (line.equals("modelmount{}") || line.equals("modelunmount{}")
                            || line.matches("modelheal\\{amount=(2\\.5|4|3)\\}")) {
                        String key = name.substring("model".length()).toLowerCase(java.util.Locale.ROOT);
                        if (extension != null) { cold.putIfAbsent(key, extension); live.put(key, extension); }
                    }
                } catch (Exception error) { throw new EventException(error); }
            }, this);
            later(100, this::begin);
        } catch (Exception error) { fail(error); }
    }

    @Override public void onDisable() {
        if (world != null) world.removePluginChunkTickets(this);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void mount(EntityMountEvent event) {
        if (denyMount && event.getEntity() == caster && event.getMount() == target) {
            event.setCancelled(true);
            getLogger().info("ACTIONS_CANCEL|kind=mount|caster=" + caster.getUniqueId() + "|target=" + target.getUniqueId());
        }
    }
    @EventHandler(priority = EventPriority.HIGHEST)
    public void unmount(EntityDismountEvent event) {
        if (denyUnmount && event.getEntity() == caster && event.getDismounted() == target) {
            event.setCancelled(true);
            getLogger().info("ACTIONS_CANCEL|kind=unmount|caster=" + caster.getUniqueId() + "|target=" + target.getUniqueId());
        }
    }

    private Object instance(Entity entity) throws Exception {
        for (Object value : (Collection<?>) call(instances, "allImpl"))
            if (((Optional<?>) call(value, "boundEntityId")).filter(entity.getUniqueId()::equals).isPresent()) return value;
        return null;
    }
    private long bindings(Entity entity) throws Exception {
        long result = 0;
        for (Object value : (Collection<?>) call(instances, "allImpl"))
            if (((Optional<?>) call(value, "boundEntityId")).filter(entity.getUniqueId()::equals).isPresent()) result++;
        return result;
    }
    private static String id(Entity entity) { return entity == null ? "none" : entity.getUniqueId().toString(); }
    private static String passengers(Entity entity) {
        String result = entity.getPassengers().stream().map(ModelActionsProbe::id).sorted().collect(Collectors.joining(","));
        return result.isEmpty() ? "none" : result;
    }
    private void state(String key, Entity selected) throws Exception {
        getLogger().info("ACTIONS_ROW|key=" + key + "|target=" + id(selected) + "|caster=" + id(caster)
                + "|bindings=" + bindings(selected) + "|casterBindings=" + bindings(caster)
                + "|health=" + (selected instanceof Damageable damageable ? damageable.getHealth() : "n/a")
                + "|casterHealth=" + caster.getHealth() + "|passengers=" + passengers(selected)
                + "|casterPassengers=" + passengers(caster) + "|vehicle=" + id(caster.getVehicle())
                + "|targetVehicle=" + id(selected.getVehicle()) + "|primary=" + Bukkit.isPrimaryThread()
                + "|tick=" + Bukkit.getCurrentTick());
        rows++;
    }
    private boolean cast(String name, Entity selected) throws Exception {
        return (Boolean) helper.getClass().getMethod("castSkill", Entity.class, String.class, Entity.class, Location.class,
                Collection.class, Collection.class, float.class).invoke(helper, caster, "ma_" + name,
                selected, caster.getLocation(), List.of(selected), List.of(), 1F);
    }
    private void prepareBoundary(String key, Entity selected) throws Exception {
        Location location = selected.getLocation();
        boolean valid = selected.isValid();
        boolean loaded = world.isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4);
        boolean accepted = cast("spawn", selected);
        long count = bindings(selected);
        getLogger().info("ACTIONS_BOUNDARY|key=" + key + "|target=" + id(selected) + "|type=" + selected.getType()
                + "|valid=" + valid + "|chunkLoaded=" + loaded + "|spawnAccepted=" + accepted
                + "|bindings=" + count + "|primary=" + Bukkit.isPrimaryThread());
        if (!valid || !loaded || !accepted || count != 1)
            throw new IllegalStateException("Invalid boundary stimulus: " + key);
    }
    private void action(String name) throws Exception { cast(name, target); state(name, target); }
    private Object wrapped(Entity entity) throws Exception {
        return nt("bukkit.BukkitAdapter").getMethod("adapt", Entity.class).invoke(null, entity);
    }
    private Object direct(Object mechanic, Object selected) throws Exception {
        return nt("api.skills.ITargetedEntitySkill").getMethod("castAtEntity", nt("api.skills.SkillMetadata"),
                nt("api.adapters.AbstractEntity")).invoke(mechanic, null, selected);
    }
    private void directRows(String phase, Map<String, Object> mechanics, Object selected) throws Exception {
        for (String name : List.of("mount", "unmount", "heal")) {
            getLogger().info("ACTIONS_DIRECT|key=" + phase + "_" + name + "|result=" + direct(mechanics.get(name), selected)
                    + "|primary=" + Bukkit.isPrimaryThread() + "|tick=" + Bukkit.getCurrentTick());
            directs++;
        }
    }
    private LivingEntity husk() {
        var entity = world.spawn(new Location(world, 8 + entities.size() * 3, 120, 0), Husk.class,
                value -> { value.setAI(false); value.setGravity(false); value.setPersistent(true); value.setSilent(true); });
        entities.add(entity); entity.setHealth(10); return entity;
    }
    private static void mount(Entity vehicle, Entity passenger) {
        if (!vehicle.addPassenger(passenger)) throw new IllegalStateException("Could not prepare passenger relation");
    }

    private void begin() throws Exception {
        instances = call(runtime, "instances");
        adapter = ((Optional<?>) call(runtime, "integrationRegistry").getClass().getMethod("get", String.class)
                .invoke(call(runtime, "integrationRegistry"), "MythicMobs")).orElseThrow();
        helper = call(mythic, "getAPIHelper");
        world = Bukkit.getWorlds().getFirst();
        world.setGameRule(GameRule.DO_MOB_SPAWNING, false); world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
        // The late boundary targets are in chunk 1. Keep both fixture chunks entity-ticking
        // across async dispatch and reloads, even with no player or spawn chunk tickets.
        for (int x = 0; x <= 1; x++) {
            Chunk chunk = world.getChunkAt(x, 0);
            chunk.getEntities();
            if (!chunk.addPluginChunkTicket(this))
                throw new IllegalStateException("Could not retain fixture chunk " + x);
        }
        caster = (LivingEntity) helper.getClass().getMethod("spawnMythicMob", String.class, Location.class)
                .invoke(helper, "MaCaster", new Location(world, 0, 120, 0));
        caster.setGravity(false); caster.setHealth(10); entities.add(caster);
        target = husk(); other = husk(); vehicle = husk(); dead = husk();
        nonDamageable = world.spawn(new Location(world, 30, 120, 0), Interaction.class);
        nonDamageable.setGravity(false); entities.add(nonDamageable);
        getLogger().info("ACTIONS_IDENTITIES|caster=" + id(caster) + "|target=" + id(target)
                + "|other=" + id(other) + "|vehicle=" + id(vehicle) + "|dead=" + id(dead) + "|nonDamageable=" + id(nonDamageable));
        if (cold.size() != 3) throw new IllegalStateException("Missing cold native handlers");
        state("initial", target);
        cast("mount", target); state("unbound_mount", target);
        cast("heal", target); state("unbound_heal", target);
        action("spawn"); mount(target, other); state("seed_other", target);
        action("mount"); cast("mount", target); state("repeat_mount", target);
        action("unmount"); cast("unmount", target); state("repeat_unmount", target);
        mount(vehicle, caster);
        cast("unmount", target); state("unmount_elsewhere", target);
        cast("mount", target); state("mount_elsewhere", target);
        caster.leaveVehicle(); other.leaveVehicle();
        cast("self_spawn", caster); cast("self_mount", caster); state("self_mount", caster); cast("self_remove", caster);
        mount(caster, vehicle); mount(vehicle, target);
        cast("mount", target); state("cycle_mount", target);
        target.leaveVehicle(); vehicle.leaveVehicle();
        for (String name : List.of("bad_mount", "bad_unmount", "bad_negative", "bad_nan", "bad_infinity", "bad_alias", "bad_unknown")) action(name);
        denyMount = true; cast("mount", target); state("cancel_mount", target); denyMount = false;
        cast("mount", target); state("mount_after_cancel", target);
        denyUnmount = true; cast("unmount", target); state("cancel_unmount", target); denyUnmount = false;
        cast("unmount", target); state("unmount_after_cancel", target);
        action("heal_default"); action("heal_zero"); action("heal"); action("heal_cap");
        target.setHealth(10); state("reset_before_async", target);
        Object selected = wrapped(target);
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try { directRows("async", cold, selected); cast("heal", target); later(5, this::lifecycle); }
            catch (Throwable error) { fail(error); }
        });
    }

    private void lifecycle() throws Exception {
        state("async_heal", target);
        prepareBoundary("non_damageable", nonDamageable);
        boolean accepted = cast("heal", nonDamageable);
        getLogger().info("ACTIONS_NON_DAMAGEABLE|target=" + id(nonDamageable) + "|nativeAccepted=" + accepted
                + "|result=" + direct(cold.get("heal"), wrapped(nonDamageable)));
        state("non_damageable", nonDamageable);
        prepareBoundary("dead", dead);
        long before = bindings(dead); dead.setHealth(0); cast("heal", dead);
        getLogger().info("ACTIONS_DEAD|target=" + id(dead) + "|bindingsBefore=" + before + "|health=" + dead.getHealth()
                + "|dead=" + dead.isDead() + "|result=" + direct(cold.get("heal"), wrapped(dead)));
        Object selected = wrapped(target);
        call(adapter, "disable"); directRows("disabled", cold, selected);
        cast("heal", target); state("disabled_after_cast", target);
        adapter.getClass().getMethod("enable", Plugin.class).invoke(adapter, runtime);
        adapter.getClass().getMethod("enable", Plugin.class).invoke(adapter, runtime);
        directRows("reenabled", cold, selected); cast("heal", target); state("reenabled_after_cast", target);
        var file = mythic.getDataFolder().toPath().resolve("Skills/model-actions.yml").toFile();
        var source = YamlConfiguration.loadConfiguration(file);
        source.set("ma_heal.Skills", List.of("modelheal{amount=4} @trigger")); source.save(file);
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "mythicmobs reload");
        later(60, () -> {
            cast("heal", target); state("reload_heal", target); directRows("reloaded_old", cold, selected);
            Map<String, Object> previous = new HashMap<>(live);
            source.set("ma_heal.Skills", List.of("modelheal{amount=3} @trigger")); source.save(file);
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "mythicmobs reload");
            later(60, () -> {
                directRows("live_reload_old", previous, selected);
                cast("heal", target); state("live_reload_heal", target);
                cast("mount", target); state("reload_mount", target);
                cast("unmount", target); state("reload_unmount", target);
                action("remove"); cleanup();
            });
        });
    }

    private void cleanup() throws Exception {
        for (Entity entity : entities) {
            Object instance = instance(entity);
            if (instance != null) instances.getClass().getMethod("despawn", int.class)
                    .invoke(instances, ((Number) call(instance, "instanceId")).intValue());
            entity.remove();
        }
        later(5, () -> {
            long visuals = world.getEntities().stream().filter(entity -> entity instanceof Display || entity instanceof Interaction).count();
            Field bridgeField = adapter.getClass().getDeclaredField("mechanics"); bridgeField.setAccessible(true);
            Object bridge = bridgeField.get(adapter);
            Field pending = bridge.getClass().getDeclaredField("pending"); pending.setAccessible(true);
            Field autoPending = adapter.getClass().getDeclaredField("spawnTasks"); autoPending.setAccessible(true);
            world.removePluginChunkTickets(this);
            getLogger().info("ACTIONS_CLEANUP|bindings=" + call(instances, "activeCount") + "|visuals=" + visuals
                    + "|ownedTasks=" + (((Set<?>) pending.get(bridge)).size() + ((Set<?>) autoPending.get(adapter)).size())
                    + "|chunkTickets=" + world.getPluginChunkTickets().getOrDefault(this, List.of()).size());
            getLogger().info("ACTIONS_SUMMARY|rows=" + rows + "|directs=" + directs + "|completed=true");
        });
    }
}
