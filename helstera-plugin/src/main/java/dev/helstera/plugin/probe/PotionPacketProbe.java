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
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Cow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;

/** Server half of the focused, real-client native/imported Potion packet comparison. */
public final class PotionPacketProbe {
    private PotionPacketProbe() { }

    public static void schedule(JavaPlugin plugin, InstanceManager instances) {
        new BukkitRunnable() {
            private int attempts;
            @Override public void run() {
                var player = plugin.getServer().getOnlinePlayers().stream()
                        .filter(p -> p.getName().equals("PotionTester")).findFirst();
                if (player.isPresent()) {
                    cancel();
                    new Session(plugin, instances, player.get()).start();
                } else if (++attempts >= 60) {
                    cancel();
                    plugin.getLogger().warning("[自检] potion-packets player-ready=false passed=false");
                }
            }
        }.runTaskTimer(plugin, 100L, 20L);
    }

    private record Case(String name, int duration, int level, int flags) { }

    private static List<Case> cases() {
        var result = new ArrayList<Case>();
        result.add(new Case("packet_default", 80, 2, 6));
        for (int flags = 0; flags < 8; flags++) {
            result.add(new Case("packet_flags_" + flags, 80, 2, flags));
        }
        result.add(new Case("packet_expire", 8, 0, 5));
        result.add(new Case("packet_force", 60, 1, 1));
        result.add(new Case("packet_cancel", 80, 2, 6));
        return List.copyOf(result);
    }

    @FunctionalInterface
    private interface Step { void run() throws Exception; }

    private static final class Session {
        private final JavaPlugin plugin;
        private final InstanceManager instances;
        private final Player player;
        private final GameMode originalMode;
        private final List<PotionEffect> originalEffects;
        private final LinkedHashMap<String, Boolean> checks = new LinkedHashMap<>();
        private final Listener listener = new Listener() { };
        private final List<Case> cases = cases();
        private final SkillService skills;
        private Cow caster;
        private ModelInstance instance;
        private Object nativeApi;
        private Method nativeCast;
        private int index;
        private int cancelledAdds;
        private int removals;
        private boolean cancelAdded;
        private boolean prepared;
        private boolean finished;

        Session(JavaPlugin plugin, InstanceManager instances, Player player) {
            this.plugin = plugin;
            this.instances = instances;
            this.player = player;
            originalMode = player.getGameMode();
            originalEffects = List.copyOf(player.getActivePotionEffects());
            skills = new SkillService(new BehaviorRegistryImpl(), plugin.getLogger());
        }

        void start() {
            guarded(() -> {
                var mythic = java.util.Objects.requireNonNull(
                        plugin.getServer().getPluginManager().getPlugin("MythicMobs"), "MythicMobs required");
                String packaged;
                try (var stream = java.util.Objects.requireNonNull(
                        plugin.getResource("probes/potion-packet-skills.yml"), "packaged skills")) {
                    packaged = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
                }
                var root = mythic.getDataFolder().toPath();
                var source = root.resolve("Skills/potion-packet-skills.yml");
                require("source-identical", mythic.isEnabled() && packaged.equals(Files.readString(source)));
                var entries = new MythicMobsImporter().convert(source, root);
                var plan = entries.stream().filter(e -> "skills.yml".equals(e.get("target-file"))).findFirst().orElseThrow();
                var yaml = YamlConfiguration.loadConfiguration(new StringReader(String.valueOf(plan.get("content"))));
                skills.loadSkills(yaml.getConfigurationSection("skills"));
                require("source-imported", skills.warnings().isEmpty()
                        && entries.stream().noneMatch(e -> "WARN".equals(e.get("severity"))));
                nativeApi = mythic.getClass().getMethod("getAPIHelper").invoke(mythic);
                nativeCast = nativeApi.getClass().getMethod("castSkill", Entity.class, String.class,
                        Entity.class, Location.class, Collection.class, Collection.class, float.class);
                require("recipient-clean", originalEffects.isEmpty());
                player.setGameMode(GameMode.SURVIVAL);
                prepared = true;
                require("survival-recipient", player.getGameMode() == GameMode.SURVIVAL);
                caster = player.getWorld().spawn(player.getLocation().clone().add(4, 3, 0), Cow.class);
                caster.setAI(false);
                caster.setGravity(false);
                caster.setRemoveWhenFarAway(false);
                instance = instances.bind("example/emberling", caster, SpawnOptions.defaults().persistent(false));
                plugin.getServer().getPluginManager().registerEvent(EntityPotionEffectEvent.class, listener,
                        EventPriority.HIGHEST, (ignored, event) -> {
                            var change = (EntityPotionEffectEvent) event;
                            if (!change.getEntity().equals(player) || change.getModifiedType() != PotionEffectType.HASTE) return;
                            if (cancelAdded && change.getAction() == EntityPotionEffectEvent.Action.ADDED) {
                                change.setCancelled(true);
                                cancelledAdds++;
                            }
                            if (!change.isCancelled() && change.getAction() == EntityPotionEffectEvent.Action.REMOVED
                                    && change.getCause() == EntityPotionEffectEvent.Cause.PLUGIN) removals++;
                        }, plugin, false);
                later(this::next, 2);
            });
        }

        private String backend() { return index < cases.size() ? "native" : "imported"; }
        private String phase() { return backend() + ":" + cases.get(index % cases.size()).name(); }
        private String key(String suffix) { return phase().replace(':', '-') + "-" + suffix; }

        private void next() throws Exception {
            if (index == cases.size() * 2) { finish(); return; }
            if (!player.isOnline()) throw new IllegalStateException("recipient disconnected");
            var test = cases.get(index % cases.size());
            player.sendMessage("POTION_PHASE " + phase());
            if (test.name().equals("packet_force")) {
                require(key("base-cast"), cast("packet_force_base"));
                require(key("base-effect"), effect(new Case("base", 80, 2, 6)));
                int before = removals;
                require(key("cast"), cast(test.name()));
                require(key("effect"), effect(test));
                require(key("removal-event"), removals == before + 1);
            } else if (test.name().equals("packet_cancel")) {
                int before = cancelledAdds;
                cancelAdded = true;
                require(key("cast"), cast(test.name()));
                cancelAdded = false;
                require(key("event"), cancelledAdds == before + 1);
                require(key("absent"), !player.hasPotionEffect(PotionEffectType.HASTE));
                later(this::endCase, 4);
                return;
            } else {
                require(key("cast"), cast(test.name()));
                require(key("effect"), effect(test));
            }
            later(() -> {
                if (!test.name().equals("packet_expire")) player.removePotionEffect(PotionEffectType.HASTE);
                require(key("removed"), !player.hasPotionEffect(PotionEffectType.HASTE));
                later(this::endCase, 2);
            }, test.name().equals("packet_expire") ? 10 : 2);
        }

        private boolean cast(String name) throws Exception {
            if (index < cases.size()) {
                return Boolean.TRUE.equals(nativeCast.invoke(nativeApi, caster, name, player,
                        player.getLocation(), List.of(player), List.of(), 1.0f));
            }
            var context = BehaviorContext.continueExecution(instance, player, 1, 3, 0, "potion-packets",
                    null, player, List.of(player), player.getLocation(), null);
            return skills.castSkill(name, context);
        }

        private boolean effect(Case expected) {
            var effect = player.getPotionEffect(PotionEffectType.HASTE);
            return effect != null && effect.getDuration() == expected.duration()
                    && effect.getAmplifier() == expected.level()
                    && effect.isAmbient() == ((expected.flags() & 1) != 0)
                    && effect.hasParticles() == ((expected.flags() & 2) != 0)
                    && effect.hasIcon() == ((expected.flags() & 4) != 0);
        }

        private void endCase() throws Exception {
            player.sendMessage("POTION_END " + phase());
            index++;
            next();
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
                plugin.getLogger().warning("[自检] potion-packets error=" + failure.getClass().getSimpleName()
                        + ":" + failure.getMessage());
                finish();
            }
        }

        private void finish() {
            if (finished) return;
            finished = true;
            try {
                require("caster-isolation", caster != null && caster.getActivePotionEffects().isEmpty());
            } catch (RuntimeException failure) { checks.put("caster-isolation", false); }
            try {
                HandlerList.unregisterAll(listener);
                player.sendMessage("POTION_RESET");
                if (prepared) {
                    player.removePotionEffect(PotionEffectType.HASTE);
                    for (var effect : originalEffects) player.addPotionEffect(effect);
                    player.setGameMode(originalMode);
                }
                if (instance != null) instances.despawn(instance.instanceId());
                if (caster != null) caster.remove();
                checks.put("cleanup", caster != null && !caster.isValid()
                        && instance != null && instances.getInstance(instance.instanceId()).isEmpty()
                        && player.getActivePotionEffects().isEmpty() && originalEffects.isEmpty()
                        && player.getGameMode() == originalMode);
            } catch (RuntimeException failure) { checks.put("cleanup", false); }
            boolean passed = checks.values().stream().allMatch(Boolean.TRUE::equals);
            var result = new StringBuilder("[自检] potion-packets");
            checks.forEach((name, value) -> result.append(' ').append(name).append('=').append(value));
            plugin.getLogger().info(result + " assertions=" + checks.size() + " passed=" + passed);
            player.sendMessage("POTION_DONE passed=" + passed);
        }
    }
}
