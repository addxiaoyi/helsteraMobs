package dev.helstera.ai.skill;

import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * 药水事件动作 -> 触发器 的映射测试。
 *
 * <p>本测试存在的理由：{@code CHANGED} 若被误当成 {@code ADDED}，玩家每续一次
 * 药水就会触发一遍 Boss 技能——表现为「站着不动也在挨打」，而日志无任何异常。
 * 这个bug 靠人工测试几乎抓不到（需要精确控制续期时机），
 * 且生产环境复现代价很高，必须靠单测守住。</p>
 */
class PotionTriggerMappingTest {

    @Test
    @DisplayName("新获得增益 -> on-buff")
    void addedBeneficialMapsToBuff() {
        assertSame(SkillTrigger.BUFF,
                SkillTriggers.triggerForPotionAction(EntityPotionEffectEvent.Action.ADDED, true));
    }

    @Test
    @DisplayName("CHANGED 不是新获得，不触发 on-buff")
    void changedDoesNotTriggerBuff() {
        // 同种效果改时长/等级，不是「又获得了一次」
        assertNull(SkillTriggers.triggerForPotionAction(
                EntityPotionEffectEvent.Action.CHANGED, true));
        assertNull(SkillTriggers.triggerForPotionAction(
                EntityPotionEffectEvent.Action.CHANGED, false));
    }

    @Test
    @DisplayName("新获得减益不触发 on-buff")
    void addedHarmfulIsNotBuff() {
        assertNull(SkillTriggers.triggerForPotionAction(
                EntityPotionEffectEvent.Action.ADDED, false));
    }

    @Test
    @DisplayName("效果结束（REMOVED / CLEARED）触发 on-potion-effect-end")
    void removalMapsToEffectEnd() {
        assertSame(SkillTrigger.POTION_EFFECT_END,
                SkillTriggers.triggerForPotionAction(EntityPotionEffectEvent.Action.REMOVED, true));
        assertSame(SkillTrigger.POTION_EFFECT_END,
                SkillTriggers.triggerForPotionAction(EntityPotionEffectEvent.Action.REMOVED, false));
        // CLEARED 是「清空所有效果」，也属于结束
        assertSame(SkillTrigger.POTION_EFFECT_END,
                SkillTriggers.triggerForPotionAction(EntityPotionEffectEvent.Action.CLEARED, false));
    }

    @Test
    @DisplayName("null 安全")
    void nullActionIsSafe() {
        assertNull(SkillTriggers.triggerForPotionAction(null, true));
        assertNull(SkillTriggers.triggerForPotionAction(null, false));
    }

    @Test
    @DisplayName("两个触发器均已标记为已接线")
    void bothWired() {
        org.junit.jupiter.api.Assertions.assertTrue(SkillTrigger.BUFF.wired());
        org.junit.jupiter.api.Assertions.assertTrue(SkillTrigger.POTION_EFFECT_END.wired());
        var unwired = SkillTrigger.unwiredNames();
        org.junit.jupiter.api.Assertions.assertFalse(unwired.contains("on-buff"));
        org.junit.jupiter.api.Assertions.assertFalse(unwired.contains("on-potion-effect-end"));
    }
}