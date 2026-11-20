package dev.helstera.ai.skill;

import dev.helstera.ai.AiManager;
import dev.helstera.ai.AiProfile;
import dev.helstera.ai.BehaviorRegistryImpl;
import dev.helstera.api.event.HelsteraEventBus;
import dev.helstera.api.event.ModelRemoveEvent;
import dev.helstera.api.instance.ModelInstance;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DespawnTriggerTest {
    @ParameterizedTest
    @CsvSource({"natural_despawn,true,true", "manual,true,true", "plugin_shutdown,true,true", "chunk_unload,true,true", "world_unload,true,true",
            "model_unload,true,true", "persistent_chunk_unload,false,false",
            "persistent_world_unload,false,false", "base_entity_removed,false,true"})
    void despawnDispatchesOnlyForPermanentRemoval(String cause, boolean despawn, boolean remove) {
        var bus = new HelsteraEventBus();
        var plugin = mock(Plugin.class, RETURNS_DEEP_STUBS);
        var ai = mock(AiManager.class);
        var instance = mock(ModelInstance.class);
        var entity = mock(org.bukkit.entity.LivingEntity.class);
        when(instance.baseEntity()).thenReturn(java.util.Optional.of(entity));
        when(instance.instanceId()).thenReturn(17); when(instance.isValid()).thenReturn(true);
        var profile = new AiProfile("probe");
        var actions = new ArrayList<String>();
        var registry = new BehaviorRegistryImpl();
        for(String event:List.of("on-despawn","on-death","on-remove")) {
            var spec = new AiProfile.TriggerSpec(); spec.actions.add(event);
            profile.triggers.put(event,spec); registry.registerAction(event,ctx->{
                actions.add(event);
                if(event.equals("on-despawn"))assertSame(entity,ctx.trigger().orElseThrow());
                else assertTrue(ctx.trigger().isEmpty());
            });
        }
        when(ai.profileOf(17)).thenReturn(profile);
        var triggers = new SkillTriggers(plugin,ai,registry,bus,Logger.getAnonymousLogger(),null);
        try {
            triggers.start();
            bus.post(new ModelRemoveEvent(instance,cause));
            var expected = new ArrayList<String>();
            if(despawn)expected.add("on-despawn"); if(remove)expected.add("on-remove");
            assertEquals(expected,actions);
            actions.clear(); when(entity.isDead()).thenReturn(true);
            bus.post(new ModelRemoveEvent(instance,"manual"));
            assertEquals(List.of("on-remove"),actions,"delayed death cleanup must not dispatch onDespawn");
            assertEquals(SkillTrigger.DESPAWN,SkillTrigger.of("onDespawn"));
        } finally { triggers.stop(); SkillExtras.resetRuntimeHooks(); }
    }
}
