package dev.helstera.ai;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * mobs/*.yml 的 {@code ai} 节覆盖测试。
 *
 * <p>重点是此前只接线了一半的那几项：标量与 {@code triggers} 有覆盖，
 * 而 {@code require} / {@code on-decision} / {@code phases} 三项从未被读取。
 * 这类缺陷在服务端表现为「配置写得越详细越没效果」，且无任何日志痕迹。</p>
 */
class AiProfileOverrideTest {

    private static AiProfile base() {
        var sec = YamlConfiguration.loadConfiguration(new StringReader("""
                sight-radius: 16
                attack-damage: 3.0
                can-flee: true
                require:
                  - has-target
                on-decision:
                  - stop-animation
                triggers:
                  on-spawn:
                    do: [set-scale 1.0]
                phases:
                  - id: p1
                    min: 0
                    max: 100
                """));
        return AiProfile.fromSection("base", sec);
    }

    private static AiProfile overridden(String overrideYml) {
        AiProfile p = base();
        p.applyOverridesFrom(YamlConfiguration.loadConfiguration(new StringReader(overrideYml)));
        return p;
    }

    @Test
    @DisplayName("生物级 phases 生效，不再被静默忽略")
    void phasesAreApplied() {
        AiProfile p = overridden("""
                phases:
                  - id: enraged
                    min: 0
                    max: 30
                    announce: "&c狂暴"
                  - id: calm
                    min: 30
                    max: 100
                """);
        assertEquals(2, p.phases.size(), "mobs/*.yml 里写的 Boss 阶段必须覆盖到档案");
        assertEquals("enraged", BossPhase.resolve(p.phases, 10).id());
        assertEquals("calm", BossPhase.resolve(p.phases, 60).id());
        assertEquals("&c狂暴", BossPhase.resolve(p.phases, 10).announce());
    }

    @Test
    @DisplayName("生物级 require 与 on-decision 生效")
    void requireAndOnDecisionAreApplied() {
        AiProfile p = overridden("""
                require:
                  - health-above 0.8
                on-decision:
                  - particle flame
                """);
        assertEquals(List.of("health-above 0.8"), p.require);
        assertEquals(List.of("particle flame"), p.onDecision);
    }

    @Test
    @DisplayName("只写单条 require 时也应生效，而非退化成空列表")
    void singleStringRequireAccepted() {
        // YAML 里只写一条时会被退化成字符串；只判 isList 会让这一项静默消失
        AiProfile p = overridden("require: health-below 0.4");
        assertEquals(List.of("health-below 0.4"), p.require,
                "写一条与写列表应当等价");
        assertEquals(List.of("stop-animation"), p.onDecision,
                "只覆盖 require 时，on-decision 应保留档案值");
    }

    @Test
    @DisplayName("未写的项保留档案原值，不被清空")
    void absentKeysKeepBaseValues() {
        AiProfile p = overridden("attack-damage: 12.0");
        assertEquals(12.0, p.attackDamage, 1e-9);
        assertEquals(16.0, p.sightRadius, 1e-9, "没写的项应保留档案值");
        assertEquals(List.of("has-target"), p.require);
        assertEquals(1, p.phases.size(), "没写 phases 时应沿用档案的阶段");
    }

    @Test
    @DisplayName("显式写空列表可清空档案的列表项")
    void emptyListClearsBase() {
        // 「整体替换」而非合并，是为了让清空某项能够表达
        AiProfile p = overridden("require: []");
        assertTrue(p.require.isEmpty(), "空列表应清空而非保留档案值");
        assertEquals(1, p.triggers.size(), "只清空 require 不该影响其它项");
    }

    @Test
    @DisplayName("触发器整体替换，不与档案合并")
    void triggersReplaceNotMerge() {
        AiProfile p = overridden("""
                triggers:
                  on-damage:
                    do: [sound ENTITY_LIGHTNING_BOLT_THUNDER]
                """);
        assertEquals(1, p.triggers.size());
        assertTrue(p.triggers.containsKey("on-damage"));
        assertFalse(p.triggers.containsKey("on-spawn"),
                "生物级只写 on-damage 时，档案级的 on-spawn 不应残留");
    }

    @Test
    @DisplayName("标量覆盖后档案副本彼此隔离")
    void overrideDoesNotMutateBase() {
        AiProfile shared = base();
        shared.applyOverridesFrom(YamlConfiguration.loadConfiguration(
                new StringReader("attack-damage: 99.0")));
        assertEquals(99.0, shared.attackDamage, 1e-9);
        // 同一档案被多个生物引用，就地修改会连带改掉别人的行为
        AiProfile other = base();
        assertEquals(3.0, other.attackDamage, 1e-9);
    }

    @Test
    @DisplayName("null 节安全返回")
    void nullSectionTolerated() {
        AiProfile p = base();
        p.applyOverridesFrom(null);
        assertEquals(3.0, p.attackDamage, 1e-9);
    }
}