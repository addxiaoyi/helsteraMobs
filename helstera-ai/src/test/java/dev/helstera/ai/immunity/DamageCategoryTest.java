package dev.helstera.ai.immunity;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 伤害类别表测试。
 *
 * <p><b>这个测试存在的理由</b>：{@link DamageCategory#KNOWN_CAUSES} 是一份手抄的
 * Bukkit 常量清单，而类别成员表也必须覆盖到每一个 cause。两处任何一处漏写，
 * 症状都是<b>「配了免疫但那条伤害照样打进来」</b>——服务端不报错、不抛异常、
 * 其它测试全绿，只有玩家会发现。</p>
 *
 * <p>所以这里用反射对着真实的 {@code EntityDamageEvent.DamageCause} 枚举做差集断言，
 * 而不是让测试复用同一份硬编码列表（那样等于自己验自己，漏写了照样全绿）。</p>
 */
class DamageCategoryTest {

    /** 取真实的 Bukkit cause 常量名；类不可用时返回 null。 */
    private static Set<String> realBukkitCauses() {
        try {
            Class<?> c = Class.forName("org.bukkit.event.entity.EntityDamageEvent$DamageCause");
            Object[] constants = c.getEnumConstants();
            if (constants == null) return null;
            Set<String> out = new LinkedHashSet<>();
            for (Object o : constants) out.add(((Enum<?>) o).name());
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    @Test
    @DisplayName("KNOWN_CAUSES 与 Bukkit 真实 DamageCause 常量完全一致")
    void knownCausesMatchesBukkit() {
        Set<String> real = realBukkitCauses();
        Assumptions.assumeTrue(real != null, "paper-api 不在测试类路径上，跳过对账");
        Set<String> missing = new LinkedHashSet<>(real);
        missing.removeAll(DamageCategory.KNOWN_CAUSES);
        Set<String> extra = new LinkedHashSet<>(DamageCategory.KNOWN_CAUSES);
        extra.removeAll(real);

        assertTrue(missing.isEmpty(),
                "Bukkit 有而本表没有的 cause（该伤害将无法被任何规则匹配）: " + missing);
        assertTrue(extra.isEmpty(),
                "本表有而 Bukkit 没有的 cause（抄错了常量名，装载期会误判为未知名）: " + extra);
    }

    @Test
    @DisplayName("每个 cause 都至少归属一个类别，否则它永远匹配不到类别规则")
    void everyCauseBelongsToAtLeastOneCategory() {
        Set<String> real = realBukkitCauses();
        Assumptions.assumeTrue(real != null, "paper-api 不在测试类路径上，跳过");
        for (String cause : real) {
            assertFalse(DamageCategory.categoriesOf(cause).isEmpty(),
                    cause + " 不属于任何类别：配 environment 之类的类别规则时它不会被命中，"
                            + "而现象与「没配」完全一致");
        }
    }

    @Test
    @DisplayName("既是 cause 名又是类别名的键，两种解释都指向同一条规则")
    void ambiguousKeysResolveToSameRule() {
        // fire / void / poison 同时是 cause 名与类别名。这类键若只注册一种解释，
        // 另一半就永远匹配不到：写 fire 却只挡 FIRE、FIRE_TICK 与 LAVA 照样打进来，
        // 而现象与「根本没配」完全一致，无从区分。
        var t = ImmunityService.compile(new ImmunityService.Config(java.util.List.of(
                ImmunityService.Row.immune("fire"))));
        var rule = t.ruleFor("FIRE");
        assertNotNull(rule, "精确 FIRE 必须命中");
        assertEquals(rule, t.ruleFor("FIRE_TICK"),
                "fire 既是 cause 也是类别，FIRE_TICK 应命中同一条规则而不是落空");
        assertEquals(rule, t.ruleFor("LAVA"), "LAVA 同理应命中同一条规则");
        assertEquals(rule, t.ruleFor("MELTING"));
        assertEquals(1, t.size(), "双解释不该产生两条规则（否则倍率叠乘）");
    }

    @Test
    @DisplayName("既是 cause 名又是类别名的键，与另一个类别的优先级仍按精确优先")
    void ambiguousKeyStillLosesToRealExactCause() {
        var t = ImmunityService.compile(new ImmunityService.Config(java.util.List.of(
                ImmunityService.Row.of("environment", 0.1),
                ImmunityService.Row.of("FIRE_TICK", 0.5))));
        assertEquals(5.0, t.evaluate("FIRE_TICK", 10.0).damage(), 1e-9,
                "FIRE_TICK 有精确规则，应压过 environment 类别");
        assertEquals(1.0, t.evaluate("LAVA", 10.0).damage(), 1e-9,
                "LAVA 无精确规则，应落到 environment");
    }

    @Test
    @DisplayName("归一化：大小写与 -_ 空格 . ~ 前缀都不敏感")
    void normalization() {
        assertEquals("entityattack", DamageCategory.normalize("ENTITY_ATTACK"));
        assertEquals("entityattack", DamageCategory.normalize("entity-attack"));
        assertEquals("entityattack", DamageCategory.normalize("EntityAttack"));
        assertEquals("fire", DamageCategory.normalize("~FIRE."));
        assertEquals("", DamageCategory.normalize(null));
        assertEquals("", DamageCategory.normalize(""));
    }

    @Test
    @DisplayName("of 同时接受连字符名与枚举名简写")
    void categoryLookup() {
        assertEquals(DamageCategory.MELEE, DamageCategory.of("entity-attack"));
        assertEquals(DamageCategory.MELEE, DamageCategory.of("ENTITY_ATTACK"));
        assertEquals(DamageCategory.MELEE, DamageCategory.of("MELEE"));
        assertEquals(DamageCategory.MELEE, DamageCategory.of("melee"));
        assertEquals(DamageCategory.ENVIRONMENT, DamageCategory.of("environment"));
        assertNull(DamageCategory.of("not-a-category"));
        assertNull(DamageCategory.of(null));
        assertNull(DamageCategory.of("  "));
    }

    @Test
    @DisplayName("FIRE 既是 cause 名也是类别名：都能被识别")
    void ambiguousFireIsResolvable() {
        assertTrue(DamageCategory.isKnownCause("FIRE"));
        assertTrue(DamageCategory.isKnownCause("fire"));
        assertNotNull(DamageCategory.of("fire"));
        assertEquals("FIRE", DamageCategory.canonicalCause("fire"),
                "cause 键必须能还原成 Bukkit 常量名，否则诊断命令展示的名字是错的");
    }

    @Test
    @DisplayName("categoriesOf 按声明顺序返回（ENVIRONMENT 之后仍有专属类别）")
    void categoriesOfIsDeterministic() {
        var first = DamageCategory.categoriesOf("LAVA");
        var second = DamageCategory.categoriesOf("LAVA");
        assertEquals(first, second, "同样输入必须给出同样顺序，否则「先写先赢」没有意义");
        assertTrue(first.contains(DamageCategory.FIRE), "LAVA 属 fire");
        assertTrue(first.contains(DamageCategory.ENVIRONMENT), "LAVA 也属 environment");
        assertEquals(DamageCategory.FIRE, first.get(0), "FIRE 声明在前，应排在前面");
    }

    @Test
    @DisplayName("纯类别名（environment / entity-attack 的类别语义）不被当成 cause")
    void knownCauseRejectsCategoryNames() {
        assertTrue(DamageCategory.isKnownCause("FIRE"));
        // 归一化会让 entity-attack 变成 entityattack，正是真 cause ENTITY_ATTACK 的形态，
        // 所以它同时是 cause 名与类别名（这正是 ambiguousKey* 那两个用例覆盖的情况）
        assertTrue(DamageCategory.isKnownCause("entity-attack"),
                "entity-attack 归一化后等于真 cause ENTITY_ATTACK");
        assertEquals("ENTITY_ATTACK", DamageCategory.canonicalCause("entity-attack"));
        // environment 没有任何同名的 cause：只认它为类别
        assertFalse(DamageCategory.isKnownCause("environment"),
                "environment 不是 cause；若被当成 cause，装载期就不会对拼错的类别名告警");
        assertNull(DamageCategory.canonicalCause("environment"));
        assertFalse(DamageCategory.isKnownCause(null));
    }
}