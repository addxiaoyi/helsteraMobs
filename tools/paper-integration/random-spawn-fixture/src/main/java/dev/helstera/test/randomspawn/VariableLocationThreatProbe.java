package dev.helstera.test.randomspawn;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Saved threat target values independently of vanilla AI and mutation recipients; observation only. */
public final class VariableLocationThreatProbe extends JavaPlugin {
    private static final List<String> SCOPES = List.of("skill", "caster", "target", "world", "global");
    private static final List<String> REGISTRIES = List.of("skill", "caster", "target", "trigger", "threat-target", "changed-target", "vanilla-target", "world", "other-world", "global");
    private final Set<Chunk> tickets = new LinkedHashSet<>();
    private int observations;
    private org.bukkit.entity.Cow aiTarget;
    private org.bukkit.entity.Cow changedTarget;
    private org.bukkit.entity.Cow vanillaTarget;
    private Object nativeThreat;
    private Object runtimeThreat;
    private record Case(String id, String arguments, String selected, String targetState, long deadline) { }
    private record Snapshot(Map<String, String> values, Map<String, Long> deadlines) { }

    private static List<Case> cases() {
        return List.of(
                sample("target", "value=@target", "@target", "first"),
                sample("target-short", "value=@T", "@target", "first"),
                sample("target-location", "value=@targetlocation", "@targetlocation", "first"),
                sample("location-alias", "value=@targetLoc", "@targetlocation", "first"),
                sample("location-short", "value=@TL", "@targetlocation", "first"),
                sample("target-changed", "value=@target", "@target", "changed"),
                sample("location-changed", "value=@targetlocation", "@targetlocation", "changed"),
                sample("target-cleared", "value=@target", "@target", "missing"),
                sample("location-cleared", "value=@targetlocation", "@targetlocation", "missing"),
                sample("target-alias-priority", "val=@origin;value=@target", "@target", "first"),
                sample("location-duplicate", "value=@target;VALUE=@targetlocation", "@targetlocation", "missing"),
                new Case("saved-location", "value=@targetlocation;save=true", "@targetlocation", "changed", 0));
    }
    private static Case sample(String id, String arguments, String selected, String targetState) {
        return new Case(id, arguments, selected, targetState, 1);
    }

    @Override public void onEnable() {
        Bukkit.getScheduler().runTaskLater(this, () -> {
            try {
                runComparison();
                getLogger().info("VLOCATION_THREAT_SUMMARY|observations=" + observations + "|passed=true");
            } catch (Throwable error) {
                getLogger().severe("VLOCATION_THREAT_FAILED|" + error);
                error.printStackTrace();
            } finally {
                for (Chunk chunk : tickets) chunk.removePluginChunkTicket(this);
            }
        }, 100);
    }

    private void runComparison() throws Exception {
        var runtime = Bukkit.getPluginManager().getPlugin("helsteraMobs");
        var mythic = Bukkit.getPluginManager().getPlugin("MythicMobs");
        if (runtime == null || mythic == null) throw new IllegalStateException("Missing plugins");
        var center = new Location(Bukkit.getWorlds().getFirst(), 0, 150, 0);
        var base = center.clone().add(110, 20, 0);
        var other = base.clone();
        other.setWorld(Bukkit.getWorlds().stream().filter(w -> w != base.getWorld()).findFirst().orElseThrow());
        for (Location point : List.of(base, base.clone().add(0, 0, 4), base.clone().add(4, 0, 0), other)) {
            Chunk chunk = point.getChunk(); chunk.getEntities();
            if (tickets.add(chunk)) chunk.addPluginChunkTicket(this);
        }
        for (String path : List.of("native-direct", "imported")) {
            try (var h = new VariablePlaceholderAudit.Harness(this, runtime, mythic, center, path.equals("imported"))) {
                h.a.teleport(new Location(base.getWorld(), 110, 170, 0, 20, 10));
                h.b.teleport(new Location(base.getWorld(), 110, 170, 4, 70, 15));
                h.c.teleport(new Location(base.getWorld(), 114, 170, 0, 120, 25));
                aiTarget = h.mob(new Location(base.getWorld(), 118, 170, 8, -25, 32));
                changedTarget = h.mob(new Location(base.getWorld(), 122, 171, 10, -65, -22));
                aiTarget.teleport(new Location(base.getWorld(), 118, 170, 8, -25, 32));
                changedTarget.teleport(new Location(base.getWorld(), 122, 171, 10, -65, -22));
                vanillaTarget = h.mob(new Location(base.getWorld(), 126, 172, 14, 55, -12));
                vanillaTarget.teleport(new Location(base.getWorld(), 126, 172, 14, 55, -12));
                nativeThreat = h.active.get(h.a).getClass().getMethod("getThreatTable").invoke(h.active.get(h.a));
                if (nativeThreat == null || !(boolean) h.active.get(h.a).getClass().getMethod("hasThreatTable").invoke(h.active.get(h.a)))
                    throw new IllegalStateException("Fixture caster must have a native threat table");
                runtimeThreat = h.runtimeType("dev.helstera.ai.ThreatTable")
                        .getConstructor(double.class, double.class, java.util.function.LongSupplier.class, java.util.function.Function.class)
                        .newInstance(0.0, 0.0, (java.util.function.LongSupplier) () -> 1000L,
                                (java.util.function.Function<java.util.UUID, Entity>) Bukkit::getEntity);
                Class<?> lookupType = h.runtimeType("dev.helstera.ai.skill.SkillExtras$ThreatLookup");
                Object lookup = java.lang.reflect.Proxy.newProxyInstance(h.runtimeLoader, new Class<?>[]{lookupType},
                        (proxy, method, args) -> (int) args[0] == h.a.getEntityId() ? runtimeThreat : null);
                h.runtimeType("dev.helstera.ai.skill.SkillExtras").getMethod("threatLookup", lookupType).invoke(null, lookup);
                var identities = new YamlConfiguration();
                for (int i = 0; i < h.mobs.size(); i++) identities.set("entity-" + i, h.mobs.get(i).getUniqueId().toString());
                identities.save(getDataFolder().toPath().resolve("identities-" + path + ".yml").toFile());
                for (String dispatch : List.of("entity", "location")) for (String scope : SCOPES)
                    for (Case sample : cases()) observe(h, path, dispatch, scope, sample, "LocationThreatAudit" + observations);
            } finally {
                Class<?> extras = Class.forName("dev.helstera.ai.skill.SkillExtras", true, runtime.getClass().getClassLoader());
                Class<?> lookup = Class.forName("dev.helstera.ai.skill.SkillExtras$ThreatLookup", true, runtime.getClass().getClassLoader());
                extras.getMethod("threatLookup", lookup).invoke(null, new Object[]{null});
            }
        }
    }

    private void observe(VariablePlaceholderAudit.Harness h, String path, String dispatch, String scope,
                         Case sample, String key) throws Exception {
        var origin = new Location(h.a.getWorld(), 130, 174, 12, 160, 35);
        var outer = new Location(h.a.getWorld(), 140, 178, 16, 210, 45);
        Entity trigger = h.c;
        prepareThreat(h);
        h.a.setTarget(vanillaTarget);
        if (h.a.getTarget() != vanillaTarget) throw new IllegalStateException("Vanilla target not installed");
        Object tree = h.newTree(trigger, h.b, origin);
        Object seedContext = h.context(h.a, h.b, trigger, origin, tree);
        Object context = dispatch.equals("location")
                ? h.contextType.getMethod("withLocationTarget", Location.class).invoke(seedContext, outer) : seedContext;
        Object meta = h.meta(h.a, trigger);
        Class<?> nativeLocation = h.nativeType("io.lumine.mythic.api.adapters.AbstractLocation");
        var adaptLocation = h.nativeType("io.lumine.mythic.bukkit.BukkitAdapter").getMethod("adapt", Location.class);
        h.metadataType.getMethod("setOrigin", nativeLocation).invoke(meta, adaptLocation.invoke(null, origin));
        Map<String, Object> registries = nativeRegistries(h, meta);
        for (int i = 0; i < REGISTRIES.size(); i++) {
            String candidate = REGISTRIES.get(i);
            registries.get(candidate).getClass().getMethod("putInt", String.class, int.class)
                    .invoke(registries.get(candidate), key, 10 + i);
            Object nativeSeed = registries.get(candidate).getClass().getMethod("get", String.class).invoke(registries.get(candidate), key);
            nativeSeed.getClass().getMethod("setExpirationTime", long.class).invoke(nativeSeed, 1L);
            Object seed = candidate.equals("vanilla-target") ? h.context(vanillaTarget, vanillaTarget, vanillaTarget, vanillaTarget.getLocation(), tree)
                    : candidate.equals("threat-target") ? h.context(aiTarget, aiTarget, aiTarget, aiTarget.getLocation(), tree)
                    : candidate.equals("changed-target") ? h.context(changedTarget, changedTarget, changedTarget, changedTarget.getLocation(), tree)
                    : candidate.equals("trigger") ? h.context(h.c, h.c, h.c, h.c.getLocation(), tree)
                    : candidate.equals("other-world") ? h.context(h.d, h.d, h.d, h.d.getLocation(), tree) : seedContext;
            String seedScope = List.of("trigger", "threat-target", "changed-target", "vanilla-target").contains(candidate) ? "caster" : candidate.equals("other-world") ? "world" : candidate;
            runRuntime(h, "set-variable var=" + key + " scope=" + seedScope + " type=INTEGER value=" + (10 + i), seed);
        }
        Snapshot nativeBefore = nativeSnapshot(h, registries, key, true);
        Snapshot runtimeBefore = runtimeSnapshot(h, tree, key);
        if (!nativeBefore.equals(runtimeBefore)) throw new IllegalStateException("Seed mismatch " + sample.id());
        String source = "setvariablelocation{var=" + scope + "." + key
                + (sample.arguments().isEmpty() ? "" : ";" + sample.arguments()) + "}";
        String beforeBindingTarget = nativeCurrent(h);
        if (!beforeBindingTarget.equals(runtimeCurrent())) throw new IllegalStateException("Prepared selection mismatch");
        Object action = h.nativeType("io.lumine.mythic.core.skills.mechanics.VariableSetLocationMechanic")
                .getConstructor(h.executorType, java.io.File.class, String.class, h.configType)
                .newInstance(h.nativeSkills, h.source.toFile(), source, h.configImpl.getConstructor(String.class).newInstance(source));
        var durationField = h.nativeType("io.lumine.mythic.core.skills.variables.VariableMechanic").getDeclaredField("duration");
        durationField.setAccessible(true);
        long nativeDuration = durationField.getLong(action);
        String mapped = path.equals("imported") ? importAction(h, source) : source;
        int warningsBefore = ((List<?>) h.skills.getClass().getMethod("warnings").invoke(h.skills)).size();
        String bound = (String) h.skills.getClass().getMethod("bindAction", String.class).invoke(h.skills, mapped);
        if (bound == null) throw new IllegalStateException("Runtime action did not bind: " + mapped);
        if (sample.targetState().equals("changed")) {
            nativeThreat.getClass().getMethod("targetThreateningEntity", h.abstractEntity)
                    .invoke(nativeThreat, h.adapt.invoke(null, changedTarget));
            runtimeThreat.getClass().getMethod("selectTarget", java.util.UUID.class).invoke(runtimeThreat, changedTarget.getUniqueId());
        } else if (sample.targetState().equals("missing")) {
            nativeThreat.getClass().getMethod("dropCombat").invoke(nativeThreat);
            runtimeThreat.getClass().getMethod("clear").invoke(runtimeThreat);
        }
        // dropCombat changes vanilla AI state; reinstall a distinct vanilla target to prove no fallback.
        h.a.setTarget(vanillaTarget);
        String vanillaTargetBefore = h.a.getTarget().getUniqueId().toString();
        String dispatchTarget = nativeCurrent(h);
        Map<String, Double> nativeThreatBefore = threatScores(nativeThreat, "asMap");
        Map<String, Double> runtimeThreatBefore = threatScores(runtimeThreat, "snapshot");
        String runtimeTargetBefore = runtimeCurrent();
        if (!nativeThreatBefore.equals(runtimeThreatBefore) || !dispatchTarget.equals(runtimeTargetBefore))
            throw new IllegalStateException("Threat state mismatch before dispatch");
        String nativeResult;
        try {
            nativeResult = dispatch.equals("entity") ? action.getClass().getMethod("castAtEntity", h.skillMetadata, h.abstractEntity)
                    .invoke(action, meta, h.adapt.invoke(null, h.b)).toString()
                    : action.getClass().getMethod("castAtLocation", h.skillMetadata, nativeLocation)
                    .invoke(action, meta, adaptLocation.invoke(null, outer)).toString();
        } catch (InvocationTargetException error) { nativeResult = "exception:" + error.getCause().getClass().getSimpleName(); }
        // asMap retains raw expiration data; get removes entries whose absolute deadline is already past.
        Map<String, Double> nativeThreatAfter = threatScores(nativeThreat, "asMap");
        String nativeTargetAfter = nativeCurrent(h);
        Snapshot nativeStoredAfter = nativeSnapshot(h, registries, key, false);
        Snapshot nativeAfter = nativeSnapshot(h, registries, key, true);
        if (!(boolean) h.registry.getClass().getMethod("runAction", String.class, h.contextType)
                .invoke(h.registry, bound, context)) throw new IllegalStateException("Runtime action did not dispatch");
        var allWarnings = (List<?>) h.skills.getClass().getMethod("warnings").invoke(h.skills);
        List<String> diagnostics = allWarnings.subList(warningsBefore, allWarnings.size()).stream().map(String::valueOf).toList();
        Map<String, Double> runtimeThreatAfter = threatScores(runtimeThreat, "snapshot");
        String runtimeTargetAfter = runtimeCurrent();
        Snapshot runtimeAfter = runtimeSnapshot(h, tree, key);
        boolean unavailableRegistry = dispatch.equals("location") && scope.equals("target");
        boolean missingEntityTarget = sample.targetState().equals("missing") && sample.selected().equals("@target");
        boolean writes = !unavailableRegistry && !missingEntityTarget;
        String expectedResult = unavailableRegistry ? "INVALID_CONFIG" : missingEntityTarget ? "exception:NullPointerException" : "SUCCESS";
        Map<String, String> expectedStoredValues = new TreeMap<>(nativeBefore.values());
        Map<String, Long> expectedStoredDeadlines = new TreeMap<>(nativeBefore.deadlines());
        Map<String, String> positions = Map.of("caster", location(h.a.getLocation()), "recipient", location(h.b.getLocation()),
                "trigger", location(h.c.getLocation()), "threat-target", location(aiTarget.getLocation()),
                "changed-target", location(changedTarget.getLocation()), "vanilla-target", location(vanillaTarget.getLocation()), "origin", location(origin), "outer", location(outer));
        if (writes) {
            String role = sample.targetState().equals("missing") ? "origin"
                    : sample.targetState().equals("changed") ? "changed-target" : "threat-target";
            expectedStoredValues.put(scope + "/" + key, positions.get(role));
            expectedStoredDeadlines.put(scope + "/" + key, sample.deadline());
        }
        var expectedValues = new TreeMap<>(expectedStoredValues);
        var expectedDeadlines = new TreeMap<>(expectedStoredDeadlines);
        if (writes && sample.deadline() > 1) {
            expectedValues.remove(scope + "/" + key); expectedDeadlines.remove(scope + "/" + key);
        }
        boolean matched = nativeResult.equals(expectedResult) && nativeDuration == sample.deadline()
                && nativeStoredAfter.equals(new Snapshot(expectedStoredValues, expectedStoredDeadlines))
                && nativeAfter.equals(new Snapshot(expectedValues, expectedDeadlines))
                && runtimeAfter.equals(new Snapshot(expectedValues, expectedDeadlines)) && diagnostics.isEmpty()
                && nativeThreatBefore.equals(nativeThreatAfter) && runtimeThreatBefore.equals(runtimeThreatAfter)
                && dispatchTarget.equals(nativeTargetAfter) && runtimeTargetBefore.equals(runtimeTargetAfter)
                && h.a.getTarget() == vanillaTarget;
        var row = new YamlConfiguration();
        row.set("case", sample.id()); row.set("path", path); row.set("dispatch", dispatch); row.set("scope", scope); row.set("key", key);
        row.set("source", source); row.set("mapped", mapped); row.set("bindingSource", mapped);
        row.set("bindingDiagnostics", diagnostics); row.set("nativeResult", nativeResult); row.set("runtimeResult", "dispatched");
        row.set("selected", sample.selected()); row.set("targetState", sample.targetState());
        row.set("targetBeforeBinding", beforeBindingTarget); row.set("targetAtDispatch", dispatchTarget);
        row.set("nativeHasThreatTable", true);
        row.set("nativePreparation", "dropCombat;threatGain(first,100);threatGain(second,105)");
        row.set("runtimePreparation", "clear;addThreat(first,100);addThreat(second,105);selectTarget(first)");
        row.set("nativeTargetAfter", nativeTargetAfter); row.set("runtimeTargetBefore", runtimeTargetBefore);
        row.set("runtimeTargetAfter", runtimeTargetAfter);
        row.set("nativeThreatBefore", nativeThreatBefore); row.set("nativeThreatAfter", nativeThreatAfter);
        row.set("runtimeThreatBefore", runtimeThreatBefore); row.set("runtimeThreatAfter", runtimeThreatAfter);
        row.set("vanillaTargetBefore", vanillaTargetBefore);
        row.set("vanillaTargetAfter", h.a.getTarget().getUniqueId().toString());
        row.set("positions", positions); row.set("nativeDurationMillis", nativeDuration);
        saveSnapshot(row, "nativeBefore", nativeBefore); saveSnapshot(row, "runtimeBefore", runtimeBefore);
        saveSnapshot(row, "nativeStoredAfter", nativeStoredAfter); saveSnapshot(row, "nativeAfter", nativeAfter);
        saveSnapshot(row, "runtimeAfter", runtimeAfter);
        row.set("matched", matched);
        var directory = getDataFolder().toPath().resolve("observations"); Files.createDirectories(directory);
        row.save(directory.resolve(String.format(Locale.ROOT, "%04d.yml", observations)).toFile());
        getLogger().info("VLOCATION_THREAT_ROW|case=" + sample.id() + "|scope=" + scope + "|dispatch=" + dispatch + "|path=" + path + "|matched=" + matched);
        observations++;
        if (!matched) throw new IllegalStateException("Location mismatch " + source + " " + dispatch + " " + nativeResult);
        for (String candidate : REGISTRIES) {
            registries.get(candidate).getClass().getMethod("remove", String.class).invoke(registries.get(candidate), key);
            Object cleanup = candidate.equals("vanilla-target") ? h.context(vanillaTarget, vanillaTarget, vanillaTarget, vanillaTarget.getLocation(), tree)
                    : candidate.equals("threat-target") ? h.context(aiTarget, aiTarget, aiTarget, aiTarget.getLocation(), tree)
                    : candidate.equals("changed-target") ? h.context(changedTarget, changedTarget, changedTarget, changedTarget.getLocation(), tree)
                    : candidate.equals("trigger") ? h.context(h.c, h.c, h.c, h.c.getLocation(), tree)
                    : candidate.equals("other-world") ? h.context(h.d, h.d, h.d, h.d.getLocation(), tree) : seedContext;
            runRuntime(h, "variable-unset var=" + key + " scope=" + (List.of("trigger", "threat-target", "changed-target", "vanilla-target").contains(candidate) ? "caster" : candidate.equals("other-world") ? "world" : candidate), cleanup);
        }
    }

    private void prepareThreat(VariablePlaceholderAudit.Harness h) throws Exception {
        nativeThreat.getClass().getMethod("dropCombat").invoke(nativeThreat);
        nativeThreat.getClass().getMethod("threatGain", h.abstractEntity, double.class)
                .invoke(nativeThreat, h.adapt.invoke(null, aiTarget), 100.0);
        nativeThreat.getClass().getMethod("threatGain", h.abstractEntity, double.class)
                .invoke(nativeThreat, h.adapt.invoke(null, changedTarget), 105.0);
        runtimeThreat.getClass().getMethod("clear").invoke(runtimeThreat);
        runtimeThreat.getClass().getMethod("addThreat", java.util.UUID.class, double.class)
                .invoke(runtimeThreat, aiTarget.getUniqueId(), 100.0);
        runtimeThreat.getClass().getMethod("addThreat", java.util.UUID.class, double.class)
                .invoke(runtimeThreat, changedTarget.getUniqueId(), 105.0);
        runtimeThreat.getClass().getMethod("selectTarget", java.util.UUID.class)
                .invoke(runtimeThreat, aiTarget.getUniqueId());
        if (!nativeCurrent(h).equals(aiTarget.getUniqueId().toString()))
            throw new IllegalStateException("Native 10 percent threshold did not retain the first target");
    }
    private String nativeCurrent(VariablePlaceholderAudit.Harness h) throws Exception {
        Object selected = nativeThreat.getClass().getMethod("getTopThreatHolder").invoke(nativeThreat);
        return selected == null ? "none" : h.abstractEntity.getMethod("getUniqueId").invoke(selected).toString();
    }
    private String runtimeCurrent() throws Exception {
        Object selected = runtimeThreat.getClass().getMethod("currentTarget").invoke(runtimeThreat);
        return selected == null ? "none" : selected.toString();
    }
    private static Map<String, Double> threatScores(Object table, String method) throws Exception {
        Map<?, ?> values = (Map<?, ?>) table.getClass().getMethod(method).invoke(table);
        var snapshot = new TreeMap<String, Double>();
        for (var entry : values.entrySet()) snapshot.put(entry.getKey().toString(), ((Number) entry.getValue()).doubleValue());
        return snapshot;
    }

    private static void saveSnapshot(YamlConfiguration row, String name, Snapshot snapshot) {
        row.set(name, snapshot.values()); row.set(name + "Deadlines", snapshot.deadlines());
    }
    private String importAction(VariablePlaceholderAudit.Harness h, String source) throws Exception {
        var yaml = new YamlConfiguration(); yaml.set("write.Skills", List.of(source)); yaml.save(h.source.toFile());
        @SuppressWarnings("unchecked") var plans = (List<Map<String, Object>>) h.importer.getClass()
                .getMethod("convert", java.nio.file.Path.class).invoke(h.importer, h.source);
        var plan = plans.stream().filter(p -> "skills.yml".equals(p.get("target-file"))).findFirst().orElseThrow();
        yaml.loadFromString(plan.get("content").toString());
        List<String> lines = yaml.getStringList("skills.write.on-decision");
        if (lines.size() != 1 || plans.stream().anyMatch(p -> "WARN".equals(p.get("severity"))))
            throw new IllegalStateException("Location did not import: " + source);
        return lines.getFirst();
    }
    private List<String> runRuntime(VariablePlaceholderAudit.Harness h, String source, Object context) throws Exception {
        int count = ((List<?>) h.skills.getClass().getMethod("warnings").invoke(h.skills)).size();
        String bound = (String) h.skills.getClass().getMethod("bindAction", String.class).invoke(h.skills, source);
        if (bound == null || !(boolean) h.registry.getClass().getMethod("runAction", String.class, h.contextType)
                .invoke(h.registry, bound, context)) throw new IllegalStateException("Runtime location did not dispatch: " + source);
        var warnings = (List<?>) h.skills.getClass().getMethod("warnings").invoke(h.skills);
        return warnings.subList(count, warnings.size()).stream().map(String::valueOf).toList();
    }
    private Map<String, Object> nativeRegistries(VariablePlaceholderAudit.Harness h, Object meta) throws Exception {
        var result = new LinkedHashMap<String, Object>();
        Class<?> scopeType = h.nativeType("io.lumine.mythic.core.skills.variables.VariableScope");
        Object variables = h.mythic.getClass().getMethod("getVariableManager").invoke(h.mythic);
        for (String scope : REGISTRIES) {
            Entity target = scope.equals("vanilla-target") ? vanillaTarget : scope.equals("threat-target") ? aiTarget : scope.equals("changed-target") ? changedTarget
                    : scope.equals("trigger") ? h.c : h.b;
            String effective = List.of("trigger", "threat-target", "changed-target", "vanilla-target").contains(scope) ? "target" : scope.equals("other-world") ? "world" : scope;
            Object registryMeta = scope.equals("other-world") ? h.meta(h.d, h.d) : meta;
            result.put(scope, variables.getClass().getMethod("getRegistry", scopeType, h.placeholderMeta, h.abstractEntity)
                    .invoke(variables, scopeType.getField(effective.toUpperCase(Locale.ROOT)).get(null), registryMeta, h.adapt.invoke(null, target)));
        }
        return result;
    }
    private Snapshot nativeSnapshot(VariablePlaceholderAudit.Harness h, Map<String, Object> registries, String key, boolean live) throws Exception {
        var result = new TreeMap<String, String>(); var deadlines = new TreeMap<String, Long>();
        Class<?> nativeLocation = h.nativeType("io.lumine.mythic.api.adapters.AbstractLocation");
        for (var registry : registries.entrySet()) {
            var values = (Map<?, ?>) registry.getValue().getClass().getMethod("asMap").invoke(registry.getValue());
            for (var entry : values.entrySet()) if (entry.getKey().toString().contains(key)) {
                Object variable = live ? registry.getValue().getClass().getMethod("get", String.class)
                        .invoke(registry.getValue(), entry.getKey().toString()) : entry.getValue();
                if (variable == null) continue;
                Object value = variable.getClass().getMethod("get").invoke(variable);
                String text = nativeLocation.isInstance(value) ? location((Location) h.nativeType("io.lumine.mythic.bukkit.BukkitAdapter")
                        .getMethod("adapt", nativeLocation).invoke(null, value)) : value.getClass().getSimpleName() + ":" + value;
                result.put(registry.getKey() + "/" + entry.getKey(), text);
                deadlines.put(registry.getKey() + "/" + entry.getKey(), (long) variable.getClass().getMethod("getExpirationTime").invoke(variable));
            }
        }
        return new Snapshot(result, deadlines);
    }
    private Snapshot runtimeSnapshot(VariablePlaceholderAudit.Harness h, Object tree, String key) throws Exception {
        var result = new TreeMap<String, String>(); var deadlines = new TreeMap<String, Long>();
        for (String scope : REGISTRIES) {
            Object snapshot;
            if (scope.equals("skill")) snapshot = h.treeType.getMethod("snapshot").invoke(tree);
            else {
                String registryKey = switch (scope) {
                    case "caster" -> "entity:" + h.a.getUniqueId(); case "target" -> "entity:" + h.b.getUniqueId();
                    case "trigger" -> "entity:" + h.c.getUniqueId();
                    case "vanilla-target" -> "entity:" + vanillaTarget.getUniqueId();
                    case "threat-target" -> "entity:" + aiTarget.getUniqueId();
                    case "changed-target" -> "entity:" + changedTarget.getUniqueId(); case "world" -> "world:" + h.a.getWorld().getUID();
                    case "other-world" -> "world:" + h.d.getWorld().getUID(); default -> "global";
                };
                snapshot = h.runtimeType("dev.helstera.ai.skill.SkillExtras").getMethod("scopedVariableSnapshot", String.class).invoke(null, registryKey);
            }
            for (String kind : List.of("variables", "locations")) {
                var values = (Map<?, ?>) snapshot.getClass().getMethod(kind).invoke(snapshot);
                String times = kind.equals("variables") ? scope.equals("skill") ? "variableExpirations" : "variableRetention"
                        : scope.equals("skill") ? "locationExpirations" : "locationRetention";
                var retention = (Map<?, ?>) snapshot.getClass().getMethod(times).invoke(snapshot);
                for (var entry : values.entrySet()) if (entry.getKey().toString().contains(key)) {
                    Object value = entry.getValue();
                    String text = kind.equals("variables") ? value.getClass().getSimpleName() + ":" + value
                            : "Location:" + value.getClass().getMethod("world").invoke(value) + ","
                            + value.getClass().getMethod("x").invoke(value) + "," + value.getClass().getMethod("y").invoke(value) + ","
                            + value.getClass().getMethod("z").invoke(value) + "," + value.getClass().getMethod("yaw").invoke(value) + ","
                            + value.getClass().getMethod("pitch").invoke(value);
                    if (result.putIfAbsent(scope + "/" + entry.getKey(), text) != null)
                        throw new IllegalStateException("Scalar and location coexist: " + scope + "/" + entry.getKey());
                    Object deadline = scope.equals("skill") ? h.treeType.getMethod(kind.equals("variables")
                            ? "variableExpiration" : "locationExpiration", String.class).invoke(tree, entry.getKey().toString())
                            : retention.get(entry.getKey());
                    deadlines.put(scope + "/" + entry.getKey(), deadline == null ? 0 : ((Number) deadline).longValue());
                }
            }
        }
        return new Snapshot(result, deadlines);
    }
    private static String location(Location point) {
        return "Location:" + point.getWorld().getName() + "," + point.getX() + "," + point.getY() + "," + point.getZ()
                + "," + point.getYaw() + "," + point.getPitch();
    }
}
