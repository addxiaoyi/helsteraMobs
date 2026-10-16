package dev.helstera.migration.importer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MM 条件叶子 → helstera 条件名的翻译测试。
 *
 * <p>这张表错了不会编译失败、不会抛异常，只会让免疫规则<b>永远不生效</b>
 * （条件求值 fail-closed）——与「没迁移」对用户完全等价。
 * 所以逐条钉死，不靠抽样。</p>
 */
class MmConditionsTest {

    private static String t(String raw) {
        return MmConditions.translate(raw).condition();
    }

    private static boolean translated(String raw) {
        return MmConditions.translate(raw).translated();
    }

    @Test
    @DisplayName("血量百分比换成 0..1 比值，不换算会让条件恒假")
    void healthPercentIsConverted() {
        // 不换算会得到 health-below 50，而比值上限是 1.0 -> 条件永不成立 -> 规则永不生效
        assertEquals("health-below 0.5", t("?health{<50%}"));
        assertEquals("health-above 0.3", t("?health{>30%}"));
        assertEquals("health-below 0.25", t("?health{<25%}"));
    }

    @Test
    @DisplayName("不带百分号的数值：>1 视为百分数，<=1 视为比值")
    void bareNumbersInferScale() {
        assertEquals("health-below 0.5", t("?health{<0.5}"), "0..1 区间应按比值处理");
        assertEquals("health-below 0.5", t("?health{<50}"), ">1 的裸数按百分数换算");
    }

    @Test
    @DisplayName("比较方向正确，不把 < 翻成 >")
    void directionIsPreserved() {
        assertEquals("health-below 0.2", t("?health{<20%}"));
        assertEquals("health-above 0.8", t("?health{>80%}"));
        assertFalse(t("?health{<20%}").equals(t("?health{>20%}")),
                "方向翻错会让免疫条件恰好作用在反面上，且看起来完全正常");
    }

    @Test
    @DisplayName("距离条件映射到 distance-*，不与血量混用、也不按百分比换算")
    void distanceMapsToDistanceConditions() {
        // 距离是格数不是百分比：<5 必须是 5 格。
        // 误按百分数换算会得到 0.05 格，条件恒真，免疫范围悄悄缩到 1/20 格。
        assertEquals("distance-below 5", t("?targetDistance{<5}"));
        assertEquals("distance-above 8", t("?targetDistance{>8}"));
    }

    @Test
    @DisplayName("无参条件映射到 helstera 的实际注册名")
    void parameterlessConditions() {
        assertEquals("has-target", t("?hasTarget"));
        assertEquals("has-target", t("?has-target"), "下划线/连字符写法都应归一");
        assertEquals("target-is player", t("?targetIsPlayer"));
        assertEquals("target-is minecraft", t("?targetIsMob"));
        assertTrue(translated("?hasTarget"));
    }

    @Test
    void cancelledConditionMapsToEventAwareRuntimeCondition() {
        assertEquals("is-cancelled", t("?isCancelled"));
        assertEquals("is-cancelled", t("?is-canceled"));
        assertTrue(translated("?isCancelled"));
    }

    @Test
    void foodLevelConditionMapsWithItsComparator() {
        assertEquals("food-level <14", t("?foodLevel{<14}"));
        assertEquals("food-level 14", t("?foodLevelIs{14}"));
        assertTrue(translated("?foodLevel{<14}"));
        assertFalse(translated("?foodLevel"));
    }

    @Test
    void airLevelConditionMapsWithItsComparator() {
        assertEquals("air-level <120", t("?airLevel{<120}"));
        assertEquals("air-level 120", t("?remainingAir{120}"));
        assertTrue(translated("?airLevel{<120}"));
        assertFalse(translated("?airLevel"));
    }

    @Test
    void damageCauseConditionMapsEventCause() {
        assertEquals("damage-cause ENTITY_ATTACK", t("?damageCause{ENTITY_ATTACK}"));
        assertTrue(translated("?damageCause{ENTITY_ATTACK}"));
        assertFalse(translated("?damageCause"));
    }

    @Test
    void hasItemConditionMapsItemAndAmountArguments() {
        assertEquals("has-item DIAMOND >=2", t("?hasItem{item=DIAMOND;amount=>=2}"));
        assertTrue(translated("?hasItem{item=DIAMOND;amount=>=2}"));
        assertFalse(translated("?hasItem"));
    }

    @Test
    void vehicleAndMotionConditionsMapToRuntimeConditions() {
        assertEquals("mounted", t("?mounted"));
        assertEquals("mounted", t("?isMounted"));
        assertEquals("moving", t("?isMoving"));
        assertEquals("has-passenger", t("?hasPassenger"));
        assertEquals("has-passenger", t("?hasPassengers"));
        assertEquals("is-leashed", t("?isLeashed"));
        assertEquals("is-leashed", t("?leashed"));
        assertEquals("has-owner", t("?hasOwner"));
        assertEquals("has-parent", t("?hasParent"));
        assertEquals("is-parent", t("?isParent"));
        assertEquals("is-parent", t("?parent"));
        assertEquals("is-child", t("?isChild"));
        assertEquals("is-child", t("?child"));
        assertEquals("is-parent-alive", t("?isParentAlive"));
        assertEquals("is-sibling", t("?isSibling"));
        assertTrue(translated("?hasPassenger"));
    }

    @Test
    void scoreboardTagConditionMapsItsTagAttribute() {
        assertEquals("has-tag tag=BossDefeated", t("?hasTag{t=BossDefeated}"));
        assertEquals("has-tag tag=BossDefeated", t("?hasScoreboardTag{tag=BossDefeated}"));
        assertTrue(translated("?hasTag{t=BossDefeated}"));
        assertFalse(translated("?hasTag"));
    }

    @Test
    @DisplayName("target entity state conditions map to runtime aliases")
    void targetStateConditionsMap() {
        assertEquals("target-is-undead", t("?targetIsUndead"));
        assertEquals("target-is-creature", t("?targetIsCreature"));
        assertEquals("target-is-baby", t("?targetIsChild"));
        assertEquals("target-is-adult", t("?targetIsAdult"));
        assertEquals("target-is-tamed", t("?targetIsTamed"));
        assertEquals("target-is-sheared", t("?targetIsSheared"));
        assertEquals("target-is-saddled", t("?targetIsSaddled"));
        assertEquals("target-is-powered", t("?targetIsCharged"));
        assertEquals("target-is-sneaking", t("?targetIsSneaking"));
        assertEquals("target-is-sprinting", t("?targetIsSprinting"));
        assertEquals("target-is-flying", t("?targetIsFlying"));
        assertEquals("target-is-blocking", t("?targetIsBlocking"));
        assertEquals("target-is-gliding", t("?targetIsGliding"));
        assertEquals("target-is-swimming", t("?targetIsSwimming"));
        assertEquals("target-on-fire", t("?targetOnFire"));
        assertEquals("target-in-air", t("?targetAirborne"));
        assertEquals("target-in-water", t("?targetInWater"));
        assertEquals("target-in-lava", t("?targetInLava"));
        assertEquals("target-is-invisible", t("?targetIsInvisible"));
        assertEquals("target-is-climbing", t("?targetIsClimbing"));
        assertEquals("target-is-blocked", t("?targetIsBlocked"));
        assertEquals("target-dead", t("?targetIsDead"));
        assertEquals("target-on-ground", t("?targetIsOnGround"));
        assertEquals("target-airborne", t("?targetIsAirborne"));
        assertTrue(translated("?targetIsUndead"));
        assertTrue(translated("?targetIsBlocking"));
    }

    @Test
    @DisplayName("比较号带 = 时按同向处理，不改变方向")
    void nonStrictComparisons() {
        assertEquals("health-below 0.5", t("?health{<=50%}"));
        assertEquals("health-above 0.5", t("?health{>=50%}"));
    }

    @Test
    @DisplayName("无等价物的条件原样保留并标记未映射，绝不丢弃")
    void unknownKeptButFlagged() {
        var m = MmConditions.translate("?onGround");
        assertFalse(m.translated(), "?onGround 在 helstera 无等价物，不能假装翻译成功");
        assertEquals("?onGround", m.condition(), "必须原样保留，用户才能看出少了什么");
    }

    @Test
    @DisplayName("空与 null 安全处理")
    void nullAndBlankSafe() {
        assertEquals(null, MmConditions.translate(null).condition());
        assertEquals(null, MmConditions.translate("   ").condition());
        assertFalse(MmConditions.translate(null).translated());
    }

    @Test
    @DisplayName("无法解析的数值按未映射处理，不抛异常")
    void unparseableValueIsNotFatal() {
        assertFalse(translated("?health{<abc}"), "解析失败应退化为未映射，而非抛异常中断整个导入");
        assertFalse(translated("?health{}"), "空参数块不是有效条件");
        assertFalse(translated("?health"), "没有比较块时无法确定语义");
    }

    @Test
    @DisplayName("批量翻译保持顺序，并单独列出未映射项")
    void translateAllSeparatesUnmapped() {
        List<String> out = new ArrayList<>();
        List<String> unmapped = new ArrayList<>();
        MmConditions.translateAll(
                List.of("?hasTarget", "?onGround", "?health{<50%}"), out, unmapped);
        assertEquals(List.of("has-target", "?onGround", "health-below 0.5"), out,
                "顺序必须保持，否则条件语义（AND）虽不变但报告对不上原文件");
        assertEquals(List.of("?onGround"), unmapped, "未映射项需单独收集，供报告告警");
    }

    @Test
    @DisplayName("批量翻译容忍 null 列表")
    void translateAllToleratesNull() {
        List<String> out = new ArrayList<>();
        List<String> unmapped = new ArrayList<>();
        MmConditions.translateAll(null, out, unmapped);
        assertTrue(out.isEmpty());
    }
}
