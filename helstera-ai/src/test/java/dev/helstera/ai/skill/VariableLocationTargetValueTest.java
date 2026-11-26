package dev.helstera.ai.skill;

import dev.helstera.ai.BehaviorRegistryImpl;
import dev.helstera.ai.ThreatTable;
import dev.helstera.api.behavior.BehaviorContext;
import dev.helstera.api.behavior.VariableLocations;
import dev.helstera.api.instance.ModelInstance;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Creature;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class VariableLocationTargetValueTest {
    private static final List<String> SCOPES = List.of("skill", "caster", "target", "world", "global");
    private static final List<String> SELECTORS = List.of("@target", "@T", "@targetlocation", "@targetLoc", "@TL");
    private final BehaviorRegistryImpl registry = new BehaviorRegistryImpl();
    private final SkillService skills = new SkillService(registry, null);
    private World casterWorld;
    private World valueWorld;
    private Creature caster;
    private LivingEntity aiTarget;
    private LivingEntity recipient;
    private LivingEntity trigger;
    private ModelInstance model;
    private Location origin;
    private BehaviorContext context;

    @BeforeEach
    void setUp() {
        SkillExtras.resetRuntimeHooks();
        VariableLocations.clear();
        casterWorld = world("target-value-caster");
        valueWorld = world("target-value-other");
        caster = entity(Creature.class, new Location(casterWorld, 1, 62, 3, 20, -10));
        aiTarget = entity(LivingEntity.class, new Location(valueWorld, 10, 71, 13, -70, 25));
        recipient = entity(LivingEntity.class, new Location(valueWorld, 20, 72, 23, -80, 30));
        trigger = entity(LivingEntity.class, new Location(valueWorld, 30, 73, 33, -90, 35));
        origin = new Location(valueWorld, 40, 74, 43, -100, 40);
        when(caster.getTarget()).thenReturn(aiTarget);
        model = mock(ModelInstance.class);
        when(model.isValid()).thenReturn(true);
        int instanceId = caster.getUniqueId().hashCode();
        when(model.instanceId()).thenReturn(instanceId);
        when(model.baseEntity()).thenReturn(Optional.of(caster));
        Location casterLocation = caster.getLocation();
        when(model.location()).thenReturn(casterLocation);
        context = BehaviorContext.execution(model, null, 1, 4, 0, "location-target-value", null,
                trigger, List.of(recipient), origin).withTargetEntity(recipient);
    }

    @AfterEach
    void tearDown() {
        SkillCatalog.taskScheduler(null);
        SkillExtras.resetRuntimeHooks();
        VariableLocations.clear();
    }

    @ParameterizedTest
    @ValueSource(strings = {"skill", "caster", "target", "world", "global"})
    void casterAiTargetSuppliesValueWhileScopeUsesExecutionRecipient(String scope) {
        for (String candidate : SCOPES) run("set-variable var=Point.Part scope=" + candidate + " value=7");
        for (String selector : SELECTORS) {
            run("setvariablelocation{var=ignored.Point.Part;scope=" + scope + ";value=" + selector + ";save=true}");
            for (String candidate : SCOPES) {
                assertEquals(candidate.equals(scope) ? Optional.of(aiTarget.getLocation()) : Optional.empty(),
                        location(candidate, "Point.Part"), candidate + " " + selector);
                assertEquals(candidate.equals(scope) ? null : 7,
                        scalar(candidate, "Point.Part"), candidate);
            }
            assertTrue(SkillExtras.scopedVariableSnapshot("entity:" + aiTarget.getUniqueId()).locations().isEmpty());
            assertTrue(SkillExtras.scopedVariableSnapshot("entity:" + trigger.getUniqueId()).locations().isEmpty());
            assertTrue(SkillExtras.scopedVariableSnapshot("world:" + valueWorld.getUID()).locations().isEmpty());
            assertSame(recipient, context.targetEntity().orElseThrow());
        }
    }

    @Test
    void outerEntitySelectorChangesRegistryButDoesNotReplaceCasterAiValue() {
        run("setvariablelocation{var=target.Point;value=@targetlocation} @trigger");
        assertEquals(aiTarget.getLocation(), VariableLocations.get(trigger, "Point").orElseThrow());
        assertTrue(VariableLocations.get(recipient, "Point").isEmpty());
        assertTrue(VariableLocations.get(aiTarget, "Point").isEmpty());
    }

    @Test
    void outerLocationDispatchRetainsSelectedValueAndCannotBorrowTargetRegistry() {
        Location outer = new Location(casterWorld, 55, 100, 13, 45, -35);
        BehaviorContext located = context.withLocationTarget(outer);
        for (String selector : List.of("@target", "@targetlocation")) {
            for (String scope : List.of("skill", "caster", "world", "global")) {
                run("setvariablelocation{var=" + scope + ".Point;value=" + selector + "}", located);
                assertEquals(aiTarget.getLocation(), location(scope, "Point").orElseThrow());
            }
            run("set-variable var=Point scope=target value=17 duration=1200");
            var before = snapshots(context);
            run("setvariablelocation{var=target.Point;value=" + selector + "}", located);
            assertEquals(before, snapshots(context));
            assertEquals(17, SkillExtras.placeholderVariable(context, "target", "Point"));
        }
    }

    @Test
    void missingAiEntityTargetPreservesBothValuesAndTheirRetention() {
        when(caster.getTarget()).thenReturn(null);
        seedValues();
        var before = snapshots(context);
        for (String selector : List.of("@target", "@T")) {
            for (String key : List.of("Scalar", "Point", "Missing")) {
                for (String scope : SCOPES) {
                    run("setvariablelocation{var=" + scope + "." + key + ";value=" + selector + ";duration=20}");
                    assertEquals(before, snapshots(context), selector + " " + scope + " " + key);
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"skill", "caster", "target", "world", "global"})
    void missingAiLocationTargetUsesMetadataOrigin(String scope) {
        when(caster.getTarget()).thenReturn(null);
        for (String selector : List.of("@targetlocation", "@targetLoc", "@TL")) {
            run("set-variable var=Point scope=" + scope + " value=7");
            run("setvariablelocation{var=" + scope + ".Point;value=" + selector + ";save=true}");
            assertEquals(origin, location(scope, "Point").orElseThrow());
            assertNull(scalar(scope, "Point"));
        }
    }

    @Test
    void missingAiTargetDoesNotUseConfirmedKillVictimAsAFallback() {
        when(caster.getTarget()).thenReturn(null);
        var killed = BehaviorContext.execution(model, null, 1, 4, 0, "on-kill", null,
                recipient, List.of(recipient), origin).withTargetEntity(recipient);
        run("set-variable var=Point scope=skill value=17", killed);
        run("setvariablelocation{var=skill.Point;value=@target}", killed);
        assertEquals(17, SkillExtras.placeholderVariable(killed, "skill", "Point"));
        run("setvariablelocation{var=skill.Point;value=@targetlocation}", killed);
        assertEquals(origin, killed.skillTree().orElseThrow().location("Point").orElseThrow());
    }

    @Test
    void unsupportedNonCreatureCastersPreserveStateDespiteOuterTargets() {
        seedValues();
        var before = snapshots(context);
        for (Class<? extends Entity> type : List.of(Mob.class, LivingEntity.class, Entity.class)) {
            Entity unsupported = entity(type, new Location(casterWorld, 65, 66, 67));
            when(model.baseEntity()).thenReturn(Optional.of(unsupported));
            for (String selector : SELECTORS) {
                for (String key : List.of("Scalar", "Point", "Missing")) {
                    run("setvariablelocation{var=skill." + key + ";value=" + selector + ";duration=20}");
                    assertEquals(before, snapshots(context), type.getSimpleName() + " " + selector);
                }
            }
            if (unsupported instanceof Mob mob) verify(mob, never()).getTarget();
        }
    }

    @Test
    void missingOrInvalidCasterPreservesStateRatherThanUsingOriginForTargetLocation() {
        seedValues();
        var before = snapshots(context);
        when(model.baseEntity()).thenReturn(Optional.empty());
        for (String selector : SELECTORS) {
            run("setvariablelocation{var=skill.Point;value=" + selector + "}");
            assertEquals(before, snapshots(context));
        }
        when(model.baseEntity()).thenReturn(Optional.of(caster));
        when(model.isValid()).thenReturn(false);
        for (String selector : SELECTORS) {
            run("setvariablelocation{var=skill.Point;value=" + selector + "}");
            assertEquals(before, snapshots(context));
        }
    }

    @Test
    void unselectedThreatTablesUseOriginOnlyForLocationSelectorsWithoutVanillaFallback() {
        seedValues();
        var table = new ThreatTable(0, 0, () -> 1000L, ignored -> aiTarget);
        SkillExtras.threatLookup(instance -> table);
        for (boolean populated : List.of(false, true)) {
            if (populated) table.addThreat(aiTarget.getUniqueId(), 100);
            assertNull(table.currentTarget());
            var before = snapshots(context);
            for (String selector : List.of("@target", "@T")) {
                for (String scope : SCOPES) {
                    for (String key : List.of("Scalar", "Point", "Missing")) {
                        run("setvariablelocation{var=" + scope + "." + key + ";value=" + selector + ";duration=20}");
                        assertEquals(before, snapshots(context), populated + " " + selector + " " + scope);
                    }
                }
            }
            for (String selector : List.of("@targetlocation", "@targetLoc", "@TL")) {
                for (String scope : SCOPES) {
                    run("setvariablelocation{var=" + scope + ".Fallback;value=" + selector + ";save=true}");
                    assertEquals(origin, location(scope, "Fallback").orElseThrow());
                }
            }
        }
        verify(caster, never()).getTarget();
    }

    @Test
    void terminalCasterCannotBecomeEligibleWhenItsThreatControllerDisappears() {
        seedValues();
        var table = new ThreatTable(0, 0, () -> 1000L, ignored -> aiTarget);
        table.addThreat(aiTarget.getUniqueId(), 100);
        SkillExtras.threatLookup(instance -> table);
        var terminal = new TerminalSkillContext(model, -100);
        var retained = terminal.detachVariables(null);
        assertNotNull(retained);
        var terminalContext = BehaviorContext.continueExecution(terminal, recipient, 1, 4, 0,
                "on-despawn", null, trigger, List.of(recipient), origin, context.skillTree().orElseThrow());
        when(model.isValid()).thenReturn(false);
        SkillExtras.threatLookup(instance -> null);
        SkillExtras.forgetEntity(caster);
        VariableLocations.clearEntity(caster);
        assertTrue(terminalContext.instanceValid());
        var before = snapshots(terminalContext);
        var retainedBefore = retained.snapshot();
        try {
            for (String selector : SELECTORS) {
                for (String scope : SCOPES) {
                    for (String key : List.of("Scalar", "Point", "Missing")) {
                        run("setvariablelocation{var=" + scope + "." + key + ";value="
                                + selector + ";duration=20}", terminalContext);
                        assertEquals(before, snapshots(terminalContext), selector + " " + scope + " " + key);
                        assertEquals(retainedBefore, retained.snapshot());
                    }
                }
            }
            verify(caster, never()).getTarget();
            run("setvariablelocation{var=skill.StaticPoint;value=@origin}", terminalContext);
            assertEquals(origin, terminalContext.skillTree().orElseThrow().location("StaticPoint").orElseThrow());
        } finally {
            terminal.close();
        }
    }

    @Test
    void delayedRepeatedValuesReadTheCurrentCasterAiTargetWithoutRebindingRecipient() {
        var pending = new ArrayDeque<Runnable>();
        var delays = new ArrayList<Long>();
        SkillCatalog.taskScheduler((task, delay) -> {
            pending.add(task);
            delays.add(delay);
            return true;
        });
        run("setvariablelocation{var=target.Point;value=@targetlocation;delay=1;repeat=1;repeatinterval=2}");
        assertTrue(location("target", "Point").isEmpty());
        pending.remove().run();
        assertEquals(aiTarget.getLocation(), location("target", "Point").orElseThrow());
        when(caster.getTarget()).thenReturn(trigger);
        pending.remove().run();
        assertEquals(trigger.getLocation(), location("target", "Point").orElseThrow());
        assertTrue(VariableLocations.get(trigger, "Point").isEmpty());
        assertEquals(List.of(1L, 2L), delays);
        assertTrue(pending.isEmpty());
    }

    @Test
    void canonicalNoValueStillUsesOuterRecipientAndLocation() {
        run("variable-location-set var=Point scope=target");
        assertEquals(recipient.getLocation(), location("target", "Point").orElseThrow());
        var outer = new Location(valueWorld, 81, 82, 83, 14, -15);
        run("variable-location-set var=Point scope=skill", context.withLocationTarget(outer));
        assertEquals(outer, location("skill", "Point").orElseThrow());
        when(caster.getTarget()).thenReturn(null);
        run("variable-location-set var=Point scope=target");
        assertEquals(recipient.getLocation(), location("target", "Point").orElseThrow());
    }

    private void seedValues() {
        for (String scope : SCOPES) {
            run("set-variable var=Scalar scope=" + scope + " value=17 duration=1200");
            run("variable-location-set var=Point scope=" + scope + " value=@origin save=true");
        }
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

    private Map<String, Object> snapshots(BehaviorContext execution) {
        var result = new LinkedHashMap<String, Object>();
        result.put("skill", execution.skillTree().orElseThrow().snapshot());
        for (String key : List.of("entity:" + caster.getUniqueId(), "entity:" + aiTarget.getUniqueId(),
                "entity:" + recipient.getUniqueId(), "entity:" + trigger.getUniqueId(),
                "world:" + casterWorld.getUID(), "world:" + valueWorld.getUID(), "global")) {
            result.put(key, SkillExtras.scopedVariableSnapshot(key));
        }
        return result;
    }

    private static World world(String name) {
        World world = mock(World.class);
        when(world.getUID()).thenReturn(UUID.randomUUID());
        when(world.getName()).thenReturn(name);
        return world;
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
