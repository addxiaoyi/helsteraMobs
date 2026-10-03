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

    /**
     * 取出 contents 之后的那一段作为命名空间。
     *
     * <p>ItemAdder 的布局固定为 {@code contents/<namespace>/<category>/<file>.yml}，
     * 但 category 目录的深度不固定（有的直接 contents 下、有的多一层），
     * 因此从文件名往上找 contents，取它的下一段，而不是依赖父目录层级。</p>
     *
     * <p>找不到 contents 时回退到父目录名，保证不抛异常。</p>
     */
    static String namespaceOf(Path file) {
        Path cur = file;
        while (cur != null) {
            Path parent = cur.getParent();
            if (parent != null && "contents".equals(parent.getFileName().toString())) {
                return cur.getFileName().toString();
            }
            cur = parent;
        }
        Path parent = file.getParent();
        return parent != null ? parent.getFileName().toString() : "unknown";
    }

    @Override
    public List<Map<String, Object>> convert(Path sourceFile) {
        List<Map<String, Object>> entries = new ArrayList<>();
        YamlConfiguration src = ImporterUtil.load(sourceFile);
        ConfigurationSection items = src.getConfigurationSection("items");
        if (items == null) return entries;

        // 命名空间是 contents/<namespace>/<category>/x.yml 里夹在 contents 之后的那一段，
        // 不是文件的直接父目录——后者是 items/blocks 这类分类目录。
        // 此前误用 getParent().getFileName()，把所有物品的命名空间塌缩成 "items"，
        // 不同资源包的同名物品会得到相同 ID 而互相覆盖。
        String pack = namespaceOf(sourceFile);
        YamlConfiguration target = new YamlConfiguration();
        int n = 0;
        for (String itemKey : items.getKeys(false)) {
            ConfigurationSection item = items.getConfigurationSection(itemKey);
            if (item == null) continue;
            // ItemAdder 的物品 ID 普遍带点号（swords.ruby_sword），而 Bukkit 的
            // YAML 解析会先按点号拆成嵌套节，getKeys(false) 只能拿到 "swords"，
            // 据此拼出的 ID 是错的。因此优先读物品自带的 id 字段——那才是
            // ItemAdder 真正的命名空间 ID。
            String declared = item.getString("id");
            String itemId = declared != null && !declared.isEmpty()
                    ? declared
                    : pack + ":" + itemKey;
            String safe = itemId.replace('.', '_').replace(':', '_');
            target.set("items." + safe + ".source", "itemadder");
            target.set("items." + safe + ".id", itemId);
            String display = item.getString("display_name");
            if (display != null) {
                target.set("items." + safe + ".display-name", display);
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
