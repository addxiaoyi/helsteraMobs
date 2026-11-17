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
