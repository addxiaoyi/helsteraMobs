package dev.helstera.api.behavior;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BehaviorContextPowerTest {
    @Test
    void scopedConditionsSharePowerButMechanicsCopyIt() {
        var context = BehaviorContext.of(null, (org.bukkit.entity.Player) null, 1, -1, 0);
        context.withConditionScope(BehaviorContext.ConditionScope.CASTER).multiplySkillPower(2);
        assertEquals(2.0f, context.skillPower());
        var child = context.copyForSkillMechanic();
        child.multiplySkillPower(3);
        assertEquals(6.0f, child.skillPower());
        assertEquals(2.0f, context.skillPower());
        var continuation = BehaviorContext.of(null, (org.bukkit.entity.Player) null, 1, -1, 0)
                .withExecutionAudienceFrom(child);
        continuation.multiplySkillPower(2);
        assertEquals(12.0f, child.skillPower());
        assertEquals(2.0f, context.skillPower());
    }

    @Test
    void nonFiniteMultiplicationDoesNotCorruptExistingMetadata() {
        var context = BehaviorContext.of(null, (org.bukkit.entity.Player) null, 1, -1, 0);
        context.multiplySkillPower(3);
        assertThrows(IllegalArgumentException.class, () -> context.multiplySkillPower(Float.NaN));
        assertThrows(IllegalArgumentException.class, () -> context.multiplySkillPower(Float.MAX_VALUE));
        assertEquals(3.0f, context.skillPower());
    }
}
