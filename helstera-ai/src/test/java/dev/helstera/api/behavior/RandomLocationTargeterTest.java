package dev.helstera.api.behavior;

import dev.helstera.ai.BehaviorRegistryImpl;
import dev.helstera.ai.skill.SkillService;
import dev.helstera.api.instance.ModelInstance;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.DoubleSupplier;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RandomLocationTargeterTest {
    private final World world = mock(World.class);
    private final Entity caster = mock(Entity.class);
    private final ModelInstance instance = mock(ModelInstance.class);
    private final Location origin = new Location(world, .25, 100.25, .25, 70, 25);
    private final Location casterLocation = new Location(world, 40.25, 120.25, .25, 90, 45);
    private final Material air = mock(Material.class), stone = mock(Material.class);
    private Predicate<Location> solid = point -> false;
    private final List<Location> reads = new ArrayList<>();

    RandomLocationTargeterTest() {
        when(air.name()).thenReturn("AIR"); when(stone.name()).thenReturn("STONE");
        when(stone.isSolid()).thenReturn(true);
        when(world.getBlockAt(any(Location.class))).thenAnswer(call -> {
            Location location = call.getArgument(0); reads.add(location.clone());
            Block block = mock(Block.class); when(block.getType()).thenReturn(solid.test(location) ? stone : air);
            return block;
        });
        when(instance.isValid()).thenReturn(true);
        when(instance.instanceId()).thenReturn(71);
        when(instance.baseEntity()).thenReturn(Optional.of(caster));
        when(instance.location()).thenReturn(casterLocation);
        when(caster.getLocation()).thenAnswer(ignored -> casterLocation.clone());
    }

    private BehaviorContext context() {
        return BehaviorContext.continueExecution(instance, (LivingEntity) null, 1, -1, 0, "random", null,
                null, List.of(), origin, null);
    }

    private List<Location> select(String spec, BehaviorContext context, DoubleSupplier random) {
        return RandomLocationTargeter.select(spec, context, UnaryOperator.identity(), random);
    }

    private static DoubleSupplier sequence(double... values) {
        AtomicInteger index = new AtomicInteger();
        return () -> values[index.getAndIncrement() % values.length];
    }

    @Test void scopeAndVerticalRulesPreserveSamplingOrderAndResetOrientation() {
        assertEquals(List.of(new Location(world, -4.75, 100.25, 2.75)),
                select("@RLO{a=1}", context(), sequence(0, .75, .125)));
        assertEquals(List.of(new Location(world, 35.25, 119.5, 2.75)),
                select("@RLNC{a=1}", context(), sequence(0, .75, .125)));
        assertEquals(List.of(new Location(world, 35.25, 120.25, 2.75)),
                select("@RLNT{a=1}", context().withEntityTargets(List.of(caster)), sequence(0, .75, .125)));
        verify(world, never()).getHighestBlockYAt(any(Location.class));
    }

    @Test void defaultsAndAliasPriorityUseSquareRatherThanDiscSampling() {
        var random = new Random(719);
        var points = select("@RLO", context(), random::nextDouble);
        assertEquals(5, points.size());
        assertTrue(points.stream().allMatch(p -> p.getY() == origin.getY() && Math.abs(p.getX() - origin.getX()) <= 5
                && Math.abs(p.getZ() - origin.getZ()) <= 5));
        var corner = select("@RLO{amount=1;a=3;radius=2;r=9;maxradius=8;maxr=7}", context(), () -> 0);
        assertEquals(List.of(new Location(world, -1.75, 100.25, -1.75)), corner);
        assertTrue(corner.getFirst().distanceSquared(new Location(world, .25,100.25,.25)) > 4);
    }

    @ParameterizedTest @ValueSource(strings = {"0", "-1", "-2147483648"})
    void nonPositiveRadiusBecomesOne(String radius) {
        assertEquals(List.of(new Location(world, -.75,100.25,-.75)),
                select("@RLO{a=1;r="+radius+"}",context(), () -> 0));
    }

    @ParameterizedTest @ValueSource(strings = {"bad", "\"2\"", "<caster.var.radius>", "2147483648"})
    void staticAndPublicPlaceholderRadiusHaveDifferentFallbacks(String radius) {
        assertEquals(-4.75, select("@RLO{a=1;r="+radius+"}",context(), () -> 0).getFirst().getX());
        assertEquals(-.75, select("@RLNT{a=1;r="+radius+"}",context().withLocationTarget(origin), () -> 0).getFirst().getX());
        assertEquals(1, select("@RLNT{a="+radius+"}",context().withLocationTarget(origin), () -> .5).size());
    }

    @Test void placeholderRangesAndStaticRangesRemainDistinct() {
        assertEquals(-4.75, select("@RLO{a=2to2;r=2to2}",context(), () -> 0).getFirst().getX());
        assertEquals(List.of(new Location(world,-1.75,100.25,-1.75),new Location(world,-1.75,100.25,-1.75)),
                select("@RLNT{a=2to2;r=2to2}",context().withLocationTarget(origin), () -> 0));
    }

    @Test void duplicatePointsConsumeSlotsButTargetsPreserveMultiplicity() {
        AtomicInteger draws = new AtomicInteger(); DoubleSupplier random = () -> {draws.incrementAndGet();return .5;};
        assertEquals(1, select("@RLO{a=3}",context(),random).size());
        assertEquals(6,draws.get()); draws.set(0);
        assertEquals(6, select("@RLNT{a=3}",context().withLocationTargets(List.of(origin,origin)),random).size());
        assertEquals(12,draws.get());
        assertTrue(select("@RLNT",context(),random).isEmpty());
    }

    @Test void minimumAndSpacingUseThreeDimensionsAndAmountSquaredAttempts() {
        AtomicInteger draws = new AtomicInteger(); DoubleSupplier random = () -> {draws.incrementAndGet();return .5;};
        assertTrue(select("@RLO{a=3;minr=1}",context(),random).isEmpty());assertEquals(18,draws.get());
        draws.set(0);
        assertEquals(1, select("@RLO{a=3;s=1}",context(),random).size());assertEquals(18,draws.get());
        assertEquals(1,select("@RLNC{a=1;r=1;minr=1}",context(),sequence(.5,.5,0)).size());
        assertEquals(1,select("@RLO{a=1;minradius=0;minr=10;spacing=0;s=10}",context(),random).size());
    }

    @Test void spacingIsGlobalAcrossTargetsAndComparedAgainstAlreadyMutatedPoints() {
        var ctx=context().withLocationTargets(List.of(origin,origin));
        assertEquals(1,select("@RLNT{a=2;s=1}",ctx,()->.5).size());
        assertEquals(4,select("@RLNT{a=2;s=1;x=5}",ctx,()->.5).size(),
                "unmutated candidate is compared to previously shifted point");
        assertTrue(select("@RLNT{a=2;minr=1;x=100}",ctx,()->.5).isEmpty(),"minimum runs before offsets");
    }

    @Test void nativeIntegerOverflowInMinimumAndSpacingIsPreserved() {
        assertEquals(1,select("@RLO{a=1;minr=50000}",context(),()->.5).size());
        assertTrue(select("@RLNT{a=1;minr=50000}",context().withLocationTarget(origin),()->.5).isEmpty());
        assertEquals(3,select("@RLNT{a=3;s=50000}",context().withLocationTarget(origin),()->.5).size());
    }

    @Test void solidsRetryAndTheTenthResampleFallsBackWithoutTestingIt() {
        solid = point -> point.getX() < 0;
        assertEquals(List.of(new Location(world,.25,100.25,.25)),
                select("@RLO{a=1;r=1}",context(),sequence(0,0,.5,.5)));
        assertEquals(2,reads.size());reads.clear();
        AtomicInteger draws = new AtomicInteger();
        DoubleSupplier random=()->draws.getAndIncrement()<20 ? 0 : .5;
        assertEquals(List.of(new Location(world,.25,101.25,.25)),select("@RLO{a=1;r=1}",context(),random));
        assertEquals(22,draws.get());assertEquals(10,reads.size());
        assertTrue(reads.stream().allMatch(p->p.getX()<0));
    }

    @Test void postFiltersAndLimitsPreserveNativeTargetDuplicates() {
        var ctx=context().withLocationTarget(origin);
        assertEquals(2,select("@RLNT{a=3;limit=2;sort=NEAREST;bt=#AIR}",ctx,()->.5).size());
        assertTrue(select("@RLNT{a=3;bi=#AIR}",ctx,()->.5).isEmpty());
        solid=point->point.getY()>101;
        assertEquals(3,select("@RLNT{a=3;y=2;bt=#STONE}",ctx,()->.5).size());
    }

    @Test void crossWorldSpacingFailsClosedAndZeroSpacingStillSelectsEachWorld() {
        World other=mock(World.class);
        Block block=mock(Block.class);when(block.getType()).thenReturn(air);when(other.getBlockAt(any(Location.class))).thenReturn(block);
        var ctx=context().withLocationTargets(List.of(origin,new Location(other,0,100,0)));
        assertEquals(2,select("@RLNT{a=1}",ctx,()->.5).size());
        assertTrue(select("@RLNT{a=1;s=1}",ctx,()->.5).isEmpty());
    }

    @Test void invalidInputsAndOversizedRequestsDoNotStartQueries() {
        for(String amount:List.of("0","-1","513","2147483647"))
            assertTrue(select("@RLO{a="+amount+"}",context(),()->.5).isEmpty());
        assertTrue(select("@RLNT{a=512}",context().withLocationTargets(List.of(origin,origin)),()->.5).isEmpty());
        assertFalse(Targeters.validLocationTargeterSpec("@RLO{conditions=bad}"));
        assertFalse(Targeters.validLocationTargeterSpec("@RLO{a=1"));
        Location invalid=origin.clone();invalid.setX(Double.NaN);
        assertTrue(select("@RLNT",context().withLocationTarget(invalid),()->.5).isEmpty());
        when(instance.isValid()).thenReturn(false);
        assertTrue(select("@RLNC",context(),()->.5).isEmpty());
        verifyNoInteractions(world);
    }

    @Test void outputCapRejectsTheWholeCollection() {
        assertTrue(select("@RLNT{a=1}",context().withLocationTargets(java.util.Collections.nCopies(4097,origin)),()->.5).isEmpty());
        assertEquals(4097,reads.size());
    }

    @Test void locationDispatchAndNestedSelectionCarryCollectionsAndGuards() {
        solid=point->true; // native deterministic fallback despite production randomness
        var registry=new BehaviorRegistryImpl();var service=new SkillService(registry,null);
        var captured=new ArrayList<Location>();
        registry.registerAction("capture-random",ctx->captured.add(ctx.targetLocation().orElseThrow()));
        registry.registerCondition("reject-random",ctx->false);
        var ctx=context().withLocationTargets(List.of(origin,casterLocation));
        String key=service.bindAction("capture-random @RLNT{a=2}");assertNotNull(key);assertTrue(service.runAction(key,ctx));
        assertEquals(4,captured.size());captured.clear();
        var yaml=new YamlConfiguration();yaml.set("skills.inner.on-decision",List.of("capture-random @targetedLoc"));
        service.loadSkills(yaml.getConfigurationSection("skills"));
        key=service.bindAction("skill{s=inner} @RLNT{a=2}");assertNotNull(key);assertTrue(service.runAction(key,ctx));
        assertEquals(2,captured.size());captured.clear();
        key=service.bindAction("capture-random @RLO{conditions=[ - reject-random true ]}");
        assertNotNull(key);assertTrue(service.runAction(key,ctx));assertTrue(captured.isEmpty());
        for(String alias:RandomLocationTargeter.NAMES) assertTrue(Targeters.isLocationName(alias));
    }
}
