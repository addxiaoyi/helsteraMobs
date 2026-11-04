package dev.helstera.ai;

import dev.helstera.ai.loot.LootService;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 掉落系统的纯逻辑测试。
 *
 * <p>只测 {@link LootService#rollPlan}：概率判定、数量区间、幸运值影响、配置容错。
 * 这部分刻意做成不依赖 Bukkit 类型的纯函数，因此可以在没有运行中服务端的
 * 环境下验证。物品实体化（ItemStack/材质注册表）不在单测覆盖范围内。</p>
 */
class LootServiceTest {

    @Test
    void nativeIndexedNullChanceCannotDefaultToGuaranteedDrop(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)
            throws Exception {
        var file = dir.resolve("loot.yml");
        java.nio.file.Files.writeString(file, """
                null-chance: &null-chance {chance: null}
                tables:
                  root:
                    entries:
                      0: {item: DIAMOND, chance: null}
                      1: {item: exp, amount: 4, chance: ~}
                      2: {table: child, chance: null}
                      3: {<<: *null-chance, item: EMERALD}
                      4: {<<: *null-chance, item: GOLD_INGOT, chance: 1}
                      5: {item: BREAD}
                  child:
                    entries: [{item: IRON_INGOT}]
                  weighted:
                    total-items: 1
                    entries:
                      0: {item: DIAMOND, chance: null}
                      1: {item: BREAD, chance: 1}
                """);
        LootService s = new LootService(null);
        s.loadFile(file);
        var hits = s.rollPlan(s.table("root"), 10, new Random(1));
        assertEquals(List.of("GOLD_INGOT", "BREAD"), hits.stream().map(hit -> hit.entry().itemId()).toList());
        assertEquals(0, s.experience("root", new Random(1)));
        assertEquals(5, s.warnings().stream().filter(w -> w.contains("chance")).count());
        for (int seed = 0; seed < 20; seed++) {
            assertEquals("BREAD", s.rollPlan(s.table("weighted"), 0, new Random(seed)).get(0).entry().itemId());
        }
    }

    @Test
    void nativeFileMergedNullConditionsRespectOverrides(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)
            throws Exception {
        var file = dir.resolve("loot.yml");
        java.nio.file.Files.writeString(file, """
                defaults: &defaults
                  conditions: null
                  trigger-conditions: null
                entry: &entry
                  item: DIAMOND
                  conditions: null
                allowed: &allowed
                  conditions: [allowed]
                  trigger-conditions: [allowed]
                shared-tables: &tables
                  inherited:
                    <<: *defaults
                    experience: 4
                    entries: [{item: DIAMOND}]
                tables:
                  <<: *tables
                  indexed:
                    entries:
                      0: {<<: *entry}
                      1: {<<: *entry, item: exp, amount: 4}
                  override:
                    <<: *defaults
                    conditions: [allowed]
                    trigger-conditions: [allowed]
                    entries: [{item: EMERALD}]
                  entry-override:
                    entries:
                      0: {<<: *entry, conditions: [allowed]}
                  null-first:
                    <<: [*defaults, *allowed]
                    entries: [{item: DIAMOND}]
                  allowed-first:
                    <<: [*allowed, *defaults]
                    entries: [{item: EMERALD}]
                """);
        LootService s = new LootService(null);
        s.loadFile(file);
        var killer = org.mockito.Mockito.mock(org.bukkit.entity.Player.class);
        var dropper = org.mockito.Mockito.mock(org.bukkit.entity.Zombie.class);
        s.setConditionEvaluator((condition, location, player) -> true);
        s.setDropperConditionEvaluator((condition, location, entity) -> true);
        for (String name : List.of("inherited", "indexed", "null-first")) {
            assertTrue(s.rollPlan(s.table(name), 0, new Random(1), null, killer, 0, dropper).isEmpty(), name);
            assertEquals(0, s.experience(name, new Random(1), null, killer, dropper), name);
        }
        for (String name : List.of("override", "entry-override", "allowed-first")) {
            assertEquals(1, s.rollPlan(s.table(name), 0, new Random(1), null, killer, 0, dropper).size(), name);
        }
    }

    @Test
    void nativeFileNullConditionsFailClosedAndReloadFailureClearsTables(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)
            throws Exception {
        var file = dir.resolve("loot.yml");
        java.nio.file.Files.writeString(file, """
                tables:
                  invalid-dropper:
                    conditions: null
                    experience: 4
                    entries: [{item: DIAMOND}]
                  invalid-trigger:
                    trigger-conditions: ~
                    experience: 4
                    entries: [{item: DIAMOND}]
                  invalid-entry:
                    entries:
                      0: {item: DIAMOND, conditions: null}
                      1: {item: exp, amount: 4, conditions: ~}
                  valid:
                    conditions: [allowed]
                    entries: [{item: EMERALD}]
                  unrestricted:
                    entries: [{item: BREAD}]
                """);
        LootService s = new LootService(null);
        s.loadFile(file);
        var killer = org.mockito.Mockito.mock(org.bukkit.entity.Player.class);
        var dropper = org.mockito.Mockito.mock(org.bukkit.entity.Zombie.class);
        s.setConditionEvaluator((condition, location, player) -> true);
        s.setDropperConditionEvaluator((condition, location, entity) -> true);
        for (String name : List.of("invalid-dropper", "invalid-trigger", "invalid-entry")) {
            assertTrue(s.rollPlan(s.table(name), 0, new Random(1), null, killer, 0, dropper).isEmpty(), name);
            assertEquals(0, s.experience(name, new Random(1), null, killer, dropper), name);
        }
        assertEquals(1, s.rollPlan(s.table("valid"), 0, new Random(1), null, killer, 0, dropper).size());
        assertEquals(1, s.rollPlan(s.table("unrestricted"), 0, new Random(1)).size());
        assertTrue(s.warnings().stream().anyMatch(w -> w.contains("invalid condition")));
        java.nio.file.Files.writeString(file, "tables: [unterminated");
        org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class, () -> s.loadFile(file));
        org.junit.jupiter.api.Assertions.assertNull(s.table("valid"));
    }

    @Test
    void invalidChanceRejectsDropEvenWithLuckScaling() {
        LootService s = service("""
                tables:
                  t:
                    luck-factor: 1
                    entries:
                      - {item: DIAMOND, chance: invalid}
                      - {item: EMERALD, chance: .nan}
                      - {item: GOLD_INGOT, chance: .inf}
                      - {item: IRON_INGOT, chance: null}
                      - {item: COAL, chance: ""}
                      - {item: BREAD}
                """);
        var hits = s.rollPlan(s.table("t"), 10, new Random(1));
        assertEquals(1, hits.size());
        assertEquals("BREAD", hits.get(0).entry().itemId());
        assertEquals(5, s.warnings().stream().filter(w -> w.contains("chance")).count());
        assertTrue(s.warnings().stream().anyMatch(w -> w.contains("chance = null")));
    }

    @Test
    void fixedAmountAppliesToItemAndExperienceAndRangeOverridesIt() {
        LootService s = service("""
                tables:
                  fixed:
                    entries: [{item: DIAMOND, amount: 4}, {item: xp, amount: 5}]
                  ranged:
                    entries: [{item: DIAMOND, amount: 9, amount-min: 2, amount-max: 3}]
                  invalid:
                    entries: [{item: xp, amount: invalid}, {item: xp, amount: 0}]
                """);
        assertEquals(4, s.rollPlan(s.table("fixed"), 0, new Random(1)).get(0).amount());
        assertEquals(5, s.experience("fixed", new Random(1)));
        for (int i = 0; i < 20; i++) {
            int amount = s.rollPlan(s.table("ranged"), 0, new Random(i)).get(0).amount();
            assertTrue(amount >= 2 && amount <= 3);
        }
        assertTrue(s.warnings().stream().anyMatch(w -> w.contains("invalid") && w.contains("amount")));
        assertTrue(s.warnings().stream().anyMatch(w -> w.contains("amount 必须为正数")));
    }

    @Test
    void experienceDropRespectsKnownKillerTier() {
        LootService s = service("""
                tables:
                  t:
                    experience: 2
                    entries:
                      - item: xp
                        amount-min: 5
                        amount-max: 5
                        min-tier-level: 3
                """);
        var killer = org.mockito.Mockito.mock(org.bukkit.entity.Player.class);
        s.setTierResolver(player -> 2);
        assertEquals(2, s.experience("t", new Random(1), null, killer));
        assertEquals(7, s.experience("t", new Random(1), 0, null, killer, 3, null));
        s.setTierResolver(player -> 3);
        assertEquals(7, s.experience("t", new Random(1), null, killer));
        s.setTierResolver(player -> -1);
        assertEquals(7, s.experience("t", new Random(1), null, killer));
        assertEquals(2, s.experience("t", new Random(1), 0, null, killer, 2, null));
        assertEquals(7, s.experience("t", new Random(1), 0, null, null, 3, null));
        assertEquals(7, s.experience("t", new Random(1)));
    }

    @Test
    void invalidTierRestrictionsDoNotBecomeUnconditionalDrops(@org.junit.jupiter.api.io.TempDir java.nio.file.Path root)
            throws Exception {
        var file = root.resolve("loot.yml");
        java.nio.file.Files.writeString(file, """
                defaults: &defaults {min-tier-level: null}
                tables:
                  list:
                    entries:
                      - {item: DIAMOND, min-tier-level: null}
                      - {item: EMERALD, min-tier-level: 0}
                      - {item: GOLD_INGOT, min-tier-level: nope}
                      - {item: exp, amount: 4, min-tier-level: -1}
                      - {item: BREAD, min-tier-level: 2}
                  indexed:
                    entries:
                      0: {<<: *defaults, item: DIAMOND}
                      1: {<<: *defaults, item: BREAD, min-tier-level: 2}
                """);
        LootService s = new LootService(null);
        s.loadFile(file);
        for (String name : List.of("list", "indexed")) {
            assertTrue(s.rollPlan(s.table(name), 0, new Random(1), 1, null, null).isEmpty());
            var hits = s.rollPlan(s.table(name), 0, new Random(1), 2, null, null);
            assertEquals(1, hits.size());
            assertEquals("BREAD", hits.get(0).entry().itemId());
        }
        assertEquals(0, s.experience("list", new Random(1)));
        assertEquals(5, s.warnings().stream().filter(w -> w.contains("min-tier-level is invalid")).count());
    }

    @Test
    void nestedExperienceWarningTraversesTablesAndStopsCycles() {
        LootService s = service("""
                tables:
                  root:
                    entries: [{table: middle}]
                  middle:
                    entries: [{table: leaf}, {table: root}]
                  leaf:
                    entries: [{item: xp, amount: 5}]
                  plain:
                    entries: [{table: cycle}]
                  cycle:
                    entries: [{table: plain}]
                """);

        assertTrue(s.warnings().stream().anyMatch(w -> w.contains("root")
                && w.contains("middle") && w.contains("经验")));
        assertTrue(s.warnings().stream().anyMatch(w -> w.contains("middle")
                && w.contains("leaf") && w.contains("经验")));
        assertFalse(s.warnings().stream().anyMatch(w -> w.contains("plain") && w.contains("经验")));
        assertEquals(0, s.experience("root", new Random(1)));
    }

    private static LootService service(String yaml) {
        LootService s = new LootService(null);
        s.load(YamlConfiguration.loadConfiguration(new StringReader(yaml)).getConfigurationSection("tables"));
        return s;
    }

    @Test
    void declaredInvalidItemDoesNotFallBackToVanilla() {
        LootService s = service("tables: {t: {entries: [{item: DIAMOND}]}}");
        s.setCustomItemResolver(id -> null);
        s.setClaimedItemPredicate(id -> id.equals("DIAMOND"));
        try (var construction = org.mockito.Mockito.mockConstruction(org.bukkit.inventory.ItemStack.class)) {
            assertTrue(s.materialize(s.rollPlan("t", 0)).isEmpty());
            assertTrue(construction.constructed().isEmpty());
        }
    }

    @Test
    void externalItemNamespacesNeverFallBackToVanillaMaterials() {
        LootService s = service("""
                tables:
                  external:
                    entries: [{item: 'mmoitems:DIAMOND'}]
                  vanilla:
                    entries: [{item: 'minecraft:DIAMOND'}]
                """);
        var external = s.rollPlan("external", 0);
        assertTrue(s.materialize(external).isEmpty());
        s.setCustomItemResolver(id -> { throw new IllegalStateException("provider unavailable"); });
        assertTrue(s.materialize(external).isEmpty());
        try (var construction = org.mockito.Mockito.mockConstruction(org.bukkit.inventory.ItemStack.class,
                (stack, context) -> {
                    assertEquals(org.bukkit.Material.DIAMOND, context.arguments().get(0));
                    org.mockito.Mockito.when(stack.getType()).thenReturn(org.bukkit.Material.DIAMOND);
                    org.mockito.Mockito.when(stack.clone()).thenReturn(stack);
                })) {
            assertEquals(1, s.materialize(s.rollPlan("vanilla", 0)).size());
            assertEquals(1, construction.constructed().size());
        }

        var custom = org.mockito.Mockito.mock(org.bukkit.inventory.ItemStack.class);
        var clone = org.mockito.Mockito.mock(org.bukkit.inventory.ItemStack.class);
        org.mockito.Mockito.when(custom.getType()).thenReturn(org.bukkit.Material.EMERALD);
        org.mockito.Mockito.when(custom.clone()).thenReturn(clone);
        s.setCustomItemResolver(id -> id.equals("mmoitems:DIAMOND") ? custom : null);
        assertEquals(List.of(clone), s.materialize(external));
    }

    @Test
    @DisplayName("chance=1.0 必定命中，数量落在区间内")
    void alwaysHitsWhenChanceIsOne() {
        LootService s = service("""
                tables:
                  t:
                    entries:
                      - item: DIAMOND
                        amount-min: 2
                        amount-max: 4
                    """);
        for (int i = 0; i < 50; i++) {
            var hits = s.rollPlan("t", 0);
            assertEquals(1, hits.size());
            assertEquals("DIAMOND", hits.get(0).entry().itemId());
            assertTrue(hits.get(0).amount() >= 2 && hits.get(0).amount() <= 4,
                    "数量越界: " + hits.get(0).amount());
        }
    }

    @Test
    @DisplayName("chance=0 永不命中")
    void neverHitsWhenChanceIsZero() {
        LootService s = service("""
                tables:
                  t:
                    entries:
                      - item: DIAMOND
                        chance: 0
                    """);
        for (int i = 0; i < 100; i++) {
            assertTrue(s.rollPlan("t", 0).isEmpty(), "chance=0 不应命中");
        }
    }

    @Test
    @DisplayName("exp 掉落行只投掷经验，不会作为物品掉落")
    void experienceDropLinesRollExperienceOnly() {
        LootService s = service("""
                tables:
                  t:
                    experience: 3
                    entries:
                      - item: exp
                        amount-min: 4
                        amount-max: 4
                        chance: 1
                      - item: xp
                        amount-min: 6
                        amount-max: 6
                        chance: 0
                      - item: DIAMOND
                        chance: 1
                    """);

        assertEquals(1, s.rollPlan(s.table("t"), 0, new Random(4)).size());
        assertEquals("DIAMOND", s.rollPlan(s.table("t"), 0, new Random(4)).get(0).entry().itemId());
        assertEquals(7, s.experience("t", new Random(4)));
    }

    @Test
    @DisplayName("TotalItems 计数与权重池排除经验条目")
    void weightedItemLinesExcludeExperienceEntries() {
        LootService s = service("""
                tables:
                  t:
                    total-items: 2
                    experience: 1
                    entries:
                      - item: exp
                        amount-min: 5
                        amount-max: 5
                        chance: 1
                      - item: DIAMOND
                        chance: 1
                        luck-scaling: false
                      - item: EMERALD
                        chance: 1
                        luck-scaling: false
                    """);

        var hits = s.rollPlan(s.table("t"), 0, new Random(8));
        assertEquals(2, hits.size());
        assertTrue(hits.stream().noneMatch(hit -> hit.entry().isExperienceDrop()));
        assertEquals(6, s.experience("t", new Random(8)));
    }

    @Test
    @DisplayName("exp 掉落行遵守条件，缺失 evaluator 時 fail closed")
    void experienceDropConditionsFailClosed() {
        LootService s = service("""
                tables:
                  t:
                    entries:
                      - item: exp
                        amount-min: 5
                        amount-max: 5
                        chance: 1
                        conditions:
                          - loot-exp-allowed
                    """);
        var killer = org.mockito.Mockito.mock(org.bukkit.entity.Player.class);

        assertEquals(0, s.experience("t", new Random(1), null, killer));
        s.setConditionEvaluator((condition, location, player) -> condition.equals("loot-exp-allowed"));
        assertEquals(5, s.experience("t", new Random(1), null, killer));
        s.setConditionEvaluator((condition, location, player) -> false);
        assertEquals(0, s.experience("t", new Random(1), null, killer));
    }

    @Test
    @DisplayName("表级 trigger-conditions 由击杀者条件求值器判定并在缺失时关闭")
    void triggerConditionsRequireKillerConditionEvaluator() {
        LootService s = service("""
                tables:
                  t:
                    experience-min: 7
                    experience-max: 7
                    trigger-conditions:
                      - permission{p=loot.vip}
                    entries:
                      - item: DIAMOND
                        chance: 1
                  root:
                    experience-min: 5
                    experience-max: 5
                    trigger-conditions:
                      - parent-pass
                    entries:
                      - table: nested
                  nested:
                    trigger-conditions:
                      - child-pass
                    entries:
                      - item: EMERALD
                        chance: 1
                """);
        assertEquals(List.of("permission{p=loot.vip}"), s.table("t").triggerConditions());
        var killer = org.mockito.Mockito.mock(org.bukkit.entity.Player.class);
        Random rnd = new Random(7);
        assertTrue(s.rollPlan(s.table("t"), 0, rnd, null, killer).isEmpty(),
                "没有条件求值器时表级 trigger 条件 fail closed");
        assertTrue(s.rollPlan(s.table("t"), 0, rnd, null, null).isEmpty(),
                "没有击杀者时表级 trigger 条件 fail closed");
        assertTrue(s.rollPlan(s.table("root"), 0, rnd, null, killer).isEmpty(),
                "嵌套表的 trigger 条件也必须单独求值");
        assertEquals(0, s.experience("root", rnd, null, killer),
                "没有条件求值器时表经验也必须 fail closed");

        s.setConditionEvaluator((condition, location, playerContext) -> playerContext != null
                && (condition.equals("permission{p=loot.vip}") || condition.equals("parent-pass")
                || condition.equals("child-pass")));
        assertEquals(1, s.rollPlan(s.table("t"), 0, rnd, null, killer).size(),
                "通过的击杀者条件应允许投表");
        assertEquals(7, s.experience("t", rnd, null, killer),
                "通过的表级条件应允许表经验");
        assertEquals(0, s.experience("t", rnd),
                "无上下文重载不能绕过表级击杀者条件");
        assertEquals(0, s.experience("t"),
                "默认随机源重载也不能绕过表级击杀者条件");
        assertEquals("EMERALD", s.rollPlan(s.table("root"), 0, rnd, null, killer)
                .get(0).entry().itemId(), "通过的条件应允许嵌套表掉落");
        assertEquals(5, s.experience("root", rnd, null, killer),
                "表级条件通过后应允许对应表经验");

        s.setConditionEvaluator((condition, location, playerContext) ->
                condition.equals("parent-pass") && playerContext != null);
        assertTrue(s.rollPlan(s.table("t"), 0, rnd, null, killer).isEmpty(),
                "拒绝的击杀者条件应跳过整张表");
        assertEquals(0, s.experience("t", rnd, null, killer),
                "拒绝的表级条件应抑制表经验");
        assertTrue(s.rollPlan(s.table("root"), 0, rnd, null, killer).isEmpty(),
                "嵌套表拒绝击杀者条件时不能产生掉落");
        assertEquals(5, s.experience("root", rnd, null, killer),
                "子表条件拒绝不能覆盖已经通过的父表经验");

        s.setConditionEvaluator((condition, location, playerContext) -> false);
        assertEquals(0, s.experience("t", rnd, null, killer),
                "拒绝的表级条件不能产生表经验");
    }

    @Test
    @DisplayName("DropTable Conditions evaluate the dead dropper independently from the killer")
    void dropperConditionsUseTheDropperContext() {
        LootService s = service("""
                tables:
                  t:
                    conditions:
                      - is-monster
                    experience-min: 6
                    experience-max: 6
                    entries:
                      - item: DIAMOND
                        chance: 1
                """);
        var dropper = org.mockito.Mockito.mock(org.bukkit.entity.Zombie.class);
        var killer = org.mockito.Mockito.mock(org.bukkit.entity.Player.class);
        assertTrue(s.rollPlan(s.table("t"), 0, new Random(1), null, killer, 0, dropper).isEmpty(),
                "missing dropper evaluator fails closed");
        assertEquals(0, s.experience("t", new Random(1), null, killer, dropper));

        s.setDropperConditionEvaluator((condition, location, actual) ->
                "is-monster".equals(condition) && actual == dropper);
        assertEquals(List.of("is-monster"), s.table("t").conditions());
        assertEquals(1, s.rollPlan(s.table("t"), 0, new Random(1), null, killer, 0, dropper).size());
        assertEquals(6, s.experience("t", new Random(1), null, killer, dropper));
        assertEquals(0, s.experience("t", new Random(1)),
                "missing dropper must not bypass table conditions");
        assertEquals(0, s.experience("t", new Random(1), null, killer),
                "killer context alone cannot satisfy dropper conditions");
        assertTrue(s.rollPlan(s.table("t"), 0, new Random(1), null, killer, 0,
                org.mockito.Mockito.mock(org.bukkit.entity.Zombie.class)).isEmpty(),
                "condition evaluator can reject a different dropper");
    }

    @Test
    void scalarTableConditionsGateItemsExperienceAndNestedTables() {
        LootService s = service("""
                tables:
                  parent:
                    entries:
                      - table: child
                  child:
                    trigger-conditions: killer-ok
                    conditions: dropper-ok
                    experience: 6
                    entries:
                      - item: DIAMOND
                        chance: 1
                """);
        var killer = org.mockito.Mockito.mock(org.bukkit.entity.Player.class);
        var dropper = org.mockito.Mockito.mock(org.bukkit.entity.Zombie.class);
        assertEquals(List.of("killer-ok"), s.table("child").triggerConditions());
        assertEquals(List.of("dropper-ok"), s.table("child").conditions());
        assertTrue(s.rollPlan(s.table("parent"), 0, new Random(1), null, killer, 0, dropper).isEmpty());
        assertEquals(0, s.experience("child", new Random(1), null, killer, dropper));
        s.setConditionEvaluator((condition, location, player) -> player == killer && condition.equals("killer-ok"));
        s.setDropperConditionEvaluator((condition, location, entity) -> entity == dropper && condition.equals("dropper-ok"));
        assertEquals(1, s.rollPlan(s.table("parent"), 0, new Random(1), null, killer, 0, dropper).size());
        assertEquals(6, s.experience("child", new Random(1), null, killer, dropper));
        assertTrue(s.rollPlan(s.table("parent"), 0, new Random(1), null, killer, 0, null).isEmpty());
        assertEquals(0, s.experience("child", new Random(1), null, null, dropper));
    }

    @Test
    void malformedConditionsFailClosedEvenWhenEvaluatorAcceptsEverything() {
        LootService s = service("""
                tables:
                  invalid-trigger:
                    trigger-conditions: [""]
                    experience: 4
                    entries: [{item: DIAMOND}]
                  invalid-dropper:
                    conditions: [42]
                    experience: 4
                    entries: [{item: DIAMOND}]
                  invalid-entry:
                    entries:
                      - {item: DIAMOND, conditions: [""]}
                      - {item: exp, amount-min: 4, conditions: [null]}
                      - {item: EMERALD, conditions: null}
                      - {item: exp, amount-min: 5, conditions: null}
                  invalid-separated-trigger:
                    trigger-conditions: 'yes;'
                    experience: 4
                    entries: [{item: DIAMOND}]
                  invalid-separated-dropper:
                    conditions: 'yes;;yes'
                    experience: 4
                    entries: [{item: DIAMOND}]
                  invalid-separated-entry:
                    entries:
                      - {item: DIAMOND, conditions: 'yes |'}
                      - {item: exp, amount-min: 4, conditions: 'yes;'}
                  invalid-unbalanced:
                    conditions: 'yes{'
                    experience: 4
                    entries: [{item: DIAMOND}]
                  unrestricted:
                    conditions: []
                    entries: [{item: DIAMOND}]
                """);
        var killer = org.mockito.Mockito.mock(org.bukkit.entity.Player.class);
        var dropper = org.mockito.Mockito.mock(org.bukkit.entity.Zombie.class);
        s.setConditionEvaluator((condition, location, player) -> true);
        s.setDropperConditionEvaluator((condition, location, entity) -> true);
        for (String table : List.of("invalid-trigger", "invalid-dropper", "invalid-entry",
                "invalid-separated-trigger", "invalid-separated-dropper", "invalid-separated-entry", "invalid-unbalanced")) {
            assertTrue(s.rollPlan(s.table(table), 0, new Random(1), null, killer, 0, dropper).isEmpty());
            assertEquals(0, s.experience(table, new Random(1), null, killer, dropper));
        }
        assertEquals(1, s.rollPlan(s.table("unrestricted"), 0, new Random(1)).size());
        assertTrue(s.warnings().stream().anyMatch(w -> w.contains("invalid condition")));
    }

    @Test
    @DisplayName("luck-scaling 随幸运值提高命中概率，且不会超过 1.0")
    void luckRaisesChanceWithinBounds() {
        LootService s = service("""
                tables:
                  t:
                    luck-factor: 0.1
                    entries:
                      - item: DIAMOND
                        chance: 0.5
                        luck-scaling: true
                    """);
        // 固定随机源 + 大量采样，统计幸运值对命中率的影响
        int low = hits(s, 0, 0L, 20000);
        int high = hits(s, 10, 0L, 20000);
        assertTrue(high > low, "幸运值应提高命中率: low=" + low + " high=" + high);
        assertTrue(high <= 20000);
    }

    @Test
    @DisplayName("luck-scaling=false 时幸运值不影响概率")
    void fixedChanceIgnoresLuck() {
        LootService s = service("""
                tables:
                  t:
                    luck-factor: 0.5
                    entries:
                      - item: DIAMOND
                        chance: 0.5
                        luck-scaling: false
                    """);
        assertEquals(hits(s, 0, 0L, 20000), hits(s, 20, 0L, 20000));
    }

    /**
     * 用固定种子的随机源连续取样。
     *
     * <p>刻意复用同一个 Random 实例而不是每次 {@code new Random(i)}：Java 的 Random
     * 对连续小种子的首个输出高度相关，用它做抽样会得到「全部同中或全部同失」的假象。</p>
     */
    private int hits(LootService s, double luck, long seed, int n) {
        Random rnd = new Random(seed);
        int c = 0;
        for (int i = 0; i < n; i++) {
            if (!s.rollPlan(s.table("t"), luck, rnd).isEmpty()) c++;
        }
        return c;
    }

    @Test
    @DisplayName("多条独立投掷：各自判定，不是整表一起中或一起不中")
    void entriesRollIndependently() {
        LootService s = service("""
                tables:
                  t:
                    entries:
                      - item: BREAD
                        chance: 0.5
                      - item: IRON_INGOT
                        chance: 0.5
                    """);
        Random rnd = new Random(12345);
        boolean sawOne = false, sawTwo = false;
        for (int i = 0; i < 200; i++) {
            int n = s.rollPlan(s.table("t"), 0, rnd).size();
            if (n == 1) sawOne = true;
            if (n == 2) sawTwo = true;
            if (sawOne && sawTwo) break;
        }
        assertTrue(sawOne && sawTwo, "应观察到只中一条与两条的情况");
    }

    @Test
    @DisplayName("amount-min > amount-max 时自动交换，不会产出负数或异常")
    void swapsInvertedAmountRange() {
        LootService s = service("""
                tables:
                  t:
                    entries:
                      - item: DIAMOND
                        amount-min: 5
                        amount-max: 2
                    """);
        var t = s.table("t");
        var e = t.entries().get(0);
        assertTrue(e.amountMin() <= e.amountMax(), "区间应被修正");
        for (int i = 0; i < 20; i++) {
            assertTrue(s.rollPlan(s.table("t"), 0, new Random(i)).get(0).amount() >= 2);
        }
    }

    @Test
    @DisplayName("缺少 item 的条目被跳过并告警，其余条目仍生效")
    void skipsEntryWithoutItem() {
        LootService s = service("""
                tables:
                  t:
                    entries:
                      - chance: 1.0
                      - item: DIAMOND
                        chance: 1.0
                    """);
        assertEquals(1, s.table("t").entries().size());
        assertEquals(1, s.warnings().size());
        assertTrue(s.warnings().get(0).contains("缺少 item"));
    }

    @Test
    @DisplayName("空表与未知表名安全返回空，不抛异常")
    void toleratesEmptyAndUnknownTables() {
        LootService s = service("""
                tables:
                  empty:
                    entries: []
                """);
        assertTrue(s.rollPlan("empty", 0).isEmpty());
        assertTrue(s.rollPlan("nope", 0).isEmpty());
        assertTrue(s.rollPlan(null, 0).isEmpty());
    }

    @Test
    @DisplayName("null 节安全返回，不抛异常")
    void toleratesNullSection() {
        LootService s = new LootService(null);
        s.load(null);
        assertEquals(0, s.size());
        assertTrue(s.warnings().isEmpty());
    }

    @Test
    @DisplayName("nested drop tables expand into concrete hits and reject cycles")
    void expandsNestedTablesWithoutRecursionLoop() {
        LootService s = service("""
                tables:
                  root:
                    entries:
                      - table: common
                  common:
                    entries:
                      - item: DIAMOND
                        amount-min: 2
                        amount-max: 2
                      - table: root
                """);

        var hits = s.rollPlan("root", 0);
        assertEquals(1, hits.size());
        assertEquals("DIAMOND", hits.get(0).entry().itemId());
        assertEquals(2, hits.get(0).amount());
    }

    @Test
    void reportsUnresolvedAndUnsupportedNestedTableBehaviorOnLoad() {
        LootService s = service("""
                tables:
                  parent:
                    entries:
                      - {table: child, amount-min: 2, amount-max: 3}
                      - {table: absent}
                  child:
                    entries:
                      - {item: exp, amount-min: 5, amount-max: 5}
                      - {item: DIAMOND}
                """);

        assertTrue(s.warnings().stream().anyMatch(w -> w.contains("parent") && w.contains("absent")));
        assertTrue(s.warnings().stream().anyMatch(w -> w.contains("child") && w.contains("数量范围")));
        assertTrue(s.warnings().stream().anyMatch(w -> w.contains("child") && w.contains("经验")));
        assertEquals(0, s.experience("parent", new Random(1)));
        assertEquals(5, s.experience("child", new Random(1)));
        assertEquals(1, s.rollPlan("parent", 0).size());
    }

    @Test
    @DisplayName("drop entry conditions are evaluated before rolling")
    void filtersEntriesByInjectedConditionEvaluator() {
        LootService s = service("""
                tables:
                  t:
                    entries:
                      - item: DIAMOND
                        conditions:
                          - permission loot.vip
                      - item: BREAD
                """);
        s.setConditionEvaluator((condition, location, killer) -> "permission loot.vip".equals(condition));

        var hits = s.rollPlan(s.table("t"), 0, new Random(3));
        assertEquals(List.of("DIAMOND", "BREAD"),
                hits.stream().map(hit -> hit.entry().itemId()).toList());

        s.setConditionEvaluator((condition, location, killer) -> false);
        hits = s.rollPlan(s.table("t"), 0, new Random(3));
        assertEquals(List.of("BREAD"), hits.stream().map(hit -> hit.entry().itemId()).toList());
    }

    @Test
    @DisplayName("table experience supports fixed and ranged values")
    void rollsTableExperience() {
        LootService fixed = service("""
                tables:
                  t:
                    experience: 12
                    entries: []
                """);
        assertEquals(12, fixed.experience("t", new Random(1)));

        LootService ranged = service("""
                tables:
                  t:
                    experience-min: 4
                    experience-max: 8
                    entries: []
                """);
        for (int i = 0; i < 20; i++) {
            assertTrue(ranged.experience("t", new Random(i)) >= 4
                    && ranged.experience("t", new Random(i)) <= 8);
        }
    }

    @Test
    @DisplayName("dynamic chance requires an evaluator and uses its resolved probability")
    void evaluatesDynamicChanceExplicitly() {
        LootService s = service("""
                tables:
                  t:
                    entries:
                      - item: DIAMOND
                        dynamic-chance: luck-based
                """);
        assertTrue(s.rollPlan(s.table("t"), 0, new Random(1)).isEmpty());
        s.setDynamicChanceEvaluator((expression, location, killer, luck) ->
                "luck-based".equals(expression) ? 1.0 : 0.0);
        assertEquals(1, s.rollPlan(s.table("t"), 0, new Random(1)).size());
    }

    @Test
    void invalidDynamicExpressionsFailClosedWhenLoadingYaml(@org.junit.jupiter.api.io.TempDir java.nio.file.Path root)
            throws Exception {
        var file = root.resolve("loot.yml");
        java.nio.file.Files.writeString(file, """
                defaults: &defaults {dynamic-chance: null}
                tables:
                  list:
                    entries:
                      - {item: DIAMOND, dynamic-chance: null}
                      - {item: EMERALD, chance-expression: ''}
                      - {item: GOLD_INGOT, dynamic-chance: 7}
                      - {item: IRON_INGOT, dynamic-chance: valid, chance-expression: valid}
                      - {item: BREAD, dynamic-chance: valid}
                  indexed:
                    entries:
                      0: {<<: *defaults, item: DIAMOND}
                      1: {<<: *defaults, item: BREAD, dynamic-chance: valid}
                """);
        LootService s = new LootService(null);
        s.loadFile(file);
        s.setDynamicChanceEvaluator((expression, location, killer, luck) -> 1);
        for (String name : List.of("list", "indexed")) {
            var hits = s.rollPlan(s.table(name), 0, new Random(1));
            assertEquals(1, hits.size(), name);
            assertEquals("BREAD", hits.get(0).entry().itemId());
        }
        assertEquals(5, s.warnings().stream().filter(w -> w.contains("dynamic chance expression")).count());
    }

    @Test
    void weightedDynamicChancesDoNotOverflowTheirSum() {
        LootService s = service("""
                tables:
                  t:
                    total-items: 1
                    entries:
                      - {item: DIAMOND, dynamic-chance: large}
                      - {item: BREAD, dynamic-chance: large}
                """);
        s.setDynamicChanceEvaluator((expression, location, killer, luck) -> 1.0e308);
        Random random = new Random(13);
        int diamonds = 0;
        for (int i = 0; i < 1000; i++) {
            var hits = s.rollPlan(s.table("t"), 0, random);
            assertEquals(1, hits.size());
            if (hits.get(0).entry().itemId().equals("DIAMOND")) diamonds++;
        }
        assertTrue(diamonds > 400 && diamonds < 600, "equal weights should select both entries");
    }

    @Test
    void tableExperienceSupportsFullNonnegativeIntegerRange() {
        LootService s = service("""
                tables:
                  wide:
                    experience-min: 0
                    experience-max: 2147483647
                  fixed:
                    experience-min: 2147483647
                    experience-max: 2147483647
                """);
        for (int seed = 0; seed < 32; seed++) {
            assertTrue(s.experience("wide", new Random(seed)) >= 0);
        }
        assertEquals(Integer.MAX_VALUE, s.experience("fixed", new Random(1)));
    }

    @Test
    void nonFiniteDynamicChanceRejectsItemsAndExperience() {
        LootService s = service("""
                tables:
                  ordinary:
                    entries:
                      - item: DIAMOND
                        dynamic-chance: invalid
                      - item: exp
                        amount-min: 5
                        amount-max: 5
                        dynamic-chance: invalid
                  weighted:
                    total-items: 1
                    entries:
                      - item: DIAMOND
                        dynamic-chance: invalid
                """);
        for (double value : new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            s.setDynamicChanceEvaluator((expression, location, killer, luck) -> value);
            assertTrue(s.rollPlan(s.table("ordinary"), 0, new Random(1)).isEmpty());
            assertTrue(s.rollPlan(s.table("weighted"), 0, new Random(1)).isEmpty());
            assertEquals(0, s.experience("ordinary", new Random(1)));
        }
    }

    @Test
    @DisplayName("表名大小写不敏感")
    void tableNamesAreCaseInsensitive() {
        LootService s = service("""
                tables:
                  BossLoot:
                    entries:
                      - item: DIAMOND
                """);
        assertNotNull(s.table("bossloot"));
        assertNotNull(s.table("BOSSLOOT"));
        assertEquals("bossloot", s.table("BossLoot").name());
    }

    @Test
    @DisplayName("附魔/名称/自定义模型数据被解析出来")
    void parsesEntryMetadata() {
        LootService s = service("""
                tables:
                  t:
                    entries:
                      - item: DIAMOND_SWORD
                        name: "§b测试剑"
                        custom-model-data: 1001
                        glow: true
                        enchantments:
                          SHARPNESS: 3
                          looting: 2
                """);
        var e = s.table("t").entries().get(0);
        assertEquals("§b测试剑", e.displayName());
        assertEquals(1001, e.customModelData());
        assertTrue(e.glow());
        assertEquals(3, e.enchantments().get("SHARPNESS"));
        assertEquals(2, e.enchantments().get("LOOTING"), "附魔名应统一大写");
    }

    @Test
    @DisplayName("luck 负值被当作 0，不降低概率")
    void negativeLuckIsClamped() {
        LootService s = service("""
                tables:
                  t:
                    entries:
                      - item: DIAMOND
                        chance: 1.0
                """);
        assertFalse(s.rollPlan("t", -5.0).isEmpty());
    }

    @Test
    @DisplayName("单次堆叠不会超过原版上限逻辑：数量由配置决定")
    void respectsConfiguredAmount() {
        LootService s = service("""
                tables:
                  t:
                    entries:
                      - item: DIAMOND
                        amount-min: 64
                        amount-max: 64
                """);
        for (int i = 0; i < 10; i++) {
            assertEquals(64, s.rollPlan(s.table("t"), 0, new Random(i)).get(0).amount());
        }
    }

    @Test
    @DisplayName("tableNames 反映已装载的表")
    void listsLoadedTables() {
        LootService s = service("""
                tables:
                  a:
                    entries:
                      - item: BREAD
                  b:
                    entries:
                      - item: STONE
                """);
        assertEquals(2, s.size());
        assertTrue(s.tableNames().containsAll(List.of("a", "b")));
    }

    // ---- DropTable generated-line count ----

    @Test
    @DisplayName("total-items fixes generated lines, independent of stack amount")
    void totalItemsCountsDropLines() {
        LootService s = service("""
                tables:
                  t:
                    total-items: 3
                    entries:
                      - item: DIAMOND
                        amount-min: 5
                        amount-max: 5
                """);
        assertEquals(3, s.table("t").totalItems());
        assertEquals(3, s.table("t").maxItems());
        assertEquals(0, s.table("t").minItems());
        var hits = s.rollPlan(s.table("t"), 0, new Random(1));
        assertEquals(3, hits.size());
        assertTrue(hits.stream().allMatch(hit -> hit.amount() == 5));
    }

    @Test
    @DisplayName("total-items uses entry chances as relative weights")
    void totalItemsUsesWeightedSelection() {
        LootService s = service("""
                tables:
                  t:
                    total-items: 1
                    entries:
                      - item: DIAMOND
                        chance: 0.25
                        luck-scaling: false
                      - item: EMERALD
                        chance: 0.75
                        luck-scaling: false
                """);
        int diamonds = 0;
        int emeralds = 0;
        Random random = new Random(20261102L);
        for (int i = 0; i < 20000; i++) {
            var hits = s.rollPlan(s.table("t"), 0, random);
            assertEquals(1, hits.size());
            if (hits.get(0).entry().itemId().equals("DIAMOND")) diamonds++;
            if (hits.get(0).entry().itemId().equals("EMERALD")) emeralds++;
        }
        assertTrue(diamonds > 4300 && diamonds < 5700, "expected about 25%, got " + diamonds);
        assertTrue(emeralds > 14300 && emeralds < 15700, "expected about 75%, got " + emeralds);
    }

    @Test
    @DisplayName("max-items stops ordered independent rolls after the line limit")
    void maxItemsStopsAtGeneratedLineLimit() {
        LootService s = service("""
                tables:
                  t:
                    max-items: 2
                    entries:
                      - item: DIAMOND
                        chance: 1
                      - item: EMERALD
                        chance: 1
                      - item: GOLD_INGOT
                        chance: 1
                """);
        var hits = s.rollPlan(s.table("t"), 0, new Random(1));
        assertEquals(List.of("DIAMOND", "EMERALD"),
                hits.stream().map(hit -> hit.entry().itemId()).toList());
    }

    @Test
    @DisplayName("max-items caps lines expanded from a nested table")
    void maxItemsCapsNestedTableExpansion() {
        LootService s = service("""
                tables:
                  parent:
                    max-items: 1
                    entries:
                      - {table: child, chance: 1}
                  child:
                    entries:
                      - {item: DIAMOND, chance: 1}
                      - {item: EMERALD, chance: 1}
                """);
        var hits = s.rollPlan(s.table("parent"), 0, new Random(1));
        assertEquals(1, hits.size());
        assertTrue(List.of("DIAMOND", "EMERALD").contains(hits.get(0).entry().itemId()));
    }

    @Test
    @DisplayName("min-items alone walks entries until the minimum line count")
    void minItemsStopsAtMinimumGeneratedLineCount() {
        LootService s = service("""
                tables:
                  t:
                    min-items: 2
                    entries:
                      - {item: DIAMOND, chance: 1}
                      - {item: EMERALD, chance: 1}
                      - {item: GOLD_INGOT, chance: 1}
                """);
        var hits = s.rollPlan(s.table("t"), 0, new Random(1));
        assertEquals(List.of("DIAMOND", "EMERALD"),
                hits.stream().map(hit -> hit.entry().itemId()).toList());
    }

    @Test
    @DisplayName("both bounds select a weighted line count within the inclusive range")
    void boundedWeightedLineCountIsInclusive() {
        LootService s = service("""
                tables:
                  t:
                    min-items: 2
                    max-items: 4
                    entries:
                      - {item: DIAMOND, chance: 1}
                """);
        Random random = new Random(42);
        boolean sawTwo = false;
        boolean sawFour = false;
        for (int i = 0; i < 200; i++) {
            int count = s.rollPlan(s.table("t"), 0, random).size();
            assertTrue(count >= 2 && count <= 4);
            sawTwo |= count == 2;
            sawFour |= count == 4;
        }
        assertTrue(sawTwo && sawFour);
    }

    @Test
    @DisplayName("bonus-level-items adds level-scaled generated lines to total-items")
    void bonusLevelItemsAddsLinesToFixedCount() {
        LootService s = service("""
                tables:
                  t:
                    total-items: 2
                    bonus-level-items: 0.5
                    entries:
                      - {item: DIAMOND, chance: 1}
                      - {item: EMERALD, chance: 1}
                """);
        assertEquals(2, s.rollPlan(s.table("t"), 0, new Random(1), null, null, 0).size());
        assertEquals(4, s.rollPlan(s.table("t"), 0, new Random(1), null, null, 4).size());
    }

    @Test
    @DisplayName("bonus-level-items increases the ordered minimum line target")
    void rangedBonusLevelItemsStaysWithinComputedBounds() {
        LootService s = service("""
                tables:
                  t:
                    min-items: 2
                    bonus-level-items: 0to1
                    entries:
                      - {item: DIAMOND, chance: 1}
                      - {item: EMERALD, chance: 1}
                      - {item: IRON_INGOT, chance: 1}
                      - {item: GOLD_INGOT, chance: 1}
                """);
        Random random = new Random(20261102L);
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        for (int i = 0; i < 500; i++) {
            int count = s.rollPlan(s.table("t"), 0, random, null, null, 3).size();
            min = Math.min(min, count);
            max = Math.max(max, count);
        }
        assertTrue(min >= 2);
        assertTrue(max <= 5);
        assertTrue(max > 2, "positive sampled multipliers should add generated lines");
    }

    @Test
    @DisplayName("bonus item options enable weighted selection with a single maximum bound")
    void bonusItemsEnableWeightedSelectionWithSingleMaximumBound() {
        LootService s = service("""
                tables:
                  t:
                    max-items: 2
                    bonus-level-items: 0.5
                    entries:
                      - {item: DIAMOND, chance: 0}
                      - {item: EMERALD, chance: 1}
                """);
        var hits = s.rollPlan(s.table("t"), 0, new Random(1), null, null, 4);
        assertEquals(4, hits.size());
        assertEquals(List.of("EMERALD", "EMERALD", "EMERALD", "EMERALD"),
                hits.stream().map(hit -> hit.entry().itemId()).toList());
    }

    @Test
    @DisplayName("bonus-luck-items adds luck-scaled weighted lines and supports negative luck")
    void bonusLuckItemsUsesKillerLuck() {
        LootService s = service("""
                tables:
                  t:
                    total-items: 2
                    bonus-luck-items: 1
                    entries:
                      - {item: DIAMOND, chance: 1}
                      - {item: EMERALD, chance: 1}
                """);
        assertEquals(5, s.rollPlan(s.table("t"), 3, new Random(1)).size());
        assertEquals(1, s.rollPlan(s.table("t"), -1, new Random(1)).size());
        assertEquals(2, s.rollPlan(s.table("t"), 0, new Random(1)).size());
    }

    @Test
    @DisplayName("fractional bonus multipliers add an extra line probabilistically")
    void fractionalBonusItemsUseFractionalChance() {
        LootService s = service("""
                tables:
                  t:
                    total-items: 2
                    bonus-level-items: 0.5
                    entries:
                      - {item: DIAMOND, chance: 1}
                """);
        Random random = new Random(20261102L);
        int bonusRolls = 0;
        int samples = 1000;
        for (int i = 0; i < samples; i++) {
            int count = s.rollPlan(s.table("t"), 0, random, null, null, 1).size();
            assertTrue(count == 2 || count == 3);
            if (count == 3) bonusRolls++;
        }
        assertTrue(bonusRolls > 400 && bonusRolls < 600,
                "0.5 bonus per level should add one line on approximately half the rolls");
    }

    @Test
    @DisplayName("bonus-luck-items accepts a multiplier range with a single maximum bound")
    void rangedBonusLuckItemsSupportsSingleMaximumBound() {
        LootService s = service("""
                tables:
                  t:
                    max-items: 2
                    bonus-luck-items: 0to1
                    entries:
                      - {item: DIAMOND, chance: 1}
                      - {item: EMERALD, chance: 1}
                      - {item: IRON_INGOT, chance: 1}
                      - {item: GOLD_INGOT, chance: 1}
                      - {item: COAL, chance: 1}
                """);
        Random random = new Random(20261102L);
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        for (int i = 0; i < 500; i++) {
            int count = s.rollPlan(s.table("t"), 3, random).size();
            min = Math.min(min, count);
            max = Math.max(max, count);
        }
        assertTrue(min >= 2);
        assertTrue(max <= 5);
        assertTrue(max > 2);
    }

    @Test
    @DisplayName("bonus-luck-items without bounds or with a reversed range fails closed")
    void invalidBonusLuckItemsDoesNotGenerateExtraLines() {
        LootService unbounded = service("""
                tables:
                  t:
                    bonus-luck-items: 1
                    entries:
                      - {item: DIAMOND, chance: 1}
                """);
        assertEquals(1, unbounded.rollPlan(unbounded.table("t"), 10, new Random(1)).size());
        assertTrue(unbounded.warnings().stream().anyMatch(w -> w.contains("BonusLuckItems")));

        LootService invalidRange = service("""
                tables:
                  t:
                    total-items: 2
                    bonus-luck-items: 2to1
                    entries:
                      - {item: DIAMOND, chance: 1}
                """);
        assertEquals(2, invalidRange.rollPlan(invalidRange.table("t"), 10, new Random(1)).size());
        assertTrue(invalidRange.warnings().stream().anyMatch(w -> w.contains("bonus-luck-items")));
    }

    @Test
    @DisplayName("official PascalCase DropTable option names are accepted")
    void officialDropTableOptionNamesAreAccepted() {
        LootService s = service("""
                tables:
                  t:
                    TotalItems: 2
                    BonusLevelItems: 1
                    BonusLuckItems: 0.5
                    entries:
                      - {item: DIAMOND, chance: 1}
                """);
        assertEquals(2, s.table("t").totalItems());
        int count = s.rollPlan(s.table("t"), 3, new Random(1), null, null, 2).size();
        assertTrue(count == 5 || count == 6,
                "fractional combined bonus adds an extra line probabilistically");
    }

    @Test
    @DisplayName("bonus-level-items is ignored without bounded generated-line count")
    void bonusLevelItemsRequiresLineBounds() {
        LootService s = service("""
                tables:
                  t:
                    bonus-level-items: 1
                    entries:
                      - {item: DIAMOND, chance: 1}
                """);
        assertFalse(s.table("t").hasBonusLevelItems());
        assertEquals(1, s.rollPlan(s.table("t"), 0, new Random(1), null, null, 10).size());
        assertTrue(s.warnings().stream().anyMatch(w -> w.contains("BonusLevelItems")));
    }

    @Test
    @DisplayName("invalid bonus-level-items range fails closed and warns")
    void invalidBonusLevelItemsFailsClosed() {
        LootService s = service("""
                tables:
                  t:
                    total-items: 2
                    bonus-level-items: 2to1
                    entries:
                      - {item: DIAMOND, chance: 1}
                """);
        assertFalse(s.table("t").hasBonusLevelItems());
        assertEquals(2, s.rollPlan(s.table("t"), 0, new Random(1), null, null, 10).size());
        assertTrue(s.warnings().stream().anyMatch(w -> w.contains("bonus-level-items")));
    }

    @Test
    @DisplayName("negative line bounds fail open to the unbounded legacy mode and warn")
    void negativeItemBoundsAreIgnored() {
        LootService s = service("""
                tables:
                  t:
                    min-items: -3
                    entries:
                      - item: DIAMOND
                """);
        assertEquals(0, s.table("t").minItems());
        assertTrue(s.warnings().stream().anyMatch(w -> w.contains("为负数")));
    }
}
