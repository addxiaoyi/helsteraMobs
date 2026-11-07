package dev.helstera.ai;

import dev.helstera.api.behavior.BehaviorContext;
import dev.helstera.ai.skill.SkillCatalog;
import dev.helstera.ai.skill.SkillExtras;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 自身位移动作的构造与边界测试。
 *
 * <p>位移类动作依赖真实世界（方块阻挡、实体速度），单元测试无法覆盖运行期效果，
 * 因此这里只锁两件能在离线环境确证的事：动作能被构造出来，以及缺实例时安全返回。
 * 真正的移动效果需要在服务端上验证。</p>
 */
class SkillActionsTest {

    private static BehaviorContext ctx() {
        // 实例为 null：模拟载体已失效的场合，动作必须静默返回而非抛异常
        return BehaviorContext.of(null, null, 1.0, -1, 0, "IDLE");
    }

    private static SkillCatalog.ActionFactory factory(String name) {
        var f = SkillCatalog.actions().get(name);
        assertNotNull(f, "内置动作 " + name + " 不存在");
        return f;
    }

    @Test
    @DisplayName("三个自身位移动作均在册")
    void movementActionsRegistered() {
        var all = SkillCatalog.actions();
        for (String n : List.of("dash", "blink", "knockback-self")) {
            assertTrue(all.containsKey(n), "动作 " + n + " 缺失");
        }
    }

    @Test
    @DisplayName("缺实例时位移动作安全返回，不抛异常")
    void movementSafeWithoutInstance() {
        factory("dash").create(List.of("5", "target")).accept(ctx());
        factory("blink").create(List.of("8", "target")).accept(ctx());
        factory("knockback-self").create(List.of("1.0", "0.4")).accept(ctx());
    }

    @Test
    @DisplayName("jump mechanic raises the caster without replacing horizontal velocity")
    void jumpActionRaisesCaster() {
        var instance = org.mockito.Mockito.mock(dev.helstera.api.instance.ModelInstance.class);
        var caster = org.mockito.Mockito.mock(org.bukkit.entity.LivingEntity.class);
        var original = new org.bukkit.util.Vector(0.2, 0.1, -0.3);
        when(instance.isValid()).thenReturn(true);
        when(instance.baseEntity()).thenReturn(java.util.Optional.of(caster));
        when(caster.getVelocity()).thenReturn(original);
        var context = BehaviorContext.of(instance, (LivingEntity) null, 1.0, -1, 0, "IDLE");

        SkillExtras.actions().get("jump").create(List.of("0.8")).accept(context);

        var captured = org.mockito.ArgumentCaptor.forClass(org.bukkit.util.Vector.class);
        verify(caster).setVelocity(captured.capture());
        org.bukkit.util.Vector result = captured.getValue();
        org.junit.jupiter.api.Assertions.assertEquals(0.2, result.getX(), 1.0e-9);
        org.junit.jupiter.api.Assertions.assertEquals(0.8, result.getY(), 1.0e-9);
        org.junit.jupiter.api.Assertions.assertEquals(-0.3, result.getZ(), 1.0e-9);
    }

    @Test
    @DisplayName("force-pull moves the selected target toward the caster")
    void forcePullActionMovesTargetTowardCaster() {
        var instance = org.mockito.Mockito.mock(dev.helstera.api.instance.ModelInstance.class);
        var caster = org.mockito.Mockito.mock(org.bukkit.entity.LivingEntity.class);
        var target = org.mockito.Mockito.mock(org.bukkit.entity.LivingEntity.class);
        var world = org.mockito.Mockito.mock(org.bukkit.World.class);
        var origin = new Location(world, 0, 64, 0);
        when(instance.isValid()).thenReturn(true);
        when(instance.baseEntity()).thenReturn(java.util.Optional.of(caster));
        when(target.getLocation()).thenReturn(new Location(world, 3, 64, 0));
        var context = BehaviorContext.continueExecution(instance, target, 1.0, 3.0, 0,
                "IDLE", null, target, List.of(target), origin, null);

        SkillExtras.actions().get("force-pull").create(List.of("1.5")).accept(context);

        var captured = org.mockito.ArgumentCaptor.forClass(org.bukkit.util.Vector.class);
        verify(target).setVelocity(captured.capture());
        org.bukkit.util.Vector result = captured.getValue();
        org.junit.jupiter.api.Assertions.assertEquals(-1.5, result.getX(), 1.0e-9);
        org.junit.jupiter.api.Assertions.assertEquals(0.0, result.getY(), 1.0e-9);
        org.junit.jupiter.api.Assertions.assertEquals(0.0, result.getZ(), 1.0e-9);
    }

    @Test
    @DisplayName("block mechanics are registered and safe without an instance")
    void blockActionsRegisteredAndSafeWithoutInstance() {
        assertNotNull(SkillExtras.actions().get("set-block"));
        assertNotNull(SkillExtras.actions().get("break-block"));
        SkillExtras.actions().get("set-block").create(List.of("STONE")).accept(ctx());
        SkillExtras.actions().get("break-block").create(List.of()).accept(ctx());
    }

    @Test
    @DisplayName("block mechanics consume a location-valued target")
    void blockActionsUseExplicitLocationTarget() {
        World world = mock(World.class);
        var block = mock(org.bukkit.block.Block.class);
        when(world.getBlockAt(org.mockito.ArgumentMatchers.any(Location.class))).thenReturn(block);
        BehaviorContext context = BehaviorContext.execution(null, null, 1, -1, 0,
                "skill", null, null, List.of(), new Location(world, 0, 0, 0))
                .withLocationTarget(new Location(world, 4.5, 5, 6.9));

        SkillExtras.actions().get("set-block").create(List.of("STONE")).accept(context);
        verify(block).setType(org.bukkit.Material.STONE, false);
    }

    @Test
    void teleportActionsRejectInvalidLocationAndUseValidDestination() {
        var instance = mock(dev.helstera.api.instance.ModelInstance.class);
        var caster = mock(LivingEntity.class);
        var target = mock(LivingEntity.class);
        var world = mock(World.class);
        when(instance.isValid()).thenReturn(true);
        when(instance.baseEntity()).thenReturn(Optional.of(caster));
        var context = BehaviorContext.continueExecution(instance, target, 1, 0, 0,
                "skill", null, target, List.of(target), new Location(world, 0, 64, 0), null);

        for (String name : List.of("teleport", "teleport-caster", "teleport-targets")) {
            var action = name.equals("teleport-targets") ? factory(name) : SkillExtras.actions().get(name);
            action.create(List.of("@location{x=NaN;y=2;z=3}")).accept(context);
            action.create(List.of("@location{x=1;x=4;y=2;z=3}")).accept(context);
        }
        org.mockito.Mockito.verify(caster, org.mockito.Mockito.never())
                .teleport(org.mockito.ArgumentMatchers.any(Location.class));
        org.mockito.Mockito.verify(target, org.mockito.Mockito.never())
                .teleport(org.mockito.ArgumentMatchers.any(Location.class));

        SkillExtras.actions().get("teleport").create(List.of("@location{x=1;y=2;z=3}")).accept(context);
        SkillExtras.actions().get("teleport-caster").create(List.of("@location{x=1;y=2;z=3}")).accept(context);
        factory("teleport-targets").create(List.of("@location{x=1;y=2;z=3}")).accept(context);
        verify(caster, org.mockito.Mockito.times(2)).teleport(new Location(world, 1, 2, 3));
        verify(target).teleport(new Location(world, 1, 2, 3));
    }

    @Test
    void mythicTeleportUsesTargetOrientationAndUnsafeBypassesSpread() {
        var instance = mock(dev.helstera.api.instance.ModelInstance.class);
        var caster = mock(LivingEntity.class);
        var target = mock(LivingEntity.class);
        var world = mock(World.class);
        when(instance.isValid()).thenReturn(true);
        when(instance.baseEntity()).thenReturn(Optional.of(caster));
        when(caster.getLocation()).thenReturn(new Location(world, 0, 64, 0, 25, 12));
        when(target.getLocation()).thenReturn(new Location(world, 10, 70, -4, 130, -30));
        var context = BehaviorContext.continueExecution(instance, target, 1, 0, 0,
                "skill", null, target, List.of(target), new Location(world, 0, 64, 0), null);

        SkillExtras.actions().get("teleport-mythic")
                .create(List.of("spreadh=50", "spreadv=50", "unsafe=true", "yaw=90", "pitch=-15"))
                .accept(context);

        verify(caster).teleport(new Location(world, 10, 70, -4, 90, -15));
    }

    @Test
    void mythicTeleportSafeLandingRequiresGroundAndPassableBody() {
        var instance = mock(dev.helstera.api.instance.ModelInstance.class);
        var caster = mock(LivingEntity.class);
        var target = mock(LivingEntity.class);
        var world = mock(World.class);
        when(instance.isValid()).thenReturn(true);
        when(instance.baseEntity()).thenReturn(Optional.of(caster));
        when(caster.getLocation()).thenReturn(new Location(world, 0, 64, 0, 25, 12));
        when(target.getLocation()).thenReturn(new Location(world, 10, 70, -4, 130, -30));
        when(world.getBlockAt(org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt()))
                .thenAnswer(invocation -> {
                    int y = invocation.getArgument(1);
                    var block = mock(org.bukkit.block.Block.class);
                    when(block.isLiquid()).thenReturn(false);
                    when(block.getType()).thenReturn(y == 69 ? org.bukkit.Material.STONE : org.bukkit.Material.AIR);
                    when(block.isPassable()).thenReturn(y != 69);
                    return block;
                });
        var context = BehaviorContext.continueExecution(instance, target, 1, 0, 0,
                "skill", null, target, List.of(target), new Location(world, 0, 64, 0), null);

        SkillExtras.actions().get("teleport-mythic")
                .create(List.of("spreadh=0", "spreadv=0", "unsafe=false", "yaw=preserve", "pitch=preserve"))
                .accept(context);

        verify(caster).teleport(new Location(world, 10, 70, -4, 25, 12));
    }

    @Test
    @DisplayName("world weather and time mechanics use Bukkit world APIs")
    void worldMechanicsWriteWeatherAndTime() {
        var instance = org.mockito.Mockito.mock(dev.helstera.api.instance.ModelInstance.class);
        var world = org.mockito.Mockito.mock(org.bukkit.World.class);
        when(instance.isValid()).thenReturn(true);
        when(instance.location()).thenReturn(new Location(world, 0, 64, 0));
        var context = BehaviorContext.of(instance, (LivingEntity) null, 1.0, -1, 0, "IDLE");

        SkillExtras.actions().get("set-weather").create(List.of("thunder", "20")).accept(context);
        SkillExtras.actions().get("set-time").create(List.of("noon")).accept(context);

        verify(world).setStorm(true);
        verify(world).setThundering(true);
        verify(world).setWeatherDuration(20);
        verify(world).setThunderDuration(20);
        verify(world).setTime(6000L);
    }

    @Test
    @DisplayName("lightning mechanic uses the target world strike API")
    void lightningActionStrikesTargetWorld() {
        var target = org.mockito.Mockito.mock(org.bukkit.entity.LivingEntity.class);
        var world = org.mockito.Mockito.mock(org.bukkit.World.class);
        when(target.getLocation()).thenReturn(new Location(world, 3, 64, 0));
        var context = BehaviorContext.of(null, target, 1.0, 3.0, 0, "IDLE");

        SkillExtras.actions().get("lightning").create(List.of("true")).accept(context);

        verify(world).strikeLightningEffect(new Location(world, 3, 64, 0));
    }

    @Test
    @DisplayName("scoreboard tag mechanics mutate the caster entity")
    void scoreboardTagActionsMutateCaster() {
        var instance = org.mockito.Mockito.mock(dev.helstera.api.instance.ModelInstance.class);
        var mob = org.mockito.Mockito.mock(org.bukkit.entity.LivingEntity.class);
        org.mockito.Mockito.when(instance.isValid()).thenReturn(true);
        org.mockito.Mockito.when(instance.baseEntity()).thenReturn(java.util.Optional.of(mob));
        var context = BehaviorContext.of(instance, (org.bukkit.entity.LivingEntity) null,
                1.0, -1, 0, "IDLE");

        SkillExtras.actions().get("add-tag").create(List.of("tag=HelsteraProbe")).accept(context);
        SkillExtras.actions().get("remove-tag").create(List.of("t=HelsteraProbe")).accept(context);

        org.mockito.Mockito.verify(mob).addScoreboardTag("HelsteraProbe");
        org.mockito.Mockito.verify(mob).removeScoreboardTag("HelsteraProbe");
        assertNotNull(SkillExtras.actions().get("add-tag-targets"));
        assertNotNull(SkillExtras.actions().get("remove-tag-targets"));
    }

    @Test
    @DisplayName("entity state mechanics write Bukkit state flags")
    void entityStateActionsWriteBukkitFlags() {
        var instance = org.mockito.Mockito.mock(dev.helstera.api.instance.ModelInstance.class);
        var mob = org.mockito.Mockito.mock(org.bukkit.entity.Mob.class);
        org.mockito.Mockito.when(instance.isValid()).thenReturn(true);
        org.mockito.Mockito.when(instance.baseEntity()).thenReturn(java.util.Optional.of(mob));
        var context = BehaviorContext.of(instance, (org.bukkit.entity.LivingEntity) null,
                1.0, -1, 0, "IDLE");

        SkillExtras.actions().get("set-ai").create(List.of("false")).accept(context);
        SkillExtras.actions().get("set-gravity").create(List.of("false")).accept(context);
        SkillExtras.actions().get("set-silent").create(List.of("true")).accept(context);
        SkillExtras.actions().get("set-collidable").create(List.of("false")).accept(context);

        org.mockito.Mockito.verify(mob).setAI(false);
        org.mockito.Mockito.verify(mob).setGravity(false);
        org.mockito.Mockito.verify(mob).setSilent(true);
        org.mockito.Mockito.verify(mob).setCollidable(false);
    }

    @Test
    @DisplayName("swing mechanic uses the requested hand and target context")
    void swingActionUsesRequestedHand() {
        var instance = org.mockito.Mockito.mock(dev.helstera.api.instance.ModelInstance.class);
        var caster = org.mockito.Mockito.mock(org.bukkit.entity.LivingEntity.class);
        when(instance.isValid()).thenReturn(true);
        when(instance.baseEntity()).thenReturn(Optional.of(caster));
        var context = BehaviorContext.of(instance, (LivingEntity) null, 1.0, -1, 0, "IDLE");

        SkillExtras.actions().get("swing").create(List.of("OFF_HAND")).accept(context);

        verify(caster).swingOffHand();
        org.mockito.Mockito.verify(caster, org.mockito.Mockito.never()).swingMainHand();
    }

    @Test
    @DisplayName("swing aliases remain registered and fail safe without an instance")
    void swingAliasesAreRegistered() {
        for (String name : List.of("swing", "swing-main-hand", "swing-off-hand")) {
            assertNotNull(SkillExtras.actions().get(name), name);
            SkillExtras.actions().get(name).create(List.of()).accept(ctx());
        }
    }

    @Test
    @DisplayName("player and combat state mechanics write the matching Bukkit flags")
    void playerAndCombatStateActionsWriteFlags() {
        var instance = org.mockito.Mockito.mock(dev.helstera.api.instance.ModelInstance.class);
        var player = org.mockito.Mockito.mock(org.bukkit.entity.Player.class);
        org.mockito.Mockito.when(instance.isValid()).thenReturn(true);
        org.mockito.Mockito.when(instance.baseEntity()).thenReturn(java.util.Optional.of(player));
        var context = BehaviorContext.of(instance, (org.bukkit.entity.LivingEntity) null,
                1.0, -1, 0, "IDLE");

        SkillExtras.actions().get("set-sneaking").create(List.of("true")).accept(context);
        SkillExtras.actions().get("set-gliding").create(List.of("true")).accept(context);
        SkillExtras.actions().get("set-swimming").create(List.of("true")).accept(context);
        SkillExtras.actions().get("set-sprinting").create(List.of("true")).accept(context);
        SkillExtras.actions().get("set-flying").create(List.of("true")).accept(context);
        SkillExtras.actions().get("set-no-damage-ticks").create(List.of("10")).accept(context);
        SkillExtras.actions().get("set-maximum-no-damage-ticks").create(List.of("30")).accept(context);
        SkillExtras.actions().get("set-walk-speed").create(List.of("0.4")).accept(context);

        org.mockito.Mockito.verify(player).setSneaking(true);
        org.mockito.Mockito.verify(player).setGliding(true);
        org.mockito.Mockito.verify(player).setSwimming(true);
        org.mockito.Mockito.verify(player).setSprinting(true);
        org.mockito.Mockito.verify(player).setFlying(true);
        org.mockito.Mockito.verify(player).setNoDamageTicks(10);
        org.mockito.Mockito.verify(player).setMaximumNoDamageTicks(30);
        org.mockito.Mockito.verify(player).setWalkSpeed(0.4f);
    }

    @Test
    @DisplayName("fire mechanics set and clear caster fire ticks")
    void fireActionsSetAndClearTicks() {
        var instance = org.mockito.Mockito.mock(dev.helstera.api.instance.ModelInstance.class);
        var mob = org.mockito.Mockito.mock(org.bukkit.entity.Mob.class);
        org.mockito.Mockito.when(instance.isValid()).thenReturn(true);
        org.mockito.Mockito.when(instance.baseEntity()).thenReturn(java.util.Optional.of(mob));
        var context = BehaviorContext.of(instance, (org.bukkit.entity.LivingEntity) null,
                1.0, -1, 0, "IDLE");

        SkillExtras.actions().get("set-fire").create(List.of("40")).accept(context);
        SkillExtras.actions().get("clear-fire").create(List.of()).accept(context);

        org.mockito.Mockito.verify(mob).setFireTicks(40);
        org.mockito.Mockito.verify(mob).setFireTicks(0);
    }

    @Test
    @DisplayName("freeze mechanic writes Paper freeze ticks to the target")
    void freezeActionWritesFreezeTicks() {
        var target = org.mockito.Mockito.mock(org.bukkit.entity.LivingEntity.class);
        var context = BehaviorContext.of(null, target, 1.0, -1, 0, "IDLE");

        SkillExtras.actions().get("freeze").create(List.of("80")).accept(context);

        verify(target).setFreezeTicks(80);
    }

    @Test
    @DisplayName("player inventory and experience mechanics use the targeted player")
    void playerInventoryAndExperienceActionsUseTarget() {
        var player = org.mockito.Mockito.mock(org.bukkit.entity.Player.class);
        var context = BehaviorContext.of(null, player, 1.0, -1, 0, "IDLE");

        assertNotNull(SkillExtras.actions().get("close-inventory"));
        assertNotNull(SkillExtras.actions().get("clear-experience"));
        assertNotNull(SkillExtras.actions().get("clear-experience-levels"));
        assertNotNull(SkillExtras.actions().get("give-experience-levels"));
        SkillExtras.actions().get("close-inventory").create(List.of()).accept(context);
        SkillExtras.actions().get("clear-experience").create(List.of()).accept(context);
        SkillExtras.actions().get("clear-experience-levels").create(List.of()).accept(context);
        SkillExtras.actions().get("give-experience-levels").create(List.of("3")).accept(context);

        verify(player).closeInventory();
        verify(player, org.mockito.Mockito.times(2)).setExp(0.0f);
        verify(player).setLevel(0);
        verify(player).giveExpLevels(3);
    }

    @Test
    @DisplayName("clear-inventory clears the targeted player inventory")
    void clearInventoryClearsTarget() {
        var player = org.mockito.Mockito.mock(org.bukkit.entity.Player.class);
        var inventory = org.mockito.Mockito.mock(org.bukkit.inventory.PlayerInventory.class);
        when(player.getInventory()).thenReturn(inventory);
        var context = BehaviorContext.of(null, player, 1.0, -1, 0, "IDLE");

        SkillExtras.actions().get("clear-inventory").create(List.of()).accept(context);

        verify(inventory).clear();
    }

    @Test
    @DisplayName("give-experience grants raw player experience")
    void giveExperienceGrantsRawExperience() {
        var player = org.mockito.Mockito.mock(org.bukkit.entity.Player.class);
        var context = BehaviorContext.of(null, player, 1.0, -1, 0, "IDLE");

        SkillExtras.actions().get("give-experience").create(List.of("25")).accept(context);

        verify(player).giveExp(25);
    }

    @Test
    @DisplayName("set-absorption writes target absorption amount")
    void setAbsorptionWritesTarget() {
        var target = org.mockito.Mockito.mock(org.bukkit.entity.LivingEntity.class);
        var context = BehaviorContext.of(null, target, 1.0, -1, 0, "IDLE");

        SkillExtras.actions().get("set-absorption").create(List.of("6")).accept(context);

        verify(target).setAbsorptionAmount(6.0);
    }

    @Test
    @DisplayName("set-absorption raises Paper's max absorption capacity")
    void setAbsorptionRaisesCapacityWhenPaperClamps() {
        var target = org.mockito.Mockito.mock(org.bukkit.entity.LivingEntity.class);
        var capacity = org.mockito.Mockito.mock(org.bukkit.attribute.AttributeInstance.class);
        when(target.getAttribute(org.mockito.ArgumentMatchers.any(org.bukkit.attribute.Attribute.class)))
                .thenReturn(capacity);
        when(capacity.getValue()).thenReturn(0.0);
        when(capacity.getBaseValue()).thenReturn(0.0);
        var context = BehaviorContext.of(null, target, 1.0, -1, 0, "IDLE");

        SkillExtras.actions().get("set-absorption").create(List.of("6")).accept(context);

        verify(capacity).setBaseValue(6.0);
        verify(target).setAbsorptionAmount(6.0);
    }

    @Test
    @DisplayName("EjectPassenger only ejects passengers from the caster")
    void ejectPassengerActionEjectsCasterPassengers() {
        var instance = org.mockito.Mockito.mock(dev.helstera.api.instance.ModelInstance.class);
        var caster = org.mockito.Mockito.mock(org.bukkit.entity.Entity.class);
        org.mockito.Mockito.when(instance.isValid()).thenReturn(true);
        org.mockito.Mockito.when(instance.baseEntity()).thenReturn(java.util.Optional.of(caster));
        var context = BehaviorContext.of(instance, (org.bukkit.entity.LivingEntity) null,
                1.0, -1, 0, "IDLE");

        SkillExtras.actions().get("eject-passenger").create(List.of()).accept(context);

        verify(caster).eject();
    }

    @Test
    @DisplayName("Oxygen adds air to the targeted player without exceeding maximum")
    void oxygenActionAddsTargetAir() {
        var player = org.mockito.Mockito.mock(org.bukkit.entity.Player.class);
        when(player.getRemainingAir()).thenReturn(100);
        when(player.getMaximumAir()).thenReturn(300);
        var context = BehaviorContext.of(null, player, 1.0, -1, 0, "IDLE");

        SkillExtras.actions().get("oxygen").create(List.of("20")).accept(context);

        verify(player).setRemainingAir(120);
    }

    @Test
    @DisplayName("ConsumeSlot removes only the requested player inventory slot amount")
    void consumeSlotActionRemovesRequestedSlotAmount() {
        var player = org.mockito.Mockito.mock(org.bukkit.entity.Player.class);
        var inventory = org.mockito.Mockito.mock(org.bukkit.inventory.PlayerInventory.class);
        var stack = org.mockito.Mockito.mock(org.bukkit.inventory.ItemStack.class);
        when(player.getInventory()).thenReturn(inventory);
        when(inventory.getItem(0)).thenReturn(stack);
        when(stack.getAmount()).thenReturn(5);
        var context = BehaviorContext.of(null, player, 1.0, -1, 0, "IDLE");

        SkillExtras.actions().get("consume-slot").create(List.of("0", "2")).accept(context);

        verify(stack).setAmount(3);
        verify(inventory, org.mockito.Mockito.never()).setItem(0, null);
    }

    @Test
    @DisplayName("player cooldown and game-mode mechanics use the targeted player")
    void playerCooldownAndGameModeActionsUseTarget() {
        var player = org.mockito.Mockito.mock(org.bukkit.entity.Player.class);
        var context = BehaviorContext.of(null, player, 1.0, -1, 0, "IDLE");

        SkillExtras.actions().get("set-material-cooldown")
                .create(List.of("ENDER_PEARL", "20")).accept(context);
        SkillExtras.actions().get("set-game-mode")
                .create(List.of("ADVENTURE")).accept(context);

        verify(player).setCooldown(org.bukkit.Material.ENDER_PEARL, 20);
        verify(player).setGameMode(org.bukkit.GameMode.ADVENTURE);
    }

    @Test
    @DisplayName("RemoveHeldItem alias removes only the active hand item")
    void removeHeldItemAliasRemovesActiveHand() {
        var target = org.mockito.Mockito.mock(org.bukkit.entity.LivingEntity.class);
        var equipment = org.mockito.Mockito.mock(org.bukkit.inventory.EntityEquipment.class);
        var held = org.mockito.Mockito.mock(org.bukkit.inventory.ItemStack.class);
        var material = org.mockito.Mockito.mock(org.bukkit.Material.class);
        when(target.getEquipment()).thenReturn(equipment);
        when(equipment.getItem(org.bukkit.inventory.EquipmentSlot.HAND)).thenReturn(held);
        when(held.getType()).thenReturn(material);
        when(material.isAir()).thenReturn(false);
        var context = BehaviorContext.of(null, target, 1.0, -1, 0, "IDLE");

        SkillExtras.actions().get("remove-held-item").create(List.of()).accept(context);

        verify(equipment).setItem(org.bukkit.inventory.EquipmentSlot.HAND, null);
    }

    @Test
    @DisplayName("damage-melee excludes the caster from its nearby targets")
    void meleeDamageDoesNotSelfDamage() {
        var instance = org.mockito.Mockito.mock(dev.helstera.api.instance.ModelInstance.class);
        var caster = org.mockito.Mockito.mock(org.bukkit.entity.LivingEntity.class);
        var target = org.mockito.Mockito.mock(org.bukkit.entity.LivingEntity.class);
        var world = org.mockito.Mockito.mock(org.bukkit.World.class);
        var origin = new org.bukkit.Location(world, 0, 64, 0);
        when(instance.isValid()).thenReturn(true);
        when(instance.baseEntity()).thenReturn(Optional.of(caster));
        when(instance.location()).thenReturn(origin);
        when(caster.isValid()).thenReturn(true);
        when(target.isValid()).thenReturn(true);
        when(world.getNearbyEntities(org.mockito.ArgumentMatchers.any(org.bukkit.Location.class),
                org.mockito.ArgumentMatchers.eq(2.5), org.mockito.ArgumentMatchers.eq(2.5),
                org.mockito.ArgumentMatchers.eq(2.5)))
                .thenReturn(List.<Entity>of(caster, target));
        var context = BehaviorContext.of(instance, (LivingEntity) null,
                1.0, -1, 0, "IDLE");

        SkillExtras.actions().get("damage-melee").create(List.of("3", "2.5")).accept(context);

        verify(target).damage(3.0, caster);
        org.mockito.Mockito.verify(caster, org.mockito.Mockito.never()).damage(
                org.mockito.ArgumentMatchers.anyDouble(), org.mockito.ArgumentMatchers.any(Entity.class));
    }

    @Test
    @DisplayName("rotation mechanics are registered and safe without an instance")
    void rotationActionsRegisteredAndSafeWithoutInstance() {
        assertNotNull(SkillExtras.actions().get("look-at"));
        assertNotNull(SkillExtras.actions().get("set-rotation"));
        SkillExtras.actions().get("look-at").create(List.of()).accept(ctx());
        SkillExtras.actions().get("set-rotation").create(List.of("90", "15")).accept(ctx());
    }

    /**
     * 非数值参数在<b>装载期</b>抛异常，而不是静默回落默认值。
     *
     * <p><b>行为期望被反转过。</b>原名 {@code movementBadArgs}，断言
     * 「参数非法时回退到默认值，不产生零位移或异常」——那正是本轮修掉的缺陷。
     * 静默回落让 {@code dash abc} 变成「冲刺默认距离」，技能照常执行，
     * {@code /helstera check} 与日志都无提示，现场只表现为「位移距离不对」。</p>
     *
     * <p>注意保留本测试原本真正想守的东西：<b>缺参与合法值不该被误伤</b>。
     * {@code dash} 不带参数、{@code dash -3}（负数合法）、{@code blink} 空参数
     * 都必须正常工作——只有真正无法解析的字面量才抛。</p>
     */
    @Test
    @DisplayName("非数值参数抛异常；缺参与合法值不受影响")
    void movementBadArgs() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> factory("dash").create(List.of("abc")),
                "dash abc 应在装载期抛异常，让 /helstera check 能报出来");
        assertTrue(e.getMessage().contains("abc"),
                "异常信息应含原始字面量，便于定位：实际 = " + e.getMessage());

        // 以下三种都必须正常建出动作——参数校验不该误伤它们
        factory("dash").create(List.of("-3")).accept(ctx());
        factory("blink").create(List.of()).accept(ctx());
        factory("knockback-self").create(List.of("1.0", "0.4")).accept(ctx());
    }

    @Test
    @DisplayName("朝向取值非法时退回默认方向")
    void movementBadDirection() {
        factory("dash").create(List.of("5", "not-a-direction")).accept(ctx());
        factory("blink").create(List.of("5", "back")).accept(ctx());
        factory("blink").create(List.of("5", "away")).accept(ctx());
    }

    @Test
    @DisplayName("位移动作区分自身朝向、靠近目标与远离目标")
    void movementDirectionModes() {
        var instance = mock(dev.helstera.api.instance.ModelInstance.class);
        var caster = mock(Entity.class);
        var target = mock(LivingEntity.class);
        World world = mock(World.class);
        // Bukkit yaw=90 faces -X; the target is due +Z so the modes are observable.
        Location origin = new Location(world, 0, 64, 0, 90, 0);
        Location targetLocation = new Location(world, 0, 64, 10);
        when(instance.isValid()).thenReturn(true);
        when(instance.location()).thenReturn(origin);
        when(instance.baseEntity()).thenReturn(Optional.of(caster));
        when(target.getLocation()).thenReturn(targetLocation);
        var context = BehaviorContext.continueExecution(instance, target, 1.0, 10.0,
                0, "movement", null, target, List.of(target), origin, null);

        factory("dash").create(List.of("2", "forward")).accept(context);
        verify(caster).setVelocity(org.mockito.ArgumentMatchers.argThat(vector ->
                Math.abs(vector.getX() + 2.0) < 1.0e-9
                        && Math.abs(vector.getY()) < 1.0e-9
                        && Math.abs(vector.getZ()) < 1.0e-9));

        clearInvocations(caster);
        factory("dash").create(List.of("2", "near")).accept(context);
        verify(caster).setVelocity(org.mockito.ArgumentMatchers.argThat(vector ->
                Math.abs(vector.getX()) < 1.0e-9
                        && Math.abs(vector.getY()) < 1.0e-9
                        && Math.abs(vector.getZ() - 2.0) < 1.0e-9));

        clearInvocations(caster);
        factory("dash").create(List.of("2", "away")).accept(context);
        verify(caster).setVelocity(org.mockito.ArgumentMatchers.argThat(vector ->
                Math.abs(vector.getX()) < 1.0e-9
                        && Math.abs(vector.getY()) < 1.0e-9
                        && Math.abs(vector.getZ() + 2.0) < 1.0e-9));
    }

    @Test
    @DisplayName("位移距离支持在执行时解析目标占位符")
    void movementDistanceResolvesAtExecutionTime() {
        var instance = mock(dev.helstera.api.instance.ModelInstance.class);
        var caster = mock(Entity.class);
        var target = mock(LivingEntity.class);
        World world = mock(World.class);
        Location origin = new Location(world, 0, 64, 0, 90, 0);
        when(instance.isValid()).thenReturn(true);
        when(instance.location()).thenReturn(origin);
        when(instance.baseEntity()).thenReturn(Optional.of(caster));
        when(target.getLocation()).thenReturn(new Location(world, 0, 64, 10));
        when(target.getHealth()).thenReturn(3.0);
        var context = BehaviorContext.continueExecution(instance, target, 1.0, 10.0,
                0, "movement", null, target, List.of(target), origin, null);

        factory("dash").create(List.of("<target.hp.raw>", "forward")).accept(context);

        verify(caster).setVelocity(org.mockito.ArgumentMatchers.argThat(vector ->
                Math.abs(vector.getX() + 3.0) < 1.0e-9
                        && Math.abs(vector.getY()) < 1.0e-9
                        && Math.abs(vector.getZ()) < 1.0e-9));
    }

    @Test
    @DisplayName("summon：未注入钩子时静默返回，不抛异常")
    void summonWithoutHook() {
        SkillCatalog.summoner(null);
        factory("summon").create(List.of("my_model", "2")).accept(ctx());
    }

    @Test
    @DisplayName("summon：模型名为空时不生成")
    void summonBlankModelId() {
        java.util.List<String> calls = new java.util.ArrayList<>();
        SkillCatalog.summoner((modelId, at) -> {
            calls.add(modelId);
            return 1;   // Summoner 现返回新实例 id（-1 表示失败）
        });
        try {
            factory("summon").create(List.of("")).accept(ctx());
            assertTrue(calls.isEmpty(), "空模型名不应触发生成");
        } finally {
            SkillCatalog.summoner(null);
        }
    }

    @Test
    @DisplayName("summon：缺实例时不生成")
    void summonNeedsInstance() {
        java.util.List<String> calls = new java.util.ArrayList<>();
        SkillCatalog.summoner((modelId, at) -> {
            calls.add(modelId);
            return 1;   // Summoner 现返回新实例 id（-1 表示失败）
        });
        try {
            factory("summon").create(List.of("my_model")).accept(ctx());
            assertTrue(calls.isEmpty(), "实例无效时不应生成，否则会凭空刷出模型");
        } finally {
            SkillCatalog.summoner(null);
        }
    }

    @Test
    @DisplayName("summon：数量与半径被限制在上限内")
    void summonClampsArgs() {
        java.util.List<String> calls = new java.util.ArrayList<>();
        SkillCatalog.summoner((modelId, at) -> {
            calls.add(modelId);
            return 1;   // Summoner 现返回新实例 id（-1 表示失败）
        });
        try {
            // 数量 999 / 半径 9999 必须被夹到上限，避免一份配置刷爆渲染
            var action = factory("summon").create(List.of("m", "999", "9999"));
            // 无实例时不会真的调用钩子，这里只确认构造不抛异常且参数被夹住
            action.accept(ctx());
            assertTrue(calls.isEmpty());
        } finally {
            SkillCatalog.summoner(null);
        }
    }

    @Test
    @DisplayName("既有动作未受影响")
    void existingActionsIntact() {
        var all = SkillCatalog.actions();
        for (String n : List.of("set-scale", "play-animation", "damage-target", "aoe-damage",
                "teleport-targets", "effect-targets", "ignite-targets", "knockback-targets",
                "dash", "blink", "knockback-self", "summon")) {
            assertTrue(all.containsKey(n), "动作 " + n + " 缺失");
        }
    }

    @Test
    @DisplayName("disguise mechanics are registered and safe without an instance")
    void disguiseActionsRegisteredAndSafe() {
        assertTrue(SkillCatalog.actions().containsKey("disguise"));
        assertTrue(SkillCatalog.actions().containsKey("remove-disguise"));
        factory("disguise").create(List.of("example/alternate")).accept(ctx());
        factory("disguise").create(List.of()).accept(ctx());
        factory("remove-disguise").create(List.of()).accept(ctx());
    }
}
