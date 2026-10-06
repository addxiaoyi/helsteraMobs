package dev.helstera.api.behavior;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 目标选择器的接线审计。
 *
 * <p>{@code Targeter} 需要真实 {@code LivingEntity}，无法脱离 Bukkit 单测排序结果，
 * 因此这里断言「注册表本身没把某个名字挂到错误的实现上」——这是本项目最想消灭的
 * 那类缺陷：名字存在、参数能解析、行为却完全不是名字承诺的那样。</p>
 */
class TargeterThreatAuditTest {

    private static final Path SRC =
            Path.of("src", "main", "java", "dev", "helstera", "api", "behavior", "Targeters.java");

    private static String source() throws IOException {
        return Files.readString(SRC, StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("threat 选择器不得再别名到 nearest")
    void threatIsNotAnAliasOfNearest() throws IOException {
        // 此前 BUILTIN.put("threat", Targeters::nearest)：名字叫 threat，
        // 实际跑的是「最近优先」。坦克拉仇恨的写法看起来生效了，却毫无作用。
        assertFalse(source().contains("BUILTIN.put(\"threat\", Targeters::nearest)"),
                "threat 选择器被别名到 nearest：按仇恨选目标会静默变成选最近的");
        assertTrue(source().contains("BUILTIN.put(\"threat\", Targeters::byThreat)"),
                "threat 应指向真正的按仇恨排序实现");
    }

    @Test
    @DisplayName("未注入数据源时 threat 返回空候选，不回落 nearest")
    void threatDoesNotFallBackToNearest() throws IOException {
        String src = source();
        int start = src.indexOf("private static List<LivingEntity> byThreat(");
        assertTrue(start > 0, "未定位到 byThreat");
        int end = src.indexOf("\n    private static", start + 10);
        String body = src.substring(start, end > start ? end : src.length());
        assertTrue(body.contains("if (p == null) return List.of();"),
                "无数据源时必须返回空候选。回落 nearest 会让「按仇恨选目标」"
                        + "静默变成「选最近的」，而配置看上去完全正常");
    }

    @Test
    @DisplayName("仇恨数据源带来源实例，避免跨 Boss 串表")
    void providerCarriesSourceInstance() throws IOException {
        assertTrue(source().contains("double threatOf(ModelInstance src, LivingEntity candidate)"),
                "仇恨按实例存储，同一玩家对不同 Boss 仇恨不同；"
                        + "只传实体无法定位该查哪张表");
    }

    @Test
    @DisplayName("并列仇恨按距离兜底，保证排序确定")
    void tieBreaksByDistance() throws IOException {
        String src = source();
        int start = src.indexOf("private static List<LivingEntity> byThreat(");
        int end = src.indexOf("\n    private static", start + 10);
        String body = src.substring(start, end > start ? end : src.length());
        assertTrue(body.contains("thenComparingDouble"),
                "等仇恨目标必须按距离兜底排序，否则遍历顺序微变会让模型"
                        + "在候选间反复横跳，表现为周期性抽搐转向");
    }

    @Test
    @DisplayName("内置选择器名可按名取到，未知名返回 null")
    void lookupByName() {
        assertNotNull(Targeters.byName("threat"));
        assertNotNull(Targeters.byName("NEAREST"), "选择器名大小写不敏感");
        assertNull(Targeters.byName("不存在的名字"),
                "未知名必须返回 null 交由调用方处理，不能静默回落");
        assertTrue(Targeters.names().containsAll(
                List.of("nearest", "farthest", "random", "lowest-health",
                        "highest-health", "players", "mobs", "threat")));
    }
}