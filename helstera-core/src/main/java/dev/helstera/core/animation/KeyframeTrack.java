package dev.helstera.core.animation;

import dev.helstera.api.Vec3;

import java.util.List;
import java.util.Map;

/**
 * 一条关键帧轨道（某骨骼的 rotation / position / scale）。
 * 帧按 time 升序；value 含义：rotation=度，position=模型像素，scale=倍率。
 */
public record KeyframeTrack(String channel, List<Keyframe> frames) {

    public record Keyframe(double time, Vec3 value, String interp) {
    }

    /**
     * 采样指定时间（线性或阶梯插值）。t 超出范围时取端点值。
     */
    public Vec3 sample(double t, boolean reverse) {
        List<Keyframe> f = frames;
        if (f.isEmpty()) return Vec3.ZERO;
        if (f.size() == 1) return f.get(0).value();
        double time = reverse ? (last().time() - t) : t;
        if (time <= f.get(0).time()) return f.get(0).value();
        if (time >= last().time()) return last().value();
        for (int i = 0; i < f.size() - 1; i++) {
            Keyframe a = f.get(i), b = f.get(i + 1);
            if (time >= a.time() && time <= b.time()) {
                String interp = a.interp() == null ? "linear" : a.interp();
                if (interp.equals("step")) return a.value();
                double span = b.time() - a.time();
                double alpha = span <= 0 ? 0 : (time - a.time()) / span;
                // smoothstep 缓动，让动画过渡更自然
                double s = alpha * alpha * (3 - 2 * alpha);
                return lerp(a.value(), b.value(), s);
            }
        }
        return last().value();
    }

    private Keyframe last() {
        return frames.get(frames.size() - 1);
    }

    private static Vec3 lerp(Vec3 a, Vec3 b, double t) {
        return new Vec3(a.x() + (b.x() - a.x()) * t,
                a.y() + (b.y() - a.y()) * t,
                a.z() + (b.z() - a.z()) * t);
    }

    /** 该骨骼所有通道（rotation/position/scale）。 */
    public record BoneTracks(Map<String, KeyframeTrack> channels) {
    }
}
