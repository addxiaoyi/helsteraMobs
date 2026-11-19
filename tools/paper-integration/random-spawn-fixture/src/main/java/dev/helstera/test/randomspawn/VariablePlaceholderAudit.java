package dev.helstera.test.randomspawn;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Native variable mechanics and public string evaluation against the runtime action store. */
final class VariablePlaceholderAudit {
    private static final List<String> SCOPES = List.of("caster", "target", "trigger", "skill", "world", "global");
    private static final List<String> FIELDS = List.of("AuditCount", "Auditcount", "AuditRate", "AuditLabel",
            "AuditRoot", "AuditRoot.Part", "AuditAbsent", "AuditAbsent|4", "AuditAbsent|4.5",
            "AuditCount|8", "AuditRate{r=0}", "AuditBad", "AuditLocation", "AuditLocation|4");
    private static final Map<String, Object> STRINGS = new LinkedHashMap<>();
    private static Exception preparationFailure;
    private static int preparedTick;

    static void prepare(Plugin mythic) {
        STRINGS.clear(); preparationFailure = null; preparedTick = Bukkit.getCurrentTick();
        if (mythic == null) return;
        try {
            Class<?> type = Class.forName("io.lumine.mythic.api.skills.placeholders.PlaceholderString", true,
                    mythic.getClass().getClassLoader());
            for (String scope : SCOPES) for (String field : FIELDS) {
                String token = "<" + scope + ".var." + field + ">";
                STRINGS.put(token, type.getMethod("of", String.class).invoke(null, token));
            }
        } catch (Exception error) { preparationFailure = error; }
    }

    static void run(JavaPlugin probe, Plugin runtime, Plugin mythic, Location center) throws Exception {
        if (preparationFailure != null) throw new IllegalStateException("Native variable preparation failed", preparationFailure);
        if (STRINGS.size() != 84 || Bukkit.getCurrentTick() - preparedTick < 6)
            throw new IllegalStateException("Native variable placeholders did not pass actual scheduler ticks");
        int rows = 0, boundaries = 0;
        for (boolean imported : List.of(false, true)) {
            try (Harness h = new Harness(probe, runtime, mythic, center, imported)) {
                for (String scene : List.of("seeded", "changed", "swapped", "self", "unset", "new-tree",
                        "origin-other-world", "missing-trigger", "item-trigger", "removed-trigger")) {
                    Entity trigger = switch (scene) {
                        case "missing-trigger" -> null;
                        case "item-trigger" -> h.item;
                        case "swapped" -> h.b;
                        case "self" -> h.a;
                        default -> h.c;
                    };
                    LivingEntity target = scene.equals("swapped") ? h.c : scene.equals("self") ? h.a : h.b;
                    Location origin = scene.equals("origin-other-world") ? h.d.getLocation() : h.a.getLocation();
                    Object tree = h.newTree(trigger, target, origin);
                    Object ctx = h.context(h.a, target, trigger, origin, tree);
                    Object meta = h.meta(h.a, trigger);
                    // Reuse compiled placeholders across real mutations, with distinct values in every registry.
                    for (int i = 0; i < h.mobs.size(); i++) {
                        Cow subject = h.mobs.get(i);
                        h.seed("caster", h.meta(subject, subject), h.context(subject, subject, subject,
                                subject.getLocation(), tree), subject, 2 + i, "Entity" + i);
                    }
                    h.seed("skill", meta, ctx, target, 9, "Skill");
                    h.seed("world", meta, ctx, target, 20, "FirstWorld");
                    h.seed("world", h.meta(h.d, h.d), h.context(h.d, h.d, h.d, h.d.getLocation(), tree), h.d, 30, "OtherWorld");
                    h.seed("global", meta, ctx, target, 40, "Global");
                    if (scene.equals("changed")) {
                        h.set("target.AuditCount", "INTEGER", "11", meta, ctx, target);
                        h.set("caster.AuditRate", "STRING", "converted", meta, ctx, target);
                        h.set("caster.AuditCount", "INTEGER", "12", h.meta(h.c, h.c),
                                h.context(h.c, h.c, h.c, h.c.getLocation(), tree), h.c);
                    }
                    if (scene.equals("self") || scene.equals("unset")) {
                        h.unset("target.AuditCount", meta, ctx, target);
                        h.unset("skill.AuditCount", meta, ctx, target);
                    }
                    if (scene.equals("new-tree")) {
                        tree = h.newTree(trigger, target, origin);
                        ctx = h.context(h.a, target, trigger, origin, tree);
                        meta = h.meta(h.a, trigger);
                    }
                    if (scene.equals("removed-trigger")) h.c.remove();
                    for (String mode : List.of("selected-target", "caster-target", "metadata-only", "contextless")) {
                        LivingEntity subject = mode.equals("selected-target") ? target : mode.equals("caster-target") ? h.a : null;
                        Object scoped = mode.equals("contextless") ? null : h.context(h.a, subject, trigger, origin, tree);
                        for (var entry : STRINGS.entrySet()) {
                            String token = entry.getKey();
                            String expected = mode.equals("contextless")
                                    ? (String) h.strings.getMethod("get").invoke(entry.getValue())
                                    : (String) h.strings.getMethod("get", h.placeholderMeta, h.abstractEntity)
                                            .invoke(entry.getValue(), meta, subject == null ? null : h.adapt.invoke(null, subject));
                            String actual = (String) h.resolve.invoke(null, token, scoped);
                            String boundary = "none";
                            boolean matched = Objects.equals(expected, actual);
                            if (expected.equals("UNDEFINED") || expected.equals("NaN")) {
                                boundary = expected.equals("UNDEFINED") ? "missing-value" : "nonfinite";
                                matched = token.equals(actual) && (double) h.resolveNumber.invoke(null, token, scoped, -999.0) == -999.0;
                            } else if (scene.equals("item-trigger") && token.startsWith("<trigger.var.")
                                    && !mode.equals("contextless")) {
                                // Helstera permits variables on ordinary entities; native needs an ActiveMob/player registry.
                                boundary = "unmanaged-registry";
                                String wanted = token.contains("|4") ? "4" : token.contains("AuditCount|8") ? "8" : token;
                                matched = token.equals(expected) && wanted.equals(actual);
                            }
                            if (!boundary.equals("none")) boundaries++;
                            probe.getLogger().info("VARIABLE_PLACEHOLDER|token=" + token + "|scene=" + scene + "|mode=" + mode
                                    + "|path=" + (imported ? "imported" : "direct") + "|native=" + expected + "|runtime=" + actual
                                    + "|boundary=" + boundary + "|matched=" + matched);
                            if (!matched) throw new IllegalStateException("Variable placeholder differs: " + token + " " + scene + " " + mode);
                            rows++;
                        }
                    }
                }
            }
        }
        if (rows != 6720) throw new IllegalStateException("Incomplete variable placeholder observations: " + rows);
        probe.getLogger().info("VARIABLE_PLACEHOLDER_SUMMARY|comparisons=" + rows + "|tokens=84|scenes=10|modes=4|paths=2|boundaries="
                + boundaries + "|schedulerTicks=" + (Bukkit.getCurrentTick() - preparedTick) + "|passed=true");
    }

    static final class Harness implements AutoCloseable {
        final Plugin runtime, mythic;
        final boolean imported;
        final ClassLoader nativeLoader, runtimeLoader;
        final Class<?> abstractEntity, placeholderMeta, strings, contextType, modelType, treeType, metadataType, triggerType,
                skillCaster, configType, configImpl, executorType, skillMetadata;
        final Method adapt, resolve, resolveNumber;
        final Object manager, nativeSkills, registry, skills, importer;
        final Path source;
        final List<Cow> mobs = new ArrayList<>();
        final Map<Entity, Object> active = new HashMap<>();
        final Map<String, Object> nativeActions = new HashMap<>();
        final Map<String, String> actions = new HashMap<>();
        final Cow a, b, c, d;
        final Entity item;

        Harness(JavaPlugin probe, Plugin runtime, Plugin mythic, Location center, boolean imported) throws Exception {
            this(probe, runtime, mythic, center, imported, List.of());
        }
        Harness(JavaPlugin probe, Plugin runtime, Plugin mythic, Location center, boolean imported,
                List<UUID> restoredEntities) throws Exception {
            this.runtime = runtime; this.mythic = mythic; this.imported = imported;
            nativeLoader = mythic.getClass().getClassLoader(); runtimeLoader = runtime.getClass().getClassLoader();
            abstractEntity = nativeType("io.lumine.mythic.api.adapters.AbstractEntity");
            placeholderMeta = nativeType("io.lumine.mythic.core.skills.placeholders.PlaceholderMeta");
            strings = nativeType("io.lumine.mythic.api.skills.placeholders.PlaceholderString");
            metadataType = nativeType("io.lumine.mythic.core.skills.SkillMetadataImpl");
            skillMetadata = nativeType("io.lumine.mythic.api.skills.SkillMetadata");
            triggerType = nativeType("io.lumine.mythic.api.skills.SkillTrigger");
            skillCaster = nativeType("io.lumine.mythic.api.skills.SkillCaster");
            configType = nativeType("io.lumine.mythic.api.config.MythicLineConfig");
            configImpl = nativeType("io.lumine.mythic.core.config.MythicLineConfigImpl");
            executorType = nativeType("io.lumine.mythic.core.skills.SkillExecutor");
            adapt = nativeType("io.lumine.mythic.bukkit.BukkitAdapter").getMethod("adapt", Entity.class);
            contextType = runtimeType("dev.helstera.api.behavior.BehaviorContext");
            modelType = runtimeType("dev.helstera.api.instance.ModelInstance");
            treeType = runtimeType("dev.helstera.api.behavior.SkillTreeContext");
            Class<?> placeholder = runtimeType("dev.helstera.ai.skill.Placeholders");
            resolve = placeholder.getMethod("resolve", String.class, contextType);
            resolveNumber = placeholder.getMethod("resolveNumber", String.class, contextType, double.class);
            manager = mythic.getClass().getMethod("getMobManager").invoke(mythic);
            nativeSkills = mythic.getClass().getMethod("getSkillManager").invoke(mythic);
            registry = runtimeType("dev.helstera.ai.BehaviorRegistryImpl").getConstructor().newInstance();
            skills = runtimeType("dev.helstera.ai.skill.SkillService")
                    .getConstructor(runtimeType("dev.helstera.api.behavior.BehaviorRegistry"), java.util.logging.Logger.class)
                    .newInstance(registry, probe.getLogger());
            importer = runtimeType("dev.helstera.migration.importer.MythicMobsImporter").getConstructor().newInstance();
            source = probe.getDataFolder().toPath().resolve("variable-source/Skills/variables.yml");
            Files.createDirectories(source.getParent());
            Location base = center.clone().add(110, 20, 0);
            a = restoredEntities.isEmpty() ? mob(base) : restoredMob(restoredEntities.get(0));
            b = restoredEntities.isEmpty() ? mob(base.clone().add(0, 0, 4)) : restoredMob(restoredEntities.get(1));
            c = restoredEntities.isEmpty() ? mob(base.clone().add(4, 0, 0)) : restoredMob(restoredEntities.get(2));
            World other = Bukkit.getWorlds().stream().filter(w -> w != base.getWorld()).findFirst().orElseThrow();
            Location cross = base.clone(); cross.setWorld(other);
            d = restoredEntities.isEmpty() ? mob(cross) : restoredMob(restoredEntities.get(3));
            item = base.getWorld().dropItem(base, new org.bukkit.inventory.ItemStack(org.bukkit.Material.STONE));
            item.setGravity(false);
        }
        Class<?> nativeType(String name) throws ClassNotFoundException { return Class.forName(name, true, nativeLoader); }
        Class<?> runtimeType(String name) throws ClassNotFoundException { return Class.forName(name, true, runtimeLoader); }
        Cow restoredMob(UUID identity) throws Exception {
            if (!(Bukkit.getEntity(identity) instanceof Cow cow)) throw new IllegalStateException("Saved cow missing: " + identity);
            Object saved = manager.getClass().getMethod("getMythicMobInstance", Entity.class).invoke(manager, cow);
            if (saved == null) throw new IllegalStateException("Saved native registry missing: " + identity);
            mobs.add(cow); active.put(cow, saved); return cow;
        }
        Cow mob(Location point) throws Exception {
            Object api = mythic.getClass().getMethod("getAPIHelper").invoke(mythic);
            Cow cow = (Cow) api.getClass().getMethod("spawnMythicMob", String.class, Location.class)
                    .invoke(api, "HelsteraRadiusAuditMob", point);
            if (cow == null) throw new IllegalStateException("Native variable mob missing");
            mobs.add(cow); cow.setAI(false); cow.setGravity(false); cow.setInvulnerable(true);
            active.put(cow, manager.getClass().getMethod("getMythicMobInstance", Entity.class).invoke(manager, cow));
            return cow;
        }
        Object meta(Entity caster, Entity trigger) throws Exception {
            return metadataType.getConstructor(triggerType, skillCaster, abstractEntity).newInstance(
                    triggerType.getMethod("get", String.class).invoke(null, "API"), active.get(caster),
                    trigger == null ? null : adapt.invoke(null, trigger));
        }
        Object newTree(Entity trigger, LivingEntity target, Location origin) throws Exception {
            return treeType.getConstructor(Entity.class, List.class, Location.class).newInstance(trigger, List.of(target), origin);
        }
        Object context(Entity caster, LivingEntity target, Entity trigger, Location origin, Object tree) throws Exception {
            Object model = Proxy.newProxyInstance(runtimeLoader, new Class<?>[]{modelType}, (proxy, method, args) -> switch (method.getName()) {
                case "isValid" -> true;
                case "baseEntity" -> Optional.of(caster);
                case "executionAudience" -> Optional.empty();
                case "location" -> caster.getLocation();
                case "instanceId" -> caster.getEntityId();
                default -> throw new UnsupportedOperationException(method.getName());
            });
            return contextType.getMethod("continueExecution", modelType, LivingEntity.class, double.class, double.class,
                            long.class, String.class, String.class, Entity.class, List.class, Location.class, treeType)
                    .invoke(null, model, target, 1.0, 4.0, 0L, "variables", null, trigger,
                            target == null ? List.of() : List.of(target), origin, tree);
        }
        void seed(String scope, Object meta, Object ctx, Entity target, int count, String label) throws Exception {
            set(scope + ".AuditCount", "INTEGER", Integer.toString(count), meta, ctx, target);
            set(scope + ".Auditcount", "INTEGER", Integer.toString(count + 100), meta, ctx, target);
            set(scope + ".AuditRate", "FLOAT", "0.125", meta, ctx, target);
            set(scope + ".AuditLabel", "STRING", label, meta, ctx, target);
            set(scope + ".AuditRoot", "STRING", "Root" + label, meta, ctx, target);
            set(scope + ".AuditRoot.Part", "STRING", "Nested" + label, meta, ctx, target);
            set(scope + ".AuditBad", "DOUBLE", "NaN", meta, ctx, target);
            Class<?> scopeType = nativeType("io.lumine.mythic.core.skills.variables.VariableScope");
            Object variables = mythic.getClass().getMethod("getVariableManager").invoke(mythic);
            Object store = variables.getClass().getMethod("getRegistry", scopeType, placeholderMeta, abstractEntity)
                    .invoke(variables, scopeType.getField(scope.toUpperCase(Locale.ROOT)).get(null), meta, adapt.invoke(null, target));
            Class<?> locationType = nativeType("io.lumine.mythic.api.adapters.AbstractLocation");
            Location point = target.getLocation();
            Object nativePoint = nativeType("io.lumine.mythic.bukkit.BukkitAdapter").getMethod("adapt", Location.class).invoke(null, point);
            store.getClass().getMethod("putLocation", String.class, locationType).invoke(store, "AuditLocation", nativePoint);
            runtimeAction("setvariable{var=" + scope + ".AuditLocation;type=LOCATION;value=\"" + point.getWorld().getName()
                    + "," + point.getX() + "," + point.getY() + "," + point.getZ() + "\"}", ctx);
        }
        void set(String variable, String type, String value, Object meta, Object ctx, Entity target) throws Exception {
            execute("setvariable{var=" + variable + ";type=" + type + ";value=\"" + value + "\"}",
                    "VariableSetMechanic", meta, ctx, target);
        }
        void unset(String variable, Object meta, Object ctx, Entity target) throws Exception {
            execute("unsetvariable{var=" + variable + "}", "VariableUnsetMechanic", meta, ctx, target);
        }
        void execute(String line, String mechanic, Object meta, Object ctx, Entity target) throws Exception {
            execute(line, mechanic, meta, ctx, target, "SUCCESS");
        }
        void execute(String line, String mechanic, Object meta, Object ctx, Entity target, String expectedResult) throws Exception {
            Object action = nativeActions.get(line);
            if (action == null) {
                action = nativeType("io.lumine.mythic.core.skills.mechanics." + mechanic)
                        .getConstructor(executorType, java.io.File.class, String.class, configType)
                        .newInstance(nativeSkills, source.toFile(), line, configImpl.getConstructor(String.class).newInstance(line));
                nativeActions.put(line, action);
            }
            Object result = action.getClass().getMethod("castAtEntity", skillMetadata, abstractEntity)
                    .invoke(action, meta, adapt.invoke(null, target));
            if (!result.toString().equals(expectedResult)) throw new IllegalStateException("Native variable action failed: " + line + " " + result);
            runtimeAction(line, ctx);
        }
        void runtimeAction(String line, Object ctx) throws Exception {
            String bound = actions.get(line);
            if (bound == null) {
                String runtimeLine = line;
                if (imported) {
                    var yaml = new YamlConfiguration(); yaml.set("write.Skills", List.of(line)); yaml.save(source.toFile());
                    @SuppressWarnings("unchecked") List<Map<String, Object>> plans = (List<Map<String, Object>>) importer.getClass()
                            .getMethod("convert", Path.class).invoke(importer, source);
                    var plan = plans.stream().filter(p -> "skills.yml".equals(p.get("target-file"))).findFirst().orElseThrow();
                    yaml.loadFromString(plan.get("content").toString());
                    List<String> lines = yaml.getStringList("skills.write.on-decision");
                    if (lines.size() != 1) throw new IllegalStateException("Variable mechanic was not imported: " + line);
                    runtimeLine = lines.getFirst();
                }
                bound = (String) skills.getClass().getMethod("bindAction", String.class).invoke(skills, runtimeLine);
                if (bound == null) throw new IllegalStateException("Variable mechanic did not bind: " + runtimeLine);
                actions.put(line, bound);
            }
            if (!(boolean) registry.getClass().getMethod("runAction", String.class, contextType).invoke(registry, bound, ctx))
                throw new IllegalStateException("Runtime variable action failed: " + line);
        }
        @Override public void close() throws Exception {
            try {
                for (Cow cow : List.of(a, d)) {
                    Object meta = meta(cow, cow), ctx = context(cow, cow, cow, cow.getLocation(), newTree(cow, cow, cow.getLocation()));
                    for (String scope : List.of("world", "global"))
                        for (String key : List.of("AuditCount", "Auditcount", "AuditRate", "AuditLabel", "AuditRoot", "AuditRoot.Part", "AuditBad", "AuditLocation"))
                            unset(scope + "." + key, meta, ctx, cow);
                }
            } finally {
                item.remove();
                for (Cow cow : mobs) {
                    runtimeType("dev.helstera.ai.skill.SkillExtras").getMethod("forgetEntity", Entity.class).invoke(null, cow);
                    runtimeType("dev.helstera.api.behavior.VariableLocations").getMethod("clearEntity", Entity.class).invoke(null, cow);
                    manager.getClass().getMethod("unregisterActiveMob", UUID.class).invoke(manager, cow.getUniqueId());
                    cow.remove();
                }
            }
        }
    }
}
