package dev.helstera.ai;

import dev.helstera.ai.loot.GeneratedLoot;
import dev.helstera.ai.loot.LootService;
import dev.helstera.api.util.MythicGeneratedLootSpec;
import org.bukkit.Location;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GeneratedLootTest {
    private final Entity caster = mock(Entity.class);
    private final Location location = new Location(null, 5, 60, 7);

    private LootService service(String yaml) throws Exception {
        var config = new YamlConfiguration();
        config.loadFromString(yaml);
        var service = new LootService(null);
        service.load(config.getConfigurationSection("tables"));
        when(caster.getLocation()).thenReturn(location);
        return service;
    }

    private GeneratedLoot.Bag generate(LootService service, String items) {
        return new GeneratedLoot(service).generate(MythicGeneratedLootSpec.parse(List.of("items=" + items)),
                caster, 1, 0, new Random(42));
    }

    @Test void mixedInlineRewardsKeepOrderAndAggregateExperienceBeforeTruncation() throws Exception {
        var bag = generate(service(""), "DIAMOND 2,exp 1.75,command{c='reward <drop.amount>'} 3,exp 2.75");
        assertEquals(2, bag.rewards().size());
        assertEquals("DIAMOND", bag.rewards().getFirst().entry().reward().item());
        assertEquals(3, bag.rewards().get(1).amount());
        assertEquals("reward <drop.amount>", bag.rewards().get(1).entry().reward().command().text());
        assertEquals(4.5, bag.experience());
    }

    @Test void tableLookupPrecedesQuoteDecodingAndNamesRetainCase() throws Exception {
        var service = service("""
                tables:
                  imported:
                    reward-source-id: '"ExactTable"'
                    reward-drops: [DIAMOND 4]
                """);
        assertEquals(4, generate(service, "\"ExactTable\"").rewards().getFirst().amount());
        assertNull(service.rewardTable("\"exacttable\""));
        service.load(new YamlConfiguration());
        assertNull(service.rewardTable("\"ExactTable\""));
    }

    @Test void nestedTablesRepeatAndContributeExperienceAndCommands() throws Exception {
        var service = service("""
                tables:
                  parent:
                    reward-drops: [Child 2]
                  child:
                    reward-source-id: Child
                    reward-drops: [DIAMOND 2, exp 3, "command{c='reward'} 4"]
                """);
        var bag = generate(service, "parent");
        assertEquals(4, bag.rewards().size());
        assertEquals(6, bag.experience());
        assertEquals(List.of(2d, 4d, 2d, 4d), bag.rewards().stream().map(GeneratedLoot.Reward::amount).toList());
    }

    @Test void experienceAndCommandsCompeteWithItemsInOneWeightedPool() throws Exception {
        var service = service("""
                tables:
                  mixed:
                    total-items: 1
                    reward-drops: [DIAMOND 2, exp 3, "command{c='reward'} 4"]
                """);
        var seen = new java.util.HashSet<String>();
        var generator = new GeneratedLoot(service);
        var random = new Random(5);
        for (int i = 0; i < 100; i++) {
            var bag = generator.generate(new MythicGeneratedLootSpec("mixed", false), caster, 1, 0, random);
            assertEquals(1, bag.rewards().size() + (bag.experience() > 0 ? 1 : 0));
            seen.add(bag.experience() > 0 ? "exp" : bag.rewards().getFirst().entry().reward().item());
        }
        assertEquals(java.util.Set.of("DIAMOND", "exp", "command"), seen);
    }

    @Test void maxCountCountsSelectedLinesBeforeNestedExpansion() throws Exception {
        var service = service("""
                tables:
                  parent:
                    max-items: 1
                    reward-drops: [child 2, EMERALD 1]
                  child:
                    reward-drops: [DIAMOND 1, exp 2]
                """);
        var bag = generate(service, "parent");
        assertEquals(2, bag.rewards().size());
        assertEquals(4, bag.experience());
        assertTrue(bag.rewards().stream().allMatch(r -> r.entry().reward().item().equals("DIAMOND")));
    }

    @Test void nonplayerCasterIsBothConditionSubjectsAndGenerationRunsWithoutDelivery() throws Exception {
        var service = service("""
                tables:
                  gated:
                    conditions: [dropper]
                    trigger-conditions: [cause]
                    reward-drops: [DIAMOND 1]
                """);
        var observed = new java.util.ArrayList<String>();
        service.setDropperConditionEvaluator((condition, at, entity) -> {
            assertSame(caster, entity); assertEquals(location, at); observed.add(condition); return true;
        });
        assertEquals(1, generate(service, "gated").rewards().size());
        assertEquals(List.of("dropper", "cause"), observed);
        service.setDropperConditionEvaluator((condition, at, entity) -> !condition.equals("cause"));
        assertTrue(generate(service, "gated").rewards().isEmpty());
    }

    @Test void cyclicTablesAndExcessiveExpansionCannotHangTheServer() throws Exception {
        var service = service("""
                tables:
                  cycle:
                    reward-drops: [cycle 1]
                  huge:
                    total-items: 10001
                    reward-drops: [DIAMOND 1]
                """);
        assertTrue(generate(service, "cycle").rewards().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> generate(service, "huge"));
    }

    @Test void nativeItemFilteringSkipsZeroAndFractionalCandidatesBeforeMerchantSelection() throws Exception {
        var generator = new GeneratedLoot(service(""));
        var spec = new MythicGeneratedLootSpec("DIAMOND 0 1,EMERALD 0.5 1,BREAD 1", false);
        var ordinary = generator.generate(spec, caster, 1, 0, new Random(1));
        assertEquals(List.of("BREAD"), ordinary.rewards().stream().map(r -> r.entry().reward().item()).toList());
        assertEquals(1, ordinary.rewards().getFirst().amount());
    }

    @Test void ordinaryLootConfigurationRetainsInclusiveAmountsAndItemMetadata() throws Exception {
        var service = service("""
                tables:
                  configured:
                    entries:
                    - item: DIAMOND
                      amount-min: 2
                      amount-max: 3
                      name: Reward
                    - item: exp
                      amount: 5
                """);
        var bag = generate(service, "configured");
        assertEquals(5, bag.experience());
        var reward = bag.rewards().getFirst();
        assertTrue((int) reward.amount() >= 2 && (int) reward.amount() <= 3);
        assertEquals("Reward", reward.entry().itemTemplate().displayName());
    }

    @Test void invalidOrAmbiguousNamedTableCannotFallBackToItemResolution() throws Exception {
        var service = service("""
                tables:
                  DIAMOND:
                    reward-drops: [mmoi:SWORD 1]
                  first:
                    reward-source-id: duplicate
                    reward-drops: [exp 1]
                  second:
                    reward-source-id: duplicate
                    reward-drops: [exp 2]
                """);
        assertThrows(IllegalArgumentException.class, () -> generate(service, "DIAMOND"));
        assertThrows(IllegalArgumentException.class, () -> generate(service, "duplicate"));
        service.load(new YamlConfiguration());
        assertEquals("DIAMOND", generate(service, "DIAMOND").rewards().getFirst().entry().reward().item());
    }

    @Test void configuredRewardEntriesHonorDynamicChancesAndTiers() throws Exception {
        var service = service("""
                tables:
                  configured:
                    entries:
                    - item: DIAMOND
                      chance-expression: denied
                    - item: EMERALD
                      tiers:
                      - amount-min: 7
                        amount-max: 7
                        weight: 1
                """);
        service.setDynamicChanceEvaluator((expression, at, player, luck) -> 0);
        var bag=generate(service,"configured");
        assertEquals(1,bag.rewards().size());
        assertEquals("EMERALD",bag.rewards().getFirst().entry().reward().item());
        assertEquals(7,bag.rewards().getFirst().amount());
    }
}
