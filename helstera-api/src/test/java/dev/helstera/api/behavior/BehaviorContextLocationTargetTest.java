package dev.helstera.api.behavior;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.util.Vector;
import dev.helstera.api.instance.ModelInstance;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class BehaviorContextLocationTargetTest {
    @Test
    void officialLocationTargetersResolveAliasesAndContextLocations() {
        World[] worldHolder = new World[1];
        worldHolder[0] = proxy(World.class, (method, args) -> method.equals("getSpawnLocation")
                ? new Location(worldHolder[0], 90, 70, -10) : null);
        World world = worldHolder[0];
        Location casterLocation = new Location(world, 10, 64, 20, 90, 0);
        Location spawnLocation = new Location(world, 3, 65, 7);
        LivingEntity caster = proxy(LivingEntity.class, (method, args) -> switch (method) {
            case "getLocation" -> casterLocation.clone();
            case "getEyeLocation" -> new Location(world, 10, 66, 20, 90, 0);
            default -> null;
        });
        ModelInstance instance = proxy(ModelInstance.class, (method, args) -> switch (method) {
            case "isValid" -> true;
            case "baseEntity" -> Optional.of(caster);
            case "location" -> casterLocation.clone();
            case "spawnLocation" -> Optional.of(spawnLocation.clone());
            default -> null;
        });
        Location origin = new Location(world, -20, 30, 40);
        BehaviorContext context = BehaviorContext.execution(instance, null, 1, -1, 0,
                "skill", null, null, java.util.List.of(), origin);

        for (String alias : java.util.List.of("@SelfLocation", "@casterLocation", "@bossLocation", "@mobLocation")) {
            assertEquals(casterLocation, Targeters.resolveLocation(alias, context).orElseThrow(), alias);
        }
        Location casterEyeLocation = new Location(world, 10, 66, 20, 90, 0);
        for (String alias : java.util.List.of("@SelfEyeLocation", "@eyeDirection", "@casterEyeLocation",
                "@bossEyeLocation", "@mobEyeLocation")) {
            assertEquals(casterEyeLocation, Targeters.resolveLocation(alias, context).orElseThrow(), alias);
            assertTrue(Targeters.validLocationTargeterSpec(alias), alias);
        }
        assertTrue(Targeters.resolveLocation("@SelfEyeLocation{unknown=true}", context).isEmpty());
        assertTrue(!Targeters.validLocationTargeterSpec("@SelfEyeLocation{unknown=true}"));
        assertEquals(origin, Targeters.resolveLocation("@source", context).orElseThrow());
        assertEquals(spawnLocation, Targeters.resolveLocation("@CasterSpawn", context).orElseThrow());
        assertEquals(new Location(world, 90, 70, -10),
                Targeters.resolveLocation("@SpawnLocation", context).orElseThrow());

        Location forward = Targeters.resolveLocation("@Forward{f=3}", context).orElseThrow();
        assertEquals(7, forward.getX(), 1e-8);
        assertEquals(20, forward.getZ(), 1e-8);
        Location eyeForward = Targeters.resolveLocation("@Forward{amount=2;uel=true;lockpitch=true}", context)
                .orElseThrow();
        assertEquals(8, eyeForward.getX(), 1e-8);
        assertEquals(66, eyeForward.getY(), 1e-8);

        assertTrue(Targeters.resolveLocation("@Forward{f=2;forward=3}", context).isEmpty());
        assertTrue(Targeters.resolveLocation("@Forward{f=2;unknown=true}", context).isEmpty());
        assertTrue(Targeters.resolveLocation("@Forward{f=NaN}", context).isEmpty());
        assertTrue(Targeters.validLocationTargeterSpec("@Forward{f=2;uel=true}"));
        assertTrue(Targeters.validLocationTargeterSpec("@casterLocation"));
        assertTrue(!Targeters.validLocationTargeterSpec("@Forward{f=2;unknown=true}"));
    }

    @Test
    void trackedLocationTargeterReadsOnlyTheSkillTreeLocation() {
        World world = proxy(World.class, (method, args) -> null);
        SkillTreeContext tree = new SkillTreeContext(null, null);
        BehaviorContext context = BehaviorContext.continueExecution(null, (LivingEntity) null,
                1, -1, 0, "skill", null, null, java.util.List.of(), null, tree);

        assertTrue(Targeters.validLocationTargeterSpec("@TrackedLocation"));
        assertFalse(Targeters.validLocationTargeterSpec("@TrackedLocation{unknown=true}"));
        assertTrue(Targeters.resolveLocation("@TrackedLocation", context).isEmpty());

        Location tracked = new Location(world, 8, 9, 10);
        tree.setLocation(SkillTreeContext.MYTHIC_TRACKED_LOCATION, tracked);
        assertEquals(tracked, Targeters.resolveLocation("@TrackedLocation", context).orElseThrow());
        assertTrue(Targeters.resolveLocation("@TrackedLocation{unknown=true}", context).isEmpty());

        tree.setLocation(SkillTreeContext.MYTHIC_TRACKED_LOCATION, new Location(null, 8, 9, 10));
        assertTrue(Targeters.resolveLocation("@TrackedLocation", context).isEmpty());
    }

    @Test
    void selfEyeLocationFailsClosedWithoutLivingCaster() {
        World world = proxy(World.class, (method, args) -> null);
        org.bukkit.entity.Entity caster = proxy(org.bukkit.entity.Entity.class, (method, args) -> null);
        ModelInstance instance = proxy(ModelInstance.class, (method, args) -> switch (method) {
            case "isValid" -> true;
            case "baseEntity" -> Optional.of(caster);
            default -> null;
        });
        BehaviorContext context = BehaviorContext.execution(instance, null, 1, -1, 0,
                "skill", null, null, java.util.List.of(), new Location(world, 0, 0, 0));
        assertTrue(Targeters.resolveLocation("@SelfEyeLocation", context).isEmpty());
        assertTrue(Targeters.resolveLocation("@SelfEyeLocation", BehaviorContext.execution(
                null, null, 1, -1, 0, "skill", null, null, java.util.List.of(), null)).isEmpty());
    }

    @Test
    void targetLocationAliasesAndPredictionUseTheActiveTarget() {
        World world = proxy(World.class, (method, args) -> null);
        Location targetLocation = new Location(world, 4, 5, 6);
        LivingEntity target = proxy(LivingEntity.class, (method, args) -> switch (method) {
            case "getLocation" -> targetLocation.clone();
            case "getVelocity" -> new Vector(1, 0, 0);
            default -> null;
        });
        BehaviorContext context = BehaviorContext.continueExecution(null, target, 1, 4, 0,
                "skill", null, null, java.util.List.of(target), null, null);

        for (String alias : java.util.List.of("@TargetLocation", "@targetloc", "@TL")) {
            assertEquals(targetLocation, Targeters.resolveLocation(alias, context).orElseThrow(), alias);
        }
        Location predicted = Targeters.resolveLocation("@TargetPredictedLoc{ticks=10;max=2}", context)
                .orElseThrow();
        assertEquals(6, predicted.getX());
        assertEquals(5, predicted.getY());
        assertEquals(6, predicted.getZ());
        assertTrue(Targeters.resolveLocation("@TPL{ticks=-1}", context).isEmpty());
        assertTrue(Targeters.resolveLocation("@TargetLocation{maxdistance=NaN}", context).isEmpty());
        assertTrue(Targeters.resolveLocation("@TargetLocation{max=4;d=8}", context).isEmpty());
        assertTrue(Targeters.validLocationTargeterSpec("@TargetPredictedLocation{ticks=10;max=4}"));
        assertTrue(!Targeters.validLocationTargeterSpec("@TargetPredictedLocation{ticks=-1}"));
    }

    @Test
    void projectileForwardUsesProjectileVelocityAndTriggerLocationUsesTriggerEntity() {
        World world = proxy(World.class, (method, args) -> null);
        Location projectileLocation = new Location(world, 1, 2, 3);
        Projectile projectile = proxy(Projectile.class, (method, args) -> switch (method) {
            case "getLocation" -> projectileLocation.clone();
            case "getVelocity" -> new Vector(0, 0, 2);
            default -> null;
        });
        BehaviorContext context = BehaviorContext.execution(null, null, 1, -1, 0,
                "skill", null, projectile, java.util.List.of(), new Location(world, 0, 0, 0));

        Location forward = Targeters.resolveLocation("@ProjectileForward{f=3}", context).orElseThrow();
        assertEquals(1, forward.getX());
        assertEquals(2, forward.getY());
        assertEquals(6, forward.getZ());
        assertEquals(projectileLocation, Targeters.resolveLocation("@TriggerLocation", context).orElseThrow());
        assertTrue(Targeters.resolveLocation("@ProjectileForward", BehaviorContext.execution(
                null, null, 1, -1, 0, "skill", null, null, java.util.List.of(), null)).isEmpty());
        Projectile stationary = proxy(Projectile.class, (method, args) -> switch (method) {
            case "getLocation" -> projectileLocation.clone();
            case "getVelocity" -> new Vector();
            default -> null;
        });
        BehaviorContext stationaryContext = BehaviorContext.execution(null, null, 1, -1, 0,
                "skill", null, stationary, java.util.List.of(), null);
        assertEquals(projectileLocation,
                Targeters.resolveLocation("@ProjectileForward{f=0}", stationaryContext).orElseThrow());
        assertTrue(Targeters.resolveLocation("@ProjectileForward", stationaryContext).isEmpty());
    }

    @Test
    void locationTargeterRejectsMalformedArgumentsAndNonFiniteCoordinates() {
        World world = (World) java.lang.reflect.Proxy.newProxyInstance(
                World.class.getClassLoader(), new Class<?>[]{World.class}, (proxy, method, args) -> null);
        BehaviorContext context = BehaviorContext.execution(null, null, 1, -1, 0,
                "skill", null, null, java.util.List.of(), new Location(world, 0, 0, 0));

        Location location = Targeters.resolveLocation("@location{x=1e2,y=-2.5,z=3}", context).orElseThrow();
        assertSame(world, location.getWorld());
        assertEquals(100, location.getX());
        assertEquals(-2.5, location.getY());
        assertEquals(3, location.getZ());
        Location defaults = Targeters.resolveLocation("@l{y=7}", context).orElseThrow();
        assertEquals(0, defaults.getX());
        assertEquals(7, defaults.getY());
        assertEquals(0, defaults.getZ());
        Location tuple = Targeters.resolveLocation("@loc{loc=1,2,3,90,-30}", context).orElseThrow();
        assertEquals(1, tuple.getX());
        assertEquals(90, tuple.getYaw());
        assertEquals(-30, tuple.getPitch());
        assertEquals(0, Targeters.resolveLocation("@location", context).orElseThrow().getX());
        assertTrue(Targeters.validLocationSpec("@loc{l=1,2,3,90,-30;w=other}"));

        for (String spec : java.util.List.of(
                "@location{x=NaN;y=2;z=3}", "@location{x=1;y=Infinity;z=3}",
                "@location{x=1;y=2;z=1e999}", "@location{x=1;X=4;y=2;z=3}",
                "@location{x=1;y=2;z=3;extra=4}", "@location{x=1;y=2;z=3;extra}",
                "@location{x=1;y=2;z=3}tail", "@location{x=1;y=2;z=3",
                "@location{x=1;y=2;z=3;world=}", "@loc{l=1,2,3,4,5;x=7}",
                "@loc{l=1,2,3,4}", "@loc{l=1,2,3,4,NaN}",
                "@location{yaw=1e100}", "@location{world=one;w=two}",
                "@location{world={other}}", "@location{world=other}}")) {
            assertTrue(Targeters.resolveLocation(spec, context).isEmpty(), spec);
            assertTrue(!Targeters.validLocationSpec(spec), spec);
        }
    }

    @Test
    void locationTargetIsIndependentFromEntityTargetsAndDefensivelyCopied() {
        World world = (World) java.lang.reflect.Proxy.newProxyInstance(
                World.class.getClassLoader(), new Class<?>[]{World.class}, (proxy, method, args) -> null);
        Location origin = new Location(world, 1, 2, 3);
        BehaviorContext base = BehaviorContext.execution(null, null, 1, -1, 0,
                "skill", null, null, java.util.List.of(), origin);
        Location pin = new Location(world, 8, 9, 10);

        BehaviorContext targeted = base.withLocationTarget(pin);
        pin.setX(99);
        Location read = targeted.targetLocation().orElseThrow();
        assertEquals(8, read.getX());
        read.setX(100);
        assertEquals(8, targeted.targetLocation().orElseThrow().getX());
        assertTrue(targeted.targetEntity().isEmpty());
        assertTrue(targeted.targets().isEmpty());
        Location copiedOrigin = targeted.origin().orElseThrow();
        assertSame(world, copiedOrigin.getWorld());
        assertEquals(origin.getX(), copiedOrigin.getX());
    }

    @Test
    void executionAudienceIsDefensivelyCopiedAndPreservedByLocationTargeting() {
        UUID id = UUID.randomUUID();
        Player player = proxy(Player.class, (method, args) -> method.equals("getUniqueId") ? id : null);
        BehaviorContext base = BehaviorContext.execution(null, player, 1, -1, 0,
                "skill", null, player, java.util.List.of(player), null)
                .withExecutionAudience(java.util.List.of(player));
        BehaviorContext targeted = base.withLocationTarget(new Location(
                (World) java.lang.reflect.Proxy.newProxyInstance(
                        World.class.getClassLoader(), new Class<?>[]{World.class}, (proxy, method, args) -> null),
                8, 9, 10));
        assertEquals(java.util.Set.of(id), targeted.executionAudience().orElseThrow());
        assertTrue(targeted.withExecutionAudienceFrom(BehaviorContext.of(null, null, 1, -1, 0))
                .executionAudience().isPresent());
        BehaviorContext nested = targeted.withNarrowedExecutionAudience(java.util.Set.of());
        nested.setExecutionAudience(java.util.Set.of(id));
        assertEquals(java.util.Set.of(id), base.executionAudience().orElseThrow());
        base.setExecutionAudience(java.util.Set.of());
        assertTrue(targeted.executionAudience().orElseThrow().isEmpty());
        assertEquals(java.util.Set.of(id), nested.executionAudience().orElseThrow());
        assertFalse(BehaviorContext.execution(null, player, 1, -1, 0,
                "skill", null, player, java.util.List.of(player), null)
                .executionAudience().isPresent());
    }

    private static <T> T proxy(Class<T> type, Invocation invocation) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, args) -> {
                    if (method.getName().equals("equals")) return proxy == args[0];
                    if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
                    return invocation.invoke(method.getName(), args);
                }));
    }

    @FunctionalInterface
    private interface Invocation {
        Object invoke(String method, Object[] args);
    }
}
