package dev.helstera.ai.skill;

import dev.helstera.ai.BehaviorRegistryImpl;
import dev.helstera.api.behavior.BehaviorContext;
import dev.helstera.api.behavior.VariableLocations;
import dev.helstera.api.instance.ModelInstance;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.LivingEntity;
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

class VariableLocationValueTest {
    private static final List<String> SCOPES = List.of("skill", "caster", "target", "world", "global");
    private static final List<String> MECHANICS = List.of(
            "variablesetlocation", "setvariablelocation", "setvarloc", "variablelocationset");
    private final BehaviorRegistryImpl registry = new BehaviorRegistryImpl();
    private final SkillService skills = new SkillService(registry, null);
    private World casterWorld;
    private World otherWorld;
    private LivingEntity caster;
    private LivingEntity target;
    private LivingEntity trigger;
    private Location origin;
    private BehaviorContext context;

    @BeforeEach
    void setUp() {
        SkillExtras.resetRuntimeHooks();
        VariableLocations.clear();
        casterWorld = world("location-caster-world");
        otherWorld = world("location-other-world");
        caster = entity(new Location(casterWorld, 1, 61, 3, 12, -8));
        target = entity(new Location(otherWorld, 20, 70, 4, 90, 10));
        trigger = entity(new Location(otherWorld, 30, 80, 6, -50, 30));
        origin = new Location(otherWorld, 40, 90, 8, 120, -20);
        int instanceId = caster.getUniqueId().hashCode();
        Location casterLocation = caster.getLocation();
        ModelInstance model = mock(ModelInstance.class);
        when(model.isValid()).thenReturn(true);
        when(model.instanceId()).thenReturn(instanceId);
        when(model.baseEntity()).thenReturn(Optional.of(caster));
        when(model.location()).thenReturn(casterLocation);
        context = BehaviorContext.execution(model, null, 1, 4, 0, "location-value", null,
                trigger, List.of(target), origin).withTargetEntity(target);
    }

    @AfterEach
    void tearDown() {
        SkillCatalog.taskScheduler(null);
        SkillExtras.resetRuntimeHooks();
        VariableLocations.clear();
    }

    @ParameterizedTest
    @ValueSource(strings = {"skill", "caster", "target", "world", "global"})
    void selectedPositionIsIndependentOfTheRegistryAndOuterRecipient(String scope) {
        for (String candidate : SCOPES) run("set-variable var=Point.Part scope=" + candidate + " value=7");
        run("setvariablelocation{var=ignored.Point.Part;scope=" + scope + "} @target");
        for (String candidate : SCOPES) {
            assertEquals(candidate.equals(scope) ? Optional.of(caster.getLocation()) : Optional.empty(),
                    location(candidate, "Point.Part"), candidate);
            assertEquals(candidate.equals(scope) ? null : 7,
                    candidate.equals("skill") ? context.skillTree().orElseThrow().variables().get("Point.Part")
                            : SkillExtras.scopedVariableSnapshot(VariableLocations.scopeKey(candidate, context))
                                    .variables().get("Point.Part"), candidate);
            assertTrue(location(candidate, "ignored.Point.Part").isEmpty());
        }
        run("setvariablelocation{var=ignored.Point.Part;scope=" + scope + ";value=@origin} @target");
        assertEquals(origin, location(scope, "Point.Part").orElseThrow());
        run("setvariablelocation{var=ignored.Point.Part;scope=" + scope + ";value=@trigger} @target");
        assertEquals(trigger.getLocation(), location(scope, "Point.Part").orElseThrow());
        assertTrue(SkillExtras.scopedVariableSnapshot("world:" + otherWorld.getUID()).locations().isEmpty());
        assertTrue(SkillExtras.scopedVariableSnapshot("entity:" + trigger.getUniqueId()).locations().isEmpty());
    }

    @Test
    void nativeMechanicNamesAndStaticSelectorAliasesBindToTheirOwnPositions() {
        var selectors = new LinkedHashMap<String, Location>();
        for (String selector : List.of("self", "caster", "boss", "mob", "selflocation", "casterlocation",
                "bosslocation", "moblocation")) selectors.put(selector, caster.getLocation());
        selectors.put("origin", origin);
        selectors.put("source", origin);
        selectors.put("trigger", trigger.getLocation());
        selectors.put("triggerlocation", trigger.getLocation());
        for (String mechanic : MECHANICS) {
            for (var entry : selectors.entrySet()) {
                run(mechanic + "{var=target.Point;value=@" + entry.getKey().toUpperCase(java.util.Locale.ROOT) + "} @target");
                assertEquals(entry.getValue(), location("target", "Point").orElseThrow(), mechanic + " " + entry.getKey());
            }
        }
    }

    @Test
    void valueAliasesUseDeclaredPriorityAndRepeatedKeysUseTheirLastValue() {
        for (String alias : List.of("value", "val", "v", "amount", "a")) {
            run("setvariablelocation{var=target.Point;" + alias + "=@origin}");
            assertEquals(origin, location("target", "Point").orElseThrow(), alias);
        }
        for (String arguments : List.of("a=@trigger;amount=@trigger;v=@trigger;val=@trigger;value=@origin",
                "value=@origin;val=@trigger", "val=@origin;v=@trigger", "v=@origin;amount=@trigger",
                "amount=@origin;a=@trigger", "value=;VALUE=@origin", "val=@unknown;VAL=@origin")) {
            run("setvariablelocation{var=target.Point;" + arguments + "}");
            assertEquals(origin, location("target", "Point").orElseThrow(), arguments);
        }
        Map<String, Object> before = snapshots(context);
        for (String arguments : List.of("value=@self;VALUE=", "value=;val=@origin", "val=;v=@origin")) {
            assertNull(skills.bindAction("setvariablelocation{var=target.Point;" + arguments + "}"));
            assertEquals(before, snapshots(context));
        }
    }

    @Test
    void locationDispatchDoesNotSubstituteItsPointForTheValueOrBorrowATargetRegistry() {
        for (String scope : List.of("skill", "caster", "world", "global")) {
            run("setvariablelocation{var=" + scope + ".Point} @origin");
            assertEquals(caster.getLocation(), location(scope, "Point").orElseThrow(), scope);
            run("setvariablelocation{var=" + scope + ".Point;value=@trigger} @origin");
            assertEquals(trigger.getLocation(), location(scope, "Point").orElseThrow(), scope);
        }
        run("set-variable var=Point scope=target value=17");
        Map<String, Object> before = snapshots(context);
        run("setvariablelocation{var=target.Point;value=@self} @origin");
        assertEquals(before, snapshots(context));
        assertEquals(17, SkillExtras.placeholderVariable(context, "target", "Point"));
        assertSame(target, context.targetEntity().orElseThrow());
        assertEquals(origin, context.origin().orElseThrow());
    }

    @Test
    void canonicalNoValueKeepsOuterTargetBehaviorAndLiteralDottedKeys() {
        run("setvariablelocation Point");
        assertEquals(target.getLocation(), location("skill", "Point").orElseThrow());
        run("variable-location-set var=Point scope=target @target");
        assertEquals(target.getLocation(), location("target", "Point").orElseThrow());
        Location outer = new Location(otherWorld, 55, 100, 13, 25, -35);
        run("variable-location-set var=Point scope=skill", context.withLocationTarget(outer));
        assertEquals(outer, location("skill", "Point").orElseThrow());
        run("variable-location-set var=skill.Point.Part scope=target value=@origin @target");
        assertEquals(origin, location("target", "skill.Point.Part").orElseThrow());
        assertTrue(location("target", "Point.Part").isEmpty());
    }

    @Test
    void missingTriggerPreservesExistingScalarLocationsAndRetention() {
        var absent = BehaviorContext.execution(context.instance(), null, 1, 4, 0, "missing-trigger", null,
                null, List.of(target), origin).withTargetEntity(target);
        run("set-variable var=Scalar scope=target value=17 duration=1200", absent);
        run("variable-location-set var=Point scope=target value=@origin save=true", absent);
        Map<String, Object> before = snapshots(absent);
        for (String key : List.of("Scalar", "Point", "Missing")) {
            run("setvariablelocation{var=target." + key + ";value=@trigger;duration=20}", absent);
            assertEquals(before, snapshots(absent), key);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"skill", "caster", "target", "world", "global"})
    void locationWritesReplaceScalarsAndKeepRawNativeDeadlines(String scope) {
        run("set-variable var=Point scope=" + scope + " value=7");
        run("setvariablelocation{var=" + scope + ".Point;value=@origin;save=true}");
        assertFalse(scope.equals("skill") ? context.skillTree().orElseThrow().variables().containsKey("Point")
                : SkillExtras.scopedVariableSnapshot(VariableLocations.scopeKey(scope, context)).variables().containsKey("Point"));
        assertEquals(origin, location(scope, "Point").orElseThrow());
        assertEquals(0, expiration(scope, "Point"));
        run("setvariablelocation{var=" + scope + ".Point;value=@self;save=false}");
        assertEquals(1, expiration(scope, "Point"));
        run("setvariablelocation{var=" + scope + ".Point;value=@trigger;duration=-1}");
        assertEquals(-50, expiration(scope, "Point"));
        assertEquals(trigger.getLocation(), location(scope, "Point").orElseThrow());
        run("set-variable var=Point scope=" + scope + " value=9");
        run("setvariablelocation{var=" + scope + ".Point;value=@origin;duration=20}");
        assertTrue(location(scope, "Point").isEmpty());
        assertNull(SkillExtras.placeholderVariable(context, scope, "Point"));
    }

    @Test
    void invalidOrUnsupportedValueSelectorsRejectWithoutChangingRegistries() {
        run("set-variable var=Point scope=target value=7 duration=1200");
        Map<String, Object> before = snapshots(context);
        for (String mechanic : MECHANICS) {
            for (String selector : List.of("", "\"@self\"", "'@origin'", "@target{limit=1}", "@targetlocation{maxdistance=32}", "@unknown",
                    "1,2,3", "world,1,2,3", "<skill.var.Selector>", "@self{y=2}", "@origin{offset=2}")) {
                String source = mechanic + "{var=target.Point;value=" + selector + "} @target";
                int warnings = skills.warnings().size();
                assertNull(skills.bindAction(source), source);
                assertTrue(skills.warnings().size() > warnings, source);
                assertEquals(before, snapshots(context), source);
            }
        }
    }

    @Test
    void delayedRepeatedLocationValuesResolveLiveSelectorsAndKeepExecutionMetadata() {
        run("set-variable var=Gate scope=skill value=1");
        var pending = new ArrayDeque<Runnable>();
        var delays = new ArrayList<Long>();
        SkillCatalog.taskScheduler((task, delay) -> {
            pending.add(task);
            delays.add(delay);
            return true;
        });
        run("setvariablelocation{var=ignored.Point;scope=target;value=@trigger;delay=1;repeat=1;repeatinterval=2}"
                + " @target ?variableEquals{var=skill.Gate;value=1} 100%");
        assertTrue(location("target", "Point").isEmpty());
        pending.remove().run();
        assertEquals(trigger.getLocation(), location("target", "Point").orElseThrow());
        Location moved = new Location(otherWorld, 77, 102, 14, -15, 42);
        when(trigger.getLocation()).thenReturn(moved);
        pending.remove().run();
        assertEquals(moved, location("target", "Point").orElseThrow());
        assertEquals(List.of(1L, 2L), delays);
        assertTrue(pending.isEmpty());
        run("set-variable var=Gate scope=skill value=0");
        run("setvariablelocation{var=target.Point;value=@self} @target ?variableEquals{var=skill.Gate;value=1}");
        assertEquals(moved, location("target", "Point").orElseThrow());
        assertSame(target, context.targetEntity().orElseThrow());
        assertEquals(origin, context.origin().orElseThrow());
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

    private long expiration(String scope, String key) {
        return scope.equals("skill") ? context.skillTree().orElseThrow().locationExpiration(key)
                : VariableLocations.expirationTime(scope, key, context);
    }

    private Map<String, Object> snapshots(BehaviorContext execution) {
        var result = new LinkedHashMap<String, Object>();
        result.put("skill", execution.skillTree().orElseThrow().snapshot());
        for (String key : List.of("entity:" + caster.getUniqueId(), "entity:" + target.getUniqueId(),
                "entity:" + trigger.getUniqueId(), "world:" + casterWorld.getUID(),
                "world:" + otherWorld.getUID(), "global")) {
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

    private static LivingEntity entity(Location point) {
        LivingEntity entity = mock(LivingEntity.class);
        when(entity.getUniqueId()).thenReturn(UUID.randomUUID());
        when(entity.getWorld()).thenReturn(point.getWorld());
        when(entity.getLocation()).thenReturn(point);
        when(entity.isValid()).thenReturn(true);
        return entity;
    }
}
