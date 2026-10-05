package dev.helstera.ai.codex;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 图鉴目录：聚合条目并做检索。
 *
 * <p>刻意做成不持有 {@code ModelRegistry} 的纯对象：图鉴要在模型热重载后
 * 立刻反映最新内容，若持有注册表就只能在下一 tick 或下一次命令执行时才刷新，
 * 表现是「卸载了模型但图鉴里还在」。</p>
 *
 * <p>条目按 id 排序而非按加入顺序：模型扫描顺序依赖文件系统，
 * 而图鉴是要给人看的列表，顺序不稳定会让每次打开都长得不一样。</p>
 */
public final class CodexCatalog {

    private final Map<String, CodexEntry> entries = new LinkedHashMap<>();

    public CodexCatalog(Collection<CodexEntry> source) {
        if (source != null) {
            for (CodexEntry e : source) {
                if (e != null && e.id() != null) entries.put(e.id(), e);
            }
        }
        // 稳定排序：id 忽略大小写，避免 "Zombie" 与 "apple" 的排序随作者书写顺序变化
        List<Map.Entry<String, CodexEntry>> sorted = new ArrayList<>(entries.entrySet());
        // 用 lambda 而非 Map.Entry.comparingKey：后者的 Comparator 泛型在本工程 JDK 下推断不出
        sorted.sort((a, b) -> {
            int c = a.getKey().compareToIgnoreCase(b.getKey());
            return c != 0 ? c : a.getKey().compareTo(b.getKey());
        });
        entries.clear();
        sorted.forEach(e -> entries.put(e.getKey(), e.getValue()));
    }

    public List<CodexEntry> all() {
        return List.copyOf(entries.values());
    }

    public int size() {
        return entries.size();
    }

    public CodexEntry find(String id) {
        return id == null ? null : entries.get(id.trim());
    }

    /**
     * 按关键字检索：匹配 id、名称、作者。
     *
     * <p>大小写不敏感。匹配到<b>任意一项</b>都算命中——管理员手输
     * 「Kingdom」时很可能指的是作者名而非模型名。</p>
     */
    public List<CodexEntry> search(String query) {
        if (query == null || query.isBlank()) return all();
        String q = query.trim().toLowerCase(Locale.ROOT);
        List<CodexEntry> out = new ArrayList<>();
        for (CodexEntry e : entries.values()) {
            if (contains(e.id(), q) || contains(e.name(), q) || contains(e.author(), q)) {
                out.add(e);
            }
        }
        return List.copyOf(out);
    }

    private static boolean contains(String hay, String needle) {
        return hay != null && hay.toLowerCase(Locale.ROOT).contains(needle);
    }

    /** 按资源包分组，保持 id 排序。 */
    public Map<String, List<CodexEntry>> byPack() {
        Map<String, List<CodexEntry>> out = new LinkedHashMap<>();
        for (CodexEntry e : entries.values()) {
            out.computeIfAbsent(e.pack(), k -> new ArrayList<>()).add(e);
        }
        out.replaceAll((k, v) -> List.copyOf(v));
        return out;
    }

    /**
     * 内容贫乏的条目（无骨骼或无动画）。
     *
     * <p>单独给出是因为它们在图鉴里是「看起来存在、其实什么都不会发生」的条目，
     * 与死配置触发器是同一类问题：写进配置不报错，但没有实际效果。</p>
     */
    public List<CodexEntry> sparse() {
        return entries.values().stream().filter(CodexEntry::isSparse).toList();
    }
}