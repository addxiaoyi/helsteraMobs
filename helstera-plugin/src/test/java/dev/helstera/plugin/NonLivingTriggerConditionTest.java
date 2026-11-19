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
import org.bukkit.entity.*;
import org.bukkit.event.Cancellable;
import org.bukkit.event.entity.EntityDamageEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NonLivingTriggerConditionTest {
    @Test void importedDirectAndInlineGatesUseTriggerAndPreserveActionContext(@TempDir Path root) throws Exception {
        var registry = new BehaviorRegistryImpl();
        var skills = new SkillService(registry, null);
        var observed = new ArrayList<BehaviorContext>();
        registry.registerAction("observe-trigger", observed::add);
        var world = mock(World.class);
        var origin = new Location(world, 0, 64, 0);
        var caster = mock(Zombie.class);
        var target = mock(Cow.class);
        when(caster.getLocation()).thenReturn(origin);
        when(caster.getHealth()).thenReturn(20.0);
        when(caster.getMaxHealth()).thenReturn(20.0);
        when(caster.getType()).thenReturn(EntityType.ZOMBIE);
        when(caster.isGlowing()).thenReturn(false);
        when(target.getType()).thenReturn(EntityType.COW);
        var model = mock(ModelInstance.class);
        when(model.isValid()).thenReturn(true);
        when(model.baseEntity()).thenReturn(Optional.of(caster));
        var event = mock(Cancellable.class);
        var audience = UUID.randomUUID();
        var item = mock(Item.class);
        when(item.getType()).thenReturn(EntityType.ITEM);
        when(item.getLocation()).thenReturn(new Location(world, 8, 90, 0));
        when(item.isGlowing()).thenReturn(true);
        when(item.isInWater()).thenReturn(true);
        var arrow = mock(Arrow.class);
        when(arrow.getType()).thenReturn(EntityType.ARROW);
        when(arrow.getLocation()).thenReturn(new Location(world, 8, 30, 0));
        // Removed triggers are dispatched too; validity is a predicate, not a global gate.
        when(arrow.isDead()).thenReturn(true);
        var armor = mock(ArmorStand.class);
        when(armor.getType()).thenReturn(EntityType.ARMOR_STAND);
        when(armor.getLocation()).thenReturn(origin);
        String[] conditions = {"entitytype{t=ITEM}", "entitytype{t=ARROW}", "isLiving",
                "height{h=>80}", "health{h=0}", "health{h=>0}", "inwater", "is-glowing", "is-dead"};
        boolean[][] expected = {{true,false,false,true,true,false,true,true,false},
                {false,true,false,false,true,false,false,false,true},
                {false,false,false,false,true,false,false,false,false}};
        var source = Files.createDirectories(root.resolve("Skills")).resolve("trigger.yml");
        int entityIndex = 0;
        for (Entity trigger : List.of(item, arrow, armor)) {
            var tree = new SkillTreeContext(trigger, List.of(target));
            var ctx = BehaviorContext.continueExecution(model, target, 1, 12, 7, "signal", "payload",
                    trigger, List.of(target), origin, tree, event, EntityDamageEvent.DamageCause.PROJECTILE)
                    .withNarrowedExecutionAudience(Set.of(audience));
            for (int i = 0; i < conditions.length; i++) for (boolean negate : List.of(false, true)) {
                String condition = conditions[i];
                // State aliases are already runtime conditions; import-native cases are tested separately below.
                boolean nativeSyntax = !condition.startsWith("is-");
                for (boolean inline : List.of(false, true)) for (boolean imported : List.of(false, true)) {
                    if (imported && !nativeSyntax) continue;
                    var yaml = new YamlConfiguration();
                    String prefix = imported ? "gate." : "skills.gate.";
                    if (inline) yaml.set(prefix + (imported ? "Skills" : "on-decision"),
                            List.of("skill{s=observe} ?~" + (negate ? "!" : "") + condition));
                    else {
                        yaml.set(prefix + (imported ? "TriggerConditions" : "trigger-conditions"),
                                List.of(imported ? condition + (negate ? " false" : " true") : (negate ? "!" : "") + condition));
                        yaml.set(prefix + (imported ? "Skills" : "on-decision"), List.of("skill{s=observe}"));
                    }
                    if (imported) {
                        yaml.save(source.toFile());
                        var plan = new MythicMobsImporter().convert(source).stream()
                                .filter(e -> "skills.yml".equals(e.get("target-file"))).findFirst().orElseThrow();
                        yaml.loadFromString(plan.get("content").toString());
                    }
                    yaml.set("skills.observe.on-decision", List.of("observe-trigger"));
                    skills.loadSkills(yaml.getConfigurationSection("skills"));
                    boolean pass = expected[entityIndex][i] != negate;
                    observed.clear();
                    if (!inline) assertEquals(pass, skills.preview("gate", ctx).wouldRun(), condition);
                    assertEquals(inline || pass, skills.castSkill("gate", ctx), condition);
                    assertEquals(pass ? 1 : 0, observed.size(), condition + " imported=" + imported + " inline=" + inline);
                    if (pass) {
                        var action = observed.getFirst();
                        assertSame(trigger, action.trigger().orElseThrow());
                        assertSame(target, action.targetEntity().orElseThrow());
                        assertEquals(List.of(target), action.targets());
                        assertEquals(origin, action.origin().orElseThrow());
                        assertSame(tree, action.skillTree().orElseThrow());
                        assertSame(event, action.cancellableEvent().orElseThrow());
                        assertEquals(ctx.damageCause(), action.damageCause());
                        assertEquals(ctx.executionAudience(), action.executionAudience());
                        assertEquals("payload", action.payload().orElseThrow());
                        assertEquals(BehaviorContext.ConditionScope.EXECUTION, action.conditionScope());
                    }
                    observed.clear();
                    var missing = BehaviorContext.continueExecution(model, target, 1, 12, 0, "test", null,
                            null, List.of(target), origin, null);
                    if (!inline) assertFalse(skills.preview("gate", missing).wouldRun());
                    assertEquals(inline, skills.castSkill("gate", missing));
                    assertTrue(observed.isEmpty(), "missing trigger never passes, including negation");
                }
            }
            entityIndex++;
        }
    }
}
