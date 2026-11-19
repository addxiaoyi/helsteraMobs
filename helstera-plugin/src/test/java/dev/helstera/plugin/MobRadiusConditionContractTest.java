package dev.helstera.plugin;

import dev.helstera.ai.BehaviorRegistryImpl;
import dev.helstera.ai.skill.SkillExtras;
import dev.helstera.ai.skill.SkillService;
import dev.helstera.api.behavior.BehaviorContext;
import dev.helstera.api.instance.ModelInstance;
import dev.helstera.migration.importer.MmConditions;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MobRadiusConditionContractTest {
    private final BehaviorRegistryImpl registry = new BehaviorRegistryImpl();
    private final SkillService skills = new SkillService(registry, null);
    private final World world = mock(World.class);
    private final Location center = new Location(world, 0, 64, 0);

    @AfterEach void resetHooks() { SkillExtras.resetRuntimeHooks(); }

    private Entity entity(EntityType type, double x, double z) {
        Entity entity = mock(Entity.class);
        when(entity.getUniqueId()).thenReturn(UUID.randomUUID());
        when(entity.getType()).thenReturn(type);
        when(entity.getLocation()).thenReturn(center.clone().add(x, 0, z));
        return entity;
    }

    private BehaviorContext context(Entity caster, Location origin) {
        ModelInstance instance = null;
        if (caster != null) {
            instance = mock(ModelInstance.class);
            when(instance.isValid()).thenReturn(true);
            when(instance.baseEntity()).thenReturn(Optional.of(caster));
        }
        return BehaviorContext.execution(instance, null, 1, -1, 0, "probe", null, null, List.of(), origin);
    }

    private boolean imported(String source, BehaviorContext context) {
        var mapped = MmConditions.translate("?" + source);
        assertTrue(mapped.translated(), source);
        String key = skills.bindCondition(mapped.condition());
        assertNotNull(key, skills.warnings().toString());
        boolean result = registry.testCondition(key, context);
        String direct = skills.bindCondition(source);
        assertNotNull(direct, source);
        assertEquals(result, registry.testCondition(direct, context), "direct binding: " + source);
        return result;
    }

    @Test void casterCenterExactCountsSphereAndNonMobTypesMatchNative() {
        Entity caster = entity(EntityType.ZOMBIE, 0, 0);
        Entity sameId = entity(EntityType.ZOMBIE, 0, 0);
        doReturn(caster.getUniqueId()).when(sameId).getUniqueId();
        doReturn(List.of(caster, sameId, entity(EntityType.ZOMBIE, 1, 0),
                        entity(EntityType.ZOMBIE, 2, 0), entity(EntityType.ZOMBIE, 4, 4),
                        entity(EntityType.ARMOR_STAND, 1, 0)))
                .when(world).getNearbyEntities(any(Location.class), anyDouble(), anyDouble(), anyDouble());
        var context = context(caster, center.clone().add(40, 0, 0));
        assertTrue(imported("mobsInRadius", context));
        assertTrue(imported("mobsInRadius{r=5;a=0}", context));
        assertFalse(imported("mobsInRadius{r=5;a=1;t=ZOMBIE}", context));
        assertTrue(imported("mobsInRadius{r=5;a=2;t=ZOMBIE}", context));
        assertFalse(imported("mobsInRadius{r=5;a=3;t=ZOMBIE}", context));
        assertTrue(imported("mobsInRadius{r=5;a=>1.5;t=ZOMBIE}", context));
        assertTrue(imported("mobsInRadius{r=5;a=<2.5;t=ZOMBIE}", context));
        assertTrue(imported("mobsInRadius{r=5;a=1.5to2.5;t=ZOMBIE}", context));
        assertTrue(imported("mobsInRadius{r=5;a=1;t=ARMOR_STAND}", context));
        assertTrue(imported("mobsInRadius{t=ZOMBIE} 2", context));
        assertTrue(imported("mobsInRadius{r=5;t=ZOMBIE} >1", context));
        assertTrue(imported("mobsInRadius{r=1;a=1;t=ZOMBIE}", context));
        verify(world, atLeastOnce()).getNearbyEntities(center, 5, 5, 5);
    }

    @Test void locationOverloadDoesNotSelectOrdinaryVanillaEntities() {
        Entity caster = entity(EntityType.ZOMBIE, 0, 0);
        doReturn(List.of(caster, entity(EntityType.ZOMBIE, 1, 0)))
                .when(world).getNearbyEntities(any(Location.class), anyDouble(), anyDouble(), anyDouble());
        for (var context : List.of(context(null, center), context(caster, center).withLocationTarget(center))) {
            assertFalse(imported("mobsInRadius{r=5;t=ZOMBIE;a=1}", context));
            assertTrue(imported("mobsInRadius{r=5;t=ZOMBIE;a=0}", context));
        }
    }

    @Test void customTypesWorkInBothScopesAndLocationMayIncludeCaster() {
        Entity custom = entity(EntityType.COW, 0, 0);
        doReturn(List.of(custom, entity(EntityType.ZOMBIE, 1, 0)))
                .when(world).getNearbyEntities(any(Location.class), anyDouble(), anyDouble(), anyDouble());
        SkillExtras.mythicMobTypeQuery(new SkillExtras.MythicMobTypeQuery() {
            public boolean isKnownType(String name) { return name.equals("ForestGuardian"); }
            public Optional<Boolean> matches(Entity entity, String name) { return Optional.of(entity == custom); }
        });
        assertTrue(imported("mobsInRadius{r=5;t=ForestGuardian;a=1}", context(null, center)));
        assertTrue(imported("mobsInRadius{r=5;t=ForestGuardian;a=0}", context(custom, center)));
        assertTrue(imported("mobsInRadius{r=5;t=ForestGuardian;a=1}",
                context(custom, center).withLocationTarget(center)));
        assertTrue(imported("mobsInRadius{r=5;t=ZOMBIE,ForestGuardian;a=1}", context(null, center)));
    }

    @Test void unavailableQueriesNeverBecomeSuccessfulThroughNegation() {
        Entity caster = entity(EntityType.ZOMBIE, 0, 0);
        var context = context(caster, center);
        when(world.getNearbyEntities(any(Location.class), anyDouble(), anyDouble(), anyDouble()))
                .thenThrow(new IllegalStateException("world unavailable"));
        for (String source : List.of("mobsInRadius{r=5;t=ZOMBIE;a=0}", "!mobsInRadius{r=5;t=ZOMBIE;a=0}")) {
            assertFalse(imported(source, context));
            assertFalse(imported(source, context(null, null)));
        }
        doReturn(List.of(entity(EntityType.COW, 1, 0)))
                .when(world).getNearbyEntities(any(Location.class), anyDouble(), anyDouble(), anyDouble());
        SkillExtras.mythicMobTypeQuery(new SkillExtras.MythicMobTypeQuery() {
            public boolean isKnownType(String name) { return true; }
            public Optional<Boolean> matches(Entity entity, String name) { return Optional.empty(); }
        });
        assertFalse(imported("mobsInRadius{r=5;t=ForestGuardian;a=0}", context));
        assertFalse(imported("!mobsInRadius{r=5;t=ForestGuardian;a=0}", context));
        SkillExtras.resetRuntimeHooks();
        assertFalse(imported("!mobsInRadius{r=5;t=ForestGuardian;a=0}", context));
    }

    @Test void countsBeyond512AreNotTruncated() {
        Entity caster = entity(EntityType.COW, 0, 0);
        doReturn(java.util.Collections.nCopies(513, entity(EntityType.ZOMBIE, 1, 0)))
                .when(world).getNearbyEntities(any(Location.class), anyDouble(), anyDouble(), anyDouble());
        assertTrue(imported("mobsInRadius{r=5;t=ZOMBIE;a=513}", context(caster, center)));
        assertFalse(imported("mobsInRadius{r=5;t=ZOMBIE;a=512}", context(caster, center)));
    }

    @Test void legacyDefaultsAndNamedOverridesArePreserved() {
        assertEquals("mobs-in-radius 0 0", MmConditions.translate("?mobsInRadius").condition());
        assertEquals("mobs-in-radius 2 2", MmConditions.translate("?mobsInRadius 2").condition());
        assertEquals("mobs-in-radius 1 2", MmConditions.translate("?mobsInRadius{a=1} 2").condition());
        assertEquals("mobs-in-radius 1 5", MmConditions.translate("?mobsInRadius{a=1;r=5} 2").condition());
        for (String source : List.of("mobsInRadius{r=5;r=6}", "mobsInRadius{a=1;amount=2}",
                "mobsInRadius{t=ZOMBIE,,COW}", "mobsInRadius{a=3to2}", "mobsInRadius{r=NaN}",
                "mobsInRadius{r=1e309}", "mobsInRadius{r=5;x=1}", "mobsInRadius{}2",
                "mobsInRadius{t=ZOMBIE} >1", "mobsInRadius{r=5} 1 junk", "mobsInRadius{r=-1}",
                "mobsInRadius{a=1;}", "mobsInRadius{r=5}{a=1}")) {
            var mapped = MmConditions.translate("?" + source);
            assertFalse(mapped.translated(), source);
            assertEquals("?" + source, mapped.condition());
            assertNull(skills.bindCondition(source), source);
        }
        assertNull(skills.bindCondition("mobs-in-radius 1 5 ZOMBIE ignored"));
    }

    @Test void dynamicRadiusIsReevaluatedAndTargetHealthMeansCasterHealth() {
        LivingEntity caster = mock(LivingEntity.class);
        when(caster.getUniqueId()).thenReturn(UUID.randomUUID());
        when(caster.getLocation()).thenReturn(center);
        when(caster.getMaxHealth()).thenReturn(20.0);
        when(caster.getHealth()).thenReturn(1.4);
        LivingEntity target = mock(LivingEntity.class);
        when(target.getHealth()).thenReturn(10.0);
        when(target.getMaxHealth()).thenReturn(20.0);
        when(target.getLocation()).thenReturn(center.clone().add(40, 0, 0));
        var ctx = context(caster, center).withTargetEntity(target);
        doReturn(List.of(entity(EntityType.ZOMBIE, 1, 0), entity(EntityType.ZOMBIE, 2, 0)))
                .when(world).getNearbyEntities(any(Location.class), anyDouble(), anyDouble(), anyDouble());
        String source = "mobsInRadius{r=<target.hp{r=0}>;t=ZOMBIE;a=1}";
        assertTrue(imported(source, ctx));
        assertTrue(imported("mobsInRadius{r=\"min(<caster.hp>, 5)\";t=ZOMBIE;a=1}", ctx));
        when(caster.getHealth()).thenReturn(2.4);
        assertFalse(imported(source, ctx));
        assertTrue(imported("mobsInRadius{r=<caster.hp{r=0}>;t=ZOMBIE;a=2}", ctx));
        verify(world, atLeastOnce()).getNearbyEntities(center, 1, 1, 1);
        verify(world, atLeastOnce()).getNearbyEntities(center, 2, 2, 2);
    }

    @Test void locationFormulaWorksButMetadataIsUnavailableEvenWithRetainedCaster() {
        Entity custom = entity(EntityType.COW, 2, 0);
        doReturn(List.of(custom)).when(world).getNearbyEntities(any(Location.class), anyDouble(), anyDouble(), anyDouble());
        SkillExtras.mythicMobTypeQuery(new SkillExtras.MythicMobTypeQuery() {
            public boolean isKnownType(String name) { return true; }
            public Optional<Boolean> matches(Entity entity, String name) { return Optional.of(entity == custom); }
        });
        for (var ctx : List.of(context(null, center), context(custom, center).withLocationTarget(center))) {
            assertTrue(imported("mobsInRadius{r=min(2, 5);a=1;t=TestMob}", ctx));
            for (String prefix : List.of("", "!")) {
                assertFalse(imported(prefix + "mobsInRadius{r=<caster.hp>;a=0;t=TestMob}", ctx));
                assertFalse(imported(prefix + "mobsInRadius{r=<random.2to2>;a=0;t=TestMob}", ctx));
            }
        }
    }

    @Test void badDynamicResultsRejectBeforeQueryAndRemainFalseUnderNegation() {
        Entity caster = entity(EntityType.ZOMBIE, 0, 0);
        var ctx = context(caster, center);
        for (String radius : List.of("<caster.var.missing>", "1/0", "(2-3)", "1e155*2", "sqrt(-1)")) {
            for (String prefix : List.of("", "!"))
                assertFalse(imported(prefix + "mobsInRadius{r=" + radius + ";t=ZOMBIE;a=0}", ctx));
        }
        verify(world, never()).getNearbyEntities(any(Location.class), anyDouble(), anyDouble(), anyDouble());
    }

    @Test void malformedNestedAndQuotedRadiusArgumentsRemainUnsupported() {
        for (String radius : List.of("<caster.hp{r=0;bogus=1}>", "<caster.hp{r=0>",
                "<caster.hp{r=0}}>", "min(2,)", "\"2 + 3", "5} junk{r=2", "2--3", "2toto3", "-2to3",
                "2to-3", "NaNto3", "1to" + "9".repeat(160), "1to" + "9".repeat(310))) {
            String source = "mobsInRadius{r=" + radius + ";t=ZOMBIE;a=0}";
            assertFalse(MmConditions.translate("?" + source).translated(), source);
            assertNull(skills.bindCondition(source), source);
        }
    }

    @Test void nativeRandomIntervalsWorkWithoutMetadataAndAreSampledAgain() {
        Entity caster = entity(EntityType.ZOMBIE, 0, 0);
        Entity custom = entity(EntityType.COW, 2, 0);
        doReturn(List.of(custom, entity(EntityType.ZOMBIE, 2, 0)))
                .when(world).getNearbyEntities(any(Location.class), anyDouble(), anyDouble(), anyDouble());
        SkillExtras.mythicMobTypeQuery(new SkillExtras.MythicMobTypeQuery() {
            public boolean isKnownType(String name) { return name.equals("TestMob"); }
            public Optional<Boolean> matches(Entity entity, String name) { return Optional.of(entity == custom); }
        });
        for (var ctx : List.of(context(caster, center), context(null, center), context(caster, center).withLocationTarget(center))) {
            assertTrue(imported("mobsInRadius{r=2to2;t=TestMob;a=1}", ctx));
            assertTrue(imported("mobsInRadius{r=3-2;t=TestMob;a=1}", ctx));
            assertFalse(imported("mobsInRadius{r=.1to1;t=TestMob;a=1}", ctx));
        }
        String key = skills.bindCondition("mobsInRadius{r=2to3;t=TestMob;a=1}");
        assertNotNull(key);
        clearInvocations(world);
        for (int i = 0; i < 32; i++) assertTrue(registry.testCondition(key, context(null, center)));
        var radii = org.mockito.ArgumentCaptor.forClass(Double.class);
        verify(world, times(32)).getNearbyEntities(eq(center), radii.capture(), anyDouble(), anyDouble());
        assertTrue(radii.getAllValues().stream().allMatch(radius -> radius >= 2 && radius <= 3));
        assertTrue(radii.getAllValues().stream().distinct().count() > 1, "must resample on each evaluation");
    }

    @Test void radiusUsesActiveQueryAndAcceptsKnownAliasesWithZeroPopulation() {
        Entity caster = entity(EntityType.ZOMBIE, 0, 0);
        Entity custom = entity(EntityType.COW, 2, 0);
        doReturn(List.of(custom)).when(world).getNearbyEntities(any(Location.class), anyDouble(), anyDouble(), anyDouble());
        var active = new java.util.concurrent.atomic.AtomicReference<Optional<Boolean>>(Optional.of(false));
        SkillExtras.mythicMobTypeQuery(new SkillExtras.MythicMobTypeQuery() {
            public boolean isKnownType(String name) { return name.equals("TestMob"); }
            public Optional<Boolean> matches(Entity entity, String name) { fail("Radius must use active query"); return Optional.empty(); }
            public boolean isKnownRadiusType(String name) { return isKnownType(name) || name.equals("BABY_ZOMBIE"); }
            public Optional<Boolean> matchesRadiusType(Entity entity, String name) { return active.get(); }
        });
        var ctx = context(caster, center);
        assertTrue(imported("mobsInRadius{r=5;a=0;t=BABY_ZOMBIE}", ctx));
        assertTrue(imported("!mobsInRadius{r=5;a=1;t=BABY_ZOMBIE}", ctx));
        assertFalse(imported("mobsInRadius{r=5;a=1;t=TestMob}", ctx));
        active.set(Optional.of(true));
        assertTrue(imported("mobsInRadius{r=5;a=1;t=TestMob}", ctx));
        active.set(Optional.empty());
        assertFalse(imported("mobsInRadius{r=5;a=0;t=TestMob}", ctx));
        assertFalse(imported("!mobsInRadius{r=5;a=0;t=TestMob}", ctx));
    }
    @Test void importedSkillConditionsUseCasterTargetAndTriggerDispatch(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path root) throws Exception {
        LivingEntity caster = mock(LivingEntity.class), near = mock(LivingEntity.class), far = mock(LivingEntity.class);
        for (LivingEntity entity : List.of(caster, near, far)) {
            when(entity.getUniqueId()).thenReturn(UUID.randomUUID());
            when(entity.getType()).thenReturn(EntityType.COW);
            when(entity.getWorld()).thenReturn(world);
            when(entity.getMaxHealth()).thenReturn(20.0);
            when(entity.getHealth()).thenReturn(20.0);
            when(entity.isValid()).thenReturn(true);
        }
        when(caster.getLocation()).thenReturn(center);
        when(near.getLocation()).thenReturn(center.clone().add(2, 0, 0));
        when(far.getLocation()).thenReturn(center.clone().add(40, 0, 0));
        doReturn(List.of(caster)).when(world).getNearbyEntities(any(Location.class), anyDouble(), anyDouble(), anyDouble());
        SkillExtras.mythicMobTypeQuery(new SkillExtras.MythicMobTypeQuery() {
            public boolean isKnownType(String name) { return name.equals("TestMob"); }
            public Optional<Boolean> matches(Entity entity, String name) { return Optional.of(entity == caster); }
        });
        var instance = context(caster, center).instance();
        var ctx = BehaviorContext.continueExecution(instance, near, 1, 2, 0, "dispatch", null,
                far, List.of(near, far), center, null);
        var observed = new java.util.ArrayList<BehaviorContext>();
        registry.registerAction("dispatch-observe", observed::add);
        var source = java.nio.file.Files.createDirectories(root.resolve("Skills")).resolve("dispatch.yml");
        java.nio.file.Files.writeString(source, """
                caster:
                  Conditions: ['mobsInRadius{r=5;a=0;t=TestMob} true']
                  Skills: ['message{m=probe}']
                target:
                  TargetConditions: ['mobsInRadius{r=5;a=1;t=TestMob} true']
                  Skills: ['message{m=probe}']
                trigger:
                  TriggerConditions: ['mobsInRadius{r=5;a=1;t=TestMob} true']
                  Skills: ['message{m=probe}']
                invalid:
                  Conditions: ['mobsInRadius{unknown=true} false']
                  Skills: ['message{m=probe}']
                player_caster:
                  Conditions: ['isPlayer']
                  Skills: ['message{m=probe}']
                null_target:
                  TargetConditions: null
                  Skills: ['message{m=probe}']
                null_trigger:
                  TriggerConditions: null
                  Skills: ['message{m=probe}']
                null_condition:
                  Conditions: null
                  Skills: ['message{m=probe}']
                """);
        var entries = new dev.helstera.migration.importer.MythicMobsImporter().convert(source);
        var plan = entries.stream().filter(e -> "skills.yml".equals(e.get("target-file"))).findFirst().orElseThrow();
        var yaml = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(
                new java.io.StringReader(plan.get("content").toString()));
        assertEquals(List.of("mobs-in-radius 0 5 TestMob"), yaml.getStringList("skills.caster.require"));
        for (String key : yaml.getConfigurationSection("skills").getKeys(false))
            yaml.set("skills." + key + ".on-decision", List.of("dispatch-observe"));
        skills.loadSkills(yaml.getConfigurationSection("skills"));
        assertTrue(skills.castSkill("caster", ctx));
        assertTrue(skills.castSkill("target", ctx));
        assertEquals(List.of(near), observed.getLast().targets());
        assertEquals(BehaviorContext.ConditionScope.EXECUTION, observed.getLast().conditionScope());
        when(near.isValid()).thenReturn(false);
        observed.clear();
        assertFalse(skills.castSkill("target", ctx), "Invalid near target cannot satisfy a condition");
        assertTrue(observed.isEmpty());
        when(near.isValid()).thenReturn(true);
        when(near.isDead()).thenReturn(true);
        assertFalse(skills.castSkill("target", ctx), "Dead targets are filtered before condition evaluation");
        when(near.isDead()).thenReturn(false);
        assertFalse(skills.castSkill("trigger", ctx));
        var nearTrigger = BehaviorContext.continueExecution(instance, far, 1, 40, 0, "dispatch", null,
                near, List.of(far), center, null);
        assertTrue(skills.castSkill("trigger", nearTrigger));
        assertFalse(skills.castSkill("invalid", ctx));
        assertFalse(skills.castSkill("null_condition", ctx));
        assertFalse(skills.castSkill("null_target", ctx));
        assertFalse(skills.castSkill("null_trigger", ctx));
        var player = mock(org.bukkit.entity.Player.class);
        var playerTarget = BehaviorContext.execution(instance, player, 1, 2, 0, "dispatch", null,
                player, List.of(player), center);
        assertFalse(skills.castSkill("player_caster", playerTarget), "Conditions must test caster, not selected player");
        var empty = BehaviorContext.continueExecution(instance, (LivingEntity) null, 1, -1, 0,
                "dispatch", null, null, List.of(), center, null);
        assertFalse(skills.castSkill("target", empty));
        assertFalse(skills.castSkill("trigger", empty));
        assertTrue(skills.castSkill("target", empty.withLocationTarget(near.getLocation())));
        assertEquals(near.getLocation(), observed.getLast().targetLocation().orElseThrow());
        assertFalse(skills.castSkill("target", empty.withLocationTarget(far.getLocation())));
        // Caster conditions ignore an unrelated explicit location target.
        assertTrue(skills.castSkill("caster", empty.withLocationTarget(near.getLocation())));
        observed.clear();
        String inline = skills.bindAction("dispatch-observe @Targets ?@mobsInRadius{r=5;a=1;t=TestMob}");
        assertNotNull(inline);
        assertTrue(skills.runAction(inline, ctx));
        assertEquals(List.of(near), observed.getLast().targets());
        assertEquals(BehaviorContext.ConditionScope.EXECUTION, observed.getLast().conditionScope());
    }

}
