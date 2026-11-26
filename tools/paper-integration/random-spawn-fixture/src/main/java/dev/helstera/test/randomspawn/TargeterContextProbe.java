package dev.helstera.test.randomspawn;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.UnaryOperator;

/** Native selection/filtering and real mechanic effects versus direct/imported production binding. */
public final class TargeterContextProbe extends JavaPlugin {
    private static final String TAG = "helstera_targeter_probe";
    private final List<Map<String, Object>> rows = new ArrayList<>();
    private final Map<Chunk, Boolean> pinned = new LinkedHashMap<>();
    private VariablePlaceholderAudit.Harness h;
    private Player player;
    private Interaction interaction;
    private Location origin;
    private String path;
    private Map<Entity, String> labels;

    @Override public void onEnable() {
        new BukkitRunnable() {
            int attempts;
            @Override public void run() {
                Player joined = Bukkit.getPlayerExact("TargeterTester");
                if (joined != null && joined.isOnline()) {
                    cancel();
                    execute(joined);
                } else if (++attempts >= 120) {
                    cancel();
                    getLogger().severe("TARGETERS_FAILED|missing-player");
                }
            }
        }.runTaskTimer(this, 100, 20);
    }

    private void execute(Player joined) {
        player = joined;
        Location original = player.getLocation();
        GameMode mode = player.getGameMode();
        try {
            player.setGameMode(GameMode.SURVIVAL);
            for (String route : List.of("direct", "imported")) run(route);
            if (rows.size() != 66) throw new IllegalStateException("Expected 66 observations, got " + rows.size());
            getLogger().info("TARGETERS_SUMMARY|observations=66|passed=true");
            player.sendMessage("TARGETERS_DONE");
        } catch (Throwable failure) {
            getLogger().severe("TARGETERS_FAILED|" + failure);
            failure.printStackTrace();
        } finally {
            try {
                var yaml = new YamlConfiguration();
                yaml.set("observations", rows);
                Files.createDirectories(getDataFolder().toPath());
                yaml.save(getDataFolder().toPath().resolve("observations.yml").toFile());
            } catch (Exception failure) { getLogger().severe("TARGETERS_FAILED|save=" + failure); }
            if (interaction != null) interaction.remove();
            player.removeScoreboardTag(TAG);
            player.teleport(original);
            player.setGameMode(mode);
            pinned.forEach(Chunk::setForceLoaded);
        }
    }

    private void run(String route) throws Exception {
        path = route;
        var world = Bukkit.getWorlds().getFirst();
        origin = new Location(world, 0, 180, 0);
        for (var loaded : Bukkit.getWorlds()) {
            pin(new Location(loaded, 110, 170, 0));
            pin(new Location(loaded, 114, 170, 0));
        }
        pin(new Location(world, 32.25, 180.75, 1.25));
        pin(new Location(world, 64.25, 180.75, 1.25));
        try (var harness = new VariablePlaceholderAudit.Harness(this,
                Bukkit.getPluginManager().getPlugin("helsteraMobs"),
                Bukkit.getPluginManager().getPlugin("MythicMobs"), new Location(world, 0, 150, 0), false)) {
            h = harness;
            require(h.a.isValid() && h.b.isValid() && h.c.isValid() && h.d.isValid(),
                    "invalid fixture caster/targets: a=" + h.a.isValid() + ",b=" + h.b.isValid()
                            + ",c=" + h.c.isValid() + ",d=" + h.d.isValid());
            require(h.b.teleport(new Location(world, 32.25, 180.75, 1.25)), "first target teleport");
            require(h.c.teleport(new Location(world, 64.25, 180.75, 1.25)), "second target teleport");
            player.teleport(new Location(world, 0, 181, 8));
            interaction = world.spawn(origin.clone().add(3, 0, 0), Interaction.class);
            interaction.setGravity(false);
            labels = new LinkedHashMap<>();
            labels.put(h.a, "caster"); labels.put(h.b, "first"); labels.put(h.c, "second");
            labels.put(h.d, "cross"); labels.put(player, "player"); labels.put(interaction, "interaction");

            entity("player-exact", "@PlayerByName{name=TargeterTester}", h.a, List.of("player"));
            entity("player-case", "@specificplayer{n=targetertester}", h.a, List.of("player"));
            entity("player-prefix", "@playerbyname{n=TargeterT}", h.a, List.of());
            entity("player-missing", "@playerbyname{n=MissingTargeter}", h.a, List.of());
            entity("player-default", "@playerbyname", h.a, List.of());
            entity("player-placeholder", "@playerbyname{n=<trigger.name>}", h.a, List.of("player"));
            entity("player-filtered", "@playerbyname{n=TargeterTester;targetPlayers=false}", h.a, List.of());
            Location here = player.getLocation();
            Location cross = here.clone(); cross.setWorld(h.d.getWorld());
            require(player.teleport(cross), "cross-world player teleport");
            entity("player-crossworld", "@specificplayer{n=TargeterTester}", h.a, List.of("player"));
            require(player.teleport(here), "restore player world");

            history("attack", player.getUniqueId());
            history("interaction", player.getUniqueId());
            require(interaction.getLastAttack() != null && interaction.getLastInteraction() != null, "history setup");
            entity("interaction-attack", "@interactionLastAttacker", interaction, List.of("player"));
            entity("interaction-attacker-alias", "@lastAttacker", interaction, List.of("player"));
            entity("interaction-interact", "@interactionLastInteract", interaction, List.of("player"));
            entity("interaction-interact-alias", "@lastInteract", interaction, List.of("player"));
            clearHistory("attack");
            entity("interaction-missing-attack", "@lastAttacker", interaction, List.of());
            clearHistory("interaction");
            entity("interaction-missing-interact", "@lastInteract", interaction, List.of());
            history("attack", UUID.fromString("00000000-0000-0000-0000-00000000cafe"));
            require(interaction.getLastAttack().getPlayer().getPlayer() == null, "offline history setup");
            entity("interaction-offline-attack", "@lastAttacker", interaction, List.of());
            entity("interaction-wrong-caster", "@lastInteract", h.a, List.of());
            history("interaction", player.getUniqueId());
            entity("interaction-player-disabled", "@lastInteract{targetPlayers=false}", interaction, List.of());

            List<LivingEntity> both = List.of(h.b, h.c);
            List<Location> points = List.of(h.b.getLocation(), h.c.getLocation());
            locations("lot-inherited", "@locationsOfTargets", both, null, points);
            locations("lot-short-alias", "@LOT", both, null, points);
            locations("lot-singular-alias", "@locationOfTarget", both, null, points);
            locations("lot-duplicate", "@LOT", List.of(h.b, h.c, h.b), null, points);
            locations("lot-empty", "@LOT", List.of(), null, List.of());
            Location inherited = new Location(world, 80.25, 181.25, 3.25);
            locations("lot-location-fallback", "@LOT", List.of(), inherited, List.of(inherited));
            locations("lot-location-empty-entities", "@LOT", List.of(), inherited, List.of());
            locations("lot-offset", "@LOT{x=1;y=2;z=-1}", both, null,
                    points.stream().map(p -> p.clone().add(1, 2, -1)).toList());
            locations("lot-length", "@LOT{length=3}", both, null,
                    points.stream().map(p -> p.clone().add(0, 0, 3)).toList());
            locations("lot-forward", "@LOT{fo=2}", both, null,
                    points.stream().map(p -> p.clone().add(0, 0, -2)).toList());
            locations("lot-coordinates", "@LOT{cx=96;cy=181;cz=4;cyaw=90;cpitch=15}", both, null,
                    List.of(new Location(world, 96, 181, 4, 90, 15)));
            locations("lot-centered", "@LOT{centered=true}", both, null,
                    points.stream().map(p -> new Location(world, p.getBlockX() + .5, p.getY(), p.getBlockZ() + .5)).toList());
            locations("lot-nearest", "@LOT{limit=1;sort=NEAREST}", List.of(h.c, h.b), null, List.of(h.b.getLocation()));
            locations("lot-furthest", "@LOT{limit=1;sortby=FURTHEST}", both, null, List.of(h.c.getLocation()));
            locations("lot-block-ignore", "@LOT{blockignore=AIR}", both, null, List.of());
            locations("lot-block-include", "@LOT{blocktypes=AIR}", both, null, points);
        } finally {
            if (interaction != null) interaction.remove();
            interaction = null;
        }
    }

    private void entity(String id, String selector, Entity caster, List<String> expected) throws Exception {
        Object meta = metadata(caster, List.of(h.b), null);
        Object targeter = nativeTargeter(selector);
        @SuppressWarnings("unchecked") Collection<Object> picked = (Collection<Object>) targeter.getClass()
                .getMethod("getEntities", h.skillMetadata).invoke(targeter, meta);
        h.skillMetadata.getMethod("setEntityTargets", Collection.class).invoke(meta, picked);
        targeter.getClass().getMethod("filter", h.skillMetadata, boolean.class).invoke(targeter, meta, false);
        @SuppressWarnings("unchecked") Collection<Object> filtered = (Collection<Object>) h.skillMetadata
                .getMethod("getEntityTargets").invoke(meta);
        List<String> nativeSelected = new ArrayList<>();
        for (Object target : filtered) nativeSelected.add(label(adaptEntity(target)));
        nativeSelected.sort(String::compareTo);
        Object context = context(caster, List.of(h.b), null);
        @SuppressWarnings("unchecked") List<LivingEntity> runtimeTargets = (List<LivingEntity>) h.runtimeType(
                "dev.helstera.api.behavior.Targeters").getMethod("select", String.class, h.contextType,
                Collection.class, List.class, UnaryOperator.class).invoke(null, selector, context,
                List.of(h.b), List.of(), resolver(context));
        List<String> runtimeSelected = runtimeTargets.stream().map(this::label).sorted().toList();
        labels.keySet().forEach(entity -> entity.removeScoreboardTag(TAG));
        Object mechanic = mechanic("addtag{tag=" + TAG + "}", "TagAddMechanic");
        boolean nativeAccepted = true;
        for (Object target : filtered) nativeAccepted &= "SUCCESS".equals(mechanic.getClass()
                .getMethod("castAtEntity", h.skillMetadata, h.abstractEntity).invoke(mechanic, meta, target).toString());
        List<String> nativeEffects = tagged();
        labels.keySet().forEach(entity -> entity.removeScoreboardTag(TAG));
        String source = "addtag{tag=" + TAG + "} " + selector;
        String binding = map(source);
        boolean runtimeAccepted = dispatch(binding, context);
        List<String> runtimeEffects = tagged();
        labels.keySet().forEach(entity -> entity.removeScoreboardTag(TAG));
        record(id, selector, source, binding, nativeSelected, runtimeSelected, expected,
                nativeEffects, runtimeEffects, expected, nativeAccepted, runtimeAccepted);
    }

    private void locations(String id, String selector, List<LivingEntity> targets, Location inherited,
                           List<Location> expected) throws Exception {
        Object meta = metadata(h.a, targets, inherited);
        boolean clearedEntities = id.equals("lot-location-empty-entities");
        if (clearedEntities) {
            h.skillMetadata.getMethod("setEntityTargets", Collection.class).invoke(meta, List.of());
            h.skillMetadata.getMethod("setLocationTargets", Collection.class).invoke(meta,
                    List.of(nativeLocation(inherited)));
        }
        Object targeter = nativeTargeter(selector);
        @SuppressWarnings("unchecked") Collection<Object> picked = (Collection<Object>) targeter.getClass()
                .getMethod("getLocations", h.skillMetadata).invoke(targeter, meta);
        h.skillMetadata.getMethod("setLocationTargets", Collection.class).invoke(meta, picked);
        targeter.getClass().getMethod("filter", h.skillMetadata).invoke(targeter, meta);
        @SuppressWarnings("unchecked") Collection<Object> filtered = (Collection<Object>) h.skillMetadata
                .getMethod("getLocationTargets").invoke(meta);
        List<Location> nativeLocations = new ArrayList<>();
        for (Object location : filtered) nativeLocations.add(adaptLocation(location));
        Object context = context(h.a, targets, clearedEntities ? null : inherited);
        if (clearedEntities) context = h.contextType.getMethod("withLocationTarget", Location.class).invoke(context, inherited);
        @SuppressWarnings("unchecked") List<Location> runtimeLocations = (List<Location>) h.runtimeType(
                "dev.helstera.api.behavior.Targeters").getMethod("resolveLocations", String.class, h.contextType,
                UnaryOperator.class).invoke(null, selector, context, resolver(context));
        var chunks = new LinkedHashMap<Chunk, Boolean>();
        List<Location> possible = new ArrayList<>(expected); possible.addAll(nativeLocations); possible.addAll(runtimeLocations);
        possible.add(h.a.getLocation()); possible.add(h.b.getLocation()); possible.add(h.c.getLocation());
        for (Location location : possible) chunks.putIfAbsent(location.getChunk(), location.getChunk().isForceLoaded());
        String source = "setchunkforceloaded{loaded=true} " + selector;
        String binding = map(source);
        try {
            chunks.keySet().forEach(chunk -> chunk.setForceLoaded(false));
            Object mechanic = mechanic("setchunkforceloaded{loaded=true}", "SetChunkForceLoadedMechanic");
            boolean nativeAccepted = true;
            Class<?> locationType = h.nativeType("io.lumine.mythic.api.adapters.AbstractLocation");
            for (Object target : filtered) nativeAccepted &= "SUCCESS".equals(mechanic.getClass()
                    .getMethod("castAtLocation", h.skillMetadata, locationType).invoke(mechanic, meta, target).toString());
            List<String> nativeEffects = chunks.keySet().stream().filter(Chunk::isForceLoaded).map(TargeterContextProbe::chunkKey).sorted().toList();
            chunks.keySet().forEach(chunk -> chunk.setForceLoaded(false));
            boolean runtimeAccepted = dispatch(binding, context);
            List<String> runtimeEffects = chunks.keySet().stream().filter(Chunk::isForceLoaded).map(TargeterContextProbe::chunkKey).sorted().toList();
            record(id, selector, source, binding, keys(nativeLocations), keys(runtimeLocations), keys(expected),
                    nativeEffects, runtimeEffects, expected.stream().map(Location::getChunk).map(TargeterContextProbe::chunkKey).distinct().sorted().toList(),
                    nativeAccepted, runtimeAccepted);
        } finally { chunks.forEach(Chunk::setForceLoaded); }
    }

    private Object context(Entity caster, List<LivingEntity> targets, Location location) throws Exception {
        Object tree = h.treeType.getConstructor(Entity.class, List.class, Location.class).newInstance(player, targets, origin);
        Object base = h.context(caster, targets.isEmpty() ? null : targets.getFirst(), player, origin, tree);
        Object instance = h.contextType.getMethod("instance").invoke(base);
        Object context = h.contextType.getMethod("continueExecution", h.modelType, LivingEntity.class, double.class,
                        double.class, long.class, String.class, String.class, Entity.class, List.class, Location.class, h.treeType)
                .invoke(null, instance, targets.isEmpty() ? null : targets.getFirst(), 1.0, 0.0, 0L,
                        "targeter-probe", null, player, location == null ? targets : null, origin, tree);
        return location == null ? context : h.contextType.getMethod("withLocationTarget", Location.class).invoke(context, location);
    }

    private Object metadata(Entity caster, List<LivingEntity> targets, Location location) throws Exception {
        Object generic = h.nativeType("io.lumine.mythic.api.mobs.GenericCaster").getConstructor(h.abstractEntity)
                .newInstance(h.adapt.invoke(null, caster));
        Object meta = h.metadataType.getConstructor(h.triggerType, h.skillCaster, h.abstractEntity).newInstance(
                h.triggerType.getMethod("get", String.class).invoke(null, "API"), generic, h.adapt.invoke(null, player));
        Class<?> locationType = h.nativeType("io.lumine.mythic.api.adapters.AbstractLocation");
        h.skillMetadata.getMethod("setOrigin", locationType).invoke(meta, nativeLocation(origin));
        List<Object> entities = new ArrayList<>();
        for (Entity target : targets) entities.add(h.adapt.invoke(null, target));
        // Setting location targets clears an existing entity collection to an empty set.
        // Only set the inherited collection for this row, preserving the native null distinction.
        if (location == null) h.skillMetadata.getMethod("setEntityTargets", Collection.class).invoke(meta, entities);
        else {
            h.skillMetadata.getMethod("setEntityTargets", Collection.class).invoke(meta, (Object) null);
            h.skillMetadata.getMethod("setLocationTargets", Collection.class).invoke(meta,
                    new ArrayList<>(List.of(nativeLocation(location))));
        }
        return meta;
    }

    private Object nativeTargeter(String selector) throws Exception {
        Object targeter = h.nativeSkills.getClass().getMethod("getTargeter", String.class).invoke(h.nativeSkills, selector);
        require(targeter != null, "native targeter missing " + selector);
        return targeter;
    }

    private Object mechanic(String source, String simpleName) throws Exception {
        Object config = h.configImpl.getConstructor(String.class).newInstance(source);
        return h.nativeType("io.lumine.mythic.core.skills.mechanics." + simpleName)
                .getConstructor(h.executorType, java.io.File.class, String.class, h.configType)
                .newInstance(h.nativeSkills, h.source.toFile(), source, config);
    }

    private Object nativeLocation(Location location) throws Exception {
        return h.nativeType("io.lumine.mythic.bukkit.BukkitAdapter").getMethod("adapt", Location.class).invoke(null, location);
    }
    private Location adaptLocation(Object location) throws Exception {
        return (Location) h.nativeType("io.lumine.mythic.bukkit.BukkitAdapter").getMethod("adapt",
                h.nativeType("io.lumine.mythic.api.adapters.AbstractLocation")).invoke(null, location);
    }
    private Entity adaptEntity(Object entity) throws Exception {
        return (Entity) h.nativeType("io.lumine.mythic.bukkit.BukkitAdapter").getMethod("adapt", h.abstractEntity).invoke(null, entity);
    }
    private UnaryOperator<String> resolver(Object context) {
        return value -> { try { return (String) h.resolve.invoke(null, value, context); }
        catch (ReflectiveOperationException error) { throw new IllegalStateException(error); } };
    }

    private String map(String source) throws Exception {
        if (path.equals("direct")) return source;
        var yaml = new YamlConfiguration(); yaml.set("write.Skills", List.of(source)); yaml.save(h.source.toFile());
        @SuppressWarnings("unchecked") var plans = (List<Map<String, Object>>) h.importer.getClass()
                .getMethod("convert", java.nio.file.Path.class).invoke(h.importer, h.source);
        var plan = plans.stream().filter(p -> "skills.yml".equals(p.get("target-file"))).findFirst().orElseThrow();
        yaml.loadFromString(plan.get("content").toString());
        List<String> actions = yaml.getStringList("skills.write.on-decision");
        require(actions.size() == 1 && plans.stream().noneMatch(p -> "WARN".equals(p.get("severity"))), "import rejected " + source);
        return actions.getFirst();
    }

    private boolean dispatch(String source, Object context) throws Exception {
        Object key = h.skills.getClass().getMethod("bindAction", String.class).invoke(h.skills, source);
        return key != null && Boolean.TRUE.equals(h.skills.getClass().getMethod("runAction", String.class,
                h.contextType).invoke(h.skills, key, context));
    }

    private void history(String type, UUID playerId) {
        long most = playerId.getMostSignificantBits(), least = playerId.getLeastSignificantBits();
        String uuid = "[I;" + (int) (most >> 32) + "," + (int) most + "," + (int) (least >> 32) + "," + (int) least + "]";
        require(Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "minecraft:data merge entity " + interaction.getUniqueId()
                + " {" + type + ":{player:" + uuid + ",timestamp:1L}}"), "history command rejected");
        var previous = type.equals("attack") ? interaction.getLastAttack() : interaction.getLastInteraction();
        require(previous != null && previous.getPlayer().getUniqueId().equals(playerId), "history record mismatch");
    }
    private void clearHistory(String type) {
        require(Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "minecraft:data remove entity "
                + interaction.getUniqueId() + " " + type), "history removal rejected");
        require((type.equals("attack") ? interaction.getLastAttack() : interaction.getLastInteraction()) == null,
                "history not cleared");
    }

    private void pin(Location location) {
        Chunk chunk = location.getChunk();
        pinned.putIfAbsent(chunk, chunk.isForceLoaded());
        chunk.setForceLoaded(true);
    }
    private String label(Entity entity) { return labels.getOrDefault(entity, "unexpected:" + entity.getType()); }
    private List<String> tagged() {
        return labels.keySet().stream().filter(entity -> entity.getScoreboardTags().contains(TAG)).map(this::label).sorted().toList();
    }
    private static String chunkKey(Chunk chunk) { return chunk.getWorld().getName() + ":" + chunk.getX() + "," + chunk.getZ(); }
    private static List<String> keys(List<Location> locations) { return locations.stream().map(TargeterContextProbe::key).distinct().sorted().toList(); }
    private static String key(Location location) {
        return String.format(Locale.ROOT, "%s:%.6f,%.6f,%.6f,%.6f,%.6f", location.getWorld().getName(),
                location.getX(), location.getY(), location.getZ(), (double) location.getYaw(), (double) location.getPitch());
    }
    private void record(String id, String selector, String source, String binding, List<String> nativeSelected,
                        List<String> runtimeSelected, List<String> expected, List<String> nativeEffects,
                        List<String> runtimeEffects, List<String> expectedEffects, boolean nativeAccepted, boolean runtimeAccepted) {
        boolean matched = nativeAccepted && runtimeAccepted && nativeSelected.equals(expected) && runtimeSelected.equals(expected)
                && nativeEffects.equals(expectedEffects) && runtimeEffects.equals(expectedEffects);
        var row = new LinkedHashMap<String, Object>();
        row.put("path", path); row.put("case", id); row.put("selector", selector); row.put("source", source); row.put("binding", binding);
        row.put("nativeSelected", nativeSelected); row.put("runtimeSelected", runtimeSelected); row.put("expected", expected);
        row.put("nativeEffects", nativeEffects); row.put("runtimeEffects", runtimeEffects); row.put("expectedEffects", expectedEffects);
        row.put("nativeAccepted", nativeAccepted); row.put("runtimeAccepted", runtimeAccepted); row.put("matched", matched);
        rows.add(row);
        getLogger().info("TARGETERS_ROW|path=" + path + "|case=" + id + "|matched=" + matched);
        if (!matched) throw new IllegalStateException("Targeter mismatch " + row);
    }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
}
