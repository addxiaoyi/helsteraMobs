package dev.helstera.plugin.probe;

import dev.helstera.ai.BehaviorRegistryImpl;
import dev.helstera.ai.skill.SkillService;
import dev.helstera.api.behavior.BehaviorContext;
import dev.helstera.api.behavior.SkillTreeContext;
import dev.helstera.api.instance.InstanceManager;
import dev.helstera.api.instance.ModelInstance;
import dev.helstera.api.instance.SpawnOptions;
import dev.helstera.migration.importer.MythicMobsImporter;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.LinkedHashSet;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.attribute.Attribute;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Cow;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffectType;

/** Real Bukkit scheduling evidence, independent of the synchronous recipient probes. */
public final class TimedRecipientProbe {
    private TimedRecipientProbe() { }

    public static void run(JavaPlugin plugin, InstanceManager instances, boolean potion) {
        new Session(plugin, instances, potion).start();
    }

    private record Application(Cow recipient, int duration, int level, int tick) { }

    @FunctionalInterface
    private interface Step {
        void run() throws Exception;
    }

    private static final class Session {
        private final JavaPlugin plugin;
        private final InstanceManager instances;
        private final boolean potion;
        private final LinkedHashMap<String, Boolean> checks = new LinkedHashMap<>();
        private final List<Cow> entities = new ArrayList<>();
        private final List<Application> applications = new ArrayList<>();
        private final LinkedHashSet<Chunk> tickets = new LinkedHashSet<>();
        private final Listener listener = new Listener() { };
        private final SkillService skills;
        private ModelInstance instance;
        private BehaviorContext context;
        private SkillTreeContext tree;
        private Path root;
        private Path source;
        private int started;
        private int firstRemovals;
        private boolean finished;

        Session(JavaPlugin plugin, InstanceManager instances, boolean potion) {
            this.plugin = plugin;
            this.instances = instances;
            this.potion = potion;
            skills = new SkillService(new BehaviorRegistryImpl(), plugin.getLogger());
        }

        void start() {
            guarded(() -> {
                var world = plugin.getServer().getWorlds().getFirst();
                var origin = world.getSpawnLocation().clone().add(potion ? 118 : 106, 12, 94);
                for (int i = 0; i < 3; i++) {
                    var location = origin.clone().add(i * 2, 0, 0);
                    location.getChunk().load();
                    if (location.getChunk().addPluginChunkTicket(plugin)) tickets.add(location.getChunk());
                    var entity = world.spawn(location, Cow.class);
                    entities.add(entity);
                    entity.setAI(false);
                    entity.setGravity(false);
                    entity.setInvulnerable(true);
                    entity.getAttribute(Attribute.GENERIC_MAX_HEALTH).setBaseValue(20);
                    entity.getAttribute(Attribute.GENERIC_MAX_ABSORPTION).setBaseValue(20);
                    entity.setHealth(i == 0 ? 8 : i * 2);
                }
                require("active-entities", entities.stream().allMatch(Cow::isValid));
                instance = instances.bind("example/emberling", caster(), SpawnOptions.defaults().persistent(false));
                caster().setHealth(8);
                root = Files.createTempDirectory("helstera-timed-recipient-");
                source = Files.createDirectory(root.resolve("Skills")).resolve("probe.yml");
                String line = potion
                        ? "potion{t=HASTE;d=(100+<target.hp{r=0}>);l=(<target.absorption>+<skill.var.offset>);force=true;delay=4;repeat=2;repeatinterval=4} @Targets"
                        : "heal{a=(<target.hp>/2+<skill.var.offset>);delay=4;repeat=2;repeatinterval=4} @Targets";
                Files.writeString(source, "timed:\n  Skills: ['" + line + "']\ninvalid_delay:\n  Skills: ['"
                        + line.replace(";repeat=2;repeatinterval=4", "") + "']\n");
                var entries = new MythicMobsImporter().convert(source, root);
                var plan = entries.stream().filter(e -> "skills.yml".equals(e.get("target-file"))).findFirst().orElseThrow();
                var yaml = YamlConfiguration.loadConfiguration(new StringReader(String.valueOf(plan.get("content"))));
                skills.loadSkills(yaml.getConfigurationSection("skills"));
                require("imported-binding", skills.warnings().isEmpty()
                        && entries.stream().noneMatch(e -> "WARN".equals(e.get("severity"))));
                tree = new SkillTreeContext(first(), List.of(first(), second()), origin);
                tree.setVariable("offset", 0);
                context = BehaviorContext.continueExecution(instance, first(), 0.1, 2, 0,
                        "timed-recipient-probe", null, first(), List.of(first(), second()), origin, tree);
                if (potion) {
                    plugin.getServer().getPluginManager().registerEvent(EntityPotionEffectEvent.class, listener,
                            EventPriority.MONITOR, (ignored, event) -> {
                                var change = (EntityPotionEffectEvent) event;
                                if (change.isCancelled() || !change.getModifiedType().equals(PotionEffectType.HASTE)) return;
                                if (!change.getEntity().equals(first()) && !change.getEntity().equals(second())) return;
                                if (change.getAction() == EntityPotionEffectEvent.Action.REMOVED
                                        && change.getEntity().equals(first())) firstRemovals++;
                                var effect = change.getNewEffect();
                                if (effect != null) applications.add(new Application((Cow) change.getEntity(),
                                        effect.getDuration(), effect.getAmplifier(), Bukkit.getCurrentTick()));
                            }, plugin, false);
                }
                started = Bukkit.getCurrentTick();
                require("delayed-cast", skills.castSkill("timed", context));
                require("no-early-mutation", close(first().getHealth(), 2) && close(second().getHealth(), 4)
                        && first().getActivePotionEffects().isEmpty() && second().getActivePotionEffects().isEmpty());
                // These values differ from those at cast time; delay must not freeze the formula.
                first().setHealth(4);
                second().setHealth(6);
                first().setAbsorptionAmount(2);
                second().setAbsorptionAmount(3);
                later(this::afterFirst, 6);
            });
        }

        private void afterFirst() {
            if (potion) {
                require("delay-latest-recipients", applications.size() == 2
                        && application(0, first(), 104, 2, started + 4)
                        && application(1, second(), 106, 3, started + 4));
                require("delay-live-effects", level(first(), 2) && level(second(), 3));
                first().setHealth(6);
                second().setHealth(8);
                first().setAbsorptionAmount(0.125); // Invalid integral level must preserve Haste.
                second().setAbsorptionAmount(4);
            } else {
                require("delay-latest-recipients", close(first().getHealth(), 6) && close(second().getHealth(), 9));
                first().setHealth(8);
                second().setHealth(10);
            }
            require("first-execution-isolation", close(caster().getHealth(), 8)
                    && caster().getActivePotionEffects().isEmpty());
            later(this::afterSecond, 4);
        }

        private void afterSecond() {
            if (potion) {
                require("repeat-latest-recipients", applications.size() == 3
                        && application(2, second(), 108, 4, started + 8));
                require("invalid-repeat-preserves-effect", level(first(), 2) && firstRemovals == 0);
                require("invalid-repeat-does-not-block-later", level(second(), 4));
                second().setAbsorptionAmount(5);
                second().setHealth(10);
            } else {
                require("repeat-latest-recipients", close(first().getHealth(), 12) && close(second().getHealth(), 15));
                second().setHealth(12);
            }
            // The next repeat must skip a removed first recipient and re-read tree variables.
            first().remove();
            tree.setVariable("offset", 1);
            require("first-recipient-removed", !first().isValid() && second().isValid());
            later(this::afterThird, 4);
        }

        private void afterThird() {
            if (potion) {
                require("removed-recipient-skipped", applications.size() == 4
                        && application(3, second(), 110, 6, started + 12));
                require("repeat-latest-variable", level(second(), 6));
            } else {
                require("removed-recipient-skipped", close(second().getHealth(), 19));
                require("repeat-latest-variable", close(second().getHealth(), 12 + 12.0 / 2 + 1));
            }
            require("original-context-preserved", context.targetEntity().orElseThrow().equals(first())
                    && context.targets().equals(List.of(first(), second())));
            require("active-targets-refreshed", tree.targets().equals(List.of(second())));
            require("repeat-isolation", close(caster().getHealth(), 8) && caster().getActivePotionEffects().isEmpty());
            later(this::afterCompleted, 4);
        }

        private void afterCompleted() {
            require("repeat-count-bounded", Bukkit.getCurrentTick() >= started + 18
                    && (potion ? applications.size() == 4 && level(second(), 6) : close(second().getHealth(), 19)));
            require("invalid-delay-cast", skills.castSkill("invalid_delay", context));
            second().setHealth(12);
            second().setAbsorptionAmount(0.125);
            tree.setVariable("offset", potion ? 0 : "<missing>");
            later(this::afterInvalidDelay, 6);
        }

        private void afterInvalidDelay() {
            require("invalid-delay-preserves-state", close(second().getHealth(), 12)
                    && (!potion || applications.size() == 4 && level(second(), 6)));
            tree.setVariable("offset", 1);
            second().setAbsorptionAmount(5);
            require("cancel-pending-cast", skills.castSkill("timed", context));
            skills.forget(instance.instanceId());
            second().setHealth(14);
            second().setAbsorptionAmount(7);
            tree.setVariable("offset", 2);
            later(this::afterCancellation, 8);
        }

        private void afterCancellation() {
            require("cancelled-delay-no-mutation", close(second().getHealth(), 14)
                    && (!potion || applications.size() == 4 && level(second(), 6)));
            finish(null);
        }

        private boolean application(int index, Cow entity, int duration, int level, int tick) {
            var actual = applications.get(index);
            // Observe the event at execution, before vanilla decrements effect duration.
            return actual.recipient().equals(entity) && actual.duration() == duration
                    && actual.level() == level && actual.tick() == tick;
        }

        private static boolean level(Cow entity, int expected) {
            var effect = entity.getPotionEffect(PotionEffectType.HASTE);
            return entity.isValid() && effect != null && effect.getAmplifier() == expected && effect.getDuration() > 0;
        }

        private void later(Step step, long delay) {
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> guarded(step), delay);
        }

        private void guarded(Step step) {
            if (finished) return;
            try { step.run(); } catch (Exception failure) { checks.put("execution", false); finish(failure); }
        }

        private void finish(Exception failure) {
            if (finished) return;
            finished = true;
            HandlerList.unregisterAll(listener);
            String error = failure == null ? "" : " error=" + failure.getClass().getSimpleName() + ":" + failure.getMessage();
            try {
                if (instance != null) {
                    skills.forget(instance.instanceId());
                    instances.despawn(instance.instanceId());
                }
                entities.forEach(Cow::remove);
                tickets.forEach(chunk -> chunk.removePluginChunkTicket(plugin));
                if (source != null) Files.deleteIfExists(source);
                if (root != null) {
                    Files.deleteIfExists(root.resolve("Skills"));
                    Files.deleteIfExists(root);
                }
                checks.put("cleanup", entities.stream().noneMatch(Cow::isValid)
                        && tickets.stream().noneMatch(chunk -> chunk.getPluginChunkTickets().contains(plugin))
                        && (instance == null || instances.getInstance(instance.instanceId()).isEmpty())
                        && (root == null || !Files.exists(root)));
            } catch (Exception cleanupFailure) {
                checks.put("cleanup", false);
                error += " cleanup-error=" + cleanupFailure.getClass().getSimpleName();
            }
            var observation = new StringBuilder("[自检] ").append(potion ? "potion" : "numeric").append("-mechanic-timing");
            checks.forEach((name, passed) -> observation.append(' ').append(name).append('=').append(passed));
            plugin.getLogger().info(observation + error + " passed=" + checks.values().stream().allMatch(Boolean.TRUE::equals));
        }

        private void require(String name, boolean passed) {
            checks.put(name, passed);
            if (!passed) throw new IllegalStateException(name + ":tick=" + Bukkit.getCurrentTick()
                    + ":started=" + started + ":applications=" + applications
                    + ":entities=" + entities.stream().map(entity -> entity.isValid() + "/" + entity.getHealth() + "/"
                    + entity.getAbsorptionAmount() + "/" + entity.getActivePotionEffects()).toList());
        }

        private Cow caster() { return entities.get(0); }
        private Cow first() { return entities.get(1); }
        private Cow second() { return entities.get(2); }
        private static boolean close(double actual, double expected) { return Math.abs(actual - expected) < 1.0e-6; }
    }
}
