package dev.helstera.test.randomspawn;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.*;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.*;

/** Public native placeholders are constructed early and evaluated after real scheduler ticks. */
final class TriggerPlaceholderAudit {
    private static final Map<String, Object> PLACEHOLDERS = new LinkedHashMap<>();
    private static final Map<String, Object> CONDITIONS = new LinkedHashMap<>();
    private static int preparedTick;
    private static Exception preparationFailure;
    static final List<String> DISTANCES = List.of(
            "distance{d=<trigger.hp>}", "distance{d=<<trigger.hp>}",
            "distance{d=0to<trigger.hp>}", "distance{d=<trigger.hp{r=0}>}",
            "distance{d=<<trigger.hp{rounding=0}>}", "distance{d=<trigger.mhp>}",
            "distance{d=<trigger.l.y.double>}", "distance{d=<trigger.l.x.double>}");

    static void prepare(Plugin mythic) {
        PLACEHOLDERS.clear(); CONDITIONS.clear(); preparationFailure = null;
        preparedTick = Bukkit.getCurrentTick();
        if (mythic == null) return;
        try {
            ClassLoader loader = mythic.getClass().getClassLoader();
            Class<?> strings = Class.forName("io.lumine.mythic.api.skills.placeholders.PlaceholderString", true, loader);
            var fields = new ArrayList<>(List.of("uuid", "name", "entity_type", "health", "hp", "thp",
                    "maxhealth", "mhp", "healthpercent", "percenthealth", "php"));
            for (String prefix : List.of("l.", "location."))
                for (String axis : List.of("x", "y", "z", "x.double", "y.double", "z.double", "yaw", "pitch", "w"))
                    fields.add(prefix + (prefix.equals("location.") && axis.equals("w") ? "world" : axis));
            fields.addAll(List.of("hp{r=0}", "hp{rounding=2}", "hp{}", "l.x{r=0}", "l.yaw{r=0}"));
            for (String field : fields) {
                String token = "<trigger." + field + ">";
                PLACEHOLDERS.put(token, strings.getMethod("of", String.class).invoke(null, token));
            }
            Class<?> condition = Class.forName("io.lumine.mythic.core.skills.conditions.all.DistanceCondition", true, loader);
            Class<?> config = Class.forName("io.lumine.mythic.api.config.MythicLineConfig", true, loader);
            Class<?> configImpl = Class.forName("io.lumine.mythic.core.config.MythicLineConfigImpl", true, loader);
            for (String expression : DISTANCES) for (boolean outcome : List.of(true, false)) {
                String line = expression + " " + outcome;
                CONDITIONS.put(line, condition.getConstructor(String.class, config)
                        .newInstance(line, configImpl.getConstructor(String.class).newInstance(line)));
            }
        } catch (Exception e) { preparationFailure = e; }
    }

    static Object condition(String expression) {
        requirePrepared();
        return Objects.requireNonNull(CONDITIONS.get(expression), "Native trigger condition was not preloaded");
    }

    private static void requirePrepared() {
        if (preparationFailure != null) throw new IllegalStateException("Native trigger preparation failed", preparationFailure);
        if (PLACEHOLDERS.size() != 34 || CONDITIONS.size() != 16 || Bukkit.getCurrentTick() - preparedTick < 6)
            throw new IllegalStateException("Native trigger placeholders must pass actual scheduler ticks before evaluation");
    }

    private record Scene(String name, Entity trigger) { }

    static void run(JavaPlugin probe, Plugin helstera, Plugin mythic, Location center) throws Exception {
        requirePrepared();
        List<Entity> owned = new ArrayList<>();
        try {
            Location base = center.clone().add(-80.25, 20.125, -80.75);
            Cow caster = cow(base, owned);
            Cow target = cow(base.clone().add(3, 0, 0), owned);
            Cow trigger = cow(base.clone().add(0, 0, 4), owned);
            target.setHealth(8.75); caster.setHealth(9.75);
            Entity item = base.getWorld().dropItem(base, new org.bukkit.inventory.ItemStack(org.bukkit.Material.STONE));
            owned.add(item); item.setGravity(false);
            Arrow arrow = base.getWorld().spawn(base, Arrow.class); owned.add(arrow); arrow.setGravity(false);
            Cow removedCow = cow(base, owned); removedCow.setHealth(7.125); removedCow.remove();
            Entity removedItem = base.getWorld().dropItem(base, new org.bukkit.inventory.ItemStack(org.bukkit.Material.STONE));
            owned.add(removedItem); removedItem.remove();
            Location other = base.clone();
            other.setWorld(Bukkit.getWorlds().stream().filter(w -> w != base.getWorld()).findFirst().orElseThrow());
            Cow cross = cow(other, owned);
            List<Scene> scenes = List.of(new Scene("living", trigger), new Scene("changed", trigger),
                    new Scene("empty-name", trigger), new Scene("item", item), new Scene("arrow", arrow),
                    new Scene("removed-living", removedCow), new Scene("removed-item", removedItem),
                    new Scene("cross-world", cross), new Scene("missing", null), new Scene("self", caster));
            ClassLoader nativeLoader = mythic.getClass().getClassLoader();
            ClassLoader runtimeLoader = helstera.getClass().getClassLoader();
            Class<?> abstractEntity = Class.forName("io.lumine.mythic.api.adapters.AbstractEntity", true, nativeLoader);
            Class<?> adapter = Class.forName("io.lumine.mythic.bukkit.BukkitAdapter", true, nativeLoader);
            var adapt = adapter.getMethod("adapt", Entity.class);
            Class<?> casterType = Class.forName("io.lumine.mythic.api.skills.SkillCaster", true, nativeLoader);
            Object nativeCaster = Proxy.newProxyInstance(nativeLoader, new Class<?>[]{casterType}, (proxy, method, args) -> {
                if (method.getName().equals("getEntity")) return adapt.invoke(null, caster);
                throw new UnsupportedOperationException(method.getName());
            });
            Class<?> metadata = Class.forName("io.lumine.mythic.core.skills.SkillMetadataImpl", true, nativeLoader);
            Class<?> meta = Class.forName("io.lumine.mythic.core.skills.placeholders.PlaceholderMeta", true, nativeLoader);
            Class<?> triggerType = Class.forName("io.lumine.mythic.api.skills.SkillTrigger", true, nativeLoader);
            Class<?> strings = Class.forName("io.lumine.mythic.api.skills.placeholders.PlaceholderString", true, nativeLoader);
            Class<?> context = Class.forName("dev.helstera.api.behavior.BehaviorContext", true, runtimeLoader);
            Class<?> modelType = Class.forName("dev.helstera.api.instance.ModelInstance", true, runtimeLoader);
            Class<?> treeType = Class.forName("dev.helstera.api.behavior.SkillTreeContext", true, runtimeLoader);
            Class<?> placeholders = Class.forName("dev.helstera.ai.skill.Placeholders", true, runtimeLoader);
            Object model = Proxy.newProxyInstance(runtimeLoader, new Class<?>[]{modelType}, (proxy, method, args) -> switch (method.getName()) {
                case "isValid" -> true;
                case "baseEntity" -> Optional.of(caster);
                case "executionAudience" -> Optional.empty();
                case "location" -> caster.getLocation();
                case "instanceId" -> -2;
                default -> throw new UnsupportedOperationException(method.getName());
            });
            int rows = 0, boundaries = 0;
            for (Scene scene : scenes) {
                if (scene.trigger() == trigger) {
                    boolean changed = !scene.name().equals("living");
                    trigger.setHealth(changed ? .6 : 3.125);
                    trigger.setCustomName(scene.name().equals("empty-name") ? "" : changed ? "Changed trigger" : "Named trigger");
                    trigger.teleport(base.clone().add(changed ? -2.33 : 0, 0, 4));
                    trigger.setRotation(changed ? 12.345f : -23.456f, changed ? -6.789f : 7.891f);
                }
                Object nativeMeta = metadata.getConstructor(triggerType, casterType, abstractEntity).newInstance(
                        triggerType.getMethod("get", String.class).invoke(null, "API"), nativeCaster,
                        scene.trigger() == null ? null : adapt.invoke(null, scene.trigger()));
                Object ctx = context.getMethod("continueExecution", modelType, LivingEntity.class, double.class,
                                double.class, long.class, String.class, String.class, Entity.class, List.class, Location.class, treeType)
                        .invoke(null, model, target, 1.0, 999.0, 0L, "trigger-audit", null, scene.trigger(), List.of(target), base, null);
                for (var entry : PLACEHOLDERS.entrySet()) for (String mode : List.of("metadata", "metadata-target", "contextless")) {
                    String token = entry.getKey();
                    String expected;
                    try {
                        expected = (String) switch (mode) {
                            case "metadata" -> strings.getMethod("get", meta).invoke(entry.getValue(), nativeMeta);
                            case "metadata-target" -> strings.getMethod("get", meta, abstractEntity)
                                    .invoke(entry.getValue(), nativeMeta, adapt.invoke(null, target));
                            default -> strings.getMethod("get").invoke(entry.getValue());
                        };
                    } catch (InvocationTargetException failure) {
                        if (scene.trigger() != null || !(failure.getCause() instanceof NullPointerException)) throw failure;
                        expected = "THREW:NullPointerException";
                    }
                    String actual = (String) placeholders.getMethod("resolve", String.class, context)
                            .invoke(null, token, mode.equals("contextless") ? null : ctx);
                    boolean boundary = !mode.equals("contextless") && (scene.trigger() == null || expected.equals("NaN"));
                    boolean matched = boundary ? token.equals(actual) : Objects.equals(expected, actual);
                    if (boundary) {
                        boundaries++;
                        double numeric = (double) placeholders.getMethod("resolveNumber", String.class, context, double.class)
                                .invoke(null, token, ctx, -999.0);
                        matched &= numeric == -999.0;
                    }
                    probe.getLogger().info("TRIGGER_PLACEHOLDER|token=" + token + "|scene=" + scene.name() + "|mode=" + mode
                            + "|native=" + expected + "|runtime=" + actual + "|boundary=" + boundary + "|matched=" + matched);
                    if (!matched) throw new IllegalStateException("Trigger placeholder differs: " + token + " " + scene.name() + " " + mode);
                    rows++;
                }
            }
            if (rows != 1020 || boundaries != 86) throw new IllegalStateException("Incomplete trigger placeholder evidence: " + rows + "/" + boundaries);
            probe.getLogger().info("TRIGGER_PLACEHOLDER_SUMMARY|comparisons=" + rows + "|tokens=34|scenes=10|modes=3|boundaries="
                    + boundaries + "|schedulerTicks=" + (Bukkit.getCurrentTick() - preparedTick) + "|passed=true");
        } finally { for (Entity entity : owned) entity.remove(); }
    }

    private static Cow cow(Location point, List<Entity> owned) {
        Cow cow = point.getWorld().spawn(point, Cow.class);
        owned.add(cow); cow.setAI(false); cow.setGravity(false); cow.setInvulnerable(true);
        return cow;
    }
}
