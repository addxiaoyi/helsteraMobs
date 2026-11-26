package dev.helstera.ai.skill;

import dev.helstera.ai.BehaviorRegistryImpl;
import dev.helstera.api.behavior.BehaviorContext;
import dev.helstera.api.util.MythicConditionAction;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.LivingEntity;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConditionActionTest {
    private final BehaviorRegistryImpl registry = new BehaviorRegistryImpl();
    private final List<String> events = new ArrayList<>();

    private SkillService service(String yaml) {
        registry.registerCondition("yes", ignored -> true);
        registry.registerCondition("no", ignored -> false);
        registry.registerAction("parent-action", ignored -> events.add("parent"));
        registry.registerAction("child-action", ignored -> events.add("child"));
        SkillService service = new SkillService(registry, null);
        service.loadSkills(YamlConfiguration.loadConfiguration(new StringReader(yaml))
                .getConfigurationSection("skills"));
        return service;
    }

    private static BehaviorContext context() {
        return BehaviorContext.execution(null, null, 1, -1, 0, "test", null, null, List.of(), null);
    }

    @Test
    void castsAndBranchesUseThePredicateWithoutTurningCastIntoARequirement() {
        for (String condition : List.of("yes", "no")) {
            for (String action : List.of("CAST", "CASTINSTEAD", "ORELSECAST")) {
                events.clear();
                SkillService service = service("""
                        skills:
                          caller:
                            require: ['%s %s child']
                            on-decision: [parent-action]
                          child:
                            on-decision: [child-action]
                        """.formatted(condition, action));
                boolean matched = condition.equals("yes");
                boolean childRuns = action.equals("ORELSECAST") ? !matched : matched;
                boolean parentRuns = action.equals("CAST") || !childRuns;
                assertEquals(parentRuns, service.castSkill("caller", context()), condition + action);
                List<String> expected = new ArrayList<>();
                if (childRuns) expected.add("child");
                if (parentRuns) expected.add("parent");
                assertEquals(expected, events, condition + action);
            }
        }
    }

    @Test
    void replacementStillStopsTheParentWhenTheChildIsMissingOrRejected() {
        SkillService service = service("""
                skills:
                  missing:
                    require: ['yes CASTINSTEAD absent']
                    on-decision: [parent-action]
                  caller:
                    require: ['no ORELSECAST child']
                    on-decision: [parent-action]
                  child:
                    require: [no]
                    on-decision: [child-action]
                """);
        assertFalse(service.castSkill("missing", context()));
        assertFalse(service.castSkill("caller", context()));
        assertEquals(List.of(), events);
    }

    @Test
    void previewDoesNotExecuteAnyOutcomeSkill() {
        SkillService service = service("""
                skills:
                  caller:
                    require: ['yes CAST child', 'no ORELSECAST child']
                    on-decision: [parent-action]
                  child:
                    on-decision: [child-action]
                """);
        assertFalse(service.preview("caller", context()).wouldRun());
        assertEquals(List.of(), events);
        assertFalse(service.castSkill("caller", context()));
        assertEquals(List.of("child", "child"), events);
    }

    @Test
    void callerCooldownIsCheckedBeforeConditionalSideEffects() {
        SkillService service = service("""
                skills:
                  caller:
                    cooldown: 10s
                    require: ['yes CAST child']
                    on-decision: [parent-action]
                  child:
                    on-decision: [child-action]
                """);
        assertTrue(service.castSkill("caller", context()));
        assertFalse(service.castSkill("caller", context()));
        assertEquals(List.of("child", "parent"), events);
    }

    @Test
    void inlineTriggerRejectionPreventsCasterConditionPowerSideEffects() {
        SkillService service = service("skills: {}");
        String power = service.bindCondition("yes POWER 3");
        var allowed = new java.util.concurrent.atomic.AtomicBoolean();
        registry.registerCondition("trigger-gate", ctx -> {
            events.add("trigger");
            return allowed.get();
        });
        registry.registerCondition("caster-outcome", ctx -> {
            events.add("caster");
            return registry.testConditionForComposition(power, ctx);
        });
        String action = service.bindAction("parent-action ?caster-outcome ?~trigger-gate");
        assertNotNull(action);
        var ctx = BehaviorContext.execution(null, null, 1, -1, 0, "test", null,
                target("trigger"), List.of(), null);
        assertTrue(registry.runAction(action, ctx));
        assertEquals(List.of("trigger"), events);
        assertEquals(1.0f, ctx.skillPower());
        events.clear();
        allowed.set(true);
        assertTrue(registry.runAction(action, ctx));
        assertEquals(List.of("trigger", "caster", "parent"), events);
        assertEquals(3.0f, ctx.skillPower());
    }

    @Test
    void delayedAndRepeatedMechanicsCapturePowerWhileRetainingLiveSkillVariables() {
        registry.registerAction("capture-power", ctx -> events.add(
                Placeholders.resolve("<skill.power>:<skill.marker>", ctx)));
        SkillService service = service("""
                skills:
                  boost:
                    require: ['yes POWER 3']
                """);
        var pending = new java.util.ArrayDeque<Runnable>();
        SkillCatalog.taskScheduler((task, delay) -> { pending.add(task); return true; });
        try {
            for (String timing : List.of("delay=2", "repeat=1;repeatinterval=2")) {
                events.clear();
                var ctx = context();
                ctx.multiplySkillPower(2);
                ctx.skillTree().orElseThrow().setVariable("marker", "before");
                String action = service.bindAction("capture-power{" + timing + "}");
                assertNotNull(action);
                assertTrue(registry.runAction(action, ctx));
                assertTrue(service.castSkill("boost", ctx));
                ctx.skillTree().orElseThrow().setVariable("marker", "after");
                assertEquals(1, pending.size());
                pending.remove().run();
                assertEquals(timing.startsWith("repeat") ? List.of("2.0:before", "2.0:after")
                        : List.of("2.0:after"), events, timing);
                assertEquals(6.0f, ctx.skillPower());
            }
        } finally {
            SkillCatalog.taskScheduler(null);
        }
    }

    @Test
    void repeatedInlinePowerIsAppliedOnceBeforeEachExecutionReceivesItsOwnMetadata() {
        registry.registerAction("capture-power", ctx -> events.add(Placeholders.resolve("<skill.power>", ctx)));
        SkillService service = service("skills: {}");
        String power = service.bindCondition("yes POWER 2");
        var checks = new java.util.concurrent.atomic.AtomicInteger();
        registry.registerCondition("power-gate", ctx -> {
            checks.incrementAndGet();
            return registry.testConditionForComposition(power, ctx);
        });
        var pending = new java.util.ArrayDeque<Runnable>();
        SkillCatalog.taskScheduler((task, delay) -> { pending.add(task); return true; });
        try {
            for (String timing : List.of("delay=2", "repeat=2;repeatinterval=2", "repeat=3")) {
                events.clear();
                checks.set(0);
                var ctx = context();
                String action = service.bindAction("capture-power{" + timing + "} ?power-gate");
                assertNotNull(action);
                assertTrue(registry.runAction(action, ctx));
                assertEquals(1, checks.get(), timing);
                assertEquals(2.0f, ctx.skillPower(), timing);
                // A later sibling changes only the parent, not already queued repeat metadata.
                ctx.multiplySkillPower(3);
                while (!pending.isEmpty()) pending.remove().run();
                assertEquals(timing.startsWith("delay") ? List.of("2.0")
                        : List.of("2.0", "2.0", "2.0"), events, timing);
                assertEquals(1, checks.get(), timing);
                assertEquals(6.0f, ctx.skillPower(), timing);
            }
        } finally {
            SkillCatalog.taskScheduler(null);
        }
    }

    @Test
    void delayedConditionsDecideBeforeSchedulingWhileRepeatedTargetConditionsStayLive() {
        SkillService service = service("skills: {}");
        var allowed = new java.util.concurrent.atomic.AtomicBoolean();
        var casterChecks = new java.util.concurrent.atomic.AtomicInteger();
        var targetChecks = new java.util.concurrent.atomic.AtomicInteger();
        registry.registerCondition("caster-gate", ctx -> { casterChecks.incrementAndGet(); return allowed.get(); });
        registry.registerCondition("target-gate", ctx -> { targetChecks.incrementAndGet(); return allowed.get(); });
        var pending = new java.util.ArrayDeque<Runnable>();
        SkillCatalog.taskScheduler((task, delay) -> { pending.add(task); return true; });
        try {
            String delayed = service.bindAction("parent-action{delay=2} ?caster-gate");
            assertTrue(registry.runAction(delayed, context()));
            assertTrue(pending.isEmpty(), "rejected caster condition does not queue a delayed action");
            allowed.set(true);
            assertTrue(registry.runAction(delayed, context()));
            allowed.set(false);
            pending.remove().run();
            assertEquals(List.of("parent"), events);
            assertEquals(2, casterChecks.get());

            events.clear();
            allowed.set(true);
            var target = target("recipient");
            var ctx = BehaviorContext.execution(null, null, 1, -1, 0, "test", null, target, List.of(target), null)
                    .withTargetEntity(target);
            String repeated = service.bindAction("parent-action{repeat=2;repeatinterval=2} @targets ?@target-gate");
            assertTrue(registry.runAction(repeated, ctx));
            allowed.set(false);
            pending.remove().run();
            allowed.set(true);
            pending.remove().run();
            assertEquals(3, targetChecks.get());
            assertEquals(List.of("parent", "parent"), events);
        } finally {
            SkillCatalog.taskScheduler(null);
        }
    }

    @Test
    void dynamicConditionSkillCyclesStopBeforeStackOverflow() {
        SkillService service = service("""
                skills:
                  caller:
                    require: ['yes CASTINSTEAD <skill.next>']
                    on-decision: [parent-action]
                """);
        var context = context();
        context.skillTree().orElseThrow().setVariable("next", "caller");
        assertFalse(service.castSkill("caller", context));
        assertEquals(List.of(), events);
        assertTrue(service.warnings().stream().anyMatch(warning -> warning.contains("32")));
    }

    @Test
    void targetConditionsAreConditionMajorAndBranchSkillsReceiveTheWholeTargetSet() {
        LivingEntity first = target("first");
        LivingEntity second = target("second");
        registry.registerCondition("record-first", ctx -> {
            events.add("first-" + ctx.targetEntity().orElseThrow().getName());
            return true;
        });
        registry.registerCondition("record-second", ctx -> {
            events.add("second-" + ctx.targetEntity().orElseThrow().getName());
            return true;
        });
        registry.registerAction("capture-targets", ctx -> events.add("targets-" + ctx.targets().size()));
        SkillService service = service("""
                skills:
                  caller:
                    target-conditions: ['record-first CAST child', record-second]
                    require: ['yes CAST caster-child']
                    on-decision: [parent-action]
                  child:
                    on-decision: [capture-targets]
                  caster-child:
                    on-decision: [child-action]
                """);
        BehaviorContext ctx = BehaviorContext.execution(null, null, 1, -1, 0,
                "test", null, null, List.of(first, second), null);
        assertTrue(service.castSkill("caller", ctx));
        assertEquals(List.of("first-first", "targets-2", "first-second", "targets-2",
                "second-first", "second-second", "child", "parent"), events);
    }

    @Test
    void targetReplacementFiltersMatchingTargetsButRetainsOtherTargets() {
        LivingEntity first = target("first");
        LivingEntity second = target("second");
        registry.registerCondition("is-first", ctx -> ctx.targetEntity().orElse(null) == first);
        registry.registerAction("capture-targets", ctx -> events.add(ctx.targets().getFirst().getName()));
        SkillService service = service("""
                skills:
                  caller:
                    target-conditions: ['is-first CASTINSTEAD child']
                    on-decision: [capture-targets]
                  child:
                    on-decision: [child-action]
                """);
        BehaviorContext ctx = BehaviorContext.execution(null, null, 1, -1, 0,
                "test", null, null, List.of(first, second), null);
        assertTrue(service.castSkill("caller", ctx));
        assertEquals(List.of("child", "second"), events);
    }

    @Test
    void encodedConditionsAvoidInterpretingLeafBooleanArgumentsAsActions() {
        registry.registerCondition("contains-boolean", ignored -> true);
        var outcome = new MythicConditionAction("contains-boolean", MythicConditionAction.Type.CAST,
                "child", false);
        SkillService service = service("""
                skills:
                  caller:
                    require: ['%s']
                  child:
                    on-decision: [child-action]
                """.formatted(outcome.runtime()));
        assertTrue(service.castSkill("caller", context()));
        assertEquals(List.of("child"), events);
    }

    @Test
    void missingArgumentsFailClosedWhileSkillLevelIsANativeNoOp() {
        SkillService service = service("""
                skills:
                  bad:
                    require: ['yes CAST']
                    on-decision: [parent-action]
                  level:
                    require: ['no LEVEL 2']
                    on-decision: [parent-action]
                """);
        assertFalse(service.castSkill("bad", context()));
        assertTrue(service.castSkill("level", context()));
        assertEquals(List.of("parent"), events);
    }

    @Test
    void powerMultipliesTheMetadataAndOrdinaryChildMechanicsDoNotLeakToTheirParent() {
        registry.registerAction("capture-power", ctx -> events.add(Placeholders.resolve("<skill.power>", ctx)));
        SkillService service = service("""
                skills:
                  caller:
                    require: ['yes POWER 2', 'no POWER 100']
                    on-decision: [capture-power, 'cast-skill child', capture-power]
                  child:
                    require: ['yes POWER 3']
                    on-decision: [capture-power]
                """);
        var ctx = context();
        assertTrue(service.preview("caller", ctx).wouldRun());
        assertEquals(1.0f, ctx.skillPower());
        assertTrue(service.castSkill("caller", ctx));
        assertEquals(List.of("2.0", "6.0", "2.0"), events);
        assertEquals(2.0f, ctx.skillPower());
    }

    @Test
    void conditionCastSharesPowerMetadataAndLaterConditionsReadTheNewValue() {
        registry.registerAction("capture-power", ctx -> events.add(Placeholders.resolve("<skill.power>", ctx)));
        SkillService service = service("""
                skills:
                  caller:
                    require: ['yes POWER 2', 'yes CAST child', 'yes POWER <skill.power>']
                    on-decision: [capture-power]
                  child:
                    require: ['yes POWER 3']
                    on-decision: [capture-power]
                """);
        assertTrue(service.castSkill("caller", context()));
        assertEquals(List.of("6.0", "36.0"), events);
    }

    @Test
    void invalidPowerRetainsThePriorFiniteValueAndDoesNotRejectTheSkill() {
        registry.registerAction("capture-power", ctx -> events.add(Placeholders.resolve("<skill.power>", ctx)));
        SkillService service = service("""
                skills:
                  caller:
                    require: ['yes POWER 2', 'yes POWER NaN', 'yes POWER missing', 'yes POWER 1e38', 'yes POWER 1e38']
                    on-decision: [capture-power]
                """);
        var ctx = context();
        assertTrue(service.castSkill("caller", ctx));
        assertEquals(2e38f, ctx.skillPower());
        assertEquals(3, service.warnings().stream().filter(value -> value.contains("Invalid condition POWER")).count());
    }

    @Test
    void damageUsesPowerUnlessDisabledAndHealingKeepsItsNativeAmount() {
        var world = mock(org.bukkit.World.class);
        var target = target("victim");
        when(target.getWorld()).thenReturn(world);
        when(target.getHealth()).thenReturn(10.0);
        when(target.getLocation()).thenReturn(new org.bukkit.Location(world, 1, 2, 3));
        SkillService service = service("""
                skills:
                  caller:
                    require: ['yes POWER 3']
                    on-decision:
                      - damage{a=2} @targets
                      - damage{a=2;pad=false} @targets
                      - heal{a=2} @targets
                """);
        var ctx = BehaviorContext.execution(null, null, 1, -1, 0, "test", null,
                null, List.of(target), new org.bukkit.Location(world, 0, 2, 3));
        assertTrue(service.castSkill("caller", ctx), service.warnings().toString());
        verify(target).damage(6.0, (org.bukkit.entity.Entity) null);
        verify(target).damage(2.0, (org.bukkit.entity.Entity) null);
        verify(target).setHealth(12.0);
    }

    private static LivingEntity target(String name) {
        LivingEntity target = mock(LivingEntity.class);
        when(target.isValid()).thenReturn(true);
        when(target.getName()).thenReturn(name);
        when(target.getMaxHealth()).thenReturn(20.0);
        when(target.getHealth()).thenReturn(20.0);
        return target;
    }
}
