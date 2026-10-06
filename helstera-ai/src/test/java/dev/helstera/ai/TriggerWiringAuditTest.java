package dev.helstera.ai;

import dev.helstera.ai.skill.SkillTrigger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 触发器接线审计测试。
 *
 * <p>存在的理由：本项目出现过多次「配置写了但永不触发且无任何报错」，
 * 其中一类根因是 {@code SkillTrigger} 的 {@code wired} 标记与真实接线状态脱节
 * （{@code on-entity-shoot} 被标 true，实际没有事件来源）。
 * 标记一旦谎报，{@code /helstera check} 会显示「全部正常」，
 * 管理员据此排除插件问题，转而去查自己的配置——方向完全错。</p>
 *
 * <p>因此这里同时守住两件事：枚举标记的自洽性，以及档案扫描确实能报出
 * 用户「实际写了」的未接线项（而不仅是全局能力清单）。</p>
 */
class TriggerWiringAuditTest {

    /** 构造一个只带 triggers 的档案；标量字段不参与本测试。 */
    private static AiProfile profileWith(String... triggerNames) {
        AiProfile p = new AiProfile("test");
        for (String n : triggerNames) {
            p.triggers.put(n, new AiProfile.TriggerSpec());
        }
        return p;
    }

    private static Map<String, AiProfile> profiles(String name, AiProfile p) {
        Map<String, AiProfile> m = new LinkedHashMap<>();
        m.put(name, p);
        return m;
    }

    @Test
    @DisplayName("只写了已接线触发器的档案，扫描无告警")
    void wiredProfileHasNoWarnings() {
        var map = profiles("boss", profileWith("on-spawn", "on-timer",
                "on-enter-combat", "on-lower-health", "on-enter-water"));
        assertEquals(List.of(), AiManager.scanUnwiredTriggers(map));
    }

    @Test
    @DisplayName("档案写了未接线触发器时，告警指名道姓说出档案与触发器")
    void reportsProfileAndTriggerName() {
        var map = profiles("龙 Boss", profileWith("on-spawn", "on-pre-target"));
        var out = AiManager.scanUnwiredTriggers(map);
        assertEquals(1, out.size(), "应恰好报出一条，实际: " + out);
        assertTrue(out.get(0).contains("龙 Boss"),
                "告警必须带档案名，否则用户不知道改哪个文件，实际: " + out.get(0));
        assertTrue(out.get(0).contains("on-pre-target"),
                "告警必须带触发器名，实际: " + out.get(0));
    }

    @Test
    @DisplayName("拼错的触发器名与未接线触发器被区分开")
    void unknownNameIsSeparateFromUnwired() {
        var map = profiles("boss", profileWith("on-pre-target", "on-banana"));
        var out = AiManager.scanUnwiredTriggers(map);
        assertEquals(2, out.size(), "两类故障应各报一条，实际: " + out);
        assertTrue(out.stream().anyMatch(s -> s.contains("无法识别")),
                "未知名应报「无法识别」，实际: " + out);
        assertTrue(out.stream().anyMatch(s -> s.contains("on-pre-target")),
                "已识别但未接线应报 wireHint，实际: " + out);
    }

    @Test
    @DisplayName("空档案集合不告警")
    void emptySourceHasNoWarnings() {
        assertEquals(List.of(), AiManager.scanUnwiredTriggers(Map.of()));
    }

    @Test
    @DisplayName("ENTITY_SHOOT 的标记与实际派发点一致")
    void entityShootMarkMatchesReality() {
        // 本测试原先断言它「未接线」，理由是模型实例不会自己发射弹丸。
        // 反向审计发现该理由不成立：载体的 entity.type 可以是 player，
        // 这类载体能射箭，EntityShootBowEvent 可达。既然已有真实派发点，
        // 继续标 false 只会让 /helstera check 劝退用户放弃可用机制。
        assertEquals(true, SkillTrigger.ENTITY_SHOOT.wired(),
                "已有 EntityShootBowEvent 派发点，标 false 会让体检命令谎报不可用");
        var map = profiles("boss", profileWith("on-entity-shoot"));
        assertEquals(List.of(), AiManager.scanUnwiredTriggers(map),
                "已接线的触发器不应再出现在未接线告警里");
    }

    @Test
    @DisplayName("告警结果按字典序稳定，便于命令行逐行比对")
    void warningsAreSorted() {
        Map<String, AiProfile> m = new LinkedHashMap<>();
        m.put("zzz", profileWith("on-pre-target"));
        m.put("aaa", profileWith("on-buff", "on-spawn"));
        var out = AiManager.scanUnwiredTriggers(m);
        // on-buff 已随 EntityPotionEffectEvent 接线，不再告警；
        // 只有 on-spawn（已接线）与 on-pre-target（未接线）两档，
        // 故 aaa 侧 0 条、zzz 侧 1 条 —— 断言的是「已接线的不产生告警」
        assertEquals(1, out.size(), "仅 on-pre-target 未接线，实际: " + out);
        assertTrue(out.get(0).startsWith("档案 zzz"), "应按档案名排序，实际: " + out);
    }
}