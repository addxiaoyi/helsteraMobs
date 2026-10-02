package dev.helstera.ai;

import dev.helstera.api.behavior.BehaviorContext;
import dev.helstera.api.behavior.BehaviorRegistry;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * 行为注册表实现：注册自定义条件、动作与状态处理器。
 *
 * <p>条件与动作的名称统一小写存储，便于 YAML 配置大小写不敏感匹配。</p>
 */
public final class BehaviorRegistryImpl implements BehaviorRegistry {

    private final Map<String, Predicate<BehaviorContext>> conditions = new ConcurrentHashMap<>();
    private final Map<String, Consumer<BehaviorContext>> actions = new ConcurrentHashMap<>();
    private final Map<String, Object> states = new ConcurrentHashMap<>();
    private volatile java.util.logging.Logger log;

    /** 可选：注入日志，用于记录条件/动作的求值异常。 */
    public void logger(java.util.logging.Logger log) {
        this.log = log;
    }

    private static String key(String name) {
        return name == null ? "" : name.trim().toLowerCase(java.util.Locale.ROOT);
    }

    @Override
    public void registerCondition(String name, Predicate<BehaviorContext> condition) {
        if (name == null || name.isBlank() || condition == null) return;
        conditions.put(key(name), condition);
    }

    @Override
    public void registerAction(String name, Consumer<BehaviorContext> action) {
        if (name == null || name.isBlank() || action == null) return;
        actions.put(key(name), action);
    }

    @Override
    public void registerState(String name, Object stateHandler) {
        if (name == null || name.isBlank()) return;
        states.put(key(name), stateHandler);
    }

    @Override
    public boolean hasCondition(String name) {
        return conditions.containsKey(key(name));
    }

    @Override
    public boolean hasAction(String name) {
        return actions.containsKey(key(name));
    }

    @Override
    public Collection<String> conditionNames() {
        return java.util.List.copyOf(conditions.keySet());
    }

    @Override
    public Collection<String> actionNames() {
        return java.util.List.copyOf(actions.keySet());
    }

    @Override
    public boolean testCondition(String name, BehaviorContext ctx) {
        Predicate<BehaviorContext> c = conditions.get(key(name));
        if (c == null) return false;
        try {
            return c.test(ctx);
        } catch (Throwable t) {
            var l = log;
            if (l != null) l.warning("自定义条件 \"" + name + "\" 抛异常，按 false 处理: " + t);
            return false;
        }
    }

    @Override
    public boolean runAction(String name, BehaviorContext ctx) {
        Consumer<BehaviorContext> a = actions.get(key(name));
        if (a == null) return false;
        try {
            a.accept(ctx);
            return true;
        } catch (Throwable t) {
            var l = log;
            if (l != null) l.warning("自定义动作 \"" + name + "\" 抛异常，已忽略: " + t);
            return false;
        }
    }
}
