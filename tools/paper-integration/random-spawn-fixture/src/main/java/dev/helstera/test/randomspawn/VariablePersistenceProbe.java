package dev.helstera.test.randomspawn;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import java.util.*;

/** Three real boots through native public mechanics and Helstera direct/imported actions. */
public final class VariablePersistenceProbe extends JavaPlugin {
    private static final List<String> SCOPES = List.of("caster", "target", "world", "global");
    private static final List<String> CASES = List.of("Default", "Save", "Transient", "ZeroSave", "ZeroTransient",
            "Negative", "TimedSave", "TimedTransient", "Expired", "ReplaceTransient", "ReplaceSaved",
            "AddSave", "AddTransient", "AddRefresh", "Float", "Double", "String", "Location");
    private final List<VariablePlaceholderAudit.Harness> harnesses = new ArrayList<>();
    @Override public void onEnable() {
        Bukkit.getScheduler().runTaskLater(this, () -> {
            try {
                if (!getConfig().getString("stage", "write").equals("write")) {
                    var ids = YamlConfiguration.loadConfiguration(getDataFolder().toPath().resolve("identities.yml").toFile());
                    for (String id : ids.getConfigurationSection("chunks").getKeys(false)) {
                        String path = "chunks." + id;
                        var world = Objects.requireNonNull(Bukkit.getWorld(UUID.fromString(ids.getString(path + ".world"))));
                        var chunk = world.getChunkAt(ids.getInt(path + ".x"), ids.getInt(path + ".z"));
                        chunk.load(); chunk.setForceLoaded(true); chunk.getEntities();
                    }
                }
                Bukkit.getScheduler().runTaskLater(this, () -> {
                    try { runProbe(); }
                    catch (Throwable e) { getLogger().severe("VARIABLE_PERSIST_SUMMARY|passed=false|error=" + e); e.printStackTrace(); }
                }, 40);
            }
            catch (Throwable e) { getLogger().severe("VARIABLE_PERSIST_SUMMARY|passed=false|error=" + e); e.printStackTrace(); }
        }, 100);
    }
    private void runProbe() throws Exception {
        String stage = getConfig().getString("stage", "write");
        boolean observeOnly = getConfig().getBoolean("observe-only", false);
        var runtime = Bukkit.getPluginManager().getPlugin("helsteraMobs");
        var mythic = Bukkit.getPluginManager().getPlugin("MythicMobs");
        var file = getDataFolder().toPath().resolve("identities.yml").toFile();
        var ids = YamlConfiguration.loadConfiguration(file);
        int rows = 0;
        for (boolean imported : List.of(false, true)) {
            List<UUID> saved = ids.getStringList("entities." + imported).stream().map(UUID::fromString).toList();
            if (!stage.equals("write") && saved.size() != 4) throw new IllegalStateException("Missing persisted identities");
            Location center = Bukkit.getWorlds().getFirst().getSpawnLocation().clone().add(imported ? 8 : 0, 0, 0);
            var h = new VariablePlaceholderAudit.Harness(this, runtime, mythic, center, imported,
                    stage.equals("write") ? List.of() : saved);
            harnesses.add(h);
            for (var cow : h.mobs) {
                cow.setPersistent(true); cow.setRemoveWhenFarAway(false); cow.getLocation().getChunk().setForceLoaded(true);
                String path = "chunks." + cow.getUniqueId();
                ids.set(path + ".world", cow.getWorld().getUID().toString());
                ids.set(path + ".x", cow.getLocation().getChunk().getX());
                ids.set(path + ".z", cow.getLocation().getChunk().getZ());
                Object active = h.active.get(cow);
                Object mode = active.getClass().getMethod("getDespawnMode").invoke(active);
                if (!mode.toString().equals("PERSISTENT")) throw new IllegalStateException("Unexpected despawn mode: " + mode);
                getLogger().info("VARIABLE_PERSIST_ENTITY|stage=" + stage + "|id=" + cow.getUniqueId() + "|mode=" + mode);
            }
            ids.set("entities." + imported, h.mobs.stream().map(m -> m.getUniqueId().toString()).toList());
            Object ctx = h.context(h.a, h.b, h.c, h.a.getLocation(), h.newTree(h.c, h.b, h.a.getLocation()));
            Object meta = h.meta(h.a, h.c);
            h.metadataType.getMethod("setEntityTarget", h.abstractEntity).invoke(meta, h.adapt.invoke(null, h.b));
            var nativeLocation = h.nativeType("io.lumine.mythic.api.adapters.AbstractLocation");
            Object point = h.nativeType("io.lumine.mythic.bukkit.BukkitAdapter").getMethod("adapt", Location.class).invoke(null, h.b.getLocation());
            h.metadataType.getMethod("setOrigin", nativeLocation).invoke(meta, point);
            for (String scope : SCOPES) for (String kind : CASES) {
                String name = "Persist" + (imported ? "I" : "D") + kind, variable = scope + "." + name;
                if (stage.equals("write")) seed(h, variable, kind, meta, ctx);
                Object manager = mythic.getClass().getMethod("getVariableManager").invoke(mythic);
                Class<?> scopeType = h.nativeType("io.lumine.mythic.core.skills.variables.VariableScope");
                Object registry = manager.getClass().getMethod("getRegistry", scopeType, h.placeholderMeta, h.abstractEntity)
                        .invoke(manager, scopeType.getField(scope.toUpperCase(Locale.ROOT)).get(null), meta, h.adapt.invoke(null, h.b));
                Object value = registry.getClass().getMethod("get", String.class).invoke(registry, name);
                boolean nativePresent = value != null;
                boolean expectedPresent = stage.equals("write") || stage.equals("verify") && !kind.equals("Expired")
                        && !(scope.equals("world") && List.of("Default", "Transient", "ZeroTransient", "ReplaceTransient", "AddSave").contains(kind));
                if (nativePresent != expectedPresent) throw new IllegalStateException("Native retention changed: " + stage + ":" + variable);
                String expectedValue = switch (kind) {
                    case "Float", "Double" -> "3.25";
                    case "String" -> "saved-string";
                    case "ReplaceTransient", "ReplaceSaved", "AddSave", "AddTransient", "AddRefresh" -> "9";
                    default -> "7";
                };
                boolean runtimePresent = (boolean) h.registry.getClass().getMethod("testCondition", String.class, h.contextType)
                        .invoke(h.registry, h.skills.getClass().getMethod("bindCondition", String.class)
                                .invoke(h.skills, "variable-is-set{var=" + variable + "}"), ctx);
                String nativeText = value == null ? "absent" : String.valueOf(value.getClass().getMethod("get").invoke(value));
                String runtimeText = (String) h.resolve.invoke(null, "<" + scope + ".var." + name + "|absent>", ctx);
                if (nativePresent && !kind.equals("Location") && !expectedValue.equals(nativeText))
                    throw new IllegalStateException("Unexpected native value: " + variable);
                var rawMethod = h.runtimeType("dev.helstera.ai.skill.SkillExtras").getDeclaredMethod("placeholderVariable", h.contextType, String.class, String.class);
                rawMethod.setAccessible(true);
                Object runtimeValue = rawMethod.invoke(null, ctx, scope, name);
                String metadataPath = "retention." + imported + "." + scope + "." + kind;
                long nativeDeadline = value == null ? 0 : ((Number) value.getClass().getMethod("getExpirationTime").invoke(value)).longValue();
                var refType = Arrays.stream(h.runtimeType("dev.helstera.ai.skill.SkillExtras").getDeclaredClasses())
                        .filter(type -> type.getSimpleName().equals("VariableRef")).findFirst().orElseThrow();
                var refConstructor = refType.getDeclaredConstructor(String.class, String.class); refConstructor.setAccessible(true);
                var expirationMethod = h.runtimeType("dev.helstera.ai.skill.SkillExtras").getDeclaredMethod("variableExpiration", h.contextType, refType);
                expirationMethod.setAccessible(true);
                long runtimeDeadline = ((Number) expirationMethod.invoke(null, ctx, refConstructor.newInstance(name, scope))).longValue();
                if (stage.equals("write")) {
                    ids.set(metadataPath + ".native", nativeDeadline); ids.set(metadataPath + ".runtime", runtimeDeadline);
                    if (nativePresent) ids.set(metadataPath + ".type", value.getClass().getMethod("get").invoke(value).getClass().getName());
                } else if (nativePresent && nativeDeadline != ids.getLong(metadataPath + ".native")) {
                    throw new IllegalStateException("Native deadline was reset: " + variable);
                }
                // Location text uses different object formatting; exact coordinates are checked separately.
                boolean equal = nativePresent == runtimePresent && (kind.equals("Location") || nativeText.equals(runtimeText));
                if (nativePresent && runtimePresent) {
                    equal &= stage.equals("write") ? (nativeDeadline > 1 && runtimeDeadline > 1
                            ? Math.abs(nativeDeadline - runtimeDeadline) < 1000 : nativeDeadline == runtimeDeadline)
                            : runtimeDeadline == ids.getLong(metadataPath + ".runtime");
                    if (!kind.equals("Location")) equal &= runtimeValue.getClass().getName().equals(ids.getString(metadataPath + ".type"));
                }
                if (kind.equals("Location") && nativePresent && runtimePresent) {
                    Object loc = value.getClass().getMethod("get").invoke(value);
                    Optional<?> selected = (Optional<?>) h.runtimeType("dev.helstera.api.behavior.VariableLocations")
                            .getMethod("get", String.class, String.class, h.contextType).invoke(null, scope, name, ctx);
                    Location actual = (Location) selected.orElseThrow();
                    Object nativeWorld = loc.getClass().getMethod("getWorld").invoke(loc);
                    equal &= actual.getWorld() != null && actual.getWorld().getName().equals(nativeWorld.getClass().getMethod("getName").invoke(nativeWorld));
                    if (stage.equals("write")) {
                        ids.set(metadataPath + ".world", actual.getWorld().getName());
                        ids.set(metadataPath + ".x", actual.getX()); ids.set(metadataPath + ".y", actual.getY()); ids.set(metadataPath + ".z", actual.getZ());
                    } else {
                        equal &= actual.getWorld().getName().equals(ids.getString(metadataPath + ".world"))
                                && actual.getX() == ids.getDouble(metadataPath + ".x") && actual.getY() == ids.getDouble(metadataPath + ".y")
                                && actual.getZ() == ids.getDouble(metadataPath + ".z");
                    }
                    for (String axis : List.of("X", "Y", "Z")) equal &= ((Number) loc.getClass().getMethod("get" + axis).invoke(loc)).doubleValue()
                            == ((Number) Location.class.getMethod("get" + axis).invoke(actual)).doubleValue();
                }
                getLogger().info("VARIABLE_PERSIST|stage=" + stage + "|scope=" + scope + "|case=" + kind
                        + "|path=" + (imported ? "imported" : "direct") + "|nativePresent=" + nativePresent
                        + "|runtimePresent=" + runtimePresent + "|native=" + (kind.equals("Location") ? "location" : nativeText)
                        + "|runtime=" + (kind.equals("Location") ? "location" : runtimeText)
                        + "|expectedPresent=" + expectedPresent + "|nativeDeadline=" + nativeDeadline + "|runtimeDeadline=" + runtimeDeadline
                        + "|matched=" + equal);
                if (!observeOnly && !equal) throw new IllegalStateException("Persistence differs: " + scope + "." + kind);
                if (stage.equals("verify")) h.unset(variable, meta, ctx, h.b);
                rows++;
            }
        }
        ids.save(file);
        getLogger().info("VARIABLE_PERSIST_SUMMARY|stage=" + stage + "|observations=" + rows
                + "|mode=" + (observeOnly ? "native-observation" : "comparison") + "|passed=true");
    }
    private void seed(VariablePlaceholderAudit.Harness h, String variable, String kind, Object meta, Object ctx) throws Exception {
        if (kind.equals("Location")) {
            h.execute("setvariablelocation{var=" + variable + ";value=@origin;save=true}", "VariableSetLocationMechanic", meta, ctx, h.b); return;
        }
        String type = switch (kind) { case "Float" -> "FLOAT"; case "Double" -> "DOUBLE"; case "String" -> "STRING"; default -> "INTEGER"; };
        String value = switch (kind) { case "Float", "Double" -> "3.25"; case "String" -> "saved-string"; default -> "7"; };
        String args = switch (kind) {
            case "Save", "ReplaceTransient", "AddTransient", "Float", "Double", "String" -> ";save=true";
            case "Transient" -> ";save=false";
            case "ZeroSave" -> ";save=true;duration=0";
            case "ZeroTransient" -> ";save=false;duration=0";
            case "Negative" -> ";save=false;duration=-1";
            case "TimedSave" -> ";save=true;duration=12000";
            case "TimedTransient" -> ";save=false;duration=12000";
            case "Expired" -> ";save=true;duration=400";
            default -> "";
        };
        h.execute("setvariable{var=" + variable + ";type=" + type + ";value=" + value + args + "}", "VariableSetMechanic", meta, ctx, h.b);
        if (kind.startsWith("Replace")) h.execute("setvariable{var=" + variable + ";value=9;save=" + kind.equals("ReplaceSaved") + "}", "VariableSetMechanic", meta, ctx, h.b);
        if (kind.startsWith("Add")) h.execute("variableadd{var=" + variable + ";amount=2;save=" + kind.equals("AddSave")
                + (kind.equals("AddRefresh") ? ";duration=12000" : "") + "}", "VariableAddMechanic", meta, ctx, h.b);
    }
}
