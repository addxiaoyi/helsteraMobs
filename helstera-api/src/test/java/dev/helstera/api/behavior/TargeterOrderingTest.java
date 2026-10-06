package dev.helstera.api.behavior;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToDoubleFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}