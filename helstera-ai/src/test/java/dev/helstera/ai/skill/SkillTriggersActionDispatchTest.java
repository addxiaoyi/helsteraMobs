package dev.helstera.ai.skill;

import dev.helstera.ai.BehaviorRegistryImpl;
import dev.helstera.api.behavior.BehaviorContext;
import dev.helstera.api.event.HelsteraEventBus;
import dev.helstera.api.instance.ModelInstance;
import org.bukkit.entity.LivingEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SkillTriggersActionDispatchTest {

    @Test
    @DisplayName("registered signal mechanics are executed instead of parsed as trigger skill references")
    void registeredSignalActionUsesBehaviorRegistry() {
        var registry = new BehaviorRegistryImpl();
        var skills = new SkillService(registry, null);
        var instance = mock(ModelInstance.class);
        when(instance.isValid()).thenReturn(true);
        when(instance.instanceId()).thenReturn(17);
        var context = BehaviorContext.of(instance, (LivingEntity) null, 1.0, -1, 0, "on-timer");
        var signals = new SkillSignals();
        var payload = new AtomicReference<String>();
        var source = new AtomicReference<Integer>();
        SkillSignals previous = SkillSignals.global();
        SkillSignals.resetGlobal(signals);
        signals.subscribe(null, "timer-probe", (name, value, sourceId) -> {
            payload.set(value);
            source.set(sourceId);
        });
        try {
            String action = skills.bindAction(
                    "signal{signal=timer-probe;payload=timer-fired;global=true}");
            assertNotNull(action);
            var triggers = new SkillTriggers(mock(org.bukkit.plugin.Plugin.class), null, registry,
                    new HelsteraEventBus(), Logger.getAnonymousLogger(), skills);

            triggers.runActions(SkillTrigger.TIMER, List.of(action), context);

            assertEquals("timer-fired", payload.get());
            assertEquals(17, source.get());
        } finally {
            SkillSignals.resetGlobal(previous);
        }
    }
    @Test
    void suspendedRemovalUsesCapturedProfileButOrdinaryInvalidDispatchRemainsBlocked() {
        var registry = new BehaviorRegistryImpl();
        var calls = new java.util.ArrayList<String>();
        var captured = new AtomicReference<ModelInstance>();
        registry.registerAction("despawn-test", ctx -> {
            org.junit.jupiter.api.Assertions.assertTrue(ctx.instanceValid());
            captured.set(ctx.instance());
            calls.add(ctx.state());
        });
        registry.registerAction("remove-test", ctx -> calls.add(ctx.state()));
        var profile = new dev.helstera.ai.AiProfile("captured");
        for (String name : List.of("on-despawn", "on-remove")) {
            var spec = new dev.helstera.ai.AiProfile.TriggerSpec();
            spec.actions.add(name.equals("on-despawn") ? "despawn-test" : "remove-test");
            profile.triggers.put(name, spec);
        }
        var instance = mock(ModelInstance.class);
        when(instance.baseEntity()).thenReturn(java.util.Optional.empty());
        when(instance.instanceId()).thenReturn(18);
        var plugin = mock(org.bukkit.plugin.Plugin.class);
        when(plugin.getConfig()).thenReturn(new org.bukkit.configuration.file.YamlConfiguration());
        var triggers = new SkillTriggers(plugin, null, registry,
                new HelsteraEventBus(), Logger.getAnonymousLogger(), null);
        triggers.dispatch(SkillTrigger.DESPAWN, instance, (LivingEntity) null, null);
        assertEquals(List.of(), calls);
        triggers.fireSuspendedRemoval(instance, profile);
        assertEquals(List.of("on-despawn", "on-remove"), calls);
        org.junit.jupiter.api.Assertions.assertFalse(captured.get().isValid());
        org.junit.jupiter.api.Assertions.assertFalse(instance.isValid());
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> captured.get().setScale(2));
        when(instance.isValid()).thenReturn(true);
        triggers.fireSuspendedRemoval(instance, profile);
        assertEquals(2, calls.size());
    }

}
