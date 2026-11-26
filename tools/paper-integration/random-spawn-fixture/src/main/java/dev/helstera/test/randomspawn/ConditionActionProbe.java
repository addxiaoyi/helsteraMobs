package dev.helstera.test.randomspawn;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Native condition-outcome dispatch versus the production named-skill entry point. */
public final class ConditionActionProbe extends JavaPlugin {
    private static final List<String> ACTIONS = List.of("REQUIRED", "CANCEL", "CAST", "CASTINSTEAD", "ORELSECAST", "POWER", "LEVEL");
    private final Set<Chunk> tickets = new LinkedHashSet<>();
    private int observations;

    @Override public void onEnable() {
        Bukkit.getScheduler().runTaskLater(this, () -> {
            try {
                compare();
                getLogger().info("CACTION_SUMMARY|observations=" + observations + "|passed=true");
            } catch (Throwable failure) {
                getLogger().severe("CACTION_FAILED|" + failure);
                failure.printStackTrace();
            } finally {
                tickets.forEach(chunk -> chunk.removePluginChunkTicket(this));
            }
        }, 100);
    }

    private void compare() throws Exception {
        var runtime = Bukkit.getPluginManager().getPlugin("helsteraMobs");
        var mythic = Bukkit.getPluginManager().getPlugin("MythicMobs");
        var center = new Location(Bukkit.getWorlds().getFirst(), 0, 150, 0);
        for (var world : Bukkit.getWorlds()) {
            var point = new Location(world, 110, 170, 0);
            for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) {
                var chunk = world.getChunkAt(point.getBlockX() / 16 + x, point.getBlockZ() / 16 + z);
                chunk.getEntities();
                if (tickets.add(chunk)) chunk.addPluginChunkTicket(this);
            }
        }
        for (String path : List.of("direct", "imported")) {
            try (var h = new VariablePlaceholderAudit.Harness(this, runtime, mythic, center, path.equals("imported"))) {
                var identities = new YamlConfiguration();
                for (int i = 0; i < h.mobs.size(); i++) identities.set("entity-" + i, h.mobs.get(i).getUniqueId().toString());
                identities.save(getDataFolder().toPath().resolve("identities-" + path + ".yml").toFile());
                for (String scope : List.of("caster", "target", "trigger")) {
                    for (String action : ACTIONS) for (boolean predicate : List.of(false, true)) {
                        observe(h, path, scope, action, predicate, "ready");
                        if (action.contains("CAST")) {
                            observe(h, path, scope, action, predicate, "missing");
                            observe(h, path, scope, action, predicate, "rejected");
                        }
                    }
                }
            }
        }
    }

    private void observe(VariablePlaceholderAudit.Harness h, String path, String scope, String action,
                         boolean predicate, String availability) throws Exception {
        String childName = "conditionchild" + observations;
        String source = "burning " + action + (action.contains("CAST") ? " " + childName
                : action.equals("POWER") || action.equals("LEVEL") ? " 2" : "");
        for (var mob : List.of(h.a, h.b, h.c)) mob.setFireTicks(0);
        Entity subject = switch (scope) { case "caster" -> h.a; case "trigger" -> h.c; default -> h.b; };
        subject.setFireTicks(predicate ? 200 : 0);
        Object tree = h.newTree(h.c, h.b, h.a.getLocation());
        Object context = h.context(h.a, h.b, h.c, h.a.getLocation(), tree);
        Object metadata = h.meta(h.a, h.c);
        h.metadataType.getMethod("setEntityTargets", Collection.class)
                .invoke(metadata, List.of(h.adapt.invoke(null, h.b)));
        var nativeChild = new ArrayList<String>();
        if (!availability.equals("missing")) {
            var skillType = h.nativeType("io.lumine.mythic.api.skills.Skill");
            Object child = Proxy.newProxyInstance(h.nativeLoader, new Class<?>[]{skillType}, (proxy, method, arguments) -> {
                return switch (method.getName()) {
                    case "getInternalName", "toString" -> childName;
                    case "isUsable" -> availability.equals("ready");
                    case "isInlineSkill", "onCooldown" -> false;
                    case "getParents", "getConditions", "getConditionsTarget", "getConditionsTrigger" -> List.of();
                    case "addParent" -> null;
                    case "execute" -> {
                        var targets = (Collection<?>) h.metadataType.getMethod("getEntityTargets").invoke(arguments[0]);
                        nativeChild.add("targets=" + targets.size());
                        yield null;
                    }
                    case "getConfig" -> null;
                    default -> throw new UnsupportedOperationException(method.getName());
                };
            });
            h.executorType.getMethod("registerSkill", String.class, skillType).invoke(h.nativeSkills, childName, child);
        }
        Object nativeCondition = h.executorType.getMethod("getCondition", String.class).invoke(h.nativeSkills, source);
        String method = switch (scope) { case "caster" -> "evaluateCaster"; case "trigger" -> "evaluateTrigger"; default -> "evaluateTargets"; };
        boolean nativePassed = (boolean) nativeCondition.getClass().getMethod(method, h.skillMetadata).invoke(nativeCondition, metadata);
        float nativePower = (float) h.metadataType.getMethod("getPower").invoke(metadata);
        String runtimeField = switch (scope) { case "caster" -> "require"; default -> scope + "-conditions"; };
        String nativeField = switch (scope) { case "caster" -> "Conditions"; case "target" -> "TargetConditions"; default -> "TriggerConditions"; };
        var yaml = new YamlConfiguration();
        if (path.equals("imported")) {
            yaml.set("caller." + nativeField, List.of(source));
            yaml.set("caller.Skills", List.of("setvariable{var=skill.parent;type=INTEGER;value=1}"));
            if (!availability.equals("missing")) {
                yaml.set(childName + ".Skills", List.of("setvariable{var=skill.child;type=INTEGER;value=1}"));
                if (availability.equals("rejected")) yaml.set(childName + ".Conditions", List.of("chance{c=0}"));
            }
            yaml.save(h.source.toFile());
            @SuppressWarnings("unchecked") var plans = (List<Map<String, Object>>) h.importer.getClass()
                    .getMethod("convert", java.nio.file.Path.class).invoke(h.importer, h.source);
            var output = plans.stream().filter(plan -> "skills.yml".equals(plan.get("target-file"))).findFirst().orElseThrow();
            yaml.loadFromString(output.get("content").toString());
        } else {
            yaml.set("skills.caller." + runtimeField, List.of(source));
            yaml.set("skills.caller.on-decision", List.of("set-variable var=skill.parent type=INTEGER value=1"));
            if (!availability.equals("missing")) {
                yaml.set("skills." + childName + ".on-decision", List.of("set-variable var=skill.child type=INTEGER value=1"));
                if (availability.equals("rejected")) yaml.set("skills." + childName + ".require", List.of("random-chance 0"));
            }
        }
        String mapped = yaml.getStringList("skills.caller." + runtimeField).getFirst();
        h.skills.getClass().getMethod("loadSkills", ConfigurationSection.class).invoke(h.skills, yaml.getConfigurationSection("skills"));
        h.skills.getClass().getMethod("preview", String.class, h.contextType).invoke(h.skills, "caller", context);
        boolean previewUntouched = variable(h, tree, "child") == 0 && variable(h, tree, "parent") == 0
                && (float) h.contextType.getMethod("skillPower").invoke(context) == 1.0f;
        boolean runtimePassed = (boolean) h.skills.getClass().getMethod("castSkill", String.class, h.contextType)
                .invoke(h.skills, "caller", context);
        int child = variable(h, tree, "child");
        int parent = variable(h, tree, "parent");
        float runtimePower = (float) h.contextType.getMethod("skillPower").invoke(context);
        boolean matched = nativePassed == runtimePassed && child == nativeChild.size()
                && parent == (nativePassed ? 1 : 0) && nativePower == runtimePower && previewUntouched;
        var row = new YamlConfiguration();
        row.set("path", path); row.set("scope", scope); row.set("action", action);
        row.set("predicate", predicate); row.set("availability", availability);
        row.set("source", source); row.set("mapped", mapped); row.set("subject", subject.getUniqueId().toString());
        row.set("nativePassed", nativePassed); row.set("runtimePassed", runtimePassed);
        row.set("nativeChildren", nativeChild.size()); row.set("runtimeChildren", child);
        row.set("runtimeParent", parent); row.set("nativePower", nativePower); row.set("runtimePower", runtimePower);
        row.set("nativeChildTargets", nativeChild); row.set("previewUntouched", previewUntouched); row.set("matched", matched);
        var output = getDataFolder().toPath().resolve("observations/" + String.format("%04d", observations++) + ".yml");
        Files.createDirectories(output.getParent()); row.save(output.toFile());
        getLogger().info("CACTION_ROW|id=" + (observations - 1) + "|matched=" + matched);
        if (!matched) throw new IllegalStateException(row.saveToString());
    }

    private static int variable(VariablePlaceholderAudit.Harness h, Object tree, String key) throws Exception {
        return ((Optional<?>) h.treeType.getMethod("variable", String.class).invoke(tree, key))
                .filter(Number.class::isInstance).map(Number.class::cast).map(Number::intValue).orElse(0);
    }
}
