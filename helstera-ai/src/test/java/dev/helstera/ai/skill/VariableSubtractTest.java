package dev.helstera.ai.skill;

import dev.helstera.ai.BehaviorRegistryImpl;
import dev.helstera.api.behavior.BehaviorContext;
import dev.helstera.api.behavior.EntityVariableContext;
import dev.helstera.api.behavior.SkillTreeContext;
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

class VariableSubtractTest {
    private static final List<String> SCOPES = List.of("skill", "caster", "target", "world", "global");
    private final BehaviorRegistryImpl registry = new BehaviorRegistryImpl();
    private final SkillService skills = new SkillService(registry, null);
    private World casterWorld;
    private World originWorld;
    private LivingEntity caster;
    private LivingEntity target;
    private LivingEntity trigger;
    private BehaviorContext context;

    @BeforeEach
    void setUp() {
        SkillExtras.resetRuntimeHooks();
        VariableLocations.clear();
        casterWorld = world("math-caster-world");
        originWorld = world("math-origin-world");
        caster = entity(casterWorld, 1);
        target = entity(originWorld, 5);
        trigger = entity(originWorld, 9);
        int instanceId = caster.getUniqueId().hashCode();
        Location location = caster.getLocation();
        ModelInstance model = mock(ModelInstance.class);
        when(model.isValid()).thenReturn(true);
        when(model.instanceId()).thenReturn(instanceId);
        when(model.baseEntity()).thenReturn(Optional.of(caster));
        when(model.location()).thenReturn(location);
        context = BehaviorContext.execution(model, null, 1, 4, 0, "math-test", null,
                trigger, List.of(target), new Location(originWorld, 0, 64, 0)).withTargetEntity(target);
    }

    @AfterEach
    void tearDown() {
        SkillCatalog.taskScheduler(null);
        SkillExtras.resetRuntimeHooks();
        VariableLocations.clear();
    }

    @ParameterizedTest
    @ValueSource(strings = {"skill", "caster", "target", "world", "global"})
    void subtractOnlyMutatesSelectedRegistryAndPreservesIntegerAndFloatTypes(String scope) {
        for (String candidate : SCOPES) {
            set(candidate, "Count.Part", "INTEGER", "7");
            set(candidate, "Rate", "FLOAT", "1.25");
        }
        run("variableSubtract{var=ignored.Count.Part;s=" + scope + ";amount=2.9}");
        run("reduceVariable{var=" + scope + ".Rate;value=0.5}");
        for (String candidate : SCOPES) {
            assertEquals(candidate.equals(scope) ? 5 : 7, value(candidate, "Count.Part"), candidate);
            assertEquals(candidate.equals(scope) ? 0.75f : 1.25f, value(candidate, "Rate"), candidate);
        }
    }

    @Test
    void aliasesPriorityDuplicatesAndDefaultZeroUseTheNativeOperand() {
        for (String alias : List.of("variableSubtract", "variableSub", "subtractVariable", "subVar", "reduceVariable")) {
            set("skill", "Count", "INTEGER", "10");
            run(alias + "{var=skill.Count;a=8;amount=7;v=6;val=5;value=1;VALUE=2}");
            assertEquals(8, value("skill", "Count"), alias);
        }
        run("variableSubtract{var=skill.Count}");
        assertEquals(8, value("skill", "Count"));
    }

    @Test
    void unsupportedTypesMissingValuesAndNonFiniteResultsLeaveTheRegistryUnchanged() {
        set("skill", "Text", "STRING", "7");
        set("skill", "Double", "DOUBLE", "7");
        set("skill", "Huge", "FLOAT", "3.4028235E38");
        var before = snapshots();
        run("variableSubtract{var=skill.Text;value=2}");
        run("variableSubtract{var=skill.Double;value=2}");
        run("variableSubtract{var=skill.Missing;value=2}");
        run("variableSubtract{var=skill.Huge;value=-3.4028235E38}");
        assertEquals(before, snapshots());
    }

    @Test
    void quotedOperandsWorkInBothDirectNativeSyntaxAndCanonicalImportedSyntax() {
        for (String alias : List.of("variableSubtract", "variableSub", "subtractVariable", "subVar", "reduceVariable")) {
            set("skill", "Count", "INTEGER", "7");
            run(alias + "{var=skill.Count;value=\"2.5\"}");
            assertEquals(5, value("skill", "Count"), alias);
        }
        set("skill", "Count", "INTEGER", "7");
        run("variable-subtract var=skill.Count value=\"2.5\"");
        assertEquals(5, value("skill", "Count"));
    }

    @Test
    void positiveDurationRefreshesAndDefaultZeroNegativePreserveTheOldDeadline() {
        var tree = context.skillTree().orElseThrow();
        long expiry = System.currentTimeMillis() + 60000;
        for (String suffix : List.of("", ";duration=0", ";duration=-1", ";duration=bad;d=40", ";save=true")) {
            tree.setVariable("Count", 7, expiry);
            run("variableSubtract{var=skill.Count;value=2" + suffix + "}");
            assertEquals(5, value("skill", "Count"));
            assertEquals(expiry, tree.variableExpiration("Count"));
        }
        run("variableSubtract{var=skill.Count;value=2;duration=1200;d=1}");
        assertTrue(tree.variableExpiration("Count") >= expiry);
        assertTrue(tree.variableExpiration("Count") <= System.currentTimeMillis() + 60000);
    }

    @Test
    void quotedNativeDurationRemainsInvalidAndPreservesExistingExpiration() {
        var tree = context.skillTree().orElseThrow();
        long expiry = System.currentTimeMillis() + 30000;
        for (String alias : List.of("variableSubtract", "variableSub", "subtractVariable", "subVar", "reduceVariable")) {
            for (String duration : List.of("duration=\"1200\"", "d='1200'", "expire=\"1200\"", "e='1200'")) {
                tree.setVariable("Count", 7, expiry);
                run(alias + "{var=skill.Count;value=2;" + duration + "}");
                assertEquals(5, value("skill", "Count"));
                assertEquals(expiry, tree.variableExpiration("Count"), alias + ":" + duration);
            }
        }
    }

    @Test
    void dynamicAmountResolvesAtExecutionAndTargeterRetainsTheMutationScope() {
        set("caster", "Step", "INTEGER", "2");
        set("target", "Count", "INTEGER", "10");
        String key = skills.bindAction("variableSubtract{var=target.Count;value=<caster.var.Step>} @targets");
        assertNotNull(key);
        set("caster", "Step", "INTEGER", "3");
        assertTrue(registry.runAction(key, context));
        assertEquals(7, value("target", "Count"));
        assertNull(skills.bindAction("variableSubtract{var=trigger.Count;value=3}"));
    }

    private void set(String scope, String key, String type, String value) {
        run("set-variable var=" + key + " scope=" + scope + " type=" + type + " value=" + value);
    }

    private void run(String source) {
        run(source, context);
    }

    private void run(String source, BehaviorContext execution) {
        String bound = skills.bindAction(source);
        assertNotNull(bound, source + ": " + skills.warnings());
        assertTrue(registry.runAction(bound, execution), source);
    }

    private Object value(String scope, String key) {
        return SkillExtras.placeholderVariable(context, scope, key);
    }

    private Map<String, Object> snapshots() {
        Map<String, Object> snapshots = new LinkedHashMap<>();
        snapshots.put("skill", context.skillTree().orElseThrow().snapshot());
        for (String key : List.of("entity:" + caster.getUniqueId(), "entity:" + target.getUniqueId(),
                "entity:" + trigger.getUniqueId(), "world:" + casterWorld.getUID(),
                "world:" + originWorld.getUID(), "global")) {
            snapshots.put(key, SkillExtras.scopedVariableSnapshot(key));
        }
        return snapshots;
    }

    private static World world(String name) {
        World world = mock(World.class);
        when(world.getUID()).thenReturn(UUID.randomUUID());
        when(world.getName()).thenReturn(name);
        return world;
    }

    private static LivingEntity entity(World world, double x) {
        LivingEntity entity = mock(LivingEntity.class);
        when(entity.getUniqueId()).thenReturn(UUID.randomUUID());
        when(entity.getWorld()).thenReturn(world);
        when(entity.getLocation()).thenReturn(new Location(world, x, 64, 0));
        when(entity.isValid()).thenReturn(true);
        return entity;
    }
}
