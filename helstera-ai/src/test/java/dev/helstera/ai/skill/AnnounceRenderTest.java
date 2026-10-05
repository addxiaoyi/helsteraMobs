package dev.helstera.ai.skill;

import dev.helstera.ai.AiProfile;
import dev.helstera.ai.BossPhase;
import dev.helstera.api.behavior.BehaviorContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 阶段公告文本的展开测试。
 *
 * <p>单测只覆盖纯文本渲染：{@code renderAnnounce} 刻意不碰世界与实体，
 * 所以占位符拼错、颜色码没转换这类问题不必上服就能发现。</p>
 */
class AnnounceRenderTest {

    private static BossPhase phase(String id, String announce) {
        return new BossPhase(id, 0, 25, announce, List.of(), List.of());
    }

    private static BehaviorContext ctx(double healthRatio) {
        // 实例与目标为 null：占位符应退化成档案名与空串，而不是抛 NPE
        return BehaviorContext.of(null, null, healthRatio, -1, 0, "IDLE");
    }

    private static String render(String raw, AiProfile p, BossPhase ph, String prev, BehaviorContext c) {
        return SkillTriggers.renderAnnounce(raw, p, ph, prev, c);
    }

    @Test
    @DisplayName("占位符全部替换，无实例无目标时不抛异常")
    void replacesPlaceholders() {
        AiProfile p = new AiProfile("dragon");
        String tpl = "%mob% 进入 %phase%（%hp%），%player%%world%";
        String out = render(tpl, p, phase("enraged", tpl), "calm", ctx(0.23));
        assertTrue(out.contains("dragon"), "应含档案名: " + out);
        assertTrue(out.contains("enraged"), "应含阶段 id: " + out);
        assertTrue(out.contains("23.0"), "血量应保留一位小数: " + out);
        assertFalse(out.contains("%phase%") || out.contains("%mob%")
                        || out.contains("%hp%") || out.contains("%player%"),
                "不应有未替换的占位符: " + out);
    }

    @Test
    @DisplayName("& 颜色码被转换")
    void translatesColorCodes() {
        AiProfile p = new AiProfile("dragon");
        String out = render("&cBoss &l狂暴", p, phase("x", "&cBoss &l狂暴"), null, ctx(0.5));
        assertFalse(out.contains("&c"), "&c 应被转换: " + out);
        assertFalse(out.contains("&l"), "&l 应被转换: " + out);
        assertTrue(out.contains("Boss"), "正文应保留: " + out);
    }

    @Test
    @DisplayName("首次进入阶段时 %prev-phase% 为空串，不显示 null")
    void firstEntryHasNoPrevPhase() {
        AiProfile p = new AiProfile("boss");
        String tpl = "[%prev-phase%>%phase%]";
        String out = render(tpl, p, phase("p2", tpl), null, ctx(0.6));
        assertEquals("[>p2]", out, "首次进入时 %prev-phase% 展开为空串，不是字面 null");
    }

    @Test
    @DisplayName("血量按一位小数取整，避免 22.999999 这种浮点噪声")
    void healthRoundedToOneDecimal() {
        AiProfile p = new AiProfile("boss");
        // 0.2299999 是典型浮点误差来源
        String out = render("%hp%", p, phase("x", "%hp%"), null, ctx(0.2299999));
        assertEquals("23.0", out);
    }
}
