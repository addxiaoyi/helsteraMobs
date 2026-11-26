package dev.helstera.test.randomspawn;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;

/** Raw native/runtime observations; the independent auditor supplies the expected outcomes. */
public final class AimingTargeterProbe extends JavaPlugin {
    private static final String TAG = "helstera_aiming_targeter_probe";
    private final List<Map<String, Object>> rows = new ArrayList<>();
    private final Map<Entity, String> labels = new LinkedHashMap<>();
    private final Map<Block, BlockData> originals = new LinkedHashMap<>();
    private final List<Entity> owned = new ArrayList<>();
    private VariablePlaceholderAudit.Harness h;
    private Player player;
    private String path;
    private Location base, near, far;
    private Class<?> nativeLocation, targeters;
    private List<Entity> inherited;
    private Object runtimeContext, nativeMetadata;
    private String scene;
    private List<String> nativeEntityInputs, nativeLocationInputs;

    @Override public void onEnable() {
        new BukkitRunnable() {
            int attempts;
            @Override public void run() {
                Player joined = Bukkit.getPlayerExact("AimTester");
                if (joined != null && joined.isOnline()) { cancel(); execute(joined); }
                else if (++attempts >= 120) { cancel(); getLogger().severe("AIMING_FAILED|missing-player"); }
            }
        }.runTaskTimer(this, 100, 20);
    }

    private void execute(Player joined) {
        player = joined;
        Location original = player.getLocation(); GameMode mode = player.getGameMode();
        try {
            player.setGameMode(GameMode.SPECTATOR);
            for (String route : List.of("direct", "imported")) run(route);
            getLogger().info("AIMING_SUMMARY|observations=" + rows.size() + "|passed=true");
            player.sendMessage("AIMING_DONE");
        } catch (Throwable failure) {
            getLogger().severe("AIMING_FAILED|" + failure); failure.printStackTrace();
        } finally {
            try {
                var yaml = new YamlConfiguration(); yaml.set("observations", rows);
                Files.createDirectories(getDataFolder().toPath());
                yaml.save(getDataFolder().toPath().resolve("observations.yml").toFile());
            } catch (Exception failure) { getLogger().severe("AIMING_FAILED|snapshot=" + failure); }
            restore(); owned.forEach(Entity::remove); owned.clear();
            player.teleport(original); player.setGameMode(mode);
        }
    }

    private void run(String route) throws Exception {
        path = route;
        var world = Bukkit.getWorlds().getFirst();
        base = new Location(world, .25, 180, .25); near = base.clone().add(2, 0, 0); far = base.clone().add(8, 0, 0);
        try (var harness = new VariablePlaceholderAudit.Harness(this,
                Bukkit.getPluginManager().getPlugin("helsteraMobs"), Bukkit.getPluginManager().getPlugin("MythicMobs"),
                new Location(world, 0, 150, 0), false)) {
            h = harness;
            targeters = h.runtimeType("dev.helstera.api.behavior.Targeters");
            nativeLocation = h.nativeType("io.lumine.mythic.api.adapters.AbstractLocation");
            labels.clear(); originals.clear();
            h.a.teleport(base); h.b.teleport(near); h.c.teleport(far);
            h.item.teleport(base.clone().add(1, 0, 0));
            h.b.setHealth(2); h.c.setHealth(8);
            labels.put(h.a, "caster"); labels.put(h.b, "near"); labels.put(h.c, "far"); labels.put(h.item, "item");
            labels.put(h.d, "cross"); labels.put(player, "player");
            Display display = world.spawn(base.clone().add(4, 0, 0), TextDisplay.class);
            ArmorStand stand = world.spawn(base.clone().add(3, 0, 0), ArmorStand.class);
            Marker marker = world.spawn(base.clone().add(5, 0, 0), Marker.class);
            owned.addAll(List.of(display, stand, marker));
            display.setGravity(false); stand.setGravity(false);
            labels.put(display, "display"); labels.put(stand, "stand"); labels.put(marker, "marker");
            for (Entity entity : List.of(h.a, h.b, h.c, h.d)) h.runtimeType("dev.helstera.api.behavior.ManagedMobIdentity")
                    .getMethod("mark", Entity.class, String.class).invoke(null, entity, "aiming-probe");
            h.c.addScoreboardTag("eligible"); h.a.addScoreboardTag("caster-only");
            inherited = List.of(h.a, h.b, h.c, h.item, display, h.item);
            prepare(h.a, List.of());
            entityRow("entity-default", "@targetedTarget"); entityRow("entity-alias", "@targeted");
            entityRow("entity-self", "@targeted{targetself=true}");
            entityRow("entity-nearest", "@targeted{sort=NEAREST;limit=1}");
            entityRow("entity-furthest", "@targeted{sort=FURTHEST;limit=1}");
            entityRow("entity-high-health", "@targeted{sort=HIGHEST_HEALTH;limit=1}");
            entityRow("entity-low-health", "@targeted{sort=LOWEST_HEALTH;limit=2}");
            entityRow("entity-sort-priority", "@targeted{sort=NEAREST;sortby=FURTHEST;limit=1}");
            entityRow("entity-quoted-limit", "@targeted{sort=NEAREST;limit=\"2\"}");
            entityRow("entity-fixed-range", "@targeted{sort=NEAREST;limit=2to2}");
            entityRow("entity-sort-unlimited", "@targeted{sort=FURTHEST}");
            entityRow("entity-ignore-animal", "@targeted{ignore=animals}");
            entityRow("entity-water", "@targeted{target=water}");
            entityRow("entity-ground", "@targeted{target=ground}");
            entityRow("entity-ignore-type-noop", "@targeted{ignoretype=COW}");
            entityRow("entity-nonmythic", "@targeted{targetvanilla=false}");
            entityRow("entity-vanilla-priority", "@targeted{targetvanilla=false;targetnonmythic=true}");
            entityRow("entity-guard", "@targeted{conditions=[ - hastag{t=eligible} true ]}");
            entityRow("entity-guard-limit", "@targeted{conditions=[ - hastag{t=eligible} true ];sort=NEAREST;limit=1}");
            entityRow("entity-caster-guard", "@targeted{conditions=[ - hastag{t=caster-only} true ]}");
            inherited = List.of(); prepare(h.a, List.of()); entityRow("entity-empty", "@targeted");
            inherited = List.of(h.item, h.b, stand, marker, player); player.teleport(base.clone().add(6, 0, 0));
            for (GameMode mode : List.of(GameMode.SURVIVAL, GameMode.CREATIVE, GameMode.SPECTATOR)) {
                player.setGameMode(mode); prepare(h.a, List.of());
                entityRow("entity-player-" + mode.name().toLowerCase(), "@targeted");
            }
            entityRow("entity-player-excluded", "@targeted{targetspectator=false}");
            entityRow("entity-stand-marker", "@targeted{targetarmorstands=true;targetmarkers=true}");
            entityRow("entity-players", "@targeted{target=players,spectator}");
            inherited = List.of(h.b, h.c, h.d); prepare(h.a, List.of()); entityRow("entity-cross-world", "@targeted");

            inherited = List.of(); prepare(h.a, List.of(near, far, near.clone()));
            locationRow("location-default", "@targetedLocation"); locationRow("location-plural", "@targetedLocations");
            locationRow("location-alias", "@targetedLoc");
            locationRow("location-offset", "@targetedLoc{x=1;y=2;z=3}");
            locationRow("location-alias-priority", "@targetedLoc{x=1;xoffset=3}");
            locationRow("location-quoted", "@targetedLoc{x=\"2\"}");
            locationRow("location-fixed-range", "@targetedLoc{x=2to2}");
            locationRow("location-centered", "@targetedLoc{centered=true}");
            locationRow("location-centered-priority", "@targetedLoc{blockcentered=false;centered=true}");
            locationRow("location-dedup", "@targetedLoc{coordinatex=5}");
            locationRow("location-zero-coordinate", "@targetedLoc{cx=0;cy=0}");
            locationRow("location-yaw-pitch", "@targetedLoc{cyaw=45;cpitch=30}");
            locationRow("location-rotation-noop", "@targetedLoc{rotatex=45;rotatey=90;rotatez=30}");
            locationRow("location-nearest", "@targetedLoc{limit=1;sort=NEAREST}");
            locationRow("location-furthest", "@targetedLoc{limit=1;sortby=FURTHEST}");
            locationRow("location-sort-priority", "@targetedLoc{limit=1;sort=NEAREST;sortby=FURTHEST}");
            locationRow("location-quoted-limit", "@targetedLoc{limit=\"1\";sort=NEAREST}");
            locationRow("location-ignore", "@targetedLoc{blockignores=AIR}");
            locationRow("location-allow", "@targetedLoc{blocktypes=AIR}");
            locationRow("location-prefix", "@targetedLoc{blocktypes=@AI}");
            locationRow("location-no-match", "@targetedLoc{blocktypes=STONE}");
            prepare(h.a, List.of()); locationRow("location-empty", "@targetedLoc");
            inherited = List.of(h.b, h.c); prepare(h.a, List.of()); locationRow("location-not-entity-projection", "@targetedLoc");

            inherited = List.of(); player.setGameMode(GameMode.SPECTATOR); player.teleport(base);
            block(0, 181, 3, Material.GLASS); block(0, 181, 6, Material.STONE);
            prepare(player, List.of()); scene = "glass-stone";
            locationRow("block-default", "@targetBlock");
            locationRow("block-air-only", "@targetBlock{it=false}");
            locationRow("block-transparent-priority", "@targetBlock{ignoreTransparent=true;it=false}");
            locationRow("block-distance", "@targetBlock{d=2}");
            locationRow("block-distance-priority", "@targetBlock{d=2;distance=4;max=5;maxdistance=8}");
            locationRow("block-distance-invalid", "@targetBlock{maxdistance=bad;d=2}");
            locationRow("block-distance-quoted", "@targetBlock{maxdistance=\"2\"}");
            locationRow("block-offset", "@targetBlock{y=2;centered=true}");
            locationRow("block-filter", "@targetBlock{blocktypes=STONE}");
            locationRow("block-filter-reject", "@targetBlock{blockignores=STONE}");
            block(0, 181, 3, Material.AIR); block(0, 181, 6, Material.AIR); scene = "empty-ray";
            locationRow("block-miss", "@targetBlock{d=8}");
            locationRow("block-miss-default", "@targetBlock");
            prepare(h.item, List.of()); locationRow("block-generic-entity", "@targetBlock");
            managedRows();
        } finally {
            restore(); owned.forEach(Entity::remove); owned.clear(); labels.clear();
        }
    }

    private void prepare(Entity caster, List<Location> locations) throws Exception {
        Object nativeCaster = h.active.get(caster);
        if (nativeCaster == null) nativeCaster = h.nativeType("io.lumine.mythic.api.mobs.GenericCaster")
                .getConstructor(h.abstractEntity).newInstance(h.adapt.invoke(null, caster));
        nativeMetadata = h.metadataType.getConstructor(h.triggerType, h.skillCaster, h.abstractEntity)
                .newInstance(h.triggerType.getMethod("get", String.class).invoke(null, "API"), nativeCaster, h.adapt.invoke(null, player));
        h.skillMetadata.getMethod("setOrigin", nativeLocation).invoke(nativeMetadata, adapt(base));
        List<Object> entities = new ArrayList<>(); for (Entity entity : inherited) entities.add(h.adapt.invoke(null, entity));
        h.skillMetadata.getMethod("setEntityTargets", Collection.class).invoke(nativeMetadata, entities);
        List<Object> points = new ArrayList<>(); for (Location location : locations) points.add(adapt(location));
        // Native setters clear the other target kind, even for an empty collection.
        if (!locations.isEmpty()) h.skillMetadata.getMethod("setLocationTargets", Collection.class).invoke(nativeMetadata, points);
        runtimeContext = h.context(caster, null, player, base, null);
        runtimeContext = h.contextType.getMethod("withEntityTargets", List.class).invoke(runtimeContext, inherited);
        if (!locations.isEmpty()) runtimeContext = h.contextType.getMethod("withLocationTargets", List.class).invoke(runtimeContext, locations);
        scene = "inherited";
    }

    private void managedRows() throws Exception {
        Object active = h.active.get(h.a);
        Object table = active.getClass().getMethod("getThreatTable").invoke(active);
        Class<?> providerType = h.runtimeType("dev.helstera.api.behavior.AimingInheritedTargeter$CasterTargetProvider");
        Class<?> selectorType = h.runtimeType("dev.helstera.api.behavior.AimingInheritedTargeter");
        var field = selectorType.getDeclaredField("casterTargetProvider"); field.setAccessible(true); Object previous = field.get(null);
        var targetState = h.runtimeType("dev.helstera.api.behavior.AimingInheritedTargeter$CasterTarget").getConstructor(Entity.class);
        Entity[] selected = {null};
        Object provider = Proxy.newProxyInstance(h.runtimeLoader, new Class<?>[]{providerType},
                (proxy, method, args) -> targetState.newInstance(selected[0]));
        try {
            selectorType.getMethod("casterTargetProvider", providerType).invoke(null, provider);
            prepare(h.a, List.of()); scene = "managed-empty";
            locationRow("block-threat-empty", "@targetBlock");
            table.getClass().getMethod("threatSet", h.abstractEntity, double.class).invoke(table, h.adapt.invoke(null, h.b), 20.0);
            selected[0] = h.b; prepare(h.a, List.of()); scene = "managed-threat-near";
            locationRow("block-threat", "@targetBlock");
            table.getClass().getMethod("threatSet", h.abstractEntity, double.class).invoke(table, h.adapt.invoke(null, h.c), 40.0);
            selected[0] = h.c; prepare(h.a, List.of()); scene = "managed-threat-far";
            locationRow("block-threat-changed", "@targetBlock{y=1}");
        } finally { selectorType.getMethod("casterTargetProvider", providerType).invoke(null, previous); }
    }

    private void entityRow(String id, String selector) throws Exception {
        Object metadata = h.skillMetadata.getMethod("deepClone").invoke(nativeMetadata);
        inspectInputs(metadata);
        Object targeter = h.nativeSkills.getClass().getMethod("getTargeter", String.class).invoke(h.nativeSkills, selector);
        @SuppressWarnings("unchecked") Collection<Object> selected = (Collection<Object>) targeter.getClass()
                .getMethod("getEntities", h.skillMetadata).invoke(targeter, metadata);
        h.skillMetadata.getMethod("setEntityTargets", Collection.class).invoke(metadata, selected);
        // Pure selector contract. Mechanic-specific creative/spectator restrictions are separate.
        targeter.getClass().getMethod("filter", h.skillMetadata, boolean.class).invoke(targeter, metadata, true);
        @SuppressWarnings("unchecked") Collection<Object> filtered = (Collection<Object>) h.skillMetadata.getMethod("getEntityTargets").invoke(metadata);
        List<String> nativeSelected = new ArrayList<>(); for (Object entity : filtered) nativeSelected.add(label(entity(entity)));
        nativeSelected.sort(String::compareTo);
        Class<?> conditionType = h.runtimeType("dev.helstera.api.behavior.Targeters$ConditionProvider");
        Object conditions = Proxy.newProxyInstance(h.runtimeLoader, new Class<?>[]{conditionType}, (proxy, method, args) -> {
            Object key = h.skills.getClass().getMethod("bindCondition", String.class).invoke(h.skills, args[0]);
            return key != null && Boolean.TRUE.equals(h.registry.getClass().getMethod("testCondition", String.class, h.contextType).invoke(h.registry, key, args[1]));
        });
        @SuppressWarnings("unchecked") List<Entity> actual = (List<Entity>) targeters.getMethod("selectEntities", String.class,
                h.contextType, Collection.class, List.class, UnaryOperator.class, conditionType)
                .invoke(null, selector, runtimeContext, List.of(h.d), List.of(), UnaryOperator.identity(), conditions);
        List<String> runtimeSelected = actual.stream().map(this::label).sorted().toList();
        clearTags(); String mechanic = "addtag{tag=" + TAG + "}";
        Object action = mechanic("TagAddMechanic", mechanic);
        boolean accepted = true;
        for (Object entity : filtered) accepted &= "SUCCESS".equals(action.getClass().getMethod("castAtEntity", h.skillMetadata, h.abstractEntity)
                .invoke(action, metadata, entity).toString());
        List<String> nativeEffects = tagged(); clearTags();
        String source = mechanic + " " + selector, binding = map(source);
        boolean runtimeAccepted = runAction(binding);
        List<String> runtimeEffects = tagged(); clearTags();
        record(id, selector, source, binding, nativeSelected, runtimeSelected, nativeEffects, runtimeEffects, accepted, runtimeAccepted);
    }

    private void locationRow(String id, String selector) throws Exception {
        Object metadata = h.skillMetadata.getMethod("deepClone").invoke(nativeMetadata);
        // deepClone shares location objects, and native coordinate mutations edit them.
        // Each independent case must start from its own supplied coordinates.
        @SuppressWarnings("unchecked") List<Location> supplied = (List<Location>) h.contextType.getMethod("locationTargets").invoke(runtimeContext);
        if (!supplied.isEmpty()) {
            List<Object> copies = new ArrayList<>();
            for (Location location : supplied) copies.add(adapt(location.clone()));
            h.skillMetadata.getMethod("setLocationTargets", Collection.class).invoke(metadata, copies);
        }
        inspectInputs(metadata);
        Object targeter = h.nativeSkills.getClass().getMethod("getTargeter", String.class).invoke(h.nativeSkills, selector);
        @SuppressWarnings("unchecked") Collection<Object> picked = (Collection<Object>) targeter.getClass().getMethod("getLocations", h.skillMetadata).invoke(targeter, metadata);
        h.skillMetadata.getMethod("setLocationTargets", Collection.class).invoke(metadata, picked);
        targeter.getClass().getMethod("filter", h.skillMetadata).invoke(targeter, metadata);
        @SuppressWarnings("unchecked") Collection<Object> filtered = (Collection<Object>) h.skillMetadata.getMethod("getLocationTargets").invoke(metadata);
        List<Location> nativePoints = new ArrayList<>(); for (Object location : filtered) nativePoints.add(point(location));
        @SuppressWarnings("unchecked") List<Location> actual = (List<Location>) targeters.getMethod("resolveLocations", String.class, h.contextType)
                .invoke(null, selector, runtimeContext);
        // Observe all relevant blocks, including caster/origin and both inherited positions.
        Map<Block, BlockData> before = new LinkedHashMap<>();
        for (int x = -3; x <= 12; x++) for (int y = 179; y <= 185; y++) for (int z = -3; z <= 65; z++) {
            Block block = base.getWorld().getBlockAt(x, y, z); before.put(block, block.getBlockData().clone());
        }
        String mechanic = "setblock{type=DIAMOND_BLOCK}"; Object action = mechanic("SetBlockTypeMechanic", mechanic);
        boolean accepted = true;
        for (Object location : filtered) accepted &= "SUCCESS".equals(action.getClass().getMethod("castAtLocation", h.skillMetadata, nativeLocation)
                .invoke(action, metadata, location).toString());
        List<String> nativeEffects = changed(before); restore(before);
        String source = mechanic + " " + selector, binding = map(source);
        boolean runtimeAccepted = runAction(binding);
        List<String> runtimeEffects = changed(before); restore(before);
        record(id, selector, source, binding, locations(nativePoints), locations(actual), nativeEffects, runtimeEffects, accepted, runtimeAccepted);
    }

    private void record(String id, String selector, String source, String binding, List<String> nativeSelected,
                        List<String> runtimeSelected, List<String> nativeEffects, List<String> runtimeEffects,
                        boolean nativeAccepted, boolean runtimeAccepted) throws Exception {
        var row = new LinkedHashMap<String, Object>();
        row.put("path", path); row.put("case", id); row.put("selector", selector); row.put("source", source); row.put("binding", binding);
        row.put("nativeSelected", nativeSelected); row.put("runtimeSelected", runtimeSelected);
        row.put("nativeEffects", nativeEffects); row.put("runtimeEffects", runtimeEffects);
        row.put("nativeAccepted", nativeAccepted); row.put("runtimeAccepted", runtimeAccepted); row.put("scene", scene);
        row.put("entityInputs", inherited.stream().map(this::label).toList());
        row.put("nativeEntityInputs", nativeEntityInputs); row.put("nativeLocationInputs", nativeLocationInputs);
        @SuppressWarnings("unchecked") List<Location> inputs = (List<Location>) h.contextType.getMethod("locationTargets").invoke(runtimeContext);
        row.put("locationInputs", locations(inputs)); row.put("playerMode", player.getGameMode().name());
        row.put("player", location(player.getLocation())); row.put("eye", location(player.getEyeLocation()));
        rows.add(row); getLogger().info("AIMING_ROW|path=" + path + "|case=" + id + "|observed=true");
    }

    private void inspectInputs(Object metadata) throws Exception {
        @SuppressWarnings("unchecked") Collection<Object> entities = (Collection<Object>) h.skillMetadata.getMethod("getEntityTargets").invoke(metadata);
        @SuppressWarnings("unchecked") Collection<Object> points = (Collection<Object>) h.skillMetadata.getMethod("getLocationTargets").invoke(metadata);
        nativeEntityInputs = new ArrayList<>(); nativeLocationInputs = new ArrayList<>();
        if (entities != null) for (Object entity : entities) nativeEntityInputs.add(label(entity(entity)));
        if (points != null) for (Object point : points) nativeLocationInputs.add(location(point(point)));
        nativeLocationInputs.sort(String::compareTo);
    }

    private Object mechanic(String type, String source) throws Exception {
        Object config = h.configImpl.getConstructor(String.class).newInstance(source);
        return h.nativeType("io.lumine.mythic.core.skills.mechanics." + type)
                .getConstructor(h.executorType, java.io.File.class, String.class, h.configType)
                .newInstance(h.nativeSkills, h.source.toFile(), source, config);
    }
    private boolean runAction(String binding) throws Exception {
        Object key = h.skills.getClass().getMethod("bindAction", String.class).invoke(h.skills, binding);
        return key != null && Boolean.TRUE.equals(h.skills.getClass().getMethod("runAction", String.class, h.contextType).invoke(h.skills, key, runtimeContext));
    }
    private String map(String source) throws Exception {
        if (path.equals("direct")) return source;
        var yaml = new YamlConfiguration(); yaml.set("write.Skills", List.of(source)); yaml.save(h.source.toFile());
        @SuppressWarnings("unchecked") var plans = (List<Map<String, Object>>) h.importer.getClass().getMethod("convert", java.nio.file.Path.class).invoke(h.importer, h.source);
        var plan = plans.stream().filter(p -> "skills.yml".equals(p.get("target-file"))).findFirst().orElseThrow();
        yaml.loadFromString(plan.get("content").toString()); var actions = yaml.getStringList("skills.write.on-decision");
        if (actions.size() != 1 || plans.stream().anyMatch(p -> "WARN".equals(p.get("severity")))) throw new IllegalStateException("Import rejected " + source);
        return actions.getFirst();
    }
    private Object adapt(Location location) throws Exception { return h.nativeType("io.lumine.mythic.bukkit.BukkitAdapter").getMethod("adapt", Location.class).invoke(null, location); }
    private Location point(Object location) throws Exception { return (Location) h.nativeType("io.lumine.mythic.bukkit.BukkitAdapter").getMethod("adapt", nativeLocation).invoke(null, location); }
    private Entity entity(Object entity) throws Exception { return (Entity) h.nativeType("io.lumine.mythic.bukkit.BukkitAdapter").getMethod("adapt", h.abstractEntity).invoke(null, entity); }
    private String label(Entity entity) { return labels.getOrDefault(entity, "unexpected:" + entity.getType()); }
    private static String location(Location location) { return String.format(java.util.Locale.ROOT, "%s:%.6f,%.6f,%.6f,%.6f,%.6f", location.getWorld().getName(), location.getX(), location.getY(), location.getZ(), (double) location.getYaw(), (double) location.getPitch()); }
    private static List<String> locations(List<Location> points) { return points.stream().map(AimingTargeterProbe::location).sorted().toList(); }
    private void clearTags() { labels.keySet().forEach(entity -> entity.removeScoreboardTag(TAG)); }
    private List<String> tagged() { return labels.keySet().stream().filter(entity -> entity.getScoreboardTags().contains(TAG)).map(this::label).sorted().toList(); }
    private void block(int x, int y, int z, Material type) { Block block = base.getWorld().getBlockAt(x, y, z); originals.putIfAbsent(block, block.getBlockData().clone()); block.setType(type, false); }
    private void restore() { restore(originals); originals.clear(); }
    private static void restore(Map<Block, BlockData> blocks) { blocks.forEach((block, data) -> { if (!block.getBlockData().equals(data)) block.setBlockData(data, false); }); }
    private static List<String> changed(Map<Block, BlockData> before) { return before.entrySet().stream().filter(entry -> !entry.getKey().getBlockData().equals(entry.getValue())).map(entry -> location(entry.getKey().getLocation()) + "=" + entry.getKey().getType()).sorted().toList(); }
}
