package dev.helstera.ai.skill;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 仇恨条件/动作的接线审计。
 *
 * <p>这些条件与动作无法脱离 Bukkit 单测：{@code BehaviorContext} 需要真实的
 * {@code ModelInstance}。但「写了却从未注册」正是本项目最想消灭的缺陷类型——
 * {@code AiController} 长期挂着「供 threat 动作直接加仇恨」的注释，而那个动作
 * 从不存在，配置里写 {@code threat-add} 只会静默无效。因此这里用源码断言锁住
 * 注册与接线位置。</p>
 */
class ThreatWiringAuditTest {

    private static final Path SRC = Path.of("src", "main", "java", "dev", "helstera", "ai");

    private static String read(String file) throws IOException {
        return Files.readString(SRC.resolve(file), StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("四个仇恨条件均已注册")
    void conditionsRegistered() throws IOException {
        String src = read("skill/SkillExtras.java");
        for (String n : List.of("threat-above", "threat-below", "has-threat",
                "threat-targets-at-least")) {
            assertTrue(src.contains("Map.entry(\"" + n + "\""),
                    "条件 " + n + " 未注册：配置里写它不会报错，但永远判 false");
        }
    }

    @Test
    @DisplayName("三个仇恨动作均已注册")
    void actionsRegistered() throws IOException {
        String src = read("skill/SkillExtras.java");
        for (String n : List.of("threat-add", "threat-clear", "threat-focus")) {
            assertTrue(src.contains("Map.entry(\"" + n + "\""),
                    "动作 " + n + " 未注册：写进 mobs/*.yml 会静默无效");
        }
    }

    @Test
    @DisplayName("SkillTriggers 构造时接线，否则静态表永远查不到仇恨")
    void lookupIsWired() throws IOException {
        String src = read("skill/SkillTriggers.java");
        assertTrue(src.contains("SkillExtras.threatLookup("),
                "未接线：条件工厂是 static 表，拿不到 AiManager，"
                        + "不接线时 threat-above 会恒为 false 且无任何报错");
        assertTrue(src.contains("controllerById("),
                "必须按实例 id 取控制器；改用 controllerOf(ModelInstance) 会要求"
                        + "调用方反查实例，漏掉空判即 NPE");
    }

    @Test
    @DisplayName("AiManager 提供按 id 取控制器的入口")
    void managerExposesControllerById() throws IOException {
        String src = read("AiManager.java");
        assertTrue(src.contains("public AiController controllerById(int instanceId)"),
                "缺少按 id 取控制器的入口，桥接只能反查实例");
    }
}