package dev.helstera.ai.skill;

import dev.helstera.ai.AiProfile;
import dev.helstera.api.behavior.BehaviorRegistry;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/**
 * 技能装载器：把 skills.yml 与 profile 中的文本定义解析成可执行的条件/动作。
 *
 * <p>核心是「按需绑定」——{@code health-below 0.3} 这行文本在加载期被切成
 * 条件名与参数，构造闭包后注册为 {@code health-below:0.3} 这样的确定性键。
 * 运行期决策节拍只按键查表，不做字符串解析与 IO。</p>
 *
 * <p>线程约束：全部方法要求主线程，在插件启用阶段调用。</p>
 */
public final class SkillService {

    private final BehaviorRegistry registry;
    private final Logger log;
    private final Map<String, SkillCatalog.ConditionFactory> conditionFactories;
    private final Map<String, SkillCatalog.ActionFactory> actionFactories;
    /** 已绑定过的键，避免重复注册与重复告警。 */
    private final Set<String> bound = new LinkedHashSet<>();
    private final List<String> warnings = new ArrayList<>();

    public SkillService(BehaviorRegistry registry, Logger log) {
        this(registry, log, SkillCatalog.conditions(), SkillCatalog.actions());
    }

    public SkillService(BehaviorRegistry registry, Logger log,
                        Map<String, SkillCatalog.ConditionFactory> conditionFactories,
                        Map<String, SkillCatalog.ActionFactory> actionFactories) {
        this.registry = registry;
        this.log = log;
        this.conditionFactories = conditionFactories;
        this.actionFactories = actionFactories;
    }

    /** 加载期告警（未知名、参数缺失等），供 /helstera debug 与启动日志展示。 */
    public List<String> warnings() {
        return List.copyOf(warnings);
    }

    /** 绑定一条条件定义，返回可写入 profile 的键；失败返回 null。 */
    public String bindCondition(String spec) {
        if (spec == null || spec.isBlank()) return null;
        List<String> parts = split(spec);
        String name = parts.get(0).toLowerCase(Locale.ROOT);
        List<String> args = parts.subList(1, parts.size());
        SkillCatalog.ConditionFactory f = conditionFactories.get(name);
        if (f == null) {
            if (!registry.hasCondition(name)) {
                warn("未知条件 \"" + name + "\"（可用内置: " + conditionFactories.keySet() + "）");
            }
            // 可能是 Java 侧注册的条件名，直接按名引用
            return registry.hasCondition(name) ? name : null;
        }
        String key = name + (args.isEmpty() ? "" : ":" + String.join(" ", args));
        if (bound.add("c:" + key)) {
            try {
                registry.registerCondition(key, f.create(args));
            } catch (RuntimeException e) {
                warn("条件 \"" + key + "\" 参数非法: " + e.getMessage());
                return null;
            }
        }
        return key;
    }

    /** 绑定一条动作定义，返回可写入 profile 的键；失败返回 null。 */
    public String bindAction(String spec) {
        if (spec == null || spec.isBlank()) return null;
        List<String> parts = split(spec);
        String name = parts.get(0).toLowerCase(Locale.ROOT);
        List<String> args = parts.subList(1, parts.size());
        SkillCatalog.ActionFactory f = actionFactories.get(name);
        if (f == null) {
            if (!registry.hasAction(name)) {
                warn("未知动作 \"" + name + "\"（可用内置: " + actionFactories.keySet() + "）");
            }
            return registry.hasAction(name) ? name : null;
        }
        String key = name + (args.isEmpty() ? "" : ":" + String.join(" ", args));
        if (bound.add("a:" + key)) {
            try {
                registry.registerAction(key, f.create(args));
            } catch (RuntimeException e) {
                warn("动作 \"" + key + "\" 参数非法: " + e.getMessage());
                return null;
            }
        }
        return key;
    }

    /**
     * 装载命名技能：{@code skills.<name>.require} 与 {@code skills.<name>.on-decision}
     * 各被注册为一个整体条件与整体动作，使 profile 只需写 {@code skills: [name]}。
     */
    public void loadSkills(ConfigurationSection root) {
        if (root == null) return;
        for (String skillName : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(skillName);
            if (s == null) continue;
            String key = ("skill:" + skillName).toLowerCase(Locale.ROOT);

            List<String> requireSpecs = s.getStringList("require");
            List<String> requireKeys = new ArrayList<>();
            for (String spec : requireSpecs) {
                String k = bindCondition(spec);
                if (k != null) requireKeys.add(k);
            }
            if (bound.add("c:" + key)) {
                List<String> captured = List.copyOf(requireKeys);
                registry.registerCondition(key, ctx -> {
                    for (String k : captured) {
                        if (!registry.testCondition(k, ctx)) return false;
                    }
                    return true;
                });
            }

            List<String> actionSpecs = s.getStringList("on-decision");
            List<String> actionKeys = new ArrayList<>();
            for (String spec : actionSpecs) {
                String k = bindAction(spec);
                if (k != null) actionKeys.add(k);
            }
            if (bound.add("a:" + key)) {
                List<String> captured = List.copyOf(actionKeys);
                registry.registerAction(key, ctx -> {
                    for (String k : captured) registry.runAction(k, ctx);
                });
            }
        }
    }

    /** 将 profile 中 require / on-decision 的文本定义展开为已绑定的键。 */
    public void expand(AiProfile profile, List<String> skillNames) {
        for (String s : skillNames) {
            String k = bindCondition("skill:" + s);
            if (k != null) profile.require.add(k);
            String a = bindAction("skill:" + s);
            if (a != null) profile.onDecision.add(a);
        }
    }

    /**
     * 展开 profile 的事件触发器：把 require / do 中的文本定义替换为已绑定的键。
     * 原地修改，便于在 loadProfiles 阶段一次性完成解析。
     */
    public void expandTriggers(AiProfile profile) {
        if (profile.triggers.isEmpty()) return;
        for (var entry : profile.triggers.entrySet()) {
            AiProfile.TriggerSpec spec = entry.getValue();
            List<String> require = new ArrayList<>();
            for (String s : spec.require) {
                String k = bindCondition(s);
                if (k != null) require.add(k);
            }
            List<String> actions = new ArrayList<>();
            for (String s : spec.actions) {
                String k = bindAction(s);
                if (k != null) actions.add(k);
            }
            spec.require.clear();
            spec.require.addAll(require);
            spec.actions.clear();
            spec.actions.addAll(actions);
        }
    }

    /** 保留引号内的空格，其余按空白切分。 */
    private static List<String> split(String spec) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < spec.length(); i++) {
            char c = spec.charAt(i);
            if (c == '"') {
                quoted = !quoted;
                continue;
            }
            if (Character.isWhitespace(c) && !quoted) {
                if (!cur.isEmpty()) {
                    out.add(cur.toString());
                    cur.setLength(0);
                }
            } else {
                cur.append(c);
            }
        }
        if (!cur.isEmpty()) out.add(cur.toString());
        return out;
    }

    private void warn(String msg) {
        warnings.add(msg);
        if (log != null) log.warning("[技能] " + msg);
    }
}
