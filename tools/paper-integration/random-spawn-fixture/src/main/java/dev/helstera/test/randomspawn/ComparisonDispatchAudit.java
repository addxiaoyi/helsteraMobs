package dev.helstera.test.randomspawn;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Cow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/** Compares public native framework dispatch with actual imported/direct skill execution. */
final class ComparisonDispatchAudit {
    private record Scene(String name, List<LivingEntity> targets, Entity trigger, Location point, float pitch) {}

    static void run(JavaPlugin probe, Plugin helstera, Plugin mythic, Location center) throws Exception {
        List<Entity> owned = new ArrayList<>();
        try {
            Location base = center.clone().add(80, 20, 0);
            base.setYaw(0); base.setPitch(0);
            Cow caster = cow(base, owned);
            Cow front = cow(base.clone().add(0, 0, 4), owned);
            Cow precision = cow(base.clone().add(0, 0, 4.00000001), owned);
            Cow back = cow(base.clone().add(0, 0, -4), owned);
            Cow above = cow(base.clone().add(0, 4, 0), owned);
            Cow below = cow(base.clone().add(0, -4, 0), owned);
            Cow side = cow(base.clone().add(4, 0, 0), owned);
            Cow overlap = cow(base.clone().add(0, 0, .2), owned);
            Cow pitched = cow(base.clone().add(0, 0, 2.8), owned);
            World other = Bukkit.getWorlds().stream().filter(w -> w != base.getWorld()).findFirst().orElseThrow();
            Location otherPoint = base.clone().add(0, 0, 4); otherPoint.setWorld(other);
            Cow cross = cow(otherPoint, owned);
            Location eye = caster.getEyeLocation();
            Entity itemEye = item(eye.clone().add(0, 0, 4), owned);
            Entity itemLow = item(base.clone().add(0, 0, 1), owned);
            Entity removedItem = item(base.clone(), owned); removedItem.remove();
            Cow removed = cow(base.clone(), owned); removed.remove();
            List<Scene> scenes = List.of(
                    new Scene("self", List.of(caster), caster, null, 0),
                    new Scene("front", List.of(front), front, null, 0),
                    new Scene("float-boundary", List.of(precision), precision, null, 0),
                    new Scene("back", List.of(back), back, null, 0),
                    new Scene("above", List.of(above), above, null, 0),
                    new Scene("below", List.of(below), below, null, 0),
                    new Scene("side", List.of(side), side, null, 0),
                    new Scene("overlap", List.of(overlap), overlap, null, 0),
                    new Scene("pitched", List.of(pitched), pitched, null, 45),
                    new Scene("cross-world", List.of(cross), cross, null, 0),
                    new Scene("item-eye", List.of(back), itemEye, null, 0),
                    new Scene("item-below", List.of(back), itemLow, null, 0),
                    new Scene("removed-item", List.of(back), removedItem, null, 0),
                    new Scene("removed-living", List.of(removed), removed, null, 0),
                    new Scene("missing", List.of(), null, null, 0),
                    new Scene("mixed-removed", List.of(front, removed), front, null, 0),
                    new Scene("mixed", List.of(caster, front, back), front, null, 0),
                    new Scene("location-front", List.of(), front, eye.clone().add(0, 0, 4), 0),
                    new Scene("location-back", List.of(), back, eye.clone().add(0, 0, -4), 0),
                    new Scene("location-cross-world", List.of(), cross, otherPoint, 0),
                    new Scene("location-at-eye", List.of(), caster, eye, 0));
            String[][] cases = {
                    {"distance{d=0}", "DistanceCondition"}, {"distance{d=4}", "DistanceCondition"},
                    {"distance{d=<4}", "DistanceCondition"}, {"distance{d=>4}", "DistanceCondition"},
                    {"distance{d=5to3}", "DistanceCondition"}, {"distance{d=-4}", "DistanceCondition"},
                    {"ydiff{d=4}", "YDiffCondition"}, {"ydiff{d=-4}", "YDiffCondition"},
                    {"ydiff{d=>0}", "YDiffCondition"},
                    {"fov{a=90}", "FieldOfViewCondition"}, {"fov{a=90;r=90}", "FieldOfViewCondition"},
                    {"fieldofview{a=20}", "FieldOfViewCondition"},
                    {"boundingboxesoverlap", "BoundingBoxesOverlapCondition"},
                    {"bbsoverlap{so=4}", "BoundingBoxesOverlapCondition"},
                    {"boundingboxesoverlap{fo=-4}", "BoundingBoxesOverlapCondition"},
                    {"iscaster", "IsCasterCondition"}};
            ClassLoader nativeLoader = mythic.getClass().getClassLoader();
            ClassLoader runtimeLoader = helstera.getClass().getClassLoader();
            Class<?> abstractEntity = Class.forName("io.lumine.mythic.api.adapters.AbstractEntity", true, nativeLoader);
            Class<?> abstractLocation = Class.forName("io.lumine.mythic.api.adapters.AbstractLocation", true, nativeLoader);
            Class<?> skillCaster = Class.forName("io.lumine.mythic.api.skills.SkillCaster", true, nativeLoader);
            Class<?> metadata = Class.forName("io.lumine.mythic.api.skills.SkillMetadata", true, nativeLoader);
            Class<?> metadataImpl = Class.forName("io.lumine.mythic.core.skills.SkillMetadataImpl", true, nativeLoader);
            Class<?> triggerType = Class.forName("io.lumine.mythic.api.skills.SkillTrigger", true, nativeLoader);
            Class<?> adapter = Class.forName("io.lumine.mythic.bukkit.BukkitAdapter", true, nativeLoader);
            Method adaptEntity = adapter.getMethod("adapt", Entity.class);
            Method adaptLocation = adapter.getMethod("adapt", Location.class);
            Object adaptedCaster = adaptEntity.invoke(null, caster);
            Object nativeCaster = Proxy.newProxyInstance(nativeLoader, new Class<?>[]{skillCaster},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getEntity" -> adaptedCaster;
                        case "getLocation" -> adaptLocation.invoke(null, caster.getLocation());
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
            Class<?> configType = Class.forName("io.lumine.mythic.api.config.MythicLineConfig", true, nativeLoader);
            Class<?> configImpl = Class.forName("io.lumine.mythic.core.config.MythicLineConfigImpl", true, nativeLoader);
            Class<?> modelType = Class.forName("dev.helstera.api.instance.ModelInstance", true, runtimeLoader);
            Class<?> contextType = Class.forName("dev.helstera.api.behavior.BehaviorContext", true, runtimeLoader);
            Class<?> treeType = Class.forName("dev.helstera.api.behavior.SkillTreeContext", true, runtimeLoader);
            Object model = Proxy.newProxyInstance(runtimeLoader, new Class<?>[]{modelType},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "isValid" -> true;
                        case "baseEntity" -> Optional.of(caster);
                        case "executionAudience" -> Optional.empty();
                        case "location" -> caster.getLocation();
                        case "instanceId" -> -1;
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
            Class<?> registryType = Class.forName("dev.helstera.ai.BehaviorRegistryImpl", true, runtimeLoader);
            Class<?> registryApi = Class.forName("dev.helstera.api.behavior.BehaviorRegistry", true, runtimeLoader);
            Class<?> skillsType = Class.forName("dev.helstera.ai.skill.SkillService", true, runtimeLoader);
            Object registry = registryType.getConstructor().newInstance();
            Object skills = skillsType.getConstructor(registryApi, java.util.logging.Logger.class).newInstance(registry, probe.getLogger());
            List<Object> observed = new ArrayList<>();
            registryType.getMethod("registerAction", String.class, Consumer.class)
                    .invoke(registry, "comparison-observe", (Consumer<Object>) observed::add);
            Object importer = Class.forName("dev.helstera.migration.importer.MythicMobsImporter", true, runtimeLoader).getConstructor().newInstance();
            Path source = probe.getDataFolder().toPath().resolve("comparison-source/Skills/comparison.yml");
            Files.createDirectories(source.getParent());
            Method continuation = contextType.getMethod("continueExecution", modelType, LivingEntity.class,
                    double.class, double.class, long.class, String.class, String.class, Entity.class,
                    List.class, Location.class, treeType);
            Location origin = base.clone().add(100, 100, 100);
            int rows = 0;
            for (String[] test : cases) for (boolean outcome : List.of(true, false)) {
                String expression = test[0] + " " + outcome;
                Class<?> conditionClass = Class.forName("io.lumine.mythic.core.skills.conditions.all." + test[1], true, nativeLoader);
                Object config = configImpl.getConstructor(String.class).newInstance(expression);
                Object condition = conditionClass.getConstructor(String.class, configType).newInstance(expression, config);
                for (String mode : List.of("Conditions", "TargetConditions", "TriggerConditions", "inline-caster", "inline-trigger")) {
                    boolean inline = mode.startsWith("inline");
                    String nativeMethod = switch (mode) {
                        case "TargetConditions" -> "evaluateTargets";
                        case "TriggerConditions", "inline-trigger" -> "evaluateTrigger";
                        default -> "evaluateCaster";
                    };
                    String runtimeField = switch (mode) {
                        case "TargetConditions" -> "target-conditions";
                        case "TriggerConditions" -> "trigger-conditions";
                        default -> "require";
                    };
                    for (boolean importedPath : List.of(false, true)) {
                        var yaml = new YamlConfiguration();
                        String prefix = importedPath ? "dispatch." : "skills.dispatch.";
                        if (inline) yaml.set(prefix + (importedPath ? "Skills" : "on-decision"),
                                List.of("skill{s=observe} " + (mode.equals("inline-trigger") ? "?~" : "?")
                                        + (outcome ? "" : "!") + test[0]));
                        else {
                            yaml.set(prefix + (importedPath ? mode : runtimeField),
                                    List.of(importedPath ? expression : (outcome ? "" : "!") + test[0]));
                            yaml.set(prefix + (importedPath ? "Skills" : "on-decision"), List.of("skill{s=observe}"));
                        }
                        if (importedPath) {
                            yaml.save(source.toFile());
                            @SuppressWarnings("unchecked") List<Map<String, Object>> entries = (List<Map<String, Object>>) importer.getClass()
                                    .getMethod("convert", Path.class).invoke(importer, source);
                            var plan = entries.stream().filter(e -> "skills.yml".equals(e.get("target-file"))).findFirst().orElseThrow();
                            yaml.loadFromString(plan.get("content").toString());
                            if (!inline && yaml.getStringList("skills.dispatch." + runtimeField).size() != 1)
                                throw new IllegalStateException("Comparison condition was not imported: " + expression);
                        }
                        yaml.set("skills.observe.on-decision", List.of("comparison-observe"));
                        skillsType.getMethod("loadSkills", ConfigurationSection.class).invoke(skills, yaml.getConfigurationSection("skills"));
                        for (Scene scene : scenes) {
                            caster.setRotation(0, scene.pitch());
                            Object nativeMeta = metadataImpl.getConstructor(triggerType, skillCaster, abstractEntity)
                                    .newInstance(triggerType.getMethod("get", String.class).invoke(null, "API"), nativeCaster,
                                            scene.trigger() == null ? null : adaptEntity.invoke(null, scene.trigger()));
                            metadataImpl.getMethod("setOrigin", abstractLocation).invoke(nativeMeta, adaptLocation.invoke(null, origin));
                            List<Object> nativeTargets = new ArrayList<>();
                            for (Entity entity : scene.targets()) nativeTargets.add(adaptEntity.invoke(null, entity));
                            if (scene.point() == null) metadataImpl.getMethod("setEntityTargets", Collection.class).invoke(nativeMeta, nativeTargets);
                            else metadataImpl.getMethod("setLocationTargets", Collection.class).invoke(nativeMeta, List.of(adaptLocation.invoke(null, scene.point())));
                            boolean expected = (boolean) conditionClass.getMethod(nativeMethod, metadata).invoke(condition, nativeMeta);
                            Set<UUID> expectedTargets = new HashSet<>();
                            if (mode.equals("TargetConditions")) {
                                Collection<?> retained = (Collection<?>) metadataImpl.getMethod("getEntityTargets").invoke(nativeMeta);
                                if (retained != null) for (Object entity : retained)
                                    expectedTargets.add((UUID) abstractEntity.getMethod("getUniqueId").invoke(entity));
                            } else scene.targets().forEach(entity -> expectedTargets.add(entity.getUniqueId()));
                            Object ctx = continuation.invoke(null, model, scene.targets().isEmpty() ? null : scene.targets().getFirst(),
                                    1.0, 777.0, 0L, "comparison", "payload", scene.trigger(), scene.targets(), origin, null);
                            if (scene.point() != null) ctx = contextType.getMethod("withLocationTarget", Location.class).invoke(ctx, scene.point());
                            boolean matched = true;
                            if (!inline) {
                                Object preview = skillsType.getMethod("preview", String.class, contextType).invoke(skills, "dispatch", ctx);
                                matched = (boolean) preview.getClass().getMethod("wouldRun").invoke(preview) == expected;
                            }
                            observed.clear();
                            boolean invoked = (boolean) skillsType.getMethod("castSkill", String.class, contextType).invoke(skills, "dispatch", ctx);
                            matched &= invoked == (inline || expected) && observed.size() == (expected ? 1 : 0);
                            if (!observed.isEmpty()) {
                                Object action = observed.getFirst();
                                @SuppressWarnings("unchecked") List<LivingEntity> retained = (List<LivingEntity>) contextType.getMethod("targets").invoke(action);
                                matched &= retained.stream().map(Entity::getUniqueId).collect(Collectors.toSet()).equals(expectedTargets)
                                        && ((Optional<?>) contextType.getMethod("trigger").invoke(action)).orElse(null) == scene.trigger()
                                        && ((Optional<?>) contextType.getMethod("origin").invoke(action)).orElseThrow().equals(origin)
                                        && ((Optional<?>) contextType.getMethod("payload").invoke(action)).orElseThrow().equals("payload")
                                        && contextType.getMethod("conditionScope").invoke(action).toString().equals("EXECUTION");
                                if (scene.point() != null) matched &= ((Optional<?>) contextType.getMethod("targetLocation").invoke(action)).orElseThrow().equals(scene.point());
                            }
                            probe.getLogger().info("COMPARISON_DISPATCH|condition=" + test[0] + "|mode=" + mode
                                    + "|outcome=" + outcome + "|scene=" + scene.name() + "|path=" + (importedPath ? "imported" : "direct")
                                    + "|native=" + expected + "|runtime=" + !observed.isEmpty() + "|matched=" + matched);
                            if (!matched) throw new IllegalStateException("Comparison dispatch differs: " + expression + " " + mode + " " + scene.name());
                            rows++;
                        }
                    }
                }
            }
            if (rows != 6720) throw new IllegalStateException("Incomplete comparison dispatch evidence: " + rows);
            probe.getLogger().info("COMPARISON_DISPATCH_SUMMARY|comparisons=" + rows + "|conditions=16|scenes=21|modes=5|passed=true");
        } finally {
            for (Entity entity : owned) entity.remove();
        }
    }

    private static Cow cow(Location location, List<Entity> owned) {
        Cow cow = location.getWorld().spawn(location, Cow.class);
        owned.add(cow); cow.setAI(false); cow.setGravity(false); cow.setInvulnerable(true);
        return cow;
    }

    private static Entity item(Location location, List<Entity> owned) {
        Entity item = location.getWorld().dropItem(location, new org.bukkit.inventory.ItemStack(org.bukkit.Material.STONE));
        owned.add(item); item.setGravity(false);
        return item;
    }
}
