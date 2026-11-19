package dev.helstera.plugin.probe;

import dev.helstera.ai.BehaviorRegistryImpl;
import dev.helstera.ai.skill.SkillService;
import dev.helstera.api.behavior.BehaviorContext;
import dev.helstera.api.instance.InstanceManager;
import dev.helstera.api.instance.ModelInstance;
import dev.helstera.api.instance.SpawnOptions;
import dev.helstera.migration.importer.MythicMobsImporter;
import java.io.StringReader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Cow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Zombie;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffectType;

/** The same packaged YAML and assertions run through production import or the pinned MM oracle. */
public final class PotionLifecycleProbe {
    private PotionLifecycleProbe() { }

    public static void run(JavaPlugin plugin, InstanceManager instances, boolean nativeOracle) {
        new Session(plugin, instances, nativeOracle).start();
    }

    @FunctionalInterface
    private interface Step { void run() throws Exception; }

    private static final class Session {
        private final JavaPlugin plugin;
        private final InstanceManager instances;
        private final boolean nativeOracle;
        private final LinkedHashMap<String, Boolean> checks = new LinkedHashMap<>();
        private final List<LivingEntity> entities = new ArrayList<>();
        private final LinkedHashSet<Chunk> tickets = new LinkedHashSet<>();
        private final List<String> events = new ArrayList<>();
        private final Listener listener = new Listener() { };
        private final SkillService skills;
        private ModelInstance instance;
        private Path root;
        private Path source;
        private Object nativeApi;
        private Method nativeCast;
        private EntityPotionEffectEvent.Action cancelledAction;
        private boolean cancelExpiration;
        private boolean cancelHealth;
        private int cancelledEffects;
        private int expirations;
        private int removals;
        private int cancelledDamage;
        private int cancelledRegain;
        private int readinessDeadline;
        private boolean finished;

        Session(JavaPlugin plugin, InstanceManager instances, boolean nativeOracle) {
            this.plugin = plugin;
            this.instances = instances;
            this.nativeOracle = nativeOracle;
            skills = new SkillService(new BehaviorRegistryImpl(), plugin.getLogger());
        }

        void start() {
            guarded(() -> {
                String yamlSource;
                try (var stream = java.util.Objects.requireNonNull(
                        plugin.getResource("probes/potion-lifecycle-skills.yml"), "packaged skills")) {
                    yamlSource = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
                }
                root = Files.createTempDirectory("helstera-potion-lifecycle-");
                source = Files.createDirectory(root.resolve("Skills")).resolve("probe.yml");
                Files.writeString(source, yamlSource);
                var entries = new MythicMobsImporter().convert(source, root);
                var plan = entries.stream().filter(e -> "skills.yml".equals(e.get("target-file"))).findFirst().orElseThrow();
                var yaml = YamlConfiguration.loadConfiguration(new StringReader(String.valueOf(plan.get("content"))));
                skills.loadSkills(yaml.getConfigurationSection("skills"));
                boolean sourceReady = skills.warnings().isEmpty()
                        && entries.stream().noneMatch(e -> "WARN".equals(e.get("severity")));
                if (nativeOracle) {
                    var mythic = java.util.Objects.requireNonNull(
                            plugin.getServer().getPluginManager().getPlugin("MythicMobs"), "MythicMobs required");
                    sourceReady &= mythic.isEnabled() && yamlSource.equals(Files.readString(
                            mythic.getDataFolder().toPath().resolve("Skills/potion-lifecycle-skills.yml")));
                    nativeApi = mythic.getClass().getMethod("getAPIHelper").invoke(mythic);
                    nativeCast = nativeApi.getClass().getMethod("castSkill", Entity.class, String.class,
                            Entity.class, Location.class, Collection.class, Collection.class, float.class);
                }
                require("source-ready", sourceReady);
                var world = plugin.getServer().getWorlds().getFirst();
                var origin = world.getSpawnLocation().clone().add(nativeOracle ? 190 : 138, 12, 94);
                for (int i = 0; i < 4; i++) {
                    var location = origin.clone().add(i * 2, 0, 0);
                    location.getChunk().load();
                    if (location.getChunk().addPluginChunkTicket(plugin)) tickets.add(location.getChunk());
                    LivingEntity entity = i == 2 ? world.spawn(location, Zombie.class) : world.spawn(location, Cow.class);
                    entities.add(entity);
                    entity.setAI(false);
                    entity.setGravity(false);
                    entity.setRemoveWhenFarAway(false);
                    entity.getAttribute(Attribute.GENERIC_MAX_HEALTH).setBaseValue(20);
                    entity.setHealth(10);
                    if (entity instanceof Zombie zombie) zombie.setShouldBurnInDay(false);
                }
                require("active-entities", entities.stream().allMatch(Entity::isValid));
                instance = instances.bind("example/emberling", caster(), SpawnOptions.defaults().persistent(false));
                caster().setHealth(10);
                registerEvents();
                // Ticketed chunks can be loaded before their neighbours permit entity ticking.
                readinessDeadline = Bukkit.getCurrentTick() + 200;
                later(this::awaitTicking, 2);
            });
        }

        private void awaitTicking() {
            boolean ready = entities.stream().allMatch(entity ->
                    entity.getLocation().getChunk().getLoadLevel() == Chunk.LoadLevel.ENTITY_TICKING);
            if (!ready && Bukkit.getCurrentTick() < readinessDeadline) {
                later(this::awaitTicking, 2);
                return;
            }
            require("chunks-ticking", ready);
            later(this::beginExpiry, 2);
        }

        private void beginExpiry() throws Exception {
            require("expiry-cast", cast("lifecycle_expire", target()));
            var effect = target().getPotionEffect(PotionEffectType.HASTE);
            require("expiry-flags", effect != null && effect.getDuration() == 8
                    && effect.isAmbient() && !effect.hasParticles() && !effect.hasIcon());
            later(this::afterExpiry, 10);
        }

        private void registerEvents() {
            var manager = plugin.getServer().getPluginManager();
            manager.registerEvent(EntityPotionEffectEvent.class, listener, EventPriority.HIGHEST, (ignored, event) -> {
                var change = (EntityPotionEffectEvent) event;
                if (!entities.contains(change.getEntity())) return;
                if (change.getAction() == cancelledAction
                        || cancelExpiration && change.getCause() == EntityPotionEffectEvent.Cause.EXPIRATION) {
                    change.setCancelled(true);
                    cancelledEffects++;
                }
            }, plugin, false);
            manager.registerEvent(EntityPotionEffectEvent.class, listener, EventPriority.MONITOR, (ignored, event) -> {
                var change = (EntityPotionEffectEvent) event;
                if (!entities.contains(change.getEntity())) return;
                if (!change.isCancelled() && change.getCause() == EntityPotionEffectEvent.Cause.EXPIRATION) expirations++;
                if (!change.isCancelled() && change.getAction() == EntityPotionEffectEvent.Action.REMOVED
                        && change.getCause() == EntityPotionEffectEvent.Cause.PLUGIN) removals++;
                events.add(entities.indexOf(change.getEntity()) + ":" + change.getModifiedType().getKey().getKey()
                        + ":" + change.getAction() + ":" + change.getCause() + ":cancelled=" + change.isCancelled());
            }, plugin, false);
            manager.registerEvent(EntityDamageEvent.class, listener, EventPriority.HIGHEST, (ignored, event) -> {
                var damage = (EntityDamageEvent) event;
                if (cancelHealth && entities.contains(damage.getEntity())) {
                    damage.setCancelled(true);
                    cancelledDamage++;
                }
            }, plugin, false);
            manager.registerEvent(EntityRegainHealthEvent.class, listener, EventPriority.HIGHEST, (ignored, event) -> {
                var regain = (EntityRegainHealthEvent) event;
                if (cancelHealth && entities.contains(regain.getEntity())) {
                    regain.setCancelled(true);
                    cancelledRegain++;
                }
            }, plugin, false);
        }

        private void afterExpiry() throws Exception {
            require("expiry-event", expirations == 1);
            require("expiry-removed", !target().hasPotionEffect(PotionEffectType.HASTE));
            require("hidden-base", cast("lifecycle_hidden_base", target()) && level(PotionEffectType.SLOWNESS, 0));
            require("hidden-stronger", cast("lifecycle_hidden_strong", target()) && level(PotionEffectType.SLOWNESS, 2));
            later(this::afterHiddenRestored, 10);
        }

        private void afterHiddenRestored() {
            require("hidden-restored", level(PotionEffectType.SLOWNESS, 0));
            int duration = target().getPotionEffect(PotionEffectType.SLOWNESS).getDuration();
            require("hidden-duration-elapsed", duration >= 29 && duration <= 31);
            later(this::afterHiddenExpired, 32);
        }

        private void afterHiddenExpired() throws Exception {
            require("hidden-eventual-expiry", !target().hasPotionEffect(PotionEffectType.SLOWNESS));
            require("forced-hidden-setup", cast("lifecycle_hidden_base", target())
                    && cast("lifecycle_hidden_strong", target()) && level(PotionEffectType.SLOWNESS, 2));
            int before = removals;
            require("forced-replacement", cast("lifecycle_forced", target()) && level(PotionEffectType.SLOWNESS, 1));
            require("forced-removal-event", removals == before + 1);
            later(this::afterForcedExpired, 10);
        }

        private void afterForcedExpired() {
            require("forced-no-resurrection", !target().hasPotionEffect(PotionEffectType.SLOWNESS));
            later(this::afterForcedSettled, 32);
        }

        private void afterForcedSettled() throws Exception {
            require("forced-stays-expired", !target().hasPotionEffect(PotionEffectType.SLOWNESS));
            cancelledAction = EntityPotionEffectEvent.Action.ADDED;
            int before = cancelledEffects;
            require("cancel-added-cast", cast("lifecycle_expire", target()));
            require("cancel-added-event", cancelledEffects == before + 1);
            require("cancel-added-no-effect", !target().hasPotionEffect(PotionEffectType.HASTE));
            cancelledAction = null;
            require("cancel-changed-setup", cast("lifecycle_hidden_base", target()));
            cancelledAction = EntityPotionEffectEvent.Action.CHANGED;
            before = cancelledEffects;
            require("cancel-changed-cast", cast("lifecycle_changed", target()));
            require("cancel-changed-event", cancelledEffects == before + 1);
            require("cancel-changed-preserved", level(PotionEffectType.SLOWNESS, 0)
                    && target().getPotionEffect(PotionEffectType.SLOWNESS).getDuration() == 40);
            cancelledAction = null;
            target().removePotionEffect(PotionEffectType.SLOWNESS);
            cancelExpiration = true;
            require("cancel-expiration-cast", cast("lifecycle_expire", target()));
            cancelledEffects = 0;
            later(this::afterCancelledExpiry, 10);
        }

        private void afterCancelledExpiry() {
            require("cancel-expiration-event", cancelledEffects > 0);
            require("cancel-expiration-retained", target().hasPotionEffect(PotionEffectType.HASTE));
            cancelExpiration = false;
            later(this::afterReleasedExpiry, 3);
        }

        private void afterReleasedExpiry() throws Exception {
            require("uncancelled-expiration", !target().hasPotionEffect(PotionEffectType.HASTE));
            resetHealth();
            require("instant-health-cast", cast("lifecycle_health", target()) && cast("lifecycle_health", undead()));
            require("instant-health-pending", health(target(), 10) && health(undead(), 10)
                    && target().hasPotionEffect(PotionEffectType.INSTANT_HEALTH)
                    && undead().hasPotionEffect(PotionEffectType.INSTANT_HEALTH));
            later(this::afterInstantHealth, 3);
        }

        private void afterInstantHealth() throws Exception {
            require("instant-health-cow", health(target(), 14));
            require("instant-health-undead", health(undead(), 4));
            require("instant-health-expired", noInstantEffects());
            resetHealth();
            require("instant-damage-cast", cast("lifecycle_damage", target()) && cast("lifecycle_damage", undead()));
            require("instant-damage-pending", health(target(), 10) && health(undead(), 10)
                    && target().hasPotionEffect(PotionEffectType.INSTANT_DAMAGE)
                    && undead().hasPotionEffect(PotionEffectType.INSTANT_DAMAGE));
            later(this::afterInstantDamage, 3);
        }

        private void afterInstantDamage() throws Exception {
            require("instant-damage-cow", health(target(), 4));
            require("instant-damage-undead", health(undead(), 14));
            require("instant-damage-expired", noInstantEffects());
            resetHealth();
            cancelledAction = EntityPotionEffectEvent.Action.ADDED;
            cancelledEffects = 0;
            require("cancel-instant-cast", cast("lifecycle_damage", target()) && cast("lifecycle_health", undead()));
            require("cancel-instant-event", cancelledEffects == 2);
            cancelledAction = null;
            later(this::afterCancelledInstant, 3);
        }

        private void afterCancelledInstant() throws Exception {
            require("cancel-instant-preserved", health(target(), 10) && health(undead(), 10) && noInstantEffects());
            cancelHealth = true;
            require("cancel-health-cast", cast("lifecycle_damage", target()) && cast("lifecycle_damage", undead()));
            later(this::afterCancelledHealth, 3);
        }

        private void afterCancelledHealth() {
            require("cancel-health-damage-event", cancelledDamage == 1);
            require("cancel-health-regain-event", cancelledRegain == 1);
            require("cancel-health-preserved", health(target(), 10) && health(undead(), 10) && noInstantEffects());
            require("target-isolation", health(caster(), 10) && health(control(), 10)
                    && caster().getActivePotionEffects().isEmpty() && control().getActivePotionEffects().isEmpty());
            finish(null);
        }

        private boolean cast(String name, LivingEntity recipient) throws Exception {
            if (nativeOracle) return Boolean.TRUE.equals(nativeCast.invoke(nativeApi, caster(), name, recipient,
                    caster().getLocation(), List.of(recipient), List.of(), 1.0f));
            return skills.castSkill(name, BehaviorContext.continueExecution(instance, recipient, 0.5, 2, 0,
                    "potion-lifecycle", null, recipient, List.of(recipient), caster().getLocation(), null));
        }

        private void resetHealth() {
            for (var entity : List.of(target(), undead())) {
                entity.setNoDamageTicks(0);
                entity.setHealth(10);
            }
        }

        private boolean level(PotionEffectType type, int amplifier) {
            var effect = target().getPotionEffect(type);
            return target().isValid() && effect != null && effect.getAmplifier() == amplifier;
        }

        private boolean noInstantEffects() {
            return List.of(target(), undead()).stream().allMatch(entity ->
                    !entity.hasPotionEffect(PotionEffectType.INSTANT_HEALTH)
                            && !entity.hasPotionEffect(PotionEffectType.INSTANT_DAMAGE));
        }

        private void later(Step step, long delay) {
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> guarded(step), delay);
        }

        private void guarded(Step step) {
            if (finished) return;
            try { step.run(); } catch (Exception failure) { checks.put("execution", false); finish(failure); }
        }

        private void require(String name, boolean passed) {
            checks.put(name, passed);
            if (!passed) throw new IllegalStateException(name + ":tick=" + Bukkit.getCurrentTick()
                    + ":entities=" + entities.stream().map(entity -> entity.isValid() + "/" + entity.getHealth()
                    + "/" + entity.getActivePotionEffects() + "/" + entity.getLocation().getChunk().getLoadLevel()).toList());
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
                entities.forEach(Entity::remove);
                tickets.forEach(chunk -> chunk.removePluginChunkTicket(plugin));
                if (source != null) Files.deleteIfExists(source);
                if (root != null) {
                    Files.deleteIfExists(root.resolve("Skills"));
                    Files.deleteIfExists(root);
                }
                checks.put("cleanup", entities.stream().noneMatch(Entity::isValid)
                        && tickets.stream().noneMatch(chunk -> chunk.getPluginChunkTickets().contains(plugin))
                        && (instance == null || instances.getInstance(instance.instanceId()).isEmpty())
                        && (root == null || !Files.exists(root)));
            } catch (Exception cleanupFailure) {
                checks.put("cleanup", false);
                error += " cleanup-error=" + cleanupFailure.getClass().getSimpleName();
            }
            String name = nativeOracle ? "potion-lifecycle-native" : "potion-lifecycle";
            plugin.getLogger().info("[自检] " + name + "-events " + String.join(",", events));
            var observation = new StringBuilder("[自检] ").append(name);
            checks.forEach((key, passed) -> observation.append(' ').append(key).append('=').append(passed));
            plugin.getLogger().info(observation + error + " passed=" + checks.values().stream().allMatch(Boolean.TRUE::equals));
        }

        private LivingEntity caster() { return entities.get(0); }
        private LivingEntity target() { return entities.get(1); }
        private LivingEntity undead() { return entities.get(2); }
        private LivingEntity control() { return entities.get(3); }
        private static boolean health(LivingEntity entity, double expected) {
            return entity.isValid() && Math.abs(entity.getHealth() - expected) < 1.0e-6;
        }
    }
}
