package dev.helstera.api.behavior;

import dev.helstera.api.instance.ModelInstance;
import org.bukkit.entity.LivingEntity;

import java.util.Collection;
import java.util.List;

/**
 * 目标选择器（Targeter）：从一批候选实体里按某种策略挑出目标。
 *
 * <p>引入这层是为了让技能动作不再只能依赖 {@link BehaviorContext#target()}
 * 这一条"当前仇恨"路径。原先只有单目标概念，做不了范围伤害、随机点名、
 * 集火残血等常见玩法。</p>
 *
 * <p>约定：返回结果已按选择策略排好序，长度为 0 表示没选到目标。
 * 选择器不产生副作用，副作用由调用它的动作负责。</p>
 *
 * <p>线程约束：仅在主线程决策节拍内调用，{@code source} 可能在返回后即销毁。</p>
 */
@FunctionalInterface
public interface Targeter {

    /**
     * @param source     决策发起方（提供位置基准），可为 null
     * @param candidates 候选实体（调用方已做世界/范围过滤）
     * @param args       选择器私有参数，通常为空列表
     * @return 选中的目标，按优先级从高到低
     */
    List<LivingEntity> select(ModelInstance source, Collection<? extends LivingEntity> candidates,
                              List<String> args);
}