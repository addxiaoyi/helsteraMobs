package dev.helstera.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 等级缩放配置的 null 安全性。
 *
 * <p><b>这个测试来自 SpotBugs 的 NP_NULL_ON_SOME_PATH</b>，指向真实缺陷：</p>
 *
 * <pre>
 * String property = map.get("property") == null ? null : String.valueOf(...);
 * if (property.isEmpty()) continue;   // property 为 null 时这里 NPE
 * </pre>
 *
 * <p>触发条件很平常：作者在 {@code levels:} 下写了一条缩放配置却忘了
 * {@code property} 字段。症状是<b>整个生物加载/生成直接抛 NPE</b>，
 * 而报错栈里既看不到是哪个字段、也看不到配置文件名，作者只能逐个
 * 二分排查。</p>
 *
 * <p>同一个 bug 在两处重复出现过（{@code AiProfile.parseLevels} 与
 * {@code HelsteraPlugin.loadLevelConfigs}），是典型的复制粘贴传播。
 * 本测试只覆盖 AiProfile 侧；plugin 侧因为要 Bukkit 的 ConfigurationSection
 * 无法在无服务器环境下构造，靠 {@code SwitchFallThroughAuditTest}
 * 那样的源码审计补位。</p>
 */
class LevelConfigNullSafetyTest {

    @Test
    @DisplayName("levels 条目缺少 property 时不抛异常，只是跳过该条")
    void missingPropertyDoesNotThrow() {
        // 用 Bukkit 的 YamlConfiguration 复现真实加载路径。
        // MockBukkit 依赖较重，这里直接用 YamlConfiguration —
        // 它不依赖服务端即可构造，足够触发同一条解析逻辑。
        org.bukkit.configuration.file.YamlConfiguration y =
                new org.bukkit.configuration.file.YamlConfiguration();
        y.set("levels", List.of(
                java.util.Map.of("base", 100.0, "growthPerLevel", 1.1),  // 缺 property
                java.util.Map.of("property", "health", "base", 200.0, "growthPerLevel", 1.2)
        ));

        assertDoesNotThrow(() -> {
            AiProfile p = new AiProfile("test");
            p.applyOverridesFrom(y.getConfigurationSection(""));
            // 第二条合法的应该被正常接受
            assertTrue(p.levelResult().health() >= 0,
                    "合法条目应被解析；NPE 意味着整段解析被中断");
            assertTrue(p.levels.size() == 1,
                    "缺 property 的条目应被跳过，合法的那条应被保留；实际得到 " + p.levels.size() + " 条");
        }, "levels 条目缺 property 时抛 NPE：作者漏写一个字段就让整个生物加载失败");
    }

    @Test
    @DisplayName("property 为 null 的条目被跳过，其余条目不受影响")
    void nullPropertyEntryIsSkippedNotFatal() {
        org.bukkit.configuration.file.YamlConfiguration y =
                new org.bukkit.configuration.file.YamlConfiguration();
        // 注意：不能用 Map.of("property", null) —— Map.of 本身就拒绝 null 值，
        // 会在测试数据构造阶段就 NPE，测不到被测代码。改用允许 null 的可变 Map，
        // 模拟「YAML 里写了 property:」这种真实形态（解析出来就是 null）。
        java.util.Map<String, Object> nullProp = new java.util.LinkedHashMap<>();
        nullProp.put("property", null);
        nullProp.put("base", 100.0);

        y.set("levels", List.of(
                nullProp,                                  // property 缺失
                java.util.Map.of("property", "  ")        // 空白字符串
        ));

        assertDoesNotThrow(() -> {
            AiProfile p = new AiProfile("test");
            p.applyOverridesFrom(y.getConfigurationSection(""));
            assertTrue(p.levels.isEmpty(),
                    "全是无效条目时应解析出空列表，而不是抛异常或塞进脏数据");
        }, "property 为 null 或空白的条目必须被跳过，不能 NPE");
    }
}