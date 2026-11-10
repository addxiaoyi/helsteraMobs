package dev.helstera.plugin;

import dev.helstera.ai.BehaviorRegistryImpl;
import dev.helstera.ai.skill.SkillExtras;
import dev.helstera.ai.skill.SkillService;
import dev.helstera.api.behavior.BehaviorContext;
import dev.helstera.api.behavior.ManagedMobIdentity;
import dev.helstera.migration.importer.MmConditions;
import dev.helstera.migration.importer.MythicMobsImporter;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.LivingEntity;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TemplateTypeMigrationRuntimeContractTest {

    @AfterEach
    void clearRuntimeHooks() {
        SkillExtras.resetRuntimeHooks();
    }

    @Test
    void importedMobTemplateLineageDrivesBoundConditions(@TempDir Path root) throws Exception {
        String suffix = "contract_" + java.util.UUID.randomUUID().toString()
                .replace("-", "").substring(0, 8);
        String parentSourceId = "TemplateTypeParent_" + suffix;
        String childSourceId = "TemplateTypeChild_" + suffix;
        Path source = root.resolve("Mobs/template-type.yml");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                mobs:
                  %1$s:
                    Type: ZOMBIE
                    Template: TemplateTypeAncestor_%2$s
                  %3$s:
                    Type: ZOMBIE
                    Template: %1$s
                """.formatted(parentSourceId, suffix, childSourceId));

        Map<String, YamlConfiguration> importedConfigs = new LinkedHashMap<>();
        Map<String, String> targetIds = new LinkedHashMap<>();
        for (Map<String, Object> entry : new MythicMobsImporter().convert(source, root)) {
            if (!"mapped".equals(entry.get("status"))
                    || !(entry.get("target-file") instanceof String targetFile)
                    || !targetFile.startsWith("mobs/")) continue;
            YamlConfiguration config = YamlConfiguration.loadConfiguration(
                    new java.io.StringReader(String.valueOf(entry.get("content"))));
            String targetId = String.valueOf(entry.get("target-mob-id"));
            importedConfigs.put(targetId, config);
            targetIds.put(String.valueOf(entry.get("source-mob-id")), targetId);
        }

        String parentId = targetIds.get(parentSourceId);
        String childId = targetIds.get(childSourceId);
        String ancestorId = "mythic_templatetypeancestor_" + suffix.toLowerCase(Locale.ROOT);
        assertNotNull(parentId);
        assertNotNull(childId);
        assertEquals(List.of(parentId), importedConfigs.get(childId).getStringList("mob.templates"));
        assertEquals(List.of(ancestorId), importedConfigs.get(parentId).getStringList("mob.templates"));

        SkillExtras.mobTemplateLookup(new MobTemplateIndex(importedConfigs::get));
        BehaviorRegistryImpl registry = new BehaviorRegistryImpl();
        SkillService skills = new SkillService(registry, null);

        var direct = MmConditions.translate("?TemplateType{t=" + parentSourceId + "}");
        var transitive = MmConditions.translate("?InstanceOf{template=TemplateTypeAncestor_" + suffix + "}");
        var unrelated = MmConditions.translate("?Template{template=unrelated}");
        var malformed = MmConditions.translate("?TemplateType{unknown=" + parentSourceId + "}");
        assertTrue(direct.translated());
        assertTrue(transitive.translated());
        assertTrue(unrelated.translated());
        assertFalse(malformed.translated());

        LivingEntity child = managedEntity(childId);
        BehaviorContext childContext = BehaviorContext.of(null, child, 1.0, -1, 0, "IDLE");
        assertTrue(registry.testCondition(skills.bindCondition(direct.condition()), childContext));
        assertTrue(registry.testCondition(skills.bindCondition(transitive.condition()), childContext));
        assertFalse(registry.testCondition(skills.bindCondition(unrelated.condition()), childContext));

        LivingEntity missingConfig = managedEntity("mythic_missing_config");
        assertFalse(registry.testCondition(skills.bindCondition(direct.condition()),
                BehaviorContext.of(null, missingConfig, 1.0, -1, 0, "IDLE")));
        LivingEntity unmanaged = mock(LivingEntity.class);
        assertFalse(registry.testCondition(skills.bindCondition(direct.condition()),
                BehaviorContext.of(null, unmanaged, 1.0, -1, 0, "IDLE")));
    }

    private static LivingEntity managedEntity(String mobId) {
        LivingEntity entity = mock(LivingEntity.class);
        PersistentDataContainer data = mock(PersistentDataContainer.class);
        when(entity.getPersistentDataContainer()).thenReturn(data);
        when(data.get(any(NamespacedKey.class), eq(PersistentDataType.STRING))).thenReturn(mobId);
        assertEquals(mobId, ManagedMobIdentity.mobId(entity));
        return entity;
    }
}
