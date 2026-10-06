package dev.helstera.ai.skill;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 触发器「已派发却标为未接线」的反向审计。
 *
 * <p>本项目此前的审计方向是单向的：把枚举里标 {@code wired=false} 的当作
 * 「确实没接线」。但本次排查发现 {@code SUMMON} 与 {@code LEASH} 早已有真实派发点，
 * 却被标成 {@code false}——于是 {@code /helstera check} 明确劝退用户
 * 「on-summon 暂未接线」，而它其实能正常工作。</p>
 *
 * <p>这个方向的错误比原方向更隐蔽：它不导致功能失效，而是让一个<b>可用</b>的功能
 * 在体检报告里被列为不可用，用户因此放弃使用。单向审计永远发现不了它。</p>
 */
class TriggerDispatchAuditTest {

    private static final Path TRIGGERS =
            Path.of("src", "main", "java", "dev", "helstera", "ai", "skill", "SkillTriggers.java");

    /** SkillTriggers 里所有真实派发的触发器枚举名。 */
    private static Set<String> dispatched() throws IOException {
        String src = Files.readString(TRIGGERS, StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("dispatch\\(\\s*SkillTrigger\\.(\\w+)").matcher(src);
        Set<String> out = new LinkedHashSet<>();
        while (m.find()) out.add(m.group(1));
        return out;
    }

    @Test
    @DisplayName("实际派发的触发器必须标记为已接线")
    void dispatchedTriggersAreMarkedWired() throws IOException {
        Set<String> notWired = new LinkedHashSet<>();
        for (SkillTrigger t : SkillTrigger.values()) {
            if (!t.wired()) notWired.add(t.name());
        }
        assertTrue(!dispatched().isEmpty(),
                "正则没匹配到任何派发点，测试本身可能已失效");

        Set<String> wrong = new LinkedHashSet<>();
        for (String d : dispatched()) {
            if (notWired.contains(d)) wrong.add(d);
        }
        assertTrue(wrong.isEmpty(),
                "这些触发器已有真实派发点，却标为未接线：/helstera check 会劝退用户"
                        + "不要用一个本来能用的机制。实际派发的有 " + dispatched()
                        + "；被误标的有 " + wrong);
    }

    @Test
    @DisplayName("未接线清单与枚举标记一致")
    void unwiredListMatchesEnum() {
        // unwiredNames() 是 /helstera check 的唯一数据源；
        // 若它自己另有一份判断，枚举改了就不同步
        assertTrue(SkillTrigger.unwiredNames().stream()
                        .allMatch(n -> {
                            SkillTrigger t = SkillTrigger.of(n);
                            return t == null || !t.wired();
                        }),
                "unwiredNames() 里出现了已接线触发器");
    }
}