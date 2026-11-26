package dev.helstera.test.randomspawn;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Pillager;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** Pinned native casts and imported/direct Helstera mutations with real nonliving recipients. */
public final class EntitySettersProbe extends JavaPlugin {
    private final List<Entity> spawned = new ArrayList<>();
    private final List<Map<String, Object>> rows = new ArrayList<>();
    private final List<org.bukkit.Chunk> tickets = new ArrayList<>();
    private VariablePlaceholderAudit.Harness h;
    private Object metadata;
    private String path;
    private boolean chunkBefore;
    private org.bukkit.Chunk chunk;

    @Override public void onEnable() {
        Bukkit.getScheduler().runTaskLater(this, () -> {
            try {
                // The shared harness spawns at x=110; keep all probe entities in
                // fully loaded chunks before native and runtime casts inspect validity.
                for (var world : Bukkit.getWorlds()) {
                    for (int x = 6; x <= 8; x++) {
                        var ticket = world.getChunkAt(x, 0);
                        ticket.addPluginChunkTicket(this);
                        tickets.add(ticket);
                    }
                }
                for (String route : List.of("direct", "imported")) run(route);
                getLogger().info("SETTERS_SUMMARY|observations=" + rows.size() + "|passed=true");
            } catch (Throwable error) {
                getLogger().severe("SETTERS_FAILED|" + error); error.printStackTrace();
            } finally {
                try {
                    var output = new YamlConfiguration(); output.set("observations", rows);
                    Files.createDirectories(getDataFolder().toPath());
                    output.save(getDataFolder().toPath().resolve("observations.yml").toFile());
                } catch (Exception failure) { getLogger().severe("SETTERS_FAILED|save=" + failure); }
                for (Entity entity : spawned) entity.remove();
                if (chunk != null) chunk.setForceLoaded(chunkBefore);
                for (var ticket : tickets) ticket.removePluginChunkTicket(this);
            }
        }, 100);
    }

    private void run(String route) throws Exception {
        path = route;
        var runtime = Bukkit.getPluginManager().getPlugin("helsteraMobs");
        var mythic = Bukkit.getPluginManager().getPlugin("MythicMobs");
        try (var harness = new VariablePlaceholderAudit.Harness(this, runtime, mythic,
                new Location(Bukkit.getWorlds().getFirst(), 0, 150, 0), route.equals("imported"))) {
            h = harness;
            metadata = h.meta(h.a, h.c);
            Location point = h.a.getLocation().clone().add(12, 0, 0);
            Interaction interaction = spawn(point, Interaction.class);
            TextDisplay text = spawn(point.clone().add(2, 0, 0), TextDisplay.class);
            ItemDisplay item = spawn(point.clone().add(4, 0, 0), ItemDisplay.class);
            Pillager raider = spawn(point.clone().add(6, 0, 0), Pillager.class); raider.setAI(false);
            compare("malus-value", "setmalus{type=WATER;amount=-1}", "SetPathfindingMalusMechanic", h.b,
                    () -> setCost(h.b, "WATER", 8), () -> cost(h.b, "WATER"), -1.0);
            compare("malus-default", "malus{}", "SetPathfindingMalusMechanic", h.b,
                    () -> setCost(h.b, "OPEN", 5), () -> cost(h.b, "OPEN"), 0.0);
            compare("leash-set", "setleasher{}", "SetLeashHolderMechanic", h.b,
                    () -> h.a.setLeashHolder(null), this::holder, "recipient");
            compare("leash-remove", "setleashholder{r=true}", "SetLeashHolderMechanic", h.b,
                    () -> h.a.setLeashHolder(h.b), this::holder, "none");
            chunk = point.getChunk(); chunkBefore = chunk.isForceLoaded();
            try {
                compareLocation("chunk-enable", "setchunkforceloaded{}", point, false, true);
                compareLocation("chunk-disable", "setchunkforceloaded{l=false}", point, true, false);
            } finally { chunk.setForceLoaded(chunkBefore); chunk = null; }
            compare("text-placeholder", "settext{t=<red>Y=<target.l.y></red>}", "SetTextDisplayMechanic", text,
                    () -> text.text(net.kyori.adventure.text.Component.empty()),
                    () -> textState(text), List.of("Y=" + text.getLocation().getY(), "#ff5555"));
            compare("text-clear", "settextdisplay{}", "SetTextDisplayMechanic", text,
                    () -> text.text(net.kyori.adventure.text.Component.text("before")),
                    () -> textState(text), List.of("", "none"));
            compare("item-set", "setdisplayentityitem{mat=DIAMOND}", "SetDisplayEntityItemMechanic", item,
                    () -> item.setItemStack(new ItemStack(Material.STONE)), () -> item.getItemStack().getType().name(), "DIAMOND");
            compare("item-clear", "setdisplayentityitem{}", "SetDisplayEntityItemMechanic", item,
                    () -> item.setItemStack(new ItemStack(Material.STONE)), () -> item.getItemStack().getType().name(), "AIR");
            compare("interaction-placeholder", "interactionsize{h=<target.l.y>;w=0}", "SetInteractionSizeMechanic", interaction,
                    () -> dimensions(interaction, 2, 3), () -> dimensions(interaction),
                    List.of((double) interaction.getLocation().getBlockY(), 0.0));
            compare("interaction-static", "interactionsize{h=4.5;w=2.25}", "SetInteractionSizeMechanic", interaction,
                    () -> dimensions(interaction, 2, 3), () -> dimensions(interaction), List.of(4.5, 2.25));
            compare("interaction-default", "setinteractionsize{}", "SetInteractionSizeMechanic", interaction,
                    () -> dimensions(interaction, 2, 3), () -> dimensions(interaction), List.of(1.0, 1.0));
            compare("interaction-negative", "setinteractionsize{h=-2;w=-3}", "SetInteractionSizeMechanic", interaction,
                    () -> dimensions(interaction, 2, 3), () -> dimensions(interaction), List.of(-2.0, -3.0));
            compare("raider-can", "setcanjoinraid{c=false}", "SetRaiderCanJoinRaidMechanic", raider,
                    () -> raider.setCanJoinRaid(true), raider::isCanJoinRaid, false);
            compare("raider-leader", "setraiderleader{}", "SetRaiderPatrolLeaderMechanic", raider,
                    () -> raider.setPatrolLeader(false), raider::isPatrolLeader, true);
            compare("raider-patrol", "setraiderblock{b=@selflocation}", "SetRaiderPatrolBlockMechanic", raider,
                    () -> raider.setPatrolTarget(point.getBlock()), () -> block(raider.getPatrolTarget()), block(h.a.getLocation().getBlock()));
            compare("wrong-entity", "setraiderleader{}", "SetRaiderPatrolLeaderMechanic", h.b,
                    () -> raider.setPatrolLeader(false), raider::isPatrolLeader, false);
            invalid(interaction, "invalid-dimension", "setinteractionsize{w=Infinity} @trigger");
            invalid(interaction, "unknown-field", "setinteractionsize{unknown=9} @trigger");
            invalid(interaction, "missing-patrol", "setraiderblock @trigger");
        }
    }

    private <T extends Entity> T spawn(Location location, Class<T> type) {
        T entity = location.getWorld().spawn(location, type); entity.setGravity(false); spawned.add(entity); return entity;
    }
    private String holder() { return !h.a.isLeashed() ? "none" : h.a.getLeashHolder() == h.b ? "recipient" : "other"; }
    private static void dimensions(Interaction entity, float height, float width) {
        entity.setInteractionHeight(height); entity.setInteractionWidth(width);
    }
    private static List<Double> dimensions(Interaction entity) {
        return List.of((double) entity.getInteractionHeight(), (double) entity.getInteractionWidth());
    }
    private static List<String> textState(TextDisplay entity) {
        var component = entity.text();
        var color = component.color();
        return List.of(net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                .serialize(component), color == null ? "none" : color.asHexString());
    }
    private static String block(org.bukkit.block.Block block) {
        return block == null ? "none" : block.getWorld().getName() + ":" + block.getX() + "," + block.getY() + "," + block.getZ();
    }
    private Object mechanic(String source, String simpleName) throws Exception {
        Class<?> type = h.nativeType("io.lumine.mythic.core.skills.mechanics." + simpleName);
        Object config = h.configImpl.getConstructor(String.class).newInstance(source);
        Object action = simpleName.equals("SetPathfindingMalusMechanic")
                ? type.getConstructor(h.executorType, java.io.File.class, String.class, h.configType, int.class)
                    .newInstance(h.nativeSkills, h.source.toFile(), source, config, 0)
                : type.getConstructor(h.executorType, java.io.File.class, String.class, h.configType)
                    .newInstance(h.nativeSkills, h.source.toFile(), source, config);
        Object clock = h.mythic.getClass().getMethod("getClock").invoke(h.mythic);
        clock.getClass().getMethod("runSecondPass").invoke(clock);
        return action;
    }
    private void compare(String id, String source, String type, Entity recipient, Runnable reset,
                         Supplier<Object> snapshot, Object expected) throws Exception {
        if (!recipient.isValid() || recipient.isDead() || !recipient.getLocation().getChunk().isLoaded())
            throw new IllegalStateException("Invalid fixture recipient before " + id + ": valid="
                    + recipient.isValid() + ", dead=" + recipient.isDead());
        reset.run(); Object before = snapshot.get();
        Object action = mechanic(source, type);
        String result = action.getClass().getMethod("castAtEntity", h.skillMetadata, h.abstractEntity)
                .invoke(action, metadata, h.adapt.invoke(null, recipient)).toString();
        Object nativeAfter = snapshot.get(); reset.run();
        Object context = h.context(h.a, null, recipient, h.a.getLocation(), null);
        String binding = map(source + " @trigger");
        dispatch(binding, context);
        record(id, source + " @trigger", binding, before, result, nativeAfter, snapshot.get(), expected, false);
    }
    private void compareLocation(String id, String source, Location location, boolean seed, boolean expected) throws Exception {
        chunk.setForceLoaded(seed);
        Object action = mechanic(source, "SetChunkForceLoadedMechanic");
        Class<?> locationType = h.nativeType("io.lumine.mythic.api.adapters.AbstractLocation");
        Object nativeLocation = h.nativeType("io.lumine.mythic.bukkit.BukkitAdapter")
                .getMethod("adapt", Location.class).invoke(null, location);
        String result = action.getClass().getMethod("castAtLocation", h.skillMetadata, locationType)
                .invoke(action, metadata, nativeLocation).toString();
        boolean nativeAfter = chunk.isForceLoaded(); chunk.setForceLoaded(seed);
        Object context = h.context(h.a, null, h.c, location, null);
        String binding = map(source + " @origin"); dispatch(binding, context);
        record(id, source + " @origin", binding, seed, result, nativeAfter, chunk.isForceLoaded(), expected, false);
    }
    private void invalid(Interaction recipient, String id, String source) throws Exception {
        dimensions(recipient, 2, 3);
        String mapped = path.equals("imported") ? mapOptional(source) : source;
        boolean rejected = mapped == null || h.skills.getClass().getMethod("bindAction", String.class).invoke(h.skills, mapped) == null;
        record(id, source, mapped == null ? "preserved-unsupported" : mapped, List.of(2.0, 3.0),
                "not-invoked-invalid", "not-invoked-invalid", dimensions(recipient), List.of(2.0, 3.0), rejected);
    }
    private void dispatch(String source, Object context) throws Exception {
        Object key = h.skills.getClass().getMethod("bindAction", String.class).invoke(h.skills, source);
        if (key == null || !(boolean) h.skills.getClass().getMethod("runAction", String.class, h.contextType)
                .invoke(h.skills, key, context)) throw new IllegalStateException("Runtime bind failed: " + source);
    }
    private String map(String source) throws Exception {
        String mapped = path.equals("imported") ? mapOptional(source) : source;
        if (mapped == null) throw new IllegalStateException("Import rejected: " + source); return mapped;
    }
    private String mapOptional(String source) throws Exception {
        var yaml = new YamlConfiguration(); yaml.set("write.Skills", List.of(source)); yaml.save(h.source.toFile());
        @SuppressWarnings("unchecked") var plans = (List<Map<String, Object>>) h.importer.getClass()
                .getMethod("convert", java.nio.file.Path.class).invoke(h.importer, h.source);
        var plan = plans.stream().filter(p -> "skills.yml".equals(p.get("target-file"))).findFirst().orElseThrow();
        yaml.loadFromString(plan.get("content").toString());
        List<String> actions = yaml.getStringList("skills.write.on-decision");
        if (actions.isEmpty()) {
            if (!yaml.getStringList("skills.write.unsupported").contains(source))
                throw new IllegalStateException("Rejected source not preserved: " + source);
            return null;
        }
        if (actions.size() != 1 || plans.stream().anyMatch(p -> "WARN".equals(p.get("severity"))))
            throw new IllegalStateException("Unexpected import diagnostics: " + source);
        return actions.getFirst();
    }
    private void record(String id, String source, String binding, Object before, String result,
                        Object nativeAfter, Object runtimeAfter, Object expected, boolean rejected) {
        boolean invalid = id.startsWith("invalid-") || id.equals("unknown-field") || id.equals("missing-patrol");
        boolean premiumGate = id.equals("interaction-placeholder");
        Object nativeExpected = premiumGate ? List.of(1.0, 0.0) : expected;
        boolean matched = expected.equals(runtimeAfter) && (invalid ? rejected : nativeExpected.equals(nativeAfter) && result.equals("SUCCESS"));
        var row = new LinkedHashMap<String, Object>();
        row.put("path", path); row.put("case", id); row.put("source", source); row.put("binding", binding);
        row.put("before", before); row.put("nativeResult", result); row.put("nativeAfter", nativeAfter);
        row.put("runtimeAfter", runtimeAfter); row.put("expected", expected); row.put("rejected", rejected); row.put("matched", matched);
        row.put("nativeExpected", nativeExpected);
        row.put("difference", premiumGate ? "public-placeholder-float-premium-gate" : "none");
        rows.add(row); getLogger().info("SETTERS_ROW|path=" + path + "|case=" + id + "|matched=" + matched);
        if (!matched) throw new IllegalStateException("Setter mismatch " + id + ": " + row);
    }
    private static double cost(Entity entity, String name) {
        try {
            Object handle = entity.getClass().getMethod("getHandle").invoke(entity);
            Class<?> type = Class.forName("net.minecraft.world.level.pathfinder.PathType");
            Object key = java.util.Arrays.stream(type.getEnumConstants()).filter(v -> ((Enum<?>) v).name().equals(name)).findFirst().orElseThrow();
            return ((Number) handle.getClass().getMethod("getPathfindingMalus", type).invoke(handle, key)).doubleValue();
        } catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
    }
    private static void setCost(Entity entity, String name, float value) {
        try {
            Object handle = entity.getClass().getMethod("getHandle").invoke(entity);
            Class<?> type = Class.forName("net.minecraft.world.level.pathfinder.PathType");
            Object key = java.util.Arrays.stream(type.getEnumConstants()).filter(v -> ((Enum<?>) v).name().equals(name)).findFirst().orElseThrow();
            handle.getClass().getMethod("setPathfindingMalus", type, float.class).invoke(handle, key, value);
        } catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
    }
}
