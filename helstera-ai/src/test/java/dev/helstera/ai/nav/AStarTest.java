package dev.helstera.ai.nav;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A* 内核测试。
 *
 * <p>用矩形网格做夹具：没有服务端也能覆盖真实算法行为。测试重点是三件事——
 * <b>正确性</b>（路径连续且不穿墙）、<b>预算</b>（超限必须如实报告而非谎称不可达）、
 * <b>性能</b>（A* 的展开数应显著少于无脑遍历，否则预算就没有意义）。</p>
 */
class AStarTest {

    /** 二元墙判定。刻意不用 BiPredicate：那是装箱的，而夹具在热路径上会被反复调用。 */
    @FunctionalInterface
    interface Wall {
        boolean blocked(int x, int y);
    }

    /**
     * 网格夹具。
     *
     * <p>墙判定作为<b>随节点携带</b>的字段，而不是靠继承覆盖：
     * {@code at()} 每次都会 new 出新节点，若墙逻辑挂在类型之外（如共享引用），
     * 新节点就会退回「无墙」的基类行为——表现为墙凭空消失、不可达用例变成可达。
     * 这正是第一版夹具的失败原因。</p>
     */
    private static class Grid implements NavNode {
        final int x, y, w, h;
        final boolean blocked;
        final Wall wall;

        Grid(int x, int y, int w, int h, boolean blocked, Wall wall) {
            this.x = x; this.y = y; this.w = w; this.h = h;
            this.blocked = blocked; this.wall = wall;
        }

        static Grid map(int w, int h) {
            return new Grid(0, 0, w, h, false, null);
        }

        Grid at(int px, int py) {
            if (px < 0 || py < 0 || px >= w || py >= h) return null;
            boolean b = wall != null && wall.blocked(px, py);
            return new Grid(px, py, w, h, b, wall);
        }

        @Override public String key() { return x + "," + y; }
        @Override public boolean walkable() { return !blocked; }

        @Override
        public List<NavNode> neighbors() {
            List<NavNode> out = new ArrayList<>(4);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    if (dx == 0 && dy == 0) continue;
                    Grid n = at(x + dx, y + dy);
                    if (n != null && n.walkable()) out.add(n);
                }
            }
            return out;
        }

        @Override
        public double costTo(NavNode to) {
            Grid o = (Grid) to;
            return (Math.abs(x - o.x) + Math.abs(y - o.y)) == 1 ? 1.0 : Math.sqrt(2.0);
        }

        @Override
        public double heuristicTo(NavNode target) {
            Grid t = (Grid) target;
            return Math.max(Math.abs(x - t.x), Math.abs(y - t.y)) * 1.0;
        }
    }

    @Test
    @DisplayName("空旷网格能找到路径，且相邻节点确实互为邻居")
    void findsPathInOpenGrid() {
        Grid map = Grid.map(20, 20);
        var r = AStar.find(map.at(0, 0), map.at(10, 0), AStar.DEFAULT_BUDGET);
        assertTrue(r.found(), "应找到路径，实际原因: " + r.reason());
        assertEquals(map.at(0, 0).key(), r.path().get(0).key(), "路径应从起点开始");
        assertEquals(map.at(10, 0).key(), r.path().get(r.path().size() - 1).key(),
                "路径应在终点结束");
        for (int i = 1; i < r.path().size(); i++) {
            // 按 key 判连续，不能用 neighbors().contains()：那是引用相等，
            // 而 at() 每次都 new 出新节点，引用永不相等——第一版就是这么写错的
            NavNode prev = r.path().get(i - 1);
            NavNode cur = r.path().get(i);
            boolean adjacent = prev.neighbors().stream().anyMatch(n -> n.key().equals(cur.key()));
            assertTrue(adjacent, "路径不连续: " + prev.key() + " -> " + cur.key());
        }
    }

    private static boolean samePos(NavNode a, NavNode b) {
        return a.key().equals(b.key());
    }

    @Test
    @DisplayName("起点等于终点时返回单节点路径")
    void startEqualsGoal() {
        Grid map = Grid.map(5, 5);
        var r = AStar.find(map.at(2, 2), map.at(2, 2), AStar.DEFAULT_BUDGET);
        assertTrue(r.found());
        assertEquals(1, r.path().size());
    }

    @Test
    @DisplayName("预算耗尽必须如实报告，不能谎称不可达")
    void budgetExhaustionIsReported() {
        // 大网格 + 极小预算：必然展开不完
        Grid map = Grid.map(120, 120);
        var r = AStar.find(map.at(0, 0), map.at(119, 119), 3);
        assertFalse(r.found(), "3 个节点不可能走完 238 步");
        assertNotNull(r.reason());
        assertTrue(r.reason().contains("预算"),
                "超预算与不可达必须可区分，实际原因: " + r.reason());
        assertTrue(r.expanded() <= 3, "展开数不得超预算，实际 " + r.expanded());
    }

    @Test
    @DisplayName("预算非法时回退到默认值而非拒绝服务")
    void invalidBudgetFallsBack() {
        Grid map = Grid.map(30, 30);
        assertTrue(AStar.find(map.at(0, 0), map.at(5, 5), 0).found());
        assertTrue(AStar.find(map.at(0, 0), map.at(5, 5), -10).found());
    }

    @Test
    @DisplayName("A* 展开数显著少于无脑全图遍历（预算因此才有意义）")
    void aStarExpandsFarFewerThanWholeGrid() {
        Grid map = Grid.map(64, 64);
        var r = AStar.find(map.at(0, 0), map.at(20, 0), AStar.DEFAULT_BUDGET);
        assertTrue(r.found());
        int wholeGrid = 64 * 64;
        assertTrue(r.expanded() < wholeGrid / 4,
                "展开 " + r.expanded() + " 相对全图 " + wholeGrid + " 过多，A* 没有发挥作用");
    }

    @Test
    @DisplayName("起终点不可通行直接失败，不做容错")
    void unwalkableEndpointsRejected() {
        Grid map = Grid.map(10, 10);
        NavNode blockedStart = new Grid(0, 0, 10, 10, true, null);
        NavNode normal = map.at(3, 3);
        assertFalse(AStar.find(blockedStart, normal, 64).found());
        assertEquals("起点不可通行", AStar.find(blockedStart, normal, 64).reason());
        assertEquals("终点不可通行", AStar.find(normal, blockedStart, 64).reason());
        assertEquals("起点或终点缺失", AStar.find(null, normal, 64).reason());
    }

    @Test
    @DisplayName("不可达时返回空路径且原因是「不可达」")
    void unreachableReportsUnreachable() {
        // 一堵横贯的墙：左右两半互不连通
        Grid map = new Grid(0, 0, 21, 5, false, (px, py) -> px == 10);
        var r = AStar.find(map.at(0, 0), map.at(20, 0), AStar.DEFAULT_BUDGET);
        assertFalse(r.found(), "被墙隔断，不应找到路径");
        assertEquals("不可达", r.reason());
    }
}