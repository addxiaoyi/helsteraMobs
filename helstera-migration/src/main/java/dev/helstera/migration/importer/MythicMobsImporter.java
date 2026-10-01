package dev.helstera.migration.importer;

import dev.helstera.api.migration.MigrationImporter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * MythicMobs 导入器：扫描 Mobs/*.yml，把每个神话生物映射为 helstera 生物定义。
 * Type/Health/Damage/Display 等映射；Skills/Options 等进入 unsupported 节点。
 */
public final class MythicMobsImporter implements MigrationImporter {

    @Override
    public String sourceId() {
        return "mythicmobs";
    }

    @Override
    public String displayName() {
        return "MythicMobs";
    }

    @Override
    public List<Path> scan(Path pluginDataFolder) {
        Path mobs = pluginDataFolder.resolve("Mobs");
        if (!Files.isDirectory(mobs)) return List.of();
        List<Path> out = new ArrayList<>();
        try (var s = Files.walk(mobs, 3)) {
            s.filter(p -> p.toString().endsWith(".yml")).forEach(out::add);
        } catch (Exception ignored) {
        }
        return out;
    }

    @Override
    public List<Map<String, Object>> convert(Path sourceFile) {
        List<Map<String, Object>> entries = new ArrayList<>();
        YamlConfiguration src = ImporterUtil.load(sourceFile);
        ConfigurationSection mobs = src.getConfigurationSection("mobs");
        if (mobs == null) return entries;

        for (String mobKey : mobs.getKeys(false)) {
            ConfigurationSection mob = mobs.getConfigurationSection(mobKey);
            if (mob == null) continue;
            String targetId = "mythic_" + mobKey.toLowerCase().replaceAll("[^a-z0-9_]", "_");
            YamlConfiguration target = new YamlConfiguration();
            target.set("schema-version", 1);
            target.set("id", targetId);
            ImporterUtil.copySection(mob, target, "mob", entries, sourceFile.toString());
            target.set("mob.ai-profile", "default");

            // 写入计划作为 content 字段（apply 阶段落盘）
            entries.add(Map.of(
                    "target-file", "mobs/" + targetId + ".yml",
                    "content", target.saveToString(),
                    "status", "mapped",
                    "note", "MythicMobs 生物 " + mobKey + " 已转换（Skills 需在 helstera 中重建）"));
        }
        return entries;
    }
}
