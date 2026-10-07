package dev.helstera.ai.skill;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 触发器名解析测试。
 *
 * <p>迁移中心产出的写法（{@code ~onSpawn}）与手写配置的写法（{@code on-spawn}）
 * 必须在同一个枚举上收敛——两者落到不同分支正是此前「导入了不触发」的根因。</p>
 */
class SkillTriggerTest {

    @Test
    @DisplayName("规范写法原样解析")
    void parsesCanonicalNames() {
        assertSame(SkillTrigger.SPAWN, SkillTrigger.of("on-spawn"));
        assertSame(SkillTrigger.TIMER, SkillTrigger.of("on-timer"));
        assertSame(SkillTrigger.DAMAGE, SkillTrigger.of("on-damage"));
        assertSame(SkillTrigger.DEATH, SkillTrigger.of("on-death"));
        assertSame(SkillTrigger.KILL_PLAYER, SkillTrigger.of("on-kill-player"));
    }

    @Test
    @DisplayName("驼峰 / 下划线 / 波浪号 / 大小写都归一到同一触发器")
    void toleratesSeparatorAndCaseVariants() {
        assertSame(SkillTrigger.SPAWN, SkillTrigger.of("onSpawn"));
        assertSame(SkillTrigger.SPAWN, SkillTrigger.of("ON_SPAWN"));
        assertSame(SkillTrigger.SPAWN, SkillTrigger.of("~onSpawn"));
        assertSame(SkillTrigger.SPAWN, SkillTrigger.of("  on-spawn  "));
        assertSame(SkillTrigger.KILL_PLAYER, SkillTrigger.of("onKillPlayer"));
        assertSame(SkillTrigger.ENTITY_SHOOT, SkillTrigger.of("on_entity_shoot"));
    }

    @Test
    @DisplayName("省略 on 前缀的短名也能解析")
    void parsesShortNames() {
        assertSame(SkillTrigger.SPAWN, SkillTrigger.of("spawn"));
        assertSame(SkillTrigger.TIMER, SkillTrigger.of("timer"));
        assertSame(SkillTrigger.DEATH, SkillTrigger.of("death"));
    }

    @Test
    @DisplayName("未知名与空值返回 null，不抛异常")
    void unknownNamesReturnNull() {
        assertNull(SkillTrigger.of("on-banana"));
        assertNull(SkillTrigger.of(""));
        assertNull(SkillTrigger.of("   "));
        assertNull(SkillTrigger.of(null));
    }

    @Test
    @DisplayName("归一化索引与逐个遍历解析结果一致")
    void indexAgreesWithLinearScan() {
        for (SkillTrigger t : SkillTrigger.values()) {
            SkillTrigger viaIndex = SkillTrigger.byNormalized(SkillTrigger.normalizeName(t.configName()));
            assertSame(t, viaIndex, "索引应能查到 " + t.configName());
        }
    }

    @Test
    @DisplayName("已接线与未接线被区分，未接线的不会被误报为未知名")
    void distinguishesWiredFromDeclared() {
        assertTrue(SkillTrigger.SPAWN.wired());
        assertTrue(SkillTrigger.TIMER.wired());
        assertNotNull(SkillTrigger.of("on-interact"));
        assertTrue(SkillTrigger.INTERACT.wired(),
                "on-interact 已接到 PlayerInteractAtEntityEvent");
        // 仍未接线的：检查命令应提示「暂未接线」而非「未知名」，
        // 让用户知道是功能缺失而不是拼错了名字
        // on-spawn-boss 已接入 ModelSpawnEvent，按档案 boss 字段判定。
        // 「无独立事件来源」不再是无法接线的理由——它与 on-spawn 同源，
        // 区分靠档案字段而非事件类型。
        assertTrue(SkillTrigger.SPAWN_BOSS.wired(), "on-spawn-boss 已接入 ModelSpawnEvent");
        assertNotNull(SkillTrigger.of("on-spawn-boss"));
        // on-entity-shoot 曾被标成 false，理由是「模型实例不会自己发射弹丸」。
        // 但载体的 entity.type 可以是 player —— 这类载体确实能射箭，
        // EntityShootBowEvent 可达，标 false 会让 check 劝退用户不用可用机制。
        assertTrue(SkillTrigger.ENTITY_SHOOT.wired(),
                "on-entity-shoot 可由玩家类型载体射箭触发，标 false 会让 check 谎报");
        assertTrue(SkillTrigger.SUMMON.wired(), "on-summon 已由 ModelSpawnEvent + MinionService 接线");
        assertTrue(SkillTrigger.LEASH.wired(), "on-leash 已由 PlayerLeashEntityEvent 接线");
    }

    @Test
    @DisplayName("派生触发器由采样节拍驱动，标记为已接线")
    void derivedTriggersAreWired() {
        // 这七个没有独立事件来源，但边沿推导已完成（DerivedTriggerLatch）
        // 且采样驱动已挂上（SkillTriggers#pollDerived），因此必须标 true；
        // 漏标会让 /helstera check 报「暂未接线」而实际能触发，误导排查方向。
        for (SkillTrigger t : new SkillTrigger[]{
                SkillTrigger.ENTER_COMBAT, SkillTrigger.LEAVE_COMBAT,
                SkillTrigger.TARGET_CHANGE, SkillTrigger.LOWER_HEALTH,
                SkillTrigger.LOST_TARGET, SkillTrigger.ENTER_WATER,
                SkillTrigger.LEAVE_WATER}) {
            assertTrue(t.wired(), t.configName() + " 已接入采样节拍，不应再算未接线");
        }
    }

    @Test
    @DisplayName("未接线清单不含派生触发器")
    void unwiredNamesExcludeDerivedTriggers() {
        var unwired = SkillTrigger.unwiredNames();
        for (SkillTrigger t : new SkillTrigger[]{
                SkillTrigger.ENTER_COMBAT, SkillTrigger.LEAVE_COMBAT,
                SkillTrigger.TARGET_CHANGE, SkillTrigger.LOWER_HEALTH,
                SkillTrigger.LOST_TARGET, SkillTrigger.ENTER_WATER,
                SkillTrigger.LEAVE_WATER}) {
            assertFalse(unwired.contains(t.configName()),
                    t.configName() + " 已接线，不该出现在未接线清单里");
        }
        // on-pre-target 由 ModelPreTargetEvent 驱动（AiController.attack 前派发）
        assertFalse(unwired.contains("on-pre-target"), "on-pre-target 已接入桥接");
        // on-damage-negation 由 ImmunityListener 在伤害归零时派发
        assertFalse(unwired.contains("on-damage-negation"), "on-damage-negation 已接入桥接");
        // on-attack-hit 由 AnimationMarkerEvent 的 attack_hit 标记驱动，
        // 桥接在 AiManager#start 里，命中才派发（挥空不触发）
        assertFalse(unwired.contains("on-attack-hit"), "on-attack-hit 已接入桥接");
        assertTrue(SkillTrigger.ATTACK_HIT.wired());
    }

    @Test
    @DisplayName("只有 on-timer 需要调度器主动驱动")
    void onlyTimerNeedsTick() {
        for (SkillTrigger t : SkillTrigger.values()) {
            assertEquals(t == SkillTrigger.TIMER, t.needsTick(), t.name());
        }
    }

    @Test
    @DisplayName("shortName 去掉 on 前缀")
    void shortNameDropsPrefix() {
        assertEquals("spawn", SkillTrigger.SPAWN.shortName());
        assertEquals("kill-player", SkillTrigger.KILL_PLAYER.shortName());
    }

    @Test
    @DisplayName("configNames 覆盖全部枚举且无重复")
    void configNamesAreComplete() {
        var names = SkillTrigger.configNames();
        assertEquals(SkillTrigger.values().length, names.size());
        assertEquals(names.size(), new java.util.HashSet<>(names).size(), "不应有重复");
        assertTrue(names.contains("on-timer"));
    }
}