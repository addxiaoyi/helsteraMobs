package dev.helstera.plugin;

import dev.helstera.ai.BehaviorRegistryImpl;
import dev.helstera.ai.skill.SkillService;
import dev.helstera.api.behavior.BehaviorContext;
import dev.helstera.api.behavior.SkillTreeContext;
import dev.helstera.api.instance.ModelInstance;
import dev.helstera.migration.importer.MythicMobsImporter;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.LivingEntity;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class NumericMechanicContractTest {
    @TempDir Path root;

    @Test
    void importedTimedFormulaRefreshesRecipientsVariablesAndCancellation() throws Exception {
        Path source = Files.createDirectories(root.resolve("Skills")).resolve("timing.yml");
        Files.writeString(source, """
                timed:
                  Skills: ['heal{a=(<target.hp>/2+<skill.var.offset>);delay=4;repeat=2;repeatinterval=4} @Targets']
                """);
        var entries = new MythicMobsImporter().convert(source, root);
        assertTrue(entries.stream().noneMatch(e -> "WARN".equals(e.get("severity"))));
        var plan = entries.stream().filter(e -> "skills.yml".equals(e.get("target-file"))).findFirst().orElseThrow();
        var yaml = YamlConfiguration.loadConfiguration(new StringReader(String.valueOf(plan.get("content"))));
        var service = new SkillService(new BehaviorRegistryImpl(), null);
        service.loadSkills(yaml.getConfigurationSection("skills"));
        assertTrue(service.warnings().isEmpty());
        var caster = mock(LivingEntity.class);
        var first = mock(LivingEntity.class);
        var second = mock(LivingEntity.class);
        var instance = mock(ModelInstance.class);
        var origin = new Location(mock(World.class), 0, 64, 0);
        when(instance.isValid()).thenReturn(true);
        when(instance.baseEntity()).thenReturn(Optional.of(caster));
        when(instance.location()).thenReturn(origin);
        for (var entity : List.of(first, second)) {
            when(entity.isValid()).thenReturn(true);
            when(entity.getMaxHealth()).thenReturn(20.0);
            when(entity.getWorld()).thenReturn(origin.getWorld());
            when(entity.getLocation()).thenReturn(origin.clone().add(2, 0, 0));
            when(entity.getHealth()).thenReturn(2.0);
        }
        var tree = new SkillTreeContext(first, List.of(first, second), origin);
        tree.setVariable("offset", 0);
        var context = BehaviorContext.continueExecution(instance, first, 0.1, 2, 0,
                "test", null, first, List.of(first, second), origin, tree);
        var pending = new java.util.ArrayDeque<Runnable>();
        var delays = new java.util.ArrayList<Long>();
        dev.helstera.ai.skill.SkillCatalog.taskScheduler((task, delay) -> {
            pending.add(task);
            delays.add(delay);
            return true;
        });
        try {
            assertTrue(service.castSkill("timed", context));
            verify(first, never()).setHealth(anyDouble());
            verify(second, never()).setHealth(anyDouble());
            when(first.getHealth()).thenReturn(4.0);
            when(second.getHealth()).thenReturn(6.0);
            pending.remove().run();
            verify(first).setHealth(6);
            verify(second).setHealth(9);
            when(first.getHealth()).thenReturn(8.0);
            when(second.getHealth()).thenReturn(10.0);
            pending.remove().run();
            verify(first).setHealth(12);
            verify(second).setHealth(15);
            clearInvocations(first, second);
            when(first.isValid()).thenReturn(false);
            when(second.getHealth()).thenReturn(12.0);
            tree.setVariable("offset", 1);
            pending.remove().run();
            verify(first, never()).setHealth(anyDouble());
            verify(second).setHealth(19);
            assertTrue(pending.isEmpty());
            assertEquals(List.of(4L, 4L, 4L), delays);
            assertEquals(List.of(first, second), context.targets());
            assertSame(first, context.targetEntity().orElseThrow());
            assertEquals(List.of(second), tree.targets());
            assertTrue(service.castSkill("timed", context));
            service.forget(instance.instanceId());
            clearInvocations(first, second);
            pending.remove().run();
            assertTrue(pending.isEmpty());
            verify(second, never()).setHealth(anyDouble());
            verify(caster, never()).setHealth(anyDouble());
        } finally {
            dev.helstera.ai.skill.SkillCatalog.taskScheduler(null);
        }
    }

    @Test
    void importedHealthPlaceholdersKeepSmallValuesAndOfficialUnits() throws Exception {
        Path source = Files.createDirectories(root.resolve("Skills")).resolve("precision.yml");
        Files.writeString(source, """
                raw:
                  Skills: ['heal{a=<caster.hp>} @Target']
                percent:
                  Skills: ['heal{a=<c.ph>} @Target']
                maximum:
                  Skills: ['sethealth{a=<caster.mhp>} @Target']
                target_raw:
                  Skills: ['damage{a=<target.thp>} @Target']
                fraction:
                  Skills: ['healpercent{m=<c.h>} @Target']
                rounded:
                  Skills: ['heal{a=<caster.hp{rounding=2}>} @Target']
                positional_rounded:
                  Skills: ['heal{<caster.hp{r=2}>} @Target']
                direct_positional:
                  Skills: ['heal <caster.hp{round=2}> @Target']
                random_float:
                  Skills: ['heal{a=<random.float{r=2}.0.125to0.125>} @Target']
                random_signed:
                  Skills: ['modifyhealth{a=<random.-1to-1>} @Target']
                rounded_fraction:
                  Skills: ['healpercent{m=<caster.hp{r=2}>} @Target']
                recipients:
                  Skills: ['heal{a=<target.hp{r=2}>} @Targets']
                invalid:
                  Skills: ['sethealth{a=<skill.var.amount>} @Target']
                """);
        var entries = new MythicMobsImporter().convert(source, root);
        assertTrue(entries.stream().noneMatch(e -> "WARN".equals(e.get("severity"))));
        var plan = entries.stream().filter(e -> "skills.yml".equals(e.get("target-file"))).findFirst().orElseThrow();
        var yaml = YamlConfiguration.loadConfiguration(new StringReader(String.valueOf(plan.get("content"))));
        var service = new SkillService(new BehaviorRegistryImpl(), null);
        service.loadSkills(yaml.getConfigurationSection("skills"));
        assertTrue(service.warnings().isEmpty(), service.warnings().toString());
        var caster = mock(LivingEntity.class);
        var target = mock(LivingEntity.class);
        var instance = mock(ModelInstance.class);
        var world = mock(World.class);
        var origin = new Location(world, 0, 64, 0);
        when(instance.isValid()).thenReturn(true);
        when(instance.baseEntity()).thenReturn(Optional.of(caster));
        when(instance.location()).thenReturn(origin);
        when(caster.isValid()).thenReturn(true);
        when(caster.getHealth()).thenReturn(0.125);
        when(caster.getMaxHealth()).thenReturn(20.0);
        when(target.isValid()).thenReturn(true);
        when(target.getWorld()).thenReturn(world);
        when(target.getLocation()).thenReturn(origin);
        when(target.getHealth()).thenReturn(3.125);
        when(target.getMaxHealth()).thenReturn(23.75);
        var tree = new SkillTreeContext(target, List.of(target), origin);
        var context = BehaviorContext.continueExecution(instance, target, 0.99, -1, 0, "precision", null,
                target, List.of(target), origin, tree);
        assertTrue(service.castSkill("raw", context));
        verify(target).setHealth(3.25);
        assertTrue(service.castSkill("percent", context));
        verify(target).setHealth(3.75);
        assertTrue(service.castSkill("maximum", context));
        verify(target).setHealth(20);
        assertTrue(service.castSkill("target_raw", context));
        verify(target).damage(3.125, caster);
        assertTrue(service.castSkill("fraction", context));
        verify(target).setHealth(6.09375);
        clearInvocations(target);
        for (String name : List.of("rounded", "positional_rounded", "direct_positional", "random_float")) {
            assertTrue(service.castSkill(name, context), name);
        }
        verify(target, times(4)).setHealth(3.255);
        assertTrue(service.castSkill("random_signed", context));
        verify(target).setHealth(2.125);
        assertTrue(service.castSkill("rounded_fraction", context));
        verify(target).setHealth(6.2125);
        clearInvocations(target);
        for (String invalid : List.of("NaN", "Infinity", "1e999", "<missing>")) {
            tree.setVariable("amount", invalid);
            assertTrue(service.castSkill("invalid", context));
        }
        verify(target, never()).setHealth(anyDouble());
        verify(caster, never()).setHealth(anyDouble());
        var second = mock(LivingEntity.class);
        when(second.isValid()).thenReturn(true);
        when(second.getWorld()).thenReturn(world);
        when(second.getLocation()).thenReturn(origin.clone().add(4, 0, 0));
        when(second.getHealth()).thenReturn(5.25);
        when(second.getMaxHealth()).thenReturn(30.0);
        tree.targets(List.of(target, second));
        context = BehaviorContext.continueExecution(instance, target, 0.99, -1, 0, "precision", null,
                target, List.of(target, second), origin, tree);
        assertTrue(service.castSkill("recipients", context));
        verify(target).setHealth(6.255);
        verify(second).setHealth(10.5);
        assertEquals(List.of(target, second), tree.targets());
    }

    @Test
    void importedExpressionsPreserveWhitespaceRoundingAndRecipientIsolation() throws Exception {
        Path source = Files.createDirectories(root.resolve("Skills")).resolve("expressions.yml");
        Files.writeString(source, """
                heal_math:
                  Skills: ['heal{a=max(1, <target.hp{r=0}> / 2)} @Targets']
                compare_math:
                  Skills: ['setfire{(<target.hp> >= 4) * 20} @Targets']
                fraction_math:
                  Skills: ['healpercent{m=(<target.hp> / 20)} @Targets']
                potion_math:
                  Skills: ['potion{t=HASTE;d=(20 + <target.hp>);l=(<target.hp> / 2)} @Targets']
                invalid_result:
                  Skills: ['knockback{power=(2+3);y=(1/0)} @Targets']
                invalid_source:
                  Skills: ['heal{a=max(1)} @Target', 'potion{d=(1+)} @Target']
                """);
        var entries = new MythicMobsImporter().convert(source, root);
        var plan = entries.stream().filter(e -> "skills.yml".equals(e.get("target-file"))).findFirst().orElseThrow();
        var yaml = YamlConfiguration.loadConfiguration(new StringReader(String.valueOf(plan.get("content"))));
        assertEquals(List.of("heal{a=max(1)} @Target", "potion{d=(1+)} @Target"),
                yaml.getStringList("skills.invalid_source.unsupported"));
        assertEquals(2, entries.stream().filter(e -> "WARN".equals(e.get("severity"))).count());
        var service = new SkillService(new BehaviorRegistryImpl(), null);
        service.loadSkills(yaml.getConfigurationSection("skills"));
        assertTrue(service.warnings().isEmpty(), service.warnings().toString());
        var world = mock(World.class);
        var target = mock(LivingEntity.class);
        var other = mock(LivingEntity.class);
        var origin = new Location(world, 0, 64, 0);
        for (var entity : List.of(target, other)) {
            when(entity.isValid()).thenReturn(true);
            when(entity.getWorld()).thenReturn(world);
            when(entity.getLocation()).thenReturn(origin);
            when(entity.getMaxHealth()).thenReturn(20.0);
        }
        when(target.getHealth()).thenReturn(3.125);
        when(other.getHealth()).thenReturn(8.0);
        var tree = new SkillTreeContext(target, List.of(target, other), origin);
        var context = BehaviorContext.continueExecution(null, target, 1, -1, 0, "expressions", null,
                target, List.of(target, other), origin, tree);
        assertTrue(service.castSkill("heal_math", context));
        verify(target).setHealth(4.625);
        verify(other).setHealth(12);
        assertTrue(service.castSkill("compare_math", context));
        verify(target).setFireTicks(0);
        verify(other).setFireTicks(20);
        assertTrue(service.castSkill("fraction_math", context));
        verify(target).setHealth(6.25);
        verify(other).setHealth(16);
        assertTrue(service.castSkill("invalid_result", context));
        verify(target, never()).setVelocity(any());
        verify(other, never()).setVelocity(any());
        assertEquals(List.of(target, other), tree.targets());
    }

    @Test
    void importedActionsExecuteWithDefaultsTargetIsolationAndFailClosedDynamicValues() throws Exception {
        Path source = Files.createDirectories(root.resolve("Skills")).resolve("numeric.yml");
        Files.writeString(source, """
                skills:
                  reordered:
                    Skills: ['knockback{vertical=0.5;power=2} @Target']
                  omitted:
                    Skills: ['knockback{vertical=0.25;delay=0} @Target']
                  dynamic:
                    Skills: ['knockback{y=0.5;power=<skill.var.power>} @Target']
                  healing:
                    Skills: ['heal{health=3} @Target']
                  radial:
                    Skills: ['damagemelee{r=4}']
                  tagged:
                    Skills: ['damage{a=2;rawtags="custom tag"} @Target']
                  fraction_heal:
                    Skills: ['healpercent{m=0.25} @Target']
                  fraction_damage:
                    Skills: ['damagepercent{p=0.25;current=true} @Target']
                  speed:
                    Skills: ['setspeed{m=2} @Target']
                  invalid:
                    Skills: ['sethealth{unknown=1} @Target']
                """);
        var entries = new MythicMobsImporter().convert(source, root);
        var plan = entries.stream().filter(e -> "skills.yml".equals(e.get("target-file"))).findFirst().orElseThrow();
        var yaml = YamlConfiguration.loadConfiguration(new StringReader(String.valueOf(plan.get("content"))));
        var service = new SkillService(new BehaviorRegistryImpl(), null);
        service.loadSkills(yaml.getConfigurationSection("skills"));
        assertTrue(service.warnings().isEmpty(), service.warnings().toString());
        var world = mock(World.class);
        var caster = mock(LivingEntity.class);
        var target = mock(LivingEntity.class);
        var control = mock(LivingEntity.class);
        var instance = mock(ModelInstance.class);
        var origin = new Location(world, 0, 64, 0);
        when(instance.isValid()).thenReturn(true);
        when(instance.baseEntity()).thenReturn(Optional.of(caster));
        when(instance.location()).thenReturn(origin);
        for (var entity : List.of(caster, target, control)) {
            when(entity.isValid()).thenReturn(true);
            when(entity.getWorld()).thenReturn(world);
            when(entity.getHealth()).thenReturn(5.0);
            when(entity.getMaxHealth()).thenReturn(20.0);
            when(entity.getLocation()).thenReturn(new Location(world, entity == caster ? 0 : 2, 64, 0));
        }
        when(world.getNearbyEntities(any(Location.class), anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(List.of(caster, target));
        var tree = new SkillTreeContext(target, List.of(target, control), origin);
        var context = BehaviorContext.continueExecution(instance, target, 1, 2, 0, "test", null,
                target, List.of(target, control), origin, tree);
        assertTrue(service.castSkill("reordered", context));
        verify(target).setVelocity(new Vector(2, 0.5, 0));
        assertTrue(service.castSkill("omitted", context));
        verify(target).setVelocity(new Vector(1, 0.25, 0));
        tree.setVariable("power", 3);
        assertTrue(service.castSkill("dynamic", context));
        verify(target).setVelocity(new Vector(3, 0.5, 0));
        clearInvocations(target);
        tree.setVariable("power", "NaN");
        assertTrue(service.castSkill("dynamic", context));
        verify(target, never()).setVelocity(any());
        assertTrue(service.castSkill("healing", context));
        verify(target).setHealth(8);
        assertTrue(service.castSkill("tagged", context));
        verify(target).damage(2, caster);
        assertTrue(service.castSkill("fraction_heal", context));
        verify(target).setHealth(10);
        assertTrue(service.castSkill("fraction_damage", context));
        verify(target).damage(1.25, caster);
        var speed = mock(org.bukkit.attribute.AttributeInstance.class);
        when(target.getAttribute(org.bukkit.attribute.Attribute.GENERIC_MOVEMENT_SPEED)).thenReturn(speed);
        assertTrue(service.castSkill("speed", context));
        verify(speed).setBaseValue((double) 0.4f);
        assertTrue(service.castSkill("radial", context));
        verify(target).damage(3, caster);
        verify(caster, never()).setHealth(anyDouble());
        verify(caster, never()).damage(anyDouble(), any(org.bukkit.entity.Entity.class));
        verify(control, never()).setHealth(anyDouble());
        verify(control, never()).setVelocity(any());
        assertEquals(List.of("sethealth{unknown=1} @Target"), yaml.getStringList("skills.invalid.unsupported"));
        assertTrue(yaml.getStringList("skills.invalid.on-decision").isEmpty());
    }
}
