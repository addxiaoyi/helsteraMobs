package dev.helstera.ai.skill;

import dev.helstera.api.behavior.BehaviorContext;
import dev.helstera.api.instance.ModelInstance;
import dev.helstera.api.util.MythicItemCopySpec;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ItemCopyActionsTest {
    private final World world = mock(World.class);
    private final FakeItemLoot animation = mock(FakeItemLoot.class);
    private final Location origin = new Location(world, 10, 100, 20);

    private <T extends Entity> T entity(Class<T> type) {
        T entity = mock(type);
        when(entity.getUniqueId()).thenReturn(UUID.randomUUID());
        when(entity.getLocation()).thenReturn(new Location(world, 30, 100, 40));
        when(entity.getWorld()).thenReturn(world);
        when(entity.isValid()).thenReturn(true);
        return entity;
    }

    private EntityEquipment equipment(LivingEntity entity) {
        EntityEquipment equipment = mock(EntityEquipment.class);
        when(entity.getEquipment()).thenReturn(equipment);
        return equipment;
    }

    private PlayerInventory inventory(Player player) {
        PlayerInventory inventory = mock(PlayerInventory.class);
        when(player.getInventory()).thenReturn(inventory);
        when(inventory.addItem(any(ItemStack.class))).thenReturn(new HashMap<>());
        return inventory;
    }

    private BehaviorContext context(Entity caster, LivingEntity... targets) {
        ModelInstance instance = mock(ModelInstance.class);
        when(instance.isValid()).thenReturn(true);
        when(instance.baseEntity()).thenReturn(Optional.ofNullable(caster));
        when(instance.executionAudience()).thenReturn(Optional.empty());
        when(instance.location()).thenReturn(origin);
        return BehaviorContext.execution(instance, null, 1, 0, 0, "test", null,
                null, List.of(targets), origin);
    }

    private void action(String name, BehaviorContext context, String... arguments) {
        ItemCopyActions.wrap(Map.of(), animation).get(name).create(List.of(arguments)).accept(context);
    }

    private static MythicItemCopySpec spec(String name, String... arguments) {
        return MythicItemCopySpec.parse(name, List.of(arguments));
    }

    private static ItemStack copyOf(ItemStack original) {
        ItemStack copy = mock(ItemStack.class);
        when(original.clone()).thenReturn(copy);
        return copy;
    }

    @Test void equipCopyCopiesEverySlotFromTargetToCasterAndRetainsExactClones() {
        LivingEntity caster = entity(LivingEntity.class), source = entity(LivingEntity.class);
        EntityEquipment destination = equipment(caster), original = equipment(source);
        var slots = List.of(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS,
                EquipmentSlot.FEET, EquipmentSlot.HAND, EquipmentSlot.OFF_HAND);
        Map<EquipmentSlot, ItemStack> copies = new HashMap<>();
        for (EquipmentSlot slot : slots) {
            ItemStack item = mock(ItemStack.class);
            when(original.getItem(slot)).thenReturn(item);
            copies.put(slot, copyOf(item));
        }
        action("equip-copy", context(caster, source), "slots=HEAD", "s=NONE", "amount=1");
        for (EquipmentSlot slot : slots) verify(destination).setItem(slot, copies.get(slot));
        verifyNoMoreInteractions(destination);
        verify(original, never()).setItem(any(), any(ItemStack.class));
        // No material/amount/meta reconstruction can discard any metadata present in the clone.
        copies.values().forEach(copy -> verifyNoInteractions(copy));
        verifyNoInteractions(animation);
    }

    @Test void airClearsDestinationButNullSlotLeavesItUntouched() {
        LivingEntity caster = entity(LivingEntity.class), source = entity(LivingEntity.class);
        EntityEquipment destination = equipment(caster), original = equipment(source);
        ItemStack air = mock(ItemStack.class);
        when(air.getType()).thenReturn(Material.AIR);
        ItemStack copiedAir = copyOf(air);
        when(original.getItem(EquipmentSlot.HEAD)).thenReturn(air);
        action("equip-copy", context(caster, source));
        verify(destination).setItem(EquipmentSlot.HEAD, copiedAir);
        verify(destination, never()).setItem(eq(EquipmentSlot.CHEST), any(ItemStack.class));
        verify(destination, never()).setItem(eq(EquipmentSlot.HAND), any(ItemStack.class));
    }

    @Test void nonlivingOrEquipmentlessCopySidesSucceedWithoutMutation() {
        LivingEntity living = entity(LivingEntity.class);
        EntityEquipment equipment = equipment(living);
        Entity nonliving = entity(Entity.class);
        var copy = spec("equipCopy");
        assertEquals(ItemCopyActions.Result.SUCCESS,
                ItemCopyActions.apply(copy, living, nonliving, origin, animation));
        assertEquals(ItemCopyActions.Result.SUCCESS,
                ItemCopyActions.apply(copy, nonliving, living, origin, animation));
        assertEquals(ItemCopyActions.Result.SUCCESS,
                ItemCopyActions.apply(copy, living, entity(LivingEntity.class), origin, animation));
        verifyNoInteractions(equipment, animation);
    }

    @Test void fromSlotCopiesWholeStackToTargetAndAnimationRetainsFullPreInventorySnapshot() {
        LivingEntity caster = entity(LivingEntity.class);
        EntityEquipment source = equipment(caster);
        Player player = entity(Player.class);
        PlayerInventory inventory = inventory(player);
        ItemStack original = mock(ItemStack.class);
        ItemStack snapshot = copyOf(original), submitted = copyOf(snapshot);
        when(original.getAmount()).thenReturn(9);
        when(snapshot.getAmount()).thenReturn(9);
        when(source.getItem(EquipmentSlot.OFF_HAND)).thenReturn(original);
        when(inventory.addItem(submitted)).thenAnswer(invocation -> {
            submitted.setAmount(0); // Bukkit may consume the submitted stack's amount.
            return new HashMap<Integer, ItemStack>();
        });
        action("give-item-from-slot", context(caster, player), "s=SHIELD", "amount=1", "fl=true");
        verify(inventory).addItem(submitted);
        ArgumentCaptor<ItemStack> captured = ArgumentCaptor.forClass(ItemStack.class);
        verify(animation).show(eq(player), captured.capture(), eq(origin));
        assertSame(snapshot, captured.getValue());
        assertEquals(9, captured.getValue().getAmount());
        assertEquals(9, original.getAmount());
        verify(original, never()).setAmount(anyInt());
        verify(snapshot, never()).setAmount(anyInt());
        verify(source, never()).setItem(any(), any(ItemStack.class));
    }

    @Test void fullInventoryDropsExactLeftoversAtTargetWithoutOwnershipMutation() {
        LivingEntity caster = entity(LivingEntity.class);
        EntityEquipment source = equipment(caster);
        Player player = entity(Player.class);
        PlayerInventory inventory = inventory(player);
        ItemStack original = mock(ItemStack.class), snapshot = copyOf(original), submitted = copyOf(snapshot);
        ItemStack firstLeftover = mock(ItemStack.class), secondLeftover = mock(ItemStack.class);
        when(source.getItem(EquipmentSlot.HAND)).thenReturn(original);
        when(inventory.addItem(submitted)).thenReturn(new HashMap<>(Map.of(0, firstLeftover, 1, secondLeftover)));
        Item dropped = mock(Item.class);
        when(world.dropItemNaturally(any(Location.class), any(ItemStack.class))).thenReturn(dropped);
        var result = ItemCopyActions.apply(spec("givefromslot", "s=0"), caster, player, origin, animation);
        assertEquals(ItemCopyActions.Result.SUCCESS, result);
        verify(world).dropItemNaturally(player.getLocation(), firstLeftover);
        verify(world).dropItemNaturally(player.getLocation(), secondLeftover);
        verifyNoInteractions(dropped, animation);
        verify(original, never()).setAmount(anyInt());
    }

    @Test void sourceConfigurationIsCheckedBeforeRecipientAndAirCountsAsPresent() {
        LivingEntity caster = entity(LivingEntity.class);
        EntityEquipment source = equipment(caster);
        Entity wrongTarget = entity(Entity.class);
        var hand = spec("givefromslot", "s=HAND");
        assertEquals(ItemCopyActions.Result.INVALID_CONFIG,
                ItemCopyActions.apply(hand, caster, wrongTarget, origin, animation));
        assertEquals(ItemCopyActions.Result.INVALID_CONFIG,
                ItemCopyActions.apply(spec("givefromslot", "s=\"HAND\""), caster, wrongTarget, origin, animation));
        ItemStack air = mock(ItemStack.class);
        when(air.getType()).thenReturn(Material.AIR);
        when(source.getItem(EquipmentSlot.HAND)).thenReturn(air);
        assertEquals(ItemCopyActions.Result.INVALID_TARGET,
                ItemCopyActions.apply(hand, caster, wrongTarget, origin, animation));
        Player player = entity(Player.class);
        PlayerInventory inventory = inventory(player);
        ItemStack copied = copyOf(air), submitted = copyOf(copied);
        assertEquals(ItemCopyActions.Result.SUCCESS,
                ItemCopyActions.apply(hand, caster, player, origin, animation));
        verify(inventory).addItem(submitted);
        verifyNoInteractions(animation);
    }

    @Test void absentCasterAndEquipmentAreInvalidConfiguration() {
        Player player = entity(Player.class);
        for (Entity caster : new Entity[] {null, entity(Entity.class), entity(LivingEntity.class)})
            assertEquals(ItemCopyActions.Result.INVALID_CONFIG, ItemCopyActions.apply(
                    spec("givefromslot", "s=HEAD"), caster, player, origin, animation));
        verify(player, never()).getInventory();
        verifyNoInteractions(animation);
    }

    @Test void missingOrLocationRecipientsNeverFallBackToTheCaster() {
        Player caster = entity(Player.class);
        EntityEquipment source = equipment(caster);
        var context = context(caster);
        action("give-item-from-slot", context, "s=HAND");
        action("equip-copy", context);
        action("give-item-from-slot", context.withEntityRecipient(caster).withLocationTarget(origin), "s=HAND");
        action("equip-copy", context.withEntityRecipient(caster).withLocationTarget(origin));
        verifyNoInteractions(source, animation);
        verify(caster, never()).getInventory();
    }

    @Test void eachTargetGetsIndependentCloneEvenAfterAnotherTargetFails() {
        LivingEntity caster = entity(LivingEntity.class);
        EntityEquipment source = equipment(caster);
        Player first = entity(Player.class), second = entity(Player.class);
        PlayerInventory firstInventory = inventory(first), secondInventory = inventory(second);
        ItemStack original = mock(ItemStack.class);
        ItemStack firstSnapshot = mock(ItemStack.class), secondSnapshot = mock(ItemStack.class);
        ItemStack firstSubmitted = copyOf(firstSnapshot), secondSubmitted = copyOf(secondSnapshot);
        when(original.clone()).thenReturn(firstSnapshot, secondSnapshot);
        when(source.getItem(EquipmentSlot.HAND)).thenReturn(original);
        when(firstInventory.addItem(firstSubmitted)).thenThrow(new IllegalStateException("inventory unavailable"));
        action("give-item-from-slot", context(caster, first, second), "slot=HAND", "fl=true");
        verify(firstInventory).addItem(firstSubmitted);
        verify(secondInventory).addItem(secondSubmitted);
        verify(animation, never()).show(eq(first), any(), any());
        verify(animation).show(second, secondSnapshot, origin);
    }

    @Test void selfGivingStillUsesIndependentStackAndQuotedFakeTrueIsDisabled() {
        Player player = entity(Player.class);
        EntityEquipment source = equipment(player);
        PlayerInventory inventory = inventory(player);
        ItemStack original = mock(ItemStack.class), snapshot = copyOf(original), submitted = copyOf(snapshot);
        when(source.getItem(EquipmentSlot.CHEST)).thenReturn(original);
        action("give-item-from-slot", context(player, player), "s=BODY", "fl=\"true\"");
        verify(inventory).addItem(submitted);
        verify(original, never()).setAmount(anyInt());
        verifyNoInteractions(animation);
    }

    @Test void nativeBindingPreservesRawSlotQuotesAliasPriorityAndIgnoredAmount() {
        LivingEntity caster = entity(LivingEntity.class);
        EntityEquipment source = equipment(caster);
        Player player = entity(Player.class);
        PlayerInventory inventory = inventory(player);
        ItemStack original = mock(ItemStack.class), snapshot = copyOf(original), submitted = copyOf(snapshot);
        when(source.getItem(EquipmentSlot.OFF_HAND)).thenReturn(original);
        var context = BehaviorContext.execution(context(caster).instance(), null, 1, 0, 0,
                "test", null, player, List.of(), origin);
        var service = new SkillService(new dev.helstera.ai.BehaviorRegistryImpl(), null);
        String key = service.bindAction("giveFromSlot{slot=\"SHIELD\";s=SHIELD} @trigger");
        assertNotNull(key);
        assertTrue(service.runAction(key, context));
        verify(inventory, never()).addItem(any(ItemStack.class));
        key = service.bindAction("giveItemFromSlot{s=HEAD;slot=HAND;SLOT=SHIELD;amount=0;fl=\"true\"} @trigger");
        assertNotNull(key);
        assertTrue(service.runAction(key, context));
        verify(inventory).addItem(submitted);
    }

    @Test void nativeBindingSelectsCopySourceAndExplicitLocationDoesNotCopyEquipment() {
        LivingEntity caster = entity(LivingEntity.class), selected = entity(LivingEntity.class);
        EntityEquipment destination = equipment(caster), source = equipment(selected);
        ItemStack original = mock(ItemStack.class), clone = copyOf(original);
        when(source.getItem(EquipmentSlot.HAND)).thenReturn(original);
        var context = BehaviorContext.execution(context(caster).instance(), null, 1, 0, 0,
                "test", null, selected, List.of(), origin);
        var service = new SkillService(new dev.helstera.ai.BehaviorRegistryImpl(), null);
        String key = service.bindAction("equipCopy{slots=NONE;amount=0} @trigger");
        assertNotNull(key);
        assertTrue(service.runAction(key, context));
        verify(destination).setItem(EquipmentSlot.HAND, clone);
        clearInvocations(destination);
        key = service.bindAction("equipCopy @selflocation");
        assertNotNull(key);
        assertTrue(service.runAction(key, context));
        verifyNoInteractions(destination);
    }
}
