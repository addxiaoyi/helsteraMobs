package dev.helstera.ai.skill;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Paper 1.21.1 pickup packets, sent only to the receiving player. No NMS entity is
 * registered in a level. Lifecycle methods and packet sends run on the server thread.
 */
public final class PaperFakeItemLoot implements FakeItemLoot, Listener, AutoCloseable {
    private static final long PICKUP_DELAY = 5L;

    interface PacketTransport {
        int spawn(Player recipient, ItemStack item, Location origin) throws ReflectiveOperationException;
        void collect(Player recipient, int itemId) throws ReflectiveOperationException;
        void remove(Player recipient, int itemId) throws ReflectiveOperationException;
    }

    private static final class Pending {
        final Player recipient;
        final UUID world;
        BukkitTask task;

        Pending(Player recipient) {
            this.recipient = recipient;
            this.world = recipient.getWorld().getUID();
        }
    }

    private final Plugin plugin;
    private final PacketTransport packets;
    private final Map<Integer, Pending> pending = new HashMap<>();
    private boolean closed;
    private boolean warned;

    public PaperFakeItemLoot(Plugin plugin) {
        this(plugin, new NativePackets());
    }

    PaperFakeItemLoot(Plugin plugin, PacketTransport packets) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.packets = Objects.requireNonNull(packets, "packets");
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    public static boolean supportsVersion(String serverName, String minecraftVersion) {
        return "Paper".equals(serverName) && "1.21.1".equals(minecraftVersion);
    }

    @Override
    public void show(Player recipient, ItemStack item, Location origin) {
        if (closed || !plugin.isEnabled() || recipient == null || !recipient.isOnline()
                || item == null || origin == null || origin.getWorld() == null) return;
        if (!plugin.getServer().isPrimaryThread()) {
            warn(new IllegalStateException("Fake item pickup requires the server thread"));
            return;
        }
        Pending entry = new Pending(recipient);
        int id;
        try {
            // The inventory API may modify the stack passed to addItem; preserve the animation snapshot.
            id = packets.spawn(recipient, item.clone(), origin.clone());
        } catch (ReflectiveOperationException | LinkageError | RuntimeException failure) {
            warn(failure);
            return;
        }
        pending.put(id, entry);
        try {
            entry.task = plugin.getServer().getScheduler().runTaskLater(plugin, () -> collect(id), PICKUP_DELAY);
        } catch (RuntimeException failure) {
            pending.remove(id);
            remove(id, entry);
            warn(failure);
        }
    }

    private void collect(int id) {
        Pending entry = pending.remove(id);
        if (entry == null) return;
        if (!closed && plugin.isEnabled() && visible(entry)) {
            try {
                packets.collect(entry.recipient, id);
            } catch (ReflectiveOperationException | LinkageError | RuntimeException failure) {
                warn(failure);
            } finally {
                // Native sends a count of one even for larger stacks. Explicit removal avoids
                // leaving the rest of a cosmetic stack behind on the recipient's client.
                remove(id, entry);
            }
        } else remove(id, entry);
    }

    private static boolean visible(Pending entry) {
        return entry.recipient.isOnline() && entry.world.equals(entry.recipient.getWorld().getUID());
    }

    private void remove(int id, Pending entry) {
        if (!visible(entry)) return;
        try {
            packets.remove(entry.recipient, id);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException failure) {
            warn(failure);
        }
    }

    private void forget(Player player) {
        var entries = pending.entrySet().iterator();
        while (entries.hasNext()) {
            var entry = entries.next();
            if (!entry.getValue().recipient.getUniqueId().equals(player.getUniqueId())) continue;
            if (entry.getValue().task != null) entry.getValue().task.cancel();
            entries.remove();
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        forget(event.getPlayer());
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        // The dimension transition already removed old-world entities from that client.
        forget(event.getPlayer());
    }

    @EventHandler
    public void onDisable(PluginDisableEvent event) {
        if (event.getPlugin() == plugin) close();
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        var outstanding = new HashMap<>(pending);
        pending.clear();
        for (var entry : outstanding.entrySet()) {
            if (entry.getValue().task != null) entry.getValue().task.cancel();
            remove(entry.getKey(), entry.getValue());
        }
        HandlerList.unregisterAll(this);
    }

    private void warn(Throwable failure) {
        if (warned) return;
        warned = true;
        plugin.getLogger().warning("[物品拾取动画] " + failure.getClass().getSimpleName() + ": "
                + failure.getMessage() + "; inventory delivery is unaffected.");
    }

    /** The adapter deliberately names Mojang methods, and only claims the inspected Paper release. */
    private static final class NativePackets implements PacketTransport {
        private Binding binding;

        private Binding binding(Player recipient) throws ReflectiveOperationException {
            if (!supportsVersion(recipient.getServer().getName(), recipient.getServer().getMinecraftVersion()))
                throw new UnsupportedOperationException("Private item pickup requires the Paper 1.21.1 adapter");
            if (binding == null) binding = new Binding(recipient.getWorld().getClass().getClassLoader());
            return binding;
        }

        @Override
        public int spawn(Player recipient, ItemStack item, Location origin) throws ReflectiveOperationException {
            Binding api = binding(recipient);
            Object level = origin.getWorld().getClass().getMethod("getHandle").invoke(origin.getWorld());
            Object stack = api.itemCopy.invoke(null, item);
            Object entity = api.itemEntity.newInstance(level, origin.getX(), origin.getY(), origin.getZ(), stack);
            // Constructing an ItemEntity reserves a globally unique entity id but does not add it to the world.
            int id = (Integer) api.entityId.invoke(entity);
            Object uuid = api.entityUuid.invoke(entity);
            Object spawn = api.addEntity.newInstance(id, uuid, origin.getX(), origin.getY(), origin.getZ(),
                    0F, 0F, api.itemType, 0, api.zeroVelocity, 0D);
            Object data = api.entityData.invoke(entity);
            Object values = api.nonDefaultData.invoke(data);
            Object metadata = api.setData.newInstance(id, values == null ? List.of() : values);
            boolean sent = false;
            try {
                api.send(recipient, spawn);
                sent = true;
                api.send(recipient, metadata);
                return id;
            } catch (ReflectiveOperationException | RuntimeException failure) {
                if (sent) {
                    try { api.send(recipient, api.removeEntities.newInstance((Object) new int[]{id})); }
                    catch (ReflectiveOperationException | RuntimeException cleanup) { failure.addSuppressed(cleanup); }
                }
                throw failure;
            }
        }

        @Override
        public void collect(Player recipient, int itemId) throws ReflectiveOperationException {
            Binding api = binding(recipient);
            api.send(recipient, api.takeItem.newInstance(itemId, recipient.getEntityId(), 1));
        }

        @Override
        public void remove(Player recipient, int itemId) throws ReflectiveOperationException {
            Binding api = binding(recipient);
            api.send(recipient, api.removeEntities.newInstance((Object) new int[]{itemId}));
        }
    }

    private static final class Binding {
        final Constructor<?> itemEntity;
        final Constructor<?> addEntity;
        final Constructor<?> setData;
        final Constructor<?> takeItem;
        final Constructor<?> removeEntities;
        final Method itemCopy;
        final Method entityId;
        final Method entityUuid;
        final Method entityData;
        final Method nonDefaultData;
        final Method sendPacket;
        final Field connection;
        final Object itemType;
        final Object zeroVelocity;

        Binding(ClassLoader loader) throws ReflectiveOperationException {
            Class<?> entity = type(loader, "net.minecraft.world.entity.Entity");
            Class<?> level = type(loader, "net.minecraft.world.level.Level");
            Class<?> stack = type(loader, "net.minecraft.world.item.ItemStack");
            Class<?> entityType = type(loader, "net.minecraft.world.entity.EntityType");
            Class<?> vector = type(loader, "net.minecraft.world.phys.Vec3");
            Class<?> packet = type(loader, "net.minecraft.network.protocol.Packet");
            Class<?> serverPlayer = type(loader, "net.minecraft.server.level.ServerPlayer");
            itemEntity = type(loader, "net.minecraft.world.entity.item.ItemEntity")
                    .getConstructor(level, double.class, double.class, double.class, stack);
            addEntity = type(loader, "net.minecraft.network.protocol.game.ClientboundAddEntityPacket")
                    .getConstructor(int.class, UUID.class, double.class, double.class, double.class,
                            float.class, float.class, entityType, int.class, vector, double.class);
            setData = type(loader, "net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket")
                    .getConstructor(int.class, List.class);
            takeItem = type(loader, "net.minecraft.network.protocol.game.ClientboundTakeItemEntityPacket")
                    .getConstructor(int.class, int.class, int.class);
            removeEntities = type(loader, "net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket")
                    .getConstructor(int[].class);
            itemCopy = type(loader, "org.bukkit.craftbukkit.inventory.CraftItemStack")
                    .getMethod("asNMSCopy", ItemStack.class);
            entityId = entity.getMethod("getId");
            entityUuid = entity.getMethod("getUUID");
            entityData = entity.getMethod("getEntityData");
            nonDefaultData = type(loader, "net.minecraft.network.syncher.SynchedEntityData")
                    .getMethod("getNonDefaultValues");
            connection = serverPlayer.getField("connection");
            sendPacket = connection.getType().getMethod("send", packet);
            itemType = entityType.getField("ITEM").get(null);
            zeroVelocity = vector.getField("ZERO").get(null);
        }

        void send(Player recipient, Object packet) throws ReflectiveOperationException {
            Object player = recipient.getClass().getMethod("getHandle").invoke(recipient);
            sendPacket.invoke(connection.get(player), packet);
        }

        private static Class<?> type(ClassLoader loader, String name) throws ClassNotFoundException {
            return Class.forName(name, false, loader);
        }
    }
}
