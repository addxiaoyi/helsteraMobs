package dev.helstera.plugin.probe;

import dev.helstera.ai.BehaviorRegistryImpl;
import dev.helstera.ai.skill.SkillService;
import dev.helstera.api.behavior.BehaviorContext;
import dev.helstera.api.instance.InstanceManager;
import dev.helstera.api.instance.ModelInstance;
import dev.helstera.api.instance.SpawnOptions;
import dev.helstera.migration.importer.MythicMobsImporter;
import java.io.File;
import java.io.StringReader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Cow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;

/** Three-boot persistence probe. The recipient Cow is deliberately unbound. */
public final class PotionRestartProbe {
    private static final Set<String> STAGES = Set.of("prepare", "verify-clear", "clean");

    private PotionRestartProbe() { }

    public static void schedule(JavaPlugin plugin, InstanceManager instances, String stage) {
        if (!STAGES.contains(stage)) {
            plugin.getLogger().warning("[自检] potion-restart invalid-stage=" + stage + " passed=false");
            return;
        }
        new BukkitRunnable() {
            private int attempts;

            @Override public void run() {
                Player owner = Bukkit.getPlayerExact("SyncOwner");
                Player watcher = Bukkit.getPlayerExact("SyncWatcher");
                if (owner != null && watcher != null) {
                    cancel();
                    new Session(plugin, instances, stage, owner, watcher).start();
                } else if (++attempts >= 120) {
                    cancel();
                    plugin.getLogger().warning("[自检] potion-restart stage=" + stage
                            + " players-ready=false passed=false");
                }
            }
        // Match the already-passing Potion synchronization probe's startup
        // settling window before invoking MythicMobs' asynchronous skill API.
        }.runTaskTimer(plugin, 100, 20);
    }

    private static final class Session implements Listener {
        private final JavaPlugin plugin;
        private final InstanceManager instances;
        private final String stage;
        private final UUID ownerId;
        private final UUID watcherId;
        private final Set<UUID> acknowledgements = new HashSet<>();
        private Cow cow;
        private Cow caster;
        private ModelInstance casterInstance;
        private World world;
        private int ownerHasteAtReady;
        private int cowHasteAtReady;
        private int ownerInvisibilityAtReady;
        private int cowInvisibilityAtReady;
        private GameMode ownerGameModeBeforeProbe;
        private GameMode watcherGameModeBeforeProbe;
        private boolean finished;

        Session(JavaPlugin plugin, InstanceManager instances, String stage, Player owner, Player watcher) {
            this.plugin = plugin;
            this.instances = instances;
            this.stage = stage;
            ownerId = owner.getUniqueId();
            watcherId = watcher.getUniqueId();
        }

        private Player owner() { return java.util.Objects.requireNonNull(Bukkit.getPlayer(ownerId)); }
        private Player watcher() { return java.util.Objects.requireNonNull(Bukkit.getPlayer(watcherId)); }

        void start() {
            plugin.getServer().getPluginManager().registerEvents(this, plugin);
            later(() -> {
                try {
                    if (stage.equals("prepare")) prepare();
                    else {
                        restoreRecipient();
                    }
                    later(() -> {
                        verify(stage.equals("clean"));
                        announceReady();
                    }, 10);
                } catch (Exception failure) {
                    fail(failure);
                }
            }, 20);
            later(() -> fail(new IllegalStateException("client acknowledgement timeout")), 2400);
        }

        private void prepare() throws Exception {
            require(owner().getActivePotionEffects().isEmpty() && watcher().getActivePotionEffects().isEmpty(),
                    "first boot players must have no effects");
            ownerGameModeBeforeProbe = owner().getGameMode();
            watcherGameModeBeforeProbe = watcher().getGameMode();
            owner().setGameMode(GameMode.SURVIVAL);
            watcher().setGameMode(GameMode.SURVIVAL);
            world = owner().getWorld();
            Location at = owner().getLocation().clone().add(2, 0, 0);
            cow = world.spawn(at, Cow.class);
            cow.setAI(false);
            cow.setGravity(false);
            cow.setInvulnerable(true);
            cow.setRemoveWhenFarAway(false);

            caster = world.spawn(at.clone().add(2, 0, 2), Cow.class);
            caster.setAI(false);
            caster.setGravity(false);
            casterInstance = instances.bind("example/emberling", caster, SpawnOptions.defaults().persistent(false));
            // MythicMobs schedules native skill execution against its caster. Keep
            // that caster bound and valid until both clients acknowledge the
            // completed observation; deleting it immediately can cancel the
            // queued Potion mechanics while still returning true from castSkill.
            boolean nativeHasteAccepted = castNative(caster, owner(), "sync_visible");
            boolean nativeInvisibilityAccepted = castNative(caster, owner(), "sync_invisible");
            boolean importedHasteAccepted = castImported(casterInstance, cow, "sync_visible");
            boolean importedInvisibilityAccepted = castImported(casterInstance, cow, "sync_invisible");
            plugin.getLogger().info("[自检] potion-restart casts native-haste=" + nativeHasteAccepted
                    + " native-invisibility=" + nativeInvisibilityAccepted
                    + " imported-haste=" + importedHasteAccepted
                    + " imported-invisibility=" + importedInvisibilityAccepted);

            File stateFile = stateFile();
            YamlConfiguration state = new YamlConfiguration();
            state.set("owner", ownerId.toString());
            state.set("watcher", watcherId.toString());
            state.set("owner-game-mode", ownerGameModeBeforeProbe.name());
            state.set("watcher-game-mode", watcherGameModeBeforeProbe.name());
            state.set("cow", cow.getUniqueId().toString());
            state.set("world", world.getUID().toString());
            state.set("x", cow.getLocation().getX());
            state.set("y", cow.getLocation().getY());
            state.set("z", cow.getLocation().getZ());
            state.save(stateFile);
            world.save();
        }

        private boolean castNative(Cow caster, LivingEntity target, String skill) throws Exception {
            var mythic = java.util.Objects.requireNonNull(
                    plugin.getServer().getPluginManager().getPlugin("MythicMobs"), "MythicMobs required");
            Object api = mythic.getClass().getMethod("getAPIHelper").invoke(mythic);
            Method cast = api.getClass().getMethod("castSkill", Entity.class, String.class,
                    Entity.class, Location.class, Collection.class, Collection.class, float.class);
            boolean accepted = Boolean.TRUE.equals(cast.invoke(api, caster, skill, target, target.getLocation(),
                    List.of(target), List.of(), 1.0f));
            require(accepted, "MythicMobs rejected " + skill);
            return accepted;
        }

        private boolean castImported(ModelInstance instance, LivingEntity target, String skill) throws Exception {
            var mythic = java.util.Objects.requireNonNull(
                    plugin.getServer().getPluginManager().getPlugin("MythicMobs"), "MythicMobs required");
            var source = mythic.getDataFolder().toPath().resolve("Skills/potion-sync-skills.yml");
            String bundled;
            try (var stream = java.util.Objects.requireNonNull(plugin.getResource("probes/potion-sync-skills.yml"))) {
                bundled = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            }
            require(bundled.equals(Files.readString(source)), "installed Mythic skill source changed");
            var entries = new MythicMobsImporter().convert(source, mythic.getDataFolder().toPath());
            var plan = entries.stream().filter(entry -> "skills.yml".equals(entry.get("target-file")))
                    .findFirst().orElseThrow();
            var yaml = YamlConfiguration.loadConfiguration(new StringReader(String.valueOf(plan.get("content"))));
            SkillService skills = new SkillService(new BehaviorRegistryImpl(), plugin.getLogger());
            skills.loadSkills(yaml.getConfigurationSection("skills"));
            require(skills.warnings().isEmpty(), "importer emitted a Potion warning");
            var context = BehaviorContext.continueExecution(instance, target, 1, 3, 0, "potion-restart", null,
                    target, List.of(target), target.getLocation(), null);
            boolean accepted = skills.castSkill(skill, context);
            require(accepted, "imported skill rejected " + skill);
            return accepted;
        }

        private void restoreRecipient() throws Exception {
            YamlConfiguration state = YamlConfiguration.loadConfiguration(stateFile());
            require(ownerId.equals(UUID.fromString(state.getString("owner", ""))), "owner UUID changed");
            require(watcherId.equals(UUID.fromString(state.getString("watcher", ""))), "watcher UUID changed");
            ownerGameModeBeforeProbe = GameMode.valueOf(state.getString("owner-game-mode", "SURVIVAL"));
            watcherGameModeBeforeProbe = GameMode.valueOf(state.getString("watcher-game-mode", "SURVIVAL"));
            UUID cowId = UUID.fromString(state.getString("cow", ""));
            world = Bukkit.getWorld(UUID.fromString(state.getString("world", "")));
            require(world != null, "saved Cow world missing");
            int chunkX = ((int) Math.floor(state.getDouble("x"))) >> 4;
            int chunkZ = ((int) Math.floor(state.getDouble("z"))) >> 4;
            world.getChunkAt(chunkX, chunkZ).load();
            Entity restored = Bukkit.getEntity(cowId);
            require(restored instanceof Cow, "saved unbound Cow UUID did not restore");
            cow = (Cow) restored;
            require(cow.getWorld().getUID().equals(world.getUID()), "saved Cow world changed");
            require(cow.getLocation().distanceSquared(new Location(world, state.getDouble("x"),
                    state.getDouble("y"), state.getDouble("z"))) < 1e-6, "saved Cow position changed");
        }

        private void verify(boolean clean) {
            ownerHasteAtReady = duration(owner(), PotionEffectType.HASTE);
            cowHasteAtReady = duration(cow, PotionEffectType.HASTE);
            ownerInvisibilityAtReady = duration(owner(), PotionEffectType.INVISIBILITY);
            cowInvisibilityAtReady = duration(cow, PotionEffectType.INVISIBILITY);
            if (clean) {
                require(owner().getActivePotionEffects().isEmpty() && cow.getActivePotionEffects().isEmpty()
                        && !owner().isInvisible() && !cow.isInvisible(), "cleared effects resurrected after restart");
            } else {
                boolean ownerActive = active(owner());
                boolean cowActive = active(cow);
                require(ownerActive && cowActive && watcher().getActivePotionEffects().isEmpty(),
                        "effects did not persist; owner=" + effectSummary(owner())
                                + " cow=" + effectSummary(cow)
                                + " watcher=" + effectSummary(watcher()));
            }
        }

        private String effectSummary(LivingEntity entity) {
            return entity.getActivePotionEffects().stream().map(effect -> effect.getType().getKey()
                    + ":" + effect.getAmplifier() + ":" + effect.getDuration() + ":"
                    + effect.isAmbient() + ":" + effect.hasParticles() + ":" + effect.hasIcon())
                    .sorted().toList().toString() + "/invisible=" + entity.isInvisible();
        }

        private boolean active(LivingEntity entity) {
            PotionEffect haste = entity.getPotionEffect(PotionEffectType.HASTE);
            PotionEffect invisibility = entity.getPotionEffect(PotionEffectType.INVISIBILITY);
            return entity.getActivePotionEffects().size() == 2 && haste != null && invisibility != null
                    && haste.getDuration() > 0 && haste.getDuration() < 1200 && haste.getAmplifier() == 2
                    && haste.isAmbient() && haste.hasParticles() && !haste.hasIcon()
                    && invisibility.getDuration() > 0 && invisibility.getDuration() < 1200
                    && invisibility.getAmplifier() == 0 && invisibility.isAmbient()
                    && !invisibility.hasParticles() && !invisibility.hasIcon() && entity.isInvisible();
        }

        private void announceReady() {
            owner().sendMessage("POTION_RESTART READY mode=" + stage);
            watcher().sendMessage("POTION_RESTART READY mode=" + stage);
        }

        @EventHandler(priority = EventPriority.HIGHEST)
        public void acknowledge(PlayerCommandPreprocessEvent event) {
            UUID senderId = event.getPlayer().getUniqueId();
            if (!senderId.equals(ownerId) && !senderId.equals(watcherId)) return;
            if (!event.getMessage().startsWith("/potionrestart ")) return;
            event.setCancelled(true);
            String expected = "/potionrestart " + stage;
            try {
                require(event.getMessage().equals(expected), "unexpected client acknowledgement");
                require(acknowledgements.add(senderId), "duplicate client acknowledgement");
                if (acknowledgements.size() != 2) return;
                acknowledgements.clear();
                if (stage.equals("verify-clear")) {
                    owner().removePotionEffect(PotionEffectType.HASTE);
                    owner().removePotionEffect(PotionEffectType.INVISIBILITY);
                    cow.removePotionEffect(PotionEffectType.HASTE);
                    cow.removePotionEffect(PotionEffectType.INVISIBILITY);
                    Bukkit.savePlayers();
                    world.save();
                    later(() -> {
                        try {
                            require(owner().getActivePotionEffects().isEmpty() && cow.getActivePotionEffects().isEmpty()
                                    && !owner().isInvisible() && !cow.isInvisible(), "effect removal did not clear live state");
                            complete();
                        } catch (Exception failure) { fail(failure); }
                    }, 10);
                } else complete();
            } catch (Exception failure) {
                fail(failure);
            }
        }

        private void require(boolean condition, String message) {
            if (!condition) throw new IllegalStateException(message);
        }

        private File stateFile() { return new File(plugin.getDataFolder(), "potion-restart-state.yml"); }

        private void later(Runnable action, long ticks) {
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (finished) return;
                try { action.run(); } catch (Exception failure) { fail(failure); }
            }, ticks);
        }

        private void complete() {
            if (finished) return;
            if (!cleanupCaster()) {
                fail(new IllegalStateException("temporary caster cleanup failed"));
                return;
            }
            if (stage.equals("clean")) {
                owner().setGameMode(ownerGameModeBeforeProbe);
                watcher().setGameMode(watcherGameModeBeforeProbe);
                Bukkit.savePlayers();
            }
            finished = true;
            HandlerList.unregisterAll(this);
            int ownerHaste = duration(owner(), PotionEffectType.HASTE);
            int cowHaste = cow == null ? 0 : duration(cow, PotionEffectType.HASTE);
            plugin.getLogger().info("[自检] potion-restart stage=" + stage + " owner=" + ownerId
                    + " watcher=" + watcherId + " cow=" + (cow == null ? "missing" : cow.getUniqueId())
                    + " owner-haste=" + ownerHaste + " cow-haste=" + cowHaste
                    + " owner-haste-restored=" + ownerHasteAtReady + " cow-haste-restored=" + cowHasteAtReady
                    + " owner-invisibility-restored=" + ownerInvisibilityAtReady
                    + " cow-invisibility-restored=" + cowInvisibilityAtReady + " passed=true");
            owner().sendMessage("POTION_RESTART DONE mode=" + stage + " passed=true");
            watcher().sendMessage("POTION_RESTART DONE mode=" + stage + " passed=true");
        }

        private int duration(LivingEntity entity, PotionEffectType type) {
            PotionEffect effect = entity.getPotionEffect(type);
            return effect == null ? 0 : effect.getDuration();
        }

        private void fail(Exception failure) {
            if (finished) return;
            finished = true;
            HandlerList.unregisterAll(this);
            cleanupCaster();
            if (cow != null && !stage.equals("prepare") && !cow.isValid()) cow = null;
            plugin.getLogger().warning("[自检] potion-restart stage=" + stage + " passed=false error="
                    + failure.getClass().getSimpleName() + ":" + failure.getMessage());
            for (UUID id : List.of(ownerId, watcherId)) {
                Player player = Bukkit.getPlayer(id);
                if (player != null) player.sendMessage("POTION_RESTART DONE mode=" + stage + " passed=false");
            }
        }

        private boolean cleanupCaster() {
            boolean cleaned = true;
            if (casterInstance != null) {
                try {
                    instances.despawn(casterInstance.instanceId());
                } catch (RuntimeException failure) {
                    cleaned = false;
                    plugin.getLogger().warning("[自检] potion-restart caster-instance-cleanup="
                            + failure.getClass().getSimpleName() + ":" + failure.getMessage());
                } finally {
                    casterInstance = null;
                }
            }
            if (caster != null) {
                try {
                    if (caster.isValid()) caster.remove();
                } catch (RuntimeException failure) {
                    cleaned = false;
                    plugin.getLogger().warning("[自检] potion-restart caster-entity-cleanup="
                            + failure.getClass().getSimpleName() + ":" + failure.getMessage());
                } finally {
                    caster = null;
                }
            }
            return cleaned;
        }
    }
}
