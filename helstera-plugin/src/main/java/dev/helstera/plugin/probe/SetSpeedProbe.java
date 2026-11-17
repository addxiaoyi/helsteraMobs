package dev.helstera.plugin.probe;

import dev.helstera.ai.BehaviorRegistryImpl;
import dev.helstera.ai.skill.SkillService;
import dev.helstera.api.behavior.BehaviorContext;
import dev.helstera.api.behavior.SkillTreeContext;
import dev.helstera.api.instance.InstanceManager;
import dev.helstera.api.instance.ModelInstance;
import dev.helstera.api.instance.SpawnOptions;
import dev.helstera.migration.importer.MythicMobsImporter;
import org.bukkit.Location;
import org.bukkit.GameMode;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Cow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Parrot;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;

/** Native/imported WALK/FLY comparisons plus a real player's ability packets. */
public final class SetSpeedProbe {
    private SetSpeedProbe() { }

    public static void schedule(JavaPlugin plugin, InstanceManager instances) {
        new BukkitRunnable() {
            private int attempts;
            @Override public void run() {
                var player = plugin.getServer().getOnlinePlayers().stream()
                        .filter(p -> p.getName().equals("SpeedTester")).findFirst();
                if (player.isPresent()) {
                    cancel();
                    runProbe(plugin, instances, player.get());
                } else if (++attempts >= 60) {
                    cancel();
                    plugin.getLogger().warning("[自检] set-speed player-ready=false passed=false");
                }
            }
        }.runTaskTimer(plugin, 100L, 20L);
    }

    private record Case(String name, boolean flying, float multiplier) {
        float speed() { return multiplier * (flying ? 0.1f : 0.2f); }
    }

    private static List<Case> cases() {
        return List.of(new Case("speed_default", false, 1), new Case("speed_double", false, 2),
                new Case("speed_fly_default", true, 1), new Case("speed_fly_double", true, 2),
                new Case("speed_walking", false, 1), new Case("speed_flying", true, 1),
                new Case("speed_type_fallback", false, 1), new Case("speed_type_contains", true, 1),
                new Case("speed_zero", false, 0), new Case("speed_negative", false, -1),
                new Case("speed_amount", false, 2), new Case("speed_a", false, 2),
                new Case("speed_value", false, 2), new Case("speed_v", false, 2),
                new Case("speed_multiplier", false, 2), new Case("speed_m", false, 2));
    }

    private static void runProbe(JavaPlugin plugin, InstanceManager instances, Player player) {
        var checks = new LinkedHashMap<String, Boolean>();
        var observations = new ArrayList<String>();
        var entities = new ArrayList<LivingEntity>();
        ModelInstance instance = null;
        float originalWalk = player.getWalkSpeed();
        float originalFly = player.getFlySpeed();
        GameMode originalMode = player.getGameMode();
        String error = "";
        try {
            var mythic = java.util.Objects.requireNonNull(
                    plugin.getServer().getPluginManager().getPlugin("MythicMobs"), "MythicMobs required");
            require(checks, "native-enabled", mythic.isEnabled());
            Object api = mythic.getClass().getMethod("getAPIHelper").invoke(mythic);
            var nativeCast = api.getClass().getMethod("castSkill", Entity.class, String.class, Entity.class,
                    Location.class, Collection.class, Collection.class, float.class);
            var mythicRoot = mythic.getDataFolder().toPath().toAbsolutePath();
            var entries = new MythicMobsImporter().convert(mythicRoot.resolve("Skills/set-speed-skills.yml"), mythicRoot);
            var plan = entries.stream().filter(e -> "skills.yml".equals(e.get("target-file"))).findFirst().orElseThrow();
            var yaml = YamlConfiguration.loadConfiguration(new StringReader(String.valueOf(plan.get("content"))));
            var skills = new SkillService(new BehaviorRegistryImpl(), plugin.getLogger());
            skills.loadSkills(yaml.getConfigurationSection("skills"));
            require(checks, "shared-source-import", skills.warnings().isEmpty()
                    && entries.stream().noneMatch(e -> "WARN".equals(e.get("severity"))));
            var world = player.getWorld();
            var origin = world.getSpawnLocation().clone().add(75, 6, 75);
            origin.getChunk().load();
            var caster = world.spawn(origin, Cow.class);
            entities.add(caster);
            caster.setAI(false);
            caster.setGravity(false);
            for (int i = 0; i < 3; i++) {
                var location = origin.clone().add(i * 3 + 3, 0, 0);
                location.getChunk().load();
                var parrot = world.spawn(location, Parrot.class);
                entities.add(parrot);
                parrot.setAI(false);
                parrot.setGravity(false);
                for (var attribute : List.of(Attribute.GENERIC_MOVEMENT_SPEED, Attribute.GENERIC_FLYING_SPEED)) {
                    parrot.getAttribute(attribute).addModifier(new AttributeModifier(
                            new NamespacedKey(plugin, "set_speed_probe"), 0.05, AttributeModifier.Operation.ADD_NUMBER));
                }
                resetMob(parrot);
            }
            var target = entities.get(1);
            var oracle = entities.get(2);
            var control = entities.get(3);
            instance = instances.bind("example/emberling", caster, SpawnOptions.defaults().persistent(false));
            double casterSpeed = caster.getAttribute(Attribute.GENERIC_MOVEMENT_SPEED).getBaseValue();
            var context = BehaviorContext.continueExecution(instance, target, 1, 3, 0, "set-speed-probe",
                    null, target, List.of(target), origin, new SkillTreeContext(target, List.of(target), origin));
            for (var test : cases()) {
                resetMob(target);
                resetMob(oracle);
                boolean nativeRan = Boolean.TRUE.equals(nativeCast.invoke(api, caster, test.name(), oracle,
                        origin, List.of(oracle), List.of(), 1.0f));
                boolean importedRan = skills.castSkill(test.name(), context);
                var attribute = test.flying() ? Attribute.GENERIC_FLYING_SPEED : Attribute.GENERIC_MOVEMENT_SPEED;
                var other = test.flying() ? Attribute.GENERIC_MOVEMENT_SPEED : Attribute.GENERIC_FLYING_SPEED;
                double expectedOther = test.flying() ? 0.7 : 0.8;
                double nativeBase = oracle.getAttribute(attribute).getBaseValue();
                double importedBase = target.getAttribute(attribute).getBaseValue();
                observations.add(test.name() + "[native=" + nativeBase + ",imported=" + importedBase + "]");
                require(checks, "mob-" + test.name(), nativeRan && importedRan
                        && close(nativeBase, test.speed()) && close(importedBase, nativeBase)
                        && close(target.getAttribute(other).getBaseValue(), expectedOther)
                        && close(oracle.getAttribute(other).getBaseValue(), expectedOther)
                        && target.getAttribute(attribute).getModifiers().size() == 1
                        && oracle.getAttribute(attribute).getModifiers().size() == 1);
            }
            require(checks, "absolute-repeat", skills.castSkill("speed_double", context)
                    && skills.castSkill("speed_double", context)
                    && close(target.getAttribute(Attribute.GENERIC_MOVEMENT_SPEED).getBaseValue(), 0.4f));
            require(checks, "target-isolation", close(control.getAttribute(Attribute.GENERIC_MOVEMENT_SPEED).getBaseValue(), 0.7)
                    && close(control.getAttribute(Attribute.GENERIC_FLYING_SPEED).getBaseValue(), 0.8)
                    && close(caster.getAttribute(Attribute.GENERIC_MOVEMENT_SPEED).getBaseValue(), casterSpeed));
            var playerContext = BehaviorContext.continueExecution(instance, player, 1, 3, 0, "set-speed-probe",
                    null, player, List.of(player), origin, new SkillTreeContext(player, List.of(player), origin));
            // MM's common entity selector filters creative/spectator players by
            // default. Audit its speed action on an eligible survival recipient.
            player.setGameMode(GameMode.SURVIVAL);
            require(checks, "survival-recipient", player.getGameMode() == GameMode.SURVIVAL);
            for (var test : cases()) {
                resetPlayer(player);
                player.sendMessage("SPEED_PHASE native:" + test.name());
                boolean nativeRan = Boolean.TRUE.equals(nativeCast.invoke(api, caster, test.name(), player,
                        origin, List.of(player), List.of(), 1.0f));
                float nativeWalk = player.getWalkSpeed();
                float nativeFly = player.getFlySpeed();
                resetPlayer(player);
                player.sendMessage("SPEED_PHASE imported:" + test.name());
                boolean importedRan = skills.castSkill(test.name(), playerContext);
                observations.add("player-" + test.name() + "[native=" + nativeWalk + "/" + nativeFly
                        + ",imported=" + player.getWalkSpeed() + "/" + player.getFlySpeed() + "]");
                require(checks, "player-" + test.name(), nativeRan && importedRan
                        && close(player.getWalkSpeed(), test.flying() ? 0.16f : test.speed())
                        && close(player.getFlySpeed(), test.flying() ? test.speed() : 0.06f)
                        && close(player.getWalkSpeed(), nativeWalk) && close(player.getFlySpeed(), nativeFly));
            }
            resetPlayer(player);
            require(checks, "player-out-of-range", run(skills, "setspeed{m=6} @Target", playerContext)
                    && close(player.getWalkSpeed(), 0.16f));
            require(checks, "missing-attribute", caster.getAttribute(Attribute.GENERIC_FLYING_SPEED) == null
                    && run(skills, "setspeed{t=FLY} @Self", context)
                    && close(caster.getAttribute(Attribute.GENERIC_MOVEMENT_SPEED).getBaseValue(), casterSpeed));
            require(checks, "location-noop", run(skills, "setspeed{m=3}", context.withLocationTarget(origin))
                    && close(caster.getAttribute(Attribute.GENERIC_MOVEMENT_SPEED).getBaseValue(), casterSpeed));
        } catch (Exception failure) {
            Throwable cause = failure instanceof java.lang.reflect.InvocationTargetException invocation
                    ? invocation.getTargetException() : failure;
            error = " error=" + cause.getClass().getSimpleName() + ":" + cause.getMessage();
            checks.put("execution", false);
        } finally {
            try {
                player.sendMessage("SPEED_RESET");
                player.setGameMode(originalMode);
                player.setWalkSpeed(originalWalk);
                player.setFlySpeed(originalFly);
                if (instance != null) instances.despawn(instance.instanceId());
                for (var entity : entities) entity.remove();
                checks.put("cleanup", entities.stream().noneMatch(Entity::isValid)
                        && (instance == null || instances.getInstance(instance.instanceId()).isEmpty())
                        && close(player.getWalkSpeed(), originalWalk) && close(player.getFlySpeed(), originalFly)
                        && player.getGameMode() == originalMode);
                player.sendMessage("SPEED_DONE");
            } catch (Exception failure) { checks.put("cleanup", false); }
        }
        StringBuilder result = new StringBuilder("[自检] set-speed");
        checks.forEach((name, value) -> result.append(' ').append(name).append('=').append(value));
        plugin.getLogger().info(result + " observations=" + String.join(",", observations) + error
                + " passed=" + checks.values().stream().allMatch(Boolean.TRUE::equals));
    }

    private static void resetMob(LivingEntity entity) {
        entity.getAttribute(Attribute.GENERIC_MOVEMENT_SPEED).setBaseValue(0.7);
        entity.getAttribute(Attribute.GENERIC_FLYING_SPEED).setBaseValue(0.8);
    }

    private static void resetPlayer(Player player) {
        player.sendMessage("SPEED_RESET");
        player.setWalkSpeed(0.16f);
        player.setFlySpeed(0.06f);
    }

    private static boolean run(SkillService skills, String source, BehaviorContext context) {
        String action = skills.bindAction(source);
        return action != null && skills.runAction(action, context);
    }

    private static boolean close(double a, double b) { return Math.abs(a - b) < 1e-7; }

    private static void require(LinkedHashMap<String, Boolean> checks, String name, boolean passed) {
        checks.put(name, passed);
        if (!passed) throw new IllegalStateException(name);
    }
}
