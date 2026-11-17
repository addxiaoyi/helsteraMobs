package dev.helstera.ai.skill;

import dev.helstera.api.behavior.BehaviorContext;
import dev.helstera.api.instance.ModelInstance;
import java.util.List;
import java.util.Optional;
import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NumericPlaceholderRoundingTest {
    private final LivingEntity caster = mock(LivingEntity.class);
    private final ModelInstance instance = mock(ModelInstance.class);
    private final BehaviorContext context = BehaviorContext.of(instance, caster, 0.5, 1, 0, "test");

    NumericPlaceholderRoundingTest() {
        when(instance.isValid()).thenReturn(true);
        when(instance.baseEntity()).thenReturn(Optional.of(caster));
        when(instance.location()).thenReturn(new Location(null, -3.125, 64, 0));
        when(caster.getHealth()).thenReturn(3.125);
        when(caster.getMaxHealth()).thenReturn(20.0);
    }

    @Test
    void roundsHalfUpWithAliasesDefaultNegativeAndDisplayOverride() {
        for (String option : List.of("rounding", "round", "r")) {
            assertEquals("3.13", Placeholders.resolve("<caster.hp{" + option + "=2}>", context));
            assertEquals("-3.13", Placeholders.resolve("<caster.loc.x{" + option + "=2}>", context));
        }
        assertEquals("3.13", Placeholders.resolve("<c.h{}>", context));
        assertEquals("15.63", Placeholders.resolve("<caster.php{r=2}>", context));
        assertEquals("3", Placeholders.resolve("<caster.hp{r=0}>", context));
        assertEquals("-3", Placeholders.resolve("<caster.loc.x{r=0}>", context));
        assertEquals("3.125", Placeholders.resolve("<caster.hp{r=-1}>", context));
        assertEquals("3.13", Placeholders.resolveDisplay("<caster.hp{r=2}>", context));
        assertEquals("3.125", Placeholders.resolveDisplay("<caster.hp{r=-1}>", context));
        assertEquals("20.0", Placeholders.resolve("<caster.mhp{r=2}>", context));
        when(caster.getHealth()).thenReturn(2.5);
        assertEquals("3", Placeholders.resolve("<caster.hp{r=0}>", context));
        when(instance.location()).thenReturn(new Location(null, -2.5, 64, 0));
        assertEquals("-3", Placeholders.resolve("<caster.loc.x{r=0}>", context));
    }

    @Test
    void integerRoundingMatchesNativeSaturationAndInvalidValuesStayUnresolved() {
        when(caster.getHealth()).thenReturn(1e100);
        assertEquals("2147483647", Placeholders.resolve("<caster.hp{r=0}>", context));
        when(instance.location()).thenReturn(new Location(null, -1e100, 64, 0));
        assertEquals("-2147483648", Placeholders.resolve("<caster.loc.x{r=0}>", context));
        when(caster.getHealth()).thenReturn(Double.NaN);
        for (String source : List.of("<caster.hp{r=2}>", "<caster.name{r=2}>", "<caster.hp{r=341}>",
                "<caster.hp{r=2;round=3}>", "<skill.var.amount{r=2}>", "<unknown{r=2}>")) {
            assertEquals(source, Placeholders.resolve(source, context));
            assertEquals(-9, Placeholders.resolveNumber(source, context, -9));
        }
        assertEquals("<caster.hp{r=2}>", Placeholders.resolve("<caster.hp{r=2}>", null));
    }
}
