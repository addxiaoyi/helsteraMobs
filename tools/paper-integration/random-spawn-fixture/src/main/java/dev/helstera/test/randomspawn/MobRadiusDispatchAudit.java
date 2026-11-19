package dev.helstera.test.randomspawn;

import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/** Exercises native framework dispatch and imported skills, not just condition check overloads. */
final class MobRadiusDispatchAudit {
    static void run(JavaPlugin probe, Plugin helstera, Plugin mythic, LivingEntity caster,
                    LivingEntity custom, Location center) throws Exception {
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
        Class<?> nativeCondition = Class.forName("io.lumine.mythic.core.skills.conditions.all.MobsInRadiusCondition", true, nativeLoader);
        Class<?> configType = Class.forName("io.lumine.mythic.api.config.MythicLineConfig", true, nativeLoader);
        Class<?> configImpl = Class.forName("io.lumine.mythic.core.config.MythicLineConfigImpl", true, nativeLoader);
        Method resolveTypes = nativeCondition.getDeclaredMethod("lambda$new$1", String[].class, configType);
        resolveTypes.setAccessible(true);
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
                .invoke(registry, "radius-dispatch-observe", (Consumer<Object>) observed::add);
        Object importer = Class.forName("dev.helstera.migration.importer.MythicMobsImporter", true, runtimeLoader).getConstructor().newInstance();
        Path source = probe.getDataFolder().toPath().resolve("dispatch-source/Skills/radius.yml");
        Files.createDirectories(source.getParent());
        Method continuation = contextType.getMethod("continueExecution", modelType, LivingEntity.class,
                double.class, double.class, long.class, String.class, String.class, Entity.class,
                List.class, Location.class, treeType);
        LivingEntity far = center.getWorld().spawn(center.clone().add(40, 0, 0), org.bukkit.entity.Cow.class);
        far.setAI(false);
        far.setGravity(false);
        far.setInvulnerable(true);
        LivingEntity removed = center.getWorld().spawn(center.clone().add(1, 0, 0), org.bukkit.entity.Cow.class);
        removed.remove();
        int rows = 0;
        try {
            for (String field : List.of("Conditions", "TargetConditions", "TriggerConditions")) {
                for (String type : List.of("ZOMBIE", "HelsteraRadiusAuditMob")) {
                    for (int amount : new int[]{0, 1, 2}) {
                        for (boolean outcome : new boolean[]{true, false}) {
                            String expression = "mobsInRadius{r=5;t=" + type + ";a=" + amount + "} " + outcome;
                            var sourceYaml = new YamlConfiguration();
                            sourceYaml.set("dispatch." + field, List.of(expression));
                            sourceYaml.set("dispatch.Skills", List.of("message{m=dispatch}"));
                            sourceYaml.save(source.toFile());
                            @SuppressWarnings("unchecked")
                            List<Map<String, Object>> entries = (List<Map<String, Object>>) importer.getClass()
                                    .getMethod("convert", Path.class).invoke(importer, source);
                            Map<String, Object> plan = entries.stream().filter(e -> "skills.yml".equals(e.get("target-file")))
                                    .findFirst().orElseThrow();
                            var imported = new YamlConfiguration();
                            imported.loadFromString(plan.get("content").toString());
                            String runtimeField = switch (field) {
                                case "Conditions" -> "require";
                                case "TargetConditions" -> "target-conditions";
                                default -> "trigger-conditions";
                            };
                            if (imported.getStringList("skills.dispatch." + runtimeField).size() != 1)
                                throw new IllegalStateException("Imported condition was dropped: " + field);
                            // Observe the exact production action context after gates; message delivery is unrelated.
                            imported.set("skills.dispatch.on-decision", List.of("radius-dispatch-observe"));
                            var direct = new YamlConfiguration();
                            direct.set("skills.dispatch." + runtimeField, List.of((outcome ? "" : "!")
                                    + expression.substring(0, expression.lastIndexOf(' '))));
                            direct.set("skills.dispatch.on-decision", List.of("radius-dispatch-observe"));
                            Object config = configImpl.getConstructor(String.class).newInstance(expression);
                            Object condition = nativeCondition.getConstructor(String.class, configType).newInstance(expression, config);
                            resolveTypes.invoke(condition, (Object) new String[]{type}, config);
                            for (String scene : List.of("near", "far", "mixed", "empty", "location-near", "location-far", "removed", "mixed-removed")) {
                                List<LivingEntity> targets = switch (scene) {
                                    case "near" -> List.of(custom);
                                    case "far" -> List.of(far);
                                    case "mixed" -> List.of(custom, far);
                                    case "removed" -> List.of(removed);
                                    case "mixed-removed" -> List.of(custom, removed);
                                    default -> List.of();
                                };
                                Entity trigger = scene.equals("empty") ? null : scene.endsWith("far") ? far : custom;
                                Location location = scene.startsWith("location-") ? (scene.endsWith("far") ? far.getLocation() : custom.getLocation()) : null;
                                Object nativeMeta = metadataImpl.getConstructor(triggerType, skillCaster, abstractEntity)
                                        .newInstance(triggerType.getMethod("get", String.class).invoke(null, "API"), nativeCaster,
                                                trigger == null ? null : adaptEntity.invoke(null, trigger));
                                List<Object> nativeTargets = new ArrayList<>();
                                for (Entity target : targets) nativeTargets.add(adaptEntity.invoke(null, target));
                                // Native setters clear the other target kind, even for an empty collection.
                                if (location == null) metadataImpl.getMethod("setEntityTargets", Collection.class).invoke(nativeMeta, nativeTargets);
                                else metadataImpl.getMethod("setLocationTargets", Collection.class).invoke(nativeMeta,
                                        List.of(adaptLocation.invoke(null, location)));
                                String nativeMethod = switch (field) {
                                    case "Conditions" -> "evaluateCaster";
                                    case "TargetConditions" -> "evaluateTargets";
                                    default -> "evaluateTrigger";
                                };
                                boolean nativePassed = (boolean) nativeCondition.getMethod(nativeMethod, metadata).invoke(condition, nativeMeta);
                                List<UUID> expectedTargets = new ArrayList<>();
                                if (field.equals("TargetConditions")) {
                                    Collection<?> retained = (Collection<?>) metadataImpl.getMethod("getEntityTargets").invoke(nativeMeta);
                                    if (retained != null) for (Object entity : retained)
                                        expectedTargets.add((UUID) abstractEntity.getMethod("getUniqueId").invoke(entity));
                                } else targets.forEach(entity -> expectedTargets.add(entity.getUniqueId()));
                                for (var definition : List.of(imported, direct)) {
                                    skillsType.getMethod("loadSkills", ConfigurationSection.class).invoke(skills, definition.getConfigurationSection("skills"));
                                    Object ctx = continuation.invoke(null, model, targets.isEmpty() ? null : targets.getFirst(),
                                            1.0, -1.0, 0L, "dispatch", null, trigger, targets, center, null);
                                    if (location != null) ctx = contextType.getMethod("withLocationTarget", Location.class).invoke(ctx, location);
                                    observed.clear();
                                    boolean result = (boolean) skillsType.getMethod("castSkill", String.class, contextType).invoke(skills, "dispatch", ctx);
                                    boolean matched = result == nativePassed && observed.size() == (nativePassed ? 1 : 0);
                                    if (result && !observed.isEmpty()) {
                                        Object action = observed.getFirst();
                                        @SuppressWarnings("unchecked")
                                        List<LivingEntity> retained = (List<LivingEntity>) contextType.getMethod("targets").invoke(action);
                                        matched &= retained.stream().map(Entity::getUniqueId).collect(java.util.stream.Collectors.toSet())
                                                .equals(new java.util.HashSet<>(expectedTargets));
                                        matched &= contextType.getMethod("conditionScope").invoke(action).toString().equals("EXECUTION");
                                        if (location != null) matched &= ((Optional<?>) contextType.getMethod("targetLocation").invoke(action)).orElse(null).equals(location);
                                    }
                                    probe.getLogger().info("MOB_RADIUS_DISPATCH|field=" + field + "|type=" + type + "|amount=" + amount
                                            + "|outcome=" + outcome + "|scene=" + scene + "|path=" + (definition == imported ? "imported" : "direct")
                                            + "|native=" + nativePassed + "|runtime=" + result + "|matched=" + matched);
                                    if (!matched) {
                                        for (Entity target : targets) {
                                            Object adapted = adaptEntity.invoke(null, target);
                                            probe.getLogger().info("MOB_RADIUS_DISPATCH_DIAGNOSTIC|valid=" + target.isValid()
                                                    + "|dead=" + target.isDead() + "|nativeValid=" + abstractEntity.getMethod("isValid").invoke(adapted)
                                                    + "|nativeDead=" + abstractEntity.getMethod("isDead").invoke(adapted)
                                                    + "|rawLocation=" + nativeCondition.getMethod("check", abstractLocation)
                                                            .invoke(condition, adaptLocation.invoke(null, target.getLocation()))
                                                    + "|nativeRetained=" + expectedTargets.size());
                                        }
                                        throw new IllegalStateException("Native radius dispatch differs");
                                    }
                                    rows++;
                                }
                            }
                        }
                    }
                }
            }
        } finally {
            far.remove();
        }
        if (rows != 576) throw new IllegalStateException("Incomplete dispatch evidence");
        probe.getLogger().info("MOB_RADIUS_DISPATCH_SUMMARY|comparisons=" + rows + "|scopes=3|scenes=8|passed=true");
    }
}
