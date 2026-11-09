package dev.helstera.ai;

import dev.helstera.ai.skill.SkillCatalog;
import dev.helstera.ai.skill.SkillExtras;
import dev.helstera.ai.skill.PinRegistry;
import dev.helstera.ai.skill.SkillService;
import dev.helstera.api.behavior.BehaviorContext;
import dev.helstera.api.instance.ModelInstance;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyDouble;

/**
 * 技能装载与条件求值的契约测试。
 *
 * <p>不依赖运行中的服务端：条件只读 BehaviorContext 的标量快照，
 * 因此可以用 {@code instance=null} 构造上下文来验证纯逻辑分支。</p>
 */
class SkillServiceTest {

    @Test
    void actionBindingRejectsUnroutedEventSuffix() {
        var svc = service();
        assertNull(svc.bindAction("set-block STONE ~onAttack"));
        assertTrue(svc.warnings().stream().anyMatch(w -> w.contains("event trigger requires event routing")));
        assertNull(svc.bindAction("set-block STONE ~onAttack ~onDeath"));
        assertTrue(svc.warnings().stream().anyMatch(w -> w.contains("multiple event triggers")));
        assertNull(svc.bindAction("set-block STONE ~ONAttack"));
        assertNull(svc.bindAction("set-block STONE ~unknown"));
        assertNull(svc.bindAction("set-block STONE ~on"));
        assertNull(svc.bindAction("set-block STONE ?"));
        assertNull(svc.bindAction("set-block STONE @"));
        assertNotNull(svc.bindAction("set-block STONE"));
        assertNotNull(svc.bindAction("message-target \"~onAttack\""));
    }

    @Test
    void locationTargeterDispatchesLocationWithoutSelectingNearbyEntities() {
        World world = mock(World.class);
        var block = mock(org.bukkit.block.Block.class);
        Location destination = new Location(world, 4, 5, 6);
        when(world.getBlockAt(destination)).thenReturn(block);
        BehaviorContext context = BehaviorContext.execution(null, null, 1, -1, 0,
                "skill", null, null, List.of(), new Location(world, 0, 64, 0));
        var svc = service();

        String valid = svc.bindAction("set-block STONE @location{x=4;y=5;z=6}");
        assertNotNull(valid);
        assertTrue(svc.runAction(valid, context));
        verify(block).setType(Material.STONE, false);
        String alias = svc.bindAction("set-block STONE @loc{loc=4,5,6,0,0}");
        assertNotNull(alias);
        assertTrue(svc.runAction(alias, context));
        verify(block, org.mockito.Mockito.times(2)).setType(Material.STONE, false);
        org.mockito.Mockito.verify(world, org.mockito.Mockito.never())
                .getNearbyEntities(org.mockito.ArgumentMatchers.any(Location.class),
                        anyDouble(), anyDouble(), anyDouble());

        String invalid = svc.bindAction("set-block STONE @location{x=NaN;y=5;z=6}");
        assertNotNull(invalid);
        assertTrue(svc.runAction(invalid, context));
        verify(block, org.mockito.Mockito.times(2)).setType(Material.STONE, false);
    }

    @Test
    void officialLocationTargeterDispatchesForwardCoordinatesToMechanics() {
        World world = mock(World.class);
        ModelInstance instance = mock(ModelInstance.class);
        LivingEntity caster = mock(LivingEntity.class);
        Location casterLocation = new Location(world, 10, 64, 20, 90, 0);
        Location destination = new Location(world, 7, 64, 20, 90, 0);
        var block = mock(org.bukkit.block.Block.class);
        when(instance.isValid()).thenReturn(true);
        when(instance.location()).thenReturn(casterLocation);
        when(instance.baseEntity()).thenReturn(Optional.of(caster));
        when(caster.getLocation()).thenReturn(casterLocation);
        when(world.getBlockAt(destination)).thenReturn(block);
        BehaviorContext context = BehaviorContext.execution(instance, null, 1, -1, 0,
                "skill", null, null, List.of(), casterLocation);
        var svc = service();

        String action = svc.bindAction("set-block STONE @Forward{f=3}");
        assertNotNull(action);
        assertTrue(svc.runAction(action, context));
        verify(block).setType(Material.STONE, false);
        org.mockito.Mockito.verify(world, org.mockito.Mockito.never())
                .getNearbyEntities(org.mockito.ArgumentMatchers.any(Location.class),
                        anyDouble(), anyDouble(), anyDouble());
    }

    @Test
    void variableLocationTargeterDispatchesStoredLocationAndRejectsAmbiguousArguments() {
        World world = mock(World.class);
        Location destination = new Location(world, 4, 5, 6);
        var block = mock(org.bukkit.block.Block.class);
        when(world.getBlockAt(destination)).thenReturn(block);
        BehaviorContext context = BehaviorContext.execution(null, null, 1, -1, 0,
                "skill", null, null, List.of(), new Location(world, 0, 64, 0));
        context.skillTree().orElseThrow().setLocation("mark", destination);
        var svc = service();
        assertEquals(destination, dev.helstera.api.behavior.Targeters.resolveLocation(
                "@variable-location{mark;scope=skill}", context).orElseThrow());
        String valid = svc.bindAction("set-block STONE @VariableLocation{var=skill.mark}");
        assertNotNull(valid);
        assertTrue(svc.runAction(valid, context));
        verify(block).setType(Material.STONE, false);

        for (String spec : List.of("@VariableLocation{var=skill.mark;var=skill.other}",
                "@VariableLocation{var=skill.mark;unknown=1}",
                "@VariableLocation{var=skill.mark}tail",
                "@VariableLocation{var=skill.missing}")) {
            assertTrue(dev.helstera.api.behavior.Targeters.resolveLocation(spec, context).isEmpty(), spec);
            String action = svc.bindAction("set-block DIRT " + spec);
            if (action != null) assertTrue(svc.runAction(action, context));
        }
        context.skillTree().orElseThrow().setLocation("no-world", new Location(null, 1, 2, 3));
        String noWorld = svc.bindAction("set-block DIRT @VariableLocation{var=skill.no-world}");
        assertNotNull(noWorld);
        assertTrue(svc.runAction(noWorld, context));
        verify(block, org.mockito.Mockito.never()).setType(Material.DIRT, false);
    }

    @Test
    void trackLocationMechanicStoresTargetedLocationAndRejectsPlayerCasters() {
        World world = mock(World.class);
        ModelInstance instance = mock(ModelInstance.class);
        LivingEntity caster = mock(LivingEntity.class);
        LivingEntity target = mock(LivingEntity.class);
        Location casterLocation = new Location(world, 1, 64, 2);
        Location targetLocation = new Location(world, 8, 70, 9);
        when(instance.isValid()).thenReturn(true);
        when(instance.baseEntity()).thenReturn(Optional.of(caster));
        when(instance.location()).thenReturn(casterLocation);
        when(caster.getLocation()).thenReturn(casterLocation);
        when(target.getLocation()).thenReturn(targetLocation);
        var tree = new dev.helstera.api.behavior.SkillTreeContext(null, List.of(target), casterLocation);
        BehaviorContext context = BehaviorContext.continueExecution(instance, target, 1, 10, 0,
                "skill", null, null, List.of(target), casterLocation, tree);
        var svc = service();

        String action = svc.bindAction("stl @targetlocation");
        assertNotNull(action);
        assertTrue(svc.runAction(action, context));
        assertEquals(targetLocation, dev.helstera.api.behavior.Targeters
                .resolveLocation("@TrackedLocation", context).orElseThrow());

        var block = mock(org.bukkit.block.Block.class);
        when(world.getBlockAt(targetLocation)).thenReturn(block);
        String blockAction = svc.bindAction("set-block STONE @TrackedLocation");
        assertNotNull(blockAction);
        assertTrue(svc.runAction(blockAction, context));
        verify(block).setType(Material.STONE, false);

        Player player = mock(Player.class);
        ModelInstance playerInstance = mock(ModelInstance.class);
        when(playerInstance.isValid()).thenReturn(true);
        when(playerInstance.baseEntity()).thenReturn(Optional.of(player));
        when(player.getLocation()).thenReturn(casterLocation);
        var playerTree = new dev.helstera.api.behavior.SkillTreeContext(null, List.of(target), casterLocation);
        BehaviorContext playerContext = BehaviorContext.continueExecution(playerInstance, target, 1, 10, 0,
                "skill", null, null, List.of(target), casterLocation, playerTree);
        String playerAction = svc.bindAction("settrackedlocation @selflocation");
        assertNotNull(playerAction, svc.warnings().toString());
        assertTrue(svc.runAction(playerAction, playerContext));
        assertTrue(playerTree.location(dev.helstera.api.behavior.SkillTreeContext.MYTHIC_TRACKED_LOCATION)
                .isEmpty());
        assertNull(svc.bindAction("track-location unexpected"));
    }

    @Test
    void distanceFromTrackedLocationMeasuresCasterAndFailsClosed() {
        World world = mock(World.class);
        World otherWorld = mock(World.class);
        ModelInstance instance = mock(ModelInstance.class);
        LivingEntity caster = mock(LivingEntity.class);
        LivingEntity target = mock(LivingEntity.class);
        Location casterLocation = new Location(world, 3, 4, 0);
        when(instance.isValid()).thenReturn(true);
        when(instance.baseEntity()).thenReturn(Optional.of(caster));
        when(instance.location()).thenReturn(casterLocation);
        when(caster.getLocation()).thenReturn(casterLocation);
        when(target.getLocation()).thenReturn(new Location(world, 100, 0, 0));
        var tree = new dev.helstera.api.behavior.SkillTreeContext(null, List.of(target), casterLocation);
        tree.setLocation(dev.helstera.api.behavior.SkillTreeContext.MYTHIC_TRACKED_LOCATION,
                new Location(world, 0, 0, 0));
        BehaviorContext context = BehaviorContext.continueExecution(instance, target, 1, 100, 0,
                "skill", null, null, List.of(target), casterLocation, tree);
        var svc = service();

        String exact = svc.bindCondition("distanceFromTL{d=5}");
        assertNotNull(exact);
        assertTrue(registry.testCondition(exact, context));
        assertTrue(registry.testCondition(svc.bindCondition(
                "distance-from-tracked-location{distance=4to6}"), context));
        assertFalse(registry.testCondition(svc.bindCondition(
                "distance-from-tracked-location{d=<5}"), context));

        tree.unsetLocation(dev.helstera.api.behavior.SkillTreeContext.MYTHIC_TRACKED_LOCATION);
        assertFalse(registry.testCondition(exact, context));
        tree.setLocation(dev.helstera.api.behavior.SkillTreeContext.MYTHIC_TRACKED_LOCATION,
                new Location(otherWorld, 0, 0, 0));
        assertFalse(registry.testCondition(exact, context));
        assertNull(svc.bindCondition("distance-from-tracked-location{d=5;distance=6}"));
        assertNull(svc.bindCondition("distance-from-tracked-location{d==5}"));
        assertNull(svc.bindCondition("distance-from-tracked-location{d=!=5}"));
        assertNull(svc.bindCondition("distance-from-tracked-location{d=Infinity}"));
    }

    @Test
    void explicitLocationDefaultsToCasterWorldRatherThanExecutionOriginWorld() {
        var instance = mock(ModelInstance.class);
        var casterWorld = mock(World.class);
        var originWorld = mock(World.class);
        when(instance.isValid()).thenReturn(true);
        when(instance.location()).thenReturn(new Location(casterWorld, 1, 2, 3));
        var context = BehaviorContext.execution(instance, null, 1, -1, 0,
                "skill", null, null, List.of(), new Location(originWorld, 5, 6, 7));
        assertEquals(casterWorld, dev.helstera.api.behavior.Targeters
                .resolveLocation("@location{x=4}", context).orElseThrow().getWorld());
    }

    private final BehaviorRegistryImpl registry = new BehaviorRegistryImpl();

    private SkillService service() {
        return new SkillService(registry, null);
    }

    private static BehaviorContext ctx(double health, long decisions) {
        return BehaviorContext.of(null, null, health, -1, decisions, "IDLE");
    }

    private static BehaviorContext ctxWithState(String state) {
        return BehaviorContext.of(null, null, 1.0, -1, 0, state);
    }

    @Test
    void rejectedConditionDoesNotBecomeBoundOnRetryOrNegation() {
        var svc = service();
        for (int i = 0; i < 3; i++) {
            assertNull(svc.bindCondition("last-signal first second"));
            assertNull(svc.bindCondition("is-frozen unknown=true"));
            assertNull(svc.bindCondition("projectile-has-ended unknown=true"));
            for (String name : List.of("has-target", "night", "raining", "thundering", "is-living",
                    "is-player", "is-mob", "is-monster", "is-animal", "is-villager", "is-tameable", "is-raider",
                    "is-baby", "is-child", "is-adult", "is-tamed", "is-sheared", "is-saddled", "is-powered",
                    "target-is-baby", "target-is-adult", "target-is-tamed", "target-is-sheared",
                    "target-is-saddled", "target-is-powered", "target-is-player", "target-is-living",
                    "target-is-mob", "target-is-monster", "target-is-animal", "target-is-villager",
                    "target-is-tameable", "target-is-raider", "has-ai", "has-gravity", "is-silent", "is-dead",
                    "is-alive", "is-valid", "target-has-ai", "target-has-gravity", "target-is-silent",
                    "target-dead", "target-is-alive", "target-is-valid", "in-water", "target-is-sneaking")) {
                assertNull(svc.bindCondition(name + " unknown=true"), name);
                assertNull(svc.bindCondition("!" + name + " unknown=true"), name);
                assertNotNull(svc.bindCondition(name), name);
            }
            assertNotNull(svc.bindCondition("has-target false"));
            for (String alias : List.of("isChild", "isCharged", "targetIsChild", "targetIsCharged")) {
                assertNull(svc.bindCondition(alias + "{unknown=true}"), alias);
                assertNotNull(svc.bindCondition(alias), alias);
            }
            assertNull(svc.bindCondition("!last-signal first second"));
            assertNull(svc.bindCondition("random-chance NaN"));
            assertNull(svc.bindCondition("not{random-chance NaN}"));
        }
        assertNotNull(svc.bindCondition("last-signal phase2"));
        assertNotNull(svc.bindCondition("is-frozen"));
        assertNotNull(svc.bindCondition("projectile-has-ended"));
        assertNull(svc.bindCondition("lastSignal{s=first;s=second}"));
        assertNull(svc.bindCondition("lastSignal{s=first,s=second}"));
        assertNotNull(svc.bindCondition("lastSignal{s=first}"));
    }

    @Test
    void rejectedActionFactoryIsRetriedWithoutCreatingPhantomBinding() {
        var attempts = new AtomicInteger();
        SkillCatalog.ActionFactory factory = args -> {
            attempts.incrementAndGet();
            throw new IllegalArgumentException("invalid argument");
        };
        var svc = new SkillService(registry, null, Map.of(), Map.of("reject-probe", factory));
        for (int i = 0; i < 3; i++) {
            assertNull(svc.bindAction("reject-probe invalid"));
            assertFalse(registry.hasAction("reject-probe:invalid"));
        }
        assertEquals(3, attempts.get());
    }

    @Test
    void malformedCompositeConditionsDoNotDropOperands() {
        var svc = service();
        for (String spec : List.of("not{has-target;is-caster}", "all{has-target;}",
                "any{;has-target}", "all{has-target;;is-caster}", "has-target ||",
                "not{has-target}extra", "all{has-target}}")) {
            assertNull(svc.bindCondition(spec), spec);
        }
        assertNotNull(svc.bindCondition("not{has-target}"));
        assertNotNull(svc.bindCondition("all{has-target;is-caster}"));
    }

    @Test
    @DisplayName("player state conditions read Bukkit blocking state")
    void playerBlockingConditionReadsEntityState() {
        var svc = service();
        var instance = mock(ModelInstance.class);
        var player = mock(Player.class);
        when(instance.isValid()).thenReturn(true);
        when(instance.baseEntity()).thenReturn(Optional.of(player));
        when(player.isBlocking()).thenReturn(true);
        var context = BehaviorContext.of(instance, (LivingEntity) null, 1.0, -1, 0, "IDLE");

        String key = svc.bindCondition("is-blocking");
        assertTrue(registry.testCondition(key, context));
        when(player.isBlocking()).thenReturn(false);
        assertFalse(registry.testCondition(key, context));
    }

    @Test
    @DisplayName("is-caster compares the explicit target with the managed caster")
    void isCasterConditionUsesEntityIdentity() {
        var svc = service();
        var instance = mock(ModelInstance.class);
        var caster = mock(LivingEntity.class);
        var other = mock(LivingEntity.class);
        when(instance.isValid()).thenReturn(true);
        when(instance.baseEntity()).thenReturn(Optional.of(caster));
        when(caster.getUniqueId()).thenReturn(java.util.UUID.randomUUID());
        when(other.getUniqueId()).thenReturn(java.util.UUID.randomUUID());
        var same = BehaviorContext.of(instance, caster, 1.0, 0, 0, "IDLE");
        var different = BehaviorContext.of(instance, other, 1.0, 0, 0, "IDLE");
        String key = svc.bindCondition("is-caster");
        assertTrue(registry.testCondition(key, same));
        assertFalse(registry.testCondition(key, different));
    }

    @Test
    @DisplayName("name aliases bind to the active entity name condition")
    void nameConditionAliasesBind() {
        var svc = service();
        var named = mock(LivingEntity.class);
        when(named.getCustomName()).thenReturn("Emberling");
        when(named.getName()).thenReturn("Zombie");
        var context = BehaviorContext.of(null, named, 1.0, -1, 0, "IDLE");

        assertTrue(registry.testCondition(svc.bindCondition("name Emberling"), context));
        assertTrue(registry.testCondition(svc.bindCondition("name{n=Emberling}"), context));
        assertFalse(registry.testCondition(svc.bindCondition("name Zombie"), context));
    }

    @Test
    @DisplayName("food-level compares the active player target and fails closed without one")
    void foodLevelConditionComparesPlayerFood() {
        var svc = service();
        var player = mock(Player.class);
        when(player.getFoodLevel()).thenReturn(14);
        var context = BehaviorContext.of(null, (LivingEntity) player, 1.0, -1, 0, "IDLE");

        String key = svc.bindCondition("food-level <= 14");
        assertNotNull(key, svc.warnings().toString());
        assertTrue(registry.testCondition(key, context));
        when(player.getFoodLevel()).thenReturn(15);
        assertFalse(registry.testCondition(key, context));
        assertFalse(registry.testCondition(key, ctx(1.0, 0)));
    }

    @Test
    @DisplayName("air-level compares remaining air and fails closed without an entity")
    void airLevelConditionComparesRemainingAir() {
        var svc = service();
        var living = mock(LivingEntity.class);
        when(living.getRemainingAir()).thenReturn(120, 121);
        var context = BehaviorContext.of(null, living, 1.0, -1, 0, "IDLE");

        String key = svc.bindCondition("air-level <= 120");
        assertNotNull(key, svc.warnings().toString());
        assertTrue(registry.testCondition(key, context));
        assertFalse(registry.testCondition(key, context));
        assertFalse(registry.testCondition(key, ctx(1.0, 0)));
    }

    @Test
    @DisplayName("target-prefixed player conditions never fall back to caster state")
    void targetPrefixedPlayerConditionsUseOnlyTarget() {
        var svc = service();
        var instance = mock(ModelInstance.class);
        var caster = mock(Player.class);
        var target = mock(Player.class);
        when(instance.isValid()).thenReturn(true);
        when(instance.baseEntity()).thenReturn(Optional.of(caster));
        when(caster.hasPermission("probe.node")).thenReturn(true);
        when(caster.getFoodLevel()).thenReturn(20);
        when(caster.getGameMode()).thenReturn(org.bukkit.GameMode.CREATIVE);
        when(caster.getRemainingAir()).thenReturn(20);
        when(target.hasPermission("probe.node")).thenReturn(false);
        when(target.getFoodLevel()).thenReturn(10);
        when(target.getGameMode()).thenReturn(org.bukkit.GameMode.SURVIVAL);
        when(target.getRemainingAir()).thenReturn(100);
        var context = BehaviorContext.of(instance, target, 1.0, -1, 0, "IDLE");

        assertFalse(registry.testCondition(svc.bindCondition("target-has-permission probe.node"), context));
        assertFalse(registry.testCondition(svc.bindCondition("target-game-mode-is CREATIVE"), context));
        assertFalse(registry.testCondition(svc.bindCondition("target-food-level >=14"), context));
        assertFalse(registry.testCondition(svc.bindCondition("target-air-level <=50"), context));

        var noTarget = BehaviorContext.of(instance, (LivingEntity) null, 1.0, -1, 0, "IDLE");
        assertFalse(registry.testCondition(svc.bindCondition("target-has-permission probe.node"), noTarget));
        assertFalse(registry.testCondition(svc.bindCondition("target-game-mode-is CREATIVE"), noTarget));
        assertFalse(registry.testCondition(svc.bindCondition("target-food-level >=14"), noTarget));
        assertFalse(registry.testCondition(svc.bindCondition("target-air-level <=50"), noTarget));

        when(target.hasPermission("probe.node")).thenReturn(true);
        when(target.getFoodLevel()).thenReturn(14);
        when(target.getGameMode()).thenReturn(org.bukkit.GameMode.CREATIVE);
        when(target.getRemainingAir()).thenReturn(50);
        assertTrue(registry.testCondition(svc.bindCondition("target-has-permission probe.node"), context));
        assertTrue(registry.testCondition(svc.bindCondition("target-game-mode-is CREATIVE"), context));
        assertTrue(registry.testCondition(svc.bindCondition("target-food-level >=14"), context));
        assertTrue(registry.testCondition(svc.bindCondition("target-air-level <=50"), context));
    }

    @Test
    void lightningEffectAliasNeverCreatesDamagingLightning() {
        var svc = service();
        var target = mock(LivingEntity.class);
        var world = mock(World.class);
        var location = new Location(world, 3, 64, 0);
        when(target.getLocation()).thenReturn(location);
        var context = BehaviorContext.of(null, target, 1.0, 3.0, 0, "IDLE");

        String visual = svc.bindAction("strike-lightning-effect");
        assertNotNull(visual, svc.warnings().toString());
        assertTrue(registry.runAction(visual, context));
        verify(world).strikeLightningEffect(location);
        org.mockito.Mockito.verify(world, org.mockito.Mockito.never()).strikeLightning(location);

        String real = svc.bindAction("strike-lightning");
        assertNotNull(real, svc.warnings().toString());
        assertTrue(registry.runAction(real, context));
        verify(world).strikeLightning(location);
    }

    @Test
    @DisplayName("signal action emits the active instance id and payload")
    void signalActionEmitsScopedPayload() {
        var svc = service();
        var instance = mock(ModelInstance.class);
        when(instance.isValid()).thenReturn(true);
        when(instance.instanceId()).thenReturn(17);
        var context = BehaviorContext.of(instance, (LivingEntity) null, 1.0, -1, 0, "IDLE");
        var bus = new dev.helstera.ai.skill.SkillSignals();
        var signal = new java.util.concurrent.atomic.AtomicReference<String>();
        var source = new java.util.concurrent.atomic.AtomicReference<Integer>();
        dev.helstera.ai.skill.SkillSignals.resetGlobal(bus);
        bus.subscribe(17, "probe", (name, payload, sourceId) -> {
            signal.set(payload);
            source.set(sourceId);
        });
        try {
            assertTrue(registry.runAction(svc.bindAction("signal probe payload"), context));
            assertEquals("payload", signal.get());
            assertEquals(17, source.get());
            assertTrue(registry.runAction(svc.bindAction("signal{signal=probe;payload=keyed}"), context));
            assertEquals("keyed", signal.get());
        } finally {
            dev.helstera.ai.skill.SkillSignals.resetGlobal(null);
        }
    }

    @Test
    @DisplayName("kill-targets kills selected entities without killing the caster")
    void killTargetsKillsSelectedEntities() {
        var svc = service();
        var instance = mock(ModelInstance.class);
        var caster = mock(LivingEntity.class);
        var target = mock(LivingEntity.class);
        when(instance.isValid()).thenReturn(true);
        when(instance.baseEntity()).thenReturn(Optional.of(caster));
        when(target.isValid()).thenReturn(true);
        when(target.isDead()).thenReturn(false);
        var context = BehaviorContext.continueExecution(instance, target, 1.0, 1.0, 0,
                "IDLE", null, caster, List.of(target), null, null);
        String key = svc.bindAction("kill-targets");
        assertTrue(registry.runAction(key, context));
        verify(target).setHealth(0);
    }

    @Test
    @DisplayName("target-has-item and target-potion-effect-active use only the explicit target")
    void targetInventoryAndPotionConditionsUseOnlyTarget() {
        var svc = service();
        var instance = mock(ModelInstance.class);
        var caster = mock(Player.class);
        var target = mock(Player.class);
        var casterInventory = mock(org.bukkit.inventory.PlayerInventory.class);
        var targetInventory = mock(org.bukkit.inventory.PlayerInventory.class);
        var expected = mock(ItemStack.class);
        var actual = mock(ItemStack.class);
        when(instance.isValid()).thenReturn(true);
        when(instance.baseEntity()).thenReturn(Optional.of(caster));
        when(caster.getInventory()).thenReturn(casterInventory);
        when(target.getInventory()).thenReturn(targetInventory);
        when(expected.getType()).thenReturn(Material.SHIELD);
        when(actual.getType()).thenReturn(Material.SHIELD);
        when(actual.getAmount()).thenReturn(1);
        when(actual.isSimilar(org.mockito.ArgumentMatchers.any())).thenReturn(true);
        when(casterInventory.getContents()).thenReturn(new ItemStack[]{actual});
        when(targetInventory.getContents()).thenReturn(new ItemStack[0]);

        when(target.getActivePotionEffects()).thenReturn(java.util.Set.of());

        var context = BehaviorContext.of(instance, target, 1.0, -1, 0, "IDLE");
        SkillExtras.itemResolver(name -> expected);
        try {
            assertFalse(registry.testCondition(svc.bindCondition("target-has-item SHIELD >=1"), context));
            assertFalse(registry.testCondition(svc.bindCondition("target-potion-effect-active SPEED"), context));
            var noTarget = BehaviorContext.of(instance, (LivingEntity) null, 1.0, -1, 0, "IDLE");
            assertFalse(registry.testCondition(svc.bindCondition("target-has-item SHIELD >=1"), noTarget));
            assertFalse(registry.testCondition(svc.bindCondition("target-potion-effect-active SPEED"), noTarget));

            when(targetInventory.getContents()).thenReturn(new ItemStack[]{actual});
            assertTrue(registry.testCondition(svc.bindCondition("target-has-item SHIELD >=1"), context));
        } finally {
            SkillExtras.itemResolver(null);
        }
    }

    @Test
    @DisplayName("target holding and wearing conditions use only the explicit target")
    void targetEquipmentConditionsUseOnlyTarget() {
        var svc = service();
        var instance = mock(ModelInstance.class);
        var caster = mock(Player.class);
        var target = mock(LivingEntity.class);
        var casterEquipment = mock(org.bukkit.inventory.EntityEquipment.class);
        var targetEquipment = mock(org.bukkit.inventory.EntityEquipment.class);
        var diamond = mock(Material.class);
        when(instance.isValid()).thenReturn(true);
        when(instance.baseEntity()).thenReturn(Optional.of(caster));
        when(caster.getEquipment()).thenReturn(casterEquipment);
        when(target.getEquipment()).thenReturn(targetEquipment);
        when(diamond.name()).thenReturn("DIAMOND");
        var held = mock(ItemStack.class);
        var helmet = mock(ItemStack.class);
        when(held.getType()).thenReturn(diamond);
        when(helmet.getType()).thenReturn(diamond);
        when(casterEquipment.getItemInMainHand()).thenReturn(held);
        when(casterEquipment.getHelmet()).thenReturn(helmet);
        when(targetEquipment.getItemInMainHand()).thenReturn(null);
        when(targetEquipment.getHelmet()).thenReturn(null);
        when(target.getActivePotionEffects()).thenReturn(java.util.Set.of());

        var context = BehaviorContext.of(instance, target, 1.0, -1, 0, "IDLE");
        assertFalse(registry.testCondition(svc.bindCondition("target-holding DIAMOND"), context));
        assertFalse(registry.testCondition(svc.bindCondition("target-wearing DIAMOND"), context));
        assertFalse(registry.testCondition(svc.bindCondition("target-has-auras"), context));

        var noTarget = BehaviorContext.of(instance, (LivingEntity) null, 1.0, -1, 0, "IDLE");
        assertFalse(registry.testCondition(svc.bindCondition("target-holding DIAMOND"), noTarget));
        assertFalse(registry.testCondition(svc.bindCondition("target-wearing DIAMOND"), noTarget));
        assertFalse(registry.testCondition(svc.bindCondition("target-has-auras"), noTarget));

        when(targetEquipment.getItemInMainHand()).thenReturn(held);
        when(targetEquipment.getHelmet()).thenReturn(helmet);
        assertTrue(registry.testCondition(svc.bindCondition("target-holding DIAMOND"), context));
        assertTrue(registry.testCondition(svc.bindCondition("target-wearing DIAMOND"), context));
    }

    @Test
    @DisplayName("damage-cause reads the original Bukkit damage event cause")
    void damageCauseConditionReadsEventContext() {
        var svc = service();
        var context = BehaviorContext.continueExecution(null, (Player) null,
                1.0, -1, 0, "damage", null, null, List.of(), null,
                new dev.helstera.api.behavior.SkillTreeContext(null, List.of(), null),
                null, org.bukkit.event.entity.EntityDamageEvent.DamageCause.ENTITY_ATTACK);
        String key = svc.bindCondition("damage-cause ENTITY_ATTACK");

        assertNotNull(key, svc.warnings().toString());
        assertTrue(registry.testCondition(key, context));
        assertFalse(registry.testCondition(svc.bindCondition("damage-cause FIRE"), context));
        assertFalse(registry.testCondition(key, ctx(1.0, 0)));
    }

    @Test
    @DisplayName("last-damage-cause reads the entity's stored damage event, not context cause")
    void lastDamageCauseReadsEntityState() {
        var svc = service();
        var entity = mock(LivingEntity.class);
        var event = mock(org.bukkit.event.entity.EntityDamageEvent.class);
        when(event.getCause()).thenReturn(org.bukkit.event.entity.EntityDamageEvent.DamageCause.FIRE);
        when(entity.getLastDamageCause()).thenReturn(event);
        var context = BehaviorContext.of(null, entity, 1.0, -1, 0, "damage");
        String key = svc.bindCondition("last-damage-cause FIRE");

        assertNotNull(key, svc.warnings().toString());
        assertTrue(registry.testCondition(key, context));
        assertFalse(registry.testCondition(svc.bindCondition("last-damage-cause ENTITY_ATTACK"), context));
    }

    @Test
    @DisplayName("has-item counts player inventory stacks and supports slot filtering")
    void hasItemConditionCountsInventory() {
        var svc = service();
        var player = mock(Player.class);
        var inventory = mock(org.bukkit.inventory.PlayerInventory.class);
        var expected = mock(org.bukkit.inventory.ItemStack.class);
        var diamonds = mock(org.bukkit.inventory.ItemStack.class);
        when(expected.getType()).thenReturn(org.bukkit.Material.DIAMOND);
        when(diamonds.getType()).thenReturn(org.bukkit.Material.DIAMOND);
        when(diamonds.getAmount()).thenReturn(2);
        when(diamonds.isSimilar(org.mockito.ArgumentMatchers.any())).thenReturn(true);
        when(player.getInventory()).thenReturn(inventory);
        when(inventory.getContents()).thenReturn(new org.bukkit.inventory.ItemStack[]{diamonds});
        var instance = mock(ModelInstance.class);
        when(instance.isValid()).thenReturn(true);
        when(instance.baseEntity()).thenReturn(Optional.of(player));
        var context = BehaviorContext.execution(instance, null, 1.0, -1, 0,
                "IDLE", null, player, List.of(player), null);

        dev.helstera.ai.skill.SkillExtras.itemResolver(name -> expected);
        try {
            String key = svc.bindCondition("hasItem{item=DIAMOND;amount=>=2}");
            assertNotNull(key, svc.warnings().toString());
            assertTrue(registry.testCondition(key, context));
            when(diamonds.getAmount()).thenReturn(1);
            assertFalse(registry.testCondition(key, context));
            assertFalse(registry.testCondition(svc.bindCondition("has-item DIAMOND >=1"),
                    BehaviorContext.of(null, (Player) null, 1.0, -1, 0, "IDLE")));
        } finally {
            dev.helstera.ai.skill.SkillExtras.itemResolver(null);
        }
    }

    @Test
    @DisplayName("has-item resolves custom items after the condition was bound")
    void hasItemResolvesLateInjectedCustomItem() {
        var svc = service();
        var player = mock(Player.class);
        var inventory = mock(org.bukkit.inventory.PlayerInventory.class);
        var expected = mock(org.bukkit.inventory.ItemStack.class);
        var actual = mock(org.bukkit.inventory.ItemStack.class);
        when(player.getInventory()).thenReturn(inventory);
        when(expected.getType()).thenReturn(org.bukkit.Material.DIAMOND);
        when(actual.getType()).thenReturn(org.bukkit.Material.DIAMOND);
        when(actual.getAmount()).thenReturn(1);
        when(actual.isSimilar(org.mockito.ArgumentMatchers.any())).thenReturn(true);
        when(inventory.getContents()).thenReturn(new org.bukkit.inventory.ItemStack[]{actual});
        var context = BehaviorContext.of(null, player, 1.0, -1, 0, "IDLE");

        SkillExtras.itemResolver(null);
        String key = svc.bindCondition("has-item integration_test_item >=1");
        SkillExtras.itemResolver(name -> expected);
        try {
            assertTrue(registry.testCondition(key, context));
        } finally {
            SkillExtras.itemResolver(null);
        }
    }

    @Test
    @DisplayName("give-item and take-item operate on the active player target")
    void giveAndTakeItemActionsUsePlayerInventory() {
        Player player = mock(Player.class);
        org.bukkit.inventory.PlayerInventory inventory = mock(org.bukkit.inventory.PlayerInventory.class);
        org.bukkit.inventory.ItemStack resolved = mock(org.bukkit.inventory.ItemStack.class);
        org.bukkit.Material material = mock(org.bukkit.Material.class);
        when(resolved.getType()).thenReturn(material);
        when(material.name()).thenReturn("DIAMOND");
        when(resolved.clone()).thenReturn(resolved);
        when(resolved.getAmount()).thenReturn(2);
        when(player.getInventory()).thenReturn(inventory);
        when(inventory.addItem(org.mockito.ArgumentMatchers.any())).thenReturn(new java.util.HashMap<>());
        var context = BehaviorContext.of(null, player, 1.0, -1, 0, "IDLE");

        SkillExtras.itemResolver(name -> resolved);
        try {
            SkillExtras.actions().get("give-item").create(List.of("DIAMOND", "2")).accept(context);
            var given = org.mockito.ArgumentCaptor.forClass(org.bukkit.inventory.ItemStack[].class);
            verify(inventory).addItem(given.capture());
            assertEquals(1, given.getValue().length);
            assertEquals(material, given.getValue()[0].getType());
            assertEquals(2, given.getValue()[0].getAmount());

            org.bukkit.inventory.ItemStack stack = mock(org.bukkit.inventory.ItemStack.class);
            when(stack.getType()).thenReturn(material);
            when(stack.getAmount()).thenReturn(3);
            when(inventory.getContents()).thenReturn(new org.bukkit.inventory.ItemStack[]{stack});
            SkillExtras.actions().get("take-item").create(List.of("DIAMOND", "2")).accept(context);
            verify(stack).setAmount(1);
        } finally {
            SkillExtras.itemResolver(null);
        }
    }

    @Test
    void customItemConditionsAndRemovalRespectItemIdentity() {
        var svc = service();
        Player player = mock(Player.class);
        var inventory = mock(org.bukkit.inventory.PlayerInventory.class);
        var material = mock(org.bukkit.Material.class);
        when(material.name()).thenReturn("DIAMOND");
        var expected = mock(org.bukkit.inventory.ItemStack.class);
        var other = mock(org.bukkit.inventory.ItemStack.class);
        var matching = mock(org.bukkit.inventory.ItemStack.class);
        when(expected.getType()).thenReturn(material);
        when(other.getType()).thenReturn(material);
        when(matching.getType()).thenReturn(material);
        when(other.getAmount()).thenReturn(5);
        when(matching.getAmount()).thenReturn(2);
        when(matching.isSimilar(expected)).thenReturn(true);
        when(player.getInventory()).thenReturn(inventory);
        when(inventory.getContents()).thenReturn(new org.bukkit.inventory.ItemStack[]{other, matching});
        var context = BehaviorContext.of(null, player, 1.0, -1, 0, "IDLE");
        SkillExtras.itemResolver(name -> expected);
        try {
            assertFalse(registry.testCondition(svc.bindCondition("has-item custom:gem >=3"), context));
            assertTrue(registry.testCondition(svc.bindCondition("has-item custom:gem >=2"), context));
            assertTrue(registry.testCondition(svc.bindCondition("has-item DIAMOND >=7"), context));
            SkillExtras.actions().get("take-item").create(List.of("custom:gem", "1")).accept(context);
            verify(matching).setAmount(1);
            verify(other, org.mockito.Mockito.never()).setAmount(org.mockito.ArgumentMatchers.anyInt());
        } finally {
            SkillExtras.itemResolver(null);
        }
    }

    @Test
    void claimedInvalidItemDoesNotFallBackInSkillConditions() {
        var svc = service();
        var player = mock(Player.class);
        var context = BehaviorContext.of(null, player, 1.0, -1, 0, "IDLE");
        SkillExtras.itemResolver(name -> null);
        SkillExtras.claimedItemPredicate(name -> name.equals("DIAMOND"));
        try (var construction = org.mockito.Mockito.mockConstruction(org.bukkit.inventory.ItemStack.class)) {
            assertFalse(registry.testCondition(svc.bindCondition("has-item DIAMOND >=1"), context));
            assertTrue(construction.constructed().isEmpty());
        } finally {
            SkillExtras.itemResolver(null);
            SkillExtras.claimedItemPredicate(null);
        }
    }

    @Test
    @DisplayName("target mechanics fall back to targetEntity when inherited targets are empty")
    void targetMechanicsUseActiveSingleTarget() {
        LivingEntity target = mock(LivingEntity.class);
        when(target.getMaxHealth()).thenReturn(20.0);
        when(target.getHealth()).thenReturn(10.0);
        BehaviorContext context = BehaviorContext.continueExecution(null, target, 0.5, 2.0,
                0, "target-test", null, target, List.of(), null, null);

        SkillCatalog.actions().get("heal-targets").create(List.of("4")).accept(context);
        SkillCatalog.actions().get("ignite-targets").create(List.of("40")).accept(context);

        verify(target).setHealth(14.0);
        verify(target).setFireTicks(40);
    }

    @Test
    @DisplayName("placeholder-backed target mechanics resolve numbers at execution time")
    void targetMechanicsResolveNumericPlaceholders() {
        LivingEntity target = mock(LivingEntity.class);
        when(target.getMaxHealth()).thenReturn(20.0);
        when(target.getHealth()).thenReturn(10.0);
        BehaviorContext context = BehaviorContext.continueExecution(null, target, 0.5, 2.0,
                0, "target-test", null, target, List.of(), null, null);

        SkillCatalog.actions().get("heal-targets").create(List.of("<target.hp.raw>")).accept(context);

        verify(target).setHealth(20.0);
    }

    @Test
    @DisplayName("feed updates player food and saturation for the active target")
    void feedUpdatesPlayerFood() {
        var svc = service();
        Player player = mock(Player.class);
        when(player.getFoodLevel()).thenReturn(10);
        when(player.getSaturation()).thenReturn(2.0f);
        BehaviorContext context = BehaviorContext.of(null, player, 1.0, -1, 0, "IDLE");

        String action = svc.bindAction("feed 4");

        assertNotNull(action, svc.warnings().toString());
        assertTrue(registry.runAction(action, context));
        verify(player).setFoodLevel(14);
        verify(player).setSaturation(6.0f);
    }

    @Test
    @DisplayName("disarm removes the active target main-hand item")
    void disarmRemovesTargetMainHand() {
        var svc = service();
        LivingEntity target = mock(LivingEntity.class);
        EntityEquipment equipment = mock(EntityEquipment.class);
        ItemStack held = mock(ItemStack.class);
        when(target.getEquipment()).thenReturn(equipment);
        when(equipment.getItem(EquipmentSlot.HAND)).thenReturn(held);
        Material heldMaterial = mock(Material.class);
        when(heldMaterial.isAir()).thenReturn(false);
        when(held.getType()).thenReturn(heldMaterial);
        BehaviorContext context = BehaviorContext.of(null, target, 1.0, -1, 0, "IDLE");

        String action = svc.bindAction("disarm");

        assertNotNull(action, svc.warnings().toString());
        assertTrue(registry.hasAction(action), action);
        assertTrue(registry.runAction(action, context));
        verify(equipment).setItem(EquipmentSlot.HAND, null);
    }

    @Test
    @DisplayName("caster healing resolves target placeholders at execution time")
    void selfHealingResolvesNumericPlaceholder() {
        ModelInstance instance = mock(ModelInstance.class);
        LivingEntity caster = mock(LivingEntity.class);
        LivingEntity target = mock(LivingEntity.class);
        when(instance.isValid()).thenReturn(true);
        when(instance.baseEntity()).thenReturn(Optional.of(caster));
        when(caster.getHealth()).thenReturn(5.0);
        when(caster.getMaxHealth()).thenReturn(20.0);
        when(target.getHealth()).thenReturn(7.0);
        BehaviorContext context = BehaviorContext.continueExecution(instance, target, 1.0, 1.0,
                0, "heal-test", null, target, List.of(target), null, null);

        SkillCatalog.actions().get("heal-self")
                .create(List.of("<target.hp.raw>"))
                .accept(context);

        verify(caster).setHealth(12.0);
    }

    @Test
    @DisplayName("extended mechanics resolve placeholder-backed numeric values at execution time")
    void extendedMechanicsResolveNumericPlaceholders() {
        var casterInstance = mock(ModelInstance.class);
        LivingEntity caster = mock(LivingEntity.class);
        LivingEntity target = mock(LivingEntity.class);
        when(casterInstance.isValid()).thenReturn(true);
        when(casterInstance.baseEntity()).thenReturn(Optional.of(caster));
        when(caster.getMaxHealth()).thenReturn(20.0);
        when(target.getHealth()).thenReturn(7.0);
        var context = BehaviorContext.continueExecution(casterInstance, target, 1.0, 1.0,
                0, "placeholder-test", null, target, List.of(target), null, null);

        SkillExtras.actions().get("set-health").create(List.of("<target.hp.raw>")).accept(context);

        verify(caster).setHealth(7.0);
    }

    @Test
    @DisplayName("set-max-health updates the Bukkit max-health attribute")
    void setMaxHealthUpdatesAttribute() {
        ModelInstance instance = mock(ModelInstance.class);
        LivingEntity caster = mock(LivingEntity.class);
        org.bukkit.attribute.AttributeInstance attribute = mock(org.bukkit.attribute.AttributeInstance.class);
        when(instance.isValid()).thenReturn(true);
        when(instance.baseEntity()).thenReturn(Optional.of(caster));
        when(caster.getAttribute(org.bukkit.attribute.Attribute.GENERIC_MAX_HEALTH))
                .thenReturn(attribute);
        when(caster.getHealth()).thenReturn(10.0);
        when(attribute.getBaseValue()).thenReturn(20.0);
        BehaviorContext context = BehaviorContext.of(instance, (LivingEntity) null,
                1.0, -1, 0, "IDLE");

        SkillExtras.actions().get("set-max-health").create(List.of("40")).accept(context);

        verify(attribute).setBaseValue(40.0);
    }

    @Test
    @DisplayName("velocity target mechanics resolve placeholders at execution time")
    void velocityMechanicResolvesPlaceholder() {
        LivingEntity target = mock(LivingEntity.class);
        when(target.getHealth()).thenReturn(3.0);
        BehaviorContext context = BehaviorContext.continueExecution(null, target, 1.0, 1.0,
                0, "placeholder-test", null, target, List.of(), null, null);

        SkillCatalog.actions().get("velocity-targets")
                .create(List.of("<target.hp.raw>", "0", "0"))
                .accept(context);

        verify(target).setVelocity(org.mockito.ArgumentMatchers.argThat(vector ->
                Math.abs(vector.getX() - 3.0) < 1.0e-9
                        && Math.abs(vector.getY()) < 1.0e-9
                        && Math.abs(vector.getZ()) < 1.0e-9));
    }

    @Test
    @DisplayName("target-count condition counts the active single target")
    void targetCountUsesActiveSingleTarget() {
        var svc = service();
        LivingEntity target = mock(LivingEntity.class);
        BehaviorContext context = BehaviorContext.continueExecution(null, target, 1.0, 1.0,
                0, "target-test", null, target, List.of(), null, null);
        String key = svc.bindCondition("target-count-at-least 1");

        assertTrue(registry.testCondition(key, context));
    }

    @Test
    @DisplayName("target-within and target line-of-sight inspect the target's AI target")
    void targetAiRelationConditionsUseMobTarget() {
        var svc = service();
        var mob = mock(org.bukkit.entity.Mob.class);
        var aiTarget = mock(LivingEntity.class);
        var world = mock(World.class);
        when(mob.getWorld()).thenReturn(world);
        when(aiTarget.getWorld()).thenReturn(world);
        when(mob.getTarget()).thenReturn(aiTarget);
        when(mob.getLocation()).thenReturn(new Location(world, 0, 64, 0));
        when(aiTarget.getLocation()).thenReturn(new Location(world, 3, 64, 0));
        when(aiTarget.isValid()).thenReturn(true);
        when(mob.hasLineOfSight(aiTarget)).thenReturn(false);
        var context = BehaviorContext.of(null, mob, 1.0, -1, 0, "IDLE");

        assertTrue(registry.testCondition(svc.bindCondition("target-within <=3"), context));
        assertFalse(registry.testCondition(svc.bindCondition("target-not-within 3"), context));
        assertFalse(registry.testCondition(svc.bindCondition("target-has-line-of-sight"), context));
        assertTrue(registry.testCondition(svc.bindCondition("target-not-in-line-of-sight"), context));
    }

    @Test
    @DisplayName("entity invulnerability condition reads Bukkit state")
    void invulnerableConditionReadsEntityState() {
        var svc = service();
        var instance = mock(ModelInstance.class);
        var entity = mock(Entity.class);
        when(instance.isValid()).thenReturn(true);
        when(instance.baseEntity()).thenReturn(Optional.of(entity));
        when(entity.isInvulnerable()).thenReturn(true);
        var context = BehaviorContext.of(instance, (LivingEntity) null, 1.0, -1, 0, "IDLE");

        String key = svc.bindCondition("invulnerable");
        assertTrue(registry.testCondition(key, context));
        when(entity.isInvulnerable()).thenReturn(false);
        assertFalse(registry.testCondition(key, context));
    }

    @Test
    @DisplayName("带参数的条件被绑成确定性键 health-below:0.3")
    void bindsParameterizedCondition() {
        var svc = service();
        String key = svc.bindCondition("health-below 0.3");

        assertEquals("health-below:0.3", key);
        assertTrue(registry.hasCondition(key));
        assertFalse(registry.testCondition(key, ctx(0.5, 0)), "血量 0.5 不应满足 <=0.3");
        assertTrue(registry.testCondition(key, ctx(0.2, 0)), "血量 0.2 应满足 <=0.3");
    }

    @Test
    @DisplayName("同一条件重复绑定不会重复注册，也不产生重复告警")
    void bindsIdempotently() {
        var svc = service();
        svc.bindCondition("health-below 0.3");
        svc.bindCondition("health-below 0.3");

        assertEquals(1, registry.conditionNames().size());
        assertEquals(List.of(), svc.warnings());
    }

    @Test
    @DisplayName("未知条件返回 null 并留下告警，理由里带可用内置名")
    void warnsOnUnknownCondition() {
        var svc = service();
        assertNull(svc.bindCondition("no-such-condition 1"));
        assertEquals(1, svc.warnings().size());
        assertTrue(svc.warnings().get(0).contains("未知条件"), svc.warnings().toString());
        assertTrue(svc.warnings().get(0).contains("health-below"), "应提示可用内置条件");
    }

    @Test
    @DisplayName("Java 侧注册的条件可直接按名引用，不算未知")
    void referencesRegisteredConditionByName() {
        registry.registerCondition("my-flag", c -> true);
        var svc = service();

        assertEquals("my-flag", svc.bindCondition("my-flag"));
        assertEquals(List.of(), svc.warnings());
        assertTrue(registry.testCondition("my-flag", ctx(1, 0)));
    }

    @Test
    @DisplayName("has-target：无目标时为 false，显式 false 参数反转语义")
    void hasTargetCondition() {
        var svc = service();
        String withTarget = svc.bindCondition("has-target");
        String withoutTarget = svc.bindCondition("has-target false");

        assertEquals("has-target", withTarget);
        assertEquals("has-target:false", withoutTarget);
        assertFalse(registry.testCondition(withTarget, ctx(1, 0)));
        assertTrue(registry.testCondition(withoutTarget, ctx(1, 0)));
    }

    @Test
    @DisplayName("health-above / health-below 边界取闭区间")
    void healthThresholdBoundaries() {
        var svc = service();
        String below = svc.bindCondition("health-below 0.5");
        String above = svc.bindCondition("health-above 0.5");

        assertTrue(registry.testCondition(below, ctx(0.5, 0)));
        assertTrue(registry.testCondition(above, ctx(0.5, 0)));
        assertFalse(registry.testCondition(below, ctx(0.51, 0)));
        assertFalse(registry.testCondition(above, ctx(0.49, 0)));
    }

    @Test
    @DisplayName("距离类条件：无目标（距离 -1）一律不满足")
    void distanceConditionsRequireTarget() {
        var svc = service();
        String near = svc.bindCondition("distance-below 5");
        String far = svc.bindCondition("distance-above 5");

        assertFalse(registry.testCondition(near, ctx(1, 0)), "distance=-1 不应满足 <=5");
        assertFalse(registry.testCondition(far, ctx(1, 0)));
    }

    @Test
    @DisplayName("Mythic health, health-percent, and distance comparators evaluate keyed syntax")
    void mythicComparatorsEvaluateKeyedSyntax() {
        LivingEntity target = mock(LivingEntity.class);
        when(target.getHealth()).thenReturn(8.0);
        when(target.getMaxHealth()).thenReturn(20.0);
        when(target.getAbsorptionAmount()).thenReturn(4.0);
        var context = BehaviorContext.of(null, target, 1.0, 7.5, 0, "IDLE");
        var svc = service();

        String healthBelow = svc.bindCondition("health{h=<10}");
        String healthPercent = svc.bindCondition("health-percent{p=>=40}");
        String distanceBelow = svc.bindCondition("distance{d=<8}");
        String distanceAbove = svc.bindCondition("distance{d=>8}");
        String targetHealth = svc.bindCondition("target-health{h=<10}");
        String targetHealthPercent = svc.bindCondition("target-health-percent{p=>=40}");
        String targetHealthBelow = svc.bindCondition("target-health-below{p=>=50}");
        String targetHealthAbove = svc.bindCondition("target-health-above{p=>=40}");
        String healthEqual = svc.bindCondition("health{h==8}");
        String healthNotEqual = svc.bindCondition("health{h!=8}");
        String healthWithAbsorption = svc.bindCondition(
                "health{h=>=12;includeabsorption=true}");
        String healthWithoutAbsorption = svc.bindCondition("health{h=>=12}");
        String healthPercentWithAbsorption = svc.bindCondition(
                "health-percent{p=>=60;includeabsorption=true}");
        String distanceEqual = svc.bindCondition("distance{d==7.5}");
        String targetHealthEqual = svc.bindCondition("target-health{h==8}");
        String lightLevelEqual = svc.bindCondition("lightLevel{level==8}");

        assertTrue(registry.testCondition(healthBelow, context));
        assertTrue(registry.testCondition(healthPercent, context));
        assertTrue(registry.testCondition(distanceBelow, context));
        assertFalse(registry.testCondition(distanceAbove, context));
        assertTrue(registry.testCondition(targetHealth, context));
        assertTrue(registry.testCondition(targetHealthPercent, context));
        assertFalse(registry.testCondition(targetHealthBelow, context));
        assertTrue(registry.testCondition(targetHealthAbove, context));
        assertTrue(registry.testCondition(healthEqual, context));
        assertFalse(registry.testCondition(healthNotEqual, context));
        assertTrue(registry.testCondition(healthWithAbsorption, context));
        assertFalse(registry.testCondition(healthWithoutAbsorption, context));
        assertTrue(registry.testCondition(healthPercentWithAbsorption, context));
        assertTrue(registry.testCondition(distanceEqual, context));
        assertTrue(registry.testCondition(targetHealthEqual, context));
        assertNotNull(lightLevelEqual);
        assertFalse(registry.testCondition(svc.bindCondition("health{h=invalid}"), context));
    }

    @Test
    @DisplayName("state-is 大小写不敏感")
    void stateConditionIsCaseInsensitive() {
        var svc = service();
        String key = svc.bindCondition("state-is chase");

        assertTrue(registry.testCondition(key, ctxWithState("CHASE")));
        assertTrue(registry.testCondition(key, ctxWithState("chase")));
        assertFalse(registry.testCondition(key, ctxWithState("IDLE")));
    }

    @Test
    @DisplayName("condition composition supports all, any, not, and boolean operators")
    void composesConditions() {
        var svc = service();
        String all = svc.bindCondition("all{health-above 0.5;state-is idle}");
        String any = svc.bindCondition("health-below 0.2 || state-is idle");
        String not = svc.bindCondition("not health-below 0.2");

        assertTrue(registry.testCondition(all, ctxWithState("IDLE")));
        assertTrue(registry.testCondition(any, ctxWithState("IDLE")));
        assertTrue(registry.testCondition(not, ctx(0.8, 0)));
        assertFalse(registry.testCondition(all, ctxWithState("CHASE")));
    }

    @Test
    @DisplayName("location conditions use an origin when no model instance exists")
    void locationConditionsUseOriginContext() {
        var svc = service();
        var world = org.mockito.Mockito.mock(org.bukkit.World.class);
        when(world.getName()).thenReturn("integration-world");
        var location = org.mockito.Mockito.mock(org.bukkit.Location.class);
        when(location.getWorld()).thenReturn(world);
        when(location.clone()).thenReturn(location);
        var context = BehaviorContext.execution(null, null, 1.0, -1, 0,
                "spawner", null, mock(org.bukkit.entity.Entity.class), List.of(), location);

        String worldCondition = svc.bindCondition("world-is integration-world");
        assertTrue(registry.testCondition(worldCondition, context));
    }

    @Test
    @DisplayName("entity-type-is checks the active target and stance-is checks execution state")
    void entityTypeAndStanceConditions() {
        var svc = service();
        ModelInstance instance = mock(ModelInstance.class);
        when(instance.isValid()).thenReturn(true);
        when(instance.instanceId()).thenReturn(902);
        LivingEntity target = mock(LivingEntity.class);
        when(target.getType()).thenReturn(org.bukkit.entity.EntityType.ZOMBIE);
        var execution = BehaviorContext.of(instance, target, 1.0, -1, 0, "ATTACK");

        String type = svc.bindCondition("entity-type-is zombie");
        String stance = svc.bindCondition("stance-is enraged");
        String setStance = svc.bindAction("set-stance enraged");
        assertNotNull(setStance, svc.warnings().toString());
        assertTrue(registry.runAction(setStance, execution));

        assertTrue(registry.testCondition(type, execution));
        assertTrue(registry.testCondition(stance, execution));
        assertFalse(registry.testCondition(svc.bindCondition("entity-type-is creeper"), execution));
        SkillExtras.forget(902);
    }

    @Test
    @DisplayName("every-n-decisions：缺省每次都满足，N>1 时按次数取模")
    void everyNDecisions() {
        var svc = service();
        String everyOne = svc.bindCondition("every-n-decisions");
        String everyThree = svc.bindCondition("every-n-decisions 3");

        assertTrue(registry.testCondition(everyOne, ctx(1, 7)));
        assertFalse(registry.testCondition(everyThree, ctx(1, 7)), "7 % 3 != 0");
        assertTrue(registry.testCondition(everyThree, ctx(1, 9)));
    }

    /**
     * 非法数字参数：装载期拒绝注册，并在 warnings 里说明原因。
     *
     * <p><b>这个测试的行为期望被反转过。</b>原名是
     * {@code malformedNumberFallsBack}，断言「非法数字回落到默认值，不抛异常」——
     * 那正是本轮要修掉的缺陷。</p>
     *
     * <p>旧行为的问题：{@code health-below abc} 会注册成一个阈值 0.0 的条件，
     * 于是「血量低于 abc」实际变成「血量低于 0」，永远不成立。技能安静地
     * 永不触发，{@code /helstera check} 与日志都没有任何提示——
     * 现场无法与「阈值设得本来就高」区分。</p>
     *
     * <p>现在参数非法时 {@code bindCondition} 返回 null（不注册）并记 warning，
     * 作者能在体检里直接看到「参数非法」与原始字面量。</p>
     */
    @Test
    @DisplayName("非法数字参数在装载期被拒绝，并记入 warnings")
    void malformedNumberIsRejectedWithWarning() {
        var svc = service();
        String key = svc.bindCondition("health-below abc");

        assertNull(key, "参数非法的条件不应被注册成阈值 0.0 的怪条件");
        assertTrue(svc.warnings().stream().anyMatch(w -> w.contains("abc")),
                "warnings 应包含原始字面量，便于定位：实际 = " + svc.warnings());
    }

    @Test
    @DisplayName("引号内的空格不被切分：message-target 可带整句文案")
    void keepsQuotedSpaces() {
        var svc = service();
        String key = svc.bindAction("message-target \"你好 世界\"");

        assertEquals("message-target:你好 世界", key);
    }

    @Test
    @DisplayName("命名技能：require 全满足才算一个整体条件，on-decision 顺序执行")
    void loadsNamedSkill() {
        var svc = service();
        YamlConfiguration y = YamlConfiguration.loadConfiguration(new StringReader("""
                skills:
                  tank:
                    require:
                      - health-above 0.8
                      - state-is idle
                    on-decision:
                      - set-scale 1.5
                """));

        svc.loadSkills(y.getConfigurationSection("skills"));

        String c = svc.bindCondition("skill:tank");
        String a = svc.bindAction("skill:tank");
        assertEquals("skill:tank", c);
        assertEquals("skill:tank", a);

        // health 1.0 + IDLE → 两个 require 均满足
        assertTrue(registry.testCondition(c, ctxWithState("IDLE")));
        assertFalse(registry.testCondition(c, ctxWithState("CHASE")));
        // 实例为空时 set-scale 会自行跳过，不应抛异常
        assertTrue(registry.runAction(a, ctxWithState("IDLE")));
    }

    @Test
    @DisplayName("技能引用未知条件时告警并拒绝执行")
    void namedSkillRejectsUnknownConditions() {
        var svc = service();
        YamlConfiguration y = YamlConfiguration.loadConfiguration(new StringReader("""
                skills:
                  broken:
                    require:
                      - nonsense
                      - health-above 0.5
                    on-decision:
                      - stop-animation
                """));

        svc.loadSkills(y.getConfigurationSection("skills"));
        assertEquals(1, svc.warnings().size());

        String c = svc.bindCondition("skill:broken");
        assertNotNull(c);
        assertFalse(registry.testCondition(c, ctx(0.9, 0)));
        assertFalse(svc.castSkill("broken", ctx(0.9, 0)));
    }

    @Test
    void negatedSkillConditionsFailClosedOnInvalidOrThrowingRequirements() {
        var svc = service();
        registry.registerCondition("broken-check", context -> { throw new IllegalStateException("failure"); });
        registry.registerCondition("normal-false", context -> false);
        var yaml = YamlConfiguration.loadConfiguration(new StringReader("""
                skills:
                  throwing:
                    require: [broken-check]
                  invalid:
                    require: [unknown-check]
                  ordinary:
                    require: [normal-false]
                """));
        svc.loadSkills(yaml.getConfigurationSection("skills"));
        for (String spec : List.of("!skill:throwing", "!skill:invalid", "not{not{skill:throwing}}")) {
            String condition = svc.bindCondition(spec);
            assertNotNull(condition, spec);
            assertFalse(registry.testCondition(condition, ctx(1.0, 0)), spec);
        }
        assertTrue(registry.testCondition(svc.bindCondition("!skill:ordinary"), ctx(1.0, 0)));
        var calls = new AtomicInteger();
        registry.registerAction("probe", context -> calls.incrementAndGet());
        String action = svc.bindAction("probe ?!skill:throwing");
        assertNotNull(action);
        assertTrue(registry.runAction(action, ctx(1.0, 0)));
        assertEquals(0, calls.get());
    }

    @Test
    void removedNamedSkillDoesNotSurviveReloadInRegistry() {
        var svc = service();
        var calls = new AtomicInteger();
        registry.registerAction("probe", context -> calls.incrementAndGet());
        var original = YamlConfiguration.loadConfiguration(new StringReader("""
                skills:
                  example:
                    on-decision: [probe]
                """));
        svc.loadSkills(original.getConfigurationSection("skills"));
        String condition = svc.bindCondition("skill:example");
        String negated = svc.bindCondition("!skill:example");
        assertTrue(registry.testCondition(condition, ctx(1.0, 0)));
        registry.runAction("skill:example", ctx(1.0, 0));
        assertEquals(1, calls.get());

        svc.loadSkills(null);
        assertFalse(registry.testCondition(condition, ctx(1.0, 0)));
        assertFalse(registry.testCondition(negated, ctx(1.0, 0)));
        registry.runAction("skill:example", ctx(1.0, 0));
        assertEquals(1, calls.get());

        svc.loadSkills(original.getConfigurationSection("skills"));
        assertTrue(registry.testCondition(condition, ctx(1.0, 0)));
        registry.runAction("skill:example", ctx(1.0, 0));
        assertEquals(2, calls.get());
    }

    @Test
    void reloadDuringDirectNamedSkillStopsRemainingActions() {
        var svc = service();
        var calls = new AtomicInteger();
        registry.registerAction("reload-now", context -> svc.loadSkills(null));
        registry.registerAction("probe", context -> calls.incrementAndGet());
        var yaml = YamlConfiguration.loadConfiguration(new StringReader("""
                skills:
                  example:
                    on-decision: [reload-now, probe]
                """));
        svc.loadSkills(yaml.getConfigurationSection("skills"));
        registry.runAction("skill:example", ctx(1.0, 0));
        assertEquals(0, calls.get());
    }

    @Test
    void reloadDuringNamedSkillRequirementFailsClosedUnderNegation() {
        var svc = service();
        registry.registerCondition("reload-check", context -> {
            svc.loadSkills(null);
            return false;
        });
        var yaml = YamlConfiguration.loadConfiguration(new StringReader("""
                skills:
                  example:
                    require: [reload-check]
                """));
        svc.loadSkills(yaml.getConfigurationSection("skills"));
        String condition = svc.bindCondition("!skill:example");
        assertNotNull(condition);
        assertFalse(registry.testCondition(condition, ctx(1.0, 0)));
    }

    @Test
    void namedSkillConditionsResolveBeforeDefinitionsAreBound() {
        var svc = service();
        var calls = new AtomicInteger();
        registry.registerAction("probe", context -> calls.incrementAndGet());
        registry.registerCondition("probe-false", context -> false);
        var yaml = YamlConfiguration.loadConfiguration(new StringReader("""
                skills:
                  Parent:
                    require: ['!skill:Child']
                    on-decision: [probe]
                  Child:
                    require: [probe-false]
                  Missing:
                    require: ['!skill:Absent']
                    on-decision: [probe]
                """));
        svc.loadSkills(yaml.getConfigurationSection("skills"));
        assertTrue(svc.castSkill("parent", ctx(1.0, 0)));
        assertEquals(1, calls.get());
        assertFalse(svc.castSkill("missing", ctx(1.0, 0)));
        assertNull(svc.bindCondition("!skill:absent"));
        assertEquals(1, calls.get());
    }

    @Test
    void namedSkillConditionCyclesFailClosedAndDoNotPoisonLaterEvaluations() {
        var svc = service();
        var yaml = YamlConfiguration.loadConfiguration(new StringReader("""
                skills:
                  first:
                    require: ['!skill:second']
                  second:
                    require: ['!skill:first']
                  ordinary: {}
                """));
        svc.loadSkills(yaml.getConfigurationSection("skills"));
        assertFalse(registry.testCondition(svc.bindCondition("!skill:first"), ctx(1.0, 0)));
        assertFalse(registry.testCondition(svc.bindCondition("skill:second"), ctx(1.0, 0)));
        assertTrue(registry.testCondition(svc.bindCondition("skill:ordinary"), ctx(1.0, 0)));
    }

    @Test
    @DisplayName("loadSkills 对 null 节安全返回")
    void toleratesNullSection() {
        var svc = service();
        svc.loadSkills(null);
        assertEquals(List.of(), svc.warnings());
    }

    @Test
    @DisplayName("expandTriggers 把触发器里的文本定义换成已绑定键")
    void expandsTriggersInPlace() {
        var svc = service();
        var profile = new AiProfile("boss");
        var spec = new AiProfile.TriggerSpec();
        spec.require.add("health-below 0.4");
        spec.actions.add("play-animation roar true 5");
        profile.triggers.put("on-damage", spec);

        svc.expandTriggers(profile);

        assertEquals(List.of("health-below:0.4"), profile.triggers.get("on-damage").require);
        assertEquals(List.of("play-animation:roar true 5"), profile.triggers.get("on-damage").actions);
        assertTrue(registry.hasCondition("health-below:0.4"));
        assertTrue(registry.hasAction("play-animation:roar true 5"));
    }

    @Test
    @DisplayName("expand 把命名技能同时挂到 require 与 onDecision")
    void expandsNamedSkillsOntoProfile() {
        var svc = service();
        YamlConfiguration y = YamlConfiguration.loadConfiguration(new StringReader("""
                skills:
                  frenzy:
                    require:
                      - health-below 0.3
                    on-decision:
                      - set-scale 1.2
                """));
        svc.loadSkills(y.getConfigurationSection("skills"));

        var profile = new AiProfile("berserker");
        svc.expand(profile, List.of("frenzy"));

        assertEquals(List.of("skill:frenzy"), profile.require);
        assertEquals(List.of("skill:frenzy"), profile.onDecision);
    }

    @Test
    @DisplayName("注册表：条件抛异常按 false 处理，异常不外泄到决策链")
    void registrySwallowsConditionErrors() {
        registry.registerCondition("boom", c -> {
            throw new IllegalStateException("炸了");
        });

        assertFalse(registry.testCondition("boom", ctx(1, 0)));
    }

    @Test
    @DisplayName("注册表：动作抛异常被吞掉并返回 false")
    void registrySwallowsActionErrors() {
        registry.registerAction("boom", c -> {
            throw new IllegalStateException("炸了");
        });

        assertFalse(registry.runAction("boom", ctx(1, 0)));
    }

    @Test
    @DisplayName("注册表：未知名求值不抛异常，分别返回 false")
    void registryToleratesUnknownNames() {
        assertFalse(registry.testCondition("ghost", ctx(1, 0)));
        assertFalse(registry.runAction("ghost", ctx(1, 0)));
        assertFalse(registry.hasCondition("ghost"));
        assertFalse(registry.hasAction("ghost"));
    }

    @Test
    @DisplayName("注册表：名称大小写不敏感，空名与 null 被忽略")
    void registryNormalizesNames() {
        registry.registerCondition("MixedCase", c -> true);
        registry.registerCondition(null, c -> true);
        registry.registerCondition("  ", c -> true);

        assertTrue(registry.testCondition("mixedcase", ctx(1, 0)));
        assertTrue(registry.testCondition("MIXEDCASE", ctx(1, 0)));
        assertEquals(1, registry.conditionNames().size(), "空名不应入库");
    }

    @Test
    @DisplayName("内置目录：条件名与动作名各自去重且非空")
    void catalogNamesAreStable() {
        Map<String, SkillCatalog.ConditionFactory> conds = SkillCatalog.conditions();
        Map<String, SkillCatalog.ActionFactory> acts = SkillCatalog.actions();

        assertTrue(conds.keySet().containsAll(List.of(
                "has-target", "health-below", "health-above",
                "distance-below", "distance-above", "state-is", "every-n-decisions")), conds.keySet().toString());
        assertTrue(acts.keySet().containsAll(List.of(
                "set-scale", "play-animation", "stop-animation",
                "set-intent", "message-target")), acts.keySet().toString());
    }

    @Test
    @DisplayName("Pin position targeters invoke actions once per location without entity targets")
    void pinTargeterDispatchesLocationContexts() {
        World world = mock(World.class);
        PinRegistry pins = new PinRegistry(name -> "world".equals(name) ? world : null);
        YamlConfiguration config = new YamlConfiguration();
        config.set("pins.Route.locations", List.of("world,1,2,3", "world,4,5,6"));
        pins.load(config);
        SkillExtras.pinRegistry(pins);
        List<Location> received = new java.util.ArrayList<>();
        registry.registerAction("location-probe", context -> {
            assertTrue(context.targetEntity().isEmpty());
            assertTrue(context.targets().isEmpty());
            received.add(context.targetLocation().orElseThrow());
        });
        try {
            var svc = service();
            String key = svc.bindAction("location-probe @Pin{name=route}");
            assertNotNull(key);
            BehaviorContext context = BehaviorContext.execution(null, null, 1, -1, 0,
                    "skill", null, null, List.of(), new Location(world, 0, 0, 0));

            assertTrue(registry.runAction(key, context));
            assertEquals(2, received.size());
            assertEquals(new Location(world, 1, 2, 3), received.get(0));
            assertEquals(new Location(world, 4, 5, 6), received.get(1));
        } finally {
            SkillExtras.pinRegistry(null);
        }
    }

    @Test
    @DisplayName("动作：实例无效时安全跳过，不抛 NullPointerException")
    void actionsSkipWhenInstanceInvalid() {
        var svc = service();
        for (String spec : List.of("set-scale 2.0", "play-animation walk", "stop-animation",
                "set-intent chase", "heal-self 5", "sound BLAZE", "particle FLAME")) {
            String key = svc.bindAction(spec);
            assertNotNull(key, spec);
            assertTrue(registry.runAction(key, ctx(1, 0)), spec + " 应在实例为空时安全返回");
        }
    }

    @Test
    @DisplayName("绑定动作名与条件名互不串扰")
    void doesNotCrossBindNames() {
        var svc = service();
        String a = svc.bindAction("message-target hi");
        assertNotNull(a);
        assertFalse(registry.hasCondition(a), "动作键不应被当成条件");
        assertTrue(registry.hasAction(a));
    }

    @Test
    @DisplayName("技能 priority 字段被正确解析，queuedSkillCount 反映排队状态")
    void parsesSkillPriority() {
        var svc = service();
        YamlConfiguration y = YamlConfiguration.loadConfiguration(new StringReader("""
                skills:
                  burst:
                    on-decision: [damage-target 50]
                    priority: 10
                  heal:
                    on-decision: [heal-self 10]
                    priority: 5
                  idle_skill:
                    on-decision: [set-scale 1.0]
                """));
        svc.loadSkills(y.getConfigurationSection("skills"));
        assertTrue(svc.skillNames().contains("burst"));
        assertTrue(svc.skillNames().contains("heal"));
        assertTrue(svc.skillNames().contains("idle_skill"));
        assertEquals(0, svc.queuedSkillCount());
    }

    // ------------------------------------------------------------------
    // 技能预览（只判定，不执行）
    // ------------------------------------------------------------------

    private SkillService skillServiceWith(String yaml) {
        var svc = service();
        YamlConfiguration y = YamlConfiguration.loadConfiguration(new StringReader(yaml));
        svc.loadSkills(y.getConfigurationSection("skills"));
        return svc;
    }

    @Test
    @DisplayName("preview 报告条件通过与否，并指出首条未满足项")
    void previewTracesConditions() {
        var svc = skillServiceWith("""
                skills:
                  enrage:
                    require:
                      - health-below 0.3
                      - has-target true
                    on-decision: [effect-self speed 2]
                """);
        // 满血：health-below 不满足，且无目标时 has-target 也不满足
        var full = svc.preview("enrage", ctx(1.0, 0));
        assertNotNull(full);
        assertFalse(full.wouldRun(), "满血时不该放行");
        assertEquals("health-below:0.3", full.firstFailing(), "应指出首条未满足的条件");
        assertEquals(2, full.trace().size(), "轨迹应逐条列出，两条都要能看到");

        // 残血：health-below 满足，但 has-target 仍因无目标而不满足。
        // 这正是预览的边界——它只能判定标量条件，依赖世界状态的条件
        // （on-ground / has-target 等）必须上真服验证。轨迹里的 ✗ 含义是
        // 「需要真服」，而不是「配置写错了」，命令输出里也这样提示。
        var low = svc.preview("enrage", ctx(0.1, 0));
        assertNotNull(low);
        assertTrue(low.trace().get(0).passed(), "残血时 health-below 应满足");
        assertFalse(low.trace().get(1).passed(), "无目标时 has-target 应不满足");
        assertEquals("has-target:true", low.firstFailing());
        assertFalse(low.actions().isEmpty(), "应列出将要执行的动作");
    }

    @Test
    @DisplayName("preview 在全部条件可由标量判定时给出确定结论")
    void previewReachesVerdictOnScalarOnlySkills() {
        var svc = skillServiceWith("""
                skills:
                  panic:
                    require:
                      - health-below 0.3
                      - state-is idle
                    on-decision: [set-scale 1.5]
                """);
        assertFalse(svc.preview("panic", ctx(1.0, 0)).wouldRun(), "满血不该放行");
        // 残血 + 状态匹配：两条都满足，才给确定结论
        assertTrue(svc.preview("panic", lowIdleCtx()).wouldRun(), "低血量且状态 idle 时应放行");
        // 残血 + 状态不匹配
        var wrongState = BehaviorContext.of(null, null, 0.1, -1, 0, "CHASE");
        assertFalse(svc.preview("panic", wrongState).wouldRun(), "状态不匹配不该放行");
    }

    /** 低血量 + idle 状态的上下文。 */
    private static BehaviorContext lowIdleCtx() {
        return BehaviorContext.of(null, null, 0.1, -1, 0, "idle");
    }

    @Test
    @DisplayName("preview 不执行动作——这是它能安全挂在真服上的前提")
    void previewDoesNotExecute() {
        var registry2 = new BehaviorRegistryImpl();
        var svc = new SkillService(registry2, null);
        YamlConfiguration y = YamlConfiguration.loadConfiguration(new StringReader("""
                skills:
                  boom:
                    on-decision: [damage-target 999]
                """));
        svc.loadSkills(y.getConfigurationSection("skills"));
        var r = svc.preview("boom", ctx(1.0, 0));
        assertNotNull(r);
        assertTrue(r.wouldRun(), "无 require 时视为恒满足");
        // 动作键已绑定但未被执行：跑一遍也不会有副作用，这里只断言调用本身安全
        assertFalse(r.actions().isEmpty());
    }

    @Test
    @DisplayName("preview 对不存在的技能返回 null，而不是抛异常")
    void previewUnknownSkill() {
        var svc = skillServiceWith("skills:\n  a:\n    on-decision: [set-scale 1.0]\n");
        assertNull(svc.preview("nope", ctx(1.0, 0)));
        assertNull(svc.preview(null, ctx(1.0, 0)));
        assertNull(svc.preview("  ", ctx(1.0, 0)));
    }

    @Test
    @DisplayName("preview 的轨迹顺序与 require 书写顺序一致")
    void previewTracePreservesOrder() {
        var svc = skillServiceWith("""
                skills:
                  ordered:
                    require:
                      - health-above 0.9
                      - state-is idle
                """);
        var p = svc.preview("ordered", ctx(1.0, 0));
        assertNotNull(p);
        assertEquals(2, p.trace().size());
        assertEquals("health-above:0.9", p.trace().get(0).key());
        assertEquals("state-is:idle", p.trace().get(1).key());
    }
    @Test
    @DisplayName("Mythic action metadata gates custom actions")
    void mythicActionMetadataGatesAction() {
        var svc = service();
        var fired = new AtomicInteger();
        var allowed = new AtomicBoolean(false);
        registry.registerCondition("allow", ctx -> allowed.get());
        registry.registerAction("probe", ctx -> fired.incrementAndGet());

        String gated = svc.bindAction("probe ?allow");
        assertNotNull(gated);
        assertFalse(gated.equals("probe"));
        assertTrue(registry.runAction(gated, ctx(1, 0)));
        assertEquals(0, fired.get());

        allowed.set(true);
        assertTrue(registry.runAction(gated, ctx(1, 0)));
        assertEquals(1, fired.get());

        String skipped = svc.bindAction("probe 0%");
        assertNotNull(skipped);
        assertTrue(registry.runAction(skipped, ctx(1, 0)));
        assertEquals(1, fired.get());
    }

    @Test
    @DisplayName("skill variables survive without an instance in one skill tree")
    void skillTreeVariablesWorkWithoutInstance() {
        var svc = service();
        var ctx = BehaviorContext.execution(null, null, 1.0, -1, 0,
                "skill", null, null, List.of(), null);
        String set = svc.bindAction("var-set score 2");
        String add = svc.bindAction("var-add score 3");
        assertNotNull(set);
        assertNotNull(add);
        assertTrue(registry.runAction(set, ctx));
        assertTrue(registry.runAction(add, ctx));
        assertEquals(5.0, ((Number) ctx.skillTree().orElseThrow()
                .variable("score").orElseThrow()).doubleValue());
    }

    @Test
    @DisplayName("Mythic setscore/addscore aliases update the instance score table")
    void mythicScoreMechanicAliasesUpdateScores() {
        var svc = service();
        var instance = mock(ModelInstance.class);
        when(instance.isValid()).thenReturn(true);
        when(instance.instanceId()).thenReturn(903);
        var context = BehaviorContext.of(instance, (LivingEntity) null,
                1.0, -1, 0, "IDLE");

        String set = svc.bindAction("setscore{objective=points;value=2}");
        String add = svc.bindAction("addscore points 3");
        String has = svc.bindCondition("has-score points");
        String gte = svc.bindCondition("score-gte points 5");

        assertNotNull(set, svc.warnings().toString());
        assertNotNull(add, svc.warnings().toString());
        assertTrue(registry.runAction(set, context));
        assertTrue(registry.runAction(add, context));
        assertTrue(registry.testCondition(has, context));
        assertTrue(registry.testCondition(gte, context));
        SkillExtras.forget(903);
        assertFalse(registry.testCondition(has, context));
    }

    @Test
    @DisplayName("Mythic GlobalCooldown is tick-based, instance-scoped, and cleaned on forget")
    void mythicGlobalCooldownIsInstanceScoped() {
        var svc = service();
        var first = mock(ModelInstance.class);
        var second = mock(ModelInstance.class);
        when(first.isValid()).thenReturn(true);
        when(second.isValid()).thenReturn(true);
        when(first.instanceId()).thenReturn(904);
        when(second.instanceId()).thenReturn(905);
        var firstContext = BehaviorContext.of(first, (LivingEntity) null,
                1.0, -1, 0, "IDLE");
        var secondContext = BehaviorContext.of(second, (LivingEntity) null,
                1.0, -1, 0, "IDLE");
        String gcd = svc.bindAction("gcd 40");
        String offgcd = svc.bindCondition("offgcd");

        assertTrue(registry.testCondition(offgcd, firstContext));
        assertTrue(registry.runAction(gcd, firstContext));
        assertFalse(registry.testCondition(offgcd, firstContext));
        assertTrue(registry.testCondition(offgcd, secondContext));

        SkillExtras.forget(904);
        assertTrue(registry.testCondition(offgcd, firstContext));
        SkillExtras.forget(905);
    }

    @Test
    @DisplayName("SetSkillCooldown changes a named skill and zero clears the override")
    void setSkillCooldownChangesNamedSkill() {
        var svc = service();
        var yaml = YamlConfiguration.loadConfiguration(new StringReader("""
                skills:
                  child:
                    on-decision: []
                """));
        svc.loadSkills(yaml.getConfigurationSection("skills"));
        var instance = mock(ModelInstance.class);
        when(instance.isValid()).thenReturn(true);
        when(instance.instanceId()).thenReturn(906);
        var context = BehaviorContext.of(instance, (LivingEntity) null,
                1.0, -1, 0, "IDLE");
        SkillExtras.skillCooldownSetter(svc::setSkillCooldown);
        try {
            String action = svc.bindAction("setskillcooldown child 2");
            assertNotNull(action, svc.warnings().toString());
            assertTrue(registry.runAction(action, context));
            assertTrue(svc.castSkill("child", context));
            assertFalse(svc.castSkill("child", context));
            assertTrue(svc.setSkillCooldown("child", context, 0));
            assertTrue(svc.castSkill("child", context));
        } finally {
            SkillExtras.skillCooldownSetter(null);
            svc.forget(906);
        }
    }

    @Test
    @DisplayName("unset removes skill-tree and instance-scoped values")
    void variableUnsetRemovesStoredValue() {
        var svc = service();
        var ctx = BehaviorContext.execution(null, null, 1.0, -1, 0,
                "skill", null, null, List.of(), null);
        String set = svc.bindAction("var-set marker 7");
        String unset = svc.bindAction("variable-unset marker");
        assertTrue(registry.runAction(set, ctx));
        assertTrue(ctx.skillTree().orElseThrow().variable("marker").isPresent());
        assertTrue(registry.runAction(unset, ctx));
        assertTrue(ctx.skillTree().orElseThrow().variable("marker").isEmpty());
    }

    @Test
    @DisplayName("variable-unset supports explicit skill and tree scopes")
    void variableUnsetScopesAreExplicit() {
        var svc = service();
        var ctx = BehaviorContext.execution(null, null, 1.0, -1, 0,
                "skill", null, null, List.of(), null);
        String set = svc.bindAction("var-set marker 7");
        assertTrue(registry.runAction(set, ctx));
        assertTrue(registry.runAction(svc.bindAction("variable-unset skill marker"), ctx));
        assertTrue(ctx.skillTree().orElseThrow().variable("marker").isEmpty());
    }

    @Test
    @DisplayName("remove-self removes only the caster entity")
    void removeSelfDoesNotRemoveTarget() {
        var svc = service();
        ModelInstance instance = mock(ModelInstance.class);
        Entity caster = mock(Entity.class);
        Entity target = mock(Entity.class);
        when(instance.isValid()).thenReturn(true);
        when(instance.baseEntity()).thenReturn(java.util.Optional.of(caster));
        when(caster.isValid()).thenReturn(true);

        var ctx = BehaviorContext.execution(instance, null, 1.0, -1, 0,
                "skill", null, target, List.of(), null);
        String action = svc.bindAction("remove-self");

        assertNotNull(action);
        assertTrue(registry.runAction(action, ctx));
        verify(caster).remove();
        verifyNoInteractions(target);
    }

    @Test
    @DisplayName("equip applies the requested material, slot, and amount")
    void equipAppliesMaterialSlotAndAmount() {
        var svc = service();
        registry.logger(java.util.logging.Logger.getLogger("SkillServiceTest"));
        var item = mock(org.bukkit.inventory.ItemStack.class);
        dev.helstera.ai.skill.SkillExtras.itemResolver(name -> item);
        ModelInstance instance = mock(ModelInstance.class);
        LivingEntity caster = mock(LivingEntity.class);
        EntityEquipment equipment = mock(EntityEquipment.class);
        when(instance.isValid()).thenReturn(true);
        when(instance.baseEntity()).thenReturn(java.util.Optional.of(caster));
        when(caster.isValid()).thenReturn(true);
        when(caster.getEquipment()).thenReturn(equipment);

        var ctx = BehaviorContext.execution(instance, null, 1.0, -1, 0,
                "skill", null, null, List.of(), null);
        String action = svc.bindAction("equip DIAMOND_SWORD off-hand 2");

        assertNotNull(action);
        try {
            assertTrue(registry.runAction(action, ctx));
            verify(item).setAmount(2);
            verify(equipment).setItem(EquipmentSlot.OFF_HAND, item);
        } finally {
            dev.helstera.ai.skill.SkillExtras.itemResolver(null);
        }
    }

    @Test
    @DisplayName("set-name applies translated name and visibility")
    void setNameAppliesDisplayName() {
        var svc = service();
        ModelInstance instance = mock(ModelInstance.class);
        LivingEntity caster = mock(LivingEntity.class);
        when(instance.isValid()).thenReturn(true);
        when(instance.baseEntity()).thenReturn(java.util.Optional.of(caster));
        when(caster.isValid()).thenReturn(true);

        var ctx = BehaviorContext.execution(instance, null, 1.0, -1, 0,
                "skill", null, null, List.of(), null);
        String action = svc.bindAction("set-name &cBoss false");

        assertNotNull(action);
        assertTrue(registry.runAction(action, ctx));
        verify(caster).setCustomName("\u00a7cBoss");
        verify(caster).setCustomNameVisible(false);
    }

    @Test
    @DisplayName("set-item replaces the requested equipment slot")
    void setItemReplacesEquipmentSlot() {
        var svc = service();
        var item = mock(org.bukkit.inventory.ItemStack.class);
        dev.helstera.ai.skill.SkillExtras.itemResolver(name -> item);
        ModelInstance instance = mock(ModelInstance.class);
        LivingEntity caster = mock(LivingEntity.class);
        EntityEquipment equipment = mock(EntityEquipment.class);
        when(instance.isValid()).thenReturn(true);
        when(instance.baseEntity()).thenReturn(java.util.Optional.of(caster));
        when(caster.isValid()).thenReturn(true);
        when(caster.getEquipment()).thenReturn(equipment);

        var ctx = BehaviorContext.execution(instance, null, 1.0, -1, 0,
                "skill", null, null, List.of(), null);
        String action = svc.bindAction("setitem{item=SHIELD;slot=main-hand;amount=1}");

        try {
            assertNotNull(action, svc.warnings().toString());
            assertTrue(registry.runAction(action, ctx));
            verify(item).setAmount(1);
            verify(equipment).setItem(EquipmentSlot.HAND, item);
        } finally {
            dev.helstera.ai.skill.SkillExtras.itemResolver(null);
        }
    }

    @Test
    @DisplayName("set-offhand writes the resolved item to the caster off hand")
    void setOffhandWritesOffhand() {
        var svc = service();
        var item = mock(org.bukkit.inventory.ItemStack.class);
        SkillExtras.itemResolver(name -> item);
        ModelInstance instance = mock(ModelInstance.class);
        LivingEntity caster = mock(LivingEntity.class);
        EntityEquipment equipment = mock(EntityEquipment.class);
        when(instance.isValid()).thenReturn(true);
        when(instance.baseEntity()).thenReturn(Optional.of(caster));
        when(caster.getEquipment()).thenReturn(equipment);
        var context = BehaviorContext.execution(instance, null, 1.0, -1, 0,
                "skill", null, null, List.of(), null);

        try {
            String action = svc.bindAction("setoffhand{item=SHIELD;amount=2}");
            assertNotNull(action, svc.warnings().toString());
            assertTrue(registry.runAction(action, context));
            verify(item).setAmount(2);
            verify(equipment).setItem(EquipmentSlot.OFF_HAND, item);
        } finally {
            SkillExtras.itemResolver(null);
        }
    }

    @Test
    @DisplayName("set-armor writes resolved items to all four armor slots")
    void setArmorWritesArmorSlots() {
        var svc = service();
        var item = mock(org.bukkit.inventory.ItemStack.class);
        SkillExtras.itemResolver(name -> item);
        ModelInstance instance = mock(ModelInstance.class);
        LivingEntity caster = mock(LivingEntity.class);
        EntityEquipment equipment = mock(EntityEquipment.class);
        when(instance.isValid()).thenReturn(true);
        when(instance.baseEntity()).thenReturn(Optional.of(caster));
        when(caster.getEquipment()).thenReturn(equipment);
        var context = BehaviorContext.execution(instance, null, 1.0, -1, 0,
                "skill", null, null, List.of(), null);

        try {
            String action = svc.bindAction(
                    "setarmor{helmet=IRON_HELMET;chestplate=IRON_CHESTPLATE;leggings=IRON_LEGGINGS;boots=IRON_BOOTS}");
            assertNotNull(action, svc.warnings().toString());
            assertTrue(registry.runAction(action, context));
            verify(equipment).setItem(EquipmentSlot.HEAD, item);
            verify(equipment).setItem(EquipmentSlot.CHEST, item);
            verify(equipment).setItem(EquipmentSlot.LEGS, item);
            verify(equipment).setItem(EquipmentSlot.FEET, item);
        } finally {
            SkillExtras.itemResolver(null);
        }
    }

    @Test
    @DisplayName("set-faction delegates the caster entity to the faction runtime bridge")
    void setFactionUsesRuntimeBridge() {
        var svc = service();
        ModelInstance instance = mock(ModelInstance.class);
        LivingEntity caster = mock(LivingEntity.class);
        when(instance.isValid()).thenReturn(true);
        when(instance.baseEntity()).thenReturn(Optional.of(caster));
        var seen = new java.util.ArrayList<String>();
        SkillExtras.factionSetter((entity, faction) -> seen.add(entity == caster ? faction : "wrong"));
        var context = BehaviorContext.execution(instance, null, 1.0, -1, 0,
                "skill", null, null, List.of(), null);
        try {
            String action = svc.bindAction("setfaction{faction=guards}");
            assertNotNull(action, svc.warnings().toString());
            assertTrue(registry.runAction(action, context));
            assertEquals(List.of("guards"), seen);
        } finally {
            SkillExtras.factionSetter(null);
        }
    }

    @Test
    @DisplayName("set-level updates the managed instance level used by Level condition")
    void setLevelUpdatesManagedInstance() {
        var svc = service();
        var instance = mock(dev.helstera.runtime.instance.ModelInstanceImpl.class);
        when(instance.isValid()).thenReturn(true);
        instance.level = 1;
        var context = BehaviorContext.of(instance, (LivingEntity) null, 1.0, -1, 0, "IDLE");

        String action = svc.bindAction("setlevel 5");
        assertNotNull(action, svc.warnings().toString());
        assertTrue(registry.runAction(action, context));
        assertEquals(5, instance.level);
        assertTrue(registry.testCondition(svc.bindCondition("level >=5"), context));
    }

    @Test
    @DisplayName("drop-item uses the caster location and item resolver")
    void dropItemDropsResolvedStack() {
        var svc = service();
        var item = mock(org.bukkit.inventory.ItemStack.class);
        var world = mock(World.class);
        var location = mock(Location.class);
        dev.helstera.ai.skill.SkillExtras.itemResolver(name -> item);
        ModelInstance instance = mock(ModelInstance.class);
        when(instance.isValid()).thenReturn(true);
        when(instance.location()).thenReturn(location);
        when(location.getWorld()).thenReturn(world);

        var ctx = BehaviorContext.of(instance, null, 1.0, -1, 0, "IDLE");
        String action = svc.bindAction("drop-item DIAMOND 3");

        try {
            assertNotNull(action);
            assertTrue(registry.runAction(action, ctx));
            verify(item).setAmount(3);
            verify(world).dropItemNaturally(location, item);
        } finally {
            dev.helstera.ai.skill.SkillExtras.itemResolver(null);
        }
    }

    @Test
    void dropItemUsesLocationTargetAndSkipsMissingVariable() {
        var svc = service();
        var template = mock(org.bukkit.inventory.ItemStack.class);
        var dropped = mock(org.bukkit.inventory.ItemStack.class);
        when(template.clone()).thenReturn(dropped);
        var world = mock(World.class);
        var origin = new Location(world, 0, 64, 0);
        var destination = new Location(world, 4, 5, 6);
        dev.helstera.ai.skill.SkillExtras.itemResolver(name -> template);
        var ctx = BehaviorContext.execution(null, null, 1, -1, 0, "skill", null, null,
                List.of(), origin);
        try {
            String missing = svc.bindAction("drop-item DIAMOND 3 @VariableLocation{var=skill.missing}");
            assertNotNull(missing);
            assertTrue(registry.runAction(missing, ctx));
            verify(world, org.mockito.Mockito.never()).dropItemNaturally(
                    org.mockito.ArgumentMatchers.any(Location.class), org.mockito.ArgumentMatchers.any());
            String action = svc.bindAction("drop-item DIAMOND 3 @location{x=4;y=5;z=6}");
            assertNotNull(action);
            assertTrue(registry.runAction(action, ctx));
            verify(dropped).setAmount(3);
            verify(world).dropItemNaturally(destination, dropped);
        } finally {
            dev.helstera.ai.skill.SkillExtras.itemResolver(null);
        }
    }

    @Test
    void dropItemRejectsInvalidNaturallyFlag() {
        var svc = service();
        assertNull(svc.bindAction("drop-item DIAMOND 1 maybe @location{x=4;y=5;z=6}"));
    }

    @Test
    void exactDropUsesCasterAndEntityTargetLocations() {
        var template = mock(org.bukkit.inventory.ItemStack.class);
        var dropped = mock(org.bukkit.inventory.ItemStack.class);
        when(template.clone()).thenReturn(dropped);
        var world = mock(World.class);
        var casterLocation = new Location(world, 1, 64, 1);
        var targetLocation = new Location(world, 4, 64, 4);
        var instance = mock(ModelInstance.class);
        when(instance.isValid()).thenReturn(true);
        when(instance.location()).thenReturn(casterLocation);
        var target = mock(org.bukkit.entity.LivingEntity.class);
        when(target.getLocation()).thenReturn(targetLocation);
        var context = BehaviorContext.continueExecution(instance, target, 1, 1, 0, "skill", null, null,
                List.of(target), casterLocation, null);
        dev.helstera.ai.skill.SkillExtras.itemResolver(name -> template);
        try {
            var actions = dev.helstera.ai.skill.SkillExtras.actions();
            actions.get("drop-item").create(List.of("DIAMOND", "2", "false")).accept(context);
            actions.get("drop-item-targets").create(List.of("DIAMOND", "2", "false")).accept(context);
            verify(world).dropItem(casterLocation, template);
            verify(world).dropItem(targetLocation, dropped);
            verify(world, org.mockito.Mockito.never()).dropItemNaturally(
                    org.mockito.ArgumentMatchers.any(Location.class), org.mockito.ArgumentMatchers.any());
        } finally {
            dev.helstera.ai.skill.SkillExtras.itemResolver(null);
        }
    }

    @Test
    void dropItemRejectsInvalidCountsInsteadOfDroppingOne() {
        var svc = service();
        var template = mock(org.bukkit.inventory.ItemStack.class);
        var world = mock(World.class);
        var origin = new Location(world, 0, 64, 0);
        var ctx = BehaviorContext.execution(null, null, 1, -1, 0, "skill", null, null,
                List.of(), origin);
        dev.helstera.ai.skill.SkillExtras.itemResolver(name -> template);
        try {
            for (String amount : List.of("0", "-1", "1.5", "2147483648", "<skill.var.missing>")) {
                String action = svc.bindAction("drop-item DIAMOND " + amount + " @location{x=4;y=5;z=6}");
                assertNotNull(action, amount);
                assertTrue(registry.runAction(action, ctx));
            }
            verify(world, org.mockito.Mockito.never()).dropItemNaturally(
                    org.mockito.ArgumentMatchers.any(Location.class), org.mockito.ArgumentMatchers.any());
        } finally {
            dev.helstera.ai.skill.SkillExtras.itemResolver(null);
        }
    }

    @Test
    @DisplayName("mount makes the active target a passenger of the caster")
    void mountAddsTargetAsPassenger() {
        var svc = service();
        ModelInstance instance = mock(ModelInstance.class);
        Entity caster = mock(Entity.class);
        LivingEntity target = mock(LivingEntity.class);
        when(instance.isValid()).thenReturn(true);
        when(instance.baseEntity()).thenReturn(java.util.Optional.of(caster));

        var ctx = BehaviorContext.of(instance, target, 1.0, -1, 0, "IDLE");
        String action = svc.bindAction("mount");

        assertNotNull(action);
        assertTrue(registry.runAction(action, ctx));
        verify(caster).addPassenger(target);
    }

    @Test
    @DisplayName("dismount leaves the caster vehicle when present")
    void dismountLeavesVehicle() {
        var svc = service();
        ModelInstance instance = mock(ModelInstance.class);
        Entity caster = mock(Entity.class);
        when(instance.isValid()).thenReturn(true);
        when(instance.baseEntity()).thenReturn(java.util.Optional.of(caster));
        when(caster.isInsideVehicle()).thenReturn(true);

        String action = svc.bindAction("dismount");
        assertNotNull(action);
        assertTrue(registry.runAction(action, BehaviorContext.of(instance, (org.bukkit.entity.Player) null,
                1.0, -1, 0, "IDLE")));
        verify(caster).leaveVehicle();
    }

    @Test
    @DisplayName("cancel-event cancels only when a cancellable event is present")
    void cancelEventUsesContextEvent() {
        var svc = service();
        var cancellable = mock(org.bukkit.event.Cancellable.class);
        var ctx = BehaviorContext.continueExecution(null, (org.bukkit.entity.Player) null,
                1.0, -1, 0, "damage", null, null, List.of(), null,
                new dev.helstera.api.behavior.SkillTreeContext(null, List.of(), null), cancellable);
        String action = svc.bindAction("cancel-event");

        assertNotNull(action);
        assertTrue(registry.runAction(action, ctx));
        verify(cancellable).setCancelled(true);
        assertTrue(registry.runAction(action, ctx(1.0, 0)));
    }

    @Test
    @DisplayName("targeter invocation retains cancellable event context")
    void targetedActionRetainsCancellableEvent() {
        var svc = service();
        var target = mock(LivingEntity.class);
        var event = mock(org.bukkit.event.Cancellable.class);
        var context = BehaviorContext.continueExecution(null, target, 1.0, 2.0, 0,
                "damage", null, target, List.of(target), null,
                new dev.helstera.api.behavior.SkillTreeContext(target, List.of(target), null),
                event, org.bukkit.event.entity.EntityDamageEvent.DamageCause.ENTITY_ATTACK);

        String action = svc.bindAction("cancel-event @Target");
        assertNotNull(action, svc.warnings().toString());
        assertTrue(registry.runAction(action, context));
        verify(event).setCancelled(true);
    }

    @Test
    @DisplayName("is-cancelled reads the same cancellable event context")
    void isCancelledReadsContextEvent() {
        var svc = service();
        var cancellable = mock(org.bukkit.event.Cancellable.class);
        when(cancellable.isCancelled()).thenReturn(false, true);
        var context = BehaviorContext.continueExecution(null, (org.bukkit.entity.Player) null,
                1.0, -1, 0, "damage", null, null, List.of(), null,
                new dev.helstera.api.behavior.SkillTreeContext(null, List.of(), null), cancellable);
        String condition = svc.bindCondition("isCancelled");

        assertNotNull(condition, svc.warnings().toString());
        assertFalse(registry.testCondition(condition, context));
        assertTrue(registry.testCondition(condition, context));
        verify(cancellable, org.mockito.Mockito.times(2)).isCancelled();
    }

    @Test
    void parsesPreboundCastSkillAction() {
        assertEquals("cancelled_damage_probe", SkillService.parseSkillRef(
                "cast-skill:cancelled_damage_probe"));
    }

    @Test
    void nestedSkillRetainsCancelledEventContext() {
        var svc = service();
        var yaml = YamlConfiguration.loadConfiguration(new StringReader("""
                skills:
                  cancelled:
                    require:
                      - is-cancelled
                    on-decision:
                      - set-name &aCANCELLED
                """));
        svc.loadSkills(yaml.getConfigurationSection("skills"));
        var event = mock(org.bukkit.event.Cancellable.class);
        when(event.isCancelled()).thenReturn(true);
        var instance = mock(ModelInstance.class);
        var caster = mock(LivingEntity.class);
        when(instance.isValid()).thenReturn(true);
        when(instance.baseEntity()).thenReturn(Optional.of(caster));
        var context = BehaviorContext.continueExecution(instance, caster, 1.0, -1, 0,
                "damage", null, caster, List.of(caster), null,
                new dev.helstera.api.behavior.SkillTreeContext(caster, List.of(caster), null), event);

        assertTrue(svc.castSkill("cancelled", context));
        verify(caster).setCustomName("\u00a7aCANCELLED");
    }

    @Test
    @DisplayName("AiProfile loads preserved goal and target selector lists")
    void aiProfileLoadsSelectorFields() {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(new StringReader("""
                goals:
                  - 0 clear
                  - 1 meleeattack
                targets:
                  - 0 clear
                  - 1 players
                """));
        AiProfile profile = AiProfile.fromSection("guard", yaml);
        assertEquals(List.of("0 clear", "1 meleeattack"), profile.goals);
        assertEquals(List.of("0 clear", "1 players"), profile.targets);
    }

    @Test
    @DisplayName("selector modifiers do not leak into the shared profile")
    void profileCopyDoesNotMutateBaseSelectorBehavior() {
        AiProfile base = new AiProfile("base");
        base.canChase = true;
        base.goals.addAll(List.of("0 clear", "1 meleeattack"));
        base.targets.addAll(List.of("0 clear", "1 players"));
        AiProfile copy = new AiProfile(base);
        copy.canChase = false;
        copy.goals.clear();
        assertTrue(base.canChase);
        assertEquals(List.of("0 clear", "1 meleeattack"), base.goals);
    }

    @Test
    @DisplayName("selector policy applies ordered clear and enables native goals")
    void selectorPolicyAppliesPriorityOrder() {
        AiProfile profile = new AiProfile("selector");
        profile.goals.addAll(List.of("0 clear", "1 meleeattack", "2 randomstroll"));
        profile.targets.addAll(List.of("0 clear", "1 players"));
        var policy = profile.selectorPolicy();
        assertTrue(policy.attack());
        assertTrue(policy.patrol());
        assertFalse(policy.chase());
        assertFalse(policy.flee());
        assertTrue(policy.playerTargets());
    }

    @Test
    @DisplayName("selector policy sorts numeric priorities before applying clear")
    void selectorPolicySortsNumericPriorities() {
        AiProfile profile = new AiProfile("selector");
        profile.goals.addAll(List.of("2 clear", "0 meleeattack"));
        profile.targets.addAll(List.of("1 clear", "0 players"));

        var policy = profile.selectorPolicy();

        assertFalse(policy.attack());
        assertFalse(policy.chase());
        assertFalse(policy.playerTargets());
        assertEquals(AiProfile.TargetMode.NONE, policy.targetMode());
        assertEquals(List.of("0 meleeattack", "2 clear"), policy.orderedGoals(profile.goals));
        assertEquals(List.of("0 players", "1 clear"), policy.orderedTargets(profile.targets));
        assertEquals(0, policy.goalPriority(profile.goals, "attack"));
        assertEquals(Integer.MAX_VALUE, policy.goalPriority(profile.goals, "flee"));
        assertEquals(Integer.MAX_VALUE, policy.goalPriority(profile.goals, "patrol"));
    }

    @Test
    @DisplayName("selector policy reports unsupported entries without dropping their token")
    void selectorPolicyReportsUnsupportedTokens() {
        AiProfile profile = new AiProfile("selector");
        profile.goals.add("2 customvendorpathfinder");
        var policy = profile.selectorPolicy();
        assertEquals(List.of("goal:customvendorpathfinder"), policy.unsupported());
    }

    @Test
    @DisplayName("panic goal enables the existing flee runtime policy")
    void selectorPolicyRecognizesPanicGoal() {
        AiProfile profile = new AiProfile("panic");
        profile.goals.add("0 panic");

        var policy = profile.selectorPolicy();

        assertTrue(policy.flee());
        assertTrue(policy.unsupported().isEmpty());
    }

    @Test
    @DisplayName("follow-owner goal selects the existing owner target runtime")
    void selectorPolicyRecognizesFollowOwnerGoal() {
        AiProfile profile = new AiProfile("follow-owner");
        profile.goals.add("0 follow_owner");

        var policy = profile.selectorPolicy();

        assertEquals(AiProfile.TargetMode.OWNER, policy.targetMode());
        assertFalse(policy.playerTargets());
        assertTrue(policy.unsupported().isEmpty());
    }

    @Test
    @DisplayName("tempt goal preserves the configured item and range")
    void selectorPolicyRecognizesTemptGoal() {
        AiProfile profile = new AiProfile("tempt");
        profile.goals.add("0 tempt WHEAT");
        profile.temptRange = 12.0;

        var policy = profile.selectorPolicy();

        assertEquals(AiProfile.TargetMode.PLAYERS, policy.targetMode());
        assertEquals("WHEAT", policy.temptItem());
        assertEquals(12.0, policy.temptRange(), 1e-9);
        assertTrue(policy.unsupported().isEmpty());
    }

    @Test
    @DisplayName("tempt matching accepts either hand and multiple configured materials")
    void temptMatchingChecksBothHands() {
        var player = mock(org.bukkit.entity.Player.class);
        var inventory = mock(org.bukkit.inventory.PlayerInventory.class);
        when(player.getInventory()).thenReturn(inventory);
        var mainHand = mock(org.bukkit.inventory.ItemStack.class);
        var offHand = mock(org.bukkit.inventory.ItemStack.class);
        when(mainHand.getType()).thenReturn(org.bukkit.Material.AIR);
        when(offHand.getType()).thenReturn(org.bukkit.Material.WHEAT);
        when(inventory.getItemInMainHand()).thenReturn(mainHand);
        when(inventory.getItemInOffHand()).thenReturn(offHand);

        assertTrue(AiController.holdsTemptItem(player, "CARROT|WHEAT"));
        assertFalse(AiController.holdsTemptItem(player, "CARROT,POTATO"));
    }


    @Test
    @DisplayName("location variables honor global scope and VariableLocation resolution")
    void globalLocationVariableRoundTrip() {
        var svc = service();
        var origin = new org.bukkit.Location(null, 12, 64, -4);
        var ctx = BehaviorContext.execution(null, null, 1.0, -1, 0,
                "skill", null, null, List.of(), origin);

        String set = svc.bindAction("variable-location-set{scope=global;var=mark}");
        String unset = svc.bindAction("variable-unset{var=global.mark}");
        assertNotNull(set);
        assertNotNull(unset);
        assertTrue(registry.runAction(set, ctx));
        var resolved = dev.helstera.api.behavior.Targeters.resolveLocation(
                "@VariableLocation{var=global.mark}", ctx);
        assertTrue(resolved.isPresent());
        assertEquals(12, resolved.orElseThrow().getX());
        assertEquals(64, resolved.orElseThrow().getY());
        assertTrue(registry.runAction(unset, ctx));
        assertTrue(dev.helstera.api.behavior.Targeters.resolveLocation(
                "@VariableLocation{var=global.mark}", ctx).isEmpty());
    }


    @Test
    @DisplayName("typed LOCATION variables resolve through VariableLocation and unset")
    void typedLocationVariableRoundTrip() {
        var svc = service();
        var ctx = BehaviorContext.execution(null, null, 1.0, -1, 0,
                "skill", null, null, List.of(), new org.bukkit.Location(null, 1, 2, 3));
        String set = svc.bindAction("setvariable{var=skill.mark;type=LOCATION;value=20,70,-5}");
        String check = svc.bindCondition("variable-is-set{var=skill.mark}");
        String unset = svc.bindAction("variable-unset{var=skill.mark}");

        assertNotNull(set);
        assertNotNull(check);
        assertNotNull(unset);
        assertTrue(registry.runAction(set, ctx));
        assertTrue(registry.testCondition(check, ctx));
        var resolved = dev.helstera.api.behavior.Targeters.resolveLocation(
                "@VariableLocation{var=skill.mark}", ctx).orElseThrow();
        assertEquals(20, resolved.getX());
        assertEquals(70, resolved.getY());
        assertEquals(-5, resolved.getZ());
        assertTrue(registry.runAction(unset, ctx));
        assertFalse(registry.testCondition(check, ctx));
        assertTrue(dev.helstera.api.behavior.Targeters.resolveLocation(
                "@VariableLocation{var=skill.mark}", ctx).isEmpty());
    }
    @Test
    @DisplayName("numeric variables honor global scope across actions and conditions")
    void numericGlobalVariableRoundTrip() {
        var svc = service();
        var ctx = BehaviorContext.execution(null, null, 1.0, -1, 0,
                "skill", null, null, List.of(), null);
        String set = svc.bindAction("setvariable{var=global.counter;value=2}");
        String check = svc.bindCondition("variable-gte{var=global.counter;value=2}");
        String unset = svc.bindAction("variable-unset{var=global.counter}");
        assertNotNull(set);
        assertNotNull(check);
        assertNotNull(unset);
        assertTrue(registry.runAction(set, ctx));
        assertTrue(registry.testCondition(check, ctx));
        assertTrue(registry.runAction(unset, ctx));
        assertFalse(registry.testCondition(check, ctx));
    }
    @Test
    @DisplayName("variable conditions support equality, range, and is-set aliases")
    void variableConditionAliasesWork() {
        var svc = service();
        var ctx = BehaviorContext.execution(null, null, 1.0, -1, 0,
                "skill", null, null, List.of(), null);
        assertTrue(registry.runAction(svc.bindAction("setvariable{var=global.answer;value=42}"), ctx));
        assertTrue(registry.testCondition(svc.bindCondition("variable-is-set{var=global.answer}"), ctx));
        assertTrue(registry.testCondition(svc.bindCondition("variableequals{var=global.answer;value=42}"), ctx));
        assertTrue(registry.testCondition(svc.bindCondition("variable-in-range{var=global.answer;min=40;max=45}"), ctx));
        assertTrue(registry.testCondition(svc.bindCondition(
                "?variableinrange{var=global.answer;min=40;max=45}"), ctx));
        assertTrue(registry.testCondition(svc.bindCondition(
                "variable-in-range{var=global.answer;value=40to45}"), ctx));
        assertTrue(registry.testCondition(svc.bindCondition(
                "variable-in-range{var=global.answer;range=>=42}"), ctx));
        assertFalse(registry.testCondition(svc.bindCondition(
                "variable-in-range{var=global.answer;value=>42}"), ctx));
        assertNull(svc.bindCondition("variable-in-range{var=global.answer}"));
        assertNull(svc.bindCondition("variable-in-range{var=global.answer;value=45to40}"));
        assertNull(svc.bindCondition("variable-in-range{var=global.answer;value=NaN}"));
        assertTrue(registry.runAction(svc.bindAction(
                "setvariable{var=global.text;type=STRING;value=ready}"), ctx));
        assertFalse(registry.testCondition(svc.bindCondition(
                "variable-in-range{var=global.text;value=0to100}"), ctx));
        assertTrue(registry.runAction(svc.bindAction("variable-unset{var=global.text}"), ctx));
        assertFalse(registry.testCondition(svc.bindCondition("variableequals{var=global.answer;value=7}"), ctx));
        assertTrue(registry.runAction(svc.bindAction("variable-unset{var=global.answer}"), ctx));
    }
    @Test
    @DisplayName("typed variables support strings and lists")
    void typedVariablesRoundTrip() {
        var svc = service();
        var ctx = BehaviorContext.execution(null, null, 1.0, -1, 0,
                "skill", null, null, List.of(), null);
        assertTrue(registry.runAction(svc.bindAction(
                "setvariable{var=skill.label;type=STRING;val=hello-world}"), ctx));
        assertTrue(registry.testCondition(svc.bindCondition(
                "variable-equals{var=skill.label;value=hello-world}"), ctx));
        assertTrue(registry.runAction(svc.bindAction(
                "setvariable{var=skill.tags;type=LIST;val=red,blue}"), ctx));
        assertTrue(registry.testCondition(svc.bindCondition(
                "variable-contains{var=skill.tags;value=blue}"), ctx));
        assertTrue(registry.runAction(svc.bindAction(
                "setvariable{var=skill.map;type=MAP;value=color:blue,size:large}"), ctx));
        assertTrue(registry.testCondition(svc.bindCondition(
                "variable-index-equals{var=skill.tags;index=1;value=blue}"), ctx));
        assertFalse(registry.testCondition(svc.bindCondition(
                "variable-index-equals{var=skill.tags;index=9;value=blue}"), ctx));
        assertTrue(registry.testCondition(svc.bindCondition(
                "variable-key-exists{var=skill.map;key=color}"), ctx));
        assertFalse(registry.testCondition(svc.bindCondition(
                "variable-key-exists{var=skill.map;key=missing}"), ctx));
        assertTrue(registry.testCondition(svc.bindCondition(
                "variable-map-value-equals{var=skill.map;key=color;value=blue}"), ctx));
        assertFalse(registry.testCondition(svc.bindCondition(
                "variable-map-value-equals{var=skill.map;key=color;value=red}"), ctx));
        assertFalse(registry.testCondition(svc.bindCondition(
                "variable-index-gte{var=skill.tags;index=1;value=1}"), ctx));
        assertTrue(registry.runAction(svc.bindAction(
                "setvariable{var=skill.numbers;type=LIST;value=2,7}"), ctx));
        assertTrue(registry.testCondition(svc.bindCondition(
                "variable-index-gte{var=skill.numbers;index=1;value=7}"), ctx));
        assertTrue(registry.testCondition(svc.bindCondition(
                "variable-index-lte{var=skill.numbers;index=0;value=2}"), ctx));
        assertTrue(registry.testCondition(svc.bindCondition(
                "variable-index-in-range{var=skill.numbers;index=1;min=6;max=8}"), ctx));
        assertFalse(registry.testCondition(svc.bindCondition(
                "variable-index-in-range{var=skill.numbers;index=9;min=0;max=10}"), ctx));
        assertFalse(registry.testCondition(svc.bindCondition(
                "variable-index-lte{var=skill.numbers;index=9;value=2}"), ctx));
        assertTrue(registry.testCondition(svc.bindCondition(
                "variable-key-not-exists{var=skill.map;key=missing}"), ctx));
        assertFalse(registry.testCondition(svc.bindCondition(
                "variable-key-not-exists{var=skill.map;key=color}"), ctx));
        assertTrue(registry.testCondition(svc.bindCondition(
                "variable-map-value-not-equals{var=skill.map;key=color;value=red}"), ctx));
        assertFalse(registry.testCondition(svc.bindCondition(
                "variable-map-value-not-equals{var=skill.map;key=color;value=blue}"), ctx));
        assertTrue(registry.runAction(svc.bindAction(
                "setvariable{var=skill.numericMap;type=MAP;value=low:2,high:7}"), ctx));
        assertTrue(registry.testCondition(svc.bindCondition(
                "variable-map-value-gte{var=skill.numericMap;key=high;value=7}"), ctx));
        assertTrue(registry.testCondition(svc.bindCondition(
                "variable-map-value-lte{var=skill.numericMap;key=low;value=2}"), ctx));
        assertTrue(registry.testCondition(svc.bindCondition(
                "variable-map-value-in-range{var=skill.numericMap;key=high;min=7;max=8}"), ctx));
        assertFalse(registry.testCondition(svc.bindCondition(
                "variable-map-value-in-range{var=skill.map;key=color;min=0;max=10}"), ctx));
        assertTrue(registry.testCondition(svc.bindCondition(
                "variable-map-value-not-in-range{var=skill.numericMap;key=high;min=0;max=6}"), ctx));
        assertFalse(registry.testCondition(svc.bindCondition(
                "variable-map-value-not-in-range{var=skill.numericMap;key=high;min=0;max=7}"), ctx));
        assertFalse(registry.testCondition(svc.bindCondition(
                "variable-map-value-gte{var=skill.map;key=color;value=1}"), ctx));
        assertTrue(registry.testCondition(svc.bindCondition(
                "variable-not-equals{var=skill.label;value=goodbye}"), ctx));
        assertTrue(registry.testCondition(svc.bindCondition(
                "variable-not-contains{var=skill.tags;value=green}"), ctx));
        assertFalse(registry.testCondition(svc.bindCondition(
                "variable-not-contains{var=skill.tags;value=blue}"), ctx));
        assertTrue(registry.runAction(svc.bindAction("variable-unset{var=skill.label}"), ctx));
        assertFalse(registry.testCondition(svc.bindCondition(
                "variable-not-equals{var=skill.label;value=goodbye}"), ctx));
    }

    @Test
    @DisplayName("negative string variable conditions honor each persistent scope")
    void negativeVariableConditionsHonorPersistentScopes() {
        var svc = service();
        String suffix = java.util.UUID.randomUUID().toString().replace("-", "");
        var world = mock(World.class);
        when(world.getUID()).thenReturn(java.util.UUID.randomUUID());
        when(world.getName()).thenReturn("variable-scope-test");
        var caster = mock(org.bukkit.entity.Player.class);
        when(caster.getUniqueId()).thenReturn(java.util.UUID.randomUUID());
        var target = mock(org.bukkit.entity.Player.class);
        when(target.getUniqueId()).thenReturn(java.util.UUID.randomUUID());
        ModelInstance instance = mock(ModelInstance.class);
        when(instance.isValid()).thenReturn(true);
        when(instance.baseEntity()).thenReturn(java.util.Optional.of(caster));
        var origin = new org.bukkit.Location(world, 0, 64, 0);
        var ctx = BehaviorContext.execution(instance, target, 1.0, 2, 0,
                "skill", null, caster, List.of(target), origin);

        for (String scope : List.of("caster", "target", "world", "global")) {
            String variable = scope + suffix;
            String reference = scope + "." + variable;
            assertTrue(registry.runAction(svc.bindAction(
                    "setvariable{var=" + reference + ";type=STRING;value=blue}"), ctx));
            assertTrue(registry.testCondition(svc.bindCondition(
                    "variable-not-equals{var=" + reference + ";value=red}"), ctx), scope);
            assertTrue(registry.testCondition(svc.bindCondition(
                    "variable-not-contains{var=" + reference + ";value=green}"), ctx), scope);
            assertFalse(registry.testCondition(svc.bindCondition(
                    "variable-not-contains{var=" + reference + ";value=blue}"), ctx), scope);
        }

        String casterRef = "caster." + "caster" + suffix;
        String targetRef = "target." + "target" + suffix;
        assertTrue(registry.runAction(svc.bindAction(
                "variable-unset{var=" + casterRef + "}"), ctx));
        assertFalse(registry.testCondition(svc.bindCondition(
                "variable-not-equals{var=" + casterRef + ";value=anything}"), ctx));
        assertFalse(registry.testCondition(svc.bindCondition(
                "variable-not-contains{var=" + casterRef + ";value=anything}"), ctx));
        assertTrue(registry.testCondition(svc.bindCondition(
                "variable-not-equals{var=" + targetRef + ";value=red}"), ctx));
    }

    @Test
    @DisplayName("AI profile loads MaxCombatDistance from mob options")
    void loadsMaxCombatDistance() {
        var yaml = YamlConfiguration.loadConfiguration(new StringReader("max-combat-distance: 18"));
        AiProfile profile = AiProfile.fromSection("guard", yaml);
        assertEquals(18, profile.maxCombatDistance, 1e-9);
    }    @Test
    @DisplayName("despawn modes distinguish chunk unload behavior")
    void despawnModeChunkPolicy() {
        assertTrue(AiManager.despawnOnChunkUnload("NORMAL"));
        assertTrue(AiManager.despawnOnChunkUnload("CHUNK"));
        assertTrue(AiManager.despawnOnChunkUnload("NPC"));
        assertFalse(AiManager.despawnOnChunkUnload("PERSISTENT"));
        assertFalse(AiManager.despawnOnChunkUnload("NEVER"));
    }    @Test
    @DisplayName("selector policy exposes look-at and entity avoidance adapters")
    void selectorPolicyExposesAdvancedGoalFlags() {
        AiProfile profile = new AiProfile("selector");
        profile.goals.addAll(List.of("0 look_at_players", "1 avoid_entity", "2 open_doors", "3 avoid_sun",
                "4 rangedattack", "5 leapattarget"));
        var policy = profile.selectorPolicy();
        assertTrue(policy.lookAtPlayers());
        assertTrue(policy.avoidEntities());
        assertTrue(policy.openDoors());
        assertTrue(policy.avoidSun());
        assertTrue(policy.rangedAttack());
        assertTrue(policy.leapAtTarget());
    }    @Test
    @DisplayName("selector policy marks attacker and hurt-by-target priorities")
    void selectorPolicyMarksTargetPriorities() {
        AiProfile profile = new AiProfile("selector");
        profile.targets.addAll(List.of("0 attacker", "1 hurtbytarget"));
        var policy = profile.selectorPolicy();
        assertTrue(policy.attackerFirst());
        assertTrue(policy.hurtByTargetFirst());
    }

    @Test
    @DisplayName("selector policy recognizes threat and faction target priority")
    void selectorPolicyRecognizesThreatAndFactionTarget() {
        AiProfile threat = new AiProfile("threat");
        threat.targets.add("0 threat");
        assertEquals(AiProfile.TargetMode.THREAT, threat.selectorPolicy().targetMode());

        AiProfile faction = new AiProfile("faction");
        faction.targets.add("0 hostile_to_faction:undead");
        var policy = faction.selectorPolicy();
        assertEquals(AiProfile.TargetMode.FACTION, policy.targetMode());
        assertEquals("undead", policy.targetFaction());
        assertTrue(policy.factionHostile());
    }

    @Test
    @DisplayName("selector policy recognizes allied faction target priority")
    void selectorPolicyRecognizesAlliedFactionTarget() {
        AiProfile profile = new AiProfile("selector");
        profile.targets.add("0 allied_to_faction:guards");
        var policy = profile.selectorPolicy();
        assertEquals(AiProfile.TargetMode.FACTION, policy.targetMode());
        assertEquals("guards", policy.targetFaction());
        assertTrue(policy.factionAllied());
        assertFalse(policy.factionHostile());
    }

    @Test
    @DisplayName("selector policy recognizes owner target priority")
    void selectorPolicyRecognizesOwnerTarget() {
        AiProfile profile = new AiProfile("selector");
        profile.targets.add("0 owner");
        var policy = profile.selectorPolicy();
        assertFalse(policy.playerTargets());
        assertEquals(AiProfile.TargetMode.OWNER, policy.targetMode());
    }

    @Test
    @DisplayName("selector policy exposes non-player living target categories")
    void selectorPolicyExposesLivingTargetCategories() {
        AiProfile profile = new AiProfile("selector");
        profile.targets.addAll(List.of("0 clear", "1 monsters"));
        var policy = profile.selectorPolicy();
        assertFalse(policy.playerTargets());
        assertEquals(AiProfile.TargetMode.MONSTERS, policy.targetMode());

        profile.targets.clear();
        profile.targets.add("living");
        assertEquals(AiProfile.TargetMode.LIVING, profile.selectorPolicy().targetMode());
    }

    @Test
    @DisplayName("caster faction conditions bind alongside target faction conditions")
    void factionConditionsBind() {
        var svc = service();
        assertNotNull(svc.bindCondition("caster-faction-is guards"));
        assertNotNull(svc.bindCondition("caster-faction-allied-to guards"));
        assertNotNull(svc.bindCondition("caster-faction-hostile-to undead"));
        assertNotNull(svc.bindCondition("target-faction-is undead"));
    }

    @Test
    @DisplayName("same-faction and different-faction compare exact faction ids")
    void exactFactionConditionsCompareCasterAndTarget() {
        var svc = service();
        var instance = mock(ModelInstance.class);
        var caster = mock(Player.class);
        var target = mock(LivingEntity.class);
        var table = mock(dev.helstera.api.behavior.FactionTable.class);
        when(instance.isValid()).thenReturn(true);
        when(instance.baseEntity()).thenReturn(Optional.of(caster));
        when(table.factionOf(caster)).thenReturn("guards");
        when(table.factionOf(target)).thenReturn("guards");
        dev.helstera.api.behavior.Factions.install(table);
        try {
            var context = BehaviorContext.of(instance, target, 1.0, -1, 0, "IDLE");
            assertTrue(registry.testCondition(svc.bindCondition("caster-faction-is guards"), context));
            assertTrue(registry.testCondition(svc.bindCondition("target-faction-is guards"), context));
            assertTrue(registry.testCondition(svc.bindCondition("same-faction"), context));
            assertFalse(registry.testCondition(svc.bindCondition("different-faction"), context));

            when(table.factionOf(target)).thenReturn("undead");
            assertFalse(registry.testCondition(svc.bindCondition("same-faction"), context));
            assertTrue(registry.testCondition(svc.bindCondition("different-faction"), context));

            var noTarget = BehaviorContext.of(instance, (LivingEntity) null, 1.0, -1, 0, "IDLE");
            assertFalse(registry.testCondition(svc.bindCondition("same-faction"), noTarget));
            assertFalse(registry.testCondition(svc.bindCondition("different-faction"), noTarget));
        } finally {
            dev.helstera.api.behavior.Factions.install(null);
        }
    }

    @Test
    @DisplayName("common Mythic condition aliases bind to canonical factories")
    void commonConditionAliasesBind() {
        var svc = service();
        assertNotNull(svc.bindCondition("permission vip.node"));
        assertNotNull(svc.bindCondition("entity-type ZOMBIE"));
        assertNotNull(svc.bindCondition("stance combat"));
        assertNotNull(svc.bindCondition("healthbelow 0.5"));
        assertNotNull(svc.bindCondition("distanceabove 4"));
        assertNotNull(svc.bindCondition("worldis integration-world"));
    }

    @Test
    @DisplayName("world condition catalog binds high-frequency environment and target aliases")
    void environmentConditionCatalogBinds() {
        var svc = service();
        for (String spec : List.of(
                "biome-is plains", "weather-is clear", "time-is day",
                "night", "raining", "thundering", "falling", "drowning <=0", "has-auras",
                "has-permission vip.node", "entity-type-is ZOMBIE", "stance-is combat",
                "health-above 0.5", "distance-below 8", "onground", "lineofsight",
                "playersinradius 1 8", "entitiesinradius 1 8", "randomchance 100",
                "mobsinradius 1 8", "incombat", "targetcount 1", "hungerbelow 10",
                "playersonline >=1", "playersinworld >=1", "mobsinworld >=1",
                "playerwithin 10", "playernotwithin 10",
                "motionx <1", "motiony >=-1", "motionz =0", "velocity <=3",
                "targethealthbelow 50", "gamemode survival", "potionactive speed", "owner", "isOwner",
                "blockTypeInRadius{type=STONE;amount=>0;radius=3}", "children{a=>1}", "inside", "outside",
                "fallSpeed{s=>0}", "enchantingLevel{l=>=1}", "enchantingExperience{a=>=0.1}",
                "plugin{p=helsteraMobs}", "pluginexists{p=__missing_plugin__}",
                "directionalVelocity{x=>0;y=<0;z=>0}", "fieldOfView{angle=90;rotation=0}",
                "spawnReason{reason=RAID}", "spawn-reason{r=NATURAL}", "isWaterMob", "isZombie",
                "aerial", "isAerial", "isEnderman", "hasEnchantment{e=SHARPNESS;l=>2}",
                "itemIsSimilar{i=DIRT;slot=0}", "similarTo{i=DIRT;s=HAND}",
                "itemRecharging",
                "targets{a=>0;targeter=@targets}",
                "cuboid{location1=0,64,0;location2=4,70,4}",
                "inBounds{shape=sphere;l=0,64,0;r=5}")) {
            assertNotNull(svc.bindCondition(spec), spec);
        }
        assertNotNull(svc.bindCondition("hasDialogOpen"));
        assertNotNull(svc.bindCondition("isInCinematic"));
        assertNotNull(svc.bindCondition("color{c=RED}"));
        assertNotNull(svc.bindCondition("materialIsOnCooldown{m=ENDER_PEARL}"));
        assertNotNull(svc.bindCondition("triggerItemType{mat=STONE,DIRT}"));
        assertNotNull(svc.bindCondition("playerKills{k=>=1}"));
        assertNotNull(svc.bindCondition("absorption{amount=>=1}"));
        assertNotNull(svc.bindCondition("hasPotionEffect{t=SPEED;l=0-2;d=1-200}"));
        assertNotNull(svc.bindCondition("has-aura-stacks SPEED >=2"));
        for (String mythicAuraAlias : List.of(
                "hasAura{aura=SPEED}",
                "hasBuff{buff=SPEED}",
                "hasDebuff{debuff=SPEED}",
                "hasAuraStacks{n=SPEED;s=>=2}",
                "hasAuraType{type=HARMFUL}",
                "hasBuffType{group=HELPFUL}",
                "hasDebuffType{auratype=HARMFUL}",
                "hasBuffStacks{n=SPEED;s=>=2}",
                "hasDebuffStacks{n=SPEED;s=>=2}",
                "auraStacks{n=SPEED;s=>=2}",
                "buffStacks{n=SPEED;s=>=2}",
                "debuffStacks{n=SPEED;s=>=2}")) {
            assertNotNull(svc.bindCondition(mythicAuraAlias), mythicAuraAlias);
        }
        assertNotNull(svc.bindCondition("triggerBlockType{mat=STONE}"));
        assertNotNull(svc.bindCondition("distanceFromLocation{x=0;y=0;z=0;distance=<10}"));
        for (String diff : List.of("xDiff{d=1}", "yDiff{diff=-1to1}", "zDiff{difference=>0}")) {
            assertNotNull(svc.bindCondition(diff), diff);
        }
        assertNotNull(svc.bindCondition("originLocation{loc=0,0,0}"));
        for (String alias : List.of("damageAmount >=1", "damage-amount >1", "amount >1", "a >1")) {
            assertNotNull(svc.bindCondition(alias), alias);
        }
        for (String alias : List.of("sneaking", "issneaking", "sprinting", "issprinting",
                "glowing", "isglowing", "swimming", "isswimming", "climbing", "isclimbing",
                "inair", "airborne", "gliding", "isgliding", "flying", "isflying",
                "blocking", "isblocking", "facingtarget", "targetinlineofsight",
                "target-line-of-sight", "invulnerable", "isinvulnerable", "isriding",
                "targetwithin <=8", "hasPassenger", "isVehicle", "targetHasPassenger",
                "targetIsVehicle", "targetNotWithin <=8", "targetInLineOfSight",
                "targetNotInLineOfSight", "hasFreeInventorySlot")) {
            assertNotNull(svc.bindCondition(alias), alias);
        }
        for (String alias : List.of("moist", "isMoist", "moistureLevel{l=3}",
                "moistureness{l=1to6}", "moistness{l=>=1}", "mobSize{s=>3}",
                "size{s=4}")) {
            assertNotNull(svc.bindCondition(alias), alias);
        }
        assertNotNull(svc.bindCondition("caster-has-permission vip.node"));
        assertNotNull(svc.bindCondition("casterpermission vip.node"));
        assertNotNull(svc.bindCondition("targettype player"));
        for (String targetType : List.of("mob", "monster", "animal", "villager", "tameable", "raider")) {
            assertNotNull(svc.bindCondition("target-is " + targetType));
        }
        for (String alias : List.of("target-invulnerable", "targetinvulnerable", "target-is-invulnerable")) {
            assertNotNull(svc.bindCondition(alias), alias);
        }
    }

    @Test
    @DisplayName("nested composite conditions preserve braces and support negation")
    void nestedCompositeConditionsBind() {
        var svc = service();
        registry.registerCondition("first", context -> true);
        registry.registerCondition("second", context -> false);
        registry.registerCondition("third", context -> true);
        registry.registerCondition("never", context -> false);

        String nested = svc.bindCondition(
                "all{conditions=first;any{second;third}}" );
        String negated = svc.bindCondition("not{any{never;never}}");

        assertNotNull(nested);
        assertNotNull(negated);
        assertTrue(registry.hasCondition(nested));
        assertTrue(registry.hasCondition(negated));
        assertTrue(registry.testCondition(nested, ctx(1.0, 0)));
        assertTrue(registry.testCondition(negated, ctx(1.0, 0)));
    }

    @Test
    void compositeConditionFailuresCannotBecomeTrueThroughNegation() {
        var svc = service();
        registry.registerCondition("broken", context -> { throw new IllegalStateException("failure"); });
        registry.registerCondition("no", context -> false);
        for (String spec : List.of("!broken", "not{not{broken}}", "!any{no;broken}",
                "any{no;!broken}", "!all{broken;no}")) {
            String key = svc.bindCondition(spec);
            assertNotNull(key, spec);
            assertFalse(registry.testCondition(key, ctx(1.0, 0)), spec);
        }
        assertTrue(registry.testCondition(svc.bindCondition("!no"), ctx(1.0, 0)));
        var calls = new AtomicInteger();
        registry.registerAction("probe", context -> calls.incrementAndGet());
        String failedAction = svc.bindAction("probe ?!broken");
        String validAction = svc.bindAction("probe ?!no");
        assertNotNull(failedAction);
        assertNotNull(validAction);
        assertTrue(registry.runAction(failedAction, ctx(1.0, 0)));
        assertEquals(0, calls.get());
        assertTrue(registry.runAction(validAction, ctx(1.0, 0)));
        assertEquals(1, calls.get());
    }

    @Test
    @DisplayName("condition composition honors unary not and boolean precedence")
    void conditionCompositionHonorsPrecedence() {
        var svc = service();
        registry.registerCondition("yes", context -> true);
        registry.registerCondition("no", context -> false);
        for (String spec : List.of("!yes && no", "yes && (no || no)",
                "all{yes;yes} && no")) {
            String key = svc.bindCondition(spec);
            assertNotNull(key, spec);
            assertFalse(registry.testCondition(key, ctx(1.0, 0)), spec);
        }
        String groupedOr = svc.bindCondition("no && (no || yes) || yes");
        assertNotNull(groupedOr);
        assertTrue(registry.testCondition(groupedOr, ctx(1.0, 0)));
        String negated = svc.bindCondition("!has-target");
        assertNotNull(negated);
        assertTrue(registry.testCondition(negated, BehaviorContext.of(null, null, 1.0, -1, 0, "IDLE")));

        String mixed = svc.bindCondition("has-target true && state-is IDLE || has-target false");
        assertNotNull(mixed);
        assertTrue(registry.testCondition(mixed, BehaviorContext.of(null, null, 1.0, -1, 0, "IDLE")));

        String grouped = svc.bindCondition("(has-target false && state-is IDLE)");
        assertNotNull(grouped);
        assertTrue(registry.testCondition(grouped, BehaviorContext.of(null, null, 1.0, -1, 0, "IDLE")));
    }

    @Test
    void scalarConditionListPreservesOrForRuntimeEvaluation() {
        var svc = service();
        registry.registerCondition("yes", context -> true);
        registry.registerCondition("no", context -> false);
        var conditions = dev.helstera.api.util.ConditionListParser.parseFailClosed("no || yes | yes");
        assertEquals(List.of("no || yes", "yes"), conditions);
        for (String condition : conditions) {
            String key = svc.bindCondition(condition);
            assertNotNull(key);
            assertTrue(registry.testCondition(key, ctx(1.0, 0)));
        }
    }

    @Test
    @DisplayName("audience-message binds a reusable audience-aware action")
    void audienceMessageIsRegistered() {
        var svc = service();
        String key = svc.bindAction("audience-message target hello");
        assertNotNull(key);
        assertTrue(registry.hasAction(key));
    }    @Test
    @DisplayName("projectile mechanics bind with Mythic shoot aliases")
    void projectileMechanicsBind() {
        var svc = service();
        String direct = svc.bindAction("shoot-projectile ARROW 1.6 3");
        String alias = svc.bindAction("shoot ARROW 1.6 3");
        String hitSkill = svc.bindAction("shoot-projectile ARROW 1.6 3 impact-skill 2 40 SPEED 1 false true 3 20");
        assertNotNull(direct);
        assertNotNull(alias);
        assertNotNull(hitSkill);
        assertTrue(registry.hasAction(direct));
        assertTrue(registry.hasAction(alias));
        String dynamic = svc.bindAction("shoot-projectile ARROW <target.hp.raw> <target.hp.raw>");
        assertNotNull(dynamic, svc.warnings().toString());
        assertTrue(registry.hasAction(dynamic));
    }    @Test
    @DisplayName("combat and movement mechanics bind with Mythic aliases")
    void combatMechanicsBind() {
        var svc = service();
        assertTrue(registry.hasAction(svc.bindAction("explosion 2 false false")));
        assertTrue(registry.hasAction(svc.bindAction("velocity 0 0.5 0")));
        assertTrue(registry.hasAction(svc.bindAction("set-target")));
        assertTrue(registry.hasAction(svc.bindAction("clear-target")));
        assertTrue(registry.hasAction(svc.bindAction("stop")));
    }    @Test
    @DisplayName("common Mythic mechanic aliases bind to native actions")
    void commonMythicAliasesBind() {
        var svc = service();
        assertTrue(registry.hasAction(svc.bindAction("settarget")));
        assertTrue(registry.hasAction(svc.bindAction("cleartarget")));
        assertTrue(registry.hasAction(svc.bindAction("remove")));
        assertTrue(registry.hasAction(svc.bindAction("setowner")));
        assertTrue(registry.hasAction(svc.bindAction("removeowner")));
        assertTrue(registry.hasAction(svc.bindAction("setparent")));
        assertTrue(registry.hasAction(svc.bindAction("removeparent")));
        assertTrue(registry.hasAction(svc.bindAction("addtag HelsteraProbe")));
        assertTrue(registry.hasAction(svc.bindAction("removetag HelsteraProbe")));
        assertTrue(registry.hasAction(svc.bindAction("add-scoreboard-tag HelsteraProbe")));
        assertTrue(registry.hasAction(svc.bindAction("setscore points 2")));
        assertTrue(registry.hasAction(svc.bindAction("addscore points 3")));
        assertTrue(registry.hasAction(svc.bindAction("closeinventory")));
        assertTrue(registry.hasAction(svc.bindAction("clearexp")));
        assertTrue(registry.hasAction(svc.bindAction("clearexplevels")));
        assertTrue(registry.hasAction(svc.bindAction("giveexplevels 2")));
        assertTrue(registry.hasAction(svc.bindAction("freeze{duration=80}")));
        assertTrue(registry.hasAction(svc.bindAction("ejectpassenger")));
        assertTrue(registry.hasAction(svc.bindAction("oxygen{amount=20}")));
        assertTrue(registry.hasAction(svc.bindAction("consumeslot{slot=0;amount=1}")));
        assertTrue(registry.hasAction(svc.bindAction("clearinventory")));
        assertTrue(registry.hasAction(svc.bindAction("givexp 25")));
        assertTrue(registry.hasAction(svc.bindAction("setabsorption 6")));
        assertTrue(registry.hasAction(svc.bindAction("removeitem DIAMOND 1")));
        assertTrue(registry.hasAction(svc.bindAction("removehelditem")));
        String setMaterialCooldown = svc.bindAction(
                "setmaterialcooldown{material=ENDER_PEARL;ticks=20}");
        assertTrue(registry.hasAction(setMaterialCooldown), svc.warnings().toString());
        assertTrue(registry.hasAction(svc.bindAction(
                "setitemcooldown{material=ENDER_PEARL;ticks=20}")));
        assertTrue(registry.hasAction(svc.bindAction("setgamemode{mode=ADVENTURE}")));
        assertTrue(registry.hasAction(svc.bindAction("setoffhand{item=SHIELD;amount=1}")));
        assertTrue(registry.hasAction(svc.bindAction("setarmor{helmet=IRON_HELMET;chestplate=AIR;leggings=AIR;boots=AIR}")));
        assertTrue(registry.hasAction(svc.bindAction("setfaction guards")));
        assertTrue(registry.hasAction(svc.bindAction("setlevel 5")));
        assertTrue(registry.hasAction(svc.bindAction("setweather{weather=rain;duration=20}")));
        assertTrue(registry.hasAction(svc.bindAction("settime noon")));
        assertTrue(registry.hasAction(svc.bindAction("lightning{effectonly=true}")));
        assertTrue(registry.hasAction(svc.bindAction("jump{velocity=0.42}")));
        assertTrue(registry.hasAction(svc.bindAction("forcepull{v=1.5}")));
        assertTrue(registry.hasAction(svc.bindAction("teleportself 1 2 3")));
        assertTrue(registry.hasAction(svc.bindAction("teleportcaster @origin")));
        assertTrue(registry.hasAction(svc.bindAction("sethealth 10")));
        assertTrue(registry.hasAction(svc.bindAction("modifyhealth -2")));
        assertTrue(registry.hasAction(svc.bindAction("modifyspeed 0.1")));
        assertTrue(registry.hasAction(svc.bindAction("knockbackself 1 0.4")));
        assertTrue(registry.hasAction(svc.bindAction("startcast fire")));
        assertTrue(registry.hasAction(svc.bindAction("cancelcast")));
        assertTrue(registry.hasAction(svc.bindAction("startdialogue intro")));
        assertTrue(registry.hasAction(svc.bindAction("setinvulnerable true")));
        assertTrue(registry.hasAction(svc.bindAction("aoedamage players 8 2 4")));
        assertTrue(registry.hasAction(svc.bindAction("playanimation attack false 3")));
        assertTrue(registry.hasAction(svc.bindAction("stopanimation")));
        assertTrue(registry.hasAction(svc.bindAction("leap 1.2")));
        assertTrue(registry.hasAction(svc.bindAction("settickspeed 1.1")));
        assertTrue(registry.hasAction(svc.bindAction("command say <target.name>")));
        assertTrue(registry.hasAction(svc.bindAction("command-self help")));
        assertTrue(registry.hasAction(svc.bindAction("command-target help")));
        assertTrue(registry.hasAction(svc.bindAction("damagemelee 3 2.5")));
        assertTrue(registry.hasAction(svc.bindAction("addthreat 5")));
        assertTrue(registry.hasAction(svc.bindAction("clearthreat true")));
        assertTrue(registry.hasAction(svc.bindAction("threatfocus")));
        assertTrue(registry.hasAction(svc.bindAction("messageall Hello 16")));
        assertTrue(registry.hasAction(svc.bindAction("messageself Hello")));
        assertTrue(registry.hasAction(svc.bindAction("randommessage One|Two")));
        assertTrue(registry.hasAction(svc.bindAction("gcd 40")));
        assertTrue(registry.hasAction(svc.bindAction("setglobalcooldown 40")));
        assertNotNull(svc.bindCondition("offgcd"));
    }    @Test
    @DisplayName("audience title aliases bind successfully")
    void audienceTitleAliasesBind() {
        var svc = service();
        assertTrue(registry.hasAction(svc.bindAction("audience-title target Hello")));
        assertTrue(registry.hasAction(svc.bindAction("title-audience target Hello")));
    }    @Test
    @DisplayName("audience particle aliases bind successfully")
    void audienceParticleAliasesBind() {
        var svc = service();
        assertTrue(registry.hasAction(svc.bindAction("audience-particle target FLAME 4")));
        assertTrue(registry.hasAction(svc.bindAction("particle-audience target FLAME 4")));
    }    @Test
    @DisplayName("audience specification accepts permission and faction filters")
    void audienceSpecActionBindsWithFilters() {
        var svc = service();
        assertNotNull(svc.bindAction("audience-message nearby hello"));
    }    @Test
    @DisplayName("audience actionbar aliases bind successfully")
    void audienceActionbarAliasesBind() {
        var svc = service();
        assertNotNull(svc.bindAction("audience-actionbar target hello"));
        assertNotNull(svc.bindAction("actionbar-audience target hello"));
        assertNotNull(svc.bindAction("actionbar hello"));
        assertNotNull(svc.bindAction("title Hello Subtitle 5 40 5"));
    }

    @Test
    @DisplayName("title mechanic sends title, subtitle, and timing to the active player target")
    void titleMechanicSendsFullTitlePayload() {
        var svc = service();
        Player player = mock(Player.class);
        BehaviorContext context = BehaviorContext.of(null, player, 1.0, -1, 0, "IDLE");
        String action = svc.bindAction("title Hello Subtitle 5 40 5");

        assertNotNull(action, svc.warnings().toString());
        assertTrue(registry.runAction(action, context));
        verify(player).sendTitle("Hello", "Subtitle", 5, 40, 5);
    }

    @Test
    @DisplayName("branch-unlock is available as a SkillTree action")
    void branchUnlockActionBinds() {
        var svc = service();
        String key = svc.bindAction("branch-unlock advanced");
        assertNotNull(key);
        assertTrue(registry.hasAction(key));
        var tree = new dev.helstera.api.behavior.SkillTreeContext(null, List.of());
        tree.defineBranch("advanced", java.util.Set.of());
        var context = BehaviorContext.continueExecution(null, (org.bukkit.entity.Player) null,
                1.0, -1, 0, "IDLE", null, null, List.of(), null, tree);
        assertTrue(svc.runAction(key, context));
        assertTrue(tree.branchUnlocked("advanced"));
    }

    @Test
    @DisplayName("SkillTree branch conditions read unlocked state")
    void branchConditionsReadSkillTreeState() {
        var svc = service();
        var tree = new dev.helstera.api.behavior.SkillTreeContext(null, List.of());
        var context = dev.helstera.api.behavior.BehaviorContext.continueExecution(
                null, (LivingEntity) null, 1.0, -1, 0, "skill", null,
                null, List.of(), null, tree);
        String unlocked = svc.bindCondition("branch-unlocked combat");
        String locked = svc.bindCondition("branch-locked combat");

        assertFalse(registry.testCondition(unlocked, context));
        assertTrue(registry.testCondition(locked, context));
        tree.unlockBranch("combat");
        assertTrue(registry.testCondition(unlocked, context));
        assertFalse(registry.testCondition(locked, context));
    }

    @Test
    @DisplayName("branch unlock accepts an inline condition argument")
    void branchUnlockConditionBinds() {
        var svc = service();
        assertNotNull(svc.bindAction("branch-unlock{branch=combat;condition=health-above 0.5}"));
    }

    @Test
    void branchUnlockConditionRuns() {
        var svc = service();
        String key = svc.bindAction("branch-unlock{branch=combat;condition=health-above 0.5}");
        var tree = new dev.helstera.api.behavior.SkillTreeContext(null, List.of());
        tree.defineBranch("combat", java.util.Set.of());
        var context = BehaviorContext.continueExecution(null, (org.bukkit.entity.Player) null,
                0.8, -1, 0, "IDLE", null, null, List.of(), null, tree);
        assertTrue(svc.runAction(key, context));
        assertTrue(tree.branchUnlocked("combat"));
    }

    @Test
    @DisplayName("branch unlock conditions remain bound to their SkillService registry")
    void branchUnlockConditionUsesOwningRegistry() {
        var owningRegistry = new BehaviorRegistryImpl();
        var owningService = new SkillService(owningRegistry, null);
        String key = owningService.bindAction(
                "branch-unlock{branch=isolated;condition=health-above 0.5}");
        assertNotNull(key);

        new SkillService(new BehaviorRegistryImpl(), null);

        var tree = new dev.helstera.api.behavior.SkillTreeContext(null, List.of());
        tree.defineBranch("isolated", java.util.Set.of());
        var context = BehaviorContext.continueExecution(null, (LivingEntity) null,
                0.8, -1, 0, "IDLE", null, null, List.of(), null, tree);
        assertTrue(owningService.runAction(key, context));
        assertTrue(tree.branchUnlocked("isolated"));
    }

    @Test
    void branchUnlockMultipleConditionsRun() {
        var svc = service();
        String key = svc.bindAction("branch-unlock{branch=elite;conditions=health-above 0.5|has-target false}");
        var tree = new dev.helstera.api.behavior.SkillTreeContext(null, List.of());
        tree.defineBranch("elite", java.util.Set.of());
        var context = BehaviorContext.continueExecution(null, (org.bukkit.entity.Player) null,
                0.8, -1, 0, "IDLE", null, null, List.of(), null, tree);
        assertTrue(svc.runAction(key, context));
        assertTrue(tree.branchUnlocked("elite"));
    }

    @Test
    @DisplayName("branch gated skills block before unlock and run after persisted unlock")
    void branchGatedSkillCastFlow() {
        var fired = new AtomicInteger();
        registry.registerAction("probe", context -> fired.incrementAndGet());
        var svc = service();
        var yaml = YamlConfiguration.loadConfiguration(new StringReader("""
                skills:
                  unlock:
                    on-decision:
                      - 'branch-unlock{branch=combat}'
                  gated:
                    branch: combat
                    cooldown: 60s
                    on-decision:
                      - probe
                """));
        svc.loadSkills(yaml.getConfigurationSection("skills"));
        var instance = mock(ModelInstance.class);
        when(instance.instanceId()).thenReturn(42);
        when(instance.isValid()).thenReturn(true);
        var context = BehaviorContext.of(instance, (org.bukkit.entity.Player) null, 1.0, -1, 0, "IDLE");

        assertFalse(svc.castSkill("gated", context));
        assertEquals(0, fired.get());
        assertTrue(svc.castSkill("unlock", context));
        assertTrue(svc.castSkill("gated", context));
        assertEquals(1, fired.get());
    }

    @Test
    void lockedBranchCannotEnterBusyInstanceQueue() {
        var fired = new AtomicInteger();
        registry.registerAction("probe", context -> fired.incrementAndGet());
        var svc = service();
        svc.loadSkills(YamlConfiguration.loadConfiguration(new StringReader("""
                skills:
                  charged:
                    cast-duration: 60s
                    on-decision: [probe]
                  gated:
                    branch: combat
                    cooldown: 60s
                    on-decision: [probe]
                """)).getConfigurationSection("skills"));
        var instance = mock(ModelInstance.class);
        when(instance.instanceId()).thenReturn(43);
        when(instance.isValid()).thenReturn(true);
        var tree = new dev.helstera.api.behavior.SkillTreeContext(null, List.of());
        var context = BehaviorContext.continueExecution(instance, (LivingEntity) null,
                1, -1, 0, "IDLE", null, null, List.of(), null, tree);

        assertTrue(svc.castSkill("charged", context));
        assertFalse(svc.castSkill("gated", context));
        assertEquals(0, svc.queuedSkillCount());
        assertTrue(tree.unlockBranch("combat"));
        assertTrue(svc.castSkill("gated", context));
        assertEquals(1, svc.queuedSkillCount());
        assertTrue(svc.cancelCast(43));
        assertEquals(1, fired.get());
    }

    @Test
    void queuedSkillsRestoreLatestAutomaticallyManagedTree() throws Exception {
        var svc = service();
        svc.loadSkills(YamlConfiguration.loadConfiguration(new StringReader("""
                skills:
                  charged:
                    cast-duration: 20ms
                    on-decision: ['var-set marker 4']
                  queued:
                    on-decision: ['var-add marker 3']
                """)).getConfigurationSection("skills"));
        var instance = mock(ModelInstance.class);
        when(instance.instanceId()).thenReturn(44);
        when(instance.isValid()).thenReturn(true);
        var context = BehaviorContext.of(instance, (LivingEntity) null, 1, -1, 0, "IDLE");

        assertTrue(svc.castSkill("charged", context));
        assertTrue(svc.castSkill("queued", context));
        assertEquals(1, svc.queuedSkillCount());
        Thread.sleep(40);
        svc.tickCasts();
        var value = svc.snapshotOf(44).orElseThrow().variables().get("marker");
        assertEquals(7.0, ((Number) value).doubleValue());
    }

    @Test
    void queuedSkillKeepsExplicitTreeContext() {
        var seen = new java.util.concurrent.atomic.AtomicReference<dev.helstera.api.behavior.SkillTreeContext>();
        registry.registerAction("capture-tree", context -> seen.set(context.skillTree().orElseThrow()));
        var svc = service();
        svc.loadSkills(YamlConfiguration.loadConfiguration(new StringReader("""
                skills:
                  charged:
                    cast-duration: 60s
                    on-decision: ['var-set marker 4']
                  queued:
                    on-decision: [capture-tree]
                """)).getConfigurationSection("skills"));
        var instance = mock(ModelInstance.class);
        when(instance.instanceId()).thenReturn(45);
        when(instance.isValid()).thenReturn(true);
        var plain = BehaviorContext.of(instance, (LivingEntity) null, 1, -1, 0, "IDLE");
        var tree = new dev.helstera.api.behavior.SkillTreeContext(null, List.of());
        var explicit = BehaviorContext.continueExecution(instance, (LivingEntity) null,
                1, -1, 0, "IDLE", null, null, List.of(), null, tree);
        assertTrue(svc.castSkill("charged", plain));
        assertTrue(svc.castSkill("queued", explicit));
        svc.restoreSkillTreeSnapshot(45,
                new dev.helstera.api.behavior.SkillTreeContext(null, List.of()).snapshot());
        assertTrue(svc.cancelCast(45));
        assertTrue(tree == seen.get());
    }

    @Test
    @DisplayName("branch-requires gate restores unlocked branches across fresh cast contexts")
    void branchGateRestoresSnapshotAcrossCasts() {
        var fired = new AtomicInteger();
        registry.registerAction("probe", context -> fired.incrementAndGet());
        var svc = service();
        var yaml = YamlConfiguration.loadConfiguration(new StringReader("""
                skills:
                  unlock_foundation:
                    on-decision:
                      - 'branch-unlock{branch=foundation}'
                  unlock_advanced:
                    on-decision:
                      - 'branch-unlock{branch=advanced}'
                  advanced:
                    branch: advanced
                    branch-requires:
                      - foundation
                    on-decision:
                      - probe
                """));
        svc.loadSkills(yaml.getConfigurationSection("skills"));
        var instance = mock(ModelInstance.class);
        when(instance.instanceId()).thenReturn(73);
        when(instance.isValid()).thenReturn(true);
        var lockedContext = BehaviorContext.of(instance, (LivingEntity) null, 1.0, -1, 0, "IDLE");

        for (int attempt = 0; attempt < 20; attempt++) {
            assertFalse(svc.castSkill("advanced", lockedContext));
        }
        assertEquals(0, fired.get());

        assertTrue(svc.castSkill("unlock_advanced", lockedContext));
        assertFalse(svc.snapshotOf(73).orElseThrow().unlockedBranches().contains("advanced"));
        assertTrue(svc.castSkill("unlock_foundation", lockedContext));
        assertTrue(svc.snapshotOf(73).orElseThrow().unlockedBranches().contains("foundation"));
        assertTrue(svc.castSkill("unlock_advanced", lockedContext));
        assertTrue(svc.snapshotOf(73).orElseThrow().unlockedBranches().contains("advanced"));

        var freshContext = BehaviorContext.of(instance, (LivingEntity) null, 1.0, -1, 0, "IDLE");
        assertTrue(svc.castSkill("advanced", freshContext));
        assertEquals(1, fired.get());
    }

    @Test
    void branchUnlockAnyAndNotConditionsRun() {
        var svc = service();
        String anyKey = svc.bindAction(
                "branch-unlock{branch=any-branch;condition=any{health-below 0.1;has-target false}}");
        String notKey = svc.bindAction(
                "branch-unlock{branch=not-branch;condition=not{health-below 0.1}}");
        var tree = new dev.helstera.api.behavior.SkillTreeContext(null, List.of());
        tree.defineBranch("any-branch", java.util.Set.of());
        tree.defineBranch("not-branch", java.util.Set.of());
        var context = BehaviorContext.continueExecution(null, (org.bukkit.entity.Player) null,
                0.8, -1, 0, "IDLE", null, null, List.of(), null, tree);

        assertTrue(svc.runAction(anyKey, context));
        assertTrue(svc.runAction(notKey, context));
        assertTrue(tree.branchUnlocked("any-branch"));
        assertTrue(tree.branchUnlocked("not-branch"));
    }

    @Test
    @DisplayName("teleport mechanics bind location and caster aliases")
    void teleportMechanicsBind() {
        var svc = service();
        assertTrue(registry.hasAction(svc.bindAction("teleport @targetlocation")));
        assertTrue(registry.hasAction(svc.bindAction("teleport @triggerlocation")));
        assertTrue(registry.hasAction(svc.bindAction("teleport @VariableLocation{var=skill.mark}")));
        assertTrue(registry.hasAction(svc.bindAction("teleport-caster @origin")));
        assertTrue(registry.hasAction(svc.bindAction("teleport-self 1 2 3")));
        assertTrue(registry.hasAction(svc.bindAction(
                "teleport-mythic{spreadh=2;spreadv=1;unsafe=false;yaw=preserve;pitch=preserve} @target")));
    }
    @Test
    void invalidExecutionOptionsAreRejectedAtBinding() {
        var svc = service();
        for (String option : List.of("delay=bad", "delay=-1", "delay=NaN", "delay=Infinity",
                "delay=1e100s", "repeat=-1", "repeat=1.5", "repeat=2147483648",
                "repeatinterval=-1", "repeat-interval=bad", "repeat=2147483647;repeatinterval=1",
                "delay=-1;DELAY=2", "repeat=1;repeat=2", "repeatinterval=1;repeat-interval=2",
                "repeat-interval=2;repeatinterval=1")) {
            String spec = "damage-target{" + option + "} 4";
            assertNull(svc.bindAction(spec), spec);
            assertTrue(svc.warnings().stream().anyMatch(w -> w.contains(spec)), spec);
        }
        String valid = svc.bindAction("damage-target{delay=50ms;repeat=2;repeatinterval=0.1s} 4");
        assertNotNull(valid, svc.warnings().toString());
        assertTrue(valid.contains("delay=1:repeat=2:interval=2"), valid);
        assertNull(svc.bindAction("skill{s=child;delay=bad}"));
        assertNull(svc.bindAction("randomskill{skills=child;repeat=-1}"));
        for (String spec : List.of("damage-target 4 0% 100%", "damage-target 4 @self @target")) {
            assertNull(svc.bindAction(spec), spec);
            assertTrue(svc.warnings().stream().anyMatch(w -> w.contains(spec)), spec);
        }
    }

    @Test
    @DisplayName("delay and repeat metadata wrap registered actions instead of bypassing them")
    void timingMetadataWrapsRegisteredAction() {
        registry.registerAction("probe", ctx -> { });
        var svc = service();
        String key = svc.bindAction("probe{delay=2;repeat=3;repeat-interval=1}");
        assertNotNull(key);
        assertTrue(key.contains("delay=2"), key);
        assertFalse("probe".equals(key), "execution metadata must not bypass the wrapper");
    }

    @Test
    @DisplayName("repeat scheduling executes every repetition with the configured delays")
    void repeatSchedulingRunsAllExecutions() {
        var fired = new AtomicInteger();
        var delays = new java.util.ArrayList<Long>();
        registry.registerAction("probe", ctx -> fired.incrementAndGet());
        var svc = service();
        dev.helstera.ai.skill.SkillCatalog.taskScheduler((task, delay) -> {
            delays.add(delay);
            task.run();
            return true;
        });
        try {
            String key = svc.bindAction("probe{delay=2;repeat=3;repeatinterval=1}");
            assertTrue(registry.runAction(key, ctx(1, 0)));
            assertEquals(4, fired.get());
            assertEquals(List.of(2L, 1L, 1L, 1L), delays);
        } finally {
            dev.helstera.ai.skill.SkillCatalog.taskScheduler(null);
        }
    }

    @Test
    @DisplayName("clearing an instance cancels delayed work and releases generation state")
    void clearingInstanceCancelsDelayedWork() throws Exception {
        var fired = new AtomicInteger();
        var scheduled = new java.util.ArrayList<Runnable>();
        registry.registerAction("probe", context -> fired.incrementAndGet());
        var svc = service();
        dev.helstera.ai.skill.SkillCatalog.taskScheduler((task, delay) -> {
            scheduled.add(task);
            return true;
        });
        try {
            String key = svc.bindAction("probe{delay=2}");
            assertTrue(registry.runAction(key, ctx(1, 0)));
            assertEquals(1, scheduled.size());
            svc.clearQueueFor(-1);
            scheduled.get(0).run();
            assertEquals(0, fired.get(), "cleared delayed work must not execute");

            var generations = SkillService.class.getDeclaredField("executionGenerations");
            generations.setAccessible(true);
            assertTrue(((Map<?, ?>) generations.get(svc)).isEmpty(),
                    "generation state must be reclaimed after the cancelled continuation runs");
        } finally {
            dev.helstera.ai.skill.SkillCatalog.taskScheduler(null);
        }
    }

    @Test
    void forgettingInstanceCancelsItsDelayedWorkButAllowsNewWork() {
        var fired = new java.util.ArrayList<Integer>();
        var scheduled = new java.util.ArrayList<Runnable>();
        registry.registerAction("capture-instance", context -> fired.add(context.instance().instanceId()));
        var svc = service();
        var removed = mock(ModelInstance.class);
        var other = mock(ModelInstance.class);
        when(removed.instanceId()).thenReturn(101);
        when(other.instanceId()).thenReturn(102);
        var removedContext = BehaviorContext.of(removed, (LivingEntity) null, 1, -1, 0, "IDLE");
        var otherContext = BehaviorContext.of(other, (LivingEntity) null, 1, -1, 0, "IDLE");
        dev.helstera.ai.skill.SkillCatalog.taskScheduler((task, delay) -> {
            scheduled.add(task);
            return true;
        });
        try {
            String action = svc.bindAction("capture-instance{delay=2}");
            assertTrue(registry.runAction(action, removedContext));
            assertTrue(registry.runAction(action, otherContext));
            svc.forget(101);
            assertTrue(registry.runAction(action, removedContext));
            scheduled.forEach(Runnable::run);
            assertEquals(List.of(102, 101), fired);
        } finally {
            dev.helstera.ai.skill.SkillCatalog.taskScheduler(null);
        }
    }

    @Test
    void forgettingInstanceDuringRepeatStopsRemainingExecutions() {
        var scheduled = new java.util.ArrayList<Runnable>();
        var fired = new AtomicInteger();
        var svc = service();
        var instance = mock(ModelInstance.class);
        when(instance.instanceId()).thenReturn(103);
        var context = BehaviorContext.of(instance, (LivingEntity) null, 1, -1, 0, "IDLE");
        registry.registerAction("forget-self", ignored -> {
            fired.incrementAndGet();
            svc.forget(103);
        });
        dev.helstera.ai.skill.SkillCatalog.taskScheduler((task, delay) -> {
            scheduled.add(task);
            return true;
        });
        try {
            String action = svc.bindAction("forget-self{delay=1;repeat=2;repeatinterval=1}");
            assertTrue(registry.runAction(action, context));
            for (int index = 0; index < scheduled.size(); index++) scheduled.get(index).run();
            assertEquals(1, fired.get());
        } finally {
            dev.helstera.ai.skill.SkillCatalog.taskScheduler(null);
        }
    }

    @Test
    void forgettingInstanceDuringSkillStopsActionsAndDoesNotRestoreSnapshot() {
        var svc = service();
        var fired = new AtomicInteger();
        registry.registerAction("detach-self", context -> svc.forget(context.instance().instanceId()));
        registry.registerAction("after-detach", context -> fired.incrementAndGet());
        svc.loadSkills(YamlConfiguration.loadConfiguration(new StringReader("""
                skills:
                  removed:
                    on-decision: ['var-set marker 1', detach-self, after-detach]
                  replacement:
                    on-decision: ['var-set marker 2']
                """)).getConfigurationSection("skills"));
        var instance = mock(ModelInstance.class);
        when(instance.instanceId()).thenReturn(107);
        when(instance.isValid()).thenReturn(true);
        var context = BehaviorContext.of(instance, (LivingEntity) null, 1, -1, 0, "IDLE");

        assertTrue(svc.castSkill("removed", context));
        assertEquals(0, fired.get());
        assertTrue(svc.snapshotOf(107).isEmpty());
        assertTrue(svc.castSkill("replacement", context));
        assertEquals(2.0, ((Number) svc.snapshotOf(107).orElseThrow().variables().get("marker")).doubleValue());
    }

    @Test
    void forgettingInstanceInNestedSkillStopsOuterActions() {
        var svc = service();
        var fired = new AtomicInteger();
        registry.registerAction("detach-self", context -> svc.forget(context.instance().instanceId()));
        registry.registerAction("after-detach", context -> fired.incrementAndGet());
        svc.loadSkills(YamlConfiguration.loadConfiguration(new StringReader("""
                skills:
                  inner:
                    on-decision: [detach-self, after-detach]
                  outer:
                    on-decision: ['cast-skill inner', after-detach]
                """)).getConfigurationSection("skills"));
        var instance = mock(ModelInstance.class);
        when(instance.instanceId()).thenReturn(108);
        when(instance.isValid()).thenReturn(true);
        var context = BehaviorContext.of(instance, (LivingEntity) null, 1, -1, 0, "IDLE");
        assertTrue(svc.castSkill("outer", context));
        assertEquals(0, fired.get());
        assertTrue(svc.snapshotOf(108).isEmpty());
    }

    @Test
    @DisplayName("reload invalidates every old delayed continuation")
    void reloadInvalidatesOldDelayedWork() throws Exception {
        var fired = new AtomicInteger();
        var scheduled = new java.util.ArrayList<Runnable>();
        registry.registerAction("probe", context -> fired.incrementAndGet());
        var svc = service();
        dev.helstera.ai.skill.SkillCatalog.taskScheduler((task, delay) -> {
            scheduled.add(task);
            return true;
        });
        try {
            String key = svc.bindAction("probe{delay=2}");
            assertTrue(registry.runAction(key, ctx(1, 0)));
            assertTrue(registry.runAction(key, ctx(1, 0)));
            assertEquals(2, scheduled.size());

            svc.loadSkills(null);
            scheduled.forEach(Runnable::run);
            assertEquals(0, fired.get(), "reload must invalidate all old delayed actions");

            var generations = SkillService.class.getDeclaredField("executionGenerations");
            generations.setAccessible(true);
            assertTrue(((Map<?, ?>) generations.get(svc)).isEmpty(),
                    "reload tombstones must be reclaimed after old continuations drain");
        } finally {
            dev.helstera.ai.skill.SkillCatalog.taskScheduler(null);
        }
    }

    @Test
    void reloadDuringSkillStopsOldChainAndAllowsNewDefinition() {
        var svc = service();
        var oldActions = new AtomicInteger();
        var newActions = new AtomicInteger();
        registry.registerAction("old-action", ignored -> oldActions.incrementAndGet());
        registry.registerAction("new-action", ignored -> newActions.incrementAndGet());
        var replacement = YamlConfiguration.loadConfiguration(new StringReader("""
                skills:
                  replacement:
                    on-decision: ['var-set marker 2', new-action]
                """));
        registry.registerAction("reload-now", context -> {
            svc.loadSkills(replacement.getConfigurationSection("skills"));
            assertTrue(svc.castSkill("replacement", context));
        });
        svc.loadSkills(YamlConfiguration.loadConfiguration(new StringReader("""
                skills:
                  old:
                    on-decision: ['var-set marker 1', reload-now, old-action]
                """)).getConfigurationSection("skills"));
        var instance = mock(ModelInstance.class);
        when(instance.instanceId()).thenReturn(109);
        when(instance.isValid()).thenReturn(true);
        var context = BehaviorContext.of(instance, (LivingEntity) null, 1, -1, 0, "IDLE");
        assertTrue(svc.castSkill("old", context));
        assertEquals(0, oldActions.get());
        assertEquals(1, newActions.get());
        assertEquals(2.0, ((Number) svc.snapshotOf(109).orElseThrow().variables().get("marker")).doubleValue());
    }

    @Test
    void reloadDuringSkillWithoutInstanceStopsOldChain() {
        var svc = service();
        var fired = new AtomicInteger();
        registry.registerAction("reload-now", context -> svc.loadSkills(null));
        registry.registerAction("old-action", context -> fired.incrementAndGet());
        svc.loadSkills(YamlConfiguration.loadConfiguration(new StringReader("""
                skills:
                  old:
                    on-decision: [reload-now, old-action]
                """)).getConfigurationSection("skills"));
        assertTrue(svc.castSkill("old", ctx(1, 0)));
        assertEquals(0, fired.get());
    }

    @Test
    @DisplayName("scheduler exceptions fail closed and release continuation state")
    void schedulerExceptionReleasesContinuationState() throws Exception {
        registry.registerAction("probe", context -> { });
        var svc = service();
        dev.helstera.ai.skill.SkillCatalog.taskScheduler((task, delay) -> {
            throw new IllegalStateException("scheduler stopped");
        });
        try {
            String key = svc.bindAction("probe{delay=2}");
            assertTrue(registry.runAction(key, ctx(1, 0)));
            assertTrue(svc.warnings().stream().anyMatch(w -> w.contains("调度")),
                    svc.warnings().toString());

            var pending = SkillService.class.getDeclaredField("pendingScheduledActions");
            pending.setAccessible(true);
            assertTrue(((Map<?, ?>) pending.get(svc)).isEmpty(),
                    "failed scheduling must not retain pending continuation state");
        } finally {
            dev.helstera.ai.skill.SkillCatalog.taskScheduler(null);
        }
    }

    @Test
    @DisplayName("throwing delayed actions still reclaim continuation state")
    void throwingDelayedActionReclaimsState() throws Exception {
        Map<String, SkillCatalog.ActionFactory> actions = new java.util.LinkedHashMap<>(
                SkillCatalog.actions());
        actions.put("boom", args -> context -> {
            throw new IllegalStateException("action failed");
        });
        var svc = new SkillService(registry, null, SkillCatalog.conditions(), actions);
        var scheduled = new java.util.ArrayList<Runnable>();
        SkillCatalog.taskScheduler((task, delay) -> {
            scheduled.add(task);
            return true;
        });
        try {
            String key = svc.bindAction("boom{delay=2}");
            assertTrue(registry.runAction(key, ctx(1, 0)));
            assertThrows(IllegalStateException.class, () -> scheduled.get(0).run());

            var pending = SkillService.class.getDeclaredField("pendingScheduledActions");
            pending.setAccessible(true);
            assertTrue(((Map<?, ?>) pending.get(svc)).isEmpty());
            var generations = SkillService.class.getDeclaredField("executionGenerations");
            generations.setAccessible(true);
            assertTrue(((Map<?, ?>) generations.get(svc)).isEmpty());
        } finally {
            SkillCatalog.taskScheduler(null);
        }
    }

    @Test
    @DisplayName("repeat re-evaluates chance and inline conditions for every execution")
    void repeatReevaluatesGuards() {
        var fired = new AtomicInteger();
        var checks = new AtomicInteger();
        registry.registerAction("probe", ctx -> fired.incrementAndGet());
        registry.registerCondition("gate", ctx -> checks.incrementAndGet() % 2 == 1);
        var svc = service();
        dev.helstera.ai.skill.SkillCatalog.taskScheduler((task, delay) -> {
            task.run();
            return true;
        });
        try {
            String key = svc.bindAction("probe{repeat=3;repeatinterval=1} ?gate");
            assertTrue(registry.runAction(key, ctx(1, 0)));
            assertEquals(4, checks.get());
            assertEquals(2, fired.get());
        } finally {
            dev.helstera.ai.skill.SkillCatalog.taskScheduler(null);
        }
    }

    @Test
    @DisplayName("cast-duration defers actions until tickCasts completes the cast")
    void castDurationDefersActions() throws Exception {
        var fired = new AtomicInteger();
        registry.registerAction("probe", ctx -> fired.incrementAndGet());
        var svc = service();
        svc.loadSkills(YamlConfiguration.loadConfiguration(new StringReader("""
                skills:
                  charged:
                    cast-duration: 10ms
                    on-decision: [probe]
                """)).getConfigurationSection("skills"));

        assertTrue(svc.castSkill("charged", ctx(1, 0)));
        assertEquals(0, fired.get());
        assertEquals(1, svc.activeCastCount());
        Thread.sleep(30);
        svc.tickCasts();
        assertEquals(1, fired.get());
        assertEquals(0, svc.activeCastCount());
    }

    @Test
    void startCastHonorsNamedSkillConditionsBranchAndCooldown() {
        var svc = service();
        registry.registerAction("probe", context -> { });
        registry.registerCondition("reject-cast", context -> false);
        svc.loadSkills(YamlConfiguration.loadConfiguration(new StringReader("""
                skills:
                  invalid:
                    cast-duration: 60s
                    require: [missing-condition]
                    on-decision: [probe]
                  rejected:
                    cast-duration: 60s
                    require: [reject-cast]
                    on-decision: [probe]
                  gated:
                    cast-duration: 60s
                    cooldown: 60s
                    branch: combat
                    on-decision: [probe]
                  instant:
                    on-decision: [probe]
                """)).getConfigurationSection("skills"));
        var instance = mock(ModelInstance.class);
        when(instance.instanceId()).thenReturn(104);
        when(instance.isValid()).thenReturn(true);
        var tree = new dev.helstera.api.behavior.SkillTreeContext(null, List.of());
        var context = BehaviorContext.continueExecution(instance, (LivingEntity) null,
                1, -1, 0, "IDLE", null, null, List.of(), null, tree);

        assertFalse(svc.startCast("invalid", context));
        assertFalse(svc.startCast("rejected", context));
        assertFalse(svc.startCast("gated", context));
        assertFalse(svc.startCast("instant", context));
        assertEquals(0, svc.activeCastCount());
        assertTrue(tree.unlockBranch("combat"));
        assertTrue(svc.startCast("gated", context));
        assertEquals(1, svc.activeCastCount());
        assertTrue(svc.cancelCast(104));
        assertFalse(svc.startCast("gated", context));
        assertEquals(0, svc.activeCastCount());
    }

    @Test
    void startCastActionCannotBypassNamedSkillConditions() {
        var svc = service();
        registry.registerAction("probe", context -> { });
        registry.registerCondition("reject-cast", context -> false);
        svc.loadSkills(YamlConfiguration.loadConfiguration(new StringReader("""
                skills:
                  gated:
                    cast-duration: 60s
                    require: [reject-cast]
                    on-decision: [probe]
                """)).getConfigurationSection("skills"));
        var instance = mock(ModelInstance.class);
        when(instance.instanceId()).thenReturn(105);
        when(instance.isValid()).thenReturn(true);
        var context = BehaviorContext.of(instance, (LivingEntity) null, 1, -1, 0, "IDLE");
        dev.helstera.ai.skill.SkillCatalog.skillService(svc);
        try {
            String action = svc.bindAction("start-cast gated");
            assertNotNull(action);
            assertTrue(registry.runAction(action, context));
            assertEquals(0, svc.activeCastCount());
        } finally {
            dev.helstera.ai.skill.SkillCatalog.skillService(null);
        }
    }

    @Test
    void castActionsUseTheirOwningServiceInsteadOfGlobalHook() {
        var owning = service();
        var otherRegistry = new BehaviorRegistryImpl();
        var other = new SkillService(otherRegistry, null);
        var configuration = YamlConfiguration.loadConfiguration(new StringReader("""
                skills:
                  charged:
                    cast-duration: 60s
                    on-decision: []
                """));
        owning.loadSkills(configuration.getConfigurationSection("skills"));
        other.loadSkills(configuration.getConfigurationSection("skills"));
        var instance = mock(ModelInstance.class);
        when(instance.instanceId()).thenReturn(106);
        when(instance.isValid()).thenReturn(true);
        var context = BehaviorContext.of(instance, (LivingEntity) null, 1, -1, 0, "IDLE");
        dev.helstera.ai.skill.SkillCatalog.skillService(other);
        try {
            String start = owning.bindAction("start-cast charged");
            String cancel = owning.bindAction("cancel-cast");
            assertTrue(owning.runAction(start, context));
            assertEquals(1, owning.activeCastCount());
            assertEquals(0, other.activeCastCount());
            assertTrue(other.startCast("charged", context));
            assertTrue(owning.runAction(cancel, context));
            assertEquals(0, owning.activeCastCount());
            assertEquals(1, other.activeCastCount());
        } finally {
            dev.helstera.ai.skill.SkillCatalog.skillService(null);
        }
    }

    @Test
    @DisplayName("cancel-cast removes the active cast without executing deferred actions")
    void cancelCastDropsDeferredAction() throws Exception {
        var fired = new AtomicInteger();
        registry.registerAction("probe", ctx -> fired.incrementAndGet());
        var svc = service();
        svc.loadSkills(YamlConfiguration.loadConfiguration(new StringReader("""
                skills:
                  charged:
                    cast-duration: 20ms
                    on-decision: [probe]
                """)).getConfigurationSection("skills"));

        assertTrue(svc.castSkill("charged", ctx(1, 0)));
        assertTrue(svc.cancelCast(-1));
        Thread.sleep(30);
        svc.tickCasts();
        assertEquals(0, fired.get());
        assertEquals(0, svc.activeCastCount());
    }

    @Test
    @DisplayName("persistent mode skips AI ticks in unloaded chunks")
    void persistentModeTickPolicy() {
        assertFalse(AiManager.tickInUnloadedChunk("PERSISTENT"));
        assertTrue(AiManager.tickInUnloadedChunk("NORMAL"));
        assertTrue(AiManager.tickInUnloadedChunk("CHUNK"));
    }

    @Test
    @DisplayName("AI profile loads ranged projectile configuration")
    void loadsRangedProjectileConfiguration() {
        var yaml = YamlConfiguration.loadConfiguration(new StringReader("""
                ranged-attack: true
                ranged-projectile-type: FIREBALL
                ranged-hit-skill: burn_target
                ranged-projectile-gravity: false
                ranged-projectile-fire-ticks: 60
                ranged-projectile-explosion: 2.5
                ranged-projectile-max-hits: 3
                ranged-projectile-potion: POISON:60:1
                ranged-projectile-pierce: 2
                ranged-projectile-tracking: true
                ranged-projectile-tracking-ticks: 40
                """));
        AiProfile profile = AiProfile.fromSection("ranged", yaml);
        assertTrue(profile.rangedAttack);
        assertEquals("FIREBALL", profile.rangedProjectileType);
        assertEquals("burn_target", profile.rangedHitSkill);
        assertFalse(profile.rangedProjectileGravity);
        assertEquals(60, profile.rangedProjectileFireTicks);
        assertEquals(2.5, profile.rangedProjectileExplosion, 1e-9);
        assertEquals(3, profile.rangedProjectileMaxHits);
        assertEquals("POISON:60:1", profile.rangedProjectilePotion);
        assertEquals(2, profile.rangedProjectilePierce);
        assertTrue(profile.rangedProjectileTracking);
        assertEquals(40, profile.rangedProjectileTrackingTicks);
    }

    @Test
    @DisplayName("Mythic entity category conditions resolve aliases against the active target")
    void entityCategoryConditionsUseTargetContext() {
        SkillService service = service();
        Player player = mock(Player.class);
        BehaviorContext playerContext = BehaviorContext.of(null, player, 1.0, -1, 0, "IDLE");

        for (String alias : List.of("isLiving", "isPlayer", "player")) {
            String key = service.bindCondition(alias);
            assertNotNull(key, alias);
            assertTrue(registry.testCondition(key, playerContext), alias);
        }
        for (String alias : List.of("isMob", "isMonster", "isAnimal", "isVillager",
                "isTameable", "isRaider")) {
            String key = service.bindCondition(alias);
            assertNotNull(key, alias);
            assertFalse(registry.testCondition(key, playerContext), alias);
        }

        org.bukkit.entity.Zombie zombie = mock(org.bukkit.entity.Zombie.class);
        BehaviorContext monsterContext = BehaviorContext.of(null, zombie, 1.0, -1, 0, "IDLE");
        assertTrue(registry.testCondition(service.bindCondition("isLiving"), monsterContext));
        assertTrue(registry.testCondition(service.bindCondition("isMob"), monsterContext));
        assertTrue(registry.testCondition(service.bindCondition("isMonster"), monsterContext));
        assertFalse(registry.testCondition(service.bindCondition("isPlayer"), monsterContext));
    }

    @Test
    @DisplayName("Mythic undead, creature and age conditions use Bukkit type capabilities")
    void entityCapabilityConditionsUseBukkitTypes() {
        SkillService service = service();
        org.bukkit.entity.Zombie zombie = mock(org.bukkit.entity.Zombie.class);
        when(zombie.getType()).thenReturn(org.bukkit.entity.EntityType.ZOMBIE);
        BehaviorContext zombieContext = BehaviorContext.of(null, zombie, 1.0, -1, 0, "IDLE");

        assertTrue(registry.testCondition(service.bindCondition("isUndead"), zombieContext));
        assertTrue(registry.testCondition(service.bindCondition("isCreature"), zombieContext));

        org.bukkit.entity.Ageable baby = mock(org.bukkit.entity.Ageable.class);
        when(baby.isAdult()).thenReturn(false);
        BehaviorContext babyContext = BehaviorContext.of(null, baby, 1.0, -1, 0, "IDLE");
        assertTrue(registry.testCondition(service.bindCondition("baby"), babyContext));
        assertFalse(registry.testCondition(service.bindCondition("isChild"), babyContext));
        assertEquals(service.bindCondition("is-child"), service.bindCondition("child"));
        assertFalse(registry.testCondition(service.bindCondition("adult"), babyContext));

        when(baby.isAdult()).thenReturn(true);
        assertTrue(registry.testCondition(service.bindCondition("isAdult"), babyContext));
        assertFalse(registry.testCondition(service.bindCondition("isBaby"), babyContext));
    }

    @Test
    @DisplayName("Mythic tame, shear, saddle and powered conditions read entity state")
    void entityStateConditionsReadBukkitState() {
        SkillService service = service();

        org.bukkit.entity.Wolf wolf = mock(org.bukkit.entity.Wolf.class);
        when(wolf.isTamed()).thenReturn(true);
        BehaviorContext wolfContext = BehaviorContext.of(null, wolf, 1.0, -1, 0, "IDLE");
        assertTrue(registry.testCondition(service.bindCondition("isTamed"), wolfContext));

        org.bukkit.entity.Sheep sheep = mock(org.bukkit.entity.Sheep.class);
        when(sheep.isSheared()).thenReturn(true);
        BehaviorContext sheepContext = BehaviorContext.of(null, sheep, 1.0, -1, 0, "IDLE");
        assertTrue(registry.testCondition(service.bindCondition("sheared"), sheepContext));

        org.bukkit.entity.Horse horse = mock(org.bukkit.entity.Horse.class);
        org.bukkit.inventory.HorseInventory horseInventory =
                mock(org.bukkit.inventory.HorseInventory.class);
        org.bukkit.inventory.ItemStack saddle = mock(org.bukkit.inventory.ItemStack.class);
        org.bukkit.Material saddleType = mock(org.bukkit.Material.class);
        when(horse.getInventory()).thenReturn(horseInventory);
        when(horseInventory.getSaddle()).thenReturn(saddle);
        when(saddle.getType()).thenReturn(saddleType);
        when(saddleType.isAir()).thenReturn(false);
        BehaviorContext horseContext = BehaviorContext.of(null, horse, 1.0, -1, 0, "IDLE");
        assertTrue(registry.testCondition(service.bindCondition("isSaddled"), horseContext));

        org.bukkit.entity.Creeper creeper = mock(org.bukkit.entity.Creeper.class);
        when(creeper.isPowered()).thenReturn(true);
        BehaviorContext creeperContext = BehaviorContext.of(null, creeper, 1.0, -1, 0, "IDLE");
        assertTrue(registry.testCondition(service.bindCondition("charged"), creeperContext));
    }

    @Test
    @DisplayName("AI, gravity, silence, dead, and validity conditions read the caster entity")
    void entityLifecycleConditionsReadBukkitState() {
        SkillService service = service();
        var instance = mock(ModelInstance.class);
        var mob = mock(org.bukkit.entity.Mob.class);
        when(instance.isValid()).thenReturn(true);
        when(instance.baseEntity()).thenReturn(Optional.of(mob));
        when(mob.hasAI()).thenReturn(true);
        when(mob.hasGravity()).thenReturn(true);
        when(mob.isSilent()).thenReturn(false);
        when(mob.isDead()).thenReturn(false);
        when(mob.isValid()).thenReturn(true);
        BehaviorContext context = BehaviorContext.of(instance, (LivingEntity) null,
                1.0, -1, 0, "IDLE");

        assertTrue(registry.testCondition(service.bindCondition("hasAI"), context));
        assertTrue(registry.testCondition(service.bindCondition("hasGravity"), context));
        assertFalse(registry.testCondition(service.bindCondition("isSilent"), context));
        assertFalse(registry.testCondition(service.bindCondition("isDead"), context));
        assertTrue(registry.testCondition(service.bindCondition("isAlive"), context));
        assertTrue(registry.testCondition(service.bindCondition("isValid"), context));
        BehaviorContext noInstance = BehaviorContext.of(null, (LivingEntity) null,
                1.0, -1, 0, "IDLE");
        assertFalse(registry.testCondition(service.bindCondition("hasAI"), noInstance));
        assertFalse(registry.testCondition(service.bindCondition("hasGravity"), noInstance));
        assertFalse(registry.testCondition(service.bindCondition("isSilent"), noInstance));
        assertFalse(registry.testCondition(service.bindCondition("isDead"), noInstance));
        assertFalse(registry.testCondition(service.bindCondition("isAlive"), noInstance));
        assertFalse(registry.testCondition(service.bindCondition("isValid"), noInstance));
    }

    @Test
    @DisplayName("operator conditions read caster and target player state")
    void operatorConditionsReadPlayerState() {
        SkillService service = service();
        Player player = mock(Player.class);
        when(player.isOp()).thenReturn(true);
        BehaviorContext targetContext = BehaviorContext.of(null, player, 1.0, -1, 0, "IDLE");
        assertTrue(registry.testCondition(service.bindCondition("target-is-op"), targetContext));
        assertTrue(registry.testCondition(service.bindCondition("target-is-operator"), targetContext));

        ModelInstance instance = mock(ModelInstance.class);
        when(instance.isValid()).thenReturn(true);
        when(instance.baseEntity()).thenReturn(Optional.of(player));
        BehaviorContext casterContext = BehaviorContext.of(instance, (LivingEntity) null,
                1.0, -1, 0, "IDLE");
        assertTrue(registry.testCondition(service.bindCondition("is-op"), casterContext));
        when(player.isOp()).thenReturn(false);
        assertFalse(registry.testCondition(service.bindCondition("is-op"), casterContext));
        assertFalse(registry.testCondition(service.bindCondition("target-is-op"), targetContext));
    }

    @Test
    @DisplayName("daylight follows the vanilla day cycle instead of rejecting daytime")
    void daylightUsesDaytimeSunHeight() {
        assertEquals(0.0, SkillCatalog.daylightLevel(0), 1e-9);
        assertEquals(1.0, SkillCatalog.daylightLevel(6_000), 1e-9);
        assertEquals(0.0, SkillCatalog.daylightLevel(12_000), 1e-9);
        assertEquals(0.0, SkillCatalog.daylightLevel(18_000), 1e-9);

        SkillService service = service();
        ModelInstance instance = mock(ModelInstance.class);
        Location location = mock(Location.class);
        World world = mock(World.class);
        when(instance.isValid()).thenReturn(true);
        when(instance.location()).thenReturn(location);
        when(location.getWorld()).thenReturn(world);
        when(world.getTime()).thenReturn(6_000L, 18_000L);
        BehaviorContext context = BehaviorContext.of(instance, (LivingEntity) null,
                1.0, -1, 0, "IDLE");
        String key = service.bindCondition("daylight 0.5");
        assertTrue(registry.testCondition(key, context));
        assertFalse(registry.testCondition(key, context));
    }

    @Test
    @DisplayName("official direct entity-state aliases bind to canonical conditions")
    void officialDirectEntityStateAliasesBind() {
        SkillService service = service();
        for (String alias : List.of("isOnGround", "isInWater", "isInLava", "isInAir",
                "isOnFire", "isBlocked", "standOn STONE", "daylight 0.5")) {
            String key = service.bindCondition(alias);
            assertNotNull(key, alias);
            assertTrue(registry.hasCondition(key), alias + " -> " + key);
        }
    }

    @Test
    @DisplayName("target entity state aliases bind to the shared state evaluators")
    void targetEntityStateAliasesBind() {
        SkillService service = service();
        for (String alias : List.of("target-is-undead", "target-is-creature", "target-is-baby",
                "target-is-child", "target-is-adult", "target-is-tamed", "target-is-sheared",
                "target-is-saddled", "target-is-powered", "target-is-charged")) {
            assertNotNull(service.bindCondition(alias), alias);
        }
    }

    @Test
    void parameterlessMovementAndEnvironmentConditionsRejectArguments() {
        SkillService service = service();
        for (String name : List.of("is-sneaking", "is-sprinting", "is-flying", "is-blocking",
                "is-gliding", "is-swimming", "in-water", "in-lava", "on-fire", "in-air",
                "target-is-sneaking", "target-is-sprinting", "target-is-flying", "target-is-blocking",
                "target-is-gliding", "target-is-swimming", "target-in-water", "target-in-lava",
                "target-on-fire", "target-in-air")) {
            assertNotNull(service.bindCondition(name), name);
            assertNull(service.bindCondition(name + " unknown=true"), name);
            assertNull(service.bindCondition(name + "{unknown=true}"), name);
        }
        Player player = mock(Player.class);
        when(player.isSneaking()).thenReturn(true);
        BehaviorContext targetContext = BehaviorContext.of(null, player, 1.0, -1, 0, "IDLE");
        assertTrue(registry.testCondition(service.bindCondition("target-is-sneaking"), targetContext));
        ModelInstance instance = mock(ModelInstance.class);
        LivingEntity caster = mock(LivingEntity.class);
        when(instance.isValid()).thenReturn(true);
        when(instance.baseEntity()).thenReturn(Optional.of(caster));
        when(caster.isInWater()).thenReturn(true);
        BehaviorContext casterContext = BehaviorContext.of(instance, (LivingEntity) null, 1.0, -1, 0, "IDLE");
        assertTrue(registry.testCondition(service.bindCondition("in-water"), casterContext));
    }

    @Test
    void parameterlessTimeAndWeatherAliasesRejectArguments() {
        SkillService service = service();
        for (String name : List.of("day", "daytime", "dawn", "dusk", "sunset", "morning", "noon",
                "midnight", "clear", "sun", "sunny", "rain", "storm", "thunder", "thunderstorm")) {
            assertNotNull(service.bindCondition(name), name);
            assertNull(service.bindCondition(name + "{unknown=true}"), name);
            assertNull(service.bindCondition(name + " unknown=true"), name);
        }
    }

    @Test
    void parameterlessVisibilityAndClimbingConditionsRejectArguments() {
        SkillService service = service();
        for (String name : List.of("is-climbing", "climbing", "isClimbing", "is-invisible", "invisible",
                "isInvisible", "is-glowing", "glowing", "isGlowing", "falling", "isFalling",
                "target-is-climbing", "targetIsClimbing", "target-is-invisible", "targetIsInvisible",
                "target-is-glowing", "targetIsGlowing", "target-is-frozen", "targetIsFrozen", "targetFrozen",
                "charged")) {
            assertNotNull(service.bindCondition(name), name);
            assertNull(service.bindCondition(name + "{unknown=true}"), name);
            assertNull(service.bindCondition(name + " unknown=true"), name);
        }
    }

    @Test
    @DisplayName("target entity state aliases evaluate against the target, not the caster")
    void targetEntityStateAliasesEvaluateTarget() {
        SkillService service = service();
        org.bukkit.entity.Zombie zombie = mock(org.bukkit.entity.Zombie.class);
        when(zombie.getType()).thenReturn(org.bukkit.entity.EntityType.ZOMBIE);
        BehaviorContext context = BehaviorContext.of(null, zombie, 1.0, -1, 0, "IDLE");

        assertTrue(registry.testCondition(service.bindCondition("target-is-undead"), context));
        assertTrue(registry.testCondition(service.bindCondition("target-is-creature"), context));
        assertFalse(registry.testCondition(service.bindCondition("target-is player"), context));

        org.bukkit.entity.Ageable baby = mock(org.bukkit.entity.Ageable.class);
        when(baby.isAdult()).thenReturn(false);
        BehaviorContext babyContext = BehaviorContext.of(null, baby, 1.0, -1, 0, "IDLE");
        assertTrue(registry.testCondition(service.bindCondition("target-is-baby"), babyContext));
        assertFalse(registry.testCondition(service.bindCondition("target-is-adult"), babyContext));

        ModelInstance casterInstance = mock(ModelInstance.class);
        org.bukkit.entity.Zombie caster = mock(org.bukkit.entity.Zombie.class);
        when(casterInstance.isValid()).thenReturn(true);
        when(casterInstance.baseEntity()).thenReturn(Optional.of(caster));
        BehaviorContext noTarget = BehaviorContext.of(casterInstance, (LivingEntity) null,
                1.0, -1, 0, "IDLE");
        assertFalse(registry.testCondition(service.bindCondition("target-is-undead"), noTarget),
                "target conditions must not fall back to the caster");
        assertFalse(registry.testCondition(service.bindCondition("target-is-baby"), noTarget),
                "target conditions must fail closed without a target");
    }

    @Test
    @DisplayName("target tame, shear, saddle and powered aliases evaluate target state")
    void targetEntityStateAliasesEvaluateBukkitState() {
        SkillService service = service();

        org.bukkit.entity.Wolf wolf = mock(org.bukkit.entity.Wolf.class);
        when(wolf.isTamed()).thenReturn(true);
        BehaviorContext wolfContext = BehaviorContext.of(null, wolf, 1.0, -1, 0, "IDLE");
        assertTrue(registry.testCondition(service.bindCondition("target-is-tamed"), wolfContext));

        org.bukkit.entity.Sheep sheep = mock(org.bukkit.entity.Sheep.class);
        when(sheep.isSheared()).thenReturn(true);
        BehaviorContext sheepContext = BehaviorContext.of(null, sheep, 1.0, -1, 0, "IDLE");
        assertTrue(registry.testCondition(service.bindCondition("target-is-sheared"), sheepContext));

        org.bukkit.entity.Horse horse = mock(org.bukkit.entity.Horse.class);
        org.bukkit.inventory.HorseInventory inventory = mock(org.bukkit.inventory.HorseInventory.class);
        org.bukkit.inventory.ItemStack saddle = mock(org.bukkit.inventory.ItemStack.class);
        org.bukkit.Material saddleType = mock(org.bukkit.Material.class);
        when(horse.getInventory()).thenReturn(inventory);
        when(inventory.getSaddle()).thenReturn(saddle);
        when(saddle.getType()).thenReturn(saddleType);
        when(saddleType.isAir()).thenReturn(false);
        BehaviorContext horseContext = BehaviorContext.of(null, horse, 1.0, -1, 0, "IDLE");
        assertTrue(registry.testCondition(service.bindCondition("target-is-saddled"), horseContext));

        org.bukkit.entity.Creeper creeper = mock(org.bukkit.entity.Creeper.class);
        when(creeper.isPowered()).thenReturn(true);
        BehaviorContext creeperContext = BehaviorContext.of(null, creeper, 1.0, -1, 0, "IDLE");
        assertTrue(registry.testCondition(service.bindCondition("target-is-powered"), creeperContext));
        assertTrue(registry.testCondition(service.bindCondition("target-is-charged"), creeperContext));
    }

    @Test
    @DisplayName("target player state conditions read Bukkit player flags")
    void targetPlayerStateConditionsReadFlags() {
        SkillService service = service();
        Player player = mock(Player.class);
        when(player.isSneaking()).thenReturn(true);
        when(player.isSprinting()).thenReturn(true);
        when(player.isFlying()).thenReturn(true);
        when(player.isBlocking()).thenReturn(true);
        BehaviorContext context = BehaviorContext.of(null, player, 1.0, -1, 0, "IDLE");

        assertTrue(registry.testCondition(service.bindCondition("target-is-sneaking"), context));
        assertTrue(registry.testCondition(service.bindCondition("target-is-sprinting"), context));
        assertTrue(registry.testCondition(service.bindCondition("target-is-flying"), context));
        assertTrue(registry.testCondition(service.bindCondition("target-is-blocking"), context));
    }

    @Test
    @DisplayName("target environmental aliases never fall back to caster state")
    void targetEnvironmentalAliasesDoNotUseCasterFallback() {
        SkillService service = service();
        ModelInstance instance = mock(ModelInstance.class);
        Player caster = mock(Player.class);
        when(instance.isValid()).thenReturn(true);
        when(instance.baseEntity()).thenReturn(Optional.of(caster));
        when(caster.isSneaking()).thenReturn(true);
        when(caster.isSprinting()).thenReturn(true);
        when(caster.isFlying()).thenReturn(true);
        when(caster.isBlocking()).thenReturn(true);
        when(caster.isGliding()).thenReturn(true);
        when(caster.isSwimming()).thenReturn(true);
        when(caster.getFireTicks()).thenReturn(20);
        when(caster.isOnGround()).thenReturn(false);

        BehaviorContext context = BehaviorContext.of(instance, (LivingEntity) null,
                1.0, -1, 0, "IDLE");
        for (String condition : List.of("target-is-sneaking", "target-is-sprinting",
                "target-is-flying", "target-is-blocking", "target-is-gliding",
                "target-is-swimming", "target-on-fire", "target-in-air",
                "target-in-water", "target-in-lava", "target-is-invisible",
                "target-is-climbing", "target-is-blocked")) {
            assertFalse(registry.testCondition(service.bindCondition(condition), context), condition);
        }
    }

    @Test
    @DisplayName("target environmental state aliases bind to runtime evaluators")
    void targetEnvironmentalStateAliasesBind() {
        SkillService service = service();
        for (String alias : List.of("target-on-fire", "target-in-air", "target-in-water", "target-in-lava")) {
            assertNotNull(service.bindCondition(alias), alias);
        }
    }

    @Test
    @DisplayName("target environmental conditions evaluate the target entity state")
    void targetEnvironmentalConditionsEvaluateState() {
        SkillService service = service();
        org.bukkit.entity.Zombie target = mock(org.bukkit.entity.Zombie.class);
        org.bukkit.Location location = mock(org.bukkit.Location.class);
        org.bukkit.World world = mock(org.bukkit.World.class);
        org.bukkit.block.Block block = mock(org.bukkit.block.Block.class);
        when(target.getFireTicks()).thenReturn(20);
        when(target.isOnGround()).thenReturn(false);
        when(target.isInWater()).thenReturn(true);
        when(target.isInLava()).thenReturn(false);
        when(target.getLocation()).thenReturn(location);
        when(location.getWorld()).thenReturn(world);
        when(location.getBlock()).thenReturn(block);
        when(block.getType()).thenReturn(org.bukkit.Material.WATER);
        BehaviorContext waterContext = BehaviorContext.of(null, target, 1.0, -1, 0, "IDLE");

        assertTrue(registry.testCondition(service.bindCondition("target-on-fire"), waterContext));
        assertTrue(registry.testCondition(service.bindCondition("target-in-air"), waterContext));
        assertTrue(registry.testCondition(service.bindCondition("target-in-water"), waterContext));
        assertFalse(registry.testCondition(service.bindCondition("target-in-lava"), waterContext));

        when(target.isInWater()).thenReturn(false);
        when(target.isInLava()).thenReturn(true);
        assertTrue(registry.testCondition(service.bindCondition("target-in-lava"), waterContext));
    }

    @Test
    @DisplayName("target invisible condition reads the target entity")
    void targetInvisibleConditionReadsTarget() {
        SkillService service = service();
        org.bukkit.entity.Zombie target = mock(org.bukkit.entity.Zombie.class);
        when(target.isInvisible()).thenReturn(true);
        BehaviorContext context = BehaviorContext.of(null, target, 1.0, -1, 0, "IDLE");
        assertTrue(registry.testCondition(service.bindCondition("target-is-invisible"), context));
    }

    @Test
    @DisplayName("target glowing condition reads the target entity")
    void targetGlowingConditionReadsTarget() {
        SkillService service = service();
        org.bukkit.entity.Zombie target = mock(org.bukkit.entity.Zombie.class);
        when(target.isGlowing()).thenReturn(true);
        BehaviorContext context = BehaviorContext.of(null, target, 1.0, -1, 0, "IDLE");

        assertTrue(registry.testCondition(service.bindCondition("target-is-glowing"), context));
        when(target.isGlowing()).thenReturn(false);
        assertFalse(registry.testCondition(service.bindCondition("targetIsGlowing"), context));
    }

    @Test
    @DisplayName("target dead and ground-state aliases bind to runtime conditions")
    void targetBasicStateAliasesBind() {
        SkillService service = service();
        for (String alias : List.of("target-is-dead", "target-is-on-ground", "target-is-airborne",
                "target-has-ai", "target-has-gravity", "target-is-silent", "target-is-valid")) {
            assertNotNull(service.bindCondition(alias), alias);
        }

        org.bukkit.entity.Zombie target = mock(org.bukkit.entity.Zombie.class);
        when(target.isOnGround()).thenReturn(false);
        when(target.hasAI()).thenReturn(true);
        when(target.hasGravity()).thenReturn(true);
        when(target.isSilent()).thenReturn(false);
        when(target.isValid()).thenReturn(true);
        BehaviorContext context = BehaviorContext.of(null, target, 1.0, -1, 0, "IDLE");
        assertFalse(registry.testCondition(service.bindCondition("target-is-dead"), context));
        assertTrue(registry.testCondition(service.bindCondition("target-is-airborne"), context));
        assertTrue(registry.testCondition(service.bindCondition("target-has-ai"), context));
        assertTrue(registry.testCondition(service.bindCondition("target-has-gravity"), context));
        assertFalse(registry.testCondition(service.bindCondition("target-is-silent"), context));
        assertTrue(registry.testCondition(service.bindCondition("target-is-valid"), context));
        BehaviorContext noTarget = BehaviorContext.of(null, (LivingEntity) null, 1.0, -1, 0, "IDLE");
        assertFalse(registry.testCondition(service.bindCondition("target-has-ai"), noTarget));
        assertFalse(registry.testCondition(service.bindCondition("target-has-gravity"), noTarget));
        assertFalse(registry.testCondition(service.bindCondition("target-is-silent"), noTarget));
        assertFalse(registry.testCondition(service.bindCondition("target-is-valid"), noTarget));

        when(target.isOnGround()).thenReturn(true);
        assertTrue(registry.testCondition(service.bindCondition("target-is-on-ground"), context));
        assertFalse(registry.testCondition(service.bindCondition("target-is-airborne"), context));
    }

    @Test
    @DisplayName("target climbing and blocked conditions read target state")
    void targetClimbingAndBlockedConditionsReadTarget() {
        SkillService service = service();
        org.bukkit.entity.Zombie target = mock(org.bukkit.entity.Zombie.class);
        org.bukkit.Location location = mock(org.bukkit.Location.class);
        org.bukkit.block.Block block = mock(org.bukkit.block.Block.class);
        when(target.isClimbing()).thenReturn(true);
        when(target.getLocation()).thenReturn(location);
        when(location.getBlock()).thenReturn(block);
        when(location.clone()).thenReturn(location);
        when(location.add(anyDouble(), anyDouble(), anyDouble())).thenReturn(location);
        when(block.getType()).thenReturn(org.bukkit.Material.STONE);
        BehaviorContext context = BehaviorContext.of(null, target, 1.0, -1, 0, "IDLE");

        assertTrue(registry.testCondition(service.bindCondition("target-is-climbing"), context));
        assertNotNull(service.bindCondition("target-is-blocked"));
    }

}
