package dev.helstera.migration.importer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MM 条件叶子 → helstera 条件名的翻译测试。
 *
 * <p>这张表错了不会编译失败、不会抛异常，只会让免疫规则<b>永远不生效</b>
 * （条件求值 fail-closed）——与「没迁移」对用户完全等价。
 * 所以逐条钉死，不靠抽样。</p>
 */
class MmConditionsTest {

    private static String t(String raw) {
        return MmConditions.translate(raw).condition();
    }

    private static boolean translated(String raw) {
        return MmConditions.translate(raw).translated();
    }

    private static void assertUntranslatedPreserved(String raw) {
        var result = MmConditions.translate(raw);
        assertFalse(result.translated(), raw);
        assertEquals(raw, result.condition(), raw);
    }

    @Test
    @DisplayName("血量百分比换成 0..1 比值，不换算会让条件恒假")
    void healthPercentIsConverted() {
        // 不换算会得到 health-below 50，而比值上限是 1.0 -> 条件永不成立 -> 规则永不生效
        assertEquals("health <50", t("?health{<50}"));
        assertEquals("health >30", t("?health{>30}"));
        assertEquals("health <25", t("?health{<25}"));
        assertEquals("health <50", t("?health{amount=<50}"));
        assertEquals("health >50 include-absorption=true",
                t("?health{h=>50;includeabsorption=true}"));
        assertEquals("health-percent >=50 include-absorption=true",
                t("?healthPercent{p=>=50%;ia=true}"));
        assertEquals("health >10 include-absorption=true", t("?health{>10;ia=true}"));
        assertEquals("health-percent >=50 include-absorption=true", t("?healthPercent{ia=true;>=0.5}"));
        assertEquals("health =10", t("?health{ia=false;10}"));
        assertFalse(translated("?health{ia=true}"));
    }

    @Test
    @DisplayName("不带百分号的数值：>1 视为百分数，<=1 视为比值")
    void bareNumbersInferScale() {
        assertEquals("health <0.5", t("?health{<0.5}"));
        assertEquals("health <50", t("?health{<50}"));
        assertEquals("health-percent <50", t("?healthPercent{p=<0.5}"));
        assertEquals("health-percent >10", t("?hppercent{p=>0.1}"));
        assertFalse(translated("?health{<50%}"));
        assertFalse(translated("?health{h=10;ia=maybe}"));
        assertFalse(translated("?health{h=NaN}"));
        for (String invalid : List.of("?health{h=10;amount=20}",
                "?health{h=10;ia=true;includeabsorption=false}",
                "?health{h=10;20}", "?healthpercent{p=1e308}")) {
            assertFalse(translated(invalid), invalid);
            assertEquals(invalid, t(invalid));
        }
        assertEquals("health >1.0E20", t("?health{h=>1e20}"));
    }

    @Test
    void healthRangesPreserveBoundsUnitsAndAbsorption() {
        assertEquals("all{health >=10;health <=20}", t("?health{h=10-20}"));
        assertEquals("all{health >=-5 include-absorption=true;health <=10 include-absorption=true}",
                t("?health{-5to10;ia=true}"));
        assertEquals("all{health-percent >=25;health-percent <=50}", t("?healthpercent{p=0.25to0.5}"));
        assertEquals("all{health-percent >=25;health-percent <=50}", t("?healthpercent{p=25%to50%}"));
        for (String invalid : List.of("?health{20to10}", "?health{-5-10}", "?health{10%to20%}",
                "?healthpercent{p=25%to0.5}", "?healthpercent{p=1e307to1e308}")) {
            assertFalse(translated(invalid), invalid);
            assertEquals(invalid, t(invalid));
        }
    }

    @Test
    void targetHealthPreservesAbsoluteAndPercentageUnits() {
        assertEquals("target-health =10", t("?targetHealth{h=10}"));
        assertEquals("target-health-percent >=50", t("?targetHealthPercent{p=>=0.5}"));
        assertEquals("target-health-percent <50", t("?targetHealthPercent{p=<50%}"));
        for (String invalid : List.of("?targetHealth{50%}", "?targetHealthPercent{1e308}",
                "?targetHealthPercent{p=NaN}")) {
            assertFalse(translated(invalid), invalid);
            assertEquals(invalid, t(invalid));
        }
    }

    @Test
    @DisplayName("比较方向正确，不把 < 翻成 >")
    void directionIsPreserved() {
        assertEquals("health <20", t("?health{<20}"));
        assertEquals("health >80", t("?health{>80}"));
        assertFalse(t("?health{<20}").equals(t("?health{>20}")),
                "方向翻错会让免疫条件恰好作用在反面上，且看起来完全正常");
    }

    @Test
    @DisplayName("距离条件映射到 distance-*，不与血量混用、也不按百分比换算")
    void distanceMapsToDistanceConditions() {
        // 距离是格数不是百分比：<5 必须是 5 格。
        // 误按百分数换算会得到 0.05 格，条件恒真，免疫范围悄悄缩到 1/20 格。
        assertEquals("distance <5", t("?targetDistance{<5}"));
        assertEquals("distance >8", t("?targetDistance{>8}"));
        assertEquals("distance <5", t("?targetDistance{distance=<5}"));
        assertEquals("distance <=5", t("?targetDistance{<=5}"));
        assertEquals("light-level >=8", t("?lightlevel{>=8}"));
        assertEquals("light-level <8", t("?lightlevel{<8}"));
        for (String invalid : List.of("?targetDistance{NaN}", "?targetDistance{5%}",
                "?lightlevel{bad}", "?targethealth{invalid}", "?targethealthpercent{NaN}")) {
            assertFalse(translated(invalid), invalid);
            assertEquals(invalid, t(invalid));
        }
    }

    @Test
    @DisplayName("Pins 条件映射保留 pin 名称、比较符和区域端点")
    void pinConditionsPreserveArguments() {
        assertEquals("distance-from-pin pin=\"arena.center\" distance=<=12",
                t("?DistanceFromPin{pin=arena.center;distance=<=12}"));
        assertEquals("distance-from-pin pin=\"arena.center\" distance=5to12",
                t("?DistanceFromPin{p=arena.center;d=5to12}"));
        assertEquals("origin-distance-from-pin pin=\"arena.center\" distance=>3",
                t("?OriginDistanceFromPin{p=arena.center;d=>3}"));
        assertEquals("in-pin-region pin1=\"arena.min\" pin2=\"arena.max\"",
                t("?InPinRegion{pin1=arena.min;pin2=arena.max}"));
        assertFalse(translated("?DistanceFromPin{pin=arena;distance=NaN}"));
        assertFalse(translated("?DistanceFromPin{pin=arena;distance=12to5}"));
        assertFalse(translated("?InPinRegion{pin1=arena.min}"));
    }

    @Test
    @DisplayName("无参条件映射到 helstera 的实际注册名")
    void parameterlessConditions() {
        assertEquals("has-target", t("?hasTarget"));
        assertEquals("has-target", t("?has-target"), "下划线/连字符写法都应归一");
        assertEquals("target-is-player", t("?targetIsPlayer"));
        assertEquals("is-caster", t("?isCaster"));
        assertEquals("target-is-mob", t("?targetIsMob"));
        assertEquals("target-is-living", t("?targetIsLiving"));
        assertTrue(translated("?hasTarget"));
        for (String condition : List.of("?isFrozen{unknown=true}", "?frozen{unknown=true}",
                "?projectileHasEnded{unknown=true}", "?snowing{unknown=true}",
                "?isInSurvivalMode{unknown=true}", "?inSurvivalMode{unknown=true}",
                "?isSnowing{unknown=true}", "?isUsingSpyglass{unknown=true}",
                "?isScoping{unknown=true}")) {
            assertFalse(translated(condition), condition);
            assertEquals(condition, t(condition));
        }
        assertEquals("is-frozen", t("?isFrozen{}"));
        assertEquals("projectile-has-ended", t("?projectileHasEnded{}"));
        for (String name : List.of("hasTarget", "night", "isRaining", "isThundering", "isLiving",
                "snowing", "isSnowing",
                "day", "daytime", "dawn", "dusk", "sunset", "morning", "noon", "midnight",
                "clear", "sun", "sunny", "rain", "storm", "thunder", "thunderstorm",
                "charged", "climbing", "isClimbing", "invisible", "isInvisible", "glowing", "isGlowing",
                "falling", "isFalling", "targetIsClimbing", "targetIsInvisible", "targetIsGlowing",
                "isUsingSpyglass", "isScoping",
                "targetIsFrozen", "targetFrozen",
                "isPlayer", "isMob", "isMonster", "isAnimal", "isVillager", "isTameable", "isRaider",
                "isBaby", "isChild", "isAdult", "isTamed", "isSheared", "isSaddled", "isPowered", "isCharged",
                "targetIsBaby", "targetIsChild", "targetIsAdult", "targetIsTamed", "targetIsSheared",
                "targetIsSaddled", "targetIsPowered", "targetIsCharged", "isSneaking", "crouching",
                "isSprinting", "sprinting", "isFlying", "flying", "isBlocking", "blocking", "isGliding",
                "gliding", "isSwimming", "swimming", "inWater", "inLava", "onFire", "isBurning",
                "burning", "inAir", "airborne", "targetIsSneaking", "targetIsSprinting", "targetIsFlying",
                "targetIsBlocking", "targetIsGliding", "targetIsSwimming", "targetInWater", "targetInLava",
                "targetOnFire", "targetBurning", "targetInAir", "targetAirborne", "targetIsPlayer",
                "targetIsLiving", "targetIsMob", "targetIsMonster", "targetIsAnimal", "targetIsVillager",
                "targetIsTameable", "targetIsRaider", "hasAI", "hasGravity", "isSilent", "isDead", "isAlive",
                "isValid", "targetHasAI", "targetHasGravity", "targetIsSilent", "targetIsDead", "targetIsAlive",
                "targetIsValid")) {
            String invalid = "?" + name + "{unknown=true}";
            assertFalse(translated(invalid), invalid);
            assertEquals(invalid, t(invalid));
            String negated = "?!" + name + "{unknown=true}";
            assertFalse(translated(negated), negated);
            assertEquals(negated, t(negated));
            assertTrue(translated("?" + name + "{}"), name);
        }
    }

    @Test
    @DisplayName("无参条件别名拒绝会被静默忽略的参数")
    void parameterlessConditionAliasesRejectUnknownArguments() {
        for (String name : List.of(
                "inCombat", "offGCD", "offGlobalCooldown", "isWaterMob", "waterMob", "isZombie", "zombie",
                "aerial", "isAerial", "isEnderman", "enderman", "isUndead", "isCreature", "child",
                "isOp", "isOperator", "isCollidable", "onGround", "isInvulnerable", "isCancelled",
                "isCanceled", "cancelled", "canceled", "mounted", "isMounted", "isRiding", "riding",
                "moist", "isMoist", "enderDragonAlive", "isDragonAlive", "dragonAlive",
                "isRaiderPatrolLeader", "isPatrolLeader", "serverIsPaper", "isPaper", "isMythicMob", "isMM",
                "isCreeperPrimed", "hasDialogOpen", "dialogOpen", "isInCinematic", "inCinematic",
                "itemRecharging", "recharging", "hasFreeInventorySlot", "hasFreeSlot", "vehicleIsDead",
                "isVehicle", "vehicle", "moving", "isMoving", "hasPassenger", "hasPassengers", "isLeashed",
                "leashed", "targetIsLeashed", "targetLeashed", "hasOwner", "ownerIsPlayer", "ownerIsOnline",
                "owner", "isOwner", "targetHasOwner", "targetOwnerIsPlayer", "targetOwnerIsOnline", "hasParent",
                "isParent", "parent", "isParentAlive", "parentAlive", "isSibling", "sibling", "targetIsOp",
                "targetIsOperator", "sameFaction", "differentFaction", "lineOfSight", "hasLineOfSight",
                "targetInLineOfSight", "targetNotInLineOfSight", "lineOfSightFromOrigin", "inLineOfSightFromOrigin",
                "inside", "outside", "targetIsUndead", "targetIsCreature", "targetIsCollidable", "targetIsBlocked",
                "targetIsVehicle", "targetHasPassenger", "targetHasPassengers", "targetIsDead", "targetIsAlive",
                "targetIsInvulnerable", "targetIsOnGround", "targetIsAirborne")) {
            String raw = "?" + name;
            String invalid = raw + "{unknown=true}";
            assertTrue(translated(raw), raw);
            assertTrue(translated(raw + "{}"), raw);
            assertFalse(translated(invalid), invalid);
            assertEquals(invalid, t(invalid));
            String negated = "?!" + name + "{unknown=true}";
            assertFalse(translated(negated), negated);
            assertEquals(negated, t(negated));
        }
    }

    @Test
    @DisplayName("bee and anger state aliases map only without parameters")
    void angryAndPollinatedConditions() {
        assertEquals("angry", t("?angry"));
        assertEquals("angry", t("?isAngry{}"));
        assertEquals("pollinated", t("?pollinated"));
        assertEquals("pollinated", t("?isPollinated{}"));
        assertEquals("pollinated", t("?hasNectar"));
        for (String raw : List.of("?angry{true}", "?isAngry{unknown=true}",
                "?pollinated{false}", "?hasNectar{unknown=true}")) {
            var mapped = MmConditions.translate(raw);
            assertFalse(mapped.translated(), raw);
            assertEquals(raw, mapped.condition(), raw);
        }
    }

    @Test
    @DisplayName("malformed parameter blocks remain untranslated and balanced nested values are accepted")
    void malformedParameterBlocksFailClosed() {
        for (String raw : List.of("?isFrozen{", "?isFrozen}", "?isFrozen}x{value}", "?isFrozen{value}}",
                "?isFrozen{{value}", "?world{world=arena;ignored=x}{}",
                "?world{world=arena}{world=other}", "?world{world=arena}junk{}")) {
            var result = MmConditions.translate(raw);
            assertFalse(result.translated(), raw);
            assertEquals(raw, result.condition(), raw);
        }
        assertTrue(translated("?stringEquals{value1=<caster.var{phase}>;value2=ready}"));
    }

    @Test
    void environmentConditionsRejectIgnoredOrDuplicateArguments() {
        assertEquals("world-is arena", t("?world{w=arena}"));
        assertEquals("weather-is rain", t("?weather{type=rain}"));
        assertEquals("time-is night", t("?time{t=night}"));
        for (String raw : List.of("?world{world=arena;world=other}",
                "?world{world=arena;unknown=true}", "?weather{unknown=rain}",
                "?time{value=day;value=night}", "?time{day;night}",
                "?world{world=arena other}")) {
            assertFalse(translated(raw), raw);
            assertEquals(raw, t(raw));
        }
    }

    @Test
    @DisplayName("常见环境、移动和药水条件映射到运行时条件")
    void environmentalAliasesMap() {
        assertEquals("night", t("?night"));
        assertEquals("raining", t("?isRaining"));
        assertEquals("!raining", t("?!raining"));
        assertEquals("thundering", t("?isThundering"));
        assertEquals("time-is day", t("?day"));
        assertEquals("time-is dawn", t("?dawn"));
        assertEquals("time-is dusk", t("?dusk"));
        assertEquals("weather-is clear", t("?sunny"));
        assertEquals("on-fire", t("?burning"));
        assertEquals("is-blocking", t("?blocking"));
        assertEquals("is-powered", t("?charged"));
        assertEquals("is-sneaking", t("?crouching"));
        assertEquals("random-chance 25", t("?chance{chance=25}"));
        assertFalse(translated("?chance{chance=NaN}"));
        assertFalse(translated("?chance{chance=Infinity}"));
        assertFalse(translated("?chance{chance=101}"));
        assertFalse(translated("?chance{chance=-1}"));
        assertEquals("falling", t("?falling"));
        assertEquals("drowning <=0", t("?drowning"));
        assertEquals("drowning >10", t("?drowning{>10}"));
        assertEquals("drowning <20", t("?drowning{air=<20}"));
        assertFalse(translated("?drowning{>abc}"));
        assertFalse(translated("?drowning{air=NaN}"));
        assertFalse(translated("?drowning{air=Infinity}"));
        assertFalse(translated("?hasAuras"));
        assertFalse(translated("?hasAuras{effect=SPEED}"));
        assertEquals("mounted", t("?isRiding"));
        assertEquals("is-vehicle", t("?isVehicle"));
        assertEquals("target-is-vehicle", t("?targetIsVehicle"));
        assertEquals("target-has-passenger", t("?targetHasPassenger"));
        assertTrue(translated("?targetHasPassenger"));
        assertEquals("target-within <5", t("?targetWithin{distance=<5}"));
        assertTrue(translated("?targetWithin{distance=<5}"));
        assertEquals("target-not-within 10", t("?targetNotWithin{d=10}"));
        assertEquals("target-has-line-of-sight", t("?targetInLineOfSight"));
        assertEquals("target-not-in-line-of-sight", t("?targetNotInLineOfSight"));
    }

    @Test
    @DisplayName("范围、随机和战斗条件映射到已有运行时 evaluator")
    void runtimeBackedUtilityConditionsMap() {
        assertEquals("in-combat", t("?inCombat"));
        assertEquals("players-in-radius 1 10", t("?playersInRadius{r=10}"));
        assertEquals("entities-in-radius 2 12", t("?entitiesInRadius{count=2;radius=12}"));
        assertEquals("mobs-in-radius 1 8", t("?mobsInRadius{radius=8}"));
        assertEquals("players-online >5", t("?playersOnline{amount=>5}"));
        assertEquals("players-in-world >=2", t("?playersInWorld{a=>=2}"));
        assertEquals("mobs-in-world <=20", t("?mobsInWorld{amount=<=20}"));
        assertEquals("player-within 10", t("?playerWithin{d=10}"));
        assertEquals("player-not-within 10", t("?playerNotWithin{distance=10}"));
        assertEquals("random-chance 25", t("?randomChance{chance=25}"));
        assertFalse(translated("?randomChance{chance=invalid}"));
        assertEquals("global-cooldown __mythic_gcd 1", t("?offgcd"));
        assertEquals("target-count-at-least 2", t("?targetCount{min=2}"));
        assertEquals("hunger-below 10", t("?hungerBelow{level=10}"));
        assertTrue(translated("?mobsInRadius{radius=8}"));
    }

    @Test
    @DisplayName("半径条件拒绝负数、非有限值和非法数量")
    void radiusConditionsFailClosed() {
        for (String condition : List.of(
                "?playersInRadius{r=-1}",
                "?entitiesInRadius{radius=NaN}",
                "?mobsInRadius{radius=Infinity}",
                "?livingInRadius{r=-0.5}",
                "?playerWithin{d=-2}")) {
            assertFalse(translated(condition), condition);
        }
        assertFalse(translated("?playersInRadius{r=8;amount=invalid}"));
        assertFalse(translated("?livingInRadius{r=8;a=NaN}"));
    }

    @Test
    void officialTargetsConditionMapsAmountAndTargeter() {
        assertEquals("targets >0", t("?targets"));
        assertEquals("targets 0to5", t("?targets{a=0to5}"));
        assertEquals("targets >=2 targeter=@targets",
                t("?targets{a=>=2;targeter=@targets}"));
        assertFalse(translated("?targets{a=invalid}"));
    }

    @Test
    void officialBoundsConditionsMapCoordinatesAndShapes() {
        assertEquals("cuboid loc1=0,64,0 loc2=10,80,10 relative=false",
                t("?cuboid{location1=0,64,0;location2=10,80,10}"));
        assertEquals("cuboid loc1=-2,0,-2 loc2=2,2,2 relative=true",
                t("?incuboid{a=-2,0,-2;b=2,2,2;r=true}"));
        assertEquals("in-bounds shape=sphere location=0,64,0 radius=5",
                t("?inBounds{shape=sphere;l=0,64,0;r=5}"));
        assertEquals("in-bounds shape=cylinder location=0,64,0 radius=3 height=10",
                t("?withinBounds{shape=cylinder;loc=0,64,0;r=3;h=10}"));
        assertFalse(translated("?inBounds{shape=sphere;loc=not-a-location;r=5}"));
    }

    @Test
    void stanceConditionMapsToDedicatedRuntimeState() {
        assertEquals("stance-is enraged", t("?stance{enraged}"));
        assertEquals("stance-is enraged", t("?stanceIs{stance=enraged}"));
        assertTrue(translated("?stance{enraged}"));
    }

    @Test
    void cancelledConditionMapsToEventAwareRuntimeCondition() {
        assertEquals("is-cancelled", t("?isCancelled"));
        assertEquals("is-cancelled", t("?is-canceled"));
        assertTrue(translated("?isCancelled"));
    }

    @Test
    @DisplayName("caster entity-state conditions map to runtime condition names")
    void casterStateConditionsMap() {
        assertEquals("is-blocked", t("?inBlock"));
        assertEquals("is-blocked 2", t("?inBlock{height=2}"));
        assertEquals("stand-on STONE", t("?standingOn{material=STONE}"));
        assertEquals("light-level <8", t("?lightLevel{<8}"));
        assertEquals("light-level >4", t("?lightLevel{>4}"));
        assertEquals("light-level <8", t("?lightLevel{level=<8}"));
        assertEquals("daylight 0", t("?daylight"));
        assertEquals("daylight 0.5", t("?daylight{value=0.5}"));
        assertEquals("facing-target", t("?facingTarget"));
        assertEquals("facing-target 30", t("?facingTarget{angle=30}"));
        assertEquals("is-living", t("?isLiving"));
        assertEquals("is-player", t("?isPlayer"));
        assertEquals("is-mob", t("?isMob"));
        assertEquals("is-monster", t("?isMonster"));
        assertEquals("is-animal", t("?isAnimal"));
        assertEquals("is-villager", t("?isVillager"));
        assertEquals("is-tameable", t("?isTameable"));
        assertEquals("is-raider", t("?isRaider"));
        assertEquals("is-undead", t("?isUndead"));
        assertEquals("is-creature", t("?isCreature"));
        assertEquals("is-baby", t("?isBaby"));
        assertEquals("is-child", t("?isChild"));
        assertEquals("is-child", t("?child"));
        assertEquals("is-adult", t("?isAdult"));
        assertEquals("is-tamed", t("?isTamed"));
        assertEquals("is-sheared", t("?isSheared"));
        assertEquals("is-saddled", t("?isSaddled"));
        assertEquals("is-powered", t("?isCharged"));
        assertEquals("is-op", t("?isOp"));
        assertEquals("has-ai", t("?hasAI"));
        assertEquals("has-gravity", t("?hasGravity"));
        assertEquals("is-silent", t("?isSilent"));
        assertEquals("is-dead", t("?isDead"));
        assertEquals("is-alive", t("?isAlive"));
        assertEquals("is-valid", t("?isValid"));
        assertEquals("is-collidable", t("?isCollidable"));
        assertEquals("has-offhand", t("?hasOffhand"));
        assertEquals("has-offhand SHIELD", t("?hasOffhand{item=SHIELD}"));
        assertEquals("on-ground", t("?onGround"));
        assertEquals("in-water", t("?inWater"));
        assertEquals("in-lava", t("?inLava"));
        assertEquals("on-fire", t("?onFire"));
        assertEquals("in-air", t("?inAir"));
        assertEquals("is-sneaking", t("?isSneaking"));
        assertEquals("is-sprinting", t("?isSprinting"));
        assertEquals("is-flying", t("?isFlying"));
        assertEquals("is-blocking", t("?isBlocking"));
        assertEquals("is-gliding", t("?isGliding"));
        assertEquals("is-swimming", t("?isSwimming"));
        assertEquals("is-climbing", t("?isClimbing"));
        assertEquals("is-invisible", t("?isInvisible"));
        assertEquals("is-glowing", t("?isGlowing"));
        assertEquals("is-invulnerable", t("?isInvulnerable"));
        assertTrue(translated("?onGround"));
        assertFalse(translated("?lightLevel{<50%}"));
    }

    @Test
    void foodLevelConditionMapsWithItsComparator() {
        assertEquals("food-level <14", t("?foodLevel{<14}"));
        assertEquals("food-level 14", t("?foodLevelIs{14}"));
        assertTrue(translated("?foodLevel{<14}"));
        assertFalse(translated("?foodLevel"));
    }

    @Test
    void foodSaturationConditionMapsWithItsComparator() {
        assertEquals("food-saturation <=1", t("?foodSaturation{a=<=1}"));
        assertEquals("food-saturation >=2", t("?hungerSaturation{s=>=2}"));
        assertEquals("target-food-saturation <=1", t("?targetFoodSaturation{value=<=1}"));
        assertFalse(translated("?foodSaturation"));
    }

    @Test
    void airLevelConditionMapsWithItsComparator() {
        assertEquals("air-level <120", t("?airLevel{<120}"));
        assertEquals("air-level 120", t("?remainingAir{120}"));
        assertTrue(translated("?airLevel{<120}"));
        assertFalse(translated("?airLevel"));
    }

    @Test
    void permissionGameModeAndPotionConditionsMapWithNamedArguments() {
        assertEquals("has-permission vip.node", t("?hasPermission{permission=vip.node}"));
        assertEquals("has-permission vip.node", t("?permission{perm=vip.node}"));
        assertEquals("has-permission vip.node", t("?hasPermission{p=vip.node}"));
        assertEquals("caster-has-permission admin.node", t("?casterHasPermission{node=admin.node}"));
        assertEquals("game-mode-is SURVIVAL", t("?gameMode{mode=SURVIVAL}"));
        assertEquals("game-mode-is SURVIVAL", t("?gameMode{gm=SURVIVAL}"));
        assertEquals("potion-effect-active SPEED", t("?potionEffectActive{effect=SPEED}"));
        assertEquals("potion-effect-active SPEED", t("?potionActive{e=SPEED}"));
        assertEquals("entity-type-is ZOMBIE", t("?entityType{type=ZOMBIE}"));
        assertEquals("entity-type-is SHEEP", t("?entityTypeIs{SHEEP}"));
        assertTrue(translated("?hasPermission{permission=vip.node}"));
        assertFalse(translated("?hasPermission"));
    }

    @Test
    void damageCauseConditionMapsEventCause() {
        assertEquals("damage-cause ENTITY_ATTACK", t("?damageCause{ENTITY_ATTACK}"));
        assertTrue(translated("?damageCause{ENTITY_ATTACK}"));
        assertFalse(translated("?damageCause"));
    }

    @Test
    void lastDamageCauseConditionMapsEntityState() {
        assertEquals("last-damage-cause ENTITY_ATTACK", t("?lastDamageCause"));
        assertEquals("last-damage-cause FIRE", t("?lastDamageCause{c=FIRE}"));
        assertTrue(translated("?lastDamageCause{cause=FIRE}"));
    }

    @Test
    void motionConditionsMapVelocityComparators() {
        assertEquals("motion-x <1", t("?motionX{v=<1}"));
        assertEquals("motion-y >=-0.5", t("?motionY{velocity=>=-0.5}"));
        assertEquals("motion-z =0", t("?motionZ{v==0}"));
        assertEquals("velocity <=3", t("?velocity{v=<=3}"));
        assertTrue(translated("?motionX{v=<1}"));
    }

    @Test
    void hasItemConditionMapsItemAndAmountArguments() {
        assertEquals("has-item DIAMOND >=2", t("?hasItem{item=DIAMOND;amount=>=2}"));
        assertTrue(translated("?hasItem{item=DIAMOND;amount=>=2}"));
        assertFalse(translated("?hasItem"));
    }

    @Test
    void freeInventorySlotConditionMapsWithoutArguments() {
        assertEquals("has-free-inventory-slot", t("?hasFreeInventorySlot"));
        assertEquals("has-free-inventory-slot", t("?hasFreeSlot"));
        assertTrue(translated("?hasFreeInventorySlot"));
    }

    @Test
    void nameConditionMapsNamedArguments() {
        assertEquals("name-is Emberling", t("?name{n=Emberling}"));
        assertEquals("name-is Emberling", t("?name{value=Emberling}"));
        assertEquals("name-is Emberling", t("?name Emberling"));
        assertTrue(translated("?name{n=Emberling}"));
        assertFalse(translated("?name"));
    }

    @Test
    void extendedOfficialPopulationAndDimensionConditionsMap() {
        assertEquals("living-in-radius >=2 5", t("?livingInRadius{a=>=2;r=5}"));
        assertEquals("living-in-radius >=1 5", t("?livingInRadius{radius=5}"));
        assertEquals("mobs-in-chunk >=2", t("?mobsInChunk{amount=>=2}"));
        assertEquals("dimension-is NETHER", t("?dimension{d=NETHER}"));
        assertTrue(translated("?mobsInChunk{a=>=1}"));
    }

    @Test
    void officialHeightOrientationAndOnBlockConditionsMap() {
        assertEquals("height <50", t("?height{h=<50}"));
        assertEquals("height-above 50", t("?heightAbove{h=50}"));
        assertEquals("height-below 50", t("?heightBelow{h=50}"));
        assertEquals("pitch -45to45", t("?pitch{p=-45to45}"));
        assertEquals("yaw 90to180 true", t("?yaw{y=90to180;c=true}"));
        assertEquals("on-block DIRT,STONE", t("?onBlock{m=DIRT,STONE}"));
        assertTrue(translated("?onBlock{m=#minecraft:logs}"));
    }

    @Test
    void officialMoistureConditionsMapFarmlandLevels() {
        assertEquals("moist", t("?moist"));
        assertEquals("moist", t("?isMoist"));
        assertEquals("moisture-level 3", t("?moisturelevel{l=3}"));
        assertEquals("moisture-level 1to6", t("?moistureness{l=1to6}"));
        assertEquals("moisture-level >=1", t("?moistness{l=>=1}"));
        assertTrue(translated("?moist"));
        assertTrue(translated("?moisturelevel{l=3}"));
        assertFalse(translated("?moisturelevel"));
        assertFalse(translated("?moisturelevel{l=not-a-level}"));
    }

    @Test
    void officialMobSizeConditionMapsRange() {
        assertEquals("mob-size >5", t("?mobsize{s=>5}"));
        assertEquals("mob-size 2to4", t("?size{s=2to4}"));
        assertTrue(translated("?size{s=4}"));
        assertFalse(translated("?size"));
    }

    @Test
    void officialDamageAmountConditionMapsDefaultAndRange() {
        assertEquals("damage-amount >0", t("?damageAmount"));
        assertEquals("damage-amount >10", t("?damageamount{amount=>10}"));
        assertEquals("damage-amount 1to5", t("?amount{a=1to5}"));
        assertTrue(translated("?damageAmount{a=>=2}"));
        assertFalse(translated("?damageAmount{a=invalid}"));
    }

    @Test
    void officialDamageTagConditionMapsAliasesAndBooleanValue() {
        assertEquals("damage-tag tag=FIRE", t("?damageTag{tag=FIRE}"));
        assertEquals("damage-tag tag=FIRE value=false", t("?damageHasTag{t=FIRE;value=false}"));
        assertTrue(translated("?damageTag{tag=FIRE}"));
        assertFalse(translated("?damageTag{tag=FIRE;value=maybe}"));
        assertFalse(translated("?damageTag{value=true}"));
    }

    @Test
    void officialOwnerConditionMapsAliases() {
        assertEquals("is-owner", t("?owner"));
        assertEquals("is-owner", t("?isOwner"));
        assertTrue(translated("?owner"));
    }

    @Test
    void officialBlockTypeInRadiusConditionMapsNamedArguments() {
        assertEquals("block-type-in-radius STONE >10 3",
                t("?blockTypeInRadius{type=STONE;amount=>10;radius=3}"));
        assertEquals("block-type-in-radius DIRT >0 8", t("?blockTypeInRadius"));
        assertTrue(translated("?blockTypeInRadius{t=STONE;a=>1;r=2}"));
        assertTrue(translated("?blockTypeInRadius{type=#minecraft:logs}"));
        assertEquals("block-type-in-radius STONE,DIRT >0 8",
                t("?blockTypeInRadius{STONE,DIRT}"));
        assertEquals("block-type-in-radius MINECRAFT:FURNACE[LIT=TRUE,FACING=NORTH] >0 8",
                t("?blockTypeInRadius{types=minecraft:furnace[lit=true,facing=north]}"));
    }

    @Test
    void blockMaterialConditionsRejectUnknownDuplicateAndMisplacedArguments() {
        for (String condition : List.of(
                "?blockType{unexpected=STONE}",
                "?blockType{type=STONE;material=DIRT}",
                "?blockType{types=}",
                "?blockType{types==STONE}",
                "?blockType{types=FURNACE[lit=true}",
                "?blockType{types=STONE;radius=3}",
                "?onBlock{unexpected=STONE}",
                "?onBlock{material=STONE;types=DIRT}",
                "?blockTypeInRadius{unexpected=STONE}",
                "?blockTypeInRadius{type=STONE;radius=3;unknown=true}",
                "?blockTypeInRadius{type=STONE;types=DIRT}",
                "?blockTypeInRadius{radius=2;r=3}",
                "?blockTypeInRadius{amount=1;a=2}",
                "?blockTypeInRadius{type=STONE;radius=}",
                "?blockTypeInRadius{type=STONE;;radius=3}")) {
            assertFalse(translated(condition), condition);
        }
        assertTrue(translated("?blockType{type=minecraft:furnace[lit=true]}"));
        assertTrue(translated("?blockTypeInRadius{STONE,DIRT;radius=3;amount=>1}"));
    }

    @Test
    void officialChildrenConditionMapsDefaultAndRanges() {
        assertEquals("children 1", t("?children"));
        assertEquals("children >2", t("?children{a=>2}"));
        assertEquals("children 1to3", t("?children{amount=1to3}"));
        assertTrue(translated("?children{a=>=1}"));
        assertFalse(translated("?children{a=invalid}"));
    }

    @Test
    void officialInsideOutsideConditionsMap() {
        assertEquals("inside", t("?inside"));
        assertEquals("outside", t("?outside"));
        assertTrue(translated("?inside"));
        assertTrue(translated("?outside"));
    }

    @Test
    void officialFallSpeedConditionMapsRange() {
        assertEquals("fall-speed >5", t("?fallSpeed{s=>5}"));
        assertEquals("fall-speed 0to1", t("?fallingspeed{s=0to1}"));
        assertTrue(translated("?fallSpeed{speed=>=1}"));
        assertFalse(translated("?fallSpeed"));
    }

    @Test
    void officialEnchantingConditionsMapDefaultsAndAliases() {
        assertEquals("enchanting-level 0", t("?enchantingLevel"));
        assertEquals("enchanting-level >=10", t("?enchantingLevel{l=>=10}"));
        assertEquals("enchanting-experience 0", t("?enchantingExperience"));
        assertEquals("enchanting-experience >0.2", t("?enchantExp{a=>0.2}"));
        assertTrue(translated("?enchantingExp{level=0to1}"));
        assertFalse(translated("?enchantingExperience{l=invalid}"));
    }

    @Test
    void officialPluginConditionMapsDefaultAndAliases() {
        assertEquals("plugin MythicMobs", t("?plugin"));
        assertEquals("plugin DemoPlugin", t("?plugin{p=DemoPlugin}"));
        assertEquals("plugin DemoPlugin", t("?pluginexists{plugin=DemoPlugin}"));
        assertTrue(translated("?hasPlugin{pl=DemoPlugin}"));
    }

    @Test
    void officialDirectionalVelocityConditionPreservesNamedArguments() {
        assertEquals("directional-velocity x=>0 y=<0 z=>0 relative=true",
                t("?directionalVelocity{x=>0;y=<0;z=>0;relative=true}"));
        assertEquals("directional-velocity y=<0 absy=true",
                t("?dvelocity{y=<0;ay=true}"));
        assertTrue(translated("?directionalVelocity{x=>0}"));
        assertFalse(translated("?directionalVelocity"));
    }

    @Test
    void officialFieldOfViewConditionMapsDefaultsAndRotation() {
        assertEquals("field-of-view 90 0", t("?fieldOfView"));
        assertEquals("field-of-view 90 15", t("?fov{a=90;r=15}"));
        assertTrue(translated("?infieldofview{angle=45}"));
        assertFalse(translated("?fieldOfView{angle=invalid}"));
    }

    @Test
    void officialDistanceFromLocationConditionMapsNamedCoordinates() {
        assertEquals("distance-from-location x=0 y=0 z=0 distance=<10",
                t("?distanceFromLocation{x=0;y=0;z=0;distance=<10}"));
        assertEquals("distance-from-location x=1 y=2 z=3 world=arena distance=0to5",
                t("?distanceFromLocation{x=1;y=2;z=3;w=arena;d=0to5}"));
        assertTrue(translated("?distanceFromLocation{x=0;y=0;z=0;d=>0}"));
        assertFalse(translated("?distanceFromLocation{d=invalid}"));
        for (String invalid : List.of(
                "?distanceFromLocation{x=0;y=0;z=0;d=5;ignored=true}",
                "?distanceFromLocation{x=0;x=1;d=5}",
                "?distanceFromLocation{distance=5;d=6}",
                "?distanceFromLocation{world=arena;w=other;d=5}",
                "?distanceFromLocation{x=0;y=0;z=0;d=5;}")) {
            assertFalse(translated(invalid), invalid);
            assertEquals(invalid, t(invalid));
        }
    }

    @Test
    void officialCoordinateDifferenceConditionsMapRanges() {
        assertEquals("x-diff >5", t("?xDiff{diff=>5}"));
        assertEquals("y-diff -2to2", t("?yDiff{d=-2to2}"));
        assertEquals("z-diff 1", t("?zDiff{difference=1}"));
        assertTrue(translated("?zDiff{d=0to5}"));
        assertFalse(translated("?xDiff{d=invalid}"));
    }

    @Test
    void officialOriginLocationConditionMapsLocationForms() {
        assertEquals("origin-location loc=10,20,30 exact=false",
                t("?originLocation{loc=10,20,30}"));
        assertEquals("origin-location loc=10,20,30 exact=true",
                t("?originLocation{location=10,20,30;e=YES}"));
        assertEquals("origin-location loc=10,20,30 exact=false",
                t("?originLocation{c=10,20,30;exact=off}"));
        assertEquals("origin-location x=1 y=2 z=3 exact=true",
                t("?originLocation{x=1;y=2;z=3;e=true}"));
        assertEquals("origin-location x=1 y=2 z=3 exact=true",
                t("?originLocation{x=1;y=2;z=3;e=1}"));
        assertTrue(translated("?originLocation{loc=0,0,0}"));
        assertFalse(translated("?originLocation{loc=0,0}"));
        assertFalse(translated("?originLocation{loc=NaN,0,0}"));
        assertFalse(translated("?originLocation{loc=0,Infinity,0}"));
        assertFalse(translated("?originLocation{x=0;y=-Infinity;z=0}"));
        assertFalse(translated("?originLocation{loc=0,0,0;exact=maybe}"));
        for (String invalid : List.of(
                "?originLocation{loc=0,0,0;ignored=true}",
                "?originLocation{loc=0,0,0;location=1,2,3}",
                "?originLocation{loc=0,0,0;x=1}",
                "?originLocation{x=1;x=2;y=3}",
                "?originLocation{exact=true;e=false}",
                "?originLocation{unexpected}")) {
            assertFalse(translated(invalid), invalid);
            assertEquals(invalid, t(invalid));
        }
    }

    @Test
    void officialSpawnReasonConditionMapsReasonAliasesAndRejectsInvalidValues() {
        assertEquals("spawn-reason RAID", t("?spawnReason{reason=RAID}"));
        assertEquals("spawn-reason NATURAL", t("?spawnReason{r=natural}"));
        assertEquals("spawn-reason CUSTOM", t("?spawnReason CUSTOM"));
        assertTrue(translated("?spawnReason{reason=raid}"));
        assertFalse(translated("?spawnReason"));
        assertFalse(translated("?spawnReason{reason=not-a-reason}"));
    }

    @Test
    void officialWaterMobAndZombieConditionsMapTypeAliases() {
        assertEquals("is-water-mob", t("?isWaterMob"));
        assertEquals("is-water-mob", t("?waterMob"));
        assertEquals("is-zombie", t("?isZombie"));
        assertEquals("is-zombie", t("?zombie"));
        assertTrue(translated("?isWaterMob"));
        assertTrue(translated("?isZombie"));
    }

    @Test
    void officialAerialConditionMapsAliases() {
        assertEquals("aerial", t("?aerial"));
        assertEquals("aerial", t("?isAerial"));
        assertTrue(translated("?aerial"));
        assertTrue(translated("?isAerial"));
    }

    @Test
    void officialEndermanConditionMapsAliases() {
        assertEquals("is-enderman", t("?isEnderman"));
        assertEquals("is-enderman", t("?enderman"));
        assertTrue(translated("?isEnderman"));
    }

    @Test
    void officialHasEnchantmentConditionMapsDefaultsAndRanges() {
        assertEquals("has-enchantment SHARPNESS >2",
                t("?hasEnchantment{e=SHARPNESS;l=>2}"));
        assertEquals("has-enchantment ANY >0", t("?hasEnchant"));
        assertEquals("has-enchantment ANY 3", t("?hasEnchant{e=ANY;l=3}"));
        assertEquals("has-enchantment ANY >=3", t("?hasEnchantment{level=>=3}"));
        assertTrue(translated("?hasEnchantment{type=SHARPNESS;l=1to3}"));
        assertFalse(translated("?hasEnchantment{e=SHARPNESS;l=invalid}"));
    }

    @Test
    void officialHasDialogOpenConditionMapsAliases() {
        assertEquals("has-dialog-open", t("?hasDialogOpen"));
        assertEquals("has-dialog-open", t("?dialogOpen"));
        assertTrue(translated("?hasDialogOpen"));
    }

    @Test
    void officialPlayerKillsConditionMapsDefaultsAndRanges() {
        assertEquals("player-kills 0", t("?playerKills"));
        assertEquals("player-kills >=2", t("?playerKills{k=>=2}"));
        assertEquals("player-kills 1to3", t("?playerKills{k=1to3}"));
        assertFalse(translated("?playerKills{k=invalid}"));
    }

    @Test
    void officialAbsorptionConditionMapsDefaultsAndRanges() {
        assertEquals("absorption >=0", t("?absorption"));
        assertEquals("absorption >=4", t("?absorption{amount=>=4}"));
        assertEquals("absorption =2", t("?absorptionamount{a=2}"));
        assertFalse(translated("?absorption{amount=invalid}"));
    }

    @Test
    void officialLevelConditionMapsRangesAndAliases() {
        assertEquals("level >=10", t("?level{l=>=10}"));
        assertEquals("level 1to10", t("?mobLevel{level=1to10}"));
        assertFalse(translated("?level{l=invalid}"));
    }

    @Test
    void officialIsFrozenConditionMapsAliases() {
        assertEquals("is-frozen", t("?isFrozen"));
        assertEquals("is-frozen", t("?frozen"));
    }

    @Test
    void officialMythicMobTypeConditionMapsExactFlag() {
        assertEquals("mythic-mob-type CoolZombie exactmatch=true",
                t("?mythicMobType{t=CoolZombie}"));
        assertEquals("mythic-mob-type Zombie exactmatch=false",
                t("?mmType{types=Zombie;em=false}"));
        assertEquals("mythic-mob-type Zombie exactmatch=true",
                t("?mythicMobType{type=Zombie;exactmatch=yes}"));
    }

    @Test
    void structuredValueConditionsRejectUnknownDuplicateEmptyAndInvalidArguments() {
        for (String raw : List.of(
                "?lunarPhase{phases=1;unknown=2}",
                "?lunarPhase{phase=1;phases=2}",
                "?lunarPhase{phase=}",
                "?enderDragonPhase{phase=CIRCLING;unknown=FLY_TO_PORTAL}",
                "?enderDragonPhase{phase=CIRCLING;p=HOVER}",
                "?enderDragonPhase{phase=}",
                "?mythicMobType{type=Zombie;unknown=true}",
                "?mythicMobType{type=Zombie;t=Husk}",
                "?mythicMobType{types=Zombie,}",
                "?mythicMobType{type=Zombie;exactmatch=maybe}",
                "?triggerItemType{types=STONE;unknown=true}",
                "?triggerItemType{type=STONE;mat=DIRT}",
                "?triggerItemType{types=}",
                "?itemType{types=STONE;unknown=true}",
                "?itemType{types=STONE;strict=maybe}",
                "?itemType{types=STONE;strict=true}",
                "?itemType{types=STONE;vanillaonly=true}",
                "?itemType{types=STONE;strict=false;exact=true}",
                "?entityItemType{material=STONE;unknown=true}",
                "?entityItemType{type=STONE;m=DIRT}",
                "?entityItemType{material=}",
                "?entityItemType",
                "?entityMaterialType",
                "?triggerBlockType{block=STONE;unknown=true}",
                "?triggerBlockType{type=STONE;b=DIRT}",
                "?triggerBlockType{types=STONE,}",
                "?structure{structure=minecraft:village;unknown=true}",
                "?structure{structure=village;s=stronghold}",
                "?structure{structure=}")) {
            assertUntranslatedPreserved(raw);
        }

        assertEquals("trigger-item-type STONE,DIRT",
                t("?itemType{types=STONE,DIRT;strict=false;vanilla=false}"));
        assertEquals("mythic-mob-type Zombie,Husk exactmatch=false",
                t("?mythicMobType{t=Zombie,Husk;em=false}"));
        assertEquals("lunar-phase 1,2", t("?lunarPhase{phase=1,2}"));
        assertEquals("ender-dragon-phase CIRCLING,HOVER",
                t("?enderDragonPhase{phase=CIRCLING,HOVER}"));
    }

    @Test
    void officialLunarPhaseConditionMapsNumericLists() {
        assertEquals("lunar-phase 0,2,4,6",
                t("?lunarPhase{p=0,2,4,6}"));
        assertEquals("lunar-phase 0", t("?lunarPhase"));
        assertFalse(translated("?lunarPhase{p=8}"));
        assertFalse(translated("?lunarPhase{p=1,invalid}"));
        assertFalse(translated("?lunarPhase{p=1,}"));
        assertFalse(translated("?lunarPhase{p=}"));
    }

    @Test
    void officialLastSignalConditionMapsDefaultAndNamedSignals() {
        assertEquals("last-signal fireCannonShot",
                t("?lastSignal{s=fireCannonShot}"));
        assertEquals("last-signal DEFAULT", t("?lastSignal"));
        assertEquals("last-signal phase2", t("?lastSignal{signal=phase2}"));
        for (String raw : List.of("?lastSignal{s=}", "?lastSignal{unknown=phase2}",
                "?lastSignal{s=phase2;unknown=x}", "?lastSignal{s=phase two}")) {
            var result = MmConditions.translate(raw);
            assertFalse(result.translated(), raw);
            assertEquals(raw, result.condition(), raw);
        }
    }

    @Test
    void officialProjectileHasEndedConditionMaps() {
        assertEquals("projectile-has-ended", t("?projectileHasEnded"));
    }

    @Test
    void officialLineOfSightFromOriginConditionMapsAliases() {
        assertEquals("line-of-sight-from-origin", t("?lineOfSightFromOrigin"));
        assertEquals("line-of-sight-from-origin", t("?inLineOfSightFromOrigin"));
    }

    @Test
    void officialStructureConditionMapsDefaultAndNamespacedValues() {
        assertEquals("structure minecraft:desert_pyramid",
                t("?structure{structure=minecraft:desert_pyramid}"));
        assertEquals("structure minecraft:village", t("?structure{minecraft:village}"));
        assertEquals("structure village", t("?structure"));
    }

    @Test
    void officialBowTensionConditionMapsForceRange() {
        assertEquals("bow-tension >0.5", t("?bowTension{value=>0.5}"));
        assertEquals("bow-tension >0", t("?bowShootTension"));
        assertFalse(translated("?bowTension{force=>1.1}"));
    }

    @Test
    void officialStringEqualsConditionMapsNamedValues() {
        assertEquals("string-equals yes yes",
                t("?stringEquals{val1=yes;val2=yes}"));
        assertTrue(translated("?stringEq{v1=foo;v2=bar}"));
        assertFalse(translated("?stringEquals{val1=only}"));
    }

    @Test
    void officialCompareValuesConditionMapsOperatorsAndValues() {
        assertEquals("compare-values value1=2 value2=10 operator=<",
                t("?compareValues{value1=2;value2=10;operator=<}"));
        assertTrue(translated("?compareValue{v1=foo;v2=bar;op=GREATER_THAN}"));
        assertFalse(translated("?compareValues{value1=1;value2=2;operator=BOGUS}"));
    }

    @Test
    void officialVariableContainsConditionMapsCompareType() {
        assertEquals("variable-contains variable=skill.examplelist value=hello comparetype=STARTS_WITH",
                t("?variableContains{var=skill.examplelist;val=hello;compareType=STARTS_WITH}"));
    }

    @Test
    void officialStringEmptyConditionsMapAliases() {
        assertEquals("string-empty", t("?stringEmpty{value=}"));
        assertEquals("string-not-empty value", t("?notEmpty{v=value}"));
    }

    @Test
    void officialIsSkillConditionMapsAliases() {
        assertEquals("is-skill ExampleMetaSkill", t("?isSkill{skill=ExampleMetaSkill}"));
        assertEquals("is-skill ExampleMetaSkill", t("?skillExists{n=ExampleMetaSkill}"));
    }

    @Test
    void officialGlobalScoreConditionMapsObjectiveAndRange() {
        assertEquals("global-score objective=KillCount value=5",
                t("?globalScore{o=KillCount;value=5}"));
        assertFalse(translated("?globalScore{o=KillCount;value=invalid}"));
    }

    @Test
    void officialScoreConditionMapsObjectiveEntryAndRange() {
        assertEquals("score objective=Kills entry=Alex value=>=5",
                t("?score{o=Kills;e=Alex;v=>=5}"));
        assertFalse(translated("?score{o=Kills;e=Alex;v=invalid}"));
    }

    @Test
    void officialLookingAtConditionMapsTypeAndDistance() {
        assertEquals("looking-at STONE 5", t("?lookingAt{b=STONE}"));
        assertEquals("looking-at COW 8", t("?lookingAt{e=COW;d=8}"));
        assertFalse(translated("?lookingAt{b=STONE;d=0}"));
    }

    @Test
    void officialEntityItemConditionsMapMaterialAliases() {
        assertEquals("entity-item-type STONE", t("?entityItemType{m=STONE}"));
        assertEquals("entity-material-type STONE,DIRT",
                t("?entityMaterialType{mat=STONE,DIRT}"));
        assertUntranslatedPreserved("?entityItemType");
    }

    @Test
    void officialEntityItemIsSimilarConditionMapsItemAliases() {
        assertEquals("entity-item-is-similar MyCustomItem",
                t("?entityItemIsSimilar{i=MyCustomItem}"));
        assertFalse(translated("?entityItemIsSimilar"));
    }

    @Test
    void officialItemIsSimilarConditionKeepsPlayerSlotSemantics() {
        assertEquals("item-is-similar MyCustomItem 0",
                t("?itemIsSimilar{i=MyCustomItem;slot=0}"));
        assertEquals("item-is-similar DIRT HAND", t("?itemIsSimilar"));
        assertEquals("item-is-similar MyCustomItem OFF_HAND",
                t("?similarTo{i=MyCustomItem;s=OFF_HAND}"));
        assertFalse(translated("?itemIsSimilar{i=MyCustomItem;slot=99}"));
    }

    @Test
    void officialItemRechargingConditionMapsAliases() {
        assertEquals("item-recharging", t("?itemRecharging"));
        assertEquals("item-recharging", t("?recharging"));
        assertTrue(translated("?itemRecharging"));
    }

    @Test
    void officialItemTypeConditionMapsToTriggerItemType() {
        assertEquals("trigger-item-type STONE,DIRT",
                t("?itemType{types=STONE,DIRT}"));
        assertEquals("trigger-item-type DIRT", t("?itemType"));
    }

    @Test
    void officialHasPotionEffectConditionMapsTypeLevelAndDuration() {
        assertEquals("has-potion-effect SPEED 0-2 0-9999",
                t("?hasPotionEffect{t=SPEED;l=0-2;d=0-9999}"));
        assertEquals("has-potion-effect ANY", t("?hasPotionEffect"));
        assertFalse(translated("?hasPotionEffect{t=SPEED;l=invalid}"));
    }

    @Test
    void officialAuraConditionsRemainUnmappedWithoutAuraDataProvider() {
        for (String raw : List.of(
                "?hasAura{aura=SPEED}",
                "?hasBuff{buff=FURY}",
                "?hasDebuff{debuff=FURY}",
                "?hasAuraStacks{n=SPEED;s=>3}",
                "?hasAuraType{type=HARMFUL}",
                "?hasBuffType{group=HELPFUL}",
                "?hasDebuffType{auratype=HARMFUL}",
                "?hasBuffStacks{n=FURY;s=>2}",
                "?hasDebuffStacks{n=FURY;s=1}",
                "?auraStacks{aura=FURY;stacks=2}",
                "?buffStacks{aura=FURY;stacks=2}",
                "?debuffStacks{aura=FURY;stacks=2}")) {
            assertFalse(translated(raw), raw);
            assertEquals(raw, t(raw), raw);
        }
    }

    @Test
    void officialIsInCinematicConditionMapsAliases() {
        assertEquals("is-in-cinematic", t("?isInCinematic"));
        assertEquals("is-in-cinematic", t("?inCinematic"));
        assertTrue(translated("?isInCinematic"));
    }

    @Test
    void officialColorConditionMapsDefaultsAndAliases() {
        assertEquals("color RED", t("?color{c=RED}"));
        assertEquals("color WHITE", t("?color"));
        assertEquals("color TABBY", t("?clr{color=tabby}"));
        assertTrue(translated("?color{c=BLUE}"));
    }

    @Test
    void officialMaterialCooldownConditionMapsDefaultsAndAliases() {
        assertEquals("material-on-cooldown ENDER_PEARL", t("?materialIsOnCooldown"));
        assertEquals("material-on-cooldown STONE", t("?materialCooldown{m=STONE}"));
        assertTrue(translated("?matCooldown{material=DIAMOND}"));
    }

    @Test
    void officialTriggerItemTypeConditionMapsDefaultsAndAliases() {
        assertEquals("trigger-item-type STONE,DIRT",
                t("?triggerItemType{mat=STONE,DIRT}"));
        assertEquals("trigger-item-type DIRT", t("?triggeringItemType"));
        assertTrue(translated("?triggerItemType{item=DIAMOND}"));
    }

    @Test
    void officialTriggerBlockTypeConditionMapsDefaultsAndAliases() {
        assertEquals("trigger-block-type STONE,DIRT",
                t("?triggerBlockType{mat=STONE,DIRT}"));
        assertEquals("trigger-block-type DIRT", t("?triggeringBlockType"));
        assertTrue(translated("?triggerBlockType{block=STONE}"));
    }

    @Test
    void officialWorldTimeAndLightSourcesMap() {
        assertEquals("world-time 0to6000", t("?worldTime{t=0to6000}"));
        assertEquals("sky-light-level >=10", t("?skyLightLevel{l=>=10}"));
        assertEquals("light-level-from-blocks 1to10", t("?lightLevelFromBlocks{l=1to10}"));
        assertEquals("light-level-from-blocks >=5", t("?blockLightLevel{l=>=5}"));
        assertTrue(translated("?worldTime{t=0to6000}"));
    }

    @Test
    void officialBlockAndBiomeTypeConditionsMapExactForms() {
        assertEquals("block-type-is DIRT,STONE", t("?blockType{type=DIRT,STONE}"));
        assertEquals("biome-type-is jungle,ocean true", t("?biomeType{t=jungle,ocean}"));
        assertEquals("biome-type-is forest,desert false", t("?biomeType{t=forest,desert;e=false}"));
        assertEquals("biome-type-is ocean false", t("?biomeCategory{e=false}"));
        assertTrue(translated("?blockType{m=STONE}"));
        assertEquals("block-type-is #MINECRAFT:LOGS", t("?blockType{m=#minecraft:logs}"));
        assertEquals("block-type-is OAK_*", t("?blockType{m=OAK_*}"));
        assertEquals("on-block #MINECRAFT:LOGS", t("?onBlock{m=#minecraft:logs}"));
        assertEquals("block-type-in-radius #MINECRAFT:LOGS >0 8",
                t("?blockTypeInRadius{m=#minecraft:logs}"));
        assertEquals("block-type-in-radius ST* >0 8", t("?blockTypeInRadius{m=ST*}"));
        assertTrue(translated("?blockType{m=#minecraft:logs}"));
        assertTrue(translated("?blockType{m=OAK_*}"));
        assertTrue(translated("?onBlock{m=#minecraft:logs}"));
        assertTrue(translated("?blockTypeInRadius{m=ST*}"));
        assertTrue(translated("?biomeType{t=forest;e=false}"));
        assertFalse(translated("?biomeType{t=forestish;e=false}"));
        assertFalse(translated("?biomeType{t=none;e=false}"));
        assertFalse(translated("?biomeType{t=forest;e=maybe}"));
    }

    @Test
    void vaultCurrencyConditionMapsAmountAndRejectsMalformedValues() {
        assertEquals("has-currency 1000", t("?hasCurrency{amount=1000}"));
        assertEquals("has-currency >250", t("?hasCurrency{a=>250}"));
        assertFalse(translated("?hasCurrency{amount=lots}"));
        assertFalse(translated("?hasCurrency"));
    }

    @Test
    void officialServerAndSurvivalConditionsMap() {
        assertEquals("server-is-paper", t("?serverIsPaper"));
        assertEquals("server-is-paper", t("?isPaper"));
        assertEquals("is-in-survival-mode", t("?isInSurvivalMode"));
        assertEquals("is-in-survival-mode", t("?inSurvivalMode"));
        assertTrue(translated("?serverIsPaper"));
    }

    @Test
    void officialParameterlessTimeAndWeatherAliasesMapToRuntimeConditions() {
        assertEquals("time-is morning", t("?morning"));
        assertEquals("time-is noon", t("?noon"));
        assertEquals("time-is midnight", t("?midnight"));
        assertEquals("weather-is clear", t("?clear"));
        assertEquals("weather-is clear", t("?sun"));
        assertEquals("weather-is rain", t("?rain"));
        assertEquals("weather-is thunder", t("?storm"));
        assertEquals("weather-is thunder", t("?thunder"));
        assertEquals("weather-is thunder", t("?thunderstorm"));
        assertEquals("snowing", t("?snowing"));
        assertEquals("snowing", t("?isSnowing"));
    }

    @Test
    void officialSpyglassConditionMaps() {
        assertEquals("is-using-spyglass", t("?isUsingSpyglass"));
        assertEquals("is-using-spyglass", t("?isScoping"));
        assertTrue(translated("?isUsingSpyglass"));
    }

    @Test
    void officialMythicMobConditionMaps() {
        assertEquals("is-mythic-mob", t("?isMythicMob"));
        assertEquals("is-mythic-mob", t("?isMM"));
        assertTrue(translated("?isMythicMob"));
    }

    @Test
    void officialServerVersionConditionsMap() {
        assertEquals("server-version 1.21.1", t("?serverVersion{v=1.21.1}"));
        assertEquals("server-version-after-or-equal 1.21.1 true",
                t("?serverAfter{version=1.21.1}"));
        assertEquals("server-version-before 1.22 true",
                t("?serverBefore{v=1.22}"));
        assertTrue(translated("?serverVersion{v=1.21.1}"));
    }

    @Test
    void officialRaiderPatrolLeaderConditionMaps() {
        assertEquals("is-raider-patrol-leader", t("?isRaiderPatrolLeader"));
        assertEquals("is-raider-patrol-leader", t("?isPatrolLeader"));
        assertTrue(translated("?isRaiderPatrolLeader"));
    }

    @Test
    void officialBoundingBoxesOverlapMapsOffset() {
        assertEquals("bounding-boxes-overlap", t("?boundingBoxesOverlap"));
        assertEquals("bounding-boxes-overlap 1.5", t("?boundingBoxesOverlap{fo=1.5}"));
        assertEquals("bounding-boxes-overlap 2", t("?bbsoverlap{forward=2}"));
        assertTrue(translated("?boundingBoxesOverlap"));
    }

    @Test
    void officialCreeperPrimedConditionMaps() {
        assertEquals("is-creeper-primed", t("?isCreeperPrimed"));
        assertTrue(translated("?isCreeperPrimed"));
    }

    @Test
    void officialMobsNearOriginMapsAmountRadiusAndTypes() {
        assertEquals("mobs-near-origin >5 10 ExampleMob,SuperMob",
                t("?mobsNearOrigin{type=ExampleMob,SuperMob;r=10;amount=>5}"));
        assertEquals("mobs-near-origin >=1 5", t("?mobsNearOrigin{r=5}"));
        assertTrue(translated("?mobsNearOrigin{r=5}"));
    }

    @Test
    void officialDistanceFromSpawnMapsRange() {
        assertEquals("distance-from-spawn <100", t("?distanceFromSpawn{d=<100}"));
        assertEquals("distance-from-spawn 0to100", t("?distanceFromSpawn{d=0to100}"));
        assertTrue(translated("?distanceFromSpawn{d=>50}"));
    }

    @Test
    void officialAltitudeMapsSurfaceRangeAndMaximum() {
        assertEquals("altitude 3-5 30", t("?altitude{h=3-5}"));
        assertEquals("altitude >=0 20", t("?heightFromSurface{a=>=0;mH=20}"));
        assertTrue(translated("?altitude{h=0to5}"));
    }

    @Test
    void officialVehicleIsDeadMaps() {
        assertEquals("vehicle-is-dead", t("?vehicleIsDead"));
        assertTrue(translated("?vehicleIsDead"));
    }

    @Test
    void officialEnderDragonAliveConditionMaps() {
        assertEquals("ender-dragon-alive", t("?enderDragonAlive"));
        assertEquals("ender-dragon-alive", t("?isDragonAlive"));
        assertTrue(translated("?enderDragonAlive"));
    }

    @Test
    void officialEnderDragonPhaseConditionMaps() {
        assertEquals("ender-dragon-phase CIRCLING", t("?enderDragonPhase{phase=CIRCLING}"));
        assertEquals("ender-dragon-phase FLY_TO_PORTAL,LEAVE_PORTAL",
                t("?enderDragonPhase{phases=FLY_TO_PORTAL,LEAVE_PORTAL}"));
        assertEquals("ender-dragon-phase CIRCLING,LEAVE_PORTAL",
                t("?edragonPhase{p=circling, leave_portal}"));
        assertTrue(translated("?enderDragonPhase{p=CIRCLING}"));
        assertFalse(translated("?enderDragonPhase{p=CIRCLING,INVALID}"));
        assertFalse(translated("?enderDragonPhase{p=CIRCLING,}"));
        assertFalse(translated("?enderDragonPhase{p=}"));
    }

    @Test
    void vehicleAndMotionConditionsMapToRuntimeConditions() {
        assertEquals("mounted", t("?mounted"));
        assertEquals("mounted", t("?isMounted"));
        assertEquals("moving", t("?isMoving"));
        assertEquals("has-passenger", t("?hasPassenger"));
        assertEquals("has-passenger", t("?hasPassengers"));
        assertEquals("is-leashed", t("?isLeashed"));
        assertEquals("is-leashed", t("?leashed"));
        assertEquals("target-is-leashed", t("?targetIsLeashed"));
        assertEquals("target-is-leashed", t("?targetLeashed"));
        assertEquals("has-owner", t("?hasOwner"));
        assertEquals("has-parent", t("?hasParent"));
        assertEquals("is-parent", t("?isParent"));
        assertEquals("is-parent", t("?parent"));
        assertEquals("is-child", t("?isChild"));
        assertEquals("is-child", t("?child"));
        assertEquals("is-parent-alive", t("?isParentAlive"));
        assertEquals("is-sibling", t("?isSibling"));
        assertTrue(translated("?hasPassenger"));
    }

    @Test
    void scoreboardTagConditionMapsItsTagAttribute() {
        assertEquals("has-tag tag=BossDefeated", t("?hasTag{t=BossDefeated}"));
        assertEquals("has-tag tag=BossDefeated", t("?hasScoreboardTag{tag=BossDefeated}"));
        assertEquals("target-has-tag tag=BossDefeated", t("?targetHasTag{t=BossDefeated}"));
        assertEquals("target-has-tag tag=BossDefeated", t("?targetHasScoreboardTag{tag=BossDefeated}"));
        assertTrue(translated("?hasTag{t=BossDefeated}"));
        assertFalse(translated("?hasTag"));
    }

    @Test
    @DisplayName("target entity state conditions map to runtime aliases")
    void targetStateConditionsMap() {
        assertEquals("target-is-undead", t("?targetIsUndead"));
        assertEquals("target-is-creature", t("?targetIsCreature"));
        assertEquals("target-is-monster", t("?targetIsMonster"));
        assertEquals("target-is-animal", t("?targetIsAnimal"));
        assertEquals("target-is-villager", t("?targetIsVillager"));
        assertEquals("target-is-tameable", t("?targetIsTameable"));
        assertEquals("target-is-raider", t("?targetIsRaider"));
        assertEquals("target-is-baby", t("?targetIsChild"));
        assertEquals("target-is-adult", t("?targetIsAdult"));
        assertEquals("target-is-tamed", t("?targetIsTamed"));
        assertEquals("target-is-sheared", t("?targetIsSheared"));
        assertEquals("target-is-saddled", t("?targetIsSaddled"));
        assertEquals("target-is-powered", t("?targetIsCharged"));
        assertEquals("target-is player", t("?targetType{type=PLAYER}"));
        assertEquals("target-is-op", t("?targetIsOp"));
        assertEquals("target-is monster", t("?targetType{type=MONSTER}"));
        assertEquals("target-is minecraft", t("?targetType{type=living}"));
        assertEquals("has-line-of-sight", t("?hasLineOfSight"));
        assertEquals("target-has-line-of-sight", t("?targetInLineOfSight"));
        assertEquals("target-invulnerable", t("?targetIsInvulnerable"));
        assertEquals("target-has-ai", t("?targetHasAI"));
        assertEquals("target-has-gravity", t("?targetHasGravity"));
        assertEquals("target-is-silent", t("?targetIsSilent"));
        assertEquals("target-is-valid", t("?targetIsValid"));
        assertEquals("target-is-collidable", t("?targetIsCollidable"));
        assertEquals("target-has-offhand DIAMOND", t("?targetHasOffhand{item=DIAMOND}"));
        assertEquals("target-is-sneaking", t("?targetIsSneaking"));
        assertEquals("target-is-sprinting", t("?targetIsSprinting"));
        assertEquals("target-is-flying", t("?targetIsFlying"));
        assertEquals("target-is-blocking", t("?targetIsBlocking"));
        assertEquals("target-is-gliding", t("?targetIsGliding"));
        assertEquals("target-is-swimming", t("?targetIsSwimming"));
        assertEquals("target-on-fire", t("?targetOnFire"));
        assertEquals("target-in-air", t("?targetAirborne"));
        assertEquals("target-in-water", t("?targetInWater"));
        assertEquals("target-in-lava", t("?targetInLava"));
        assertEquals("target-is-glowing", t("?targetIsGlowing"));
        assertEquals("target-is-invisible", t("?targetIsInvisible"));
        assertEquals("target-is-climbing", t("?targetIsClimbing"));
        assertEquals("target-is-frozen", t("?targetIsFrozen"));
        assertEquals("target-is-frozen", t("?targetFrozen"));
        assertEquals("target-is-blocked", t("?targetIsBlocked"));
        assertEquals("target-dead", t("?targetIsDead"));
        assertEquals("target-is-alive", t("?targetIsAlive"));
        assertEquals("target-invulnerable", t("?targetIsInvulnerable"));
        assertEquals("target-on-ground", t("?targetIsOnGround"));
        assertEquals("target-airborne", t("?targetIsAirborne"));
        assertTrue(translated("?targetIsUndead"));
        assertTrue(translated("?targetIsBlocking"));
        assertFalse(translated("?targetType{type=unknown}"));
    }

    @Test
    @DisplayName("target permission, inventory and health conditions map to target-aware runtime evaluators")
    void targetContextConditionsMap() {
        assertEquals("target-has-permission example.node", t("?targetHasPermission{permission=example.node}"));
        assertEquals("target-game-mode-is SURVIVAL", t("?targetGameMode{mode=SURVIVAL}"));
        assertEquals("target-food-level <=14", t("?targetFoodLevel{value=<=14}"));
        assertEquals("target-air-level <=100", t("?targetAirLevel{value=<=100}"));
        assertEquals("target-has-item SHIELD >=1", t("?targetHasItem{item=SHIELD;amount=>=1}"));
        assertEquals("target-potion-effect-active SPEED", t("?targetPotionEffectActive{effect=SPEED}"));
        assertEquals("target-potion-effect-active SPEED", t("?targetHasPotionEffect{effect=SPEED}"));
        assertFalse(translated("?targetHasAuras{effect=SPEED}"));
        assertFalse(translated("?targetHasAuras"));
        assertEquals("holding DIAMOND", t("?holding{item=DIAMOND}"));
        assertEquals("wearing IRON_HELMET", t("?wearing{item=IRON_HELMET}"));
        assertEquals("target-holding DIAMOND", t("?targetHolding{item=DIAMOND}"));
        assertEquals("target-wearing IRON_HELMET", t("?targetWearing{item=IRON_HELMET}"));
        assertEquals("caster-faction-is guards", t("?faction{faction=guards}"));
        assertEquals("target-faction-is guards", t("?targetFaction{faction=guards}"));
        assertEquals("same-faction", t("?sameFaction"));
        assertEquals("different-faction", t("?differentFaction"));
        assertEquals("owner-is-player", t("?ownerIsPlayer"));
        assertEquals("owner-is-online", t("?ownerIsOnline"));
        assertEquals("target-has-owner", t("?targetHasOwner"));
        assertEquals("target-owner-is-player", t("?targetOwnerIsPlayer"));
        assertEquals("target-owner-is-online", t("?targetOwnerIsOnline"));
        assertEquals("target-health <=15", t("?targetHealth{h=<=15}"));
        assertEquals("target-health-percent >=50", t("?targetHealthPercent{p=>=50%}"));
        assertTrue(translated("?targetHasItem{item=SHIELD;amount=>=1}"));
    }

    @Test
    void commonWorldAndHealthConditionsMap() {
        assertEquals("world-is arena", t("?world{world=arena}"));
        assertEquals("world-is arena", t("?world-is arena"));
        assertEquals("biome-is plains true", t("?biome{value=plains}"));
        assertEquals("biome-is minecraft:plains,false false", t("?biome{b=minecraft:plains,false;e=false}"));
        assertEquals("biome-is minecraft:plains true", t("?biome"));
        assertEquals("biome-is minecraft:plains,desert false", t("?biome{e=false;b=minecraft:plains, desert}"));
        assertEquals("biome-is minecraft:plains false", t("?biome{exact=false}"));
        for (String invalid : List.of("?biome{b=plains,}", "?biome{b=,plains}",
                "?biome{b=}", "?biome{e=}", "?biome{b=plains desert}", "?biome{b=plains;unknown=1}",
                "?biome{b=plains;biome=desert}")) {
            assertFalse(translated(invalid), invalid);
            assertEquals(invalid, t(invalid));
        }
        assertEquals("weather-is rain", t("?weather{type=rain}"));
        assertEquals("time-is night", t("?time{value=night}"));
        assertEquals("health-percent >=50", t("?healthPercent{p=>=50%}"));
        assertEquals("health <50", t("?health <50"));
        assertEquals("has-permission loot.vip", t("?permission loot.vip"));
        assertTrue(translated("?world{world=arena}"));
    }

    @Test
    @DisplayName("比较号带 = 时按同向处理，不改变方向")
    void nonStrictComparisons() {
        assertEquals("health <=50", t("?health{<=50}"));
        assertEquals("health >=50", t("?health{>=50}"));
    }

    @Test
    @DisplayName("等值与不等值比较器保留到通用运行时条件")
    void equalityComparisonsAreMapped() {
        assertEquals("health =50", t("?health{==50}"));
        assertEquals("health !=50", t("?health{!=50}"));
        assertEquals("distance =5", t("?targetDistance{distance==5}"));
        assertEquals("target-health =10", t("?targetHealth{h==10}"));
        assertEquals("target-health-percent !=50", t("?targetHealthPercent{p!=50%}"));
        assertEquals("light-level =8", t("?lightLevel{level==8}"));
        assertTrue(translated("?health{!=50}"));
    }

    @Test
    @DisplayName("无等价物的条件原样保留并标记未映射，绝不丢弃")
    void unknownKeptButFlagged() {
        var m = MmConditions.translate("?unsupportedCondition");
        assertFalse(m.translated(), "未知条件不能假装翻译成功");
        assertEquals("?unsupportedCondition", m.condition(), "必须原样保留，用户才能看出少了什么");
    }

    @Test
    @DisplayName("空与 null 安全处理")
    void nullAndBlankSafe() {
        assertEquals(null, MmConditions.translate(null).condition());
        assertEquals(null, MmConditions.translate("   ").condition());
        assertFalse(MmConditions.translate(null).translated());
    }

    @Test
    @DisplayName("无法解析的数值按未映射处理，不抛异常")
    void unparseableValueIsNotFatal() {
        assertFalse(translated("?health{<abc}"), "解析失败应退化为未映射，而非抛异常中断整个导入");
        assertFalse(translated("?health{}"), "空参数块不是有效条件");
        assertFalse(translated("?health"), "没有比较块时无法确定语义");
    }

    @Test
    @DisplayName("批量翻译保持顺序，并单独列出未映射项")
    void translateAllSeparatesUnmapped() {
        List<String> out = new ArrayList<>();
        List<String> unmapped = new ArrayList<>();
        MmConditions.translateAll(
                List.of("?hasTarget", "?unsupportedCondition", "?health{<50}"), out, unmapped);
        assertEquals(List.of("has-target", "?unsupportedCondition", "health <50"), out,
                "顺序必须保持，否则条件语义（AND）虽不变但报告对不上原文件");
        assertEquals(List.of("?unsupportedCondition"), unmapped, "未映射项需单独收集，供报告告警");
    }

    @Test
    @DisplayName("批量翻译容忍 null 列表")
    void translateAllToleratesNull() {
        List<String> out = new ArrayList<>();
        List<String> unmapped = new ArrayList<>();
        MmConditions.translateAll(null, out, unmapped);
        assertTrue(out.isEmpty());
    }
}
