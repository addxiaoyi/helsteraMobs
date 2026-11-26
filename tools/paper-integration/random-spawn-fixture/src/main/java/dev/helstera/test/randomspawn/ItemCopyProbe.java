package dev.helstera.test.randomspawn;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Actual inventories and two-client private packet observations. No synthetic evidence is written. */
public final class ItemCopyProbe extends JavaPlugin {
    private static final List<EquipmentSlot> SLOTS = List.of(EquipmentSlot.HEAD, EquipmentSlot.CHEST,
            EquipmentSlot.LEGS, EquipmentSlot.FEET, EquipmentSlot.HAND, EquipmentSlot.OFF_HAND);
    private static final List<Material> MATERIALS = List.of(Material.EMERALD, Material.DIAMOND,
            Material.AMETHYST_SHARD, Material.LAPIS_LAZULI, Material.IRON_INGOT, Material.GOLD_INGOT);
    private final Set<Chunk> tickets = new LinkedHashSet<>();
    private final List<Item> dropped = new ArrayList<>();
    private Player receiver, observer;
    private Plugin runtime;
    private VariablePlaceholderAudit.Harness h;
    private Interaction nonliving;
    private Location origin, receiverLocation;
    private List<Map<?, ?>> cases;
    private int index, waits;
    private YamlConfiguration row;
    private Map<?, ?> sample;
    private Entity caster, target;
    private String path, key;

    @Override public void onEnable() { Bukkit.getScheduler().runTaskLater(this, this::begin, 100); }

    private void begin() {
        receiver = Bukkit.getPlayerExact("CopyReceiver"); observer = Bukkit.getPlayerExact("CopyObserver");
        if (receiver == null || observer == null) {
            if (++waits > 300) { fail(new IllegalStateException("missing two clients")); return; }
            Bukkit.getScheduler().runTaskLater(this, this::begin, 5); return;
        }
        try {
            runtime = Bukkit.getPluginManager().getPlugin("helsteraMobs");
            var mythic = Bukkit.getPluginManager().getPlugin("MythicMobs");
            var center = new Location(Bukkit.getWorlds().getFirst(), 0, 150, 0);
            origin = center.clone().add(112, 20, 0);
            receiverLocation = center.clone().add(110, 20, 2);
            Location other = origin.clone();
            other.setWorld(Bukkit.getWorlds().stream().filter(w -> w != origin.getWorld()).findFirst().orElseThrow());
            for (Location point : List.of(origin, origin.clone().add(0, 0, 4), origin.clone().add(4, 0, 0), other)) {
                Chunk chunk = point.getChunk(); chunk.getEntities();
                if (tickets.add(chunk)) chunk.addPluginChunkTicket(this);
            }
            h = new VariablePlaceholderAudit.Harness(this, runtime, mythic, center, false);
            h.item.remove();
            nonliving = origin.getWorld().spawn(origin.clone().add(0, 0, -2), Interaction.class);
            receiver.setGameMode(GameMode.CREATIVE); observer.setGameMode(GameMode.CREATIVE);
            receiver.setGravity(false); observer.setGravity(false);
            receiver.teleport(receiverLocation); observer.teleport(receiverLocation.clone().add(2, 0, 0));
            cases = YamlConfiguration.loadConfiguration(getDataFolder().toPath().resolve("cases.yml").toFile()).getMapList("cases");
            var ids = new YamlConfiguration();
            for (var entry : Map.of("caster", h.a, "target", h.b, "nonliving", nonliving,
                    "receiver", receiver, "observer", observer).entrySet()) {
                ids.set(entry.getKey() + ".uuid", entry.getValue().getUniqueId().toString());
                ids.set(entry.getKey() + ".id", entry.getValue().getEntityId());
            }
            ids.save(getDataFolder().toPath().resolve("identities.yml").toFile());
            Bukkit.getScheduler().runTaskLater(this, this::phase, 20);
        } catch (Throwable failure) { fail(failure); }
    }

    private void phase() {
        try {
            if (index == cases.size() * 3) {
                getLogger().info("ITEM_COPY_SUMMARY|observations=" + index + "|completed=true");
                tell("ITEM_COPY_DONE"); closeFixture(); return;
            }
            path = List.of("native", "direct", "imported").get(index / cases.size());
            sample = cases.get(index % cases.size()); key = text("key");
            dropped.forEach(Entity::remove); dropped.clear();
            receiver.teleport(receiverLocation); receiver.getInventory().clear(); observer.getInventory().clear();
            caster = text("source").equals("player") ? receiver : text("source").equals("nonliving") ? nonliving : h.a;
            target = text("target").equals("player") || text("target").equals("self") ? receiver
                    : text("target").equals("nonliving") ? nonliving : h.b;
            for (int i = 0; i < SLOTS.size(); i++) {
                ItemStack template = template(i);
                h.a.getEquipment().setItem(SLOTS.get(i), bool("copy") ? new ItemStack(Material.BARRIER) : template);
                h.b.getEquipment().setItem(SLOTS.get(i), bool("copy") ? template : new ItemStack(Material.BARRIER));
                if (caster == receiver) receiver.getEquipment().setItem(SLOTS.get(i), template);
            }
            if (text("variant").equals("air")) h.b.getEquipment().setItem(EquipmentSlot.HEAD, new ItemStack(Material.AIR));
            if (!text("fill").equals("empty")) {
                for (int i = 0; i < 36; i++) receiver.getInventory().setItem(i, new ItemStack(Material.BARRIER, 64));
                if (text("fill").equals("partial")) {
                    ItemStack partial = ((LivingEntity) caster).getEquipment().getItem(EquipmentSlot.HAND).clone();
                    partial.setAmount(62); receiver.getInventory().setItem(0, partial);
                }
            }
            String source = text("mechanic") + "{" + text("arguments") + "} @trigger";
            String mapped = source;
            if (path.equals("imported")) mapped = imported(source);
            row = new YamlConfiguration();
            row.set("index", index); row.set("path", path); row.set("case", key);
            row.set("source", source); row.set("mapped", mapped); row.set("primary", Bukkit.isPrimaryThread());
            row.set("startTick", Bukkit.getCurrentTick()); row.set("origin", point(origin));
            row.set("receiverLocation", point(receiver.getLocation()));
            row.set("caster", caster.getUniqueId().toString()); row.set("target", target.getUniqueId().toString());
            row.set("before", state());
            tell("ITEM_COPY_PHASE " + index + " " + path + " " + key);
            tell("ITEM_COPY_TICK " + index + " 0");
            if (path.equals("native")) row.set("result", nativeCast(source));
            else {
                Object tree = h.newTree(target, target instanceof LivingEntity living ? living : h.b, origin);
                Object context = h.context(caster, target instanceof LivingEntity living ? living : null, target, origin, tree);
                if (!(target instanceof LivingEntity)) context = h.contextType.getMethod("withEntityRecipient", Entity.class).invoke(context, target);
                String binding = (String) h.skills.getClass().getMethod("bindAction", String.class).invoke(h.skills, mapped);
                boolean executed = binding != null && (boolean) h.registry.getClass().getMethod("runAction", String.class, h.contextType)
                        .invoke(h.registry, binding, context);
                row.set("result", executed ? "EXECUTED" : "UNBOUND");
            }
            for (Entity entity : origin.getWorld().getEntities()) if (entity instanceof Item item && !item.isDead()) {
                dropped.add(item); item.setGravity(false); item.setVelocity(new org.bukkit.util.Vector());
                item.setPickupDelay(Integer.MAX_VALUE);
            }
            row.set("after", state());
            if (text("variant").equals("mutate") && caster instanceof LivingEntity living) {
                ItemStack changed = living.getEquipment().getItem(EquipmentSlot.HAND);
                changed.setAmount(1); var meta = changed.getItemMeta(); meta.setDisplayName("Changed source");
                changed.setItemMeta(meta); living.getEquipment().setItem(EquipmentSlot.HAND, changed);
            }
            row.set("afterSourceMutation", state());
            tick(1);
        } catch (Throwable failure) { fail(failure); }
    }

    private void tick(int offset) {
        Bukkit.getScheduler().runTaskLater(this, () -> {
            try {
                tell("ITEM_COPY_TICK " + index + " " + offset);
                if (offset == 2 && text("cleanup").equals("disable") && !path.equals("native")) {
                    h.runtimeType("dev.helstera.ai.skill.SkillExtras").getMethod("host", Plugin.class).invoke(null, new Object[]{null});
                    row.set("cleanupTick", Bukkit.getCurrentTick());
                }
                if (offset == 2 && text("cleanup").equals("world")) {
                    receiver.teleport(h.d.getLocation()); row.set("cleanupTick", Bukkit.getCurrentTick());
                }
                if (offset < 10) { tick(offset + 1); return; }
                row.set("settled", state()); row.set("endTick", Bukkit.getCurrentTick());
                row.set("entityIds", origin.getWorld().getEntities().stream().map(Entity::getEntityId).sorted().toList());
                var output = getDataFolder().toPath().resolve("observations/%04d.yml".formatted(index));
                Files.createDirectories(output.getParent()); row.save(output.toFile());
                getLogger().info("ITEM_COPY_ROW|index=" + index + "|path=" + path + "|case=" + key + "|result=" + row.getString("result"));
                tell("ITEM_COPY_END " + index);
                if (text("cleanup").equals("disable") && !path.equals("native"))
                    h.runtimeType("dev.helstera.ai.skill.SkillExtras").getMethod("host", Plugin.class).invoke(null, runtime);
                index++;
                Bukkit.getScheduler().runTaskLater(this, this::phase, 3);
            } catch (Throwable failure) { fail(failure); }
        }, 1);
    }

    private String nativeCast(String source) throws Exception {
        String mechanic = bool("copy") ? "EquipCopyMechanic" : "GiveItemFromSlotMechanic";
        String raw = source.substring(0, source.lastIndexOf(" @"));
        Object action = h.nativeType("io.lumine.mythic.core.skills.mechanics." + mechanic)
                .getConstructor(h.executorType, java.io.File.class, String.class, h.configType)
                .newInstance(h.nativeSkills, h.source.toFile(), raw, h.configImpl.getConstructor(String.class).newInstance(raw));
        Object nativeCaster = h.nativeType("io.lumine.mythic.api.mobs.GenericCaster")
                .getConstructor(h.abstractEntity).newInstance(h.adapt.invoke(null, caster));
        Object meta = h.metadataType.getConstructor(h.triggerType, h.skillCaster, h.abstractEntity).newInstance(
                h.triggerType.getMethod("get", String.class).invoke(null, "API"), nativeCaster, h.adapt.invoke(null, target));
        Class<?> locationType = h.nativeType("io.lumine.mythic.api.adapters.AbstractLocation");
        Object nativeOrigin = h.nativeType("io.lumine.mythic.bukkit.BukkitAdapter").getMethod("adapt", Location.class).invoke(null, origin);
        meta.getClass().getMethod("setOrigin", locationType).invoke(meta, nativeOrigin);
        try {
            return action.getClass().getMethod("castAtEntity", h.skillMetadata, h.abstractEntity)
                    .invoke(action, meta, h.adapt.invoke(null, target)).toString();
        } catch (InvocationTargetException failure) { return failure.getCause().getClass().getSimpleName(); }
    }

    private String imported(String source) throws Exception {
        var input = new YamlConfiguration(); input.set("copy.Skills", List.of(source)); input.save(h.source.toFile());
        @SuppressWarnings("unchecked") var plans = (List<Map<String,Object>>) h.importer.getClass().getMethod("convert", java.nio.file.Path.class).invoke(h.importer, h.source);
        var plan = plans.stream().filter(entry -> "skills.yml".equals(entry.get("target-file"))).findFirst().orElseThrow();
        input.loadFromString(plan.get("content").toString());
        var actions = input.getStringList("skills.copy.on-decision");
        if (actions.size() != 1 || input.contains("skills.copy.unsupported")) throw new IllegalStateException("import failed: " + source);
        return actions.getFirst();
    }

    private ItemStack template(int slot) {
        boolean damageable = text("variant").equals("damageable") && SLOTS.get(slot) == EquipmentSlot.HAND;
        ItemStack item = new ItemStack(damageable ? Material.DIAMOND_SWORD : MATERIALS.get(slot), damageable ? 1 : 7);
        var meta = item.getItemMeta();
        meta.setDisplayName("Copy-" + SLOTS.get(slot).name()); meta.setLore(List.of("Line one", "Line two"));
        meta.setCustomModelData(100 + slot); meta.setEnchantmentGlintOverride(true);
        meta.getPersistentDataContainer().set(new NamespacedKey(this, "proof"), PersistentDataType.STRING, "private-meta-" + slot);
        if (damageable && meta instanceof org.bukkit.inventory.meta.Damageable durability) durability.setDamage(17);
        item.setItemMeta(meta); return item;
    }

    private Map<String,Object> state() throws Exception {
        var state = new LinkedHashMap<String,Object>();
        state.put("caster", equipment(caster)); state.put("target", equipment(target));
        state.put("inventory", inventory(receiver)); state.put("observerInventory", inventory(observer));
        var drops = new ArrayList<Map<String,Object>>();
        for (Item item : dropped) if (item.isValid()) {
            var entry = new LinkedHashMap<String,Object>(); entry.put("item", stack(item.getItemStack()));
            entry.put("owner", item.getOwner() == null ? "none" : item.getOwner().toString());
            entry.put("thrower", item.getThrower() == null ? "none" : item.getThrower().toString());
            entry.put("canMobPickup", item.canMobPickup()); entry.put("location", point(item.getLocation()));
            entry.put("entityId", item.getEntityId()); drops.add(entry);
        }
        state.put("drops", drops); return state;
    }

    private Map<String,Object> equipment(Entity entity) throws Exception {
        var result = new LinkedHashMap<String,Object>();
        if (entity instanceof LivingEntity living && living.getEquipment() != null)
            for (EquipmentSlot slot : SLOTS) result.put(slot.name(), stack(living.getEquipment().getItem(slot)));
        return result;
    }

    private List<Map<String,Object>> inventory(Player player) throws Exception {
        var result = new ArrayList<Map<String,Object>>();
        var items = player.getInventory().getStorageContents();
        for (int i = 0; i < items.length; i++) if (items[i] != null && !items[i].getType().isAir())
            result.add(Map.of("slot", i, "item", stack(items[i])));
        return result;
    }

    private Map<String,Object> stack(ItemStack item) throws Exception {
        if (item == null || item.getType().isAir()) return Map.of("type", "AIR", "amount", 0);
        ItemStack unit = item.clone(); unit.setAmount(1);
        byte[] bytes = unit.serializeAsBytes();
        var result = new LinkedHashMap<String,Object>();
        result.put("type", item.getType().name()); result.put("amount", item.getAmount());
        result.put("fingerprint", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        result.put("data", Base64.getEncoder().encodeToString(bytes));
        var meta = item.getItemMeta(); result.put("name", meta.hasDisplayName() ? meta.getDisplayName() : "");
        result.put("lore", meta.hasLore() ? meta.getLore() : List.of());
        result.put("model", meta.hasCustomModelData() ? meta.getCustomModelData() : 0);
        result.put("proof", meta.getPersistentDataContainer().getOrDefault(new NamespacedKey(this, "proof"), PersistentDataType.STRING, ""));
        result.put("damage", meta instanceof org.bukkit.inventory.meta.Damageable damage ? damage.getDamage() : 0);
        return result;
    }

    private Map<String,Object> point(Location location) {
        return Map.of("world", location.getWorld().getName(), "x", location.getX(), "y", location.getY(), "z", location.getZ());
    }
    private boolean bool(String name) { return Boolean.TRUE.equals(sample.get(name)); }
    private String text(String name) { return sample.get(name).toString(); }
    private void tell(String text) { receiver.sendMessage(text); observer.sendMessage(text); }
    private void fail(Throwable failure) {
        getLogger().severe("ITEM_COPY_FAILED|" + failure); failure.printStackTrace(); closeFixture();
    }
    private void closeFixture() {
        dropped.forEach(Entity::remove); dropped.clear();
        if (nonliving != null) nonliving.remove();
        if (h != null) {
            try { h.close(); } catch (Exception failure) { getLogger().warning(failure.toString()); }
            h = null;
        }
        for (Chunk chunk : tickets) chunk.removePluginChunkTicket(this);
        tickets.clear();
    }
    @Override public void onDisable() { closeFixture(); }
}
