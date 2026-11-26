package dev.helstera.test.randomspawn;

import org.bukkit.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.lang.reflect.Field;
import java.util.*;

/** Real entities, a real player, and production controller attach/rebind/detach on pinned Paper. */
public final class SelectorRepairsProbe extends JavaPlugin {
    private Plugin runtime;
    private Object instances, ai;
    private Class<?> profileType, instanceType;
    private YamlConfiguration profiles;
    private final List<Entity> entities = new ArrayList<>();
    private final Set<Chunk> tickets = new HashSet<>();
    private Player player;
    private World world;
    private int attempts;

    @Override public void onEnable() { Bukkit.getScheduler().runTaskLater(this, this::awaitPlayer, 100); }
    private void awaitPlayer() {
        player = Bukkit.getPlayerExact("SelectorPlayer");
        if (player == null) {
            if (++attempts < 120) Bukkit.getScheduler().runTaskLater(this, this::awaitPlayer, 10);
            else getLogger().severe("SELECTOR_FAILED|error=player timeout");
            return;
        }
        try { runProbe(); }
        catch (Throwable error) { getLogger().severe("SELECTOR_FAILED|error=" + error); error.printStackTrace(); }
        finally { for (Chunk chunk : tickets) chunk.removePluginChunkTicket(this); }
    }
    private static Object call(Object value, String method) throws Exception { return value.getClass().getMethod(method).invoke(value); }
    private Class<?> type(String name) throws Exception { return Class.forName(name, true, runtime.getClass().getClassLoader()); }
    private Object profile(String name) throws Exception {
        return profileType.getMethod("fromSection", String.class, ConfigurationSection.class)
                .invoke(null, name, profiles.getConfigurationSection("profiles." + name));
    }
    private Object bind(Entity entity) throws Exception {
        Class<?> optionsType = type("dev.helstera.api.instance.SpawnOptions");
        Object options = optionsType.getMethod("defaults").invoke(null);
        optionsType.getMethod("noAi", boolean.class).invoke(options, true);
        optionsType.getMethod("gravity", boolean.class).invoke(options, false);
        return instances.getClass().getMethod("bind", String.class, Entity.class, optionsType)
                .invoke(instances, "example/emberling", entity, options);
    }
    private Object attach(Object instance, String profile) throws Exception {
        ai.getClass().getMethod("attach", instanceType, profileType).invoke(ai, instance, profile(profile));
        return controller(instance);
    }
    private Object controller(Object instance) throws Exception {
        return ai.getClass().getMethod("controllerById", int.class).invoke(ai, id(instance));
    }
    private static int id(Object instance) throws Exception { return ((Number) call(instance, "instanceId")).intValue(); }
    private void detach(Object instance) throws Exception { ai.getClass().getMethod("detach", int.class).invoke(ai, id(instance)); }
    private void rebind(Object controller, String name) throws Exception {
        controller.getClass().getMethod("rebindProfile", profileType).invoke(controller, profile(name));
    }
    private <T extends LivingEntity> T spawn(Class<T> kind, Location point) {
        Chunk chunk = point.getChunk(); chunk.getEntities();
        if (tickets.add(chunk)) chunk.addPluginChunkTicket(this);
        T entity = world.spawn(point, kind, value -> {
            value.setAI(false); value.setGravity(false); value.setInvulnerable(true); value.setPersistent(true); value.setSilent(true);
        });
        entities.add(entity); return entity;
    }
    private void door(String key, Zombie zombie, Object instance) throws Exception {
        getLogger().info("SELECTOR_DOOR|key=" + key + "|mob=" + zombie.getUniqueId() + "|supported=" + zombie.supportsBreakingDoors()
                + "|enabled=" + zombie.canBreakDoors() + "|controller=" + (controller(instance) != null)
                + "|alive=" + (zombie.isValid() && !zombie.isDead()) + "|tick=" + Bukkit.getCurrentTick());
    }
    private void flee(String key, String profile, Cow mob, Object instance, Location origin) throws Exception {
        mob.teleport(origin); mob.setVelocity(new Vector());
        Object controller = attach(instance, profile); call(controller, "tick");
        LivingEntity target = (LivingEntity) call(controller, "targetEntity"); Vector velocity = mob.getVelocity();
        getLogger().info("SELECTOR_FLEE|key=" + key + "|mob=" + mob.getUniqueId()
                + "|target=" + (target == null ? "none" : target.getUniqueId()) + "|state=" + call(controller, "state")
                + "|vx=" + velocity.getX() + "|vz=" + velocity.getZ() + "|mode=" + player.getGameMode()
                + "|tick=" + Bukkit.getCurrentTick());
    }
    private void runProbe() throws Exception {
        runtime = Bukkit.getPluginManager().getPlugin("helsteraMobs"); instances = call(runtime, "instances"); ai = call(runtime, "ai");
        profileType = type("dev.helstera.ai.AiProfile"); instanceType = type("dev.helstera.runtime.instance.ModelInstanceImpl");
        profiles = YamlConfiguration.loadConfiguration(getDataFolder().toPath().resolve("profiles.yml").toFile());
        world = Bukkit.getWorlds().getFirst(); world.setGameRule(GameRule.DO_MOB_SPAWNING, false);
        world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false); world.setTime(18000);
        Location origin = new Location(world, 0, 150, 0);
        player.setGameMode(GameMode.SURVIVAL); player.setGravity(false); player.setInvulnerable(true);
        player.teleport(origin.clone().add(4, 0, 0));
        Zombie zombie = spawn(Zombie.class, origin.clone().add(40, 0, 0));
        Cow mob = spawn(Cow.class, origin); Wolf wolf = spawn(Wolf.class, origin.clone().add(-4, 0, 0));
        Cow look = spawn(Cow.class, origin.clone().add(0, 0, 8));
        Object zombieInstance = bind(zombie), mobInstance = bind(mob), lookInstance = bind(look);
        getLogger().info("SELECTOR_IDENTITIES|player=" + player.getUniqueId() + "|zombie=" + zombie.getUniqueId()
                + "|mob=" + mob.getUniqueId() + "|wolf=" + wolf.getUniqueId() + "|look=" + look.getUniqueId());
        getLogger().info("SELECTOR_POSITIONS|mobX=" + mob.getLocation().getX() + "|playerX=" + player.getLocation().getX()
                + "|wolfX=" + wolf.getLocation().getX() + "|mobZ=" + mob.getLocation().getZ()
                + "|playerZ=" + player.getLocation().getZ() + "|wolfZ=" + wolf.getLocation().getZ());
        zombie.setCanBreakDoors(false); door("baseline_false", zombie, zombieInstance);
        Object controller = attach(zombieInstance, "door_on"); door("apply_false", zombie, zombieInstance);
        rebind(controller, "clear"); door("reload_clear", zombie, zombieInstance);
        rebind(controller, "door_on"); door("reload_on", zombie, zombieInstance);
        controller = attach(zombieInstance, "door_on"); door("repeated_attach", zombie, zombieInstance);
        detach(zombieInstance); door("detach_false", zombie, zombieInstance);
        zombie.setCanBreakDoors(true); door("baseline_true", zombie, zombieInstance);
        controller = attach(zombieInstance, "door_on"); door("apply_true", zombie, zombieInstance);
        rebind(controller, "reset"); door("reload_reset", zombie, zombieInstance);
        detach(zombieInstance); door("detach_true", zombie, zombieInstance);
        zombie.setCanBreakDoors(false);
        for (String name : List.of("all_then_clear", "all_then_reset")) {
            attach(zombieInstance, name); door(name, zombie, zombieInstance);
            var pathfinder = zombie.getPathfinder();
            getLogger().info("SELECTOR_FLAGS|key=" + name + "|float=" + pathfinder.canFloat()
                    + "|open=" + pathfinder.canOpenDoors() + "|pass=" + pathfinder.canPassDoors());
        }
        flee("players", "flee_players", mob, mobInstance, origin);
        flee("wolves", "flee_wolves", mob, mobInstance, origin);
        flee("clear", "clear", mob, mobInstance, origin);
        flee("reset", "reset", mob, mobInstance, origin);
        flee("invalid_faction", "flee_bad", mob, mobInstance, origin);
        player.setGameMode(GameMode.CREATIVE); flee("creative", "flee_players", mob, mobInstance, origin);
        player.setGameMode(GameMode.SPECTATOR); flee("spectator", "flee_players", mob, mobInstance, origin);
        player.setGameMode(GameMode.SURVIVAL);
        Object lookController = attach(lookInstance, "clear");
        Field random = lookController.getClass().getDeclaredField("random"); random.setAccessible(true);
        ((Random) random.get(lookController)).setSeed(1234L);
        for (String name : List.of("random", "clear")) {
            Location before = look.getLocation(); before.setYaw(0); before.setPitch(0); look.teleport(before);
            rebind(lookController, name.equals("random") ? "random_look" : "cleared_look");
            Location after = look.getLocation(); Vector toward = player.getLocation().toVector().subtract(after.toVector()).setY(0).normalize();
            double alignment = after.getDirection().setY(0).normalize().dot(toward);
            getLogger().info("SELECTOR_LOOK|key=" + name + "|mob=" + look.getUniqueId() + "|yaw=" + after.getYaw()
                    + "|alignment=" + alignment + "|moved=" + (after.distanceSquared(before) > 0.000001));
        }
        rebind(lookController, "unavailable");
        String diagnostic = call(lookController, "selectorDiagnostic").toString();
        getLogger().info("SELECTOR_DIAGNOSTIC|climb=" + diagnostic.contains("native climbing has no public API")
                + "|closeDoor=" + diagnostic.contains("door-closing toggle"));
        rebind(lookController, "door_on");
        getLogger().info("SELECTOR_UNSUPPORTED_DOOR|reported=" + call(lookController, "selectorDiagnostic").toString()
                .contains("requires a Zombie supporting door breaking"));
        for (Object instance : List.of(zombieInstance, mobInstance, lookInstance)) {
            detach(instance); instances.getClass().getMethod("despawn", int.class).invoke(instances, id(instance));
        }
        for (Entity entity : entities) entity.remove();
        long visuals = world.getEntities().stream().filter(entity -> entity instanceof Display || entity instanceof Interaction).count();
        getLogger().info("SELECTOR_CLEANUP|bindings=" + call(instances, "activeCount") + "|controllers=" + call(ai, "activeCount") + "|visuals=" + visuals);
        getLogger().info("SELECTOR_SUMMARY|doors=12|flee=7|look=2|completed=true");
        player.sendMessage("SELECTOR_REPAIRS_DONE");
    }
}
