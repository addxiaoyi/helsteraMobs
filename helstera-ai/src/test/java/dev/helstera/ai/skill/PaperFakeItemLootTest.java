package dev.helstera.ai.skill;

import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PaperFakeItemLootTest {
    private final Plugin plugin = mock(Plugin.class);
    private final Server server = mock(Server.class);
    private final PluginManager plugins = mock(PluginManager.class);
    private final BukkitScheduler scheduler = mock(BukkitScheduler.class);
    private final BukkitTask task = mock(BukkitTask.class);
    private final World world = mock(World.class);
    private final Player player = mock(Player.class);
    private final ItemStack source = mock(ItemStack.class);
    private final ItemStack snapshot = mock(ItemStack.class);
    private final PaperFakeItemLoot.PacketTransport packets = mock(PaperFakeItemLoot.PacketTransport.class);
    private final Location origin = new Location(world, 9, 75, -3);

    @BeforeEach void setup() throws ReflectiveOperationException {
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        when(plugin.isEnabled()).thenReturn(true);
        when(server.getPluginManager()).thenReturn(plugins);
        when(server.getScheduler()).thenReturn(scheduler);
        when(server.isPrimaryThread()).thenReturn(true);
        when(scheduler.runTaskLater(eq(plugin), any(Runnable.class), eq(5L))).thenReturn(task);
        when(world.getUID()).thenReturn(UUID.randomUUID());
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getWorld()).thenReturn(world);
        when(player.isOnline()).thenReturn(true);
        when(source.clone()).thenReturn(snapshot);
        when(packets.spawn(eq(player), eq(snapshot), any(Location.class))).thenReturn(42);
    }

    private Runnable callback() {
        ArgumentCaptor<Runnable> run = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).runTaskLater(eq(plugin), run.capture(), eq(5L));
        return run.getValue();
    }

    @Test void nativeVersionClaimIsLimitedToTheInspectedPaperRelease() {
        assertTrue(PaperFakeItemLoot.supportsVersion("Paper", "1.21.1"));
        assertFalse(PaperFakeItemLoot.supportsVersion("Paper", "1.21.11"));
        assertFalse(PaperFakeItemLoot.supportsVersion("Spigot", "1.21.1"));
        assertFalse(PaperFakeItemLoot.supportsVersion(null, null));
    }

    @Test void missingInputsAndOfflinePlayersDoNotSendOrSchedulePackets() {
        try (var animation = new PaperFakeItemLoot(plugin, packets)) {
            animation.show(null, source, origin);
            animation.show(player, null, origin);
            animation.show(player, source, null);
            animation.show(player, source, new Location(null, 0, 0, 0));
            when(player.isOnline()).thenReturn(false);
            animation.show(player, source, origin);
            verifyNoInteractions(packets, scheduler);
        }
    }

    @Test void pickupUsesPrivateSnapshotsAndFiveTickDelayThenRemovesCosmeticRemainder() throws Exception {
        try (var animation = new PaperFakeItemLoot(plugin, packets)) {
            animation.show(player, source, origin);
            ArgumentCaptor<Location> location = ArgumentCaptor.forClass(Location.class);
            verify(packets).spawn(eq(player), same(snapshot), location.capture());
            assertNotSame(origin, location.getValue());
            assertEquals(origin, location.getValue());
            verify(packets, never()).collect(any(), anyInt());
            verify(packets, never()).remove(any(), anyInt());
            Runnable callback = callback();
            callback.run();
            var order = inOrder(packets);
            order.verify(packets).spawn(eq(player), same(snapshot), any(Location.class));
            order.verify(packets).collect(player, 42);
            order.verify(packets).remove(player, 42);
            callback.run();
            verify(packets, times(1)).collect(player, 42);
            verify(packets, times(1)).remove(player, 42);
        }
        verify(player, never()).getInventory();
    }

    @Test void shutdownCancelsAndRemovesOnlyOutstandingItemsAndIsIdempotent() throws Exception {
        var animation = new PaperFakeItemLoot(plugin, packets);
        animation.show(player, source, origin);
        Runnable callback = callback();
        animation.close();
        animation.close();
        callback.run();
        animation.show(player, source, origin);
        verify(task).cancel();
        verify(packets).remove(player, 42);
        verify(packets, never()).collect(any(), anyInt());
        verify(packets, times(1)).spawn(any(), any(), any());
    }

    @Test void pluginDisableEventCleansUpEvenBeforeExplicitHookReset() throws Exception {
        var animation = new PaperFakeItemLoot(plugin, packets);
        animation.show(player, source, origin);
        var unrelated = mock(PluginDisableEvent.class);
        when(unrelated.getPlugin()).thenReturn(mock(Plugin.class));
        animation.onDisable(unrelated);
        verify(task, never()).cancel();
        var disabling = mock(PluginDisableEvent.class);
        when(disabling.getPlugin()).thenReturn(plugin);
        animation.onDisable(disabling);
        verify(task).cancel();
        verify(packets).remove(player, 42);
        callback().run();
        verify(packets, never()).collect(any(), anyInt());
    }

    @Test void disconnectForgetsPacketsAndCannotCollectAfterReconnect() throws Exception {
        try (var animation = new PaperFakeItemLoot(plugin, packets)) {
            animation.show(player, source, origin);
            Runnable callback = callback();
            animation.onQuit(new PlayerQuitEvent(player, (net.kyori.adventure.text.Component) null));
            verify(task).cancel();
            callback.run();
            verify(packets, never()).collect(any(), anyInt());
            verify(packets, never()).remove(any(), anyInt());
        }
    }

    @Test void changingDimensionCancelsOldWorldPickupWithoutSendingIntoNewWorld() throws Exception {
        try (var animation = new PaperFakeItemLoot(plugin, packets)) {
            animation.show(player, source, origin);
            Runnable callback = callback();
            animation.onWorldChange(new PlayerChangedWorldEvent(player, world));
            callback.run();
            verify(task).cancel();
            verify(packets, never()).collect(any(), anyInt());
            verify(packets, never()).remove(any(), anyInt());
        }
    }

    @Test void offlineAndChangedWorldAreAlsoGuardedAtCallbackTime() throws Exception {
        try (var animation = new PaperFakeItemLoot(plugin, packets)) {
            animation.show(player, source, origin);
            when(player.isOnline()).thenReturn(false);
            callback().run();
            verify(packets, never()).collect(any(), anyInt());
            verify(packets, never()).remove(any(), anyInt());
        }
        reset(scheduler);
        when(scheduler.runTaskLater(eq(plugin), any(Runnable.class), eq(5L))).thenReturn(task);
        when(player.isOnline()).thenReturn(true);
        try (var animation = new PaperFakeItemLoot(plugin, packets)) {
            animation.show(player, source, origin);
            World nextWorld = mock(World.class);
            when(nextWorld.getUID()).thenReturn(UUID.randomUUID());
            when(player.getWorld()).thenReturn(nextWorld);
            callback().run();
            verify(packets, never()).collect(any(), anyInt());
            verify(packets, never()).remove(any(), anyInt());
        }
    }

    @Test void failureToScheduleRemovesSpawnedItemWithoutCreatingInventoryOrWorldEntities() throws Exception {
        when(scheduler.runTaskLater(eq(plugin), any(Runnable.class), eq(5L)))
                .thenThrow(new IllegalStateException("plugin disabled during scheduling"));
        try (var animation = new PaperFakeItemLoot(plugin, packets)) {
            animation.show(player, source, origin);
            verify(packets).remove(player, 42);
            verify(packets, never()).collect(any(), anyInt());
            verify(player, never()).getInventory();
        }
    }

    @Test void packetFailureStillRemovesItemAndDoesNotRetryInventoryDelivery() throws Exception {
        doThrow(new ReflectiveOperationException("send failed")).when(packets).collect(player, 42);
        try (var animation = new PaperFakeItemLoot(plugin, packets)) {
            animation.show(player, source, origin);
            callback().run();
            verify(packets).remove(player, 42);
            verify(player, never()).getInventory();
        }
    }

    @Test void spawnFailureSchedulesNothingAndAvailabilityChecksNeverTouchInventory() throws Exception {
        when(packets.spawn(any(), any(), any())).thenThrow(new ReflectiveOperationException("adapter unavailable"));
        try (var animation = new PaperFakeItemLoot(plugin, packets)) {
            animation.show(player, source, origin);
            verifyNoInteractions(scheduler);
            verify(player, never()).getInventory();
            when(server.isPrimaryThread()).thenReturn(false);
            animation.show(player, source, origin);
            verify(packets, times(1)).spawn(any(), any(), any());
            when(server.isPrimaryThread()).thenReturn(true);
            when(plugin.isEnabled()).thenReturn(false);
            animation.show(player, source, origin);
            verify(packets, times(1)).spawn(any(), any(), any());
        }
    }

    @Test void playerDisconnectDoesNotCancelAnotherPlayersPendingPickup() throws Exception {
        Player observer = mock(Player.class);
        when(observer.getUniqueId()).thenReturn(UUID.randomUUID());
        try (var animation = new PaperFakeItemLoot(plugin, packets)) {
            animation.show(player, source, origin);
            animation.onQuit(new PlayerQuitEvent(observer, (net.kyori.adventure.text.Component) null));
            verify(task, never()).cancel();
            callback().run();
            verify(packets).collect(player, 42);
            verify(packets, never()).collect(eq(observer), anyInt());
            verify(packets, never()).spawn(eq(observer), any(), any());
        }
    }
}
