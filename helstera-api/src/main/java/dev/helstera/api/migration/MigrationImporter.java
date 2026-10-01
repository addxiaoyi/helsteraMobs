package dev.helstera.api.migration;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 迁移导入器 SPI：公开字段映射、资源解析和报告接口。
 * 第三方可通过 Java ServiceLoader 或 MigrationService#registerImporter 注册。
 */
public interface MigrationImporter {

    /** 来源标识，例如 "mythicmobs"。 */
    String sourceId();

    /** 来源插件显示名。 */
    String displayName();

    /** 扫描来源目录，返回找到的文件列表（相对路径）。 */
    List<Path> scan(Path pluginDataFolder);

    /**
     * 将一个来源文件转换为 helstera 目标文件写入计划。
     * 每条计划：{"target": 相对路径, "content": 文件内容, "status": "mapped"/"unsupported", "warnings": [...]}
     * 无法无损转换的字段必须放入 "unsupported" 节点，不能静默丢弃。
     */
    List<Map<String, Object>> convert(Path sourceFile);
}
