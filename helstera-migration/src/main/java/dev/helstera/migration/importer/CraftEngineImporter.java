package dev.helstera.migration.importer;

import dev.helstera.api.migration.MigrationImporter;
import org.bukkit.configuration.file.YamlConfiguration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * CraftEngine 导入器：扫描其资源目录，把自定义物品/方块 ID 索引为 helstera 命名空间引用。
 */
public final class CraftEngineImporter implements MigrationImporter {

    @Override
    public String sourceId() {
        return "craftengine";
    }

    @Override
    public String displayName() {
        return "CraftEngine";
    }

    @Override
    public List<Path> scan(Path pluginDataFolder) {
        List<Path> out = new ArrayList<>();
        Path resources = pluginDataFolder.resolve("resources");
        if (!Files.isDirectory(resources)) return out;
        try (var s = Files.walk(resources, 4)) {
            s.filter(p -> p.toString().endsWith(".yml")).forEach(out::add);
        } catch (Exception ignored) {
        }
        return out;
    }

    @Override
    public List<Map<String, Object>> convert(Path sourceFile) {
        List<Map<String, Object>> entries = new ArrayList<>();
        YamlConfiguration src = ImporterUtil.load(sourceFile);
        int n = 0;
        for (String key : src.getKeys(true)) {
            if (src.isConfigurationSection(key)) continue;
            n++;
        }
        if (n == 0) return entries;
        String ns = sourceFile.getParent().getFileName().toString();
        entries.add(ImporterUtil.entry(sourceFile.toString(), "resources." + ns,
                "attachments/items.yml#craftengine." + ns, n + " 项",
                "mapped", "CraftEngine 命名空间 " + ns + " 已索引（ce: -> craftengine:）"));
        return entries;
    }
}
