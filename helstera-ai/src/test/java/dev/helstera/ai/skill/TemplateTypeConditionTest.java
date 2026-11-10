package dev.helstera.ai.skill;

import dev.helstera.ai.BehaviorRegistryImpl;
import dev.helstera.api.behavior.BehaviorContext;
import org.bukkit.entity.LivingEntity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class TemplateTypeConditionTest {

    @AfterEach
    void clearRuntimeHooks() {
        SkillExtras.resetRuntimeHooks();
    }

    @Test
    void matchesAnyRequestedTemplateOnTheConditionTarget() {
        LivingEntity target = mock(LivingEntity.class);
        SkillExtras.mobTemplateLookup(entity -> entity == target
                ? List.of("mythic_undead", "mythic_armored") : List.of());
        BehaviorContext context = BehaviorContext.of(null, target, 1.0, -1, 0, "IDLE");
        BehaviorRegistryImpl registry = new BehaviorRegistryImpl();
        SkillService skills = new SkillService(registry, null);

        for (String source : List.of(
                "TemplateType templates=undead,armored",
                "Template t=undead,missing",
                "instanceOf template=mythic_armored")) {
            String key = skills.bindCondition(source);
            assertTrue(key != null && registry.testCondition(key, context), source);
        }
        assertFalse(registry.testCondition(skills.bindCondition("template-type t=missing"), context));
    }

    @Test
    void missingContextAndUnavailableLookupFailClosed() {
        LivingEntity target = mock(LivingEntity.class);
        BehaviorContext context = BehaviorContext.of(null, target, 1.0, -1, 0, "IDLE");
        BehaviorRegistryImpl registry = new BehaviorRegistryImpl();
        SkillService skills = new SkillService(registry, null);
        String key = skills.bindCondition("template-type templates=undead");

        assertFalse(registry.testCondition(key, context));
        SkillExtras.mobTemplateLookup(entity -> List.of());
        assertFalse(registry.testCondition(key, context));
        assertFalse(registry.testCondition(key,
                BehaviorContext.of(null, null, 1.0, -1, 0, "IDLE")));
    }

    @Test
    void rejectsUnknownDuplicateAndEmptyTemplateArguments() {
        var factory = SkillExtras.conditions().get("template-type");
        for (List<String> args : List.of(
                List.<String>of(),
                List.of("templates=undead", "t=armored"),
                List.of("unknown=undead"),
                List.of("templates=undead,"),
                List.of("templates="))) {
            assertThrows(IllegalArgumentException.class, () -> factory.create(args), args.toString());
        }
    }
}
