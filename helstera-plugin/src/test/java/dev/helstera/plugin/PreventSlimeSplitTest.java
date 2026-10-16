package dev.helstera.plugin;

import dev.helstera.api.instance.SpawnOptions;
import org.bukkit.entity.Slime;
import org.bukkit.event.entity.SlimeSplitEvent;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PreventSlimeSplitTest {

    @Test
    void cancelsSplitOnlyWhenTheSpawnOptionIsEnabled() {
        SlimeSplitEvent normal = splitEvent();
        HelsteraPlugin.applySlimeSplitPolicy(normal, SpawnOptions.defaults());
        assertFalse(normal.isCancelled());
        assertEquals(3, normal.getCount());

        SlimeSplitEvent prevented = splitEvent();
        HelsteraPlugin.applySlimeSplitPolicy(prevented, SpawnOptions.defaults().preventSlimeSplit(true));
        assertTrue(prevented.isCancelled());
        assertEquals(3, prevented.getCount());
    }

    private static SlimeSplitEvent splitEvent() {
        Slime slime = (Slime) Proxy.newProxyInstance(Slime.class.getClassLoader(),
                new Class<?>[]{Slime.class}, (proxy, method, args) -> {
                    Class<?> type = method.getReturnType();
                    if (type == boolean.class) return false;
                    if (type == int.class) return 0;
                    if (type == long.class) return 0L;
                    if (type == float.class) return 0f;
                    if (type == double.class) return 0d;
                    if (type == short.class) return (short) 0;
                    if (type == byte.class) return (byte) 0;
                    if (type == char.class) return (char) 0;
                    return null;
                });
        return new SlimeSplitEvent(slime, 3);
    }
}
