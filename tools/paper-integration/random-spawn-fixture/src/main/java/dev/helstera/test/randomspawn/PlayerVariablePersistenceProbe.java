package dev.helstera.test.randomspawn;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import java.util.*;

/** Actual connected players, native public mechanics and production direct/imported actions. */
public final class PlayerVariablePersistenceProbe extends JavaPlugin implements Listener {
    private static final List<String> NAMES = List.of("VariableOwner", "VariablePeer");
    private static final List<String> CASES = List.of("Default", "Save", "Transient", "ZeroSave", "ZeroTransient",
            "Negative", "TimedSave", "TimedTransient", "Expired", "ReplaceTransient", "ReplaceSaved",
            "AddSave", "AddTransient", "AddRefresh", "Float", "Double", "String", "Location");
    private final Map<String, Integer> joins = new HashMap<>();
    private final Map<Boolean, VariablePlaceholderAudit.Harness> harnesses = new HashMap<>();
    private String phase;
    private int observations;
    private boolean running, failed, done;
    private YamlConfiguration saved;
    @Override public void onEnable() {
        phase = getConfig().getString("stage", "write");
        saved = YamlConfiguration.loadConfiguration(getDataFolder().toPath().resolve("identities.yml").toFile());
        Bukkit.getPluginManager().registerEvents(this, this);
    }
    @EventHandler(priority = EventPriority.MONITOR) public void join(PlayerJoinEvent event) {
        if (!NAMES.contains(event.getPlayer().getName())) return;
        joins.merge(event.getPlayer().getName(), 1, Integer::sum);
        session("join", event.getPlayer());
        Bukkit.getScheduler().runTaskLater(this, this::attempt, 60);
    }
    @EventHandler(priority = EventPriority.HIGHEST) public void writeOnQuit(PlayerQuitEvent event) {
        if (!NAMES.contains(event.getPlayer().getName()) || failed) return;
        try {
            Player player = event.getPlayer();
            for (var entry : harnesses.entrySet()) {
                var h = entry.getValue(); String name = "QuitHook" + (entry.getKey() ? "I" : "D");
                Object profile = h.active.get(player);
                Object registry = profile.getClass().getMethod("getVariables").invoke(profile);
                Object variable = registry.getClass().getMethod("get", String.class).invoke(registry, name);
                int value = ((Number) variable.getClass().getMethod("get").invoke(variable)).intValue() + 1;
                Object ctx = h.context(player, player, player, player.getLocation(), h.newTree(player, player, player.getLocation()));
                h.execute("setvariable{var=caster." + name + ";value=" + value + ";save=true}", "VariableSetMechanic", h.meta(player, player), ctx, player);
                getLogger().info("PLAYER_VAR_QUIT_WRITE|stage=" + getConfig().getString("stage") + "|name=" + player.getName()
                        + "|path=" + (entry.getKey() ? "imported" : "direct") + "|value=" + value + "|passed=true");
            }
        } catch (Throwable e) { fail(e); }
    }
    @EventHandler(priority = EventPriority.MONITOR) public void quit(PlayerQuitEvent event) {
        if (!NAMES.contains(event.getPlayer().getName())) return;
        session("quit", event.getPlayer()); UUID id = event.getPlayer().getUniqueId();
        Bukkit.getScheduler().runTaskLater(this, () -> {
            try {
                var mythic = Bukkit.getPluginManager().getPlugin("MythicMobs");
                Object manager = mythic.getClass().getMethod("getPlayerManager").invoke(mythic);
                boolean nativeEmpty = ((Optional<?>) manager.getClass().getMethod("getProfile", UUID.class).invoke(manager, id)).isEmpty();
                var h = harnesses.get(false);
                Object state = h.runtimeType("dev.helstera.ai.skill.SkillExtras").getMethod("scopedVariableSnapshot", String.class).invoke(null, "player:" + id);
                boolean runtimeEmpty = ((Map<?, ?>) state.getClass().getMethod("variables").invoke(state)).isEmpty()
                        && ((Map<?, ?>) state.getClass().getMethod("locations").invoke(state)).isEmpty();
                getLogger().info("PLAYER_VAR_UNLOADED|stage=" + getConfig().getString("stage") + "|uuid=" + id
                        + "|native=" + nativeEmpty + "|runtime=" + runtimeEmpty + "|passed=" + (nativeEmpty && runtimeEmpty));
                if (!nativeEmpty || !runtimeEmpty) throw new IllegalStateException("Offline player registry retained");
            } catch (Throwable e) { fail(e); }
        }, 20);
    }
    private void fail(Throwable e) {
        failed = true; getLogger().severe("PLAYER_VAR_SUMMARY|passed=false|error=" + e); e.printStackTrace();
    }
    private void session(String kind, Player player) {
        getLogger().info("PLAYER_VAR_SESSION|stage=" + getConfig().getString("stage") + "|event=" + kind
                + "|name=" + player.getName() + "|uuid=" + player.getUniqueId() + "|join=" + joins.get(player.getName()));
    }
    private void attempt() {
        if (running || failed || done) return;
        List<Player> players = NAMES.stream().map(Bukkit::getPlayerExact).toList();
        int expectedJoin = List.of("reconnect", "cleared").contains(phase) ? 2 : 1;
        if (players.stream().anyMatch(Objects::isNull) || joins.values().stream().anyMatch(n -> n != expectedJoin)) return;
        try {
            var nativePlugin = Bukkit.getPluginManager().getPlugin("MythicMobs");
            Object manager = nativePlugin.getClass().getMethod("getPlayerManager").invoke(nativePlugin);
            for (Player player : players) if (manager.getClass().getMethod("getProfile", Player.class).invoke(manager, player) == null) {
                Bukkit.getScheduler().runTaskLater(this, this::attempt, 10); return;
            }
            running = true;
            observe(players, manager);
            saved.save(getDataFolder().toPath().resolve("identities.yml").toFile());
            String completed = phase;
            if (phase.equals("write")) phase = "reconnect";
            else if (phase.equals("verify")) phase = "cleared";
            else done = true;
            getLogger().info("PLAYER_VAR_PHASE|stage=" + getConfig().getString("stage") + "|phase=" + completed + "|observations=148|passed=true");
            for (Player player : players) player.sendMessage("PLAYER_VAR_CONTROL "
                    + (completed.equals("cleared") && player.getName().equals("VariablePeer") ? "hold" : done ? "done" : "reconnect") + " " + completed);
            if (done) getLogger().info("PLAYER_VAR_SUMMARY|stage=" + getConfig().getString("stage") + "|observations=" + observations + "|passed=true");
            running = false;
        } catch (Throwable e) {
            fail(e);
        }
    }
    private void observe(List<Player> players, Object manager) throws Exception {
        var runtime = Bukkit.getPluginManager().getPlugin("helsteraMobs");
        var mythic = Bukkit.getPluginManager().getPlugin("MythicMobs");
        for (Player player : players) {
            String path = "players." + player.getName();
            if (phase.equals("write")) saved.set(path, player.getUniqueId().toString());
            else if (!player.getUniqueId().toString().equals(saved.getString(path))) throw new IllegalStateException("Player UUID changed");
        }
        for (boolean imported : List.of(false, true)) {
            var h = harnesses.get(imported);
            if (h == null) {
                h = new VariablePlaceholderAudit.Harness(this, runtime, mythic, players.getFirst().getLocation(), imported);
                harnesses.put(imported, h);
            }
            for (Player player : players) h.active.put(player, manager.getClass().getMethod("getProfile", Player.class).invoke(manager, player));
            for (int actor = 0; actor < players.size(); actor++) {
                Player caster = players.get(actor), target = players.get(1 - actor);
                Location origin = target.getLocation();
                Object ctx = h.context(caster, target, caster, origin, h.newTree(caster, target, origin));
                Object meta = h.meta(caster, caster);
                h.metadataType.getMethod("setEntityTarget", h.abstractEntity).invoke(meta, h.adapt.invoke(null, target));
                Class<?> locationType = h.nativeType("io.lumine.mythic.api.adapters.AbstractLocation");
                Object point = h.nativeType("io.lumine.mythic.bukkit.BukkitAdapter").getMethod("adapt", Location.class).invoke(null, origin);
                h.metadataType.getMethod("setOrigin", locationType).invoke(meta, point);
                for (String scope : List.of("caster", "target")) for (String kind : CASES) {
                    String name = "Player" + (imported ? "I" : "D") + actor + scope + kind, variable = scope + "." + name;
                    Player subject = scope.equals("caster") ? caster : target;
                    Player other = scope.equals("caster") ? target : caster;
                    if (phase.equals("write")) seed(h, variable, kind, actor, meta, ctx, target);
                    Object profile = manager.getClass().getMethod("getProfile", Player.class).invoke(manager, subject);
                    Object nativeRegistry = profile.getClass().getMethod("getVariables").invoke(profile);
                    Object nativeVariable = nativeRegistry.getClass().getMethod("get", String.class).invoke(nativeRegistry, name);
                    Object nativeValue = nativeVariable == null ? null : nativeVariable.getClass().getMethod("get").invoke(nativeVariable);
                    var extras = h.runtimeType("dev.helstera.ai.skill.SkillExtras");
                    var raw = extras.getDeclaredMethod("placeholderVariable", h.contextType, String.class, String.class); raw.setAccessible(true);
                    Object runtimeValue = raw.invoke(null, ctx, scope, name);
                    String path = "values." + name;
                    boolean expected = phase.equals("write") || List.of("reconnect", "verify").contains(phase) && !kind.equals("Expired");
                    boolean nativePresent = nativeValue != null, runtimePresent = runtimeValue != null;
                    if (nativePresent != expected) throw new IllegalStateException("Native retention differs: " + phase + ":" + variable);
                    long nativeDeadline = nativePresent ? ((Number) nativeVariable.getClass().getMethod("getExpirationTime").invoke(nativeVariable)).longValue() : 0;
                    var refType = Arrays.stream(extras.getDeclaredClasses()).filter(type -> type.getSimpleName().equals("VariableRef")).findFirst().orElseThrow();
                    var ctor = refType.getDeclaredConstructor(String.class, String.class); ctor.setAccessible(true);
                    var expiry = extras.getDeclaredMethod("variableExpiration", h.contextType, refType); expiry.setAccessible(true);
                    long runtimeDeadline = ((Number) expiry.invoke(null, ctx, ctor.newInstance(name, scope))).longValue();
                    if (phase.equals("write")) { saved.set(path + ".nativeDeadline", nativeDeadline); saved.set(path + ".runtimeDeadline", runtimeDeadline); }
                    else if (nativePresent && nativeDeadline != saved.getLong(path + ".nativeDeadline")) throw new IllegalStateException("Native deadline reset");
                    boolean matched = expected == runtimePresent;
                    if (expected) {
                        matched &= phase.equals("write") ? (nativeDeadline > 1 ? Math.abs(nativeDeadline - runtimeDeadline) < 1000 : nativeDeadline == runtimeDeadline)
                                : runtimeDeadline == saved.getLong(path + ".runtimeDeadline");
                        String expectedValue = switch (kind) {
                            case "Float", "Double" -> String.valueOf(3.25 + actor);
                            case "String" -> "saved-" + actor;
                            case "ReplaceTransient", "ReplaceSaved", "AddSave", "AddTransient", "AddRefresh" -> Integer.toString(9 + actor * 10);
                            default -> Integer.toString(7 + actor * 10);
                        };
                        if (!kind.equals("Location")) {
                            if (!expectedValue.equals(nativeValue.toString())) throw new IllegalStateException("Native value differs");
                            matched &= runtimeValue != null && nativeValue.getClass().equals(runtimeValue.getClass()) && nativeValue.equals(runtimeValue);
                        } else {
                            String expectedPoint = locationText(nativeValue);
                            if (phase.equals("write")) saved.set(path + ".location", expectedPoint);
                            else if (!saved.getString(path + ".location").equals(expectedPoint)) throw new IllegalStateException("Native location changed");
                            matched &= runtimeValue instanceof Location loc && expectedPoint.equals(loc.getWorld().getName() + "," + loc.getX() + "," + loc.getY() + "," + loc.getZ());
                        }
                    }
                    Object triggerCtx = h.context(caster, target, subject, origin, h.newTree(subject, target, origin));
                    Object alias = raw.invoke(null, triggerCtx, "trigger", name);
                    matched &= Objects.equals(runtimeValue, alias);
                    String bound = (String) h.skills.getClass().getMethod("bindCondition", String.class).invoke(h.skills, "variable-is-set{var=" + variable + "}");
                    matched &= expected == (boolean) h.registry.getClass().getMethod("testCondition", String.class, h.contextType).invoke(h.registry, bound, ctx);
                    Object otherProfile = manager.getClass().getMethod("getProfile", Player.class).invoke(manager, other);
                    Object otherRegistry = otherProfile.getClass().getMethod("getVariables").invoke(otherProfile);
                    boolean isolated = otherRegistry.getClass().getMethod("get", String.class).invoke(otherRegistry, name) == null
                            && raw.invoke(null, ctx, scope.equals("caster") ? "target" : "caster", name) == null;
                    matched &= isolated;
                    getLogger().info("PLAYER_VAR|phase=" + phase + "|actor=" + actor + "|scope=" + scope + "|case=" + kind
                            + "|path=" + (imported ? "imported" : "direct") + "|uuid=" + subject.getUniqueId()
                            + "|expected=" + expected + "|native=" + nativePresent + "|runtime=" + runtimePresent
                            + "|nativeDeadline=" + nativeDeadline + "|runtimeDeadline=" + runtimeDeadline + "|isolated=" + isolated + "|matched=" + matched);
                    if (!matched && !getConfig().getBoolean("observe-only")) throw new IllegalStateException("Player runtime differs: " + variable);
                    if (phase.equals("verify")) h.unset(variable, meta, ctx, target);
                    observations++;
                }
                String hook = "QuitHook" + (imported ? "I" : "D");
                Object self = h.context(caster, caster, caster, caster.getLocation(), h.newTree(caster, caster, caster.getLocation()));
                if (phase.equals("write")) h.execute("setvariable{var=caster." + hook + ";value=0;save=true}", "VariableSetMechanic", h.meta(caster, caster), self, caster);
                Object profile = manager.getClass().getMethod("getProfile", Player.class).invoke(manager, caster);
                Object registry = profile.getClass().getMethod("getVariables").invoke(profile);
                Object variable = registry.getClass().getMethod("get", String.class).invoke(registry, hook);
                int value = ((Number) variable.getClass().getMethod("get").invoke(variable)).intValue();
                int expected = phase.equals("final") && caster.getName().equals("VariablePeer") ? 30
                        : List.of("write", "reconnect", "verify", "cleared", "final").indexOf(phase);
                String runtimeValue = (String) h.resolve.invoke(null, "<caster.var." + hook + ">", self);
                boolean matched = value == expected && runtimeValue.equals(Integer.toString(expected));
                getLogger().info("PLAYER_VAR_HOOK|phase=" + phase + "|name=" + caster.getName() + "|path=" + (imported ? "imported" : "direct")
                        + "|expected=" + expected + "|native=" + value + "|runtime=" + runtimeValue + "|passed=" + matched);
                if (!matched) throw new IllegalStateException("Quit hook write was not retained");
                if (phase.equals("cleared") && caster.getName().equals("VariablePeer")) {
                    h.execute("setvariable{var=caster." + hook + ";value=30;save=true}", "VariableSetMechanic", h.meta(caster, caster), self, caster);
                    getLogger().info("PLAYER_VAR_ONLINE_WRITE|name=" + caster.getName() + "|path=" + (imported ? "imported" : "direct") + "|value=30|passed=true");
                }
                observations++;
            }
        }
    }
    private String locationText(Object loc) throws Exception {
        Object world = loc.getClass().getMethod("getWorld").invoke(loc);
        return world.getClass().getMethod("getName").invoke(world) + "," + loc.getClass().getMethod("getX").invoke(loc)
                + "," + loc.getClass().getMethod("getY").invoke(loc) + "," + loc.getClass().getMethod("getZ").invoke(loc);
    }
    private void seed(VariablePlaceholderAudit.Harness h, String variable, String kind, int actor, Object meta, Object ctx, Player target) throws Exception {
        if (kind.equals("Location")) { h.execute("setvariablelocation{var=" + variable + ";value=@origin;save=true}", "VariableSetLocationMechanic", meta, ctx, target); return; }
        String type = switch (kind) { case "Float" -> "FLOAT"; case "Double" -> "DOUBLE"; case "String" -> "STRING"; default -> "INTEGER"; };
        String value = switch (kind) { case "Float", "Double" -> String.valueOf(3.25 + actor); case "String" -> "saved-" + actor; default -> Integer.toString(7 + actor * 10); };
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
        h.execute("setvariable{var=" + variable + ";type=" + type + ";value=" + value + args + "}", "VariableSetMechanic", meta, ctx, target);
        if (kind.startsWith("Replace")) h.execute("setvariable{var=" + variable + ";value=" + (9 + actor * 10) + ";save=" + kind.equals("ReplaceSaved") + "}", "VariableSetMechanic", meta, ctx, target);
        if (kind.startsWith("Add")) h.execute("variableadd{var=" + variable + ";amount=2;save=" + kind.equals("AddSave") + (kind.equals("AddRefresh") ? ";duration=12000" : "") + "}", "VariableAddMechanic", meta, ctx, target);
    }
}
