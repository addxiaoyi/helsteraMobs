package dev.helstera.test.randomspawn;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.entity.ArmorStand;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Diagnostic comparison: mismatches remain exploratory evidence, never a release pass. */
final class MobRadiusAudit {
    private MobRadiusAudit() { }

    static void run(JavaPlugin probe, Plugin helstera, Player player) throws Exception {
        Plugin mythic = Bukkit.getPluginManager().getPlugin("MythicMobs");
        if (mythic == null || !mythic.isEnabled()) throw new IllegalStateException("MythicMobs unavailable");
        ClassLoader nativeLoader = mythic.getClass().getClassLoader();
        ClassLoader runtimeLoader = helstera.getClass().getClassLoader();
        Class<?> configType = Class.forName("io.lumine.mythic.api.config.MythicLineConfig", true, nativeLoader);
        Class<?> configImpl = Class.forName("io.lumine.mythic.core.config.MythicLineConfigImpl", true, nativeLoader);
        Class<?> nativeType = Class.forName("io.lumine.mythic.core.skills.conditions.all.MobsInRadiusCondition",
                true, nativeLoader);
        Class<?> nativeLocation = Class.forName("io.lumine.mythic.api.adapters.AbstractLocation", true, nativeLoader);
        Class<?> nativeCaster = Class.forName("io.lumine.mythic.api.skills.SkillCaster", true, nativeLoader);
        Class<?> nativeMetadata = Class.forName("io.lumine.mythic.api.skills.SkillMetadata", true, nativeLoader);
        Class<?> adapter = Class.forName("io.lumine.mythic.bukkit.BukkitAdapter", true, nativeLoader);
        Method adaptLocation = adapter.getMethod("adapt", Location.class);
        Method adaptEntity = adapter.getMethod("adapt", Entity.class);
        Method locationCheck = nativeType.getMethod("check", nativeLocation);
        Method casterCheck = nativeType.getMethod("check", nativeMetadata);
        // Execute the pinned implementation's deferred type-resolution callback.
        // This exercises its actual resolver without rerunning unrelated plugin loaders.
        Method resolveTypes = nativeType.getDeclaredMethod("lambda$new$1", String[].class, configType);
        resolveTypes.setAccessible(true);
        Class<?> modelType = Class.forName("dev.helstera.api.instance.ModelInstance", true, runtimeLoader);
        Class<?> contextType = Class.forName("dev.helstera.api.behavior.BehaviorContext", true, runtimeLoader);
        Method execution = contextType.getMethod("execution", modelType, Player.class, double.class,
                double.class, long.class, String.class, String.class, Entity.class, List.class, Location.class);
        Method translate = Class.forName("dev.helstera.migration.importer.MmConditions", true, runtimeLoader)
                .getMethod("translate", String.class);
        var skillField = helstera.getClass().getDeclaredField("skillService");
        skillField.setAccessible(true);
        Object skills = skillField.get(helstera);
        var registryField = helstera.getClass().getDeclaredField("behaviorRegistry");
        registryField.setAccessible(true);
        Object registry = registryField.get(helstera);
        Method bind = skills.getClass().getMethod("bindCondition", String.class);
        Method test = registry.getClass().getMethod("testCondition", String.class, contextType);
        Location center = player.getLocation().add(0, 60, 0);
        List<Entity> entities = new ArrayList<>();
        int observations = 0;
        int mismatches = 0;
        try {
            Zombie caster = center.getWorld().spawn(center, Zombie.class);
            entities.add(caster);
            for (Location at : List.of(center.clone().add(1, 0, 0), center.clone().add(2, 0, 0),
                    center.clone().add(4, 0, 4))) {
                entities.add(center.getWorld().spawn(at, Zombie.class));
            }
            entities.add(center.getWorld().spawn(center.clone().add(0, 1, 0), ArmorStand.class));
            Object nativeApi = mythic.getClass().getMethod("getAPIHelper").invoke(mythic);
            Entity custom = (Entity) nativeApi.getClass().getMethod("spawnMythicMob", String.class, Location.class)
                    .invoke(nativeApi, "HelsteraRadiusAuditMob", center.clone().add(0, 0, 1));
            if (custom == null) throw new IllegalStateException("Native audit mob was not spawned");
            entities.add(custom);
            for (Entity entity : entities) {
                entity.setGravity(false);
                entity.setInvulnerable(true);
                if (entity instanceof org.bukkit.entity.Mob mob) mob.setAI(false);
            }
            Object adaptedCaster = adaptEntity.invoke(null, caster);
            Object nativeSource = Proxy.newProxyInstance(nativeLoader, new Class<?>[]{nativeCaster},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getEntity" -> adaptedCaster;
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
            Object metadata = Proxy.newProxyInstance(nativeLoader, new Class<?>[]{nativeMetadata},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getCaster" -> nativeSource;
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
            Object model = Proxy.newProxyInstance(runtimeLoader, new Class<?>[]{modelType},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "isValid" -> true;
                        case "baseEntity" -> Optional.of(caster);
                        case "executionAudience" -> Optional.empty();
                        case "location" -> caster.getLocation();
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
            record Case(String source, String types) { }
            var cases = List.of(
                    new Case("mobsInRadius", ""),
                    new Case("mobsInRadius{r=5}", ""),
                    new Case("mobsInRadius{r=5;a=0}", ""),
                    new Case("mobsInRadius{r=5;t=ZOMBIE;a=1}", "ZOMBIE"),
                    new Case("mobsInRadius{r=5;t=ZOMBIE;a=2}", "ZOMBIE"),
                    new Case("mobsInRadius{r=5;t=ZOMBIE;a=3}", "ZOMBIE"),
                    new Case("mobsInRadius{r=5;t=ARMOR_STAND;a=1}", "ARMOR_STAND"),
                    new Case("mobsInRadius{r=5;t=ZOMBIE;a=>1.5}", "ZOMBIE"),
                    new Case("mobsInRadius{r=5;t=ZOMBIE;a=<2.5}", "ZOMBIE"),
                    new Case("mobsInRadius{r=5;t=ZOMBIE;a=1to2}", "ZOMBIE"),
                    new Case("mobsInRadius{t=ZOMBIE} 2", "ZOMBIE"),
                    new Case("mobsInRadius{r=5;t=ZOMBIE} >1", "ZOMBIE"),
                    new Case("mobsInRadius 2", ""),
                    new Case("mobsInRadius{t=ZOMBIE}", "ZOMBIE"),
                    new Case("mobsInRadius{t=ZOMBIE;a=2}", "ZOMBIE"),
                    new Case("mobsInRadius{r=1;t=ZOMBIE;a=1}", "ZOMBIE"),
                    new Case("mobsInRadius{r=5;t=HelsteraRadiusAuditMob;a=1}", "HelsteraRadiusAuditMob"),
                    new Case("mobsInRadius{r=5;t=HelsteraRadiusAuditMob;a=0}", "HelsteraRadiusAuditMob"),
                    new Case("mobsInRadius{r=5;t=ZOMBIE,HelsteraRadiusAuditMob;a=3}", "ZOMBIE,HelsteraRadiusAuditMob"),
                    new Case("mobsInRadius{r=5;t=ZOMBIE,HelsteraRadiusAuditMob;a=1}", "ZOMBIE,HelsteraRadiusAuditMob"));
            for (Case sample : cases) {
                Object config = configImpl.getConstructor(String.class).newInstance(sample.source());
                Object nativeCondition = nativeType.getConstructor(String.class, configType)
                        .newInstance(sample.source(), config);
                resolveTypes.invoke(nativeCondition, (Object) sample.types().split(","), config);
                Object mapped = translate.invoke(null, "?" + sample.source());
                boolean translated = (boolean) mapped.getClass().getMethod("translated").invoke(mapped);
                String runtime = (String) mapped.getClass().getMethod("condition").invoke(mapped);
                String key = translated ? (String) bind.invoke(skills, runtime) : null;
                String directKey = (String) bind.invoke(skills, sample.source());
                for (String mode : List.of("caster", "location", "target-location")) {
                    for (double offset : new double[]{0, 40}) {
                        Location origin = center.clone().add(offset, 0, 0);
                        Object context = execution.invoke(null, mode.equals("location") ? null : model,
                                null, 1.0, -1.0, 0L, "mob-radius-audit", null, null, List.of(), origin);
                        if (mode.equals("target-location"))
                            context = contextType.getMethod("withLocationTarget", Location.class).invoke(context, origin);
                        boolean nativeResult = mode.equals("caster")
                                ? (boolean) casterCheck.invoke(nativeCondition, metadata)
                                : (boolean) locationCheck.invoke(nativeCondition, adaptLocation.invoke(null, origin));
                        boolean importedResult = key != null && (boolean) test.invoke(registry, key, context);
                        boolean directResult = directKey != null && (boolean) test.invoke(registry, directKey, context);
                        boolean matched = key != null && directKey != null
                                && nativeResult == importedResult && nativeResult == directResult;
                        observations++;
                        if (!matched) mismatches++;
                        probe.getLogger().info("MOB_RADIUS_AUDIT|source=" + sample.source() + "|mode=" + mode
                                + "|offset=" + offset + "|mapped=" + translated + "|bound=" + (key != null)
                                + "|native=" + nativeResult + "|imported=" + importedResult
                                + "|direct=" + directResult + "|matched=" + matched);
                    }
                }
            }
        } catch (Exception error) {
            probe.getLogger().log(java.util.logging.Level.SEVERE, "MOB_RADIUS_AUDIT_ERROR", error);
            throw error;
        } finally {
            entities.forEach(Entity::remove);
        }
        boolean cleanup = entities.stream().noneMatch(Entity::isValid);
        probe.getLogger().info("MOB_RADIUS_AUDIT_SUMMARY|observations=" + observations + "|mismatches=" + mismatches
                + "|cleanup=" + cleanup);
        if (observations != 120 || !cleanup) throw new IllegalStateException("Incomplete mob-radius audit");
    }
}
