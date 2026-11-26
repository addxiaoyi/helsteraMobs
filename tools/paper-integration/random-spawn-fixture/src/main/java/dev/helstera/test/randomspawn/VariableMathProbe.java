package dev.helstera.test.randomspawn;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Native equation observations, including intentional non-finite rejection differences. */
public final class VariableMathProbe extends JavaPlugin {
    private static final List<String> SCOPES = List.of("skill", "caster", "target", "world", "global");
    private static final List<String> REGISTRIES = List.of("skill", "caster", "target", "trigger", "world", "other-world", "global");
    private final Set<Chunk> tickets = new LinkedHashSet<>();
    private int observations;
    private record Case(String id, String arguments, String equation, String type, String nativeValue,
                        String runtimeValue, String nativeResult, long durationTicks) { }

    private static List<Case> cases() {
        var cases = new ArrayList<Case>();
        cases.add(integer("default", "", "x", "7", 0));
        cases.add(integer("legacy-options", "op=*;value=3", "x", "7", 0));
        cases.add(integer("equation", "equation=x*2+1", "x*2+1", "15", 0));
        cases.add(integer("eq", "eq=x/2", "x/2", "3", 0));
        cases.add(integer("e", "e=x+2", "x+2", "9", 0));
        cases.add(integer("quoted", "equation=\"x + 2\"", "x + 2", "9", 0));
        cases.add(integer("single-quoted", "equation='x+2'", "x+2", "9", 0));
        cases.add(integer("alias-priority", "equation=x+1;eq=x+2;e=x+3", "x+1", "8", 0));
        cases.add(integer("duplicate", "equation=bad;EQUATION=x+3", "x+3", "10", 0));
        cases.add(integer("parentheses", "equation=(x+1)*2", "(x+1)*2", "16", 0));
        cases.add(integer("power", "equation=2^3^2", "2^3^2", "512", 0));
        cases.add(integer("unary", "equation=-x/2", "-x/2", "-3", 0));
        cases.add(new Case("float", "equation=x/2", "x/2", "FLOAT", "Float:3.5", "Float:3.5", "SUCCESS", 0));
        cases.add(integer("functions", "equation=max(2,min(x,4))", "max(2,min(x,4))", "4", 0));
        cases.add(new Case("missing", "equation=x+1", "x+1", "MISSING", null, null, "INVALID_CONFIG", 0));
        cases.add(new Case("double", "equation=x+1", "x+1", "DOUBLE", "Double:7.0", "Double:7.0", "INVALID_CONFIG", 0));
        cases.add(new Case("string", "equation=x+1", "x+1", "STRING", "String:7", "String:7", "INVALID_CONFIG", 0));
        cases.add(integer("duration-preserve", "equation=x+1;save=false", "x+1", "8", 0));
        cases.add(integer("duration-positive", "equation=x+1;duration=1200", "x+1", "8", 1200));
        cases.add(integer("duration-zero-save", "equation=x+1;duration=0;save=true", "x+1", "8", 0));
        cases.add(integer("duration-negative", "equation=x+1;duration=-4", "x+1", "8", -4));
        cases.add(integer("duration-e-numeric", "e=1200", "1200", "1200", 1200));
        cases.add(integer("duration-alias-priority", "equation=x+1;duration=1200;d=2400", "x+1", "8", 1200));
        cases.add(integer("duration-invalid-first", "equation=x+1;duration=bad;d=1200", "x+1", "8", 0));
        cases.add(integer("duration-duplicate", "equation=x+1;duration=1200;DURATION=0", "x+1", "8", 0));
        cases.add(new Case("division-zero", "equation=x/0", "x/0", "INTEGER", "Integer:7", "Integer:7", "exception:ArithmeticException", 0));
        cases.add(new Case("nan-integer", "equation=sqrt(-1)", "sqrt(-1)", "INTEGER", "Integer:0", "Integer:7", "SUCCESS", 0));
        cases.add(new Case("nan-float", "equation=sqrt(-1)", "sqrt(-1)", "FLOAT", "Float:NaN", "Float:7.0", "SUCCESS", 0));
        cases.add(new Case("overflow-float", "equation=10^1000", "10^1000", "FLOAT", "Float:Infinity", "Float:7.0", "SUCCESS", 0));
        cases.add(integer("finite-int-saturation", "equation=10^12", "10^12", "2147483647", 0));
        return List.copyOf(cases);
    }
    private static Case integer(String id, String arguments, String equation, String result, long ticks) {
        return new Case(id, arguments, equation, "INTEGER", "Integer:" + result, "Integer:" + result, "SUCCESS", ticks);
    }

    @Override public void onEnable() {
        Bukkit.getScheduler().runTaskLater(this, () -> {
            try {
                runComparison();
                getLogger().info("VMATH_SUMMARY|observations=" + observations + "|passed=true");
            } catch (Throwable error) {
                getLogger().severe("VMATH_FAILED|" + error);
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
                var identities = new YamlConfiguration();
                for (int i = 0; i < h.mobs.size(); i++) identities.set("entity-" + i, h.mobs.get(i).getUniqueId().toString());
                identities.save(getDataFolder().toPath().resolve("identities-" + path + ".yml").toFile());
                for (String scope : SCOPES) for (Case sample : cases()) observe(h, path, scope, sample, "MathAudit" + observations);
            }
        }
    }
    private void observe(VariablePlaceholderAudit.Harness h, String path, String scope, Case sample, String key) throws Exception {
        Object tree = h.newTree(h.c, h.b, h.b.getLocation());
        Object context = h.context(h.a, h.b, h.c, h.b.getLocation(), tree);
        Object meta = h.meta(h.a, h.c);
        Map<String, Object> registries = nativeRegistries(h, meta);
        Class<?> variableType = h.nativeType("io.lumine.mythic.core.skills.variables.Variable");
        for (int i = 0; i < REGISTRIES.size(); i++) {
            String candidate = REGISTRIES.get(i);
            if (candidate.equals(scope) && sample.type().equals("MISSING")) continue;
            String type = candidate.equals(scope) ? sample.type() : "INTEGER";
            String value = candidate.equals(scope) ? "7" : Integer.toString(10 + i);
            Object variable = switch (type) {
                case "FLOAT" -> h.nativeType("io.lumine.mythic.core.skills.variables.types.FloatVariable").getConstructor(float.class).newInstance(Float.parseFloat(value));
                case "DOUBLE" -> h.nativeType("io.lumine.mythic.core.skills.variables.types.DoubleVariable").getConstructor(double.class).newInstance(Double.parseDouble(value));
                case "STRING" -> h.nativeType("io.lumine.mythic.core.skills.variables.types.StringVariable").getConstructor(String.class).newInstance(value);
                default -> h.nativeType("io.lumine.mythic.core.skills.variables.types.IntegerVariable").getConstructor(int.class).newInstance(Integer.parseInt(value));
            };
            registries.get(candidate).getClass().getMethod("put", String.class, variableType).invoke(registries.get(candidate), key, variable);
            Object seedContext = candidate.equals("trigger") ? h.context(h.c, h.c, h.c, h.c.getLocation(), tree)
                    : candidate.equals("other-world") ? h.context(h.d, h.d, h.d, h.d.getLocation(), tree) : context;
            String seedScope = candidate.equals("trigger") ? "caster" : candidate.equals("other-world") ? "world" : candidate;
            runRuntime(h, "set-variable var=" + key + " scope=" + seedScope + " type=" + type + " value=" + value
                    + (candidate.equals(scope) ? " duration=2400" : ""), seedContext);
            if (candidate.equals(scope)) variableType.getMethod("setExpirationTime", long.class)
                    .invoke(variable, runtimeExpiration(h, tree, scope, key));
        }
        Map<String, String> nativeBefore = nativeSnapshot(h, registries, key);
        Map<String, String> runtimeBefore = runtimeSnapshot(h, tree, key);
        if (!nativeBefore.equals(runtimeBefore)) throw new IllegalStateException("Seed mismatch " + sample.id());
        long nativeBeforeExpiration = nativeExpiration(h, registries.get(scope), key);
        long runtimeBeforeExpiration = runtimeExpiration(h, tree, scope, key);
        String source = "variablemath{var=" + scope + "." + key + (sample.arguments().isEmpty() ? "" : ";" + sample.arguments()) + "}";
        Object action = h.nativeType("io.lumine.mythic.core.skills.mechanics.VariableMathMechanic")
                .getConstructor(h.executorType, java.io.File.class, String.class, h.configType)
                .newInstance(h.nativeSkills, h.source.toFile(), source, h.configImpl.getConstructor(String.class).newInstance(source));
        var equationField = action.getClass().getDeclaredField("equation"); equationField.setAccessible(true);
        String resolvedEquation = (String) h.strings.getMethod("get", h.placeholderMeta, h.abstractEntity)
                .invoke(equationField.get(action), meta, h.adapt.invoke(null, h.b));
        var durationField = h.nativeType("io.lumine.mythic.core.skills.variables.VariableMechanic").getDeclaredField("duration");
        durationField.setAccessible(true);
        long nativeDuration = durationField.getLong(action);
        String nativeResult;
        long nativeStart = System.currentTimeMillis();
        try {
            nativeResult = action.getClass().getMethod("castAtEntity", h.skillMetadata, h.abstractEntity)
                    .invoke(action, meta, h.adapt.invoke(null, h.b)).toString();
        } catch (InvocationTargetException error) { nativeResult = "exception:" + error.getCause().getClass().getSimpleName(); }
        long nativeEnd = System.currentTimeMillis();
        String mapped = path.equals("imported") ? importAction(h, source) : source;
        long runtimeStart = System.currentTimeMillis();
        List<String> diagnostics = runRuntime(h, mapped, context);
        long runtimeEnd = System.currentTimeMillis();
        Map<String, String> nativeAfter = nativeSnapshot(h, registries, key);
        Map<String, String> runtimeAfter = runtimeSnapshot(h, tree, key);
        var expectedNative = new TreeMap<>(nativeBefore);
        var expectedRuntime = new TreeMap<>(runtimeBefore);
        if (sample.nativeValue() != null) expectedNative.put(scope + "/" + key, sample.nativeValue());
        if (sample.runtimeValue() != null) expectedRuntime.put(scope + "/" + key, sample.runtimeValue());
        long nativeAfterExpiration = nativeExpiration(h, registries.get(scope), key);
        long runtimeAfterExpiration = runtimeExpiration(h, tree, scope, key);
        boolean matched = nativeResult.equals(sample.nativeResult()) && resolvedEquation.equals(sample.equation())
                && nativeAfter.equals(expectedNative) && runtimeAfter.equals(expectedRuntime)
                && nativeBeforeExpiration == runtimeBeforeExpiration
                && expirationMatches(nativeBeforeExpiration, nativeAfterExpiration, sample.durationTicks(), nativeStart, nativeEnd)
                && expirationMatches(runtimeBeforeExpiration, runtimeAfterExpiration, sample.durationTicks(), runtimeStart, runtimeEnd);
        var row = new YamlConfiguration();
        row.set("case", sample.id()); row.set("path", path); row.set("scope", scope); row.set("key", key);
        row.set("source", source); row.set("mapped", mapped); row.set("bindingSource", mapped);
        row.set("bindingDiagnostics", diagnostics); row.set("nativeResult", nativeResult); row.set("runtimeResult", "dispatched");
        row.set("resolvedEquation", resolvedEquation); row.set("nativeDurationMillis", nativeDuration);
        row.set("nativeBefore", nativeBefore); row.set("runtimeBefore", runtimeBefore);
        row.set("nativeAfter", nativeAfter); row.set("runtimeAfter", runtimeAfter);
        row.set("expectedNativeAfter", expectedNative); row.set("expectedRuntimeAfter", expectedRuntime);
        row.set("nativeBeforeExpiration", nativeBeforeExpiration); row.set("runtimeBeforeExpiration", runtimeBeforeExpiration);
        row.set("nativeAfterExpiration", nativeAfterExpiration); row.set("runtimeAfterExpiration", runtimeAfterExpiration);
        row.set("nativeStart", nativeStart); row.set("nativeEnd", nativeEnd); row.set("runtimeStart", runtimeStart); row.set("runtimeEnd", runtimeEnd);
        row.set("sameValue", nativeAfter.equals(runtimeAfter)); row.set("matched", matched);
        var directory = getDataFolder().toPath().resolve("observations"); Files.createDirectories(directory);
        row.save(directory.resolve(String.format(Locale.ROOT, "%04d.yml", observations)).toFile());
        getLogger().info("VMATH_ROW|case=" + sample.id() + "|scope=" + scope + "|path=" + path + "|matched=" + matched);
        observations++;
        if (!matched) throw new IllegalStateException("Equation mismatch " + source);
        for (String candidate : REGISTRIES) {
            registries.get(candidate).getClass().getMethod("remove", String.class).invoke(registries.get(candidate), key);
            Object cleanup = candidate.equals("trigger") ? h.context(h.c, h.c, h.c, h.c.getLocation(), tree)
                    : candidate.equals("other-world") ? h.context(h.d, h.d, h.d, h.d.getLocation(), tree) : context;
            runRuntime(h, "variable-unset var=" + key + " scope=" + (candidate.equals("trigger") ? "caster" : candidate.equals("other-world") ? "world" : candidate), cleanup);
        }
    }
    private static boolean expirationMatches(long before, long after, long ticks, long start, long end) {
        return ticks > 0 ? after >= start + ticks * 50 && after <= end + ticks * 50 : after == before;
    }
    private String importAction(VariablePlaceholderAudit.Harness h, String source) throws Exception {
        var yaml = new YamlConfiguration(); yaml.set("write.Skills", List.of(source)); yaml.save(h.source.toFile());
        @SuppressWarnings("unchecked") var plans = (List<Map<String, Object>>) h.importer.getClass()
                .getMethod("convert", java.nio.file.Path.class).invoke(h.importer, h.source);
        var plan = plans.stream().filter(p -> "skills.yml".equals(p.get("target-file"))).findFirst().orElseThrow();
        yaml.loadFromString(plan.get("content").toString());
        List<String> lines = yaml.getStringList("skills.write.on-decision");
        if (lines.size() != 1 || plans.stream().anyMatch(p -> "WARN".equals(p.get("severity"))))
            throw new IllegalStateException("Equation did not import: " + source);
        return lines.getFirst();
    }
    private List<String> runRuntime(VariablePlaceholderAudit.Harness h, String source, Object context) throws Exception {
        int count = ((List<?>) h.skills.getClass().getMethod("warnings").invoke(h.skills)).size();
        String bound = (String) h.skills.getClass().getMethod("bindAction", String.class).invoke(h.skills, source);
        if (bound == null || !(boolean) h.registry.getClass().getMethod("runAction", String.class, h.contextType)
                .invoke(h.registry, bound, context)) throw new IllegalStateException("Runtime equation did not dispatch: " + source);
        var warnings = (List<?>) h.skills.getClass().getMethod("warnings").invoke(h.skills);
        return warnings.subList(count, warnings.size()).stream().map(String::valueOf).toList();
    }
    private Map<String, Object> nativeRegistries(VariablePlaceholderAudit.Harness h, Object meta) throws Exception {
        var result = new LinkedHashMap<String, Object>();
        Class<?> scopeType = h.nativeType("io.lumine.mythic.core.skills.variables.VariableScope");
        Object variables = h.mythic.getClass().getMethod("getVariableManager").invoke(h.mythic);
        for (String scope : REGISTRIES) {
            Entity target = scope.equals("trigger") ? h.c : h.b;
            String effective = scope.equals("trigger") ? "target" : scope.equals("other-world") ? "world" : scope;
            Object registryMeta = scope.equals("other-world") ? h.meta(h.d, h.d) : meta;
            result.put(scope, variables.getClass().getMethod("getRegistry", scopeType, h.placeholderMeta, h.abstractEntity)
                    .invoke(variables, scopeType.getField(effective.toUpperCase(Locale.ROOT)).get(null), registryMeta, h.adapt.invoke(null, target)));
        }
        return result;
    }
    private Map<String, String> nativeSnapshot(VariablePlaceholderAudit.Harness h, Map<String, Object> registries, String key) throws Exception {
        var result = new TreeMap<String, String>();
        for (var registry : registries.entrySet()) {
            var values = (Map<?, ?>) registry.getValue().getClass().getMethod("asMap").invoke(registry.getValue());
            for (var entry : values.entrySet()) if (entry.getKey().toString().contains(key)) {
                Object value = entry.getValue().getClass().getMethod("get").invoke(entry.getValue());
                result.put(registry.getKey() + "/" + entry.getKey(), value.getClass().getSimpleName() + ":" + value);
            }
        }
        return result;
    }
    private Object runtimeRegistry(VariablePlaceholderAudit.Harness h, Object tree, String scope) throws Exception {
        if (scope.equals("skill")) return h.treeType.getMethod("snapshot").invoke(tree);
        String registryKey = switch (scope) {
            case "caster" -> "entity:" + h.a.getUniqueId(); case "target" -> "entity:" + h.b.getUniqueId();
            case "trigger" -> "entity:" + h.c.getUniqueId(); case "world" -> "world:" + h.a.getWorld().getUID();
            case "other-world" -> "world:" + h.d.getWorld().getUID(); default -> "global";
        };
        return h.runtimeType("dev.helstera.ai.skill.SkillExtras").getMethod("scopedVariableSnapshot", String.class).invoke(null, registryKey);
    }
    private Map<String, String> runtimeSnapshot(VariablePlaceholderAudit.Harness h, Object tree, String key) throws Exception {
        var result = new TreeMap<String, String>();
        for (String scope : REGISTRIES) {
            Object snapshot = runtimeRegistry(h, tree, scope);
            for (String kind : List.of("variables", "locations")) {
                var values = (Map<?, ?>) snapshot.getClass().getMethod(kind).invoke(snapshot);
                for (var entry : values.entrySet()) if (entry.getKey().toString().contains(key)) {
                    Object value = entry.getValue(); result.put(scope + "/" + entry.getKey(), value.getClass().getSimpleName() + ":" + value);
                }
            }
        }
        return result;
    }
    private long nativeExpiration(VariablePlaceholderAudit.Harness h, Object registry, String key) throws Exception {
        Object value = registry.getClass().getMethod("get", String.class).invoke(registry, key);
        return value == null ? 0 : (long) value.getClass().getMethod("getExpirationTime").invoke(value);
    }
    private long runtimeExpiration(VariablePlaceholderAudit.Harness h, Object tree, String scope, String key) throws Exception {
        Object snapshot = runtimeRegistry(h, tree, scope);
        var deadlines = (Map<?, ?>) snapshot.getClass().getMethod(scope.equals("skill") ? "variableExpirations" : "variableRetention").invoke(snapshot);
        Object deadline = deadlines.get(key); return deadline == null ? 0 : ((Number) deadline).longValue();
    }
}
