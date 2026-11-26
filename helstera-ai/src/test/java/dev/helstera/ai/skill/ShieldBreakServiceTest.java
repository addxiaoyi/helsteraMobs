package dev.helstera.ai.skill;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.IntSupplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ShieldBreakServiceTest {
    private final Plugin plugin = mock(Plugin.class);
    private final Server server = mock(Server.class);
    private final BukkitScheduler scheduler = mock(BukkitScheduler.class);
    private final Player player = mock(Player.class);
    private final PlayerInventory inventory = mock(PlayerInventory.class);
    private final World world = mock(World.class);
    private final Map<EquipmentSlot, ItemStack> hands = new HashMap<>();
    private final List<Runnable> callbacks = new ArrayList<>();
    private final List<BukkitTask> tasks = new ArrayList<>();
    private final List<ItemStack> reinserted = new ArrayList<>();
    private final ItemStack shield = item(Material.SHIELD);
    private final Location at = new Location(world, 2, 70, 4);

    private static ItemStack item(Material material) {
        var item = mock(ItemStack.class);
        when(item.getType()).thenReturn(material);
        when(item.clone()).thenReturn(item);
        return item;
    }

    @BeforeEach void setup() {
        when(plugin.getServer()).thenReturn(server);
        when(plugin.isEnabled()).thenReturn(true);
        when(server.getPluginManager()).thenReturn(mock(PluginManager.class));
        when(server.getScheduler()).thenReturn(scheduler);
        when(server.isPrimaryThread()).thenReturn(true);
        when(player.isOnline()).thenReturn(true);
        when(player.isBlocking()).thenReturn(true);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getInventory()).thenReturn(inventory);
        when(player.getWorld()).thenReturn(world);
        when(player.getLocation()).thenReturn(at);
        when(inventory.getItemInMainHand()).thenAnswer(call -> hands.get(EquipmentSlot.HAND));
        when(inventory.getItemInOffHand()).thenAnswer(call -> hands.get(EquipmentSlot.OFF_HAND));
        when(inventory.getItem(any(EquipmentSlot.class))).thenAnswer(call -> hands.get(call.getArgument(0)));
        doAnswer(call -> { hands.put(call.getArgument(0), call.getArgument(1)); return null; })
                .when(inventory).setItem(any(EquipmentSlot.class), nullable(ItemStack.class));
        when(inventory.addItem(any(ItemStack.class))).thenAnswer(call -> {
            reinserted.add(call.getArgument(0)); return new HashMap<Integer, ItemStack>();
        });
        when(scheduler.runTaskLater(eq(plugin), any(Runnable.class), eq(1L))).thenAnswer(call -> {
            callbacks.add(call.getArgument(1)); var task = mock(BukkitTask.class); tasks.add(task); return task;
        });
        hands.put(EquipmentSlot.HAND, shield);
    }

    @Test void blockingMainHandSequenceRemovesThenRestoresExactlyOnce() {
        try (var service = new ShieldBreakService(plugin)) {
            assertEquals(ShieldBreakService.Result.SUCCESS, service.disable(player, () -> 100));
            assertNull(hands.get(EquipmentSlot.HAND));
            assertEquals(1, service.pendingCount());
            var order = inOrder(player, inventory, scheduler);
            order.verify(player).setCooldown(Material.SHIELD, 100);
            order.verify(player).playSound(at, "minecraft:item.shield.block", 1, 1);
            order.verify(player).playSound(at, "minecraft:item.shield.break", 1, 1);
            order.verify(inventory).setItem(EquipmentSlot.HAND, null);
            order.verify(scheduler).runTaskLater(eq(plugin), any(Runnable.class), eq(1L));
            callbacks.getFirst().run(); callbacks.getFirst().run();
            assertSame(shield, hands.get(EquipmentSlot.HAND));
            assertEquals(0, service.pendingCount());
            verify(inventory, times(1)).setItem(EquipmentSlot.HAND, shield);
            verify(player, never()).clearActiveItem();
            verify(player, never()).completeUsingActiveItem();
        }
    }

    @Test void mainHandWinsWithTwoShieldsAndOffHandIsUsedOtherwise() {
        var offhand = item(Material.SHIELD);
        hands.put(EquipmentSlot.OFF_HAND, offhand);
        try (var service = new ShieldBreakService(plugin)) {
            service.disable(player, () -> 20);
            assertSame(offhand, hands.get(EquipmentSlot.OFF_HAND));
            callbacks.getFirst().run();
            hands.put(EquipmentSlot.HAND, item(Material.STONE));
            service.disable(player, () -> 30);
            assertNull(hands.get(EquipmentSlot.OFF_HAND));
            callbacks.getLast().run();
            assertSame(offhand, hands.get(EquipmentSlot.OFF_HAND));
        }
    }

    @Test void eligibilityShortCircuitsDurationAndNegativeDurationCannotRemoveShield() {
        IntSupplier duration = mock(IntSupplier.class);
        try (var service = new ShieldBreakService(plugin)) {
            when(player.isBlocking()).thenReturn(false);
            assertEquals(ShieldBreakService.Result.CONDITION_FAILED, service.disable(player, duration));
            when(player.isBlocking()).thenReturn(true); hands.clear();
            assertEquals(ShieldBreakService.Result.CONDITION_FAILED, service.disable(player, duration));
            verifyNoInteractions(duration, scheduler);
            hands.put(EquipmentSlot.HAND, shield);
            assertThrows(IllegalArgumentException.class, () -> service.disable(player, () -> -1));
            assertSame(shield, hands.get(EquipmentSlot.HAND));
            verify(player, never()).setCooldown(any(Material.class), anyInt());
        }
    }

    @Test void occupiedSlotRestoresShieldBeforeReinsertingReplacementAndRetainsLeftovers() {
        ItemStack replacement = item(Material.STONE), leftover = item(Material.STONE);
        try (var service = new ShieldBreakService(plugin)) {
            service.disable(player, () -> 20);
            hands.put(EquipmentSlot.HAND, replacement);
            when(inventory.addItem(replacement)).thenAnswer(call -> {
                assertSame(shield, hands.get(EquipmentSlot.HAND));
                return new HashMap<>(Map.of(0, leftover));
            });
            callbacks.getFirst().run();
            assertSame(shield, hands.get(EquipmentSlot.HAND));
            verify(world).dropItem(at, leftover);
        }
    }

    @Test void fullInventoryDropsDisplacedItemAtCurrentLocationWithoutChangingOwnership() {
        ItemStack replacement = item(Material.EMERALD);
        try (var service = new ShieldBreakService(plugin)) {
            service.disable(player, () -> 0);
            hands.put(EquipmentSlot.HAND, replacement);
            when(inventory.firstEmpty()).thenReturn(-1);
            callbacks.getFirst().run();
            verify(world).dropItem(at, replacement);
            verify(inventory, never()).addItem(any(ItemStack.class));
            assertSame(shield, hands.get(EquipmentSlot.HAND));
        }
    }

    @Test void schedulingFailureRestoresImmediatelyAndLeavesNoPendingTask() {
        when(scheduler.runTaskLater(eq(plugin), any(Runnable.class), eq(1L)))
                .thenThrow(new IllegalStateException("disabled"));
        try (var service = new ShieldBreakService(plugin)) {
            assertThrows(IllegalStateException.class, () -> service.disable(player, () -> 20));
            assertSame(shield, hands.get(EquipmentSlot.HAND));
            assertEquals(0, service.pendingCount());
        }
    }

    @Test void shutdownAndQuitRestoreBeforeSavingAndStaleCallbacksCannotDuplicateItems() {
        var service = new ShieldBreakService(plugin);
        service.disable(player, () -> 20);
        service.onQuit(new PlayerQuitEvent(player, (net.kyori.adventure.text.Component) null));
        assertSame(shield, hands.get(EquipmentSlot.HAND));
        callbacks.getFirst().run();
        verify(inventory, times(1)).setItem(EquipmentSlot.HAND, shield);
        service.disable(player, () -> 20);
        service.close(); service.close(); callbacks.getLast().run();
        assertSame(shield, hands.get(EquipmentSlot.HAND));
        assertEquals(0, service.pendingCount());
        assertEquals(ShieldBreakService.Result.UNAVAILABLE, service.disable(player, () -> 20));
        verify(inventory, times(2)).setItem(EquipmentSlot.HAND, shield);
    }

    @Test void deathMovesDetachedShieldIntoDeathDropsOrKeepsItWithTheInventory() {
        try (var service = new ShieldBreakService(plugin)) {
            var death = mock(PlayerDeathEvent.class);
            var drops = new ArrayList<ItemStack>();
            when(death.getEntity()).thenReturn(player); when(death.getDrops()).thenReturn(drops);
            service.disable(player, () -> 20);
            service.onDeath(death); callbacks.getFirst().run();
            assertEquals(List.of(shield), drops);
            assertNull(hands.get(EquipmentSlot.HAND));
            hands.put(EquipmentSlot.HAND, shield);
            when(death.getKeepInventory()).thenReturn(true);
            service.disable(player, () -> 20);
            service.onDeath(death); callbacks.getLast().run();
            assertSame(shield, hands.get(EquipmentSlot.HAND));
            assertEquals(1, drops.size());
        }
    }

    @Test void repeatedInvocationsCanOwnBothHandsWithoutLosingEitherShield() {
        ItemStack offhand = item(Material.SHIELD); hands.put(EquipmentSlot.OFF_HAND, offhand);
        try (var service = new ShieldBreakService(plugin)) {
            service.disable(player, () -> 20); service.disable(player, () -> 20);
            assertEquals(2, service.pendingCount());
            callbacks.forEach(Runnable::run);
            assertSame(shield, hands.get(EquipmentSlot.HAND));
            assertSame(offhand, hands.get(EquipmentSlot.OFF_HAND));
            assertEquals(0, service.pendingCount());
        }
    }

    @Test void failedShieldWriteRetainsOwnershipUntilCleanupCanRestoreIt() {
        var service = new ShieldBreakService(plugin);
        service.disable(player, () -> 20);
        doThrow(new IllegalStateException("inventory write failed"))
                .doAnswer(call -> { hands.put(EquipmentSlot.HAND, shield); return null; })
                .when(inventory).setItem(EquipmentSlot.HAND, shield);
        assertThrows(IllegalStateException.class, () -> callbacks.getFirst().run());
        assertEquals(1, service.pendingCount());
        assertNull(hands.get(EquipmentSlot.HAND));
        service.close();
        assertSame(shield, hands.get(EquipmentSlot.HAND));
        assertEquals(0, service.pendingCount());
        callbacks.getFirst().run();
        verify(inventory, times(2)).setItem(EquipmentSlot.HAND, shield);
    }
}
