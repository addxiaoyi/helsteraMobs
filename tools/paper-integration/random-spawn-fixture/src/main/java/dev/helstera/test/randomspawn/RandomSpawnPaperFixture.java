package dev.helstera.test.randomspawn;

import org.bukkit.Bukkit;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Creeper;
import org.bukkit.entity.Allay;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Skeleton;
import org.bukkit.entity.Zombie;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Executes observable spawn events against the production RandomSpawnService on Paper. */
public final class RandomSpawnPaperFixture extends JavaPlugin implements Listener {
    private final Map<String, LivingEntity> originals = new LinkedHashMap<>();
    private final Map<String, EventObservation> observations = new LinkedHashMap<>();
    private NamespacedKey probeKey;
    private String activeCase;
    private Class<? extends LivingEntity> activeType;
    private Location activeLocation;
    private boolean importerPassed;
    private boolean unsupportedTypedDeny;
    private boolean syntheticPassed;
    private boolean addClusterPassed;
    private boolean addConditionsPassed;
    private boolean addPlayerRadiusPassed;
    private boolean playerModePassed;
    private boolean addBorderPassed;
    private boolean addConditionPreserved;
    private boolean typesAlternativesPassed;
    private boolean naturalPlayerReady;
    private boolean naturalPadPrepared;
    private boolean naturalWindowStarted;
    private Path generatedRuntimeConfig;
    private int naturalPadMinX;
    private int naturalPadMaxX;
    private int naturalPadMinZ;
    private int naturalPadMaxZ;
    private int naturalPadFloor;
    private int naturalAttempts;
    private int naturalCancelled;
    private int naturalBlockLight = -1;

    private record EventObservation(boolean cancelled, String reason, String entityType) { }

    @Override
    public void onEnable() {
        VariablePlaceholderAudit.prepare(Bukkit.getPluginManager().getPlugin("MythicMobs"));
        TriggerPlaceholderAudit.prepare(Bukkit.getPluginManager().getPlugin("MythicMobs"));
        probeKey = new NamespacedKey(this, "case");
        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getScheduler().runTaskLater(this, this::runProbe, 60L);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void tagProbeEvent(CreatureSpawnEvent event) {
        if (activeCase == null || activeLocation == null || !activeType.isInstance(event.getEntity())
                || event.getLocation().distanceSquared(activeLocation) > 0.01) return;
        event.getEntity().getPersistentDataContainer().set(probeKey, PersistentDataType.STRING, activeCase);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void observeProbeEvent(CreatureSpawnEvent event) {
        String id = event.getEntity().getPersistentDataContainer().get(probeKey, PersistentDataType.STRING);
        if (id != null) {
            originals.put(id, event.getEntity());
            observations.put(id, new EventObservation(event.isCancelled(), event.getSpawnReason().name(),
                    event.getEntity().getType().name()));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void observeNaturalSpawn(CreatureSpawnEvent event) {
        if (!naturalWindowStarted || event.getSpawnReason() != CreatureSpawnEvent.SpawnReason.NATURAL) return;
        Location location = event.getLocation();
        if (location.getBlockX() < naturalPadMinX || location.getBlockX() > naturalPadMaxX
                || location.getBlockZ() < naturalPadMinZ || location.getBlockZ() > naturalPadMaxZ
                || location.getBlockY() < naturalPadFloor + 1 || location.getBlockY() > naturalPadFloor + 3) return;
        naturalAttempts++;
        if (event.isCancelled()) naturalCancelled++;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void prepareNaturalPlayer(PlayerJoinEvent event) {
        if (!"RandomSpawnProbe".equals(event.getPlayer().getName())) return;
        World world = Bukkit.getWorld("integration-world");
        if (world == null) return;
        world.setGameRule(GameRule.DO_MOB_SPAWNING, false);
        event.getPlayer().setGameMode(org.bukkit.GameMode.SURVIVAL);
        event.getPlayer().setInvulnerable(true);
        Location playerLocation = world.getSpawnLocation().clone();
        playerLocation.setY(world.getHighestBlockYAt(playerLocation) + 1.0);
        event.getPlayer().teleport(playerLocation);
        naturalPlayerReady = true;
        getLogger().info("RANDOM_SPAWN_NATURAL_PLAYER|ready=true|gameMode="
                + event.getPlayer().getGameMode().name());
    }

    private void runProbe() {
        try {
            importerPassed = importAndLoadMythicSource();
            getLogger().info("RANDOM_SPAWN_IMPORT|passed=" + importerPassed
                    + "|mappedDefinitions=16|unsupportedActions=1|unsupportedTypedDeny="
                    + unsupportedTypedDeny + "|addConditionPreserved=" + addConditionPreserved
                    + "|resolvedTypes=true|mappedConditions=true|typesAlternatives=" + typesAlternativesPassed);
            require(importerPassed, "Mythic source import did not produce the expected runtime projection");
            World world = Bukkit.getWorld("integration-world");
            require(world != null, "integration-world did not load");
            Location base = world.getSpawnLocation().clone();
            base.setY(world.getHighestBlockYAt(base) + 1.0);

            spawn("replace", world, base, Skeleton.class, CreatureSpawnEvent.SpawnReason.CUSTOM);
            spawn("condition-control", world, base.clone().add(12, 0, 0), Creeper.class,
                    CreatureSpawnEvent.SpawnReason.CUSTOM);
            spawn("reason-control", world, base.clone().add(24, 0, 0), Creeper.class,
                    CreatureSpawnEvent.SpawnReason.CUSTOM);
            spawn("deny", world, base.clone().add(30, 0, 0), Zombie.class,
                    CreatureSpawnEvent.SpawnReason.CUSTOM);
            spawn("deny-condition-control", world, base.clone().add(36, 0, 0), Creeper.class,
                    CreatureSpawnEvent.SpawnReason.CUSTOM);
            Bukkit.getScheduler().runTaskLater(this, () -> spawn("cooldown-immediate", world,
                    base.clone().add(36, 0, 0), Skeleton.class, CreatureSpawnEvent.SpawnReason.CUSTOM), 1L);
            Bukkit.getScheduler().runTaskLater(this, () -> spawn("cooldown-expired", world,
                    base.clone().add(48, 0, 0), Skeleton.class, CreatureSpawnEvent.SpawnReason.CUSTOM), 25L);
            runTypesAlternativesProbe(world, base.clone().add(80, 0, 0));
            Bukkit.getScheduler().runTaskLater(this, () -> verify(world, base), 35L);
        } catch (Throwable error) {
            getLogger().severe("RANDOM_SPAWN_PAPER_FIXTURE|passed=false|error="
                    + error.getClass().getSimpleName() + ":" + String.valueOf(error.getMessage()).replace('|', '/'));
        }
    }

    @SuppressWarnings("unchecked")
    private boolean importAndLoadMythicSource() throws Exception {
        var pluginManager = Bukkit.getPluginManager();
        var helstera = pluginManager.getPlugin("helsteraMobs");
        require(helstera != null && helstera.isEnabled(), "helsteraMobs is not enabled");
        Path mythicRoot = helstera.getDataFolder().toPath().getParent().resolve("mythic-import-source");
        Path source = mythicRoot.resolve("RandomSpawns/replace.yml");

        Class<?> importerType = Class.forName("dev.helstera.migration.importer.MythicMobsImporter",
                true, helstera.getClass().getClassLoader());
        Object importer = importerType.getConstructor().newInstance();
        Method convert = importerType.getMethod("convert", Path.class, Path.class);
        List<Map<String, Object>> entries = (List<Map<String, Object>>) convert.invoke(importer, source, mythicRoot);
        long mappedDefinitions = entries.stream().filter(entry -> "mapped".equals(entry.get("status"))
                && String.valueOf(entry.get("source-key")).startsWith("RandomSpawns.")).count();
        long unsupportedActions = entries.stream().filter(entry -> "unsupported".equals(entry.get("status"))
                && "RandomSpawns.UnsupportedAction".equals(entry.get("source-key"))).count();
        long unsupportedAddCondition = entries.stream().filter(entry -> "unsupported".equals(entry.get("status"))
                && "RandomSpawns.ConditionalAdd".equals(entry.get("source-key"))
                && String.valueOf(entry.get("note")).contains("ADD Conditions requiring an entity")).count();
        addConditionPreserved = unsupportedAddCondition == 1;
        long unsupportedTypedDenyCount = entries.stream().filter(entry -> "unsupported".equals(entry.get("status"))
                && "RandomSpawns.IgnoredDeny".equals(entry.get("source-key"))
                && String.valueOf(entry.get("note")).contains("DENY does not spawn a mob")).count();
        unsupportedTypedDeny = unsupportedTypedDenyCount == 1;
        Map<String, Object> target = entries.stream()
                .filter(entry -> "random-spawns.yml".equals(entry.get("target-file")))
                .filter(entry -> "mapped".equals(entry.get("status")))
                .findFirst().orElse(null);
        if (mappedDefinitions != 16 || unsupportedActions != 1 || !addConditionPreserved
                || !unsupportedTypedDeny || target == null) return false;

        Path generated = helstera.getDataFolder().toPath().resolve("random-spawns.yml");
        generatedRuntimeConfig = generated;
        Files.writeString(generated, String.valueOf(target.get("content")));
        Field serviceField = helstera.getClass().getDeclaredField("randomSpawnService");
        serviceField.setAccessible(true);
        Object service = serviceField.get(helstera);
        if (service == null) return false;
        Method loadFile = service.getClass().getMethod("loadFile", Path.class);
        loadFile.invoke(service, generated);
        Method size = service.getClass().getMethod("size");
        YamlConfiguration generatedYaml = YamlConfiguration.loadConfiguration(generated.toFile());
        ConfigurationSection alternatives = generatedYaml.getConfigurationSection(
                "random-spawns.alternativetypes.types");
        typesAlternativesPassed = alternatives != null
                && alternatives.getValues(false).equals(Map.of(
                        "mythic_random_spawn_probe", 1.0, "mythic_random_spawn_guard", 1.0))
                && generatedYaml.getStringList("random-spawns.alternativetypes.conditions")
                .equals(List.of("entity-type-is ALLAY"));
        return ((Number) size.invoke(service)).intValue() == 16 && typesAlternativesPassed
                && Files.readString(generated).contains("mythic_random_spawn_probe")
                && Files.readString(generated).contains("entity-type-is SKELETON")
                && Files.readString(generated).contains("position-type: LAND")
                && Files.readString(generated).contains("time-is night")
                && Files.readString(generated).contains("weather-is clear")
                && Files.readString(generated).contains("action: ADD")
                && Files.readString(generated).contains("action: DENY");
    }

    private void runTypesAlternativesProbe(World world, Location start) {
        int first = 0;
        int second = 0;
        int validEvents = 0;
        for (int i = 0; i < 16; i++) {
            String caseId = "types-control-" + i;
            spawn(caseId, world, start, Allay.class, CreatureSpawnEvent.SpawnReason.CUSTOM);
            EventObservation observation = observations.get(caseId);
            if (observation != null && observation.cancelled() && !isValid(caseId)) validEvents++;
        }
        first = namedZombies(world, start, "Random Spawn Replacement", 4);
        second = namedZombies(world, start, "Condition Guard Must Not Spawn", 4);
        typesAlternativesPassed &= validEvents == 16 && first + second == 16 && first > 0 && second > 0;
        getLogger().info("RANDOM_SPAWN_TYPES|passed=" + typesAlternativesPassed
                + "|alternatives=" + first + ":" + second + "|validEvents=" + validEvents + "/16");
    }

    private int namedReplacementZombies(World world, Location center, double radius) {
        return namedZombies(world, center, "Random Spawn Replacement", radius)
                + namedZombies(world, center, "Condition Guard Must Not Spawn", radius);
    }

    private void spawn(String id, World world, Location location, Class<? extends LivingEntity> type,
                       CreatureSpawnEvent.SpawnReason reason) {
        activeCase = id;
        activeType = type;
        activeLocation = location.clone();
        try {
            world.spawn(location, type, reason, false, null);
        } finally {
            activeCase = null;
            activeType = null;
            activeLocation = null;
        }
    }

    private void verify(World world, Location base) {
        try {
            EventObservation replacement = observations.get("replace");
            EventObservation condition = observations.get("condition-control");
            EventObservation reason = observations.get("reason-control");
            EventObservation deny = observations.get("deny");
            EventObservation denyCondition = observations.get("deny-condition-control");
            EventObservation cooldownImmediate = observations.get("cooldown-immediate");
            EventObservation cooldownExpired = observations.get("cooldown-expired");
            int replacementCount = namedZombies(world, base, "Random Spawn Replacement");
            int conditionGuardCount = namedZombies(world, base.clone().add(12, 0, 0), "Condition Guard Must Not Spawn");
            boolean replacePassed = replacement != null && replacement.cancelled()
                    && !isValid("replace") && replacementCount == 1;
            boolean conditionPassed = condition != null && !condition.cancelled()
                    && isValid("condition-control") && conditionGuardCount == 0;
            boolean reasonPassed = reason != null && !reason.cancelled()
                    && isValid("reason-control");
            boolean denyPassed = deny != null && deny.cancelled() && !isValid("deny")
                    && namedZombies(world, base.clone().add(30, 0, 0), "Random Spawn Replacement") == 0;
            boolean denyConditionPassed = denyCondition != null && !denyCondition.cancelled()
                    && isValid("deny-condition-control");
            boolean immediateCooldownPassed = cooldownImmediate != null && !cooldownImmediate.cancelled()
                    && isValid("cooldown-immediate");
            boolean expiredCooldownPassed = cooldownExpired != null && cooldownExpired.cancelled()
                    && !isValid("cooldown-expired")
                    && namedZombies(world, base.clone().add(48, 0, 0), "Random Spawn Replacement") == 1;

            log("replace", replacePassed, replacement, isValid("replace"), replacementCount);
            log("condition-control", conditionPassed, condition,
                    isValid("condition-control"), conditionGuardCount);
            log("reason-control", reasonPassed, reason,
                    isValid("reason-control"), 0);
            log("deny", denyPassed, deny, isValid("deny"), 0);
            log("deny-condition-control", denyConditionPassed, denyCondition,
                    isValid("deny-condition-control"), 0);
            log("cooldown-immediate", immediateCooldownPassed, cooldownImmediate,
                    isValid("cooldown-immediate"), 0);
            log("cooldown-expired", expiredCooldownPassed, cooldownExpired,
                    isValid("cooldown-expired"), 1);
            syntheticPassed = importerPassed && replacePassed && conditionPassed && reasonPassed
                    && denyPassed && denyConditionPassed && immediateCooldownPassed && expiredCooldownPassed
                    && typesAlternativesPassed;
            awaitNaturalProbe(world, base, 0);
        } catch (Throwable error) {
            getLogger().severe("RANDOM_SPAWN_PAPER_FIXTURE|passed=false|verifyError="
                    + error.getClass().getSimpleName() + ":" + String.valueOf(error.getMessage()).replace('|', '/'));
        }
    }

    private void awaitNaturalProbe(World world, Location base, int waitedTicks) {
        if (!naturalPlayerReady) {
            if (waitedTicks >= 1_200) {
                logNaturalProbe(false);
                return;
            }
            Bukkit.getScheduler().runTaskLater(this,
                    () -> awaitNaturalProbe(world, base, waitedTicks + 20), 20L);
            return;
        }

        if (!naturalPadPrepared) {
            try {
                runAddClusterProbe(world);
            } catch (Throwable error) {
                getLogger().severe("RANDOM_SPAWN_ADD|passed=false|error="
                        + error.getClass().getSimpleName() + ":" + String.valueOf(error.getMessage()).replace('|', '/'));
            }
            naturalPadPrepared = true;
            int centerX = base.getBlockX() + 40;
            int centerZ = base.getBlockZ();
            naturalPadMinX = centerX - 7;
            naturalPadMaxX = centerX + 7;
            naturalPadMinZ = centerZ - 7;
            naturalPadMaxZ = centerZ + 7;
            naturalPadFloor = base.getBlockY() - 1;
            for (int x = naturalPadMinX; x <= naturalPadMaxX; x++) {
                for (int z = naturalPadMinZ; z <= naturalPadMaxZ; z++) {
                    boolean wall = x == naturalPadMinX || x == naturalPadMaxX
                            || z == naturalPadMinZ || z == naturalPadMaxZ;
                    world.getBlockAt(x, naturalPadFloor, z).setType(Material.STONE, false);
                    world.getBlockAt(x, naturalPadFloor + 4, z).setType(Material.STONE, false);
                    for (int y = naturalPadFloor + 1; y <= naturalPadFloor + 3; y++) {
                        world.getBlockAt(x, y, z).setType(wall ? Material.STONE : Material.AIR, false);
                    }
                }
            }
            world.setTime(18_000);
            world.setDifficulty(org.bukkit.Difficulty.HARD);
            world.setSpawnFlags(true, false);
            // Paper propagates the roof/wall lighting asynchronously. Do not
            // record the old skylight value or count attempts before it settles.
            Bukkit.getScheduler().runTaskLater(this,
                    () -> awaitNaturalProbe(world, base, waitedTicks + 20), 20L);
            return;
        }

        if (!naturalWindowStarted) {
            naturalBlockLight = world.getBlockAt((naturalPadMinX + naturalPadMaxX) / 2,
                    naturalPadFloor + 1, (naturalPadMinZ + naturalPadMaxZ) / 2).getLightLevel();
            if (naturalBlockLight != 0) {
                if (waitedTicks >= 1_200) {
                    logNaturalProbe(false);
                    return;
                }
                Bukkit.getScheduler().runTaskLater(this,
                        () -> awaitNaturalProbe(world, base, waitedTicks + 20), 20L);
                return;
            }
            naturalWindowStarted = true;
            world.setGameRule(GameRule.DO_MOB_SPAWNING, true);
            getLogger().info("RANDOM_SPAWN_NATURAL_WINDOW|enabled=true|playerReady=true|padDistance=40"
                    + "|doMobSpawning=" + world.getGameRuleValue(GameRule.DO_MOB_SPAWNING)
                    + "|spawnMonsters=true|blockLight=" + naturalBlockLight);
        }

        if (naturalAttempts > 0) {
            logNaturalProbe(naturalCancelled == naturalAttempts);
            return;
        }
        if (waitedTicks >= 1_200) {
            logNaturalProbe(false);
            return;
        }
        Bukkit.getScheduler().runTaskLater(this,
                () -> awaitNaturalProbe(world, base, waitedTicks + 20), 20L);
    }

    private void logNaturalProbe(boolean cancelled) {
        boolean passed = naturalPlayerReady && naturalWindowStarted && naturalBlockLight == 0
                && naturalAttempts > 0 && naturalCancelled == naturalAttempts;
        getLogger().info("RANDOM_SPAWN_NATURAL_CASE|passed=" + (cancelled && passed)
                + "|reason=NATURAL|observed=" + naturalAttempts + "|cancelled=" + naturalCancelled
                + "|playerReady=" + naturalPlayerReady + "|blockLight=" + naturalBlockLight);
        boolean allPassed = importerPassed && syntheticPassed && addClusterPassed && addConditionsPassed
                && addBorderPassed && addPlayerRadiusPassed && playerModePassed
                && cancelled && passed;
        getLogger().info("RANDOM_SPAWN_PAPER_FIXTURE|passed=" + allPassed
                + "|paper=" + Bukkit.getVersion().replace('|', '/')
                + "|api=" + Bukkit.getBukkitVersion()
                + "|importer=" + importerPassed
                + "|synthetic=" + syntheticPassed
                + "|addCluster=" + addClusterPassed
                + "|addConditions=" + addConditionsPassed
                + "|addBorder=" + addBorderPassed
                + "|addPlayerRadius=" + addPlayerRadiusPassed
                + "|playerMode=" + playerModePassed
                + "|natural=" + (cancelled && passed));
    }

    private void runAddClusterProbe(World world) throws Exception {
        var player = Bukkit.getPlayerExact("RandomSpawnProbe");
        require(player != null && player.isOnline()
                && (player.getGameMode() == org.bukkit.GameMode.SURVIVAL
                || player.getGameMode() == org.bukkit.GameMode.ADVENTURE),
                "eligible ADD player is unavailable");
        var helstera = Bukkit.getPluginManager().getPlugin("helsteraMobs");
        Field serviceField = helstera.getClass().getDeclaredField("randomSpawnService");
        serviceField.setAccessible(true);
        Object service = serviceField.get(helstera);
        YamlConfiguration imported = YamlConfiguration.loadConfiguration(generatedRuntimeConfig.toFile());
        ConfigurationSection importedAdd = imported.getConfigurationSection("random-spawns.clusteradd");
        require(importedAdd != null && "ADD".equalsIgnoreCase(importedAdd.getString("action", "")),
                "production importer did not produce the ADD rule");
        YamlConfiguration isolated = new YamlConfiguration();
        ConfigurationSection add = isolated.createSection("random-spawns.clusteradd");
        for (String key : importedAdd.getKeys(false)) {
            if ("types".equalsIgnoreCase(key)) {
                ConfigurationSection types = importedAdd.getConfigurationSection(key);
                java.util.Map<String, Object> values = new LinkedHashMap<>();
                for (String mob : types.getKeys(false)) values.put(mob, types.get(mob));
                add.set(key, values);
            } else {
                add.set(key, importedAdd.get(key));
            }
        }
        Path addOnly = helstera.getDataFolder().toPath().resolve("random-spawn-add-only.yml");
        isolated.save(addOnly.toFile());
        service.getClass().getMethod("loadFile", Path.class).invoke(service, addOnly);
        Class<?> settingsType = Class.forName(
                "dev.helstera.ai.spawner.RandomSpawnService$AddSettings", true,
                helstera.getClass().getClassLoader());
        Object settings = settingsType.getConstructor(String.class, int.class, int.class, int.class,
                int.class, int.class).newInstance("CLUSTER", 2, 8, 16, 1, 70);
        service.getClass().getMethod("configureAdd", settingsType).invoke(service, settings);
        int before = namedZombies(world, player.getLocation(), "Random Spawn Replacement", 12);
        Object spawned = service.getClass().getMethod("tickAdd", java.util.Collection.class)
                .invoke(service, List.of(player));
        int after = namedZombies(world, player.getLocation(), "Random Spawn Replacement", 12);
        addClusterPassed = ((Number) spawned).intValue() == 1 && after == before + 1;
        Object disabled = settingsType.getConstructor(String.class, int.class, int.class, int.class,
                int.class, int.class).newInstance("NONE", 12, 64, 16, 1, 70);
        getLogger().info("RANDOM_SPAWN_ADD|passed=" + addClusterPassed
                + "|generator=CLUSTER|position=LAND|spawned=" + spawned
                + "|before=" + before + "|after=" + after
                + "|playerMode=" + player.getGameMode().name());

        ConfigurationSection importedConditional = imported.getConfigurationSection(
                "random-spawns.locationconditionaladd");
        ConfigurationSection wrongWorld = imported.getConfigurationSection(
                "random-spawns.wrongworldconditionaladd");
        require(importedConditional != null && wrongWorld != null
                        && importedConditional.getStringList("conditions").equals(List.of(
                        "world-is integration-world", "time-is night", "weather-is clear",
                        "players-in-radius >0 64 true"))
                        && wrongWorld.getStringList("conditions").equals(List.of("world-is missing-world")),
                "production importer did not project candidate-point conditions");
        Path conditionalAdd = helstera.getDataFolder().toPath().resolve("random-spawn-add-conditions.yml");
        saveIsolatedAdd(importedConditional, conditionalAdd, "locationconditionaladd");
        service.getClass().getMethod("loadFile", Path.class).invoke(service, conditionalAdd);
        service.getClass().getMethod("configureAdd", settingsType).invoke(service, settings);
        world.setStorm(false);
        world.setThundering(false);
        world.setTime(6_000);
        int daytimeSpawned = ((Number) service.getClass().getMethod("tickAdd", java.util.Collection.class)
                .invoke(service, List.of(player))).intValue();
        world.setTime(18_000);
        world.setStorm(true);
        int rainySpawned = ((Number) service.getClass().getMethod("tickAdd", java.util.Collection.class)
                .invoke(service, List.of(player))).intValue();
        world.setStorm(false);
        world.setThundering(false);
        int beforeConditional = namedZombies(world, player.getLocation(), "Random Spawn Replacement", 12);
        int clearNightSpawned = ((Number) service.getClass().getMethod("tickAdd", java.util.Collection.class)
                .invoke(service, List.of(player))).intValue();
        int afterConditional = namedZombies(world, player.getLocation(), "Random Spawn Replacement", 12);

        Path wrongWorldAdd = helstera.getDataFolder().toPath().resolve("random-spawn-add-wrong-world.yml");
        saveIsolatedAdd(wrongWorld, wrongWorldAdd, "wrongworldconditionaladd");
        service.getClass().getMethod("loadFile", Path.class).invoke(service, wrongWorldAdd);
        int beforeWrongWorld = namedZombies(world, player.getLocation(), "Random Spawn Replacement", 12);
        int wrongWorldSpawned = ((Number) service.getClass().getMethod("tickAdd", java.util.Collection.class)
                .invoke(service, List.of(player))).intValue();
        int afterWrongWorld = namedZombies(world, player.getLocation(), "Random Spawn Replacement", 12);
        addConditionsPassed = daytimeSpawned == 0 && rainySpawned == 0 && clearNightSpawned == 1
                && afterConditional == beforeConditional + 1 && wrongWorldSpawned == 0
                && afterWrongWorld == beforeWrongWorld;
        getLogger().info("RANDOM_SPAWN_ADD_CONDITIONS|passed=" + addConditionsPassed
                + "|predicates=world,time,weather|day-spawned=" + daytimeSpawned
                + "|rainy-night-spawned=" + rainySpawned + "|clear-night-spawned=" + clearNightSpawned
                + "|wrong-world-spawned=" + wrongWorldSpawned + "|before=" + beforeConditional
                + "|after=" + afterConditional);

        record PlayerCase(String rule, String condition, int expected) { }
        List<PlayerCase> playerCases = List.of(
                new PlayerCase("playercountadd", "players-in-radius >0 64 true", 1),
                new PlayerCase("playercountrejectedadd", "players-in-radius >0 1 true", 0),
                new PlayerCase("playerwithinadd", "player-within 64", 1),
                new PlayerCase("playerdistanceconditionaladd", "player-within 1", 0),
                new PlayerCase("playernotwithinadd", "player-not-within 1", 1),
                new PlayerCase("playernotwithinrejectedadd", "player-not-within 64", 0));
        Field conditionField = service.getClass().getDeclaredField("addConditionTester");
        conditionField.setAccessible(true);
        @SuppressWarnings("unchecked")
        var productionTester = (java.util.function.BiPredicate<String, Location>) conditionField.get(service);
        require(productionTester != null, "production ADD condition tester is missing");
        int[] evaluations = {0};
        java.util.function.BiPredicate<String, Location> observedTester = (condition, point) -> {
            evaluations[0]++;
            return productionTester.test(condition, point);
        };
        Method setTester = service.getClass().getMethod("setAddConditionTester", java.util.function.BiPredicate.class);
        setTester.invoke(service, observedTester);
        addPlayerRadiusPassed = true;
        try {
        for (PlayerCase playerCase : playerCases) {
            ConfigurationSection rule = imported.getConfigurationSection("random-spawns." + playerCase.rule());
            require(rule != null && rule.getStringList("conditions").equals(List.of(playerCase.condition())),
                    "missing imported player condition: " + playerCase.rule());
            Path playerConfig = helstera.getDataFolder().toPath().resolve("random-spawn-" + playerCase.rule() + ".yml");
            saveIsolatedAdd(rule, playerConfig, playerCase.rule());
            service.getClass().getMethod("loadFile", Path.class).invoke(service, playerConfig);
            evaluations[0] = 0;
            int beforePlayer = namedZombies(world, player.getLocation(), "Random Spawn Replacement", 12);
            int playerSpawned = ((Number) service.getClass().getMethod("tickAdd", java.util.Collection.class)
                    .invoke(service, List.of(player))).intValue();
            int afterPlayer = namedZombies(world, player.getLocation(), "Random Spawn Replacement", 12);
            boolean passed = evaluations[0] > 0 && playerSpawned == playerCase.expected()
                    && afterPlayer == beforePlayer + playerCase.expected();
            addPlayerRadiusPassed &= passed;
            getLogger().info("RANDOM_SPAWN_ADD_PLAYER|id=" + playerCase.rule() + "|passed=" + passed
                    + "|condition=" + playerCase.condition() + "|expected=" + playerCase.expected()
                    + "|spawned=" + playerSpawned + "|before=" + beforePlayer + "|after=" + afterPlayer
                    + "|evaluated=" + evaluations[0]);
        }
        } finally {
            setTester.invoke(service, productionTester);
        }

        runPlayerModeProbe(helstera, player);
        if (getConfig().getBoolean("mob-radius-audit", false)) MobRadiusAudit.run(this, helstera, player);

        ConfigurationSection importedCluster = imported.getConfigurationSection("random-spawns.clusteradd");
        require(importedCluster != null, "production importer did not retain the basic ADD rule");
        Path borderAdd = helstera.getDataFolder().toPath().resolve("random-spawn-add-border.yml");
        saveIsolatedAdd(importedCluster, borderAdd, "clusteradd");
        service.getClass().getMethod("loadFile", Path.class).invoke(service, borderAdd);
        service.getClass().getMethod("configureAdd", settingsType).invoke(service, settings);
        var border = world.getWorldBorder();
        Location oldCenter = border.getCenter();
        double oldSize = border.getSize();
        int beforeBorder = namedZombies(world, player.getLocation(), "Random Spawn Replacement", 12);
        int borderSpawned;
        try {
            Location playerLocation = player.getLocation();
            border.setCenter(playerLocation.getX() + 1_024, playerLocation.getZ() + 1_024);
            border.setSize(16);
            borderSpawned = ((Number) service.getClass().getMethod("tickAdd", java.util.Collection.class)
                    .invoke(service, List.of(player))).intValue();
        } finally {
            border.setCenter(oldCenter.getX(), oldCenter.getZ());
            border.setSize(oldSize);
        }
        int afterBorder = namedZombies(world, player.getLocation(), "Random Spawn Replacement", 12);
        addBorderPassed = borderSpawned == 0 && afterBorder == beforeBorder;
        getLogger().info("RANDOM_SPAWN_ADD_BORDER|passed=" + addBorderPassed
                + "|spawned=" + borderSpawned + "|before=" + beforeBorder + "|after=" + afterBorder);
        service.getClass().getMethod("configureAdd", settingsType).invoke(service, disabled);
        service.getClass().getMethod("loadFile", Path.class).invoke(service, generatedRuntimeConfig);
    }

    private void runPlayerModeProbe(org.bukkit.plugin.Plugin helstera, org.bukkit.entity.Player player)
            throws Exception {
        ClassLoader loader = helstera.getClass().getClassLoader();
        Class<?> contextType = Class.forName("dev.helstera.api.behavior.BehaviorContext", true, loader);
        Method execution = contextType.getMethod("execution",
                Class.forName("dev.helstera.api.instance.ModelInstance", true, loader),
                org.bukkit.entity.Player.class, double.class, double.class, long.class, String.class,
                String.class, Entity.class, List.class, Location.class);
        Field skillsField = helstera.getClass().getDeclaredField("skillService");
        skillsField.setAccessible(true);
        Object skills = skillsField.get(helstera);
        Field registryField = helstera.getClass().getDeclaredField("behaviorRegistry");
        registryField.setAccessible(true);
        Object registry = registryField.get(helstera);
        Method bind = skills.getClass().getMethod("bindCondition", String.class);
        Method test = registry.getClass().getMethod("testCondition", String.class, contextType);
        String[] specs = {"players-in-radius >0 1 true", "players-in-radius >0 1 false",
                "player-within 1", "player-not-within 1", "players-in-radius 0 1 true",
                "players-in-radius 1 1 true"};
        String[] keys = new String[specs.length];
        for (int i = 0; i < specs.length; i++) {
            keys[i] = (String) bind.invoke(skills, specs[i]);
            require(keys[i] != null, "unbound player condition: " + specs[i]);
        }
        var oldMode = player.getGameMode();
        playerModePassed = true;
        try {
            for (var mode : org.bukkit.GameMode.values()) {
                player.setGameMode(mode);
                Object context = execution.invoke(null, null, null, 1.0, -1.0, 0L,
                        "random-spawn-player-mode", null, null, List.of(), player.getLocation());
                boolean eligible = mode == org.bukkit.GameMode.SURVIVAL || mode == org.bukkit.GameMode.ADVENTURE;
                boolean passed = true;
                StringBuilder actual = new StringBuilder();
                for (int i = 0; i < keys.length; i++) {
                    boolean result = (boolean) test.invoke(registry, keys[i], context);
                    boolean expected = i == 4 ? !eligible : (i < 3 || i == 5) && eligible;
                    passed &= result == expected;
                    if (i > 0) actual.append(',');
                    actual.append(result);
                }
                playerModePassed &= passed;
                getLogger().info("RANDOM_SPAWN_PLAYER_MODE|mode=" + mode + "|passed=" + passed
                        + "|count-default,count-include-spectator,within,not-within,count-zero,count-one=" + actual);
            }
            player.setGameMode(org.bukkit.GameMode.SURVIVAL);
            Object corner = execution.invoke(null, null, null, 1.0, -1.0, 0L,
                    "random-spawn-player-corner", null, null, List.of(), player.getLocation().add(0.9, 0, 0.9));
            boolean spherical = !(boolean) test.invoke(registry, keys[0], corner)
                    && !(boolean) test.invoke(registry, keys[2], corner)
                    && (boolean) test.invoke(registry, keys[3], corner);
            playerModePassed &= spherical;
            getLogger().info("RANDOM_SPAWN_PLAYER_SPHERE|passed=" + spherical + "|radius=1|offset=0.9,0,0.9");
        } finally {
            player.setGameMode(oldMode);
        }
        playerModePassed &= player.getGameMode() == oldMode;
        getLogger().info("RANDOM_SPAWN_PLAYER_SEMANTICS|passed=" + playerModePassed
                + "|mode-restored=" + (player.getGameMode() == oldMode));
        runNativePlayerSyntaxProbe(helstera, player, execution, skills, registry, bind, test);
    }

    private void runNativePlayerSyntaxProbe(org.bukkit.plugin.Plugin helstera, org.bukkit.entity.Player player,
                                            Method execution, Object skills, Object registry, Method bind,
                                            Method test) throws Exception {
        var mythic = Bukkit.getPluginManager().getPlugin("MythicMobs");
        require(mythic != null && mythic.isEnabled(), "pinned MythicMobs comparison plugin unavailable");
        ClassLoader nativeLoader = mythic.getClass().getClassLoader();
        Class<?> configType = Class.forName("io.lumine.mythic.api.config.MythicLineConfig", true, nativeLoader);
        Class<?> configImpl = Class.forName("io.lumine.mythic.core.config.MythicLineConfigImpl", true, nativeLoader);
        Class<?> locationType = Class.forName("io.lumine.mythic.api.adapters.AbstractLocation", true, nativeLoader);
        Method adapt = Class.forName("io.lumine.mythic.bukkit.BukkitAdapter", true, nativeLoader)
                .getMethod("adapt", Location.class);
        Method translate = Class.forName("dev.helstera.migration.importer.MmConditions", true,
                helstera.getClass().getClassLoader()).getMethod("translate", String.class);
        record SyntaxCase(String line, String type) { }
        List<SyntaxCase> cases = List.of(
                new SyntaxCase("playersInRadius", "PlayersInRadiusCondition"),
                new SyntaxCase("playersInRadius{r=4}", "PlayersInRadiusCondition"),
                new SyntaxCase("playersInRadius 1", "PlayersInRadiusCondition"),
                new SyntaxCase("playersInRadius >1", "PlayersInRadiusCondition"),
                new SyntaxCase("playersInRadius{r=4} <2", "PlayersInRadiusCondition"),
                new SyntaxCase("playersInRadius{r=4} >0", "PlayersInRadiusCondition"),
                new SyntaxCase("playersInRadius{r=4;a=1;is=false}", "PlayersInRadiusCondition"),
                new SyntaxCase("playerWithin", "PlayerWithinCondition"),
                new SyntaxCase("playerNotWithin{}", "PlayerNotWithinCondition"),
                new SyntaxCase("playerWithin{} 4", "PlayerWithinCondition"),
                new SyntaxCase("playerNotWithin 4", "PlayerNotWithinCondition"));
        var oldMode = player.getGameMode();
        int comparisons = 0;
        int mismatches = 0;
        try {
            for (var syntax : cases) {
                Object mapped = translate.invoke(null, "?" + syntax.line());
                require((boolean) mapped.getClass().getMethod("translated").invoke(mapped), syntax.line());
                String runtime = (String) mapped.getClass().getMethod("condition").invoke(mapped);
                String key = (String) bind.invoke(skills, runtime);
                require(key != null, "unbound imported condition: " + runtime);
                Class<?> nativeType = Class.forName("io.lumine.mythic.core.skills.conditions.all." + syntax.type(),
                        true, nativeLoader);
                Object config = configImpl.getConstructor(String.class).newInstance(syntax.line());
                Object nativeCondition = nativeType.getConstructor(String.class, configType)
                        .newInstance(syntax.line(), config);
                Method nativeCheck = nativeType.getMethod("check", locationType);
                int caseMismatches = 0;
                for (var mode : org.bukkit.GameMode.values()) {
                    player.setGameMode(mode);
                    for (double offset : new double[]{0, 1, 40}) {
                        Location location = player.getLocation().add(offset, 0, 0);
                        Object context = execution.invoke(null, null, null, 1.0, -1.0, 0L,
                                "player-native-comparison", null, null, List.of(), location);
                        boolean importedResult = (boolean) test.invoke(registry, key, context);
                        boolean nativeResult = (boolean) nativeCheck.invoke(nativeCondition, adapt.invoke(null, location));
                        comparisons++;
                        if (importedResult != nativeResult) caseMismatches++;
                        getLogger().info("PLAYER_SYNTAX_COMPARE|source=" + syntax.line() + "|mode=" + mode
                                + "|offset=" + offset + "|native=" + nativeResult + "|imported=" + importedResult);
                    }
                }
                mismatches += caseMismatches;
                getLogger().info("PLAYER_SYNTAX_CASE|source=" + syntax.line() + "|runtime=" + runtime
                        + "|comparisons=12|mismatches=" + caseMismatches);
            }
        } finally {
            player.setGameMode(oldMode);
        }
        boolean passed = comparisons == 132 && mismatches == 0 && player.getGameMode() == oldMode;
        playerModePassed &= passed;
        getLogger().info("PLAYER_SYNTAX_NATIVE|passed=" + passed + "|cases=" + cases.size()
                + "|comparisons=" + comparisons + "|mismatches=" + mismatches
                + "|mode-restored=" + (player.getGameMode() == oldMode));
        require(passed, "native/imported player condition syntax mismatch");
        for (String extension : List.of("playersInRadius >=2", "playersInRadius{r=4} <=1")) {
            Class<?> nativeType = Class.forName("io.lumine.mythic.core.skills.conditions.all.PlayersInRadiusCondition",
                    true, nativeLoader);
            boolean nativeRejected = false;
            try {
                nativeType.getConstructor(String.class, configType).newInstance(extension,
                        configImpl.getConstructor(String.class).newInstance(extension));
            } catch (java.lang.reflect.InvocationTargetException error) {
                if (!(error.getCause() instanceof NumberFormatException)) throw error;
                nativeRejected = true;
            }
            Object mapped = translate.invoke(null, "?" + extension);
            boolean translated = (boolean) mapped.getClass().getMethod("translated").invoke(mapped);
            String runtime = (String) mapped.getClass().getMethod("condition").invoke(mapped);
            boolean importedBound = translated && bind.invoke(skills, runtime) != null;
            getLogger().info("PLAYER_SYNTAX_EXTENSION|source=" + extension
                    + "|nativeRejected=" + nativeRejected + "|importedBound=" + importedBound);
            require(nativeRejected && importedBound, "inclusive comparator compatibility boundary changed");
        }
    }

    private void saveIsolatedAdd(ConfigurationSection importedAdd, Path destination, String id) throws Exception {
        YamlConfiguration isolated = new YamlConfiguration();
        ConfigurationSection add = isolated.createSection("random-spawns." + id);
        for (String key : importedAdd.getKeys(false)) {
            if ("types".equalsIgnoreCase(key)) {
                ConfigurationSection types = importedAdd.getConfigurationSection(key);
                java.util.Map<String, Object> values = new LinkedHashMap<>();
                for (String mob : types.getKeys(false)) values.put(mob, types.get(mob));
                add.set(key, values);
            } else {
                add.set(key, importedAdd.get(key));
            }
        }
        isolated.save(destination.toFile());
    }

    private int namedZombies(World world, Location center, String expectedName) {
        return namedZombies(world, center, expectedName, 4);
    }

    private int namedZombies(World world, Location center, String expectedName, double radius) {
        return (int) world.getNearbyEntities(center, radius, radius, radius).stream()
                .filter(Zombie.class::isInstance)
                .map(Entity::getCustomName)
                .filter(expectedName::equals)
                .count();
    }

    private boolean isValid(String id) {
        LivingEntity entity = originals.get(id);
        return entity != null && entity.isValid();
    }

    private void log(String id, boolean passed, EventObservation event,
                     boolean originalValid, int namedZombieCount) {
        getLogger().info("RANDOM_SPAWN_CASE|id=" + id + "|passed=" + passed
                + "|cancelled=" + (event != null && event.cancelled())
                + "|reason=" + (event == null ? "missing" : event.reason())
                + "|originalValid=" + originalValid + "|namedZombieCount=" + namedZombieCount);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
