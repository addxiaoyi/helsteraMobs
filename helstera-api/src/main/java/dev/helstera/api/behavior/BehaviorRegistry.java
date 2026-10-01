package dev.helstera.api.behavior;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;

/**
 * 行为注册表：注册自定义条件、动作与状态（AI 用）。
 * 线程约束：注册任意线程；执行在主线程调度内。
 */
public interface BehaviorRegistry {

    /** 注册命名条件：context -> 是否满足。 */
    void registerCondition(String name, BiPredicate<Object, Object> condition);

    /** 注册命名动作：执行器。 */
    void registerAction(String name, BiConsumer<Object, Object> action);

    /** 注册命名状态处理器。 */
    void registerState(String name, Object stateHandler);

    boolean hasCondition(String name);

    boolean hasAction(String name);

    Collection<String> conditionNames();

    Collection<String> actionNames();
}
