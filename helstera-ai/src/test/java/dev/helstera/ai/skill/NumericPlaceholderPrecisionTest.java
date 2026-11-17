package dev.helstera.ai.skill;

import dev.helstera.ai.BehaviorRegistryImpl;
import dev.helstera.api.behavior.BehaviorContext;
import dev.helstera.api.behavior.SkillTreeContext;
import dev.helstera.api.instance.ModelInstance;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class NumericPlaceholderPrecisionTest {
    private final LivingEntity caster = mock(LivingEntity.class);
    private final LivingEntity target = mock(LivingEntity.class);
    private final LivingEntity second = mock(LivingEntity.class);
    private final ModelInstance instance = mock(ModelInstance.class);
    private final World world = mock(World.class);
    private final Location origin = new Location(world, -12.3125, 64.0625, 8.875);
    private final SkillTreeContext tree = new SkillTreeContext(target, List.of(target, second), origin);
    private final BehaviorContext context = BehaviorContext.continueExecution(instance, target, 0.99,
            1.23456789, 0, "precision", null, target, List.of(target, second), origin, tree);
    private final SkillService skills = new SkillService(new BehaviorRegistryImpl(), null);

    NumericPlaceholderPrecisionTest() {
        when(instance.isValid()).thenReturn(true);
        when(instance.baseEntity()).thenReturn(Optional.of(caster));
        when(instance.location()).thenReturn(origin);
        for (var entity : List.of(caster, target, second)) {
            when(entity.isValid()).thenReturn(true);
            when(entity.getWorld()).thenReturn(world);
            when(entity.getLocation()).thenReturn(origin.clone().add(1.125, 0.25, 0.0625));
        }
        when(caster.getHealth()).thenReturn(7.125);
        when(caster.getMaxHealth()).thenReturn(17.25);
        when(caster.getAbsorptionAmount()).thenReturn(0.0125);
        when(target.getHealth()).thenReturn(3.125);
        when(target.getMaxHealth()).thenReturn(23.75);
        when(target.getAbsorptionAmount()).thenReturn(0.25);
        when(second.getHealth()).thenReturn(4.5);
        when(second.getMaxHealth()).thenReturn(12.5);
        when(second.getAbsorptionAmount()).thenReturn(0.125);
    }

    private void run(String source) {
        String key = skills.bindAction(source);
        assertNotNull(key, source + skills.warnings());
        assertTrue(skills.runAction(key, context));
    }

    @Test
    void officialHealthAliasesUseScopedRawHealthAndLivePercentNotContextSnapshot() {
        for (String key : List.of("caster.health", "caster.hp", "caster.thp", "c.h", "mob.hp")) {
            assertEquals(7.125, Placeholders.resolveNumber("<" + key + ">", context, -1), key);
        }
        for (String key : List.of("target.health", "target.hp", "target.thp", "target.hp.raw", "player.hp")) {
            assertEquals(3.125, Placeholders.resolveNumber("<" + key + ">", context, -1), key);
        }
        for (String key : List.of("caster.healthpercent", "caster.percenthealth", "caster.php", "c.ph", "caster.hp.percent")) {
            assertEquals(100 * (7.125 / 17.25), Placeholders.resolveNumber("<" + key + ">", context, -1), key);
        }
        for (String key : List.of("target.healthpercent", "target.percenthealth", "target.php", "target.hp.percent")) {
            assertEquals(100 * (3.125 / 23.75), Placeholders.resolveNumber("<" + key + ">", context, -1), key);
        }
        for (String key : List.of("caster.mhp", "caster.maxhealth", "c.mh")) {
            assertEquals(17.25, Placeholders.resolveNumber("<" + key + ">", context, -1), key);
            assertTrue(Placeholders.knownKeys().contains(key));
        }
    }

    @Test
    void defaultPrecisionCoversHealthCoordinatesDistanceDeltasAndAttributes() {
        var armor = mock(AttributeInstance.class);
        when(target.getAttribute(Attribute.GENERIC_ARMOR)).thenReturn(armor);
        when(armor.getValue()).thenReturn(7.0625);
        for (var entry : Map.of("target.absorption", 0.25, "caster.absorb", 0.0125,
                "caster.loc.x", -12.3125, "target.loc.y", 64.3125,
                "caster.distance", 1.23456789, "target.distance", 1.23456789,
                "caster.dx", 1.125, "caster.dy", 0.25, "target.armor", 7.0625).entrySet()) {
            assertEquals(entry.getValue(), Placeholders.resolveNumber("<" + entry.getKey() + ">", context, -1));
        }
        assertEquals("0.25", Placeholders.resolve("<target.absorption>", context));
        assertEquals("0.3", Placeholders.resolveDisplay("<target.absorption>", context));
        assertEquals("-12.3", Placeholders.resolveDisplay("<caster.loc.x>", context));
        assertEquals("3.1", Placeholders.resolveDisplay("<target.hp>", context));
        assertEquals(1e200, Placeholders.resolveNumber("1e200", context, -1));
    }

    @Test
    void numericMechanicsAndPerTargetFractionsConsumeExactValues() {
        run("heal{a=<caster.absorb>} @Target");
        verify(target).setHealth(3.1375);
        run("damage{a=<target.hp>} @Target");
        verify(target).damage(3.125, caster);
        run("hp{m=<target.absorption>} @Targets");
        verify(target).setHealth(9.0625);
        verify(second).setHealth(6.0625);
        verify(caster, never()).setHealth(anyDouble());
        verify(second, never()).damage(anyDouble(), any(Entity.class));
    }

    @Test
    void invalidDataStaysUnresolvedAndCannotBecomeAZeroHealthMutation() {
        when(target.getAbsorptionAmount()).thenReturn(Double.POSITIVE_INFINITY);
        when(target.getHealth()).thenReturn(Double.NaN);
        for (String key : List.of("target.absorption", "target.health", "target.hp", "target.php")) {
            assertEquals("<" + key + ">", Placeholders.resolve("<" + key + ">", context));
            assertEquals(-1, Placeholders.resolveNumber("<" + key + ">", context, -1));
        }
        run("sethealth{a=<target.absorption>} @Target");
        run("damage{a=<target.absorption>} @Target");
        verify(target, never()).setHealth(anyDouble());
        verify(target, never()).damage(anyDouble(), any(Entity.class));
        for (String invalid : List.of("NaN", "Infinity", "-Infinity", "1e999")) {
            assertEquals(-1, Placeholders.resolveNumber(invalid, context, -1));
        }
        when(target.getHealth()).thenReturn(3.125);
        when(target.getMaxHealth()).thenReturn(0.0);
        assertEquals("<target.php>", Placeholders.resolve("<target.php>", context));
    }

    @Test
    void variablesRandomAndTextDoNotAccidentallyUseCompactFormatting() {
        tree.setVariable("Amount", "0.0123456789");
        assertEquals("0.0123456789", Placeholders.resolve("<skill.var.Amount>", context));
        assertEquals("0.0123456789", Placeholders.resolveDisplay("<skill.var.Amount>", context));
        assertEquals("name=<unknown> health=3.125", Placeholders.resolve("name=<unknown> health=<target.hp>", context));
        assertEquals(0.0125, Placeholders.resolveNumber("<random.0.0125to0.0125>", context, -1));
        for (int i = 0; i < 100; i++) {
            double number = Placeholders.resolveNumber("<random.0.123to0.124>", context, -1);
            assertTrue(number >= 0.123 && number <= 0.124);
        }
        assertNull(Placeholders.resolveDisplay(null, context));
    }
}
