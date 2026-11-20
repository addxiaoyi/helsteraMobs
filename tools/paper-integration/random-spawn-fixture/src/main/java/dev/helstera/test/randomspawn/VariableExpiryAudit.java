package dev.helstera.test.randomspawn;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import java.util.*;

/** Real-time expiry through public native mechanics and imported/direct runtime actions. */
final class VariableExpiryAudit extends BukkitRunnable {
    private static final List<String> SCOPES = List.of("caster", "target", "skill", "world", "global");
    private static final List<String> CASES = List.of("Short", "Zero", "Negative", "Reset", "Refresh", "Keep", "Unset", "Location", "LocationTimed", "ExpiredAdd", "FloatKeep", "IntegerFraction", "WrongType", "MissingAdd");
    private final JavaPlugin probe;
    private final List<VariablePlaceholderAudit.Harness> harnesses = new ArrayList<>();
    private final List<Sample> samples = new ArrayList<>();
    private final Map<VariablePlaceholderAudit.Harness, Object> restored = new LinkedHashMap<>();
    private int snapshotChecks;
    private final Set<org.bukkit.Chunk> tickets = new HashSet<>();
    private long started;
    private int phase, rows;
    private record Sample(VariablePlaceholderAudit.Harness h, String scope, String name, String kind,
                          Object meta, Object context, Object nativeString, Object nativeRegistry, String condition) { }

    private VariableExpiryAudit(JavaPlugin probe) { this.probe = probe; }
    static void start(JavaPlugin probe, Plugin runtime, Plugin mythic, Location center) throws Exception {
        var audit = new VariableExpiryAudit(probe);
        try {
            for (boolean imported : List.of(false, true)) {
                Location helperCenter=center.clone().add(imported ? 40 : 0, 0, 40);
                Location base=helperCenter.clone().add(110,20,0);
                var cross=base.clone();cross.setWorld(Bukkit.getWorlds().stream().filter(w->w!=base.getWorld()).findFirst().orElseThrow());
                // A ticket added after spawning can race asynchronous entity loading.
                // Prepare every actual helper destination before invoking the native spawn API.
                for(Location point:List.of(base,base.clone().add(0,0,4),base.clone().add(4,0,0),cross)) {
                    var chunk=point.getChunk();chunk.getEntities();
                    if(audit.tickets.add(chunk))chunk.addPluginChunkTicket(probe);
                }
                var h = new VariablePlaceholderAudit.Harness(probe, runtime, mythic, helperCenter, imported);
                audit.harnesses.add(h);
                for (var mob : h.mobs) {
                    var chunk = mob.getLocation().getChunk();
                    if (audit.tickets.add(chunk)) chunk.addPluginChunkTicket(probe);
                }
                Object tree = h.newTree(h.c, h.b, h.a.getLocation());
                Object context = h.context(h.a, h.b, h.c, h.a.getLocation(), tree), meta = h.meta(h.a, h.c);
                Class<?> locationType = h.nativeType("io.lumine.mythic.api.adapters.AbstractLocation");
                Object point = h.nativeType("io.lumine.mythic.bukkit.BukkitAdapter").getMethod("adapt", Location.class).invoke(null, h.b.getLocation());
                h.metadataType.getMethod("setOrigin", locationType).invoke(meta, point);
                h.metadataType.getMethod("setEntityTarget", h.abstractEntity).invoke(meta, h.adapt.invoke(null, h.b));
                for (String scope : SCOPES) for (String kind : CASES) {
                    String name = "Expiry" + (imported ? "I" : "D") + kind;
                    String variable = scope + "." + name;
                    String duration = switch (kind) { case "Zero" -> ";d=0"; case "Negative" -> ";e=-1"; default -> ";duration=40"; };
                    if (kind.startsWith("Location")) {
                        h.execute("setvariablelocation{var=" + variable + ";value=@origin"
                                        + (kind.equals("LocationTimed") ? ";expire=20" : "") + "}",
                                "VariableSetLocationMechanic", meta, context, h.b);
                    } else if (kind.equals("MissingAdd")) {
                        h.execute("variableadd{var=" + variable + ";amount=2;duration=200}",
                                "VariableAddMechanic", meta, context, h.b, "INVALID_CONFIG");
                    } else {
                        String value = kind.equals("FloatKeep") ? ";type=FLOAT;value=0.125"
                                : kind.equals("WrongType") ? ";type=DOUBLE;value=7"
                                : kind.equals("IntegerFraction") ? ";value=7" : ";type=INTEGER;value=7";
                        h.execute("setvariable{var=" + variable + value + duration + "}",
                                "VariableSetMechanic", meta, context, h.b);
                        if (kind.equals("Reset")) h.set(variable, "INTEGER", "9", meta, context, h.b);
                        if (kind.equals("Refresh") || kind.equals("Keep"))
                            h.execute("variableadd{var=" + variable + ";amount=2" + (kind.equals("Refresh") ? ";expire=200" : "") + "}",
                                    "VariableAddMechanic", meta, context, h.b);
                        if (kind.equals("FloatKeep") || kind.equals("IntegerFraction") || kind.equals("WrongType"))
                            h.execute("variableadd{var=" + variable + ";amount=" + (kind.equals("FloatKeep") ? "0.125" : "2.9") + "}",
                                    "VariableAddMechanic", meta, context, h.b, kind.equals("WrongType") ? "INVALID_CONFIG" : "SUCCESS");
                        if (kind.equals("Unset")) h.unset(variable, meta, context, h.b);
                    }
                    Class<?> scopeType = h.nativeType("io.lumine.mythic.core.skills.variables.VariableScope");
                    Object manager = mythic.getClass().getMethod("getVariableManager").invoke(mythic);
                    Object registry = manager.getClass().getMethod("getRegistry", scopeType, h.placeholderMeta, h.abstractEntity)
                            .invoke(manager, scopeType.getField(scope.toUpperCase(Locale.ROOT)).get(null), meta, h.adapt.invoke(null, h.b));
                    Object text = h.strings.getMethod("of", String.class).invoke(null, "<" + scope + ".var." + name + "|absent>");
                    String condition = (String) h.skills.getClass().getMethod("bindCondition", String.class)
                            .invoke(h.skills, "variable-is-set{var=" + variable + "}");
                    if (condition == null) throw new IllegalStateException("Expiry condition did not bind");
                    var sample = new Sample(h, scope, name, kind, meta, context, text, registry, condition);
                    audit.samples.add(sample);
                    audit.observe(sample, "initial");
                }
                Class<?> snapshotType = h.runtimeType("dev.helstera.api.behavior.SkillTreeContext$Snapshot");
                Class<?> store = h.runtimeType("dev.helstera.api.behavior.SkillTreeSnapshotStore");
                java.io.File file = probe.getDataFolder().toPath().resolve("expiry-" + imported + ".yml").toFile();
                Object snapshot = h.treeType.getMethod("snapshot").invoke(tree);
                store.getMethod("save", java.io.File.class, snapshotType).invoke(null, file, snapshot);
                Object loaded = store.getMethod("load", java.io.File.class).invoke(null, file);
                Object restoredTree = h.treeType.getMethod("restore", snapshotType, org.bukkit.entity.Entity.class,
                                List.class, java.util.function.Function.class)
                        .invoke(null, loaded, h.c, List.of(h.b), (java.util.function.Function<String, org.bukkit.World>) Bukkit::getWorld);
                audit.restored.put(h, h.context(h.a, h.b, h.c, h.a.getLocation(), restoredTree));
            }
            audit.observeSnapshots("initial");
            audit.started = System.currentTimeMillis();
            audit.runTaskTimer(probe, 1, 1);
        } catch (Exception error) { audit.cleanup(); throw error; }
    }

    @Override public void run() {
        try {
            long elapsed = System.currentTimeMillis() - started;
            if (elapsed < (phase == 0 ? 2500 : 11000)) return;
            for(var h:harnesses)for(var mob:h.mobs) {
                Object active=h.manager.getClass().getMethod("getActiveMob",java.util.UUID.class).invoke(h.manager,mob.getUniqueId());
                boolean live=mob.isValid()&&!mob.isDead()&&Bukkit.getEntity(mob.getUniqueId())==mob&&((Optional<?>)active).isPresent();
                probe.getLogger().info("VARIABLE_EXPIRY_ENTITY|phase="+phase+"|path="+(h.imported?"imported":"direct")
                        +"|uuid="+mob.getUniqueId()+"|live="+live);
                if(!live)throw new IllegalStateException("Expiry helper entity/registry disappeared: "+mob.getUniqueId());
            }
            for (Sample sample : samples) observe(sample, phase == 0 ? "short-expired" : "refreshed-expired");
            observeSnapshots(phase == 0 ? "short-expired" : "refreshed-expired");
            if (++phase == 2) {
                cleanup(); cancel();
                if (rows != 420 || snapshotChecks != 6) throw new IllegalStateException("Incomplete expiry observations: " + rows);
                probe.getLogger().info("VARIABLE_EXPIRY_SUMMARY|observations=" + rows
                        + "|scopes=5|cases=14|phases=3|paths=2|snapshotChecks=6|elapsedMillis=" + elapsed + "|cleanup=true|passed=true");
            }
        } catch (Throwable error) {
            try { cleanup(); } catch (Exception ignored) { }
            cancel();
            probe.getLogger().severe("VARIABLE_EXPIRY_SUMMARY|passed=false|error=" + error);
        }
    }

    private void observe(Sample s, String phase) throws Exception {
        var h = s.h();
        if (phase.equals("short-expired") && s.kind().equals("ExpiredAdd"))
            h.execute("variableadd{var=" + s.scope() + "." + s.name() + ";amount=2;duration=200}",
                    "VariableAddMechanic", s.meta(), s.context(), h.b, "INVALID_CONFIG");
        String expected = (String) h.strings.getMethod("get", h.placeholderMeta, h.abstractEntity)
                .invoke(s.nativeString(), s.meta(), h.adapt.invoke(null, h.b));
        String actual = (String) h.resolve.invoke(null, "<" + s.scope() + ".var." + s.name() + "|absent>", s.context());
        boolean nativeSet = (boolean) s.nativeRegistry().getClass().getMethod("has", String.class).invoke(s.nativeRegistry(), s.name());
        boolean runtimeSet = (boolean) h.registry.getClass().getMethod("testCondition", String.class, h.contextType)
                .invoke(h.registry, s.condition(), s.context());
        boolean shouldExist = switch (s.kind()) {
            case "Unset", "LocationTimed", "MissingAdd" -> false;
            case "Short", "Keep", "ExpiredAdd", "FloatKeep", "IntegerFraction", "WrongType" -> phase.equals("initial");
            case "Refresh" -> !phase.equals("refreshed-expired");
            default -> true;
        };
        boolean same = expected.equals(actual);
        boolean matched = same && nativeSet == shouldExist && runtimeSet == shouldExist;
        probe.getLogger().info("VARIABLE_EXPIRY|scope=" + s.scope() + "|case=" + s.kind() + "|phase=" + phase
                + "|path=" + (h.imported ? "imported" : "direct") + "|native=" + expected + "|runtime=" + actual
                + "|nativeSet=" + nativeSet + "|runtimeSet=" + runtimeSet + "|matched=" + matched);
        if (!matched) throw new IllegalStateException("Variable expiry differs: " + s.scope() + " " + s.kind() + " " + phase);
        rows++;
    }

    private void observeSnapshots(String phase) throws Exception {
        for (var entry : restored.entrySet()) {
            var h = entry.getKey();
            String token = "<skill.var.Expiry" + (h.imported ? "I" : "D") + "Refresh|absent>";
            String actual = (String) h.resolve.invoke(null, token, entry.getValue());
            String expected = phase.equals("refreshed-expired") ? "absent" : "9";
            boolean matched = expected.equals(actual);
            probe.getLogger().info("VARIABLE_EXPIRY_SNAPSHOT|phase=" + phase + "|path=" + (h.imported ? "imported" : "direct")
                    + "|runtime=" + actual + "|matched=" + matched);
            if (!matched) throw new IllegalStateException("Snapshot lost its variable deadline");
            snapshotChecks++;
        }
    }

    private void cleanup() throws Exception {
        Exception failure = null;
        for (Sample sample : samples) {
            try { sample.h().unset(sample.scope() + "." + sample.name(), sample.meta(), sample.context(), sample.h().b); }
            catch (Exception error) { failure = error; }
        }
        samples.clear();
        for (var h : harnesses) {
            try { h.close(); } catch (Exception error) { failure = error; }
        }
        for (boolean imported : List.of(false, true))
            java.nio.file.Files.deleteIfExists(probe.getDataFolder().toPath().resolve("expiry-" + imported + ".yml"));
        for (var chunk : tickets) chunk.removePluginChunkTicket(probe);
        tickets.clear();
        restored.clear();
        harnesses.clear();
        if (failure != null) throw failure;
    }
}
