package dev.helstera.api.migration;

import java.util.List;
import java.util.Map;

/**
 * 迁移服务：扫描、预览、应用、报告和回滚。
 * 流程：scan -> preview -> (dry-run) -> apply（自动备份，失败自动回滚）。
 */
public interface MigrationService {

    /** 注册导入器（SPI）。 */
    void registerImporter(MigrationImporter importer);

    /** 扫描所有已注册导入器的来源。返回报告。 */
    MigrationReport scan(String source);

    /** 生成映射预览（不落盘）。 */
    MigrationReport preview(String source);

    /**
     * 应用迁移（自动备份；dryRun=true 时只生成报告不写入）。
     */
    MigrationReport apply(String source, boolean dryRun);

    /** 回滚到指定备份。 */
    boolean rollback(String source, String backupId);

    /** 列出可用备份。 */
    List<Map<String, Object>> listBackups(String source);

    /** 最近一次报告（JSON 字符串）。 */
    String lastReportJson();
}
