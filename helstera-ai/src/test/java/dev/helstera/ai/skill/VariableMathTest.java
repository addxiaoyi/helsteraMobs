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

class VariableMathTest {
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
    void equationsPreserveNumericTypesAndOnlyMutateTheSelectedRegistry(String scope) {
        for (String candidate : SCOPES) {
            set(candidate, "Counter.Part", "INTEGER", "7");
            set(candidate, "Rate.Part", "FLOAT", "1.25");
        }
        run("variablemath{var=ignored.Counter.Part;s=" + scope + ";equation=x/2}");
        run("varmath{var=" + scope + ".Rate.Part;eq=x/2}");
        for (String candidate : SCOPES) {
            assertEquals(candidate.equals(scope) ? 3 : 7, value(candidate, "Counter.Part"), candidate);
            assertEquals(candidate.equals(scope) ? 0.625f : 1.25f, value(candidate, "Rate.Part"), candidate);
            assertInstanceOf(Integer.class, value(candidate, "Counter.Part"));
            assertInstanceOf(Float.class, value(candidate, "Rate.Part"));
            assertNull(value(candidate, "ignored.Counter.Part"));
        }
        assertTrue(SkillExtras.scopedVariableSnapshot("world:" + originWorld.getUID()).variables().isEmpty());
        assertTrue(SkillExtras.scopedVariableSnapshot("entity:" + trigger.getUniqueId()).variables().isEmpty());
    }

    @Test
    void equationAliasesUseNativePriorityAndLastDuplicateIncludingEmptyValues() {
        set("skill", "Counter", "INTEGER", "4");
        run("variablemath{var=skill.Counter;e=x+100;eq=x+10;equation=x+1}");
        assertEquals(5, value("skill", "Counter"));
        run("variablemath{var=skill.Counter;equation=x+1;eq=x+10;e=x+100}");
        assertEquals(6, value("skill", "Counter"));
        run("variablemath{var=skill.Counter;equation=unknown;EQUATION=x*2}");
        assertEquals(12, value("skill", "Counter"));
        run("variablemath{var=skill.Counter;e=x+100;eq=x-2}");
        assertEquals(10, value("skill", "Counter"));
        run("variablemath{var=skill.Counter;e=x+2}");
        assertEquals(12, value("skill", "Counter"));
        Map<String, Object> before = snapshots();
        for (String args : List.of("equation=x+1;EQUATION=", "eq=x+1;equation=", "e=x+1;eq=")) {
            assertNull(skills.bindAction("variablemath{var=skill.Counter;" + args + "}"));
            assertEquals(before, snapshots());
        }
    }

    @Test
    void quotedEquationsAndCanonicalLiteralDottedKeysSurviveBinding() {
        set("target", "skill.Counter.Part", "INTEGER", "7");
        run("variable-math var=skill.Counter.Part scope=target equation=\"x + 2\"");
        assertEquals(9, value("target", "skill.Counter.Part"));
        assertNull(value("target", "Counter.Part"));
        set("target", "Counter.Part", "INTEGER", "3");
        run("variablemath{var=skill.Counter.Part;scope=target;equation=\"x + 1\"}");
        assertEquals(4, value("target", "Counter.Part"));
        run("variablemath{var=skill.Counter.Part;scope=target;eq='x * 2'}");
        assertEquals(8, value("target", "Counter.Part"));
        assertEquals(9, value("target", "skill.Counter.Part"));
    }

    @Test
    void defaultEquationIgnoresLegacyOptionsWhileCanonicalExtensionKeepsThem() {
        set("skill", "Counter", "INTEGER", "7");
        run("variablemath{var=skill.Counter}");
        run("variablemath{var=skill.Counter;op=*;value=9}");
        assertEquals(7, value("skill", "Counter"));
        run("var-math var=skill.Counter op=* value=3");
        assertEquals(21.0, value("skill", "Counter"));
        run("var-math var=skill.Extension op=+ value=2");
        assertEquals(2.0, value("skill", "Extension"));
    }

    @Test
    void equationsAlwaysEvaluateConstantsModuloComparisonsAndFunctions() {
        set("skill", "Count", "INTEGER", "7");
        for (var sample : Map.of("7%4", 3, "1==1", 1, "pi", 3,
                "max(2,min(x+4,9))", 5, "2^3^2", 512).entrySet()) {
            set("skill", "Count", "INTEGER", "1");
            run("variablemath{var=skill.Count;equation=" + sample.getKey() + "}");
            assertEquals(sample.getValue(), value("skill", "Count"), sample.getKey());
        }
    }

    @Test
    void missingAndUnsupportedVariableTypesDoNotCreateOrReplaceValues() {
        for (String type : List.of("DOUBLE", "LONG", "STRING", "BOOLEAN", "LIST")) {
            set("skill", type, type, type.equals("BOOLEAN") ? "true" : type.equals("LIST") ? "1,2" : "7");
        }
        run("variable-location-set var=Point scope=skill @target");
        Map<String, Object> before = snapshots();
        for (String key : List.of("Absent", "DOUBLE", "LONG", "STRING", "BOOLEAN", "LIST", "Point")) {
            run("variablemath{var=skill." + key + ";equation=x+2;duration=40}");
            assertEquals(before, snapshots(), key);
        }
        var tree = context.skillTree().orElseThrow();
        tree.setVariable("Expired", 7, 2);
        run("variablemath{var=skill.Expired;equation=x+2}");
        assertFalse(tree.variables().containsKey("Expired"));
    }

    @Test
    void malformedEquationsFailBindingAndRuntimeDomainErrorsLeaveValuesAndDeadlinesUntouched() {
        set("skill", "Counter", "INTEGER", "7");
        set("skill", "Rate", "FLOAT", "1.25");
        Map<String, Object> before = snapshots();
        for (String equation : List.of("", "x+", "unknown", "max(x)", "eval(x+1)", "NaN", "Infinity")) {
            String source = "variablemath{var=skill.Counter;equation=" + equation + "}";
            int warnings = skills.warnings().size();
            assertNull(skills.bindAction(source), source);
            assertTrue(skills.warnings().size() > warnings, source);
            assertEquals(before, snapshots(), source);
        }
        for (String equation : List.of("x/0", "sqrt(-1)", "exp(10000)", "x+<target.var.Missing>")) {
            for (String key : List.of("Counter", "Rate")) {
                run("variablemath{var=skill." + key + ";equation=" + equation + ";duration=40}");
                assertEquals(before, snapshots(), equation);
            }
        }
        for (String reference : List.of("var=trigger.Counter", "var=skill.Counter;scope=", "var=")) {
            assertNull(skills.bindAction("variablemath{" + reference + ";equation=x+1}"));
            assertEquals(before, snapshots());
        }
    }

    @Test
    void retentionUsesNativeAliasPrecedenceAndTreatsEAsEquationAndDuration() {
        var tree = context.skillTree().orElseThrow();
        long original = System.currentTimeMillis() + 60_000;
        tree.setVariable("Counter", 7, original);
        for (String suffix : List.of("", ";duration=0", ";duration=-1", ";save=true", ";save=false",
                ";duration=bad;d=40", ";duration=2s", ";e=\"40\"")) {
            run("variablemath{var=skill.Counter;equation=x+1" + suffix + "}");
            assertEquals(original, tree.variableExpiration("Counter"), suffix);
        }
        run("variablemath{var=skill.Counter;e=x+1}");
        assertEquals(original, tree.variableExpiration("Counter"));
        long before = System.currentTimeMillis();
        run("variablemath{var=skill.Counter;e=40}");
        assertEquals(40, value("skill", "Counter"));
        assertTrue(tree.variableExpiration("Counter") >= before + 2_000);
        assertTrue(tree.variableExpiration("Counter") <= System.currentTimeMillis() + 2_000);
        before = System.currentTimeMillis();
        run("variablemath{var=skill.Counter;eq=x+1;e=80}");
        assertEquals(41, value("skill", "Counter"));
        assertTrue(tree.variableExpiration("Counter") >= before + 4_000);
        run("variablemath{var=skill.Counter;equation=x;duration=bad;DURATION=1200;d=40}");
        assertTrue(tree.variableExpiration("Counter") >= before + 60_000);
    }

    @Test
    void delayedRepeatedEquationsResolveLivePlaceholdersAndKeepTargetAndConditionMetadata() {
        set("caster", "Step", "INTEGER", "2");
        set("target", "Counter", "INTEGER", "3");
        run("set-variable var=Counter scope=target value=10", context.withTargetEntity(trigger));
        set("skill", "Gate", "INTEGER", "1");
        var pending = new ArrayDeque<Runnable>();
        var delays = new ArrayList<Long>();
        SkillCatalog.taskScheduler((task, delay) -> {
            pending.add(task);
            delays.add(delay);
            return true;
        });
        run("variablemath{var=ignored.Counter;scope=target;equation=x+<caster.var.Step>;delay=1;repeat=1;repeatinterval=2}"
                + " @trigger ?variableEquals{var=skill.Gate;value=1} 100%");
        assertEquals(10, value("trigger", "Counter"));
        pending.remove().run();
        assertEquals(12, value("trigger", "Counter"));
        set("caster", "Step", "INTEGER", "5");
        pending.remove().run();
        assertEquals(17, value("trigger", "Counter"));
        assertEquals(List.of(1L, 2L), delays);
        assertTrue(pending.isEmpty());
        assertEquals(3, value("target", "Counter"));
        set("skill", "Gate", "INTEGER", "0");
        run("variablemath{var=target.Counter;equation=x+1} @trigger ?variableEquals{var=skill.Gate;value=1}");
        assertEquals(17, value("trigger", "Counter"));
        assertSame(target, context.targetEntity().orElseThrow());
    }

    @Test
    void unavailableTargetRegistryAbortsWithoutMutatingRetainedCasterValues() {
        ModelInstance terminal = mock(ModelInstance.class, withSettings().extraInterfaces(EntityVariableContext.class));
        var variables = new SkillTreeContext(caster, List.of(caster), caster.getLocation());
        variables.setVariable("Counter", 7);
        var owner = (EntityVariableContext) terminal;
        when(terminal.isValid()).thenReturn(true);
        when(terminal.instanceId()).thenReturn(874);
        when(terminal.baseEntity()).thenReturn(Optional.of(caster));
        when(owner.variablesFor(caster)).thenReturn(Optional.of(variables));
        when(owner.registryAvailableFor(caster)).thenReturn(false);
        BehaviorContext unavailable = BehaviorContext.execution(terminal, null, 1, 4, 0,
                "unavailable-math", null, caster, List.of(caster), caster.getLocation()).withTargetEntity(caster);
        String bound = skills.bindAction("variablemath{var=target.Counter;equation=x+1}");
        assertNotNull(bound);
        assertFalse(skills.runAction(bound, unavailable));
        assertEquals(7, variables.variable("Counter").orElseThrow());
        assertTrue(SkillExtras.scopedVariableSnapshot("entity:" + caster.getUniqueId()).variables().isEmpty());
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
