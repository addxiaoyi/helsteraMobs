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
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
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
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;

/** Real observer tracking and player-data reconnect checks; never reapplies effects on join. */
public final class PotionSyncProbe {
    private PotionSyncProbe() { }
    private static final List<String> STAGES = List.of("live", "watcher-rejoin", "owner-rejoin",
            "changed", "hidden", "cleared", "clean-rejoin");

    public static void schedule(JavaPlugin plugin, InstanceManager instances) {
        new BukkitRunnable() {
            private int attempts;
            @Override public void run() {
                var owner = Bukkit.getPlayerExact("SyncOwner");
                var watcher = Bukkit.getPlayerExact("SyncWatcher");
                if (owner != null && watcher != null) {
                    cancel();
                    new Session(plugin, instances, owner, watcher).start();
                } else if (++attempts >= 90) {
                    cancel();
                    plugin.getLogger().warning("[自检] potion-sync players-ready=false passed=false");
                }
            }
        }.runTaskTimer(plugin, 100, 20);
    }

    @FunctionalInterface private interface Step { void run() throws Exception; }
    private record Original(GameMode mode, boolean invulnerable, boolean gravity, Location location) { }

    private static final class Session implements Listener {
        private final JavaPlugin plugin;
        private final InstanceManager instances;
        private final UUID ownerId;
        private final UUID watcherId;
        private final Map<UUID, Original> originals = new LinkedHashMap<>();
        private final LinkedHashMap<String, Boolean> checks = new LinkedHashMap<>();
        private final HashSet<UUID> acknowledgements = new HashSet<>();
        private final SkillService skills;
        private Cow caster;
        private Cow recipient;
        private ModelInstance instance;
        private Object nativeApi;
        private Method nativeCast;
        private int backendIndex;
        private int stageIndex;
        private int previousEntityId;
        private int quitDuration;
        private int quitInvisibilityDuration;
        private int joinDeadline;
        private boolean quitObserved;
        private boolean awaitingAcknowledgements;
        private boolean finished;

        Session(JavaPlugin plugin, InstanceManager instances, Player owner, Player watcher) {
            this.plugin = plugin;
            this.instances = instances;
            ownerId = owner.getUniqueId();
            watcherId = watcher.getUniqueId();
            for (var player : List.of(owner, watcher)) originals.put(player.getUniqueId(),
                    new Original(player.getGameMode(), player.isInvulnerable(), player.hasGravity(), player.getLocation()));
            skills = new SkillService(new BehaviorRegistryImpl(), plugin.getLogger());
        }

        private Player owner() { return java.util.Objects.requireNonNull(Bukkit.getPlayer(ownerId), "owner offline"); }
        private Player watcher() { return java.util.Objects.requireNonNull(Bukkit.getPlayer(watcherId), "watcher offline"); }
        private String backend() { return backendIndex == 0 ? "native" : "imported"; }
        private String stage() { return STAGES.get(stageIndex); }
        private String phase() { return backend() + ":" + stage(); }
        private String key(String suffix) { return backend() + "-" + stage() + "-" + suffix; }

        void start() {
            guarded(() -> {
                var mythic = java.util.Objects.requireNonNull(
                        plugin.getServer().getPluginManager().getPlugin("MythicMobs"), "MythicMobs required");
                String sourceText;
                try (var stream = java.util.Objects.requireNonNull(plugin.getResource("probes/potion-sync-skills.yml"))) {
                    sourceText = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
                }
                var root = mythic.getDataFolder().toPath();
                var source = root.resolve("Skills/potion-sync-skills.yml");
                require("source-identical", mythic.isEnabled() && sourceText.equals(Files.readString(source)));
                var entries = new MythicMobsImporter().convert(source, root);
                var plan = entries.stream().filter(e -> "skills.yml".equals(e.get("target-file"))).findFirst().orElseThrow();
                var yaml = YamlConfiguration.loadConfiguration(new StringReader(String.valueOf(plan.get("content"))));
                skills.loadSkills(yaml.getConfigurationSection("skills"));
                require("source-imported", skills.warnings().isEmpty()
                        && entries.stream().noneMatch(e -> "WARN".equals(e.get("severity"))));
                nativeApi = mythic.getClass().getMethod("getAPIHelper").invoke(mythic);
                nativeCast = nativeApi.getClass().getMethod("castSkill", Entity.class, String.class,
                        Entity.class, Location.class, Collection.class, Collection.class, float.class);
                require("players-clean", owner().getActivePotionEffects().isEmpty() && watcher().getActivePotionEffects().isEmpty());
                var origin = owner().getWorld().getSpawnLocation().clone().add(0, 6, 0);
                for (var player : List.of(owner(), watcher())) {
                    prepare(player);
                    player.teleport(origin.clone().add(player.equals(owner()) ? 0 : 3, 0, 0));
                }
                caster = origin.getWorld().spawn(origin.clone().add(3, 0, 3), Cow.class);
                recipient = origin.getWorld().spawn(origin.clone().add(0, 0, 3), Cow.class);
                for (var cow : List.of(caster, recipient)) {
                    cow.setAI(false);
                    cow.setGravity(false);
                    cow.setInvulnerable(true);
                    cow.setRemoveWhenFarAway(false);
                }
                instance = instances.bind("example/emberling", caster, SpawnOptions.defaults().persistent(false));
                plugin.getServer().getPluginManager().registerEvents(this, plugin);
                later(this::next, 20);
                later(() -> { throw new IllegalStateException("session timeout"); }, 4000);
            });
        }

        private void prepare(Player player) {
            player.setGameMode(GameMode.SURVIVAL);
            player.setInvulnerable(true);
            player.setGravity(false);
        }

        private void send(String command) {
            String message = "POTION_SYNC " + command + " backend=" + backend() + " stage=" + stage()
                    + " owner=" + owner().getEntityId() + " cow=" + recipient.getEntityId();
            owner().sendMessage(message);
            watcher().sendMessage(message);
        }

        private void next() throws Exception {
            acknowledgements.clear();
            awaitingAcknowledgements = false;
            send("BEGIN");
            switch (stage()) {
                case "live" -> {
                    for (var entity : List.<LivingEntity>of(owner(), recipient)) {
                        String role = entity instanceof Player ? "owner" : "cow";
                        require(key(role + "-haste-cast"), cast("sync_visible", entity));
                        require(key(role + "-invisible-cast"), cast("sync_invisible", entity));
                        require(key(role + "-initial-state"), effects(entity, 2, true, true, false)
                                && entity.getPotionEffect(PotionEffectType.HASTE).getDuration() == 1200);
                    }
                }
                case "changed", "hidden" -> {
                    boolean visible = stage().equals("changed");
                    for (var entity : List.<LivingEntity>of(owner(), recipient)) {
                        String role = entity instanceof Player ? "owner" : "cow";
                        require(key(role + "-cast"), cast(visible ? "sync_changed" : "sync_hidden", entity));
                        require(key(role + "-initial-state"), effects(entity, 1, false, visible, visible)
                                && entity.getPotionEffect(PotionEffectType.HASTE).getDuration() == 1200);
                    }
                }
                case "cleared" -> {
                    for (var entity : List.<LivingEntity>of(owner(), recipient)) {
                        entity.removePotionEffect(PotionEffectType.HASTE);
                        entity.removePotionEffect(PotionEffectType.INVISIBILITY);
                    }
                }
                default -> {
                    var reconnecting = stage().equals("watcher-rejoin") ? watcher() : owner();
                    previousEntityId = reconnecting.getEntityId();
                    quitObserved = false;
                    quitDuration = -1;
                    quitInvisibilityDuration = -1;
                    joinDeadline = Bukkit.getCurrentTick() + 600;
                    send("RECONNECT");
                    String reconnectPhase = phase();
                    later(() -> {
                        if (phase().equals(reconnectPhase) && !awaitingAcknowledgements) {
                            throw new IllegalStateException("reconnect timeout");
                        }
                    }, 620);
                    return;
                }
            }
            later(this::checkpoint, 6);
        }

        private boolean cast(String name, LivingEntity target) throws Exception {
            if (backendIndex == 0) return Boolean.TRUE.equals(nativeCast.invoke(nativeApi, caster, name, target,
                    target.getLocation(), List.of(target), List.of(), 1.0f));
            var context = BehaviorContext.continueExecution(instance, target, 1, 3, 0, "potion-sync", null,
                    target, List.of(target), target.getLocation(), null);
            return skills.castSkill(name, context);
        }

        private boolean effects(LivingEntity entity, int level, boolean ambient, boolean particles, boolean icon) {
            var haste = entity.getPotionEffect(PotionEffectType.HASTE);
            var invisible = entity.getPotionEffect(PotionEffectType.INVISIBILITY);
            return entity.getActivePotionEffects().size() == 2 && haste != null && invisible != null
                    && haste.getDuration() > 0 && haste.getDuration() <= 1200 && haste.getAmplifier() == level
                    && haste.isAmbient() == ambient && haste.hasParticles() == particles && haste.hasIcon() == icon
                    && invisible.getDuration() > 0 && invisible.getAmplifier() == 0
                    && invisible.isAmbient() && !invisible.hasParticles() && !invisible.hasIcon();
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void quit(PlayerQuitEvent event) {
            if (finished) return;
            var expected = stage().equals("watcher-rejoin") ? watcherId : ownerId;
            if (!event.getPlayer().getUniqueId().equals(expected)) return;
            guarded(() -> {
                require(key("quit"), event.getPlayer().getEntityId() == previousEntityId);
                var effect = event.getPlayer().getPotionEffect(PotionEffectType.HASTE);
                quitDuration = effect == null ? 0 : effect.getDuration();
                var invisible = event.getPlayer().getPotionEffect(PotionEffectType.INVISIBILITY);
                quitInvisibilityDuration = invisible == null ? 0 : invisible.getDuration();
                quitObserved = true;
            });
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void join(PlayerJoinEvent event) {
            if (finished || !originals.containsKey(event.getPlayer().getUniqueId())) return;
            guarded(() -> {
                var expected = stage().equals("watcher-rejoin") ? watcherId : ownerId;
                require(key("rejoin"), quitObserved && event.getPlayer().getUniqueId().equals(expected)
                        && event.getPlayer().getEntityId() != previousEntityId && Bukkit.getCurrentTick() < joinDeadline);
                prepare(event.getPlayer());
                if (!stage().equals("watcher-rejoin")) {
                    var effect = event.getPlayer().getPotionEffect(PotionEffectType.HASTE);
                    int duration = effect == null ? 0 : effect.getDuration();
                    var invisible = event.getPlayer().getPotionEffect(PotionEffectType.INVISIBILITY);
                    int invisibleDuration = invisible == null ? 0 : invisible.getDuration();
                    // Pinned PlayerList.remove calls ServerPlayer.doTick once
                    // after PlayerQuitEvent and before saving player data.
                    plugin.getLogger().info("[自检] potion-sync-duration backend=" + backend()
                            + " stage=" + stage() + " effect=haste quit=" + quitDuration + " join=" + duration);
                    plugin.getLogger().info("[自检] potion-sync-duration backend=" + backend()
                            + " stage=" + stage() + " effect=invisibility quit=" + quitInvisibilityDuration
                            + " join=" + invisibleDuration);
                    require(key("persisted-duration"), duration == Math.max(0, quitDuration - 1)
                            && invisibleDuration == Math.max(0, quitInvisibilityDuration - 1));
                }
                later(this::checkpoint, 10);
            });
        }

        private void checkpoint() {
            boolean clear = stage().equals("cleared") || stage().equals("clean-rejoin");
            boolean changed = stage().equals("changed") || stage().equals("hidden");
            for (var entity : List.<LivingEntity>of(owner(), recipient)) {
                require(key((entity instanceof Player ? "owner" : "cow") + "-state"), clear
                        ? entity.getActivePotionEffects().isEmpty() && !entity.isInvisible()
                        : effects(entity, changed ? 1 : 2, !changed, !stage().equals("hidden"), stage().equals("changed"))
                                && entity.isInvisible());
            }
            require(key("isolation"), caster.getActivePotionEffects().isEmpty() && watcher().getActivePotionEffects().isEmpty());
            awaitingAcknowledgements = true;
            int haste = owner().hasPotionEffect(PotionEffectType.HASTE)
                    ? owner().getPotionEffect(PotionEffectType.HASTE).getDuration() : 0;
            int invisible = owner().hasPotionEffect(PotionEffectType.INVISIBILITY)
                    ? owner().getPotionEffect(PotionEffectType.INVISIBILITY).getDuration() : 0;
            send("CHECK haste=" + haste + " invisible=" + invisible);
        }

        @EventHandler(priority = EventPriority.HIGHEST)
        public void acknowledge(PlayerCommandPreprocessEvent event) {
            if (!originals.containsKey(event.getPlayer().getUniqueId()) || !event.getMessage().startsWith("/potionsync ")) return;
            event.setCancelled(true);
            guarded(() -> {
                require(key("ack-" + (event.getPlayer().getUniqueId().equals(ownerId) ? "owner" : "watcher")),
                        awaitingAcknowledgements && event.getMessage().equals("/potionsync " + phase())
                                && acknowledgements.add(event.getPlayer().getUniqueId()));
                if (acknowledgements.size() != 2) return;
                awaitingAcknowledgements = false;
                if (++stageIndex == STAGES.size()) {
                    stageIndex = 0;
                    if (++backendIndex == 2) { finish(); return; }
                }
                later(this::next, 2);
            });
        }

        private void require(String name, boolean passed) {
            checks.put(name, passed);
            if (!passed) throw new IllegalStateException(name);
        }

        private void later(Step step, long ticks) {
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> guarded(step), ticks);
        }

        private void guarded(Step step) {
            if (finished) return;
            try { step.run(); }
            catch (Exception failure) {
                checks.put("execution", false);
                plugin.getLogger().warning("[自检] potion-sync error=" + failure.getClass().getSimpleName() + ":" + failure.getMessage());
                finish();
            }
        }

        private void finish() {
            if (finished) return;
            finished = true;
            HandlerList.unregisterAll(this);
            try {
                for (var entry : originals.entrySet()) {
                    var player = java.util.Objects.requireNonNull(Bukkit.getPlayer(entry.getKey()));
                    player.removePotionEffect(PotionEffectType.HASTE);
                    player.removePotionEffect(PotionEffectType.INVISIBILITY);
                    player.setGameMode(entry.getValue().mode());
                    player.setInvulnerable(entry.getValue().invulnerable());
                    player.setGravity(entry.getValue().gravity());
                    player.teleport(entry.getValue().location());
                }
                if (instance != null) instances.despawn(instance.instanceId());
                if (caster != null) caster.remove();
                if (recipient != null) recipient.remove();
                checks.put("cleanup", caster != null && !caster.isValid() && recipient != null && !recipient.isValid()
                        && instance != null && instances.getInstance(instance.instanceId()).isEmpty()
                        && originals.entrySet().stream().allMatch(e -> {
                            var player = Bukkit.getPlayer(e.getKey());
                            return player != null && player.getActivePotionEffects().isEmpty()
                                    && player.getGameMode() == e.getValue().mode()
                                    && player.isInvulnerable() == e.getValue().invulnerable()
                                    && player.hasGravity() == e.getValue().gravity()
                                    && player.getLocation().distanceSquared(e.getValue().location()) < 1e-6;
                        }));
            } catch (RuntimeException failure) { checks.put("cleanup", false); }
            boolean passed = checks.values().stream().allMatch(Boolean.TRUE::equals);
            var result = new StringBuilder("[自检] potion-sync");
            checks.forEach((name, value) -> result.append(' ').append(name).append('=').append(value));
            plugin.getLogger().info(result + " assertions=" + checks.size() + " passed=" + passed);
            for (var id : originals.keySet()) {
                var player = Bukkit.getPlayer(id);
                if (player != null) player.sendMessage("POTION_SYNC DONE passed=" + passed);
            }
        }
    }
}
