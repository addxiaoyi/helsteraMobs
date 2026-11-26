package dev.helstera.ai.skill;

import dev.helstera.ai.BehaviorRegistryImpl;
import dev.helstera.api.behavior.BehaviorContext;
import dev.helstera.api.instance.ModelInstance;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.List;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DisplayTransformationBindingTest {
    private final World world = mock(World.class);
    private final SkillService service = new SkillService(new BehaviorRegistryImpl(), null);
    private <T extends Entity> T entity(Class<T> type, int height) {
        T entity = mock(type);
        when(entity.isValid()).thenReturn(true);
        when(entity.getLocation()).thenReturn(new Location(world, 1, height, 3));
        return entity;
    }
    private Display display() {
        var display = entity(Display.class, 12);
        when(display.getTransformation()).thenReturn(seed());
        when(display.getInterpolationDelay()).thenReturn(7);
        return display;
    }
    private Transformation seed() {
        return new Transformation(new Vector3f(1,2,3), new Quaternionf(1,2,3,4),
                new Vector3f(2,3,4), new Quaternionf(-2,3,1,2));
    }
    private BehaviorContext context(Entity caster, Entity recipient) {
        var instance = mock(ModelInstance.class);
        var location = caster.getLocation();
        when(instance.isValid()).thenReturn(true);
        when(instance.baseEntity()).thenReturn(Optional.of(caster));
        when(instance.location()).thenReturn(location);
        return BehaviorContext.execution(instance, null, 1, 0, 0, "test", null, recipient, List.of(), location);
    }
    private Transformation run(String source, Display recipient) {
        String key = service.bindAction(source); assertNotNull(key);
        assertTrue(service.runAction(key, context(entity(LivingEntity.class, 75), recipient)));
        var captured = ArgumentCaptor.forClass(Transformation.class);
        var order = inOrder(recipient);
        order.verify(recipient).setInterpolationDelay(7);
        order.verify(recipient).setTransformation(captured.capture());
        verify(recipient, never()).setInterpolationDuration(anyInt());
        return captured.getValue();
    }

    @Test void allAliasesReachNonlivingRecipientsWithTargetScopedValues() {
        for (String alias : List.of("displaytransformation", "settransformation", "transformation", "display-transformation")) {
            var selected = display();
            var result = run(alias + "{a=add;value=1,<target.l.y>,3} @trigger", selected);
            assertEquals(new Vector3f(2,14,6), result.getTranslation());
            assertEquals(seed().getScale(), result.getScale());
            assertEquals(seed(), selected.getTransformation()); // Returned snapshot not mutated.
        }
    }

    @Test void quaternionProductAndQuotientAreOrderedAndNotComponentWise() {
        var product = run("transformation{type=LEFT_ROTATION;a=MULTIPLY;value=2,1,-1,3} @trigger", display());
        assertEquals(new Quaternionf(6,17,2,11), product.getLeftRotation());
        assertEquals(seed().getRightRotation(), product.getRightRotation());
        var quotient = run("transformation{type=LEFT_ROTATION;a=DIVIDE;value=2,1,-1,3} @trigger", display());
        var q = quotient.getLeftRotation();
        assertArrayEquals(new float[]{0,-5f/15,16f/15,13f/15}, new float[]{q.x,q.y,q.z,q.w}, .00001f);
    }

    @Test void noTargetUsesCasterDespiteAnInheritedTargetAndExplicitLocationIsInert() {
        var caster = display(); var inherited = entity(LivingEntity.class, 22);
        var context = context(caster, inherited).withTargetEntity(inherited);
        var key = service.bindAction("transformation{value=4,5,6}"); assertNotNull(key);
        assertTrue(service.runAction(key, context)); verify(caster).setTransformation(any());
        clearInvocations(caster);
        key = service.bindAction("transformation{value=4,5,6} @origin"); assertNotNull(key);
        assertTrue(service.runAction(key, context)); verify(caster, never()).setTransformation(any());
    }

    @Test void invalidArityUnknownSelectorsAndNonfiniteResultsCannotPartiallyWrite() {
        assertNull(service.bindAction("transformation{value=1,2} @trigger"));
        assertNull(service.bindAction("transformation @unknown"));
        assertNull(service.bindAction("transformation 1,2,3 @trigger"));
        var selected = display();
        String key = service.bindAction("transformation{a=DIVIDE;value=1,0,1} @trigger"); assertNotNull(key);
        assertTrue(service.runAction(key, context(entity(LivingEntity.class, 75), selected)));
        verify(selected, never()).setTransformation(any());
        verify(selected, never()).setInterpolationDelay(anyInt());
    }
}
