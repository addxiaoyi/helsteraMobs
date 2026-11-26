package dev.helstera.test.randomspawn;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

/** Observe actual Item query/filter output and TagAdd effects; the Python auditor owns expectations. */
public final class ItemTargeterProbe extends JavaPlugin {
    private static final String TAG = "helstera_item_targeter_probe";
    private final List<Map<String, Object>> rows = new ArrayList<>();
    private final Map<Chunk, Boolean> pinned = new LinkedHashMap<>();
    private final Map<Entity, String> labels = new LinkedHashMap<>();
    private final List<Item> items = new ArrayList<>();
    private VariablePlaceholderAudit.Harness h;
    private Player player;
    private String path;
    private Location base;
    private Map<String, Double> nativeScores = Map.of();

    @Override public void onEnable() {
        new BukkitRunnable() {
            int attempts;
            @Override public void run() {
                Player joined = Bukkit.getPlayerExact("ItemTester");
                if (joined != null && joined.isOnline()) { cancel(); execute(joined); }
                else if (++attempts >= 120) { cancel(); getLogger().severe("ITEMS_FAILED|missing-player"); }
            }
        }.runTaskTimer(this, 100, 20);
    }

    private void execute(Player joined) {
        player = joined;
        Location original = player.getLocation();
        GameMode mode = player.getGameMode();
        try {
            player.setGameMode(GameMode.SPECTATOR);
            for (String route : List.of("direct", "imported")) run(route);
            getLogger().info("ITEMS_SUMMARY|observations=" + rows.size() + "|passed=true");
            player.sendMessage("ITEMS_DONE");
        } catch (Throwable failure) {
            getLogger().severe("ITEMS_FAILED|" + failure);
            failure.printStackTrace();
        } finally {
            try {
                var yaml = new YamlConfiguration(); yaml.set("observations", rows);
                Files.createDirectories(getDataFolder().toPath());
                yaml.save(getDataFolder().toPath().resolve("observations.yml").toFile());
            } catch (Exception failure) { getLogger().severe("ITEMS_FAILED|snapshot=" + failure); }
            items.forEach(Entity::remove); items.clear();
            player.teleport(original); player.setGameMode(mode);
            pinned.forEach(Chunk::setForceLoaded);
        }
    }

    private void run(String route) throws Exception {
        path = route;
        var world = Bukkit.getWorlds().getFirst();
        base = new Location(world, 8, 180, 8);
        for (var loaded : Bukkit.getWorlds()) {
            pin(new Location(loaded, 110, 170, 0)); pin(new Location(loaded, 114, 170, 0));
            pin(new Location(loaded, 8, 180, 8));
        }
        pin(base); pin(base.clone().add(8, 0, 0)); pin(base.clone().add(24, 0, 0));
        try (var harness = new VariablePlaceholderAudit.Harness(this,
                Bukkit.getPluginManager().getPlugin("helsteraMobs"), Bukkit.getPluginManager().getPlugin("MythicMobs"),
                new Location(world, 0, 150, 0), false)) {
            h = harness;
            labels.clear();
            labels.put(h.a, "caster"); labels.put(h.b, "living"); labels.put(h.c, "other-living");
            labels.put(h.d, "cross-living"); labels.put(h.item, "harness-item"); labels.put(player, "player");
            require(h.a.teleport(base), "caster teleport"); require(h.b.teleport(base.clone().add(1, 0, 0)), "living teleport");
            player.teleport(base.clone().add(0, 2, 4));
            Item near = item("near", 9, 180, 8), far = item("far", 12, 180, 8);
            item("negative", 6, 180, 8); item("edge", 13, 180, 8); item("beyond", 13.0625, 180, 8);
            item("high", 8, 185, 8); item("same-chunk", 15.984375, 180, 8); item("next-chunk", 16.015625, 180, 8);
            item("origin", 32, 180, 8);
            Item cross = h.d.getWorld().dropItem(new Location(h.d.getWorld(), 8, 180, 8), new ItemStack(Material.DIAMOND));
            cross.setGravity(false); cross.setVelocity(new Vector()); cross.setPickupDelay(Integer.MAX_VALUE);
            items.add(cross); labels.put(cross, "cross-item");
            far.addScoreboardTag("eligible"); h.a.addScoreboardTag("caster-only");
            near.setMetadata("Faction", new FixedMetadataValue(this, "Blue"));
            far.setMetadata("Faction", new FixedMetadataValue(this, "blue"));
            h.runtimeType("dev.helstera.api.behavior.ManagedMobIdentity").getMethod("mark", Entity.class, String.class)
                    .invoke(null, h.a, "item-caster");

            row("iir-default", "@itemsInRadius"); row("iir-alias", "@IIR");
            row("ino-default", "@itemsNearOrigin"); row("ino-alias", "@INO");
            row("radius-long", "@IIR{radius=2}"); row("radius-short", "@IIR{r=2}");
            row("iir-alias-priority", "@IIR{r=2;radius=5}");
            row("ino-alias-priority", "@INO{radius=5;r=2}");
            row("iir-invalid-priority", "@IIR{radius=bad;r=2}");
            row("ino-invalid-priority", "@INO{radius=2;r=bad}");
            row("radius-quoted", "@IIR{r=\"2\"}"); row("radius-placeholder", "@IIR{r=<caster.level>}");
            row("radius-repeat", "@IIR{r=5;r=2}"); row("radius-zero", "@IIR{r=0}");
            row("radius-negative", "@IIR{r=-2}"); row("radius-nan", "@IIR{r=NaN}");
            row("bounding-box", "@IIR{bb=true}"); row("bounding-box-alias", "@IIR{useboundingbox=true}");
            row("bounding-box-priority", "@IIR{bb=false;useboundingbox=true}");
            row("target-water", "@IIR{target=water}"); row("target-flying", "@IIR{target=flying}");
            row("target-ground", "@IIR{target=ground}"); row("target-players", "@IIR{target=players}");
            row("ignore-type-noop", "@IIR{ignoretype=ITEM}"); row("unique-zero", "@IIR{unique=0}");
            row("ignore-vanilla", "@IIR{ignore=vanilla}"); row("nonmythic-off", "@IIR{targetnonmythic=false}");
            row("vanilla-priority", "@IIR{targetvanilla=false;targetnonmythic=true}");
            row("vanilla-override", "@IIR{ignore=vanilla;targetvanilla=true}");
            row("factionless", "@IIR{targetsamefaction=false}");
            row("ignore-faction", "@IIR{ignore=faction}");
            row("nearest", "@IIR{sort=NEAREST;limit=1}"); row("furthest", "@IIR{sortby=FURTHEST;limit=1}");
            row("sort-priority", "@IIR{sort=NEAREST;sortby=FURTHEST;limit=1}");
            row("sort-unlimited", "@IIR{sort=FURTHEST}"); row("limit-negative", "@IIR{sort=FURTHEST;limit=-1}");
            row("limit-invalid", "@IIR{sort=NEAREST;limit=bad}"); row("limit-decimal", "@IIR{sort=NEAREST;limit=2.9}");
            row("limit-quoted", "@IIR{sort=NEAREST;limit=\"2\"}"); row("limit-range-fixed", "@IIR{sort=NEAREST;limit=2to2}");
            row("sort-random", "@IIR{sort=RANDOM;limit=1}"); row("sort-health-high", "@IIR{sort=HIGHEST_HEALTH;limit=1}");
            row("sort-health-low", "@IIR{sort=LOWEST_HEALTH;limit=1}"); row("sort-invalid", "@IIR{sort=bad;limit=1}");
            row("origin-query", "@INO", h.a, new Location(world, 32, 180, 8));
            row("origin-independent", "@IIR", h.a, new Location(world, 32, 180, 8));
            row("origin-sort", "@IIR{sort=NEAREST;limit=1}", h.a, new Location(world, 32, 180, 8));
            row("self-excluded", "@IIR", near, near.getLocation());
            row("self-included", "@IIR{target=caster}", near, near.getLocation());
            row("self-override", "@IIR{target=caster;targetself=invalid}", near, near.getLocation());
            row("unloaded-origin", "@INO", h.a, new Location(world, 8192, 180, 8192));
            h.a.teleport(new Location(world, 11, 180, 8));
            row("chunk-bounding-box", "@IIR{r=4.9;bb=true}");
            h.a.teleport(new Location(world, 0, 180, 8)); row("negative-reversed", "@IIR{r=-2}");
            h.a.teleport(base);
            owner(near.getUniqueId()); row("owner-excluded", "@IIR{targetowner=false}");
            row("owner-override", "@IIR{ignore=owner;targetowner=true}"); owner(null);

            row("guard-tag", "@IIR{conditions=[ - hastag{t=eligible} true ]}");
            row("guard-before-limit", "@IIR{conditions=[ - hastag{t=eligible} true ];sort=NEAREST;limit=1}");
            row("guard-inverted", "@IIR{conditions=[ - hastag{t=eligible} false ]}");
            row("guard-caster-negative", "@IIR{conditions=[ - hastag{t=caster-only} true ]}");
            row("guard-priority", "@IIR{targetconditions=[ - hastag{t=eligible} true ];conditions=[ - hastag{t=caster-only} true ]}");
            row("guard-type", "@IIR{conditions=[ - entitytype{t=ITEM} true ]}");
            row("guard-distance", "@IIR{conditions=[ - distance{d=<2} true ]}");
            row("guard-unsupported-inverted", "@IIR{conditions=[ - health{h=>0} false ]}");
            threatRows(near, far);
        } finally {
            items.forEach(Entity::remove); items.clear(); labels.clear();
        }
    }

    private void row(String id, String selector) throws Exception { row(id, selector, h.a, base); }
    private void row(String id, String selector, Entity caster, Location origin) throws Exception {
        Set<String> chunks = chunks();
        Object nativeCaster = h.active.get(caster);
        if (nativeCaster == null) nativeCaster = h.nativeType("io.lumine.mythic.api.mobs.GenericCaster")
                .getConstructor(h.abstractEntity).newInstance(h.adapt.invoke(null, caster));
        Object meta = h.metadataType.getConstructor(h.triggerType, h.skillCaster, h.abstractEntity)
                .newInstance(h.triggerType.getMethod("get", String.class).invoke(null, "API"), nativeCaster, h.adapt.invoke(null, player));
        Object nativeOrigin = h.nativeType("io.lumine.mythic.bukkit.BukkitAdapter").getMethod("adapt", Location.class).invoke(null, origin);
        h.skillMetadata.getMethod("setOrigin", h.nativeType("io.lumine.mythic.api.adapters.AbstractLocation")).invoke(meta, nativeOrigin);
        Object targeter = h.nativeSkills.getClass().getMethod("getTargeter", String.class).invoke(h.nativeSkills, selector);
        require(targeter != null, "native targeter missing " + selector);
        @SuppressWarnings("unchecked") Collection<Object> picked = (Collection<Object>) targeter.getClass()
                .getMethod("getEntities", h.skillMetadata).invoke(targeter, meta);
        h.skillMetadata.getMethod("setEntityTargets", Collection.class).invoke(meta, picked);
        targeter.getClass().getMethod("filter", h.skillMetadata, boolean.class).invoke(targeter, meta, false);
        @SuppressWarnings("unchecked") Collection<Object> filtered = (Collection<Object>) h.skillMetadata.getMethod("getEntityTargets").invoke(meta);
        List<String> nativeSelected = new ArrayList<>();
        for (Object target : filtered) nativeSelected.add(label((Entity) h.nativeType("io.lumine.mythic.bukkit.BukkitAdapter")
                .getMethod("adapt", h.abstractEntity).invoke(null, target)));
        nativeSelected.sort(String::compareTo);
        Object context = h.context(caster, null, player, origin, null);
        var providerType = h.runtimeType("dev.helstera.api.behavior.Targeters$ConditionProvider");
        Object provider = Proxy.newProxyInstance(h.runtimeLoader, new Class<?>[]{providerType}, (proxy, method, args) -> {
            Object key = h.skills.getClass().getMethod("bindCondition", String.class).invoke(h.skills, args[0]);
            return key != null && Boolean.TRUE.equals(h.registry.getClass().getMethod("testCondition", String.class, h.contextType).invoke(h.registry, key, args[1]));
        });
        UnaryOperator<String> resolver = value -> { try { return (String) h.resolve.invoke(null, value, context); }
            catch (ReflectiveOperationException error) { throw new IllegalStateException(error); } };
        @SuppressWarnings("unchecked") List<Entity> selected = (List<Entity>) h.runtimeType("dev.helstera.api.behavior.Targeters")
                .getMethod("selectEntities", String.class, h.contextType, Collection.class, List.class, UnaryOperator.class, providerType)
                .invoke(null, selector, context, List.of(h.b), List.of(), resolver, provider);
        List<String> runtimeSelected = selected.stream().map(this::label).sorted().toList();
        clearTags();
        String mechanicSource = "addtag{tag=" + TAG + "}";
        Object config = h.configImpl.getConstructor(String.class).newInstance(mechanicSource);
        Object mechanic = h.nativeType("io.lumine.mythic.core.skills.mechanics.TagAddMechanic")
                .getConstructor(h.executorType, java.io.File.class, String.class, h.configType)
                .newInstance(h.nativeSkills, h.source.toFile(), mechanicSource, config);
        boolean nativeAccepted = true;
        for (Object target : filtered) nativeAccepted &= "SUCCESS".equals(mechanic.getClass()
                .getMethod("castAtEntity", h.skillMetadata, h.abstractEntity).invoke(mechanic, meta, target).toString());
        List<String> nativeEffects = tagged(); clearTags();
        String source = mechanicSource + " " + selector, binding = map(source);
        Object key = h.skills.getClass().getMethod("bindAction", String.class).invoke(h.skills, binding);
        boolean runtimeAccepted = key != null && Boolean.TRUE.equals(h.skills.getClass()
                .getMethod("runAction", String.class, h.contextType).invoke(h.skills, key, context));
        List<String> runtimeEffects = tagged(); clearTags();
        var addedChunks = chunks(); addedChunks.removeAll(chunks);
        var row = new LinkedHashMap<String, Object>();
        row.put("path", path); row.put("case", id); row.put("selector", selector); row.put("source", source); row.put("binding", binding);
        row.put("nativeSelected", nativeSelected); row.put("runtimeSelected", runtimeSelected);
        row.put("nativeEffects", nativeEffects); row.put("runtimeEffects", runtimeEffects);
        row.put("nativeAccepted", nativeAccepted); row.put("runtimeAccepted", runtimeAccepted);
        row.put("loadedChunksAdded", addedChunks.stream().sorted().toList());
        row.put("nativeThreatScores", nativeScores);
        row.put("entities", labels.entrySet().stream().map(entry -> Map.of("label", entry.getValue(),
                "uuid", entry.getKey().getUniqueId().toString(), "type", entry.getKey().getType().name(),
                "world", entry.getKey().getWorld().getName(), "x", entry.getKey().getX(),
                "y", entry.getKey().getY(), "z", entry.getKey().getZ())).toList());
        rows.add(row); getLogger().info("ITEMS_ROW|path=" + path + "|case=" + id + "|observed=true");
    }

    private void threatRows(Item near, Item far) throws Exception {
        Object table = h.active.get(h.a).getClass().getMethod("getThreatTable").invoke(h.active.get(h.a));
        // A living leader prevents native threatSet from trying to cast an Item
        // to LivingEntity in ActiveMob.setTarget. Only score reads are under test.
        table.getClass().getMethod("threatSet", h.abstractEntity, double.class).invoke(table, h.adapt.invoke(null, h.b), 100.0);
        table.getClass().getMethod("threatSet", h.abstractEntity, double.class).invoke(table, h.adapt.invoke(null, near), 5.0);
        table.getClass().getMethod("threatSet", h.abstractEntity, double.class).invoke(table, h.adapt.invoke(null, far), 10.0);
        nativeScores = Map.of("near", (Double) table.getClass().getMethod("getThreat", h.abstractEntity).invoke(table, h.adapt.invoke(null, near)),
                "far", (Double) table.getClass().getMethod("getThreat", h.abstractEntity).invoke(table, h.adapt.invoke(null, far)),
                "living", (Double) table.getClass().getMethod("getThreat", h.abstractEntity).invoke(table, h.adapt.invoke(null, h.b)));
        require(nativeScores.equals(Map.of("near", 5.0, "far", 10.0, "living", 100.0)), "native threat preparation");
        Class<?> targeters = h.runtimeType("dev.helstera.api.behavior.Targeters");
        var field = targeters.getDeclaredField("entityThreatProvider"); field.setAccessible(true); Object previous = field.get(null);
        Class<?> providerType = h.runtimeType("dev.helstera.api.behavior.Targeters$EntityThreatProvider");
        Object provider = Proxy.newProxyInstance(h.runtimeLoader, new Class<?>[]{providerType},
                (proxy, method, args) -> Map.of(near.getUniqueId(), 5.0, far.getUniqueId(), 10.0));
        try {
            targeters.getMethod("entityThreatProvider", providerType).invoke(null, provider);
            row("threat-high", "@IIR{sort=HIGHEST_THREAT;limit=1}");
            row("threat-low", "@IIR{sort=LOWEST_THREAT;limit=1}");
            row("threat-unlimited", "@IIR{sort=HIGHEST_THREAT}");
        } finally {
            nativeScores = Map.of();
            targeters.getMethod("entityThreatProvider", providerType).invoke(null, previous);
        }
    }

    private void owner(UUID owner) throws Exception {
        Object active = h.active.get(h.a);
        if (owner == null) {
            active.getClass().getMethod("removeOwner").invoke(active);
            h.runtimeType("dev.helstera.api.behavior.MobRelations").getMethod("removeOwner", Entity.class).invoke(null, h.a);
        } else {
            active.getClass().getMethod("setOwner", UUID.class).invoke(active, owner);
            h.runtimeType("dev.helstera.api.behavior.MobRelations").getMethod("setOwnerMetadata", Entity.class, UUID.class).invoke(null, h.a, owner);
        }
    }
    private Item item(String label, double x, double y, double z) {
        var location = new Location(base.getWorld(), x, y, z);
        Item item = base.getWorld().dropItem(location, new ItemStack(Material.STONE));
        item.setGravity(false); item.setVelocity(new Vector()); item.setPickupDelay(Integer.MAX_VALUE);
        require(item.teleport(location), "item teleport"); items.add(item); labels.put(item, label); return item;
    }
    private String map(String source) throws Exception {
        if (path.equals("direct")) return source;
        var yaml = new YamlConfiguration(); yaml.set("write.Skills", List.of(source)); yaml.save(h.source.toFile());
        @SuppressWarnings("unchecked") var plans = (List<Map<String, Object>>) h.importer.getClass()
                .getMethod("convert", java.nio.file.Path.class).invoke(h.importer, h.source);
        var plan = plans.stream().filter(p -> "skills.yml".equals(p.get("target-file"))).findFirst().orElseThrow();
        yaml.loadFromString(plan.get("content").toString());
        var actions = yaml.getStringList("skills.write.on-decision");
        require(actions.size() == 1 && plans.stream().noneMatch(p -> "WARN".equals(p.get("severity"))), "import rejected " + source);
        return actions.getFirst();
    }
    private Set<String> chunks() { return java.util.Arrays.stream(base.getWorld().getLoadedChunks())
            .map(chunk -> chunk.getX() + "," + chunk.getZ()).collect(Collectors.toSet()); }
    private void pin(Location location) { Chunk chunk = location.getChunk(); pinned.putIfAbsent(chunk, chunk.isForceLoaded()); chunk.setForceLoaded(true); }
    private void clearTags() { labels.keySet().forEach(entity -> entity.removeScoreboardTag(TAG)); }
    private List<String> tagged() { return labels.keySet().stream().filter(entity -> entity.getScoreboardTags().contains(TAG)).map(this::label).sorted().toList(); }
    private String label(Entity entity) { return labels.getOrDefault(entity, "unexpected:" + entity.getType()); }
    private static void require(boolean value, String message) { if (!value) throw new IllegalStateException(message); }
}
