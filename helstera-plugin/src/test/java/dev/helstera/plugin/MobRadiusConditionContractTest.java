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
                "<caster.hp{r=0}}>", "min(2,)", "\"2 + 3", "5} junk{r=2", "1to5", "2-3")) {
            String source = "mobsInRadius{r=" + radius + ";t=ZOMBIE;a=0}";
            assertFalse(MmConditions.translate("?" + source).translated(), source);
            assertNull(skills.bindCondition(source), source);
        }
    }
}
