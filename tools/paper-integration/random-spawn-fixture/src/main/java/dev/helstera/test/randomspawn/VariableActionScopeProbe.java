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

/** Focused native mutation-scope comparison. Does not claim general variable mechanic parity. */
public final class VariableActionScopeProbe extends JavaPlugin {
    private static final List<String> SCOPES = List.of("skill", "caster", "target", "world", "global");
    private static final List<String> REGISTRIES = List.of("skill", "caster", "target", "trigger", "world", "other-world", "global");
    private final Set<Chunk> tickets = new LinkedHashSet<>();
    private int observations;

    private record Case(String id, String arguments, String scope) { }

    private record ImportResult(String mapped, List<String> diagnostics) { }

    @Override public void onEnable() {
        Bukkit.getScheduler().runTaskLater(this, () -> {
            try {
                runComparison();
                getLogger().info("VSCOPE_SUMMARY|observations=" + observations + "|passed=true");
            } catch (Throwable error) {
                getLogger().severe("VSCOPE_FAILED|" + error);
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
        var cases = new ArrayList<Case>();
        cases.add(new Case("default", "var={key}", "skill"));
        for (String scope : SCOPES) {
            cases.add(new Case("prefix-" + scope, "var=" + scope + ".{key}", scope));
            cases.add(new Case("explicit-" + scope, "var={key};scope=" + scope, scope));
            cases.add(new Case("override-" + scope, "var=skill.{key};s=" + scope.toUpperCase(Locale.ROOT), scope));
        }
        for (String alias : List.of("name", "n", "variable", "var", "key", "k"))
            cases.add(new Case("alias-" + alias, alias + "=ignored.{key};s=target", "target"));
        cases.add(new Case("duplicate-name", "var=skill.{key};VAR=target.{key}", "target"));
        cases.add(new Case("alias-order", "var=skill.{key};variable=caster.{key}", "caster"));
        cases.add(new Case("name-priority", "var=skill.{key};variable=caster.{key};name=world.{key}", "world"));
        cases.add(new Case("scope-priority", "var={key};s=caster;scope=target", "target"));
        cases.add(new Case("duplicate-scope", "var=skill.{key};scope=bad;SCOPE=global", "global"));
        cases.add(new Case("invalid-prefix-overridden", "var=trigger.{key};scope=world", "world"));
        cases.add(new Case("empty-prefix-overridden", "var=.{key};scope=caster", "caster"));
        cases.add(new Case("dotted-key-prefix", "var=global.{key}", "global"));
        cases.add(new Case("dotted-key-override", "var=skill.{key};scope=target", "target"));
        cases.add(new Case("invalid-prefix", "var=unknown.{key}", null));
        cases.add(new Case("trigger-prefix", "var=trigger.{key}", null));
        cases.add(new Case("invalid-scope", "var=skill.{key};scope=bad", null));
        cases.add(new Case("empty-scope", "var=skill.{key};scope=", null));
        cases.add(new Case("trigger-scope", "var=skill.{key};scope=trigger", null));
        cases.add(new Case("duplicate-invalid", "var=skill.{key};scope=caster;scope=bad", null));
        cases.add(new Case("quoted-scope", "var=skill.{key};scope=\"target\"", null));

        int number = 0;
        for (String path : List.of("canonical-direct", "native-direct", "imported")) {
            try (var h = new VariablePlaceholderAudit.Harness(this, runtime, mythic, center, path.equals("imported"))) {
                var identities = new YamlConfiguration();
                for (int i = 0; i < h.mobs.size(); i++) identities.set("entity-" + i, h.mobs.get(i).getUniqueId().toString());
                identities.save(getDataFolder().toPath().resolve("identities-" + path + ".yml").toFile());
                for (String operation : List.of("set", "add", "unset", "location")) for (Case sample : cases) {
                    if (path.equals("canonical-direct") && sample.scope() == null) continue;
                    observe(h, path, operation, sample, "ScopeAudit" + (number++)
                            + (sample.id().startsWith("dotted-key-") ? ".Part" : ""));
                }
            }
        }
    }

    private void observe(VariablePlaceholderAudit.Harness h, String path, String operation, Case sample, String key) throws Exception {
        Object tree = h.newTree(h.c, h.b, h.b.getLocation());
        Object context = h.context(h.a, h.b, h.c, h.b.getLocation(), tree);
        Object meta = h.meta(h.a, h.c);
        Class<?> nativeLocation = h.nativeType("io.lumine.mythic.api.adapters.AbstractLocation");
        Object point = h.nativeType("io.lumine.mythic.bukkit.BukkitAdapter").getMethod("adapt", Location.class)
                .invoke(null, h.b.getLocation());
        h.metadataType.getMethod("setOrigin", nativeLocation).invoke(meta, point);
        Map<String, Object> nativeRegistries = nativeRegistries(h, meta);
        var beforeExpected = new TreeMap<String, String>();
        for (int i = 0; i < REGISTRIES.size(); i++) {
            String scope = REGISTRIES.get(i);
            nativeRegistries.get(scope).getClass().getMethod("putInt", String.class, int.class)
                    .invoke(nativeRegistries.get(scope), key, 10 + i);
            Object seedContext = scope.equals("trigger")
                    ? h.context(h.c, h.c, h.c, h.c.getLocation(), tree)
                    : scope.equals("other-world") ? h.context(h.d, h.d, h.d, h.d.getLocation(), tree) : context;
            String seedScope = scope.equals("trigger") ? "caster" : scope.equals("other-world") ? "world" : scope;
            // These are literal runtime controls; neither the importer nor native config chooses their registry.
            runRuntime(h, "set-variable var=" + key + " scope=" + seedScope + " type=INTEGER value=" + (10 + i), seedContext);
            beforeExpected.put(scope + "/" + key, "Integer:" + (10 + i));
        }
        String prefix = key.split("\\.", 2)[0];
        Map<String, String> nativeBefore = nativeSnapshot(h, nativeRegistries, prefix);
        Map<String, String> runtimeBefore = runtimeSnapshot(h, tree, prefix);
        if (!beforeExpected.equals(nativeBefore) || !beforeExpected.equals(runtimeBefore))
            throw new IllegalStateException("Wrong seed " + sample.id());
        String mechanic = switch (operation) {
            case "set" -> "VariableSetMechanic";
            case "add" -> "VariableAddMechanic";
            case "unset" -> "VariableUnsetMechanic";
            default -> "VariableSetLocationMechanic";
        };
        String name = switch (operation) {
            case "set" -> "setvariable"; case "add" -> "variableadd";
            case "unset" -> "variableunset"; default -> "setvariablelocation";
        };
        String options = switch (operation) {
            case "set" -> ";value=7"; case "add" -> ";amount=7";
            case "location" -> ";value=@origin"; default -> "";
        };
        String source = name + "{" + sample.arguments().replace("{key}", key) + options + "}";
        Object action = h.nativeType("io.lumine.mythic.core.skills.mechanics." + mechanic)
                .getConstructor(h.executorType, java.io.File.class, String.class, h.configType)
                .newInstance(h.nativeSkills, h.source.toFile(), source, h.configImpl.getConstructor(String.class).newInstance(source));
        var scopeField = h.nativeType("io.lumine.mythic.core.skills.variables.VariableMechanic").getDeclaredField("scope");
        scopeField.setAccessible(true);
        String parsedScope = String.valueOf(scopeField.get(action));
        if (!parsedScope.equals(sample.scope() == null ? "null" : sample.scope().toUpperCase(Locale.ROOT)))
            throw new IllegalStateException("Unexpected native parsed scope " + parsedScope + " " + source);
        String nativeResult;
        try {
            nativeResult = action.getClass().getMethod("castAtEntity", h.skillMetadata, h.abstractEntity)
                    .invoke(action, meta, h.adapt.invoke(null, h.b)).toString();
        } catch (InvocationTargetException error) {
            if (sample.scope() != null) throw error;
            nativeResult = "exception:" + error.getCause().getClass().getSimpleName();
        }
        String mapped;
        List<String> bindingDiagnostics = List.of();
        List<String> importDiagnostics = List.of();
        if (path.equals("imported")) {
            ImportResult result = importAction(h, source, sample.scope() == null);
            mapped = result.mapped();
            importDiagnostics = result.diagnostics();
            if (mapped != null) bindingDiagnostics = runRuntime(h, mapped, context);
        } else if (path.equals("native-direct")) {
            // Feed the source verbatim to the production binder, including aliases,
            // duplicate keys, quote characters and conflicting dotted prefixes.
            bindingDiagnostics = bindAndRunRuntime(h, source, context, sample.scope() == null);
            mapped = sample.scope() == null ? null : source;
        } else {
            mapped = switch (operation) {
            case "set" -> "set-variable var=" + key + " scope=" + sample.scope() + " type=INTEGER value=7";
            case "add" -> "variable-add var=" + key + " scope=" + sample.scope() + " value=7";
            case "unset" -> "variable-unset var=" + key + " scope=" + sample.scope();
            default -> "variable-location-set var=" + key + " scope=" + sample.scope();
            };
            bindingDiagnostics = runRuntime(h, mapped, context);
        }
        Map<String, String> expected = new TreeMap<>(beforeExpected);
        if (sample.scope() != null) {
            String entry = sample.scope() + "/" + key;
            if (!nativeResult.equals("SUCCESS")) throw new IllegalStateException("Native action failed: " + nativeResult);
            switch (operation) {
                case "set" -> expected.put(entry, "Integer:7");
                case "add" -> expected.put(entry, "Integer:" + (17 + REGISTRIES.indexOf(sample.scope())));
                case "unset" -> expected.remove(entry);
                default -> expected.put(entry, location(h.b.getLocation()));
            }
        }
        Map<String, String> nativeAfter = nativeSnapshot(h, nativeRegistries, prefix);
        Map<String, String> runtimeAfter = runtimeSnapshot(h, tree, prefix);
        boolean matched = expected.equals(nativeAfter) && expected.equals(runtimeAfter);
        var evidence = new YamlConfiguration();
        evidence.set("case", sample.id()); evidence.set("operation", operation);
        evidence.set("path", path);
        evidence.set("source", source); evidence.set("mapped", mapped);
        evidence.set("runtimeResult", mapped == null ? "rejected" : "SUCCESS");
        evidence.set("bindingSource", path.equals("native-direct") ? source : mapped);
        evidence.set("bindingDiagnostics", bindingDiagnostics);
        evidence.set("importDiagnostics", importDiagnostics);
        evidence.set("parsedScope", parsedScope); evidence.set("nativeResult", nativeResult);
        evidence.set("nativeBefore", nativeBefore); evidence.set("runtimeBefore", runtimeBefore);
        evidence.set("expectedAfter", expected); evidence.set("nativeAfter", nativeAfter); evidence.set("runtimeAfter", runtimeAfter);
        evidence.set("matched", matched);
        var directory = getDataFolder().toPath().resolve("observations"); Files.createDirectories(directory);
        evidence.save(directory.resolve(String.format(Locale.ROOT, "%04d.yml", observations)).toFile());
        getLogger().info("VSCOPE_ROW|case=" + sample.id() + "|operation=" + operation + "|path="
                + path + "|matched=" + matched);
        observations++;
        for (var registry : nativeRegistries.values()) registry.getClass().getMethod("remove", String.class).invoke(registry, key);
        for (String scope : REGISTRIES) {
            Object cleanupContext = scope.equals("trigger") ? h.context(h.c, h.c, h.c, h.c.getLocation(), tree)
                    : scope.equals("other-world") ? h.context(h.d, h.d, h.d, h.d.getLocation(), tree) : context;
            String cleanupScope = scope.equals("trigger") ? "caster" : scope.equals("other-world") ? "world" : scope;
            runRuntime(h, "variable-unset var=" + key + " scope=" + cleanupScope, cleanupContext);
        }
        if (!matched) throw new IllegalStateException("Scope mismatch: " + source);
    }

    private ImportResult importAction(VariablePlaceholderAudit.Harness h, String source, boolean rejected) throws Exception {
        var yaml = new YamlConfiguration(); yaml.set("write.Skills", List.of(source)); yaml.save(h.source.toFile());
        @SuppressWarnings("unchecked") var plans = (List<Map<String, Object>>) h.importer.getClass()
                .getMethod("convert", java.nio.file.Path.class).invoke(h.importer, h.source);
        var plan = plans.stream().filter(p -> "skills.yml".equals(p.get("target-file"))).findFirst().orElseThrow();
        yaml.loadFromString(plan.get("content").toString());
        List<String> lines = yaml.getStringList("skills.write.on-decision");
        if (rejected) {
            if (!lines.isEmpty() || !yaml.getStringList("skills.write.unsupported").equals(List.of(source))
                    || plans.stream().noneMatch(p -> "WARN".equals(p.get("severity")) && "unsupported".equals(p.get("status"))))
                throw new IllegalStateException("Unsafe source was not preserved: " + source);
            List<String> diagnostics = plans.stream().filter(p -> "WARN".equals(p.get("severity"))
                    && "unsupported".equals(p.get("status"))).map(p -> String.valueOf(p.get("note"))).toList();
            return new ImportResult(null, diagnostics);
        }
        if (lines.size() != 1) throw new IllegalStateException("Source did not import: " + source);
        return new ImportResult(lines.getFirst(), List.of());
    }

    private List<String> runRuntime(VariablePlaceholderAudit.Harness h, String line, Object context) throws Exception {
        return bindAndRunRuntime(h, line, context, false);
    }

    private List<String> bindAndRunRuntime(VariablePlaceholderAudit.Harness h, String line, Object context,
                                         boolean rejected) throws Exception {
        int warningCount = ((List<?>) h.skills.getClass().getMethod("warnings").invoke(h.skills)).size();
        String bound = (String) h.skills.getClass().getMethod("bindAction", String.class).invoke(h.skills, line);
        var allWarnings = (List<?>) h.skills.getClass().getMethod("warnings").invoke(h.skills);
        List<String> diagnostics = allWarnings.subList(warningCount, allWarnings.size()).stream().map(String::valueOf).toList();
        if (rejected) {
            if (bound != null || diagnostics.isEmpty() || diagnostics.stream().noneMatch(message -> message.contains(line)))
                throw new IllegalStateException("Unsafe source bound or lacked a diagnostic: " + line);
            return diagnostics;
        }
        if (bound == null || !(boolean) h.registry.getClass().getMethod("runAction", String.class, h.contextType)
                .invoke(h.registry, bound, context)) throw new IllegalStateException("Runtime action failed: " + line);
        if (!diagnostics.isEmpty()) throw new IllegalStateException("Valid source produced warnings: " + diagnostics);
        return diagnostics;
    }

    private Map<String, Object> nativeRegistries(VariablePlaceholderAudit.Harness h, Object meta) throws Exception {
        var result = new LinkedHashMap<String, Object>();
        Class<?> scopeType = h.nativeType("io.lumine.mythic.core.skills.variables.VariableScope");
        Object variables = h.mythic.getClass().getMethod("getVariableManager").invoke(h.mythic);
        for (String scope : REGISTRIES) {
            Entity target = scope.equals("trigger") ? h.c : h.b;
            String effective = scope.equals("trigger") ? "target" : scope.equals("other-world") ? "world" : scope;
            Object registryMeta = scope.equals("other-world") ? h.meta(h.d, h.d) : meta;
            Object registry = variables.getClass().getMethod("getRegistry", scopeType, h.placeholderMeta, h.abstractEntity)
                    .invoke(variables, scopeType.getField(effective.toUpperCase(Locale.ROOT)).get(null), registryMeta, h.adapt.invoke(null, target));
            if (registry == null) throw new IllegalStateException("Missing native registry " + scope);
            result.put(scope, registry);
        }
        return result;
    }

    private Map<String, String> nativeSnapshot(VariablePlaceholderAudit.Harness h, Map<String, Object> registries, String prefix) throws Exception {
        var result = new TreeMap<String, String>();
        Class<?> nativeLocation = h.nativeType("io.lumine.mythic.api.adapters.AbstractLocation");
        for (var entry : registries.entrySet()) {
            var values = (Map<?, ?>) entry.getValue().getClass().getMethod("asMap").invoke(entry.getValue());
            for (var value : values.entrySet()) if (value.getKey().toString().contains(prefix)) {
                Object raw = value.getValue().getClass().getMethod("get").invoke(value.getValue());
                String text = nativeLocation.isInstance(raw)
                        ? location((Location) h.nativeType("io.lumine.mythic.bukkit.BukkitAdapter").getMethod("adapt", nativeLocation).invoke(null, raw))
                        : raw.getClass().getSimpleName() + ":" + raw;
                result.put(entry.getKey() + "/" + value.getKey(), text);
            }
        }
        return result;
    }

    private Map<String, String> runtimeSnapshot(VariablePlaceholderAudit.Harness h, Object tree, String prefix) throws Exception {
        var result = new TreeMap<String, String>();
        for (String scope : REGISTRIES) {
            Object snapshot;
            if (scope.equals("skill")) snapshot = h.treeType.getMethod("snapshot").invoke(tree);
            else {
                String registryKey = switch (scope) {
                    case "caster" -> "entity:" + h.a.getUniqueId(); case "target" -> "entity:" + h.b.getUniqueId();
                    case "trigger" -> "entity:" + h.c.getUniqueId(); case "world" -> "world:" + h.a.getWorld().getUID();
                    case "other-world" -> "world:" + h.d.getWorld().getUID(); default -> "global";
                };
                snapshot = h.runtimeType("dev.helstera.ai.skill.SkillExtras").getMethod("scopedVariableSnapshot", String.class).invoke(null, registryKey);
            }
            for (String kind : List.of("variables", "locations")) {
                var values = (Map<?, ?>) snapshot.getClass().getMethod(kind).invoke(snapshot);
                for (var entry : values.entrySet()) if (entry.getKey().toString().contains(prefix)) {
                    Object value = entry.getValue();
                    String text = kind.equals("variables") ? value.getClass().getSimpleName() + ":" + value
                            : "location:" + value.getClass().getMethod("world").invoke(value) + ","
                            + value.getClass().getMethod("x").invoke(value) + "," + value.getClass().getMethod("y").invoke(value)
                            + "," + value.getClass().getMethod("z").invoke(value);
                    result.put(scope + "/" + entry.getKey(), text);
                }
            }
        }
        return result;
    }

    private static String location(Location location) {
        return "location:" + location.getWorld().getName() + "," + location.getX() + "," + location.getY() + "," + location.getZ();
    }
}
