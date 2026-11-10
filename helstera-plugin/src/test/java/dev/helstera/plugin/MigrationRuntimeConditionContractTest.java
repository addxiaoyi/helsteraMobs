package dev.helstera.plugin;

import dev.helstera.ai.BehaviorRegistryImpl;
import dev.helstera.ai.loot.DropTable;
import dev.helstera.ai.skill.AuraRegistry;
import dev.helstera.ai.skill.SkillExtras;
import dev.helstera.ai.skill.SkillService;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

/** Cross-module contract for translated conditions and mechanics used by migration tests. */
class MigrationRuntimeConditionContractTest {

    private static <T extends org.bukkit.Keyed> T keyedVariant(Class<T> type, String name) {
        var key = new org.bukkit.NamespacedKey("minecraft", name.toLowerCase(java.util.Locale.ROOT));
        return type.cast(java.lang.reflect.Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getKey" -> key;
                    case "name", "toString" -> name.toUpperCase(java.util.Locale.ROOT);
                    case "ordinal", "compareTo" -> 0;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == arguments[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                }));
    }

    @Test
    @DisplayName("imported LookingAt selectors reach the matching Player ray trace")
    void importedLookingAtSelectorsReachRuntime() {
        var player = mock(org.bukkit.entity.Player.class);
        var blockHit = mock(org.bukkit.util.RayTraceResult.class);
        var block = mock(org.bukkit.block.Block.class);
        var entityHit = mock(org.bukkit.util.RayTraceResult.class);
        var entity = mock(org.bukkit.entity.Entity.class);
        when(player.rayTraceBlocks(6.0, org.bukkit.FluidCollisionMode.NEVER)).thenReturn(blockHit);
        when(player.rayTraceEntities(8, true)).thenReturn(entityHit);
        when(blockHit.getHitBlock()).thenReturn(block);
        when(block.getType()).thenReturn(org.bukkit.Material.STONE);
        when(entityHit.getHitEntity()).thenReturn(entity);
        when(entity.getType()).thenReturn(org.bukkit.entity.EntityType.FIREWORK_ROCKET);
        var context = dev.helstera.api.behavior.BehaviorContext.of(
                null, player, 1.0, -1, 0, "IDLE");
        var nonPlayerContext = dev.helstera.api.behavior.BehaviorContext.of(
                null, mock(org.bukkit.entity.Zombie.class), 1.0, -1, 0, "IDLE");
        var registry = new BehaviorRegistryImpl();
        var service = new SkillService(registry, null);

        var blockPrimary = dev.helstera.migration.importer.MmConditions.translate(
                "?lookingAt{b=STONE;d=6}");
        var blockAlias = dev.helstera.migration.importer.MmConditions.translate(
                "?lookingAt{block=STONE;distance=6}");
        assertTrue(blockPrimary.translated());
        assertEquals("looking-at block STONE 6", blockPrimary.condition());
        assertTrue(blockAlias.translated());
        String blockPrimaryKey = service.bindCondition(blockPrimary.condition());
        String blockAliasKey = service.bindCondition(blockAlias.condition());
        assertNotNull(blockPrimaryKey);
        assertNotNull(blockAliasKey);
        assertTrue(registry.testCondition(blockPrimaryKey, context));
        assertTrue(registry.testCondition(blockAliasKey, context));

        var entitySelector = dev.helstera.migration.importer.MmConditions.translate(
                "?lookingAt{e=FIREWORK_ROCKET;d=8}");
        assertTrue(entitySelector.translated());
        assertEquals("looking-at entity FIREWORK_ROCKET 8", entitySelector.condition());
        String entityKey = service.bindCondition(entitySelector.condition());
        assertNotNull(entityKey);
        assertTrue(registry.testCondition(entityKey, context),
                "an entity selector whose name is also a material must use the entity ray trace");

        when(block.getType()).thenReturn(org.bukkit.Material.DIRT);
        assertFalse(registry.testCondition(blockPrimaryKey, context));
        when(entity.getType()).thenReturn(org.bukkit.entity.EntityType.COW);
        assertFalse(registry.testCondition(entityKey, context));
        assertFalse(registry.testCondition(blockPrimaryKey, nonPlayerContext));
        assertFalse(dev.helstera.migration.importer.MmConditions.translate(
                "?lookingAt{b=STONE;d=0}").translated());
    }

    @Test
    @DisplayName("imported SkillOnCooldown reads the resolved target instance cooldown")
    void importedSkillOnCooldownReadsTargetInstance() {
        var registry = new BehaviorRegistryImpl();
        var service = new SkillService(registry, null);
        var yaml = YamlConfiguration.loadConfiguration(new java.io.StringReader("""
                skills:
                  pulse:
                    cooldown: 30s
                    on-decision: []
                """));
        service.loadSkills(yaml.getConfigurationSection("skills"));

        var target = mock(org.bukkit.entity.Zombie.class);
        var source = mock(org.bukkit.entity.Zombie.class);
        var unbound = mock(org.bukkit.entity.Zombie.class);
        UUID targetId = UUID.randomUUID();
        UUID sourceId = UUID.randomUUID();
        UUID unboundId = UUID.randomUUID();
        when(target.getUniqueId()).thenReturn(targetId);
        when(source.getUniqueId()).thenReturn(sourceId);
        when(unbound.getUniqueId()).thenReturn(unboundId);
        for (var entity : List.of(target, source, unbound)) {
            when(entity.isValid()).thenReturn(true);
            when(entity.isDead()).thenReturn(false);
        }

        var targetInstance = mock(dev.helstera.api.instance.ModelInstance.class);
        var sourceInstance = mock(dev.helstera.api.instance.ModelInstance.class);
        when(targetInstance.isValid()).thenReturn(true);
        when(targetInstance.instanceId()).thenReturn(901);
        when(targetInstance.baseEntity()).thenReturn(java.util.Optional.of(target));
        when(sourceInstance.isValid()).thenReturn(true);
        when(sourceInstance.instanceId()).thenReturn(902);
        when(sourceInstance.baseEntity()).thenReturn(java.util.Optional.of(source));
        service.instanceLookup(entity -> entity.getUniqueId().equals(targetId) ? targetInstance
                : entity.getUniqueId().equals(sourceId) ? sourceInstance : null);

        var translated = dev.helstera.migration.importer.MmConditions.translate(
                "?skillOnCooldown{s=pulse}");
        assertTrue(translated.translated());
        assertEquals("skill-on-cooldown pulse", translated.condition());
        String condition = service.bindCondition(translated.condition());
        assertNotNull(condition, service.warnings().toString());
        var targetContext = dev.helstera.api.behavior.BehaviorContext.of(
                sourceInstance, target, 1.0, -1, 0, "IDLE");
        assertFalse(registry.testCondition(condition, targetContext));

        var ownerContext = dev.helstera.api.behavior.BehaviorContext.of(
                targetInstance, (org.bukkit.entity.LivingEntity) null, 1.0, -1, 0, "IDLE");
        assertTrue(service.castSkill("pulse", ownerContext));
        assertTrue(registry.testCondition(condition, targetContext));

        var unboundContext = dev.helstera.api.behavior.BehaviorContext.of(
                sourceInstance, unbound, 1.0, -1, 0, "IDLE");
        assertFalse(registry.testCondition(condition, unboundContext));
        assertTrue(service.setSkillCooldown("pulse", ownerContext, 0));
        assertFalse(registry.testCondition(condition, targetContext));
        assertNull(service.bindCondition("SkillOnCooldown{unknown=pulse}"));
    }

    @Test
    @DisplayName("imported targetHasAuras checks only the explicit target's named Aura")
    void importedTargetHasAurasReachRuntime() {
        var instance = mock(dev.helstera.api.instance.ModelInstance.class);
        var caster = mock(org.bukkit.entity.LivingEntity.class);
        var target = mock(org.bukkit.entity.LivingEntity.class);
        UUID casterId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        when(instance.isValid()).thenReturn(true);
        when(instance.baseEntity()).thenReturn(java.util.Optional.of(caster));
        when(caster.getUniqueId()).thenReturn(casterId);
        when(target.getUniqueId()).thenReturn(targetId);
        when(target.isValid()).thenReturn(true);
        when(target.isDead()).thenReturn(false);

        var auraRegistry = new AuraRegistry(() -> 10L, ignored -> true);
        auraRegistry.apply(casterId, null, "Burning Fury", "HELPFUL", 100, 1,
                true, false, false, false, false);
        auraRegistry.apply(targetId, null, "Burning Fury", "HELPFUL", 100, 1,
                true, false, false, false, false);
        SkillExtras.auraRegistry(auraRegistry);
        try {
            var imported = dev.helstera.migration.importer.MmConditions.translate(
                    "?targetHasAuras{aura=\"Burning Fury\"}");
            assertTrue(imported.translated());
            assertEquals("mythic-target-has-aura aura=\"Burning Fury\"", imported.condition());

            var conditions = new BehaviorRegistryImpl();
            var service = new SkillService(conditions, null);
            String key = service.bindCondition(imported.condition());
            assertNotNull(key);
            var targetContext = dev.helstera.api.behavior.BehaviorContext.of(
                    instance, target, 1.0, -1, 0, "IDLE");
            assertTrue(conditions.testCondition(key, targetContext));

            auraRegistry.remove(targetId, "Burning Fury", Integer.MAX_VALUE);
            assertFalse(conditions.testCondition(key, targetContext),
                    "a caster Aura must not satisfy a target-only check");
            var noTargetContext = dev.helstera.api.behavior.BehaviorContext.of(
                    instance, (org.bukkit.entity.LivingEntity) null, 1.0, -1, 0, "IDLE");
            assertFalse(conditions.testCondition(key, noTargetContext));
            assertFalse(dev.helstera.migration.importer.MmConditions.translate(
                    "?targetHasAuras{aura=Burning Fury;unknown=true}").translated());
        } finally {
            SkillExtras.auraRegistry(null);
        }
    }

    @Test
    @DisplayName("imported survival aliases bind to the target-player survival condition")
    void importedSurvivalAliasesReachRuntime() {
        var player = mock(org.bukkit.entity.Player.class);
        when(player.getGameMode()).thenReturn(org.bukkit.GameMode.SURVIVAL);
        var context = dev.helstera.api.behavior.BehaviorContext.of(
                null, player, 1.0, -1, 0, "IDLE");
        var nonPlayerContext = dev.helstera.api.behavior.BehaviorContext.of(
                null, mock(org.bukkit.entity.Zombie.class), 1.0, -1, 0, "IDLE");
        var registry = new BehaviorRegistryImpl();
        var service = new SkillService(registry, null);

        for (String source : List.of("?isInSurvivalMode", "?inSurvivalMode")) {
            var mapped = dev.helstera.migration.importer.MmConditions.translate(source);
            assertTrue(mapped.translated(), source);
            assertEquals("is-in-survival-mode", mapped.condition());
            String key = service.bindCondition(mapped.condition());
            assertNotNull(key);
            assertTrue(registry.testCondition(key, context), source);
            assertFalse(registry.testCondition(key, nonPlayerContext), source);
        }

        for (String source : List.of("?isInSurvivalMode{unknown=true}", "?inSurvivalMode{unknown=true}")) {
            assertFalse(dev.helstera.migration.importer.MmConditions.translate(source).translated(), source);
        }
    }

    @Test
    @DisplayName("imported execution-audience aliases and per-target inline filters reach runtime")
    void importedExecutionAudienceConditionsReachRuntime() {
        var member = mock(org.bukkit.entity.Player.class);
        var outsider = mock(org.bukkit.entity.Player.class);
        UUID memberId = UUID.randomUUID();
        UUID outsiderId = UUID.randomUUID();
        when(member.getUniqueId()).thenReturn(memberId);
        when(outsider.getUniqueId()).thenReturn(outsiderId);
        var registry = new BehaviorRegistryImpl();
        var service = new SkillService(registry, null);

        for (String source : List.of("?isInAudience", "?inaudience", "?audiencecontains")) {
            var mapped = dev.helstera.migration.importer.MmConditions.translate(source);
            assertTrue(mapped.translated(), source);
            assertEquals("is-in-audience", mapped.condition());
            String key = service.bindCondition(mapped.condition());
            assertNotNull(key);
            assertTrue(registry.testCondition(key,
                    dev.helstera.api.behavior.BehaviorContext.of(null, outsider, 1.0, -1, 0, "IDLE")),
                    "without an attached audience any Player passes");
            assertTrue(registry.testCondition(key,
                    dev.helstera.api.behavior.BehaviorContext.of(null, member, 1.0, -1, 0, "IDLE")
                            .withExecutionAudience(List.of(member))));
            assertFalse(registry.testCondition(key,
                    dev.helstera.api.behavior.BehaviorContext.of(null, member, 1.0, -1, 0, "IDLE")
                            .withExecutionAudience(List.of())));
            assertFalse(registry.testCondition(key,
                    dev.helstera.api.behavior.BehaviorContext.of(null,
                            mock(org.bukkit.entity.Zombie.class), 1.0, -1, 0, "IDLE")));
        }

        List<org.bukkit.entity.LivingEntity> selected = new ArrayList<>();
        registry.registerAction("capture-targets", context -> selected.addAll(context.targets()));
        String action = service.bindAction("capture-targets @targets ?@isInAudience");
        assertNotNull(action);
        var unrestricted = dev.helstera.api.behavior.BehaviorContext.execution(
                null, member, 1.0, -1, 0, "IDLE", null, null, List.of(member, outsider), null);
        assertTrue(registry.runAction(action, unrestricted));
        assertEquals(List.of(member, outsider), selected,
                "without an attached audience every Player remains eligible");

        selected.clear();
        var context = dev.helstera.api.behavior.BehaviorContext.execution(
                null, member, 1.0, -1, 0, "IDLE", null, null, List.of(member, outsider), null)
                .withExecutionAudience(List.of(member));
        assertTrue(registry.runAction(action, context));
        assertEquals(List.of(member), selected);

        String implicitTargetAction = service.bindAction("capture-targets ?@isInAudience");
        assertNotNull(implicitTargetAction);
        selected.clear();
        assertTrue(registry.runAction(implicitTargetAction, context));
        assertEquals(List.of(member), selected,
                "per-target conditions must also wrap registered actions without an explicit targeter");

        for (String source : List.of("?isInAudience{unknown=true}", "?inaudience{true}",
                "?audiencecontains{player=x}")) {
            assertFalse(dev.helstera.migration.importer.MmConditions.translate(source).translated(), source);
        }
    }

    @Test
    @DisplayName("imported spyglass aliases bind to the active-item runtime condition")
    void importedSpyglassAliasesReachRuntime() {
        var player = mock(org.bukkit.entity.Player.class);
        var activeItem = mock(org.bukkit.inventory.ItemStack.class);
        when(player.hasActiveItem()).thenReturn(true);
        when(player.getActiveItem()).thenReturn(activeItem);
        when(activeItem.getType()).thenReturn(org.bukkit.Material.SPYGLASS);
        var context = dev.helstera.api.behavior.BehaviorContext.of(
                null, player, 1.0, -1, 0, "IDLE");
        var nonPlayerContext = dev.helstera.api.behavior.BehaviorContext.of(
                null, mock(org.bukkit.entity.Zombie.class), 1.0, -1, 0, "IDLE");
        var registry = new BehaviorRegistryImpl();
        var service = new SkillService(registry, null);

        for (String source : List.of("?isUsingSpyglass", "?isScoping")) {
            var mapped = dev.helstera.migration.importer.MmConditions.translate(source);
            assertTrue(mapped.translated(), source);
            assertEquals("is-using-spyglass", mapped.condition());
            String key = service.bindCondition(mapped.condition());
            assertNotNull(key);
            assertTrue(registry.testCondition(key, context), source);
            assertFalse(registry.testCondition(key, nonPlayerContext), source);
        }

        var invalid = dev.helstera.migration.importer.MmConditions.translate(
                "?isUsingSpyglass{unknown=true}");
        assertFalse(invalid.translated());
    }

    @Test
    @DisplayName("imported EntityType variants bind and match the Bukkit target")
    void importedEntityTypeVariantsReachRuntime() {
        var cat = mock(org.bukkit.entity.Cat.class);
        when(cat.getType()).thenReturn(org.bukkit.entity.EntityType.CAT);
        var tabbyType = keyedVariant(org.bukkit.entity.Cat.Type.class, "tabby");
        var blackType = keyedVariant(org.bukkit.entity.Cat.Type.class, "black");
        when(cat.getCatType()).thenReturn(tabbyType);
        var catContext = dev.helstera.api.behavior.BehaviorContext.of(
                null, cat, 1.0, -1, 0, "IDLE");
        var wolf = mock(org.bukkit.entity.Wolf.class);
        when(wolf.getType()).thenReturn(org.bukkit.entity.EntityType.WOLF);
        when(wolf.getVariant()).thenReturn(keyedVariant(org.bukkit.entity.Wolf.Variant.class, "snowy"));
        var wolfContext = dev.helstera.api.behavior.BehaviorContext.of(
                null, wolf, 1.0, -1, 0, "IDLE");
        var registry = new BehaviorRegistryImpl();
        var service = new SkillService(registry, null);

        var tabby = dev.helstera.migration.importer.MmConditions.translate(
                "?entityType{type=CAT{variant=TABBY}}");
        assertTrue(tabby.translated());
        assertEquals("entity-type-is CAT[variant=TABBY]", tabby.condition());
        String tabbyKey = service.bindCondition(tabby.condition());
        assertNotNull(tabbyKey);
        assertTrue(registry.testCondition(tabbyKey, catContext));
        when(cat.getCatType()).thenReturn(blackType);
        assertFalse(registry.testCondition(tabbyKey, catContext));

        var alias = dev.helstera.migration.importer.MmConditions.translate(
                "?mobType{type=CAT{variant=TABBY},WOLF{variant=SNOWY}}");
        assertTrue(alias.translated());
        String aliasKey = service.bindCondition(alias.condition());
        assertNotNull(aliasKey);
        assertTrue(registry.testCondition(aliasKey, wolfContext));
        assertFalse(registry.testCondition(aliasKey, dev.helstera.api.behavior.BehaviorContext.of(
                null, mock(org.bukkit.entity.Zombie.class), 1.0, -1, 0, "IDLE")));

        var horse = mock(org.bukkit.entity.Horse.class);
        when(horse.getType()).thenReturn(org.bukkit.entity.EntityType.HORSE);
        when(horse.getColor()).thenReturn(org.bukkit.entity.Horse.Color.WHITE);
        when(horse.getStyle()).thenReturn(org.bukkit.entity.Horse.Style.WHITE_DOTS);
        var horseContext = dev.helstera.api.behavior.BehaviorContext.of(
                null, horse, 1.0, -1, 0, "IDLE");
        var horseTraits = dev.helstera.migration.importer.MmConditions.translate(
                "?entityType{type=HORSE{c=white;s=white dots}}");
        assertTrue(horseTraits.translated());
        assertEquals("entity-type-is HORSE[color=WHITE;style=WHITE_DOTS]", horseTraits.condition());
        String horseTraitsKey = service.bindCondition(horseTraits.condition());
        assertNotNull(horseTraitsKey);
        assertTrue(registry.testCondition(horseTraitsKey, horseContext));
        when(horse.getStyle()).thenReturn(org.bukkit.entity.Horse.Style.BLACK_DOTS);
        assertFalse(registry.testCondition(horseTraitsKey, horseContext));

        var mismatchedTrait = dev.helstera.migration.importer.MmConditions.translate(
                "?entityType{type=ZOMBIE{variant=TABBY}}");
        assertTrue(mismatchedTrait.translated());
        String mismatchedTraitKey = service.bindCondition(mismatchedTrait.condition());
        var zombie = mock(org.bukkit.entity.Zombie.class);
        when(zombie.getType()).thenReturn(org.bukkit.entity.EntityType.ZOMBIE);
        assertFalse(registry.testCondition(mismatchedTraitKey,
                dev.helstera.api.behavior.BehaviorContext.of(null, zombie, 1.0, -1, 0, "IDLE")));

        for (String source : List.of("?entityType{type=CAT{variant=UNKNOWN}}",
                "?entityType{type=COW{variant=WARM}}",
                "?entityType{CAT;type=WOLF}")) {
            var preserved = dev.helstera.migration.importer.MmConditions.translate(source);
            assertFalse(preserved.translated(), source);
            assertEquals(source, preserved.condition());
        }
    }

    @Test
    @DisplayName("official variable condition aliases reach their scoped runtime consumers")
    void importedVariableConditionsReachRuntime() {
        var instance = mock(dev.helstera.api.instance.ModelInstance.class);
        var caster = mock(org.bukkit.entity.LivingEntity.class);
        var target = mock(org.bukkit.entity.Player.class);
        var world = mock(org.bukkit.World.class);
        when(instance.isValid()).thenReturn(true);
        when(instance.instanceId()).thenReturn(7301);
        when(instance.baseEntity()).thenReturn(java.util.Optional.of(caster));
        when(caster.getUniqueId()).thenReturn(UUID.randomUUID());
        when(target.getUniqueId()).thenReturn(UUID.randomUUID());
        when(world.getUID()).thenReturn(UUID.randomUUID());
        var context = dev.helstera.api.behavior.BehaviorContext.execution(
                instance, target, 1.0, -1, 0, "IDLE", null, target, List.of(target),
                new org.bukkit.Location(world, 0, 64, 0));
        var registry = new BehaviorRegistryImpl();
        var service = new SkillService(registry, null);
        String suffix = UUID.randomUUID().toString().replace("-", "");

        for (String scope : List.of("skill", "caster", "target", "world", "global")) {
            String variable = "parity" + suffix;
            String value = scope.equals("skill") ? "42" : "ready";
            String set = service.bindAction("setvariable{var=" + scope + "." + variable
                    + ";value=" + value + (scope.equals("skill") ? "}" : ";type=STRING}"));
            assertNotNull(set, scope);
            assertTrue(registry.runAction(set, context), scope);

            String source = "?VariableEquals{var=" + scope + "." + variable + ";value=" + value + "}";
            var mapped = dev.helstera.migration.importer.MmConditions.translate(source);
            assertTrue(mapped.translated(), source);
            String condition = service.bindCondition(mapped.condition());
            assertNotNull(condition, mapped.condition());
            assertTrue(registry.testCondition(condition, context), scope);

            var isSet = dev.helstera.migration.importer.MmConditions.translate(
                    "?varIsSet{key=" + scope + "." + variable + "}");
            assertTrue(isSet.translated(), scope);
            assertTrue(registry.testCondition(service.bindCondition(isSet.condition()), context), scope);
            if (scope.equals("global")) {
                var wrongType = dev.helstera.migration.importer.MmConditions.translate(
                        "?VariableInRange{var=global." + variable + ";value=0to100}");
                assertTrue(wrongType.translated());
                String wrongTypeKey = service.bindCondition(wrongType.condition());
                assertNotNull(wrongTypeKey);
                assertFalse(registry.testCondition(wrongTypeKey, context));
            }

            String unset = service.bindAction("variable-unset{var=" + scope + "." + variable + "}");
            assertNotNull(unset, scope);
            assertTrue(registry.runAction(unset, context), scope);
        }

        String numeric = "range" + suffix;
        String setGlobal = service.bindAction("setvariable{var=global." + numeric + ";value=42}");
        assertNotNull(setGlobal);
        assertTrue(registry.runAction(setGlobal, context));
        for (String source : List.of(
                "?VariableInRange{var=global." + numeric + ";value=40to45}",
                "?varInRange{scope=global;variable=" + numeric + ";range=>=42}",
                "?varRange{s=global;n=" + numeric + ";r=<=42}")) {
            var mapped = dev.helstera.migration.importer.MmConditions.translate(source);
            assertTrue(mapped.translated(), source);
            String condition = service.bindCondition(mapped.condition());
            assertNotNull(condition, mapped.condition());
            assertTrue(registry.testCondition(condition, context), source);
        }
        var above = dev.helstera.migration.importer.MmConditions.translate(
                "?VariableInRange{var=global." + numeric + ";value=>42}");
        assertTrue(above.translated());
        assertFalse(registry.testCondition(service.bindCondition(above.condition()), context));

        var missing = dev.helstera.migration.importer.MmConditions.translate(
                "?VariableEquals{var=global.missing" + suffix + ";value=ready}");
        assertTrue(missing.translated());
        assertFalse(registry.testCondition(service.bindCondition(missing.condition()), context));
        for (String invalid : List.of(
                "?VariableInRange{var=global." + numeric + "}",
                "?VariableInRange{var=global." + numeric + ";value=45to40}")) {
            var preserved = dev.helstera.migration.importer.MmConditions.translate(invalid);
            assertFalse(preserved.translated(), invalid);
            assertEquals(invalid, preserved.condition());
        }
        String unset = service.bindAction("variable-unset{var=global." + numeric + "}");
        assertNotNull(unset);
        assertTrue(registry.runAction(unset, context));

        String expectedVariable = "expected" + suffix;
        String actualVariable = "actual" + suffix;
        for (String variable : List.of(expectedVariable, actualVariable)) {
            String set = service.bindAction("setvariable{var=skill." + variable
                    + ";value=ready;type=STRING}");
            assertNotNull(set);
            assertTrue(registry.runAction(set, context));
        }
        var placeholderEquals = dev.helstera.migration.importer.MmConditions.translate(
                "?VariableEquals{var=skill." + actualVariable + ";value=<skill.var."
                        + expectedVariable + ">}");
        assertTrue(placeholderEquals.translated());
        String placeholderKey = service.bindCondition(placeholderEquals.condition());
        assertNotNull(placeholderKey);
        assertTrue(registry.testCondition(placeholderKey, context));
        for (String variable : List.of(expectedVariable, actualVariable)) {
            String remove = service.bindAction("variable-unset{var=skill." + variable + "}");
            assertNotNull(remove);
            assertTrue(registry.runAction(remove, context));
        }
    }

    @Test
    @DisplayName("imported free-slot aliases evaluate the target player's inventory")
    void importedFreeInventorySlotAliasesReachRuntime() {
        var player = mock(org.bukkit.entity.Player.class);
        var inventory = mock(org.bukkit.inventory.PlayerInventory.class);
        when(player.getInventory()).thenReturn(inventory);
        when(inventory.firstEmpty()).thenReturn(0);
        var context = dev.helstera.api.behavior.BehaviorContext.of(
                null, player, 1.0, -1, 0, "IDLE");
        var registry = new BehaviorRegistryImpl();
        var service = new SkillService(registry, null);

        for (String source : List.of("?hasFreeInventorySlot", "?hasFreeSlot")) {
            String directKey = service.bindCondition(source);
            assertNotNull(directKey, service.warnings().toString());
            assertTrue(registry.testCondition(directKey, context), source);
            var mapped = dev.helstera.migration.importer.MmConditions.translate(source);
            assertTrue(mapped.translated(), source);
            assertEquals("has-free-inventory-slot", mapped.condition());
            String key = service.bindCondition(mapped.condition());
            assertNotNull(key);
            assertTrue(registry.testCondition(key, context), source);
            when(inventory.firstEmpty()).thenReturn(-1);
            assertFalse(registry.testCondition(key, context), source);
            when(inventory.firstEmpty()).thenReturn(0);
        }

        var nonPlayer = dev.helstera.api.behavior.BehaviorContext.of(
                null, mock(org.bukkit.entity.Zombie.class), 1.0, -1, 0, "IDLE");
        String key = service.bindCondition("has-free-inventory-slot");
        assertFalse(registry.testCondition(key, nonPlayer));
        assertNull(service.bindCondition("has-free-inventory-slot{unknown=true}"));
        assertNull(service.bindCondition("hasFreeSlot slot=0"));
        for (String source : List.of("?hasFreeInventorySlot{slot=0}", "?hasFreeSlot{unknown=true}")) {
            assertFalse(dev.helstera.migration.importer.MmConditions.translate(source).translated(), source);
        }
    }

    @Test
    @DisplayName("imported Name aliases evaluate custom and fallback entity names")
    void importedNameAliasesReachRuntime() {
        var zombie = mock(org.bukkit.entity.Zombie.class);
        when(zombie.getCustomName()).thenReturn("Name Probe");
        when(zombie.getName()).thenReturn("Zombie");
        var context = dev.helstera.api.behavior.BehaviorContext.of(
                null, zombie, 1.0, -1, 0, "IDLE");
        var registry = new BehaviorRegistryImpl();
        var service = new SkillService(registry, null);

        for (String source : List.of("?name{n=\"Name Probe\"}", "?nameIs{\"Name Probe\"}")) {
            var mapped = dev.helstera.migration.importer.MmConditions.translate(source);
            assertTrue(mapped.translated(), source);
            assertEquals("name-is \"Name Probe\"", mapped.condition());
            String key = service.bindCondition(mapped.condition());
            assertNotNull(key);
            assertTrue(registry.testCondition(key, context), source);
        }

        var defaultName = dev.helstera.migration.importer.MmConditions.translate("?name{value=Zombie}");
        assertTrue(defaultName.translated());
        String defaultNameKey = service.bindCondition(defaultName.condition());
        assertNotNull(defaultNameKey);
        assertFalse(registry.testCondition(defaultNameKey, context));

        when(zombie.getCustomName()).thenReturn(null);
        assertTrue(registry.testCondition(defaultNameKey, context));
        String customNameKey = service.bindCondition("name-is \"Name Probe\"");
        assertNotNull(customNameKey);
        assertFalse(registry.testCondition(customNameKey, context));

        for (String source : List.of("?name{unknown=Zombie}", "?name{n=Zombie;value=Other}",
                "?name{n=}")) {
            var mapped = dev.helstera.migration.importer.MmConditions.translate(source);
            assertFalse(mapped.translated(), source);
            assertEquals(source, mapped.condition());
        }
    }

    @Test
    @DisplayName("imported server conditions evaluate exact and inclusive version boundaries")
    void importedServerConditionsReachRuntime() {
        var registry = new BehaviorRegistryImpl();
        var service = new SkillService(registry, null);
        var context = dev.helstera.api.behavior.BehaviorContext.of(
                null, null, 1.0, -1, 0, "IDLE");

        try (var bukkit = mockStatic(org.bukkit.Bukkit.class)) {
            bukkit.when(org.bukkit.Bukkit::getName).thenReturn("Paper");
            bukkit.when(org.bukkit.Bukkit::getVersion).thenReturn("1.21.1-133");
            bukkit.when(org.bukkit.Bukkit::getMinecraftVersion).thenReturn("1.21.1");

            var paper = dev.helstera.migration.importer.MmConditions.translate("?serverIsPaper");
            var paperAlias = dev.helstera.migration.importer.MmConditions.translate("?isPaper");
            assertTrue(paper.translated());
            assertTrue(paperAlias.translated());
            assertEquals("server-is-paper", paper.condition());
            assertEquals("server-is-paper", paperAlias.condition());
            String paperKey = service.bindCondition(paper.condition());
            String paperAliasKey = service.bindCondition(paperAlias.condition());
            assertNotNull(paperKey);
            assertNotNull(paperAliasKey);
            assertTrue(registry.testCondition(paperKey, context));
            assertTrue(registry.testCondition(paperAliasKey, context));
            bukkit.when(org.bukkit.Bukkit::getName).thenReturn("CraftBukkit");
            bukkit.when(org.bukkit.Bukkit::getVersion).thenReturn("1.21.1");
            assertFalse(registry.testCondition(paperKey, context));
            assertFalse(registry.testCondition(paperAliasKey, context));
            bukkit.when(org.bukkit.Bukkit::getName).thenReturn("Paper");
            bukkit.when(org.bukkit.Bukkit::getVersion).thenReturn("1.21.1-133");

            for (String source : List.of("?serverIsPaper", "?isPaper")) {
                var mapped = dev.helstera.migration.importer.MmConditions.translate(source);
                assertTrue(mapped.translated(), source);
                String key = service.bindCondition(mapped.condition());
                assertNotNull(key, source);
                assertTrue(registry.testCondition(key, context), source);
            }

            for (String source : List.of(
                    "?serverVersion{v=1.21.1}",
                    "?serverAfter{version=1.21.1}",
                    "?serverBefore{version=1.22}")) {
                var mapped = dev.helstera.migration.importer.MmConditions.translate(source);
                assertTrue(mapped.translated(), source);
                String key = service.bindCondition(mapped.condition());
                assertNotNull(key, source);
                assertTrue(registry.testCondition(key, context), source);
            }

            for (String source : List.of(
                    "?serverVersion{v=1.21.4}",
                    "?serverAfter{version=1.21.1;inclusive=false}",
                    "?serverAfter{version=1.21.4}",
                    "?serverBefore{version=1.21.1;inclusive=false}")) {
                var mapped = dev.helstera.migration.importer.MmConditions.translate(source);
                assertTrue(mapped.translated(), source);
                String key = service.bindCondition(mapped.condition());
                assertNotNull(key, source);
                assertFalse(registry.testCondition(key, context), source);
            }
        }
    }

    @Test
    @DisplayName("imported LocalDifficulty aliases bind and evaluate through SkillService")
    void importedLocalDifficultyConditionReachRuntime() {
        var world = mock(org.bukkit.World.class);
        var target = mock(org.bukkit.entity.LivingEntity.class);
        when(target.getLocation()).thenReturn(new org.bukkit.Location(world, 4, 64, 4));
        var context = dev.helstera.api.behavior.BehaviorContext.of(
                null, target, 1.0, -1, 0, "IDLE");
        var noLocation = dev.helstera.api.behavior.BehaviorContext.of(
                null, (org.bukkit.entity.LivingEntity) null, 1.0, -1, 0, "IDLE");
        var registry = new BehaviorRegistryImpl();
        var service = new SkillService(registry, null);

        try {
            SkillExtras.localDifficultyLookup(location -> 2.0);
            for (String source : List.of(
                    "?LocalDifficulty{difficulty=>0}",
                    "?localdifficulty{diff=>=2}",
                    "?localdifficulty{d=1to3}")) {
                var mapped = dev.helstera.migration.importer.MmConditions.translate(source);
                assertTrue(mapped.translated(), source);
                String key = service.bindCondition(mapped.condition());
                assertNotNull(key, source + " warnings=" + service.warnings());
                assertTrue(registry.testCondition(key, context), source);
            }
            for (String source : List.of("?localdifficulty{d=>2}", "?localdifficulty{d=3to4}")) {
                var mapped = dev.helstera.migration.importer.MmConditions.translate(source);
                assertTrue(mapped.translated(), source);
                String key = service.bindCondition(mapped.condition());
                assertNotNull(key, source);
                assertFalse(registry.testCondition(key, context), source);
            }
            assertFalse(registry.testCondition(
                    service.bindCondition("local-difficulty >0"), noLocation));
            for (String source : List.of(
                    "?localdifficulty{unknown=>0}",
                    "?localdifficulty{d=>0;diff=>1}",
                    "?localdifficulty{d=2to1}")) {
                assertFalse(dev.helstera.migration.importer.MmConditions.translate(source).translated(), source);
            }
        } finally {
            SkillExtras.resetRuntimeHooks();
        }
    }

    @Test
    @DisplayName("imported Burning condition actions reach the runtime condition evaluator")
    void importedBurningConditionActionsReachRuntime(@TempDir Path root) throws Exception {
        Path source = Files.createDirectories(root.resolve("Skills")).resolve("burning.yml");
        Files.writeString(source, """
                skills:
                  only_when_not_burning:
                    Skills:
                      - 'message{m=not burning}'
                    TargetConditions:
                      - isburning false
                  only_when_burning:
                    Skills:
                      - 'message{m=burning}'
                    TargetConditions:
                      - burning true
                """);
        var entry = new dev.helstera.migration.importer.MythicMobsImporter().convert(source).stream()
                .filter(item -> "skills.yml".equals(item.get("target-file"))).findFirst().orElseThrow();
        var yaml = YamlConfiguration.loadConfiguration(new java.io.StringReader(String.valueOf(entry.get("content"))));
        assertEquals(List.of("!on-fire"), yaml.getStringList("skills.only_when_not_burning.target-conditions"));
        assertEquals(List.of("on-fire"), yaml.getStringList("skills.only_when_burning.target-conditions"));

        var target = mock(org.bukkit.entity.LivingEntity.class);
        var context = dev.helstera.api.behavior.BehaviorContext.of(
                null, target, 1.0, -1, 0, "IDLE");
        var registry = new BehaviorRegistryImpl();
        var service = new SkillService(registry, null);
        String notBurning = service.bindCondition(yaml.getStringList(
                "skills.only_when_not_burning.target-conditions").get(0));
        String burning = service.bindCondition(yaml.getStringList(
                "skills.only_when_burning.target-conditions").get(0));
        assertNotNull(notBurning);
        assertNotNull(burning);

        when(target.getFireTicks()).thenReturn(0);
        assertTrue(registry.testCondition(notBurning, context));
        assertFalse(registry.testCondition(burning, context));
        when(target.getFireTicks()).thenReturn(20);
        assertFalse(registry.testCondition(notBurning, context));
        assertTrue(registry.testCondition(burning, context));
    }

    @Test
    void importedMessageWithCommaReachesTargetIntact(@TempDir Path root) throws Exception {
        Path source = Files.createDirectories(root.resolve("Skills")).resolve("message.yml");
        Files.writeString(source, """
                skills:
                  greeting:
                    Skills:
                      - 'message{m="Hello, radius=10; world"}'
                """);
        var entry = new dev.helstera.migration.importer.MythicMobsImporter().convert(source).stream()
                .filter(item -> "skills.yml".equals(item.get("target-file"))).findFirst().orElseThrow();
        var yaml = YamlConfiguration.loadConfiguration(new java.io.StringReader(String.valueOf(entry.get("content"))));
        String action = yaml.getStringList("skills.greeting.on-decision").get(0);
        var player = mock(org.bukkit.entity.Player.class);
        var context = dev.helstera.api.behavior.BehaviorContext.execution(null, null, 1, -1, 0,
                "skill", null, null, List.of(player), null);
        var service = new SkillService(new BehaviorRegistryImpl(), null);
        String key = service.bindAction(action);
        assertNotNull(key);
        assertTrue(service.runAction(key, context));
        verify(player).sendMessage("Hello, radius=10; world");
    }

    @Test
    void importedMessageWithEscapedQuotesReachesTargetIntact(@TempDir Path root) throws Exception {
        Path source = Files.createDirectories(root.resolve("Skills")).resolve("escaped-message.yml");
        Files.writeString(source, """
                skills:
                  greeting:
                    Skills:
                      - 'message{m="Say \\"hi; radius=10\\" at C:\\\\home } now"} @trigger'
                """);
        var entry = new dev.helstera.migration.importer.MythicMobsImporter().convert(source).stream()
                .filter(item -> "skills.yml".equals(item.get("target-file"))).findFirst().orElseThrow();
        var yaml = YamlConfiguration.loadConfiguration(new java.io.StringReader(String.valueOf(entry.get("content"))));
        var actions = yaml.getStringList("skills.greeting.on-decision");
        assertEquals(1, actions.size());
        var player = mock(org.bukkit.entity.Player.class);
        var context = dev.helstera.api.behavior.BehaviorContext.execution(null, null, 1, -1, 0,
                "skill", null, player, List.of(player), null);
        var service = new SkillService(new BehaviorRegistryImpl(), null);
        String key = service.bindAction(actions.get(0));
        assertNotNull(key, actions.get(0));
        assertTrue(service.runAction(key, context));
        verify(player).sendMessage("Say \"hi; radius=10\" at C:\\home } now");
    }

    @Test
    void importedDropItemUsesLocationAndSkipsMissingVariable(@TempDir Path root) throws Exception {
        Path source = Files.createDirectories(root.resolve("Skills")).resolve("drop-location.yml");
        Files.writeString(source, """
                skills:
                  reward:
                    Skills:
                      - dropitem{items=DIAMOND 3;n=false} @location{x=4;y=5;z=6}
                      - dropitem{i=DIAMOND;amount=2} @VariableLocation{var=skill.missing}
                """);
        var entry = new dev.helstera.migration.importer.MythicMobsImporter().convert(source).stream()
                .filter(item -> "skills.yml".equals(item.get("target-file"))).findFirst().orElseThrow();
        var yaml = YamlConfiguration.loadConfiguration(new java.io.StringReader(String.valueOf(entry.get("content"))));
        var actions = yaml.getStringList("skills.reward.on-decision");
        assertEquals(2, actions.size());
        assertTrue(yaml.getStringList("skills.reward.unsupported").isEmpty());
        var world = mock(org.bukkit.World.class);
        var origin = new org.bukkit.Location(world, 0, 64, 0);
        var destination = new org.bukkit.Location(world, 4, 5, 6);
        var template = mock(org.bukkit.inventory.ItemStack.class);
        var dropped = mock(org.bukkit.inventory.ItemStack.class);
        when(template.clone()).thenReturn(dropped);
        var context = dev.helstera.api.behavior.BehaviorContext.execution(null, null, 1, -1, 0,
                "skill", null, null, List.of(), origin);
        var service = new SkillService(new BehaviorRegistryImpl(), null);
        dev.helstera.ai.skill.SkillExtras.itemResolver(name -> template);
        try {
            for (String action : actions) {
                String key = service.bindAction(action);
                assertNotNull(key, action);
                assertTrue(service.runAction(key, context));
            }
            verify(dropped).setAmount(3);
            verify(world).dropItem(destination, dropped);
            verify(world, times(1)).dropItem(any(org.bukkit.Location.class), any());
            verify(world, never()).dropItemNaturally(any(org.bukkit.Location.class), any());
        } finally {
            dev.helstera.ai.skill.SkillExtras.itemResolver(null);
        }
    }

    @Test
    void importedInlineDropCountResolvesAtExecution(@TempDir Path root) throws Exception {
        Path source = Files.createDirectories(root.resolve("Skills")).resolve("dynamic-drop.yml");
        Files.writeString(source, """
                skills:
                  reward:
                    Skills:
                      - dropitem{items=DIAMOND <skill.var.count>;n=false} @location{x=4;y=5;z=6}
                """);
        var entry = new dev.helstera.migration.importer.MythicMobsImporter().convert(source).stream()
                .filter(item -> "skills.yml".equals(item.get("target-file"))).findFirst().orElseThrow();
        var yaml = YamlConfiguration.loadConfiguration(new java.io.StringReader(String.valueOf(entry.get("content"))));
        var actions = yaml.getStringList("skills.reward.on-decision");
        assertEquals(1, actions.size());
        var world = mock(org.bukkit.World.class);
        var template = mock(org.bukkit.inventory.ItemStack.class);
        var dropped = mock(org.bukkit.inventory.ItemStack.class);
        when(template.clone()).thenReturn(dropped);
        var context = dev.helstera.api.behavior.BehaviorContext.execution(null, null, 1, -1, 0,
                "skill", null, null, List.of(), new org.bukkit.Location(world, 0, 64, 0));
        var service = new SkillService(new BehaviorRegistryImpl(), null);
        dev.helstera.ai.skill.SkillExtras.itemResolver(name -> template);
        try {
            String action = service.bindAction(actions.get(0));
            assertNotNull(action);
            assertTrue(service.runAction(action, context));
            verify(world, never()).dropItem(any(org.bukkit.Location.class), any());
            context.skillTree().orElseThrow().setVariable("count", 5);
            assertTrue(service.runAction(action, context));
            verify(dropped).setAmount(5);
            verify(world).dropItem(new org.bukkit.Location(world, 4, 5, 6), dropped);
        } finally {
            dev.helstera.ai.skill.SkillExtras.itemResolver(null);
        }
    }

    @Test
    void importedDropItemListDispatchesEachItemAtLocation(@TempDir Path root) throws Exception {
        Path source = Files.createDirectories(root.resolve("Skills")).resolve("drop-list.yml");
        Files.writeString(source, """
                skills:
                  reward:
                    Skills:
                      - dropitem{items=DIAMOND 3,STONE 2,EMERALD <skill.var.missing>,n=false} @location{x=4;y=5;z=6}
                """);
        var entry = new dev.helstera.migration.importer.MythicMobsImporter().convert(source).stream()
                .filter(item -> "skills.yml".equals(item.get("target-file"))).findFirst().orElseThrow();
        var yaml = YamlConfiguration.loadConfiguration(new java.io.StringReader(String.valueOf(entry.get("content"))));
        var actions = yaml.getStringList("skills.reward.on-decision");
        assertEquals(1, actions.size());
        var world = mock(org.bukkit.World.class);
        var diamond = mock(org.bukkit.inventory.ItemStack.class);
        var stone = mock(org.bukkit.inventory.ItemStack.class);
        var diamondDrop = mock(org.bukkit.inventory.ItemStack.class);
        var stoneDrop = mock(org.bukkit.inventory.ItemStack.class);
        when(diamond.clone()).thenReturn(diamondDrop);
        when(stone.clone()).thenReturn(stoneDrop);
        var context = dev.helstera.api.behavior.BehaviorContext.execution(null, null, 1, -1, 0,
                "skill", null, null, List.of(), new org.bukkit.Location(world, 0, 64, 0));
        var service = new SkillService(new BehaviorRegistryImpl(), null);
        dev.helstera.ai.skill.SkillExtras.itemResolver(name -> name.equals("DIAMOND") ? diamond : stone);
        try {
            String action = service.bindAction(actions.get(0));
            assertNotNull(action);
            assertTrue(service.runAction(action, context));
            verify(diamondDrop).setAmount(3);
            verify(stoneDrop).setAmount(2);
            verify(world).dropItem(new org.bukkit.Location(world, 4, 5, 6), diamondDrop);
            verify(world).dropItem(new org.bukkit.Location(world, 4, 5, 6), stoneDrop);
            verify(world, times(2)).dropItem(any(org.bukkit.Location.class), any());
        } finally {
            dev.helstera.ai.skill.SkillExtras.itemResolver(null);
        }
    }

    @Test
    void importedExplosionUsesLocationTargetInsteadOfOrigin(@TempDir Path root) throws Exception {
        Path source = Files.createDirectories(root.resolve("Skills")).resolve("explosion.yml");
        Files.writeString(source, """
                skills:
                  blast:
                    Skills:
                      - explosion{yield=3} @location{x=4;y=5;z=6}
                """);
        var entry = new dev.helstera.migration.importer.MythicMobsImporter().convert(source).stream()
                .filter(item -> "skills.yml".equals(item.get("target-file"))).findFirst().orElseThrow();
        var yaml = YamlConfiguration.loadConfiguration(new java.io.StringReader(String.valueOf(entry.get("content"))));
        String action = yaml.getStringList("skills.blast.on-decision").get(0);
        var world = mock(org.bukkit.World.class);
        var origin = new org.bukkit.Location(world, 0, 64, 0);
        var destination = new org.bukkit.Location(world, 4, 5, 6);
        var context = dev.helstera.api.behavior.BehaviorContext.execution(null, null, 1, -1, 0,
                "skill", null, null, List.of(), origin);
        var service = new SkillService(new BehaviorRegistryImpl(), null);
        String key = service.bindAction(action);
        assertNotNull(key);
        assertTrue(service.runAction(key, context));
        verify(world).createExplosion(destination, 3f, false, false);
        verify(world, never()).createExplosion(origin, 3f, false, false);
    }

    @Test
    void importedExplosionRequiresResolvedVariableLocation(@TempDir Path root) throws Exception {
        Path source = Files.createDirectories(root.resolve("Skills")).resolve("variable-blast.yml");
        Files.writeString(source, """
                skills:
                  blast:
                    Skills:
                      - explosion{yield=2} @VariableLocation{var=skill.blast}
                """);
        var entry = new dev.helstera.migration.importer.MythicMobsImporter().convert(source).stream()
                .filter(item -> "skills.yml".equals(item.get("target-file"))).findFirst().orElseThrow();
        var yaml = YamlConfiguration.loadConfiguration(new java.io.StringReader(String.valueOf(entry.get("content"))));
        String action = yaml.getStringList("skills.blast.on-decision").get(0);
        var world = mock(org.bukkit.World.class);
        var origin = new org.bukkit.Location(world, 0, 64, 0);
        var destination = new org.bukkit.Location(world, 7, 8, 9);
        var context = dev.helstera.api.behavior.BehaviorContext.execution(null, null, 1, -1, 0,
                "skill", null, null, List.of(), origin);
        var service = new SkillService(new BehaviorRegistryImpl(), null);
        String key = service.bindAction(action);
        assertNotNull(key);
        assertTrue(service.runAction(key, context));
        verify(world, never()).createExplosion(any(org.bukkit.Location.class), anyFloat(), anyBoolean(), anyBoolean());
        context.skillTree().orElseThrow().setLocation("blast", destination);
        assertTrue(service.runAction(key, context));
        verify(world).createExplosion(destination, 2f, false, false);
        verify(world, never()).createExplosion(origin, 2f, false, false);
    }

    @Test
    void importedLocationTupleAliasReachesBlockAction(@TempDir Path root) throws Exception {
        Path source = Files.createDirectories(root.resolve("Skills")).resolve("tuple.yml");
        Files.writeString(source, """
                skills:
                  place:
                    Skills:
                      - setblock{material=STONE} @loc{l=4,5,6,0,0}
                """);
        var entry = new dev.helstera.migration.importer.MythicMobsImporter().convert(source).stream()
                .filter(item -> "skills.yml".equals(item.get("target-file"))).findFirst().orElseThrow();
        var yaml = YamlConfiguration.loadConfiguration(new java.io.StringReader(String.valueOf(entry.get("content"))));
        String action = yaml.getStringList("skills.place.on-decision").get(0);
        var world = mock(org.bukkit.World.class);
        var block = mock(org.bukkit.block.Block.class);
        when(world.getBlockAt(new org.bukkit.Location(world, 4, 5, 6))).thenReturn(block);
        var context = dev.helstera.api.behavior.BehaviorContext.execution(null, null, 1, -1, 0,
                "skill", null, null, List.of(), new org.bukkit.Location(world, 0, 64, 0));
        var service = new SkillService(new BehaviorRegistryImpl(), null);
        String key = service.bindAction(action);
        assertNotNull(key);
        assertTrue(service.runAction(key, context));
        verify(block).setType(org.bukkit.Material.STONE, false);
    }

    @Test
    void importedVariableLocationTargetsStoredBlockAndRejectsAmbiguousParameters(@TempDir Path root) throws Exception {
        Path source = Files.createDirectories(root.resolve("Skills")).resolve("location.yml");
        Files.writeString(source, """
                skills:
                  place:
                    Skills:
                      - setblock{material=STONE} @varLocation{key=mark;s=skill}
                      - setblock{material=DIRT} @varLocation{var=skill.mark;name=skill.other}
                """);
        var entry = new dev.helstera.migration.importer.MythicMobsImporter().convert(source).stream()
                .filter(item -> "skills.yml".equals(item.get("target-file"))).findFirst().orElseThrow();
        var yaml = YamlConfiguration.loadConfiguration(new java.io.StringReader(String.valueOf(entry.get("content"))));
        var actions = yaml.getStringList("skills.place.on-decision");
        assertEquals(1, actions.size());
        assertEquals(List.of("setblock{material=DIRT} @varLocation{var=skill.mark;name=skill.other}"),
                yaml.getStringList("skills.place.unsupported"));
        assertTrue(new dev.helstera.migration.importer.MythicMobsImporter().convert(source).stream()
                .anyMatch(item -> "WARN".equals(item.get("severity"))
                        && String.valueOf(item.get("note")).contains("Invalid VariableLocation")));
        var world = mock(org.bukkit.World.class);
        var destination = new org.bukkit.Location(world, 4, 5, 6);
        var block = mock(org.bukkit.block.Block.class);
        when(world.getBlockAt(destination)).thenReturn(block);
        var context = dev.helstera.api.behavior.BehaviorContext.execution(null, null, 1, -1, 0,
                "skill", null, null, List.of(), new org.bukkit.Location(world, 0, 64, 0));
        context.skillTree().orElseThrow().setLocation("mark", destination);
        var service = new SkillService(new BehaviorRegistryImpl(), null);
        for (String action : actions) {
            String key = service.bindAction(action);
            assertNotNull(key, action);
            assertTrue(service.runAction(key, context));
        }
        verify(block).setType(org.bukkit.Material.STONE, false);
        verify(block, never()).setType(org.bukkit.Material.DIRT, false);
    }

    @Test
    void reimportedMobReplacesStaleDefinitionAndBacksItUp(@TempDir Path root) throws Exception {
        Path data = Files.createDirectories(root.resolve("helstera"));
        Path mobs = Files.createDirectories(root.resolve("mythic/Mobs"));
        Files.writeString(mobs.resolve("boss.yml"), "Boss: {Type: ' skeleton ', Health: 30}\n");
        Path target = Files.createDirectories(data.resolve("mobs")).resolve("mythic_boss.yml");
        String original = "entity:\n  type: ZOMBIE\n  health: 7\nmob:\n  attack-damage: 99\n";
        Files.writeString(target, original);
        var plugin = mock(org.bukkit.plugin.Plugin.class);
        var mythic = mock(org.bukkit.plugin.Plugin.class);
        var manager = mock(org.bukkit.plugin.PluginManager.class);
        when(plugin.getDataFolder()).thenReturn(data.toFile());
        when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getAnonymousLogger());
        when(mythic.getDataFolder()).thenReturn(mobs.getParent().toFile());
        when(manager.getPlugin("MythicMobs")).thenReturn(mythic);
        try (var bukkit = mockStatic(org.bukkit.Bukkit.class)) {
            bukkit.when(org.bukkit.Bukkit::getPluginManager).thenReturn(manager);
            var migration = new dev.helstera.migration.MigrationServiceImpl(plugin);
            migration.registerImporter(new dev.helstera.migration.importer.MythicMobsImporter());
            var result = migration.apply("mythicmobs", false);
            assertEquals(true, ((java.util.Map<?, ?>) result.data().get("apply-result")).get("success"));
            var yaml = YamlConfiguration.loadConfiguration(target.toFile());
            assertEquals("SKELETON", HelsteraPlugin.configuredEntityType(
                    yaml.getConfigurationSection("entity"), yaml.getConfigurationSection("mob")));
            assertEquals(30, yaml.getInt("mob.health"));
            assertFalse(yaml.contains("entity.health"));
            assertFalse(yaml.contains("mob.attack-damage"));
            try (var backups = Files.walk(data.resolve("migration/backups"))) {
                Path backup = backups.filter(path -> path.endsWith("mobs/mythic_boss.yml"))
                        .findFirst().orElseThrow();
                assertEquals(original, Files.readString(backup));
            }
        }
    }

    @Test
    void repeatedImportsKeepIndependentBackupsUsableAfterRestart(@TempDir Path root) throws Exception {
        Path data = Files.createDirectories(root.resolve("helstera"));
        Path mobs = Files.createDirectories(root.resolve("mythic/Mobs"));
        Path source = mobs.resolve("boss.yml");
        Path target = Files.createDirectories(data.resolve("mobs")).resolve("mythic_boss.yml");
        String original = "entity: {type: ZOMBIE}\n";
        Files.writeString(target, original);
        Files.writeString(source, "Boss: {Type: SKELETON}\n");
        var plugin = mock(org.bukkit.plugin.Plugin.class);
        var mythic = mock(org.bukkit.plugin.Plugin.class);
        var manager = mock(org.bukkit.plugin.PluginManager.class);
        when(plugin.getDataFolder()).thenReturn(data.toFile());
        when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getAnonymousLogger());
        when(mythic.getDataFolder()).thenReturn(mobs.getParent().toFile());
        when(manager.getPlugin("MythicMobs")).thenReturn(mythic);
        try (var bukkit = mockStatic(org.bukkit.Bukkit.class)) {
            bukkit.when(org.bukkit.Bukkit::getPluginManager).thenReturn(manager);
            var migration = new dev.helstera.migration.MigrationServiceImpl(plugin);
            migration.registerImporter(new dev.helstera.migration.importer.MythicMobsImporter());
            assertEquals(true, ((java.util.Map<?, ?>) migration.apply("mythicmobs", false).data().get("apply-result")).get("success"));
            String firstId = String.valueOf(migration.listBackups("mythicmobs").get(0).get("backup-id"));
            String firstImport = Files.readString(target);
            Files.writeString(source, "Boss: {Type: CREEPER}\n");
            assertEquals(true, ((java.util.Map<?, ?>) migration.apply("mythicmobs", false).data().get("apply-result")).get("success"));
            var backups = migration.listBackups("mythicmobs");
            assertEquals(2, backups.size());
            String secondId = backups.stream().map(entry -> String.valueOf(entry.get("backup-id")))
                    .filter(id -> !id.equals(firstId)).findFirst().orElseThrow();
            var restarted = new dev.helstera.migration.MigrationServiceImpl(plugin);
            assertTrue(restarted.rollback("mythicmobs", secondId));
            assertEquals(firstImport, Files.readString(target));
            assertTrue(restarted.rollback("mythicmobs", firstId));
            assertEquals(original, Files.readString(target));
            Files.delete(target);
            Files.createDirectory(target);
            Files.writeString(target.resolve("blocker"), "occupied");
            assertFalse(restarted.rollback("mythicmobs", firstId));
        }
    }

    @Test
    void malformedExistingTargetStopsMigrationBeforeAnyWrites(@TempDir Path root) throws Exception {
        Path data = Files.createDirectories(root.resolve("helstera"));
        Path mythicRoot = Files.createDirectories(root.resolve("mythic"));
        Path items = Files.createDirectories(mythicRoot.resolve("Items"));
        Path mobs = Files.createDirectories(mythicRoot.resolve("Mobs"));
        Files.writeString(items.resolve("items.yml"), "blade: {Id: DIAMOND_SWORD}\n");
        Files.writeString(mobs.resolve("boss.yml"), "Boss: {Type: ZOMBIE}\n");
        Path target = data.resolve("items.yml");
        String malformed = "items: [unterminated\n";
        Files.writeString(target, malformed);
        var plugin = mock(org.bukkit.plugin.Plugin.class);
        var mythic = mock(org.bukkit.plugin.Plugin.class);
        var manager = mock(org.bukkit.plugin.PluginManager.class);
        when(plugin.getDataFolder()).thenReturn(data.toFile());
        when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getAnonymousLogger());
        when(mythic.getDataFolder()).thenReturn(mythicRoot.toFile());
        when(manager.getPlugin("MythicMobs")).thenReturn(mythic);
        try (var bukkit = mockStatic(org.bukkit.Bukkit.class)) {
            bukkit.when(org.bukkit.Bukkit::getPluginManager).thenReturn(manager);
            var migration = new dev.helstera.migration.MigrationServiceImpl(plugin);
            migration.registerImporter(new dev.helstera.migration.importer.MythicMobsImporter());
            var result = (java.util.Map<?, ?>) migration.apply("mythicmobs", false).data().get("apply-result");
            assertEquals(false, result.get("success"));
            assertTrue(String.valueOf(result.get("error")).contains("items.yml"));
            assertEquals(malformed, Files.readString(target));
            assertFalse(Files.exists(data.resolve("mobs/mythic_boss.yml")));
        }
    }

    @Test
    void migratedNullSpawnerConditionsRemainClosedAfterWritingAndLoading(@TempDir Path root) throws Exception {
        Path data = Files.createDirectories(root.resolve("helstera"));
        Path mythicRoot = Files.createDirectories(root.resolve("mythic"));
        Path source = Files.createDirectories(mythicRoot.resolve("Spawners")).resolve("gates.yml");
        Files.writeString(source, """
                spawners:
                  base: &base {Mob: boss, X: 0, Z: 0, Conditions: null}
                  merged: {<<: *base}
                  override: {<<: *base, Conditions: []}
                  alias: {Mob: boss, X: 0, Z: 0, spawn-conditions: null}
                  absent: {Mob: boss, X: 0, Z: 0}
                """);
        var plugin = mock(org.bukkit.plugin.Plugin.class);
        var mythic = mock(org.bukkit.plugin.Plugin.class);
        var manager = mock(org.bukkit.plugin.PluginManager.class);
        when(plugin.getDataFolder()).thenReturn(data.toFile());
        when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getAnonymousLogger());
        when(mythic.getDataFolder()).thenReturn(mythicRoot.toFile());
        when(manager.getPlugin("MythicMobs")).thenReturn(mythic);
        try (var bukkit = mockStatic(org.bukkit.Bukkit.class)) {
            bukkit.when(org.bukkit.Bukkit::getPluginManager).thenReturn(manager);
            var migration = new dev.helstera.migration.MigrationServiceImpl(plugin);
            migration.registerImporter(new dev.helstera.migration.importer.MythicMobsImporter());
            var result = migration.apply("mythicmobs", false);
            assertEquals(true, ((java.util.Map<?, ?>) result.data().get("apply-result")).get("success"));
        }
        var runtime = new dev.helstera.ai.spawner.SpawnerService(null, (id, loc, profile) -> 1);
        runtime.setConditionTester((condition, location) -> true);
        runtime.loadFile(data.resolve("spawners.yml"));
        var location = new org.bukkit.Location(mock(org.bukkit.World.class), 0, 64, 0);
        for (String name : List.of("base", "merged", "alias")) {
            assertFalse(runtime.forceSpawn(name, location), name);
            assertEquals("conditions rejected location", runtime.forceSpawnFailure(name), name);
        }
        assertTrue(runtime.forceSpawn("override", location));
        assertTrue(runtime.forceSpawn("absent", location));
    }

    @Test
    void malformedMigratedSpawnerConditionsFailClosedEvenWithPermissiveTester(@TempDir Path root) throws Exception {
        Path source = Files.createDirectories(root.resolve("Spawners")).resolve("gates.yml");
        Files.writeString(source, """
                gate:
                  Mob: boss
                  X: 0
                  Z: 0
                  Conditions: ['night;']
                """);
        var entries = new dev.helstera.migration.importer.MythicMobsImporter().convert(source);
        assertTrue(entries.stream().anyMatch(e -> "unsupported".equals(e.get("status"))
                && String.valueOf(e.get("note")).contains("Invalid condition")));
        String content = String.valueOf(entries.stream().filter(e -> "spawners.yml".equals(e.get("target-file")))
                .findFirst().orElseThrow().get("content"));
        var yaml = YamlConfiguration.loadConfiguration(new java.io.StringReader(content));
        assertEquals(List.of("night", ""), yaml.getStringList("spawners.gate.conditions"));
        var runtime = new dev.helstera.ai.spawner.SpawnerService(null, (id, loc, profile) -> 1);
        runtime.setConditionTester((condition, location) -> true);
        runtime.load(yaml);
        var world = mock(org.bukkit.World.class);
        when(world.getTime()).thenReturn(18000L);
        assertFalse(runtime.forceSpawn("gate", new org.bukkit.Location(world, 0, 64, 0)));
        assertEquals("conditions rejected location", runtime.forceSpawnFailure("gate"));
    }

    @Test
    void standaloneRegionAndSpawnerConditionReachRuntime(@TempDir Path root) throws Exception {
        Path data = Files.createDirectories(root.resolve("helstera"));
        Path mythicRoot = Files.createDirectories(root.resolve("mythic"));
        Path spawners = Files.createDirectories(mythicRoot.resolve("Spawners"));
        Files.writeString(spawners.resolve("regions.yml"), """
                regions:
                  Arena-A: {World: arena-world, X1: -10, Y1: 0, Z1: -10, X2: 10, Y2: 100, Z2: 10}
                """);
        Files.writeString(spawners.resolve("gates.yml"), """
                gate:
                  Mob: boss
                  X: 0
                  Z: 0
                  Conditions: ['region=Arena-A']
                """);
        var plugin = mock(org.bukkit.plugin.Plugin.class);
        var mythic = mock(org.bukkit.plugin.Plugin.class);
        var manager = mock(org.bukkit.plugin.PluginManager.class);
        when(plugin.getDataFolder()).thenReturn(data.toFile());
        when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getAnonymousLogger());
        when(mythic.getDataFolder()).thenReturn(mythicRoot.toFile());
        when(manager.getPlugin("MythicMobs")).thenReturn(mythic);
        try (var bukkit = mockStatic(org.bukkit.Bukkit.class)) {
            bukkit.when(org.bukkit.Bukkit::getPluginManager).thenReturn(manager);
            var service = new dev.helstera.migration.MigrationServiceImpl(plugin);
            service.registerImporter(new dev.helstera.migration.importer.MythicMobsImporter());
            var result = service.apply("mythicmobs", false);
            assertEquals(true, ((java.util.Map<?, ?>) result.data().get("apply-result")).get("success"));
            var yaml = YamlConfiguration.loadConfiguration(data.resolve("spawners.yml").toFile());
            assertEquals(List.of("region=arena_a"), yaml.getStringList("spawners.gate.conditions"));
            assertEquals("arena-world", yaml.getString("spawners.regions.arena_a.world"));
            var runtime = new dev.helstera.ai.spawner.SpawnerService(null, (id, loc, profile) -> 1);
            runtime.load(yaml);
            var world = mock(org.bukkit.World.class);
            when(world.getName()).thenReturn("arena-world");
            assertTrue(runtime.forceSpawn("gate", new org.bukkit.Location(world, 0, 64, 0)));
            assertFalse(runtime.forceSpawn("gate", new org.bukkit.Location(world, 11, 64, 0)));
        }
    }

    @Test
    void importedSpawnerReferencesResolveToImportedMobIds(@TempDir Path root) throws Exception {
        Path data = Files.createDirectories(root.resolve("helstera"));
        Path mythicRoot = Files.createDirectories(root.resolve("mythic"));
        Path mobs = Files.createDirectories(mythicRoot.resolve("Mobs"));
        Path spawners = Files.createDirectories(mythicRoot.resolve("Spawners"));
        Files.writeString(mobs.resolve("mobs.yml"), "mobs:\n  Boss-A: {Type: ZOMBIE}\n  Minion: {Type: SKELETON}\n");
        Files.writeString(spawners.resolve("arena.yml"), """
                arena:
                  Mob: Boss-A
                  X: 1
                  Z: 2
                  RandomMobs:
                    Boss-A: 1
                    Minion: 2
                    external: 3
                """);
        var plugin = mock(org.bukkit.plugin.Plugin.class);
        var mythic = mock(org.bukkit.plugin.Plugin.class);
        var manager = mock(org.bukkit.plugin.PluginManager.class);
        when(plugin.getDataFolder()).thenReturn(data.toFile());
        when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getAnonymousLogger());
        when(mythic.getDataFolder()).thenReturn(mythicRoot.toFile());
        when(manager.getPlugin("MythicMobs")).thenReturn(mythic);
        try (var bukkit = mockStatic(org.bukkit.Bukkit.class)) {
            bukkit.when(org.bukkit.Bukkit::getPluginManager).thenReturn(manager);
            var service = new dev.helstera.migration.MigrationServiceImpl(plugin);
            service.registerImporter(new dev.helstera.migration.importer.MythicMobsImporter());
            var result = service.apply("mythicmobs", false);
            assertEquals(true, ((java.util.Map<?, ?>) result.data().get("apply-result")).get("success"));
            assertTrue(result.entries().stream().anyMatch(e -> "unsupported".equals(e.get("status"))
                    && String.valueOf(e.get("note")).contains("external")));
            var yaml = YamlConfiguration.loadConfiguration(data.resolve("spawners.yml").toFile());
            var runtime = new dev.helstera.ai.spawner.SpawnerService(null, (id, location, profile) -> 1);
            runtime.load(yaml);
            var arena = runtime.get("arena");
            assertEquals("mythic_boss_a", arena.mobId());
            assertEquals(Set.of("mythic_boss_a", "mythic_minion", "external"),
                    arena.expectedDistribution().keySet());
        }
    }

    @Test
    void migratedItemTemplatesReferenceNormalizedDefinitionsAcrossFiles(@TempDir Path root) throws Exception {
        Path data = Files.createDirectories(root.resolve("helstera"));
        Path source = Files.createDirectories(root.resolve("mythic/Items"));
        Files.writeString(source.resolve("base.yml"), "Base-Sword: {Id: DIAMOND_SWORD}\n");
        Files.writeString(source.resolve("child.yml"), "Boss-Sword: {Template: Base-Sword}\n");
        var plugin = mock(org.bukkit.plugin.Plugin.class);
        var mythic = mock(org.bukkit.plugin.Plugin.class);
        var manager = mock(org.bukkit.plugin.PluginManager.class);
        when(plugin.getDataFolder()).thenReturn(data.toFile());
        when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getAnonymousLogger());
        when(mythic.getDataFolder()).thenReturn(source.getParent().toFile());
        when(manager.getPlugin("MythicMobs")).thenReturn(mythic);
        try (var bukkit = mockStatic(org.bukkit.Bukkit.class)) {
            bukkit.when(org.bukkit.Bukkit::getPluginManager).thenReturn(manager);
            var migration = new dev.helstera.migration.MigrationServiceImpl(plugin);
            migration.registerImporter(new dev.helstera.migration.importer.MythicMobsImporter());
            var result = migration.apply("mythicmobs", false);
            assertEquals(true, ((java.util.Map<?, ?>) result.data().get("apply-result")).get("success"));
            var yaml = YamlConfiguration.loadConfiguration(data.resolve("items.yml").toFile());
            String template = yaml.getString("items.boss_sword.template");
            assertEquals("base_sword", template);
            assertEquals("DIAMOND_SWORD", yaml.getString("items." + template + ".id"));
        }
    }

    @Test
    void itemAndSpawnerCollisionsBlockMigration(@TempDir Path root) throws Exception {
        Path data = Files.createDirectories(root.resolve("helstera"));
        Path mythicRoot = Files.createDirectories(root.resolve("mythic"));
        Path items = Files.createDirectories(mythicRoot.resolve("Items"));
        Path spawners = Files.createDirectories(mythicRoot.resolve("Spawners"));
        Path itemFile = items.resolve("items.yml");
        Path spawnerFile = spawners.resolve("spawners.yml");
        Path target = data.resolve("items.yml");
        String original = "items:\n  existing: {id: BREAD}\n";
        Files.writeString(target, original);
        var plugin = mock(org.bukkit.plugin.Plugin.class);
        var mythic = mock(org.bukkit.plugin.Plugin.class);
        var manager = mock(org.bukkit.plugin.PluginManager.class);
        when(plugin.getDataFolder()).thenReturn(data.toFile());
        when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getAnonymousLogger());
        when(mythic.getDataFolder()).thenReturn(mythicRoot.toFile());
        when(manager.getPlugin("MythicMobs")).thenReturn(mythic);
        try (var bukkit = mockStatic(org.bukkit.Bukkit.class)) {
            bukkit.when(org.bukkit.Bukkit::getPluginManager).thenReturn(manager);
            var service = new dev.helstera.migration.MigrationServiceImpl(plugin);
            service.registerImporter(new dev.helstera.migration.importer.MythicMobsImporter());
            Files.writeString(itemFile, "Boss-A: {Id: DIAMOND}\nBoss A: {Id: EMERALD}\n");
            var itemConflict = service.apply("mythicmobs", false);
            assertTrue(itemConflict.entries().stream().anyMatch(e -> "duplicate-definition-id".equals(e.get("conflict-type"))));
            assertEquals(false, ((java.util.Map<?, ?>) itemConflict.data().get("apply-result")).get("success"));
            assertEquals(original, Files.readString(target));

            Files.writeString(itemFile, "Boss-A: {Id: DIAMOND}\n");
            Files.writeString(items.resolve("other.yml"), "Boss A: {Id: EMERALD}\n");
            assertEquals(false, ((java.util.Map<?, ?>) service.apply("mythicmobs", false)
                    .data().get("apply-result")).get("success"));
            assertEquals(original, Files.readString(target));
            Files.delete(items.resolve("other.yml"));
            Files.writeString(spawnerFile, "Boss-A: {mob: one, x: 1, z: 1}\nBoss A: {mob: two, x: 2, z: 2}\n");
            assertEquals(false, ((java.util.Map<?, ?>) service.apply("mythicmobs", false)
                    .data().get("apply-result")).get("success"));
            assertEquals(original, Files.readString(target));
            Files.writeString(spawnerFile, "Boss-A: {mob: one, x: 1, z: 1}\n");
            Files.writeString(spawners.resolve("other.yml"), "Boss A: {mob: two, x: 2, z: 2}\n");
            assertEquals(false, ((java.util.Map<?, ?>) service.apply("mythicmobs", false)
                    .data().get("apply-result")).get("success"));
            assertEquals(original, Files.readString(target));
            Files.delete(spawners.resolve("other.yml"));
            Files.writeString(spawnerFile, """
                    regions:
                      Arena-A: {world: world}
                      Arena A: {world: world}
                    spawners:
                      one: {mob: one, x: 1, z: 1}
                    """);
            assertEquals(false, ((java.util.Map<?, ?>) service.apply("mythicmobs", false)
                    .data().get("apply-result")).get("success"));
            assertEquals(original, Files.readString(target));
        }
    }

    @Test
    void migratedSkillsReachRuntimeAndDuplicateIdsBlockApply(@TempDir Path root) throws Exception {
        Path data = Files.createDirectories(root.resolve("helstera"));
        Path source = Files.createDirectories(root.resolve("mythic/Skills"));
        Path first = source.resolve("first.yml");
        Files.writeString(first, "Boss-A:\n  Skills: ['damage{amount=5} @self']\n");
        Path target = data.resolve("skills.yml");
        Files.writeString(target, "skills:\n  BOSS_A: {cooldown: 100}\n  keep: {on-decision: []}\n");
        var plugin = mock(org.bukkit.plugin.Plugin.class);
        var mythic = mock(org.bukkit.plugin.Plugin.class);
        var manager = mock(org.bukkit.plugin.PluginManager.class);
        when(plugin.getDataFolder()).thenReturn(data.toFile());
        when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getAnonymousLogger());
        when(mythic.getDataFolder()).thenReturn(source.getParent().toFile());
        when(manager.getPlugin("MythicMobs")).thenReturn(mythic);
        try (var bukkit = mockStatic(org.bukkit.Bukkit.class)) {
            bukkit.when(org.bukkit.Bukkit::getPluginManager).thenReturn(manager);
            var service = new dev.helstera.migration.MigrationServiceImpl(plugin);
            service.registerImporter(new dev.helstera.migration.importer.MythicMobsImporter());
            var applied = service.apply("mythicmobs", false);
            assertEquals(true, ((java.util.Map<?, ?>) applied.data().get("apply-result")).get("success"));
            var yaml = YamlConfiguration.loadConfiguration(target.toFile());
            assertFalse(yaml.contains("skills.BOSS_A"));
            assertFalse(yaml.contains("skills.boss_a.cooldown"));
            var runtime = new SkillService(new BehaviorRegistryImpl(), null);
            runtime.loadSkills(yaml.getConfigurationSection("skills"));
            assertTrue(runtime.skillNames().containsAll(List.of("boss_a", "keep")));

            String existing = Files.readString(target);
            Files.writeString(source.resolve("second.yml"), "Boss A:\n  Skills: ['damage{amount=9} @self']\n");
            var conflict = service.apply("mythicmobs", false);
            assertTrue(conflict.entries().stream().anyMatch(e -> "duplicate-skill-id".equals(e.get("conflict-type"))));
            assertEquals(false, ((java.util.Map<?, ?>) conflict.data().get("apply-result")).get("success"));
            assertEquals(existing, Files.readString(target));

            Files.delete(source.resolve("second.yml"));
            Files.writeString(first, "Boss-A:\n  Skills: ['damage{amount=5} @self']\nBoss A:\n  Skills: ['damage{amount=9} @self']\n");
            assertTrue(service.preview("mythicmobs").entries().stream()
                    .anyMatch(e -> "duplicate-skill-id".equals(e.get("conflict-type"))));
            assertEquals(false, ((java.util.Map<?, ?>) service.apply("mythicmobs", false)
                    .data().get("apply-result")).get("success"));
            assertEquals(existing, Files.readString(target));
        }
    }

    @Test
    void migrationRejectsNormalizedMobCollisionsBeforeWriting(@TempDir Path root) throws Exception {
        Path data = Files.createDirectories(root.resolve("helstera"));
        Path mobs = Files.createDirectories(root.resolve("mythic/Mobs"));
        Files.writeString(mobs.resolve("bosses.yml"), """
                mobs:
                  Boss-A: {Type: ZOMBIE, Health: 10}
                  Boss A: {Type: SKELETON, Damage: 5}
                """);
        Path target = data.resolve("mobs/mythic_boss_a.yml");
        Files.createDirectories(target.getParent());
        String original = "id: original\n";
        Files.writeString(target, original);
        var plugin = mock(org.bukkit.plugin.Plugin.class);
        var mythic = mock(org.bukkit.plugin.Plugin.class);
        var manager = mock(org.bukkit.plugin.PluginManager.class);
        when(plugin.getDataFolder()).thenReturn(data.toFile());
        when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getAnonymousLogger());
        when(mythic.getDataFolder()).thenReturn(mobs.getParent().toFile());
        when(manager.getPlugin("MythicMobs")).thenReturn(mythic);
        try (var bukkit = mockStatic(org.bukkit.Bukkit.class)) {
            bukkit.when(org.bukkit.Bukkit::getPluginManager).thenReturn(manager);
            var service = new dev.helstera.migration.MigrationServiceImpl(plugin);
            service.registerImporter(new dev.helstera.migration.importer.MythicMobsImporter());
            var preview = service.preview("mythicmobs");
            var conflict = preview.entries().stream()
                    .filter(e -> "duplicate-mob-target".equals(e.get("conflict-type"))).findFirst().orElseThrow();
            assertEquals("mobs/mythic_boss_a.yml", conflict.get("target-file"));
            assertTrue(String.valueOf(conflict.get("note")).contains("Boss-A"));
            assertTrue(String.valueOf(conflict.get("note")).contains("Boss A"));
            var result = service.apply("mythicmobs", false);
            assertEquals(false, ((java.util.Map<?, ?>) result.data().get("apply-result")).get("success"));
            assertEquals(original, Files.readString(target));
            assertFalse(Files.exists(data.resolve("migration/backups")));
        }
    }

    @Test
    void migrationRejectsNormalizedDuplicateTablesAndPreservesExistingFiles(@TempDir Path root) throws Exception {
        Path data = Files.createDirectories(root.resolve("helstera"));
        Path source = Files.createDirectories(root.resolve("mythic/DropTables"));
        Path dropFile = source.resolve("rewards.yml");
        Files.writeString(dropFile, """
                Boss-A:
                  Drops: [DIAMOND 1 1]
                Boss A:
                  Drops: [EMERALD 1 1]
                """);
        Path target = data.resolve("loot.yml");
        String original = "tables:\n  BOSS_A: {entries: [{item: BREAD}]}\n";
        Files.writeString(target, original);
        var plugin = mock(org.bukkit.plugin.Plugin.class);
        var mythic = mock(org.bukkit.plugin.Plugin.class);
        var manager = mock(org.bukkit.plugin.PluginManager.class);
        when(plugin.getDataFolder()).thenReturn(data.toFile());
        when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getAnonymousLogger());
        when(mythic.getDataFolder()).thenReturn(source.getParent().toFile());
        when(manager.getPlugin("MythicMobs")).thenReturn(mythic);

        try (var bukkit = mockStatic(org.bukkit.Bukkit.class)) {
            bukkit.when(org.bukkit.Bukkit::getPluginManager).thenReturn(manager);
            var service = new dev.helstera.migration.MigrationServiceImpl(plugin);
            service.registerImporter(new dev.helstera.migration.importer.MythicMobsImporter());
            var preview = service.preview("mythicmobs");
            assertTrue(preview.entries().stream().anyMatch(e -> "duplicate-loot-table".equals(e.get("conflict-type"))));
            var rejected = service.apply("mythicmobs", false);
            assertEquals(false, ((java.util.Map<?, ?>) rejected.data().get("apply-result")).get("success"));
            assertEquals(original, Files.readString(target));
            assertFalse(Files.exists(data.resolve("migration/backups")));

            Files.writeString(dropFile, "Boss-A:\n  Drops: [DIAMOND 1 1]\n");
            var applied = service.apply("mythicmobs", false);
            assertEquals(true, ((java.util.Map<?, ?>) applied.data().get("apply-result")).get("success"));
            var loot = YamlConfiguration.loadConfiguration(target.toFile());
            assertFalse(loot.contains("tables.BOSS_A"));
            assertEquals("DIAMOND", loot.getString("tables.boss_a.entries.0.item"));
        }
    }

    @Test
    void invalidCollidingTableBlocksApplyBeforeUnrelatedWrites(@TempDir Path root) throws Exception {
        Path data = Files.createDirectories(root.resolve("helstera"));
        Path mythicRoot = Files.createDirectories(root.resolve("mythic"));
        Path drops = Files.createDirectories(mythicRoot.resolve("DropTables"));
        Files.writeString(drops.resolve("one.yml"), "Boss-A: {Drops: [DIAMOND 1 1]}\n");
        Files.writeString(drops.resolve("two.yml"), "Boss_A: null\n");
        Path mobs = Files.createDirectories(mythicRoot.resolve("Mobs"));
        Files.writeString(mobs.resolve("safe.yml"), "safe: {Type: ZOMBIE}\n");
        Path target = data.resolve("loot.yml");
        String original = "tables:\n  boss_a: {entries: [{item: BREAD}]}\n";
        Files.writeString(target, original);
        var plugin = mock(org.bukkit.plugin.Plugin.class);
        var mythic = mock(org.bukkit.plugin.Plugin.class);
        var manager = mock(org.bukkit.plugin.PluginManager.class);
        when(plugin.getDataFolder()).thenReturn(data.toFile());
        when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getAnonymousLogger());
        when(mythic.getDataFolder()).thenReturn(mythicRoot.toFile());
        when(manager.getPlugin("MythicMobs")).thenReturn(mythic);
        try (var bukkit = mockStatic(org.bukkit.Bukkit.class)) {
            bukkit.when(org.bukkit.Bukkit::getPluginManager).thenReturn(manager);
            var service = new dev.helstera.migration.MigrationServiceImpl(plugin);
            service.registerImporter(new dev.helstera.migration.importer.MythicMobsImporter());
            var result = service.apply("mythicmobs", false);
            assertEquals(false, ((java.util.Map<?, ?>) result.data().get("apply-result")).get("success"));
            assertTrue(result.entries().stream().anyMatch(e -> "duplicate-loot-table".equals(e.get("conflict-type"))));
            assertEquals(original, Files.readString(target));
            assertFalse(Files.exists(data.resolve("mobs/mythic_safe.yml")));
        }
    }

    @Test
    void malformedSourceStopsApplyBeforeWritingAnyFile(@TempDir Path root) throws Exception {
        Path data = Files.createDirectories(root.resolve("helstera"));
        Path source = Files.createDirectories(root.resolve("mythic/Mobs"));
        Path broken = source.resolve("broken.yml");
        Files.writeString(broken, "broken: [unterminated\n");
        Files.writeString(source.resolve("valid.yml"), "Boss: {Type: ZOMBIE}\n");
        var plugin = mock(org.bukkit.plugin.Plugin.class);
        var mythic = mock(org.bukkit.plugin.Plugin.class);
        var manager = mock(org.bukkit.plugin.PluginManager.class);
        when(plugin.getDataFolder()).thenReturn(data.toFile());
        when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getAnonymousLogger());
        when(mythic.getDataFolder()).thenReturn(source.getParent().toFile());
        when(manager.getPlugin("MythicMobs")).thenReturn(mythic);
        try (var bukkit = mockStatic(org.bukkit.Bukkit.class)) {
            bukkit.when(org.bukkit.Bukkit::getPluginManager).thenReturn(manager);
            var service = new dev.helstera.migration.MigrationServiceImpl(plugin);
            service.registerImporter(new dev.helstera.migration.importer.MythicMobsImporter());
            var preview = service.preview("mythicmobs");
            assertTrue(preview.entries().stream().anyMatch(e -> "conversion-failed".equals(e.get("conflict-type"))
                    && broken.toString().equals(e.get("file"))));
            var applied = service.apply("mythicmobs", false);
            assertEquals(false, ((java.util.Map<?, ?>) applied.data().get("apply-result")).get("success"));
            assertFalse(Files.exists(data.resolve("mobs/mythic_boss.yml")));
            assertFalse(Files.exists(data.resolve("migration/backups")));
        }
    }

    @Test
    void invalidCategoryBlocksScanAndApply(@TempDir Path root) throws Exception {
        Path data = Files.createDirectories(root.resolve("helstera"));
        Path source = Files.createDirectories(root.resolve("mythic"));
        Files.createDirectories(source.resolve("Mobs"));
        Files.writeString(source.resolve("Mobs/boss.yml"), "Boss: {Type: ZOMBIE}\n");
        Files.writeString(source.resolve("Skills"), "not a directory");
        var plugin = mock(org.bukkit.plugin.Plugin.class);
        var mythic = mock(org.bukkit.plugin.Plugin.class);
        var manager = mock(org.bukkit.plugin.PluginManager.class);
        when(plugin.getDataFolder()).thenReturn(data.toFile());
        when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getAnonymousLogger());
        when(mythic.getDataFolder()).thenReturn(source.toFile());
        when(manager.getPlugin("MythicMobs")).thenReturn(mythic);
        try (var bukkit = mockStatic(org.bukkit.Bukkit.class)) {
            bukkit.when(org.bukkit.Bukkit::getPluginManager).thenReturn(manager);
            var service = new dev.helstera.migration.MigrationServiceImpl(plugin);
            service.registerImporter(new dev.helstera.migration.importer.MythicMobsImporter());
            assertTrue(String.valueOf(service.scan("mythicmobs").data().get("error")).contains("Skills"));
            var applied = service.apply("mythicmobs", false);
            assertTrue(String.valueOf(applied.data().get("error")).contains("Skills"));
            assertFalse(Files.exists(data.resolve("mobs/mythic_boss.yml")));
            assertFalse(Files.exists(data.resolve("migration/backups")));
        }
    }

    @Test
    void migrationAppliesTopLevelAndWrappedMobDefinitions(@TempDir Path root) throws Exception {
        Path data = Files.createDirectories(root.resolve("helstera"));
        Path source = Files.createDirectories(root.resolve("mythic/Mobs"));
        Files.writeString(source.resolve("boss.yml"), "Boss: {Type: ZOMBIE, Health: 42}\nmobs:\n  Guard: {Type: SKELETON}\n");
        var plugin = mock(org.bukkit.plugin.Plugin.class);
        var mythic = mock(org.bukkit.plugin.Plugin.class);
        var manager = mock(org.bukkit.plugin.PluginManager.class);
        when(plugin.getDataFolder()).thenReturn(data.toFile());
        when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getAnonymousLogger());
        when(mythic.getDataFolder()).thenReturn(source.getParent().toFile());
        when(manager.getPlugin("MythicMobs")).thenReturn(mythic);
        try (var bukkit = mockStatic(org.bukkit.Bukkit.class)) {
            bukkit.when(org.bukkit.Bukkit::getPluginManager).thenReturn(manager);
            var service = new dev.helstera.migration.MigrationServiceImpl(plugin);
            service.registerImporter(new dev.helstera.migration.importer.MythicMobsImporter());
            var result = service.apply("mythicmobs", false);
            assertEquals(true, ((java.util.Map<?, ?>) result.data().get("apply-result")).get("success"));
            var boss = YamlConfiguration.loadConfiguration(data.resolve("mobs/mythic_boss.yml").toFile());
            assertEquals(42.0, boss.getDouble("mob.health"));
            assertTrue(Files.exists(data.resolve("mobs/mythic_guard.yml")));
        }
    }

    @Test
    void migrationUsesRootCategoryWhenNestedDirectoryHasCategoryName(@TempDir Path root) throws Exception {
        Path data = Files.createDirectories(root.resolve("helstera"));
        Path source = Files.createDirectories(root.resolve("mythic"));
        Files.createDirectories(source.resolve("DropTables/Items"));
        Files.createDirectories(source.resolve("Items/Skills"));
        Files.writeString(source.resolve("Items/Skills/gear.yml"), "Boss-Sword: {Id: DIAMOND_SWORD}\n");
        Files.writeString(source.resolve("DropTables/Items/reward.yml"), "reward: {Drops: [Boss-Sword 1 1]}\n");
        var plugin = mock(org.bukkit.plugin.Plugin.class);
        var mythic = mock(org.bukkit.plugin.Plugin.class);
        var manager = mock(org.bukkit.plugin.PluginManager.class);
        when(plugin.getDataFolder()).thenReturn(data.toFile());
        when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getAnonymousLogger());
        when(mythic.getDataFolder()).thenReturn(source.toFile());
        when(manager.getPlugin("MythicMobs")).thenReturn(mythic);
        try (var bukkit = mockStatic(org.bukkit.Bukkit.class)) {
            bukkit.when(org.bukkit.Bukkit::getPluginManager).thenReturn(manager);
            var service = new dev.helstera.migration.MigrationServiceImpl(plugin);
            service.registerImporter(new dev.helstera.migration.importer.MythicMobsImporter());
            var result = service.apply("mythicmobs", false);
            assertEquals(true, ((java.util.Map<?, ?>) result.data().get("apply-result")).get("success"));
            var loot = YamlConfiguration.loadConfiguration(data.resolve("loot.yml").toFile());
            assertEquals("boss_sword", loot.getString("tables.reward.entries.0.item"));
            var items = YamlConfiguration.loadConfiguration(data.resolve("items.yml").toFile());
            assertEquals("DIAMOND_SWORD", items.getString("items.boss_sword.id"));
        }
    }

    @Test
    void migratedNestedTableReferencesReachNormalizedRuntimeTable(@TempDir Path root) throws Exception {
        Path data = Files.createDirectories(root.resolve("helstera"));
        Path source = Files.createDirectories(root.resolve("mythic/DropTables"));
        Path area = Files.createDirectories(source.resolve("area"));
        Files.writeString(area.resolve("rewards.yml"), """
                parent:
                  Drops:
                    - Boss-A 1 1
                """);
        Files.writeString(source.resolve("child.yml"), "Boss-A:\n  Drops: [DIAMOND 1 1]\n");
        Path mobs = Files.createDirectories(source.getParent().resolve("Mobs/area"));
        Files.writeString(mobs.resolve("boss.yml"), """
                mobs:
                  Boss:
                    Type: ZOMBIE
                    DropTable-1: Boss-A
                  Caller:
                    Type: ZOMBIE
                    Drops: [Boss-A 1 1]
                """);
        var plugin = mock(org.bukkit.plugin.Plugin.class);
        var mythic = mock(org.bukkit.plugin.Plugin.class);
        var manager = mock(org.bukkit.plugin.PluginManager.class);
        when(plugin.getDataFolder()).thenReturn(data.toFile());
        when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getAnonymousLogger());
        when(mythic.getDataFolder()).thenReturn(source.getParent().toFile());
        when(manager.getPlugin("MythicMobs")).thenReturn(mythic);

        try (var bukkit = mockStatic(org.bukkit.Bukkit.class)) {
            bukkit.when(org.bukkit.Bukkit::getPluginManager).thenReturn(manager);
            var migration = new dev.helstera.migration.MigrationServiceImpl(plugin);
            migration.registerImporter(new dev.helstera.migration.importer.MythicMobsImporter());
            var result = migration.apply("mythicmobs", false);
            assertEquals(true, ((java.util.Map<?, ?>) result.data().get("apply-result")).get("success"));
            var yaml = YamlConfiguration.loadConfiguration(data.resolve("loot.yml").toFile());
            assertEquals("boss_a", yaml.getString("tables.parent.entries.0.table"));
            var mob = YamlConfiguration.loadConfiguration(data.resolve("mobs/mythic_boss.yml").toFile());
            assertEquals("boss_a", mob.getString("mob.drops.table"));
            var runtime = new dev.helstera.ai.loot.LootService(null);
            runtime.load(yaml.getConfigurationSection("tables"));
            var hits = runtime.rollPlan("parent", 0);
            assertEquals(1, hits.size());
            assertEquals("DIAMOND", hits.get(0).entry().itemId());
            assertEquals("DIAMOND", runtime.rollPlan(mob.getString("mob.drops.table"), 0)
                    .get(0).entry().itemId());
            var caller = YamlConfiguration.loadConfiguration(data.resolve("mobs/mythic_caller.yml").toFile());
            assertEquals("DIAMOND", runtime.rollPlan(caller.getString("mob.drops.table"), 0)
                    .get(0).entry().itemId());
        }
    }

    @Test
    void migratedCustomItemDropResolvesNormalizedItemDefinition(@TempDir Path root) throws Exception {
        Path items = Files.createDirectories(root.resolve("Items"));
        Path drops = Files.createDirectories(root.resolve("DropTables"));
        Path itemFile = items.resolve("gear.yml");
        Path dropFile = drops.resolve("rewards.yml");
        Files.writeString(itemFile, "Boss-Sword: {Id: DIAMOND_SWORD}\n");
        Files.writeString(dropFile, "rewards:\n  Drops: [Boss-Sword 1 1]\n");
        var importer = new dev.helstera.migration.importer.MythicMobsImporter();
        var itemPlan = importer.convert(itemFile).stream().filter(e -> "items.yml".equals(e.get("target-file")))
                .findFirst().orElseThrow();
        var dropPlan = importer.convert(dropFile).stream().filter(e -> "loot.yml".equals(e.get("target-file")))
                .findFirst().orElseThrow();
        var itemYaml = YamlConfiguration.loadConfiguration(new java.io.StringReader(String.valueOf(itemPlan.get("content"))));
        var lootYaml = YamlConfiguration.loadConfiguration(new java.io.StringReader(String.valueOf(dropPlan.get("content"))));
        var configured = mock(org.bukkit.inventory.ItemStack.class);
        var clone = mock(org.bukkit.inventory.ItemStack.class);
        when(configured.getType()).thenReturn(org.bukkit.Material.DIAMOND_SWORD);
        when(configured.clone()).thenReturn(clone);
        var runtime = new dev.helstera.ai.loot.LootService(null);
        runtime.load(lootYaml.getConfigurationSection("tables"));
        runtime.setCustomItemResolver(id -> itemYaml.isConfigurationSection("items." + id) ? configured : null);
        var result = runtime.materialize(runtime.rollPlan("rewards", 0));
        assertEquals(List.of(clone), result);
        verify(clone).setAmount(1);
    }

    @Test
    @DisplayName("standalone Mythic DropTable projection is consumed by the runtime parser")
    void migratedDropTableReachesRuntimeConsumer(@TempDir Path root) throws Exception {
        Path source = root.resolve("DropTables/rewards.yml");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                rewards:
                  TotalItems: 3
                  BonusLevelItems: 0.5
                  Drops:
                    - DIAMOND 1 0.25
                    - GOLD_INGOT 1-3 0.1
                    - EMERALD 2 0.75
                """);
        var entries = new dev.helstera.migration.importer.MythicMobsImporter().convert(source);
        String yaml = String.valueOf(entries.stream().filter(e -> "loot.yml".equals(e.get("target-file")))
                .findFirst().orElseThrow().get("content"));
        YamlConfiguration projected = YamlConfiguration.loadConfiguration(new java.io.StringReader(yaml));
        List<String> problems = new ArrayList<>();
        DropTable runtime = DropTable.parse("rewards",
                projected.getConfigurationSection("tables.rewards"), problems);

        assertTrue(problems.isEmpty(), problems.toString());
        assertEquals(3, runtime.totalItems());
        assertEquals(0.5, runtime.bonusLevelItemsMin(), 1e-9);
        assertEquals(3, runtime.entries().size());
        assertEquals("DIAMOND", runtime.entries().get(0).itemId());
        assertEquals(0.25, runtime.entries().get(0).chance(), 1e-9);
        assertEquals("GOLD_INGOT", runtime.entries().get(1).itemId());
        assertEquals(1, runtime.entries().get(1).amountMin());
        assertEquals(2, runtime.entries().get(1).amountMax());
    }

    @Test
    void malformedDropChanceCannotReachRuntimeTable(@TempDir Path root) throws Exception {
        Path source = Files.createDirectories(root.resolve("DropTables")).resolve("rewards.yml");
        Files.writeString(source, """
                rewards:
                  Drops:
                    - DIAMOND 1 1%0
                    - {item: EMERALD, chance: '25%%'}
                    - COAL 1 0x1p-1
                    - {item: IRON_INGOT, chance: '0.5d'}
                    - GOLD_INGOT 1 25%
                """);
        var converted = new dev.helstera.migration.importer.MythicMobsImporter().convert(source);
        String yaml = String.valueOf(converted.stream().filter(e -> "loot.yml".equals(e.get("target-file"))
                && "mapped".equals(e.get("status")) && e.containsKey("content"))
                .findFirst().orElseThrow().get("content"));
        var projected = YamlConfiguration.loadConfiguration(new java.io.StringReader(yaml));
        List<String> problems = new ArrayList<>();
        var runtime = DropTable.parse("rewards", projected.getConfigurationSection("tables.rewards"), problems);
        assertTrue(problems.isEmpty(), problems.toString());
        assertEquals(1, runtime.entries().size());
        assertEquals("GOLD_INGOT", runtime.entries().get(0).itemId());
        assertEquals(0.25, runtime.entries().get(0).chance(), 1e-9);
        assertTrue(converted.stream().anyMatch(e -> "rewards.Drops".equals(e.get("source-key"))
                && "WARN".equals(e.get("severity")) && String.valueOf(e.get("value")).contains("1%0")));
        assertTrue(converted.stream().anyMatch(e -> "rewards.Drops".equals(e.get("source-key"))
                && "WARN".equals(e.get("severity")) && String.valueOf(e.get("value")).contains("0x1p-1")
                && String.valueOf(e.get("value")).contains("0.5d")));
    }

    @Test
    void explicitNullDropConditionsCannotReachRuntimeRoll(@TempDir Path root) throws Exception {
        Path source = root.resolve("DropTables/rewards.yml");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                unsafe_dropper:
                  Conditions: null
                  Drops: [DIAMOND 1 1]
                unsafe_trigger:
                  TriggerConditions: ~
                  Drops: [EMERALD 1 1]
                safe:
                  Drops: [BREAD 1 1]
                """);
        var entries = new dev.helstera.migration.importer.MythicMobsImporter().convert(source);
        var combined = new YamlConfiguration();
        for (var entry : entries) {
            if (!"mapped".equals(entry.get("status")) || !entry.containsKey("content")) continue;
            var yaml = YamlConfiguration.loadConfiguration(new java.io.StringReader(String.valueOf(entry.get("content"))));
            var tables = yaml.getConfigurationSection("tables");
            if (tables == null) continue;
            for (String name : tables.getKeys(false)) combined.set("tables." + name, tables.get(name));
        }
        var service = new dev.helstera.ai.loot.LootService(null);
        service.load(combined.getConfigurationSection("tables"));
        assertNull(service.table("unsafe_dropper"));
        assertNull(service.table("unsafe_trigger"));
        assertTrue(service.rollPlan("unsafe_dropper", 0).isEmpty());
        assertTrue(service.rollPlan("unsafe_trigger", 0).isEmpty());
        var safe = service.rollPlan("safe", 0);
        assertEquals(1, safe.size());
        assertEquals("BREAD", safe.get(0).entry().itemId());
        for (String name : List.of("unsafe_dropper", "unsafe_trigger")) {
            assertTrue(entries.stream().anyMatch(e -> "unsupported".equals(e.get("status"))
                    && "WARN".equals(e.get("severity"))
                    && String.valueOf(e.get("source-key")).startsWith(name)), name);
        }
    }

    @Test
    void nestedRejectedDropTableDoesNotProduceDrops(@TempDir Path root) throws Exception {
        Path directory = Files.createDirectories(root.resolve("DropTables"));
        Path child = directory.resolve("child.yml");
        Path parent = directory.resolve("parent.yml");
        Files.writeString(child, """
                merged_defaults: &merged_defaults
                  Conditions: null
                rejected_merged:
                  <<: *merged_defaults
                  Drops: [DIAMOND 1 1]
                rejected_child:
                  Conditions: null
                  Drops: [DIAMOND 1 1]
                rejected_trigger:
                  TriggerConditions: ~
                  Drops: [EMERALD 1 1]
                rejected_unknown:
                  Conditions: [future-condition]
                  Drops: [DIAMOND 1 1]
                rejected_conflict:
                  Conditions: [is-player]
                  conditions: [is-monster]
                  Drops: [DIAMOND 1 1]
                rejected_empty:
                  Drops: [MMOItems:SWORD 1 1]
                rejected_indirect:
                  Drops: [rejected_empty 1 1]
                rejected_scalar: 42
                rejected_null: null
                allowed_child:
                  Drops: [GOLD_INGOT 1 1]
                """);
        Files.writeString(parent, """
                parent:
                  Drops:
                    - rejected_child 1 1
                    - rejected_merged 1 1
                    - {table: rejected_merged}
                    - {table: rejected_child}
                    - rejected_trigger 1 1
                    - {table: rejected_trigger}
                    - rejected_unknown 1 1
                    - {table: rejected_conflict}
                    - rejected_empty 1 1
                    - {table: rejected_indirect}
                    - rejected_scalar 1 1
                    - {table: rejected_scalar}
                    - rejected_null 1 1
                    - {table: rejected_null}
                    - allowed_child 1 1
                    - BREAD 1 1
                """);
        var importer = new dev.helstera.migration.importer.MythicMobsImporter();
        var combined = new YamlConfiguration();
        var childEntries = importer.convert(child);
        for (Path source : List.of(child, parent)) {
            for (var entry : importer.convert(source)) {
                if (!"mapped".equals(entry.get("status")) || !entry.containsKey("content")) continue;
                var yaml = YamlConfiguration.loadConfiguration(new java.io.StringReader(String.valueOf(entry.get("content"))));
                var tables = yaml.getConfigurationSection("tables");
                if (tables == null) continue;
                for (String name : tables.getKeys(false)) combined.set("tables." + name, tables.get(name));
            }
        }
        assertTrue(childEntries.stream().anyMatch(e -> "unsupported".equals(e.get("status"))
                && "rejected_child".equals(e.get("source-key")) && "WARN".equals(e.get("severity"))));
        assertTrue(importer.convert(parent).stream().anyMatch(e -> "parent.Drops".equals(e.get("source-key"))
                && "unsupported".equals(e.get("status")) && String.valueOf(e.get("value")).contains("rejected_child")
                && String.valueOf(e.get("value")).contains("rejected_merged")
                && String.valueOf(e.get("value")).contains("rejected_trigger")
                && String.valueOf(e.get("value")).contains("rejected_unknown")
                && String.valueOf(e.get("value")).contains("rejected_conflict")
                && String.valueOf(e.get("value")).contains("rejected_empty")
                && String.valueOf(e.get("value")).contains("rejected_indirect")
                && String.valueOf(e.get("value")).contains("rejected_scalar")
                && String.valueOf(e.get("value")).contains("rejected_null")));
        assertTrue(childEntries.stream().anyMatch(e -> "rejected_scalar".equals(e.get("source-key"))
                && "unsupported".equals(e.get("status")) && "WARN".equals(e.get("severity"))
                && "42".equals(String.valueOf(e.get("value")))));
        assertTrue(childEntries.stream().anyMatch(e -> "rejected_null".equals(e.get("source-key"))
                && "unsupported".equals(e.get("status")) && "WARN".equals(e.get("severity"))));
        var stale = YamlConfiguration.loadConfiguration(new java.io.StringReader("""
                entries:
                  0: {item: DIAMOND, chance: 1}
                """));
        combined.set("tables.rejected_child", stale);
        combined.set("tables.rejected_merged", stale);
        combined.set("tables.rejected_trigger", stale);
        combined.set("tables.rejected_unknown", stale);
        combined.set("tables.rejected_conflict", stale);
        combined.set("tables.rejected_empty", stale);
        combined.set("tables.rejected_indirect", stale);
        combined.set("tables.rejected_scalar", stale);
        combined.set("tables.rejected_null", stale);
        var service = new dev.helstera.ai.loot.LootService(null);
        service.load(combined.getConfigurationSection("tables"));
        assertNotNull(service.table("rejected_child"));
        var hits = service.rollPlan("parent", 0);
        assertEquals(List.of("GOLD_INGOT", "BREAD"), hits.stream().map(hit -> hit.entry().itemId()).toList());
    }

    @Test
    void appliedMergedNullDropTableCannotReachStaleTarget(@TempDir Path root) throws Exception {
        Path data = Files.createDirectories(root.resolve("helstera"));
        Path mythicRoot = Files.createDirectories(root.resolve("mythic"));
        Path drops = Files.createDirectories(mythicRoot.resolve("DropTables"));
        Files.writeString(drops.resolve("child.yml"), """
                defaults: &defaults {Conditions: null}
                unsafe:
                  <<: *defaults
                  Drops: [DIAMOND 1 1]
                """);
        Files.writeString(drops.resolve("parent.yml"), """
                parent:
                  Drops: [unsafe 1 1, BREAD 1 1]
                """);
        Files.writeString(data.resolve("loot.yml"), """
                tables:
                  unsafe:
                    entries: [{item: DIAMOND}]
                """);
        var plugin = mock(org.bukkit.plugin.Plugin.class);
        var mythic = mock(org.bukkit.plugin.Plugin.class);
        var manager = mock(org.bukkit.plugin.PluginManager.class);
        when(plugin.getDataFolder()).thenReturn(data.toFile());
        when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getAnonymousLogger());
        when(mythic.getDataFolder()).thenReturn(mythicRoot.toFile());
        when(manager.getPlugin("MythicMobs")).thenReturn(mythic);
        try (var bukkit = mockStatic(org.bukkit.Bukkit.class)) {
            bukkit.when(org.bukkit.Bukkit::getPluginManager).thenReturn(manager);
            var migration = new dev.helstera.migration.MigrationServiceImpl(plugin);
            migration.registerImporter(new dev.helstera.migration.importer.MythicMobsImporter());
            var result = migration.apply("mythicmobs", false);
            assertEquals(true, ((java.util.Map<?, ?>) result.data().get("apply-result")).get("success"));
        }
        var service = new dev.helstera.ai.loot.LootService(null);
        service.loadFile(data.resolve("loot.yml"));
        assertNotNull(service.table("unsafe"));
        assertEquals(List.of("BREAD"), service.rollPlan("parent", 0).stream()
                .map(hit -> hit.entry().itemId()).toList());
    }

    @Test
    void mobDirectDropTableReferenceRejectsUnsafeConditions(@TempDir Path root) throws Exception {
        Path drops = Files.createDirectories(root.resolve("DropTables"));
        Files.writeString(drops.resolve("tables.yml"), """
                merged_defaults: &merged_defaults
                  TriggerConditions: null
                unsafe_merged:
                  <<: *merged_defaults
                  Drops: [DIAMOND 1 1]
                unsafe:
                  Conditions: [future-condition]
                  Drops: [DIAMOND 1 1]
                safe:
                  Drops: [BREAD 1 1]
                scalar: 42
                null_table: ~
                """);
        Path mobs = Files.createDirectories(root.resolve("Mobs"));
        Path source = mobs.resolve("mobs.yml");
        Files.writeString(source, """
                invalid:
                  Type: ZOMBIE
                  DropTable-1: unsafe
                invalid_merged:
                  Type: ZOMBIE
                  DropTable-1: unsafe_merged
                valid:
                  Type: ZOMBIE
                  DropTable-1: safe
                invalid_scalar:
                  Type: ZOMBIE
                  DropTable-1: scalar
                invalid_null:
                  Type: ZOMBIE
                  DropTable-1: null_table
                """);
        var entries = new dev.helstera.migration.importer.MythicMobsImporter().convert(source);
        var invalid = entries.stream().filter(e -> "mobs/mythic_invalid.yml".equals(e.get("target-file")))
                .findFirst().orElseThrow();
        var valid = entries.stream().filter(e -> "mobs/mythic_valid.yml".equals(e.get("target-file")))
                .findFirst().orElseThrow();
        var invalidYaml = YamlConfiguration.loadConfiguration(new java.io.StringReader(String.valueOf(invalid.get("content"))));
        var validYaml = YamlConfiguration.loadConfiguration(new java.io.StringReader(String.valueOf(valid.get("content"))));
        assertFalse(invalidYaml.contains("mob.drops.table"));
        assertEquals("unsafe", invalidYaml.getString("mob.unsupported.Drops.entries"));
        var invalidMerged = entries.stream().filter(e -> "mobs/mythic_invalid_merged.yml".equals(e.get("target-file")))
                .findFirst().orElseThrow();
        var invalidMergedYaml = YamlConfiguration.loadConfiguration(new java.io.StringReader(String.valueOf(invalidMerged.get("content"))));
        assertFalse(invalidMergedYaml.contains("mob.drops.table"));
        assertEquals("unsafe_merged", invalidMergedYaml.getString("mob.unsupported.Drops.entries"));
        assertEquals("safe", validYaml.getString("mob.drops.table"));
        var invalidScalar = entries.stream().filter(e -> "mobs/mythic_invalid_scalar.yml".equals(e.get("target-file")))
                .findFirst().orElseThrow();
        var invalidScalarYaml = YamlConfiguration.loadConfiguration(new java.io.StringReader(String.valueOf(invalidScalar.get("content"))));
        assertFalse(invalidScalarYaml.contains("mob.drops.table"));
        assertEquals("scalar", invalidScalarYaml.getString("mob.unsupported.Drops.entries"));
        var invalidNull = entries.stream().filter(e -> "mobs/mythic_invalid_null.yml".equals(e.get("target-file")))
                .findFirst().orElseThrow();
        var invalidNullYaml = YamlConfiguration.loadConfiguration(new java.io.StringReader(String.valueOf(invalidNull.get("content"))));
        assertFalse(invalidNullYaml.contains("mob.drops.table"));
        assertEquals("null_table", invalidNullYaml.getString("mob.unsupported.Drops.entries"));
        assertTrue(entries.stream().anyMatch(e -> "invalid.DropTable-1".equals(e.get("source-key"))
                && "unsupported".equals(e.get("status")) && "WARN".equals(e.get("severity"))));
    }

    @Test
    void migratedDropRestrictionsAreAllEnforcedDuringRoll(@TempDir Path root) throws Exception {
        Path source = root.resolve("DropTables/rewards.yml");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                rewards:
                  Drops:
                    - item: DIAMOND
                      Conditions: is-player
                      DropIf: permission loot.vip
                      drop-if: world-is arena
                    - {item: EMERALD, future-condition: required}
                    - GOLD_INGOT 1 1 unexpected-option
                """);
        var entries = new dev.helstera.migration.importer.MythicMobsImporter().convert(source);
        var warning = entries.stream().filter(e -> "unsupported".equals(e.get("status"))
                && "rewards.Drops".equals(e.get("source-key"))).findFirst().orElseThrow();
        assertTrue(String.valueOf(warning.get("value")).contains("EMERALD"));
        assertTrue(String.valueOf(warning.get("value")).contains("GOLD_INGOT"));
        String yaml = String.valueOf(entries.stream().filter(e -> "loot.yml".equals(e.get("target-file")))
                .findFirst().orElseThrow().get("content"));
        var projected = YamlConfiguration.loadConfiguration(new java.io.StringReader(yaml));
        var service = new dev.helstera.ai.loot.LootService(null);
        service.load(projected.getConfigurationSection("tables"));
        var table = service.table("rewards");
        assertNotNull(table);
        assertEquals(1, table.entries().size());
        var conditions = List.of("is-player", "world-is arena", "has-permission loot.vip");
        assertEquals(Set.copyOf(conditions), Set.copyOf(table.entries().get(0).conditions()));

        assertTrue(service.rollPlan(table, 0, new java.util.Random(0)).isEmpty());
        for (String rejected : conditions) {
            service.setConditionEvaluator((condition, location, killer) -> !condition.equals(rejected));
            assertTrue(service.rollPlan(table, 0, new java.util.Random(0)).isEmpty(), rejected);
        }
        service.setConditionEvaluator((condition, location, killer) -> true);
        var hits = service.rollPlan(table, 0, new java.util.Random(0));
        assertEquals(1, hits.size());
        assertEquals("DIAMOND", hits.get(0).entry().itemId());
    }

    @Test
    @DisplayName("dropper condition binding evaluates the death entity context")
    void dropperConditionUsesDeathEntityContext() {
        var zombie = mock(org.bukkit.entity.Zombie.class);
        var world = mock(org.bukkit.World.class);
        when(zombie.getWorld()).thenReturn(world);
        var origin = new org.bukkit.Location(world, 0, 64, 0);
        var context = dev.helstera.api.behavior.BehaviorContext.continueExecution(
                null, zombie, 1, -1, 0, "loot-dropper", null, zombie,
                List.of(zombie), origin, new dev.helstera.api.behavior.SkillTreeContext(
                        zombie, List.of(zombie), origin));
        var registry = new BehaviorRegistryImpl();
        var skills = new SkillService(registry, null);
        String key = skills.bindCondition("is-monster");

        assertNotNull(key);
        assertTrue(registry.testCondition(key, context));
        var player = mock(org.bukkit.entity.Player.class);
        var playerContext = dev.helstera.api.behavior.BehaviorContext.continueExecution(
                null, player, 1, -1, 0, "loot-dropper", null, player,
                List.of(player), origin, new dev.helstera.api.behavior.SkillTreeContext(
                        player, List.of(player), origin));
        assertFalse(registry.testCondition(key, playerContext));
    }

    @Test
    void positionalConditionsGuardMigratedActions(@TempDir Path root) throws Exception {
        Path source = Files.createDirectories(root.resolve("Skills")).resolve("conditions.yml");
        Files.writeString(source, """
                skills:
                  conditional:
                    Skills:
                      - 'damage 3 ?targetIsAlive'
                      - 'damage 7 ?targetIsDead'
                      - 'damage 11 ?hasTarget{value=false}'
                """);
        var entries = new dev.helstera.migration.importer.MythicMobsImporter().convert(source);
        String yaml = String.valueOf(entries.stream().filter(e -> e.containsKey("target-file"))
                .findFirst().orElseThrow().get("content"));
        var registry = new BehaviorRegistryImpl();
        var skills = new SkillService(registry, null);
        skills.loadSkills(YamlConfiguration.loadConfiguration(new java.io.StringReader(yaml))
                .getConfigurationSection("skills"));
        var target = mock(org.bukkit.entity.LivingEntity.class);
        when(target.isValid()).thenReturn(true);
        var world = mock(org.bukkit.World.class);
        when(target.getWorld()).thenReturn(world);
        var context = dev.helstera.api.behavior.BehaviorContext.continueExecution(
                null, target, 1, -1, 0, "test", null, null, List.of(target),
                new org.bukkit.Location(world, 0, 64, 0), null);
        assertTrue(skills.castSkill("conditional", context), skills.warnings().toString());
        verify(target).damage(eq(3.0), isNull(org.bukkit.entity.Entity.class));
        verify(target, never()).damage(eq(7.0), isNull(org.bukkit.entity.Entity.class));
        verify(target, never()).damage(eq(11.0), isNull(org.bukkit.entity.Entity.class));
    }

    @Test
    void migratedNestedSkillExecutesWithDelayAndRepeat(@TempDir Path root) throws Exception {
        Path source = Files.createDirectories(root.resolve("Skills")).resolve("nested.yml");
        Files.writeString(source, """
                skills:
                  parent:
                    Skills:
                      - 'skill{s="child",delay=2;repeat=1;repeatinterval=3} @Target{limit=1}'
                      - 'skill{note="text;s=child"}'
                      - 'skill{s=child;unknown=true}'
                      - 'skill{s=child;repeat=-1}'
                      - 'skill{s=child;delay=NaN}'
                      - 'damage{amount=40;delay=NaN} @Target'
                      - 'damage{amount=40;repeat=1;repeat=2} @Target'
                      - 'damage 40 0%'
                      - 'damage 40 0% 100%'
                      - 'damage 40 @self @target'
                      - 'damage 40 @self > > @target'
                      - 'damage 40 101%'
                      - 'damage 40 NaN%'
                      - 'damage 40 ?'
                      - 'damage 40 @'
                  child:
                    Skills:
                      - damage{amount=4}
                """);
        var entries = new dev.helstera.migration.importer.MythicMobsImporter().convert(source);
        String yaml = String.valueOf(entries.stream().filter(e -> e.containsKey("target-file"))
                .findFirst().orElseThrow().get("content"));
        assertEquals(13, YamlConfiguration.loadConfiguration(new java.io.StringReader(yaml))
                .getStringList("skills.parent.unsupported").size());
        var registry = new BehaviorRegistryImpl();
        var skills = new SkillService(registry, null);
        skills.loadSkills(YamlConfiguration.loadConfiguration(new java.io.StringReader(yaml))
                .getConfigurationSection("skills"));
        var target = mock(org.bukkit.entity.LivingEntity.class);
        var world = mock(org.bukkit.World.class);
        when(target.getWorld()).thenReturn(world);
        when(target.getLocation()).thenReturn(new org.bukkit.Location(world, 3, 64, 0));
        when(target.getMaxHealth()).thenReturn(20.0);
        when(target.getHealth()).thenReturn(20.0);
        var context = dev.helstera.api.behavior.BehaviorContext.continueExecution(
                null, target, 1, -1, 0, "test", null, null, List.of(target),
                new org.bukkit.Location(world, 0, 64, 0), null);
        var delays = new ArrayList<Long>();
        dev.helstera.ai.skill.SkillCatalog.taskScheduler((task, delay) -> {
            delays.add(delay);
            task.run();
            return true;
        });
        try {
            assertTrue(skills.castSkill("parent", context), skills.warnings().toString());
            assertEquals(List.of(2L, 3L), delays);
            verify(target, times(2)).damage(eq(4.0), isNull(org.bukkit.entity.Entity.class));
        } finally {
            dev.helstera.ai.skill.SkillCatalog.taskScheduler(null);
        }
    }

    @Test
    void migratedMobTriggerBindsNestedSkillExecutionOptions(@TempDir Path root) throws Exception {
        Path mobs = Files.createDirectories(root.resolve("Mobs"));
        Path source = mobs.resolve("trigger.yml");
        Files.writeString(source, """
                mobs:
                  Boss:
                    Skills:
                      - skill{s=child;delay=2;repeat=1;repeatinterval=3} @Target{limit=1} ~onSpawn
                """);
        var entries = new dev.helstera.migration.importer.MythicMobsImporter().convert(source);
        String yaml = String.valueOf(entries.stream().filter(e -> e.containsKey("target-file"))
                .findFirst().orElseThrow().get("content"));
        var mob = YamlConfiguration.loadConfiguration(new java.io.StringReader(yaml)).getConfigurationSection("mob");
        var profile = dev.helstera.ai.AiProfile.fromSection("boss", mob);
        assertTrue(profile.onDecision.isEmpty());
        var registry = new BehaviorRegistryImpl();
        var skills = new SkillService(registry, null);
        var skillConfig = YamlConfiguration.loadConfiguration(new java.io.StringReader("""
                child:
                  on-decision:
                    - damage{amount=4}
                """));
        skills.loadSkills(skillConfig);
        skills.expandTriggers(profile);
        var actions = profile.triggers.get("on-spawn").actions;
        assertEquals(1, actions.size());
        var target = mock(org.bukkit.entity.LivingEntity.class);
        var world = mock(org.bukkit.World.class);
        when(target.getWorld()).thenReturn(world);
        when(target.getLocation()).thenReturn(new org.bukkit.Location(world, 3, 64, 0));
        when(target.getMaxHealth()).thenReturn(20.0);
        when(target.getHealth()).thenReturn(20.0);
        var context = dev.helstera.api.behavior.BehaviorContext.continueExecution(
                null, target, 1, -1, 0, "on-spawn", null, null, List.of(target),
                new org.bukkit.Location(world, 0, 64, 0), null);
        var delays = new ArrayList<Long>();
        dev.helstera.ai.skill.SkillCatalog.taskScheduler((task, delay) -> {
            delays.add(delay);
            task.run();
            return true;
        });
        try {
            assertTrue(registry.runAction(actions.get(0), context));
            assertEquals(List.of(2L, 3L), delays);
            verify(target, times(2)).damage(eq(4.0), isNull(org.bukkit.entity.Entity.class));
        } finally {
            dev.helstera.ai.skill.SkillCatalog.taskScheduler(null);
        }
    }

    @Test
    void migratedSpawnTriggerRunsFromModelSpawnEvent(@TempDir Path root) throws Exception {
        Path source = Files.createDirectories(root.resolve("Mobs")).resolve("spawn.yml");
        Files.writeString(source, """
                mobs:
                  Boss:
                    Skills:
                      - damage{amount=4} @Self ~onSpawn
                """);
        var entries = new dev.helstera.migration.importer.MythicMobsImporter().convert(source);
        String yaml = String.valueOf(entries.stream().filter(e -> e.containsKey("target-file"))
                .findFirst().orElseThrow().get("content"));
        var mob = YamlConfiguration.loadConfiguration(new java.io.StringReader(yaml)).getConfigurationSection("mob");
        var profile = dev.helstera.ai.AiProfile.fromSection("boss", mob);
        var registry = new BehaviorRegistryImpl();
        var skills = new SkillService(registry, null);
        skills.expandTriggers(profile);
        var ai = mock(dev.helstera.ai.AiManager.class);
        var instance = mock(dev.helstera.api.instance.ModelInstance.class);
        var entity = mock(org.bukkit.entity.LivingEntity.class);
        var world = mock(org.bukkit.World.class);
        var location = new org.bukkit.Location(world, 0, 64, 0);
        when(instance.instanceId()).thenReturn(42);
        when(instance.isValid()).thenReturn(true);
        when(instance.location()).thenReturn(location);
        when(instance.baseEntity()).thenReturn(java.util.Optional.of(entity));
        when(entity.getWorld()).thenReturn(world);
        when(entity.getLocation()).thenReturn(location);
        when(entity.getMaxHealth()).thenReturn(20.0);
        when(entity.getHealth()).thenReturn(20.0);
        when(ai.profileOf(42)).thenReturn(profile);
        var plugin = mock(org.bukkit.plugin.Plugin.class);
        var server = mock(org.bukkit.Server.class);
        var manager = mock(org.bukkit.plugin.PluginManager.class);
        var scheduler = mock(org.bukkit.scheduler.BukkitScheduler.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getPluginManager()).thenReturn(manager);
        when(server.getScheduler()).thenReturn(scheduler);
        var bus = new dev.helstera.api.event.HelsteraEventBus();
        var triggers = new dev.helstera.ai.skill.SkillTriggers(plugin, ai, registry, bus,
                java.util.logging.Logger.getAnonymousLogger(), skills);
        triggers.start();
        bus.post(new dev.helstera.api.event.ModelSpawnEvent(instance, location));
        verify(entity).damage(4.0, entity);
    }

    @Test
    void migratedDeathTriggerRunsForManagedVictim(@TempDir Path root) throws Exception {
        Path source = Files.createDirectories(root.resolve("Mobs")).resolve("death.yml");
        Files.writeString(source, """
                mobs:
                  Boss:
                    Skills:
                      - damage{amount=4} @Target ~onDeath
                      - damage{amount=2} @Trigger ~onDeath
                """);
        var entries = new dev.helstera.migration.importer.MythicMobsImporter().convert(source);
        String yaml = String.valueOf(entries.stream().filter(e -> e.containsKey("target-file"))
                .findFirst().orElseThrow().get("content"));
        var mob = YamlConfiguration.loadConfiguration(new java.io.StringReader(yaml)).getConfigurationSection("mob");
        var profile = dev.helstera.ai.AiProfile.fromSection("boss", mob);
        var registry = new BehaviorRegistryImpl();
        var skills = new SkillService(registry, null);
        skills.expandTriggers(profile);
        var ai = mock(dev.helstera.ai.AiManager.class);
        var instance = mock(dev.helstera.runtime.instance.ModelInstanceImpl.class);
        var victim = mock(org.bukkit.entity.LivingEntity.class);
        var killer = mock(org.bukkit.entity.Player.class);
        var world = mock(org.bukkit.World.class);
        var location = new org.bukkit.Location(world, 0, 64, 0);
        when(instance.instanceId()).thenReturn(42);
        when(instance.isValid()).thenReturn(true);
        when(instance.baseEntity()).thenReturn(java.util.Optional.of(victim));
        when(victim.getKiller()).thenReturn(killer);
        when(victim.getLocation()).thenReturn(location);
        when(victim.getMaxHealth()).thenReturn(20.0);
        when(killer.getWorld()).thenReturn(world);
        when(killer.getUniqueId()).thenReturn(java.util.UUID.randomUUID());
        when(killer.getLocation()).thenReturn(location);
        when(killer.getMaxHealth()).thenReturn(20.0);
        when(killer.getHealth()).thenReturn(20.0);
        when(ai.allInstances()).thenReturn(List.of(instance));
        when(ai.profileOf(42)).thenReturn(profile);
        var plugin = mock(org.bukkit.plugin.Plugin.class);
        when(plugin.getConfig()).thenReturn(new YamlConfiguration());
        var triggers = new dev.helstera.ai.skill.SkillTriggers(plugin, ai,
                registry, new dev.helstera.api.event.HelsteraEventBus(),
                java.util.logging.Logger.getAnonymousLogger(), skills);
        var event = mock(org.bukkit.event.entity.EntityDeathEvent.class);
        when(event.getEntity()).thenReturn(victim);
        triggers.onDeath(event);
        verify(killer).damage(4.0, victim);
        verify(killer).damage(2.0, victim);

        var mobKiller = mock(org.bukkit.entity.Zombie.class);
        when(mobKiller.getUniqueId()).thenReturn(java.util.UUID.randomUUID());
        when(mobKiller.getWorld()).thenReturn(world);
        when(mobKiller.getLocation()).thenReturn(location);
        when(mobKiller.getMaxHealth()).thenReturn(20.0);
        when(mobKiller.getHealth()).thenReturn(20.0);
        var damage = mock(org.bukkit.event.entity.EntityDamageByEntityEvent.class);
        var projectile = mock(org.bukkit.entity.Projectile.class);
        when(projectile.getShooter()).thenReturn(mobKiller);
        when(damage.getDamager()).thenReturn(projectile);
        when(victim.getKiller()).thenReturn(null);
        when(victim.getLastDamageCause()).thenReturn(damage);
        triggers.onDeath(event);
        verify(mobKiller).damage(4.0, victim);
        verify(mobKiller).damage(2.0, victim);
        when(victim.getLastDamageCause()).thenReturn(null);
        triggers.onDeath(event);
        verify(killer, times(1)).damage(2.0, victim);
        verify(mobKiller, times(1)).damage(2.0, victim);
        verify(event, never()).setReviveHealth(anyDouble());
    }

    @Test
    void migratedKillTriggersReachTheKillerRuntime(@TempDir Path root) throws Exception {
        Path source = Files.createDirectories(root.resolve("Mobs")).resolve("kill.yml");
        Files.writeString(source, """
                mobs:
                  Boss:
                    Skills:
                      - damage{amount=4} @Target ~onKill
                      - damage{amount=2} @Trigger ~onKillPlayer
                """);
        var entries = new dev.helstera.migration.importer.MythicMobsImporter().convert(source);
        String yaml = String.valueOf(entries.stream().filter(e -> e.containsKey("target-file"))
                .findFirst().orElseThrow().get("content"));
        var mob = YamlConfiguration.loadConfiguration(new java.io.StringReader(yaml))
                .getConfigurationSection("mob");
        var profile = dev.helstera.ai.AiProfile.fromSection("boss", mob);
        var registry = new BehaviorRegistryImpl();
        var skills = new SkillService(registry, null);
        skills.expandTriggers(profile);
        assertEquals(1, profile.triggers.get("on-kill").actions.size());
        assertEquals(1, profile.triggers.get("on-kill-player").actions.size());

        var ai = mock(dev.helstera.ai.AiManager.class);
        var instance = mock(dev.helstera.runtime.instance.ModelInstanceImpl.class);
        var killer = mock(org.bukkit.entity.Zombie.class);
        var victim = mock(org.bukkit.entity.LivingEntity.class);
        var world = mock(org.bukkit.World.class);
        var location = new org.bukkit.Location(world, 0, 64, 0);
        when(instance.instanceId()).thenReturn(42);
        when(instance.isValid()).thenReturn(true);
        when(instance.baseEntity()).thenReturn(java.util.Optional.of(killer));
        when(killer.getUniqueId()).thenReturn(java.util.UUID.randomUUID());
        when(killer.getLocation()).thenReturn(location);
        when(killer.getWorld()).thenReturn(world);
        when(victim.getUniqueId()).thenReturn(java.util.UUID.randomUUID());
        when(victim.getLocation()).thenReturn(location);
        when(victim.getWorld()).thenReturn(world);
        when(victim.getMaxHealth()).thenReturn(20.0);
        when(victim.getHealth()).thenReturn(20.0);
        var damage = mock(org.bukkit.event.entity.EntityDamageByEntityEvent.class);
        when(damage.getDamager()).thenReturn(killer);
        when(victim.getLastDamageCause()).thenReturn(damage);
        when(ai.allInstances()).thenReturn(List.of(instance));
        when(ai.profileOf(42)).thenReturn(profile);
        var plugin = mock(org.bukkit.plugin.Plugin.class);
        when(plugin.getConfig()).thenReturn(new YamlConfiguration());
        var triggers = new dev.helstera.ai.skill.SkillTriggers(plugin, ai, registry,
                new dev.helstera.api.event.HelsteraEventBus(),
                java.util.logging.Logger.getAnonymousLogger(), skills);
        var event = mock(org.bukkit.event.entity.EntityDeathEvent.class);
        when(event.getEntity()).thenReturn(victim);

        triggers.onConfirmedDeath(event);

        verify(victim).damage(4.0, killer);
        verify(victim, never()).damage(2.0, killer);
    }

    @Test
    void migratedDamagedTriggerTargetsAttacker(@TempDir Path root) throws Exception {
        Path source = Files.createDirectories(root.resolve("Mobs")).resolve("damaged.yml");
        Files.writeString(source, """
                mobs:
                  Boss:
                    Skills:
                      - damage{amount=3} @Trigger ~onDamaged
                      - damage{amount=2} @Trigger ~onHurt
                """);
        var entries = new dev.helstera.migration.importer.MythicMobsImporter().convert(source);
        String yaml = String.valueOf(entries.stream().filter(e -> e.containsKey("target-file"))
                .findFirst().orElseThrow().get("content"));
        var mob = YamlConfiguration.loadConfiguration(new java.io.StringReader(yaml)).getConfigurationSection("mob");
        var profile = dev.helstera.ai.AiProfile.fromSection("boss", mob);
        var registry = new BehaviorRegistryImpl();
        var skills = new SkillService(registry, null);
        skills.expandTriggers(profile);
        var ai = mock(dev.helstera.ai.AiManager.class);
        var instance = mock(dev.helstera.runtime.instance.ModelInstanceImpl.class);
        var victim = mock(org.bukkit.entity.LivingEntity.class);
        var attacker = mock(org.bukkit.entity.LivingEntity.class);
        var world = mock(org.bukkit.World.class);
        var location = new org.bukkit.Location(world, 0, 64, 0);
        when(instance.instanceId()).thenReturn(42);
        when(instance.isValid()).thenReturn(true);
        when(instance.baseEntity()).thenReturn(java.util.Optional.of(victim));
        when(victim.getUniqueId()).thenReturn(java.util.UUID.randomUUID());
        when(victim.getLocation()).thenReturn(location);
        when(victim.getWorld()).thenReturn(world);
        when(victim.getMaxHealth()).thenReturn(20.0);
        when(victim.getHealth()).thenReturn(20.0);
        when(attacker.getWorld()).thenReturn(world);
        when(attacker.getLocation()).thenReturn(location);
        when(attacker.getMaxHealth()).thenReturn(20.0);
        when(attacker.getHealth()).thenReturn(20.0);
        when(ai.allInstances()).thenReturn(List.of(instance));
        when(ai.profileOf(42)).thenReturn(profile);
        var plugin = mock(org.bukkit.plugin.Plugin.class);
        when(plugin.getConfig()).thenReturn(new YamlConfiguration());
        var triggers = new dev.helstera.ai.skill.SkillTriggers(plugin, ai, registry,
                new dev.helstera.api.event.HelsteraEventBus(), java.util.logging.Logger.getAnonymousLogger(), skills);
        var event = mock(org.bukkit.event.entity.EntityDamageByEntityEvent.class);
        when(event.getEntity()).thenReturn(victim);
        when(event.getDamager()).thenReturn(attacker);
        triggers.onDamage(event);
        verify(attacker).damage(3.0, victim);
        verify(attacker).damage(2.0, victim);
        var environmental = mock(org.bukkit.event.entity.EntityDamageEvent.class);
        when(environmental.getEntity()).thenReturn(victim);
        triggers.onEnvironmentalDamage(environmental);
        verify(victim, never()).damage(anyDouble(), any(org.bukkit.entity.Entity.class));
    }

    @Test
    void killTriggerWaitsForConfirmedDeath() throws Exception {
        var mob = YamlConfiguration.loadConfiguration(new java.io.StringReader("""
                mob:
                  triggers:
                    on-kill:
                      do:
                        - damage{amount=4} @Target
                """)).getConfigurationSection("mob");
        var profile = dev.helstera.ai.AiProfile.fromSection("boss", mob);
        var registry = new BehaviorRegistryImpl();
        var skills = new SkillService(registry, null);
        skills.expandTriggers(profile);
        assertTrue(profile.triggers.containsKey("on-kill"), profile.triggers.keySet().toString());
        var ai = mock(dev.helstera.ai.AiManager.class);
        var instance = mock(dev.helstera.runtime.instance.ModelInstanceImpl.class);
        var killer = mock(org.bukkit.entity.Zombie.class);
        var victim = mock(org.bukkit.entity.LivingEntity.class);
        var world = mock(org.bukkit.World.class);
        var location = new org.bukkit.Location(world, 0, 64, 0);
        when(instance.instanceId()).thenReturn(42);
        when(instance.isValid()).thenReturn(true);
        when(instance.baseEntity()).thenReturn(java.util.Optional.of(killer));
        when(killer.getUniqueId()).thenReturn(java.util.UUID.randomUUID());
        when(killer.getLocation()).thenReturn(location);
        when(killer.getWorld()).thenReturn(world);
        when(victim.getUniqueId()).thenReturn(java.util.UUID.randomUUID());
        when(victim.getLocation()).thenReturn(location);
        when(victim.getWorld()).thenReturn(world);
        when(victim.getMaxHealth()).thenReturn(20.0);
        when(victim.getHealth()).thenReturn(20.0);
        var damage = mock(org.bukkit.event.entity.EntityDamageByEntityEvent.class);
        when(damage.getDamager()).thenReturn(killer);
        when(victim.getLastDamageCause()).thenReturn(damage);
        when(ai.allInstances()).thenReturn(List.of(instance));
        when(ai.profileOf(42)).thenReturn(profile);
        var plugin = mock(org.bukkit.plugin.Plugin.class);
        when(plugin.getConfig()).thenReturn(new YamlConfiguration());
        var triggers = new dev.helstera.ai.skill.SkillTriggers(plugin, ai, registry,
                new dev.helstera.api.event.HelsteraEventBus(), java.util.logging.Logger.getAnonymousLogger(), skills);
        var event = mock(org.bukkit.event.entity.EntityDeathEvent.class);
        when(event.getEntity()).thenReturn(victim);
        triggers.onDeath(event);
        verify(victim, never()).damage(anyDouble(), any(org.bukkit.entity.Entity.class));
        when(event.isCancelled()).thenReturn(true);
        triggers.onConfirmedDeath(event);
        verify(victim, never()).damage(anyDouble(), any(org.bukkit.entity.Entity.class));
        when(event.isCancelled()).thenReturn(false);
        triggers.onConfirmedDeath(event);
        verify(victim).damage(4.0, killer);
    }

    @Test
    void migratedDeathTriggerCanCancelDeath(@TempDir Path root) throws Exception {
        Path source = Files.createDirectories(root.resolve("Mobs")).resolve("cancel.yml");
        Files.writeString(source, """
                mobs:
                  Boss:
                    Options:
                      ReviveHealth: -1
                    Skills:
                      - cancelevent ~onDeath
                """);
        var entries = new dev.helstera.migration.importer.MythicMobsImporter().convert(source);
        String yaml = String.valueOf(entries.stream().filter(e -> e.containsKey("target-file"))
                .findFirst().orElseThrow().get("content"));
        var mob = YamlConfiguration.loadConfiguration(new java.io.StringReader(yaml)).getConfigurationSection("mob");
        var profile = dev.helstera.ai.AiProfile.fromSection("boss", mob);
        var registry = new BehaviorRegistryImpl();
        var skills = new SkillService(registry, null);
        skills.expandTriggers(profile);
        var ai = mock(dev.helstera.ai.AiManager.class);
        var instance = mock(dev.helstera.runtime.instance.ModelInstanceImpl.class);
        var victim = mock(org.bukkit.entity.LivingEntity.class);
        var world = mock(org.bukkit.World.class);
        var location = new org.bukkit.Location(world, 0, 64, 0);
        when(instance.instanceId()).thenReturn(42);
        when(instance.isValid()).thenReturn(true);
        when(instance.baseEntity()).thenReturn(java.util.Optional.of(victim));
        when(victim.getLocation()).thenReturn(location);
        when(victim.getMaxHealth()).thenReturn(20.0);
        when(ai.allInstances()).thenReturn(List.of(instance));
        when(ai.profileOf(42)).thenReturn(profile);
        var plugin = mock(org.bukkit.plugin.Plugin.class);
        when(plugin.getConfig()).thenReturn(new YamlConfiguration());
        var triggers = new dev.helstera.ai.skill.SkillTriggers(plugin, ai, registry,
                new dev.helstera.api.event.HelsteraEventBus(), java.util.logging.Logger.getAnonymousLogger(), skills);
        var event = mock(org.bukkit.event.entity.EntityDeathEvent.class);
        when(event.getEntity()).thenReturn(victim);
        var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        doAnswer(invocation -> { cancelled.set(invocation.getArgument(0)); return null; })
                .when(event).setCancelled(anyBoolean());
        when(event.isCancelled()).thenAnswer(invocation -> cancelled.get());
        triggers.onDeath(event);
        verify(event).setCancelled(true);
        triggers.onCancelledDeath(event);
        verify(event).setReviveHealth(20.0);
        assertTrue(event.isCancelled());
        profile.reviveHealth = 8;
        cancelled.set(false);
        triggers.onDeath(event);
        triggers.onCancelledDeath(event);
        verify(event).setReviveHealth(8.0);
        profile.reviveHealth = 40;
        cancelled.set(false);
        triggers.onDeath(event);
        triggers.onCancelledDeath(event);
        verify(event, times(2)).setReviveHealth(20.0);
        // Another plugin can cancel after the LOWEST death-trigger handler.
        cancelled.set(false);
        profile.reviveHealth = 6;
        triggers.onCancelledDeath(event);
        verify(event, never()).setReviveHealth(6.0);
        cancelled.set(true);
        triggers.onCancelledDeath(event);
        verify(event).setReviveHealth(6.0);
        var triggerHandler = dev.helstera.ai.skill.SkillTriggers.class
                .getMethod("onDeath", org.bukkit.event.entity.EntityDeathEvent.class)
                .getAnnotation(org.bukkit.event.EventHandler.class);
        var cleanupHandler = dev.helstera.ai.AiManager.class
                .getMethod("onDeath", org.bukkit.event.entity.EntityDeathEvent.class)
                .getAnnotation(org.bukkit.event.EventHandler.class);
        var lootHandler = HelsteraPlugin.class
                .getMethod("onEntityDeathForLoot", org.bukkit.event.entity.EntityDeathEvent.class)
                .getAnnotation(org.bukkit.event.EventHandler.class);
        assertTrue(triggerHandler.priority().ordinal() < lootHandler.priority().ordinal());
        var reviveHandler = dev.helstera.ai.skill.SkillTriggers.class
                .getMethod("onCancelledDeath", org.bukkit.event.entity.EntityDeathEvent.class)
                .getAnnotation(org.bukkit.event.EventHandler.class);
        assertEquals(org.bukkit.event.EventPriority.HIGHEST, reviveHandler.priority());
        assertFalse(reviveHandler.ignoreCancelled());
        assertTrue(lootHandler.ignoreCancelled());
        assertTrue(cleanupHandler.ignoreCancelled());
        var killHandler = dev.helstera.ai.skill.SkillTriggers.class
                .getMethod("onConfirmedDeath", org.bukkit.event.entity.EntityDeathEvent.class)
                .getAnnotation(org.bukkit.event.EventHandler.class);
        var chunkHandler = HelsteraPlugin.class
                .getMethod("onConfirmedEntityDeath", org.bukkit.event.entity.EntityDeathEvent.class)
                .getAnnotation(org.bukkit.event.EventHandler.class);
        assertEquals(org.bukkit.event.EventPriority.MONITOR, killHandler.priority());
        assertTrue(killHandler.ignoreCancelled());
        assertEquals(org.bukkit.event.EventPriority.MONITOR, chunkHandler.priority());
        assertTrue(chunkHandler.ignoreCancelled());
    }

    @Test
    void migratedLightningEffectTargetsOnlySelectedEntity(@TempDir Path root) throws Exception {
        Path source = Files.createDirectories(root.resolve("Skills")).resolve("lightning.yml");
        Files.writeString(source, """
                skills:
                  storm:
                    Skills:
                      - strike-lightning-effect @Target
                """);
        var entries = new dev.helstera.migration.importer.MythicMobsImporter().convert(source);
        String yaml = String.valueOf(entries.stream().filter(e -> e.containsKey("target-file"))
                .findFirst().orElseThrow().get("content"));
        String action = YamlConfiguration.loadConfiguration(new java.io.StringReader(yaml))
                .getStringList("skills.storm.on-decision").get(0);
        assertEquals("lightning true @target", action);

        var world = mock(org.bukkit.World.class);
        var target = mock(org.bukkit.entity.LivingEntity.class);
        var location = new org.bukkit.Location(world, 3, 64, 0);
        when(target.getLocation()).thenReturn(location);
        when(target.getWorld()).thenReturn(world);
        when(target.getMaxHealth()).thenReturn(20.0);
        when(target.getHealth()).thenReturn(20.0);
        var context = dev.helstera.api.behavior.BehaviorContext.continueExecution(
                null, target, 1, -1, 0, "test", null, null,
                List.of(target), new org.bukkit.Location(world, 0, 64, 0), null);
        var registry = new BehaviorRegistryImpl();
        var skills = new SkillService(registry, null);
        String key = skills.bindAction(action);
        assertNotNull(key, skills.warnings().toString());
        assertTrue(registry.runAction(key, context));
        verify(world).strikeLightningEffect(location);
        verify(world, never()).strikeLightning(any(org.bukkit.Location.class));

        clearInvocations(world);
        var noTarget = dev.helstera.api.behavior.BehaviorContext.continueExecution(
                null, (org.bukkit.entity.LivingEntity) null, 1, -1, 0, "test", null, null,
                List.of(), new org.bukkit.Location(world, 0, 64, 0), null);
        assertTrue(registry.runAction(key, noTarget));
        verify(world, never()).strikeLightningEffect(any(org.bukkit.Location.class));
        verify(world, never()).strikeLightning(any(org.bukkit.Location.class));
    }

    @Test
    @DisplayName("migrated nearest mob targeter executes against the nearest non-player")
    void migratedNearestMobSelectsTarget(@TempDir Path root) throws Exception {
        Path source = root.resolve("Skills/target.yml");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                skills:
                  target:
                    Skills:
                      - damage{amount=5} @Nearest_Mob
                      - damage{amount=7} @living{filter=players} > @Nearest_Mob
                """);
        var entries = new dev.helstera.migration.importer.MythicMobsImporter().convert(source);
        String yaml = String.valueOf(entries.stream().filter(e -> e.containsKey("target-file"))
                .findFirst().orElseThrow().get("content"));
        var actions = YamlConfiguration.loadConfiguration(new java.io.StringReader(yaml))
                .getStringList("skills.target.on-decision");
        String action = actions.get(0);

        var world = mock(org.bukkit.World.class);
        var caster = mock(org.bukkit.entity.LivingEntity.class);
        var player = mock(org.bukkit.entity.Player.class);
        var near = mock(org.bukkit.entity.LivingEntity.class);
        var far = mock(org.bukkit.entity.LivingEntity.class);
        var instance = mock(dev.helstera.api.instance.ModelInstance.class);
        var origin = new org.bukkit.Location(world, 0, 64, 0);
        when(instance.isValid()).thenReturn(true);
        when(instance.location()).thenReturn(origin);
        when(instance.baseEntity()).thenReturn(java.util.Optional.of(caster));
        when(player.getLocation()).thenReturn(new org.bukkit.Location(world, 1, 64, 0));
        when(near.getLocation()).thenReturn(new org.bukkit.Location(world, 2, 64, 0));
        when(far.getLocation()).thenReturn(new org.bukkit.Location(world, 5, 64, 0));
        when(near.getWorld()).thenReturn(world);
        when(near.getMaxHealth()).thenReturn(20.0);
        when(near.getHealth()).thenReturn(20.0);
        var context = dev.helstera.api.behavior.BehaviorContext.continueExecution(
                instance, null, 1, -1, 0, "test", null, null,
                List.of(player, far, near), origin, null);
        var registry = new BehaviorRegistryImpl();
        var skills = new SkillService(registry, null);
        String key = skills.bindAction(action);
        assertNotNull(key, action);
        assertTrue(registry.runAction(key, context));
        verify(near).damage(5.0, caster);
        verify(far, never()).damage(anyDouble(), any(org.bukkit.entity.Entity.class));
        verify(player, never()).damage(anyDouble(), any(org.bukkit.entity.Entity.class));
        String chained = skills.bindAction(actions.get(1));
        assertNotNull(chained, actions.get(1));
        assertTrue(registry.runAction(chained, context));
        verify(near, never()).damage(eq(7.0), any(org.bukkit.entity.Entity.class));
        var playerContext = dev.helstera.api.behavior.BehaviorContext.continueExecution(
                instance, player, 1, 1, 0, "test", null, null, List.of(player), origin, null);
        String filteredContext = skills.bindAction("damage{amount=8} @Target{} > @living{filter=mob}");
        assertNotNull(filteredContext);
        assertTrue(registry.runAction(filteredContext, playerContext));
        verify(player, never()).damage(eq(8.0), any(org.bukkit.entity.Entity.class));
        for (String separator : List.of(">", " >", "> ")) {
            String compactChain = skills.bindAction(
                    "damage{amount=9} @Target{}" + separator + "@living{filter=mob}");
            assertNotNull(compactChain);
            assertTrue(registry.runAction(compactChain, playerContext));
        }
        verify(player, never()).damage(eq(9.0), any(org.bukkit.entity.Entity.class));
    }

    private static final Pattern EXPECTED_TRANSLATION = Pattern.compile(
            "assertEquals\\(\\\"([^\\\"]+)\\\"\\s*,\\s*t\\(");
    private static final Pattern POSITIVE_TRANSLATION = Pattern.compile(
            "assertTrue\\(translated\\(\\\"((?:\\\\.|[^\\\"\\\\])*)\\\"\\)\\)");
    private static final Pattern EXPECTED_ACTION = Pattern.compile(
            "actions\\.contains\\(\\\"([^\\\"]+)\\\"\\)");
    private static final Pattern ACTION_LIST_TARGET = Pattern.compile(
            "^\\)\\s*,\\s*\\w+\\.getStringList\\(\\\"(?:skills\\.[^\\\"]*\\.on-decision|mob\\.triggers\\.[^\\\"]*\\.do)\\\"\\)");
    private static final Pattern JAVA_STRING = Pattern.compile("\\\"((?:\\\\.|[^\\\"\\\\])*)\\\"");

    private static Path repoRoot() {
        Path current = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6 && current != null; i++) {
            if (Files.isDirectory(current.resolve("helstera-migration"))) return current;
            current = current.getParent();
        }
        throw new IllegalStateException("repository root not found");
    }

    private static Set<String> translatedConditions() throws IOException {
        Path source = repoRoot().resolve(
                "helstera-migration/src/test/java/dev/helstera/migration/importer/MmConditionsTest.java");
        String text = Files.readString(source, StandardCharsets.UTF_8);
        var matcher = EXPECTED_TRANSLATION.matcher(text);
        Set<String> conditions = new LinkedHashSet<>();
        while (matcher.find()) conditions.add(matcher.group(1));
        addPositiveTranslations(text, conditions);
        return conditions;
    }

    private static void addPositiveTranslations(String source, Set<String> conditions) {
        var positiveMatcher = POSITIVE_TRANSLATION.matcher(source);
        while (positiveMatcher.find()) {
            String raw = positiveMatcher.group(1).replace("\\\\", "\\").replace("\\\"", "\"");
            var mapped = dev.helstera.migration.importer.MmConditions.translate(raw);
            if (mapped.translated()) conditions.add(mapped.condition());
        }
    }

    private static Set<String> translatedActions() throws IOException {
        Path source = repoRoot().resolve(
                "helstera-migration/src/test/java/dev/helstera/migration/importer/ImportersTest.java");
        String text = Files.readString(source, StandardCharsets.UTF_8);
        var matcher = EXPECTED_ACTION.matcher(text);
        Set<String> actions = new LinkedHashSet<>();
        while (matcher.find()) actions.add(matcher.group(1));
        for (String body : actionListBodies(text)) {
            var strings = JAVA_STRING.matcher(body);
            while (strings.find()) {
                actions.add(strings.group(1).replace("\\\\", "\\").replace("\\\"", "\""));
            }
        }
        return actions;
    }

    /** Extracts List.of bodies with a small scanner so escaped quotes cannot trigger regex backtracking. */
    private static List<String> actionListBodies(String source) {
        List<String> bodies = new ArrayList<>();
        String marker = "assertEquals(List.of(";
        int from = 0;
        while ((from = source.indexOf(marker, from)) >= 0) {
            int bodyStart = from + marker.length();
            boolean quoted = false;
            boolean escaped = false;
            int close = -1;
            for (int i = bodyStart; i < source.length(); i++) {
                char c = source.charAt(i);
                if (escaped) {
                    escaped = false;
                    continue;
                }
                if (quoted && c == '\\') {
                    escaped = true;
                    continue;
                }
                if (c == '"') {
                    quoted = !quoted;
                    continue;
                }
                if (!quoted && c == ')' && source.startsWith(",", i + 1)) {
                    close = i;
                    break;
                }
            }
            if (close >= 0) {
                String tail = source.substring(close, Math.min(source.length(), close + 220));
                if (ACTION_LIST_TARGET.matcher(tail).find()) {
                    bodies.add(source.substring(bodyStart, close));
                }
                from = close + 2;
            } else {
                break;
            }
        }
        return bodies;
    }

    @Test
    void actionAuditIncludesTriggerActionsButExcludesPreservedSource() {
        String source = "assertEquals(List.of(\"unsupported-source\"), mobTarget.getStringList(\"mob.unsupported.Skills\"));"
                + "assertEquals(List.of(\"drop-item STONE 1\"), mobTarget.getStringList(\"mob.triggers.on-spawn.do\"));"
                + "assertEquals(List.of(\"message-target hi\"), target.getStringList(\"skills.demo.on-decision\"));";
        var bodies = actionListBodies(source);
        assertEquals(2, bodies.size());
        assertTrue(bodies.get(0).contains("drop-item STONE 1"));
        assertTrue(bodies.get(1).contains("message-target hi"));
    }

    @Test
    @DisplayName("positive translation-only assertions join the runtime condition audit")
    void positiveTranslationAssertionsJoinRuntimeAudit() {
        Set<String> conditions = new LinkedHashSet<>();
        addPositiveTranslations("assertTrue(translated(\"?serverIsPaper\"));"
                + "assertTrue(translated(\"?hasAuras\"));", conditions);

        assertTrue(conditions.contains("server-is-paper"));
        assertFalse(conditions.contains("?hasAuras"));
    }

    @Test
    void invalidImportedDamageConditionsNeverApplyEvenWithPermissiveEvaluator(@TempDir Path root) throws Exception {
        Path source = Files.createDirectories(root.resolve("Mobs")).resolve("modifiers.yml");
        Files.writeString(source, """
                mobs:
                  Boss:
                    DamageModifiers:
                      FIRE:
                        multiplier: 0.5
                        conditions: [null]
                      PROJECTILE:
                        - multiplier: 0.5
                          conditions: [42]
                """);
        var entries = new dev.helstera.migration.importer.MythicMobsImporter().convert(source);
        var plan = entries.stream().filter(e -> e.containsKey("target-file")).findFirst().orElseThrow();
        var yaml = YamlConfiguration.loadConfiguration(new java.io.StringReader(String.valueOf(plan.get("content"))));
        var profile = dev.helstera.ai.AiProfile.fromSection("boss", yaml.getConfigurationSection("mob"));
        for (String cause : List.of("FIRE", "PROJECTILE")) {
            assertFalse(profile.immunityTable().evaluate(cause, 10, ignored -> true).matched(), cause);
        }
        assertEquals(java.util.Collections.singletonList(null),
                yaml.getList("mob.unsupported.DamageModifiers.FIRE.conditions"));
        var preserved = yaml.getMapList("mob.unsupported.DamageModifiers.PROJECTILE");
        assertEquals(1, preserved.size());
        assertEquals(List.of(42), preserved.get(0).get("conditions"));
        assertEquals(2, entries.stream().filter(e -> "WARN".equals(e.get("severity"))
                && String.valueOf(e.get("source-key")).endsWith(".conditions")).count());
    }

    @Test
    void importedModifierConditionsPreserveCaseAndExplicitNull(@TempDir Path root) throws Exception {
        Path source = Files.createDirectories(root.resolve("Mobs")).resolve("modifiers.yml");
        Files.writeString(source, """
                mobs:
                  Boss:
                    DamageModifiers:
                      FIRE:
                        Multiplier: 0.5
                        Conditions: ['health-above 0.7']
                      PROJECTILE:
                        - Multiplier: 0.5
                          Conditions: null
                      MAGIC:
                        multiplier: invalid
                        conditions: ['health-above 0.7']
                """);
        var entries = new dev.helstera.migration.importer.MythicMobsImporter().convert(source);
        var plan = entries.stream().filter(e -> e.containsKey("target-file")).findFirst().orElseThrow();
        var yaml = YamlConfiguration.loadConfiguration(new java.io.StringReader(String.valueOf(plan.get("content"))));
        var profile = dev.helstera.ai.AiProfile.fromSection("boss", yaml.getConfigurationSection("mob"));
        var table = profile.immunityTable();
        assertFalse(table.evaluate("FIRE", 10, ignored -> false).matched());
        assertEquals(5, table.evaluate("FIRE", 10, ignored -> true).damage(), 1e-9);
        assertFalse(table.evaluate("PROJECTILE", 10, ignored -> true).matched());
        var preserved = yaml.getMapList("mob.unsupported.DamageModifiers.PROJECTILE");
        assertEquals(1, preserved.size());
        assertTrue(preserved.get(0).containsKey("Conditions"));
        assertEquals(null, preserved.get(0).get("Conditions"));
        assertFalse(table.evaluate("MAGIC", 10, ignored -> true).matched());
        assertTrue(entries.stream().anyMatch(e -> "WARN".equals(e.get("severity"))
                && String.valueOf(e.get("note")).contains("Invalid multiplier")));
    }

    @Test
    void importedImmunitySectionsRetainConditionsAndRejectUnknownFields(@TempDir Path root) throws Exception {
        Path source = Files.createDirectories(root.resolve("Mobs")).resolve("immunity.yml");
        Files.writeString(source, """
                mobs:
                  Boss:
                    Immunities:
                      FIRE:
                        Conditions: ['health-above 0.7']
                      PROJECTILE:
                        conditions: [null]
                      MAGIC:
                        unknown-gate: true
                        custom:
                          levels: [1, 2]
                      FALL: true
                """);
        var entries = new dev.helstera.migration.importer.MythicMobsImporter().convert(source);
        var plan = entries.stream().filter(e -> e.containsKey("target-file")).findFirst().orElseThrow();
        var yaml = YamlConfiguration.loadConfiguration(new java.io.StringReader(String.valueOf(plan.get("content"))));
        var table = dev.helstera.ai.AiProfile.fromSection("boss", yaml.getConfigurationSection("mob")).immunityTable();
        assertFalse(table.evaluate("FIRE", 10, ignored -> false).matched());
        assertEquals(0, table.evaluate("FIRE", 10, ignored -> true).damage(), 1e-9);
        assertFalse(table.evaluate("PROJECTILE", 10, ignored -> true).matched());
        assertFalse(table.evaluate("MAGIC", 10, ignored -> true).matched());
        assertEquals(0, table.evaluate("FALL", 10, ignored -> false).damage(), 1e-9);
        assertEquals(List.of(1, 2), yaml.getIntegerList("mob.unsupported.Immunities.MAGIC.custom.levels"));
        assertTrue(entries.stream().anyMatch(e -> "WARN".equals(e.get("severity"))
                && String.valueOf(e.get("note")).contains("unknown-gate")));
    }

    @Test
    void unsupportedModifierFieldsDoNotBecomeUnconditional(@TempDir Path root) throws Exception {
        Path source = Files.createDirectories(root.resolve("Mobs")).resolve("modifiers.yml");
        Files.writeString(source, """
                mobs:
                  Boss:
                    DamageModifiers:
                      FIRE:
                        multiplier: 0.5
                        unknown-gate: true
                        custom:
                          levels: [3, 4]
                      PROJECTILE:
                        - multiplier: 0.5
                          unknown-gate: true
                      MAGIC:
                        - multiplier: 0.5
                        - multiplier: 0.25
                """);
        var entries = new dev.helstera.migration.importer.MythicMobsImporter().convert(source);
        var plan = entries.stream().filter(e -> e.containsKey("target-file")).findFirst().orElseThrow();
        var yaml = YamlConfiguration.loadConfiguration(new java.io.StringReader(String.valueOf(plan.get("content"))));
        var table = dev.helstera.ai.AiProfile.fromSection("boss", yaml.getConfigurationSection("mob")).immunityTable();
        assertFalse(table.evaluate("FIRE", 10, ignored -> true).matched());
        assertFalse(table.evaluate("PROJECTILE", 10, ignored -> true).matched());
        assertEquals(5, table.evaluate("MAGIC", 10, ignored -> true).damage(), 1e-9);
        assertTrue(yaml.contains("mob.unsupported.DamageModifiers.FIRE"));
        assertEquals(List.of(3, 4), yaml.getIntegerList("mob.unsupported.DamageModifiers.FIRE.custom.levels"));
        assertTrue(yaml.contains("mob.unsupported.DamageModifiers.PROJECTILE"));
        assertEquals(2, yaml.getList("mob.unsupported.DamageModifiers.MAGIC").size());
        assertTrue(entries.stream().filter(e -> "WARN".equals(e.get("severity"))
                && "unsupported".equals(e.get("status"))).count() >= 3);
    }

    @Test
    void explicitNullInModifierAndImmunitySectionsFailsClosed(@TempDir Path root) throws Exception {
        Path source = Files.createDirectories(root.resolve("Mobs")).resolve("nulls.yml");
        Files.writeString(source, """
                mobs:
                  Boss:
                    Immunities:
                      FIRE:
                        When: null
                      FALL: true
                      WITHER: null
                      ENTITY_ATTACK:
                        unknown-gate: null
                    DamageModifiers:
                      PROJECTILE:
                        multiplier: 0.5
                        Conditions: ~
                      MAGIC:
                        multiplier: 0.5
                      LAVA:
                        multiplier: 0.5
                        when: null
                      DROWNING:
                        multiplier: null
                        conditions: ['health-above 0.1']
                      POISON: null
                      ENTITY_ATTACK:
                        multiplier: 0.5
                        unknown-gate: null
                """);
        var entries = new dev.helstera.migration.importer.MythicMobsImporter().convert(source);
        var plan = entries.stream().filter(e -> e.containsKey("target-file")).findFirst().orElseThrow();
        var yaml = YamlConfiguration.loadConfiguration(new java.io.StringReader(String.valueOf(plan.get("content"))));
        var table = dev.helstera.ai.AiProfile.fromSection("boss", yaml.getConfigurationSection("mob")).immunityTable();
        assertFalse(table.evaluate("FIRE", 10, ignored -> true).matched());
        assertFalse(table.evaluate("WITHER", 10, ignored -> true).matched());
        assertFalse(table.evaluate("ENTITY_ATTACK", 10, ignored -> true).matched());
        assertTrue(yaml.contains("mob.unsupported.Immunities.ENTITY_ATTACK.unknown-gate"));
        assertTrue(yaml.contains("mob.unsupported.DamageModifiers.ENTITY_ATTACK.unknown-gate"));
        assertTrue(entries.stream().anyMatch(e -> "WARN".equals(e.get("severity"))
                && String.valueOf(e.get("source-key")).endsWith(".Immunities.WITHER")));
        assertFalse(table.evaluate("PROJECTILE", 10, ignored -> true).matched());
        assertEquals(0, table.evaluate("FALL", 10, ignored -> true).damage(), 1e-9);
        assertEquals(5, table.evaluate("MAGIC", 10, ignored -> true).damage(), 1e-9);
        assertFalse(table.evaluate("LAVA", 10, ignored -> true).matched());
        assertFalse(table.evaluate("DROWNING", 10, ignored -> true).matched());
        assertFalse(table.evaluate("POISON", 10, ignored -> true).matched());
        assertTrue(yaml.contains("mob.unsupported.DamageModifiers.DROWNING"));
        assertTrue(yaml.contains("mob.unsupported.DamageModifiers.POISON"));
        assertTrue(entries.stream().anyMatch(e -> "WARN".equals(e.get("severity"))
                && String.valueOf(e.get("source-key")).endsWith(".DamageModifiers.POISON")));
        assertTrue(entries.stream().filter(e -> "WARN".equals(e.get("severity"))).count() >= 2);
    }

    @Test
    void invalidImportedConditionEntriesCannotRemoveSkillGates(@TempDir Path root) throws Exception {
        Path source = Files.createDirectories(root.resolve("Skills")).resolve("invalid.yml");
        Files.writeString(source, """
                skills:
                  null_condition:
                    TargetConditions: [null]
                    Skills: ['damage{amount=4} @Target']
                  numeric_condition:
                    TriggerConditions: [123]
                    Skills: ['damage{amount=4} @Target']
                  blank_condition:
                    TargetConditions: ['']
                    Skills: ['damage{amount=4} @Target']
                """);
        var entries = new dev.helstera.migration.importer.MythicMobsImporter().convert(source);
        var plan = entries.stream().filter(e -> e.containsKey("target-file")).findFirst().orElseThrow();
        var yaml = YamlConfiguration.loadConfiguration(new java.io.StringReader(String.valueOf(plan.get("content"))));
        var registry = new BehaviorRegistryImpl();
        var skills = new SkillService(registry, null);
        skills.loadSkills(yaml.getConfigurationSection("skills"));
        var target = mock(org.bukkit.entity.LivingEntity.class);
        var context = dev.helstera.api.behavior.BehaviorContext.continueExecution(
                null, target, 1, 1, 0, "IDLE", null, target, List.of(target), null, null);
        for (String name : List.of("null_condition", "numeric_condition", "blank_condition")) {
            assertFalse(skills.castSkill(name, context), name);
        }
        assertEquals(3, entries.stream().filter(e -> "WARN".equals(e.get("severity"))
                && String.valueOf(e.get("source-key")).contains("Conditions.unmapped")).count());
        verify(target, never()).damage(anyDouble(), any(org.bukkit.entity.Entity.class));
    }

    @Test
    void invalidLastSignalArgumentsRemainRejectedAfterImport(@TempDir Path root) throws Exception {
        Path source = Files.createDirectories(root.resolve("Skills")).resolve("signals.yml");
        Files.writeString(source, "skills:\n  invalid:\n    TargetConditions: ['lastSignal{s=phase2;unknown=x}']\n"
                + "    Skills: ['damage{amount=4} @Target']\n"
                + "  duplicate:\n    TargetConditions: ['lastSignal{s=phase1;s=phase2}']\n"
                + "    Skills: ['damage{amount=4} @Target']\n");
        var entries = new dev.helstera.migration.importer.MythicMobsImporter().convert(source);
        assertTrue(entries.stream().anyMatch(e -> String.valueOf(e.get("source-key")).contains("Conditions.unmapped")));
        var plan = entries.stream().filter(e -> "skills.yml".equals(e.get("target-file"))).findFirst().orElseThrow();
        var yaml = YamlConfiguration.loadConfiguration(new java.io.StringReader(String.valueOf(plan.get("content"))));
        var skills = new SkillService(new BehaviorRegistryImpl(), null);
        skills.loadSkills(yaml.getConfigurationSection("skills"));
        var target = mock(org.bukkit.entity.LivingEntity.class);
        var context = dev.helstera.api.behavior.BehaviorContext.continueExecution(
                null, target, 1, 1, 0, "IDLE", null, target, List.of(target), null, null);
        assertFalse(skills.castSkill("invalid", context));
        assertFalse(skills.castSkill("duplicate", context));
        verify(target, never()).damage(anyDouble(), any(org.bukkit.entity.Entity.class));
    }

    @Test
    void importedTargetSneakingGatesNonPlayerTargets(@TempDir Path root) throws Exception {
        Path source = Files.createDirectories(root.resolve("Skills")).resolve("sneaking.yml");
        Files.writeString(source, "skills:\n  sneaking:\n    TargetConditions: [targetIsSneaking]\n"
                + "    Skills: ['damage{amount=4} @Target']\n");
        var entries = new dev.helstera.migration.importer.MythicMobsImporter().convert(source);
        var plan = entries.stream().filter(e -> "skills.yml".equals(e.get("target-file"))).findFirst().orElseThrow();
        var yaml = YamlConfiguration.loadConfiguration(new java.io.StringReader(String.valueOf(plan.get("content"))));
        var skills = new SkillService(new BehaviorRegistryImpl(), null);
        skills.loadSkills(yaml.getConfigurationSection("skills"));
        var target = mock(org.bukkit.entity.Zombie.class);
        var instance = mock(dev.helstera.api.instance.ModelInstance.class);
        when(instance.isValid()).thenReturn(true);
        when(instance.baseEntity()).thenReturn(java.util.Optional.of(target));
        var context = dev.helstera.api.behavior.BehaviorContext.continueExecution(
                instance, target, 1, 1, 0, "IDLE", null, target, List.of(target), null, null);
        when(target.isSneaking()).thenReturn(false);
        assertFalse(skills.castSkill("sneaking", context));
        verify(target, never()).damage(anyDouble(), any(org.bukkit.entity.Entity.class));
        when(target.isSneaking()).thenReturn(true);
        assertTrue(skills.castSkill("sneaking", context));
    }

    @Test
    void parameterlessConditionsRejectUnknownArgumentsAfterImport(@TempDir Path root) throws Exception {
        Path source = Files.createDirectories(root.resolve("Skills")).resolve("invalid-parameterless.yml");
        Files.writeString(source, """
                skills:
                  frozen_invalid:
                    TargetConditions: ['isFrozen{unknown=true}']
                    Skills: ['damage{amount=4} @Target']
                  projectile_invalid:
                    TargetConditions: ['projectileHasEnded{unknown=true}']
                    Skills: ['damage{amount=4} @Target']
                  living_invalid:
                    TargetConditions: ['isLiving{unknown=true}']
                    Skills: ['damage{amount=4} @Target']
                  baby_invalid:
                    TargetConditions: ['targetIsChild{unknown=true}']
                    Skills: ['damage{amount=4} @Target']
                  water_invalid:
                    TargetConditions: ['inWater{unknown=true}']
                    Skills: ['damage{amount=4} @Target']
                  sneaking_invalid:
                    TargetConditions: ['targetIsSneaking{unknown=true}']
                    Skills: ['damage{amount=4} @Target']
                  player_invalid:
                    TargetConditions: ['targetIsPlayer{unknown=true}']
                    Skills: ['damage{amount=4} @Target']
                  alive_negated_invalid:
                    TargetConditions: ['!targetIsAlive{unknown=true}']
                    Skills: ['damage{amount=4} @Target']
                """);
        var entries = new dev.helstera.migration.importer.MythicMobsImporter().convert(source);
        assertEquals(8, entries.stream().filter(e -> "WARN".equals(e.get("severity"))
                && String.valueOf(e.get("source-key")).contains("Conditions.unmapped")).count());
        var plan = entries.stream().filter(e -> "skills.yml".equals(e.get("target-file"))).findFirst().orElseThrow();
        var yaml = YamlConfiguration.loadConfiguration(new java.io.StringReader(String.valueOf(plan.get("content"))));
        var skills = new SkillService(new BehaviorRegistryImpl(), null);
        skills.loadSkills(yaml.getConfigurationSection("skills"));
        var target = mock(org.bukkit.entity.LivingEntity.class);
        when(target.isFrozen()).thenReturn(true);
        var context = dev.helstera.api.behavior.BehaviorContext.continueExecution(
                null, target, 1, 1, 0, "IDLE", null, target, List.of(target), null, null);
        assertFalse(skills.castSkill("frozen_invalid", context));
        assertFalse(skills.castSkill("projectile_invalid", context));
        assertFalse(skills.castSkill("living_invalid", context));
        assertFalse(skills.castSkill("baby_invalid", context));
        assertFalse(skills.castSkill("water_invalid", context));
        assertFalse(skills.castSkill("sneaking_invalid", context));
        assertFalse(skills.castSkill("player_invalid", context));
        assertFalse(skills.castSkill("alive_negated_invalid", context));
        verify(target, never()).damage(anyDouble(), any(org.bukkit.entity.Entity.class));
    }

    @Test
    void invalidVisibilityAndClimbingArgumentsRemainRejectedAfterImport(@TempDir Path root) throws Exception {
        Path source = Files.createDirectories(root.resolve("Skills")).resolve("visibility.yml");
        Files.writeString(source, """
                skills:
                  valid:
                    TargetConditions: [targetIsGlowing]
                    Skills: ['damage{amount=4} @Target']
                  invalid_glow:
                    TargetConditions: ['targetIsGlowing{unknown=true}']
                    Skills: ['damage{amount=4} @Target']
                  invalid_climb:
                    TargetConditions: ['climbing{unknown=true}']
                    Skills: ['damage{amount=4} @Target']
                  invalid_frozen:
                    TargetConditions: ['targetFrozen{unknown=true}']
                    Skills: ['damage{amount=4} @Target']
                  negated_invalid:
                    TargetConditions: ['!targetIsInvisible{unknown=true}']
                    Skills: ['damage{amount=4} @Target']
                """);
        var entries = new dev.helstera.migration.importer.MythicMobsImporter().convert(source);
        assertEquals(4, entries.stream().filter(e -> "WARN".equals(e.get("severity"))
                && String.valueOf(e.get("source-key")).contains("TargetConditions.unmapped")).count());
        var plan = entries.stream().filter(e -> "skills.yml".equals(e.get("target-file"))).findFirst().orElseThrow();
        var yaml = YamlConfiguration.loadConfiguration(new java.io.StringReader(String.valueOf(plan.get("content"))));
        assertEquals(List.of("targetIsGlowing{unknown=true}"), yaml.getStringList("skills.invalid_glow.target-conditions"));
        assertEquals(List.of("climbing{unknown=true}"), yaml.getStringList("skills.invalid_climb.target-conditions"));
        assertEquals(List.of("targetFrozen{unknown=true}"), yaml.getStringList("skills.invalid_frozen.target-conditions"));
        assertEquals(List.of("!targetIsInvisible{unknown=true}"), yaml.getStringList("skills.negated_invalid.target-conditions"));
        var skills = new SkillService(new BehaviorRegistryImpl(), null);
        skills.loadSkills(yaml.getConfigurationSection("skills"));
        var target = mock(org.bukkit.entity.Zombie.class);
        var world = mock(org.bukkit.World.class);
        var location = new org.bukkit.Location(world, 0, 64, 0);
        when(target.getWorld()).thenReturn(world);
        when(target.getLocation()).thenReturn(location);
        when(target.isGlowing()).thenReturn(true);
        var context = dev.helstera.api.behavior.BehaviorContext.continueExecution(
                null, target, 1, 1, 0, "IDLE", null, null, List.of(target), location, null);
        assertTrue(skills.castSkill("valid", context));
        assertFalse(skills.castSkill("invalid_glow", context));
        assertFalse(skills.castSkill("invalid_climb", context));
        assertFalse(skills.castSkill("invalid_frozen", context));
        assertFalse(skills.castSkill("negated_invalid", context));
        verify(target, times(1)).damage(eq(4.0), isNull(org.bukkit.entity.Entity.class));
    }

    @Test
    void invalidTimeAndWeatherAliasesRemainRejectedAfterImport(@TempDir Path root) throws Exception {
        Path source = Files.createDirectories(root.resolve("Skills")).resolve("time-weather.yml");
        Files.writeString(source, """
                skills:
                  valid:
                    TargetConditions: [morning, clear]
                    Skills: ['damage{amount=4} @Target']
                  invalid_time:
                    TargetConditions: ['morning{unknown=true}']
                    Skills: ['damage{amount=4} @Target']
                  invalid_weather:
                    TargetConditions: ['rain{unknown=true}']
                    Skills: ['damage{amount=4} @Target']
                  negated_invalid:
                    TargetConditions: ['!thunder{unknown=true}']
                    Skills: ['damage{amount=4} @Target']
                """);
        var entries = new dev.helstera.migration.importer.MythicMobsImporter().convert(source);
        assertEquals(3, entries.stream().filter(e -> "WARN".equals(e.get("severity"))
                && String.valueOf(e.get("source-key")).contains("TargetConditions.unmapped")).count());
        var plan = entries.stream().filter(e -> "skills.yml".equals(e.get("target-file"))).findFirst().orElseThrow();
        var yaml = YamlConfiguration.loadConfiguration(new java.io.StringReader(String.valueOf(plan.get("content"))));
        assertEquals(List.of("morning{unknown=true}"), yaml.getStringList("skills.invalid_time.target-conditions"));
        assertEquals(List.of("!thunder{unknown=true}"), yaml.getStringList("skills.negated_invalid.target-conditions"));
        var skills = new SkillService(new BehaviorRegistryImpl(), null);
        skills.loadSkills(yaml.getConfigurationSection("skills"));
        var world = mock(org.bukkit.World.class);
        when(world.getTime()).thenReturn(1000L);
        var location = new org.bukkit.Location(world, 0, 64, 0);
        var target = mock(org.bukkit.entity.LivingEntity.class);
        when(target.getWorld()).thenReturn(world);
        when(target.getLocation()).thenReturn(location);
        var context = dev.helstera.api.behavior.BehaviorContext.continueExecution(
                null, target, 1, 1, 0, "IDLE", null, null, List.of(target), location, null);
        assertTrue(skills.castSkill("valid", context));
        assertFalse(skills.castSkill("invalid_time", context));
        assertFalse(skills.castSkill("invalid_weather", context));
        assertFalse(skills.castSkill("negated_invalid", context));
        verify(target, times(1)).damage(eq(4.0), isNull(org.bukkit.entity.Entity.class));
    }

    @Test
    void malformedEnvironmentArgumentsFailClosedAfterSkillImport(@TempDir Path root) throws Exception {
        Path source = Files.createDirectories(root.resolve("Skills")).resolve("environment.yml");
        Files.writeString(source, """
                skills:
                  valid:
                    TargetConditions: ['world{world=arena}', 'time{value=day}', 'weather{type=clear}']
                    Skills: ['damage{amount=4} @Target']
                  invalid_world:
                    TargetConditions: ['world{world=arena;unknown=true}']
                    Skills: ['damage{amount=4} @Target']
                  invalid_time:
                    TargetConditions: ['time{value=day;value=night}']
                    Skills: ['damage{amount=4} @Target']
                  invalid_weather:
                    TargetConditions: ['weather{type=clear;unknown=true}']
                    Skills: ['damage{amount=4} @Target']
                  negated_invalid:
                    TargetConditions: ['!world{world=other;unknown=true}']
                    Skills: ['damage{amount=4} @Target']
                """);
        var entries = new dev.helstera.migration.importer.MythicMobsImporter().convert(source);
        assertEquals(4, entries.stream().filter(e -> "WARN".equals(e.get("severity"))
                && String.valueOf(e.get("source-key")).contains("TargetConditions.unmapped")).count());
        var plan = entries.stream().filter(e -> "skills.yml".equals(e.get("target-file"))).findFirst().orElseThrow();
        var yaml = YamlConfiguration.loadConfiguration(new java.io.StringReader(String.valueOf(plan.get("content"))));
        assertEquals(List.of("world{world=arena;unknown=true}"), yaml.getStringList("skills.invalid_world.target-conditions"));
        var skills = new SkillService(new BehaviorRegistryImpl(), null);
        skills.loadSkills(yaml.getConfigurationSection("skills"));
        var world = mock(org.bukkit.World.class);
        when(world.getName()).thenReturn("arena");
        when(world.getTime()).thenReturn(1000L);
        var location = new org.bukkit.Location(world, 0, 64, 0);
        var target = mock(org.bukkit.entity.LivingEntity.class);
        when(target.getWorld()).thenReturn(world);
        when(target.getLocation()).thenReturn(location);
        var context = dev.helstera.api.behavior.BehaviorContext.continueExecution(
                null, target, 1, 1, 0, "IDLE", null, null, List.of(target), location, null);
        assertTrue(skills.castSkill("valid", context));
        assertFalse(skills.castSkill("invalid_world", context));
        assertFalse(skills.castSkill("invalid_time", context));
        assertFalse(skills.castSkill("invalid_weather", context));
        assertFalse(skills.castSkill("negated_invalid", context));
        verify(target, times(1)).damage(eq(4.0), isNull(org.bukkit.entity.Entity.class));
    }

    @Test
    void importedBiomeSkillConditionsGateExecutionAndPreserveInvalidSource(@TempDir Path root) throws Exception {
        Path source = Files.createDirectories(root.resolve("Skills")).resolve("biome.yml");
        Files.writeString(source, """
                skills:
                  partial:
                    TargetConditions: ['?biome{e=false;b=desert, plains}']
                    Skills: ['damage{amount=4} @Target']
                  exact:
                    TargetConditions: ['?biome{e=true;b=desert, plains}']
                    Skills: ['damage{amount=4} @Target']
                  invalid:
                    TargetConditions: ['?biome{b=plains,}']
                    Skills: ['damage{amount=4} @Target']
                """);
        var entries = new dev.helstera.migration.importer.MythicMobsImporter().convert(source);
        String yaml = String.valueOf(entries.stream().filter(e -> e.containsKey("target-file"))
                .findFirst().orElseThrow().get("content"));
        var converted = YamlConfiguration.loadConfiguration(new java.io.StringReader(yaml));
        assertEquals(List.of("?biome{b=plains,}"), converted.getStringList("skills.invalid.target-conditions"));
        assertTrue(entries.stream().anyMatch(e -> "WARN".equals(e.get("severity"))
                && String.valueOf(e.get("source-key")).contains("TargetConditions.unmapped")));
        var registry = new BehaviorRegistryImpl();
        var skills = new SkillService(registry, null);
        skills.loadSkills(converted.getConfigurationSection("skills"));
        var world = mock(org.bukkit.World.class);
        var block = mock(org.bukkit.block.Block.class);
        when(world.getBlockAt(any(org.bukkit.Location.class))).thenReturn(block);
        when(block.getBiome()).thenReturn(org.bukkit.block.Biome.SUNFLOWER_PLAINS);
        var location = new org.bukkit.Location(world, 0, 64, 0);
        var target = mock(org.bukkit.entity.LivingEntity.class);
        when(target.getWorld()).thenReturn(world);
        when(target.getLocation()).thenReturn(location);
        var context = dev.helstera.api.behavior.BehaviorContext.continueExecution(
                null, target, 1, 1, 0, "IDLE", null, null, List.of(target), location, null);
        assertTrue(skills.castSkill("partial", context));
        assertFalse(skills.castSkill("exact", context));
        assertFalse(skills.castSkill("invalid", context));
        verify(target, times(1)).damage(eq(4.0), isNull(org.bukkit.entity.Entity.class));
    }

    @Test
    void malformedImportedEnvironmentConditionsRemainUnbound() {
        var registry = new BehaviorRegistryImpl();
        var skills = new SkillService(registry, null);
        for (String raw : List.of("?world{world=arena;unknown=true}",
                "?world{world=arena}{world=other}", "?time{value=day;value=night}")) {
            var mapped = dev.helstera.migration.importer.MmConditions.translate(raw);
            assertFalse(mapped.translated(), raw);
            assertEquals(raw, mapped.condition());
            assertNull(skills.bindCondition(mapped.condition()), raw);
        }
    }

    @Test
    void importedOriginLocationAliasesReachRuntimeAndRejectInvalidValues() {
        var world = mock(org.bukkit.World.class);
        var origin = new org.bukkit.Location(world, 10.25, 20.5, 30.75);
        var context = dev.helstera.api.behavior.BehaviorContext.execution(
                null, null, 1, -1, 0, "IDLE", null, null, List.of(), origin);
        var registry = new BehaviorRegistryImpl();
        var skills = new SkillService(registry, null);

        for (String source : List.of(
                "?originLocation{location=10,20,30}",
                "?originLocation{c=10.25,20.5,30.75;e=yes}",
                "?originLocation{x=10.25;y=20.5;z=30.75;e=1}")) {
            var mapped = dev.helstera.migration.importer.MmConditions.translate(source);
            assertTrue(mapped.translated(), source);
            String key = skills.bindCondition(mapped.condition());
            assertNotNull(key, source);
            assertTrue(registry.testCondition(key, context), source);
        }

        var mismatch = dev.helstera.migration.importer.MmConditions.translate(
                "?originLocation{l=999,999,999}");
        assertTrue(mismatch.translated());
        assertFalse(registry.testCondition(skills.bindCondition(mismatch.condition()), context));

        for (String source : List.of("?originLocation{loc=NaN,0,0}",
                "?originLocation{x=0;y=Infinity;z=0}",
                "?originLocation{loc=0,0,0;exact=maybe}")) {
            var mapped = dev.helstera.migration.importer.MmConditions.translate(source);
            assertFalse(mapped.translated(), source);
            assertEquals(source, mapped.condition());
            assertNull(skills.bindCondition(mapped.condition()), source);
        }
    }

    @Test
    void importedItemRechargingAliasesReachRuntimeAndFailClosed() {
        var player = mock(org.bukkit.entity.Player.class);
        when(player.getAttackCooldown()).thenReturn(0.75f);
        var registry = new BehaviorRegistryImpl();
        var skills = new SkillService(registry, null);
        var playerContext = dev.helstera.api.behavior.BehaviorContext.of(
                null, player, 1, -1, 0, "IDLE");
        var importedKeys = new java.util.ArrayList<String>();

        for (String source : List.of("?itemRecharging", "?recharging")) {
            var mapped = dev.helstera.migration.importer.MmConditions.translate(source);
            assertTrue(mapped.translated(), source);
            assertEquals("item-recharging", mapped.condition(), source);
            String key = skills.bindCondition(mapped.condition());
            assertNotNull(key, source);
            importedKeys.add(key);
            assertTrue(registry.testCondition(key, playerContext), source);
        }

        when(player.getAttackCooldown()).thenReturn(1.0f);
        for (String key : importedKeys) assertFalse(registry.testCondition(key, playerContext));

        var zombie = mock(org.bukkit.entity.Zombie.class);
        var nonHumanContext = dev.helstera.api.behavior.BehaviorContext.of(
                null, zombie, 1, -1, 0, "IDLE");
        for (String key : importedKeys) assertFalse(registry.testCondition(key, nonHumanContext));
        var missingContext = dev.helstera.api.behavior.BehaviorContext.of(
                null, (org.bukkit.entity.LivingEntity) null, 1, -1, 0, "IDLE");
        for (String key : importedKeys) assertFalse(registry.testCondition(key, missingContext));
    }

    @Test
    @DisplayName("imported PlayerKills comparisons reach the instance counter and fail closed")
    void importedPlayerKillsReachesRuntimeAndFailsClosed() {
        var instance = mock(dev.helstera.api.instance.ModelInstance.class);
        var player = mock(org.bukkit.entity.Player.class);
        when(instance.isValid()).thenReturn(true);
        when(instance.instanceId()).thenReturn(73);
        when(instance.baseEntity()).thenReturn(java.util.Optional.of(player));
        when(player.isValid()).thenReturn(true);
        when(player.isDead()).thenReturn(false);

        var counter = new dev.helstera.ai.skill.PlayerKillCount();
        counter.record(73);
        SkillExtras.playerKillCount(counter);
        try {
            var registry = new BehaviorRegistryImpl();
            var skills = new SkillService(registry, null);
            var context = dev.helstera.api.behavior.BehaviorContext.of(
                    instance, player, 1.0, -1, 0, "on-kill-player");

            var primary = dev.helstera.migration.importer.MmConditions.translate(
                    "?playerKills{k=>=1}");
            var range = dev.helstera.migration.importer.MmConditions.translate(
                    "?playerKills{k=1to1}");
            var negative = dev.helstera.migration.importer.MmConditions.translate(
                    "?playerKills{k=>1}");
            var invalid = dev.helstera.migration.importer.MmConditions.translate(
                    "?playerKills{k=invalid}");
            assertTrue(primary.translated());
            assertTrue(range.translated());
            assertTrue(negative.translated());
            assertFalse(invalid.translated());

            String primaryKey = skills.bindCondition(primary.condition());
            String rangeKey = skills.bindCondition(range.condition());
            String negativeKey = skills.bindCondition(negative.condition());
            assertNotNull(primaryKey);
            assertNotNull(rangeKey);
            assertNotNull(negativeKey);
            assertTrue(registry.testCondition(primaryKey, context));
            assertTrue(registry.testCondition(rangeKey, context));
            assertFalse(registry.testCondition(negativeKey, context));
            assertNull(skills.bindCondition(invalid.condition()));

            counter.record(73);
            assertTrue(registry.testCondition(primaryKey, context));
            assertFalse(registry.testCondition(rangeKey, context));
            assertTrue(registry.testCondition(negativeKey, context));
        } finally {
            SkillExtras.playerKillCount(null);
        }
    }

    @Test
    void importedTargetHealthPercentUsesMultiplierAndFailsClosedWithoutTarget() {
        var target = mock(org.bukkit.entity.LivingEntity.class);
        when(target.getHealth()).thenReturn(10.0);
        when(target.getMaxHealth()).thenReturn(20.0);
        var context = dev.helstera.api.behavior.BehaviorContext.continueExecution(
                null, target, 1, 1, 0, "IDLE", null, null, List.of(target), null, null);
        var missing = dev.helstera.api.behavior.BehaviorContext.continueExecution(
                null, null, 1, 1, 0, "IDLE", null, null, List.of(), null, null);
        var registry = new BehaviorRegistryImpl();
        var skills = new SkillService(registry, null);
        for (String source : List.of("?targetHealthPercent{>=0.5}", "?targetHealthPercent{<=50%}",
                "?targetHealth{=10}")) {
            var mapped = dev.helstera.migration.importer.MmConditions.translate(source);
            assertTrue(mapped.translated(), source);
            String bound = skills.bindCondition(mapped.condition());
            assertTrue(registry.testCondition(bound, context), source);
            assertFalse(registry.testCondition(bound, missing), source);
        }
        for (String source : List.of("?targetHealthPercent{<0.5}", "?targetHealthPercent{>50%}")) {
            var mapped = dev.helstera.migration.importer.MmConditions.translate(source);
            assertTrue(mapped.translated(), source);
            assertFalse(registry.testCondition(skills.bindCondition(mapped.condition()), context), source);
        }
    }

    @Test
    @DisplayName("imported isChild aliases evaluate caster-parent relations, not age")
    void importedIsChildUsesParentRelationInsteadOfAge() {
        var parent = mock(org.bukkit.entity.Entity.class);
        var child = mock(org.bukkit.entity.Ageable.class);
        var instance = mock(dev.helstera.api.instance.ModelInstance.class);
        var data = mock(org.bukkit.persistence.PersistentDataContainer.class);
        var parentId = java.util.UUID.randomUUID();
        when(instance.isValid()).thenReturn(true);
        when(instance.baseEntity()).thenReturn(java.util.Optional.of(parent));
        when(parent.isValid()).thenReturn(true);
        when(parent.isDead()).thenReturn(false);
        when(parent.getUniqueId()).thenReturn(parentId);
        when(child.isValid()).thenReturn(true);
        when(child.isDead()).thenReturn(false);
        when(child.getUniqueId()).thenReturn(java.util.UUID.randomUUID());
        when(child.isAdult()).thenReturn(true);
        when(child.getPersistentDataContainer()).thenReturn(data);
        when(data.get(any(org.bukkit.NamespacedKey.class),
                eq(org.bukkit.persistence.PersistentDataType.STRING))).thenReturn(parentId.toString());

        var context = dev.helstera.api.behavior.BehaviorContext.of(instance,
                (org.bukkit.entity.LivingEntity) child,
                1, -1, 0, "IDLE");
        var registry = new BehaviorRegistryImpl();
        var skills = new SkillService(registry, null);
        for (String source : List.of("?isChild", "?child")) {
            var mapped = dev.helstera.migration.importer.MmConditions.translate(source);
            assertTrue(mapped.translated(), source);
            assertEquals("is-child", mapped.condition(), source);
            assertTrue(registry.testCondition(skills.bindCondition(mapped.condition()), context), source);
        }
        assertFalse(registry.testCondition(skills.bindCondition("is-baby"), context));
    }

    @Test
    @DisplayName("imported angry and pollinated aliases reach their runtime state consumers")
    void importedAngryAndPollinatedConditionsReachRuntime() {
        var bee = mock(org.bukkit.entity.Bee.class);
        var wolf = mock(org.bukkit.entity.Wolf.class);
        var vex = mock(org.bukkit.entity.Vex.class);
        when(bee.getAnger()).thenReturn(30, 0);
        when(bee.hasNectar()).thenReturn(true, false);
        when(wolf.isAngry()).thenReturn(true, false);
        when(vex.isCharging()).thenReturn(true, false);
        var registry = new BehaviorRegistryImpl();
        var skills = new SkillService(registry, null);

        assertImportedState(skills, registry, "?angry", bee, true);
        assertImportedState(skills, registry, "?angry", bee, false);
        assertImportedState(skills, registry, "?isAngry", wolf, true);
        assertImportedState(skills, registry, "?isAngry", wolf, false);
        assertImportedState(skills, registry, "?angry", vex, true);
        assertImportedState(skills, registry, "?angry", vex, false);
        assertImportedState(skills, registry, "?pollinated", bee, true);
        assertImportedState(skills, registry, "?hasNectar", bee, false);
        assertNotNull(skills.bindCondition("isAngry"));
        assertNotNull(skills.bindCondition("is-pollinated"));
        assertNotNull(skills.bindCondition("hasNectar"));

        var unsupported = dev.helstera.migration.importer.MmConditions.translate("?angry");
        var piglin = mock(org.bukkit.entity.PigZombie.class);
        var piglinContext = dev.helstera.api.behavior.BehaviorContext.continueExecution(
                null, piglin, 1, -1, 0, "IDLE", null, null, List.of(piglin), null, null);
        assertFalse(registry.testCondition(skills.bindCondition(unsupported.condition()), piglinContext));
        var missing = dev.helstera.api.behavior.BehaviorContext.continueExecution(
                null, null, 1, -1, 0, "IDLE", null, null, List.of(), null, null);
        assertFalse(registry.testCondition(skills.bindCondition(unsupported.condition()), missing));
    }

    @Test
    @DisplayName("imported sheared aliases reach Sheep, Bogged and Snow Golem state")
    void importedShearedConditionsReachRuntimeForSupportedEntities() {
        var sheep = mock(org.bukkit.entity.Sheep.class);
        var bogged = mock(org.bukkit.entity.Bogged.class);
        var snowman = mock(org.bukkit.entity.Snowman.class);
        var registry = new BehaviorRegistryImpl();
        var skills = new SkillService(registry, null);

        when(sheep.isSheared()).thenReturn(true);
        assertImportedState(skills, registry, "?isSheared", sheep, true);
        assertImportedState(skills, registry, "?targetIsSheared", sheep, true);
        when(sheep.isSheared()).thenReturn(false);
        assertImportedState(skills, registry, "?isSheared", sheep, false);
        assertImportedState(skills, registry, "?targetIsSheared", sheep, false);

        when(bogged.isSheared()).thenReturn(true);
        assertImportedState(skills, registry, "?isSheared", bogged, true);
        assertImportedState(skills, registry, "?targetIsSheared", bogged, true);
        when(bogged.isSheared()).thenReturn(false);
        assertImportedState(skills, registry, "?isSheared", bogged, false);
        assertImportedState(skills, registry, "?targetIsSheared", bogged, false);

        when(snowman.isDerp()).thenReturn(true);
        assertImportedState(skills, registry, "?isSheared", snowman, true);
        assertImportedState(skills, registry, "?targetIsSheared", snowman, true);
        when(snowman.isDerp()).thenReturn(false);
        assertImportedState(skills, registry, "?isSheared", snowman, false);
        assertImportedState(skills, registry, "?targetIsSheared", snowman, false);

        var unsupported = mock(org.bukkit.entity.Zombie.class);
        assertImportedState(skills, registry, "?isSheared", unsupported, false);
        assertImportedState(skills, registry, "?targetIsSheared", unsupported, false);
        var missing = dev.helstera.api.behavior.BehaviorContext.continueExecution(
                null, (org.bukkit.entity.LivingEntity) null, 1, -1, 0,
                "IDLE", null, null, List.of(), null, null);
        for (String source : List.of("?isSheared", "?targetIsSheared")) {
            var mapped = dev.helstera.migration.importer.MmConditions.translate(source);
            assertTrue(mapped.translated(), source);
            assertFalse(registry.testCondition(skills.bindCondition(mapped.condition()), missing), source);
        }
    }

    @Test
    @DisplayName("imported inCombat checks a live entity target and rejects absent targets")
    void importedInCombatUsesActiveRuntimeTarget() {
        var mob = mock(org.bukkit.entity.Mob.class);
        var target = mock(org.bukkit.entity.LivingEntity.class);
        when(target.isValid()).thenReturn(true);
        when(target.isDead()).thenReturn(false);
        when(mob.getTarget()).thenReturn(target);
        var registry = new BehaviorRegistryImpl();
        var skills = new SkillService(registry, null);
        var mapped = dev.helstera.migration.importer.MmConditions.translate("?inCombat");
        assertTrue(mapped.translated());
        var context = dev.helstera.api.behavior.BehaviorContext.continueExecution(
                null, mob, 1, -1, 0, "IDLE", null, null, List.of(mob), null, null);
        String condition = skills.bindCondition(mapped.condition());

        assertTrue(registry.testCondition(condition, context));
        when(mob.getTarget()).thenReturn(null);
        assertFalse(registry.testCondition(condition, context));
        when(mob.getTarget()).thenReturn(target);
        when(target.isDead()).thenReturn(true);
        assertFalse(registry.testCondition(condition, context));
        when(target.isDead()).thenReturn(false);
        when(target.isValid()).thenReturn(false);
        assertFalse(registry.testCondition(condition, context));

        var unsupported = mock(org.bukkit.entity.Player.class);
        var unsupportedContext = dev.helstera.api.behavior.BehaviorContext.continueExecution(
                null, unsupported, 1, -1, 0, "IDLE", null, null,
                List.of(unsupported), null, null);
        assertFalse(registry.testCondition(condition, unsupportedContext));
        var missing = dev.helstera.api.behavior.BehaviorContext.continueExecution(
                null, (org.bukkit.entity.LivingEntity) null, 1, -1, 0,
                "IDLE", null, null, List.of(), null, null);
        assertFalse(registry.testCondition(condition, missing));
    }

    private static void assertImportedState(SkillService skills, BehaviorRegistryImpl registry,
                                            String source, org.bukkit.entity.LivingEntity entity,
                                            boolean expected) {
        var mapped = dev.helstera.migration.importer.MmConditions.translate(source);
        assertTrue(mapped.translated(), source);
        var context = dev.helstera.api.behavior.BehaviorContext.continueExecution(
                null, entity, 1, -1, 0, "IDLE", null, null, List.of(entity), null, null);
        assertEquals(expected, registry.testCondition(skills.bindCondition(mapped.condition()), context), source);
    }

    @Test
    void importedDistanceAndLightComparisonsPreserveInclusiveAndStrictBoundaries() {
        var world = mock(org.bukkit.World.class);
        var block = mock(org.bukkit.block.Block.class);
        when(world.getBlockAt(any(org.bukkit.Location.class))).thenReturn(block);
        when(block.getLightLevel()).thenReturn((byte) 8);
        var location = new org.bukkit.Location(world, 0, 64, 0);
        var target = mock(org.bukkit.entity.LivingEntity.class);
        var context = dev.helstera.api.behavior.BehaviorContext.continueExecution(
                null, target, 1, 5, 0, "IDLE", null, null, List.of(target), location, null);
        var registry = new BehaviorRegistryImpl();
        var skills = new SkillService(registry, null);
        for (String raw : List.of("?targetDistance{<=5}", "?targetDistance{>=5}",
                "?lightlevel{<=8}", "?lightlevel{>=8}")) {
            var mapped = dev.helstera.migration.importer.MmConditions.translate(raw);
            assertTrue(mapped.translated(), raw);
            assertTrue(registry.testCondition(skills.bindCondition(mapped.condition()), context), raw);
        }
        for (String raw : List.of("?targetDistance{<5}", "?targetDistance{>5}",
                "?lightlevel{<8}", "?lightlevel{>8}")) {
            var mapped = dev.helstera.migration.importer.MmConditions.translate(raw);
            assertTrue(mapped.translated(), raw);
            assertFalse(registry.testCondition(skills.bindCondition(mapped.condition()), context), raw);
        }
    }

    @Test
    void importedHealthConditionsUseAbsoluteValuesAndPreserveStrictBoundaries() {
        var target = mock(org.bukkit.entity.LivingEntity.class);
        when(target.getHealth()).thenReturn(10.0);
        when(target.getMaxHealth()).thenReturn(20.0);
        when(target.getAbsorptionAmount()).thenReturn(2.0);
        var context = dev.helstera.api.behavior.BehaviorContext.continueExecution(
                null, target, 1, 1, 0, "IDLE", null, null, List.of(target), null, null);
        var registry = new BehaviorRegistryImpl();
        var skills = new SkillService(registry, null);
        for (String raw : List.of("?health{h=10}", "?health{h=<=10}",
                "?health{h=>10;ia=true}", "?health{>10;ia=true}", "?health{10-12}",
                "?health{12to12;ia=true}", "?healthpercent{p=0.25to0.5}", "?healthpercent{p=0.5}",
                "?healthpercent{p=50%}")) {
            var mapped = dev.helstera.migration.importer.MmConditions.translate(raw);
            assertTrue(mapped.translated(), raw);
            assertTrue(registry.testCondition(skills.bindCondition(mapped.condition()), context), raw);
        }
        for (String raw : List.of("?health{h=<10}", "?health{h=>10}",
                "?healthpercent{p=<50%}", "?health{11to20}", "?healthpercent{p=51%to70%}")) {
            var mapped = dev.helstera.migration.importer.MmConditions.translate(raw);
            assertTrue(mapped.translated(), raw);
            assertFalse(registry.testCondition(skills.bindCondition(mapped.condition()), context), raw);
        }
        var missing = dev.helstera.api.behavior.BehaviorContext.continueExecution(
                null, null, 1, 1, 0, "IDLE", null, null, List.of(), null, null);
        assertFalse(registry.testCondition(skills.bindCondition("health =10"), missing));
    }

    @Test
    void reorderedBiomeArgumentsReachRuntimeWithTheirOriginalMeaning() {
        var world = mock(org.bukkit.World.class);
        var block = mock(org.bukkit.block.Block.class);
        when(world.getBlockAt(any(org.bukkit.Location.class))).thenReturn(block);
        when(block.getBiome()).thenReturn(org.bukkit.block.Biome.SUNFLOWER_PLAINS);
        var location = new org.bukkit.Location(world, 0, 64, 0);
        var context = dev.helstera.api.behavior.BehaviorContext.continueExecution(
                null, null, 1, 1, 0, "IDLE", null, null, List.of(), location, null);
        var registry = new BehaviorRegistryImpl();
        var skills = new SkillService(registry, null);
        var partial = dev.helstera.migration.importer.MmConditions.translate(
                "?biome{e=false;b=desert, plains}");
        var exact = dev.helstera.migration.importer.MmConditions.translate(
                "?biome{e=true;b=desert, plains}");
        assertTrue(partial.translated());
        assertTrue(exact.translated());
        assertTrue(registry.testCondition(skills.bindCondition(partial.condition()), context));
        assertFalse(registry.testCondition(skills.bindCondition(exact.condition()), context));
    }

    @Test
    @DisplayName("all canonical migration conditions bind through the runtime SkillService")
    void everyTranslatedConditionBindsAtRuntime() throws IOException {
        Set<String> conditions = translatedConditions();
        assertFalse(conditions.isEmpty(), "migration condition source audit found no expected translations");

        var registry = new BehaviorRegistryImpl();
        var service = new SkillService(registry, null);
        List<String> failures = new ArrayList<>();
        for (String condition : conditions) {
            String key = service.bindCondition(condition);
            if (key == null || !registry.hasCondition(key)) {
                failures.add(condition + " -> " + key);
            }
        }

        assertTrue(failures.isEmpty(),
                "migration produced canonical conditions that runtime cannot bind: " + failures);
        assertNotNull(service.bindCondition("target-has-line-of-sight"));
    }

    @Test
    @DisplayName("all canonical migration mechanics bind through the runtime SkillService")
    void everyTranslatedActionBindsAtRuntime() throws IOException {
        Set<String> actions = translatedActions();
        assertFalse(actions.isEmpty(), "migration action source audit found no expected actions");

        var registry = new BehaviorRegistryImpl();
        var service = new SkillService(registry, null);
        YamlConfiguration definitions = new YamlConfiguration();
        ConfigurationSection skills = definitions.createSection("skills");
        for (String action : actions) {
            if (!action.startsWith("random-skill ")) continue;
            for (String candidate : action.substring("random-skill ".length()).split("[,;|]")) {
                String name = candidate.trim();
                if (!name.isBlank()) skills.createSection(name);
            }
        }
        service.loadSkills(skills);
        List<String> failures = new ArrayList<>();
        for (String action : actions) {
            String key = service.bindAction(action);
            if (key == null || !registry.hasAction(key)) failures.add(action + " -> " + key);
        }

        assertTrue(failures.isEmpty(),
                "migration produced mechanics that runtime cannot bind: " + failures);
    }
}
