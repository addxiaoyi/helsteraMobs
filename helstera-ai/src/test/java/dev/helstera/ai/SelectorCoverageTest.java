package dev.helstera.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 新增的官方 goal / target 选择器覆盖。
 *
 * <p>这里只验证「解析结果」与「诊断」——即哪些 token 变成策略位、
 * 哪些被明确判为无公开 API 等价物。运行时行为依赖 Bukkit 世界，
 * 由集成测试覆盖，不在纯单测里造假实体。</p>
 */
class SelectorCoverageTest {

    private static AiProfile goals(String... selectors) {
        AiProfile profile = new AiProfile("coverage");
        profile.goals.addAll(List.of(selectors));
        return profile;
    }

    private static AiProfile targets(String... selectors) {
        AiProfile profile = new AiProfile("coverage");
        profile.targets.addAll(List.of(selectors));
        return profile;
    }

    // ---------------- goal ----------------

    @Test
    @DisplayName("float goal enables the float bit without claiming swim semantics")
    void floatGoalIsDistinctFromSwim() {
        AiProfile.SelectorPolicy policy = goals("0 float", "1 lookatplayers").selectorPolicy();

        assertTrue(policy.canFloat(), "float 必须打开显式浮水位");
        assertTrue(policy.swim(), "float 同时保持不会淹死的基线语义");
        assertTrue(policy.unsupported().isEmpty(), policy.unsupported().toString());
    }

    @Test
    @DisplayName("profile without float goal leaves the float bit off")
    void floatIsOffUnlessRequested() {
        AiProfile.SelectorPolicy policy = goals("0 meleeattack").selectorPolicy();

        assertFalse(policy.canFloat(), "没有 float 就不该打开 Pathfinder 浮水");
        assertFalse(policy.canBreakDoors());
    }

    @Test
    @DisplayName("breakdoors maps to its own policy bit")
    void breakDoorsMapsToPolicyBit() {
        AiProfile.SelectorPolicy policy = goals("0 meleeattack", "1 breakdoors").selectorPolicy();

        assertTrue(policy.canBreakDoors());
        assertTrue(policy.unsupported().isEmpty(), policy.unsupported().toString());
    }

    @Test
    @DisplayName("climb/spider/closedoor are diagnosed instead of silently dropped")
    void unsupportedNativeGoalsAreDiagnosed() {
        AiProfile.SelectorPolicy policy = goals("0 spider", "1 closedoor").selectorPolicy();

        assertEquals(Boolean.FALSE, policy.canClimb(),
                "攀爬没有公开 API，不能假装已启用；unsupported=" + policy.unsupported());
        assertTrue(policy.unsupported().stream().anyMatch(s -> s.contains("climb")),
                "climb 必须出现在诊断里：" + policy.unsupported());
        assertTrue(policy.unsupported().stream().allMatch(s -> s.startsWith("goal:")),
                policy.unsupported().toString());
    }

    @Test
    @DisplayName("native chase aliases reuse the existing chase policy")
    void chaseAliasesDoNotWarn() {
        AiProfile.SelectorPolicy policy = goals(
                "0 nearestattackabletarget", "1 targetofopportunity", "2 movetowardsTarget").selectorPolicy();

        assertTrue(policy.chase(), "别名为 chase，必须落到既有策略位");
        assertTrue(policy.unsupported().isEmpty(), policy.unsupported().toString());
    }

    // ---------------- target ----------------

    @Test
    @DisplayName("insultedTargets becomes its own target mode")
    void insultedTargetsHasDedicatedMode() {
        AiProfile.SelectorPolicy policy = targets("0 insultedTargets").selectorPolicy();

        assertEquals(AiProfile.TargetMode.INSULTED, policy.targetMode());
        assertTrue(policy.hurtByTargetFirst(), "被冒犯者优先于场景扫描");
        assertTrue(policy.unsupported().isEmpty(), policy.unsupported().toString());
    }

    @Test
    @DisplayName("cleartarget is a distinct token, not a table reset")
    void clearTargetKeepsEarlierRulesButYieldsNoTarget() {
        AiProfile profile = new AiProfile("cleartarget");
        profile.targets.addAll(List.of("0 players", "1 cleartarget"));

        SelectorPlan plan = SelectorPlan.compile(profile);

        assertEquals(List.of("players", "cleartarget"),
                plan.targets().stream().map(SelectorPlan.Rule::token).toList(),
                "cleartarget 不重置优先序列表（那个语义属于 clear）");
        assertEquals(SelectorPlan.TargetKind.NONE,
                SelectorPlan.targetKind(plan.targets().get(1)));
        assertTrue(plan.unsupported().isEmpty(), plan.unsupported().toString());
    }

    @Test
    @DisplayName("cleartarget in profile targets clears the runtime target mode")
    void clearTargetModeIsNone() {
        AiProfile.SelectorPolicy policy = targets("0 clearTarget").selectorPolicy();

        assertEquals(AiProfile.TargetMode.NONE, policy.targetMode());
        assertFalse(policy.playerTargets());
        assertTrue(policy.unsupported().isEmpty(), policy.unsupported().toString());
    }

    @Test
    @DisplayName("insultedTargets keeps its own TargetKind in the plan")
    void insultedTargetKindIsTyped() {
        AiProfile profile = targets("0 insulted_targets");

        SelectorPlan plan = SelectorPlan.compile(profile);

        assertEquals(SelectorPlan.TargetKind.INSULTED,
                SelectorPlan.targetKind(plan.targets().get(0)));
    }

    @Test
    @DisplayName("plural and snake_case variants are accepted")
    void variantSpellingsAreAccepted() {
        for (String selector : List.of("insulted_targets", "insultedtarget",
                "cleartargets", "clear_targets", "break_door", "close_doors", "climb")) {
            boolean goal = selector.startsWith("break") || selector.startsWith("close")
                    || selector.equals("climb");
            AiProfile profile = goal ? goals("0 " + selector) : targets("0 " + selector);
            SelectorPlan plan = SelectorPlan.compile(profile);

            assertTrue(plan.unsupported().isEmpty(),
                    selector + " 被当成未知选择器：" + plan.unsupported());
        }
    }
}
