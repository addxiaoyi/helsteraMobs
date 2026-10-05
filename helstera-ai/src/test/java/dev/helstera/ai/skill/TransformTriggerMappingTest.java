package dev.helstera.ai.skill;

import org.bukkit.event.entity.EntityTransformEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 变形原因 -> 触发器 的映射测试。
 *
 * <p>这段 switch 写反的后果是「剪羊毛触发 on-age」：配置作者在日志里看不到任何
 * 异常，档案里写了 {@code on-age} 的技能会在玩家剪毛时反复触发。
 * 而复现它需要拿剪刀去剪一个模型生物，现场几乎不可能自然撞上——所以必须单测。</p>
 *
 * <p>本测试同时守住一条<b>用编译期事实换来的结论</b>：Paper 的
 * {@code TransformReason} 常量集里没有 {@code AGED}，Bukkit 不提供
 * 「生物成年」事件，因此 {@code on-age} 不能挂在这个事件上，必须保持未接线。
 * 哪天Paper 若补上该常量，这里会先编译失败，提示重新评估。</p>
 */
class TransformTriggerMappingTest {

    @Test
    @DisplayName("剪毛对应 on-shear")
    void shearedMapsToShear() {
        assertSame(SkillTrigger.SHEAR,
                SkillTriggers.triggerForTransformReason(EntityTransformEvent.TransformReason.SHEARED));
    }

    @Test
    @DisplayName("TransformReason 里不存在 AGED，on-age 不能挂在此事件上")
    void noAgedReasonExists() {
        // 这不是「暂时没接」，而是 API 层根本没有该事件来源。
        // 若 Paper 未来补上 AGED 常量，本用例会在编译期失败，提醒重新评估接线。
        for (var r : EntityTransformEvent.TransformReason.values()) {
            assertNotEquals("AGED", r.name(),
                    "Paper 新增了 AGED：on-age 现在可以走事件路线，应重新接线并改标记");
        }
        // 「无事件来源 => 不能接线」这条推理已被推翻：AGE 改走派生采样路线，
        // 与 on-enter/on-leave-water 同机制，不依赖任何事件。
        // 若 AGED 常量将来出现，本用例会在编译期失败，提醒改回事件路线并移除采样代码。
        assertTrue(SkillTrigger.AGE.wired(),
                "on-age 已走 Ageable 采样差分；若已改回事件路线请同步更新本断言");
    }

    @Test
    @DisplayName("与档案语义无关的变形返回 null，不硬套触发器")
    void unrelatedReasonsAreIgnored() {
        for (var r : EntityTransformEvent.TransformReason.values()) {
            if (r == EntityTransformEvent.TransformReason.SHEARED) continue;
            assertNull(SkillTriggers.triggerForTransformReason(r),
                    r + " 与档案语义不对应，不应派发任何触发器");
        }
        assertNull(SkillTriggers.triggerForTransformReason(null));
    }

    @Test
    @DisplayName("on-shear 与 on-age 都已标记为已接线")
    void wiringFlagsMatchReality() {
        assertTrue(SkillTrigger.SHEAR.wired());
        var unwired = SkillTrigger.unwiredNames();
        assertFalse(unwired.contains("on-shear"), "on-shear 已接入 EntityTransformEvent");
        // on-age 已改为派生路线：Paper 无成年事件，改由采样 Ageable#isAdult() 求差分，
        // 与 on-enter/on-leave-water 同一机制。
        // 断言翻转是因为「无事件来源 => 不接线」这条推理在新路线下已不成立——
        // 派生触发器本来就不依赖事件，缺事件不再是无法接线的理由。
        assertFalse(unwired.contains("on-age"),
                "on-age 已接入 Ageable 采样差分，不该留在未接线清单里");
        assertTrue(SkillTrigger.AGE.wired());
    }

    @Test
    @DisplayName("age 与 shear 是不同触发器，不会互相串台")
    void noCrossTalk() {
        assertEquals("on-age", SkillTrigger.AGE.configName());
        assertEquals("on-shear", SkillTrigger.SHEAR.configName());
        assertNotNull(SkillTrigger.of("on-age"));
        assertNotNull(SkillTrigger.of("on-shear"));
    }
}