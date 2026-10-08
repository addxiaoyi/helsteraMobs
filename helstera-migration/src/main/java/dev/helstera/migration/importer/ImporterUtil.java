package dev.helstera.migration.importer;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 公共导入器工具：扫描 YML、读取节、构建条目。
 */
public final class ImporterUtil {

    private ImporterUtil() {
    }

    /** 找到 pluginDataFolder 及其子目录中的所有 .yml 文件（限深 4）。 */
    public static List<Path> findYml(Path pluginDataFolder) {
        List<Path> out = new ArrayList<>();
        if (!Files.isDirectory(pluginDataFolder)) return out;
        try (Stream<Path> s = Files.walk(pluginDataFolder, 4)) {
            s.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".yml"))
                    .forEach(out::add);
        } catch (IOException ignored) {
        }
        return out;
    }

    public static YamlConfiguration load(Path file) {
        try {
            return YamlConfiguration.loadConfiguration(Files.newBufferedReader(file, StandardCharsets.UTF_8));
        } catch (Exception e) {
            return new YamlConfiguration();
        }
    }

    /** 构建一条映射条目。 */
    public static Map<String, Object> entry(String sourceFile, String sourceKey, String targetKey,
                                            Object value, String status, String note) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("source-file", sourceFile);
        e.put("source-key", sourceKey);
        e.put("target-key", targetKey);
        e.put("value", String.valueOf(value));
        e.put("status", status);
        e.put("note", note == null ? "" : note);
        return e;
    }

    /** 将一个节的字段拷贝到目标节，未映射字段进入 unsupported 子节。 */
    public static void copySection(ConfigurationSection src, YamlConfiguration target, String targetPath,
                                   List<Map<String, Object>> entryCollector, String sourceFile) {
        for (String key : src.getKeys(true)) {
            if (src.isConfigurationSection(key)) continue;
            String full = src.getCurrentPath() + "." + key;
            Object val = src.get(key);
            // 已知字段映射
            String known = knownField(key);
            if (known != null) {
                target.set(targetPath + "." + known, val);
                entryCollector.add(ImporterUtil.entry(sourceFile, full, targetPath + "." + known,
                        val, "mapped", null));
            } else {
                // 无法无损转换：保存为 unsupported 节点并列入报告
                target.set(targetPath + ".unsupported." + key, val);
                entryCollector.add(ImporterUtil.entry(sourceFile, full, targetPath + ".unsupported." + key,
                        val, "unsupported", "helstera 无此字段，已保留原值避免静默丢弃"));
            }
        }
    }

    private static String knownField(String key) {
        return switch (key.toLowerCase()) {
            case "type", "mobtype", "entitytype" -> "entity-type";
            case "health" -> "health";
            case "damage", "attackdamage" -> "attack-damage";
            case "display", "displayname", "name" -> "display-name";
            case "speed", "movementspeed" -> "move-speed";
            case "armor" -> "armor";
            case "level", "levelmod" -> "level";
            case "options", "skills", "atrigger", "onattack", "ondeath", "onspawn" -> null;
            default -> null;
        };
    }
}
