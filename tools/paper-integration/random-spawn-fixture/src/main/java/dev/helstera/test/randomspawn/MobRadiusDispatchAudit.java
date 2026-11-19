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
            // Public MM gates targeter lists behind Premium. Observe that boundary separately
            // from comparisons against its public condition-library evaluator; never inject guards.
            Object nativeSkills = mythic.getClass().getMethod("getSkillManager").invoke(mythic);
            if ((boolean) mythic.getClass().getMethod("isVolatile").invoke(null))
                throw new IllegalStateException("Expected the pinned public-build targeter boundary");
            Class<?> nativeTargeter = Class.forName("io.lumine.mythic.core.skills.SkillTargeter", true, nativeLoader);
            Class<?> nativeSelector = Class.forName("io.lumine.mythic.core.skills.targeters.ILocationSelector", true, nativeLoader);
            var nativeGuards = nativeTargeter.getDeclaredField("targetConditions");
            nativeGuards.setAccessible(true);
            var nativeGuardText = nativeTargeter.getDeclaredField("targetConditionString");
            nativeGuardText.setAccessible(true);
            // Native evaluateTrigger accepts any AbstractEntity; compare real non-living entities too.
            List<Entity> triggerEntities = new ArrayList<>();
            int triggerRows = 0;
            try {
                Entity itemNear = center.getWorld().dropItem(center, new org.bukkit.inventory.ItemStack(org.bukkit.Material.STONE));
                triggerEntities.add(itemNear);
                Entity itemFar = center.getWorld().dropItem(center.clone().add(40, 20, 0), new org.bukkit.inventory.ItemStack(org.bukkit.Material.STONE));
                triggerEntities.add(itemFar);
                Entity arrowNear = center.getWorld().spawn(center, org.bukkit.entity.Arrow.class);
                triggerEntities.add(arrowNear);
                Entity arrowFar = center.getWorld().spawn(center.clone().add(40, 20, 0), org.bukkit.entity.Arrow.class);
                triggerEntities.add(arrowFar);
                Entity armor = center.getWorld().spawn(center, org.bukkit.entity.ArmorStand.class);
                triggerEntities.add(armor);
                Entity removedItem = center.getWorld().dropItem(center, new org.bukkit.inventory.ItemStack(org.bukkit.Material.STONE));
                triggerEntities.add(removedItem);
                removedItem.remove();
                for (Entity entity : triggerEntities) entity.setGravity(false);
                Map<String, Entity> scenes = new java.util.LinkedHashMap<>();
                scenes.put("item-near", itemNear); scenes.put("item-far", itemFar);
                scenes.put("arrow-near", arrowNear); scenes.put("arrow-far", arrowFar);
                scenes.put("armor", armor); scenes.put("living", custom);
                scenes.put("removed-item", removedItem); scenes.put("removed-living", removed);
                scenes.put("missing", null);
                String[][] cases = {
                        {"entitytype{t=ITEM}", "EntityTypeCondition"},
                        {"entitytype{t=ARROW}", "EntityTypeCondition"},
                        {"isLiving", "IsLivingCondition"},
                        {"health{h=0}", "HealthCondition"},
                        {"health{h=>0}", "HealthCondition"},
                        {"height{h=" + center.getY() + "}", "HeightCondition"},
                        {"height{h=>" + (center.getY() + 10) + "}", "HeightCondition"},
                        {"onground", "OnGroundCondition"},
                        {"world{w=" + center.getWorld().getName() + "}", "WorldCondition"},
                        {"mobsInRadius{r=5;t=HelsteraRadiusAuditMob;a=0}", "MobsInRadiusCondition"},
                        {"mobsInRadius{r=5;t=HelsteraRadiusAuditMob;a=1}", "MobsInRadiusCondition"},
                        {"mobsInRadius{r=5;t=ZOMBIE;a=0}", "MobsInRadiusCondition"}};
                for (String[] test : cases) for (boolean outcome : List.of(true, false)) {
                    String expression = test[0] + " " + outcome;
                    Class<?> conditionClass = Class.forName("io.lumine.mythic.core.skills.conditions.all." + test[1], true, nativeLoader);
                    Object config = configImpl.getConstructor(String.class).newInstance(expression);
                    Object condition = conditionClass.getConstructor(String.class, configType).newInstance(expression, config);
                    if (test[1].equals("MobsInRadiusCondition")) resolveTypes.invoke(condition,
                            (Object) new String[]{test[0].contains("HelsteraRadiusAuditMob") ? "HelsteraRadiusAuditMob" : "ZOMBIE"}, config);
                    for (var scene : scenes.entrySet()) {
                        Entity trigger = scene.getValue();
                        Object nativeMeta = metadataImpl.getConstructor(triggerType, skillCaster, abstractEntity)
                                .newInstance(triggerType.getMethod("get", String.class).invoke(null, "API"), nativeCaster,
                                        trigger == null ? null : adaptEntity.invoke(null, trigger));
                        boolean nativePassed = (boolean) conditionClass.getMethod("evaluateTrigger", metadata).invoke(condition, nativeMeta);
                        for (boolean inline : List.of(false, true)) for (boolean importedPath : List.of(false, true)) {
                            var yaml = new YamlConfiguration();
                            String prefix = importedPath ? "dispatch." : "skills.dispatch.";
                            if (inline) yaml.set(prefix + (importedPath ? "Skills" : "on-decision"),
                                    List.of("skill{s=observe} ?~" + (outcome ? "" : "!") + test[0]));
                            else {
                                yaml.set(prefix + (importedPath ? "TriggerConditions" : "trigger-conditions"),
                                        List.of(importedPath ? expression : (outcome ? "" : "!") + test[0]));
                                yaml.set(prefix + (importedPath ? "Skills" : "on-decision"), List.of("skill{s=observe}"));
                            }
                            if (importedPath) {
                                yaml.save(source.toFile());
                                @SuppressWarnings("unchecked")
                                List<Map<String, Object>> entries = (List<Map<String, Object>>) importer.getClass()
                                        .getMethod("convert", Path.class).invoke(importer, source);
                                var plan = entries.stream().filter(e -> "skills.yml".equals(e.get("target-file"))).findFirst().orElseThrow();
                                yaml.loadFromString(plan.get("content").toString());
                            }
                            yaml.set("skills.observe.on-decision", List.of("radius-dispatch-observe"));
                            skillsType.getMethod("loadSkills", ConfigurationSection.class).invoke(skills, yaml.getConfigurationSection("skills"));
                            Object ctx = continuation.invoke(null, model, far, 1.0, -1.0, 0L, "trigger-audit", "payload",
                                    trigger, List.of(far), center, null);
                            boolean matched = true;
                            if (!inline) {
                                Object preview = skillsType.getMethod("preview", String.class, contextType).invoke(skills, "dispatch", ctx);
                                matched = (boolean) preview.getClass().getMethod("wouldRun").invoke(preview) == nativePassed;
                            }
                            observed.clear();
                            boolean invoked = (boolean) skillsType.getMethod("castSkill", String.class, contextType).invoke(skills, "dispatch", ctx);
                            matched &= invoked == (inline || nativePassed) && observed.size() == (nativePassed ? 1 : 0);
                            if (!observed.isEmpty()) {
                                Object action = observed.getFirst();
                                matched &= ((Optional<?>) contextType.getMethod("trigger").invoke(action)).orElse(null) == trigger
                                        && ((Optional<?>) contextType.getMethod("targetEntity").invoke(action)).orElse(null) == far
                                        && contextType.getMethod("targets").invoke(action).equals(List.of(far))
                                        && ((Optional<?>) contextType.getMethod("origin").invoke(action)).orElseThrow().equals(center)
                                        && ((Optional<?>) contextType.getMethod("payload").invoke(action)).orElseThrow().equals("payload")
                                        && contextType.getMethod("conditionScope").invoke(action).toString().equals("EXECUTION");
                            }
                            probe.getLogger().info("NONLIVING_TRIGGER|scene=" + scene.getKey() + "|condition=" + test[0]
                                    + "|outcome=" + outcome + "|inline=" + inline + "|path=" + (importedPath ? "imported" : "direct")
                                    + "|native=" + nativePassed + "|runtime=" + !observed.isEmpty() + "|matched=" + matched);
                            if (!matched) throw new IllegalStateException("Non-living trigger mismatch: " + scene.getKey() + " " + expression);
                            triggerRows++;
                        }
                    }
                }
            } finally {
                for (Entity entity : triggerEntities) entity.remove();
            }
            if (triggerRows != 864) throw new IllegalStateException("Incomplete non-living trigger evidence");
            probe.getLogger().info("NONLIVING_TRIGGER_SUMMARY|comparisons=" + triggerRows + "|scenes=9|conditions=12|passed=true");
            Object nativePins = mythic.getClass().getMethod("getPinManager").invoke(mythic);
            Object packs = mythic.getClass().getMethod("getPackManager").invoke(mythic);
            Object pack = packs.getClass().getMethod("getBasePack").invoke(packs);
            Class<?> packType = Class.forName("io.lumine.mythic.api.packs.Pack", true, nativeLoader);
            Path pinFile = ((java.io.File) packType.getMethod("getPackFile", String.class).invoke(pack, "pins.yml")).toPath();
            byte[] priorPinFile = Files.exists(pinFile) ? Files.readAllBytes(pinFile) : null;
            @SuppressWarnings("unchecked") Map<String, Object> nativePinMap = (Map<String, Object>)
                    nativePins.getClass().getMethod("getPins").invoke(nativePins);
            Class<?> pinType = Class.forName("io.lumine.mythic.core.skills.pins.Pin", true, nativeLoader);
            Class<?> pinsType = Class.forName("dev.helstera.ai.skill.PinRegistry", true, runtimeLoader);
            Class<?> extras = Class.forName("dev.helstera.ai.skill.SkillExtras", true, runtimeLoader);
            var pinsGetter = extras.getDeclaredMethod("pinRegistry");
            pinsGetter.setAccessible(true);
            Object priorPins = pinsGetter.invoke(null);
            Object runtimePins = pinsType.getConstructor().newInstance();
            Location blockStart = center.getBlock().getLocation();
            Location blockEnd = blockStart.clone().add(1, 0, 0);
            var pinsYaml = new YamlConfiguration();
            List<String> pinNames = List.of("DispatchGuardRoute", "DispatchGuardFirst", "DispatchGuardLast");
            List<String> createdPins = new ArrayList<>();
            int locationRows = 0;
            try {
                for (int i = 0; i < pinNames.size(); i++) {
                    String name = pinNames.get(i);
                    if (((Optional<?>) nativePins.getClass().getMethod("getPin", String.class).invoke(nativePins, name)).isPresent())
                        throw new IllegalStateException("Audit pin already exists: " + name);
                    Location point = i == 0 ? center : i == 1 ? blockStart : blockEnd;
                    if (i == 0) {
                        Object pin = nativePins.getClass().getMethod("createMultiPin", packType, String.class, abstractLocation)
                                .invoke(nativePins, pack, name, adaptLocation.invoke(null, point));
                        createdPins.add(name);
                        @SuppressWarnings("unchecked") List<Object> points = (List<Object>) pinType.getMethod("getLocations").invoke(pin);
                        points.add(adaptLocation.invoke(null, far.getLocation()));
                        pin.getClass().getMethod("save").invoke(pin);
                        pinsYaml.set("pins." + name, List.of(point.getWorld().getName() + "," + point.getX() + "," + point.getY() + "," + point.getZ(),
                                far.getWorld().getName() + "," + far.getLocation().getX() + "," + far.getLocation().getY() + "," + far.getLocation().getZ()));
                    } else {
                        nativePins.getClass().getMethod("createPin", packType, String.class, abstractLocation)
                                .invoke(nativePins, pack, name, adaptLocation.invoke(null, point));
                        createdPins.add(name);
                        pinsYaml.set("pins." + name, point.getWorld().getName() + "," + point.getX() + "," + point.getY() + "," + point.getZ());
                    }
                }
                if (!((List<?>) pinsType.getMethod("load", ConfigurationSection.class).invoke(runtimePins, pinsYaml)).isEmpty())
                    throw new IllegalStateException("Runtime pin load failed");
                extras.getMethod("pinRegistry", pinsType).invoke(null, runtimePins);
                for (String scene : List.of("origin-near", "origin-far", "self", "trigger", "pin", "missing-pin", "region")) {
                    String selector = switch (scene) {
                        case "self" -> "@SelfLocation";
                        case "trigger" -> "@TriggerLocation";
                        case "pin" -> "@Pin{name=DispatchGuardRoute}";
                        case "missing-pin" -> "@Pin{name=DispatchGuardMissing}";
                        case "region" -> "@BlocksInPinRegion{pin1=DispatchGuardFirst;pin2=DispatchGuardLast}";
                        default -> "@Origin";
                    };
                    for (String alias : List.of("conditions", "targetconditions", "cond", "c")) {
                        for (String type : List.of("ZOMBIE", "HelsteraRadiusAuditMob")) {
                            for (int amount : new int[]{0, 1, 2}) {
                                for (boolean outcome : new boolean[]{true, false}) {
                                    String expression = "mobsInRadius{r=5;t=" + type + ";a=" + amount + "}";
                                    String guard = alias + "=[ - " + expression + " " + outcome + " ]";
                                    String targeter = selector.endsWith("}") ? selector.substring(0, selector.length() - 1) + ";" + guard + "}"
                                            : selector + "{" + guard + "}";
                                    Object target = nativeSkills.getClass().getMethod("getTargeter", String.class).invoke(nativeSkills, targeter);
                                    if (!nativeSelector.isInstance(target)) throw new IllegalStateException("Not a native location selector: " + targeter);
                                    String guardList = "[ - " + expression + " " + outcome + " ]";
                                    if (nativeGuards.get(target) != null || !guardList.equals(nativeGuardText.get(target)))
                                        throw new IllegalStateException("Unexpected public targeter condition boundary: " + targeter);
                                    // This is a public condition-library reference, not Premium targeter execution.
                                    List<?> guards = (List<?>) nativeSkills.getClass().getMethod("getConditions", String.class)
                                            .invoke(nativeSkills, guardList);
                                    if (guards.size() != 1 || !nativeCondition.isInstance(guards.getFirst()))
                                        throw new IllegalStateException("Native condition library did not parse radius guard: " + guardList);
                                    resolveTypes.invoke(guards.getFirst(), (Object) new String[]{type}, configImpl.getConstructor(String.class).newInstance(expression));
                                    Object nativeMeta = metadataImpl.getConstructor(triggerType, skillCaster, abstractEntity)
                                            .newInstance(triggerType.getMethod("get", String.class).invoke(null, "API"), nativeCaster, adaptEntity.invoke(null, custom));
                                    Location origin = scene.equals("origin-far") ? far.getLocation() : center;
                                    metadataImpl.getMethod("setOrigin", abstractLocation).invoke(nativeMeta, adaptLocation.invoke(null, origin));
                                    Collection<?> locations = (Collection<?>) nativeSelector.getMethod("getLocations", metadata).invoke(target, nativeMeta);
                                    var unfilteredPoints = new java.util.HashSet<String>();
                                    for (Object point : locations) unfilteredPoints.add(locationKey((Location) adapter.getMethod("adapt", abstractLocation).invoke(null, point)));
                                    metadataImpl.getMethod("setLocationTargets", Collection.class).invoke(nativeMeta, locations);
                                    nativeSelector.getMethod("filter", metadata).invoke(target, nativeMeta);
                                    Collection<?> publicFiltered = (Collection<?>) metadataImpl.getMethod("getLocationTargets").invoke(nativeMeta);
                                    var publicPoints = new java.util.HashSet<String>();
                                    for (Object point : publicFiltered) publicPoints.add(locationKey((Location) adapter.getMethod("adapt", abstractLocation).invoke(null, point)));
                                    if (!publicPoints.equals(unfilteredPoints))
                                        throw new IllegalStateException("Public targeter unexpectedly filtered positions");
                                    nativeCondition.getMethod("evaluateTargets", metadata).invoke(guards.getFirst(), nativeMeta);
                                    Collection<?> expected = (Collection<?>) metadataImpl.getMethod("getLocationTargets").invoke(nativeMeta);
                                    var expectedPoints = new java.util.HashSet<String>();
                                    for (Object point : expected) expectedPoints.add(locationKey((Location) adapter.getMethod("adapt", abstractLocation).invoke(null, point)));
                                    String line = "skill{s=observe} " + targeter;
                                    var sourceYaml = new YamlConfiguration();
                                    sourceYaml.set("dispatch.Skills", List.of(line));
                                    sourceYaml.set("observe.Skills", List.of("message{m=observe}"));
                                    sourceYaml.save(source.toFile());
                                    @SuppressWarnings("unchecked") List<Map<String, Object>> entries = (List<Map<String, Object>>) importer.getClass()
                                            .getMethod("convert", Path.class).invoke(importer, source);
                                    var imported = new YamlConfiguration();
                                    imported.loadFromString(entries.stream().filter(e -> "skills.yml".equals(e.get("target-file")))
                                            .findFirst().orElseThrow().get("content").toString());
                                    if (imported.getStringList("skills.dispatch.on-decision").size() != 1)
                                        throw new IllegalStateException("Location-guarded skill was not imported: " + line);
                                    imported.set("skills.observe.on-decision", List.of("radius-dispatch-observe"));
                                    var direct = new YamlConfiguration();
                                    direct.set("skills.dispatch.on-decision", List.of(line));
                                    direct.set("skills.observe.on-decision", List.of("radius-dispatch-observe"));
                                    for (var definition : List.of(imported, direct)) {
                                        skillsType.getMethod("loadSkills", ConfigurationSection.class).invoke(skills, definition.getConfigurationSection("skills"));
                                        Object ctx = continuation.invoke(null, model, custom, 1.0, -1.0, 0L, "dispatch", null,
                                                custom, List.of(custom, far), origin, null);
                                        observed.clear();
                                        boolean invoked = (boolean) skillsType.getMethod("castSkill", String.class, contextType).invoke(skills, "dispatch", ctx);
                                        var actualPoints = new java.util.HashSet<String>();
                                        boolean clean = true;
                                        for (Object action : observed) {
                                            Location point = (Location) ((Optional<?>) contextType.getMethod("targetLocation").invoke(action)).orElseThrow();
                                            actualPoints.add(locationKey(point));
                                            clean &= ((List<?>) contextType.getMethod("targets").invoke(action)).isEmpty()
                                                    && ((Optional<?>) contextType.getMethod("targetEntity").invoke(action)).isEmpty()
                                                    && contextType.getMethod("conditionScope").invoke(action).toString().equals("EXECUTION");
                                        }
                                        boolean matched = invoked && clean && expectedPoints.equals(actualPoints) && observed.size() == expectedPoints.size();
                                        probe.getLogger().info("LOCATION_TARGET_GUARD|scene=" + scene + "|alias=" + alias + "|type=" + type
                                                + "|amount=" + amount + "|outcome=" + outcome + "|path=" + (definition == imported ? "imported" : "direct")
                                                + "|publicNativeGuardApplied=false|nativeUnfilteredCount=" + publicPoints.size()
                                                + "|conditionLibraryCount=" + expectedPoints.size() + "|runtimeCount=" + observed.size() + "|matched=" + matched);
                                        if (!matched) throw new IllegalStateException("Location guard differs from native condition-library reference: " + targeter
                                                + " expected=" + expectedPoints + " actual=" + actualPoints);
                                        locationRows++;
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (Exception failure) {
                probe.getLogger().log(java.util.logging.Level.SEVERE, "LOCATION_TARGET_GUARD_ERROR", failure);
                throw failure;
            } finally {
                extras.getMethod("pinRegistry", pinsType).invoke(null, priorPins);
                // Pinned removePin throws on its empty YAML property path. Restore isolated fixture state.
                for (String name : createdPins) nativePinMap.remove(name);
                if (priorPinFile == null) Files.deleteIfExists(pinFile);
                else Files.write(pinFile, priorPinFile);
            }
            if (locationRows != 672) throw new IllegalStateException("Incomplete location guard evidence");
            probe.getLogger().info("LOCATION_TARGET_GUARD_SUMMARY|comparisons=" + locationRows
                    + "|selectors=7|aliases=4|publicNativeGuardApplied=false|reference=condition-library|passed=true");
        } finally {
            far.remove();
        }
        if (rows != 576) throw new IllegalStateException("Incomplete dispatch evidence");
        probe.getLogger().info("MOB_RADIUS_DISPATCH_SUMMARY|comparisons=" + rows + "|scopes=3|scenes=8|passed=true");
    }
    private static String locationKey(Location point) {
        return point.getWorld().getUID() + ":" + point.getX() + ":" + point.getY() + ":" + point.getZ();
    }

}
