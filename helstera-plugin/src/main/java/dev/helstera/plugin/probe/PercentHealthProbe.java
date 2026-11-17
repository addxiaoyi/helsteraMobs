package dev.helstera.plugin.probe;

import dev.helstera.ai.BehaviorRegistryImpl;
import dev.helstera.ai.skill.SkillService;
import dev.helstera.ai.skill.Placeholders;
import dev.helstera.api.behavior.BehaviorContext;
import dev.helstera.api.behavior.SkillTreeContext;
import dev.helstera.api.instance.InstanceManager;
import dev.helstera.api.instance.ModelInstance;
import dev.helstera.api.instance.SpawnOptions;
import dev.helstera.migration.importer.MythicMobsImporter;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Cow;
import org.bukkit.entity.Entity;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;

/** Identical imported/native skill inputs, real MM 5.7.2 oracle, and isolated entity controls. */
public final class PercentHealthProbe {
    private PercentHealthProbe() { }

    public static void run(JavaPlugin plugin, InstanceManager instances) {
        var checks = new LinkedHashMap<String, Boolean>();
        var observations = new ArrayList<String>();
        var entities = new ArrayList<Cow>();
        Listener listener = new Listener() { };
        ModelInstance instance = null;
        Path root = null;
        Path invalid = null;
        String error = "";
        try {
            var mythic = java.util.Objects.requireNonNull(
                    plugin.getServer().getPluginManager().getPlugin("MythicMobs"), "MythicMobs required");
            require(checks, "native-enabled", mythic.isEnabled());
            Object api = mythic.getClass().getMethod("getAPIHelper").invoke(mythic);
            var nativeCast = api.getClass().getMethod("castSkill", Entity.class, String.class, Entity.class,
                    Location.class, Collection.class, Collection.class, float.class);
            Path mythicRoot = mythic.getDataFolder().toPath().toAbsolutePath();
            Path nativeSource = mythicRoot.resolve("Skills/percent-health-skills.yml");
            var entries = new MythicMobsImporter().convert(nativeSource, mythicRoot);
            var plan = entries.stream().filter(e -> "skills.yml".equals(e.get("target-file"))).findFirst().orElseThrow();
            var yaml = YamlConfiguration.loadConfiguration(new StringReader(String.valueOf(plan.get("content"))));
            var skills = new SkillService(new BehaviorRegistryImpl(), plugin.getLogger());
            skills.loadSkills(yaml.getConfigurationSection("skills"));
            require(checks, "shared-source-import", skills.warnings().isEmpty()
                    && entries.stream().noneMatch(e -> "WARN".equals(e.get("severity"))));
            var world = plugin.getServer().getWorlds().getFirst();
            var origin = world.getSpawnLocation().clone().add(94, 6, 94);
            for (int i = 0; i < 4; i++) {
                var location = origin.clone().add(i * 3, 0, 0);
                location.getChunk().load();
                var entity = world.spawn(location, Cow.class);
                entities.add(entity);
                entity.setAI(false);
                entity.setGravity(false);
                entity.getAttribute(Attribute.GENERIC_MAX_HEALTH).setBaseValue(20);
                entity.getAttribute(Attribute.GENERIC_MAX_ABSORPTION).setBaseValue(20);
                entity.setHealth(10);
            }
            var caster = entities.get(0);
            var target = entities.get(1);
            var oracle = entities.get(2);
            var control = entities.get(3);
            instance = instances.bind("example/emberling", caster, SpawnOptions.defaults().persistent(false));
            caster.setHealth(10);
            var tree = new SkillTreeContext(target, List.of(target), origin);
            var context = BehaviorContext.continueExecution(instance, target, 0.5, 3, 0, "fraction-probe",
                    null, target, List.of(target), origin, tree);
            var expected = new LinkedHashMap<String, Double>();
            expected.put("fraction_heal_default", 12.0);
            expected.put("fraction_heal_quarter", 15.0);
            expected.put("fraction_heal_alias", 15.0);
            expected.put("fraction_heal_hp", 15.0);
            expected.put("fraction_heal_clamp", 20.0);
            expected.put("fraction_heal_negative", 6.0);
            expected.put("fraction_overheal_below", 20.0);
            expected.put("fraction_overheal_at_cap", 20.0);
            expected.put("fraction_overheal_no_cap", 20.0);
            expected.put("fraction_damage_default", 8.0);
            expected.put("fraction_damage_quarter", 5.0);
            expected.put("fraction_damage_alias", 5.0);
            expected.put("fraction_damage_current", 7.5);
            expected.put("fraction_damage_current_alias", 7.5);
            for (var test : expected.entrySet()) {
                reset(target);
                reset(oracle);
                boolean nativeRan = Boolean.TRUE.equals(nativeCast.invoke(api, caster, test.getKey(), oracle,
                        origin, List.of(oracle), List.of(), 1.0f));
                boolean importedRan = skills.castSkill(test.getKey(), context);
                double absorption = test.getKey().equals("fraction_overheal_below") ? 2 : 0;
                observations.add(test.getKey() + "[native=" + oracle.getHealth() + "/" + oracle.getAbsorptionAmount()
                        + ",imported=" + target.getHealth() + "/" + target.getAbsorptionAmount() + "]");
                require(checks, test.getKey(), nativeRan && importedRan
                        && close(oracle.getHealth(), test.getValue()) && close(target.getHealth(), oracle.getHealth())
                        && close(oracle.getAbsorptionAmount(), absorption)
                        && close(target.getAbsorptionAmount(), oracle.getAbsorptionAmount())
                        && close(target.getMaxHealth(), 20) && close(oracle.getMaxHealth(), 20));
            }
            require(checks, "target-isolation", close(caster.getHealth(), 10) && close(control.getHealth(), 10));
            var precision = new LinkedHashMap<String, Double>();
            precision.put("precision_caster_hp", 3.25);
            precision.put("precision_caster_thp", 3.25);
            precision.put("precision_caster_short", 3.25);
            precision.put("precision_caster_percent", 3.75);
            precision.put("precision_caster_percent_short", 3.75);
            precision.put("precision_target_hp", 6.25);
            precision.put("precision_target_thp", 6.25);
            precision.put("precision_target_percent", 18.75);
            precision.put("precision_caster_max", 20.0);
            precision.put("precision_heal_fraction", 5.625);
            precision.put("precision_damage_fraction", 0.625);
            precision.put("precision_damage_current", 2.734375);
            caster.setHealth(0.125);
            reset(oracle);
            oracle.setHealth(3.125);
            boolean nativeDynamicRan = Boolean.TRUE.equals(nativeCast.invoke(api, caster, "precision_caster_hp", oracle,
                    origin, List.of(oracle), List.of(), 1.0f));
            require(checks, "native-free-numeric-fallback", nativeDynamicRan && close(caster.getHealth(), 0.125)
                    && close(oracle.getHealth(), 4.125)
                    && Boolean.FALSE.equals(mythic.getClass().getMethod("isVolatile").invoke(null)));
            for (var test : precision.entrySet()) {
                reset(target);
                target.setHealth(3.125);
                boolean importedRan = skills.castSkill(test.getKey(), context);
                observations.add(test.getKey() + "[imported=" + target.getHealth() + "]");
                require(checks, test.getKey(), importedRan && close(target.getHealth(), test.getValue()));
            }
            oracle.setHealth(3.125);
            target.setHealth(3.125);
            nativePlaceholderChecks(caster, oracle, context, checks, observations);
            require(checks, "precision-target-isolation", close(caster.getHealth(), 0.125)
                    && close(control.getHealth(), 10));
            caster.setHealth(10);
            boolean[] cancel = {false};
            int[] events = {0, 0};
            plugin.getServer().getPluginManager().registerEvent(EntityDamageEvent.class, listener,
                    EventPriority.HIGHEST, (ignored, event) -> {
                        var damage = (EntityDamageEvent) event;
                        int index = damage.getEntity().equals(target) ? 0 : damage.getEntity().equals(oracle) ? 1 : -1;
                        if (index < 0) return;
                        events[index]++;
                        if (cancel[0]) damage.setCancelled(true);
                        else damage.setDamage(1.5);
                    }, plugin, false);
            for (boolean cancelled : List.of(false, true)) {
                cancel[0] = cancelled;
                reset(target);
                reset(oracle);
                events[0] = 0;
                events[1] = 0;
                boolean nativeRan = Boolean.TRUE.equals(nativeCast.invoke(api, caster, "fraction_damage_quarter", oracle,
                        origin, List.of(oracle), List.of(), 1.0f));
                require(checks, cancelled ? "cancelled-damage" : "modified-damage", nativeRan
                        && skills.castSkill("fraction_damage_quarter", context)
                        && close(target.getHealth(), cancelled ? 10 : 8.5)
                        && close(target.getHealth(), oracle.getHealth()) && events[0] > 0 && events[1] > 0);
            }
            HandlerList.unregisterAll(listener);
            reset(target);
            tree.setVariable("fraction", 0.25);
            require(checks, "dynamic", run(skills, "hp{m=<skill.var.fraction>} @Target", context)
                    && close(target.getHealth(), 15));
            for (String value : List.of("NaN", "Infinity", "<missing>")) {
                tree.setVariable("fraction", value);
                require(checks, "invalid-dynamic-" + checks.size(), run(skills, "hp{m=<skill.var.fraction>} @Target", context)
                        && close(target.getHealth(), 15));
            }
            reset(target);
            require(checks, "legacy-percent-points", run(skills, "heal-percent{p=25} @Target", context)
                    && close(target.getHealth(), 15));
            require(checks, "self-healing", run(skills, "hp{m=0.25} @Self", context)
                    && close(caster.getHealth(), 15) && close(control.getHealth(), 10));
            root = Files.createTempDirectory("helstera-fraction-");
            invalid = Files.createDirectory(root.resolve("Skills")).resolve("invalid.yml");
            Files.writeString(invalid, """
                    invalid:
                      Skills:
                        - 'healpercent{m=0.1;multiplier=0.2} @Target'
                        - 'damagepercent{p=NaN} @Target'
                        - 'healpercent{oh=maybe} @Target'
                        - 'damagepercent{ignorearmor=true} @Target'
                        - 'healpercent{tags=NEWER_VERSION} @Target'
                    """);
            var invalidEntries = new MythicMobsImporter().convert(invalid, root);
            var invalidPlan = invalidEntries.stream().filter(e -> "skills.yml".equals(e.get("target-file"))).findFirst().orElseThrow();
            var preserved = YamlConfiguration.loadConfiguration(new StringReader(String.valueOf(invalidPlan.get("content"))));
            require(checks, "invalid-preserved", preserved.getStringList("skills.invalid.unsupported").size() == 5
                    && preserved.getStringList("skills.invalid.on-decision").isEmpty()
                    && invalidEntries.stream().filter(e -> "WARN".equals(e.get("severity"))).count() == 5);
        } catch (Exception failure) {
            Throwable cause = failure instanceof java.lang.reflect.InvocationTargetException invocation
                    ? invocation.getTargetException() : failure;
            error = " error=" + cause.getClass().getSimpleName() + ":" + cause.getMessage();
            checks.put("execution", false);
        } finally {
            HandlerList.unregisterAll(listener);
            try {
                if (instance != null) instances.despawn(instance.instanceId());
                for (var entity : entities) entity.remove();
                if (invalid != null) Files.deleteIfExists(invalid);
                if (root != null) {
                    Files.deleteIfExists(root.resolve("Skills"));
                    Files.deleteIfExists(root);
                }
                checks.put("cleanup", entities.stream().noneMatch(Cow::isValid)
                        && (instance == null || instances.getInstance(instance.instanceId()).isEmpty())
                        && (root == null || !Files.exists(root)));
            } catch (Exception failure) { checks.put("cleanup", false); }
        }
        StringBuilder result = new StringBuilder("[自检] percent-health");
        checks.forEach((name, value) -> result.append(' ').append(name).append('=').append(value));
        plugin.getLogger().info(result + " observations=" + String.join(",", observations) + error
                + " passed=" + checks.values().stream().allMatch(Boolean.TRUE::equals));
    }

    private static void reset(Cow entity) {
        entity.setAbsorptionAmount(0);
        entity.setHealth(10);
        entity.setNoDamageTicks(0);
    }

    private static void nativePlaceholderChecks(Cow caster, Cow target, BehaviorContext context,
                                                LinkedHashMap<String, Boolean> checks,
                                                List<String> observations) throws Exception {
        Class<?> abstractEntity = Class.forName("io.lumine.mythic.api.adapters.AbstractEntity");
        Class<?> adapter = Class.forName("io.lumine.mythic.bukkit.BukkitAdapter");
        var adapt = adapter.getMethod("adapt", Entity.class);
        Object casterAdapter = adapt.invoke(null, caster);
        Object targetAdapter = adapt.invoke(null, target);
        Class<?> strings = Class.forName("io.lumine.mythic.api.skills.placeholders.PlaceholderString");
        var of = strings.getMethod("of", String.class);
        Class<?> metaType = Class.forName("io.lumine.mythic.core.skills.placeholders.PlaceholderMeta");
        Class<?> genericMeta = Class.forName("io.lumine.mythic.core.skills.placeholders.GenericPlaceholderMeta");
        Object meta = genericMeta.getConstructor(abstractEntity).newInstance(casterAdapter);
        var get = strings.getMethod("get", metaType, abstractEntity);
        var aliases = new LinkedHashMap<String, Double>();
        for (String scope : List.of("caster", "target")) {
            double health = scope.equals("caster") ? 0.125 : 3.125;
            for (String field : List.of("health", "hp", "thp")) aliases.put(scope + "." + field, health);
            for (String field : List.of("maxhealth", "mhp")) aliases.put(scope + "." + field, 20.0);
            for (String field : List.of("healthpercent", "percenthealth", "php")) {
                aliases.put(scope + "." + field, 100 * (health / 20));
            }
        }
        aliases.put("c.h", 0.125);
        aliases.put("c.mh", 20.0);
        aliases.put("c.ph", 0.625);
        for (var entry : aliases.entrySet()) {
            String source = "<" + entry.getKey() + ">";
            String nativeText = (String) get.invoke(of.invoke(null, source), meta, targetAdapter);
            String importedText = Placeholders.resolve(source, context);
            observations.add(entry.getKey() + "[native=" + nativeText + ",imported=" + importedText + "]");
            require(checks, "placeholder-" + entry.getKey(), nativeText.equals(importedText)
                    && close(Double.parseDouble(nativeText), entry.getValue()));
        }
    }

    private static boolean run(SkillService service, String source, BehaviorContext context) {
        String key = service.bindAction(source);
        return key != null && service.runAction(key, context);
    }

    private static boolean close(double a, double b) { return Math.abs(a - b) < 1e-6; }

    private static void require(LinkedHashMap<String, Boolean> checks, String name, boolean passed) {
        checks.put(name, passed);
        if (!passed) throw new IllegalStateException(name);
    }
}
