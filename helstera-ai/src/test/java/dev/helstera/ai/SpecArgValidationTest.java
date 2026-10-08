package dev.helstera.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 技能参数写错时的装载期拒绝。
 *
 * <p><b>这个功能来自真服观察</b>：往 skills.yml 里写
 * {@code set-scale abc} 与 {@code heal-percent 百分比}，{@code /helstera check}
 * 全绿、日志零警告、技能照常执行——但用的是默认值。症状是「技能有时没效果」，
 * 现场无法与「阈值设得本来就高」区分。这是本项目最常见的缺陷形态。</p>
 *
 * <p>修法不是逐个动作加校验（133 个工厂，改不过来也会漂移），而是收口到
 * {@link dev.helstera.ai.skill.SpecArgs}：参数<b>存在但格式非法</b>时抛
 * IllegalArgumentException，由 bindAction/bindCondition 已有的 try-catch
 * 转成一条 warning。</p>
 */
class SpecArgValidationTest {

    private static dev.helstera.ai.skill.SkillCatalog.ActionFactory action(String name) {
        var a = dev.helstera.ai.skill.SkillCatalog.actions().get(name);
        assertTrue(a != null, "内置动作 " + name + " 应存在");
        return a;
    }

    private static dev.helstera.ai.skill.SkillCatalog.ConditionFactory cond(String name) {
        var c = dev.helstera.ai.skill.SkillCatalog.conditions().get(name);
        assertTrue(c != null, "内置条件 " + name + " 应存在");
        return c;
    }

    // ---- 数值参数 ----

    @Test
    @DisplayName("数值参数非法时抛异常，且异常含原始字面量")
    void numericGarbageThrows() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> action("set-scale").create(List.of("abc")));
        assertTrue(e.getMessage().contains("abc"),
                "异常必须含原始字面量，作者才能定位：实际 = " + e.getMessage());

        assertThrows(IllegalArgumentException.class,
                () -> cond("health-below").create(List.of("百分比")));
    }

    @Test
    @DisplayName("缺参与合法值不被误伤（含负数、小数、科学计数）")
    void legitimateValuesStillWork() {
        // 缺参 -> 用默认值，不抛
        action("set-scale").create(List.of());
        action("dash").create(List.of());
        // 合法值
        action("set-scale").create(List.of("1.5"));
        action("set-scale").create(List.of("-3"));
        action("set-scale").create(List.of("1e-3"));
        action("set-scale").create(List.of("  0.8  "));
        cond("health-below").create(List.of("0.3"));
        cond("targets-exist").create(List.of("nearest", "8", "2"));
    }

    // ---- 布尔参数：原实现最隐蔽的一类 ----

    @Test
    @DisplayName("布尔参数接受 true/false/yes/no/on/off/1/0")
    void booleanAcceptsCommonSpellings() {
        for (String t : List.of("true", "TRUE", "yes", "on", "1")) {
            assertTrue(S.bool(t), t + " 应解析为 true");
        }
        for (String f : List.of("false", "No", "off", "0")) {
            assertFalse(S.bool(f), f + " 应解析为 false");
        }
    }

    @Test
    @DisplayName("布尔参数写错时抛异常，而不是静默当成 false")
    void booleanGarbageThrows() {
        // Boolean.parseBoolean("yes") 是 false——原先 loop yes 会静默关掉循环
        assertThrows(IllegalArgumentException.class, () -> S.bool("yes-please"));
        assertThrows(IllegalArgumentException.class, () -> S.bool("2"));
    }

    // ---- invisible 这类 def=true 的动作：原先 def 被忽略 ----

    @Test
    @DisplayName("缺参时返回声明的默认值（SkillExtras.bool 曾无视 def）")
    void missingArgYieldsDeclaredDefault() {
        // SkillExtras 里 invisible / glowing 等 3 处声明默认值 true，
        // 而旧实现写作 `a.size() > i && parseBoolean(...)`，
        // 参数缺失时短路返回 false，无视了传进来的 def。
        // 症状：作者写 `invisible`（本意隐身）却让生物显形。
        assertTrue(S.boolWithDefault(null, 0, true));
        assertFalse(S.boolWithDefault(null, 0, false));
        assertTrue(S.boolWithDefault(List.of(), 0, true));
        assertFalse(S.boolWithDefault(List.of(""), 0, false));
    }

    // ---- 端到端：告警能进 /helstera check ----

    @Test
    @DisplayName("端到端：非法参数不注册动作，并进 warnings")
    void endToEndWarningReachesCheck() {
        var registry = new BehaviorRegistryImpl();
        var svc = new dev.helstera.ai.skill.SkillService(registry, null);
        // 直接走 bindAction：真实加载路径，异常会被接住并转成 warning
        String key = svc.bindAction("set-scale abc");

        assertTrue(key == null || key.isEmpty(),
                "参数非法的动作不应被注册成 \"set-scale:abc\"");
        assertTrue(svc.warnings().stream().anyMatch(w -> w.contains("abc")),
                "warnings 必须含原始字面量：实际 = " + svc.warnings());
        assertFalse(registry.hasAction("set-scale:abc"),
                "非法动作不应进入注册表，否则会真的被执行");
    }

    @Test
    @DisplayName("合法动作仍然正常注册，不受影响")
    void validActionStillRegisters() {
        var registry = new BehaviorRegistryImpl();
        var svc = new dev.helstera.ai.skill.SkillService(registry, null);
        String key = svc.bindAction("set-scale 1.4");
        assertEquals("set-scale:1.4", key);
        assertTrue(svc.warnings().isEmpty(), "合法配置不该产生告警：实际 = " + svc.warnings());
    }

    /**
     * reload 后旧告警必须消失。
     *
     * <p><b>真服验证过的 bug</b>：写入含 {@code set-scale WRONG} 的 skills.yml，
     * reload 后改回合法内容再 reload，{@code /helstera check} 仍报 1 处问题。
     * 原因是 {@code loadSkills} 清了 defs/cooldowns 却漏了 warnings，于是告警
     * 一轮轮叠加。作者看到自己已经改对的配置仍在报错，会以为修复没生效，
     * 转而去反复检查正确的那份文件——比不报错更误导。</p>
     */
    @Test
    @DisplayName("reload 后旧告警被清掉，不会与新告警叠加")
    void reloadClearsStaleWarnings() {
        var registry = new BehaviorRegistryImpl();
        var svc = new dev.helstera.ai.skill.SkillService(registry, null);

        svc.bindAction("set-scale WRONG");
        assertEquals(1, svc.warnings().size(), "第一轮应恰好 1 条告警");

        // 改回合法配置后重新装载：走 loadSkills 的清空路径
        org.bukkit.configuration.file.YamlConfiguration y =
                org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(
                        new java.io.StringReader("skills:\n  ok:\n    on-decision: [set-scale 1.1]\n"));
        svc.loadSkills(y.getConfigurationSection("skills"));

        assertTrue(svc.warnings().isEmpty(),
                "reload 后旧的「参数非法」告警应消失，实际仍为: " + svc.warnings());
    }

    /** 反复 loadSkills 不得让告警无限增长。 */
    @Test
    @DisplayName("连续多次 loadSkills 不累积告警")
    void repeatedLoadsDoNotAccumulateWarnings() {
        var registry = new BehaviorRegistryImpl();
        var svc = new dev.helstera.ai.skill.SkillService(registry, null);
        var bad = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(
                new java.io.StringReader("skills:\n  s:\n    on-decision: [set-scale BAD]\n"));
        var good = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(
                new java.io.StringReader("skills:\n  s:\n    on-decision: [set-scale 1.0]\n"));

        for (int i = 0; i < 5; i++) {
            svc.loadSkills(bad.getConfigurationSection("skills"));
            assertEquals(1, svc.warnings().size(), "第 " + (i + 1) + " 轮：应恰好 1 条");
            svc.loadSkills(good.getConfigurationSection("skills"));
            assertTrue(svc.warnings().isEmpty(), "第 " + (i + 1) + " 轮改对后应清空");
        }
    }

    /** SpecArgs 在 dev.helstera.ai.skill 包，这里用别名保持测试可读。 */
    private static final class S {
        static boolean bool(String raw) {
            return dev.helstera.ai.skill.SpecArgs.bool(List.of(raw), 0, false, "测试");
        }

        static boolean boolWithDefault(List<String> args, int i, boolean def) {
            return dev.helstera.ai.skill.SpecArgs.bool(args, i, def, "测试");
        }
    }
}