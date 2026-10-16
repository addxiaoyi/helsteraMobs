package dev.helstera.api.behavior;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * 目标排序内核的语义测试。
 *
 * <p>{@link Targeters#orderedBy} 是泛型纯函数，不碰 Bukkit，因此这里能真正
 * 验证排序规则——此前它内联在每个选择器里，只能靠真服观测，而「等分时兜底
 * 顺序写反」在真服上表现为模型偶尔选错目标，几乎无法归因。</p>
 */
class TargeterOrderingTest {

    private record Item(String id, double score, double distance) {
    }

    private static List<String> order(List<Item> in) {
        List<Item> copy = new ArrayList<>(in);
        List<String> out = new ArrayList<>();
        for (Item i : Targeters.orderedBy(copy, Item::score, Item::distance)) {
            out.add(i.id());
        }
        return out;
    }

    @Test
    @DisplayName("分数降序：分高的排前面，与距离无关")
    void higherScoreFirst() {
        var in = List.of(
                new Item("far-high", 90, 50),
                new Item("near-low", 10, 1),
                new Item("mid", 50, 20));
        assertEquals(List.of("far-high", "mid", "near-low"), order(in),
                "目标选择的主依据必须是分数；否则「拉最仇恨的人」会变成「打最近的人」");
    }

    @Test
    @DisplayName("等分时按距离升序兜底，保证结果确定")
    void tiesBreakByDistance() {
        var in = List.of(
                new Item("b", 50, 30),
                new Item("a", 50, 10),
                new Item("c", 50, 20));
        assertEquals(List.of("a", "c", "b"), order(in),
                "等分目标必须按距离定序，否则每 tick 遍历顺序微变会让模型横跳");
    }

    @Test
    @DisplayName("全部等分且等距时保持输入顺序，不自行重排")
    void fullyTiedKeepsInputOrder() {
        var in = List.of(
                new Item("x", 0, 0),
                new Item("y", 0, 0),
                new Item("z", 0, 0));
        assertEquals(List.of("x", "y", "z"), order(in),
                "完全无法区分时保持原序，比任意交换更可预期");
    }

    @Test
    @DisplayName("不修改传入的集合")
    void doesNotMutateInput() {
        var in = new ArrayList<>(List.of(
                new Item("b", 10, 1), new Item("a", 90, 1)));
        List<Item> snapshot = new ArrayList<>(in);
        Targeters.orderedBy(in, Item::score, Item::distance);
        assertEquals(snapshot, in,
                "选择器常被多个动作复用，就地排序会让前一个动作的结果影响后一个");
    }

    @Test
    @DisplayName("空集合与单元素均安全")
    void degenerateInputs() {
        assertEquals(List.of(), order(List.of()));
        assertEquals(List.of("only"),
                order(List.of(new Item("only", 1, 1))));
    }

    @Test
    @DisplayName("负分可用：低血量优先靠负分表达而非特殊分支")
    void negativeScoresWork() {
        var in = List.of(
                new Item("positive", 5, 1),
                new Item("negative", -5, 1));
        assertEquals(List.of("positive", "negative"), order(in));
    }

    @Test
    @DisplayName("比较器对重复对象保持稳定")
    void duplicateItemsStable() {
        var dup = new Item("same", 5, 5);
        var in = List.of(dup, dup, dup);
        assertEquals(3, order(in).size());
    }

    @Test
    @DisplayName("全 NaN 分数不会让排序退化或抛异常")
    void nanScoresDoNotBreak() {
        var in = List.of(
                new Item("nan1", Double.NaN, 1),
                new Item("nan2", Double.NaN, 2),
                new Item("ok", 1, 30));
        List<String> out = order(in);
        assertEquals(3, out.size(), "NaN 输入不得导致丢元素");
        assertEquals("ok", out.get(0), "正常分数应排在 NaN 之前");
    }
    @Test
    @DisplayName("spatial targeters are registered and preserve named arguments")
    void spatialTargetersAreRegistered() {
        assertTrue(Targeters.names().containsAll(List.of(
                "sphere", "ring", "cone", "lineofsight", "los")));
        assertTrue(Targeters.byName("cone") != null);
        assertTrue(Targeters.byName("ring") != null);
    }

    @Test
    @DisplayName("context targeter recognizes parameterized VariableLocation and fails closed when absent")
    void variableLocationTargeterFailsClosedWithoutStoredLocation() {
        var ctx = BehaviorContext.execution(null, null, 1, -1, 0, "skill",
                null, null, List.of(), null);
        assertEquals(List.of(), Targeters.select("@VariableLocation{var=missing}", ctx,
                List.of(), List.of()));
    }

    @Test
    @DisplayName("VariableLocation resolves a stored SkillTree location, not an entity approximation")
    void variableLocationResolvesStoredLocation() {
        var tree = new SkillTreeContext(null, List.of());
        tree.setLocation("mark", new org.bukkit.Location(null, 12, 64, -4));
        var ctx = BehaviorContext.continueExecution(null, null, 1, -1, 0,
                "skill", null, null, List.of(), null, tree);
        var resolved = Targeters.resolveLocation("@VariableLocation{var=mark}", ctx);
        assertTrue(resolved.isPresent());
        assertEquals(12, resolved.orElseThrow().getX());
        assertTrue(Targeters.resolveLocation("@VariableLocation{var=missing}", ctx).isEmpty());
        assertEquals(List.of(), Targeters.select("@VariableLocation{var=mark}", ctx,
                List.of(), List.of()), "Location targeter must not pretend a nearby entity is the saved location");
    }


    @Test
    @DisplayName("targeter filter, count zero, and chain semantics are explicit")
    void targeterFilterCountAndChain() {
        var player = livingProxy(org.bukkit.entity.Player.class);
        var mob = livingProxy(org.bukkit.entity.LivingEntity.class);
        List<org.bukkit.entity.LivingEntity> candidates = List.of(player, mob);

        assertEquals(List.of(player), Targeters.select(
                "@living{filter=players}", (dev.helstera.api.instance.ModelInstance) null, candidates, List.of()));
        assertEquals(List.of(), Targeters.select(
                "@living{count=0}", (dev.helstera.api.instance.ModelInstance) null, candidates, List.of()));
        assertEquals(List.of(player), Targeters.select(
                "@living{filter=players} > @nearest{count=1}", (dev.helstera.api.instance.ModelInstance) null, candidates, List.of()));
    }

    @Test
    @DisplayName("targeter faction filters distinguish exact, allied, and hostile factions")
    void targeterFactionFilters() {
        var guards = livingProxy(org.bukkit.entity.LivingEntity.class);
        var undead = livingProxy(org.bukkit.entity.LivingEntity.class);
        var raiders = livingProxy(org.bukkit.entity.LivingEntity.class);
        var factions = new java.util.IdentityHashMap<org.bukkit.entity.Entity, String>();
        factions.put(guards, "guards");
        factions.put(undead, "undead");
        factions.put(raiders, "raiders");
        Factions.install(new FactionTable() {
            @Override public String factionOf(org.bukkit.entity.Entity entity) { return factions.get(entity); }
            @Override public boolean allied(org.bukkit.entity.Entity a, org.bukkit.entity.Entity b) {
                return alliedFactions(factionOf(a), factionOf(b));
            }
            @Override public boolean alliedFactions(String a, String b) {
                return a != null && b != null && (a.equalsIgnoreCase(b)
                        || (a.equalsIgnoreCase("guards") && b.equalsIgnoreCase("raiders"))
                        || (a.equalsIgnoreCase("raiders") && b.equalsIgnoreCase("guards")));
            }
            @Override public java.util.Collection<String> names() { return java.util.List.of("guards", "undead", "raiders"); }
        });
        try {
            List<org.bukkit.entity.LivingEntity> candidates = List.of(guards, undead, raiders);
            assertEquals(List.of(guards), Targeters.select("@living{filter=faction:guards}",
                    (dev.helstera.api.instance.ModelInstance) null, candidates, List.of()));
            assertEquals(List.of(guards, raiders), Targeters.select("@living{filter=allied:guards}",
                    (dev.helstera.api.instance.ModelInstance) null, candidates, List.of()));
            assertEquals(List.of(undead), Targeters.select("@living{filter=hostile:guards}",
                    (dev.helstera.api.instance.ModelInstance) null, candidates, List.of()));
        } finally {
            Factions.install(null);
        }
    }

    @Test
    @DisplayName("threat targeter applies faction filtering before threat ordering")
    void threatTargeterComposesWithFactionFilter() {
        var guardsLow = livingProxy(org.bukkit.entity.LivingEntity.class);
        var guardsHigh = livingProxy(org.bukkit.entity.LivingEntity.class);
        var undeadHigh = livingProxy(org.bukkit.entity.LivingEntity.class);
        var factions = new java.util.IdentityHashMap<org.bukkit.entity.Entity, String>();
        factions.put(guardsLow, "guards");
        factions.put(guardsHigh, "guards");
        factions.put(undeadHigh, "undead");
        var threat = new java.util.IdentityHashMap<org.bukkit.entity.Entity, Double>();
        threat.put(guardsLow, 5.0);
        threat.put(guardsHigh, 20.0);
        threat.put(undeadHigh, 100.0);
        Factions.install(new FactionTable() {
            @Override public String factionOf(org.bukkit.entity.Entity entity) { return factions.get(entity); }
            @Override public boolean allied(org.bukkit.entity.Entity a, org.bukkit.entity.Entity b) {
                return alliedFactions(factionOf(a), factionOf(b));
            }
            @Override public boolean alliedFactions(String a, String b) {
                return a != null && b != null && a.equalsIgnoreCase(b);
            }
            @Override public java.util.Collection<String> names() { return java.util.List.of("guards", "undead"); }
        });
        Targeters.threatProvider((source, candidate) -> threat.getOrDefault(candidate, 0.0));
        try {
            assertEquals(List.of(guardsHigh, guardsLow), Targeters.select(
                    "@threat{filter=faction:guards}", (dev.helstera.api.instance.ModelInstance) null,
                    List.of(guardsLow, undeadHigh, guardsHigh), List.of()));
            assertEquals(List.of(undeadHigh), Targeters.select(
                    "@threat{filter=hostile:guards}", (dev.helstera.api.instance.ModelInstance) null,
                    List.of(guardsLow, undeadHigh, guardsHigh), List.of()));
        } finally {
            Targeters.threatProvider(null);
            Factions.install(null);
        }
    }

    @Test
    @DisplayName("target targeter keeps non-player LivingEntity targets")
    void targetTargeterUsesLivingEntityContext() {
        var target = (org.bukkit.entity.LivingEntity) Proxy.newProxyInstance(
                org.bukkit.entity.LivingEntity.class.getClassLoader(),
                new Class<?>[]{org.bukkit.entity.LivingEntity.class},
                (proxy, method, args) -> method.getName().equals("getLocation")
                        ? new org.bukkit.Location(null, 2, 3, 4) : defaultValue(method.getReturnType()));
        var context = BehaviorContext.continueExecution(null, target, 1, 2, 0, "skill",
                null, null, List.of(target), null, null);
        assertSame(target, Targeters.select("@target", context, List.of(), List.of()).get(0));
        assertEquals(2, Targeters.resolveLocation("@targetlocation", context).orElseThrow().getX());
    }

    @Test
    @DisplayName("attacker targeter resolves a non-player trigger entity")
    void attackerTargeterUsesTriggerEntity() {
        var attacker = livingProxy(org.bukkit.entity.LivingEntity.class);
        var context = BehaviorContext.continueExecution(null, null, 1, 2, 0,
                "skill", null, attacker, List.of(attacker), null, null);
        assertSame(attacker, Targeters.select("@attacker", context, List.of(), List.of()).get(0));
    }

    @Test
    @DisplayName("owner targeter fails closed when caster is not tameable")
    void ownerTargeterWithoutOwnerIsEmpty() {
        var context = BehaviorContext.continueExecution(null, null, 1, -1, 0,
                "skill", null, null, List.of(), null, null);
        assertEquals(List.of(), Targeters.select("@owner", context, List.of(), List.of()));
    }

    private static <T extends org.bukkit.entity.LivingEntity> T livingProxy(Class<T> type) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(),
                new Class<?>[]{type}, (proxy, method, args) -> {
                    if (method.getName().equals("getType")) return org.bukkit.entity.EntityType.ZOMBIE;
                    if (method.getName().equals("getLocation")) return new org.bukkit.Location(null, 0, 0, 0);
                    return defaultValue(method.getReturnType());
                }));
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == char.class) return (char) 0;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0f;
        return 0d;
    }
}
