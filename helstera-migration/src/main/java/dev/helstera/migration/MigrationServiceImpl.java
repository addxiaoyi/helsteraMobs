package dev.helstera.migration;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.helstera.api.migration.MigrationImporter;
import dev.helstera.api.migration.MigrationReport;
import dev.helstera.api.migration.MigrationService;
import org.bukkit.plugin.Plugin;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;

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
    /** 回滚记录：backupId -> {source -> files}。 */
    private final Map<String, Map<String, List<Path>>> backups = new ConcurrentHashMap<>();

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
            List<Path> files = imp.scan(pluginDataOf(source));
            for (Path f : files) {
                try {
                    report.entries().addAll(imp.convert(f));
                } catch (Exception e) {
                    report.addEntry(Map.of("file", f.toString(), "status", "conflict",
                            "note", "转换失败: " + e.getMessage()));
                }
            }
        } catch (Exception e) {
            report.put("error", e.getMessage());
        }
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

        Path targetRoot = plugin.getDataFolder().toPath();
        String backupId = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));

        // 1. 备份将要写入的目标文件
        List<Path> written = new ArrayList<>();
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            for (Map<String, Object> e : preview.entries()) {
                if (!"mapped".equals(e.get("status"))) continue;
                String targetRel = String.valueOf(e.get("target-file"));
                if (targetRel == null || "null".equals(targetRel)) continue;
                Path target = targetRoot.resolve(targetRel).normalize();
                if (!target.startsWith(targetRoot)) {
                    throw new IllegalStateException("迁移目标路径越界: " + targetRel);
                }
                // 备份原文件（如存在）
                if (Files.exists(target)) {
                    Path backup = backupRoot.resolve(source + "/" + backupId + "/" + targetRel);
                    Files.createDirectories(backup.getParent());
                    Files.copy(target, backup, StandardCopyOption.REPLACE_EXISTING);
                    backups.computeIfAbsent(backupId, k -> new HashMap<>())
                            .computeIfAbsent(source, k -> new ArrayList<>()).add(backup);
                }
                // 原子写入
                Files.createDirectories(target.getParent());
                Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
                Files.writeString(tmp, String.valueOf(e.get("content")), StandardCharsets.UTF_8);
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
                Files.deleteIfExists(p); // 新写入的文件删除即可（原文件在备份中未被动过）
            } catch (Exception e) {
                all = false;
            }
        }
        audit("apply-rollback", source, backupId);
        return all;
    }

    @Override
    public boolean rollback(String source, String backupId) {
        Map<String, List<Path>> record = backups.get(backupId);
        if (record == null) {
            // 扫描备份目录
            Path dir = backupRoot.resolve(source + "/" + backupId);
            if (!Files.isDirectory(dir)) return false;
            try (var s = Files.walk(dir)) {
                s.filter(Files::isRegularFile).forEach(b -> {
                    try {
                        String rel = dir.relativize(b).toString();
                        Path target = plugin.getDataFolder().toPath().resolve(rel);
                        Files.createDirectories(target.getParent());
                        Files.copy(b, target, StandardCopyOption.REPLACE_EXISTING);
                    } catch (Exception ignored) {
                    }
                });
            } catch (Exception e) {
                return false;
            }
            audit("rollback", source, backupId);
            return true;
        }
        for (List<Path> paths : record.values()) {
            for (Path b : paths) {
                try {
                    // 备份根 = backupRoot/source/backupId/targetRoot...
                    Path rel = backupRoot.resolve(source + "/" + backupId).relativize(b);
                    Path target = plugin.getDataFolder().toPath().resolve(rel);
                    Files.createDirectories(target.getParent());
                    Files.copy(b, target, StandardCopyOption.REPLACE_EXISTING);
                } catch (Exception ignored) {
                }
            }
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
