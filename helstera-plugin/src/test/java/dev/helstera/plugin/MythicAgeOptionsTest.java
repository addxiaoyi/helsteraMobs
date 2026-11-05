package dev.helstera.plugin;

import dev.helstera.api.instance.SpawnOptions;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Ageable;
import org.bukkit.entity.Bee;
import org.bukkit.entity.Creeper;
import org.bukkit.entity.Phantom;
import org.bukkit.entity.PigZombie;
import org.bukkit.entity.Raider;
import org.bukkit.entity.Slime;
import org.bukkit.entity.Tameable;
import org.bukkit.entity.Wolf;
import org.bukkit.entity.Horse;
import org.bukkit.entity.Cat;
import org.bukkit.entity.Sheep;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.FallingBlock;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionType;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MythicAgeOptionsTest {
    @Test
    void interactionPolicyCombinesInteractableAndPreventItemUse() {
        assertFalse(HelsteraPlugin.shouldCancelEntityInteraction(SpawnOptions.defaults()));
        assertTrue(HelsteraPlugin.shouldCancelEntityInteraction(
                SpawnOptions.defaults().interactable(false)));
        assertTrue(HelsteraPlugin.shouldCancelEntityInteraction(
                SpawnOptions.defaults().preventItemUse(true)));
    }


    @Test
    void importedEntityOptionsDoNotHideTheMobEntityType() {
        var config = YamlConfiguration.loadConfiguration(new java.io.StringReader("""
                mob:
                  entity-type: HUSK
                entity:
                  age: -1
                """));
        assertEquals("HUSK", HelsteraPlugin.configuredEntityType(
                config.getConfigurationSection("entity"), config.getConfigurationSection("mob")));

        var explicit = YamlConfiguration.loadConfiguration(new java.io.StringReader("""
                mob:
                  entity-type: HUSK
                entity:
                  type: ZOMBIE
                  age: -1
                """));
        assertEquals("ZOMBIE", HelsteraPlugin.configuredEntityType(
                explicit.getConfigurationSection("entity"), explicit.getConfigurationSection("mob")));
    }

    @Test
    void appliesAgeBabyAndAgeLockToAgeableEntity() {
        Map<String, Object> calls = new LinkedHashMap<>();
        Ageable entity = (Ageable) Proxy.newProxyInstance(Ageable.class.getClassLoader(),
                new Class<?>[]{Ageable.class}, (proxy, method, args) -> {
                    if (method.getName().startsWith("set")) {
                        calls.put(method.getName(), args == null || args.length == 0 ? true : args[0]);
                    }
                    Class<?> type = method.getReturnType();
                    if (type == boolean.class) return false;
                    if (type == int.class) return 0;
                    return null;
                });
        var options = YamlConfiguration.loadConfiguration(new java.io.StringReader("""
                age: -1
                baby: true
                age-lock: true
                """));
        assertTrue(options.contains("baby"), options.getKeys(false).toString());
        assertTrue(options.getBoolean("baby"));

        HelsteraPlugin.applyAgeOptions(entity, options);

        assertEquals(-1, calls.get("setAge"));
        assertEquals(true, calls.get("setBaby"), calls.toString());
        assertEquals(true, calls.get("setAgeLock"));

        calls.clear();
        HelsteraPlugin.applyAgeOptions(entity,
                YamlConfiguration.loadConfiguration(new java.io.StringReader("adult: true")));
        assertEquals(true, calls.get("setAdult"), calls.toString());
    }

    @Test
    void appliesZombieBabyOptionsThroughTheZombieSpecificSetter() {
        Map<String, Object> calls = new LinkedHashMap<>();
        org.bukkit.entity.Zombie entity = proxy(org.bukkit.entity.Zombie.class, calls);
        var options = YamlConfiguration.loadConfiguration(new java.io.StringReader("""
                age: -24000
                baby: true
                """));

        HelsteraPlugin.applyAgeOptions(entity, options);

        assertEquals(-24000, calls.get("setAge"));
        assertEquals(true, calls.get("setBaby"), calls.toString());

        calls.clear();
        HelsteraPlugin.applyAgeOptions(entity,
                YamlConfiguration.loadConfiguration(new java.io.StringReader("adult: true")));
        assertEquals(false, calls.get("setBaby"), calls.toString());
    }

    @Test
    void appliesPickupOverrideAndInitialNoDamageTicks() {
        Map<String, Object> calls = new LinkedHashMap<>();
        var mob = proxy(org.bukkit.entity.Mob.class, calls);
        var explicitPickup = YamlConfiguration.loadConfiguration(
                new java.io.StringReader("can-pickup-items: true"));
        HelsteraPlugin.applyMobPickupOption(mob, explicitPickup,
                SpawnOptions.defaults().preventItemPickup(true));
        assertEquals(true, calls.get("setCanPickupItems"),
                "explicit mob pickup configuration overrides policy fallback");

        calls.clear();
        HelsteraPlugin.applyMobPickupOption(mob,
                YamlConfiguration.loadConfiguration(new java.io.StringReader("")),
                SpawnOptions.defaults().preventItemPickup(true));
        assertEquals(false, calls.get("setCanPickupItems"),
                "PreventItemPickup supplies the fallback only when entity config is absent");

        calls.clear();
        HelsteraPlugin.applySpawnNoDamageTicks(mob, SpawnOptions.defaults().noDamageTicks(19));
        assertEquals(19, calls.get("setNoDamageTicks"));
    }

    @Test
    void appliesRaidAndTameOptionsOnlyThroughMatchingBukkitInterfaces() {
        Map<String, Object> calls = new LinkedHashMap<>();
        var patrolBlock = Proxy.newProxyInstance(org.bukkit.block.Block.class.getClassLoader(),
                new Class<?>[]{org.bukkit.block.Block.class}, (proxy, method, args) -> {
                    Class<?> type = method.getReturnType();
                    if (type == boolean.class) return false;
                    if (type == int.class) return 0;
                    return null;
                });
        var patrolWorld = Proxy.newProxyInstance(org.bukkit.World.class.getClassLoader(),
                new Class<?>[]{org.bukkit.World.class}, (proxy, method, args) ->
                        method.getName().equals("getBlockAt") ? patrolBlock : null);
        var patrolLocation = new org.bukkit.Location((org.bukkit.World) patrolWorld, 0, 64, 0);
        Raider raider = (Raider) Proxy.newProxyInstance(Raider.class.getClassLoader(),
                new Class<?>[]{Raider.class}, (proxy, method, args) -> {
                    if (method.getName().startsWith("set")) {
                        calls.put(method.getName(), args == null || args.length == 0 ? true : args[0]);
                    }
                    if (method.getName().equals("getLocation")) return patrolLocation;
                    Class<?> type = method.getReturnType();
                    if (type == boolean.class) return false;
                    if (type == int.class) return 0;
                    return null;
                });
        Tameable tameable = (Tameable) Proxy.newProxyInstance(Tameable.class.getClassLoader(),
                new Class<?>[]{Tameable.class}, (proxy, method, args) -> {
                    if (method.getName().startsWith("set")) {
                        calls.put(method.getName(), args == null || args.length == 0 ? true : args[0]);
                    }
                    Class<?> type = method.getReturnType();
                    if (type == boolean.class) return false;
                    if (type == int.class) return 0;
                    return null;
                });
        var options = YamlConfiguration.loadConfiguration(new java.io.StringReader("""
                can-join-raid: false
                patrol-leader: true
                patrol-spawn-point: true
                tamed: true
                """));

        HelsteraPlugin.applyEntitySpecificOptions(raider, options);
        HelsteraPlugin.applyEntitySpecificOptions(tameable, options);

        assertEquals(false, calls.get("setCanJoinRaid"));
        assertEquals(true, calls.get("setPatrolLeader"));
        assertTrue(calls.get("setPatrolTarget") == patrolBlock);
        assertEquals(true, calls.get("setTamed"));
    }

    @Test
    void appliesSizeToSlimesAndPhantoms() {
        Map<String, Object> slimeCalls = new LinkedHashMap<>();
        Map<String, Object> phantomCalls = new LinkedHashMap<>();
        Slime slime = proxy(Slime.class, slimeCalls);
        Phantom phantom = proxy(Phantom.class, phantomCalls);
        var options = YamlConfiguration.loadConfiguration(new java.io.StringReader("size: 6"));

        HelsteraPlugin.applyEntitySpecificOptions(slime, options);
        HelsteraPlugin.applyEntitySpecificOptions(phantom, options);

        assertEquals(6, slimeCalls.get("setSize"));
        assertEquals(6, phantomCalls.get("setSize"));
    }

    @Test
    void appliesSaddledOptionThroughSteerableApi() {
        Map<String, Object> calls = new LinkedHashMap<>();
        var steerable = proxy(org.bukkit.entity.Steerable.class, calls);
        HelsteraPlugin.applyEntitySpecificOptions(steerable,
                YamlConfiguration.loadConfiguration(new java.io.StringReader("saddled: true")));
        assertEquals(true, calls.get("setSaddle"), calls.toString());
    }

    @Test
    void appliesCarryingChestOptionThroughChestedHorseApi() {
        Map<String, Object> calls = new LinkedHashMap<>();
        var chestedHorse = proxy(org.bukkit.entity.ChestedHorse.class, calls);
        HelsteraPlugin.applyEntitySpecificOptions(chestedHorse,
                YamlConfiguration.loadConfiguration(new java.io.StringReader("carrying-chest: true")));
        assertEquals(true, calls.get("setCarryingChest"), calls.toString());
    }

    @Test
    void appliesAngryStateToWolvesAndZombifiedPiglins() {
        Map<String, Object> wolfCalls = new LinkedHashMap<>();
        Map<String, Object> piglinCalls = new LinkedHashMap<>();
        var options = YamlConfiguration.loadConfiguration(new java.io.StringReader("angry: true"));

        HelsteraPlugin.applyEntitySpecificOptions(proxy(Wolf.class, wolfCalls), options);
        HelsteraPlugin.applyEntitySpecificOptions(proxy(PigZombie.class, piglinCalls), options);

        assertEquals(true, wolfCalls.get("setAngry"));
        assertEquals(true, piglinCalls.get("setAngry"));
    }

    @Test
    void jockeyMountGuardIsLimitedToZombiesAndGenericMountGuardRemainsGlobal() {
        var zombieOnly = SpawnOptions.defaults().preventJockeyMounts(true);
        assertTrue(HelsteraPlugin.preventsEntityMount(zombieOnly, org.bukkit.entity.EntityType.ZOMBIE));
        assertFalse(HelsteraPlugin.preventsEntityMount(zombieOnly, org.bukkit.entity.EntityType.HUSK));

        var allTypes = SpawnOptions.defaults().preventMount(true);
        assertTrue(HelsteraPlugin.preventsEntityMount(allTypes, org.bukkit.entity.EntityType.HUSK));
    }

    @Test
    void appliesCreeperExplosionOptionsAndIgnoresNegativeDefaults() {
        Map<String, Object> calls = new LinkedHashMap<>();
        Creeper creeper = proxy(Creeper.class, calls);
        var options = YamlConfiguration.loadConfiguration(new java.io.StringReader("""
                creeper-explosion-radius: 5
                creeper-fuse-ticks: 60
                creeper-super-charged: true
                """));

        HelsteraPlugin.applyEntitySpecificOptions(creeper, options);

        assertEquals(5, calls.get("setExplosionRadius"));
        assertEquals(60, calls.get("setMaxFuseTicks"));
        assertEquals(true, calls.get("setPowered"));

        Map<String, Object> defaults = new LinkedHashMap<>();
        HelsteraPlugin.applyEntitySpecificOptions(proxy(Creeper.class, defaults),
                YamlConfiguration.loadConfiguration(new java.io.StringReader("""
                        creeper-explosion-radius: -1
                        creeper-fuse-ticks: -1
                        """)));
        org.junit.jupiter.api.Assertions.assertTrue(defaults.isEmpty());
    }

    @Test
    void appliesBeeNectarAndStingerState() {
        Map<String, Object> calls = new LinkedHashMap<>();
        var bee = proxy(Bee.class, calls);
        var options = YamlConfiguration.loadConfiguration(new java.io.StringReader("""
                has-nectar: true
                has-stung: false
                """));

        HelsteraPlugin.applyEntitySpecificOptions(bee, options);

        assertEquals(true, calls.get("setHasNectar"));
        assertEquals(false, calls.get("setHasStung"));
    }

    @Test
    void appliesLegacyMigratedPotionTypeToPotionMeta() {
        Map<String, Object> calls = new LinkedHashMap<>();
        PotionMeta meta = proxy(PotionMeta.class, calls);
        var options = YamlConfiguration.loadConfiguration(new java.io.StringReader("""
                base-potion-type: LONG_SWIFTNESS
                """));

        HelsteraPlugin.applyPotionItemEffects(meta, options);

        assertEquals(PotionType.LONG_SWIFTNESS, calls.get("setBasePotionType"));
    }

    @Test
    void parsesNamespacedItemEnchantmentsWithoutDroppingLevels() {
        var parsed = HelsteraPlugin.parseItemSpec(
                "minecraft:diamond:2:SHARPNESS:5,LOOTING:2");

        assertEquals("DIAMOND", parsed.material());
        assertEquals(2, parsed.amount());
        assertEquals(List.of(
                new HelsteraPlugin.ParsedEnchantment("SHARPNESS", 5),
                new HelsteraPlugin.ParsedEnchantment("LOOTING", 2)),
                parsed.enchantments());
    }

    @Test
    void itemSpecRejectsExternalIdentifiersWithoutTreatingThemAsEnchantments() {
        assertNull(HelsteraPlugin.parseItemSpec("stone:custom"));
        assertNull(HelsteraPlugin.parseItemSpec("minecraft:stone:custom"));
        assertNull(HelsteraPlugin.parseItemSpec("stone:"));
        assertEquals("STONE", HelsteraPlugin.parseItemSpec("minecraft:stone").material());
        assertEquals(2, HelsteraPlugin.parseItemSpec("stone:2").amount());
        assertEquals(List.of(new HelsteraPlugin.ParsedEnchantment("SHARPNESS", 3)),
                HelsteraPlugin.parseItemSpec("stone:1:sharpness:3").enchantments());
    }

    @Test
    void readsDelimitedReasonOptionsAsRuntimeLists() {
        var config = YamlConfiguration.loadConfiguration(new java.io.StringReader("""
                mob:
                  prevent-transformation-reasons: DROWNED, CURED|INFECTION
                """));

        assertEquals(List.of("DROWNED", "CURED", "INFECTION"),
                HelsteraPlugin.configuredStringList(config,
                        "mob.prevent-transformation-reasons", "prevent-transformation-reasons"));
    }

    @Test
    void emptyNestedReasonListFallsBackToLegacyOption() {
        var config = YamlConfiguration.loadConfiguration(new java.io.StringReader("""
                mob:
                  prevent-transformation-reasons: []
                prevent-transformation-reasons: DROWNED,CURED
                """));

        assertEquals(List.of("DROWNED", "CURED"),
                HelsteraPlugin.configuredStringList(config,
                        "mob.prevent-transformation-reasons", "prevent-transformation-reasons"));
    }

    @Test
    void appliesHorseCatSheepAndArmorStandOptions() {
        Map<String, Object> horseCalls = new LinkedHashMap<>();
        Map<String, Object> catCalls = new LinkedHashMap<>();
        Map<String, Object> sheepCalls = new LinkedHashMap<>();
        Map<String, Object> standCalls = new LinkedHashMap<>();
        var options = YamlConfiguration.loadConfiguration(new java.io.StringReader("""
                horse-color: WHITE
                horse-style: WHITE_DOTS
                horse-max-domestication: 20
                horse-domestication: 5
                horse-jump-strength: 0.8
                carrying-chest: true
                cat-type: SIAMESE
                cat-sitting: true
                cat-collar-color: BLUE
                sheep-color: RED
                sheep-sheared: true
                armor-stand-arms: true
                armor-stand-baseplate: false
                armor-stand-marker: true
                armor-stand-small: true
                armor-stand-can-move: false
                armor-stand-can-tick: false
                """));

        HelsteraPlugin.applyEntitySpecificOptions(proxy(Horse.class, horseCalls), options);
        HelsteraPlugin.applyEntitySpecificOptions(proxy(Sheep.class, sheepCalls), options);
        HelsteraPlugin.applyEntitySpecificOptions(proxy(ArmorStand.class, standCalls), options);

        assertEquals(Horse.Color.WHITE, horseCalls.get("setColor"));
        assertEquals(Horse.Style.WHITE_DOTS, horseCalls.get("setStyle"));
        assertEquals(20, horseCalls.get("setMaxDomestication"));
        assertEquals(5, horseCalls.get("setDomestication"));
        assertEquals(0.8, horseCalls.get("setJumpStrength"));
        assertEquals(true, horseCalls.get("setCarryingChest"));
        assertEquals(org.bukkit.DyeColor.RED, sheepCalls.get("setColor"));
        assertEquals(true, sheepCalls.get("setSheared"));
        assertEquals(true, standCalls.get("setArms"));
        assertEquals(false, standCalls.get("setBasePlate"));
        assertEquals(true, standCalls.get("setMarker"));
        assertEquals(true, standCalls.get("setSmall"));
        assertEquals(false, standCalls.get("setCanMove"));
        assertEquals(false, standCalls.get("setCanTick"));
    }

    @Test
    void appliesFallingBlockOptionsWithoutLivingEntityAssumptions() {
        Map<String, Object> calls = new LinkedHashMap<>();
        FallingBlock falling = proxy(FallingBlock.class, calls);
        var options = YamlConfiguration.loadConfiguration(new java.io.StringReader("""
                persistent: false
                gravity: false
                falling-block-drop-item: false
                falling-block-hurt-entities: true
                falling-block-cancel-drop: true
                """));

        HelsteraPlugin.applyNonLivingEntityOptions(falling, options);

        assertEquals(false, calls.get("setPersistent"));
        assertEquals(false, calls.get("setGravity"));
        assertEquals(false, calls.get("setDropItem"));
        assertEquals(true, calls.get("setHurtEntities"));
        assertEquals(true, calls.get("setCancelDrop"));
    }

    private static <T> T proxy(Class<T> type, Map<String, Object> calls) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, args) -> {
                    if (method.getName().startsWith("set")) {
                        calls.put(method.getName(), args == null || args.length == 0 ? true : args[0]);
                    }
                    Class<?> returnType = method.getReturnType();
                    if (returnType == boolean.class) return false;
                    if (returnType == int.class) return 0;
                    if (returnType == long.class) return 0L;
                    if (returnType == float.class) return 0f;
                    if (returnType == double.class) return 0d;
                    return null;
                }));
    }
}
