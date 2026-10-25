package dev.helstera.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class AiManagerLifecycleAuditTest {
    @Test
    @DisplayName("AI manager retains removable event-bus listeners for explicit cleanup")
    void lifecycleListenersAreStoredAndUnregistered() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/dev/helstera/ai/AiManager.java"));
        assertTrue(source.contains("private java.util.function.Consumer<AnimationMarkerEvent> animationMarkerListener"));
        assertTrue(source.contains("private java.util.function.Consumer<ModelRemoveEvent> modelRemoveListener"));
        assertTrue(source.contains("bus.unregister(AnimationMarkerEvent.class, animationMarkerListener)"));
        assertTrue(source.contains("bus.unregister(ModelRemoveEvent.class, modelRemoveListener)"));
        assertTrue(source.contains("detach(e.instance().instanceId())"));
        assertTrue(source.contains("for (Integer instanceId : java.util.List.copyOf(controlled.keySet()))"));
        assertTrue(source.contains("detach(instanceId)"));
    }
}
