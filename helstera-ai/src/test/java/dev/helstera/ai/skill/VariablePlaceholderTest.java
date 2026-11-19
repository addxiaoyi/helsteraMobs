package dev.helstera.ai.skill;

import dev.helstera.ai.BehaviorRegistryImpl;
import dev.helstera.api.behavior.BehaviorContext;
import dev.helstera.api.behavior.VariableLocations;
import dev.helstera.api.instance.ModelInstance;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.LivingEntity;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class VariablePlaceholderTest {
    private final BehaviorRegistryImpl registry = new BehaviorRegistryImpl();
    private final SkillService skills = new SkillService(registry, null);

    private static World world() {
        World world = mock(World.class);
        when(world.getUID()).thenReturn(UUID.randomUUID());
        when(world.getName()).thenReturn("variable-world");
        return world;
    }
    private static LivingEntity entity(World world) {
        var entity = mock(LivingEntity.class);
        when(entity.getUniqueId()).thenReturn(UUID.randomUUID());
        when(entity.getWorld()).thenReturn(world);
        when(entity.getLocation()).thenReturn(new Location(world, 0, 64, 0));
        when(entity.isValid()).thenReturn(true);
        return entity;
    }
    private static BehaviorContext context(LivingEntity caster, LivingEntity target, LivingEntity trigger, World origin) {
        var model = mock(ModelInstance.class);
        when(model.isValid()).thenReturn(true);
        int instanceId = caster.getUniqueId().hashCode();
        when(model.instanceId()).thenReturn(instanceId);
        when(model.baseEntity()).thenReturn(Optional.of(caster));
        return BehaviorContext.execution(model, null, 1, 4, 0, "variables", null, trigger,
                List.of(target), new Location(origin, 0, 64, 0)).withTargetEntity(target);
    }
    private void action(String source, BehaviorContext ctx) {
        String bound = skills.bindAction(source);
        assertNotNull(bound, skills.warnings().toString());
        assertTrue(registry.runAction(bound, ctx), source);
    }
    private void set(BehaviorContext ctx, String variable, String type, String value) {
        action("setvariable{var=" + variable + ";type=" + type + ";value=" + value + "}", ctx);
    }

    @Test
    void entityRolesShareValuesAndConditionsWhileOtherEntitiesStayIsolated() {
        World world = world(); var caster = entity(world); var target = entity(world);
        var c = context(caster, target, caster, world);
        try {
            set(c, "caster.Count", "INTEGER", "7");
            set(c, "target.Count", "INTEGER", "3");
            assertEquals("7 3 7", Placeholders.resolve("<caster.var.Count> <target.var.Count> <trigger.var.Count>", c));
            var self = c.withTargetEntity(caster);
            assertEquals("7", Placeholders.resolve("<target.var.Count>", self));
            action("variableadd{var=target.Count;amount=2}", self);
            assertEquals("9 9", Placeholders.resolve("<caster.var.Count> <trigger.var.Count>", c));
            assertTrue(registry.testCondition(skills.bindCondition("variable-equals{var=caster.Count;value=9}"), c));
            action("variable-unset{var=target.Count}", self);
            assertEquals("<caster.var.Count> 3 <trigger.var.Count>",
                    Placeholders.resolve("<caster.var.Count> <target.var.Count> <trigger.var.Count>", c));
        } finally { SkillExtras.forgetEntity(caster); SkillExtras.forgetEntity(target); }
    }

    @Test
    void scopedNamesTypesDefaultsAndDottedArgumentsMatchNativeSelection() {
        World world = world(); var entity = entity(world); var c = context(entity, entity, entity, world);
        try {
            set(c, "caster.Count", "INTEGER", "7");
            set(c, "caster.count", "INTEGER", "2");
            set(c, "caster.Rate", "FLOAT", "0.125");
            set(c, "skill.Root.Part", "STRING", "nested");
            set(c, "caster.Root", "STRING", "root");
            assertEquals("7 2 0.125 0.125 root nested", Placeholders.resolve(
                    "<caster.var.Count> <target.var.count> <trigger.var.Rate> <caster.var.Rate{r=0}> "
                            + "<caster.var.Root.Part> <skill.var.Root.Part>", c));
            assertEquals("4 4.5 4", Placeholders.resolve(
                    "<caster.var.Absent|4.5> <skill.var.Absent|4.5> <target.var.Absent|4>", c));
            assertEquals("<target.var.Absent|>", Placeholders.resolve("<target.var.Absent|>", c));
            assertEquals("<target.var.Absent>", Placeholders.resolve("<target.var.Absent>", c));
            assertEquals(4, Placeholders.resolveNumber("<caster.var.Absent|4>", c, -1));
            c.skillTree().orElseThrow().setVariable("伤害", 9);
            assertEquals("9", Placeholders.resolve("<SKILL.VAR.伤害>", c));
        } finally { SkillExtras.forgetEntity(entity); }
    }

    @Test
    void worldRegistryFollowsCasterRatherThanOriginAndGlobalStillNeedsMetadata() {
        World first = world(), second = world(); var a = entity(first); var b = entity(second);
        String key = "World" + UUID.randomUUID().toString().replace("-", "");
        var c = context(a, b, b, second);
        var reversed = context(b, a, a, first);
        try {
            set(c, "world." + key, "STRING", "first");
            set(reversed, "world." + key, "STRING", "second");
            set(c, "global." + key + ".Part", "DOUBLE", "3.125");
            assertEquals("first", Placeholders.resolve("<world.var." + key + ">", c));
            assertEquals("second", Placeholders.resolve("<world.var." + key + ">", reversed));
            assertEquals("3.125", Placeholders.resolve("<global.var." + key + ".Part>", reversed));
            assertEquals("<global.var." + key + ".Part>", Placeholders.resolve("<global.var." + key + ".Part>", null));
        } finally {
            action("variable-unset{var=world." + key + "}", c);
            action("variable-unset{var=world." + key + "}", reversed);
            action("variable-unset{var=global." + key + ".Part}", c);
        }
    }

    @Test
    void unsupportedAndNonfiniteValuesDoNotUseDefaultAndDoNotBorrowTarget() {
        World world = world(); var entity = entity(world); var c = context(entity, entity, entity, world);
        try {
            set(c, "caster.Bad", "DOUBLE", "NaN");
            set(c, "caster.List", "LIST", "a,b");
            assertEquals("<caster.var.Bad|4> <caster.var.List|text>",
                    Placeholders.resolve("<caster.var.Bad|4> <caster.var.List|text>", c));
            assertEquals(-1, Placeholders.resolveNumber("<caster.var.Bad|4>", c, -1));
            var noTrigger = BehaviorContext.execution(c.instance(), null, 1, 4, 0, "test", null, null,
                    List.of(entity), entity.getLocation()).withTargetEntity(entity);
            assertEquals("<trigger.var.Count|4>", Placeholders.resolve("<trigger.var.Count|4>", noTrigger));
        } finally { SkillExtras.forgetEntity(entity); }
    }

    @Test
    void typeReplacementAndEntityCleanupRemoveAllRoleViews() {
        World world = world(); var entity = entity(world); var c = context(entity, entity, entity, world);
        try {
            set(c, "caster.Value", "INTEGER", "7");
            set(c, "target.Value", "LOCATION", "1,2,3");
            assertEquals("variable-world,1.0,2.0,3.0", Placeholders.resolve("<trigger.var.Value>", c));
            assertEquals("variable-world,1.0,2.0,3.0", Placeholders.resolve("<trigger.var.Value|4>", c));
            assertEquals(-1, Placeholders.resolveNumber("<trigger.var.Value|4>", c, -1));
            assertTrue(VariableLocations.get("caster", "Value", c).isPresent());
            set(c, "caster.Value", "STRING", "new");
            assertTrue(VariableLocations.get("target", "Value", c).isEmpty());
            assertEquals("new", Placeholders.resolve("<trigger.var.Value>", c));
            SkillExtras.forgetEntity(entity);
            assertEquals("<caster.var.Value> <target.var.Value> <trigger.var.Value>",
                    Placeholders.resolve("<caster.var.Value> <target.var.Value> <trigger.var.Value>", c));
        } finally { SkillExtras.forgetEntity(entity); VariableLocations.clearEntity(entity); }
    }

    @Test
    void skillTreesDoNotReadLegacyInstanceVariables() {
        World world = world(); var entity = entity(world); var c = context(entity, entity, entity, world);
        var legacy = BehaviorContext.of(c.instance(), entity, 1, 4, 0, "legacy");
        try {
            action("var-set Count 7", legacy);
            assertEquals("7.0", Placeholders.resolve("<skill.var.Count>", legacy));
            assertEquals("<skill.var.Count>", Placeholders.resolve("<skill.var.Count>", c));
            assertFalse(registry.testCondition(skills.bindCondition("has-var Count"), c));
            set(c, "skill.Count", "INTEGER", "4");
            assertEquals("4", Placeholders.resolve("<skill.var.Count>", c));
            assertEquals("7.0", Placeholders.resolve("<skill.var.Count>", legacy));
        } finally { SkillExtras.forget(c.instance().instanceId()); }
    }
    @Test
    void timedWritesReplacementAndArithmeticKeepTheCorrectDeadline() {
        World world = world(); var entity = entity(world); var c = context(entity, entity, entity, world);
        var tree = c.skillTree().orElseThrow();
        action("setvariable{var=skill.Count;type=INTEGER;value=7;duration=1200}", c);
        long deadline = tree.variableExpiration("Count");
        assertTrue(deadline > System.currentTimeMillis());
        action("variableadd{var=skill.Count;amount=2}", c);
        assertEquals(deadline, tree.variableExpiration("Count"));
        action("variableadd{var=skill.Count;amount=2;expire=2400}", c);
        assertTrue(tree.variableExpiration("Count") > deadline);
        action("setvariable{var=skill.Count;type=STRING;value=permanent}", c);
        assertEquals(1, tree.variableExpiration("Count"));
        tree.setVariable("Count", 3, 2);
        assertEquals("fallback", Placeholders.resolve("<skill.var.Count|fallback>", c));
        assertFalse(registry.testCondition(skills.bindCondition("has-var Count"), c));
        action("setvariable{var=skill.Count;type=INTEGER;value=9;d=-1}", c);
        assertEquals("9", Placeholders.resolve("<skill.var.Count>", c));
        assertEquals(-50, tree.variableExpiration("Count"));
    }

    @Test
    void locationDeadlinesReachAllReadersAndMalformedDurationsCannotBind() {
        World world = world(); var entity = entity(world); var c = context(entity, entity, entity, world);
        try {
            VariableLocations.set("caster", "Expired", c, entity.getLocation(), 2);
            assertTrue(VariableLocations.get("target", "Expired", c).isEmpty());
            assertEquals("fallback", Placeholders.resolve("<trigger.var.Expired|fallback>", c));
            action("setvariable{var=caster.Location;type=LOCATION;value=1,2,3;duration=1200}", c);
            assertTrue(VariableLocations.expirationTime("target", "Location", c) > System.currentTimeMillis());
            action("setvariablelocation{var=caster.Location;duration=20}", c);
            assertTrue(VariableLocations.get("caster", "Location", c).isEmpty());
            assertNull(skills.bindAction("setvariable{var=skill.Count;type=INTEGER;value=9;duration=NaN}"));
            assertNull(skills.bindAction("variableadd{var=skill.Count;amount=1;d=1;expire=2}"));
        } finally { SkillExtras.forgetEntity(entity); VariableLocations.clearEntity(entity); }
    }

    @Test
    void nativeAdditionPreservesNumericTypesAndDoesNotResurrectExpiredValues() {
        World world = world(); var entity = entity(world); var c = context(entity, entity, entity, world);
        var tree = c.skillTree().orElseThrow();
        action("setvariable{var=skill.Integer;value=7;duration=1200}", c);
        action("variableadd{var=skill.Integer;amount=2.9}", c);
        assertEquals(9, tree.variable("Integer").orElseThrow());
        set(c, "skill.Float", "FLOAT", "0.125");
        action("variableadd{var=skill.Float;amount=0.125}", c);
        assertEquals(0.25f, tree.variable("Float").orElseThrow());
        tree.setVariable("Expired", 7, 2);
        action("variableadd{var=skill.Expired;amount=2;duration=40}", c);
        action("variableadd{var=skill.Missing;amount=2}", c);
        assertTrue(tree.variable("Expired").isEmpty());
        assertTrue(tree.variable("Missing").isEmpty());
        set(c, "skill.Double", "DOUBLE", "7");
        action("variableadd{var=skill.Double;amount=2}", c);
        assertEquals(7.0, tree.variable("Double").orElseThrow());
        action("var-add Extension 2", c);
        assertEquals(2.0, tree.variable("Extension").orElseThrow());
    }

    @Test
    void scopedPersistencePreservesTypesAndAddRetentionWithoutLeakingEntityScopes(@org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
        SkillExtras.resetRuntimeHooks();
        World world = world(); var a = entity(world); var b = entity(world); var c = context(a, b, a, world);
        var file = directory.resolve("variables.yml").toFile();
        try {
            action("setvariable{var=world.Transient;value=7}", c);
            action("variableadd{var=world.Transient;amount=2;save=true}", c);
            action("setvariable{var=world.Saved;value=7;save=true}", c);
            action("variableadd{var=world.Saved;amount=2;save=false}", c);
            action("setvariable{var=caster.Count;value=7}", c);
            action("setvariable{var=target.Count;value=3;save=true}", c);
            action("setvariable{var=global.Rate;type=FLOAT;value=3.25;duration=1200;save=false}", c);
            SkillExtras.saveScopedVariables(file);
            var state = dev.helstera.api.behavior.ScopedVariableSnapshotStore.load(file);
            long deadline = state.get("global").variableRetention().get("Rate");
            SkillExtras.resetRuntimeHooks(); SkillExtras.loadScopedVariables(file);
            assertNull(SkillExtras.placeholderVariable(c, "world", "Transient"));
            assertEquals(9, SkillExtras.placeholderVariable(c, "world", "Saved"));
            assertEquals("7 3 7", Placeholders.resolve("<caster.var.Count> <target.var.Count> <trigger.var.Count>", c));
            assertEquals(3.25F, SkillExtras.placeholderVariable(c, "global", "Rate"));
            SkillExtras.saveScopedVariables(file);
            assertEquals(deadline, dev.helstera.api.behavior.ScopedVariableSnapshotStore.load(file).get("global").variableRetention().get("Rate"));
            action("variable-unset{var=global.Rate}", c); SkillExtras.forgetEntity(a);
            SkillExtras.saveScopedVariables(file); SkillExtras.resetRuntimeHooks(); SkillExtras.loadScopedVariables(file);
            assertNull(SkillExtras.placeholderVariable(c, "global", "Rate"));
            assertNull(SkillExtras.placeholderVariable(c, "caster", "Count"));
            assertEquals(3, SkillExtras.placeholderVariable(c, "target", "Count"));
            java.nio.file.Files.writeString(file.toPath(), "registry-format: 1\nregistries: [bad]\n");
            assertThrows(java.io.IOException.class, () -> SkillExtras.loadScopedVariables(file));
            assertEquals(3, SkillExtras.placeholderVariable(c, "target", "Count"));
        } finally { SkillExtras.resetRuntimeHooks(); }
    }

}
