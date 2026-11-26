package dev.helstera.test.randomspawn;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
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

/** Real ordinary player target values; direct native casts, observation only. */
public final class VariableLocationPlayerProbe extends JavaPlugin {
    private static final List<String> SCOPES = List.of("skill", "caster", "target", "world", "global");
    private static final List<String> REGISTRIES = List.of("skill", "caster", "target", "trigger", "aimed", "peer", "world", "other-world", "global");
    private final Set<Chunk> tickets = new LinkedHashSet<>();
    private Player caster;
    private Player peer;
    private org.bukkit.entity.Cow aimed;
    private int observations;
    private boolean started;
    private record Case(String id, String arguments, String selector, String expected, String wall, String peerMode, boolean miss, boolean turn, long deadline) { }
    private record Snapshot(Map<String, String> values, Map<String, Long> deadlines) { }

    private static List<Case> cases() {
        return List.of(
                sample("entity-hit", "value=@target", "@target", "aimed", "AIR", "off", false, false),
                sample("entity-short", "value=@T", "@target", "aimed", "AIR", "off", false, false),
                sample("entity-miss", "value=@target", "@target", "origin", "AIR", "off", true, false),
                sample("entity-stone", "value=@target", "@target", "origin", "STONE", "off", false, false),
                sample("entity-glass", "value=@target", "@target", "origin", "GLASS", "off", false, false),
                sample("entity-barrier", "value=@target", "@target", "aimed", "BARRIER", "off", false, false),
                sample("entity-creative", "value=@target", "@target", "aimed", "AIR", "CREATIVE", false, false),
                sample("entity-player", "value=@target", "@target", "peer", "AIR", "SURVIVAL", false, false),
                sample("location-stone", "value=@targetlocation", "@targetlocation", "near-hit", "STONE", "off", false, false),
                sample("location-empty", "value=@targetlocation", "@targetlocation", "endpoint", "AIR", "off", false, false),
                sample("location-glass", "value=@targetlocation", "@targetlocation", "far-hit", "GLASS", "off", false, false),
                sample("location-barrier", "value=@targetLoc", "@targetlocation", "far-hit", "BARRIER", "off", false, false),
                sample("location-short", "value=@TL", "@targetlocation", "endpoint", "AIR", "off", false, false),
                sample("location-turned", "value=@targetlocation", "@targetlocation", "turned-endpoint", "AIR", "off", false, true),
                new Case("location-saved", "value=@targetlocation;save=true", "@targetlocation", "near-hit", "STONE", "off", false, false, 0));
    }
    private static Case sample(String id, String arguments, String selector, String expected, String wall, String peerMode, boolean miss, boolean turn) {
        return new Case(id, arguments, selector, expected, wall, peerMode, miss, turn, 1);
    }

    @Override public void onEnable() {
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (started) return;
            caster = Bukkit.getPlayerExact("LocationCaster");
            peer = Bukkit.getPlayerExact("LocationPeer");
            if (caster == null || peer == null || caster.getTicksLived() < 40 || peer.getTicksLived() < 40) return;
            try {
                var mythic = Bukkit.getPluginManager().getPlugin("MythicMobs");
                Object manager = mythic.getClass().getMethod("getPlayerManager").invoke(mythic);
                if (manager.getClass().getMethod("getProfile", Player.class).invoke(manager, caster) == null
                        || manager.getClass().getMethod("getProfile", Player.class).invoke(manager, peer) == null) return;
                started = true;
                runComparison();
                getLogger().info("VLOCATION_PLAYER_SUMMARY|observations=" + observations + "|passed=true");
                caster.sendMessage("VLOCATION_PLAYER_DONE"); peer.sendMessage("VLOCATION_PLAYER_DONE");
            } catch (Throwable error) {
                started = true; getLogger().severe("VLOCATION_PLAYER_FAILED|" + error); error.printStackTrace();
            } finally {
                if (started) for (Chunk chunk : tickets) chunk.removePluginChunkTicket(this);
            }
        }, 20, 10);
    }

    private void runComparison() throws Exception {
        var runtime = Bukkit.getPluginManager().getPlugin("helsteraMobs");
        var mythic = Bukkit.getPluginManager().getPlugin("MythicMobs");
        if (runtime == null || mythic == null) throw new IllegalStateException("Missing plugins");
        var world = Bukkit.getWorlds().getFirst();
        caster.setGameMode(GameMode.CREATIVE); caster.setAllowFlight(true); caster.setFlying(true);
        caster.setGravity(false); caster.setInvulnerable(true); peer.setGravity(false); peer.setInvulnerable(true);
        for (int x = 233; x <= 323; x++) for (int z = 298; z <= 367; z++) {
            if (x > 303 && z > 303 || x < 298 && z > 303) continue;
            for (int y = 169; y <= 174; y++) world.getBlockAt(x, y, z).setType(Material.AIR, false);
            Chunk chunk = world.getChunkAt(x >> 4, z >> 4);
            if (tickets.add(chunk)) chunk.addPluginChunkTicket(this);
        }
        Object manager = mythic.getClass().getMethod("getPlayerManager").invoke(mythic);
        for (String path : List.of("native-direct", "imported")) {
            try (var h = new VariablePlaceholderAudit.Harness(this, runtime, mythic, new Location(world, 0, 150, 0), path.equals("imported"))) {
                h.active.put(caster, manager.getClass().getMethod("getProfile", Player.class).invoke(manager, caster));
                h.active.put(peer, manager.getClass().getMethod("getProfile", Player.class).invoke(manager, peer));
                h.b.teleport(new Location(world, 310, 170, 300, 70, 15));
                h.c.teleport(new Location(world, 290, 170, 300, 120, 25));
                aimed = h.mob(new Location(world, 300.5, 170, 312.5, 30, 20));
                var identities = new YamlConfiguration();
                identities.set("caster", caster.getUniqueId().toString()); identities.set("peer", peer.getUniqueId().toString());
                for (int i = 0; i < h.mobs.size(); i++) identities.set("entity-" + i, h.mobs.get(i).getUniqueId().toString());
                identities.save(getDataFolder().toPath().resolve("identities-" + path + ".yml").toFile());
                for (String dispatch : List.of("entity", "location")) for (String scope : SCOPES)
                    for (Case sample : cases()) observe(h, path, dispatch, scope, sample, "LocationPlayerAudit" + observations);
            }
        }
    }

    private void prepare(Case sample) {
        var world = caster.getWorld();
        caster.teleport(new Location(world, 300.5, 170, 300.5, 0, 0));
        aimed.teleport(new Location(world, sample.miss() ? 320.5 : 300.5, 170, 312.5, 30, 20));
        peer.setGameMode(sample.peerMode().equals("SURVIVAL") ? GameMode.SURVIVAL : GameMode.CREATIVE);
        peer.teleport(new Location(world, sample.peerMode().equals("off") ? 320.5 : 300.5, 170, 308.5, 135, 15));
        world.getBlockAt(300, 171, 305).setType(Material.valueOf(sample.wall()), false);
        world.getBlockAt(300, 171, 315).setType(sample.expected().equals("far-hit") ? Material.STONE : Material.AIR, false);
    }

    private void observe(VariablePlaceholderAudit.Harness h, String path, String dispatch, String scope, Case sample, String key) throws Exception {
        prepare(sample);
        var origin = new Location(caster.getWorld(), 330, 174, 320, 160, 35);
        var outer = new Location(caster.getWorld(), 340, 178, 326, 210, 45);
        Object tree = h.newTree(h.c, h.b, origin);
        Object seedContext = h.context(caster, h.b, h.c, origin, tree);
        Object context = dispatch.equals("location") ? h.contextType.getMethod("withLocationTarget", Location.class).invoke(seedContext, outer) : seedContext;
        Object meta = h.meta(caster, h.c);
        Class<?> nativeLocation = h.nativeType("io.lumine.mythic.api.adapters.AbstractLocation");
        var adaptLocation = h.nativeType("io.lumine.mythic.bukkit.BukkitAdapter").getMethod("adapt", Location.class);
        h.metadataType.getMethod("setOrigin", nativeLocation).invoke(meta, adaptLocation.invoke(null, origin));
        Map<String, Object> registries = nativeRegistries(h, meta);
        for (int i = 0; i < REGISTRIES.size(); i++) {
            String candidate = REGISTRIES.get(i);
            Object registry = registries.get(candidate);
            registry.getClass().getMethod("putInt", String.class, int.class).invoke(registry, key, 10 + i);
            Object seedValue = registry.getClass().getMethod("get", String.class).invoke(registry, key);
            seedValue.getClass().getMethod("setExpirationTime", long.class).invoke(seedValue, 1L);
            runRuntime(h, "set-variable var=" + key + " scope=" + seedScope(candidate) + " type=INTEGER value=" + (10 + i), seedContext(h, candidate, tree, seedContext));
        }
        Snapshot nativeBefore = nativeSnapshot(h, registries, key, true), runtimeBefore = runtimeSnapshot(h, tree, key);
        if (!nativeBefore.equals(runtimeBefore)) throw new IllegalStateException("Seed mismatch " + sample.id());
        String source = "setvariablelocation{var=" + scope + "." + key + ";" + sample.arguments() + "}";
        String beforeBinding = location(caster.getLocation());
        Object action = h.nativeType("io.lumine.mythic.core.skills.mechanics.VariableSetLocationMechanic")
                .getConstructor(h.executorType, java.io.File.class, String.class, h.configType)
                .newInstance(h.nativeSkills, h.source.toFile(), source, h.configImpl.getConstructor(String.class).newInstance(source));
        String mapped = path.equals("imported") ? importAction(h, source) : source;
        int warningsBefore = ((List<?>) h.skills.getClass().getMethod("warnings").invoke(h.skills)).size();
        String bound = (String) h.skills.getClass().getMethod("bindAction", String.class).invoke(h.skills, mapped);
        if (bound == null) throw new IllegalStateException("Runtime did not bind " + mapped);
        if (sample.turn()) caster.teleport(new Location(caster.getWorld(), 300.5, 170, 300.5, 90, 0));
        Location eye = caster.getEyeLocation();
        String nativeResult;
        try {
            nativeResult = dispatch.equals("entity") ? action.getClass().getMethod("castAtEntity", h.skillMetadata, h.abstractEntity)
                    .invoke(action, meta, h.adapt.invoke(null, h.b)).toString()
                    : action.getClass().getMethod("castAtLocation", h.skillMetadata, nativeLocation).invoke(action, meta, adaptLocation.invoke(null, outer)).toString();
        } catch (InvocationTargetException error) { nativeResult = "exception:" + error.getCause().getClass().getSimpleName(); }
        Snapshot nativeStoredAfter = nativeSnapshot(h, registries, key, false), nativeAfter = nativeSnapshot(h, registries, key, true);
        if (!(boolean) h.registry.getClass().getMethod("runAction", String.class, h.contextType).invoke(h.registry, bound, context))
            throw new IllegalStateException("Runtime did not dispatch");
        var warnings = (List<?>) h.skills.getClass().getMethod("warnings").invoke(h.skills);
        var diagnostics = warnings.subList(warningsBefore, warnings.size()).stream().map(String::valueOf).toList();
        Snapshot runtimeAfter = runtimeSnapshot(h, tree, key);
        boolean writes = !(scope.equals("target") && dispatch.equals("location"));
        Location expected = switch (sample.expected()) {
            case "aimed" -> aimed.getLocation(); case "peer" -> peer.getLocation(); case "origin" -> origin;
            case "near-hit" -> new Location(caster.getWorld(), 300.5, eye.getY(), 305);
            case "far-hit" -> new Location(caster.getWorld(), 300.5, eye.getY(), 315);
            default -> eye.toVector().add(eye.getDirection().normalize().multiply(64)).toLocation(caster.getWorld());
        };
        var expectedValues = new TreeMap<>(nativeBefore.values()); var expectedDeadlines = new TreeMap<>(nativeBefore.deadlines());
        if (writes) { expectedValues.put(scope + "/" + key, location(expected)); expectedDeadlines.put(scope + "/" + key, sample.deadline()); }
        Snapshot expectedSnapshot = new Snapshot(expectedValues, expectedDeadlines);
        boolean matched = nativeResult.equals(writes ? "SUCCESS" : "INVALID_CONFIG")
                && nativeStoredAfter.equals(expectedSnapshot) && nativeAfter.equals(expectedSnapshot)
                && runtimeAfter.equals(expectedSnapshot) && diagnostics.isEmpty();
        var row = new YamlConfiguration();
        row.set("case", sample.id()); row.set("scope", scope); row.set("dispatch", dispatch); row.set("path", path); row.set("key", key);
        row.set("source", source); row.set("mapped", mapped); row.set("bindingSource", mapped); row.set("bindingDiagnostics", diagnostics);
        row.set("selected", sample.selector()); row.set("nativeResult", nativeResult); row.set("runtimeResult", "dispatched");
        row.set("casterBeforeBinding", beforeBinding); row.set("casterAtDispatch", location(caster.getLocation())); row.set("eyeAtDispatch", location(eye));
        row.set("wall", sample.wall()); row.set("rearWall", sample.expected().equals("far-hit") ? "STONE" : "AIR");
        row.set("peerMode", peer.getGameMode().name()); row.set("expectedRole", sample.expected());
        row.set("positions", Map.of("caster", location(caster.getLocation()), "recipient", location(h.b.getLocation()), "trigger", location(h.c.getLocation()),
                "aimed", location(aimed.getLocation()), "peer", location(peer.getLocation()), "origin", location(origin), "outer", location(outer)));
        saveSnapshot(row, "nativeBefore", nativeBefore); saveSnapshot(row, "runtimeBefore", runtimeBefore);
        saveSnapshot(row, "nativeStoredAfter", nativeStoredAfter); saveSnapshot(row, "nativeAfter", nativeAfter); saveSnapshot(row, "runtimeAfter", runtimeAfter);
        row.set("matched", matched);
        var directory = getDataFolder().toPath().resolve("observations"); Files.createDirectories(directory);
        row.save(directory.resolve(String.format(Locale.ROOT, "%04d.yml", observations)).toFile());
        getLogger().info("VLOCATION_PLAYER_ROW|case=" + sample.id() + "|scope=" + scope + "|dispatch=" + dispatch + "|path=" + path + "|matched=" + matched);
        observations++;
        if (!matched) throw new IllegalStateException("Player location mismatch " + source + " " + dispatch + " " + nativeResult);
        for (String candidate : REGISTRIES) {
            registries.get(candidate).getClass().getMethod("remove", String.class).invoke(registries.get(candidate), key);
            runRuntime(h, "variable-unset var=" + key + " scope=" + seedScope(candidate), seedContext(h, candidate, tree, seedContext));
        }
    }
    private static String seedScope(String candidate) {
        return List.of("trigger", "aimed", "peer").contains(candidate) ? "caster" : candidate.equals("other-world") ? "world" : candidate;
    }
    private Object seedContext(VariablePlaceholderAudit.Harness h, String candidate, Object tree, Object seed) throws Exception {
        Entity subject = switch (candidate) { case "trigger" -> h.c; case "aimed" -> aimed; case "peer" -> peer; case "other-world" -> h.d; default -> null; };
        return subject == null ? seed : h.context(subject, (org.bukkit.entity.LivingEntity) subject, subject, subject.getLocation(), tree);
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
            Entity target = scope.equals("aimed") ? aimed : scope.equals("peer") ? peer
                    : scope.equals("trigger") ? h.c : h.b;
            String effective = List.of("trigger", "aimed", "peer").contains(scope) ? "target" : scope.equals("other-world") ? "world" : scope;
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
                    case "caster" -> "player:" + caster.getUniqueId(); case "target" -> "entity:" + h.b.getUniqueId();
                    case "trigger" -> "entity:" + h.c.getUniqueId();
                    case "aimed" -> "entity:" + aimed.getUniqueId();
                    case "peer" -> "player:" + peer.getUniqueId(); case "world" -> "world:" + caster.getWorld().getUID();
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
