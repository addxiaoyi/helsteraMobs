package dev.helstera.ai.summon;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 召唤物（minion）关系管理：谁召唤了谁、限制递归与数量。
 *
 * <p><b>存在的理由</b>：MM 的 {@code onSummon} 与 {@code summon} 动作会互相触发，
 * 没有递归上限时一份配置就能无限套娃生成——表现为「点一下拉杆生成上千只怪、
 * 服务端直接卡死」。本类把这三道闸门做成<b>纯逻辑</b>，无需服务端即可单测。</p>
 *
 * <p>线程约束：全部为并发容器，可任意线程调用。</p>
 */
public final class MinionService {

    /** 默认递归深度上限：召唤物还能再召唤几层。 */
    public static final int DEFAULT_MAX_DEPTH = 2;
    /** 默认同一召唤主下的召唤物数量上限。 */
    public static final int DEFAULT_MAX_PER_OWNER = 24;

    /** 召唤物实例 -> 召唤者实例。 */
    private final Map<Integer, Integer> parentOf = new ConcurrentHashMap<>();
    /** 召唤主实例 -> 它名下召唤物 id。 */
    private final Map<Integer, List<Integer>> childrenOf = new ConcurrentHashMap<>();

    private int maxDepth = DEFAULT_MAX_DEPTH;
    private int maxPerOwner = DEFAULT_MAX_PER_OWNER;

    /** 设置上限。&lt;=0 回落默认值——非法配置不该导致「无限召唤」。 */
    public void limits(int depth, int perOwner) {
        this.maxDepth = depth > 0 ? depth : DEFAULT_MAX_DEPTH;
        this.maxPerOwner = perOwner > 0 ? perOwner : DEFAULT_MAX_PER_OWNER;
    }

    public int maxDepth() {
        return maxDepth;
    }

    public int maxPerOwner() {
        return maxPerOwner;
    }

    /**
     * 能否再召唤。
     *
     * @param summoner 召唤者实例 id
     * @param summonerDepth 召唤者的递归深度（0 = 召唤主本身）
     * @return 失败原因；可召唤返回 null
     */
    public String whyBlocked(int summoner, int summonerDepth) {
        if (summonerDepth >= maxDepth) {
            return "已达递归深度上限 " + maxDepth;
        }
        List<Integer> kids = childrenOf.get(summoner);
        if (kids != null && kids.size() >= maxPerOwner) {
            return "召唤主 " + summoner + " 的召唤物已达上限 " + maxPerOwner;
        }
        return null;
    }

    /**
     * 登记一次召唤关系。
     *
     * <p>先检查后写入，中间不加锁：并发下最坏情况是短暂超出一个上限，
     * 而不是状态错乱。召唤是低频操作，而严格一致性的开销不值得。</p>
     */
    public String register(int owner, int minion, int minionDepth) {
        String blocked = whyBlocked(owner, minionDepth - 1);
        if (blocked != null) return blocked;
        parentOf.put(minion, owner);
        childrenOf.computeIfAbsent(owner, k -> java.util.Collections.synchronizedList(new ArrayList<>()))
                .add(minion);
        return null;
    }

    /** 某召唤物的深度；召唤主自身为 0。游离实例（无召唤者）为 0。 */
    public int depthOf(int instanceId) {
        int depth = 0;
        int cur = instanceId;
        // 用访问计数上限而非 while(true)：数据一旦成环就会死循环卡死主线程。
        // 成环只可能来自程序缺陷，这里选择「截断」而非抛异常。
        for (int guard = 0; guard < 64; guard++) {
            Integer p = parentOf.get(cur);
            if (p == null || p.equals(cur)) break;
            cur = p;
            depth++;
        }
        return depth;
    }

    /** 召唤物死亡/移除时清理。 */
    public void forget(int minionId) {
        Integer parent = parentOf.remove(minionId);
        if (parent != null) {
            List<Integer> kids = childrenOf.get(parent);
            if (kids != null) kids.removeIf(k -> k == minionId);
        }
        // 自身若是召唤主，也清掉它的孩子列表
        childrenOf.remove(minionId);
    }

    public int minionCount(int owner) {
        List<Integer> kids = childrenOf.get(owner);
        return kids == null ? 0 : kids.size();
    }

    public int totalMinions() {
        return parentOf.size();
    }

    /** 召唤主的全部直系召唤物。 */
    public List<Integer> childrenOf(int owner) {
        List<Integer> kids = childrenOf.get(owner);
        return kids == null ? List.of() : List.copyOf(kids);
    }

    /** 该实例是否为召唤物（有已登记的召唤者）。SUMMON 触发器据此区分召唤与普通生成。 */
    public boolean isMinion(int instanceId) {
        return parentOf.containsKey(instanceId);
    }

    /** 该实例的召唤者 id；非召唤物返回 null。 */
    public Integer parentOf(int instanceId) {
        return parentOf.get(instanceId);
    }

    /**
     * 直接改写父关系，仅供测试构造环路。
     *
     * <p>环路无法经由 {@link #register} 产生——它只允许 owner≠minion 的单向边。
     * 但真实环路可能来自并发顺序或后续代码改动，所以 {@link #depthOf} 仍必须能
     * 处理；没有这个钩子就测不到它。</p>
     */
    void forceParentForTest(int minion, int owner) {
        parentOf.put(minion, owner);
    }

    /** 清空全部关系（reload 用）。 */
    public void clear() {
        parentOf.clear();
        childrenOf.clear();
    }
}