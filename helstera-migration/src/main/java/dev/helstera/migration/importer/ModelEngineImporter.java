package dev.helstera.migration.importer;

import dev.helstera.api.migration.MigrationImporter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * ModelEngine 导入器：扫描 models 蓝图目录，把模型引用映射为 helstera 模型条目。
 * 模型文件本身（bbmodel 蓝图）不转换，写入迁移报告要求用 Blockbench 导出 helstera-v1 格式。
 */
public final class ModelEngineImporter implements MigrationImporter {

    @Override
    public String sourceId() {
        return "modelengine";
    }

    @Override
    public String displayName() {
        return "ModelEngine";
    }

    @Override
    public List<Path> scan(Path pluginDataFolder) {
        List<Path> out = new ArrayList<>();
        Path models = pluginDataFolder.resolve("models");
        if (!Files.isDirectory(models)) return out;
        try (var s = Files.walk(models, 3)) {
            s.filter(p -> p.toString().endsWith(".bbmodel")).forEach(out::add);
        } catch (Exception ignored) {
        }
        return out;
    }

    @Override
    public List<Map<String, Object>> convert(Path sourceFile) {
        List<Map<String, Object>> entries = new ArrayList<>();
        String name = sourceFile.getFileName().toString().replace(".bbmodel", "");
        String targetId = "me_" + name.toLowerCase().replaceAll("[^a-z0-9_]", "_");
        entries.add(ImporterUtil.entry(sourceFile.toString(), "blueprint:" + name,
                "models/" + targetId + "/", "[蓝图]",
                "unsupported",
                "bbmodel 蓝图无法无损转换：请用 Blockbench 打开后导出 helstera-v1 格式"
                        + "（model.json + animations.json + manifest.yml）到 plugins/helsteraMobs/models/"
                        + targetId + "/"));
        return entries;
    }
}
