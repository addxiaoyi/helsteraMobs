package dev.helstera.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 档案热重载换绑的回归测试。
 *
 * <p><b>这个缺陷是真实存在的，且症状最隐蔽</b>：{@code loadProfiles} 会
 * {@code profiles.clear()} 后重建全新 {@code AiProfile} 对象，而
 * {@code boundProfiles} / {@code AiController} 存的是 attach 时的旧引用。
 * 于是 {@code /helstera reload config} 之后，存量 Boss 仍按<b>旧</b>免疫表挨打，
 * 新生成的实例却用上新配置——两者行为不一致，且控制台没有任何提示。
 * 「reload 好像不管用」「有的 Boss 免疫有的不免疫」都从这里来。</p>
 *
 * <p>完整路径需要真的构造 {@code ModelInstanceImpl} 并 attach，成本远超收益；
 * 因此这里分两层守：<b>能纯逻辑验证的用反射直接验</b>（判等语义、null 安全、
 * 空档案名回退），<b>接线位置用源码断言锁住</b>——「实现了却忘了调用」正是本项目
 * 最想消灭的缺陷类型，而它无法靠行为测试发现。</p>
 */
class ProfileRebindAuditTest {

    private static final Path SRC = Path.of("src", "main", "java", "dev", "helstera", "ai");

    private static String read(String file) throws IOException {
        return Files.readString(SRC.resolve(file), StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("loadProfiles 必须调用 rebindProfiles，否则换绑方法永远不会被执行")
    void loadProfilesWiresRebind() throws IOException {
        String src = read("AiManager.java");
        int start = src.indexOf("public void loadProfiles(");
        assertTrue(start > 0, "未定位到 loadProfiles");
        int end = src.indexOf("\n    public ", start + 10);
        assertTrue(end > start, "未定位到 loadProfiles 方法结尾");
        String body = src.substring(start, end);

        // 必须出现两次：null 早退分支与正常分支各一次。
        // 只改正常分支会让「ai.profiles 整节被删掉」这条路径漏掉换绑——
        // 而那恰恰是最需要换绑的场景（所有档案都没了）
        int calls = body.split("rebindProfiles\\(", -1).length - 1;
        assertTrue(calls >= 2,
                "loadProfiles 内只调用了 " + calls + " 次 rebindProfiles，"
                        + "需要 null 早退分支与正常分支各一次");
    }

    @Test
    @DisplayName("换绑靠引用判等而不是 equals")
    void rebindUsesIdentitySemantics() throws IOException {
        String src = read("AiManager.java");
        assertTrue(src.contains("newSetFromMap(new java.util.IdentityHashMap<>()"),
                "必须用 IdentityHashMap 收集旧档案：走 equals 的集合会把"
                        + " mobs/*.yml 覆盖出来的副本也算进去，从而被静默换绑、抹掉生物级覆盖");
    }

    @Test
    @DisplayName("rebindProfiles 对空/null 输入安全且返回 0")
    void rebindIsNullSafe() throws Exception {
        Method m = AiManager.class.getMethod("rebindProfiles", java.util.Set.class);
        // 构造器只保存引用、不触碰它们，null 入参足够安全地跑到方法体
        AiManager ai = new AiManager(null, null, null);
        assertEquals(0, (int) m.invoke(ai, (java.util.Set<?>) null),
                "null 集合应安全返回 0，而不是抛异常把整个 reload 打断");
        assertEquals(0, (int) m.invoke(ai, java.util.Set.of()),
                "空集合应返回 0");
    }

    @Test
    @DisplayName("档案已从配置中删除时保留旧对象，不退化成无档案")
    void missingProfileKeepsOldBinding() throws Exception {
        AiManager ai = new AiManager(null, null, null);
        Method m = AiManager.class.getMethod("rebindProfiles", java.util.Set.class);
        // 空的 boundProfiles -> 没有可换绑的实例
        assertEquals(0, (int) m.invoke(ai, java.util.Set.of(new AiProfile("gone"))));

        String src = Files.readString(SRC.resolve("AiManager.java"), StandardCharsets.UTF_8);
        int start = src.indexOf("public int rebindProfiles(");
        assertTrue(start > 0, "未定位到 rebindProfiles");
        int end = src.indexOf("\n    public ", start + 10);
        String body = src.substring(start, end);
        assertTrue(body.contains("if (fresh == null || fresh == cur) continue;"),
                "新档案里查无此名时必须保留旧引用；置 null 会让实例退化成"
                        + "「无档案」，免疫与技能全部失效，比用旧配置更难察觉");
    }

    @Test
    @DisplayName("AiController 暴露换绑入口，且 profile 非 final")
    void controllerExposesRebind() throws Exception {
        Method m = AiController.class.getMethod("rebindProfile", AiProfile.class);
        assertNotNull(m);

        String src = read("AiController.java");
        assertTrue(src.contains("private AiProfile profile;"),
                "profile 必须是可变字段：写成 final 则 reload 对存量 Boss 无效，"
                        + "且编译器与测试都不会报任何问题");
        assertTrue(!src.contains("private final AiProfile profile;"),
                "profile 仍是 final，档案热重载对本控制器无效");
    }

    @Test
    @DisplayName("换绑不重置仇恨表与状态机")
    void rebindKeepsCombatState() throws IOException {
        String src = read("AiController.java");
        int start = src.indexOf("public void rebindProfile(");
        assertTrue(start > 0, "未定位到 rebindProfile");
        int end = src.indexOf("\n    public ", start + 10);
        int close = end > start ? end : src.length();
        String body = src.substring(start, close);
        assertTrue(!body.contains("this.threat ="),
                "换绑不得重建仇恨表：reload 会顺带清掉「谁正在打我」，"
                        + "让 reload 变成一次战斗状态清档");
        assertTrue(body.contains("if (fresh != null)"),
                "fresh 为 null 时必须忽略，保留旧引用优于退化成无档案");
    }
}