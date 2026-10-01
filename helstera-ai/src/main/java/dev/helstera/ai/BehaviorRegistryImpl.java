package dev.helstera.ai;

import dev.helstera.api.behavior.BehaviorRegistry;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;

/**
 * 行为注册表实现：注册自定义条件、动作与状态处理器。
 */
public final class BehaviorRegistryImpl implements BehaviorRegistry {

    private final Map<String, BiPredicate<Object, Object>> conditions = new ConcurrentHashMap<>();
    private final Map<String, BiConsumer<Object, Object>> actions = new ConcurrentHashMap<>();
    private final Map<String, Object> states = new ConcurrentHashMap<>();

    @Override
    public void registerCondition(String name, BiPredicate<Object, Object> condition) {
        conditions.put(name.toLowerCase(), condition);
    }

    @Override
    public void registerAction(String name, BiConsumer<Object, Object> action) {
        actions.put(name.toLowerCase(), action);
    }

    @Override
    public void registerState(String name, Object stateHandler) {
        states.put(name.toLowerCase(), stateHandler);
    }

    public BiPredicate<Object, Object> condition(String name) {
        return conditions.get(name.toLowerCase());
    }

    public BiConsumer<Object, Object> action(String name) {
        return actions.get(name.toLowerCase());
    }

    @Override
    public boolean hasCondition(String name) {
        return conditions.containsKey(name.toLowerCase());
    }

    @Override
    public boolean hasAction(String name) {
        return actions.containsKey(name.toLowerCase());
    }

    @Override
    public Collection<String> conditionNames() {
        return conditions.keySet();
    }

    @Override
    public Collection<String> actionNames() {
        return actions.keySet();
    }
}
