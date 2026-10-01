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
 * ItemAdder 导入器：扫描 contents 目录的 items 配置，映射为 helstera 挂接物品引用表。
 */
public final class ItemAdderImporter implements MigrationImporter {

    @Override
    public String sourceId() {
        return "itemadder";
    }

    @Override
    public String displayName() {
        return "ItemAdder";
    }

    @Override
    public List<Path> scan(Path pluginDataFolder) {
        List<Path> out = new ArrayList<>();
        Path contents = pluginDataFolder.resolve("contents");
        if (!Files.isDirectory(contents)) return out;
        try (var s = Files.walk(contents, 4)) {
            s.filter(p -> p.toString().endsWith(".yml")).forEach(out::add);
        } catch (Exception ignored) {
        }
        return out;
    }

    @Override
    public List<Map<String, Object>> convert(Path sourceFile) {
        List<Map<String, Object>> entries = new ArrayList<>();
        YamlConfiguration src = ImporterUtil.load(sourceFile);
        ConfigurationSection items = src.getConfigurationSection("items");
        if (items == null) return entries;

        String pack = sourceFile.getParent().getFileName().toString();
        YamlConfiguration target = new YamlConfiguration();
        int n = 0;
        for (String itemKey : items.getKeys(false)) {
            ConfigurationSection item = items.getConfigurationSection(itemKey);
            if (item == null) continue;
            String itemId = pack + ":" + itemKey;
            target.set("items." + itemKey.replace('.', '_') + ".source", "itemadder");
            target.set("items." + itemKey.replace('.', '_') + ".id", itemId);
            String display = item.getString("display_name");
            if (display != null) {
                target.set("items." + itemKey.replace('.', '_') + ".display-name", display);
            }
            n++;
            entries.add(ImporterUtil.entry(sourceFile.toString(), "items." + itemKey,
                    "attachments/items.yml#items." + itemKey, itemId, "mapped", "命名空间映射完成"));
        }
        if (n > 0) {
            entries.add(Map.of(
                    "target-file", "attachments/items.yml",
                    "content", target.saveToString(),
                    "status", "mapped",
                    "note", "ItemAdder 物品 " + n + " 项已索引"));
        }
        return entries;
    }
}
