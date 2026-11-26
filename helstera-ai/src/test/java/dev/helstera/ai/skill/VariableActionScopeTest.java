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

class VariableActionScopeTest {
    private static final List<String> SCOPES = List.of("skill", "caster", "target", "world", "global");
    private static final List<String> NAME_ALIASES = List.of("name", "n", "variable", "var", "key", "k");
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
        casterWorld = world("caster-world");
        originWorld = world("origin-world");
        caster = entity(casterWorld, 1);
        target = entity(originWorld, 5);
        trigger = entity(originWorld, 9);
        int instanceId = caster.getUniqueId().hashCode();
        Location casterLocation = caster.getLocation();
        ModelInstance model = mock(ModelInstance.class);
        when(model.isValid()).thenReturn(true);
        when(model.instanceId()).thenReturn(instanceId);
        when(model.baseEntity()).thenReturn(Optional.of(caster));
        when(model.location()).thenReturn(casterLocation);
        context = BehaviorContext.execution(model, null, 1, 4, 0, "scope-test", null,
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
    void nativeMutationsUseThePrefixAndPreserveEveryRemainingDot(String scope) {
        seedScalars("Counter.Part");
        run("setvariable{var=" + scope + ".Counter.Part;value=20}");
        assertOnlyScalarChanged(scope, "Counter.Part", 20);
        run("variableadd{var=" + scope + ".Counter.Part;amount=2}");
        assertOnlyScalarChanged(scope, "Counter.Part", 22);
        run("variableunset{var=" + scope + ".Counter.Part}");
        assertOnlyScalarChanged(scope, "Counter.Part", null);

        run("setvariablelocation{var=" + scope + ".Point.Part} @target");
        assertOnlyLocationSet(scope, "Point.Part");
        run("locationunset{var=" + scope + ".Point.Part}");
        for (String candidate : SCOPES) assertTrue(location(candidate, "Point.Part").isEmpty());
        assertTrue(SkillExtras.scopedVariableSnapshot("world:" + originWorld.getUID()).variables().isEmpty());
        assertTrue(SkillExtras.scopedVariableSnapshot("world:" + originWorld.getUID()).locations().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"skill", "caster", "target", "world", "global"})
    void explicitScopeOverridesThePrefixAfterRemovingOnlyItsFirstSegment(String scope) {
        seedScalars("Counter.Part");
        run("setvariable{var=unknown.Counter.Part;scope=" + scope + ";value=20}");
        assertOnlyScalarChanged(scope, "Counter.Part", 20);
        run("variableadd{var=skill.Counter.Part;s=" + scope + ";amount=2}");
        assertOnlyScalarChanged(scope, "Counter.Part", 22);
        run("variableunset{var=target.Counter.Part;scope=" + scope + "}");
        assertOnlyScalarChanged(scope, "Counter.Part", null);
        run("setvariablelocation{var=world.Point.Part;s=" + scope + "} @target");
        assertOnlyLocationSet(scope, "Point.Part");
        run("locationunset{var=global.Point.Part;scope=" + scope + "}");
        for (String candidate : SCOPES) {
            assertTrue(location(candidate, "Point.Part").isEmpty());
            for (String prefix : List.of("unknown", "skill", "target", "world", "global")) {
                assertNull(value(candidate, prefix + ".Counter.Part"));
                assertTrue(location(candidate, prefix + ".Point.Part").isEmpty());
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"name", "n", "variable", "var", "key", "k"})
    void allNativeNameAliasesWorkAcrossScalarAndLocationMutations(String alias) {
        run("setvariable{" + alias + "=target.Counter.Part;value=7}");
        run("variableadd{" + alias + "=target.Counter.Part;amount=2}");
        assertEquals(9, value("target", "Counter.Part"));
        run("variableunset{" + alias + "=target.Counter.Part}");
        assertNull(value("target", "Counter.Part"));
        run("setvariablelocation{" + alias + "=target.Point.Part} @target");
        assertEquals(caster.getLocation(), location("target", "Point.Part").orElseThrow());
        run("unsetlocation{" + alias + "=target.Point.Part}");
        assertTrue(location("target", "Point.Part").isEmpty());
    }

    @Test
    void aliasPrecedenceIsNativeOrderRegardlessOfSourceOrder() {
        for (int first = 0; first < NAME_ALIASES.size(); first++) {
            StringBuilder arguments = new StringBuilder();
            for (int index = NAME_ALIASES.size() - 1; index >= first; index--) {
                arguments.append(NAME_ALIASES.get(index)).append("=skill.Alias")
                        .append(first).append('.').append(index).append(';');
            }
            run("setvariable{" + arguments + "value=7}");
            assertEquals(7, value("skill", "Alias" + first + "." + first));
            for (int ignored = first + 1; ignored < NAME_ALIASES.size(); ignored++) {
                assertNull(value("skill", "Alias" + first + "." + ignored));
            }
        }
        run("setvariable{var=skill.Scope.Part;scope=target;s=global;value=8}");
        assertEquals(8, value("target", "Scope.Part"));
        assertNull(value("global", "Scope.Part"));
        run("setvariable{var=skill.Scope.Part;s=global;scope=caster;value=9}");
        assertEquals(9, value("caster", "Scope.Part"));
        assertNull(value("global", "Scope.Part"));
    }

    @Test
    void repeatedKeysAreCaseInsensitiveAndLastWinsEvenWhenTheEarlierValueIsInvalid() {
        run("setvariable{var=trigger.Wrong;VaR=skill.Duplicate.Part;scope=invalid;SCOPE=target;value=7}");
        assertEquals(7, value("target", "Duplicate.Part"));
        assertNull(value("skill", "Duplicate.Part"));
        assertNull(value("trigger", "Wrong"));
        run("variableadd{n=;N=world.Duplicate.Part;s=;S=target;amount=2}");
        assertEquals(9, value("target", "Duplicate.Part"));
        run("variableunset{key=skill.Wrong;KEY=caster.Duplicate.Part;s=world;S=target}");
        assertNull(value("target", "Duplicate.Part"));
        run("setvariable{NAME=;name=global.伤害:基础-值.Part;value=11}");
        assertEquals(11, value("global", "伤害:基础-值.Part"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"setvariable", "variableadd", "variableunset", "setvariablelocation", "locationunset"})
    void invalidNativeReferencesFailBindingWithoutChangingAnyRegistry(String mechanic) {
        seedScalars("Counter.Part");
        for (String scope : SCOPES) {
            run("variable-location-set var=Point.Part scope=" + scope + " @target");
        }
        run("set-variable var=Counter.Part scope=target value=31", context.withTargetEntity(trigger));
        Map<String, Object> before = registrySnapshot();
        List<String> invalid = List.of(
                "var=unknown.Counter.Part", "var=trigger.Counter.Part", "var=skill.", "var=",
                "var=skill.Counter.Part;scope=", "var=skill.Counter.Part;s=",
                "var=skill.Counter.Part;scope=invalid", "var=skill.Counter.Part;s=trigger",
                "var=skill.Counter.Part;scope=\"target\"", "var=skill.Counter.Part;s='target'",
                "var=\"target.Counter.Part\"", "var='target.Counter.Part'",
                "var=<target.var.Name>", "var=skill.Counter.Part;VaR=",
                "var=skill.Counter.Part;VAR=trigger.Counter.Part",
                "var=skill.Counter.Part;scope=target;SCOPE=",
                "var=skill.Counter.Part;s=target;scope=invalid",
                "var=skill.Counter.Part;name=", "var=skill.Counter.Part;name=trigger.Counter.Part");
        for (String reference : invalid) {
            String source = mechanic + "{" + reference + ";value=1;amount=1} @self";
            int warnings = skills.warnings().size();
            assertNull(skills.bindAction(source), source);
            assertTrue(skills.warnings().size() > warnings, source);
            assertTrue(skills.warnings().get(skills.warnings().size() - 1)
                    .contains("invalid Mythic variable reference"), source);
            assertEquals(before, registrySnapshot(), source);
        }
        assertNull(skills.bindAction(mechanic + "{} @self"));
        assertEquals(before, registrySnapshot());
    }

    @Test
    void canonicalActionsPositionalExtensionsAndConditionsKeepTheirExistingKeys() {
        run("set-variable var=Counter.Part scope=target type=INTEGER value=7");
        run("variable-add var=Counter.Part scope=target amount=2");
        assertEquals(9, value("target", "Counter.Part"));
        run("set-variable{var=skill.Literal.Part;scope=target;value=4}");
        assertEquals(4, value("target", "skill.Literal.Part"));
        assertNull(value("target", "Literal.Part"));
        assertCondition("variableEquals{var=skill.Literal.Part;scope=target;value=4}", true);
        assertCondition("variableEquals{var=Literal.Part;scope=target;value=4}", false);
        assertCondition("variable-equals{var=Counter.Part;scope=target;value=9}", true);
        run("setvariable Positional 7");
        assertEquals(7, value("skill", "Positional"));
        run("var-add Extension 2");
        assertEquals(2.0, value("skill", "Extension"));
        run("variable-unset var=Counter.Part scope=target");
        assertNull(value("target", "Counter.Part"));
    }

    @Test
    void normalizedReferencesRetainTargetersConditionsDelayAndRepeatMetadata() {
        run("set-variable var=Counter.Part scope=target value=3");
        run("set-variable var=Counter.Part scope=target value=7", context.withTargetEntity(trigger));
        run("setvariable{var=skill.Gate;value=1}");
        var pending = new ArrayDeque<Runnable>();
        var delays = new ArrayList<Long>();
        SkillCatalog.taskScheduler((task, delay) -> {
            pending.add(task);
            delays.add(delay);
            return true;
        });
        run("variableadd{var=skill.Counter.Part;s=target;amount=2;delay=1;repeat=1;repeatinterval=2}"
                + " @trigger ?variableEquals{var=skill.Gate;value=1} 100%");
        assertEquals(7, value("trigger", "Counter.Part"));
        pending.remove().run();
        assertEquals(9, value("trigger", "Counter.Part"));
        pending.remove().run();
        assertEquals(11, value("trigger", "Counter.Part"));
        assertTrue(pending.isEmpty());
        assertEquals(List.of(1L, 2L), delays);
        assertEquals(3, value("target", "Counter.Part"));
        assertNull(value("skill", "Counter.Part"));
        run("setvariable{var=skill.Gate;value=0}");
        run("variableadd{var=skill.Counter.Part;s=target;amount=2}"
                + " @trigger ?variableEquals{var=skill.Gate;value=1}");
        assertEquals(11, value("trigger", "Counter.Part"));
        assertSame(target, context.targetEntity().orElseThrow());
        assertEquals(List.of(target), context.targets());
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

    private void run(String source) {
        run(source, context);
    }

    private void run(String source, BehaviorContext execution) {
        String bound = skills.bindAction(source);
        assertNotNull(bound, source + ": " + skills.warnings());
        assertTrue(registry.runAction(bound, execution), source);
    }

    private void assertCondition(String source, boolean expected) {
        String bound = skills.bindCondition(source);
        assertNotNull(bound, source + ": " + skills.warnings());
        assertEquals(expected, registry.testCondition(bound, context), source);
    }

    private Object value(String scope, String key) {
        return SkillExtras.placeholderVariable(context, scope, key);
    }

    private Optional<Location> location(String scope, String key) {
        return scope.equals("skill") ? context.skillTree().orElseThrow().location(key)
                : VariableLocations.get(scope, key, context);
    }

    private void seedScalars(String key) {
        for (int index = 0; index < SCOPES.size(); index++) {
            run("set-variable var=" + key + " scope=" + SCOPES.get(index) + " value=" + (10 + index));
        }
    }

    private void assertOnlyScalarChanged(String selected, String key, Object expected) {
        for (int index = 0; index < SCOPES.size(); index++) {
            String scope = SCOPES.get(index);
            assertEquals(scope.equals(selected) ? expected : 10 + index, value(scope, key), scope);
            assertNull(value(scope, selected + "." + key), scope);
        }
    }

    private void assertOnlyLocationSet(String selected, String key) {
        for (String scope : SCOPES) {
            assertEquals(scope.equals(selected) ? Optional.of(caster.getLocation()) : Optional.empty(),
                    location(scope, key), scope);
            assertTrue(location(scope, selected + "." + key).isEmpty(), scope);
        }
    }

    private Map<String, Object> registrySnapshot() {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("skill-variables", context.skillTree().orElseThrow().variables());
        snapshot.put("skill-locations", context.skillTree().orElseThrow().locations());
        for (String key : List.of("entity:" + caster.getUniqueId(), "entity:" + target.getUniqueId(),
                "entity:" + trigger.getUniqueId(), "world:" + casterWorld.getUID(),
                "world:" + originWorld.getUID(), "global")) {
            snapshot.put(key, SkillExtras.scopedVariableSnapshot(key));
        }
        return snapshot;
    }
}
