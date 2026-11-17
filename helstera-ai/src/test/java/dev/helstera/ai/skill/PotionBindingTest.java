package dev.helstera.ai.skill;

import dev.helstera.ai.BehaviorRegistryImpl;
import dev.helstera.api.behavior.BehaviorContext;
import org.bukkit.entity.LivingEntity;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PotionBindingTest {
    @Test
    void keepsNamedArgumentsAndRemovesExecutionMetadata() {
        var service = new SkillService(new BehaviorRegistryImpl(), null);
        for (String spec : List.of("potion", "potion{}", "potion{delay=0;repeat=0;repeatinterval=0}",
                "effect{hasIcon=false;lvl=2;ow=true;d=40;ambientparticles=true;hasParticles=false;t=SPEED}",
                "effect-self{effect=SPEED;amplifier=2;show-particles=false;show-icon=false}",
                "effect-targets SPEED 20 1 true false false true @Target")) {
            assertNotNull(service.bindAction(spec), spec + ": " + service.warnings());
        }
    }

    @Test
    void rejectsMalformedAndDuplicateArgumentsBeforeMapLoss() {
        var service = new SkillService(new BehaviorRegistryImpl(), null);
        for (String args : List.of("type=SPEED;type=POISON", "t=SPEED;type=POISON", "level=1;lvl=2",
                "type=SPEED;unknown=true", "type=", "duration=0", "level=-1", "force=maybe",
                "duration=NaN", "level=Infinity", "type=SPEED;POISON", "type=SPEED;;duration=20")) {
            assertNull(service.bindAction("potion{" + args + "}"), args);
        }
        assertFalse(service.warnings().isEmpty());
        assertNull(service.bindAction("potion @Target SPEED"));
        assertNull(service.bindAction("potion{type=SPEED} @Target garbage"));
    }

    @Test
    void unresolvedOrInvalidDynamicValuesDoNotTouchEntities() {
        var service = new SkillService(new BehaviorRegistryImpl(), null);
        var target = mock(LivingEntity.class);
        when(target.isValid()).thenReturn(true);
        var context = BehaviorContext.continueExecution(null, target, 1.0, -1, 0,
                "skill", null, target, List.of(target), null, null);
        for (String args : List.of("type=<missing.type>", "duration=<missing.ticks>", "level=<target.hp.raw>")) {
            when(target.getHealth()).thenReturn(-1.0);
            String action = service.bindAction("potion{" + args + "}");
            assertNotNull(action);
            assertTrue(service.runAction(action, context));
        }
        verify(target, never()).addPotionEffect(any());
        verify(target, never()).removePotionEffect(any());
    }
}
