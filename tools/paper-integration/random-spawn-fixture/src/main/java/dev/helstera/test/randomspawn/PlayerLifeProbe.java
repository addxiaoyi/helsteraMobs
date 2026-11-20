package dev.helstera.test.randomspawn;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.plugin.java.JavaPlugin;
import java.util.*;

/** Real death/respawn and reconnect sessions, with untouched native profile lifecycle. */
public final class PlayerLifeProbe extends JavaPlugin implements Listener {
    private static final List<String> NAMES = List.of("VariableOwner", "VariablePeer");
    private static final List<String> CASES = List.of("Default", "Saved", "Transient", "Negative", "Timed", "DeadExpiry",
            "Float", "Double", "String", "Location", "Deleted", "Generation");
    private final Map<Boolean, VariablePlaceholderAudit.Harness> harnesses = new HashMap<>();
    private final Map<String, Integer> joins = new HashMap<>();
    private final Map<String, Long> quitTimes = new HashMap<>();
    private final Set<String> respawned = new HashSet<>();
    private YamlConfiguration saved;
    private boolean running, failed, done;
    private String phase = "write";
    @Override public void onEnable() {
        saved = new YamlConfiguration(); Bukkit.getPluginManager().registerEvents(this, this);
    }
    @EventHandler public void join(PlayerJoinEvent event) {
        Player p = event.getPlayer(); if (!NAMES.contains(p.getName())) return;
        int attempt = joins.merge(p.getName(), 1, Integer::sum);
        long gap = quitTimes.containsKey(p.getName()) ? (System.nanoTime() - quitTimes.get(p.getName())) / 1_000_000 : -1;
        getLogger().info("PLAYER_LIFE_SESSION|event=join|name=" + p.getName() + "|uuid=" + p.getUniqueId() + "|attempt=" + attempt + "|gapMillis=" + gap);
        Bukkit.getScheduler().runTaskLater(this, this::attempt, 2);
    }
    @EventHandler(priority = EventPriority.HIGHEST) public void death(PlayerDeathEvent event) {
        Player p = event.getPlayer(); if (!NAMES.contains(p.getName()) || failed) return;
        getLogger().info("PLAYER_LIFE_EVENT|event=death|name=" + p.getName() + "|uuid=" + p.getUniqueId() + "|dead=" + p.isDead());
        try { hook(p, 1); } catch (Throwable e) { fail(e); }
    }
    @EventHandler(priority = EventPriority.HIGHEST) public void respawn(PlayerRespawnEvent event) {
        Player p = event.getPlayer(); if (!NAMES.contains(p.getName()) || failed) return;
        respawned.add(p.getName());
        getLogger().info("PLAYER_LIFE_EVENT|event=respawn|name=" + p.getName() + "|uuid=" + p.getUniqueId());
        try { hook(p, 2); } catch (Throwable e) { fail(e); }
        Bukkit.getScheduler().runTaskLater(this, this::attempt, 5);
    }
    private void hook(Player p, int expected) throws Exception {
        for (var entry : harnesses.entrySet()) {
            var h = entry.getValue(); String hook = "LifeHook" + (entry.getKey() ? "I" : "D");
            Object ctx = h.context(p, p, p, p.getLocation(), h.newTree(p, p, p.getLocation()));
            h.execute("setvariable{var=caster." + hook + ";value=" + expected + ";save=true}", "VariableSetMechanic", h.meta(p, p), ctx, p);
            Object profile = h.active.get(p);
            Object registry = profile.getClass().getMethod("getVariables").invoke(profile);
            Object variable = registry.getClass().getMethod("get", String.class).invoke(registry, hook);
            Object nativeValue = variable.getClass().getMethod("get").invoke(variable);
            String runtimeValue = (String) h.resolve.invoke(null, "<caster.var." + hook + ">", ctx);
            if (!Integer.valueOf(expected).equals(nativeValue) || !Integer.toString(expected).equals(runtimeValue))
                throw new IllegalStateException("Event write differs");
            getLogger().info("PLAYER_LIFE_EVENT_WRITE|name=" + p.getName() + "|path=" + (entry.getKey() ? "imported" : "direct")
                    + "|value=" + expected + "|native=" + nativeValue + "|runtime=" + runtimeValue);
        }
    }
    @EventHandler(priority = EventPriority.MONITOR) public void quit(PlayerQuitEvent event) {
        Player p = event.getPlayer(); if (!NAMES.contains(p.getName())) return;
        quitTimes.put(p.getName(), System.nanoTime());
        getLogger().info("PLAYER_LIFE_SESSION|event=quit|name=" + p.getName() + "|uuid=" + p.getUniqueId() + "|attempt=" + joins.get(p.getName()));
        try {
            var mythic = Bukkit.getPluginManager().getPlugin("MythicMobs");
            Object manager = mythic.getClass().getMethod("getPlayerManager").invoke(mythic);
            boolean nativeEmpty = ((Optional<?>) manager.getClass().getMethod("getProfile", UUID.class).invoke(manager, p.getUniqueId())).isEmpty();
            var h = harnesses.get(false);
            Object state = h.runtimeType("dev.helstera.ai.skill.SkillExtras").getMethod("scopedVariableSnapshot", String.class).invoke(null, "player:" + p.getUniqueId());
            boolean runtimeEmpty = ((Map<?,?>) state.getClass().getMethod("variables").invoke(state)).isEmpty() && ((Map<?,?>) state.getClass().getMethod("locations").invoke(state)).isEmpty();
            getLogger().info("PLAYER_LIFE_UNLOADED|name=" + p.getName() + "|attempt=" + joins.get(p.getName()) + "|native=" + nativeEmpty + "|runtime=" + runtimeEmpty);
            if (!nativeEmpty || !runtimeEmpty) throw new IllegalStateException("Offline registry retained");
        } catch (Throwable e) { fail(e); }
    }
    private void fail(Throwable e) { failed = true; getLogger().severe("PLAYER_LIFE_FAILED|phase=" + phase + "|error=" + e); e.printStackTrace(); }
    private int generation() { return switch (phase) { case "write", "respawn" -> 1; case "reconnect1" -> 2; case "reconnect2" -> 3; case "reconnect3" -> 4; default -> 5; }; }
    private void attempt() {
        if (failed || running || done) return;
        int expected = switch (phase) { case "write", "respawn" -> 1; case "reconnect1" -> 2; case "reconnect2" -> 3; case "reconnect3" -> 4; default -> 5; };
        if (phase.equals("respawn") && respawned.size() != 2) return;
        if (NAMES.stream().anyMatch(name -> Bukkit.getPlayerExact(name) == null || !Objects.equals(joins.get(name), expected) || Bukkit.getPlayerExact(name).isDead())) return;
        try {
            Object mythic = Bukkit.getPluginManager().getPlugin("MythicMobs"); Object manager = mythic.getClass().getMethod("getPlayerManager").invoke(mythic);
            for (String name : NAMES) if (manager.getClass().getMethod("getProfile", Player.class).invoke(manager, Bukkit.getPlayerExact(name)) == null) {
                Bukkit.getScheduler().runTaskLater(this, this::attempt, 1); return;
            }
            running = true; observe(); running = false;
        } catch (Throwable e) { fail(e); }
    }
    private void observe() throws Exception {
        var runtime = Bukkit.getPluginManager().getPlugin("helsteraMobs");
        var mythic = Bukkit.getPluginManager().getPlugin("MythicMobs");
        if (phase.equals("write")) {
            Object config = mythic.getClass().getMethod("getConfiguration").invoke(mythic);
            int interval = ((Number) config.getClass().getMethod("getClockIntervalSaving").invoke(config)).intValue();
            if (interval != 300 || runtime.getConfig().getInt("variables.player-autosave-seconds") != 300)
                throw new IllegalStateException("Unexpected save interval");
            getLogger().info("PLAYER_LIFE_CLOCK|nativeSeconds=300|runtimeSeconds=300");
        }
        Object manager = mythic.getClass().getMethod("getPlayerManager").invoke(mythic);
        int count = 0;
        for (boolean imported : List.of(false, true)) {
            var h = harnesses.get(imported);
            if (h == null) { h = new VariablePlaceholderAudit.Harness(this, runtime, mythic, Bukkit.getPlayerExact(NAMES.getFirst()).getLocation(), imported); harnesses.put(imported, h); }
            for (String name : NAMES) { Player p = Bukkit.getPlayerExact(name); h.active.put(p, manager.getClass().getMethod("getProfile", Player.class).invoke(manager, p)); }
            for (int actor = 0; actor < 2; actor++) {
                Player caster = Bukkit.getPlayerExact(NAMES.get(actor)), target = Bukkit.getPlayerExact(NAMES.get(1 - actor));
                Location origin = target.getLocation();
                Object ctx = h.context(caster, target, caster, origin, h.newTree(caster, target, origin));
                Object meta = h.meta(caster, caster);
                h.metadataType.getMethod("setEntityTarget", h.abstractEntity).invoke(meta, h.adapt.invoke(null, target));
                Class<?> locationType = h.nativeType("io.lumine.mythic.api.adapters.AbstractLocation");
                h.metadataType.getMethod("setOrigin", locationType).invoke(meta, h.nativeType("io.lumine.mythic.bukkit.BukkitAdapter").getMethod("adapt", Location.class).invoke(null, origin));
                for (String scope : List.of("caster", "target")) for (String kind : CASES) {
                    String name = "Life" + (imported ? "I" : "D") + actor + scope + kind, variable = scope + "." + name;
                    Player subject = scope.equals("caster") ? caster : target, other = scope.equals("caster") ? target : caster;
                    if (phase.equals("write")) seed(h, variable, kind, actor, meta, ctx, target);
                    Object registry = h.active.get(subject).getClass().getMethod("getVariables").invoke(h.active.get(subject));
                    Object nv = registry.getClass().getMethod("get", String.class).invoke(registry, name);
                    Object n = nv == null ? null : nv.getClass().getMethod("get").invoke(nv);
                    Class<?> extras = h.runtimeType("dev.helstera.ai.skill.SkillExtras");
                    var raw = extras.getDeclaredMethod("placeholderVariable", h.contextType, String.class, String.class); raw.setAccessible(true);
                    Object r = raw.invoke(null, ctx, scope, name);
                    var ref = Arrays.stream(extras.getDeclaredClasses()).filter(t -> t.getSimpleName().equals("VariableRef")).findFirst().orElseThrow();
                    var ctor = ref.getDeclaredConstructor(String.class, String.class); ctor.setAccessible(true);
                    var expiry = extras.getDeclaredMethod("variableExpiration", h.contextType, ref); expiry.setAccessible(true);
                    long nd = nv == null ? 0 : ((Number) nv.getClass().getMethod("getExpirationTime").invoke(nv)).longValue();
                    long rd = ((Number) expiry.invoke(null, ctx, ctor.newInstance(name, scope))).longValue();
                    boolean expected = kind.equals("Generation") || phase.equals("write") || !List.of("cleared", "final").contains(phase) && !List.of("Deleted", "DeadExpiry").contains(kind);
                    String nvText = render(n), rvText = render(r), type = n == null ? "absent" : n.getClass().getSimpleName();
                    if (phase.equals("write")) { saved.set(name + ".nativeDeadline", nd); saved.set(name + ".runtimeDeadline", rd); saved.set(name + ".value", nvText); }
                    String value = kind.equals("Generation") ? Integer.toString(actor * 10 + generation())
                            : saved.getString(name + ".value");
                    boolean matched = expected == (n != null) && expected == (r != null) && nvText.equals(rvText);
                    if (expected) {
                        matched &= nvText.equals(value) && (kind.equals("Location") || n.getClass().equals(r.getClass()));
                        matched &= phase.equals("write") ? (nd > 1 ? Math.abs(nd - rd) < 1000 : nd == rd)
                                : nd == saved.getLong(name + ".nativeDeadline") && rd == saved.getLong(name + ".runtimeDeadline");
                    }
                    Object otherRegistry = h.active.get(other).getClass().getMethod("getVariables").invoke(h.active.get(other));
                    boolean isolated = otherRegistry.getClass().getMethod("get", String.class).invoke(otherRegistry, name) == null
                            && raw.invoke(null, ctx, scope.equals("caster") ? "target" : "caster", name) == null;
                    String bound = (String) h.skills.getClass().getMethod("bindCondition", String.class).invoke(h.skills, "variable-is-set{var=" + variable + "}");
                    matched &= isolated && expected == (boolean) h.registry.getClass().getMethod("testCondition", String.class, h.contextType).invoke(h.registry, bound, ctx);
                    getLogger().info("PLAYER_LIFE|phase=" + phase + "|actor=" + actor + "|scope=" + scope + "|case=" + kind + "|path=" + (imported ? "imported" : "direct")
                            + "|uuid=" + subject.getUniqueId() + "|expected=" + expected + "|native=" + nvText + "|runtime=" + rvText + "|nativeType=" + type
                            + "|runtimeType=" + (r == null ? "absent" : r.getClass().getSimpleName())
                            + "|nativeDeadline=" + nd + "|runtimeDeadline=" + rd + "|isolated=" + isolated + "|matched=" + matched);
                    if (!matched) throw new IllegalStateException("Player lifecycle differs: " + variable);
                    if (phase.equals("write") && kind.equals("Deleted") || phase.equals("reconnect3") && !kind.equals("Generation")) h.unset(variable, meta, ctx, target);
                    if (kind.equals("Generation") && List.of("respawn", "reconnect1", "reconnect2", "reconnect3").contains(phase))
                        h.execute("setvariable{var=" + variable + ";save=true;value=" + (actor * 10 + generation() + 1) + "}", "VariableSetMechanic", meta, ctx, target);
                    count++;
                }
                Object self = h.context(caster, caster, caster, caster.getLocation(), h.newTree(caster, caster, caster.getLocation()));
                String hook = "LifeHook" + (imported ? "I" : "D");
                if (phase.equals("write")) h.execute("setvariable{var=caster." + hook + ";save=true;value=0}", "VariableSetMechanic", h.meta(caster, caster), self, caster);
                Object profile = h.active.get(caster); Object registry = profile.getClass().getMethod("getVariables").invoke(profile);
                Object v = registry.getClass().getMethod("get", String.class).invoke(registry, hook);
                int value = ((Number) v.getClass().getMethod("get").invoke(v)).intValue();
                String runtimeValue = (String) h.resolve.invoke(null, "<caster.var." + hook + ">", self);
                int expected = phase.equals("write") ? 0 : 2;
                if (value != expected || !runtimeValue.equals(Integer.toString(expected))) throw new IllegalStateException("Lost death/respawn hook");
                getLogger().info("PLAYER_LIFE_HOOK|phase=" + phase + "|name=" + caster.getName() + "|path=" + (imported ? "imported" : "direct") + "|native=" + value + "|runtime=" + runtimeValue + "|passed=true");
                count++;
            }
        }
        saved.save(getDataFolder().toPath().resolve("observations.yml").toFile());
        getLogger().info("PLAYER_LIFE_PHASE|phase=" + phase + "|observations=" + count + "|online=" + Bukkit.getOnlinePlayers().size() + "|passed=true");
        if (phase.equals("write")) {
            phase = "respawn";
            Bukkit.getScheduler().runTaskLater(this, () -> {
                for (String name : NAMES) {
                    Player p = Bukkit.getPlayerExact(name); p.setGameMode(org.bukkit.GameMode.SURVIVAL); p.setInvulnerable(false); p.setHealth(0);
                }
            }, 5);
        } else if (phase.equals("reconnect3")) { phase = "cleared"; observe(); }
        else if (phase.equals("final")) {
            getLogger().info("PLAYER_LIFE_SUMMARY|observations=700|passed=true"); done = true;
            for (String name : NAMES) Bukkit.getPlayerExact(name).sendMessage("PLAYER_LIFE_CONTROL done final");
        } else {
            String completed = phase;
            phase = switch (phase) { case "respawn" -> "reconnect1"; case "reconnect1" -> "reconnect2"; case "reconnect2" -> "reconnect3"; case "cleared" -> "final"; default -> throw new IllegalStateException(phase); };
            for (String name : NAMES) Bukkit.getPlayerExact(name).sendMessage("PLAYER_LIFE_CONTROL reconnect " + completed);
        }
    }
    private String render(Object value) throws Exception {
        if (value == null) return "absent";
        if (value instanceof Location point) return point.getWorld().getName() + "," + point.getX() + "," + point.getY() + "," + point.getZ();
        if (value.getClass().getSimpleName().contains("Location")) {
            Object world = value.getClass().getMethod("getWorld").invoke(value);
            return world.getClass().getMethod("getName").invoke(world) + "," + value.getClass().getMethod("getX").invoke(value)
                    + "," + value.getClass().getMethod("getY").invoke(value) + "," + value.getClass().getMethod("getZ").invoke(value);
        }
        return value.toString();
    }
    private void seed(VariablePlaceholderAudit.Harness h, String variable, String kind, int actor, Object meta, Object ctx, Player target) throws Exception {
        if (kind.equals("Location")) { h.execute("setvariablelocation{var=" + variable + ";value=@origin;save=true}", "VariableSetLocationMechanic", meta, ctx, target); return; }
        String type = switch (kind) { case "Float" -> "FLOAT"; case "Double" -> "DOUBLE"; case "String" -> "STRING"; default -> "INTEGER"; };
        String value = switch (kind) { case "Float", "Double" -> String.valueOf(3.25 + actor); case "String" -> "saved-" + actor; case "Generation" -> Integer.toString(actor * 10 + 1); default -> Integer.toString(actor * 10 + 7); };
        String args = switch (kind) { case "Default" -> ""; case "Transient" -> ";save=false"; case "Negative" -> ";save=false;duration=-1";
            case "Timed" -> ";save=false;duration=12000"; case "DeadExpiry" -> ";save=true;duration=40"; default -> ";save=true"; };
        h.execute("setvariable{var=" + variable + ";type=" + type + ";value=" + value + args + "}", "VariableSetMechanic", meta, ctx, target);
    }
}
