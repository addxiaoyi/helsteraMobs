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
import org.bukkit.entity.Player;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class VariableLocationThreatValueTest {
    private static final List<String> SCOPES = List.of("skill", "caster", "target", "world", "global");
    private static final List<String> SELECTORS = List.of("@target", "@T", "@targetlocation", "@targetLoc", "@TL");
    private final BehaviorRegistryImpl registry = new BehaviorRegistryImpl();
    private final SkillService skills = new SkillService(registry, null);
    private final Map<UUID, Entity> entities = new LinkedHashMap<>();
    private World casterWorld;
    private World valueWorld;
    private Creature caster;
    private LivingEntity selected;
    private LivingEntity highest;
    private LivingEntity recipient;
    private LivingEntity trigger;
    private ModelInstance model;
    private Location origin;
    private BehaviorContext context;
    private ThreatTable table;

    @BeforeEach
    void setUp() {
        SkillExtras.resetRuntimeHooks();
        VariableLocations.clear();
        casterWorld = world("threat-location-caster");
        valueWorld = world("threat-location-value");
        caster = entity(Creature.class, new Location(casterWorld, 1, 62, 3, 20, -10));
        selected = entity(LivingEntity.class, new Location(valueWorld, 10, 71, 13, -70, 25));
        highest = entity(LivingEntity.class, new Location(valueWorld, 15, 75, 18, -40, 15));
        recipient = entity(LivingEntity.class, new Location(casterWorld, 20, 72, 23, -80, 30));
        trigger = entity(LivingEntity.class, new Location(casterWorld, 30, 73, 33, -90, 35));
        origin = new Location(casterWorld, 40, 74, 43, -100, 40);
        when(caster.getTarget()).thenReturn(trigger);
        model = mock(ModelInstance.class);
        when(model.isValid()).thenReturn(true);
        int instanceId = caster.getUniqueId().hashCode();
        when(model.instanceId()).thenReturn(instanceId);
        when(model.baseEntity()).thenReturn(Optional.of(caster));
        Location casterLocation = caster.getLocation();
        when(model.location()).thenReturn(casterLocation);
        context = BehaviorContext.execution(model, null, 1, 4, 0, "threat-location", null,
                trigger, List.of(recipient), origin).withTargetEntity(recipient);
        table = new ThreatTable(0, 0, () -> 1000L, entities::get);
        table.addThreat(selected.getUniqueId(), 100);
        table.selectTarget(selected.getUniqueId());
        table.addThreat(highest.getUniqueId(), 105);
        SkillExtras.threatLookup(id -> table);
    }

    @AfterEach
    void tearDown() {
        SkillCatalog.taskScheduler(null);
        SkillExtras.resetRuntimeHooks();
        VariableLocations.clear();
    }

    @ParameterizedTest
    @ValueSource(strings = {"skill", "caster", "target", "world", "global"})
    void selectedThreatEntitySuppliesCrossWorldPositionWhileRecipientChoosesRegistry(String scope) {
        for (String candidate : SCOPES) run("set-variable var=Point.Part scope=" + candidate + " value=7");
        for (String selector : SELECTORS) {
            run("setvariablelocation{var=ignored.Point.Part;scope=" + scope + ";value=" + selector + ";save=true}");
            for (String candidate : SCOPES) {
                assertEquals(candidate.equals(scope) ? Optional.of(selected.getLocation()) : Optional.empty(),
                        location(candidate, "Point.Part"), candidate + " " + selector);
                assertEquals(candidate.equals(scope) ? null : 7, scalar(candidate, "Point.Part"));
            }
            assertEquals(selected.getUniqueId(), table.currentTarget());
            assertTrue(SkillExtras.scopedVariableSnapshot("entity:" + selected.getUniqueId()).locations().isEmpty());
            assertTrue(SkillExtras.scopedVariableSnapshot("entity:" + highest.getUniqueId()).locations().isEmpty());
            assertTrue(SkillExtras.scopedVariableSnapshot("world:" + valueWorld.getUID()).locations().isEmpty());
        }
        verify(caster, never()).getTarget();
    }

    @Test
    void readingStoredSelectionDoesNotConsultClockOrRecomputeRank() {
        var calls = new AtomicInteger();
        var forbidden = new AtomicBoolean();
        table = new ThreatTable(0, 0, () -> {
            calls.incrementAndGet();
            if (forbidden.get()) throw new IllegalStateException("location read consulted threat clock");
            return 1000L;
        }, entities::get);
        table.addThreat(selected.getUniqueId(), 100);
        table.selectTarget(selected.getUniqueId());
        table.addThreat(highest.getUniqueId(), 105);
        assertEquals(highest.getUniqueId(), table.top(null));
        var before = table.snapshot();
        int beforeCalls = calls.get();
        forbidden.set(true);
        for (String selector : SELECTORS) {
            run("setvariablelocation{var=skill.Point;value=" + selector + "}");
            assertEquals(selected.getLocation(), location("skill", "Point").orElseThrow());
        }
        assertEquals(beforeCalls, calls.get());
        assertEquals(selected.getUniqueId(), table.currentTarget());
        forbidden.set(false);
        assertEquals(before, table.snapshot());
    }

    @Test
    void outerEntityAndLocationDispatchDoNotSupplyTheThreatValue() {
        run("setvariablelocation{var=target.Point;value=@targetlocation} @trigger");
        assertEquals(selected.getLocation(), VariableLocations.get(trigger, "Point").orElseThrow());
        assertTrue(VariableLocations.get(recipient, "Point").isEmpty());
        Location outer = new Location(casterWorld, 51, 91, 15, 45, -20);
        var located = context.withLocationTarget(outer);
        for (String selector : List.of("@target", "@targetlocation")) {
            for (String scope : List.of("skill", "caster", "world", "global")) {
                run("setvariablelocation{var=" + scope + ".Point;value=" + selector + "}", located);
                assertEquals(selected.getLocation(), location(scope, "Point").orElseThrow());
            }
            run("set-variable var=Point scope=target value=17 duration=1200");
            var before = snapshots(context);
            run("setvariablelocation{var=target.Point;value=" + selector + "}", located);
            assertEquals(before, snapshots(context));
        }
    }

    @Test
    void unresolvedSelectedIdentityPreservesScalarLocationAndDeadlinesInEveryScope() {
        seedValues();
        var before = snapshots(context);
        entities.remove(selected.getUniqueId());
        for (String selector : SELECTORS) {
            for (String scope : SCOPES) {
                for (String key : List.of("Scalar", "Point", "Missing")) {
                    run("setvariablelocation{var=" + scope + "." + key + ";value=" + selector + ";duration=20}");
                    assertEquals(before, snapshots(context), selector + " " + scope + " " + key);
                }
            }
        }
        assertEquals(selected.getUniqueId(), table.currentTarget());
        verify(caster, never()).getTarget();
    }

    @Test
    void clearingSelectionDistinguishesEntityNoWriteFromLocationOrigin() {
        table.clear();
        seedValues();
        var before = snapshots(context);
        for (String selector : List.of("@target", "@T")) {
            run("setvariablelocation{var=skill.Scalar;value=" + selector + ";duration=20}");
            assertEquals(before, snapshots(context));
        }
        for (String selector : List.of("@targetlocation", "@targetLoc", "@TL")) {
            run("setvariablelocation{var=skill.Scalar;value=" + selector + ";save=true}");
            assertEquals(origin, location("skill", "Scalar").orElseThrow());
            assertNull(scalar("skill", "Scalar"));
        }
        verify(caster, never()).getTarget();
    }

    @Test
    void storedThreatTargetAllowsNonCreatureCastersWithoutBorrowingVanillaTargets() {
        for (Class<? extends Entity> type : List.of(Mob.class, LivingEntity.class, Entity.class)) {
            Entity nonCreature = entity(type, new Location(casterWorld, 61, 65, 67));
            when(model.baseEntity()).thenReturn(Optional.of(nonCreature));
            for (String selector : SELECTORS) {
                run("setvariablelocation{var=skill.Point;value=" + selector + "}");
                assertEquals(selected.getLocation(), location("skill", "Point").orElseThrow());
            }
            if (nonCreature instanceof Mob mob) verify(mob, never()).getTarget();
        }
    }

    @Test
    void playerMissingAndInvalidCastersRemainUnavailableEvenWithStoredThreatTarget() {
        seedValues();
        var before = snapshots(context);
        Player player = entity(Player.class, new Location(casterWorld, 71, 75, 77));
        for (Optional<Entity> casterEntity : List.of(Optional.<Entity>of(player), Optional.<Entity>empty())) {
            when(model.baseEntity()).thenReturn(casterEntity);
            for (String selector : SELECTORS) {
                run("setvariablelocation{var=skill.Point;value=" + selector + ";duration=20}");
                assertEquals(before, snapshots(context));
            }
        }
        when(model.baseEntity()).thenReturn(Optional.of(caster));
        when(model.isValid()).thenReturn(false);
        for (String selector : SELECTORS) {
            run("setvariablelocation{var=skill.Point;value=" + selector + ";duration=20}");
            assertEquals(before, snapshots(context));
        }
        verify(player, never()).getEyeLocation();
    }

    @Test
    void terminalCasterCannotReadStoredThreatSelectionEvenWhileLookupRemainsAvailable() {
        seedValues();
        var terminal = new TerminalSkillContext(model, -110);
        var retained = terminal.detachVariables(null);
        assertNotNull(retained);
        var terminalContext = BehaviorContext.continueExecution(terminal, recipient, 1, 4, 0,
                "on-despawn", null, trigger, List.of(recipient), origin, context.skillTree().orElseThrow());
        var before = snapshots(terminalContext);
        var retainedBefore = retained.snapshot();
        try {
            for (String selector : SELECTORS) {
                for (String scope : SCOPES) {
                    run("setvariablelocation{var=" + scope + ".Point;value=" + selector + ";duration=20}", terminalContext);
                    assertEquals(before, snapshots(terminalContext));
                    assertEquals(retainedBefore, retained.snapshot());
                }
            }
        } finally {
            terminal.close();
        }
    }

    @Test
    void delayedRepeatedValuesReadNewSelectionsWithoutChangingRecipient() {
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
        assertEquals(selected.getLocation(), location("target", "Point").orElseThrow());
        table.selectTarget(highest.getUniqueId());
        pending.remove().run();
        assertEquals(highest.getLocation(), location("target", "Point").orElseThrow());
        assertEquals(List.of(1L, 2L), delays);
        assertTrue(pending.isEmpty());
        assertTrue(VariableLocations.get(highest, "Point").isEmpty());
        assertSame(recipient, context.targetEntity().orElseThrow());
    }

    @Test
    void currentEntityPositionIsReadOnEachExecutionAndStoredByValue() {
        String bound = skills.bindAction("setvariablelocation{var=skill.Point;value=@target}");
        assertNotNull(bound);
        assertTrue(registry.runAction(bound, context));
        Location original = selected.getLocation();
        Location moved = new Location(valueWorld, 88, 102, 14, 175, -42);
        when(selected.getLocation()).thenReturn(moved);
        assertEquals(original, location("skill", "Point").orElseThrow());
        assertTrue(registry.runAction(bound, context));
        assertEquals(moved, location("skill", "Point").orElseThrow());
    }

    @Test
    void canonicalNoValueStillUsesRecipientDespiteStoredThreatSelection() {
        run("variable-location-set var=Point scope=target");
        assertEquals(recipient.getLocation(), location("target", "Point").orElseThrow());
        run("variable-location-set var=Point scope=target value=@target");
        assertEquals(selected.getLocation(), location("target", "Point").orElseThrow());
        assertEquals(selected.getUniqueId(), table.currentTarget());
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
        for (Entity entity : List.of(caster, selected, highest, recipient, trigger)) {
            String key = "entity:" + entity.getUniqueId();
            result.put(key, SkillExtras.scopedVariableSnapshot(key));
        }
        for (String key : List.of("world:" + casterWorld.getUID(), "world:" + valueWorld.getUID(), "global")) {
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

    private <T extends Entity> T entity(Class<T> type, Location point) {
        T entity = mock(type);
        UUID id = UUID.randomUUID();
        when(entity.getUniqueId()).thenReturn(id);
        when(entity.getWorld()).thenReturn(point.getWorld());
        when(entity.getLocation()).thenReturn(point);
        when(entity.isValid()).thenReturn(true);
        entities.put(id, entity);
        return entity;
    }
}
