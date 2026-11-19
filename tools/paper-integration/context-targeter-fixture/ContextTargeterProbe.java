package dev.helstera.test.targeter;

import dev.helstera.ai.BehaviorRegistryImpl;
import dev.helstera.ai.skill.SkillService;
import dev.helstera.api.behavior.BehaviorContext;
import dev.helstera.api.instance.SpawnOptions;
import dev.helstera.migration.importer.MythicMobsImporter;
import dev.helstera.plugin.HelsteraPlugin;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Cow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/** Compares visible skill side effects, not just whether castSkill accepted a skill ID. */
public final class ContextTargeterProbe extends JavaPlugin {
    private static final float BEFORE = 0.16f;
    private static final float AFTER = 0.4f;

    @Override public void onEnable() {
        new BukkitRunnable() {
            private int attempts;
            @Override public void run() {
                Player player = getServer().getPlayerExact("ContextTester");
                if (player != null && player.isOnline()) {
                    cancel();
                    runProbe(player);
                } else if (++attempts >= 60) {
                    cancel();
                    getLogger().warning("CONTEXT_TARGETER_FAILED player missing");
                }
            }
        }.runTaskTimer(this, 20L, 20L);
    }

    private void runProbe(Player player) {
        List<LivingEntity> spawned = new ArrayList<>();
        var originalMode = player.getGameMode();
        float originalSpeed = player.getWalkSpeed();
        var helstera = (HelsteraPlugin) getServer().getPluginManager().getPlugin("helsteraMobs");
        Integer instanceId = null;
        boolean finished = false;
        try {
            var mythic = Objects.requireNonNull(getServer().getPluginManager().getPlugin("MythicMobs"));
            require(mythic.isEnabled() && helstera != null && helstera.isEnabled(), "plugins not enabled");
            Object api = mythic.getClass().getMethod("getAPIHelper").invoke(mythic);
            var cast = api.getClass().getMethod("castSkill", Entity.class, String.class, Entity.class,
                    Location.class, Collection.class, Collection.class, float.class);
            var root = mythic.getDataFolder().toPath().toAbsolutePath();
            var entries = new MythicMobsImporter().convert(root.resolve("Skills/context-targeter-skills.yml"), root);
            var plan = entries.stream().filter(e -> "skills.yml".equals(e.get("target-file"))).findFirst()
                    .orElseThrow();
            var yaml = YamlConfiguration.loadConfiguration(new StringReader(String.valueOf(plan.get("content"))));
            var skills = new SkillService(new BehaviorRegistryImpl(), getLogger());
            skills.loadSkills(yaml.getConfigurationSection("skills"));
            require(skills.warnings().isEmpty()
                    && entries.stream().noneMatch(e -> "WARN".equals(e.get("severity"))), "shared source rejected");
            var origin = player.getLocation().clone().add(0, 8, 0);
            var nativeCaster = (Mob) api.getClass().getMethod("spawnMythicMob", String.class, Location.class)
                    .invoke(api, "ContextTargeterFixtureCaster", origin);
            spawned.add(nativeCaster);
            var importedCaster = cow(origin, spawned);
            var aiTarget = cow(origin.clone().add(2, 0, 0), spawned);
            var inherited = cow(origin.clone().add(4, 0, 0), spawned);
            var instance = helstera.instances().bind("example/emberling", importedCaster,
                    SpawnOptions.defaults().persistent(false));
            instanceId = instance.instanceId();
            var triggerContext = BehaviorContext.continueExecution(instance, inherited, 1, 2, 0, "context-probe",
                    null, player, List.of(inherited), origin, null);
            for (GameMode mode : GameMode.values()) {
                player.setGameMode(mode);
                require(player.getGameMode() == mode, "player mode did not change");
                for (var skill : List.of("ct_trigger", "ct_modes_on", "ct_modes_off", "ct_players_off")) {
                    setSpeed(player, BEFORE);
                    require(Boolean.TRUE.equals(cast.invoke(api, nativeCaster, skill, player, origin,
                            List.of(inherited), List.of(), 1f)), "native cast rejected " + skill);
                    boolean nativeHit = readHit(player);
                    setSpeed(player, BEFORE);
                    require(skills.castSkill(skill, triggerContext), "imported cast rejected " + skill);
                    report(mode.name().toLowerCase(java.util.Locale.ROOT) + "-" + skill,
                            nativeHit, readHit(player));
                }
                setSpeed(player, BEFORE);
                require(Boolean.TRUE.equals(cast.invoke(api, nativeCaster, "ct_living_players_off", player, origin,
                        List.of(), List.of(), 1f)), "native living targeter cast rejected");
                boolean nativeLivingPlayerDisabled = readHit(player);
                var livingContext = BehaviorContext.continueExecution(instance, player, 1, 0, 0,
                        "context-probe", null, player, List.of(player), origin, null);
                setSpeed(player, BEFORE);
                require(skills.castSkill("ct_living_players_off", livingContext),
                        "imported living targeter player-context cast rejected");
                report(mode.name().toLowerCase(java.util.Locale.ROOT) + "-living-players-off",
                        nativeLivingPlayerDisabled, readHit(player));
            }

            importedCaster.setAI(false);
            importedCaster.setTarget(aiTarget);
            nativeCaster.setAI(false);
            nativeCaster.setTarget(aiTarget);
            for (String phase : List.of("different-ai-target", "missing-ai-target", "inherited-only")) {
                if (phase.equals("missing-ai-target")) {
                    nativeCaster.setTarget(null);
                    importedCaster.setTarget(null);
                }
                boolean hasAiTarget = phase.equals("different-ai-target");
                require(nativeCaster.getTarget() == (hasAiTarget ? aiTarget : null)
                        && importedCaster.getTarget() == (hasAiTarget ? aiTarget : null), "AI target setup failed");
                setSpeed(aiTarget, BEFORE);
                setSpeed(inherited, BEFORE);
                require(Boolean.TRUE.equals(cast.invoke(api, nativeCaster, "ct_target", player, origin,
                        List.of(inherited), List.of(), 1f)), "native target cast rejected");
                boolean nativeAi = readHit(aiTarget);
                boolean nativeInherited = readHit(inherited);
                setSpeed(aiTarget, BEFORE);
                setSpeed(inherited, BEFORE);
                var context = BehaviorContext.continueExecution(instance,
                        phase.equals("inherited-only") ? null : inherited, 1, 2, 0, "context-probe",
                        null, player, List.of(inherited), origin, null);
                require(skills.castSkill("ct_target", context), "imported target cast rejected");
                report(phase + "-ai", nativeAi, readHit(aiTarget));
                report(phase + "-inherited", nativeInherited, readHit(inherited));
            }

            nativeCaster.setTarget(null);
            importedCaster.setTarget(null);
            setSpeed(player, BEFORE);
            require(Boolean.TRUE.equals(cast.invoke(api, nativeCaster, "ct_target", player, origin,
                    List.of(player), List.of(), 1f)), "native event-bound target cast rejected");
            boolean nativeEventBound = readHit(player);
            setSpeed(player, BEFORE);
            var eventBoundContext = BehaviorContext.continueExecution(instance, player, 1, 2, 0,
                    "context-probe", null, player, List.of(player), origin, null);
            require(skills.castSkill("ct_target", eventBoundContext), "imported event-bound target cast rejected");
            report("event-bound-target", nativeEventBound, readHit(player));

            finished = true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            getLogger().log(java.util.logging.Level.WARNING, "CONTEXT_TARGETER_FAILED", e);
        } finally {
            player.setGameMode(originalMode);
            player.setWalkSpeed(originalSpeed);
            if (instanceId != null) helstera.instances().despawn(instanceId);
            spawned.forEach(Entity::remove);
            if (finished && player.getGameMode() == originalMode && player.getWalkSpeed() == originalSpeed
                    && spawned.stream().noneMatch(Entity::isValid)) {
                getLogger().info("CONTEXT_TARGETER_COMPLETE cleanup=true");
            }
        }
    }

    private Cow cow(Location at, List<LivingEntity> spawned) {
        var cow = at.getWorld().spawn(at, Cow.class);
        spawned.add(cow);
        cow.setAI(false);
        cow.setGravity(false);
        return cow;
    }

    private void report(String id, boolean nativeHit, boolean importedHit) {
        getLogger().info("CONTEXT_TARGETER_CASE " + id + " native=" + nativeHit + " imported=" + importedHit);
    }

    private static boolean readHit(LivingEntity target) {
        double value = target instanceof Player p ? p.getWalkSpeed()
                : target.getAttribute(Attribute.GENERIC_MOVEMENT_SPEED).getBaseValue();
        require(Math.abs(value - BEFORE) < 1e-6 || Math.abs(value - AFTER) < 1e-6, "unexpected speed " + value);
        return Math.abs(value - AFTER) < 1e-6;
    }

    private static void setSpeed(LivingEntity target, float speed) {
        if (target instanceof Player p) p.setWalkSpeed(speed);
        else target.getAttribute(Attribute.GENERIC_MOVEMENT_SPEED).setBaseValue(speed);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
