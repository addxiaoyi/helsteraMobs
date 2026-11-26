package dev.helstera.test.randomspawn;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Native drop-manager initialization, real inventories and two connected protocol clients. */
public final class GeneratedLootProbe extends JavaPlugin {
    private VariablePlaceholderAudit.Harness h;
    private Player receiver, observer;
    private Interaction nonliving;
    private Plugin runtime, mythic;
    private List<Map<?, ?>> cases;
    private final List<Object> nativeActions = new ArrayList<>();
    private final List<Item> dropped = new ArrayList<>();
    private int index, waits;
    private Map<?, ?> sample;
    private YamlConfiguration row;
    private Entity caster, target;
    private Location origin;
    private String path, source;

    @Override public void onEnable() { Bukkit.getScheduler().runTaskLater(this, this::begin, 100); }

    private void begin() {
        receiver = Bukkit.getPlayerExact("CopyReceiver"); observer = Bukkit.getPlayerExact("CopyObserver");
        if (receiver == null || observer == null) {
            if (++waits > 300) { fail(new IllegalStateException("two clients missing")); return; }
            Bukkit.getScheduler().runTaskLater(this, this::begin, 5); return;
        }
        try {
            runtime = Bukkit.getPluginManager().getPlugin("helsteraMobs");
            mythic = Bukkit.getPluginManager().getPlugin("MythicMobs");
            var center = new Location(Bukkit.getWorlds().getFirst(), 0, 150, 0);
            h = new VariablePlaceholderAudit.Harness(this, runtime, mythic, center, false);
            h.item.remove();
            origin = center.clone().add(113, 20, 3);
            nonliving = origin.getWorld().spawn(origin.clone().add(1, 0, 0), Interaction.class);
            for (Player player : List.of(receiver, observer)) {
                player.setGameMode(GameMode.CREATIVE); player.setGravity(false);
                player.teleport(center.clone().add(player == receiver ? 110 : 112, 20, 0));
            }
            var board = Bukkit.getScoreboardManager().getMainScoreboard();
            if (board.getObjective("grantAudit") == null) board.registerNewObjective("grantAudit", "dummy");
            cases = YamlConfiguration.loadConfiguration(getDataFolder().toPath().resolve("cases.yml").toFile()).getMapList("cases");
            var ids = new YamlConfiguration();
            for (var entry : Map.of("receiver", receiver, "observer", observer, "cow", h.a, "targetCow", h.b, "nonliving", nonliving).entrySet()) {
                ids.set(entry.getKey()+".uuid", entry.getValue().getUniqueId().toString());
                ids.set(entry.getKey()+".entityId", entry.getValue().getEntityId());
            }
            ids.save(getDataFolder().toPath().resolve("identities.yml").toFile());
            // Use the production importer for named tables, including source-name preservation.
            var tablesFile = mythic.getDataFolder().toPath().resolve("DropTables/generated-loot.yml");
            @SuppressWarnings("unchecked") var plans = (List<Map<String,Object>>) h.importer.getClass()
                    .getMethod("convert", java.nio.file.Path.class).invoke(h.importer, tablesFile);
            var combined = new YamlConfiguration();
            for (var plan : plans) if ("loot.yml".equals(plan.get("target-file"))) {
                var part = new YamlConfiguration(); part.loadFromString(plan.get("content").toString());
                part.getValues(true).forEach((key, value) -> {
                    if (!(value instanceof org.bukkit.configuration.ConfigurationSection)) combined.set(key, value);
                });
            }
            combined.save(getDataFolder().toPath().resolve("imported-loot.yml").toFile());
            Object loot = runtime.getClass().getMethod("loot").invoke(runtime);
            loot.getClass().getMethod("load", org.bukkit.configuration.ConfigurationSection.class)
                    .invoke(loot, combined.getConfigurationSection("tables"));
            var itemFile = mythic.getDataFolder().toPath().resolve("Items/generated-loot.yml");
            @SuppressWarnings("unchecked") var itemPlans = (List<Map<String,Object>>) h.importer.getClass()
                    .getMethod("convert", java.nio.file.Path.class).invoke(h.importer, itemFile);
            var itemPlan = itemPlans.stream().filter(p -> "items.yml".equals(p.get("target-file"))).findFirst().orElseThrow();
            Files.writeString(runtime.getDataFolder().toPath().resolve("items.yml"), itemPlan.get("content").toString());
            runtime.getClass().getMethod("reloadCustomItems").invoke(runtime);
            for (var entry : cases) {
                String raw = entry.get("mechanic")+"{"+entry.get("arguments")+"}";
                nativeActions.add(h.nativeType("io.lumine.mythic.core.skills.mechanics.GiveItemFromTargetMechanic")
                        .getConstructor(h.executorType, java.io.File.class, String.class, h.configType)
                        .newInstance(h.nativeSkills, h.source.toFile(), raw, h.configImpl.getConstructor(String.class).newInstance(raw)));
            }
            // Exercise the actual queued second pass; constructing and immediately casting is invalid.
            Object clock = mythic.getClass().getMethod("getClock").invoke(mythic);
            clock.getClass().getMethod("runSecondPass").invoke(clock);
            Bukkit.getScheduler().runTaskLater(this, this::phase, 8);
        } catch (Throwable failure) { fail(failure); }
    }

    private String text(String key) { return String.valueOf(sample.get(key)); }
    private void phase() {
        if (index >= cases.size()*3) {
            tell("ITEM_COPY_DONE");
            getLogger().info("GENERATED_LOOT_SUMMARY|observations="+index+"|completed=true"); return;
        }
        try {
            sample = cases.get(index/3); path = List.of("native", "direct", "imported").get(index%3);
            for (Item item : dropped) item.remove(); dropped.clear();
            for (Player player : List.of(receiver, observer)) {
                player.getInventory().clear(); player.setExp(0); player.setLevel(0); player.setTotalExperience(0);
                Bukkit.getScoreboardManager().getMainScoreboard().getObjective("grantAudit").getScore(player.getName()).setScore(0);
            }
            if (!text("inventory").equals("empty")) {
                for (int i=0;i<36;i++) receiver.getInventory().setItem(i,new ItemStack(Material.STONE,64));
                if (text("inventory").equals("partial")) receiver.getInventory().setItem(0,new ItemStack(Material.DIAMOND,62));
            }
            caster = text("source").equals("cow") ? h.a : receiver;
            target = switch(text("target")) { case "cow" -> h.b; case "nonliving" -> nonliving; default -> observer; };
            source = text("mechanic")+"{"+text("arguments")+"} @"+(text("target").equals("location") ? "origin" : "trigger");
            row = new YamlConfiguration();
            row.set("index",index); row.set("case",text("key")); row.set("path",path); row.set("source",source);
            row.set("caster",caster.getUniqueId().toString()); row.set("target",target.getUniqueId().toString());
            row.set("origin",location(text("target").equals("location")?origin:target.getLocation()));
            row.set("casterLocation",location(caster.getLocation())); row.set("before",state());
            tell("ITEM_COPY_PHASE "+index+" "+path+" "+text("key"));
            Bukkit.getScheduler().runTaskLater(this,this::cast,2);
        } catch(Throwable failure) { fail(failure); }
    }

    private void cast() {
        try {
            row.set("startTick",Bukkit.getCurrentTick()); row.set("primary",Bukkit.isPrimaryThread());
            tell("ITEM_COPY_TICK "+index+" 0");
            if(path.equals("native")) { row.set("mapped",source); row.set("result",nativeCast()); }
            else {
                String mapped = path.equals("imported") ? imported(source) : source; row.set("mapped",mapped);
                Object tree = h.newTree(target, h.b, origin);
                Object context = h.context(caster,h.b,target,origin,tree);
                String key = (String)h.skills.getClass().getMethod("bindAction",String.class).invoke(h.skills,mapped);
                if(key==null) throw new IllegalStateException("binding failed: "+mapped);
                boolean ran=(Boolean)h.skills.getClass().getMethod("runAction",String.class,h.contextType).invoke(h.skills,key,context);
                row.set("result",ran?"EXECUTED":"REJECTED");
            }
            for(Item item:origin.getWorld().getEntitiesByClass(Item.class)) {
                dropped.add(item); item.setGravity(false); item.setVelocity(new org.bukkit.util.Vector()); item.setPickupDelay(Integer.MAX_VALUE);
            }
            row.set("after",state()); tick(1);
        } catch(Throwable failure) { fail(failure); }
    }

    private String nativeCast() throws Exception {
        Object action=nativeActions.get(index/3);
        Object nativeCaster=h.nativeType("io.lumine.mythic.api.mobs.GenericCaster")
                .getConstructor(h.abstractEntity).newInstance(h.adapt.invoke(null,caster));
        Object metadata=h.metadataType.getConstructor(h.triggerType,h.skillCaster,h.abstractEntity).newInstance(
                h.triggerType.getMethod("get",String.class).invoke(null,"API"),nativeCaster,h.adapt.invoke(null,observer));
        try {
            if(text("target").equals("location")) {
                Class<?> type=h.nativeType("io.lumine.mythic.api.adapters.AbstractLocation");
                Object at=h.nativeType("io.lumine.mythic.bukkit.BukkitAdapter").getMethod("adapt",Location.class).invoke(null,origin);
                return action.getClass().getMethod("castAtLocation",h.skillMetadata,type).invoke(action,metadata,at).toString();
            }
            return action.getClass().getMethod("castAtEntity",h.skillMetadata,h.abstractEntity)
                    .invoke(action,metadata,h.adapt.invoke(null,target)).toString();
        } catch(InvocationTargetException failure) { return failure.getCause().getClass().getSimpleName(); }
    }

    private String imported(String raw) throws Exception {
        var input=new YamlConfiguration(); input.set("grant.Skills",List.of(raw)); input.save(h.source.toFile());
        @SuppressWarnings("unchecked") var plans=(List<Map<String,Object>>)h.importer.getClass()
                .getMethod("convert",java.nio.file.Path.class).invoke(h.importer,h.source);
        var plan=plans.stream().filter(p->"skills.yml".equals(p.get("target-file"))).findFirst().orElseThrow();
        input.loadFromString(plan.get("content").toString());
        var actions=input.getStringList("skills.grant.on-decision");
        if(actions.size()!=1 || input.contains("skills.grant.unsupported")) throw new IllegalStateException("import failed: "+raw);
        return actions.getFirst();
    }

    private void tick(int offset) {
        Bukkit.getScheduler().runTaskLater(this,()->{
            try {
                tell("ITEM_COPY_TICK "+index+" "+offset);
                if(offset<10) { tick(offset+1); return; }
                row.set("settled",state()); row.set("endTick",Bukkit.getCurrentTick());
                row.set("entityIds",origin.getWorld().getEntities().stream().map(Entity::getEntityId).sorted().toList());
                var file=getDataFolder().toPath().resolve("observations/%04d.yml".formatted(index));
                Files.createDirectories(file.getParent()); row.save(file.toFile());
                getLogger().info("GENERATED_LOOT_ROW|index="+index+"|case="+text("key")+"|path="+path);
                tell("ITEM_COPY_END "+index); index++; Bukkit.getScheduler().runTaskLater(this,this::phase,3);
            } catch(Throwable failure) { fail(failure); }
        },1);
    }

    private Map<String,Object> state() {
        var state=new LinkedHashMap<String,Object>();
        state.put("receiver",inventory(receiver)); state.put("observer",inventory(observer));
        state.put("xp",receiver.getTotalExperience()); state.put("observerXp",observer.getTotalExperience());
        var objective=Bukkit.getScoreboardManager().getMainScoreboard().getObjective("grantAudit");
        state.put("score",objective.getScore(receiver.getName()).getScore());
        state.put("observerScore",objective.getScore(observer.getName()).getScore());
        state.put("op",receiver.isOp());
        state.put("drops",dropped.stream().filter(Entity::isValid).map(item->{
            var value=new LinkedHashMap<String,Object>();
            value.put("entityId",item.getEntityId());value.put("type",item.getItemStack().getType().name());value.put("amount",item.getItemStack().getAmount());
            value.put("model",model(item.getItemStack()));
            value.put("data",java.util.Base64.getEncoder().encodeToString(item.getItemStack().serializeAsBytes()));
            value.put("owner",String.valueOf(item.getOwner()));value.put("thrower",String.valueOf(item.getThrower()));
            value.put("canMobPickup",item.canMobPickup());value.put("location",location(item.getLocation()));return value;
        }).toList());
        return state;
    }
    private List<Map<String,Object>> inventory(Player player) {
        List<Map<String,Object>> out=new ArrayList<>();var items=player.getInventory().getStorageContents();
        for(int slot=0;slot<items.length;slot++) if(items[slot]!=null&&!items[slot].getType().isAir())
            out.add(Map.of("slot",slot,"type",items[slot].getType().name(),"amount",items[slot].getAmount(),
                    "model",model(items[slot]),"data",java.util.Base64.getEncoder().encodeToString(items[slot].serializeAsBytes())));
        return out;
    }
    private int model(ItemStack item) { var meta=item.getItemMeta(); return meta.hasCustomModelData()?meta.getCustomModelData():0; }
    private Map<String,Object> location(Location location) {return Map.of("world",location.getWorld().getName(),"x",location.getX(),"y",location.getY(),"z",location.getZ());}
    private void tell(String message) { receiver.sendMessage(message); observer.sendMessage(message); }
    private void fail(Throwable failure) { getLogger().severe("GENERATED_LOOT_FAILED|"+failure); failure.printStackTrace(); }
}
