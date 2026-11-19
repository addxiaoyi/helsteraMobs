package dev.helstera.plugin;

import dev.helstera.ai.BehaviorRegistryImpl;
import dev.helstera.ai.skill.SkillService;
import dev.helstera.api.behavior.BehaviorContext;
import dev.helstera.api.instance.ModelInstance;
import dev.helstera.migration.importer.MmConditions;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.*;
import org.bukkit.util.BoundingBox;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ComparisonConditionContractTest {
    private final BehaviorRegistryImpl registry = new BehaviorRegistryImpl();
    private final SkillService skills = new SkillService(registry, null);
    private final World world = mock(World.class);
    private final LivingEntity caster = mock(LivingEntity.class);
    private final LivingEntity target = mock(LivingEntity.class);
    private final Item trigger = mock(Item.class);
    private final ModelInstance model = mock(ModelInstance.class);

    private BehaviorContext context(BehaviorContext.ConditionScope scope) {
        when(model.isValid()).thenReturn(true);
        when(model.baseEntity()).thenReturn(Optional.of(caster));
        when(caster.getUniqueId()).thenReturn(new UUID(1, 1));
        when(target.getUniqueId()).thenReturn(new UUID(2, 2));
        when(trigger.getUniqueId()).thenReturn(new UUID(3, 3));
        when(caster.getLocation()).thenReturn(new Location(world, 0, 64, 0));
        when(target.getLocation()).thenReturn(new Location(world, 0, 64, 4));
        when(trigger.getLocation()).thenReturn(new Location(world, 0, 68, 0));
        when(target.isValid()).thenReturn(true);
        return BehaviorContext.continueExecution(model, target, 1, 777, 0, "probe", "payload",
                trigger, List.of(target), new Location(world, 100, 100, 100), null).withConditionScope(scope);
    }

    private boolean test(String expression, BehaviorContext ctx) {
        String direct = skills.bindCondition(expression);
        assertNotNull(direct, skills.warnings().toString());
        var mapped = MmConditions.translate("?" + expression);
        assertTrue(mapped.translated(), expression);
        String imported = skills.bindCondition(mapped.condition());
        assertNotNull(imported, mapped.condition());
        boolean result = registry.testCondition(direct, ctx);
        assertEquals(result, registry.testCondition(imported, ctx), expression + " import/direct");
        return result;
    }

    @Test void distanceDispatchUsesActualCoordinatesAndNativeFloatSquaredBoundary() {
        var ctx = context(BehaviorContext.ConditionScope.TARGET);
        assertTrue(test("distance{d=4}", ctx));
        assertFalse(test("distance{d=<4}", ctx));
        assertFalse(test("distance{d=>4}", ctx));
        assertTrue(test("distance{d=3to5}", ctx));
        assertTrue(test("distance{d=5to3}", ctx));
        assertTrue(test("distance{d=-4}", ctx));
        when(target.getLocation()).thenReturn(new Location(world, 0, 64, 4.00000001));
        assertTrue(test("distance{d=4}", ctx), "native distanceSquared is narrowed to float");
        when(target.getLocation()).thenReturn(new Location(world, 0, 64, 6));
        assertFalse(test("distance{d=4}", ctx), "do not reuse cached distance or previous coordinates");
        assertTrue(test("distance{d=4}", ctx.withConditionScope(BehaviorContext.ConditionScope.TRIGGER)));
        assertTrue(test("distance{d=0}", ctx.withConditionScope(BehaviorContext.ConditionScope.CASTER)));
        assertTrue(test("distance{d=4}", ctx.withLocationTarget(new Location(world, 4, 64, 0))
                .withConditionScope(BehaviorContext.ConditionScope.TARGET)));
        assertFalse(test("distance{d=4}", ctx.withLocationTarget(new Location(mock(World.class), 4, 64, 0))
                .withConditionScope(BehaviorContext.ConditionScope.TARGET)));
    }

    @Test void yDifferenceUsesCasterAndSubjectIncludingCrossWorldCoordinates() {
        var ctx = context(BehaviorContext.ConditionScope.TRIGGER);
        assertTrue(test("ydiff{d=4}", ctx));
        assertFalse(test("ydiff{d=0}", ctx));
        when(trigger.getLocation()).thenReturn(new Location(mock(World.class), 0, 68, 0));
        assertTrue(test("ydiff{diff=4}", ctx));
        assertFalse(test("distance{d=4}", ctx));
        assertTrue(test("ydiff{difference=2to5}", ctx));
        var point = ctx.withLocationTarget(new Location(world, 0, 60, 0))
                .withConditionScope(BehaviorContext.ConditionScope.TARGET);
        assertTrue(test("ydiff{d=-4}", point));
    }

    @Test void dispatchDecisionsSurviveNegationAndDoNotSkipConjuncts() {
        var ctx = context(BehaviorContext.ConditionScope.CASTER);
        for (String source : List.of("ydiff{d=0}", "fieldofview{a=90}", "iscaster", "boundingboxesoverlap")) {
            assertFalse(test(source, ctx));
            assertFalse(test("!" + source, ctx), "no caster interface rejects before inversion");
        }
        var point = ctx.withLocationTarget(new Location(world, 0, 64, 4))
                .withConditionScope(BehaviorContext.ConditionScope.TARGET);
        for (String source : List.of("iscaster", "boundingboxesoverlap")) {
            assertTrue(test(source, point), "entity-only predicates leave position targets unchanged");
            assertTrue(test("!" + source, point));
        }
        registry.registerCondition("reject-comparison", ignored -> false);
        registry.registerCondition("accept-comparison", ignored -> true);
        assertFalse(registry.testCondition(skills.bindCondition("all{iscaster;reject-comparison}"), point));
        assertTrue(registry.testCondition(skills.bindCondition("all{!iscaster;accept-comparison}"), point));
        assertFalse(registry.testCondition(skills.bindCondition("!all{iscaster;accept-comparison}"), ctx));
        var yaml = new org.bukkit.configuration.file.YamlConfiguration();
        yaml.set("skills.reject.require", List.of("iscaster", "reject-comparison"));
        yaml.set("skills.accept.require", List.of("!iscaster", "accept-comparison"));
        skills.loadSkills(yaml.getConfigurationSection("skills"));
        assertFalse(registry.testCondition(skills.bindCondition("skill:reject"), point));
        assertTrue(registry.testCondition(skills.bindCondition("skill:accept"), point));
        assertFalse(registry.testCondition(skills.bindCondition("!skill:accept"), ctx));
    }

    @Test void distanceKeepsScientificScalarAsAnExtension() {
        var ctx = context(BehaviorContext.ConditionScope.TARGET);
        assertTrue(test("distance{d=4e-0}", ctx));
        assertFalse(test("distance{d=1e-2}", ctx));
        var execution = context(BehaviorContext.ConditionScope.EXECUTION);
        // Legacy AI execution uses cached distance and its positional <= default.
        assertTrue(registry.testCondition(skills.bindCondition("distance 800"), execution));
        assertFalse(registry.testCondition(skills.bindCondition("distance 4"), execution));
    }

    @Test void fieldOfViewUsesEyesAndPreservesNativePositionFilteringDirection() {
        var ctx = context(BehaviorContext.ConditionScope.TRIGGER);
        when(caster.getEyeLocation()).thenReturn(new Location(world, 0, 65.6, 0));
        when(trigger.getLocation()).thenReturn(new Location(world, 0, 64, 1));
        assertFalse(test("fov{a=90}", ctx), "feet would appear in cone; item is below eye cone");
        when(trigger.getLocation()).thenReturn(new Location(world, 0, 65.6, 4));
        assertTrue(test("fov{a=90}", ctx));
        when(trigger.getLocation()).thenReturn(new Location(mock(World.class), 4, 65.6, 0));
        assertTrue(test("fov{a=90;r=90}", ctx), "native compares vectors without a world check");
        var point = ctx.withLocationTarget(new Location(world, 0, 65.6, 4))
                .withConditionScope(BehaviorContext.ConditionScope.TARGET);
        assertFalse(test("fov{a=90}", point), "5.7.2 removes matching location targets");
        assertTrue(test("!fov{a=90}", point));
    }

    @Test void boundingBoxOffsetKeepsNativePitchScaleAndGenericTriggerEntity() {
        var ctx = context(BehaviorContext.ConditionScope.TRIGGER);
        when(caster.getLocation()).thenReturn(new Location(world, 0, 64, 0, 0, 45));
        when(caster.getBoundingBox()).thenReturn(new BoundingBox(-.3, 64, -.3, .3, 66, .3));
        when(trigger.getBoundingBox()).thenReturn(new BoundingBox(-.2, 64, 2.6, .2, 65, 3.0));
        assertTrue(test("boundingboxesoverlap{so=4}", ctx));
        assertFalse(test("boundingboxesoverlap{fo=0}", ctx));
        when(caster.getLocation()).thenReturn(new Location(world, 0, 64, 0, 0, 0));
        assertFalse(test("boundingboxesoverlap{f=4}", ctx));
    }

    @Test void malformedComparisonArgumentsAreRejectedAndPreserved() {
        for (String expression : List.of("distance{d=4;distance=5}", "distance{bad=4}", "distance{d=NaN}",
                "distance{d=1toInfinity}", "ydiff{d=1;diff=2}", "ydiff{bad=3}",
                "boundingboxesoverlap{f=4;fo=5}", "boundingboxesoverlap{f=Infinity}", "boundingboxesoverlap{bad=3}")) {
            assertNull(skills.bindCondition(expression), expression);
            assertFalse(MmConditions.translate("?" + expression).translated(), expression);
        }
    }
}
