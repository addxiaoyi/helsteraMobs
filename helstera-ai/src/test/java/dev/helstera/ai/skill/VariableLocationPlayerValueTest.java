package dev.helstera.ai.skill;

import dev.helstera.ai.BehaviorRegistryImpl;
import dev.helstera.api.behavior.BehaviorContext;
import dev.helstera.api.behavior.VariableLocations;
import dev.helstera.api.instance.ModelInstance;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.BlockIterator;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedConstruction;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class VariableLocationPlayerValueTest {
    private static final List<String> SCOPES = List.of("skill", "caster", "target", "world", "global");
    private final BehaviorRegistryImpl registry = new BehaviorRegistryImpl();
    private final SkillService skills = new SkillService(registry, null);
    private World world;
    private Player caster;
    private LivingEntity aimed;
    private LivingEntity recipient;
    private LivingEntity trigger;
    private Location origin;
    private Location eye;
    private ModelInstance model;
    private BehaviorContext context;
    private Material air;
    private Material stone;
    private Material glass;

    @BeforeEach
    void setUp() {
        SkillExtras.resetRuntimeHooks();
        VariableLocations.clear();
        world = mock(World.class);
        when(world.getUID()).thenReturn(UUID.randomUUID());
        when(world.getName()).thenReturn("player-target-world");
        caster = entity(Player.class, new Location(world, .5, 70, .5, 0, 0));
        eye = new Location(world, .5, 71.62, .5, 0, 0);
        when(caster.getEyeLocation()).thenAnswer(invocation -> eye.clone());
        aimed = entity(LivingEntity.class, new Location(world, .5, 70, 5.5, 65, -12));
        recipient = entity(LivingEntity.class, new Location(world, 20, 72, 23, -80, 30));
        trigger = entity(LivingEntity.class, new Location(world, 30, 73, 33, -90, 35));
        origin = new Location(world, 40, 74, 43, -100, 40);
        when(caster.getNearbyEntities(32, 32, 32)).thenReturn(List.of(aimed));
        model = mock(ModelInstance.class);
        when(model.isValid()).thenReturn(true);
        int instanceId = caster.getUniqueId().hashCode();
        when(model.instanceId()).thenReturn(instanceId);
        when(model.baseEntity()).thenReturn(Optional.of(caster));
        Location casterLocation = caster.getLocation();
        when(model.location()).thenReturn(casterLocation);
        context = BehaviorContext.execution(model, null, 1, 4, 0, "player-location", null,
                trigger, List.of(recipient), origin).withTargetEntity(recipient);
        // Material shape flags delegate to Paper's registry; isolate those server-owned facts here.
        air = material(true, false, false);
        stone = material(false, true, true);
        glass = material(false, true, false);
    }

    @AfterEach
    void tearDown() {
        SkillCatalog.taskScheduler(null);
        SkillExtras.resetRuntimeHooks();
        VariableLocations.clear();
    }

    @ParameterizedTest
    @ValueSource(strings = {"skill", "caster", "target", "world", "global"})
    void entityAimProvidesValueWhileTheOuterRecipientChoosesRegistry(String scope) {
        for (String candidate : SCOPES) run("set-variable var=Point scope=" + candidate + " value=7");
        try (var ignored = traversal(block(air, 0, 71, 5))) {
            for (String selector : List.of("@target", "@T")) {
                run("setvariablelocation{var=" + scope + ".Point;value=" + selector + ";save=true}");
                for (String candidate : SCOPES) {
                    assertEquals(candidate.equals(scope) ? Optional.of(aimed.getLocation()) : Optional.empty(),
                            location(candidate, "Point"), selector + " " + candidate);
                    assertEquals(candidate.equals(scope) ? null : 7, scalar(candidate, "Point"));
                }
            }
        }
        assertTrue(SkillExtras.scopedVariableSnapshot("entity:" + aimed.getUniqueId()).locations().isEmpty());
        assertTrue(SkillExtras.scopedVariableSnapshot("entity:" + trigger.getUniqueId()).locations().isEmpty());
        assertEquals("player:" + caster.getUniqueId(), VariableLocations.scopeKey("caster", context));
    }

    @Test
    void outerEntityAndLocationTargetsDoNotReplacePlayerAim() {
        try (var ignored = traversal(block(air, 0, 71, 5))) {
            run("setvariablelocation{var=target.Point;value=@target} @trigger");
            assertEquals(aimed.getLocation(), VariableLocations.get(trigger, "Point").orElseThrow());
            assertTrue(VariableLocations.get(recipient, "Point").isEmpty());
            var located = context.withLocationTarget(new Location(world, 55, 91, 15, 45, -20));
            for (String scope : List.of("skill", "caster", "world", "global")) {
                run("setvariablelocation{var=" + scope + ".Point;value=@target}", located);
                assertEquals(aimed.getLocation(), location(scope, "Point").orElseThrow());
            }
            run("set-variable var=Point scope=target value=17 duration=1200");
            var before = snapshots();
            run("setvariablelocation{var=target.Point;value=@target}", located);
            assertEquals(before, snapshots());
        }
    }

    @Test
    void missingPlayerEntityAimUsesOriginRatherThanRecipientOrTrigger() {
        when(caster.getNearbyEntities(32, 32, 32)).thenReturn(List.of());
        try (var ignored = traversal(block(air, 0, 71, 5))) {
            for (String scope : SCOPES) {
                run("set-variable var=Point scope=" + scope + " value=17");
                run("setvariablelocation{var=" + scope + ".Point;value=@target;save=true}");
                assertEquals(origin, location(scope, "Point").orElseThrow());
                assertNull(scalar(scope, "Point"));
            }
        }
    }

    @Test
    void entitySelectionUsesNearbyIterationOrderWithinTheSameExpandedBlock() {
        LivingEntity farther = entity(LivingEntity.class, new Location(world, .5, 70, 6.7));
        LivingEntity nearer = entity(LivingEntity.class, new Location(world, .5, 70, 5.2));
        Entity item = entity(Entity.class, new Location(world, .5, 70, 5.0));
        when(caster.getNearbyEntities(32, 32, 32)).thenReturn(List.of(item, farther, nearer));
        try (var ignored = traversal(block(air, 0, 71, 5))) {
            assertSame(farther, MythicPlayerTargeting.entity(caster));
        }
    }

    @Test
    void entityBoundsAreInclusiveAndUseFeetRatherThanBoundingBoxIntersections() {
        double[][] included = {{-.75, 70, 4.25}, {1.75, 73.5, 6.75}};
        try (var ignored = traversal(block(air, 0, 71, 5))) {
            for (double[] point : included) {
                LivingEntity candidate = entity(LivingEntity.class, new Location(world, point[0], point[1], point[2]));
                when(caster.getNearbyEntities(32, 32, 32)).thenReturn(List.of(candidate));
                assertSame(candidate, MythicPlayerTargeting.entity(caster));
            }
            for (double[] point : List.of(new double[]{-.751, 70, 5}, new double[]{1.751, 70, 5},
                    new double[]{0, 69.999, 5}, new double[]{0, 73.501, 5},
                    new double[]{0, 70, 4.249}, new double[]{0, 70, 6.751})) {
                LivingEntity outside = entity(LivingEntity.class, new Location(world, point[0], point[1], point[2]));
                when(caster.getNearbyEntities(32, 32, 32)).thenReturn(List.of(outside));
                assertNull(MythicPlayerTargeting.entity(caster));
            }
        }
    }

    @Test
    void entitySelectionSkipsCreativePlayersButIncludesSpectators() {
        Player creative = entity(Player.class, new Location(world, .5, 70, 5.4));
        Player spectator = entity(Player.class, new Location(world, .5, 70, 5.5));
        when(creative.getGameMode()).thenReturn(GameMode.CREATIVE);
        when(spectator.getGameMode()).thenReturn(GameMode.SPECTATOR);
        when(caster.getNearbyEntities(32, 32, 32)).thenReturn(List.of(creative, spectator, aimed));
        try (var ignored = traversal(block(air, 0, 71, 5))) {
            assertSame(spectator, MythicPlayerTargeting.entity(caster));
        }
    }

    @Test
    void entitySelectionDoesNotAddAnUnspecifiedDeadOrInvalidFilter() {
        when(aimed.isValid()).thenReturn(false);
        when(aimed.isDead()).thenReturn(true);
        try (var ignored = traversal(block(air, 0, 71, 5))) {
            assertSame(aimed, MythicPlayerTargeting.entity(caster));
        }
    }

    @Test
    void solidOrOccludingBlocksStopEntitySelectionBeforeCandidatesInThatBlock() {
        for (Material opaque : List.of(stone, glass, material(false, false, true))) {
            try (var ignored = traversal(block(opaque, 0, 71, 5), block(air, 0, 71, 6))) {
                assertNull(MythicPlayerTargeting.entity(caster));
            }
        }
        try (var ignored = traversal(block(Material.BARRIER, 0, 71, 5))) {
            assertSame(aimed, MythicPlayerTargeting.entity(caster));
        }
    }

    @Test
    void blockAimUsesShapeIntersectionAndResetsYawPitchForEveryLocationAlias() {
        Block hit = block(stone, 0, 71, 5);
        when(hit.rayTrace(any(Location.class), any(Vector.class), eq(64.0), eq(FluidCollisionMode.ALWAYS)))
                .thenReturn(new RayTraceResult(new Vector(.5, 71.62, 5.0)));
        try (var ignored = traversal(hit)) {
            for (String selector : List.of("@targetlocation", "@targetLoc", "@TL")) {
                run("setvariablelocation{var=target.Point;value=" + selector + "}");
                assertEquals(new Location(world, .5, 71.62, 5), location("target", "Point").orElseThrow());
            }
        }
        verify(caster, never()).getNearbyEntities(anyDouble(), anyDouble(), anyDouble());
    }

    @Test
    void glassAndOtherTransparentShapesAreSkippedForBlockAim() {
        Block glassBlock = block(glass, 0, 71, 3);
        Block transparentSolid = block(material(true, true, true), 0, 71, 4);
        Block nonSolid = block(material(false, false, true), 0, 71, 5);
        Block empty = block(stone, 0, 71, 6);
        when(empty.isEmpty()).thenReturn(true);
        Block stoneBlock = block(stone, 0, 71, 8);
        when(stoneBlock.rayTrace(any(), any(), eq(64.0), eq(FluidCollisionMode.ALWAYS)))
                .thenReturn(new RayTraceResult(new Vector(.5, 71.62, 8)));
        try (var ignored = traversal(glassBlock, transparentSolid, nonSolid, empty, stoneBlock)) {
            assertEquals(new Location(world, .5, 71.62, 8), MythicPlayerTargeting.location(caster));
        }
        for (Block skipped : List.of(glassBlock, transparentSolid, nonSolid, empty)) {
            verify(skipped, never()).rayTrace(any(), any(), anyDouble(), any());
        }
    }

    @Test
    void blockAimContinuesAfterMissedShapeAndOtherwiseUsesSixtyFourBlockEndpoint() {
        eye = new Location(world, 1, 72, 3, 30, -25);
        Block miss = block(stone, 0, 71, 5);
        try (var ignored = traversal(miss)) {
            run("setvariablelocation{var=skill.Point;value=@targetlocation}");
            Vector endpoint = eye.toVector().add(eye.getDirection().multiply(64));
            Location result = location("skill", "Point").orElseThrow();
            assertEquals(endpoint.getX(), result.getX(), 1e-10);
            assertEquals(endpoint.getY(), result.getY(), 1e-10);
            assertEquals(endpoint.getZ(), result.getZ(), 1e-10);
            assertEquals(0, result.getYaw());
            assertEquals(0, result.getPitch());
            assertEquals(world, result.getWorld());
        }
        Block later = block(stone, 0, 71, 8);
        when(later.rayTrace(any(), any(), eq(64.0), eq(FluidCollisionMode.ALWAYS)))
                .thenReturn(new RayTraceResult(new Vector(.5, 71.62, 8)));
        try (var ignored = traversal(miss, later)) {
            assertEquals(new Location(world, .5, 71.62, 8), MythicPlayerTargeting.location(caster));
        }
    }

    @Test
    void nullHitPositionFallsBackToOriginWithoutBorrowingTheRecipient() {
        Block hit = block(stone, 0, 71, 5);
        RayTraceResult unavailablePosition = mock(RayTraceResult.class);
        when(hit.rayTrace(any(), any(), eq(64.0), eq(FluidCollisionMode.ALWAYS)))
                .thenReturn(unavailablePosition);
        try (var ignored = traversal(hit)) {
            run("setvariablelocation{var=target.Point;value=@targetlocation}");
            assertEquals(origin, location("target", "Point").orElseThrow());
        }
    }

    @Test
    void delayedRepeatsReadNewPlayerAimAndKeepTheReceivingRegistry() {
        var pending = new ArrayDeque<Runnable>();
        var delays = new ArrayList<Long>();
        SkillCatalog.taskScheduler((task, delay) -> {
            pending.add(task); delays.add(delay); return true;
        });
        try (var ignored = traversal(block(air, 0, 71, 5))) {
            run("setvariablelocation{var=target.Point;value=@target;delay=1;repeat=1;repeatinterval=2}");
            assertTrue(location("target", "Point").isEmpty());
            pending.remove().run();
            assertEquals(aimed.getLocation(), location("target", "Point").orElseThrow());
            when(caster.getNearbyEntities(32, 32, 32)).thenReturn(List.of());
            pending.remove().run();
            assertEquals(origin, location("target", "Point").orElseThrow());
        }
        assertEquals(List.of(1L, 2L), delays);
        assertTrue(pending.isEmpty());
        assertSame(recipient, context.targetEntity().orElseThrow());
    }

    @Test
    void terminalPlayerContextStillPreservesStateWithoutInspectingAim() {
        run("set-variable var=Point scope=skill value=17 duration=1200");
        var terminal = new TerminalSkillContext(model, -120);
        var terminalContext = BehaviorContext.continueExecution(terminal, recipient, 1, 4, 0,
                "on-despawn", null, trigger, List.of(recipient), origin, context.skillTree().orElseThrow());
        var before = snapshots();
        try {
            for (String selector : List.of("@target", "@targetlocation")) {
                run("setvariablelocation{var=skill.Point;value=" + selector + ";duration=20}", terminalContext);
                assertEquals(before, snapshots());
            }
            verify(caster, never()).getNearbyEntities(anyDouble(), anyDouble(), anyDouble());
            verify(caster, never()).getEyeLocation();
        } finally {
            terminal.close();
        }
    }

    @Test
    void canonicalNoValueKeepsOuterRecipientWithoutInspectingPlayerAim() {
        run("variable-location-set var=Point scope=target");
        assertEquals(recipient.getLocation(), location("target", "Point").orElseThrow());
        Location outer = new Location(world, 81, 82, 83, 14, -15);
        run("variable-location-set var=Point scope=skill", context.withLocationTarget(outer));
        assertEquals(outer, location("skill", "Point").orElseThrow());
        verify(caster, never()).getNearbyEntities(anyDouble(), anyDouble(), anyDouble());
        verify(caster, never()).getEyeLocation();
    }

    private MockedConstruction<BlockIterator> traversal(Block... path) {
        return mockConstruction(BlockIterator.class, (iterator, construction) -> {
            var blocks = List.of(path).iterator();
            when(iterator.hasNext()).thenAnswer(invocation -> blocks.hasNext());
            when(iterator.next()).thenAnswer(invocation -> blocks.next());
            if (construction.arguments().getFirst() instanceof Player) {
                assertEquals(List.of(caster, 32), construction.arguments());
            } else {
                assertEquals(5, construction.arguments().size());
                assertEquals(world, construction.arguments().get(0));
                assertEquals(64, construction.arguments().get(4));
            }
        });
    }

    private Block block(Material material, int x, int y, int z) {
        Block block = mock(Block.class);
        when(block.getType()).thenReturn(material);
        when(block.getX()).thenReturn(x);
        when(block.getY()).thenReturn(y);
        when(block.getZ()).thenReturn(z);
        return block;
    }

    @SuppressWarnings("deprecation")
    private static Material material(boolean transparent, boolean solid, boolean occluding) {
        Material material = mock(Material.class);
        when(material.isTransparent()).thenReturn(transparent);
        when(material.isSolid()).thenReturn(solid);
        when(material.isOccluding()).thenReturn(occluding);
        return material;
    }

    private void run(String source) { run(source, context); }
    private void run(String source, BehaviorContext execution) {
        String bound = skills.bindAction(source);
        assertNotNull(bound, source + ": " + skills.warnings());
        assertTrue(registry.runAction(bound, execution), source);
    }
    private Optional<Location> location(String scope, String key) {
        return scope.equals("skill") ? context.skillTree().orElseThrow().location(key)
                : VariableLocations.get(scope, key, context);
    }
    private Object scalar(String scope, String key) {
        return scope.equals("skill") ? context.skillTree().orElseThrow().variables().get(key)
                : SkillExtras.scopedVariableSnapshot(VariableLocations.scopeKey(scope, context)).variables().get(key);
    }
    private Map<String, Object> snapshots() {
        var result = new LinkedHashMap<String, Object>();
        result.put("skill", context.skillTree().orElseThrow().snapshot());
        for (String key : List.of("player:" + caster.getUniqueId(), "entity:" + aimed.getUniqueId(),
                "entity:" + recipient.getUniqueId(), "entity:" + trigger.getUniqueId(),
                "world:" + world.getUID(), "global")) {
            result.put(key, SkillExtras.scopedVariableSnapshot(key));
        }
        return result;
    }
    private static <T extends Entity> T entity(Class<T> type, Location point) {
        T entity = mock(type);
        when(entity.getUniqueId()).thenReturn(UUID.randomUUID());
        when(entity.getWorld()).thenReturn(point.getWorld());
        when(entity.getLocation()).thenReturn(point);
        when(entity.isValid()).thenReturn(true);
        return entity;
    }
}
