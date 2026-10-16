package dev.helstera.plugin;

import dev.helstera.ai.skill.SkillTriggers;
import dev.helstera.api.instance.SpawnOptions;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PreventVanillaDamageTest {

    @Test
    void zeroesVanillaDamageAndLeavesDamageTriggerEligible() throws Exception {
        var normal = SpawnOptions.defaults();
        var guarded = SpawnOptions.defaults().preventVanillaDamage(true);

        assertEquals(6.5, HelsteraPlugin.vanillaAttackDamage(normal, 6.5), 1e-9);
        assertEquals(0.0, HelsteraPlugin.vanillaAttackDamage(guarded, 6.5), 1e-9);
        EventHandler handler = SkillTriggers.class.getMethod("onDamage", EntityDamageByEntityEvent.class)
                .getAnnotation(EventHandler.class);
        assertNotNull(handler);
        assertEquals(EventPriority.HIGHEST, handler.priority());
        assertTrue(handler.ignoreCancelled());
    }
}
