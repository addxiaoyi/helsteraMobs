package dev.helstera.api.behavior;

import org.bukkit.entity.Entity;

import java.util.Collection;

/**
 * 阵营表：判定两个实体之间是敌是友。
 *
 * <p><b>为何要这层抽象</b>：阵营不是插件内部的实现细节，它同时影响四层——
 * 目标选择（不打同伙）、伤害过滤（同伙免伤）、命令（查询/改派）、配置（声明）。
 * 若让 {@code helstera-ai} 的具体实现直接渗进这几层，换一套阵营规则
 * （例如按队伍、护队关系、动态敌对）就得改遍全部调用点。这里只定契约。</p>
 *
 * <p><b>默认语义：无阵营 = 与所有人敌对</b>。这让「不配置阵营」的现有配置
 * 行为完全不变——绝大多数生物不写 faction 就是打一切。若反过来默认成
 * 「无阵营 = 中立」，会给所有存量配置套上一层意料之外的免伤。</p>
 *
 * <p>实现必须无副作用且主线程安全：目标选择在决策节拍内逐帧调用。</p>
 */
public interface FactionTable {

    /**
     * 取实体所属阵营名。
     *
     * @return 阵营名；未归队返回 null（视为与所有人敌对）
     */
    String factionOf(Entity entity);

    /**
     * 两个实体是否属于同盟（互不攻击）。
     *
     * <p>「同阵营」必然是同盟；跨阵营是否同盟由各阵营的 allies 关系决定。
     * 关系应视为<b>对称</b>：A 的盟友里有 B，则 B 的盟友里也应有 A。
     * 实现需自行保证，调用方不重复判定。</p>
     *
     * <p>任一方无阵营时返回 false：没归队的人不属于任何同盟。</p>
     */
    boolean allied(Entity a, Entity b);

    /**
     * 两个实体是否可互相攻击。等价于 {@code !allied}，单独给出是为了让调用点
     * 读起来是业务语言而不是双重否定。
     */
    default boolean hostile(Entity a, Entity b) {
        return !allied(a, b);
    }

    /**
     * 已登记的阵营名（不含玩家阵营等内建项）。
     *
     * <p>供 {@code /helstera faction list} 与配置校验使用；返回不可变集合。</p>
     */
    Collection<String> names();
}