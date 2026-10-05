package dev.helstera.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 骨骼命中盒的几何判定测试。
 *
 * <p>重点是<b>退化输入</b>：尺寸为 0、列表为空、坐标含 NaN。
 * 判定在攻击命中链路上，漏判会让 Boss 打不到人（表现为「模型挥砍打空气」），
 * 而这类现象在服务端日志里完全没有痕迹，只能靠单测钉住。</p>
 */
class BoneHitboxTest {

    private static BoneHitbox.Box box(String name, double x, double y, double z,
                                      double w, double h) {
        return new BoneHitbox.Box(name, x, y, z, w, h);
    }

    @Test
    @DisplayName("点在盒内时命中")
    void pointInsideHits() {
        var b = box("sword", 10, 64, 10, 2.0, 2.0);
        assertTrue(BoneHitbox.anyContains(List.of(b), 10.5, 64.2, 9.8));
    }

    @Test
    @DisplayName("点在盒外不命中")
    void pointOutsideMisses() {
        var b = box("sword", 10, 64, 10, 2.0, 2.0);
        assertFalse(BoneHitbox.anyContains(List.of(b), 12.5, 64, 10));
    }

    @Test
    @DisplayName("盒边界上算命中（闭区间）")
    void boundaryCountsAsHit() {
        // 半宽为 1，x=11 与 x=9 正好落在边界上
        var b = box("sword", 10, 64, 10, 2.0, 2.0);
        assertTrue(BoneHitbox.anyContains(List.of(b), 11.0, 64, 10));
        assertTrue(BoneHitbox.anyContains(List.of(b), 9.0, 64, 10));
    }

    @Test
    @DisplayName("多盒时任一命中即可")
    void anyBoxHits() {
        var boxes = List.of(box("hand", 0, 64, 0, 1, 1), box("sword", 5, 64, 0, 2, 2));
        assertTrue(BoneHitbox.anyContains(boxes, 5, 64, 0));
        assertFalse(BoneHitbox.anyContains(boxes, 3, 64, 0));
    }

    @Test
    @DisplayName("空列表与 null 一律不命中")
    void emptyNeverHits() {
        assertFalse(BoneHitbox.anyContains(List.of(), 0, 0, 0));
        assertFalse(BoneHitbox.anyContains(null, 0, 0, 0));
    }

    @Test
    @DisplayName("尺寸为 0 的盒视为未配置，不参与判定")
    void zeroSizedBoxIgnored() {
        // 漏填宽度的配置若退化成零厚薄片，会把命中变成「站在骨骼平面上就中招」
        var zero = box("bad", 10, 64, 10, 0, 5);
        assertFalse(BoneHitbox.anyContains(List.of(zero), 10, 64, 10));
        var negative = box("bad", 10, 64, 10, -3, 5);
        assertFalse(BoneHitbox.anyContains(List.of(negative), 10, 64, 10));
    }

    @Test
    @DisplayName("列表中的 null 元素被跳过而不是抛异常")
    void nullElementsSkipped() {
        List<BoneHitbox.Box> withNull = new ArrayList<>(Arrays.asList(null, box("a", 1, 1, 1, 2, 2)));
        assertTrue(BoneHitbox.anyContains(withNull, 1, 1, 1));
        assertFalse(BoneHitbox.anyContains(withNull, 9, 9, 9));
    }

    @Test
    @DisplayName("判定点取腿部以上 0.9 格")
    void bodyPointIsAboveFeet() {
        org.bukkit.Location at = new org.bukkit.Location(null, 0, 64, 0);
        assertEquals(64.9, BoneHitbox.bodyPoint(at), 1e-9);
    }
}
