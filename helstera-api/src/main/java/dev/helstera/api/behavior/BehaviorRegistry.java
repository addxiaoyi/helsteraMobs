package dev.helstera.api.behavior;

import java.util.Collection;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * 行为注册表：注册自定义条件、动作与状态，供 AI 决策链与技能引擎调用。
 *
 * <p>线程约束：注册任意线程；条件求值与动作执行均在主线程决策节拍内。</p>
 *
 * <p>注意：条件抛异常会被捕获并按 false 处理，单个条件出错不会中断决策。</p>
 */
public interface BehaviorRegistry {

    /** 注册命名条件：上下文 -> 是否满足。 */
    void registerCondition(String name, Predicate<BehaviorContext> condition);

    /** 注册命名动作：执行器。 */
    void registerAction(String name, Consumer<BehaviorContext> action);

    /** 注册命名状态处理器。 */
    void registerState(String name, Object stateHandler);

    boolean hasCondition(String name);

    boolean hasAction(String name);

    Collection<String> conditionNames();

    Collection<String> actionNames();

    /**
     * 求值命名条件。
     *
     * @return 条件不存在、上下文为空或求值抛异常时返回 false
     */
    boolean testCondition(String name, BehaviorContext ctx);

    /**
     * 执行命名动作。动作不存在或抛异常时静默忽略。
     *
     * @return 是否实际执行了动作
     */
    boolean runAction(String name, BehaviorContext ctx);
}
