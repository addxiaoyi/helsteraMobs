package dev.helstera.migration;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.helstera.api.migration.MigrationImporter;
import dev.helstera.api.migration.MigrationReport;
import dev.helstera.api.migration.MigrationService;
import org.bukkit.plugin.Plugin;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.io.StringReader;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ServiceLoader;

/**
 * 迁移服务实现：
 * scan -> preview -> apply(自动备份，失败自动回滚) -> rollback -> report。
 * 原始文件永不覆盖；helstera 输出写入自己的数据目录。
 */
public final class MigrationServiceImpl implements MigrationService {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Plugin plugin;
    private final Map<String, MigrationImporter> importers = new LinkedHashMap<>();
    private final Path backupRoot;
    private volatile String lastReportJson = "{}";

    public MigrationServiceImpl(Plugin plugin) {
        this.plugin = plugin;
        this.backupRoot = plugin.getDataFolder().toPath().resolve("migration/backups");
        // SPI：ServiceLoader 自动发现第三方导入器
        for (MigrationImporter importer : ServiceLoader.load(MigrationImporter.class,
                plugin.getClass().getClassLoader())) {
            registerImporter(importer);
        }
    }

    @Override
    public void registerImporter(MigrationImporter importer) {
        importers.put(importer.sourceId(), importer);
        plugin.getLogger().info("[迁移] 注册导入器: " + importer.displayName());
    }

    private MigrationImporter importer(String source) {
        MigrationImporter i = importers.get(source.toLowerCase());
        if (i == null) {
            throw new IllegalArgumentException("未知迁移来源: " + source
                    + "（可用: " + importers.keySet() + "）");
        }
        return i;
    }

    private Path pluginDataOf(String source) {
        Plugin p = org.bukkit.Bukkit.getPluginManager().getPlugin(sourceToPlugin(source));
        if (p == null) {
            throw new IllegalStateException("来源插件 " + sourceToPlugin(source) + " 未安装，无法扫描");
        }
        return p.getDataFolder().toPath();
    }

    private String sourceToPlugin(String source) {
        return switch (source.toLowerCase()) {
            case "mythicmobs" -> "MythicMobs";
            case "modelengine" -> "ModelEngine";
            case "itemadder" -> "ItemAdder";
            case "craftengine" -> "CraftEngine";
            default -> source;
        };
    }

    @Override
    public MigrationReport scan(String source) {
        MigrationImporter imp = importer(source);
        List<Path> files;
        try {
            files = imp.scan(pluginDataOf(source));
        } catch (Exception e) {
            MigrationReport r = new MigrationReport(source);
            r.put("error", e.getMessage());
            lastReportJson = GSON.toJson(r.data());
            return r;
        }
        MigrationReport report = new MigrationReport(source);
        report.put("files-found", files.size());
        report.put("importer", imp.displayName());
        for (Path f : files) {
            report.addEntry(Map.of("file", f.toString(), "status", "found"));
        }
        lastReportJson = GSON.toJson(report.data());
        audit("scan", source, files.size() + " files");
        return report;
    }

    @Override
    public MigrationReport preview(String source) {
        MigrationImporter imp = importer(source);
        MigrationReport report = new MigrationReport(source);
        report.put("importer", imp.displayName());
        report.put("dry-run", true);
        try {
            Path sourceRoot = pluginDataOf(source);
            List<Path> files = imp.scan(sourceRoot);
            for (Path f : files) {
                try {
                    for (Map<String, Object> entry : imp.convert(f, sourceRoot)) {
                        Map<String, Object> withSource = new LinkedHashMap<>(entry);
                        withSource.putIfAbsent("file", f.toString());
                        report.addEntry(withSource);
                    }
                } catch (Exception e) {
                    report.addEntry(Map.of("file", f.toString(), "status", "conflict", "conflict-type", "conversion-failed",
                            "note", "转换失败: " + e.getMessage()));
                }
            }
        } catch (Exception e) {
            report.put("error", e.getMessage());
        }
        report.entries().addAll(resolveSpawnerMobReferences(report.entries()));
        resolveSpawnerRegionReferences(report.entries());
        report.entries().addAll(lootTableConflicts(report.entries()));
        report.entries().addAll(mobTargetConflicts(report.entries()));
        report.entries().addAll(skillConflicts(report.entries()));
        report.entries().addAll(contentDefinitionConflicts(report.entries()));
        report.put("summary", report.summary());
        lastReportJson = GSON.toJson(report.data());
        audit("preview", source, report.summary());
        return report;
    }

    @Override
    public MigrationReport apply(String source, boolean dryRun) {
        MigrationReport preview = preview(source);
        if (dryRun) return preview;
        if (preview.data().containsKey("error")) {
            lastReportJson = GSON.toJson(preview.data());
            return preview;
        }
        if (preview.entries().stream().anyMatch(e -> "conversion-failed".equals(e.get("conflict-type"))
                || "duplicate-loot-table".equals(e.get("conflict-type"))
                || "duplicate-mob-target".equals(e.get("conflict-type"))
                || "duplicate-skill-id".equals(e.get("conflict-type"))
                || "duplicate-spawner-pool-id".equals(e.get("conflict-type"))
                || "duplicate-definition-id".equals(e.get("conflict-type")))) {
            preview.put("apply-result", Map.of("success", false, "written", 0,
                    "error", "Conversion failure or duplicate imported IDs; resolve conflicts before applying"));
            lastReportJson = GSON.toJson(preview.data());
            return preview;
        }

        Path targetRoot = plugin.getDataFolder().toPath();
        String backupId = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
                + "_" + java.util.UUID.randomUUID();

        // 1. 备份将要写入的目标文件
        List<Path> written = new ArrayList<>();
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            Map<String, YamlConfiguration> merged = new LinkedHashMap<>();
            Map<String, Path> targetPaths = new LinkedHashMap<>();
            for (Map<String, Object> e : preview.entries()) {
                if (!"mapped".equals(e.get("status"))) continue;
                if (!(e.get("target-file") instanceof String targetRel)
                        || !(e.get("content") instanceof String content)) continue;
                Path target = targetRoot.resolve(targetRel).normalize();
                if (!target.startsWith(targetRoot)) {
                    throw new IllegalStateException("Migration target escapes root: " + targetRel);
                }
                boolean replaceMob = e.containsKey("source-mob-id") && targetRel.startsWith("mobs/")
                        && targetRel.endsWith(".yml");
                YamlConfiguration destination = merged.get(targetRel);
                if (destination == null) {
                    destination = new YamlConfiguration();
                    if (!replaceMob && Files.exists(target)) {
                        try {
                            destination.load(target.toFile());
                        } catch (Exception ex) {
                            throw new IllegalStateException("Cannot load migration target " + target + ": " + ex.getMessage(), ex);
                        }
                    }
                    merged.put(targetRel, destination);
                }
                YamlConfiguration incoming = new YamlConfiguration();
                try {
                    incoming.loadFromString(content);
                } catch (org.bukkit.configuration.InvalidConfigurationException ex) {
                    throw new IllegalStateException("Invalid migration plan for " + targetRel + ": " + ex.getMessage(), ex);
                }
                if ("loot.yml".equals(targetRel)) mergeLootYaml(incoming, destination);
                else if ("skills.yml".equals(targetRel)) mergeDefinitions(incoming, destination, "skills");
                else if ("items.yml".equals(targetRel)) mergeDefinitions(incoming, destination, "items");
                else if ("spawners.yml".equals(targetRel)) {
                    clearDefinitions(incoming, destination, "spawners.regions", null);
                    clearDefinitions(incoming, destination, "spawners", "regions");
                    mergeYaml(incoming, destination, "");
                }
                else mergeYaml(incoming, destination, "");
                targetPaths.put(targetRel, target);
                if (!target.startsWith(targetRoot)) {
                    throw new IllegalStateException("迁移目标路径越界: " + targetRel);
                }
                // 备份原文件（如存在）
                // 原子写入
            }
            for (Map.Entry<String, YamlConfiguration> item : merged.entrySet()) {
                Path target = targetPaths.get(item.getKey());
                if (Files.exists(target)) {
                    Path backup = backupRoot.resolve(source + "/" + backupId + "/" + item.getKey());
                    Files.createDirectories(backup.getParent());
                    Files.copy(target, backup);
                }
                Files.createDirectories(target.getParent());
                Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
                Files.writeString(tmp, item.getValue().saveToString(), StandardCharsets.UTF_8);
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                written.add(target);
            }
            result.put("success", true);
            result.put("written", written.size());
        } catch (Exception ex) {
            // 失败自动回滚
            result.put("success", false);
            result.put("error", ex.getMessage());
            result.put("rolled-back", rollbackWritten(written, source, backupId));
        }
        preview.put("apply-result", result);
        lastReportJson = GSON.toJson(preview.data());
        audit("apply", source, result.toString());
        return preview;
    }

    private boolean rollbackWritten(List<Path> written, String source, String backupId) {
        boolean all = true;
        for (Path p : written) {
            try {
                if (restoreWrittenFile(p, plugin.getDataFolder().toPath(), backupRoot, source, backupId)) {
                    continue;
                }
                Files.deleteIfExists(p); // 新写入的文件删除即可（原文件在备份中未被动过）
            } catch (Exception e) {
                all = false;
            }
        }
        audit("apply-rollback", source, backupId);
        return all;
    }

    static void mergeYaml(ConfigurationSection source, YamlConfiguration target, String prefix) {
        for (String key : source.getKeys(false)) {
            String path = prefix.isEmpty() ? key : prefix + "." + key;
            ConfigurationSection nested = source.getConfigurationSection(key);
            if (nested != null) {
                mergeYaml(nested, target, path);
            } else {
                target.set(path, source.get(key));
            }
        }
    }

    static void mergeLootYaml(YamlConfiguration source, YamlConfiguration target) {
        mergeDefinitions(source, target, "tables");
    }

    static void mergeDefinitions(YamlConfiguration source, YamlConfiguration target, String section) {
        clearDefinitions(source, target, section, null);
        mergeYaml(source, target, "");
    }

    static void clearDefinitions(YamlConfiguration source, YamlConfiguration target, String section, String excluded) {
        ConfigurationSection tables = source.getConfigurationSection(section);
        if (tables != null) {
            ConfigurationSection existing = target.getConfigurationSection(section);
            if (existing != null) {
                for (String name : tables.getKeys(false)) {
                    if (excluded != null && excluded.equalsIgnoreCase(name)) continue;
                    String id = name.toLowerCase(Locale.ROOT);
                    for (String oldName : new ArrayList<>(existing.getKeys(false))) {
                        if (oldName.toLowerCase(Locale.ROOT).equals(id)) existing.set(oldName, null);
                    }
                }
            }
        }
    }

    static List<Map<String, Object>> contentDefinitionConflicts(List<Map<String, Object>> entries) {
        Map<String, String> sources = new LinkedHashMap<>();
        List<Map<String, Object>> conflicts = new ArrayList<>();
        for (Map<String, Object> entry : entries) {
            if (!"mapped".equals(entry.get("status")) || !(entry.get("content") instanceof String content)) continue;
            String target = String.valueOf(entry.get("target-file"));
            List<String> sections = switch (target) {
                case "items.yml" -> List.of("items");
                case "spawners.yml" -> List.of("spawners", "spawners.regions");
                default -> List.of();
            };
            if (sections.isEmpty()) continue;
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(new StringReader(content));
            String source = String.valueOf(entry.getOrDefault("file", "unknown"));
            for (String section : sections) {
                ConfigurationSection definitions = yaml.getConfigurationSection(section);
                if (definitions == null) continue;
                for (String name : definitions.getKeys(false)) {
                    if ("spawners".equals(section) && "regions".equalsIgnoreCase(name)) continue;
                    String id = section + ":" + name.toLowerCase(Locale.ROOT);
                    String previous = sources.putIfAbsent(id, source);
                    if (previous != null) conflicts.add(Map.of("status", "conflict", "conflict-type", "duplicate-definition-id",
                            "id", id, "file", source, "previous-file", previous,
                            "note", "Multiple imported definitions resolve to the same ID."));
                }
            }
        }
        return conflicts;
    }

    static List<Map<String, Object>> resolveSpawnerMobReferences(List<Map<String, Object>> entries) {
        Map<String, String> mobIds = new LinkedHashMap<>();
        for (Map<String, Object> entry : entries) {
            if ("mapped".equals(entry.get("status")) && entry.get("source-mob-id") instanceof String source
                    && entry.get("target-mob-id") instanceof String target) {
                mobIds.put(source.trim().toLowerCase(Locale.ROOT), target);
            }
        }
        List<Map<String, Object>> diagnostics = new ArrayList<>();
        for (Map<String, Object> entry : entries) {
            if (!"mapped".equals(entry.get("status")) || !"spawners.yml".equals(entry.get("target-file"))
                    || !(entry.get("content") instanceof String content)) continue;
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(new StringReader(content));
            ConfigurationSection spawners = yaml.getConfigurationSection("spawners");
            if (spawners == null) continue;
            for (String name : spawners.getKeys(false)) {
                if ("regions".equalsIgnoreCase(name)) continue;
                ConfigurationSection spawner = spawners.getConfigurationSection(name);
                if (spawner == null) continue;
                String mob = spawner.getString("mob");
                if (mob != null) {
                    String resolved = mobIds.get(mob.trim().toLowerCase(Locale.ROOT));
                    if (resolved != null) spawner.set("mob", resolved);
                    else diagnostics.add(unresolvedMob(entry, name, mob));
                }
                ConfigurationSection pool = spawner.getConfigurationSection("random-mobs");
                if (pool == null) continue;
                Map<String, Object> resolvedPool = new LinkedHashMap<>();
                for (String key : pool.getKeys(false)) {
                    String resolved = mobIds.get(key.trim().toLowerCase(Locale.ROOT));
                    if (resolved == null) {
                        resolved = key;
                        diagnostics.add(unresolvedMob(entry, name, key));
                    }
                    if (resolvedPool.putIfAbsent(resolved, pool.get(key)) != null) {
                        diagnostics.add(Map.of("status", "conflict", "conflict-type", "duplicate-spawner-pool-id",
                                "file", String.valueOf(entry.getOrDefault("file", "unknown")),
                                "note", "Spawner " + name + " has multiple pool entries resolving to " + resolved));
                    }
                }
                spawner.set("random-mobs", resolvedPool);
            }
            entry.put("content", yaml.saveToString());
        }
        return diagnostics;
    }

    static void resolveSpawnerRegionReferences(List<Map<String, Object>> entries) {
        Map<String, String> regionIds = new LinkedHashMap<>();
        for (Map<String, Object> entry : entries) {
            if (!"mapped".equals(entry.get("status")) || !(entry.get("source-regions") instanceof Map<?, ?> regions)) continue;
            for (Map.Entry<?, ?> region : regions.entrySet()) {
                if (region.getKey() instanceof String id && region.getValue() instanceof String original) {
                    regionIds.put(original.trim().toLowerCase(Locale.ROOT), id);
                }
            }
        }
        for (Map<String, Object> entry : entries) {
            if (!"mapped".equals(entry.get("status")) || !"spawners.yml".equals(entry.get("target-file"))
                    || !(entry.get("content") instanceof String content)) continue;
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(new StringReader(content));
            ConfigurationSection spawners = yaml.getConfigurationSection("spawners");
            if (spawners == null) continue;
            for (String name : spawners.getKeys(false)) {
                if ("regions".equalsIgnoreCase(name)) continue;
                ConfigurationSection spawner = spawners.getConfigurationSection(name);
                if (spawner == null || !spawner.isList("conditions")) continue;
                List<String> conditions = spawner.getStringList("conditions");
                for (int i = 0; i < conditions.size(); i++) {
                    String condition = conditions.get(i).trim();
                    boolean negated = condition.startsWith("!");
                    String leaf = negated ? condition.substring(1).trim() : condition;
                    if (!leaf.toLowerCase(Locale.ROOT).startsWith("region=")) continue;
                    String id = regionIds.get(leaf.substring(7).trim().toLowerCase(Locale.ROOT));
                    if (id != null) conditions.set(i, (negated ? "!" : "") + "region=" + id);
                }
                spawner.set("conditions", conditions);
            }
            entry.put("content", yaml.saveToString());
        }
    }

    private static Map<String, Object> unresolvedMob(Map<String, Object> entry, String spawner, String mob) {
        return Map.of("status", "unsupported", "file", String.valueOf(entry.getOrDefault("file", "unknown")),
                "note", "Spawner " + spawner + " references " + mob
                        + "; no matching mob was imported, so the reference was preserved.");
    }

    static List<Map<String, Object>> skillConflicts(List<Map<String, Object>> entries) {
        Map<String, String> sources = new LinkedHashMap<>();
        List<Map<String, Object>> conflicts = new ArrayList<>();
        for (Map<String, Object> entry : entries) {
            if (!"mapped".equals(entry.get("status")) || !"skills.yml".equals(entry.get("target-file"))
                    || !(entry.get("content") instanceof String content)) continue;
            ConfigurationSection skills = YamlConfiguration.loadConfiguration(new StringReader(content))
                    .getConfigurationSection("skills");
            if (skills == null) continue;
            String source = String.valueOf(entry.getOrDefault("file", "unknown"));
            for (String name : skills.getKeys(false)) {
                String previous = sources.putIfAbsent(name.toLowerCase(Locale.ROOT), source);
                if (previous != null) conflicts.add(Map.of("status", "conflict", "conflict-type", "duplicate-skill-id",
                        "skill", name, "file", source, "previous-file", previous,
                        "note", "Multiple imported skills resolve to the same ID."));
            }
        }
        return conflicts;
    }

    static List<Map<String, Object>> lootTableConflicts(List<Map<String, Object>> entries) {
        Map<String, String> sources = new LinkedHashMap<>();
        List<Map<String, Object>> conflicts = new ArrayList<>();
        for (Map<String, Object> entry : entries) {
            if (!"mapped".equals(entry.get("status")) || !"loot.yml".equals(entry.get("target-file"))
                    || !(entry.get("content") instanceof String content)) continue;
            ConfigurationSection tables = YamlConfiguration.loadConfiguration(new StringReader(content))
                    .getConfigurationSection("tables");
            if (tables == null) continue;
            String source = String.valueOf(entry.getOrDefault("file", "unknown"));
            for (String name : tables.getKeys(false)) {
                String previous = sources.putIfAbsent(name.toLowerCase(Locale.ROOT), source);
                if (previous != null) {
                    conflicts.add(Map.of("status", "conflict", "conflict-type", "duplicate-loot-table",
                            "table", name, "file", source, "previous-file", previous,
                            "note", "Multiple imported tables resolve to the same loot table ID."));
                }
            }
        }
        return conflicts;
    }

    static List<Map<String, Object>> mobTargetConflicts(List<Map<String, Object>> entries) {
        Map<String, Map<String, Object>> sources = new LinkedHashMap<>();
        List<Map<String, Object>> conflicts = new ArrayList<>();
        for (Map<String, Object> entry : entries) {
            if (!"mapped".equals(entry.get("status")) || !(entry.get("content") instanceof String)
                    || !(entry.get("target-file") instanceof String target)
                    || !target.startsWith("mobs/") || !target.endsWith(".yml")) continue;
            Map<String, Object> previous = sources.putIfAbsent(target.toLowerCase(Locale.ROOT), entry);
            if (previous != null) {
                conflicts.add(Map.of("status", "conflict", "conflict-type", "duplicate-mob-target",
                        "target-file", target, "file", String.valueOf(entry.getOrDefault("file", "unknown")),
                        "previous-file", String.valueOf(previous.getOrDefault("file", "unknown")),
                        "note", "Multiple imported mobs resolve to the same target file: "
                                + previous.getOrDefault("note", "") + "; " + entry.getOrDefault("note", "")));
            }
        }
        return conflicts;
    }

    static boolean restoreWrittenFile(Path target, Path dataRoot, Path backupRoot,
                                      String source, String backupId) throws java.io.IOException {
        Path relative = dataRoot.relativize(target);
        Path backup = backupRoot.resolve(source).resolve(backupId).resolve(relative);
        if (!Files.isRegularFile(backup)) return false;
        Files.createDirectories(target.getParent());
        Files.copy(backup, target, StandardCopyOption.REPLACE_EXISTING);
        Files.deleteIfExists(target.resolveSibling(target.getFileName() + ".tmp"));
        return true;
    }

    @Override
    public boolean rollback(String source, String backupId) {
        Path dir = backupRoot.resolve(source).resolve(backupId);
        if (!Files.isDirectory(dir)) return false;
        try (var paths = Files.walk(dir)) {
            for (Path backup : paths.filter(Files::isRegularFile).toList()) {
                Path target = plugin.getDataFolder().toPath().resolve(dir.relativize(backup));
                Files.createDirectories(target.getParent());
                Files.copy(backup, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("[迁移] 回滚失败: " + e.getMessage());
            return false;
        }
        audit("rollback", source, backupId);
        return true;
    }

    @Override
    public List<Map<String, Object>> listBackups(String source) {
        List<Map<String, Object>> out = new ArrayList<>();
        Path dir = backupRoot.resolve(source);
        if (!Files.isDirectory(dir)) return out;
        try (var s = Files.list(dir)) {
            s.filter(Files::isDirectory).forEach(d -> {
                out.add(Map.of("backup-id", d.getFileName().toString(), "source", source));
            });
        } catch (Exception ignored) {
        }
        return out;
    }

    @Override
    public String lastReportJson() {
        return lastReportJson;
    }

    /** 结构化审计日志（网页保存/迁移应用/回滚共用格式）。 */
    private void audit(String action, String source, String detail) {
        try {
            Path log = plugin.getDataFolder().toPath().resolve("migration/audit.log");
            Files.createDirectories(log.getParent());
            String line = String.format("[%s] %s source=%s %s%n",
                    LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME), action, source, detail);
            Files.writeString(log, line, StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception ignored) {
        }
    }
}
