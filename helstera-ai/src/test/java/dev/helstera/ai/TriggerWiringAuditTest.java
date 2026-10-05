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
        var map = profiles("龙 Boss", profileWith("on-spawn", "on-summon"));
        var out = AiManager.scanUnwiredTriggers(map);
        assertEquals(1, out.size(), "应恰好报出一条，实际: " + out);
        assertTrue(out.get(0).contains("龙 Boss"),
                "告警必须带档案名，否则用户不知道改哪个文件，实际: " + out.get(0));
        assertTrue(out.get(0).contains("on-summon"),
                "告警必须带触发器名，实际: " + out.get(0));
    }

    @Test
    @DisplayName("拼错的触发器名与未接线触发器被区分开")
    void unknownNameIsSeparateFromUnwired() {
        var map = profiles("boss", profileWith("on-summon", "on-banana"));
        var out = AiManager.scanUnwiredTriggers(map);
        assertEquals(2, out.size(), "两类故障应各报一条，实际: " + out);
        assertTrue(out.stream().anyMatch(s -> s.contains("无法识别")),
                "未知名应报「无法识别」，实际: " + out);
        assertTrue(out.stream().anyMatch(s -> s.contains("on-summon")),
                "已识别但未接线应报 wireHint，实际: " + out);
    }

    @Test
    @DisplayName("空档案集合不告警")
    void emptySourceHasNoWarnings() {
        assertEquals(List.of(), AiManager.scanUnwiredTriggers(Map.of()));
    }

    @Test
    @DisplayName("未接线的 ENTITY_SHOOT 被如实标记")
    void entityShootIsNotLying() {
        assertEquals(false, SkillTrigger.ENTITY_SHOOT.wired(),
                "无 ProjectileLaunch 来源，标 true 会让体检命令谎报已支持");
        var map = profiles("boss", profileWith("on-entity-shoot"));
        assertEquals(1, AiManager.scanUnwiredTriggers(map).size(),
                "写了 on-entity-shoot 的档案必须被报出");
    }

    @Test
    @DisplayName("告警结果按字典序稳定，便于命令行逐行比对")
    void warningsAreSorted() {
        Map<String, AiProfile> m = new LinkedHashMap<>();
        m.put("zzz", profileWith("on-summon"));
        m.put("aaa", profileWith("on-buff", "on-spawn"));
        var out = AiManager.scanUnwiredTriggers(m);
        // on-buff 已随 EntityPotionEffectEvent 接线，不再告警；
        // 只有 on-spawn（已接线）与 on-summon（未接线）两档，
        // 故 aaa 侧 0 条、zzz 侧 1 条 —— 断言的是「已接线的不产生告警」
        assertEquals(1, out.size(), "仅 on-summon 未接线，实际: " + out);
        assertTrue(out.get(0).startsWith("档案 zzz"), "应按档案名排序，实际: " + out);
    }
}