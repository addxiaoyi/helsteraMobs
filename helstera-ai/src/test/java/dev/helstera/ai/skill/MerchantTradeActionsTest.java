package dev.helstera.ai.skill;

import dev.helstera.ai.BehaviorRegistryImpl;
import dev.helstera.ai.loot.GeneratedLoot;
import dev.helstera.ai.loot.GeneratedLootTable;
import dev.helstera.api.behavior.BehaviorContext;
import dev.helstera.api.instance.ModelInstance;
import dev.helstera.api.util.MerchantTradeSpec;
import dev.helstera.api.util.MythicRewardEntry;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.entity.WanderingTrader;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MerchantRecipe;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MerchantTradeActionsTest {
    private final World world = mock(World.class);
    private final GeneratedLoot generator = mock(GeneratedLoot.class);
    private final Random random = new Random(1);
    private <T extends Entity> T entity(Class<T> type, int height) {
        var entity = mock(type);
        when(entity.isValid()).thenReturn(true);
        when(entity.getLocation()).thenReturn(new Location(world, 1, height, 2));
        return entity;
    }
    private BehaviorContext context(Entity caster, Entity trigger, LivingEntity... selected) {
        var instance = mock(ModelInstance.class);
        var location = caster.getLocation();
        when(instance.isValid()).thenReturn(true); when(instance.baseEntity()).thenReturn(Optional.of(caster));
        when(instance.location()).thenReturn(location);
        return BehaviorContext.execution(instance, null, 1, 0, 0, "test", null, trigger, List.of(selected), location);
    }
    private ItemStack stack() {
        var stack = mock(ItemStack.class); var count = new AtomicInteger(1);
        when(stack.clone()).thenReturn(stack); when(stack.getAmount()).thenAnswer(call -> count.get());
        doAnswer(call -> { count.set(call.getArgument(0)); return null; }).when(stack).setAmount(anyInt());
        return stack;
    }
    private void generateItems(List<ItemStack> items) {
        var entry = new GeneratedLootTable.Entry(MythicRewardEntry.parse("DIAMOND 3"), null);
        var command = new GeneratedLootTable.Entry(MythicRewardEntry.parse("command{c='ignored'} 1"), null);
        when(generator.generate(any(), any(), eq(1d), anyDouble(), eq(random))).thenReturn(
                new GeneratedLoot.Bag(List.of(new GeneratedLoot.Reward(command, 1), new GeneratedLoot.Reward(entry, 3)), 10));
        var pending = new ArrayList<>(items);
        when(generator.materialize(any())).thenAnswer(call -> {
            GeneratedLoot.Reward reward = call.getArgument(0);
            assertNull(reward.entry().reward().command()); assertEquals(1, reward.amount());
            return pending.removeFirst();
        });
    }
    @Test void insertsAtIndexWithMetadataAndTriggerAmountsButMerchantDropper() {
        var selected = entity(WanderingTrader.class, 14); var caster = entity(LivingEntity.class, 65);
        var trigger = entity(Player.class, 7);
        var oldA = mock(MerchantRecipe.class); var oldB = mock(MerchantRecipe.class);
        when(selected.getRecipes()).thenReturn(List.of(oldA, oldB));
        var output = stack(); var input = stack(); var second = stack(); generateItems(List.of(output, input, second));
        var spec = MerchantTradeSpec.parse(List.of("slot=1", "result=DIAMOND <target.l.y>", "i=EMERALD 3", "i2=BREAD 2",
                "uses=6", "exp=true", "vexp=8", "multiplier=0.25", "d=4", "special=-2", "discounts=true"));
        MerchantTradeActions.mutate(spec, context(caster, trigger, selected), generator, random);
        @SuppressWarnings("unchecked") ArgumentCaptor<List<MerchantRecipe>> capture = ArgumentCaptor.forClass(List.class);
        verify(selected).setRecipes(capture.capture()); var recipes = capture.getValue();
        assertSame(oldA, recipes.get(0)); assertSame(oldB, recipes.get(2));
        var recipe = recipes.get(1);
        assertEquals(7, recipe.getResult().getAmount());
        assertEquals(List.of(3, 2), recipe.getIngredients().stream().map(ItemStack::getAmount).toList());
        assertEquals(0, recipe.getUses()); assertEquals(6, recipe.getMaxUses()); assertTrue(recipe.hasExperienceReward());
        assertEquals(8, recipe.getVillagerExperience()); assertEquals(.25f, recipe.getPriceMultiplier());
        assertEquals(4, recipe.getDemand()); assertEquals(-2, recipe.getSpecialPrice()); assertTrue(recipe.shouldIgnoreDiscounts());
        verify(generator, times(3)).generate(any(), same(selected), eq(1d), eq(0d), eq(random));
    }
    @Test void replaceUsesOneRecipeAndDoesNotOverwriteTheList() {
        var selected = entity(WanderingTrader.class, 14); generateItems(List.of(stack(), stack()));
        MerchantTradeActions.mutate(MerchantTradeSpec.parse(List.of("mode=REPLACE", "index=2")),
                context(selected, selected, selected), generator, random);
        verify(selected).setRecipe(eq(2), any()); verify(selected, never()).setRecipes(any());
    }
    @Test void removeDoesNotGenerateItemsAndInvalidIndexCannotPartiallyWrite() {
        var selected = entity(WanderingTrader.class, 14); var original = mock(MerchantRecipe.class);
        when(selected.getRecipes()).thenReturn(List.of(original));
        MerchantTradeActions.mutate(MerchantTradeSpec.parse(List.of("m=REMOVE", "s=4")),
                context(selected, selected, selected), generator, random);
        verify(selected, never()).setRecipes(any());
        MerchantTradeActions.mutate(MerchantTradeSpec.parse(List.of("m=REMOVE")),
                context(selected, selected, selected), null, random);
        verify(selected).setRecipes(List.of()); verifyNoInteractions(generator);
    }
    @Test void productionBindingAcceptsUnknownModeAsInertAndRejectsMalformedSources() {
        var service = new SkillService(new BehaviorRegistryImpl(), null);
        var villager = entity(WanderingTrader.class, 14);
        String key = service.bindAction("setTrade{action=SET} @trigger"); assertNotNull(key);
        assertTrue(service.runAction(key, context(entity(Player.class, 7), villager)));
        verify(villager, never()).setRecipes(any()); verify(villager, never()).setRecipe(anyInt(), any());
        for (String invalid : List.of("addTrade ADD @trigger", "trade @unknown", "addTrade{multiplier=Infinity} @trigger"))
            assertNull(service.bindAction(invalid));
    }
    @Test void openBindingUsesCasterVillagerAndSelectedPlayerWithForce() {
        var service = new SkillService(new BehaviorRegistryImpl(), null);
        var villager = entity(Villager.class, 14); var player = entity(Player.class, 7);
        when(player.isOnline()).thenReturn(true);
        String key = service.bindAction("trade{real=true} @trigger"); assertNotNull(key);
        assertTrue(service.runAction(key, context(villager, player)));
        verify(player).openMerchant(villager, true); clearInvocations(player);
        assertTrue(service.runAction(key, context(player, villager)));
        assertTrue(service.runAction(key, context(entity(WanderingTrader.class, 14), player)));
        verify(player, never()).openMerchant(any(org.bukkit.inventory.Merchant.class), anyBoolean());
    }
}
