package dev.helstera.test.randomspawn;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Records actual Display state and brackets metadata packets for two protocol clients. */
public final class DisplayTransformProbe extends JavaPlugin {
    private VariablePlaceholderAudit.Harness h;
    private Player receiver, observer;
    private Display display, control;
    private List<Map<?, ?>> cases;
    private final List<Object> nativeActions = new ArrayList<>();
    private Map<?, ?> sample;
    private YamlConfiguration row;
    private String path, source;
    private int index, waits;

    @Override public void onEnable() { Bukkit.getScheduler().runTaskLater(this, this::begin, 100); }

    private void begin() {
        receiver = Bukkit.getPlayerExact("DisplayReceiver"); observer = Bukkit.getPlayerExact("DisplayObserver");
        if (receiver == null || observer == null) {
            if (++waits > 300) { fail(new IllegalStateException("missing two clients")); return; }
            Bukkit.getScheduler().runTaskLater(this, this::begin, 5); return;
        }
        try {
            h = new VariablePlaceholderAudit.Harness(this, Bukkit.getPluginManager().getPlugin("helsteraMobs"),
                    Bukkit.getPluginManager().getPlugin("MythicMobs"), new Location(receiver.getWorld(), 0, 150, 0), false);
            h.item.remove(); h.a.teleport(new Location(receiver.getWorld(), 110, 180, 0));
            for (Player player : List.of(receiver, observer)) {
                player.setGameMode(GameMode.CREATIVE); player.setGravity(false); player.setInvulnerable(true);
                player.teleport(new Location(player.getWorld(), player == receiver ? 110 : 112, 175, 0));
            }
            cases = YamlConfiguration.loadConfiguration(getDataFolder().toPath().resolve("cases.yml").toFile()).getMapList("cases");
            var identities = new YamlConfiguration();
            for (var entry : Map.of("receiver", receiver, "observer", observer, "caster", h.a, "cow", h.b).entrySet()) {
                identities.set(entry.getKey()+".uuid", entry.getValue().getUniqueId().toString());
                identities.set(entry.getKey()+".entityId", entry.getValue().getEntityId());
            }
            identities.save(getDataFolder().toPath().resolve("identities.yml").toFile());
            for (var entry : cases) {
                String raw = entry.get("mechanic")+"{"+entry.get("arguments")+"}";
                nativeActions.add(h.nativeType("io.lumine.mythic.core.skills.mechanics.DisplayTransformationMechanic")
                        .getConstructor(h.executorType, java.io.File.class, String.class, h.configType)
                        .newInstance(h.nativeSkills, h.source.toFile(), raw, h.configImpl.getConstructor(String.class).newInstance(raw)));
            }
            Bukkit.getScheduler().runTaskLater(this, this::phase, 8);
        } catch (Throwable failure) { fail(failure); }
    }

    private String text(String key) { return String.valueOf(sample.get(key)); }
    private Transformation seed() {
        return new Transformation(new Vector3f(1,2,3), new Quaternionf(1,2,3,4),
                new Vector3f(2,3,4), new Quaternionf(-2,3,1,2));
    }
    private Display spawn(String type, int x) {
        Location at = new Location(receiver.getWorld(), x, 170, 0);
        Display entity = switch (type) {
            case "text" -> at.getWorld().spawn(at, TextDisplay.class, item -> item.text(Component.text("Transform")));
            case "item" -> at.getWorld().spawn(at, ItemDisplay.class, item -> item.setItemStack(new ItemStack(Material.DIAMOND)));
            default -> at.getWorld().spawn(at, BlockDisplay.class, item -> item.setBlock(Material.STONE.createBlockData()));
        };
        entity.setGravity(false); entity.setTransformation(seed()); entity.setInterpolationDelay(3);
        entity.setInterpolationDuration(12); entity.setTeleportDuration(2); return entity;
    }
    private void phase() {
        if (index >= cases.size()*3) {
            tell("DISPLAY_TRANSFORM_DONE"); getLogger().info("DISPLAY_TRANSFORM_SUMMARY|observations="+index+"|completed=true"); return;
        }
        try {
            if (display != null) display.remove(); if (control != null) control.remove();
            sample = cases.get(index/3); path = List.of("native", "direct", "imported").get(index%3);
            display = spawn(text("display"), 110); control = spawn("block", 113);
            source = text("mechanic")+"{"+text("arguments")+"}"+(text("selector").equals("none") ? "" : " @trigger");
            row = new YamlConfiguration(); row.set("index", index); row.set("path", path); row.set("case", text("key")); row.set("source", source);
            row.set("displayId", display.getEntityId()); row.set("displayUuid", display.getUniqueId().toString()); row.set("displayType", display.getType().name());
            row.set("controlId", control.getEntityId());
            tell("DISPLAY_TRANSFORM_PHASE "+index+" "+path+" "+text("key"));
            tell("DISPLAY_TRANSFORM_TARGET "+index+" "+display.getEntityId()+" "+control.getEntityId());
            Bukkit.getScheduler().runTaskLater(this, this::cast, 8);
        } catch (Throwable failure) { fail(failure); }
    }
    private void cast() {
        try {
            row.set("before", state()); row.set("startTick", Bukkit.getCurrentTick()); row.set("primary", Bukkit.isPrimaryThread());
            Entity casterEntity = text("selector").equals("none") ? display : h.a;
            Entity recipient = text("selector").equals("cow") ? h.b : display;
            row.set("caster", casterEntity.getUniqueId().toString()); row.set("target", recipient.getUniqueId().toString());
            tell("DISPLAY_TRANSFORM_TICK "+index+" 0");
            if (path.equals("native")) {
                row.set("mapped", source);
                Object caster = h.nativeType("io.lumine.mythic.api.mobs.GenericCaster").getConstructor(h.abstractEntity).newInstance(h.adapt.invoke(null, casterEntity));
                Object metadata = h.metadataType.getConstructor(h.triggerType,h.skillCaster,h.abstractEntity).newInstance(
                        h.triggerType.getMethod("get",String.class).invoke(null,"API"), caster, h.adapt.invoke(null,recipient));
                Object action = nativeActions.get(index/3);
                try {
                    Object result = text("selector").equals("none") ? action.getClass().getMethod("cast", h.skillMetadata).invoke(action, metadata)
                            : action.getClass().getMethod("castAtEntity", h.skillMetadata, h.abstractEntity).invoke(action, metadata, h.adapt.invoke(null, recipient));
                    row.set("result", result.toString());
                } catch (InvocationTargetException failure) { row.set("result", failure.getCause().getClass().getSimpleName()); }
            } else {
                String mapped = path.equals("imported") ? imported(source) : source;
                row.set("mapped", mapped == null ? "UNSUPPORTED" : mapped);
                String key = mapped == null ? null : (String) h.skills.getClass().getMethod("bindAction",String.class).invoke(h.skills,mapped);
                Object context = h.context(casterEntity,h.b,recipient,casterEntity.getLocation(),h.newTree(recipient,h.b,casterEntity.getLocation()));
                row.set("result", key == null ? "REJECTED" : (Boolean)h.skills.getClass().getMethod("runAction",String.class,h.contextType)
                        .invoke(h.skills,key,context) ? "EXECUTED" : "FAILED");
            }
            row.set("after",state()); tick(1);
        } catch (Throwable failure) { fail(failure); }
    }
    private void tick(int offset) {
        Bukkit.getScheduler().runTaskLater(this, () -> {
            try {
                tell("DISPLAY_TRANSFORM_TICK "+index+" "+offset);
                if (offset < 15) { tick(offset+1); return; }
                row.set("settled",state()); row.set("endTick",Bukkit.getCurrentTick());
                var file = getDataFolder().toPath().resolve("observations/%04d.yml".formatted(index));
                Files.createDirectories(file.getParent()); row.save(file.toFile());
                getLogger().info("DISPLAY_TRANSFORM_ROW|index="+index+"|case="+text("key")+"|path="+path);
                tell("DISPLAY_TRANSFORM_END "+index); index++; Bukkit.getScheduler().runTaskLater(this,this::phase,3);
            } catch (Throwable failure) { fail(failure); }
        },1);
    }
    private String imported(String raw) throws Exception {
        var yaml = new YamlConfiguration(); yaml.set("display.Skills",List.of(raw)); yaml.save(h.source.toFile());
        @SuppressWarnings("unchecked") var plans = (List<Map<String,Object>>)h.importer.getClass().getMethod("convert",java.nio.file.Path.class).invoke(h.importer,h.source);
        var plan = plans.stream().filter(p -> "skills.yml".equals(p.get("target-file"))).findFirst().orElseThrow();
        yaml.loadFromString(plan.get("content").toString()); var actions = yaml.getStringList("skills.display.on-decision");
        if (actions.isEmpty() && yaml.getStringList("skills.display.unsupported").equals(List.of(raw))) return null;
        if (actions.size()!=1) throw new IllegalStateException("import failed "+raw); return actions.getFirst();
    }
    private Map<String,Object> state() { return Map.of("display", snapshot(display), "control", snapshot(control)); }
    private Map<String,Object> snapshot(Display entity) {
        var t = entity.getTransformation();
        return Map.of("translation", vector(t.getTranslation()), "scale", vector(t.getScale()),
                "left_rotation", quaternion(t.getLeftRotation()), "right_rotation", quaternion(t.getRightRotation()),
                "delay", entity.getInterpolationDelay(), "duration", entity.getInterpolationDuration(), "teleport", entity.getTeleportDuration());
    }
    private List<Float> vector(Vector3f v) { return List.of(v.x,v.y,v.z); }
    private List<Float> quaternion(Quaternionf q) { return List.of(q.x,q.y,q.z,q.w); }
    private void tell(String message) { receiver.sendMessage(message); observer.sendMessage(message); }
    private void fail(Throwable failure) { getLogger().severe("DISPLAY_TRANSFORM_FAILED|"+failure); failure.printStackTrace(); }
}
