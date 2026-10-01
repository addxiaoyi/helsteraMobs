package dev.helstera.runtime.animation;

import dev.helstera.api.Vec3;

/**
 * 单骨骼姿态（相对静止姿势的偏移）。
 * rotation=度；position=模型像素（渲染时 /16）；scale=倍率偏移（0 表示不变）。
 */
public record BonePose(Vec3 rotation, Vec3 position, Vec3 scale) {

    public static final BonePose ZERO = new BonePose(Vec3.ZERO, Vec3.ZERO, Vec3.ZERO);

    public static BonePose lerp(BonePose a, BonePose b, double t) {
        return new BonePose(lerpV(a.rotation, b.rotation, t),
                lerpV(a.position, b.position, t),
                lerpV(a.scale, b.scale, t));
    }

    public BonePose add(BonePose o) {
        return new BonePose(addV(rotation, o.rotation), addV(position, o.position), addV(scale, o.scale));
    }

    private static Vec3 lerpV(Vec3 a, Vec3 b, double t) {
        return new Vec3(a.x() + (b.x() - a.x()) * t, a.y() + (b.y() - a.y()) * t, a.z() + (b.z() - a.z()) * t);
    }

    private static Vec3 addV(Vec3 a, Vec3 b) {
        return new Vec3(a.x() + b.x(), a.y() + b.y(), a.z() + b.z());
    }
}
