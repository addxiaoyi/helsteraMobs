package dev.helstera.ai;

import java.util.List;

/**
 * 骨骼命中盒的几何判定（纯函数，不触达 Bukkit 运行期）。
 *
 * <p>存在的理由：模型 JSON 里的 {@code bone.hitbox} 此前被解析进 API 却无人读取，
 * 攻击命中一律按以模型原点为心的球处理——长矛、巨斧一类的武器骨骼因此判定不出
 * 「挥到了」，哪怕玩家正站在斧刃上。</p>
 *
 * <p>刻意退化为轴对齐盒（AABB），不做 OBB 或胶囊：判定要跟着动画逐 Tick 跑，
 * 而真正的碰撞形状由渲染层的 Interaction 实体承担，这里只需要一个
 * 「够不够得着」的近似。用旋转盒换来的精度不足以抵消每 Tick 的矩阵开销，
 * 反而让行为难以预测。</p>
 */
public final class BoneHitbox {

    /** 一个已换算到世界单位的骨骼命中盒。 */
    public record Box(String bone, double x, double y, double z, double width, double height) {
    }

    private BoneHitbox() {
    }

    /**
     * 点是否落在任一盒内。
     *
     * <p>盒以骨骼世界坐标为中心，水平按 width/2、竖直按 height/2 外扩。
     * 尺寸为 0 或负数视为未配置（直接返回 false），而不是退化成无限薄的片——
     * 那会让一个漏填宽度的配置把命中判定变成「站在骨骼所在平面上就中招」。</p>
     */
    public static boolean anyContains(List<Box> boxes, double px, double py, double pz) {
        if (boxes == null || boxes.isEmpty()) return false;
        for (Box b : boxes) {
            if (b == null) continue;
            double hw = b.width() / 2.0;
            double hh = b.height() / 2.0;
            if (hw <= 0 || hh <= 0) continue;
            if (Math.abs(px - b.x()) <= hw
                    && Math.abs(py - b.y()) <= hh
                    && Math.abs(pz - b.z()) <= hw) {
                return true;
            }
        }
        return false;
    }

    /**
     * 取实体腿部中心作为判定点。
     *
     * <p>用 {@code +0.9} 而不是几何中心：目标是玩家，判定的是「身体是否被扫到」，
     * 而站姿玩家的躯干大致在这个高度。若取几何中心，高举武器的 Boss 挥砍会
     * 判定不到近身玩家的头肩，与手感不符。</p>
     */
    public static double bodyPoint(org.bukkit.Location at) {
        return at.getY() + 0.9;
    }
}
