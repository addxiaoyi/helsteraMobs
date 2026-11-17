package dev.helstera.plugin.probe;

import dev.helstera.ai.BehaviorRegistryImpl;
import dev.helstera.ai.skill.SkillService;
import dev.helstera.api.behavior.BehaviorContext;
import dev.helstera.api.behavior.SkillTreeContext;
import dev.helstera.api.instance.InstanceManager;
import dev.helstera.api.instance.ModelInstance;
import dev.helstera.api.instance.SpawnOptions;
import dev.helstera.migration.importer.MythicMobsImporter;
import org.bukkit.attribute.Attribute;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Cow;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/** Opt-in production importer/binding evidence; all assertions run within one server tick. */
public final class NumericMechanicProbe {
    private NumericMechanicProbe() { }

    public static void run(JavaPlugin plugin, InstanceManager instances) {
        var checks = new LinkedHashMap<String, Boolean>();
        var entities = new ArrayList<Cow>();
        ModelInstance instance = null;
        Path root = null;
        Path source = null;
        String error = "";
        try {
            var world = plugin.getServer().getWorlds().getFirst();
            var origin = world.getSpawnLocation().clone().add(94, 6, 94);
            for (int i = 0; i < 3; i++) {
                var location = origin.clone().add(i == 2 ? 10 : i * 2, 0, 0);
                location.getChunk().load();
                var entity = world.spawn(location, Cow.class);
                entities.add(entity);
                entity.setAI(false);
                entity.setGravity(false);
                entity.setVelocity(new Vector());
            }
            var caster = entities.get(0);
            var target = entities.get(1);
            var control = entities.get(2);
            require(checks, "active-entities", entities.stream().allMatch(Cow::isValid));
            instance = instances.bind("example/emberling", caster, SpawnOptions.defaults().persistent(false));
            for (var entity : entities) entity.setHealth(8);
            root = Files.createTempDirectory("helstera-numeric-");
            source = Files.createDirectory(root.resolve("Skills")).resolve("probe.yml");
            Files.writeString(source, """
                    skills:
                      reordered:
                        Skills: ['knockback{vertical=0.5;power=2} @Target']
                      omitted:
                        Skills: ['knockback{vertical=0.25;delay=0} @Target']
                      force:
                        Skills: ['forcepull{velocity=2} @Target']
                      vertical:
                        Skills: ['forcepull{yv=0.2} @Target']
                      radial:
                        Skills: ['pull{power=2}']
                      melee:
                        Skills: ['damagemelee{r=4}']
                      damage:
                        Skills: ['damage{value=2} @Target']
                      heal:
                        Skills: ['heal{health=2} @Target']
                      health:
                        Skills: ['sethealth{health=6} @Target']
                      dynamic:
                        Skills: ['knockback{y=<skill.var.up>;power=<skill.var.power>} @Target']
                      speed:
                        Skills: ['modifyspeed{factor=2} @Target']
                      status:
                        Skills:
                          - 'setfire{t=60} @Target'
                          - 'freeze{time=80} @Target'
                          - 'setmaxnodamageticks{time=30} @Target'
                          - 'setnodamageticks{t=5} @Target'
                      invalid:
                        Skills:
                          - 'knockback{power=1;strength=2} @Target'
                          - 'pull{power=NaN}'
                          - 'heal{a=}'
                          - 'sethealth{unknown=1} @Target'
                          - 'forcepull{v=2;yv=bad} @Target'
                    """);
            var entries = new MythicMobsImporter().convert(source, root);
            var plan = entries.stream().filter(e -> "skills.yml".equals(e.get("target-file"))).findFirst().orElseThrow();
            var yaml = YamlConfiguration.loadConfiguration(new StringReader(String.valueOf(plan.get("content"))));
            require(checks, "invalid-preserved", yaml.getStringList("skills.invalid.unsupported").size() == 5
                    && yaml.getStringList("skills.invalid.on-decision").isEmpty()
                    && entries.stream().filter(e -> "WARN".equals(e.get("severity"))).count() == 5);
            var skills = new SkillService(new BehaviorRegistryImpl(), plugin.getLogger());
            skills.loadSkills(yaml.getConfigurationSection("skills"));
            require(checks, "imported-binding", skills.warnings().isEmpty());
            var tree = new SkillTreeContext(target, List.of(target, control), origin);
            var context = BehaviorContext.continueExecution(instance, target, 1, 2, 0,
                    "numeric-probe", null, target, List.of(target, control), origin, tree);
            require(checks, "reordered", skills.castSkill("reordered", context)
                    && vector(target.getVelocity(), 2, 0.5, 0));
            require(checks, "omitted", skills.castSkill("omitted", context)
                    && vector(target.getVelocity(), 1, 0.25, 0));
            require(checks, "force-no-vertical", skills.castSkill("force", context)
                    && vector(target.getVelocity(), -2, 0, 0));
            require(checks, "force-only-vertical", skills.castSkill("vertical", context)
                    && vector(target.getVelocity(), -1, 0.2, 0));
            require(checks, "radial-default-radius", skills.castSkill("radial", context)
                    && vector(target.getVelocity(), -2, 0, 0)
                    && control.getVelocity().lengthSquared() == 0 && caster.getVelocity().lengthSquared() == 0);
            require(checks, "melee-default-damage", skills.castSkill("melee", context)
                    && close(target.getHealth(), 5) && close(caster.getHealth(), 8) && close(control.getHealth(), 8));
            target.setNoDamageTicks(0);
            require(checks, "absolute-damage", skills.castSkill("damage", context) && close(target.getHealth(), 3));
            require(checks, "heal-target", skills.castSkill("heal", context) && close(target.getHealth(), 5));
            require(checks, "set-health-target", skills.castSkill("health", context) && close(target.getHealth(), 6));
            require(checks, "self-heal", run(skills, "heal{a=1}", context)
                    && close(caster.getHealth(), 9) && close(target.getHealth(), 6) && close(control.getHealth(), 8));
            tree.setVariable("power", 3);
            tree.setVariable("up", 0.6);
            require(checks, "dynamic", skills.castSkill("dynamic", context)
                    && vector(target.getVelocity(), 3, 0.6, 0));
            tree.setVariable("power", 1);
            for (String invalid : List.of("NaN", "Infinity", "<missing.value>")) {
                tree.setVariable("up", invalid);
                require(checks, "dynamic-noop-" + checks.size(), skills.castSkill("dynamic", context)
                        && vector(target.getVelocity(), 3, 0.6, 0));
            }
            double speed = target.getAttribute(Attribute.GENERIC_MOVEMENT_SPEED).getBaseValue();
            double controlSpeed = control.getAttribute(Attribute.GENERIC_MOVEMENT_SPEED).getBaseValue();
            require(checks, "speed-target", skills.castSkill("speed", context)
                    && close(target.getAttribute(Attribute.GENERIC_MOVEMENT_SPEED).getBaseValue(), speed * 2)
                    && close(control.getAttribute(Attribute.GENERIC_MOVEMENT_SPEED).getBaseValue(), controlSpeed));
            caster.setFireTicks(0);
            control.setFireTicks(0);
            require(checks, "status-target", skills.castSkill("status", context)
                    && target.getFireTicks() == 60 && target.getFreezeTicks() == 80
                    && target.getMaximumNoDamageTicks() == 30 && target.getNoDamageTicks() == 5
                    && caster.getFireTicks() == 0 && control.getFireTicks() == 0
                    && caster.getFreezeTicks() == 0 && control.getFreezeTicks() == 0);
            require(checks, "invalid-health-noop", run(skills, "sethealth{a=<missing.value>} @Target", context)
                    && close(target.getHealth(), 6));
            require(checks, "invalid-direct", skills.bindAction("knockback{power=2;strength=1}") == null
                    && skills.bindAction("sethealth{a=NaN}") == null);
        } catch (Exception failure) {
            error = " error=" + failure.getClass().getSimpleName() + ":" + failure.getMessage();
            checks.put("execution", false);
        } finally {
            try {
                if (instance != null) instances.despawn(instance.instanceId());
                for (var entity : entities) entity.remove();
                if (source != null) Files.deleteIfExists(source);
                if (root != null) {
                    Files.deleteIfExists(root.resolve("Skills"));
                    Files.deleteIfExists(root);
                }
                checks.put("cleanup", entities.stream().noneMatch(Cow::isValid)
                        && (instance == null || instances.getInstance(instance.instanceId()).isEmpty())
                        && (root == null || !Files.exists(root)));
            } catch (Exception failure) {
                checks.put("cleanup", false);
                error += " cleanup-error=" + failure.getClass().getSimpleName();
            }
        }
        StringBuilder observation = new StringBuilder("[自检] numeric-mechanic");
        checks.forEach((name, value) -> observation.append(' ').append(name).append('=').append(value));
        plugin.getLogger().info(observation + error + " passed=" + checks.values().stream().allMatch(Boolean.TRUE::equals));
    }

    private static boolean run(SkillService skills, String line, BehaviorContext context) {
        String key = skills.bindAction(line);
        return key != null && skills.runAction(key, context);
    }

    private static void require(LinkedHashMap<String, Boolean> checks, String name, boolean passed) {
        checks.put(name, passed);
        if (!passed) throw new IllegalStateException(name);
    }

    private static boolean close(double actual, double expected) { return Math.abs(actual - expected) < 1.0e-6; }

    private static boolean vector(Vector actual, double x, double y, double z) {
        return close(actual.getX(), x) && close(actual.getY(), y) && close(actual.getZ(), z);
    }
}
