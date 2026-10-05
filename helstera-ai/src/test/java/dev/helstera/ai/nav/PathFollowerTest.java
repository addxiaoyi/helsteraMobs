package dev.helstera.ai.nav;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 路径跟随器测试。
 *
 * <p>时间通过参数注入而非 {@code System.currentTimeMillis()}，因此可以直接推进时钟
 * 验证节流与卡死检测——否则只能靠 sleep，既慢又不稳定。</p>
 *
 * <p>重点是三件会「悄悄退化」的事：首次必须能寻路（节流初值不能把第一次也压住）、
 * 卡死必须能侧移（仅重算对墙后目标无效）、路点 key 解析失败不得把生物永久钉死。</p>
 */
class PathFollowerTest {

    /** 极简节点：key 形如 "x,z"，邻居线性相邻。 */
    private static NavNode node(int x, int z) {
        return new NavNode() {
            @Override public String key() { return x + "," + z; }
            @Override public boolean walkable() { return true; }
            @Override public List<NavNode> neighbors() { return List.of(); }
            @Override public double costTo(NavNode to) { return 1.0; }
            @Override public double heuristicTo(NavNode target) { return 0.0; }
        };
    }

    @Test
    @DisplayName("首次调用即可寻路，节流不应压住第一次")
    void firstRepathAllowedImmediately() {
        PathFollower f = new PathFollower();
        // 节流初值若不特殊处理，第一次会被判「距上次寻路不足 2 秒」而空走
        assertTrue(f.shouldRepath(0L), "首次必须允许寻路");
    }

    @Test
    @DisplayName("节流窗口内不重复寻路，窗口过后放行")
    void repathIsThrottled() {
        PathFollower f = new PathFollower();
        f.shouldRepath(0L);
        f.onRepathed(1000L);
        assertFalse(f.shouldRepath(1500L), "1.5s 时距上次寻路仅 0.5s，不该重算");
        assertTrue(f.shouldRepath(3000L), "3s 时已过 2s 节流窗口");
    }

    @Test
    @DisplayName("卡死检测：连续无位移才判定，位移即刷新")
    void stuckDetectionNeedsRealStall() {
        PathFollower f = new PathFollower();
        // 先建立基准位置
        f.recordProgress(0L, 0, 0);
        assertFalse(f.isStuck(1500L), "1.5s 未达阈值");
        assertTrue(f.isStuck(2500L), "2.5s 原地不动应判定卡死");
        // 一旦产生位移就解除
        f.recordProgress(2500L, 1.0, 0);
        assertFalse(f.isStuck(3000L), "有推进后不应再判卡死");
    }

    @Test
    @DisplayName("微小抖动不算推进")
    void tinyJitterIsNotProgress() {
        PathFollower f = new PathFollower();
        f.recordProgress(0L, 0, 0);
        f.recordProgress(1000L, 0.01, 0.01); // 小于 PROGRESS_EPS
        assertTrue(f.isStuck(2500L), "原地抖动应视为未推进");
    }

    @Test
    @DisplayName("侧移请求只发一次，且会清空路径并松开节流")
    void sidestepIsOneShotAndReleasesThrottle() {
        PathFollower f = new PathFollower();
        f.setPath(List.of(node(0, 0), node(1, 0)));
        f.onRepathed(1000L);

        f.requestSidestep(2500L);
        assertTrue(f.consumeSidestep(), "应产生一次侧移请求");
        assertFalse(f.consumeSidestep(), "侧移请求应被消费后清空");
        assertFalse(f.hasPath(), "侧移后应清空旧路径，否则会继续撞墙");
        assertTrue(f.shouldRepath(2500L), "侧移后应松开节流，立即重新寻路");
    }

    @Test
    @DisplayName("路点游标从 1 起步，抵达后推进，走完返回 null")
    void waypointCursorAdvances() {
        PathFollower f = new PathFollower();
        f.setPath(List.of(node(0, 0), node(3, 0), node(6, 0)));
        // 第 0 个路点是自身所在格子，游标从 1 开始
        assertEquals("3,0", f.nextWaypoint().key());

        assertFalse(f.advanceIfReached(0, 0), "离路点还远，不该推进");
        assertTrue(f.advanceIfReached(3, 0), "抵达 3,0 应推进");
        assertEquals("6,0", f.nextWaypoint().key());

        assertTrue(f.advanceIfReached(6, 0), "抵达 6,0 应推进");
        assertNull(f.nextWaypoint(), "路径走完应返回 null，调用方据此直连目标");
    }

    @Test
    @DisplayName("空路径安全：游标不越界，无路点可取")
    void emptyPathIsSafe() {
        PathFollower f = new PathFollower();
        f.setPath(List.of());
        assertFalse(f.hasPath());
        assertNull(f.nextWaypoint());
        f.setPath(null);
        assertFalse(f.hasPath());
        assertNull(f.nextWaypoint());
    }

    @Test
    @DisplayName("单节点路径不越界")
    void singleNodePathSafe() {
        PathFollower f = new PathFollower();
        f.setPath(List.of(node(1, 1)));
        // 游标 clamp 到合法范围，不能抛越界
        f.advanceIfReached(1, 1);
        assertNull(f.nextWaypoint());
    }

    @Test
    @DisplayName("key 解析不出坐标时视为已抵达，不得把生物永久钉死")
    void unparsableKeyDoesNotStallForever() {
        NavNode odd = new NavNode() {
            @Override public String key() { return "no-coordinates-here"; }
            @Override public boolean walkable() { return true; }
            @Override public List<NavNode> neighbors() { return List.of(); }
            @Override public double costTo(NavNode to) { return 1.0; }
            @Override public double heuristicTo(NavNode t) { return 0.0; }
        };
        PathFollower f = new PathFollower();
        f.setPath(List.of(node(0, 0), odd));
        assertTrue(f.advanceIfReached(0, 0), "解析失败时应按已抵达处理以免卡死");
    }
}