package dev.helstera.ai.skill;

import dev.helstera.ai.loot.GeneratedLoot;
import dev.helstera.ai.loot.GeneratedLootTable;
import dev.helstera.ai.loot.LootService;
import dev.helstera.api.behavior.BehaviorContext;
import dev.helstera.api.instance.ModelInstance;
import dev.helstera.api.util.MythicGeneratedLootSpec;
import dev.helstera.api.util.MythicRewardEntry;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GeneratedLootActionsTest {
    private final World world = mock(World.class);
    private final Location castAt = new Location(world, 1, 64, 2);
    private final Location selectedAt = new Location(world, 20, 70, 30);
    private final GeneratedLoot generator = mock(GeneratedLoot.class);
    private final FakeItemLoot animation = mock(FakeItemLoot.class);
    private final Random random = new Random(1);
    private final MythicGeneratedLootSpec spec = new MythicGeneratedLootSpec("Rewards", true);

    @AfterEach void clearHooks() { GeneratedLootActions.loot(null); }

    private <T extends Entity> T entity(Class<T> type, Location at) {
        T entity = mock(type);
        when(entity.getLocation()).thenReturn(at);
        when(entity.getWorld()).thenReturn(world);
        when(entity.getUniqueId()).thenReturn(UUID.randomUUID());
        when(entity.isValid()).thenReturn(true);
        return entity;
    }

    private BehaviorContext context(Entity caster, LivingEntity... targets) {
        var instance = mock(ModelInstance.class);
        when(instance.baseEntity()).thenReturn(Optional.of(caster));
        when(instance.isValid()).thenReturn(true);
        when(instance.location()).thenReturn(castAt);
        return BehaviorContext.execution(instance, null, 1, 0, 0, "test", null,
                entity(Player.class, selectedAt), List.of(targets), castAt);
    }

    private GeneratedLoot.Reward reward(String raw) {
        var entry = MythicRewardEntry.parse(raw);
        return new GeneratedLoot.Reward(new GeneratedLootTable.Entry(entry, null), entry.minimum());
    }

    @Test void grantsGoToCasterAndOverflowOwnershipIsSetBeforeWorldInsertion() {
        Player caster = entity(Player.class, castAt), selected = entity(Player.class, selectedAt);
        var inventory = mock(PlayerInventory.class);
        when(caster.getInventory()).thenReturn(inventory);
        var reward = reward("DIAMOND 3");
        var bag = new GeneratedLoot.Bag(List.of(reward), 4.5);
        when(generator.generate(eq(spec), eq(caster), anyDouble(), anyDouble(), eq(random))).thenReturn(bag);
        ItemStack delivered = mock(ItemStack.class), submitted = mock(ItemStack.class), visual = mock(ItemStack.class);
        ItemStack overflow = mock(ItemStack.class);
        when(delivered.clone()).thenReturn(submitted);
        when(generator.materialize(reward)).thenReturn(delivered, visual);
        when(inventory.addItem(submitted)).thenReturn(new HashMap<>(java.util.Map.of(0, overflow)));
        Item dropped = mock(Item.class);
        when(world.dropItemNaturally(eq(castAt), eq(overflow), any())).thenAnswer(invocation -> {
            Consumer<Item> consumer = invocation.getArgument(2); consumer.accept(dropped); return dropped;
        });
        GeneratedLootActions.execute(spec, context(caster, selected), generator, animation, random);
        verify(inventory).addItem(submitted);
        verify(caster).giveExp(4);
        verify(selected, never()).getInventory();
        verify(dropped).setCanMobPickup(false);
        verify(dropped).setOwner(caster.getUniqueId());
        verify(dropped).setThrower(caster.getUniqueId());
        verify(animation).show(caster, visual, selectedAt);
        verify(generator, times(2)).materialize(reward);
    }

    @Test void eachSelectedOriginGeneratesOneBagAndLocationTargetTakesPrecedence() {
        Player caster = entity(Player.class, castAt);
        LivingEntity one = entity(LivingEntity.class, selectedAt), two = entity(LivingEntity.class, castAt);
        when(generator.generate(any(), eq(caster), anyDouble(), anyDouble(), any())).thenReturn(new GeneratedLoot.Bag(List.of(), 2));
        GeneratedLootActions.execute(spec, context(caster, one, two), generator, animation, random);
        verify(caster, times(2)).giveExp(2);
        clearInvocations(caster, generator);
        GeneratedLootActions.execute(spec, context(caster, one, two).withLocationTarget(selectedAt), generator, animation, random);
        verify(caster).giveExp(2);
        verify(generator).generate(eq(spec), eq(caster), anyDouble(), anyDouble(), eq(random));
    }

    @Test void nonplayerStillGeneratesButDoesNotMaterializeOrDeliver() {
        var caster = entity(LivingEntity.class, castAt);
        var selected = entity(Player.class, selectedAt);
        when(generator.generate(any(), eq(caster), anyDouble(), anyDouble(), any()))
                .thenReturn(new GeneratedLoot.Bag(List.of(reward("DIAMOND 3")), 8));
        GeneratedLootActions.execute(spec, context(caster, selected), generator, animation, random);
        verify(generator).generate(eq(spec), eq(caster), anyDouble(), anyDouble(), eq(random));
        verify(generator, never()).materialize(any());
        verify(selected, never()).getInventory();
        verifyNoInteractions(animation);
    }

    @Test void absenceOfSelectedEntityOrLocationDoesNotCreateRewards() {
        GeneratedLootActions.execute(spec, context(entity(Player.class, castAt)), generator, animation, random);
        verifyNoInteractions(generator, animation);
    }

    @Test void operatorStateIsRestoredWhenCommandDispatchThrows() {
        Player caster = entity(Player.class, castAt);
        when(caster.performCommand("reward 7")).thenThrow(new IllegalStateException("command failed"));
        var command = new MythicRewardEntry.Command("reward <drop.amount>", true, false, true);
        assertThrows(IllegalStateException.class,
                () -> GeneratedLootActions.command(command, 7.8, context(caster), caster));
        var order = inOrder(caster);
        order.verify(caster).setOp(true);
        order.verify(caster).performCommand("reward 7");
        order.verify(caster).setOp(false);
    }

    @Test void productionBindingSupportsNonlivingAndLocationOriginsAndRejectsMissingItems() throws Exception {
        var loot = new LootService(null);
        var config = new org.bukkit.configuration.file.YamlConfiguration();
        config.loadFromString("tables:\n  reward:\n    reward-drops: [exp 5]\n");
        loot.load(config.getConfigurationSection("tables"));
        GeneratedLootActions.loot(loot);
        Player caster = entity(Player.class, castAt);
        var context = context(caster).withEntityRecipient(entity(Entity.class, selectedAt));
        var service = new SkillService(new dev.helstera.ai.BehaviorRegistryImpl(), null);
        String key = service.bindAction("giveItemsFromTarget{items=reward;fl=false}");
        assertNotNull(key); assertTrue(service.runAction(key, context));
        key = service.bindAction("itemgivefromtarget{i=exp 2;fl=false} @selflocation");
        assertNotNull(key); assertTrue(service.runAction(key, context));
        verify(caster).giveExp(5);
        verify(caster).giveExp(2);
        assertNull(service.bindAction("givefromtarget{fl=false}"));
    }
}
