package dev.helstera.ai.nav;

import java.util.List;

/**
 * 寻路节点：把「地图上能站的地方」抽象成一个无 Bukkit 依赖的最小契约。
 *
 * <p>刻意不直接用 {@code Location} 或 {@code Block}：寻路内核是最该被密集单测的
 * 部分，而带上 Bukkit 类型就等于单测必须起服务端。若实现层需要世界，
 * 放在 {@link NavGraph} 的构造里注入即可，不必渗进节点本身。</p>
 */
public interface NavNode {

    /** 稳定唯一键。A* 用它做 map 的键，因此必须与位置一一对应且不随时间变化。 */
    String key();

    /** 是否可通行（无方块阻挡、不是岩浆、允许寻路等）。 */
    boolean walkable();

    /**
     * 可达的相邻节点。
     *
     * <p>返回空列表表示节点已到边界。<b>必须排除不可通行节点</b>，
     * 否则搜索会浪费预算去展开注定失败的分支——这正是预算被提前耗尽的典型原因。</p>
     */
    List<NavNode> neighbors();

    /** 从本节点到 {@code to} 的代价（通常是距离，1 或 √2）。 */
    double costTo(NavNode to);

    /**
     * 到目标的启发式估计，<b>必须可采纳（不高估）</b>。
     *
     * <p>高估会让 A* 退化成「更快但可能绕路」：它会跳过看起来更贵的分支，
     * 于是返回的未必是最短路。高估不保证不完备，但会破坏最优性——
     * 而寻路的用途恰恰是需要一条「不绕远」的路径。</p>
     */
    double heuristicTo(NavNode target);
}