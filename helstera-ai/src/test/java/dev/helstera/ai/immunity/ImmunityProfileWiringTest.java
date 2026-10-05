package dev.helstera.ai.immunity;

import dev.helstera.ai.AiProfile;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.StringReader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 档案级 {@code immunities} / {@code damage-modifiers} 的接线测试。
 *
 * <p>验的是「YAML 写得进、规则真生效」这条完整链路。纯判定逻辑由
 * {@link ImmunityServiceTest} 覆盖，但配置解析那一步曾经是整个功能里
 * 最容易「写了没反应」的一环——写进 mobs/*.yml 却不生效时，
 * 服务端不会有任何异常。</p>
 */
class ImmunityProfileWiringTest {

    private static AiProfile profile(String yml) {
        return AiProfile.fromSection("p",
                YamlConfiguration.loadConfiguration(new StringReader(yml)));
    }

    @Test
    @DisplayName("档案级 immunities 生效")
    void profileImmunitiesParsed() {
        AiProfile p = profile("""
                immunities: [FIRE, LAVA, DROWNING]
                """);
        var t = p.immunityTable();
        assertEquals(3, t.size(), "档案里写的三条免疫都该被读到，实际: " + t.rules());
        assertEquals(0.0, t.evaluate("FIRE", 10.0).damage(), 1e-9);
        assertEquals(0.0, t.evaluate("LAVA", 10.0).damage(), 1e-9);
        assertEquals(0.0, t.evaluate("SUFFOCATION", 10.0).damage(), 1e-9,
                "DROWNING 类别含 SUFFOCATION/CRAMMING");
    }

    @Test
    @DisplayName("damage-modifiers 接受裸数字")
    void bareNumberModifier() {
        AiProfile p = profile("""
                damage-modifiers:
                  fire: 0.5
                  entity-attack: 0.25
                """);
        var t = p.immunityTable();
        assertEquals(5.0, t.evaluate("FIRE", 10.0).damage(), 1e-9);
        assertEquals(2.5, t.evaluate("ENTITY_ATTACK", 10.0).damage(), 1e-9);
    }

    @Test
    @DisplayName("damage-modifiers 接受字符串数字")
    void stringNumberModifier() {
        AiProfile p = profile("""
                damage-modifiers:
                  fire: "0.5"
                """);
        assertEquals(5.0, p.immunityTable().evaluate("FIRE", 10.0).damage(), 1e-9);
    }

    @Test
    @DisplayName("damage-modifiers 接受 multiplier / immune 子节")
    void sectionModifier() {
        AiProfile p = profile("""
                damage-modifiers:
                  PROJECTILE:
                    multiplier: 0.1
                  MAGIC:
                    immune: true
                """);
        var t = p.immunityTable();
        assertEquals(1.0, t.evaluate("PROJECTILE", 10.0).damage(), 1e-9);
        assertEquals(0.0, t.evaluate("MAGIC", 10.0).damage(), 1e-9);
    }

    @Test
    @DisplayName("负倍率经 YAML 解析后仍是负值（回血链路不能被解析吃掉）")
    void negativeModifierSurvivesYaml() {
        AiProfile p = profile("""
                damage-modifiers:
                  entity-attack: -1.0
                """);
        assertEquals(-10.0, p.immunityTable().evaluate("ENTITY_ATTACK", 10.0).damage(), 1e-9);
    }

    @Test
    @DisplayName("未知名在档案级也告警，可经 /helstera check 暴露")
    void unknownNameWarnsAtProfileLevel() {
        AiProfile p = profile("""
                immunities: [FIRE_TYPO]
                """);
        assertTrue(p.immunityWarnings().stream().anyMatch(w -> w.contains("FIRE_TYPO")),
                "实际: " + p.immunityWarnings());
        assertTrue(p.immunityTable().isEmpty());
    }

    @Test
    @DisplayName("mobs/*.yml 的生物级覆盖整体替换档案级免疫")
    void mobOverrideReplacesProfileImmunity() {
        AiProfile base = profile("""
                immunities: [FIRE, LAVA]
                damage-modifiers:
                  fire: 0.5
                """);
        base.applyOverridesFrom(YamlConfiguration.loadConfiguration(new StringReader("""
                immunities: [POISON]
                """)));
        var t = base.immunityTable();
        assertEquals(1, t.size(), "生物级写了 immunities 就该整体替换档案级，实际: " + t.rules());
        assertTrue(t.evaluate("POISON", 10.0).matched());
        // 档案级的 LAVA 不该残留——免疫漏配的表现是「怪物打不动」，
        // 而合并语义会让作者写一条就悄悄继承档案的全部免疫
        assertFalse(t.evaluate("LAVA", 10.0).matched(),
                "生物级只写 POISON 时，档案级的 LAVA 免疫不该残留");
    }

    @Test
    @DisplayName("生物级写了 damage-modifiers 就替换档案级 immunities")
    void mobOverrideReplacesImmunitiesWhenOnlyModifiersWritten() {
        AiProfile base = profile("immunities: [FIRE, LAVA]");
        base.applyOverridesFrom(YamlConfiguration.loadConfiguration(
                new StringReader("damage-modifiers:\n  poison: 0.5\n")));
        assertEquals(1, base.immunityTable().size());
        assertFalse(base.immunityTable().evaluate("FIRE", 10.0).matched());
    }

    @Test
    @DisplayName("没写免疫的生物级覆盖保留档案级免疫")
    void absentKeysKeepProfileImmunity() {
        AiProfile base = profile("immunities: [FIRE]");
        base.applyOverridesFrom(YamlConfiguration.loadConfiguration(
                new StringReader("attack-damage: 12.0")));
        assertEquals(12.0, base.attackDamage, 1e-9);
        assertTrue(base.immunityTable().evaluate("FIRE", 10.0).matched(),
                "只改攻击力不该把档案的免疫清掉");
    }

    @Test
    @DisplayName("复制构造带免疫（否则 mobs 覆盖副本会丢免疫）")
    void copyConstructorCarriesImmunity() {
        AiProfile base = profile("immunities: [FIRE]\ndamage-modifiers:\n  fall: 0.5\n");
        AiProfile copy = new AiProfile(base);
        assertEquals(2, copy.immunityTable().size(),
                "档案副本必须带上免疫规则，否则 mobs/*.yml 覆盖出的档案会突然不免疫");
        assertTrue(copy.immunityTable().evaluate("FIRE", 10.0).matched());
        assertEquals(5.0, copy.immunityTable().evaluate("FALL", 10.0).damage(), 1e-9);
    }

    @Test
    @DisplayName("覆盖后旧表被失效，不会读到覆盖前的缓存")
    void overrideInvalidatesCachedTable() {
        AiProfile p = profile("immunities: [FIRE]");
        assertTrue(p.immunityTable().evaluate("FIRE", 10.0).matched());
        p.applyOverridesFrom(YamlConfiguration.loadConfiguration(
                new StringReader("immunities: [POISON]\n")));
        assertFalse(p.immunityTable().evaluate("FIRE", 10.0).matched(),
                "缓存的表必须随覆盖失效，否则改了配置却仍按旧免疫生效");
        assertTrue(p.immunityTable().evaluate("POISON", 10.0).matched());
    }

    @Test
    @DisplayName("未配置免疫的档案不改任何伤害")
    void profileWithoutImmunityIsInert() {
        AiProfile p = profile("sight-radius: 16.0");
        assertTrue(p.immunityTable().isEmpty());
        for (String cause : DamageCategory.KNOWN_CAUSES) {
            var r = p.immunityTable().evaluate(cause, 10.0);
            assertFalse(r.matched(), cause + ": 未配置免疫时不应命中任何规则");
            assertEquals(10.0, r.damage(), 1e-9);
        }
    }

    @Test
    @DisplayName("null 节安全返回")
    void nullSectionTolerated() {
        AiProfile p = new AiProfile("x");
        p.applyOverridesFrom(null);
        assertTrue(p.immunityTable().isEmpty());
        assertTrue(p.immunityWarnings().isEmpty());
    }
}