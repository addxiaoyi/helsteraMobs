package dev.helstera.ai.skill;

import dev.helstera.ai.BehaviorRegistryImpl;
import dev.helstera.api.behavior.BehaviorContext;
import dev.helstera.api.behavior.SkillTreeContext;
import dev.helstera.api.instance.ModelInstance;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class SetSpeedActionTest {
    private final SkillService skills = new SkillService(new BehaviorRegistryImpl(), null);
    private final LivingEntity caster = mock(LivingEntity.class);
    private final LivingEntity target = mock(LivingEntity.class);
    private final Player player = mock(Player.class);
    private final AttributeInstance walk = mock(AttributeInstance.class);
    private final AttributeInstance fly = mock(AttributeInstance.class);
    private final ModelInstance instance = mock(ModelInstance.class);
    private final Location origin = new Location(mock(World.class), 0, 64, 0);
    private final BehaviorContext context = BehaviorContext.continueExecution(instance, target, 1, 2, 0,
            "speed", null, target, List.of(target, player), origin,
            new SkillTreeContext(target, List.of(target, player), origin));

    SetSpeedActionTest() {
        when(instance.isValid()).thenReturn(true);
        when(instance.baseEntity()).thenReturn(Optional.of(caster));
        when(instance.location()).thenReturn(origin);
        for (var entity : List.of(caster, target, player)) {
            when(entity.isValid()).thenReturn(true);
            when(entity.getLocation()).thenReturn(origin);
            when(entity.getWorld()).thenReturn(origin.getWorld());
        }
        when(target.getAttribute(Attribute.GENERIC_MOVEMENT_SPEED)).thenReturn(walk);
        when(target.getAttribute(Attribute.GENERIC_FLYING_SPEED)).thenReturn(fly);
        when(walk.getBaseValue()).thenReturn(0.7);
        when(walk.getValue()).thenReturn(0.9);
    }

    private void run(String source) {
        String action = skills.bindAction(source);
        assertNotNull(action, source + skills.warnings());
        assertTrue(skills.runAction(action, context));
    }

    @Test
    void absoluteMobBasesAreIdempotentAndDoNotScaleCurrentAttribute() {
        run("setspeed{m=2} @Target");
        run("set-speed{speed=2;type=WALK} @Target");
        verify(walk, times(2)).setBaseValue((double) 0.4f);
        verify(walk, never()).getBaseValue();
        verify(walk, never()).getValue();
        run("setspeed{t=FLYING;m=3} @Target");
        verify(fly).setBaseValue((double) (3f * 0.1f));
        verify(caster, never()).getAttribute(any());
        verify(player, never()).setWalkSpeed(anyFloat());
    }

    @Test
    void playerApisUseDistinctWalkAndFlyDefaultsAndPreserveNegativeValues() {
        run("setspeed @Targets");
        verify(player).setWalkSpeed(0.2f);
        run("setspeed{type=FLY;s=2} @Targets");
        verify(player).setFlySpeed(0.2f);
        run("setspeed{m=-1} @Targets");
        verify(player).setWalkSpeed(-0.2f);
        verify(player, never()).getAttribute(any());
    }

    @Test
    void invalidPlayerRangeAndMissingMobAttributeSkipOnlyThatRecipient() {
        run("setspeed{m=6} @Targets");
        verify(player, never()).setWalkSpeed(anyFloat());
        verify(walk).setBaseValue((double) (6f * 0.2f));
        when(target.getAttribute(Attribute.GENERIC_FLYING_SPEED)).thenReturn(null);
        run("setspeed{t=FLY} @Targets");
        verify(fly, never()).setBaseValue(anyDouble());
        verify(player).setFlySpeed(0.1f);
    }

    @Test
    void locationDeadAndMalformedTargetsCannotMutateSpeed() {
        var action = SkillExtras.actions().get("set-speed").create(List.of());
        action.accept(context.withLocationTarget(origin));
        when(target.isDead()).thenReturn(true);
        when(player.isValid()).thenReturn(false);
        action.accept(context);
        verify(walk, never()).setBaseValue(anyDouble());
        verify(caster, never()).getAttribute(any());
        verify(player, never()).setWalkSpeed(anyFloat());
        for (String source : List.of("setspeed{s=1;s=2}", "setspeed{s=1;m=2}",
                "setspeed{s=1;;t=FLY}", "setspeed{s=NaN}", "setspeed @Target 2",
                "setspeed{m=<skill.var.speed>}", "setspeed{duration=20}")) {
            assertNull(skills.bindAction(source), source);
        }
    }
}
