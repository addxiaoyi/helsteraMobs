package dev.helstera.migration.importer;

import dev.helstera.api.migration.MigrationImporter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * MythicMobs 导入器：扫描 Mobs/*.yml 与 Skills/*.yml，转为 helstera 定义。
 *
 * <p>此前只映射 4 个字段（Type/Health/Damage/Display），其余全部塞进
 * {@code unsupported}。问题在于 MythicMobs 配置里<b>信息量最大的恰恰是 Options
 * 与 Skills</b>——而这两个都被整块丢进 unsupported，等于「导入了但等于没导入」，
 * 用户仍要手写全部内容。</p>
 *
 * <p>现在分三类处理：</p>
 * <ul>
 *   <li><b>可直接映射</b>：写入 helstera 对应路径；</li>
 *   <li><b>可转换</b>：MythicMobs 的行内 mechanic / 触发器写法转成 helstera
 *       的定义行（经 {@link MythicSpec} 归一化）；</li>
 *   <li><b>无等价</b>：进 {@code unsupported} 并按 BLOCKER/WARN 分级，
 *       说明「为什么丢、怎么手工补」。</li>
 * </ul>
 *
 * <p><b>Skills 双重落地</b>：既解析成可执行条目，又在 {@code unsupported.Skills}
 * 保留原始列表。前者让导入结果真的能跑，后者让作者能逐条比对原配置——
 * 只保留原值等于让用户自己重写，只保留解析结果则丢失了「原本长什么样」，
 * 两者缺一都会让迁移变成黑盒。</p>
 *
 * <p>报告条目沿用 {@code status} 字段（mapped/unsupported）并新增 {@code severity}，
 * 避免破坏既有消费者对 {@code status} 的判断。</p>
 */
public final class MythicMobsImporter implements MigrationImporter {

    @Override
    public String sourceId() {
        return "mythicmobs";
    }

    @Override
    public String displayName() {
        return "MythicMobs";
    }

    @Override
    public List<Path> scan(Path pluginDataFolder) {
        List<Path> out = new ArrayList<>();
        collect(pluginDataFolder.resolve("Mobs"), out, 3);
        // Skills 目录一并扫：Mobs 里 skill{s=X} 引用的定义在这里，
        // 只导 Mobs 会得到一堆指向空技能的悬空引用。
        collect(pluginDataFolder.resolve("Skills"), out, 3);
        return out;
    }

    private void collect(Path dir, List<Path> out, int depth) {
        if (!Files.isDirectory(dir)) return;
        try (var s = Files.walk(dir, depth)) {
            s.filter(p -> p.toString().endsWith(".yml")).forEach(out::add);
        } catch (Exception ignored) {
        }
    }

    @Override
    public List<Map<String, Object>> convert(Path sourceFile) {
        List<Map<String, Object>> entries = new ArrayList<>();
        YamlConfiguration src = ImporterUtil.load(sourceFile);
        boolean isSkillsDir = sourceFile.getParent() != null
                && sourceFile.getParent().getFileName() != null
                && "skills".equalsIgnoreCase(sourceFile.getParent().getFileName().toString());

        if (isSkillsDir) {
            return convertSkills(src, sourceFile, entries);
        }

        ConfigurationSection mobs = src.getConfigurationSection("mobs");
        if (mobs == null) return entries;

        for (String mobKey : mobs.getKeys(false)) {
            ConfigurationSection mob = mobs.getConfigurationSection(mobKey);
            if (mob == null) continue;
            convertMob(mobKey, mob, sourceFile.toString(), entries);
        }
        return entries;
    }

    // ------------------------------------------------------------------
    // 生物
    // ------------------------------------------------------------------

    private void convertMob(String mobKey, ConfigurationSection mob, String srcFile,
                            List<Map<String, Object>> entries) {
        String targetId = "mythic_" + normalizeId(mobKey);
        YamlConfiguration target = new YamlConfiguration();
        target.set("schema-version", 1);
        target.set("id", targetId);

        // ---- 基础属性 ----
        mapScalar(mob, "type", srcFile, entries, target, "mob.entity-type");
        mapScalar(mob, "health", srcFile, entries, target, "mob.health");
        mapScalar(mob, "damage", srcFile, entries, target, "mob.attack-damage");
        mapScalar(mob, "display", srcFile, entries, target, "mob.display-name");
        mapScalar(mob, "speed", srcFile, entries, target, "mob.move-speed");
        mapScalar(mob, "armor", srcFile, entries, target, "mob.armor");
        mapScalar(mob, "followrange", srcFile, entries, target, "mob.sight-radius");
        mapScalar(mob, "exp", srcFile, entries, target, "mob.exp");
        mapScalar(mob, "level", srcFile, entries, target, "mob.level");
        mapScalar(mob, "knockbackresistance", srcFile, entries, target, "mob.knockback-resistance");

        // ---- Options：MythicMobs 里最常改的语义配置 ----
        ConfigurationSection options = ciSection(mob, "Options");
        if (options != null) {
            mapOption(options, srcFile, entries, target, "FollowRange", "mob.sight-radius");
            mapOption(options, srcFile, entries, target, "MovementSpeed", "mob.move-speed");
            mapOption(options, srcFile, entries, target, "MaxCombatDistance", "mob.sight-radius");
            mapOption(options, srcFile, entries, target, "AttackSpeed", "mob.attack-cooldown");
            mapOption(options, srcFile, entries, target, "Despawn", "mob.despawn");
            mapOption(options, srcFile, entries, target, "Health", "mob.health");
            mapOption(options, srcFile, entries, target, "Damage", "mob.attack-damage");
            mapOption(options, srcFile, entries, target, "ThreatTable", "mob.threat-weight");
            mapOption(options, srcFile, entries, target, "SpawningDistance", "mob.spawning-distance");
            // Options 整体也留一份，避免上表没覆盖的项静默消失
            target.set("mob.unsupported.Options", options.getValues(true));
            note(entries, srcFile, mobKey + ".Options", "mob.unsupported.Options",
                    options.getValues(true).toString(), "WARN",
                    "Options 为部分映射：已识别的键已单独落地，其余保留原值");
        }

        // ---- Drops ----
        Object drops = firstOf(mob, "DropTable-1", "DropTable1", "Drops");
        if (drops != null) {
            target.set("mob.drops.table", drops);
            note(entries, srcFile, mobKey + ".DropTable-1", "mob.drops.table", drops, "mapped", null);
        }

        // ---- Skills：双重落地 ----
        List<String> rawSkills = stringOrList(mob, "Skills");
        if (!rawSkills.isEmpty()) {
            // 原值保留
            target.set("mob.unsupported.Skills", rawSkills);
            note(entries, srcFile, mobKey + ".Skills", "mob.unsupported.Skills",
                    rawSkills, "unsupported", "原始 Skills 已解析到下方条目，并在此保留原值供比对");

            List<String> decisions = new ArrayList<>();
            List<String> skillRefs = new ArrayList<>();
            SkillScan scan = new SkillScan();
            for (String line : rawSkills) {
                classifySkillLine(line, scan, skillRefs, decisions, entries, srcFile, mobKey);
            }
            if (!skillRefs.isEmpty()) target.set("mob.skills", skillRefs);
            if (!decisions.isEmpty()) target.set("mob.on-decision", decisions);
            for (SkillScan.Trigger t : scan.triggers) {
                target.set("mob.triggers." + t.trigger + ".do", t.actions);
                note(entries, srcFile, mobKey + ".Skills[~" + t.trigger + "]", "mob.triggers." + t.trigger,
                        t.actions, "mapped", "MythicMobs ~Trigger:Skill 已转成 helstera 触发器");
            }
        }

        // ---- Immunities / DamageModifiers ----
        mapDamageModifiers(mob, srcFile, entries, target, mobKey);

        // ---- 无等价字段：分级告警 ----
        unsupportedSections(mob, entries, srcFile, mobKey, target,
                "AIGoalSelectors", "helstera 的 AI 为档案式状态机，无 MM 的 Goal 选择器；需在 ai.yml 手工改写",
                "AIGoalTargets", "helstera 使用 Targeter 而非 AIGoalTargets，需改为 mobs/*.yml 的 ai.targeter",
                "Modules", "MM 的 Java 扩展模块无 helstera 对应物，行为需手工用技能重建",
                "Equipment", "helstera 尚无装备系统；需用技能 equip-self 近似",
                "Spawners", "MM 的内嵌 Spawners 需拆成 helstera 的独立 spawners.yml 条目");

        // AI 档位：放在最后，前面若已显式指定过 ai-profile 则不覆盖
        if (target.getString("mob.ai-profile") == null) {
            target.set("mob.ai-profile", "default");
        }

        entries.add(Map.of(
                "target-file", "mobs/" + targetId + ".yml",
                "content", target.saveToString(),
                "status", "mapped",
                "note", "MythicMobs 生物 " + mobKey + " 已转换"));
    }

    /** 触发器分组的中间结果。 */
    private static final class SkillScan {
        final List<Trigger> triggers = new ArrayList<>();

        void add(String trigger, String action) {
            for (Trigger t : triggers) {
                if (t.trigger.equals(trigger)) {
                    t.actions.add(action);
                    return;
                }
            }
            Trigger t = new Trigger();
            t.trigger = trigger;
            t.actions.add(action);
            triggers.add(t);
        }

        static final class Trigger {
            String trigger;
            final List<String> actions = new ArrayList<>();
        }
    }

    /**
     * 把一行 Skills 分流到「触发器 / 技能引用 / 直接 mechanic」。
     *
     * <p>三种写法互斥，判定顺序即优先级：{@code ~Trigger:Skill} 最特殊
     * （带冒号），其次是 {@code skill{s=X}}，剩下的都当行内 mechanic。</p>
     */
    private void classifySkillLine(String line, SkillScan scan, List<String> skillRefs,
                                   List<String> decisions, List<Map<String, Object>> entries,
                                   String srcFile, String mobKey) {
        if (line == null || line.isBlank()) return;
        String raw = line.trim();

        // 纯注释
        if (raw.startsWith("#")) return;

        // 1) ~onSpawn:Skill
        String call = MythicSpec.triggerCall(raw);
        if (call != null) {
            int bar = call.indexOf('|');
            String trigger = call.substring(0, bar);
            String skill = call.substring(bar + 1);
            scan.add(trigger, "cast-skill " + skill);
            return;
        }

        // 2) skill{s=X}：转为技能引用，交给 skills.yml 解析
        String ref = MythicSpec.skillRef(raw);
        if (ref != null) {
            skillRefs.add(ref);
            return;
        }

        // 3) 行内 mechanic：归一化后作为决策动作
        String normalized = MythicSpec.normalize(raw);
        if (normalized == null || normalized.isBlank()) return;
        decisions.add(normalized);
        note(entries, srcFile, mobKey + ".Skills[]", "mob.on-decision", normalized,
                "mapped", "行内 mechanic 已归一化为 helstera 动作定义");
    }

    // ------------------------------------------------------------------
    // Skills/*.yml
    // ------------------------------------------------------------------

    /**
     * 转换独立的技能文件。
     *
     * <p>MythicMobs 的 {@code Skills/Foo.yml} 是「一条列表含多个 mechanic 行」，
     * 输出为 helstera 的单个命名技能；同名冲突通过加文件前缀规避。</p>
     */
    private List<Map<String, Object>> convertSkills(YamlConfiguration src, Path file,
                                                     List<Map<String, Object>> entries) {
        String fileStem = file.getFileName() == null ? "skills"
                : file.getFileName().toString().replaceAll("\\.ya?ml$", "");
        // Skills/Foo.yml 可能直接是一串列表，也可能包在 skills: 下
        Object root = src.get("skills") != null ? src.get("skills") : null;
        List<String> lines = new ArrayList<>();
        if (root instanceof List<?> l) {
            for (Object o : l) if (o != null) lines.add(String.valueOf(o));
        } else {
            for (String key : src.getKeys(false)) {
                List<String> l = src.getStringList(key);
                if (!l.isEmpty()) lines.addAll(l);
            }
        }
        if (lines.isEmpty()) return entries;

        String skillId = normalizeId(fileStem);
        YamlConfiguration target = new YamlConfiguration();
        target.set("schema-version", 1);
        List<String> actions = new ArrayList<>();
        List<String> refs = new ArrayList<>();
        SkillScan scan = new SkillScan();
        for (String line : lines) {
            classifySkillLine(line, scan, refs, actions, entries, file.toString(), fileStem);
        }
        if (!actions.isEmpty()) target.set("skills." + skillId + ".on-decision", actions);
        for (SkillScan.Trigger t : scan.triggers) {
            target.set("skills." + skillId + ".triggers." + t.trigger + ".do", t.actions);
        }
        if (target.getConfigurationSection("skills") == null
                && actions.isEmpty() && scan.triggers.isEmpty()) {
            return entries;   // 全是技能引用时无可落地的动作，留给引用方处理
        }
        entries.add(Map.of(
                "target-file", "skills/" + skillId + ".yml",
                "content", target.saveToString(),
                "status", "mapped",
                "note", "MythicMobs 技能 " + fileStem + " 已转换"));
        return entries;
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    private void unsupportedSections(ConfigurationSection mob, List<Map<String, Object>> entries,
                                     String srcFile, String mobKey, YamlConfiguration target,
                                     String... sectionAndReason) {
        for (int i = 0; i < sectionAndReason.length; i += 2) {
            String name = sectionAndReason[i];
            String reason = sectionAndReason[i + 1];
            ConfigurationSection sec = ciSection(mob, name);
            if (sec == null) continue;
            target.set("mob.unsupported." + name, sec.getValues(true));
            // 无等价物属必须手工处理，用 BLOCKER 让用户无法忽略
            note(entries, srcFile, mobKey + "." + name, "mob.unsupported." + name,
                    sec.getValues(true).toString(), "unsupported", reason, "BLOCKER");
        }
    }

    private void mapScalar(ConfigurationSection mob, String name, String srcFile,
                           List<Map<String, Object>> entries, YamlConfiguration target, String path) {
        Object v = ciGet(mob, name);
        if (v == null) return;
        target.set(path, v);
        note(entries, srcFile, name, path, v, "mapped", null);
    }

    /**
     * 大小写不敏感取节。
     *
     * <p>必须这么做：Bukkit 的 {@code get()} 大小写敏感，而 MythicMobs 的键
     * 全是大驼峰（{@code Health} / {@code Display} / {@code DropTable-1}）。
     * 直接 {@code get("health")} 会静默返回 null——导入结果里属性全为默认值，
     * 而报告里一条告警都没有，正是最难排查的一类失败。</p>
     */
    static Object ciGet(ConfigurationSection s, String... names) {
        for (String name : names) {
            Object v = s.get(name);
            if (v != null) return v;
        }
        // 回退：遍历实有键做忽略大小写匹配
        for (String want : names) {
            for (String actual : s.getKeys(false)) {
                if (actual.equalsIgnoreCase(want)) return s.get(actual);
            }
        }
        return null;
    }

    static ConfigurationSection ciSection(ConfigurationSection s, String... names) {
        for (String name : names) {
            ConfigurationSection sec = s.getConfigurationSection(name);
            if (sec != null) return sec;
        }
        for (String want : names) {
            for (String actual : s.getKeys(false)) {
                if (actual.equalsIgnoreCase(want)) {
                    ConfigurationSection sec = s.getConfigurationSection(actual);
                    if (sec != null) return sec;
                }
            }
        }
        return null;
    }

    private void mapOption(ConfigurationSection options, String srcFile,
                           List<Map<String, Object>> entries, YamlConfiguration target,
                           String optionName, String path) {
        Object v = ciGet(options, optionName);
        if (v == null) return;
        // Despawn 是嵌套节，直接搬会带出 MM 特有结构，标注需人工确认
        Object resolved = v instanceof ConfigurationSection
                ? v.getClass().getSimpleName() : v;
        target.set(path, v instanceof ConfigurationSection ? resolved : v);
        note(entries, srcFile, "Options." + optionName, path, resolved, "mapped",
                v instanceof ConfigurationSection ? "该 Options 项是嵌套节，helstera 侧结构不同，需人工确认" : null);
    }

    /**
     * 迁移 {@code Immunities} 与 {@code DamageModifiers}。
     *
     * <p>此前这两个节被整块列进 {@code unsupported}，理由是「helstera 尚无伤害修饰器」。
     * 现在 helstera 侧已实现免疫/倍率表，这个理由不再成立——继续报 BLOCKER 会让
     * 已有 MM 免疫配置的用户以为必须手工重建，而它们本可以自动落地。</p>
     *
     * <p>MM 的 DamageModifiers 值有三种写法，逐一兼容：裸数字、带
     * {@code multiplier} 的节、以及带 {@code conditions} 的条目列表。</p>
     */
    private void mapDamageModifiers(ConfigurationSection mob, String srcFile,
                                    List<Map<String, Object>> entries,
                                    YamlConfiguration target, String mobKey) {
        Object immRaw = ciGet(mob, "Immunities");
        List<String> immunities = new ArrayList<>();
        if (immRaw instanceof ConfigurationSection sec) {
            for (String k : sec.getKeys(false)) immunities.add(k);
        } else if (immRaw != null) {
            immunities.addAll(stringOrList(mob, "Immunities"));
        }
        if (!immunities.isEmpty()) {
            target.set("mob.immunities", immunities);
            note(entries, srcFile, mobKey + ".Immunities", "mob.immunities",
                    immunities, "mapped",
                    "MM 免疫清单已转为 helstera immunities（cause 名与类别名通用，写错会在 /helstera check 报出）");
        }

        ConfigurationSection mods = ciSection(mob, "DamageModifiers");
        if (mods == null) return;
        int mapped = 0;
        for (String key : mods.getKeys(false)) {
            Object raw = mods.get(key);
            if (raw == null) continue;
            String path = "mob.damage-modifiers." + key;
            if (raw instanceof Number n) {
                target.set(path, n);
                note(entries, srcFile, mobKey + ".DamageModifiers." + key, path,
                        n, "mapped", "裸倍率已直接转为 helstera damage-modifiers");
                mapped++;
            } else if (raw instanceof ConfigurationSection sub) {
                Double mul = sub.contains("multiplier") ? sub.getDouble("multiplier") : null;
                List<String> rawConds = stringOrList(sub, "conditions");
                List<String> conds = new ArrayList<>();
                List<String> unmapped = new ArrayList<>();
                MmConditions.translateAll(rawConds, conds, unmapped);
                if (mul != null && conds.isEmpty()) {
                    target.set(path, mul);
                } else if (mul != null) {
                    target.set(path + ".multiplier", mul);
                    target.set(path + ".conditions", conds);
                } else if (!conds.isEmpty()) {
                    target.set(path + ".immune", true);
                    target.set(path + ".conditions", conds);
                } else {
                    note(entries, srcFile, mobKey + ".DamageModifiers." + key, path,
                            sub.getValues(true).toString(), "unsupported",
                            "该条既无 multiplier 也无 conditions，无法判定语义，请手工确认", "WARN");
                    continue;
                }
                if (!unmapped.isEmpty()) {
                    // 未映射的条件已原样保留，但必须告警：条件求值是 fail-closed，
                    // 一个永不成立的条件会让整条免疫规则静默失效
                    note(entries, srcFile, mobKey + ".DamageModifiers." + key + ".conditions",
                            path + ".conditions", unmapped, "mapped",
                            "以下条件在 helstera 无等价物，已原样保留，需手工改写: " + unmapped,
                            "WARN");
                }
                note(entries, srcFile, mobKey + ".DamageModifiers." + key, path,
                        target.get(path), "mapped",
                        conds.isEmpty() ? null : "已带条件：仅在 conditions 全部成立时生效");
                mapped++;
            } else if (raw instanceof List<?> list && !list.isEmpty()) {
                // MM 的多条目写法：取第一条能落地的，并提示其余需手工合并。
                // 注意 YAML 列表里的 map 元素反序列化成 Map 而不是 ConfigurationSection，
                // 只判 section 会让这条分支永远进不去（表现为该 cause 静默不迁移）
                Object first = list.get(0);
                Double mul = mapDouble(first, "multiplier");
                List<String> conds = mapList(first, "conditions");
                if (mul != null) {
                    target.set(path + ".multiplier", mul);
                    if (!conds.isEmpty()) target.set(path + ".conditions", conds);
                    note(entries, srcFile, mobKey + ".DamageModifiers." + key, path,
                            mul, "mapped",
                            list.size() > 1
                                    ? "MM 该 cause 有 " + list.size()
                                    + " 条 modifier，只迁了第一条，其余需手工合并（helstera 同一键只保留一条）"
                                    : null);
                    mapped++;
                    continue;
                }
                if (!conds.isEmpty()) {
                    target.set(path + ".immune", true);
                    target.set(path + ".conditions", conds);
                    note(entries, srcFile, mobKey + ".DamageModifiers." + key, path,
                            conds, "mapped",
                            list.size() > 1
                                    ? "只迁了第一条，其余需手工合并（helstera 同一键只保留一条）"
                                    : null);
                    mapped++;
                    continue;
                }
                note(entries, srcFile, mobKey + ".DamageModifiers." + key, path,
                        list.toString(), "unsupported",
                        "多条目写法未能识别出 multiplier，请手工确认", "WARN");
            }
        }
        if (mapped == 0) {
            note(entries, srcFile, mobKey + ".DamageModifiers", "mob.damage-modifiers",
                    mods.getValues(true).toString(), "unsupported",
                    "未能识别出任何可迁移的倍率条目", "WARN");
        }
    }

    private void note(List<Map<String, Object>> entries, String srcFile, String srcKey,
                      String targetKey, Object value, String status, String note) {
        note(entries, srcFile, srcKey, targetKey, value, status, note, "INFO");
    }

    private void note(List<Map<String, Object>> entries, String srcFile, String srcKey,
                      String targetKey, Object value, String status, String note, String severity) {
        Map<String, Object> e = new LinkedHashMap<>(ImporterUtil.entry(
                srcFile, srcKey, targetKey, value, status, note));
        e.put("severity", severity);
        entries.add(e);
    }

    /** 合法化生物/技能 ID：小写、非字母数字转下划线、去掉 MythicMobs 前缀重复。 */
    static String normalizeId(String raw) {
        if (raw == null) return "unnamed";
        String s = raw.trim().toLowerCase(Locale.ROOT);
        // MythicMobs 常写成 SKILL.FooBar，File 前缀与目录名重复，去掉避免 FooBar 变成 skill_foobar
        int dot = s.indexOf('.');
        if (dot > 0) s = s.substring(dot + 1);
        s = s.replaceAll("[^a-z0-9_]", "_").replaceAll("_+", "_")
                .replaceAll("^_", "").replaceAll("_$", "");
        return s.isEmpty() ? "unnamed" : s;
    }

    private static Object firstOf(ConfigurationSection s, String... keys) {
        return ciGet(s, keys);
    }

    private static List<String> stringOrList(ConfigurationSection s, String key) {
        Object raw = ciGet(s, key);
        if (raw == null) return List.of();
        if (raw instanceof String one) return List.of(one);
        if (raw instanceof List<?> l) {
            List<String> out = new ArrayList<>();
            for (Object o : l) if (o != null) out.add(String.valueOf(o));
            return out;
        }
        return List.of(String.valueOf(raw));
    }

    /**
     * 从 YAML 列表里的 map 元素取数值。
     *
     * <p>刻意不接受 {@code ConfigurationSection}：Bukkit 把 YAML 的<b>列表</b>元素
     * 反序列化成 {@code Map}，只有节才是 {@code ConfigurationSection}。
     * 按 section 判断会让「列表里写 multiple 条 modifier」这种最常见的写法整条落空，
     * 而外部表现只是「该 cause 没迁过来」，没有任何报错。</p>
     */
    private static Double mapDouble(Object element, String key) {
        if (!(element instanceof Map<?, ?> m)) return null;
        for (Map.Entry<?, ?> e : m.entrySet()) {
            if (String.valueOf(e.getKey()).equalsIgnoreCase(key) && e.getValue() instanceof Number n) {
                return n.doubleValue();
            }
        }
        return null;
    }

    /** 从 YAML 列表里的 map 元素取字符串列表；也兼容单条字符串。 */
    private static List<String> mapList(Object element, String key) {
        if (!(element instanceof Map<?, ?> m)) return List.of();
        for (Map.Entry<?, ?> e : m.entrySet()) {
            if (!String.valueOf(e.getKey()).equalsIgnoreCase(key)) continue;
            Object v = e.getValue();
            if (v instanceof String one) return one.isBlank() ? List.of() : List.of(one.trim());
            if (v instanceof List<?> l) {
                List<String> out = new ArrayList<>();
                for (Object o : l) if (o != null && !String.valueOf(o).isBlank()) {
                    out.add(String.valueOf(o).trim());
                }
                return out;
            }
        }
        return List.of();
    }
}