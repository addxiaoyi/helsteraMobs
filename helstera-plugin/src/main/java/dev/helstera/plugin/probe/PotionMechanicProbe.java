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
import org.bukkit.entity.Zombie;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/** Focused, opt-in Paper evidence using production migration/binding and real entities. */
public final class PotionMechanicProbe {
    private PotionMechanicProbe() { }

    public static void run(JavaPlugin plugin, InstanceManager instances) {
        var checks = new LinkedHashMap<String, Boolean>();
        var entities = new ArrayList<Zombie>();
        ModelInstance instance = null;
        Path root = null;
        Path source = null;
        Listener cancellation = new Listener() { };
        String error = "";
        try {
            var world = plugin.getServer().getWorlds().getFirst();
            var origin = world.getSpawnLocation().clone().add(94, 4, 94);
            origin.getChunk().load();
            for (int i = 0; i < 3; i++) {
                var location = origin.clone().add(i * 2, 0, 0);
                location.getChunk().load();
                var entity = world.spawn(location, Zombie.class);
                entities.add(entity);
                entity.setAI(false);
                entity.setGravity(false);
                entity.setInvulnerable(true);
                entity.getAttribute(Attribute.GENERIC_MAX_ABSORPTION).setBaseValue(20);
            }
            var caster = entities.get(0);
            var target = entities.get(1);
            var control = entities.get(2);
            caster.setTarget(target);
            require(checks, "active-entities", entities.stream().allMatch(Zombie::isValid));
            instance = instances.bind("example/emberling", caster, SpawnOptions.defaults().persistent(false));
            root = Files.createTempDirectory("helstera-potion-");
            source = Files.createDirectory(root.resolve("Skills")).resolve("probe.yml");
            Files.writeString(source, """
                    skills:
                      defaults:
                        Skills: ['potion @Target']
                      flags:
                        Skills: ['potion{t=FAST_DIGGING;d=40;lvl=2;or=true;a=true;p=false;i=false} @Target']
                      dynamic:
                        Skills: ['potion{t=<skill.var.type>;d=<skill.var.ticks>;l=<skill.var.level>;force=true} @Target']
                      normal:
                        Skills: ['potion{t=SPEED;d=40;l=0} @Target']
                      forced:
                        Skills: ['potion{t=SPEED;d=40;l=0;force=true} @Target']
                      recipients:
                        Skills: ['potion{t=HASTE;d=<target.mhp{r=0}>;l=<target.hp{r=0}>;force=true} @Targets']
                      recipient_invalid:
                        Skills: ['potion{t=SPEED;d=30;l=<target.absorption>;force=true} @Targets']
                      recipient_type:
                        Skills: ['potion{t=<target.name>;d=35;l=1;force=true} @Targets']
                      expression:
                        Skills: ['potion{t=HASTE;d=(20 + <target.hp{r=0}>);l=max(1, <target.hp>/2);force=true} @Targets']
                      expression_invalid:
                        Skills: ['potion{t=HASTE;d=(1/0);l=1;force=true} @Targets']
                      invalid:
                        Skills:
                          - 'potion{type=UNKNOWN} @Target'
                          - 'potion{d=NaN} @Target'
                          - 'potion{level=-1} @Target'
                          - 'potion{type=SPEED;t=POISON} @Target'
                          - 'potion{force=maybe} @Target'
                          - 'potion{unknown=true} @Target'
                    """);
            var entries = new MythicMobsImporter().convert(source, root);
            var plan = entries.stream().filter(e -> "skills.yml".equals(e.get("target-file"))).findFirst().orElseThrow();
            var yaml = YamlConfiguration.loadConfiguration(new StringReader(String.valueOf(plan.get("content"))));
            require(checks, "invalid-preserved", yaml.getStringList("skills.invalid.unsupported").size() == 6
                    && yaml.getStringList("skills.invalid.on-decision").isEmpty()
                    && entries.stream().filter(e -> "WARN".equals(e.get("severity"))).count() == 6);
            var skills = new SkillService(new BehaviorRegistryImpl(), plugin.getLogger());
            skills.loadSkills(yaml.getConfigurationSection("skills"));
            var tree = new SkillTreeContext(target, List.of(target, control), origin);
            var context = BehaviorContext.continueExecution(instance, target, 1, 2, 0,
                    "potion-probe", null, target, List.of(target, control), origin, tree);
            require(checks, "imported-default", yaml.getStringList("skills.defaults.on-decision")
                    .equals(List.of("effect-targets SLOWNESS 100 0 @target")) && skills.warnings().isEmpty());
            require(checks, "default-cast", skills.castSkill("defaults", context));
            require(checks, "default", effect(target, PotionEffectType.SLOWNESS, 100, 0, false, true, true));
            require(checks, "target-isolation", caster.getActivePotionEffects().isEmpty()
                    && control.getActivePotionEffects().isEmpty());
            require(checks, "aliases-flags", skills.castSkill("flags", context)
                    && effect(target, PotionEffectType.HASTE, 40, 2, true, false, false));
            tree.setVariable("type", "SLOW");
            tree.setVariable("ticks", 80);
            tree.setVariable("level", 2);
            require(checks, "dynamic", skills.castSkill("dynamic", context)
                    && effect(target, PotionEffectType.SLOWNESS, 80, 2, false, true, true));
            target.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 400, 3, false, true, true));
            require(checks, "normal-retains-stronger", skills.castSkill("normal", context)
                    && effect(target, PotionEffectType.SPEED, 400, 3, false, true, true));
            require(checks, "force-replaces-stronger", skills.castSkill("forced", context)
                    && effect(target, PotionEffectType.SPEED, 40, 0, false, true, true));
            target.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 400, 3, false, true, true));
            boolean[] removalCancelled = {false};
            plugin.getServer().getPluginManager().registerEvent(EntityPotionEffectEvent.class, cancellation,
                    org.bukkit.event.EventPriority.HIGHEST, (listener, event) -> {
                        var change = (EntityPotionEffectEvent) event;
                        if (change.getEntity().equals(target) && change.getAction() == EntityPotionEffectEvent.Action.REMOVED
                                && change.getModifiedType().equals(PotionEffectType.SPEED)) {
                            change.setCancelled(true);
                            removalCancelled[0] = true;
                        }
                    }, plugin, false);
            require(checks, "cancelled-removal", skills.castSkill("forced", context)
                    && removalCancelled[0]
                    && effect(target, PotionEffectType.SPEED, 400, 3, false, true, true));
            HandlerList.unregisterAll(cancellation);
            for (String variable : List.of("type", "ticks", "level")) {
                tree.setVariable(variable, variable.equals("type") ? "UNKNOWN" : "<missing>");
                require(checks, "invalid-dynamic-" + variable, skills.castSkill("dynamic", context)
                        && effect(target, PotionEffectType.SLOWNESS, 80, 2, false, true, true));
                tree.setVariable(variable, variable.equals("type") ? "SLOW" : variable.equals("ticks") ? 80 : 2);
            }
            tree.setVariable("level", -1);
            require(checks, "negative-dynamic-level", skills.castSkill("dynamic", context)
                    && effect(target, PotionEffectType.SLOWNESS, 80, 2, false, true, true));
            String self = skills.bindAction("effect-self{t=SPEED;d=30;l=1;a=true;p=false;i=false;ow=true}");
            require(checks, "self", self != null && skills.runAction(self, context)
                    && effect(caster, PotionEffectType.SPEED, 30, 1, true, false, false)
                    && control.getActivePotionEffects().isEmpty());
            String multiple = skills.bindAction("potion{t=GLOWING;d=40} @Targets");
            tree.targets(List.of(target, control));
            require(checks, "multiple-targets", multiple != null && skills.runAction(multiple, context)
                    && target.hasPotionEffect(PotionEffectType.GLOWING) && control.hasPotionEffect(PotionEffectType.GLOWING)
                    && !caster.hasPotionEffect(PotionEffectType.GLOWING));
            target.getAttribute(Attribute.GENERIC_MAX_HEALTH).setBaseValue(20);
            control.getAttribute(Attribute.GENERIC_MAX_HEALTH).setBaseValue(30);
            target.setHealth(2);
            control.setHealth(4);
            require(checks, "expression-recipient-duration-level", skills.castSkill("expression", context)
                    && effect(target, PotionEffectType.HASTE, 22, 1, false, true, true)
                    && effect(control, PotionEffectType.HASTE, 24, 2, false, true, true));
            require(checks, "expression-invalid-preserves-existing", skills.castSkill("expression_invalid", context)
                    && effect(target, PotionEffectType.HASTE, 22, 1, false, true, true)
                    && effect(control, PotionEffectType.HASTE, 24, 2, false, true, true));
            require(checks, "recipient-duration-level", skills.castSkill("recipients", context)
                    && effect(target, PotionEffectType.HASTE, 20, 2, false, true, true)
                    && effect(control, PotionEffectType.HASTE, 30, 4, false, true, true));
            caster.setHealth(6);
            String dynamicSelf = skills.bindAction("effect-self{t=HASTE;d=30;l=<target.hp{r=0}>;force=true}");
            require(checks, "self-recipient-context", dynamicSelf != null && skills.runAction(dynamicSelf, context)
                    && effect(caster, PotionEffectType.HASTE, 30, 6, false, true, true));
            target.setCustomName("HASTE");
            control.setCustomName("SPEED");
            require(checks, "recipient-type", skills.castSkill("recipient_type", context)
                    && effect(target, PotionEffectType.HASTE, 35, 1, false, true, true)
                    && effect(control, PotionEffectType.SPEED, 35, 1, false, true, true));
            control.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 400, 3, false, true, true));
            target.setAbsorptionAmount(0.125);
            control.setAbsorptionAmount(2);
            require(checks, "recipient-invalid-first-preserves-effect", skills.castSkill("recipient_invalid", context)
                    && effect(target, PotionEffectType.SPEED, 400, 3, false, true, true)
                    && effect(control, PotionEffectType.SPEED, 30, 2, false, true, true));
            control.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 400, 3, false, true, true));
            target.setAbsorptionAmount(2);
            control.setAbsorptionAmount(0.25);
            require(checks, "recipient-invalid-second-preserves-effect", skills.castSkill("recipient_invalid", context)
                    && effect(target, PotionEffectType.SPEED, 30, 2, false, true, true)
                    && effect(control, PotionEffectType.SPEED, 400, 3, false, true, true));
            require(checks, "recipient-context-preserved", tree.targets().equals(List.of(target, control))
                    && context.targets().equals(List.of(target, control))
                    && context.targetEntity().orElseThrow().equals(target));
            boolean[] otherRemoved = {false};
            int[] invalidAdds = {0};
            plugin.getServer().getPluginManager().registerEvent(EntityPotionEffectEvent.class, cancellation,
                    org.bukkit.event.EventPriority.HIGHEST, (listener, event) -> {
                        var change = (EntityPotionEffectEvent) event;
                        if (change.getEntity().equals(control) && change.getAction() == EntityPotionEffectEvent.Action.ADDED) {
                            invalidAdds[0]++;
                        }
                        if (change.getEntity().equals(target) && change.getAction() == EntityPotionEffectEvent.Action.REMOVED
                                && change.getModifiedType().equals(PotionEffectType.HASTE)) {
                            otherRemoved[0] = true;
                            control.remove();
                        }
                    }, plugin, false);
            require(checks, "callback-removes-later-recipient", skills.castSkill("recipients", context)
                    && otherRemoved[0] && !control.isValid() && invalidAdds[0] == 0
                    && effect(target, PotionEffectType.HASTE, 20, 2, false, true, true));
            HandlerList.unregisterAll(cancellation);
            boolean[] currentRemoved = {false};
            plugin.getServer().getPluginManager().registerEvent(EntityPotionEffectEvent.class, cancellation,
                    org.bukkit.event.EventPriority.HIGHEST, (listener, event) -> {
                        var change = (EntityPotionEffectEvent) event;
                        if (change.getEntity().equals(target) && change.getAction() == EntityPotionEffectEvent.Action.ADDED) {
                            invalidAdds[0]++;
                        }
                        if (!currentRemoved[0] && change.getEntity().equals(target)
                                && change.getAction() == EntityPotionEffectEvent.Action.REMOVED
                                && change.getModifiedType().equals(PotionEffectType.HASTE)) {
                            currentRemoved[0] = true;
                            target.remove();
                        }
                    }, plugin, false);
            var callbackContext = BehaviorContext.continueExecution(instance, target, 1, 2, 0,
                    "potion-probe", null, target, List.of(target, caster), origin, tree);
            require(checks, "callback-removes-current-recipient", skills.castSkill("recipients", callbackContext)
                    && currentRemoved[0] && !target.isValid() && invalidAdds[0] == 0
                    && effect(caster, PotionEffectType.HASTE, 20, 6, false, true, true));
        } catch (Exception failure) {
            error = " error=" + failure.getClass().getSimpleName() + ":" + failure.getMessage();
            checks.put("execution", false);
        } finally {
            HandlerList.unregisterAll(cancellation);
            try {
                if (instance != null) instances.despawn(instance.instanceId());
                for (var entity : entities) entity.remove();
                if (source != null) Files.deleteIfExists(source);
                if (root != null) {
                    Files.deleteIfExists(root.resolve("Skills"));
                    Files.deleteIfExists(root);
                }
                checks.put("cleanup", entities.stream().noneMatch(Zombie::isValid)
                        && (instance == null || instances.getInstance(instance.instanceId()).isEmpty())
                        && (root == null || !Files.exists(root)));
            } catch (Exception failure) {
                checks.put("cleanup", false);
                error += " cleanup-error=" + failure.getClass().getSimpleName();
            }
        }
        StringBuilder observation = new StringBuilder("[自检] potion-mechanic");
        checks.forEach((name, value) -> observation.append(' ').append(name).append('=').append(value));
        plugin.getLogger().info(observation + error + " passed=" + checks.values().stream().allMatch(Boolean.TRUE::equals));
        TimedRecipientProbe.run(plugin, instances, true);
        PotionLifecycleProbe.run(plugin, instances, false);
        if (plugin.getConfig().getBoolean("debug.self-test-potion-lifecycle-native", false)) {
            PotionLifecycleProbe.run(plugin, instances, true);
        }
    }

    private static void require(LinkedHashMap<String, Boolean> checks, String name, boolean passed) {
        checks.put(name, passed);
        if (!passed) throw new IllegalStateException(name);
    }

    private static boolean effect(org.bukkit.entity.LivingEntity entity, PotionEffectType type,
                                  int duration, int level, boolean ambient, boolean particles, boolean icon) {
        var effect = entity.getPotionEffect(type);
        if (effect == null) throw new IllegalStateException("missing-effect:" + type.getKey()
                + ":entity-valid=" + entity.isValid() + ":effects=" + entity.getActivePotionEffects());
        boolean matches = effect.getDuration() == duration && effect.getAmplifier() == level
                && effect.isAmbient() == ambient && effect.hasParticles() == particles && effect.hasIcon() == icon;
        if (!matches) throw new IllegalStateException("effect-mismatch:" + type.getKey()
                + ":expected=" + duration + "/" + level + "/" + ambient + "/" + particles + "/" + icon
                + ":actual=" + effect.getDuration() + "/" + effect.getAmplifier() + "/" + effect.isAmbient()
                + "/" + effect.hasParticles() + "/" + effect.hasIcon());
        return true;
    }
}
