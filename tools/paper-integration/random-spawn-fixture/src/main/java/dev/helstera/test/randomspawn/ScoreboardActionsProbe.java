package dev.helstera.test.randomspawn;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;

import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Actual main-scoreboard snapshots, using native constructors and imported/direct runtime binding. */
public final class ScoreboardActionsProbe extends JavaPlugin {
    private final Set<Chunk> tickets = new LinkedHashSet<>();
    private int observations, waits;
    private Player player;
    private Scoreboard board;
    private final List<Object> nativeActions = new ArrayList<>();
    private final List<String> nativeSources = new ArrayList<>();
    private int nativePreparedTick;

    @Override public void onEnable() { Bukkit.getScheduler().runTaskLater(this, this::begin, 100); }
    private void begin() {
        player = Bukkit.getPlayerExact("ScoreboardPlayer");
        if (player == null) {
            if (++waits > 300) { getLogger().severe("SCOREBOARD_FAILED|missing player"); return; }
            Bukkit.getScheduler().runTaskLater(this, this::begin, 5); return;
        }
        try {
            prepareNativeActions();
            Bukkit.getScheduler().runTaskLater(this, this::execute, 6);
        } catch (Throwable failure) {
            getLogger().severe("SCOREBOARD_FAILED|" + failure); failure.printStackTrace();
        }
    }

    private void prepareNativeActions() throws Exception {
        var mythic = Bukkit.getPluginManager().getPlugin("MythicMobs");
        var loader = mythic.getClass().getClassLoader();
        var executorType = Class.forName("io.lumine.mythic.core.skills.SkillExecutor", true, loader);
        var configType = Class.forName("io.lumine.mythic.api.config.MythicLineConfig", true, loader);
        var configImpl = Class.forName("io.lumine.mythic.core.config.MythicLineConfigImpl", true, loader);
        var manager = mythic.getClass().getMethod("getSkillManager").invoke(mythic);
        var sourceFile = getDataFolder().toPath().resolve("variable-source/Skills/variables.yml");
        Files.createDirectories(sourceFile.getParent());
        var input = YamlConfiguration.loadConfiguration(getDataFolder().toPath().resolve("cases.yml").toFile());
        for (String path : List.of("direct", "imported")) {
            for (Map<?, ?> sample : input.getMapList("cases")) {
                String mode = sample.get("objective").toString();
                String objective = mode.equals("target") || mode.equals("no-target") ? "<target.uuid>"
                        : mode.equals("trigger") ? "<trigger.uuid>" : "ScoreAudit" + nativeActions.size();
                String arguments = sample.get("arguments").toString();
                if (!mode.contains("empty")) arguments = "o=" + objective + (arguments.isEmpty() ? "" : ";" + arguments);
                String source = sample.get("mechanic") + "{" + arguments + "}";
                nativeSources.add(source);
                nativeActions.add(Class.forName("io.lumine.mythic.core.skills.mechanics."
                                + family(sample.get("mechanic").toString()) + "Mechanic", true, loader)
                        .getConstructor(executorType, java.io.File.class, String.class, configType)
                        .newInstance(manager, sourceFile.toFile(), source, configImpl.getConstructor(String.class).newInstance(source)));
            }
        }
        // Trigger/caster placeholder delegates initialize through MythicClock.queueAfterLoad.
        // Let the real scheduler resolve them before invoking the same mechanic instances.
        nativePreparedTick = Bukkit.getCurrentTick();
        getLogger().info("SCOREBOARD_PREPARED|actions=" + nativeActions.size() + "|tick=" + nativePreparedTick);
    }

    private void execute() {
        try {
            if (Bukkit.getCurrentTick() - nativePreparedTick < 6)
                throw new IllegalStateException("Native placeholders have not passed six scheduler ticks");
            run();
            getLogger().info("SCOREBOARD_SUMMARY|observations=" + observations + "|completed=true");
            player.sendMessage("SCOREBOARD_DONE");
        } catch (Throwable failure) {
            getLogger().severe("SCOREBOARD_FAILED|" + failure); failure.printStackTrace();
        } finally {
            for (Chunk chunk : tickets) chunk.removePluginChunkTicket(this);
        }
    }

    private void run() throws Exception {
        var runtime = Bukkit.getPluginManager().getPlugin("helsteraMobs");
        var mythic = Bukkit.getPluginManager().getPlugin("MythicMobs");
        board = Bukkit.getScoreboardManager().getMainScoreboard();
        var input = YamlConfiguration.loadConfiguration(getDataFolder().toPath().resolve("cases.yml").toFile());
        var cases = input.getMapList("cases");
        var center = new Location(Bukkit.getWorlds().getFirst(), 0, 150, 0);
        var base = center.clone().add(110, 20, 0);
        var other = base.clone(); other.setWorld(Bukkit.getWorlds().stream().filter(world -> world != base.getWorld()).findFirst().orElseThrow());
        for (Location point : List.of(base, base.clone().add(0, 0, 4), base.clone().add(4, 0, 0), other)) {
            Chunk chunk = point.getChunk(); chunk.getEntities();
            if (tickets.add(chunk)) chunk.addPluginChunkTicket(this);
        }
        for (String path : List.of("direct", "imported")) {
            try (var h = new VariablePlaceholderAudit.Harness(this, runtime, mythic, center, false)) {
                h.b.setHealth(10); h.b.setCustomName("8"); h.c.setCustomName("9");
                Interaction interaction = base.getWorld().spawn(base.clone().add(2, 0, 0), Interaction.class);
                try {
                    var identities = new YamlConfiguration();
                    identities.set("caster", h.a.getUniqueId().toString()); identities.set("target", h.b.getUniqueId().toString());
                    identities.set("trigger", h.c.getUniqueId().toString()); identities.set("other", h.d.getUniqueId().toString());
                    identities.set("interaction", interaction.getUniqueId().toString());
                    identities.set("player", player.getUniqueId().toString()); identities.set("playerName", player.getName());
                    identities.save(getDataFolder().toPath().resolve("identities-" + path + ".yml").toFile());
                    for (Map<?, ?> sample : cases) observe(h, path, sample, interaction);
                } finally { interaction.remove(); }
            }
        }
    }

    private void observe(VariablePlaceholderAudit.Harness h, String path, Map<?, ?> sample, Entity interaction) throws Exception {
        String key = sample.get("key").toString(), mechanic = sample.get("mechanic").toString();
        String family = family(mechanic), recipient = sample.get("recipient").toString();
        Entity selected = switch (recipient) { case "player" -> player; case "interaction" -> interaction; default -> h.b; };
        Entity trigger = recipient.equals("player") || recipient.equals("interaction") ? selected : h.c;
        List<Entity> targets = recipient.equals("multiple") ? List.of(h.b, h.c) : List.of(selected);
        String mode = sample.get("objective").toString();
        String objective = mode.equals("target") ? selected.getUniqueId().toString()
                : mode.equals("trigger") ? trigger.getUniqueId().toString()
                : mode.equals("no-target") ? "<target.uuid>" : mode.contains("empty") ? "" : "ScoreAudit" + observations;
        String objectiveArgument = mode.equals("target") || mode.equals("no-target") ? "o=<target.uuid>" : mode.equals("trigger")
                ? "o=<trigger.uuid>" : mode.contains("empty") ? "" : "o=" + objective;
        String arguments = sample.get("arguments").toString();
        if (!objectiveArgument.isEmpty()) arguments = objectiveArgument + (arguments.isEmpty() ? "" : ";" + arguments);
        String source = mechanic + "{" + arguments + "}" + sample.get("suffix");
        String nativeSource = mechanic + "{" + arguments + "}";
        String sentinel = "Sentinel" + observations;
        Map<String, Integer> seeds = new LinkedHashMap<>();
        seeds.put("__GLOBAL__", 101); seeds.put(h.a.getUniqueId().toString(), 102);
        seeds.put(h.b.getUniqueId().toString(), 103); seeds.put(h.c.getUniqueId().toString(), 104);
        seeds.put(h.d.getUniqueId().toString(), 105); seeds.put(player.getName(), 106);
        seeds.put(player.getUniqueId().toString(), 107); seeds.put(interaction.getUniqueId().toString(), 108);
        seeds.put("dummy", 109); seeds.put("custom", 110); seeds.put("other", 111);
        List<String> holders = family.contains("Global") ? List.of("__GLOBAL__") : family.contains("Mob")
                ? List.of(h.a.getUniqueId().toString()) : family.contains("Target")
                ? targets.stream().map(entity -> entity instanceof Player p ? p.getName() : entity.getUniqueId().toString()).toList()
                : List.of("trigger".equals(sample.get("holder")) ? trigger.getUniqueId().toString()
                : "caster".equals(sample.get("holder")) ? h.a.getUniqueId().toString()
                : sample.get("holder") == null ? "dummy" : sample.get("holder").toString());
        Object seed = sample.get("seed");
        if (seed == null) seeds.clear();
        else for (String holder : holders) seeds.put(holder, ((Number) seed).intValue());
        seed(objective, sentinel, seeds, seed != null);
        Map<String, Object> nativeBefore = snapshot(objective, sentinel);
        Object meta = h.meta(h.a, trigger);
        if (!nativeSource.equals(nativeSources.get(observations)))
            throw new IllegalStateException("Prepared mechanic source changed: " + nativeSource);
        Object nativeAction = nativeActions.get(observations);
        String nativeResult;
        try {
            if (family.contains("Target")) {
                nativeResult = "SUCCESS";
                for (Entity target : targets) nativeResult = nativeAction.getClass()
                        .getMethod("castAtEntity", h.skillMetadata, h.abstractEntity)
                        .invoke(nativeAction, meta, h.adapt.invoke(null, target)).toString();
            } else nativeResult = nativeAction.getClass().getMethod("cast", h.skillMetadata).invoke(nativeAction, meta).toString();
        } catch (InvocationTargetException failure) { nativeResult = failure.getCause().getClass().getSimpleName(); }
        Map<String, Object> nativeAfter = snapshot(objective, sentinel);
        seed(objective, sentinel, seeds, seed != null);
        Map<String, Object> runtimeBefore = snapshot(objective, sentinel);
        Object tree = h.newTree(trigger, h.b, h.a.getLocation());
        Object context = h.context(h.a, selected instanceof LivingEntity living ? living : null, trigger, h.a.getLocation(), tree);
        if (!(selected instanceof LivingEntity)) context = h.contextType.getMethod("withEntityRecipient", Entity.class).invoke(context, selected);
        if (recipient.equals("multiple")) {
            Object model = h.contextType.getMethod("instance").invoke(context);
            context = h.contextType.getMethod("continueExecution", h.modelType, LivingEntity.class, double.class,
                    double.class, long.class, String.class, String.class, Entity.class, List.class, Location.class, h.treeType)
                    .invoke(null, model, h.b, 1D, 4D, 0L, "scoreboard", null, trigger, List.of(h.b, h.c), h.a.getLocation(), tree);
        }
        String mapped = source;
        if (path.equals("imported")) {
            var yaml = new YamlConfiguration(); yaml.set("write.Skills", List.of(source)); yaml.save(h.source.toFile());
            @SuppressWarnings("unchecked") List<Map<String,Object>> plans = (List<Map<String,Object>>) h.importer.getClass()
                    .getMethod("convert", java.nio.file.Path.class).invoke(h.importer, h.source);
            var plan = plans.stream().filter(value -> "skills.yml".equals(value.get("target-file"))).findFirst().orElseThrow();
            yaml.loadFromString(plan.get("content").toString());
            List<String> actions = yaml.getStringList("skills.write.on-decision");
            if (actions.size() != 1) throw new IllegalStateException("Did not import " + source);
            mapped = actions.getFirst();
        }
        String binding = (String) h.skills.getClass().getMethod("bindAction", String.class).invoke(h.skills, mapped);
        if (binding == null || !(boolean) h.registry.getClass().getMethod("runAction", String.class, h.contextType)
                .invoke(h.registry, binding, context)) throw new IllegalStateException("Did not run " + mapped);
        Map<String, Object> runtimeAfter = snapshot(objective, sentinel);
        var row = new YamlConfiguration();
        row.set("case", key); row.set("path", path); row.set("source", source); row.set("mapped", mapped);
        row.set("objective", objective); row.set("sentinel", sentinel); row.set("nativeResult", nativeResult);
        row.set("nativeBefore", nativeBefore); row.set("runtimeBefore", runtimeBefore);
        row.set("nativeAfter", nativeAfter); row.set("runtimeAfter", runtimeAfter);
        row.set("sameState", nativeAfter.equals(runtimeAfter));
        row.set("primary", Bukkit.isPrimaryThread()); row.set("tick", Bukkit.getCurrentTick());
        row.set("nativePreparedTick", nativePreparedTick);
        var file = getDataFolder().toPath().resolve("observations/%04d.yml".formatted(observations));
        Files.createDirectories(file.getParent()); row.save(file.toFile());
        getLogger().info("SCOREBOARD_ROW|case=" + key + "|path=" + path + "|nativeResult=" + nativeResult
                + "|sameState=" + nativeAfter.equals(runtimeAfter));
        unregister(objective); unregister(sentinel); observations++;
    }

    private void unregister(String name) { Objective objective = board.getObjective(name); if (objective != null) objective.unregister(); }
    private void seed(String name, String sentinel, Map<String,Integer> scores, boolean exists) {
        unregister(name); unregister(sentinel);
        if (exists) {
            Objective objective = board.registerNewObjective(name, "dummy");
            scores.forEach((holder, value) -> objective.getScore(holder).setScore(value));
        }
        board.registerNewObjective(sentinel, "dummy").getScore("unrelated").setScore(713);
    }
    private Map<String,Object> snapshot(String name, String sentinel) {
        Map<String,Object> values = new LinkedHashMap<>();
        for (String objectiveName : List.of(name, sentinel)) {
            var objective = board.getObjective(objectiveName);
            if (objective == null) { values.put(objectiveName, "absent"); continue; }
            var scores = new TreeMap<String,Integer>();
            for (String entry : board.getEntries()) if (objective.getScore(entry).isScoreSet()) scores.put(entry, objective.getScore(entry).getScore());
            values.put(objectiveName, scores);
        }
        return values;
    }
    private static String family(String name) {
        return switch (name.toLowerCase(java.util.Locale.ROOT)) {
            case "setglobalscore", "sgs" -> "SetGlobalScore"; case "setmobscore", "sms" -> "SetMobScore";
            case "settargetscore", "sts" -> "SetTargetScore"; case "modifyglobalscore", "mgs" -> "ModifyGlobalScore";
            case "modifymobscore", "mms" -> "ModifyMobScore"; case "modifytargetscore", "mts" -> "ModifyTargetScore";
            case "modifyscore" -> "ModifyScore"; default -> throw new IllegalArgumentException(name);
        };
    }
}
