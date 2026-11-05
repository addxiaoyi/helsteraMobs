package dev.helstera.ai.skill;

import dev.helstera.api.behavior.BehaviorContext;
import dev.helstera.api.instance.ModelInstance;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Pig;
import org.bukkit.entity.Wolf;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.potion.PotionEffect;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EntityStateConditionTest {

    private static Predicate<BehaviorContext> condition(String name) {
        return SkillExtras.conditions().get(name).create(List.of());
    }

    private static BehaviorContext context(LivingEntity target) {
        BehaviorContext context = mock(BehaviorContext.class);
        when(context.targetEntity()).thenReturn(Optional.ofNullable(target));
        return context;
    }

    @Test
    void mountedChecksWhetherTheCurrentTargetRidesAVehicle() {
        LivingEntity target = mock(LivingEntity.class);
        when(target.isInsideVehicle()).thenReturn(true);

        assertTrue(condition("mounted").test(context(target)));
        when(target.isInsideVehicle()).thenReturn(false);
        assertFalse(condition("mounted").test(context(target)));
        assertFalse(condition("mounted").test(context(null)));
    }

    @Test
    void onGroundUsesTheCasterEntityStateBeforeLocationFallback() {
        ModelInstance instance = mock(ModelInstance.class);
        LivingEntity caster = mock(LivingEntity.class);
        when(instance.isValid()).thenReturn(true);
        when(instance.baseEntity()).thenReturn(Optional.of(caster));
        BehaviorContext context = BehaviorContext.of(instance, (LivingEntity) null,
                1.0, -1, 0, "IDLE");

        when(caster.isOnGround()).thenReturn(true);
        assertTrue(condition("on-ground").test(context));
        when(caster.isOnGround()).thenReturn(false);
        assertFalse(condition("on-ground").test(context));
    }

    @Test
    void liquidConditionsPreferBukkitEntityState() {
        ModelInstance instance = mock(ModelInstance.class);
        LivingEntity caster = mock(LivingEntity.class);
        when(instance.isValid()).thenReturn(true);
        when(instance.baseEntity()).thenReturn(Optional.of(caster));
        BehaviorContext context = BehaviorContext.of(instance, (LivingEntity) null,
                1.0, -1, 0, "IDLE");

        when(caster.isInWater()).thenReturn(true);
        when(caster.isInLava()).thenReturn(false);
        assertTrue(condition("in-water").test(context));
        assertFalse(condition("in-lava").test(context));

        when(caster.isInWater()).thenReturn(false);
        when(caster.isInLava()).thenReturn(true);
        assertFalse(condition("in-water").test(context));
        assertTrue(condition("in-lava").test(context));
    }

    @Test
    void movingRequiresFiniteNonzeroVelocity() {
        LivingEntity target = mock(LivingEntity.class);
        when(target.getVelocity()).thenReturn(new Vector(0.1, 0.0, 0.0));
        assertTrue(condition("moving").test(context(target)));

        when(target.getVelocity()).thenReturn(new Vector());
        assertFalse(condition("moving").test(context(target)));

        when(target.getVelocity()).thenReturn(new Vector(Double.NaN, 0.0, 0.0));
        assertFalse(condition("moving").test(context(target)));
    }

    @Test
    void hasPassengerChecksTheCurrentTarget() {
        LivingEntity target = mock(LivingEntity.class);
        when(target.getPassengers()).thenReturn(List.of(mock(Entity.class)));
        assertTrue(condition("has-passenger").test(context(target)));

        when(target.getPassengers()).thenReturn(List.of());
        assertFalse(condition("has-passenger").test(context(target)));
        assertFalse(condition("has-passenger").test(context(null)));
    }

    @Test
    void isLeashedChecksTheCurrentTarget() {
        Wolf wolf = mock(Wolf.class);
        when(wolf.isLeashed()).thenReturn(true);
        assertTrue(condition("is-leashed").test(context(wolf)));

        when(wolf.isLeashed()).thenReturn(false);
        assertFalse(condition("is-leashed").test(context(wolf)));
        assertFalse(condition("is-leashed").test(context(null)));
    }

    @Test
    void mythicIsChildUsesParentRelationAndIsIndependentFromAge() {
        ModelInstance instance = mock(ModelInstance.class);
        Wolf caster = mock(Wolf.class);
        Pig child = mock(Pig.class);
        PersistentDataContainer data = mock(PersistentDataContainer.class);
        UUID casterId = UUID.randomUUID();
        when(instance.isValid()).thenReturn(true);
        when(instance.baseEntity()).thenReturn(Optional.of(caster));
        when(caster.isValid()).thenReturn(true);
        when(caster.isDead()).thenReturn(false);
        when(caster.getUniqueId()).thenReturn(casterId);
        when(child.isValid()).thenReturn(true);
        when(child.isDead()).thenReturn(false);
        when(child.getUniqueId()).thenReturn(UUID.randomUUID());
        when(child.getPersistentDataContainer()).thenReturn(data);
        when(child.isAdult()).thenReturn(true);
        when(data.get(any(NamespacedKey.class), eq(PersistentDataType.STRING)))
                .thenReturn(casterId.toString());
        BehaviorContext ctx = BehaviorContext.of(instance, (LivingEntity) child,
                1.0, -1, 0, "IDLE");

        assertTrue(condition("is-child").test(ctx));
        assertFalse(condition("is-baby").test(ctx));

        when(data.get(any(NamespacedKey.class), eq(PersistentDataType.STRING))).thenReturn(null);
        when(child.isAdult()).thenReturn(false);
        assertFalse(condition("is-child").test(ctx));
        assertTrue(condition("is-baby").test(ctx));
    }

    @Test
    void targetIsLeashedChecksTheExplicitTarget() {
        Wolf wolf = mock(Wolf.class);
        when(wolf.isLeashed()).thenReturn(true);
        assertTrue(condition("target-is-leashed").test(context(wolf)));
        when(wolf.isLeashed()).thenReturn(false);
        assertFalse(condition("target-is-leashed").test(context(wolf)));
        assertFalse(condition("target-is-leashed").test(context(null)));
    }

    @Test
    void targetIsSneakingChecksNonPlayerLivingEntities() {
        Wolf wolf = mock(Wolf.class);
        when(wolf.isSneaking()).thenReturn(true);
        assertTrue(condition("target-is-sneaking").test(context(wolf)));
        when(wolf.isSneaking()).thenReturn(false);
        assertFalse(condition("target-is-sneaking").test(context(wolf)));
        assertFalse(condition("target-is-sneaking").test(context(null)));
    }

    @Test
    void hasOwnerChecksVanillaTameableOwnership() {
        Wolf wolf = mock(Wolf.class);
        when(wolf.getOwner()).thenReturn(mock(org.bukkit.entity.AnimalTamer.class));
        assertTrue(condition("has-owner").test(context(wolf)));

        when(wolf.getOwner()).thenReturn(null);
        assertFalse(condition("has-owner").test(context(wolf)));
        assertFalse(condition("has-owner").test(context(null)));
    }

    @Test
    void targetOwnerConditionsUseTheExplicitTarget() {
        Wolf wolf = mock(Wolf.class);
        org.bukkit.entity.Player player = mock(org.bukkit.entity.Player.class);
        when(wolf.getOwner()).thenReturn(player);
        assertTrue(condition("target-has-owner").test(context(wolf)));
        assertTrue(condition("target-owner-is-player").test(context(wolf)));
        assertFalse(condition("target-has-owner").test(context(null)));
        assertFalse(condition("target-owner-is-player").test(context(null)));
    }

    @Test
    void ownerIsPlayerDistinguishesPlayerOwners() {
        Wolf wolf = mock(Wolf.class);
        org.bukkit.entity.Player player = mock(org.bukkit.entity.Player.class);
        when(wolf.getOwner()).thenReturn(player);
        assertTrue(condition("owner-is-player").test(context(wolf)));

        when(wolf.getOwner()).thenReturn(null);
        assertFalse(condition("owner-is-player").test(context(wolf)));
        assertFalse(condition("owner-is-player").test(context(null)));
    }

    @Test
    void hasTagChecksExactScoreboardTagOnCurrentTarget() {
        LivingEntity target = mock(LivingEntity.class);
        when(target.getScoreboardTags()).thenReturn(java.util.Set.of("BossDefeated"));
        assertTrue(SkillExtras.conditions().get("has-tag").create(List.of("tag=BossDefeated"))
                .test(context(target)));
        assertFalse(SkillExtras.conditions().get("has-tag").create(List.of("t=Other"))
                .test(context(target)));
        assertFalse(SkillExtras.conditions().get("has-tag").create(List.of(""))
                .test(context(target)));
        assertFalse(SkillExtras.conditions().get("has-tag").create(List.of("BossDefeated"))
                .test(context(null)));
    }

    @Test
    void targetHasTagUsesTheExplicitTargetEntity() {
        LivingEntity target = mock(LivingEntity.class);
        when(target.getScoreboardTags()).thenReturn(java.util.Set.of("TargetMarked"));

        assertTrue(SkillExtras.conditions().get("target-has-tag")
                .create(List.of("tag=TargetMarked")).test(context(target)));
        assertFalse(SkillExtras.conditions().get("target-has-tag")
                .create(List.of("t=Other")).test(context(target)));
        assertFalse(SkillExtras.conditions().get("target-has-tag")
                .create(List.of("TargetMarked")).test(context(null)));
    }

    @Test
    void collidableAndOffhandConditionsReadTheActiveEntity() {
        LivingEntity target = mock(LivingEntity.class);
        org.bukkit.inventory.EntityEquipment equipment = mock(org.bukkit.inventory.EntityEquipment.class);
        org.bukkit.inventory.ItemStack shield = mock(org.bukkit.inventory.ItemStack.class);
        org.bukkit.Material shieldMaterial = mock(org.bukkit.Material.class);
        when(target.isCollidable()).thenReturn(true);
        when(target.getEquipment()).thenReturn(equipment);
        when(shieldMaterial.isAir()).thenReturn(false);
        when(shieldMaterial.name()).thenReturn("SHIELD");
        when(shield.getType()).thenReturn(shieldMaterial);
        when(equipment.getItemInOffHand()).thenReturn(shield);

        assertTrue(condition("is-collidable").test(context(target)));
        assertTrue(SkillExtras.conditions().get("has-offhand").create(List.of())
                .test(context(target)));
        assertTrue(SkillExtras.conditions().get("has-offhand").create(List.of("SHIELD"))
                .test(context(target)));
        assertFalse(SkillExtras.conditions().get("has-offhand").create(List.of("DIAMOND"))
                .test(context(target)));

        when(target.isCollidable()).thenReturn(false);
        org.bukkit.inventory.ItemStack empty = mock(org.bukkit.inventory.ItemStack.class);
        org.bukkit.Material air = mock(org.bukkit.Material.class);
        when(air.isAir()).thenReturn(true);
        when(empty.getType()).thenReturn(air);
        when(equipment.getItemInOffHand()).thenReturn(empty);
        assertFalse(condition("is-collidable").test(context(target)));
        assertFalse(SkillExtras.conditions().get("has-offhand").create(List.of()).test(context(target)));
    }

    @Test
    void environmentalAliasesUseWorldAndEntityState() {
        ModelInstance instance = mock(ModelInstance.class);
        LivingEntity caster = mock(LivingEntity.class);
        Location location = mock(Location.class);
        World world = mock(World.class);
        when(instance.isValid()).thenReturn(true);
        when(instance.baseEntity()).thenReturn(Optional.of(caster));
        when(instance.location()).thenReturn(location);
        when(location.getWorld()).thenReturn(world);
        when(world.getTime()).thenReturn(13_000L);
        when(world.hasStorm()).thenReturn(true);
        when(world.isThundering()).thenReturn(false);
        when(caster.isOnGround()).thenReturn(false);
        when(caster.getVelocity()).thenReturn(new Vector(0, -0.2, 0));
        when(caster.getRemainingAir()).thenReturn(0);
        PotionEffect effect = mock(PotionEffect.class);
        when(caster.getActivePotionEffects()).thenReturn(Set.of(effect));
        BehaviorContext ctx = BehaviorContext.of(instance, (LivingEntity) null, 1.0, -1, 0, "IDLE");

        assertTrue(condition("night").test(ctx));
        assertTrue(condition("raining").test(ctx));
        assertFalse(condition("thundering").test(ctx));
        assertTrue(condition("falling").test(ctx));
        assertTrue(condition("drowning").test(ctx));
        assertTrue(condition("has-auras").test(ctx));
    }

    @Test
    void drowningComparatorValidatesAtBindAndEvaluatesRemainingAir() {
        ModelInstance instance = mock(ModelInstance.class);
        LivingEntity entity = mock(LivingEntity.class);
        when(instance.isValid()).thenReturn(true);
        when(instance.baseEntity()).thenReturn(Optional.of(entity));
        when(entity.getRemainingAir()).thenReturn(80);
        BehaviorContext ctx = BehaviorContext.of(instance, (LivingEntity) null,
                1.0, -1, 0, "IDLE");

        assertTrue(SkillExtras.conditions().get("drowning").create(List.of("<=100")).test(ctx));
        assertFalse(SkillExtras.conditions().get("drowning").create(List.of(">100")).test(ctx));
        assertThrows(IllegalArgumentException.class,
                () -> SkillExtras.conditions().get("drowning").create(List.of(">NaN")));
    }

    @Test
    void targetWithinUsesExecutionDistance() {
        LivingEntity target = mock(LivingEntity.class);
        BehaviorContext ctx = BehaviorContext.continueExecution(null, target, 1.0, 4.0,
                0, "target", null, target, List.of(target), null, null);

        assertTrue(SkillExtras.conditions().get("target-within")
                .create(List.of("<=5")).test(ctx));
        BehaviorContext far = BehaviorContext.continueExecution(null, target, 1.0, 6.0,
                0, "target", null, target, List.of(target), null, null);
        assertFalse(SkillExtras.conditions().get("target-within")
                .create(List.of("<=5")).test(far));
    }
}
