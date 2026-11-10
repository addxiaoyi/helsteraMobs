package dev.helstera.ai.skill;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Map;
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

    private enum SourceKind {
        DIRECT_DISPATCH,
        DERIVED_SAMPLE,
        CONDITION_SAMPLE,
        POTION_EVENT,
        SIGNAL_BUS
    }

    private static final Path TRIGGERS =
            Path.of("src", "main", "java", "dev", "helstera", "ai", "skill", "SkillTriggers.java");

    /** SkillTriggers 里所有真实派发的触发器枚举名。 */
    private static Set<String> dispatched() throws IOException {
        String src = Files.readString(TRIGGERS, StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("(?:dispatch|dispatchWithSpec)\\(\\s*SkillTrigger\\.(\\w+)").matcher(src);
        Set<String> out = new LinkedHashSet<>();
        while (m.find()) out.add(m.group(1));
        return out;
    }

    /**
     * Each wired trigger must have one auditable source route.  Direct Bukkit
     * and Helstera-bus routes use the default direct category; the routes that
     * intentionally dispatch through a dynamic enum variable are listed
     * explicitly so adding one cannot silently skip the sampler/mapping.
     */
    private static Map<SkillTrigger, SourceKind> sourceKinds() {
        EnumMap<SkillTrigger, SourceKind> out = new EnumMap<>(SkillTrigger.class);
        for (SkillTrigger trigger : SkillTrigger.values()) {
            if (trigger.wired()) out.put(trigger, SourceKind.DIRECT_DISPATCH);
        }
        for (SkillTrigger trigger : EnumSet.of(
                SkillTrigger.ENTER_COMBAT,
                SkillTrigger.LEAVE_COMBAT,
                SkillTrigger.TARGET_CHANGE,
                SkillTrigger.LOWER_HEALTH,
                SkillTrigger.LOST_TARGET,
                SkillTrigger.ENTER_WATER,
                SkillTrigger.LEAVE_WATER,
                SkillTrigger.AGE,
                SkillTrigger.ENTER_REGION,
                SkillTrigger.LEAVE_REGION)) {
            out.put(trigger, SourceKind.DERIVED_SAMPLE);
        }
        out.put(SkillTrigger.CONDITION_MET, SourceKind.CONDITION_SAMPLE);
        out.put(SkillTrigger.CONDITION_LOST, SourceKind.CONDITION_SAMPLE);
        out.put(SkillTrigger.BUFF, SourceKind.POTION_EVENT);
        out.put(SkillTrigger.POTION_EFFECT_END, SourceKind.POTION_EVENT);
        out.put(SkillTrigger.ON_SIGNAL, SourceKind.SIGNAL_BUS);
        return Map.copyOf(out);
    }

    private static String triggerToken(SkillTrigger trigger) {
        return "SkillTrigger." + trigger.name();
    }

    /** Assert that a dynamic source still contains the named trigger in its route. */
    private static void assertDynamicSource(String source, SkillTrigger trigger,
                                            SourceKind kind, Set<String> problems) {
        String token = triggerToken(trigger);
        if (!source.contains(token)) {
            problems.add(trigger.name() + " [" + kind + "]: source no longer names the trigger");
        }
        switch (kind) {
            case DERIVED_SAMPLE -> {
                if (!source.contains("DERIVED_TRIGGERS") || !source.contains("dispatch(edge")) {
                    problems.add(trigger.name() + " [DERIVED_SAMPLE]: missing sampler or edge dispatch");
                }
            }
            case CONDITION_SAMPLE -> {
                if (!source.contains("pollConditions") || !source.contains("dispatch(t,")) {
                    problems.add(trigger.name() + " [CONDITION_SAMPLE]: missing condition sampler or dispatch");
                }
            }
            case POTION_EVENT -> {
                if (!source.contains("triggerForPotionAction")
                        || !source.contains("dispatch(trigger")
                        || (trigger == SkillTrigger.POTION_EFFECT_END
                        && !source.contains("dispatchPotionEffectEnd"))) {
                    problems.add(trigger.name() + " [POTION_EVENT]: missing Bukkit potion mapping/dispatch");
                }
            }
            case SIGNAL_BUS -> {
                if (!source.contains("subscribeSignal")
                        || !source.contains("dispatchSignal")
                        || !source.contains("runActions(SkillTrigger.ON_SIGNAL")) {
                    problems.add(trigger.name() + " [SIGNAL_BUS]: missing subscription or payload dispatch");
                }
            }
            case DIRECT_DISPATCH -> throw new AssertionError("direct route passed to dynamic audit");
        }
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
    @DisplayName("每个已接线触发器都能追溯到事件、总线或采样来源")
    void everyWiredTriggerHasAuditableSource() throws IOException {
        String source = Files.readString(TRIGGERS, StandardCharsets.UTF_8);
        Map<SkillTrigger, SourceKind> kinds = sourceKinds();
        Set<SkillTrigger> wired = EnumSet.noneOf(SkillTrigger.class);
        for (SkillTrigger trigger : SkillTrigger.values()) {
            if (trigger.wired()) wired.add(trigger);
        }

        Set<SkillTrigger> missingClassification = EnumSet.copyOf(wired);
        missingClassification.removeAll(kinds.keySet());
        Set<SkillTrigger> staleClassification = EnumSet.copyOf(kinds.keySet());
        staleClassification.removeAll(wired);
        assertTrue(missingClassification.isEmpty() && staleClassification.isEmpty(),
                "wired/source 分类不同步：缺少 " + missingClassification
                        + "，多余 " + staleClassification);

        Set<String> problems = new LinkedHashSet<>();
        for (Map.Entry<SkillTrigger, SourceKind> entry : kinds.entrySet()) {
            SkillTrigger trigger = entry.getKey();
            SourceKind kind = entry.getValue();
            if (kind == SourceKind.DIRECT_DISPATCH) {
                String marker = trigger == SkillTrigger.TIMER
                        ? "dispatchWithSpec(SkillTrigger.TIMER" : "dispatch(" + triggerToken(trigger);
                if (!source.contains(marker)) {
                    problems.add(trigger.name() + " [DIRECT_DISPATCH]: missing " + marker);
                }
            } else {
                assertDynamicSource(source, trigger, kind, problems);
            }
        }
        assertTrue(problems.isEmpty(),
                "已接线触发器没有可审计的事件/总线/采样来源：" + problems);
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

    @Test
    @DisplayName("实体交互监听覆盖普通右键与 AtEntity 子类事件")
    void interactionHandlerUsesBaseEntityEvent() throws IOException {
        String src = Files.readString(TRIGGERS, StandardCharsets.UTF_8);
        assertTrue(src.contains("import org.bukkit.event.player.PlayerInteractEntityEvent;"),
                "交互触发器必须监听 Bukkit 的父类实体交互事件");
        assertTrue(src.contains("void onInteract(PlayerInteractEntityEvent e)"),
                "普通 PlayerInteractEntityEvent 不能被遗漏");
    }

    @Test
    @DisplayName("stop clears event-bus listeners and sampled trigger state")
    void stopOwnsLifecycleCleanup() throws IOException {
        String src = Files.readString(TRIGGERS, StandardCharsets.UTF_8);
        int stop = src.indexOf("public void stop()");
        assertTrue(stop >= 0, "SkillTriggers stop method is missing");
        String body = src.substring(stop, Math.min(src.length(), stop + 2600));
        for (String required : Set.of("nextFire.clear()", "condLatch.clear()",
                "derivedLatch.clear()", "potionState.clear()",
                "potionEndDispatchTick.clear()", "busSubs.clear()",
                "potionEffectEndDispatcher(null)", "resetRuntimeHooks()")) {
            assertTrue(body.contains(required), "stop must clear " + required);
        }
    }
}
