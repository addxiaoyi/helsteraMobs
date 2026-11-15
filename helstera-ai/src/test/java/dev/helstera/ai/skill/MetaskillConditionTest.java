package dev.helstera.ai.skill;

import dev.helstera.ai.BehaviorRegistryImpl;
import dev.helstera.api.behavior.BehaviorContext;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MetaskillConditionTest {

    private final BehaviorRegistryImpl registry = new BehaviorRegistryImpl();

    private SkillService service(String yaml) {
        return service(registry, yaml);
    }

    private static SkillService service(BehaviorRegistryImpl registry, String yaml) {
        YamlConfiguration configuration = YamlConfiguration.loadConfiguration(new StringReader(yaml));
        SkillService service = new SkillService(registry, null);
        service.loadSkills(configuration.getConfigurationSection("skills"));
        return service;
    }

    private static BehaviorContext context() {
        return BehaviorContext.execution(null, null, 1, -1, 0, "test", null,
                null, List.of(), null);
    }

    @Test
    void conditionSkillReceivesTemporaryParametersAndDeterminesTheResult() {
        List<String> observed = new ArrayList<>();
        registry.registerAction("capture-parameter", context -> observed.add(
                Placeholders.resolve("<skill.hello>", context)));
        SkillService service = service("""
                skills:
                  check:
                    on-decision:
                      - capture-parameter
                      - determine-condition{det=true}
                  caller:
                    require:
                      - metaskillcondition{skill=check;hello=ciao}
                """);
        BehaviorContext context = context();

        assertTrue(service.castSkill("caller", context), service.warnings().toString());
        assertEquals(List.of("ciao"), observed);
        assertTrue(context.skillTree().orElseThrow().variable("hello").isEmpty(),
                "condition arguments must not leak into the caller's skill tree");
    }

    @Test
    void determineConditionWithSelfTargeterUsesTheManagedCaster() {
        AtomicInteger determined = new AtomicInteger();
        registry.registerAction("record-determination", ignored -> determined.incrementAndGet());
        SkillService service = service("""
                skills:
                  check:
                    on-decision:
                      - record-determination
                      - determine-condition{det=true} @self
                  caller:
                    require:
                      - metaskill-condition{skill=check}
                """);

        var caster = mock(org.bukkit.entity.LivingEntity.class);
        when(caster.isValid()).thenReturn(true);
        when(caster.isDead()).thenReturn(false);
        var instance = mock(dev.helstera.api.instance.ModelInstance.class);
        when(instance.isValid()).thenReturn(true);
        when(instance.baseEntity()).thenReturn(Optional.of(caster));
        when(instance.instanceId()).thenReturn(73);
        when(instance.executionAudience()).thenReturn(Optional.empty());
        BehaviorContext context = BehaviorContext.execution(instance, null, 1.0, -1, 0,
                "test", null, caster, List.of(), null);

        assertTrue(service.castSkill("caller", context), service.warnings().toString());
        assertEquals(1, determined.get(), service.warnings().toString());
    }

    @Test
    void determineModesComposeInsideTheirOwnNestedEvaluationFrame() {
        SkillService service = service("""
                skills:
                  inner:
                    on-decision:
                      - determine-condition{det=true}
                  check:
                    require:
                      - skillcondition{s=inner}
                    on-decision:
                      - determine-condition{det=false;mode=SET}
                      - determine-condition{det=true;mode=OR}
                  caller:
                    require:
                      - metaskill-condition{skill=check}
                """);

        assertTrue(service.castSkill("caller", context()), service.warnings().toString());

        SkillService andService = service(new BehaviorRegistryImpl(), """
                skills:
                  check:
                    on-decision:
                      - determine-condition{det=true;mode=AND}
                  caller:
                    require:
                      - metaskill-condition{skill=check}
                """);
        assertTrue(andService.castSkill("caller", context()), andService.warnings().toString());

        SkillService notService = service(new BehaviorRegistryImpl(), """
                skills:
                  check:
                    on-decision:
                      - determine-condition{det=false;mode=NOT}
                  caller:
                    require:
                      - metaskill-condition{skill=check}
                """);
        assertTrue(notService.castSkill("caller", context()), notService.warnings().toString());
    }

    @Test
    void absentDeterminationAndRecursiveConditionFailClosed() {
        AtomicInteger called = new AtomicInteger();
        registry.registerAction("probe", context -> called.incrementAndGet());
        SkillService service = service("""
                skills:
                  empty:
                    on-decision: [probe]
                  loop:
                    require:
                      - metaskill-condition{skill=loop}
                    on-decision:
                      - determine-condition{det=true}
                  caller:
                    require:
                      - metaskill-condition{skill=empty}
                """);

        assertFalse(service.castSkill("caller", context()));
        assertEquals(1, called.get(), "the condition skill runs, but no decision means false");
        assertNull(service.bindCondition("metaskill-condition{skill=missing}"));
        assertTrue(service.warnings().stream().anyMatch(warning -> warning.contains("循环引用")));
    }

    @Test
    void deferredConditionSkillsAreRejectedBeforeSchedulingSideEffects() {
        AtomicInteger scheduled = new AtomicInteger();
        SkillCatalog.taskScheduler((task, delay) -> {
            scheduled.incrementAndGet();
            return true;
        });
        try {
            SkillService service = service("""
                    skills:
                      check:
                        on-decision:
                          - determine-condition{det=true;delay=1}
                      caller:
                        require:
                          - metaskill-condition{skill=check}
                    """);

            assertFalse(service.castSkill("caller", context()));
            assertEquals(0, scheduled.get());
        } finally {
            SkillCatalog.taskScheduler(null);
        }
    }

    @Test
    void invalidArgumentsAndUnknownActionsAreRejected() {
        SkillService service = service("""
                skills:
                  check:
                    on-decision: [determine-condition]
                  caller:
                    require:
                      - metaskill-condition{skill=check;skill=other}
                """);

        assertFalse(service.castSkill("caller", context()));
        assertNull(service.bindAction("determine-condition{det=maybe}"));
        assertNull(service.bindCondition("metaskill-condition{skill=check;delay=2}"));
        assertNotNull(service.bindAction("determine-condition{determination=false;mode=OR}"));
    }
}
