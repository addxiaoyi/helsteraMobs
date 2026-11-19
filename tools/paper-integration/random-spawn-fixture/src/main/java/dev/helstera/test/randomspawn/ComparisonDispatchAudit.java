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
        VariablePlaceholderAudit.run(probe, helstera, mythic, center);
        TriggerPlaceholderAudit.run(probe, helstera, mythic, center);
        run(probe, helstera, mythic, center, false, false);
        run(probe, helstera, mythic, center, true, false);
        run(probe, helstera, mythic, center, false, true);
        VariableExpiryAudit.start(probe, helstera, mythic, center);
    }

    private static void run(JavaPlugin probe, Plugin helstera, Plugin mythic, Location center,
                            boolean extended, boolean dynamicDistance) throws Exception {
        List<Entity> owned = new ArrayList<>();
        Relations relations = null;
        try {
            Location base = center.clone().add(80, 20, 0);
            base.setYaw(0); base.setPitch(0);
            Cow caster = extended ? nativeCow(base, owned, mythic) : cow(base, owned);
            Cow front = extended ? nativeCow(base.clone().add(0, 0, 4), owned, mythic) : cow(base.clone().add(0, 0, 4), owned);
            Cow precision = cow(base.clone().add(0, 0, 4.00000001), owned);
            Cow back = extended ? nativeCow(base.clone().add(0, 0, -4), owned, mythic) : cow(base.clone().add(0, 0, -4), owned);
            Cow above = extended ? nativeCow(base.clone().add(0, 4, 0), owned, mythic) : cow(base.clone().add(0, 4, 0), owned);
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
            if (extended) {
                var expanded = new ArrayList<>(scenes);
                expanded.addAll(List.of(
                        new Scene("related-removed-item", List.of(back), removedItem, null, 0),
                        new Scene("related-removed-living", List.of(removed), removed, null, 0),
                        new Scene("related-item", List.of(back), itemEye, null, 0),
                        new Scene("no-relations", List.of(front), front, null, 0),
                        new Scene("no-faction", List.of(cross), cross, null, 0),
                        new Scene("empty-faction", List.of(cross), cross, null, 0),
                        new Scene("unregistered", List.of(front), front, null, 0),
                        new Scene("occluded", List.of(front), front, null, 0),
                        new Scene("mounted", List.of(above), above, null, 0),
                        new Scene("dismounted", List.of(above), above, null, 0)));
                scenes = List.copyOf(expanded);
            }
            if (dynamicDistance) {
                var expanded = new ArrayList<>(scenes);
                expanded.addAll(List.of(
                        new Scene("low-health", List.of(front), front, null, 0),
                        new Scene("rounded-health", List.of(front), front, null, 0),
                        new Scene("mixed-world", List.of(front, cross), front, null, 0),
                        new Scene("mixed-world-reversed", List.of(cross, front), cross, null, 0),
                        new Scene("mixed-removed-world", List.of(removed, cross), cross, null, 0)));
                scenes = List.copyOf(expanded);
                front.setHealth(8);
            }
            String[][] cases = {
                    {"distance{d=0}", "DistanceCondition"}, {"distance{d=4}", "DistanceCondition"},
                    {"distance{d=<4}", "DistanceCondition"}, {"distance{d=>4}", "DistanceCondition"},
                    {"distance{d=5to3}", "DistanceCondition"}, {"distance{d=-4}", "DistanceCondition"},
                    {"ydiff{d=4}", "YDiffCondition"}, {"ydiff{d=-4}", "YDiffCondition"},
                    {"ydiff{d=>0}", "YDiffCondition"},
                    {"fov{a=90}", "FieldOfViewCondition"}, {"fov{a=90;r=90}", "FieldOfViewCondition"},
                    {"fieldofview{a=20}", "FieldOfViewCondition"},
                    {"fov{a=-90}", "FieldOfViewCondition"}, {"fov{a=0}", "FieldOfViewCondition"},
                    {"fov{a=-0}", "FieldOfViewCondition"}, {"fov{a=180}", "FieldOfViewCondition"},
                    {"fov{a=180.000005}", "FieldOfViewCondition"}, {"fov{a=180.000006}", "FieldOfViewCondition"},
                    {"fov{a=181}", "FieldOfViewCondition"}, {"fov{a=270}", "FieldOfViewCondition"},
                    {"fov{a=359.99999}", "FieldOfViewCondition"}, {"fov{a=360}", "FieldOfViewCondition"},
                    {"fov{a=360.00001}", "FieldOfViewCondition"}, {"fov{a=360.00002}", "FieldOfViewCondition"},
                    {"fov{a=720}", "FieldOfViewCondition"}, {"fov{a=1.7976931348623157E308}", "FieldOfViewCondition"},
                    {"fov{a=181;r=450}", "FieldOfViewCondition"}, {"fov{a=181;r=-270}", "FieldOfViewCondition"},
                    {"fov{a=360;r=720}", "FieldOfViewCondition"}, {"fov{a=360;r=-450}", "FieldOfViewCondition"},
                    {"boundingboxesoverlap", "BoundingBoxesOverlapCondition"},
                    {"bbsoverlap{so=4}", "BoundingBoxesOverlapCondition"},
                    {"boundingboxesoverlap{fo=-4}", "BoundingBoxesOverlapCondition"},
                    {"iscaster", "IsCasterCondition"}};
            if (extended) {
                String min = coordinates(base.clone().add(-4, -4, -4));
                String max = coordinates(base.clone().add(4, 4, 4));
                cases = new String[][] {
                        {"cuboid{a=-4,-4,-4;b=4,4,4;r=true}", "CuboidCondition"},
                        {"cuboid{l1=4,4,4;l2=-4,-4,-4;relative=true}", "CuboidCondition"},
                        {"cuboid{location1=" + min + ";location2=" + max + "}", "CuboidCondition"},
                        {"cuboid{loc1=" + min + ";loc2=" + max + ";r=true}", "CuboidCondition"},
                        {"cuboid{a=0,0,0;b=0,0,0;r=true}", "CuboidCondition"},
                        {"cuboid{a=0,0,0;b=4,4,4;r=true}", "CuboidCondition"},
                        {"owner", "OwnerCondition"}, {"isparent", "IsParentCondition"},
                        {"samefaction", "FactionSameCondition"}, {"lineofsight", "LineOfSightCondition"},
                        {"vehicleisdead", "VehicleIsDeadCondition"}};
            }
            if (dynamicDistance) {
                cases = new String[][] {
                        {"distance{d=<caster.hp>}", "DistanceCondition"},
                        {"distance{d=<<caster.hp>}", "DistanceCondition"},
                        {"distance{d=><caster.hp>}", "DistanceCondition"},
                        {"distance{d=0to<caster.hp>}", "DistanceCondition"},
                        {"distance{d=<target.hp>}", "DistanceCondition"},
                        {"distance{d=<<target.hp>}", "DistanceCondition"},
                        {"distance{d=<target.hp{r=0}>}", "DistanceCondition"},
                        {"distance{d=<<target.hp{r=0}>}", "DistanceCondition"},
                        {"distance{d=<caster.hp{rounding=0}>}", "DistanceCondition"},
                        {"distance{d=<c.h>}", "DistanceCondition"},
                        {"distance{d=<random.4to4>}", "DistanceCondition"},
                        {"distance{d=<<random.4to4>}", "DistanceCondition"},
                        {"distance{d=><random.4to4>}", "DistanceCondition"},
                        {"distance{d=<random.-4to-4>}", "DistanceCondition"},
                        {"distance{d=<random.float.4to4>}", "DistanceCondition"},
                        {"distance{d=<random.float{r=0}.4to4>}", "DistanceCondition"},
                        {"distance{d=<random.4to4>to<random.4to4>}", "DistanceCondition"},
                        {"distance{d=<random.0to0>to<random.4to4>}", "DistanceCondition"},
                        {"distance{d=<target.hp>to<caster.hp>}", "DistanceCondition"},
                        {"distance{d=<random.float.rounded.4to4>}", "DistanceCondition"}};
            }
            if (dynamicDistance) {
                var expandedCases = new ArrayList<>(Arrays.asList(cases));
                for (String expression : TriggerPlaceholderAudit.DISTANCES)
                    expandedCases.add(new String[]{expression, "DistanceCondition"});
                for (String expression : List.of("distance{d=<global.var.HelsteraDistanceMissing|4>}",
                        "distance{d=<<global.var.HelsteraDistanceMissing|4>}",
                        "distance{d=<world.var.HelsteraDistanceMissing|4>}",
                        "distance{d=<skill.var.HelsteraDistanceMissing|4>}",
                        "distance{d=<global.var.HelsteraDistanceMissing|4.5>}",
                        "distance{d=<skill.var.HelsteraDistanceMissing>}"))
                    expandedCases.add(new String[]{expression, "DistanceCondition"});
                cases = expandedCases.toArray(String[][]::new);
            }
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
            if (extended) relations = new Relations(helstera, mythic, caster, front, back, above, base, owned);
            String marker = extended ? "RELATIONAL_DISPATCH" : dynamicDistance ? "DYNAMIC_DISTANCE_DISPATCH" : "COMPARISON_DISPATCH";
            int rows = 0;
            for (String[] test : cases) for (boolean outcome : List.of(true, false)) {
                String expression = test[0] + " " + outcome;
                Class<?> conditionClass = Class.forName("io.lumine.mythic.core.skills.conditions.all." + test[1], true, nativeLoader);
                Object config = configImpl.getConstructor(String.class).newInstance(expression);
                Object condition = test[0].contains("<trigger.") ? TriggerPlaceholderAudit.condition(expression)
                        : conditionClass.getConstructor(String.class, configType).newInstance(expression, config);
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
                            if (relations != null) relations.configure(scene);
                            if (dynamicDistance) caster.setHealth(scene.name().equals("low-health") ? .4
                                    : scene.name().equals("rounded-health") ? .6 : 3.125);
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
                            probe.getLogger().info(marker + "|condition=" + test[0] + "|mode=" + mode
                                    + "|outcome=" + outcome + "|scene=" + scene.name() + "|path=" + (importedPath ? "imported" : "direct")
                                    + "|native=" + expected + "|runtime=" + !observed.isEmpty() + "|matched=" + matched);
                            if (!matched) throw new IllegalStateException("Comparison dispatch differs: " + expression + " " + mode + " " + scene.name());
                            rows++;
                        }
                    }
                }
            }
            int expectedRows = extended ? 6820 : dynamicDistance ? 17680 : 14280;
            if (rows != expectedRows) throw new IllegalStateException("Incomplete comparison dispatch evidence: " + rows);
            probe.getLogger().info(marker + "_SUMMARY|comparisons=" + rows + "|conditions=" + cases.length
                    + "|scenes=" + scenes.size() + "|modes=5|passed=true");
        } finally {
            try { if (relations != null) relations.close(); }
            finally { for (Entity entity : owned) entity.remove(); }
        }
    }

    private static String coordinates(Location point) {
        return point.getX() + "," + point.getY() + "," + point.getZ();
    }

    private static Cow nativeCow(Location location, List<Entity> owned, Plugin mythic) throws Exception {
        Object api = mythic.getClass().getMethod("getAPIHelper").invoke(mythic);
        Cow cow = (Cow) api.getClass().getMethod("spawnMythicMob", String.class, Location.class)
                .invoke(api, "HelsteraRadiusAuditMob", location);
        if (cow == null) throw new IllegalStateException("Native relation mob missing");
        owned.add(cow); cow.setAI(false); cow.setGravity(false); cow.setInvulnerable(true);
        return cow;
    }

    /** Uses public native ownership/faction APIs and production Helstera relation storage. */
    private static final class Relations implements AutoCloseable {
        private final Class<?> factions, tableType, relationType, activeType, casterType;
        private final Object priorTable, table, manager, activeCaster;
        private final Cow caster, front, above, vehicle;
        private final List<Cow> nativeMembers;
        private final Location aboveStart;
        private boolean obstructed;
        private final Method adaptEntity, register, unregister, bind;
        private final List<org.bukkit.block.BlockState> wall = new ArrayList<>();
        private boolean registered = true;

        Relations(Plugin helstera, Plugin mythic, Cow caster, Cow front, Cow back, Cow above,
                  Location base, List<Entity> owned) throws Exception {
            this.caster = caster; this.front = front; this.above = above;
            aboveStart = above.getLocation();
            nativeMembers = List.of(caster, front, back, above);
            ClassLoader runtime = helstera.getClass().getClassLoader(), nativeLoader = mythic.getClass().getClassLoader();
            factions = Class.forName("dev.helstera.api.behavior.Factions", true, runtime);
            tableType = Class.forName("dev.helstera.api.behavior.FactionTable", true, runtime);
            relationType = Class.forName("dev.helstera.api.behavior.MobRelations", true, runtime);
            table = Class.forName("dev.helstera.ai.FactionService", true, runtime).getConstructor().newInstance();
            bind = table.getClass().getMethod("bindInstance", UUID.class, String.class);
            priorTable = factions.getMethod("table").invoke(null);
            manager = mythic.getClass().getMethod("getMobManager").invoke(mythic);
            activeType = Class.forName("io.lumine.mythic.core.mobs.ActiveMob", true, nativeLoader);
            casterType = Class.forName("io.lumine.mythic.api.skills.SkillCaster", true, nativeLoader);
            adaptEntity = Class.forName("io.lumine.mythic.bukkit.BukkitAdapter", true, nativeLoader).getMethod("adapt", Entity.class);
            register = manager.getClass().getMethod("registerActiveMob", activeType);
            unregister = manager.getClass().getMethod("unregisterActiveMob", UUID.class);
            activeCaster = active(caster);
            setFaction(front, "Guards"); setFaction(back, "guards"); setFaction(above, "Raiders");
            vehicle = cow(base.clone().add(12, 0, 0), owned);
            // Isolate the only obstruction, and restore exact block data after each scene.
            for (int x = -1; x <= 1; x++) for (int y = 0; y <= 3; y++)
                wall.add(base.clone().add(x, y, 2).getBlock().getState());
            factions.getMethod("install", tableType).invoke(null, table);
        }

        private Object active(Entity entity) throws Exception {
            return ((Optional<?>) manager.getClass().getMethod("getActiveMob", UUID.class)
                    .invoke(manager, entity.getUniqueId())).orElseThrow();
        }

        private void setFaction(Entity entity, String value) throws Exception {
            activeType.getMethod("setFaction", String.class).invoke(active(entity), value);
            bind.invoke(table, entity.getUniqueId(), value);
        }

        void configure(Scene scene) throws Exception {
            if (!registered) { register.invoke(manager, activeCaster); registered = true; }
            vehicle.eject();
            if (!above.getLocation().equals(aboveStart) && !above.teleport(aboveStart))
                throw new IllegalStateException("Could not restore subject position");
            if (obstructed) for (var block : wall) block.update(true, false);
            obstructed = scene.name().equals("occluded");
            if (obstructed) for (var block : wall) block.getBlock().setType(org.bukkit.Material.STONE, false);
            if (scene.name().equals("mounted") && !vehicle.addPassenger(above))
                throw new IllegalStateException("Could not mount subject");
            if (scene.name().equals("dismounted")) {
                if (!vehicle.addPassenger(above)) throw new IllegalStateException("Could not mount subject");
                vehicle.eject();
            }
            String faction = switch (scene.name()) {
                case "no-faction", "unregistered" -> null;
                case "empty-faction" -> "";
                default -> "Guards";
            };
            activeType.getMethod("setFaction", String.class).invoke(activeCaster, faction);
            bind.invoke(table, caster.getUniqueId(), faction);
            Entity related = scene.name().startsWith("related-") ? scene.trigger() : front;
            if (scene.name().equals("no-relations") || scene.name().equals("unregistered")) related = null;
            relationType.getMethod("removeOwner", Entity.class).invoke(null, caster);
            relationType.getMethod("clearParent", Entity.class).invoke(null, caster);
            activeType.getMethod("removeOwner").invoke(activeCaster);
            activeType.getMethod("setParent", casterType).invoke(activeCaster, new Object[]{null});
            if (related != null) {
                activeType.getMethod("setOwner", UUID.class).invoke(activeCaster, related.getUniqueId());
                Object adapted = adaptEntity.invoke(null, related);
                Object parent = Proxy.newProxyInstance(casterType.getClassLoader(), new Class<?>[]{casterType},
                        (proxy, method, args) -> switch (method.getName()) {
                            case "getEntity" -> adapted;
                            case "addChild" -> null;
                            default -> throw new UnsupportedOperationException(method.getName());
                        });
                activeType.getMethod("setParent", casterType).invoke(activeCaster, parent);
                relationType.getMethod("setOwner", Entity.class, Entity.class).invoke(null, caster, related);
                relationType.getMethod("setParent", Entity.class, Entity.class).invoke(null, caster, related);
            }
            if (scene.name().equals("unregistered")) {
                unregister.invoke(manager, caster.getUniqueId()); registered = false;
            }
        }

        @Override public void close() throws Exception {
            try {
                for (var block : wall) block.update(true, false);
                for (Entity entity : nativeMembers) unregister.invoke(manager, entity.getUniqueId());
            } finally { factions.getMethod("install", tableType).invoke(null, priorTable); }
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
