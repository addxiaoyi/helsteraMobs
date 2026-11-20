package dev.helstera.test.randomspawn;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import java.util.*;

/** Native clocks run normally; this probe only writes/reads variables and never calls a save API. */
public final class PlayerAutosaveProbe extends JavaPlugin implements Listener {
    private static final List<String> NAMES = List.of("VariableOwner", "VariablePeer");
    private static final List<String> CASES = List.of("Default", "Saved", "Transient", "Negative", "Timed", "OfflineExpiry",
            "Float", "Double", "String", "Location", "Deleted", "Generation");
    private final Map<Boolean, VariablePlaceholderAudit.Harness> harnesses = new HashMap<>();
    private YamlConfiguration saved;
    private boolean started;
    private String phase;
    @Override public void onEnable() {
        phase = getConfig().getString("stage");
        saved = YamlConfiguration.loadConfiguration(getDataFolder().toPath().resolve("observations.yml").toFile());
        Bukkit.getPluginManager().registerEvents(this, this);
    }
    @Override public void onDisable() { getLogger().info("PLAYER_AUTO_DISABLE|stage=" + getConfig().getString("stage")); }
    @EventHandler public void join(PlayerJoinEvent event) {
        if (!NAMES.contains(event.getPlayer().getName())) return;
        getLogger().info("PLAYER_AUTO_SESSION|event=join|name=" + event.getPlayer().getName() + "|uuid=" + event.getPlayer().getUniqueId());
        Bukkit.getScheduler().runTaskLater(this, this::begin, 40);
    }
    @EventHandler public void quit(PlayerQuitEvent event) {
        if (NAMES.contains(event.getPlayer().getName())) getLogger().info("PLAYER_AUTO_SESSION|event=quit|name=" + event.getPlayer().getName());
    }
    private void begin() {
        if (started || NAMES.stream().anyMatch(n -> Bukkit.getPlayerExact(n) == null)) return;
        try {
            Object mythic = Bukkit.getPluginManager().getPlugin("MythicMobs");
            Object manager = mythic.getClass().getMethod("getPlayerManager").invoke(mythic);
            for (String name : NAMES) if (manager.getClass().getMethod("getProfile", Player.class).invoke(manager, Bukkit.getPlayerExact(name)) == null) {
                Bukkit.getScheduler().runTaskLater(this, this::begin, 10); return;
            }
            Object config = mythic.getClass().getMethod("getConfiguration").invoke(mythic);
            int interval = ((Number) config.getClass().getMethod("getClockIntervalSaving").invoke(config)).intValue();
            if (interval != 5) throw new IllegalStateException("Native saving clock must be 5 seconds, got " + interval);
            getLogger().info("PLAYER_AUTO_CLOCK|nativeSeconds=" + interval + "|runtimeSeconds=" + Bukkit.getPluginManager().getPlugin("helsteraMobs").getConfig().getInt("variables.player-autosave-seconds"));
            started = true; observe();
        } catch (Throwable e) { fail(e); }
    }
    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof org.bukkit.command.ConsoleCommandSender) || args.length != 1) return false;
        if (!(phase.equals("write") && args[0].equals("updated") || phase.equals("recover") && args[0].equals("cleared"))) return false;
        phase = args[0];
        try { observe(); } catch (Throwable e) { fail(e); }
        return true;
    }
    private void fail(Throwable e) { getLogger().severe("PLAYER_AUTO_FAILED|phase=" + phase + "|error=" + e); e.printStackTrace(); }
    private void observe() throws Exception {
        var runtime = Bukkit.getPluginManager().getPlugin("helsteraMobs");
        var mythic = Bukkit.getPluginManager().getPlugin("MythicMobs");
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
                    String name = "Auto" + (imported ? "I" : "D") + actor + scope + kind, variable = scope + "." + name;
                    Player subject = scope.equals("caster") ? caster : target, other = scope.equals("caster") ? target : caster;
                    if (phase.equals("write")) seed(h, variable, kind, actor, meta, ctx, target);
                    if (phase.equals("updated") || phase.equals("cleared")) {
                        if (kind.equals("Generation")) h.execute("setvariable{var=" + variable + ";save=true;value=" + (actor * 10 + (phase.equals("updated") ? 2 : 3)) + "}", "VariableSetMechanic", meta, ctx, target);
                        else if (phase.equals("cleared") || kind.equals("Deleted")) h.unset(variable, meta, ctx, target);
                    }
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
                    boolean expected = kind.equals("Generation") || phase.equals("write") || phase.equals("updated") && !kind.equals("Deleted")
                            || phase.equals("recover") && !List.of("Deleted", "OfflineExpiry").contains(kind);
                    String nvText = render(n), rvText = render(r), type = n == null ? "absent" : n.getClass().getSimpleName();
                    if (phase.equals("write")) { saved.set(name + ".nativeDeadline", nd); saved.set(name + ".runtimeDeadline", rd); saved.set(name + ".value", nvText); }
                    String value = kind.equals("Generation") ? Integer.toString(actor * 10 + (phase.equals("write") ? 1 : List.of("updated", "recover").contains(phase) ? 2 : 3))
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
                    getLogger().info("PLAYER_AUTO|phase=" + phase + "|actor=" + actor + "|scope=" + scope + "|case=" + kind + "|path=" + (imported ? "imported" : "direct")
                            + "|uuid=" + subject.getUniqueId() + "|expected=" + expected + "|native=" + nvText + "|runtime=" + rvText + "|nativeType=" + type
                            + "|nativeDeadline=" + nd + "|runtimeDeadline=" + rd + "|isolated=" + isolated + "|matched=" + matched);
                    if (!matched) throw new IllegalStateException("Player autosave differs: " + variable);
                    count++;
                }
            }
        }
        saved.save(getDataFolder().toPath().resolve("observations.yml").toFile());
        getLogger().info("PLAYER_AUTO_PHASE|phase=" + phase + "|observations=" + count + "|online=" + Bukkit.getOnlinePlayers().size() + "|passed=true");
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
            case "Timed" -> ";save=false;duration=12000"; case "OfflineExpiry" -> ";save=true;duration=1200"; default -> ";save=true"; };
        h.execute("setvariable{var=" + variable + ";type=" + type + ";value=" + value + args + "}", "VariableSetMechanic", meta, ctx, target);
    }
}
