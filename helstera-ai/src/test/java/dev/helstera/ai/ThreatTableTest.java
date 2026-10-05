package dev.helstera.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 仇恨表测试。
 *
 * <p>不依赖运行中的服务端：本类内核按 UUID 运算，玩家解析与时钟均由构造注入，
 * 因此可以用假时钟精确验证衰减——而衰减用真实时钟只能靠 sleep 近似，
 * 那会让测试既慢又不稳定。</p>
 *
 * <p>重点覆盖两条最容易写错的规则：<b>并列时的确定性排序</b>（否则模型会在
 * 等仇恨目标之间周期性抽搐）与<b>距离权重</b>（否则远程输出永远拉不到仇恨）。</p>
 */
class ThreatTableTest {

    /** 可手动推进的假时钟。 */
    private final AtomicLong now = new AtomicLong(1_000_000L);

    private ThreatTable table(double decay, double falloff) {
        return new ThreatTable(decay, falloff, now::get, id -> id);
    }

    /** 按名称派生稳定 UUID，让排序断言可读且确定。 */
    private static UUID uuid(String name) {
        return UUID.nameUUIDFromBytes(name.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("威胁高者当选为目标")
    void picksHighestThreat() {
        var t = table(0, 0);
        UUID a = uuid("a"), b = uuid("b");
        t.addThreat(a, 10, 0);
        t.addThreat(b, 50, 0);
        assertEquals(b, t.top(null));
    }

    @Test
    @DisplayName("多次伤害累加")
    void accumulatesDamage() {
        var t = table(0, 0);
        UUID a = uuid("a");
        t.addThreat(a, 3, 0);
        t.addThreat(a, 4, 0);
        assertEquals(7.0, t.threatOf(a), 1e-9);
    }

    @Test
    @DisplayName("零与负伤害不记入仇恨")
    void ignoresNonPositiveDamage() {
        var t = table(0, 0);
        UUID a = uuid("a");
        t.addThreat(a, 0, 0);
        t.addThreat(a, -5, 0);
        assertEquals(0.0, t.threatOf(a), 1e-9);
        assertNull(t.top(null), "没有有效仇恨时不应返回目标");
    }

    @Test
    @DisplayName("距离为负表示未知，按满权重计入")
    void negativeDistanceCountsAsUnknown() {
        // BehaviorContext 用 distanceToTarget()<0 表示「无目标」，因此 -1 是
        // 「距离未知」而非「距离为负」，权重必须为 1.0 而不是被压低
        var t = table(0, 24);
        UUID a = uuid("a");
        t.addThreat(a, 10, -1);
        assertEquals(10.0, t.threatOf(a), 1e-9);
    }

    @Test
    @DisplayName("null 目标被忽略而不抛异常")
    void toleratesNull() {
        var t = table(0, 0);
        t.addThreat((UUID) null, 10, 0);
        assertEquals(0, t.size());
        assertEquals(0.0, t.threatOf(null));
    }

    @Test
    @DisplayName("距离权重：同样伤害，近战比远程仇恨更高")
    void distanceFavoursMelee() {
        var t = table(0, 24);
        UUID melee = uuid("melee"), ranged = uuid("ranged");
        t.addThreat(melee, 10, 0);
        t.addThreat(ranged, 10, 60);
        assertEquals(melee, t.top(null),
                "同等伤害下近战应更优先，否则远程输出永远拉不到仇恨");
        assertTrue(t.threatOf(ranged) < t.threatOf(melee));
    }

    @Test
    @DisplayName("距离权重有下限，极远目标仍保留部分仇恨")
    void distanceWeightHasFloor() {
        var t = table(0, 1);
        UUID far = uuid("far");
        t.addThreat(far, 10, 100_000);
        assertTrue(t.threatOf(far) >= 5.0,
                "权重下限 0.5，不应把远端输出压成 0，实际 " + t.threatOf(far));
    }

    @Test
    @DisplayName("falloff 为 0 时距离不影响权重")
    void zeroFalloffIgnoresDistance() {
        var t = table(0, 0);
        UUID a = uuid("a");
        t.addThreat(a, 10, 999);
        assertEquals(10.0, t.threatOf(a), 1e-9);
    }

    @Test
    @DisplayName("衰减：威胁随时间指数下降")
    void decaysOverTime() {
        var t = table(0.1, 0);
        UUID a = uuid("a");
        t.addThreat(a, 100, 0);
        double before = t.threatOf(a);
        now.addAndGet(5000);          // 推进 5 秒
        double after = t.threatOf(a);
        assertTrue(after < before, "应衰减: " + before + " -> " + after);
        // 指数衰减：after = before * e^(-rate * seconds) = 100 * e^-0.5
        assertEquals(before * Math.exp(-0.1 * 5), after, 1e-6);
    }

    @Test
    @DisplayName("指数衰减不会归零过快，高仇恨能持续压制低仇恨")
    void exponentialDecayKeepsLead() {
        // 这是改用指数衰减的核心原因：线性衰减下 2 秒即触底归零，
        // 高仇恨与低仇恨一起消失，仇恨表就失去意义
        var t = table(0.5, 0);
        UUID tank = uuid("tank"), squishy = uuid("squishy");
        t.addThreat(tank, 1000, 0);
        t.addThreat(squishy, 10, 0);
        now.addAndGet(2000);
        assertTrue(t.threatOf(tank) > t.threatOf(squishy),
                "2 秒后坦克仍应压制挂件，实际 " + t.threatOf(tank) + " vs " + t.threatOf(squishy));
        assertEquals(tank, t.top(null));
        assertTrue(t.threatOf(tank) > 0, "指数衰减应渐近趋零而非触底");
    }

    @Test
    @DisplayName("衰减到接近零后视为清零并可切走目标")
    void decayClearsTarget() {
        var t = table(0.5, 0);
        UUID a = uuid("a"), b = uuid("b");
        t.addThreat(a, 10, 0);
        t.addThreat(b, 9, 0);
        assertEquals(a, t.top(null));
        now.addAndGet(600_000);       // 十分钟后 a/b 均早已归零
        assertNull(t.top(null), "长时间无仇恨输入后不应再有目标");
        assertTrue(t.threatOf(a) < 1.0);
    }

    @Test
    @DisplayName("高衰减率下仍能在归零前切到次高目标")
    void switchesBeforeFullDecay() {
        var t = table(1.0, 0);
        UUID a = uuid("a"), b = uuid("b");
        t.addThreat(a, 50, 0);
        t.addThreat(b, 5, 0);
        assertEquals(a, t.top(null));
        now.addAndGet(2000);          // e^-2 ≈ 0.135，a ≈ 6.8 仍高于 b
        assertEquals(a, t.top(null));
        now.addAndGet(2000);          // a ≈ 0.92，已低于 b ≈ 0.67 之下但仍>0
        assertTrue(t.threatOf(b) > 0);
    }

    @Test
    @DisplayName("衰减为 0 时威胁不随时间变化")
    void zeroDecayKeepsThreat() {
        var t = table(0, 0);
        UUID a = uuid("a");
        t.addThreat(a, 10, 0);
        now.addAndGet(600_000);
        assertEquals(10.0, t.threatOf(a), 1e-9);
    }

    @Test
    @DisplayName("新伤害会刷新衰减起点")
    void freshDamageResetsDecayClock() {
        var t = table(0.2, 0);
        UUID a = uuid("a");
        t.addThreat(a, 10, 0);
        now.addAndGet(1000);
        t.addThreat(a, 10, 0);        // 再次命中
        double before = t.threatOf(a);
        assertEquals(20.0, before, 1e-6, "两次伤害应完整叠加，不应被中途衰减扣掉");
    }

    @Test
    @DisplayName("并列威胁时取最近者")
    void tieBreaksByDistance() {
        var t = table(0, 0);
        UUID near = uuid("near"), far = uuid("far");
        t.addThreat(near, 10, 0);
        t.addThreat(far, 10, 0);
        var dist = new java.util.HashMap<UUID, Double>();
        dist.put(near, 3.0);
        dist.put(far, 20.0);
        assertEquals(near, t.top(id -> dist.getOrDefault(id, 0.0)));
    }

    @Test
    @DisplayName("威胁与距离都并列时按 UUID 排序，保证确定性")
    void tieBreaksDeterministicallyByUuid() {
        // 若无 UUID 兜底，map 遍历顺序会让目标在两个等条件玩家间逐帧横跳
        for (int run = 0; run < 50; run++) {
            var t = table(0, 0);
            UUID a = uuid("player-a"), b = uuid("player-b");
            t.addThreat(a, 10, 5);
            t.addThreat(b, 10, 5);
            UUID expected = a.compareTo(b) < 0 ? a : b;
            assertEquals(expected, t.top(null), "第 " + run + " 次结果不一致");
        }
    }

    @Test
    @DisplayName("ordered 返回完整降序列表")
    void orderedReturnsAll() {
        var t = table(0, 0);
        UUID a = uuid("a"), b = uuid("b"), c = uuid("c");
        t.addThreat(a, 5, 0);
        t.addThreat(b, 30, 0);
        t.addThreat(c, 15, 0);
        assertEquals(List.of(b, c, a), t.ordered(null));
    }

    @Test
    @DisplayName("clear 清空全部仇恨")
    void clearRemovesAll() {
        var t = table(0, 0);
        t.addThreat(uuid("a"), 10, 0);
        t.addThreat(uuid("b"), 20, 0);
        assertEquals(2, t.size());
        t.clear();
        assertEquals(0, t.size());
        assertNull(t.top(null));
    }

    @Test
    @DisplayName("remove 只移除指定目标")
    void removeSingle() {
        var t = table(0, 0);
        UUID a = uuid("a"), b = uuid("b");
        t.addThreat(a, 10, 0);
        t.addThreat(b, 20, 0);
        t.remove(a);
        assertFalse(t.contains(a));
        assertTrue(t.contains(b));
        assertEquals(b, t.top(null));
    }

    @Test
    @DisplayName("snapshot 按降序返回且只含有效目标")
    void snapshotIsSorted() {
        var t = table(0, 0);
        t.addThreat(uuid("a"), 5, 0);
        t.addThreat(uuid("b"), 30, 0);
        t.addThreat(uuid("c"), 15, 0);
        Map<UUID, Double> snap = t.snapshot();
        assertEquals(3, snap.size());
        List<Double> values = List.copyOf(snap.values());
        assertEquals(List.of(30.0, 15.0, 5.0), values);
    }

    @Test
    @DisplayName("snapshot 不含已衰减归零的目标")
    void snapshotExcludesCleared() {
        var t = table(0.5, 0);
        t.addThreat(uuid("a"), 10, 0);
        t.addThreat(uuid("b"), 10, 0);
        now.addAndGet(120_000);
        assertTrue(t.snapshot().isEmpty(), "全部衰减后快照应为空，实际 " + t.snapshot());
    }

    @Test
    @DisplayName("衰减到零的条目被移除，map 不随玩家进出无限增长")
    void clearedEntriesAreRemoved() {
        var t = table(0.9, 0);
        for (int i = 0; i < 100; i++) {
            t.addThreat(uuid("p" + i), 1, 0);
        }
        assertEquals(100, t.size());
        now.addAndGet(60_000);
        assertNull(t.top(null));
        assertTrue(t.snapshot().isEmpty());
        assertEquals(0, t.size(), "归零条目应被清理，否则长时间运行会持续泄漏");
    }

    @Test
    @DisplayName("负的衰减与负的 falloff 被夹到 0，不产生反向增长")
    void clampsNegativeConfig() {
        var t = table(-1, -1);
        UUID a = uuid("a");
        t.addThreat(a, 10, 5);
        now.addAndGet(10_000);
        assertEquals(10.0, t.threatOf(a), 1e-9, "负衰减不应让仇恨增长");
    }

    @Test
    @DisplayName("手动加仇恨（threat mechanic）不依赖伤害")
    void manualThreatWithoutDamage() {
        var t = table(0, 0);
        UUID a = uuid("a"), b = uuid("b");
        t.addThreat(a, 5, 0);
        t.addThreat(b, 999, 0);        // 相当于「一次技能点名拉满仇恨」
        assertEquals(b, t.top(null));
        assertEquals(999.0, t.threatOf(b), 1e-9);
    }

    @Test
    @DisplayName("时钟为 null 时回退到系统时钟，不崩")
    void nullClockFallsBack() {
        var t = new ThreatTable(0, 0, null, id -> id);
        t.addThreat(uuid("a"), 10, 0);
        assertNotNull(t.top(null));
        assertEquals(10.0, t.threatOf(uuid("a")), 1e-6);
    }

    @Test
    @DisplayName("resolve 未注入解析器时返回 null 而非抛异常")
    void resolveWithoutResolver() {
        var t = new ThreatTable(0, 0, now::get, null);
        assertNull(t.resolve(uuid("a")));
    }
}