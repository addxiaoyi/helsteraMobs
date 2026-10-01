package dev.helstera.api.migration;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 迁移报告：聚合条目与统计。
 */
public final class MigrationReport {

    private final String source;
    private final Map<String, Object> data = new LinkedHashMap<>();

    public MigrationReport(String source) {
        this.source = source;
        data.put("source", source);
        data.put("entries", new java.util.ArrayList<Map<String, Object>>());
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> entries() {
        return (List<Map<String, Object>>) data.get("entries");
    }

    public void addEntry(Map<String, Object> entry) {
        entries().add(entry);
    }

    public MigrationReport put(String key, Object value) {
        data.put(key, value);
        return this;
    }

    public String source() {
        return source;
    }

    public Map<String, Object> data() {
        return data;
    }

    /** 统计摘要行。 */
    public String summary() {
        int total = entries().size();
        long mapped = entries().stream().filter(e -> "mapped".equals(e.get("status"))).count();
        long unsupported = entries().stream().filter(e -> "unsupported".equals(e.get("status"))).count();
        long conflict = entries().stream().filter(e -> "conflict".equals(e.get("status"))).count();
        return String.format("来源=%s 共 %d 项：映射 %d，不支持 %d，冲突 %d", source, total, mapped, unsupported, conflict);
    }
}
